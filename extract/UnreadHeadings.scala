package obsidiananki.extract

import laika.ast.{Header, RootElement}
import obsidiananki.model.NoteId
import obsidiananki.plan.{BuildFailure, SourceKind, SourceRef}

/** A heading the author wrote that this tool does not read as a heading — and WHAT THAT COSTS,
  * which is the whole reason this is a sum rather than a list of headings.
  *
  * ═══════════════════════════════════════════════════════════════════════════════════════════
  * WHY A HEADING NOBODY SEES IS A CARD-IDENTITY PROBLEM AND NOT A RENDERING ONE
  * ═══════════════════════════════════════════════════════════════════════════════════════════
  *
  * A card's identity is its note's frontmatter `id` plus the chain of headings it hangs off, and
  * that chain is read off Laika's `Section` tree. So a heading that never becomes a `Section` can
  * cost two quite different things, and until this type existed they were reported as one:
  *
  *   - the card that heading would have made, which is simply absent, and
  *   - A DIFFERENT KEY FOR EVERY HEADING BENEATH IT, because the missing heading contributes no
  *     segment and the headings below re-parent onto its own parent.
  *
  * The second only happens when the heading would have been TOP-LEVEL in the other reading. It is
  * far worse than the first: the cards build perfectly and are merely filed wrong, so nothing
  * fails, the run reports no failures and exits 0 — and when the file is later corrected the
  * cards are re-keyed, which orphans and SUSPENDS live Anki notes along with their review history
  * and creates replacements that have none.
  *
  * ═══════════════════════════════════════════════════════════════════════════════════════════
  * WHY THE DISTINCTION LIVES IN THE TYPE AND NOT IN A BRANCH AT EACH CONSUMER
  * ═══════════════════════════════════════════════════════════════════════════════════════════
  *
  * The two cost different things, so they earn different answers: the milder one is REPORTED and
  * the note's cards are written as usual, the severe one WITHHOLDS every card in the note. A
  * boolean parameter, or a severity read back off the message, would let a consumer take the
  * severe path for the mild case — which refuses an author's whole note over a heading that cost
  * them one card — or the mild path for the severe one, which writes cards under keys this tool
  * already knows are wrong. Under `-Wconf:msg=exhaustive:e` a new consumer cannot fail to answer
  * for both, and [[withholdsTheNotesCards]] means no consumer has to decide twice.
  */
enum UnreadHeading:

  /** THE HEADING IS INDENTED INSIDE A LIST ITEM, where CommonMark — so Obsidian — puts it too.
    *
    * Both readings agree about where this heading sits, so no other card in the note changes its
    * key and nothing below it moves. What is lost is the card this heading would have made, and
    * that alone.
    *
    * NO LINE NUMBER, AND THE ABSENCE IS THE EVIDENCE. Its sibling carries one because finding the
    * heading's `#` in the FIRST COLUMN of some line is exactly what proves the severe case; a
    * heading that was not found there is this one. Locating it anyway would take a second
    * definition of "which lines are headings" — the drift `Extractor`'s own `LineIndex` comment
    * warns against — so the message quotes the heading's text instead.
    */
  case NoCardOfItsOwn(heading: Header)

  /** THE HEADING IS WRITTEN AT THE START OF ITS LINE, where CommonMark closes the list or the
    * quote above it and reads a top-level heading. This tool reads it as more of the line above.
    *
    * So the two readings disagree about the note's whole OUTLINE, not merely about one heading:
    * every heading below this one keys under a different parent in the two readings, and the card
    * that swallowed it also absorbs the text — marker and all — and the list items the author
    * wrote underneath it.
    *
    * @param line
    *   the ORIGINAL FILE's line, 1-based, so it can be typed into a jump-to-line box. The same
    *   contract as `ListIndent.Finding.line`, and it is not optional here: this case cannot be
    *   built without having found the heading's line, because that is what establishes it.
    */
  case EveryHeadingBelowMisfiled(heading: Header, line: Int)

  /** The heading's text as the parser extracted it, marker and all, so a message can quote the
    * author's own words back rather than describing them.
    */
  def headingText: String = this match
    case NoCardOfItsOwn(h)               => h.extractText.trim
    case EveryHeadingBelowMisfiled(h, _) => h.extractText.trim

  /** WHETHER THIS COSTS THE NOTE EVERY CARD IN IT — the one decision the two cases differ on,
    * written once here so that no consumer restates it.
    *
    * TRUE MEANS NOTHING KEYED BY THIS NOTE'S HEADINGS MAY BE WRITTEN. The keys derived from the
    * outline are derivable and WRONG, and choosing which of them are unaffected would mean
    * reconstructing the outline the author meant — a guess this project does not make. So the
    * tool declines to say what this file's cards are, rather than saying it and being plausibly
    * wrong.
    *
    * FALSE IS NOT "HARMLESS". A card the author expected is still missing, and that is still
    * reported; what it does not do is punish the rest of the note for it.
    */
  def withholdsTheNotesCards: Boolean = this match
    case NoCardOfItsOwn(_)               => false
    case EveryHeadingBelowMisfiled(_, _) => true

/** Finding the headings above, and saying what they mean.
  *
  * ═══════════════════════════════════════════════════════════════════════════════════════════
  * WHAT THIS ASKS, AND WHY THE FIRST HALF IS A FACT RATHER THAN AN INFERENCE
  * ═══════════════════════════════════════════════════════════════════════════════════════════
  *
  * Laika's section-building rewrite lifts a document's headings into `Section`s. A `Header` node
  * that survives the rewrite WITHOUT being some `Section`'s own header is one the rewrite could
  * not lift, and that is exactly the set of headings the extractor's walk — which hunts `Section`s
  * and nothing else — will never see. So the first question asked here is not *did the author
  * mean a heading?* but *did THIS TOOL end up treating it as one?*, and the parse tree answers it
  * outright.
  *
  * THAT IS THE WHOLE REASON THIS IS NOT WRITTEN AS A SOURCE SCAN. Its sibling `ListIndent.scala`
  * reads raw text and RE-DERIVES a rule the parser is about to apply, so it can be wrong in both
  * directions and is deliberately built to miss rather than to over-report. Nothing is re-derived
  * here, and a `Header` that is not a `Section`'s header cannot be a false positive about the
  * fact it reports.
  *
  * IT IS ALSO WHY THIS CATCHES CAUSES NOBODY ENUMERATED. Four are known today — a heading
  * absorbed into an open list item, the same absorption through a list item's LAZY CONTINUATION
  * line (which `ListIndent` records as a known miss, because its own dedent rule closes the list
  * there), the same absorption by an open BLOCKQUOTE, and a heading indented inside a list item.
  * A source scan would need a rule per cause; this needs none, and a fifth cause reports itself
  * the day it appears.
  *
  * ═══════════════════════════════════════════════════════════════════════════════════════════
  * AND WHY THE SECOND HALF — WHICH OF THE TWO IT IS — MUST ASK THE SOURCE
  * ═══════════════════════════════════════════════════════════════════════════════════════════
  *
  * The tree cannot tell the two cases apart, and assuming it could is the false-positive trap. An
  * author who INDENTS a heading inside a list item produces the byte-identical tree shape, an
  * `ast.Header` inside a `BulletListItem` — measured against laika-core 1.3.2 at two and three
  * columns of indent. The only thing separating that from a swallowed heading is whether the
  * author wrote the `#` in the FIRST COLUMN, and that fact exists nowhere but in the source.
  *
  * COLUMN ZERO IS A CONSERVATIVE STAND-IN FOR THE REAL RULE, deliberately. What CommonMark
  * actually does is close the enclosing container when a line is indented less than that
  * container's content column, so a heading indented ONE column inside a two-column list item is
  * top-level to Obsidian and is reported here as the milder case. That is a MISS on the severity
  * and never an invention of one, which is the direction `docs/findings/PARSER-DISAGREEMENTS.md`
  * rules this family must fail in: an author refused for no reason learns to distrust every
  * refusal this tool makes.
  *
  * ═══════════════════════════════════════════════════════════════════════════════════════════
  * WHAT WAS REJECTED
  * ═══════════════════════════════════════════════════════════════════════════════════════════
  *
  * MATCHING ON `Styles("section")`, which Laika's rewrite happens to stamp onto a lifted header
  * and not onto an unlifted one. It works today and is an undocumented side effect of somebody
  * else's rewrite rules, so it would break in silence — and silence is the failure mode this
  * whole file exists to close. Identity comparison against the headers `Section`s actually hold
  * asks the same question through the public structure instead.
  *
  * COUNTING `#` LINES IN THE SOURCE AND COMPARING AGAINST THE SECTION COUNT. It needs fence
  * tracking, it must decide what a heading is a second time, and a setext heading — which has no
  * `#` line at all — MASKS a swallowed one by making the two counts agree again.
  */
object UnreadHeadings:

  /** Every heading the AUTHOR wrote that this tool did not read as one, in document order.
    *
    * ══ STUBBED IN THIS COMMIT ══ so that the tests pinning the defect compile and FAIL rather
    * than failing to build, which would say nothing about which behaviour is missing. The
    * implementation is the commit that follows this one; nothing calls this yet.
    *
    * @param body
    *   the note's RAW SOURCE, frontmatter already removed, and NOT optional. Two questions here
    *   need it: whether the author wrote the heading in the first column, which decides the
    *   severity, and whether the line is really a TAG rather than a heading — laika-core 1.3.2
    *   reads `#tag` with no space after the hashes as a heading and CommonMark does not.
    * @param bodyFirstLine
    *   the line of the ORIGINAL FILE that `body` starts on, so a reported line is the one an
    *   editor shows rather than one counted from the end of the frontmatter.
    */
  def in(root: RootElement, body: String, bodyFirstLine: Int): Vector[UnreadHeading] =
    Vector.empty

  /** What an author reads about ONE such heading.
    *
    * IT SAYS WHAT WOULD OTHERWISE HAVE HAPPENED, following [[ListIndent.explain]]: an author told
    * only "this heading was not read" has no way to judge whether the tool is being fussy,
    * whereas one told that the cards below it would be filed under the wrong heading knows
    * immediately what the report is buying them.
    *
    * IT NAMES THE CONSTRUCT AND NOT THE PARSER, which is a standing ruling in
    * `docs/findings/PARSER-DISAGREEMENTS.md` and one this tool has broken before: an Obsidian
    * callout is reported today as "unresolved link id reference: !note", which is Laika's
    * sentence about a shortcut reference link offered to somebody who typed a callout.
    *
    * THE TWO MESSAGES DO NOT SHARE A REMEDY, AND THAT IS THE SHARPEST EDGE IN THIS FILE. "Put a
    * blank line above the heading" is right for a heading a list swallowed and is a TRAP for one
    * the author indented on purpose: un-indenting THAT heading makes it a real one, which inserts
    * a new segment into the path of every card below it and re-keys them. So the milder message
    * says what was lost, says that nothing else moved, and warns what the obvious fix would cost.
    *
    * THE SEVERE CAUSE IS OFFERED AS THE LIKELY ONE RATHER THAN ASSERTED, because this function is
    * handed a heading and not the lines around it. A list item above it with no blank line
    * between is the cause of every instance found in a real vault so far, and saying so is what
    * makes the message actionable; hedging it is what keeps it honest when the line above is a
    * quote instead. Diagnosing which would mean re-reading the source, and this deliberately does
    * not.
    */
  def explain(unread: UnreadHeading): String = unread match
    case UnreadHeading.EveryHeadingBelowMisfiled(heading, _) =>
      s"'${heading.extractText.trim}' is written as a heading at the start of its line and this " +
        "tool does not read it as one, so it makes no card of its own AND the headings below it " +
        "are filed under the wrong parent — a heading path is half a card's identity, so no card " +
        "in this note is written until this is fixed, and correcting the file later would re-key " +
        "any cards it has already produced and orphan the live Anki notes behind them. Most " +
        "often the line directly above such a heading is a list item with no blank line between " +
        "the two, and markdown then reads the heading as more of that list item; a quoted line " +
        "above it does the same. Put a blank line above the heading"

    case UnreadHeading.NoCardOfItsOwn(heading) =>
      s"'${heading.extractText.trim}' is written as a heading and this tool does not read it as " +
        "one, so it makes no card of its own. Nothing else in the note is affected: the heading " +
        "is indented inside a list item, and markdown reads it as part of that item whichever " +
        "way you look at it, so no other card changes. If you meant it as a heading of the note " +
        "rather than as part of the list, move it out to the start of its own line with a blank " +
        "line above it — but know that doing so files every card below it under a new heading, " +
        "which changes those cards' identity and re-keys them"

  /** The failure the scan is told about, which is a DIFFERENT CASE for each severity.
    *
    * WHY NOT ONE CASE CARRYING A SEVERITY. The two ask different things of the reader — one says
    * a card is missing, the other says every card in the file is withheld — and a report that
    * printed them identically would leave an author unable to tell "you will not get one card"
    * from "you will get none of them". They also differ in what the tool is claiming: the first
    * is news ABOUT a heading, the second is news about the file's KEYS.
    *
    * THE LINE IS ATTACHED ONLY WHERE ONE EXISTS. `SourceRef` prints a bare file name for line 0,
    * which is what every other whole-note failure does; the severe case has a real line because
    * finding it in the first column is what made it severe, and an author fixes it by putting the
    * cursor there and pressing Return.
    */
  def failure(unread: UnreadHeading, noteId: NoteId, filePath: String): BuildFailure = unread match
    case u @ UnreadHeading.EveryHeadingBelowMisfiled(_, line) =>
      BuildFailure.KeyMisfiledInFile(noteId, SourceRef(filePath, line, SourceKind.Heading), explain(u))

    case u @ UnreadHeading.NoCardOfItsOwn(_) =>
      BuildFailure.HeadingUnreadInFile(noteId, SourceRef(filePath, 0, SourceKind.Heading), explain(u))
