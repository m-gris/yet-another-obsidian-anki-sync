package obsidiananki.model

import cats.data.NonEmptyVector
import cats.syntax.all.*

/* Card identity: the key derived from the markdown, and its encoding into an Anki tag.
 *
 * NOTHING GENERATED IS EVER WRITTEN BACK INTO THE MARKDOWN, which is why identity is DERIVED
 * from text already present — the frontmatter `id`, plus whichever node of the note the card
 * hangs off — rather than minted and written down. The binding to an Anki note is held on the
 * Anki side: in the `Identity` field, and as a `src::` tag on notes written before that field
 * existed.
 *
 * WHY THE SOURCE AND NOT ANKI. Not because Anki is disposable — a collection is a JOIN of a
 * derived layer, which this tool can recompute for nothing, and an accumulated one it can never
 * recompute at all: interval, ease, due date, the review log. Bookkeeping is acceptable on the
 * derived half. It is unacceptable in the vault because the vault is what a person writes.
 */

/** A note's `id:` frontmatter value. */
opaque type NoteId = String

object NoteId:
  /** `id:` is load-bearing, so a blank one is refused rather than skipped. */
  def fromFrontmatter(raw: String): Either[KeyError, NoteId] =
    val canonical = TagCodec.canonical(raw)
    if canonical.isEmpty then Left(KeyError.BlankNoteId) else Right(canonical)

  extension (id: NoteId) def value: String = id

/** One segment of a heading path: the marked heading, or one of its ancestors.
  *
  * A segment is the heading's EXTRACTED TEXT with the `#flashcard/…` marker stripped — not the
  * raw markdown source, and not the rendered HTML — so that bolding a word in a heading cannot
  * orphan its card.
  */
opaque type HeadingSegment = String

object HeadingSegment:
  /** Matches a `#flashcard` marker and any number of `/`-separated qualifiers, so that a short
    * `cdd/2way` and a long `sequence/headers/recursive/bfs` are both removed in full.
    */
  private val Marker = """#flashcard(?:/[\w-]+)*""".r

  /** Strip the marker, canonicalise, reject what is left if it is empty.
    *
    * A heading that consists ONLY of a marker, or only of markup that extracts to nothing,
    * has no segment to contribute and must fail rather than silently key as "".
    *
    * STRIPPING THE MARKER IS NOT COSMETIC: the key must survive retagging, which changes only
    * how many cards a heading generates.
    */
  def fromExtractedText(raw: String): Either[KeyError, HeadingSegment] =
    val canonical = TagCodec.canonical(Marker.replaceAllIn(raw, ""))
    if canonical.isEmpty then Left(KeyError.EmptyHeadingSegment(raw)) else Right(canonical)

  /** NOT marker-stripped a second time, which is not the no-op it looks like: removing a marker
    * from the middle of a heading can splice its leftovers into a new one, so a second strip can
    * mangle a name that was stored correctly.
    */
  private[model] def fromDecoded(decoded: String): Either[KeyError, HeadingSegment] =
    val canonical = TagCodec.canonical(decoded)
    if canonical.isEmpty then Left(KeyError.EmptyHeadingSegment(decoded)) else Right(canonical)

  extension (s: HeadingSegment) def value: String = s

/** The chain of ancestor headings down to the marked one, outermost first.
  *
  * Non-empty because a card always has at least the marked heading itself — which is what tells
  * two identically-named facets under different ancestors apart.
  */
final case class HeadingPath(segments: NonEmptyVector[HeadingSegment]):
  def render: String = segments.toVector.map(_.value).mkString(" / ")

/** A frontmatter property's NAME, canonicalised exactly as a heading segment is.
  *
  * SAME CANONICALISATION, DIFFERENT TYPE. `Special-Case-Of` and `special-case-of` must key
  * alike for the same reason two spellings of a heading do — an author who tidies their
  * frontmatter has not made a different card. The type differs from [[HeadingSegment]] because
  * the two are not interchangeable: one names a heading and the other names a property, and a
  * card anchored at each is a different card even when the names coincide.
  */
opaque type PropertyName = String

object PropertyName:

  /** Canonicalise, and refuse what canonicalises to nothing. Used for a name read out of
    * frontmatter and for one recovered from a tag: unlike a heading segment, a property name
    * carries no marker, so there is nothing a second pass could damage.
    */
  def fromFrontmatter(raw: String): Either[KeyError, PropertyName] =
    val canonical = TagCodec.canonical(raw)
    if canonical.isEmpty then Left(KeyError.EmptyPropertyName(raw)) else Right(canonical)

  extension (p: PropertyName) def value: String = p

/** WHICH NODE OF A NOTE a card is anchored to.
  *
  * ==Why this is a sum and not a list of segments==
  *
  * A note is a tree of nodes and a card hangs off one of them. Until now the only markable node
  * was a heading, so the anchor could be a bare chain of heading names — but a heading is one
  * kind of node among several, exactly as a directory is one kind of filesystem entry. A
  * frontmatter property is a node. The note itself is a node. Neither is reachable through a
  * chain of headings, and neither is a special case of one.
  *
  * ==Why a mixed path is not representable==
  *
  * A property belongs to the NOTE, never to a heading inside it — Obsidian has no per-heading
  * frontmatter — so `headings / property` is not a shape the domain has. Modelling the anchor as
  * a list of kinded segments would admit it, and every consumer would then need a rule for
  * something that cannot occur. Every case below is a shape the domain has.
  */
enum CardPath:

  /** The ordinary case: a chain of ancestor headings ending at the marked one. */
  case Headings(headings: HeadingPath)

  /** A frontmatter property of the note. Terminal by nature — a property has no children, so
    * there is no chain to record.
    */
  case Property(name: PropertyName)

  /** The note itself, carrying no anchor below it — a note with no headings whose whole body is
    * the card.
    *
    * _Admitted by the type on 2026-08-25 and PRODUCED FROM 2026-08-26, by `Extractor.fromWholeNote`
    * when a note carries a marker in its frontmatter and has no heading it could have fallen off._
    * The shape was settled a day before the behaviour arrived, on purpose: identity is the most
    * expensive thing in this system to change once review history exists and the cheapest while a
    * collection is nearly empty.
    */
  case Note

  /** ONE BLOCK OF A NOTE, named by the `^blockid` its author wrote at the end of it.
    *
    * THE FOURTH KIND, AND THE ONE THE CODEC RESERVED ROOM FOR. A cloze card scoped to a block
    * needs to be told apart from the block beside it, and nothing else in this type can do that:
    * two paragraphs under one heading share a heading path, and a note has only one of itself.
    *
    * IT CARRIES NO HEADING CHAIN, deliberately. An Obsidian block id is unique within its note,
    * so the note and the anchor are the whole identity — and including the heading would make
    * moving a paragraph from under one heading to another re-key its card, which is exactly the
    * fragility the anchor exists to remove.
    */
  case Block(anchor: BlockAnchor)

  /** For a human reading a report. The kinds are told apart in words, because a reader who
    * cannot see which node a card came from cannot act on the line.
    */
  def render: String = this match
    case Headings(headings) => headings.render
    case Property(name)     => s"property '${name.value}'"
    case Note               => "the note itself"
    case Block(anchor)      => s"block '^${anchor.value}'"

/** The identity of a card's source location. */
final case class CardKey(noteId: NoteId, path: CardPath)

/** A tag this tool owns and may rewrite.
  *
  * The tool owns exactly the prefixes listed in [[ownedPrefixes]] and no others. Every other tag
  * on a note belongs to the person using Anki — a leech marker, a custom study scope — and must
  * be preserved untouched. Being allowed to write to a collection does not hand us its namespace.
  *
  * This is a type rather than a bare String so that "write the tags" cannot quietly become
  * "write all the tags".
  */
opaque type OwnedTag = String

object OwnedTag:
  val SrcPrefix: String      = "src"
  val ShaPrefix: String      = "sha"
  val OrphanedPrefix: String = "orphaned"

  val ownedPrefixes: Set[String] = Set(SrcPrefix, ShaPrefix, OrphanedPrefix, VaultTag.Prefix)

  /** True when a tag read back from Anki is one of ours; everything else is untouchable.
    *
    * Case-insensitive: Anki cannot tell `SRC::x` from `src::x`.
    */
  def isOwned(tag: String): Boolean =
    ownedPrefixes.contains(
      tag.split("::", -1).headOption.getOrElse("").toLowerCase(java.util.Locale.ROOT)
    )

  /** The content hash tag, used to decide "nothing to do" BEFORE any call is made.
    *
    * `updateNoteFields` has no early-out, so "nothing to do" must be decided before the call.
    * Lowercase because Anki case-folds tags.
    */
  def sha(hex: String): OwnedTag = s"$ShaPrefix::${hex.toLowerCase(java.util.Locale.ROOT)}"

  /** Marks a note whose markdown source has disappeared. */
  def orphaned(key: CardKey): OwnedTag =
    s"$OrphanedPrefix::${TagCodec.encode(key).value.stripPrefix(s"$SrcPrefix::")}"

  /** Escape hatch that bypasses every guarantee this type exists to provide.
    *
    * Legitimate uses are narrow: reconstructing a tag already read back out of Anki, and
    * constructing deliberately malformed tags in tests. Prefer [[TagCodec.encode]], [[sha]]
    * or [[orphaned]] everywhere else — those cannot produce a tag Anki would tear apart.
    */
  def unsafeFromString(s: String): OwnedTag = s

  /** For a tag this object has just built correctly. Separate from [[unsafeFromString]] so that
    * grepping the escape hatch finds only the places that really bypassed the codec.
    */
  private[model] def unsafe(s: String): OwnedTag = s

  extension (t: OwnedTag) def value: String = t

enum KeyError:
  case BlankNoteId
  case EmptyHeadingSegment(raw: String)

  /** A frontmatter property whose name canonicalises to nothing. Cannot arise from YAML, which
    * has no empty keys, but can from a tag that was hand-edited into that shape.
    */
  case EmptyPropertyName(raw: String)

  /** An empty `^blockid`. Cannot arise from the parser, whose production requires at least one
    * character, but can from a tag hand-edited into that shape.
    */
  case EmptyBlockAnchor(raw: String)

  /** A `^blockid` holding something outside Obsidian's own set of letters, digits and hyphens. */
  case UnusableBlockAnchor(raw: String)

  case MalformedTag(tag: String, reason: String)

/** Encoding of a [[CardKey]] into the Anki tag that binds it to a note, and back.
  *
  * Anki tags are WHITESPACE-DELIMITED and most headings contain a space, so the encoding below
  * is what makes the whole identity mechanism work.
  *
  * WHY EACH CHARACTER IS EXCLUDED from the safe set, which is the part [[isSafe]] cannot say:
  * whitespace splits a tag in two; `_` and `*` are WILDCARDS in Anki's tag search, so leaving
  * either raw turns every exact lookup fuzzy; `/` occurs inside real headings, so it cannot also
  * serve as the unencoded segment separator; `:` is Anki's own hierarchy separator.
  *
  * TWO ALTERNATIVES REJECTED, and no test can rule out either, because neither exists to run.
  * Base64url: a person reads the orphan list before anything is pruned, so a tag has to stay
  * legible in Anki's browser. "Pick a rarer separator": no character is impossible in a heading,
  * and the fixture vault's `## Cost / benefit` holds the obvious candidate. What IS pinned is the
  * consequence — `CardKeyTest` asserts the encoded form literally, so any scheme that stops a
  * heading's words showing through fails at once.
  */
object TagCodec:

  /** Unreserved characters, kept literal so tags stay human-readable in Anki's browser.
    * Deliberately narrow: alphanumerics plus `-` and `.`, which keep ids like
    * `1786713776-ZMPB` and `2026-08-18` legible without introducing a search metacharacter.
    */
  def isSafe(c: Char): Boolean =
    (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9') ||
      c == '-' || c == '.'

  /** The structural separators. Neither can survive [[encodeComponent]], which is what makes
    * decoding unambiguous.
    */
  private val FieldSep   = "::"
  private val SegmentSep = "/"

  /** Applied at CONSTRUCTION of [[NoteId]] and [[HeadingSegment]], so encoding downstream is
    * pure transport.
    *
    * THE EQUALITIES THIS CREATES ARE DELIBERATE, and each is pinned by a test. Case folds because
    * Anki folds it — two spellings of one heading are one card in the collection whether we like
    * it or not, so stating the equality here beats a false orphan and a false create on every
    * run. Whitespace collapses because a markdown formatter normalises a stray double space as a
    * matter of routine, markdownlint and prettier both by default, and a key that is not stable
    * under formatting means A FORMATTING PASS SILENTLY ORPHANS CARDS.
    *
    * `Locale.ROOT` because a Turkish locale folds `I` to a dotless `ı`.
    */
  def canonical(raw: String): String =
    java.text.Normalizer
      .normalize(raw, java.text.Normalizer.Form.NFC)
      .trim
      .replaceAll("\\s+", " ")
      .toLowerCase(java.util.Locale.ROOT)

  /** Percent-encode the UTF-8 bytes of anything outside the safe set, lowercase hex. */
  def encodeComponent(raw: String): String =
    val out = new StringBuilder
    raw.getBytes(java.nio.charset.StandardCharsets.UTF_8).foreach { b =>
      val c = (b & 0xff).toChar
      if b >= 0 && isSafe(c) then out.append(c)
      else out.append("%%%02x".format(b & 0xff))
    }
    out.toString

  /** Inverse of [[encodeComponent]] for a single component. */
  def decodeComponent(encoded: String): Either[KeyError, String] =
    val bytes = new java.io.ByteArrayOutputStream
    var i     = 0
    var error = Option.empty[KeyError]
    while i < encoded.length && error.isEmpty do
      encoded.charAt(i) match
        case '%' if i + 2 < encoded.length =>
          try
            bytes.write(Integer.parseInt(encoded.substring(i + 1, i + 3), 16))
            i += 3
          catch
            case _: NumberFormatException =>
              error = Some(KeyError.MalformedTag(encoded, s"bad percent escape at $i"))
        case '%' =>
          error = Some(KeyError.MalformedTag(encoded, s"truncated percent escape at $i"))
        case c =>
          bytes.write(c.toInt)
          i += 1
    error.toLeft(new String(bytes.toByteArray, java.nio.charset.StandardCharsets.UTF_8))

  /** THE DISCRIMINATOR FOR A PATH THAT IS NOT A CHAIN OF HEADINGS, and the one invariant it
    * rests on, stated here because the encoding is unreadable without it.
    *
    * Percent-encoding a non-empty segment cannot produce an empty one, so a leading EMPTY token
    * is a shape no heading path can take — which leaves it free to mean something else.
    *
    * WHY NOT A PLAIN PREFIX ON EVERY PATH, which would be easier to read. Because `h/` in front
    * of every heading path would rewrite the identity of every card that already exists, and
    * `extract/golden/fixture-cards.txt` pins them all under `DO NOT REGENERATE THIS FILE`.
    * Rewriting every identity line by hand is indistinguishable, in a diff, from the blind
    * regeneration that file exists to catch. Heading paths therefore encode byte-for-byte as
    * they always have, and the new kinds take a shape that was previously unreachable.
    */
  private val NotAHeadingPath = ""

  private val PropertyMark = "p"
  private val NoteMark     = "n"

  /** ONE LETTER, LIKE ITS TWO NEIGHBOURS, and it must never be reused for anything else: an
    * identity written into a collection outlives every decision made after it.
    */
  private val BlockMark = "b"

  /** `src::{id}::{path}` — the tag that binds a markdown card to its Anki note.
    *
    * The path is `{seg}/{seg}/…` for headings, `/p/{name}` for a frontmatter property, `/n` for
    * the note itself, and `/b/{anchor}` for one block named by its `^blockid`. See
    * [[NotAHeadingPath]] for why the last three are unambiguous.
    */
  def encode(key: CardKey): OwnedTag =
    val id = encodeComponent(key.noteId.value)
    val path = key.path match
      case CardPath.Headings(headings) =>
        headings.segments.toVector.map(s => encodeComponent(s.value)).mkString(SegmentSep)
      case CardPath.Property(name) =>
        Vector(NotAHeadingPath, PropertyMark, encodeComponent(name.value)).mkString(SegmentSep)
      case CardPath.Note =>
        Vector(NotAHeadingPath, NoteMark).mkString(SegmentSep)
      case CardPath.Block(anchor) =>
        Vector(NotAHeadingPath, BlockMark, encodeComponent(anchor.value)).mkString(SegmentSep)
    OwnedTag.unsafe(s"${OwnedTag.SrcPrefix}$FieldSep$id$FieldSep$path")

  /** Recover the key from a tag read back out of Anki. */
  def decode(tag: String): Either[KeyError, CardKey] =
    def malformed(reason: String) = KeyError.MalformedTag(tag, reason)
    tag.split(FieldSep, -1).toList match
      case OwnedTag.SrcPrefix :: rawId :: rawPath :: Nil =>
        for
          idText <- decodeComponent(rawId)
          noteId <- NoteId.fromFrontmatter(idText)
          path <- decodePath(rawPath, malformed)
        yield CardKey(noteId, path)
      case OwnedTag.SrcPrefix :: _ =>
        Left(malformed("expected exactly src::<id>::<path>"))
      case _ =>
        Left(malformed(s"not a ${OwnedTag.SrcPrefix}$FieldSep tag"))

  /** THE KIND IS DECIDED BY THE FIRST TOKEN AND NOTHING ELSE, which is what makes this total.
    *
    * An unrecognised mark is MALFORMED rather than quietly read as a heading.
    */
  private def decodePath(
      rawPath: String,
      malformed: String => KeyError,
  ): Either[KeyError, CardPath] =
    rawPath.split(SegmentSep, -1).toVector match
      case Vector(NotAHeadingPath, NoteMark) => Right(CardPath.Note)

      case Vector(NotAHeadingPath, BlockMark, rawAnchor) =>
        for
          text   <- decodeComponent(rawAnchor)
          anchor <- BlockAnchor.read(text)
        yield CardPath.Block(anchor)

      case Vector(NotAHeadingPath, PropertyMark, rawName) =>
        for
          name  <- decodeComponent(rawName)
          value <- PropertyName.fromFrontmatter(name)
        yield CardPath.Property(value)

      case tokens if tokens.headOption.contains(NotAHeadingPath) =>
        Left(malformed(s"'$rawPath' is marked as not being a heading path, and names no kind this tool knows"))

      case tokens =>
        for
          segments <- tokens.traverse(s => decodeComponent(s).flatMap(HeadingSegment.fromDecoded))
          nev      <- NonEmptyVector.fromVector(segments).toRight(malformed("empty heading path"))
        yield CardPath.Headings(HeadingPath(nev))
