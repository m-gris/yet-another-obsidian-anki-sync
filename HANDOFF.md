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
| **The tool** | `backend-interview-prep/obsidian-anki-custom-sync/` |
| **Design docs** | `backend-interview-prep/srs-obsidian-anki/` — REQUIREMENTS, CARD-MODEL, LEARNING-MODEL |
| **Fixture vault** | `obsidian-anki-custom-sync/dummy-vault/` — yours, encodes the design |
| **Hostile fixtures** | `obsidian-anki-custom-sync/hostile-vaults/` — must fail loudly, or must survive a *wrong* parser |
| **Marc's real vault** | `/Users/marc/srs-in-obsidian-test/` — **READ ONLY, NEVER WRITE** |
| **Dead PoC** | `backend-interview-prep/poc-obsidian-vault/` — superseded, ignore entirely |

**Three vaults, do not confuse them.** Marc's real vault is *parser-hazard evidence only* — its heterogeneous ids and stray aliases are leftovers from an abandoned experiment, **not** intended design. Never infer conventions from it.

`scala-cli test <tool dir>` runs everything. **204 tests, 0 failures, 0 warnings** as of `bb2c8f1`.

---

## State: what is done

Everything except the AnkiConnect interpreter.

- `parser/ObsidianSyntax.scala` — Obsidian dialect (wikilinks, embeds, highlights, task-list rejection) and **the canonical `markupParser`**. Build parsers only from there.
- `model/` — `CardKey`/`TagCodec` (identity + tag encoding), `Marker` (the six markers), `CardSpec` (the six card shapes).
- `anki/` — the `Anki[F]` algebra and `InMemoryAnki`, a working fake that **enforces Anki's real constraints**.
- `plan/` — `VaultScan`, `SyncAction`, `Planner`, `Executor`/`Observer`.
- `extract/` — `Frontmatter`, `Extractor`, `Tables`, `Cloze`, `VaultWalker`.
- `cli/` — `inspect` works; `sync` fails loudly as unimplemented.

Try it: `scala-cli run <tool dir> -- inspect <tool dir>/dummy-vault` → 43 cards, 1 expected failure, 3 deliberate duplicate keys, exit 2.

---

## Your next task: the AnkiConnect interpreter

Implement `Anki[F]` over HTTP against `localhost:8765`. **Four hazards, all verified live.** Two are MITIGATED — behaviours you must implement correctly. Two are ELIMINATED — and their entries say what **not** to build, because defending a path that no longer exists is dead code that reads as diligence.

1. **MITIGATED — write surgically: `updateNoteFields(fields)` → `removeTags(oldSha)` → `addTags(newSha)`.**

   _Superseded 2026-08-19. This entry previously read "Use `updateNote`, NEVER `updateNoteFields`". That is now wrong and actively dangerous — follow the ordering above instead._

   Both halves of the original finding hold: `updateNoteFields` **silently discards** its `tags` parameter (`error: null`, clean exit), and `updateNote` writes both. But `updateNote` **replaces the entire tag set**, verified live:

   ```
   initial            [leech, marc-put-this-here, src::t1]
   tags=[ours only] → [sha::dead, src::t1]      ← foreign tags DESTROYED
   no tags key      → preserved
   tags=[]          → []                         ← note becomes unenumerable
   ```

   `leech` is applied by Anki's own scheduler. Writing the whole tag set would make destroying it **mandatory on every update**, on the happy path — worse than the bug it closes.

   Surgical never writes the whole tag set, so it is *structurally incapable* of clobbering a foreign tag. And every interruption self-heals:

   - interrupted after fields → old sha still present → next sync sees a mismatch → retries
   - interrupted after `removeTags` → no sha at all → treated as changed → retries

   **Fields first, sha last.** The inverse ordering — sha first — strands the note permanently: it holds old content under the new hash, the planner computes `markdown hash == recorded hash`, emits no `Update`, and nothing ever reports it. That inversion was live in `Executor` and is fixed in `717d899`; the ordering is now covered by a test that asserts on the **fields stored in Anki**, never on the next plan being empty — because in the broken state the next plan *is* empty.

   Residual, hence MITIGATED not ELIMINATED: a stale observation can leave two `sha::` tags. More than one `sha::` reads as "cannot claim unchanged", so the note is rewritten and the extra is cleared. Converges rather than being prevented.
2. **MITIGATED — tags go inline on create AND on retype.** `addNote` takes them inline. `updateNoteModel` **wipes all tags unless you pass them in the same call** — leaving a note with no `src::` tag, which is not merely unmatched but *unenumerable*: invisible to lookup, reconciler and prune, permanently.
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

Ruled by Marc. The reasoning is in the source and in `srs-obsidian-anki/CARD-MODEL.md`.

- **B1 tag encoding.** Percent-encode outside `[A-Za-z0-9.-]`. Anki tags are whitespace-delimited — a tag **cannot contain a space**, and 62 of 80 real headings do. `_` and `*` are search wildcards. `/` occurs inside real headings so it cannot double as the separator.
- **B5 heading segment.** Extracted text, marker-stripped, NFC + case-folded + internal whitespace collapsed. Deliberate equalities: `**CAP**` == `CAP`, `Costs` == `costs`, `a  b` == `a b`. Whitespace collapses because a markdown *formatter* would otherwise silently orphan cards.
- **B6 section body.** Own prose only, stopping at the next heading of any level — **plus a hard error on an empty body**. Without the second half, `2way` silently produces one card where it promised two.
- **B7 duplicates.** `allowDuplicate: true`, field order Concept/Descriptor/Description. We own identity via `src::`; Anki's first-field checksum is a competing mechanism that would fight ours.
- **Deletion.** The sync **never deletes**. Orphans are tagged `orphaned::`; a separate explicit `prune` command removes them after a human sees the list.
- **Cloze grouping.** `==text==` is its own group keyed by its text (fragile); `==2|text==` joins group 2 keyed by the group (stable — text may change freely and the card keeps its history). **The label IS the cloze number.** Two *unlabelled* highlights with identical text are refused, with the remedy named. Digits only, to keep `==a|b==` unambiguous.
- **Task lists are rejected by name**, not supported. Parsing is **strict** — lenient mode is off, and turning it back on would re-arm the mechanism that hid the wikilink bug.

---

## Things that look like bugs and are not

- **`dummy-vault/Patterns/Table-Edge-Cases.md` produces duplicate keys on purpose.** It has two `Retry` rows. `inspect` reports them and exits 2. That is the fixture working. Do not "fix" it.
- **`Cost / benefit` contains the path separator on purpose.** It probes the encoding.
- **`hostile-vaults/corrupt-frontmatter/` must SUCCEED, not fail.** Those files are the shapes a *wrong* parser mangles silently. Only 6 of the 11 hostile fixtures are must-fail cases; `FIXTURES.md` says which is which.

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

1. **The AnkiConnect interpreter** — the only unimplemented piece of v0, with the four constraints above.
2. **The `prune` command** — small, reads `orphaned::` tags. v0-adjacent.
3. **Rename detection has no algorithm.** An orphan plus an unmatched key is structurally identical to an unrelated delete plus create. `Relink` exists in the ADT as a *proposal for a human*, never performed — but nothing produces one yet.
4. **Repair-in-place of an existing note type is untested.** The live checks *created* a correct note type on a clean profile rather than repairing the defective one in `POC-test`, which still has the back-side template defect.
5. **The hazard list is not yet in the design docs.** Marc's condition: every entry must be honestly labelled ELIMINATED or MITIGATED — a documented hazard whose remedy is a workaround reads as solved and is worse than no note.
