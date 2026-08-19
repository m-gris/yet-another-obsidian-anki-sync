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

final case class SourceRef(file: String, line: Int, kind: SourceKind):
  def describe: String = s"$file:$line (${kind.describe})"

/** A spec together with its provenance. Provenance is a wrapper rather than a field of
  * [[CardSpec]] because a spec is about card CONTENT; where it came from is a different
  * concern, needed only for diagnostics.
  */
final case class SourcedSpec(spec: CardSpec, source: SourceRef):
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

  /** Neither key nor note id could be derived — missing or unreadable frontmatter. Observed
    * keys cannot even be GROUPED by file, so no orphan inference is possible at all and the
    * scan as a whole degrades to partial.
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
