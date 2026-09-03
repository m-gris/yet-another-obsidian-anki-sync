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
  * RULED BY MARC, 2026-08-22 (`docs/reference/REQUIREMENTS.md` item 11): folder path, file
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

/** Whether a parsed document has no headings at all.
  *
  * ASKED OF THE PARSE RATHER THAN THE TEXT, because `#` at the start of a line is a heading in
  * markdown and a tag anywhere else, and only the parser knows which — a line reading
  * `#flashcard/sequence` in the body is a tag, not a heading called "flashcard/sequence".
  *
  * A document that did not parse answers FALSE — it is not known to have no headings, and this
  * predicate must never turn "could not look" into "there are none". That is the same collapse
  * `MarkedHeadings` exists to prevent, arriving one function along.
  */
private[extract] def hasNoHeadings(root: laika.ast.RootElement): Boolean =
  // THE DECLARATIONS HEADING IS NOT STRUCTURE, and this is a ruling rather than an exception
  // grudgingly made. `# Properties-to-Flashcards` is metadata that happens to be written in the
  // body — frontmatter with room to explain itself. Counting it would mean that adding a
  // declarations block to a headingless note SILENTLY retires its whole-note card: a behaviour
  // change arriving from an unrelated edit, which is the worst shape of failure this project
  // recognises.
  def isDeclarations(h: laika.ast.Header): Boolean =
    def loosely(raw: String) =
      obsidiananki.model.TagCodec.canonical(raw).replace('-', ' ').replace('_', ' ')
    loosely(h.extractText) == loosely(EdgeSchema.Heading)

  // BOTH SHAPES ARE HANDLED, AND ONLY ONE OF THEM OCCURS IN PRACTICE. Laika's section builder
  // wraps every heading in a `Section`, so the bare-`Header` arm below is not reached by any
  // vault this tool has parsed — verified by making it throw, which killed no test.
  //
  // IT IS KEPT AND UNIT-TESTED DIRECTLY rather than deleted, because deleting it would send a
  // bare header to the catch-all and be answered "not a heading". A document full of headings
  // would then report having none, and every marked note in it would become a candidate for a
  // whole-note card. That is a silent, total misreading resting on an implementation detail of
  // somebody else's rewrite rules; the arm costs one line and removes the dependency.
  def anyHeading(block: laika.ast.Block): Boolean = block match
    case h: laika.ast.Header => !isDeclarations(h)
    case sec: laika.ast.Section =>
      !isDeclarations(sec.header) || sec.content.exists(anyHeading)
    case c: laika.ast.BlockContainer => c.content.exists(anyHeading)
    case _                    => false
  !root.content.exists(anyHeading)

/** Whether any heading in a file carries a `#flashcard` marker — INCLUDING the case where the
  * question could not be asked.
  *
  * IT IS THREE STATES BECAUSE THERE ARE THREE, and a `Boolean` here told a lie. The search used
  * to be `parsed.fold(_ => false, doc => hasMarkedHeading(doc.content))`, which files "the
  * markdown would not parse" under the same answer as "the markdown parsed and holds no
  * marker". The report then said `no HEADING carries a marker` about a document nothing had
  * ever read — a claim the tool was in no position to make.
  *
  * REACHABLE BY ORDINARY PROSE, not by anything exotic. Parsing is strict on purpose, so
  * `An array index like [0] in prose.` fails to parse; see `parser/ObsidianSyntax.test.scala`,
  * "bare bracketed prose FAILS loudly under strict parsing". Any note containing an array index
  * takes this path.
  *
  * `CouldNotLook` IS NAMED FOR THE EPISTEMIC STATE, not for the cause. What matters downstream
  * is not that a parser failed; it is that no claim about markers may be made about this file
  * in either direction — neither "it has one" nor "it has none".
  */
enum MarkedHeadings:

  /** Some heading carries a marker. */
  case Present

  /** The note HAS headings and none of them carries a marker.
    *
    * SPLIT FROM `Headingless` ON 2026-08-28. These were one case, `Absent`, told apart by a
    * boolean in a pattern guard — while the comment above that guard described the difference
    * in prose and called only the first a mistake. A distinction that load-bearing belongs in
    * the type, where a new branch cannot fail to notice it. See `docs/history/IN-FLIGHT.md` item 36.
    */
  case NoneMarked(document: laika.ast.RootElement)

  /** The note has NO headings at all, so frontmatter is the only place a marker could live. */
  case Headingless(document: laika.ast.RootElement)

  /** The markdown could not be parsed, so NO claim about its markers may be made in either
    * direction.
    */
  case CouldNotLook

/** Turning a whole vault into a scan. */
object VaultWalker:

  /** The `id` property, as a note identity — or why it could not be one.
    *
    * IT LIVES HERE RATHER THAN ON [[PropertyValue]] because only this caller knows that an id
    * must be SINGULAR. `tags` and a typed edge are equally happy with one value or several, and
    * a shared `asSingleString` helper would have to invent an answer for them too.
    *
    * THE TWO NON-SCALAR ARMS ARE WHY THE TYPE CHANGED. Before it, a list-valued or
    * nested-mapping `id` was dropped by the parser and reached this point as ABSENT — so a note
    * that plainly has an `id:` line was told it has no id and asked to add one. The shape is now
    * visible, so each can say what is actually wrong.
    *
    * The failure is a STRING rather than a `KeyError` because these two arms are not key errors:
    * nothing was malformed, the property is simply the wrong shape to be an identity. It reaches
    * the reader through the same `unusable id:` sentence either way.
    */
  private def noteIdFrom(value: PropertyValue): Either[String, NoteId] = value match
    case PropertyValue.One(text) => NoteId.fromFrontmatter(text).left.map(_.toString)

    case PropertyValue.Many(items) =>
      Left(
        s"the 'id' property is a list of ${items.size} value(s), and a note's id must be a " +
          "single value — every card in the file is keyed on it"
      )

    case PropertyValue.Unreadable(shape) =>
      Left(s"the 'id' property is $shape, and a note's id must be a single scalar value")


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

          // ── DOES THIS TOOL READ THIS NOTE'S HEADINGS THE WAY ITS AUTHOR WROTE THEM? ────────
          //
          // ASKED HERE BECAUSE HERE IS ABOVE EVERYTHING THAT CLASSIFIES THE NOTE. A `#` line
          // swallowed by a list is not a parse ERROR — the parse succeeds, and what it succeeds
          // at is a different document from the one the author wrote. So the questions asked
          // below this line are answered about that different document: `hasMarkedHeading` cannot
          // see a marker on a heading that is no longer a heading, and `hasNoHeadings` answers
          // "none" for a note whose only heading was swallowed, because it descends
          // `BlockContainer`s while Laika's `BulletList` is a `ListContainer`. Neither could be
          // trusted to GATE this check, which is why the check does not go through them.
          //
          // A SECOND CALL TO THE SAME PURE FUNCTION, the first being inside
          // `Extractor.fromDocument`, and that is not the duplication this codebase objects to.
          // `Decks.fromRelativePath`'s docstring warns against two BODIES of a rule, which are
          // free to drift; this is one function called twice on one document. What would remove
          // even that is passing the answer down — rejected because the extractor is where a key
          // is derived, so that is where the rule has to hold whoever calls it.
          //
          // AN UNPARSEABLE DOCUMENT ANSWERS "NONE", which is not a defensive default: there is no
          // parse to disagree with the source, so no claim about this file's headings may be made
          // in either direction. Such a file is already reported below, as `MarkerUnknowable` or
          // as `KeyUnderivableInFile`.
          val unreadHeadings: Vector[UnreadHeading] =
            parsed.fold(
              _ => Vector.empty,
              doc => UnreadHeadings.in(doc.content, body, split.bodyFirstLine),
            )

          // THREE STATES, NOT TWO. This was `parsed.fold(_ => false, …)` until 2026-08-24,
          // which answered "no marker found" for a document that was never read — and the
          // report then said so to the author. See [[MarkedHeadings]].
          val marked = parsed.fold(
            _ => MarkedHeadings.CouldNotLook,
            doc =>
              if Extractor.hasMarkedHeading(doc.content) then MarkedHeadings.Present
              // THE DOCUMENT TRAVELS WITH THE ANSWER, which is what removes the
              // `parsed.toOption.get` further down. Scala's sums are sums of PRODUCTS: a case
              // may carry exactly the evidence its branch needs, so a branch reached only when
              // the parse succeeded can be handed the parsed value instead of re-extracting it
              // with a partial function.
              else if hasNoHeadings(doc.content) then MarkedHeadings.Headingless(doc.content)
              else MarkedHeadings.NoneMarked(doc.content),
          )

          // INTENT DECLARED IN THE FRONTMATTER AND NOWHERE ELSE. Read off the RAW block rather
          // than the parsed keys, because `Frontmatter.parse` keeps only scalar values and a
          // `tags:` list is dropped before it gets here — which is the very shape this looks
          // for. A substring match is enough: the block is small, and anything naming
          // `flashcard` in it is a statement of what the note was meant to be.
          val frontmatterNamesFlashcard =
            split.frontmatter.exists(_.toLowerCase.contains("flashcard"))

          // WHICH SHAPE THE FRONTMATTER ASKS FOR, as opposed to merely THAT it asks. Reading this
          // needs `tags` to survive parsing, which it did not until the property parser was
          // widened: Obsidian writes it as a YAML list, lists were dropped, and the only thing
          // left was the substring check above — enough to complain about, never enough to act on.
          // WHICH OF THIS NOTE'S PROPERTIES MAKE CARDS — declared by THIS NOTE, for itself.
          //
          // LEXICAL SCOPE, AND THAT IS THE WHOLE POINT. _It was one vault-wide declaration until
          // 2026-08-26._ A relation earns its place in frontmatter for querying and for the graph,
          // which is most of its value; whether to be DRILLED on it is a separate decision and
          // belongs to the note carrying it. A vault-wide vocabulary turns every occurrence
          // everywhere into a card whether that was meant or not — the difference between a
          // lexically scoped expansion and a global rewrite.
          //
          // A NOTE THAT DECLARES NOTHING MAKES NO RELATION CARDS, silently, and that is the
          // ordinary case: most notes carry properties and want none of them drilled.
          val (declared, declarationFailures) =
            EdgeSchema.findIn(split.body) match
              case None => (EdgeSchema.empty, Vector.empty[BuildFailure])
              case Some(text) =>
                EdgeSchema
                  .parse(text)
                  .fold(
                    errs =>
                      (
                        EdgeSchema.empty,
                        Vector(
                          BuildFailure.EdgeVocabularyUnusable(
                            file.relativePath,
                            errs.toVector.map(_.describe).mkString("; "),
                          )
                        ),
                      ),
                    ok => (ok, Vector.empty[BuildFailure]),
                  )
          failures ++= declarationFailures

          // ── EVERY FRONTMATTER TAG, READ RATHER THAN FILTERED ────────────────────────────
          //
          // THIS USED TO BE A FILTER FOLLOWED BY A SWALLOWED ERROR — `docs/history/IN-FLIGHT.md` item 37 —
          // and two different mistakes fell through it, both without a word:
          //
          //   .filter(_.toLowerCase.startsWith("flashcard"))
          //   .flatMap(t => Marker.parse(s"#$t").toOption.flatten)
          //
          // A tag spelled `flashard/…`, one character short, failed the filter and vanished —
          // and the separate did-you-mean check below searches the frontmatter for the SUBSTRING
          // `flashcard`, so it missed the same tag for the same reason. A tag spelled
          // `flashcard/sequence/hedars` passed the filter, failed to parse, and had its error
          // dropped by `.toOption`: reported, but as "your marker is not on a heading", which
          // sends the author off to move something whose only problem is its spelling.
          //
          // `Marker.readTag` answers with a CASE rather than an Option, so neither situation can
          // be lost by being left out of a predicate, and both matches below are exhaustive.
          val tagReadings: Vector[TagReading] =
            keys
              .get("tags")
              .toVector
              .flatMap {
                case PropertyValue.One(t)        => Vector(t)
                case PropertyValue.Many(ts)      => ts
                case PropertyValue.Unreadable(_) => Vector.empty
              }
              .map(Marker.readTag)

          tagReadings.foreach:
            case TagReading.AMarker(_) => ()
            case TagReading.NotOurs(_) => ()

            case TagReading.Misspelled(raw, probably) =>
              failures += BuildFailure.MarkerMisspelled(
                file.relativePath,
                s"its frontmatter tag '$raw' is not a marker this tool reads, but everything " +
                  s"after its first segment matches '$probably' — so it makes no cards. Correct " +
                  s"the spelling, or remove the tag if it was never meant to be a marker",
              )

            case TagReading.Unrecognised(raw) =>
              failures += BuildFailure.MarkerMisspelled(
                file.relativePath,
                s"its frontmatter tag '$raw' names 'flashcard' and then a token this tool does " +
                  s"not recognise, so it makes no cards. Run --help for every marker it reads",
              )

          val frontmatterMarker: Option[Marker] =
            // MATCHED EXHAUSTIVELY RATHER THAN COLLECTED, so a reading added later has to say
            // whether it yields a marker instead of being skipped by a partial function — which
            // is the shape of the bug this block replaces.
            tagReadings.flatMap {
              case TagReading.AMarker(m)       => Some(m)
              case TagReading.Unrecognised(_)  => None
              case TagReading.Misspelled(_, _) => None
              case TagReading.NotOurs(_)       => None
            }.headOption

          /** THE AUTHOR'S OWN TAGS, ready to travel into Anki.
            *
            * COMPUTED HERE BECAUSE HERE IS THE ONLY LAYER THAT SEES FRONTMATTER. The extractor
            * receives a parsed body and knows nothing of a note's properties, so deriving these
            * anywhere further down would mean parsing frontmatter twice.
            *
            * MATCHED EXHAUSTIVELY, not collected, for the reason the block above records: a
            * reading added later must say whether it is one of the author's tags rather than
            * being skipped by a partial function.
            */
          val vaultTags: Vector[VaultTag] =
            tagReadings.flatMap {
              case TagReading.NotOurs(raw)     => Vector(VaultTag.read(raw))
              case TagReading.AMarker(_)       => Vector.empty
              case TagReading.Unrecognised(_)  => Vector.empty
              case TagReading.Misspelled(_, _) => Vector.empty
            }

          val identity = keys.get("id").map(noteIdFrom)

          // WHAT TO SAY TO A FILE THAT DECLARED INTENT AND MADE NO CARDS, which depends on
          // whether the tool actually LOOKED at its headings. Asked as a match rather than as
          // `&& !marked`, because the negation silently lumped "did not look" in with "looked
          // and found nothing" and then reported the second.
          // THE WHOLE-NOTE CARD, shared by the two shapes that legitimately reach it.
          //
          // IT IS HANDED THE PARSED DOCUMENT RATHER THAN REACHING FOR ONE, and that is the
          // whole reason `MarkedHeadings` now carries it. The previous version called
          // `parsed.toOption.get` at this point: the branch KNEW the parse had succeeded,
          // having arrived through a guard that inspected the parsed value, but the type did
          // not carry that knowledge, so it was re-extracted with a partial function.
          def wholeNoteCard(marker: Marker, document: laika.ast.RootElement): Unit =
            identity match
              // NOTHING BUILT FROM THIS NOTE'S BODY WHILE ITS OUTLINE IS IN DISPUTE. A whole-note
              // card is not keyed by any heading, so it looks exempt — and it is not.
              // `#flashcard/sequence/headers` on a whole note builds its card OUT OF the note's
              // headings, so a swallowed one is an item silently missing from a card that
              // otherwise looks complete; and the note reaches this branch at all only because
              // `hasNoHeadings` could not see that heading either, so its body carries the
              // heading's own marker text as content.
              //
              // SILENT HERE BECAUSE `Extractor.fromDocument` REPORTS IT, and that call is made
              // for this same note further down whatever route brings it here. Reporting in both
              // places would tell the author the same news twice.
              //
              // ONLY WHERE THE TWO READINGS PLACE A HEADING DIFFERENTLY. A heading indented inside
              // a list item is placed identically by both, so it leaves this card exactly as
              // Obsidian renders it and withholding it would take a card away for no gain — see
              // [[UnreadHeading.commonMarkPlacesItElsewhere]], which reports that fact and leaves
              // this decision here.
              case Some(Right(_)) if unreadHeadings.exists(_.commonMarkPlacesItElsewhere) => ()

              case Some(Right(noteId)) =>
                val note = Extractor.fromWholeNote(
                  noteId,
                  fileName,
                  file.relativePath,
                  marker,
                  document,
                  vaultTags,
                  split.bodyFirstLine,
                )
                specs ++= note.specs
                failures ++= note.failures
                note.specs.foreach { s =>
                  Decks
                    .compose(deckRoot, shape, Decks.sourceFor(file.relativePath, Vector.empty), s.recall)
                    .foreach(composed => decks += s.key -> composed.path)
                }

              // No usable id, so the card cannot be keyed. Reported below by the id branch,
              // which says exactly that; a second message here would be the same news twice.
              case _ => ()

          /** Whether a tag on this file has already been reported as a near miss. */
          val nearMissReported: Boolean = tagReadings.exists:
            case TagReading.Misspelled(_, _) => true
            case TagReading.Unrecognised(_)  => true
            case TagReading.AMarker(_)       => false
            case TagReading.NotOurs(_)       => false

          def markerNotOnHeading(): Unit =
            // SILENT WHEN A NEAR MISS WAS ALREADY NAMED, and that suppression is the point rather
            // than tidiness. This message tells the author to move their marker onto a heading.
            // For a tag reading `flashcard/sequence/hedars` that is the WRONG ADVICE — the marker
            // is already somewhere this tool reads and only its token is wrong — and following it
            // would not help. Measured against a throwaway vault on 2026-08-28: before this
            // guard, such a file drew both messages, the accurate one and then the misleading
            // one, in that order.
            //
            // SILENT FOR AN UNREAD HEADING TOO, FOR EXACTLY THE SAME REASON, and this arm is
            // reachable rather than theoretical: `hasMarkedHeading` walks `Section`s, so a marker
            // on a heading this tool did not read as a heading is invisible to it and the note
            // arrives here classified `NoneMarked`. The message below would then tell an author
            // whose marker is already on a heading to move it out of the frontmatter it is not
            // in. The accurate message is raised by the extractor, which names the heading.
            //
            // BOTH SEVERITIES SUPPRESS IT, unlike the card-withholding above, and the asymmetry
            // is deliberate: what is wrong here is the CLASSIFICATION, and a marker sitting on an
            // indented heading is just as invisible to `hasMarkedHeading` as one on a swallowed
            // heading. What differs between the two is what it costs, not what the tool can see.
            if !nearMissReported && unreadHeadings.isEmpty then
              failures += BuildFailure.MarkerNotOnHeading(
                file.relativePath,
                  "its frontmatter names 'flashcard' but no HEADING carries a marker, so it " +
                    "makes no cards — a marker goes on the heading itself, as in " +
                    "'## Some descriptor #flashcard/cdd/2way'. Typing one into the Obsidian " +
                    "editor files it under the 'tags' property instead, where this tool " +
                    "cannot see it",
              )

          if frontmatterNamesFlashcard then
            marked match
              // It has a marker where a marker belongs. Nothing to say.
              case MarkedHeadings.Present => ()

              // ── HEADINGS, NONE OF THEM MARKED ────────────────────────────────────────────
              //
              // WHAT THIS MEANS DEPENDS ON WHAT THE MARKER READS, and until 2026-08-28 it did
              // not: the condition here asked whether there was SOME frontmatter marker and
              // never which one, so every marker was given the prose answer.
              //
              // A MARKER THAT READS PROSE makes this the Obsidian accident. Typing
              // `#flashcard/3way` into the desktop editor lifts it out of the text and files it
              // under the frontmatter `tags` property, leaving a note that LOOKS marked and
              // produces nothing. The headings are where the marker should have gone, and
              // saying so is the whole message.
              //
              // A MARKER THAT READS STRUCTURE makes this its INTENDED use, and refusing it was
              // a defect — `docs/history/IN-FLIGHT.md` item 35, found by Marc on the day the marker shipped.
              // A whole-note `#flashcard/sequence/headers` card is made OF the headings, since
              // they are its items, so it cannot work on a note without them; the old advice to
              // move the marker onto a heading was advice the author had to ignore.
              case MarkedHeadings.NoneMarked(document) =>
                frontmatterMarker match
                  case Some(marker) =>
                    marker.wholeNoteReads match
                      case NoteMaterial.Structure => wholeNoteCard(marker, document)
                      case NoteMaterial.Prose     => markerNotOnHeading()

                  // The frontmatter names `flashcard` and no tag in it parses as a marker — a
                  // misspelling, most often. Nothing here makes a card either way.
                  case None => markerNotOnHeading()

              // ── NO HEADINGS AT ALL ───────────────────────────────────────────────────────
              //
              // Frontmatter is the only place a marker could live, so THE NOTE ITSELF IS THE
              // CARD and nothing has gone wrong. The exact mirror of the case above: a prose
              // marker is satisfied here and a structure marker cannot be, because it has
              // nothing to reveal.
              case MarkedHeadings.Headingless(document) =>
                frontmatterMarker match
                  case Some(marker) =>
                    marker.wholeNoteReads match
                      case NoteMaterial.Prose => wholeNoteCard(marker, document)
                      case NoteMaterial.Structure =>
                        // "AND THE NOTE HAS NONE" IS TRUE OF THE TREE AND FALSE OF THE FILE when
                        // a heading went unread, since the heading this card would have revealed
                        // is the one nobody read. Suppressed for the same reason as its sibling
                        // above: the extractor's report names that heading, and this one would
                        // send an author who wrote headings off to write some.
                        if unreadHeadings.isEmpty then
                          failures += BuildFailure.MarkerNotOnHeading(
                            file.relativePath,
                            "its frontmatter asks for a card made from this note's HEADINGS, " +
                              "and the note has none — so there is nothing to reveal. Write the " +
                              "headings you meant to learn, or remove the marker",
                          )
                  case None => markerNotOnHeading()

              // NOTHING READ THE DOCUMENT, so no claim about its headings may be made in
              // either direction — and this file may well carry a perfectly good marker.
              //
              // REPORTED ONLY WHERE NOTHING ELSE REPORTS IT, which is the no-id case alone: an
              // unusable id becomes `FileUnreadable` just below, and a usable one makes the
              // same unparseable markdown a `KeyUnderivableInFile`. Both name the parser's own
              // error, and both send the reader to the single action that fixes this — repair
              // the markdown — so a second message beside either would be noise.
              case MarkedHeadings.CouldNotLook =>
                identity match
                  case Some(_) => ()
                  case None =>
                    failures += BuildFailure.MarkerUnknowable(
                      file.relativePath,
                      "its frontmatter names 'flashcard', but its markdown could not be " +
                        "parsed — so this tool cannot say whether any heading carries a " +
                        "marker, and it makes no cards. Parsing is strict: an array index " +
                        "written as '[0]' in prose is enough to stop it. The file also has " +
                        "no 'id' in its frontmatter, which its cards would need in order to " +
                        "be keyed",
                    )

          identity match
            // NO ID. Whether that is a mistake depends entirely on whether the file asked for
            // cards, and only the parsed document can say. A note with marked headings is an
            // author who will get nothing and must be told; a note without them is ordinary
            // prose — the vast majority of any real vault — and saying anything at all about it
            // is the noise that stops a report being read.
            case None =>
              marked match
                case MarkedHeadings.Present =>
                  failures += BuildFailure.MarkedWithoutNoteId(
                    file.relativePath,
                    "has #flashcard heading(s) but no 'id' in its frontmatter, so its cards " +
                      "cannot be keyed — add an id to the frontmatter",
                  )

                // Asked for nothing we can see, and owns nothing, having no id. Stays quiet.
                // BOTH UNMARKED SHAPES ALIKE HERE, and unlike above: with no id there is no
                // card to build whichever it is, so the distinction the type now draws buys
                // nothing at this branch and pretending otherwise would be noise.
                case MarkedHeadings.NoneMarked(_) | MarkedHeadings.Headingless(_) => ()

                // Already reported as `MarkerUnknowable` above IF the frontmatter declared
                // intent. If it did not, this is ordinary prose that happens not to parse and
                // owns nothing — the vast majority of any real vault — and naming it is the
                // noise that stops a report being read.
                case MarkedHeadings.CouldNotLook => ()

            case Some(Left(e)) => unreadable(s"unusable id: $e")
            case Some(Right(noteId)) =>
              // EDGE CARDS COME FIRST AND DO NOT NEED THE PARSED DOCUMENT, which is a property
              // worth having rather than an accident of ordering: a note whose markdown will not
              // parse still declares its relations, because they live in frontmatter, and the
              // frontmatter parsed — that is how we have an id at all. So a stray `[0]` in a
              // sentence costs the note its heading cards and none of its edges.
              val (edgeSpecs, edgeFailures) = Edges.specsFor(
                noteId = noteId,
                noteName = fileName,
                relativePath = file.relativePath,
                location = file.relativePath.split('/').dropRight(1).toVector :+ fileName,
                properties = keys,
                rawFrontmatter = split.frontmatter.getOrElse(""),
                schema = declared,
                vaultTags = vaultTags,
              )
              specs ++= edgeSpecs
              failures ++= edgeFailures
              edgeSpecs.foreach { s =>
                Decks
                  .compose(deckRoot, shape, Decks.sourceFor(file.relativePath, Vector.empty), s.recall)
                  .foreach(composed => decks += s.key -> composed.path)
              }

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
                      vaultTags,
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

    // A CROSS-NOTE CHECK, AND THEREFORE ONLY POSSIBLE HERE. Whether a reversible edge asks a
    // question with several right answers is a fact about the whole vault, not about any note.
    val built = specs.result()
    VaultIndex(
      VaultScan.from(built, failures.result() ++ Edges.reverseCollisions(built)),
      decks.result(),
    )
