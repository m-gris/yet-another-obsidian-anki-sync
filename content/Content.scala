package obsidiananki.content

/* ═══════════════════════════════════════════════════════════════════════════════════════════
 * (A) WHAT THIS IS
 * ═══════════════════════════════════════════════════════════════════════════════════════════
 *
 * The CLOSED ALGEBRA describing what a card FIELD may contain. Twelve constructors, owned by
 * this project, with no dependency on Laika's element hierarchy.
 *
 * It is INERT DATA. There is no lowering, no renderer, no error type and no method of any kind
 * in this file, and nothing in the project references it yet. It arrives alone so that the
 * shape can be argued about before any call site exists to make it expensive to change.
 *
 * The plan it belongs to:
 *   S7 (this slice)  the algebra, plus worked examples proving it inhabited.
 *   S8               ONE lowering from Laika into it — the only place a catch-all may exist,
 *                    returning Either so an unrecognised construct is a LOUD REFUSAL NAMING IT.
 *   S9               the extract layer swaps onto it, and renderers over the closed type get
 *                    their matches checked for completeness by the compiler.
 *
 * ═══════════════════════════════════════════════════════════════════════════════════════════
 * (B) WHY, AND THE OPERATIVE RULE
 * ═══════════════════════════════════════════════════════════════════════════════════════════
 *
 * TEN silent defects came out of hand-written recursive walks over Laika's tree. Every one was
 * a construct nobody wrote a case for, or a trait match that caught something it should not.
 * All ten produced a card that was created and looked right. Examples, all real:
 *
 *   - `BulletListItem` is a `BlockContainer` but NOT a `Block`, so a walk collecting `Block`
 *     children matched the list and discarded every item in it.
 *   - `LiteralBlock` holds a `String` and is neither a `SpanContainer` nor an
 *     `ElementContainer`, so code blocks were deleted outright.
 *   - `Table` is not a container at all and has no `content` member, so whole tables vanished —
 *     and the same gap silently disabled the embed rejection, the only safety check the
 *     extract layer has.
 *   - matching the SUPERTYPE `TextContainer` to rescue code blocks also matched `Comment`, so
 *     the author's private `<!-- … -->` notes were printed onto their flashcards.
 *   - two walks over the same tree with different notions of "container" drifted apart, and a
 *     cloze section was told it had no highlight when it plainly did.
 *
 * THE RULE: MATCH CONCRETE NODE TYPES, NEVER TRAITS. Laika's hierarchy is OPEN, so a trait
 * match is a bet on what else implements it, and it loses in BOTH directions at once —
 * silently dropping what you wanted and silently including what you did not.
 *
 * SHARPENED, because "concrete" alone is not enough: enumerate the concrete cases FROM THE
 * EMITTING PARSER, NOT FROM THE FIXTURE. `laika/internal/markdown/ListParsers.scala`
 * (laika-core 1.3.2) `flattenItems` emits `ForcedParagraph` when a list has blank lines
 * between its items — a LOOSE list — and `SpanSequence` for the leading paragraph of a TIGHT
 * multi-block item. Neither construct occurs anywhere in `dummy-vault`, so NO tree dump of the
 * fixture can reveal them. A closed algebra enumerated from a dump would be complete against
 * the fixture and wrong against the first real note.
 *
 * ═══════════════════════════════════════════════════════════════════════════════════════════
 * (C) THE FENCE
 * ═══════════════════════════════════════════════════════════════════════════════════════════
 *
 * The property this refactor must preserve is: THE GOLDEN OUTPUT IS UNCHANGED.
 *
 * `extract/golden/fixture-cards.txt` pins, for all 54 fixture cards, the identity tag, the note
 * type and every field name and value. That file is the acceptance test for the whole refactor.
 *
 * IT MUST NEVER BE WORDED "the lowering reproduces today's walk", because THERE IS NO SINGLE
 * TODAY'S WALK. `Extractor.elementText` (extract/Extractor.scala:229) matches the CONCRETE
 * `LiteralBlock` — narrowed there after the trait match on `TextContainer` printed a private
 * comment onto a card — while `Cloze.elementRender` (extract/Cloze.scala:108) still matches the
 * TRAIT `TextContainer`. The disclosure fix was applied to one walk and not the other. This
 * says only that the second phrasing would be FALSE; it is NOT a claim that some input reaches
 * the difference. The golden pins only inputs on which the two happen to agree; it says nothing
 * about the inputs on which they do not. Writing that second phrasing into a doc comment here
 * would be the eleventh untrue prose claim shipped in this project.
 *
 * ═══════════════════════════════════════════════════════════════════════════════════════════
 * (D) THE ONE-WAY RULE
 * ═══════════════════════════════════════════════════════════════════════════════════════════
 *
 * This refactor may turn SILENCE INTO A REFUSAL. It may NEVER turn a refusal into silence, or
 * a refusal into a card. The second direction is an OUTPUT CHANGE and belongs to a later,
 * golden-visible slice; it is also the direction that no test reddens, because a more
 * permissive tool produces more cards rather than fewer.
 *
 * NAMED INSTANCE — the derived structural `==` on `Inline.Deletion` must NOT become the
 * `ClozeGroup.Unlabelled` key.
 *
 *   `==foo==`    lowers to  Deletion(None, Vector(Text("foo")))
 *   `==*foo*==`  lowers to  Deletion(None, Vector(Emphasis(Vector(Text("foo")))))
 *
 * Equal extracted text, UNEQUAL STRUCTURE. An `==`-keyed grouping would therefore treat these
 * as two distinct groups and silently emit two cards, where `CARD-MODEL.md` §Cloze ratifies an
 * error — "Two *unlabelled* highlights with identical text in one section are an error" — and
 * `SpecError.AmbiguousClozeDeletion` fires today.
 *
 * So: THE UNLABELLED-GROUP KEY MUST BE A NAMED PROJECTION FUNCTION over lowered content, never
 * derived equality on the node. The projection is a decision someone makes and can be read;
 * derived equality is a decision nobody makes and cannot be read.
 *
 * ═══════════════════════════════════════════════════════════════════════════════════════════
 * (E) NO PROJECTION METHODS
 * ═══════════════════════════════════════════════════════════════════════════════════════════
 *
 * This file has no `.text`, no `.render`, no `toString` override, no `given Show` and no
 * extension methods — and neither does its test. That is a constraint, not an omission.
 *
 * REASON: identity and display are DELIBERATELY TWO PROJECTIONS in this codebase, and the
 * split cost real work to build. `Tables.cellSegment` → `Tables.cellSource` →
 * `HeadingSegment.fromExtractedText` is the identity path and is FROZEN; `CellDisplay` is the
 * display path and is injected so it may change freely. Before the split, improving how a cell
 * LOOKED silently changed what a card WAS — an orphan plus a brand-new card with no review
 * history.
 *
 * And the vocabulary is not even settled within one file: `extract/Cloze.scala` computes the
 * unlabelled-group key two different ways — `h.content.map(spanText).mkString` at line 87 and
 * `h.extractText` at line 151. A single canonical "give me the text" method on this type would
 * quietly re-couple all of that, and it would do so by looking like the tidy thing to add.
 *
 * ═══════════════════════════════════════════════════════════════════════════════════════════
 * (F) EMPTINESS IS REPRESENTABLE, AND MUST NOT BECOME THE EMPTINESS TEST
 * ═══════════════════════════════════════════════════════════════════════════════════════════
 *
 * `Block.Paragraph(Vector.empty)`, `Cell(Vector.empty)` and `Block.Bullets(Vector.empty)` are
 * all constructible. There is no `NonEmptyVector` in this file.
 *
 * AN EMPTY `Cell` IS REQUIRED, not tolerated: `laika/internal/markdown/github/Tables.scala`
 * `applyColumnOptions` pads a short row with `CellType.BodyCell.empty`, a ZERO-BLOCK cell, so a
 * ragged (short-row) GFM table produces one. The honest invariant for a GFM cell is therefore
 * "EXACTLY ONE `Paragraph`, OR NOTHING" — not "always exactly `Seq(Paragraph(spans))`", which
 * is how earlier notes in this project recorded it. A `NonEmpty` cell, or a lowering that
 * demanded exactly one `Paragraph`, would refuse every legal ragged table.
 *
 * NON-EMPTINESS IS NOT ASSERTED for `Bullets`, `Numbered` or `Paragraph` either. Nothing has
 * verified that Laika cannot emit an empty one, and no consumer exists that would crash on it.
 * A type in this project must not assert what is unverified.
 *
 * AND THE EMPTINESS TEST STAYS WHERE IT IS. Ruled B6 — `SpecError.EmptyBody` — is decided on
 * RENDERED TEXT, through `Body.fromExtracted`, and must stay so. A structural `blocks.isEmpty`
 * test would stop B6 firing for a paragraph that is structurally non-empty but renders to `""`
 * — a paragraph holding only `%%a comment%%` is exactly that.
 *
 * ═══════════════════════════════════════════════════════════════════════════════════════════
 * (G) DISPOSITION OF EVERY LAIKA CONSTRUCT
 *     — STATED INTENT FOR THE LOWERING SLICE (S8); THIS FILE GUARANTEES NONE OF IT.
 * ═══════════════════════════════════════════════════════════════════════════════════════════
 *
 * Nothing below is enforced by anything in this file. It is the argument that the twelve
 * constructors are the right twelve, written down so S8 is decided rather than improvised.
 * Constructs are named in the AUTHOR's vocabulary first, Laika's second.
 *
 * ── CONSTRUCTOR (the card model needs the distinction) ──────────────────────────────────────
 *
 *   a paragraph                     `Paragraph`                     → Block.Paragraph
 *   a bulleted list, and its items  `BulletList` / `BulletListItem` → Block.Bullets / Item
 *   a numbered list, and its items  `EnumList` / `EnumListItem`     → Block.Numbered / Item
 *   a fenced or indented code block  `LiteralBlock` AND `CodeBlock` → Block.Code (BOTH)
 *   a table                         `Table` / `TableHead` / `TableBody` / `Row` / `Cell`
 *                                                                   → Block.Table / Cell
 *   plain words                     `Text`                          → Inline.Text
 *   an inline code span             `Literal`                       → Inline.CodeSpan
 *   italics                         `Emphasized`                    → Inline.Emphasis
 *   bold                            `Strong`                        → Inline.Strong
 *   a cloze highlight               `ObsidianSyntax.Highlighted`     → Inline.Deletion
 *
 * `Block.Numbered` IS NOT HERE ON PROGRESSIVE DISCLOSURE, and this corrects an earlier framing.
 * `CARD-MODEL.md` §Lists ratifies ordered-list progressive disclosure as DEFERRED ("Revisit
 * only when a list appears where sequence genuinely matters and the plain form demonstrably
 * fails"), and lists it again under "Deliberately deferred". It earns its place on two other
 * grounds: (a) `EnumList` renders NON-EMPTY today in three real-vault files, so omitting it
 * would convert working input into a refusal the golden cannot see; and (b) the only
 * alternative that also preserves behaviour — lowering `EnumList` to `Block.Bullets` — destroys
 * a distinction `CARD-MODEL.md` explicitly names as a different KIND of knowledge (membership
 * versus sequence), which would make the deferred feature unbuildable from lowered content.
 *
 * `Inline.Strong` is in on the ordinary-prose argument, and the honesty is recorded rather than
 * buried: it has ZERO witnesses in a fixture card body — the fixture's only `**` sits in
 * `Body-Shapes.md`'s UNMARKED H1 intro — while 13 of 25 real-vault notes contain `**`. That is
 * the same argument declined below for a blockquote, and the asymmetry is deliberate: `Strong`
 * is an INLINE, so admitting it cannot change the block lattice, whereas a blockquote would
 * add a block that contains blocks.
 *
 * ── FLATTEN OR ALIAS BY NAME ────────────────────────────────────────────────────────────────
 * (renders NON-EMPTY today, and the card model does not need the wrapper)
 *
 *   a blockquote or callout   `QuotedBlock`  → its blocks, hoisted; `attribution` declined
 *   strikethrough             `Deleted`      → its spans, hoisted
 *   an inline link            `SpanLink`     → its spans, hoisted; the URL is dropped
 *   a loose list's item text  `ForcedParagraph` → Block.Paragraph
 *   a tight item's lead span  `SpanSequence` in block position → Block.Paragraph
 *
 * WHY THIS IS BEHAVIOUR-PRESERVING, which is the criterion — not fixture evidence.
 * `Extractor.elementText` joins with `"\n"` and applies `.filter(_.nonEmpty)` at EVERY BLOCK
 * LEVEL — the `ElementContainer` case, the `Table` case, and `bodyText`'s own join. Hoisting a
 * wrapper's blocks into its parent therefore yields the same string: a `"\n"`-join of a
 * `"\n"`-join flattens, and the wrapper contributed no characters of its own. `QuotedBlock` is
 * a `BlockContainer` and falls to that `ElementContainer` case. `SpanLink` and `Deleted` are
 * spans, so they reach the FIRST case (`SpanContainer`), whose `extractText` already
 * concatenates through them — removing the wrapper changes nothing there either.
 *
 * NO RULING FROM THE AUTHOR IS NEEDED TO PRESERVE behaviour — only to CHANGE it, and changing
 * it is a later golden-visible slice. Flattening is what keeps the first real-vault run from
 * refusing every note that contains a blockquote.
 *
 *   `BlockSequence` — UNVERIFIED. It appears in `flattenItems`' rewrite patterns
 *   (`ListParsers.scala:68`) as something MATCHED, and it is not established whether one can
 *   survive into a lowered list item. The lowering must NAME it — with whatever disposition is
 *   then justified — rather than let it reach the refusing default by accident.
 *
 * ── DROP BY NAME ────────────────────────────────────────────────────────────────────────────
 * (renders `""` today; each gets its own named `case`, NEVER a catch-all)
 *
 *   an author's private comment  `ObsidianSyntax.ObsidianComment`
 *   a horizontal line            `Rule` — `case class Rule(options)` has NO content member at
 *                                all, so a constructor for it would carry nothing
 *   a hard line break            `LineBreak`
 *
 * THE `LineBreak` REASON IS NON-OBVIOUS AND MUST NOT BE PARAPHRASED AS "it renders to nothing".
 * Laika rewrites a line ending in two spaces to `…\\\r` before span parsing
 * (`laika/internal/markdown/BlockParsers.scala:109`) and matches exactly that sequence as a
 * `LineBreak` (`laika/internal/markdown/InlineParsers.scala:49`). The paragraph's lines are
 * then joined, so THE NEWLINE ITSELF IS LEFT AT THE HEAD OF THE FOLLOWING `Text` NODE. Dropping
 * `LineBreak` is byte-identical BY ACCIDENT OF CHARACTER PLACEMENT, not by design.
 *
 *   CONSEQUENCE FOR THIS ALGEBRA: `Inline.Text` MAY CONTAIN A LITERAL NEWLINE. Anything that
 *   assumes a `Text` payload is a single line is wrong.
 *
 * ── REFUSE BY NAME ──────────────────────────────────────────────────────────────────────────
 * (refuses today, and must go on refusing)
 *
 *   an Obsidian embed  `ObsidianSyntax.ObsidianEmbed`   — v0 builds no media pipeline
 *   a task list        `ObsidianSyntax.TaskListMarker`  — ruled unsupported, rejected by name
 *   a markdown image   `Image`                          — matched concretely, never as `Link`
 *
 * ── NOT ENUMERATED ──────────────────────────────────────────────────────────────────────────
 *
 *   `Header` / `Title` — `Extractor.ownBody` stops at the next `Section`, and Laika's section
 *   rewrite turns every header into one, so a header is never part of a body.
 *
 *   Callouts (`> [!info]`) and footnotes (`[^fn]`) never reach this algebra AT ALL. They fail
 *   the WHOLE DOCUMENT at `ObsidianSyntax.markupParser` with "unresolved link id reference",
 *   which becomes `FileUnreadable` and downgrades the run to `PartialScan` — which in turn
 *   disables orphan inference vault-wide. Their disposition is a parser question, not a
 *   lowering question.
 *
 * EVERY "no constructor" LINE ABOVE IS WORDED "no constructor exists for X" — a statement about
 * THIS FILE, checkable by reading it. Never "X cannot occur", which is a claim about Laika that
 * this file cannot guarantee and that nothing here would catch going stale.
 *
 * ═══════════════════════════════════════════════════════════════════════════════════════════
 * (H) NAMED LOSSES
 * ═══════════════════════════════════════════════════════════════════════════════════════════
 *
 * 1. `EnumList.start` AND `EnumFormat` ARE NOT CARRIED — and in laika-core 1.3.2 THERE IS
 *    NOTHING TO CARRY. `ListParsers.enumLists` builds `EnumList(items, EnumFormat())` with
 *    `start` omitted, so it is always the default 1; `enumStartRest = (anyOf(CharGroup.digit) ~
 *    "." ~ wsAfterItemStart).as("")` matches the author's digits and DISCARDS them; and
 *    `EnumListItem`'s position comes from `Iterator.from(1)`, not from the source. A list
 *    authored `3. 4. 5.` is already indistinguishable from `1. 2. 3.` in the parse tree. A
 *    `start` field on `Block.Numbered` could hold only the constant 1 — an illusory promise
 *    encoded in a type. VERIFIED 2026-08-20 against the extracted source; RE-CHECK ON A LAIKA
 *    UPGRADE, because this loss is a property of that version and not of markdown.
 *
 * 2. A table's `caption` AND `columns` ARE NOT CARRIED. Laika's `Table` has four children
 *    (`head`, `body`, `caption`, `columns`); in this dialect both of the latter are empty.
 *
 * 3. A CODE BLOCK'S SPAN STRUCTURE COLLAPSES TO A `String`. `CodeBlock` holds spans — Laika's
 *    syntax highlighting — and `Block.Code` keeps only their text. `language` IS carried, and
 *    that asymmetry with `start` is the whole point: Laika really reports the language, so
 *    carrying it promises something real.
 *
 * 4. NOT OURS, NAMED SO IT IS NOT MISTAKEN FOR OURS: Laika TRUNCATES an over-long table row
 *    before this project ever sees it (`applyColumnOptions` does `.take(count)` on every row).
 *    Cells past the header's column count are gone upstream of any lowering.
 *
 * ═══════════════════════════════════════════════════════════════════════════════════════════
 * (I) COVERAGE GAPS — shapes this type expresses that the fixture cannot witness
 * ═══════════════════════════════════════════════════════════════════════════════════════════
 *
 * `Content.test.scala` has a fixture-sourced value for each shape `dummy-vault` produces. These
 * are the shapes it does NOT, so the gap is recorded rather than discovered later:
 *
 *   - `Inline.Strong` in a MARKED body — the fixture's only `**` is in `Body-Shapes.md`'s
 *     unmarked H1 intro; `**` appears in 13 of 25 real-vault notes.
 *   - `Block.Numbered` AT ALL — zero fixture occurrences, three real-vault files.
 *   - A NESTED LIST — `dummy-vault` has NONE. `Replication.md` indents its sub-items by two
 *     spaces and laika-core 1.3.2 nests only at four, so what reads as one nested list parses
 *     as THREE SIBLING `BulletList`s.
 *   - A LOOSE LIST, hence `ForcedParagraph`.
 *   - AN EMPTY `Cell` — the fixture has no ragged (short-row) table.
 *   - NESTED INLINE INSIDE A `Deletion` — every fixture highlight contains one bare `Text`,
 *     which is exactly why the `==` hazard in (D) has no fixture witness either.
 *
 * ═══════════════════════════════════════════════════════════════════════════════════════════
 * (J) NAMING RULE FOR CONSUMERS
 * ═══════════════════════════════════════════════════════════════════════════════════════════
 *
 * Scala 3 enum cases are namespaced under their enum, so `Block.Paragraph` and `Inline.Text`
 * CANNOT collide with `laika.ast.*` under any import. Only the two TOP-LEVEL names in this
 * package are exposed — `Item` and `Cell` — and `Cell` does collide: `laika.ast.Cell` exists
 * and `extract/Tables.scala` holds one.
 *
 * THE RULE FOR CONSUMERS — the S8 lowering, and `Tables.scala`, which will hold both kinds of
 * cell at once:
 *
 *     import obsidiananki.content as C
 *     … C.Block.Paragraph(…) … C.Cell(…) …
 *
 * NEVER a wildcard import of this package, and never `import Block.*`. (Two wildcard imports of
 * the same name are a compile error — "Reference to Cell is ambiguous" — so the collision is
 * loud rather than silent; the rule exists to keep call sites READABLE, not to avert a silent
 * failure.)
 *
 * Nesting `Cell` inside the enum to dodge the collision was considered and rejected: an enum
 * body cannot hold a `case class` (a `case` there is an enum case), so it would need a
 * hand-written companion — real cost for a collision that is already loud. Abbreviated names
 * (`Para`, `Grid`, `Plain`, `Verbatim`, `Blank`) were rejected too: they cost permanent
 * legibility and do not avoid the `Cell` collision either way.
 * ═══════════════════════════════════════════════════════════════════════════════════════════
 */

/** Block-level content of a card field.
  *
  * SEPARATE FROM [[Inline]] ON PURPOSE. The load-bearing reason is the table cell: because a
  * [[Cell]] holds `Vector[Inline]`, "a cell cannot contain a table" is UNREPRESENTABLE rather
  * than merely untested. Reinforcing it: Laika's own `SpanContainer.extractText` is itself a
  * trait match with a silent `""` fallback, so an algebra that modelled blocks but stored a
  * `String` for their text would ship this project's signature defect class one level down.
  */
enum Block:
  /** A run of prose. May be empty — see section (F). */
  case Paragraph(content: Vector[Inline])

  /** A list the author spelled with a BULLET character — `-`, `*` or `+`.
    *
    * THE CONSTRUCTOR NAMES THE SYNTAX AND NOTHING ELSE. This docstring used to say "MEMBERSHIP
    * is the knowledge, not sequence", and its sibling said the opposite about [[Numbered]] —
    * both asserting that the bullet character carries the AUTHOR'S MEANING. It does not, and
    * `#flashcard/sequence` is what makes that concrete: a bulleted body under that marker is
    * revealed one item at a time, while the same bulleted body under `#flashcard/2way` is
    * shown whole. WHETHER THE ORDER IS THE KNOWLEDGE IS STATED BY THE HEADING'S MARKER and is
    * not inferable from the body — which is precisely why that marker is explicit rather than
    * inferred. Corrected 2026-08-21, by the slice that falsified it.
    */
  case Bullets(items: Vector[Item])

  /** A list the author spelled with NUMBERS — `1.`, `2.`, and so on.
    *
    * Two spellings of one shape: this and [[Bullets]] differ in the character the author typed
    * and in the element a renderer emits for it (`<ol>` rather than `<ul>`), not in what the
    * list MEANS. See [[Bullets]] for where the meaning actually lives.
    *
    * Carries no start number and no format; see the named loss (H)(1).
    */
  case Numbered(items: Vector[Item])

  /** A code block, fenced or indented. `language` is `Some` only where Laika reported one —
    * a `CodeBlock` — and `None` for a `LiteralBlock`, which has no language to report. The
    * text is flat: any span structure Laika built for highlighting is dropped, see (H)(3).
    */
  case Code(language: Option[String], text: String)

  /** A table. `header` is one row of cells; `rows` are the body rows. No caption and no column
    * specification — see (H)(2). Rows are NOT guaranteed to be the same length as `header`:
    * Laika pads short rows with an empty cell and truncates long ones, see (F) and (H)(4).
    */
  case Table(header: Vector[Cell], rows: Vector[Vector[Cell]])

/** Inline content — everything that can appear inside a paragraph, a list item's paragraph, or
  * a table cell.
  */
enum Inline:
  /** Plain words. MAY CONTAIN A LITERAL NEWLINE: see the `LineBreak` note in section (G). */
  case Text(text: String)

  /** An inline code span — Laika's `Literal`. Named `CodeSpan` so it is not confused with
    * `Block.Code`, and not `Literal` so it is not confused with `LiteralBlock`.
    */
  case CodeSpan(text: String)

  /** Italics. */
  case Emphasis(content: Vector[Inline])

  /** Bold. */
  case Strong(content: Vector[Inline])

  /** A HIGHLIGHT THAT IS NOT A CLOZE — what the author wrote as `==text==`.
    *
    * IT EXISTS BECAUSE `==` STOPPED MEANING ONE THING. Until 2026-08-28 every `==text==` in a
    * marked section was a cloze deletion and this algebra had nowhere to put a highlight that
    * was merely a highlight. Marc's ruling: a cloze is written `==<<text>>==`, so the brackets
    * declare the intent and a bare `==text==` stays what Obsidian says it is — emphasis, shown
    * as a `<mark>`, making no card.
    *
    * WHY THAT WAS WORTH A CONSTRUCTOR RATHER THAN A FLAG ON [[Deletion]]. A `Deletion` carries
    * a group and an ordinal is computed for it; a highlight carries neither and can never be
    * asked for one. Modelling the two as one type with a boolean would put every consumer in
    * the position of asking, and one of them would eventually forget.
    *
    * IT IS DELIBERATELY NOT `Emphasis` OR `Strong`. Those render as `<em>` and `<strong>`; this
    * renders as `<mark>`, which is a third level the author can reach for and Obsidian already
    * draws. Folding it into one of the others would silently change how existing notes look.
    */
  case Highlight(content: Vector[Inline])

  /** A cloze deletion — what the author wrote as `==<<text>>==` or `==<<N|text>>==`.
    *
    * `group` MATCHES `ObsidianSyntax.Highlighted(group: Option[Int], content)` EXACTLY
    * (parser/ObsidianSyntax.scala:104), so the vocabulary does not fork across the boundary:
    * `Some(n)` is a label the author wrote, `None` is an unlabelled deletion.
    *
    * THE ORDINAL — the `cN` Anki schedules against — IS DELIBERATELY NOT HERE. It is computed
    * per section, over all of that section's deletions, and it decides WHICH ANKI CARD HOLDS
    * WHICH SCHEDULING HISTORY. Keeping it out means the numbering is derived from a value that
    * has one owner (the cloze builder's environment) rather than from the same value that gets
    * rendered — which is what makes `Cloze.renderWithDeletions`' `sys.error` structurally
    * unreachable instead of unreachable-by-comment.
    *
    * SEE SECTION (D) BEFORE USING `==` ON THIS. Derived structural equality distinguishes
    * `==foo==` from `==*foo*==`, which have identical extracted text; using it as the
    * unlabelled-group key would silently turn a ratified refusal into two cards.
    */
  case Deletion(group: Option[Int], content: Vector[Inline])

  /** MATHS THE AUTHOR WROTE BETWEEN SINGLE DOLLARS, held as TeX SOURCE and never parsed.
    *
    * THE PAYLOAD IS RAW, AND THAT IS THE WHOLE POINT. Measured 2026-08-28 and pinned in
    * `parser/ObsidianSyntax.test.scala` under "maths: pinned, not supported": run through
    * markdown's inline parsers, `\\` collapses to `\` and a PAIR of `_` is eaten as emphasis,
    * so `x_1 + y_1` arrives as `x1 + y1` while a third subscript with no partner keeps its
    * own. A multi-line `align` is destroyed before this algebra could ever see it. Capturing
    * the source verbatim is what prevents that, and it is why this holds a `String` rather
    * than a `Vector[Inline]` — the same reason [[Text]] and [[CodeSpan]] do.
    *
    * THE DELIMITERS ARE NOT IN `tex`, AND THEY ARE NOT THIS TYPE'S BUSINESS. `$` is how
    * Obsidian spells maths and `\(` is how an Anki field spells it; a constructor of a closed
    * algebra names the THING and never either spelling. Each renderer emits its own, which is
    * the ordinary initial-encoding division of labour and not a decision special to maths.
    *
    * A CONSEQUENCE WORTH STATING, BECAUSE IT MOVES A CARD KEY ONCE. `AsText` strips every
    * delimiter it meets — no backticks on [[CodeSpan]], no asterisks on [[Emphasis]], no `==`
    * on [[Highlight]] — for the reason written at [[Highlight]]: that renderer is the oracle
    * for whether a section has a body at all, so syntax must never inflate the count. Maths
    * obeys the same rule. So `# Notation (Given 2 sets, $A$ and $B$)` keys on
    * `notation (given 2 sets, a and b)` once this constructor exists, where today it keys with
    * the dollars still in it. THAT RE-KEY IS ACCEPTED DELIBERATELY: holding the dollars would
    * mean writing the only special case into the one renderer whose uniformity is load-bearing,
    * in order to hold still a string that nothing yet depends on.
    */
  case MathInline(tex: String)

  /** Maths the author wrote between DOUBLE dollars — display maths, set on a line of its own.
    *
    * A SEPARATE CONSTRUCTOR RATHER THAN A MODE ON [[MathInline]], AND THE CHOICE CARRIES NO
    * DESIGN CONTENT. `Math(mode, tex)` and `MathInline(tex) | MathDisplay(tex)` are the same
    * type up to isomorphism, so nothing follows from picking either. It is settled by the
    * ruling recorded at [[Highlight]] — a consumer obliged to ask which kind it holds will one
    * day forget to ask — and settled that way for consistency rather than for advantage.
    *
    * WHAT THE DISTINCTION IS NOT is a rendering detail smuggled into the algebra. Set-apart
    * against flowing-in-the-sentence is the AUTHOR'S intent, in the way a heading is not a
    * font size. Both spellings of it, `$$` and `\[`, stay out here.
    *
    * IT IS AN `Inline` AND NOT A `Block`, which is measured rather than assumed. Markdown
    * hands a `$$…$$` line back as a PARAGRAPH containing text, so a block constructor would
    * have to reconstitute a block the parser never produced. `<p>\[…\]</p>` is legal.
    */
  case MathDisplay(tex: String)

/** One item of a bulleted or numbered list.
  *
  * HOLDS BLOCKS, NOT SPANS — and the evidence is thinner than it first looks, so it is stated
  * honestly. Two grounds:
  *
  *   1. THE DEFECT CLASS. Laika's `BulletListItem` is a `BlockContainer` and NOT a `Block`;
  *      that exact confusion is what ate every list item in this project. Modelling an item as
  *      its own type, containing blocks, is the shape that makes the confusion unrepresentable.
  *   2. THE OBSERVED FIXTURE. Every `BulletListItem` in `dummy-vault` holds a `Paragraph` and
  *      never bare spans.
  *
  * WHAT IS NOT EVIDENCE FOR IT: `Replication.md`'s apparently nested list. It is not nested —
  * see the coverage gap in section (I). `dummy-vault` contains no nested list at all, so no
  * fixture value witnesses an `Item` holding more than one block.
  */
final case class Item(blocks: Vector[Block])

/** One table cell.
  *
  * INLINE ONLY. A GFM cell holds EXACTLY ONE `Paragraph`, OR NOTHING — the second half is not
  * pedantry: `applyColumnOptions` pads a short row with a zero-block cell. Both possibilities
  * map onto `Vector[Inline]`, one as the paragraph's spans and one as the empty vector, so a
  * ragged table is representable without a separate case. See section (F).
  */
final case class Cell(content: Vector[Inline])
