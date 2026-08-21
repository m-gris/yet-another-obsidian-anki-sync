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
    * `note-types/cloze-sequence/front.html:1-3` renders `<h4>{{Title}}</h4>` plus the WHOLE
    * `{{Text}}` div, and `front.html:9-13` adds `hidden-cloze` to `#text li` and binds nothing
    * else. `styling.css:22-24` dims that div to `opacity: 0.5` on the question side and
    * `back.html:46` restores it to 1, which says the same thing the selector says. So a
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
    */
  object NoteTypes:
    val Basic: String            = "Basic"
    val BasicAndReversed: String = "Basic (and reversed card)"
    val Cloze: String            = "Cloze"
    val ConceptDescriptor: String = "3 way Concept-Descriptor"

    /** The list note type: one card per note, whose items reveal one at a time.
      *
      * VERIFIED 2026-08-21, and worth recording because it is easy to assume otherwise: THIS
      * `val` IS THE SOLE MACHINE-READABLE STATEMENT OF THAT NOTE TYPE'S NAME IN THE REPO.
      * None of the four installable files in `note-types/cloze-sequence/` names the model —
      * `fields.json` holds only field names, and the name appears elsewhere only as the H1 of
      * that directory's `README.md` and in one prose comment. So a rename here silently stops
      * matching a real collection, and nothing in the repository would notice.
      */
    val ClozeSequence: String = "Cloze Sequence"

  /** Field order for [[NoteTypes.ConceptDescriptor]].
    *
    * RULED (B7): Concept, Descriptor, Description — matching the templates already on the
    * note type. Field order is purely a display concern here, because `allowDuplicate` is
    * on and identity comes from the `src::` tag rather than Anki's first-field checksum.
    */
  val ConceptDescriptorFields: Vector[String] = Vector("Concept", "Descriptor", "Description")

  /** Field order for [[NoteTypes.ClozeSequence]], mirroring [[ConceptDescriptorFields]].
    *
    * AN OWED CONTRACT, NOT A GUARANTEE, AND THE GAP IS NAMED RATHER THAN PAPERED OVER.
    * `note-types/cloze-sequence/fields.json` is the other half of this contract, and NOTHING
    * ties the two together: no test reads that file, and no build step compares them. If they
    * drift, a real collection answers `UnknownField` per note at write time — after the plan
    * has already been printed as though it would work.
    *
    * Tying them belongs to the slice that INSTALLS the note type, which already reads all four
    * files in that directory. It is not done here because `model/` deliberately depends on
    * nothing (verified 2026-08-20, recorded at [[SpecError.UnsupportedContent]]), so reading
    * that file from here would need a third copy of the upward `user.dir` path walk inside the
    * one package that has no dependencies at all.
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

  /** Field names of the stock note types.
    *
    * VERIFIED against a live collection via `modelFieldNames` (2026-08-19), after being
    * carried as an explicit assumption. Worth recording that they turned out to be correct:
    * of the documented defaults this project has checked, this is the only one that did not
    * lie. The others — HOCON typing, Laika's missing tables, `updateNoteFields` dropping
    * tags — all produced plausible output instead of failing, which is why the assumption
    * was worth checking rather than trusting.
    */
  object BasicFields:
    val Front: String = "Front"
    val Back: String  = "Back"

  object ClozeFields:
    val Text: String      = "Text"
    val BackExtra: String = "Back Extra"

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
