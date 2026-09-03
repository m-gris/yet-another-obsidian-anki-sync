package obsidiananki.extract

import obsidiananki.model.NoteId
import obsidiananki.parser.ObsidianSyntax
import obsidiananki.plan.{BuildFailure, OrphanShelter}

/** What [[UnreadHeadings]] must report, what it must NOT, and WHICH OF THE TWO THINGS it says.
  *
  * ==Why these expectations are not guesses==
  *
  * Every case below was run through laika-core 1.3.2 before being written down: each body was
  * parsed and the resulting tree printed, so "this heading is not lifted into a `Section`" is an
  * observation rather than a prediction. Where a test says OBSIDIAN reads something differently,
  * that follows from CommonMark's rules and is an inference — nobody has yet opened one of these
  * files in Obsidian's reading view and looked.
  *
  * ==The two severities, and why they are not one==
  *
  * Both halves of this type describe a heading this tool does not read as a heading. They differ
  * in what that COSTS, and the difference is large enough that one is a report and the other
  * refuses the note:
  *
  *   - [[UnreadHeading.NoCardOfItsOwn]] — the heading is indented inside a list item, where
  *     CommonMark puts it too. Both readings agree about where it sits, so nothing else in the
  *     note moves; what is lost is a card for that heading alone.
  *   - [[UnreadHeading.EveryHeadingBelowMisfiled]] — the heading is written at the START of its
  *     line, where CommonMark closes the list or the quote above it and reads a top-level
  *     heading. This tool reads it as more of the line above, so the heading contributes no
  *     segment and every heading below re-parents onto ITS parent. The cards build perfectly and
  *     are filed under keys the author did not write.
  *
  * ==The asymmetry, and how it differs from its sibling's==
  *
  * `ListIndent.test.scala` opens by stating that a false positive costs more than a miss, and
  * builds its scanner to miss. That posture belongs to a check which RE-DERIVES a parser rule
  * from raw text and can be wrong about the rule.
  *
  * The question *did this tool treat the line as a heading?* re-derives nothing and cannot be
  * wrong. The question *would CommonMark have made it a top-level heading?* is the one that CAN
  * be, and it is answered from a single fact — whether the author wrote the `#` in the first
  * column — chosen because it is conservative in the direction this project prefers. A heading
  * indented one column inside a two-column list item would be top-level to CommonMark and is
  * reported here as the milder case: a MISS on the severity, never an invention of it.
  */
class UnreadHeadingsTest extends munit.FunSuite:

  private def unread(body: String): Vector[UnreadHeading] =
    val root = ObsidianSyntax.markupParser.parse(body).fold(e => fail(s"parse: $e"), _.content)
    UnreadHeadings.in(root, body, 1)

  /** Each finding as `severity: heading text`, so one assertion pins both halves. A test that
    * checked only the text would pass just as happily for the wrong severity, which is the whole
    * distinction this file exists to draw.
    */
  private def found(body: String): Vector[String] = unread(body).map {
    case UnreadHeading.NoCardOfItsOwn(h)                  => s"no card of its own: ${h.extractText.trim}"
    case UnreadHeading.EveryHeadingBelowMisfiled(h, line) => s"misfiles below (line $line): ${h.extractText.trim}"
  }

  // ══════════════════ the heading that re-parents everything below it ════

  test("a heading on the line straight after a list line misfiles every heading below it") {
    assertEquals(
      found("- data size\n# 5 Questions\n- How does data move ?\n"),
      Vector("misfiles below (line 2): 5 Questions"),
    )
  }

  test("the marker on such a heading travels with it, so a message can quote it back") {
    assertEquals(
      found("- data size\n# 5 Questions #flashcard/sequence\n"),
      Vector("misfiles below (line 2): 5 Questions #flashcard/sequence"),
    )
  }

  /** THE CASE `ListIndent` RECORDS AS A KNOWN MISS, closed here for nothing. CommonMark lets a
    * list item's paragraph continue on an UNINDENTED line, so the item is still open when the
    * heading arrives — while a scanner reading raw text sees a line at column zero and closes the
    * list, which is what `ListIndentTest`'s "KNOWN MISS" pins. Asking the parse tree needs to
    * know nothing about lazy continuation.
    */
  test("a heading after a list item's LAZY CONTINUATION line is caught too") {
    assertEquals(
      found("- Pin the client to a replica\nwhose log position is current.\n# Next\n"),
      Vector("misfiles below (line 3): Next"),
    )
  }

  test("a numbered list swallows a heading exactly as a bulleted one does") {
    assertEquals(found("1. alpha\n# Next\n"), Vector("misfiles below (line 2): Next"))
  }

  /** A BLOCKQUOTE SWALLOWS A HEADING THE SAME WAY, AND THIS IS WHY THE QUOTE EXCLUSION BELOW IS
    * NOT WRITTEN AS "SKIP ANYTHING INSIDE A QUOTE". Measured against laika-core 1.3.2: the
    * heading below lands INSIDE the `QuotedBlock`, absorbed as the quoted paragraph's lazy
    * continuation. CommonMark does not absorb it — an ATX heading is not paragraph continuation
    * text — so the two readings differ exactly as they do for a list, and every heading below is
    * re-parented in the same way.
    *
    * A check that dropped every heading inside a quote would file this one as agreement and miss
    * it. What separates it from the quoted heading further down is not the container: it is that
    * the author wrote the `#` in the first column, where no quote can hold it.
    */
  test("a heading absorbed by the blockquote above it is the same defect, not a quoted heading") {
    assertEquals(
      found("> quoted paragraph\n# Heading\n\n# Real\n"),
      Vector("misfiles below (line 2): Heading"),
    )
  }

  test("every such heading is reported, in document order, rather than only the first") {
    assertEquals(
      found("- a\n# First\n- b\n\n- c\n# Second\n"),
      Vector("misfiles below (line 2): First", "misfiles below (line 6): Second"),
    )
  }

  // ════════════════════════ the heading that costs only its own card ════

  /** INDENTED INSIDE THE LIST ITEM, WHERE COMMONMARK PUTS IT TOO. The tree is the same shape as
    * the swallowed case above — an `ast.Header` inside a `BulletListItem` — which is precisely
    * why the tree alone cannot tell the two apart and the source has to be asked for one fact.
    *
    * Both parsers agree about where this heading sits, so nothing below it moves and no card
    * changes its key. What is lost is the card this heading would have made, which is worth
    * saying and is not worth refusing a note over.
    */
  test("a heading indented inside a list item costs its own card and nothing else") {
    assertEquals(found("- alpha\n  # Indented\n- beta\n"), Vector("no card of its own: Indented"))
  }

  test("three columns of indent is the same answer as two") {
    assertEquals(found("- alpha\n   # Indented\n- beta\n"), Vector("no card of its own: Indented"))
  }

  // ══════════════════════════════════════════════════════ must NOT report ════

  test("an ordinary document reports nothing, however many headings it has") {
    assertEquals(found("# A\n\ntext\n\n## B\n\nmore\n\n### C\n\nmore still\n"), Vector.empty)
  }

  test("a heading after a list line WITH the blank line is exactly what it looks like") {
    assertEquals(found("- data size\n\n# 5 Questions\n\n- How does data move ?\n"), Vector.empty)
  }

  test("a heading directly after a PARAGRAPH is silent — that one is read correctly") {
    assertEquals(found("some prose\n# Heading\n"), Vector.empty)
  }

  test("a heading directly after a table row is silent") {
    assertEquals(found("| a | b |\n| - | - |\n| 1 | 2 |\n# Heading\n"), Vector.empty)
  }

  /** A HEADING WRITTEN INSIDE A QUOTE IS NOT A DISAGREEMENT. CommonMark puts it inside the quote
    * exactly as this tool does, no card's identity has ever depended on it, and an author who
    * wrote a callout and was told their heading is unreadable would be being refused over a
    * construct working as written.
    *
    * IT IS DROPPED RATHER THAN REPORTED AS THE MILDER CASE, which is a decision and not an
    * oversight: the milder case says "the card you expected is missing", and nobody expects a
    * card from the title line of a callout.
    */
  test("a heading written inside a blockquote is not reported at all") {
    assertEquals(found("> # Quoted\n> quoted body\n\n# Real\n"), Vector.empty)
  }

  test("a `#` line inside a fenced code block is not a heading and is not reported") {
    assertEquals(found("```markdown\n# not a heading\n- alpha\n# nor this\n```\n\n# Real\n"), Vector.empty)
  }

  test("a `#` line in an indented code block is not a heading and is not reported") {
    assertEquals(found("    # not a heading\n\n# Real\n"), Vector.empty)
  }

  /** THE DISAGREEMENT THAT RUNS THE OTHER WAY, and the one false positive this check has to be
    * told about by name. CommonMark — so Obsidian — requires a space after the `#`, and
    * laika-core 1.3.2 does not, so a tag on its own line IS a `Header` to this tool's parser.
    * Straight after a list line it is absorbed like any other and arrives looking exactly like
    * the defect, at column zero and all.
    *
    * MEASURED, NOT ASSUMED. Nothing is lost when it happens: the tag lowers to text and prints as
    * the tag the author typed, which is what Obsidian shows too, so the two readings agree about
    * the OUTCOME while disagreeing about the construct.
    *
    * AND REPORTING IT WOULD DO REAL HARM, which is why this is more than noise. The remedy the
    * message offers is a blank line above the heading, and following it here makes this tool lift
    * the tag into a real section — inserting a heading the author never wrote into the path of
    * every card below it.
    */
  test("a tag on its own line is NOT reported, though this tool's parser reads it as a heading") {
    assertEquals(found("- alpha\n#flashcard/sequence\n"), Vector.empty)
  }

  test("an indented tag line inside a list is not reported either") {
    assertEquals(found("- alpha\n  #flashcard/sequence\n"), Vector.empty)
  }

  test("a real heading is still reported when a tag line sits beside it") {
    assertEquals(
      found("- alpha\n#flashcard/sequence\n\n- beta\n# Real Heading\n"),
      Vector("misfiles below (line 5): Real Heading"),
    )
  }

  /** A SETEXT HEADING HAS NO `#` LINE AT ALL, which is one of the two things that sink a check
    * built on counting `#` lines: such a heading makes the counts agree again and MASKS a
    * swallowed one. Here it is a `Header` like any other, and lifted like any other.
    */
  test("a setext heading is a heading and is not reported") {
    assertEquals(found("Title\n=====\n\ntext\n"), Vector.empty)
  }

  test("display maths spanning several lines is not mistaken for anything") {
    assertEquals(found("$$\na = b\n$$\n\n# Real\n"), Vector.empty)
  }

  test("a document with no headings at all reports nothing rather than failing") {
    assertEquals(found("- alpha\n- beta\n\nSome prose.\n"), Vector.empty)
  }

  // ═════════════════════════════════════════════ which heading is which ════

  /** A REPEATED HEADING TEXT DOES NOT CONFUSE THE REPORT. `# Repeated` appears twice: once
    * properly, once swallowed by the list above it. The swallowed one is reported; the one that
    * became a `Section` is not.
    */
  test("a heading repeated in one note is reported once, for the copy that was swallowed") {
    assertEquals(found("# Repeated\n\ntext\n\n- alpha\n# Repeated\n"), Vector("misfiles below (line 6): Repeated"))
  }

  /** THE SEVERITY IS DECIDED IN DOCUMENT ORDER, AND HAS TO BE. The indented heading below shares
    * its text with a perfectly ordinary heading EARLIER in the file. A lookup that searched the
    * whole file for a `#` line reading `Alpha` would find that earlier line, conclude the author
    * wrote this one in the first column, and refuse a note over a heading that misfiles nothing.
    *
    * That is the expensive direction of wrong — a false refusal — so it is pinned here rather
    * than left to the implementation to remember.
    */
  test("a heading sharing its text with an earlier one is not promoted by that other line") {
    assertEquals(
      found("# Alpha\n\ntext\n\n- item\n  # Alpha\n"),
      Vector("no card of its own: Alpha"),
    )
  }

  test("the line reported is the FILE's line, not the body's") {
    val body = "- data size\n# 5 Questions\n"
    val root = ObsidianSyntax.markupParser.parse(body).fold(e => fail(s"parse: $e"), _.content)
    assertEquals(
      UnreadHeadings.in(root, body, 7).map {
        case UnreadHeading.EveryHeadingBelowMisfiled(_, line) => line
        case UnreadHeading.NoCardOfItsOwn(_)                  => 0
      },
      Vector(8),
    )
  }

  // ═════════════════════════════════════════ what each one is worth ════

  test("only the misfiling heading withholds the note's cards") {
    assertEquals(
      unread("- alpha\n# Swallowed\n").map(_.withholdsTheNotesCards),
      Vector(true),
    )
    assertEquals(
      unread("- alpha\n  # Indented\n").map(_.withholdsTheNotesCards),
      Vector(false),
    )
  }

  // ══════════════════════════════════════════════════════════════ message ════

  private def only(body: String): UnreadHeading =
    unread(body) match
      case Vector(one) => one
      case other       => fail(s"expected exactly one finding, got $other")

  test("the refusal names the heading, the consequence, and the remedy") {
    val message = UnreadHeadings.explain(only("- data size\n# 5 Questions #flashcard/sequence\n"))

    assert(message.contains("5 Questions"), message)
    assert(message.contains("blank line"), message)
    // The consequence, not merely the fact — an author who is not told what a refusal buys them
    // cannot judge whether the tool is being fussy. Same reasoning as `ListIndent.explain`.
    assert(message.contains("wrong parent"), message)
    assert(message.toLowerCase.contains("identity"), message)
  }

  /** THE MILDER MESSAGE MUST NOT BORROW THE SEVERE ONE'S ADVICE, and this is the sharpest edge in
    * the whole check. "Put a blank line above the heading" is the right remedy for a heading the
    * list swallowed, and it is a TRAP for one the author indented deliberately: un-indenting it
    * makes it a real heading, which inserts a new segment into the path of every card below it
    * and re-keys them. So this message says what was lost, says that nothing else moved, and
    * warns what following the obvious fix would cost.
    */
  test("the milder message says what was lost and warns what un-indenting would cost") {
    val message = UnreadHeadings.explain(only("- alpha\n  # Indented\n"))

    assert(message.contains("Indented"), message)
    assert(message.contains("no card"), message)
    assert(message.toLowerCase.contains("identity"), message)
    // It must not send the author to the severe case's remedy, which here would re-key cards.
    assert(!message.contains("Put a blank line"), message)
  }

  /** NAMED IN THE AUTHOR'S VOCABULARY, NOT THE TOOL'S — a standing ruling in
    * `docs/findings/PARSER-DISAGREEMENTS.md`, whose cautionary example is an Obsidian callout
    * reported as `unresolved link id reference: !note`.
    */
  test("neither message names the parser or its internals") {
    Vector(
      UnreadHeadings.explain(only("- data size\n# Swallowed\n")),
      UnreadHeadings.explain(only("- alpha\n  # Indented\n")),
    ).foreach { message =>
      assert(!message.toLowerCase.contains("laika"), message)
      assert(!message.contains("Section"), message)
      assert(!message.contains("Header"), message)
    }
  }

  // ══════════════════════════════════════════ what the scan is told ════

  private val n1 = NoteId.fromFrontmatter("n1").toOption.get

  /** THE LINE IS CARRIED INTO THE REPORT because the severe case HAS one — proving the author
    * wrote the `#` in the first column is what made it severe. The author fixes this by putting
    * a cursor on that line and pressing Return, so sending them to the top of the file would
    * throw away the most useful thing known about the problem.
    */
  test("the misfiling heading becomes a misfiled-key failure, at its own line") {
    assertEquals(
      UnreadHeadings.failure(only("- data size\n# Swallowed\n"), n1, "Note.md") match
        case BuildFailure.KeyMisfiledInFile(id, source, _) => (id.value, source.file, source.line)
        case other                                         => fail(s"wrong failure: $other"),
      ("n1", "Note.md", 2),
    )
  }

  /** AND THE MILDER ONE HAS NO LINE TO GIVE, which is a property of the type rather than an
    * omission: the line number exists only where it was needed to establish the severity, and an
    * indented heading was classified without one. `SourceRef` prints a bare file name for line 0,
    * and the message quotes the heading instead.
    */
  test("the indented heading becomes an unread-heading failure, with no line") {
    assertEquals(
      UnreadHeadings.failure(only("- alpha\n  # Indented\n"), n1, "Note.md") match
        case BuildFailure.HeadingUnreadInFile(id, source, _) => (id.value, source.file, source.line)
        case other                                           => fail(s"wrong failure: $other"),
      ("n1", "Note.md", 0),
    )
  }

  /** BOTH SHELTER THE WHOLE NOTE, AND THE MILDER ONE IS THE LESS OBVIOUS HALF. Its cards are
    * still written, so it may look as though there is nothing to shelter — but a heading this
    * tool cannot see is one whose card it cannot enumerate, and if that card exists in Anki
    * already it would be inferred an orphan and SUSPENDED. Sheltering says "I cannot tell you
    * what this note owns", which is exactly true of both.
    */
  test("both failures shelter the whole note from orphan inference") {
    assertEquals(
      Vector(
        UnreadHeadings.failure(only("- data size\n# Swallowed\n"), n1, "Note.md").shelters,
        UnreadHeadings.failure(only("- alpha\n  # Indented\n"), n1, "Note.md").shelters,
      ),
      Vector(OrphanShelter.WholeNote(n1), OrphanShelter.WholeNote(n1)),
    )
  }
