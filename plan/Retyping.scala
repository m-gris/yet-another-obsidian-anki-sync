package obsidiananki.plan

import cats.syntax.all.*
import obsidiananki.anki.Anki

/** When a note may be moved from one Anki note type to another, and when it may not.
  *
  * THE ONE THING AT RISK IS A CARD'S ORDINAL. Every card of a note carries an ordinal, and a
  * note type decides what an ordinal MEANS: on a standard type it is an index into the card
  * templates, on a cloze type it is the cloze number minus one. Moving a note between two note
  * types keeps the cards and their ordinals — the note type underneath them changes. So a
  * card can end up holding an ordinal its new note type cannot generate.
  *
  * WHAT ANKI THEN DOES WITH THAT CARD IS NOT ESTABLISHED IN THIS REPOSITORY, and saying so is
  * the point of this file. AnkiConnect's `updateNoteModel` sets the note's notetype id and
  * hands the note to `Collection.update_note`, which is a one-line call into Anki's Rust
  * backend (`anki/collection.py:507-509` and `anki/notes.py:70-80`, both read on this machine
  * on 2026-08-21; the backend itself is compiled and was not read). Whether the stranded card
  * survives, is orphaned until someone runs Check Database, or is destroyed outright decides
  * whether this operation costs review history — and answering it requires writing to a real
  * collection, which no agent has done.
  *
  * SO THE GATE ADMITS ONLY THE REGION WHERE THE QUESTION CANNOT ARISE. Two note types are
  * compatible when they are both cloze or both standard, AND have the same number of card
  * templates. Then every ordinal that exists is an ordinal the new type generates, by
  * arithmetic rather than by trust:
  *
  *   - standard to standard, same template count `n`: every ordinal is below `n` on the old
  *     type, so every ordinal is below `n` on the new one;
  *   - cloze to cloze: an ordinal is a cloze number, and a cloze type generates a card for
  *     whichever cloze numbers its text holds, whatever its template count.
  *
  * EVERYTHING ELSE IS REFUSED, INCLUDING CASES THAT ARE PROBABLY FINE. Moving from one
  * template to two — a heading retagged `#flashcard/1way` to `#flashcard/2way` — cannot strand
  * a card, but it needs Anki to GENERATE the second one, and non-generation is the silent
  * failure [[SyncAction.Retype]] exists to prevent. Both directions are unmeasured, so neither
  * is taken. This is a deliberately narrow gate rather than a permanent ruling: measuring the
  * behaviour once, in a throwaway profile, is what would widen it.
  *
  * THE REMEDY OFFERED IS ANKI'S OWN, and it is a real one rather than a shrug: Browse, select
  * the notes, Notes > Change Note Type. That dialogue maps templates and fields explicitly and
  * shows what it will do, which is exactly the decision this tool declines to take blind.
  */

/** As much of a note type's shape as this decision needs.
  *
  * DELIBERATELY NOT THE TEMPLATES THEMSELVES. What their names are, and what they render, has
  * no bearing on whether an ordinal is generatable; carrying them would invite a later check
  * that compares them and refuses a note type someone has merely restyled.
  */
final case class NoteTypeShape(templateCount: Int, isCloze: Boolean)

/** Why a note may not be moved between two note types.
  *
  * BOTH SHAPES ARE NAMED IN THE MESSAGE. "Incompatible note types" tells the reader nothing
  * they can act on; the counts are what makes it obvious that a `2way` heading was retagged
  * `1way`, or that a cloze section became a plain one.
  */
enum RetypeRefusal:
  case ClozeKindDiffers(from: String, fromIsCloze: Boolean, to: String, toIsCloze: Boolean)
  case TemplateCountDiffers(from: String, fromCount: Int, to: String, toCount: Int)

  private def kindOf(isCloze: Boolean): String = if isCloze then "a cloze" else "a standard"

  def describe: String = this match
    case ClozeKindDiffers(from, fromIsCloze, to, toIsCloze) =>
      s"'$from' is ${kindOf(fromIsCloze)} note type and '$to' is ${kindOf(toIsCloze)} one. " +
        "A card's ordinal means a template on one and a cloze number on the other, so the " +
        "existing cards would carry ordinals the new note type does not generate"
    case TemplateCountDiffers(from, fromCount, to, toCount) =>
      s"'$from' has $fromCount card template(s) and '$to' has $toCount. " +
        "Moving a note between note types keeps its cards and their ordinals, and this tool " +
        "has not established what Anki does with a card whose ordinal the new note type " +
        "cannot generate"

  /** The sentence that follows the reason wherever this is reported. Written once, here,
    * because it is the same remedy in every case and a second copy would drift from this one.
    */
  def remedy: String =
    "this tool will not move it; do it in Anki if you want it — Browse, select the notes, " +
      "Notes > Change Note Type, which maps templates and fields explicitly"

/** Whether a run is permitted to move notes between note types.
  *
  * OFF BY DEFAULT AT THE COMMAND LINE, and the reason is not timidity. A single sync run can
  * carry hundreds of these, each of which blanks a note's fields and replaces its whole tag
  * set before writing them back — an operation this tool had never performed against any
  * collection until it was asked to. Doing that as a side effect of a routine reconcile would
  * be a schema-level change to somebody's collection that nobody asked for, which is the same
  * objection that keeps `install-note-types` a command of its own.
  *
  * DEFERRING IS NOT SKIPPING. A deferred action is reported as its own outcome, separately
  * from failures, and it makes the run non-clean — so it cannot be mistaken for "nothing to
  * do", which is the failure shape this project keeps finding.
  */
enum RetypePolicy:
  case Defer
  case Apply

/** WHAT A RUN WILL ACTUALLY DO WITH ONE RETYPE — the whole decision, as a value.
  *
  * IT EXISTS BECAUSE THE DECISION HAD TWO HALVES IN TWO PLACES AND ONLY ONE CALLER COULD SEE
  * BOTH. The policy half ([[RetypePolicy]]) is pure and was consulted by the report, so a dry
  * run rendered it correctly. The shape half ([[Retyping.refusalFor]]) needs the note types
  * read out of the collection, so it lived inside `Executor.runOne` — and a dry run never
  * reaches the executor. The result was a preview that promised a migration the real run then
  * refused: `--dry-run --migrate-note-types` printed `1 move to another note type` and
  * `result: OK` over a move that could not happen.
  *
  * A PREVIEW THAT DISAGREES WITH THE RUN IT PREVIEWS IS WORSE THAN NO PREVIEW, because it is
  * believed. So the decision is now one total function over both halves, and both the preview
  * and the run ask it rather than each deciding a part. They cannot drift while there is only
  * one of it.
  */
enum RetypeVerdict:

  /** The policy allows it and the shapes permit it. */
  case WillApply

  /** The policy withholds it. NOT a refusal: the tool was not asked, and says so. */
  case DeferredByPolicy

  /** The policy allows it and the shapes forbid it — declining on EVIDENCE, not on
    * instruction, which is why it is reported as a failure rather than as a deferral.
    */
  case RefusedByShapes(refusal: RetypeRefusal)

  /** A note type the plan names could not be measured. Distinct from a refusal on purpose:
    * refusing says the move is wrong, this says the question could not be asked.
    */
  case ShapesUnavailable(from: String, to: String)

object Retyping:

  /** THE ONE PLACE THAT DECIDES. Pure, so a preview costs nothing beyond the shape reads it
    * shares with the run, and so every branch is drivable without a collection.
    *
    * THE POLICY IS ASKED FIRST, and that ordering is load-bearing rather than stylistic. Under
    * [[RetypePolicy.Defer]] no shapes are read at all — a deferred run must not pay for two
    * requests per note type to answer a question whose answer it has already declined to act
    * on. So `shapes` is legitimately empty there, and consulting it first would turn every
    * deferral into a [[RetypeVerdict.ShapesUnavailable]].
    */
  def verdictFor(
      from: String,
      to: String,
      policy: RetypePolicy,
      shapes: Map[String, NoteTypeShape],
  ): RetypeVerdict = policy match
    case RetypePolicy.Defer => RetypeVerdict.DeferredByPolicy
    case RetypePolicy.Apply =>
      (shapes.get(from), shapes.get(to)) match
        case (Some(fromShape), Some(toShape)) =>
          refusalFor(from, fromShape, to, toShape)
            .fold(RetypeVerdict.WillApply)(RetypeVerdict.RefusedByShapes(_))
        case _ => RetypeVerdict.ShapesUnavailable(from, to)


  /** PURE, so every branch is drivable without a collection.
    *
    * `None` means the move is admissible. The cloze test comes first because it is the one
    * whose failure mode is unbounded: a cloze note may hold any number of cards regardless of
    * how many templates its type has, so a count comparison would pass while the ordinals ran
    * far past anything the new type could generate.
    */
  def refusalFor(
      from: String,
      fromShape: NoteTypeShape,
      to: String,
      toShape: NoteTypeShape,
  ): Option[RetypeRefusal] =
    if fromShape.isCloze != toShape.isCloze then
      Some(RetypeRefusal.ClozeKindDiffers(from, fromShape.isCloze, to, toShape.isCloze))
    else if fromShape.templateCount != toShape.templateCount then
      Some(RetypeRefusal.TemplateCountDiffers(from, fromShape.templateCount, to, toShape.templateCount))
    else None

  /** Read the shape of each named note type, ONCE per name.
    *
    * De-duplicated because a migration is many notes over very few note-type pairs — the run
    * that moves a whole collection off Anki's stock types touches three — and this is two
    * requests per name against a collection somebody is waiting on.
    *
    * A REFUSAL HERE FAILS THE WHOLE READ rather than being collected per name. These are reads
    * of note types that demonstrably exist (a note is sitting on one of them, and `sync`'s
    * preflight has already confirmed the other), so a refusal means something is wrong with
    * the collection or the connection rather than with one note type.
    */
  def shapesOf[F[_]: cats.Monad](anki: Anki[F], names: Vector[String]): F[Map[String, NoteTypeShape]] =
    names.distinct
      .traverse { name =>
        for
          templates <- anki.noteTypeTemplates(name)
          isCloze   <- anki.noteTypeIsCloze(name)
        yield name -> NoteTypeShape(templates.size, isCloze)
      }
      .map(_.toMap)

  /** Every note type named by a retype in this plan, both sides, de-duplicated. */
  def noteTypesIn(plan: Plan): Vector[String] =
    plan.actions.collect { case SyncAction.Retype(_, _, from, to, _, _, _, _) => Vector(from, to) }.flatten.distinct
