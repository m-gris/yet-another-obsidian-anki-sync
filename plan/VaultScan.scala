package obsidiananki.plan

import obsidiananki.model.{CardKey, CardSpec, NoteId}

/** Where a spec came from, so that a collision can be reported legibly.
  *
  * "Duplicate key" alone, on a collision between a table cell and a deeply-nested heading,
  * is a message that costs an hour and teaches nothing. Both sides must name themselves.
  */
enum SourceKind:
  case Heading
  case TablePair
  case TableRow

  def describe: String = this match
    case Heading   => "heading"
    case TablePair => "table pair card"
    case TableRow  => "table row card"

/** Where a spec came from.
  *
  * `detail` disambiguates sources that share a file, a line AND a kind — which is exactly
  * what two identical row concepts in one table produce. Without it a collision between
  * them reports the same position twice and tells the author nothing about which row to fix.
  */
final case class SourceRef(file: String, line: Int, kind: SourceKind, detail: Option[String] = None):
  def describe: String =
    val where = if line > 0 then s"$file:$line" else file
    s"$where (${kind.describe}${detail.fold("")(d => s", $d")})"

/** A spec together with its provenance. Provenance is a wrapper rather than a field of
  * [[CardSpec]] because a spec is about card CONTENT; where it came from is a different
  * concern.
  *
  * _Amended 2026-08-22: this said provenance was "needed only for diagnostics". It has a
  * second consumer now — a deck path can be composed from where a card sits in its document._
  *
  * `sectionTitles` IS THE HEADING CHAIN FROM THE FILE'S TOP HEADING DOWN TO THE MARKED ONE,
  * as DISPLAY text. Three things it deliberately is not:
  *
  *   - not the card key's path, which is canonicalised — case-folded and whitespace-collapsed
  *     — so a deck built from it would read in permanent lowercase. Same argument, and the
  *     same answer, as `extract/CardContext.scala` reached for the on-card breadcrumb.
  *   - not extended by a table's row concept or column header, though the KEY is. Following
  *     the key that far would mint a deck per table row. Every card one marked heading
  *     produces shares that heading's chain.
  *   - not a deck. It is one ingredient; `extract/VaultWalker.scala`'s `DeckShape` decides
  *     whether it is used at all, and the default does not use it.
  *
  * NO DEFAULT VALUE, deliberately. A default would let a test construct a spec whose chain is
  * silently empty while production always fills it, and the deck built from that spec would be
  * correct by accident in the test and wrong in the field.
  */
final case class SourcedSpec(spec: CardSpec, source: SourceRef, sectionTitles: Vector[String]):
  def key: CardKey = spec.key

/** A card that could not be built.
  *
  * The distinction between the two cases is load-bearing for orphan inference, which is the
  * only reason it is modelled rather than collapsed into a message.
  */
enum BuildFailure:
  /** The key was derivable; only the card could not be built — an empty body, a malformed
    * table. The note's existing Anki counterpart must be excluded from orphan inference
    * INDIVIDUALLY, so that broken is not read as deleted.
    */
  case KeyKnown(key: CardKey, source: SourceRef, reason: String)

  /** The key could NOT be derived, but the note's id could — a heading that extracts to
    * nothing, a marker on an empty heading. There is no key to exclude, so every observed
    * key belonging to that NOTE must be suppressed instead: the blast radius is the file.
    */
  case KeyUnderivableInFile(noteId: NoteId, source: SourceRef, reason: String)

  /** The file has MARKED HEADINGS but no `id` in its frontmatter, so its cards cannot be keyed.
    *
    * The author asked for cards and will get none, so this is loud. But it does NOT degrade the
    * scan, and that distinction is the whole reason this case exists apart from
    * [[FileUnreadable]].
    *
    * A card's identity is `(frontmatter id, heading path)`. A file with no id has therefore
    * never produced an Anki note, and cannot own a single observed key — so there is nothing
    * for orphan inference to be confused about, and no reason to give up computing it across
    * the whole vault. _Added 2026-08-22. Before it, a file with no id was reported as
    * `FileUnreadable`, which suppressed orphan inference for EVERY OTHER FILE; the stated
    * reason was that it "costs us the ability to group observed keys by note", which is true
    * of unreadable frontmatter and false of frontmatter that is perfectly readable and simply
    * has no id._
    */
  case MarkedWithoutNoteId(file: String, reason: String)

  /** Neither key nor note id could be derived — missing or unreadable frontmatter. Observed
    * keys cannot even be GROUPED by file, so no orphan inference is possible at all and the
    * scan as a whole degrades to partial.
    *
    * NOT the same as "no id". Frontmatter that cannot be PARSED might have carried an id we
    * failed to read, so we genuinely do not know what the file owns. Frontmatter that parses
    * and has no id tells us exactly what it owns: nothing. See [[MarkedWithoutNoteId]].
    */
  case FileUnreadable(file: String, reason: String)

/** The result of walking the vault.
  *
  * The distinction is the whole point: "present in Anki, absent from markdown" is a valid
  * inference ONLY if the markdown side was seen in full. Making that structural rather than
  * a runtime check means it cannot be forgotten — a planner given a [[PartialScan]] has no
  * way to produce an orphan, because the data to produce one is not there.
  */
enum VaultScan:
  /** Every file in the vault was read and every marked heading resolved to a key — even the
    * ones that then failed to build. Orphan inference is sound.
    */
  case CompleteScan(specs: Vector[SourcedSpec], failures: Vector[BuildFailure])

  /** At least one file could not be read well enough to know what keys it owns. Creates and
    * updates are still sound per-key — a key that IS present really was seen — but no
    * orphan set can be computed.
    */
  case PartialScan(specs: Vector[SourcedSpec], failures: Vector[BuildFailure])

object VaultScan:

  /** Build a scan, choosing complete or partial from the failures themselves rather than
    * letting the caller assert it.
    */
  def from(specs: Vector[SourcedSpec], failures: Vector[BuildFailure]): VaultScan =
    // A file we could not read at all is the one failure that costs us the ability to
    // group observed keys by note — so it, and only it, degrades the whole scan.
    val unreadable = failures.exists {
      case BuildFailure.FileUnreadable(_, _) => true
      case _                                 => false
    }
    if unreadable then PartialScan(specs, failures) else CompleteScan(specs, failures)

  extension (scan: VaultScan)
    def specs: Vector[SourcedSpec] = scan match
      case CompleteScan(s, _) => s
      case PartialScan(s, _)  => s

    def failures: Vector[BuildFailure] = scan match
      case CompleteScan(_, f) => f
      case PartialScan(_, f)  => f

    /** Keys that were built successfully. */
    def builtKeys: Set[CardKey] = scan.specs.map(_.key).toSet

    /** Keys that failed to build but are nonetheless accounted for. Excluded from orphan
      * inference individually, so that BROKEN is not read as DELETED.
      */
    def failedKeys: Set[CardKey] = scan.failures.collect {
      case BuildFailure.KeyKnown(key, _, _) => key
    }.toSet

    /** Notes whose keys cannot be enumerated because something in them was underivable.
      * EVERY observed key belonging to one of these notes is suppressed.
      *
      * This is the case a per-key exclusion cannot cover: with no derivable key there is
      * nothing to exclude, so the card's existing note would fall straight through into the
      * orphan set. The blast radius widens from the card to the file — the smallest unit we
      * can still reason about.
      */
    def suppressedNoteIds: Set[NoteId] = scan.failures.collect {
      case BuildFailure.KeyUnderivableInFile(noteId, _, _) => noteId
    }.toSet

    /** Whether orphan inference is possible at all. */
    def canInferOrphans: Boolean = scan match
      case CompleteScan(_, _) => true
      case PartialScan(_, _)  => false
