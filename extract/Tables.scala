package obsidiananki.extract

import laika.ast.*
import obsidiananki.content as C
import obsidiananki.model.*
import obsidiananki.plan.SourceKind

/** Decomposing a `#flashcard/table` section into cards.
  *
  * A table row is a set of concept–descriptor–description triples written compactly, so it
  * needs no separate card model: each pair card IS a three-field spec. What the table adds
  * is the ROW CARD — the concept with all its descriptors together — because a benefit
  * divorced from its cost is trivia, and the contrast is the point.
  *
  * KEYS EXTEND THE HEADING PATH rather than forming a parallel shape:
  *   pair card  …/{row concept}/{column header}
  *   row card   …/{row concept}
  * The row card genuinely has one fewer coordinate, so this is a real structural difference
  * and not a sentinel — and it means table keys reuse the encoder, decoder and uniqueness
  * gate unchanged instead of needing a second mechanism.
  */
/** Which table row a card came from, so a collision between two identical row concepts can
  * say WHICH rows collided rather than reporting the same position twice.
  */
final case class RowSource(kind: SourceKind, rowNumber: Option[Int]):
  def detail: Option[String] = rowNumber.map(n => s"row $n")

object RowSource:
  val heading: RowSource = RowSource(SourceKind.Heading, None)
  def table(kind: SourceKind, row: Int): RowSource = RowSource(kind, Some(row))

/** How a table cell is turned into text a person READS on a card.
  *
  * INJECTED, not called directly, and that is the whole point of this type. Before it
  * existed one function produced both a cell's identity segment and its displayed text, so
  * improving how a cell LOOKS silently changed what a card IS — an orphan plus a brand-new
  * card with no review history. Identity now goes through [[Tables.cellSegment]], which is
  * frozen; display goes through here, which is free to change.
  *
  * A `CellDisplay` is readable by NOTHING that decides whether a card exists. Every existence
  * gate in [[Tables]] reads [[Tables.cellSource]] instead, so a renderer can neither mint nor
  * destroy a card. That is a property, not a coincidence — `Tables.test.scala` states it at
  * its hostile renderer and test A would report a violation.
  *
  * THIS REMOVES THE ACCIDENTAL PATH. It does not make re-coupling impossible: a future author
  * can still type `HeadingSegment.fromExtractedText(display.text(cell))`, because
  * `fromExtractedText` accepts a bare `String` and lives in `model/CardKey.scala`. Only the
  * differential guard in `Tables.test.scala` would notice.
  */
/** TWO PROJECTIONS, NOT ONE, and the second exists because escaping cannot be applied twice.
  *
  * `text` is a cell rendered READY TO BE A FIELD — escaped, for production. `raw` is the same
  * cell UNESCAPED, for the one consumer that escapes for itself: the row card's table is
  * assembled inside `content/`, where the only way to make an `Html.Fragment` from a String is
  * to escape it. Handing that an already-escaped value puts `&amp;amp;` on a card.
  *
  * BOTH ARE INJECTED TOGETHER so that a test's hostile renderer reaches EVERY rendered field.
  * When `raw` was not part of this record the row card silently bypassed the injection, and the
  * vacuity guard in `Tables.test.scala` caught it — which is what that guard is for.
  */
final case class CellDisplay(text: Cell => String, raw: Cell => String)

object CellDisplay:
  /** Today's rendering — a SECOND, VERBATIM COPY of [[Tables.cellSource]]'s body.
    *
    * THE DUPLICATION IS DELIBERATE AND IS THE POINT OF THE SPLIT. It will read as a DRY
    * violation, so name what it is: this is the INVERSE of the
    * `Cloze.collectHighlights` vs `renderWithDeletions` scar. There, two walks HAD TO AGREE
    * and drifted apart. Here, the two projections MUST BE FREE TO DIVERGE — display is about
    * to change and identity must never move — and re-converging them onto one helper is the
    * bug, not the fix.
    *
    * Note the honest limit: re-merging these two bodies leaves keys unchanged and fields
    * changed, so the guard in `Tables.test.scala` would stay GREEN. Nothing catches that; it
    * is stated there too.
    */
  val Default: CellDisplay =
    val plain: Cell => String =
      cell => cell.content.collect { case sc: SpanContainer => sc.extractText }.mkString(" ").trim
    // Unescaped BY DEFINITION, so both projections are the same function here.
    CellDisplay(text = plain, raw = plain)

  /** WHAT PRODUCTION INJECTS SINCE S11 — [[Default]]'s text, escaped for an HTML field.
    *
    * ADDED BESIDE `Default` RATHER THAN REPLACING IT, and the choice is load-bearing in two
    * directions. `content/AsText.test.scala` compares `AsText.cellDisplay` against
    * `CellDisplay.Default` over the whole fixture; mutating `Default` would leave that sweep
    * green while it silently stopped comparing what its name says it compares, because no
    * fixture cell contains any of the six escaped characters. And `Escaped` is DEFINED IN
    * TERMS OF `Default`, so `Default` is still on the production path as the inner factor and
    * that sweep still pins it — this is a decomposition, not a shadow path.
    *
    * TOTAL, AND WITH NO FAILURE CHANNEL. `Html.escape` is a total `String => String`;
    * escaping needs no lowering, so the partiality of `content.Lower.cell` is irrelevant here.
    * That matters because the repo carried the opposite claim as a reason (see the ledger at
    * the foot of `Extractor.scala`, and `content/Lower.scala:128-131`, which is not this
    * slice's to correct): a future author must not "unblock" richer cell rendering by adding a
    * failure channel nobody needed.
    *
    * ESCAPE-ONLY, AND THAT IS NOT FINISHED WORK. A cell's bold, inline code and any block
    * shape inside it still flatten exactly as they did before S11. Rendering a cell's
    * STRUCTURE is a later slice, and it needs `extract/Tables.test.scala` in its file list,
    * because both honest routes to it cross that file.
    *
    * UNSPLIT, HALF. A cell is the SECOND member of the family enumerated at
    * `Extractor.headingFace` — one construct, two readings — and the only one where the split
    * was actually made: `Tables.cellSource` stayed on identity, this stayed on display. What is
    * unfinished is that the display half never gained a RENDERER, only an escaper, so it can
    * say what a cell's characters are but not what its markup means. Maths in a cell shows the
    * author's dollars for that reason, and will keep doing so after headings are fixed.
    */
  val Escaped: CellDisplay =
    CellDisplay(
      text = cell => C.Html.escape(Default.text(cell)).render,
      // `Escaped.text` IS `escape ∘ raw`, so a consumer that escapes for itself gets the same
      // bytes by taking `raw` — which is what makes the two projections one decomposition
      // rather than two renderers that could drift.
      raw = Default.text,
    )

object Tables:

  /** A table card's key: the section's own key, EXTENDED by the row and — for a pair card — the
    * column header.
    *
    * WHY IT MUST BE A HEADING PATH, and why the other cases raise rather than being handled.
    * `base` is the key of the SECTION whose body holds the table, and a section is a marked
    * heading; there is no other way to reach this code. A property carries a single value and
    * has no body to put a table in, and the note-itself card is the note's prose. So the two
    * remaining cases are unreachable rather than merely unusual, and inventing an answer for
    * them would be inventing a key — which is the one thing in this system that must never be
    * guessed at, because a wrong key silently updates the wrong Anki note.
    *
    * IT RAISES INSTEAD OF RETURNING AN OPTION on the same grounds `Executor` raises for a
    * retype verdict it was told could not occur: a `None` here would be swallowed by the
    * surrounding `map` and the table would produce fewer cards than it has rows, silently.
    */
  private def deeper(base: CardKey, extra: HeadingSegment*): CardKey =
    base.path match
      case CardPath.Headings(headings) =>
        base.copy(path = CardPath.Headings(HeadingPath(headings.segments ++ extra.toVector)))
      case other =>
        throw new IllegalStateException(
          s"a table was found under ${other.render}, which has no body that could contain one"
        )


  /** Build every card a table section declares.
    *
    * Note this requires GitHubFlavor to be enabled on the parser. Laika's base Markdown has
    * no table support at all: the whole table would arrive as one paragraph of literal pipe
    * characters, and this would find nothing.
    *
    * `display` IS AN EXPLICIT ARGUMENT WITH NO DEFAULT AND NO DELEGATING OVERLOAD. A default
    * or a two-argument wrapper would be exactly as invisible as each other: production would
    * drive one arity while the guard drove the other, and a later inlining would leave the
    * guard green against a function production no longer calls — the shadow-path shape this
    * project already has a scar from.
    *
    * ONE CALL SITE, injecting [[CellDisplay.Escaped]]. _Corrected 2026-08-23._ This paragraph
    * described two call sites of which only one ran, guarded by an `if marker == Marker.Table`
    * in `Extractor.buildSpecs`. That comparison had been permanently FALSE since `Marker.Table`
    * gained parameters, so the guarded branch was the dead one and the `marker match` arm this
    * paragraph called unexercised was in fact the only live path. The guard is deleted.
    *
    * `contextTitles` IS FULLY COMPUTED BY THE CALLER, AND THIS FILE HOLDS NO RULE ABOUT IT.
    * Which heading segments a card's breadcrumb keeps depends on what that card's face already
    * shows, and the six shapes differ; `Extractor.buildSpecs` is the one place all six are
    * visible together, so the decision lives there. What this file knows is only that a table
    * card's face shows a row cell and a column header, never the marked heading, so it keeps
    * every segment it is handed — the caller passes `ancestorTitles :+ title`.
    *
    * IT IS UNESCAPED HERE AND ESCAPED IN THE ARGUMENT POSITION, exactly as `display` escapes
    * cell text: escaping upstream of the identity/display fork moves card KEYS. It also takes
    * NO DEFAULT AND HAS NO DELEGATING OVERLOAD, for the reason already written above about
    * `display` — a default would let production and the guard drive different arities.
    */
  def fromSection(
      key: CardKey,
      section: Section,
      display: CellDisplay,
      contextTitles: Vector[String],
      directions: ThreeFieldDirections,
      scope: TableScope,
  ): Either[SpecError, Vector[(CardSpec, RowSource)]] =
    val where   = key.path.render
    // ALREADY COMPOSED BY THE CALLER, which is the one that knows which fields this card
    // carries — `Extractor.buildSpecs` hands over the location with nothing excluded, because a
    // table card's fields come from CELLS rather than from where the note sits.
    val context = CardContext.compose(contextTitles, Vector.empty)
    firstTable(section).toRight(SpecError.TableWithoutTable(where)).flatMap { table =>
      val headerRow = rowCells(table.head.content).headOption.getOrElse(Vector.empty)
      val bodyRows  = rowCells(table.body.content)

      // The first column names the concept; the remaining headers are the descriptors. Each is
      // paired HERE, ONCE, with the key segment its header yields, so the emptiness gate below
      // and the pair-card construction in `cardsForRow` read the same decision instead of
      // deriving it twice and risking two answers.
      val descriptorColumns =
        headerRow.drop(1).map(cell => DescriptorColumn(cell, cellSegment(cell).toOption))

      // AND THE FIRST HEADER IS KEPT, where it used to be read for its position and thrown
      // away. It names what the rows ARE — `Bone` over `Frontal`, `Parietal` — so without it a
      // card asks "anterior border: orbital rim" and expects "Frontal" while never saying it
      // wants the name of a bone. DISPLAY text, not a key segment: it is shown and never keyed,
      // so it cannot move a card's identity however it is written.
      val conceptLabel = headerRow.headOption.map(display.text).getOrElse("")
      // THE SAME HEADER, UNESCAPED, for the row card's table. Passing the escaped one into
      // `Html.rowTable` would escape it a second time and put `&amp;amp;` on a card — the
      // failure the `Fragment` type prevents INSIDE `content/` and cannot prevent at a caller
      // that hands it something already escaped.
      val conceptLabelRaw = headerRow.headOption.map(display.raw).getOrElse("")

      // NO USABLE DESCRIPTOR COLUMN, AND THE TWO WAYS THAT HAPPENS ARE ONE REFUSAL. The gate
      // is "at least one column whose header can name a card", not "at least one column": a
      // header that canonicalises to empty is a key segment that does not exist, so its column
      // can never produce a pair card, and with no pairs surviving no row card either. Both
      // shapes are therefore a table that CANNOT produce a card, whatever its rows say, and
      // both are known before a single row is read.
      if descriptorColumns.forall(_.segment.isEmpty) then
        val what =
          if descriptorColumns.isEmpty then "it has a concept column but no descriptor columns"
          else if descriptorColumns.sizeIs == 1 then
            "its one descriptor column has a header that is blank, or holds nothing but a " +
              "marker, so it cannot name a card — give the column a heading"
          else
            s"all ${descriptorColumns.size} of its descriptor columns have headers that are " +
              "blank, or hold nothing but a marker, so none can name a card — give them headings"
        Left(SpecError.TableWithoutDescriptors(where, what))
      else
        val cards = bodyRows.zipWithIndex.flatMap((row, i) =>
          cardsForRow(key, descriptorColumns, row, i + 1, display, context, conceptLabel, conceptLabelRaw, directions, scope)
        )

        // ASKED FOR ROW CARDS AND GOT NONE. Reported rather than returned empty: an explicit
        // marker that yields zero cards is the dual of silent card creation, and the author
        // would otherwise see a clean run and believe the section synced. The two shapes that
        // reach here are a table whose rows all carry fewer than two usable descriptor cells,
        // and a table with no body rows at all — the message names which.
        //
        // THE PREDICATE IS "WANTS ROW CARDS AND NOTHING ELSE", which is what `/rows` means and
        // is spelled out rather than compared against that case by name. A scope wanting BOTH
        // kinds and getting none is deliberately NOT reported here: its cell cards are missing
        // too, and that is already `TableWithoutDescriptors`' business, said in the vocabulary
        // of columns rather than of row cards. Only a scope that asked for row cards ALONE has
        // a complaint this message can answer.
        if scope.wantsRowCards && !scope.wantsCellCards && cards.isEmpty then
          val what =
            if bodyRows.isEmpty then "the table has no rows yet"
            else
              // BOTH COUNTS, because they can differ: a column whose header canonicalises to
              // empty is declared but can never name a card, so quoting only the declared
              // total would say "3 columns" about a table that has one.
              s"every row has fewer than two usable descriptor cells " +
                s"(${descriptorColumns.count(_.segment.isDefined)} of ${descriptorColumns.size} " +
                s"declared column(s) can name a card)"
          Left(SpecError.TableRowsWithoutRows(where, what))
        else Right(cards)
    }

  /** A DECLARED descriptor column, paired once with the key segment its header yields.
    *
    * `segment` is `None` for a header that canonicalises to empty — blank, or nothing but a
    * `#flashcard` marker — which is a column that can never name a card.
    *
    * SUCH COLUMNS ARE CARRIED, NOT FILTERED OUT, because a value cell is matched to its header
    * BY POSITION (`zip` against `row.drop(1)`). Dropping the unusable ones from this vector
    * would slide every later column's values under the wrong header — a silently wrong card
    * rather than a missing one.
    */
  private final case class DescriptorColumn(cell: Cell, segment: Option[HeadingSegment])

  /** One descriptor column of one row, projected ONCE into all three things it feeds.
    *
    * Built in a single traversal so the `pairs.size < 2` threshold, the pair cards and the
    * row card's displayed descriptors all read the SAME vector. Splitting this into an
    * identity vector and a display vector of different lengths is a live
    * `NonEmptyVector.fromVectorUnsafe` crash on a real vault; this shape makes it
    * unrepresentable.
    */
  /** `header`/`value` are the DISPLAY projection, already escaped by [[CellDisplay.Escaped]] and
    * ready to be a field. `headerRaw`/`valueRaw` are the same cells UNESCAPED, and exist because
    * the row card's table is assembled inside `content/` — where escaping happens on the way
    * into an `Html.Fragment` and cannot be applied twice.
    *
    * FOUR PROJECTIONS OF ONE CELL NOW, and the fifth — `headerSeg` — is the IDENTITY one and
    * must never be conflated with any of them; that severance is what `Tables.test.scala` exists
    * to hold.
    */
  private final case class Descriptor(
      headerSeg: HeadingSegment,
      header: String,
      value: String,
      headerRaw: String,
      valueRaw: String,
  )

  /** One row's cards: a pair card per usable descriptor cell, plus a row card when the row
    * carries TWO OR MORE of them.
    *
    * EVERY EXISTENCE GATE HERE READS THE FROZEN PROJECTION, never `display`. A card that
    * stops being minted is an orphan, so "does this card exist" is an identity concern and
    * not a rendering one.
    */
  private def cardsForRow(
      base: CardKey,
      descriptorColumns: Vector[DescriptorColumn],
      row: Vector[Cell],
      rowNumber: Int,
      display: CellDisplay,
      context: String,
      conceptLabel: String,
      conceptLabelRaw: String,
      directions: ThreeFieldDirections,
      scope: TableScope,
  ): Vector[(CardSpec, RowSource)] =
    row.headOption.map(cell => (cell, cellSegment(cell))) match
      // A row with no cells at all names nothing.
      case None => Vector.empty
      // A concept cell with no derivable segment names nothing either. BEHAVIOUR-IDENTICAL to
      // the previous `.filter(_.nonEmpty)` on EVERY input, not merely on the fixture: a
      // non-empty but unkeyable concept already dropped every pair card (each `for` began
      // `rowSeg <- …toOption` = `None`) and dropped the row card the same way.
      case Some((_, Left(_))) => Vector.empty
      case Some((conceptCell, Right(rowSeg))) =>
        val rowConcept = display.text(conceptCell)
        // UNESCAPED, for the row card's table — see `Descriptor` for why both projections exist.
        val rowConceptRaw = display.raw(conceptCell)

        val pairs: Vector[Descriptor] =
          descriptorColumns.zip(row.drop(1)).flatMap { (column, valueCell) =>
            // THE HEADER GATE IS AN IDENTITY GATE, AND THIS ONE CHANGES OUTPUT — for exactly
            // one input class, ruled rather than discovered. A header cell whose text is
            // non-empty but canonicalises to empty after marker stripping (in practice, a
            // cell containing only `#flashcard…`) used to be counted in `pairs`: it inflated
            // the `pairs.size < 2` row-card threshold and was printed on a real card as a
            // descriptor literally named `#flashcard`, while producing no pair card of its
            // own. That is incoherent, so it is now excluded outright. `dummy-vault` does not
            // contain the input; `Tables.test.scala` test E pins it.
            //
            // THE DECISION IS READ HERE, NOT MADE HERE — `column.segment` was computed once in
            // `fromSection`, which is also what the whole-table refusal gates on. When EVERY
            // column fails this test the section is refused outright rather than returning an
            // empty vector, so the silence this comment used to record is gone.
            //
            // The VALUE cell gates on the frozen projection, never on `display`.
            if cellSource(valueCell).isEmpty then None
            else
              column.segment.map { headerSeg =>
                Descriptor(
                  headerSeg,
                  display.text(column.cell),
                  display.text(valueCell),
                  display.raw(column.cell),
                  display.raw(valueCell),
                )
              }
          }

        val pairCards = pairs.flatMap { d =>
          // THE ONE RESIDUAL COUPLING, NAMED RATHER THAN CLOSED. The gate above reads
          // `cellSource` and the body reads `display`, so a renderer that BLANKS a non-blank
          // cell would drop this pair card silently. Closing it needs a new `SpecError` case
          // in `model/CardSpec.scala`, which is outside this slice. Deliberately NOT papered
          // over with a `sys.error` under an `Either` signature or a `getOrElse` default: it
          // is observable to the differential guard (a dropped card changes the key sequence)
          // and, with `CellDisplay.Default`, unreachable — `Body.fromExtracted` returns
          // `None` iff the text trims to empty, and `cellSource` already trims.
          //
          // RE-DERIVED FOR `CellDisplay.Escaped`, which is what production injects since S11,
          // rather than left stale: `Html.escape` is INJECTIVE, maps empty to empty, and
          // introduces no whitespace — every character it emits is either the input character
          // or an entity beginning `&`. So a non-empty trimmed input yields a non-empty output
          // whose first and last characters are non-whitespace, on which `.trim` is a no-op.
          // The argument above therefore survives the swap unchanged.
          Body.fromExtracted(d.value).map { body =>
            val key = deeper(base, rowSeg, d.headerSeg)
            CardSpec.ThreeField(
              key,
              rowConcept,
              d.header,
              body,
              directions,
              context,
              conceptLabel,
            ) -> RowSource.table(SourceKind.TablePair, rowNumber)
          }
        }

        // Emitted only with two or more descriptors: with one it would merely duplicate the
        // single pair card. The threshold and the construction below read the SAME vector,
        // and `.map` is size-preserving, so the guard and `fromVectorUnsafe` cannot skew.
        val rowCard =
          if !scope.wantsRowCards then Vector.empty
          else if pairs.size < 2 then Vector.empty
          else
            val key = deeper(base, rowSeg)
            Vector(
              CardSpec.TableRow(
                key,
                // RAW text in, escaped inside `Html.rowTable`. `CellDisplay.Escaped` is
                // `Html.escape ∘ Default`, so passing `Default` here and escaping inside
                // produces byte-identical output while keeping the only String-to-Fragment
                // step inside `content/`.
                blanked = C.Html
                  .rowTable(
                    conceptLabelRaw +: pairs.map(_.headerRaw),
                    Some(rowConceptRaw) +: pairs.map(_ => None),
                  )
                  .render,
                filled = C.Html
                  .rowTable(
                    conceptLabelRaw +: pairs.map(_.headerRaw),
                    Some(rowConceptRaw) +: pairs.map(d => Some(d.valueRaw)),
                  )
                  .render,
                context,
              ) -> RowSource.table(SourceKind.TableRow, rowNumber)
            )

        // `rows` DROPS THE CELL CARDS, which is the whole point of asking for it: the cluster
        // is the knowledge and a column at a time tests something the author does not want.
        (if scope.wantsCellCards then pairCards else Vector.empty) ++ rowCard

  private def firstTable(section: Section): Option[Table] =
    section.content.collectFirst { case t: Table => t }

  /** Cells for each row. Rows stay `Vector[Cell]` all the way down to the point of use.
    *
    * THIS IS WHERE THE SEVERANCE LIVES. Collapsing a `Cell` to a `String` here — as this
    * function used to — is what made one value mean two things: `cardsForRow` bound the
    * concept and the header ONCE each and fed each binding to both the key and the card's
    * text. Splitting the projection function while a `Vector[Vector[String]]` grid survived
    * would have severed nothing, because the shared local survives.
    */
  private def rowCells(rows: Seq[Row]): Vector[Vector[Cell]] =
    rows.toVector.map(_.content.toVector)

  /** THE FROZEN PROJECTION. Feeds identity and every existence gate; never a card's text.
    *
    * Carried here VERBATIM from the function that used to serve both contracts — the
    * `mkString(" ")` and the trailing `.trim` were deliberately not tidied while moving, so
    * that this slice changes no extracted value.
    *
    * MEASURED FACT about those two, not inference. S1's mutant matrix ran both against the
    * whole suite and both SURVIVED: `mkString(" ")` → `mkString("@@")` (M4) and removing the
    * `.trim` (M5) left every test green. The mechanism, read out of laika-core '''1.3.2'''
    * rather than assumed — so it is a bet on a Laika version, stated as one:
    * `laika/internal/markdown/github/Tables.scala:38` builds every GFM cell as
    * `Cell(cellType, Seq(Paragraph(spans)))`, one block unconditionally, and pads a short row
    * with a zero-block `CellType.BodyCell.empty`; a one-or-zero-element `mkString(sep)` emits
    * no separator for ANY `sep`. And `LineSource.trim`
    * (`laika/parse/SourceCursor.scala:231-235`) is applied per cell BEFORE span parsing, so
    * there is no edge whitespace left to trim.
    *
    * BACK-REFERENCE, because `Golden.test.scala` is outside this slice's file list and still
    * names this function by its former name: what that file records as `Tables.cellText` —
    * including its whole M4/M5 matrix and the sentence "the golden pins `Tables.cellText`'s
    * OUTPUT VALUE ONLY" — is this function. And a CONTENT CORRECTION, not merely a rename:
    * M4 and M5 were measured BEFORE the identity/display split, when ONE function served both
    * contracts. There are now TWO copies of this body, here and in [[CellDisplay.Default]],
    * so reproducing that result means mutating both.
    */
  private def cellSource(cell: Cell): String =
    cell.content.collect { case sc: SpanContainer => sc.extractText }.mkString(" ").trim

  /** THE IDENTITY PROJECTION — a cell's contribution to a card's key, and nothing else.
    *
    * This is the ONLY place `HeadingSegment.fromExtractedText` is called in this file, and it
    * takes a `Cell` rather than a `String` so that rendered content has no accidental way in.
    * ACCIDENTAL, not impossible: `fromExtractedText` still accepts a bare `String` from
    * `model/CardKey.scala`, so a future author can re-couple the paths by hand and only the
    * differential guard in `Tables.test.scala` would notice.
    */
  private def cellSegment(cell: Cell): Either[KeyError, HeadingSegment] =
    HeadingSegment.fromExtractedText(cellSource(cell))
