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

/** ONE PART OF A CARD'S LOCATION that can become a deck level.
  *
  * ==Why this is an enum and not three booleans on [[DeckShape]]==
  *
  * The record was `DeckShape(folders: Boolean, fileName: Boolean, headings: Boolean)`, and the
  * cost of that was not in the record — it was at the command line. `--deck-from` validated its
  * tokens against one hand-written list and mapped those tokens to fields in ANOTHER, fourteen
  * lines below. The two could disagree in silence: add `"tags"` to the allowlist, forget the
  * mapping, and `--help` advertises a source, the parser accepts it, `unknown` comes back
  * empty, and nothing whatsoever acts on it. A documented, accepted flag that does nothing.
  *
  * `model/Marker.scala` already solved this one file over. Its `Documented` table and
  * `fromToken` are checked against each other by a test that reads the `case` string literals
  * out of the source — "ANYONE ADDING A `case` BELOW MUST ADD A ROW HERE, and the build fails
  * otherwise". The deck sources never got that treatment; now the token table below is the only
  * place a name exists, so there is nothing left to drift.
  */
enum DeckLevel:
  case Folders
  case FileName
  case Headings

object DeckLevel:

  /** EVERY TOKEN `--deck-from` ACCEPTS, in the order the parts nest, with the gloss `--help`
    * prints. Precedent and reasoning: `Marker.Documented`.
    *
    * A case added above without a row here cannot be typed at the command line; a row here
    * naming a case that does not exist does not compile.
    */
  val Documented: Vector[(String, DeckLevel)] =
    Vector("folders" -> Folders, "file" -> FileName, "headings" -> Headings)

  val tokens: Vector[String]                  = Documented.map(_._1)
  private val byToken: Map[String, DeckLevel] = Documented.toMap

  def fromToken(token: String): Option[DeckLevel] = byToken.get(token.trim.toLowerCase)

/** Which parts of a card's location become deck levels.
  *
  * A SET, BECAUSE MEMBERSHIP IS THE ONLY CHOICE. The parts nest in document order, so the order
  * they are named in carries no meaning and must not be expressible — see the note below.
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
opaque type DeckShape = Set[DeckLevel]

object DeckShape:
  def of(levels: Set[DeckLevel]): DeckShape = levels
  extension (s: DeckShape) def levels: Set[DeckLevel] = s

  /** What the tool did before the shape was configurable, and still the default.
    *
    * THE DEFAULT MUST NOT MOVE CARDS. Every card already synced sits in a folder-derived deck,
    * so any other default would greet an existing collection with a deck move for every note.
    * Nothing is lost by that — a deck move keeps scheduling — but a run that reports hundreds
    * of changes nobody asked for is a run that stops being read.
    */
  val FoldersOnly: DeckShape = Set(DeckLevel.Folders)

/** Mapping a card's location onto an Anki deck path. */
object Decks:

  /** Cut a deck path before the first segment the card asks the reviewer to recall.
    *
    * TRUNCATION, NOT REMOVAL, and that is forced by Anki rather than chosen: a deck path is
    * prefix-closed — `A::B::C` means C inside B inside A — so a middle segment cannot be
    * dropped without re-parenting the card somewhere that does not exist. Everything below an
    * offending segment goes with it.
    *
    * BY VALUE, NOT BY POSITION. Cutting the slot the concept came from is not sufficient, and
    * the case that proves it is the commonest Obsidian convention there is: `Surjection.md`
    * whose body opens `# Surjection`. The concept is the heading, so cutting before the
    * headings leaves `…::Math::Surjection` — the file name, carrying the same word, and the
    * answer is printed anyway. One string, two slots; removing one slot removes one copy.
    *
    * COMPARED UNDER [[HeadingSegment]]'s CANONICAL FORM — NFC, trimmed, whitespace-collapsed,
    * case-folded — because a deck segment and a heading are author text that may differ in all
    * four ways while naming the same thing, and a spoiler that leaks on a capital letter is
    * worse than no check at all.
    */
  def clamp(segments: Vector[String], recall: RecallText): Vector[String] =
    val avoid = recall.values.map(TagCodec.canonical).filter(_.nonEmpty).toSet
    if avoid.isEmpty then segments
    else
      segments.indexWhere(s => avoid.contains(TagCodec.canonical(s))) match
        case -1 => segments
        case i  => segments.take(i)

  /** A deck path, plus anything the anti-spoiler rule cut off the end of it.
    *
    * `clampedAway` IS NOT AN ERROR AND IS NOT EMPTY-MEANS-FINE-EITHER. A clamped card is filed
    * CORRECTLY, merely more shallowly than the shape asked for — unlike a segment containing
    * `::`, for which no correct deck exists at all. So this is reported rather than refused.
    *
    * But it is REPORTED, and that is the point of carrying it rather than discarding it inside
    * `compose`: this file already objects, three lines below, that "a deck path silently shorter
    * than asked for is a card filed somewhere the author did not choose and would have to notice
    * by eye". Clamping makes exactly that happen, on purpose. The remedy is to say so.
    */
  final case class ComposedDeck(path: DeckPath, clampedAway: Vector[String])

  /** Compose the deck a card belongs in from the parts its [[DeckShape]] selects, cut short of
    * anything the card asks the reviewer to recall.
    *
    * Returns the reason on the left rather than dropping a bad segment, because a deck path
    * silently shorter than asked for is a card filed somewhere the author did not choose and
    * would have to notice by eye.
    *
    * THE ROOT IS NEVER CLAMPED, and that is a property of this function rather than of
    * [[clamp]], which is uniform over whatever it is handed. The root is not derived from the
    * card's location — it is a constant prefix the author chose, and it is what makes the deck
    * a deck. Clamping it would leave a card with no deck at all, which is not a shallower
    * filing but an impossible one.
    */
  def compose(
      root: DeckPath,
      shape: DeckShape,
      source: DeckSource,
      recall: RecallText,
  ): Either[String, ComposedDeck] =
    // NAMED ALONGSIDE THE VALUES, so a refusal can say "heading 'A::B'" rather than "'A::B'".
    // A message that does not say which of three sources produced the segment sends the
    // author looking through a folder tree for a string that is in a heading.
    // DRIVEN BY `DeckLevel.values`, WHICH IS THE NESTING ORDER, and matched rather than
    // branched. Three `if`s on three boolean fields stood here; a fourth part could be
    // selected at the command line and contribute nothing, in silence, because no `if`
    // mentioned it. The compiler asks this match about every part there is.
    val selected: Vector[(String, String)] =
      DeckLevel.values.toVector.filter(shape.levels.contains).flatMap {
        case DeckLevel.Folders  => source.folders.map("folder" -> _)
        case DeckLevel.FileName => Vector("file name" -> source.fileName)
        case DeckLevel.Headings => source.headings.map("heading" -> _)
      }

    // TRIMMED BEFORE ANYTHING ELSE, because Anki trims deck names itself: leaving the padding
    // on would have the tool believe in a deck named `" Spaced "` that Anki calls `"Spaced"`,
    // and every run would then try to move the card into a deck it is already in.
    val trimmed = selected.map((kind, value) => (kind, value.trim)).filter((_, value) => value.nonEmpty)

    // CHECKED OVER THE SELECTION AND NOT OVER THE SOURCE. A `::` in a segment the shape does
    // not use cannot reach a deck path, so refusing it would be the tool objecting to
    // something it is not looking at.
    // CHECKED BEFORE CLAMPING, DELIBERATELY. A `::` in a segment the clamp is about to remove
    // could not reach a deck path, so checking afterwards would refuse it for one card and
    // accept it for its sibling under a different marker. A refusal that depends on which
    // marker a heading carries is one an author cannot predict; keeping the check ahead of the
    // clamp makes it a property of the LOCATION, which is where the author can see it.
    trimmed.find((_, value) => value.contains("::")) match
      case Some((kind, value)) =>
        Left(s"$kind '$value' contains '::', which is Anki's deck separator")
      case None =>
        val selectedSegments = trimmed.map(_._2)
        val kept             = clamp(selectedSegments, recall)
        Right(
          ComposedDeck(
            DeckPath(NonEmptyVector.fromVectorUnsafe(root.segments.toVector ++ kept)),
            clampedAway = selectedSegments.drop(kept.length),
          )
        )

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
    // NO RECALL TEXT, because there is no card here to spoil. This function answers "where
    // does this FILE file", for callers that have a path and nothing else; a clamp needs a
    // card, and passing `none` says so rather than leaving it to be inferred.
    compose(root, DeckShape.FoldersOnly, sourceFor(relativeFilePath, Vector.empty), RecallText.none)
      .map(_.path)

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
          val marked = parsed.fold(_ => false, doc => Extractor.hasMarkedHeading(doc.content))

          // INTENT DECLARED IN THE FRONTMATTER AND NOWHERE ELSE. Read off the RAW block rather
          // than the parsed keys, because `Frontmatter.parse` keeps only scalar values and a
          // `tags:` list is dropped before it gets here — which is the very shape this looks
          // for. A substring match is enough: the block is small, and anything naming
          // `flashcard` in it is a statement of what the note was meant to be.
          val frontmatterNamesFlashcard =
            split.frontmatter.exists(_.toLowerCase.contains("flashcard"))

          // THE MARKER WENT TO THE WRONG PLACE. Typing `#flashcard/3way` into a note in the
          // Obsidian desktop app lifts it out of the text and files it under the frontmatter
          // `tags` property, leaving a note that LOOKS marked and produces nothing. Reported
          // wherever it happens — with an id or without one — because the note has said what it
          // was for and the gap between that and its headings is the whole message.
          if frontmatterNamesFlashcard && !marked then
            failures += BuildFailure.MarkerNotOnHeading(
              file.relativePath,
              "its frontmatter names 'flashcard' but no HEADING carries a marker, so it makes " +
                "no cards — a marker goes on the heading itself, as in " +
                "'## Some descriptor #flashcard/3way'. Typing one into the Obsidian editor " +
                "files it under the 'tags' property instead, where this tool cannot see it",
            )

          keys.get("id").map(NoteId.fromFrontmatter) match
            // NO ID. Whether that is a mistake depends entirely on whether the file asked for
            // cards, and only the parsed document can say. A note with marked headings is an
            // author who will get nothing and must be told; a note without them is ordinary
            // prose — the vast majority of any real vault — and saying anything at all about it
            // is the noise that stops a report being read.
            case None =>
              parsed match
                case Right(_) if marked =>
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
                    Decks.compose(
                      deckRoot,
                      shape,
                      Decks.sourceFor(file.relativePath, s.sectionTitles),
                      s.recall,
                    ) match
                      case Right(composed) =>
                        specs += s
                        decks += s.key -> composed.path
                      case Left(reason) =>
                        failures += BuildFailure.KeyKnown(s.key, s.source, s"deck: $reason")
                  }
    }

    VaultIndex(VaultScan.from(specs.result(), failures.result()), decks.result())
