package obsidiananki.extract

import cats.data.NonEmptyVector
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
final case class CellDisplay(text: Cell => String)

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
    CellDisplay(cell => cell.content.collect { case sc: SpanContainer => sc.extractText }.mkString(" ").trim)

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
    */
  val Escaped: CellDisplay =
    CellDisplay(cell => C.Html.escape(Default.text(cell)).render)

object Tables:

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
    * A NAMED, UNCLOSED HOLE: `Extractor.scala` has two call sites and only ONE of them runs.
    * The arm inside `buildSpecs`'s `marker match` sits in the `else` of
    * `if marker == Marker.Table` and exists only because an inexhaustive match is a build
    * error here. Its `CellDisplay` argument is exercised by no test and never will be while
    * that `if` stands — a future "simplification" that collapses the `if` makes it live.
    * Both call sites inject [[CellDisplay.Escaped]] since S11.
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
  ): Either[SpecError, Vector[(CardSpec, RowSource)]] =
    val where   = key.path.render
    val context = CardContext.render(contextTitles)
    firstTable(section).toRight(SpecError.TableWithoutTable(where)).flatMap { table =>
      val headerRow = rowCells(table.head.content).headOption.getOrElse(Vector.empty)
      val bodyRows  = rowCells(table.body.content)

      // The first column names the concept; the remaining headers are the descriptors.
      val descriptorHeaders = headerRow.drop(1)

      // AND THE FIRST HEADER IS KEPT, where it used to be read for its position and thrown
      // away. It names what the rows ARE — `Bone` over `Frontal`, `Parietal` — so without it a
      // card asks "anterior border: orbital rim" and expects "Frontal" while never saying it
      // wants the name of a bone. DISPLAY text, not a key segment: it is shown and never keyed,
      // so it cannot move a card's identity however it is written.
      val conceptLabel = headerRow.headOption.map(display.text).getOrElse("")

      if descriptorHeaders.isEmpty then Left(SpecError.TableWithoutDescriptors(where))
      else
        Right(
          bodyRows.zipWithIndex.flatMap((row, i) =>
            cardsForRow(key, descriptorHeaders, row, i + 1, display, context, conceptLabel)
          )
        )
    }

  /** One descriptor column of one row, projected ONCE into all three things it feeds.
    *
    * Built in a single traversal so the `pairs.size < 2` threshold, the pair cards and the
    * row card's displayed descriptors all read the SAME vector. Splitting this into an
    * identity vector and a display vector of different lengths is a live
    * `NonEmptyVector.fromVectorUnsafe` crash on a real vault; this shape makes it
    * unrepresentable.
    */
  private final case class Descriptor(headerSeg: HeadingSegment, header: String, value: String)

  /** One row's cards: a pair card per usable descriptor cell, plus a row card when the row
    * carries TWO OR MORE of them.
    *
    * EVERY EXISTENCE GATE HERE READS THE FROZEN PROJECTION, never `display`. A card that
    * stops being minted is an orphan, so "does this card exist" is an identity concern and
    * not a rendering one.
    */
  private def cardsForRow(
      base: CardKey,
      descriptorHeaders: Vector[Cell],
      row: Vector[Cell],
      rowNumber: Int,
      display: CellDisplay,
      context: String,
      conceptLabel: String,
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

        val pairs: Vector[Descriptor] =
          descriptorHeaders.zip(row.drop(1)).flatMap { (headerCell, valueCell) =>
            // THE HEADER GATE IS AN IDENTITY GATE, AND THIS ONE CHANGES OUTPUT — for exactly
            // one input class, ruled rather than discovered. A header cell whose text is
            // non-empty but canonicalises to empty after marker stripping (in practice, a
            // cell containing only `#flashcard…`) used to be counted in `pairs`: it inflated
            // the `pairs.size < 2` row-card threshold and was printed on a real card as a
            // descriptor literally named `#flashcard`, while producing no pair card of its
            // own. That is incoherent, so it is now excluded outright. `dummy-vault` does not
            // contain the input; `Tables.test.scala` test E pins it.
            //
            // NOTE what stays silent: a table whose ONLY descriptor column has such a header
            // still yields zero cards with nothing said, because `descriptorHeaders` is
            // non-empty at the `Cell` level so `TableWithoutDescriptors` does not fire. Same
            // silence as before for that input — no regression, no improvement.
            //
            // The VALUE cell gates on the frozen projection, never on `display`.
            if cellSource(valueCell).isEmpty then None
            else
              cellSegment(headerCell).toOption.map { headerSeg =>
                Descriptor(headerSeg, display.text(headerCell), display.text(valueCell))
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
            val key = base.copy(path = HeadingPath(base.path.segments :+ rowSeg :+ d.headerSeg))
            CardSpec.ThreeField(
              key,
              rowConcept,
              d.header,
              body,
              ThreeFieldDirections.Default,
              context,
              conceptLabel,
            ) -> RowSource.table(SourceKind.TablePair, rowNumber)
          }
        }

        // Emitted only with two or more descriptors: with one it would merely duplicate the
        // single pair card. The threshold and the construction below read the SAME vector,
        // and `.map` is size-preserving, so the guard and `fromVectorUnsafe` cannot skew.
        val rowCard =
          if pairs.size < 2 then Vector.empty
          else
            val key = base.copy(path = HeadingPath(base.path.segments :+ rowSeg))
            Vector(
              CardSpec.TableRow(
                key,
                rowConcept,
                NonEmptyVector.fromVectorUnsafe(pairs.map(d => d.header -> d.value)),
                context,
              ) -> RowSource.table(SourceKind.TableRow, rowNumber)
            )

        pairCards ++ rowCard

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
