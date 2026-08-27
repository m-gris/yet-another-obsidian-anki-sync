package obsidiananki.cli

import cats.data.EitherT
import cats.effect.{ExitCode, IO}
import cats.syntax.all.*
import com.monovore.decline.Opts
import com.monovore.decline.effect.CommandIOApp
import obsidiananki.anki.{
  AnkiConnectClient,
  AnkiError,
  AssetError,
  DeckPath,
  InstallOutcome,
  NoteTypeAsset,
  NoteTypeAssets,
  NoteTypeInstaller,
  NoteTypeProblem,
}
import obsidiananki.extract.{DeckShape, VaultFile, VaultIndex, VaultWalker}
import obsidiananki.model.CardKey
import obsidiananki.plan.{
  ExecutionReport,
  Executor,
  Observer,
  OrphanInference,
  Plan,
  PlanError,
  Planner,
  RetypePolicy,
  SyncAction,
}
import org.http4s.ember.client.EmberClientBuilder
import java.io.IOException
import java.nio.file.{Files, NoSuchFileException, Path, Paths}
import scala.jdk.CollectionConverters.*

/** The imperative shell.
  *
  * Everything below the CLI is pure, which is why the walk, the extractor, the planner and
  * the reporter can all be tested without a filesystem, a network or captured output.
  *
  * WHERE THE IO IS, STATED EXACTLY. `Cli` consults the filesystem in one place and one only:
  * `--vault-path` is checked at parse time for existence and for Obsidian's marker
  * directory, so a path that is not a vault is refused before anything else happens. Every
  * OTHER file this tool opens — the vault's markdown, Obsidian's vault registry — and every
  * line it prints, and the one line it reads back, happen in this file.
  *
  * _Amended when the vault stopped being a positional argument. This previously read
  * "reading files and printing lines happen here and nowhere else", which was already
  * imprecise — `Cli` has stat-ed the vault path since the marker check was added — and this
  * change adds a second read here rather than moving that one._
  */
object Main
    extends CommandIOApp(
      name = "obsidian-anki-sync",
      header = Help.header,
    ):

  /** The effect [[AnkiConnectClient]] speaks: an `IO` that may carry a REFUSAL from Anki.
    *
    * Named for what the `Left` means rather than for its shape: Anki answered and said no.
    * Being unable to reach Anki at all is not this — it stays a `Throwable` in `IO`.
    */
  private[cli] type Refused[A] = EitherT[IO, AnkiError, A]

  /** `inspect` and `sync` resolve the vault the SAME way — one call each to the same
    * function, differing only in what they do with the answer.
    *
    * THE ORDER FOR `sync` IS GATE FIRST, THEN PROMPT: vault resolution happens INSIDE
    * [[withVerifiedProfile]]'s body. Two things follow, both wanted. Nobody is asked to
    * choose a vault for a run that a shut Anki had already doomed. And the gate's claim —
    * "the check runs before the vault is READ" — stays true, because prompting is not
    * reading: the vault's files are opened by `sync`, after the choice.
    */
  def main: Opts[IO[ExitCode]] = Cli.command.map {
    case Command.Inspect(selection, deckRoot, deckShape, verbose) =>
      withChosenVault(selection)(inspect(_, deckRoot, deckShape, verbose))
    case Command.Sync(selection, profile, deckRoot, deckShape, dryRun, retypePolicy) =>
      withVerifiedProfile(profile)(anki =>
        withChosenVault(selection)(sync(_, deckRoot, deckShape, dryRun, retypePolicy, anki))
      )
    case Command.InstallNoteTypes(profile, repair) =>
      withVerifiedProfile(profile)(installNoteTypes(_, repair))
  }

  /** Read the vault and say what it holds. Touches no collection. */
  private def inspect(
      vault: VaultRoot,
      deckRoot: DeckPath,
      deckShape: DeckShape,
      verbose: Boolean,
  ): IO[ExitCode] =
    for
      files <- readVault(vault)
      index = VaultWalker.scan(files, deckRoot, deckShape)
      _ <- IO.println(s"vault:    $vault")
      _ <- IO.println(s"files:    ${files.size}")
      _ <- Report.inspect(index, verbose).traverse_(IO.println)
    yield exitCodeFor(index)

  // -------------------------------------------------------------- the note types ----

  /** Create this tool's note types in the collection the gate has already confirmed.
    *
    * THE ONLY COMMAND THAT CHANGES A COLLECTION'S SHAPE, and the only one that writes without
    * a vault. `sync` deliberately does not do this on its own — see [[Command.InstallNoteTypes]].
    *
    * THE ASSET FAILURE IS ITS OWN EXIT PATH, and it exits 2 rather than 1. If the definitions
    * in this repository cannot be read then no collection was touched at all, which is exactly
    * what 2 means here; and the fault is in the build or the packaging rather than in anything
    * the person did.
    */
  private def installNoteTypes(anki: AnkiConnectClient[IO], repair: Boolean): IO[ExitCode] =
    NoteTypeAssets.all match
      case Left(errors) =>
        (Vector(
          "REFUSED: this tool's own note type definitions could not be read.",
          "",
        ) ++ errors.map("  " + _.describe) ++ Vector(
          "",
          "Nothing was read from the collection and nothing was written. These files ship with",
          "the tool, so this is a build or packaging fault rather than anything about your vault",
          "or your collection.",
        )).traverse_(IO.println).as(ExitCode(2))

      case Right(assets) =>
        NoteTypeInstaller.install[Refused](anki, assets).value.flatMap {
          case Left(error) =>
            Vector(
              "",
              "STOPPED: the collection refused a request while its note types were being read.",
              "",
              s"  Anki's answer:  ${error.toString}",
              "",
              "Some note types may already have been created. Re-running is safe: this command",
              "creates only what is absent and never overwrites what is there.",
            ).traverse_(IO.println).as(ExitCode.Error)

          case Right(outcome) =>
            Report.noteTypes(outcome).traverse_(IO.println) *>
              (if !repair then IO.pure(exitCodeForInstall(outcome))
               else repairNoteTypes(anki, assets, outcome))
        }

  /** The second half of `install-note-types --repair`: close the differences the survey found.
    *
    * RUNS AFTER THE INSTALL REPORT AND NEVER INSTEAD OF IT, so what a person reads is always
    * "here is what differs" followed by "here is what I did about it", in that order. A repair
    * that printed only its own actions would be asking someone to accept a change it never
    * showed them.
    *
    * A RUN BLOCKED BY A HAND-RENAME REPAIRS NOTHING, for the same reason it creates nothing: the
    * collection is not in the state this command is for, and there is exactly one thing to do
    * about that. The install half has already said so, so this adds nothing to the noise.
    */
  private def repairNoteTypes(
      anki: AnkiConnectClient[IO],
      assets: Vector[NoteTypeAsset],
      outcome: InstallOutcome,
  ): IO[ExitCode] =
    if outcome.blockedByRename.nonEmpty then IO.pure(ExitCode(2))
    else
      val plan = NoteTypeInstaller.planRepair(outcome.before)
      if plan.isEmpty && plan.refusals.isEmpty then IO.pure(exitCodeForInstall(outcome))
      else
        NoteTypeInstaller.repair[Refused](anki, assets, plan).value.flatMap {
          case Left(error) =>
            Vector(
              "",
              "STOPPED: the collection refused a request during the repair.",
              "",
              s"  Anki's answer:  ${error.toString}",
              "",
              "Part of the repair may already have landed. Re-running is safe — a repair adds",
              "only fields that are missing and rewrites templates to match the repository, so",
              "doing it twice reaches the same place as doing it once.",
            ).traverse_(IO.println).as(ExitCode.Error)

          case Right(result) =>
            Report.noteTypeRepair(result).traverse_(IO.println) *>
              IO.pure(if result.isClean then ExitCode.Success else ExitCode.Error)
        }

  /** THE EXIT-CODE CONTRACT OF `install-note-types`, in the same three-value shape as
    * [[exitCodeFor]]:
    *
    *   - `2` — the run REFUSED and NOTHING WAS WRITTEN. TWO things reach it, and only the
    *     second is decided here. _Amended 2026-08-21: this line said "exactly one thing", which
    *     was false against the code directly above it._
    *       1. THE ASSET FAILURE — this tool's own note type definitions under
    *          `resources/note-types/` could not be read, so [[installNoteTypes]] returns
    *          `ExitCode(2)` at its `NoteTypeAssets.all` `Left` arm without ever calling this
    *          function. The comment on [[installNoteTypes]] states this too.
    *       2. A HAND-RENAME STILL OUTSTANDING — `outcome.blockedByRename.nonEmpty`, the branch
    *          below, which stops the whole run rather than part of it. [[repairNoteTypes]]
    *          re-tests the same condition so that `--repair` repairs nothing either.
    *   - `1` — it ran and something is wrong: a note type could not be created, or one is
    *     present and differs from the repository and was therefore left alone.
    *   - `0` — every note type is now present and matches. Creating some of them is not a
    *     problem; that is the command doing its job.
    *
    * A THIRD `2` PRECEDES ALL OF THESE AND IS NOT PART OF THIS CONTRACT: the shared profile
    * gate, [[verifyThen]], answers `ExitCode(2)` on a mismatched, unaskable or unreachable
    * profile before `install-note-types` is entered at all. It is common to every command that
    * touches a collection, which is why it is named here rather than counted above.
    *
    * SPLIT OUT SO IT CAN BE TESTED, for the same reason [[verifyThen]] is.
    */
  private[cli] def exitCodeForInstall(outcome: InstallOutcome): ExitCode =
    if outcome.blockedByRename.nonEmpty then ExitCode(2)
    else if outcome.isClean then ExitCode.Success
    else ExitCode.Error

  // ------------------------------------------------------------ choosing a vault ----

  /** What `System.console()` returned, and NOTHING MORE.
    *
    * NAMED FOR THE MEASUREMENT rather than for what one would like it to mean, because those
    * are not the same thing and the gap is load-bearing. Measured on Temurin 17.0.17, the
    * JDK this machine builds and runs it with: `System.console()` is non-null only when stdin AND stdout
    * are both terminals, so `sync … | tee log`, typed by a person sitting at a terminal, is
    * [[Absent]]. Calling this `stdinIsATerminal` would therefore be a name that lies in the
    * one case the person can disprove by looking at their screen.
    *
    * THE DIRECTION IT FAILS IN IS THE POINT. A false [[Absent]] refuses a run that could have
    * asked; a false [[Present]] would print a menu into a pipe and block on a stream nobody
    * is typing into. Only the first happens here.
    *
    * UNVERIFIED UPGRADE HAZARD, recorded because it inverts the check rather than weakening
    * it: `System.console()` is understood to change meaning from JDK 22 — non-null even under
    * redirection, with `Console.isTerminal()` as the discriminator. Not measured here. If
    * this project moves past JDK 17, re-measure BEFORE trusting this: unchanged, a cron run
    * would start consuming stdin instead of being refused.
    */
  private[cli] enum ConsolePresence:
    case Present
    case Absent

  /** What the person's typed answer amounted to.
    *
    * TWO CASES, NOT MORE. There is no retry loop, so every way of not choosing has exactly
    * one consequence — print the reason, refuse, exit 2 — and [[Verdict]]'s own comment in
    * this file already rules that a case the exit path cannot act on differently does not
    * earn itself. End-of-input never reaches this type at all: [[chooseVault]] matches it at
    * the boundary, where a closed stream is a different fact from a misread answer.
    */
  private[cli] enum PickerAnswer:
    case Chosen(entry: RegisteredVault)
    case NotChosen(reason: String)

  /** The list that was shown, and the reading of what came back — ONE value.
    *
    * WHY THESE ARE NOT TWO FUNCTIONS. A renderer and a chooser each taking a
    * `Vector[RegisteredVault]` could be handed different vectors, and then the person reads
    * row 3 and gets vault 1. Ordinal *n* means nothing except against the vector that was
    * displayed, so there is one vector and a private constructor, and the mismatch is
    * unrepresentable rather than a thing code review has to keep catching.
    *
    * PURE: [[lines]] is text and [[interpret]] is a function on a string. Nothing here reads
    * or writes anything, which is why the whole picker is testable with no terminal.
    */
  private[cli] final class Offering private (at: Path, rows: Vector[RegisteredVault]):

    /** WHAT IS ON SCREEN, and as much about what is NOT.
      *
      * THE ORDER IS THE PARSER'S OWN and is not re-sorted here. In particular the vault
      * Obsidian has open is not floated to the top: a row in first position is a default
      * wearing a listing's clothes, and there is deliberately no default.
      *
      * `(open in Obsidian)` MARKS EVERY FLAGGED ENTRY. The flag is per entry; one open vault
      * is an observation of one file rather than something this code can rely on, so two
      * flagged entries make two flagged rows. It is an annotation and never a preselection.
      *
      * NO OPAQUE ID, because it is a key in a JSON object and not a name anyone chose. NO
      * `ts` IN ANY FORM: that it is the moment the vault was last opened is an INFERENCE, and
      * "last opened 3 days ago" is the shape that inference would take on screen. NO COUNT of
      * vaults in the header — measured against circe 0.14.16, this build's version, two
      * identical keys inside one JSON object collapse to one, so a count would claim more
      * about the file than the parser can support; the ordinals number the rows anyway.
      */
    val lines: Vector[String] =
      val header = Vector(
        "Obsidian's vault registry, read from:",
        s"  $at",
        "",
      )
      val listed = rows.zipWithIndex.map { (row, index) =>
        val flag = if row.openInObsidian then "  (open in Obsidian)" else ""
        s"  ${index + 1}  ${row.path}$flag"
      }
      val footer = Vector(
        "",
        "This list is NOT every vault you have: it is the directories Obsidian has opened. A",
        "vault Obsidian has never been pointed at is not here — name it with --vault-path.",
        "",
        s"Type the number of the vault to read (1-${rows.size}):",
      )
      header ++ listed ++ footer

    /** TOTAL over an arbitrary string, because a prompt receives arbitrary strings.
      *
      * THE RULE IS THE WHOLE RULE: trim, parse as an integer if it is one, take that row if
      * there is one. So `01`, `+1` and a trailing carriage return all select, while an empty
      * line, `0`, a number past the end, two numbers, a number too large for an `Int`, a
      * pasted path and a typed vault name all refuse with the range named.
      *
      * A TYPED PATH IS DELIBERATELY NOT ACCEPTED HERE. That is `--vault-path` reimplemented
      * at a prompt — where the shell history keeps no record of what was chosen, and where
      * the parse-time validation `--vault-path` gets is already behind us.
      */
    def interpret(typed: String): PickerAnswer =
      val cleaned = typed.trim
      cleaned.toIntOption.flatMap(n => rows.lift(n - 1)) match
        case Some(entry) => PickerAnswer.Chosen(entry)
        case None =>
          PickerAnswer.NotChosen(
            s"REFUSED: '$cleaned' is not one of the vaults offered. " +
              s"Type the number of a row, between 1 and ${rows.size}."
          )

  private[cli] object Offering:
    def of(at: Path, rows: Vector[RegisteredVault]): Offering = new Offering(at, rows)

  /** What to tell the person when the registry could not be turned into a list.
    *
    * PURE: takes data, returns lines, prints nothing — the same contract as [[Report]], and
    * these sit here rather than there only because that file is outside this change.
    *
    * ALL THREE CASES MATCHED, no wildcard, and all three END THE SAME WAY: name the vault
    * with `--vault-path`. That is what makes every registry failure degrade to the behaviour
    * the tool had before it read a registry at all — it fails backwards.
    */
  private[cli] def describeRegistryError(e: RegistryError): Vector[String] = e match
    case RegistryError.NotFound(at) =>
      Vector(
        "REFUSED: no vault was named, and Obsidian's vault registry is not where this tool looks.",
        "",
        s"  looked at:  $at",
        "",
        "That is a report that the list COULD NOT BE LOOKED AT. It says nothing about how many",
        "vaults you have. The path above is where Obsidian keeps the registry on macOS, and it is",
        "the only place this tool tries — on another platform the file lives elsewhere and this",
        "will never find it.",
        "",
        "Name the vault yourself: --vault-path <directory>",
      )

    case RegistryError.Unreadable(at, cause) =>
      Vector(
        "REFUSED: no vault was named, and Obsidian's vault registry could not be read.",
        "",
        s"  file:         $at",
        // The cause is shown as itself. Rewording it here would make a second source of truth
        // for one failure — the objection this file already records about not re-rendering
        // `AnkiError`.
        s"  the failure:  $cause",
        "",
        "The file is there; opening or reading it did not work.",
        "",
        "Name the vault yourself: --vault-path <directory>",
      )

    case RegistryError.Malformed(at, reason) =>
      Vector(
        "REFUSED: no vault was named, and Obsidian's vault registry is not a shape this tool reads.",
        "",
        s"  file:              $at",
        s"  what went wrong:   $reason",
        "",
        "Nothing was guessed from it. A registry that cannot be parsed yields no vault list at all,",
        "rather than a shortened one.",
        "",
        "Name the vault yourself: --vault-path <directory>",
      )

  /** The registry was READ and knows of no vault.
    *
    * A SEPARATE MESSAGE FROM [[RegistryError.NotFound]], deliberately: "there is no registry
    * file at this path" and "the registry lists no vaults" are different facts with different
    * remedies, and collapsing them would make the tool say it could not look when it did.
    *
    * Printing an empty numbered list and then waiting for a number is the alternative this
    * exists to rule out.
    */
  private[cli] def describeNoVaults(at: Path): Vector[String] =
    Vector(
      "REFUSED: no vault was named, and Obsidian's registry lists no vaults.",
      "",
      s"  file:  $at",
      "",
      "The file was read and its vault list is empty, so there is nothing to offer. This is not",
      "the same as the file being missing: Obsidian has a registry here and it knows of no vault.",
      "",
      "Name the vault yourself: --vault-path <directory>",
    )

  /** No vault named, and no terminal to ask at.
    *
    * WORDED TO THE PREDICATE WE ACTUALLY HAVE. See [[ConsolePresence]]: what was measured is
    * that this process has no interactive console, which needs BOTH its input and its output
    * to be a terminal. Saying "stdin is not a terminal" would be false for the person who
    * piped the output while sitting at one — the single case where they can see for
    * themselves that the message is wrong.
    */
  private[cli] def describeNoConsole(): Vector[String] =
    Vector(
      "REFUSED: no vault was named, and there is no interactive terminal to ask at.",
      "",
      "Choosing from Obsidian's list of vaults needs a terminal for BOTH the list and the answer,",
      "and this process does not have both. That is the case under cron, in a pipeline and in",
      "CI — and also for a run typed by hand whose output is redirected or piped.",
      "",
      "There is no default vault, not even the one Obsidian currently has open.",
      "",
      "Name the vault: --vault-path <directory>",
    )

  /** The answer never arrived because the stream ended.
    *
    * END OF INPUT IS NOT A CHOICE and is not a mistyped answer either. Reading it as the
    * first row would pick a vault nobody named; reading it as "not understood" would send
    * the person to correct a typo they did not make.
    */
  private[cli] def describeStdinClosed(): Vector[String] =
    Vector(
      "REFUSED: stdin closed before an answer arrived, so no vault was chosen.",
      "",
      "End of input is not an answer, and there is no default vault to fall back to.",
      "",
      "Name the vault: --vault-path <directory>",
    )

  /** A row was chosen and the directory it names is not a vault.
    *
    * `VaultRoot.at`'S REASON IS CARRIED VERBATIM. Rewording it would create a second source
    * of truth for one failure — the same objection this file already records about not
    * re-rendering `AnkiError` — and the two wordings would drift.
    *
    * THE PROVENANCE LINE COMES FIRST, and that placement is the point of it. `VaultRoot.at`'s
    * text was written for a path someone typed, and ends by asking whether they meant a
    * folder inside it; read cold against a path nobody typed, that question has no addressee.
    * Saying where the path came from before quoting the reason gives it one.
    *
    * REFUSES; DOES NOT ASK AGAIN. One question, one answer.
    */
  private[cli] def describeStalePick(entry: RegisteredVault, reason: String): Vector[String] =
    Vector(
      "REFUSED: the vault you chose cannot be read.",
      "",
      "That path came from Obsidian's own registry, which records that Obsidian opened the",
      "directory as a vault at some past time. Nobody typed it on the command line, and the",
      "directory may since have been moved, renamed or deleted.",
      "",
      s"  chosen:  ${entry.path}",
      s"  $reason",
      "",
      "Name the vault directly: --vault-path <directory>",
    )

  /** Turn what was typed on the command line into the vault to read, or into the lines that
    * say why there is none.
    *
    * EVERY DEPENDENCY IS INJECTED — the console measurement, the home directory, the registry
    * read and the question — so the whole of this is drivable with no terminal, no filesystem
    * and no home. [[verifyThen]] already makes that argument about the profile gate: an
    * untested guardrail is a claim rather than a guarantee, and this one stands between the
    * tool and whichever collection a wrongly chosen vault would flag.
    *
    * `Left` is the refusal lines for the caller to print; the caller exits 2.
    *
    * THE ORDER IS NOT ARBITRARY, and each step is before the next for its own reason:
    *   1. An explicitly named vault short-circuits — the registry is NOT read and nothing is
    *      asked. Every registry failure degrades to "name it with --vault-path", so a broken
    *      registry must not be able to break the thing it degrades to.
    *   2. The console check comes BEFORE the registry read. Refusing a cron run must not
    *      depend on the registry being readable, and the refusal is the same either way.
    *   3. The registry read comes before the question, obviously; a read that failed has
    *      nothing to show.
    *
    * ONE QUESTION, ONE ANSWER, NO RETRY LOOP. Every refusal ends the run, including a chosen
    * row that turns out not to be a vault. A loop here is exited only by a human or by
    * end-of-input, and is easy to spin.
    *
    * `at` IS COMPUTED ONCE and used for the read, for the listing's header and for the
    * empty-registry message, so the path this says it read and the path it read cannot
    * differ.
    */
  private[cli] def chooseVault(
      selection: VaultSelection,
      console: ConsolePresence,
      home: Path,
      readRegistry: Path => IO[Either[RegistryError, Vector[RegisteredVault]]],
      ask: Vector[String] => IO[Option[String]],
  ): IO[Either[Vector[String], VaultRoot]] =
    selection match
      case VaultSelection.AtPath(root) => IO.pure(Right(root))
      case VaultSelection.Ask =>
        console match
          case ConsolePresence.Absent => IO.pure(Left(describeNoConsole()))
          case ConsolePresence.Present =>
            val at = VaultRegistry.locate(home)
            readRegistry(at).flatMap {
              case Left(error)                 => IO.pure(Left(describeRegistryError(error)))
              case Right(rows) if rows.isEmpty => IO.pure(Left(describeNoVaults(at)))
              case Right(rows) =>
                val offering = Offering.of(at, rows)
                ask(offering.lines).map {
                  case None => Left(describeStdinClosed())
                  case Some(typed) =>
                    offering.interpret(typed) match
                      case PickerAnswer.NotChosen(reason) => Left(Vector(reason))
                      case PickerAnswer.Chosen(entry) =>
                        // THE REGISTRY CHOOSES BUT DOES NOT VOUCH. An entry records a past
                        // opening, not a present directory, so `VaultRoot.at` stays the one
                        // predicate in this tool that decides whether a path is a vault.
                        VaultRoot.at(entry.path) match
                          case Right(root)  => Right(root)
                          case Left(reason) => Left(describeStalePick(entry, reason))
                }
            }

  /** [[chooseVault]] wired to the real console, the real home directory and the real file,
    * then either handed to `body` or printed as a refusal.
    *
    * THE ONE PLACE THE PICKER TOUCHES THE WORLD: the console measurement, the home
    * directory, the file read and the question are all supplied from here, rather than
    * defaulted into [[chooseVault]]'s parameters where a test could not displace them.
    *
    * NOTE WHAT THAT LEAVES UNTESTED, rather than implying otherwise: everything in this
    * function and the two below it is the production wiring the tests inject past, so it is
    * exercised by running the tool and not by the suite.
    */
  private def withChosenVault(selection: VaultSelection)(
      body: VaultRoot => IO[ExitCode]
  ): IO[ExitCode] =
    for
      console <- IO(
        if System.console() == null then ConsolePresence.Absent else ConsolePresence.Present
      )
      // `sys.props` APPLIED, not `get`: a JVM with no `user.home` is a broken JVM and should
      // say so, loudly, rather than have a directory guessed for it.
      home = Paths.get(sys.props("user.home"))
      chosen <- chooseVault(selection, console, home, readRegistryFile, askOnConsole)
      code <- chosen match
        case Right(root) => body(root)
        case Left(lines) => lines.traverse_(IO.println).as(ExitCode(2))
    yield code

  /** Read the registry file and hand its bytes to the pure parser.
    *
    * `try`/`catch` INSIDE `IO.blocking`, and NOT an `.attempt` over the `IO`: this file's
    * gate comment says there is EXACTLY ONE `.attempt` over IO here, and a second would make
    * that sentence false.
    *
    * TWO EXCEPTIONS CAUGHT AND NOTHING BROADER. `NoSuchFileException` first, because it is an
    * `IOException` and the wider catch would otherwise swallow it and report "could not read"
    * for a file that simply is not there — two different facts with two different remedies.
    */
  private def readRegistryFile(at: Path): IO[Either[RegistryError, Vector[RegisteredVault]]] =
    IO.blocking {
      try VaultRegistry.parse(at, Files.readString(at))
      catch
        case _: NoSuchFileException => Left(RegistryError.NotFound(at))
        case failure: IOException   => Left(RegistryError.Unreadable(at, failure.toString))
    }

  /** Show the list and read one line back — bound together as ONE act, deliberately.
    *
    * A menu cannot be printed without a read following it, a read cannot happen without the
    * menu, and a test injecting this can count how many times it happened, which is what
    * proves there is no retry loop.
    *
    * `readLine` RETURNS `null` AT END OF INPUT. It is wrapped at the boundary and matched
    * immediately by the caller, so no nullable and no `Option[String]` travels onward and
    * there is no `getOrElse("")` to read a closed stream as an empty answer.
    *
    * THE MENU GOES TO STDOUT, like everything else this tool prints. Measured: the picker
    * runs only when stdout is a terminal (see [[ConsolePresence]]), so the menu cannot be
    * captured by `> log` or `| tee` and the stream choice is unobservable today. That stops
    * being true if the console check ever sharpens to stdin alone — at which point where the
    * menu goes becomes a real question rather than a free one.
    */
  private def askOnConsole(lines: Vector[String]): IO[Option[String]] =
    lines.traverse_(IO.println) *> IO.blocking(Option(scala.io.StdIn.readLine()))

  // ------------------------------------------------------------- the profile gate ----

  /** How the profile probe ended.
    *
    * CLOSED, with no fifth "assume it is fine" case, and BOTH mappings over it match every
    * case rather than falling back on a wildcard: `describeProfileCheck`, and
    * `withVerifiedProfile`'s exit-code branch.
    *
    * WHAT THAT BUYS. A case added later cannot silently acquire a PERMISSION — only
    * `Confirmed` reaches `body`, so anything new refuses the run — AND it cannot be added
    * without both mappings answering for it: `project.scala` ends its option line with
    * `-Wconf:msg=exhaustive:e`, so an inexhaustive match is a BUILD ERROR here, not a warning.
    *
    * _Corrected 2026-08-23, and the correction is the point._ This paragraph said the opposite
    * — that the build sets no `-Xfatal-warnings`, that a non-exhaustive match merely warns, and
    * that a fifth case would compile and raise a `MatchError` on the first `sync`. That was true
    * when written on 2026-08-19 and false one day later: `754042a` added the flag on 2026-08-20
    * and this comment was not revisited. Its own earlier draft had claimed enforcement and been
    * "corrected" by measurement — so the file records a true statement being replaced by a false
    * one, then outliving the fix.
    *
    * Left standing, it was worse than a stale note. In a codebase whose comments are
    * load-bearing, a comment telling the next author that exhaustiveness is UNENFORCED is an
    * invitation to reach for the `case _ =>` that this project has just spent a day removing.
    *
    * Four cases and not two, because `activeProfile` has two failure channels before any
    * string comparison happens at all. "Could not look", "the wrong collection is open" and
    * "could not reach Anki" are three different facts, and none of them may be rendered as
    * either of the others.
    */
  private[cli] enum ProfileCheck:
    /** Carries the name ANKI RETURNED, not the argument that was asked for, so the
      * confirming line reports what Anki said rather than what we requested.
      */
    case Confirmed(open: String)
    case Mismatch(requested: String, open: String)
    case CouldNotAsk(requested: String, error: AnkiError)
    case Unreachable(requested: String, cause: Throwable)

  /** Decide how the probe ended, from the already-`attempt`ed answer.
    *
    * PURE AND TOTAL, which is the point of taking the attempted value rather than the
    * effect: this needs no HTTP, no IO and no Anki, so every branch can be driven directly.
    *
    * THE COMPARISON IS EXACT `==`, on the string `Cli` has already trimmed. No case-folding,
    * no Unicode normalisation, no prefix matching. Every loosening enlarges the set of
    * collections this tool can reach, and the near miss is not hypothetical: a profile named
    * `claude-POC-test` sits next to one named `POC-test` on this machine right now. Whether
    * Anki itself distinguishes profile names the same way is NOT known, and nothing here
    * depends on it.
    */
  private[cli] def classifyProfile(
      requested: String,
      answer: Either[Throwable, Either[AnkiError, String]],
  ): ProfileCheck =
    answer match
      case Left(cause)        => ProfileCheck.Unreachable(requested, cause)
      case Right(Left(error)) => ProfileCheck.CouldNotAsk(requested, error)
      case Right(Right(open)) =>
        if open == requested then ProfileCheck.Confirmed(open)
        else ProfileCheck.Mismatch(requested, open)

  /** What to tell the person about the probe.
    *
    * PURE: takes data, returns lines, prints nothing — the same contract as [[Report]]. It
    * sits here rather than in [[Report]] only because that file is outside this change.
    *
    * BOTH PROFILE NAMES ARE QUOTED AND LABELLED BY ROLE. Quoting is not decoration: a
    * trailing space or a difference in case is otherwise invisible, and the message then
    * reads as baffling rather than as self-explanatory.
    */
  private[cli] def describeProfileCheck(check: ProfileCheck): Vector[String] =
    check match
      case ProfileCheck.Confirmed(open) =>
        // Aligned with inspect's `vault:` / `files:` value column, so a run leaves a record
        // of which collection it touched instead of a successful gate being invisible.
        Vector(s"profile:  '$open'")

      case ProfileCheck.Mismatch(requested, open) =>
        Vector(
          "REFUSED: Anki has a different collection open.",
          "",
          s"  requested (--profile):   '$requested'",
          s"  currently open in Anki:  '$open'",
          "",
          "Nothing was read from the collection and nothing was written.",
          "",
          "This tool does not switch profiles. Switching would close whatever collection you",
          "currently have open, possibly in the middle of a review. Either switch Anki to the",
          "profile you asked for, or re-run naming the profile that is actually open.",
        )

      case ProfileCheck.CouldNotAsk(requested, error) =>
        Vector(
          "REFUSED: could not determine which collection Anki has open.",
          "",
          s"  requested (--profile):   '$requested'",
          // AnkiError has no rendering of its own and `anki/Anki.scala` is outside this
          // change, so the error is shown as itself; writing a second rendering of that enum
          // here would create a second source of truth that drifts from the first.
          s"  Anki's answer:           ${error.toString}",
          "",
          "This is NOT a report that the wrong collection is open — the check itself could not",
          "be carried out, so the run is refused. Nothing was read from the collection and",
          "nothing was written.",
        )

      case ProfileCheck.Unreachable(requested, cause) =>
        Vector(
          s"REFUSED: could not reach AnkiConnect at ${AnkiConnectClient.DefaultUri}.",
          "",
          s"  requested (--profile):   '$requested'",
          // toString, never getMessage: getMessage can be null, and a line reading `null`
          // would present the absence of a message as though it were a diagnosis.
          s"  the failure:             ${cause.toString}",
          "",
          "Nothing was read from the collection and nothing was written. Candidates, none of",
          "which this tool can tell apart: Anki is not running; the AnkiConnect add-on is not",
          "installed or is disabled; something else is holding that port.",
        )

  /** Run `body` only against a collection whose name matches `expected`; otherwise refuse.
    *
    * A SCOPING COMBINATOR RATHER THAN A FIRST STEP IN A SEQUENCE, deliberately. This is the
    * only place that builds the HTTP client and constructs an [[AnkiConnectClient]], so no
    * expression in this file can hold a client without having gone through the check. A gate
    * written as the first statement of a for-comprehension is merely a documented gate: a
    * later edit that inserts a call above it, or branches around it, disarms it and nothing
    * fails.
    *
    * THE HONEST LIMITS OF THAT GUARANTEE, all three of which are real:
    *   - It is MODULE-LOCAL. Nothing stops a future file from constructing an
    *     [[AnkiConnectClient]] directly. Making that impossible needs a type only this check
    *     can mint, which would live in `anki/`.
    *   - The profile is CHECKED ONCE, AT THE START OF THE RUN. A profile switched inside
    *     Anki while a run is in progress is not detected.
    *   - ⚠️ IT BINDS A RUN TO A COLLECTION, NOT A VAULT TO A COLLECTION, and that is the
    *     consequential one. Naming another vault's path alongside the profile you use for this
    *     one passes this gate cleanly, and the run then flags and SUSPENDS every card the first
    *     vault put there — because nothing in a `src::` tag says which vault a note came from,
    *     so `Observer.observe` cannot tell the two apart. This check is the only thing standing
    *     in the way, and it was not built for that job. `README.md`, "ONE VAULT PER ANKI
    *     PROFILE".
    *
    * The check runs BEFORE the vault is read, so a refusal can make the strongest claim that
    * is actually true — the collection was neither read nor written — and so a mismatch is
    * not buried under pages of vault output.
    *
    * `--dry-run` gets no exemption: there is no plan without an observed collection, and a
    * dry run against the wrong one finds no `src::` tags and confidently proposes creating
    * every card in the vault.
    *
    * EXACTLY ONE `.attempt` OVER IO IN THIS FILE, and it is the one below, scoped to this
    * probe. Never wrap the run itself in one: a handler around the run would print "nothing
    * was written" after notes had already been written.
    */
  private[cli] def withVerifiedProfile(expected: String)(
      body: AnkiConnectClient[IO] => IO[ExitCode]
  ): IO[ExitCode] =
    EmberClientBuilder.default[IO].build.use(httpClient =>
      verifyThen(AnkiConnectClient[IO](httpClient, AnkiConnectClient.DefaultUri), expected)(body)
    )

  /** The gate's actual behaviour, over an ALREADY-BUILT client.
    *
    * SPLIT OUT SO IT CAN BE TESTED, and the split is deliberate about what it gives up. The
    * combinator above still owns client construction on the production path, so nothing in
    * this file can obtain a client without passing through the check. What this function
    * additionally allows is a caller that supplies its own client — which is exactly what a
    * test needs, since the in-process fake AnkiConnect server is reached through an http4s
    * client built from an `HttpApp` rather than from a socket.
    *
    * The alternative was leaving the guardrail unexercised, and an untested guardrail is a
    * claim rather than a guarantee: this is the one piece of code standing between the tool
    * and a real collection.
    */
  private[cli] def verifyThen(anki: AnkiConnectClient[IO], expected: String)(
      body: AnkiConnectClient[IO] => IO[ExitCode]
  ): IO[ExitCode] =
    for
      answer <- anki.activeProfile.value.attempt
      check = classifyProfile(expected, answer)
      _ <- describeProfileCheck(check).traverse_(IO.println)
      code <- check match
        case ProfileCheck.Confirmed(_)      => body(anki)
        case ProfileCheck.Mismatch(_, _)    => IO.pure(ExitCode(2))
        case ProfileCheck.CouldNotAsk(_, _) => IO.pure(ExitCode(2))
        case ProfileCheck.Unreachable(_, _) => IO.pure(ExitCode(2))
    yield code

  // ---------------------------------------------------------------- the sync run ----

  /** How a sync run ended.
    *
    * THREE KINDS OF FAILURE MEET IN THIS FILE AND MUST NOT BE CONFLATED, which is what this
    * enum exists to carry:
    *   - a [[PlanError]] says the vault and the collection are not internally consistent —
    *     two sources deriving one card identity, or two Anki notes claiming one. Nothing may
    *     be written at all.
    *   - an [[AnkiError]] says Anki answered and refused ONE action. [[Executor]] records it
    *     and carries on, so one unwritable card does not abandon the other forty-nine.
    *   - a `Throwable` in `IO` says Anki could not be reached, and aborts the run. It is
    *     deliberately not an `AnkiError`: fifty identical connection failures would describe
    *     a dead collection as forty-nine ordinary problems.
    *
    * The pipeline therefore composes in plain `IO`, opening each `EitherT` with `.value` at
    * its OWN position and matching it there. `Planner.plan`'s `Left` is a
    * `Vector[PlanError]`, a different type from `AnkiError` and a vector rather than a
    * scalar, so any single error channel would require exactly the conflation above. And the
    * two `AnkiError` `Left`s mean OPPOSITE things about whether anything was written — the
    * same `AnkiError.Remote` value arises from a read and from a write — so it is POSITION,
    * not the error value, that distinguishes them. One trailing handler could not.
    *
    * "Was anything written?" is the claim every one of these messages makes. Attaching it to
    * a case means the compiler carries it, and means a refused plan has nowhere to be
    * coerced into an empty [[Plan]] — which matters, because `Report.plan` prints "nothing to
    * do" for an empty action vector and would report success over a broken vault.
    */
  private[cli] enum SyncOutcome:
    /** The collection does not have the note types this run is about to write to.
      *
      * A REFUSAL BEFORE THE PLAN IS COMPUTED, not a failure per note afterwards. Two reasons.
      * A missing note type makes every `addNote` fail, so the plan would be printed as though
      * it would work and then fail forty-nine times over. And a note type that exists but lacks
      * a field is WORSE than that: Anki reports an unrecognised field name on create as "cannot
      * create note because it is empty" — the same message a genuinely empty note gets — and on
      * update as no error whatsoever.
      *
      * IT APPLIES TO `--dry-run` TOO. A dry run against a collection that cannot be written to
      * would print a plan nobody can apply; saying so is the more useful answer.
      */
    case NoteTypesNotReady(problems: Vector[NoteTypeProblem])

    /** This tool's own note type definitions could not be read off the classpath.
      *
      * SEPARATE FROM [[NoteTypesNotReady]] because it is a different fact with a different
      * addressee: nothing is wrong with the collection, and nothing the person can do to their
      * vault or their profile would fix it. It is a build or packaging fault.
      */
    case NoteTypeDefinitionsUnreadable(errors: Vector[AssetError])

    case CouldNotObserve(error: AnkiError)
    case RefusedInconsistent(errors: Vector[PlanError])
    case PlannedOnly(plan: Plan)

    /** `report` CARRIES BOTH WHAT FAILED AND WHAT WAS DEFERRED, and they are not the same
      * fact. A failure is an action Anki refused; a deferral is an action this run was not
      * asked to perform. Collapsing them would either tell someone their collection is
      * misbehaving when it is not, or hide work that is outstanding.
      */
    case Applied(plan: Plan, report: ExecutionReport)

    /** Carries ONLY the error, deliberately not the plan, so that no renderer can be tempted
      * to compute an "N of M applied" figure that nobody knows.
      */
    case AbortedDuringExecution(error: AnkiError)

  /** Reconcile the vault against the collection the gate has already confirmed.
    *
    * The vault is read FIRST: a filesystem failure then aborts before the collection is read
    * at all, and a wrong `--deck-root` or vault path shows up as `notes: 0` on screen before
    * a plan full of orphan flags rather than after it.
    */
  private def sync(
      vault: VaultRoot,
      deckRoot: DeckPath,
      deckShape: DeckShape,
      dryRun: Boolean,
      retypePolicy: RetypePolicy,
      anki: AnkiConnectClient[IO],
  ): IO[ExitCode] =
    for
      files <- readVault(vault)
      index = VaultWalker.scan(files, deckRoot, deckShape)
      // Aligned with inspect's value column. The gate has already printed `profile:` above.
      _ <- IO.println(s"vault:    $vault")
      _ <- IO.println(s"files:    ${files.size}")
      _ <- IO.println(s"notes:    ${index.scan.specs.size}")
      outcome <- observeAndApply(index, deckRoot, dryRun, retypePolicy, anki)
      result = verdict(outcome)
      _ <- (describeSyncOutcome(outcome) ++ describeVerdict(result)).traverse_(IO.println)
    yield exitCodeFor(result)

  /** Check the note types, then observe the collection, plan against it, and — unless this is a
    * dry run — apply it.
    *
    * THE NOTE TYPE CHECK IS FIRST, AND IT IS A REFUSAL RATHER THAN A REPAIR. A collection this
    * tool cannot write to is refused before its notes are enumerated, so the message says what
    * to do instead of a plan being printed that would fail on every action. `--dry-run` gets no
    * exemption, for the same reason the profile gate gives it none: a plan computed against a
    * collection nothing can be written to describes a run that cannot happen.
    *
    * THE PLAN IS PRINTED BEFORE IT IS EXECUTED, on the applying path as well as the dry-run
    * one. It is the only mitigation available here for "the process died mid-write": if the
    * run dies on an unhandled transport failure, the person still has on screen what was in
    * flight. Two consequences, named rather than left for a reader to discover:
    * `describeSyncOutcome` renders only the TAIL of a run rather than the whole of it, and
    * on a dry run the banner appears twice — once before the plan and once after.
    *
    * There is no pre-screen with `Planner.checkUnique` before observing. `Planner.plan`
    * deliberately checks both sides and returns their collisions together, so an early
    * vault-side screen would look like a helpful short-circuit while suppressing every
    * Anki-side collision until the vault happened to be clean.
    */
  private[cli] def observeAndApply(
      index: VaultIndex,
      deckRoot: DeckPath,
      dryRun: Boolean,
      retypePolicy: RetypePolicy,
      anki: AnkiConnectClient[IO],
  ): IO[SyncOutcome] =
    NoteTypeAssets.all match
      case Left(errors) => IO.pure(SyncOutcome.NoteTypeDefinitionsUnreadable(errors))
      case Right(assets) =>
        // THE PREFLIGHT COMES FIRST, before the collection is enumerated and long before
        // anything is written. It never creates anything: `install-note-types` is the command
        // that does that, explicitly and on its own.
        //
        // IT IS ALSO WHAT MAKES A MIGRATION'S ORDERING SAFE. A retype names a note type this
        // tool owns as its destination, and this preflight has already refused the whole run
        // if any of the five is absent — so by the time `Executor` moves a note, the type it
        // is moving to demonstrably exists and declares every field being written. Without
        // that, a migration run against a collection where `install-note-types` had not yet
        // been run would blank each note's fields and then have nowhere to put them back.
        NoteTypeInstaller.readiness[Refused](anki, assets).value.flatMap {
          case Left(error)                    => IO.pure(SyncOutcome.CouldNotObserve(error))
          case Right(problems) if problems.nonEmpty =>
            IO.pure(SyncOutcome.NoteTypesNotReady(problems))
          case Right(_) => reconcile(index, deckRoot, dryRun, retypePolicy, anki)
        }

  /** Observe, plan, and — unless this is a dry run — apply. The note types have already been
    * checked by the caller; nothing here re-checks them.
    */
  private def reconcile(
      index: VaultIndex,
      deckRoot: DeckPath,
      dryRun: Boolean,
      retypePolicy: RetypePolicy,
      anki: AnkiConnectClient[IO],
  ): IO[SyncOutcome] =
    Observer.observe[Refused](anki).value.flatMap {
      case Left(error) => IO.pure(SyncOutcome.CouldNotObserve(error))
      case Right(observed) =>
        Planner.plan(index.scan, observed, index.deckOf(deckRoot), Planner.newNoteFor) match
          case Left(errors) => IO.pure(SyncOutcome.RefusedInconsistent(errors))
          case Right(plan) =>
            (if dryRun then IO.println("DRY RUN — the plan below will NOT be applied.")
             else IO.unit) *>
              Report.plan(plan, retypePolicy).traverse_(IO.println) *>
              (if dryRun then
                 // THE DRY RUN ASKS THE SAME QUESTION THE REAL RUN ASKS, and this is the whole
                 // reason `Executor.preview` exists. `Report.plan` above renders the POLICY
                 // half of the retype decision and cannot reach the other half, which needs the
                 // note types read out of the collection — so `--dry-run --migrate-note-types`
                 // used to print `1 move to another note type` and `result: OK` over a move the
                 // real run then refused.
                 //
                 // A FAILED READ ENDS THE DRY RUN rather than being reported as an empty
                 // preview. Nothing has been written — a dry run writes nothing by
                 // construction — so ending here costs the person only the answer they came
                 // for, which is better than a preview that quietly stopped checking.
                 Executor.preview[Refused](plan, anki, retypePolicy).value.flatMap {
                   case Left(error) => IO.pure(SyncOutcome.AbortedDuringExecution(error))
                   case Right(verdicts) =>
                     Report.retypePreview(verdicts).traverse_(IO.println).as(
                       SyncOutcome.PlannedOnly(plan)
                     )
                 }
               else
                 Executor.run[Refused](plan, anki, retypePolicy).value.map {
                   case Left(error)   => SyncOutcome.AbortedDuringExecution(error)
                   case Right(report) => SyncOutcome.Applied(plan, report)
                 })
    }

  /** What to tell the person about how the run ended.
    *
    * PURE: takes data, returns lines, prints nothing — the same contract as [[Report]], and
    * it sits here rather than there only because that file is outside this change.
    *
    * THIS RENDERS THE CASE-SPECIFIC BLOCK ONLY, not the whole tail of a run. The caller
    * appends [[describeVerdict]] after it, which is where the one-line summary and the exit
    * code both come from.
    */
  private[cli] def describeSyncOutcome(outcome: SyncOutcome): Vector[String] =
    outcome match
      case SyncOutcome.NoteTypesNotReady(problems) =>
        Report.noteTypesNotReady(problems)

      case SyncOutcome.NoteTypeDefinitionsUnreadable(errors) =>
        Vector(
          "",
          "REFUSED: this tool's own note type definitions could not be read.",
          "",
        ) ++ errors.map("  " + _.describe) ++ Vector(
          "",
          "Nothing was written. These files ship with the tool, so this is a build or packaging",
          "fault rather than anything about your vault or your collection.",
        )

      case SyncOutcome.CouldNotObserve(error) =>
        Vector(
          "",
          "REFUSED: could not read the collection.",
          "",
          // AnkiError has no rendering of its own and `anki/Anki.scala` is outside this
          // change, so the error is shown as itself; writing a second rendering of that enum
          // here would create a second source of truth that drifts from the first.
          s"  Anki's answer:  ${error.toString}",
          "",
          "Nothing was written. This is NOT a report that the collection is empty — the read",
          "itself failed, so what Anki holds is unknown and no plan can be computed against it.",
        )

      case SyncOutcome.RefusedInconsistent(errors) =>
        // "THE COLLECTION WAS READ" and not the gate's "nothing was read from the
        // collection": this inconsistency is only discoverable AFTER observing, so the
        // gate's wording would be false here.
        Vector("", "REFUSED: the vault and the collection are not internally consistent.", "") ++
          errors.map("  " + _.describe) ++
          Vector(
            "",
            "The collection was read; nothing was written. Each line above names both sides of",
            "one collision, so both can be opened and fixed before re-running.",
          )

      case SyncOutcome.PlannedOnly(_) =>
        Vector("", "DRY RUN — nothing was written.")

      case SyncOutcome.Applied(plan, ExecutionReport(failures, deferred, applied)) =>
        // `attempted:` and `failed:`, never "applied N of M". `Executor.runOne` traverses an
        // Update's changes, so a failure on the first change abandons the rest of THAT action
        // while recording one failure — "applied" would assert more than the code guarantees.
        //
        // DEFERRED ACTIONS ARE SUBTRACTED FROM `attempted:` rather than counted in it. They
        // were not attempted; printing them as attempts and then as zero failures would say
        // they succeeded.
        val counts = Vector(
          "",
          s"attempted: ${plan.actions.size - deferred.size}",
          s"failed:    ${failures.size}",
        ) ++ Option.when(deferred.nonEmpty)(s"deferred:  ${deferred.size}")
        val failureLines =
          if failures.isEmpty then Vector.empty
          else
            Vector("", "SOME ACTIONS FAILED.", "failures") ++
              failures.map { f =>
                // Both halves of the key, as `PlanError.describe` already renders one:
                // `HeadingPath.render` joins heading segments only and is file-independent,
                // so two files sharing a heading chain would otherwise produce two identical
                // and indistinguishable lines.
                val k = keyOf(f.action)
                s"  '${k.path.render}' (note '${k.noteId.value}'): ${f.error.toString}"
              } ++
              Vector(
                "",
                "The failed actions were not recorded as done: a re-run recomputes the plan from the vault",
                "and the collection and attempts them again. Whether that succeeds depends on why each one",
                "failed — an action this tool cannot yet carry out will fail again.",
              )
        counts ++ failureLines ++ Report.appliedRetypes(applied) ++ Report.deferredRetypes(deferred)

      case SyncOutcome.AbortedDuringExecution(error) =>
        Vector(
          "",
          "STOPPED EARLY: the collection refused an action in a way that ended the run.",
          "",
          s"  Anki's answer:  ${error.toString}",
          "",
          "WRITING MAY ALREADY HAVE BEGUN. Part of the plan may have been applied and part not, and",
          "this tool does not know which. A re-run recomputes the plan from what the vault and the",
          "collection then hold, so whatever was already applied is not applied twice.",
        )

  /** The identity of the card an action is about.
    *
    * Only the KEY, with no verb. The verb would have to duplicate `Report.kindOf`'s wording,
    * which is private — and a second copy of it here is exactly the second source of truth
    * this file already refuses to create for `AnkiError`. Nothing is duplicated, so nothing
    * can drift: the action counts `Report.plan` printed just above carry the verbs, and
    * `AnkiError.Remote` and `AnkiError.UnsupportedOperation` name their own operation.
    *
    * The right home for this rendering is `Report.scala`; it is here only because that file
    * is outside this change.
    */
  private def keyOf(a: SyncAction): CardKey = a match
    case SyncAction.Create(key, _)                => key
    case SyncAction.Update(key, _, _)             => key
    case SyncAction.Retype(key, _, _, _, _, _, _, _) => key
    case SyncAction.Flag(key, _)                  => key
    case SyncAction.Unflag(key, _)                => key

  /** How the run is to be judged: ONE value, read by the exit code and by the last line on
    * screen alike.
    *
    * A PROJECTION OF [[SyncOutcome]], not a second copy of it. Its `reasons` are literally
    * the facts the exit code was computed from, so the summary a person reads and the number
    * a script reads cannot disagree — [[sync]] derives this once and hands the same value to
    * [[describeVerdict]] and to [[exitCodeFor]]. Two independent readings of the same fields
    * could, and did, differ in appearance: an applied run carrying build failures ended on
    * screen with `failed:    0` while exiting non-zero.
    *
    * THE GUARANTEE IS NARROWER THAN THAT DIAGNOSIS MIGHT SUGGEST, so state it exactly: the
    * FINAL line and the exit code cannot disagree, because both are derived from this one
    * value. Earlier lines still can, and the `failed:    0` above is still printed — this
    * appends a verdict rather than removing the contradiction above it.
    *
    * EXACTLY THREE CASES, ONE PER EXIT CODE, and no fourth. A fourth case would be a
    * distinction the exit code cannot carry, which is to say it would be [[SyncOutcome]]
    * again — and [[SyncOutcome]] already exists for that, one layer up.
    */
  private[cli] enum Verdict:
    case Clean(note: String)
    case Problems(reasons: Vector[String])

    /** Named `Refusal` and not `Refused`, because `Refused[A]` above is this file's name for
      * the effect [[AnkiConnectClient]] speaks, and two unrelated meanings of one word in one
      * file is a trap for a later reader.
      */
    case Refusal(reason: String)

  /** The facts about a plan that make a run non-clean.
    *
    * THE ORPHAN CLAUSE IS WRITTEN OUT rather than left to coincide with a build failure.
    * Today the two always travel together — a scan is partial exactly when a file was
    * unreadable, and that failure reaches `plan.failures` — so today the coincidence gives
    * the right number. `RECONCILER-SHAPE.md` §5 names a second cause of partiality, a run
    * scoped to a subset, which would carry no failures at all; on that day "could not look
    * for orphans" would otherwise exit clean.
    *
    * The suppression reason is deliberately not interpolated: `Report.plan` already prints
    * `orphans NOT computed: <why>` and owns that wording.
    */

  private def problemsIn(plan: Plan): Vector[String] =
    val buildFailures =
      if plan.failures.isEmpty then Vector.empty
      else Vector(s"${Report.quantify(plan.failures.size, "card")} could not be built from the vault")

    val orphanNote = plan.orphanInference match
      case OrphanInference.Computed => Vector.empty
      case OrphanInference.SuppressedIncompleteScan(_) =>
        Vector(
          "orphans were not computed — this run could not look, which is not the same as " +
            "finding none"
        )

    buildFailures ++ orphanNote

  private[cli] def verdict(outcome: SyncOutcome): Verdict = outcome match
    case SyncOutcome.NoteTypesNotReady(problems) =>
      Verdict.Refusal(
        s"${problems.size} of this tool's note types are missing or incomplete in this collection"
      )

    case SyncOutcome.NoteTypeDefinitionsUnreadable(_) =>
      Verdict.Refusal("this tool's own note type definitions could not be read")

    case SyncOutcome.CouldNotObserve(_) =>
      Verdict.Refusal("the collection could not be read")

    case SyncOutcome.RefusedInconsistent(errors) =>
      Verdict.Refusal(s"${errors.size} identity collisions between the vault and the collection")

    case SyncOutcome.PlannedOnly(plan) =>
      val reasons = problemsIn(plan)
      if reasons.nonEmpty then Verdict.Problems(reasons)
      else
        Verdict.Clean(
          if plan.actions.isEmpty then "dry run; the plan is empty"
          else s"dry run; ${plan.actions.size} actions are outstanding"
        )

    case SyncOutcome.Applied(plan, ExecutionReport(failures, deferred, _)) =>
      // DEFERRED WORK MAKES A RUN NON-CLEAN, deliberately, even though nothing went wrong.
      // The precedent this departs from is the dry run, which is clean with actions
      // outstanding — but a dry run's work is outstanding until the NEXT ordinary run, whereas
      // this work is outstanding until somebody passes a flag no ordinary run will pass. A
      // green result would say the collection matches the vault, and it does not.
      val reasons =
        (if failures.isEmpty then Vector.empty
         else Vector(s"${Report.quantify(failures.size, "action")} failed — listed above")) ++
          (if deferred.isEmpty then Vector.empty
           else
             Vector(
               s"${Report.quantify(deferred.size, "note")} ${if deferred.size == 1 then "is" else "are"} " +
                 "on the wrong note type and " +
                 (if deferred.size == 1 then "was" else "were") + " left alone at your request " +
                 "(--no-migrate-note-types) — drop the flag to move them"
             )) ++ problemsIn(plan)
      if reasons.nonEmpty then Verdict.Problems(reasons)
      else
        Verdict.Clean(
          if plan.actions.isEmpty then "the plan was empty; the collection already matched the vault"
          else s"all ${plan.actions.size} actions were applied"
        )

    case SyncOutcome.AbortedDuringExecution(_) =>
      Verdict.Problems(Vector("the run stopped early; an unknown part of the plan was applied"))

  /** PURE: takes data, returns lines, prints nothing. The marker sits in the same value
    * column as `profile:` / `vault:` / `files:` / `cards:`.
    */
  private[cli] def describeVerdict(v: Verdict): Vector[String] = v match
    case Verdict.Clean(note) =>
      Vector("", s"result:   OK — $note")
    case Verdict.Problems(reasons) =>
      Vector("", "result:   PROBLEMS — this run is not clean:") ++ reasons.map("  " + _)
    case Verdict.Refusal(reason) =>
      Vector("", s"result:   REFUSED — nothing was written: $reason")

  /** THE EXIT-CODE CONTRACT OF THIS TOOL, stated once:
    *
    *   - `ExitCode.Success` (0) — it ran and nothing is wrong. A dry run with pending actions
    *     is 0: pending work is not a failure, and `inspect` already sets that precedent.
    *   - `ExitCode.Error` (1) — it ran and something is wrong: some actions failed, some
    *     cards could not be built from the vault, orphans could not be computed, or the run
    *     stopped early.
    *   - `ExitCode(2)` — the run REFUSED to do its job and NOTHING WAS WRITTEN: the three
    *     profile refusals in [[withVerifiedProfile]], every refusal in [[withChosenVault]],
    *     plus `CouldNotObserve` and `RefusedInconsistent`.
    *
    * `AbortedDuringExecution` is 1 AND NOT 2. That is the most consequential number here: 2
    * asserts that nothing was written, and on that path it is false.
    *
    * ONE CASE IS NOT IN THAT LIST, and it is the exception to "1 means it ran": a command
    * line REFUSED BY THE PARSER exits **1**, printed to **stderr**, without the run having
    * started at all. That is `CommandIOApp`'s own behaviour and not a choice made here
    * (measured against decline-effect 2.6.2). So "that is not a vault" reaches a script as 1
    * when `--vault-path` names it and as 2 when a registry row does. This split is not new
    * with the flag — before `--vault-path` existed the vault was a positional argument and a
    * bad one exited 1 the same way. Whether to unify it is open, and unifying means moving
    * the check out of `Cli` and giving up failing before anything is read.
    *
    * There are no codes 3 or 4 because nothing consumes the exit code yet; when a consumer
    * appears, the split that earns itself is transient (`Unreachable`) against permanent
    * (`Mismatch`, `RefusedInconsistent`), so the change is `2 -> {2,3}` rather than a
    * reshuffle of what already exists.
    */
  private[cli] def exitCodeFor(v: Verdict): ExitCode = v match
    case Verdict.Clean(_)    => ExitCode.Success
    case Verdict.Problems(_) => ExitCode.Error
    case Verdict.Refusal(_)  => ExitCode(2)

  /** Non-zero when anything was wrong, so a failure is visible to a script and not only to
    * a reader. Duplicate keys are fatal: nothing would be written.
    */
  private def exitCodeFor(index: VaultIndex): ExitCode =
    if Planner.checkUnique(index.scan.specs).nonEmpty then ExitCode(2)
    else if index.scan.failures.nonEmpty then ExitCode.Error
    else ExitCode.Success

  private def readVault(root: VaultRoot): IO[Vector[VaultFile]] =
    IO.blocking {
      val dir = root.path
      Files
        .walk(dir)
        .iterator
        .asScala
        // Obsidian's own configuration lives under the very directory that marks this as a
        // vault (see [[VaultRoot]]). It holds no cards, and the name is now referenced from
        // one place rather than written out twice.
        .filter(p =>
          p.toString.endsWith(".md") && !p.toString.contains(s"/${VaultRoot.MarkerDirectory}/")
        )
        .toVector
        .map(p => VaultFile(dir.relativize(p).toString, Files.readString(p)))
        .sortBy(_.relativePath)
    }
