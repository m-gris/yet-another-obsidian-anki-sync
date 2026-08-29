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

  // ------------------- markdown's own rules, which are why maths is captured raw ----

  /** WHY THE MATHS PARSER REFUSES TO DESCEND, pinned on ORDINARY PROSE so the mechanism stays
    * visible now that maths no longer travels through it.
    *
    * WHAT THIS SECTION USED TO SAY, AND WHY IT CHANGED. Until 2026-08-29 these were
    * characterisation tests asserting these same two corruptions INSIDE `$$…$$`, because `$`
    * was a delimiter nowhere and maths was read as prose — row 5 of
    * `docs/PARSER-DISAGREEMENTS.md`, and the reason `docs/MATHS-ON-A-CARD.md` exists. They were
    * written to go red the day a maths parser landed, and they did.
    *
    * THEY ARE REPOINTED RATHER THAN DELETED BECAUSE THE CORRUPTION HAS NOT GONE ANYWHERE. It is
    * markdown behaving correctly, and all that changed is that maths is now lifted out before
    * markdown sees it. So this section is the standing answer to anyone proposing a `recursive`
    * maths parser: these two are what such a parser would have to survive, and it cannot.
    */
  test("markdown eats a doubled backslash in prose — which is TeX's row separator") {
    val text = parse("""a \\ b""").content.collect { case p: Paragraph => p.extractText }.mkString
    assertEquals(text, """a \ b""")
  }

  /** POSITION-DEPENDENT, which is what makes it worse than it first reads: the third subscript
    * keeps its underscore because it has no partner left to pair with. Note also that
    * CommonMark would not pair these at all, forbidding intraword `_` emphasis, so this is a
    * divergence from what Obsidian renders rather than merely an inconvenience.
    */
  test("markdown pairs underscores in prose as emphasis — which are TeX's subscripts") {
    val text =
      parse("""x_1 + y_1 = z_1""").content.collect { case p: Paragraph => p.extractText }.mkString
    assertEquals(text, """x1 + y1 = z_1""")
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

  // ------------------------------------------------- maths: recognised, captured raw ----

  def displayTex(src: String): List[String] =
    parse(src).collect { case m: ObsidianSyntax.MathDisplay => m.tex }.toList

  def inlineTex(src: String): List[String] =
    parse(src).collect { case m: ObsidianSyntax.MathInline => m.tex }.toList

  def noMaths(src: String): Unit =
    assertEquals(displayTex(src) ++ inlineTex(src), Nil, s"maths was found in: $src")

  /** THE POINT OF THE WHOLE SLICE, IN ONE ASSERTION. The row separator survives. Above, under
    * "maths: pinned, not supported", the same input loses one of its backslashes, because
    * markdown owns that character and reads `\\` as an escape for it. Capturing the span
    * without descending into it is what prevents that, and nothing downstream could have
    * recovered what was already gone from the tree.
    */
  test("maths: a row separator survives capture, where prose parsing destroyed it") {
    val tex = """\begin{align} a &= b \\ c &= d \end{align}"""
    assertEquals(displayTex("$$" + tex + "$$"), List(tex))
  }

  test("maths: a subscript pair survives capture, where prose parsing ate it as emphasis") {
    assertEquals(displayTex("""$$x_1 + y_1 = z_1$$"""), List("""x_1 + y_1 = z_1"""))
  }

  /** DOUBLE DOLLARS ARE TRIED FIRST, and getting this wrong is not a near miss: read
    * single-first, `$$B^A$$` becomes an empty inline span, the text `B^A`, and another empty
    * inline span.
    */
  test("maths: $$…$$ is one display span, not two empty inline ones") {
    assertEquals(displayTex("""$$B^A$$"""), List("B^A"))
    assertEquals(inlineTex("""$$B^A$$"""), Nil)
  }

  test("maths: single dollars give inline maths, and the delimiters are not in the payload") {
    assertEquals(inlineTex("""The set $B^A$ of functions"""), List("B^A"))
  }

  test("maths: prose either side of an inline span is preserved") {
    val text = parse("""The set $B^A$ of functions""")
      .collect { case p: Paragraph => p.content.collect { case t: Text => t.content } }
      .flatten
      .toList
    assertEquals(text, List("The set ", " of functions"))
  }

  // ── the false positives, which are the whole risk of making `$` a delimiter ──────────

  /** A LONE DOLLAR IN PROSE ABOUT MONEY MUST NOT OPEN MATHS. This is the rule pandoc's
    * `tex_math_dollars` settled, worth copying rather than re-deriving: an opening `$` needs a
    * non-space immediately to its right, a closing `$` needs a non-space immediately to its
    * left, and the closer must not be followed by a digit. Here the second dollar has a space
    * before it so it cannot close, and there is no other candidate.
    */
  test("maths: prices do not become maths") {
    noMaths("""It costs $5 to $10 today.""")
  }

  test("maths: an opening dollar followed by a space opens nothing") {
    noMaths("""a $ x $ b""")
  }

  test("maths: an unclosed delimiter stays prose rather than swallowing the line") {
    noMaths("""a $$ b""")
    noMaths("""a $ b""")
  }

  /** WHAT AN AUTHOR TYPES TO MEAN A DOLLAR SIGN. Pinned rather than assumed, because which
    * layer consumes the backslash decides whether this is even our problem.
    */
  test("maths: an escaped dollar does not open maths") {
    noMaths("""costs \$5 and \$10""")
  }

  /** MATHS IN A HEADING IS THE CASE THAT MOVES A CARD KEY, so the mechanism is pinned rather
    * than described. The node is not a `SpanContainer`, so `extractText` — a trait match with
    * a silent fallthrough for anything else — contributes nothing for it. The heading above,
    * under "maths: pinned, not supported", asserts what this same input keys as TODAY; this
    * one asserts what it keys as once maths parses, and the pair IS the migration.
    */
  test("maths in a heading is recognised, and drops out of the extracted text") {
    val src  = "# Notation (Given 2 sets, $A$ and $B$)\n\nbody\n"
    assertEquals(inlineTex(src), List("A", "B"))
    assertEquals(headingPath(parse(src)), List(List("Notation (Given 2 sets,  and )")))
  }
