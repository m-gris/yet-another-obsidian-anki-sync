package obsidiananki.extract

/** What [[ListIndent]] must and must NOT flag.
  *
  * ==The asymmetry these tests encode==
  *
  * A missed case yields a card that is merely as wrong as it was before this check existed. A
  * FALSE POSITIVE refuses a card that was fine, and an author who is refused for no reason
  * learns to distrust every refusal this tool makes. So the "must not flag" half below is the
  * load-bearing half, and where the two pull against each other this scanner is built to miss
  * rather than to over-report. The one known miss is pinned as a test of its own, at the bottom,
  * so it is a recorded limit rather than a surprise.
  *
  * ==Why these expectations are not guesses==
  *
  * Every "must flag" case here was run through laika-core 1.3.2 directly before being written
  * down: at two and three spaces the parser splits one list into several and regroups the items,
  * at four spaces and at a tab it nests correctly. The numbers in these tests are observations,
  * not predictions.
  */
class ListIndentTest extends munit.FunSuite:

  private def linesFlagged(body: String): Vector[Int] =
    ListIndent.scan(body, bodyFirstLine = 1).all.map(_.line)

  // ════════════════════════════════════════════════════════════ must flag ════

  test("a bullet sub-item indented two spaces is flagged, and says what it needs") {
    val found = ListIndent.scan("- alpha\n  - alpha sub\n- beta\n", bodyFirstLine = 1).all
    assertEquals(found.map(f => (f.line, f.found, f.needed)), Vector((2, 2, 4)))
    assertEquals(found.head.text, "- alpha sub")
  }

  test("three spaces is flagged too — the cutoff is four, not 'more than two'") {
    assertEquals(linesFlagged("- alpha\n   - alpha sub\n"), Vector(2))
  }

  test("a numbered sub-item is flagged on the same rule") {
    // `1.` puts its text at column 3, so three spaces is what CommonMark reads as nesting and
    // what Laika does not. Two spaces is NOT nesting to either of them — see the sibling test.
    assertEquals(linesFlagged("1. alpha\n   1. alpha sub\n"), Vector(2))
  }

  test("every offending line is reported, not just the first") {
    val body = "- alpha\n  - alpha sub\n- beta\n  - beta sub\n"
    assertEquals(linesFlagged(body), Vector(2, 4))
  }

  test("a second level is flagged against ITS OWN parent, not against column zero") {
    // The inner item's parent sits at indent 4, so the inner item needs 8.
    val found = ListIndent.scan("- alpha\n    - sub\n      - sub sub\n", bodyFirstLine = 1).all
    assertEquals(found.map(f => (f.line, f.found, f.needed)), Vector((3, 6, 8)))
  }

  test("a blank line between items does not end the list") {
    // A loose list is still one list, so the sub-item is still a sub-item.
    assertEquals(linesFlagged("- alpha\n\n  - alpha sub\n"), Vector(3))
  }

  // ════════════════════════════════════════════════════════ must NOT flag ════

  test("four spaces is what the parser wants, so it is silent") {
    assertEquals(linesFlagged("- alpha\n    - alpha sub\n- beta\n"), Vector.empty)
  }

  test("a tab is silent — it is what Obsidian writes and the parser reads it correctly") {
    assertEquals(linesFlagged("- alpha\n\t- alpha sub\n- beta\n"), Vector.empty)
  }

  test("a flat list is silent however long it is") {
    assertEquals(linesFlagged("- alpha\n- beta\n- gamma\n"), Vector.empty)
  }

  test("a whole list indented up to three spaces is silent — that is a legal top-level list") {
    // The trap this avoids: flagging on "an indented list marker" alone. CommonMark allows a
    // top-level list up to three columns in, and there is no parent here to disagree about.
    assertEquals(linesFlagged("  - alpha\n  - beta\n"), Vector.empty)
  }

  test("a numbered sub-item at two spaces is silent — BOTH parsers decline to nest it") {
    // Silence here is the whole discipline of this file: the check reports DISAGREEMENT between
    // the two readings, not shallow indentation. `1. ` puts text at column 3, so at two columns
    // CommonMark does not nest either, both parsers agree, and the author gets no card refused.
    assertEquals(linesFlagged("1. alpha\n  1. alpha sub\n"), Vector.empty)
  }

  test("an indented list inside a fenced code block is silent") {
    val body = "Example:\n\n```markdown\n- alpha\n  - alpha sub\n```\n"
    assertEquals(linesFlagged(body), Vector.empty)
  }

  test("a tilde fence counts as a fence too") {
    assertEquals(linesFlagged("~~~\n- alpha\n  - alpha sub\n~~~\n"), Vector.empty)
  }

  test("prose between two lists ends the first one, so the second is not its child") {
    val body = "- alpha\n\nSome prose that closes the list.\n\n  - beta\n"
    assertEquals(linesFlagged(body), Vector.empty)
  }

  test("a heading ends the list, so a list under it is not a child of one above it") {
    assertEquals(linesFlagged("- alpha\n\n## Next\n\n  - beta\n"), Vector.empty)
  }

  test("an empty body yields nothing rather than failing") {
    assertEquals(linesFlagged(""), Vector.empty)
  }

  // ══════════════════════════════════════════════════ ownership by heading ════

  test("a finding is owned by the nearest heading above it") {
    val body =
      """# Title
        |
        |## First #flashcard/2way
        |
        |- alpha
        |  - alpha sub
        |
        |## Second #flashcard/2way
        |
        |- beta
        |  - beta sub
        |""".stripMargin
    val report = ListIndent.scan(body, bodyFirstLine = 1)
    assertEquals(report.under(3).map(_.line), Vector(6))
    assertEquals(report.under(8).map(_.line), Vector(11))
    assertEquals(report.under(1), Vector.empty)
  }

  test("a heading with a clean body is answered with nothing, not with a failed lookup") {
    val report = ListIndent.scan("## Clean\n\n- alpha\n    - alpha sub\n", bodyFirstLine = 1)
    assertEquals(report.under(1), Vector.empty)
    assertEquals(report.under(999), Vector.empty)
  }

  test("a finding above the first heading belongs to no card") {
    val report = ListIndent.scan("- alpha\n  - alpha sub\n\n## Later\n", bodyFirstLine = 1)
    assertEquals(report.all.map(_.line), Vector(2))
    assertEquals(report.under(4), Vector.empty)
  }

  // ═════════════════════════════════════════════ counted from the file ════

  test("line numbers are counted from the body's origin, not from the body's first line") {
    // A note whose frontmatter occupied four lines hands its body in starting at line 5. The
    // sub-item is the body's second line, so it is the FILE's line 6 — and 6 is what an author
    // types into a jump-to-line box. Reporting 2 here would be wrong in a way that looks right.
    val report = ListIndent.scan("- alpha\n  - alpha sub\n", bodyFirstLine = 5)
    assertEquals(report.all.map(_.line), Vector(6))
  }

  test("heading ownership is counted from the same origin, or every lookup would miss") {
    val report = ListIndent.scan("## Marked\n\n- alpha\n  - alpha sub\n", bodyFirstLine = 5)
    assertEquals(report.under(5).map(_.line), Vector(8))
    assertEquals(report.under(1), Vector.empty)
  }

  // ═══════════════════════════════════════════════════════ the message ════

  test("the refusal names the line, both column counts, and the remedy") {
    val message = ListIndent.explain(Vector(ListIndent.Finding(6, 2, 4, "- alpha sub")))
    assert(message.contains("line 6"), message)
    assert(message.contains("2"), message)
    assert(message.contains("4"), message)
    assert(message.contains("- alpha sub"), message)
    assert(message.toLowerCase.contains("tab"), message)
  }

  test("several findings are reported in one message rather than only the first") {
    val message = ListIndent.explain(
      Vector(ListIndent.Finding(6, 2, 4, "- alpha sub"), ListIndent.Finding(9, 2, 4, "- beta sub"))
    )
    assert(message.contains("line 6"), message)
    assert(message.contains("line 9"), message)
  }

  // ═════════════════════════════════════════════════════════ known limit ════

  test("KNOWN MISS: a lazy continuation line at column zero hides the sub-item below it") {
    // CommonMark lets an item's paragraph continue on an UNINDENTED line. This scanner reads
    // that line as prose that closes the list, so the sub-item after it looks top-level and is
    // not flagged. Deliberate: closing the list on a dedent is what keeps "prose between two
    // lists" (above) from being a false positive, and a miss costs a card that is no worse than
    // it was before this check existed, while a false positive refuses a card that was fine.
    //
    // Pinned so that a future change to the dedent rule shows up here as a CHANGED EXPECTATION
    // rather than passing unnoticed. If this ever starts flagging line 3, that is an
    // improvement — update the test and say so.
    val body = "- Pin the client to a replica whose applied log position is at least the\nposition returned by its last write.\n  - Needs a version token.\n"
    assertEquals(linesFlagged(body), Vector.empty)
  }
