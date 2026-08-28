package obsidiananki.content

/* ═══════════════════════════════════════════════════════════════════════════════════════════
 * THIS FILE DOES NOT IMPORT `laika` AT ALL, AND THAT IS A STRUCTURAL GUARANTEE
 * ═══════════════════════════════════════════════════════════════════════════════════════════
 *
 * `Lower.scala` carries a PROSE ban on calling Laika's `SpanContainer.extractText` — itself a
 * trait match with a silent `case _ => ""`, which is the exact defect shape this refactor
 * exists to abolish. Here the ban is stronger and cheaper to check: with no `laika` import,
 * calling `extractText` is IMPOSSIBLE rather than merely forbidden, and verifying it takes
 * reading the first ten lines instead of auditing every branch. Do not add one.
 *
 * ═══════════════════════════════════════════════════════════════════════════════════════════
 * WHAT THIS IS
 * ═══════════════════════════════════════════════════════════════════════════════════════════
 *
 * The renderer from the closed algebra to this tool's PLAIN-TEXT rendering of a card. `plain`
 * produces the string a body is gated on; `text`, given a `{{cN::…}}` hook, produces the cloze
 * rendering. Both are reached from outside this package — `extract/Extractor.scala` calls
 * `plain`, `extract/Cloze.scala` calls `text`.
 *
 * THIS HEADER USED TO SAY that nothing outside `content/` called the file yet, and that `plain`
 * and `text` REPRODUCED `Extractor.bodyText` and `Cloze.renderWithDeletions` byte for byte.
 * Both statements have expired, and in the way the second one anticipated: the two functions it
 * named no longer exist as separate walks, having been replaced by calls to this file. So
 * `extract/golden/fixture-cards.txt` is now this file's regression net rather than evidence
 * that it is safely inert.
 *
 * ═══════════════════════════════════════════════════════════════════════════════════════════
 * `-Wconf:msg=exhaustive:e` CHECKS THE DOMAIN, NEVER THE CODOMAIN
 * ═══════════════════════════════════════════════════════════════════════════════════════════
 *
 * The build treats an inexhaustive match as an ERROR, so no constructor of `Block` or `Inline`
 * can be silently unhandled here. That guarantee is about which cases EXIST, and says nothing
 * whatever about what they RETURN: `case Strong(c) => ""` compiles clean, warns nothing, and
 * passes the golden, because the fixture has no `Strong` in a marked body.
 *
 * The differential in `Lower.test.scala` is the VALUE-LEVEL HALF of that check, not extra
 * coverage. Deleting it would leave twelve compiler-checked cases and no evidence that any of
 * them returns the right string.
 *
 * ═══════════════════════════════════════════════════════════════════════════════════════════
 * THE CONSTRUCTOR-PINNING LEDGER — where each of the twelve gets its evidence
 * ═══════════════════════════════════════════════════════════════════════════════════════════
 *
 * Measured against `dummy-vault` on 2026-08-20. "fixture" means a section of the fixture vault
 * exercises it through test (A) or (B); "snippet" means only a written markdown string in test
 * (C) does. NO CONSTRUCTOR IS UNPINNED.
 *
 *   Block.Paragraph  fixture  (every body)
 *   Block.Bullets    fixture  (Body-Shapes § "Layers of the epidermis", and three more)
 *   Block.Numbered   snippet  — ZERO ordered lists in the whole fixture
 *   Block.Code       fixture  (Body-Shapes § "Running the test suite", unlanguaged;
 *                              Replication § "How does a follower catch up…", `sql`)
 *   Block.Table      fixture  (five tables) — plus a snippet for the RAGGED case, which the
 *                              fixture does not contain
 *   Inline.Text      fixture  (every body)
 *   Inline.CodeSpan  fixture  (Replication § "How does a follower catch up…") + trap pin
 *   Inline.Emphasis  fixture  (Linearizability § "Contrast with sequential consistency")
 *   Inline.Strong    snippet  — and, MEASURED, also by the vault sweep: the fixture's only `**`
 *                              sits in `Body-Shapes.md`'s H1 intro, which the sweep reaches
 *                              because that H1 becomes a `Section` rather than a document
 *                              title. The SNIPPET is what the ledger banks on, deliberately —
 *                              that H1 witness depends on `SectionBuilder` defaulting
 *                              `firstHeaderAsTitle` to `!orphan` (Cursor.scala:491 stamps
 *                              `orphan = true` for a bare `MarkupParser.parse`), so a Laika
 *                              upgrade could silently un-pin the constructor. NO MARKED body
 *                              in the fixture contains `**` at all, while `**` appears in 13
 *                              of the 25 real-vault notes.
 *   Inline.Deletion  fixture  (five cloze sections, labelled and unlabelled)
 *   Item             fixture  (via every `Bullets`)
 *   Cell             fixture  (via every `Table`) + the ragged snippet for the EMPTY `Cell`
 *
 * ═══════════════════════════════════════════════════════════════════════════════════════════
 * THE `.filter(_.nonEmpty)` IS ON THE RENDERED STRING, AT BLOCK LEVEL ONLY
 * ═══════════════════════════════════════════════════════════════════════════════════════════
 *
 * NEVER STRUCTURAL. `Content.scala` §(F) rules this and the reason is `SpecError.EmptyBody`
 * (ruled B6): emptiness is decided on RENDERED TEXT, through `Body.fromExtracted`. A structural
 * `blocks.isEmpty` test would stop B6 firing for a paragraph that is structurally non-empty but
 * renders to `""` — a paragraph holding only `%%a comment%%` is exactly that. Getting the LEVEL
 * wrong instead inserts a blank line between two surviving paragraphs, which moves the golden.
 * `Lower.test.scala` pins both halves.
 *
 * ═══════════════════════════════════════════════════════════════════════════════════════════
 * A NOTE FOR S9: A `deletions` FUNCTION MUST BE DERIVED FROM `text`, NEVER WRITTEN BESIDE IT
 * ═══════════════════════════════════════════════════════════════════════════════════════════
 *
 * S8 deliberately ships no such function, because it would have no consumer and would therefore
 * itself be unpinned. When S9 needs occurrence identity for cloze numbering, the way to get it
 * is to CALL `text` and collect through the callback, discarding the returned string — not to
 * write a second walk that also finds deletions. Writing it beside `text` reproduces the
 * `Cloze.collectHighlights` vs `Cloze.renderWithDeletions` drift with new paint: two walks over
 * one tree with different notions of "container", which told a cloze section it had no highlight
 * when it plainly did.
 */
object AsText:

  /** The hook that renders a deletion as its bare inner text — no `{{cN::…}}`, no `==`.
    *
    * This is what `Extractor.bodyText` does today: `Highlighted` is a `SpanContainer`, so
    * `extractText` returns its content and the markers vanish.
    */
  val Plain: (Option[Int], String) => String = (_, inner) => inner

  /** Blocks to the string the card carries.
    *
    * THE WHOLE TAIL LIVES HERE — the block-level join, the non-empty filter and the trailing
    * `.trim` — rather than being split across a caller. Splitting the `.trim` out would leave it
    * pinned by nothing, and it is load-bearing: both `Extractor.bodyText` and
    * `Cloze.renderWithDeletions` end with exactly this expression.
    *
    * @param deletion how an `Inline.Deletion` renders, given its group label (`None` for an
    *                 unlabelled `==text==`) and its already-rendered inner text. `(label,
    *                 inner)` is exactly sufficient for the cloze path, because
    *                 `renderWithDeletions` uses its `inner` string AS the unlabelled group's
    *                 key. No index and no node is passed: `Content.scala` §(D) bans handing a
    *                 caller the derived structural `==` on `Inline.Deletion`, since `==foo==`
    *                 and `==*foo*==` are structurally unequal with equal text and an
    *                 `==`-keyed grouping would silently mint two cards where
    *                 `SpecError.AmbiguousClozeDeletion` fires today.
    */
  def text(blocks: Vector[Block], deletion: (Option[Int], String) => String): String =
    blocks.map(blockText(_, deletion)).filter(_.nonEmpty).mkString("\n").trim

  /** The body as an ordinary (non-cloze) card carries it. */
  def plain(blocks: Vector[Block]): String = text(blocks, Plain)

  /** THE DISPLAY PROJECTION for a table cell, and it is named that way on purpose.
    *
    * It mirrors `CellDisplay.Default`, which is the projection a person READS on a card. It is a
    * SECOND BODY beside the table-cell rendering inside `blockText` below, differing only by the
    * trailing `.trim`, and that duplication is deliberate — it mirrors the duplication
    * `CellDisplay.Default` already documents at `Tables.scala:53-64`: display is about to change
    * and identity must never move, so the two projections MUST BE FREE TO DIVERGE. Re-merging
    * them onto one helper is the bug, not the fix.
    *
    * S9 may legitimately wire `CellDisplay(c => AsText.cellDisplay(...))`. `Tables.cellSource`
    * may NEVER reach this, nor anything else in this file: it feeds
    * `HeadingSegment.fromExtractedText`, and on that path a refusal is not a loud error but an
    * ABSENT KEY — an orphan, flagged against a real synced note.
    *
    * HONEST LIMIT: the `.trim` here is PINNED BY NOTHING, exactly as `Golden.test.scala`'s
    * mutant M5 recorded for the body it copies. `LineSource.trim` is applied by `rowRest` to
    * every cell BEFORE span parsing, so no parser-produced cell carries edge whitespace and no
    * differential over parsed input can distinguish trim from no-trim. `AsText.test.scala` does
    * NOT kill M5 and does not claim to.
    */
  def cellDisplay(c: Cell): String = c.content.map(inlineText(_, Plain)).mkString.trim

  // ══════════════════════════════════════════════════════════════════ blocks ════

  private def blockText(b: Block, deletion: (Option[Int], String) => String): String = b match
    // NO separator between inlines, and NO trim. `Extractor.elementText`'s `SpanContainer` arm
    // is a bare `.mkString` — the block-level join and filter happen one level up, in `text`.
    case Block.Paragraph(content) => content.map(inlineText(_, deletion)).mkString

    // NO `"- "` OR `"1. "` MARKER IS EMITTED, for either list kind. Today's output has none:
    // `Extractor.elementText` reaches a `BulletList` through its generic `ElementContainer` arm
    // and simply joins the items. Adding a marker is a golden-visible output change and belongs
    // to the later HTML slice, not here. `Numbered` renders identically to `Bullets` for exactly
    // that reason — the distinction is carried in the algebra and not yet rendered.
    case Block.Bullets(items)  => items.map(itemText(_, deletion)).filter(_.nonEmpty).mkString("\n")
    case Block.Numbered(items) => items.map(itemText(_, deletion)).filter(_.nonEmpty).mkString("\n")

    // `language` IS CARRIED BY `Lower` AND DELIBERATELY NOT RENDERED. A carried-but-unrendered
    // field reads as an omission and the next reader will "fix" it into a golden diff; it is
    // carried because Laika really reports it and a later HTML slice will want it.
    case Block.Code(_, text) => text

    // ONE CELL PER LINE, NO PIPES, and an empty cell filtered out ENTIRELY — not even a blank
    // line. Someone will read this as a bug and "improve" it into a golden diff, so: today's
    // rendering nests `Table` → head/body → rows → cells → blocks, joining with `"\n"` and
    // filtering empties at EVERY level, and a nested `"\n"`-join with an empty filter collapses
    // to exactly this flat join. The header row is included, in document order, ahead of the
    // body rows.
    //
    // NOTE this is the ORDINARY-BODY path — a table appearing inside a `1way`/`2way`/`cloze`
    // section. _Corrected 2026-08-23:_ this said a `#flashcard/table` section "does not come
    // through here at all". It does. `Extractor.buildSpecs` renders every marked section's body
    // before its `marker match`, a table section included, so this arm runs for one and its
    // output is then discarded — `Tables.scala` decomposes the section into cards separately.
    case Block.Table(header, rows) =>
      (header ++ rows.flatten)
        .map(c => c.content.map(inlineText(_, deletion)).mkString)
        .filter(_.nonEmpty)
        .mkString("\n")

  private def itemText(item: Item, deletion: (Option[Int], String) => String): String =
    item.blocks.map(blockText(_, deletion)).filter(_.nonEmpty).mkString("\n")

  // ══════════════════════════════════════════════════════════════════ inlines ════

  private def inlineText(i: Inline, deletion: (Option[Int], String) => String): String = i match
    case Inline.Text(text) => text

    // RENDERS WITHOUT BACKTICKS, and the name is what makes that surprising. Verified:
    // `laika.ast.Literal` is a `TextContainer`, and `SpanContainer.extractText` matches
    // `TextContainer` BEFORE `SpanContainer`, so `` `foo` `` is `foo` today with no backticks
    // anywhere. A future author will read `CodeSpan` and "fix" this; `AsText.test.scala` pins it.
    case Inline.CodeSpan(text) => text

    // Children concatenated, no `*` and no `**`. Same reason as `CodeSpan`: `extractText`
    // recurses through a `SpanContainer` and emits no markers.
    case Inline.Emphasis(content) => content.map(inlineText(_, deletion)).mkString
    case Inline.Strong(content)   => content.map(inlineText(_, deletion)).mkString

    case Inline.Deletion(label, content) =>
      // The inner text is rendered with the SAME hook, which is exactly what
      // `Cloze.renderWithDeletions` does: its group key and its `inner` are the same string,
      // computed by the same recursion.
      deletion(label, content.map(inlineText(_, deletion)).mkString)
