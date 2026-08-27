# Decisions the tool will not take alone

_Written 2026-08-27, from a design conversation between Marc and Claude. **Nothing here is built.**
Claims marked VERIFIED were established by running something or by reading the file cited;
everything else is reasoning, and says so. It opens with the answer rather than the three-layer
TLDR / Summary / Full form, following its siblings — `EVOLVABILITY.md`,
`CLOZE-REDESIGN.md`, `EDIT-IN-OBSIDIAN.md`, `PARSER-DISAGREEMENTS.md`._

---

## The one-paragraph answer

Four different things in this tool end the same way: **the vault and the collection disagree, the
tool will not resolve it alone, and it tells you in a line of a report you scroll past.** An orphan
waits for a `prune` that does not exist; a refused shrink waits for you to do it by hand in Anki; a
card retired by narrowing waits for nothing at all; a note left behind by `--no-migrate-note-types`
waits for you to drop the flag. Each is a *pending decision with a cost you cannot see* — you are
never shown how many reviews the card has. The proposal is to make that category first-class: the
tool **enumerates the pending decisions with their costs**, and **carries out one named decision**,
and nothing more. No loop, no prompt, no terminal UI in the Scala at all — because the destination
is an Obsidian plugin, and a TUI written first is a TUI thrown away. `prune` stops being a command
and becomes one action among several, which is a better shape than it had.

**This is untidy rather than urgent, and the number is what says so.** The six parked orphans hold
nineteen reviews between them and nothing beyond a one-day interval — measured 2026-08-27, the
first time anyone looked. No accumulated value is stuck. The case for building this is that **the
cost is invisible**, which that measurement demonstrates by being the first of its kind; it is not
that something valuable is trapped today.

---

## The category, stated once

A pending decision is a state where:

1. the vault says one thing and the collection says another,
2. the tool has established that it will not reconcile them by itself — either because doing so
   could cost review history, or because you asked it not to, and
3. **something with accumulated value is waiting on the answer.**

Point 3 is what makes it a queue rather than a report. A build failure is not a pending decision:
nothing is waiting, and fixing the markdown resolves it. A parked orphan is: a card with months of
review history sits suspended, and no edit to the vault will free it.

**The tool currently has no word for this.** Each member is reported in its own vocabulary — one as
a count, one as a *failure*, one not at all — so they read as four unrelated problems rather than
one category with four instances.

---

## The four members

| what happened | what waits | what could be done about it |
|---|---|---|
| a heading was deleted or reworded | the note is tagged `orphaned::` and every card suspended | **prune** · **keep** · **relink** to a heading that exists |
| a marker narrowed across note types — `cdd/3way` → `2way` | nothing; the run reports a *failure* and the note stays put | **apply anyway**, told what it costs · **skip** · **restore the marker** |
| a marker narrowed inside the `cdd` family | a blank-fronted card keeps its history, invisible, and Anki's *Empty Cards* will offer to delete it | **flag** it · **prune** it · **restore the marker** |
| `--no-migrate-note-types` was passed | the note stays on a shape its own marker no longer asks for | **move** it · **leave** it |

**Live counts on Marc's collection, VERIFIED 2026-08-27:** six parked orphans; two notes whose
shrink is refused, both with zero reviews on every card. The orphan count was three the day before.

**Row 3 is not built** — see `IN-FLIGHT.md` item 23, where flagging such a card is decided and
unbuilt. **Row 2's third state does not exist**: today the answer is refuse, permanently, and the
report tells you to do it by hand in Anki instead.

---

## Three stances on a refusal, and why only the third is acceptable

_RULED by Marc, 2026-08-27, after the same refusal blocked him twice in one session. **This is the
sharpest statement of what this document is for**, and it was reached by rejecting a weaker
proposal of Claude's — which is why the rejected one is recorded beside the ruling rather than
quietly dropped._

When the vault asks for a change that might cost review history, three stances are available:

| | stance | who decides | status |
|---|---|---|---|
| **(a)** | **refuse on SHAPE** — any shrink refused, whatever is at stake | the tool | what it does today |
| **(b)** | **refuse on EVIDENCE** — refuse only when the cards actually hold reviews | the tool, at a better threshold | **PROPOSED BY CLAUDE, REJECTED BY MARC** |
| **(c)** | **never refuse — state the cost, let the author choose** | the author | **RULED** |

### Why (b) was rejected, and why the distinction is not a small one

(b) looks like the fix. It unblocks the common case — a note whose cards have never been reviewed
— and it is a strictly better rule than (a). **That is exactly what makes it the more dangerous
answer: it satisfies the complaint without conceding the principle.**

(b) keeps the tool in the position of FORBIDDING and merely moves the threshold at which it does.
Marc's ruling is that changing his mind about his own cards is **his decision, made knowingly** —
not a privilege the tool extends once it has judged the stakes low enough. A tool that permits the
change only when it costs nothing has not accepted that; it has restated its authority with a
friendlier face.

**The general form, worth carrying to other gates in this codebase:** when a tool refuses, the
question is never only "is the threshold right?" It is "who is entitled to decide?" Tuning a
threshold answers the first and silently keeps the second.

### What blocks (c) is a measurement, and the dependency is stronger than it looks

The tool cannot state the cost, because nobody has established it. The refusal's own text says so:
*"this tool has not established what Anki then does with them"*. Cards past the new note type's
last ordinal might be kept, orphaned, or destroyed, and all three remain consistent with what is
known.

**SO (c) BUILT BEFORE THE MEASUREMENT WOULD BE WORSE THAN (a), NOT BETTER.** An "apply anyway" that
means *"proceed, and I cannot tell you what happens"* violates this document's own founding rule —
**nothing is applied that has not been shown with its cost** — and it fails in the worse direction,
because it LOOKS informed. A refusal at least tells the truth about what the tool does not know.

**This makes M4 the critical path rather than one measurement among four.** See *What must be
measured before building anything* below.

### It is not hypothetical, and the numbers say so

MEASURED 2026-08-27, live: `Database Scaleability.md` had both its headings turned down from
`cdd/3way` to `2way`. The tool read the vault correctly — `inspect` reports
`Obsidian Basic (and reversed card)` for both — and then refused the move, twice, under
`SOME ACTIONS FAILED`. **The six cards it protected hold 0 reviews, 0 lapses and 0 interval
between them.** The author was blocked from an edit that would have cost nothing, and told to go
and do it by hand in another application.

---

## What each item must carry, and why it is the point

The reason to build this rather than improve four reports is that **none of them shows the cost**.

A parked orphan is printed as part of a count. Nothing says whether that card has two reviews or
two hundred, which is the only fact that decides what to do with it. The same is true of every
other row: a refused shrink names the note types and not the review history at stake; a narrowed
card is not reported at all.

So an item carries, at minimum:

- **which card or note**, named as the author would recognise it — the heading path, not a tag;
- **what happened**, in one sentence;
- **what it costs to resolve each way** — reviews, interval, and whether the loss is recoverable;
- **the actions available**, which differ per row.

The cost is the deliverable. Everything else is already printed somewhere.

---

## The architecture, which is the load-bearing part

**The Scala tool gains no interactivity.** It gains two capabilities:

- **enumerate** the pending decisions, as structured data, with their costs;
- **apply** one named decision.

That is all. No loop, no prompt, no cursor handling, no TTY.

**Why.** The destination is an Obsidian plugin. Marc's stated intent is that this tool is
*"ultimately used in Obsidian — ideally not from a CLI, nor a TUI"*. A review loop written as a
terminal program puts the decision logic inside the presentation, and the presentation is the part
being thrown away. Split the two and a terminal consumer, a plugin, and anything later are all thin.

**There is precedent in this repository, deliberate and recent.** `locate/Locate.scala` exists so
that an Anki add-on can *ask* the Scala tool where a card came from, rather than carrying a second
implementation of the identity codec in Python — see `docs/EDIT-IN-OBSIDIAN.md`, which states the
reason: a copy of `TagCodec.canonical` in another language, held honest only by a test, is the
defect class this project fights hardest. **The same argument applies here with more force**: an
action that prunes a card must not exist twice.

**What that implies about the boundary.** Enumeration returns data, not prose — the report's
sentences are for a human reading a run, and a plugin will write its own. Application takes an
identifier for one decision and one action, so that a UI cannot express "prune everything" by
accident; a caller that wants a batch loops, visibly.

**Where relink gets easy.** Reattaching an orphan means choosing which heading it now belongs to.
That is miserable in a terminal — you would be typing a heading path — and native in Obsidian,
where fuzzy-finding a note is what the application is for. Building the terminal version first
would design the interface around the harder medium.

---

## What this replaces, and why that is an improvement

**`prune` stops being a command.** It has been on the open list for a week as *"deletes flagged
cards after the list has been reviewed"*, and its entire safety argument is that a human sees the
list first.

**That argument weakens exactly as the danger grows.** Six items you read. Six hundred you confirm
blindly — and six hundred is precisely the state a mass-flagging accident produces, which
`EVOLVABILITY.md` §3.4 describes and which is still not guarded. A per-item decision with the
review count in front of you is as considered at the hundredth as at the first.

So `prune` becomes an **action**, available on the rows where it makes sense, and the command
disappears. That is a smaller surface and a stronger guarantee.

---

## What is decided, and what is not

**Decided, and inherited rather than settled here:**

- No TTY in the Scala. Enumerate and apply; the UI is a separate thing.
- One decision per application. A batch is a caller looping, not a flag.
- Nothing is applied that has not been shown with its cost.

**Not decided, and each is Marc's:**

1. **Whether `relink` is in the first version at all.** It is the highest-value action on the list
   and the only one needing a target chosen. Leaving it out makes the first version much smaller;
   leaving it out also means the orphan queue can only be emptied by deleting, which is the wrong
   incentive.
2. **Where the third state for a refused shrink lands** — an action here, or a flag on `sync`. An
   action means the cost is shown at the moment of choosing, which is the argument for this whole
   document; a flag means it can be scripted. They are not exclusive.
3. **Whether a terminal consumer is written at all before the plugin**, or whether enumeration
   simply prints as text until the plugin exists.
4. **Whether cloze groups at risk of ordinal drift belong here.** They are a *risk*, not a pending
   decision — nothing is waiting, and the vault can be edited to remove the risk. Arguably a
   different category, and `CLOZE-REDESIGN.md` covers it.

---

## What must be measured before building anything

- ⚠️ **What a shrink actually does to a card at a stranded ordinal. THIS IS THE CRITICAL
  PATH** — it gates stance (c) above, which is ruled and unbuildable until this is known.
  UNMEASURED, and it decides
  whether row 2's third state is *"proceed, it is fine"*, *"proceed, the cards are recoverable via
  Check Database"*, or *"proceed and spend this much review history"*. `EVOLVABILITY.md` M4 records
  the experiment; only its growth half has been run. One hour in a throwaway profile, and the
  `Check Database` step is not optional — *orphaned* and *destroyed* look identical without it.
- **Whether AnkiConnect exposes template MAPPING on a note-type change.** UNMEASURED. Anki's own
  *Change Note Type* dialogue lets a person map templates explicitly, which is the one real
  advantage the current refusal's advice has over this tool. If the API exposes it, the tool can
  offer what the dialogue offers instead of pointing at it.
- **What `Tools > Empty Cards` does to a retired card**, and whether it takes the review log with
  it. UNMEASURED, recorded in `EVOLVABILITY.md` M5, whose gate-flip half has been run. It decides
  whether row 3's flag is a convenience or the only thing standing between a card and deletion.
- ~~**How many reviews the parked orphans actually hold.**~~ **MEASURED 2026-08-27**, and the
  answer is *untidy, not urgent*:

  | orphan | cards | reviews | max interval |
  |---|---|---|---|
  | `descriptor` | 3 | 7 | 0d |
  | `surjection/definition` | 2 | 3 | 0d |
  | `module 1 thinking in patterns/…` | 1 | 0 | 0d |
  | `framework` | 2 | 7 | 1d |
  | `/n` — a whole-note anchor | 1 | 0 | 0d |
  | `scale` | 1 | 2 | 1d |
  | | | **19** | **1 day** |

  **That it took a deliberate script to learn this is the finding.** The count of parked orphans is
  printed on every run; the cost of each has never been printed at all, so "six notes parked" has
  been carrying the same weight whether those notes held nineteen reviews or nineteen hundred. That
  is precisely the gap this document proposes to close, and the measurement is its first instance.

  _One entry is worth knowing about: `framework` is the note migrated between note types on
  2026-08-26 and orphaned on 2026-08-27 because its file was deleted from the vault. The tool
  behaved correctly; it is listed here only so that nobody reading later mistakes it for a
  regression in the migration._
