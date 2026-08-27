package obsidiananki.cli

import obsidiananki.anki.{InstallOutcome, NoteTypeProblem, NoteTypeStatus, RepairOutcome}
import obsidiananki.extract.VaultIndex
import obsidiananki.model.CardKey
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
    *
    * IT COUNTS NOTES, AND USED TO CALL THEM CARDS. One `CardSpec` becomes exactly one Anki
    * NOTE, and a note generates as many cards as its note type has templates: a `2way` heading
    * is one note and two cards, a cloze with three groups is one note and three. Measured
    * against the test collection on 2026-08-22 — 43 notes carrying the identity tag, 82 cards —
    * while this line said "cards: 43". The vault cannot be asked how many CARDS it implies
    * without counting cloze groups and reading each note type's template count, so the honest
    * fix is to name what is actually being counted rather than to invent the other number.
    */
  def inspect(index: VaultIndex, verbose: Boolean): Vector[String] =
    val scan  = index.scan
    val specs = scan.specs

    val header = Vector(
      s"notes:    ${specs.size}",
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

    val noteLines =
      if !verbose || specs.isEmpty then Vector.empty
      else
        "" +: "notes" +: specs.sortBy(_.key.path.render).map { s =>
          f"  ${s.spec.noteTypeName}%-26s ${s.key.path.render}"
        }

    header ++ failureLines ++ duplicateLines ++ deckLines ++ noteLines

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

    header ++ orphanNote ++ parkedNote(p.parked) ++ failureLines

  /** What this tool is already holding, said on EVERY run rather than only on the run that
    * parked it.
    *
    * THE BEHAVIOUR THIS REPAIRS. A parked note produces no action ever again — the planner
    * skips it because it is already flagged — and every other mention of orphans in this file
    * is a line about WORK. So the run that parks a note names it once and every run afterwards
    * says nothing, while the note stays suspended and out of review. A real collection held six
    * of them under a run that printed `nothing to do`.
    *
    * SILENT AT ZERO, deliberately. A `0 notes parked` line on every clean run is noise, and
    * noise in a fixed position is worse than absence: it trains the reader to skip exactly the
    * block where the real number will one day appear.
    *
    * IT DOES NOT PROMISE A REMEDY IT DOES NOT HAVE. `prune` — the command that removes these
    * after a person has seen the list — IS NOT BUILT. Naming it as though it worked would send
    * the reader to a command that does not exist, so the line says plainly that nothing removes
    * them yet. WHEN `prune` LANDS, THIS SENTENCE MUST CHANGE; it is written to be found by
    * grepping for the word.
    */
  private def parkedNote(parked: Vector[CardKey]): Vector[String] =
    if parked.isEmpty then Vector.empty
    else
      Vector(
        "",
        s"${quantify(parked.size, "note")} parked as orphaned: the source " +
          s"${if parked.sizeIs == 1 then "heading is" else "headings are"} gone from the vault, " +
          s"so ${if parked.sizeIs == 1 then "it is" else "they are"} suspended rather than deleted.",
        "  Nothing removes them yet — the 'prune' command is not built.",
      )

  /** "1 card" / "2 cards" — the count and its noun, agreeing.
    *
    * IT EXISTS BECAUSE THE SUMMARY LINE IS THE ONE LINE EVERY RUN PRINTS. Three of them read
    * `${xs.size} cards` / `actions` / `notes` unconditionally, so a run with exactly one
    * problem ended on "1 cards could not be built from the vault" — which is the sentence a
    * person sees most often, since one problem is the common case.
    *
    * IT LIVES HERE RATHER THAN IN `Main` because it is presentation, and because a second copy
    * beside the caller that needed it would be two definitions of the same sentence fragment,
    * free to drift. `Main` calls it as `Report.quantify`.
    *
    * DELIBERATELY NAIVE: it appends an `s`. Every noun these summaries use is regular, and a
    * general pluraliser would be a library pretending this file needs one. A noun that is not
    * regular should be passed already-plural to a different helper, not taught to this one.
    */
  def quantify(n: Int, singular: String): String =
    if n == 1 then s"$n $singular" else s"$n ${singular}s"

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

    // ASKS EVERY STATUS, rather than matching `Present` and answering `case _ => Vector.empty`
    // for the rest — which is how a status nobody had classified went unmentioned entirely.
    // A status with no differences contributes no lines, which is not the same as it MATCHING:
    // the state line above says which it is, and the two are printed separately on purpose.
    val drifts = outcome.before.filter(_.drift.nonEmpty).flatMap { status =>
      s"  ${status.name}" +: status.drift.map("    " + _.describe)
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
        "You asked for that with --no-migrate-note-types; without it they would have moved.",
        "",
        "Each stays on the note type it is on, which is NOT the one its marker asks for — so its",
        "fields are arranged for a shape you are no longer requesting, and its cards may be asking",
        "a question you did not write. Drop the flag to move them.",
      )

  /** WHAT A DRY RUN SAYS ABOUT THE RETYPES IT IS PREVIEWING.
    *
    * ONLY THE VERDICTS THAT CONTRADICT THE PLAN SUMMARY ARE PRINTED. `Report.plan` above
    * already counts a retype as work; a `WillApply` verdict agrees with it and adding a second
    * line saying so would be noise. What the summary CANNOT say is that a line it just counted
    * as work will not happen — so those are the ones this block exists for, and printing them
    * is the whole point of the function.
    *
    * DEFERRALS ARE ALSO SILENT HERE, and that is not an omission: `kindOf` already labels them
    * "(NOT APPLIED — see --no-migrate-note-types)" in the summary itself, so the contradiction
    * this block reports does not arise for them.
    *
    * THE REFUSAL'S OWN SENTENCE IS REUSED, not paraphrased. The preview must print what the run
    * would print; a second wording here would drift from `RetypeRefusal.describe` and the two
    * would eventually disagree about the same collection.
    */
  def retypePreview(verdicts: Vector[(SyncAction.Retype, RetypeVerdict)]): Vector[String] =
    val blocked = verdicts.collect {
      case (retype, RetypeVerdict.RefusedByShapes(refusal)) =>
        (retype, refusal.describe, refusal.remedy)
      case (retype, RetypeVerdict.ShapesUnavailable(from, to)) =>
        (
          retype,
          s"the shape of note type '$from' or '$to' could not be read from the collection",
          "this is a connection or collection fault rather than anything about the move",
        )
    }

    if blocked.isEmpty then Vector.empty
    else
      Vector(
        "",
        s"OF THE MOVES COUNTED ABOVE, ${blocked.size} WILL NOT HAPPEN.",
        "",
        "This is what the run itself would refuse, checked here against the same note types",
        "rather than assumed — so this preview and the run agree.",
        "",
      ) ++ blocked.flatMap { (retype, why, remedy) =>
        Vector(
          s"  '${retype.key.path.render}'  (${retype.from} -> ${retype.to})",
          s"    $why.",
          s"    $remedy.",
        )
      } ++ Vector(
        "",
        "Everything else in the plan is unaffected: a note that is not moved stays on the note",
        "type it is on, and nothing in this tool ever deletes a note.",
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
      // ASKED OF THE SUM, AND WITH NO IMPOSSIBLE CORNER TO ASSERT AGAINST. This was two
      // `case _ => false` probes feeding a `(Boolean, Boolean)` match, whose fourth corner
      // could not happen and so ended in a `sys.error`. That traded a compile error for a
      // RUNTIME CRASH INSIDE THE DRY-RUN REPORT — `Report.plan` is printed before a plan is
      // applied, so a third `Change` case would have crashed in front of the very person who
      // ran `--dry-run` to be careful.
      //
      // Projecting to `ChangeKind` instead makes the impossible state unrepresentable rather
      // than asserted: `changes` is a `NonEmptyVector`, so there is always at least one kind,
      // and a third kind must supply its own wording before this compiles.
      //
      // SORTED BY DECLARATION ORDER so the line reads the same however the planner happened to
      // build the vector — see `ChangeKind`.
      changes.toVector
        .map(_.kind)
        .distinct
        .sortBy(k => ChangeKind.values.indexOf(k))
        .map(_.describe)
        .mkString(", and ")

    case _: SyncAction.Retype =>
      retypePolicy match
        case RetypePolicy.Defer => "move to another note type (NOT APPLIED — you passed --no-migrate-note-types)"
        case RetypePolicy.Apply => "move to another note type"
    case _: SyncAction.Flag   => "flag as orphaned"
    case _: SyncAction.Unflag => "clear orphan flag"

  private def describeFailure(f: BuildFailure): String = f match
    case BuildFailure.KeyKnown(key, source, reason) =>
      s"${source.describe}  ${key.path.render}: $reason"
    case BuildFailure.KeyUnderivableInFile(noteId, source, reason) =>
      s"${source.describe}  note '${noteId.value}': $reason (orphan checks suppressed for this note)"
    case BuildFailure.MarkerNotOnHeading(file, reason) =>
      s"$file: $reason"
    case BuildFailure.MarkedWithoutNoteId(file, reason) =>
      s"$file: $reason"
    case BuildFailure.MarkerUnknowable(file, reason) =>
      s"$file: $reason"
    case BuildFailure.EdgeVocabularyUnusable(file, reason) =>
      s"$file: $reason (none of this note's properties makes a card until this is fixed)"
    case BuildFailure.FileUnreadable(file, reason) =>
      s"$file: $reason (SCAN IS PARTIAL — no orphans will be computed)"
