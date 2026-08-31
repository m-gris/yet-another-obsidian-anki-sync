package obsidiananki.model

import cats.data.NonEmptyVector

class CardKeyTest extends munit.FunSuite:

  // ---------------------------------------------------------------- helpers ----

  def seg(raw: String): HeadingSegment =
    HeadingSegment.fromExtractedText(raw).fold(e => fail(s"bad segment '$raw': $e"), identity)

  def id(raw: String): NoteId =
    NoteId.fromFrontmatter(raw).fold(e => fail(s"bad id '$raw': $e"), identity)

  def key(noteId: String, segments: String*): CardKey =
    CardKey(id(noteId), CardPath.Headings(HeadingPath(NonEmptyVector.fromVectorUnsafe(segments.toVector.map(seg)))))

  def encoded(k: CardKey): String = TagCodec.encode(k).value

  def prop(noteId: String, name: String): CardKey =
    CardKey(
      id(noteId),
      CardPath.Property(
        PropertyName.fromFrontmatter(name).fold(e => fail(s"bad property '$name': $e"), identity)
      ),
    )

  // ------------------------------------------------- which node a card hangs off ----

  /** THE INVARIANT THE WHOLE ENCODING RESTS ON, pinned directly rather than trusted.
    *
    * A path that is not a chain of headings is marked by a LEADING EMPTY TOKEN, which is only
    * unambiguous because no heading path can produce one. That follows from two facts —
    * `HeadingSegment` refuses an empty value, and percent-encoding a non-empty string yields a
    * non-empty string — and the day either changes, every property tag in the collection becomes
    * readable as a heading tag instead. This test is what makes that day loud.
    */
  test("no heading path ever encodes with an empty leading token") {
    val awkward = Vector(
      key("n1", "a"),
      key("n1", ".", "-"),
      key("n1", "0", "1", "2"),
      key("n1", "p"),                       // the property mark, as an ordinary heading
      key("n1", "n"),                       // the note mark, likewise
      key("n1", "b"),                       // and the block mark
      key("n1", "Cost / benefit"),          // a slash INSIDE a segment, percent-encoded
      key("n1", "  spaced  out  "),
      key("n1", "%20"),                     // text that looks like an escape already
      key("n1", "café"),
    )
    awkward.foreach { k =>
      val path = encoded(k).split("::", -1).last
      assert(
        !path.startsWith("/"),
        s"'${k.path.render}' encoded to '$path', which a property path could be mistaken for",
      )
    }
  }

  test("a heading path still encodes exactly as it always did") {
    // The golden file pins every one of these and says DO NOT REGENERATE at the top. If this
    // changes, every identity in every collection changes with it.
    assertEquals(encoded(key("fix-consistency", "consistency", "definition")),
                 "src::fix-consistency::consistency/definition")
    assertEquals(encoded(key("n1", "Cost / benefit")), "src::n1::cost%20%2f%20benefit")
  }

  test("a property card round-trips through its tag") {
    val k = prop("n1", "special-case-of")
    assertEquals(encoded(k), "src::n1::/p/special-case-of")
    assertEquals(TagCodec.decode(encoded(k)), Right(k))
  }

  test("the note-itself card round-trips through its tag") {
    val k = CardKey(id("n1"), CardPath.Note)
    assertEquals(encoded(k), "src::n1::/n")
    assertEquals(TagCodec.decode(encoded(k)), Right(k))
  }

  /** THE FOURTH KIND, AND THE ONE THAT MAKES A CLOZE CARD SCOPED TO A PARAGRAPH POSSIBLE.
    *
    * An `^abc123` written at the end of a block is Obsidian's own name for it, and it is the only
    * thing in a vault that survives editing the block's text — which is exactly the edit a card
    * about that text invites.
    */
  test("a block card round-trips through its tag") {
    val anchor = BlockAnchor.read("abc123").fold(e => fail(s"$e"), identity)
    val k      = CardKey(id("n1"), CardPath.Block(anchor))
    assertEquals(encoded(k), "src::n1::/b/abc123")
    assertEquals(TagCodec.decode(encoded(k)), Right(k))
  }

  /** THE SHAPE THAT KEEPS THE FOUR KINDS APART. A heading path never begins with the separator,
    * because a heading segment is non-empty by construction — so a leading empty token is a shape
    * no heading can take, and each kind claims one letter after it. A block claiming a letter a
    * heading could have produced would file two different cards under one identity.
    */
  test("a block path cannot be confused with a heading path") {
    val anchor = BlockAnchor.read("b").fold(e => fail(s"$e"), identity)
    val block  = encoded(CardKey(id("n1"), CardPath.Block(anchor)))
    val heading = encoded(key("n1", "b"))
    assertNotEquals(block, heading, "a one-letter block anchor collided with a heading of the same name")
    assertEquals(TagCodec.decode(block).map(_.path.render), Right("block '^b'"))
  }

  /** ANKI FOLDS TAG CASE, so an anchor that reached the collection in two spellings would be one
    * tag there and two identities here — which is how a card comes to be created twice.
    */
  test("an anchor is folded to lower case, because Anki folds tag case") {
    val upper = BlockAnchor.read("ABC-123").fold(e => fail(s"$e"), identity)
    assertEquals(upper.value, "abc-123")
  }

  /** REFUSED RATHER THAN CARRIED. A tag hand-edited in Anki can hold anything; an identity that
    * cannot round-trip must fail here rather than name a card nothing can find again.
    */
  test("an anchor outside Obsidian's character set is refused") {
    assert(BlockAnchor.read("a b").isLeft, "a space was accepted into an identity")
    assert(BlockAnchor.read("a/b").isLeft, "a separator was accepted into an identity")
    assert(BlockAnchor.read("").isLeft, "an empty anchor was accepted")
  }

  /** THE COLLISION THIS TYPE EXISTS TO PREVENT.
    *
    * `special-case-of:` in the frontmatter and `# Special-Case-Of` in the body are two different
    * cards, and both spellings were genuinely on the table as ways of writing one relation. A
    * path of bare names would give them one key — and one key for two cards is a duplicate
    * identity, which refuses the entire run until a name is changed.
    */
  test("a property and a heading of the same name are different cards") {
    val fromProperty = prop("n1", "special-case-of")
    val fromHeading  = key("n1", "Special-Case-Of")

    assertNotEquals(fromProperty, fromHeading)
    assertNotEquals(encoded(fromProperty), encoded(fromHeading))
    assertEquals(TagCodec.decode(encoded(fromProperty)), Right(fromProperty))
    assertEquals(TagCodec.decode(encoded(fromHeading)), Right(fromHeading))
  }

  test("a property name is canonicalised, so tidying its spelling is not a new card") {
    assertEquals(prop("n1", "Special-Case-Of"), prop("n1", "special-case-of"))
  }

  /** AN UNKNOWN MARK IS MALFORMED, NOT SILENTLY READ AS A HEADING.
    *
    * A tag this tool cannot place must never be filed as though it belonged somewhere: a
    * mis-filed card is updated in place, and the card it really names is created again beside
    * it. That is the damage the identity design exists to prevent, and a lenient decoder would
    * reintroduce it from the one direction nothing else guards.
    */
  test("a path marked as non-heading but naming an unknown kind is refused, and says why") {
    // THE REASON IS ASSERTED, NOT MERELY THE REFUSAL, and a mutation is what forced that.
    // Deleting the guard entirely leaves all three of these still failing — an empty leading
    // token makes the first heading segment empty, which `HeadingSegment.fromDecoded` rejects
    // on its own. So `isLeft` was true either way and the test could not see the guard at all.
    // What the guard actually buys is the MESSAGE: "names no kind this tool knows" tells a
    // reader their tag is from a newer version or was hand-edited, where "a heading segment is
    // empty" describes a shape they never wrote.
    def reasonOf(tag: String): String =
      TagCodec.decode(tag) match
        case Left(KeyError.MalformedTag(_, reason)) => reason
        case other                                  => fail(s"expected a malformed tag, got $other")

    assert(
      reasonOf("src::n1::/z/whatever").contains("names no kind"),
      s"the unknown mark was refused for the wrong reason: ${reasonOf("src::n1::/z/whatever")}",
    )
    assert(reasonOf("src::n1::/p").contains("names no kind"), "a property mark with no name")
    assert(reasonOf("src::n1::/n/extra").contains("names no kind"), "the note mark takes nothing after it")
  }

  // ---------------------------------------------------------------- NoteId ----

  test("a blank frontmatter id is rejected loudly") {
    assert(NoteId.fromFrontmatter("").isLeft)
    assert(NoteId.fromFrontmatter("   ").isLeft)
  }

  test("a date-like id survives verbatim") {
    // The HOCON hazard in reverse: "2026-08-18" must never become "202608-18".
    assertEquals(id("2026-08-18").value, "2026-08-18")
  }

  // ------------------------------------------------------------ marker stripping ----

  test("every marker the tool publishes is stripped from a heading segment, in full") {
    // Driven off the published table rather than a hand-picked few, so a marker spelling the
    // stripper cannot handle cannot be added without this failing. The longest tokens are four
    // qualifiers deep and no example test reached them.
    Marker.Documented.map(_._1).foreach { token =>
      assertEquals(
        HeadingSegment.fromExtractedText(s"Cost $token").map(_.value),
        Right("cost"),
        s"'$token' left residue in the key, so a heading marked this way is keyed BY ITS " +
          "MARKER — retagging it would orphan the card and mint a duplicate",
      )
    }
  }

  test("changing a marker does NOT change the key") {
    assertEquals(seg("Cost #flashcard/cdd/2way").value, seg("Cost #flashcard/cdd/3way").value)
    assertEquals(seg("Term #flashcard/1way").value, seg("Term #flashcard/2way").value)
  }

  test("a heading consisting only of a marker has no segment and is rejected") {
    assert(HeadingSegment.fromExtractedText("#flashcard/3way").isLeft)
    assert(HeadingSegment.fromExtractedText("   ").isLeft)
    assert(HeadingSegment.fromExtractedText("").isLeft)
  }

  /** A segment is the heading's EXTRACTED text. Extraction is Laika's job, so
    * this test drives the real parser rather than handing raw markdown to a function that
    * never sees it — which is what makes it evidence that the two layers compose.
    */
  test("formatting is not part of the key — a deliberate, documented equality") {
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

  // ------- canonical form ----

  test("case is folded — a deliberate, documented collision") {
    // Anki matches tags case-insensitively, so these ARE the same card. Recognising that
    // here is what stops it surfacing as a false orphan plus a false create, every run.
    assertEquals(seg("Costs").value, seg("costs").value)
    assertEquals(encoded(key("n1", "Costs")), encoded(key("n1", "COSTS")))
  }

  /** RULED. Whitespace is not significant. A markdown linter or formatter normalises a
    * stray double space routinely — markdownlint and prettier both do by default — so if
    * internal whitespace were part of the key, a FORMATTING PASS WOULD SILENTLY ORPHAN
    * CARDS. Same reasoning as the extracted-text rule: the key must survive changes that
    * alter only presentation.
    */
  test("internal whitespace is collapsed — a deliberate, documented equality") {
    assertEquals(seg("Cost  /  benefit").value, seg("Cost / benefit").value)
    assertEquals(seg("a  b").value, seg("a b").value)
    assertEquals(seg("CAP\tTheorem").value, seg("CAP Theorem").value)
    assertEquals(encoded(key("n1", "a  b")), encoded(key("n1", "a b")))
    assertEquals(seg("  Cost / benefit  ").value, seg("Cost / benefit").value,
                 "whitespace at the ENDS changed the key — collapsing runs does not remove it, " +
                 "so a formatter trimming a heading would orphan its card")
  }

  test("unicode is NFC-normalised so equal-looking headings are equal") {
    val precomposed = "Café"  // e + combining acute
    val composed    = "Café"   // precomposed e-acute
    assertEquals(seg(precomposed).value, seg(composed).value)
  }

  // ------- the safe set ----

  test("whitespace is encoded — a tag cannot contain a space") {
    val tag = encoded(key("n1", "CAP Theorem"))
    assert(!tag.contains(" "), s"tag contains a raw space and will be torn in two: $tag")
    assert(tag.contains("%20"), s"space not percent-encoded: $tag")
  }

  test("Anki search wildcards _ and * are encoded") {
    val tag = encoded(key("n1", "a_b*c"))
    assert(!tag.contains("_"), s"raw _ is a single-char wildcard in tag search: $tag")
    assert(!tag.contains("*"), s"raw * is a multi-char wildcard in tag search: $tag")
  }

  test("a literal / inside a heading is encoded, so it cannot pose as the separator") {
    val tag = encoded(key("n1", "Cost / benefit"))
    assert(tag.contains("%2f"), s"literal slash not encoded: $tag")
  }

  test("the id and path separators are the only structural :: in the tag") {
    val tag = encoded(key("weird::id", "a::b"))
    assertEquals(tag.split("::", -1).length, 3, s"colons leaked into a component: $tag")
  }

  // ------- injectivity ----

  test("THE MOTIVATING CASE: '## A/B' and '## A' > '### B' are DIFFERENT keys") {
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

  /** THE ONE TEST IN THIS SUITE THAT TOUCHES A GLOBAL, and it must stay the only one: it swaps
    * the JVM's default locale and restores it in a `finally`. Under a parallel runner this is a
    * hazard.
    *
    * It earns that because the failure it guards is invisible any other way — two machines
    * computing DIFFERENT identities for the same heading, so a vault synced on one and then the
    * other orphans every card and mints replacements.
    */
  test("card identity does not follow the machine's locale") {
    val saved = java.util.Locale.getDefault
    try
      java.util.Locale.setDefault(java.util.Locale.forLanguageTag("tr"))
      assertEquals(
        seg("INDEX").value,
        "index",
        "the ambient locale reached the key: Turkish folds I to a dotless i, so this machine " +
          "and the next would disagree about which card a heading is",
      )
    finally java.util.Locale.setDefault(saved)
  }

  /** A TAG IS NOT TRUSTED TO ARRIVE CANONICAL. It can be typed by a person — `locate` takes one
    * straight off the command line — or hand-edited in Anki's browser, and the Identity FIELD it
    * now also lives in is not case-folded by Anki the way a tag is.
    *
    * Decoding without canonicalising yields a key that never equals the one the vault derives, so
    * the tool reports a card as gone while it is sitting there, and a sync reads it as one orphan
    * plus one create.
    */
  test("a tag decodes to the same key however the author spelled it") {
    assertEquals(TagCodec.decode("src::n1::Coupling"), Right(key("n1", "coupling")))
    assertEquals(TagCodec.decode("src::n1::CAP%20Theorem/Definition"),
                 Right(key("n1", "cap theorem", "definition")))
    assertEquals(TagCodec.decode("src::n1::/p/Special-Case-Of"), Right(prop("n1", "special-case-of")))
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
      // Stripping a marker can SPLICE a new one into existence: the match starts mid-word,
      // and removing it joins "#flash" to "card x". A second strip on the way back would
      // therefore mangle a name that was stored correctly.
      "#flash#flashcardcard x",
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

  test("every owned prefix is recognised, and everything else belongs to the user") {
    // Derived from the set rather than listed again. The CASE half is the real guard: `isOwned`
    // folds what it is handed and compares against the set as written, so a prefix added to the
    // set in mixed case would never match anything.
    OwnedTag.ownedPrefixes.foreach { p =>
      assert(OwnedTag.isOwned(s"$p::x"), s"'$p' is in ownedPrefixes but isOwned refuses it")
      assert(OwnedTag.isOwned(s"${p.toUpperCase}::x"), s"'$p' is not matched case-insensitively")
    }

    // A person's own tags. Wiping these would be the clobbering bug in different clothes.
    assert(!OwnedTag.isOwned("leech"))
    assert(!OwnedTag.isOwned("marked"))
    assert(!OwnedTag.isOwned("Obsidian_to_Anki"))
    assert(!OwnedTag.isOwned("my::own::hierarchy"))
    assert(!OwnedTag.isOwned("source::not-ours"))
  }
