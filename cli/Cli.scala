package obsidiananki.cli

import cats.data.{NonEmptyVector, Validated}
import cats.syntax.all.*
import com.monovore.decline.Opts
import obsidiananki.anki.DeckPath
import obsidiananki.plan.RetypePolicy
import java.nio.file.Paths

/** How the person named the vault to read.
  *
  * TWO CASES, AND NEITHER OF THEM IS A DEFAULT. `--vault-path` names a directory outright;
  * omitting it asks to be shown a choice. There is no third case for a vault named some
  * other way, because no flag can construct one: `--vault <name>` does not exist, since
  * Obsidian's registry stores no name for a vault and what a vault's name is has not been
  * ruled. Adding a case later is purely additive; carrying one no flag can build would be a
  * hole shaped like a guess.
  */
enum VaultSelection:
  /** PROOF-CARRYING. Holding a [[VaultRoot]] rather than a `Path` means the marker check has
    * already run — at parse time, so a mistyped path fails before anything is read, before
    * any network probe, and while the shell history still records what was typed.
    */
  case AtPath(root: VaultRoot)

  /** No vault was named. `Main` shows the vaults Obsidian has opened and asks; it never
    * picks one, not even the vault Obsidian currently has open.
    */
  case Ask

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
  case Inspect(vault: VaultSelection, deckRoot: DeckPath, verbose: Boolean)

  /** Reconcile the vault against a collection. Requires a profile, explicitly.
    *
    * `retypePolicy` IS A FLAG AND DEFAULTS TO DEFERRING, which is a judgement rather than an
    * oversight — [[obsidiananki.plan.RetypePolicy]] carries the reasoning. In short: moving a
    * note between note types blanks every field and replaces every tag before writing them
    * back, and the first run that does it will move every note this tool has ever synced. That
    * is not a thing to do as a side effect of a reconcile nobody framed that way.
    */
  case Sync(
      vault: VaultSelection,
      profile: String,
      deckRoot: DeckPath,
      dryRun: Boolean,
      retypePolicy: RetypePolicy,
  )

  /** Put this tool's five note types into a collection, and report what is already there.
    *
    * NO VAULT, because it reads none: this is about the SHAPE of a collection rather than
    * about any card in it. It still requires a profile, for the same reason `sync` does — the
    * one command in this tool that changes a collection's shape must not be able to reach the
    * wrong collection by omission.
    *
    * A SEPARATE COMMAND RATHER THAN SOMETHING `sync` DOES ON ITS OWN. `sync` refuses when the
    * note types are not there and names this command; it never creates them itself. Creating a
    * note type is a one-time act of setup that changes what the collection IS, and a tool that
    * did it as a side effect of the first sync would alter the schema of somebody's collection
    * without ever having been asked to.
    */
  case InstallNoteTypes(profile: String)

object Cli:

  /** The vault, named explicitly or left to be chosen.
    *
    * VALIDATED HERE, AT PARSE TIME, and not deferred to `Main`: this is the same
    * `VaultRoot.at` check the vault carried while it was a positional argument, so a path
    * that is not a vault is refused before the vault is read and before Anki is contacted at
    * all.
    *
    * THE FAILURE MUST NOT DEMOTE TO [[VaultSelection.Ask]]. `.orNone` sits OUTSIDE
    * `mapValidated` deliberately, and decline keeps the two apart: an absent flag yields
    * `None`, while a flag whose validation FAILED yields the failure itself, not `None`
    * (measured against decline 2.6.2, the version this build resolves). Were it otherwise, a
    * person who typed a path that is not a vault would be shown a menu — and would
    * reasonably read that as the path having been accepted. The behaviour is pinned by a
    * test rather than left to this paragraph.
    *
    * THE EMPTY STRING IS REFUSED BEFORE `Paths.get` IS REACHED, and it is the only string
    * shape refused here. `Paths.get("")` is the empty path, which `VaultRoot.at` resolves
    * with `toAbsolutePath` into the PROCESS WORKING DIRECTORY — so `--vault-path ""` quietly
    * means "wherever I happen to be standing", and is accepted outright when that directory
    * carries Obsidian's marker. The way it arrives is `--vault-path "$VAULT"` with the
    * variable unset. Whitespace-only and relative paths are NOT refused: they resolve
    * against the working directory too, but they fail honestly at `VaultRoot.at` with a
    * message naming the directory that was tried, so refusing them here would be inventing
    * strictness for shapes that already report themselves.
    */
  private val vaultSelectionOpt: Opts[VaultSelection] =
    Opts
      .option[String](
        "vault-path",
        "The Obsidian vault to read. Omit to choose from the vaults Obsidian has opened.",
      )
      .mapValidated { raw =>
        if raw.isEmpty then
          Validated.invalidNel(
            "--vault-path is empty. An empty path is the current directory, not 'no vault' — " +
              "so this would have read whatever directory you are standing in. Give the vault's " +
              "path, or omit --vault-path to choose from the vaults Obsidian has opened."
          )
        else
          VaultRoot.at(Paths.get(raw)) match
            case Right(root)  => Validated.valid(VaultSelection.AtPath(root))
            case Left(reason) => Validated.invalidNel(reason)
      }
      .orNone
      .map {
        case Some(selection) => selection
        case None            => VaultSelection.Ask
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
      (vaultSelectionOpt, deckRootOpt, Opts.flag("verbose", "List every card.").orFalse)
        .mapN(Command.Inspect.apply)
    }

  /** OPT-IN, and it maps to a two-case type rather than staying a `Boolean` past this point.
    *
    * A bare `Boolean` threaded from here to the executor would arrive as an unnamed positional
    * argument three functions away, where `run(plan, anki, true)` says nothing about what is
    * true. The flag is the only place the word "migrate" appears; everything downstream reads
    * `RetypePolicy.Apply`.
    */
  private val retypePolicyOpt: Opts[RetypePolicy] =
    Opts
      .flag(
        "migrate-note-types",
        "Also move notes that are on a different note type from the one the vault asks for. " +
          "Off by default: this rewrites every field and every tag of each note it moves.",
      )
      .orFalse
      .map(if _ then RetypePolicy.Apply else RetypePolicy.Defer)

  private val sync: Opts[Command] =
    Opts.subcommand("sync", "Reconcile the vault against an Anki collection.") {
      (
        vaultSelectionOpt,
        profileOpt,
        deckRootOpt,
        Opts.flag("dry-run", "Compute and print the plan without applying it.").orFalse,
        retypePolicyOpt,
      ).mapN(Command.Sync.apply)
    }

  private val installNoteTypes: Opts[Command] =
    Opts.subcommand(
      "install-note-types",
      "Create this tool's note types in a collection, and report any that already differ.",
    ) {
      profileOpt.map(Command.InstallNoteTypes.apply)
    }

  val command: Opts[Command] = inspect orElse sync orElse installNoteTypes
