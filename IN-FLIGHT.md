# In flight — delete this file when the work below has landed

_Rewritten 2026-08-21. Everything below is verified, not remembered._

## A multi-agent run IS in progress

```
task id   w25dlhe8v
run id    wf_eb291fa9-081
script    ~/.claude/projects/-Users-marc-.../workflows/scripts/obsidian-owned-note-types-wf_eb291fa9-081.js
resume    Workflow({ scriptPath: <above>, resumeFromRunId: "wf_eb291fa9-081" })
```

**If a usage limit is near, STOP it deliberately** (`TaskStop`) rather than letting agents die
mid-slice. A clean stop plus a resume costs nothing; a degraded run costs untangling. The
previous run survived that twice.

**What it is doing.** Giving the tool its own Anki note types, so it stops writing to the stock
`Basic` / `Basic (and reversed card)` / `Cloze` that the rest of Marc's collection shares — and
carrying the heading path onto the card as a visible `Context` field, because a card reading
"Frontal → Anterior border" cannot be answered without already knowing it means the frontal
*bone*. Names Marc chose: `Obsidian Basic`, `Obsidian Basic (and reversed card)`,
`Obsidian Cloze`, `Obsidian Cloze Sequence`, `Obsidian Concept-Descriptor`.

**Why a real field and not the `src::` tag.** The tag carries the CANONICAL path — lowercased,
percent-encoded — because identity is deliberately severed from display. A tag-derived
breadcrumb would be permanently lowercase. `Extractor`'s `ancestorTitles` already holds the
properly cased chain and throws all but its last element away.

**Two renames have no API** — AnkiConnect cannot rename a model. `Cloze Sequence` and
`3 way Concept-Descriptor` must be renamed by hand in Tools → Manage Note Types. Agents were
told not to attempt or work around this.

**The acceptance invariant, unchanged:** every `src::` tag in `extract/golden/fixture-cards.txt`
stays byte-identical. Field values are EXPECTED to move — a field is being added — but a moved
key is an orphan plus a historyless card.

**Verified before launch, by live probe in `claude-POC-test`:** `updateNoteModel` keeps the
card's id, its `type`/`queue`/`interval`/`factor`/`reps`, and its review-log entry. So the
migration does not cost review history. Footgun found while proving it: `getReviewsOfCards`
returns EMPTY for card ids passed as strings, real entries for the same ids as integers, and
does not error either way.

## What landed

| commit    | what                                                                     |
|-----------|--------------------------------------------------------------------------|
| `00a6bc9` | `content/AsHtml.scala` — a second renderer, escaping enforced by an opaque type |
| `ff9f559` | card fields became HTML; `#flashcard/sequence` added                      |
| `b2e1597` | under-indented nested lists refused; every diagnostic's line number fixed |

State: 415 tests, no failures. The fixture vault yields 55 cards and 2 expected failures.
**No `src::` identity tag has moved across any of it**, so every existing note is an UPDATE and
keeps its review history.

## What is NOT done

1. **Run `install-note-types` against profile `claude-POC-test`.** _Amended 2026-08-21: the
   CODE now exists — `anki/NoteTypeAssets.scala` reads the five definitions off the classpath
   and `anki/NoteTypeInstall.scala` creates the missing ones through AnkiConnect's
   `createModel`. Nobody has run it against a live collection._ Note what a live run will find:
   both note types awaiting a hand-rename are still under their old names, so it will refuse and
   create nothing until Marc has renamed them in Tools → Manage Note Types. And once renamed,
   neither has the `Context` field — `modelFieldNames` was read on 2026-08-21 and answers
   `[Title, Text]` for `Cloze Sequence` and `[Concept, Descriptor, Description, ThreeWay]` for
   `3 way Concept-Descriptor` — so that field has to be added by hand as well. `sync` refuses
   loudly in both cases rather than writing.
2. **Sync and let Marc review a real progressively-revealed list card.** Sync a collision-free
   copy of the fixture vault — everything except `Patterns/Table-Edge-Cases.md`, which holds
   three deliberate duplicate identities.

   ⚠️ **Every note already in `claude-POC-test` is on a note type this tool no longer writes
   to**, since the five `Obsidian *` names landed. An ordinary `sync` now plans one *move*
   per such note, leaves it alone, and says so; passing `--migrate-note-types` makes the
   moves. Added 2026-08-21 and **never run against a live collection** — see HANDOFF's
   hazard 2 for what the operation destroys if it is called with anything less than the whole
   field set and the whole tag set.
3. **Prove, do not assert, that HTML fields arrive as an UPDATE.** Every note will be rewritten
   once now that fields are HTML. That should preserve scheduling; check it in `claude-POC-test`
   rather than claiming it.
4. **Orphan suspension** — ruled 2026-08-19, still NOT BUILT. `Anki[F]` has no `suspend` /
   `unsuspend`, and `Unflag` must unsuspend.
5. **`prune` command.**
6. **A formatter, and a keybinding for it** — Marc's suggestion, not yet designed. It would
   re-indent a vault's nested lists to four spaces so the refusal in `ListIndent` becomes rare
   rather than merely informative. Nothing has been decided about where it lives or what invokes
   it.

## Rulings that are settled — do not reopen

- **The list marker is EXPLICIT, never inferred from the body.** Marc's three reasons: a mistake
  becomes detectable (marker and body are two statements that can disagree); identity stays
  stable when a body is edited; and it preserves a choice the tool cannot make — a bulleted
  answer shown WHOLE is legitimate and different from one revealed step by step. The tool can
  see that a list is present; it can never see whether the ORDER is the knowledge.
- **Progressive only where the list is the ANSWER.** Where it is the prompt, show it in full or
  the question is unanswerable.
- **Under-indented nested lists are REFUSED, not repaired.** Repair is impossible, not merely
  awkward: the parser has already consumed the indentation. See `extract/ListIndent.scala`.
- **Never touch profile `User 1`** or `/Users/marc/srs-in-obsidian-test/`.

## Fixtures that must not be "tidied"

- `dummy-vault/Patterns/Shallow-Nesting.md` — its two-space nesting IS the fixture. Re-indenting
  it would silently retire the check and take the expected-failure count back to 1.
- `dummy-vault/Patterns/Table-Edge-Cases.md` — holds deliberate duplicate identities.

## Known-false claims in ratified documents

`srs-obsidian-anki/CARD-MODEL.md` §Lists still says progressive disclosure "cannot be expressed
by one Anki note" and "requires generating N notes". Both are false, and the second is the
opposite of what is wanted — N notes are scheduled independently, which destroys the sequencing.
Amending it is Marc's call, dated.
