# Handoff

_Written for a future session with no memory of this one. Transient working document — delete it when v0 ships. Everything here is either verified or explicitly marked as an assumption._

---

## Read this first

You are building **a standalone Scala 3 CLI that syncs marked headings in an Obsidian markdown vault into Anki**, via AnkiConnect. Not an Obsidian plugin.

**The one design property that everything else serves:** nothing generated is ever written back into the markdown. Card identity is *derived* from `(frontmatter id, heading path)`, and the binding to an Anki note is stored **in Anki** as a `src::` tag. Anki is a derived artifact, so bookkeeping there is free — which is exactly what makes it unacceptable in the source.

**You work with a reviewing agent called `zero`**, over `hcom`. You are `feta`. Report completions and blockers; `zero` reviews, and adjudicates with Marc when a design document must change. Send with:

```bash
uvx hcom send @zero --intent inform --name feta --file <path>
```

Long messages must go via `--file`; the shell has an alias-guard hook that blocks commands containing words like `grep`, `cat`, `diff` even inside heredoc text.

---

## Where things are

| | |
|---|---|
| **The tool** | this repository — its root IS the tool |
| **Design docs** | `docs/` — REQUIREMENTS, CARD-MODEL, LEARNING-MODEL |
| **Fixture vault** | `dummy-vault/` — yours, encodes the design |
| **Hostile fixtures** | `hostile-vaults/` — must fail loudly, or must survive a *wrong* parser |
| **Marc's real vault** | `/Users/marc/srs-in-obsidian-test/` — **READ ONLY, NEVER WRITE** |

_Paths corrected 2026-08-22, when the tool was extracted from the repository it grew up in
(`backend-interview-prep`) into one of its own. The design documents were a sibling directory
there and are `docs/` here. **The dead PoC vault did not come with it** — `poc-obsidian-vault/`
stays behind in the old repository, so references to it anywhere in these documents now point
at something this repository does not contain. It was superseded and to be ignored anyway._

**Three vaults, do not confuse them.** Marc's real vault is *parser-hazard evidence only* — its heterogeneous ids and stray aliases are leftovers from an abandoned experiment, **not** intended design. Never infer conventions from it.

`just test` runs everything, as does `scala-cli test .` from the repository root.
**32 suites, 651 tests, 0 failures, 0 warnings** — measured on 2026-08-24 by running it and
summing the per-suite totals. _The line has said 493, then 511; the suite keeps growing, so
treat the number as a reading rather than a fact and re-measure before quoting it._

---

## State: what is done

Everything except orphan suspension, a per-write field-name preflight, and the `prune` command
— see *Open items*. The reading and writing paths both work end to end against a real
collection.

⚠️ ~~**Nothing in this tool has touched a live collection since the note types were renamed on
2026-08-21.** `install-note-types` and `sync --migrate-note-types` have both been driven only
against the in-process fakes.~~ **CONTRADICTED — do not act on the struck-through paragraph.**
_Flagged 2026-08-21._ `IN-FLIGHT.md` records the whole sequence as having been run against
profile `claude-POC-test`, with before/after measurements (43 notes, 0 card ids changed, 0
scheduling moves, 21 notes retyped). The two documents cannot both be right and `IN-FLIGHT.md`
is the later one. **NOT RE-VERIFIED HERE:** nothing in this session talked to a collection, so
which is true was established by reading `IN-FLIGHT.md`, not by measuring. Read that file
before you believe either.

The live sequence, in order, is: hand-rename the two note types in Anki's Tools → Manage Note
Types → `install-note-types` → `install-note-types --repair` (this is what adds `Context` and
rewrites the templates; the by-hand field addition the old text called for is not needed) →
`sync --dry-run` → `sync --migrate-note-types`.

- `parser/ObsidianSyntax.scala` — Obsidian dialect (wikilinks, embeds, highlights, task-list rejection) and **the canonical `markupParser`**. Build parsers only from there.
- `model/` — `CardKey`/`TagCodec` (identity + tag encoding), `Marker`, `CardSpec` (**five** cases: `TwoField`, `ThreeField`, `Cloze`, `TableRow`, `Sequence`).

  **DO NOT COUNT MARKERS IN PROSE HERE.** This line carried a hand-maintained tally that was wrong twice. `Marker.Documented` is the list, `--help` prints it, and `model/Marker.test.scala` reads the `case` literals out of the source and fails the build if the two disagree. Read those, not this.

  **THE MARKER VOCABULARY CHANGED ON 2026-08-24.** `Nway` counts RETRIEVAL DIRECTIONS everywhere, and the ceiling is a property of the SHAPE — front-back has two fields and so at most two directions, concept-descriptor-description has three fields and so three. `#flashcard/3way` broke that rule: it used a direction word to select a shape and then produced TWO cards, needing `/all` for a third. The shape is now named: `#flashcard/cdd/{1,2,3}way`, mirroring `table`, which was always coherent. Front-back stays unprefixed — it is what you get when you name no shape. `3way` and `3way/all` remain as ALIASES to the same values, so rewriting a vault's markers changes no key, no note type and no field, and syncs nothing.
- `anki/` — the `Anki[F]` algebra; `InMemoryAnki`, a working fake that **enforces Anki's real constraints**; and, since 2026-08-19, `AnkiConnectClient` over http4s/Ember plus `FakeAnkiConnect`, an in-process fake AnkiConnect **server** that reproduces the traps rather than the happy path.
- `plan/` — `VaultScan`, `SyncAction`, `Planner`, `Executor`/`Observer`.
- `extract/` — `Frontmatter`, `Extractor`, `Tables`, `Cloze`, `VaultWalker`.
- `cli/` — `inspect`, `sync` and `install-note-types`. `sync` has run against a real collection: it creates, updates, refuses an inconsistent vault before writing, and a second run reports the collection already matches the vault. `install-note-types` (added 2026-08-21) creates this tool's five note types from `resources/note-types/`, and with `--repair` also adds missing fields and overwrites templates and styling. `sync` refuses before observing when those note types are missing or lack a field it writes.
  `sync --migrate-note-types` (added 2026-08-21) additionally moves notes that sit on a DIFFERENT note type from the one the vault asks for — which since the rename is every note synced before that date. Without the flag those notes are left alone and reported as *deferred*, which makes the run exit 1 rather than 0.
  _Two sentences here claiming these commands "have NOT been run against a live collection by any agent" were removed on 2026-08-21: `IN-FLIGHT.md` says they have. See the contradiction flagged above; nothing in this session measured a collection either way._

Try it: `scala-cli run <tool dir> -- inspect --vault-path <tool dir>/dummy-vault` → 12 files,
55 notes, 2 expected failures, 3 deliberate duplicate keys, `scan: complete`, exit 2.
_Corrected 2026-08-21 by running exactly that command and copying what it printed; the line
previously said 53 cards and 1 failure. Corrected again 2026-08-22: the tool itself printed
`cards:` for a count of NOTES, and now prints `notes:`. One note carries as many cards as its
note type has templates — measured against `claude-POC-test`, 43 notes and 82 cards._ The two failures are both fixtures doing their job: the under-indented
nested list in `Patterns/Shallow-Nesting.md`, and the descriptor-less table in the first section
of `Patterns/Table-Edge-Cases.md`.

**THE VAULT IS NAMED BY A FLAG, NOT POSITIONALLY** — that changed on 2026-08-20, and the old
positional form now fails. With NO vault flag the tool lists the vaults Obsidian knows about and
asks; with no terminal to ask at, it refuses. `dummy-vault` is a registered Obsidian vault, but
do not rely on the picker to reach it — name it with `--vault-path`.

**`sync` cannot write to `dummy-vault`'s collection**: the fixture contains deliberate duplicate
identities, and duplicates are fatal by design. To exercise the write path, copy the vault
without `Patterns/Table-Edge-Cases.md`.

⚠️ **Before you point `sync` at a collection that already holds table cards, read open item 1
below** — one refusal inside a `#flashcard/table` section sends every card that table already
produced to the orphan list.

---

## The AnkiConnect interpreter, and the four hazards it encodes

_Built 2026-08-19 (`47c3ad4`, hardened in `7981a95`) — this section is no longer a task, but every constraint below still governs any change to it._ `Anki[F]` over HTTP against `localhost:8765`. **Four hazards, all verified live.** Two are MITIGATED — behaviours you must implement correctly. Two are ELIMINATED — and their entries say what **not** to build, because defending a path that no longer exists is dead code that reads as diligence.

1. **MITIGATED — write surgically: `updateNoteFields(fields)` → `removeTags(oldSha)` → `addTags(newSha)`.**

   _Superseded 2026-08-19. This entry previously read "Use `updateNote`, NEVER `updateNoteFields`". That is now wrong and actively dangerous — follow the ordering above instead._

   ⚠️ **`updateNoteFields` names two different things — do not conflate them.** Our *algebra method* `Anki[F].updateNoteFields` is implemented over AnkiConnect's **`updateNote` action, passing no `tags` key**. Calling AnkiConnect's `updateNoteFields` *action* instead would reintroduce the bug below.

   Both halves of the original finding hold: the **`updateNoteFields` action silently discards** its `tags` parameter (`error: null`, clean exit), and the `updateNote` action writes both. But `updateNote` **replaces the entire tag set** when a `tags` key is present, verified live:

   ```
   initial            [leech, marc-put-this-here, src::t1]
   tags=[ours only] → [sha::dead, src::t1]      ← foreign tags DESTROYED
   no tags key      → preserved
   tags=[]          → []                         ← note becomes unenumerable
   ```

   `leech` is applied by Anki's own scheduler. Writing the whole tag set would make destroying it **mandatory on every update**, on the happy path — worse than the bug it closes.

   The interpreter uses the third option in that table — **`updateNote` with no `tags` key at all** — which is strictly better than either thing this entry originally proposed: it cannot drop tags, because it never passes them, and it cannot replace them, because omitting the key preserves them.

   Surgical never writes the whole tag set, so it is *structurally incapable* of clobbering a foreign tag. And every interruption self-heals:

   - interrupted after fields → old sha still present → next sync sees a mismatch → retries
   - interrupted after `removeTags` → no sha at all → treated as changed → retries

   **Fields first, sha last.** The inverse ordering — sha first — strands the note permanently: it holds old content under the new hash, the planner computes `markdown hash == recorded hash`, emits no `Update`, and nothing ever reports it. That inversion was live in `Executor` and is fixed in `717d899`; the ordering is now covered by a test that asserts on the **fields stored in Anki**, never on the next plan being empty — because in the broken state the next plan *is* empty.

   Residual, hence MITIGATED not ELIMINATED: a stale observation can leave two `sha::` tags. More than one `sha::` reads as "cannot claim unchanged", so the note is rewritten and the extra is cleared. Converges rather than being prevented.
2. **MITIGATED — tags go inline on create AND on retype.** `addNote` takes them inline. `updateNoteModel` **wipes all tags unless you pass them in the same call** — leaving a note with no `src::` tag, which is not merely unmatched but *unenumerable*: invisible to lookup, reconciler and prune, permanently.

   _Extended 2026-08-21, when the retype half was built._ Read out of the add-on's own source on this machine (`__init__.py:849-899`), `updateNoteModel` is destructive in **three** ways, not one, and each is now covered by a test against both fakes:

   - `anki_note.tags = note.get('tags', [])` is unconditional, so an omitted `tags` key ERASES rather than preserves — the OPPOSITE of `updateNote`, which is the trap: the two actions look alike and their tag semantics are inverted.
   - `anki_note.fields = [''] * len(new_model['flds'])` blanks every field first, so the whole field set must be sent or fields are silently emptied.
   - a field name the new note type does not have is silently ignored (case-insensitive match, no `else`), so a typo is an empty field with no error anywhere.

   `SyncAction.Retype` therefore carries the whole field set plus the tag set split into `ownedTags` (minted) and `preservedTags` (echoed verbatim from the observation). One consequence worth knowing: because the write replaces everything at once, it is ATOMIC — unlike an `Update`, there is no window in which the note holds new content under a stale hash, so none of hazard 1's ordering rules apply to it.

   **A fourth thing about it is NOT mitigated and is instead refused.** A card's ordinal means a template index on a standard note type and a cloze number on a cloze one, and nothing here establishes what Anki does with a card whose ordinal the new note type cannot generate — `Collection.update_note` is one line into the compiled Rust backend. `plan/Retyping.scala` admits a move only when both note types are the same kind AND declare the same number of templates, which is the region where the question cannot arise; everything else is refused by name, pointing at Anki's own Change Note Type dialogue. Measuring the behaviour once in a throwaway profile is what would widen that gate.
3. **ELIMINATED — never call `cardsInfo`. Do not build the pre-check.**

   _Superseded 2026-08-19. This entry previously read "a single broken card poisons a whole batch read… needs handling and probably a pre-check."_

   The finding was real but mis-scoped. Poisoning is a **`cardsInfo` property, not a batch-read property** — `notesInfo` is not poisoned. And `cardsInfo` need never be called at all: `cardsOf` comes from `notesInfo`'s own `cards` array (zero extra calls), and `deckOf` from `getDecks`.

   The reconciler's bulk read is `notesInfo`, so it was never exposed. Eliminated by not making the call, which is why **the pre-check must not be built** — it would be dead code defending a path that no longer exists.

4. **ELIMINATED — same root cause. Do not build the sanitiser.**

   _Superseded 2026-08-19. This entry previously read "AnkiConnect emits unescaped control characters… tolerate it on the read path."_

   The raw `\x00-\x1f` appears in **rendered question/answer HTML**, which only `cardsInfo` returns. With `cardsInfo` never called, the bytes never reach the decoder.

   One thing from this hazard **does** carry, and it is the important half: **AnkiConnect reported the failure correctly** — `error` non-null, `result` null. The wire was never silent. Any silence would be entirely ours, in reading `null` as empty. So the envelope decodes to a sum type and there is **no `result.getOrElse(Vector.empty)` anywhere**.

   Keep the *could-not-enumerate* distinction regardless of this specific hazard dying: it guards a class of failure, not an instance.

Also settled: **scheduling survives a retype** (interval/reps/queue unchanged), so `Retype` need not be delete-and-recreate. And `allowDuplicate: true` is genuinely honoured — without it every concept's *second* descriptor would be rejected as a first-field duplicate.

---

## Traps that have already cost time — read before touching a test

**`sys.props("user.dir")` IS THE SHELL'S WORKING DIRECTORY, NOT THE PROJECT'S.** Four tests
located repository files by walking up from it, and on 2026-08-24 all four were measured
resolving files under `backend-interview-prep/obsidian-anki-custom-sync/` — a STALE COPY of this
entire tool, left behind when it was extracted on 2026-08-23. That included the GOLDEN TEST,
which was comparing a stale vault's cards against a stale golden. Every one of them passed,
because the copies were still identical. Use `TestSources.read` / `TestSources.dir`, which anchor
on the compiled test classes and walk up to `project.scala`. **The stale copy still exists**, so
the trap is armed for any new test that reaches for the old idiom.

**REVERT A MUTATION WITH `cp`, NEVER WITH `git checkout`.** `git checkout -- <file>` cannot tell
a one-line mutation from an hour of uncommitted work in the same file. It destroyed work FIVE
times in one session — once an entire enum, and once producing a PUSHED commit whose message
described a fix the commit did not contain, because five of six files were staged and the sixth
had been reverted. Commit first, back up with `cp` to a scratch path, restore with `cp`.

**RE-RUN THE WHOLE SUITE BETWEEN THE LAST EDIT AND THE COMMIT.** Not before the last edit.

**A DRIFT TEST NEEDS A VACUITY GUARD.** Any test that extracts something from a source file and
compares two sets must first assert the extraction found a plausible number of things. The
`--deck-from` token test failed loudly rather than passing by luck only because of
`assert(declared.sizeIs >= 3, "…the extraction is broken, so this test is proving nothing")`.
Without it, two empty sets agree.

**COMMENTS HERE ARE LOAD-BEARING AND NOTHING CHECKS THEM.** On 2026-08-24 three comments in three
files were found asserting the exact inverse of what the code did — a commit had made an `if`
always-false and the compiler, having forced the neighbouring `match` arm to be updated, said
nothing about the `if`. Another comment told future authors that exhaustiveness was unenforced,
written one day before the flag that enforces it landed. When a fix invalidates a comment, the
comment is part of the fix.

**THE COMPILER IS THE FIRST TEST.** `project.scala` ends `-Wconf:msg=exhaustive:e`, so an
inexhaustive match is a BUILD ERROR. A `case _ =>` over a sealed sum opts out of that as
completely as an `if` does, while looking like it did not — that was the entire yield of the
2026-08-23 audit. Before writing a test for "what if someone adds a case", check whether the
compiler already refuses it; twice this week it did, which is the better outcome.

## The methodology that has actually been catching bugs

This matters more than any individual finding.

**Do not trust a green test.** Mutate the implementation and confirm each test *fails*. This has caught two tests that were passing for the wrong reason and would have kept passing with the feature deleted:

- an orphan test whose *setup* used the feature under test, so it poisoned itself;
- a property whose *generator* only produced clean scans, so it never reached the code it claimed to cover.

**A test whose setup uses the feature under test can poison itself, silently.** Always include a control mutant that must *survive*, or you are only proving the harness fails on everything.

**Test slices against each other, not just in isolation.** Three real bugs came from this: the key model against the parser, the key model against the Anki fake, and the planner against fixture-derived specs. Hand-built inputs encode your own assumptions, and the assumption is what needs attacking.

**Assert the rejection fires, never merely that something is absent.** Absence-assertions pass when the code never looks. That is how the `BulletList` bug was caught — a `BulletList` is a `ListContainer`, *not* a `BlockContainer`, so a walker matching on `BlockContainer` silently skipped every list.

**Shrinking is disabled in the property suite, deliberately.** ScalaCheck's shrinker does not terminate usefully on these nested structures, so a failing property *hangs* instead of reporting. A property that cannot report its own failure is worse than none.

---

## Hazards found, and what they have in common

Six so far. **Every one produced plausible output instead of failing.**

| | |
|---|---|
| HOCON frontmatter | Laika parses `---` as HOCON: `id: 2026-08-18` came back as `202608-18`. Fixed by splitting the block off and using snakeyaml **with implicit typing disabled** — its default resolver has the same bug. |
| Wikilink text loss | `[[X]]` lands in an `InvalidSpan` that `extractText` skips. Worse in a *heading*: it stops the heading becoming a `Section` at all, re-parenting the subtree. |
| Missing GitHubFlavor | Laika's base Markdown has **no table support** — a table arrives as one paragraph of pipe characters, so `#flashcard/table` would silently produce one garbage card. |
| `updateNoteFields` | Drops tags silently (above). |
| Self-poisoning tests | A test's setup using the feature under test (above). |
| A rationalising comment | I wrote a silent no-op for `Retype` **and a comment arguing the silence was deliberate**. The other five defeat the runtime; this one defeats the *review* — it reassures the person looking. |

**Of the documented library defaults checked, five lied and one held.** Treat every default as a claim.

---

## Design decisions you must not relitigate

Ruled by Marc. The reasoning is in the source and in `docs/CARD-MODEL.md`.

- **B1 tag encoding.** Percent-encode outside `[A-Za-z0-9.-]`. Anki tags are whitespace-delimited — a tag **cannot contain a space**, and 62 of 80 real headings do. `_` and `*` are search wildcards. `/` occurs inside real headings so it cannot double as the separator.
- **B5 heading segment.** Extracted text, marker-stripped, NFC + case-folded + internal whitespace collapsed. Deliberate equalities: `**CAP**` == `CAP`, `Costs` == `costs`, `a  b` == `a b`. Whitespace collapses because a markdown *formatter* would otherwise silently orphan cards.
- **B6 section body.** Own prose only, stopping at the next heading of any level — **plus a hard error on an empty body**. Without the second half, `2way` silently produces one card where it promised two.
- **B7 duplicates.** `allowDuplicate: true`, field order Concept/Descriptor/Description. We own identity via `src::`; Anki's first-field checksum is a competing mechanism that would fight ours.
- **Deletion.** The sync **never deletes**. Orphans are **suspended in place** and tagged `orphaned::`, and the run reports what it suspended; a separate explicit `prune` command removes them after a human sees the list. _Suspension added 2026-08-19: a tag alone left the card in the daily review rotation, so a card whose source heading was gone kept being asked with only a tag nobody reads to show for it. A holding deck was considered and rejected — decks mirror folders while the identity tag encodes the heading path, so once a heading is gone the original folder is unrecoverable and the card's current deck is its only record._
- **The breadcrumb is a RULE, not a setting** (2026-08-24). `Context` carries the whole location — folders, file name, heading chain — minus whatever the card already holds as a FIELD. A `VaultLayout` YAML file and a `ContextShape` were both built, green and unwired, and both DELETED: what a breadcrumb may show is not a preference. Deck SHAPE stays a flag because it is genuine taste; the anti-spoiler ceiling over it is not.
- **A deck path may not print the card's own answer** (2026-08-24). `Decks.clamp` TRUNCATES because a deck path is prefix-closed; the breadcrumb REMOVES because it is a list. And a breadcrumb DE-DUPLICATES where a deck path must not — a deck is a filing address and must stay unambiguous, a breadcrumb is a sentence and repetition is noise.
- **Rename detection is CUT from v0**, ruled 2026-08-19 — a subsystem, not a feature. A rename therefore surfaces as an orphan plus an unrelated create, reconciled by hand, which is lossless precisely because the orphan is suspended rather than deleted. The `Relink` case was removed the same day; what was learned is recorded in `CARD-MODEL.md` under *Deliberately deferred*.
- **Cloze grouping.** `==text==` is its own group keyed by its text (fragile); `==2|text==` joins group 2 keyed by the group (stable — text may change freely and the card keeps its history). **The label IS the cloze number.** Two *unlabelled* highlights with identical text are refused, with the remedy named. Digits only, to keep `==a|b==` unambiguous.
- **Task lists are rejected by name**, not supported. Parsing is **strict** — lenient mode is off, and turning it back on would re-arm the mechanism that hid the wikilink bug.

---

## Things that look like bugs and are not

- **`dummy-vault/Patterns/Table-Edge-Cases.md` produces duplicate keys on purpose.** It has two `Retry` rows, which mint three duplicate keys — the row card and both pair cards. `inspect` reports them and exits 2. That is the fixture working. Do not "fix" it. Its first section is also one of the vault's two expected failures: a concept column with no descriptor columns.
- **`dummy-vault/Patterns/Shallow-Nesting.md` fails on purpose too**, and is the vault's other expected failure. Its list items are indented two spaces, which this tool's parser reads as a new list rather than as nesting, so the card would say something the note does not. **Re-indenting it to four spaces silently retires the check** — the failure disappears and the test counting the vault's failures goes green while proving nothing. `FIXTURES.md` carries the full note.
- **`Cost / benefit` contains the path separator on purpose.** It probes the encoding.
- **`hostile-vaults/corrupt-frontmatter/` must SUCCEED, not fail.** Those files are the shapes a *wrong* parser mangles silently. Only 6 of the 11 hostile fixtures are must-fail cases; `FIXTURES.md` says which is which.
- **A card tagged `orphaned::` that is NOT suspended is not a defect, and sync will not touch it.** Suspending is a POSTCONDITION OF FLAGGING, not an invariant the tool maintains, and the difference rests on one fact about Anki: a suspended card is `queue = -1` and **nothing records who suspended it, or why**. So the tool cannot tell a card it suspended from one you unsuspended yourself, and an invariant it cannot enforce would be a claim of ownership it does not have. It suspends once, at the moment it flags; after that the card is yours. Sync therefore never re-suspends and never warns — a warning indistinguishable from a legitimate choice is noise, and noise is how a report stops being read.

  **The one exception is a MIGRATION, and it deliberately has no command.** Cards flagged before 2026-08-22 were tagged and never suspended, because suspending did not exist yet. That state arises once and cannot recur, so it is fixed by hand rather than guarded against forever: in Anki, Browse → search `tag:orphaned::* -is:suspended` → select all → Suspend. A CLI command for it would live forever to solve a problem that happens once.

---

## Live Anki state

- Anki is currently sitting on profile **`claude-POC-test`**, not `POC-test`. `loadProfile` moved it.
- `claude-POC-test` holds **3 notes / 7 cards, two of them deliberately corrupted** ("missing template" orphans), left as evidence so batch-read handling can be tested against real corruption rather than a simulation. Delete when done.
- **Never touch profile `User 1` — that is Marc's real collection.**
- Exactly one agent touches a live collection at a time. Everything else uses `InMemoryAnki`.
- Resolve note types **by name**. Ids are collection-local; both ids that appear in older notes are wrong for any duplicated profile.

---

## Working agreements

- **You have standing permission to commit your own green slices.** Tests green first. Stage **specific paths** — never `git add .`, there is unrelated dirty state in this repo. Commit messages explain the *why*, written for a stranger, no over-promising, with the `Co-Authored-By: Claude Opus 5 (1M context) <noreply@anthropic.com>` trailer.
- **Ask Marc before renaming a symbol** — he prefers to drive IDE refactoring. (Once, avoiding a name collision by narrowing an import was better than renaming anyway.)
- **Marc wants one question at a time, with just enough context — not a dump.** Long status reports go to `zero`, not to him.
- Design-document changes are Marc's call. Mark amendments with a date, never silently.
- Use `/bin/cat`, `/usr/bin/grep`, `/usr/bin/sed` — bare `cat`/`grep`/`ls` are aliased and a hook blocks them.

---

## Open items

1. ~~**A refusal inside a `#flashcard/table` section orphans every card that table produced.**~~
   **NOT A DEFECT SINCE BEFORE THIS ENTRY WAS WRITTEN — the entry was wrong, and it was wrong in
   the direction that wastes a session.** `Planner.underAFailedSection` shelters every observed
   key whose path extends the path of a section that failed to build, which is exactly the table
   case: failures are recorded at the SECTION key and a table's cards are keyed one or two
   segments deeper. The clause carries a long comment recording that it was measured — one image
   in one cell flagged and suspended fifteen live cards before it existed.

   **WHAT WAS ACTUALLY MISSING WAS THE GUARD, AND THAT IS NOW BUILT (2026-08-24).** Commenting
   the clause out left all 651 tests green, so a refactor could have deleted it in silence and
   restored the data loss. `plan/Planner.test.scala` now has one test that fails in BOTH
   directions — if the prefix rule goes, the table's cards appear as orphans; if someone widens
   the shelter to the whole note, a control card that is genuinely absent stops being flagged.
   Both were confirmed by mutation.

   _The lesson generalises past this entry: a document that says "NOT FIXED" is a claim about
   code, and it decays exactly like a comment does. Two of the four items closed on 2026-08-21
   and 2026-08-24 were closed by reading the source and finding the entry contradicted flatly._

2. **DECIDE WHAT A CARD FIELD CONTAINS: plain text or an HTML fragment?** Nothing can be
   sensibly formatted until this is ruled on, and it is Marc's. Anki fields ARE HTML, so a
   literal newline renders as a SPACE — verified by reading a synced note back. For
   hard-wrapped prose that is correct and wanted. For a list it is wrong: the items are now
   preserved (fixed 2026-08-20) but arrive newline-joined, so a bulleted answer reads as a
   run-on sentence. Choosing HTML means escaping user content becomes an obligation, and
   changes every content hash once — an UPDATE for every note, so scheduling survives, but the
   run will report the lot.
3. ~~**Suspend orphans.**~~ **BUILT** — `Anki[F].suspend`/`unsuspend` exist in all three interpreters and were verified live. `Flag` tags then suspends; `Unflag` unsuspends then clears the tag.
4. **Check field names before writing.** _Partly done, 2026-08-21._ `sync` now runs `NoteTypeInstaller.readiness` before it observes the collection — one `modelNames` plus one `modelFieldNames` per note type — and refuses the whole run when a note type is absent or does not declare a field this tool writes. What is still open is narrower: nothing checks an INDIVIDUAL write's field names against the note type it names. The two coincide today, because every write is built by `CardSpec.fields` and `anki/NoteTypeAssets.test.scala` ties that to the manifests. `AnkiError.UnknownField` is still raised by `InMemoryAnki` and **unreachable** through `AnkiConnect`, because it cannot be classified from the wire: Anki reports a wrong field name as *"cannot create note because it is empty"*, exactly as it reports a genuinely empty note.
5. **The `prune` command** — reads `orphaned::` tags. v0-adjacent.
6. ~~**Repair-in-place of an existing note type is NOT BUILT.**~~ **BUILT — this item is closed.**
   _Corrected 2026-08-21 by reading the code, which contradicted the item flatly._ The entry
   claimed "there is no `--repair`, not even behind a flag". There is: `cli/Cli.scala:195-200`
   declares an `Opts.flag("repair", …).orFalse` on `install-note-types`, and
   `cli/Main.scala`'s `repairNoteTypes` adds missing fields and overwrites templates and
   styling with the repository's versions. **The ruling the entry was defending survives and
   is unchanged** — the flag is OFF BY DEFAULT, precisely because a template somebody improved
   in Anki is theirs, so the overwrite must be asked for by name. What was wrong was only the
   claim that no opt-in exists. `anki/NoteTypeInstall.scala` records why refusing *always*,
   with no opt-in at all, turned out to be the more dangerous default.
   The caveat in the old entry still stands and is worth keeping: `updateModelTemplates` looks
   templates up BY NAME and silently ignores names it does not recognise, so a wrong name is a
   repair that reports success and changes nothing.
7. **The hazard list is not yet in the design docs.** Marc's condition: every entry must be honestly labelled ELIMINATED or MITIGATED — a documented hazard whose remedy is a workaround reads as solved and is worse than no note.
8. ~~**Two separate retype gates, and `--dry-run` only saw one.**~~ **FIXED 2026-08-24.** The
   decision had two halves in two places: the POLICY half was pure and the report consulted it,
   while the COMPATIBILITY half needed the note types read out of the collection and lived
   inside the executor, which a dry run never enters. So `--dry-run --migrate-note-types`
   printed `1 move to another note type` and `result: OK` over a move the real run refused.

   Both halves are now one total function, `Retyping.verdictFor`, returning a `RetypeVerdict`
   (`WillApply` / `DeferredByPolicy` / `RefusedByShapes(refusal)` / `ShapesUnavailable`). The
   executor asks it instead of deciding, and `Executor.preview` asks the same function for a dry
   run — so there is one decision and the two cannot drift. The preview pays what the run pays
   and no more: nothing under `Defer`, and under `Apply` the same two reads per DISTINCT note
   type, none at all when the plan holds no retypes.

   Guarded by a LAW asserted in both directions — a move the run refuses must preview as
   refused, and a move the run makes must preview as work — because the first alone is satisfied
   by a preview that refuses everything. Restoring the old behaviour fails it.

9. **THE COMPATIBILITY GATE IS DELIBERATELY NARROWER THAN THE TRUTH, AND WIDENING IT IS AN
   EXPERIMENT, NOT AN EDIT.** Not a defect — `plan/Retyping.scala`'s header says so plainly —
   but it is the reason a `1way` heading retagged `2way` will not migrate, which reads as a bug
   from the outside. It refuses one-template-to-two even though that CANNOT strand a card,
   because the move needs Anki to GENERATE the second card and non-generation is the silent
   failure `SyncAction.Retype` exists to prevent. Both directions are unmeasured. What would
   settle it is one measurement in a throwaway profile — never `User 1` — recording what Anki
   actually does to a card whose ordinal its new note type cannot generate: survives, is
   orphaned until Check Database, or is destroyed. Until somebody runs that, widening the gate
      would be trading a documented refusal for an undocumented risk.

   **WHAT DID CHANGE ON 2026-08-24: the refusal now names the RIGHT unknown.** One sentence
   covered both directions and was accurate for only one — it warned about cards carrying
   ordinals the new type cannot generate, which happens when the count SHRINKS and cannot happen
   when it grows. The growth refusal now says that nothing would be stranded and that what is
   unestablished is whether Anki GENERATES the extra cards. The gate is unchanged; only the
   explanation is now true in both directions, and a test pins them apart.

10. **THE `if`-VERSUS-PATTERN-MATCH AUDIT IS EIGHT-ELEVENTHS DONE — THREE FINDINGS ARE OPEN.**
    Run by a subagent on 2026-08-23 against one question: where does an `if` or a `case _ =>`
    decide something a sealed sum should have decided? Eleven findings, seven landed —
    `plan/VaultScan.scala`'s `OrphanShelter`, `plan/SyncAction.scala`'s `Disposition` and
    `ChangeKind`, `extract/Tables.scala`'s `DescriptorColumn`, among them.

    The four that remain are written out in full, with file and line, as items **3, 4, 5 and 6
    of `IN-FLIGHT.md`** — that is IN-FLIGHT's own numbering, not the audit's, and the entries
    there are the ones to read. In the order they should be taken:

    - ~~`extract/VaultWalker.scala:264`~~ **DONE 2026-08-24** — the one that changed behaviour.
      See IN-FLIGHT item 5 for what it needed beyond the match: removing the false message left
      a file with nothing said about it, so it also took a new `BuildFailure.MarkerUnknowable`.
    - **`anki/NoteTypeInstall.scala:356-380` — four independent drift probes,** where a fifth
      `NoteTypeDrift` case would match none of them and be reported as "nothing needed changing".
    - **`anki/NoteTypeStatus` catch-alls at three sites,** where a fourth status reports clean.
    - **STYLE only** — behaviour-preserving, and the entry argues it may not be worth doing.

11. **THE DAY-TO-DAY QUEUE IS `IN-FLIGHT.md`, NOT THIS LIST.** This section holds the items that
    outlive a session: rulings deferred to Marc, subsystems not built, hazards with a blast
    radius. `IN-FLIGHT.md` holds what is mid-flight right now — currently sixteen numbered
    items, the settled rulings behind them, the fixtures that look untidy on purpose, and the
    mistakes worth not repeating. **Read both.** An item that survives more than a few sessions
    belongs here; an item finished this week belongs there and then disappears.

12. **FIVE DECISIONS ARE WAITING ON MARC, none of them blocking.** Named here so a later session
    does not rediscover them as bugs and 'fix' them. Each is written out in full as items 7–11
    of `IN-FLIGHT.md`.

    - **Blank-rendering cards.** NOT the same thing as an empty section body, which is already a
      hard refusal (`B6`, above). This is a card Anki keeps but cannot show — switching a table
      to fewer directions leaves cards whose FRONT is empty, and Anki says "The front of this
      card is blank" until Tools → Empty Cards is run. Say nothing, or count them and say so.
    - **Content duplicates.** Two cards with identical fields under different keys trip nothing:
      `allowDuplicate` is on and uniqueness is by key. Refusing would be wrong — two tables
      legitimately defining one term is the author's business — so it could only be a report.
    - **Obsidian tags onto Anki notes.** Requested 2026-08-22, so that a vault tag can drive an
      Anki filtered deck. The hard part is not reading them: it is that an unprefixed vault tag
      is indistinguishable from one somebody added by hand in Anki, and this tool must never
      delete a tag believing it owned it.
    - **Row scope at `cdd/2way` / `cdd/3way`** — a row card that blanks the CONCEPT rather than
      the values. Needs a second template; not designed.
    - **Whether to drop the `3way` / `3way/all` aliases** once the vault is rewritten to `cdd/`,
      which would turn the old spelling from a silent synonym into a loud refusal.
