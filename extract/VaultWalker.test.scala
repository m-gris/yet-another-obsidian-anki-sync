package obsidiananki.extract

import cats.data.NonEmptyVector
import obsidiananki.anki.DeckPath
import obsidiananki.model.*
import obsidiananki.plan.{BuildFailure, VaultScan}

class VaultWalkerTest extends munit.FunSuite:

  val root: DeckPath = DeckPath(NonEmptyVector.one("Obsidian"))

  def deck(path: String): DeckPath =
    Decks.fromRelativePath(root, path).fold(e => fail(s"deck mapping failed: $e"), identity)

  def scan(files: (String, String)*): VaultIndex = scanWith(DeckShape.FoldersOnly, files*)
  def scanWith(shape: DeckShape, files: (String, String)*): VaultIndex =
    VaultWalker.scan(files.toVector.map(VaultFile.apply.tupled), root, shape)

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
    val first  = VaultWalker.scan(files.map(VaultFile.apply.tupled), root, DeckShape.FoldersOnly)
    val second = VaultWalker.scan(files.map(VaultFile.apply.tupled), root, DeckShape.FoldersOnly)
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
    Decks.compose(root, shape, source, RecallText.none).fold(e => fail(s"compose: $e"), _.path.render)

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
        Decks.compose(root, DeckShape.FoldersOnly, source, RecallText.none).map(_.path.render),
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
      composed(DeckShape.of(Set(DeckLevel.Folders, DeckLevel.Headings))),
      "Obsidian::System-Design::Replication::Read-your-writes consistency",
    )
  }

  /** THE OVERLAP IS SHOWN, NOT SMOOTHED AWAY. See `DeckShape`'s note: a rule that dropped the
    * repeat could not tell this apart from a heading that genuinely repeats its parent.
    */
  test("selecting both the file name and the headings repeats a name that appears in both") {
    assertEquals(
      composed(DeckShape.of(Set(DeckLevel.Folders, DeckLevel.FileName, DeckLevel.Headings))),
      "Obsidian::System-Design::Replication::Replication::Read-your-writes consistency",
    )
  }

  test("headings alone drop the folder, keeping the whole section chain") {
    assertEquals(
      composed(DeckShape.of(Set(DeckLevel.Headings))),
      "Obsidian::Replication::Read-your-writes consistency",
    )
  }

  test("the file name alone makes the file a deck level, which the folder mapping never does") {
    assertEquals(
      composed(DeckShape.of(Set(DeckLevel.Folders, DeckLevel.FileName))),
      "Obsidian::System-Design::Replication",
    )
  }

  /** Selecting nothing is a legal composition and means one flat deck. It is not refused,
    * because "put everything in one deck" is a real way to work, not a mistake.
    */
  test("selecting no source at all files every card in the root deck") {
    assertEquals(
      composed(DeckShape.of(Set.empty)),
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
      .compose(root, DeckShape.of(Set(DeckLevel.Folders, DeckLevel.Headings)), bad, RecallText.none)
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
      Decks.compose(root, DeckShape.FoldersOnly, bad, RecallText.none).map(_.path.render),
      Right("Obsidian::System-Design"),
    )
  }

  test("a file name containing Anki's separator is refused when the file name is selected") {
    val bad = replication.copy(fileName = "we::ird")
    assert(
      Decks.compose(root, DeckShape.of(Set(DeckLevel.Folders, DeckLevel.FileName)), bad, RecallText.none).isLeft
    )
  }

  test("surrounding whitespace is trimmed from a heading, as Anki would trim it") {
    val padded = replication.copy(headings = Vector("  Replication  ", " Read-your-writes  "))
    assertEquals(
      composed(DeckShape.of(Set(DeckLevel.Headings)), padded),
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
      composed(DeckShape.of(Set(DeckLevel.Headings)), blank),
      "Obsidian::Replication::Marked",
    )
  }

  // ================================================ the walk composes per CARD ====

  val withHeadings: DeckShape = DeckShape.of(Set(DeckLevel.Folders, DeckLevel.Headings))

  /** THE REASON THE COMPOSITION MOVED FROM THE FILE TO THE CARD. Two cards in one file share a
    * folder and a file name but not a heading chain, so a deck derived per file cannot tell
    * them apart and a deck derived per card must.
    */
  test("two cards in one file land in DIFFERENT decks when headings are selected") {
    val index = scanWith(
      withHeadings,
      "System-Design/Replication.md" -> note(
        "n1",
        "# Replication\n\nx\n\n## Sync tradeoff #flashcard/1way\n\nBody.\n\n" +
          "## Read-your-writes #flashcard/1way\n\nBody.\n",
      ),
    )
    assertEquals(
      index.scan.specs.map(s => index.decks(s.key).render).sorted,
      Vector(
        "Obsidian::System-Design::Replication::Read-your-writes",
        "Obsidian::System-Design::Replication::Sync tradeoff",
      ),
    )
  }

  test("a nested heading chain becomes a nested deck") {
    val index = scanWith(
      withHeadings,
      "System-Design/Consistency.md" -> note(
        "n1",
        "# Consistency\n\nx\n\n## Session guarantees\n\ny\n\n### Monotonic reads\n\nz\n\n" +
          "#### Definition #flashcard/1way\n\nBody.\n",
      ),
    )
    assertEquals(
      index.scan.specs.map(s => index.decks(s.key).render),
      Vector(
        "Obsidian::System-Design::Consistency::Session guarantees::Monotonic reads::Definition"
      ),
    )
  }

  /** THE BLAST RADIUS OF AN UNMAPPABLE DECK IS NOW THE CARD, where it used to be the file.
    *
    * The check used to run before the file was parsed, so it could only report
    * `FileUnreadable` — which costs the WHOLE SCAN its ability to infer orphans, across every
    * other file in the vault. Composed per card, the key is in hand, so the affected card is
    * reported individually and everything else keeps working. Three separate claims, asserted
    * separately below because two of them would pass on their own for the wrong reason.
    */
  test("one heading carrying Anki's separator costs one card, not the file and not the scan") {
    val index = scanWith(
      withHeadings,
      "A.md" -> note(
        "n1",
        "# A\n\nx\n\n## Fine #flashcard/1way\n\nBody.\n\n## Bad::Heading #flashcard/1way\n\nBody.\n",
      ),
    )
    assertEquals(
      index.scan.specs.map(_.key.path.render),
      Vector("a / fine"),
      "the sibling card was lost along with the bad one",
    )
    assertEquals(
      index.scan.failedKeys.map(_.path.render),
      Set("a / bad::heading"),
      "the refused card must keep its KEY, so its Anki note is sheltered from orphaning",
    )
    assert(
      index.scan.canInferOrphans,
      "one bad heading degraded the whole scan — that is the per-file behaviour this replaced",
    )
  }

  /** THE CONTROL. The same file under the default shape has nothing wrong with it: the heading
    * that carries `::` never reaches a deck path, so both cards sync.
    */
  test("the same heading is no problem at all when headings are not selected") {
    val index = scan(
      "A.md" -> note(
        "n1",
        "# A\n\nx\n\n## Fine #flashcard/1way\n\nBody.\n\n## Bad::Heading #flashcard/1way\n\nBody.\n",
      )
    )
    assertEquals(index.scan.specs.size, 2)
    assertEquals(index.scan.failures, Vector.empty)
  }

  // ============================== a file's trouble stays that file's trouble ====

  /** ==Why this section exists==
    *
    * `scan: PARTIAL` means NO orphan can be computed anywhere in the vault. Until 2026-08-22 a
    * file with no `id` produced exactly that, so a single ordinary note — prose, a template, a
    * README — switched off orphan detection for every other file. In a real vault most notes
    * are not card sources, so the tool arrived permanently partial and permanently noisy.
    *
    * The rule now: DEGRADE ONLY WHEN WE CANNOT TELL WHAT A FILE OWNS. A card's identity is
    * `(frontmatter id, heading path)`, so a file whose frontmatter reads fine and has no id has
    * never produced an Anki note and owns nothing — there is nothing to be confused about.
    */
  test("an ordinary note with no id is not a failure at all, and does not degrade the scan") {
    val index = scan(
      "Card.md"  -> note("n1", "# A\n\nx\n\n## One #flashcard/1way\n\nBody.\n"),
      "Prose.md" -> "# Just thinking\n\nNo frontmatter, no markers, not a card source.\n",
    )
    assertEquals(index.scan.specs.size, 1)
    assertEquals(index.scan.failures, Vector.empty, "an ordinary note was reported as a failure")
    assert(index.scan.canInferOrphans, "one id-less prose note switched off orphan inference")
  }

  /** THE OTHER HALF, and the reason the case above cannot simply be "ignore files with no id".
    * Marking a heading is asking for cards. Getting none, silently, is the failure mode this
    * whole design exists to prevent.
    */
  test("a note that asks for cards but has no id is reported, loudly, WITHOUT degrading") {
    val index = scan(
      "Card.md"   -> note("n1", "# A\n\nx\n\n## One #flashcard/1way\n\nBody.\n"),
      "Wanted.md" -> "# B\n\nx\n\n## Two #flashcard/2way\n\nBody.\n",
    )
    assertEquals(index.scan.specs.size, 1, "the good file's card was lost")
    assertEquals(
      index.scan.failures.collect { case BuildFailure.MarkedWithoutNoteId(f, _) => f },
      Vector("Wanted.md"),
    )
    assert(index.scan.canInferOrphans, "a keyless marked file must not cost the vault its orphans")
  }

  /** THE DISTINCTION THAT MAKES THE TWO TESTS ABOVE POSSIBLE, and the one a textual search for
    * `#flashcard` would get wrong. A marker inside a fenced code block is DOCUMENTATION — this
    * repository's own `How to write cards.md` is written that way, and so is anything a vault
    * accumulates that explains the syntax. Such a file asks for nothing and must stay silent.
    */
  test("a marker inside a code fence is documentation, not a request for cards") {
    val index = scan(
      "How to write cards.md" -> ("# How to write cards\n\nWrite a heading like this:\n\n"
        + "````markdown\n## A term #flashcard/2way\n\nIts definition.\n````\n")
    )
    assertEquals(index.scan.specs, Vector.empty)
    assertEquals(index.scan.failures, Vector.empty, "a fenced example was read as a real marker")
    assert(index.scan.canInferOrphans)
  }

  /** Markdown that will not parse, in a file whose id IS readable. Every key such a file could
    * own begins with that id, so those keys can be suppressed by themselves and the rest of the
    * vault keeps its orphan inference. This was a `FileUnreadable` until 2026-08-22.
    */
  test("a file with a good id but unparseable markdown suppresses only its own note") {
    val index = scan(
      "Good.md" -> note("n1", "# A\n\nx\n\n## One #flashcard/1way\n\nBody.\n"),
      "Bad.md"  -> note("n2", "# B\n\n" + ("[" * 400) + "\n"),
    )
    assertEquals(index.scan.specs.size, 1, "the readable file's card was lost with the bad one")
    assert(
      index.scan.canInferOrphans,
      "one file's syntax error threw away the whole vault's orphan inference",
    )
  }

  /** THE ONE THAT STILL DEGRADES, and it should. Frontmatter that will not parse might have
    * carried an id we failed to read, so the file may own Anki notes under a name we cannot
    * see — and flagging those as orphans would suspend live cards. Not knowing is different
    * from knowing there is nothing.
    */
  test("frontmatter that cannot be parsed still degrades the scan") {
    val index = scan(
      "Good.md" -> note("n1", "# A\n\nx\n\n## One #flashcard/1way\n\nBody.\n"),
      "Bad.md"  -> "---\nid: x\n  bad: [unclosed\n---\n\n# B\n",
    )
    assert(!index.scan.canInferOrphans, "unreadable frontmatter must still suppress orphans")
    assert(
      index.scan.failures.exists {
        case BuildFailure.FileUnreadable(_, _) => true
        case _                                 => false
      },
      s"expected a FileUnreadable, got ${index.scan.failures}",
    )
  }

  /** THE MARKER IN THE WRONG PLACE, which is what a real vault produced within minutes.
    *
    * Typing `#flashcard/3way` into a note in the Obsidian desktop app lifts it out of the text
    * and files it under the frontmatter `tags` property. The note then LOOKS marked — the
    * Properties panel says `flashcard/3way` — and makes no cards, because this tool's rule is
    * that a marker sits on a HEADING.
    *
    * Silence is wrong here even though a note with no marked heading is normally none of the
    * tool's business: the frontmatter has said what the note was for.
    */
  test("a flashcard tag in the frontmatter with no marked heading is reported") {
    val index = scan(
      "Concept.md" -> "---\nid: n1\ntags:\n  - flashcard/3way\n---\n# Descriptor\nDescription\n"
    )
    assertEquals(index.scan.specs, Vector.empty)
    assertEquals(
      index.scan.failures.collect { case BuildFailure.MarkerNotOnHeading(f, _) => f },
      Vector("Concept.md"),
    )
    assert(index.scan.canInferOrphans, "this says nothing about what the file owns")
  }

  /** THE CONTROL. A note whose marker IS on a heading must not be nagged just because the word
    * also appears in its frontmatter — which it will, since obsidian.nvim and Obsidian alike
    * keep a `tags` list and an author may well tag the note too.
    */
  test("a marked heading silences the frontmatter check, even when tags mention flashcard") {
    val index = scan(
      "Good.md" ->
        "---\nid: n1\ntags:\n  - flashcard/3way\n---\n\n# Concept\n\n## Descriptor #flashcard/3way\n\nDescription.\n"
    )
    assertEquals(index.scan.specs.size, 1)
    assertEquals(index.scan.failures, Vector.empty, "a correctly marked note was nagged")
  }

  // ================================ the deck may not print the answer ====

  /** ==Why a deck path needs an anti-spoiler rule at all==
    *
    * Every one of the eight card front templates opens
    * `<div class="context"><span class="deck">{{Deck}}</span>…`, at full body size. The deck
    * path is therefore ON THE CARD, next to the breadcrumb — not sidebar chrome. A deck segment
    * naming the answer prints the answer above the question.
    *
    * `CardContext` has defended against that since it was written, per card shape, at five call
    * sites. `Decks.compose` never has: it takes a location and a shape, knows nothing about
    * what any card asks, and its only refusal is on `::`.
    *
    * ==Why truncation, and why by value==
    *
    * A deck path is prefix-closed, so a middle segment cannot be dropped without re-parenting
    * the card somewhere that does not exist. And cutting the SLOT the answer came from is not
    * enough — see the `Surjection.md` test below, which is the case that killed the purely
    * structural version of this rule.
    */
  private def clamped(path: Vector[String], recall: String*): Vector[String] =
    Decks.clamp(path, RecallText(recall.toVector))

  test("a path holding nothing the card asks for is left whole") {
    assertEquals(
      clamped(Vector("Obsidian", "Anatomy", "Skeletal", "Bones of the hand"), "Scaphoid"),
      Vector("Obsidian", "Anatomy", "Skeletal", "Bones of the hand"),
    )
  }

  test("a card that asks for nothing from its location is never clamped") {
    val path = Vector("Obsidian", "Anatomy", "Scaphoid", "Blood supply")
    assertEquals(Decks.clamp(path, RecallText.none), path)
  }

  /** THE CASE THAT KILLED THE STRUCTURAL RULE, and the reason this compares by VALUE.
    *
    * `Math/Surjection.md` whose body opens `# Surjection` — the commonest Obsidian convention
    * there is, and one `dummy-vault` follows in all twelve files. The concept is the HEADING,
    * so cutting before the headings leaves the FILE NAME behind, carrying the same word. One
    * string in two slots; removing one slot removes one copy, and the answer is printed anyway.
    */
  test("the answer is cut wherever it appears, not merely where it came from") {
    assertEquals(
      clamped(Vector("Obsidian", "Math", "Surjection"), "Surjection"),
      Vector("Obsidian", "Math"),
    )
  }

  /** Everything below an offending segment goes with it, because a deck path is prefix-closed:
    * `A::B::C` means C inside B inside A, so B cannot be removed while keeping C.
    */
  test("truncation takes everything below the offending segment") {
    assertEquals(
      clamped(Vector("Obsidian", "Math", "Surjection", "Definition"), "Surjection"),
      Vector("Obsidian", "Math"),
    )
  }

  /** A deck segment and a heading are both author text and may differ in case, in spacing, or
    * in Unicode normal form while naming the same thing. A spoiler that leaks on a capital
    * letter is worse than no check at all, so the comparison uses the canonical form the card
    * key itself is built on.
    */
  test("the comparison survives case, spacing and normal form") {
    assertEquals(clamped(Vector("Obsidian", "SCAPHOID"), "Scaphoid"), Vector("Obsidian"))
    assertEquals(clamped(Vector("Obsidian", "Bones  of   the hand"), "Bones of the hand"), Vector("Obsidian"))
    // "é" as e + U+0301 in the path, precomposed in the answer.
    assertEquals(clamped(Vector("Obsidian", "Café"), "Café"), Vector("Obsidian"))
  }

  /** A two-way card asks for its own heading on the reverse; a three-field card asks for its
    * concept. A card can therefore carry more than one thing to avoid, and the SHALLOWEST cut
    * wins — anything else would leave a segment the other card spoils.
    */
  test("with several things to avoid, the shallowest cut wins") {
    assertEquals(
      clamped(Vector("Obsidian", "Anatomy", "Bones", "Scaphoid"), "Scaphoid", "Bones"),
      Vector("Obsidian", "Anatomy"),
    )
  }

  /** The root is a deck segment like any other and is NOT exempt: a vault whose root deck is
    * named after what its cards ask would spoil every one of them. Returning an empty path is
    * the honest answer; the caller decides what to do with it.
    */
  test("not even the root is exempt") {
    assertEquals(clamped(Vector("Obsidian"), "Obsidian"), Vector.empty)
  }

  test("blank things-to-avoid are ignored rather than matching every blank segment") {
    val path = Vector("Obsidian", "Anatomy")
    assertEquals(Decks.clamp(path, RecallText(Vector("", "   "))), path)
  }

  // ============================ the deck-source tokens cannot drift ====

  /** ==Why this reads the source file==
    *
    * `--deck-from` used to validate its tokens against one hand-written list and map them to
    * record fields in another, fourteen lines apart. Add a name to the first and forget the
    * second, and `--help` advertises a source, the parser accepts it, and NOTHING ACTS ON IT.
    * A flag that is documented, accepted, and inert.
    *
    * `DeckLevel.Documented` is now the only place a token exists, which closes the gap between
    * the two lists — but not the gap between the enum and the table. A case added to the enum
    * with no row here is simply untypeable at the command line: no error, no warning, just a
    * source nobody can select.
    *
    * So this reads the `case` declarations out of the source, exactly as
    * `model/Marker.test.scala` does for the marker tokens, and requires the two sets to agree.
    * That is the precedent this file is following, quoted from `Marker.scala`: "ANYONE ADDING A
    * `case` BELOW MUST ADD A ROW HERE, and the build fails otherwise."
    */
  // ANCHORED ON THE COMPILED CLASSES, NOT ON `user.dir` — see `TestSources`. A walk up from
  // the working directory resolved a STALE COPY of this file in the repository this tool was
  // extracted from, and a drift test reading the wrong file is a green test proving nothing.
  private def deckLevelSource: String =
    obsidiananki.TestSources.read(getClass, "extract/VaultWalker.scala")

  test("every DeckLevel case has a token, and every token names a case") {
    // The `case` lines of `enum DeckLevel`, and nothing else in the file: anchored to a
    // two-space indent so a `case` inside any match body cannot be mistaken for one.
    val declared = """(?m)^  case (Folders|FileName|Headings)$""".r
      .findAllMatchIn(deckLevelSource)
      .map(_.group(1))
      .toSet

    assert(
      declared.sizeIs >= 3,
      s"only ${declared.size} DeckLevel cases were found in the source — the extraction is " +
        "broken, so this test is proving nothing. Fix the regex, do NOT delete the assertion.",
    )
    assertEquals(
      declared,
      DeckLevel.values.map(_.toString).toSet,
      "the source and the enum disagree about which parts of a location exist",
    )
    assertEquals(
      DeckLevel.Documented.map(_._2).toSet,
      DeckLevel.values.toSet,
      "a DeckLevel case has no --deck-from token, so no command line can select it",
    )
  }

  /** The tokens are what `--help` prints and what an error message offers, so a duplicate or a
    * blank would be a line nobody can act on.
    */
  test("the tokens are distinct and non-blank") {
    assertEquals(DeckLevel.tokens.distinct.size, DeckLevel.tokens.size)
    assert(DeckLevel.tokens.forall(_.trim.nonEmpty))
  }

  /** `Documented` is in the order the parts NEST, which is the order `compose` walks them in.
    * If the two disagreed, `--help` would list the parts in one order and a deck path would be
    * built in another.
    */
  test("the documented order is the nesting order") {
    assertEquals(DeckLevel.Documented.map(_._2), DeckLevel.values.toVector)
  }
