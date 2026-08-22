package obsidiananki.extract

import cats.data.NonEmptyVector
import obsidiananki.anki.*
import obsidiananki.model.*
import obsidiananki.plan.*
import java.nio.file.{Files, Path, Paths}
import scala.jdk.CollectionConverters.*

/** The planner, driven by specs from the REAL FIXTURE VAULT through the REAL extraction path.
  *
  * Everything else exercises the planner on hand-built `CardSpec`s, and hand-built inputs
  * encode the author's assumptions — which is precisely where a comfortable assumption
  * hides. Twice now, testing two slices against each other has caught something neither
  * caught alone: the key model against the parser, and the key model against the Anki fake.
  * This is the third such seam and the one with the most behind it.
  */
class FixtureVaultTest extends munit.FunSuite:

  val deckRoot: DeckPath = DeckPath(NonEmptyVector.one("Obsidian"))

  /** Locate dummy-vault by walking up from the working directory, so the test does not
    * depend on where it is invoked from.
    */
  lazy val vaultRoot: Option[Path] =
    // Checks both candidates at every level, because the tests may be invoked from the
    // project directory OR from a sibling — depending on cwd would make this pass or fail
    // for reasons that have nothing to do with the code.
    Iterator
      .iterate(Paths.get(sys.props("user.dir")).toAbsolutePath)(_.getParent)
      .takeWhile(_ != null)
      .take(6)
      .flatMap(dir => Iterator(dir.resolve("dummy-vault"), dir.resolve("obsidian-anki-custom-sync/dummy-vault")))
      .find(Files.isDirectory(_))

  def loadVault(exclude: String => Boolean = _ => false): Vector[VaultFile] =
    val root = vaultRoot.getOrElse(fail("dummy-vault not found from " + sys.props("user.dir")))
    Files
      .walk(root)
      .iterator
      .asScala
      .filter(p => p.toString.endsWith(".md"))
      .toVector
      .map(p => VaultFile(root.relativize(p).toString, Files.readString(p)))
      .filterNot(f => exclude(f.relativePath))
      .sortBy(_.relativePath)

  def newNoteOf(s: SourcedSpec, d: DeckPath, sha: String): NewNote =
    NewNote(
      noteType = s.spec.noteTypeName,
      deck = d,
      fields = s.spec.fields,
      tags = NonEmptyVector.of(TagCodec.encode(s.key), OwnedTag.sha(sha)),
    )

  // The table fixture deliberately contains two identical row concepts, so the whole vault
  // is SUPPOSED to be rejected. Excluded where the subject is the planner rather than the gate.
  val collisionFixture = "Table-Edge-Cases.md"

  // ================================================ the gate ====

  /** The fixture exists to provoke this, so the gate firing on it is the fixture working —
    * and the message must name which rows collided, not merely that something did.
    */
  test("the fixture vault's deliberate collision IS rejected, legibly") {
    val index  = VaultWalker.scan(loadVault(), deckRoot, DeckShape.FoldersOnly)
    val errors = Planner.checkUnique(index.scan.specs)

    assert(errors.nonEmpty, "the deliberate duplicate-row-concept collision was not caught")
    val message = errors.map(_.describe).mkString("\n")
    assert(message.contains("row 1"), s"collision does not say WHICH row:\n$message")
    assert(message.contains("row 2"), s"collision does not say WHICH row:\n$message")
    assert(message.contains(".md:"), s"collision does not name a line:\n$message")
  }

  // ================================================ the law, on real specs ====

  test("the fixture vault extracts cards at all") {
    val index = VaultWalker.scan(loadVault(_.contains(collisionFixture)), deckRoot, DeckShape.FoldersOnly)
    assert(index.scan.specs.sizeIs > 20, s"only ${index.scan.specs.size} specs extracted")
  }

  test("LAW on REAL specs: plan, apply, and the next plan is empty") {
    val index = VaultWalker.scan(loadVault(_.contains(collisionFixture)), deckRoot, DeckShape.FoldersOnly)

    // THE CLOZE-SEQUENCE OPT-IN IS GONE FROM THIS CALL SITE, and it is worth saying why,
    // because it was here on purpose.
    //
    // WHAT USED TO BE HERE: `InMemoryAnki.defaultNoteTypes + (ClozeSequence -> …)`. The four
    // types in `defaultNoteTypes` were Anki's STOCK four, which is what Marc's collection
    // holds, so opting the fifth in at the call site kept "the collection does not have this
    // type yet" true and VISIBLE — and what it made visible was a real gap: nothing in the
    // production path checked, before planning, that the collection HAD the types it was about
    // to write to, so the failure was discovered per note at WRITE time, after the plan had
    // been printed as though it would work.
    //
    // WHY IT CANNOT STAY: Marc ruled on 2026-08-21 that the tool writes only to note types it
    // owns, so ALL FIVE are now `Obsidian *` types and none of them is stock. There is no
    // longer a "stock collection plus one" to express. `defaultNoteTypes` now reads the five
    // manifests under `resources/note-types/`, so this fake models a collection in which the
    // installer has already run.
    //
    // THE GAP IT DEMONSTRATED IS CLOSED, and this note is kept rather than deleted so that
    // nobody re-adds the opt-in to demonstrate it again. `cli/Main.scala`'s `observeAndApply`
    // runs `NoteTypeInstaller.readiness` before the collection is enumerated and refuses when a
    // note type is absent or lacks a field this tool writes; `cli/Main.test.scala` drives that
    // against a collection with no note types at all and asserts that nothing is written.
    val anki = InMemoryAnki()

    def planNow(): Plan =
      val observed = Observer.observe(anki).fold(e => fail(s"observe: $e"), identity)
      Planner
        .plan(index.scan, observed, index.deckOf(deckRoot), newNoteOf)
        .fold(errs => fail(s"plan errors: ${errs.map(_.describe).mkString("\n")}"), identity)

    val first = planNow()
    // `RetypePolicy.Apply`: this collection starts empty, so nothing can be on a wrong note
    // type and no note-type move can arise. Applying rather than deferring means that if one
    // ever DID arise here it would be attempted and reported, rather than quietly set aside
    // and then reappearing as a non-empty second plan with no explanation.
    val failures =
      Executor.run(first, anki, RetypePolicy.Apply).fold(e => fail(s"execute: $e"), identity).failures

    // Cloze sections are not implemented yet, so they arrive as build failures rather than
    // specs — which must not stop the rest of the vault from syncing.
    assertEquals(failures, Vector.empty, s"execution failures on real fixtures: $failures")
    assertEquals(first.actions.size, index.scan.specs.size)

    assertEquals(planNow().actions, Vector.empty, "a second run over the real vault was not empty")
  }

  test("every tag derived from the real vault is one Anki will actually store") {
    val index = VaultWalker.scan(loadVault(), deckRoot, DeckShape.FoldersOnly)
    // The default note types are now the tool's own five — see the long note above.
    val anki = InMemoryAnki()
    index.scan.specs.foreach { s =>
      val note = newNoteOf(s, deckRoot, "deadbeef")
      assert(
        anki.addNote(note).isRight,
        s"Anki would reject the tag for '${s.key.path.render}': ${TagCodec.encode(s.key).value}",
      )
    }
  }

  test("decks follow the folder structure of the real vault") {
    val index = VaultWalker.scan(loadVault(_.contains(collisionFixture)), deckRoot, DeckShape.FoldersOnly)
    val rendered = index.decks.values.map(_.render).toSet
    assert(rendered.contains("Obsidian::System-Design"), s"got $rendered")
    assert(rendered.contains("Obsidian::Patterns::Nested::Deep"), s"got $rendered")
    assert(!rendered.exists(_.endsWith(".md")), s"a file became a deck level: $rendered")
  }

  /** The failures the real vault produces must all be ones we EXPECT — otherwise the law
    * above could be passing simply because most of the vault silently produced nothing.
    */
  test("the real vault's build failures are all accounted for") {
    val index = VaultWalker.scan(loadVault(_.contains(collisionFixture)), deckRoot, DeckShape.FoldersOnly)
    val reasons = index.scan.failures.collect { case BuildFailure.KeyKnown(_, _, r) => r }
    reasons.foreach { r =>
      assert(
        r.contains("cloze") || r.contains("no descriptor columns") || r.contains("nested list"),
        s"unexpected build failure from the real vault: $r",
      )
    }
    assert(index.scan.canInferOrphans, "the fixture vault should scan completely")
  }

  /** The under-indented nested list in `Patterns/Shallow-Nesting.md` is refused, and refused
    * with the FILE's own line numbers.
    *
    * ==Why this is not already covered by `ListIndentTest`==
    *
    * That suite hands the scanner a string and starts counting at line 1. Everything this test
    * adds lives in the wiring around it: that a note on disk reaches the scan at all, that the
    * refusal is attached to the ONE card whose body contains the problem, and — the part that
    * was actually wrong before it existed — that the line numbers survive frontmatter removal.
    * A body-relative line number looks entirely plausible in an error message and sends the
    * author to the wrong place, which no unit test on a frontmatter-free string can catch.
    */
  test("an under-indented nested list is refused, quoting the file's own line numbers") {
    val index  = VaultWalker.scan(loadVault(_.contains(collisionFixture)), deckRoot, DeckShape.FoldersOnly)
    val nested = index.scan.failures.collect {
      case BuildFailure.KeyKnown(_, ref, reason) if reason.contains("nested list") => (ref, reason)
    }

    assertEquals(nested.size, 1, s"expected exactly one refusal, got ${nested.map(_._1)}")
    val (ref, reason) = nested.head

    assertEquals(ref.file, "Patterns/Shallow-Nesting.md")

    // Read off the fixture, and the reason the fixture says not to re-indent it: these are the
    // lines the two offending sub-items sit on IN THE FILE, frontmatter included. Before the
    // body's origin was threaded through, this reported 22 and 24 — four lines high, exactly
    // the frontmatter that had been removed.
    assert(reason.contains("line 26"), reason)
    assert(reason.contains("line 28"), reason)

    // The remedy has to be in the message, not just the complaint.
    assert(reason.contains("4 spaces or one tab"), reason)
  }
