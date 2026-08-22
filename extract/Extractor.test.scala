package obsidiananki.extract

import obsidiananki.model.*
import obsidiananki.parser.ObsidianSyntax
import obsidiananki.plan.{BuildFailure, SourcedSpec}

class ExtractorTest extends munit.FunSuite:

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

  test("a row card carries ALL of the row's descriptors together") {
    val note = extract(messagingTable)
    specFor(note, "messaging / cost / benefit / queue").spec match
      case CardSpec.TableRow(_, concept, descriptors, _) =>
        assertEquals(concept, "Queue")
        // `&amp;` arrived in S11: production injects `CellDisplay.Escaped`, so a cell's text
        // is escaped for an HTML field. This is the one place the fixture markdown in THIS
        // file carries a character that escaping moves — `dummy-vault` carries none in any
        // table cell, which is why the golden does not witness it.
        assertEquals(
          descriptors.toVector,
          Vector("Benefit" -> "Load Absorption", "Cost" -> "Delay &amp; Duplication"),
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

  // ================================================ cloze ====

  def clozeOf(n: ExtractedNote, path: String): CardSpec.Cloze =
    specFor(n, path).spec match
      case c: CardSpec.Cloze => c
      case other             => fail(s"expected a Cloze spec, got $other")

  test("one cloze SECTION becomes one note holding all its deletions") {
    val note = extract(
      "# Bones\n\nx\n\n## Long bone #flashcard/cloze\n\nThe ==diaphysis== and the ==epiphysis==.\n"
    )
    assertEquals(note.specs.size, 1, "a cloze section must yield ONE note, not one per highlight")
    assertEquals(clozeOf(note, "bones / long bone").deletions.length, 2)
  }

  /** UNLABELLED: its own group of one, numbered in order of first appearance. */
  test("unlabelled highlights each form their own group") {
    val note = extract("# B\n\nx\n\n## L #flashcard/cloze\n\nThe ==a== and the ==b==.\n")
    val ds   = clozeOf(note, "b / l").deletions.toVector
    assertEquals(ds.map(_.ordinal), Vector(1, 2))
    assertEquals(ds.map(_.group), Vector(ClozeGroup.Unlabelled("a"), ClozeGroup.Unlabelled("b")))
  }

  /** LABELLED: several highlights sharing a label are ONE group and blank together. */
  test("highlights sharing a label form ONE group with one card") {
    val note = extract(
      "# B\n\nx\n\n## L #flashcard/cloze\n\n==1|alpha== then ==2|beta== then ==1|gamma==.\n"
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
    assertEquals(groupsOf("The ==1|Mercurey== orbits."), groupsOf("The ==1|Mercury== orbits."))
    // Unlabelled: the same fix retires the key. Accepted, and visible.
    assertNotEquals(groupsOf("The ==Mercurey== orbits."), groupsOf("The ==Mercury== orbits."))
  }

  test("an unlabelled group never takes a number a label has claimed") {
    val note = extract("# B\n\nx\n\n## L #flashcard/cloze\n\n==1|a== and ==b== and ==2|c==.\n")
    val ds   = clozeOf(note, "b / l").deletions.toVector
    assertEquals(ds.map(_.ordinal).sorted, Vector(1, 2, 3), s"numbers collided: ${ds.map(_.ordinal)}")
  }

  /** Separate groups by rule, identical text, and nothing but POSITION to tell them apart.
    * Refused with the remedy named, rather than tie-broken positionally.
    */
  test("two IDENTICAL unlabelled highlights are refused, with the remedy named") {
    val note = extract(
      "# B\n\nx\n\n## L #flashcard/cloze\n\nA ==quorum== is a majority. Any two ==quorum== sets meet.\n"
    )
    assertEquals(note.specs, Vector.empty)
    val reason = note.failures.collectFirst { case BuildFailure.KeyKnown(_, _, r) => r }
    assert(reason.exists(_.contains("label them")), s"remedy not named: $reason")
  }

  test("identical text is fine when the duplicates are LABELLED into one group") {
    val note = extract(
      "# B\n\nx\n\n## L #flashcard/cloze\n\nA ==1|quorum== is a majority. Two ==1|quorum== sets meet.\n"
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
      "# B\n\nx\n\n## L #flashcard/cloze\n\nWrite `==x==` to mark one. The ==real== one counts.\n"
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
    val note = extract("# B\n\nx\n\n## L #flashcard/cloze\n\nThe ==femur== is a bone.\n")
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
      "# B\n\nx\n\n## C #flashcard/cloze\n\n| A | B |\n| - | - |\n| ==one== | two |\n"
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
      "# B\n\nx\n\n## L #flashcard/cloze\n\nThe three layers:\n\n- the ==epidermis==\n- the ==dermis==\n- the ==hypodermis==\n"
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
      "# B\n\nx\n\n## L #flashcard/cloze\n\n1. first the ==ureter==\n2. then the ==bladder==\n"
    )
    val rendered = clozeOf(note, "b / l").text.value
    assert(rendered.contains("{{c1::ureter}}") && rendered.contains("{{c2::bladder}}"), rendered)
  }

  /** A label IS the cloze number, so two highlights sharing one blank together as one card. */
  test("a labelled group renders its own number, and a shared label renders the same number") {
    val note = extract(
      "# B\n\nx\n\n## L #flashcard/cloze\n\nA ==2|quorum== is a majority; two ==2|quorum== sets meet.\n"
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
    val note = extract("# B\n\nx\n\n## L #flashcard/cloze\n\nThe ==1|shaft== and each ==end==.\n")
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
      "# B\n\nx\n\n## L #flashcard/cloze\n\nWrite `==x==` to mark one. The ==real== one counts.\n"
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
      "# B\n\nx\n\n## L #flashcard/cloze\n\nA ==quorum== is a majority; two ==quorum== sets meet.\n"
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
    assert(reason.contains("no ==highlight=="), s"the refusal does not say what is missing: $reason")
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
    assertEquals(back, "<p>alpha\nbeta</p><p>gamma</p><ul><li>one</li><li>two</li></ul>")
    assert(
      !back.contains("<br"),
      s"a `<br>` here would put the author's 80-column wrap on the card as a line break: [$back]",
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
        "Context" -> "B",
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
