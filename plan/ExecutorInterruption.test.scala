package obsidiananki.plan

import cats.data.NonEmptyVector
import obsidiananki.anki.*
import obsidiananki.model.*
import obsidiananki.plan.SectionChain.{NoRecall, NoSectionChain}
import scala.collection.mutable.ListBuffer

/** Interruption DURING a single action, which is the gap every other suite leaves open.
  *
  * `PlannerLawTest` already has a partial-application property, but it truncates the plan at
  * ACTION granularity — `actions.take(n)`. An Update is not one write: it replaces the
  * content hash and it writes the fields, and the dangerous states live strictly BETWEEN
  * those two. No property that cuts between actions can reach them.
  *
  * The law under test is the one the write ordering exists to guarantee:
  *
  *   interrupt an Update anywhere, and a later run still brings the note to the content the
  *   markdown asks for.
  *
  * WHAT THIS ASSERTS, AND WHY IT IS NOT THE OBVIOUS THING. It asserts on the FIELDS STORED
  * IN ANKI, never on the next plan being empty. The failure mode being hunted is a note the
  * planner BELIEVES is up to date — so in the broken case the next plan is empty, and an
  * emptiness assertion would pass while the note sat there holding stale content forever.
  * Asserting absence of work is precisely the mistake here; the assertion has to look at the
  * data.
  */
class ExecutorInterruptionTest extends munit.FunSuite:

  type Result[A] = Either[AnkiError, A]

  /** An interpreter that performs the first `budget` WRITES and then refuses every further
    * one, modelling a crash, a dropped connection, or Anki being quit mid-sync.
    *
    * Reads pass through untouched: they change nothing, so interrupting one cannot leave a
    * damaged state, and counting them would only make the budget hard to reason about.
    *
    * The refusal is reported as [[AnkiError.UnsupportedOperation]] because that is exactly
    * what it means here — the call did not happen. `Executor.run` collects it as an
    * `ExecutionFailure` and carries on, which is the behaviour a real partial failure gets.
    *
    * IT ALSO NOTES EACH WRITE ON A SHARED `timeline`, which is how the ledger's ordering becomes
    * observable without a second decorator. Every write in the algebra already funnels through the
    * one `write` method below — that is what makes the budget trustworthy — so the same funnel
    * serves to say WHEN a write happened relative to the append. A note is made only for a write
    * that is actually performed: a refused call did not happen, and recording it would make an
    * interrupted run look as though it had written more than it did.
    */
  final class InterruptAfter(
      underlying: InMemoryAnki,
      budget: Int,
      timeline: ListBuffer[String] = ListBuffer.empty,
  ) extends Anki[Result]:
    private var writes = 0

    private def write[A](op: => Result[A]): Result[A] =
      if writes >= budget then
        Left(AnkiError.UnsupportedOperation("interrupted", s"fault injected after $budget writes"))
      else
        writes += 1
        timeline += "anki"
        op

    def noteTypeNames: Result[Vector[String]]                       = underlying.noteTypeNames
    def fieldNames(noteType: String): Result[Vector[String]]        = underlying.fieldNames(noteType)
    def noteTypeTemplates(noteType: String): Result[Map[String, CardTemplate]] =
      underlying.noteTypeTemplates(noteType)
    def noteTypeStyling(noteType: String): Result[String] = underlying.noteTypeStyling(noteType)
    def noteTypeIsCloze(noteType: String): Result[Boolean] = underlying.noteTypeIsCloze(noteType)
    def ownedNotes: Result[Vector[AnkiNoteId]] = underlying.ownedNotes

    /** DELEGATED WITHOUT SPENDING BUDGET. This double interrupts after N WRITES, and browsing
      * opens a window rather than touching the collection — charging for it would silently
      * shift every interruption point in this file.
      */
    def browse(query: String): Result[Unit] = underlying.browse(query)
    def notesInfo(ids: Vector[AnkiNoteId]): Result[Vector[ObservedNote]] = underlying.notesInfo(ids)
    def cardsOf(ids: Vector[AnkiNoteId]): Result[Vector[AnkiCardId]]     = underlying.cardsOf(ids)
    def standingOf(cards: Vector[AnkiCardId]): Result[Vector[CardStanding]] = underlying.standingOf(cards)
    def deckOf(card: AnkiCardId): Result[Option[DeckPath]]               = underlying.deckOf(card)

    // COUNTED AS A WRITE, because it is one — it changes the SHAPE of the collection. Nothing
    // in this suite's plans reaches it (a plan holds card actions, never note type actions),
    // so it is here to satisfy the algebra and to be interrupted correctly if that ever
    // changes, rather than because a test drives it today.
    def createNoteType(spec: NoteTypeSpec): Result[Unit] = write(underlying.createNoteType(spec))

    // The three REPAIR operations, counted as writes for the same reason as the line above and
    // reached by this suite to the same extent: never. They change a collection's SHAPE, so if
    // a plan ever carries one it must be interruptible like everything else rather than slip
    // past the budget because a decorator forgot to count it.
    def addNoteTypeField(noteType: String, field: String): Result[Unit] =
      write(underlying.addNoteTypeField(noteType, field))

    def setNoteTypeTemplates(
        noteType: String,
        templates: Map[String, CardTemplate],
    ): Result[Unit] = write(underlying.setNoteTypeTemplates(noteType, templates))

    def setNoteTypeStyling(noteType: String, css: String): Result[Unit] =
      write(underlying.setNoteTypeStyling(noteType, css))

    // COUNTED AS WRITES, and unlike the note-type operations these ARE reached by this suite:
    // a Flag now tags AND suspends, so an interruption can land between the two. That is the
    // partial state the ordering in `Executor` is chosen to make recoverable.
    def suspend(cards: Vector[AnkiCardId]): Result[Unit]   = write(underlying.suspend(cards))
    def unsuspend(cards: Vector[AnkiCardId]): Result[Unit] = write(underlying.unsuspend(cards))

    def addNote(note: NewNote): Result[AnkiNoteId] = write(underlying.addNote(note))

    /** COUNTED AS A WRITE, and it is the one write in the algebra that is ATOMIC: fields, note
      * type and the whole tag set land in a single call, so there is no partial state to
      * interrupt INTO. Interrupting it means it did not happen at all.
      */
    def changeNoteType(
        id: AnkiNoteId,
        to: String,
        fields: Vector[(String, String)],
        ownedTags: NonEmptyVector[OwnedTag],
        preservedTags: Vector[String],
    ): Result[Unit] =
      write(underlying.changeNoteType(id, to, fields, ownedTags, preservedTags))

    def updateNoteFields(id: AnkiNoteId, fields: Vector[(String, String)]): Result[Unit] =
      write(underlying.updateNoteFields(id, fields))
    def addTags(ids: Vector[AnkiNoteId], tags: Vector[OwnedTag]): Result[Unit] =
      write(underlying.addTags(ids, tags))
    def removeTags(ids: Vector[AnkiNoteId], tags: Vector[OwnedTag]): Result[Unit] =
      write(underlying.removeTags(ids, tags))
    def changeDeck(cards: Vector[AnkiCardId], deck: DeckPath): Result[Unit] =
      write(underlying.changeDeck(cards, deck))

  // ---------------------------------------------------------------- fixtures ----

  def key(id: String, segments: String*): CardKey =
    CardKey(
      NoteId.fromFrontmatter(id).toOption.get,
      CardPath.Headings(HeadingPath(
        NonEmptyVector.fromVectorUnsafe(
          segments.toVector.map(s => HeadingSegment.fromExtractedText(s).toOption.get)
        )
      )),
    )

  val defaultDeck: DeckPath = DeckPath(NonEmptyVector.of("Obsidian", "System-Design"))

  def body(s: String): Body = Body.fromExtracted(s).getOrElse(fail("empty test body"))

  def scanOf(k: CardKey, front: String, back: String): VaultScan =
    VaultScan.from(
      Vector(
        SourcedSpec(
          CardSpec.TwoField(k, front, body(back), TwoFieldDirections.Forward, Bearings.breadcrumbOnly("Coupling")),
          SourceRef("Note.md", 1, SourceKind.Heading),
          NoSectionChain,
          NoRecall,
         Vector.empty,
        )
      ),
      Vector.empty,
    )

  def newNoteOf(s: SourcedSpec, d: DeckPath, sha: String): NewNote =
    NewNote(
      noteType = s.spec.noteTypeName,
      deck = d,
      fields = s.spec.fields,
      // MIRRORS PRODUCTION, which stopped writing the identity tag on 2026-08-29: a note this
      // tool creates carries its identity in a field. A helper still writing the tag would make
      // every fixture a note that needs migrating, and the convergence law would never hold.
      tags = NonEmptyVector.one(OwnedTag.sha(sha)),
    )

  def planOf(scan: VaultScan, anki: Anki[Result]): Plan =
    val observed = Observer.observe(anki).fold(e => fail(s"observe failed: $e"), identity)
    Planner
      .plan(scan, observed, _ => defaultDeck, newNoteOf, HandBuiltCensus.of(scan))
      .fold(errs => fail(s"plan errors: ${errs.map(_.describe)}"), identity)

  def storedBack(anki: InMemoryAnki): String =
    Observer
      .observe(anki)
      .fold(e => fail(s"observe failed: $e"), identity)
      .notes
      .headOption
      .getOrElse(fail("the note vanished entirely"))
      .note
      .fields
      .toMap
      .getOrElse("Back", fail("no Back field"))

  // ================================================================ the law ====

  val k: CardKey = key("n1", "Coupling", "Temporal coupling")

  test("SAFETY: an Update interrupted at ANY point still converges on a later run") {
    // Wide enough to cover every write an Update makes, plus the case where nothing at all
    // got through and the case where everything did.
    for budget <- 0 to 5 do
      val anki = InMemoryAnki()

      // Establish the note with its original content, uninterrupted.
      val before = scanOf(k, "Temporal coupling", "OLD BODY.")
      Executor.run(planOf(before, anki), anki, RetypePolicy.Defer, Set.empty, RecordedNowhere.ledger).fold(e => fail(s"setup aborted: $e"), identity)
      assertEquals(storedBack(anki), "OLD BODY.", s"[budget=$budget] setup did not take")

      // Now edit it, and interrupt the resulting Update after `budget` writes.
      val after = scanOf(k, "Temporal coupling", "NEW BODY.")
      val interrupted = planOf(after, anki)
      assertEquals(interrupted.actions.size, 1, s"[budget=$budget] expected exactly one Update")
      Executor
        .run(interrupted, InterruptAfter(anki, budget), RetypePolicy.Defer, Set.empty, RecordedNowhere.ledger)
        .fold(e => fail(s"[budget=$budget] execution aborted entirely: $e"), identity)

      // Recovery: whatever state the interruption left, a later run must repair it. Two
      // passes, because a single pass repairing it is a stronger claim than the law makes.
      Executor.run(planOf(after, anki), anki, RetypePolicy.Defer, Set.empty, RecordedNowhere.ledger).fold(e => fail(s"recovery aborted: $e"), identity)
      Executor.run(planOf(after, anki), anki, RetypePolicy.Defer, Set.empty, RecordedNowhere.ledger).fold(e => fail(s"recovery aborted: $e"), identity)

      assertEquals(
        storedBack(anki),
        "NEW BODY.",
        s"[budget=$budget] the note was left holding stale content that no later run repairs",
      )
  }

  /** The control. If the harness reported a failure for every budget, the property above
    * would be proving only that fault injection works.
    */
  test("CONTROL: with no interruption the update simply applies") {
    val anki = InMemoryAnki()
    val before = scanOf(k, "Temporal coupling", "OLD BODY.")
    Executor.run(planOf(before, anki), anki, RetypePolicy.Defer, Set.empty, RecordedNowhere.ledger).fold(e => fail(s"$e"), identity)

    val after = scanOf(k, "Temporal coupling", "NEW BODY.")
    val report = Executor.run(planOf(after, anki), anki, RetypePolicy.Defer, Set.empty, RecordedNowhere.ledger).fold(e => fail(s"$e"), identity)

    assert(report.failures.isEmpty, s"an uninterrupted update reported failures: ${report.failures}")
    assertEquals(storedBack(anki), "NEW BODY.")
  }

  /** Two content hashes on one note make "has this changed?" unanswerable, and the honest
    * answer is "assume it changed" — which re-syncs and normalises. The state is reachable
    * whenever a tag write lands but its partner does not, so it must CONVERGE rather than be
    * prevented.
    */
  test("a note carrying TWO content hashes is treated as changed, and heals") {
    val anki = InMemoryAnki()
    val scan = scanOf(k, "Temporal coupling", "BODY.")
    Executor.run(planOf(scan, anki), anki, RetypePolicy.Defer, Set.empty, RecordedNowhere.ledger).fold(e => fail(s"$e"), identity)

    val id = Observer.observe(anki).toOption.get.notes.head.note.id
    anki.addTags(Vector(id), Vector(OwnedTag.sha("deadbeef"))).fold(e => fail(s"$e"), identity)

    val plan = planOf(scan, anki)
    assert(plan.actions.nonEmpty, "a note with two content hashes was reported as up to date")

    Executor.run(plan, anki, RetypePolicy.Defer, Set.empty, RecordedNowhere.ledger).fold(e => fail(s"$e"), identity)
    assertEquals(planOf(scan, anki).actions, Vector.empty, "the ambiguity did not heal")
  }

  // ══════════════════════════ THE SAME LAW, ONE LAYER OUT: THE RUN LEDGER ════
  //
  // This suite exists for the rule that an interruption must leave work to be REDONE rather than
  // BELIEVED DONE, and the ledger's ordering is that rule applied to the audit trail. Recorded
  // before the first write, an interruption can leave a line whose reassignment never happened —
  // visible, and undone by the next run finding the note still stranded. Written after, an
  // interruption can move somebody's review history with nothing anywhere saying it happened, which
  // Marc's ruling of 2026-09-12 forbids outright. The two tests below are those two halves.

  /** The same card, one parent over: `## Temporal coupling` moved from under `# Coupling` to under
    * `# Architecture`. Its own name, its body and its breadcrumb are untouched, so the pairing
    * corroborates and the run plans a reassignment — which is the only action that earns a line.
    */
  val movedK: CardKey = key("n1", "Architecture", "Temporal coupling")

  test("SAFETY: every ledger line is recorded BEFORE the first write reaches Anki") {
    val anki = InMemoryAnki()
    Executor
      .run(planOf(scanOf(k, "Temporal coupling", "BODY."), anki), anki, RetypePolicy.Defer, Set.empty, RecordedNowhere.ledger)
      .fold(e => fail(s"setup aborted: $e"), identity)

    val plan = planOf(scanOf(movedK, "Temporal coupling", "BODY."), anki)
    assert(
      plan.actions.exists { case _: SyncAction.Reassign => true; case _ => false },
      s"the fixture was supposed to plan a reassignment: ${plan.actions}",
    )

    // ONE TIMELINE, SHARED, rather than two counters compared afterwards: the order is the claim, so
    // the observation has to be of a single sequence.
    val timeline = ListBuffer.empty[String]
    val watched  = InterruptAfter(anki, budget = Int.MaxValue, timeline = timeline)
    Executor
      .run(plan, watched, RetypePolicy.Defer, Set.empty, RecordingLedger[Result](Right(()), timeline))
      .fold(e => fail(s"execution aborted: $e"), identity)

    assertEquals(timeline.headOption, Some("ledger"), s"the trail was not written first: $timeline")
    // THE NON-VACUITY HALF, AND IT IS NOT OPTIONAL. Without it this passes on a run that wrote
    // nothing at all, which is the shape of green that proves nothing — the assertion above would
    // hold over a timeline of exactly one element.
    assert(timeline.contains("anki"), s"nothing was ever written, so the order proves nothing: $timeline")
  }

  test("a ledger that cannot be appended to aborts the run, and NOTHING is written") {
    val anki = InMemoryAnki()
    Executor
      .run(planOf(scanOf(k, "Temporal coupling", "BODY."), anki), anki, RetypePolicy.Defer, Set.empty, RecordedNowhere.ledger)
      .fold(e => fail(s"setup aborted: $e"), identity)

    val before = Observer.observe(anki).fold(e => fail(s"$e"), identity)
    val plan   = planOf(scanOf(movedK, "Temporal coupling", "BODY."), anki)

    val outcome = Executor.run(
      plan,
      anki,
      RetypePolicy.Defer,
      Set.empty,
      // The refusal travels as an `AnkiError` only because that is the one channel this `F` has; in
      // production it is an `IO` failure carrying `cli.LedgerUnwritable`. What is asserted below is
      // the state of the collection, never which error came back.
      RefusingLedger[Result](Left(AnkiError.UnsupportedOperation("record", "fault injected"))),
    )

    assert(outcome.isLeft, s"an unrecordable run reported success: $outcome")

    // ASSERTED ON THE DATA, following this file's own header: the failure being hunted is a note
    // that was changed anyway, and only the stored state can say. The note must still claim the key
    // it had, which is precisely what the reassignment would have rewritten.
    val after = Observer.observe(anki).fold(e => fail(s"$e"), identity)
    assertEquals(after.notes.map(_.key), Vector(k), "the reassignment happened despite the refusal")
    assertEquals(after.notes.map(_.note.fields), before.notes.map(_.note.fields))
    assertEquals(after.notes.map(_.note.tags), before.notes.map(_.note.tags))
  }
