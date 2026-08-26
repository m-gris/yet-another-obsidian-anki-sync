package obsidiananki.plan

import obsidiananki.model.{CardKey, CardSpec, NoteId, RecallText}

/** Where a spec came from, so that a collision can be reported legibly.
  *
  * "Duplicate key" alone, on a collision between a table cell and a deeply-nested heading,
  * is a message that costs an hour and teaches nothing. Both sides must name themselves.
  */
enum SourceKind:
  case Heading
  case TablePair
  case TableRow

  /** A frontmatter property — a typed edge. Not a heading, and named so that a diagnostic sends
    * the reader to the top of the file rather than into its body.
    */
  case Property

  def describe: String = this match
    case Heading   => "heading"
    case TablePair => "table pair card"
    case TableRow  => "table row card"
    case Property  => "frontmatter property"

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
/** `recall` IS WHAT THIS CARD ASKS THE REVIEWER TO PRODUCE, and it rides alongside
  * `sectionTitles` because the two are consumed together: a deck path is built from the titles
  * and then cut short of anything in here. See [[RecallText]] for why it is raw text carried
  * from the extractor rather than read back off `spec`.
  */
final case class SourcedSpec(
    spec: CardSpec,
    source: SourceRef,
    sectionTitles: Vector[String],
    recall: RecallText,
):
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

  /** The frontmatter mentions `flashcard`, but no HEADING carries a marker — so no card is made.
    *
    * The shape this catches, seen on a real vault within minutes of it being set up: typing
    * `#flashcard/3way` into a note in the Obsidian desktop app, where the editor lifts it out of
    * the text and files it under the frontmatter `tags` property. The result is a note that
    * looks marked, says `flashcard/3way` at the top in the Properties panel, and produces
    * nothing. The tool's own rule is that a marker belongs ON A HEADING, and a frontmatter tag
    * is invisible to it.
    *
    * WORTH REPORTING BECAUSE THE INTENT IS LEGIBLE. Ordinarily a note with no marked heading is
    * simply prose and saying anything about it would be noise — but a note whose frontmatter
    * names `flashcard` has told us what it was for, and staying silent about the gap between
    * that and its headings is exactly the silent-nothing this design exists to prevent.
    *
    * Non-degrading, like its neighbour: this says nothing about which notes the file owns.
    */
  case MarkerNotOnHeading(file: String, reason: String)

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

  /** A NOTE'S DECLARATIONS BLOCK COULD NOT BE READ, so none of ITS properties makes a card.
    *
    * ITS BLAST RADIUS IS THE NOTE. _It was the whole vault until 2026-08-26, when the vocabulary
    * moved from one vault-wide note to a heading in each note that wants one — lexical scope
    * rather than a global rewrite._
    *
    * REPORTED RATHER THAN PASSED OVER, because a note that DECLARES relations and makes none is
    * not the same as a note that declares none. The second is the ordinary case and says nothing;
    * the first means the cards this note's author expects are silently absent.
    *
    * Non-degrading: it says nothing about which notes own which cards, so orphan inference is
    * untouched, and this note's heading cards are unaffected.
    */
  case EdgeVocabularyUnusable(file: String, reason: String)

  /** The frontmatter declares `flashcard` intent, the markdown WILL NOT PARSE, and there is no
    * usable `id` — so nothing else in this file reports anything, and no claim about markers is
    * available in either direction.
    *
    * IT EXISTS TO CLOSE A HOLE OPENED BY REMOVING A LIE, which is the only reason to add a
    * failure case. Until 2026-08-24 the marker search folded an unparseable document to "no
    * marker found", so this file was reported as [[MarkerNotOnHeading]] — telling the author
    * that no heading carried a marker, about a document nothing had read. Deleting that false
    * report on its own would have left this file SILENT: a note that declared it wanted cards,
    * produced none, and had nothing said about it anywhere.
    *
    * WHY ONLY WHEN THERE IS NO USABLE ID. With an id, the same unparseable markdown is already
    * reported as [[KeyUnderivableInFile]], which names the parser's own error and shelters the
    * note's keys. Both messages would send the reader to the same single action — fix the
    * markdown — so the second would be noise. This fires exactly where nothing else does.
    *
    * NON-DEGRADING, for the same reason as [[MarkedWithoutNoteId]]: a file with no id has never
    * produced an Anki note and owns no observed key, so orphan inference across the rest of the
    * vault is untouched.
    *
    * REACHED BY ORDINARY PROSE. Parsing is strict, so an array index written as `[0]` in a
    * sentence is enough.
    */
  case MarkerUnknowable(file: String, reason: String)

  /** Neither key nor note id could be derived — missing or unreadable frontmatter. Observed
    * keys cannot even be GROUPED by file, so no orphan inference is possible at all and the
    * scan as a whole degrades to partial.
    *
    * NOT the same as "no id". Frontmatter that cannot be PARSED might have carried an id we
    * failed to read, so we genuinely do not know what the file owns. Frontmatter that parses
    * and has no id tells us exactly what it owns: nothing. See [[MarkedWithoutNoteId]].
    */
  case FileUnreadable(file: String, reason: String)

  /** WHAT THIS FAILURE SHELTERS FROM ORPHAN INFERENCE — the one question, asked once.
    *
    * Three consumers used to ask three DIFFERENT questions of this type, each with its own
    * partial match and each silent about a case it had not heard of: `VaultScan.from` asked
    * "does this degrade the scan?" with a `case _ => false`; `failedKeys` and
    * `suppressedNoteIds` asked "does this exclude a key / a note?" with a `collect`. A sixth
    * case had to be remembered in three places, and forgetting any of them compiled clean.
    *
    * They are the same question. What a failure shelters is a property OF THE FAILURE, and the
    * three consumers are projections of it. Written longhand here, `-Wconf:msg=exhaustive:e`
    * makes a new case answer all three at once, or not compile.
    *
    * THE STAKES, SO THE NEXT PERSON KNOWS WHAT THEY ARE ANSWERING. Getting this wrong runs
    * orphan inference on incomplete data, and an orphan is TAGGED AND SUSPENDED — a live card
    * with real review history silently out of the review queue. It has happened once already:
    * `Extractor` records that one image pasted into one table cell flagged fifteen live cards.
    */
  def shelters: OrphanShelter = this match
    // The key was derivable, so the card's Anki counterpart can be excluded BY ITSELF —
    // broken must not read as deleted.
    case KeyKnown(key, _, _) => OrphanShelter.OneKey(key)

    // No key to exclude, so the blast radius widens to the note: the smallest unit still
    // reasonable about.
    case KeyUnderivableInFile(noteId, _, _) => OrphanShelter.WholeNote(noteId)

    // NOTHING TO SHELTER — but for two different reasons, and the distinction matters enough
    // to write out. _The comment here used to say all of these were files with no usable `id`.
    // That was false of the first one, whose own test gives the file `id: n1`; the conclusion
    // was right and the stated reason was not. Corrected 2026-08-24._

    // KNOWN TO OWN NOTHING NOW. This file parses, and the tool can see exactly what it
    // produces: no cards. If it once had marked headings and they were removed, the notes they
    // made ARE deleted and SHOULD be flagged — sheltering them would hide a real orphan.
    case MarkerNotOnHeading(_, _) => OrphanShelter.Nothing

    // NEVER OWNED ANYTHING. A card's identity begins with the frontmatter id, so a file without
    // one has never produced an Anki note and cannot own an observed key.
    case MarkedWithoutNoteId(_, _) => OrphanShelter.Nothing

    // Nothing to shelter: this is about the vault's vocabulary, not about any note's cards.
    case EdgeVocabularyUnusable(_, _) => OrphanShelter.Nothing
    case MarkerUnknowable(_, _)    => OrphanShelter.Nothing

    // The one that costs the whole vault. Frontmatter that will not parse might have carried
    // an id we failed to read, so the file may own notes under a name we cannot see.
    case FileUnreadable(_, _) => OrphanShelter.Unknowable

/** What a [[BuildFailure]] protects from being read as a deletion.
  *
  * FOUR CASES BECAUSE THERE ARE FOUR ANSWERS, and the last is not a bigger version of the
  * third. `WholeNote` says "these keys are accounted for"; `Unknowable` says "I cannot tell you
  * what is accounted for" — a statement about the tool's knowledge rather than about the file,
  * and the only one that can invalidate inference for files it never touched.
  */
enum OrphanShelter:
  case Nothing
  case OneKey(key: CardKey)
  case WholeNote(noteId: NoteId)
  case Unknowable

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
    // group observed keys by note — so it, and only it, degrades the whole scan. WHICH
    // failures those are is `BuildFailure.shelters`, asked rather than restated: a
    // `case _ => false` here would let a sixth case join the harmless ones in silence.
    val unknowable = failures.exists(_.shelters == OrphanShelter.Unknowable)
    if unknowable then PartialScan(specs, failures) else CompleteScan(specs, failures)

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
    def failedKeys: Set[CardKey] = scan.failures.map(_.shelters).collect {
      case OrphanShelter.OneKey(key) => key
    }.toSet

    /** Notes whose keys cannot be enumerated because something in them was underivable.
      * EVERY observed key belonging to one of these notes is suppressed.
      *
      * This is the case a per-key exclusion cannot cover: with no derivable key there is
      * nothing to exclude, so the card's existing note would fall straight through into the
      * orphan set. The blast radius widens from the card to the file — the smallest unit we
      * can still reason about.
      */
    def suppressedNoteIds: Set[NoteId] = scan.failures.map(_.shelters).collect {
      case OrphanShelter.WholeNote(noteId) => noteId
    }.toSet

    /** Whether orphan inference is possible at all. */
    def canInferOrphans: Boolean = scan match
      case CompleteScan(_, _) => true
      case PartialScan(_, _)  => false
