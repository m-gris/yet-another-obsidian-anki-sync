package obsidiananki.cli

import io.circe.Json
import java.nio.file.{InvalidPathException, Path, Paths}

/** The `ts` value of a registry entry, carried verbatim.
  *
  * Opaque so that nothing renders or compares it without a deliberate unwrap. It LOOKS like
  * epoch milliseconds and it is plausibly the moment Obsidian last opened that vault, but
  * that is an INFERENCE from the magnitude of four numbers and nothing here depends on it.
  * Wrapping it keeps the inference from spreading by accident into a date on a screen.
  */
opaque type RegistryStamp = Long

object RegistryStamp:
  def apply(raw: Long): RegistryStamp = raw
  extension (s: RegistryStamp) def rawTs: Long = s

/** One entry of Obsidian's vault registry: exactly the four things the file contains.
  *
  * WHAT AN ENTRY ACTUALLY CLAIMS, and it is weaker than it looks: Obsidian OPENED this
  * directory as a vault at some past time. That is all. The directory may since have been
  * moved, renamed or deleted, so the registry CHOOSES but does not VOUCH — whatever is picked
  * from it still has to go through [[VaultRoot.at]], which is the one predicate in this tool
  * that decides whether a path is a vault.
  *
  * WHICH IS WHY THIS IS NOT A `VaultRoot` AND CANNOT BECOME ONE HERE. `VaultRoot.at`
  * guarantees "this carried Obsidian's marker directory when we looked"; an entry has had no
  * such look. The only other road to a `VaultRoot` is `VaultRoot.unsafeAt`, whose own comment
  * says it is for tests and fixtures only, and using it here would make that comment false.
  *
  * NOTHING IS DERIVED. In particular there is no name field, because the registry has none —
  * the JSON key is an opaque identifier, not a label anyone chose. A `name` here would dress
  * our own derivation as data Obsidian gave us.
  */
final case class RegisteredVault(
    /** The opaque JSON object key. Carried so an entry can be referred to; not for display. */
    id: String,
    /** The `path` field, converted and not interpreted — see [[VaultRegistry.parse]]. */
    path: Path,
    /** The `ts` field. Decoded and required; unread by anything in this file. */
    stamp: RegistryStamp,
    /** This entry's own `open` flag.
      *
      * PER ENTRY, deliberately, rather than one `openVault` on the registry. Exactly one
      * entry carried `open` in the file that was examined, but that is an observation of one
      * file and not something this code can enforce; two flagged entries must come back as
      * two flagged rows rather than as an arbitrary winner.
      */
    openInObsidian: Boolean,
)

/** Why reading the registry could not produce a list of entries. */
enum RegistryError:
  /** The registry file is not there. Carries the exact path, so the reader can see where we
    * looked — this is "could not look", never "there is nothing there".
    */
  case NotFound(at: Path)

  /** The file exists and could not be read. `cause` is the underlying failure's own words. */
  case Unreadable(at: Path, cause: String)

  /** The bytes were read and are not the shape this parser requires. */
  case Malformed(at: Path, reason: String)

/** Obsidian's own list of the vaults it has opened, read as data.
  *
  * `Main` reads the file and hands the text to [[parse]]; a vault is chosen from the result
  * when no `--vault-path` is given. This is the pure half of that work, so the parsing can be
  * tested against the file's real shape without a filesystem.
  *
  * _Corrected 2026-08-20._ This paragraph previously stated the module had no caller and that
  * the vault was still a positional argument. Both were true when written and stopped being
  * true within the hour: the work that wired it up was not permitted to edit this file, and
  * this file could not know. A comment that describes the state of code ELSEWHERE goes stale
  * silently — which is the argument for describing only what this file guarantees.
  *
  * NO IO HAPPENS HERE, and `java.nio.file.Files` is deliberately not imported at all.
  * [[locate]] computes a path and [[parse]] reads a `String`; opening the file belongs to
  * `Main`, whose comment already carries "reading files and printing lines happen here and
  * nowhere else". The split is the point: everything below that can go wrong is data-to-data.
  *
  * NOTHING HERE CAN WRITE THE REGISTRY. There is no encoder and no serialiser, so read-only
  * is a property of the API rather than a promise in a comment.
  *
  * ON DEPENDING ON THIS FILE AT ALL. Its location and its layout are OBSERVED on one machine
  * and are not documented by Obsidian — exactly the standing of the `.obsidian` marker that
  * `VaultRoot` already depends on, and acceptable for the same reason: the direction it fails
  * in. If Obsidian moves the file or changes the shape, this refuses with a message naming
  * the path it looked at or the field it could not read, and naming a vault explicitly still
  * works. Loud and wrong beats quiet and wrong.
  *
  * WHAT THE RESULT IS COMPLETE WITH RESPECT TO: the entries the parser retained. It is not a
  * statement about the file's bytes — whether two identical keys inside `vaults` would both
  * survive JSON parsing has not been checked here.
  */
object VaultRegistry:

  /** Where Obsidian keeps the registry on macOS, relative to the home directory.
    *
    * ONE CONSTANT AND NO PLATFORM BRANCH. On a system that keeps it somewhere else this
    * simply does not find it, and [[RegistryError.NotFound]] carries the full path it tried —
    * which names the assumption on screen more precisely than a branch on `os.name` could,
    * and without depending on a string the JVM is free to change.
    */
  val RelativeLocation: String = "Library/Application Support/obsidian/obsidian.json"

  /** Total, and cannot fail: this computes a path, it does not consult one.
    *
    * `home` is a parameter rather than a call to `user.home` so that the composition is
    * drivable from a test; the real home is supplied by the caller that does the IO.
    */
  def locate(home: Path): Path = home.resolve(RelativeLocation)

  /** Read the registry's CONTENT. Pure: `json` is the bytes someone else already read.
    *
    * `at` is carried only so a refusal can say which file it is talking about. It is never
    * opened here.
    *
    * STRICT ABOUT WHAT IT REQUIRES, TOLERANT ABOUT WHAT IT IGNORES. `path` and `ts` must be
    * present and of the right type; unknown keys at either level — the top-level `insider`
    * and `cli` seen alongside `vaults`, and whatever a later Obsidian adds — are ignored
    * without complaint. Strictness means not INVENTING values, not forbidding extra ones.
    *
    * THE ABSENCE OF `vaults` IS CHECKED SEPARATELY FROM ITS VALUE, and this is not
    * decoration. Circe answers `Right(None)` for an ABSENT key exactly as it does for an
    * explicit null — established in `AnkiConnect.envelope` against the same circe version —
    * so decoding straight to an optional map would read "this is not an obsidian.json" as
    * "you have no vaults". An empty `vaults` object, by contrast, is a `Right`: Obsidian
    * knows of no vaults, which is a fact about the file. Whether an empty list is usable is
    * the caller's policy and not this function's.
    *
    * ONE BAD ENTRY REFUSES THE WHOLE FILE, naming the offending id and field. Returning the
    * survivors would hand the caller a silently shortened list, and a silently shortened list
    * of vaults is the same failure shape as a silently shortened scan of one.
    *
    * THE PATH IS CONVERTED AND NOT INTERPRETED. Neither `toAbsolutePath` nor `normalize` is
    * called, so a `..` inside an entry stays unresolved and a `~` stays a literal character;
    * `VaultRoot.at` performs the one normalisation, at the point where it matters. Two things
    * to know about the conversion: `Paths.get` drops a trailing slash, so the `Path` is not
    * literally the string as written though it denotes the same directory; and it THROWS
    * `InvalidPathException` on characters JSON can carry and a path cannot, such as a NUL —
    * caught here, because without the catch this signature's `Either` would be a lie.
    *
    * DUPLICATE PATHS ARE NOT COLLAPSED. Without touching the filesystem, string equality is
    * not vault identity — this machine's filesystem is case-insensitive in one direction and
    * symlinks defeat it in the other — so two rows naming the same directory are reported as
    * two rows.
    *
    * THE ORDER IS CANONICAL, ASCENDING BY THE PATH STRING, tie-broken by id. That is a
    * DETERMINISM guarantee and not a display recommendation: the order in which a JSON
    * object's members come back is a property of the parser rather than anything JSON
    * promises, so the sort is what makes two runs over one file agree. Sorting the STRING
    * rather than using `Path.compareTo` keeps the comparison off the filesystem provider.
    * `stamp` is carried so that a caller wanting a different order, or an annotation, has the
    * value without editing this file.
    */
  def parse(at: Path, json: String): Either[RegistryError, Vector[RegisteredVault]] =
    io.circe.parser.parse(json) match
      case Left(failure) =>
        Left(RegistryError.Malformed(at, s"not JSON: ${failure.message}"))
      case Right(document) =>
        document.hcursor.downField("vaults").focus match
          case None =>
            Left(RegistryError.Malformed(at, "no 'vaults' key — is this an obsidian.json?"))
          case Some(vaultsJson) =>
            vaultsJson.asObject match
              case None =>
                Left(
                  RegistryError.Malformed(
                    at,
                    s"'vaults' is not an object: ${vaultsJson.noSpaces.take(120)}",
                  )
                )
              case Some(vaults) =>
                val read = vaults.toVector.map((id, entryJson) => entry(at, id, entryJson))
                read.collectFirst { case Left(error) => error } match
                  case Some(error) => Left(error)
                  case None =>
                    Right(
                      read.collect { case Right(vault) => vault }
                        .sortBy(vault => (vault.path.toString, vault.id))
                    )

  /** One entry, by the id it is filed under. */
  private def entry(at: Path, id: String, json: Json): Either[RegistryError, RegisteredVault] =
    val cursor = json.hcursor
    def malformed(what: String): RegistryError =
      RegistryError.Malformed(at, s"vault '$id': $what")
    for
      rawPath <- cursor
        .downField("path")
        .as[String]
        .left
        .map(_ => malformed("'path' is missing or is not a string"))
      path <- readPath(rawPath).left
        .map(cause => malformed(s"'path' is not a usable path: $cause"))
      ts <- cursor
        .downField("ts")
        .as[Long]
        .left
        .map(_ => malformed("'ts' is missing or is not a whole number"))
      // ABSENT means not open — that is Obsidian's own encoding, observed rather than a
      // default invented here: three of the four entries examined omitted the key entirely
      // and none carried `false`. Present-and-not-a-boolean is a different thing and is
      // refused, because reading `"open": "yes"` as false would be inventing a value.
      open <- cursor.downField("open").focus match
        case None => Right(false)
        case Some(openJson) =>
          openJson.asBoolean.toRight(
            malformed(s"'open' is present but is not a boolean: ${openJson.noSpaces.take(40)}")
          )
    yield RegisteredVault(id, path, RegistryStamp(ts), open)

  /** `Paths.get`, with the exception it is documented to throw turned into a value. */
  private def readPath(raw: String): Either[String, Path] =
    try Right(Paths.get(raw))
    catch case invalid: InvalidPathException => Left(invalid.getMessage)
