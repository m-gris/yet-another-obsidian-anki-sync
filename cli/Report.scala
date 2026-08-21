package obsidiananki.cli

import obsidiananki.anki.{InstallOutcome, NoteTypeProblem, NoteTypeStatus}
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

  /** What an `install-note-types` run found and what it did.
    *
    * EVERY NOTE TYPE GETS A LINE, INCLUDING THE ONES THAT WERE ALREADY FINE. A report that
    * listed only problems would be indistinguishable, at a glance, from a report that could not
    * look — and this command's whole job is to say what a collection holds.
    *
    * THE RENAME REFUSAL COMES FIRST AND SAYS WHAT WAS NOT DONE. It is the one outcome where
    * the tool declines to act on anything at all, and the remedy is a manual step in Anki that
    * no amount of re-running will substitute for.
    */
  def noteTypes(outcome: InstallOutcome): Vector[String] =
    val blocked = outcome.blockedByRename
    val refusal =
      if blocked.isEmpty then Vector.empty
      else
        Vector(
          "REFUSED: a note type is still under its old name, so NOTHING was created.",
          "",
        ) ++ blocked.map((old, wanted) => s"  rename '$old'  ->  '$wanted'") ++ Vector(
          "",
          "Those note types hold notes with review history, and AnkiConnect has no action that",
          "renames a model — so this is done by hand, in Anki's Tools > Manage Note Types.",
          "",
          "Creating them here instead would leave you with TWO note types: a new empty one and",
          "the old populated one, with every note still on the old and nothing to tell you so.",
          "",
        )

    val listed = "note types" +: outcome.before.map { status =>
      val state = status match
        case NoteTypeStatus.Absent(_) if outcome.created.contains(status.name) => "CREATED"
        case NoteTypeStatus.Absent(_)                                          => "absent"
        case NoteTypeStatus.AwaitingManualRename(_, current)                   => s"AWAITING RENAME FROM '$current'"
        case NoteTypeStatus.Present(_, drift) if drift.isEmpty                 => "present, matches"
        case NoteTypeStatus.Present(_, _)                                      => "present, DIFFERS"
      f"  ${status.name}%-36s $state"
    }

    val drifts = outcome.before.flatMap {
      case NoteTypeStatus.Present(asset, drift) if drift.nonEmpty =>
        s"  ${asset.spec.name}" +: drift.map("    " + _.describe)
      case _ => Vector.empty
    }
    val driftLines =
      if drifts.isEmpty then Vector.empty
      else
        Vector("", "DIFFERENCES — reported, NOT repaired:") ++ drifts ++ Vector(
          "",
          "Nothing was written to these. This tool never overwrites a note type it did not",
          "create: a template or a stylesheet you edited in Anki is yours, and only you know",
          "whether the repository's version is the one you want.",
        )

    val failureLines =
      if outcome.failures.isEmpty then Vector.empty
      else
        Vector("", "COULD NOT CREATE:") ++
          outcome.failures.map((name, error) => s"  '$name': ${error.toString}")

    refusal ++ listed ++ driftLines ++ failureLines

  /** Why `sync` will not write to this collection, and what to do about it. */
  def noteTypesNotReady(problems: Vector[NoteTypeProblem]): Vector[String] =
    Vector(
      "",
      "REFUSED: this collection is not ready for the note types this tool writes.",
      "",
    ) ++ problems.map("  " + _.describe) ++ Vector(
      "",
      "Nothing was written. Run `install-note-types --profile <profile>` to create what is",
      "missing; it reports anything that is present and differs rather than overwriting it.",
      "",
      "This is checked BEFORE the plan is computed on purpose. Anki reports a field name it",
      "does not recognise as \"cannot create note because it is empty\" on create, and as no",
      "error at all on update — so a plan run against a collection missing a field would look",
      "like it worked.",
    )

  private def kindOf(a: SyncAction): String = a match
    case _: SyncAction.Create => "create"
    case _: SyncAction.Update => "update"
    case _: SyncAction.Retype => "retype (NOT APPLIED — unimplemented)"
    case _: SyncAction.Flag   => "flag as orphaned"
    case _: SyncAction.Unflag => "clear orphan flag"

  private def describeFailure(f: BuildFailure): String = f match
    case BuildFailure.KeyKnown(key, source, reason) =>
      s"${source.describe}  ${key.path.render}: $reason"
    case BuildFailure.KeyUnderivableInFile(noteId, source, reason) =>
      s"${source.describe}  note '${noteId.value}': $reason (orphan checks suppressed for this note)"
    case BuildFailure.FileUnreadable(file, reason) =>
      s"$file: $reason (SCAN IS PARTIAL — no orphans will be computed)"
