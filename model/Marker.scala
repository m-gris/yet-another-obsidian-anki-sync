package obsidiananki.model

/** What a marked heading opts in to, and what it becomes.
  *
  * A heading carries at most one `#flashcard/…` marker. Headings without one generate
  * nothing, so ordinary prose sections stay ordinary — opt-in, because silent card creation
  * is the failure mode the whole design is built against.
  */

/** Retrieval directions over a two-field (heading + body) card.
  *
  * `1way` and `2way` count DIRECTIONS. This is a different quantity from the one `3way`
  * counts, which is why the two families get different types rather than a shared integer:
  * the confusion is worth making unrepresentable rather than documenting.
  */
enum TwoFieldDirections:
  /** `#flashcard/1way` — heading to body only. */
  case Forward

  /** `#flashcard/2way` — heading to body and back. */
  case Both

/** Directions generated for a three-field card.
  *
  * `3way` counts FIELDS, not cards. It selects the concept–descriptor shape, whose default
  * is TWO directions: recall the concept, and recall the description.
  */
enum ThreeFieldDirections:
  /** `#flashcard/3way` — recall the concept; recall the description. */
  case Default

  /** `#flashcard/3way/all` — additionally recall the descriptor. */
  case All

/** The marker on a heading, parsed. */
enum Marker:
  case TwoField(directions: TwoFieldDirections)
  case ThreeField(directions: ThreeFieldDirections)
  case Cloze
  case Table

  /** `#flashcard/sequence` — the body's list items are revealed ONE AT A TIME, as ONE note on
    * ONE schedule.
    *
    * WHY THE MARKER IS NAMED FOR THE SEQUENCE AND NOT FOR THE LIST. The tool can SEE that a
    * list is present; it can NEVER see whether the ORDER is the knowledge. A marker named
    * `/list` would name the half the tool can see, and a marker that restates what the tool
    * can see is precisely the one a later author "simplifies" into an inference from the body
    * — which is the thing the explicit-marker ruling exists to prevent. `/sequence` names the
    * half only the author knows, so marker and body stay two independent statements that can
    * DISAGREE, and a disagreement is detectable. A bulleted answer shown WHOLE is legitimate
    * and is a DIFFERENT card from one revealed step by step; only the author can say which.
    *
    * THE INVERSION THE AUTHOR MUST BE TOLD ONCE: EVERYTHING IN THE BODY THAT IS NOT A LIST
    * ITEM IS PRINTED ON THE QUESTION SIDE. Read off the templates rather than predicted —
    * `note-types/cloze-sequence/templates/cloze-sequence.front.html:2-4` renders
    * `<h4>{{Title}}</h4>` plus the WHOLE `{{Text}}` div, and its `:10-14` adds `hidden-cloze`
    * to `#text li` and binds nothing else. `styling.css:22-24` dims that div to `opacity: 0.5`
    * on the question side and the back template's `:46` restores it to 1, which says the same
    * thing the selector says. (Paths and line numbers re-checked 2026-08-21, after the note
    * type's files moved under `templates/` and its front gained a `Context` line.) So a
    * lead-in paragraph is a GIFT — it becomes the prompt — while prose written AFTER the list
    * is a SPOILER printed on the front. The contract is therefore stronger than "the body is
    * the answer": THE BODY'S LIST ITEMS ARE THE ANSWER; EVERYTHING ELSE IN THE BODY IS PROMPT.
    * Not refused, because refusing closing prose would be a new ruling and would refuse
    * legitimate caveats; documented, with the fixture demonstrating the shape that works.
    *
    * TODAY THIS MARKER ALREADY FAILED LOUDLY — `fromToken` returned `None`, which becomes
    * `MarkerError.Unrecognised` and then `BuildFailure.KeyKnown`. So adding it converts a
    * REFUSAL INTO A CARD. `content/Content.scala` §(D)'s "may never turn a refusal into a
    * card" does not forbid this: its own first three words scope it to the closed-
    * representation REFACTOR, where a refusal becoming a card would have been a silent
    * behaviour change nobody asked for. Here it is the whole point of the slice.
    *
    * A NAME COLLISION, STATED ONCE. `REQUIREMENTS.md:160,173,178` already uses "sequence" for
    * the AUTHORED ROUTE — the order in which new cards are introduced ACROSS cards. That is a
    * different layer from this one, which orders items INSIDE a single card. Both are unbuilt
    * as far as that document is concerned; this one is being built here.
    */
  case Sequence

/** Why a heading's marker could not be understood.
  *
  * Note what is NOT an error: a heading with no marker at all. That is the ordinary case
  * and yields `None`. An UNRECOGNISED marker, though, must fail loudly — a typo like
  * `#flashcard/2-way` silently treated as "unmarked" would mean a card the author asked for
  * simply never appears, which is the same silent-omission failure in a new place.
  */
enum MarkerError:
  case Unrecognised(raw: String)
  case Multiple(raws: List[String])

object Marker:

  /** Anki note type names.
    *
    * RESOLVED BY NAME, NEVER BY ID. Note type ids are collection-local: the id recorded in
    * the design documents came from a different profile's backup and does not match the
    * live one, and the plan duplicates a profile anyway. Names are the stable contract.
    *
    * EVERY NAME IS PREFIXED `Obsidian `, AND THAT IS A RULING RATHER THAN A CONVENTION.
    * Ruled by Marc, 2026-08-21: this tool writes ONLY to note types it owns, so that changing
    * a template can never reach the rest of his collection. Before the ruling the tool wrote
    * to Anki's STOCK `Basic` / `Basic (and reversed card)` / `Cloze`, which every other note
    * in the collection also uses.
    *
    * TWO OF THE FIVE ARE RENAMES OF NOTE TYPES THAT ALREADY HOLD NOTES — `Cloze Sequence` and
    * `3 way Concept-Descriptor`. AnkiConnect has no rename-model action, so those two renames
    * are done BY HAND in Tools → Manage Note Types. (REPORTED, NOT RE-CHECKED HERE: the
    * add-on's own `apiReflect` action list was enumerated live on 2026-08-21 and holds
    * `modelFieldRename` and `modelTemplateRename` but nothing for the model itself. Re-running
    * `apiReflect` is what would confirm it.) The old names are recorded in
    * `note-types/<type>/manifest.json` under `renamedFrom`, which is what lets an installer
    * refuse rather than create a second, empty note type beside the populated one.
    *
    * THE OTHER HALF OF THIS CONTRACT IS `note-types/<slug>/manifest.json`, whose `name` key
    * carries the same five strings. Nothing compares the two yet — the test that does (design
    * document §4, "T1") belongs to the slice that writes the installer, because it must read
    * those files and `model/` deliberately depends on nothing. Until then a disagreement is
    * silent, and a silent disagreement means a write to a note type that does not exist.
    */
  object NoteTypes:
    val Basic: String             = "Basic"
    val BasicAndReversed: String  = "Basic (and reversed card)"
    val Cloze: String             = "Cloze"
    val ConceptDescriptor: String = "3 way Concept-Descriptor"

    /** The list note type: one card per note, whose items reveal one at a time. */
    val ClozeSequence: String = "Cloze Sequence"

    /** All five, IN THE ORDER THE MANIFEST DIRECTORIES ARE LISTED in `note-types/README.md`
      * (`basic`, `basic-and-reversed`, `cloze`, `cloze-sequence`, `concept-descriptor`), so
      * that the owed manifest-versus-Scala test can compare two ordered vectors rather than
      * two sets. A set comparison would pass while the two disagreed about which name belongs
      * to which directory.
      */
    val All: Vector[String] =
      Vector(Basic, BasicAndReversed, Cloze, ClozeSequence, ConceptDescriptor)

  /** The heading chain a card came from, shown ON THE CARD.
    *
    * WHY A REAL FIELD AND NOT THE `src::` TAG. The tag carries the CANONICAL path —
    * lowercased and percent-encoded — because identity is deliberately severed from display.
    * A breadcrumb derived from it would read `body shapes > cranial bones and their sutures`
    * in permanent lowercase. `Extractor` already computes the properly cased chain and used to
    * discard all but its last element.
    *
    * WHAT IT IS FOR. A card can be unanswerable because it lost its context. The example is
    * in this repository and was re-read from it: before this field existed,
    * `extract/golden/fixture-cards.txt` recorded the card keyed
    * `…body%20shapes/cranial%20bones%20and%20their%20sutures/frontal/anterior%20border` as
    * carrying `Concept: Frontal`, `Descriptor: Anterior border` and nothing else. Frontal WHAT
    * — bone, lobe, cortex? The one segment that disambiguates, `Cranial bones and their
    * sutures`, was dropped. The same shape was reported from a live collection.
    */
  val ContextField: String = "Context"

  /** Field order for [[NoteTypes.ConceptDescriptor]]'s FIRST THREE fields.
    *
    * RULED (B7): Concept, Descriptor, Description — matching the templates already on the
    * note type. Field order is purely a display concern here, because `allowDuplicate` is
    * on and identity comes from the `src::` tag rather than Anki's first-field checksum.
    *
    * ⚠️ THIS VECTOR MUST NOT GROW, AND THE REASON IS A SILENT TRUNCATION RATHER THAN TASTE.
    * `CardSpec.fields` builds the three-field arm as `ConceptDescriptorFields.zip(Vector(
    * concept, descriptor, description))`, and `Vector.zip` truncates to the shorter side
    * WITHOUT COMPLAINT. Appending a name here would therefore drop it on the floor and every
    * test would stay green. The complete field list — including [[ThreeWayField]] and
    * [[ContextField]] — is [[FieldOrder.ConceptDescriptor]], which is built from this vector
    * rather than beside it.
    */
  val ConceptDescriptorFields: Vector[String] = Vector("Concept", "Descriptor", "Description")

  /** Field order for [[NoteTypes.ClozeSequence]]'s first two fields.
    *
    * ⚠️ MUST NOT GROW, for the identical zip-truncation reason spelled out at
    * [[ConceptDescriptorFields]]: `CardSpec.fields`'s sequence arm zips this against two
    * values. The complete list is [[FieldOrder.ClozeSequence]].
    */
  val ClozeSequenceFields: Vector[String] = Vector("Title", "Text")

  /** The conditional field that switches on the third retrieval direction.
    *
    * Anki generates a card only when its front renders non-empty, which is how the stock
    * "Basic (optional reversed card)" works. Wrapping Card 3's FRONT in
    * `{{#ThreeWay}}…{{/ThreeWay}}` therefore makes the third direction opt-in without
    * needing a second note type. Setting this field is what `#flashcard/3way/all` does.
    */
  val ThreeWayField: String = "ThreeWay"

  /** Field names carried over from Anki's stock note types.
    *
    * THESE NAMES WERE VERIFIED against a live collection via `modelFieldNames`, twice, by
    * earlier sessions and not re-checked here: 2026-08-19, and again 2026-08-21 in profile
    * `claude-POC-test`, where stock `Basic` came back `[Front, Back]` and stock `Cloze`
    * `[Text, Back Extra]`. Worth recording, because of the documented library defaults this
    * project has checked, this is the only one that did not lie. The others — HOCON typing,
    * Laika's missing tables, `updateNoteFields` dropping tags — all produced plausible output
    * instead of failing.
    *
    * WHAT THEY NOW NAME HAS CHANGED, THOUGH THE STRINGS HAVE NOT. Since [[NoteTypes]] became
    * the tool's own `Obsidian *` types, these are the names this tool AUTHORS into types it
    * creates, not names it reads off Anki's. They were kept identical to the stock ones
    * deliberately: `Retype` between [[NoteTypes.Basic]] and [[NoteTypes.BasicAndReversed]] is
    * then a template change and not a field remapping, and a template copied from a stock type
    * needs no edit.
    *
    * ⚠️ `anki/InMemoryAnki.scala`'s `defaultNoteTypes` comment claims these names are
    * "UNVERIFIED against a live collection". That comment is STALE — see the two dates above —
    * and correcting it belongs to whichever slice next edits that file.
    */
  object BasicFields:
    val Front: String = "Front"
    val Back: String  = "Back"

  object ClozeFields:
    val Text: String      = "Text"
    val BackExtra: String = "Back Extra"

  /** THE COMPLETE, ORDERED FIELD LIST OF EACH NOTE TYPE — what an installer's `createModel`
    * is given, and what `CardSpec.fields` must produce, name for name and in order.
    *
    * SEPARATE FROM [[ConceptDescriptorFields]] AND [[ClozeSequenceFields]] ON PURPOSE. Those
    * two are ZIP OPERANDS whose length is load-bearing; these are DECLARATIONS. Growing a zip
    * operand silently drops a field (see the warning there); growing a declaration is caught,
    * because `CardSpec.fields` can be compared against it.
    *
    * NOTHING IN PRODUCTION READS THIS YET, and that is stated rather than hidden: the
    * installer that will feed it to `createModel` is a later slice. What reads it today is
    * `model/Marker.test.scala`, which compares it against what `CardSpec.fields` emits — the
    * test that makes a truncating zip fail rather than pass quietly.
    *
    * [[ContextField]] IS LAST ON EVERY TYPE, for three reasons. Anki's Sort Field defaults to
    * field 1, and a breadcrumb there would fill the Browse list with the same repeated prefix.
    * Appending leaves every existing field position unchanged, so nothing downstream shifts.
    * And AnkiConnect's `modelFieldAdd` appends unless given an explicit index, so appending is
    * what a later repair would do anyway — that last one is REPORTED, from a reading of the
    * add-on source on 2026-08-21 (`__init__.py:1433`), and was not re-checked here.
    */
  object FieldOrder:
    val Basic: Vector[String] =
      Vector(BasicFields.Front, BasicFields.Back, ContextField)

    /** The same three names as [[Basic]] — see the note at [[BasicFields]] for why the two
      * types deliberately share a field list.
      */
    val BasicAndReversed: Vector[String] = Basic

    val Cloze: Vector[String] =
      Vector(ClozeFields.Text, ClozeFields.BackExtra, ContextField)

    val ClozeSequence: Vector[String] = ClozeSequenceFields :+ ContextField

    val ConceptDescriptor: Vector[String] =
      ConceptDescriptorFields :+ ThreeWayField :+ ContextField

    /** Keyed by note type name, so a consumer holding a `CardSpec` can ask
      * `FieldOrder.byNoteType(spec.noteTypeName)`.
      *
      * A `Map`, so `.apply` THROWS on an unknown name rather than returning an empty vector.
      * Deliberate: a silently empty field list is a note type whose cards never generate,
      * which is this project's signature failure shape.
      */
    val byNoteType: Map[String, Vector[String]] = Map(
      NoteTypes.Basic             -> Basic,
      NoteTypes.BasicAndReversed  -> BasicAndReversed,
      NoteTypes.Cloze             -> Cloze,
      NoteTypes.ClozeSequence     -> ClozeSequence,
      NoteTypes.ConceptDescriptor -> ConceptDescriptor,
    )

  private val MarkerPattern = """#flashcard(?:/[\w-]+)*""".r

  /** The heading text with its marker removed, for display.
    *
    * Distinct from [[HeadingSegment.fromExtractedText]], which additionally canonicalises
    * for the KEY — case-folded, whitespace-collapsed. A card shows this text to a human, so
    * it keeps its original casing.
    */
  def stripMarker(headingText: String): String =
    MarkerPattern.replaceAllIn(headingText, "").trim.replaceAll("\\s+", " ")

  private def fromToken(token: String): Option[Marker] = token match
    case "#flashcard/1way"     => Some(TwoField(TwoFieldDirections.Forward))
    case "#flashcard/2way"     => Some(TwoField(TwoFieldDirections.Both))
    case "#flashcard/3way"     => Some(ThreeField(ThreeFieldDirections.Default))
    case "#flashcard/3way/all" => Some(ThreeField(ThreeFieldDirections.All))
    case "#flashcard/cloze"    => Some(Cloze)
    case "#flashcard/table"    => Some(Table)
    case "#flashcard/sequence" => Some(Sequence)
    case _                     => None

  /** Parse the marker out of a heading's extracted text.
    *
    * `Right(None)` means "no marker, generates nothing" — the ordinary case.
    * `Left(_)` means "something that looks like a marker but is not one" — loud failure.
    */
  def parse(headingText: String): Either[MarkerError, Option[Marker]] =
    MarkerPattern.findAllIn(headingText).toList match
      case Nil            => Right(None)
      case token :: Nil   => fromToken(token).toRight(MarkerError.Unrecognised(token)).map(Some(_))
      case several        => Left(MarkerError.Multiple(several))

  extension (m: Marker)
    /** The Anki note type this marker's note is created with.
      *
      * [[Marker.Table]] has none: a table row yields notes of two DIFFERENT types — pair
      * cards use the concept-descriptor type, the row card uses Basic — so the note type is
      * a property of the emitted spec, not of the marker.
      */
    def noteTypeName: Option[String] = m match
      case TwoField(TwoFieldDirections.Forward) => Some(NoteTypes.Basic)
      case TwoField(TwoFieldDirections.Both)    => Some(NoteTypes.BasicAndReversed)
      case ThreeField(_)                        => Some(NoteTypes.ConceptDescriptor)
      case Cloze                                => Some(NoteTypes.Cloze)
      case Sequence                             => Some(NoteTypes.ClozeSequence)
      case Table                                => None
