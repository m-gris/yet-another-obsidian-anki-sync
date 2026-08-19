package obsidiananki.anki

import cats.data.NonEmptyVector
import obsidiananki.model.OwnedTag

/** The Anki operations this tool needs, as an algebra.
  *
  * Two interpreters are intended: one over AnkiConnect's HTTP API, and one wholly in
  * memory. The in-memory one is a FAKE, not a mock — it stores notes and answers queries,
  * so tests assert on outcomes rather than on which calls were made. Crucially it also
  * enforces Anki's real constraints (see [[InMemoryAnki]]), which is what lets the tag
  * encoding be tested without touching a live collection.
  */

opaque type AnkiNoteId = Long
object AnkiNoteId:
  def apply(raw: Long): AnkiNoteId = raw
  extension (id: AnkiNoteId) def value: Long = id

opaque type AnkiCardId = Long
object AnkiCardId:
  def apply(raw: Long): AnkiCardId = raw
  extension (id: AnkiCardId) def value: Long = id

/** A deck path under the tool's root prefix.
  *
  * Decks mirror FOLDER paths and the file is deliberately not a deck level, or every
  * concept would become its own two-card deck. Decks carry filing only, never learning
  * order: study scope comes from filtered decks over tags, introduction order from
  * new-card position. Conflating the three is what sank the earlier design.
  */
final case class DeckPath(segments: NonEmptyVector[String]):
  /** Anki's own hierarchy separator. */
  def render: String = segments.toVector.mkString("::")

/** A note to create.
  *
  * TAGS ARE PART OF CREATION, NOT A FOLLOW-UP. `addNote` accepts them inline, and B12
  * requires it: a note created without its `src::` tag is not merely unmatched, it is
  * UNENUMERABLE — invisible to the key lookup, the reconciler and prune, permanently.
  * Making tags a field of this record rather than a separate call closes the window
  * structurally instead of narrowing it.
  */
final case class NewNote(
    noteType: String,
    deck: DeckPath,
    fields: Vector[(String, String)],
    tags: NonEmptyVector[OwnedTag],
)

/** A note as read back out of Anki.
  *
  * NOTE THE ASYMMETRY IN THE TAG TYPES, which is deliberate and is the ownership rule made
  * structural: reads carry RAW `String` tags because Anki returns everything, including
  * tags a person applied themselves; writes take [[OwnedTag]] because the tool may only
  * ever set its own. You cannot accidentally write back a tag you merely observed.
  */
final case class ObservedNote(
    id: AnkiNoteId,
    noteType: String,
    fields: Vector[(String, String)],
    tags: Vector[String],
)

enum AnkiError:
  /** An action the planner can legitimately produce but the executor cannot yet carry out.
    *
    * Exists so that "not implemented" is LOUD. A silent no-op here would report "nothing to
    * do" while the change the author asked for never happened — which is the failure the
    * action was carved out to prevent, reintroduced one layer down.
    */
  case UnsupportedOperation(what: String, why: String)

  /** Anki tags are whitespace-delimited; a tag containing whitespace is torn into
    * fragments. This is the failure that invalidated the original identity scheme.
    */
  case TagContainsWhitespace(tag: String)
  case NoSuchNote(id: AnkiNoteId)
  case NoSuchNoteType(name: String)
  case UnknownField(noteType: String, field: String)
  case DuplicateNote(firstFieldChecksumOf: String)

  /** Anki answered, and refused, with a message this tool does not classify further.
    *
    * NOT the same thing as being unable to reach Anki, and the difference decides what
    * happens next. A refusal is a fact about ONE action: the executor records it and carries
    * on, so one bad card does not abandon the other forty-nine. Being unable to reach Anki
    * is not an action-level fact and is deliberately NOT modelled here — it stays a
    * transport failure and aborts the run, because collecting fifty identical "connection
    * refused" entries would describe a dead collection as forty-nine ordinary problems.
    */
  case Remote(action: String, message: String)

  /** Anki answered with something this tool cannot read.
    *
    * Separate from [[Remote]] because the remedy differs: a refusal is Anki working
    * correctly and saying no, whereas this means the wire shape is not the one that was
    * verified — a version change, or an assumption that was wrong from the start.
    */
  case MalformedResponse(action: String, detail: String)

trait Anki[F[_]]:

  /** Note types present in the collection, BY NAME. Ids are collection-local and must never
    * be hardcoded: the id in the design documents came from a different profile's backup.
    */
  def noteTypeNames: F[Vector[String]]

  /** Field names of a note type, in the collection's own order. */
  def fieldNames(noteType: String): F[Vector[String]]

  /** Every note carrying a tag under the given prefix.
    *
    * ONE bulk query, not one per card. A per-card lookup driven by markdown keys can never
    * detect an orphan, because an orphan is by definition a key present in Anki and absent
    * from markdown — invisible to a loop over markdown's keys.
    */
  def findNotesByTagPrefix(prefix: String): F[Vector[AnkiNoteId]]

  def notesInfo(ids: Vector[AnkiNoteId]): F[Vector[ObservedNote]]

  def addNote(note: NewNote): F[AnkiNoteId]

  /** Note that this has NO early-out: Anki bumps the modification stamp even when the text
    * is identical. "Zero changes on re-run" must therefore be decided BEFORE calling this,
    * which is what the `sha::` tag is for.
    */
  def updateNoteFields(id: AnkiNoteId, fields: Vector[(String, String)]): F[Unit]

  def addTags(ids: Vector[AnkiNoteId], tags: Vector[OwnedTag]): F[Unit]

  def removeTags(ids: Vector[AnkiNoteId], tags: Vector[OwnedTag]): F[Unit]

  /** The cards belonging to these notes.
    *
    * Needed because identity is per-NOTE while decks and scheduling are per-CARD, and a
    * three-field note has up to three cards. This is the note/card impedance point of the
    * whole design.
    */
  def cardsOf(ids: Vector[AnkiNoteId]): F[Vector[AnkiCardId]]

  def deckOf(card: AnkiCardId): F[Option[DeckPath]]

  def changeDeck(cards: Vector[AnkiCardId], deck: DeckPath): F[Unit]
