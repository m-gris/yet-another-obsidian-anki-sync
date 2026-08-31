package obsidiananki.content

import cats.data.{NonEmptyVector, Validated}
import cats.syntax.all.*
import laika.ast
import obsidiananki.parser.ObsidianSyntax

/* ═══════════════════════════════════════════════════════════════════════════════════════════
 * THE SWAP HAPPENED. THIS IS THE ONE LOWERING, AND IT IS WIRED.
 * ═══════════════════════════════════════════════════════════════════════════════════════════
 *
 * `extract/Extractor.scala` calls `Lower.blocks` — search for `C.Lower.blocks` — so every card
 * this tool produces passes through here. The renderers over its output are called from there
 * and from `extract/Cloze.scala`; `extract/Tables.scala` reaches this package for its `Html`
 * type rather than for the lowering.
 *
 * THIS HEADER USED TO SAY THE OPPOSITE — that nothing in the file was wired, that the
 * hand-written walks still ran beside it, and that `extract/golden/fixture-cards.txt` therefore
 * could not move. That was true when it was written and stopped being true when the swap
 * landed. A comment asserting that its own code is dead is the most expensive kind to leave
 * stale, because it invites a reader to change something they believe nothing depends on.
 *
 * ═══════════════════════════════════════════════════════════════════════════════════════════
 * NAMING INSIDE THIS PACKAGE — §(J) OF `Content.scala` IS NOT VIOLATED HERE, IT IS INVERTED
 * ═══════════════════════════════════════════════════════════════════════════════════════════
 *
 * `Content.scala` §(J) tells CONSUMERS OUTSIDE this package to write `import obsidiananki.content
 * as C` and say `C.Block.Paragraph`. This file is INSIDE the package, so the inversion is the
 * correct reading of that rule rather than an exception to it: our own names (`Block`, `Inline`,
 * `Item`, `Cell`, `Refusal`) are unqualified, and Laika's are always `ast.Paragraph`,
 * `ast.Cell`, `ast.Text`. `import laika.ast`, NEVER `import laika.ast.*` — a wildcard would put
 * `laika.ast.Cell` and our `Cell` in the same scope, which is exactly the collision §(J) exists
 * to keep out of a reader's head.
 *
 * ═══════════════════════════════════════════════════════════════════════════════════════════
 * THE OPERATIVE RULE: MATCH CONCRETE NODE TYPES, NEVER TRAITS
 * ═══════════════════════════════════════════════════════════════════════════════════════════
 *
 * Laika's element hierarchy is OPEN, so a trait match is a bet on what else implements that
 * trait — and it loses in BOTH directions at once, silently dropping what you wanted and
 * silently including what you did not. Two exhibits, both of which survive checking:
 *
 *   - `BulletListItem` and `EnumListItem` are `BlockContainer`s but are NOT `Block`s (they are
 *     `ListItem extends Element`). A walk collecting only `Block` children matched the list and
 *     then discarded every item in it: `- one` `- two` vanished while the prose around it
 *     survived. That is why the two item types below are matched as their own concrete case
 *     classes rather than reached through a container trait.
 *   - `Table` is a `Block` with `ElementTraversal` and `RewritableContainer`. It is NOT an
 *     `ElementContainer` and HAS NO `content` MEMBER AT ALL, so a generic container walk fell
 *     straight past it: a table inside an ordinary body disappeared whole — and the SAME gap
 *     silently disabled the embed rejection, the only safety check the extract layer has.
 *
 * A THIRD EXHIBIT IS OFTEN TOLD HERE AND IS FALSE; DO NOT REPEAT IT. The story that matching
 * the trait `TextContainer` (to rescue code blocks) also caught `Comment` and printed the
 * author's private comments onto cards does not survive checking. Verified 2026-08-20:
 * `laika.ast.Comment` is constructed only by `internal/rst/ExplicitBlockParsers.scala:124` and
 * is unreachable from the Markdown path, and `ObsidianSyntax.ObsidianComment` declares `text`,
 * not `content`, so it is not a `TextContainer` either. Narrowing that trait match cannot have
 * been what stopped the disclosure — the `ObsidianComment` NODE was. The RULE is untouched;
 * only that one exhibit is wrong. (`extract/Extractor.scala:225-228` and `Content.scala` §(B)
 * still carry it; both are outside this slice's file list.)
 *
 * ═══════════════════════════════════════════════════════════════════════════════════════════
 * NEVER CALL LAIKA'S `extractText`, ANYWHERE, FOR ANY BRANCH
 * ═══════════════════════════════════════════════════════════════════════════════════════════
 *
 * `laika/ast/containers.scala:117-121` implements `SpanContainer.extractText` as
 * `content.map { case tc: TextContainer => tc.content; case sc: SpanContainer => sc.extractText;
 * case _ => "" }.mkString` — a trait match with a SILENT `case _ => ""`. Using it inside the one
 * file built to abolish that pattern would ship the defect one level down, where no
 * exhaustiveness check and no refusal can see it. This is why `CodeBlock` below is lowered by
 * requiring every span to be a concrete `ast.Text` rather than by reaching for `extractText` for
 * that one branch.
 *
 * (`AsText.scala` gets a stronger form of the same ban: it does not import `laika` at all, so
 * calling `extractText` there is impossible rather than merely forbidden. Here the ban has to
 * stay prose, because this file must import Laika to match on it.)
 *
 * ═══════════════════════════════════════════════════════════════════════════════════════════
 * WHY A NESTED `Header` FLATTENS RATHER THAN REFUSING — THE FENCE, NOT TASTE
 * ═══════════════════════════════════════════════════════════════════════════════════════════
 *
 * `Content.scala` §(G) lists `Header`/`Title` as "not enumerated", on the grounds that
 * `Extractor.ownBody` stops at the next `Section` and Laika's section rewrite turns every
 * header into one. THAT IS TRUE AT TOP LEVEL ONLY. `SectionBuilder` never recurses — its
 * `buildSections` walks `document.content` and nothing below it — and `atxHeader` is neither
 * `rootOnly` nor `nestedOnly`, so a `Header` survives inside a blockquote or a list item. Since
 * `QuotedBlock` flattens its blocks into the parent vector, such a `Header` arrives here.
 *
 * It flattens to `Block.Paragraph` because `ast.Header` is a `Block with SpanContainer`, so
 * `Extractor.elementText`'s first arm RENDERS its text today. Replacing rendered content with a
 * refusal is an OUTPUT change, which this slice forbids — and the differential test for
 * `> ## X` could not even be written, since the two sides would return different `Either`s.
 * There is a real loss in the flatten (no heading constructor exists, so the level is gone);
 * carrying it is a card-model question, not a lowering question.
 *
 * ═══════════════════════════════════════════════════════════════════════════════════════════
 * `BlockSequence` IS VERIFIED REACHABLE, NOT "UNVERIFIED"
 * ═══════════════════════════════════════════════════════════════════════════════════════════
 *
 * `Content.scala` §(G) hedges that it is unverified whether a `BlockSequence` can survive into
 * a lowered item. It is verified, and the answer is yes at BLOCK level:
 * `internal/markdown/BlockParsers.scala:118-121` `headerOrParagraph` emits
 * `BlockSequence(paragraph, list)` for any paragraph interrupted by a list with no blank line —
 * ordinary authored markdown, not an edge case — and `ListParsers.rewriteItemContent:68-69`
 * unwraps one only in a list item's LEADING position. It gets a named splice case below.
 *
 * ═══════════════════════════════════════════════════════════════════════════════════════════
 * A FLATTEN RECURSES INTO `blocks`; IT NEVER RE-IMPLEMENTS A CHILD WALK
 * ═══════════════════════════════════════════════════════════════════════════════════════════
 *
 * A disposition list is a CLAIM about what children can appear; recursion is a PROOF about
 * them. The span-position flattens (`Deleted`, `SpanLink`, `SpanSequence`) are safe either way,
 * because `Inline` is closed over their children. `QuotedBlock` is the one flatten landing in
 * BLOCK position, where the unanalysed children live — a `Header`, a `BlockSequence`, a nested
 * `QuotedBlock`, a nested `Table` — and that is exactly why it must recurse into this same
 * total function rather than enumerate them a second time.
 *
 * ═══════════════════════════════════════════════════════════════════════════════════════════
 * IDENTITY vs DISPLAY — THE PER-PATH QUALIFIER `Content.scala` §(D) NEEDS
 * ═══════════════════════════════════════════════════════════════════════════════════════════
 *
 * §(D) states the one-way rule: this refactor may turn SILENCE into a REFUSAL, never a refusal
 * into silence or into a card. That is stated without qualification there, and it needs one,
 * because the two paths are not alike:
 *
 *   - On the DISPLAY path (a card's text), silence → refusal is exactly the improvement wanted.
 *   - On the IDENTITY path it is CATASTROPHIC. `Tables.cellSegment → cellSource →
 *     HeadingSegment.fromExtractedText` is frozen and must NEVER route through `Lower` or
 *     `AsText`. There a `Left` is not a loud error — it is an ABSENT KEY, hence an orphan,
 *     hence a `SyncAction.Flag` against a real synced note with review history.
 *
 * `CellDisplay(text: Cell => String)` is TOTAL and `Lower` is PARTIAL. At S9 that forces
 * exactly one of three things: widen `CellDisplay` to carry a failure channel (the only
 * permitted option), swallow the refusal (forbidden by the one-way rule), or hoist it to the
 * section key (which is the hole named next).
 *
 * ═══════════════════════════════════════════════════════════════════════════════════════════
 * THE NINE-CARD ORPHAN HOLE — NAMED HERE, NOT FIXED HERE, AND IT BLOCKS S9
 * ═══════════════════════════════════════════════════════════════════════════════════════════
 *
 * `Extractor.walk:81` records every `buildSpecs` failure as `BuildFailure.KeyKnown` at the
 * SECTION key, while `Tables.cardsForRow:170,190` keys every table card ONE OR TWO SEGMENTS
 * DEEPER. So no table card is ever keyed at the section key. `Planner:211` computes
 * `accountedFor = builtKeys ++ failedKeys` and sends everything else to `SyncAction.Flag`.
 * `dummy-vault/Patterns/Messaging.md § "Cost / benefit"` is exactly nine cards: refuse that
 * section and `VaultScan.failedKeys` gains a key nothing claims while all nine live Anki notes
 * are flagged. Reachable TODAY with one `![[x.png]]` in one cell, against a real collection of
 * 29 synced notes.
 *
 * S8 neither causes nor fixes it — `Lower`'s signature contains no `CardKey`, and the fix lives
 * in `Extractor.scala`, `Tables.scala` and `plan/`, all outside this slice's file list. What
 * S8 does is MULTIPLY ITS TRIGGERS, from three constructs (embed, task list, image) to every
 * construct this file does not model. It must be closed before S9 wires anything.
 *
 * ═══════════════════════════════════════════════════════════════════════════════════════════
 * REFUSAL PRIORITY CHANGES AT S9, AND IT IS RULED RATHER THAN DISCOVERED
 * ═══════════════════════════════════════════════════════════════════════════════════════════
 *
 * `Extractor.bodyText:171-192` runs three separate `collectFirst`s, so today "an embed
 * anywhere" beats "a task list earlier in the document". Document-order accumulation reports
 * whichever comes first in the document instead. This is invisible to
 * `extract/golden/fixture-cards.txt`, which pins a failure's case name and key but never its
 * reason. When S9 collapses a `NonEmptyVector[Refusal]` into `BuildFailure.KeyKnown(reason:
 * String)`, it must JOIN all of them — a `.head` there is a silent loss nothing can see.
 */

/** Why a construct could not be lowered.
  *
  * A CLOSED ENUM, NEVER A BARE `String`. S9 maps this into `SpecError`; a `String` would force
  * a catch-all there and re-import the very defect class this file exists to remove, one layer
  * up.
  *
  * TWO KINDS, and the split is what the payloads are for:
  *
  *   - UNSUPPORTED BY RULING — `Embed`, `TaskList`, `Image`. The AUTHOR has a remedy, and the
  *     payload is the string they grep their own vault for.
  *   - UNRECOGNISED — `UnknownBlock`, `UnknownSpan`. Only a MAINTAINER has a remedy, and the
  *     payload is `productPrefix`, which is the maintainer's parenthetical. Two cases rather
  *     than one because a span surprise inside a cell and a block surprise inside a body are
  *     different fixes.
  *
  * `UnexpectedShape` is the third thing: a construct we DO recognise whose CHILDREN are a shape
  * we do not model — a table cell that is neither one paragraph nor empty, a table head that is
  * not exactly one row.
  *
  * NEVER `node.toString`. Laika's `toString` dumps the whole subtree, so it can print the
  * author's `%%private note%%` into a message that reaches a report. This project has already
  * shipped one comment-onto-a-card disclosure; a refusal message is not the place to ship the
  * second. `productPrefix` is the class name alone.
  *
  * A NAMED LIMIT: NO EXCERPT AND NO LOCATOR SHIPS IN S8. Neither is available at the point of
  * refusal — the lowering sees an element, not a line — and building one is real design work
  * rather than a field. THE CONSTRAINT ON WHOEVER ADDS ONE: an excerpt must be assembled from
  * LOWERED content, AFTER the drop cases have run, and truncated. Assembling it from the Laika
  * node re-discloses exactly the comments the drop list exists to remove.
  */
enum Refusal:
  case Embed(target: String)
  case TaskList
  case Image(target: String)
  case UnexpectedShape(what: String)
  case UnknownBlock(laikaClass: String)
  case UnknownSpan(laikaClass: String)

  /** The construct named the way an AUTHOR would say it, with the maintainer's class name at
    * most a trailing parenthetical.
    */
  def describe: String = this match
    case Embed(t)           => s"an Obsidian embed of '$t'"
    case TaskList           => "a task list"
    case Image(t)           => s"an image '$t'"
    case UnexpectedShape(w) => w
    case UnknownBlock(c)    => s"a construct this tool does not model, in block position ($c)"
    case UnknownSpan(c)     => s"a construct this tool does not model, inside a line ($c)"

/** THE ONE LOWERING from Laika's open element hierarchy into this project's closed algebra.
  *
  * This is the ONLY place in the project where a catch-all over Laika's types may exist, and
  * there are exactly two of them: the final arm of the block match and the final arm of the
  * span match. Both REFUSE, naming the construct. Every other construct has its own arm.
  *
  * REFUSALS ACCUMULATE, in document order, with NO CAP and NO DEDUPE. The deciding reason is
  * the first run over a real 25-note vault: that is where every unmodelled construct surfaces
  * at once, and a single-refusal type turns it into N fix-one-rerun cycles. Implemented with
  * `Validated` over a `NonEmptyVector` internally — `traverse` and `mapN` accumulate, where an
  * `Either` for-comprehension would short-circuit — and `.toEither` at the two public
  * boundaries.
  *
  * `Either`, NEVER `Ior`. A partial result beside a list of errors is the one-way rule's
  * forbidden direction wearing a nicer name: on any refusal, NO CONTENT.
  */
object Lower:

  /** The accumulating half of the two public `Either`s.
    *
    * NAMED LOCALLY RATHER THAN IMPORTED. `cats.data` defines `ValidatedNel` and `ValidatedNec`
    * and, as of cats-core 2.12.0, NO `ValidatedNev` — checked in the sources, not assumed. The
    * `NonEmptyVector` carrier is not negotiable: it is the public refusal type, chosen to match
    * this project's `Vector`-everywhere dialect, and `Semigroup[NonEmptyVector[A]]` is what
    * makes `Validated` accumulate rather than keep the first error.
    */
  private type Refused[A] = Validated[NonEmptyVector[Refusal], A]

  private def ok[A](a: A): Refused[A] = Validated.valid(a)

  private def refuse[A](r: Refusal): Refused[A] = Validated.invalid(NonEmptyVector.one(r))

  /** Lower a section's own blocks. One of the two entry points; there are exactly two. */
  def blocks(bs: Vector[ast.Block]): Either[NonEmptyVector[Refusal], Vector[Block]] =
    blocksV(bs).toEither

  /** Lower one table cell. The other entry point.
    *
    * TWO ARMS ARE REQUIRED, and the second is not defensive padding.
    * `laika/internal/markdown/github/Tables.scala` builds every GFM cell as
    * `Cell(cellType, Seq(Paragraph(spans)))` — one block — but `applyColumnOptions` PADS A
    * SHORT ROW with `CellType.BodyCell.empty`, which holds ZERO blocks. So the honest invariant
    * is "exactly one `Paragraph`, or nothing", not the "always exactly `Seq(Paragraph(spans))`"
    * that `HANDOFF.md` and this slice's brief both state.
    *
    * This is the honest remedy `Golden.test.scala:117-120` names and defers: `cellSource`'s
    * `mkString(" ")` READS as "handles multi-block cells" and cannot. Here it lands as a
    * refusal, WIRED TO NOTHING — `Tables.scala` is outside this slice's file list and is on the
    * frozen identity path besides.
    */
  def cell(c: ast.Cell): Either[NonEmptyVector[Refusal], Cell] =
    cellV(c).toEither

  /** A RUN OF SPANS, WHICH IS WHAT A HEADING IS. Laika keeps a `Section`'s header OUTSIDE its
    * content (`laika/ast/blocks.scala:231`), so a heading is a `Seq[ast.Span]` and never reaches
    * [[blocks]]. That is the mechanical reason headings were missed when bodies moved onto this
    * algebra, and it is recorded at the call site in `extract/Extractor.scala`.
    *
    * WHY THIS EXISTS AT ALL, AND WHAT IT MAY NOT BE USED FOR. A heading has TWO readings — the
    * key it contributes and the face it shows — and until now one string served both, so its
    * maths and its images could only ever be shown raw or dropped. This entry point is for the
    * DISPLAY reading only. IDENTITY MUST NOT ROUTE THROUGH HERE: the ruling recorded beside
    * `Tables.cellSource` in `extract/Extractor.scala` holds that on the identity path a `Left`
    * is not a loud error but an ABSENT KEY, and that reasoning covers a heading exactly as it
    * covers a cell.
    *
    * A REFUSAL HERE IS AN IMPROVEMENT RATHER THAN A NEW FAILURE. An image in a marked heading is
    * dropped in silence today, which is the open defect named at that same call site; routed
    * through here it is refused by name, and the key it would have corrupted is derived
    * separately and still exists to attach the refusal to.
    */
  def spans(ss: Vector[ast.Span]): Either[NonEmptyVector[Refusal], Vector[Inline]] =
    spansV(ss).toEither

  // ══════════════════════════════════════════════════════════════════ blocks ════

  private def blocksV(bs: Vector[ast.Block]): Refused[Vector[Block]] =
    bs.traverse(blockV).map(_.flatten)

  /** One Laika block to ZERO, ONE or MANY of ours.
    *
    * Zero is a DROP (an explicit, named case — never a fallthrough). Many is a FLATTEN, spliced
    * into the parent's vector. The final arm is one of the project's two permitted catch-alls,
    * and it refuses.
    */
  private def blockV(b: ast.Block): Refused[Vector[Block]] = b match
    // ── constructors ────────────────────────────────────────────────────────────────
    case ast.Paragraph(spans, _) => blockSpansV(spans.toVector).map(is => Vector(Block.Paragraph(is)))

    case ast.BulletList(items, _, _) =>
      items.toVector.traverse(bulletItemV).map(is => Vector(Block.Bullets(is)))

    case ast.EnumList(items, _, _, _) =>
      // `start` and `EnumFormat` are not carried, and in laika-core 1.3.2 there is nothing to
      // carry: `ListParsers.enumLists` builds `EnumList(items, EnumFormat())` with `start`
      // omitted, and `enumStartRest` matches the author's digits and discards them. A list
      // authored `3. 4. 5.` is already indistinguishable from `1. 2. 3.` in the parse tree.
      items.toVector.traverse(enumItemV).map(is => Vector(Block.Numbered(is)))

    case ast.LiteralBlock(text, _) =>
      // An INDENTED code block. `LiteralBlock` holds a `String` (it is a `TextContainer`), not
      // spans — which is why a walk over `SpanContainer`s deleted code outright.
      ok(Vector(Block.Code(None, text)))

    case ast.CodeBlock(language, spans, _, _) =>
      // A FENCED code block, which DOES carry its language — the asymmetry with `EnumList.start`
      // above is that Laika really reports this one.
      //
      // EVERY SPAN MUST BE A CONCRETE `ast.Text`, and anything else refuses. Without the
      // `SyntaxHighlighting` extension — which `ObsidianSyntax.markupParser` does not enable —
      // `FencedCodeBlocks` emits exactly `Seq(Text(...))`, so this is byte-identical to today.
      // If highlighting is ever switched on, `laika.ast.CodeSpan` refuses LOUDLY here instead of
      // being silently reshaped into something that renders differently. This is also the
      // branch that would otherwise tempt someone into Laika's `extractText`; see the ban above.
      codeTextV(spans.toVector)
        .map(text => Vector(Block.Code(Option(language).filter(_.nonEmpty), text)))

    case ast.Table(head, body, _, _, _) =>
      // `caption` and `columns` are not carried; both are empty in this dialect, and the loss
      // is already named in `Content.scala` §(H). Laika truncates an over-long row before this
      // project ever sees it — named so it is not mistaken for a loss of ours.
      val headerV: Refused[Vector[Cell]] = head.content.toVector match
        case Vector(ast.Row(cells, _)) => cells.toVector.traverse(cellV)
        case Vector()                  => ok(Vector.empty[Cell])
        case rows =>
          refuse(Refusal.UnexpectedShape(s"a table head that is not exactly one row (${rows.size})"))
      val rowsV: Refused[Vector[Vector[Cell]]] =
        body.content.toVector.traverse { case ast.Row(cells, _) => cells.toVector.traverse(cellV) }
      (headerV, rowsV).mapN((header, rows) => Vector(Block.Table(header, rows)))

    // ── flattens: renders non-empty today, and the card model does not need the wrapper ──
    //
    // BEHAVIOUR-PRESERVING because `Extractor.elementText` joins with `"\n"` and applies
    // `.filter(_.nonEmpty)` at every BLOCK level — its `ElementContainer` arm, its `Table` arm,
    // and `bodyText`'s own final join — so hoisting a wrapper's blocks into the parent yields
    // the same string. (Its `SpanContainer` arm does neither: spans are concatenated with no
    // separator and no filter. That half is stated separately because the span-position
    // flattens below rely on it, not on the block-level join.)
    case ast.ForcedParagraph(spans, _) =>
      // A loose list's item paragraph. `ListParsers.flattenItems` emits this when a list has
      // blank lines between items — a shape `dummy-vault` does not contain, which is why no
      // tree dump of the fixture could have revealed it and it had to be read out of the parser.
      blockSpansV(spans.toVector).map(is => Vector(Block.Paragraph(is)))

    case ast.SpanSequence(spans, _) =>
      // `SpanSequence` is both a `Block` and a `Span`. This is its BLOCK position;
      // `ListParsers.flattenItems` emits one for a tight multi-block item's leading paragraph.
      blockSpansV(spans.toVector).map(is => Vector(Block.Paragraph(is)))

    case ast.Header(_, spans, _) =>
      // A heading NESTED inside a blockquote or a list item. See the long note above: it
      // flattens rather than refusing because it renders today, and the level is lost.
      blockSpansV(spans.toVector).map(is => Vector(Block.Paragraph(is)))

    case ast.QuotedBlock(blocks, _, _) =>
      // A BLOCKQUOTE or an Obsidian callout. `attribution` is declined — it is a span sequence
      // Laika renders separately, and the card model has no place for it. RECURSES into this
      // same total function; see the note above on why a flatten may never re-implement a walk.
      blocksV(blocks.toVector)

    case ast.BlockSequence(blocks, _) => blocksV(blocks.toVector)

    // ── drops: renders "" today, each with its own named case ────────────────────────
    case ast.Rule(_) =>
      // A HORIZONTAL LINE. `case class Rule(options)` has no content member at all, so there is
      // nothing to carry even if the card model wanted one. Contributes NO `Block`.
      ok(Vector.empty[Block])

    // ── the first of the project's two permitted catch-alls, and it REFUSES ──────────
    case other => refuse(Refusal.UnknownBlock(other.productPrefix))

  /** A list item is NOT a `Block`, so it is matched as its own concrete case class. */
  private def bulletItemV(item: ast.BulletListItem): Refused[Item] = item match
    case ast.BulletListItem(bs, _, _) => blocksV(bs.toVector).map(Item.apply)

  private def enumItemV(item: ast.EnumListItem): Refused[Item] = item match
    case ast.EnumListItem(bs, _, _, _) => blocksV(bs.toVector).map(Item.apply)

  private def cellV(c: ast.Cell): Refused[Cell] = c.content.toVector match
    case Vector(ast.Paragraph(spans, _)) => blockSpansV(spans.toVector).map(Cell.apply)
    case Vector()                        => ok(Cell(Vector.empty))
    case other =>
      refuse(
        Refusal.UnexpectedShape(
          s"a table cell holding ${other.size} blocks (${other.map(_.productPrefix).mkString(", ")})"
        )
      )

  private def codeTextV(spans: Vector[ast.Span]): Refused[String] =
    // The lambda is annotated rather than left to inference: without the explicit
    // `Refused[String]`, `traverse` cannot pick its applicative and the compiler
    // reports an ambiguity between unrelated instances instead of the real shape.
    val piece: ast.Span => Refused[String] =
      case ast.Text(t, _) => ok(t)
      case other          => refuse(Refusal.UnknownSpan(other.productPrefix))
    spans.traverse(piece).map(_.mkString)

  // ══════════════════════════════════════════════════════════════════ spans ════

  private def spansV(ss: Vector[ast.Span]): Refused[Vector[Inline]] =
    ss.traverse(spanV).map(_.flatten)

  /** A block's own spans, with trailing whitespace dropped.
    *
    * ONLY A `^blockid` PRODUCES ANY. The identifier lowers to nothing (see `BlockId` below) while
    * the space the author typed in front of it stays behind in the preceding `Text` — the span
    * parser starts at the caret and cannot reach backwards. Nowhere else does a block end in
    * whitespace: a hard line break is its own node and is dropped as one.
    *
    * WHY IT IS WORTH REMOVING WHEN IT RENDERS AS NOTHING. HTML collapses it, so no card looks
    * different — but it is part of the field value and therefore of the content hash, so leaving
    * it in means the day an author deletes that one space the tool reports a change nobody made
    * and rewrites the note.
    *
    * NOT IN [[spansV]], which also lowers NESTED spans: trimming there would eat a space that is
    * content, between an emphasis and the word after it.
    */
  private def blockSpansV(ss: Vector[ast.Span]): Refused[Vector[Inline]] =
    spansV(ss).map { is =>
      is.lastOption match
        case Some(Inline.Text(t)) =>
          val trimmed = t.replaceAll("\\s+$", "")
          if trimmed.isEmpty then is.init else is.init :+ Inline.Text(trimmed)
        case _ => is
    }

  /** One Laika span to ZERO, ONE or MANY of ours, on the same discipline as `blockV`. */
  private def spanV(s: ast.Span): Refused[Vector[Inline]] = s match
    // ── constructors ────────────────────────────────────────────────────────────────
    case ast.Text(text, _) =>
      // THIS IS ALSO HOW A WIKILINK ARRIVES. `ObsidianSyntax.wikilinkParser` emits
      // `Text(displayText, Styles("wikilink"))`, so `[[X]]` is an ordinary bare `Text` node by
      // the time it reaches here and no link constructor is needed for it.
      //
      // AN `Inline.Text` MAY CONTAIN A LITERAL NEWLINE — see the `LineBreak` drop below.
      ok(Vector(Inline.Text(text)))

    case ast.Literal(text, _)     => ok(Vector(Inline.CodeSpan(text)))
    case ast.Emphasized(spans, _) => spansV(spans.toVector).map(is => Vector(Inline.Emphasis(is)))
    case ast.Strong(spans, _)     => spansV(spans.toVector).map(is => Vector(Inline.Strong(is)))

    case ObsidianSyntax.Highlighted(group, content, _) =>
      spansV(content.toVector).map(is => Vector(Inline.Deletion(group, is)))

    case ObsidianSyntax.PlainHighlight(content, _) =>
      spansV(content.toVector).map(is => Vector(Inline.Highlight(is)))

    // ── flattens in SPAN position ───────────────────────────────────────────────────
    //
    // Safe without enumerating their children, because `Inline` is closed over exactly what a
    // span may contain. They also need no join argument: `Extractor.elementText`'s
    // `SpanContainer` arm concatenates with NO separator and NO filter, and so does `AsText`.
    case ast.Deleted(spans, _)         => spansV(spans.toVector) // STRIKETHROUGH
    case ast.SpanLink(spans, _, _, _)  => spansV(spans.toVector) // the URL is dropped
    case ast.SpanSequence(spans, _)    => spansV(spans.toVector)

    // ── drops, each with its own named case ─────────────────────────────────────────
    case ast.LineBreak(_) =>
      // A HARD LINE BREAK. The non-obvious reason dropping it is safe, reproduced from
      // `Content.scala` §(G) rather than paraphrased: Laika keeps the newline INSIDE THE
      // FOLLOWING `Text` NODE, so dropping the break is byte-identical BY ACCIDENT OF CHARACTER
      // PLACEMENT, not by design. The consequence for the algebra is stated at `ast.Text` above.
      ok(Vector.empty[Inline])

    case ObsidianSyntax.BlockId(_, _) =>
      // `^abc123` — OBSIDIAN'S NAME FOR THE BLOCK, WHICH IS METADATA AND NOT CONTENT.
      //
      // ZERO INLINES, so it can never reach a card. That is a stronger guarantee than removing
      // it downstream would be: this algebra has no constructor for it, so no renderer can
      // print one, and oas-3lu — a block id appearing on the card face — becomes
      // unrepresentable rather than fixed.
      //
      // AND IT IS READ BEFORE THIS POINT, NOT LOST HERE. The extractor takes a block's anchor
      // off the PARSE TREE, where the position that makes it an identifier is still visible.
      // By the time anything is lowered, a block is a list of inlines and "at the end of the
      // block" has stopped being a thing that can be asked.
      //
      // DROPPED RATHER THAN REFUSED, which it was for a few hours on 2026-08-29 and which was a
      // regression: giving the parser a production for `^abc123` turned it from harmless text
      // into an unknown span, so a block carrying one refused its whole card. Measured against
      // Marc's vault the same day — 125 block ids, all in one note with no frontmatter id, so
      // nothing was producing cards yet and nothing broke. It would have bitten the moment he
      // gave that note an id, which is exactly what somebody does when they want its
      // annotations to become cards.
      ok(Vector.empty[Inline])

    case ObsidianSyntax.ObsidianComment(_, _) =>
      // `%%…%%` or `<!--…-->`. Obsidian hides both in reading view, which is precisely why they
      // get used for private notes and precisely why printing one on a flashcard is a
      // disclosure rather than a formatting slip. Its text is NOT carried into any refusal
      // message; see the `Refusal` note on `toString`.
      ok(Vector.empty[Inline])

    // MATHS, CARRIED ACROSS AS SOURCE AND UNTOUCHED. The parser captured it without descending,
    // so `tex` is exactly what the author typed between the delimiters and this arm is a
    // rename. It could not be anything more: no arm here could REPAIR maths that had been
    // descended into, because `\\` and a paired `_` are already gone from the tree by the time
    // lowering runs. The protection is a parser concern; this is where it is banked.
    case ObsidianSyntax.MathInline(tex, _)  => ok(Vector(Inline.MathInline(tex)))
    case ObsidianSyntax.MathDisplay(tex, _) => ok(Vector(Inline.MathDisplay(tex)))

    // ── refusals by ruling ──────────────────────────────────────────────────────────
    case ObsidianSyntax.ObsidianEmbed(target, _) => refuse(Refusal.Embed(target))
    case ObsidianSyntax.TaskListMarker(_, _)     => refuse(Refusal.TaskList)

    case image: ast.Image =>
      // A PLAIN MARKDOWN IMAGE IS AN EMBED TOO, and it is matched as the CONCRETE `Image`,
      // never as `Link` — ordinary hyperlinks are perfectly renderable and must keep flattening.
      refuse(Refusal.Image(imageTarget(image)))

    // ── the second of the project's two permitted catch-alls, and it REFUSES ─────────
    case other => refuse(Refusal.UnknownSpan(other.productPrefix))

  /** The image's PATH, derived exactly as `Extractor.bodyText:189-191` derives it today.
    *
    * The path, not the alt text: the path is what an author greps their vault for, while an alt
    * text is a label several images may share. `ExternalTarget` and `InternalTarget` are matched
    * concretely because `Target`'s rendering differs per case and `toString` would print the
    * constructor.
    */
  private def imageTarget(image: ast.Image): String = image.target match
    case ast.ExternalTarget(url)   => url
    case t: ast.InternalTarget     => t.underlying.toString
