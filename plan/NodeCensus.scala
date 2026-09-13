package obsidiananki.plan

import obsidiananki.model.{CardKey, CardPath, NoteId}

/** WHICH NODES OF A NOTE THE VAULT STILL HOLDS — a fact the move survey could not see, and the only
  * witness of survival that can see a concept which kept nothing but prose.
  *
  * ══ ONE WITNESS OF TWO, SINCE 2026-09-13 ══
  *
  * This type was built as THE discriminator, and it is now the first of two: the ruling of
  * 2026-09-13 (`docs/design/IDENTITY-DECISION-SHEET.md`) also counts a concept as surviving when the
  * same survey corroborated a card onto it — see `plan/MoveEvidence.scala`'s `SubjectSurvival`. That
  * second witness exists because the question this type answers is about ONE NOTE'S TREE: a concept
  * that left for another note, or that was re-nested under a new ancestor, is absent from the node
  * paths of the note it left, and answering "gone" about it is truthful and not enough. Nothing
  * about this type changed; what changed is that its answer is no longer the whole of the check.
  *
  * ══ THE QUESTION THIS ANSWERS, AND WHY NOTHING ELSE COULD ══
  *
  * `plan/MoveEvidence.scala` reads a pairing off two card keys and the fields on either side. For
  * a concept-descriptor card that is not enough to tell two different events apart, and the two
  * have opposite ruled outcomes:
  *
  *   - `# Kafka` was RELABELLED `# RabbitMQ` and its descriptors came with it. Same cards; history
  *     follows (the ruling of 2026-09-12, Decision 2 of `docs/design/IDENTITY-DECISION-SHEET.md`).
  *   - `## Definition` was RE-PARENTED from under `# Kafka` to under `# NATS`, and `# Kafka` goes
  *     on existing. A concept-descriptor card's parent is constitutive, so that is a DIFFERENT
  *     card and history must never follow (standing ruling R2, deck scenario S24).
  *
  * The keys look the same in both: one segment inside the name window changed. What separates them
  * is whether THE OLD CONCEPT GOES ON EXISTING — and the half of that answer this type holds is a
  * fact about the vault's node tree, not about any card. The survey's two inputs cannot carry it. A
  * concept heading that kept some cards shows up there only as the prefix of somebody else's key,
  * and a concept heading that kept only PROSE shows up nowhere at all — which is deck scenario
  * S24C, and the reason this type cannot be replaced by the witness of 2026-09-13.
  *
  * ══ WHAT A NODE IS HERE ══
  *
  * A node is a place in a note a card could hang off, named by its canonical segments outermost
  * first — the same reading [[MoveEvidence.segmentsOf]] takes of a key path. Two sources fill it,
  * and neither covers the other:
  *
  *   - EVERY HEADING THE TOOL READS AS A HEADING, marked or not, canonicalised exactly as a key
  *     segment is. This is the half that sees a concept which kept only prose.
  *   - EVERY PROPER PREFIX OF EVERY KEY THE SCAN ACCOUNTS FOR. This is the half that sees a TABLE
  *     ROW, whose node is a row's first cell and is no heading at all — `## Cost / benefit`'s pair
  *     cards key as `…/{row concept}/{column header}`, so the row node exists only as their prefix.
  *
  * ══ WHY THE ANSWER IS THREE-VALUED ══
  *
  * [[MoveFinding.Incomparable]] set the precedent this obeys: a run that could not look must never
  * be mistaken for a run that looked and found nothing. "The old concept survives NOWHERE" is a
  * claim about the whole vault, so it may only be made from a census that actually read one.
  */
final case class NodeCensus private (
    nodes: Map[NoteId, Set[Vector[String]]],
    unreadableVault: Option[String],
    unreadableNotes: Map[NoteId, String],
):

  /** The nodes this note still holds, or why they cannot be known.
    *
    * THE VAULT-WIDE REASON IS CHECKED FIRST, and the order is the honest one rather than the
    * cheap one: a file nobody could read may own any id at all, so a note this run appears to
    * have surveyed perfectly may be missing everything that file held.
    *
    * AN UNKNOWN NOTE IS SURVEYED AND EMPTY, which is the one answer here that looks like a
    * default and is not. On a scan with nothing unreadable, an id that appears nowhere is a file
    * that was deleted or re-keyed — and "this note holds no nodes" is then a finding the run is
    * entitled to make. It is also the finding the relabel-in-place case rests on, so answering
    * `Unsurveyable` to be safe would refuse every cross-note rename instead.
    */
  def nodesOf(noteId: NoteId): NodeCensus.Answer =
    unreadableVault.orElse(unreadableNotes.get(noteId)) match
      case Some(reason) => NodeCensus.Answer.Unsurveyable(reason)
      case None         => NodeCensus.Answer.Surveyed(nodes.getOrElse(noteId, Set.empty))

object NodeCensus:

  /** WHAT THE WALKER SAW: per note, one entry per heading it read, as that heading's whole chain
    * of canonical segments.
    *
    * PREFIX-CLOSED ON ARRIVAL, which is why [[of]] takes no closure over it. Every heading in the
    * tree gets its own entry, so the chain of any heading's ancestor is itself an entry.
    */
  type Outlines = Map[NoteId, Vector[Vector[String]]]

  /** Either this note's nodes, or the reason nobody may conclude anything from their absence. */
  enum Answer:
    case Surveyed(nodes: Set[Vector[String]])
    case Unsurveyable(reason: String)

  /** ONE PRODUCER: the walker's heading outlines, plus the keys the scan accounts for.
    *
    * TAKES THE WHOLE SCAN RATHER THAN A LIST OF KEYS, because "accounted for" is already a
    * decided question — built keys plus the keys of cards that failed to build, which are present
    * and broken rather than gone. Restating that here would be a second answer to it.
    *
    * ═══ WHICH FAILURES SILENCE IT IS ASKED OF [[BuildFailure.shelters]], NOT LISTED HERE ═══
    *
    * That method already answers the one question "what does this failure protect from being read
    * as a deletion", and the answer is the same question this needs: a failure that shelters a
    * WHOLE NOTE does so precisely because that note's keys cannot be enumerated or cannot be
    * trusted — which is exactly the condition under which its node tree cannot be either. A
    * failure that shelters the whole vault silences every note for the same reason
    * `Planner.plan`'s orphan branch gives: unreadable frontmatter might have carried any id.
    *
    * Listing the cases here instead would put a fourth consumer beside that method's three, free
    * to forget a case added later — and forgetting one here means asserting "the old concept
    * survives nowhere" about a vault that was not read.
    *
    * ═══ WHY THE REASONS NAME THE STATE RATHER THAN THE FILE ═══
    *
    * `MarkedHeadings.CouldNotLook` set this precedent: what a consumer must know is that no claim
    * may be made, not which parser failed. The run already reports every build failure by name in
    * its own block, so repeating one here would be a third place a failure is put into words and
    * therefore a third place it can drift.
    */
  def of(scan: VaultScan, outlines: Outlines): NodeCensus =
    val unreadableFiles = scan.failures.count(_.shelters == OrphanShelter.Unknowable)

    val unreadableVault = Option.when(unreadableFiles > 0)(
      s"$unreadableFiles file(s) could not be read at all, and an unreadable file may hold nodes " +
        "belonging to any note — see this run's build failures"
    )

    val unreadableNotes: Map[NoteId, String] = scan.failures.flatMap(f =>
      f.shelters match
        case OrphanShelter.WholeNote(noteId) =>
          Vector(
            noteId ->
              (s"note ${noteId.value}'s heading tree could not be derived, or is not the tree its " +
                "author wrote — see this run's build failures")
          )
        case OrphanShelter.Nothing | OrphanShelter.OneKey(_) | OrphanShelter.Unknowable =>
          Vector.empty
    ).toMap

    // ── THE NODES A KEY CAN SHOW: everything ABOVE a card the vault accounts for ──────────
    //
    // PROPER PREFIXES, so a card's own position is not made a node by the existence of that
    // card. What the survey asks this type is whether a PARENT survives; whether the stranded
    // card's own key came back is a question it answered before it got here.
    //
    // BUILT AND FAILED-BUT-KEYED ALIKE, exactly as orphan inference treats them: a card that
    // would not build is present and broken, and reading its absence as a vanished node would
    // say a concept had gone because one of its cards has a malformed table.
    val fromKeys: Map[NoteId, Set[Vector[String]]] =
      (scan.builtKeys ++ scan.failedKeys).toVector
        .flatMap(key => properPrefixesOf(key).map(key.noteId -> _))
        .groupMap(_._1)(_._2)
        .view
        .mapValues(_.toSet)
        .toMap

    val surveyed: Map[NoteId, Set[Vector[String]]] =
      (outlines.keySet ++ fromKeys.keySet).view.map { noteId =>
        noteId ->
          (outlines.getOrElse(noteId, Vector.empty).toSet ++ fromKeys.getOrElse(noteId, Set.empty))
      }.toMap

    NodeCensus(surveyed, unreadableVault, unreadableNotes)

  /** Every non-empty PROPER prefix of a key's node path, outermost first.
    *
    * ONLY A HEADING CHAIN HAS ONE. A frontmatter property and the whole-note card anchor at the
    * NOTE rather than inside a chain, and a block deliberately records no heading above it
    * (`model/CardKey.scala` — carrying one would re-key the card when its paragraph moved). None
    * of the three can therefore be a node's parent, and the empty prefix — the note's own root —
    * is not a place this census speaks about.
    *
    * MATCHED EXHAUSTIVELY so that a fifth kind of anchor has to say whether it nests.
    */
  private def properPrefixesOf(key: CardKey): Vector[Vector[String]] = key.path match
    case CardPath.Headings(headings) =>
      val segments = headings.segments.toVector.map(_.value)
      (1 until segments.length).toVector.map(segments.take)
    case CardPath.Property(_) | CardPath.Note | CardPath.Block(_) => Vector.empty
