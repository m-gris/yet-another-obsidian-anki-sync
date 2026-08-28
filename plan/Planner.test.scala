package obsidiananki.plan

import cats.data.NonEmptyVector
import obsidiananki.anki.*
import obsidiananki.model.*
import obsidiananki.plan.SectionChain.{NoRecall, NoSectionChain}

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
      CardPath.Headings(HeadingPath(
        NonEmptyVector.fromVectorUnsafe(
          segments.toVector.map(s => HeadingSegment.fromExtractedText(s).toOption.get)
        )
      )),
    )

  def deck(segments: String*): DeckPath =
    DeckPath(NonEmptyVector.fromVectorUnsafe(segments.toVector))

  val defaultDeck: DeckPath = deck("Obsidian", "System-Design")

  def body(s: String): Body = Body.fromExtracted(s).getOrElse(fail("empty test body"))

  /** The `context` breadcrumb is a fixed literal in this file, and that is deliberate.
    * Nothing the planner does reads it — it is one more field value, hashed with the rest —
    * so varying it here would suggest a coupling that does not exist. It is non-empty rather
    * than `""` so that a spec built here is shaped like one the extractor really produces.
    */
  val testContext: String = "Coupling"

  def twoFieldSpec(k: CardKey, front: String, back: String): CardSpec =
    CardSpec.TwoField(k, front, body(back), TwoFieldDirections.Forward, testContext)

  // Fixture defaults for a source reference. These tests are about planning rather than about
  // where a card came from; a wrong file or line would show up in the key being asserted.
  // ast-grep-ignore: default-parameter
  def sourced(spec: CardSpec, file: String = "Note.md", line: Int = 1): SourcedSpec =
    SourcedSpec(spec, SourceRef(file, line, SourceKind.Heading), NoSectionChain, NoRecall)

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
    *
    * `RetypePolicy.Apply` THROUGHOUT THIS FILE, so that a note-type move is ATTEMPTED rather
    * than set aside. The opposite policy would make several tests below pass for the wrong
    * reason — a deferred retype is not a failure and is also not a write, so "nothing failed"
    * and "the note did not move" would both hold no matter what the executor did.
    */
  def runPlan(p: Plan, anki: InMemoryAnki): Unit =
    val failures = runPlanCollecting(p, anki)
    assert(failures.isEmpty, s"unexpected execution failures: $failures")

  def runPlanCollecting(p: Plan, anki: InMemoryAnki): Vector[ExecutionFailure] =
    runReport(p, anki, RetypePolicy.Apply).failures

  def runReport(p: Plan, anki: InMemoryAnki, policy: RetypePolicy): ExecutionReport =
    Executor.run(p, anki, policy, Set.empty).fold(e => fail(s"execution aborted entirely: $e"), identity)

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
          testContext,
          // Built from headings, so nothing names the concept's kind.
          "",
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

  /** A NOTE THAT CHANGED BOTH ITS FOLDER AND ITS NOTE TYPE MUST ARRIVE IN BOTH PLACES.
    *
    * The defect this pins: `deckDiffers` used to be computed only in the branch taken when the
    * note type MATCHES, so a note that changed both had its deck silently left behind. The run
    * reported itself clean and exited zero; the NEXT run then moved the deck unasked, which
    * breaks "a second run changes nothing" rather than hiding behind it.
    *
    * THE POLICY MUST BE `Apply`. Under `Defer` the move is set aside, so "the deck did not move"
    * holds no matter what the executor does and the test proves nothing.
    *
    * THE TWO NOTE TYPES MUST BE SAME-SHAPE. `Retyping` refuses a move between types with
    * different cloze-ness or template counts, and a refusal would make this go green for the
    * wrong reason — the deck would be unmoved because NOTHING happened.
    */
  test("a note that moved folder AND changed note type arrives in the new deck, in one run") {
    val anki = InMemoryAnki()
    val k    = key("n1", "A", "B")

    // Start: a one-way card, in the default deck.
    runPlan(planOf(scanOf(sourced(twoFieldSpec(k, "f", "body"))), observe(anki)), anki)

    // Then BOTH change at once: the marker becomes `sequence`, and the file moves folder.
    //
    // `1way` -> `sequence` IS THE PAIR TO USE, and not an arbitrary choice: `Retyping` refuses
    // a move between note types whose cloze-ness or template COUNT differ, and of this tool's
    // five types these two are the only same-shape pair — both standard, both one template.
    // Any other pair is refused, the run fails loudly, and the test would prove nothing about
    // decks because nothing would have moved.
    val moved   = deck("Obsidian", "Patterns")
    val retyped = CardSpec.Sequence(k, "f", body("<ul><li>body</li></ul>"), testContext, RevealOrder.DepthFirst)
    val plan = Planner
      .plan(scanOf(sourced(retyped)), observe(anki), _ => moved, newNoteOf)
      .fold(e => fail(s"$e"), identity)

    assertEquals(plan.actions.size, 1, s"expected ONE action carrying the whole note: ${plan.actions}")
    runPlan(plan, anki)

    // ASSERTED ON ANKI, not on the plan: the question is where the card ended up.
    val after = observe(anki).notes.head
    assertEquals(after.note.noteType, retyped.noteTypeName, "the note type did not change")
    assertEquals(after.deck, Some(moved), "the note type moved but the card was left in its old deck")

    // AND THE LAW HOLDS. This is the half the old behaviour broke: it converged, but only by
    // doing unrequested work on a later run.
    val second = Planner
      .plan(scanOf(sourced(retyped)), observe(anki), _ => moved, newNoteOf)
      .fold(e => fail(s"$e"), identity)
    assertEquals(second.actions, Vector.empty, s"a second run still had work to do: ${second.actions}")
  }

  test("a retype that does NOT move folders carries no deck, so no pointless write is issued") {
    val anki = InMemoryAnki()
    val k    = key("n1", "A", "B")
    runPlan(planOf(scanOf(sourced(twoFieldSpec(k, "f", "body"))), observe(anki)), anki)

    val retyped = CardSpec.Sequence(k, "f", body("<ul><li>body</li></ul>"), testContext, RevealOrder.DepthFirst)
    planOf(scanOf(sourced(retyped)), observe(anki)).actions match
      case Vector(r: SyncAction.Retype) =>
        assertEquals(r.deck, None, "a deck move was planned for a note that never moved")
      case other => fail(s"expected a single Retype, got $other")
  }

  test("a marker change becomes a Retype, never a silent field update") {
    val anki = InMemoryAnki()
    val k    = key("n1", "A", "Term")
    // 1way first...
    runPlan(planOf(scanOf(sourced(twoFieldSpec(k, "Term", "def"))), observe(anki)), anki)

    // ...then retagged as 2way, which shares field names with Basic. An ordinary update
    // would SUCCEED here and the reverse card would never exist.
    val reversed = CardSpec.TwoField(k, "Term", body("def"), TwoFieldDirections.Both, testContext)
    planOf(scanOf(sourced(reversed)), observe(anki)).actions match
      case Vector(SyncAction.Retype(_, _, from, to, _, _, _, _)) =>
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

  /** Class (a'): THE FAILURE KEY IS A PREFIX OF THE CARDS' KEYS, WHICH IS THE TABLE CASE.
    *
    * `Planner.underAFailedSection` existed and was correct before this test did, and NOTHING
    * EXERCISED IT — deleting the clause left the whole suite green. That is what this test is
    * for. It was added on 2026-08-24 after the `underAFailedSection` clause was commented out
    * and the suite still passed; with this test present, that same deletion fails here.
    *
    * WHY A PREFIX AND NOT AN EQUALITY. `Extractor.walk` records every `buildSpecs` failure at
    * the key of the SECTION that failed, while `Tables.cardsForRow` keys each card one or two
    * segments DEEPER — the row's concept, plus the column header for a pair card. So the
    * section key that failed matches NO observed note, and every card the table ever produced
    * looks unclaimed. Before the clause existed, one image pasted into one cell flagged and
    * SUSPENDED fifteen live cards while the run reported "1 card could not be built".
    *
    * THE CONTROL CARD IS THE POINT OF THE TEST, not decoration. `stillOrphaned` is a genuinely
    * absent card in the SAME NOTE, sitting outside the failed section. Without it this test
    * would pass just as well against a planner that had stopped flagging orphans altogether,
    * or one that suppressed the entire note rather than the failed subtree — which is the
    * over-broad fix a later author is most likely to reach for.
    */
  test("a failure at a section key shelters the cards keyed BENEATH it, and only those") {
    val anki    = InMemoryAnki()
    val section = key("n1", "Messaging", "Cost / benefit")
    val row     = key("n1", "Messaging", "Cost / benefit", "Queue")
    val pair    = key("n1", "Messaging", "Cost / benefit", "Queue", "Benefit")

    // OUTSIDE the failed section, and deliberately in the SAME note — a shelter keyed on the
    // note id rather than the path would swallow this one and the assertion would not notice.
    val stillOrphaned = key("n1", "Messaging", "Unrelated heading")

    // Seeded in ONE scan, for the reason the neighbouring test gives: syncing them one at a
    // time flags the earlier cards along the way, and the assertions below would then pass
    // because already-flagged orphans are skipped rather than because sheltering works.
    runPlan(
      planOf(
        VaultScan.from(
          Vector(row, pair, stillOrphaned).map(k => sourced(twoFieldSpec(k, "f", "b"))),
          Vector.empty,
        ),
        observe(anki),
      ),
      anki,
    )

    // The section fails to build. Its cards are therefore not in the scan — a failed build
    // produces no specs — and neither is the unrelated card, which was really deleted.
    val scan = VaultScan.from(
      Vector.empty,
      Vector(
        BuildFailure.KeyKnown(
          section,
          SourceRef("Messaging.md", 12, SourceKind.Heading),
          "an image in a table cell",
        )
      ),
    )

    val flagged = planOf(scan, observe(anki)).actions.collect { case SyncAction.Flag(k, _) => k }

    assertEquals(
      flagged.toSet,
      Set(stillOrphaned),
      "expected ONLY the card outside the failed section to be flagged — if the two table " +
        "cards appear here the prefix rule is gone; if nothing appears, sheltering has become " +
        "note-wide and is now hiding real orphans",
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
      SourcedSpec(twoFieldSpec(k, "f", "one"), SourceRef("Note.md", 10, SourceKind.Heading), NoSectionChain, NoRecall),
      SourcedSpec(twoFieldSpec(k, "f", "two"), SourceRef("Note.md", 40, SourceKind.TablePair), NoSectionChain, NoRecall),
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
      SourcedSpec(twoFieldSpec(k, "f", "one"), SourceRef("Messaging.md", 10, SourceKind.Heading), NoSectionChain, NoRecall),
      SourcedSpec(twoFieldSpec(k, "f", "two"), SourceRef("Messaging.md", 42, SourceKind.TableRow), NoSectionChain, NoRecall),
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
      Planner.contentHash(
        CardSpec.TwoField(k, "front", body("back"), TwoFieldDirections.Both, testContext)
      ),
    )
  }

  // ================================================ execution failures ====

  /** A silent no-op here would report "nothing to do" while the reverse card the author
    * asked for never appeared — the exact failure Retype was carved out to prevent,
    * reintroduced one layer down.
    *
    * `Obsidian Basic` HAS ONE CARD TEMPLATE AND `Obsidian Basic (and reversed card)` HAS TWO,
    * so this particular move is one `plan/Retyping.scala` refuses to make: what Anki does with
    * a card whose ordinal the new note type cannot generate is unestablished, and the second
    * card's generation is equally unmeasured. The refusal is LOUD and names both note types —
    * it is not the same thing as the deferral that `RetypePolicy.Defer` produces, which is why
    * this asserts a failure rather than merely "the note did not move".
    */
  test("a move between differently-shaped note types is REFUSED, loudly and by name") {
    // NARROWING, NOT WIDENING. This test seeded a one-way card and asked for a two-way one until
    // 2026-08-26 — a move the gate refused then and admits now, because growth was measured to
    // keep every card, every id and every review log. The refusal this test is about is the other
    // direction: a card that went both ways, narrowed to one, whose second card would be stranded
    // on a note type that cannot generate its ordinal.
    val anki = InMemoryAnki()
    val k    = key("n1", "A", "Term")
    val both = CardSpec.TwoField(k, "Term", body("def"), TwoFieldDirections.Both, testContext)
    runPlan(planOf(scanOf(sourced(both)), observe(anki)), anki)

    // REPORTED AS WAITING SINCE 2026-08-27, NOT AS A FAILURE. What this test is about has not
    // changed — both note types must be named, the reason must say what differs, a remedy must
    // be offered, and the note must not have moved — but those sentences now live on the price
    // rather than on a raised error, so they are read off the report.
    val plan   = planOf(scanOf(sourced(twoFieldSpec(k, "Term", "def"))), observe(anki))
    val report = runReport(plan, anki, RetypePolicy.Apply)

    assertEquals(report.failures, Vector.empty, s"a decision was reported as a failure: $report")
    assertEquals(report.pending.size, 1, s"expected one change waiting on an answer: $report")

    val loss = report.pending.head.loss
    assert(
      loss.describe.contains(Marker.NoteTypes.BasicAndReversed),
      s"the source note type is not named: ${loss.describe}",
    )
    assert(
      loss.describe.contains(Marker.NoteTypes.Basic),
      s"the target note type is not named: ${loss.describe}",
    )
    assert(loss.describe.contains("template"), s"the reason does not say what differs: ${loss.describe}")
    assert(loss.remedy.contains("Change Note Type"), s"no remedy is offered: ${loss.remedy}")

    assertEquals(
      observe(anki).notes.head.note.noteType,
      Marker.NoteTypes.BasicAndReversed,
      "a change that was only being asked about moved the note anyway",
    )
  }

  /** RULED: one failing action must not abandon the rest of the plan. */
  test("a failing action does not abort the remainder of the plan") {
    val anki  = InMemoryAnki()
    val good1 = key("n1", "A", "One")
    val bad   = key("n1", "A", "Retyped")
    val good2 = key("n1", "A", "Two")

    // Seed the card that will need a retype, plus nothing else. It is seeded WIDE and narrowed
    // below, because narrowing is the direction the gate still refuses.
    runPlan(
      planOf(
        scanOf(sourced(CardSpec.TwoField(bad, "Term", body("def"), TwoFieldDirections.Both, testContext))),
        observe(anki),
      ),
      anki,
    )

    val scan = scanOf(
      sourced(twoFieldSpec(good1, "f", "b")),
      sourced(twoFieldSpec(bad, "Term", "def")),
      sourced(twoFieldSpec(good2, "f", "b")),
    )
    // WHAT THIS NOW EXERCISES, AND WHAT IT NO LONGER DOES — worth stating, because the change
    // is a real loss of coverage rather than a rewording.
    //
    // The action in the middle used to FAIL, and this test's name is about a failure not
    // aborting the rest. Since 2026-08-27 a narrowing does not fail: `Executor.run` prices it
    // and partitions it out BEFORE execution, so it never reaches the code that could abort.
    //
    // What is asserted below is therefore the PARTITION rather than failure resilience: setting
    // one action aside must not drop the actions around it, which is a genuine risk of the way
    // that partition rebuilds the action list, and which nothing else covers.
    //
    // THE ORIGINAL INVARIANT IS NOW UNCOVERED, and nothing else covers it — checked. Only a
    // cloze-kind mismatch still raises from a retype, and building that fixture here is more
    // than a rename. Recorded as item 30 of `IN-FLIGHT.md`, which also names the fault-injecting
    // doubles in two other test files as the route nobody tried.
    val report = runReport(planOf(scan, observe(anki)), anki, RetypePolicy.Apply)

    assertEquals(report.failures, Vector.empty, s"nothing here should fail any more: $report")
    assertEquals(report.pending.size, 1, "expected exactly the narrowing to be set aside")
    val present = observe(anki).notes.map(_.key).toSet
    assert(present.contains(good1), "an action beside the one set aside was abandoned")
    assert(present.contains(good2), "an action beside the one set aside was abandoned")
  }

  /** THE OTHER HALF, RESTORED 2026-08-28 — `IN-FLIGHT.md` item 30.
    *
    * The test above is about an action SET ASIDE. This one is about an action that genuinely
    * RAISES, which is what the pair's name has always claimed and what stopped being covered on
    * 2026-08-27, when narrowing began to be priced and partitioned out before execution instead
    * of failing. The invariant is worth more than the pair: one bad note must not leave the rest
    * of a vault unsynced until somebody fixes it.
    *
    * THE FAILURE IS A REAL ONE RATHER THAN AN INJECTED FAULT, which is why no test double
    * appears here. Anki refuses a note whose first field duplicates an existing one, and this
    * collection is built with that refusal switched on — so the middle action fails through
    * exactly the path a real duplicate would take. The item named fault injection as the
    * untried route; a real error turned out to be available and is worth more, since an
    * injected fault can only prove the executor survives errors it was handed, not errors the
    * collection actually produces.
    *
    * THE POSITION OF THE FAILURE IS ASSERTED, NOT ASSUMED. If the failing action were planned
    * last, everything below would pass while proving nothing at all — the actions "after" it
    * would be an empty set. That guard is the difference between this test and a vacuous one.
    */
  test("a failing action does not abort the remainder of the plan") {
    val anki = InMemoryAnki(allowDuplicate = false)

    // A note already in the collection whose FRONT is the text the middle action will re-use.
    val seeded = key("n0", "A", "Seeded")
    runPlan(planOf(scanOf(sourced(twoFieldSpec(seeded, "Clash", "b"))), observe(anki)), anki)

    val before = key("n1", "A", "Before")
    val clash  = key("n1", "A", "Clash")
    val after  = key("n1", "A", "After")

    val scan = scanOf(
      sourced(twoFieldSpec(before, "one", "b")),
      sourced(twoFieldSpec(clash, "Clash", "b")),
      sourced(twoFieldSpec(after, "two", "b")),
    )
    val plan = planOf(scan, observe(anki))

    // THE KEY EACH ACTION NAMES. Every case carries one, but the enum does not expose a common
    // accessor, so this match does it — exhaustively, on purpose: an action added later has to
    // say which card it is about rather than falling into a wildcard and being skipped here.
    def keyOf(a: SyncAction): CardKey = a match
      case SyncAction.Create(k, _)    => k
      case SyncAction.Update(k, _, _) => k
      case r: SyncAction.Retype       => r.key
      case SyncAction.Flag(k, _)      => k
      case SyncAction.Unflag(k, _)    => k

    val failingAt = plan.actions.indexWhere(a => keyOf(a) == clash)
    assert(failingAt >= 0, s"the clashing action was not planned: ${plan.actions.map(keyOf)}")
    assert(
      failingAt < plan.actions.size - 1,
      "the failing action is planned LAST, so nothing follows it and this test proves nothing",
    )

    val report = runReport(plan, anki, RetypePolicy.Apply)

    assertEquals(report.failures.size, 1, s"expected exactly the duplicate to fail: $report")
    assertEquals(keyOf(report.failures.head.action), clash, "the wrong action failed")

    val present = observe(anki).notes.map(_.key).toSet
    assert(present.contains(before), "an action BEFORE the failure was lost")
    assert(
      present.contains(after),
      "an action AFTER the failure never ran — one bad note abandoned the rest of the plan",
    )
    assert(!present.contains(clash), "the duplicate was written after all, so nothing failed")
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
    runPlan(Plan(applied, full.orphanInference, Vector.empty, full.parked), anki)

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
        case SyncAction.Retype(k, _, _, _, _, _, _, _) => k
      })),
      "an already-applied action was scheduled again",
    )

    // Finishing the job converges.
    runPlan(resumed, anki)
    assertEquals(planOf(scan, observe(anki)).actions, Vector.empty)
  }
