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
      bodyFirstLine: Int = 1,
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
    case SpecError.SequenceWithoutItems(p, what) =>
      s"#flashcard/sequence at '$p' asks for a list revealed one item at a time, but $what — " +
        s"write the items as a list, or use #flashcard/1way or #flashcard/2way if the answer " +
        s"is meant to be shown whole"
    case SpecError.ListNestingUnreadable(p, what) => s"$what, at '$p'"

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
    // THE BREADCRUMB A TABLE'S CARDS SHOW, computed HERE and passed down, because the rule is
    // the caller's and not `Tables`'. A table card's face shows the row cell and the column
    // header — never the marked heading and never its ancestors — so nothing above it is
    // already on the card and the whole chain INCLUDING this heading survives. Contrast the
    // heading-derived three-field arm below, which must drop one segment because that segment
    // IS the Concept.
    val tableContextTitles = ancestorTitles :+ title

    if marker == Marker.Table then
      bodyBlocks(where, ownBody(section))
        // The SAFETY walk, which runs for a table section too and only needs to reach the
        // refusals — the directions it passes are irrelevant to that and never reach a card.
        .flatMap(_ =>
          Tables.fromSection(key, section, CellDisplay.Escaped, tableContextTitles, ThreeFieldDirections.Default)
        )
    else for
      lowered <- bodyBlocks(where, ownBody(section))

      // ── THE RULE, AND THEN THE INVARIANT. TWO BINDINGS, IN THIS ORDER. ────────────────
      //
      // BOTH RENDERINGS ARE COMPUTED AHEAD OF THE MARKER MATCH, AND BOTH ARE DISCARDED ON THE
      // CLOZE PATH. That reads like dead code and is not: `EmptyBody` must keep firing AHEAD
      // of the cloze branch, so a cloze section whose body renders to nothing is reported as
      // an empty body rather than as a cloze section with no highlight. Moving either below
      // the marker match changes which error an author reads.
      text = C.AsText.plain(lowered)

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

      html = C.AsHtml.plain(lowered).render

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
          // CONTEXT IS THE WHOLE ANCESTOR CHAIN. This card's face is the heading itself, so
          // every ancestor above it is absent from the card and none of them is the answer.
          Right(
            Vector(
              CardSpec.TwoField(
                key,
                C.Html.escape(title).render,
                body,
                directions,
                CardContext.render(ancestorTitles),
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
                CardContext.render(ancestorTitles.dropRight(1)),
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
            .fromLowered(key, lowered, CardContext.render(ancestorTitles :+ title))
            .map(c => Vector(c -> RowSource.heading))

        // UNREACHABLE WHILE THE `if marker == Marker.Table` ABOVE STANDS, and compiled only
        // because an inexhaustive match is a build error here. It is given the same argument
        // the live call site is given, so a future "simplification" that collapses that `if`
        // does not silently start emitting contextless table cards.
        case Marker.Table(directions) =>
          Tables.fromSection(key, section, CellDisplay.Escaped, tableContextTitles, directions)

        case Marker.Sequence =>
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
          val items     = sequenceItems(lowered)
          val surviving = items.filter(item => !C.AsHtml.plain(item.blocks).isEmpty)
          if surviving.isEmpty then
            val what =
              if items.isEmpty then s"the body holds no list — it holds ${describeBlocks(lowered)}"
              else s"the body's list has ${items.size} items and every one of them renders empty"
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
                  CardContext.render(ancestorTitles),
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

  def lineOf(headingText: String): Int =
    val needle = headingText.trim
    if needle.isEmpty then 0
    else
      val idx = lines.indexWhere(l => l.startsWith("#") && l.dropWhile(_ == '#').trim == needle, cursor)
      if idx < 0 then 0
      else
        cursor = idx + 1
        idx + bodyFirstLine // the file's own line number, as an editor counts it
