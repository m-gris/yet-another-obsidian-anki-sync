package obsidiananki.config

import obsidiananki.anki.DeckPath

/** WHERE A CARD SITS IN THE VAULT — the one thing every projection below is a view of.
  *
  * A card comes from somewhere: a chain of folders, a file, and a chain of headings inside that
  * file. That is the whole of what "location" means here, and it is modelled once because two
  * different things are derived from it — the Anki deck a card is filed under, and the
  * breadcrumb printed on the card itself.
  *
  * _Replaces `DeckSource` and `ContextSource`, which were the same three fields under two names.
  * Two identical records is not a distinction, it is a duplicate._
  *
  * A PATH IS TWO FIELDS, NOT ONE STRING. Keeping the folders apart from the file name is what
  * makes "the whole path" and "the directories only" both expressible; a single joined path
  * would force the pair together.
  */
final case class CardLocation(
    folders: Vector[String],
    fileName: String,
    headings: Vector[String],
)

/** One part of a [[CardLocation]] — the vocabulary a vault uses to say what it wants shown.
  *
  * A SUM TYPE RATHER THAN THREE BOOLEANS, and a SET of these rather than an ordered list. Both
  * choices remove a state that must never exist: the parts NEST — a file lives in a folder, a
  * heading lives in a file — so an arrangement putting the heading above the folder would
  * describe a containment that is not real. Order is therefore not a preference to be expressed
  * and got wrong; it is a property of the domain, applied at the point of composition. What a
  * vault chooses is WHICH parts appear, and a set says exactly that and nothing more.
  */
enum LocationPart:
  case Folders, FileName, Headings

object LocationPart:

  /** The order the parts nest in, outermost first. Not configurable — see the type's note. */
  val nesting: Vector[LocationPart] = Vector(Folders, FileName, Headings)

  /** The name a vault writes in its layout file, and the one an error message quotes back. */
  def label(part: LocationPart): String = part match
    case Folders  => "folders"
    case FileName => "file"
    case Headings => "headings"

/** HOW ONE VAULT PROJECTS ITS CARDS' LOCATIONS INTO ANKI — the vault's own declaration.
  *
  * ==Why this is a vault-level document and not a set of command-line flags==
  *
  * Every field here changes STORED STATE. A different `deckRoot` or `deckFrom` moves every card
  * to a different deck; a different `contextFrom` rewrites a card FIELD, and therefore every
  * note's content hash. So a run that forgets a flag does not merely behave differently — it
  * undoes the previous run:
  *
  * {{{
  * sync --deck-from folders,headings   ->  43 cards move
  * sync                                ->  43 cards move BACK
  * }}}
  *
  * A per-invocation switch is the wrong shape for a setting that has to be the same every time
  * or the collection churns. It is also the wrong PLACE: how a vault's cards are shaped is an
  * authoring decision, of exactly the same kind as the `#flashcard` markers themselves, and
  * those live in the vault because that is where authoring decisions belong. Declared once in
  * the vault, the setting travels with it, is versioned with it, is identical on every machine
  * and in every run, and can be read by a human who opens the vault.
  *
  * _Ruled by Marc, 2026-08-23, on being shown the churn above: "if those are bad designs, of
  * course we drop / remove them". `--deck-root` and `--deck-from` are removed rather than
  * deprecated, and no `--context-from` was ever added._
  *
  * ==What it does not decide==
  *
  * Only presentation and organisation, per REQUIREMENTS.md item 11. Nothing here can make the
  * tool silently wrong: identity, review history and refusing-rather-than-guessing are not
  * options and are not represented.
  */
final case class VaultLayout(
    deckRoot: DeckPath,
    deckFrom: Set[LocationPart],
    contextFrom: Set[LocationPart],
)

object VaultLayout:

  /** What a vault gets when it declares nothing, and what every existing vault already has.
    *
    * THE DEFAULT MUST MOVE NOTHING AND REWRITE NOTHING. Decks have always mirrored folder paths
    * under a root called `Obsidian`, and a breadcrumb has always been the heading chain. A
    * vault that adds no layout file must therefore sync exactly as it did before this type
    * existed — otherwise the first run after upgrading rewrites a whole collection.
    */
  val default: VaultLayout =
    VaultLayout(
      deckRoot = DeckPath(cats.data.NonEmptyVector.one("Obsidian")),
      deckFrom = Set(LocationPart.Folders),
      contextFrom = Set(LocationPart.Headings),
    )

  private val DeckRootKey    = "deck-root"
  private val DeckFromKey    = "deck-from"
  private val ContextFromKey = "context-from"

  private val knownKeys: Vector[String] = Vector(DeckRootKey, DeckFromKey, ContextFromKey)

  private val partNames: Map[String, LocationPart] =
    LocationPart.values.map(p => LocationPart.label(p) -> p).toMap

  /** A YAML loader with IMPLICIT TYPING TURNED OFF, so every scalar stays a string.
    *
    * THE SAME CONFIGURATION `extract/Frontmatter.scala` USES, AND FOR THE SAME REASON, which is
    * worth restating rather than cross-referencing: snakeyaml's default resolver types
    * `2026-08-18` as a date and `007` as an integer. A deck root is a NAME — `deck-root: 2026`
    * names a deck called `2026`, not the number two thousand and twenty-six — so every scalar
    * here must survive exactly as written.
    *
    * _Not shared with `Frontmatter`'s copy because that one is private to a different concern
    * and returns a `Map[String, String]`, dropping every list — and lists are precisely what a
    * layout document is made of._
    */
  private def yaml: org.yaml.snakeyaml.Yaml =
    val options = org.yaml.snakeyaml.LoaderOptions()
    val resolver = new org.yaml.snakeyaml.resolver.Resolver:
      override protected def addImplicitResolvers(): Unit = ()
    org.yaml.snakeyaml.Yaml(
      org.yaml.snakeyaml.constructor.SafeConstructor(options),
      org.yaml.snakeyaml.representer.Representer(org.yaml.snakeyaml.DumperOptions()),
      org.yaml.snakeyaml.DumperOptions(),
      options,
      resolver,
    )

  /** Read a vault's declaration.
    *
    * PARSE, DON'T VALIDATE: the untyped document becomes a [[VaultLayout]] here, at the
    * boundary, once — and everything downstream takes the typed value and cannot be handed a
    * misspelt part name or a deck root that is not a deck path.
    *
    * An ABSENT file is not an error; it means [[default]]. A file that is present and wrong is
    * an error, and a loud one: a vault that tried to say something must never be read as having
    * said nothing.
    */
  def parse(document: String): Either[LayoutError, VaultLayout] =
    readMapping(document).flatMap { entries =>
      for
        _           <- rejectUnknownKeys(entries)
        deckRoot    <- deckRootOf(entries)
        deckFrom    <- partsOf(entries, DeckFromKey, default.deckFrom)
        contextFrom <- partsOf(entries, ContextFromKey, default.contextFrom)
      yield VaultLayout(deckRoot, deckFrom, contextFrom)
    }

  /** The untyped document, as a key to raw-value map. Everything after this point is typed.
    *
    * AN ABSENT OR EMPTY DOCUMENT IS AN EMPTY MAPPING, NOT AN ERROR — a vault that declares
    * nothing gets [[default]]. A document that is present and is not a mapping IS an error: it
    * tried to say something, and the tool must not read it as having said nothing.
    */
  private def readMapping(document: String): Either[LayoutError, Map[String, Any]] =
    if document.trim.isEmpty then Right(Map.empty)
    else
      try
        Option(yaml.load[Any](document)) match
          case None => Right(Map.empty)
          case Some(m: java.util.Map[?, ?]) =>
            import scala.jdk.CollectionConverters.*
            Right(m.asScala.iterator.map((k, v) => k.toString -> (v: Any)).toMap)
          case Some(other) =>
            Left(LayoutError.NotAMapping(s"expected a mapping of settings, got ${shapeOf(other)}"))
      catch
        case e: org.yaml.snakeyaml.error.YAMLException =>
          Left(LayoutError.NotAMapping(e.getMessage))

  /** REFUSED, NOT IGNORED, and this is the most consequential line in the file. A skipped
    * `deck-form` leaves the vault on a default it never chose, and the only symptom is a
    * collection quietly rearranged on the next run.
    */
  private def rejectUnknownKeys(entries: Map[String, Any]): Either[LayoutError, Unit] =
    entries.keys.toVector.sorted.find(!knownKeys.contains(_)) match
      case Some(key) => Left(LayoutError.UnknownKey(key, knownKeys))
      case None      => Right(())

  private def deckRootOf(entries: Map[String, Any]): Either[LayoutError, DeckPath] =
    entries.get(DeckRootKey) match
      case None => Right(default.deckRoot)
      case Some(raw: String) =>
        // Split on Anki's own separator, exactly as the removed `--deck-root` flag did, so a
        // vault moving from the flag to this file lands on the identical deck.
        val segments = raw.split("::").toVector.map(_.trim).filter(_.nonEmpty)
        cats.data.NonEmptyVector
          .fromVector(segments)
          .toRight(LayoutError.UnusableDeckRoot(raw))
          .map(DeckPath.apply)
      case Some(_) =>
        Left(LayoutError.WrongValueShape(DeckRootKey, "a deck name, e.g. Obsidian or Study::Obsidian"))

  private def partsOf(
      entries: Map[String, Any],
      key: String,
      whenAbsent: Set[LocationPart],
  ): Either[LayoutError, Set[LocationPart]] =
    entries.get(key) match
      // ABSENT IS NOT EMPTY. Saying nothing leaves this setting alone; `[]` is a declaration
      // that no part is wanted — one flat deck, or a card with no breadcrumb.
      case None => Right(whenAbsent)
      case Some(list: java.util.List[?]) =>
        import scala.jdk.CollectionConverters.*
        list.asScala.toVector
          .foldLeft[Either[LayoutError, Set[LocationPart]]](Right(Set.empty)) { (acc, raw) =>
            acc.flatMap { parts =>
              val name = raw.toString.trim.toLowerCase
              partNames
                .get(name)
                .toRight(LayoutError.UnknownPart(key, raw.toString.trim, knownPartNames))
                .map(parts + _)
            }
          }
      case Some(_) =>
        Left(
          LayoutError.WrongValueShape(
            key,
            s"a list of parts, e.g. [${knownPartNames.mkString(", ")}]",
          )
        )

  private def knownPartNames: Vector[String] = LocationPart.nesting.map(LocationPart.label)

  private def shapeOf(value: Any): String = value match
    case _: java.util.List[?] => "a list"
    case _: String            => "a single value"
    case _                    => "something that is not a mapping"

/** Why a vault's layout declaration could not be read.
  *
  * Each case carries what the author needs to fix it — the offending text, and where relevant
  * what was expected instead. A layout file is short and hand-written, so every error here is a
  * typo somebody can correct in seconds IF they are told which word is wrong.
  */
enum LayoutError:
  /** The document is not YAML, or not a mapping at its top level. */
  case NotAMapping(detail: String)

  /** A key this tool does not know. Refused rather than ignored: a silently skipped `deck-form`
    * leaves the vault on defaults it did not choose and says nothing about why.
    */
  case UnknownKey(key: String, known: Vector[String])

  /** A value under `deck-from` or `context-from` that names no part of a location. */
  case UnknownPart(key: String, value: String, known: Vector[String])

  /** `deck-root` was present but empty, or held nothing a deck can be named by. */
  case UnusableDeckRoot(raw: String)

  /** A key whose value is the wrong shape — a list where a word was wanted, or the reverse. */
  case WrongValueShape(key: String, expected: String)
