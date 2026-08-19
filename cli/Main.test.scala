package obsidiananki.cli

import cats.data.NonEmptyVector
import cats.effect.{ExitCode, IO}
import cats.effect.unsafe.implicits.global
import obsidiananki.anki.*
import obsidiananki.extract.{VaultFile, VaultWalker}
import obsidiananki.model.*
import obsidiananki.plan.*
import org.http4s.Uri
import org.http4s.client.Client

/** The shell, tested — including the guardrail, which had none.
  *
  * WHY THIS FILE EXISTS AND WHY IT IS LATE. The sync wiring was written by a group of agents
  * scoped to `Main.scala` alone, and a test file is another file, so three slices shipped with
  * no coverage at all. The constraint was meant to stop them wandering into other modules; it
  * silently also stopped them writing tests. This is the code path between the tool and a real
  * Anki collection, so that is not a gap worth leaving.
  *
  * THESE ARE TESTS-AFTER, and they carry the weakness that implies: they were written against
  * code that already existed, so they cannot have failed first and passing proves less than it
  * would have. The substitute is mutation — every behaviour asserted here has been broken
  * deliberately to confirm the matching test dies. A test that survives its mutant is not
  * evidence, and was rewritten rather than kept.
  *
  * No socket and no running Anki: `Client.fromHttpApp` wires the real interpreter to
  * [[FakeAnkiConnect]], which reproduces the behaviours observed on a live collection.
  */
class MainTest extends munit.FunSuite:

  def fixture(profile: String = "claude-POC-test"): (FakeAnkiConnect.State, AnkiConnectClient[IO]) =
    val state = FakeAnkiConnect.State()
    state.profile = profile
    val client = Client.fromHttpApp(FakeAnkiConnect.app(state))
    (state, AnkiConnectClient[IO](client, Uri.unsafeFromString("http://localhost:8765")))

  val deckRoot: DeckPath = DeckPath(NonEmptyVector.one("Obsidian"))

  def vaultOf(files: (String, String)*) =
    VaultWalker.scan(files.toVector.map(VaultFile.apply.tupled), deckRoot)

  def note(id: String, body: String) = s"---\nid: $id\n---\n\n$body"

  val oneCard: String =
    note("n1", "# Coupling\n\n## Temporal coupling #flashcard/1way\n\nAll parties up at once.\n")

  // ================================================ the profile gate ====

  /** THE GUARDRAIL, asserted on its EFFECT rather than on its message.
    *
    * The point is not that a refusal is printed. It is that the body never runs — so nothing
    * reads or writes the collection. A test that only checked the exit code would pass while
    * the body had already run and written notes.
    */
  test("a mismatched profile refuses WITHOUT running the body") {
    val (_, anki) = fixture(profile = "claude-POC-test")
    var bodyRan   = false

    val code = Main
      .verifyThen(anki, "POC-test") { _ =>
        bodyRan = true
        IO.pure(ExitCode.Success)
      }
      .unsafeRunSync()

    assert(!bodyRan, "the body ran against a collection the person did not name")
    assertEquals(code, ExitCode(2))
  }

  test("a matching profile runs the body and returns its exit code") {
    val (_, anki) = fixture(profile = "POC-test")
    var bodyRan   = false

    val code = Main
      .verifyThen(anki, "POC-test") { _ =>
        bodyRan = true
        IO.pure(ExitCode.Error)
      }
      .unsafeRunSync()

    assert(bodyRan, "a correctly named collection was refused")
    assertEquals(code, ExitCode.Error, "the body's exit code was discarded")
  }

  /** Anki is deliberately case-sensitive here. The near miss is real: this machine has a
    * profile named `claude-POC-test` beside one named `POC-test`.
    */
  test("profile matching does not fold case or trim beyond what the CLI already did") {
    val (_, anki) = fixture(profile = "POC-Test")
    var bodyRan   = false
    Main.verifyThen(anki, "POC-test")(_ => IO { bodyRan = true; ExitCode.Success }).unsafeRunSync()
    assert(!bodyRan, "'POC-Test' was accepted for 'POC-test'")
  }

  // ---- the classifier, which is where the three failures are kept apart ----

  test("the three probe failures are three different facts") {
    val boom = RuntimeException("connection refused")
    assertEquals(
      Main.classifyProfile("p", Left(boom)),
      Main.ProfileCheck.Unreachable("p", boom),
    )
    assertEquals(
      Main.classifyProfile("p", Right(Left(AnkiError.Remote("getActiveProfile", "nope")))),
      Main.ProfileCheck.CouldNotAsk("p", AnkiError.Remote("getActiveProfile", "nope")),
    )
    assertEquals(Main.classifyProfile("p", Right(Right("q"))), Main.ProfileCheck.Mismatch("p", "q"))
    assertEquals(Main.classifyProfile("p", Right(Right("p"))), Main.ProfileCheck.Confirmed("p"))
  }

  /** Reporting what Anki said, not what was asked for. Echoing the argument back would make a
    * confirming line that is true by construction and therefore worthless.
    */
  test("the confirming line names the profile ANKI reported") {
    val lines = Main.describeProfileCheck(Main.ProfileCheck.Confirmed("claude-POC-test"))
    assert(lines.mkString.contains("claude-POC-test"), lines.mkString)
  }

  test("a mismatch names BOTH profiles, so the message is self-explanatory") {
    val lines = Main.describeProfileCheck(Main.ProfileCheck.Mismatch("POC-test", "User 1")).mkString("\n")
    assert(lines.contains("POC-test"), lines)
    assert(lines.contains("User 1"), lines)
    assert(lines.contains("nothing was written"), s"the refusal does not say nothing was written:\n$lines")
  }

  /** "Could not look" must never be rendered as "the wrong collection is open". They call for
    * different actions from the person reading.
    */
  test("could-not-ask does NOT claim the wrong collection is open") {
    val lines = Main
      .describeProfileCheck(Main.ProfileCheck.CouldNotAsk("POC-test", AnkiError.Remote("x", "y")))
      .mkString("\n")
    assert(lines.contains("NOT"), s"the message does not disclaim a mismatch:\n$lines")
  }

  // ================================================ the run ====

  def runSync(dryRun: Boolean, files: (String, String)*): (FakeAnkiConnect.State, Main.SyncOutcome) =
    val (state, anki) = fixture()
    val outcome = Main
      .observeAndApply(vaultOf(files*), deckRoot, dryRun, anki)
      .unsafeRunSync()
    (state, outcome)

  /** DRY RUN MUST NOT WRITE, asserted on the collection rather than on the outcome value.
    * Checking only that the outcome says `PlannedOnly` would pass while notes were created.
    */
  test("a dry run writes NOTHING to the collection") {
    val (state, outcome) = runSync(dryRun = true, "A.md" -> oneCard)
    assertEquals(state.notes.size, 0, "a dry run created notes")
    outcome match
      case Main.SyncOutcome.PlannedOnly(plan) => assertEquals(plan.actions.size, 1)
      case other                              => fail(s"expected PlannedOnly, got $other")
  }

  test("a real run creates the card, tagged with its identity and its content hash") {
    val (state, outcome) = runSync(dryRun = false, "A.md" -> oneCard)
    assertEquals(state.notes.size, 1)
    val tags = state.notes.values.head.tags
    assert(tags.exists(_.startsWith("src::")), s"no identity tag: $tags")
    assert(tags.exists(_.startsWith("sha::")), s"no content hash: $tags")
    outcome match
      case Main.SyncOutcome.Applied(_, failures) => assert(failures.isEmpty, s"$failures")
      case other                                 => fail(s"expected Applied, got $other")
  }

  /** THE LAW, through the whole shell rather than through the planner alone. */
  test("running twice changes nothing the second time") {
    val (state, anki) = fixture()
    val index         = vaultOf("A.md" -> oneCard)
    Main.observeAndApply(index, deckRoot, dryRun = false, anki).unsafeRunSync()
    val before  = state.notes.size
    val outcome = Main.observeAndApply(index, deckRoot, dryRun = false, anki).unsafeRunSync()
    assertEquals(state.notes.size, before, "a second run created another note")
    outcome match
      case Main.SyncOutcome.Applied(plan, _) => assertEquals(plan.actions, Vector.empty)
      case other                             => fail(s"expected Applied with no actions, got $other")
  }

  /** An inconsistent vault must stop before ANYTHING is written — not report and continue. */
  test("duplicate identities refuse the run, and write nothing") {
    val duplicated = note(
      "n1",
      """|# T
         |
         |x
         |
         |## Dup #flashcard/table
         |
         || Pattern | Purpose | Failure mode |
         || ------- | ------- | ------------ |
         || Retry   | Recover | Amplifies    |
         || Retry   | Repeat  | Storms       |
         |""".stripMargin,
    )
    val (state, outcome) = runSync(dryRun = false, "T.md" -> duplicated)
    assertEquals(state.notes.size, 0, "an inconsistent vault still wrote to the collection")
    outcome match
      case Main.SyncOutcome.RefusedInconsistent(errors) => assert(errors.nonEmpty)
      case other                                        => fail(s"expected a refusal, got $other")
  }

  // ================================================ the verdict ====

  /** The exit code is the only thing a script can see, so each outcome must map to one that
    * distinguishes it. The case that matters most is a run that applied SOME actions and
    * failed others: it must not look like success.
    */
  test("every outcome maps to an exit code, and a partly-failed run is not success") {
    val empty = Plan(Vector.empty, OrphanInference.Computed, Vector.empty)
    val failure = ExecutionFailure(
      SyncAction.Flag(
        CardKey(
          NoteId.fromFrontmatter("n1").toOption.get,
          HeadingPath(NonEmptyVector.one(HeadingSegment.fromExtractedText("A").toOption.get)),
        ),
        AnkiNoteId(1L),
      ),
      AnkiError.Remote("addTags", "refused"),
    )

    def code(o: Main.SyncOutcome) = Main.exitCodeFor(Main.verdict(o))

    assertEquals(code(Main.SyncOutcome.Applied(empty, Vector.empty)), ExitCode.Success)
    assertNotEquals(
      code(Main.SyncOutcome.Applied(empty, Vector(failure))),
      ExitCode.Success,
      "a run whose actions failed reported success",
    )
    assertNotEquals(
      code(Main.SyncOutcome.CouldNotObserve(AnkiError.Remote("notesInfo", "x"))),
      ExitCode.Success,
      "a run that could not read the collection reported success",
    )
    assertNotEquals(
      code(Main.SyncOutcome.RefusedInconsistent(Vector.empty)),
      ExitCode.Success,
      "a refused run reported success",
    )
    assertNotEquals(
      code(Main.SyncOutcome.AbortedDuringExecution(AnkiError.Remote("addNote", "x"))),
      ExitCode.Success,
      "a run that aborted mid-write reported success",
    )
  }

  /** A run that could not compute orphans must say so. Silence there reads as "there were
    * none", which is the difference between a quiet run and a wrong one.
    */
  test("a run that could not look for orphans does not report a clean result") {
    val partial = Plan(
      Vector.empty,
      OrphanInference.SuppressedIncompleteScan("a file could not be read"),
      Vector.empty,
    )
    Main.verdict(Main.SyncOutcome.Applied(partial, Vector.empty)) match
      case Main.Verdict.Clean(_) => fail("a run with orphan inference suppressed reported clean")
      case _                     => ()
  }
