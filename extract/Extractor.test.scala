package obsidiananki.extract

import obsidiananki.model.*
import obsidiananki.parser.ObsidianSyntax
import obsidiananki.plan.{BuildFailure, SourcedSpec}

class ExtractorTest extends munit.FunSuite:

  def extract(markdown: String, id: String = "n1", fileName: String = "Note"): ExtractedNote =
    val (_, body) = Frontmatter.read(markdown).fold(e => fail(s"frontmatter: $e"), identity)
    val root = ObsidianSyntax.markupParser.parse(body).fold(e => fail(s"parse: $e"), _.content)
    Extractor.fromDocument(
      NoteId.fromFrontmatter(id).toOption.get,
      fileName,
      s"$fileName.md",
      root,
      body,
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
      case CardSpec.ThreeField(_, concept, descriptor, _, _) =>
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
      case CardSpec.ThreeField(_, concept, _, _, _) => assertEquals(concept, "Consistency")
      case other                                    => fail(s"expected ThreeField, got $other")
  }

  test("the marker is stripped from the descriptor as well as from the key") {
    val note = extract("# A\n\nx\n\n## Cost #flashcard/3way/all\n\nExpensive.\n")
    specFor(note, "a / cost").spec match
      case CardSpec.ThreeField(_, _, descriptor, _, directions) =>
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
      case CardSpec.TwoField(_, _, back, _) =>
        assertEquals(back.value, "Only this sentence.")
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
      case CardSpec.TwoField(_, _, back, _) =>
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
      case CardSpec.ThreeField(_, concept, descriptor, description, _) =>
        assertEquals(concept, "Queue")
        assertEquals(descriptor, "Benefit")
        assertEquals(description.value, "Load Absorption")
      case other => fail(s"expected ThreeField, got $other")
  }

  test("a row card carries ALL of the row's descriptors together") {
    val note = extract(messagingTable)
    specFor(note, "messaging / cost / benefit / queue").spec match
      case CardSpec.TableRow(_, concept, descriptors) =>
        assertEquals(concept, "Queue")
        assertEquals(
          descriptors.toVector,
          Vector("Benefit" -> "Load Absorption", "Cost" -> "Delay & Duplication"),
        )
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
      case CardSpec.ThreeField(_, _, _, description, _) =>
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
