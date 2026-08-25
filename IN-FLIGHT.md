# In flight — delete this file when the work below has landed

_Rewritten 2026-08-24. Everything here was checked, not remembered. **Nothing is running.**
Working tree clean; 651 tests, 0 failures, 0 warnings. 10 commits were unpushed when this was
written — run `git push origin main` if that is still true._

## Where things stand

Card identity has not moved once across everything below: the golden's `src::` lines are
unchanged, so every note is an UPDATE and keeps its review history. That is the acceptance
invariant and it holds.

Two changes DO rewrite content. Both were deliberate and both were reported to Marc first:

- **The breadcrumb.** `Context` is now the whole location — folders, file name, heading chain —
  minus whatever the card already carries as a FIELD. All 55 golden cards moved; on Marc's real
  vault it planned **16 updates and no deck moves**.
- **`#flashcard/cdd/{1,2,3}way`** replaced `3way` / `3way/all`, with the old spellings kept as
  aliases so rewriting a vault's markers syncs nothing at all.

## The two defects found last — ONE FIXED, ONE STILL OPEN

1. ~~**A dry run does not consult the retype gate.**~~ **FIXED** — one `Retyping.verdictFor`
   now answers both halves, and `Executor.preview` asks it for the dry run. Guarded by a law
   asserted in both directions. Original report, kept because it is the measurement:
   Measured on Marc's vault: `sync --dry-run --migrate-note-types` printed
   `1 move to another note type` and `result: OK`, while a real run refuses — `Obsidian Basic`
   has 1 template, `Obsidian Concept-Descriptor` has 3, and `plan/Retyping.scala:117` refuses any
   difference. Cause: `cli/Main.scala:929` returns `PlannedOnly` for a dry run and never reaches
   `Executor.run`, the only place note-type shapes are read. A dry run whose whole job is to say
   what a real run will do cannot see the one check that would stop it.
2. **STILL OPEN — but the MESSAGE is fixed.** Each direction now names its own unknown, and a
   test pins them apart; the gate itself still refuses both, pending the measurement below.
   **THE RETYPE GATE COMPARES TEMPLATE COUNTS, WHICH CONFLATES TWO UNLIKE RISKS.** It refuses any
   difference. But growing and shrinking fail differently, and only one of the two failures is
   the one the gate's comment is actually about:

   - **SHRINKING (3 → 1) CAN STRAND A CARD.** Cards at ordinals 1 and 2 would have no template
     on the new type. What Anki does with them — survives, orphaned until Check Database, or
     destroyed — is one line into the compiled Rust backend, unread. This is the documented
     unknown, and refusing it is right.
   - **GROWING (1 → 3) CANNOT STRAND A CARD** — ordinal 0 exists on both types, by arithmetic.
     **But that is not the same as being safe**, and an earlier draft of this entry said "safe",
     which was wrong. The growth move needs Anki to GENERATE the two new cards on a note-type
     change, and **nobody has measured whether it does**. Non-generation is silent, and silent
     non-generation is precisely the failure `SyncAction.Retype` exists to prevent — the header
     of `plan/Retyping.scala` says exactly this, and it was written before this entry was.

   **So the gate is narrowable but not by reasoning alone.** Splitting it into
   `Grows` / `Shrinks` / `Same` is the easy half; the half that unblocks it is ONE MEASUREMENT
   in a throwaway profile — never `User 1` — retyping a note upward and counting the cards
   afterwards. Until that exists, the gate is honest and this item stays open.

   _Marc's immediate case needs no fix at all: `Obsidian Basic` has 1 template and
   `Obsidian Concept-Descriptor` has 3, and that card has 0 reviews — so deleting the note in
   Anki and re-syncing recreates it on the right note type with nothing lost._

## OPEN — audit items not yet done

The `if`-versus-pattern-match audit (a subagent, 2026-08-23) produced 11 findings; seven landed.

3. ~~**`anki/NoteTypeInstall.scala:356-380` — four independent drift probes.**~~ **DONE
   2026-08-24**, as IN-FLIGHT proposed: one total question per difference, folded over the list.

   `NoteTypeDrift.repair` returns a `DriftRepair` — `RefuseWholeType`, `AddFields`,
   `ReplaceTemplate`, `ReplaceStyling`, `LeaveAlone` — and the planner folds those with a match
   that is itself total. **Two gates, not one:** a new `NoteTypeDrift` case breaks the build at
   `repair`, and a new `DriftRepair` case breaks it at the fold. Both verified by adding the
   `SortFieldDiffers` case the entry named and watching the compiler refuse it.

   `LeaveAlone` is unused today, and a test asserts that it stays unused, so choosing it later
   is a visible ruling rather than a quiet resting place for a case nobody decided about.
4. ~~**Catch-alls over `NoteTypeStatus` at three sites.**~~ **DONE 2026-08-24.** The question is
   now asked once, at `NoteTypeStatus.differences`, and the three consumers do no matching at
   all — `isClean` is `before.forall(_.differences.isEmpty)`, the repair read-back is
   `status.name -> status.differences`, and the report filters on `_.differences.nonEmpty`.

   **It is named `differences`, not `drift`, only because `Present` already has a parameter
   called `drift` and a member cannot share the name.** Renaming that parameter is the tidier
   outcome and is Marc's call, not a side effect of an audit fix.

   **It IS the abstract member this entry proposed, reached by making `NoteTypeStatus` a sealed
   trait.** _Landed 2026-08-25, after two corrections worth recording._

   The first attempt used a total match on the `enum` instead, justified by the claim that a
   Scala 3 enum case cannot carry a body. That claim is TRUE — both spellings are rejected by
   the PARSER, `case Absent(a: A):` with an indented `def` and
   `case Absent(a: A) extends NoteTypeStatus { … }` alike — but the restriction belongs to the
   `enum` SYNTAX, not to Scala, and presenting it as a language limit made a design choice look
   like a closed door. Marc asked; it was tested rather than remembered; the desugared shape
   compiled first time.

   **The conversion paid for itself twice over, and both were things the enum form gave up:**

   - **The member is called `drift`, its natural name.** `Present`'s existing parameter
     satisfies it directly, exactly as its `asset` parameter satisfies the other abstract
     member. Under the `enum` it had to be `differences`, because a member cannot share a name
     with a case parameter — and that left a rename hanging as an open question for Marc.
   - **The error moves to where the mistake is.** Adding a variant without answering now fails
     with _"class Unclassifiable needs to be abstract, since def drift … is not defined"_,
     pointing AT THE VARIANT. The match form pointed at a match further down the file: the
     author was told a case was missing somewhere else. A variant now cannot be written at all
     without answering.

   Nothing was given up: no `values`, no `ordinal`, no `fromOrdinal` was used, and `sealed` is
   what made the matches exhaustive to begin with. The report's state line still gates
   independently — verified by satisfying `drift` and watching it refuse anyway.

   _Verified by adding a fourth case. The report's state line already refused it — that gate
   existed. What is new is that satisfying the state line no longer lets the build go green:
   `differences` refuses independently, which is exactly the hole, since an author who fixed
   the one error they were shown would previously have shipped three silent wrong answers._
5. ~~**`extract/VaultWalker.scala:264` folds an `Either` to a `Boolean`.**~~ **DONE 2026-08-24.**
   The three states are now the `MarkedHeadings` enum — `Present`, `Absent`, `CouldNotLook` —
   named for the epistemic state rather than for the parser, because what matters downstream is
   that no claim about markers may be made in EITHER direction.

   Removing the false message opened a hole, and closing it needed a new failure case. A file
   with no `id` whose markdown will not parse is reported by nothing else, so deleting the lie
   alone would have left it silent — worse than a wrong message, which at least gets read. It is
   now `BuildFailure.MarkerUnknowable`, raised ONLY where nothing else names the file.

   **Neither fixture vault exercised it** — the check IN-FLIGHT asked for — so the tests build
   the input inline. They did not need anything exotic: parsing is strict, so `[0]` written in a
   sentence is enough, which means this fired on ordinary prose.

   _A mutation found a missing guard: widening the no-double-report condition from `case None`
   to `case _` left the whole suite green, so that claim lived in a docstring and was checked by
   nothing. There is an assertion for it now._
6. **STYLE, ranked below everything above and possibly not worth doing.**
   `plan/Retyping.scala`'s `isCloze: Boolean` wants a two-case enum — but no failure is nameable,
   since Anki has exactly two kinds. And note-type names as `String`, which the auditor
   deliberately declined to prescribe: one side of that string is genuinely open, because
   `existing.note.noteType` is whatever Anki holds, foreign types included.

## OPEN — decisions only Marc can make

7. **Should sync report cards that will render blank?** Switching a table to fewer directions
   leaves cards whose front is empty. Anki keeps them and says "The front of this card is blank"
   until Tools → Empty Cards is run. Either say nothing, or count them and say so.
8. **Content-duplicate report.** Two cards with identical fields under different keys trip
   nothing: `allowDuplicate` is on, uniqueness is by key, and nothing compares content. It also
   weakens the hash-based repair hint, which fires only when exactly one card matches a hash.
   Refusing would be wrong — two tables legitimately defining one term is the author's business —
   so it would have to be a report.
9. **Carry a note's Obsidian tags onto its Anki notes.** Requested 2026-08-22. The tool owns only
   the `src::`, `sha::` and `orphaned::` prefixes and preserves foreign tags, but never reads a
   note's frontmatter `tags` — so an Obsidian tag cannot drive an Anki filtered deck. Open
   questions: verbatim or namespaced (an unprefixed vault tag is indistinguishable from one added
   by hand in Anki, and this tool must never delete somebody's own tag believing it owned it),
   and what a tag REMOVED in the vault should do to the note carrying it.
10. **Row scope at `cdd/2way` / `cdd/3way`** — a row card that blanks the CONCEPT rather than the
    values ("which bone has these two borders?"). Needs a second template. Not designed.
11. **The marker vocabulary is half-migrated.** `cdd/{1,2,3}way` is coherent, `table` always was,
    and front-back is deliberately unprefixed. `3way` / `3way/all` remain as aliases. Decide
    whether to drop them (and rewrite the vault, which syncs nothing) or keep them indefinitely.

## OPEN — work with no decision left in it

12. **`prune`** — deletes flagged cards after the list has been reviewed. Named in `README.md`
    under *Not built yet*, and it is the exact case `SyncAction.dispositionUnder` was written to
    protect against.

    **NOW DISCOVERABLE, WHICH RAISES ITS PRIORITY.** Until 2026-08-24 a parked note was
    mentioned by the run that parked it and by no run afterwards, so the missing command was
    invisible too. `Report.parkedNote` now says on every run how many notes are parked — and
    says plainly that nothing removes them yet. That sentence is a standing admission of an
    unbuilt command, and it is written to be found by grepping for `prune` when the command
    lands. Marc found the gap by asking why deleting a note in Obsidian appeared to do nothing
    in Anki; it had done something, twice, and said so only the first time.
13. **A formatter, and a keybinding** — largely solved on 2026-08-22 without being closed here.
    `conform.nvim` runs prettier on markdown at `--tab-width 4`, bound to `<leader>fo`, and that
    REPAIRS a two-space nested list into a four-space one — so it is the fix for `ListIndent`'s
    refusal rather than a cause of it. Format-on-save stays off deliberately.
14. **Recovery tiers 3 and 4** — matching a broken identity tag by SIMILAR content. Tiers 1 and 2
    (exact hash, exact fields) are built. Anything fuzzy may only RANK candidates, never apply.
15. **`ZZ-probe-delete-me`** — a note type left in `claude-POC-test` by a migration probe.
    AnkiConnect has no delete-model action; Tools → Manage Note Types.
16. **Marc's vault still has one note on the wrong note type** — but it is a DIFFERENT note
    from the one this entry first named. _Re-measured 2026-08-25 by dry-running the live vault._
    `3 components` is gone from the plan; the note now blocked is `framework`, on
    `Obsidian Basic (and reversed card)` (2 card templates) while the vault asks for
    `Obsidian Concept-Descriptor` (3). That is the GROWTH direction, so it is a live instance of
    exactly what item 2 is about — the measurement is no longer hypothetical, it has a note
    waiting on it.

## Rulings that are settled — do not reopen

- **The list marker is EXPLICIT.** The tool can see a list is present; it can never see whether
  the ORDER is the knowledge.
- **Under-indented nested lists are REFUSED, not repaired.** The parser has already consumed the
  indentation, so repair would be a guess. A FORMATTER may repair the source — see item 13.
- **The tool writes only to its OWN five note types**, never Anki's stock ones.
- **A note type that already exists is never overwritten without `--repair`.**
- **Suspending an orphan is a POSTCONDITION of flagging, not an invariant.** Anki records no
  authorship for a suspended card, so the tool cannot tell its own suspension from Marc's, and an
  invariant it cannot enforce would be a claim of ownership it does not have.
- **New note-type fields go LAST in a manifest.** `modelFieldAdd` APPENDS, so any other position
  leaves a repaired collection permanently reporting a field-order difference.
- **Gate fields are INVERTED — empty means the old behaviour**, so a note predating a field
  renders as it always did rather than blank.
- **Presentation and organisation are composable OPTIONS; correctness is not** (REQUIREMENTS
  item 11). An option to be silently wrong is a defect with a switch on it.
- **CONFIGURATION WAS TRIED AND REMOVED, 2026-08-23.** A `VaultLayout` YAML file and a
  `ContextShape` both existed, green and unwired, and were both deleted. The breadcrumb is a
  RULE, not a setting: everything the card's face does not already carry. Deck SHAPE stays a flag
  because it is genuine taste — but the anti-spoiler CEILING over it is not.
- **A deck path may not print the card's own answer.** `Decks.clamp` truncates; the breadcrumb
  removes. The difference is forced rather than chosen: a deck path is prefix-closed, a
  breadcrumb is a list.
- **A breadcrumb DE-DUPLICATES and a deck path does NOT.** A deck is a filing address and must
  stay unambiguous; a breadcrumb is a sentence read while answering, and repetition is noise.
- **Never touch profile `User 1`** or `/Users/marc/srs-in-obsidian-test/`.

## Fixtures that must not be "tidied"

- `dummy-vault/Patterns/Shallow-Nesting.md` — its two-space nesting IS the fixture.
- `dummy-vault/Patterns/Table-Edge-Cases.md` — deliberate duplicate identities. Excluded from a
  live sync by copying the vault without this file; leaving it in aborts the plan.

## Method, and the mistakes worth not repeating

**MUTATION TESTING — write the test, then break the production code and check the test dies, with
a CONTROL MUTANT that must survive.** It caught, this week alone: a test asserting only a card's
key that would have survived a column-alignment bug; a message branch no test reached (corrupting
it to the literal `MUTANT` left 548 tests green); and twice a "fix" that reached no test because
the COMPILER rejected it first, which is the better outcome.

**NEVER `git checkout` TO REVERT A MUTATION.** It cost uncommitted work FIVE times in one
session — once eating an entire enum, and once producing a PUSHED COMMIT whose message described
work it did not contain. Commit before mutating, and revert with `cp` from a scratch copy, so the
restore can only touch bytes that were explicitly saved. `git checkout` cannot tell a mutation
from an hour of real work in the same file.

**RE-RUN THE SUITE BEFORE COMMITTING, ALWAYS.** That broken pushed commit happened because five
of six files were staged and the sixth — the only one carrying the actual fix — had been
reverted, and nothing re-checked.

**TESTS THAT READ REPOSITORY FILES MUST NOT USE `sys.props("user.dir")`.** It is the SHELL's
working directory. Four tests used it and all four resolved files under
`backend-interview-prep/obsidian-anki-custom-sync/` — a stale copy of this entire tool left
behind by the extraction — INCLUDING THE GOLDEN TEST, which was comparing a stale vault's cards
against a stale golden. They passed because the copies were identical. `TestSources` now anchors
on the compiled test classes and walks up to `project.scala`. **The stale copy still exists**, so
the trap is armed for any new test reaching for the old idiom.

**A GREEN DRIFT TEST PROVES NOTHING UNTIL ITS EXTRACTION IS GUARDED.** The `--deck-from` token
test failed loudly rather than passing by luck ONLY because it asserts `declared.sizeIs >= 3`
before comparing two sets. Without that line it would have compared two empty sets and agreed.

**COMMENTS ARE LOAD-BEARING HERE AND NOTHING CHECKS THEM.** Found this week: three comments in
three files asserting the exact inverse of what the code did, after a commit made an `if`
always-false; and a comment in `cli/Main.scala` telling future authors that exhaustiveness is
unenforced, written one day before the flag that enforces it landed and never revisited. When a
fix invalidates a comment, the comment is part of the fix.

**THE COMPILER IS THE FIRST TEST.** `-Wconf:msg=exhaustive:e` makes an inexhaustive match a BUILD
ERROR. A `case _ =>` over a sealed sum opts out of that as completely as an `if` does, while
looking like it did not — which was the entire yield of the audit. The demonstration is in this
repository's own history: `6a494e7` gave `Marker.Table` parameters, the compiler forced the MATCH
arm updated in that same diff, and said nothing about the `if` eleven lines above, which flipped
to always-false and stayed that way for four days.

## OPEN — found after the numbered sections above were written

_Appended rather than filed into the section it belongs to, because `HANDOFF.md` cross-references
the items above BY NUMBER ("items 7–11 of IN-FLIGHT.md") and renumbering would silently break
those references._

17. **DECISION — the final summary line counts actions the run will refuse.** Measured live on
    2026-08-24, with `--dry-run --migrate-note-types` over Marc's vault. The report said, in
    order: `1 move to another note type`, `1 update`, then a block correctly headed
    `OF THE MOVES COUNTED ABOVE, 1 WILL NOT HAPPEN` — and then closed on
    `result: OK — dry run; 2 actions are outstanding`.

    Only ONE of those two will be applied. The refusal is disclosed three lines earlier, so
    nothing is hidden; what is wrong is that the LAST line, which is the one line every run
    prints and the one a person actually reads, still counts the refused move as work. This is
    the same shape as the dry-run defect fixed the same day — a summary that does not describe
    what the run will do — arriving one line lower.

    Candidates, not a prescription: count only what will be applied and name the refused ones
    separately; or keep the total and qualify the noun so the number stops reading as a promise.
    **Marc's call — it is the wording of the line he reads most.** The counting lives in
    `cli/Main.scala`'s `describeSyncOutcome` / `verdict`, and `RetypeVerdict` already carries
    exactly the distinction the line needs.

## PARKED, deliberately — small, cheap, and explicitly deferred on 2026-08-25

_Marc asked for these to be kept warm rather than done now. They are small enough to lose and
important enough not to, so they are named here rather than left inside a 56k-character document
nobody re-reads. Both come out of `docs/EVOLVABILITY.md`, which carries the full reasoning._

18. **TWO DOCSTRINGS ON THE HISTORY-PROTECTING PATH SAY THE OPPOSITE OF WHAT THE CODE DOES.**
    Free to fix, actively misleading, and both sit on the mechanism that keeps review history
    alive — which is exactly where a wrong comment costs the most.

    - `plan/SyncAction.scala:162-166`, the `Flag` case's own documentation, says "WHAT THIS DOES
      TODAY IS WRITE A TAG, AND NOTHING ELSE… that is not built: `Anki` has no suspend operation
      yet." It is flatly false: `Anki.suspend` exists and `Executor` suspends every card of a
      flagged note. The single place where the code describes the action that removes a card
      from review describes an action it stopped performing.
    - `model/CardSpec.scala:42-46`, echoed at `docs/CARD-MODEL.md:251`, promises that editing an
      unlabelled cloze deletion's text "retires the key: the card starts over and the old one is
      flagged as an orphan, visible in the prune list." **No such thing happens.** Orphan
      flagging works on `CardKey`s and there is one key per SECTION, not per cloze group — the
      planner has no cloze awareness at all. A retired cloze group produces no flag, no tag and
      no prune-list entry.

19. **THE GIT-REPLAY MEASUREMENT (called M1 in `docs/EVOLVABILITY.md`) HAS NOT BEEN RUN, AND IT
    GATES THE IDENTITY DECISION.** Read-only, no Anki, no writes, roughly an afternoon: walk the
    vault's git history, run the extractor over each pair of trees, diff the key sets, and
    classify every key death — file deleted, file renamed, ancestor heading reworded, marked
    heading reworded, frontmatter `id` changed, body-only edit. Then count how many cards each
    ancestor rewording would have orphaned; **that number is the amplification factor.**

    **Why it is worth an afternoon:** every option for making reformulation affordable is
    currently being weighed on the assumption that heading renames dominate the cost of change.
    Nobody has checked. Renaming is the most VIVID cost, which is not the same as the largest,
    and the answer decides whether the effort belongs on heading renames or on ancestor renames —
    which have a far larger blast radius and no good candidate remedy.

## Method — what the last two days added to "the mistakes worth not repeating"

_Written 2026-08-26. Each of these cost real time on 2026-08-25 and every one is the same shape as
the bugs this project keeps finding in itself: **something that looks like verification and is
not.**_

- **A TEST THAT COMPARES TWO ABSENT THINGS PASSES.** Three separate instances in two days. A new
  parser test asserted "the inline spelling reads the same as the block spelling" and was green
  while BOTH were `None` — it would have stayed green through the entire defect it was written for.
  The measurement of whether unsuspending restores scheduling compared ten card columns and
  reported every one identical; three of them, **including `interval`, the one that matters most**,
  were `None` on both sides, because `cardsInfo` returns `interval` and not `ivl`. **Assert
  presence before asserting agreement, and make the check abort when a field is absent on both
  sides rather than counting it as a match.**

- **A GUARD CAN BE DEAD CODE AND ITS TEST STILL PASS.** `TagCodec.decode` grew a clause refusing a
  path marked as non-heading that names no kind. Deleting the clause killed no test — such a tag is
  ALREADY refused, for a different reason, because its first heading segment is empty. The clause's
  only real product is its MESSAGE. A test asserting `isLeft` could not see that; one asserting the
  reason can. **When a mutation survives, the usual finding is not that the code is unnecessary but
  that the test is measuring the wrong thing.**

- **A DOCSTRING THAT PROMISES A TEST MUST BE WRITTEN WITH THAT TEST, IN THE SAME PASS.** The
  `CardPath.Note` documentation said "a test asserts that nothing produces it yet" before any such
  test existed. That is the same false-comment defect being fixed elsewhere in this file, committed
  fresh. Write the sentence and the assertion together or write neither.

- **A COMPILE ERROR CAN BE A GHOST.** A `match may not be exhaustive` names a case that exists in
  neither the working tree nor `HEAD` — that is a stale incremental-compile artifact from an
  earlier mutation run, not a real error. Check `git grep` before debugging it.

- **PERL WITH `-CSD` DOUBLE-ENCODES LITERAL UTF-8 IN A HEREDOC.** Section signs and em dashes came
  out as mojibake in a committed document. Every doc edit since is done in Python with an explicit
  `encoding="utf-8"` on both the read and the write, and checked with a grep for the mojibake
  bytes afterwards.

- **DELETE BY LOCATING TWO EXACT LITERALS, NEVER BY A REGEX WITH A LAZY MULTILINE WILDCARD.** One
  such regex deleted an entire type: the replacement docstring reused the opening sentence of the
  docstring being removed, so the match started from the wrong occurrence. The `cp` backup made it
  a non-event. Compute the span, assert the start is unique, refuse if the span is an implausible
  number of lines, and print what is about to go.
