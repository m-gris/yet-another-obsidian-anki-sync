package obsidiananki.locate

import cats.data.NonEmptyVector
import obsidiananki.extract.VaultFile
import obsidiananki.model.*
import obsidiananki.plan.*
import obsidiananki.plan.SectionChain.{NoRecall, NoSectionChain}

/** What `locate` can get wrong is not "it fails" — it is "it opens the wrong thing, or nothing,
  * and says neither". Every test here is aimed at one of those two.
  *
  * The centrepiece is `E1`. It pins the one bug this design went out of its way to make
  * unrepresentable, and it would have been invisible in Marc's vault for as long as his ids
  * stayed lowercase.
  */
class LocateTest extends munit.FunSuite:

  // ---------------------------------------------------------------- fixtures ----

  def key(id: String, segments: String*): CardKey =
    CardKey(
      NoteId.fromFrontmatter(id).toOption.get,
      CardPath.Headings(HeadingPath(
        NonEmptyVector.fromVectorUnsafe(
          segments.toVector.map(s => HeadingSegment.fromExtractedText(s).toOption.get)
        )
      )),
    )

  def body(s: String): Body = Body.fromExtracted(s).getOrElse(fail("empty test body"))

  def spec(k: CardKey): CardSpec =
    CardSpec.TwoField(k, "front", body("back"), TwoFieldDirections.Forward, "Context")

  /** A scan holding exactly these cards at exactly these lines. Completeness is irrelevant on
    * this path — see `Locate.anchor` — so every fixture is a complete one.
    */
  def scanOf(entries: (CardKey, Int)*): VaultScan =
    VaultScan.from(
      entries.toVector.map((k, line) =>
        SourcedSpec(
          spec(k),
          SourceRef("Note.md", line, SourceKind.Heading),
          NoSectionChain,
          NoRecall,
          Vector.empty,
        )
      ),
      Vector.empty,
    )

  def note(id: String): VaultFile =
    VaultFile("Note.md", s"---\nid: $id\n---\n\n# Definition #flashcard\nText.\n")

  val vault: VaultName = VaultName("Study")

  def tagOf(k: CardKey): String = TagCodec.encode(k).value

  // -------------------------------------------------------- the id that is sent ----

  test("E1: the RAW frontmatter id reaches the URI, never the canonical one") {
    // The identity key case-folds; Advanced URI's uid lookup does not. Sending the folded copy
    // would resolve nothing, and a uid that resolves nothing opens NOTHING AT ALL — no note, no
    // notice, no log. This is the test that says the two ids never got swapped.
    val raw = "22E55B6A-Upper"
    val k   = key(raw, "Definition")

    Locate.decide(tagOf(k), vault, Vector(note(raw)), scanOf(k -> 7)) match
      case Located.Placed(uri) =>
        assert(uri.value.contains(s"uid=$raw"), s"canonical id leaked into the URI: ${uri.value}")
        assert(!uri.value.contains("uid=22e55b6a-upper"), s"lowercased uid sent: ${uri.value}")
      case other => fail(s"expected Placed, got $other")
  }

  test("E2: a note is still FOUND by its canonical id, however it is spelled in the file") {
    val k = key("22E55B6A-Upper", "Definition")
    // The tag holds the folded id; the file holds the original. They must still meet.
    assert(Locate.note(k.noteId, Vector(note("22E55B6A-Upper"))).isRight)
  }

  // ------------------------------------------------------------- the anchor ----

  test("E3: a placed card carries its line") {
    val k = key("n1", "Definition")
    Locate.decide(tagOf(k), vault, Vector(note("n1")), scanOf(k -> 42)) match
      case Located.Placed(uri) => assert(uri.value.endsWith("&line=42"), uri.value)
      case other               => fail(s"expected Placed, got $other")
  }

  test("E4: a card whose heading is gone opens the note, and says why") {
    val k = key("n1", "Definition")
    // The note is in the vault; the scan no longer has this card. A reworded heading.
    Locate.decide(tagOf(k), vault, Vector(note("n1")), scanOf()) match
      case Located.Unplaced(uri, Unplaceable.CardGone(_)) =>
        assert(!uri.value.contains("line="), s"an unplaced card must not name a line: ${uri.value}")
      case other => fail(s"expected Unplaced/CardGone, got $other")
  }

  test("E5: line 0 is NOT a line") {
    // `Extractor`'s LineIndex answers 0 when it cannot find a heading. Passed on unchanged that
    // would become `line=0` — a position that is not one, sending an editor somewhere arbitrary
    // while reporting success.
    val k = key("n1", "Definition")
    Locate.decide(tagOf(k), vault, Vector(note("n1")), scanOf(k -> 0)) match
      case Located.Unplaced(uri, Unplaceable.LineUnknown(_)) =>
        assert(!uri.value.contains("line="), uri.value)
      case other => fail(s"expected Unplaced/LineUnknown, got $other")
  }

  test("E6: two cards on one key are reported, not tie-broken") {
    val k = key("n1", "Definition")
    Locate.decide(tagOf(k), vault, Vector(note("n1")), scanOf(k -> 7, k -> 19)) match
      case Located.Unplaced(_, Unplaceable.KeyedTwice(_, lines)) =>
        assertEquals(lines.toVector.map(_.value).sorted, Vector(7, 19))
      case other => fail(s"expected Unplaced/KeyedTwice, got $other")
  }

  // ---------------------------------------------------- refusing to guess ----

  test("E7: no URI is emitted for a note the vault does not have") {
    val k = key("n1", "Definition")
    // A URI naming an unresolvable id is not an error anywhere downstream: the plugin silently
    // does nothing and the add-on cannot observe that it did. This side is the only one that can
    // catch it, so it must.
    Locate.decide(tagOf(k), vault, Vector.empty, scanOf()) match
      case Located.NoteMissing(id) => assertEquals(id.value, "n1")
      case other                   => fail(s"expected NoteMissing, got $other")
  }

  test("E8: a tag this tool did not write does not decode") {
    Locate.decide("src::not a real tag", vault, Vector(note("n1")), scanOf()) match
      case Located.Undecodable(tag, _) => assertEquals(tag, "src::not a real tag")
      case other                       => fail(s"expected Undecodable, got $other")
  }

  // ------------------------------------------------------------ the escaping ----

  test("E9: URI escaping is RFC 3986, not form encoding") {
    val k = key("n1", "Definition")
    Locate.decide(tagOf(k), VaultName("my vault_~x"), Vector(note("n1")), scanOf(k -> 1)) match
      case Located.Placed(uri) =>
        assert(uri.value.contains("vault=my%20vault_~x"), uri.value)
        // `+` would be `java.net.URLEncoder`, which is form encoding. Obsidian would read it
        // back as a literal plus sign in the vault's name.
        assert(!uri.value.contains("+"), s"form-encoded space: ${uri.value}")
      case other => fail(s"expected Placed, got $other")
  }

  test("E10: a non-ASCII vault name survives the round trip") {
    // Marc's vault is named with emoji at both ends, so this is his actual case rather than a
    // contrived one.
    val name = "📖-obsidian-anki-srs-📖"
    val k    = key("n1", "Definition")
    Locate.decide(tagOf(k), VaultName(name), Vector(note("n1")), scanOf(k -> 1)) match
      case Located.Placed(uri) =>
        val sent = uri.value.split("vault=")(1).split("&")(0)
        assertEquals(java.net.URLDecoder.decode(sent, "UTF-8"), name)
      case other => fail(s"expected Placed, got $other")
  }

  // ------------------------------------------------- the machine-readable half ----

  /** The add-on cannot read prose, and it must not have to guess which line of a report is the
    * link. `uriOf` is the seam: exactly the outcomes that opened something answer with a URI.
    */
  test("E11: a URI comes back for exactly the outcomes that open something") {
    val k     = key("n1", "Definition")
    val files = Vector(note("n1"))

    assert(Locate.uriOf(Locate.decide(tagOf(k), vault, files, scanOf(k -> 5))).isDefined, "placed")
    assert(Locate.uriOf(Locate.decide(tagOf(k), vault, files, scanOf())).isDefined, "unplaced still opens the note")
    assert(Locate.uriOf(Locate.decide(tagOf(k), vault, Vector.empty, scanOf())).isEmpty, "no note, no URI")
    assert(Locate.uriOf(Locate.decide("junk", vault, files, scanOf())).isEmpty, "no tag, no URI")
  }
