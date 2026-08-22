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

  /** Whether Anki classes this note type as a CLOZE type.
    *
    * NEEDED FOR EXACTLY ONE DECISION, and it is worth saying which so that nobody widens it:
    * [[obsidiananki.plan.Retyping]] refuses to move a note between two note types unless both
    * are cloze or neither is. A card's ORDINAL means a template index on a standard type and a
    * cloze NUMBER on a cloze type, so moving a note across that boundary hands its cards
    * ordinals the new type cannot generate — and what Anki then does with them is not
    * established anywhere in this repository.
    *
    * IT CANNOT BE INFERRED FROM THE NAME, THE STYLESHEET OR THE TEMPLATES, which is why it is
    * an operation rather than a heuristic. `Obsidian Cloze Sequence` has "Cloze" in its name,
    * defines `.cloze` and `.hidden-cloze` in its stylesheet, and is NOT a cloze type — its
    * front renders `{{Text}}`, a plain field reference. [[NoteTypeManifest]] records the same
    * warning for the flag this tool AUTHORS; this is the read side of it.
    *
    * THE COST, NAMED: the AnkiConnect interpreter answers this from `findModelsByName`, which
    * returns Anki's whole internal note-type dictionary. That is a larger surface than this
    * tool otherwise touches. Only the `type` key is read, and its two values were read out of
    * `anki/consts.py` on this machine on 2026-08-21 — `MODEL_STD = 0`, `MODEL_CLOZE = 1`.
    */
  def noteTypeIsCloze(noteType: String): F[Boolean]

  /** Create a note type. NOT AN UPSERT — Anki refuses a name that already exists.
    *
    * This is the one operation in the algebra that changes the SHAPE of a collection rather
    * than its contents, and the reason it is here is that without it a fresh profile cannot be
    * synced into at all: the first `addNote` fails because the note type does not exist.
    *
    * THERE IS NO `updateNoteType` TAKING A WHOLE SPEC, and that part is still deliberate.
    * Repairing a note type in place means overwriting templates and a stylesheet a person may
    * have edited, and the safe default this tool takes is that nothing it did not create is
    * overwritten without being asked. What exists instead is the three NARROW operations below:
    * each changes exactly one thing, and none is reached unless a human asked for a repair —
    * see [[obsidiananki.anki.NoteTypeInstaller.repair]].
    *
    * AMENDED 2026-08-21, and the reason is worth keeping. This docstring previously said no such
    * operation existed AT ALL and treated that as the safe position. It was not safe. A note
    * type that already exists was never touched, so the two types this tool inherited by
    * hand-rename kept the collection's own templates, which mention no `Context` field. That
    * field was then computed, written, hashed and synced onto 21 of 43 live notes AND RENDERED
    * NOWHERE. Refusing to write is only safe when the refusal is VISIBLE; here it produced a
    * feature that existed everywhere except on the screen. The principle survives; the blanket
    * prohibition does not.
    */
  def createNoteType(spec: NoteTypeSpec): F[Unit]

  /** Add ONE field to a note type that already exists.
    *
    * IDEMPOTENT AT THE FAR END, read out of the add-on installed on this machine rather than
    * assumed: `__init__.py:1437-1441` guards with `if fieldName not in fieldMap`, so adding a
    * field that is already there is a no-op rather than an error or a duplicate.
    *
    * ONE FIELD PER CALL, because that is the shape of the underlying action and because a
    * partial failure is then unambiguous — the caller knows exactly which field was added.
    *
    * THE `index` PARAMETER IS NOT EXPOSED. Anki appends, and this tool writes fields BY NAME, so
    * position affects the Browse column order and nothing this tool depends on. Exposing it
    * would invite reordering a person's fields for cosmetic reasons.
    */
  def addNoteTypeField(noteType: String, field: String): F[Unit]

  /** Replace the front and back of templates that ALREADY EXIST, matched BY NAME.
    *
    * THREE PROPERTIES OF THE UNDERLYING ACTION THAT A CALLER MUST KNOW, all read out of
    * `__init__.py:1294-1312` on this machine, none of them inferred:
    *
    *   1. IT CANNOT ADD OR REMOVE A TEMPLATE. It iterates the templates the collection already
    *      holds and updates whichever of them it was given.
    *   2. A TEMPLATE NAME IT CANNOT FIND IS SILENTLY SKIPPED — `templates.get(name)` then
    *      `if template:`. No error, no report, a clean exit having done nothing. That is this
    *      project's signature failure mode arriving through somebody else's API, so a caller
    *      MUST check the names BEFORE calling rather than trusting the result afterwards; see
    *      [[NoteTypeDrift.TemplateNamesDiffer]].
    *   3. AN EMPTY FRONT OR BACK IS IGNORED RATHER THAN WRITTEN — `if qfmt:` / `if afmt:`. A
    *      template cannot be blanked through this action.
    */
  def setNoteTypeTemplates(noteType: String, templates: Map[String, CardTemplate]): F[Unit]

  /** Replace a note type's whole stylesheet.
    *
    * WHOLESALE, NOT A MERGE: `__init__.py:1322` is a bare assignment to `ankiModel['css']`, so
    * anything a person added to that stylesheet by hand is gone. That is why this is reached
    * only from an explicitly requested repair, and why the repair reports what differs first.
    */
  def setNoteTypeStyling(noteType: String, css: String): F[Unit]

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

  /** Move a note ONTO A DIFFERENT NOTE TYPE, rewriting its fields and its whole tag set.
    *
    * THE WHOLE FIELD SET AND THE WHOLE TAG SET ARE PARAMETERS BECAUSE ANKI DESTROYS BOTH.
    * Read out of the installed add-on's own source on 2026-08-21
    * (`addons21/2055492159/__init__.py:849-899`, the `updateNoteModel` action):
    *
    *   - `anki_note.fields = [''] * len(new_model['flds'])` blanks EVERY field of the new type
    *     before any value is written, so a field this call does not name is left empty.
    *   - the write loop matches names CASE-INSENSITIVELY and simply never assigns a name the
    *     new type does not have — no error, no report. A misspelled field name is therefore a
    *     silently emptied field.
    *   - `anki_note.tags = note.get('tags', [])` is UNCONDITIONAL. Omitting `tags` does not
    *     preserve them, it ERASES them — the opposite of `updateNote`, where omitting the key
    *     preserves. For this tool an erased tag set is an erased `src::` identity, which makes
    *     the note not merely unmatched but UNENUMERABLE: invisible to the key lookup, the
    *     reconciler and prune, permanently, with no later call able to find it again.
    *
    * THE TAGS ARE SPLIT IN TWO, and the split is the ownership rule from [[ObservedNote]] made
    * structural rather than remembered. `ownedTags` are AUTHORED — the `src::` identity and the
    * new `sha::` content hash. `preservedTags` are ECHOED: raw strings the caller read back off
    * this very note, passed through verbatim because Anki will otherwise drop them. The tool
    * still cannot MINT a foreign tag; it can only hand back one it has seen.
    *
    * `ownedTags` IS NON-EMPTY so that a call which would leave the note without its identity
    * cannot be written at all — the same reason [[NewNote.tags]] is non-empty.
    *
    * ONE CALL, NOT THREE. Fields, note type and hash all land together or none of them do, so
    * unlike an [[updateNoteFields]]-then-tag sequence there is no window in which the note
    * holds new content under a stale hash. Nothing here needs the fields-first-hash-last
    * ordering that [[obsidiananki.plan.Change.FieldsChanged]] documents.
    *
    * WHAT THIS DOES **NOT** PROMISE. Whether an existing card whose ordinal the new note type
    * cannot generate survives, is orphaned, or is destroyed is NOT established in this
    * repository: the decision is taken by Anki's Rust backend (`Collection.update_note` in
    * `anki/collection.py` is a one-line call into it, read on this machine 2026-08-21) and
    * establishing it needs a write to a real collection. [[obsidiananki.plan.Retyping]] is the
    * gate that keeps every caller out of that region; this operation itself is unguarded.
    */
  def changeNoteType(
      id: AnkiNoteId,
      to: String,
      fields: Vector[(String, String)],
      ownedTags: NonEmptyVector[OwnedTag],
      preservedTags: Vector[String],
  ): F[Unit]

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

  /** Take cards OUT of the review queue without changing anything else about them.
    *
    * WHAT SUSPENSION IS, precisely, because the alternatives all lose something this does not:
    * a suspended card keeps its deck, its interval, its ease, its due date and its whole review
    * log, and is simply never shown. Unsuspending puts it back exactly where it was. It is the
    * only mechanism Anki has that removes a card from study while destroying nothing.
    *
    * WHY IT IS NEEDED. A card whose heading has gone gets an `orphaned::` tag today and NOTHING
    * else, so it stays in the daily rotation and goes on asking a question the vault no longer
    * answers. Ruled 2026-08-19; unbuilt until now.
    *
    * PER CARD, WHILE FLAGGING IS PER NOTE, and that asymmetry is Anki's rather than this file's:
    * identity lives on the note and scheduling lives on the card, so a three-field note has up
    * to three cards to suspend. This is the same impedance point [[cardsOf]] exists for.
    *
    * IDEMPOTENT AT THE FAR END — suspending an already-suspended card is a no-op — so a re-run
    * that suspends the same orphan again costs a call and changes nothing.
    */
  def suspend(cards: Vector[AnkiCardId]): F[Unit]

  /** Put cards back in the queue, with the scheduling they had when they left it.
    *
    * THE OTHER HALF OF [[suspend]], AND NOT OPTIONAL. A heading that comes back must bring its
    * card back with it; without this the flag would be cleared, the card would look live in
    * every report, and it would never be shown again — a worse state than the one suspension
    * was introduced to fix, because it looks fixed.
    */
  def unsuspend(cards: Vector[AnkiCardId]): F[Unit]
