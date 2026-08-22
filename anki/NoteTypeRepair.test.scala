package obsidiananki.anki

import cats.data.NonEmptyVector
import obsidiananki.model.Marker

/** Repairing a note type that ALREADY EXISTS, against the FAKE collection.
  *
  * ==Why this exists==
  *
  * The installer creates what is absent and leaves what is present alone. That rule, applied on
  * its own, produced the defect this suite guards: the two note types this tool inherited by
  * hand-rename were PRESENT, so they were never touched, so they kept the collection's own
  * templates — which mention no `Context` field. The field was computed, written, hashed and
  * synced onto 21 of 43 live notes AND RENDERED NOWHERE. Measured against profile
  * `claude-POC-test` on 2026-08-21, not imagined.
  *
  * ==The trap every test below is shaped around==
  *
  * AnkiConnect's `updateModelTemplates` resolves each template BY NAME and silently ignores a
  * name it does not recognise (`__init__.py:1301-1303`, read on this machine), and
  * `modelFieldAdd` silently does nothing when the field is already there (`:1437-1441`). Both
  * answer `null` — success — either way. So "the call returned without an error" says NOTHING
  * about whether the collection changed, which is why [[NoteTypeInstaller.repair]] re-reads and
  * why [[InMemoryAnki]] reproduces the silence rather than being kinder than Anki.
  *
  * ASSERTIONS ARE ON OUTCOMES: what the collection holds afterwards, and what a fresh survey
  * says about it. Never on which calls were made.
  */
class NoteTypeRepairTest extends munit.FunSuite:

  type Result[A] = Either[AnkiError, A]

  val assets: Vector[NoteTypeAsset] =
    NoteTypeAssets.all.fold(errors => fail(errors.map(_.describe).mkString("; ")), identity)

  def assetNamed(name: String): NoteTypeAsset =
    assets.find(_.spec.name == name).getOrElse(fail(s"no asset for '$name'"))

  /** The state that produced the live defect: the note type is present under the RIGHT name and
    * holds real notes, but its shape is the one the collection had before this tool knew about
    * it — no `Context` field, and templates that never mention one.
    */
  def asInheritedByRename(name: String): NoteTypeSpec =
    val ours = assetNamed(name).spec
    ours.copy(
      // Both fields this tool derives are stripped, which is what a note type inherited by
      // hand-rename actually looks like: it predates them.
      fields = NonEmptyVector.fromVectorUnsafe(
        ours.fields.toVector.filterNot(f => f == Marker.ContextField || f == Marker.ConceptLabelField)
      ),
      templates = ours.templates.map { (templateName, template) =>
        templateName -> CardTemplate(
          front = template.front.replace("{{Context}}", "").replace("""<div class="context"></div>""", ""),
          back = template.back,
        )
      },
      styling = ".card { font-family: arial; font-size: 20px; text-align: center; }",
    )

  def collectionWith(specs: NoteTypeSpec*): InMemoryAnki =
    InMemoryAnki(noteTypes = specs.map(s => s.name -> s).toMap)

  def surveyOf(anki: InMemoryAnki): Vector[NoteTypeStatus] =
    NoteTypeInstaller.survey[Result](anki, assets).fold(e => fail(s"survey failed: $e"), identity)

  def repairOf(anki: InMemoryAnki): RepairOutcome =
    val plan = NoteTypeInstaller.planRepair(surveyOf(anki))
    NoteTypeInstaller
      .repair[Result](anki, assets, plan)
      .fold(e => fail(s"repair failed: $e"), identity)

  private val ConceptDescriptor = Marker.NoteTypes.ConceptDescriptor

  /** An interpreter that performs the first `writes` REPAIR writes and then refuses every
    * further one, modelling a run cut off midway — Anki quit, connection dropped.
    *
    * Reads pass through, so the collection can still be asked what landed. Only the three
    * repair operations are counted, because they are the only ones this suite drives.
    *
    * WHY A DECORATOR AND NOT A RECORDING SPY: the property under test is the ORDER two writes
    * go out in, and the honest way to observe that without asserting on calls is to stop after
    * the first one and ask the collection what changed. Same reason `ExecutorInterruption.test`
    * exists rather than a mock that counts invocations.
    */
  final class FailAfter(underlying: InMemoryAnki, writes: Int) extends Anki[Result]:
    private var done = 0

    private def write[A](op: => Result[A]): Result[A] =
      if done >= writes then
        Left(AnkiError.UnsupportedOperation("interrupted", s"fault injected after $writes writes"))
      else
        done += 1
        op

    def addNoteTypeField(noteType: String, field: String): Result[Unit] =
      write(underlying.addNoteTypeField(noteType, field))
    def setNoteTypeTemplates(noteType: String, templates: Map[String, CardTemplate]): Result[Unit] =
      write(underlying.setNoteTypeTemplates(noteType, templates))
    def setNoteTypeStyling(noteType: String, css: String): Result[Unit] =
      write(underlying.setNoteTypeStyling(noteType, css))

    export underlying.{
      noteTypeNames,
      fieldNames,
      noteTypeTemplates,
      noteTypeStyling,
      noteTypeIsCloze,
      createNoteType,
      findNotesByTagPrefix,
      notesInfo,
      addNote,
      updateNoteFields,
      changeNoteType,
      addTags,
      removeTags,
      cardsOf,
      deckOf,
      changeDeck,
      suspend,
      unsuspend,
    }

  // ══════════════════════════════════ planning, with no Anki at all ══════

  test("a present note type missing a field is planned an AddField for exactly that field") {
    val anki = collectionWith(asInheritedByRename(ConceptDescriptor))
    val plan = NoteTypeInstaller.planRepair(surveyOf(anki))

    val added = plan.actions.collect { case RepairAction.AddField(n, f) if n == ConceptDescriptor => f }
    // Both derived fields, in DECLARED order — which is also the order Anki will append them.
    assertEquals(
      added,
      Vector(Marker.ContextField, Marker.ConceptLabelField),
      s"plan was ${plan.actions.map(_.describe)}",
    )
    assertEquals(plan.refusals, Vector.empty)
  }

  test("a note type identical to the repository is planned nothing and reported unchanged") {
    val anki = collectionWith(assets.map(_.spec)*)
    val plan = NoteTypeInstaller.planRepair(surveyOf(anki))

    assert(plan.isEmpty, s"an identical collection was planned changes: ${plan.actions.map(_.describe)}")
    assertEquals(plan.unchanged.toSet, Marker.NoteTypes.All.toSet)
  }

  test("a note type ABSENT is not a repair — it is left to install") {
    val plan = NoteTypeInstaller.planRepair(surveyOf(InMemoryAnki(noteTypes = Map.empty)))
    assert(plan.isEmpty, s"repair planned work for absent types: ${plan.actions.map(_.describe)}")
    assertEquals(plan.refusals, Vector.empty)
  }

  test("FIELD ORDER ALONE is deliberately NOT repaired, and the drift is left standing") {
    // Reordering somebody's fields changes their Browse column list and nothing this tool
    // stores — it writes fields by name. Doing it uninvited is a cosmetic edit to a collection
    // nobody asked for, so the difference is reported and left.
    val ours     = assetNamed(ConceptDescriptor).spec
    val reversed = ours.copy(fields = NonEmptyVector.fromVectorUnsafe(ours.fields.toVector.reverse))
    val anki     = collectionWith(reversed)

    val plan = NoteTypeInstaller.planRepair(surveyOf(anki))
    assertEquals(plan.actions, Vector.empty, s"field order was 'repaired': ${plan.actions.map(_.describe)}")
    assert(plan.unchanged.contains(ConceptDescriptor))

    // And the drift is still reported, rather than quietly forgotten.
    val drift = surveyOf(anki).collect { case NoteTypeStatus.Present(a, d) if a.spec.name == ConceptDescriptor => d }
    assert(drift.flatten.exists(_.isInstanceOf[NoteTypeDrift.FieldsDiffer]), s"drift vanished: $drift")
  }

  test("a field the collection has and the repository does not is NEVER removed") {
    // Removing a field DELETES ITS CONTENT from every note of that type. Nothing in a repair
    // may do that, however tidy it would make the comparison look.
    val ours   = assetNamed(ConceptDescriptor).spec
    val extra  = ours.copy(fields = ours.fields :+ "SomebodyElsesField")
    val anki   = collectionWith(extra)

    val plan = NoteTypeInstaller.planRepair(surveyOf(anki))
    assert(
      !plan.actions.exists(_.describe.contains("SomebodyElsesField")),
      s"a repair proposed touching a foreign field: ${plan.actions.map(_.describe)}",
    )
    repairOf(anki)
    val after = anki.fieldNames(ConceptDescriptor).fold(e => fail(s"$e"), identity)
    assert(after.contains("SomebodyElsesField"), s"a foreign field was removed: $after")
  }

  // ══════════════════════════════ the refusal that prevents a silent no-op ══

  test("TEMPLATE NAMES DIFFERING refuses the whole note type rather than calling an API that lies") {
    // `updateModelTemplates` would return success having changed nothing. Refusing in advance is
    // the only way that failure is ever visible, so the refusal must cover the note type's OTHER
    // drift too — the styling here differs as well and must still not be written.
    val ours = assetNamed(ConceptDescriptor).spec
    val renamedTemplates = ours.copy(
      templates = ours.templates.map((n, t) => s"$n (renamed by hand)" -> t),
      styling = "/* edited by a person */",
    )
    val anki = collectionWith(renamedTemplates)

    val plan = NoteTypeInstaller.planRepair(surveyOf(anki))
    assertEquals(plan.actions, Vector.empty, s"actions planned despite unmatched names: ${plan.actions.map(_.describe)}")
    assertEquals(plan.refusals.map(_.noteType), Vector(ConceptDescriptor))

    val outcome = repairOf(anki)
    assert(!outcome.isClean, "a run that refused a note type reported itself clean")
    assertEquals(
      anki.noteTypeStyling(ConceptDescriptor).fold(e => fail(s"$e"), identity),
      "/* edited by a person */",
      "a refused note type had its stylesheet overwritten anyway",
    )
  }

  test("the fake reproduces Anki's silent skip, so a test cannot prove a repair that would not happen") {
    // Pinning the FAKE, not the installer: if this ever starts inserting or refusing, every
    // test above becomes evidence about a collection that does not exist.
    val ours = assetNamed(ConceptDescriptor).spec
    val anki = collectionWith(ours)

    val result = anki.setNoteTypeTemplates(ConceptDescriptor, Map("no such template" -> CardTemplate("x", "y")))
    assertEquals(result, Right(()), "the fake reported a failure Anki does not report")
    assertEquals(
      anki.noteTypeTemplates(ConceptDescriptor).fold(e => fail(s"$e"), identity),
      ours.templates.toVector.toMap,
      "the fake applied a template Anki would have ignored",
    )
  }

  // ══════════════════════════════════════════════ carrying it out ══════

  test("repairing the inherited note type closes every difference, checked by RE-READING") {
    val anki    = collectionWith(asInheritedByRename(ConceptDescriptor))
    val outcome = repairOf(anki)

    assertEquals(outcome.failures, Vector.empty)
    assert(outcome.isClean, s"repair did not report clean: $outcome")

    // The claim that matters, made against the collection rather than against the outcome.
    val after = surveyOf(anki).collect {
      case NoteTypeStatus.Present(a, d) if a.spec.name == ConceptDescriptor => d
    }.flatten
    assertEquals(after, Vector.empty, s"drift survived the repair: ${after.map(_.describe)}")

    assertEquals(
      anki.noteTypeTemplates(ConceptDescriptor).fold(e => fail(s"$e"), identity),
      assetNamed(ConceptDescriptor).spec.templates.toVector.toMap,
    )
  }

  test("the Context field arrives BEFORE the templates that reference it") {
    // A template naming a field the note type does not have renders NOTHING. So if a run dies
    // between the two writes, the wrong order leaves a live card whose breadcrumb is blank.
    //
    // ASSERTED BY INTERRUPTING RATHER THAN BY INSPECTING THE PLAN, and the difference matters:
    // an earlier version of this test read the order off `plan.actions` and therefore pinned how
    // `planRepair` happens to concatenate its vectors, not the order the writes actually go out
    // in. Mutating the sort inside `repair` left that version green. Cutting the run off after
    // one write and asking the COLLECTION what landed cannot be fooled that way.
    val anki    = collectionWith(asInheritedByRename(ConceptDescriptor))
    val stopped = FailAfter(anki, writes = 1)
    val plan    = NoteTypeInstaller.planRepair(surveyOf(anki))

    NoteTypeInstaller.repair[Result](stopped, assets, plan).fold(e => fail(s"repair aborted: $e"), identity)

    assert(
      anki.fieldNames(ConceptDescriptor).fold(e => fail(s"$e"), identity).contains("Context"),
      "after one write the field was still missing, so something else went first",
    )
    assertEquals(
      anki.noteTypeTemplates(ConceptDescriptor).fold(e => fail(s"$e"), identity),
      asInheritedByRename(ConceptDescriptor).templates.toVector.toMap,
      "a template was written before the field it references",
    )
  }

  test("a difference the repair will NOT close keeps the run from reporting itself clean") {
    // The teeth of the re-read. This note type needs no action — the only difference is a field
    // of somebody else's, which a repair must never remove — so nothing is written and nothing
    // fails. `isClean` must still be false, because the collection and the repository still
    // disagree and no human has decided which is right.
    //
    // This is the case that catches a `repair` which reports drift from what it INTENDED rather
    // than from what it READ: with the re-read stubbed out, every assertion above still passes
    // and this one does not.
    val ours  = assetNamed(ConceptDescriptor).spec
    val anki  = collectionWith(ours.copy(fields = ours.fields :+ "SomebodyElsesField"))
    val plan  = NoteTypeInstaller.planRepair(surveyOf(anki))
    assertEquals(plan.actions, Vector.empty)

    val outcome = repairOf(anki)
    assertEquals(outcome.failures, Vector.empty)
    assertEquals(outcome.plan.refusals, Vector.empty)
    assert(
      outcome.remainingDrift.exists((n, d) => n == ConceptDescriptor && d.nonEmpty),
      s"the surviving difference was not read back: ${outcome.remainingDrift}",
    )
    assert(!outcome.isClean, "a collection that still differs from the repository reported clean")
  }

  test("adding a field that is already declared changes nothing and does not duplicate it") {
    // Anki's own `modelFieldAdd` is guarded by `if fieldName not in fieldMap`, so this is a
    // property of the real API that the fake must share. Exercised DIRECTLY because no repair
    // plan ever asks for a field that is already present — which is exactly how a fake that
    // appended unconditionally stayed green through every other test in this file.
    val ours   = assetNamed(ConceptDescriptor).spec
    val anki   = collectionWith(ours)
    val before = anki.fieldNames(ConceptDescriptor).fold(e => fail(s"$e"), identity)

    assertEquals(anki.addNoteTypeField(ConceptDescriptor, "Context"), Right(()))

    assertEquals(
      anki.fieldNames(ConceptDescriptor).fold(e => fail(s"$e"), identity),
      before,
      "adding an existing field changed the field list",
    )
  }

  test("a repair is idempotent — running it twice changes nothing the second time") {
    val anki = collectionWith(asInheritedByRename(ConceptDescriptor))
    repairOf(anki)

    val second = NoteTypeInstaller.planRepair(surveyOf(anki))
    assert(second.isEmpty, s"a second repair still wanted work: ${second.actions.map(_.describe)}")
  }

  test("a repair aimed at a note type that vanished mid-run fails by NAME, not opaquely") {
    val anki = collectionWith(asInheritedByRename(ConceptDescriptor))
    val plan = NoteTypeInstaller.planRepair(surveyOf(anki))

    val gone = InMemoryAnki(noteTypes = Map.empty)
    val outcome =
      NoteTypeInstaller.repair[Result](gone, assets, plan).fold(e => fail(s"repair aborted: $e"), identity)

    assert(outcome.failures.nonEmpty, "a repair against a collection missing the type reported success")
    assert(
      outcome.failures.exists((_, e) => e == AnkiError.NoSuchNoteType(ConceptDescriptor)),
      s"failure did not name the note type: ${outcome.failures}",
    )
  }
