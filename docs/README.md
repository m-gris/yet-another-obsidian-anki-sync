# What is in here, and which of it is allowed to be out of date

Nineteen documents accumulated in one flat directory over ten days, and the question a reader
actually has — *can I trust this one?* — could only be answered by reading it. **The directory now
answers it**, because what a document claims determines whether it can rot.

| | claims | may it be stale? |
|---|---|---|
| **`reference/`** | how the tool works **now** | **No.** Staleness here is a defect — the code moved and the document did not. |
| **`findings/`** | a **measured fact**, with the date it was measured | **No, and it cannot be.** A measurement is true of the moment it was taken. It can only be superseded by a newer one. |
| **`design/`** | a **decision, or an open question** | **Yes, in one direction only.** A design document is expected to describe things that do not exist, so "nothing here is built" is a normal sentence. What it may *not* do is describe something as unbuilt after it ships. |
| **`history/`** | what somebody thought **at the time** | **It is stale by definition**, and that is the point. Kept for the reasoning, never for the instructions. |

Two documents sit outside this scheme deliberately: **`README.md`** at the repository root is the
front door, and **`IN-FLIGHT.md`** is the live open-work list — the one index anyone consults, and
the only place a *status* is authoritative.

## Where status lives, and why it is not a directory

A document is rarely wholly done or wholly outstanding. `design/CLOZE-REDESIGN.md` currently holds
four shipped decisions, two open questions, one parked idea and three withdrawn ones. A directory
called `todo/` would lie about the first four; one called `done/` would lie about the rest.

**So the tree carries the KIND, which does not change, and each document carries its own STATUS
block, which does.** A status block is a few lines under the title saying what is built, what is
open, what is parked and what has been withdrawn — editable in the file you were already editing,
rather than a file move that updates no references and therefore never happens.

`history/` is the exception that earns a directory, because *superseded* is the one status that
applies to a whole document at once.

## What documents may not do

**Assert their own status in prose, loosely.** `docs/design/CLOZE-REDESIGN.md` opened with
"Nothing here is built" for three days after four of its decisions shipped. Prose does not update
itself; a status block is at least in one predictable place, and the open-work list links to it.

**Be deleted when they stop being current.** They move to `history/`. A superseded document still
holds the reasoning that produced what replaced it, and that reasoning is usually the expensive
part.
