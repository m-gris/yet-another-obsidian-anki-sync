package obsidiananki.plan

import obsidiananki.anki.AnkiNoteId
import obsidiananki.model.CardKey

/** ONE AUTOMATIC MOVE OF REVIEW HISTORY FROM ONE CARD KEY TO ANOTHER, AS DATA.
  *
  * ═══ WHY IT EXISTS, AND WHAT "SILENTLY" WAS RULED TO MEAN ═══
  *
  * Marc ruled on 2026-09-12 (`docs/design/IDENTITY-DECISION-SHEET.md`, Decision 1) that a plain
  * card whose own heading changed is followed WITHOUT INTERRUPTING THE SYNC — and, in the same
  * breath, that the audit trail "should probably have been offered from the start" and be used
  * "ALL the time". So the apparent choice between acting silently and recording what was done was
  * a false dichotomy: "SILENTLY" MEANS UNINTERRUPTED AT SYNC TIME, NEVER UNRECORDED. Every
  * automatic history-carrying action is written down, durably, whether or not anybody is watching.
  *
  * ═══ WHY REVIEW HISTORY IS THE THING WORTH RECORDING ═══
  *
  * It is the one piece of state this tool cannot recompute. Fields, tags, decks and the identity
  * field are all derived from the vault, so a wrong one is repaired by the next run. Intervals,
  * ease and a card's whole review log exist only in the collection, and a reassignment attaches
  * them to a DIFFERENT card key on evidence that is strong but not a proof. The ledger is what
  * makes such a decision checkable afterwards by somebody who was not there.
  *
  * ═══ WHAT IT CARRIES, AND WHY EACH FIELD IS HERE ═══
  *
  * BOTH KEYS, not a description of the difference: a reader reconstructing "what used to hold this
  * history" needs the key it left, and one asking "what holds it now" needs the key it arrived at.
  * Either alone makes the other unrecoverable.
  *
  * THEY ARE [[CardKey]]s RATHER THAN ANY RENDERING OF ONE. How a key reaches a file — encoded so a
  * program can decode it, spelled out so a person can grep it, or both — is a decision about the
  * wire, and `cli/Ledger.scala` makes it. Pre-rendering here would put half the format in the core
  * and leave the other half at the edge, which is how two halves of one decision come to disagree.
  *
  * THE GRADE, so a line says on what evidence the move was made. The four cases of [[Agreement]]
  * were four different events before 2026-09-12 and are four different RULINGS after it.
  *
  * THE DIVERGENCES, WHICH ARE WHAT MAKE A LINE CHECKABLE FROM THE LEDGER ALONE. Without them a
  * line says two keys and a grade, and answering "was that the right call?" means re-deriving the
  * evidence from a vault that has since moved on. With them the line carries the observation
  * itself. Mandatory since the 2026-09-04 ruling that no finding may travel without its full
  * divergence list.
  *
  * ═══ WHAT IS DELIBERATELY NOT A FIELD ═══
  *
  * WHEN IT HAPPENED AND WHICH RUN DID IT. Both belong to the APPEND rather than to the act: a run
  * appends all of its moves at once, so one instant and one run id serve the whole batch, and
  * putting them here would be N copies of one fact — N copies that a caller could then make
  * disagree. `cli/Ledger.scala` stamps them as it writes, where they are known.
  */
final case class HistoryMove(
    ankiNote: AnkiNoteId,
    from: CardKey,
    to: CardKey,
    grade: Agreement,
    noteType: String,
    divergences: Vector[Divergence],

    /** WAS THE NOTE RELEASED FROM THE ORPHAN PEN AS PART OF THIS MOVE — one field, not a second
      * entry.
      *
      * An `Unflag` and the `Reassign` that follows it are ONE EVENT: the note was parked by an
      * earlier run, its heading turned out to be alive somewhere else, and this run unsuspended it
      * and re-keyed it. A second line would tell a reader that two things happened to one card;
      * no line at all would lose the fact that a card had stopped being studied and was put back
      * into the rotation.
      */
    unflagged: Boolean,
)

object HistoryMove:

  /** EVERY HISTORY-CARRYING ACTION AMONG THE ONES ABOUT TO BE CARRIED OUT.
    *
    * ═══ IT TAKES THE ACTIONS RATHER THAN THE [[Plan]], AND THAT IS THE SAFETY ARGUMENT ═══
    *
    * It is called with exactly the vector [[Executor]] is about to hand to Anki — after every
    * policy has done its setting-aside — so "what was recorded" and "what was attempted" are two
    * readings of ONE value and cannot drift. Given the whole plan instead, a policy that withheld
    * a reassignment would have it recorded as done, and nothing in this file would notice. Today
    * only retypes are ever withheld, so the two vectors agree; the point is that they cannot stop
    * agreeing.
    *
    * ═══ `Reassign` IS THE WHOLE POPULATION, BY A PROPERTY OF THE ALGEBRA ═══
    *
    * It is the only action that attaches an existing note's review log to a key it did not
    * previously claim. `Create` starts at zero, `Update` and `Retype` keep the key they have, and
    * `Flag`/`Unflag` change whether a card is studied rather than which card it is. So this is not
    * a filter somebody chose and could choose differently — it is the answer to "what moves review
    * history?" read off `plan/SyncAction.scala`.
    *
    * Total and pure, so the whole contract is testable as data: no reassignment in, nothing out,
    * which is what makes "a mere content update writes no ledger line" an assertion rather than a
    * hope.
    */
  def inActions(actions: Vector[SyncAction]): Vector[HistoryMove] = ???

/** WHERE A RUN WRITES DOWN WHAT IT IS ABOUT TO DO TO SOMEBODY'S REVIEW HISTORY.
  *
  * ONE METHOD TAKING THE WHOLE RUN'S MOVES AT ONCE, because the ordering guarantee is about the
  * RUN and not about each move: everything is recorded BEFORE the first write, so an interruption
  * can leave a move recorded-and-not-applied and never applied-and-not-recorded. That is
  * `plan/Executor.scala`'s own rule — when an interruption must leave one of two wrong states,
  * choose the one that makes work be REDONE rather than BELIEVED DONE — and a duplicate line is
  * bounded and visible where an unrecorded write is neither. Appending per move, interleaved with
  * the writes, would forfeit exactly that.
  *
  * THE EMPTY CALL IS A REAL CALL. A run with nothing to record still says so, and whether that
  * touches the file is the implementation's business. The executor does not decide to skip it,
  * because "skip the ledger when …" is the shape of thing that grows a second condition.
  *
  * A FAILURE TO RECORD ABORTS THE RUN, and it does so by the ordinary means rather than by a
  * branch: `record` is sequenced first, so a failed effect short-circuits everything after it and
  * nothing reaches Anki. There is nothing here to forget and no `catch` that could turn an
  * unrecordable reassignment into one that happens anyway.
  *
  * THERE IS DELIBERATELY NO NO-OP IMPLEMENTATION IN PRODUCTION CODE. One that discarded what it
  * was given would satisfy every type in this file while defeating the ruling that caused the file
  * to exist — silently, for whoever wired it. The fakes are compiled only into the test
  * configuration; see `plan/RecordedNowhere.test.scala`.
  */
trait Ledger[F[_]]:
  def record(moves: Vector[HistoryMove]): F[Unit]
