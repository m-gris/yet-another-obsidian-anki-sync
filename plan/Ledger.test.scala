package obsidiananki.plan

import cats.data.NonEmptyVector
import obsidiananki.anki.*
import obsidiananki.model.*
import obsidiananki.plan.SectionChain.{NoRecall, NoSectionChain}
import scala.collection.mutable.ListBuffer

// ════════════════════════════════════════════════ THE FAKES THAT OBSERVE ════
//
// FAKES RATHER THAN MOCKS: nothing here asks whether a method was called. One keeps what it was
// handed so a test can assert on the VALUE, the other refuses so a test can assert on the state of
// the collection afterwards. [[RecordedNowhere]] next door is the third, for suites that are not
// asking about the trail at all.

/** A ledger that keeps what it was handed, in memory.
  *
  * `appended` IS A LIST OF BATCHES rather than a flat list of moves, because part of the contract
  * is the batching: a run records its moves in ONE call, before its first write. Flattening here
  * would make "one call per run" unobservable, and an implementation that appended per move would
  * pass every assertion.
  *
  * `timeline` IS SHARED WITH THE ANKI DOUBLE THAT WATCHES WRITES, which is how the ordering becomes
  * a property of one sequence rather than two counters that have to be compared.
  */
final class RecordingLedger[F[_]](result: F[Unit], timeline: ListBuffer[String] = ListBuffer.empty)
    extends Ledger[F]:
  val appended: ListBuffer[Vector[HistoryMove]] = ListBuffer.empty

  def record(moves: Vector[HistoryMove]): F[Unit] =
    appended += moves
    timeline += "ledger"
    result

/** A ledger that cannot write, modelling a full disk, a read-only home, or an unwritable path.
  *
  * IT REFUSES WITH AN `AnkiError` ONLY BECAUSE THAT IS THE ONLY CHANNEL `Either[AnkiError, *]` HAS.
  * A real append failure is NOT an Anki error and is deliberately not modelled as one — see
  * `anki/Anki.scala`, which makes that argument for transport failures, and `cli/Main.scala`, where
  * the appender's failure is an `IO` failure travelling past this channel entirely. What the tests
  * using this assert is the OUTCOME: that nothing reached the collection. None of them asserts
  * which error came back, because that would be asserting a property of this fake.
  */
final class RefusingLedger[F[_]](refusal: F[Unit]) extends Ledger[F]:
  def record(moves: Vector[HistoryMove]): F[Unit] = refusal

/** WHAT THE RUN WROTE DOWN ABOUT MOVING REVIEW HISTORY, AND WHEN IT WROTE NOTHING.
  *
  * ==What this suite is FOR==
  *
  * Marc's ruling of 2026-09-12 (Decision 1) made the audit trail a standing property rather than an
  * option: every automatic history-carrying action is recorded, always. So the claims that matter
  * are equal and opposite, and both are pinned here — a followed rename IS recorded, and an ordinary
  * content edit is NOT. A ledger that recorded everything would be as useless as one that recorded
  * nothing: the whole value of a line is that its presence MEANS review history moved.
  *
  * ==Why the derivation is tested as data==
  *
  * `HistoryMove.inActions` is pure, so every claim about what gets recorded is an assertion about a
  * returned value rather than about a file. The file, the clock and the run id belong to the edge and
  * are pinned in `cli/Ledger.test.scala`.
  */
class LedgerTest extends munit.FunSuite:

  type Result[A] = Either[AnkiError, A]

  // ------------------------------------------------------------------ fixtures ----

  def key(id: String, segments: String*): CardKey =
    CardKey(
      NoteId.fromFrontmatter(id).toOption.get,
      CardPath.Headings(
        HeadingPath(
          NonEmptyVector.fromVectorUnsafe(
            segments.toVector.map(s => HeadingSegment.fromExtractedText(s).toOption.get)
          )
        )
      ),
    )

  def body(s: String): Body = Body.fromExtracted(s).getOrElse(fail("empty test body"))

  val deck: DeckPath   = DeckPath(NonEmptyVector.of("Obsidian", "System-Design"))
  val where: SourceRef = SourceRef("System Design.md", 12, SourceKind.Heading)

  def sourced(spec: CardSpec): SourcedSpec =
    SourcedSpec(spec, where, NoSectionChain, NoRecall, Vector.empty)

  def twoField(k: CardKey, front: String, back: String, context: String): CardSpec =
    CardSpec.TwoField(k, front, body(back), TwoFieldDirections.Forward, context)

  def scanOf(specs: SourcedSpec*): VaultScan = VaultScan.from(specs.toVector, Vector.empty)

  def planOf(scan: VaultScan, anki: InMemoryAnki): Plan =
    val state = Observer.observe(anki).fold(e => fail(s"observe failed: $e"), identity)
    Planner
      .plan(scan, state, _ => deck, Planner.newNoteFor, HandBuiltCensus.of(scan))
      .fold(errs => fail(s"unexpected plan errors: ${errs.map(_.describe)}"), identity)

  def runPlan(p: Plan, anki: InMemoryAnki): Unit =
    val report = Executor
      .run(p, anki, RetypePolicy.Apply, Set.empty, RecordedNowhere.ledger)
      .fold(e => fail(s"execution aborted entirely: $e"), identity)
    assert(report.failures.isEmpty, s"unexpected execution failures: ${report.failures}")

  /** Sync a vault holding `spec` into an empty collection, so that a later plan has something to
    * strand. Built by SYNCING rather than by hand, because the whole mechanism reads what the last
    * successful sync wrote, and a hand-built note is a guess at that.
    */
  def collectionHolding(spec: CardSpec): InMemoryAnki =
    val anki = InMemoryAnki()
    runPlan(planOf(scanOf(sourced(spec)), anki), anki)
    anki

  // ── THE MOVE: `## Essential numbers` became a subtree of a new parent ───────────────
  //
  // The heading's own text and its body are untouched; its place, and so its breadcrumb, are not.
  // That is the shape the mechanism exists for, and it grades NameAndSubstance — the same name,
  // somewhere else.

  val strandedKey: CardKey = key("system-design", "essential numbers", "scale")
  val movedKey: CardKey =
    key("system-design", "system design interview framework", "essential numbers", "scale")

  val beforeMove: CardSpec = twoField(strandedKey, "Scale", "10^9 users", "System design")
  val afterMove: CardSpec =
    twoField(movedKey, "Scale", "10^9 users", "System design › System design interview framework")

  /** The same card, edited in place: same key, different body. Nothing moves. */
  val editedInPlace: CardSpec = twoField(strandedKey, "Scale", "10^10 users", "System design")

  // ------------------------------------------- the fixtures are not vacuous ----

  test("VACUITY GUARD — the move changes the key, and the edit changes the body but not the key") {
    assertNotEquals(beforeMove.key, afterMove.key)
    assertEquals(editedInPlace.key, beforeMove.key)
    // `.toMap.apply(…)` RATHER THAN `.toMap(…)`: `toMap`'s only parameter list is its implicit
    // witness, so `toMap("Back")` supplies THAT rather than looking a key up, and the error it
    // produces names neither.
    assertNotEquals(
      editedInPlace.fields.toMap.apply("Back"),
      beforeMove.fields.toMap.apply("Back"),
    )
  }

  // ================================================ WHAT EARNS A LEDGER LINE ====

  test("a followed rename is recorded — one move, both keys, the grade and the note type") {
    val anki = collectionHolding(beforeMove)
    val plan = planOf(scanOf(sourced(afterMove)), anki)

    HistoryMove.inActions(plan.actions) match
      case Vector(moved) =>
        assertEquals(moved.from, strandedKey)
        assertEquals(moved.to, movedKey)
        assertEquals(moved.grade, Agreement.NameAndSubstance)
        assertEquals(moved.noteType, beforeMove.noteTypeName)
        assertEquals(moved.unflagged, false, "nothing was parked, so nothing was released")
      case other =>
        fail(s"a reassignment should produce exactly one ledger move, got ${other.size}: $other")
  }

  test("the move names the Anki note whose history moved, not merely the keys") {
    // Without this the trail says a path moved and leaves the reader to work out which note that
    // was — which is the one lookup they cannot do, because the note no longer claims the old key.
    val anki  = collectionHolding(beforeMove)
    val moved = HistoryMove.inActions(planOf(scanOf(sourced(afterMove)), anki).actions).head

    assertEquals(
      Vector(moved.ankiNote),
      anki.ownedNotes.fold(e => fail(s"$e"), identity),
    )
  }

  test("EVERY DIVERGENCE the evidence held travels, so the pairing is checkable from the line") {
    // The 2026-09-04 ruling: a run acting on evidence must say what the evidence was. Here the
    // breadcrumb is what differed, and the record has to name it along with both of its values.
    val anki  = collectionHolding(beforeMove)
    val moved = HistoryMove.inActions(planOf(scanOf(sourced(afterMove)), anki).actions).head

    assertEquals(
      moved.divergences.map(d => (d.field, d.inAnki, d.inVault)),
      Vector(
        (
          Marker.ContextField,
          "System design",
          "System design › System design interview framework",
        )
      ),
    )
  }

  // ============================================ WHAT MUST NOT EARN A LINE ====

  test("a mere content update records NOTHING — a line means review history MOVED") {
    val anki = collectionHolding(beforeMove)
    val plan = planOf(scanOf(sourced(editedInPlace)), anki)

    assert(
      plan.actions.exists { case _: SyncAction.Update => true; case _ => false },
      s"the fixture did not produce the update it is about: ${plan.actions}",
    )
    assertEquals(HistoryMove.inActions(plan.actions), Vector.empty)
  }

  test("a run with nothing to do records nothing") {
    val anki = collectionHolding(beforeMove)
    val plan = planOf(scanOf(sourced(beforeMove)), anki)

    assertEquals(plan.actions, Vector.empty, "the fixture was supposed to have converged")
    assertEquals(HistoryMove.inActions(plan.actions), Vector.empty)
  }

  test("a card that vanished records nothing — being parked is not history moving") {
    val anki = collectionHolding(beforeMove)
    val plan = planOf(scanOf(), anki)

    assert(
      plan.actions.exists { case _: SyncAction.Flag => true; case _ => false },
      s"the fixture did not park anything: ${plan.actions}",
    )
    assertEquals(HistoryMove.inActions(plan.actions), Vector.empty)
  }

  test("a card created from scratch records nothing — a new card starts at zero") {
    val anki = InMemoryAnki()
    val plan = planOf(scanOf(sourced(beforeMove)), anki)

    assert(
      plan.actions.exists { case _: SyncAction.Create => true; case _ => false },
      s"the fixture did not create anything: ${plan.actions}",
    )
    assertEquals(HistoryMove.inActions(plan.actions), Vector.empty)
  }

  // ==================================== `unflagged`: ONE EVENT, NOT TWO ====

  test("a note released from the orphan pen as part of its move is recorded as unflagged") {
    val anki = collectionHolding(beforeMove)
    // An earlier run over a vault that had lost the heading entirely: flag and suspend.
    runPlan(planOf(scanOf(), anki), anki)

    val plan = planOf(scanOf(sourced(afterMove)), anki)
    assert(
      plan.actions.exists { case _: SyncAction.Unflag => true; case _ => false },
      s"the fixture did not produce the release it is about: ${plan.actions}",
    )

    assertEquals(HistoryMove.inActions(plan.actions).map(_.unflagged), Vector(true))
  }

  test("an Unflag naming a DIFFERENT key on the same note is not read as part of the move") {
    // THE DOUBLE MATCH, PINNED. `Planner` emits `Unflag` from three branches: once in the
    // reassignment branch, naming the OLD key, immediately before the `Reassign`; and twice for a
    // note that is present AT its key and merely needs its orphan tag cleared. Matching on the note
    // id alone would report a note released for an unrelated reason as having been released as part
    // of its move — a claim the ledger would be making about somebody's collection on no evidence.
    // Only the pairing of note AND old key makes it the same event.
    val anki = collectionHolding(beforeMove)
    val reassign = planOf(scanOf(sourced(afterMove)), anki).actions
      .collectFirst { case r: SyncAction.Reassign => r }
      .getOrElse(fail("the fixture did not produce a reassignment"))

    val elsewhere = key("system-design", "something else entirely")
    val actions: Vector[SyncAction] =
      Vector(SyncAction.Unflag(elsewhere, reassign.corroboration.noteId), reassign)

    assertEquals(
      HistoryMove.inActions(actions).map(_.unflagged),
      Vector(false),
      "a release naming another key was counted as part of the move",
    )
  }

  // ========================== ONE CALL PER RUN, WHATEVER THERE IS TO SAY ====

  test("a plan holding no reassignment still calls the ledger, with an empty batch") {
    // ONE CALL PER RUN, UNCONDITIONALLY, so that "did this run record what it moved?" is answerable
    // without the executor having first decided the question was moot. Whether an empty batch
    // touches the file is the appender's decision and is made in `cli/Main.scala`; the executor does
    // not get to skip the call, because "skip the ledger when …" is the shape of thing that grows a
    // second condition.
    val anki   = collectionHolding(beforeMove)
    val ledger = RecordingLedger[Result](Right(()))

    Executor
      .run(planOf(scanOf(sourced(editedInPlace)), anki), anki, RetypePolicy.Apply, Set.empty, ledger)
      .fold(e => fail(s"execution aborted: $e"), identity)

    assertEquals(ledger.appended.toVector, Vector(Vector.empty))
  }

  test("a run that reassigns hands the ledger exactly what the plan said would move") {
    val anki = collectionHolding(beforeMove)
    val plan = planOf(scanOf(sourced(afterMove)), anki)
    val ledger = RecordingLedger[Result](Right(()))

    Executor
      .run(plan, anki, RetypePolicy.Apply, Set.empty, ledger)
      .fold(e => fail(s"execution aborted: $e"), identity)

    assertEquals(ledger.appended.toVector, Vector(HistoryMove.inActions(plan.actions)))
    assertEquals(ledger.appended.toVector.flatten.size, 1)
  }
