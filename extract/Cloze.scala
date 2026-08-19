package obsidiananki.extract

import cats.data.NonEmptyVector
import laika.ast.*
import obsidiananki.model.*
import obsidiananki.parser.ObsidianSyntax

/** Turning a `#flashcard/cloze` section into one Anki note holding all of its deletions.
  *
  * ONE SECTION IS ONE NOTE. Adding a highlight therefore adds a CARD to an existing note
  * rather than creating a new note, so the note's key never churns.
  *
  * Within that note, deletions are grouped, and the grouping is what makes numbering stable:
  * a labelled group's number IS its label, chosen by the author and immune to text edits.
  */
object Cloze:

  /** Build the cloze note for a section.
    *
    * Numbering:
    *   - a LABELLED group takes its label as its `cN`, so it is stable by construction —
    *     nothing the tool computes can move it.
    *   - an UNLABELLED group takes the lowest number no label has claimed, in order of first
    *     appearance. That is stable only while the set of deletions is stable, which is
    *     precisely the fragility the ruling makes visible: labelling is how you avoid it.
    */
  def fromSection(key: CardKey, section: Section): Either[SpecError, CardSpec] =
    val where  = key.path.render
    val blocks = Extractor.ownBody(section)
    val found  = highlights(blocks)

    if found.isEmpty then Left(SpecError.ClozeWithoutDeletions(where))
    else
      // Two unlabelled highlights with the same text are indistinguishable by anything but
      // position, so they are refused with the remedy named rather than tie-broken.
      val unlabelledTexts = found.collect { case (None, text) => text }
      unlabelledTexts.groupBy(identity).collectFirst { case (t, occurrences) if occurrences.sizeIs > 1 => t } match
        case Some(duplicate) => Left(SpecError.AmbiguousClozeDeletion(where, duplicate))
        case None =>
          for
            text <- Extractor.bodyText(blocks).left.map {
              case SpecError.UnsupportedEmbed(_, t) => SpecError.UnsupportedEmbed(where, t)
              case SpecError.UnsupportedTaskList(_) => SpecError.UnsupportedTaskList(where)
              case other                            => other
            }
            body <- Body.fromExtracted(text).toRight(SpecError.EmptyBody(where))
            deletions <- NonEmptyVector
              .fromVector(number(found))
              .toRight(SpecError.ClozeWithoutDeletions(where))
          yield CardSpec.Cloze(key, body, deletions)

  /** Assign each group its `cN`. */
  private def number(found: Vector[(Option[Int], String)]): Vector[ClozeDeletion] =
    val labels = found.collect { case (Some(n), _) => n }.toSet

    // Grouped in order of FIRST APPEARANCE, so the result is deterministic.
    val groups: Vector[(ClozeGroup, Vector[String])] =
      found
        .map {
          case (Some(n), text) => ClozeGroup.Labelled(n)      -> text
          case (None, text)    => ClozeGroup.Unlabelled(text) -> text
        }
        .foldLeft(Vector.empty[(ClozeGroup, Vector[String])]) { case (acc, (group, text)) =>
          acc.indexWhere(_._1 == group) match
            case -1 => acc :+ (group -> Vector(text))
            case i  => acc.updated(i, group -> (acc(i)._2 :+ text))
        }

    var nextFree = 1
    groups.map { (group, texts) =>
      val ordinal = group match
        case ClozeGroup.Labelled(n) => n
        case ClozeGroup.Unlabelled(_) =>
          // Skip anything a label already claims, or two groups would share a card.
          while labels.contains(nextFree) do nextFree += 1
          val n = nextFree
          nextFree += 1
          n
      ClozeDeletion(ordinal, group, texts)
    }

  /** Every highlight in document order, with its group label and its text. */
  private def highlights(blocks: Vector[Block]): Vector[(Option[Int], String)] =
    blocks.flatMap(collectHighlights)

  private def collectHighlights(e: Element): Vector[(Option[Int], String)] = e match
    case h: ObsidianSyntax.Highlighted => Vector(h.group -> h.extractText)
    case ec: ElementContainer[?]       => ec.content.toVector.flatMap(collectHighlights)
    case _                             => Vector.empty
