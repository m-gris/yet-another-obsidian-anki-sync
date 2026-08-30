package obsidiananki.plan

import cats.syntax.all.*
import obsidiananki.anki.{Anki, AnkiNoteId, CardStanding}

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
  * GROWTH IS NOW ADMITTED, BECAUSE IT WAS MEASURED. _Until 2026-08-26 this gate refused ANY
  * difference in template count, on the stated grounds that growing needs Anki to GENERATE the
  * extra cards and non-generation is silent. That was the right reflex and the wrong permanent
  * answer; the paragraph here said so, and said that measuring it once in a throwaway profile is
  * what would widen it. It was measured, and it widened._
  *
  * What the measurement found, in `claude-POC-test`: a two-card note whose cards had been
  * reviewed to intervals of 21 and 7 days, retyped onto a THREE-template note type, kept both
  * cards — the same card IDS, the same intervals, ease, reps, queue and review logs, with zero
  * drift on every column read back. And it settled on TWO cards rather than three, because this
  * tool's note types GATE their templates on field values: the Concept-Descriptor type has three
  * templates and a `cdd/2way` note wants two of them.
  *
  * THAT IS ALSO WHY THE COMPARISON IS NOT A COUNT OF CARDS. A template count is an upper bound on
  * a note's cards, never the number it has. What matters is that every ordinal the note ALREADY
  * holds exists on the new type — and since every existing ordinal is below the old template
  * count, `fromCount <= toCount` guarantees it without needing to read a single card.
  *
  * SHRINKING IS STILL REFUSED, and that refusal is the original argument intact: cards at
  * ordinals the new type cannot generate are stranded, and what Anki does with them — survives,
  * orphaned until Check Database, or destroyed — is a question this repository has not answered.
  * The comparison is deliberately conservative in one respect: a note may hold FEWER cards than
  * its type has templates, so a shrink that would in fact strand nothing is refused too. That
  * costs a rare admissible move and needs no card to be read to be safe.
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

/** Why a note may not be moved between two note types, WHATEVER THE AUTHOR SAYS.
  *
  * THIS TYPE SHRANK ON 2026-08-27, AND THE REASON IS A RULING RATHER THAN A TIDY-UP. It used to
  * hold a second case, `TemplateCountDiffers`, covering a move onto a note type with fewer
  * templates. Marc ruled that such a move is HIS DECISION: it is perfectly coherent, it simply
  * costs cards, and a tool that forbids it has substituted its judgement for its author's. That
  * case is now [[RetypeDestroysCards]], which is a PRICE rather than a refusal.
  *
  * WHAT IS LEFT HERE IS THE GENUINELY IMPOSSIBLE. A cloze note holds as many cards as its text
  * has cloze numbers, with no relation to how many templates its note type declares, so the
  * cards that would be left behind cannot even be ENUMERATED — there is no price to quote and
  * therefore no decision to offer. That is the difference the split encodes: a refusal is what
  * remains when there is nothing to put in front of the author.
  *
  * BOTH SHAPES ARE NAMED IN THE MESSAGE. "Incompatible note types" tells the reader nothing
  * they can act on; naming the two makes it obvious that a cloze section became a plain one.
  */
enum RetypeRefusal:
  case ClozeKindDiffers(from: String, fromIsCloze: Boolean, to: String, toIsCloze: Boolean)

  private def kindOf(isCloze: Boolean): String = if isCloze then "a cloze" else "a standard"

  def describe: String = this match
    case ClozeKindDiffers(from, fromIsCloze, to, toIsCloze) =>
      s"'$from' is ${kindOf(fromIsCloze)} note type and '$to' is ${kindOf(toIsCloze)} one. " +
        "A card's ordinal means a template on one and a cloze number on the other, so the " +
        "existing cards would carry ordinals the new note type does not generate"

  /** The sentence that follows the reason wherever this is reported. */
  def remedy: String =
    "this tool will not move it; do it in Anki if you want it — Browse, select the notes, " +
      "Notes > Change Note Type, which maps templates and fields explicitly"

/** A move that is COHERENT and DESTROYS CARDS — a price, not a refusal.
  *
  * NOT AN ERROR TYPE. Ruled by Marc 2026-08-27: narrowing a marker is a thing an author is
  * entitled to do knowingly, so this tool's job is to say what it costs and let him answer, not
  * to withhold it. See `docs/design/REVIEW-QUEUE.md` § *Three stances on a refusal*, which records the
  * weaker proposal — refusing only when the cards happen to hold reviews — and why it was
  * rejected: it keeps the tool forbidding and merely moves the threshold.
  *
  * ONLY NARROWING REACHES HERE. Widening cannot destroy a card by arithmetic — every ordinal the
  * note already holds is below the old template count and therefore below the new one.
  *
  * WHAT IT COSTS IS NOW KNOWN, AND THAT IS RECENT. Until 2026-08-27 the answer was genuinely
  * unestablished and the refusal said so. It was then MEASURED (`docs/findings/EVOLVABILITY.md` § M4):
  * the cards are NOT destroyed by the move. They survive it, unusable, and Anki's
  * `Tools > Check Database` destroys them at some later moment of the author's choosing — which
  * is why the sentence below states WHEN, not merely how many. A warning that said only "these
  * cards will be lost" would be discovered to be wrong by someone whose collection looked
  * untouched for a fortnight.
  */
final case class RetypeDestroysCards(from: String, fromCount: Int, to: String, toCount: Int):

  /** The ordinals the new note type cannot generate. Empty is unrepresentable in practice —
    * this type is only constructed when `fromCount > toCount` — but the range says it anyway.
    */
  def doomedOrdinals: Range = toCount until fromCount

  def describe: String =
    val n = fromCount - toCount
    s"'$from' has $fromCount card template(s) and '$to' has $toCount, so $n card(s) of this " +
      "note would have nowhere to go. They are not deleted by the move itself: they survive it " +
      "unusable, and Anki destroys them the next time Tools > Check Database is run, taking " +
      "their review history with them"

  /** WORDED AS A REFUSAL FOR NOW, DELIBERATELY. The ruling says the author decides, but the
    * command that lets him say so does not exist yet, so this slice changes the TYPES without
    * changing what a run does. When the per-note decision lands this becomes an invitation to
    * authorise the move by name, and `IN-FLIGHT.md` item 29 is where that is tracked.
    */
  def remedy: String =
    "this tool will not move it; do it in Anki if you want it — Browse, select the notes, " +
      "Notes > Change Note Type, which maps templates and fields explicitly"

/** WHAT NARROWING THIS PARTICULAR NOTE WOULD ACTUALLY COST.
  *
  * [[RetypeDestroysCards]] is about two NOTE TYPES and says how many cards *any* note would
  * lose. This is about ONE NOTE and says which cards, and what review they carry. The
  * distinction is the whole reason the ruling of 2026-08-27 is buildable: a message quoting the
  * template arithmetic tells the author nothing they can weigh, and a message saying "this
  * strands two cards holding 47 reviews" tells them everything.
  *
  * EMPTY IS MEANINGFUL AND MUST NOT BE CONFLATED WITH FREE. A note whose doomed cards have
  * never been reviewed costs nothing in history and still loses the cards — which is exactly
  * the case Marc hit on 2026-08-27, six cards at zero reviews. So `reviews == 0` is a fact
  * about history, not a licence to proceed unasked: the ruling is that HE decides, and the
  * rejected alternative was precisely a tool that decides for itself whenever the number is
  * small enough.
  */
final case class RetypePrice(doomed: Vector[CardStanding]):
  def cards: Int   = doomed.size
  def reviews: Int = doomed.map(_.reviews).sum

/** ONE CHANGE THE RUN IS WAITING ON: which note, what it costs, and the name to answer with.
  *
  * NOT A FAILURE, AND THE DISTINCTION IS THE DEFECT THIS FIXES. Until now a narrowing was
  * raised as an error, so a run that had made a correct and deliberate decision announced it
  * under `SOME ACTIONS FAILED` / `PROBLEMS`. Nothing failed. The tool declined to destroy cards
  * without being asked, which is the behaviour Marc wants — reported in the vocabulary of
  * breakage, where it is indistinguishable from a crash.
  *
  * IT CARRIES THE PRICE IT QUOTED. Whoever renders this must not recompute the cost: the number
  * shown to the author and the number the decision is answered against have to be the same one,
  * or the two can disagree across a vault edit and the author approves something other than
  * what they read.
  *
  * NAMED FOR RETYPES ONLY, DELIBERATELY. `docs/design/REVIEW-QUEUE.md` describes four kinds of pending
  * decision and this is the first to be built. A `PendingDecision` covering all four would be a
  * type designed against three cases nobody has written yet; the generalisation is cheap once a
  * second one exists and dishonest before then.
  */
final case class PendingRetype(
    handle: DecisionHandle,
    retype: SyncAction.Retype,
    loss: RetypeDestroysCards,
    price: RetypePrice,
)

/** WHAT THE TWO SHAPES SAY ABOUT A MOVE, before any policy is consulted.
  *
  * CLOSED, so that adding a third thing shapes can say is a compile error at every consumer
  * rather than a case that quietly falls through. It replaces an `Option[RetypeRefusal]`, whose
  * `None` had to carry "admissible" and whose `Some` had to carry two unlike things.
  */
enum ShapeJudgement:
  case Admissible
  case Refused(refusal: RetypeRefusal)
  case Destroys(loss: RetypeDestroysCards)

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

  /** The move is COHERENT and would DESTROY CARDS. Distinct from a refusal on purpose:
    * refusing says this tool will not do it, whereas this says the author has not yet been
    * asked. Ruled by Marc 2026-08-27 — see [[RetypeDestroysCards]].
    *
    * IT STILL BEHAVES AS A REFUSAL AT THE MOMENT, because the command that lets the author
    * answer does not exist yet. The type is what changed here, not the run.
    */
  case DestroysCards(loss: RetypeDestroysCards)

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
          judgeShapes(from, fromShape, to, toShape) match
            case ShapeJudgement.Admissible     => RetypeVerdict.WillApply
            case ShapeJudgement.Refused(r)     => RetypeVerdict.RefusedByShapes(r)
            case ShapeJudgement.Destroys(loss) => RetypeVerdict.DestroysCards(loss)
        case _ => RetypeVerdict.ShapesUnavailable(from, to)


  /** WHAT THE SHAPES ALONE SAY. Pure, so every branch is drivable without a collection.
    *
    * WAS `refusalFor`, RETURNING `Option[RetypeRefusal]`, UNTIL 2026-08-27. That signature had
    * to make `None` mean "admissible" and `Some` mean two unlike things — one move this tool
    * will never make, and one it will make once its author says so. Renamed and widened to a
    * closed type so the difference is visible to the compiler rather than to a reader.
    *
    * THE CLOZE TEST COMES FIRST, and that ordering is load-bearing. A cloze note may hold any
    * number of cards regardless of how many templates its type declares, so a count comparison
    * would pass while the ordinals ran far past anything the new type could generate.
    */
  def judgeShapes(
      from: String,
      fromShape: NoteTypeShape,
      to: String,
      toShape: NoteTypeShape,
  ): ShapeJudgement =
    if fromShape.isCloze != toShape.isCloze then
      ShapeJudgement.Refused(
        RetypeRefusal.ClozeKindDiffers(from, fromShape.isCloze, to, toShape.isCloze)
      )
    // ONLY NARROWING COSTS ANYTHING. Widening cannot destroy a card by arithmetic — every
    // ordinal the note already holds is below the old template count, and therefore below the
    // new one — and whether Anki GENERATES the extra cards was measured on 2026-08-26: it does.
    else if fromShape.templateCount > toShape.templateCount then
      ShapeJudgement.Destroys(
        RetypeDestroysCards(from, fromShape.templateCount, to, toShape.templateCount)
      )
    else ShapeJudgement.Admissible

  /** PURE — which of a note's cards this narrowing dooms.
    *
    * SEPARATE FROM THE READING OF THEM, so the selection is drivable without a collection and
    * the effectful part has no logic in it worth testing. Anki numbers a note's cards from
    * zero, so a note type with N templates generates ordinals 0..N-1 and everything at or
    * above N has nowhere to go.
    *
    * IT FILTERS RATHER THAN ASSUMING THE STANDINGS ARE COMPLETE OR ORDERED. A note does not
    * necessarily hold a card at every ordinal — a concept-descriptor note with its gate field
    * clear holds two cards on a three-template type — so "drop the last k" would be wrong, and
    * wrong in the direction that under-counts the price.
    */
  def doomedBy(standings: Vector[CardStanding], loss: RetypeDestroysCards): Vector[CardStanding] =
    standings.filter(standing => standing.ordinal >= loss.toCount)

  /** WHAT THIS NOTE WOULD LOSE — the reading, with the deciding left to [[doomedBy]].
    *
    * READ BEFORE THE MOVE, NEVER AFTER, AND THAT IS A MEASURED CONSTRAINT RATHER THAN A
    * PREFERENCE. Once the note is on the narrower type, AnkiConnect's `cardsInfo` fails for the
    * WHOLE note rather than for the doomed cards alone, so there is no reading this back to
    * check afterwards. See [[Anki.standingOf]] and `docs/findings/EVOLVABILITY.md` § M4.
    */
  def priceOf[F[_]: cats.Monad](
      anki: Anki[F],
      note: AnkiNoteId,
      loss: RetypeDestroysCards,
  ): F[RetypePrice] =
    for
      cards     <- anki.cardsOf(Vector(note))
      standings <- anki.standingOf(cards)
    yield RetypePrice(doomedBy(standings, loss))

  /** PRICE EVERY CHANGE THE RUN IS WAITING ON, and leave every other verdict alone.
    *
    * ONE READ PER AFFECTED NOTE, and none at all when nothing is waiting — which is the ordinary
    * case. A run that priced every retype would pay for a read per note to answer a question
    * only a narrowing asks.
    */
  def pendingOf[F[_]: cats.Monad](
      anki: Anki[F],
      verdicts: Vector[(SyncAction.Retype, RetypeVerdict)],
  ): F[Vector[PendingRetype]] =
    verdicts
      // A TOTAL MATCH RATHER THAN A `collect`, for the reason `cli/Report.scala` records at
      // length: a partial function opts out of the exhaustiveness this build otherwise treats as
      // an error, so a verdict added later would silently never be presented as a question —
      // which here means a change the run withholds and never mentions to anybody.
      .flatMap { (retype, verdict) =>
        verdict match
          case RetypeVerdict.DestroysCards(loss)     => Vector(retype -> loss)
          case RetypeVerdict.WillApply               => Vector.empty
          case RetypeVerdict.DeferredByPolicy        => Vector.empty
          case RetypeVerdict.RefusedByShapes(_)      => Vector.empty
          case RetypeVerdict.ShapesUnavailable(_, _) => Vector.empty
      }
      .traverse { (retype, loss) =>
        priceOf(anki, retype.noteId, loss).map(price =>
          PendingRetype(DecisionHandle.of(retype.key), retype, loss, price)
        )
      }

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
