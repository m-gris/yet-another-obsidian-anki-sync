package obsidiananki.plan

import cats.data.NonEmptyVector
import obsidiananki.anki.*
import obsidiananki.model.*
import obsidiananki.plan.SectionChain.{NoRecall, NoSectionChain}

/** Moving a note from one Anki note type to another, which is the operation that carries this
  * tool's own note types into a collection that was synced before they existed.
  *
  * WHY THIS IS A SUITE OF ITS OWN. `PlannerTest` proves that a note-type difference is planned
  * as a [[SyncAction.Retype]] rather than as an ordinary field update. What it says nothing
  * about is the part that can destroy something: the move blanks every field of the note and
  * replaces its entire tag set before writing anything back, so anything the action failed to
  * carry is gone. Every test below asserts on WHAT THE COLLECTION ENDS UP HOLDING — the note's
  * type, its fields, its tags, its card ids — and never on which calls were made.
  *
  * THE THREE FACTS THAT MUST NOT BLUR INTO EACH OTHER, and which this file keeps apart:
  *
  *   - APPLIED. The move was made.
  *   - DEFERRED. The run was not asked to make it ([[RetypePolicy.Defer]]). Nothing was
  *     attempted, nothing failed, and the work is outstanding.
  *   - REFUSED. The move was asked for and this tool declined, because the two note types are
  *     shaped differently enough that a card could be left holding an ordinal its new note
  *     type cannot generate. That is a failure and reads as one.
  */
class RetypingTest extends munit.FunSuite:

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

  val defaultDeck: DeckPath = DeckPath(NonEmptyVector.of("Obsidian", "System-Design"))

  def body(s: String): Body = Body.fromExtracted(s).getOrElse(fail("empty test body"))

  val testContext: String = "Coupling"

  def sourced(spec: CardSpec): SourcedSpec =
    SourcedSpec(spec, SourceRef("Note.md", 1, SourceKind.Heading), NoSectionChain, NoRecall)

  def scanOf(specs: CardSpec*): VaultScan =
    VaultScan.from(specs.toVector.map(sourced), Vector.empty)

  def newNoteOf(s: SourcedSpec, d: DeckPath, sha: String): NewNote =
    NewNote(
      noteType = s.spec.noteTypeName,
      deck = d,
      fields = s.spec.fields,
      tags = NonEmptyVector.of(TagCodec.encode(s.key), OwnedTag.sha(sha)),
    )

  def observe(anki: InMemoryAnki): ObservedState =
    Observer.observe(anki).fold(e => fail(s"observe failed: $e"), identity)

  def planOf(scan: VaultScan, anki: InMemoryAnki): Plan =
    Planner
      .plan(scan, observe(anki), _ => defaultDeck, newNoteOf)
      .fold(errs => fail(s"unexpected plan errors: ${errs.map(_.describe)}"), identity)

  def runReport(p: Plan, anki: InMemoryAnki, policy: RetypePolicy): ExecutionReport =
    Executor.run(p, anki, policy).fold(e => fail(s"execution aborted entirely: $e"), identity)

  /** A stand-in for Anki's stock `Basic`: one card template, not cloze, `Front` and `Back` and
    * no `Context`.
    *
    * BUILT HERE RATHER THAN READ FROM `resources/note-types/`, because it is deliberately NOT
    * one of this tool's own note types — it is the shape a collection synced before 2026-08-21
    * actually holds, and it is what every note in `claude-POC-test` sits on today. Its template
    * count and its cloze flag are the two properties the gate reads; the template TEXT is
    * irrelevant to every assertion here and is a placeholder.
    */
  val stockBasic: NoteTypeSpec = NoteTypeSpec(
    name = "Basic",
    isCloze = false,
    fields = NonEmptyVector.of(Marker.BasicFields.Front, Marker.BasicFields.Back),
    templates = NonEmptyVector.one("Card 1" -> CardTemplate("{{Front}}", "{{FrontSide}}{{Back}}")),
    styling = ".card { }",
  )

  /** A stand-in for Anki's stock `Cloze`: one template, and CLOZE. */
  val stockCloze: NoteTypeSpec = NoteTypeSpec(
    name = "Cloze",
    isCloze = true,
    fields = NonEmptyVector.of(Marker.ClozeFields.Text, Marker.ClozeFields.BackExtra),
    templates = NonEmptyVector.one("Cloze" -> CardTemplate("{{cloze:Text}}", "{{cloze:Text}}")),
    styling = ".card { }",
  )

  def collectionWith(extra: NoteTypeSpec*): InMemoryAnki =
    InMemoryAnki(noteTypes = InMemoryAnki.defaultNoteTypes ++ extra.map(s => s.name -> s))

  /** One note sitting on `oldType`, tagged as this tool tags a note it has synced, plus the two
    * kinds of foreign tag that must survive: one a person applied and one Anki's own scheduler
    * applied.
    */
  def seedOnOldType(
      anki: InMemoryAnki,
      oldType: String,
      k: CardKey,
      fields: Vector[(String, String)],
  ): AnkiNoteId =
    val id = anki
      .addNote(
        NewNote(
          noteType = oldType,
          deck = defaultDeck,
          fields = fields,
          tags = NonEmptyVector.of(TagCodec.encode(k), OwnedTag.sha("deadbeefdeadbeef")),
        )
      )
      .fold(e => fail(s"seeding failed: $e"), identity)
    anki.simulateUserTag(id, "leech")
    anki.simulateUserTag(id, "marc-put-this-here")
    id

  def tagsOf(anki: InMemoryAnki, id: AnkiNoteId): Vector[String] =
    anki.notesInfo(Vector(id)).fold(e => fail(s"$e"), identity).head.tags

  def noteAt(anki: InMemoryAnki, id: AnkiNoteId): ObservedNote =
    anki.notesInfo(Vector(id)).fold(e => fail(s"$e"), identity).head

  val k: CardKey = key("n1", "Coupling", "Temporal coupling")

  def basicSpec: CardSpec =
    CardSpec.TwoField(k, "Temporal coupling", body("All up at once."), TwoFieldDirections.Forward, testContext)

  // ============================================================ the pure gate ====

  /** THE WHOLE SAFETY ARGUMENT, as a table. `None` means "the move cannot strand a card",
    * and it is reached only by arithmetic: same kind, same number of templates, so every
    * ordinal that exists is an ordinal the new note type generates.
    */
  test("the gate admits only same-kind, same-template-count moves") {
    def shape(templates: Int, cloze: Boolean) = NoteTypeShape(templates, cloze)

    assertEquals(
      Retyping.refusalFor("A", shape(1, false), "B", shape(1, false)),
      None,
      "a move that cannot strand a card was refused",
    )
    assertEquals(
      Retyping.refusalFor("A", shape(3, false), "B", shape(3, false)),
      None,
      "three templates to three templates was refused",
    )
    assertEquals(
      Retyping.refusalFor("A", shape(1, true), "B", shape(1, true)),
      None,
      "cloze to cloze was refused",
    )

    assertEquals(
      Retyping.refusalFor("A", shape(1, false), "B", shape(2, false)),
      Some(RetypeRefusal.TemplateCountDiffers("A", 1, "B", 2)),
      "growing the template count was allowed, and card GENERATION is unmeasured",
    )
    assertEquals(
      Retyping.refusalFor("A", shape(2, false), "B", shape(1, false)),
      Some(RetypeRefusal.TemplateCountDiffers("A", 2, "B", 1)),
      "shrinking the template count was allowed, and that is the case that strands a card",
    )
    assertEquals(
      Retyping.refusalFor("A", shape(1, true), "B", shape(1, false)),
      Some(RetypeRefusal.ClozeKindDiffers("A", true, "B", false)),
      "a cloze note was allowed onto a standard note type on equal template counts",
    )
  }

  /** THE REFUSAL MUST NAME THE RIGHT UNKNOWN, and the two directions have different ones.
    *
    * A single sentence covered both until 2026-08-24 and was accurate for only one: it said the
    * cards might carry an ordinal the new note type cannot generate, which is what happens when
    * the template count SHRINKS and cannot happen when it grows. Somebody retagging a heading
    * from one card to two was sent to reason about stranded cards that do not exist, while the
    * actual reason the tool withholds the move — that card GENERATION on a note-type change is
    * unmeasured — appeared nowhere.
    *
    * ASSERTED ON THE DISTINGUISHING CLAUSE, not on the whole sentence. Pinning the full text
    * would make this a change-detector that fails on any rewording; pinning the clause that
    * differs is what stops the two reasons being collapsed back into one.
    */
  test("a refusal explains the direction it is refusing, and the two directions differ") {
    val shrinking = RetypeRefusal.TemplateCountDiffers("Wide", 3, "Narrow", 1).describe
    val growing   = RetypeRefusal.TemplateCountDiffers("Narrow", 1, "Wide", 3).describe

    assert(
      shrinking.contains("ordinals it cannot generate"),
      s"the shrinking refusal stopped naming the stranded card, which is its whole reason: $shrinking",
    )
    assert(
      !shrinking.contains("GENERATES"),
      s"the shrinking refusal blamed generation, which is the other direction's unknown: $shrinking",
    )

    assert(
      growing.contains("GENERATES"),
      s"the growing refusal stopped naming card generation, which is its only reason: $growing",
    )
    assert(
      growing.contains("No existing card would be stranded"),
      s"the growing refusal did not say that stranding cannot happen here: $growing",
    )

    assertNotEquals(
      shrinking,
      growing,
      "both directions gave the same reason again — one of them is therefore wrong, which is " +
        "exactly the state this test was written to end",
    )

    // BOTH STILL REFUSED. The message changed; the gate did not. If this ever fails because
    // growing now returns None, that is the measurement having landed — update `IN-FLIGHT.md`
    // and this test together, and say which profile it was measured in.
    assertEquals(
      Retyping.refusalFor("Narrow", NoteTypeShape(1, false), "Wide", NoteTypeShape(3, false)),
      Some(RetypeRefusal.TemplateCountDiffers("Narrow", 1, "Wide", 3)),
      "the growth direction was admitted without a measurement to justify it",
    )
  }

  // ------------------------------------------- the verdict: both halves at once ----

  /** `verdictFor` IS THE FIX FOR A PREVIEW THAT LIED. The policy half was pure and the report
    * consulted it; the shape half needed the collection and lived inside the executor, which a
    * dry run never enters. So `--dry-run --migrate-note-types` announced a migration that the
    * real run then refused. These tests pin the combined decision — the thing both callers now
    * ask instead of each deciding a part.
    */
  test("the policy is asked before the shapes, so a deferral needs no shapes at all") {
    assertEquals(
      Retyping.verdictFor("A", "B", RetypePolicy.Defer, Map.empty),
      RetypeVerdict.DeferredByPolicy,
      "a deferred run consulted shapes it deliberately never read, and would now report the " +
        "collection as unmeasurable instead of simply saying it declined to act",
    )
  }

  /** ORDERING, ASSERTED SEPARATELY FROM THE EMPTY-MAP CASE ABOVE. That one passes even if the
    * shapes are consulted first and happen to be absent; this one fails, because here the
    * shapes are present AND incompatible. Only checking both pins the order.
    */
  test("a deferral is reported as deferred even when the shapes would have refused it") {
    assertEquals(
      Retyping.verdictFor(
        "A",
        "B",
        RetypePolicy.Defer,
        Map("A" -> NoteTypeShape(1, false), "B" -> NoteTypeShape(3, false)),
      ),
      RetypeVerdict.DeferredByPolicy,
      "declining on instruction was reported as declining on evidence — the person cannot " +
        "tell 'you did not ask me to' from 'you asked and it is not possible'",
    )
  }

  test("under Apply, a compatible pair is a move that will happen") {
    assertEquals(
      Retyping.verdictFor(
        "A",
        "B",
        RetypePolicy.Apply,
        Map("A" -> NoteTypeShape(2, false), "B" -> NoteTypeShape(2, false)),
      ),
      RetypeVerdict.WillApply,
    )
  }

  test("under Apply, an incompatible pair carries the refusal itself, not just a flag") {
    assertEquals(
      Retyping.verdictFor(
        "A",
        "B",
        RetypePolicy.Apply,
        Map("A" -> NoteTypeShape(1, false), "B" -> NoteTypeShape(3, false)),
      ),
      RetypeVerdict.RefusedByShapes(RetypeRefusal.TemplateCountDiffers("A", 1, "B", 3)),
      "the verdict must carry the refusal so the preview can print the SAME sentence the run " +
        "would have printed — a bare 'refused' would drift from it",
    )
  }

  /** EITHER SIDE MISSING, BOTH ASSERTED. A `shapes.get(from)` that forgot `to` would pass on
    * one of these and fail on the other.
    */
  test("a note type that could not be measured is its own verdict, not a refusal") {
    val onlyFrom = Map("A" -> NoteTypeShape(1, false))
    val onlyTo   = Map("B" -> NoteTypeShape(1, false))

    assertEquals(
      Retyping.verdictFor("A", "B", RetypePolicy.Apply, onlyFrom),
      RetypeVerdict.ShapesUnavailable("A", "B"),
    )
    assertEquals(
      Retyping.verdictFor("A", "B", RetypePolicy.Apply, onlyTo),
      RetypeVerdict.ShapesUnavailable("A", "B"),
    )
    assertEquals(
      Retyping.verdictFor("A", "B", RetypePolicy.Apply, Map.empty),
      RetypeVerdict.ShapesUnavailable("A", "B"),
    )
  }

  /** THE CLOZE TEST COMES FIRST, and the order is load-bearing rather than stylistic. A cloze
    * note may hold any number of cards regardless of how many templates its type declares, so
    * a template-count message would send the reader to count templates when the real problem is
    * that ordinals mean something else entirely on the other side.
    */
  test("when both differ, the cloze kind is what gets reported") {
    assertEquals(
      Retyping.refusalFor("A", NoteTypeShape(1, true), "B", NoteTypeShape(3, false)),
      Some(RetypeRefusal.ClozeKindDiffers("A", true, "B", false)),
    )
  }

  test("a refusal names both note types, what differs, and what to do instead") {
    val counts = RetypeRefusal.TemplateCountDiffers("Basic", 1, "Obsidian Basic (and reversed card)", 2)
    assert(counts.describe.contains("Basic"), counts.describe)
    assert(counts.describe.contains("Obsidian Basic (and reversed card)"), counts.describe)
    assert(counts.describe.contains("1") && counts.describe.contains("2"), counts.describe)
    assert(counts.remedy.contains("Change Note Type"), counts.remedy)

    val kinds = RetypeRefusal.ClozeKindDiffers("Cloze", true, "Obsidian Basic", false)
    assert(kinds.describe.contains("cloze"), kinds.describe)
    assert(kinds.describe.contains("standard"), kinds.describe)
  }

  // ======================================================= what the plan carries ====

  /** A MOVE BLANKS EVERY FIELD AND REPLACES EVERY TAG, so the action has to carry the whole of
    * both. This asserts on the action rather than on the outcome deliberately: it is the plan
    * that would be printed and reviewed before anyone applies it.
    */
  test("the planned move carries the whole new field set, the identity, the new hash and the foreign tags") {
    val anki = collectionWith(stockBasic)
    seedOnOldType(anki, "Basic", k, Vector("Front" -> "Temporal coupling", "Back" -> "All up at once."))

    planOf(scanOf(basicSpec), anki).actions match
      case Vector(SyncAction.Retype(planned, _, from, to, fields, ownedTags, preservedTags, _)) =>
        assertEquals(planned, k)
        assertEquals(from, "Basic")
        assertEquals(to, Marker.NoteTypes.Basic)

        assertEquals(fields, basicSpec.fields, "the move does not carry the whole new field set")
        assert(
          fields.exists((name, _) => name == Marker.ContextField),
          s"the field the new note type exists for is not being written: $fields",
        )

        val owned = ownedTags.toVector.map(_.value)
        assert(owned.contains(TagCodec.encode(k).value), s"the identity tag is not carried: $owned")
        assert(
          owned.contains(OwnedTag.sha(Planner.contentHash(basicSpec)).value),
          s"the hash of the content being written is not carried: $owned",
        )
        assert(
          !owned.contains("sha::deadbeefdeadbeef"),
          s"the STALE content hash was carried over, so the next run would skip the note: $owned",
        )

        assertEquals(
          preservedTags.sorted,
          Vector("leech", "marc-put-this-here"),
          "a foreign tag was dropped from the move, and this write is what would erase it",
        )
      case other => fail(s"expected exactly one Retype, got $other")
  }

  /** A MOVE OVER A FLAGGED NOTE PLANS AN `Unflag` AS WELL, AND THE `Unflag` COMES FIRST.
    *
    * _Rewritten 2026-08-25. This test previously asserted the OPPOSITE — that a move plans
    * exactly one action and no `Unflag` — and it was wrong in the way that costs review history._
    *
    * The reasoning it encoded was half right, which is why it survived review. A `Retype`
    * rebuilds the note's owned tags from scratch, so the stale `orphaned::` tag really is gone
    * by that same write and a second write to remove it really would be redundant. The half it
    * missed: `Unflag` does not only remove a tag. It UNSUSPENDS every card of the note first,
    * and nothing else in the retype path does that. So a note that was flagged, suspended, and
    * came back on a different note type ended up correctly keyed, correctly typed, untagged —
    * and suspended forever, invisible to every report because reports count notes CARRYING the
    * tag, and unreachable by every later run because content and deck then matched.
    *
    * The redundant tag removal is the price of reusing the action that already carries the
    * unsuspend, and it is the right price: teaching the retype arm its own second way to
    * unsuspend would put two mechanisms where one belongs.
    */
  test("a move over a FLAGGED note unflags it first, then retypes") {
    val anki = collectionWith(stockBasic)
    val id   = seedOnOldType(anki, "Basic", k, Vector("Front" -> "f", "Back" -> "b"))
    anki
      .addTags(Vector(id), Vector(OwnedTag.orphaned(k)))
      .fold(e => fail(s"$e"), identity)
    anki.cardsOf(Vector(id)).flatMap(anki.suspend).fold(e => fail(s"$e"), identity)

    val actions = planOf(scanOf(basicSpec), anki).actions
    assertEquals(actions.size, 2, s"expected an Unflag AND a Retype: $actions")

    // ORDER IS ASSERTED, not incidental. Interrupted between the two, an unflag-then-retype
    // leaves the note back in the queue on its OLD type — visible and fully repairable next
    // run. The reverse leaves the retype done and the card suspended, which IS the stranded
    // state this test exists for.
    assert(
      actions.head.isInstanceOf[SyncAction.Unflag],
      s"the Unflag must precede the Retype, or an interruption strands the card: $actions",
    )
    assert(actions(1).isInstanceOf[SyncAction.Retype], s"expected a Retype second: $actions")

    runReport(planOf(scanOf(basicSpec), anki), anki, RetypePolicy.Apply)

    assert(
      !tagsOf(anki, id).exists(_.startsWith(s"${OwnedTag.OrphanedPrefix}::")),
      s"the orphan flag survived the move: ${tagsOf(anki, id)}",
    )
    anki.cardsOf(Vector(id)).fold(e => fail(s"$e"), identity).foreach { c =>
      assert(!anki.isSuspended(c), s"card ${c.value} was left suspended by the move")
    }
  }

  /** THE CONTROL, and the reason the fix is conditional rather than unconditional. A note the
    * tool never flagged may still be suspended — by Marc, in Anki, on purpose. The settled
    * ruling is that this tool cannot tell its own suspension from a person's, so the `orphaned::`
    * tag is the ONLY evidence that a suspension was ours to undo. Without this test, the fix
    * above is satisfiable by unsuspending on every retype.
    */
  test("a move over an UNFLAGGED note plans no Unflag and leaves a hand-suspension alone") {
    val anki = collectionWith(stockBasic)
    val id   = seedOnOldType(anki, "Basic", k, Vector("Front" -> "f", "Back" -> "b"))
    anki.cardsOf(Vector(id)).flatMap(anki.suspend).fold(e => fail(s"$e"), identity)

    val actions = planOf(scanOf(basicSpec), anki).actions
    assertEquals(actions.size, 1, s"a move over an unflagged note planned more than one action: $actions")
    assert(!actions.exists(_.isInstanceOf[SyncAction.Unflag]), s"an Unflag was planned: $actions")

    runReport(planOf(scanOf(basicSpec), anki), anki, RetypePolicy.Apply)
    anki.cardsOf(Vector(id)).fold(e => fail(s"$e"), identity).foreach { c =>
      assert(anki.isSuspended(c), s"card ${c.value} was un-suspended by a retype that never flagged it")
    }
  }

  // ============================================================== the migration ====

  /** THE MIGRATION THIS SLICE EXISTS FOR, end to end against the fake collection.
    *
    * A note synced under Anki's stock `Basic` — which is what every note in this project's test
    * profile sits on — moves onto `Obsidian Basic`. Asserted on everything the operation could
    * have destroyed: the note id, the card ids, the deck, the foreign tags, and the field the
    * new note type was introduced for.
    */
  test("a note on a stock note type moves onto this tool's own, keeping its cards and its foreign tags") {
    val anki = collectionWith(stockBasic)
    val id   = seedOnOldType(anki, "Basic", k, Vector("Front" -> "Temporal coupling", "Back" -> "All up at once."))
    val cardsBefore = anki.cardsOf(Vector(id)).fold(e => fail(s"$e"), identity)
    val deckBefore  = anki.deckOf(cardsBefore.head).fold(e => fail(s"$e"), identity)

    val report = runReport(planOf(scanOf(basicSpec), anki), anki, RetypePolicy.Apply)
    assert(report.failures.isEmpty, s"the move failed: ${report.failures}")
    assert(report.deferred.isEmpty, s"the move was deferred although the policy was Apply: $report")

    val after = noteAt(anki, id)
    assertEquals(after.noteType, Marker.NoteTypes.Basic, "the note did not move")
    assertEquals(after.id, id, "the note id changed — this would be a new card with no history")
    assertEquals(
      anki.cardsOf(Vector(id)).fold(e => fail(s"$e"), identity),
      cardsBefore,
      "the card ids changed, so the review history went with them",
    )
    assertEquals(
      anki.deckOf(cardsBefore.head).fold(e => fail(s"$e"), identity),
      deckBefore,
      "the card left its deck",
    )

    assertEquals(after.fields, basicSpec.fields, "the fields did not survive the move intact")
    assert(
      after.tags.contains("leech") && after.tags.contains("marc-put-this-here"),
      s"a foreign tag was destroyed by the move: ${after.tags}",
    )
    assert(after.tags.contains(TagCodec.encode(k).value), s"the note lost its identity: ${after.tags}")
    assert(
      !after.tags.contains("sha::deadbeefdeadbeef"),
      s"the stale content hash survived: ${after.tags}",
    )
  }

  /** THE LAW, for the migration path. If the hash written by the move did not describe the
    * content the move wrote, the next run would plan the note again — for ever.
    */
  test("LAW: after a move, the next plan is empty") {
    val anki = collectionWith(stockBasic)
    seedOnOldType(anki, "Basic", k, Vector("Front" -> "Temporal coupling", "Back" -> "All up at once."))

    val scan = scanOf(basicSpec)
    runReport(planOf(scan, anki), anki, RetypePolicy.Apply)
    assertEquals(planOf(scan, anki).actions, Vector.empty, "a moved note was planned again")
  }

  /** DEFERRED IS NOT FAILED, AND IT IS NOT DONE EITHER. The default policy leaves the note
    * exactly as it was — asserted on the collection, because an outcome-only assertion would
    * pass while the note had been moved.
    */
  test("under the default policy the note is left alone and reported as DEFERRED, not as failed") {
    val anki = collectionWith(stockBasic)
    val id   = seedOnOldType(anki, "Basic", k, Vector("Front" -> "Temporal coupling", "Back" -> "All up at once."))
    val before = noteAt(anki, id)

    val report = runReport(planOf(scanOf(basicSpec), anki), anki, RetypePolicy.Defer)

    assert(report.failures.isEmpty, s"a deferral was reported as a failure: ${report.failures}")
    assertEquals(report.deferred.size, 1, s"the deferred move was not reported at all: $report")
    assertEquals(report.deferred.head.to, Marker.NoteTypes.Basic)

    val after = noteAt(anki, id)
    assertEquals(after.noteType, "Basic", "a deferred move moved the note anyway")
    assertEquals(after.fields, before.fields, "a deferred move rewrote the note's fields")
    assertEquals(after.tags, before.tags, "a deferred move rewrote the note's tags")
  }

  /** DEFERRING ONE ACTION MUST NOT HOLD UP THE REST OF THE PLAN. A vault where one heading
    * needs a move and forty do not must still sync the forty.
    */
  test("deferring a move does not stop the other actions in the plan") {
    val anki = collectionWith(stockBasic)
    seedOnOldType(anki, "Basic", k, Vector("Front" -> "Temporal coupling", "Back" -> "All up at once."))

    val fresh = CardSpec.TwoField(
      key("n1", "Coupling", "Afferent coupling"),
      "Afferent coupling",
      body("Who depends on me."),
      TwoFieldDirections.Forward,
      testContext,
    )
    val report = runReport(planOf(scanOf(basicSpec, fresh), anki), anki, RetypePolicy.Defer)

    assertEquals(report.deferred.size, 1)
    assert(report.failures.isEmpty, s"${report.failures}")
    assert(
      observe(anki).notes.map(_.key).contains(fresh.key),
      "the create that followed a deferred move was abandoned",
    )
  }

  // ================================================================== refusals ====

  /** A CLOZE NOTE MUST NOT LAND ON A STANDARD NOTE TYPE, even though both types here declare
    * exactly one card template. A cloze note's ordinals are cloze numbers and may run far past
    * one; on the other side they would be template indices that do not exist.
    *
    * Asserted on the collection as well as on the failure: a refusal that still wrote would be
    * the worst of both.
    */
  test("a cloze note is not moved onto a standard note type, even on equal template counts") {
    val anki = collectionWith(stockCloze)
    val id = seedOnOldType(
      anki,
      "Cloze",
      k,
      Vector(Marker.ClozeFields.Text -> "The shaft is the {{c1::diaphysis}}.", Marker.ClozeFields.BackExtra -> ""),
    )

    // The vault now asks for a sequence card at that key, whose note type is NOT cloze.
    val sequence = CardSpec.Sequence(k, "Layers", body("<ul><li>one</li><li>two</li></ul>"), testContext)
    val report   = runReport(planOf(scanOf(sequence), anki), anki, RetypePolicy.Apply)

    assertEquals(report.failures.size, 1, s"the move was not refused: $report")
    report.failures.head.error match
      case AnkiError.UnsupportedOperation(_, why) =>
        assert(why.contains("cloze"), s"the reason does not say what differs: $why")
      case other => fail(s"expected UnsupportedOperation, got $other")

    assertEquals(noteAt(anki, id).noteType, "Cloze", "a refused move moved the note anyway")
  }

  /** A REFUSAL IS A FAILURE, NOT A DEFERRAL, and the two must not be reported as one thing.
    * A deferral says "you did not ask me to"; a refusal says "you asked and I will not".
    */
  test("a refused move is reported as a failure and never as a deferral") {
    val anki = collectionWith(stockBasic)
    seedOnOldType(anki, "Basic", k, Vector("Front" -> "f", "Back" -> "b"))

    val reversed =
      CardSpec.TwoField(k, "Temporal coupling", body("All up at once."), TwoFieldDirections.Both, testContext)
    val report = runReport(planOf(scanOf(reversed), anki), anki, RetypePolicy.Apply)

    assertEquals(report.failures.size, 1, s"expected one refusal: $report")
    assert(report.deferred.isEmpty, s"a refusal was reported as a deferral: $report")
  }

  // ------------------------------- the preview must agree with the run it previews ----

  /** THE LAW THE DRY-RUN DEFECT BROKE, stated as a law rather than as a case.
    *
    * `--dry-run --migrate-note-types` printed `1 move to another note type` and `result: OK`
    * for a move the real run then refused, because the preview could reach the POLICY half of
    * the retype decision and not the SHAPE half — the half that needs the note types read out
    * of the collection, and that lived inside the executor a dry run never enters.
    *
    * THE LAW IS ASSERTED IN BOTH DIRECTIONS AGAINST THE SAME COLLECTION, which is what makes
    * it a law rather than two examples: a refusal must be previewed as a refusal AND an
    * admissible move must be previewed as one. Only checking the refusing direction would be
    * satisfied by a preview that refused everything.
    */
  def previewOf(p: Plan, anki: InMemoryAnki, policy: RetypePolicy): Vector[RetypeVerdict] =
    Executor
      .preview(p, anki, policy)
      .fold(e => fail(s"preview aborted: $e"), identity)
      .map(_._2)

  test("LAW: a move the run refuses is previewed as refused, not as work") {
    val anki = collectionWith(stockBasic)
    seedOnOldType(anki, "Basic", k, Vector("Front" -> "f", "Back" -> "b"))

    // `Basic` has one card template; asking for both directions needs the tool's own two-card
    // note type, so this is a 1 -> 2 move and the gate refuses it.
    val reversed =
      CardSpec.TwoField(k, "Temporal coupling", body("All up at once."), TwoFieldDirections.Both, testContext)
    val plan = planOf(scanOf(reversed), anki)

    // PREVIEW FIRST, AGAINST THE UNTOUCHED COLLECTION — the order a person experiences.
    val previewed = previewOf(plan, anki, RetypePolicy.Apply)
    assertEquals(previewed.size, 1, s"expected exactly one retype to preview: $previewed")
    assert(
      previewed.head.isInstanceOf[RetypeVerdict.RefusedByShapes],
      s"the preview called a refusable move ordinary work — this IS the dry-run defect: $previewed",
    )

    // AND THE RUN AGREES. Asserted rather than assumed: if this ever reports success the law is
    // still broken, only in the other direction.
    val report = runReport(plan, anki, RetypePolicy.Apply)
    assertEquals(report.failures.size, 1, s"the run did not refuse what the preview refused: $report")
  }

  test("LAW: a move the run makes is previewed as work, not as refused") {
    val anki = collectionWith(stockBasic)
    val id   = seedOnOldType(anki, "Basic", k, Vector("Front" -> "f", "Back" -> "b"))

    // One template to one template: admissible by arithmetic, and the case that keeps the test
    // above from passing against a preview that simply refuses everything.
    val plan = planOf(scanOf(basicSpec), anki)

    assertEquals(
      previewOf(plan, anki, RetypePolicy.Apply),
      Vector(RetypeVerdict.WillApply),
      "the preview refused a move the run makes, which is the same disagreement the other way",
    )

    runReport(plan, anki, RetypePolicy.Apply)
    assertEquals(
      noteAt(anki, id).noteType,
      Marker.NoteTypes.Basic,
      "the run did not make the move its own preview promised",
    )
  }

  /** UNDER `Defer` THE VERDICT IS `DeferredByPolicy`, WHATEVER THE COLLECTION HOLDS.
    *
    * WHAT THIS DOES NOT ASSERT, said plainly so nobody reads more into it: it does not prove
    * that no request was made. `InMemoryAnki` exposes no request log, so the claim in
    * `Executor.preview` that a deferred preview costs nothing is enforced only by reading it.
    * Making that claim testable means giving the fake a counter, which is a change to the fake
    * and belongs with whoever needs it — a comment saying "verified" here would be worse than
    * this one saying it is not.
    */
  test("LAW: previewing a deferred run makes no request of the collection") {
    val anki = collectionWith(stockBasic)
    seedOnOldType(anki, "Basic", k, Vector("Front" -> "f", "Back" -> "b"))
    val plan = planOf(scanOf(basicSpec), anki)

    assertEquals(
      previewOf(plan, anki, RetypePolicy.Defer),
      Vector(RetypeVerdict.DeferredByPolicy),
      "a deferred retype was previewed as something other than deferred",
    )
  }

  // ========================================= the behaviours that force the design ====

  /** THE REASON THE ACTION CARRIES THE WHOLE FIELD SET, demonstrated against the fake rather
    * than asserted in a comment.
    *
    * Anki blanks every field of the new note type and then fills in only the names it was
    * given, ignoring any it does not recognise — with no error either way. So a caller that
    * sends a subset gets a note with empty fields and a successful return value.
    */
  test("a move that names too few fields leaves the rest EMPTY, and reports success") {
    val anki = collectionWith(stockBasic)
    val id   = seedOnOldType(anki, "Basic", k, Vector("Front" -> "Term", "Back" -> "definition"))

    val outcome = anki.changeNoteType(
      id,
      Marker.NoteTypes.Basic,
      Vector(Marker.BasicFields.Front -> "Term"),
      NonEmptyVector.one(TagCodec.encode(k)),
      Vector.empty,
    )

    assert(outcome.isRight, s"the fake reported an error Anki does not report: $outcome")
    assertEquals(
      noteAt(anki, id).fields,
      Vector(
        Marker.BasicFields.Front -> "Term",
        Marker.BasicFields.Back  -> "",
        Marker.ContextField      -> "",
        Marker.SameShapeField    -> "",
      ),
      "the fake did not reproduce the blank-then-fill behaviour, so nothing here would catch it",
    )
  }

  /** THE REASON THE ACTION CARRIES THE WHOLE TAG SET. Anki replaces the tags unconditionally,
    * so a tag that is not passed is destroyed — and the one that matters most is `src::`,
    * without which the note becomes unenumerable: no later run could find it to repair it.
    */
  test("a move replaces the tag set outright — anything not passed is gone") {
    val anki = collectionWith(stockBasic)
    val id   = seedOnOldType(anki, "Basic", k, Vector("Front" -> "f", "Back" -> "b"))
    assert(tagsOf(anki, id).contains("leech"), "the fixture did not set up a foreign tag")

    anki
      .changeNoteType(
        id,
        Marker.NoteTypes.Basic,
        Vector(Marker.BasicFields.Front -> "f"),
        NonEmptyVector.one(TagCodec.encode(k)),
        preservedTags = Vector.empty,
      )
      .fold(e => fail(s"$e"), identity)

    assertEquals(
      tagsOf(anki, id),
      Vector(TagCodec.encode(k).value),
      "the fake merged tags instead of replacing them, which is not what Anki does",
    )
  }

  /** The move must still refuse a note type that is not there, rather than inventing one.
    * `sync` will not reach this — its preflight refuses the whole run when one of this tool's
    * note types is missing — but the algebra is used by more than `sync`.
    */
  test("a move onto a note type the collection does not have is refused") {
    val anki = collectionWith(stockBasic)
    val id   = seedOnOldType(anki, "Basic", k, Vector("Front" -> "f", "Back" -> "b"))

    assertEquals(
      anki.changeNoteType(
        id,
        "No Such Note Type",
        Vector("Front" -> "f"),
        NonEmptyVector.one(TagCodec.encode(k)),
        Vector.empty,
      ),
      Left(AnkiError.NoSuchNoteType("No Such Note Type")),
    )
    assertEquals(noteAt(anki, id).noteType, "Basic", "a refused move changed the note anyway")
  }

  // ============ which actions a policy carries out, asked of the sum ====

  /** ==Why this is on `SyncAction` and not in `Executor`==
    *
    * `Executor.run` used to split the plan with
    * `plan.actions.filter { case _: Retype => false; case _ => true }` — a catch-all that
    * sweeps every action it has not heard of INTO execution. `-Wconf:msg=exhaustive:e` makes an
    * inexhaustive MATCH a build error, but a catch-all opts out of that check as completely as
    * an `if` would, while looking like it did not.
    *
    * The case it would swallow is not hypothetical. `README.md` names `prune` — the command
    * that DELETES flagged cards — as the next thing to be built. Under the old split a `Prune`
    * action would have been handed to the executor by a run whose whole contract is "do not act
    * on an instruction you were not given".
    *
    * These tests pin the dispositions. The compiler pins the exhaustiveness: a sixth case does
    * not compile until `dispositionUnder` answers for it.
    */
  private val someKey = key("n1", "Coupling")
  private val someNoteId = AnkiNoteId(1L)

  /** The four actions no policy may switch off, plus the one it may. Hand-built rather than
    * planned, because what is under test is the DISPOSITION and not how an action is produced.
    */
  private val alwaysAttempted: Vector[(String, SyncAction)] = Vector(
    "create" -> SyncAction.Create(
      someKey,
      NewNote(
        noteType = Marker.NoteTypes.Basic,
        deck = defaultDeck,
        fields = Vector("Front" -> "f", "Back" -> "b"),
        tags = NonEmptyVector.of(TagCodec.encode(someKey), OwnedTag.sha("sha::a")),
      ),
    ),
    "update" -> SyncAction.Update(
      someKey,
      someNoteId,
      NonEmptyVector.one(Change.FieldsChanged(Vector("Front" -> "f"), "sha::a")),
    ),
    "flag"   -> SyncAction.Flag(someKey, someNoteId),
    "unflag" -> SyncAction.Unflag(someKey, someNoteId),
  )

  private val aRetype: SyncAction = SyncAction.Retype(
    key = someKey,
    noteId = someNoteId,
    from = Marker.NoteTypes.Basic,
    to = Marker.NoteTypes.BasicAndReversed,
    fields = Vector("Front" -> "f", "Back" -> "b"),
    ownedTags = NonEmptyVector.of(TagCodec.encode(someKey), OwnedTag.sha("sha::a")),
    preservedTags = Vector.empty,
    deck = None,
  )

  test("under Defer, only a retype is set aside") {
    assertEquals(
      aRetype.dispositionUnder(RetypePolicy.Defer),
      Disposition.Defer,
      "a retype must not be attempted by a run that was not asked to migrate",
    )
    alwaysAttempted.foreach { (kind, action) =>
      assertEquals(
        action.dispositionUnder(RetypePolicy.Defer),
        Disposition.Attempt,
        s"deferring retypes must not stop a $kind — 'every other action runs either way'",
      )
    }
  }

  test("under Apply, every action is attempted, retypes included") {
    assertEquals(aRetype.dispositionUnder(RetypePolicy.Apply), Disposition.Attempt)
    alwaysAttempted.foreach { (_, action) =>
      assertEquals(action.dispositionUnder(RetypePolicy.Apply), Disposition.Attempt)
    }
  }

  /** THE POLICY ONLY EVER SWITCHES ONE THING. Stated as its own property because
    * `Executor.run`'s docstring promises it in prose — "every other action runs either way" —
    * and prose is not checked.
    */
  test("the policy changes the disposition of retypes and of nothing else") {
    alwaysAttempted.foreach { (kind, action) =>
      assertEquals(
        action.dispositionUnder(RetypePolicy.Defer),
        action.dispositionUnder(RetypePolicy.Apply),
        s"the policy changed what happens to a $kind",
      )
    }
    assertNotEquals(
      aRetype.dispositionUnder(RetypePolicy.Defer),
      aRetype.dispositionUnder(RetypePolicy.Apply),
    )
  }
