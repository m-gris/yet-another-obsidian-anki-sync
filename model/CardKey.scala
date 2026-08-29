package obsidiananki.model

import cats.data.NonEmptyVector

/** Card identity: the key derived from the markdown, and its encoding into an Anki tag.
  *
  * The central design property of this tool is that NOTHING GENERATED IS EVER WRITTEN BACK
  * INTO THE MARKDOWN. Identity is therefore *derived* from text already present — the
  * frontmatter `id` and the chain of ancestor headings — and the *binding* to an Anki note
  * is stored in Anki, as a tag. Anki is a derived artifact, so bookkeeping there costs
  * nothing; that is precisely what makes it unacceptable in the source.
  */

/** A note's `id:` frontmatter value. Non-empty by construction. */
opaque type NoteId = String

object NoteId:
  /** Reject blank ids loudly. `id:` is load-bearing — a note without one produces no cards,
    * and a note with a blank one would produce cards under a key nothing can distinguish.
    *
    * The value is canonicalised at construction, so an id is already in the form that will
    * be encoded, hashed and compared. Note this is a no-op for every id shape in the
    * reference vault — they are lowercase alphanumerics and hyphens — which is exactly why
    * `2026-08-18` must survive verbatim.
    */
  def fromFrontmatter(raw: String): Either[KeyError, NoteId] =
    val canonical = TagCodec.canonical(raw)
    if canonical.isEmpty then Left(KeyError.BlankNoteId) else Right(canonical)

  extension (id: NoteId) def value: String = id

/** One segment of a heading path: the marked heading, or one of its ancestors.
  *
  * RULED (B5): a segment is the heading's EXTRACTED TEXT with the `#flashcard/…` marker
  * stripped — not the raw markdown source, and not the rendered HTML.
  *
  * The reason is stability under formatting edits: bolding a word in a heading must never
  * orphan its card. The accepted cost is a deliberate equality — `## **CAP**` and `## CAP`
  * are the SAME key. That is documented, not discovered.
  */
opaque type HeadingSegment = String

object HeadingSegment:
  /** Matches a `#flashcard` marker and any number of `/`-separated qualifiers, so that
    * `/3way` and `/3way/all` are both removed in full.
    */
  private val Marker = """#flashcard(?:/[\w-]+)*""".r

  /** Strip the marker, canonicalise, reject what is left if it is empty.
    *
    * A heading that consists ONLY of a marker, or only of markup that extracts to nothing,
    * has no segment to contribute and must fail rather than silently key as "".
    *
    * STRIPPING THE MARKER IS NOT COSMETIC. The key must be marker-independent, or retagging
    * a heading from `3way` to `3way/all` — an edit that changes only how many cards are
    * generated — would change the key, orphaning the note and minting a duplicate.
    */
  def fromExtractedText(raw: String): Either[KeyError, HeadingSegment] =
    val canonical = TagCodec.canonical(Marker.replaceAllIn(raw, ""))
    if canonical.isEmpty then Left(KeyError.EmptyHeadingSegment(raw)) else Right(canonical)

  /** For values recovered from an existing tag, which are canonical already and must not be
    * marker-stripped a second time.
    */
  private[model] def fromDecoded(decoded: String): Either[KeyError, HeadingSegment] =
    if decoded.isEmpty then Left(KeyError.EmptyHeadingSegment(decoded)) else Right(decoded)

  extension (s: HeadingSegment) def value: String = s

/** The chain of ancestor headings down to the marked one, outermost first.
  *
  * Non-empty because a card always has at least the marked heading itself. This is what
  * makes two identically-named facets under different ancestors distinguishable —
  * `CAP Theorem / Definition` and `Quorum / Definition` rather than `Definition` twice.
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

  /** Canonicalise, and refuse what canonicalises to nothing. */
  def fromFrontmatter(raw: String): Either[KeyError, PropertyName] =
    val canonical = TagCodec.canonical(raw)
    if canonical.isEmpty then Left(KeyError.EmptyPropertyName(raw)) else Right(canonical)

  /** For a name recovered from an existing tag, which is canonical already. */
  private[model] def fromDecoded(decoded: String): Either[KeyError, PropertyName] =
    if decoded.isEmpty then Left(KeyError.EmptyPropertyName(decoded)) else Right(decoded)

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
  * something that cannot occur. Three cases, no impossible fourth.
  *
  * ==Why the names must not collide==
  *
  * `special-case-of:` in the frontmatter and `# Special-Case-Of` in the body are two different
  * cards that a bare-name path would give one key. That is not hypothetical: both spellings were
  * on the table as ways of writing the same relation. One key for two cards is a duplicate, and
  * a duplicate identity refuses the whole run — loudly, so nothing is corrupted, but the vault
  * stops syncing until a name is changed. Distinguishing the KIND removes the collision by
  * construction rather than by asking authors to avoid it.
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
  * The tool owns EXACTLY the `src::`, `sha::` and `orphaned::` prefixes. Every other tag on
  * a note belongs to the person using Anki — a leech marker, a custom study scope — and
  * must be preserved untouched. Anki being a derived artifact makes bookkeeping there
  * acceptable; it does not hand us the namespace.
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

  /** True when a tag read back from Anki is one of ours. Everything else is untouchable.
    *
    * Matches on the FIRST `::`-separated component only, so `source::x` is not mistaken for
    * `src::x` and a person's own `my::own::hierarchy` is left alone.
    *
    * CASE-INSENSITIVE, and that is not a nicety. Anki folds tag case, so `SRC::x` and
    * `src::x` are THE SAME TAG in the collection. Comparing case-sensitively here would
    * mean calling a tag foreign that Anki cannot distinguish from one of ours — we would
    * promise to preserve something we were simultaneously overwriting.
    */
  def isOwned(tag: String): Boolean =
    ownedPrefixes.contains(
      tag.split("::", -1).headOption.getOrElse("").toLowerCase(java.util.Locale.ROOT)
    )

  /** The content hash tag, used to decide "nothing to do" BEFORE any call is made.
    *
    * Necessary because `updateNoteFields` has no early-out — it bumps the note's
    * modification stamp even when the text is identical — so "zero changes on re-run"
    * cannot be achieved by sending everything and letting Anki dedupe. Lowercase because
    * Anki case-folds tags.
    */
  def sha(hex: String): OwnedTag = s"$ShaPrefix::${hex.toLowerCase(java.util.Locale.ROOT)}"

  /** Marks a note whose markdown source has disappeared. The sync never deletes; a separate
    * explicit `prune` command lists these and removes them only when asked.
    */
  def orphaned(key: CardKey): OwnedTag =
    s"$OrphanedPrefix::${TagCodec.encode(key).value.stripPrefix(s"$SrcPrefix::")}"

  /** An author's OWN tag, carried from a note's frontmatter into this tool's namespace.
    *
    * NAMESPACED RATHER THAN VERBATIM, AND THAT IS NOT TIDINESS. A verbatim `scala` in Anki is
    * indistinguishable from a `scala` somebody added by hand, so removing a tag deleted in the
    * vault would mean deleting a tag this tool never wrote. Under a prefix it owns, the set is a
    * pure function of the vault: added when the author adds one, removed when they remove one,
    * and it can never touch a tag it did not write.
    *
    * THE HAZARD IS ANKI ITSELF, NOT THE AUTHOR, which is why discipline could not have replaced
    * this. Anki adds `leech` on its own when a card lapses too often, and `marked` when a card is
    * marked in the reviewer. Both land on notes this tool generated. A verbatim sync that removed
    * whatever the vault no longer named would delete Anki's own record of which cards are giving
    * the author trouble — information that can only be earned back by failing reviews again.
    *
    * NESTING SURVIVES: Obsidian's `/` becomes Anki's `::`, so the author's tag tree appears in
    * Anki's sidebar with its shape intact. See [[VaultTag.read]], which is the only caller and
    * which decides what may be carried at all.
    */
  private[model] def vault(nested: String): OwnedTag =
    s"${VaultTag.Prefix}::${nested.replace("/", "::").toLowerCase(java.util.Locale.ROOT)}"

  /** Escape hatch that bypasses every guarantee this type exists to provide.
    *
    * Legitimate uses are narrow: reconstructing a tag already read back out of Anki, and
    * constructing deliberately malformed tags in tests. Prefer [[TagCodec.encode]], [[sha]]
    * or [[orphaned]] everywhere else — those cannot produce a tag Anki would tear apart.
    */
  def unsafeFromString(s: String): OwnedTag = s

  private[model] def unsafe(s: String): OwnedTag = s

  extension (t: OwnedTag) def value: String = t

enum KeyError:
  case BlankNoteId
  case EmptyHeadingSegment(raw: String)

  /** A frontmatter property whose name canonicalises to nothing. Cannot arise from YAML, which
    * has no empty keys, but can from a tag that was hand-edited into that shape.
    */
  case EmptyPropertyName(raw: String)

  /** A `^blockid` that is empty once trimmed. Cannot arise from the parser, whose production
    * requires at least one character, but can from a tag hand-edited into that shape.
    */
  case EmptyBlockAnchor(raw: String)

  /** A `^blockid` holding something outside Obsidian's own set of letters, digits and hyphens. */
  case UnusableBlockAnchor(raw: String)

  case MalformedTag(tag: String, reason: String)

/** Encoding of a [[CardKey]] into the Anki tag that binds it to a note, and back.
  *
  * RULED (B1). Anki tags are WHITESPACE-DELIMITED — a tag cannot contain a space — and 62
  * of 80 headings in the reference vault contain one. A raw `src::{id}::{path}` tag is
  * therefore torn into fragments on write and unfindable on read, silently. The encoding
  * below is what makes the whole identity mechanism work.
  *
  * The rules, all decided rather than inferred:
  *
  *   - PERCENT-ENCODE everything outside a declared ASCII safe set. The safe set excludes
  *     whitespace; `_` and `*` (both are WILDCARDS in Anki's tag search, so leaving them
  *     raw turns every exact lookup into a fuzzy one); `/` (it occurs inside real headings,
  *     so it cannot also serve as the unencoded segment separator); and `:` (Anki's own
  *     hierarchy separator).
  *
  *   - EQUALITY IS NFC + CASE-FOLDING, because Anki matches and unifies tags
  *     case-insensitively and normalises on save. Canonicalising on the way IN means our
  *     computed key already matches what Anki will store. The consequence is a deliberate
  *     equality: two headings differing only in case are the SAME card. Documented, not
  *     discovered.
  *
  *   - BASE64URL WAS REJECTED. It would make orphan lists unreadable in Anki's browser and
  *     remove the ability to debug by eye — a real cost, since the orphan list is reviewed
  *     by a human before anything is pruned.
  *
  *   - "PICK A RARER SEPARATOR" WAS REJECTED on evidence: no character is impossible in a
  *     heading, and the fixture vault's `## Cost / benefit` already contains the obvious
  *     candidate.
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

  /** NFC-normalise, trim, and case-fold. Applied at CONSTRUCTION of [[NoteId]] and
    * [[HeadingSegment]], so every value downstream is already canonical and encoding is a
    * pure transport concern.
    *
    * Case-folding is forced on us: Anki matches and unifies tags case-insensitively, so
    * `## Costs` and `## costs` ARE the same card whether we like it or not. Folding here
    * makes that a stated equality rather than a false orphan plus a false create on every
    * run. Locale.ROOT because a Turkish locale would fold `I` to a dotless `ı`.
    *
    * WHITESPACE IS NOT SIGNIFICANT — it is trimmed at the ends and internal runs collapse
    * to a single space, so `a  b` and `a b` are the SAME key. The reason is not tidiness: a
    * markdown linter or formatter normalises a stray double space as a matter of routine
    * (markdownlint and prettier both do, by default), so treating internal whitespace as
    * significant would mean A FORMATTING PASS SILENTLY ORPHANS CARDS. That is the same
    * argument that makes a segment extracted text rather than raw source — the key must be
    * stable under changes that alter only presentation.
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
    * A heading path encodes as `seg/seg/…` and EVERY SEGMENT IS NON-EMPTY: `HeadingSegment`
    * refuses an empty value at construction and again at `fromDecoded`, and percent-encoding a
    * non-empty string cannot produce an empty one. So a leading EMPTY token — a path beginning
    * with the separator — is a shape no heading path can ever take, and is therefore free to
    * mean something else. `TagCodecTest` pins that invariant directly rather than trusting it.
    *
    * WHY NOT A PLAIN PREFIX ON EVERY PATH, which would be easier to read. Because `h/` in front
    * of every heading path would rewrite the identity tag of every card that already exists, and
    * the golden file that pins them is 498 lines with `DO NOT REGENERATE THIS FILE` at the top.
    * Changing 55 identity lines by hand is indistinguishable, in a diff, from the blind
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

  /** Recover the key from a tag read back out of Anki.
    *
    * Left-anchored splitting is safe: `::` cannot survive [[encodeComponent]], so the first
    * two occurrences are always the real structural separators.
    */
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
    * A non-empty first token is a heading segment, and therefore so is every token — there is no
    * mixed path (see [[CardPath]]). An empty first token is the mark that the path is something
    * else, and the token after it says which. An unrecognised mark is MALFORMED rather than
    * quietly read as a heading: a tag this tool cannot place must never be filed as though it
    * belonged somewhere, because a mis-filed card is updated in place and the card it really
    * names is created again beside it.
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
          anchor <- BlockAnchor.fromDecoded(text)
        yield CardPath.Block(anchor)

      case Vector(NotAHeadingPath, PropertyMark, rawName) =>
        for
          name  <- decodeComponent(rawName)
          value <- PropertyName.fromDecoded(name)
        yield CardPath.Property(value)

      case tokens if tokens.headOption.contains(NotAHeadingPath) =>
        Left(malformed(s"'$rawPath' is marked as not being a heading path, and names no kind this tool knows"))

      case tokens =>
        for
          segments <- tokens.traverseEither(s => decodeComponent(s).flatMap(HeadingSegment.fromDecoded))
          nev      <- NonEmptyVector.fromVector(segments).toRight(malformed("empty heading path"))
        yield CardPath.Headings(HeadingPath(nev))

  /** Local traverse, to avoid pulling a cats syntax import in for one call site. */
  extension [A](v: Vector[A])
    private def traverseEither[E, B](f: A => Either[E, B]): Either[E, Vector[B]] =
      v.foldLeft[Either[E, Vector[B]]](Right(Vector.empty)) { (acc, a) =>
        for { bs <- acc; b <- f(a) } yield bs :+ b
      }
