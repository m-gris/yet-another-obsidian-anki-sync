package obsidiananki.extract

import obsidiananki.content as C
import obsidiananki.model.*
import obsidiananki.parser.ObsidianSyntax
import obsidiananki.plan.{BuildFailure, SourcedSpec}

class ExtractorTest extends munit.FunSuite:

  // Fixture defaults, and removing them was measured: it breaks 259 call sites across the suite.
  // These tests are about MARKDOWN, and the note's id and file name appear in every key they
  // assert on — so a wrong value here fails loudly and immediately, in the assertion itself.
  // ast-grep-ignore: default-parameter
  def extract(markdown: String, id: String = "n1", fileName: String = "Note"): ExtractedNote =
    val (_, split) = Frontmatter.read(markdown).fold(e => fail(s"frontmatter: $e"), identity)
    val body = split.body
    val root = ObsidianSyntax.markupParser.parse(body).fold(e => fail(s"parse: $e"), _.content)
    Extractor.fromDocument(
      NoteId.fromFrontmatter(id).toOption.get,
      fileName,
      s"$fileName.md",
      root,
      body,
      split.bodyFirstLine,
      // These tests are about what markdown yields, not about a note's own tags. Named at every
      // site rather than defaulted, so that no call site holds a decision nobody made.
      Vector.empty,
    )

  def paths(n: ExtractedNote): Vector[String] = n.specs.map(_.key.path.render)

  def specFor(n: ExtractedNote, path: String): SourcedSpec =
    n.specs.find(_.key.path.render == path).getOrElse(fail(s"no spec at '$path' — have ${paths(n)}"))

  // ================================================ marking ====

  test("only marked headings become cards") {
    val note = extract(
      """|# Coupling
         |
         |Intro prose that belongs to no card.
         |
         |## Temporal coupling #flashcard/2way
         |
         |All parties must be up at once.
         |
         |## Notes
         |
         |Unmarked, so this generates nothing.
         |""".stripMargin
    )
    assertEquals(paths(note), Vector("coupling / temporal coupling"))
    assertEquals(note.failures, Vector.empty)
  }

  test("a marked heading at any depth works") {
    val note = extract(
      """|# A
         |
         |x
         |
         |## B
         |
         |y
         |
         |### C
         |
         |z
         |
         |#### Deep #flashcard/1way
         |
         |The answer.
         |""".stripMargin
    )
    assertEquals(paths(note), Vector("a / b / c / deep"))
  }

  // ================================================ the collision case ====

  /** The fixture that motivates the whole path key: identical facet names under different
    * ancestors must stay distinct.
    */
  test("identically named facets under different ancestors get DIFFERENT keys") {
    val note = extract(
      """|# Multi-Topic
         |
         |Intro.
         |
         |## CAP Theorem
         |
         |About CAP.
         |
         |### Definition #flashcard/3way
         |
         |Consistency, availability, partition tolerance.
         |
         |## Quorum
         |
         |About quorums.
         |
         |### Definition #flashcard/3way
         |
         |A majority of replicas.
         |""".stripMargin
    )
    assertEquals(
      paths(note).sorted,
      Vector("multi-topic / cap theorem / definition", "multi-topic / quorum / definition"),
    )
  }

  // ================================================ concept resolution ====

  test("a three-field card takes its concept from the NEAREST ancestor heading") {
    val note = extract(
      """|# Linearizability
         |
         |Intro.
         |
         |## Definition #flashcard/3way
         |
         |Operations appear instantaneous.
         |""".stripMargin
    )
    specFor(note, "linearizability / definition").spec match
      case CardSpec.ThreeField(_, concept, descriptor, _, _, _, _) =>
        assertEquals(concept, "Linearizability")
        assertEquals(descriptor, "Definition")
      case other => fail(s"expected ThreeField, got $other")
  }

  test("with no ancestor heading the concept falls back to the FILENAME") {
    val note = extract(
      """|## Definition #flashcard/3way
         |
         |Body.
         |""".stripMargin,
      fileName = "Consistency",
    )
    specFor(note, "definition").spec match
      case CardSpec.ThreeField(_, concept, _, _, _, _, _) => assertEquals(concept, "Consistency")
      case other                                    => fail(s"expected ThreeField, got $other")
  }

  test("the marker is stripped from the descriptor as well as from the key") {
    val note = extract("# A\n\nx\n\n## Cost #flashcard/3way/all\n\nExpensive.\n")
    specFor(note, "a / cost").spec match
      case CardSpec.ThreeField(_, _, descriptor, _, directions, _, _) =>
        assertEquals(descriptor, "Cost")
        assertEquals(directions, ThreeFieldDirections.All)
      case other => fail(s"expected ThreeField, got $other")
  }

  // ================================================ B6: the body ====

  /** RULED (B6): own prose only, stopping at the next heading of ANY level. Including
    * descendants would make a parent card duplicate its children.
    */
  test("B6: a card's body is its OWN prose, not its descendants'") {
    val note = extract(
      """|# A
         |
         |x
         |
         |## Parent #flashcard/1way
         |
         |Only this sentence.
         |
         |### Child
         |
         |This must NOT appear in the parent's body.
         |""".stripMargin
    )
    specFor(note, "a / parent").spec match
      case CardSpec.TwoField(_, _, back, _, _) =>
        // The `<p>` arrived in S11: a card body is an HTML fragment. The assertion is on the
        // WHOLE value rather than `contains`, so the child's sentence still cannot sneak in.
        assertEquals(back.value, "<p>Only this sentence.</p>")
      case other => fail(s"expected TwoField, got $other")
  }

  /** The second half of B6, and the reason it is not pedantry: an empty back field makes
    * Anki decline to generate the reverse card, so `2way` would silently produce ONE card
    * where it promised TWO.
    */
  test("B6: a marked heading with an EMPTY body is a hard error, not an empty card") {
    val note = extract(
      """|# A
         |
         |x
         |
         |## Marked #flashcard/2way
         |
         |### Immediately a subheading
         |
         |So the marked heading has no prose of its own.
         |""".stripMargin
    )
    assertEquals(note.specs, Vector.empty, "an empty-bodied card was emitted")
    assert(
      note.failures.exists {
        case BuildFailure.KeyKnown(_, _, reason) => reason.toLowerCase.contains("empty")
        case _                                   => false
      },
      s"expected an empty-body failure, got ${note.failures}",
    )
  }

  /** The failure must carry its KEY, so the planner can exclude it from orphan inference.
    * Otherwise a broken card looks deleted and its live Anki note goes to the prune list.
    */
  test("an empty-body failure CARRIES ITS KEY so it is not mistaken for a deletion") {
    val note = extract("# A\n\nx\n\n## Marked #flashcard/1way\n\n### Sub\n\ny\n")
    note.failures.collectFirst { case BuildFailure.KeyKnown(key, _, _) => key } match
      case Some(key) => assertEquals(key.path.render, "a / marked")
      case None      => fail(s"failure did not carry a key: ${note.failures}")
    }

  /** B6 IS DECIDED ON THE PLAIN-TEXT RENDERING, NEVER ON THE HTML ONE — and this body is the
    * input that discriminates the two.
    *
    * `%%…%%` is an Obsidian comment and lowers to ZERO inlines, so this body becomes one
    * paragraph holding a single space. `AsText` kills it through its trailing `.trim` and the
    * rule fires. `AsHtml` has no such trim and renders `<p> </p>` — eleven non-blank
    * characters — on which `Body.fromExtracted`'s `raw.trim.isEmpty` does NOT fire, because
    * the string begins with `<`.
    *
    * So an HTML-gated B6 would silently ship a visually blank card here instead of refusing;
    * and for a `2way` marker Anki then declines the reverse card, so the marker produces ONE
    * card where it promised TWO. Refusal → card is the one direction the design forbids, and
    * nothing else reddens on it: a more permissive tool makes MORE cards, not fewer.
    *
    * NO NOTE IN `dummy-vault` CONTAINS THIS BODY, so `extract/golden/fixture-cards.txt` pins
    * nothing here and this test is the whole net.
    */
  test("B6 is decided on the plain text: a body of only comments is still an EMPTY body") {
    val note = extract("# A\n\nx\n\n## Marked #flashcard/2way\n\n%%a%% %%b%%\n")
    assert(note.specs.isEmpty, s"a body of only private comments produced a card: ${note.specs}")
    val reason = note.failures.collect { case BuildFailure.KeyKnown(_, _, r) => r }.mkString(" ")
    assert(reason.contains("empty body"), s"the refusal is not an empty-body error: $reason")
  }

  // ================================================ loud rejections ====

  test("an unrecognised marker fails loudly rather than being treated as unmarked") {
    val note = extract("# A\n\nx\n\n## Typo #flashcard/2-way\n\nBody.\n")
    assertEquals(note.specs, Vector.empty)
    assert(note.failures.nonEmpty, "a typo'd marker was silently ignored")
  }

  test("an embed in a card body is rejected by name") {
    val note = extract("# A\n\nx\n\n## Marked #flashcard/1way\n\nSee ![[diagram.png]] here.\n")
    assertEquals(note.specs, Vector.empty)
    assert(
      note.failures.exists {
        case BuildFailure.KeyKnown(_, _, r) => r.toLowerCase.contains("embed")
        case _                              => false
      },
      s"expected an embed rejection, got ${note.failures}",
    )
  }

  test("a task list in a card body is rejected by name") {
    val note = extract("# A\n\nx\n\n## Marked #flashcard/1way\n\n- [ ] a\n- [x] b\n")
    assertEquals(note.specs, Vector.empty)
    assert(
      note.failures.exists {
        case BuildFailure.KeyKnown(_, _, r) => r.toLowerCase.contains("task")
        case _                              => false
      },
      s"expected a task-list rejection, got ${note.failures}",
    )
  }

  // ================================================ wikilinks survive ====

  test("a wikilink in a body keeps its display text") {
    val note = extract("# A\n\nx\n\n## M #flashcard/1way\n\nStronger than [[Sequential Consistency]].\n")
    specFor(note, "a / m").spec match
      case CardSpec.TwoField(_, _, back, _, _) =>
        assert(back.value.contains("Sequential Consistency"), s"lost the link text: ${back.value}")
      case other => fail(s"expected TwoField, got $other")
  }

  test("a wikilink IN A MARKED HEADING still produces a card and keeps the subtree intact") {
    val note = extract(
      """|# Messaging
         |
         |x
         |
         |## [[Message Queue]]
         |
         |y
         |
         |### Definition #flashcard/3way
         |
         |A buffer.
         |""".stripMargin
    )
    assertEquals(paths(note), Vector("messaging / message queue / definition"))
  }

  // ================================================ the two families ====

  test("1way and 2way select different note types") {
    val one = extract("# A\n\nx\n\n## Q? #flashcard/1way\n\nAnswer.\n")
    val two = extract("# A\n\nx\n\n## Term #flashcard/2way\n\nDefinition.\n")
    assertEquals(specFor(one, "a / q?").spec.noteTypeName, Marker.NoteTypes.Basic)
    assertEquals(specFor(two, "a / term").spec.noteTypeName, Marker.NoteTypes.BasicAndReversed)
  }

  // ================================================ tables ====

  val messagingTable: String =
    """|# Messaging
       |
       |Intro.
       |
       |## Cost / benefit #flashcard/table
       |
       || Pattern | Benefit         | Cost                |
       || ------- | --------------- | ------------------- |
       || Queue   | Load Absorption | Delay & Duplication |
       || Pub/Sub | Fan-out         | Ordering            |
       |""".stripMargin

  test("a table yields a pair card per cell PLUS a row card per row") {
    val note = extract(messagingTable)
    // 2 rows x 2 descriptors = 4 pair cards, + 2 row cards
    assertEquals(note.specs.size, 6, s"got ${paths(note)}")
    assertEquals(note.failures, Vector.empty)
  }

  test("pair keys extend the path with row concept AND column header") {
    val note = extract(messagingTable)
    assert(
      paths(note).contains("messaging / cost / benefit / queue / benefit"),
      s"pair key missing — have ${paths(note)}",
    )
  }

  test("a row key extends the path with the row concept ONLY") {
    val note = extract(messagingTable)
    assert(paths(note).contains("messaging / cost / benefit / queue"), s"have ${paths(note)}")
  }

  test("a pair card is a three-field spec — no separate card model") {
    val note = extract(messagingTable)
    specFor(note, "messaging / cost / benefit / queue / benefit").spec match
      case CardSpec.ThreeField(_, concept, descriptor, description, _, _, _) =>
        assertEquals(concept, "Queue")
        assertEquals(descriptor, "Benefit")
        assertEquals(description.value, "Load Absorption")
      case other => fail(s"expected ThreeField, got $other")
  }

  test("a row card draws the whole row as a table, blanked on the front and filled on the back") {
    val note = extract(messagingTable)
    specFor(note, "messaging / cost / benefit / queue").spec match
      case CardSpec.TableRow(_, blanked, filled, _) =>
        // THE SAME TABLE TWICE, differing only in what is filled in. Both carry every header,
        // so the shape a reviewer reads does not change between question and answer.
        Vector("Pattern", "Benefit", "Cost").foreach { header =>
          assert(blanked.contains(s"<th>$header</th>"), s"front lost the '$header' column: $blanked")
          assert(filled.contains(s"<th>$header</th>"), s"back lost the '$header' column: $filled")
        }

        // The concept is given on both sides; it is the thing being asked ABOUT.
        assert(blanked.contains("<td>Queue</td>"), blanked)

        // Every descriptor value is hidden on the front and present on the back.
        assert(!blanked.contains("Load Absorption"), s"front gave the answer away: $blanked")
        assert(blanked.contains("[&hellip;]"), s"front has no blank at all: $blanked")
        assert(filled.contains("<td>Load Absorption</td>"), filled)

        // ESCAPED EXACTLY ONCE. This cell is the only place the fixture markdown in THIS file
        // carries a character that escaping moves — `dummy-vault` carries none in any table
        // cell, so the golden does not witness it. Double-escaping would put `&amp;amp;` on the
        // card, and is the live hazard when a value escaped by `CellDisplay` is handed to
        // `Html.rowTable`, which escapes what it is given.
        assert(filled.contains("Delay &amp; Duplication"), filled)
        assert(!filled.contains("&amp;amp;"), s"the value was escaped twice: $filled")
      case other => fail(s"expected TableRow, got $other")
  }

  /** The slash in "Cost / benefit" is the path JOIN character appearing as heading text.
    * The encoding must keep the two distinguishable.
    */
  test("a slash in the table's own heading does not corrupt the derived keys") {
    val note = extract(messagingTable)
    val tag  = TagCodec.encode(specFor(note, "messaging / cost / benefit / queue").key).value
    assert(tag.contains("%2f"), s"literal slash was not encoded: $tag")
  }

  test("source kinds distinguish pair cards from row cards, for legible collisions") {
    import obsidiananki.plan.SourceKind
    val note  = extract(messagingTable)
    val kinds = note.specs.map(_.source.kind).toSet
    assertEquals(kinds, Set(SourceKind.TablePair, SourceKind.TableRow))
  }

  // ---- edge cases the fixture vault exists to cover ----

  test("a row with exactly ONE descriptor gets no row card — it would duplicate the pair") {
    val note = extract(
      """|# T
         |
         |x
         |
         |## One #flashcard/table
         |
         || Concept | Definition |
         || ------- | ---------- |
         || Alpha   | The first  |
         |""".stripMargin
    )
    assertEquals(note.specs.size, 1, s"got ${paths(note)}")
    assertEquals(note.specs.head.spec.noteTypeName, Marker.NoteTypes.ConceptDescriptor)
  }

  /** An explicit marker that yields zero cards is the dual of silent card creation: the
    * author asked for cards and got none, with nothing said.
    */
  test("a table with NO descriptor columns is reported, not silently empty") {
    val note = extract(
      """|# T
         |
         |x
         |
         |## OnlyConcepts #flashcard/table
         |
         || Consistency model |
         || ----------------- |
         || Linearizable      |
         |""".stripMargin
    )
    assertEquals(note.specs, Vector.empty)
    assert(
      note.failures.exists {
        case BuildFailure.KeyKnown(_, _, r) => r.contains("no descriptor columns")
        case _                              => false
      },
      s"a zero-card table was silent: ${note.failures}",
    )
  }

  test("a table marker with no table in the section is reported") {
    val note = extract("# T\n\nx\n\n## NoTable #flashcard/table\n\nJust prose, no table.\n")
    assertEquals(note.specs, Vector.empty)
    assert(note.failures.nonEmpty)
  }

  test("a wikilink inside a table cell keeps its display text") {
    val note = extract(
      """|# T
         |
         |x
         |
         |## W #flashcard/table
         |
         || Pattern | Benefit             | Cost  |
         || ------- | ------------------- | ----- |
         || Queue   | [[Load Absorption]] | Delay |
         |""".stripMargin
    )
    specFor(note, "t / w / queue / benefit").spec match
      case CardSpec.ThreeField(_, _, _, description, _, _, _) =>
        assertEquals(description.value, "Load Absorption")
      case other => fail(s"expected ThreeField, got $other")
  }

  // ================================================ B10 legibility ====

  /** A collision is only useful if it says WHICH sources collided. Two identical row
    * concepts in one table share a file, a line AND a kind, so without the row number both
    * sides report the same position and the message teaches nothing.
    */
  test("B10: two identical row concepts are distinguishable by ROW NUMBER") {
    val note = extract(
      """|# T
         |
         |x
         |
         |## Dup #flashcard/table
         |
         || Pattern | Purpose | Failure mode |
         || ------- | ------- | ------------ |
         || Retry   | Recover | Amplifies    |
         || Retry   | Repeat  | Storms       |
         |""".stripMargin
    )
    val details = note.specs.map(_.source.detail).distinct.flatten.sorted
    assertEquals(details, Vector("row 1", "row 2"), s"row numbers missing: $details")
  }

  test("B10: heading positions are real line numbers, and repeated names differ") {
    val note = extract(
      """|# Multi
         |
         |x
         |
         |## A
         |
         |y
         |
         |### Definition #flashcard/3way
         |
         |First.
         |
         |## B
         |
         |z
         |
         |### Definition #flashcard/3way
         |
         |Second.
         |""".stripMargin
    )
    val lines = note.specs.map(_.source.line).sorted
    assertEquals(lines.size, 2)
    assert(lines.forall(_ > 0), s"line numbers not resolved: $lines")
    assertEquals(lines.distinct.size, 2, s"identical heading names reported the SAME line: $lines")
  }

  /** B11 — FOUND IN MARC'S VAULT ON 2026-08-27, by running `locate` over every card in his
    * collection: one of twenty-nine resolved to no line at all.
    *
    * The note was `System Design Pattern.md`, whose marked heading is
    * `# ==3== Components #flashcard/cdd/1way`. A heading's line is recovered by matching its
    * EXTRACTED text back against the raw body — Laika keeps no source position — and extraction
    * strips the highlight, so `3 Components …` was compared against `==3== Components …` and
    * missed. The answer was 0, which is printed as a file with no position at all.
    *
    * IT IS NOT SPECIFIC TO HIGHLIGHTS. Any inline markup in a marked heading does it: bold, a
    * wikilink, inline code, maths. All four already appear in headings in that vault.
    */
  test("B11: a marked heading carrying inline markup still resolves to its line") {
    val note = extract(
      """|# ==<<3>>== Components #flashcard/cdd/1way
         |
         |- A Problem
         |- A Solution
         |""".stripMargin
    )
    val lines = note.specs.map(_.source.line)
    assert(lines.nonEmpty, "no cards were produced, so this test proves nothing")
    assert(lines.forall(_ > 0), s"markup in the heading lost its line: $lines")
  }

  /** The same defect reached through three other kinds of inline markup, because a fix that
    * happens to handle `==…==` and nothing else would pass B11 and still fail in the vault.
    */
  test("B11b: bold, wikilinks and inline code in a marked heading keep their lines") {
    val cases = Vector(
      "# The **CAP** theorem #flashcard/cdd/1way",
      "# A [[Set|set]] of things #flashcard/cdd/1way",
      "# The `id` field #flashcard/cdd/1way",
    )
    cases.foreach { heading =>
      val note  = extract(s"$heading\n\n- One\n- Two\n")
      val lines = note.specs.map(_.source.line)
      assert(lines.nonEmpty, s"no cards from: $heading")
      assert(lines.forall(_ > 0), s"lost its line: $heading -> $lines")
    }
  }

  // ================================================ cloze ====

  // ---------------------------------------- a cloze with no heading of its own ----
  //
  // *Highlight a phrase anywhere and get a card*, which is what Marc asked for at the start.
  // The card is scoped to its BLOCK and keyed by the `^blockid` its author wrote, so several
  // gaps in one paragraph are several cards of ONE Anki note and Anki can keep them off the
  // same day.

  test("a clozed block in an unmarked section becomes a card keyed by its anchor") {
    val note = extract(
      "# B\n\nx\n\n## Forearm\n\nThe ==<<radius>>== is a forearm bone. ^fa1\n"
    )
    assertEquals(note.failures, Vector.empty, s"${note.failures}")
    assertEquals(paths(note), Vector("block '^fa1'"))
  }

  test("the space before a ^blockid is not content, so removing it later reports no change") {
    val spaced   = extract("# B\n\n## Forearm\n\nThe ==<<radius>>== is a bone. ^fa1\n")
    val unspaced = extract("# B\n\n## Forearm\n\nThe ==<<radius>>== is a bone.^fa1\n")

    assertEquals(paths(spaced), Vector("block '^fa1'"))
    assertEquals(
      spaced.specs.map(_.spec.fields),
      unspaced.specs.map(_.spec.fields),
      "the separator before the anchor reached the card, so it is in the content hash and " +
        "deleting it would rewrite the note for no reason",
    )
  }

  test("rewriting a clozed block leaves its key untouched, so the card is updated not re-minted") {
    val before   = extract("# B\n\n## Forearm\n\nThe ==<<radius>>== is a forearm bone. ^fa1\n")
    val around   = extract("# B\n\n## Forearm\n\nThe ==<<radius>>== sits on the thumb side. ^fa1\n")
    // The typo fix the design is really about: the edit lands INSIDE the deletion, which is where
    // `oas-9yz.1` could still move the key when it settles what an unlabelled group is keyed by.
    val inDelete = extract("# B\n\n## Forearm\n\nThe ==<<radius bone>>== is a forearm bone. ^fa1\n")

    Vector(before, around, inDelete).foreach(n => assertEquals(n.failures, Vector.empty, s"${n.failures}"))
    assertEquals(paths(before), Vector("block '^fa1'"))

    Vector(around -> "prose around the deletion", inDelete -> "the deletion's own text").foreach {
      case (edited, what) =>
        assertNotEquals(
          edited.specs.map(_.spec.fields),
          before.specs.map(_.spec.fields),
          s"editing $what changed nothing, so the key comparison below would prove nothing",
        )
        assertEquals(
          edited.specs.map(_.key),
          before.specs.map(_.key),
          s"editing $what moved the key, so it orphans the card and restarts its history",
        )
    }
  }

  /** THE BUNDLING THIS ENDS. Under a marked heading the card's text is the whole section, so a
    * section with three paragraphs shows all three whatever the highlight was in. Scoped to a
    * block, a card shows its own paragraph and nothing else.
    */
  test("the card shows its own block, not the whole section") {
    val note = extract(
      "# B\n\nx\n\n## Forearm\n\nA paragraph that must not appear.\n\n" +
        "The ==<<radius>>== is a forearm bone. ^fa1\n"
    )
    val text = specFor(note, "block '^fa1'").spec.fields.toMap.apply("Text")
    assert(text.contains("{{c1::radius}}"), s"the deletion is missing: $text")
    assert(!text.contains("must not appear"), s"the card carried a neighbouring paragraph: $text")
  }

  /** TWO BLOCKS UNDER ONE HEADING, WHICH IS THE CASE NOTHING ELSE COULD TELL APART. A heading
    * path is the same for both; only the anchor distinguishes them, which is why it exists.
    */
  test("two clozed blocks under one heading are two cards") {
    val note = extract(
      "# B\n\nx\n\n## Bones\n\nThe ==<<radius>>== is one. ^b1\n\nThe ==<<femur>>== is another. ^b2\n"
    )
    assertEquals(note.failures, Vector.empty, s"${note.failures}")
    assertEquals(paths(note).sorted, Vector("block '^b1'", "block '^b2'"))
  }

  /** SEVERAL GAPS IN ONE BLOCK ARE ONE NOTE, which is what buys sibling burying. */
  test("several deletions in one block are one card with several cloze numbers") {
    val note = extract(
      "# B\n\nx\n\n## Bones\n\nThe ==<<1|radius>>== and the ==<<2|ulna>>== are bones. ^fa1\n"
    )
    assertEquals(paths(note), Vector("block '^fa1'"))
    val text = specFor(note, "block '^fa1'").spec.fields.toMap.apply("Text")
    assert(text.contains("{{c1::radius}}") && text.contains("{{c2::ulna}}"), text)
  }

  /** REFUSED AND NAMED. The author wrote a deletion, which is as explicit a statement of intent
    * as a marker on a heading, so producing nothing silently is the failure this design exists
    * to prevent. The message must name the fix, because the fix is one keystroke.
    */
  test("a clozed block with no anchor is refused, and told what to do about it") {
    val note = extract("# B\n\nx\n\n## Forearm\n\nThe ==<<radius>>== is a forearm bone.\n")
    assertEquals(note.specs, Vector.empty, s"a card was built with no identity: ${note.specs}")
    val reason = note.failures.collect { case BuildFailure.ClozeBlockUnanchored(_, _, r) => r }.mkString
    assert(reason.contains("^blockid"), s"the refusal does not name what is missing: $reason")
    assert(reason.contains("Obsidian"), s"the refusal does not name the fix: $reason")
  }

  /** THE MOST LITERAL READING OF WHAT WAS ASKED FOR: a note with no headings at all. This is the
    * note somebody writes when they did not want to think about structure, and it was producing
    * nothing — the document walk descends through headings and ignores everything else, so a
    * top-level paragraph never reached the code that reads it.
    */
  test("a clozed block in a note with no headings at all becomes a card") {
    val note = extract("The ==<<radius>>== is a forearm bone. ^fa1\n")
    assertEquals(note.failures, Vector.empty, s"${note.failures}")
    assertEquals(paths(note), Vector("block '^fa1'"))
  }

  test("a clozed block before the first heading becomes a card") {
    val note = extract("Prose with the ==<<ulna>>== in it. ^pre1\n\n# B\n\nx\n")
    assertEquals(paths(note), Vector("block '^pre1'"), s"${note.failures}")
  }

  /** NOT DOUBLE-COUNTED. A `#flashcard/cloze` heading already turns its highlights into a card
    * keyed by its path; reading them again as blocks would make two cards of every one.
    */
  test("a marked cloze section is not read a second time as blocks") {
    val note = extract("# B\n\nx\n\n## L #flashcard/cloze\n\nThe ==<<femur>>== is a bone. ^ignored\n")
    assertEquals(paths(note), Vector("b / l"), s"the section's highlights were counted twice")
  }


  /** THE RULING THAT SPLIT `==` IN TWO, 2026-08-28.
    *
    * Before it, every `==text==` in a cloze section was a deletion, and nothing in a note told
    * you which highlights were cards. Marc's ruling: a cloze is `==<<text>>==`. A bare
    * `==text==` is what Obsidian says it is — a highlight, rendering as one, making no card.
    *
    * THE COST OF THE BRACKETS IS FOUR CHARACTERS; what they buy is that an author can see which
    * of their highlights are cards. Reserving `==` outright was the alternative and was cheaper
    * to type, but nothing would have distinguished a card from emphasis by looking.
    */
  test("a bare highlight is NOT a deletion, and a section holding only bare ones is refused") {
    val note = extract("# B\n\nx\n\n## L #flashcard/cloze\n\nThe ==diaphysis== and the ==epiphysis==.\n")
    assertEquals(note.specs, Vector.empty, s"a bare highlight made a card: ${note.specs}")
    val reason = note.failures.collect { case BuildFailure.KeyKnown(_, _, r) => r }.mkString(" ")
    assert(reason.contains("emphasis now"), s"the author is not told why they got nothing: $reason")
  }

  /** THE MIXED CASE, WHICH IS THE ONE THAT COULD GO WRONG QUIETLY. A section holding both kinds
    * must build cards from the bracketed ones ONLY — a bare highlight that slipped into a
    * deletion would blank a word the author meant to emphasise.
    */
  test("a section mixing both kinds makes cards from the bracketed highlights alone") {
    val note = extract(
      "# B\n\nx\n\n## L #flashcard/cloze\n\nThe ==<<diaphysis>>== and the merely ==important== bit.\n"
    )
    assertEquals(note.failures, Vector.empty, s"${note.failures}")
    val text = specFor(note, "b / l").spec.fields.toMap.apply("Text")
    assert(text.contains("{{c1::diaphysis}}"), s"the bracketed highlight is not a deletion: $text")
    assert(!text.contains("{{c2::"), s"a bare highlight became a second deletion: $text")
    assert(text.contains("<mark>important</mark>"), s"the bare highlight lost its highlighting: $text")
  }

  def clozeOf(n: ExtractedNote, path: String): CardSpec.Cloze =
    specFor(n, path).spec match
      case c: CardSpec.Cloze => c
      case other             => fail(s"expected a Cloze spec, got $other")

  test("one cloze SECTION becomes one note holding all its deletions") {
    val note = extract(
      "# Bones\n\nx\n\n## Long bone #flashcard/cloze\n\nThe ==<<diaphysis>>== and the ==<<epiphysis>>==.\n"
    )
    assertEquals(note.specs.size, 1, "a cloze section must yield ONE note, not one per highlight")
    assertEquals(clozeOf(note, "bones / long bone").deletions.length, 2)
  }

  /** UNLABELLED: its own group of one, numbered in order of first appearance. */
  test("unlabelled highlights each form their own group") {
    val note = extract("# B\n\nx\n\n## L #flashcard/cloze\n\nThe ==<<a>>== and the ==<<b>>==.\n")
    val ds   = clozeOf(note, "b / l").deletions.toVector
    assertEquals(ds.map(_.ordinal), Vector(1, 2))
    assertEquals(ds.map(_.group), Vector(ClozeGroup.Unlabelled("a"), ClozeGroup.Unlabelled("b")))
  }

  /** LABELLED: several highlights sharing a label are ONE group and blank together. */
  test("highlights sharing a label form ONE group with one card") {
    val note = extract(
      "# B\n\nx\n\n## L #flashcard/cloze\n\n==<<1|alpha>>== then ==<<2|beta>>== then ==<<1|gamma>>==.\n"
    )
    val ds = clozeOf(note, "b / l").deletions.toVector
    assertEquals(ds.size, 2, s"expected two groups, got ${ds.map(_.group)}")
    assertEquals(ds.map(_.ordinal).sorted, Vector(1, 2))
    val groupOne = ds.find(_.ordinal == 1).getOrElse(fail("no group 1"))
    assertEquals(groupOne.texts, Vector("alpha", "gamma"))
  }

  /** THE POINT OF LABELLING. The group id is the key, so the text may change freely and the
    * card keeps its history — whereas an unlabelled deletion is keyed by its own text.
    */
  test("a labelled group's identity SURVIVES a text edit; an unlabelled one does not") {
    def groupsOf(body: String) =
      clozeOf(extract(s"# B\n\nx\n\n## L #flashcard/cloze\n\n$body\n"), "b / l").deletions.toVector
        .map(_.group)

    // Labelled: fixing a typo leaves the group untouched.
    assertEquals(groupsOf("The ==<<1|Mercurey>>== orbits."), groupsOf("The ==<<1|Mercury>>== orbits."))
    // Unlabelled: the same fix retires the key. Accepted, and visible.
    assertNotEquals(groupsOf("The ==<<Mercurey>>== orbits."), groupsOf("The ==<<Mercury>>== orbits."))
  }

  test("an unlabelled group never takes a number a label has claimed") {
    val note = extract("# B\n\nx\n\n## L #flashcard/cloze\n\n==<<1|a>>== and ==<<b>>== and ==<<2|c>>==.\n")
    val ds   = clozeOf(note, "b / l").deletions.toVector
    assertEquals(ds.map(_.ordinal).sorted, Vector(1, 2, 3), s"numbers collided: ${ds.map(_.ordinal)}")
  }

  /** Separate groups by rule, identical text, and nothing but POSITION to tell them apart.
    * Refused with the remedy named, rather than tie-broken positionally.
    */
  test("two IDENTICAL unlabelled highlights are refused, with the remedy named") {
    val note = extract(
      "# B\n\nx\n\n## L #flashcard/cloze\n\nA ==<<quorum>>== is a majority. Any two ==<<quorum>>== sets meet.\n"
    )
    assertEquals(note.specs, Vector.empty)
    val reason = note.failures.collectFirst { case BuildFailure.KeyKnown(_, _, r) => r }
    assert(reason.exists(_.contains("label them")), s"remedy not named: $reason")
  }

  test("identical text is fine when the duplicates are LABELLED into one group") {
    val note = extract(
      "# B\n\nx\n\n## L #flashcard/cloze\n\nA ==<<1|quorum>>== is a majority. Two ==<<1|quorum>>== sets meet.\n"
    )
    assertEquals(note.specs.size, 1)
    assertEquals(clozeOf(note, "b / l").deletions.length, 1)
  }

  test("a cloze section with no highlight at all is reported") {
    val note = extract("# B\n\nx\n\n## L #flashcard/cloze\n\nJust prose, nothing marked.\n")
    assertEquals(note.specs, Vector.empty)
    assert(note.failures.nonEmpty)
  }

  test("a literal ==highlight== inside a code span is NOT a deletion") {
    val note = extract(
      "# B\n\nx\n\n## L #flashcard/cloze\n\nWrite `==x==` to mark one. The ==<<real>>== one counts.\n"
    )
    val ds = clozeOf(note, "b / l").deletions.toVector
    assertEquals(ds.map(_.group), Vector(ClozeGroup.Unlabelled("real")))
  }

  /** The body carries the WHOLE section AND its deletions, marked where the author put them.
    *
    * THIS TEST PREVIOUSLY ASSERTED `"The femur is a bone."` — the plain text, with no deletion
    * in it at all — and passed. It was pinning the defect in place: Anki REFUSES a Cloze note
    * containing no `{{cN::…}}`, so every cloze card this tool produced failed to be created.
    * The first live run found it; nothing here could, because the in-memory Anki and the fake
    * AnkiConnect server both accept such a note happily.
    */
  test("a cloze note carries the whole section text, WITH its deletions marked in place") {
    val note = extract("# B\n\nx\n\n## L #flashcard/cloze\n\nThe ==<<femur>>== is a bone.\n")
    // The `<p>` arrived in S11. `{{c1::…}}` is unchanged and UNESCAPED: those braces are the
    // tool's own, put there by `content.Html.clozeDeletion` after the inner text was escaped.
    assertEquals(clozeOf(note, "b / l").text.value, "<p>The {{c1::femur}} is a bone.</p>")
  }

  /** The Back field of a two-field card, which is where a dropped body construct shows up. */
  def twoFieldBack(n: ExtractedNote, path: String): String =
    specFor(n, path).spec.fields.toMap.getOrElse("Back", fail(s"no Back field at '$path'"))

  // ======================================= nothing in a body is silently discarded ====
  //
  // `CARD-MODEL.md` §"Where the concept comes from" ratifies what a description may contain:
  // "the whole section body — prose, lists, formulae, code". Each test below covers a
  // construct that reached the Anki field as NOTHING, with the card created and looking
  // correct. They share one cause: Laika's containers do not form the hierarchy the walk
  // assumed, so anything outside it fell to a catch-all returning the empty string.

  /** A fenced code block with no language is a `LiteralBlock` — a `TextContainer`, which is
    * neither a `SpanContainer` nor an `ElementContainer`. It fell straight through.
    */
  test("a fenced code block survives into the card") {
    val note = extract("# B\n\nx\n\n## Run #flashcard/2way\n\nRun this:\n\n```\nscala-cli test .\n```\n")
    val back = twoFieldBack(note, "b / run")
    assert(back.contains("scala-cli test ."), s"the code fence was dropped: [$back]")
  }

  test("an indented code block survives into the card") {
    val note = extract("# B\n\nx\n\n## Run #flashcard/2way\n\nRun this:\n\n    scala-cli test .\n")
    val back = twoFieldBack(note, "b / run")
    assert(back.contains("scala-cli test ."), s"the indented code block was dropped: [$back]")
  }

  /** A `Table` is a `Block` with `ElementTraversal`, but it is NOT an `ElementContainer` and
    * has no `content` member at all — so a walk descending through `content` matched nothing
    * and the whole table vanished. This is a table inside an ORDINARY body, not a
    * `#flashcard/table` card.
    */
  test("a markdown table inside an ordinary body survives into the card") {
    val note = extract(
      "# B\n\nx\n\n## Compare #flashcard/2way\n\nintro\n\n| A | B |\n| - | - |\n| 1 | 2 |\n"
    )
    val back = twoFieldBack(note, "b / compare")
    assert(back.contains("1") && back.contains("2"), s"the table was dropped: [$back]")
  }

  /** THE SAME GAP IN THE ONLY SAFETY CHECK THE EXTRACT LAYER HAS.
    *
    * Embeds are refused BY TYPE because an Anki card cannot resolve an Obsidian attachment
    * link. The doc comment on that check claimed it reached "inside table cells". It did not,
    * for the same reason as above — so an embed hidden in a cell was accepted and a card with
    * a broken image was written.
    *
    * ASSERTED ON THE REJECTION FIRING, never on the absence of the embed from the output.
    */
  test("an embed hidden inside a table cell is REFUSED, not quietly accepted") {
    val note = extract(
      "# B\n\nx\n\n## Compare #flashcard/2way\n\n| A | B |\n| - | - |\n| ![[pic.png]] | 2 |\n"
    )
    val refusal = note.failures.map(_.toString).mkString(" ") + note.specs.map(_.toString).mkString(" ")
    assert(
      note.specs.isEmpty,
      s"a card was built from a body containing an embed: $refusal",
    )
    assert(refusal.contains("pic.png"), s"the refusal does not name the embed: $refusal")
  }

  /** A body that is ONLY a list came out empty, and an empty body is a hard error — so the
    * card was refused with a message blaming the author for a body that is plainly there.
    */
  test("a body that is only a list produces a card, not an empty-body error") {
    val note = extract("# B\n\nx\n\n## Layers #flashcard/2way\n\n- epidermis\n- dermis\n")
    assert(note.failures.isEmpty, s"a list-only body was refused: ${note.failures}")
    val back = twoFieldBack(note, "b / layers")
    assert(back.contains("epidermis") && back.contains("dermis"), s"[$back]")
  }

  test("a nested list keeps its sub-items") {
    val note = extract("# B\n\nx\n\n## Layers #flashcard/2way\n\n- outer\n    - inner\n")
    val back = twoFieldBack(note, "b / layers")
    assert(back.contains("outer") && back.contains("inner"), s"the sub-item was dropped: [$back]")
  }

  // =============================== the other half: nothing is silently ADDED or LOST ====
  //
  // Descending through every child fixes under-inclusion. It invites the opposite mistake,
  // and the four below are it. THE RULE THEY ENFORCE IS: MATCH CONCRETE NODE TYPES, NEVER
  // TRAITS. Laika's hierarchy is open, so a trait match is a bet on what else implements it —
  // and the bet is lost in both directions at once, silently.

  /** AN HTML COMMENT IS PRIVATE. Obsidian does not render one, so an author uses it for notes
    * meant for nobody. Putting it on a flashcard is a disclosure, not a formatting slip.
    *
    * INTRODUCED 2026-08-20 BY THE FIX FOR CODE BLOCKS. A `LiteralBlock` is a `TextContainer`,
    * so `case tc: TextContainer` rescued code blocks — and `Comment` is a `TextContainer` too,
    * so the same line put comments on cards. Before that fix a comment fell to the catch-all
    * and was correctly dropped. One defect closed, another opened, by one trait match.
    */
  test("an HTML comment stays private and never reaches the card") {
    val note = extract("# B\n\nx\n\n## C #flashcard/2way\n\nreal answer\n\n<!-- PRIVATE NOTE -->\n")
    val back = twoFieldBack(note, "b / c")
    assert(!back.contains("PRIVATE"), s"a private comment was put on the card: [$back]")
    // The `<p>` arrived in S11. The point of the whole-value assertion is unchanged: the
    // comment appears NOWHERE, not even as an empty element or a stray space.
    assertEquals(back, "<p>real answer</p>")
  }

  /** A CLOZE SECTION WHOSE HIGHLIGHT IS INSIDE A TABLE was told it had no highlight at all.
    *
    * The highlight collector matched `ElementContainer` while the renderer beside it had
    * learned about `Table`; a `Table` is not an `ElementContainer`, so the two walked the same
    * blocks with different lattices. The message named a remedy the author had already applied.
    */
  test("a highlight inside a table is found, not reported as missing") {
    val note = extract(
      "# B\n\nx\n\n## C #flashcard/cloze\n\n| A | B |\n| - | - |\n| ==<<one>>== | two |\n"
    )
    assert(
      !note.failures.map(_.toString).mkString.contains("no ==highlight=="),
      s"a section with a highlight was told it had none: ${note.failures}",
    )
    assertEquals(clozeOf(note, "b / c").deletions.toVector.size, 1)
  }

  /** MARKDOWN IMAGES ARE REFUSED LIKE OBSIDIAN EMBEDS, and for the same reason: an Anki card
    * cannot resolve a vault-relative path.
    *
    * `![[x.png]]` was refused by name while `![x](x.png)` was swallowed without a word —
    * Laika parses the latter to `Image`, which is a `Link`, and neither a `TextContainer` nor
    * a `SpanContainer`.
    *
    * THIS COMMENT USED TO CLAIM THE CHECK REACHED A MARKED HEADING TOO ("in a heading that
    * silently changes the key: two headings differing only by their image were the same
    * card"). It never did: `Section(header, content)` keeps the header OUTSIDE `content`
    * (`laika/ast/blocks.scala:231`), and this check runs over `Extractor.ownBody`, which is
    * built from `section.content`. The heading hazard is REAL AND STILL OPEN — an image in a
    * marked heading is dropped by `SpanContainer.extractText`'s silent default and the card's
    * KEY changes, which orphans a live synced note rather than merely losing a word. It is
    * named at `Extractor.scala`'s `section.header.extractText` and needs its own slice. THIS
    * TEST IS ABOUT THE BODY.
    */
  test("a markdown image in a body is REFUSED, not silently swallowed") {
    val note = extract("# B\n\nx\n\n## C #flashcard/2way\n\nsee ![diagram](diagram.png) here\n")
    assert(note.specs.isEmpty, s"a card was built from a body containing an image: ${note.specs}")
    assert(
      note.failures.map(_.toString).mkString.contains("diagram.png"),
      s"the refusal does not name the image: ${note.failures}",
    )
  }

  /** THE SAFETY CHECK DID NOT RUN AT ALL FOR A TABLE-MARKED SECTION — it lives in the branch
    * that table sections skip. So an embed in a cell was neither rendered nor refused: the
    * cell came back empty, the row was dropped for being empty, and NOTHING was reported.
    * A card silently ceased to exist.
    */
  test("an embed inside a #flashcard/table section is REFUSED, not silently dropped") {
    val note = extract(
      "# B\n\nx\n\n## C #flashcard/table\n\n| Pattern | Purpose |\n| - | - |\n| ![[pic.png]] | recover |\n"
    )
    assert(
      note.failures.map(_.toString).mkString.contains("pic.png"),
      s"an embed in a table cell was not refused: specs=${note.specs.size} failures=${note.failures}",
    )
  }

  /** THE ONE DOCUMENTED USE OF LISTS, and it had no test at all.
    *
    * `CARD-MODEL.md` §Lists rules that unordered lists need no card type of their own —
    * "membership is the knowledge, not sequence. Plain multi-cloze covers it". So the
    * supported way to write one is highlights inside list items, which means a cloze body
    * containing a `BulletList`.
    *
    * That is precisely where this project has been bitten before: a `BulletList` is a
    * `ListContainer`, NOT a `BlockContainer`, so a walker matching only on blocks returns
    * nothing for a list and the card comes out empty — silently, since an empty body is only
    * an error when it is empty for every reason at once.
    */
  test("highlights inside a bullet list become deletions, list and all") {
    val note = extract(
      "# B\n\nx\n\n## L #flashcard/cloze\n\nThe three layers:\n\n- the ==<<epidermis>>==\n- the ==<<dermis>>==\n- the ==<<hypodermis>>==\n"
    )
    val spec = clozeOf(note, "b / l")
    assertEquals(spec.deletions.toVector.size, 3, s"lost a deletion inside the list: ${spec.text.value}")
    val rendered = spec.text.value
    assert(rendered.contains("{{c1::epidermis}}"), rendered)
    assert(rendered.contains("{{c2::dermis}}"), rendered)
    assert(rendered.contains("{{c3::hypodermis}}"), rendered)
    assert(rendered.contains("The three layers:"), s"the prose before the list was dropped: $rendered")
  }

  /** A numbered list is ordinary content too. Progressive disclosure — one card per step,
    * revealing the previous ones — is what `CARD-MODEL.md` defers, not this.
    */
  test("highlights inside a numbered list are deletions like any other") {
    val note = extract(
      "# B\n\nx\n\n## L #flashcard/cloze\n\n1. first the ==<<ureter>>==\n2. then the ==<<bladder>>==\n"
    )
    val rendered = clozeOf(note, "b / l").text.value
    assert(rendered.contains("{{c1::ureter}}") && rendered.contains("{{c2::bladder}}"), rendered)
  }

  /** A label IS the cloze number, so two highlights sharing one blank together as one card. */
  test("a labelled group renders its own number, and a shared label renders the same number") {
    val note = extract(
      "# B\n\nx\n\n## L #flashcard/cloze\n\nA ==<<2|quorum>>== is a majority; two ==<<2|quorum>>== sets meet.\n"
    )
    assertEquals(
      clozeOf(note, "b / l").text.value,
      "<p>A {{c2::quorum}} is a majority; two {{c2::quorum}} sets meet.</p>",
    )
  }

  /** An unlabelled group takes the lowest number no label has claimed, so it cannot collide
    * with a label the author chose — which would silently merge two cards into one.
    */
  test("an unlabelled group skips a number a label already claims") {
    val note = extract("# B\n\nx\n\n## L #flashcard/cloze\n\nThe ==<<1|shaft>>== and each ==<<end>>==.\n")
    assertEquals(
      clozeOf(note, "b / l").text.value,
      "<p>The {{c1::shaft}} and each {{c2::end}}.</p>",
    )
  }

  /** A highlight inside a code span is not a deletion, so it must survive rendering as the
    * literal text the author typed rather than becoming a blank.
    */
  test("a literal highlight inside code is rendered as text, not turned into a deletion") {
    val note = extract(
      "# B\n\nx\n\n## L #flashcard/cloze\n\nWrite `==x==` to mark one. The ==<<real>>== one counts.\n"
    )
    val rendered = clozeOf(note, "b / l").text.value
    assert(rendered.contains("{{c1::real}}"), rendered)
    assert(!rendered.contains("::x}}"), s"a code-span highlight became a deletion: $rendered")
  }

  // ============================== S9: refusal reasons, pinned before they were rewired ====
  //
  // THREE CHARACTERIZATION PINS AND ONE NEW BEHAVIOUR. T1, T2 and T3 were written and run
  // GREEN against the pre-S9 tree, so that the behaviour they describe could be shown not to
  // move when the extract layer was swapped onto `content.Lower`. T4 was written and run RED
  // against that same tree: it is the ONE behaviour S9 deliberately changes.
  //
  // Why they had to be written at all: `extract/golden/fixture-cards.txt` records a failure's
  // CASE NAME and KEY and deliberately never its reason, so nothing in the suite pinned any of
  // these reason strings. S9 changes how two of them are derived.

  /** T1 — CHARACTERIZATION. Ambiguous unlabelled cloze deletions are refused with the remedy.
    *
    * PINNED BY NOTHING BEFORE S9: there is no other test for
    * [[SpecError.AmbiguousClozeDeletion]] in the repo, and `dummy-vault` contains no ambiguous
    * section, so the golden cannot witness it either. S9 changes this check's KEY DERIVATION —
    * the unlabelled group key stops coming from Laika's `extractText` and starts coming from
    * the rendered `AsText` text — so the check must not be left unpinned while it moves.
    */
  test("two unlabelled highlights with identical text are refused, with the remedy named") {
    val note = extract(
      "# B\n\nx\n\n## L #flashcard/cloze\n\nA ==<<quorum>>== is a majority; two ==<<quorum>>== sets meet.\n"
    )
    assert(note.specs.isEmpty, s"an ambiguous cloze section produced a card: ${note.specs}")
    val reason = note.failures.collect { case BuildFailure.KeyKnown(_, _, r) => r }.mkString(" ")
    assert(reason.contains("quorum"), s"the refusal does not name the duplicated text: $reason")
    assert(reason.contains("label them"), s"the refusal does not name the remedy: $reason")
  }

  /** T2 — CHARACTERIZATION. A cloze section with no `==highlight==` is refused by name. */
  test("a cloze section with no highlight is refused, naming what is missing") {
    val note = extract("# B\n\nx\n\n## L #flashcard/cloze\n\nOrdinary prose, nothing marked.\n")
    assert(note.specs.isEmpty, s"a cloze section without a deletion produced a card: ${note.specs}")
    val reason = note.failures.collect { case BuildFailure.KeyKnown(_, _, r) => r }.mkString(" ")
    assert(
      reason.contains("no ==<<highlight>>=="),
      s"the refusal does not name the syntax that actually makes a card: $reason",
    )
    assert(
      reason.contains("emphasis now"),
      s"the refusal does not tell an author who wrote plain highlights why they made nothing: $reason",
    )
  }

  /** T3 — CHARACTERIZATION, AND VACUOUS BEFORE S9. A multi-item task list is refused ONCE.
    *
    * Before the swap this could not fail: the reason was the single sentence "task list is not
    * supported, at '…'" however many items the list had. After the swap it is the ONLY thing
    * pinning the `.distinct` in `Extractor.bodyBlocks` — the lowering returns one
    * `Refusal.TaskList` PER ITEM (measured: two items, two refusals; five items, five), and
    * without the de-duplication the author reads "a task list; a task list".
    *
    * The cost of that `.distinct` is accepted deliberately and stated here so it is not
    * mistaken for an oversight: the author is NOT told how many items there were.
    */
  test("a multi-item task list is refused once, not once per item") {
    val note = extract("# A\n\nx\n\n## Marked #flashcard/1way\n\n- [ ] a\n- [x] b\n")
    assert(note.specs.isEmpty, s"a task list produced a card: ${note.specs}")
    val reason = note.failures.collect { case BuildFailure.KeyKnown(_, _, r) => r }.mkString(" ")
    val occurrences = reason.sliding("task".length).count(_ == "task")
    assertEquals(occurrences, 1, s"'task' should appear exactly once in: $reason")
  }

  /** T4 — NEW BEHAVIOUR, AND THE ONE INTENDED RED. Every refusal in a body is reported.
    *
    * BEFORE S9 THIS FAILED, and that failure is the point. `Extractor.bodyText` ran three
    * independent `collectFirst`s over the body's spans and returned a SINGLE `SpecError`, with
    * an embed anywhere beating a task list earlier in the document — so the author fixed the
    * embed, re-ran, and only then learned about the task list. `content.Lower` accumulates in
    * DOCUMENT ORDER and `Extractor.bodyBlocks` joins every description, so one run names both.
    *
    * Invisible to the golden, which pins a failure's case name and key but never its reason.
    */
  test("a body holding two refusable constructs reports BOTH, not just the first") {
    val note = extract(
      "# A\n\nx\n\n## Marked #flashcard/1way\n\n- [ ] a task first\n\nthen ![[diagram.png]]\n"
    )
    assert(note.specs.isEmpty, s"a card was built from a body with two refusals: ${note.specs}")
    val reason = note.failures.collect { case BuildFailure.KeyKnown(_, _, r) => r }.mkString(" ")
    assert(reason.contains("task"), s"the task list is not named: $reason")
    assert(reason.contains("diagram.png"), s"the embed is not named: $reason")
  }

  // ============================================= S11: the field is HTML ====
  //
  // ANKI FIELDS ARE HTML. A literal newline in one renders as a SPACE — read back from a real
  // note — so while the tool sent plain text, every piece of the author's formatting was
  // destroyed on the way out: a bulleted answer arrived on the card as one run-on line.
  //
  // WHAT THE TWO TESTS BELOW OBSERVE IS TAGS IN A FIELD VALUE. NOBODY HAS RENDERED THESE
  // CARDS. Where a claim here is about LINES A PERSON SEES it is written as a PREDICTION from
  // `<p>` / `<li>` box layout, never as an observation.

  /** THE ACCEPTANCE TEST FOR S11, and it is TWO opposite requirements held in one value.
    *
    * A HARD-WRAPPED PARAGRAPH MUST NOT GAIN A LINE BREAK. The author wraps prose at 80
    * columns and that wrap is not content; it survives here as a literal newline INSIDE one
    * `<p>`, which HTML collapses back to a space — PREDICTED from block layout rather than
    * observed. Emitting `<br>` instead would put the author's column width on the card, which
    * is why the second assertion is about an ABSENCE and carries its own clue.
    *
    * A LIST MUST GAIN ONE. Note what that means under HTML: the list gains NO NEWLINE
    * CHARACTER AT ALL — it gains `</li><li>`. A test written against `\n` would be false on
    * the list half and vacuous on the paragraph half.
    *
    * THE `</p><p>` ADJACENCY is the half the hard-wrap assertion alone cannot prove, so it is
    * pinned by asserting the WHOLE field value rather than by `contains`: a boundary BETWEEN
    * blocks must stop collapsing at the same moment a wrap INSIDE one goes on collapsing.
    * Those two together are the whole job of this slice.
    */
  test("a hard-wrapped paragraph gains no line break, while a list gains one element per item") {
    val note = extract(
      """|# B
         |
         |x
         |
         |## Q #flashcard/2way
         |
         |alpha
         |beta
         |
         |gamma
         |
         |- one
         |- two
         |""".stripMargin
    )
    val back = twoFieldBack(note, "b / q")
    // A NEWLINE IS A LINE BREAK SINCE 2026-08-29 — Obsidian's own default, and the reasoning is
    // at `Html.escapeWithBreaks`. This assertion previously required the opposite and forbade
    // `<br>` outright.
    assertEquals(back, "<p>alpha<br>beta</p><p>gamma</p><ul><li>one</li><li>two</li></ul>")
    assert(
      back.contains("<ul><li>one</li><li>two</li></ul>"),
      s"a list must still become elements rather than breaks: [$back]",
    )
  }

  /** THE ONLY THING PINNING `CellDisplay.Escaped` END TO END.
    *
    * Measured on 2026-08-21: no cell in any `dummy-vault` table contains any of the six
    * escaped characters, so the 27 table-derived cards move
    * `extract/golden/fixture-cards.txt` by ZERO BYTES and the golden cannot witness this.
    *
    * DRIVEN FROM AN INLINE MARKDOWN STRING ON PURPOSE, and adding the same character to
    * `dummy-vault` instead would be the wrong fix: `content/AsText.test.scala` runs a live
    * sweep comparing `AsText.cellDisplay` against `CellDisplay.Default` over the fixture, and
    * it would redden — in a file this slice may not edit.
    *
    * NOTE WHAT THIS DOES NOT ASSERT: the cell is ESCAPED, not rendered. A cell's bold, inline
    * code and block structure still flatten exactly as they did before this slice.
    */
  test("a special character in a table cell reaches the card escaped") {
    val note = extract(
      "# B\n\nx\n\n## T #flashcard/table\n\n| Concept | Note |\n| - | - |\n| Alpha | a < b |\n"
    )
    assertEquals(note.failures, Vector.empty)
    val fields = specFor(note, "b / t / alpha / note").spec.fields.toMap
    val description = fields.getOrElse("Description", fail(s"no Description field — have $fields"))
    assertEquals(description, "a &lt; b")
  }

  // ================================================ S12: the sequence card ====
  //
  // WHAT THESE FOUR TESTS OBSERVE IS SPEC VALUES AND TAGS. The human's requirement is about
  // what a card DOES ON REVIEW — items revealed one at a time, on one schedule — and NOBODY
  // HAS RENDERED THIS CARD. Where a claim below is about what a person would see, it is
  // written as a PREDICTION read off `resources/note-types/cloze-sequence/templates/`,
  // never as an observation.

  /** Every reason this note refused to build, joined — the channel an author actually reads. */
  def refusalReasons(n: ExtractedNote): String =
    n.failures.collect { case BuildFailure.KeyKnown(_, _, r) => r }.mkString(" ")

  /** THE HAPPY PATH: one marked section becomes ONE note of the list note type.
    *
    * THE LEAD-IN PARAGRAPH IS INSIDE THE FIELD AND IS NOT EXTRACTED AWAY, which looks like a
    * leak until you read the template. `front.html:9-13` adds `hidden-cloze` to `#text li` and
    * binds nothing else, so everything in the field that is not a list item is PROMPT — the
    * lead-in becomes the question and the items are what is hidden. Removing it here would
    * remove the question.
    *
    * THE WHOLE `fields` VECTOR IS ASSERTED, not the Text value alone, because field ORDER is
    * part of what a note type contract is: `Marker.FieldOrder.ClozeSequence` is the single
    * source of it and a silent reordering would put the body in the Title.
    *
    * THE `Context` VALUE IS THE ANCESTOR CHAIN WITHOUT THIS HEADING — here just `B`, the note's
    * H1. It stops short of `Path of blood` because that string is already the `Title` field,
    * which this note type's own front template prints as `<h4>{{Title}}</h4>`; including it
    * would print the same words twice on the question side.
    */
  test("a sequence section becomes one Cloze Sequence note whose Text carries the list") {
    val note = extract(
      """|# B
         |
         |x
         |
         |## Path of blood #flashcard/sequence
         |
         |From the body to the lungs:
         |
         |- superior vena cava
         |- right atrium
         |""".stripMargin
    )
    assertEquals(note.failures, Vector.empty)
    assertEquals(paths(note), Vector("b / path of blood"))
    val spec = specFor(note, "b / path of blood").spec
    assertEquals(spec.noteTypeName, Marker.NoteTypes.ClozeSequence)
    assertEquals(
      spec.fields,
      Vector(
        "Title"   -> "Path of blood",
        "Text"    -> "<p>From the body to the lungs:</p><ul><li>superior vena cava</li><li>right atrium</li></ul>",
        // THE FILE NAME LEADS THE BREADCRUMB SINCE 2026-08-24. `extract` defaults `fileName` to
        // "Note", and a breadcrumb is now the WHOLE location minus what the card carries as a
        // field — the marked heading is the `Title` here, so it is excluded and everything
        // above it kept. This read "B" while the breadcrumb was heading-ancestors only.
        "Context" -> "Note › B",
        "Reveal"  -> "",
        // The card's identity, a field rather than a tag since 2026-08-28.
        "Identity" -> "src::n1::b/path%20of%20blood",
      ),
    )
  }

  /** THE RULED REFUSAL: a marker asking for a list, over a body that holds none.
    *
    * The marker and the body are two INDEPENDENT statements, which is the whole point of the
    * marker being explicit rather than inferred from the body — so when they disagree the
    * refusal must name BOTH halves: what was asked for, and what is actually there.
    *
    * IT MUST ALSO NAME THE OTHER EXIT, and that clause is not decoration. A bulleted answer
    * shown WHOLE is legitimate and is a different card from one revealed step by step; the
    * author's real mistake here is usually the MARKER, not the markdown. This message is the
    * only place an author who never read the design document learns that.
    */
  test("a sequence marker over a body with NO list is refused, naming both halves") {
    val note = extract(
      """|# B
         |
         |x
         |
         |## Path of blood #flashcard/sequence
         |
         |Just prose, no list at all.
         |
         |```
         |select 1
         |```
         |""".stripMargin
    )
    assertEquals(note.specs, Vector.empty, s"a sequence card was built with no list: ${note.specs}")
    val reason = refusalReasons(note)
    assert(reason.contains("#flashcard/sequence"), s"the refusal does not name what was ASKED FOR: $reason")
    assert(reason.contains("no list"), s"the refusal does not say a list is missing: $reason")
    assert(reason.contains("a paragraph"), s"the refusal does not name WHAT IS THERE: $reason")
    assert(reason.contains("a code block"), s"the refusal does not name WHAT IS THERE: $reason")
    assert(
      reason.contains("#flashcard/2way"),
      s"the refusal does not name the other exit, so the author cannot learn that a list " +
        s"shown WHOLE is a legitimate different card: $reason",
    )
  }

  /** THE SURVIVAL HALF OF THE PREDICATE, and it is the only thing pinning it.
    *
    * `%%…%%` is an Obsidian comment and lowers to ZERO inlines, so each item here holds one
    * empty paragraph. The renderer drops an item that renders empty, and then drops the whole
    * list — so the `Text` field would contain the lead-in paragraph and NO `li` at all.
    *
    * A PRESENCE-ONLY PREDICATE PASSES THIS BODY: the list block is right there with two items
    * in it. So is B6, carried by the lead-in paragraph, and so is `Body.fromExtracted` on the
    * HTML. PREDICTED CONSEQUENCE, read off the templates rather than observed: `front.html:10`
    * hides `#text li` and there are none, so front and back would be identical and the reveal
    * key would do nothing — a card reviewed forever as a prompt with no answer, with no error
    * anywhere.
    */
  test("a sequence list whose items ALL render empty is refused, not shipped as a card") {
    val note = extract(
      """|# B
         |
         |x
         |
         |## Path of blood #flashcard/sequence
         |
         |From the body to the lungs:
         |
         |- %%not written yet%%
         |- %%nor this one%%
         |""".stripMargin
    )
    assertEquals(
      note.specs,
      Vector.empty,
      s"a sequence card whose Text holds no list item was emitted: ${note.specs}",
    )
    val reason = refusalReasons(note)
    assert(reason.contains("#flashcard/sequence"), s"the refusal does not name what was ASKED FOR: $reason")
    assert(
      reason.contains("renders empty") || reason.contains("render empty"),
      s"the refusal does not say the items rendered empty, so the author is sent hunting for " +
        s"a list that is plainly there: $reason",
    )
  }

  /** B6 STILL FIRES FIRST, AND IT MATTERS WHICH ERROR THE AUTHOR READS.
    *
    * The B6 gate sits ahead of the marker match, so a marked heading immediately followed by a
    * subheading reports "empty body" — the more actionable error, since the fix is to write
    * prose — rather than "no list". This ordering is exactly the kind a later reader
    * "improves" by moving the sequence check earlier.
    */
  test("a sequence heading with an EMPTY body reports the empty body, not the missing list") {
    val note = extract(
      """|# B
         |
         |x
         |
         |## Path of blood #flashcard/sequence
         |
         |### Immediately a subheading
         |
         |So the marked heading has no prose of its own.
         |""".stripMargin
    )
    assertEquals(note.specs, Vector.empty)
    val reason = refusalReasons(note)
    assert(reason.contains("empty body"), s"expected an empty-body refusal, got: $reason")
    assert(!reason.contains("no list"), s"the sequence check ran ahead of B6: $reason")
  }

  // ============================== S12b: a sequence sourced from SUBHEADINGS ====
  //
  // `#flashcard/sequence/headers` makes the STRUCTURE of a document the thing recalled. The
  // expected HTML below is a PREDICTION read off `content/AsHtml.scala:558-561` rather than a
  // transcription of what the code produced: an item holding exactly one paragraph renders as
  // `<li>text</li>` with no `<p>`, and an item holding a paragraph AND a nested list takes the
  // general arm, so its own text keeps its `<p>` wrapper.

  /** THE PROSE IS ABSENT, AND THAT IS THE ASSERTION THAT MATTERS.
    *
    * RULED BY MARC 2026-08-28: this marker asks for structure, and prose is not structure, so
    * the card is the outline ALONE. This is the opposite of `#flashcard/sequence`, two tests
    * above, where a lead-in paragraph becomes the question side — there the author wrote the
    * list in order to be a card, so the body IS the card's material.
    */
  test("a heading's subheadings become one Cloze Sequence note, and its prose is not on the card") {
    val note = extract(
      """|# B
         |
         |x
         |
         |## Path of blood #flashcard/sequence/headers
         |
         |From the body to the lungs:
         |
         |### superior vena cava
         |
         |### right atrium
         |""".stripMargin
    )
    assertEquals(note.failures, Vector.empty)
    assertEquals(paths(note), Vector("b / path of blood"))
    val spec = specFor(note, "b / path of blood").spec
    assertEquals(spec.noteTypeName, Marker.NoteTypes.ClozeSequence)
    assertEquals(
      spec.fields,
      Vector(
        "Title"   -> "Path of blood",
        "Text"    -> "<ul><li>superior vena cava</li><li>right atrium</li></ul>",
        "Context" -> "Note › B",
        "Reveal"  -> "",
        // The card's identity, a field rather than a tag since 2026-08-28.
        "Identity" -> "src::n1::b/path%20of%20blood",
      ),
    )
  }

  /** THE MARKED HEADING'S OWN BODY IS NOT MERELY DEPRIORITISED, IT IS ABSENT. Asserted
    * separately from the field comparison above because a future change that appended the
    * outline to the body would still produce a card, and only this says it must not.
    */
  test("prose under a subheading-sourced heading reaches no field of the card") {
    val note = extract(
      """|# B
         |
         |x
         |
         |## Path of blood #flashcard/sequence/headers
         |
         |A sentence that must not appear anywhere.
         |
         |### superior vena cava
         |""".stripMargin
    )
    val spec = specFor(note, "b / path of blood").spec
    assert(
      !spec.fields.exists((_, v) => v.contains("must not appear")),
      s"the heading's prose reached the card: ${spec.fields}",
    )
  }

  test("the recursive form nests the whole subtree") {
    val note = extract(
      """|# B
         |
         |x
         |
         |## Path of blood #flashcard/sequence/headers/recursive
         |
         |### Heart
         |
         |#### Left
         |
         |#### Right
         |
         |### Lungs
         |""".stripMargin
    )
    assertEquals(note.failures, Vector.empty)
    assertEquals(
      specFor(note, "b / path of blood").spec.fields.toMap.apply("Text"),
      "<ul><li><p>Heart</p><ul><li>Left</li><li>Right</li></ul></li><li>Lungs</li></ul>",
    )
  }

  /** DIRECT MEANS DIRECT — the same document, without `/recursive`, keeps only one level. The
    * two are asserted over IDENTICAL markdown so the difference can only be the marker.
    */
  /** THE ORDER TRAVELS AS A FIELD AND NOTHING IN THIS TOOL ACTS ON IT.
    *
    * Both orders produce the SAME nested list — asserted here by comparing the `Text` of the
    * two cards — because which item the reveal key uncovers next is decided at review time by
    * the note type's template, reading this field. A tool that reordered the list would change
    * what the card LOOKS like, which is not what was asked for.
    */
  test("the breadth-first marker changes only the Reveal field, not the list") {
    def textAndReveal(token: String) =
      val note = extract(
        s"""|# B
            |
            |x
            |
            |## Path of blood $token
            |
            |### Heart
            |
            |#### Left
            |
            |### Lungs
            |""".stripMargin
      )
      val fields = specFor(note, "b / path of blood").spec.fields.toMap
      (fields("Text"), fields("Reveal"))

    // BOTH SPELLED OUT, so this test says nothing about which one the short token means.
    // That is asserted once, in `Marker.test.scala`, and flipping the default should not make
    // a test about the FIELD fail — which it did when the default moved on 2026-08-28.
    val (dfsText, dfsReveal) = textAndReveal("#flashcard/sequence/headers/recursive/dfs")
    val (bfsText, bfsReveal) = textAndReveal("#flashcard/sequence/headers/recursive/bfs")

    assertEquals(dfsText, bfsText, "the two orders rendered different lists; only the reveal differs")
    assertEquals(dfsReveal, "", "depth-first must write an EMPTY field, or every existing card changes")
    assertEquals(bfsReveal, Marker.BreadthFirstMarker)
  }

  test("without /recursive the same document yields only the direct children") {
    val note = extract(
      """|# B
         |
         |x
         |
         |## Path of blood #flashcard/sequence/headers
         |
         |### Heart
         |
         |#### Left
         |
         |#### Right
         |
         |### Lungs
         |""".stripMargin
    )
    assertEquals(note.failures, Vector.empty)
    assertEquals(
      specFor(note, "b / path of blood").spec.fields.toMap.apply("Text"),
      "<ul><li>Heart</li><li>Lungs</li></ul>",
    )
  }

  /** THE REFUSAL, AND WHY IT IS THIS ERROR RATHER THAN A NEW ONE.
    *
    * RULED BY MARC 2026-08-28, on the principle that the failure belongs to the SEQUENCE CARD'S
    * OWN PRECONDITION — a card that reveals items needs at least one — and not to the place the
    * items were supposed to come from. The source contributes only the diagnosis. That is the
    * shape `SequenceWithoutItems` already had: a heading path plus a phrase naming what is
    * there instead, documented as covering two situations. This is the third.
    *
    * IT MUST NOT REPORT AN EMPTY BODY. B6 refuses a heading with no prose of its own, and this
    * heading HAS none — that is the ordinary shape of this marker. The blocks are chosen above
    * that gate precisely so the author reads the actionable sentence instead.
    */
  test("a subheading-sourced marker with NO subheadings is refused, and not as an empty body") {
    val note = extract(
      """|# B
         |
         |x
         |
         |## Path of blood #flashcard/sequence/headers
         |
         |Prose, but not a single subheading.
         |""".stripMargin
    )
    assertEquals(note.specs, Vector.empty, s"a card was built with no subheadings: ${note.specs}")
    val reason = refusalReasons(note)
    assert(reason.contains("no subheadings"), s"the refusal does not say what is missing: $reason")
    assert(
      !reason.contains("empty body"),
      s"reported as an empty body, which is this marker's NORMAL shape and sends the author " +
        s"off to write prose that would not help: $reason",
    )
  }

  /** THE CASE THAT SURVIVES THE PARSE AND STILL HAS NOTHING TO SHOW. Every subheading here is
    * marker-only, so each title is empty once the marker is stripped and the renderer drops
    * every item — the same silent-success shape the body-list form already guards against.
    */
  /** THE WHOLE-NOTE ROUTE, WHICH IS THE ONE MARC REACHED FOR FIRST.
    *
    * A marker may be written in the note's frontmatter `tags:` instead of on a heading, and it
    * then applies to the WHOLE NOTE — the file name becomes the title. For this marker that is
    * arguably the most natural use of all: *learn the structure of this note*, whose items are
    * its top-level headings.
    *
    * IT WORKS FOR A REASON WORTH PINNING RATHER THAN REDISCOVERING. `fromWholeNote` builds a
    * SYNTHETIC section whose content is the whole document, precisely so that every marker is
    * built by the same code that builds it for a heading. So the outline reads that synthetic
    * section's children, which are the note's top-level headings. Nothing was added for this;
    * the test exists because "it follows from the design" is exactly the claim that quietly
    * stops being true.
    *
    * FOUND BY USE, 2026-08-28. Marc wrote the marker into frontmatter, synced, and no note
    * appeared — for two reasons that were NOT this one: the tag read `flashard`, a typo that
    * both the marker filter and the did-you-mean check miss because both look for the string
    * `flashcard`; and the installed executable predated the feature.
    */
  test("a whole-note marker in frontmatter makes a card from the note's top-level headings") {
    val root = ObsidianSyntax.markupParser
      .parse("""|# A
                |## A.1
                |## A.2
                |# B
                |## B.1
                |""".stripMargin)
      .fold(e => fail(s"parse: $e"), _.content)

    val note = Extractor.fromWholeNote(
      NoteId.fromFrontmatter("2ac356b7").fold(e => fail(s"note id: $e"), identity),
      "Outline Learning",
      "Outline Learning.md",
      Marker.Sequence(SequenceSource.ChildHeadings(HeadingReach.DirectChildren)),
      root,
      Vector.empty,
      7,
    )

    assertEquals(note.failures, Vector.empty)
    assertEquals(note.specs.size, 1)
    val fields = note.specs.head.spec.fields.toMap
    assertEquals(fields("Title"), "Outline Learning")
    assertEquals(fields("Text"), "<ul><li>A</li><li>B</li></ul>")
  }

  test("a whole-note recursive marker reaches every level of the note") {
    val root = ObsidianSyntax.markupParser
      .parse("""|# A
                |## A.1
                |## A.2
                |# B
                |## B.1
                |""".stripMargin)
      .fold(e => fail(s"parse: $e"), _.content)

    val note = Extractor.fromWholeNote(
      NoteId.fromFrontmatter("2ac356b7").fold(e => fail(s"note id: $e"), identity),
      "Outline Learning",
      "Outline Learning.md",
      Marker.Sequence(SequenceSource.ChildHeadings(HeadingReach.WholeSubtree(RevealOrder.DepthFirst))),
      root,
      Vector.empty,
      7,
    )

    assertEquals(note.failures, Vector.empty)
    assertEquals(
      note.specs.head.spec.fields.toMap.apply("Text"),
      "<ul><li><p>A</p><ul><li>A.1</li><li>A.2</li></ul></li><li><p>B</p><ul><li>B.1</li></ul></li></ul>",
    )
  }

  test("subheadings that are all marker-only are refused rather than shipped as an empty list") {
    val note = extract(
      """|# B
         |
         |x
         |
         |## Path of blood #flashcard/sequence/headers
         |
         |### #flashcard/2way
         |""".stripMargin
    )
    assertEquals(
      note.specs.filter(_.spec.noteTypeName == Marker.NoteTypes.ClozeSequence),
      Vector.empty,
      s"a sequence card whose Text holds no list item was emitted: ${note.specs}",
    )
  }

  // ================== what a card asks for, insofar as its location could name it ====

  /** ==Why only two shapes appear below==
    *
    * A card's deck path is printed on its front — every one of the eight templates opens
    * `<span class="deck">{{Deck}}</span>` — so a deck segment naming the answer prints the
    * answer above the question. Which shapes are at risk is not a matter of taste: it is read
    * off the templates. Only a two-way card (whose reverse blanks the marked heading) and a
    * three-field card (whose first card blanks the concept) ever ask for something a folder,
    * file or heading also names.
    *
    * The empty answers are therefore FINDINGS, not gaps, and are asserted as deliberately as
    * the non-empty ones. A cloze card asks for a highlighted span; a sequence card for a list
    * order; a table card for a cell. None of those is a location part.
    */
  private def recalls(marker: Marker, title: String, ancestors: Vector[String], file: String) =
    Extractor.recallFromLocation(marker, title, ancestors, file).values

  test("a one-way card asks for its body, which no location names") {
    assertEquals(
      recalls(Marker.TwoField(TwoFieldDirections.Forward), "Definition", Vector("Surjection"), "Functions"),
      Vector.empty,
    )
  }

  /** The reverse card blanks `{{Front}}`, which is the marked heading itself. */
  test("a two-way card asks for its own heading") {
    assertEquals(
      recalls(Marker.TwoField(TwoFieldDirections.Both), "Scaphoid", Vector("Carpals"), "Bones"),
      Vector("Scaphoid"),
    )
  }

  /** Card 1 blanks `{{Concept}}`, and the concept is the NEAREST ANCESTOR heading. */
  test("a three-field card asks for its concept, taken from the nearest ancestor") {
    assertEquals(
      recalls(Marker.ThreeField(ThreeFieldDirections.Default), "Definition", Vector("Maths", "Surjection"), "Functions"),
      Vector("Surjection"),
    )
  }

  /** THE CASE THAT MAKES THE FILE NAME DANGEROUS. With no ancestor heading the concept falls
    * back to the FILE NAME (`Extractor.scala`'s `ancestorTitles.lastOption.getOrElse(fileName)`),
    * so the file name becomes the answer — and a deck level naming the file would print it.
    */
  test("with no ancestor heading the concept is the file name") {
    assertEquals(
      recalls(Marker.ThreeField(ThreeFieldDirections.Default), "Definition", Vector.empty, "Surjection"),
      Vector("Surjection"),
    )
  }

  /** `3way/all` adds a third card blanking the DESCRIPTOR — the marked heading. It sits deeper
    * than the concept, so truncating at the concept already removes it; it is named anyway
    * because the check is by VALUE, and a FOLDER sharing that name would sit above the concept
    * and otherwise slip through.
    */
  test("3way/all asks for the descriptor as well as the concept") {
    val got = recalls(Marker.ThreeField(ThreeFieldDirections.All), "Blood supply", Vector("Scaphoid"), "Bones")
    assert(got.contains("Scaphoid"), s"the concept is missing: $got")
    assert(got.contains("Blood supply"), s"the descriptor is missing: $got")
  }

  test("a cloze card asks for a highlighted span, which no location names") {
    assertEquals(recalls(Marker.Cloze, "Layers", Vector("Skin"), "Anatomy"), Vector.empty)
  }

  test("a sequence card asks for a list order, which no location names") {
    assertEquals(recalls(Marker.Sequence(SequenceSource.BodyList), "Path of blood", Vector("Heart"), "Anatomy"), Vector.empty)
  }

  /** A table card's concept is a table CELL and its descriptor a COLUMN HEADER. Neither is a
    * folder, a file or a heading, so a table section's headings are safe at any depth.
    */
  test("a table card asks for a cell, which no location names") {
    assertEquals(
      recalls(Marker.Table(ThreeFieldDirections.All, TableScope.Both), "Sutures", Vector("Bones"), "Anatomy"),
      Vector.empty,
    )
  }

  /** RAW, NOT ESCAPED, and this is the property that makes the whole check work. The concept
    * reaches `CardSpec` HTML-escaped, so a comparison against the spec would miss every heading
    * containing one of the six escaped characters. Taken here, before escaping, it matches the
    * heading text a deck path is built from.
    */
  test("the text is raw, so it can be compared against a deck segment") {
    assertEquals(
      recalls(Marker.ThreeField(ThreeFieldDirections.Default), "Definition", Vector("A & B"), "F"),
      Vector("A & B"),
    )
  }

  // ================ a table's marker must reach the cards it makes ====

  /** ==Why this exists, and why its absence was the real defect==
    *
    * `#flashcard/table` carries two axes — how many DIRECTIONS each cell is asked, and whether
    * the cards are about CELLS, whole ROWS, or both. `model/Marker.test.scala` proves the
    * parser reads those tokens. Nothing proved they reached a card.
    *
    * They did not, for four days. `Extractor.buildSpecs` guarded its table path with
    * `if marker == Marker.Table`, which became permanently FALSE the moment `Marker.Table`
    * gained parameters — a bare `Marker.Table` is the companion object. Production ran the
    * `marker match` arm instead, which happens to be correct, so no card was ever wrong. But
    * had the guard been "repaired" rather than deleted, it would have hardcoded
    * `Default, Both` and silently retired every `/1way`, `/3way`, `/cells` and `/rows` token
    * the README advertises — and NOTHING WOULD HAVE FAILED, because every table in
    * `dummy-vault` is marked bare and no test drove a variant end to end.
    *
    * These tests are that missing end. They assert on CARDS, not on the parser.
    */
  private def tableCardsFor(marker: String): Vector[(String, String)] =
    val note =
      s"""|# Bones
          |
          |## Cranial bones $marker
          |
          || Bone     | Anterior border | Posterior border |
          || -------- | --------------- | ---------------- |
          || Frontal  | Orbital rim     | Coronal suture   |
          || Parietal | Coronal suture  | Lambdoid suture  |
          |""".stripMargin
    extract(note, id = "anatomy", fileName = "Anatomy").specs.map(s => s.spec.noteTypeName -> s.key.path.render)

  /** A bare marker asks for both scopes: a pair card per usable cell, plus a row card per row. */
  test("a bare table marker yields cell cards AND row cards") {
    val cards = tableCardsFor("#flashcard/table")
    assert(cards.exists((_, k) => k == "bones / cranial bones / frontal"), s"no row card: $cards")
    assert(
      cards.exists((_, k) => k == "bones / cranial bones / frontal / anterior border"),
      s"no cell card: $cards",
    )
  }

  /** `/cells` SUPPRESSES the row card. If the marker's scope did not reach `Tables`, this would
    * still contain one — which is exactly what a repaired guard hardcoding `Both` would do.
    */
  test("/cells reaches the cards: the row card is gone, the cell cards remain") {
    val cards = tableCardsFor("#flashcard/table/cells")
    assert(
      !cards.exists((_, k) => k == "bones / cranial bones / frontal"),
      s"/cells still produced a row card, so the scope never reached Tables: $cards",
    )
    assert(
      cards.exists((_, k) => k == "bones / cranial bones / frontal / anterior border"),
      s"/cells lost its cell cards: $cards",
    )
  }

  /** `/rows` is the mirror: the whole-row card alone. */
  test("/rows reaches the cards: the cell cards are gone, the row card remains") {
    val cards = tableCardsFor("#flashcard/table/rows")
    assert(cards.exists((_, k) => k == "bones / cranial bones / frontal"), s"no row card: $cards")
    assert(
      !cards.exists((_, k) => k.endsWith("/ anterior border")),
      s"/rows still produced cell cards, so the scope never reached Tables: $cards",
    )
  }

  /** THE DIRECTION AXIS, which a hardcoded `Default` would also have silently retired. `3way`
    * fills the `ThreeWay` gate field, which is what makes Anki generate the third card; the
    * default leaves it empty. Asserted on the FIELD, because the card count is Anki's business
    * and this tool's business is what it writes.
    */
  test("/3way reaches the cards, and the default does not fill the gate field") {
    def gate(marker: String): Set[String] =
      val note =
        s"""|# Bones
            |
            |## Cranial bones $marker
            |
            || Bone    | Anterior border |
            || ------- | --------------- |
            || Frontal | Orbital rim     |
            |""".stripMargin
      extract(note, id = "anatomy", fileName = "Anatomy").specs
        .flatMap(_.spec.fields.collect { case (n, v) if n == Marker.ThreeWayField => v })
        .toSet

    assertEquals(gate("#flashcard/table"), Set(""), "the default must leave the gate empty")
    assert(gate("#flashcard/table/3way").forall(_.nonEmpty), "/3way did not reach the card")
  }

  // ============ cdd/1way: the card that could not be asked for ====

  /** `System Design Pattern.md` holding `# 3 Components`, which is the note that exposed the
    * vocabulary bug. The author wanted the concept-descriptor shape asked ONE way; the only
    * token meaning "one" was `#flashcard/1way`, which is a different SHAPE with no concept
    * field — so the card read "3 Components" with an empty breadcrumb and no way to know three
    * components OF WHAT.
    *
    * With the marker on the H1 there is no ancestor heading, so the concept falls back to the
    * FILE NAME. That is the whole point: the file already knew the answer.
    */
  private def sdp(marker: String) =
    extract(s"# 3 Components $marker\n- A Problem\n- A Solution\n- A Cost\n",
            id = "sdp", fileName = "System Design Pattern")

  test("cdd/1way puts the file name on the card as the concept") {
    val specs = sdp("#flashcard/cdd/1way").specs
    assertEquals(specs.size, 1)
    val fields = specs.head.spec.fields.toMap
    assertEquals(fields("Concept"), "System Design Pattern")
    assertEquals(fields("Descriptor"), "3 Components")
    assert(fields("Description").contains("A Problem"), fields("Description"))
  }

  /** THE GATE THAT MAKES IT ONE CARD. `ValueOnly` is INVERTED — a non-empty value suppresses
    * the concept-recall card — so that a note predating the field renders as it always did.
    * `cdd/2way` must leave it empty; `cdd/1way` must fill it.
    */
  test("cdd/1way sets the gate that suppresses the second direction, and cdd/2way does not") {
    def gate(marker: String) = sdp(marker).specs.head.spec.fields.toMap.apply(Marker.ValueOnlyField)
    assert(gate("#flashcard/cdd/1way").nonEmpty, "cdd/1way did not suppress the concept card")
    assertEquals(gate("#flashcard/cdd/2way"), "", "cdd/2way must leave the gate empty")
  }

  /** THE PROPERTY THAT MAKES THE RENAME FREE. Rewriting a marker must change nothing a sync can
    * see — same key, same note type, same fields, therefore the same content hash and no
    * update. Asserted on the whole spec rather than field by field, so nothing can differ
    * quietly.
    */
  test("rewriting 3way as cdd/2way changes nothing about the cards") {
    assertEquals(
      sdp("#flashcard/3way").specs.map(s => (s.key, s.spec)),
      sdp("#flashcard/cdd/2way").specs.map(s => (s.key, s.spec)),
    )
    assertEquals(
      sdp("#flashcard/3way/all").specs.map(s => (s.key, s.spec)),
      sdp("#flashcard/cdd/3way").specs.map(s => (s.key, s.spec)),
    )
  }

  // ------------------- a heading's SECOND reading: what it looks like on a card ----

  /** Parse one heading and hand back its `Header`, THROUGH THE PRODUCTION PARSER. Hand-built
    * Laika values are banned here — see the note at the head of `AsText.test.scala` — because
    * such a tree can encode an input no parser can produce, and which markdown yields which
    * spans is the entire subject of these tests.
    */
  def header(line: String): laika.ast.Header =
    ObsidianSyntax.markupParser
      .parse(line + "\n\nbody\n")
      .fold(e => fail(s"parse: $e"), _.content)
      .collect { case s: laika.ast.Section => s.header }
      .headOption
      .getOrElse(fail(s"no section produced by: $line"))

  /** The plain-text reading of a run of spans, which is what a key is made of. */
  def plainOf(spans: Vector[laika.ast.Span]): String =
    C.Lower.spans(spans) match
      case Left(refusals) => fail(s"refused: ${refusals.toVector}")
      case Right(inlines) => C.AsText.plain(Vector(C.Block.Paragraph(inlines)))

  def faceOf(line: String): String =
    Extractor.headingFace(header(line)).fold(rs => fail(s"refused: ${rs.toVector}"), _.render)

  /** THE LAW THAT KEEPS THE TWO READINGS HONEST, and the reason this is not merely another
    * hand-written second walk.
    *
    * A heading has an identity reading and a display reading. They are allowed to differ in
    * FORM, since one is a key and the other is markup, and they must never differ in WHICH
    * WORDS the heading contains. Nothing enforced that before, which is exactly how two walks
    * drifted in the cloze case (`extract/Cloze.scala:62`) and how the two collapsed into one
    * here.
    *
    * A DIFFERENTIAL RATHER THAN TWO FIXED STRINGS, deliberately. A fixed pair can be satisfied
    * by editing both; this fails the moment either derivation learns something the other does
    * not.
    */
  def assertReadingsAgree(line: String): Unit =
    val h           = header(line)
    val keyReading  = Marker.stripMarker(h.extractText)
    val faceReading = plainOf(Extractor.spansWithoutMarker(h.content.toVector))
    assertEquals(faceReading, keyReading, s"the two readings disagree for: $line")

  test("readings agree: a plain heading") {
    assertReadingsAgree("# Definition #flashcard/2way")
  }

  test("readings agree: a heading carrying maths") {
    assertReadingsAgree("# Notation (Given 2 sets, $A$ and $B$) #flashcard/cdd/2way")
  }

  test("readings agree: a heading carrying a wikilink and emphasis") {
    assertReadingsAgree("# The *fast* [[Message Queue]] path #flashcard/2way")
  }

  test("readings agree: a heading with no marker at all") {
    assertReadingsAgree("# An ancestor heading nobody marked")
  }

  // ── the face itself ──────────────────────────────────────────────────────────────

  test("face: a plain heading is escaped text, exactly as it is today") {
    assertEquals(faceOf("# Layers of the epidermis #flashcard/2way"), "Layers of the epidermis")
  }

  test("face: a heading's maths is RENDERED, not shown as the author's dollars") {
    assertEquals(
      faceOf("# Notation (Given 2 sets, $A$ and $B$) #flashcard/cdd/2way"),
      """Notation (Given 2 sets, \(A\) and \(B\))""",
    )
  }

  test("face: the escaped characters are still escaped") {
    assertEquals(faceOf("# W + R > N & b #flashcard/2way"), "W + R &gt; N &amp; b")
  }

  /** AN IMPROVEMENT RATHER THAN A NEW FAILURE, and the reason it is safe to make here. An image
    * in a marked heading is dropped in silence today, which is the open defect named at this
    * file's section walk. Routed through the lowering it is refused BY NAME. The key was
    * derived before this runs and still exists, so there is something to attach the refusal to,
    * and that ordering is the whole reason the ruling about the identity path is not violated.
    */
  test("face: an image in a heading REFUSES, where today it vanishes without a word") {
    val result = Extractor.headingFace(header("# See ![[diagram.png]] here #flashcard/2way"))
    assert(result.isLeft, s"an image in a heading was silently accepted: $result")
  }

  // ── stripping the marker off SPANS, which is the part that can eat a word ────────

  test("marker strip: the token goes and the rest of the trailing text stays") {
    assertEquals(plainOf(Extractor.spansWithoutMarker(header("# Definition #flashcard/2way").content.toVector)),
      "Definition")
  }

  test("marker strip: non-text spans either side of the marker survive") {
    val spans = Extractor.spansWithoutMarker(header("# $A$ and $B$ #flashcard/2way").content.toVector)
    assertEquals(spans.collect { case m: ObsidianSyntax.MathInline => m.tex }, Vector("A", "B"))
  }

  // ══════════ a heading this tool does not read as a heading (oas-30t) ════
  //
  // A `#` heading written at the start of the line DIRECTLY AFTER a list line, with no blank line
  // between them, is absorbed by laika-core 1.3.2 into the open list item and parsed there as a
  // nested `ast.Header`. The section builder lifts only TOP-LEVEL headers into `Section`s, so it
  // never becomes one — while CommonMark, which is what Obsidian follows, closes the list and
  // reads a heading. `extract/ListIndent.scala` carries the measurement, and
  // `extract/UnreadHeadings.test.scala` pins the detection itself; these tests pin what the
  // EXTRACTOR does about it. Every consequence below reported `failures: 0` and exit 0 before
  // this section existed.
  //
  // EVERY CASE HERE DIFFERS FROM ITS CONTROL BY ONE BLANK LINE, which is the whole experiment: a
  // test that ever passes for some other reason passes its control too, and the pair stops being
  // informative.

  private def misfiled(note: ExtractedNote): Vector[String] = note.failures.collect {
    case BuildFailure.KeyMisfiledInFile(_, _, reason) => reason
  }

  private def unreadHeading(note: ExtractedNote): Vector[String] = note.failures.collect {
    case BuildFailure.HeadingUnreadInFile(_, _, reason) => reason
  }

  /** Consequence 1 of 4: the swallowed heading carried the marker, so its card is never created.
    *
    * It is the ONLY marked heading in the note, so before this check the note produced nothing at
    * all and said nothing at all.
    */
  test("swallowed heading: a marked heading absorbed by a list makes no card, and says so") {
    val note = extract(
      """|# Scale
         |
         |- users
         |- data size
         |# 5 Questions #flashcard/sequence
         |- How does data move ?
         |- Where is data stored ?
         |""".stripMargin
    )
    assertEquals(note.specs, Vector.empty)
    assert(misfiled(note).exists(_.contains("5 Questions")), s"not named: ${note.failures}")
  }

  test("swallowed heading CONTROL: with the blank line the same note builds its card") {
    val note = extract(
      """|# Scale
         |
         |- users
         |- data size
         |
         |# 5 Questions #flashcard/sequence
         |
         |- How does data move ?
         |- Where is data stored ?
         |""".stripMargin
    )
    assertEquals(paths(note), Vector("5 questions"))
    assertEquals(note.failures, Vector.empty)
  }

  /** Consequences 2 and 4 of 4, which arrive together on one card. The swallowed heading's own
    * text becomes CONTENT of the card above it — marker and all, since nothing here reads the
    * line as a heading — and the list items written UNDER it rejoin the list above it, because
    * one `BulletList` spans the whole run. So the card above answers with a four-item list the
    * note does not contain, two of whose items were written under a different heading.
    *
    * The refusal is what this asserts: no card is built, so there is no face for any of it to
    * reach.
    */
  test("swallowed heading: the card above is refused rather than shipped with the text below it") {
    val note = extract(
      """|# Scale #flashcard/sequence
         |
         |- users
         |- data size
         |# 5 Questions
         |- How does data move ?
         |- Where is data stored ?
         |""".stripMargin
    )
    assertEquals(note.specs, Vector.empty)
    assert(misfiled(note).exists(_.contains("5 Questions")), s"not named: ${note.failures}")
  }

  /** Consequence 3 of 4, AND THE EXPENSIVE ONE. The swallowed heading is unmarked and a marked
    * heading BELOW it survives — so the card builds perfectly and is merely filed under the wrong
    * parent. Nothing fails, which is why no per-card refusal could ever reach it.
    *
    * `# Alpha` / list / `# Beta` / `## Gamma #flashcard/1way` keys as `alpha / gamma` without the
    * blank line and `beta / gamma` with it. A heading path is half a card's identity, so adding
    * the blank line later orphans the note and mints a history-less replacement.
    */
  test("swallowed heading: a card that would be filed under the WRONG PARENT is refused") {
    val note = extract(
      """|# Alpha
         |
         |- an item
         |# Beta
         |
         |## Gamma #flashcard/1way
         |
         |The answer.
         |""".stripMargin
    )
    assertEquals(
      paths(note),
      Vector.empty,
      "a mis-keyed card was built: the heading path is half the card's identity",
    )
    assert(misfiled(note).exists(_.contains("Beta")), s"not named: ${note.failures}")
  }

  /** The control that shows what the key WOULD have been, so the test above is a claim about
    * identity rather than about card count.
    */
  test("swallowed heading CONTROL: with the blank line the card is filed under Beta") {
    val note = extract(
      """|# Alpha
         |
         |- an item
         |
         |# Beta
         |
         |## Gamma #flashcard/1way
         |
         |The answer.
         |""".stripMargin
    )
    assertEquals(paths(note), Vector("beta / gamma"))
  }

  /** THE BLAST RADIUS IS THE NOTE, and it has to be: every heading below the swallowed one is
    * re-parented, so this tool cannot enumerate which keys the note owns. Sheltering less would
    * let orphan inference read a correctly-keyed live card as deleted — and an orphan is tagged
    * and SUSPENDED.
    */
  test("swallowed heading: the failure shelters the WHOLE NOTE from orphan inference") {
    val note = extract("# Alpha\n\n- an item\n# Beta\n\n## Gamma #flashcard/1way\n\nThe answer.\n")
    assertEquals(
      note.failures.map(_.shelters),
      Vector(obsidiananki.plan.OrphanShelter.WholeNote(NoteId.fromFrontmatter("n1").toOption.get)),
    )
  }

  // ── THE MILDER CASE: REPORTED, AND THE NOTE'S CARDS ARE STILL WRITTEN ────────────
  //
  // A heading INDENTED inside a list item parses to the same tree shape as a swallowed one — an
  // `ast.Header` inside a `BulletListItem` — and CommonMark puts it inside the item as well. The
  // two readings agree about where it sits, so no card below it is filed differently and the
  // only thing lost is the card that heading would have made. Refusing the note over that would
  // cost the author every card in it to save one.

  test("an indented heading is reported, and the note's other cards are still built") {
    val note = extract(
      """|# Alpha
         |
         |- an item
         |  # indented under the item
         |- another item
         |
         |## Beta #flashcard/1way
         |
         |The answer.
         |""".stripMargin
    )
    assertEquals(paths(note), Vector("alpha / beta"))
    assert(
      unreadHeading(note).exists(_.contains("indented under the item")),
      s"the heading nobody will get a card for was passed over: ${note.failures}",
    )
  }

  // ── THE FALSE POSITIVES THIS MUST NOT HAVE ──────────────────────────────────────
  //
  // `docs/findings/PARSER-DISAGREEMENTS.md` rules that this family must MISS rather than
  // OVER-REPORT: "an author refused for no reason learns to distrust every refusal this tool
  // makes." Each case below either produces no unlifted heading at all or produces one both
  // parsers place identically, so there is nothing to report and nothing to refuse.

  test("no false positive: a heading directly after a PARAGRAPH is read correctly by both") {
    val note = extract(
      """|# Alpha
         |
         |prose with no blank line after it
         |## Beta #flashcard/1way
         |
         |The answer.
         |""".stripMargin
    )
    assertEquals(paths(note), Vector("alpha / beta"))
    assertEquals(note.failures, Vector.empty)
  }

  test("no false positive: a heading written inside a blockquote does not disturb the note") {
    val note = extract(
      """|# Alpha
         |
         |> # A quoted heading
         |> quoted body
         |
         |## Beta #flashcard/1way
         |
         |The answer.
         |""".stripMargin
    )
    assertEquals(paths(note), Vector("alpha / beta"))
    assertEquals(note.failures, Vector.empty)
  }

  test("no false positive: a `#` line inside a fenced code block is not a heading at all") {
    val note = extract(
      """|# Alpha
         |
         |- an item
         |
         |```markdown
         |- a list line
         |# not a heading
         |```
         |
         |## Beta #flashcard/1way
         |
         |The answer.
         |""".stripMargin
    )
    assertEquals(paths(note), Vector("alpha / beta"))
    assertEquals(note.failures, Vector.empty)
  }

  test("no false positive: a frontmatter-style tag line after a list is not a heading to Obsidian") {
    val note = extract(
      """|# Alpha
         |
         |- an item
         |#flashcard/sequence
         |
         |## Beta #flashcard/1way
         |
         |The answer.
         |""".stripMargin
    )
    assertEquals(paths(note), Vector("alpha / beta"))
    assertEquals(note.failures, Vector.empty)
  }
