package obsidiananki.cli

import cats.data.EitherT
import cats.effect.{ExitCode, IO}
import cats.syntax.all.*
import com.monovore.decline.Opts
import com.monovore.decline.effect.CommandIOApp
import obsidiananki.anki.{AnkiConnectClient, AnkiError, DeckPath}
import obsidiananki.extract.{VaultFile, VaultIndex, VaultWalker}
import obsidiananki.model.CardKey
import obsidiananki.plan.{
  ExecutionFailure,
  Executor,
  Observer,
  OrphanInference,
  Plan,
  PlanError,
  Planner,
  SyncAction,
}
import org.http4s.ember.client.EmberClientBuilder
import java.nio.file.{Files, Path}
import scala.jdk.CollectionConverters.*

/** The imperative shell.
  *
  * Everything below the CLI is pure: reading files and printing lines happen here and
  * nowhere else, which is why the walk, the extractor, the planner and the reporter can all
  * be tested without a filesystem, a network or captured output.
  */
object Main
    extends CommandIOApp(
      name = "obsidian-anki-sync",
      header = "Sync marked headings in an Obsidian vault into Anki.",
    ):

  /** The effect [[AnkiConnectClient]] speaks: an `IO` that may carry a REFUSAL from Anki.
    *
    * Named for what the `Left` means rather than for its shape: Anki answered and said no.
    * Being unable to reach Anki at all is not this — it stays a `Throwable` in `IO`.
    */
  private type Refused[A] = EitherT[IO, AnkiError, A]

  def main: Opts[IO[ExitCode]] = Cli.command.map {
    case Command.Inspect(vault, deckRoot, verbose) => inspect(vault, deckRoot, verbose)
    case Command.Sync(vault, profile, deckRoot, dryRun) =>
      withVerifiedProfile(profile)(sync(vault, deckRoot, dryRun, _))
  }

  /** Read the vault and say what it holds. Touches no collection. */
  private def inspect(vault: Path, deckRoot: DeckPath, verbose: Boolean): IO[ExitCode] =
    for
      files <- readVault(vault)
      index = VaultWalker.scan(files, deckRoot)
      _ <- IO.println(s"vault:    $vault")
      _ <- IO.println(s"files:    ${files.size}")
      _ <- Report.inspect(index, verbose).traverse_(IO.println)
    yield exitCodeFor(index)

  // ------------------------------------------------------------- the profile gate ----

  /** How the profile probe ended.
    *
    * CLOSED, with no fifth "assume it is fine" case, and BOTH mappings over it match every
    * case rather than falling back on a wildcard: `describeProfileCheck`, and
    * `withVerifiedProfile`'s exit-code branch.
    *
    * WHAT THAT BUYS, AND WHAT IT DOES NOT. A case added later cannot silently acquire a
    * PERMISSION: only `Confirmed` reaches `body`, so anything new refuses the run. But it
    * does NOT fail to compile. This build sets `-deprecation -feature -Wunused:all` and NOT
    * `-Xfatal-warnings`, so a non-exhaustive match is a warning and the compiler still exits
    * 0 — a fifth case would build, and raise a `MatchError` the first time someone ran
    * `sync`.
    *
    * An earlier draft of this comment asserted the compiler refuses it. It does not, and
    * that was established by compiling a throwaway file to check rather than by reading.
    * Making the claim true would mean adding `-Xfatal-warnings`, which is a decision about
    * the whole build rather than about this enum.
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
    * THE HONEST LIMITS OF THAT GUARANTEE, both of which are real:
    *   - It is MODULE-LOCAL. Nothing stops a future file from constructing an
    *     [[AnkiConnectClient]] directly. Making that impossible needs a type only this check
    *     can mint, which would live in `anki/`.
    *   - The profile is CHECKED ONCE, AT THE START OF THE RUN. A profile switched inside
    *     Anki while a run is in progress is not detected.
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
    case CouldNotObserve(error: AnkiError)
    case RefusedInconsistent(errors: Vector[PlanError])
    case PlannedOnly(plan: Plan)
    case Applied(plan: Plan, failures: Vector[ExecutionFailure])

    /** Carries ONLY the error, deliberately not the plan, so that no renderer can be tempted
      * to compute an "N of M applied" figure that nobody knows.
      */
    case AbortedDuringExecution(error: AnkiError)

  /** Reconcile the vault against the collection the gate has already confirmed.
    *
    * The vault is read FIRST: a filesystem failure then aborts before the collection is read
    * at all, and a wrong `--deck-root` or vault path shows up as `cards: 0` on screen before
    * a plan full of orphan flags rather than after it.
    */
  private def sync(
      vault: Path,
      deckRoot: DeckPath,
      dryRun: Boolean,
      anki: AnkiConnectClient[IO],
  ): IO[ExitCode] =
    for
      files <- readVault(vault)
      index = VaultWalker.scan(files, deckRoot)
      // Aligned with inspect's value column. The gate has already printed `profile:` above.
      _ <- IO.println(s"vault:    $vault")
      _ <- IO.println(s"files:    ${files.size}")
      _ <- IO.println(s"cards:    ${index.scan.specs.size}")
      outcome <- observeAndApply(index, deckRoot, dryRun, anki)
      result = verdict(outcome)
      _ <- (describeSyncOutcome(outcome) ++ describeVerdict(result)).traverse_(IO.println)
    yield exitCodeFor(result)

  /** Observe the collection, plan against it, and — unless this is a dry run — apply it.
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
              Report.plan(plan).traverse_(IO.println) *>
              (if dryRun then IO.pure(SyncOutcome.PlannedOnly(plan))
               else
                 Executor.run[Refused](plan, anki).value.map {
                   case Left(error)     => SyncOutcome.AbortedDuringExecution(error)
                   case Right(failures) => SyncOutcome.Applied(plan, failures)
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

      case SyncOutcome.Applied(plan, failures) =>
        // `attempted:` and `failed:`, never "applied N of M". `Executor.runOne` traverses an
        // Update's changes, so a failure on the first change abandons the rest of THAT action
        // while recording one failure — "applied" would assert more than the code guarantees.
        val counts = Vector(
          "",
          s"attempted: ${plan.actions.size}",
          s"failed:    ${failures.size}",
        )
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
        counts ++ failureLines

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
    case SyncAction.Create(key, _)       => key
    case SyncAction.Update(key, _, _)    => key
    case SyncAction.Retype(key, _, _, _) => key
    case SyncAction.Flag(key, _)         => key
    case SyncAction.Unflag(key, _)       => key

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
      else Vector(s"${plan.failures.size} cards could not be built from the vault")

    val orphanNote = plan.orphanInference match
      case OrphanInference.Computed => Vector.empty
      case OrphanInference.SuppressedIncompleteScan(_) =>
        Vector(
          "orphans were not computed — this run could not look, which is not the same as " +
            "finding none"
        )

    buildFailures ++ orphanNote

  private[cli] def verdict(outcome: SyncOutcome): Verdict = outcome match
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

    case SyncOutcome.Applied(plan, failures) =>
      val reasons =
        (if failures.isEmpty then Vector.empty
         else Vector(s"${failures.size} actions failed — listed above")) ++ problemsIn(plan)
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
    *     profile refusals in [[withVerifiedProfile]], plus `CouldNotObserve` and
    *     `RefusedInconsistent`.
    *
    * `AbortedDuringExecution` is 1 AND NOT 2. That is the most consequential number here: 2
    * asserts that nothing was written, and on that path it is false.
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

  private def readVault(root: Path): IO[Vector[VaultFile]] =
    IO.blocking {
      Files
        .walk(root)
        .iterator
        .asScala
        .filter(p => p.toString.endsWith(".md") && !p.toString.contains("/.obsidian/"))
        .toVector
        .map(p => VaultFile(root.relativize(p).toString, Files.readString(p)))
        .sortBy(_.relativePath)
    }
