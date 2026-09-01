package obsidiananki.model

import cats.data.NonEmptyVector

/** One Anki note, as derived from the markdown — before anything is rendered or pushed.
  *
  * A `CardSpec` is the boundary between the pure extraction of meaning from a document and
  * the mechanics of talking to Anki. It says WHAT note should exist, not how to make it.
  */

/** The body of a marked section, already extracted.
  *
  * Non-empty by construction, which is [[SpecError.EmptyBody]]'s whole purpose.
  */
opaque type Body = String

object Body:
  /** `None` when the body is empty or blank.
    *
    * Deliberately not an `Either[SpecError, _]`: [[SpecError.EmptyBody]] names the heading
    * path, and a body has no business knowing where it came from. The caller, which does
    * know, attaches that context.
    */
  def fromExtracted(raw: String): Option[Body] =
    val trimmed = raw.trim
    if trimmed.isEmpty then None else Some(trimmed)

  extension (b: Body) def value: String = b

/** How a deletion is identified across edits.
  *
  * RULED. The two forms differ in exactly one respect — whether the key survives an edit to
  * the deletion's own text — and the author chooses per highlight.
  */
enum ClozeGroup:
  /** `==2|text==`. Keyed by the author-written group id, so the TEXT MAY CHANGE FREELY and
    * the card keeps its review history. Several highlights may share one label, and they
    * then blank together as a single card.
    */
  case Labelled(label: Int)

  /** `==text==`. Its own group of one, keyed by its own text — so editing the text, even to
    * fix a typo, retires the key: the card starts over and the old one is flagged as an
    * orphan, visible in the prune list. Accepted rather than worked around; labelling is
    * how an author buys stability when a card's history matters.
    */
  case Unlabelled(text: String)

/** One cloze deletion group within a section.
  *
  * `ordinal` is the `cN` number Anki will use. Anki schedules each `cN` independently, so
  * a number that moved between runs would move review history between cards.
  */
final case class ClozeDeletion(ordinal: Int, group: ClozeGroup, texts: Vector[String])

/** Why a marked heading could not produce a spec. */
enum SpecError:

  /** A WHOLE NOTE ASKED TO BE A THREE-FIELD CARD, which needs three parts where a note has two.
    *
    * A concept-descriptor card is a thing, an aspect of it, and the value of that aspect. A marked
    * HEADING supplies all three — the note or ancestor heading is the concept, the heading itself
    * is the aspect, its body is the value. A whole note supplies only two: its name and its body.
    * The aspect would have to be the name a second time, so the card would ask "Essential Numbers"
    * about "Essential Numbers", which is not a question.
    *
    * REFUSED RATHER THAN QUIETLY DEGRADED to a two-field card, because the marker says what the
    * author wanted and the tool silently making something else is how you end up reviewing a card
    * you did not ask for and cannot find the source of.
    */
  case WholeNoteCannotBeThreeField(fileName: String, marker: String)
  /** RULED (B6). A marked heading whose own prose is empty is a HARD ERROR.
    *
    * This is not pedantry. The body stops at the next heading of any level, so a marked
    * heading immediately followed by a subheading has an empty body. For `2way` that means
    * an empty back field, Anki then declines to generate the reverse card, and the marker
    * silently produces ONE card where it promised TWO. Silent success is this project's
    * signature failure; it must abort loudly instead.
    */
  case EmptyBody(headingPath: String)

  /** A `#flashcard/cloze` section with no `==highlight==` in it. Anki rejects a cloze note
    * whose text has no deletion, so this must fail here rather than at the API boundary.
    */
  case ClozeWithoutDeletions(headingPath: String)

  /** Two UNLABELLED highlights with identical text in one section.
    *
    * "A ==quorum== is a majority. Any two ==quorum== sets intersect." — separate groups by
    * rule, identical text, and nothing but POSITION to tell them apart. Refused, with the
    * remedy named: label them. A positional tiebreak would reintroduce exactly the hazard
    * the design rejects, for the case nobody would think to test.
    */
  case AmbiguousClozeDeletion(headingPath: String, text: String)

  /** A `#flashcard/table` section with no table in its body. */
  case TableWithoutTable(headingPath: String)

  /** A table with no descriptor column this tool can name a card after.
    *
    * TWO SHAPES REACH HERE AND `what` SAYS WHICH. Either the header row declares no descriptor
    * column at all, or it declares some and every one of their header cells canonicalises to
    * empty — blank, or holding nothing but a `#flashcard` marker. The second shape used to be
    * SILENT: a header cell is a card's KEY SEGMENT, so one that canonicalises to empty names
    * nothing, and a table whose only descriptor column has such a header emits no pair card
    * and — with fewer than two descriptors surviving — no row card either, while the header
    * row was non-empty at the `Cell` level so nothing fired.
    *
    * Reported rather than silently producing nothing. An explicit marker that yields zero
    * cards is the dual of the silent card creation the whole design guards against: the
    * author asked for cards and got none, with no indication why.
    */
  case TableWithoutDescriptors(headingPath: String, what: String)

  /** Content in a card body that this tool does not put on a card.
    *
    * `what` is every reason the lowering gave, `"; "`-joined in DOCUMENT ORDER and
    * de-duplicated — see `Extractor.bodyBlocks`, the ONE place a `Refusal` becomes a
    * `SpecError` and the ONE place a heading path is attached.
    *
    * TYPED AS A STRING ON PURPOSE. Verified 2026-08-20: `model/` imports nothing from
    * `obsidiananki` at all — `cats.data.NonEmptyVector` is its only import, in this file
    * and in `CardKey.scala`. Carrying `content.Refusal` here would give the
    * dependency-free base layer a permanent dependency on the lowering's error
    * vocabulary, for a payload NOTHING pattern-matches: `SpecError` values are
    * constructed in `extract/` and consumed only by `Extractor.describe`.
    *
    * REPLACES `UnsupportedTaskList` AND `UnsupportedEmbed`, which named exactly two of the
    * three constructs the old body walk could refuse (a plain markdown image was reported as
    * an embed) and could name only ONE of them per section. Both were rejected LOUDLY AND BY
    * NAME rather than left to fail as an "unresolved link id reference"; that property is
    * what this case keeps, since `Refusal.describe` names the construct in the author's own
    * vocabulary. Neither ruling changed: a task list and an embed are still unsupported.
    */
  case UnsupportedContent(headingPath: String, what: String)

  /** A `#flashcard/sequence` section whose body yields NO list item that survives rendering.
    *
    * NAMED FOR THE ITEMS, NOT FOR THE LIST, ON PURPOSE: the name must not promise a weaker
    * check than the code performs. Two situations reach it, and `whatIsThere` says which:
    *
    *   1. THE BODY HOLDS NO LIST AT ALL — the ruled case. The marker and the body are two
    *      independent statements and they disagree, so both halves are named: what was asked
    *      for, and what is actually there.
    *   2. THE BODY'S LIST HAS ITEMS AND EVERY ONE OF THEM RENDERS EMPTY. `- %%not ready yet%%`
    *      is the measured example: an Obsidian comment lowers to zero inlines, the renderer
    *      drops the empty item, and then drops the whole list. Without this half the tool
    *      would ship a note whose Text holds ZERO `li` — front identical to back, the reveal
    *      key doing nothing, reviewed forever as a prompt with no answer and no error
    *      anywhere. That is silent success, this project's signature failure.
    *
    * `whatIsThere` IS A STRING, precedent [[UnsupportedContent]] and for the same recorded
    * reason: `model/` imports nothing from `obsidiananki`, and nothing pattern-matches the
    * payload — `SpecError` values are built in `extract/` and read only by
    * `Extractor.describe`.
    */
  case SequenceWithoutItems(headingPath: String, whatIsThere: String)

  /** A nested list this tool's markdown parser and the author's editor read DIFFERENTLY.
    *
    * The parser wants four columns of indentation, or a tab, before it reads a line as a
    * sub-item; CommonMark — which Obsidian implements — is satisfied by two. Given less than
    * four, the parser does not merely decline to nest: it closes the list and opens a new one,
    * which the next unindented item then joins. A remark and the thing it was a remark about
    * come out as siblings, so the card asserts something the note never said WHILE LOOKING
    * PERFECTLY WELL-FORMED. Silent success again, which is why this is a refusal, not a warning.
    *
    * REFUSED RATHER THAN REPAIRED, because repair is impossible rather than merely awkward:
    * indentation is consumed by the parser, so once the wrong shape exists nothing records
    * which line was indented and which was not. `extract/ListIndent.scala` therefore reads
    * SOURCE TEXT, and `what` carries the evidence it found — line numbers and column counts.
    *
    * `what` IS A STRING, precedent [[UnsupportedContent]] and for the same recorded reason:
    * `model/` imports nothing from `obsidiananki`, and carrying `ListIndent.Finding` here would
    * give the dependency-free base layer a dependency on `extract/` for a payload that nothing
    * pattern-matches.
    */
  case ListNestingUnreadable(headingPath: String, what: String)

  /** `#flashcard/table/rows` on a table that yields NO row card.
    *
    * A row card is emitted only for a row carrying TWO OR MORE usable descriptor cells: with one
    * it would be byte-identical to that row's single cell card, differing only in key — two notes
    * holding the same fact, on two schedules, forever, with nothing comparing content across keys
    * to notice. So `rows` on a one-descriptor table asks for the only card kind the table cannot
    * produce, and the honest answer is nothing at all.
    *
    * REFUSED RATHER THAN LEFT SILENT, because "an explicit marker that yields zero cards" is the
    * dual of silent card creation and the reason [[TableWithoutDescriptors]] exists. Without this
    * the author marks a heading, sees a clean run, and believes it synced.
    */
  case TableRowsWithoutRows(headingPath: String, what: String)

/** What will become exactly one Anki note.
  *
  * TABLE KEYS EXTEND THE HEADING PATH rather than forming a parallel key shape. A pair card
  * keys as `…/{row concept}/{column header}` and a row card as `…/{row concept}`. This is
  * not a sentinel — the row card genuinely has one fewer coordinate — and it means table
  * keys reuse the encoding, the decoder and the uniqueness gate unchanged rather than
  * needing a second mechanism. A collision between a table key and a deeply-nested heading
  * key is possible in principle and is caught by the set-level uniqueness check that runs
  * before any write.
  */
enum CardSpec:
  /** `#flashcard/1way` and `#flashcard/2way`. */
  case TwoField(
      key: CardKey,
      front: String,
      back: Body,
      directions: TwoFieldDirections,
      context: String,
  )

  /** `#flashcard/3way` and `#flashcard/3way/all`, and a table's pair cards. */
  case ThreeField(
      key: CardKey,
      concept: String,
      descriptor: String,
      description: Body,
      directions: ThreeFieldDirections,
      context: String,
      conceptLabel: String,
  )

  /** `#flashcard/cloze` — one note holding ALL of the section's deletions, so adding a
    * highlight adds a CARD to an existing note rather than churning the key.
    */
  case Cloze(
      key: CardKey,
      text: Body,
      deletions: NonEmptyVector[ClozeDeletion],
      context: String,
  )

  /** A table's row card: the concept, with all its descriptors together.
    *
    * Emitted only when a row carries TWO OR MORE descriptors — with one it would merely
    * duplicate the single pair card. This is the card that preserves the relation; a
    * benefit divorced from its cost is trivia.
    */
  case TableRow(
      key: CardKey,
      blanked: String,
      filled: String,
      context: String,
  )

  /** `#flashcard/sequence` — ONE note whose list items are revealed one at a time, on ONE
    * schedule.
    *
    * `text` IS THE WHOLE RENDERED BODY, not the items alone, because the note type's templates
    * want exactly that: they render the whole field and hide only its `li` elements, so
    * everything else in the body becomes the question side.
    *
    * THIS TYPE DOES NOT GUARANTEE THAT THE TEXT HOLDS A LIST, AND NO COMMENT MAY IMPLY IT
    * DOES. "There is at least one list item that survives rendering" is established by a
    * REFUSAL in `extract/` — [[SpecError.SequenceWithoutItems]] — which runs before this value
    * is ever built. A `Sequence` constructed by hand in a test can hold anything at all.
    *
    * TWO SHAPES REJECTED, recorded with their reasons so they are not re-proposed:
    *   - `NonEmptyVector[Item]` is IMPOSSIBLE here. `model/` imports nothing from
    *     `obsidiananki` (verified 2026-08-20, recorded at [[SpecError.UnsupportedContent]]),
    *     and carrying `content.Item` would give the dependency-free base layer a permanent
    *     dependency on the renderer's algebra.
    *   - `NonEmptyVector[String]` joined into `<li>` inside `fields` is worse, and this file's
    *     own note at [[TableRow]] already rules out its shape: markup construction outside
    *     `content/` bypasses the opaque `Fragment` and reopens the hole that type was
    *     introduced to shut.
    */
  case Sequence(key: CardKey, title: String, text: Body, context: String, reveal: RevealOrder)

object CardSpec:

  extension (spec: CardSpec)
    /** Every spec knows its own key. */
    def key: CardKey = spec match
      case TwoField(k, _, _, _, _)      => k
      case ThreeField(k, _, _, _, _, _, _) => k
      case Cloze(k, _, _, _)            => k
      case TableRow(k, _, _, _)         => k
      case Sequence(k, _, _, _, _)      => k

    /** The Anki note type this spec creates. Behaviour on the sum type: the consumer asks,
      * the variant answers, rather than the consumer branching on which variant it holds.
      */
    def noteTypeName: String = spec match
      case TwoField(_, _, _, TwoFieldDirections.Forward, _) => Marker.NoteTypes.Basic
      case TwoField(_, _, _, TwoFieldDirections.Both, _)    => Marker.NoteTypes.BasicAndReversed
      case ThreeField(_, _, _, _, _, _, _)                  => Marker.NoteTypes.ConceptDescriptor
      case Cloze(_, _, _, _)                                => Marker.NoteTypes.Cloze
      // The row card is a plain Basic holding two renderings of one table — see `blanked` and `filled` below.
      case TableRow(_, _, _, _)                             => Marker.NoteTypes.Basic
      case Sequence(_, _, _, _, _)                          => Marker.NoteTypes.ClozeSequence

    /** Field name to value, in the note type's field order.
      *
      * For a three-field spec the first three are the ruled display order; the trailing
      * [[Marker.ThreeWayField]] is the conditional switch that makes Anki generate the
      * third card, empty unless the marker asked for all directions.
      *
      * [[Marker.ContextField]] IS LAST ON EVERY ARM, and every arm APPENDS it explicitly
      * rather than growing the vector it zips against. `Vector.zip` truncates to the shorter
      * side without complaint, so adding `"Context"` to `Marker.ConceptDescriptorFields` or
      * `Marker.ClozeSequenceFields` would drop the field on the floor with every test still
      * green. `Marker.FieldOrder` holds the complete declared list, and
      * `model/Marker.test.scala` compares what this function emits against it.
      *
      * THE VALUE MAY LEGITIMATELY BE EMPTY — a card whose heading chain is empty, such as a
      * `3way` heading sitting directly under a note's H1. The note type's templates wrap the
      * field in `{{#Context}}…{{/Context}}`, so an empty value emits no markup at all rather
      * than an empty rule and a margin.
      */
    /** EVERY FIELD THIS CARD WRITES, INCLUDING ITS IDENTITY.
      *
      * THE IDENTITY IS APPENDED ONCE HERE RATHER THAN IN EVERY ARM, and that is the point of
      * doing it at this level: it cannot be forgotten by a card kind added later, and it cannot
      * disagree between kinds. It is LAST for the reason every other appended field is last —
      * Anki's `modelFieldAdd` appends, so a field declared anywhere else leaves a repaired
      * collection permanently reporting a field-order difference it can never fix.
      *
      * IT IS THE SAME STRING THE `src::` TAG HELD. See [[Marker.IdentityField]]: this moved
      * where the identity is stored, not what it is.
      */
    def fields: Vector[(String, String)] =
      perKindFields :+ (Marker.IdentityField -> TagCodec.encode(spec.key).value)

    private def perKindFields: Vector[(String, String)] = spec match
      case TwoField(_, front, back, _, context) =>
        Vector(
          Marker.BasicFields.Front -> front,
          Marker.BasicFields.Back  -> back.value,
          Marker.ContextField      -> context,
          // EMPTY: a heading's question and its answer are different things, so the answer
          // belongs BENEATH the question in the ordinary way.
          Marker.SameShapeField -> "",
        )

      case ThreeField(_, concept, descriptor, description, directions, context, conceptLabel) =>
        val threeWay = directions match
          case ThreeFieldDirections.All       => "1"
          case ThreeFieldDirections.Default   => ""
          case ThreeFieldDirections.ValueOnly => ""
        val valueOnly = directions match
          case ThreeFieldDirections.ValueOnly => "1"
          case ThreeFieldDirections.Default   => ""
          case ThreeFieldDirections.All       => ""
        Marker.ConceptDescriptorFields.zip(Vector(concept, descriptor, description.value)) :+
          (Marker.ThreeWayField -> threeWay) :+
          (Marker.ContextField -> context) :+
          (Marker.ConceptLabelField -> conceptLabel) :+
          (Marker.ValueOnlyField -> valueOnly)

      case Cloze(_, text, _, context) =>
        // The body ALREADY CARRIES its `{{cN::…}}` deletions: `Cloze.renderWithDeletions`
        // puts them in when the spec is built, so there is nothing to apply here. One note
        // holds all of a section's deletions, and Anki makes one card per distinct `cN`.
        //
        // This comment previously said deletions were "applied to the text when the note is
        // rendered". Nothing did that, and the sentence is why nobody looked: the raw
        // `==markdown==` went to Anki, which refuses a Cloze note containing no deletion.
        // Corrected 2026-08-20, after a live run failed on exactly those two cards.
        Vector(
          Marker.ClozeFields.Text      -> text.value,
          Marker.ClozeFields.BackExtra -> "",
          Marker.ContextField          -> context,
        )

      case TableRow(_, blanked, filled, context) =>
        // TWO RENDERINGS OF ONE TABLE — the same shape with and without its answers — so the
        // question and the answer differ only by what is filled in. Nothing reflows between
        // sides, which is the whole reason a row card is a table rather than a list.
        //
        // BOTH ARRIVE ALREADY RENDERED, built by `content/`'s `Html.rowTable`. This case
        // previously joined the descriptors into `"$header: $value"` lines here, with a comment
        // conceding that how it should LOOK was unresolved. It was resolved by HTML: a newline
        // inside a field collapses to a space, so that join arrived on the card as one run-on
        // line. Building markup here would also mean concatenating around values escaped
        // elsewhere, which is what the opaque `Html.Fragment` exists to prevent.
        Vector(
          Marker.BasicFields.Front -> blanked,
          Marker.BasicFields.Back  -> filled,
          Marker.ContextField      -> context,
          // The two sides ARE one table, so the answer REPLACES the question rather than
          // appearing beneath it. Last, matching the declared order — new fields are appended,
          // because Anki's `modelFieldAdd` appends and any other position leaves a repaired
          // collection permanently reporting a field-order difference.
          Marker.SameShapeField -> "1",
        )

      case Sequence(_, title, text, context, reveal) =>
        // The zip form, exactly as the three-field arm above: the constant is the single
        // source of field ORDER for the two fields it names, so a reordering of THOSE happens
        // in one place rather than two. Context is appended, never zipped — see the note on
        // this function.
        Marker.ClozeSequenceFields.zip(Vector(title, text.value)) :+
          (Marker.ContextField -> context) :+
          // EMPTY FOR DEPTH-FIRST, so a note written before this field existed and a note
          // explicitly asking for depth-first are byte-identical — which is what makes the
          // field's arrival invisible to every existing card.
          (Marker.RevealField -> (reveal match
            case RevealOrder.DepthFirst   => ""
            case RevealOrder.BreadthFirst => Marker.BreadthFirstMarker
          ))
