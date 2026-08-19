package obsidiananki.extract

import cats.data.NonEmptyVector
import obsidiananki.anki.DeckPath
import obsidiananki.model.*
import obsidiananki.parser.ObsidianSyntax
import obsidiananki.plan.{BuildFailure, VaultScan}

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

/** Mapping a vault folder path onto an Anki deck path. */
object Decks:

  /** FOLDER path to deck path, under a root prefix. THE FILE IS NOT A DECK LEVEL.
    *
    * Making the file a level would give every concept its own two-card deck — hundreds of
    * them. The root prefix isolates synced cards from any deck made by hand, so the subtree
    * can be deleted and rebuilt without touching anything else.
    *
    * Decks carry FILING ONLY, never learning order: study scope comes from filtered decks
    * over tags and introduction order from new-card position. Conflating the three is what
    * sank the earlier design.
    */
  def fromRelativePath(root: DeckPath, relativeFilePath: String): Either[String, DeckPath] =
    val folders = relativeFilePath.split('/').toVector.dropRight(1).map(_.trim).filter(_.nonEmpty)
    // "::" is Anki's own deck separator. A folder containing it would silently create deeper
    // nesting than the vault actually has, so it is refused rather than reinterpreted.
    folders.find(_.contains("::")) match
      case Some(bad) =>
        Left(s"folder name '$bad' contains '::', which is Anki's deck separator")
      case None =>
        // Dropping the file name is the rule: a note at the vault root lands in the root
        // deck rather than one of its own.
        Right(
          if folders.isEmpty then root
          else DeckPath(NonEmptyVector.fromVectorUnsafe(root.segments.toVector ++ folders))
        )

/** Turning a whole vault into a scan. */
object VaultWalker:

  /** Read every file, extract its cards, and classify what went wrong.
    *
    * The three failure classes are not cosmetic — they decide how far orphan inference is
    * trusted:
    *   - a card that failed to build keeps its KEY, so it is excluded individually
    *   - a heading that yields no key suppresses its whole NOTE
    *   - a file that cannot be read at all degrades the SCAN, and no orphan set is produced
    */
  def scan(files: Vector[VaultFile], deckRoot: DeckPath): VaultIndex =
    val specs    = Vector.newBuilder[obsidiananki.plan.SourcedSpec]
    val failures = Vector.newBuilder[BuildFailure]
    val decks    = Map.newBuilder[CardKey, DeckPath]

    // Sorted so a scan is reproducible: the same vault must always yield the same plan, or
    // "a second run changes nothing" becomes dependent on directory iteration order.
    files.sortBy(_.relativePath).foreach { file =>
      val fileName = file.relativePath.split('/').lastOption.getOrElse("").stripSuffix(".md")

      def unreadable(reason: String): Unit =
        failures += BuildFailure.FileUnreadable(file.relativePath, reason)

      Decks.fromRelativePath(deckRoot, file.relativePath) match
        case Left(reason) => unreadable(reason)
        case Right(deck) =>
          Frontmatter.read(file.content) match
            case Left(err) => unreadable(s"frontmatter: $err")
            case Right((keys, body)) =>
              keys.get("id").map(NoteId.fromFrontmatter) match
                // No id means no cards — but SAID, not skipped. Silently dropping a whole
                // file is the same omission the design guards against everywhere else, and
                // it also costs us the ability to group observed keys by note.
                case None          => unreadable("no 'id' in frontmatter, so no cards can be keyed")
                case Some(Left(e)) => unreadable(s"unusable id: $e")
                case Some(Right(noteId)) =>
                  ObsidianSyntax.markupParser.parse(body) match
                    case Left(err) => unreadable(s"markdown: ${err.toString.take(200)}")
                    case Right(doc) =>
                      val note =
                        Extractor.fromDocument(noteId, fileName, file.relativePath, doc.content, body)
                      specs ++= note.specs
                      failures ++= note.failures
                      note.specs.foreach(s => decks += s.key -> deck)
    }

    VaultIndex(VaultScan.from(specs.result(), failures.result()), decks.result())
