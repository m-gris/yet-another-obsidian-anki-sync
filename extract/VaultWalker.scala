package obsidiananki.extract

import cats.data.NonEmptyVector
import obsidiananki.anki.DeckPath
import obsidiananki.model.*
import obsidiananki.parser.ObsidianSyntax
import obsidiananki.plan.{BuildFailure, SourceKind, SourceRef, VaultScan}

/** One markdown file's path and contents.
  *
  * The walk takes files as DATA rather than reading them itself, so the whole of it is pure
  * and testable without a filesystem. Enumerating and reading files is a thin shell around
  * this, not part of it.
  */
final case class VaultFile(relativePath: String, content: String)

/** The scan, plus the deck each card belongs in.
  *
  * Decks are carried alongside rather than inside [[obsidiananki.plan.SourcedSpec]] because a
  * deck is a property of the card's LOCATION IN THE VAULT, not of the card itself — and the
  * planner takes it as a function for exactly that reason.
  */
final case class VaultIndex(scan: VaultScan, decks: Map[CardKey, DeckPath]):
  def deckOf(root: DeckPath): CardKey => DeckPath = key => decks.getOrElse(key, root)

/** Every deck level a card's location COULD contribute, in document order.
  *
  * The ingredients, not the dish. A [[DeckShape]] decides which of them are used, and holding
  * them apart is what lets the scan stay one thing while the deck is a preference — the scan
  * carries this, and the deck is composed from it afterwards.
  *
  * `headings` IS THE SECTION CHAIN AND NOT THE CARD KEY'S PATH. Two differences, both
  * load-bearing:
  *
  *   - It is DISPLAY TITLES, so a deck reads `Read-your-writes consistency` and not the
  *     canonical `read-your-writes consistency` the key carries. Same argument as
  *     [[CardContext]], which took the same decision for the on-card breadcrumb.
  *   - It STOPS AT THE MARKED HEADING. A table's card keys continue into the row concept and
  *     the column header; following them would mint a deck per table row, which is the
  *     cardinality the "the file is not a deck level" note below exists to avoid. Every card a
  *     marked heading produces shares that heading's deck.
  */
final case class DeckSource(folders: Vector[String], fileName: String, headings: Vector[String])

/** Which parts of a card's location become deck levels.
  *
  * RULED BY MARC, 2026-08-22 (`docs/REQUIREMENTS.md` item 11): folder path, file
  * name and heading path are COMPLEMENTARY segments of one deck path, each optional. How decks
  * are shaped is a way of thinking, not a correctness property, so the mechanism is exposed
  * rather than a preference embedded.
  *
  * THERE IS NO ORDERING FREEDOM, and that is deliberate rather than an omission. The three
  * sources nest in document order — a file lives in a folder, a heading lives in a file — so a
  * deck that put the heading above the folder would describe a containment that does not
  * exist. What is optional is WHICH sources appear, not their order.
  *
  * The overlap between `fileName` and `headings` is the AUTHOR'S to resolve, not this type's.
  * A vault whose every file opens with an H1 restating its own name — which `dummy-vault` does,
  * all twelve — yields `…::Replication::Replication::Read-your-writes consistency` with both
  * selected. That is a legible consequence of the composition asked for, so it is left visible
  * instead of being silently de-duplicated: a rule that dropped a repeat would also drop a
  * heading that genuinely repeats its parent, and the author could not tell which had happened.
  */
final case class DeckShape(folders: Boolean, fileName: Boolean, headings: Boolean)

object DeckShape:

  /** What the tool did before the shape was configurable, and still the default.
    *
    * THE DEFAULT MUST NOT MOVE CARDS. Every card already synced sits in a folder-derived deck,
    * so any other default would greet an existing collection with a deck move for every note.
    * Nothing is lost by that — a deck move keeps scheduling — but a run that reports hundreds
    * of changes nobody asked for is a run that stops being read.
    */
  val FoldersOnly: DeckShape = DeckShape(folders = true, fileName = false, headings = false)

/** Mapping a card's location onto an Anki deck path. */
object Decks:

  /** Compose the deck a card belongs in from the parts its [[DeckShape]] selects.
    *
    * Returns the reason on the left rather than dropping a bad segment, because a deck path
    * silently shorter than asked for is a card filed somewhere the author did not choose and
    * would have to notice by eye.
    */
  def compose(root: DeckPath, shape: DeckShape, source: DeckSource): Either[String, DeckPath] =
    // NAMED ALONGSIDE THE VALUES, so a refusal can say "heading 'A::B'" rather than "'A::B'".
    // A message that does not say which of three sources produced the segment sends the
    // author looking through a folder tree for a string that is in a heading.
    val selected: Vector[(String, String)] =
      (if shape.folders then source.folders.map("folder" -> _) else Vector.empty) ++
        (if shape.fileName then Vector("file name" -> source.fileName) else Vector.empty) ++
        (if shape.headings then source.headings.map("heading" -> _) else Vector.empty)

    // TRIMMED BEFORE ANYTHING ELSE, because Anki trims deck names itself: leaving the padding
    // on would have the tool believe in a deck named `" Spaced "` that Anki calls `"Spaced"`,
    // and every run would then try to move the card into a deck it is already in.
    val trimmed = selected.map((kind, value) => (kind, value.trim)).filter((_, value) => value.nonEmpty)

    // CHECKED OVER THE SELECTION AND NOT OVER THE SOURCE. A `::` in a segment the shape does
    // not use cannot reach a deck path, so refusing it would be the tool objecting to
    // something it is not looking at.
    trimmed.find((_, value) => value.contains("::")) match
      case Some((kind, value)) =>
        Left(s"$kind '$value' contains '::', which is Anki's deck separator")
      case None =>
        Right(DeckPath(NonEmptyVector.fromVectorUnsafe(root.segments.toVector ++ trimmed.map(_._2))))

  /** Split a vault-relative file path into the deck levels it could contribute.
    *
    * A note at the vault root has NO folders, so with the default shape it lands in the root
    * deck rather than one of its own. The `.md` suffix comes off the file name here, at the
    * one place that knows the string is a path.
    *
    * SPLITTING ONLY — trimming and dropping blanks belong to [[compose]] and are NOT repeated
    * here. This function used to do both, and a mutation removing them changed no test at all:
    * `compose` normalises whatever it is handed, because a `DeckSource` can also be built by
    * hand. Two places normalising the same values are free to disagree, and unobservable code
    * is where the disagreement would hide. The same mutation applied to `compose`'s trim kills
    * four tests, which is what "the normalisation lives there" means.
    */
  def sourceFor(relativeFilePath: String, headings: Vector[String]): DeckSource =
    val parts = relativeFilePath.split('/').toVector
    DeckSource(
      folders = parts.dropRight(1),
      fileName = parts.lastOption.getOrElse("").stripSuffix(".md"),
      headings = headings,
    )

  /** FOLDER path to deck path, under a root prefix. THE FILE IS NOT A DECK LEVEL.
    *
    * Making the file a level would give every concept its own two-card deck — hundreds of
    * them. The root prefix isolates synced cards from any deck made by hand, so the subtree
    * can be deleted and rebuilt without touching anything else. Both remain true of the
    * DEFAULT shape; selecting the file name or the headings is how an author asks for the
    * other arrangement, having seen what it costs.
    *
    * Decks carry FILING ONLY, never learning order: study scope comes from filtered decks
    * over tags and introduction order from new-card position. Conflating the three is what
    * sank the earlier design.
    *
    * DELEGATES RATHER THAN REIMPLEMENTS, and that is the point of keeping it. The default
    * shape must place every already-synced card exactly where this function put it, forever;
    * two bodies that merely agree today would be free to drift, and the drift would show up as
    * a deck move for every note in a collection. One body cannot drift. What is left here is a
    * NAME for the folder-only arrangement and the reasoning behind it.
    */
  def fromRelativePath(root: DeckPath, relativeFilePath: String): Either[String, DeckPath] =
    compose(root, DeckShape.FoldersOnly, sourceFor(relativeFilePath, Vector.empty))

/** Turning a whole vault into a scan. */
object VaultWalker:

  /** Read every file, extract its cards, and classify what went wrong.
    *
    * The three failure classes are not cosmetic — they decide how far orphan inference is
    * trusted:
    *   - a card that failed to build keeps its KEY, so it is excluded individually
    *   - a heading that yields no key suppresses its whole NOTE
    *   - a file that cannot be read at all degrades the SCAN, and no orphan set is produced
    *
    * `shape` HAS NO DEFAULT. A default here would be a behavioural choice that production
    * could stop making without anything failing, while every test kept exercising the one the
    * default names — the shadow-path shape this project already has a scar from. Callers that
    * want the old arrangement ask for [[DeckShape.FoldersOnly]] by name.
    */
  def scan(files: Vector[VaultFile], deckRoot: DeckPath, shape: DeckShape): VaultIndex =
    val specs    = Vector.newBuilder[obsidiananki.plan.SourcedSpec]
    val failures = Vector.newBuilder[BuildFailure]
    val decks    = Map.newBuilder[CardKey, DeckPath]

    // Sorted so a scan is reproducible: the same vault must always yield the same plan, or
    // "a second run changes nothing" becomes dependent on directory iteration order.
    files.sortBy(_.relativePath).foreach { file =>
      val fileName = file.relativePath.split('/').lastOption.getOrElse("").stripSuffix(".md")

      def unreadable(reason: String): Unit =
        failures += BuildFailure.FileUnreadable(file.relativePath, reason)

      // ONE FILE'S TROUBLE MUST NOT COST THE WHOLE VAULT ITS ORPHAN INFERENCE. `unreadable`
      // does exactly that — every `FileUnreadable` degrades the entire scan — so it is now
      // reserved for the one situation that earns it: WE CANNOT TELL WHAT THE FILE OWNS. That
      // is true when its frontmatter will not parse, and when it declares an id we cannot use;
      // in both cases the file may have produced Anki notes under an id we are unable to read,
      // and flagging those as orphans would suspend live cards.
      //
      // Everything else below is reported and SCOPED. A card's identity is
      // `(frontmatter id, heading path)`, so a file whose frontmatter READS FINE and simply has
      // no id has never produced a note and owns nothing; and a file whose id is good but whose
      // markdown will not parse owns only notes under THAT id, which can be suppressed by
      // themselves. _Reordered 2026-08-22: the parse now happens before the id is demanded, so
      // "does this file even want cards" can be asked of a document that has no id._
      Frontmatter.read(file.content) match
        case Left(err) => unreadable(s"frontmatter: $err")
        case Right((keys, split)) =>
          val body   = split.body
          val parsed = ObsidianSyntax.markupParser.parse(body)
          keys.get("id").map(NoteId.fromFrontmatter) match
            // NO ID. Whether that is a mistake depends entirely on whether the file asked for
            // cards, and only the parsed document can say. A note with marked headings is an
            // author who will get nothing and must be told; a note without them is ordinary
            // prose — the vast majority of any real vault — and saying anything at all about it
            // is the noise that stops a report being read.
            case None =>
              parsed match
                case Right(doc) if Extractor.hasMarkedHeading(doc.content) =>
                  failures += BuildFailure.MarkedWithoutNoteId(
                    file.relativePath,
                    "has #flashcard heading(s) but no 'id' in its frontmatter, so its cards " +
                      "cannot be keyed — add an id to the frontmatter",
                  )
                // Asked for nothing we can see, and owns nothing, having no id. Stays quiet.
                case Right(_) | Left(_) => ()

            case Some(Left(e)) => unreadable(s"unusable id: $e")
            case Some(Right(noteId)) =>
              parsed match
                // THE ID IS GOOD, SO THE BLAST RADIUS IS THIS NOTE. Every key this file could
                // own begins with `noteId`, and `KeyUnderivableInFile` suppresses exactly those
                // from orphan inference while the rest of the vault carries on. This was a
                // `FileUnreadable` until 2026-08-22, which threw away every other file's orphan
                // inference over one file's syntax error.
                case Left(err) =>
                  failures += BuildFailure.KeyUnderivableInFile(
                    noteId,
                    SourceRef(file.relativePath, 0, SourceKind.Heading),
                    s"markdown: ${err.toString.take(200)}",
                  )
                case Right(doc) =>
                  val note =
                    Extractor.fromDocument(
                      noteId,
                      fileName,
                      file.relativePath,
                      doc.content,
                      body,
                      split.bodyFirstLine,
                    )
                  failures ++= note.failures

                  // COMPOSED PER CARD, WHERE IT USED TO BE PER FILE, because the heading chain
                  // differs between two cards in one file. A card whose deck cannot be built
                  // is NOT kept: a spec with no entry in `decks` would fall back to the root
                  // deck through `VaultIndex.deckOf` and be filed somewhere nobody chose.
                  //
                  // ITS BLAST RADIUS IS THE CARD, and it moved there from the file. The check
                  // used to run before parsing, so a folder carrying `::` reported one
                  // `FileUnreadable` and cost the whole scan its ability to infer orphans.
                  // Reporting `KeyKnown` per card is strictly better on both counts: the keys
                  // ARE known here, so the affected notes can be sheltered individually and
                  // the rest of the vault keeps its orphan inference. It is also the only
                  // shape that can express a refusal caused by ONE heading.
                  note.specs.foreach { s =>
                    Decks.compose(deckRoot, shape, Decks.sourceFor(file.relativePath, s.sectionTitles)) match
                      case Right(deck) =>
                        specs += s
                        decks += s.key -> deck
                      case Left(reason) =>
                        failures += BuildFailure.KeyKnown(s.key, s.source, s"deck: $reason")
                  }
    }

    VaultIndex(VaultScan.from(specs.result(), failures.result()), decks.result())
