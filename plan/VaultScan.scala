package obsidiananki.plan

import obsidiananki.model.{CardKey, CardSpec, NoteId, RecallText, VaultTag}

/** Where a spec came from, so that a collision can be reported legibly.
  *
  * "Duplicate key" alone, on a collision between a table cell and a deeply-nested heading,
  * is a message that costs an hour and teaches nothing. Both sides must name themselves.
  */
enum SourceKind:
  case Heading
  case TablePair
  case TableRow

  /** One block of a note, named by the `^blockid` its author wrote at the end of it. Not a
    * heading, so a diagnostic sends the reader to a paragraph rather than to a title.
    */
  case Block

  /** A frontmatter property — a typed edge. Not a heading, and named so that a diagnostic sends
    * the reader to the top of the file rather than into its body.
    */
  case Property

  def describe: String = this match
    case Heading   => "heading"
    case TablePair => "table pair card"
    case TableRow  => "table row card"
    case Property  => "frontmatter property"
    case Block     => "block"

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

    /** WHAT THE AUTHOR'S OWN FRONTMATTER TAGS BECOME — every outcome, not only the carried ones.
      *
      * ALL THREE KINDS TRAVEL, which is lossless where filtering here would be lossy. A tag Anki
      * cannot hold is something the author needs telling about; discarding it at the point the
      * decision is made would leave nothing downstream able to say which tag was dropped, and a
      * frontmatter tag that silently does nothing is the failure this project keeps meeting.
      *
      * PER SPEC RATHER THAN PER FILE, because one `CardSpec` becomes one Anki NOTE and tags
      * belong to a note. Several specs from one file therefore repeat their file's tags, which
      * is not duplication of state — it is the same value reaching each note that needs it.
      */
    vaultTags: Vector[VaultTag],
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

  /** THE KEYS WERE DERIVED AND THEY ARE THE WRONG ONES. A heading in this file is not read as a
    * heading by this tool, so the file's heading tree is not the one its author wrote and no key
    * derived from that tree can be trusted.
    *
    * ITS OWN CASE, AND NOT [[KeyUnderivableInFile]], THOUGH THE TWO SHELTER THE SAME THING. That
    * one says a key COULD NOT BE DERIVED. This one says keys were derived perfectly well and are
    * filed under headings the author did not write — a state that is strictly worse, because
    * nothing about it fails. Collapsing them would compile and pass every test; what would be
    * lost is the reason. A later change letting `KeyUnderivableInFile` emit the specs it DID
    * manage to build would be perfectly reasonable for the case it describes — a note that lost
    * one heading still has good keys for the others — and would silently re-enable writing cards
    * under keys this tool knows to be wrong.
    *
    * WHY THE BLAST RADIUS IS THE FILE, which departs from the per-heading scoping this codebase
    * otherwise prefers — `SpecError.ListNestingUnreadable` refuses ONE card and lets its siblings
    * sync. That refusal can be contained because a mis-indented list corrupts one section's
    * CONTENT. An unread heading corrupts the file's STRUCTURE: it is missing from the tree every
    * key in the file is derived from, and working out which of the remaining headings are
    * unaffected would mean reconstructing the tree the author meant, which is a guess this
    * project does not make.
    *
    * SHELTERING THE WHOLE NOTE IS THE OTHER HALF OF THAT DECISION AND IS NOT OPTIONAL. Refusing
    * the file's cards without it would make every Anki note the file has already produced look
    * DELETED, and an inferred orphan is tagged and SUSPENDED — live cards with real review
    * history out of the review queue because of one missing blank line.
    *
    * Its id is good and its markdown parsed whenever this fires, since a heading had to be read
    * before it could be found unlifted. See [[obsidiananki.extract.UnreadHeading]] for which
    * headings do this and which merely cost their own card.
    */
  case KeyMisfiledInFile(noteId: NoteId, source: SourceRef, reason: String)

  /** A HEADING IN THIS FILE IS NOT READ AS A HEADING BY THIS TOOL, and the only thing that costs
    * is the card that heading would have made.
    *
    * ITS SIBLING [[KeyMisfiledInFile]] IS THE SAME OBSERVATION WITH A FAR LARGER BILL, and the
    * two are separate cases because they ask different things of the author. There, every card in
    * the file is withheld and the fix is urgent. Here the file's cards are written exactly as
    * before: the heading is indented inside a list item, where CommonMark puts it too, so the two
    * readings agree about the note's outline and no other key moves. Reporting both through one
    * case would leave a reader unable to tell "you will get one fewer card" from "you will get
    * none of them".
    *
    * WORTH REPORTING AT ALL BECAUSE THE INTENT IS LEGIBLE, which is the argument
    * [[MarkerNotOnHeading]] makes for itself. Somebody wrote a heading; this tool makes no card
    * from it and would otherwise say nothing, which is the silent-omission failure this design
    * exists to prevent.
    *
    * IT SHELTERS THE WHOLE NOTE, WHICH IS THE LESS OBVIOUS HALF. Its cards are still written, so
    * it looks as though there is nothing to shelter — and `MarkerNotOnHeading` sheltering nothing
    * rests on the tool being able to see exactly what its file produces. That is the one thing it
    * cannot see here: a heading it does not read is a card it cannot enumerate, so if that card
    * already exists in Anki it would be inferred an orphan and SUSPENDED, for a heading the
    * author never deleted.
    */
  case HeadingUnreadInFile(noteId: NoteId, source: SourceRef, reason: String)

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

  /** A block holds cloze deletions and carries no `^blockid`, so no card could be keyed to it.
    *
    * ITS OWN CASE, AND NON-DEGRADING LIKE ITS NEIGHBOUR. There is no key to exclude, but unlike
    * `KeyUnderivableInFile` this says nothing about which notes the file owns: every other card
    * in it is sound, and one anchorless paragraph must not suppress a whole note's worth.
    *
    * WORTH REPORTING FOR THE REASON THAT ONE IS. The author wrote `==<<…>>==`, which is a
    * statement of intent as explicit as a marker on a heading. Producing nothing and saying
    * nothing is the silent-omission failure this design exists to prevent — and the fix is one
    * keystroke in Obsidian, which the message can name.
    */
  case ClozeBlockUnanchored(file: String, line: Int, reason: String)

  /** A frontmatter tag that ALMOST names a marker — `docs/history/IN-FLIGHT.md` item 37.
    *
    * ITS OWN CASE RATHER THAN A VARIANT OF THE ONE ABOVE, because the fix is different. That one
    * says "move your marker onto a heading"; this one says "you have spelled it wrong". Telling
    * an author to move a marker that is misspelled sends them to the wrong place, which is what
    * happened to Marc on 2026-08-28 for the half of this that was reported at all.
    *
    * THE HALF THAT WAS NOT REPORTED IS THE REASON THIS EXISTS. A tag reading `flashard/...` —
    * one character short — was dropped by a `startsWith` filter, and the separate check meant to
    * catch a note that declared intent and made nothing searches the frontmatter for the
    * substring `flashcard`, so it missed it for the same reason. The one check written for this
    * class of mistake was defeated by a misspelling of the very string it searches for, and the
    * author was told nothing at all.
    *
    * NOTHING IS GUESSED. `Marker.readTag` matches everything after a tag's first segment against
    * the tails this tool documents, exactly — no spelling distance and no threshold. This
    * project's rule that fuzzy matching may RANK but never DECIDE is not bent here; an exact
    * match on the tool's own published vocabulary is not fuzzy.
    *
    * Non-degrading, like its neighbours: it says nothing about which notes the file owns.
    */
  case MarkerMisspelled(file: String, reason: String)

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

    // THE SAME SHELTER FOR THE OPPOSITE REASON, and writing it out rather than sharing the arm
    // above is the point. There the keys could not be derived; here they were derived and are
    // wrong, so the ones this file really owns cannot be enumerated at all. Both leave the note
    // as the smallest unit still reasonable about — but a reader who saw one arm covering two
    // cases would have to guess which argument it rested on.
    case KeyMisfiledInFile(noteId, _, _) => OrphanShelter.WholeNote(noteId)

    // AND THE SAME AGAIN FOR THE MILDER OBSERVATION, WHICH IS THE ONE WORTH PAUSING ON. This
    // note's cards ARE written, so it reads like a case with nothing to shelter — the position
    // `MarkerNotOnHeading` takes below, on the grounds that the tool can see exactly what its
    // file produces. That is the one thing it cannot see here: a heading it does not read is a
    // card it cannot enumerate, and if that card is already in Anki, inference would call it an
    // orphan and SUSPEND it over a heading nobody deleted.
    case HeadingUnreadInFile(noteId, _, _) => OrphanShelter.WholeNote(noteId)

    // NOTHING TO SHELTER — but for two different reasons, and the distinction matters enough
    // to write out. _The comment here used to say all of these were files with no usable `id`.
    // That was false of the first one, whose own test gives the file `id: n1`; the conclusion
    // was right and the stated reason was not. Corrected 2026-08-24._

    // KNOWN TO OWN NOTHING NOW. This file parses, and the tool can see exactly what it
    // produces: no cards. If it once had marked headings and they were removed, the notes they
    // made ARE deleted and SHOULD be flagged — sheltering them would hide a real orphan.
    case MarkerNotOnHeading(_, _) => OrphanShelter.Nothing

    // KNOWN TO OWN NOTHING NOW, for the same reason as its neighbour above. A misspelled tag is
    // not a marker, so the file's cards are exactly the ones its HEADINGS produce, and the tool
    // can see those. Sheltering on the strength of a tag that names nothing would hide a real
    // orphan behind a typo.
    case MarkerMisspelled(_, _) => OrphanShelter.Nothing

    // NOTHING TO SHELTER, AND FOR A THIRD REASON. There is no key here — the anchor that would
    // have made one is missing — but the note this block sits in is otherwise sound and its
    // other cards are all accounted for. Widening to the whole note would suppress every one of
    // them because one paragraph lacks a keystroke, which is the opposite of proportionate. If
    // that block once HAD an anchor and it was deleted, its card genuinely is an orphan and
    // should be flagged: sheltering it would hide a real one.
    case ClozeBlockUnanchored(_, _, _) => OrphanShelter.Nothing

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
