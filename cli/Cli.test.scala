package obsidiananki.cli

import cats.data.NonEmptyVector
import com.monovore.decline.Command as DeclineCommand
import obsidiananki.anki.{AnkiNoteId, DeckPath}
import obsidiananki.extract.{DeckShape, VaultFile, VaultWalker}
import obsidiananki.plan.*

class CliTest extends munit.FunSuite:

  val parser = DeclineCommand("obsidian-anki-sync", "test")(Cli.command)

  def parse(args: String*): Either[String, Command] =
    parser.parse(args).left.map(_.toString)

  /** A real Obsidian vault, made here rather than borrowed from the repository.
    *
    * It used to be `sys.props("user.dir")` — "any real directory", which was the whole
    * problem: the argument only had to BE a directory, so the parent of a vault, or an
    * unrelated folder, parsed happily. A vault is now a directory carrying Obsidian's own
    * marker, so the fixture has to be one.
    *
    * Built in a temp directory so the test does not depend on a hidden directory surviving in
    * git, which handles them badly.
    */
  val existingDir: String =
    val dir = java.nio.file.Files.createTempDirectory("vault-fixture")
    java.nio.file.Files.createDirectory(dir.resolve(VaultRoot.MarkerDirectory))
    dir.toString

  /** A directory that exists but is NOT a vault — the shape of pointing one level too high. */
  val notAVault: String =
    java.nio.file.Files.createTempDirectory("not-a-vault").toString

  // ================================================ the guardrail ====

  /** THE point of the argument. A tool that reaches a real collection because a flag was
    * forgotten is the failure this design exists to prevent, so omission must be a usage
    * error rather than a default.
    */
  test("sync WITHOUT a profile is a usage error, not a default") {
    val result = parse("sync", "--vault-path", existingDir)
    assert(result.isLeft, "sync ran without a profile")
    assert(result.left.exists(_.contains("profile")), s"error does not name the profile: $result")
  }

  test("a blank profile is refused") {
    assert(parse("sync", "--vault-path", existingDir, "--profile", "   ").isLeft)
  }

  test("sync WITH a profile parses") {
    parse("sync", "--vault-path", existingDir, "--profile", "POC-test") match
      case Right(Command.Sync(_, profile, _, _, dryRun, retypePolicy)) =>
        assertEquals(profile, "POC-test")
        assertEquals(dryRun, false)
        // THE DEFAULT IS THE SAFE ONE, asserted rather than assumed. Without the flag a sync
        // does not move notes between note types; a default of `Apply` would make the first
        // ordinary sync after the note types were renamed rewrite every field and every tag of
        // every note this tool had previously synced, unasked.
        assertEquals(retypePolicy, RetypePolicy.Defer)
      case other => fail(s"expected a Sync command, got $other")
  }

  /** `inspect` touches no collection, so requiring a profile there would be theatre — and
    * theatre teaches people to supply the flag without thinking, which is worse than not
    * asking.
    */
  test("inspect needs NO profile, because it contacts nothing") {
    assert(parse("inspect", "--vault-path", existingDir).isRight)
  }

  // ================================================ validation ====

  test("a vault path that is not a directory is refused at parse time") {
    assert(parse("inspect", "--vault-path", "/definitely/not/a/real/directory").isLeft)
  }

  /** THE CASE A BARE DIRECTORY CHECK LET THROUGH, and the reason this is a type rather than a
    * validation.
    *
    * A directory that exists but holds no Obsidian marker is almost always the PARENT of the
    * vault, or an unrelated folder. Accepting it is not a harmless no-op: the tool reads no
    * marked headings, which is a complete scan of nothing, so every card in the collection
    * looks deleted and the run flags all of them and reports success.
    */
  test("a directory that exists but is NOT a vault is refused, and the message says why") {
    val result = parse("inspect", "--vault-path", notAVault)
    assert(result.isLeft, s"an ordinary directory was accepted as a vault: $result")
    assert(
      result.left.exists(e => e.contains("Obsidian") || e.contains(VaultRoot.MarkerDirectory)),
      s"the refusal does not explain what was missing: $result",
    )
  }

  test("the same refusal applies to sync, not only to inspect") {
    assert(parse("sync", "--vault-path", notAVault, "--profile", "POC-test").isLeft)
  }

  test("the deck root defaults to Obsidian and accepts a nested path") {
    parse("inspect", "--vault-path", existingDir) match
      case Right(Command.Inspect(_, deckRoot, _, _)) => assertEquals(deckRoot.render, "Obsidian")
      case other                                     => fail(s"got $other")
    parse("inspect", "--vault-path", existingDir, "--deck-root", "My::Root") match
      case Right(Command.Inspect(_, deckRoot, _, _)) => assertEquals(deckRoot.render, "My::Root")
      case other                                     => fail(s"got $other")
  }

  test("an empty deck root is refused") {
    assert(parse("inspect", "--vault-path", existingDir, "--deck-root", "::").isLeft)
  }

  // ================================================ choosing a vault ====

  /** OMITTING THE FLAG IS NOT AN ERROR AND IS NOT A DEFAULT EITHER. It parses to a request
    * to ask, which `Main` answers by listing the vaults Obsidian has opened. The choice
    * still has to be made; nothing here picks one.
    */
  test("with no --vault-path, both commands parse to a request to ASK") {
    parse("inspect") match
      case Right(Command.Inspect(selection, _, _, _)) => assertEquals(selection, VaultSelection.Ask)
      case other                                   => fail(s"got $other")
    parse("sync", "--profile", "POC-test") match
      case Right(Command.Sync(selection, _, _, _, _, _)) => assertEquals(selection, VaultSelection.Ask)
      case other                                     => fail(s"got $other")
  }

  test("--vault-path names the vault outright, carrying a root that has already been checked") {
    parse("inspect", "--vault-path", existingDir) match
      case Right(Command.Inspect(VaultSelection.AtPath(root), _, _, _)) =>
        assertEquals(
          root.render,
          java.nio.file.Paths.get(existingDir).toAbsolutePath.normalize.toString,
        )
      case other => fail(s"expected an explicitly named vault, got $other")
  }

  /** THE DEMOTION THAT MUST NOT HAPPEN. `--vault-path` is optional, so the obvious way to
    * write it — validate, then `.orNone` — could plausibly turn a REFUSED path into "no flag
    * given" and open the picker. A person who typed a path and got a menu would reasonably
    * conclude the path was fine.
    */
  test("a --vault-path that is not a vault is REFUSED, never demoted to a request to ask") {
    val result = parse("inspect", "--vault-path", notAVault)
    result match
      case Left(message) =>
        assert(
          message.contains("Obsidian") || message.contains(VaultRoot.MarkerDirectory),
          s"the refusal does not explain what was missing: $message",
        )
      case Right(Command.Inspect(selection, _, _, _)) =>
        fail(s"a bad --vault-path parsed instead of being refused, as $selection")
      case Right(other) => fail(s"got $other")
  }

  /** THE EMPTY STRING IS THE ONE SHAPE THAT LIES. `Paths.get("")` is the empty path, and
    * `VaultRoot.at` resolves it with `toAbsolutePath` — so `--vault-path ""` means "whatever
    * directory I am standing in", and is accepted outright if that directory happens to
    * carry the Obsidian marker. It arrives from `--vault-path "$VAULT"` with the variable
    * unset, which is not an exotic way to invoke a tool.
    *
    * ASSERTED ON THE REFUSAL FIRING, never on the path that would otherwise come back: with
    * the check deleted this parses successfully and yields the working directory, so an
    * assertion about the resulting value could pass while the defect was live.
    */
  test("an EMPTY --vault-path is refused, and the message says what it would have meant") {
    val result = parse("inspect", "--vault-path", "")
    assert(result.isLeft, s"an empty vault path was accepted: $result")
    assert(
      result.left.exists(_.contains("current directory")),
      s"the refusal does not say an empty path means the current directory: $result",
    )
  }

  /** A TRIPWIRE, not a feature test. `--vault <name>` is one of the three ways the design
    * rules, and it is deliberately ABSENT: Obsidian's registry stores no name for a vault —
    * its entries are keyed by an opaque identifier and carry a path and a timestamp — so any
    * name would be one this tool invented, and what a vault's name is has not been ruled.
    *
    * This test fails the day someone adds the flag, which is the moment to check the naming
    * contract has been settled rather than guessed.
    */
  test("--vault is NOT accepted, because what a vault's name is has not been decided") {
    assert(parse("inspect", "--vault", "anything").isLeft, "--vault was accepted")
    assert(parse("sync", "--vault", "anything", "--profile", "POC-test").isLeft)
  }

  // ================================================ reporting ====

  val deckRoot: DeckPath = DeckPath(NonEmptyVector.one("Obsidian"))

  def indexOf(files: (String, String)*) =
    VaultWalker.scan(files.toVector.map(VaultFile.apply.tupled), deckRoot, DeckShape.FoldersOnly)

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
    val lines =
      Report.plan(Plan(Vector.empty, OrphanInference.Computed, Vector.empty), RetypePolicy.Defer)
    assertEquals(lines, Vector("nothing to do"))
  }

  /** THE PLAN IS PRINTED BEFORE IT IS APPLIED, so the summary line for a note-type move has to
    * say whether this particular run is going to make it. Under the default policy it is not,
    * and someone reading the plan needs to know that before deciding whether to re-run.
    */
  test("a note-type move says whether THIS run will make it") {
    val index = indexOf("A.md" -> note("n1", "# A\n\nx\n\n## One #flashcard/1way\n\nBody.\n"))
    val key   = index.scan.specs.head.key
    val plan = Plan(
      Vector(
        SyncAction.Retype(
          key = key,
          noteId = obsidiananki.anki.AnkiNoteId(1L),
          from = "Basic",
          to = obsidiananki.model.Marker.NoteTypes.Basic,
          fields = Vector("Front" -> "One", "Back" -> "Body.", "Context" -> "A"),
          ownedTags = NonEmptyVector.one(obsidiananki.model.TagCodec.encode(key)),
          preservedTags = Vector.empty,
          // This test is about what the REPORT says, not about decks; no move to carry.
          deck = None,
        )
      ),
      OrphanInference.Computed,
      Vector.empty,
    )

    val deferred = Report.plan(plan, RetypePolicy.Defer).mkString("\n")
    assert(deferred.contains("NOT APPLIED"), s"a deferred move reads as work that will happen:\n$deferred")
    assert(deferred.contains("--migrate-note-types"), s"the flag that would do it is not named:\n$deferred")

    val applying = Report.plan(plan, RetypePolicy.Apply).mkString("\n")
    assert(
      !applying.contains("NOT APPLIED"),
      s"a move this run WILL make is labelled as one it will not:\n$applying",
    )
  }

  // ================================================ install-note-types ====

  test("install-note-types parses and carries the profile, and does NOT repair by default") {
    assertEquals(
      parse("install-note-types", "--profile", "claude-POC-test"),
      Right(Command.InstallNoteTypes("claude-POC-test", repair = false)),
    )
  }

  /** The DEFAULT matters more here than the flag does. Without `--repair` this command creates
    * what is absent and overwrites nothing, so a template somebody improved in Anki survives a
    * run they made for an unrelated reason. The flag is how they say otherwise, in as many words.
    */
  test("install-note-types --repair is opt-in, and is carried through") {
    assertEquals(
      parse("install-note-types", "--profile", "claude-POC-test", "--repair"),
      Right(Command.InstallNoteTypes("claude-POC-test", repair = true)),
    )
  }

  /** THE SAME GUARDRAIL AS `sync`, and for a stronger reason: this is the only command that
    * changes what a collection IS rather than what it holds. Reaching the wrong one because a
    * flag was forgotten is exactly the failure the argument exists to prevent.
    */
  test("install-note-types WITHOUT a profile is a usage error, not a default") {
    val result = parse("install-note-types")
    assert(result.isLeft, "install-note-types ran without a profile")
    assert(result.left.exists(_.contains("profile")), s"error does not name the profile: $result")
  }

  /** IT TAKES NO VAULT, because it reads none. Accepting `--vault-path` would suggest the
    * command's effect depended on which vault was named, and it does not.
    */
  test("install-note-types takes no vault") {
    assert(
      parse("install-note-types", "--profile", "p", "--vault-path", existingDir).isLeft,
      "a vault was accepted by a command that reads no vault",
    )
  }

  // ================================================ --deck-from ====

  private def shapeOf(args: String*): DeckShape =
    parse(("inspect" +: "--vault-path" +: existingDir +: args)*) match
      case Right(Command.Inspect(_, _, shape, _)) => shape
      case other                                  => fail(s"got $other")

  /** THE DEFAULT IS LOAD-BEARING, not a convenience. Every card in an already-synced
    * collection sits in a folder-derived deck, so any other default would greet it with a deck
    * move for every note.
    */
  test("--deck-from defaults to folders, the arrangement every synced collection already has") {
    assertEquals(shapeOf(), DeckShape.FoldersOnly)
    assertEquals(shapeOf(), DeckShape(folders = true, fileName = false, headings = false))
  }

  test("--deck-from selects the named sources and only those") {
    assertEquals(
      shapeOf("--deck-from", "folders,headings"),
      DeckShape(folders = true, fileName = false, headings = true),
    )
    assertEquals(
      shapeOf("--deck-from", "file"),
      DeckShape(folders = false, fileName = true, headings = false),
    )
    assertEquals(
      shapeOf("--deck-from", "folders,file,headings"),
      DeckShape(folders = true, fileName = true, headings = true),
    )
  }

  test("--deck-from tolerates spacing and case, since a shell script will have both") {
    assertEquals(
      shapeOf("--deck-from", " Folders , HEADINGS "),
      DeckShape(folders = true, fileName = false, headings = true),
    )
  }

  /** 'none' is a word rather than an empty value, because "one flat deck" is a real way to
    * work while an empty value is how an unset shell variable arrives.
    */
  test("--deck-from none selects nothing, and an empty value is refused") {
    assertEquals(
      shapeOf("--deck-from", "none"),
      DeckShape(folders = false, fileName = false, headings = false),
    )
    assert(parse("inspect", "--vault-path", existingDir, "--deck-from", "").isLeft)
  }

  test("--deck-from none cannot be combined with a source, since it means the absence of one") {
    assert(parse("inspect", "--vault-path", existingDir, "--deck-from", "none,folders").isLeft)
  }

  /** REFUSED RATHER THAN IGNORED. A typo silently dropped would file every card somewhere the
    * author did not choose, and the only symptom would be decks that look slightly wrong.
    */
  test("an unknown source is refused, and the message names it and the alternatives") {
    parse("inspect", "--vault-path", existingDir, "--deck-from", "folders,headinsg") match
      case Left(help) =>
        val text = help.toString
        assert(text.contains("headinsg"), s"the message must name the typo — got: $text")
        assert(text.contains("headings"), s"the message must name what was meant — got: $text")
      case other => fail(s"a misspelt source was accepted: $other")
  }

  test("the same option is available on sync, not only on inspect") {
    parse(
      "sync",
      "--vault-path",
      existingDir,
      "--profile",
      "POC-test",
      "--deck-from",
      "headings",
    ) match
      case Right(Command.Sync(_, _, _, shape, _, _)) =>
        assertEquals(shape, DeckShape(folders = false, fileName = false, headings = true))
      case other => fail(s"got $other")
  }

  // ================================================ what an "update" actually is ====

  /** `SyncAction.Update` carries a field change, a deck change, or both, and the summary used
    * to call all three "update".
    *
    * IT MATTERED LITTLE UNTIL `--deck-from` EXISTED and matters a lot now: the first run after
    * changing the deck shape moves every card in the collection and rewrites the content of
    * none, and a line reading "43 update" tells the reader the opposite of what happened.
    * Measured against a live collection before this was split: 43 cards moved deck and the
    * summary said "43 update".
    */
  private def updateLine(changes: Change*): String =
    val index = indexOf("A.md" -> note("n1", "# A\n\nx\n\n## One #flashcard/1way\n\nBody.\n"))
    val key   = index.scan.specs.head.key
    val plan = Plan(
      Vector(
        SyncAction.Update(key, AnkiNoteId(1L), NonEmptyVector.fromVectorUnsafe(changes.toVector))
      ),
      OrphanInference.Computed,
      Vector.empty,
    )
    Report.plan(plan, RetypePolicy.Defer).mkString("\n")

  private val movedDeck: Change =
    Change.DeckChanged(Some(DeckPath(NonEmptyVector.one("Old"))), DeckPath(NonEmptyVector.one("New")))

  private val rewroteFields: Change =
    Change.FieldsChanged(Vector("Front" -> "f", "Back" -> "b"), "sha::deadbeef")

  test("a note that only moved deck is not reported as content having been rewritten") {
    val line = updateLine(movedDeck)
    assert(line.contains("move to another deck"), s"a deck move is not named as one:\n$line")
    assert(
      !line.contains("1  update"),
      s"a deck move is still being counted as a content update:\n$line",
    )
  }

  test("a note whose fields changed is still an update") {
    val line = updateLine(rewroteFields)
    assert(line.contains("update"), s"a field change is not named as one:\n$line")
    assert(
      !line.contains("move to another deck"),
      s"a field change claims the card also moved deck:\n$line",
    )
  }

  /** BOTH IS ITS OWN LINE rather than being filed under whichever came first — a card that
    * moved AND was rewritten is two facts, and dropping either is the thing this split exists
    * to stop.
    */
  test("a note that both changed and moved says both") {
    val line = updateLine(rewroteFields, movedDeck)
    assert(line.contains("update"), s"the content change went missing:\n$line")
    assert(line.contains("move to another deck"), s"the deck move went missing:\n$line")
  }
