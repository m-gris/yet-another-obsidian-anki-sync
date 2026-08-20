package obsidiananki.extract

import cats.data.NonEmptyVector
import laika.ast.*
import obsidiananki.content as C
import obsidiananki.model.*
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
        // AN OPEN HAZARD, NOT COVERED BY ANYTHING BELOW. `SpanContainer.extractText` is Laika's
        // own trait match with a silent `case _ => ""` (`laika/ast/containers.scala:117-121`),
        // so an image in a MARKED HEADING is dropped without a word — and here that does not
        // lose a word from a body, it changes the card's KEY, which orphans a live synced note.
        //
        // The body-side image refusal does NOT cover this and never did: `Section(header,
        // content)` keeps the header OUTSIDE `content` (`laika/ast/blocks.scala:231`), and the
        // refusal runs over `ownBody`, which is built from `section.content`. A comment on that
        // refusal used to claim otherwise. Fixing this is its own slice.
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

      // A DELIBERATE, ANNOTATED EXCEPTION to "match concrete node types, never traits", and it
      // is listed as such in the ledger beside `bodyBlocks`. This is a STRUCTURAL HUNT FOR
      // `Section`s, not a rendering walk: its failure mode is failing to FIND a card, never
      // silently dropping content from one. A missed container yields no card at all, which is
      // loud in the diff against Anki; a rendering walk that missed one yielded a card that
      // looked right and was missing part of its answer.
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
    case SpecError.UnsupportedContent(p, what) => s"$what, at '$p'"

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
    //
    // THE SAFETY CHECK RUNS FOR A TABLE SECTION TOO. It used to live only in the `else`
    // branch, so a `#flashcard/table` section never ran it at all: an embed in a cell was
    // neither rendered nor refused — the cell came back empty, the row was dropped for being
    // empty, and NOTHING was reported. A card silently ceased to exist. `bodyBlocks` is called
    // here purely for its refusals; the lowered blocks are DISCARDED, because a table section's
    // content IS the table and `Tables` decomposes it separately.
    //
    // WHAT FIRING IT COSTS, WHICH THIS COMMENT USED TO OMIT. `walk` records every `buildSpecs`
    // failure as `BuildFailure.KeyKnown` at the SECTION key, while `Tables.cardsForRow:170,190`
    // keys every table card ONE OR TWO SEGMENTS DEEPER — so `VaultScan.failedKeys` claims none
    // of them, and `Planner:211` (`accountedFor = builtKeys ++ failedKeys`) sends every one of
    // them to `SyncAction.Flag`. `dummy-vault/Patterns/Messaging.md § "Cost / benefit"` is nine
    // cards; one `![[x.png]]` in one cell flags all nine against a live collection. NOT FIXED
    // HERE — it needs a key SET on the failure record, which is its own slice.
    //
    // THIS SWAP DOES NOT WIDEN THAT HOLE, and the claim is a VERSION-SCOPED BET rather than a
    // proof. Against laika-core 1.3.2 with `Markdown` + `GitHubFlavor` + this bundle and strict
    // parsing, the set of constructs that can refuse a TABLE section is still exactly three —
    // embed, image, task list: `laika/internal/markdown/github/Tables.scala:100-104` builds the
    // head as unconditionally one `Row`, `:36-39` builds every cell as unconditionally
    // `Seq(Paragraph(spans))`, `:85-97` pads only with the zero-block `CellType.BodyCell.empty`,
    // and `parser/ObsidianSyntax.scala:251` sets no `failOnMessages` override, so an
    // `InvalidBlock`/`InvalidSpan` fails the whole document at `VaultWalker.scala:92` instead of
    // reaching the lowering. THE RESIDUAL, stated rather than hidden: `content.Lower` enumerates
    // no `ast.InvalidBlock`/`ast.InvalidSpan`, and whether a below-Error invalid element can
    // survive into the tree is UNVERIFIED.
    if marker == Marker.Table then
      bodyBlocks(where, ownBody(section))
        .flatMap(_ => Tables.fromSection(key, section, CellDisplay.Default))
    else for
      lowered <- bodyBlocks(where, ownBody(section))
      text = C.AsText.plain(lowered)
      // COMPUTED AND THEN DISCARDED ON THE CLOZE PATH, DELIBERATELY. It reads like dead code
      // and is not: `EmptyBody` must keep firing AHEAD of the cloze branch, so a cloze section
      // whose body renders to nothing is reported as an empty body rather than as a cloze
      // section with no highlight. Moving this below the marker match changes which error an
      // author reads.
      body <- Body.fromExtracted(text).toRight(SpecError.EmptyBody(where))
      spec <- marker match
        case Marker.TwoField(directions) =>
          Right(Vector(CardSpec.TwoField(key, title, body, directions) -> RowSource.heading))

        case Marker.ThreeField(directions) =>
          // The concept is the NEAREST ancestor heading, or the filename when the marked
          // heading has no ancestor at all.
          val concept = ancestorTitles.lastOption.getOrElse(fileName)
          Right(Vector(CardSpec.ThreeField(key, concept, title, body, directions) -> RowSource.heading))

        case Marker.Cloze => Cloze.fromLowered(key, lowered).map(c => Vector(c -> RowSource.heading))
        case Marker.Table => Tables.fromSection(key, section, CellDisplay.Default)
    yield spec

  /** A section's OWN prose — everything down to the next heading of ANY level.
    *
    * RULED (B6). Descendant sections are excluded: including them would make a parent card
    * duplicate its children and grow without bound. The consequence is that a marked heading
    * immediately followed by a subheading has an EMPTY body, which is a hard error rather
    * than an empty field — see [[SpecError.EmptyBody]].
    *
    * THIS RUNS OVER LAIKA'S TREE, AND LOWERING HAPPENS AFTER IT, NEVER BEFORE. The closed
    * algebra has no `Section` constructor, so "lower the whole section, then take-while" has no
    * analogue: every descendant section would already have been flattened into its parent's
    * blocks, and every parent card would swallow all of its children — the cheapest available
    * way to move the golden.
    */
  def ownBody(section: Section): Vector[Block] =
    section.content.toVector.takeWhile {
      case _: Section => false
      case _          => true
    }

  /** A section's own prose, LOWERED into this project's closed algebra — or every reason it
    * could not be.
    *
    * THIS IS THE ONLY PLACE A `Refusal` BECOMES A `SpecError`, AND THE ONLY PLACE A HEADING
    * PATH IS ATTACHED. The predecessor, `bodyText`, constructed `SpecError.UnsupportedEmbed("")`
    * with a heading path it did not have and could not have, and three separate `.left.map`
    * blocks patched the empty string afterwards — one per call site. That lie is now
    * UNREPRESENTABLE rather than merely fixed: `content.Refusal` has no path field at all, so
    * the path can only be attached here, by the caller that knows it. The three patch blocks
    * are gone because their input no longer exists, not because someone tidied them away.
    *
    * ONE WALK, NOT TWO. Refusal used to be a separate pre-scan (`allSpans`, hunting embeds,
    * images and task markers) standing beside a renderer (`elementText`). Two walks over one
    * tree with different notions of "container" is this project's signature defect — it is how
    * a cloze section was told it had no highlight when it plainly did. Refusal is now INHERENT
    * in the single lowering: a construct is refused because the lowering has no case for it.
    *
    * `.distinct` IS ON THE DESCRIPTION, AND IT LOSES THE COUNT. Measured: `- [ ] a\n- [x] b`
    * parses to two `BulletListItem`s each holding a `TaskListMarker`, so the lowering returns
    * `NonEmptyVector(TaskList, TaskList)`, and a five-item list returns five. Without the
    * de-duplication the author reads "a task list; a task list". The de-duplication belongs
    * HERE, at the join, and never inside the lowering, whose vector stays complete for any
    * future consumer that wants the multiplicity. THE COST IS ACCEPTED DELIBERATELY: the author
    * is not told there were five. `Extractor.test.scala`'s "a multi-item task list is refused
    * once" is what pins it.
    *
    * ORDER IS DOCUMENT ORDER, which is a CHANGE. `bodyText` ran three independent
    * `collectFirst`s, so an embed anywhere beat a task list earlier in the document, and it
    * reported exactly one. This joins all of them in the order the author wrote them.
    */
  private def bodyBlocks(where: String, blocks: Vector[Block]): Either[SpecError, Vector[C.Block]] =
    C.Lower.blocks(blocks).left.map { refusals =>
      SpecError.UnsupportedContent(where, refusals.toVector.map(_.describe).distinct.mkString("; "))
    }

  // ══════════════════════════════════ THE LEDGER OF SURVIVING LAIKA TRAIT-MATCHES ════
  //
  // "The old walks are gone" is true of the RENDERING walks in this file and in `Cloze.scala`:
  // `bodyText`, `blockText`, `elementText` and `allSpans` are deleted, and with them the
  // three-way `collectFirst` priority, the `case _ => ""` catch-all, the `case sc:
  // SpanContainer` and `case ec: ElementContainer[?]` trait matches, and the known-false
  // `SpecError.UnsupportedEmbed("")` construction. It is NOT true of the project as a whole,
  // and writing it without this list would make it the eleventh untrue claim shipped here.
  //
  // FOUR TRAIT-MATCHES OVER LAIKA'S TYPES SURVIVE THIS SLICE:
  //
  //   1. `section.header.extractText`, above. `SpanContainer.extractText` is itself a trait
  //      match with a silent `case _ => ""` (`laika/ast/containers.scala:117-121`). This is the
  //      IDENTITY of every non-table card and it is the WORST-PLACED survivor: a silent drop
  //      here does not lose a word, it changes a KEY — which orphans a live synced note. Open
  //      defect, named at the call site, fixed in its own slice.
  //   2. `Tables.cellSource` — frozen by ruling, identity path. It may never route through
  //      `content.Lower` or `content.AsText`, because there a `Left` is not a loud error but an
  //      ABSENT KEY.
  //   3. `CellDisplay.Default` — DISPLAY path, movable in principle and BLOCKED in practice:
  //      `CellDisplay.text` is a total `Cell => String` and `Lower.cell` is partial, and the
  //      only permitted widening (a failure channel on `CellDisplay`) would change a shape that
  //      `extract/Tables.test.scala` pins at seven call sites — a file outside this slice.
  //   4. `walk`'s `case container: BlockContainer`, above, excluded with the reason written
  //      there: it hunts `Section`s structurally and cannot silently truncate a card's content.
  //
  // AND ONE CONSEQUENCE, STATED RATHER THAN LEFT FOR A REVIEWER TO FIND: a `#flashcard/table`
  // section is still walked TWICE after this slice — once by `Lower` for its refusals, once by
  // `Tables` for its cards. Do not write "one lowering" without that exception.


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
