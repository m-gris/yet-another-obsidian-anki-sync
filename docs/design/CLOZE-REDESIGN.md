# Redesigning cloze

> **STATUS — 2026-08-30.** Beads carry the live status (`bd list`, prefix `oas`); this is the
> summary for a reader who arrived at the document rather than at the tracker.
>
> **BUILT** — the `==<<text>>==` syntax, so a bare `==highlight==` is emphasis and makes no card ·
> `^blockid` as a production in the grammar rather than prose that reaches the card face · a card
> identified by the block it sits in, encoded `src::{id}::/b/{anchor}` · a cloze card from any
> block, with no heading needed.
>
> **OPEN** — what an unlabelled highlight is keyed by (`oas-9yz.1`, waiting on recovery tier 3,
> `oas-4ti`) · block identifiers inside Obsidian callouts fail the strict parse (`oas-yom`) · the
> `%%card%%` uniqueness objection (`oas-9yz.3`) · the git replay, M1 (`oas-nmg`).
>
> **WITHDRAWN** — identity by matching old text against new · a key projected onto the labels in a
> block. Both are kept below with the reasoning that killed them.

_Named `ANCHORS-BELOW-A-HEADING.md` until 2026-08-27. That named one mechanism inside it —
attaching a card to something smaller than a heading — while the document is about cloze as a
whole: what text a card shows, how it is packaged into Anki, what identifies it, and what has been
ruled out. Renamed with `git mv`, so the history is intact._

_Written 2026-08-27, from a design conversation between Marc and Claude. It opened with "nothing
here is built" until 2026-08-30, by which time four of its decisions had shipped — see the status
block above, which exists because prose does not update itself._

_Claims marked VERIFIED were established by running something; everything else is reasoning, and
says so. It follows this repository's convention of opening with the answer rather than the
three-layer form, because its siblings — `EVOLVABILITY.md`, `PIPELINE-DESIGN.md` — do._

_**A warning about evidence, learned the hard way on 2026-08-27.** Claude surveyed what the vault
contains today — `==` appearing outside any cloze marker — and proposed a design rule from it.
Marc rejected the move: this is a tool under construction, and it may **impose** conventions on
whoever uses it, its author included. **What the vault contains today is the status quo. It is not
evidence about what the design ought to be.** The survey was also wrong on its facts: a `==3==`
that Claude classified as emphasis had in fact been written in the hope that it would become a
cloze. Both halves are worth remembering — the vault is weak evidence, and inferring an author's
intent from syntax is guessing._

---

## The one-paragraph answer

Cloze is meant to be the escape hatch — the tool you reach for when knowledge does not fit a
rigid shape — and it is currently the most rigid thing in the system: the marker goes on a
**heading**, and the whole section body becomes one card's text, so every gap in a five-paragraph
section shows all five paragraphs. Fixing that means letting a card hang off something **smaller
than a heading**, which turns out not to be a syntax problem but an **identity** problem: what
names a paragraph, such that the card survives you editing the sentence it is about? ~~Obsidian's
own block references are eliminated — VERIFIED, they print on the card face.~~ **They are the
answer, adopted 2026-08-29** — that elimination rested on a defect in this tool being mistaken for
a property of the syntax; see the section below. An invented author-typed
anchor was proposed and has an unanswered objection. Two candidates remain, both Marc's: the cloze
**label** is already an author-supplied stable identifier, and a **similarity fingerprint traced
across git commits** can migrate a binding whose key has moved. They are complementary rather than
rival, and neither is blocked on anything but a decision.

**The decision that has since been taken, and the one thing to read if you read nothing else:**
ship **per-group** first — one Anki note per labelled group, which the label already names and
which can therefore be built now — and treat **per-paragraph** as REQUIRED rather than optional,
because only per-paragraph lets Anki space apart the several gaps of one paragraph so you do not
meet them all in a single session. Per-group is a staging post, and **the longer it runs the more
the switch costs**: several Anki notes must become one, Anki cannot merge notes while keeping their
cards, so those cards and their review history are lost. Today that cost is zero — no paragraph in
the collection holds more than one group — and it grows with every multi-gap paragraph written.
Full statement, with the vocabulary defined, under *The packaging ruling*.

---

## Why this is an identity problem and not a syntax one

A card is anchored at a **node** of a note. `CardPath` already has three cases — a chain of
headings, a frontmatter property, the note itself — and each was cheap to add because each anchor
had an obvious **name**: the heading text, the property name, nothing at all. The codec reserves
room for a fourth (`model/CardKey.scala`, the leading-empty-token scheme), so adding `Block` costs
almost nothing structurally.

What it costs is a name. A paragraph does not have one. And a card whose anchor moves is a card
that detaches from its review history — the same failure `EVOLVABILITY.md` §4 calls the system's
cost centre, arriving one level further down.

So every candidate below is judged on one question: **does the anchor survive the author editing
the thing it anchors?**

_**Two clarifications added 2026-08-27, because this heading now over-claims.**_

_First, there **is** a syntax question, and it is a different one. This section is about the
**anchor** — what identifies a card. *The marker* — what syntax tells the tool a phrase is a cloze
at all — is genuinely a syntax question, it is open, and it is upstream of this. See
*The marker: what syntax says "this is a cloze"* below. The two do not compete; a decision on one
constrains nothing on the other._

_Second, the "one question" above assumes the answer must be an anchor that survives on its own.
See *Identity is a matching problem, not a naming problem* below, which argues the tool holds both
sides at sync time and can pair them by comparing text — in which case surviving unaided is a
stronger property than is needed. If that argument holds, this section's framing is not wrong so
much as **narrow**._

---

## What has been eliminated, and on what evidence

### Obsidian block references (`^blockid`) — ELIMINATED 2026-08-27, ADOPTED 2026-08-29

> **THIS SECTION ELIMINATED THE MECHANISM THAT IS NOW THE ANSWER**, and how that happened is worth
> more than the conclusion was.
>
> **The first finding was a DEFECT MISTAKEN FOR A PROPERTY.** "A block id prints on the card face"
> was true, and it was a bug in this tool rather than a fact about `^blockid`: nothing in the
> parser had a production for it, so it fell through as prose. Marc's ruling, 2026-08-29 — *there
> is a grammar of Obsidian and this tool should model it, not paper over the places it did not* —
> made the repair a production rather than a string stripped on the way out. A block id now lowers
> to nothing at all, so no renderer can print one and the defect is unrepresentable rather than
> fixed.
>
> **The second finding was never about the mechanism.** Callouts failing the strict parse blocks
> the note they are in, whatever anchors anyone chooses. Still open: `oas-yom`.
>
> **What separated them was Marc's question**, asked after this section had been read back to him:
> *"I said could we **use** `^blockid` — not should the tool **write** it."* The unstated
> assumption was that a surrogate key must be written by whoever needs it, and this tool has never
> written to the vault. It does not have to: the **author** writes the anchor, Obsidian generates
> one with a keystroke, and a cloze block without one is refused — the same contract a heading
> already has, where declaring the marker is how you ask for the card.
>
> _The reasoning below is kept unedited. It was sound on its evidence; the evidence was
> incomplete._

The obvious candidate, and Marc's vault is full of them (`[[Modern Mathematics#^Z4YC85FV]]`, with
`obsidian-copy-block-link` installed). Obsidian generates them, they survive edits to the block,
and they are not prose — everything an anchor should be.

**VERIFIED BY EXECUTION, and it kills them:** run through this project's own parser,
`The outermost layer is the ==epidermis==. ^abc123` renders as
`<p>The outermost layer is the {{c1::epidermis}}. ^abc123</p>`. **A block id prints on the card
face.** It is card *content*, not metadata. `^` is a delimiter nowhere in the stack — not in
Laika's `Markdown.spanParsers`, not in `GitHubFlavor`, not among the six parsers
`ObsidianSyntax.bundle` registers — and `Html.escape` covers `& < > " { }` and leaves it alone.

A second finding, also VERIFIED, would have blocked them anyway: **every one of the 125 block-id
definitions in `References/Modern Mathematics.md` sits inside an Obsidian callout, and callouts
fail this tool's strict parse** — `> [!note] Page 31` yields `unresolved link id reference: !note`,
because CommonMark reads `[!note]` as a shortcut reference link. It is silent today only because
that note has no frontmatter `id`; giving it one, which is exactly what somebody does when they
want its annotations to become cards, is what makes the failure appear.

_The first finding is **fixed** — `parser/ObsidianSyntax.scala` carries the production and
`content/Lower.scala` drops the node. The second is **open** as `oas-yom`. Both were open items 20
and 21 in the markdown tracker that beads replaced on 2026-08-30; the archive at
`docs/history/IN-FLIGHT.md` holds their original wording and is not where to look for their
status._

### A plain content hash — ELIMINATED, and instructively

Hashing a paragraph and using the digest as its name fails for a reason worth stating, because it
generalises: **a cryptographic hash is designed to do the opposite of what an anchor needs.**
Avalanche is the point — flip one bit of input, every output bit changes. So fixing a typo re-keys
the card. Git blob SHAs fail identically, and per-file besides.

### An invented author-typed anchor (`%%card cloze: some-name%%`) — OBJECTION UNANSWERED

Proposed by the pipeline brainstorm (`PIPELINE-DESIGN.md`) and genuinely clever in its carrier:
`%%…%%` is an Obsidian comment, which VERIFIED is already a parsed node
(`parser/ObsidianSyntax.scala:81`), already lowers to zero inlines
(`content/Lower.scala:428-433`), and is already hidden by Obsidian — so unlike `^blockid` it cannot
reach a card face.

Its adversarial review found the objection: **an author-typed name has no uniqueness mechanism.**
Duplicate a paragraph — the commonest operation on a literature note — and you have two identical
anchors, one key, and a whole-run refusal. The proposal rejected `^blockid` partly on *unverified*
per-file uniqueness while proposing something that fails the same test harder.

The objection is answerable — refuse per note rather than per run, naming both occurrences — but
nobody has answered it, and the option should not be adopted until somebody does.

---

## The marker: what syntax says "this is a cloze"

_OPEN, raised by Marc 2026-08-27 and NOT settled. It sits upstream of everything below, because it
decides which text in a note the tool looks at in the first place._

The tool reads `==highlighted==`, Obsidian's own highlight syntax, chosen for two stated reasons:
it renders as a highlight, which is apt for "phrase you will be asked about", and it is the
convention across the Obsidian spaced-repetition ecosystem.

Adopting it **fully** — every `==…==` anywhere is a cloze, no heading marker, no declaration — is
the shortest route to what Marc asked for: *highlight a phrase anywhere and get a card.* The price
is that `==` becomes **reserved**, unavailable as a third level of emphasis beside bold and italic.
Marc has said he is willing to pay it.

### The three options, and what separates them

| written as | renders in Obsidian as | leaves `==` free for emphasis | can you see which highlights are cards |
|---|---|---|---|
| `==radius==` | a highlight | no | **no** |
| `<<radius>>` | the literal text `<<radius>>` | yes | yes |
| `==<<radius>>==` | a highlight reading `<<radius>>` | yes | yes |

**The hybrid is Marc's proposal, and its strength is how it fails.** A bare custom delimiter
renders as literal brackets and no highlight at all — on mobile, in a preview, on a machine without
the plugin. The hybrid still gets a real Obsidian highlight in every one of those places; it merely
has visible brackets inside it.

### What CSS can and cannot do

**CSS alone cannot hide the inner `<<>>`.** Obsidian renders `==<<radius>>==` as
`<mark><<radius>></mark>` — HTML identical to a plain highlight, with the brackets a fragment of a
text node. CSS selects elements, and there is no element here to select. This is the same wall the
`1|` group labels hit, for the same reason.

**A plugin can, and gets more for the same work.** Post-process each `<mark>` whose text matches
`<<…>>`: strip the brackets, add a class. Clozes then carry their own colour, visibly distinct from
emphasis highlights. This is the plugin possibility already noted for hiding the group labels — not
a second one.

### What the decision turns on

If `==` is not wanted for emphasis, the hybrid's one remaining benefit is that **you can tell at a
glance which highlights are cards.** The plain form gives no way to know without checking. So the
question is narrow: is that worth four extra characters on every cloze ever written?

~~**Nobody has decided.**~~ **DECIDED BY MARC 2026-08-28, AND BUILT THE SAME DAY.** The hybrid
won: a cloze is `==<<text>>==`, and a bare `==highlight==` is an ordinary Obsidian highlight that
makes no card. Four characters buy the property reserving `==` could not — you can see, by looking
at a note, which of its highlights are cards.

**The migration cost nothing, which is the strongest fact about it.** The fixture vault's cloze
notes were rewritten to the new spelling and the golden record of every card that vault produces
came out BYTE-IDENTICAL: not one card identity and not one field value moved. That is not luck. An
unlabelled deletion is keyed by its own text, and stripping the brackets leaves the text alone — so
this changed a syntax and not a collection.

**What it also bought, unplanned:** finding clozes WITHOUT a heading marker became safe. Before it,
scanning every block for highlights would have swept up every emphasis highlight in the vault. Now
only `<<…>>` counts, so "find every cloze in this note" is exact rather than a guess. That was a
prerequisite for everything below, and it is done.

---

## What the scope of such a card would be

Distinct from the anchor question, and settled more easily. What delineates the text a cloze card
shows?

**A sentence cannot be it.** Markdown has no concept of one and neither does Laika, so the tool
would be splitting on `.` against prose containing `e.g.`, decimals, `Fig. 3`, and — in this
vault — `$B^A$` and other maths. A silent-failure generator.

**A line cannot be it.** Inside a paragraph a line break is soft wrap, an artifact of how the file
happens to be wrapped. Reflowing a paragraph would silently re-cut every card in it.

**A block can.** Paragraph, list item, quoted block, table — real nodes in the parse tree, which
the tool already receives. It is also what Obsidian's own block references address and what
`metadataCache.sections` drills into.

Scoping to the block fixes the original complaint directly: today the body is the whole **section**,
so a section with three paragraphs and five highlights produces five cards *each showing all three
paragraphs*. That is the bundling.

---

## Identity is a matching problem, not a naming problem

_Raised by Claude 2026-08-27, answering Marc's request for a deeper reflection._

**SETTLED 2026-08-29, AND NOT THE WAY THIS SECTION PROPOSES.** Identity stays a PURE FUNCTION OF
THE VAULT — derived, deterministic, the same notes always producing the same cards whatever the
collection happens to hold. What this section proposes would make identity depend on Anki's current
state, so two runs over one vault could diverge. That property was never weighed when the section
was written, and it is worth more than what matching was going to buy.

**WHAT SURVIVES OF IT, AND IT IS THE USEFUL HALF.** Comparing old text with new is exactly right as
a way to RANK candidates for a human to approve — which is this project's standing rule about fuzzy
matching, and which is already designed as recovery tier 3 (oas-4ti, and the long
comment at `plan/Planner.scala`'s `identityErrorFor`). It belongs there, on the suggest side of the
line, and not in the identity function. See *How per-paragraph is actually unblocked* below.

Both candidates below assume identity means a **name** — a label, a block id, a fingerprint;
something written down that must survive arbitrary editing on its own. That may be answering a
harder question than the tool actually asks.

**The tool does not need to name a card. It needs to pair the cards Anki holds with the cards the
vault now produces.** Those are different problems:

- **Naming** is global and one-sided. The name must survive unaided, with nothing to compare against.
- **Matching** is local and two-sided. Both collections are in hand at the moment of the sync.

Matching also runs in a tiny arena: the scope is a single note — typically one to ten cards — and
**Anki's `Text` field holds the previous content verbatim**, so the old text itself is available for
comparison, not merely a hash of it.

### The drift case dissolves with no fuzziness at all

Ordinal drift is the failure this document exists to prevent: insert a highlight earlier in a
paragraph and every later ordinal rotates, so review history silently re-attaches to different
content under an ordinary update.

```
old note in Anki:  {{c1::radius}}   {{c2::ulna}}
new text in vault: {{c1::forearm}}  {{c2::radius}}  {{c3::ulna}}
```

**Exact string equality pairs old `c1` with new `c2`, and old `c2` with new `c3`.** No threshold,
no similarity score, no git history, no author-supplied label. The failure this whole document is
organised around is resolved by comparing strings.

### What is left over, and where it goes

The residue is a card whose text was **edited and moved in the same sitting**, leaving exact
matching no partner for it. In a scope of five cards that is one or two items — and it is precisely
the shape `REVIEW-QUEUE.md` describes: propose the pairing, show what it costs, let the author
confirm.

That also honours the standing ruling that **fuzzy matching may rank but must never apply on its
own.** The ranking becomes a proposal in a queue, not a silent write.

### What it would change about labels

Labels stop being a requirement and become **a way to buy certainty on the residue** — written
where you want a card pinned, not on every highlight. That is a materially different burden to ask
an author to carry, and it is the strongest argument against treating candidate A as the default.

### What has not been checked, and must be before this is relied on

- **That AnkiConnect returns the `Text` field for cloze notes on `notesInfo`.** Assumed here,
  UNVERIFIED, and the entire reframe rests on it.
- **What matching does when a card is genuinely deleted** rather than moved. Two leftovers must not
  be paired with each other merely because both were left over — that would silently graft one
  card's history onto unrelated content, which is the exact harm being prevented.
- **Whether within-note matching is enough**, or whether a card can move between notes and needs a
  wider arena.

---

## The two candidates that remain

Both are Marc's, from the conversation of 2026-08-26/27. They solve different halves and compose.

### A. The cloze label as the anchor

`==2|epidermis==` — the `2` is author-supplied, note-scoped, and **survives rewriting
`epidermis`**, rewording the sentence around it, and reflowing the paragraph. It is precisely the
author-typed anchor the `%%card%%` proposal wanted to invent, except it already exists in the
syntax and is already used.

It also sidesteps that proposal's objection: a duplicated paragraph carries a duplicated **label**,
which is note-scoped and which the tool can refuse by name.

**And it can dissolve the ordinal-drift defect rather than guard against it.** Today one section is
one Anki note with N cards, and a card's ordinal is its cloze number — so inserting a highlight
earlier renumbers the later ones and review history re-attaches to different content
(`EVOLVABILITY.md` §3.2, still open). If each label-group becomes **its own Anki note with one
card**, there are no ordinals to rotate. The defect stops being something to guard and becomes
unrepresentable.

~~**A generalisation worth keeping even if the rest is dropped:** do not hash the content — project
it onto the part that is already stable. In a cloze block that part is the labels. A key of
`(frontmatter id, heading path, the labels in this block)` is content-derived, needs no new syntax,
and moves only when the author deliberately renumbers.~~

**WITHDRAWN 2026-08-29. IT ASSUMES LABELS ARE UNIQUE ACROSS A NOTE, AND THEY ARE NOT.** Marc caught
this by reading the syntax rather than the prose. A label is scoped to the section it appears in —
which is what `extract/Cloze.scala`'s `number` actually does, computing over one section's
highlights — so two blocks may each use `1` and `2` meaning different things:

```markdown
# Header

The ==<<1|radius>>== and the ==<<2|ulna>>== are forearm bones.

The ==<<1|femur>>== and the ==<<2|tibia>>== are leg bones.
```

Both project to `(note id, "Header", [1,2])`. **Same key, two different paragraphs.** The
projection cannot identify a block, because a label only means something INSIDE a scope and the
scope is the thing being named. It is circular.

**A SECOND DEFECT, FOUND BEFORE THE FIRST ONE KILLED IT**, recorded so the idea is not revived in a
patched form: the label SET is not stable under ADDITION either. Adding a third gap to a paragraph
turns `[1,2]` into `[1,2,3]`, re-keying the note and orphaning the cards already in it — a cost
paid for an edit that only adds.

**It could be made to work by making labels note-scoped**, unique across a whole file. That is a
real tax — in a ten-paragraph note the author numbers into the twenties and must remember which
numbers are spent — and it is not needed, for the reason the next section gives.

**The fork it forces.** If a paragraph holds three groups, do they become one Anki note with three
cards, or three notes with one card each?

| | one note per block | one note per label-group |
| --- | --- | --- |
| ordinal drift | still possible for **unlabelled** groups | **unrepresentable** |
| anchor needed for the block | **yes — unsolved** | **no** — the label is the anchor |
| Anki sibling burying | kept | **lost** |
| paragraph text | stored once | duplicated per note |

The second makes the anchor problem disappear rather than solving it. **Its only real cost is
sibling burying** — Anki's ability to keep two gaps from one paragraph off the same day — and that
is the open question for Marc: is seeing them together fine?

### B. A similarity fingerprint, traced across git commits

Marc's, and it corrects a claim made against it in conversation. The objection was that a
similarity fingerprint cannot be an identifier because similarity needs a threshold, and a
threshold is a number nobody can justify — the same grounds on which `EVOLVABILITY.md` rejected a
proportional orphan guard.

**That objection holds for a single comparison and fails for a traced one.** Comparing two
arbitrary states six months apart is hard. Comparing **consecutive commits** is easy, and chaining
small deltas crosses the six months without ever making a large comparison. This is exactly how
`git log --follow` tracks a file across renames, and it works.

It also answers the threshold objection rather than dodging it: git ships a similarity threshold
(`-M`, 50% by default) that has been exercised on every repository in the world for twenty years.
Inheriting a battle-tested default is categorically different from inventing a number.

**Keep the two mechanisms separate**, because they have different properties:

- **The fingerprint is the key** — a pure function of current content, derivable from the vault
  alone with no memory. That preserves the property the design rests on: a sidecar is a cache,
  never an oracle (`CARD-MODEL.md`).
- **Git is the rename detector** — consulted only to *migrate a binding* whose key has moved:
  *"Anki holds key A, the vault now has key B, and here is the commit where A became B."*

Kept separate, identity never becomes a function of history — which would be the expensive change.
History is evidence for a **repair**, and the design already has a shape for that: `relink`, the
manual version, reattaching an orphan to a new key. Git-as-evidence is the same operation with the
pairing **supplied rather than guessed**, which satisfies the standing ruling that anything fuzzy
may rank but never apply. Git is not guessing; it is reading a record of the edit.

**Degradation is graceful, which is rare and worth having.** No commits means no trace, which means
today's behaviour — orphan plus create. The tool is strictly better when git is used and no worse
when it is not. As Marc put it: one should not fight indiscipline.

---

## The precondition, met on 2026-08-29

> **MET, AND STILL NOT ENOUGH — for a different reason, and one with an end.** Marc put the vault
> under git the same day this was read back to him. `git rev-parse` now succeeds and the
> repository has **zero commits**, so M1 still cannot run: a replay needs history, and there is
> none yet. That is a waiting period rather than a decision, and it ends with the first commit —
> which is why the measurement is now an ordinary open item, `oas-nmg`, rather than a blocked one.
>
> Candidate B is unblocked on the same terms.
>
> _The original, unedited:_

**The vault is not under git.** VERIFIED 2026-08-27: `git rev-parse` fails in
`/Users/marc/📖-obsidian-anki-srs-📖/`.

That blocks candidate B outright, and it blocks something already on the books: **`EVOLVABILITY.md`
lists M1 — the git replay that classifies every key death and would settle whether heading renames
actually dominate the cost of change — as gating the identity decision, and describes it as "an
afternoon, no Anki needed". It cannot run.** It walks the vault's git history and there is none.
Running it against the fixture vault in this repository would measure how the fixtures were
authored, not how Marc edits notes, and it would be easy to mistake the result for the real one.

One `git init` and a habit of committing unblocks both.

---

## The packaging ruling

_Decided by Marc, 2026-08-27. **Read this before building anything in this document.**_

### The words, because two of them are Anki's and one is this tool's

- An Anki **note** is content — a set of fields. It is not what you review.
- An Anki **card** is what you review, and it carries the scheduling: interval, ease, due date,
  review log. **One note generates one or more cards.**
- Cards generated by the same note are **siblings**. Anki can deliberately space siblings apart so
  you do not meet two of them in one session. This is called **sibling burying**.
- A **group** is one or more highlighted phrases sharing a label — `==1|radius== ==1|ulna==` is one
  group, and it blanks both phrases together as a single card. An unlabelled `==radius==` is a
  group of one. **One group makes one card.**

### The two ways to package the same cards

Take one paragraph with two groups:

```markdown
The ==1|radius== and the ==2|ulna== are forearm bones.
```

Both designs produce the same two cards to review. They differ in how those cards are packed:

| | **per-group** | **per-paragraph** |
|---|---|---|
| Anki notes made | 2, each holding the sentence | 1, holding the sentence |
| cards per note | 1 | 2 |
| siblings? | no — they are unrelated notes | yes |
| Anki can space them apart | **no** | **yes** |
| what names the note | the group's label, which the author wrote | **nothing yet — see below** |

### The ruling

**Ship per-group first. Per-paragraph is REQUIRED, not optional.**

Per-group is first because it can be built: the label the author writes is already a stable name,
so nothing new has to be invented. It is a **first step chosen for buildability, not a
destination.**

**Why per-paragraph is required, in Marc's judgement and against Claude's initial one.** Under
per-group, five groups in one paragraph are five unrelated notes, so all five can come up in the
same session — you read the same sentence five times with a different phrase hidden each time.
Claude called that low-value repetition to be tolerated. **Marc rejected that: preventing it is the
point.** Sibling burying is a study-quality property, not an internal tidiness one, and it is the
only thing per-group gives up.

### What blocks per-paragraph

**Nothing names a paragraph.** A note needs an identity that survives the author editing it, and:

- **position** breaks the moment a paragraph is inserted above it;
- **a content hash** breaks the moment the sentence is edited — which is the very thing the card is
  about;
- ~~**Obsidian's own block reference, `^abc123`, is eliminated**~~ — **WITHDRAWN 2026-08-29. This
  is what names a paragraph**, and the list above is the reason it had to be something the author
  writes rather than something the tool derives: position and content are both unstable, and no
  amount of cleverness makes a derived name survive the edit the card is about. See the section
  above for how the elimination came to be wrong.

~~Two candidates remain~~ — **and neither is needed for this.** A similarity fingerprint traced
across git commits belongs to REPAIR rather than to naming (`oas-4ti`), and an invisible marker
written into the source was only ever an attempt to store a surrogate the tool would have had to
write. `^blockid` is a surrogate the AUTHOR writes, which is what dissolved the problem.

### How per-paragraph is actually unblocked

_Marc, 2026-08-29, and it removes the need to answer the question above at all._

**THE PREMISE OF THIS WHOLE SECTION IS WRONG, AND THE WORD FOR IT WAS WRONG TOO.** "A block with no
stable name loses its cards" is false. A card whose key moves is not destroyed — it is tagged
`orphaned::`, suspended, and its review history sits in Anki untouched. Nothing is lost. What is
missing is only the RECONNECTION: nothing offers to bind the orphan to the card that replaced it.
The honest word is **disconnected, and recoverable if the tool helps** — never *fragile*.

**SO THE MISSING PIECE IS RECOVERY, NOT NAMING**, and it is already designed. `oas-4ti` records
recovery tiers 3 and 4 — matching a broken identity by SIMILAR content — with tiers 1
and 2, exact hash and exact fields, already built. The long comment at `plan/Planner.scala`'s
`identityErrorFor` says the same in more detail, including the constraint that makes it safe: it
may only ever NAME a candidate, because a wrong rebind moves review history onto the wrong card,
silently and irreversibly.

**WHICH DISSOLVES THE TRADE.** The choice looked like: per-group and lose sibling burying, or
note-scoped labels and pay a numbering tax. Neither is needed. Keep labels scoped to their block as
they are; accept that a block whose clozes are unlabelled re-keys when their text changes; and let
the sync put the orphan aside and PROPOSE the pairing — which is exactly the shape
`REVIEW-QUEUE.md` describes and exactly what the standing fuzzy-matching rule permits.

**THREE PIECES OF THIS ALREADY EXIST**: orphan flagging and suspension; recovery tiers 1 and 2; and
the priced, named, approvable decision — a run that says *this costs N cards holding M reviews,
approve it by this name*. Tier 3 is the missing middle: rank the candidates and put them in that
queue.

**WHAT THIS DOES NOT LICENSE.** Similarity never decides. Identity remains derived from the vault,
and a proposal is something a person approves — see the correction added to *Identity is a matching
problem* above.

### What a later reader must not assume

**That per-group is where this ends.** It is a staging post.

**And that adopting per-paragraph later is free.** It is not, and the cost grows. Moving a
multi-group paragraph from per-group to per-paragraph means several Anki notes must become one —
and Anki has no operation that merges notes while preserving their cards. Cards belong to notes, so
those cards die and their review history dies with them.

**The cost is therefore proportional to how many multi-group paragraphs exist when the switch
happens**, which is zero today and grows every time somebody writes one. MEASURED 2026-08-27: the
collection holds five cloze notes, four reviews in total, and **not one of them has more than a
single group**. Whoever picks this up should re-measure before assuming that still holds.

---

## What is decided, and what is not

**Decided:**

- ~~`^blockid` is out. It reaches the card face.~~ **REVERSED 2026-08-29: `^blockid` is IN, and is
  what names a block.** Reaching the card face was a defect in this tool, repaired by giving the
  grammar a production for it. The author writes the anchor; a cloze block without one is refused.
- A plain content hash is out. Avalanche is the wrong property.
- **The `==<<text>>==` syntax**, decided and shipped 2026-08-28: a bare `==highlight==` is
  emphasis and makes no card, so an author can see which of their highlights are cards.
- The scope of a sub-heading card is the **block**. Sentences and lines are not things the parser
  can see.
- Fuzzy matching belongs in **repair**, never in a key — the standing ruling holds, and candidate B
  respects it by supplying evidence rather than a guess.

**Not decided, and each is Marc's:**

1. ~~**Sibling burying** — is it worth keeping?~~ **DECIDED 2026-08-27 BY MARC: YES.** See
   *The packaging ruling* below, which is where this answer is written out.
2. **Unlabelled highlights** — refused, keyed by their text with the honest consequence that
   editing the text retires the card, or something else? _Note that the current documentation
   already claims the second and it demonstrably does not happen: the planner has no cloze
   awareness at all, so no orphan is ever produced for a retired group._ **Still open: `oas-9yz.1`,
   waiting on recovery tier 3 (`oas-4ti`), and the false docstring is `oas-9yz.2`.**
3. ~~**Whether the vault goes under git**, which gates candidate B and M1 alike.~~ **DONE
   2026-08-29 — Marc put the vault under git.** It has no commits yet, so M1 still cannot run, but
   that ends with the first commit rather than with a decision: `oas-nmg`.
4. **The `%%card%%` uniqueness objection** — answerable by refusing per note rather than per run,
   but unanswered. **`oas-9yz.3`.**

**Decided since, and not in the lists above** — sibling burying is required, so a card is packaged
one Anki note per block rather than one per group; and the cloze card that needs no heading is
built, keyed by its block. Both are in the status block at the top.
