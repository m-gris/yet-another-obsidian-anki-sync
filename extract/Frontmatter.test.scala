package obsidiananki.extract

class FrontmatterTest extends munit.FunSuite:

  import PropertyValue.*

  def split(s: String): SplitNote =
    Frontmatter.split(s).fold(e => fail(s"split failed: $e"), identity)

  def parsed(s: String): Map[String, PropertyValue] =
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
    assertEquals(parsed("---\n---\n\nBody.\n"), Map.empty[String, PropertyValue])
  }

  // ================================================ typing ====

  /** THE HOCON BUG, ONE LIBRARY FURTHER ON. YAML's implicit typing resolves `2026-08-18` to
    * a date and `007` to an integer. If that reached the card key it would corrupt identity
    * exactly as HOCON did — silently, and only for certain id shapes.
    */
  test("REGRESSION: a date-like id survives VERBATIM, not as a date") {
    assertEquals(parsed("---\nid: 2026-08-18\n---\n").get("id"), Some(One("2026-08-18")))
  }

  test("REGRESSION: a numeric-looking id keeps its leading zeros") {
    assertEquals(parsed("---\nid: 007\n---\n").get("id"), Some(One("007")))
  }

  test("REGRESSION: a boolean-looking value stays text") {
    assertEquals(parsed("---\nid: true\n---\n").get("id"), Some(One("true")))
  }

  test("a quoted id is unquoted exactly once") {
    assertEquals(parsed("---\nid: \"2026-08-18\"\n---\n").get("id"), Some(One("2026-08-18")))
  }

  test("the real vault's id shapes all survive") {
    val shapes = List("1786713776-ZMPB", "2026-08-18", "MOC-Design-Gurus-System-Design-Patterns")
    shapes.foreach { s =>
      assertEquals(parsed(s"---\nid: $s\n---\n").get("id"), Some(One(s)), s"corrupted: $s")
    }
  }

  // ================================================ real-world shapes ====

  /** These are the shapes that BROKE the HOCON parser — a one-item block sequence was read
    * as a string, a two-item one failed outright, and an empty value aborted the parse. A
    * real YAML parser must take all three in its stride, and `id` must still come through.
    */
  test("REGRESSION: a one-item block sequence does not corrupt the mapping") {
    val m = parsed("---\nid: n1\naliases:\n  - Module 1\n---\n")
    assertEquals(m.get("id"), Some(One("n1")))

    // WAS `None`, ASSERTED AS "a list should be absent, not stringified", UNTIL 2026-08-25.
    // Half of that was right and is still enforced by the shape: a one-item sequence must not
    // arrive as the STRING "Module 1", which is exactly the corruption the HOCON parser
    // produced and this suite exists to catch. What was wrong was the remedy — dropping the
    // key made a list indistinguishable from a property nobody wrote.
    assertEquals(m.get("aliases"), Some(Many(Vector("Module 1"))))
  }

  test("REGRESSION: a multi-item block sequence does not abort the parse") {
    val m = parsed("---\nid: n1\ntags:\n  - a\n  - b\n---\n")
    assertEquals(m.get("id"), Some(One("n1")))
  }

  test("REGRESSION: an empty value does not abort the parse") {
    val m = parsed("---\nid: n1\nauthor:\npublished:\n---\n")
    assertEquals(m.get("id"), Some(One("n1")))
  }

  test("an inline sequence and a nested mapping are both tolerated") {
    val m = parsed("---\nid: n1\ntags: [a, b]\nnested:\n  k: v\n---\n")
    assertEquals(m.get("id"), Some(One("n1")))
  }

  test("a missing id is not an error here — it is the caller's decision") {
    assertEquals(parsed("---\ntags:\n  - x\n---\n").get("id"), None)
  }

  // ============================== values that are not scalars ====

  /** WHAT THIS SECTION IS FOR. Every test above was written while a property value could only
    * be a string, so each of them asserts what happens to `id` and says nothing about the
    * property beside it. These assert the value that used to be thrown away.
    *
    * The motivating case is not hypothetical: a real note in Marc's vault asks to be a
    * `#flashcard/sequence` and the tool could not read the request, because Obsidian writes
    * `tags:` as a block sequence and a block sequence was dropped. The tool knew only that the
    * word "flashcard" occurred somewhere in the raw block — enough to complain, not enough to
    * act.
    */

  test("a block sequence is read as its items, in order") {
    assertEquals(
      parsed("---\nid: n1\ntags:\n  - a\n  - b\n  - c\n---\n").get("tags"),
      Some(Many(Vector("a", "b", "c"))),
    )
  }

  /** ORDER IS PRESERVED AND ASSERTED SEPARATELY, because a `Set` would satisfy the test above
    * only by accident of insertion. Whether the order MEANS anything is a question for the
    * consumer — `#flashcard/sequence` says yes, a set of tags says no — and it cannot be asked
    * at all if the parser has already lost it.
    */
  test("a sequence keeps the order it was written in, not a sorted one") {
    assertEquals(
      parsed("---\nid: n1\nsteps:\n  - zebra\n  - apple\n  - mango\n---\n").get("steps"),
      Some(Many(Vector("zebra", "apple", "mango"))),
    )
  }

  test("an inline sequence reads the same as a block sequence") {
    // Obsidian and hand-editing produce both spellings for the same property. They must not
    // arrive as different shapes, or every consumer has to know which tool wrote the note.
    val inline = parsed("---\nid: n1\ntags: [a, b]\n---\n").get("tags")

    // NON-VACUITY FIRST. Asserting only that the two agree passed while BOTH were `None` —
    // which is precisely the state this change exists to end, so the test would have been
    // green for the whole time the defect was present.
    assertEquals(inline, Some(Many(Vector("a", "b"))), "the inline spelling was not read at all")
    assertEquals(inline, parsed("---\nid: n1\ntags:\n  - a\n  - b\n---\n").get("tags"))
  }

  /** AN EMPTY LIST IS A DECLARED PROPERTY, and `tags: []` is what Obsidian writes into a note
    * whose tags you have removed. Reporting it as absent would be true of the tags and false of
    * the property, and a consumer that wants to distinguish "never had any" from "had some and
    * they were cleared" could not.
    */
  test("an empty sequence is an empty list, not an absent property") {
    assertEquals(parsed("---\nid: n1\ntags: []\n---\n").get("tags"), Some(Many(Vector.empty)))
  }

  /** A DECLARED PROPERTY WITH NO VALUE IS AN EMPTY STRING, NOT ABSENT — and this test was
    * written asserting the opposite before the implementation corrected it.
    *
    * The assumption was that `aliases:` with nothing after it yields YAML null. It does not,
    * and the reason is a feature of this file: the loader has IMPLICIT TYPING TURNED OFF, which
    * disables the null resolver along with the date and integer ones, so an empty value arrives
    * as `""`. That has always been this parser's behaviour — an empty scalar is a `String` and
    * was kept — and no test pinned it, which is why an assumption could be written down as a
    * test and reach a run.
    *
    * IT IS ALSO THE BETTER BEHAVIOUR, so it is being kept rather than merely tolerated. An empty
    * `id:` reaches identity as an empty value and is refused with a message about the value; had
    * it been dropped, the note would have been told it has no `id` at all and asked to add a
    * line that is plainly already there.
    *
    * Obsidian's own template leaves several of these in every note it makes.
    */
  test("a declared property with no value is an empty value, not an absent one") {
    val m = parsed("---\nid: n1\naliases:\npublished:\n---\n")
    assertEquals(m.get("aliases"), Some(One("")))
    assertEquals(m.get("published"), Some(One("")))
    assertEquals(m.get("id"), Some(One("n1")), "the scalar beside them must still come through")
  }

  /** UNREADABLE IS NOT ABSENT, and the distinction earns its case by what it lets a message say.
    * A nested mapping reported as a missing key sends a reader to add something they can see is
    * already there.
    */
  test("a nested mapping is reported as present-but-unreadable, not as missing") {
    val m = parsed("---\nid: n1\nnested:\n  k: v\n---\n")
    m.get("nested") match
      case Some(Unreadable(_)) => ()
      case other               => fail(s"expected Unreadable, got $other")
    assertEquals(m.get("id"), Some(One("n1")))
  }

  test("a sequence containing a nested mapping is unreadable rather than partly read") {
    // Reading the scalars and discarding the mapping would answer a question nobody asked and
    // hide the part that was not understood.
    val m = parsed("---\nid: n1\nmixed:\n  - plain\n  - k: v\n---\n")
    m.get("mixed") match
      case Some(Unreadable(_)) => ()
      case other               => fail(s"expected Unreadable, got $other")
  }

  /** THE CASE THIS WHOLE CHANGE EXISTS FOR, asserted against the exact bytes Obsidian writes.
    *
    * Taken verbatim from the frontmatter of a real note — `Essential Numbers (for System Design
    * Interview).md` — which asks to be a flashcard and, until this change, could not be heard.
    */
  test("Obsidian's own tags block yields the marker the note is asking for") {
    val m = parsed("---\nid: ee9ac008\ncreated: 2026-08-25T19:03:44\naliases:\ntags:\n  - flashcard/sequence\n---\n")
    assertEquals(
      m.get("tags"),
      Some(Many(Vector("flashcard/sequence"))),
      "the note's marker is still invisible, which is the defect this change is for",
    )
    assertEquals(m.get("id"), Some(One("ee9ac008")))
    assertEquals(m.get("created"), Some(One("2026-08-25T19:03:44")), "a timestamp must not be typed")
  }

  // ================================================ malformed ====

  test("a top-level list rather than a mapping is rejected") {
    assertEquals(Frontmatter.parse("- a\n- b\n"), Left(FrontmatterError.NotAMapping))
  }

  test("genuinely malformed YAML is rejected rather than silently ignored") {
    assert(Frontmatter.parse("id: [unclosed\n").isLeft)
  }
