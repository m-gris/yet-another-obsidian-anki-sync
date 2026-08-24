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

3. **`anki/NoteTypeInstall.scala:356-380` — four independent drift probes.** `NoteTypeDrift` has
   four cases, each consumed by its own `collect` / `collectFirst` / `.contains`. A fifth case
   (`SortFieldDiffers` is the obvious candidate) matches no probe, so `actions` is empty, the note
   type lands in `unchanged`, and the report prints "REPAIR: nothing needed changing" for a note
   type that differs. Partly mitigated by accident — `repair` re-reads the collection, so the
   report contradicts itself rather than lying outright. Fix: one total `dispositionOf(drift)`
   returning Refuse / Fix / LeaveAlone, folded over the drift list.
4. **Catch-alls over `NoteTypeStatus` at three sites** — `NoteTypeInstall.scala:249-252` and
   `:445-448`, and `cli/Report.scala:126-130`. A fourth status would be answered `true` by
   `isClean`, `Vector.empty` by `remainingDrift`, and nothing at all by the report: a run
   reporting clean over a collection it has just said it cannot classify. `NoteTypeStatus`
   already carries `def asset` and `def name` as abstract members satisfied per case; it should
   carry `def drift` the same way.
5. **`extract/VaultWalker.scala:264` folds an `Either` to a `Boolean`,** collapsing three states
   into two — unparseable, parsed-with-no-marker, parsed-with-marker. A file whose frontmatter
   names `flashcard` and whose markdown does NOT parse is told "no HEADING carries a marker", a
   claim the tool never established. **THE ONLY REMAINING FIX THAT CHANGES BEHAVIOUR.** Check
   `dummy-vault` and `hostile-vaults` for a fixture exercising the wrong message before landing.
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
16. **Marc's vault still has one note on the wrong note type** — `3 components`, on
    `Obsidian Basic` while the vault now asks for `Obsidian Concept-Descriptor`. See item 2.

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
