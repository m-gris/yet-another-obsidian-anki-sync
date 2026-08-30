# Reconciler Shape

_The proposed shape of the sync action model: what the reconciler may conclude, and what it structurally may not. **Status: PROPOSED, not ratified.** The shape below is agreed between the implementing and reviewing agents; it is recorded here so it survives the conversation. It is not a design document — the design documents live in [`docs/`](docs/) and only Marc changes those._

**Blocked on, and deliberately not settled here:** the tag encoding (B1), what a heading-path segment is (B5), what "the section body" means once a heading has children (B6), and the note type's field order versus Anki's duplicate check (B7). No types are written until those are ruled on.

---

## Full

### Why the original four cases were not enough

The specified model was `Create | Update | Move | Flag`. It is under-dimensioned in four independent directions, and the reason they were all missed is a single conflation: the ADT mixes **what differs** — a fact about the domain — with **what call to make** — a concern of the interpreter. `Move` is the clearest symptom. Moving a note between folders is not a different *kind* of conclusion from editing its body; it is a different *change*. Modelling it as a sibling of `Update` is what makes "edited and moved" inexpressible.

Separating the two dissolves that case rather than patching it, and the same cut makes the remaining three additions fall out naturally.

### 1. `Update` carries a non-empty set of changes

```
Update(key, noteId, changes)
    changes : NonEmptySet of  FieldsChanged | DeckChanged | TagsChanged
```

"Edited and moved" is one `Update` carrying two changes. `Move` disappears as a top-level case.

The non-emptiness is load-bearing rather than tidiness. Because the `sha::` tag decides "nothing to do" *before* any call is made, an `Update` that changes nothing is not a thing the reconciler can legitimately conclude — so it should not be constructible. `Update` must also be unrepresentable without a resolved `AnkiNoteId`: carry the id inside the case rather than as an `Option` beside it, so "update something we never found" cannot be written down.

### 2. A note-type change is not an `Update`

```
Retype(key, noteId, from, to)
```

This is the sharpest of the four, because the failure mode is **silent success**. The marker selects the note type, but the identity key is deliberately marker-independent — so retagging a heading changes the note type without changing the key. `Basic` and `Basic (and reversed)` share field names, so `updateNoteFields` succeeds, reports success, and the reverse card the marker asked for simply never exists. Nothing errors. Nothing is logged. The card count is wrong forever.

**Unresolved, and deliberately not guessed:** Anki has a Change Notetype operation, but whether AnkiConnect exposes it, and whether it preserves scheduling, is unverified. Verifying means touching the live collection, which belongs to the single serialised integration phase. Two branches follow:

- exposed and scheduling-preserving → `Retype` is an ordinary action.
- otherwise → the only route is delete-then-create, which destroys the card's review history. That must never be automatic; it joins relink and prune as **confirm-required, never automatic**.

This question should be the first item of the live-Anki phase, because the ADT's shape depends on the answer.

_Amended 2026-08-21, when `Retype` was built._ The first branch is the one that happened, and the case is now `Retype(key, noteId, from, to, fields, ownedTags, preservedTags)` — see `plan/SyncAction.scala`. AnkiConnect exposes an `updateNoteModel` action; a live probe in profile `claude-POC-test` reported that a note moved with it keeps its card id and that card's `type` / `queue` / `interval` / `factor` / `reps`, and that the review-log entry survived. (Reported by an earlier session; the session that wrote this paragraph did not re-measure it and made no live contact of any kind.)

Three things the paragraphs above did not anticipate, each of which shaped the case:

- **The operation is destructive by construction.** Read out of the add-on's own source on this machine (`addons21/2055492159/__init__.py:849-899`): it blanks every field of the new note type before writing anything, matches field names case-insensitively and silently ignores any it does not recognise, and assigns the tag set unconditionally from `note.get('tags', [])` — so an omitted `tags` key *erases* the tags rather than preserving them, unlike `updateNote`. That is why the case carries the whole field set and the whole tag set rather than just `from` and `to`.
- **It is not unconditionally safe even so.** A card's ordinal means a template index on a standard note type and a cloze number on a cloze one, and nothing in this repository establishes what Anki does with a card whose ordinal the new note type cannot generate — that decision is taken in Anki's compiled Rust backend. `plan/Retyping.scala` therefore refuses any move where the two note types differ in cloze kind or in template count, and names Anki's own Browse → Notes → Change Note Type dialogue as the remedy. Retagging `#flashcard/1way` as `#flashcard/2way` — the very example this section was written around — falls on the refusing side, because `Obsidian Basic` has one card template and `Obsidian Basic (and reversed card)` has two.
- **It is opt-in.** `sync` plans the move and does not make it unless `--migrate-note-types` is passed; the notes it left alone are reported as *deferred*, a third outcome distinct from applied and from failed. The reason is not the scheduling question, which is settled: it is that the first such run moves every note the tool has ever synced, and that is too large an act to take as a side effect of a routine reconcile.

### 3. ~~`Relink` is a proposal, not an action~~ — WITHDRAWN 2026-08-19

This section proposed a `Relink(orphanKey, orphanNoteId, candidateKey, confidence)` case: a rename detected heuristically, reported as a pairing for a human to confirm and never performed, on the argument already accepted for flag-then-prune — *a heuristic must not drive an irreversible operation.* That argument still stands. What has gone is the heuristic it was guarding.

**Marc cut automatic rename detection from v0** on 2026-08-19, judging it a subsystem rather than a feature. With nothing to produce a proposal, the case had no owner, and it was removed from the code the same day. Note that the case as implemented never carried the `confidence` field this section specifies — it was written with three fields and the drift went unnoticed, which is its own small illustration of why a proposed shape is not a design document.

An orphan is now **suspended** rather than left in rotation, so reconciling a rename by hand is lossless: the card keeps its full review history and restoring the heading restores the card. See *Deletion* and *Deliberately deferred* in [`docs/reference/CARD-MODEL.md`](docs/reference/CARD-MODEL.md), which is the ratified record and carries what was learned while exploring detection.

### 4. `Unflag` closes the orphan set's first hole

```
Unflag(key, noteId)
```

A key present in *both* markdown and Anki, but currently carrying an `orphaned::` tag, must have that tag cleared. Without this transition the flag set only ever grows, and a stale orphan becomes indistinguishable from a live one — in precisely the list a human is meant to review before pruning. The safety of flag-then-prune rests on that list being trustworthy.

### 5. `Flag` requires a completeness witness

```
VaultScan = CompleteScan(notes) | PartialScan(notes, failures)
```

"Present in Anki, absent from markdown" is only a valid inference if the markdown scan was **total**. If one file failed to parse, a directory was unreadable, or the run was scoped to a subset, then every card in the unscanned region looks orphaned. That is mass false flagging produced by an ordinary, expected condition.

Making it a runtime check invites forgetting it. Making it structural does not: let the planner derive `Flag` actions **only** from a `CompleteScan`. A `PartialScan` still yields `Create` and `Update` actions — those are sound per-key, because a key that *is* present was really seen — but yields no orphan set at all, and says so.

This also answers a question the design documents never ask: what should happen when one file in the vault fails to parse? Under this shape — sync everything else, flag nothing, report the failures. **Fail loudly without failing totally.**

### 6. The tool owns only its own tag prefixes

Anki is a derived artifact, which is what makes storing bookkeeping there acceptable. It does not follow that the tag *namespace* belongs to the tool. A person may tag a card in Anki for their own purposes — leech marking, a custom study scope, anything — and a naive tag reconciliation would wipe it on the next run.

So the tool owns exactly `src::`, `sha::` and `orphaned::`, and must preserve every other tag untouched. `TagsChanged` means **our tags differ**, never **the tag set differs**. This distinction is easy to lose in implementation and should be carried by a type — an owned-tag type rather than a bare string — so that "write the tags" cannot accidentally mean "write all the tags".

### 7. Deck is a card property; the note is the unit of identity

The identity scheme keys on **notes** — `src::` is a note tag — while decks and scheduling are per-**card**, and a three-field note has up to three cards. `DeckChanged` therefore cannot be executed against a note id: `changeDeck` takes card ids, so the interpreter must fan out from the note to its cards.

This is the note/card impedance point of the whole design, and it is also where a partial failure could leave one card behind in the old deck while its siblings move. Worth stating explicitly rather than discovering in the interpreter.

### 8. Write ordering: prefer redundant work over believed work

Two orderings matter, and both are the same hazard in different clothes: a run interrupted between two calls that should have been one.

**On create** — the identity tag must be written by the call that *creates* the note, never afterwards. `addNote` accepts tags inline. A note created without its `src::` tag is not merely unmatched, it is **unenumerable**: invisible to the key lookup, to the reconciler, and to prune, permanently.

**On update** — write the fields **first** and the new `sha::` **last**. If the hash is written first and the run dies before the fields, the note holds its OLD content under the NEW hash; the next run compares the markdown's hash against the tag, finds them equal, and skips the note — silently and permanently. Writing fields first means an interruption leaves new content under a stale hash, which the next run sees as a difference and simply writes again: a *redundant update*, which is harmless because the write is idempotent.

> ⚠️ _Corrected 2026-08-19._ **This section previously prescribed the exact opposite** — "write the new `sha::` **before** the fields" — and gave the argument above in reverse, attributing the permanent-skip disaster to the ordering that in fact prevents it. That prescription was implemented, shipped, and is fixed in commit `717d899`. It was caught by a property that interrupts an update after each write and checks that a later run still brings the note to the content the markdown asks for. Note that the property asserts on **the fields stored in Anki**, never on the next plan being empty: in the broken state the plan *is* empty, so the obvious assertion passes while the note sits stale forever.

The principle generalises, and it was never the part that was wrong: when an interruption must leave the system in one of two wrong states, choose the one that causes work to be redone rather than the one that causes work to be believed done.

### What is deliberately not in the model

**No `NoOp`.** "Nothing to do" is the absence of an action. Representing it fills a plan with entries that mean nothing happened, and invites a caller to iterate over them.

**No change to `Create`, and no change to flag-then-prune.** `Create` is the only case that legitimately carries no note id. Prune remains a separate, explicit, human-gated command that reads `orphaned::` tags; nothing here makes the sync itself delete.

---

## Summary

The specified action model `Create | Update | Move | Flag` conflates **what differs** with **what call to make**, and that single conflation accounts for most of its gaps. Separating the two makes `Move` a *change* rather than an action, so `Update` carries a non-empty set of changes — fields, deck, tags — and "edited and moved" becomes expressible without a special case. Non-emptiness matters because the `sha::` hash decides "nothing to do" before any call, so a no-op update is not a conclusion the reconciler can legitimately reach.

Three cases were added, of which two survive. `Retype` exists because a marker change alters the note type without altering the key, and `Basic` versus `Basic (and reversed)` share field names — so the update *succeeds*, reports success, and the requested reverse card never exists. Silent success is this project's signature failure and deserves its own case. _Amended 2026-08-21: this previously ended "Whether `Retype` is an ordinary action or a confirm-required one depends on an AnkiConnect capability that has not been verified and is deliberately not guessed." The capability exists — `updateNoteModel` — and `Retype` became an ordinary action carrying the whole field set and the whole tag set, but a **conditional** one: it is opt-in behind `sync --migrate-note-types`, and it is refused outright between note types of different shape. See §2._ `Unflag` clears an `orphaned::` tag from a key that has reappeared, without which the flag set only grows and the prune list a human reviews becomes untrustworthy. **`Relink` was withdrawn on 2026-08-19** when automatic rename detection was cut from v0: the argument for making it a proposal rather than an action was sound, but with no heuristic left to guard, the case had no owner. A rename is now reconciled by hand, losslessly, because an orphan is suspended rather than left in rotation.

The structural idea is `VaultScan = CompleteScan | PartialScan`, with `Flag` derivable **only** from a complete scan. "In Anki, not in markdown" is a valid inference only if the scan was total; a partial scan still yields sound per-key creates and updates but no orphan set at all. This makes the precondition impossible to forget and answers a question the design never asked — when one file fails to parse, sync the rest, flag nothing, report.

Three constraints govern execution. The tool owns only the `src::`, `sha::` and `orphaned::` prefixes and must preserve every other tag, so `TagsChanged` means *our* tags differ. Deck is a per-card property while identity is per-note, so a deck change must fan out to the note's cards. And write ordering is pessimistic by design: the identity tag is written by the call that creates the note, and a new hash is written *after* the fields it describes — so that an interrupted run redoes work rather than believing work was done. _Corrected 2026-08-19: this said "before" until commit `717d899`, which is the ordering that causes the permanent silent skip rather than preventing it._

---

## TLDR

The action model splits **what differs** from **what call to make**; `Move` was a change, not an action, so `Update` carries a non-empty set of changes and "edited and moved" stops being a special case.

`Retype` becomes its own case because a marker change alters the note type but not the key, and the failing update *succeeds silently* — the requested reverse card simply never exists.

`Unflag` clears a stale orphan tag, without which the human-reviewed prune list only ever grows. `Relink` was a reported proposal rather than a performed action; it was **withdrawn on 2026-08-19** with the rest of automatic rename detection, and a rename is now reconciled by hand — losslessly, because an orphan is suspended rather than deleted.

`Flag` is derivable only from a `CompleteScan`, so a partial run cannot mass-flag orphans — one unparseable file means sync the rest, flag nothing, report.

The tool owns only its own tag prefixes, deck changes fan out from notes to cards, and hashes are written **after** the fields they describe so an interruption redoes work rather than believing work done — this document said "before" until 2026-08-19, which is the ordering that causes the failure rather than preventing it.
