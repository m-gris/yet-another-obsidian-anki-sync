package obsidiananki.spike

import obsidiananki.model.HeadingSegment

/** SPIKE — deciding whether a vanished column and a new one are the SAME column, renamed.
  *
  * **BELONGS TO oas-hnr** — making a table row into one Anki note. This spike settles
  * whether a vanished column is a renamed one, and the design it feeds is `docs/design/TABLE-CARDS-REDESIGN.md`.
  *
  * ==NOT WIRED IN, AND IT REPORTS RATHER THAN ACTS==
  *
  * Nothing imports this, and nothing here changes a placement. Every function returns a
  * [[Finding]], which is DATA ABOUT EVIDENCE. Acting on it would move review history between cards
  * on the tool's own reading of an ambiguous situation, which is the line
  * `plan/SyncAction.scala:295-318` draws and `docs/findings/EVOLVABILITY.md` §4A restates: **anything fuzzy
  * may RANK candidates, never apply.**
  *
  * The names in [[Finding]] were chosen to keep that straight. A finding says *corroborated*, not
  * *renamed* — it describes what the evidence shows and stops there. A verdict that asserted the
  * rename would be a prescription wearing an observation's clothes.
  *
  * ==Why this is not fuzzy matching at all==
  *
  * `docs/findings/EVOLVABILITY.md` §4A makes the reframe this file depends on: **exact agreement on a
  * byte-identical field is evidence of the same class as the content hash, not a similarity
  * score.** There is no threshold here, no distance, and no number anybody has to justify — which
  * is the ground on which the proportional orphan guard was rejected (`docs/reference/CARD-MODEL.md:214`).
  * Two values either are the same bytes or they are not.
  *
  * ==Why the table gives better evidence than a heading ever could==
  *
  * §4A was written for a renamed HEADING, where the tool gets one witness: one orphaned note whose
  * body still matches. A renamed COLUMN is witnessed once per ROW, because the rename hits every
  * row of the table at once. Twelve rows agreeing is not a coincidence anyone needs a threshold to
  * dismiss.
  *
  * That section explicitly excludes tables, on the grounds that "a table's cells come from rows,
  * not headings". Under the row-as-note design in `docs/design/TABLE-CARDS-REDESIGN.md` that stops being
  * true, and this file is what the exclusion turns into once it does.
  *
  * ==Where the evidence comes from==
  *
  * [[RowPlacement]] already says which slots are [[SlotState.Stranded]] and which columns are
  * [[Provenance.Fresh]]. It does NOT carry values, deliberately — placement matches on keys and
  * must never read a value. The caller holds both: the observed note supplies what Anki still
  * records at a stranded slot, and the vault supplies what a fresh column now says. Assembling
  * those into [[RowEvidence]] is the caller's job, and keeping the two files uncoupled is what
  * stops a value ever reaching a placement decision.
  */

/** A cell's value, for comparison only.
  *
  * NON-EMPTY BY CONSTRUCTION, and that is a correctness requirement rather than tidiness. An empty
  * value would agree with every other empty value, so a table with blank cells would corroborate
  * every pairing equally and this file would confidently report nonsense. A row that cannot speak
  * must be silent rather than agreeable.
  */
opaque type CellValue = String

object CellValue:

  /** `None` for a value that is blank or whitespace, mirroring `model/CardSpec.scala`'s `Body`,
    * which refuses the same input for the same reason and is the precedent this follows.
    */
  def of(raw: String): Option[CellValue] =
    val trimmed = raw.trim
    if trimmed.isEmpty then None else Some(trimmed)

  extension (value: CellValue) def text: String = value

/** WHAT ONE ROW HAS TO SAY, and it is only ever about that row.
  *
  * `wasAt` is what Anki still records at each slot the vault no longer names — the stranded ones.
  * `nowAt` is what the vault says for each column that is new to this note — the fresh ones.
  *
  * A key cannot appear in both. If the vault still names a column and Anki already had it, that
  * column is [[Provenance.Kept]] and is no part of any rename question. Construction refuses it,
  * so no consumer has to wonder.
  */
opaque type RowEvidence = (Map[HeadingSegment, CellValue], Map[HeadingSegment, CellValue])

object RowEvidence:

  def of(
      wasAt: Map[HeadingSegment, CellValue],
      nowAt: Map[HeadingSegment, CellValue],
  ): Either[EvidenceError, RowEvidence] =
    // SORTED BEFORE TAKING ONE, so that a caller offering several contradictory keys is told about
    // the same one on every run. A `Set`'s head is whichever the hash table happens to yield.
    wasAt.keySet
      .intersect(nowAt.keySet)
      .toVector
      .sortBy(_.value)
      .headOption
      .map(EvidenceError.KeyBothStrandedAndFresh(_))
      .toLeft((wasAt, nowAt))

  extension (row: RowEvidence)
    def wasAt: Map[HeadingSegment, CellValue] = row._1
    def nowAt: Map[HeadingSegment, CellValue] = row._2

/** Refusals about the evidence a caller assembled, not about the table a person wrote.
  *
  * SEPARATE SUM, for the reason `ShapeError` is separate from `PlacementError` in
  * `spike/RowPlacement.scala`: the audience differs. This is a caller bug and its message is for
  * whoever is writing the code.
  */
enum EvidenceError:

  /** One key offered as both stranded and fresh in the same row, which describes a column that
    * simultaneously vanished and arrived. That is [[Provenance.Kept]] misreported.
    */
  case KeyBothStrandedAndFresh(key: HeadingSegment)

/** WHAT THE EVIDENCE SHOWS about one stranded column. Four outcomes, and each says a different
  * thing to the person reading the report.
  *
  * NONE OF THESE IS AN INSTRUCTION. See this file's header: the tool reports, a person decides.
  */
enum Finding:

  /** EXACTLY ONE fresh column agrees, no row dissents, and no other stranded column agrees with
    * that same fresh one. `rows` is how many rows actually corroborated, which is the number worth
    * printing — twelve rows agreeing reads very differently from one.
    */
  case Corroborated(from: HeadingSegment, to: HeadingSegment, rows: Int)

  /** SEVERAL fresh columns agree, so the evidence ranks nothing. Silence with the candidates
    * named, rather than a pick — `plan/SyncAction.scala` already rules that a list of maybes is
    * how a report stops being read, so a consumer must be able to tell this from
    * [[Corroborated]] by type rather than by reading a count.
    */
  case Ambiguous(from: HeadingSegment, candidates: Vector[HeadingSegment])

  /** ONE fresh column agrees, but another stranded column agrees with it too. The pairing is not
    * mutual, so it is not evidence of anything — two columns cannot both have become the same one.
    *
    * A CASE OF ITS OWN RATHER THAN FOLDED INTO [[Ambiguous]], because from this column's side
    * there IS only one candidate: reporting it as ambiguous with a single-element list would read
    * as a contradiction to anyone looking at the message.
    */
  case Contested(from: HeadingSegment, to: HeadingSegment, alsoClaimedBy: Vector[HeadingSegment])

  /** No fresh column's value ever agreed with this one's. The column was deleted, or it was
    * renamed AND its values were edited in the same commit, which nothing can distinguish.
    */
  case Unexplained(from: HeadingSegment)

object RenameEvidence:

  /** Survey a whole table's rows and say what the evidence shows about each stranded column.
    *
    * ==Why the whole table and not one row==
    *
    * A column rename hits every row at once, so a single row is one witness and the table is as
    * many as it has rows. Surveying row by row would throw away exactly the corroboration that
    * makes this stronger than the heading case §4A was written for.
    *
    * ==The rule, and why it needs no threshold==
    *
    * A pairing is corroborated when the two values are the same bytes in at least one row and
    * differ in NO row. Rows where either side has nothing to say are silent rather than
    * agreeable. Then a finding is [[Finding.Corroborated]] only when the pairing is MUTUALLY
    * unique — one candidate from each side.
    *
    * NO PROPORTION IS COMPUTED AND NONE SHOULD BE. "Nine of twelve rows agree" is a similarity
    * score wearing a fraction's clothes, and choosing where to cut it is the number nobody can
    * justify that `docs/reference/CARD-MODEL.md:214` already rejected. One dissenting row means the columns
    * hold different content, and that is a fact rather than a weak signal.
    */
  def survey(rows: Vector[RowEvidence]): Vector[Finding] =
    // SORTED, so a report reads the same on two runs over an unchanged table. These come out of
    // `Map` key sets, whose iteration order is a hash-table detail — the same reason
    // `extract/Cloze.scala` groups deletions in order of first appearance and says so.
    val stranded = rows.flatMap(_.wasAt.keys).distinct.sortBy(_.value)
    val fresh    = rows.flatMap(_.nowAt.keys).distinct.sortBy(_.value)

    /** Rows that agree, and rows that dissent, for one pairing.
      *
      * A ROW WITH NOTHING TO SAY IS SILENT RATHER THAN AGREEABLE. If either side is absent from a
      * row — the column was blank there, so [[CellValue.of]] refused it — that row counts as
      * neither, because counting it as agreement would let a table of blanks corroborate anything.
      */
    def tally(from: HeadingSegment, to: HeadingSegment): (Int, Int) =
      rows.foldLeft((0, 0)) { case ((agreed, dissented), row) =>
        (row.wasAt.get(from), row.nowAt.get(to)) match
          case (Some(was), Some(now)) =>
            if was == now then (agreed + 1, dissented) else (agreed, dissented + 1)
          case _ => (agreed, dissented)
      }

    // EXACT BYTES IN AT LEAST ONE ROW, AND DIFFERENT BYTES IN NONE. No proportion, no threshold:
    // one dissenting row means the two columns hold different content, which is a fact rather than
    // a weak signal. See this object's note on why a fraction here would be a similarity score.
    def corroborates(from: HeadingSegment, to: HeadingSegment): Boolean =
      val (agreed, dissented) = tally(from, to)
      dissented == 0 && agreed >= 1

    stranded.map { from =>
      fresh.filter(corroborates(from, _)) match
        case Vector() => Finding.Unexplained(from)

        case Vector(to) =>
          // MUTUAL UNIQUENESS, WHICH IS THE HALF THAT IS EASY TO FORGET. One candidate from this
          // column's side is not enough: if another stranded column also agrees with `to`, then
          // two columns would both have become the same one, which is not a thing that happened.
          stranded.filter(other => other != from && corroborates(other, to)) match
            case Vector()  => Finding.Corroborated(from, to, tally(from, to)._1)
            case claimants => Finding.Contested(from, to, claimants)

        case candidates => Finding.Ambiguous(from, candidates)
    }
