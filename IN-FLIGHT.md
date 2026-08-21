# In flight — delete this file when the work below has landed

_Rewritten 2026-08-21. The multi-agent run this file used to describe has FINISHED and landed;
nothing is running now. Everything below is verified, not remembered._

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

1. **Install the `Cloze Sequence` note type** into profile `claude-POC-test`, via AnkiConnect
   `createModel`, from the four files in `note-types/cloze-sequence/`. Until this exists in the
   collection, a `#flashcard/sequence` card cannot be written, let alone reviewed.
2. **Sync and let Marc review a real progressively-revealed list card.** Sync a collision-free
   copy of the fixture vault — everything except `Patterns/Table-Edge-Cases.md`, which holds
   three deliberate duplicate identities.
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
