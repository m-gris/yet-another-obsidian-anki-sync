package obsidiananki.extract

import laika.ast.{Header, QuotedBlock, RootElement, Section}
import obsidiananki.model.NoteId
import obsidiananki.plan.{BuildFailure, SourceKind, SourceRef}

/** A heading the author wrote that this tool does not read as a heading, and WHERE IN ITS LINE
  * THE AUTHOR WROTE THE `#` — which is the whole reason this is more than a list of headings.
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
  * WHY THE HEADING IS A FIELD AND ONLY THE PLACE IS A SUM
  * ═══════════════════════════════════════════════════════════════════════════════════════════
  *
  * Whichever of the two this turns out to be, it is a `Header` the parser produced and that this
  * tool did not lift into a `Section`. The `Header` is therefore not what varies, and carrying it
  * in both arms of a sum bought nothing: the only thing a match on that sum could do with it was
  * pull it straight back out again, which is what this type's `headingText` used to be — a match
  * over two cases that returned the same expression from both.
  *
  * What varies is where in its line the author wrote the `#`, so that — and nothing else — is the
  * sum. See [[UnreadHeading.Site]].
  *
  * IT ALSO PUTS THE LINE NUMBER WHERE THE LINE NUMBER IS KNOWN. A line is not a property of a
  * heading's severity; it is the EVIDENCE that established one. The source lookup answers "the
  * `#` is at the start of line N" or answers nothing, and [[Site.AtTheStartOfItsLine]] is the arm
  * built from the first answer, so it holds what that lookup returned. Its sibling holds no line
  * because no lookup produced one — see there for why looking harder is refused rather than
  * merely unimplemented.
  *
  * ═══════════════════════════════════════════════════════════════════════════════════════════
  * WHY THE DISTINCTION LIVES IN THE TYPE AND NOT IN A BRANCH AT EACH CONSUMER
  * ═══════════════════════════════════════════════════════════════════════════════════════════
  *
  * The two place the heading differently, so they cost different things and earn different
  * answers: the milder one is REPORTED and the note's cards are written as usual, the severe one
  * costs the note every card in it. A boolean parameter, or a severity read back off the message,
  * would let a consumer take the severe path for the mild case — which refuses an author's whole
  * note over a heading that cost them one card — or the mild path for the severe one, which
  * writes cards under keys this tool already knows are wrong. Under `-Wconf:msg=exhaustive:e` a
  * new consumer that matches on [[Site]] cannot fail to answer for both, and
  * [[commonMarkPlacesItElsewhere]] states the one consequence they differ on, so that no consumer
  * derives it twice.
  *
  * @param heading
  *   the `Header` node itself, kept rather than reduced to its text, because `explain` quotes the
  *   author's words and `in` compares this node by REFERENCE against the headers `Section`s hold.
  * @param written
  *   where the author put the `#`, which is the only fact separating the two.
  */
final case class UnreadHeading(heading: Header, written: UnreadHeading.Site):

  /** The heading's text as the parser extracted it, marker and all, so a message can quote the
    * author's own words back rather than describing them.
    */
  def text: String = heading.extractText.trim

  /** WHETHER COMMONMARK — SO OBSIDIAN — PUTS THIS HEADING SOMEWHERE ELSE IN THE NOTE'S OUTLINE
    * than this tool puts it. The one consequence the two [[Site]]s differ on, derived here so
    * that no consumer derives it twice.
    *
    * AN OBSERVATION, AND DELIBERATELY NOT A DECISION. This replaced `withholdsTheNotesCards`, a
    * `Boolean` answering "what should the planner do about this heading" — which fixed a planning
    * policy inside the type that merely reports what the parsers did, and left the two
    * inseparable: a consumer that wanted the fact got the policy with it, and a consumer that
    * disagreed with the policy had no fact to appeal to. What FOLLOWS from a disputed outline is
    * that every key in the note is derived from a heading tree the author did not write; deciding
    * to withhold those cards rather than write them is `Extractor.fromDocument`'s call and is
    * argued there.
    *
    * IT IS NOT A RESTATEMENT OF THE CASE NAMES, which is why it is worth writing down. It follows
    * from CommonMark's container rule — a line indented less than the enclosing block's content
    * column ends that block — and the first column is short of every content column there is.
    *
    * FALSE IS NOT "HARMLESS". A card the author expected is still missing, and that is still
    * reported; what it does not do is punish the rest of the note for it.
    */
  def commonMarkPlacesItElsewhere: Boolean = written match
    case UnreadHeading.Site.AtTheStartOfItsLine(_)      => true
    case UnreadHeading.Site.IndentedInsideTheBlockAbove => false

object UnreadHeading:

  /** WHERE IN ITS LINE THE AUTHOR WROTE THE `#`, which is a fact the source holds and the parse
    * tree does not.
    *
    * THE TREE COULD NOT HAVE BEEN ASKED. Both arms produce the byte-identical shape — an
    * `ast.Header` nested inside a `BulletListItem`, measured against laika-core 1.3.2 at two and
    * at three columns of indent — so nothing downstream of the parse can separate them. It is
    * also the WHOLE of what separates them: everything either arm costs follows from it.
    */
  enum Site:

    /** THE `#` IS IN THE FIRST COLUMN, where CommonMark — so Obsidian — closes the list or the
      * quote above it and reads a TOP-LEVEL heading. This tool reads the same line as more of the
      * block above.
      *
      * So the two readings disagree about the note's whole OUTLINE, not merely about one heading:
      * every heading below this one keys under a different parent in the two readings, and the
      * card that swallowed it also absorbs the text — marker and all — and the list items the
      * author wrote underneath it.
      *
      * @param line
      *   the ORIGINAL FILE's line, 1-based, so it can be typed into a jump-to-line box. The same
      *   contract as `ListIndent.Finding.line`, and it is not optional here because it is not
      *   decoration: the lookup that found the `#` in the first column is what builds this arm,
      *   and this is the line that lookup returned.
      */
    case AtTheStartOfItsLine(line: Int)

    /** THE `#` IS INDENTED INTO THE BLOCK ABOVE, far enough that CommonMark keeps that block open
      * and puts the heading inside it too.
      *
      * Both readings agree about where this heading sits, so no other card in the note changes
      * its key and nothing below it moves. What is lost is the card this heading would have made,
      * and that alone.
      *
      * WHAT ACTUALLY PUTS A HEADING HERE is that the source does not show its `#` at the start of
      * a line; every instance measured so far is a heading indented inside a list item, which is
      * the case this is named for. Naming it after the measured cause rather than after the
      * lookup's silence is deliberate — a case called "not found at the start of a line" would
      * describe this file's machinery rather than the author's file.
      *
      * NO LINE NUMBER, AND LOOKING FOR ONE IS REFUSED RATHER THAN UNIMPLEMENTED. The lookup that
      * gives its sibling a line asks whether the heading's text follows a `#` in the FIRST
      * COLUMN, and an indented heading is by construction not there. Locating it anyway would
      * take a second definition of "which lines are headings" — the drift `Extractor`'s own
      * `LineIndex` comment warns against — so the message quotes the heading's text instead.
      */
    case IndentedInsideTheBlockAbove

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
    * EMPTY IS THE ORDINARY ANSWER, not a lookup that failed: almost every note ever parsed by
    * this tool yields nothing here, which is what makes a non-empty answer worth stopping for.
    *
    * ==The four questions, in the order they are asked==
    *
    * The order is load-bearing twice over, so it is written out rather than left to be read off
    * the code:
    *
    *   1. DID SOME `Section` LIFT THIS HEADING? If so there is nothing to say about it. Compared
    *      by REFERENCE, for the reason under "what was rejected" above rather than anything about
    *      repeated headings — `==` passes every test in this suite, because Laika stamps
    *      `Styles("section")` onto a header it lifts and not onto one it does not, so a lifted
    *      heading and an unlifted one are never equal as values however identical their text.
    *      That stamp is exactly the undocumented side effect this file declines to rest on. `eq`
    *      asks whether this IS the object a `Section` holds, which is what the question means and
    *      cannot drift.
    *   2. IS THE LINE A TAG? Dropped if so — see [[obsidianReadsATagHere]] for the harm reporting
    *      it would do. Asked BEFORE the column test, because a tag line sits at column zero and
    *      would otherwise be classified as the severe case.
    *   3. IS THE HEADING'S `#` IN THE FIRST COLUMN? Then CommonMark ends the list or the quote
    *      above it and reads a top-level heading, and the two readings disagree about the whole
    *      outline.
    *   4. ONLY THEN, IS IT INSIDE A QUOTE? Dropped if so. This is last, and putting it earlier is
    *      the bug it exists to avoid: a heading at column zero directly below a quoted line is
    *      absorbed INTO the `QuotedBlock` — measured against laika-core 1.3.2 — so a check that
    *      dropped everything inside a quote would file that one as agreement and miss it.
    *
    * ==Why the line is looked up for EVERY heading and not only the unlifted ones==
    *
    * `LineIndex` carries a CURSOR, so a lookup answers with the first matching line at or after
    * the previous answer. Asking it about every heading in document order is what keeps those
    * answers aligned with the document: skipping the lifted ones would let a heading that shares
    * its text with an ordinary heading EARLIER in the file match that earlier line, conclude the
    * author wrote this one at column zero, and refuse a note over a heading that misfiles
    * nothing. Pinned by "a heading sharing its text with an earlier one is not promoted".
    *
    * IT BUILDS ITS OWN INDEX rather than sharing the extractor's, and for the same reason: that
    * cursor belongs to the walk which reports each card's position, and lending it to a second
    * traversal would silently move every position reported afterwards. The cost is one pass over
    * the lines of one file.
    *
    * ==Why Laika's own traversal rather than a walk written here==
    *
    * `Element.collect` descends every case-class field of every node, so it reaches containers
    * this project's other walks do not: `Extractor.walk` and `hasNoHeadings` both descend
    * `BlockContainer`, and Laika's `BulletList` is a `ListContainer` — which is precisely why a
    * heading swallowed INTO a list item is invisible to both of them. A walk written here by hand
    * would have had to know that, and the next container would catch it out again.
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
    val lifted = root.collect { case section: Section => section.header }
    val quoted = root.collect { case quote: QuotedBlock => quote }.flatMap(_.collect { case h: Header => h })
    val lines  = LineIndex(body, bodyFirstLine)

    root
      .collect { case heading: Header => heading }
      .flatMap { heading =>
        // FIRST, BEFORE ANY OF THE TESTS BELOW SHORT-CIRCUIT IT, because the cursor must advance
        // for every heading in the document and not only for the ones that survive the filters.
        //
        // THE LOOKUP YIELDS EVIDENCE AND THE MATCH BELOW CLASSIFIES IT, which is why the sentinel
        // `LineIndex.lineOf` answers with — 0, meaning "no such line" — is turned into an absent
        // option here rather than carried further as a number that has to be remembered not to be
        // a line. Nothing else in this function knows about the sentinel.
        //
        // ASKED EXACTLY ONCE PER HEADING, AND THE `val` IS WHAT GUARANTEES IT. `lineOf` advances a
        // cursor, so a second call for the same heading would answer about a LATER line and drag
        // every answer after it down the document.
        val found             = lines.lineOf(heading.extractText)
        val atTheStartOfALine = Option.when(found > 0)(found)

        if lifted.exists(_ eq heading) then None
        else if obsidianReadsATagHere(body, heading) then None
        else
          atTheStartOfALine match
            case Some(line) => Some(UnreadHeading(heading, UnreadHeading.Site.AtTheStartOfItsLine(line)))
            // Asked ONLY once the source has failed to put the `#` in the first column, for the
            // reason given as question 4 above.
            case None if quoted.exists(_ eq heading) => None
            case None => Some(UnreadHeading(heading, UnreadHeading.Site.IndentedInsideTheBlockAbove))
      }
      .toVector

  /** Would Obsidian read this heading's source line as a TAG rather than as a heading?
    *
    * THE ONE PLACE THE TWO PARSERS DISAGREE IN THE OTHER DIRECTION, and it has to be excluded or
    * this check refuses well-formed notes and gives harmful advice while doing it. CommonMark —
    * so Obsidian — requires a space after the `#` characters, and laika-core 1.3.2 does not, so
    * `#flashcard/sequence` written on its own line is a TAG to the author and a HEADING to this
    * tool's parser. Written straight after a list line it is then absorbed into the list, arrives
    * here as an unlifted `Header` at column zero, and looks exactly like the severe case.
    *
    * NOTHING IS LOST WHEN THAT HAPPENS, which is why it is dropped rather than reported. The tag
    * lowers to text and prints as the tag the author typed, which is what Obsidian shows too. The
    * two readings agree about the OUTCOME even though they disagree about the construct.
    *
    * REPORTING IT WOULD BE WORSE THAN NOISE. The remedy this check offers is a blank line above
    * the heading, and following it here would make Laika lift the tag into a real section —
    * inserting a heading the author never wrote into the path of every card below it. An author
    * refused for no reason learns to distrust every refusal; one who is refused and then given
    * advice that breaks their file learns something worse.
    *
    * A POSITIVE EXCLUSION RATHER THAN A POSITIVE ADMISSION, deliberately. It drops only headings
    * whose source line can be SHOWN to be a tag; a heading whose line cannot be found at all —
    * because it is a setext heading, or because its text was rewritten on the way through the
    * span parsers — stays reported. Getting that backwards would silence the whole check on any
    * note whose headings this cannot locate, which is failing quiet rather than failing loud.
    *
    * LEADING WHITESPACE IS TOLERATED because CommonMark allows a heading up to three columns in
    * and an author's tag line may be indented inside a list; the lookahead is what carries the
    * decision, and it is unaffected by the indent.
    */
  private def obsidianReadsATagHere(body: String, heading: Header): Boolean =
    val text = heading.extractText.trim
    body.linesIterator.exists { line =>
      TagLineLaikaReadsAsAHeading.findPrefixOf(line).isDefined &&
      line.dropWhile(c => c == ' ' || c == '\t').dropWhile(_ == '#').trim == text
    }

  /** `#{1,6}` with NO space after it — Laika's heading, CommonMark's tag. The lookahead excludes
    * a further `#` so that a run of seven or more, which is a heading to neither parser, cannot
    * match by having its first six taken.
    */
  private val TagLineLaikaReadsAsAHeading = """^[ \t]*#{1,6}(?=[^\s#])""".r

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
  def explain(unread: UnreadHeading): String = unread.written match
    case UnreadHeading.Site.AtTheStartOfItsLine(_) =>
      s"'${unread.text}' is written as a heading at the start of its line and this " +
        "tool does not read it as one, so it makes no card of its own AND the headings below it " +
        "are filed under the wrong parent — a heading path is half a card's identity, so no card " +
        "in this note is written until this is fixed, and correcting the file later would re-key " +
        "any cards it has already produced and orphan the live Anki notes behind them. Most " +
        "often the line directly above such a heading is a list item with no blank line between " +
        "the two, and markdown then reads the heading as more of that list item; a quoted line " +
        "above it does the same. Put a blank line above the heading"

    case UnreadHeading.Site.IndentedInsideTheBlockAbove =>
      s"'${unread.text}' is written as a heading and this tool does not read it as " +
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
  def failure(unread: UnreadHeading, noteId: NoteId, filePath: String): BuildFailure = unread.written match
    case UnreadHeading.Site.AtTheStartOfItsLine(line) =>
      BuildFailure.KeyMisfiledInFile(noteId, SourceRef(filePath, line, SourceKind.Heading), explain(unread))

    case UnreadHeading.Site.IndentedInsideTheBlockAbove =>
      BuildFailure.HeadingUnreadInFile(noteId, SourceRef(filePath, 0, SourceKind.Heading), explain(unread))
