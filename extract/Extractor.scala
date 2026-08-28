package obsidiananki.extract

import cats.data.NonEmptyVector
import laika.ast.*
import obsidiananki.content as C
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
  /** Does this document ask for any cards at all?
    *
    * ANSWERED FROM THE PARSED TREE, never by searching the raw text, and the difference is not
    * academic: this repository's own `How to write cards.md` shows every marker inside a fenced
    * code block, and a vault will collect more documents like it. A fence is not a `Section`,
    * so it cannot be mistaken for a marked heading here, where a textual scan for `#flashcard`
    * would report each example as a card the author had asked for and been denied.
    *
    * A heading whose marker is UNRECOGNISED counts as asking, deliberately. `#flashcard/2-way`
    * is a typo rather than prose, and the caller's whole job is to tell "this file wants cards"
    * apart from "this file is ordinary writing".
    */
  def hasMarkedHeading(root: RootElement): Boolean =
    def go(element: Element): Boolean = element match
      case section: Section =>
        Marker.parse(section.header.extractText).fold(_ => true, _.isDefined)
        || section.content.exists(go)
      case container: BlockContainer => container.content.exists(go)
      case _                         => false
    root.content.exists(go)

  /** WHAT A CARD OF THIS SHAPE ASKS THE REVIEWER TO PRODUCE, in raw display text.
    *
    * Only two shapes can ever ask for something a location also names, and this function is
    * that fact written down once:
    *
    *   - a TWO-WAY card, whose reverse blanks `{{Front}}` — the marked heading itself
    *   - a THREE-FIELD card, whose first card blanks `{{Concept}}` — the nearest ancestor
    *     heading, or the FILE NAME when the marked heading has no ancestor
    *
    * `3way/all` adds a third card blanking the DESCRIPTOR, which is the marked heading. That is
    * deeper in the location than the concept, so truncating at the concept already removes it —
    * but it is named here anyway, because the check is by VALUE: a folder sharing the marked
    * heading's name would otherwise slip through above the concept.
    *
    * Everything else asks for body prose, a cloze deletion, a list order, or a table cell. None
    * of those is a folder, a file or a heading, so nothing is at risk and the answer is empty.
    * That emptiness is a finding, not a gap.
    *
    * RAW, NEVER ESCAPED — see [[RecallText]]. The strings here are the same ones
    * `CardContext` receives unescaped, taken before `Html.escape` runs.
    */
  def recallFromLocation(
      marker: Marker,
      title: String,
      ancestorTitles: Vector[String],
      fileName: String,
  ): RecallText =
    val concept = ancestorTitles.lastOption.getOrElse(fileName)
    marker match
      case Marker.TwoField(TwoFieldDirections.Both)       => RecallText(Vector(title))
      case Marker.ThreeField(ThreeFieldDirections.All)    => RecallText(Vector(concept, title))
      case Marker.ThreeField(ThreeFieldDirections.Default) => RecallText(Vector(concept))
      // `ValueOnly` suppresses the concept-recall card entirely, so the concept is never the
      // answer. Unreachable from a heading today — no heading token selects it — but stated
      // rather than folded into the catch-all, because folding it in would make the reason
      // invisible if a heading token ever did.
      case Marker.ThreeField(ThreeFieldDirections.ValueOnly) => RecallText.none
      case Marker.TwoField(TwoFieldDirections.Forward)       => RecallText.none
      case Marker.Cloze | Marker.Sequence(_)                 => RecallText.none
      case Marker.Table(_, _)                                => RecallText.none

  /** THE WHOLE NOTE AS ONE CARD, for a note that carries a marker in its frontmatter and has no
    * headings at all.
    *
    * ==Why a note is a markable thing==
    *
    * A note is a tree of nodes and a card hangs off one of them. A heading is one kind of node,
    * the way a directory is one kind of filesystem entry; a note with no headings is a leaf, and
    * there is nothing else in it that could carry the marker. `Essential Numbers (for System
    * Design Interview).md` is three list items and a frontmatter tag — asking its author to add a
    * heading that restates the file name is ceremony, and the heading would be a worse name for
    * the card than the file already is.
    *
    * ==Why ONLY when there are no headings==
    *
    * A note that HAS headings and carries a `flashcard` tag in its frontmatter is the case
    * Obsidian's editor creates by accident: typing `#flashcard/2way` into the body lifts the tag
    * out of the text and files it under `tags`, leaving a note that looks marked and makes
    * nothing. That is already reported, and it must stay reported — so the frontmatter marker
    * means "this note is the card" exactly when there is no heading it could have fallen off.
    * The two cases are told apart by the note's own shape, which is decidable from the parse.
    *
    * ==Why it reuses the section builder rather than having its own==
    *
    * Every marker means the same thing here as it does on a heading, so every marker should be
    * built the same way. The note is handed to [[buildSpecs]] as a synthetic section whose title
    * is the file name and whose content is the whole body, which is why `cloze`, `sequence`,
    * `table`, `1way` and `2way` all work with nothing written for them. `cdd` is the exception
    * and is refused — see [[SpecError.WholeNoteCannotBeThreeField]].
    */
  def fromWholeNote(
      noteId: NoteId,
      fileName: String,
      filePath: String,
      marker: Marker,
      root: RootElement,
      // NO DEFAULT, AND `1` WOULD BE THE WRONG ONE. This is the file line the body starts at,
      // and every card's source reference is computed from it. `1` is correct only for a note
      // with NO frontmatter — and a note without frontmatter yields no cards at all, because
      // the `id:` is what makes a file eligible. So the default was wrong for every note this
      // tool has ever processed, and being wrong is SILENT: cards build, the run succeeds, and
      // "open this note at line N" simply lands in the wrong place. Removed 2026-08-28.
      bodyFirstLine: Int,
  ): ExtractedNote =
    val key = CardKey(noteId, CardPath.Note)
    val ref = SourceRef(filePath, bodyFirstLine, SourceKind.Heading)

    marker match
      // THE ONE SHAPE A NOTE CANNOT BE. See `SpecError.WholeNoteCannotBeThreeField`: three parts
      // are needed and a note has its name and its body. Matched before anything is built,
      // because the alternative is a card asking the file name about the file name.
      case Marker.ThreeField(_) =>
        ExtractedNote(
          Vector.empty,
          Vector(
            BuildFailure.KeyKnown(
              key,
              ref,
              describe(SpecError.WholeNoteCannotBeThreeField(fileName, "cdd")),
            )
          ),
        )

      case _ =>
        // A SYNTHETIC SECTION, so every other marker is built by exactly the code that builds it
        // for a heading. The header is never read by `buildSpecs` — it reads `section.content` —
        // but it is filled with the file name rather than left blank, so that anything reading
        // it later finds the note's own name rather than an empty string.
        val section = Section(Header(1, Seq(Text(fileName))), root.content)

        buildSpecs(key, marker, fileName, Vector.empty, section, fileName, foldersOf(filePath)) match
          case Left(err) => ExtractedNote(Vector.empty, Vector(BuildFailure.KeyKnown(key, ref, describe(err))))
          case Right(built) =>
            ExtractedNote(
              built.map { case (spec, src) =>
                SourcedSpec(spec, ref.copy(kind = src.kind, detail = src.detail), Vector.empty, RecallText.none)
              },
              Vector.empty,
            )

  private def foldersOf(filePath: String): Vector[String] =
    filePath.split('/').dropRight(1).toVector

  def fromDocument(
      noteId: NoteId,
      fileName: String,
      filePath: String,
      root: RootElement,
      // NO DEFAULT ON EITHER, AND THE EMPTY BODY WAS THE MORE DANGEROUS OF THE TWO. `body` is
      // the RAW SOURCE, and it feeds `ListIndent.scan` — the guard that reports when this
      // parser reads a list differently from Obsidian. A caller that omitted it would disable
      // that check and report no disagreements, which is indistinguishable from there being
      // none. `bodyFirstLine` is the file line the body starts at; see `fromWholeNote` above
      // for why `1` was the wrong default rather than merely an unstated one.
      body: String,
      bodyFirstLine: Int,
  ): ExtractedNote =
    val specs    = Vector.newBuilder[SourcedSpec]
    val failures = Vector.newBuilder[BuildFailure]
    val lines    = LineIndex(body, bodyFirstLine)

    // SCANNED ONCE PER NOTE, then asked per card. This reads the RAW SOURCE, which is the only
    // place the answer still exists — see `ListIndent` for why a parsed tree cannot be asked.
    //
    // GIVEN THE SAME ORIGIN AS `lines`, and that is not optional: a finding is looked up by the
    // heading line `lines` produced, so if the two counted from different origins every lookup
    // would miss and the check would silently never fire.
    val listIndent = ListIndent.scan(body, bodyFirstLine)

    // THE FOLDERS THIS NOTE SITS IN, for the breadcrumb. Derived here rather than passed in
    // because `filePath` is already a parameter and splitting it twice would be two answers to
    // one question — `Decks.sourceFor` does the same split for the deck path.
    val folders = filePath.split('/').toVector.dropRight(1)

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
                  CardKey(noteId, CardPath.Headings(HeadingPath(NonEmptyVector.fromVectorUnsafe(path)))),
                  ref,
                  s"unusable marker: $err",
                )

              case Right(None) => () // ordinary prose section — an ancestor, not a card

              case Right(Some(marker)) =>
                val key = CardKey(noteId, CardPath.Headings(HeadingPath(NonEmptyVector.fromVectorUnsafe(path))))

                // CHECKED BEFORE THE CARD IS BUILT, not after. Once built, the card looks fine:
                // the regrouped list is well-formed markdown and renders without complaint, so
                // there is nothing downstream left to notice. This is the last point at which
                // the evidence — the source lines — is still in hand.
                //
                // A card whose body has this is refused ALONE. Its siblings under other
                // headings in the same file are unaffected and still sync, because ownership is
                // per-heading; a whole file is not punished for one bad section.
                val ambiguousNesting = listIndent.under(ref.line)

                if ambiguousNesting.nonEmpty then
                  val why =
                    SpecError.ListNestingUnreadable(
                      key.path.render,
                      ListIndent.explain(ambiguousNesting),
                    )
                  failures += BuildFailure.KeyKnown(key, ref, describe(why))
                else
                  buildSpecs(key, marker, title, ancestorTitles, section, fileName, folders) match
                    case Right(built) => built.foreach { case (spec, src) =>
                        specs += SourcedSpec(
                          spec,
                          ref.copy(kind = src.kind, detail = src.detail),
                          // `nextTitles`, NOT `ancestorTitles`: the chain a deck is built from
                          // ends AT the marked heading, so the deck a `#flashcard` heading
                          // produces is named after that heading. It is the same vector the
                          // recursive descent below passes on, so a card and the cards under
                          // it agree about where they sit.
                          sectionTitles = nextTitles,
                          // COMPUTED HERE BECAUSE HERE IS WHERE THE RAW TEXT STILL EXISTS.
                          // `title` and `ancestorTitles` are unescaped at this point; by the
                          // time they reach a `CardSpec` they have been through `Html.escape`,
                          // and an escaped concept can never match the heading a deck path is
                          // built from.
                          recall = recallFromLocation(marker, title, ancestorTitles, fileName),
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
    case SpecError.WholeNoteCannotBeThreeField(file, marker) =>
      s"'$file' asks in its frontmatter to be a whole-note '$marker' card, but a " +
        "concept-descriptor card needs three parts and a note has two — its name and its body. " +
        "Put the marker on a heading, which supplies the third, or ask for a shape that needs two"
    // THE MESSAGE NAMES `==<<…>>==`, NOT `==…==`, SINCE 2026-08-28. A plain highlight is no
    // longer a cloze, so telling an author their section has "no ==highlight==" would send
    // somebody who wrote plenty of them looking for the ones they can see.
    case SpecError.ClozeWithoutDeletions(p) =>
      s"cloze section with no ==<<highlight>>== at '$p' — a bare ==highlight== is emphasis now"
    case SpecError.AmbiguousClozeDeletion(p, t) =>
      s"two unlabelled '==<<$t>>==' highlights at '$p' cannot be told apart — label them, " +
        s"e.g. ==<<1|$t>>=="
    case SpecError.TableWithoutTable(p)      => s"table marker with no table at '$p'"
    case SpecError.TableWithoutDescriptors(p, what) =>
      s"table at '$p' yields no cards: $what"
    case SpecError.UnsupportedContent(p, what) => s"$what, at '$p'"
    case SpecError.SequenceWithoutItems(p, what) =>
      s"#flashcard/sequence at '$p' asks for a list revealed one item at a time, but $what — " +
        s"write the items as a list, or use #flashcard/1way or #flashcard/2way if the answer " +
        s"is meant to be shown whole"
    case SpecError.ListNestingUnreadable(p, what) => s"$what, at '$p'"
    case SpecError.TableRowsWithoutRows(p, what) =>
      s"#flashcard/table/rows at '$p' asks only for whole-row cards, but $what — a row card " +
        "needs two or more descriptor columns, since with one it would merely duplicate that " +
        "row's single cell card. Drop '/rows' to get the cell cards instead"

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
      folders: Vector[String],
  ): Either[SpecError, Vector[(CardSpec, RowSource)]] =
    val where = key.path.render

    // EVERYWHERE THIS CARD CAME FROM, in the order the parts nest. Each arm below hands this
    // to `CardContext.compose` together with the strings ITS OWN card already carries as
    // fields, and compose removes them — a segment on the question side being redundant and
    // one on the answer side being a spoiler.
    //
    // THE FILE NAME IS IN HERE, which it never used to be, and that is the whole fix: a note
    // whose marked heading is its H1 has no ancestor, so a breadcrumb built from headings
    // alone rendered EMPTY. `System Design Pattern.md` holding `# 3 Components` produced a
    // card asking "3 Components" with nothing on it saying three components OF WHAT.
    val location = folders ++ Vector(fileName) ++ ancestorTitles :+ title
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
    // THE BREADCRUMB A TABLE'S CARDS SHOW, computed HERE and passed down, because the rule is
    // the caller's and not `Tables`'. A table card's face shows the row cell and the column
    // header — never the marked heading and never its ancestors — so nothing above it is
    // already on the card and the whole chain INCLUDING this heading survives. Contrast the
    // heading-derived three-field arm below, which must drop one segment because that segment
    // IS the Concept.
    // THE WHOLE LOCATION, WITH NOTHING EXCLUDED. A table card's fields are a row cell and a
    // column header — never a folder, a file name or a heading — so no location segment is on
    // the card and every one of them is context worth having.
    val tableContextTitles = location

    for
      lowered <- bodyBlocks(where, ownBody(section))

      // ── WHICH BLOCKS THIS CARD IS MADE OF, CHOSEN BEFORE ANYTHING RENDERS THEM ──────
      //
      // FOR EVERY MARKER BUT ONE THIS IS THE HEADING'S OWN BODY, and `blocks` is `lowered`.
      // `#flashcard/sequence/headers` is the exception: it asks for STRUCTURE, and RULED BY
      // MARC 2026-08-28 prose is not structure, so its card is the OUTLINE ALONE rather than
      // the outline added to the body. A lead-in sentence under such a heading is note
      // content that belongs to a different card — the two-field markers already make cards
      // out of a heading and its prose — not material for this one.
      //
      // IT HAPPENS HERE, ABOVE THE B6 GATE, AND THAT POSITION IS THE WHOLE POINT. A heading
      // whose content is entirely subheadings has an EMPTY own-body, which is the ORDINARY
      // shape of this marker rather than an edge case. Had the choice been made in the marker
      // match below, two things would already have gone wrong: B6 would have refused the card
      // as an empty body, and — worse — `body` is bound between here and there with a
      // `sys.error` fallback whose argument for being unreachable ASSUMES B6 refused first.
      // Reaching that arm with an empty body would have CRASHED rather than refused. Choosing
      // the blocks first means no gate is weakened and no fallback is undermined: the card
      // genuinely has content, and every rule below judges it on the content it has.
      //
      // THE REFUSAL FOR "NO SUBHEADINGS" IS A PARSE, NOT A CHECK. `Outline.read` is total and
      // an empty result is a legitimate answer from it; turning that into a `NonEmptyVector`
      // here is the single place emptiness is decided, and `Outline.render` cannot be asked
      // the question afterwards because it does not accept the empty case.
      blocks <- marker match
        case Marker.Sequence(SequenceSource.ChildHeadings(reach)) =>
          NonEmptyVector
            .fromVector(Outline.read(section, reach))
            .toRight(SpecError.SequenceWithoutItems(where, "the heading has no subheadings"))
            .map(nodes => Vector(Outline.render(nodes)))
        case _ => Right(lowered)

      // ── THE RULE, AND THEN THE INVARIANT. TWO BINDINGS, IN THIS ORDER. ────────────────
      //
      // BOTH RENDERINGS ARE COMPUTED AHEAD OF THE MARKER MATCH, AND BOTH ARE DISCARDED ON THE
      // CLOZE PATH. That reads like dead code and is not: `EmptyBody` must keep firing AHEAD
      // of the cloze branch, so a cloze section whose body renders to nothing is reported as
      // an empty body rather than as a cloze section with no highlight. Moving either below
      // the marker match changes which error an author reads.
      text = C.AsText.plain(blocks)

      // B6 GATES ON THE PLAIN TEXT, NEVER ON THE HTML — and the reason is this codebase's own,
      // written at `CellDisplay` above `Tables.scala`'s object: A RENDERER CAN NEITHER MINT NOR
      // DESTROY A CARD.
      //
      // The divergence is REACHABLE, not hypothetical. `content/Lower.scala:428` lowers an
      // `ObsidianComment` to zero inlines, so a body of `%%a%% %%b%%` lowers to
      // `Paragraph(Vector(Inline.Text(" ")))`. `AsText` kills that through its trailing `.trim`;
      // `AsHtml` renders `<p> </p>`, eleven non-blank characters, and
      // `Body.fromExtracted`'s `raw.trim.isEmpty` (`model/CardSpec.scala:24-26`) does NOT fire
      // on it — the `.trim` is a no-op there because the string begins with `<`. Gating on the
      // HTML would silently retire B6 for that input and, for a `2way` marker, ship one card
      // where the marker promised two. `Extractor.test.scala`'s "B6 is decided on the plain
      // text" is what pins this; no `dummy-vault` note contains that body.
      _ <- Body.fromExtracted(text).toRight(SpecError.EmptyBody(where))

      html = C.AsHtml.plain(blocks).render

      // AN ASSERTION, NOT A SECOND REFUSAL, AND NOT CLAIMED UNREACHABLE.
      //
      // Why not a second `SpecError.EmptyBody`: that error is the AUTHOR's channel and teaches
      // exactly one lesson — "this marked heading has no prose of its own, because a subheading
      // follows it". If the gate above passed, the author's source is not empty, there is
      // nothing they can do, and a second `EmptyBody` sends them hunting for prose they already
      // wrote. `sys.error` is the shape `Cloze.render`'s `misaligned` already uses in this
      // layer for the same kind of statement.
      //
      // IT IS ONE-DIRECTIONAL, WHICH IS WEAKER THAN UNREACHABLE AND IS SAID THAT WAY ON
      // PURPOSE — this project has already shipped a comment asserting a branch unreachable
      // that was not. The argument: `AsHtml` empty ⇒ every block contributed nothing ⇒ every
      // block's inner rendered empty ⇒ `AsText`'s per-block text was empty for all of them ⇒
      // the gate above already left with a `Left`. The only mechanism `AsText` has that
      // `AsHtml` lacks is that trailing `.trim`, and it diverges in the OTHER direction
      // (AsText-empty while AsHtml is not) — which is precisely why the gate reads `AsText`.
      // EVIDENCE, and it is evidence rather than a type-level proof:
      // `content/AsHtml.test.scala`'s B6 aggregate asserts
      // `AsHtml.plain(bs).render.isEmpty == AsText.plain(bs).isEmpty` over hand-built values.
      body = Body
        .fromExtracted(html)
        .getOrElse(
          sys.error(
            s"card body rendering disagreed at '$where': the plain-text rendering is non-empty " +
              s"(${text.length} chars) while the HTML rendering is blank (${html.length} chars). " +
              s"B6 was decided on the plain text and passed, so this is a renderer disagreement, " +
              s"not an empty body."
          )
        )

      spec <- marker match
        case Marker.TwoField(directions) =>
          // ESCAPED HERE, IN THE ARGUMENT POSITION, AND NOWHERE UPSTREAM. See the note below
          // the `ThreeField` arm — rebinding `title` is the highest-consequence mistake
          // available in this function.
          // THE FRONT IS THE MARKED HEADING, and it is the only location segment this card
          // carries as a field. Folders, the file name and every ancestor above it are context
          // the card does not otherwise have — and for a note whose marked heading is its H1,
          // the file name is the ONLY thing naming the subject.
          Right(
            Vector(
              CardSpec.TwoField(
                key,
                C.Html.escape(title).render,
                body,
                directions,
                CardContext.compose(location, Vector(title)),
              ) -> RowSource.heading
            )
          )

        case Marker.ThreeField(directions) =>
          // The concept is the NEAREST ancestor heading, or the filename when the marked
          // heading has no ancestor at all.
          //
          // WHY THE ESCAPING HAPPENS IN THESE ARGUMENT POSITIONS AND NOWHERE ELSE. `rawHeading`
          // (`:57`) and `title` (`:68`) feed BOTH identity and display: `HeadingSegment
          // .fromExtractedText(rawHeading)` and `Marker.stripMarker(rawHeading)` are two
          // separate calls on the same raw string eight lines apart, and display and identity
          // have already forked there. Escaping upstream of that fork would move card KEYS —
          // the golden's Quorums key carries `>` as `%3e` INSIDE the key
          // (`…what%20does%20w%20%2b%20r%20%3e%20n%20buy%20you%3f`), which would become
          // `%26gt%3b`: one orphan plus one history-less card for every heading containing any
          // of the six escaped characters. `title` must likewise not be escaped before it feeds
          // `nextTitles`, or the escape reaches descendants twice over.
          //
          // WITH THE TABLE CELLS ABOVE, THIS MAKES THE RULE COMPLETE AND GREPPABLE: every
          // String that becomes an Anki field value is escaped at its construction site in
          // `extract/`, with exactly ONE named exception — `CardSpec.fields`'s `TableRow` arm
          // builds that card's Back as `s"$d: $v"` joined with `"\n"` inside `model/`,
          // which imports nothing from `content/`. That one is not escaped and not rendered;
          // it is an open question, not an oversight.
          val concept = ancestorTitles.lastOption.getOrElse(fileName)

          // CONTEXT DROPS THE LAST ANCESTOR, AND THAT IS NOT A TIDINESS RULE. The last
          // ancestor is bound one line above as the Concept, which Card 1 of this note type
          // asks the reviewer to RECALL. Printing it in the breadcrumb would print the answer
          // on the question side of that card. So the breadcrumb carries everything ABOVE the
          // concept and stops there.
          //
          // AN EMPTY CHAIN IS THE ORDINARY RESULT FOR A `##` HEADING DIRECTLY UNDER AN H1 —
          // five of the fixture vault's fifty-five cards. It is also what an ancestorless
          // heading produces, where the concept fell back to the file name: `dropRight` on an
          // empty vector is empty, so no branch is needed and none is written.
          //
          // WHAT THE MOTIVATING CARD BECOMES: heading path
          // `Body shapes / Cranial bones and their sutures / Frontal / Anterior border` was
          // showing `Concept: Frontal, Descriptor: Anterior border` and nothing else. It now
          // carries `Body shapes › Cranial bones and their sutures` above the prompt.
          Right(
            Vector(
              CardSpec.ThreeField(
                key,
                C.Html.escape(concept).render,
                C.Html.escape(title).render,
                body,
                directions,
                // TWO FIELDS CARRY LOCATION SEGMENTS HERE — the concept, which is the nearest
                // ancestor heading or the FILE NAME when there is none, and the descriptor,
                // which is the marked heading. Both are excluded by value rather than by
                // position, which is what makes the file-name case work: when the concept came
                // from the file name, naming it removes it wherever it sits.
                CardContext.compose(location, Vector(concept, title)),
                // EMPTY, AND THAT IS NOT AN OMISSION. The label names what KIND of thing the
                // concept is, and only a table has anywhere to say it: its first column's header.
                // Here the concept is an ancestor HEADING, which names the thing itself and never
                // its kind. The templates guard on the field, so empty renders nothing at all.
                "",
              ) -> RowSource.heading
            )
          )

        // A CLOZE CARD'S FACE IS THE BODY TEXT ALONE — the heading is nowhere on it — so the
        // breadcrumb carries the chain INCLUDING this heading.
        //
        // THE RESIDUAL, NAMED RATHER THAN HIDDEN: a heading that NAMES what its own body
        // blanks would now print the answer on the question side. All five cloze headings in
        // the fixture vault were checked against their bodies on 2026-08-21 and none leaks —
        // "The three layers, blanked", "Bones of the forearm", "Bones of the hand, in two
        // parts", "Anatomy of a long bone", "Cells that remodel bone". Five data points are
        // evidence, not a guarantee; the mitigation is an authoring rule of the kind
        // `CARD-MODEL.md` already states for `3way` headings, and the alternative — dropping
        // the heading for cloze alone — costs the largest context gain in the design.
        case Marker.Cloze =>
          Cloze
            // NOTHING EXCLUDED. A cloze note's fields are its text and its extra — the marked
            // heading is not among them, so the whole location survives, this heading included.
            // `lowered` AND NOT `blocks`, ON PURPOSE. They are equal for every marker
            // except the heading-sourced sequence, so this reads as an arbitrary choice and
            // is not: a cloze card is about the author's PROSE. Pinning it to the body means
            // a marker added later that redefines `blocks` cannot silently change what a
            // cloze card is made of.
            .fromLowered(key, lowered, CardContext.compose(location, Vector.empty))
            .map(c => Vector(c -> RowSource.heading))

        // THE ONE AND ONLY CALL SITE, and a demonstration of this project's own thesis.
        //
        // An `if marker == Marker.Table` once stood above this `for`, and this arm carried a
        // comment calling itself UNREACHABLE. The comment was true when written and false from
        // `6a494e7` onward: that commit gave `Marker.Table` PARAMETERS, so a bare `Marker.Table`
        // became the companion OBJECT and the comparison became permanently false. The compiler
        // forced THIS ARM to be updated in that same diff and said nothing about the `if` eleven
        // lines above — the match got exhaustiveness, the `if` got silence.
        //
        // So the guard was deleted rather than repaired. Repairing it would hardcode
        // `Default, Both` and retire every `/1way`, `/3way`, `/cells` and `/rows` token the
        // README advertises, with no test to notice.
        case Marker.Table(directions, scope) =>
          Tables.fromSection(key, section, CellDisplay.Escaped, tableContextTitles, directions, scope)

        // ── A HEADING'S SUBHEADINGS AS THE SEQUENCE — NOT BUILT ─────────────────────────
        //
        // THE HOLE IS DELIBERATE, AND THIS ARM EXISTS SO THE COMPILER HOLDS THE PLACE. Filed
        // as `IN-FLIGHT.md` item 28. What it must do is understood: synthesise the same
        // `Block.Bullets` a hand-written list would have lowered to, from this section's child
        // headings, so `AsHtml` emits the `<li>` elements the note type's `#text li` selector
        // already hides. No new rendering code, exactly as the arm below added none.
        //
        // WHY IT IS NOT A ONE-LINE SUBSTITUTION, which is the finding worth leaving here:
        // `body` is bound ABOVE this match by rendering `lowered`, and its `sys.error` fallback
        // is justified by an argument that ASSUMES the B6 gate above already refused an empty
        // body. A heading whose content is entirely subheadings HAS an empty own-body — that is
        // the ordinary shape of this marker, not an edge case — so reaching this arm with the
        // current bindings would crash rather than refuse. The blocks a card renders from must
        // therefore be chosen BEFORE that gate, which is a change to the shared pipeline rather
        // than to any one arm.
        case Marker.Sequence(source) =>
          // ── THE REFUSAL, AND THE ONE PLACE IN THIS PROJECT THAT GATES ON A RENDERER ──────
          //
          // TWO PARTS TO THE PREDICATE, and the second is an EXTENSION of the ruling rather
          // than an application of it. The ruling covers "a marker asking for a list over a
          // body that has none". The second part covers "a body whose list has items and every
          // one of them renders empty", and it is measured rather than imagined:
          // `content/Lower.scala:428` lowers an Obsidian comment to ZERO inlines, so
          // `- %%not ready yet%%` becomes `Item(Vector(Block.Paragraph(Vector())))`;
          // `AsHtml`'s item arm filters that empty item out of the `<li>` concat and its
          // `wrap` then drops the whole `<ul>`. Such a body PASSES B6 (the lead-in paragraph
          // carries it), PASSES `Body.fromExtracted` on the HTML, and PASSES a presence-only
          // check — and ships a note whose Text holds zero `li`, which
          // `resources/note-types/cloze-sequence/templates/cloze-sequence.front.html:11` then hides none
          // of. Silent success.
          //
          // THE ORACLE IS `AsHtml`, NOT `AsText`, AND THE EQUIVALENCE IS EXACT RATHER THAN
          // APPROXIMATE. `AsHtml.plain(item.blocks).isEmpty` computes the identical expression
          // to the emptiness `AsHtml`'s own item arm tests before deciding whether to emit an
          // `<li>`: in the tight arm both sides are `inlines(spans, Plain)`, and in the general
          // arm both sides are literally the same filtered concat. The rendered STRINGS differ;
          // the EMPTINESS coincides, and emptiness is all this predicate reads. An `AsText`
          // oracle would additionally refuse an item that renders to whitespace only — which
          // `AsHtml` emits as a real `<li>` that reveals perfectly well — i.e. a false refusal
          // of a working card, through the trailing-`.trim` divergence nobody has ruled on.
          //
          // THIS COUPLES A REFUSAL TO A RENDERER, WHICH WILL READ AS VIOLATING A RULE THIS
          // CODEBASE STATES, so both halves of the honest rule are written here, next to each
          // other, or a later reader "fixes" one into the other. `Tables.scala` says A RENDERER
          // CAN NEITHER MINT NOR DESTROY A CARD, and taken literally that forbids this: a
          // change to `AsHtml`'s empty-item rule would change which sections refuse. Taken
          // DELIBERATELY, because the structural alternative is worse — a presence-only check
          // lets the renderer destroy the card's CONTENT while the refusal certifies it fine.
          // THE HONEST RESTATEMENT: GATE ON ONE NAMED RENDERER, AND ON THE ONE THAT PRODUCES
          // THE FIELD IN QUESTION. B6 asks about the body AS TEXT and gates on `AsText`, for
          // the reasons written above at that gate; this asks about a property of the field
          // the note type's `#text li` selector consumes, so it gates on `AsHtml`.
          //
          // B6 STILL FIRES FIRST, FREE, AND THAT ORDERING IS DELIBERATE: the B6 gate is above,
          // ahead of this match, so a `#flashcard/sequence` heading immediately followed by a
          // subheading reads "empty body" — the more actionable error, since the fix is to
          // write prose — and never "no list".
          //
          // A REFUSAL HERE CANNOT ORPHAN A LIVE NOTE, and the mechanism is load-bearing rather
          // than incidental: `walk` records every `buildSpecs` `Left` as `BuildFailure.KeyKnown`
          // AT THE SECTION KEY, `VaultScan` collects those into `failedKeys`, and
          // `Planner:211`'s `accountedFor = builtKeys ++ failedKeys` therefore claims this key,
          // so the live note is never sent to `SyncAction.Flag`. THE INVARIANT THAT BUYS IT:
          // ONE MARKED SECTION, ONE CARD, KEYED AT THE SECTION KEY. `Marker.Table` lacks that
          // property and pays for it — see the hole documented above. The moment anyone emits
          // per-item cards at deeper keys, and "one card per list item" is the obvious thing a
          // future reader proposes, that hazard reappears verbatim on the marker whose refusal
          // fires most often.
          // ONE ARM FOR BOTH SOURCES, WHICH IS THE CLAIM THIS FEATURE WAS BUILT ON. By the
          // time control reaches here the difference between them has already been spent:
          // `blocks` holds either the body's list or the outline, and everything from this
          // point — the refusal, the rendering, the note type, the fields — is the same code
          // it always was. If this ever needs to branch on `source` for anything but a
          // sentence, "a new source, not a new card" has stopped being true.
          // WHICH ORDER THE CARD REVEALS IN, decided here and CARRIED as a field rather than
          // acted on. Nothing in this tool reorders anything: both orders produce the same
          // nested list, and which item the reveal key uncovers next is the note type's
          // template's decision at review time.
          //
          // A FLAT LIST IS DEPTH-FIRST BY DEFINITION, not by default — with no levels the two
          // orders name the same sequence, so this is a statement of fact rather than a choice
          // standing in for a missing one. `HeadingReach.DirectChildren` cannot carry an order
          // for exactly that reason.
          val reveal = source match
            case SequenceSource.BodyList                                    => RevealOrder.DepthFirst
            case SequenceSource.ChildHeadings(HeadingReach.DirectChildren)  => RevealOrder.DepthFirst
            case SequenceSource.ChildHeadings(HeadingReach.WholeSubtree(o)) => o

          val items     = sequenceItems(blocks)
          val surviving = items.filter(item => !C.AsHtml.plain(item.blocks).isEmpty)
          if surviving.isEmpty then
            val what =
              // NAMED FOR THE SOURCE THE AUTHOR ACTUALLY WROTE. A reader who marked a
              // heading for its subheadings is not helped by a sentence about "the body's
              // list", and the fix each one needs is different.
              source match
                case SequenceSource.BodyList =>
                  if items.isEmpty then s"the body holds no list — it holds ${describeBlocks(blocks)}"
                  else s"the body's list has ${items.size} items and every one of them renders empty"
                // The no-subheadings case never reaches here — it is refused above, where the
                // blocks are chosen. What DOES reach here is subtler and real: every subheading
                // was marker-only, so each title is empty once the marker is stripped and the
                // renderer drops every item.
                case SequenceSource.ChildHeadings(_) =>
                  s"the heading has ${items.size} subheadings and every one of them is empty " +
                    "once its marker is removed"
            Left(SpecError.SequenceWithoutItems(where, what))
          else
            // THE TITLE IS ESCAPED IN THE ARGUMENT POSITION AND NOWHERE UPSTREAM — see the
            // note under the `ThreeField` arm, which spells out why with the measured witness
            // rather than repeating it here. Doing it here keeps that rule complete and
            // greppable with exactly its ONE existing exception; a rule with two is not a rule.
            //
            // THE TITLE IS NON-EMPTY WHENEVER THIS SPEC EXISTS, and that is DERIVED rather
            // than assumed: `HeadingSegment.fromExtractedText` and `Marker.stripMarker` apply
            // the same marker regex and then trim, so they are empty on exactly the same
            // inputs — and a heading that reached `buildSpecs` already produced a segment
            // (`model/CardKey.test.scala:47` pins that a marker-only heading is a `Left`).
            // THAT IS ALL THE TOOL GUARANTEES, and no sentence here says anything about what
            // Anki does with the field: nobody has installed this note type.
            //
            // `text` IS THE WHOLE RENDERED BODY — the `body` binding computed above, reused.
            // THIS ARM ADDS ZERO RENDERING CODE, which is the strongest fact in the slice:
            // `AsHtml` already emits `<ul><li>` for `Block.Bullets` and `<ol><li>` for
            // `Block.Numbered`, and the cloze-sequence back template's `:15` selects
            // `#text li`, agnostic to which. The field this note type wants is byte-for-byte
            // the `Body` this branch already builds.
            //
            // CONTEXT IS THE ANCESTOR CHAIN WITHOUT THIS HEADING, exactly as for the two-field
            // arm and for the same reason: `Title` IS this heading, printed on the question
            // side by the note type's own `<h4>{{Title}}</h4>`, so repeating it in the
            // breadcrumb would print it twice.
            Right(
              Vector(
                CardSpec.Sequence(
                  key,
                  C.Html.escape(title).render,
                  body,
                  // The marked heading IS a field here — the template renders `{{Title}}` above
                  // the list — so it is excluded and everything above it kept.
                  CardContext.compose(location, Vector(title)),
                  reveal,
                ) -> RowSource.heading
              )
            )
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

  /** Every list item in a body, in document order — the raw material of a sequence card.
    *
    * A FULL EXHAUSTIVE MATCH, five arms written longhand, rather than an `.exists` with a
    * catch-all. `-Wconf:msg=exhaustive:e` is live, so when a sixth `Block` constructor arrives
    * the compiler ASKS whether it is a list instead of silently answering "no" — which is the
    * difference between a new constructor being considered and a card quietly ceasing to
    * exist.
    *
    * TOP-LEVEL ONLY, AND THE RESTRICTION IS NOT REAL. A list cannot occur inside a table cell
    * at all: `content.Cell` holds `Vector[Inline]`, so that is unrepresentable rather than
    * merely unhandled. The only other place a list can nest is inside an `Item` — i.e. already
    * inside a list, and therefore already counted: if an inner item survives rendering then
    * its outer item's inner is non-empty, so the outer survives too.
    */
  private def sequenceItems(blocks: Vector[C.Block]): Vector[C.Item] = blocks.flatMap {
    case C.Block.Bullets(items)  => items
    case C.Block.Numbered(items) => items
    case C.Block.Paragraph(_)    => Vector.empty
    case C.Block.Code(_, _)      => Vector.empty
    case C.Block.Table(_, _)     => Vector.empty
  }

  /** A body's block shapes, in the AUTHOR'S vocabulary — the "what is there" half of a
    * refusal that has to name both halves.
    *
    * `.distinct` FOLLOWS `bodyBlocks`'s PRECEDENT AND CARRIES ITS COST: the author is told
    * there is "a paragraph", never that there were three. Accepted for the same reason it was
    * accepted there — "a paragraph, a paragraph, a paragraph" reads as a bug in the tool.
    */
  private def describeBlocks(blocks: Vector[C.Block]): String =
    blocks.map {
      case C.Block.Paragraph(_) => "a paragraph"
      case C.Block.Bullets(_)   => "a bulleted list"
      case C.Block.Numbered(_)  => "a numbered list"
      case C.Block.Code(_, _)   => "a code block"
      case C.Block.Table(_, _)  => "a table"
    }.distinct.mkString(", ")

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
  //   3. `CellDisplay` — the DISPLAY path. Production injects `CellDisplay.Escaped` since S11;
  //      `CellDisplay.Default` is still on that path as `Escaped`'s inner factor, and its trait
  //      match is what survives here. PART OF THIS MOVED AND PART IS STILL BLOCKED, and the
  //      reason recorded here before S11 was WRONG rather than merely stale, so it is replaced
  //      and not amended:
  //
  //        MOVED — ESCAPING. `CellDisplay.Escaped` composes `Html.escape` over `Default`. It is
  //        a total `String => String`; escaping needs no lowering at all, so the old reason
  //        ("`CellDisplay.text` is total while `Lower.cell` is partial, therefore the only
  //        permitted widening is a failure channel") was not binding and never had been. That
  //        wrong reason is still load-bearing prose at `content/Lower.scala:128-131`, which
  //        this slice may not edit; left uncorrected it invites a future author to "unblock"
  //        the rest by adding a failure channel nobody needs.
  //
  //        STILL BLOCKED — A CELL'S BLOCK STRUCTURE. Bold, inline code and any block shape
  //        inside a cell still flatten. Both honest routes to fixing that cross a file outside
  //        these slices: rendering inside `Tables.scala` reddens `extract/Tables.test.scala`,
  //        and routing cells through `content/` needs `content/AsText.test.scala`'s live cell
  //        differential widened. THE ROUTE THAT COMPILES GREEN IS THE DANGEROUS ONE: mutating
  //        `CellDisplay.Default` in place passes everything silently, because no fixture cell
  //        contains a special character, so that differential would go on passing while it
  //        stopped comparing what its name says it compares.
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
  *
  * ==Why the comparison happens twice==
  *
  * _Amended 2026-08-27, from a defect found by running `locate` over every card in Marc's
  * collection: one of twenty-nine resolved to no line._
  *
  * The needle is a heading's EXTRACTED text and the haystack is RAW SOURCE, so the two agree
  * only while the heading carries no inline markup. `# ==3== Components #flashcard/cdd/1way`
  * extracts to `3 Components #flashcard/cdd/1way` and never equals its own line. The answer was
  * 0, which reads downstream as "no position" and prints a file name with no line — a heading
  * that is harder to find reported as one that cannot be found at all. Bold, wikilinks, inline
  * code and maths all do it, and all four already occur in headings in that vault.
  *
  * So a candidate line is now compared BOTH raw and extracted. The raw comparison is kept and
  * kept FIRST: it is exact for every heading that already worked, so no line that resolves today
  * can move. The extracted comparison is reached only when the raw one misses, and it obtains
  * its text by handing the line back to THE SAME PARSER the document came through — not by
  * stripping markup here, which would be a second implementation of extraction and would drift.
  */
/** @param bodyFirstLine
  *   the line of the ORIGINAL FILE that `body` starts on. Without it every position reported by
  *   this class is counted from the top of the BODY and is therefore short by the length of the
  *   frontmatter — a number that is printed as `Note.md:15` and reads as a file position while
  *   not being one. See [[obsidiananki.extract.SplitNote]] for the measurement.
  */
private final class LineIndex(body: String, bodyFirstLine: Int):
  private val lines  = body.linesIterator.toVector
  private var cursor = 0

  /** Every heading line's text as the extractor sees it, computed once.
    *
    * Lazy and per-line rather than per-lookup: a note with many headings and many cards would
    * otherwise re-parse each candidate for every card. Non-heading lines are never parsed.
    */
  private lazy val extractedHeadings: Vector[Option[String]] =
    lines.map(l => if l.startsWith("#") then extractedText(l) else None)

  /** One line's heading text, obtained from the real parser rather than by stripping markup.
    *
    * The line is parsed COMPLETE WITH ITS `#` PREFIX, so the parser sees a heading rather than a
    * paragraph. A line that fails to parse, or parses to something that is not a heading, simply
    * yields nothing and falls back to the raw comparison.
    */
  private def extractedText(line: String): Option[String] =
    ObsidianSyntax.markupParser
      .parse(line)
      .toOption
      .flatMap(
        _.content.content.collectFirst {
          case s: Section => s.header.extractText.trim
          case h: Header  => h.extractText.trim
        }
      )

  def lineOf(headingText: String): Int =
    val needle = headingText.trim
    if needle.isEmpty then 0
    else
      val found = lines.indices.drop(cursor).find { i =>
        val line = lines(i)
        line.startsWith("#") &&
        (line.dropWhile(_ == '#').trim == needle || extractedHeadings(i).contains(needle))
      }
      found match
        case None => 0
        case Some(i) =>
          cursor = i + 1
          i + bodyFirstLine // the file's own line number, as an editor counts it
