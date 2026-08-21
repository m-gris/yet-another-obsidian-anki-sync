package obsidiananki.anki

import cats.MonadError
import cats.data.NonEmptyVector
import cats.syntax.all.*

/** Getting this tool's five note types into a collection, and refusing to write to one that
  * does not have them.
  *
  * THE SAFE DEFAULT, RULED HERE AND STATED ONCE: **nothing this tool did not create is ever
  * silently overwritten.** A note type that is absent is CREATED; a note type that is present
  * and differs from the repository is REPORTED and left exactly as it is.
  *
  * THERE IS NOW A REPAIR PATH, AND IT IS OPT-IN. Amended 2026-08-21. This comment used to say
  * there was none "not even behind a flag", and treated that as the conservative position. It
  * was not conservative, it was silently lossy: the two note types this tool inherited by
  * hand-rename are PRESENT, so they were never touched, so they kept the collection's own
  * templates — which mention no `Context` field. That field was then computed, written, hashed
  * and synced onto 21 of 43 live notes and rendered NOWHERE. A refusal is only safe when it is
  * VISIBLE, and this one produced a feature that existed everywhere except on the screen.
  *
  * The default is unchanged — [[NoteTypeInstaller.install]] still creates only what is absent.
  * [[NoteTypeInstaller.repair]] runs only when a human asks for it, reports what it will change
  * before changing it, and VERIFIES BY RE-READING afterwards rather than trusting that the write
  * landed. That last part is not belt-and-braces; see [[NoteTypeInstaller.repair]].
  *
  * TWO SEPARATE QUESTIONS LIVE IN THIS FILE, and they are deliberately not the same function:
  *
  *   - [[NoteTypeInstaller.survey]] — the full comparison, five note types against the
  *     repository, including template bodies and stylesheets. Read-only. It is what
  *     `install-note-types` reports, and it costs three extra requests per note type.
  *   - [[NoteTypeInstaller.readiness]] — the narrow question `sync` asks before it writes
  *     anything: does every note type exist, and does each declare every field this tool is
  *     about to write? Read-only, and only `modelNames` plus one `modelFieldNames` per type.
  *
  * A DIFFERENT TEMPLATE IS NOT A REASON TO REFUSE A SYNC, which is why the second is not just
  * the first with a filter. A person who improved a template has changed how a card LOOKS; a
  * missing field changes what Anki STORES, and does it silently — Anki reports a field name it
  * does not recognise as "cannot create note because it is empty" on create, and as no error at
  * all on update.
  */

/** Which side of a card template differs. */
enum TemplateSide:
  case Front
  case Back

/** ONE way a collection's copy of a note type differs from the repository's.
  *
  * Every case is a comparison of two strings that ought to be equal, never a re-derivation:
  * `resources/note-types/` holds the exact text that goes into a collection, so a difference is
  * a diff a human reads.
  */
enum NoteTypeDrift:
  /** The field list differs — in membership, in order, or both.
    *
    * ORDER COUNTS AS A DIFFERENCE HERE and does not stop a sync. Anki's Sort Field defaults to
    * field 1, so a reordering fills the Browse list with a different column; that is worth
    * reporting and is not worth refusing to write over.
    */
  case FieldsDiffer(declared: Vector[String], inCollection: Vector[String])

  /** The SET of template names differs.
    *
    * Compared as a set, not as a sequence, because what comes back from `modelTemplates` is a
    * JSON object and carries no order this tool is entitled to rely on. A name in one and not
    * the other is the important half anyway: AnkiConnect's `updateModelTemplates` looks
    * templates up by name and silently ignores names it does not recognise, so a template this
    * tool does not know about is one no repair could ever reach.
    */
  case TemplateNamesDiffer(declared: Vector[String], inCollection: Vector[String])

  /** A template present under the same name on both sides holds different text on one side. */
  case TemplateSideDiffers(templateName: String, side: TemplateSide)

  case StylingDiffers

  def describe: String = this match
    case FieldsDiffer(declared, inCollection) =>
      s"fields differ: repository has [${declared.mkString(", ")}], " +
        s"collection has [${inCollection.mkString(", ")}]"
    case TemplateNamesDiffer(declared, inCollection) =>
      s"template names differ: repository has [${declared.mkString(", ")}], " +
        s"collection has [${inCollection.mkString(", ")}]"
    case TemplateSideDiffers(templateName, side) =>
      s"template '$templateName': the ${side.toString.toLowerCase} side differs from the repository"
    case StylingDiffers =>
      "the stylesheet differs from the repository"

/** What the collection holds for ONE of this tool's note types. */
enum NoteTypeStatus:
  /** DECLARED ABSTRACT AND SATISFIED BY EACH CASE'S OWN `asset` PARAMETER. Every status is
    * about one note type definition from the repository, so the accessor exists on the sum
    * rather than only on each variant.
    */
  def asset: NoteTypeAsset
  /** Not in the collection under its own name, and its former name is not there either — so it
    * can simply be created.
    */
  case Absent(asset: NoteTypeAsset)

  /** Not in the collection under its own name, and the name it is being RENAMED FROM **is**.
    *
    * THIS IS WHY `renamedFrom` EXISTS AT ALL, and it guards a failure with no error anywhere.
    * Two of the five note types already hold notes with real review history and are being
    * renamed rather than recreated; AnkiConnect has no action that renames a model, so the
    * renames are done by hand in Anki's Tools → Manage Note Types. A naive create-if-missing
    * installer run before that hand-rename would leave the collection holding TWO note types —
    * a new empty one and the old populated one, every note still on the old — because
    * `createModel` refuses only when the NEW name already exists. Nothing would report it.
    *
    * ⚠️ THIS GUARD NARROWS THAT HAZARD; IT DOES NOT CLOSE IT, and the difference is a
    * misspelling. [[NoteTypeInstaller.statusOf]] reaches this case through
    * `asset.renamedFrom.filter(inCollection.contains)` — an EXACT string match. If the human
    * rename typed the new name wrong, the collection holds neither `spec.name` nor
    * `renamedFrom`: the type is classified [[Absent]], [[NoteTypeInstaller.install]] creates
    * every absent type, and the result is the empty duplicate beside the populated original
    * that this case exists to prevent — with the original now under a name no code here will
    * ever look for. Nothing compares names loosely and nothing warns. A misspelled rename was
    * reported during the live run of 2026-08-21; the collection no longer shows it
    * (`modelNames` on 2026-08-21 lists only the correctly spelled names), so the mechanism
    * above was read out of this file rather than reproduced.
    */
  case AwaitingManualRename(asset: NoteTypeAsset, currentName: String)

  /** In the collection, with whatever differences were found. Empty means identical. */
  case Present(asset: NoteTypeAsset, drift: Vector[NoteTypeDrift])

  def name: String = asset.spec.name

/** A reason `sync` must not write to this collection.
  *
  * NARROWER THAN [[NoteTypeDrift]] ON PURPOSE — see the note at the top of this file. Only two
  * things make a write unsafe rather than merely surprising.
  */
enum NoteTypeProblem:
  /** The note type is not there. Every `addNote` naming it would be refused.
    *
    * `awaitingRenameFrom` carries the old name when the collection still holds it, so the
    * message can say "rename it" rather than "install it" — two different remedies, and doing
    * the second when the first was needed is how a populated note type gets stranded beside an
    * empty duplicate.
    */
  case Missing(name: String, awaitingRenameFrom: Option[String])

  /** The note type exists and does not declare fields this tool writes.
    *
    * THE SILENT ONE. Anki reports an unrecognised field name on create as "cannot create note
    * because it is empty" — the same message a genuinely empty note produces — and on update as
    * no error whatsoever. So a note type missing `Context` would take an update happily and
    * store nothing, run after run.
    */
  case FieldsMissing(name: String, missing: NonEmptyVector[String])

  def describe: String = this match
    case Missing(name, None) =>
      s"'$name' is not in this collection"
    case Missing(name, Some(old)) =>
      s"'$name' is not in this collection, but '$old' is — that note type is meant to be " +
        "RENAMED to it by hand, not replaced"
    case FieldsMissing(name, missing) =>
      s"'$name' does not have the field(s) ${missing.toVector.map("'" + _ + "'").mkString(", ")}, " +
        "which this tool writes"

/** One change a repair will make to ONE note type. Each maps to exactly one algebra call.
  *
  * NOTHING HERE REMOVES OR REORDERS ANYTHING. Anki can delete a field and move a field, and this
  * tool does neither: a field it does not declare belongs to whoever added it, and field order
  * affects the Browse column list rather than anything stored. The only additive-vs-destructive
  * line that IS crossed is a template body and a stylesheet, which is exactly why a repair has
  * to be asked for.
  */
enum RepairAction:
  /** Declared abstract and satisfied by each case's own parameter, the same way
    * [[NoteTypeStatus.asset]] is: every action is about one note type, so the accessor belongs
    * on the sum rather than being re-derived by a match.
    */
  def noteType: String

  case AddField(noteType: String, field: String)
  case ReplaceTemplates(noteType: String, templateNames: Vector[String])
  case ReplaceStyling(noteType: String)

  def describe: String = this match
    case AddField(n, f)         => s"'$n': add the field '$f'"
    case ReplaceTemplates(n, t) => s"'$n': overwrite template(s) ${t.map("'" + _ + "'").mkString(", ")}"
    case ReplaceStyling(n)      => s"'$n': overwrite the stylesheet"

/** A note type whose difference from the repository a repair CANNOT close.
  *
  * Exactly one thing reaches this today, and it is the reason the type exists rather than being
  * a bare error string: the template NAMES differ. AnkiConnect's `updateModelTemplates` resolves
  * each template by name and skips names it does not recognise IN SILENCE, so attempting the
  * repair would return success having changed nothing. Refusing in advance is the only way that
  * failure is ever visible.
  */
final case class RepairRefused(noteType: String, reason: String)

/** What a repair would do, decided WITHOUT talking to Anki.
  *
  * PURE, and that is the point: every decision about what to overwrite is testable from a
  * `Vector[NoteTypeStatus]` alone, and the effectful half below does nothing but carry it out.
  */
final case class RepairPlan(
    actions: Vector[RepairAction],
    refusals: Vector[RepairRefused],
    unchanged: Vector[String],
):
  def isEmpty: Boolean = actions.isEmpty

/** What a repair actually did, and — the part that matters — what the collection looked like
  * afterwards when it was read back.
  */
final case class RepairOutcome(
    plan: RepairPlan,
    applied: Vector[RepairAction],
    failures: Vector[(RepairAction, AnkiError)],
    remainingDrift: Vector[(String, Vector[NoteTypeDrift])],
):
  /** Every action landed, nothing was refused, and RE-READING THE COLLECTION FOUND NO DIFFERENCE
    * LEFT. The third clause is the one with teeth — see [[NoteTypeInstaller.repair]].
    */
  def isClean: Boolean =
    failures.isEmpty && plan.refusals.isEmpty && remainingDrift.forall(_._2.isEmpty)

/** The result of an install run. */
final case class InstallOutcome(
    before: Vector[NoteTypeStatus],
    created: Vector[String],
    failures: Vector[(String, AnkiError)],
):
  /** The (old name, new name) pairs a human must rename in Anki before this can proceed.
    *
    * DERIVED FROM `before` RATHER THAN STORED BESIDE IT, so the report and the decision that
    * produced it cannot disagree.
    */
  def blockedByRename: Vector[(String, String)] =
    before.collect { case NoteTypeStatus.AwaitingManualRename(asset, current) =>
      current -> asset.spec.name
    }

  /** NOTHING WENT WRONG — which is not the same as nothing happened.
    *
    * A run that created four note types is clean; a run that found one already present and
    * differing from the repository is not, because the collection and this repository now
    * describe the same note type differently and nobody has decided which is right.
    */
  def isClean: Boolean =
    failures.isEmpty && blockedByRename.isEmpty && before.forall {
      case NoteTypeStatus.Present(_, drift) => drift.isEmpty
      case _                                => true
    }

object NoteTypeInstaller:

  /** Compare every note type in the repository with the collection. READS ONLY. */
  def survey[F[_]: cats.Monad](
      anki: Anki[F],
      assets: Vector[NoteTypeAsset],
  ): F[Vector[NoteTypeStatus]] =
    anki.noteTypeNames.flatMap { present =>
      val inCollection = present.toSet
      assets.traverse(asset => statusOf(anki, asset, inCollection))
    }

  private def statusOf[F[_]: cats.Monad](
      anki: Anki[F],
      asset: NoteTypeAsset,
      inCollection: Set[String],
  ): F[NoteTypeStatus] =
    val spec = asset.spec
    if !inCollection.contains(spec.name) then
      // The former name is only interesting when the collection actually still holds it. A
      // `renamedFrom` naming a note type nobody has is just history.
      asset.renamedFrom.filter(inCollection.contains) match
        case Some(current) => cats.Monad[F].pure(NoteTypeStatus.AwaitingManualRename(asset, current))
        case None          => cats.Monad[F].pure(NoteTypeStatus.Absent(asset))
    else
      for
        fields    <- anki.fieldNames(spec.name)
        templates <- anki.noteTypeTemplates(spec.name)
        styling   <- anki.noteTypeStyling(spec.name)
      yield NoteTypeStatus.Present(asset, driftBetween(spec, fields, templates, styling))

  /** PURE, so every branch is drivable without a collection at all. */
  def driftBetween(
      spec: NoteTypeSpec,
      fields: Vector[String],
      templates: Map[String, CardTemplate],
      styling: String,
  ): Vector[NoteTypeDrift] =
    val fieldDrift =
      if spec.fields.toVector == fields then Vector.empty
      else Vector(NoteTypeDrift.FieldsDiffer(spec.fields.toVector, fields))

    val declaredNames = spec.templates.toVector.map(_._1)
    val nameDrift =
      if declaredNames.toSet == templates.keySet then Vector.empty
      else
        Vector(
          NoteTypeDrift.TemplateNamesDiffer(declaredNames, templates.keys.toVector.sorted)
        )

    // Only templates present under the SAME NAME on both sides can have their text compared;
    // one that exists on only one side is already reported by `nameDrift`, and pairing it with
    // some other template by position would invent a correspondence Anki does not have.
    val bodyDrift = spec.templates.toVector.flatMap { (name, declared) =>
      templates.get(name).toVector.flatMap { inCollection =>
        Option.when(inCollection.front != declared.front)(
          NoteTypeDrift.TemplateSideDiffers(name, TemplateSide.Front)
        ) ++
          Option.when(inCollection.back != declared.back)(
            NoteTypeDrift.TemplateSideDiffers(name, TemplateSide.Back)
          )
      }
    }

    val stylingDrift =
      if styling == spec.styling then Vector.empty else Vector(NoteTypeDrift.StylingDiffers)

    fieldDrift ++ nameDrift ++ bodyDrift ++ stylingDrift

  /** Create every note type that is absent, and NOTHING else.
    *
    * IF ANY NOTE TYPE IS AWAITING A HAND-RENAME, NOTHING AT ALL IS CREATED. The run cannot
    * reach the state it is for, and a half-installed collection where `sync` still refuses is a
    * worse place to leave someone than an untouched one — with one message to act on rather
    * than two. This is the same posture `Planner` already takes towards a vault with colliding
    * identities: refuse the whole plan, name both sides, write nothing.
    *
    * PER-TYPE FAILURES ARE COLLECTED, NOT FATAL, exactly as `Executor` collects per-action
    * failures: one note type Anki refuses must not abandon the other four.
    */
  /** Decide what a repair would change, from a survey alone. NO EFFECTS, and no Anki.
    *
    * ONE DRIFT CASE IS DELIBERATELY NOT REPAIRED: a [[NoteTypeDrift.FieldsDiffer]] where every
    * declared field is already present and only the ORDER differs. Anki's field order decides
    * the Browse column list and nothing this tool stores — it writes fields by name — so
    * reordering somebody's fields to match a repository would be a cosmetic change to their
    * collection that they did not ask for. Such a note type therefore stays in `unchanged`, and
    * the surviving drift is reported rather than closed.
    *
    * A FIELD IN THE COLLECTION THAT THE REPOSITORY DOES NOT DECLARE IS NEVER REMOVED, for the
    * same reason and a stronger one: removing a field DELETES ITS CONTENT from every note of
    * that type.
    */
  def planRepair(statuses: Vector[NoteTypeStatus]): RepairPlan =
    val present = statuses.collect { case p @ NoteTypeStatus.Present(_, _) => p }

    val perType = present.map { case NoteTypeStatus.Present(asset, drift) =>
      val name = asset.spec.name

      // Refuse the WHOLE note type, not just its template action: with the names disagreeing
      // there is no way to know which repository template corresponds to which of the
      // collection's, so overwriting any of them is a guess.
      val namesDiffer = drift.collectFirst { case d: NoteTypeDrift.TemplateNamesDiffer => d }

      namesDiffer match
        case Some(d) =>
          (Vector.empty[RepairAction], Vector(RepairRefused(name, d.describe)), Vector.empty[String])

        case None =>
          val addFields = drift.collect { case NoteTypeDrift.FieldsDiffer(declared, inCollection) =>
            declared.filterNot(inCollection.contains).map(RepairAction.AddField(name, _))
          }.flatten

          val touchedTemplates =
            drift.collect { case NoteTypeDrift.TemplateSideDiffers(t, _) => t }.distinct

          val templateAction =
            if touchedTemplates.isEmpty then Vector.empty
            else Vector(RepairAction.ReplaceTemplates(name, touchedTemplates))

          val stylingAction =
            if drift.contains(NoteTypeDrift.StylingDiffers) then Vector(RepairAction.ReplaceStyling(name))
            else Vector.empty

          val actions = addFields ++ templateAction ++ stylingAction
          if actions.isEmpty then (Vector.empty, Vector.empty, Vector(name))
          else (actions, Vector.empty, Vector.empty)
    }

    RepairPlan(
      actions = perType.flatMap(_._1),
      refusals = perType.flatMap(_._2),
      unchanged = perType.flatMap(_._3),
    )

  /** Carry out a repair, then READ THE COLLECTION BACK AND COMPARE AGAIN.
    *
    * THE RE-READ IS THE POINT OF THIS FUNCTION, not a courtesy. AnkiConnect's
    * `updateModelTemplates` looks each template up by name and silently ignores a name it does
    * not recognise (`__init__.py:1301-1303`), and `modelFieldAdd` silently does nothing when the
    * field is already there. Both answer `null` — success — either way. So "the call returned
    * without an error" says nothing at all about whether the collection changed, and a repair
    * that reported success on that basis would be this project's signature defect wearing a
    * different hat.
    *
    * FIELDS FIRST, THEN TEMPLATES, THEN STYLING. A template that references a field the note
    * type does not have renders as nothing, so the other order leaves a window — brief, but real
    * if the run fails midway — where the card is live and the breadcrumb is blank.
    *
    * PER-ACTION FAILURES ARE COLLECTED RATHER THAN FATAL, matching [[install]]: one note type
    * Anki refuses must not abandon the rest. The re-read then shows exactly what survived.
    */
  def repair[F[_]](anki: Anki[F], assets: Vector[NoteTypeAsset], plan: RepairPlan)(using
      F: MonadError[F, AnkiError]
  ): F[RepairOutcome] =
    val byName = assets.map(a => a.spec.name -> a).toMap

    def perform(action: RepairAction): F[Unit] = action match
      case RepairAction.AddField(noteType, field) =>
        anki.addNoteTypeField(noteType, field)
      case RepairAction.ReplaceTemplates(noteType, _) =>
        // The WHOLE declared template map is sent, not only the templates reported as differing.
        // Anki updates what it is given and leaves the rest, so sending all of them is the same
        // result with one fewer thing to get wrong — and it means a template that drifted
        // between the survey and now is also corrected.
        F.fromOption(byName.get(noteType), AnkiError.NoSuchNoteType(noteType))
          .flatMap(a => anki.setNoteTypeTemplates(noteType, a.spec.templates.toVector.toMap))
      case RepairAction.ReplaceStyling(noteType) =>
        F.fromOption(byName.get(noteType), AnkiError.NoSuchNoteType(noteType))
          .flatMap(a => anki.setNoteTypeStyling(noteType, a.spec.styling))

    val ordered = plan.actions.sortBy {
      case _: RepairAction.AddField          => 0
      case _: RepairAction.ReplaceTemplates  => 1
      case _: RepairAction.ReplaceStyling    => 2
    }

    ordered
      .traverse(action => perform(action).attempt.map(_.left.toOption.map(action -> _).toLeft(action)))
      .flatMap { results =>
        // EVERY note type is re-read, not only the ones an action touched. Narrowing this to
        // "what we changed" was the first version and it made `isClean` mean less than it says:
        // a note type whose difference this repair deliberately does NOT close — a field order,
        // a field of somebody else's that must never be removed — was left out of the answer
        // entirely, so a collection still differing from the repository reported itself clean.
        // The extra cost is a handful of reads.
        survey(anki, assets).map { after =>
          RepairOutcome(
            plan = plan,
            applied = results.collect { case Right(action) => action },
            failures = results.collect { case Left(failure) => failure },
            remainingDrift = after.map {
              case NoteTypeStatus.Present(asset, drift) => asset.spec.name -> drift
              case other                                => other.name -> Vector.empty
            },
          )
        }
      }

  def install[F[_]](anki: Anki[F], assets: Vector[NoteTypeAsset])(using
      F: MonadError[F, AnkiError]
  ): F[InstallOutcome] =
    survey(anki, assets).flatMap { statuses =>
      val outcome = InstallOutcome(statuses, Vector.empty, Vector.empty)
      if outcome.blockedByRename.nonEmpty then F.pure(outcome)
      else
        val absent = statuses.collect { case NoteTypeStatus.Absent(asset) => asset }
        absent
          .traverse { asset =>
            anki
              .createNoteType(asset.spec)
              .attempt
              .map(_.left.toOption.map(asset.spec.name -> _).toLeft(asset.spec.name))
          }
          .map { results =>
            outcome.copy(
              created = results.collect { case Right(name) => name },
              failures = results.collect { case Left(failure) => failure },
            )
          }
    }

  /** The narrow, cheap question `sync` asks BEFORE it writes: is this collection ready?
    *
    * An empty result means ready. READS ONLY, and reads only what it needs — `modelNames` plus
    * one `modelFieldNames` per note type — so a template or stylesheet this tool cannot read is
    * never a reason a sync fails.
    *
    * EXTRA FIELDS AND A DIFFERENT FIELD ORDER ARE NOT PROBLEMS HERE. This tool writes fields by
    * NAME, so a field it does not know about is one it leaves alone, and the order in the
    * collection affects the Browse list rather than what is stored. Only a field that is MISSING
    * makes a write silently do the wrong thing.
    */
  def readiness[F[_]: cats.Monad](
      anki: Anki[F],
      assets: Vector[NoteTypeAsset],
  ): F[Vector[NoteTypeProblem]] =
    anki.noteTypeNames.flatMap { present =>
      val inCollection = present.toSet
      assets.traverse { asset =>
        val spec = asset.spec
        if !inCollection.contains(spec.name) then
          cats.Monad[F].pure(
            Vector(NoteTypeProblem.Missing(spec.name, asset.renamedFrom.filter(inCollection.contains)))
          )
        else
          anki.fieldNames(spec.name).map { declared =>
            NonEmptyVector
              .fromVector(spec.fields.toVector.filterNot(declared.contains))
              .map(missing => NoteTypeProblem.FieldsMissing(spec.name, missing))
              .toVector
          }
      }.map(_.flatten)
    }
