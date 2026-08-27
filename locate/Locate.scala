package obsidiananki.locate

import cats.data.NonEmptyVector
import obsidiananki.extract.{Frontmatter, PropertyValue, VaultFile}
import obsidiananki.model.{CardKey, CardPath, KeyError, NoteId, TagCodec}
import obsidiananki.plan.VaultScan

/** Turning a `src::` identity tag back into a place in the vault, for the `locate` subcommand.
  *
  * THIS IS THE INVERSE OF THE THING THE REST OF THE TOOL DOES, and it is not a total inverse.
  * `model/CardKey.scala` derives a key from text and stores the binding in Anki; here an Anki
  * note hands back a key and asks where it came from. The fold that produced the key is lossy by
  * DESIGN — case, whitespace runs and inline markup are all deliberately discarded, so that a
  * formatting pass cannot orphan a card — so the reverse direction cannot be a lookup. It is a
  * SEARCH, and a search can come up empty or come up twice.
  *
  * WHY THIS LIVES IN SCALA AT ALL, when the thing that consumes it is a Python add-on. Because
  * the alternative is a second implementation of `TagCodec.canonical` and of heading-text
  * extraction — the two functions every card's identity passes through. A copy of them in
  * another language, held honest only by a test, is the defect class this project fights
  * hardest. The add-on therefore asks this question rather than answering it.
  */

/** A vault's NAME, which is what Obsidian's URI scheme addresses.
  *
  * Distinct from `cli.VaultRoot`, which is a PATH that has been checked for Obsidian's marker
  * directory. The two are not derivable from one another: a vault's name is what its registry
  * entry calls it, not necessarily its directory's basename.
  */
opaque type VaultName = String

object VaultName:
  /** No validation. A vault name is whatever Obsidian's registry calls it, and this tool has no
    * standing to judge it — the only thing that could is Obsidian, which answers by opening the
    * vault or not.
    */
  def apply(raw: String): VaultName = raw
  extension (v: VaultName) def value: String = v

/** A note's frontmatter `id` **AS WRITTEN IN THE FILE**, and the whole reason this type exists
  * is that it must never be confused with [[obsidiananki.model.NoteId]].
  *
  * `NoteId` is CANONICAL — it has been through `TagCodec.canonical`, so it is NFC-normalised,
  * trimmed, whitespace-collapsed and **lowercased**. That is correct for a key and wrong for a
  * lookup: Obsidian's Advanced URI plugin matches `uid=` against the raw frontmatter value with
  * an exact string comparison that folds nothing (read out of Advanced URI 2.0.0 on 2026-08-27).
  *
  * So sending the key's copy of the id would work for every lowercase id and fail for any other
  * — and fail SILENTLY, because a uid that does not resolve leaves the plugin with no file to
  * open and its dispatcher then falls through every branch and does nothing at all. No note, no
  * notice, no log. Pressing the edit key would simply appear to be broken.
  *
  * Marc's ids are lowercase hexadecimal UUIDs, so the bug is inert today. A SEPARATE TYPE IS
  * WHAT KEEPS IT INERT: the two ids cannot be substituted for one another by accident, and the
  * compiler refuses the mistake rather than a reviewer having to catch it.
  */
opaque type FrontmatterId = String

object FrontmatterId:
  /** Package-private on purpose: the only honest source of one of these is a note that was just
    * read off disk. A `FrontmatterId` conjured from anywhere else would be exactly the canonical
    * id wearing the wrong type, which is the confusion this type exists to prevent.
    */
  private[locate] def fromNote(raw: String): FrontmatterId = raw
  extension (f: FrontmatterId) def value: String = f

/** A **1-based** line number, counted the way an editor counts.
  *
  * The base is stated because it is exactly the kind of off-by-one that produces a plausible
  * wrong answer — landing one line above the heading looks like a rendering quirk, not a bug.
  * Whether Advanced URI's `line=` agrees is a Phase 4 question; if it does not, the conversion
  * belongs at the URI boundary and nowhere else.
  */
opaque type LineNumber = Int

object LineNumber:
  /** `Extractor`'s `LineIndex` answers 0 when it cannot find a heading, and 0 IS NOT A LINE.
    * Refusing it here is what turns that sentinel into [[Unplaceable.LineUnknown]] instead of a
    * position that would quietly send an editor to the top of the file while claiming to be one.
    */
  private[locate] def fromScan(line: Int): Option[LineNumber] = Option.when(line > 0)(line)
  extension (l: LineNumber) def value: Int = l

/** A finished `obsidian://adv-uri` URI.
  *
  * Opaque with one constructor and one exit, the mechanism `content.Html.Fragment` already uses.
  * PERCENT-ENCODING FOR A URI QUERY IS NOT `TagCodec`'S ENCODING and the two must not be able to
  * touch each other: one escapes for Anki's tag grammar, the other for a URL, and they disagree
  * about almost every character that matters.
  */
opaque type ObsidianUri = String

object ObsidianUri:
  /** THE ONLY CONSTRUCTOR, and it is package-private so that [[Uri.of]] is the only caller.
    * An opaque type is transparent to its COMPANION and to nothing else, so this is also what
    * makes the escaping unbypassable: a raw String cannot become a URI anywhere but here.
    */
  private[locate] def rendered(raw: String): ObsidianUri = raw
  extension (u: ObsidianUri) def value: String = u

/** WHY AN ANCHOR COULD NOT BE PLACED — never *that* it could not.
  *
  * All three arms end in the same behaviour, opening the note at its top, and they are still
  * separate because they are different messages to a person, and two of them are different
  * messages about DIFFERENT THINGS: one is a fact about the vault, one is a limit of this tool,
  * and one means the vault cannot currently be synced at all.
  *
  * NONE OF THEM IS AN ERROR. The card exists in Anki, so it was derivable once; every arm here
  * is a way the markdown has moved on since.
  */
enum Unplaceable:

  /** The note is still there; this card is not in it any more.
    *
    * Overwhelmingly the ordinary case, and worth saying plainly to whoever pressed the key: a
    * marked heading was reworded or removed, which RETIRES its card and mints a new one. The
    * card in front of them is the old one, and the next sync will flag it.
    */
  case CardGone(path: CardPath)

  /** The card is in the scan and its line could not be recovered. A LIMIT OF THIS TOOL, not a
    * fact about the vault, and it should read that way when it is reported.
    *
    * Laika does not retain source positions, so `Extractor`'s `LineIndex` recovers a heading's
    * line by matching its text back against the raw body, and answers 0 when it cannot. This is
    * that 0, given a name instead of being passed on as a line number that would send an editor
    * to the top of the file while claiming to be a position.
    */
  case LineUnknown(path: CardPath)

  /** Two cards in the vault carry this one key.
    *
    * THE VAULT CANNOT BE SYNCED IN THIS STATE — a duplicate identity refuses the whole run — so
    * this is not really an anchor problem at all; it is the sync's own refusal, met early by
    * whoever happened to press the edit key first. Reported rather than tie-broken, because
    * picking one of two would hide the thing that needs fixing.
    */
  case KeyedTwice(path: CardPath, lines: NonEmptyVector[LineNumber])

/** What a URI can address. Total over the shapes above, so a new anchor kind must answer here
  * before this compiles.
  *
  * NOTE THE ABSENCE OF A HEADING ARM. The design sketch had one, addressing the note by heading
  * NAME. It cannot work: Advanced URI matches a heading by exact equality against its raw text,
  * and the only heading text this tool holds is the canonical form, which is lowercased and
  * whitespace-collapsed. Every heading with a capital letter in it would miss. The anchor is
  * therefore a LINE, which is also why [[Locate.anchor]] has to read the file.
  */
enum UriTarget:
  case WholeNote(vault: VaultName, uid: FrontmatterId)
  case AtLine(vault: VaultName, uid: FrontmatterId, line: LineNumber)

/** The answer `locate` gives, and the reason it is a sum rather than an `Option[Uri]`.
  *
  * TWO OF THESE FOUR OPEN SOMETHING AND TWO DO NOT, and the add-on must be able to tell which
  * without inspecting a string. An `Option` would collapse `Unplaced` into `Placed` — opening
  * somewhere arbitrary while reporting success — or into nothing, discarding the note we DID
  * find. Both were the shape the design document called out as unrepresentable.
  */
enum Located:

  /** The note was found and the anchor was placed within it. */
  case Placed(uri: ObsidianUri)

  /** The note was found; the anchor was not. Opens at the top, and says why. */
  case Unplaced(uri: ObsidianUri, why: Unplaceable)

  /** No note in this vault carries that id. NOTHING IS EMITTED — see [[Locate.decide]]. */
  case NoteMissing(id: NoteId)

  /** The tag did not decode. Hand-edited, or written by something that is not this tool. */
  case Undecodable(tag: String, reason: KeyError)

object Locate:

  /** Find the note carrying this id, and hand back the id AS THE FILE SPELLS IT.
    *
    * The comparison is canonical on both sides — the tag's id already is, and each file's
    * frontmatter value is folded before matching — because that is the equality the key was
    * built on. What comes back is the RAW value, because that is the one Obsidian can match.
    *
    * Needed even when the anchor resolves, since the scan records a card's FILE but the URI is
    * addressed by frontmatter id rather than by path.
    */
  def note(id: NoteId, files: Vector[VaultFile]): Either[NoteId, (VaultFile, FrontmatterId)] =
    files.iterator
      .flatMap(file => rawId(file).map(raw => (file, raw)))
      .collectFirst {
        case (file, raw) if TagCodec.canonical(raw) == id.value =>
          (file, FrontmatterId.fromNote(raw))
      }
      .toRight(id)

  /** The frontmatter key this tool derives identity from. The literal is also spelled out at
    * `extract/VaultWalker.scala:469`, where the id is read on the way IN; the two must agree.
    */
  private val IdProperty = "id"

  /** A note's `id` exactly as its file spells it, or nothing.
    *
    * A NOTE THIS CANNOT PARSE IS SKIPPED RATHER THAN REFUSED, and that is not the swallowed
    * failure it resembles. This is a SEARCH: a note whose frontmatter is unreadable is not the
    * note being looked for, and refusing here would mean one broken file anywhere in the vault
    * stopped every card in it from opening. The breakage itself is not being hidden — `sync`
    * reports it, loudly, which is where it belongs.
    */
  private def rawId(file: VaultFile): Option[String] =
    Frontmatter.read(file.content).toOption.flatMap { (keys, _) =>
      keys.get(IdProperty) match
        case Some(PropertyValue.One(text))     => Some(text)
        case Some(PropertyValue.Many(_))       => None
        case Some(PropertyValue.Unreadable(_)) => None
        case None                              => None
    }

  /** Where this card sits, ASKED OF THE SCAN THE SYNC ITSELF USES.
    *
    * ==Why this is a lookup and not a search==
    *
    * An earlier sketch of this file had a function that re-found the heading: walk the note,
    * apply the same fold to each heading, compare. That would have been a SECOND traversal
    * agreeing with the first by construction and by nothing else — the defect class this whole
    * design exists to avoid, reappearing one layer down.
    *
    * It is also unnecessary. `VaultWalker.scan` already produces, for every card in the vault, a
    * `SourcedSpec` carrying both its `CardKey` and a `SourceRef` whose line is the FILE's own
    * line number. So the anchor is not recomputed here at all: it is read off the same scan the
    * sync plans from. Not merely the same implementation of the fold — THE SAME EXECUTION OF IT.
    *
    * ==Why the scan's completeness does not matter here==
    *
    * `VaultScan` distinguishes a complete walk from a partial one because orphan inference is
    * unsound without the whole vault. This asks about ONE key that is either present or absent,
    * and a key that is present really was seen, so the distinction carries no weight on this
    * path. Stated because silently ignoring that sum would otherwise look like an oversight.
    */
  def anchor(key: CardKey, scan: VaultScan): Either[Unplaceable, LineNumber] =
    val lines = scan.specs.filter(_.key == key).map(_.source.line)
    if lines.isEmpty then Left(Unplaceable.CardGone(key.path))
    else if lines.sizeIs == 1 then
      LineNumber.fromScan(lines.head).toRight(Unplaceable.LineUnknown(key.path))
    else
      // TWO CARDS, ONE KEY. Reported rather than tie-broken — see `Unplaceable.KeyedTwice`.
      // When not one of their lines could be recovered there is nothing to report them AT, so
      // this degrades to the weaker statement rather than inventing a position. The duplicate is
      // the more important fact of the two, and it is the one `sync` will refuse over anyway.
      Left(
        NonEmptyVector
          .fromVector(lines.flatMap(LineNumber.fromScan))
          .fold(Unplaceable.LineUnknown(key.path))(Unplaceable.KeyedTwice(key.path, _))
      )

  /** The link, if the answer has one. THE SEAM BETWEEN A REPORT AND A CALLER THAT IS A PROGRAM.
    *
    * The Anki add-on cannot read prose and must not have to work out which line of a report is
    * the link. Asking here is total over [[Located]], so a fifth outcome has to say whether it
    * opens anything before this compiles — which is the question that would otherwise be settled
    * by accident, inside a string.
    */
  def uriOf(result: Located): Option[ObsidianUri] = result match
    case Located.Placed(uri)      => Some(uri)
    case Located.Unplaced(uri, _) => Some(uri)
    case Located.NoteMissing(_)   => None
    case Located.Undecodable(_, _) => None

  /** The whole question, composed. THE ONE SIGNATURE THE ADD-ON DEPENDS ON.
    *
    * NO URI IS EMITTED FOR A NOTE THAT WAS NOT FOUND, and that is a decision rather than an
    * omission. A URI naming an unresolvable id is not an error anywhere downstream — the plugin
    * silently does nothing with it and the add-on cannot observe that it did. This is the only
    * side of the system that reads the vault, so it is the only side that can catch it.
    */
  def decide(
      tag: String,
      vault: VaultName,
      files: Vector[VaultFile],
      scan: VaultScan,
  ): Located =
    TagCodec.decode(tag) match
      case Left(reason) => Located.Undecodable(tag, reason)
      case Right(key) =>
        note(key.noteId, files) match
          case Left(missing) => Located.NoteMissing(missing)
          case Right((_, uid)) =>
            anchor(key, scan) match
              case Right(line) => Located.Placed(Uri.of(UriTarget.AtLine(vault, uid, line)))
              case Left(why)   => Located.Unplaced(Uri.of(UriTarget.WholeNote(vault, uid)), why)

object Uri:

  private val Scheme = "obsidian://adv-uri"

  /** Render a target. The only way to make an [[ObsidianUri]]. */
  def of(target: UriTarget): ObsidianUri = target match
    case UriTarget.WholeNote(vault, uid) =>
      ObsidianUri.rendered(s"$Scheme?vault=${escape(vault.value)}&uid=${escape(uid.value)}")
    case UriTarget.AtLine(vault, uid, line) =>
      ObsidianUri.rendered(
        s"$Scheme?vault=${escape(vault.value)}&uid=${escape(uid.value)}&line=${line.value}"
      )

  /** Percent-encoding for a URI query: RFC 3986's unreserved set kept literal, every other byte
    * escaped, hex in upper case as that RFC prefers.
    *
    * THIS IS NOT `TagCodec.encodeComponent` AND MUST NEVER BECOME IT. The two escape for
    * different grammars — one for Anki's whitespace-delimited tag syntax, the other for a URL —
    * and they disagree about nearly every character that matters. `_` and `~` are safe here and
    * emphatically not there, where both are search wildcards; `.` and `-` happen to be safe in
    * both, which is the coincidence that would make a shared implementation look correct for as
    * long as the ids stayed hexadecimal.
    *
    * `java.net.URLEncoder` was REJECTED: it is form encoding, not URI encoding, and writes a
    * space as `+`. Obsidian's parser would read that back as a literal plus in a vault name.
    */
  private def escape(raw: String): String =
    val out = new StringBuilder
    raw.getBytes(java.nio.charset.StandardCharsets.UTF_8).foreach { b =>
      val c = (b & 0xff).toChar
      val unreserved =
        (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9') ||
          c == '-' || c == '.' || c == '_' || c == '~'
      if b >= 0 && unreserved then out.append(c) else out.append("%%%02X".format(b & 0xff))
    }
    out.toString
