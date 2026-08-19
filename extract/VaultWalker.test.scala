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
