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
            // Called for its REJECTIONS — an embed or a task list anywhere in the body is
            // refused by type here. Its text is deliberately discarded: the body that goes
            // to Anki is built by `renderWithDeletions` below, which is the only rendering
            // that carries the deletions.
            _ <- Extractor.bodyText(blocks).left.map {
              case SpecError.UnsupportedEmbed(_, t) => SpecError.UnsupportedEmbed(where, t)
              case SpecError.UnsupportedTaskList(_) => SpecError.UnsupportedTaskList(where)
              case other                            => other
            }
            deletions <- NonEmptyVector
              .fromVector(number(found))
              .toRight(SpecError.ClozeWithoutDeletions(where))
            body <- Body
              .fromExtracted(renderWithDeletions(blocks, deletions))
              .toRight(SpecError.EmptyBody(where))
          yield CardSpec.Cloze(key, body, deletions)

  /** The body as Anki must receive it: `{{cN::…}}` where the author wrote a highlight.
    *
    * THIS IS THE STEP THAT WAS MISSING, and its absence was invisible because everything
    * around it was right. The grouping is ruled on, parsed, numbered and tested; the note
    * type and its field names are correct; the only thing never done was putting the two
    * together. A comment on the field renderer asserted this happened "when the note is
    * rendered", which is why nobody looked — the seventh untrue claim in this project, and
    * the one that hid an actual defect rather than merely overstating a guarantee.
    *
    * It cost two cards on the first live run: Anki REFUSES a Cloze note containing no
    * deletion, with "cannot create note for unknown reason". Nothing caught it earlier
    * because both fakes accept such a note happily — the fake and the code shared the
    * misunderstanding, so the suite was green and wrong together.
    *
    * RENDERED FROM THE TREE, NOT BY SUBSTITUTING INTO THE EXTRACTED TEXT. By the time the
    * body has been flattened to a string the `==` markers are gone, so a deletion could only
    * be located by matching its text — and a word that also appears elsewhere in the body
    * would be blanked in the wrong place, or in several. Walking the spans puts each
    * `{{cN::…}}` exactly where the author put the highlight.
    */
  private def renderWithDeletions(blocks: Vector[Block], deletions: NonEmptyVector[ClozeDeletion]): String =
    // A group's ordinal, looked up by the group itself rather than by text, so two
    // highlights sharing a label render the same `cN` and therefore blank together.
    val ordinalOf: Map[ClozeGroup, Int] = deletions.toVector.map(d => d.group -> d.ordinal).toMap

    def spanText(s: Span): String = s match
      case h: ObsidianSyntax.Highlighted =>
        val group = h.group match
          case Some(n) => ClozeGroup.Labelled(n)
          case None    => ClozeGroup.Unlabelled(h.content.map(spanText).mkString)
        val inner = h.content.map(spanText).mkString
        ordinalOf.get(group) match
          case Some(n) => s"{{c$n::$inner}}"
          // Unreachable while `number` groups exactly the highlights `highlights` found —
          // both walk the same blocks. Rendering the bare text rather than throwing would
          // silently drop a deletion, which is the failure this whole function exists to
          // fix, so it is left as a match error rather than papered over.
          case None => sys.error(s"cloze group with no ordinal: $group")
      case sc: SpanContainer => sc.content.map(spanText).mkString
      case t: TextContainer  => t.content
      case _                 => ""

    def blockText(b: Block): String = b match
      case sc: SpanContainer       => sc.content.map(spanText).mkString
      case ec: ElementContainer[?] =>
        ec.content.collect { case blk: Block => blockText(blk) }.filter(_.nonEmpty).mkString("\n")
      case _ => ""

    blocks.map(blockText).filter(_.nonEmpty).mkString("\n").trim

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
