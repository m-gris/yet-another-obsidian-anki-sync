package obsidiananki.cli

import cats.data.NonEmptyVector
import com.monovore.decline.Command as DeclineCommand
import obsidiananki.anki.DeckPath
import obsidiananki.extract.{VaultFile, VaultWalker}
import obsidiananki.plan.*

class CliTest extends munit.FunSuite:

  val parser = DeclineCommand("obsidian-anki-sync", "test")(Cli.command)

  def parse(args: String*): Either[String, Command] =
    parser.parse(args).left.map(_.toString)

  val existingDir: String =
    sys.props("user.dir") // any real directory; the argument only has to BE one

  // ================================================ the guardrail ====

  /** THE point of the argument. A tool that reaches a real collection because a flag was
    * forgotten is the failure this design exists to prevent, so omission must be a usage
    * error rather than a default.
    */
  test("sync WITHOUT a profile is a usage error, not a default") {
    val result = parse("sync", existingDir)
    assert(result.isLeft, "sync ran without a profile")
    assert(result.left.exists(_.contains("profile")), s"error does not name the profile: $result")
  }

  test("a blank profile is refused") {
    assert(parse("sync", existingDir, "--profile", "   ").isLeft)
  }

  test("sync WITH a profile parses") {
    parse("sync", existingDir, "--profile", "POC-test") match
      case Right(Command.Sync(_, profile, _, dryRun)) =>
        assertEquals(profile, "POC-test")
        assertEquals(dryRun, false)
      case other => fail(s"expected a Sync command, got $other")
  }

  /** `inspect` touches no collection, so requiring a profile there would be theatre — and
    * theatre teaches people to supply the flag without thinking, which is worse than not
    * asking.
    */
  test("inspect needs NO profile, because it contacts nothing") {
    assert(parse("inspect", existingDir).isRight)
  }

  // ================================================ validation ====

  test("a vault path that is not a directory is refused at parse time") {
    assert(parse("inspect", "/definitely/not/a/real/directory").isLeft)
  }

  test("the deck root defaults to Obsidian and accepts a nested path") {
    parse("inspect", existingDir) match
      case Right(Command.Inspect(_, deckRoot, _)) => assertEquals(deckRoot.render, "Obsidian")
      case other                                  => fail(s"got $other")
    parse("inspect", existingDir, "--deck-root", "My::Root") match
      case Right(Command.Inspect(_, deckRoot, _)) => assertEquals(deckRoot.render, "My::Root")
      case other                                  => fail(s"got $other")
  }

  test("an empty deck root is refused") {
    assert(parse("inspect", existingDir, "--deck-root", "::").isLeft)
  }

  // ================================================ reporting ====

  val deckRoot: DeckPath = DeckPath(NonEmptyVector.one("Obsidian"))

  def indexOf(files: (String, String)*) =
    VaultWalker.scan(files.toVector.map(VaultFile.apply.tupled), deckRoot)

  def note(id: String, body: String) = s"---\nid: $id\n---\n\n$body"

  /** A run that produced nothing must look different from one that was never asked to do
    * anything, and both must look different from one that failed quietly. Counts are always
    * shown, including zero.
    */
  test("the report always states counts, even when everything is zero") {
    val lines = Report.inspect(indexOf(), verbose = false).mkString("\n")
    assert(lines.contains("cards:    0"), lines)
    assert(lines.contains("failures: 0"), lines)
  }

  test("a partial scan SAYS orphans cannot be computed") {
    val index = indexOf("Bad.md" -> "---\nid: n1\n\nunterminated\n")
    val lines = Report.inspect(index, verbose = false).mkString("\n")
    assert(lines.contains("PARTIAL"), s"a partial scan did not announce itself:\n$lines")
  }

  test("duplicate keys are reported as fatal, with both sources named") {
    val index = indexOf(
      "T.md" -> note(
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
    )
    val lines = Report.inspect(index, verbose = false).mkString("\n")
    assert(lines.contains("nothing would be written"), s"collision not fatal:\n$lines")
    assert(lines.contains("row 1") && lines.contains("row 2"), s"rows not named:\n$lines")
  }

  test("a plan with no actions says so rather than printing an empty list") {
    val lines = Report.plan(Plan(Vector.empty, OrphanInference.Computed, Vector.empty))
    assertEquals(lines, Vector("nothing to do"))
  }

  /** A retype is planned but deliberately not applied, so the report must say that rather
    * than listing it as though it will happen.
    */
  test("a retype is labelled as NOT APPLIED in the plan summary") {
    val index = indexOf("A.md" -> note("n1", "# A\n\nx\n\n## One #flashcard/1way\n\nBody.\n"))
    val key   = index.scan.specs.head.key
    val plan = Plan(
      Vector(SyncAction.Retype(key, obsidiananki.anki.AnkiNoteId(1L), "Basic", "Cloze")),
      OrphanInference.Computed,
      Vector.empty,
    )
    assert(Report.plan(plan).mkString("\n").contains("NOT APPLIED"))
  }
