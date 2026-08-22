package obsidiananki.extract

import cats.data.NonEmptyVector
import laika.ast.{Cell, Section, Table}
import obsidiananki.model.*
import obsidiananki.parser.ObsidianSyntax
import obsidiananki.plan.SourceKind

/** The permanent guard on the severance of a table cell's IDENTITY projection from its
  * DISPLAY projection.
  *
  * ==What the severance is==
  *
  * Before this slice, one value per cell meant two things at once. `rowCells` collapsed every
  * `Cell` to a `String`, and `cardsForRow` bound that string ONCE and handed the same binding
  * to `HeadingSegment.fromExtractedText` (which becomes the `src::` identity tag stored in
  * Anki) and to the `CardSpec` display fields (which become the text on the card). Improving
  * how a cell LOOKS therefore changed what a card IS — and a card whose identity changes is
  * not an updated card, it is an ORPHAN plus a brand-new card with no review history.
  *
  * Now rows stay `Vector[Cell]` down to the point of use, and each cell is projected there
  * TWICE by two different things: `Tables.cellSegment` for identity (frozen) and the injected
  * [[CellDisplay]] for display (free to change). This file is the test that the two stay
  * apart.
  *
  * ==How the guard works — differential, not golden==
  *
  * The same `Section` is run through `Tables.fromSection` twice, once with
  * `CellDisplay.Default` and once with a deliberately hostile renderer that wraps every cell
  * in ASCII sentinels. The derived keys must be IDENTICAL (test A) while every rendered field
  * must visibly differ (test B). Test C proves the hostile renderer is actually potent
  * against the identity function, so the guard cannot be switched off by "simplifying" the
  * mangler.
  *
  * ==Honest limits — read these before trusting this file==
  *
  *   - `TagCodec.canonical` ALREADY insulates identity from whitespace, case and NFC/NFD
  *     change (`CardKey.scala:210-215`). The severance built here is against CONTENT change —
  *     the class `canonical` does not absorb and which nothing structural prevented. It is
  *     NOT true that identity was fragile to every rendering change.
  *   - The guard covers the PARAMETER ROUTE: identity consuming the injected renderer. It
  *     does NOT cover the SHARED-HELPER ROUTE. If a future author merges `Tables.cellSource`
  *     and `CellDisplay.Default` back onto one frozen helper and has `cellSegment` call it,
  *     the injected mangler still reaches display only — keys unchanged, fields changed, and
  *     this guard stays GREEN. Nothing in this slice closes that, because
  *     `HeadingSegment.fromExtractedText` remains public and `String`-typed in
  *     `model/CardKey.scala`, which is outside this slice's file list.
  *   - `GoldenTest` CANNOT detect re-coupling. S1's C1 control measured that provenance-only
  *     changes are invisible to it; an author who routes identity back through the display
  *     path produces byte-identical golden output and `GoldenTest` stays green. `GoldenTest`
  *     also does not compare emission order at all — it pairs records through a
  *     `Map[Slot, CardRecord]` (`Golden.test.scala:493-496`). The whole weight of "the
  *     severance holds" rests on tests A–E below. The sentence "the golden proves the paths
  *     are severed" is FALSE.
  *   - The acceptance criterion "the S1 golden file is BYTE-IDENTICAL" is trivially true:
  *     nothing in this repository writes `fixture-cards.txt`. The load-bearing claim is that
  *     `GoldenTest` still PASSES, unchanged.
  *   - This slice DOES change output, for exactly one input class — see test E.
  *
  * ==MEASURED, not predicted==
  *
  * Every row below was RUN on 2026-08-20 by mutating `extract/Tables.scala`, running the
  * WHOLE suite, reverting, and proving the restoration byte-identical against a scratchpad
  * copy. "rest of the suite" means every other test in the project, `GoldenTest` included.
  *
  * {{{
  * mutation of Tables.scala                                  this file        rest of suite
  * ------------------------------------------------------------------------------------------
  * identity re-coupled to display: the header segment        A FAILS          all green
  *   taken from `display.text(headerCell)` instead of
  *   `cellSegment(headerCell)`
  * emission order inverted: `pairCards ++ rowCard`           D FAILS          all green
  *   -> `rowCard ++ pairCards`                               (E's shape
  *                                                            assertion too)
  * pre-slice header semantics restored: a marker-only        E FAILS          all green
  *   header counted toward `pairs.size < 2` and printed
  *   as a row-card descriptor
  * }}}
  *
  * READ THE RIGHT-HAND COLUMN. Re-coupling identity to display — the exact defect this slice
  * exists to prevent — was invisible to all 312 tests that existed before this file, GOLDEN
  * INCLUDED. So was emission order. That is the measurement behind the "honest limits" above,
  * not an inference from them.
  *
  * ==Why this drives `Tables.fromSection` directly, and not the fixture vault==
  *
  * A DEVIATION FROM THE WRITTEN ACCEPTANCE, forced by the file list and recorded rather than
  * silently reinterpreted. Driving the differential over the whole fixture vault would need
  * `CellDisplay` threaded through `Extractor.fromDocument` and `VaultWalker.scan`, both
  * outside this slice. So: this guard asserts key-invariance over the table sections it
  * drives directly, and `GoldenTest` carries the fixture's breadth. Two nets, different
  * failure modes.
  *
  * Inline markdown is parsed by the REAL `ObsidianSyntax.markupParser`; no Laika AST is hand
  * built. S1 §9's ban targets synthetic ASTs that pin inputs Laika's GFM parser cannot
  * produce; it does not reach a markdown string Laika parses correctly, and
  * `Extractor.test.scala` already carries inline table markdown, so this is the established
  * idiom rather than a second fixture corpus.
  */
class TablesTest extends munit.FunSuite:

  // ================================================ harness ====

  /** Two rows, two descriptor columns — enough for pair cards AND row cards on both rows. */
  private val grid: String =
    """|# T
       |
       |Intro.
       |
       |## Grid #flashcard/table
       |
       || Pattern | Benefit         | Cost                |
       || ------- | --------------- | ------------------- |
       || Queue   | Load Absorption | Delay & Duplication |
       || Topic   | Fan-out         | Ordering            |
       |""".stripMargin

  /** Collected CONCRETELY and non-recursively — `case s: Section`, never a trait. Depth two is
    * all these fixtures need: a top-level `#` heading with the marked `##` beneath it.
    */
  private def sectionOf(markdown: String): Section =
    val root = ObsidianSyntax.markupParser.parse(markdown).fold(e => fail(s"parse: $e"), _.content)
    val sections = root.content
      .collect { case s: Section => s }
      .flatMap(s => s +: s.content.collect { case s2: Section => s2 })
    sections
      .find(_.header.extractText.contains("#flashcard/table"))
      .getOrElse(fail(s"no #flashcard/table section in:\n$markdown"))

  private def baseKey: CardKey =
    val id  = NoteId.fromFrontmatter("n1").fold(e => fail(s"note id: $e"), identity)
    val seg = HeadingSegment.fromExtractedText("T").fold(e => fail(s"heading segment: $e"), identity)
    CardKey(id, HeadingPath(NonEmptyVector.one(seg)))

  /** `contextTitles` IS PART OF THE HARNESS, NOT PART OF WHAT THESE TESTS MEASURE.
    *
    * `Tables.fromSection` holds no rule about which heading segments a card's breadcrumb
    * keeps — the caller computes that, and for a table the caller passes
    * `ancestorTitles :+ title`. What is passed here mirrors the fixture markdown above (`# T`
    * with `## Grid #flashcard/table` beneath it) so that the value is realistic rather than a
    * sentinel, but no assertion in this file reads it: these tests are about the identity /
    * display severance, and a breadcrumb is neither.
    */
  private def cardsOf(section: Section, display: CellDisplay): Vector[(CardSpec, RowSource)] =
    Tables
      .fromSection(baseKey, section, display, Vector("T", "Grid"))
      .fold(e => fail(s"fromSection: $e"), identity)

  /** The hostile display projection.
    *
    * DERIVED FROM THE PRODUCTION RENDERER rather than written out again, so it cannot drift
    * out of step with what production actually does. ASCII sentinels because failure output
    * must be readable in any terminal.
    *
    * It is UNCONDITIONAL and can never produce a blank string. That is not a coincidence to
    * be documented — it is the property this slice establishes: every existence gate in
    * `Tables` now reads `cellSource`, never the renderer, so a renderer can neither MINT nor
    * DESTROY a card. If this renderer ever changed the set of cards, the severance would be
    * broken, and test A would say so.
    */
  private val Hostile: CellDisplay =
    CellDisplay(cell => "<<" + CellDisplay.Default.text(cell) + ">>")

  private def tableOf(section: Section): Table =
    section.content.collectFirst { case t: Table => t }.getOrElse(fail("no table in the section"))

  /** Every cell whose text feeds a key: the descriptor column headers, and each row concept. */
  private def identityCells(section: Section): Vector[Cell] =
    val table       = tableOf(section)
    val headerCells = table.head.content.toVector.flatMap(_.content.toVector).drop(1)
    val concepts    = table.body.content.toVector.flatMap(_.content.toVector.headOption)
    headerCells ++ concepts

  private def tags(cards: Vector[(CardSpec, RowSource)]): Vector[String] =
    cards.map((spec, _) => TagCodec.encode(spec.key).value)

  private def fieldValue(spec: CardSpec, name: String): String =
    spec.fields
      .collectFirst { case (n, v) if n == name => v }
      .getOrElse(fail(s"note type ${spec.noteTypeName} has no field '$name' — has ${spec.fields}"))

  // ================================================ A: identity is invariant ====

  /** Encoded tags rather than `CardKey` values, as an ORDERED `Vector` with multiplicity —
    * never a `Set`, `groupBy`, `Map` keyed on the key, or `.sorted`. The tag is what lands in
    * Anki, it is what an orphan IS, it is the vocabulary the golden already speaks, and a
    * failure prints something pasteable into Anki's browser. Multiplicity matters because
    * duplicate identities are a real fixture case that must not be collapsed away.
    */
  test("a display change does not move a single card identity") {
    val section = sectionOf(grid)
    val default = cardsOf(section, CellDisplay.Default)
    val hostile = cardsOf(section, Hostile)

    assert(default.nonEmpty, "vacuous: the fixture produced no cards at all")
    assertEquals(
      tags(hostile),
      tags(default),
      "A DISPLAY CHANGE MOVED A CARD IDENTITY. Every tag on the left is a card that would " +
        "be created fresh in Anki with no review history, and every tag on the right that is " +
        "missing from the left becomes an ORPHAN — and orphan suspension is ruled but NOT " +
        "BUILT, so it stays in the daily review rotation with only a tag to show for it. " +
        "Identity must not read the renderer.",
    )
  }

  // ================================================ B: the change reached display ====

  /** THE VACUITY GUARD, and it is not optional: without it test A passes just as well when
    * the hostile renderer reaches nothing at all.
    *
    * `CardSpec.TableRow`'s back field matters most. Its `s"$d: $v"` join lives in
    * `CardSpec.fields`'s `TableRow` arm, NOT in `Tables.scala` — so it is exactly the
    * surface a botched split leaves untouched while everything else looks right.
    */
  test("the display change actually reached every rendered field") {
    val hostile = cardsOf(sectionOf(grid), Hostile)
    assert(hostile.nonEmpty, "vacuous: the fixture produced no cards at all")

    val why =
      "THIS ASSERTION IS THE VACUITY GUARD FOR THE TEST ABOVE. Test A only means something " +
        "if the hostile renderer demonstrably reached the rendered fields. If this fails, " +
        "the field named below is no longer produced by the injected CellDisplay — fix the " +
        "renderer wiring, do NOT delete this assertion."

    var sawRow = false
    hostile.foreach { (spec, _) =>
      spec match
        case CardSpec.ThreeField(_, concept, descriptor, description, _, _, _) =>
          assert(concept.contains("<<"), s"$why — ThreeField concept was [$concept]")
          assert(descriptor.contains("<<"), s"$why — ThreeField descriptor was [$descriptor]")
          assert(description.value.contains("<<"), s"$why — ThreeField description was [$description]")

        case CardSpec.TableRow(_, _, _, _) =>
          sawRow = true
          val front = fieldValue(spec, Marker.BasicFields.Front)
          val back  = fieldValue(spec, Marker.BasicFields.Back)
          assert(front.contains("<<"), s"$why — TableRow Front was [$front]")
          assert(back.contains("<<"), s"$why — TableRow Back was [$back]")

        case other => fail(s"a table produced a spec that is neither a pair nor a row card: $other")
    }
    assert(sawRow, "vacuous: no TableRow card was produced, so its joined back was never checked")
  }

  // ================================================ C: the mangler is potent ====

  /** WITHOUT THIS TEST THE GUARD HAS A SILENT OFF SWITCH THAT READS AS A TIDY-UP.
    *
    * Someone "simplifying" [[Hostile]] to `_.toUpperCase` keeps test B green — the fields
    * really do differ — while `TagCodec.canonical` (`CardKey.scala:210-215`: NFC, trim,
    * collapse whitespace, `toLowerCase(ROOT)`) absorbs the change entirely. Test A would then
    * pass with identity FULLY RE-COUPLED to display.
    *
    * Asserted against `HeadingSegment.fromExtractedText`, not `TagCodec.canonical`, because
    * that is the function identity actually calls — and it includes the marker strip
    * (`CardKey.scala:47,59`) that would eat a `#flashcard`-shaped sentinel.
    */
  test("the hostile renderer is genuinely hostile to the identity function") {
    val cells = identityCells(sectionOf(grid))
    assert(cells.nonEmpty, "vacuous: no identity-bearing cells were found in the fixture")

    cells.foreach { cell =>
      val plain   = HeadingSegment.fromExtractedText(CellDisplay.Default.text(cell))
      val mangled = HeadingSegment.fromExtractedText(Hostile.text(cell))
      assertNotEquals(
        mangled,
        plain,
        "THE HOSTILE RENDERER IS NO LONGER HOSTILE. HeadingSegment.fromExtractedText maps " +
          s"both projections of the cell [${CellDisplay.Default.text(cell)}] to the same " +
          "value, so tests A and B would both pass even with identity fully re-coupled to " +
          "display. Restore a mangler that survives TagCodec.canonical and the marker strip.",
      )
    }
  }

  // ================================================ D: emission order ====

  /** EMISSION ORDER IS PINNED BY NOTHING ELSE IN THE REPOSITORY. Verified by reading:
    * `GoldenTest` pairs its records through a `Map[Slot, CardRecord]`
    * (`Golden.test.scala:493-496`) and never compares position, and every table assertion in
    * `ExtractorTest` uses `.contains`, `.toSet` or `.sorted` — so swapping
    * `pairCards ++ rowCard` for `rowCard ++ pairCards` was green across the whole suite
    * before this test existed.
    *
    * It is worth pinning because emission order IS `Planner.plan` action order IS Anki
    * note-creation order IS new-card introduction position, and this slice restructures
    * exactly the loop that decides it.
    */
  test("table cards are emitted in row order, pair cards before the row card") {
    val emitted = cardsOf(sectionOf(grid), CellDisplay.Default).map { (spec, src) =>
      (spec.key.path.render, src.kind, src.rowNumber)
    }
    assertEquals(
      emitted,
      Vector(
        ("t / queue / benefit", SourceKind.TablePair, Some(1)),
        ("t / queue / cost", SourceKind.TablePair, Some(1)),
        ("t / queue", SourceKind.TableRow, Some(1)),
        ("t / topic / benefit", SourceKind.TablePair, Some(2)),
        ("t / topic / cost", SourceKind.TablePair, Some(2)),
        ("t / topic", SourceKind.TableRow, Some(2)),
      ),
    )
  }

  // ================================================ E: the ruled behaviour change ====

  /** THE ONE INPUT CLASS WHOSE OUTPUT THIS SLICE CHANGES, and `dummy-vault` cannot reach it.
    *
    * A header cell whose extracted text is NON-EMPTY but canonicalises to EMPTY after marker
    * stripping — in practice a cell containing only `#flashcard…`. Before this slice such a
    * column was counted in `pairs`, so it inflated the `pairs.size < 2` row-card threshold
    * AND was printed on a real card as a descriptor with the literal text `#flashcard`, while
    * producing no pair card of its own (its `HeadingSegment` was a `Left`). That is
    * incoherent. Under the header-gates-on-identity ruling it is excluded outright.
    *
    * The table below is built so all three consequences are observable at once: row 1 keeps
    * one real descriptor and so loses its row card, row 2 keeps two and so still has one,
    * whose descriptor list must not mention the marker column.
    */
  test("a header cell that canonicalises to empty contributes no descriptor") {
    val cards = cardsOf(
      sectionOf(
        """|# T
           |
           |Intro.
           |
           |## Marked #flashcard/table
           |
           || Pattern | Benefit | #flashcard | Cost     |
           || ------- | ------- | ---------- | -------- |
           || Queue   | Absorbs | ignored    |          |
           || Topic   | Fan-out | ignored    | Ordering |
           |""".stripMargin
      ),
      CellDisplay.Default,
    )

    val shape = cards.map((spec, src) => (spec.key.path.render, src.kind))
    assertEquals(
      shape,
      Vector(
        ("t / queue / benefit", SourceKind.TablePair),
        ("t / topic / benefit", SourceKind.TablePair),
        ("t / topic / cost", SourceKind.TablePair),
        ("t / topic", SourceKind.TableRow),
      ),
      "the `#flashcard`-only header column must yield no pair card, and row 1 must lose its " +
        "row card because only ONE real descriptor survives",
    )

    val rowCard = cards.collectFirst { case (s: CardSpec.TableRow, _) => s }
      .getOrElse(fail(s"expected a row card for row 2 — got $shape"))
    assertEquals(
      rowCard.descriptors.toVector,
      Vector("Benefit" -> "Fan-out", "Cost" -> "Ordering"),
      "the marker-only column must be absent from the row card's descriptors, not printed " +
        "on the card as a descriptor literally named '#flashcard'",
    )
  }

  /** The concept-cell sibling of the ruling above. BEHAVIOUR-IDENTICAL to before the slice —
    * traced, not assumed: previously a non-empty but unkeyable concept passed the
    * `.filter(_.nonEmpty)` gate and then dropped every pair card (each `for` began
    * `rowSeg <- …toOption` = `None`) and the row card (same `.toOption`), yielding
    * `Vector.empty` too. It is pinned here because the gate MOVED and nothing else covers it.
    */
  test("a concept cell that canonicalises to empty names nothing at all") {
    val cards = cardsOf(
      sectionOf(
        """|# T
           |
           |Intro.
           |
           |## Anon #flashcard/table
           |
           || Pattern    | Benefit | Cost  |
           || ---------- | ------- | ----- |
           || #flashcard | Absorbs | Delay |
           |""".stripMargin
      ),
      CellDisplay.Default,
    )
    assertEquals(cards, Vector.empty, "a row whose concept has no derivable segment names nothing")
  }
