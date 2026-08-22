package obsidiananki.cli

import cats.data.NonEmptyVector
import cats.effect.{ExitCode, IO}
import cats.effect.unsafe.implicits.global
import obsidiananki.anki.*
import obsidiananki.extract.{VaultFile, VaultWalker}
import obsidiananki.model.*
import obsidiananki.plan.*
import org.http4s.{HttpApp, Uri}
import org.http4s.client.Client
import java.nio.file.{Files, Path, Paths}

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

  /** EVERY non-confirming outcome must refuse, not just a mismatch.
    *
    * Added because a mutant that let `CouldNotAsk` through SURVIVED the first version of this
    * file: only the mismatch case was covered. That is the worse half to leave untested — a
    * mismatch means the guard worked and said no, whereas a failed check means the guard did
    * not run at all, and failing open there is indistinguishable from having no guard.
    */
  test("a profile check that FAILS also refuses, without running the body") {
    val check = Main.classifyProfile("POC-test", Right(Left(AnkiError.Remote("getActiveProfile", "boom"))))
    assertEquals(
      check,
      Main.ProfileCheck.CouldNotAsk("POC-test", AnkiError.Remote("getActiveProfile", "boom")),
    )

    // A server that answers nothing at all, so the probe fails in the effect rather than
    // returning a refusal — "cannot reach Anki", a different fact from a mismatch.
    var bodyRan = false
    val dead    = Client.fromHttpApp(HttpApp[IO](_ => IO.raiseError(RuntimeException("no socket"))))
    val code = Main
      .verifyThen(
        AnkiConnectClient[IO](dead, Uri.unsafeFromString("http://localhost:8765")),
        "POC-test",
      )(_ => IO { bodyRan = true; ExitCode.Success })
      .unsafeRunSync()

    assert(!bodyRan, "the body ran although the profile could not be verified")
    assertEquals(code, ExitCode(2))
  }

  /** The OTHER way the check can fail, and a separate branch of the gate.
    *
    * Anki answers, correctly, with a refusal — a collection that is not open, say. That is
    * neither a mismatch nor an unreachable server, and it took a second surviving mutant to
    * notice the gate's branch for it had no test: the previous one exercised the unreachable
    * path only, and the two are different lines of code.
    */
  test("a probe REFUSED by Anki also refuses the run, without running the body") {
    val refusing = Client.fromHttpApp(
      HttpApp[IO](_ =>
        IO.pure(
          org.http4s
            .Response[IO](org.http4s.Status.Ok)
            .withEntity("""{"result": null, "error": "collection is not open"}""")
        )
      )
    )
    var bodyRan = false
    val code = Main
      .verifyThen(
        AnkiConnectClient[IO](refusing, Uri.unsafeFromString("http://localhost:8765")),
        "POC-test",
      )(_ => IO { bodyRan = true; ExitCode.Success })
      .unsafeRunSync()

    assert(!bodyRan, "the body ran although Anki refused to say which collection was open")
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
      .observeAndApply(vaultOf(files*), deckRoot, dryRun, RetypePolicy.Defer, anki)
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
      case Main.SyncOutcome.Applied(_, report) => assert(report.failures.isEmpty, s"$report")
      case other                                 => fail(s"expected Applied, got $other")
  }

  /** THE LAW, through the whole shell rather than through the planner alone. */
  test("running twice changes nothing the second time") {
    val (state, anki) = fixture()
    val index         = vaultOf("A.md" -> oneCard)
    Main.observeAndApply(index, deckRoot, dryRun = false, RetypePolicy.Defer, anki).unsafeRunSync()
    val before  = state.notes.size
    val outcome = Main.observeAndApply(index, deckRoot, dryRun = false, RetypePolicy.Defer, anki).unsafeRunSync()
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

    assertEquals(code(Main.SyncOutcome.Applied(empty, ExecutionReport(Vector.empty, Vector.empty))), ExitCode.Success)
    assertNotEquals(
      code(Main.SyncOutcome.Applied(empty, ExecutionReport(Vector(failure), Vector.empty))),
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

  /** WHAT THE PERSON READS, which had no test at all.
    *
    * Added because a mutant that broke `describeSyncOutcome` outright SURVIVED the first
    * version of this file. The exit code was covered and the prose was not, so the tool could
    * have printed nothing — or the wrong thing — while exiting correctly. Someone acting on
    * what is on screen rather than on an exit code is the normal case, not the exceptional one.
    */
  test("each way a run can end says on screen what happened to the collection") {
    val empty = Plan(Vector.empty, OrphanInference.Computed, Vector.empty)
    def lines(o: Main.SyncOutcome) = Main.describeSyncOutcome(o).mkString("\n")

    val couldNotObserve = lines(Main.SyncOutcome.CouldNotObserve(AnkiError.Remote("notesInfo", "boom")))
    assert(couldNotObserve.nonEmpty, "a run that could not read the collection printed nothing")
    assert(couldNotObserve.contains("boom"), s"Anki's own words were dropped:\n$couldNotObserve")

    assert(lines(Main.SyncOutcome.RefusedInconsistent(Vector.empty)).nonEmpty, "a refusal printed nothing")
    assert(
      lines(Main.SyncOutcome.AbortedDuringExecution(AnkiError.Remote("addNote", "boom"))).nonEmpty,
      "a run that aborted mid-write printed nothing",
    )

    // The case that must never read as success.
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
    val partlyFailed = lines(Main.SyncOutcome.Applied(empty, ExecutionReport(Vector(failure), Vector.empty)))
    assert(
      partlyFailed.toUpperCase.contains("FAIL"),
      s"a run whose actions failed does not say so on screen:\n$partlyFailed",
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
    Main.verdict(Main.SyncOutcome.Applied(partial, ExecutionReport(Vector.empty, Vector.empty))) match
      case Main.Verdict.Clean(_) => fail("a run with orphan inference suppressed reported clean")
      case _                     => ()
  }

  // ================================================ choosing a vault ====

  /** Where the registry would live for a home directory nothing in these tests reads. Every
    * test below injects the read, so no file at this path is ever opened.
    */
  val someHome: Path = Paths.get("/home/nobody")

  val registryAt: Path = VaultRegistry.locate(someHome)

  /** Entries built directly rather than parsed, so these tests exercise the picker and not
    * the parser — which has its own suite. The ids are 16 hex characters because that is the
    * shape the real registry uses, and they exist here mainly to be asserted ABSENT from the
    * listing.
    */
  def registryEntry(
      id: String,
      path: String,
      open: Boolean = false,
      ts: Long = 1786621931297L,
  ): RegisteredVault =
    RegisteredVault(id, Paths.get(path), RegistryStamp(ts), open)

  /** A directory that really is an Obsidian vault, made the way `CliTest` makes one. */
  def realVaultDirectory(): Path =
    val dir = Files.createTempDirectory("chosen-vault")
    Files.createDirectory(dir.resolve(VaultRoot.MarkerDirectory))
    dir

  def refusalOf(result: Either[Vector[String], VaultRoot]): String = result match
    case Left(lines)  => lines.mkString("\n")
    case Right(chosen) => fail(s"expected a refusal, got the vault $chosen")

  val threeRows: Vector[RegisteredVault] = Vector(
    registryEntry("0123456789abcdef", "/vaults/alpha", open = true),
    registryEntry("fedcba9876543210", "/vaults/beta"),
    registryEntry("00112233445566aa", "/vaults/gamma", open = true),
  )

  /** THE ESCAPE HATCH MUST NOT DEPEND ON THE THING IT IS THE ESCAPE FROM.
    *
    * Every way the registry can fail degrades to "name the vault with --vault-path". If
    * naming it explicitly went through the registry, a broken registry would take the
    * remedy down with it. Asserted on the EFFECT — the injected read and the injected
    * question both record whether they ran — because a test that only checked the returned
    * vault would pass while the registry was read and discarded.
    */
  test("an explicit vault is used as given: the registry is not read and nothing is asked") {
    val dir  = realVaultDirectory()
    val root = VaultRoot.at(dir).toOption.getOrElse(fail("the fixture directory is not a vault"))
    var readRan = false
    var askRan  = false

    val result = Main
      .chooseVault(
        VaultSelection.AtPath(root),
        Main.ConsolePresence.Present,
        someHome,
        _ => IO { readRan = true; Right(Vector.empty) },
        _ => IO { askRan = true; None },
      )
      .unsafeRunSync()

    assert(!readRan, "the registry was read although the vault was named explicitly")
    assert(!askRan, "a question was asked although the vault was named explicitly")
    assertEquals(result, Right(root))
  }

  /** REFUSING A NON-INTERACTIVE RUN MUST NOT DEPEND ON THE REGISTRY EITHER, which is why the
    * console check comes first. A cron run with a missing registry and a cron run with a
    * healthy one are the same refusal, and neither may hang waiting for an answer that will
    * never come.
    */
  test("no vault named and no terminal to ask at: refused, without reading or prompting") {
    var readRan = false
    var askRan  = false

    val result = Main
      .chooseVault(
        VaultSelection.Ask,
        Main.ConsolePresence.Absent,
        someHome,
        _ => IO { readRan = true; Right(threeRows) },
        _ => IO { askRan = true; Some("1") },
      )
      .unsafeRunSync()

    assert(!readRan, "the registry was read although there was nobody to show it to")
    assert(!askRan, "a question was asked although there is no terminal to ask at")
    val lines = refusalOf(result)
    assert(lines.contains("--vault-path"), s"the refusal does not name the flag that works:\n$lines")
  }

  /** THREE FAILURES, THREE FACTS. `NotFound` in particular must read as "could not look",
    * never as "you have no vaults" — the first says nothing about what exists and the second
    * claims it does.
    */
  test("the three ways the registry can fail read as three different facts, each naming the file") {
    def lines(e: RegistryError) = Main.describeRegistryError(e).mkString("\n")

    val notFound   = lines(RegistryError.NotFound(registryAt))
    val unreadable = lines(RegistryError.Unreadable(registryAt, "java.io.IOException: permission denied"))
    val malformed  = lines(RegistryError.Malformed(registryAt, "no 'vaults' key"))

    Vector(notFound, unreadable, malformed).foreach { text =>
      assert(text.contains(registryAt.toString), s"the message does not name the file:\n$text")
      assert(text.contains("--vault-path"), s"the message does not name the way forward:\n$text")
    }

    assertNotEquals(notFound, unreadable)
    assertNotEquals(unreadable, malformed)
    assertNotEquals(notFound, malformed)

    assert(
      notFound.toUpperCase.contains("COULD NOT"),
      s"a missing registry does not say the list could not be looked at:\n$notFound",
    )
    assert(
      !notFound.contains("lists no vaults"),
      s"a missing registry claims the registry lists no vaults:\n$notFound",
    )
    assert(unreadable.contains("permission denied"), s"the cause was dropped:\n$unreadable")
    assert(malformed.contains("no 'vaults' key"), s"the reason was dropped:\n$malformed")
  }

  /** A registry that was READ and holds no vaults is a different fact from one that could
    * not be read at all, and the two send a person to different places. The alternative —
    * printing an empty numbered list and waiting — is what this refusal exists to prevent.
    */
  test("a registry that lists no vaults is refused, in its own words") {
    var askRan = false
    val result = Main
      .chooseVault(
        VaultSelection.Ask,
        Main.ConsolePresence.Present,
        someHome,
        _ => IO.pure(Right(Vector.empty)),
        _ => IO { askRan = true; Some("1") },
      )
      .unsafeRunSync()

    assert(!askRan, "an empty list was offered and an answer waited for")
    val lines = refusalOf(result)
    assert(lines.contains(registryAt.toString), s"the refusal does not name the file:\n$lines")
    assert(lines.contains("--vault-path"), s"the refusal does not name the way forward:\n$lines")
    assertNotEquals(
      lines,
      Main.describeRegistryError(RegistryError.NotFound(registryAt)).mkString("\n"),
      "an empty registry and a missing registry print the same thing",
    )
  }

  /** WHAT IS ON SCREEN WHEN SOMEONE IS ASKED TO CHOOSE.
    *
    * Two entries are flagged deliberately: the `open` flag is per entry, and Obsidian having
    * one vault open is an observation of one file rather than something this tool can rely
    * on. Two flagged entries must render as two flagged rows and not as an arbitrary winner.
    */
  test("the listing numbers the rows, shows full paths, and flags every open vault") {
    val lines = Main.Offering.of(registryAt, threeRows).lines
    val text  = lines.mkString("\n")
    def rowFor(path: String) =
      lines.find(_.contains(path)).getOrElse(fail(s"no row for $path in:\n$text"))

    assert(text.contains(registryAt.toString), s"the header does not name the file read:\n$text")

    assert(rowFor("/vaults/alpha").trim.startsWith("1 "), s"row 1 is not numbered 1:\n$text")
    assert(rowFor("/vaults/beta").trim.startsWith("2 "), s"row 2 is not numbered 2:\n$text")
    assert(rowFor("/vaults/gamma").trim.startsWith("3 "), s"row 3 is not numbered 3:\n$text")

    assertEquals(
      lines.count(_.contains("(open in Obsidian)")),
      2,
      s"two open vaults did not produce two flagged rows:\n$text",
    )
    assert(rowFor("/vaults/beta").contains("(open in Obsidian)") == false, s"beta was flagged:\n$text")

    assert(
      text.contains("--vault-path"),
      s"the listing does not say how to reach a vault Obsidian has never opened:\n$text",
    )
    threeRows.foreach(row =>
      assert(!text.contains(row.id), s"an opaque registry id reached the screen: ${row.id}\n$text")
    )
    assert(
      !text.contains("1786621931297"),
      s"a registry timestamp reached the screen:\n$text",
    )
  }

  /** ONE VALUE RENDERS THE LIST AND READS THE ANSWER, so ordinal n cannot be interpreted
    * against a different vector from the one that was shown.
    *
    * THE ORDINAL IS TAKEN OFF THE PRINTED ROW rather than assumed, and every row is checked.
    * An earlier version of this test typed "2" against three rows and asserted the middle
    * entry — which is the FIXED POINT of reversing the vector, so a mutant that interpreted
    * answers against a reversed list survived it. A test that survives its mutant is not
    * evidence.
    */
  test("the number printed beside a row is the number that selects it") {
    val offering = Main.Offering.of(registryAt, threeRows)
    threeRows.foreach { row =>
      val printed = offering.lines
        .find(_.contains(row.path.toString))
        .getOrElse(fail(s"no row was printed for ${row.path}"))
      val ordinal = printed.trim.takeWhile(_.isDigit)
      assert(ordinal.nonEmpty, s"the row for ${row.path} carries no number: '$printed'")
      assertEquals(
        offering.interpret(ordinal),
        Main.PickerAnswer.Chosen(row),
        s"'$ordinal' is printed beside ${row.path} but does not select it",
      )
    }
  }

  /** TOTAL OVER AN ARBITRARY STRING. Whatever is typed at a prompt, including a pasted path
    * or a vault name, has to land somewhere — and everything that is not an offered ordinal
    * lands on a refusal naming the range, rather than on an exception or a silent pick.
    */
  test("the prompt accepts only an offered ordinal, and says the range when it does not") {
    val offering = Main.Offering.of(registryAt, threeRows)

    assertEquals(offering.interpret("1"), Main.PickerAnswer.Chosen(threeRows(0)))
    assertEquals(offering.interpret("01"), Main.PickerAnswer.Chosen(threeRows(0)))
    assertEquals(offering.interpret("+1"), Main.PickerAnswer.Chosen(threeRows(0)))
    assertEquals(offering.interpret(" 3\r"), Main.PickerAnswer.Chosen(threeRows(2)))

    val hostile =
      Vector("", "   ", "0", "-1", "4", "1 2", "99999999999999999999", "/vaults/alpha", "alpha", "y")
    hostile.foreach { typed =>
      offering.interpret(typed) match
        case Main.PickerAnswer.NotChosen(reason) =>
          assert(
            reason.contains("1") && reason.contains("3"),
            s"the refusal for '$typed' does not name the range: $reason",
          )
        case chosen => fail(s"'$typed' selected something: $chosen")
    }
  }

  /** EOF IS NOT AN ANSWER. Reading a closed stdin as a choice would pick a vault nobody
    * named; reading it as "not understood" would send the person to correct a typo they did
    * not make.
    */
  test("stdin closing before an answer arrives is a refusal of its own") {
    var asked = 0
    val result = Main
      .chooseVault(
        VaultSelection.Ask,
        Main.ConsolePresence.Present,
        someHome,
        _ => IO.pure(Right(threeRows)),
        _ => IO { asked += 1; None },
      )
      .unsafeRunSync()

    assertEquals(asked, 1)
    val lines = refusalOf(result)
    assert(lines.contains("stdin"), s"the refusal does not say what happened:\n$lines")
    assert(
      !lines.contains("between 1 and"),
      s"a closed stdin is reported as an answer that was not understood:\n$lines",
    )
  }

  /** THE REGISTRY CHOOSES BUT DOES NOT VOUCH. An entry records that Obsidian opened that
    * directory at some past time; the directory may since have moved. `VaultRoot.at` is
    * still the only thing that decides, and its reason is carried VERBATIM rather than
    * reworded — one fact, one wording, arriving by two routes.
    *
    * The count also proves there is no retry loop: a refusal ends the run.
    */
  test("a chosen entry that is no longer a vault is refused ONCE, saying where the path came from") {
    val stale = Files.createTempDirectory("registry-entry-gone-stale")
    val rows  = Vector(registryEntry("aabbccddeeff0011", stale.toString))
    var asked = 0

    val result = Main
      .chooseVault(
        VaultSelection.Ask,
        Main.ConsolePresence.Present,
        someHome,
        _ => IO.pure(Right(rows)),
        _ => IO { asked += 1; Some("1") },
      )
      .unsafeRunSync()

    assertEquals(asked, 1, "the picker asked again after a refusal")
    val lines = refusalOf(result)
    val verbatim =
      VaultRoot.at(stale).swap.toOption.getOrElse(fail("the stale fixture is somehow a vault"))
    assert(lines.contains(verbatim), s"VaultRoot.at's own reason was reworded:\n$lines")
    assert(lines.contains("registry"), s"the message does not say where the path came from:\n$lines")
    assert(
      lines.indexOf("registry") < lines.indexOf(verbatim),
      s"the provenance comes after the reason it is meant to frame:\n$lines",
    )
  }

  test("a chosen entry that IS a vault becomes the vault the run reads") {
    val dir  = realVaultDirectory()
    val root = VaultRoot.at(dir).toOption.getOrElse(fail("the fixture directory is not a vault"))
    val rows = Vector(registryEntry("99887766554433aa", dir.toString))

    val result = Main
      .chooseVault(
        VaultSelection.Ask,
        Main.ConsolePresence.Present,
        someHome,
        _ => IO.pure(Right(rows)),
        _ => IO.pure(Some("1")),
      )
      .unsafeRunSync()

    assertEquals(result, Right(root))
  }

  // ================================================ the note type preflight ====

  /** THE GAP THIS SLICE CLOSES, at the level of the shell.
    *
    * A profile with none of this tool's note types in it is what a NEW Anki profile looks like.
    * Before the preflight existed, `sync` computed a plan against it, printed the plan as
    * though it would work, and then failed on every single note.
    *
    * ASSERTED ON THE COLLECTION, not only on the outcome value: nothing may be written.
    */
  test("sync REFUSES a collection that does not have this tool's note types, and writes nothing") {
    val (state, anki) = fixture()
    state.models = Map.empty

    val outcome = Main.observeAndApply(vaultOf("A.md" -> oneCard), deckRoot, dryRun = false, RetypePolicy.Defer, anki)
      .unsafeRunSync()

    outcome match
      case Main.SyncOutcome.NoteTypesNotReady(problems) =>
        assertEquals(problems.size, Marker.NoteTypes.All.size)
      case other => fail(s"expected NoteTypesNotReady, got $other")

    assertEquals(state.notes.size, 0, "notes were written to a collection with no note types")
    assertEquals(Main.exitCodeFor(Main.verdict(outcome)), ExitCode(2))
  }

  /** THE SILENT ONE. A note type that EXISTS but lacks a field this tool writes takes an update
    * happily and stores nothing — Anki reports an unrecognised field name on update as no error
    * at all. So the preflight looks at fields, not merely at names.
    *
    * The shape is not hypothetical: verified live, read-only, on 2026-08-21 in profile
    * `claude-POC-test`, `modelFieldNames` for `Cloze Sequence` answers `[Title, Text]` and for
    * `3 way Concept-Descriptor` `[Concept, Descriptor, Description, ThreeWay]` — NEITHER has
    * `Context`. Renaming those two by hand will therefore not, on its own, make them writable.
    */
  test("sync REFUSES when a note type is present but lacks a field this tool writes") {
    val (state, anki) = fixture()
    val basic = state.models(Marker.NoteTypes.Basic)
    state.models += Marker.NoteTypes.Basic -> basic.copy(
      fields = NonEmptyVector.of(Marker.BasicFields.Front, Marker.BasicFields.Back)
    )

    val outcome = Main.observeAndApply(vaultOf("A.md" -> oneCard), deckRoot, dryRun = false, RetypePolicy.Defer, anki)
      .unsafeRunSync()

    outcome match
      case Main.SyncOutcome.NoteTypesNotReady(problems) =>
        assertEquals(
          problems,
          Vector(
            NoteTypeProblem.FieldsMissing(
              Marker.NoteTypes.Basic,
              NonEmptyVector.of(Marker.ContextField, Marker.SameShapeField),
            )
          ),
        )
      case other => fail(s"expected NoteTypesNotReady, got $other")

    assertEquals(state.notes.size, 0, "a note was written to a note type missing a field")
  }

  /** A DRY RUN GETS NO EXEMPTION. Printing a plan that cannot be applied is the less useful
    * answer, and it is the one that reads as though everything is fine.
    */
  test("a dry run is refused too when the note types are not there") {
    val (_, anki) = fixture()
    val (state2, anki2) = fixture()
    state2.models = Map.empty
    assert(
      Main.observeAndApply(vaultOf("A.md" -> oneCard), deckRoot, dryRun = true, RetypePolicy.Defer, anki2)
        .unsafeRunSync()
        .isInstanceOf[Main.SyncOutcome.NoteTypesNotReady],
      "a dry run planned against a collection it could not write to",
    )
    // Control: the same dry run against a collection that IS ready still plans.
    assert(
      Main.observeAndApply(vaultOf("A.md" -> oneCard), deckRoot, dryRun = true, RetypePolicy.Defer, anki)
        .unsafeRunSync()
        .isInstanceOf[Main.SyncOutcome.PlannedOnly],
      "the preflight refused a collection that was ready",
    )
  }

  test("the refusal names each missing note type and points at install-note-types") {
    val lines = Report
      .noteTypesNotReady(Vector(NoteTypeProblem.Missing("Obsidian Basic", None)))
      .mkString("\n")
    assert(lines.contains("Obsidian Basic"), lines)
    assert(lines.contains("install-note-types"), s"no remedy is named:\n$lines")
    assert(lines.contains("Nothing was written"), lines)
  }

  /** "Install it" and "rename it" are different remedies. Doing the first when the second was
    * needed leaves a populated note type stranded beside an empty duplicate, with no error.
    */
  test("a note type still under its old name is reported as a RENAME, not as missing") {
    val lines = Report
      .noteTypesNotReady(
        Vector(NoteTypeProblem.Missing("Obsidian Cloze Sequence", Some("Cloze Sequence")))
      )
      .mkString("\n")
    assert(lines.contains("Cloze Sequence"), lines)
    assert(lines.contains("RENAMED"), s"the message does not say to rename it:\n$lines")
  }

  // ================================================ install-note-types ====

  def installOutcome(anki: AnkiConnectClient[IO]): InstallOutcome =
    NoteTypeAssets.all match
      case Left(errors) => fail(errors.map(_.describe).mkString("; "))
      case Right(assets) =>
        NoteTypeInstaller.install[Main.Refused](anki, assets).value.unsafeRunSync() match
          case Right(outcome) => outcome
          case Left(error)    => fail(s"install refused: $error")

  test("install-note-types creates every note type a fresh profile is missing") {
    val (state, anki) = fixture()
    state.models = Map.empty

    val outcome = installOutcome(anki)
    assertEquals(outcome.created.toSet, Marker.NoteTypes.All.toSet)
    assertEquals(state.models.keySet, Marker.NoteTypes.All.toSet)
    assertEquals(Main.exitCodeForInstall(outcome), ExitCode.Success)
  }

  /** THE ONE OUTCOME WHERE NOTHING IS CREATED AT ALL, and the exit code has to say so: 2 means
    * nothing was written, and here that is true.
    */
  test("install-note-types refuses entirely, and reports 2, while a hand-rename is pending") {
    val (state, anki) = fixture()
    val sequence = state.models(Marker.NoteTypes.ClozeSequence)
    state.models = Map("Cloze Sequence" -> sequence.copy(name = "Cloze Sequence"))

    val outcome = installOutcome(anki)
    assertEquals(outcome.blockedByRename, Vector("Cloze Sequence" -> "Obsidian Cloze Sequence"))
    assertEquals(state.models.keySet, Set("Cloze Sequence"), "something was created despite the refusal")
    assertEquals(Main.exitCodeForInstall(outcome), ExitCode(2))

    val lines = Report.noteTypes(outcome).mkString("\n")
    assert(lines.contains("NOTHING was created"), s"the refusal does not say so:\n$lines")
    assert(lines.contains("Manage Note Types"), s"the manual remedy is not named:\n$lines")
  }

  /** THE SAFE DEFAULT, at the level of the shell: reported, not repaired, and the exit code is
    * 1 rather than 0 so a script notices.
    */
  test("a note type that differs is reported, left alone, and reported as a problem") {
    val (state, anki) = fixture()
    val basic = state.models(Marker.NoteTypes.Basic)
    val edited = basic.copy(styling = ".card { color: rebeccapurple; }")
    state.models += Marker.NoteTypes.Basic -> edited

    val outcome = installOutcome(anki)
    assertEquals(state.models(Marker.NoteTypes.Basic).styling, edited.styling, "the styling was overwritten")
    assertEquals(Main.exitCodeForInstall(outcome), ExitCode.Error)

    val lines = Report.noteTypes(outcome).mkString("\n")
    assert(lines.contains("NOT repaired"), s"the report does not say it changed nothing:\n$lines")
    assert(lines.contains("stylesheet differs"), s"the difference is not named:\n$lines")
  }

  test("an unchanged collection reports every note type and exits clean") {
    val (_, anki) = fixture()
    val outcome = installOutcome(anki)
    assertEquals(outcome.created, Vector.empty)
    assertEquals(Main.exitCodeForInstall(outcome), ExitCode.Success)
    val lines = Report.noteTypes(outcome).mkString("\n")
    Marker.NoteTypes.All.foreach(n => assert(lines.contains(n), s"'$n' is not in the report:\n$lines"))
  }

  // ================================================ moving notes between types ====

  /** THE SITUATION THIS WHOLE FEATURE IS FOR, built rather than described.
    *
    * A note is synced normally, and then put back onto Anki's stock `Basic` — which is where
    * every note synced before 2026-08-21 actually sits, because until then this tool wrote to
    * the stock note types. It keeps its `src::` identity and its `sha::` hash, so it is a note
    * this tool recognises and would otherwise consider up to date.
    *
    * BUILT BY SYNCING FIRST AND THEN MOVING THE NOTE BACK, rather than by writing a `src::` tag
    * by hand: the tag is a percent-encoded canonicalisation of the heading path, and a
    * hand-written one that was subtly wrong would make the test pass by producing no plan at
    * all.
    */
  def collectionWithANoteOnTheStockType(): (FakeAnkiConnect.State, AnkiConnectClient[IO], Long) =
    val (state, anki) = fixture()
    Main
      .observeAndApply(vaultOf("A.md" -> oneCard), deckRoot, dryRun = false, RetypePolicy.Defer, anki)
      .unsafeRunSync()
    assertEquals(state.notes.size, 1, "the fixture sync did not create the note")

    state.models += "Basic" -> NoteTypeSpec(
      name = "Basic",
      isCloze = false,
      fields = NonEmptyVector.of("Front", "Back"),
      templates = NonEmptyVector.one("Card 1" -> CardTemplate("{{Front}}", "{{FrontSide}}{{Back}}")),
      styling = "",
    )
    val (id, note) = state.notes.head
    state.notes += id -> note.copy(
      model = "Basic",
      fields = note.fields.filterNot((name, _) => name == Marker.ContextField),
      tags = note.tags :+ "leech",
    )
    (state, anki, id)

  /** WITHOUT THE FLAG, NOTHING MOVES — and the run says so rather than reporting success.
    *
    * Asserted on the collection first: an assertion on the outcome value alone would pass
    * while the note had in fact been rewritten.
    */
  test("sync leaves a note that is on the wrong note type alone, and says what it did not do") {
    val (state, anki, id) = collectionWithANoteOnTheStockType()

    val outcome = Main
      .observeAndApply(vaultOf("A.md" -> oneCard), deckRoot, dryRun = false, RetypePolicy.Defer, anki)
      .unsafeRunSync()

    assertEquals(state.notes(id).model, "Basic", "the note was moved without being asked")

    val deferred = outcome match
      case Main.SyncOutcome.Applied(_, report) =>
        assert(report.failures.isEmpty, s"a deferral was reported as a failure: ${report.failures}")
        report.deferred
      case other => fail(s"expected Applied, got $other")
    assertEquals(deferred.size, 1, "the outstanding move was not reported at all")

    val screen = Main.describeSyncOutcome(outcome).mkString("\n")
    assert(screen.contains("--migrate-note-types"), s"the flag that would do it is not named:\n$screen")
    assert(screen.contains("Basic"), s"the note types involved are not named:\n$screen")

    // AND IT IS NOT A CLEAN RUN. The collection does not match the vault, and a green result
    // would say it does.
    Main.verdict(outcome) match
      case Main.Verdict.Clean(_) => fail(s"a run with outstanding moves reported clean:\n$screen")
      case _                     => ()
    assertEquals(Main.exitCodeFor(Main.verdict(outcome)), ExitCode.Error)
  }

  /** WITH THE FLAG, THE NOTE MOVES — and everything the move could have destroyed is checked.
    *
    * `Context` is the field this tool's own note types were introduced for, and the stock type
    * the note was sitting on does not have it, so its presence afterwards is what proves the
    * move actually happened rather than the note merely being updated in place.
    */
  test("sync --migrate-note-types moves the note, keeping its identity and its foreign tags") {
    val (state, anki, id) = collectionWithANoteOnTheStockType()

    val outcome = Main
      .observeAndApply(vaultOf("A.md" -> oneCard), deckRoot, dryRun = false, RetypePolicy.Apply, anki)
      .unsafeRunSync()

    outcome match
      case Main.SyncOutcome.Applied(_, report) =>
        assert(report.failures.isEmpty, s"the move failed: ${report.failures}")
        assert(report.deferred.isEmpty, s"the move was deferred despite the flag: $report")
      case other => fail(s"expected Applied, got $other")

    val moved = state.notes(id)
    assertEquals(moved.model, Marker.NoteTypes.Basic, "the note did not move")
    assert(
      moved.fields.exists((name, value) => name == Marker.ContextField && value.nonEmpty),
      s"the field the new note type exists for was not written: ${moved.fields}",
    )
    assert(moved.tags.exists(_.startsWith("src::")), s"the note lost its identity: ${moved.tags}")
    assert(moved.tags.contains("leech"), s"a foreign tag was destroyed by the move: ${moved.tags}")

    // THE LAW, through the shell: the hash written by the move describes what the move wrote,
    // so the next run has nothing to do.
    Main
      .observeAndApply(vaultOf("A.md" -> oneCard), deckRoot, dryRun = false, RetypePolicy.Apply, anki)
      .unsafeRunSync() match
      case Main.SyncOutcome.Applied(plan, _) =>
        assertEquals(plan.actions, Vector.empty, "the moved note was planned again")
      case other => fail(s"expected Applied, got $other")
  }

  /** A DRY RUN STILL WRITES NOTHING, flag or no flag. The flag says what the run may do, not
    * whether it is a rehearsal.
    */
  test("a dry run with --migrate-note-types moves nothing") {
    val (state, anki, id) = collectionWithANoteOnTheStockType()
    Main
      .observeAndApply(vaultOf("A.md" -> oneCard), deckRoot, dryRun = true, RetypePolicy.Apply, anki)
      .unsafeRunSync()
    assertEquals(state.notes(id).model, "Basic", "a dry run moved a note")
  }
