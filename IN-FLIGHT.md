# In flight — delete this file when the work below has landed

_Rewritten 2026-08-24, appended to daily since. Everything here was checked, not remembered._

_**Test count last measured 2026-08-27: 34 suites, 744 tests, 0 failures, 0 warnings.** The
figure in this header read 651 until then, three days after it stopped being true — a number in a
header is the easiest thing in a document to leave behind, and `HANDOFF.md` carries the same
figure independently, so the two can disagree without either looking wrong. Treat both as a
reading rather than a fact: run the suite._

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
2. ~~**STILL OPEN**~~ **RESOLVED 2026-08-26 by measuring it.** Growth was measured lossless — every card, id, interval, ease and review log survived, and Anki honoured the gate fields, so the move is two cards to two cards rather than two templates to three. The gate now refuses only SHRINKING, which is still genuinely unmeasured. _The entry below is kept because its reasoning was right and its framing was wrong: it argued about template counts, and a template count is an upper bound on a note's cards rather than the number it has._ Each direction now names its own unknown, and a
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
6. ~~**STYLE, ranked below everything above and possibly not worth doing.**~~ **THE RANKING WAS
   WRONG — SUPERSEDED BY ITEM 25.** _Corrected 2026-08-27._ Marc's `framework` card was the
   counterexample: a note on the wrong note type, where the tool could say only that a NAME
   differed and nothing about what its fields MEANT. That is not style. _Original entry follows._
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
16. ~~**Marc's vault still has one note on the wrong note type**~~ **FIXED 2026-08-26.** `framework` was migrated onto `Obsidian Concept-Descriptor` with `--migrate-note-types` after the gate was widened. Verified against the live collection: two cards before, two after, the same card ids, zero drift on interval, ease, reps, lapses or review log — and its concept now sits in the `Concept` field instead of being demoted to the breadcrumb, which is what Marc reported in the first place. _Original entry follows._ — but it is a DIFFERENT note
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

- **A MUTATION HARNESS THAT COUNTS TEST FAILURES CANNOT SEE A MUTATION THE COMPILER REJECTED.**
  _2026-08-26._ A mutation was reported as SURVIVING when the build had in fact refused it —
  `-Wconf:msg=exhaustive:e` will not accept a match that stops being exhaustive, so there were no
  tests and therefore no failures, which counts identically to zero kills. That is the strongest
  possible outcome being reported as the weakest. **Count compile errors and test failures
  separately**, and treat a mutation that does not build as killed by the compiler.

- **ASSERT THE CONSEQUENCE, NOT THE MESSAGE.** _2026-08-26._ A test named "two schema notes yield
  no vocabulary at all" asserted only that the refusal was REPORTED. Silently adopting the first of
  the two vocabularies left it green, because the report is emitted either way. A test that names a
  behaviour in its title and checks only the diagnostic is the same class of thing as a docstring
  that promises a test: it reads as verification and is not.

- **WHEN A MUTATION SURVIVES, FIND OUT WHICH ARM IS LOAD-BEARING BEFORE BLAMING THE TEST.**
  _2026-08-26._ The usual finding is a weak test. This time it was DEAD CODE: making the
  bare-`Header` arm of the heading predicate throw killed nothing, because Laika's section builder
  wraps every heading in a `Section` and that arm is unreachable through any vault. The arm was
  kept — deleting it would send a bare header to the catch-all and answer "not a heading", so a
  document full of headings would report having none — and the predicate is now driven directly on
  hand-built syntax trees so both arms are covered.

- **A DRIFT-GUARD TEST YOU WROTE YOURSELF IS EVIDENCE YOU CREATED THE DRIFT.** _2026-08-26._ The
  declarations parser had a test asserting its three words agreed with the marker vocabulary. That
  test existed only because the parser had invented a second vocabulary instead of composing the
  first — and the second one clashed, since `1way` on a heading is a two-field card. Composing
  `Marker.parse` deleted the drift, the lookup table, the word list its messages needed, and the
  guard. **Before writing a test that two things agree, ask why they are two things.**

## OPEN — verified by the pipeline brainstorm, 2026-08-26

_Three facts, each checked by running or reading rather than inferred, and each surviving the
adversarial review at `docs/PIPELINE-DESIGN-REVIEW.md`. They are lifted out of that 38,000-character
document because a finding nobody can find is a finding nobody acts on._

20. ⚠️ **AN OBSIDIAN BLOCK REFERENCE PRINTS ON THE CARD FACE.** _This is also why `^blockid` is
    eliminated as a card ANCHOR — see `docs/CLOZE-REDESIGN.md`._ VERIFIED BY EXECUTION through this
    project's own parser: `The outermost layer is the ==epidermis==. ^abc123` renders as
    `<p>The outermost layer is the {{c1::epidermis}}. ^abc123</p>`.

    **Structural, not accidental.** `^` is a delimiter nowhere in the stack — not in Laika's
    `Markdown.spanParsers`, not in `GitHubFlavor`, not among the six parsers
    `ObsidianSyntax.bundle` registers, and `Html.escape` covers `& < > " { }` and leaves `^` alone.
    So no Laika change will start eating carets, and nothing will start dropping them either.

    **NOT LIVE IN MARC'S VAULT TODAY, and one edit from being live.** His `^` definitions sit in
    unmarked `## Sources` sections. Mark a section that contains one and it goes onto a card.

    Candidates, not a prescription: strip a trailing `^id` when lowering a block; render it as
    nothing the way `%%comments%%` already are; or leave it and document the trap. **It also
    kills `^blockid` as a card ANCHOR**, which is what the brainstorm went looking for.

21. ⚠️ **EVERY BLOCK-ID DEFINITION IN `References/Modern Mathematics.md` IS INSIDE A CALLOUT, AND
    CALLOUTS FAIL THE STRICT PARSE.** VERIFIED BY EXECUTION against the real file: `> [!note] Page
    31` yields `unresolved link id reference: !note`, because CommonMark reads `[!note]` as a
    shortcut reference link — the same mechanism the wikilink handling exists for.

    **It is silent only because that note has no `id:` in its frontmatter**, so the walk takes the
    `CouldNotLook => ()` arm and says nothing. **Giving it an id — which is exactly what somebody
    does when they want its annotations to become cards — is what makes the failure appear.**
    All 125 definitions are affected.

    A latent trap of the shape this project keeps finding: correct behaviour today, and the
    obvious next action detonates it.

22. **`Marker.NoteTypes` HAS NO PRODUCTION CONSUMERS.** Only tests reference it, plus one comment.
    Dead API sitting in the middle of the back end the brainstorm was convened to examine. _Audit
    before deleting: parked-not-dead is a real category, and the five names it holds are the ones
    the manifests install._


## OPEN — decided 2026-08-26, not yet built

23. **A CARD RETIRED BY NARROWING GETS FLAGGED AND REPORTED.** Ruled by Marc after two rounds:
    first "tag it", which turned out to be unbuildable, then "flag it".

    **Why a tag cannot do it.** Anki tags live on NOTES; suspension and flags live on CARDS. The
    tool's own algebra shows the split — `addTags` takes note ids, `suspend` takes card ids.
    Narrowing retires ONE card of a note whose siblings are healthy, so a note-level tag would mark
    all of them and every report would call the whole note parked.

    **Why a flag rather than suspension.** The settled ruling is that the tool cannot tell its own
    suspension from Marc's, which is exactly why an orphan carries a tag ALONGSIDE its suspension —
    the tag is the record. For a single card there is no tag available, so suspension alone would
    leave the tool unable to recognise its own work and unable to undo it safely when the marker is
    widened again. A flag is per-card AND self-identifying: it does both jobs. The counter-argument
    for suspension — that a flag does not remove the card from review — is void, because a retired
    card's front renders empty and Anki never queues it.

    **Colour 7**, all seven being free in the live collection today.

    **What is still to build:** the tool must work out which ordinals a spec calls for, compare
    that against the note's current gate field values — available in `ObservedNote.fields`, which
    is fetched on every run and consumed by NOTHING today — flag the difference, un-flag on
    widening, and say so in the report.

    **Cloze is out of scope and must say so.** A cloze note's card count comes from its CONTENT,
    not from gate fields, so "which ordinals should exist" is not answerable the same way. Retiring
    a cloze deletion is the separate, still-open ordinal-drift problem.

    **One correspondence needs pinning by a test.** `{{^ValueOnly}}` and `{{#ThreeWay}}` OPEN their
    front templates and wrap them entirely, which is what makes them card gates; `{{#Context}}` and
    `{{#ConceptLabel}}` sit inside wrapping display fragments and are not. That distinction lives
    only in the template text today, so a template edit could silently change which cards exist —
    and this feature would then flag the wrong card.

24. ~~**A NOTE-TYPE MOVE SHOULD APPLY BY DEFAULT.**~~ **BUILT 2026-08-27**, in four commits: the
    default flipped and the flag inverted to `--no-migrate-note-types`; the run gained a `MOVED`
    block naming every note it moved and between which note types; a dry run gained the matching
    `WOULD MOVE` block; and `README.md` gained a section, having previously never mentioned the
    operation at all.

    **Two things learned in the building, both worth carrying forward.** The executor did not know
    what it had applied — the function that ran each action returned only the failures — so the
    field had to be added rather than derived from "the plan minus the deferred minus the failed",
    which is the catch-all shape this codebase keeps removing. And flipping the default caused a
    REGRESSION nobody would have caught by reading the diff: notes that used to be reported by the
    deferred block moved into the applied path, where the dry run said nothing about them, so the
    preview for the largest write the tool makes went quiet exactly as that write became automatic.
    It was found by running the tool against the real vault afterwards. _Original entry follows._

24b. **A NOTE-TYPE MOVE SHOULD APPLY BY DEFAULT.** Ruled by Marc 2026-08-27. Not built.

    **What changes.** `sync` moves a note onto the note type the vault asks for without being told
    to. `--migrate-note-types` inverts into `--no-migrate-note-types`, kept as a brake rather than
    deleted — Marc chose "apply by default" over "remove the flag entirely", so the ability to opt
    out of a restructuring you can see coming in a dry run survives.

    **Why it is safe, which is the part that would be lost if only the decision were recorded.**
    The shape gate runs regardless of policy: under `RetypePolicy.Apply`, `Retyping.verdictFor`
    still calls `refusalFor`, which still refuses shrinking and still refuses crossing cloze-ness.
    **So the flag now gates ONLY moves that have been measured lossless.** It stopped protecting
    anything on 2026-08-26, when growth was measured — cards keep their ids, intervals, ease, reps
    and review logs — and the gate was narrowed to shrinking. A flag whose whole remaining effect
    is to withhold a proven-safe operation is a flag that has outlived its argument.

    **The original argument, for the record, because it was a good one.** `changeNoteType` blanks
    every field and replaces the whole tag set before writing them back, a single run can carry
    hundreds, and doing that as a side effect of a routine reconcile is a structural change to
    somebody's collection nobody asked for. That reasoning was correct when the operation was
    unmeasured. What survives of it is milder and is about SURPRISE rather than loss: a sync that
    silently restructures many notes is startling even when it is right — which argues for
    reporting loudly, not for refusing.

    **What it costs, and therefore what must be built with it.** The report needs a block naming
    every note moved and what it moved between, replacing the current "NOT DONE — ask with
    `--migrate-note-types`" block, which stops being true. Without that, a run would restructure
    notes and mention it only as a count.

    **Follow-on, blocked on this.** `README.md` mentions retyping, migration and deferral NOWHERE
    — so the one audience who experiences "I changed a marker and nothing happened" is told
    nothing at all. It must gain a section once this lands, and it should document the behaviour
    that will then be true rather than the behaviour being replaced.

25. **THE ANKI-FACING SEAM IS STRINGLY TYPED, AND THAT IS A MODELLING GAP RATHER THAN A STYLE ONE.**
    _Recorded 2026-08-27. Supersedes item 6, which ranked it "possibly not worth doing"._

    **The three parts, in order of how much they hide.**

    - **A field has no ROLE.** Nothing anywhere says that `Front` on `Obsidian Basic` plays the part
      `Descriptor` plays on `Obsidian Concept-Descriptor`. The word *role* appears **zero times**
      across the eleven markdown documents in this repository. So when a note sits on a note type
      the vault did not ask for, the tool can say a name differs and can say nothing whatever about
      what the note's fields currently MEAN.
    - **A note type is a `String` on both sides** — `ObservedNote.noteType` and
      `CardSpec.noteTypeName` — compared with `!=`. The comparison is right and the type is a bag
      of characters at the point where the domain is richest.
    - **Field names are strings in four places that must agree and are tied together only by
      tests**: the manifests under `resources/note-types/`, the template text (which references
      them in Anki's own language, type-checked by nothing), `CardSpec.fields`, and
      `NoteTypeInstall`'s drift comparison.

    **The counterexample that got it re-ranked.** A heading marked `#flashcard/cdd/2way` sat on
    `Obsidian Basic (and reversed card)` with its DESCRIPTOR in the `Front` slot and its CONCEPT
    demoted to `Context`. Card 2 therefore showed the description and asked for the descriptor —
    not one of the three questions `cdd/2way` promises. Marc reviewed it repeatedly. The run
    reported the mismatch as a note-type name difference, which is all it could say, and told him
    "Leaving them is safe" — true about his data and false about his reviews.

    _The underlying note-type mismatch is fixed (item 16) and the gate is widened. What is NOT
    fixed is that the tool had no vocabulary for the state, which is why it could not describe it._

    **A FOURTH PART, ADDED 2026-08-29 AFTER IT COST SOMETHING.** A note's FIELD SET travels as
    `Vector[(String, String)]` — measured, 23 production signatures and 11 in tests: the card
    spec, three `SyncAction` cases, the `Anki` port, both implementations, the wire client, and
    the card-generation rules.

    **What it cost.** The in-memory collection let `addNote` create a note holding FEWER fields
    than its note type declares — a state Anki cannot represent, since a note's fields ARE its
    type's list and adding a field to a type gives every existing note that field, empty. A test
    then modelled "a note synced before the `Identity` field existed" as exactly that impossible
    note, and proved two false things at once: `updateNoteFields` merges over the fields a note
    ALREADY has, so writing the missing one was dropped silently while the modification count
    still moved.

    **There WAS a check, and it was one-sided.** `checkFields` rejects a field name the note type
    does not declare and says nothing about a name the type DOES declare that the caller omitted.
    A check that can be one-sided is a check separable from the value it guards — which is the
    argument for the type rather than for a better check.

    **And the field-ORDER rule is a comment repeated three times.** Anki's `modelFieldAdd`
    appends, so a field declared anywhere but last leaves a repaired collection permanently
    reporting a difference it can never fix. That reasoning is written out at `CardSpec.fields`'
    concept-descriptor arm and twice more in `Marker.FieldOrder` — three copies of a rule nothing
    enforces, which is what a missing type looks like from outside.

    **THE SHAPE OF THE FIX, for this part.** A field set constructible only from a
    `NoteTypeSpec`: the spec supplies the names and their order, the caller supplies values by
    name, anything absent is empty rather than missing. `checkFields` is then DELETED rather than
    made symmetric, because the state it looks for stops existing. Gone with it: a missing field,
    an extra field, a wrong order, a misspelled name at construction. NOT gone: a wrong value —
    worth saying, so the type is not later blamed for something it never claimed.

    **WHY THE ASYMMETRY EXISTS AT ALL**, which is the question underneath all four parts. This
    project already applies boundary discipline once: `content/Content.scala` is a closed algebra
    that exists precisely because Laika's types are open and foreign, so the parse boundary is
    where foreign shapes stop. The same reasoning was never applied to AnkiConnect, whose wire
    format is JSON with string keys — and that shape was carried inward instead of being parsed
    at the edge. Not a principle this repository lacks; a principle it applied on one side.

    **Where the full diagnosis lives, and why that is not good enough.** `docs/PIPELINE-DESIGN.md`
    surveys every site, and its back-end fact table was independently verified — nine of eleven
    rows opened and all nine accurate. But that document opens with a warning telling readers not
    to implement from it, because its RECOMMENDATION failed adversarial review. **A live problem
    parked inside a discredited document is close to not being recorded at all**, which is why it
    is restated here.

    **Not urgent, and say why:** a note type is not part of the key, so unlike identity work this
    costs the same whenever it is done. It does not compete with the now-or-never column.

26. **A DEFERRED RETYPE IS MODELLED AS AN ABSENT ACTION, NOT AS A STATE THE CARD IS IN.**
    _Recorded 2026-08-27._

    `RetypeVerdict` — `WillApply`, `DeferredByPolicy`, `RefusedByShapes`, `ShapesUnavailable` — is
    a verdict about a PLAN. Every case answers "what will this run do?" and none answers "what is
    true of this card right now?". So the tool can say *I am not doing this* and has no way at all
    to say **"this card is currently asking a different question from the one your vault asks."**

    **What that cost.** The run printed `Leaving them is safe: a note that is not moved simply
    stays on the note type it is on`. That sentence is true about the DATA and false about the
    REVIEWS: the card went on asking the wrong question and Marc went on answering it. A reader
    given a reassurance has no reason to look further.

    **The general shape, which is why this is worth recording rather than only fixing.** This
    project keeps finding CONDITIONS THE TOOL CREATES AND HAS NO NAME FOR, and each has been
    solved one at a time without the underlying gap moving:

    - a parked orphan was mentioned by the run that parked it and by no run afterwards (fixed —
      `Report.parkedNote`);
    - a card retired by narrowing has no marker at all (decided, item 23, not built);
    - a note on a note type the vault did not ask for cannot be described beyond a name difference
      (item 25);
    - and this one.

    Each fix taught the REPORT to say one more thing. None of them gave the tool a way to answer
    "what state is this card in?", which is the question all four are instances of.

    **Machinery that already exists and is used for none of it.** `ObservedNote.fields` is decoded
    on every run from the bulk `notesInfo` the observer already makes, and consumed by NOTHING in
    `plan/`. It holds what Anki currently believes — the values that would let the tool compare
    what a card IS against what the vault ASKS, which is the whole content of "what state is this
    card in". Recorded twice already (`docs/EVOLVABILITY.md` §3.7, and item 23 above) and applied
    to neither.

---

## OPEN — requested by Marc, 2026-08-27

28. ~~**A HEADING'S SUBHEADINGS BECOME ONE SEQUENCE CARD.**~~ **BUILT 2026-08-28.** Requested by
    Marc 2026-08-27, shipped the next day in four slices: the marker vocabulary; the outline
    transformation with its laws; the wiring; and a reveal order that was not part of the
    original request. Documented in `README.md` under *Structure* and *A marker on the note
    itself*. _The original entry follows, because its reasoning is still the reasoning._

    **WHAT SHIPPED BEYOND WHAT WAS ASKED FOR.** A nested outline can be revealed either a level
    at a time or branch by branch — `…/recursive/bfs` and `…/recursive/dfs`, with a level at a
    time the default. Both show the SAME list; only the order the reveal key walks it in
    differs, and that is decided by the note type's template at review time rather than by this
    tool reordering anything. It is carried in a new `Reveal` field whose EMPTY value means
    depth-first, which is what made adding it a non-migration: the golden record of every
    fixture card gained exactly one line, an empty field, with no identity and no existing value
    changed.

    **THE TWO OPEN SUB-QUESTIONS BELOW ARE BOTH ANSWERED.** The heading-level lint is item 34,
    parked with its justification corrected. The refusal for a marker with no subheadings reuses
    the existing sequence refusal, on the principle that the failure belongs to the sequence
    card's own precondition rather than to the source of its items.

    **AND TWO DEFECTS CAME OUT OF THE FIRST REAL USE** — items 35 and 36, both since fixed.

    **What it is.** The child headings of a marked heading become the ordered items of a sequence
    card, so the STRUCTURE of a document becomes the thing recalled — a table of contents you can
    be tested on. Two forms: direct children only, and `/recursive` for the whole subtree, nested.

    ```markdown
    ## bar #flashcard/sequence/headers        # foo #flashcard/sequence/headers/recursive
    ### bar1                                  ## bar
    ### bar2                                  ### bar1
    ### bar3                                  #### bar1.1
    ## baz    <- sibling, excluded            ## baz
    ```

    **WHY IT IS SMALL: it is not a new card kind, it is a new SOURCE for one that already works.**
    `#flashcard/sequence` already builds exactly this card — ONE note, ONE schedule, items revealed
    one at a time — from the body's LIST ITEMS. This feeds the same path from CHILD HEADINGS. The
    note type, its templates, the reveal script and the scheduling model all already exist.

    **VERIFIED by reading, 2026-08-27:** the front template's script binds
    `document.querySelectorAll("#text li")` — a DESCENDANT selector, so items nest at any depth are
    hidden. **`/recursive` needs no template change.**
    (`resources/note-types/cloze-sequence/templates/cloze-sequence.front.html`)

    **IT FILLS A HOLE RATHER THAN ADDING A CORNER.** Ruled B6 (`extract/Extractor.scala:653`): a
    section's own body stops at the next heading of ANY level, descendants excluded, so **a marked
    heading immediately followed by a subheading has an EMPTY body — today a hard error,
    `SpecError.EmptyBody`.** Both examples above are exactly that shape, so they refuse today. This
    marker converts a refusal into a card — the same move `#flashcard/sequence` itself made, one
    level up.

    **RULED by Marc 2026-08-27: `EmptyBody` is a rule for SOME card kinds, not all of them.**
    CONFIRMED in code the same day — it is raised in exactly two places, `extract/Extractor.scala:405`
    and `extract/Cloze.scala:113`, each on its own card-kind path. **So nothing has to be relaxed
    and no existing rule weakens: the new path simply does not call it, and the change is purely
    additive.** Claude had framed this as "EmptyBody must relax for this marker", which was wrong.

    **It composes with the sequence contract for free.** That contract is: the body's list items are
    the answer, EVERYTHING ELSE IN THE BODY IS PRINTED ON THE QUESTION SIDE. So prose written under
    the marked heading before its first subheading becomes the prompt, with no new rule invented.

    **NAMING — one spelling, not two.** Marc's request wrote `sequenced/headers`; the existing
    marker is `sequence`. An unrecognised marker fails loudly by design (`MarkerError.Unrecognised`),
    so two spellings of one word in this namespace is a typo generator. Use
    `#flashcard/sequence/headers`.

    ~~**A skip only becomes load-bearing under THIS marker**, where "direct child" stops being
    well-defined.~~ **WITHDRAWN 2026-08-28 — that was wrong, and it was Claude's claim.** Writing
    the reader disproved it: Laika nests every heading inside the nearest SHALLOWER one, so under
    `## bar` a `#### deep` that skipped a level is unambiguously a direct child, and Obsidian's
    own outline pane agrees. The card and the editor show the same tree. **A skipped level causes
    no divergence this feature can hit.** The heading-level lint Marc ruled for is still wanted
    and is now its own item — see 34 — parked because it fixes no defect this feature can reach.

    **RULED by Marc 2026-08-28: the marked heading's PROSE IS NOT THIS CARD'S MATERIAL.** The
    marker asks for structure and prose is not structure, so the blocks this card renders are the
    OUTLINE ALONE — replacing the body rather than extending it. This DIFFERS from
    `#flashcard/sequence`, where the body is the card's material because the author wrote that
    list to be a card, and where non-list prose therefore becomes the question side.

    _Why that is not the silent omission it first looks like, in Marc's words: prose and its
    parent heading may perfectly well be a card of their own — that is exactly what the
    two-field markers make. The prose is not orphaned by this design; it simply belongs to a
    different card than this one._

    **A CONSEQUENCE NOBODY HAS RULED ON.** A heading carries at most one marker, so a heading with
    both prose and subheadings cannot ask for a two-field card AND a structure card. Whether that
    is a limitation worth lifting is open and is NOT part of this item.

    **IT ALSO MAKES THE BUILD SIMPLER, which is why it is recorded here rather than only in a
    commit.** If the card's blocks are the outline alone, they are non-empty whenever there are
    subheadings, so the empty-body gate passes honestly and the existing sequence arm operates on
    them unchanged.

    **NOT DECIDED: what happens when the marker is present and there are NO child headings at all.**
    A one-item sequence card is useless, so this presumably refuses — but which error, and whether
    it reuses `EmptyBody`'s channel or needs its own, is open.

    **Build method, stated by Marc when the item was filed:** functional core / DDD, a
    parser-writer's mindset, **types first and hole-driven wherever the compiler allows it**, then
    tests, then implementation — in that order, per concept rather than per phase.

    **PROGRESS.** Slice 1 (the marker vocabulary) and slice 2 (the outline transformation and its
    laws) are built and green. Nothing calls the transformation yet — the extractor arm that will
    is still `???`. What remains is choosing the blocks before rendering, and the refusal when the
    marker is present and there are no subheadings.

---

## OPEN — ruled by Marc, 2026-08-27, and it outranks item 28

29. ⚠️ **THE TOOL MUST NOT FORBID A NOTE-TYPE SHRINK. IT MUST STATE THE COST AND LET THE AUTHOR
    DECIDE.** Ruled by Marc 2026-08-27, after the same refusal blocked him twice in one session.
    **Nothing is built.** The full reasoning, including the weaker proposal that was rejected on
    the way to this one, is `docs/REVIEW-QUEUE.md` § *Three stances on a refusal*.

    **The ruling in one line.** Changing your mind about your own cards is the AUTHOR's decision,
    made knowingly — not a privilege the tool grants once it judges the stakes low enough.

    **THE REJECTED PROPOSAL IS THE INSTRUCTIVE PART.** Claude proposed making the gate
    evidence-based: refuse a shrink only when the cards actually hold reviews. It is a strictly
    better rule than today's, it unblocks the common case, and **Marc rejected it** — because it
    keeps the tool FORBIDDING and merely moves the threshold. The general form is worth carrying
    to every other gate in this codebase: **when a tool refuses, the question is not only "is the
    threshold right?" but "who is entitled to decide?" — and tuning a threshold answers the first
    while silently keeping the second.**

    **WHY IT CANNOT SIMPLY BE SWITCHED ON, and this is the whole of the remaining work.** The tool
    cannot state the cost because nobody has established it; the refusal's own text admits as much.
    An "apply anyway" meaning *"proceed, and I cannot tell you what happens"* would be WORSE than
    today's refusal, not better — it violates `REVIEW-QUEUE.md`'s founding rule that nothing is
    applied without its cost shown, and it fails in the worse direction because it LOOKS informed.

    **So the sequence is fixed, and M4 is the critical path:**
    1. Run **M4's shrinking half** (`docs/EVOLVABILITY.md`) — an hour in a throwaway profile.
       `Check Database` afterwards is not optional: *orphaned* and *destroyed* look identical
       without it.
    2. Build the third state — *apply anyway, with the cost shown* — which is
       `REVIEW-QUEUE.md`'s row 2 and has never existed.
    3. Only then is the refusal gone rather than relocated.

    **MEASURED 2026-08-27, and it is why this outranks item 28.** `Database Scaleability.md` had
    both headings turned down from `cdd/3way` to `2way`. The tool read the vault correctly —
    `inspect` reports `Obsidian Basic (and reversed card)` for both — and refused the move twice,
    under `SOME ACTIONS FAILED`. **The six cards it protected hold 0 reviews, 0 lapses and 0
    interval between them.** Item 28 is a feature Marc WANTS; this is a defect that STOPS him.

    _Three presentation defects were identified in the same output and are NOT covered by the
    ruling above, because fixing them would not unblock anything — they are worth their own entry
    if this one is not taken whole: a principled refusal announced as `SOME ACTIONS FAILED` /
    `PROBLEMS`; the raw case-class `UnsupportedOperation(what,why)` toString reaching the terminal;
    and the same 400-character reason printed once per affected note, burying the remedy._

---

## OPEN — left behind by the priced-decision work, 2026-08-28

_All three were noticed while building `--approve` and recorded here rather than left in a
comment. Two of them were referred to as "filed" before they were, which is the reason for this
section: a pointer to a document that does not mention the thing is worse than no pointer._

30. ~~**A LAW LOST ITS ONLY TEST: "a failing action does not abort the remainder of the plan".**~~
    **FIXED 2026-08-28**, the day it was found. The test now sits beside the one that replaced
    it, so the pair reads as the two halves of one concern: an action set ASIDE, and an action
    that genuinely RAISES.

    **THE FAILURE IS REAL RATHER THAN INJECTED, which is better than the route this item named.**
    Anki refuses a note whose first field duplicates an existing one, and the collection in the
    test is built with that refusal on — so the middle action fails through exactly the path a
    real duplicate takes. An injected fault could only have shown that the executor survives
    errors it was handed, not errors the collection itself produces. It also avoided writing a
    twenty-five-method delegating double, the in-memory collection being final.

    **THE POSITION OF THE FAILING ACTION IS ASSERTED.** Planned last, it would have nothing after
    it, every remaining assertion would hold, and the test would prove nothing. _Original entry
    follows._

    **The invariant.** `Executor.applyEach` runs a plan's actions in sequence against a live
    collection. One action raising must not abandon the ones after it — otherwise a single bad
    note leaves the rest of a vault unsynced until that note is fixed.

    **How the coverage was lost.** The test in `plan/Planner.test.scala` built three actions and
    rigged the middle one to fail. The rigged failure was a NARROWING, and since 2026-08-27 a
    narrowing does not fail: `Executor.run` prices it and partitions it out BEFORE execution, so
    it never reaches the code that could abort. The test still passes and no longer contains a
    failure at all. It was retargeted to cover the partition instead — that setting one action
    aside must not drop its neighbours, which is a real risk of how the action list is rebuilt
    and is otherwise untested — and its docstring says so.

    **Why it was not simply restored.** After the split of 2026-08-27, the only thing that still
    RAISES from a retype is a cloze-kind mismatch, and `Planner.test.scala` has no cloze
    fixtures: seeding a note onto a cloze note type there is new setup rather than a rename.
    Injecting a fault is the other route — `NoteTypeRepair.test.scala` and
    `ExecutorInterruption.test.scala` both already have doubles that do it — and neither was
    tried.

    **CHECKED 2026-08-28: no other test covers it.** `ExecutorInterruption.test.scala` tests
    convergence after an interruption, which is a different property.

31. ~~**THE IN-MEMORY COLLECTION DECIDES A NOTE'S CARD COUNT FROM THE NOTE TYPE'S NAME.**~~
    **FIXED 2026-08-28.** The count now comes from the note type's SPEC, through a new
    `anki/CardGeneration.scala` that models Anki's own rule: a template makes a card when at
    least one real field on its front is non-empty once conditional sections are resolved.

    **THE OBVIOUS FIX WAS AVOIDED, as this item warned.** Counting templates would have been
    wrong: two fronts in this repository are wrapped in conditionals that open in OPPOSITE
    directions, so a rule treating every section as "present means render" passes a test about
    one gate while failing the other. Fifteen tests, most against the real template files rather
    than imitations of them, including one asserting every ungated front renders for an ordinary
    note — the more dangerous direction, since a rule that was too strict would make the tool
    price a change as destroying cards that never existed.

    **CLOZE IS COUNTED AS ANKI COUNTS IT**, one card per distinct ordinal, where the fake used to
    answer one for every cloze note — so ordinal drift, the failure the cloze redesign is
    organised around, could not be reproduced against it at all. _Original entry follows._

    `InMemoryAnki.cardCountOf` matches on the note type's NAME — `Basic (and reversed)` gives 2,
    `Concept-Descriptor` gives 2 or 3 depending on a gate field, cloze gives 1, **and anything
    else gives 1** — rather than reading the `NoteTypeSpec` it was handed. So a note type defined
    in a test with three templates yields a note with ONE card.

    **WHY IT MATTERS BEYOND TIDINESS.** A test seeding a locally-defined multi-template type and
    asserting about its cards is testing something other than what it appears to. That happened
    on 2026-08-28: a test seeded `stockWide`, which declares three templates, expected three
    cards and got one. The test was pointed at a named note type instead and a comment left
    explaining why, which is a workaround rather than a fix.

    **The obvious change is not obviously right.** Deriving the count from the spec's template
    list would be wrong too: real Anki generates a card per template whose FRONT renders
    non-empty, which is why the concept-descriptor gate field exists at all. Modelling that
    properly means the fake evaluating templates, which is a bigger thing than it sounds.

32. **TOOL ARTEFACTS ARE SITTING UNTRACKED IN THE REPOSITORY, and one would be committed.**
    Found 2026-08-27. **Not mine to fix** — flagged for whoever owns them.

    `.serena/` is a language-server cache; it wants to be in `.gitignore` rather than committed.
    `addon/obsidian_edit/__pycache__/` holds compiled Python bytecode, and `addon/` IS now
    tracked, so a later `git add` of that directory would put `.pyc` files into the history. The
    `.gitignore` work done on 2026-08-27 covered `meta.json` — which Anki writes into the
    repository through the installed add-on's symlink — but not either of these.

33. **~~A PRINCIPLED REFUSAL IS ANNOUNCED AS A FAILURE.~~ FIXED 2026-08-27**, superseding the
    first of the three presentation defects listed at the end of item 29 above. **The other two
    stand, and item 29's closing paragraph is therefore stale.**

    **STILL OPEN: the raw case-class `toString` reaches the terminal.**
    `cli/Main.scala:1131` prints `${f.error.toString}` for a failed action, and five other sites
    print `${error.toString}` under `Anki's answer:`. For `AnkiError.UnsupportedOperation(what,
    why)` that renders Scala's derived form — both constructor arguments joined by a comma, with
    the type name wrapped round them — which is why Marc's report on 2026-08-27 contained
    `UnsupportedOperation(move 'reads' from ...,'Obsidian Concept-Descriptor' has 3 card
    template(s)...)`. **The fix is a `describe` on `AnkiError` rather than a change at each call
    site**, so that a sixth site cannot reintroduce it.

    **STILL OPEN, though much reduced: one long reason printed once per affected note.** Two
    notes failing for the same reason printed the same four hundred characters twice. Narrowings
    no longer take this path at all, so the remaining case is a cloze-kind mismatch across
    several notes — rarer, and not measured.
---

## PARKED — wanted, but fixing no defect yet, 2026-08-28

34. **A HYGIENE LINTER FOR SKIPPED HEADING LEVELS.** Ruled desirable by Marc 2026-08-27, parked
    by him 2026-08-28 after the justification originally offered for it turned out to be wrong.
    **Nothing is built.**

    **What it would report.** A heading that jumps more than one level below its parent — `##`
    followed directly by `####` — anywhere in the vault.

    **WHY IT IS NOT URGENT, AND THIS IS THE CORRECTION.** It was filed under item 28 on the claim
    that a skipped level makes "direct child" ambiguous for `#flashcard/sequence/headers`. That
    claim was Claude's and it was wrong. **Laika nests every heading inside the nearest SHALLOWER
    one**, so a `####` under a `##` is a direct child of that `##`, with no ambiguity to resolve —
    and **Obsidian's own outline pane nests it identically**, so the card and the editor agree.
    Nothing this feature does is affected.

    **WHY IT IS STILL WANTED — Marc, 2026-08-28.** Laika's behaviour is helpful and probably what
    anyone would want; the concern is not that it is wrong but that it is *forgiving*. A document
    whose levels drift stays readable while quietly diverging from what its author believes it
    says, and an occasional lint-and-tidy pass is cheap insurance against that surfacing later as
    something surprising.

    **IT IS AN OPTION, NOT A GATE — ruled by Marc.** It must not refuse a sync. A rule that
    refuses notes needs a defect to point at, and this one has none to offer.

    **MEASURED 2026-08-27:** 23 notes in the live vault, **zero** skipped levels. So it would
    report nothing today, and building it later costs no more than building it now — which is
    what makes parking it safe rather than merely convenient.

---

## OPEN — found by using the feature, 2026-08-28

35. ~~**A WHOLE-NOTE STRUCTURE MARKER IS REFUSED ON EXACTLY THE NOTES IT IS FOR.**~~
    **FIXED 2026-08-28**, the day it was found. A marker now answers what a whole-note card made
    from it would consume — the note's prose or its structure — and the walker matches on that
    answer instead of asking whether there is some marker and no headings. Both branches carry a
    symmetric requirement: a prose marker needs no headings, a structure marker needs some.
    Because the match is exhaustive and this project treats an inexhaustive match as an error, a
    marker added later cannot avoid deciding. Three tests: the note Marc actually wrote, its
    mirror, and the Obsidian accident the old guard existed to catch, which must still be caught.
    _The original entry follows; its account of the cause is the whole lesson._

    **What happens.** A marker may be written in a note's frontmatter `tags:` instead of on a
    heading, in which case it applies to the WHOLE NOTE and the file name becomes the card's
    title. `extract/VaultWalker.scala:492` gates that on:

    ```scala
    case MarkedHeadings.Absent
        if frontmatterMarker.isDefined && hasNoHeadings(doc.content) =>
    ```

    So a whole-note card is built only when the note has **no headings at all**. A note with
    headings gets `BuildFailure.MarkerNotOnHeading` instead, advising the author to move the
    marker onto a heading.

    **WHY THAT GATE IS RIGHT FOR EVERY MARKER THAT EXISTED BEFORE THIS ONE.** Typing
    `#flashcard/3way` into the Obsidian editor makes it lift the tag out of the body and file it
    under `tags:`, leaving a note that LOOKS marked and produces nothing. Those markers build a
    card from the note's PROSE, so headings being present means the prose is fragmented and the
    marker probably fell off one of them. The refusal is the tool catching a real accident.

    **WHY IT IS BACKWARDS FOR THIS ONE.** A whole-note `sequence/headers` card is made OF the
    headings — they are its items. It cannot work on a note without them. So its correct use is
    indistinguishable, under this gate, from the accident the gate exists to catch, and the
    advice it prints is advice the author must not follow.

    **THE FUNCTION ALREADY WORKS; ONLY THE PATH TO IT IS CLOSED.** `Extractor.fromWholeNote`
    wraps the document in a synthetic section so every marker runs through the heading code, and
    two tests added 2026-08-28 assert it produces the right card from Marc's actual note. The
    walker simply never calls it for a note with headings.

    **THE TYPE-LEVEL CAUSE, WHICH IS THE PART WORTH FIXING RATHER THAN PATCHING.** The guard
    asks `frontmatterMarker.isDefined` — *is there SOME marker* — never *which*. The rule it
    encodes is marker-DEPENDENT and the code cannot see that:

    | marker | a whole-note card consumes | headings present means |
    |---|---|---|
    | `2way`, `cloze`, `sequence` | the note's PROSE | fragmented — suspicious |
    | `sequence/headers` | the note's HEADINGS | required |

    So the marker should answer what it reads, and the guard should be an exhaustive match over
    that answer rather than a boolean conjunction — at which point both branches carry a
    symmetric requirement (prose markers need NO headings, structure markers need SOME) and a
    marker added later cannot avoid deciding.

    **AND THAT IS HOW THIS WAS POSSIBLE AT ALL**, which Marc asked and which is the general
    lesson: the compiler can only force a decision where there is a match to be exhaustive over.
    A boolean guard folding two questions together is invisible to it — **a decision with no
    author**, the same defect class as the default-parameter gate in `rules/`, one level up.
    There the missing author was at a call site; here it is at the type.

36. ~~**`MarkedHeadings` IS MISSING A CONSTRUCTOR, AND ITS OWN DOCSTRING SAYS SO.**~~
    **FIXED 2026-08-28**, alongside item 35. The two situations its comment described in prose
    are now separate cases, and each carries the parsed document — which removed the
    `parsed.toOption.get` a few lines further down, since the branch can be handed the value it
    had arrived through a guard to inspect. _The original entry follows._

    `extract/VaultWalker.scala:324` declares three cases — `Present`, `Absent`, `CouldNotLook` —
    and the comment above the guard that uses it reads:

    > `MarkedHeadings.Absent` covers two situations that look alike and are not: a note WITH
    > headings none of which is marked — the Obsidian accident — and a note with NO headings at
    > all, where the frontmatter is the only place a marker could live and the note is a leaf.
    > Only the first is a mistake.

    **That is a prose description of a missing constructor.** The distinction is real, it is
    load-bearing, and it is drawn by a boolean in a pattern guard rather than by the type — so
    nothing forces a reader to notice it and nothing forces a new branch to handle both.

    **A SECOND SYMPTOM OF THE SAME GAP, and evidence it is not merely tidiness.**
    `VaultWalker.scala:502` calls `parsed.toOption.get`. That branch KNOWS the parse succeeded —
    it got there through a guard that inspected the parsed document — but the type does not
    carry that knowledge, so the value is re-extracted with a partial function. A case that
    CARRIED its parsed document would make the `.get` impossible rather than merely unnecessary.

    **RICHER THAN AN ENUM, per Marc 2026-08-28.** Scala's sums are sums of PRODUCTS: each case
    may carry exactly the evidence that case has. So the fix is not four bare cases but cases
    holding what their branch needs. That is where the real gain is here — not in phantom or
    refined types, which would be cargo-culted in this spot: a marker is parsed from a string at
    runtime, so indexing the type by what it reads would produce an existential that has to be
    unpacked by a match anyway, at which point the match IS the mechanism.

37. ~~**A NEAR-MISS TAG PRODUCES TOTAL SILENCE.**~~ **FIXED 2026-08-28**, the day it was found.

    **IT LANDED IN THE SYNC PATH AFTER ALL, and this item's own guess that it belonged with the
    parked hygiene tooling was wrong.** That guess was made about a FUZZY check. The rule built
    is exact — everything after a tag's first segment matched against the tails this tool
    documents — so it has no threshold to tune, cannot fire on an ordinary tag, and belongs
    exactly where the mistake is made. The standing rule that fuzzy matching may rank but never
    decide is untouched: an exact match on the tool's own published vocabulary is not fuzzy.

    **ONE TYPE, NOT TWO CHECKS.** Reading a tag now answers with a case — a marker, a recognised
    prefix with an unknown token, a near miss, or none of our business — so neither of the two
    silent holes can return by being left out of a predicate. Both were the same shape and both
    are closed.

    **A THIRD DEFECT WAS FOUND BY RUNNING THE TOOL rather than reading it.** A tag with the right
    prefix and a wrong token tripped the new check AND the old one, so the author got the
    accurate message followed by advice to move a marker whose only problem was its spelling. The
    precise message now suppresses the general one. _Original entry follows._

    Marc's frontmatter read `flashard/sequence/headers` — one character short of `flashcard`.
    Nothing was reported. Two separate checks both miss it, and both for the same reason:

    - the marker filter keeps tags where `_.toLowerCase.startsWith("flashcard")`;
    - the did-you-mean check, whose entire job is to say *"this note declared intent and made no
      cards"*, tests `frontmatter.toLowerCase.contains("flashcard")`.

    **So the one check written to catch this class of mistake is defeated by a misspelling of
    the very string it searches for.**

    **WHAT COULD BE REPORTED WITHOUT GUESSING.** A tag whose TRAILING segments exactly match a
    marker this tool documents, while its leading segment is not `flashcard`. That is an exact
    match against the tool's own published vocabulary — no edit distance, no threshold, no claim
    about intent. `math` never trips it; `flashard/sequence/headers`, `flashcards/2way` and
    `flash/cloze` all trip it immediately.

    **AND WHERE FUZZY MATCHING IS LEGITIMATE HERE, which is worth stating because this project
    rules against it elsewhere.** The standing rule is that fuzzy matching may RANK but never
    APPLY — it must not silently decide which card is which. A tool whose only output is *"did
    you mean `flashcard/sequence/headers`?"* ranks and hands the author the choice, so edit
    distance and thresholds are fine here, ruled acceptable by Marc 2026-08-28. It stays OUT of
    the sync path and beside the heading-level linter as an opt-in pass.
