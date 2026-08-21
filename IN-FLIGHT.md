# In flight — delete this file when the work below has landed

_Written 2026-08-21, immediately before a context compaction, so that a session with no memory
of the conversation can pick this up. Everything here is verified, not remembered._

## The running workflow

```
task id     we29elfrm
run id      wf_130aefb2-a8a
script      ~/.claude/projects/-Users-marc-.../workflows/scripts/mob-html-and-lists-wf_130aefb2-a8a.js
resume      Workflow({ scriptPath: <above>, resumeFromRunId: "wf_130aefb2-a8a" })
```

It has already been interrupted twice by session limits and resumed cleanly both times.
Completed agents replay from cache; only failures re-run. **If a usage limit is near, STOP it
deliberately** (`TaskStop`) rather than letting agents die mid-slice — a clean stop plus a
resume costs nothing, a degraded run costs untangling.

## What it is doing, in three slices

1. **`content/AsHtml.scala`** — a second renderer over the closed shapes, producing the HTML
   fragment an Anki field holds. Escaping must be *structural*: raw author text unable to reach
   the output without passing through the escaper. Adds files nobody calls, so the golden file
   MUST NOT move.
2. **Switch the fields to HTML.** The first change to what Anki receives. The golden file
   **is meant to move here**, and the driver must explain the diff field by field in prose.
3. **List cards** — a marked section whose list reveals one item at a time, as ONE card on ONE
   schedule, using the `Cloze Sequence` note type. Stops at producing the card spec; it may not
   touch `anki/`, `plan/` or `cli/`.

## The invariant that decides pass or fail

**CARD IDENTITY MUST NOT MOVE.** Every `src::` tag in `extract/golden/fixture-cards.txt` must be
byte-identical to its committed version. Check with `git diff` on that file and look at the
`card ⟦src::…⟧` lines specifically — field values may change, keys may not.

A changed key is **not** an update. It is an orphan plus a brand-new card with no review
history — and orphan suspension is ruled but NOT BUILT, so the orphan also stays in the daily
review rotation with only a tag to show for it.

## State to compare against

```
HEAD            72e8761  Save the list note type into the repository
tests           359, 0 failures
fixture vault   inspect --vault-path <tool>/dummy-vault  →  54 cards, 1 expected failure
tree            clean under obsidian-anki-custom-sync/ except the workflow's own new files
```

## What happens after it lands

1. Verify identity and the golden diff **by reading them**, not from the agents' reports.
2. Commit.
3. Install the `Cloze Sequence` note type into profile **`claude-POC-test`** via AnkiConnect
   `createModel`, from the four files in `note-types/cloze-sequence/`.
4. Sync a collision-free copy of the fixture vault (everything except
   `Patterns/Table-Edge-Cases.md`, which holds three deliberate duplicate identities) and let
   Marc review a real progressively-revealed list card.
5. Expect **every note to be rewritten once** when the fields become HTML. That is an UPDATE, so
   scheduling and review history survive — prove it in `claude-POC-test` rather than assert it.

## Rulings that are settled — do not reopen

- **The list marker is EXPLICIT, never inferred from the body.** Marc's three reasons: a mistake
  becomes detectable (marker and body are two statements that can disagree); identity stays
  stable when a body is edited; and it preserves a choice the tool cannot make — a bulleted
  answer shown WHOLE is legitimate and different from one revealed step by step. The tool can
  see that a list is present; it can never see whether the ORDER is the knowledge.
- **Progressive only where the list is the ANSWER.** Where it is the prompt, show it in full or
  the question is unanswerable.
- **Never touch profile `User 1`** or `/Users/marc/srs-in-obsidian-test/`.

## Known-false claims in ratified documents

`srs-obsidian-anki/CARD-MODEL.md` §Lists still says progressive disclosure "cannot be expressed
by one Anki note" and "requires generating N notes". Both are false, and the second is the
opposite of what is wanted — N notes are scheduled independently, which destroys the sequencing.
Amending it is Marc's call, dated.
