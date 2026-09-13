package obsidiananki.cli

import cats.data.NonEmptyVector
import io.circe.parser.parse
import java.nio.file.Paths
import java.time.Instant
import obsidiananki.anki.AnkiNoteId
import obsidiananki.model.{CardKey, CardPath, HeadingPath, HeadingSegment, NoteId, TagCodec}
import obsidiananki.plan.{Agreement, Divergence, FieldRole, HistoryMove}

/** THE LEDGER AS IT LANDS ON DISK — the line format, the path, and the run id.
  *
  * ==Why the wire format is tested apart from what earns a line==
  *
  * `plan/Ledger.test.scala` pins WHICH actions are recorded; this pins WHAT A LINE SAYS. The split
  * follows the code's own: the core derives moves and knows nothing about JSON, the edge stamps and
  * encodes them. Tested together, a change to the derivation and a change to the format would be
  * indistinguishable in a failure.
  *
  * ==Why these assertions name keys as literal strings==
  *
  * The keys are a PUBLISHED INTERFACE. `cli/AsJson.scala` already argues that hand-written encoders
  * exist so that renaming a Scala field cannot silently rename a key somebody's `jq` filter reads,
  * and the argument is stronger for a trail than for a sync result: a ledger is read months after it
  * is written, by which time nothing in the file reveals that the format moved — the old lines still
  * carry the old key and the reader has no reason to doubt them. So changing a key here has to be a
  * deliberate act with a failing test in front of it.
  */
class LedgerFileTest extends munit.FunSuite:

  // ------------------------------------------------------------------ fixtures ----

  def key(id: String, segments: String*): CardKey =
    CardKey(
      NoteId.fromFrontmatter(id).toOption.get,
      CardPath.Headings(
        HeadingPath(
          NonEmptyVector.fromVectorUnsafe(
            segments.toVector.map(s => HeadingSegment.fromExtractedText(s).toOption.get)
          )
        )
      ),
    )

  val strandedKey: CardKey = key("system-design", "essential numbers", "scale")
  val movedKey: CardKey    = key("system-design", "framework", "essential numbers", "scale")

  val at: Instant = Instant.parse("2026-09-13T00:45:00Z")
  val run: RunId  = RunId.of(at, "a3f9c1")

  val moved: HistoryMove = HistoryMove(
    ankiNote = AnkiNoteId(1757),
    from = strandedKey,
    to = movedKey,
    grade = Agreement.NameAndSubstance,
    noteType = "Obsidian Basic",
    // `Bearing` is the role the breadcrumb plays — see `FieldRole.declared`, where
    // `Marker.ContextField` maps to it for every note type this tool declares.
    divergences = Vector(
      Divergence("Context", FieldRole.Bearing, "System design", "System design › Framework")
    ),
    unflagged = true,
  )

  val entry: LedgerEntry = LedgerEntry(at, run, moved)

  def objectOf(line: String) =
    parse(line)
      .getOrElse(fail(s"the line is not JSON: $line"))
      .asObject
      .getOrElse(fail(s"the line is not a JSON object: $line"))

  // ================================================================ the line ====

  test("one entry is one JSON object, on one line, with every key the format promises") {
    val line = LedgerFile.line(entry)
    assert(!line.contains("\n"), s"a ledger line must not contain a newline: $line")

    assertEquals(
      objectOf(line).keys.toVector.sorted,
      Vector(
        "action",
        "ankiNote",
        "at",
        "divergences",
        "from",
        "grade",
        "noteType",
        "run",
        "to",
        "unflagged",
      ),
    )
  }

  test("the scalars say what happened, when, and under which run") {
    val obj = objectOf(LedgerFile.line(entry))

    def str(k: String): String =
      obj(k).flatMap(_.asString).getOrElse(fail(s"no string at '$k'"))

    assertEquals(str("at"), "2026-09-13T00:45:00Z")
    assertEquals(str("run"), "20260913T004500Z-a3f9c1")
    assertEquals(str("grade"), "NameAndSubstance")
    assertEquals(str("noteType"), "Obsidian Basic")
    assertEquals(obj("ankiNote").flatMap(_.asNumber).map(_.toString), Some("1757"))
    assertEquals(obj("unflagged").flatMap(_.asBoolean), Some(true))
  }

  test("`action` is emitted even though a reassignment is the only thing recorded") {
    // A consumer's filter — select(.action == "reassign") — has to keep working on the day a second
    // kind of action earns a line, and a line that never said what it was cannot be told apart from
    // one that did. It is a constant in the encoder rather than a field on the type, because a field
    // that can hold only one value is a field that can one day hold the wrong one.
    assertEquals(
      objectOf(LedgerFile.line(entry))("action").flatMap(_.asString),
      Some("reassign"),
    )
  }

  test("BOTH KEYS travel in both forms — decodable for a program, greppable for a person") {
    val line = LedgerFile.line(entry)
    val obj  = objectOf(line)

    def side(name: String): Map[String, String] =
      obj(name)
        .flatMap(_.asObject)
        .map(_.toMap.flatMap((k, v) => v.asString.map(k -> _)))
        .getOrElse(fail(s"no object at '$name'"))

    assertEquals(side("from").keySet, Set("tag", "note", "path"))
    assertEquals(side("to").keySet, Set("tag", "note", "path"))

    // THE DECODE CLAIM, ROUND-TRIPPED RATHER THAN ASSUMED. The encoded form is on the line so a
    // program can recover the exact key; asserting that it decodes back to the key we started from
    // is what makes that true rather than merely plausible.
    assertEquals(TagCodec.decode(side("from")("tag")), Right(strandedKey))
    assertEquals(TagCodec.decode(side("to")("tag")), Right(movedKey))

    assertEquals(side("from")("path"), strandedKey.path.render)
    assertEquals(side("to")("note"), movedKey.noteId.value)

    // THE GREP CLAIM, ALSO ASSERTED. The whole reason a rendered path is on the line is that
    // somebody hunting a lost card greps for it. Were it encoded, this would fail and nothing else
    // in the suite would notice.
    assert(
      line.contains("essential numbers / scale"),
      s"a person could not grep this line for the card's path: $line",
    )
  }

  test("every divergence is named with what Anki held and what the vault now says") {
    assertEquals(
      objectOf(LedgerFile.line(entry))("divergences").flatMap(_.asArray).map(_.map(_.noSpaces)),
      Some(
        Vector("""{"field":"Context","was":"System design","now":"System design › Framework"}""")
      ),
    )
  }

  test("a move that explained nothing still produces the key, as an empty list") {
    // A grade of Total is reached with no divergence at all — the same path in a different note. The
    // key has to be present anyway: a consumer distinguishing "nothing differed" from "this version
    // does not report what differed" cannot do it if the key is absent in both cases, and the failure
    // would be silent. This is `cli/AsJson.scala`'s always-emit rule, applied here.
    val silent = LedgerFile.line(LedgerEntry(at, run, moved.copy(divergences = Vector.empty)))
    assertEquals(objectOf(silent)("divergences").flatMap(_.asArray), Some(Vector.empty))
  }

  // ============================================================ the document ====

  test("a run's lines are newline-TERMINATED, so an append cannot run onto the previous run") {
    val two = LedgerFile.document(Vector(moved, moved), at, run)
    assertEquals(two, LedgerFile.line(entry) + "\n" + LedgerFile.line(entry) + "\n")
    assert(two.endsWith("\n"))
  }

  test("nothing recorded is nothing appended — not a blank line") {
    assertEquals(LedgerFile.document(Vector.empty, at, run), "")
  }

  test("every line of one run carries that run's instant and id, because the batch is one event") {
    // The interface is what makes this true: the stamp is taken once, for the whole batch, so a
    // caller cannot assemble a run whose lines disagree about when they happened.
    val document = LedgerFile.document(Vector(moved, moved.copy(unflagged = false)), at, run)
    val lines    = document.linesIterator.toVector
    assertEquals(lines.size, 2)
    assertEquals(
      lines.map(l => objectOf(l)("run").flatMap(_.asString)).distinct,
      Vector(Some("20260913T004500Z-a3f9c1")),
    )
    assertEquals(
      lines.map(l => objectOf(l)("at").flatMap(_.asString)).distinct,
      Vector(Some("2026-09-13T00:45:00Z")),
    )
  }

  // ================================================================ the path ====

  test("the ledger lives under this tool's own name, below the home it is given") {
    assertEquals(
      LedgerFile.locate(Paths.get("/Users/someone")).toString,
      "/Users/someone/Library/Application Support/obsidian-anki-sync/ledger.jsonl",
    )
  }

  test("the run id sorts in time order, which is what makes the trail readable") {
    val earlier = RunId.of(Instant.parse("2026-09-13T00:45:00Z"), "zzzzzz")
    val later   = RunId.of(Instant.parse("2026-09-13T00:45:01Z"), "aaaaaa")
    assert(earlier.value < later.value, s"'${earlier.value}' should sort before '${later.value}'")
  }

  test("two runs starting in the same second are still told apart") {
    val together = Instant.parse("2026-09-13T00:45:00Z")
    assertNotEquals(RunId.of(together, "a3f9c1").value, RunId.of(together, "b7e204").value)
  }
