package obsidiananki.anki

import cats.data.NonEmptyVector
import cats.syntax.all.*
import io.circe.{Decoder, DecodingFailure, HCursor}
import obsidiananki.model.Marker
import java.nio.charset.StandardCharsets

/** The five note type definitions, read out of the repository.
  *
  * WHY THIS EXISTS AT ALL. Until it did, the tool could WRITE A NOTE of a given type but could
  * not CREATE THE TYPE, so a fresh Anki profile could not be synced into: the first write
  * failed. TWO custom types got into the `claude-POC-test` profile by hand, before any of this
  * existed — `Cloze Sequence` and `3 way Concept-Descriptor` — and the hand-rename design this
  * file serves is built around exactly those two: they are the two manifests carrying
  * `renamedFrom` and `capturedFrom`, and they are now `Obsidian Cloze Sequence` and
  * `Obsidian Concept-Descriptor` in that profile (`modelNames`, read 2026-08-21). This sentence
  * used to say there was one.
  *
  * EVERY FILE UNDER `resources/note-types/` IS THE EXACT TEXT THAT GOES INTO A COLLECTION —
  * no templating, no substitution, no shared stylesheet partial concatenated at install time.
  * Two things follow, and the second is the reason for the first: each type's `styling.css` is
  * COMPLETE, so the `.context` block is duplicated verbatim five times; and a drift check is a
  * string comparison against what `modelTemplates` / `modelStyling` return, which a human can
  * read as a diff rather than having to re-derive.
  *
  * THE CONTRACT WITH `model/Marker.scala` IS HELD BY A TEST, NOT BY THIS FILE. Nothing here
  * validates a manifest against `Marker.NoteTypes` or `Marker.FieldOrder`: `NoteTypeAssets`
  * reads files and parses them, and that is all it does. `anki/NoteTypeAssets.test.scala`
  * compares the two. There is a second, broader detector as well, which is worth knowing about
  * because it is not obvious: [[InMemoryAnki.defaultNoteTypes]] is built from these manifests,
  * and the fake rejects a write naming a field its note type does not declare — so a manifest
  * that lost a field name fails tests in suites that never mention note type assets at all.
  * MEASURED 2026-08-21, in a scratch copy of this project, by deleting `Context` from
  * `basic/manifest.json`: 38 of 511 tests failed, spread over ten suites (`PlannerTest` 15,
  * `RetypingTest` / `PlannerLawTest` / `ExecutorInterruptionTest` / `DuplicateIdentityTest` /
  * `NoteTypeAssetsTest` 3 each, `AnkiConnectClientTest` / `NoteTypeInstallTest` / `MainTest` /
  * `FixtureVaultTest` 2 each). That is a MINORITY of the suite — this comment used to say
  * "most" — and the spread, not the count, is the property worth having.
  */

/** Where a vendored note type came from, and under what licence. */
final case class DerivedFrom(url: String, licence: String, licenceFile: String, modified: Boolean)

/** Records that a definition was READ OUT OF a live collection rather than authored, so that a
  * later reader does not "tidy" templates that have to stay byte-identical to what is there.
  */
final case class CapturedFrom(profile: String, action: String, date: String)

/** One template, as the manifest names it: a template name and two file paths. */
final case class TemplateRef(name: String, front: String, back: String)

/** `manifest.json`, parsed.
  *
  * STRICT, WITH NO DEFAULTS ANYWHERE — an absent key fails, and so does an unexpected one. The
  * precedent is `AnkiConnect.scala`'s `Decoder[ObservedNote]`, and the reason is the same: a
  * decoder that fills in a plausible value manufactures a note type nobody wrote down.
  *
  * `isCloze` IS MANDATORY AND IS NEVER INFERRED, and `Cloze Sequence` is why. That type has
  * "Cloze" in its name, defines `.cloze` and `.hidden-cloze` in its stylesheet, and calls its
  * hidden items clozes — and it is NOT a cloze note type: its front renders `{{Text}}`, a plain
  * field reference, not `{{cloze:Text}}`. (REPORTED, NOT RE-CHECKED HERE: an earlier session
  * read `type=0` for it and `type=1` for stock `Cloze` from `findModelsByName` on 2026-08-21.
  * Re-running that action is what would confirm it.) Every available heuristic — the name, the
  * CSS, the vocabulary — gets this one exactly backwards.
  */
final case class NoteTypeManifest(
    name: String,
    renamedFrom: Option[String],
    isCloze: Boolean,
    fields: NonEmptyVector[String],
    styling: String,
    templates: NonEmptyVector[TemplateRef],
    derivedFrom: Option[DerivedFrom],
    capturedFrom: Option[CapturedFrom],
)

/** A note type definition as loaded: the manifest, plus the same thing with every file read. */
final case class NoteTypeAsset(slug: String, manifest: NoteTypeManifest, spec: NoteTypeSpec):
  /** The name this type carries in a collection that has not yet been hand-renamed. */
  def renamedFrom: Option[String] = manifest.renamedFrom

/** Why a note type definition could not be loaded.
  *
  * ALL OF THESE ARE PROGRAMMING OR PACKAGING FAULTS, not user error: the files are in this
  * repository. They are still modelled rather than thrown, because the install command has to
  * report all five and a thrown exception reports one.
  */
enum AssetError:
  /** `getResourceAsStream` returned null.
    *
    * THE LOUDEST CASE HERE, and the one worth naming separately. A null stream read as an
    * empty string would produce a note type whose templates are blank — which Anki accepts and
    * which then generates no cards, silently. This is the shape of every defect this project
    * has found: plausible output instead of failure.
    */
  case ResourceMissing(path: String)
  case Unreadable(path: String, cause: String)
  case NotJson(path: String, detail: String)
  case Malformed(path: String, detail: String)

  /** A file the manifest names resolved, and holds nothing. Same consequence as
    * [[ResourceMissing]], reached a different way.
    */
  case EmptyFile(path: String)

  def describe: String = this match
    case ResourceMissing(path) => s"$path: not on the classpath"
    case Unreadable(path, cause) => s"$path: could not be read: $cause"
    case NotJson(path, detail) => s"$path: not JSON: $detail"
    case Malformed(path, detail) => s"$path: $detail"
    case EmptyFile(path) => s"$path: is empty"

object NoteTypeAssets:

  /** The classpath prefix everything below lives under. See `project.scala` for why the
    * `resources/` wrapper directory exists and why the files are not at the classpath root.
    */
  val ResourceRoot: String = "/note-types"

  /** NOTE TYPE NAME -> DIRECTORY SLUG, an EXPLICIT TABLE rather than a classpath enumeration.
    *
    * Enumerating the directory works when the classpath entry is a directory and does not work
    * when it is a jar, so a tool that worked in development would fail once packaged — with,
    * again, an empty list rather than an error.
    *
    * THE KEYS ARE `Marker.NoteTypes.*` BY REFERENCE, never re-typed as literals, so a rename
    * there cannot leave a stale string here. The ORDER is `Marker.NoteTypes.All`'s order; the
    * test asserts that, so this cannot drift into a different order and still look right.
    */
  val slugs: Vector[(String, String)] = Vector(
    Marker.NoteTypes.Basic             -> "basic",
    Marker.NoteTypes.BasicAndReversed  -> "basic-and-reversed",
    Marker.NoteTypes.Cloze             -> "cloze",
    Marker.NoteTypes.ClozeSequence     -> "cloze-sequence",
    Marker.NoteTypes.ConceptDescriptor -> "concept-descriptor",
  )

  /** Read one file under a type's directory, as UTF-8 text.
    *
    * FAILS ON A NULL STREAM AND ON AN EMPTY FILE. Both would otherwise become an empty
    * template or an empty stylesheet, which Anki accepts.
    */
  def readFile(slug: String, relative: String): Either[AssetError, String] =
    val path = s"$ResourceRoot/$slug/$relative"
    Option(getClass.getResourceAsStream(path)) match
      case None => Left(AssetError.ResourceMissing(path))
      case Some(stream) =>
        val read =
          try Right(new String(stream.readAllBytes(), StandardCharsets.UTF_8))
          catch case failure: java.io.IOException => Left(AssetError.Unreadable(path, failure.toString))
          finally stream.close()
        read.flatMap(text => if text.isEmpty then Left(AssetError.EmptyFile(path)) else Right(text))

  /** Parse a manifest's TEXT, with `path` used only for the error messages.
    *
    * A PURE FUNCTION, separated from the classpath read for the same reason every shape in
    * `AnkiConnect.scala` is: the parts that have historically gone wrong are then testable
    * without the filesystem, and a test can hand it a manifest that is deliberately wrong.
    */
  def parseManifest(path: String, text: String): Either[AssetError, NoteTypeManifest] =
    io.circe.parser.parse(text) match
      case Left(failure) => Left(AssetError.NotJson(path, failure.message))
      case Right(json) =>
        json.as[NoteTypeManifest].leftMap(failure => AssetError.Malformed(path, failure.message))

  /** Parse one type's `manifest.json` off the classpath. */
  def readManifest(slug: String): Either[AssetError, NoteTypeManifest] =
    readFile(slug, "manifest.json")
      .flatMap(parseManifest(s"$ResourceRoot/$slug/manifest.json", _))

  /** One note type, with every file its manifest names resolved to text. */
  def load(slug: String): Either[AssetError, NoteTypeAsset] =
    for
      manifest <- readManifest(slug)
      styling  <- readFile(slug, manifest.styling)
      templates <- manifest.templates.traverse { ref =>
        for
          front <- readFile(slug, ref.front)
          back  <- readFile(slug, ref.back)
        yield ref.name -> CardTemplate(front, back)
      }
    yield NoteTypeAsset(
      slug = slug,
      manifest = manifest,
      spec = NoteTypeSpec(
        name = manifest.name,
        isCloze = manifest.isCloze,
        fields = manifest.fields,
        templates = templates,
        styling = styling,
      ),
    )

  /** All five, in [[slugs]] order, reporting EVERY failure rather than the first.
    *
    * One run should tell the reader everything that is wrong with the repository's copy of the
    * definitions, for the same reason `Planner.plan` returns every identity collision at once.
    */
  def all: Either[Vector[AssetError], Vector[NoteTypeAsset]] =
    val loaded = slugs.map((_, slug) => load(slug))
    val errors = loaded.collect { case Left(e) => e }
    if errors.nonEmpty then Left(errors) else Right(loaded.collect { case Right(a) => a })

  // ------------------------------------------------------------------ decoders ----

  /** Decode an object, REFUSING ANY KEY NOT NAMED HERE.
    *
    * Circe ignores unknown keys by default. That default is wrong for this file: a manifest
    * with `isColze: true` would decode with `isCloze` missing — which, since there is no
    * default, at least fails — but a manifest with a misspelled OPTIONAL key such as
    * `renamdFrom` would decode cleanly with the rename hazard silently disarmed.
    */
  private def strictObject[A](known: Set[String])(read: HCursor => Decoder.Result[A]): Decoder[A] =
    Decoder.instance { cursor =>
      cursor.keys match
        case None => Left(DecodingFailure("expected a JSON object", cursor.history))
        case Some(keys) =>
          keys.filterNot(known).toVector match
            case Vector() => read(cursor)
            case unexpected =>
              Left(
                DecodingFailure(
                  s"unexpected key(s): ${unexpected.mkString(", ")}; " +
                    s"known keys are ${known.toVector.sorted.mkString(", ")}",
                  cursor.history,
                )
              )
    }

  /** NAMED, because two `NonEmptyVector` decoders live in this object and Scala 3 derives the
    * same synthetic name for both.
    */
  private given nonEmptyFieldNames: Decoder[NonEmptyVector[String]] =
    Decoder[Vector[String]].emap(NonEmptyVector.fromVector(_).toRight("must not be empty"))

  private given Decoder[DerivedFrom] =
    strictObject(Set("url", "licence", "licenceFile", "modified")) { c =>
      for
        url         <- c.downField("url").as[String]
        licence     <- c.downField("licence").as[String]
        licenceFile <- c.downField("licenceFile").as[String]
        modified    <- c.downField("modified").as[Boolean]
      yield DerivedFrom(url, licence, licenceFile, modified)
    }

  /** `action` IS FREE TEXT, and deliberately not an enum. The two captured manifests record
    * `"modelTemplates, modelStyling"` because their stylesheet came from the second action and
    * their templates from the first; a decoder that only admitted one action name would refuse
    * a true statement.
    */
  private given Decoder[CapturedFrom] =
    strictObject(Set("profile", "action", "date")) { c =>
      for
        profile <- c.downField("profile").as[String]
        action  <- c.downField("action").as[String]
        date    <- c.downField("date").as[String]
      yield CapturedFrom(profile, action, date)
    }

  /** `name` IS THE TEMPLATE'S NAME IN THE COLLECTION, and is never derived from the file name
    * or the file name from it.
    *
    * The reason is a silent no-op rather than a mismatch: AnkiConnect's `updateModelTemplates`
    * looks each template up BY NAME in the model it is updating and ignores names it does not
    * recognise, so a wrong name is a repair that reports success and changes nothing.
    * (REPORTED, NOT RE-CHECKED HERE: read out of the add-on's `__init__.py:1294-1312` on
    * 2026-08-21.) The two renamed types' template names were captured verbatim from the live
    * collection for exactly this reason; the file slugs under `templates/` are documentation.
    */
  private given Decoder[TemplateRef] =
    strictObject(Set("name", "front", "back")) { c =>
      for
        name  <- c.downField("name").as[String]
        front <- c.downField("front").as[String]
        back  <- c.downField("back").as[String]
      yield TemplateRef(name, front, back)
    }

  private given nonEmptyTemplateRefs: Decoder[NonEmptyVector[TemplateRef]] =
    Decoder[Vector[TemplateRef]].emap(NonEmptyVector.fromVector(_).toRight("must not be empty"))

  private given Decoder[NoteTypeManifest] =
    strictObject(
      Set("name", "renamedFrom", "isCloze", "fields", "styling", "templates", "derivedFrom", "capturedFrom")
    ) { c =>
      for
        name        <- c.downField("name").as[String]
        renamedFrom <- c.downField("renamedFrom").as[Option[String]]
        isCloze     <- c.downField("isCloze").as[Boolean]
        fields      <- c.downField("fields").as[NonEmptyVector[String]]
        styling     <- c.downField("styling").as[String]
        templates   <- c.downField("templates").as[NonEmptyVector[TemplateRef]]
        derived     <- c.downField("derivedFrom").as[Option[DerivedFrom]]
        captured    <- c.downField("capturedFrom").as[Option[CapturedFrom]]
      yield NoteTypeManifest(name, renamedFrom, isCloze, fields, styling, templates, derived, captured)
    }
