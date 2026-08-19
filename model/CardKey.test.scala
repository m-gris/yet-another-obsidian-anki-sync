package obsidiananki.model

import cats.data.NonEmptyVector

class CardKeyTest extends munit.FunSuite:

  // ---------------------------------------------------------------- helpers ----

  def seg(raw: String): HeadingSegment =
    HeadingSegment.fromExtractedText(raw).fold(e => fail(s"bad segment '$raw': $e"), identity)

  def id(raw: String): NoteId =
    NoteId.fromFrontmatter(raw).fold(e => fail(s"bad id '$raw': $e"), identity)

  def key(noteId: String, segments: String*): CardKey =
    CardKey(id(noteId), HeadingPath(NonEmptyVector.fromVectorUnsafe(segments.toVector.map(seg))))

  def encoded(k: CardKey): String = TagCodec.encode(k).value

  // ---------------------------------------------------------------- NoteId ----

  test("a blank frontmatter id is rejected loudly") {
    assert(NoteId.fromFrontmatter("").isLeft)
    assert(NoteId.fromFrontmatter("   ").isLeft)
  }

  test("a date-like id survives verbatim") {
    // The HOCON hazard in reverse: "2026-08-18" must never become "202608-18".
    assertEquals(id("2026-08-18").value, "2026-08-18")
  }

  // ------------------------------------------------------- B5: marker stripping ----

  test("B5 REGRESSION: the flashcard marker is stripped from a heading segment") {
    assertEquals(seg("Definition #flashcard/3way").value, "definition")
    assertEquals(seg("Cost #flashcard/3way/all").value, "cost")
    assertEquals(seg("Anatomy of a long bone #flashcard/cloze").value, "anatomy of a long bone")
  }

  test("B5 REGRESSION: changing a marker does NOT change the key") {
    // Retagging 3way -> 3way/all must not orphan the note and mint a duplicate.
    assertEquals(seg("Cost #flashcard/3way").value, seg("Cost #flashcard/3way/all").value)
    assertEquals(seg("Term #flashcard/1way").value, seg("Term #flashcard/2way").value)
  }

  test("a heading consisting only of a marker has no segment and is rejected") {
    assert(HeadingSegment.fromExtractedText("#flashcard/3way").isLeft)
    assert(HeadingSegment.fromExtractedText("   ").isLeft)
    assert(HeadingSegment.fromExtractedText("").isLeft)
  }

  /** B5 rules that a segment is the heading's EXTRACTED text. Extraction is Laika's job, so
    * this test drives the real parser rather than handing raw markdown to a function that
    * never sees it — which is what makes it evidence that the two layers compose.
    */
  test("B5: formatting is not part of the key — a deliberate, documented equality") {
    def segmentOf(headingLine: String): String =
      val doc = obsidiananki.parser.ObsidianSyntax.markupParser
        .parse(s"$headingLine\n\nBody.\n")
        .fold(e => fail(s"parse failed: $e"), _.content)
      val header = doc
        .collect { case s: laika.ast.Section => s.header }
        .headOption
        .getOrElse(fail(s"no Section produced for: $headingLine"))
      seg(header.extractText).value

    // Bolding a word in a heading must never orphan its card.
    assertEquals(segmentOf("## **CAP**"), segmentOf("## CAP"))
    // Same for inline code, and for a wikilink resolving to the same display text.
    assertEquals(segmentOf("## `CAP`"), segmentOf("## CAP"))
    assertEquals(segmentOf("## [[CAP]]"), segmentOf("## CAP"))
  }

  // ------------------------------------------------------- B1: canonical form ----

  test("B1: case is folded — a deliberate, documented collision") {
    // Anki matches tags case-insensitively, so these ARE the same card. Recognising that
    // here is what stops it surfacing as a false orphan plus a false create, every run.
    assertEquals(seg("Costs").value, seg("costs").value)
    assertEquals(encoded(key("n1", "Costs")), encoded(key("n1", "COSTS")))
  }

  /** RULED. Whitespace is not significant. A markdown linter or formatter normalises a
    * stray double space routinely — markdownlint and prettier both do by default — so if
    * internal whitespace were part of the key, a FORMATTING PASS WOULD SILENTLY ORPHAN
    * CARDS. Same reasoning as B5's extracted-text rule: the key must survive changes that
    * alter only presentation.
    */
  test("B1: internal whitespace is collapsed — a deliberate, documented equality") {
    assertEquals(seg("Cost  /  benefit").value, seg("Cost / benefit").value)
    assertEquals(seg("a  b").value, seg("a b").value)
    assertEquals(seg("CAP\tTheorem").value, seg("CAP Theorem").value)
    assertEquals(encoded(key("n1", "a  b")), encoded(key("n1", "a b")))
  }

  test("B1: unicode is NFC-normalised so equal-looking headings are equal") {
    val precomposed = "Café"  // e + combining acute
    val composed    = "Café"   // precomposed e-acute
    assertEquals(seg(precomposed).value, seg(composed).value)
  }

  // ------------------------------------------------------- B1: the safe set ----

  test("B1: whitespace is encoded — a tag cannot contain a space") {
    val tag = encoded(key("n1", "CAP Theorem"))
    assert(!tag.contains(" "), s"tag contains a raw space and will be torn in two: $tag")
    assert(tag.contains("%20"), s"space not percent-encoded: $tag")
  }

  test("B1: Anki search wildcards _ and * are encoded") {
    val tag = encoded(key("n1", "a_b*c"))
    assert(!tag.contains("_"), s"raw _ is a single-char wildcard in tag search: $tag")
    assert(!tag.contains("*"), s"raw * is a multi-char wildcard in tag search: $tag")
  }

  test("B1: a literal / inside a heading is encoded, so it cannot pose as the separator") {
    val tag = encoded(key("n1", "Cost / benefit"))
    assert(tag.contains("%2f"), s"literal slash not encoded: $tag")
  }

  test("B1: the id and path separators are the only structural :: in the tag") {
    val tag = encoded(key("weird::id", "a::b"))
    assertEquals(tag.split("::", -1).length, 3, s"colons leaked into a component: $tag")
  }

  // ------------------------------------------------------- B1: injectivity ----

  test("B1 THE MOTIVATING CASE: '## A/B' and '## A' > '### B' are DIFFERENT keys") {
    // Under an unencoded '/' join these two are byte-identical, and splitting a slashed
    // heading into two nested headings would silently rebind the new card to the old note.
    val flat   = encoded(key("n1", "A/B"))
    val nested = encoded(key("n1", "A", "B"))
    assertNotEquals(flat, nested, "the join character is not injective")
  }

  test("the multi-topic collision the path key exists to prevent") {
    val cap    = encoded(key("fix-multi-topic", "Multi-Topic", "CAP Theorem", "Definition"))
    val quorum = encoded(key("fix-multi-topic", "Multi-Topic", "Quorum", "Definition"))
    assertNotEquals(cap, quorum)
  }

  // ------------------------------------------------------- round trip ----

  test("encode/decode round-trips") {
    val k = key("fix-messaging", "Messaging Patterns", "Cost / benefit")
    assertEquals(TagCodec.decode(encoded(k)), Right(k))
  }

  test("encode/decode round-trips over hostile segments") {
    val nasty = List(
      "CAP Theorem",
      "Cost / benefit",
      "a_b*c",
      "colons::inside",
      "percent % sign",
      "emoji 🧠 here",
      "accented café",
      "trailing spaces   ",
      "100% of the time",
      "a\tb",
    )
    nasty.foreach { s =>
      val k = key("n1", s)
      assertEquals(TagCodec.decode(encoded(k)), Right(k), s"round trip failed for: '$s'")
    }
  }

  test("the encoded tag is left-anchored with the src prefix") {
    assert(encoded(key("n1", "X")).startsWith("src::"))
  }

  test("decoding rejects a malformed tag rather than guessing") {
    assert(TagCodec.decode("not-a-src-tag").isLeft)
    assert(TagCodec.decode("src::onlyid").isLeft)
    assert(TagCodec.decode("src::id::").isLeft)
  }

  // ------------------------------------------------------- OwnedTag ----

  test("only our three prefixes are owned; everything else belongs to the user") {
    assert(OwnedTag.isOwned("src::n1::x"))
    assert(OwnedTag.isOwned("sha::deadbeef"))
    assert(OwnedTag.isOwned("orphaned::n1::x"))

    // A person's own tags. Wiping these would be the clobbering bug in different clothes.
    assert(!OwnedTag.isOwned("leech"))
    assert(!OwnedTag.isOwned("marked"))
    assert(!OwnedTag.isOwned("Obsidian_to_Anki"))
    assert(!OwnedTag.isOwned("my::own::hierarchy"))
    assert(!OwnedTag.isOwned("source::not-ours"))
  }
