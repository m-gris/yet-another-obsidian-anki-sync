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

  // A `Relink` case sat here: a proposed pairing between an orphan and an unmatched key, for
  // a human to confirm. REMOVED 2026-08-19, when automatic rename detection was cut from v0
  // as a subsystem in its own right rather than a feature.
  //
  // Removed rather than kept-and-guarded, which is the usual remedy here: this project holds
  // that a type may be designed AHEAD of its implementation, and that an unconnected type
  // should be implemented or guarded, not deleted. That rule protects a design still on the
  // way in. Cut is not ahead — nothing in v0 will ever produce this, so it had no owner.
  //
  // The reconciliation it was for now happens by hand, which is lossless because an orphan is
  // SUSPENDED rather than deleted and keeps its whole review history. What was learned while
  // exploring detection is recorded in the design document under "Deliberately deferred" —
  // notably that candidates are confined to the cards sharing one note id, and that the
  // vault's git history is the input to any semantic approach rather than an alternative.

/** Why a plan could not be produced. Nothing is written when any of these is present. */
enum PlanError:
  /** Two sources derived the SAME key.
    *
    * Both sides name themselves — key, and each source's file, line and kind — because a
    * collision between a table cell and a deeply-nested heading is otherwise a message that
    * teaches nothing. Legibility here is a contract, not a nicety.
    */
  case DuplicateKey(key: CardKey, first: SourceRef, second: SourceRef)

  /** Two ANKI NOTES carry the same identity tag — the same collision as [[DuplicateKey]],
    * arriving from the other side.
    *
    * Fatal for the same reason, and the symmetry is the point: this side was silent for as
    * long as the lookup was built with `.toMap`, which kept one note and made the other
    * invisible to every later run. Both note ids are named because the remedy is manual — a
    * human has to open both in Anki and decide which to keep.
    */
  case DuplicateIdentityInAnki(key: CardKey, first: AnkiNoteId, second: AnkiNoteId)

  def describe: String = this match
    case DuplicateKey(key, first, second) =>
      s"duplicate card key '${key.path.render}' (note '${key.noteId.value}') derived from " +
        s"two sources: ${first.describe} and ${second.describe}"
    case DuplicateIdentityInAnki(key, first, second) =>
      s"two Anki notes claim the card key '${key.path.render}' (note '${key.noteId.value}'): " +
        s"note ids ${first.value} and ${second.value} — open both in Anki and delete one"

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
