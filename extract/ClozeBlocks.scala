package obsidiananki.extract

import laika.ast
import obsidiananki.model.BlockAnchor
import obsidiananki.parser.ObsidianSyntax

/** ONE BLOCK THAT HOLDS CLOZE DELETIONS, AND THE NAME ITS AUTHOR GAVE IT.
  *
  * The unit a headingless cloze card is made of. Requested by Marc as *highlight a phrase
  * anywhere and get a card — no heading needed*; see `docs/CLOZE-REDESIGN.md`.
  *
  * THE ANCHOR IS OPTIONAL HERE AND REQUIRED BY THE CALLER, which is the same division
  * `Outline.read` makes: this function reports what a document holds, and refusing is a decision
  * that needs a heading path to name in the message. Reporting an anchorless block rather than
  * dropping it is what lets the author be TOLD; dropping it here would make a highlight that
  * produces nothing look like a highlight that was never seen.
  */
final case class ClozeBlock(anchor: Option[BlockAnchor], block: ast.Block)

object ClozeBlocks:

  /** Every block in this body that holds at least one cloze deletion.
    *
    * ON THE PARSE TREE, NOT ON THE LOWERED CONTENT, and that is forced rather than preferred.
    * An anchor is an anchor because of WHERE it sits — last in its block — and lowering discards
    * position: by then a block is a list of inlines and "at the end of the block" is no longer a
    * question anything can answer. This is the last point at which it can be read.
    *
    * A LIST ITEM IS A BLOCK. Obsidian lets an author put a `^id` on one, and a cloze written in a
    * list is at least as common as one in a paragraph — so the walk descends into anything
    * holding blocks and reports the innermost thing that holds the highlight.
    */
  def in(blocks: Seq[ast.Block]): Vector[ClozeBlock] =
    blocks.toVector.flatMap {
      // A HEADING IS NOT A CLOZE BLOCK, and that is a decision rather than an oversight.
      // Obsidian attaches a `^id` to a block, not to a heading — you reference a heading by its
      // text — so a clozed heading could never carry an anchor and would be refused on every
      // run. A heading that wants cloze cards has the `#flashcard/cloze` route, which keys them
      // by its own path and needs no anchor at all.
      case _: ast.Header => Vector.empty

      // TESTED BEFORE DESCENDING, so the innermost thing holding the highlight is what gets
      // reported. A bullet list holds one only through its item; reporting the list would make
      // a single card showing every item in it, which is the bundling this scoping ends.
      case b if holdsADeletion(b) => Vector(ClozeBlock(anchorOf(b), b))

      case bc: ast.BlockContainer => in(bc.content)

      // A LIST IS NOT A `BlockContainer` IN LAIKA — verified 2026-08-29, and it cost two failing
      // tests to find. `BulletList extends Block with ListContainer`, whose content is
      // `Seq[ListItem]`, and `ListItem` is an `Element` rather than a `Block`. So the descent
      // above walks straight past every list, and a cloze written in one is invisible. The items
      // themselves ARE block containers, which is why the second step is the ordinary descent.
      case lc: ast.ListContainer =>
        in(lc.content.collect { case items: ast.BlockContainer => items }.flatMap(_.content))

      case _ => Vector.empty
    }

  /** Whether a block holds a cloze deletion IN ITS OWN spans, not in a block nested inside it.
    *
    * THE DISTINCTION IS THE WHOLE OF THE RECURSION. A bullet list containing a clozed item holds
    * the highlight only through its item; reporting the list would make one card of every item
    * it contains, and the card would show all of them.
    */
  private def holdsADeletion(block: ast.Block): Boolean = block match
    case sc: ast.SpanContainer => sc.content.exists(isDeletion)
    case _                     => false

  /** RECURSIVE THROUGH SPANS, because `**the ==<<radius>>==**` is a deletion inside emphasis and
    * an author writing one has not asked for anything unusual.
    */
  private def isDeletion(span: ast.Span): Boolean = span match
    case _: ObsidianSyntax.Highlighted => true
    case sc: ast.SpanContainer         => sc.content.exists(isDeletion)
    case _                             => false

  /** The `^abc123` an author wrote at the end of this block, if they wrote one.
    *
    * THE PARSER HAS ALREADY DECIDED IT IS ONE. Its production requires the end of a block, so a
    * `BlockId` node anywhere in the tree is an anchor by construction and this only has to find
    * it. What it must NOT do is re-derive the position, which would be the same rule stated
    * twice in two places and free to drift.
    */
  private def anchorOf(block: ast.Block): Option[BlockAnchor] = block match
    case sc: ast.SpanContainer =>
      sc.content
        .collectFirst { case ObsidianSyntax.BlockId(raw, _) => raw }
        // TOTAL TODAY, AND SAFE IF IT EVER IS NOT. The parser's character set and the model's
        // are the same, so this cannot fail — and `BlockAnchor`'s own note says that if they
        // ever drift, the model is the one that must refuse. Reading the failure as "no anchor"
        // sends such a block to the caller's refusal, which tells the author, rather than
        // writing an identity the codec could not read back.
        .flatMap(BlockAnchor.fromParsed(_).toOption)
    case _ => None
