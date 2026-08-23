package obsidiananki.config

import obsidiananki.config.LocationPart.*

/** Reading a vault's declaration of how its cards are filed and labelled.
  *
  * ==The property that matters more than any single case below==
  *
  * Every field of a [[VaultLayout]] changes STORED STATE — which deck a card sits in, and what
  * a card field contains. So the parser has two jobs of unequal weight. Reading a declaration
  * correctly is the ordinary one. The heavy one is REFUSING anything it does not fully
  * understand, because the alternative to a refusal is not "no change": it is silently falling
  * back to a default the author did not choose, then moving or rewriting every card in the
  * collection to match it. `deck-form:` misspelt must not read as "no deck preference".
  *
  * That is why the unknown-key and unknown-value tests are not politeness. They are the
  * difference between a typo you fix in five seconds and a collection you have to reconcile by
  * hand.
  */
class VaultLayoutTest extends munit.FunSuite:

  private def parsed(document: String): VaultLayout =
    VaultLayout.parse(document).fold(e => fail(s"expected a layout, got $e"), identity)

  private def refused(document: String): LayoutError =
    VaultLayout.parse(document).fold(identity, l => fail(s"expected a refusal, got $l"))

  // ---------------------------------------------------------------- the default ----

  /** THE ONE THAT PROTECTS EVERY EXISTING VAULT. Decks have always mirrored folder paths under
    * a root called `Obsidian`, and a breadcrumb has always been the heading chain. A vault that
    * declares nothing must sync exactly as it did before this type existed — otherwise the
    * first run after upgrading moves every card and rewrites every note.
    */
  test("the default is what every vault already had") {
    assertEquals(VaultLayout.default.deckRoot.render, "Obsidian")
    assertEquals(VaultLayout.default.deckFrom, Set(Folders))
    assertEquals(VaultLayout.default.contextFrom, Set(Headings))
  }

  test("an empty document declares nothing, and nothing means the default") {
    assertEquals(parsed(""), VaultLayout.default)
    assertEquals(parsed("   \n\n  "), VaultLayout.default)
  }

  /** Declaring one thing must not silently reset the others. A vault that says only where its
    * decks live has said nothing about its breadcrumbs, and its breadcrumbs must not move.
    */
  test("a partial declaration leaves everything it did not mention alone") {
    val layout = parsed("deck-from: [folders, headings]\n")
    assertEquals(layout.deckFrom, Set(Folders, Headings))
    assertEquals(layout.contextFrom, VaultLayout.default.contextFrom)
    assertEquals(layout.deckRoot, VaultLayout.default.deckRoot)
  }

  // ---------------------------------------------------------------- reading it ----

  test("every part can be named, under either key") {
    val layout = parsed(
      """|deck-from: [folders, file, headings]
         |context-from: [folders, file]
         |""".stripMargin
    )
    assertEquals(layout.deckFrom, Set(Folders, FileName, Headings))
    assertEquals(layout.contextFrom, Set(Folders, FileName))
  }

  test("a deck root may be a nested path") {
    assertEquals(parsed("deck-root: Study::Obsidian\n").deckRoot.render, "Study::Obsidian")
  }

  /** An empty list is a real declaration and a legal one: one flat deck, or a card with no
    * breadcrumb at all. It is NOT the same as saying nothing, which means the default.
    */
  test("an empty list means none of the parts, which is different from the default") {
    val layout = parsed("deck-from: []\ncontext-from: []\n")
    assertEquals(layout.deckFrom, Set.empty[LocationPart])
    assertEquals(layout.contextFrom, Set.empty[LocationPart])
  }

  test("part names tolerate the spacing and case a hand-written file will have") {
    assertEquals(parsed("deck-from: [ Folders ,  HEADINGS ]\n").deckFrom, Set(Folders, Headings))
  }

  /** The parts NEST, so naming them in a different order cannot mean anything different. A set
    * is what makes that unrepresentable rather than merely discouraged.
    */
  test("the order parts are written in carries no meaning") {
    assertEquals(
      parsed("deck-from: [headings, folders, file]\n").deckFrom,
      parsed("deck-from: [file, folders, headings]\n").deckFrom,
    )
  }

  test("repeating a part is harmless rather than an error") {
    assertEquals(parsed("deck-from: [folders, folders]\n").deckFrom, Set(Folders))
  }

  // ---------------------------------------------------------------- refusing ----

  /** THE MOST IMPORTANT REFUSAL. Ignoring an unrecognised key would leave the vault on a
    * default it never chose, and the only symptom would be a collection quietly rearranged.
    */
  test("an unknown key is refused, and the message names it") {
    refused("deck-form: [folders]\n") match
      case LayoutError.UnknownKey(key, known) =>
        assertEquals(key, "deck-form")
        assert(known.contains("deck-from"), s"the alternatives must be offered: $known")
      case other => fail(s"expected UnknownKey, got $other")
  }

  test("an unknown part is refused, and the message names it and the alternatives") {
    refused("deck-from: [folders, subfolders]\n") match
      case LayoutError.UnknownPart(key, value, known) =>
        assertEquals(key, "deck-from")
        assertEquals(value, "subfolders")
        assert(known.contains("folders") && known.contains("file") && known.contains("headings"))
      case other => fail(s"expected UnknownPart, got $other")
  }

  test("a blank deck root is refused rather than silently replaced by the default") {
    assert(refused("deck-root: \"\"\n").isInstanceOf[LayoutError.UnusableDeckRoot])
    assert(refused("deck-root: \"::\"\n").isInstanceOf[LayoutError.UnusableDeckRoot])
  }

  test("a part list written as a bare word is refused, saying what shape was wanted") {
    refused("deck-from: folders\n") match
      case LayoutError.WrongValueShape(key, _) => assertEquals(key, "deck-from")
      case other                               => fail(s"expected WrongValueShape, got $other")
  }

  test("a deck root written as a list is refused, saying what shape was wanted") {
    refused("deck-root: [Obsidian, Study]\n") match
      case LayoutError.WrongValueShape(key, _) => assertEquals(key, "deck-root")
      case other                               => fail(s"expected WrongValueShape, got $other")
  }

  test("a document that is not a mapping is refused") {
    assert(refused("- folders\n- headings\n").isInstanceOf[LayoutError.NotAMapping])
    assert(refused("just a sentence\n").isInstanceOf[LayoutError.NotAMapping])
  }

  test("malformed YAML is refused rather than throwing") {
    assert(refused("deck-from: [folders\ncontext-from: ]\n").isInstanceOf[LayoutError.NotAMapping])
  }

  /** A layout file sits in a vault full of markdown whose frontmatter this tool already parses
    * with implicit typing DISABLED, because `2026-08-18` typed as a date once corrupted an id.
    * The same must hold here: a deck root is a name, whatever it looks like.
    */
  test("a deck root that looks like a number or a date survives as written") {
    assertEquals(parsed("deck-root: \"2026\"\n").deckRoot.render, "2026")
    assertEquals(parsed("deck-root: 2026-08-23\n").deckRoot.render, "2026-08-23")
  }
