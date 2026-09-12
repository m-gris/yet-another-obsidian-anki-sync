package obsidiananki.plan

import obsidiananki.model.NoteId

/** WHICH NODES OF A NOTE THE VAULT STILL HOLDS — the one fact the move survey could not see.
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
  * is whether THE OLD CONCEPT'S PATH SURVIVES — and that is a fact about the vault's node tree, not
  * about any card. The survey's two inputs cannot carry it. A concept heading that kept some cards
  * shows up there only as the prefix of somebody else's key, and a concept heading that kept only
  * PROSE shows up nowhere at all.
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

  /** The nodes this note still holds, or why they cannot be known. */
  def nodesOf(noteId: NoteId): NodeCensus.Answer = ???

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
    */
  def of(scan: VaultScan, outlines: Outlines): NodeCensus = ???
