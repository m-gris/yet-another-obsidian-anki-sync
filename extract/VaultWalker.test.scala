package obsidiananki.extract

import cats.data.NonEmptyVector
import obsidiananki.anki.DeckPath
import obsidiananki.model.*
import obsidiananki.parser.ObsidianSyntax
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

  // A fixture default. The deck-shape tests each name the source they are about; this one is the
  // shared example, and a wrong one changes the composed deck path the test asserts on.
  // ast-grep-ignore: default-parameter
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

  // ------------------------- a document nothing could read makes no claim either way ----

  /** THE BODY USED BY THE THREE TESTS BELOW, and it is chosen to make the old lie vivid.
    *
    * IT HAS A MARKED HEADING, plainly visible on line 3. It also contains `[0]`, and parsing is
    * STRICT — see `parser/ObsidianSyntax.test.scala`, "bare bracketed prose FAILS loudly under
    * strict parsing" — so the document does not parse. Until 2026-08-24 the marker search
    * folded that failure to "no marker found", and the tool then told the author that NO
    * HEADING CARRIED A MARKER about a file whose third line carries one.
    *
    * AN ARRAY INDEX IN PROSE IS ALL IT TAKES. This is not an exotic input; it is a sentence.
    */
  val unparseableBody =
    "# Concept\n\n## Descriptor #flashcard/cdd/2way\n\nAn array index like [0] in prose.\n"

  /** THE VACUITY GUARD FOR THE THREE TESTS BELOW. Every one of them means nothing unless the
    * body genuinely fails to parse — if strict parsing were ever relaxed, or if `[0]` stopped
    * being rejected, they would all pass while testing nothing at all. Asserted here once,
    * against the parser itself, rather than assumed three times.
    */
  test("the fixture body used by the marker-search tests really does fail to parse") {
    assert(
      ObsidianSyntax.markupParser.parse(unparseableBody).isLeft,
      "the body parses now, so the three tests below prove nothing — pick another " +
        "unparseable body or check whether strict parsing is still on",
    )
  }

  /** THE LIE, WITH AN ID. The file declares flashcard intent, carries a marked heading, and
    * does not parse. It must not be told that no heading carries a marker: nothing read the
    * document, so the tool is in no position to say either way.
    *
    * WHAT IT SHOULD SAY INSTEAD IS ALREADY THERE. With a usable id the unparseable markdown is
    * reported as `KeyUnderivableInFile`, which names the parser's own error and shelters this
    * note's keys from orphan inference. That is the one actionable message — fix the markdown —
    * so a second one would be noise. Its presence is also what proves the parse failed.
    */
  test("an unparseable document is NOT reported as having no marked heading") {
    val index = scan(
      "Broken.md" -> s"---\nid: n1\ntags:\n  - flashcard\n---\n\n$unparseableBody"
    )
    assert(
      index.scan.failures.exists {
        case BuildFailure.KeyUnderivableInFile(_, _, _) => true
        case _                                          => false
      },
      s"the markdown parsed after all, so this test proves nothing: ${index.scan.failures}",
    )
    assertEquals(
      index.scan.failures.collect { case BuildFailure.MarkerNotOnHeading(f, _) => f },
      Vector.empty,
      "the tool told the author that no heading carries a marker, about a document it could " +
        "not read — and whose third line carries one",
    )

    // AND NOT A SECOND MESSAGE EITHER. `MarkerUnknowable` exists for the file nothing else
    // names; this file IS named, by the `KeyUnderivableInFile` asserted above. Both would send
    // the reader to the same single action — repair the markdown — so the second is noise.
    //
    // THIS ASSERTION WAS MISSING UNTIL A MUTATION FOUND IT. Widening the guard in
    // `VaultWalker` from `case None` to `case _`, so that every unparseable intent-declaring
    // file got a `MarkerUnknowable` whether or not it already had one, left the whole suite
    // green. The claim was written in a docstring and checked by nothing.
    assertEquals(
      index.scan.failures.collect { case BuildFailure.MarkerUnknowable(f, _) => f },
      Vector.empty,
      "a file that already reports its parse error was given a second message saying the " +
        "same thing",
    )
  }

  /** THE HOLE THAT REMOVING THE LIE WOULD OTHERWISE OPEN. Same file, no id.
    *
    * Nothing else reports this file: `KeyUnderivableInFile` needs an id to name, and the
    * no-id branch stays deliberately quiet about documents that did not parse. So deleting the
    * false message without putting anything in its place would leave a note that declared it
    * wanted cards, produced none, and had NOTHING said about it — which is worse than a wrong
    * message, because a wrong message at least gets read.
    */
  test("a file that declares flashcard intent and will not parse is reported, not silenced") {
    val index = scan(
      "Broken.md" -> s"---\ntags:\n  - flashcard\n---\n\n$unparseableBody"
    )
    assertEquals(
      index.scan.failures.collect { case BuildFailure.MarkerUnknowable(f, _) => f },
      Vector("Broken.md"),
      s"nothing was said about a file that asked for cards and got none: ${index.scan.failures}",
    )
    assertEquals(
      index.scan.failures.collect { case BuildFailure.MarkerNotOnHeading(f, _) => f },
      Vector.empty,
      "still claiming no heading carries a marker, now in the no-id branch",
    )
    assert(
      index.scan.canInferOrphans,
      "a file with no id owns no Anki note, so it must not cost the vault its orphan inference",
    )
  }

  /** THE CONTROL THAT STOPS THE FIX BECOMING NOISE. An unparseable file that never mentioned
    * flashcards and has no id says nothing about itself and owns nothing. It is ordinary prose
    * — the vast majority of any real vault — and a report that names it is a report nobody
    * finishes reading. Without this, `MarkerUnknowable` could be emitted for every file that
    * fails to parse and the two tests above would still pass.
    */
  test("an unparseable file that never asked for cards stays quiet") {
    val index = scan("Prose.md" -> unparseableBody)
    assertEquals(
      index.scan.failures,
      Vector.empty,
      "ordinary prose with an array index in it was reported",
    )
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

  // ══════════════════════════════ cards made from frontmatter relations ══════

  /** A TYPED EDGE IS A CARD, END TO END — vocabulary read from the vault, properties read from a
    * note, and a three-field card out the other side.
    *
    * The fixture is the note that prompted the feature. `Function Space.md` declares
    * `special-case-of: "[[HomSet]]"`, and its headings name aspects of the concept rather than the
    * concept itself — which is why the subject can only be the file name.
    */
  /** THE DECLARATIONS BLOCK A NOTE CARRIES FOR ITSELF. Appended to a note's body in the tests
    * below, exactly as an author would write it under the properties it describes.
    */
  val declares: String =
    "\n# Properties-to-Flashcards\n\nThe relations I use here.\n\n" +
      "- special-case-of: cdd/1way\n- dual-of: cdd/2way\n"

  test("a declared property becomes a card, with the file name as its subject") {
    val index = scan(
      "Function Space.md" ->
        ("---\nid: n1\nspecial-case-of: \"[[HomSet]]\"\n---\n\n# Definition\n\nProse.\n" + declares)
    )

    val edges = index.scan.specs.filter(_.key.path match
      case CardPath.Property(_) => true
      case _                    => false)

    assertEquals(edges.size, 1, s"expected one edge card, got ${index.scan.specs.map(_.key.path.render)}")
    edges.head.spec match
      case t: CardSpec.ThreeField =>
        assertEquals(t.concept, "Function Space")
        assertEquals(t.descriptor, "special-case-of")
        assertEquals(t.description.value, "HomSet")
      case other => fail(s"an edge must be a three-field card: $other")
  }

  /** THE WHOLE REASON THE VOCABULARY IS PER NOTE. A relation earns its place in frontmatter for
    * querying and for the graph; being DRILLED on it is a separate decision. A note that carries
    * `special-case-of` and declares nothing gets no card, even though another note in the same
    * vault may declare that very property — which a vault-wide vocabulary could not express.
    */
  test("a note that carries a relation but declares nothing makes no card") {
    val index = scan(
      "Function Space.md" -> "---\nid: n1\nspecial-case-of: \"[[HomSet]]\"\n---\n\n# A #flashcard/1way\n\nb\n",
      // Another note declaring the SAME property, which must not reach across.
      "Elsewhere.md" -> ("---\nid: n2\nspecial-case-of: \"[[Thing]]\"\n---\n\n# B\n\nc\n" + declares),
    )
    assertEquals(
      index.scan.specs.map(_.key).collect { case k if isProperty(k.path) => k.noteId.value },
      Vector("n2"),
      "a declaration in one note reached across into another",
    )
    assertEquals(index.scan.failures, Vector.empty, "declaring nothing was reported as a problem")
  }

  def isProperty(p: CardPath): Boolean = p match
    case CardPath.Property(_) => true
    case _                    => false

  /** AN UNREADABLE VOCABULARY IS NOT THE SAME AS NO VOCABULARY, and the difference is the whole
    * reason it is reported. With no schema, nothing was expected. With a broken one, every
    * typed-edge card the author expects is silently absent.
    */
  /** A NOTE THAT DECLARES RELATIONS AND MAKES NONE IS NOT THE SAME as one that declares none. The
    * second is the ordinary case and says nothing; the first means the cards this note's author
    * expects are silently absent — so it is reported, and only for that note.
    */
  test("a declarations block that cannot be read is reported, and only for its own note") {
    val index = scan(
      "Broken.md" ->
        ("---\nid: n1\nspecial-case-of: \"[[HomSet]]\"\n---\n\n# A\n\nb\n" +
          "\n# Properties-to-Flashcards\n\n- special-case-of: sideways\n"),
      "Fine.md" ->
        ("---\nid: n2\nspecial-case-of: \"[[Thing]]\"\n---\n\n# B\n\nc\n" + declares),
    )
    assertEquals(
      index.scan.failures.collect { case BuildFailure.EdgeVocabularyUnusable(f, _) => f },
      Vector("Broken.md"),
    )
    assertEquals(
      index.scan.specs.map(_.key).collect { case k if isProperty(k.path) => k.noteId.value },
      Vector("n2"),
      "one note's broken declaration cost another note its cards",
    )
  }

  /** TWO NOTES MAY DECLARE THE SAME PROPERTY DIFFERENTLY, and that is not a conflict — it is the
    * feature. _There was a test here refusing a vault with two vocabulary notes; per-note scoping
    * deletes the ambiguity rather than resolving it, so the test goes with it._
    */
  test("two notes may declare the same property differently, each for itself") {
    val index = scan(
      "One.md" -> ("---\nid: n1\ndual-of: \"[[A]]\"\n---\n\n# X\n\ny\n" +
        "\n# Properties-to-Flashcards\n\n- dual-of: cdd/1way\n"),
      "Two.md" -> ("---\nid: n2\ndual-of: \"[[B]]\"\n---\n\n# X\n\ny\n" +
        "\n# Properties-to-Flashcards\n\n- dual-of: cdd/3way\n"),
    )
    assertEquals(index.scan.failures, Vector.empty, s"${index.scan.failures}")
    assertEquals(
      index.scan.specs.collect {
        case sp if isProperty(sp.key.path) =>
          sp.spec.asInstanceOf[CardSpec.ThreeField].directions
      }.toSet,
      Set(ThreeFieldDirections.ValueOnly, ThreeFieldDirections.All),
    )
  }

  /** THE PROPERTY THAT MAKES EDGES MORE ROBUST THAN HEADINGS, and it falls out of where they live
    * rather than being engineered. A relation is declared in frontmatter, and the frontmatter
    * parsed — that is how the note has an id at all — so a body the strict parser refuses costs
    * the note its heading cards and none of its edges.
    */
  test("a note whose body will not parse still declares its relations") {
    val index = scan(
      "Broken.md" -> ("---\nid: n1\nspecial-case-of: \"[[HomSet]]\"\n---\n\n" +
        "# Definition #flashcard/1way\n\nAn array index like [0] in prose.\n" + declares),
    )

    assert(
      index.scan.failures.exists {
        case BuildFailure.KeyUnderivableInFile(_, _, _) => true
        case _                                          => false
      },
      s"the body parsed after all, so this proves nothing: ${index.scan.failures}",
    )
    assertEquals(
      index.scan.specs.map(_.key.path).collect { case CardPath.Property(p) => p.value },
      Vector("special-case-of"),
      "the edge was lost along with the body",
    )
  }

  /** A REVERSIBLE EDGE THAT ASKS ONE QUESTION WITH SEVERAL RIGHT ANSWERS.
    *
    * `dual-of` is declared `2way`, so it also asks "what is the dual of X?". Two notes naming the
    * same X make two cards asking that identical question and holding different answers — so
    * whichever comes up, one of them marks you wrong. The tool can see both notes at once, which
    * is why this is detected rather than forbidden outright or left to the author.
    */
  test("a reversible edge with several right answers is refused, naming them") {
    val index = scan(
      "Product.md" -> ("---\nid: n1\ndual-of: \"[[Category]]\"\n---\n\n# A\n\nb\n" + declares),
      "Sum.md"     -> ("---\nid: n2\ndual-of: \"[[Category]]\"\n---\n\n# A\n\nb\n" + declares),
    )

    val refusals = index.scan.failures.collect {
      case BuildFailure.KeyKnown(_, _, reason) if reason.contains("dual-of") => reason
    }
    assertEquals(refusals.size, 2, s"expected both sides refused: ${index.scan.failures}")
    assert(refusals.head.contains("Product") && refusals.head.contains("Sum"), refusals.head)
  }

  /** THE CONTROL. A one-way edge asks only forwards, so the same object on many notes is
    * perfectly ordinary — and `special-case-of` is exactly the relation where that is the norm.
    * Without this, the check above could be satisfied by refusing every repeated object.
    */
  test("a one-way edge may point many notes at the same thing") {
    val index = scan(
      "Function Space.md" -> ("---\nid: n1\nspecial-case-of: \"[[HomSet]]\"\n---\n\n# A\n\nb\n" + declares),
      "Exponential.md"    -> ("---\nid: n2\nspecial-case-of: \"[[HomSet]]\"\n---\n\n# A\n\nb\n" + declares),
    )
    assertEquals(index.scan.failures, Vector.empty, s"a 1way edge was refused: ${index.scan.failures}")
    assertEquals(index.scan.specs.count(_.key.path match
      case CardPath.Property(_) => true
      case _                    => false), 2)
  }

  // ═════════════════════════════ a note with no headings IS the card ═════════

  /** THE NOTE THAT STARTED THIS. `Essential Numbers (for System Design Interview).md` is three
    * list items and a frontmatter tag saying `flashcard/sequence`. It has no headings, and asking
    * its author to add one that restates the file name is ceremony — the heading would be a worse
    * name for the card than the file already is.
    *
    * A note is a tree of nodes and a card hangs off one of them. A heading is one kind of node,
    * the way a directory is one kind of filesystem entry. A note with no headings is a leaf.
    */
  test("a headingless note whose frontmatter carries a marker becomes one card") {
    val index = scan(
      "Essential Numbers.md" ->
        ("---\nid: n1\ntags:\n  - flashcard/sequence\n---\n\n" +
          "- scale (users, requests per second, data size)\n- read/write ratio\n- the cost of downtime\n")
    )

    assertEquals(index.scan.failures, Vector.empty, s"the note was refused: ${index.scan.failures}")
    assertEquals(index.scan.specs.map(_.key.path), Vector(CardPath.Note))
    assertEquals(index.scan.specs.head.spec.noteTypeName, Marker.NoteTypes.ClozeSequence)
  }

  test("the whole-note card is named by its file, since nothing else names it") {
    val index = scan(
      "Essential Numbers.md" -> "---\nid: n1\ntags:\n  - flashcard/sequence\n---\n\n- a\n- b\n"
    )
    val fields = index.scan.specs.head.spec.fields.toMap
    assert(
      fields.values.exists(_.contains("Essential Numbers")),
      s"the file name reached no field: $fields",
    )
  }

  /** THE CASE THIS MUST NOT SWALLOW. Typing `#flashcard/2way` into the body in Obsidian lifts the
    * tag out of the text and files it under `tags`, leaving a note that LOOKS marked and makes
    * nothing. A frontmatter marker therefore means "this note is the card" exactly when there is
    * no heading it could have fallen off — which the note's own shape decides.
    */
  test("a note WITH headings and a frontmatter marker is still the Obsidian accident") {
    val index = scan(
      "Concept.md" -> "---\nid: n1\ntags:\n  - flashcard/2way\n---\n\n# Descriptor\n\nDescription.\n"
    )
    assertEquals(index.scan.specs, Vector.empty, "a preamble card was invented")
    assertEquals(
      index.scan.failures.collect { case BuildFailure.MarkerNotOnHeading(f, _) => f },
      Vector("Concept.md"),
    )
  }

  /** THE SILENCE MARC HIT ON 2026-08-28 — `IN-FLIGHT.md` item 37.
    *
    * He wrote `flashard/sequence/headers` into a note's frontmatter, one character short of
    * `flashcard`, synced, and was told NOTHING. Two checks missed it for the same reason: the
    * filter that read a marker kept tags beginning with `flashcard`, and the separate check whose
    * whole job is to say "this note declared intent and made no cards" searches the frontmatter
    * for that same string as a SUBSTRING. The one check written to catch this class of mistake
    * was defeated by a misspelling of the very string it searches for.
    *
    * THE MESSAGE MUST NAME THE CORRECTION, not merely announce a problem. The whole cost of this
    * defect was the time between "no card appeared" and "oh, a typo", and only the suggestion
    * closes that gap.
    */
  test("a frontmatter tag one character short of the marker is reported, with the correction") {
    val index = scan(
      "Outline.md" -> "---\nid: n1\ntags:\n  - flashard/sequence/headers\n---\n\n# A\n## A.1\n"
    )
    val reasons = index.scan.failures.collect { case BuildFailure.MarkerMisspelled(_, r) => r }
    assertEquals(reasons.size, 1, s"the near miss went unreported: ${index.scan.failures}")
    assert(reasons.head.contains("flashard/sequence/headers"), s"the tag is not quoted: ${reasons.head}")
    assert(
      reasons.head.contains("#flashcard/sequence/headers"),
      s"the correction is not offered, which is the only part that saves any time: ${reasons.head}",
    )
  }

  /** THE OTHER HALF, AND IT WAS REPORTED AS THE WRONG THING RATHER THAN NOT AT ALL. This tag
    * passed the old filter, failed to parse, and had its error dropped — so the author was told
    * to move a marker onto a heading, when it was already in a place the tool reads and only its
    * token was wrong.
    */
  test("a correctly prefixed frontmatter tag with an unknown token says so, not 'not on a heading'") {
    val index = scan(
      "Outline.md" -> "---\nid: n1\ntags:\n  - flashcard/sequence/hedars\n---\n\n# A\n## A.1\n"
    )
    val reasons = index.scan.failures.collect { case BuildFailure.MarkerMisspelled(_, r) => r }
    assertEquals(reasons.size, 1, s"the unknown token went unreported: ${index.scan.failures}")
    assert(reasons.head.contains("does not recognise"), s"not named as an unknown token: ${reasons.head}")
  }

  /** ONE MESSAGE, AND THE ACCURATE ONE — found by running the built tool against a throwaway
    * vault on 2026-08-28 rather than by reading the code.
    *
    * A tag reading `flashcard/…` with an unknown token trips BOTH checks: the new one, which
    * names the token, and the older one, which says the frontmatter mentions `flashcard` and no
    * heading carries a marker. The second is not merely redundant, it is WRONG ADVICE — it sends
    * the author to move a marker that is already somewhere this tool reads and whose only fault
    * is its spelling. So the precise message suppresses the general one.
    */
  test("a near miss is reported once, and not also as a marker in the wrong place") {
    val index = scan(
      "Outline.md" -> "---\nid: n1\ntags:\n  - flashcard/sequence/hedars\n---\n\n# A\n## A.1\n"
    )
    assertEquals(
      index.scan.failures.collect { case BuildFailure.MarkerNotOnHeading(f, _) => f },
      Vector.empty,
      "the author was also told to move a marker whose only problem is how it is spelled",
    )
    assertEquals(index.scan.failures.size, 1, s"more than one message: ${index.scan.failures}")
  }

  /** NO NOISE, WHICH IS WHAT KEEPS THE REPORT WORTH READING. An ordinary tag must produce
    * nothing at all, however many segments it has — a check that flagged real tags would be
    * abandoned within a week, and the abandonment would take the useful half with it.
    */
  test("ordinary frontmatter tags are not reported as near misses") {
    val index = scan(
      "Note.md" -> "---\nid: n1\ntags:\n  - maths/topology\n  - reading/2026\n---\n\n# A\n\nProse.\n"
    )
    assertEquals(
      index.scan.failures.collect { case BuildFailure.MarkerMisspelled(f, _) => f },
      Vector.empty,
      "an ordinary tag was reported as a misspelled marker",
    )
  }

  test("a marked heading still silences the frontmatter check and makes no note card") {
    val index = scan(
      "Good.md" ->
        ("---\nid: n1\ntags:\n  - flashcard/2way\n---\n\n# Concept\n\n" +
          "## Descriptor #flashcard/2way\n\nDescription.\n")
    )
    assertEquals(index.scan.failures, Vector.empty)
    assert(!index.scan.specs.map(_.key.path).contains(CardPath.Note), "a note card was also made")
  }

  /** EVERY MARKER MEANS HERE WHAT IT MEANS ON A HEADING, because the note is handed to the same
    * builder as a synthetic section. Nothing was written per marker, so this test is what says
    * they all arrived.
    */
  test("the shapes that need a name and a body all work on a whole note") {
    def one(tag: String, body: String) =
      scan("N.md" -> s"---\nid: n1\ntags:\n  - $tag\n---\n\n$body")

    assertEquals(one("flashcard/1way", "Just prose.\n").scan.specs.head.spec.noteTypeName,
                 Marker.NoteTypes.Basic)
    assertEquals(one("flashcard/2way", "Just prose.\n").scan.specs.head.spec.noteTypeName,
                 Marker.NoteTypes.BasicAndReversed)
    assertEquals(one("flashcard/cloze", "A ==highlighted== fact.\n").scan.specs.head.spec.noteTypeName,
                 Marker.NoteTypes.Cloze)
  }

  /** THE ONE SHAPE A WHOLE NOTE CANNOT BE, refused rather than quietly degraded. A
    * concept-descriptor card needs three parts; a note has two, its name and its body. The aspect
    * would have to be the name a second time, which is not a question.
    */
  test("a whole note cannot be a concept-descriptor card, and is told so") {
    val index = scan("N.md" -> "---\nid: n1\ntags:\n  - flashcard/cdd/2way\n---\n\nProse.\n")
    assertEquals(index.scan.specs, Vector.empty)
    assertEquals(index.scan.failures.size, 1, s"${index.scan.failures}")

    // ASSERTED AS THE RIGHT REFUSAL, not merely as A refusal. Before the whole-note card existed
    // this note was refused too, with "no HEADING carries a marker" — so a test counting failures
    // passed on the old behaviour and could not see the new one arrive.
    assertEquals(
      index.scan.failures.collect { case BuildFailure.MarkerNotOnHeading(f, _) => f },
      Vector.empty,
      "refused for the old reason: the marker IS the note's, there is no heading it fell off",
    )
  }

  /** THE DEFECT MARC HIT ON THE DAY THE MARKER SHIPPED — `IN-FLIGHT.md` item 35.
    *
    * He wrote `flashcard/sequence/headers` into a note's frontmatter, synced, and got a refusal
    * telling him to move the marker onto a heading. That advice was impossible to follow: a
    * whole-note structure card is made OF the note's headings, so the headings whose presence
    * triggered the refusal are the very thing the card needs.
    *
    * THE CAUSE WAS A BOOLEAN GUARD ASKING THE WRONG QUESTION. It tested whether there was SOME
    * frontmatter marker and whether the note had no headings — never WHICH marker — so every
    * marker got the answer that is correct for one that reads the note's PROSE. The marker now
    * answers what it reads and the walker matches on that answer.
    */
  test("a whole-note structure marker builds its card from the note's headings") {
    val index = scan(
      "Outline Learning.md" ->
        "---\nid: n1\ntags:\n  - flashcard/sequence/headers\n---\n\n# A\n## A.1\n## A.2\n# B\n## B.1\n"
    )
    assertEquals(
      index.scan.failures.collect { case BuildFailure.MarkerNotOnHeading(f, m) => m },
      Vector.empty,
      "still told to move the marker onto a heading, which is advice this marker cannot take",
    )
    assertEquals(index.scan.specs.size, 1, s"${index.scan.failures}")
    val fields = index.scan.specs.head.spec.fields.toMap
    assertEquals(fields("Title"), "Outline Learning")
    assertEquals(fields("Text"), "<ul><li>A</li><li>B</li></ul>")
  }

  /** THE RECURSIVE FORM THROUGH THE ROUTE AN AUTHOR ACTUALLY TAKES.
    *
    * The nesting itself is pinned at two lower levels — over hand-written trees in
    * `Outline.test.scala`, and over a marked heading in `Extractor.test.scala`. What THIS covers
    * is the whole path: a tag in frontmatter, parsed as a marker, applied to the whole note, and
    * rendered. The two-level marker token has to survive the frontmatter reader, which filters
    * tags by prefix and re-parses them with a `#` prepended, and nothing else asserts that.
    */
  test("a whole-note RECURSIVE structure marker nests every level of the note") {
    val index = scan(
      "Outline Learning.md" ->
        ("---\nid: n1\ntags:\n  - flashcard/sequence/headers/recursive\n---\n\n" +
          "# A\n## A.1\n## A.2\n# B\n## B.1\n")
    )
    assertEquals(index.scan.failures, Vector.empty, s"${index.scan.failures}")
    assertEquals(index.scan.specs.size, 1)
    assertEquals(
      index.scan.specs.head.spec.fields.toMap.apply("Text"),
      "<ul><li><p>A</p><ul><li>A.1</li><li>A.2</li></ul></li><li><p>B</p><ul><li>B.1</li></ul></li></ul>",
    )
  }

  /** THE MIRROR, AND IT IS WHAT KEEPS THE FIX FROM BEING A HOLE. A structure marker needs
    * headings, so a note without any cannot satisfy it — and must be told the actionable thing,
    * which is the opposite of what a prose marker is told in the same position.
    */
  test("a whole-note structure marker on a note with NO headings is refused, and says why") {
    val index = scan("N.md" -> "---\nid: n1\ntags:\n  - flashcard/sequence/headers\n---\n\nJust prose.\n")
    assertEquals(index.scan.specs, Vector.empty)
    val messages = index.scan.failures.collect { case BuildFailure.MarkerNotOnHeading(_, m) => m }
    assertEquals(messages.size, 1, s"${index.scan.failures}")
    assert(
      messages.head.contains("has none"),
      s"the refusal does not say the note has no headings, which is the only fix: ${messages.head}",
    )
  }

  /** THE ACCIDENT THE OLD GUARD EXISTED TO CATCH, STILL CAUGHT. Typing a marker into Obsidian's
    * editor files it under `tags`, leaving a note that looks marked and makes nothing. For a
    * marker reading PROSE that is still a mistake, and the fix above must not have quietly
    * turned every such note into a whole-note card.
    */
  test("a prose marker in frontmatter on a note WITH headings is still the Obsidian accident") {
    val index = scan("N.md" -> "---\nid: n1\ntags:\n  - flashcard/2way\n---\n\n# A\n\nSome prose.\n")
    assertEquals(index.scan.specs, Vector.empty, s"a card was built: ${index.scan.specs}")
    assertEquals(
      index.scan.failures.collect { case BuildFailure.MarkerNotOnHeading(f, _) => f },
      Vector("N.md"),
      "the marker-in-the-wrong-place report was lost",
    )
  }

  test("a headingless note with an empty body is refused, like an empty section") {
    val index = scan("N.md" -> "---\nid: n1\ntags:\n  - flashcard/1way\n---\n\n")
    assertEquals(index.scan.specs, Vector.empty)
    assertEquals(index.scan.failures.size, 1, s"${index.scan.failures}")
    assertEquals(
      index.scan.failures.collect { case BuildFailure.MarkerNotOnHeading(f, _) => f },
      Vector.empty,
      "refused for the old reason rather than for having nothing to make a card from",
    )
  }

  /** THE CONTROL. Ordinary prose with no marker anywhere is the vast majority of a real vault and
    * must stay silent — a note is not a card merely by having no headings.
    */
  test("a headingless note with no marker is not a card and is not mentioned") {
    val index = scan("Prose.md" -> "---\nid: n1\n---\n\nJust some notes to myself.\n")
    assertEquals(index.scan.specs, Vector.empty)
    assertEquals(index.scan.failures, Vector.empty)
  }

  /** A DECLARATIONS BLOCK IS METADATA, NOT STRUCTURE — and this test is the reason that ruling
    * exists rather than being an exception grudgingly made.
    *
    * A whole-note card is made when a note has no headings. `# Properties-to-Flashcards` is a
    * heading. So without the exclusion, adding a declarations block to a headingless note would
    * SILENTLY retire its whole-note card and orphan it in Anki — a behaviour change arriving from
    * an edit that has nothing to do with it, which is the worst shape of failure this project
    * recognises. The note keeps both cards.
    */
  test("declaring a relation does not cost a headingless note its whole-note card") {
    val index = scan(
      "Essential Numbers.md" ->
        ("---\nid: n1\ntags:\n  - flashcard/sequence\nspecial-case-of: \"[[Interviewing]]\"\n---\n\n" +
          "- scale\n- read/write ratio\n" + declares)
    )

    assertEquals(index.scan.failures, Vector.empty, s"${index.scan.failures}")
    assertEquals(
      index.scan.specs.map(_.key.path).map {
        case CardPath.Note        => "note"
        case CardPath.Property(_) => "property"
        case CardPath.Headings(_) => "headings"
      }.sorted,
      Vector("note", "property"),
      "the declarations heading was counted as structure and cost the note its whole-note card",
    )
  }

  // ────────────────── the heading predicate, driven directly ──────────────────

  /** DRIVEN ON HAND-BUILT SYNTAX TREES, because one of its two arms is unreachable through any
    * vault. Laika's section builder wraps every heading in a `Section`, so the bare-`Header` arm
    * is never exercised by a parsed note — a mutation proved it, by throwing from that arm and
    * killing no test.
    *
    * The arm is kept, so it is tested here. Deleting it would send a bare header to the catch-all
    * and answer "not a heading": a document full of headings would report having none, and every
    * marked note in it would become a candidate for a whole-note card. That is a total, silent
    * misreading resting on an implementation detail of Laika's rewrite rules.
    */
  test("the heading predicate answers correctly for a BARE header, which no vault produces") {
    import laika.ast.*
    def root(blocks: Block*) = RootElement(blocks.toSeq)

    assert(!hasNoHeadings(root(Header(1, Seq(Text("Concept"))))),
           "a bare header was not seen as a heading")
    assert(hasNoHeadings(root(Header(1, Seq(Text("Properties-to-Flashcards"))))),
           "a bare declarations header was counted as structure")
    assert(hasNoHeadings(root(Paragraph(Seq(Text("just prose"))))),
           "prose was counted as a heading")
    assert(hasNoHeadings(root()), "an empty document has no headings")
  }
