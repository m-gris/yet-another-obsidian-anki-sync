package obsidiananki.extract

class FrontmatterTest extends munit.FunSuite:

  def split(s: String): SplitNote =
    Frontmatter.split(s).fold(e => fail(s"split failed: $e"), identity)

  def parsed(s: String): Map[String, String] =
    Frontmatter.read(s).fold(e => fail(s"read failed: $e"), _._1)

  // ================================================ the split ====

  test("a well-formed note splits into frontmatter and body") {
    val note = split("---\nid: n1\ntitle: T\n---\n\n# Heading\n\nBody.\n")
    assertEquals(note.frontmatter.map(_.trim), Some("id: n1\ntitle: T"))
    assert(note.body.startsWith("# Heading"), s"body was '${note.body.take(30)}'")
  }

  // ============================== where the body starts ====

  /** Every line number this tool prints to an author is counted from `bodyFirstLine`, so
    * getting it wrong fails nothing — it just sends the reader to the wrong line, plausibly.
    * That is exactly the class of defect this project keeps finding, so the arithmetic is
    * pinned here rather than trusted.
    */
  test("the body's first line is the FILE's line number, not 1") {
    // ---(1) id(2) ---(3) blank(4) # Heading(5)
    assertEquals(split("---\nid: n1\n---\n\n# Heading\n\nBody.\n").bodyFirstLine, 5)
  }

  test("blank lines after the closing fence are counted, not silently skipped") {
    // They are dropped from the body TEXT, so unless they are also counted the origin comes
    // out short by however many the author happened to leave behind.
    val note = split("---\nid: n1\n---\n\n\n\n# Heading\n")
    assertEquals(note.bodyFirstLine, 7)
    assert(note.body.startsWith("# Heading"), s"body was '${note.body.take(30)}'")
  }

  test("a note with no frontmatter starts at line 1") {
    assertEquals(split("# Heading\n\nBody.\n").bodyFirstLine, 1)
  }

  test("a note with no frontmatter is an ordinary note, not an error") {
    val src  = "# Heading\n\nBody.\n"
    val note = split(src)
    assertEquals(note.frontmatter, None)
    assertEquals(note.body, src)
  }

  // ---- the hostile cases: every non-frontmatter use of --- in real markdown ----

  /** A thematic break. Under a naive "find the first ---" split this would open a
    * frontmatter block halfway down the document and silently eat everything above it.
    */
  test("HOSTILE: a thematic break in the body is not frontmatter") {
    val src  = "# Heading\n\nSome prose.\n\n---\n\nMore prose.\n"
    val note = split(src)
    assertEquals(note.frontmatter, None)
    assertEquals(note.body, src, "a thematic break was mistaken for frontmatter")
  }

  /** A table's separator row. `| --- |` is not `---`, but a sloppy `contains` or a trimmed
    * comparison would match it.
    */
  test("HOSTILE: a table separator row is not frontmatter") {
    val src  = "# T\n\n| A | B |\n| --- | --- |\n| 1 | 2 |\n"
    val note = split(src)
    assertEquals(note.frontmatter, None)
    assertEquals(note.body, src)
  }

  /** Dashes inside a fenced code block — legitimate content that must not be structural. */
  test("HOSTILE: --- inside a fenced code block is not frontmatter") {
    val src  = "# T\n\n```yaml\n---\nid: not-really\n---\n```\n\nAfter.\n"
    val note = split(src)
    assertEquals(note.frontmatter, None)
    assertEquals(note.body, src)
  }

  /** Obsidian requires the fence on line 1. A blank line first means there is none. */
  test("HOSTILE: a leading blank line means there is no frontmatter") {
    val src  = "\n---\nid: n1\n---\n\n# T\n"
    val note = split(src)
    assertEquals(note.frontmatter, None)
    assertEquals(note.body, src)
  }

  /** Ambiguous: the whole file could be frontmatter, or none of it. Refused rather than
    * guessed at — guessing would silently discard the entire document.
    */
  test("HOSTILE: an unterminated frontmatter block is refused, not guessed at") {
    assertEquals(Frontmatter.split("---\nid: n1\n\n# T\n\nBody.\n"), Left(FrontmatterError.Unterminated))
  }

  test("a frontmatter block containing a --- inside a quoted value still terminates correctly") {
    val note = split("---\nid: n1\ntitle: \"a --- b\"\n---\n\nBody.\n")
    assertEquals(note.body.trim, "Body.")
  }

  test("an empty frontmatter block is allowed and yields no keys") {
    assertEquals(parsed("---\n---\n\nBody.\n"), Map.empty[String, String])
  }

  // ================================================ typing ====

  /** THE HOCON BUG, ONE LIBRARY FURTHER ON. YAML's implicit typing resolves `2026-08-18` to
    * a date and `007` to an integer. If that reached the card key it would corrupt identity
    * exactly as HOCON did — silently, and only for certain id shapes.
    */
  test("REGRESSION: a date-like id survives VERBATIM, not as a date") {
    assertEquals(parsed("---\nid: 2026-08-18\n---\n").get("id"), Some("2026-08-18"))
  }

  test("REGRESSION: a numeric-looking id keeps its leading zeros") {
    assertEquals(parsed("---\nid: 007\n---\n").get("id"), Some("007"))
  }

  test("REGRESSION: a boolean-looking value stays text") {
    assertEquals(parsed("---\nid: true\n---\n").get("id"), Some("true"))
  }

  test("a quoted id is unquoted exactly once") {
    assertEquals(parsed("---\nid: \"2026-08-18\"\n---\n").get("id"), Some("2026-08-18"))
  }

  test("the real vault's id shapes all survive") {
    val shapes = List("1786713776-ZMPB", "2026-08-18", "MOC-Design-Gurus-System-Design-Patterns")
    shapes.foreach { s =>
      assertEquals(parsed(s"---\nid: $s\n---\n").get("id"), Some(s), s"corrupted: $s")
    }
  }

  // ================================================ real-world shapes ====

  /** These are the shapes that BROKE the HOCON parser — a one-item block sequence was read
    * as a string, a two-item one failed outright, and an empty value aborted the parse. A
    * real YAML parser must take all three in its stride, and `id` must still come through.
    */
  test("REGRESSION: a one-item block sequence does not corrupt the mapping") {
    val m = parsed("---\nid: n1\naliases:\n  - Module 1\n---\n")
    assertEquals(m.get("id"), Some("n1"))
    assertEquals(m.get("aliases"), None, "a list should be absent, not stringified")
  }

  test("REGRESSION: a multi-item block sequence does not abort the parse") {
    val m = parsed("---\nid: n1\ntags:\n  - a\n  - b\n---\n")
    assertEquals(m.get("id"), Some("n1"))
  }

  test("REGRESSION: an empty value does not abort the parse") {
    val m = parsed("---\nid: n1\nauthor:\npublished:\n---\n")
    assertEquals(m.get("id"), Some("n1"))
  }

  test("an inline sequence and a nested mapping are both tolerated") {
    val m = parsed("---\nid: n1\ntags: [a, b]\nnested:\n  k: v\n---\n")
    assertEquals(m.get("id"), Some("n1"))
  }

  test("a missing id is not an error here — it is the caller's decision") {
    assertEquals(parsed("---\ntags:\n  - x\n---\n").get("id"), None)
  }

  // ================================================ malformed ====

  test("a top-level list rather than a mapping is rejected") {
    assertEquals(Frontmatter.parse("- a\n- b\n"), Left(FrontmatterError.NotAMapping))
  }

  test("genuinely malformed YAML is rejected rather than silently ignored") {
    assert(Frontmatter.parse("id: [unclosed\n").isLeft)
  }
