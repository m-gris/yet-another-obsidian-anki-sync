package obsidiananki.extract

import obsidiananki.parser.ObsidianSyntax

/** WHAT COUNTS AS A CLOZE BLOCK, AND WHAT ITS NAME IS.
  *
  * The specification for *highlight a phrase anywhere and get a card*. Written before the
  * implementation, over real markdown rather than hand-built trees, because the two properties
  * being pinned — which block holds a highlight, and where its anchor sits — are both about
  * position, and position is what a hand-built tree quietly gets to decide for itself.
  */
class ClozeBlocksTest extends munit.FunSuite:

  private def blocksOf(markdown: String): Vector[ClozeBlock] =
    val root = ObsidianSyntax.markupParser
      .parse(markdown)
      .fold(e => fail(s"parse: $e"), _.content)
    ClozeBlocks.in(root.content)

  private def anchors(markdown: String): Vector[Option[String]] =
    blocksOf(markdown).map(_.anchor.map(_.value))

  // ══════════════════════════════════════════════════ what is a cloze block ════

  test("a paragraph holding a cloze is one block, named by its anchor") {
    assertEquals(anchors("The ==<<radius>>== is a forearm bone. ^abc123"), Vector(Some("abc123")))
  }

  /** ONE BLOCK, NOT ONE PER HIGHLIGHT. Several deletions in one paragraph are several CARDS of
    * one Anki note — which is what lets Anki keep them off the same day. That packaging is the
    * whole reason the card is scoped to a block rather than to a group.
    */
  test("several clozes in one paragraph are one block") {
    val found = blocksOf("The ==<<1|radius>>== and the ==<<2|ulna>>== are bones. ^abc123")
    assertEquals(found.size, 1, s"expected one block, got ${found.size}")
  }

  test("a paragraph with no cloze is not a block") {
    assertEquals(blocksOf("Just prose, with a ==plain highlight== in it. ^abc123"), Vector.empty)
  }

  test("two paragraphs each holding a cloze are two blocks") {
    assertEquals(
      anchors("The ==<<radius>>== is one. ^one\n\nThe ==<<femur>>== is another. ^two"),
      Vector(Some("one"), Some("two")),
    )
  }

  /** A LIST ITEM IS A BLOCK, and Obsidian lets an anchor sit on one. A cloze written in a list is
    * at least as common as one in a paragraph.
    */
  test("a clozed list item is a block of its own") {
    assertEquals(
      anchors("- the ==<<epidermis>>== ^skin1\n- the ==<<dermis>>== ^skin2"),
      Vector(Some("skin1"), Some("skin2")),
    )
  }

  /** THE RECURSION'S ONE REAL DECISION. A list holds the highlight only through its item, so
    * reporting the LIST would make one card showing every item it contains — which is exactly
    * the bundling this scoping exists to end.
    */
  test("the list itself is not reported, only the item holding the highlight") {
    val found = blocksOf("- plain item\n- the ==<<dermis>>== ^skin2")
    assertEquals(found.size, 1, s"expected the item alone, got ${found.size} blocks")
    assertEquals(found.head.anchor.map(_.value), Some("skin2"))
  }

  // ══════════════════════════════════════════════════ the anchor ════════════════

  /** REPORTED WITHOUT ONE RATHER THAN DROPPED. A block with a cloze and no anchor has no stable
    * identity, and the author must be TOLD that — dropping it here would make a highlight that
    * produces nothing indistinguishable from one that was never seen, which is the silent
    * omission this project designs against. The refusal itself is the caller's, because it needs
    * a heading path to name.
    */
  test("a cloze block with no anchor is reported, carrying none") {
    assertEquals(anchors("The ==<<radius>>== is a forearm bone."), Vector(None))
  }

  /** POSITION IS WHAT MAKES AN ANCHOR AN ANCHOR, and the parser already decided that. This
    * asserts the decision is not re-made here — a caret mid-sentence is arithmetic, and a block
    * holding one has no anchor rather than an anchor called `2`.
    */
  test("a caret in the middle of a block is not its anchor") {
    assertEquals(anchors("The ==<<radius>>== grows as x^2 does."), Vector(None))
  }

  test("an anchor is folded to lower case, as an identity must be") {
    assertEquals(anchors("The ==<<radius>>== is a bone. ^ABC-123"), Vector(Some("abc-123")))
  }
