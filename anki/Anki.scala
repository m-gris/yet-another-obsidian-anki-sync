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

/** One card template's two sides, as Anki holds them.
  *
  * THE TEMPLATE'S NAME IS DELIBERATELY NOT IN HERE. `modelTemplates` returns templates keyed by
  * name, while `createModel` takes them as an ordered list — the name is a key in one shape and
  * a positional field in the other. Keeping it outside means neither shape carries a redundant
  * copy that could disagree with its own key.
  */
final case class CardTemplate(front: String, back: String)

/** A note type as `createModel` needs it: every file already resolved to its TEXT.
  *
  * `fields` AND `templates` ARE NON-EMPTY BY CONSTRUCTION. Anki has no note type with no
  * fields, and a note type with no templates generates no cards at all — something that looks
  * installed and produces nothing, which is this project's signature failure shape. Made
  * unrepresentable rather than checked.
  *
  * `templates` IS AN ORDERED VECTOR OF (name, template) PAIRS, never a map, because the order
  * IS the card ordinal in Anki and a map carries no order.
  *
  * `isCloze` IS CARRIED, NEVER INFERRED — see [[NoteTypeManifest]] for the note type that makes
  * every heuristic get it backwards.
  */
final case class NoteTypeSpec(
    name: String,
    isCloze: Boolean,
    fields: NonEmptyVector[String],
    templates: NonEmptyVector[(String, CardTemplate)],
    styling: String,
)

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

  /** `createModel` was asked for a name the collection already holds.
    *
    * ANKI REFUSES THIS; `createModel` IS NOT AN UPSERT. Both interpreters raise it, which is
    * worth stating because the sibling case [[UnknownField]] does NOT have that property.
    *
    * THE WIRE MESSAGE IS EXACTLY `Model name already exists`, AND IT DOES NOT NAME THE MODEL.
    * Established in this repository by reading the running add-on's own source rather than by
    * exercising it (creating a model is a write, and the slice that added this had read-only
    * access to a live collection): `__init__.py:1126-1127` raises
    * `Exception('Model name already exists')`, and `web.py:289-290` puts `str(exception)` into
    * the envelope's `error` field verbatim. So the NAME in this case comes from the request
    * rather than from the response, which is why the mapping lives in
    * `AnkiConnectClient.createNoteType` and not in `AnkiConnect.classify` — `classify` sees the
    * message and never the parameters.
    *
    * NEITHER INTERPRETER REACHES IT ON THE HAPPY PATH: [[obsidiananki.anki.NoteTypeInstaller]]
    * reads `noteTypeNames` and creates only what is absent, so getting here means a note type
    * appeared between the read and the write, or a caller skipped the survey.
    */
  case NoteTypeExists(name: String)
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

  /** The card templates of a note type, KEYED BY TEMPLATE NAME.
    *
    * A MAP AND NOT AN ORDERED VECTOR, on purpose. AnkiConnect's `modelTemplates` answers with a
    * JSON OBJECT keyed by template name, and a JSON object carries no order that this tool is
    * entitled to rely on — depending on the order circe happened to preserve would be a bet on
    * an undocumented property of somebody else's serialiser. Card ORDER therefore comes from
    * [[NoteTypeSpec.templates]], which is ours; what comes back from a collection is only ever
    * compared by name.
    */
  def noteTypeTemplates(noteType: String): F[Map[String, CardTemplate]]

  /** A note type's complete stylesheet, exactly as the collection holds it. */
  def noteTypeStyling(noteType: String): F[String]

  /** Create a note type. NOT AN UPSERT — Anki refuses a name that already exists.
    *
    * This is the one operation in the algebra that changes the SHAPE of a collection rather
    * than its contents, and the reason it is here is that without it a fresh profile cannot be
    * synced into at all: the first `addNote` fails because the note type does not exist.
    *
    * THERE IS DELIBERATELY NO `updateNoteType`. Repairing a note type in place means overwriting
    * templates and a stylesheet a person may have edited, and the safe default this tool takes
    * is that nothing it did not create is silently overwritten — see
    * [[obsidiananki.anki.NoteTypeInstaller]], which reports differences and changes nothing.
    */
  def createNoteType(spec: NoteTypeSpec): F[Unit]

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
