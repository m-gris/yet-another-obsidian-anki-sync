package obsidiananki.cli

import java.nio.file.Paths

/** Tests for the pure half of reading Obsidian's vault registry.
  *
  * THE FIXTURE MIRRORS THE OBSERVED SHAPE; IT IS NOT COPIED FROM ANY MACHINE. The ids are
  * sixteen hex characters because that is the width observed in a real `obsidian.json`, and
  * the paths are invented. A fixture carrying this machine's actual vault list would make the
  * suite pass or fail according to what happens to be installed.
  *
  * Deliberate properties of the fixture, each load-bearing for one case below:
  *   - the four entries are written OUT of path order, so a passing order assertion cannot be
  *     satisfied by the file's own order;
  *   - `"open": true` appears on exactly ONE entry and the key is ABSENT — not `false` — on
  *     the other three, which is how Obsidian was observed to encode it;
  *   - the siblings `"insider"` and `"cli"` are present at top level, and one unknown key is
  *     added at each of the two levels, so the tolerance case has something to tolerate.
  */
class VaultRegistryTest extends munit.FunSuite:

  /** Stands in for the place `Main` will have read the bytes from. Never opened. */
  val at = Paths.get("/somewhere/obsidian.json")

  val fixture: String =
    """{
      |  "vaults": {
      |    "b3f1a9c2d4e6f708": { "path": "/Users/example/Zettel", "ts": 1700000000001 },
      |    "0a1b2c3d4e5f6071": { "path": "/Users/example/Archive", "ts": 1700000000002, "colour": "blue" },
      |    "9f8e7d6c5b4a3210": { "path": "/Users/example/Notes", "ts": 1700000000003, "open": true },
      |    "1122334455667788": { "path": "/Users/example/Drafts", "ts": 1700000000004 }
      |  },
      |  "insider": true,
      |  "cli": true,
      |  "updates": { "channel": "stable" }
      |}""".stripMargin

  def parsed(json: String): Vector[RegisteredVault] =
    VaultRegistry.parse(at, json) match
      case Right(vaults) => vaults
      case Left(error)   => fail(s"expected a parse, got $error")

  def malformedReason(json: String): String =
    VaultRegistry.parse(at, json) match
      case Left(RegistryError.Malformed(where, reason)) =>
        assertEquals(where, at, "Malformed did not carry the path it was told to read")
        reason
      case other => fail(s"expected Malformed, got $other")

  // ============================================== what the file says ====

  test("every entry is returned, in path-ascending order") {
    val vaults = parsed(fixture)
    assertEquals(
      vaults.map(_.path.toString),
      Vector(
        "/Users/example/Archive",
        "/Users/example/Drafts",
        "/Users/example/Notes",
        "/Users/example/Zettel",
      ),
    )
    assertEquals(
      vaults.map(_.id),
      Vector("0a1b2c3d4e5f6071", "1122334455667788", "9f8e7d6c5b4a3210", "b3f1a9c2d4e6f708"),
    )
    assertEquals(
      vaults.map(_.stamp.rawTs),
      Vector(1700000000002L, 1700000000004L, 1700000000003L, 1700000000001L),
    )
  }

  /** `open` is a PER-ENTRY flag. The assertion is over all four rather than over "the open
    * one", because at-most-one-open is an observation about one file and not a rule this code
    * can enforce.
    */
  test("openInObsidian is true only where the key says so, and false where it is absent") {
    val flags = parsed(fixture).map(v => v.path.toString -> v.openInObsidian).toMap
    assertEquals(
      flags,
      Map(
        "/Users/example/Archive" -> false,
        "/Users/example/Drafts" -> false,
        "/Users/example/Notes" -> true,
        "/Users/example/Zettel" -> false,
      ),
    )
  }

  /** THE CONTROL MUTANT. Every other case here asserts a refusal; if the parser were broken
    * in a way that refused everything, all of them would still pass. This one must SURVIVE.
    *
    * It also states the tolerance contract directly: unknown keys at either level — today's
    * `insider`/`cli`, and whatever a later Obsidian adds — are ignored without complaint.
    */
  test("unknown keys at either level are ignored rather than refused") {
    assertEquals(parsed(fixture).size, 4)
  }

  // ============================================== the refusals ====

  /** A file with no `vaults` key at all is not an obsidian.json, and must not read as "you
    * have no vaults". Asserting the refusal FIRES is the whole point: asserting the result is
    * empty would pass just as well if the parser never looked.
    */
  test("a JSON object with no 'vaults' key is refused, not read as empty") {
    assert(malformedReason("{}").contains("vaults"))
  }

  test("'vaults' present but not an object is refused") {
    assert(malformedReason("""{"vaults": 3}""").contains("vaults"))
    assert(malformedReason("""{"vaults": []}""").contains("vaults"))
  }

  /** Distinguished from the case above by being a RIGHT: Obsidian knows of no vaults, which
    * is a fact about the file rather than a failure to read it. Whether an empty list is
    * usable is not this function's business.
    */
  test("'vaults' as an empty object is an empty list, and a Right") {
    assertEquals(
      VaultRegistry.parse(at, """{"vaults": {}}"""),
      Right(Vector.empty[RegisteredVault]),
    )
  }

  test("an entry with no 'path' is refused") {
    val reason = malformedReason("""{"vaults":{"aaaa000011112222":{"ts":1}}}""")
    assert(reason.contains("aaaa000011112222"), reason)
    assert(reason.contains("path"), reason)
  }

  test("an entry with no 'ts' is refused") {
    val reason = malformedReason("""{"vaults":{"aaaa000011112222":{"path":"/tmp/v"}}}""")
    assert(reason.contains("aaaa000011112222"), reason)
    assert(reason.contains("ts"), reason)
  }

  /** Absent `open` means not open; `open` present and not a Boolean means we are not reading
    * the file we think we are. It must never be quietly read as false.
    */
  test("an entry whose 'open' is not a Boolean is refused") {
    val reason =
      malformedReason("""{"vaults":{"aaaa000011112222":{"path":"/tmp/v","ts":1,"open":"yes"}}}""")
    assert(reason.contains("aaaa000011112222"), reason)
    assert(reason.contains("open"), reason)
  }

  /** JSON can carry a NUL byte in a string; `Paths.get` throws `InvalidPathException` on one.
    * Without the catch, the `Either` return type would be a lie and the exception would
    * escape past every caller's error handling.
    */
  test("a path JSON can encode but Paths.get cannot represent is refused, not thrown") {
    val json = "{\"vaults\":{\"aaaa000011112222\":{\"path\":\"/tmp/a\\u0000b\",\"ts\":1}}}"
    val reason = malformedReason(json)
    assert(reason.contains("aaaa000011112222"), reason)
    assert(reason.contains("path"), reason)
  }

  /** ONE bad entry refuses the WHOLE file. A parser that dropped the offender and returned
    * the rest would hand the caller a silently shortened vault list — the same failure shape
    * as a silently shortened vault scan, one level up.
    */
  test("one malformed entry among four refuses the whole file and names the offender") {
    val broken = fixture.replace(""""ts": 1700000000003,""", """"ts": "recently",""")
    assert(broken != fixture, "the fixture edit did not apply")
    val reason = malformedReason(broken)
    assert(reason.contains("9f8e7d6c5b4a3210"), reason)
    assert(reason.contains("ts"), reason)
  }

  test("input that is not JSON at all is refused") {
    assert(malformedReason("not json").nonEmpty)
  }

  // ============================================== no interpretation ====

  /** The path is converted, never interpreted. `.normalize` would rewrite `/a/../b` to `/b`
    * and `.toAbsolutePath` would resolve a relative path against the process working
    * directory — both are decisions this function has no standing to make, and `VaultRoot.at`
    * already normalises at the one place it matters.
    */
  test("a path is carried as written: '..' is left unresolved") {
    val json = """{"vaults":{"aaaa000011112222":{"path":"/a/../b","ts":1}}}"""
    assertEquals(parsed(json).map(_.path.toString), Vector("/a/../b"))
  }

  // ============================================== locate ====

  test("locate composes the observed relative location under the given home") {
    assertEquals(
      VaultRegistry.locate(Paths.get("/Users/example")).toString,
      "/Users/example/" + VaultRegistry.RelativeLocation,
    )
  }
