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

/** One `==highlight==` inside a cloze section.
  *
  * `ordinal` is the `cN` number Anki will use. It is assigned by DELETION TEXT and carried
  * here rather than derived from position: Anki schedules each `cN` card independently, so
  * renumbering on edit would move review history between cards.
  */
final case class ClozeDeletion(ordinal: Int, text: String)

/** Why a marked heading could not produce a spec. */
enum SpecError:
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

  /** A `#flashcard/table` section with no table in its body. */
  case TableWithoutTable(headingPath: String)

  /** A table with a concept column but NO descriptor columns.
    *
    * Reported rather than silently producing nothing. An explicit marker that yields zero
    * cards is the dual of the silent card creation the whole design guards against: the
    * author asked for cards and got none, with no indication why.
    */
  case TableWithoutDescriptors(headingPath: String)

  /** A GFM task list (`- [ ]` / `- [x]`) in a card body.
    *
    * RULED: not supported. Rejected LOUDLY AND BY NAME rather than left to fail as an
    * "unresolved link id reference", which names the mechanism instead of the problem and
    * tells an author nothing about what to change.
    *
    * Note what this eliminates: the hazard was never "task lists do not work", it was that
    * their text vanished SILENTLY. Loud rejection removes the silence. It does not add
    * support, and support was never the requirement.
    */
  case UnsupportedTaskList(headingPath: String)

  /** An `![[embed]]` in a card body. v0 builds no media pipeline; per the rendering ruling
    * an embed is rejected loudly rather than silently producing a card with a broken image.
    */
  case UnsupportedEmbed(headingPath: String, target: String)

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
  case TwoField(key: CardKey, front: String, back: Body, directions: TwoFieldDirections)

  /** `#flashcard/3way` and `#flashcard/3way/all`, and a table's pair cards. */
  case ThreeField(
      key: CardKey,
      concept: String,
      descriptor: String,
      description: Body,
      directions: ThreeFieldDirections,
  )

  /** `#flashcard/cloze` — one note holding ALL of the section's deletions, so adding a
    * highlight adds a CARD to an existing note rather than churning the key.
    */
  case Cloze(key: CardKey, text: Body, deletions: NonEmptyVector[ClozeDeletion])

  /** A table's row card: the concept, with all its descriptors together.
    *
    * Emitted only when a row carries TWO OR MORE descriptors — with one it would merely
    * duplicate the single pair card. This is the card that preserves the relation; a
    * benefit divorced from its cost is trivia.
    */
  case TableRow(key: CardKey, concept: String, descriptors: NonEmptyVector[(String, String)])

object CardSpec:

  extension (spec: CardSpec)
    /** Every spec knows its own key. */
    def key: CardKey = spec match
      case TwoField(k, _, _, _)      => k
      case ThreeField(k, _, _, _, _) => k
      case Cloze(k, _, _)            => k
      case TableRow(k, _, _)         => k

    /** The Anki note type this spec creates. Behaviour on the sum type: the consumer asks,
      * the variant answers, rather than the consumer branching on which variant it holds.
      */
    def noteTypeName: String = spec match
      case TwoField(_, _, _, TwoFieldDirections.Forward) => Marker.NoteTypes.Basic
      case TwoField(_, _, _, TwoFieldDirections.Both)    => Marker.NoteTypes.BasicAndReversed
      case ThreeField(_, _, _, _, _)                     => Marker.NoteTypes.ConceptDescriptor
      case Cloze(_, _, _)                                => Marker.NoteTypes.Cloze
      // The row card is a plain Basic: concept on the front, all descriptors on the back.
      case TableRow(_, _, _)                             => Marker.NoteTypes.Basic

    /** Field name to value, in the note type's field order.
      *
      * For a three-field spec the first three are the ruled display order; the trailing
      * [[Marker.ThreeWayField]] is the conditional switch that makes Anki generate the
      * third card, empty unless the marker asked for all directions.
      */
    def fields: Vector[(String, String)] = spec match
      case TwoField(_, front, back, _) =>
        Vector(
          Marker.BasicFields.Front -> front,
          Marker.BasicFields.Back  -> back.value,
        )

      case ThreeField(_, concept, descriptor, description, directions) =>
        val threeWay = directions match
          case ThreeFieldDirections.All     => "1"
          case ThreeFieldDirections.Default => ""
        Marker.ConceptDescriptorFields.zip(Vector(concept, descriptor, description.value)) :+
          (Marker.ThreeWayField -> threeWay)

      case Cloze(_, text, _) =>
        // Deletions are applied to the text when the note is rendered; the note itself has
        // a single Text field holding all of them.
        Vector(
          Marker.ClozeFields.Text      -> text.value,
          Marker.ClozeFields.BackExtra -> "",
        )

      case TableRow(_, concept, descriptors) =>
        // Joining the descriptors is the minimal honest thing to do here. How this should
        // actually LOOK is a rendering concern and may be revised when fields are rendered
        // to HTML; the structure — concept front, all descriptors together on the back — is
        // what matters, since a benefit divorced from its cost is trivia.
        val back = descriptors.toVector.map((d, v) => s"$d: $v").mkString("\n")
        Vector(
          Marker.BasicFields.Front -> concept,
          Marker.BasicFields.Back  -> back,
        )
