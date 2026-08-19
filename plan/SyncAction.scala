package obsidiananki.plan

import cats.data.NonEmptyVector
import obsidiananki.anki.{AnkiNoteId, DeckPath, NewNote}
import obsidiananki.model.CardKey

/** What the reconciler concluded, and what it may not conclude.
  *
  * The model separates WHAT DIFFERS from WHAT CALL TO MAKE. A deck move is a CHANGE, not a
  * kind of action, which is why "edited and moved" needs no special case.
  */

/** One dimension along which an existing note differs from what the markdown now says.
  *
  * Each change carries its own NEW VALUE, so an action is self-contained: an executor needs
  * no lookup table and no callback to carry it out, and a plan can be printed, reviewed or
  * stored without losing what it would actually do.
  */
enum Change:
  /** The rendered fields differ, together with the content hash of the new value.
    *
    * The hash travels with the fields because the two must be written together and IN THAT
    * ORDER — hash first. An interruption after the fields but before the hash would leave a
    * stale hash claiming the note is already correct, and every later run would skip it
    * silently and permanently.
    */
  case FieldsChanged(fields: Vector[(String, String)], newSha: String)

  case DeckChanged(from: Option[DeckPath], to: DeckPath)

enum SyncAction:
  /** In the markdown, not in Anki. The only case that legitimately carries no note id.
    *
    * The identity tag travels inside [[NewNote]], so it is written by the call that creates
    * the note. A note created without it would be unenumerable rather than merely unmatched.
    */
  case Create(key: CardKey, note: NewNote)

  /** In both, and differing.
    *
    * The note id is carried INSIDE the case rather than beside it, so "update something we
    * never found" cannot be constructed. The change set is non-empty, so "update that
    * changes nothing" cannot be constructed either — and it must not be, because the
    * `sha::` tag decides that before any call is made.
    */
  case Update(key: CardKey, noteId: AnkiNoteId, changes: NonEmptyVector[Change])

  /** The marker changed, so the NOTE TYPE must change.
    *
    * Its own case because the failure it prevents is SILENT SUCCESS: `Basic` and
    * `Basic (and reversed card)` share field names, so an ordinary field update succeeds,
    * reports success, and the reverse card the author asked for never exists.
    *
    * Whether this can be executed while preserving scheduling depends on an AnkiConnect
    * capability that has not been verified. If it cannot, this becomes confirm-required
    * rather than automatic, since the alternative destroys review history.
    */
  case Retype(key: CardKey, noteId: AnkiNoteId, from: String, to: String)

  /** In Anki, not in the markdown. Flagged, never deleted.
    *
    * Derivable ONLY from a complete scan. A partial scan cannot produce this case because
    * the data needed to justify it was never gathered.
    */
  case Flag(key: CardKey, noteId: AnkiNoteId)

  /** Previously flagged, now present again. Clears the orphan tag.
    *
    * Without this the flag set only grows, and a stale orphan becomes indistinguishable
    * from a live one in the list a human reviews before pruning.
    */
  case Unflag(key: CardKey, noteId: AnkiNoteId)

  /** A proposed pairing between an orphan and an unmatched key, for a human to confirm.
    *
    * Deliberately a PROPOSAL. Rename detection is heuristic, and a heuristic must not drive
    * an irreversible operation — the same argument that made deletion flag-then-prune.
    */
  case Relink(orphanKey: CardKey, orphanNoteId: AnkiNoteId, candidate: CardKey)

/** Why a plan could not be produced. Nothing is written when any of these is present. */
enum PlanError:
  /** Two sources derived the SAME key.
    *
    * Both sides name themselves — key, and each source's file, line and kind — because a
    * collision between a table cell and a deeply-nested heading is otherwise a message that
    * teaches nothing. Legibility here is a contract, not a nicety.
    */
  case DuplicateKey(key: CardKey, first: SourceRef, second: SourceRef)

  def describe: String = this match
    case DuplicateKey(key, first, second) =>
      s"duplicate card key '${key.path.render}' (note '${key.noteId.value}') derived from " +
        s"two sources: ${first.describe} and ${second.describe}"

/** Whether orphans were computed, and if not, why not. Reported rather than silent: a run
  * that could not look for orphans must not be mistaken for a run that found none.
  */
enum OrphanInference:
  case Computed
  case SuppressedIncompleteScan(reason: String)

final case class Plan(
    actions: Vector[SyncAction],
    orphanInference: OrphanInference,
    failures: Vector[BuildFailure],
)
