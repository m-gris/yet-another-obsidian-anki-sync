package obsidiananki.anki

import cats.data.NonEmptyVector
import obsidiananki.model.{Marker, OwnedTag}

/** Installing the tool's note types, against the FAKE collection.
  *
  * ASSERTIONS ARE ON OUTCOMES, never on which calls were made: what note types the collection
  * ends up holding, what fields they declare, whether a note can then be written. A test that
  * checked "createModel was called" would pass against an installer that called it with the
  * wrong arguments.
  *
  * THE FRESH-PROFILE CASE IS THE REASON THIS SLICE EXISTS. `InMemoryAnki(noteTypes =
  * Map.empty)` is a profile with none of this tool's note types in it, which is what a new Anki
  * profile actually looks like. Before `createNoteType` existed the tool could not get from
  * there to a collection it could write to at all — the first `addNote` was refused, and that
  * was the whole gap.
  */
class NoteTypeInstallTest extends munit.FunSuite:

  type Result[A] = Either[AnkiError, A]

  val assets: Vector[NoteTypeAsset] =
    NoteTypeAssets.all.fold(errors => fail(errors.map(_.describe).mkString("; ")), identity)

  def assetNamed(name: String): NoteTypeAsset =
    assets.find(_.spec.name == name).getOrElse(fail(s"no asset for '$name'"))

  /** A note type that is NOT one of ours: enough shape to occupy a name in the collection.
    *
    * Used to stand in for the two note types that already exist under their OLD names and hold
    * real notes with real review history. Their contents do not matter to any assertion here;
    * their NAMES are the whole point.
    */
  def foreignNoteType(name: String): NoteTypeSpec =
    NoteTypeSpec(
      name = name,
      isCloze = false,
      fields = NonEmptyVector.of("Concept", "Descriptor", "Description", "ThreeWay"),
      templates = NonEmptyVector.of("Card 1" -> CardTemplate("{{Concept}}", "{{Descriptor}}")),
      styling = ".card { font-family: arial; }",
    )

  def freshProfile(): InMemoryAnki = InMemoryAnki(noteTypes = Map.empty)

  def installInto(anki: InMemoryAnki): InstallOutcome =
    NoteTypeInstaller.install[Result](anki, assets).fold(e => fail(s"install failed: $e"), identity)

  def namesIn(anki: InMemoryAnki): Vector[String] =
    anki.noteTypeNames.fold(e => fail(s"$e"), identity)

  // -------------------------------------------------- a fresh profile ----

  test("a fresh profile gets all five note types, with the fields the manifests declare") {
    val anki    = freshProfile()
    val outcome = installInto(anki)

    assertEquals(outcome.created.toSet, Marker.NoteTypes.All.toSet)
    assertEquals(outcome.failures, Vector.empty)
    assert(outcome.isClean, s"a fresh install reported problems: $outcome")
    assertEquals(namesIn(anki).sorted, Marker.NoteTypes.All.sorted)

    assets.foreach { asset =>
      assertEquals(
        anki.fieldNames(asset.spec.name),
        Right(asset.spec.fields.toVector),
        s"'${asset.spec.name}' was installed with different fields from its manifest",
      )
      assertEquals(anki.noteTypeStyling(asset.spec.name), Right(asset.spec.styling))
      assertEquals(
        anki.noteTypeTemplates(asset.spec.name),
        Right(asset.spec.templates.toVector.toMap),
      )
    }
  }

  /** THE GAP THIS SLICE CLOSES, stated as a behaviour rather than as a count of note types: a
    * write that a fresh profile refuses must succeed once the install has run.
    */
  test("a note this tool writes is REFUSED before the install and accepted after it") {
    val note = NewNote(
      noteType = Marker.NoteTypes.Basic,
      deck = DeckPath(NonEmptyVector.of("Obsidian")),
      fields = Vector("Front" -> "Q", "Back" -> "A", Marker.ContextField -> "A > B"),
      tags = NonEmptyVector.one(OwnedTag.unsafeFromString("src::n1::a")),
    )

    val before = freshProfile()
    assertEquals(before.addNote(note), Left(AnkiError.NoSuchNoteType(Marker.NoteTypes.Basic)))

    val after = freshProfile()
    installInto(after)
    assert(after.addNote(note).isRight, "the note was still refused after installing the types")
  }

  test("a second install on the same collection creates nothing and reports nothing wrong") {
    val anki = freshProfile()
    installInto(anki)

    val again = installInto(anki)
    assertEquals(again.created, Vector.empty)
    assertEquals(again.failures, Vector.empty)
    assert(again.isClean, s"a re-install reported problems: $again")
    assert(
      again.before.forall {
        case NoteTypeStatus.Present(_, drift) => drift.isEmpty
        case other                            => fail(s"expected every type present, got $other")
      }
    )
  }

  // -------------------------------------------------- the rename hazard ----

  /** THE FAILURE WITH NO ERROR ANYWHERE, and the reason `renamedFrom` is in the manifests.
    *
    * `Cloze Sequence` and `3 way Concept-Descriptor` hold real notes with real review history,
    * and AnkiConnect cannot rename a model — that is done by hand in Tools > Manage Note Types.
    * A create-if-missing installer run first would leave TWO note types where one was wanted:
    * a new empty one and the old populated one, every note still on the old, nothing reported.
    */
  test("an install is REFUSED ENTIRELY while a note type is still awaiting its hand-rename") {
    val anki = InMemoryAnki(noteTypes =
      Map(
        "Cloze Sequence"            -> foreignNoteType("Cloze Sequence"),
        "3 way Concept-Descriptor"  -> foreignNoteType("3 way Concept-Descriptor"),
      )
    )
    val outcome = installInto(anki)

    assertEquals(
      outcome.blockedByRename.toSet,
      Set(
        "Cloze Sequence"           -> "Obsidian Cloze Sequence",
        "3 way Concept-Descriptor" -> "Obsidian Concept-Descriptor",
      ),
    )
    assertEquals(outcome.created, Vector.empty, "note types were created despite the refusal")

    // NOTHING AT ALL, not merely nothing of the two blocked ones. The three that could have
    // been created were not, so the collection is exactly as it was found.
    assertEquals(
      namesIn(anki).sorted,
      Vector("3 way Concept-Descriptor", "Cloze Sequence"),
    )
  }

  /** A `renamedFrom` naming a note type the collection does not hold is just history — the type
    * is simply absent and can be created.
    */
  test("a renamed-from name that is not in the collection does not block anything") {
    val anki    = freshProfile()
    val outcome = installInto(anki)
    assertEquals(outcome.blockedByRename, Vector.empty)
    assert(outcome.created.contains("Obsidian Cloze Sequence"))
  }

  // -------------------------------------------------- drift is reported, never repaired ----

  /** THE SAFE DEFAULT, AS A TEST: a note type a person may have edited is reported and left
    * alone. Every kind of difference is exercised at once, and the collection is compared
    * before and after to prove nothing was written.
    */
  test("a note type that differs from the repository is REPORTED and left exactly as it is") {
    val basic = assetNamed(Marker.NoteTypes.Basic)
    val edited = basic.spec.copy(
      fields = NonEmptyVector.of("Front", "Back"),
      styling = ".card { color: rebeccapurple; }",
      templates = NonEmptyVector.of("Card 1" -> CardTemplate("{{Front}} EDITED", "{{Back}}")),
    )
    val anki = InMemoryAnki(noteTypes = Map(edited.name -> edited))

    val outcome = installInto(anki)

    val drift = outcome.before.collectFirst {
      case NoteTypeStatus.Present(asset, d) if asset.spec.name == Marker.NoteTypes.Basic => d
    }.getOrElse(fail("Obsidian Basic was not reported as present"))

    assert(drift.contains(NoteTypeDrift.StylingDiffers), s"styling drift not reported: $drift")
    assert(
      drift.contains(NoteTypeDrift.TemplateSideDiffers("Card 1", TemplateSide.Front)),
      s"template drift not reported: $drift",
    )
    assert(
      drift.exists { case _: NoteTypeDrift.FieldsDiffer => true; case _ => false },
      s"field drift not reported: $drift",
    )
    assert(!outcome.isClean, "a drifting note type was reported as clean")

    // NOT REPAIRED. This is the assertion the whole ruling rests on.
    assertEquals(anki.noteTypeStyling(edited.name), Right(edited.styling))
    assertEquals(anki.fieldNames(edited.name), Right(Vector("Front", "Back")))
    assertEquals(
      anki.noteTypeTemplates(edited.name),
      Right(Map("Card 1" -> CardTemplate("{{Front}} EDITED", "{{Back}}"))),
    )

    // The four that were absent were still created: one drifting type does not block the rest.
    assertEquals(outcome.created.size, 4)
  }

  /** A template the repository does not know about cannot be repaired even in principle:
    * `updateModelTemplates` looks templates up by name and ignores names it does not recognise.
    * So the NAME SET is compared, and a difference is reported in its own right.
    */
  test("a template name that exists on only one side is reported as a name difference") {
    val basic = assetNamed(Marker.NoteTypes.Basic)
    val extra = basic.spec.copy(
      templates = basic.spec.templates :+ ("Card 2" -> CardTemplate("{{Back}}", "{{Front}}"))
    )
    val drift = NoteTypeInstaller.driftBetween(
      basic.spec,
      extra.fields.toVector,
      extra.templates.toVector.toMap,
      basic.spec.styling,
      basic.spec.isCloze,
    )
    assertEquals(
      drift,
      Vector(NoteTypeDrift.TemplateNamesDiffer(Vector("Card 1"), Vector("Card 1", "Card 2"))),
    )
  }

  test("an identical note type produces no drift at all") {
    val basic = assetNamed(Marker.NoteTypes.Basic)
    assertEquals(
      NoteTypeInstaller.driftBetween(
        basic.spec,
        basic.spec.fields.toVector,
        basic.spec.templates.toVector.toMap,
        basic.spec.styling,
        basic.spec.isCloze,
      ),
      Vector.empty,
    )
  }

  // -------------------------------------------------- the kind nobody was comparing ----

  /** THE ONE PROPERTY THE SURVEY SENT AND NEVER READ BACK.
    *
    * `isCloze` is declared in every manifest and handed to `createModel`, and until now the
    * comparison that decides whether a collection agrees with this repository looked at fields,
    * template names, template bodies and the stylesheet — and not at the KIND. So a note type
    * whose kind differed from the declaration passed the drift check in silence.
    *
    * THE FIXTURE IS THE FAILURE THAT MATTERS, not an arbitrary flip. `Obsidian Cloze` is the one
    * type this repository declares CLOZE, and the content the extractor authors for it is
    * `{{c1::…}}` text. Standing as a STANDARD type it accepts that text without complaint, stores
    * it verbatim, and generates one card showing the braces — a plausible wrong answer with no
    * error anywhere.
    */
  def clozeStandingAsStandard(): InMemoryAnki =
    val declared = assetNamed(Marker.NoteTypes.Cloze).spec
    InMemoryAnki(noteTypes = Map(declared.name -> declared.copy(isCloze = false)))

  test("a note type whose KIND differs from the repository is reported as a difference") {
    // Driven through the fake rather than through `driftBetween` directly, because the claim is
    // that the live kind is RE-READ. A pure test over a value handed in would pass against a
    // survey that never asked the collection.
    val outcome = installInto(clozeStandingAsStandard())

    val drift = outcome.before.collectFirst {
      case NoteTypeStatus.Present(asset, d) if asset.spec.name == Marker.NoteTypes.Cloze => d
    }.getOrElse(fail("Obsidian Cloze was not reported as present"))

    assert(
      drift.nonEmpty,
      "a note type standing as the wrong KIND reported no difference at all",
    )
  }

  test("a KIND difference keeps an install from reporting itself clean") {
    assert(
      !installInto(clozeStandingAsStandard()).isClean,
      "a collection whose note type is the wrong kind reported itself clean",
    )
  }

  /** THE MESSAGE IS THE HALF THAT DECIDES WHETHER THIS IS USEFUL OR MERELY NOISY.
    *
    * No AnkiConnect action changes a model's kind, so this difference stands on every run until
    * a person acts in Anki. `InstallOutcome.isClean` goes false on any difference, which is
    * correct — the collection and this repository disagree about something load-bearing — but it
    * means the sentence has to carry its own remedy, or the run reports a permanent complaint
    * nobody can answer.
    *
    * IT MUST ALSO SAY WHAT IT MEANS FOR A RETYPE, because that is the decision downstream of it:
    * `plan/Retyping.scala` admits a move only when both note types are the same kind, and it
    * reads both kinds LIVE — so a collection whose kind is not the declared one silently moves
    * that gate, admitting or refusing moves on a premise this repository did not author.
    */
  test("the KIND difference says it cannot be repaired here, and what it means for a retype") {
    val drift = installInto(clozeStandingAsStandard()).before.collectFirst {
      case NoteTypeStatus.Present(asset, d) if asset.spec.name == Marker.NoteTypes.Cloze => d
    }.getOrElse(fail("Obsidian Cloze was not reported as present")).map(_.describe).mkString("\n")

    assert(drift.contains("cloze"), s"the declared kind is not named:\n$drift")
    assert(drift.contains("standard"), s"the collection's kind is not named:\n$drift")
    assert(
      drift.contains("cannot") && drift.contains("repair"),
      s"the message does not say the tool cannot repair this:\n$drift",
    )
    assert(drift.contains("retype"), s"the message says nothing about a retype:\n$drift")
  }

  /** REFUSING THE WHOLE NOTE TYPE, not merely declining to change its kind.
    *
    * The repository's templates for a cloze type are WRITTEN FOR ONE — `{{cloze:Text}}` — so
    * writing them onto a standard type would report success and produce cards that render the
    * wrong thing. That is the same argument that already refuses a note type whose template
    * NAMES differ: a repair whose foundation is wrong reports success either way, and the only
    * place that failure is ever visible is before the call.
    */
  test("a KIND difference refuses the whole note type rather than repairing round it") {
    // The stylesheet differs too, so there is something a repair could otherwise reach for.
    val declared   = assetNamed(Marker.NoteTypes.Cloze).spec
    val collection = InMemoryAnki(noteTypes =
      Map(declared.name -> declared.copy(isCloze = false, styling = "/* edited by a person */"))
    )

    val statuses = NoteTypeInstaller.survey[Result](collection, assets)
      .fold(e => fail(s"survey failed: $e"), identity)
    val plan = NoteTypeInstaller.planRepair(statuses)

    assertEquals(
      plan.actions.filter(_.noteType == Marker.NoteTypes.Cloze),
      Vector.empty,
      s"a repair was planned for a note type of the wrong kind: ${plan.actions.map(_.describe)}",
    )
    assertEquals(
      plan.refusals.map(_.noteType),
      Vector(Marker.NoteTypes.Cloze),
      s"the wrong-kind note type was not refused: ${plan.refusals}",
    )
  }

  /** BOTH DIRECTIONS, because only one of them is the direction anybody would guess.
    *
    * `Obsidian Cloze Sequence` is the note type every heuristic gets backwards — "Cloze" in its
    * name, `.cloze` in its stylesheet, and NOT a cloze type. A collection holding it as a cloze
    * type is the mistake a person makes by hand, and it must be reported just as loudly.
    */
  test("a note type standing as CLOZE where the repository declares standard is reported too") {
    val declared = assetNamed(Marker.NoteTypes.ClozeSequence).spec
    val anki = InMemoryAnki(noteTypes = Map(declared.name -> declared.copy(isCloze = true)))

    val drift = installInto(anki).before.collectFirst {
      case NoteTypeStatus.Present(asset, d) if asset.spec.name == Marker.NoteTypes.ClozeSequence => d
    }.getOrElse(fail("Obsidian Cloze Sequence was not reported as present"))

    assert(drift.nonEmpty, "a standard note type standing as cloze reported no difference")
  }

  /** Anki's own constraint: `createModel` is not an upsert. Modelled so that a caller which
    * skips the survey fails rather than appearing to repair something.
    */
  test("creating a note type whose name already exists is refused") {
    val anki  = freshProfile()
    val basic = assetNamed(Marker.NoteTypes.Basic).spec
    assertEquals(anki.createNoteType(basic), Right(()))
    assertEquals(anki.createNoteType(basic), Left(AnkiError.NoteTypeExists(basic.name)))
  }

  // -------------------------------------------------- the sync preflight ----

  def readinessOf(anki: InMemoryAnki): Vector[NoteTypeProblem] =
    NoteTypeInstaller.readiness[Result](anki, assets).fold(e => fail(s"$e"), identity)

  test("a fresh profile is NOT ready, and every missing note type is named") {
    assertEquals(
      readinessOf(freshProfile()).toSet,
      Marker.NoteTypes.All.map(NoteTypeProblem.Missing(_, None)).toSet,
    )
  }

  test("an installed collection is ready") {
    val anki = freshProfile()
    installInto(anki)
    assertEquals(readinessOf(anki), Vector.empty)
  }

  /** "Install it" and "rename it" are different remedies, and doing the first when the second
    * was needed is how a populated note type gets stranded beside an empty duplicate. The
    * preflight therefore says which.
    */
  test("a note type still under its old name is reported as awaiting a rename, not as missing") {
    val anki = InMemoryAnki(noteTypes = Map("Cloze Sequence" -> foreignNoteType("Cloze Sequence")))
    assert(
      readinessOf(anki).contains(
        NoteTypeProblem.Missing("Obsidian Cloze Sequence", Some("Cloze Sequence"))
      ),
      s"the old name was not carried into the report: ${readinessOf(anki)}",
    )
  }

  /** THE SILENT ONE, AND THE REASON THE PREFLIGHT LOOKS AT FIELDS AT ALL.
    *
    * Verified live, read-only, on 2026-08-21 in profile `claude-POC-test`: `modelFieldNames`
    * for `Cloze Sequence` answers `[Title, Text]` and for `3 way Concept-Descriptor`
    * `[Concept, Descriptor, Description, ThreeWay]`. NEITHER HAS `Context`. So the hand-rename
    * alone does not make those two writable — the field has to be added as well — and without
    * this check the tool would write `Context` to them and Anki would store nothing while
    * reporting no error on update at all.
    */
  test("a note type present but missing a field this tool writes is NOT ready") {
    val sequence = assetNamed(Marker.NoteTypes.ClozeSequence).spec
    val withoutContext = sequence.copy(fields = NonEmptyVector.of("Title", "Text"))
    val anki = InMemoryAnki(noteTypes = Map(withoutContext.name -> withoutContext))

    assert(
      readinessOf(anki).contains(
        NoteTypeProblem.FieldsMissing(
          Marker.NoteTypes.ClozeSequence,
          // BOTH FIELDS THE FIXTURE OMITS. `Reveal` joined the note type on 2026-08-28; this
          // asserts the readiness check names EVERY missing field rather than the first, which
          // is what makes the run's message actionable in one pass.
          // EVERY field the fixture omits, not the first. `Identity` joined on 2026-08-28.
          NonEmptyVector.of(Marker.ContextField, Marker.RevealField, Marker.IdentityField),
        )
      ),
      s"a missing Context field did not stop the run: ${readinessOf(anki)}",
    )
  }

  /** A field the collection has and this tool does not write is NOT a problem: the tool writes
    * fields by name and leaves anything else alone. Refusing here would refuse a collection a
    * person had legitimately extended.
    */
  test("an EXTRA field in the collection does not stop a sync") {
    val basic = assetNamed(Marker.NoteTypes.Basic).spec
    val extended = basic.copy(fields = basic.fields :+ "Notes To Self")
    val anki = InMemoryAnki(noteTypes = Map(extended.name -> extended))
    assert(
      !readinessOf(anki).exists {
        case NoteTypeProblem.FieldsMissing(name, _) => name == basic.name
        case _                                      => false
      },
      "an extra field was treated as a missing one",
    )
  }
