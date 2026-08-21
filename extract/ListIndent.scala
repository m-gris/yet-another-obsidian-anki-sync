package obsidiananki.extract

/** Finds nested list items that this tool's markdown parser will read differently from the way
  * the author meant them, and than the way Obsidian shows them back.
  *
  * ═══════════════════════════════════════════════════════════════════════════════════════════
  * THE DISAGREEMENT, STATED ONCE
  * ═══════════════════════════════════════════════════════════════════════════════════════════
  *
  * Markdown has to decide, for an indented list line, whether it is a CHILD of the item above
  * or the start of something new. CommonMark — which is what Obsidian implements — says it is a
  * child when it is indented at least as far as the parent item's TEXT begins. For `- alpha`,
  * text begins at column 2, so two spaces is enough.
  *
  * laika-core 1.3.2 wants FOUR spaces, or a tab. This is not a bug on its side: Laika's own
  * spec-compliance page says it follows the ORIGINAL Markdown description plus the PHP Markdown
  * test suite, and states that it does not run the CommonMark test suite. Original Markdown
  * wanted four. Pandoc and Python-Markdown behave the same way and have had the same complaint
  * open for years. So this will not be fixed upstream, and this tool has to cope.
  *
  * WHAT COPING HAS TO MEAN HERE, and why silence is not an option. Given
  *
  * {{{
  * - Route reads to the leader
  *   - Simple, but concentrates read load on one node.
  * - Pin the client to a replica
  * }}}
  *
  * Laika does not merely decline to nest the second line. It CLOSES the list and opens a new
  * one, which the third line then joins. The result is that "Simple, but concentrates read load
  * on one node" ends up a SIBLING of "Pin the client to a replica" — presented as a third
  * option rather than as a remark about the first. The card then asserts something the note
  * never said, and it looks perfectly well-formed while doing it. That is this project's
  * signature failure mode, so it is refused rather than rendered.
  *
  * REPAIR IS NOT AVAILABLE, which is why this scan reads SOURCE TEXT rather than the parsed
  * tree. Indentation is consumed by the parser. By the time the three lists exist, nothing
  * records that one line was indented and the next was not — they are genuinely peers. Any
  * repair after the fact would be a guess at the author's intent.
  *
  * ═══════════════════════════════════════════════════════════════════════════════════════════
  * WHO CAN ACTUALLY TRIGGER THIS
  * ═══════════════════════════════════════════════════════════════════════════════════════════
  *
  * Not Obsidian. Its own Tab key writes one tab or four spaces and nothing else — both of which
  * Laika reads correctly — and an Obsidian maintainer has said on the forum that indenting with
  * anything else "will be broken" for Obsidian too. So a vault written only through Obsidian
  * cannot reach this check.
  *
  * What reaches it is text that arrived some other way: web clippings, imports, a hand edit in
  * another editor, a formatter such as Prettier (which indents by two), or an agent writing a
  * note directly into the vault. That is the whole point of scanning: those are exactly the
  * files nobody proofreads.
  */
object ListIndent:

  /** One list line that CommonMark would nest and laika-core 1.3.2 would not.
    *
    * @param line   the ORIGINAL FILE's line, 1-based, so it can be typed into a jump-to-line box
    * @param found  columns of indentation the line actually has
    * @param needed columns it must have for the parser to read it as a sub-item
    * @param text   the offending line, trimmed, so the message can quote it back
    */
  final case class Finding(line: Int, found: Int, needed: Int, text: String)

  /** Every [[Finding]] in one note, indexed by the heading whose body it falls in.
    *
    * A note is scanned ONCE and every marked heading then asks this what belongs to it, so the
    * cost does not multiply by the number of cards in a file.
    *
    * OWNERSHIP IS "THE NEAREST PRECEDING HEADING", which needs no heading-level arithmetic:
    * body text belongs to the last heading above it, whatever the levels around it are doing.
    * Findings above the first heading are keyed to line 0 and belong to no card.
    */
  final class Report private[ListIndent] (private val byHeadingLine: Map[Int, Vector[Finding]]):

    /** The findings inside the body of the heading on `headingLine`, in document order.
      *
      * TOTAL BY CONSTRUCTION, and that is why the map is private. Absence here means "that
      * heading's body is clean", which is the ordinary case rather than a lookup that failed,
      * so it is answered with an empty vector inside the type that owns the index — not with a
      * default supplied by every caller.
      */
    def under(headingLine: Int): Vector[Finding] =
      byHeadingLine.getOrElse(headingLine, Vector.empty)

    /** Every finding in the note, in document order, whatever heading owns it. */
    val all: Vector[Finding] = byHeadingLine.values.toVector.flatten.sortBy(_.line)

  /** Scan a note's markdown body — frontmatter already removed — for the disagreement above.
    *
    * A HAND-WRITTEN LINE SCANNER, and it has to be: the whole point is to see the indentation
    * that the real parser is about to consume, so it cannot be answered from a parsed tree.
    * It is deliberately SMALLER than a markdown parser — it tracks fences, headings and open
    * list items, and nothing else — because every construct it learns to understand is another
    * construct it can misunderstand, and a false refusal costs more here than a missed one.
    */
  def scan(body: String, bodyFirstLine: Int): Report =
    val found = Vector.newBuilder[(Int, Finding)]

    // The heading whose body we are inside. 0 until the first one, which is why a finding above
    // every heading belongs to no card.
    var heading = 0

    // The open fence's delimiter character and length, or None outside a fenced block.
    var fence = Option.empty[(Char, Int)]

    // Open list items, innermost first. See `Open` for what the two columns mean.
    var open = List.empty[Open]

    body.linesIterator.zipWithIndex.foreach { (line, index) =>
      // The FILE's line number, not the body's. An author reads this out of an error message
      // and types it into a jump-to-line box, so it has to agree with what the editor shows —
      // and with the heading lines this report is keyed by. See `SplitNote.bodyFirstLine`.
      val lineNumber = index + bodyFirstLine

      fence match
        // Inside a fenced block nothing is a list, a heading, or anything else. A closing fence
        // is the same character, at least as long, and carries no info string.
        case Some((char, length)) =>
          fenceAt(line) match
            case Some((c, l, info)) if c == char && l >= length && info.isEmpty => fence = None
            case _                                                             => ()

        case None =>
          if fenceAt(line).isDefined then fence = fenceAt(line).map((c, l, _) => (c, l))
          else if isHeading(line) then
            heading = lineNumber
            // A heading closes every open list. Without this, a list under one heading would
            // adopt an indented list under the next as its child.
            open = List.empty
          else if line.isBlank then ()
          else
            val indent = indentWidth(line.takeWhile(isSpaceOrTab))

            // Close every item this line has dedented out of. An item stays open only while the
            // line reaches its CONTENT column, which is the same rule for a list line and for a
            // paragraph — and applying it to paragraphs too is what stops prose between two
            // lists from making the second one look like a child of the first.
            open = open.dropWhile(item => indent < item.contentColumn)

            listMarkerAt(line).foreach { marker =>
              open.headOption.foreach { parent =>
                // Reaching the parent's content column means CommonMark — and so Obsidian —
                // reads this line as a SUB-ITEM. laika-core 1.3.2 needs four columns past the
                // parent's own indent before it agrees. Between the two lies the disagreement.
                val needed = parent.indent + LaikaNestingColumns
                if indent < needed then
                  found += heading -> Finding(lineNumber, indent, needed, line.trim)
              }
              open = Open(indent, indent + marker.width) :: open
            }
    }

    Report(found.result().groupMap(_._1)(_._2))

  /** The refusal an author reads, built from the findings of ONE card.
    *
    * Separate from [[Finding]] so that the wording lives next to the explanation of what is
    * wrong, and can be tested without building an `Extractor`.
    *
    * IT SAYS WHAT WOULD HAVE HAPPENED, not just that something is wrong. An author who is told
    * only "bad indentation" has no way to judge whether the tool is being fussy; an author who
    * is told the items would be REGROUPED knows immediately that the card would have lied. The
    * last clause names the likely culprit, because the one editor that cannot have caused this
    * is the one the author was probably using.
    */
  def explain(findings: Vector[Finding]): String =
    val where = findings
      .map(f => s"line ${f.line} has ${f.found} (needs ${f.needed}): ${f.text}")
      .mkString(" | ")
    s"nested list indented too little for this tool's markdown parser, which reads such a line " +
      s"as the start of a NEW list rather than as a sub-item — the items around it are regrouped, " +
      s"so the card would say something this note does not. Indent to 4 spaces or one tab. " +
      s"[$where]. Obsidian's own Tab key writes 4 spaces or a tab, so 2-space indentation " +
      s"usually arrives from another editor, a web clipping, or a formatter"

  // ══════════════════════════════════════════════════════════════ internals ════

  /** Columns of indentation laika-core 1.3.2 wants past a parent item's own indent before it
    * will read a line as that item's child. Measured against the parser, not assumed.
    */
  private val LaikaNestingColumns = 4

  /** An open list item.
    *
    * @param indent        columns before its marker — what a CHILD is measured against
    * @param contentColumn columns before its text — what tells us a line is still INSIDE it
    */
  private final case class Open(indent: Int, contentColumn: Int)

  /** A list marker at the start of a line. `width` spans the marker and the spaces after it, so
    * `indent + width` is the column its text begins at.
    */
  private final case class Marker(width: Int)

  private def isSpaceOrTab(c: Char): Boolean = c == ' ' || c == '\t'

  /** A tab advances to the next multiple of four, as CommonMark specifies — which is also why a
    * tab-indented sub-item lands on 4 and satisfies the parser.
    */
  private def indentWidth(leading: String): Int =
    leading.foldLeft(0) {
      case (width, '\t') => (width / 4 + 1) * 4
      case (width, _)    => width + 1
    }

  /** ATX headings only, and anchored at column zero rather than allowing CommonMark's three
    * columns of slack — deliberately, so that this scanner's idea of "which heading owns this
    * line" cannot drift from `Extractor`'s `LineIndex`, which also anchors at column zero.
    */
  private val HeadingLine = """^#{1,6}(?:\s|$)""".r

  private def isHeading(line: String): Boolean = HeadingLine.findPrefixOf(line).isDefined

  private val FenceLine = """^[ \t]*(`{3,}|~{3,})(.*)$""".r

  private def fenceAt(line: String): Option[(Char, Int, String)] = line match
    case FenceLine(delimiter, info) => Some((delimiter.head, delimiter.length, info.trim))
    case _                          => None

  /** A bullet (`-`, `*`, `+`) or an ordered marker (`1.`, `1)`), followed by at least one space
    * — or by end of line, which is a legal empty item.
    *
    * REQUIRING THE SPACE is what keeps `---` (a thematic break, and a frontmatter delimiter)
    * and `*emphasis*` from being read as list items.
    */
  private val MarkerLine  = """^[ \t]*([-*+]|\d{1,9}[.)])([ \t]+)\S.*$""".r
  private val EmptyMarker = """^[ \t]*([-*+]|\d{1,9}[.)])[ \t]*$""".r

  private def listMarkerAt(line: String): Option[Marker] = line match
    case MarkerLine(marker, spaces) => Some(Marker(marker.length + spaces.length))
    // An item with no text still opens one, and its text column is where text would have gone.
    case EmptyMarker(marker) => Some(Marker(marker.length + 1))
    case _                   => None
