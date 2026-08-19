package obsidiananki.cli

import obsidiananki.extract.VaultIndex
import obsidiananki.plan.*

/** Rendering results for a human.
  *
  * Pure: every function here takes data and returns lines. Nothing prints, so what the tool
  * would say is testable without capturing stdout — and the caller decides where it goes.
  */
object Report:

  /** What the vault yields, with nothing hidden.
    *
    * FAILURES ARE REPORTED BEFORE SUCCESSES, and the counts are always shown even when zero.
    * A run that produced nothing must look different from a run that was never asked to do
    * anything, and both must look different from a run that failed quietly.
    */
  def inspect(index: VaultIndex, verbose: Boolean): Vector[String] =
    val scan  = index.scan
    val specs = scan.specs

    val header = Vector(
      s"cards:    ${specs.size}",
      s"failures: ${scan.failures.size}",
      s"scan:     ${if scan.canInferOrphans then "complete" else "PARTIAL — orphans cannot be computed"}",
    )

    val failureLines =
      if scan.failures.isEmpty then Vector.empty
      else "" +: "failures" +: scan.failures.map(describeFailure).map("  " + _)

    val duplicates = Planner.checkUnique(specs)
    val duplicateLines =
      if duplicates.isEmpty then Vector.empty
      else "" +: "DUPLICATE KEYS — nothing would be written" +: duplicates.map("  " + _.describe)

    val deckLines =
      if specs.isEmpty then Vector.empty
      else
        val byDeck = specs.groupBy(s => index.decks.get(s.key).map(_.render).getOrElse("?"))
        "" +: "decks" +: byDeck.toVector.sortBy(_._1).map((deck, ss) => f"  ${ss.size}%3d  $deck")

    val cardLines =
      if !verbose || specs.isEmpty then Vector.empty
      else
        "" +: "cards" +: specs.sortBy(_.key.path.render).map { s =>
          f"  ${s.spec.noteTypeName}%-26s ${s.key.path.render}"
        }

    header ++ failureLines ++ duplicateLines ++ deckLines ++ cardLines

  /** What a plan would do, summarised by action kind then listed. */
  def plan(p: Plan): Vector[String] =
    val counts = p.actions.groupBy(kindOf).toVector.sortBy(_._1)
    val header =
      if p.actions.isEmpty then Vector("nothing to do")
      else "actions" +: counts.map((kind, as) => f"  ${as.size}%3d  $kind")

    val orphanNote = p.orphanInference match
      case OrphanInference.Computed                       => Vector.empty
      case OrphanInference.SuppressedIncompleteScan(why)  =>
        // A run that could not look for orphans must not be mistaken for one that found none.
        Vector("", s"orphans NOT computed: $why")

    val failureLines =
      if p.failures.isEmpty then Vector.empty
      else "" +: "build failures" +: p.failures.map(describeFailure).map("  " + _)

    header ++ orphanNote ++ failureLines

  private def kindOf(a: SyncAction): String = a match
    case _: SyncAction.Create => "create"
    case _: SyncAction.Update => "update"
    case _: SyncAction.Retype => "retype (NOT APPLIED — unimplemented)"
    case _: SyncAction.Flag   => "flag as orphaned"
    case _: SyncAction.Unflag => "clear orphan flag"
    case _: SyncAction.Relink => "relink proposal (needs confirmation)"

  private def describeFailure(f: BuildFailure): String = f match
    case BuildFailure.KeyKnown(key, source, reason) =>
      s"${source.describe}  ${key.path.render}: $reason"
    case BuildFailure.KeyUnderivableInFile(noteId, source, reason) =>
      s"${source.describe}  note '${noteId.value}': $reason (orphan checks suppressed for this note)"
    case BuildFailure.FileUnreadable(file, reason) =>
      s"$file: $reason (SCAN IS PARTIAL — no orphans will be computed)"
