package obsidiananki.plan

import cats.data.NonEmptyVector
import obsidiananki.anki.*
import obsidiananki.model.*

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
    */
  final class InterruptAfter(underlying: InMemoryAnki, budget: Int) extends Anki[Result]:
    private var writes = 0

    private def write[A](op: => Result[A]): Result[A] =
      if writes >= budget then
        Left(AnkiError.UnsupportedOperation("interrupted", s"fault injected after $budget writes"))
      else
        writes += 1
        op

    def noteTypeNames: Result[Vector[String]]                       = underlying.noteTypeNames
    def fieldNames(noteType: String): Result[Vector[String]]        = underlying.fieldNames(noteType)
    def findNotesByTagPrefix(prefix: String): Result[Vector[AnkiNoteId]] =
      underlying.findNotesByTagPrefix(prefix)
    def notesInfo(ids: Vector[AnkiNoteId]): Result[Vector[ObservedNote]] = underlying.notesInfo(ids)
    def cardsOf(ids: Vector[AnkiNoteId]): Result[Vector[AnkiCardId]]     = underlying.cardsOf(ids)
    def deckOf(card: AnkiCardId): Result[Option[DeckPath]]               = underlying.deckOf(card)

    def addNote(note: NewNote): Result[AnkiNoteId] = write(underlying.addNote(note))
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
      HeadingPath(
        NonEmptyVector.fromVectorUnsafe(
          segments.toVector.map(s => HeadingSegment.fromExtractedText(s).toOption.get)
        )
      ),
    )

  val defaultDeck: DeckPath = DeckPath(NonEmptyVector.of("Obsidian", "System-Design"))

  def body(s: String): Body = Body.fromExtracted(s).getOrElse(fail("empty test body"))

  def scanOf(k: CardKey, front: String, back: String): VaultScan =
    VaultScan.from(
      Vector(
        SourcedSpec(
          CardSpec.TwoField(k, front, body(back), TwoFieldDirections.Forward, "Coupling"),
          SourceRef("Note.md", 1, SourceKind.Heading),
        )
      ),
      Vector.empty,
    )

  def newNoteOf(s: SourcedSpec, d: DeckPath, sha: String): NewNote =
    NewNote(
      noteType = s.spec.noteTypeName,
      deck = d,
      fields = s.spec.fields,
      tags = NonEmptyVector.of(TagCodec.encode(s.key), OwnedTag.sha(sha)),
    )

  def planOf(scan: VaultScan, anki: Anki[Result]): Plan =
    val observed = Observer.observe(anki).fold(e => fail(s"observe failed: $e"), identity)
    Planner
      .plan(scan, observed, _ => defaultDeck, newNoteOf)
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
      Executor.run(planOf(before, anki), anki).fold(e => fail(s"setup aborted: $e"), identity)
      assertEquals(storedBack(anki), "OLD BODY.", s"[budget=$budget] setup did not take")

      // Now edit it, and interrupt the resulting Update after `budget` writes.
      val after = scanOf(k, "Temporal coupling", "NEW BODY.")
      val interrupted = planOf(after, anki)
      assertEquals(interrupted.actions.size, 1, s"[budget=$budget] expected exactly one Update")
      Executor
        .run(interrupted, InterruptAfter(anki, budget))
        .fold(e => fail(s"[budget=$budget] execution aborted entirely: $e"), identity)

      // Recovery: whatever state the interruption left, a later run must repair it. Two
      // passes, because a single pass repairing it is a stronger claim than the law makes.
      Executor.run(planOf(after, anki), anki).fold(e => fail(s"recovery aborted: $e"), identity)
      Executor.run(planOf(after, anki), anki).fold(e => fail(s"recovery aborted: $e"), identity)

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
    Executor.run(planOf(before, anki), anki).fold(e => fail(s"$e"), identity)

    val after = scanOf(k, "Temporal coupling", "NEW BODY.")
    val failures = Executor.run(planOf(after, anki), anki).fold(e => fail(s"$e"), identity)

    assert(failures.isEmpty, s"an uninterrupted update reported failures: $failures")
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
    Executor.run(planOf(scan, anki), anki).fold(e => fail(s"$e"), identity)

    val id = Observer.observe(anki).toOption.get.notes.head.note.id
    anki.addTags(Vector(id), Vector(OwnedTag.sha("deadbeef"))).fold(e => fail(s"$e"), identity)

    val plan = planOf(scan, anki)
    assert(plan.actions.nonEmpty, "a note with two content hashes was reported as up to date")

    Executor.run(plan, anki).fold(e => fail(s"$e"), identity)
    assertEquals(planOf(scan, anki).actions, Vector.empty, "the ambiguity did not heal")
  }
