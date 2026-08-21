package obsidiananki.anki

import cats.MonadError
import cats.data.NonEmptyVector
import cats.syntax.all.*

/** Getting this tool's five note types into a collection, and refusing to write to one that
  * does not have them.
  *
  * THE SAFE DEFAULT, RULED HERE AND STATED ONCE: **nothing this tool did not create is ever
  * silently overwritten.** A note type that is absent is CREATED; a note type that is present
  * and differs from the repository is REPORTED and left exactly as it is. There is no repair
  * path, not even behind a flag, and that is a decision rather than an omission — repairing
  * means sending a template and a stylesheet over ones a person may have edited by hand in
  * Anki, and the person who edited them is the only one who knows whether that is wanted.
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
