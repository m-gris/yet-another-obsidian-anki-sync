package obsidiananki.cli

import cats.data.{NonEmptyVector, Validated}
import cats.syntax.all.*
import com.monovore.decline.Opts
import obsidiananki.anki.DeckPath
import java.nio.file.{Files, Path, Paths}

/** The command line.
  *
  * THE PROFILE ARGUMENT IS THE GUARDRAIL. Any command that touches a collection requires it
  * explicitly, with NO DEFAULT, so the real collection cannot be reached by omission — the
  * failure mode where a tool does something irreversible because a flag was forgotten.
  */
enum Command:
  /** Read the vault and report what it would produce. Touches NO collection at all, so it
    * needs no profile: it answers "what does my vault say?" rather than "what would change?".
    */
  case Inspect(vault: Path, deckRoot: DeckPath, verbose: Boolean)

  /** Reconcile the vault against a collection. Requires a profile, explicitly. */
  case Sync(vault: Path, profile: String, deckRoot: DeckPath, dryRun: Boolean)

object Cli:

  private val vaultArg: Opts[Path] =
    Opts
      .argument[String]("vault")
      .mapValidated { raw =>
        val path = Paths.get(raw).toAbsolutePath.normalize
        if Files.isDirectory(path) then Validated.valid(path)
        else Validated.invalidNel(s"not a directory: $path")
      }

  /** No default, and no way to supply an empty one. A missing profile is a usage error, not
    * an invitation to guess.
    */
  private val profileOpt: Opts[String] =
    Opts
      .option[String]("profile", "Anki profile to sync into. Required; there is no default.")
      .mapValidated { raw =>
        if raw.trim.nonEmpty then Validated.valid(raw.trim)
        else Validated.invalidNel("profile must not be blank")
      }

  private val deckRootOpt: Opts[DeckPath] =
    Opts
      .option[String]("deck-root", "Root deck for synced cards. Default: Obsidian")
      .withDefault("Obsidian")
      .mapValidated { raw =>
        val segments = raw.split("::").toVector.map(_.trim).filter(_.nonEmpty)
        NonEmptyVector.fromVector(segments) match
          case Some(nev) => Validated.valid(DeckPath(nev))
          case None      => Validated.invalidNel("deck-root must not be empty")
      }

  private val inspect: Opts[Command] =
    Opts.subcommand("inspect", "Report what the vault would produce. Touches no collection.") {
      (vaultArg, deckRootOpt, Opts.flag("verbose", "List every card.").orFalse)
        .mapN(Command.Inspect.apply)
    }

  private val sync: Opts[Command] =
    Opts.subcommand("sync", "Reconcile the vault against an Anki collection.") {
      (
        vaultArg,
        profileOpt,
        deckRootOpt,
        Opts.flag("dry-run", "Compute and print the plan without applying it.").orFalse,
      ).mapN(Command.Sync.apply)
    }

  val command: Opts[Command] = inspect orElse sync
