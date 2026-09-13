package obsidiananki.deck

import cats.data.NonEmptyVector
import obsidiananki.anki.{AnkiNoteId, DeckPath, ObservedNote}
import obsidiananki.extract.{DeckShape, VaultFile, VaultIndex, VaultWalker}
import obsidiananki.model.*
import obsidiananki.plan.*

/** THE SCENARIO DECK — every edit-event of the card model, run end to end WITHOUT ANKI.
  *
  * ══ WHAT ONE SCENARIO IS ══
  *
  * A before-fixture vault, an edit (expressed as an after-fixture vault; multi-step scenarios add
  * a third state), and one run of the real machinery over the transition:
  *
  *   1. the BEFORE state is extracted by the production pipeline ([[VaultWalker.scan]]) and
  *      turned into a synthesized Anki collection — each spec's fields BYTE-COPIED into an
  *      [[ObservedNote]], because the move-evidence floor is byte-identity and a normalising fake
  *      would silently pass scenarios the floor should decide;
  *   2. the AFTER state is extracted the same way;
  *   3. the evidence-free DIFF is classified (create / update / retype / flag / unflag /
  *      non-event / plan-refused), using the same building blocks the Planner uses —
  *      [[Planner.checkUnique]], [[Planner.contentHash]], [[VaultAccounting]] — so the rules are
  *      composed here, never restated;
  *   4. the stranded and unclaimed populations are computed BY THE SAME RULE the move-build
  *      Planner uses (`plan/Planner.scala`, the `isFlaggedOrphan || canInferOrphans` filter and
  *      the `byKey` complement), and handed to [[MoveEvidence.survey]] verbatim;
  *   5. the policy seam ([[MovePolicy]]) decides, per finding, what is done and what the run
  *      says — THE ONLY STEP A WORLD MAY CHANGE.
  *
  * Events that generate no finding (a body edit, a file rename that re-keys nothing, a deck
  * move…) are outside the seam by construction and identical across worlds; the DIFF section is
  * what keeps them visible.
  *
  * ══ DETERMINISM ══
  *
  * Fixture files are read sorted, synthetic note ids are assigned in sorted key order, every list
  * is sorted before rendering, and the survey itself sorts — so two runs over one deck produce
  * byte-identical transcripts, and two WORLDS' transcripts differ only where their policies do.
  */
object WorldDeck:

  val deckRoot: DeckPath = DeckPath(NonEmptyVector.one("Obsidian"))

  // ─────────────────────────────────────────────────────────────────── scenarios ──

  /** One scenario: which fixture directory, which states (in run order), what the author did.
    *
    * `fixture` is a directory name under `deck/fixtures/`; two scenarios may share one (S41
    * re-reads S33's edit from the row card's side, S42 re-reads S34's).
    *
    * `perturb` mutates the SYNTHESIZED observed cards, and only those — for the scenarios whose
    * observed state must not be what any spec produces (S73's hand-edited field, S74's stock
    * note type). Everything else passes the collection through untouched.
    */
  final case class Scenario(
      id: String,
      title: String,
      kind: String,
      edit: String,
      fixture: String,
      states: Vector[String],
      perturb: Vector[ObservedCard] => Vector[ObservedCard],
  )

  private def s(id: String, title: String, kind: String, edit: String): Scenario =
    Scenario(id, title, kind, edit, id, Vector("before", "after"), identity)

  private def shared(id: String, fixture: String, title: String, kind: String, edit: String): Scenario =
    Scenario(id, title, kind, edit, fixture, Vector("before", "after"), identity)

  private def threeStep(id: String, title: String, kind: String, edit: String): Scenario =
    Scenario(id, title, kind, edit, id, Vector("before", "after", "after2"), identity)

  private def perturbed(
      id: String,
      title: String,
      kind: String,
      edit: String,
      perturb: Vector[ObservedCard] => Vector[ObservedCard],
  ): Scenario = Scenario(id, title, kind, edit, id, Vector("before", "after"), perturb)

  /** S73: the observed note's Back bytes were changed by hand in Anki's editor after the last
    * sync — the measured round-trip covers the API path only, never a human edit.
    */
  private def handEditedBack(cards: Vector[ObservedCard]): Vector[ObservedCard] =
    cards.map(c =>
      c.copy(note =
        c.note.copy(fields = c.note.fields.map((n, v) =>
          if n == Marker.BasicFields.Back then n -> (v + " (reworded by hand in Anki)") else n -> v
        ))
      )
    )

  /** S74: the note was synced before this tool took note types of its own and still sits on
    * stock `Basic`, which this tool declares no roles for.
    */
  private def legacyNoteType(cards: Vector[ObservedCard]): Vector[ObservedCard] =
    cards.map(c => c.copy(note = c.note.copy(noteType = "Basic")))

  val plainHeading      = "plain heading card"
  val conceptDescriptor = "concept-descriptor heading card"
  val tablePair         = "table pair card"
  val tableRow          = "table row card"
  val clozeSection      = "cloze section card"
  val clozeBlock        = "cloze block card"
  val sequence          = "sequence card"
  val wholeNote         = "whole-note card"
  val relation          = "relation card"
  val crossCutting      = "cross-cutting"

  val scenarios: Vector[Scenario] = Vector(
    s("S01", "reword a plain heading in place", plainHeading, "'## Temporal coupling' becomes '## Temporal coupling of services'; the body is untouched"),
    s("S02", "replace the subject in a plain heading", plainHeading, "'## Kafka delivery' becomes '## NATS delivery'; the generic body is byte-identical — the same observable as S01"),
    s("S03", "reword an unmarked ancestor", plainHeading, "the ancestor '## Messaging patterns' becomes '## Patterns of messaging'; the marked heading and body are untouched"),
    s("S04", "re-parent a marked heading in the same note", plainHeading, "the marked H2 moves to H3 under '## Notes'"),
    s("S05", "move a marked section to another note, verbatim", plainHeading, "the heading and body are cut from Coupling.md into Inbox.md, chain verbatim"),
    s("S06", "change the heading level with the chain unchanged", plainHeading, "'##' becomes '###'; the ancestor chain does not change"),
    s("S07", "edit the body in place", plainHeading, "one sentence is appended to the body"),
    s("S08", "move AND edit the body in one commit", plainHeading, "S04's re-parent, and the body reworded, in one edit"),
    s("S09", "reword the heading AND re-parent it", plainHeading, "the heading is reworded and moved under '## Notes'; the body is untouched"),
    s("S10", "bold one word of the marked heading", plainHeading, "'Temporal' is bolded; nothing else changes"),
    s("S11", "bold one word AND re-parent", plainHeading, "the heading is bolded and moved under '## Notes'; the body is untouched"),
    s("S12", "delete the marked section", plainHeading, "the marked heading and its body are removed"),
    threeStep("S13", "delete, then restore verbatim", plainHeading, "run 1: the section is deleted; run 2: it is restored byte-identically"),
    threeStep("S14", "delete, park, then recreate reworded", plainHeading, "run 1: the section is deleted and the orphan parks; run 2: it is recreated with a reworded heading, body verbatim"),
    s("S15", "split one section into two", plainHeading, "the original heading keeps paragraph 1; a new marked sibling gets paragraph 2"),
    shared("S15b", "S15b", "split, both halves reworded", plainHeading, "the section is split and BOTH headings are new wordings"),
    s("S16", "merge two sections into one", plainHeading, "two marked siblings become one new marked heading whose body is the concatenation"),
    shared("S16b", "S16b", "merge two byte-identical sections", plainHeading, "the two siblings' bodies are byte-identical and the merged body equals both"),
    s("S17", "copy-paste creates a duplicate key", plainHeading, "the marked section is duplicated under the same ancestor: two sources, one key"),
    s("S18", "retag 2way to 1way in place", plainHeading, "only the marker changes"),
    s("S19", "retag AND re-parent in one commit", plainHeading, "the marker changes 2way to 1way and the heading moves under '## Notes'"),
    s("S20", "reword a descriptor slot", conceptDescriptor, "'## Definition' becomes '## Formal definition' under '# Kafka'; the body is untouched"),
    s("S21", "replace a descriptor slot", conceptDescriptor, "'## Definition' becomes '## Contrast' under '# Kafka'; the body is byte-identical — the same observable as S20"),
    s("S22", "reword the concept in place", conceptDescriptor, "'# Kafka' becomes '# Apache Kafka'; both descriptor sections are untouched"),
    s("S23", "swap the concept's subject in place", conceptDescriptor, "'# Kafka' becomes '# RabbitMQ'; bodies untouched — the same observable as S22"),
    s("S24", "re-parent a descriptor under a different concept", conceptDescriptor, "'## Definition' (body verbatim) moves from under '# Kafka' to under '# NATS'"),
    // THE THREE CONSTRUCTED BY THE FINAL ADVERSARIAL REVIEW, 2026-09-13, and named as it named
    // them. Each varies ONE thing about S24: where the concept the descriptor left ends up. The
    // review's point was that S24 alone cannot tell a survival check reading a NOTE'S OWN node
    // tree from one reading the whole vault's evidence — the ruling of 2026-09-13 in
    // `docs/design/IDENTITY-DECISION-SHEET.md` is what these pin.
    s("S24A", "re-parent a descriptor while the concept moves to ANOTHER NOTE", conceptDescriptor, "'## Definition' moves under '# NATS' in Messaging.md; '# Kafka' and '## Cost' move verbatim into Queues.md"),
    s("S24B", "re-parent a descriptor while the concept is RE-NESTED in the same note", conceptDescriptor, "'## Definition' moves under '# NATS'; '# Kafka' becomes '## Kafka' under a new '# Archive'"),
    s("S24C", "re-parent a descriptor while the concept keeps only PROSE", conceptDescriptor, "'## Definition' moves under '# NATS'; '# Kafka' stays but holds only prose, '## Cost' is deleted"),
    s("S25", "move a concept and its whole subtree to another note", conceptDescriptor, "'# Kafka' and both descriptor sections move verbatim into Queues.md"),
    s("S26", "rename the file of an ancestorless descriptor", conceptDescriptor, "Kafka.md becomes NATS.md; the id and the body are untouched"),
    s("S27", "rename the file AND change the id", conceptDescriptor, "Kafka.md becomes NATS.md and 'id: k1' becomes 'id: k2' in one commit"),
    s("S28", "retag cdd/2way to cdd/3way in place", conceptDescriptor, "only the marker changes"),
    shared("S28b", "S28b", "retag cdd/3way AND re-parent", conceptDescriptor, "the marker changes and the descriptor moves under '# NATS' in one commit"),
    s("S29", "swap the two table rows", tablePair, "the Queue and Cache rows change places; nothing else changes"),
    s("S30", "edit one cell value", tablePair, "'Delay' becomes 'Delay and duplication'"),
    s("S31", "rename a column header, values distinct", tablePair, "'Benefit' becomes 'Advantage'; the two rows hold distinct values in that column"),
    s("S32", "rename a column header, values identical", tablePair, "'Benefit' becomes 'Advantage'; both rows hold the byte-identical value in that column"),
    s("S33", "replace a row's subject, values byte-identical", tablePair, "the Queue row is deleted and a Stream row with the same values added"),
    s("S34", "fix a typo in a row concept", tablePair, "'Qeue' becomes 'Queue'; values untouched — the same observable as S33"),
    s("S35", "add a column", tablePair, "a Latency column with values is added"),
    s("S36", "delete a column", tablePair, "the Cost column is removed"),
    shared("S36b", "S36b", "blank one value cell", tablePair, "the Queue row's Cost cell is emptied"),
    s("S37", "reword the marked table heading", tablePair, "'## Cost / benefit' becomes '## Trade-offs'; the table is untouched"),
    shared("S37b", "S37b", "move the whole table section to another note", tablePair, "the section moves verbatim into Notes2.md"),
    s("S38", "rename the first header cell", tablePair, "'Pattern' becomes 'Approach'"),
    shared("S38b", "S38b", "rename the first header AND move the section", tablePair, "'Pattern' becomes 'Approach' and the section moves into Notes2.md, one commit"),
    s("S39", "withdraw the cell cards", tablePair, "the marker becomes #flashcard/table/rows"),
    shared("S39b", "S39b", "change the cell direction alone", tablePair, "the marker becomes #flashcard/table/1way"),
    s("S40", "an unrelated failure inside the table section", tablePair, "one value cell gains an embed: ![[x.png]]"),
    s("S41", "replace a row's subject on a row-only table, values byte-identical", tableRow, "the marker requests row cards only (#flashcard/table/rows); the Queue row is deleted and a Stream row with the same values added — the row card renders the concept into its Substance"),
    s("S42", "fix a typo in a row concept on a row-only table", tableRow, "the marker requests row cards only; 'Qeue' becomes 'Queue', values untouched — the same observable as S41"),
    s("S43", "edit one value, row card view", tableRow, "'Speed' becomes 'High speed' — the pair card and the row card both update in place"),
    s("S44", "reword a cloze heading in place", clozeSection, "'## The three layers' becomes '## Skin layers'; the passage is untouched"),
    shared("S44b", "S44b", "move the cloze section to another note", clozeSection, "the section moves verbatim into Reference.md"),
    s("S45", "edit cloze prose, deletions untouched", clozeSection, "words are added around the highlights"),
    s("S46", "move the cloze section AND edit its prose", clozeSection, "the section is re-parented and its prose edited, one commit"),
    s("S47", "add an unlabelled highlight before the existing ones", clozeSection, "a new sentence with an unlabelled deletion is prepended"),
    s("S48", "two unlabelled highlights with identical text", clozeSection, "a second unlabelled ==<<epidermis>>== appears in the section"),
    s("S49", "remove the cloze marker over an anchored block", clozeSection, "the #flashcard/cloze marker comes off the heading; the block keeps its ^blockid"),
    s("S50", "move an anchored block under a different heading", clozeBlock, "the ^forearm paragraph moves from '## Bones' to '## Joints', same note"),
    s("S51", "move an anchored block to another note", clozeBlock, "the paragraph, ^forearm included, moves into Skeleton.md"),
    s("S52", "rename the block anchor", clozeBlock, "'^forearm' becomes '^forearm-bones'; the text is untouched"),
    s("S53", "delete the block anchor, keep the deletions", clozeBlock, "the '^forearm' id is removed from the paragraph"),
    s("S54", "move the block into the cloze section", clozeBlock, "the paragraph moves under the #flashcard/cloze heading and loses its anchor"),
    s("S55", "reorder sequence items", sequence, "two list items change places"),
    s("S56", "reword the sequence title in place", sequence, "'## Deploy pipeline' becomes '## Deployment pipeline'; the list is untouched"),
    shared("S56b", "S56b", "replace the subject in the sequence title", sequence, "'## Deploy pipeline' becomes '## Release train'; the list is byte-identical — the same observable as S56"),
    s("S57", "switch the sequence source in place", sequence, "#flashcard/sequence becomes #flashcard/sequence/headers on a heading that has both a list and subheadings"),
    shared("S57b", "S57b", "change the reveal order AND move", sequence, "the marker gains /dfs — 'recursive' alone already means breadth-first, so /dfs is the suffix that changes the Reveal field — and the heading is re-parented, one commit"),
    s("S58", "rename a whole-note card's file", wholeNote, "Essential Numbers.md becomes Core Numbers.md; the id is untouched"),
    s("S59", "change only the id", wholeNote, "'id: n5' becomes 'id: n5x'; the file name and body are untouched"),
    s("S60", "change the id AND rename the file", wholeNote, "both in one commit"),
    s("S61", "give the whole-note card a first heading", wholeNote, "the frontmatter marker moves onto a new '# Essential Numbers' heading"),
    s("S62", "delete the id from the frontmatter", wholeNote, "the 'id:' line is removed; the marked heading stays"),
    s("S63", "rename a relation card's file", relation, "Function Space.md becomes Exponential Object.md; the property is untouched"),
    s("S64", "move the relation declaration to another note", relation, "the property and its rule move from Function Space.md into HomFunctor.md"),
    s("S65", "rename the predicate", relation, "'special-case-of' becomes 'instance-of' in the frontmatter and in the rule; the value is untouched"),
    s("S66", "retidy the property's capitalisation", relation, "'special-case-of:' becomes 'Special-Case-Of:'; the rule and value are untouched"),
    s("S67", "add a second value", relation, "the property's value becomes a two-element list"),
    s("S68", "remove the rule from Properties-to-Flashcards", relation, "the declarations block is deleted; the property stays in the frontmatter"),
    s("S69", "a second note declares the same reversible relation", relation, "Currying.md arrives declaring the same (predicate, object) as 2way"),
    s("S70", "basename coincidence across folders", relation, "A/Kafka.md (id kx) is deleted; B/Kafka.md (id ky), same relation and value, is new this run"),
    s("S71", "re-express the relation as a marked heading", relation, "the frontmatter property becomes '## Special-Case-Of #flashcard/cdd/2way'"),
    s("S72", "move a file between folders", crossCutting, "Coupling.md moves from Topics/ to Patterns/; nothing inside it changes"),
    perturbed("S73", "S04's move against a hand-edited note", crossCutting, "the same re-parent as S04, but the observed note's Back was edited by hand in Anki after the last sync", handEditedBack),
    perturbed("S74", "S04's move against a legacy note type", crossCutting, "the same re-parent as S04, but the observed note sits on stock 'Basic', which this tool does not declare", legacyNoteType),
  )

  // ─────────────────────────────────────────────────────── loading and synthesis ──

  def loadState(fixturesRoot: java.nio.file.Path, fixture: String, state: String): Vector[VaultFile] =
    import scala.jdk.CollectionConverters.*
    val dir = fixturesRoot.resolve(fixture).resolve(state)
    if !java.nio.file.Files.isDirectory(dir) then
      sys.error(s"fixture state '$fixture/$state' does not exist at $dir")
    java.nio.file.Files
      .walk(dir)
      .iterator
      .asScala
      .filter(_.toString.endsWith(".md"))
      .toVector
      .map(p => VaultFile(dir.relativize(p).toString, java.nio.file.Files.readString(p)))
      .sortBy(_.relativePath)

  def indexOf(files: Vector[VaultFile]): VaultIndex =
    VaultWalker.scan(files, deckRoot, DeckShape.FoldersOnly)

  private def keyOrder(k: CardKey): (String, String) = (k.noteId.value, k.path.render)

  /** The Anki collection the last successful sync over this state would have left behind.
    *
    * FIELDS ARE BYTE-COPIED from `CardSpec.fields` (`model/CardSpec.scala`), never re-rendered:
    * the floor's byte-identity is the measured round-trip contract, and a normalising fake would
    * silently pass scenarios the floor should decide. The `sha::` tag is the one production
    * writes, so the diff's "nothing to do" is decided by the same comparison.
    */
  def synthesize(index: VaultIndex, firstId: Long): Vector[ObservedCard] =
    val scan = index.scan
    if scan.failures.nonEmpty then
      sys.error(
        s"a BEFORE state must be a valid synced vault, and this one has failures: " +
          scan.failures.map(failureText).mkString("; ")
      )
    val duplicates = Planner.checkUnique(scan.specs)
    if duplicates.nonEmpty then
      sys.error(s"a BEFORE state derives duplicate keys: ${duplicates.map(_.describe).mkString("; ")}")
    scan.specs.sortBy(s => keyOrder(s.key)).zipWithIndex.map { (s, i) =>
      ObservedCard(
        s.key,
        ObservedNote(
          AnkiNoteId(firstId + i),
          s.spec.noteTypeName,
          s.spec.fields,
          Vector(OwnedTag.sha(Planner.contentHash(s.spec)).value),
        ),
        Some(index.deckOf(deckRoot)(s.key)),
      )
    }

  // ─────────────────────────────────────────────────────────── one run of the deck ──

  /** The evidence-free classification of one card, or of one event that is not about one card. */
  enum DiffEntry:
    case Created(key: CardKey)
    case Updated(key: CardKey)
    case Retyped(key: CardKey, from: String, to: String)
    case NonEvent(key: CardKey)
    case Flagged(key: CardKey)
    case Parked(key: CardKey)
    case Unflagged(key: CardKey)
    case Sheltered(key: CardKey)
    case NotComputed(key: CardKey)
    case Failure(text: String)
    case PlanRefused(errors: Vector[String])

    def render: String = this match
      case Created(k)        => s"create      '${k.path.render}' in ${k.noteId.value}"
      case Updated(k)        => s"update      '${k.path.render}' in ${k.noteId.value}"
      case Retyped(k, f, t)  => s"retype      '${k.path.render}' in ${k.noteId.value} — $f → $t (deferred unless asked)"
      case NonEvent(k)       => s"non-event   '${k.path.render}' in ${k.noteId.value}"
      case Flagged(k)        => s"flag        '${k.path.render}' in ${k.noteId.value} — suspended, never deleted"
      case Parked(k)         => s"parked      '${k.path.render}' in ${k.noteId.value} — already flagged by an earlier run"
      case Unflagged(k)      => s"unflag      '${k.path.render}' in ${k.noteId.value} — the key is back"
      case Sheltered(k)      => s"sheltered   '${k.path.render}' in ${k.noteId.value} — something above it failed to build, so absence proves nothing"
      case NotComputed(k)    => s"no-verdict  '${k.path.render}' in ${k.noteId.value} — the scan was partial, so absence proves nothing"
      case Failure(t)        => s"refused     $t"
      case PlanRefused(errs) => ("PLAN REFUSED — nothing in this run was planned:" +: errs.map(e => s"  $e")).mkString("\n  ")

  final case class StepResult(
      diff: Vector[DiffEntry],
      findings: Vector[MoveFinding],
      decisions: Vector[(MoveFinding, PolicyDecision)],
      refused: Boolean,
      collectionAfter: Vector[ObservedCard],
  )

  /** One sync run: the AFTER scan against the synthesized collection, through the survey and the
    * policy seam. See the object docstring for the five stages and their provenance.
    */
  def runStep(
      index: VaultIndex,
      collection: Vector[ObservedCard],
      policy: MovePolicy,
      createdIdsFrom: Long,
  ): StepResult =
    val scan       = index.scan
    val duplicates = Planner.checkUnique(scan.specs)

    if duplicates.nonEmpty then
      // THE WHOLE PLAN IS REFUSED, exactly as `Planner.plan` refuses it today. R5 rules this
      // wrong (refuse only the cards involved); the deck shows the blast radius rather than
      // varying it, because the refusal happens before the survey and is therefore outside the
      // policy seam.
      StepResult(
        Vector(DiffEntry.PlanRefused(duplicates.map(_.describe))),
        Vector.empty,
        Vector.empty,
        refused = true,
        collectionAfter = collection,
      )
    else
      val byKey = collection.map(c => c.key -> c).toMap
      if byKey.size != collection.size then
        sys.error("the synthesized collection holds two notes with one key — a deck defect")

      val accounting = VaultAccounting.of(scan)

      val specDiff = scan.specs.sortBy(s => keyOrder(s.key)).map { s =>
        byKey.get(s.key) match
          case None => DiffEntry.Created(s.key)
          case Some(card) =>
            if card.note.noteType != s.spec.noteTypeName then
              DiffEntry.Retyped(s.key, card.note.noteType, s.spec.noteTypeName)
            else if !card.recordedSha.contains(Planner.contentHash(s.spec)) then
              DiffEntry.Updated(s.key)
            else DiffEntry.NonEvent(s.key)
      }

      val unflags = collection
        .filter(c => c.isFlaggedOrphan && scan.builtKeys.contains(c.key))
        .sortBy(c => keyOrder(c.key))
        .map(c => DiffEntry.Unflagged(c.key))

      val observedDiff = collection.sortBy(c => keyOrder(c.key)).flatMap { c =>
        if scan.builtKeys.contains(c.key) then Vector.empty
        else if accounting.accountsFor(c.key) then Vector(DiffEntry.Sheltered(c.key))
        else if c.isFlaggedOrphan then Vector(DiffEntry.Parked(c.key))
        else if scan.canInferOrphans then Vector(DiffEntry.Flagged(c.key))
        else Vector(DiffEntry.NotComputed(c.key))
      }

      val failureLines = scan.failures.map(f => DiffEntry.Failure(failureText(f))).sortBy(_.render)

      val diff = failureLines ++ specDiff ++ unflags ++ observedDiff

      // ── the stranded and unclaimed populations, by the move-build Planner's own rule ──
      // grep-hooks: `plan/Planner.scala`, `!accounting.accountsFor(card.key) &&
      // (card.isFlaggedOrphan || scan.canInferOrphans)`; unclaimed is the `Create` complement.
      val stranded  = collection.filter(c => !accounting.accountsFor(c.key) && (c.isFlaggedOrphan || scan.canInferOrphans))
      val unclaimed = scan.specs.filterNot(s => byKey.contains(s.key))
      // THE CENSUS COMES FROM THE SAME `index` AS `unclaimed`, which is the AFTER state — the
      // question it answers is whether a subject the collection remembers is still in the vault
      // the run is planning against.
      val findings = MoveEvidence.survey(stranded, unclaimed, index.census)

      val decisions = findings.map { f =>
        val d = policy.decide(f)
        d match
          case PolicyDecision.ApplyReassign(evidence, _) =>
            // A world may not mint a reassignment from any finding other than the one it
            // received — see `MovePolicy`. Enforced, not trusted.
            if !(f match { case c: MoveFinding.Corroborated => c == evidence; case _ => false }) then
              sys.error(
                s"policy '${policy.name}' returned apply-reassign carrying evidence about " +
                  s"'${evidence.candidate.path.render}' for a finding about " +
                  s"'${f.strandedNote.value}' — a reassignment may only be minted from the " +
                  "finding it answers"
              )
          case PolicyDecision.ParkAndReport(_) | PolicyDecision.Refuse(_) |
              PolicyDecision.QueueForReview(_) => ()
        f -> d
      }

      // ── evolve the collection for the next step, by the EVIDENCE-FREE diff alone ──
      // Reassignments are deliberately not applied here: no multi-step scenario in this deck
      // reassigns before its last run, and applying a policy's decision to the shared collection
      // would let one world's choice change another world's later evidence.
      val specByKey = scan.specs.map(s => s.key -> s).toMap
      val evolved = collection.flatMap { c =>
        specByKey.get(c.key) match
          case Some(s) =>
            Vector(
              c.copy(note =
                c.note.copy(
                  noteType = s.spec.noteTypeName,
                  fields = s.spec.fields,
                  tags = Vector(OwnedTag.sha(Planner.contentHash(s.spec)).value),
                )
              )
            )
          case None =>
            if c.isFlaggedOrphan then Vector(c)
            else if accounting.accountsFor(c.key) || !scan.canInferOrphans then Vector(c)
            else
              Vector(
                c.copy(note = c.note.copy(tags = c.note.tags :+ OwnedTag.orphaned(c.key).value))
              )
      }
      val created = scan.specs
        .filterNot(s => byKey.contains(s.key))
        .sortBy(s => keyOrder(s.key))
        .zipWithIndex
        .map { (s, i) =>
          ObservedCard(
            s.key,
            ObservedNote(
              AnkiNoteId(createdIdsFrom + i),
              s.spec.noteTypeName,
              s.spec.fields,
              Vector(OwnedTag.sha(Planner.contentHash(s.spec)).value),
            ),
            Some(index.deckOf(deckRoot)(s.key)),
          )
        }

      StepResult(diff, findings, decisions, refused = false, collectionAfter = evolved ++ created)

  /** What a [[BuildFailure]] says, for the transcript. `PlanError` has a `describe`;
    * `BuildFailure` does not, so the words live here.
    */
  def failureText(f: BuildFailure): String = f match
    case BuildFailure.KeyKnown(key, source, reason) =>
      s"'${key.path.render}' in ${key.noteId.value} at ${source.describe}: $reason"
    case BuildFailure.KeyUnderivableInFile(noteId, source, reason) =>
      s"note ${noteId.value} at ${source.describe}: $reason (its keys cannot be derived)"
    case BuildFailure.KeyMisfiledInFile(noteId, source, reason) =>
      s"note ${noteId.value} at ${source.describe}: $reason (its keys are not trustworthy)"
    case BuildFailure.HeadingUnreadInFile(noteId, source, reason) =>
      s"note ${noteId.value} at ${source.describe}: $reason"
    case BuildFailure.MarkerNotOnHeading(file, reason)   => s"$file: $reason"
    case BuildFailure.ClozeBlockUnanchored(file, line, reason) => s"$file:$line: $reason"
    case BuildFailure.MarkerMisspelled(file, reason)     => s"$file: $reason"
    case BuildFailure.MarkedWithoutNoteId(file, reason)  => s"$file: $reason"
    case BuildFailure.EdgeVocabularyUnusable(file, reason) => s"$file: $reason"
    case BuildFailure.MarkerUnknowable(file, reason)     => s"$file: $reason"
    case BuildFailure.FileUnreadable(file, reason)       => s"$file: $reason"

  // ─────────────────────────────────────────────────────────────────── transcripts ──

  final case class ScenarioRun(scenario: Scenario, steps: Vector[StepResult]):

    def rendered: String =
      val sb = new StringBuilder
      sb ++= s"SCENARIO ${scenario.id} — ${scenario.title}   [${scenario.kind}]\n"
      sb ++= s"EDIT\n  ${scenario.edit}\n"
      steps.zipWithIndex.foreach { (step, i) =>
        if steps.sizeIs > 1 then sb ++= s"RUN ${i + 1}\n"
        sb ++= "DIFF\n"
        if step.diff.isEmpty then sb ++= "  (nothing at all)\n"
        else step.diff.foreach(e => sb ++= s"  ${e.render}\n")
        sb ++= "EVIDENCE\n"
        if step.refused then sb ++= "  (not surveyed: the plan was refused before the survey could run)\n"
        else if step.findings.isEmpty then sb ++= "  (empty — no stranded note to explain)\n"
        else step.findings.foreach(f => sb ++= s"  ${f.describe}\n")
        sb ++= "POLICY\n"
        if step.decisions.isEmpty then sb ++= "  (no findings reached the seam)\n"
        else step.decisions.foreach((_, d) => sb ++= s"  ${d.label}: ${d.message}\n")
        sb ++= "LEDGER\n"
        val ledger = ledgerLines(step)
        if ledger.isEmpty then sb ++= "  (no card's standing changed)\n"
        else ledger.foreach(l => sb ++= s"  $l\n")
      }
      sb.result()

    private def ledgerLines(step: StepResult): Vector[String] =
      val reassignedTo   = step.decisions.collect { case (_, PolicyDecision.ApplyReassign(e, _)) => e.candidate }.toSet
      val reassignedFrom = step.decisions.collect { case (_, PolicyDecision.ApplyReassign(e, _)) => e.stranded }.toSet
      val refusedByPolicy = step.decisions.collect {
        case (f, PolicyDecision.Refuse(_)) => f.strandedNote
      }.toSet
      val queued = step.decisions.collect {
        case (f, PolicyDecision.QueueForReview(_)) => f.strandedNote
      }.toSet
      step.diff.flatMap {
        case DiffEntry.Created(k) =>
          if reassignedTo.contains(k) then
            Vector(s"history FOLLOWED onto '${k.path.render}' — the existing note was reassigned, no new card")
          else Vector(s"new card at ZERO: '${k.path.render}'")
        case DiffEntry.Updated(k)  => Vector(s"history intact in place: '${k.path.render}' (updated)")
        case DiffEntry.Retyped(k, _, _) => Vector(s"history intact in place: '${k.path.render}' (retype, deferred unless asked)")
        case DiffEntry.Flagged(k) =>
          if reassignedFrom.contains(k) then Vector.empty
          else Vector(s"history STRANDED on the suspended note: '${k.path.render}'")
        case DiffEntry.Parked(k) =>
          if reassignedFrom.contains(k) then Vector.empty
          else Vector(s"history still STRANDED (parked): '${k.path.render}'")
        case DiffEntry.Unflagged(k) => Vector(s"history restored to review: '${k.path.render}' (unflagged)")
        case DiffEntry.Sheltered(k) => Vector(s"history untouched, sheltered: '${k.path.render}'")
        case DiffEntry.Failure(t)   => Vector(s"refused, nothing written: $t")
        case DiffEntry.PlanRefused(_) => Vector("EVERY card refused: the whole plan was rejected over a duplicate key")
        case DiffEntry.NonEvent(_) | DiffEntry.NotComputed(_) => Vector.empty
      } ++
        step.decisions.collect {
          case (f, PolicyDecision.Refuse(_)) if refusedByPolicy.contains(f.strandedNote) =>
            s"refused by the policy: note ${f.strandedNote.value}"
          case (f, PolicyDecision.QueueForReview(_)) if queued.contains(f.strandedNote) =>
            s"queued for review: note ${f.strandedNote.value}"
        }

  def runScenario(
      fixturesRoot: java.nio.file.Path,
      scenario: Scenario,
      policy: MovePolicy,
  ): ScenarioRun =
    val beforeIndex = indexOf(loadState(fixturesRoot, scenario.fixture, scenario.states.head))
    val initial     = scenario.perturb(synthesize(beforeIndex, firstId = 1))
    val (steps, _) = scenario.states.tail.zipWithIndex.foldLeft((Vector.empty[StepResult], initial)) {
      case ((done, collection), (state, i)) =>
        val index = indexOf(loadState(fixturesRoot, scenario.fixture, state))
        val step  = runStep(index, collection, policy, createdIdsFrom = 100L * (i + 1) + 1)
        (done :+ step, step.collectionAfter)
    }
    ScenarioRun(scenario, steps)

  /** The whole deck under one policy — one transcript, byte-comparable across worlds. */
  def transcript(fixturesRoot: java.nio.file.Path, policy: MovePolicy): String =
    val header = s"WORLD: ${policy.name}\n${"=" * 78}\n"
    header + scenarios.map(sc => runScenario(fixturesRoot, sc, policy).rendered).mkString("\n")

  /** The fixture root, found by walking up from a starting directory to the repository root —
    * the directory holding `project.scala`. Anchoring on the working directory is exactly what
    * `TestSources` warns against, so the tests pass their own compiled location instead; this
    * is the fallback for the command-line runner, which is documented to run from the repo.
    */
  def fixturesRootFrom(start: java.nio.file.Path): java.nio.file.Path =
    Iterator
      .iterate(start.toAbsolutePath)(_.getParent)
      .takeWhile(_ != null)
      .take(12)
      .find(dir => java.nio.file.Files.exists(dir.resolve("project.scala")))
      .map(_.resolve("deck").resolve("fixtures"))
      .getOrElse(sys.error(s"no project.scala above $start — run the deck from inside the repository"))

/** Print the baseline world's transcript. Run from the repository root:
  *
  * {{{ scala-cli run . --main-class obsidiananki.deck.runDeck }}}
  */
@main def runDeck(): Unit =
  val fixtures = WorldDeck.fixturesRootFrom(java.nio.file.Paths.get(""))
  print(WorldDeck.transcript(fixtures, BaselinePolicy))
