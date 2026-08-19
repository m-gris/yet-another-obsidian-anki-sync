package obsidiananki.plan

import cats.data.NonEmptyVector
import obsidiananki.anki.*
import obsidiananki.model.*

/** The planner is where "run it twice and the second run changes nothing" is decided, so
  * that law is the centrepiece here. It is provable entirely against the in-memory
  * interpreter, with no markdown and no live collection involved.
  */
class PlannerTest extends munit.FunSuite:

  type Result[A] = Either[AnkiError, A]

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

  def deck(segments: String*): DeckPath =
    DeckPath(NonEmptyVector.fromVectorUnsafe(segments.toVector))

  val defaultDeck: DeckPath = deck("Obsidian", "System-Design")

  def body(s: String): Body = Body.fromExtracted(s).getOrElse(fail("empty test body"))

  def twoFieldSpec(k: CardKey, front: String, back: String): CardSpec =
    CardSpec.TwoField(k, front, body(back), TwoFieldDirections.Forward)

  def sourced(spec: CardSpec, file: String = "Note.md", line: Int = 1): SourcedSpec =
    SourcedSpec(spec, SourceRef(file, line, SourceKind.Heading))

  /** Builds the NewNote for a Create. Carries BOTH owned tags, because the identity tag and
    * the content hash must exist from the moment the note does.
    */
  def newNoteOf(s: SourcedSpec, d: DeckPath, sha: String): NewNote =
    NewNote(
      noteType = s.spec.noteTypeName,
      deck = d,
      fields = s.spec.fields,
      tags = NonEmptyVector.of(TagCodec.encode(s.key), OwnedTag.sha(sha)),
    )

  def planOf(scan: VaultScan, observed: ObservedState): Plan =
    Planner
      .plan(scan, observed, _ => defaultDeck, newNoteOf)
      .fold(errs => fail(s"unexpected plan errors: ${errs.map(_.describe)}"), identity)

  def observe(anki: InMemoryAnki): ObservedState =
    Observer.observe(anki).fold(e => fail(s"observe failed: $e"), identity)

  /** Runs a plan and asserts nothing failed. Use [[runPlanCollecting]] when failures are
    * the point of the test.
    */
  def runPlan(p: Plan, anki: InMemoryAnki): Unit =
    val failures = runPlanCollecting(p, anki)
    assert(failures.isEmpty, s"unexpected execution failures: $failures")

  def runPlanCollecting(p: Plan, anki: InMemoryAnki): Vector[ExecutionFailure] =
    Executor.run(p, anki).fold(e => fail(s"execution aborted entirely: $e"), identity)

  def scanOf(specs: SourcedSpec*): VaultScan = VaultScan.from(specs.toVector, Vector.empty)

  // ================================================================ THE LAW ====

  /** The acceptance criterion, as a law rather than a scenario.
    *
    * If this holds, "re-run unchanged makes ZERO changes" is not a thing to test case by
    * case — it is a property of the planner.
    */
  test("LAW: applying a plan makes the next plan empty") {
    val anki = InMemoryAnki()
    val scan = scanOf(
      sourced(twoFieldSpec(key("n1", "Coupling", "Temporal coupling"), "Temporal coupling", "All up at once.")),
      sourced(twoFieldSpec(key("n1", "Coupling", "Afferent coupling"), "Afferent coupling", "Who depends on me.")),
      sourced(
        CardSpec.ThreeField(
          key("n2", "Linearizability", "Definition"),
          "Linearizability",
          "Definition",
          body("Operations appear instantaneous."),
          ThreeFieldDirections.All,
        )
      ),
    )

    val first = planOf(scan, observe(anki))
    assertEquals(first.actions.size, 3, "first run should create all three")
    runPlan(first, anki)

    val second = planOf(scan, observe(anki))
    assertEquals(second.actions, Vector.empty, s"second run was not empty: ${second.actions}")
  }

  test("LAW: the law still holds after a content edit and re-sync") {
    val anki = InMemoryAnki()
    val k    = key("n1", "Coupling", "Temporal coupling")

    runPlan(planOf(scanOf(sourced(twoFieldSpec(k, "Temporal coupling", "Old text."))), observe(anki)), anki)

    val edited = scanOf(sourced(twoFieldSpec(k, "Temporal coupling", "New text.")))
    val update = planOf(edited, observe(anki))
    assertEquals(update.actions.size, 1, s"expected one update, got ${update.actions}")
    runPlan(update, anki)

    assertEquals(planOf(edited, observe(anki)).actions, Vector.empty)
  }

  test("re-running does not touch the note at all — asserted on Anki, not on the plan") {
    val anki = InMemoryAnki()
    val scan = scanOf(sourced(twoFieldSpec(key("n1", "A", "B"), "front", "back")))
    runPlan(planOf(scan, observe(anki)), anki)

    val id     = observe(anki).notes.head.note.id
    val before = anki.modCountOf(id)
    runPlan(planOf(scan, observe(anki)), anki)
    assertEquals(anki.modCountOf(id), before, "a no-op run still wrote to the note")
  }

  // ================================================================ actions ====

  test("an unchanged card produces no action; an edited one produces exactly one Update") {
    val anki = InMemoryAnki()
    val k    = key("n1", "A", "B")
    runPlan(planOf(scanOf(sourced(twoFieldSpec(k, "f", "old"))), observe(anki)), anki)

    val plan = planOf(scanOf(sourced(twoFieldSpec(k, "f", "new"))), observe(anki))
    plan.actions match
      case Vector(SyncAction.Update(_, _, changes)) =>
        assert(changes.toVector.exists(_.isInstanceOf[Change.FieldsChanged]))
      case other => fail(s"expected a single Update, got $other")
  }

  test("moving a file produces a deck change, and edited-AND-moved is ONE Update") {
    val anki = InMemoryAnki()
    val k    = key("n1", "A", "B")
    runPlan(planOf(scanOf(sourced(twoFieldSpec(k, "f", "old"))), observe(anki)), anki)

    val moved = deck("Obsidian", "Patterns")
    val plan = Planner
      .plan(scanOf(sourced(twoFieldSpec(k, "f", "new"))), observe(anki), _ => moved, newNoteOf)
      .fold(e => fail(s"$e"), identity)

    plan.actions match
      case Vector(SyncAction.Update(_, _, changes)) =>
        val kinds = changes.toVector
        assert(kinds.exists(_.isInstanceOf[Change.FieldsChanged]), s"no field change: $kinds")
        assert(kinds.exists(_.isInstanceOf[Change.DeckChanged]), s"no deck change: $kinds")
      case other => fail(s"edited AND moved should be ONE Update, got $other")
  }

  test("a marker change becomes a Retype, never a silent field update") {
    val anki = InMemoryAnki()
    val k    = key("n1", "A", "Term")
    // 1way first...
    runPlan(planOf(scanOf(sourced(twoFieldSpec(k, "Term", "def"))), observe(anki)), anki)

    // ...then retagged as 2way, which shares field names with Basic. An ordinary update
    // would SUCCEED here and the reverse card would never exist.
    val reversed = CardSpec.TwoField(k, "Term", body("def"), TwoFieldDirections.Both)
    planOf(scanOf(sourced(reversed)), observe(anki)).actions match
      case Vector(SyncAction.Retype(_, _, from, to)) =>
        assertEquals(from, Marker.NoteTypes.Basic)
        assertEquals(to, Marker.NoteTypes.BasicAndReversed)
      case other => fail(s"expected a Retype, got $other")
  }

  // ================================================================ orphans ====

  test("a card removed from the markdown is FLAGGED, never deleted") {
    val anki = InMemoryAnki()
    val k    = key("n1", "A", "Gone")
    runPlan(planOf(scanOf(sourced(twoFieldSpec(k, "f", "b"))), observe(anki)), anki)

    planOf(scanOf(), observe(anki)).actions match
      case Vector(SyncAction.Flag(flagged, _)) => assertEquals(flagged, k)
      case other                               => fail(s"expected a Flag, got $other")
  }

  test("a flagged card that reappears is unflagged, so the prune list stays trustworthy") {
    val anki = InMemoryAnki()
    val k    = key("n1", "A", "Gone")
    val spec = sourced(twoFieldSpec(k, "f", "b"))
    runPlan(planOf(scanOf(spec), observe(anki)), anki)
    runPlan(planOf(scanOf(), observe(anki)), anki) // flag it

    val actions = planOf(scanOf(spec), observe(anki)).actions
    assert(
      actions.exists(_.isInstanceOf[SyncAction.Unflag]),
      s"a reappeared card was not unflagged: $actions",
    )
  }

  test("an already-flagged orphan is not flagged again") {
    val anki = InMemoryAnki()
    runPlan(planOf(scanOf(sourced(twoFieldSpec(key("n1", "A", "X"), "f", "b"))), observe(anki)), anki)
    runPlan(planOf(scanOf(), observe(anki)), anki)
    assertEquals(planOf(scanOf(), observe(anki)).actions, Vector.empty)
  }

  // ------------------------------------------- the three levels of suppression ----

  /** Class (a): the key was derivable, only the card failed to build. BROKEN MUST NOT BE
    * READ AS DELETED — the card is excluded from orphan inference individually.
    */
  test("a card that failed to build does NOT get its note flagged as orphaned") {
    val anki = InMemoryAnki()
    val k    = key("n1", "A", "Empty body")
    runPlan(planOf(scanOf(sourced(twoFieldSpec(k, "f", "b"))), observe(anki)), anki)

    val scan = VaultScan.from(
      Vector.empty,
      Vector(BuildFailure.KeyKnown(k, SourceRef("Note.md", 3, SourceKind.Heading), "empty body")),
    )
    assertEquals(
      planOf(scan, observe(anki)).actions,
      Vector.empty,
      "a card that merely failed to build was sent to the prune list",
    )
  }

  /** Class (b), blast radius determinable: no key, but the file's note id is known, so every
    * observed key belonging to that note is suppressed.
    */
  test("an underivable key suppresses orphan inference for that whole note") {
    val anki  = InMemoryAnki()
    val k1    = key("n1", "A", "One")
    val k2    = key("n1", "A", "Two")
    val other = key("n2", "B", "Untouched")

    // Seed all three in ONE scan. Syncing them one at a time would flag each earlier card
    // as an orphan along the way, and the assertion below would then pass merely because
    // already-flagged orphans are skipped — proving nothing about suppression.
    runPlan(
      planOf(
        VaultScan.from(Vector(k1, k2, other).map(k => sourced(twoFieldSpec(k, "f", "b"))), Vector.empty),
        observe(anki),
      ),
      anki,
    )

    val scan = VaultScan.from(
      Vector(sourced(twoFieldSpec(other, "f", "b"))),
      Vector(
        BuildFailure.KeyUnderivableInFile(
          NoteId.fromFrontmatter("n1").toOption.get,
          SourceRef("Note.md", 3, SourceKind.Heading),
          "heading extracted to nothing",
        )
      ),
    )
    assertEquals(
      planOf(scan, observe(anki)).actions,
      Vector.empty,
      "keys of a note with an underivable heading were flagged as orphans",
    )
  }

  /** Class (b), blast radius NOT determinable: a file that could not be read at all means
    * observed keys cannot even be grouped, so NO orphan set exists.
    */
  test("an unreadable file degrades the scan and suppresses orphans entirely") {
    val anki = InMemoryAnki()
    runPlan(planOf(scanOf(sourced(twoFieldSpec(key("n1", "A", "X"), "f", "b"))), observe(anki)), anki)

    val scan = VaultScan.from(
      Vector.empty,
      Vector(BuildFailure.FileUnreadable("Broken.md", "no frontmatter id")),
    )
    val plan = planOf(scan, observe(anki))
    assert(!plan.actions.exists(_.isInstanceOf[SyncAction.Flag]), "a partial scan flagged orphans")
    assert(
      plan.orphanInference.isInstanceOf[OrphanInference.SuppressedIncompleteScan],
      "a partial scan must SAY it could not look, not silently find nothing",
    )
  }

  test("creates and updates still happen on a partial scan — only orphans are suppressed") {
    val anki = InMemoryAnki()
    val scan = VaultScan.from(
      Vector(sourced(twoFieldSpec(key("n1", "A", "X"), "f", "b"))),
      Vector(BuildFailure.FileUnreadable("Broken.md", "unreadable")),
    )
    assertEquals(planOf(scan, observe(anki)).actions.size, 1)
  }

  // ================================================================ B10 ====

  test("B10: two sources deriving one key is rejected before anything is written") {
    val k = key("n1", "A", "Definition")
    val scan = scanOf(
      SourcedSpec(twoFieldSpec(k, "f", "one"), SourceRef("Note.md", 10, SourceKind.Heading)),
      SourcedSpec(twoFieldSpec(k, "f", "two"), SourceRef("Note.md", 40, SourceKind.TablePair)),
    )
    Planner.plan(scan, ObservedState(Vector.empty), _ => defaultDeck, newNoteOf) match
      case Left(errors) => assertEquals(errors.size, 1)
      case Right(_)     => fail("a duplicate key was allowed through")
  }

  /** Loud is not enough — it must be LEGIBLE. A collision between a table cell and a nested
    * heading, reported as "duplicate key", costs an hour and teaches nothing.
    */
  test("B10: the error names both sources, their kinds and their positions") {
    val k = key("n1", "Messaging", "Definition")
    val scan = scanOf(
      SourcedSpec(twoFieldSpec(k, "f", "one"), SourceRef("Messaging.md", 10, SourceKind.Heading)),
      SourcedSpec(twoFieldSpec(k, "f", "two"), SourceRef("Messaging.md", 42, SourceKind.TableRow)),
    )
    val message = Planner.checkUnique(scan.specs).map(_.describe).mkString
    assert(message.contains("messaging / definition"), s"key not named: $message")
    assert(message.contains("Messaging.md:10"), s"first position missing: $message")
    assert(message.contains("Messaging.md:42"), s"second position missing: $message")
    assert(message.contains("heading"), s"first kind missing: $message")
    assert(message.contains("table row card"), s"second kind missing: $message")
  }

  // ================================================================ hashing ====

  test("the content hash distinguishes what it must") {
    val k    = key("n1", "A", "B")
    val base = twoFieldSpec(k, "front", "back")
    assertEquals(Planner.contentHash(base), Planner.contentHash(twoFieldSpec(k, "front", "back")))
    assertNotEquals(Planner.contentHash(base), Planner.contentHash(twoFieldSpec(k, "front", "other")))
    // Field boundaries must matter: ("ab","c") and ("a","bc") are different content.
    assertNotEquals(
      Planner.contentHash(twoFieldSpec(k, "ab", "c")),
      Planner.contentHash(twoFieldSpec(k, "a", "bc")),
    )
    // The note type is part of the hash, so a retype is always visible as a difference.
    assertNotEquals(
      Planner.contentHash(base),
      Planner.contentHash(CardSpec.TwoField(k, "front", body("back"), TwoFieldDirections.Both)),
    )
  }

  // ================================================ execution failures ====

  /** A silent no-op here would report "nothing to do" while the reverse card the author
    * asked for never appeared — the exact failure Retype was carved out to prevent,
    * reintroduced one layer down. Until the AnkiConnect capability is verified it must be
    * LOUD.
    */
  test("Retype is not silently skipped — it fails loudly as unimplemented") {
    val anki = InMemoryAnki()
    val k    = key("n1", "A", "Term")
    runPlan(planOf(scanOf(sourced(twoFieldSpec(k, "Term", "def"))), observe(anki)), anki)

    val reversed = CardSpec.TwoField(k, "Term", body("def"), TwoFieldDirections.Both)
    val plan     = planOf(scanOf(sourced(reversed)), observe(anki))
    val failures = runPlanCollecting(plan, anki)

    assertEquals(failures.size, 1, s"expected one reported failure, got $failures")
    failures.head.error match
      case AnkiError.UnsupportedOperation(what, _) =>
        assert(what.contains("retype"), s"failure does not name the operation: $what")
      case other => fail(s"expected UnsupportedOperation, got $other")
  }

  /** RULED: one failing action must not abandon the rest of the plan. */
  test("a failing action does not abort the remainder of the plan") {
    val anki  = InMemoryAnki()
    val good1 = key("n1", "A", "One")
    val bad   = key("n1", "A", "Retyped")
    val good2 = key("n1", "A", "Two")

    // Seed the card that will need a retype, plus nothing else.
    runPlan(planOf(scanOf(sourced(twoFieldSpec(bad, "Term", "def"))), observe(anki)), anki)

    val scan = scanOf(
      sourced(twoFieldSpec(good1, "f", "b")),
      sourced(CardSpec.TwoField(bad, "Term", body("def"), TwoFieldDirections.Both)),
      sourced(twoFieldSpec(good2, "f", "b")),
    )
    val failures = runPlanCollecting(planOf(scan, observe(anki)), anki)

    assertEquals(failures.size, 1, "expected exactly the retype to fail")
    val present = observe(anki).notes.map(_.key).toSet
    assert(present.contains(good1), "an action after the failure was abandoned")
    assert(present.contains(good2), "an action after the failure was abandoned")
  }

  /** The executor's analogue of the law. Without this, "idempotent" covers only the happy
    * path — and a half-applied plan is the case where a resumable design actually pays.
    */
  test("LAW: a half-applied plan re-plans to EXACTLY the unapplied remainder") {
    val anki = InMemoryAnki()
    val keys = (1 to 6).map(i => key("n1", "A", s"Card $i")).toVector
    val scan = VaultScan.from(keys.map(k => sourced(twoFieldSpec(k, "f", s"body ${k.path.render}"))), Vector.empty)

    val full = planOf(scan, observe(anki))
    assertEquals(full.actions.size, 6)

    // Apply only the first half, as an interrupted run would.
    val applied   = full.actions.take(3)
    val unapplied = full.actions.drop(3)
    runPlan(Plan(applied, full.orphanInference, Vector.empty), anki)

    val resumed = planOf(scan, observe(anki))
    assertEquals(
      resumed.actions.size,
      unapplied.size,
      s"resumed plan was not the remainder: ${resumed.actions}",
    )
    assertEquals(
      resumed.actions.collect { case SyncAction.Create(k, _) => k }.toSet,
      unapplied.collect { case SyncAction.Create(k, _) => k }.toSet,
      "the resumed plan is not exactly the unapplied remainder",
    )
    // And nothing that DID land is rewritten.
    assert(
      !resumed.actions.exists(a => applied.collect { case SyncAction.Create(k, _) => k }.contains(a match {
        case SyncAction.Create(k, _)    => k
        case SyncAction.Update(k, _, _) => k
        case SyncAction.Flag(k, _)      => k
        case SyncAction.Unflag(k, _)    => k
        case SyncAction.Retype(k, _, _, _) => k
        case SyncAction.Relink(k, _, _) => k
      })),
      "an already-applied action was scheduled again",
    )

    // Finishing the job converges.
    runPlan(resumed, anki)
    assertEquals(planOf(scan, observe(anki)).actions, Vector.empty)
  }
