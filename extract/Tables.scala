package obsidiananki.extract

import cats.data.NonEmptyVector
import laika.ast.*
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

object Tables:

  /** Build every card a table section declares.
    *
    * Note this requires GitHubFlavor to be enabled on the parser. Laika's base Markdown has
    * no table support at all: the whole table would arrive as one paragraph of literal pipe
    * characters, and this would find nothing.
    */
  def fromSection(key: CardKey, section: Section): Either[SpecError, Vector[(CardSpec, RowSource)]] =
    val where = key.path.render
    firstTable(section).toRight(SpecError.TableWithoutTable(where)).flatMap { table =>
      val headerRow = rowCells(table.head.content).headOption.getOrElse(Vector.empty)
      val bodyRows  = rowCells(table.body.content)

      // The first column names the concept; the remaining headers are the descriptors.
      val descriptorHeaders = headerRow.drop(1)

      if descriptorHeaders.isEmpty then Left(SpecError.TableWithoutDescriptors(where))
      else Right(bodyRows.zipWithIndex.flatMap((row, i) => cardsForRow(key, descriptorHeaders, row, i + 1)))
    }

  /** One row's cards: a pair card per non-empty descriptor cell, plus a row card when the
    * row carries TWO OR MORE of them.
    */
  private def cardsForRow(
      base: CardKey,
      descriptorHeaders: Vector[String],
      row: Vector[String],
      rowNumber: Int,
  ): Vector[(CardSpec, RowSource)] =
    row.headOption.filter(_.nonEmpty) match
      case None => Vector.empty // a row with no concept names nothing
      case Some(rowConcept) =>
        val pairs = descriptorHeaders.zip(row.drop(1)).collect {
          case (header, cell) if header.nonEmpty && cell.nonEmpty => header -> cell
        }

        val pairCards = pairs.flatMap { (header, cell) =>
          for
            rowSeg  <- HeadingSegment.fromExtractedText(rowConcept).toOption
            colSeg  <- HeadingSegment.fromExtractedText(header).toOption
            body    <- Body.fromExtracted(cell)
          yield
            val key = base.copy(path = HeadingPath(base.path.segments :+ rowSeg :+ colSeg))
            CardSpec.ThreeField(key, rowConcept, header, body, ThreeFieldDirections.Default) ->
              RowSource.table(SourceKind.TablePair, rowNumber)
        }

        // Emitted only with two or more descriptors: with one it would merely duplicate the
        // single pair card.
        val rowCard =
          if pairs.size < 2 then Vector.empty
          else
            HeadingSegment
              .fromExtractedText(rowConcept)
              .toOption
              .map { rowSeg =>
                val key = base.copy(path = HeadingPath(base.path.segments :+ rowSeg))
                CardSpec.TableRow(key, rowConcept, NonEmptyVector.fromVectorUnsafe(pairs)) ->
                  RowSource.table(SourceKind.TableRow, rowNumber)
              }
              .toVector

        pairCards ++ rowCard

  private def firstTable(section: Section): Option[Table] =
    section.content.collectFirst { case t: Table => t }

  /** Cell text for each row, descending through the cell's blocks. */
  private def rowCells(rows: Seq[Row]): Vector[Vector[String]] =
    rows.toVector.map(_.content.toVector.map(cellText))

  private def cellText(cell: Cell): String =
    cell.content.collect { case sc: SpanContainer => sc.extractText }.mkString(" ").trim
