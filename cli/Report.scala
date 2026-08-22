package obsidiananki.cli

import obsidiananki.anki.{InstallOutcome, NoteTypeProblem, NoteTypeStatus, RepairOutcome}
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

  /** What a plan would do, summarised by action kind then listed.
    *
    * THE POLICY IS A PARAMETER because one action kind reads differently depending on it: a
    * retype under [[RetypePolicy.Defer]] is planned and will not happen, and a summary line
    * saying only "move to another note type" would describe work the run is not going to do.
    * The plan is printed BEFORE it is applied, so this line is what someone reads while
    * deciding whether to re-run with the flag.
    */
  def plan(p: Plan, retypePolicy: RetypePolicy): Vector[String] =
    val counts = p.actions.groupBy(kindOf(_, retypePolicy)).toVector.sortBy(_._1)
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

  /** What `--repair` changed, and — the part worth printing even when it is boring — what it
    * READ BACK afterwards.
    *
    * THE SURVIVING-DIFFERENCE SECTION IS NOT DECORATION. Two of Anki's own actions report
    * success having done nothing: `updateModelTemplates` skips a template name it does not
    * recognise, and `modelFieldAdd` is a no-op when the field is already there. So a repair that
    * printed only "applied 3 changes" would be reporting its intentions. These lines come from
    * reading the collection back.
    */
  def noteTypeRepair(outcome: RepairOutcome): Vector[String] =
    val applied =
      if outcome.applied.isEmpty then Vector("", "REPAIR: nothing needed changing.")
      else Vector("", "REPAIRED:") ++ outcome.applied.map("  " + _.describe)

    val refused =
      if outcome.plan.refusals.isEmpty then Vector.empty
      else
        Vector("", "REFUSED — these were left exactly as they are:") ++
          outcome.plan.refusals.map(r => s"  '${r.noteType}': ${r.reason}") ++
          Vector(
            "",
            "A note type whose TEMPLATE NAMES do not match the repository cannot be repaired:",
            "Anki updates templates by name and silently ignores names it does not know, so the",
            "attempt would report success having changed nothing. Rename the templates in Anki to",
            "match, or change the repository to match the collection — either way it is a choice",
            "about which of the two is right, and only you can make it.",
          )

    val failures =
      if outcome.failures.isEmpty then Vector.empty
      else
        Vector("", "COULD NOT APPLY:") ++
          outcome.failures.map((action, error) => s"  ${action.describe}: ${error.toString}")

    val surviving = outcome.remainingDrift.filter(_._2.nonEmpty)
    val survivingLines =
      if surviving.isEmpty then Vector("", "Read back afterwards: every note type now matches.")
      else
        Vector("", "STILL DIFFERENT after the repair, read back from the collection:") ++
          surviving.flatMap((name, drift) => s"  $name" +: drift.map("    " + _.describe)) ++
          Vector(
            "",
            "A field order and a field this tool does not declare are reported here and are",
            "never changed: reordering fields would rearrange your Browse columns uninvited, and",
            "removing a field would delete its contents from every note of that type.",
          )

    applied ++ refused ++ failures ++ survivingLines

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

  /** What was NOT done, and how to ask for it.
    *
    * A SEPARATE BLOCK RATHER THAN A COUNT, because the count is already in the plan summary
    * above and what is missing there is the WHY. The cards are named — up to a limit — so that
    * someone can see whether the list is the migration they expect or a heading they retagged
    * by accident.
    */
  def deferredRetypes(deferred: Vector[SyncAction.Retype]): Vector[String] =
    if deferred.isEmpty then Vector.empty
    else
      val shown = 10
      val pairs = deferred.map(r => s"${r.from}  ->  ${r.to}").distinct.sorted
      val listed = deferred.take(shown).map(r => s"  '${r.key.path.render}'  (${r.from} -> ${r.to})")
      val elided =
        if deferred.size <= shown then Vector.empty
        else Vector(s"  ... and ${deferred.size - shown} more")

      Vector(
        "",
        s"NOT DONE: ${deferred.size} note(s) are on a different note type from the one the vault asks for.",
        "",
      ) ++ pairs.map("  " + _) ++ Vector("") ++ listed ++ elided ++ Vector(
        "",
        "Nothing was written to those notes — not their fields, not their deck, not their tags.",
        "Moving a note between note types blanks every field and replaces every tag before",
        "writing them back, so this tool does not do it unless asked. Ask with:",
        "",
        "  sync --migrate-note-types ...",
        "",
        "Leaving them is safe: a note that is not moved simply stays on the note type it is on,",
        "and nothing in this tool ever deletes a note.",
      )

  private def kindOf(a: SyncAction, retypePolicy: RetypePolicy): String = a match
    case _: SyncAction.Create => "create"

    // AN UPDATE IS TWO DIFFERENT EVENTS AND USED TO BE ONE WORD. `SyncAction.Update` carries a
    // field change, a deck change, or both, and until decks became something a person composes
    // deliberately (`--deck-from`) the deck half was rare enough that "update" covered it. It is
    // not rare any more: the first run after changing the deck shape moves every card in the
    // collection, and a summary reading "43 update" invites the reader to believe their content
    // was rewritten. Naming the two apart costs one line, and is the difference between a report
    // that is read and one that is skimmed.
    case SyncAction.Update(_, _, changes) =>
      val fields = changes.exists { case _: Change.FieldsChanged => true; case _ => false }
      val deck   = changes.exists { case _: Change.DeckChanged => true; case _ => false }
      (fields, deck) match
        case (true, false) => "update"
        case (false, true) => "move to another deck"
        case (true, true)  => "update, and move to another deck"
        // AN ASSERTION, NOT A FALLBACK. `changes` is a `NonEmptyVector` over a sum with exactly
        // these two cases, so one of the flags is always set. A default of "update" here would
        // turn a third case somebody adds later into a silently mislabelled line.
        case (false, false) =>
          sys.error(
            s"an Update carried changes that are neither a field change nor a deck change — a " +
              s"case was added to Change without teaching the report about it: $changes"
          )

    case _: SyncAction.Retype =>
      retypePolicy match
        case RetypePolicy.Defer => "move to another note type (NOT APPLIED — see --migrate-note-types)"
        case RetypePolicy.Apply => "move to another note type"
    case _: SyncAction.Flag   => "flag as orphaned"
    case _: SyncAction.Unflag => "clear orphan flag"

  private def describeFailure(f: BuildFailure): String = f match
    case BuildFailure.KeyKnown(key, source, reason) =>
      s"${source.describe}  ${key.path.render}: $reason"
    case BuildFailure.KeyUnderivableInFile(noteId, source, reason) =>
      s"${source.describe}  note '${noteId.value}': $reason (orphan checks suppressed for this note)"
    case BuildFailure.FileUnreadable(file, reason) =>
      s"$file: $reason (SCAN IS PARTIAL — no orphans will be computed)"
