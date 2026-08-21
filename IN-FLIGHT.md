# In flight — delete this file when the work below has landed

_Rewritten 2026-08-21, after the note-type work was verified against a live collection.
Everything here was checked, not remembered. **Nothing is running now.**_

## Verified against `claude-POC-test`, end to end

The whole sequence has been run and its results measured, rather than asserted:

```
install-note-types --profile claude-POC-test            # survey; writes nothing
install-note-types --profile claude-POC-test --repair   # adds Context, rewrites templates + css
sync --vault-path <copy of dummy-vault> --profile claude-POC-test --migrate-note-types
```

Measured before and after the migration, by capturing every note and card and comparing:

| | |
|---|---|
| notes | 43 before, 43 after |
| identities lost or gained | **none** |
| notes moved to a new note type | 21 |
| **card ids changed** | **0** |
| **cards whose scheduling moved** | **0** (`type`, `queue`, `interval`, `factor`, `reps`) |
| notes still on a stock Anki note type | 0 |
| notes with a populated `Context` | 38 of 43 |

The five empty `Context` values are correct, not missed: they are two-level paths such as
`consistency/definition`, where the single ancestor is already shown as the card's Concept.
Repeating it would be noise, and `{{#Context}}` renders nothing when the field is empty.

Both commands are IDEMPOTENT, checked by re-running: the second sync plans nothing at all, and
the second install reports all five note types matching.

The motivating card now reads `Body shapes › Cranial bones and their sutures` above
`Frontal` / `Anterior border`, which is the whole point of the exercise — "Frontal" alone could
have meant the bone, the lobe or the cortex.

## What is NOT done

1. **The adversarial reviewers' findings — 25 still open**, of the 27 the workflow's verify phase
   produced. The two BLOCKING ones are fixed (`c244a08`, `bac5902`, `c20c723`). What remains is
   mostly the same failure this project keeps producing — **nine untrue prose claims** in
   comments and documents — plus real test gaps, the sharpest being that NOTHING ties
   `class="context"` in the ten template files to the `.context` rule in the five stylesheets, so
   renaming the class passes the whole suite and the breadcrumb silently renders as a title.
   The full list is in the workflow journal at
   `~/.claude/projects/-Users-marc-.../subagents/workflows/wf_eb291fa9-081/journal.jsonl`
   (read the `result` key, not `value`).
2. **Orphan suspension** — ruled 2026-08-19, still NOT BUILT. `Anki[F]` has no `suspend` /
   `unsuspend`, and `Unflag` must unsuspend.
3. **`prune` command.**
4. **A formatter, and a keybinding for it** — Marc's suggestion. It would re-indent a vault's
   nested lists to four spaces so the refusal in `ListIndent` becomes rare rather than merely
   informative. Marc's view: "probably trivial", to be done later.
5. **`ZZ-probe-delete-me`** — a note type left in `claude-POC-test` by a migration probe.
   AnkiConnect has no delete-model action, so removing it means Tools → Manage Note Types.
   Harmless if left.

## Rulings that are settled — do not reopen

- **The list marker is EXPLICIT, never inferred from the body.** The tool can see that a list is
  present; it can never see whether the ORDER is the knowledge. A bulleted answer shown WHOLE is
  legitimate and different from one revealed step by step.
- **Progressive disclosure only where the list is the ANSWER.**
- **Under-indented nested lists are REFUSED, not repaired.** Repair is impossible, not merely
  awkward: the parser has already consumed the indentation. See `extract/ListIndent.scala`.
- **The tool writes only to its OWN five note types**, never to Anki's stock ones.
- **A note type that already exists is never overwritten without `--repair`.** But note the
  amendment recorded in `anki/NoteTypeInstall.scala`: refusing *always*, with no opt-in at all,
  was not the safe position — it is what let `Context` be written to 21 notes and rendered on
  none of them.
- **Never touch profile `User 1`** or `/Users/marc/srs-in-obsidian-test/`.

## Fixtures that must not be "tidied"

- `dummy-vault/Patterns/Shallow-Nesting.md` — its two-space nesting IS the fixture. Re-indenting
  it silently retires the check.
- `dummy-vault/Patterns/Table-Edge-Cases.md` — deliberate duplicate identities. Excluded from a
  live sync by copying the vault and deleting this file; leaving it in aborts the plan.

## Known-false claims in ratified documents

`srs-obsidian-anki/CARD-MODEL.md` §Lists still says progressive disclosure "cannot be expressed
by one Anki note" and "requires generating N notes". Both are false, and the second is the
opposite of what is wanted — N notes are scheduled independently, which destroys the sequencing.
Amending it is Marc's call, dated.
