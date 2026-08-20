package obsidiananki.content

/** Worked examples of the closed algebra, one per distinct body shape in `dummy-vault`.
  *
  * WHAT THESE VALUES PROVE, AND WHAT THEY DO NOT. They prove the type is INHABITED for each
  * fixture body shape — that a value of `Vector[Block]` exists with that shape and the compiler
  * accepts it. They do NOT prove the type is SUFFICIENT. No lowering exists in this slice, so
  * nothing here shows that a lowering would produce exactly these values, nor that nothing was
  * lost on the way in. Sufficiency is S8's burden and it is a different test.
  *
  * PROVENANCE: each shape was TRANSCRIBED FROM A DUMPED PARSE TREE, not read off the markdown.
  * The dump is at
  * `/private/tmp/claude-501/-Users-marc-DATA-PROG-SCALA-backend-interview-prep-srs-obsidian-anki/d40fdfc4-ab27-4a8e-9f4d-eb8acae6083e/scratchpad/probe/out.txt`,
  * produced by a scratchpad probe run through this project's own
  * `parser.ObsidianSyntax.markupParser`. Treat the dump as the artefact, NOT as a rerunnable
  * script: the probe source in that directory has since been overwritten with an unrelated
  * probe, so re-running it does not reproduce the file.
  *
  * Reading the markdown instead of the dump would have got at least one of these wrong: the
  * `Read-your-writes consistency` example LOOKS like a nested list in the source and is three
  * sibling lists in the tree.
  *
  * STRING PAYLOADS ARE ROLE-NAMING PLACEHOLDERS, NOT VAULT TEXT. These examples pin STRUCTURE.
  * Transcribing the vault's real sentences would add hundreds of characters that no assertion
  * reads, would drift silently against the fixture, and would invite the next reader to believe
  * the test pins characters. Structural values ARE real: `Deletion`'s `group` and `Code`'s
  * `language` are transcribed from the dump, because they are structure rather than prose.
  *
  * THIS FILE DOES NOT IMPORT LAIKA, deliberately. A test that parsed markdown here would be S8
  * arriving early, and it would pin a parse that `extract/golden/fixture-cards.txt` already
  * pins — a second, weaker copy of an existing guarantee.
  *
  * NO HELPERS. Not a flattener, not a shape-printer, not a shared pattern. `-Wunused:all` is on
  * and the project is at zero warnings, and a test-local flattener is precisely the thing a
  * later author lifts into main — which is the `.text` method `Content.scala` refuses to have.
  * Every assertion is a pattern match with a failing catch-all arm; the arm is also what keeps
  * the match exhaustive, and an inexhaustive match is a build error in this project.
  */
class ContentSuite extends munit.FunSuite:

  // ───────────────────────────────────────────────────────────────────────────────────────
  // Fixture shapes. Eleven, enumerated from the dump.
  // ───────────────────────────────────────────────────────────────────────────────────────

  /** `dummy-vault/Anatomy/Body-Shapes.md`
    * § `Bones of the hand, in two parts #flashcard/cloze`
    *
    * Dumped subtree:
    * {{{
    * Paragraph( Text, Highlighted(Text), Text, Highlighted(Text), Text )
    * Paragraph( Text )
    * BulletList( BulletListItem( Paragraph( Text, Highlighted(Text), Text ) ) x3 )
    * Paragraph( Text )
    * }}}
    *
    * WRITTEN FIRST ON PURPOSE. It is the only fixture shape that exercises `Block`, `Item`,
    * `Inline` and `Deletion` at once, and it is the exact shape the two drifting Laika walks
    * disagreed about: `Cloze.collectHighlights` and `Cloze.renderWithDeletions` walked these
    * same blocks with different notions of "container".
    */
  val multiBlockCloze: Vector[Block] =
    Vector(
      Block.Paragraph(
        Vector(
          Inline.Text("lead-in prose"),
          Inline.Deletion(None, Vector(Inline.Text("first deletion"))),
          Inline.Text("between the deletions"),
          Inline.Deletion(None, Vector(Inline.Text("second deletion"))),
          Inline.Text("tail of the sentence"),
        )
      ),
      Block.Paragraph(Vector(Inline.Text("prose introducing the list"))),
      Block.Bullets(
        Vector(
          Item(
            Vector(
              Block.Paragraph(
                Vector(
                  Inline.Text("item lead-in"),
                  Inline.Deletion(None, Vector(Inline.Text("third deletion"))),
                  Inline.Text("item tail"),
                )
              )
            )
          ),
          Item(
            Vector(
              Block.Paragraph(
                Vector(
                  Inline.Text("item lead-in"),
                  Inline.Deletion(None, Vector(Inline.Text("fourth deletion"))),
                  Inline.Text("item tail"),
                )
              )
            )
          ),
          Item(
            Vector(
              Block.Paragraph(
                Vector(
                  Inline.Text("item lead-in"),
                  Inline.Deletion(None, Vector(Inline.Text("fifth deletion"))),
                  Inline.Text("item tail"),
                )
              )
            )
          ),
        )
      ),
      Block.Paragraph(Vector(Inline.Text("closing prose"))),
    )

  /** `dummy-vault/Patterns/Nested/Deep/Quorums.md`
    * § `What does W + R > N buy you? #flashcard/1way`
    *
    * Dumped subtree: `Paragraph( Text )`. The simplest body the vault contains.
    */
  val plainParagraph: Vector[Block] =
    Vector(Block.Paragraph(Vector(Inline.Text("one run of prose"))))

  /** `dummy-vault/Anatomy/Body-Shapes.md`
    * § `Layers of the epidermis #flashcard/2way`
    *
    * Dumped subtree:
    * {{{
    * Paragraph( Text )
    * BulletList( BulletListItem( Paragraph( Text ) ) x4 )
    * }}}
    *
    * Every fixture `BulletListItem` holds a `Paragraph` and never bare spans — which is the
    * observed half of why `Item` carries blocks rather than inline content.
    */
  val paragraphThenFlatBulletList: Vector[Block] =
    Vector(
      Block.Paragraph(Vector(Inline.Text("prose introducing the list"))),
      Block.Bullets(
        Vector(
          Item(Vector(Block.Paragraph(Vector(Inline.Text("first item"))))),
          Item(Vector(Block.Paragraph(Vector(Inline.Text("second item"))))),
          Item(Vector(Block.Paragraph(Vector(Inline.Text("third item"))))),
          Item(Vector(Block.Paragraph(Vector(Inline.Text("fourth item"))))),
        )
      ),
    )

  /** `dummy-vault/Anatomy/Body-Shapes.md`
    * § `Running the test suite #flashcard/2way`
    *
    * Dumped subtree:
    * {{{
    * Paragraph( Text )
    * LiteralBlock
    * Paragraph( Text )
    * }}}
    *
    * A fence with NO language arrives as `LiteralBlock`, which holds a `String`. `language` is
    * `None` because Laika reports none, not because this file declines to carry it.
    */
  val unlanguagedFence: Vector[Block] =
    Vector(
      Block.Paragraph(Vector(Inline.Text("prose introducing the fence"))),
      Block.Code(None, "the fenced text, verbatim"),
      Block.Paragraph(Vector(Inline.Text("prose after the fence"))),
    )

  /** `dummy-vault/System-Design/Replication.md`
    * § `How does a follower catch up after a network partition? #flashcard/1way`
    *
    * Dumped subtree:
    * {{{
    * Paragraph( Text, Text, Text )
    * CodeBlock( Text )
    * Paragraph( Text, Literal, Text, Literal, Text, Text, Text )
    * }}}
    *
    * A fence WITH a language arrives as `CodeBlock`, whose children are spans — Laika's syntax
    * highlighting. `Block.Code` collapses those spans to a `String`; see the named loss in
    * `Content.scala`. `Some("sql")` is transcribed, not invented: the language is the one thing
    * Laika really reports here.
    *
    * The adjacent bare `Text` runs are wikilinks and ordinary prose side by side — see
    * `wikilinksAsPlainText`.
    */
  val languagedFence: Vector[Block] =
    Vector(
      Block.Paragraph(
        Vector(
          Inline.Text("prose before"),
          Inline.Text("a wikilink's display text"),
          Inline.Text("prose after"),
        )
      ),
      Block.Code(Some("sql"), "the fenced text, verbatim"),
      Block.Paragraph(
        Vector(
          Inline.Text("prose"),
          Inline.CodeSpan("an inline code span"),
          Inline.Text("more prose"),
          Inline.CodeSpan("another inline code span"),
          Inline.Text("prose"),
          Inline.Text("a wikilink's display text"),
          Inline.Text("prose to the end"),
        )
      ),
    )

  /** `dummy-vault/Anatomy/Body-Shapes.md`
    * § `Cranial bones and their sutures #flashcard/table`
    *
    * Dumped subtree:
    * {{{
    * Table
    *   TableHead( Row( Cell( Paragraph( Text ) ) x3 ) )
    *   TableBody( Row( Cell( Paragraph( Text ) ) x3 ) x2 )
    * }}}
    *
    * Every fixture cell holds exactly one `Paragraph`, so every fixture cell lowers to inline
    * content with nothing dropped. That is a fact about the FIXTURE. The type permits an empty
    * `Cell` because Laika can produce a zero-block one; the fixture has no ragged table, so no
    * value here witnesses it — see `inventedShapes`.
    */
  val tableOnly: Vector[Block] =
    Vector(
      Block.Table(
        header = Vector(
          Cell(Vector(Inline.Text("first column header"))),
          Cell(Vector(Inline.Text("second column header"))),
          Cell(Vector(Inline.Text("third column header"))),
        ),
        rows = Vector(
          Vector(
            Cell(Vector(Inline.Text("row one, concept cell"))),
            Cell(Vector(Inline.Text("row one, second cell"))),
            Cell(Vector(Inline.Text("row one, third cell"))),
          ),
          Vector(
            Cell(Vector(Inline.Text("row two, concept cell"))),
            Cell(Vector(Inline.Text("row two, second cell"))),
            Cell(Vector(Inline.Text("row two, third cell"))),
          ),
        ),
      )
    )

  /** `dummy-vault/Anatomy/Body-Shapes.md`
    * § `The three layers, blanked #flashcard/cloze`
    *
    * Dumped subtree: `Paragraph( Text, Highlighted, Text, Highlighted, Text, Highlighted, Text )`
    * — seven children, three of them unlabelled highlights.
    */
  val unlabelledDeletions: Vector[Block] =
    Vector(
      Block.Paragraph(
        Vector(
          Inline.Text("prose"),
          Inline.Deletion(None, Vector(Inline.Text("first deletion"))),
          Inline.Text("prose"),
          Inline.Deletion(None, Vector(Inline.Text("second deletion"))),
          Inline.Text("prose"),
          Inline.Deletion(None, Vector(Inline.Text("third deletion"))),
          Inline.Text("prose to the end"),
        )
      )
    )

  /** `dummy-vault/Anatomy/Body-Shapes.md`
    * § `Bones of the forearm #flashcard/cloze`
    *
    * Dumped subtree: `Paragraph( Text, Highlighted, Text, Highlighted, Text, Highlighted, Text,
    * Highlighted, Text )` — nine children, four labelled highlights forming two groups.
    *
    * THE ONLY `Some` WITNESS IN THE FIXTURE. The labels `1` and `2` are transcribed, not
    * invented, and the repetition — group 1 twice, group 2 twice — is the point: two highlights
    * sharing a label are one deletion group and blank together.
    */
  val labelledDeletions: Vector[Block] =
    Vector(
      Block.Paragraph(
        Vector(
          Inline.Text("prose"),
          Inline.Deletion(Some(1), Vector(Inline.Text("group one, first occurrence"))),
          Inline.Text("prose"),
          Inline.Deletion(Some(2), Vector(Inline.Text("group two, first occurrence"))),
          Inline.Text("prose"),
          Inline.Deletion(Some(1), Vector(Inline.Text("group one, second occurrence"))),
          Inline.Text("prose"),
          Inline.Deletion(Some(2), Vector(Inline.Text("group two, second occurrence"))),
          Inline.Text("prose to the end"),
        )
      )
    )

  /** `dummy-vault/System-Design/Linearizability.md`
    * § `Contrast with sequential consistency #flashcard/3way`
    *
    * Dumped subtree: `Paragraph( Text, Emphasized( Text ), Text )`.
    *
    * The fixture's ONLY inline emphasis inside a marked body.
    */
  val emphasisInProse: Vector[Block] =
    Vector(
      Block.Paragraph(
        Vector(
          Inline.Text("prose before"),
          Inline.Emphasis(Vector(Inline.Text("the emphasised words"))),
          Inline.Text("prose after"),
        )
      )
    )

  /** `dummy-vault/System-Design/Replication.md`
    * § `Why does synchronous replication trade availability for durability? #flashcard/1way`
    *
    * Dumped subtree: `Paragraph( Text x7 )`.
    *
    * WIKILINKS NEED NO CONSTRUCTOR. The section's source contains three wikilinks, one of them
    * aliased, yet the tree holds only bare `Text`: `parser.ObsidianSyntax.wikilinkParser` emits
    * `Text(displayText, Styles("wikilink"))` rather than a link node, so a wikilink arrives as
    * one more adjacent `Text` run. Seven of them here, alternating prose and link text.
    */
  val wikilinksAsPlainText: Vector[Block] =
    Vector(
      Block.Paragraph(
        Vector(
          Inline.Text("prose"),
          Inline.Text("a wikilink's display text"),
          Inline.Text("prose"),
          Inline.Text("a wikilink's display text"),
          Inline.Text("prose"),
          Inline.Text("an aliased wikilink's display text"),
          Inline.Text("prose to the end"),
        )
      )
    )

  /** `dummy-vault/System-Design/Replication.md`
    * § `Read-your-writes consistency #flashcard/2way`
    *
    * Dumped subtree:
    * {{{
    * Paragraph( Text, Text, Text )
    * BulletList( BulletListItem( Paragraph( Text ) ) )
    * BulletList( BulletListItem( Paragraph( Text ) ) x2 )
    * BulletList( BulletListItem( Paragraph( Text, Literal, Text ) ) )
    * }}}
    *
    * THREE SIBLING LISTS, NOT A NESTED ONE. DO NOT COMMENT IT AS NESTED. The note indents its
    * sub-items by TWO spaces; laika-core 1.3.2 nests only at four, because
    * `laika/internal/markdown/BlockParsers.scala` `mdBlock` prefixes the continuation parser
    * with `insignificantSpaces` (`anyOf(' ').max(3)`) followed by `not(itemStart)` — so a
    * two-space indent is consumed as insignificant and the line reads as a new top-level item
    * start, ending the current list. `dummy-vault` therefore contains NO nested list at all,
    * and this file contains no fixture-sourced witness of one.
    */
  val siblingBulletLists: Vector[Block] =
    Vector(
      Block.Paragraph(
        Vector(
          Inline.Text("prose"),
          Inline.Text("a wikilink's display text"),
          Inline.Text("prose to the end"),
        )
      ),
      Block.Bullets(Vector(Item(Vector(Block.Paragraph(Vector(Inline.Text("first list, sole item"))))))),
      Block.Bullets(
        Vector(
          Item(Vector(Block.Paragraph(Vector(Inline.Text("second list, first item"))))),
          Item(Vector(Block.Paragraph(Vector(Inline.Text("second list, second item"))))),
        )
      ),
      Block.Bullets(
        Vector(
          Item(
            Vector(
              Block.Paragraph(
                Vector(
                  Inline.Text("third list, sole item"),
                  Inline.CodeSpan("an inline code span"),
                  Inline.Text("tail of the item"),
                )
              )
            )
          )
        )
      ),
    )

  // ───────────────────────────────────────────────────────────────────────────────────────
  // WHAT THE ELEVEN LEAVE OUT, so nobody wonders what was missed.
  //
  // The remaining marked sections of `dummy-vault` are SHAPE-DUPLICATES of the eleven above —
  // same constructors, different prose:
  //
  //   • Bones.md § "Anatomy of a long bone" and § "Cells that remodel bone" use the same
  //     constructors as `unlabelledDeletions`, differing only in HOW MANY `Deletion`s the
  //     paragraph holds (four and three against this file's three).
  //   • Messaging.md § "Cost / benefit", and all three sections of Table-Edge-Cases.md, use the
  //     same constructors as `tableOnly`, differing only in row and column counts (one of them
  //     is a single-column table: header of one `Cell`, rows of one `Cell`).
  //   • Consistency.md's four marked sections, Coupling.md's five, Multi-Topic.md's four and
  //     Quorums.md's second section are all `plainParagraph`.
  //
  // Two things in `dummy-vault` reach NO body at all and so appear nowhere here: the H1 intro
  // paragraphs (they belong to no marked section — Body-Shapes.md's is where the fixture's only
  // `**bold**` sits) and the unmarked headings, which generate nothing by design.
  // ───────────────────────────────────────────────────────────────────────────────────────

  /** NO FIXTURE PROVENANCE — INVENTED; INHABITATION ONLY, NOT EVIDENCE ABOUT THE VAULT.
    *
    * Nothing in `dummy-vault` produces any of these shapes, and this value is not a claim that
    * anything does. It exists so that the five constructors and one emptiness case the fixture
    * cannot witness are at least exercised by a compiling value:
    *
    *   - `Block.Numbered` — zero fixture occurrences; three files in the real vault.
    *   - `Inline.Strong` inside a card body — zero fixture occurrences (the fixture's only `**`
    *     is in an unmarked H1 intro); 13 of 25 real-vault notes contain `**`.
    *   - an `Item` whose blocks contain a nested `Bullets` — `dummy-vault` has NO nested list.
    *   - `Inline.Deletion` containing something other than `Text` — the hazard that makes
    *     derived `==` unusable as the unlabelled-group key; see `Content.scala` section (D).
    *   - `Cell(Vector.empty)` — Laika pads a short row with a zero-block cell; the fixture has
    *     no ragged table.
    */
  val inventedShapes: Vector[Block] =
    Vector(
      Block.Numbered(
        Vector(
          Item(
            Vector(
              Block.Paragraph(Vector(Inline.Strong(Vector(Inline.Text("bold run"))))),
              Block.Bullets(Vector(Item(Vector(Block.Paragraph(Vector(Inline.Text("nested item"))))))),
            )
          )
        )
      ),
      Block.Paragraph(
        Vector(Inline.Deletion(None, Vector(Inline.Emphasis(Vector(Inline.Text("emphasised deletion"))))))
      ),
      Block.Table(
        header = Vector(Cell(Vector(Inline.Text("sole column header")))),
        rows = Vector(Vector(Cell(Vector.empty))),
      ),
    )

  // ───────────────────────────────────────────────────────────────────────────────────────
  // Assertions. One test per value; shape by pattern match; a failing catch-all arm.
  // ───────────────────────────────────────────────────────────────────────────────────────

  test("multiBlockCloze — paragraph with deletions, prose, a bullet list of deletions, prose") {
    multiBlockCloze match
      case Vector(
            Block.Paragraph(
              Vector(
                Inline.Text(_),
                Inline.Deletion(None, Vector(Inline.Text(_))),
                Inline.Text(_),
                Inline.Deletion(None, Vector(Inline.Text(_))),
                Inline.Text(_),
              )
            ),
            Block.Paragraph(Vector(Inline.Text(_))),
            Block.Bullets(
              Vector(
                Item(Vector(Block.Paragraph(Vector(Inline.Text(_), Inline.Deletion(None, Vector(Inline.Text(_))), Inline.Text(_))))),
                Item(Vector(Block.Paragraph(Vector(Inline.Text(_), Inline.Deletion(None, Vector(Inline.Text(_))), Inline.Text(_))))),
                Item(Vector(Block.Paragraph(Vector(Inline.Text(_), Inline.Deletion(None, Vector(Inline.Text(_))), Inline.Text(_))))),
              )
            ),
            Block.Paragraph(Vector(Inline.Text(_))),
          ) =>
        ()
      case other => fail(s"shape changed: $other")
  }

  test("plainParagraph — one paragraph of one text run") {
    plainParagraph match
      case Vector(Block.Paragraph(Vector(Inline.Text(_)))) => ()
      case other                                          => fail(s"shape changed: $other")
  }

  test("paragraphThenFlatBulletList — a paragraph, then four single-paragraph items") {
    paragraphThenFlatBulletList match
      case Vector(
            Block.Paragraph(Vector(Inline.Text(_))),
            Block.Bullets(
              Vector(
                Item(Vector(Block.Paragraph(Vector(Inline.Text(_))))),
                Item(Vector(Block.Paragraph(Vector(Inline.Text(_))))),
                Item(Vector(Block.Paragraph(Vector(Inline.Text(_))))),
                Item(Vector(Block.Paragraph(Vector(Inline.Text(_))))),
              )
            ),
          ) =>
        ()
      case other => fail(s"shape changed: $other")
  }

  test("unlanguagedFence — a fence with no language carries None") {
    unlanguagedFence match
      case Vector(
            Block.Paragraph(Vector(Inline.Text(_))),
            Block.Code(None, _),
            Block.Paragraph(Vector(Inline.Text(_))),
          ) =>
        ()
      case other => fail(s"shape changed: $other")
  }

  test("languagedFence — a fence with a language carries it, and code spans sit inline") {
    languagedFence match
      case Vector(
            Block.Paragraph(Vector(Inline.Text(_), Inline.Text(_), Inline.Text(_))),
            Block.Code(Some("sql"), _),
            Block.Paragraph(
              Vector(
                Inline.Text(_),
                Inline.CodeSpan(_),
                Inline.Text(_),
                Inline.CodeSpan(_),
                Inline.Text(_),
                Inline.Text(_),
                Inline.Text(_),
              )
            ),
          ) =>
        ()
      case other => fail(s"shape changed: $other")
  }

  test("tableOnly — a three-column header and two body rows, every cell inline-only") {
    tableOnly match
      case Vector(
            Block.Table(
              Vector(
                Cell(Vector(Inline.Text(_))),
                Cell(Vector(Inline.Text(_))),
                Cell(Vector(Inline.Text(_))),
              ),
              Vector(
                Vector(
                  Cell(Vector(Inline.Text(_))),
                  Cell(Vector(Inline.Text(_))),
                  Cell(Vector(Inline.Text(_))),
                ),
                Vector(
                  Cell(Vector(Inline.Text(_))),
                  Cell(Vector(Inline.Text(_))),
                  Cell(Vector(Inline.Text(_))),
                ),
              ),
            )
          ) =>
        ()
      case other => fail(s"shape changed: $other")
  }

  test("unlabelledDeletions — three deletions with no group") {
    unlabelledDeletions match
      case Vector(
            Block.Paragraph(
              Vector(
                Inline.Text(_),
                Inline.Deletion(None, Vector(Inline.Text(_))),
                Inline.Text(_),
                Inline.Deletion(None, Vector(Inline.Text(_))),
                Inline.Text(_),
                Inline.Deletion(None, Vector(Inline.Text(_))),
                Inline.Text(_),
              )
            )
          ) =>
        ()
      case other => fail(s"shape changed: $other")
  }

  test("labelledDeletions — four deletions carrying two repeated group labels") {
    labelledDeletions match
      case Vector(
            Block.Paragraph(
              Vector(
                Inline.Text(_),
                Inline.Deletion(Some(1), Vector(Inline.Text(_))),
                Inline.Text(_),
                Inline.Deletion(Some(2), Vector(Inline.Text(_))),
                Inline.Text(_),
                Inline.Deletion(Some(1), Vector(Inline.Text(_))),
                Inline.Text(_),
                Inline.Deletion(Some(2), Vector(Inline.Text(_))),
                Inline.Text(_),
              )
            )
          ) =>
        ()
      case other => fail(s"shape changed: $other")
  }

  test("emphasisInProse — emphasis nests inline content") {
    emphasisInProse match
      case Vector(
            Block.Paragraph(
              Vector(Inline.Text(_), Inline.Emphasis(Vector(Inline.Text(_))), Inline.Text(_))
            )
          ) =>
        ()
      case other => fail(s"shape changed: $other")
  }

  test("wikilinksAsPlainText — seven adjacent text runs, no link constructor") {
    wikilinksAsPlainText match
      case Vector(
            Block.Paragraph(
              Vector(
                Inline.Text(_),
                Inline.Text(_),
                Inline.Text(_),
                Inline.Text(_),
                Inline.Text(_),
                Inline.Text(_),
                Inline.Text(_),
              )
            )
          ) =>
        ()
      case other => fail(s"shape changed: $other")
  }

  test("siblingBulletLists — three lists side by side, not one nested list") {
    siblingBulletLists match
      case Vector(
            Block.Paragraph(Vector(Inline.Text(_), Inline.Text(_), Inline.Text(_))),
            Block.Bullets(Vector(Item(Vector(Block.Paragraph(Vector(Inline.Text(_))))))),
            Block.Bullets(
              Vector(
                Item(Vector(Block.Paragraph(Vector(Inline.Text(_))))),
                Item(Vector(Block.Paragraph(Vector(Inline.Text(_))))),
              )
            ),
            Block.Bullets(
              Vector(
                Item(
                  Vector(
                    Block.Paragraph(Vector(Inline.Text(_), Inline.CodeSpan(_), Inline.Text(_)))
                  )
                )
              )
            ),
          ) =>
        ()
      case other => fail(s"shape changed: $other")
  }

  test("inventedShapes — NO FIXTURE PROVENANCE: numbered, strong, nesting, deletion-of-emphasis, empty cell") {
    inventedShapes match
      case Vector(
            Block.Numbered(
              Vector(
                Item(
                  Vector(
                    Block.Paragraph(Vector(Inline.Strong(Vector(Inline.Text(_))))),
                    Block.Bullets(Vector(Item(Vector(Block.Paragraph(Vector(Inline.Text(_))))))),
                  )
                )
              )
            ),
            Block.Paragraph(
              Vector(Inline.Deletion(None, Vector(Inline.Emphasis(Vector(Inline.Text(_))))))
            ),
            Block.Table(
              Vector(Cell(Vector(Inline.Text(_)))),
              Vector(Vector(Cell(Vector()))),
            ),
          ) =>
        ()
      case other => fail(s"shape changed: $other")
  }
