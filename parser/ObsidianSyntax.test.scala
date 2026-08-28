package obsidiananki.parser

import laika.ast.*
import obsidiananki.parser.ObsidianSyntax.{Highlighted, ObsidianEmbed}

class ObsidianSyntaxTest extends munit.FunSuite:

  /** Exercise the CANONICAL parser, not a hand-rolled one. A test that builds its own parser
    * proves the dialect works in isolation while saying nothing about the configuration the
    * tool actually runs — and two elements of that configuration fail silently when omitted.
    */
  val parser = ObsidianSyntax.markupParser

  def parse(src: String): RootElement =
    parser.parse(src).fold(e => fail(s"parse failed: $e"), _.content)

  /** Depth-first collection of Section nodes, so heading paths can be asserted. */
  def sections(e: Element): List[Section] = e match
    case s: Section         => s :: s.content.flatMap(sections).toList
    case bc: BlockContainer => bc.content.flatMap(sections).toList
    case _                  => Nil

  /** A table Cell holds Blocks, not Spans, so it has no extractText of its own. */
  def cellText(c: Cell): String =
    c.content.collect { case sc: SpanContainer => sc.extractText }.mkString(" ").trim

  def headingPath(root: RootElement): List[List[String]] =
    def walk(e: Element, prefix: List[String]): List[List[String]] = e match
      case s: Section =>
        val here = prefix :+ s.header.extractText.trim
        here :: s.content.flatMap(walk(_, here)).toList
      case bc: BlockContainer => bc.content.flatMap(walk(_, prefix)).toList
      case _                  => Nil
    walk(root, Nil)

  // ---------------------------------------------------------------- displayText ----

  test("displayText: bare target") {
    assertEquals(ObsidianSyntax.displayText("Consistency"), "Consistency")
  }

  test("displayText: alias wins over target") {
    assertEquals(ObsidianSyntax.displayText("Availability|Availabilities"), "Availabilities")
  }

  test("displayText: opaque id target with a meaningful alias") {
    assertEquals(
      ObsidianSyntax.displayText("1786868697-SMIR|Module 2 - Moving Data"),
      "Module 2 - Moving Data",
    )
  }

  test("displayText: #heading fragment is stripped from the target") {
    assertEquals(ObsidianSyntax.displayText("Consistency#Linearizability"), "Consistency")
  }

  test("displayText: ^blockid is stripped from the target") {
    assertEquals(ObsidianSyntax.displayText("Consistency^abc123"), "Consistency")
  }

  test("displayText: fragment-only link falls back to the fragment") {
    assertEquals(ObsidianSyntax.displayText("#Linearizability"), "Linearizability")
  }

  test("displayText: surrounding whitespace is trimmed") {
    assertEquals(ObsidianSyntax.displayText("  Consistency  "), "Consistency")
  }

  test("displayText: blank alias falls back to the target") {
    assertEquals(ObsidianSyntax.displayText("Consistency|   "), "Consistency")
  }

  // ---------------------------------------------------------------- body spans ----

  test("wikilink in body prose keeps its text (the data-loss regression)") {
    val root = parse("Stronger than [[Sequential Consistency]] here.")
    val text = root.content.collect { case p: Paragraph => p.extractText }.mkString
    assertEquals(text, "Stronger than Sequential Consistency here.")
  }

  test("adjacent wikilinks do not swallow the text between them") {
    val root = parse("A [[Client]] sends a [[Request]] to a [[Server]] now.")
    val text = root.content.collect { case p: Paragraph => p.extractText }.mkString
    assertEquals(text, "A Client sends a Request to a Server now.")
  }

  test("wikilink carries the wikilink style so the decision stays reversible") {
    val root  = parse("See [[Consistency]].")
    val texts = root.collect { case t: Text if t.options.styles.contains("wikilink") => t.content }
    assertEquals(texts.toList, List("Consistency"))
  }

  test("unclosed wikilink degrades to plain text instead of eating the document") {
    val root = parse("A [[dangling reference\n\nAnd a later paragraph.")
    val text = root.content.collect { case p: Paragraph => p.extractText }.mkString(" ")
    assert(text.contains("dangling reference"), s"lost the dangling text: $text")
    assert(text.contains("And a later paragraph."), s"lost the later paragraph: $text")
  }

  // ---------------------------------------------------------------- headings ----

  test("REGRESSION B2: a marked heading containing a wikilink still becomes a Section") {
    val root = parse(
      """|# Messaging
         |
         |## [[Message Queue]] #flashcard/2way
         |
         |A buffer that decouples producer from consumer.
         |""".stripMargin
    )
    val found = sections(root).map(_.header.extractText.trim)
    assert(
      found.contains("Message Queue #flashcard/2way"),
      s"the wikilink heading did not become a Section. Sections found: $found",
    )
  }

  test("REGRESSION B2: headings below a wikilink heading keep their true ancestor") {
    val root = parse(
      """|# Messaging
         |
         |## [[Message Queue]]
         |
         |### Definition #flashcard/3way
         |
         |A buffer that decouples producer from consumer.
         |""".stripMargin
    )
    val paths = headingPath(root)
    assert(
      paths.contains(List("Messaging", "Message Queue", "Definition #flashcard/3way")),
      s"subtree re-parented. Paths: $paths",
    )
  }

  test("nine sibling wikilink headings stay distinct rather than collapsing") {
    val src = (1 to 9).map(i => s"## [[Concept $i]]\n\nBody $i.\n").mkString("\n")
    val found = sections(parse(src)).map(_.header.extractText.trim)
    assertEquals(found.distinct.size, 9, s"headings collapsed: $found")
  }

  // ---------------------------------------------------------------- embeds ----

  test("embed becomes a distinct node so extraction can reject it loudly") {
    val root    = parse("An embed: ![[diagram.png]]")
    val embeds  = root.collect { case e: ObsidianEmbed => e.target }
    assertEquals(embeds.toList, List("diagram.png"))
  }

  test("embed is not silently downgraded to a wikilink") {
    val root  = parse("![[diagram.png]]")
    val texts = root.collect { case t: Text if t.options.styles.contains("wikilink") => t.content }
    assertEquals(texts.toList, Nil, "the ![[...]] embed was parsed as a plain wikilink")
  }

  // ---------------------------------------------------------------- no regressions ----

  test("ordinary markdown links are untouched") {
    val root  = parse("See [the docs](https://example.com).")
    val links = root.collect { case l: SpanLink => l.extractText }
    assertEquals(links.toList, List("the docs"))
  }

  test("inline code is untouched") {
    // Laika models an inline code span as Literal, not InlineCode.
    val root = parse("Use `List[Int]` here.")
    val code = root.collect { case l: Literal => l.content }
    assertEquals(code.toList, List("List[Int]"))
  }

  /** GitHubFlavor regression. Laika's base `Markdown` swallows the language tag into the
    * content of a plain `Literal` ("scala\nval x = 1"); only GFM yields a real `CodeBlock`.
    */
  test("fenced code block keeps its language (requires GitHubFlavor)") {
    val root   = parse("```scala\nval x = 1\n```\n")
    val blocks = root.collect { case b: CodeBlock => (b.language, b.extractText) }
    assertEquals(blocks.toList, List(("scala", "val x = 1")))
  }

  /** THE table card kind depends entirely on this. Laika's base `Markdown` has no table
    * support at all: the whole table parses as ONE Paragraph whose text is the literal pipe
    * characters, so `#flashcard/table` would silently yield a single garbage card instead of
    * n pair cards plus a row card.
    */
  test("REGRESSION: a markdown table parses as a Table (requires GitHubFlavor)") {
    val root = parse(
      """|## Cost / benefit #flashcard/table
         |
         || Pattern | Benefit         | Cost                |
         || ------- | --------------- | ------------------- |
         || Queue   | Load Absorption | Delay & Duplication |
         |""".stripMargin
    )
    val tables = root.collect { case t: Table => t }
    assertEquals(tables.size, 1, "no Table node — GitHubFlavor is not enabled")

    val headers = tables.head.head.content.flatMap(_.content).map(cellText).toList
    assertEquals(headers, List("Pattern", "Benefit", "Cost"))

    val firstRow = tables.head.body.content.head.content.map(cellText).toList
    assertEquals(firstRow, List("Queue", "Load Absorption", "Delay & Duplication"))
  }

  test("a wikilink inside a table cell keeps its display text") {
    val root = parse(
      """|| Pattern | Benefit            |
         || ------- | ------------------ |
         || Queue   | [[Load Absorption]] |
         |""".stripMargin
    )
    val cells = root.collect { case t: Table => t }.head.body.content.head.content
      .map(cellText)
      .toList
    assertEquals(cells, List("Queue", "Load Absorption"))
  }

  /** The review flagged this specifically: a literal `==highlight==` written inside a code
    * span occurs in the reference vault. Lexing the body with a raw regex rather than over
    * the AST would false-positive on it and mint a cloze deletion out of documentation.
    */
  test("a highlight inside a code span is NOT a cloze deletion") {
    val root = parse("Write `==highlight==` to mark a deletion.")
    val hs   = root.collect { case h: Highlighted => h.extractText }
    assertEquals(hs.toList, Nil, "a code span was lexed as a cloze deletion")
    // THE OLD SPELLING ON PURPOSE. A code span holds exactly what the author typed, and the
    // point of this test is that nothing lexes it. It is also the more searching case since
    // 2026-08-28: a bare `==highlight==` is no longer a cloze anywhere, so a code span holding
    // one must come out as those characters and nothing else.
    assertEquals(root.collect { case l: Literal => l.content }.toList, List("==highlight=="))
  }

  test("a wikilink inside a code span is NOT a wikilink") {
    val root = parse("Write `[[Target]]` to link.")
    val wl   = root.collect { case t: Text if t.options.styles.contains("wikilink") => t.content }
    assertEquals(wl.toList, Nil, "a code span was parsed as a wikilink")
    assertEquals(root.collect { case l: Literal => l.content }.toList, List("[[Target]]"))
  }

  test("highlights still parse alongside the wikilink parser") {
    val root = parse("The ==<<Sun>>== is a star and ==<<Jupiter>>== is a gas giant.")
    val hs   = root.collect { case h: Highlighted => h.extractText }
    assertEquals(hs.toList, List("Sun", "Jupiter"))
  }

  test("a wikilink inside a highlight keeps its display text") {
    // BRACKETED BY HAND. The migration of 2026-08-28 skipped this fixture deliberately: its
    // content holds a pipe, and a script cannot tell a wikilink's alias separator from a cloze
    // group label. Getting that wrong would have turned `[[Quorum|majority]]` into group 
    // "Quorum" silently.
    val root = parse("A ==<<[[Quorum|majority]]>>== is required.")
    val hs   = root.collect { case h: Highlighted => h.extractText }
    assertEquals(hs.toList, List("majority"))
  }

  // ------------------------------------------------- task lists: rejected by name ----

  /** RULED: task lists are not supported. The hazard was never "they do not work" — it was
    * that their text vanished SILENTLY, because Laika's GitHubFlavor does not implement them
    * and CommonMark reads `[ ]` as an unresolvable link reference. Recognising the construct
    * is what lets extraction reject it BY TYPE, with a name.
    */
  test("a task-list marker is recognised as such, not as a broken link reference") {
    val root    = parse("- [ ] unchecked\n- [x] checked\n")
    val markers = root.collect { case t: ObsidianSyntax.TaskListMarker => t.checked }
    assertEquals(markers.toList, List(false, true))
  }

  test("recognising task lists is what lets STRICT parsing succeed on them") {
    // Under the old configuration this document only parsed because lenient mode swallowed
    // two "unresolved link id reference" errors.
    assert(ObsidianSyntax.markupParser.parse("- [ ] a\n- [x] b\n").isRight)
  }

  test("only the three task-marker forms match; other bracket contents do not") {
    // The parser is exactly three characters wide, so it cannot swallow arbitrary brackets.
    val root = parse("- [ ] a\n- [x] b\n- [X] c\n")
    assertEquals(root.collect { case t: ObsidianSyntax.TaskListMarker => t.checked }.toList,
      List(false, true, true))
  }

  /** THE ACCEPTED COST OF STRICTNESS, recorded so it is a known trade rather than a
    * surprise. Laika reports an unresolved shortcut reference as an error, so bare bracketed
    * prose — an array index `[0]`, a citation `[1]` — fails the parse.
    *
    * Measured before accepting it: with task lists recognised, BOTH vaults parse 13/13 and
    * 9/9 strictly, so this costs nothing on real content today. And the failure is LOUD,
    * which is the whole point of the trade: under lenient mode the same construct lost its
    * text in silence.
    */
  test("bare bracketed prose FAILS loudly under strict parsing — the accepted trade") {
    val result = ObsidianSyntax.markupParser.parse("An array index like [0] in prose.")
    assert(result.isLeft, "bracketed prose was silently accepted; strictness is not on")
  }

  // ------------------------------------------------- strict parsing ----

  /** Lenient mode is OFF. It existed only to get past wikilinks and task lists, both of
    * which are now handled explicitly. Keeping it would mean every FUTURE unknown construct
    * still lost its text quietly — a mechanism that hides one class of failure hides all.
    */
  test("parsing is STRICT — an unresolvable reference is an error, not a silent drop") {
    val result = ObsidianSyntax.markupParser.parse("See [some undefined reference][nope].")
    assert(result.isLeft, "an unresolved reference was swallowed instead of reported")
  }

  // ------------------------------------------------- maths: pinned, not supported ----

  /** MATHS IS NOT PARSED, AND THIS SECTION PINS WHAT HAPPENS INSTEAD. `$` is a delimiter in
    * none of this dialect's span parsers, nor in Laika's `Markdown.spanParsers`, nor in
    * `GitHubFlavor`, so `$…$` and `$$…$$` are read as ORDINARY PROSE and reach a card as
    * literal text. That is row 5 of `docs/PARSER-DISAGREEMENTS.md`; `docs/MATHS-ON-A-CARD.md`
    * is the long form, and these are the measurements it cites.
    *
    * THESE ARE CHARACTERISATION TESTS. They assert what the parser DOES today, two of them
    * asserting a corruption rather than a behaviour anyone wants. They exist so that the
    * claims in that document stay falsifiable instead of becoming folklore — the document
    * says the parser eats a TeX row separator, and this is where that stops being a sentence
    * somebody wrote down and starts being something the build re-checks.
    *
    * THEY ARE EXPECTED TO GO RED THE DAY MATHS IS PARSED, and that is their second job.
    */
  test("maths: TeX that is not markdown survives verbatim — this is the common case") {
    val src  = """$$ \forall f \quad \text{Id} \circ f = f \circ \text{Id} = f $$"""
    val text = parse(src).content.collect { case p: Paragraph => p.extractText }.mkString
    assertEquals(text, src)
  }

  /** `\` IS ASCII PUNCTUATION, SO MARKDOWN OWNS IT. `\\` is markdown's escape for a literal
    * backslash and arrives as one — but `\\` is also TeX's ROW SEPARATOR inside `align`,
    * `gather`, `array` and `cases`. Every multi-line maths block is therefore already corrupt
    * before any of this tool's own code runs, and nothing downstream can recover it: the
    * second backslash is not in the parse tree to recover.
    */
  test("maths: a TeX row separator is eaten by the markdown escape rule") {
    val src  = """$$\begin{align} a &= b \\ c &= d \end{align}$$"""
    val text = parse(src).content.collect { case p: Paragraph => p.extractText }.mkString
    assertEquals(text, """$$\begin{align} a &= b \ c &= d \end{align}$$""")
  }

  /** SUBSCRIPT SURVIVAL DEPENDS ON HOW MANY OTHER SUBSCRIPTS SHARE THE PARAGRAPH, which is
    * what makes this worse than it first reads. Two underscores pair, so the span between
    * them becomes `Emphasized` and BOTH delimiters are consumed; the third has no partner and
    * keeps its underscore. Note that CommonMark would not pair these at all — it forbids
    * intraword `_` emphasis — so this is a divergence from what Obsidian renders, not merely
    * an inconvenience.
    */
  test("maths: paired subscripts are consumed as emphasis, and an odd one out survives") {
    val text = parse("""$$x_1 + y_1 = z_1$$""")
      .content.collect { case p: Paragraph => p.extractText }.mkString
    assertEquals(text, """$$x1 + y1 = z_1$$""")
  }

  /** THE TRIPWIRE. A card's key is derived from `section.header.extractText`, so this string
    * is not cosmetic — it is identity. Changing what this test expects is a MIGRATION
    * DECISION, not a test fix: a changed key is an orphaned card holding its review history
    * and claimed by nothing, plus a replacement starting from zero.
    */
  test("maths in a heading reaches the card key WITH its dollars intact") {
    val root = parse("## Notation (given 2 sets, $A$ and $B$)\n\nbody\n")
    assertEquals(headingPath(root), List(List("Notation (given 2 sets, $A$ and $B$)")))
  }

  /** WHY THE TRIPWIRE IS ARMED, pinned on a construct that already exists so the mechanism is
    * demonstrable today rather than hypothetical. `ObsidianComment` declares `text: String`
    * rather than `content: Seq[Span]` — deliberately, since that is what keeps its innards
    * away from the inline parsers — and is therefore not a `SpanContainer`. Laika's
    * `extractText` is a trait match with a silent `case _ => ""`, so such a node contributes
    * NOTHING to a heading.
    *
    * A maths node would want exactly that shape, for exactly that reason. This test is the
    * evidence that adopting it re-keys every heading containing maths.
    */
  test("a node that is not a SpanContainer vanishes from a heading's extractText") {
    val root = parse("## Notation %%hidden%% here\n\nbody\n")
    assertEquals(headingPath(root), List(List("Notation  here")))
  }
