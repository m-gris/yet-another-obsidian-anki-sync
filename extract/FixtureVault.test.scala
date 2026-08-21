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
    val index  = VaultWalker.scan(loadVault(), deckRoot)
    val errors = Planner.checkUnique(index.scan.specs)

    assert(errors.nonEmpty, "the deliberate duplicate-row-concept collision was not caught")
    val message = errors.map(_.describe).mkString("\n")
    assert(message.contains("row 1"), s"collision does not say WHICH row:\n$message")
    assert(message.contains("row 2"), s"collision does not say WHICH row:\n$message")
    assert(message.contains(".md:"), s"collision does not name a line:\n$message")
  }

  // ================================================ the law, on real specs ====

  test("the fixture vault extracts cards at all") {
    val index = VaultWalker.scan(loadVault(_.contains(collisionFixture)), deckRoot)
    assert(index.scan.specs.sizeIs > 20, s"only ${index.scan.specs.size} specs extracted")
  }

  test("LAW on REAL specs: plan, apply, and the next plan is empty") {
    val index = VaultWalker.scan(loadVault(_.contains(collisionFixture)), deckRoot)

    // "Cloze Sequence" IS OPTED IN HERE RATHER THAN ADDED TO `defaultNoteTypes`, and the
    // distinction is the point rather than a style choice. `defaultNoteTypes` models a STOCK
    // collection, which the human's is; baking this type into it would make the whole suite
    // behave as though the slice that INSTALLS the note type had already run — and it would
    // hide that nothing in the production path checks, before planning, that the collection
    // HAS this type. `noteTypeNames` exists on the algebra (`anki/Anki.scala:107`) and is
    // implemented in both backends, and grep finds no production caller; the failure is
    // therefore discovered per note at WRITE time, after the plan has been printed as though
    // it would work. WHAT THE ERROR LOOKS LIKE AGAINST A LIVE COLLECTION IS NOT ASSERTED HERE:
    // `anki/AnkiConnect.scala:130` maps a `model was not found:` message to
    // `AnkiError.NoSuchNoteType`, and whether a real AnkiConnect answers `addNote` that way is
    // unverified — we may not contact one. Opting in at the call site keeps "the collection
    // does not have this type yet" true and visible.
    //
    // VERIFIED, so that luck is not mistaken for design: `InMemoryAnki.cardCountOf` matches the
    // note type BY EXACT NAME and falls through to `case _ => 1`. So the opted-in type yields
    // exactly ONE card — which is right, and right for this note type, but right BY
    // FALLTHROUGH rather than by any rule naming it.
    val anki = InMemoryAnki(
      InMemoryAnki.defaultNoteTypes + (Marker.NoteTypes.ClozeSequence -> Marker.ClozeSequenceFields)
    )

    def planNow(): Plan =
      val observed = Observer.observe(anki).fold(e => fail(s"observe: $e"), identity)
      Planner
        .plan(index.scan, observed, index.deckOf(deckRoot), newNoteOf)
        .fold(errs => fail(s"plan errors: ${errs.map(_.describe).mkString("\n")}"), identity)

    val first    = planNow()
    val failures = Executor.run(first, anki).fold(e => fail(s"execute: $e"), identity)

    // Cloze sections are not implemented yet, so they arrive as build failures rather than
    // specs — which must not stop the rest of the vault from syncing.
    assertEquals(failures, Vector.empty, s"execution failures on real fixtures: $failures")
    assertEquals(first.actions.size, index.scan.specs.size)

    assertEquals(planNow().actions, Vector.empty, "a second run over the real vault was not empty")
  }

  test("every tag derived from the real vault is one Anki will actually store") {
    val index = VaultWalker.scan(loadVault(), deckRoot)
    // Opted in for the same reason as above — see the note there.
    val anki = InMemoryAnki(
      InMemoryAnki.defaultNoteTypes + (Marker.NoteTypes.ClozeSequence -> Marker.ClozeSequenceFields)
    )
    index.scan.specs.foreach { s =>
      val note = newNoteOf(s, deckRoot, "deadbeef")
      assert(
        anki.addNote(note).isRight,
        s"Anki would reject the tag for '${s.key.path.render}': ${TagCodec.encode(s.key).value}",
      )
    }
  }

  test("decks follow the folder structure of the real vault") {
    val index = VaultWalker.scan(loadVault(_.contains(collisionFixture)), deckRoot)
    val rendered = index.decks.values.map(_.render).toSet
    assert(rendered.contains("Obsidian::System-Design"), s"got $rendered")
    assert(rendered.contains("Obsidian::Patterns::Nested::Deep"), s"got $rendered")
    assert(!rendered.exists(_.endsWith(".md")), s"a file became a deck level: $rendered")
  }

  /** The failures the real vault produces must all be ones we EXPECT — otherwise the law
    * above could be passing simply because most of the vault silently produced nothing.
    */
  test("the real vault's build failures are all accounted for") {
    val index = VaultWalker.scan(loadVault(_.contains(collisionFixture)), deckRoot)
    val reasons = index.scan.failures.collect { case BuildFailure.KeyKnown(_, _, r) => r }
    reasons.foreach { r =>
      assert(
        r.contains("cloze") || r.contains("no descriptor columns"),
        s"unexpected build failure from the real vault: $r",
      )
    }
    assert(index.scan.canInferOrphans, "the fixture vault should scan completely")
  }
