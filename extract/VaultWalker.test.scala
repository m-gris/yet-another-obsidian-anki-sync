package obsidiananki.extract

import cats.data.NonEmptyVector
import obsidiananki.anki.DeckPath
import obsidiananki.model.*
import obsidiananki.plan.{BuildFailure, VaultScan}

class VaultWalkerTest extends munit.FunSuite:

  val root: DeckPath = DeckPath(NonEmptyVector.one("Obsidian"))

  def deck(path: String): DeckPath =
    Decks.fromRelativePath(root, path).fold(e => fail(s"deck mapping failed: $e"), identity)

  def scan(files: (String, String)*): VaultIndex =
    VaultWalker.scan(files.toVector.map(VaultFile.apply.tupled), root)

  def note(id: String, body: String): String = s"---\nid: $id\n---\n\n$body"

  // ================================================ deck mapping ====

  test("the FOLDER path becomes the deck path; the file is NOT a level") {
    assertEquals(
      deck("References/Design-Gurus/Module 1.md").render,
      "Obsidian::References::Design-Gurus",
    )
  }

  test("a note at the vault root lands in the root deck") {
    assertEquals(deck("Linearizability.md").render, "Obsidian")
  }

  test("nesting is preserved to any depth") {
    assertEquals(deck("Patterns/Nested/Deep/Quorums.md").render, "Obsidian::Patterns::Nested::Deep")
  }

  /** `::` is Anki's own deck separator, so a folder containing it would silently create
    * deeper nesting than the vault has. Refused rather than quietly reinterpreted.
    */
  test("a folder name containing Anki's separator is REFUSED, not reinterpreted") {
    assert(Decks.fromRelativePath(root, "we::ird/Note.md").isLeft)
  }

  test("surrounding whitespace in a folder name is trimmed, as Anki would trim it") {
    assertEquals(deck("  Spaced  /Note.md").render, "Obsidian::Spaced")
  }

  // ================================================ the walk ====

  test("cards from several files are gathered into one scan") {
    val index = scan(
      "A.md" -> note("n1", "# A\n\nx\n\n## One #flashcard/1way\n\nBody.\n"),
      "B.md" -> note("n2", "# B\n\ny\n\n## Two #flashcard/1way\n\nBody.\n"),
    )
    assertEquals(index.scan.specs.size, 2)
    assertEquals(index.scan.failures, Vector.empty)
  }

  test("each card's deck comes from its file's folder") {
    val index = scan(
      "Patterns/Deep/A.md" -> note("n1", "# A\n\nx\n\n## One #flashcard/1way\n\nBody.\n")
    )
    val key = index.scan.specs.head.key
    assertEquals(index.decks(key).render, "Obsidian::Patterns::Deep")
  }

  test("a complete scan can infer orphans") {
    val index = scan("A.md" -> note("n1", "# A\n\nx\n\n## One #flashcard/1way\n\nBody.\n"))
    assert(index.scan.canInferOrphans)
  }

  // ================================================ the three failure classes ====

  /** A file with no id produces no cards — but that must be REPORTED. Silently skipping a
    * whole file is the same omission the design guards against everywhere else.
    */
  test("a file with no frontmatter id is reported, not silently skipped") {
    val index = scan("A.md" -> "# A\n\nx\n\n## One #flashcard/1way\n\nBody.\n")
    assertEquals(index.scan.specs, Vector.empty)
    assert(index.scan.failures.nonEmpty, "a file without an id vanished silently")
  }

  /** THE consequential one. Not knowing a file's keys means we cannot tell which observed
    * Anki notes belong to it, so absence proves nothing and NO orphan set may be produced.
    */
  test("a file that cannot be read degrades the whole scan to PARTIAL") {
    val index = scan(
      "Good.md" -> note("n1", "# A\n\nx\n\n## One #flashcard/1way\n\nBody.\n"),
      "Bad.md"  -> "---\nid: n2\n\nunterminated frontmatter\n",
    )
    assert(!index.scan.canInferOrphans, "a partial scan claimed it could infer orphans")
    assert(
      index.scan.failures.exists {
        case BuildFailure.FileUnreadable(_, _) => true
        case _                                 => false
      },
      s"expected a FileUnreadable, got ${index.scan.failures}",
    )
  }

  test("the sound files still yield their cards on a partial scan") {
    val index = scan(
      "Good.md" -> note("n1", "# A\n\nx\n\n## One #flashcard/1way\n\nBody.\n"),
      "Bad.md"  -> "---\nid: n2\n\nunterminated\n",
    )
    assertEquals(index.scan.specs.size, 1, "a readable file's cards were lost with the bad one")
  }

  test("a card that fails to build keeps its KEY, so it is not mistaken for a deletion") {
    val index = scan("A.md" -> note("n1", "# A\n\nx\n\n## One #flashcard/1way\n\n### Sub\n\ny\n"))
    assertEquals(index.scan.failedKeys.map(_.path.render), Set("a / one"))
    assert(index.scan.canInferOrphans, "an ordinary build failure should not degrade the scan")
  }

  test("a heading that extracts to nothing suppresses its whole NOTE") {
    // A heading consisting only of a marker has no segment to contribute, so no key beneath
    // it is derivable and the blast radius widens from the card to the file.
    val index = scan("A.md" -> note("n1", "# A\n\nx\n\n## #flashcard/1way\n\nBody.\n"))
    assertEquals(index.scan.suppressedNoteIds.map(_.value), Set("n1"))
  }

  test("a file whose deck cannot be mapped is reported rather than filed somewhere wrong") {
    val index = scan("we::ird/A.md" -> note("n1", "# A\n\nx\n\n## One #flashcard/1way\n\nBody.\n"))
    assert(index.scan.failures.nonEmpty, "an unmappable deck was silently accepted")
  }

  // ================================================ ordering ====

  test("the scan is stable — same input, same output") {
    val files = Vector(
      "B.md" -> note("n2", "# B\n\ny\n\n## Two #flashcard/1way\n\nBody.\n"),
      "A.md" -> note("n1", "# A\n\nx\n\n## One #flashcard/1way\n\nBody.\n"),
    )
    val first  = VaultWalker.scan(files.map(VaultFile.apply.tupled), root)
    val second = VaultWalker.scan(files.map(VaultFile.apply.tupled), root)
    assertEquals(first.scan.specs.map(_.key), second.scan.specs.map(_.key))
  }

  // ================================================ composing a deck path ====

  /** The card used throughout this section: `System-Design/Replication.md`, whose H1 is
    * `# Replication`, with the marked heading `## Read-your-writes consistency` beneath it.
    * It is `dummy-vault`'s real shape, and the H1 restating the file name is the overlap the
    * three sources have to be able to express a choice about.
    */
  val replication: DeckSource =
    DeckSource(
      folders = Vector("System-Design"),
      fileName = "Replication",
      headings = Vector("Replication", "Read-your-writes consistency"),
    )

  def composed(shape: DeckShape, source: DeckSource = replication): String =
    Decks.compose(root, shape, source).fold(e => fail(s"compose: $e"), _.render)

  /** THE ONE THAT PROTECTS EXISTING COLLECTIONS. Every card synced before the shape became
    * configurable sits in a folder-derived deck, so the default must place them exactly where
    * they already are — otherwise the first run after this change is a deck move per note.
    *
    * LITERAL STRINGS, and specifically NOT a comparison against `Decks.fromRelativePath`. That
    * function now DELEGATES to the very call under test, so comparing the two would agree no
    * matter what either did. These strings are the ones the folder-mapping tests above have
    * asserted since before the shape existed. A source carrying a file name and a heading chain
    * is passed in precisely so that selecting neither is doing visible work.
    */
  test("the default shape places a card exactly where the folder mapping already did") {
    Vector(
      "References/Design-Gurus/Module 1.md" -> "Obsidian::References::Design-Gurus",
      "Linearizability.md"                  -> "Obsidian",
      "Patterns/Nested/Deep/Quorums.md"     -> "Obsidian::Patterns::Nested::Deep",
      "  Spaced  /Note.md"                  -> "Obsidian::Spaced",
    ).foreach { (path, expected) =>
      val source = Decks.sourceFor(path, Vector("H1", "Marked heading"))
      assertEquals(
        Decks.compose(root, DeckShape.FoldersOnly, source).map(_.render),
        Right(expected),
        s"the default shape moved '$path'",
      )
    }
  }

  /** Marc's own worked example, 2026-08-22: folder, then file, then the marked heading, one
    * `Replication` — reached by NOT selecting the file name, since this vault's H1 already
    * carries it.
    */
  test("folders and headings give the deck path from the ruling") {
    assertEquals(
      composed(DeckShape(folders = true, fileName = false, headings = true)),
      "Obsidian::System-Design::Replication::Read-your-writes consistency",
    )
  }

  /** THE OVERLAP IS SHOWN, NOT SMOOTHED AWAY. See `DeckShape`'s note: a rule that dropped the
    * repeat could not tell this apart from a heading that genuinely repeats its parent.
    */
  test("selecting both the file name and the headings repeats a name that appears in both") {
    assertEquals(
      composed(DeckShape(folders = true, fileName = true, headings = true)),
      "Obsidian::System-Design::Replication::Replication::Read-your-writes consistency",
    )
  }

  test("headings alone drop the folder, keeping the whole section chain") {
    assertEquals(
      composed(DeckShape(folders = false, fileName = false, headings = true)),
      "Obsidian::Replication::Read-your-writes consistency",
    )
  }

  test("the file name alone makes the file a deck level, which the folder mapping never does") {
    assertEquals(
      composed(DeckShape(folders = true, fileName = true, headings = false)),
      "Obsidian::System-Design::Replication",
    )
  }

  /** Selecting nothing is a legal composition and means one flat deck. It is not refused,
    * because "put everything in one deck" is a real way to work, not a mistake.
    */
  test("selecting no source at all files every card in the root deck") {
    assertEquals(
      composed(DeckShape(folders = false, fileName = false, headings = false)),
      "Obsidian",
    )
  }

  /** `::` is Anki's own separator, so a segment carrying it would create deeper nesting than
    * the vault has — the same refusal the folder mapping already makes, extended to the two
    * sources that can now reach a deck path.
    */
  test("a heading containing Anki's separator is refused when headings are selected") {
    val bad = replication.copy(headings = Vector("Replication", "A::B"))
    val err = Decks
      .compose(root, DeckShape(folders = true, fileName = false, headings = true), bad)
      .swap
      .getOrElse(fail("a heading containing '::' must be refused"))
    assert(err.contains("A::B"), s"the message must name the offending segment — got: $err")
    assert(err.contains("heading"), s"the message must say which kind of segment — got: $err")
  }

  /** THE CONTROL FOR THE REFUSAL ABOVE, and the reason the check lives in `compose` rather
    * than in the walk: a segment the shape does not use cannot break a deck path, so refusing
    * it would be the tool objecting to something it is not looking at.
    */
  test("a heading containing Anki's separator is ignored when headings are NOT selected") {
    val bad = replication.copy(headings = Vector("Replication", "A::B"))
    assertEquals(
      Decks.compose(root, DeckShape.FoldersOnly, bad).map(_.render),
      Right("Obsidian::System-Design"),
    )
  }

  test("a file name containing Anki's separator is refused when the file name is selected") {
    val bad = replication.copy(fileName = "we::ird")
    assert(
      Decks.compose(root, DeckShape(folders = true, fileName = true, headings = false), bad).isLeft
    )
  }

  test("surrounding whitespace is trimmed from a heading, as Anki would trim it") {
    val padded = replication.copy(headings = Vector("  Replication  ", " Read-your-writes  "))
    assertEquals(
      composed(DeckShape(folders = false, fileName = false, headings = true), padded),
      "Obsidian::Replication::Read-your-writes",
    )
  }

  /** A heading that trims to nothing contributes no level rather than an empty one. An empty
    * deck segment is not a deck Anki can name, and the marked heading it came from has already
    * been refused elsewhere for having no derivable key.
    */
  test("a blank heading contributes no deck level") {
    val blank = replication.copy(headings = Vector("Replication", "   ", "Marked"))
    assertEquals(
      composed(DeckShape(folders = false, fileName = false, headings = true), blank),
      "Obsidian::Replication::Marked",
    )
  }
