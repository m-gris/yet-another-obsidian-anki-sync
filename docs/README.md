# What is in here, and which of it is allowed to be out of date

Nineteen documents accumulated in one flat directory over ten days, and the question a reader
actually has — *can I trust this one?* — could only be answered by reading it. **The directory now
answers it**, because what a document claims determines whether it can rot.

| | claims | may it be stale? |
|---|---|---|
| **`reference/`** | how the tool works **now** | **No.** Staleness here is a defect — the code moved and the document did not. |
| **`findings/`** | a **measured fact**, with the date it was measured | **No, and it cannot be.** A measurement is true of the moment it was taken. It can only be superseded by a newer one. |
| **`design/`** | a **decision, or an open question** | **No, because it makes no claim that can go stale.** A design document records what was decided, what was rejected and what is still undecided — all historical facts. It says nothing about whether any of it is built, so there is nothing in it to fall behind the code. |
| **`history/`** | what somebody thought **at the time** | **It is stale by definition**, and that is the point. Kept for the reasoning, never for the instructions. |

**`README.md`** at the repository root sits outside this scheme deliberately: it is the front
door.

**Open work is not in here at all.** It lives in beads — `bd list`, prefix `oas` — which is the
one place a status is authoritative. A document may *describe* work that is outstanding; it may
not be where you go to find out what is. `history/IN-FLIGHT.md` is the markdown tracker beads
replaced on 2026-08-30, kept for the sixteen closed items whose reasoning is worth having.

## Where status lives, and why it is not a directory

A document is rarely wholly done or wholly outstanding. `design/CLOZE-REDESIGN.md` currently holds
four shipped decisions, two open questions, one parked idea and three withdrawn ones. A directory
called `todo/` would lie about the first four; one called `done/` would lie about the rest.

**So the tree carries the KIND, which does not change, and a bead carries the STATUS, which
does.** A document holds one line — a query, not a summary:

```
> **Work on this document** — `bd list --all --spec docs/design/CLOZE-REDESIGN.md`
```

**Closed means built, and the closing reason says what shipped; open means outstanding.** So a
reader at the document can answer *is this implemented?* and *what is left?* from one command,
and the line itself can never go stale because it states nothing.

**That requires the tracker to remember what is finished**, which is why a decision becomes a
bead and shipping it means closing it rather than deleting it. A tracker holding only open items
cannot answer the first question at all — and for a day it could not, because only the open work
was migrated into it.

_A summary block was tried here first, on 2026-08-30, and lasted about an hour. It listed what was
built and what was open, which is a status kept in two places: close a bead and the document
lies. Marc named it as double maintenance the moment he saw it._

`history/` is the exception that earns a directory, because *superseded* is the one status that
applies to a whole document at once.

## What documents may not do

**Assert their own status, in prose or in a block.** `docs/design/CLOZE-REDESIGN.md` opened with
"Nothing here is built" for three days after four of its decisions had shipped, and went on listing
`^blockid` as *eliminated* while a block identifier was what identified a cloze card. Prose does
not update itself, and neither does a summary. The line above is a query, which is why it is the
only form allowed.

**Confuse the state of the ARGUMENT with the state of the WORK.** *Undecided* is a fact about the
argument and belongs here — it is what the document is for. *Unbuilt* is a fact about the work and
belongs in a bead. The first cannot go stale; the second goes stale the moment somebody builds it.

**Be deleted when they stop being current.** They move to `history/`. A superseded document still
holds the reasoning that produced what replaced it, and that reasoning is usually the expensive
part.
