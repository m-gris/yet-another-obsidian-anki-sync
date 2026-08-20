package obsidiananki.extract

import cats.data.NonEmptyVector
import laika.ast.*
import obsidiananki.model.*
import obsidiananki.parser.ObsidianSyntax
import obsidiananki.plan.{BuildFailure, SourceKind, SourceRef, SourcedSpec}

/** Turning one parsed note into the cards it declares.
  *
  * The walk is over Laika's nested `Section` tree rather than over lines, which is the whole
  * reason this tool exists: a card's identity is its position in the document tree, and no
  * regex-based bridge can express that.
  */
final case class ExtractedNote(specs: Vector[SourcedSpec], failures: Vector[BuildFailure])

object Extractor:

  /** Extract every card declared by one note.
    *
    * @param noteId   from frontmatter, already validated
    * @param fileName used as the fallback concept when a marked heading has no ancestor
    * @param filePath for diagnostics only
    */
  def fromDocument(
      noteId: NoteId,
      fileName: String,
      filePath: String,
      root: RootElement,
      body: String = "",
  ): ExtractedNote =
    val specs    = Vector.newBuilder[SourcedSpec]
    val failures = Vector.newBuilder[BuildFailure]
    val lines    = LineIndex(body)

    /** @param ancestors heading texts from the outermost down to this section's parent,
      *                  already marker-stripped and canonicalised into segments
      * @param ancestorTitles the same chain as DISPLAY text, for the concept — a concept is
      *                  shown on a card, so it keeps its original casing rather than the
      *                  canonical lowercase form the key uses
      */
    def walk(
        element: Element,
        ancestors: Vector[HeadingSegment],
        ancestorTitles: Vector[String],
    ): Unit = element match
      case section: Section =>
        val rawHeading = section.header.extractText
        val ref        = SourceRef(filePath, lines.lineOf(rawHeading), SourceKind.Heading)

        HeadingSegment.fromExtractedText(rawHeading) match
          // A heading that extracts to nothing cannot contribute a path segment, so no key
          // beneath it is derivable either. The blast radius is the FILE, not the card.
          case Left(_) =>
            failures += BuildFailure.KeyUnderivableInFile(noteId, ref, s"heading extracts to nothing")

          case Right(segment) =>
            val path      = ancestors :+ segment
            val title     = Marker.stripMarker(rawHeading)
            val nextTitles = ancestorTitles :+ title

            Marker.parse(rawHeading) match
              case Left(err) =>
                failures += BuildFailure.KeyKnown(
                  CardKey(noteId, HeadingPath(NonEmptyVector.fromVectorUnsafe(path))),
                  ref,
                  s"unusable marker: $err",
                )

              case Right(None) => () // ordinary prose section — an ancestor, not a card

              case Right(Some(marker)) =>
                val key = CardKey(noteId, HeadingPath(NonEmptyVector.fromVectorUnsafe(path)))
                buildSpecs(key, marker, title, ancestorTitles, section, fileName) match
                  case Right(built) => built.foreach { case (spec, src) =>
                      specs += SourcedSpec(
                        spec,
                        ref.copy(kind = src.kind, detail = src.detail),
                      )
                    }
                  case Left(err) => failures += BuildFailure.KeyKnown(key, ref, describe(err))

            // Descend regardless: an unmarked heading is still an ancestor, and a marked one
            // can contain further marked headings.
            section.content.foreach(walk(_, path, nextTitles))

      case container: BlockContainer => container.content.foreach(walk(_, ancestors, ancestorTitles))
      case _                         => ()

    root.content.foreach(walk(_, Vector.empty, Vector.empty))
    ExtractedNote(specs.result(), failures.result())

  private def describe(e: SpecError): String = e match
    case SpecError.EmptyBody(p)              => s"empty body at '$p'"
    case SpecError.ClozeWithoutDeletions(p)  => s"cloze section with no ==highlight== at '$p'"
    case SpecError.AmbiguousClozeDeletion(p, t) =>
      s"two unlabelled '==$t==' highlights at '$p' cannot be told apart — label them, e.g. ==1|$t=="
    case SpecError.TableWithoutTable(p)      => s"table marker with no table at '$p'"
    case SpecError.TableWithoutDescriptors(p) =>
      s"table at '$p' has a concept column but no descriptor columns, so it yields no cards"
    case SpecError.UnsupportedTaskList(p)    => s"task list is not supported, at '$p'"
    case SpecError.UnsupportedEmbed(p, t)    => s"embed '$t' is not supported, at '$p'"

  /** A marked heading yields ONE spec for most markers and MANY for a table — n pair cards
    * plus a row card per row. Each carries the source kind it should be reported as, so a
    * key collision names "table pair card" rather than "heading".
    */
  private def buildSpecs(
      key: CardKey,
      marker: Marker,
      title: String,
      ancestorTitles: Vector[String],
      section: Section,
      fileName: String,
  ): Either[SpecError, Vector[(CardSpec, RowSource)]] =
    val where = key.path.render
    // A table section's content IS the table; it has no prose body of its own, so the
    // empty-body rule must not be applied to it.
    if marker == Marker.Table then Tables.fromSection(key, section)
    else for
      text <- bodyText(ownBody(section)).left.map {
        case SpecError.UnsupportedEmbed(_, t) => SpecError.UnsupportedEmbed(where, t)
        case SpecError.UnsupportedTaskList(_) => SpecError.UnsupportedTaskList(where)
        case other                            => other
      }
      body <- Body.fromExtracted(text).toRight(SpecError.EmptyBody(where))
      spec <- marker match
        case Marker.TwoField(directions) =>
          Right(Vector(CardSpec.TwoField(key, title, body, directions) -> RowSource.heading))

        case Marker.ThreeField(directions) =>
          // The concept is the NEAREST ancestor heading, or the filename when the marked
          // heading has no ancestor at all.
          val concept = ancestorTitles.lastOption.getOrElse(fileName)
          Right(Vector(CardSpec.ThreeField(key, concept, title, body, directions) -> RowSource.heading))

        case Marker.Cloze => Cloze.fromSection(key, section).map(c => Vector(c -> RowSource.heading))
        case Marker.Table => Tables.fromSection(key, section)
    yield spec

  /** A section's OWN prose — everything down to the next heading of ANY level.
    *
    * RULED (B6). Descendant sections are excluded: including them would make a parent card
    * duplicate its children and grow without bound. The consequence is that a marked heading
    * immediately followed by a subheading has an EMPTY body, which is a hard error rather
    * than an empty field — see [[SpecError.EmptyBody]].
    */
  def ownBody(section: Section): Vector[Block] =
    section.content.toVector.takeWhile {
      case _: Section => false
      case _          => true
    }

  /** Plain text of a section's own prose, with the constructs v0 refuses already rejected. */
  def bodyText(blocks: Vector[Block]): Either[SpecError, String] =
    // Reject BY TYPE, not by inspecting rendered text or matching an error message. The
    // parser recognises both constructs precisely so this check can be structural.
    val spans = blocks.flatMap(allSpans)
    val embed = spans.collectFirst { case e: ObsidianSyntax.ObsidianEmbed => e }
    val task  = spans.collectFirst { case t: ObsidianSyntax.TaskListMarker => t }
    (embed, task) match
      case (Some(e), _) => Left(SpecError.UnsupportedEmbed("", e.target))
      case (_, Some(_)) => Left(SpecError.UnsupportedTaskList(""))
      case _ =>
        Right(blocks.map(blockText).filter(_.nonEmpty).mkString("\n").trim)

  private def blockText(b: Block): String = elementText(b)

  /** Text of any element, descending through EVERY child.
    *
    * THE CASES BELOW ARE A TYPE LATTICE, NOT A STYLE. Laika's containers do not form the tidy
    * hierarchy this walk originally assumed, and each omission destroyed content SILENTLY —
    * the card was still created, still looked right, and was missing part of its answer. All
    * three facts were read out of laika-core 1.3.2's sources rather than inferred:
    *
    *   - `BulletListItem` and `EnumListItem` are `BlockContainer`s but are NOT `Block`s (they
    *     are `ListItem extends Element`). A walk collecting only `Block` children matched the
    *     list and then discarded every item in it. That is why `- one` `- two` vanished while
    *     the prose around it survived.
    *   - `LiteralBlock` is a `TextContainer` — `Container[String]` — and is neither a
    *     `SpanContainer` nor an `ElementContainer`. It fell to the catch-all. That is a fenced
    *     or indented code block, silently deleted, in a project whose ratified design promises
    *     the body may hold "prose, lists, formulae, code".
    *   - `Table` is a `Block` with `ElementTraversal` and `RewritableContainer`. It is NOT an
    *     `ElementContainer` and HAS NO `content` MEMBER AT ALL, so it too fell to the
    *     catch-all: a table inside an ordinary body disappeared whole. Its parts are reachable
    *     only by naming them, which is what the case below does.
    *
    * `case _ => ""` IS THE HAZARD, and it is kept only because Laika's element hierarchy is
    * open. Every construct that has ever reached it did so by being silently deleted, which is
    * this project's signature failure. Anything added here must be added because its shape was
    * checked, never because a test happened to pass.
    */
  private def elementText(e: Element): String = e match
    case sc: SpanContainer => sc.extractText
    case tc: TextContainer => tc.content
    // Named explicitly because a Table has no `content` to descend into. Head and body are
    // `TableContainer`s, which ARE `ElementContainer`s, so everything below them is generic.
    case t: Table =>
      Vector(t.head, t.body).map(elementText).filter(_.nonEmpty).mkString("\n")
    case ec: ElementContainer[?] =>
      ec.content.toVector.collect { case el: Element => elementText(el) }.filter(_.nonEmpty).mkString("\n")
    case _ => ""

  /** Every span anywhere beneath an element, including inside table cells and list items.
    *
    * Written out rather than reaching for a Laika traversal helper because the rejection it
    * feeds must be exhaustive: an embed hidden in a table cell is still an embed, and missing
    * one would put a card with a broken image into Anki.
    *
    * THAT SENTENCE WAS FALSE UNTIL 2026-08-20. A `Table` is not an `ElementContainer`, so it
    * matched no case and returned nothing — an `![[embed]]` inside a table cell was NOT
    * rejected, and the card was written. The comment claimed the coverage that made the check
    * worth having, which is exactly why nobody tested it. The `Table` case below is what makes
    * the sentence true.
    */
  private def allSpans(e: Element): Vector[Span] = e match
    case s: (Span & SpanContainer) => s +: s.content.toVector.flatMap(allSpans)
    case s: Span                   => Vector(s)
    case t: Table                  => Vector(t.head, t.body).flatMap(allSpans)
    // Generic container case, NOT BlockContainer specifically: a BulletList is a
    // ListContainer, and matching only on BlockContainer would walk straight past every
    // list — leaving a task marker or an embed inside one undetected.
    case ec: ElementContainer[?] => ec.content.toVector.flatMap(allSpans)
    case _                       => Vector.empty


/** Line lookup for diagnostics.
  *
  * Laika's `Header` does not retain a source position, so the line is recovered by matching
  * the heading's extracted text against the raw body. A CURSOR is kept so that two headings
  * with identical text report DIFFERENT lines — reporting the first match for both would
  * reproduce the very ambiguity these positions exist to remove.
  */
private final class LineIndex(body: String):
  private val lines  = body.linesIterator.toVector
  private var cursor = 0

  def lineOf(headingText: String): Int =
    val needle = headingText.trim
    if needle.isEmpty then 0
    else
      val idx = lines.indexWhere(l => l.startsWith("#") && l.dropWhile(_ == '#').trim == needle, cursor)
      if idx < 0 then 0
      else
        cursor = idx + 1
        idx + 1 // 1-based, as an editor counts
