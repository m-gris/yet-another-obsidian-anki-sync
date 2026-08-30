# Redesigning cloze

> **Work on this document** — `bd list --all --spec docs/design/CLOZE-REDESIGN.md`
>
> Closed means built, and the closing reason says what shipped; open means outstanding. **This
> document records decisions and reasoning, never progress** — a status kept in two places goes
> stale in one of them.

_Named `ANCHORS-BELOW-A-HEADING.md` until 2026-08-27. That named one mechanism inside it —
attaching a card to something smaller than a heading — while the document is about cloze as a
whole: what text a card shows, how it is packaged into Anki, what identifies it, and what has been
ruled out. Renamed with `git mv`, so the history is intact._

_Written 2026-08-27, from a design conversation between Marc and Claude. Claims marked VERIFIED were established by running something; everything else is reasoning, and
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

Cloze was meant to be the escape hatch — the thing you reach for when knowledge does not fit a
rigid shape — and it was the most rigid thing in the system: the marker went on a **heading**, and
the whole section body became one card's text, so every gap in a five-paragraph section showed all
five paragraphs.

**A cloze card is now scoped to its block**, and needs no heading at all. Write
`==<<text>>==` anywhere in a note that has a frontmatter `id`, give the block an Obsidian block
identifier, and that block becomes one Anki note whose cards are its groups:

```markdown
The ==<<1|radius>>== and the ==<<2|ulna>>== are forearm bones. ^forearm
```

Two cards, **siblings of one note**, so Anki can keep them out of the same session — which is the
whole reason the card is scoped to a block rather than to a group. The card shows that paragraph
and nothing around it.

**Three things had to be true at once**, and each was a separate argument: a bare `==highlight==`
must stay emphasis, or nothing tells you which of your highlights are cards; the block needs a name
that survives you editing the sentence the card is about, which nothing derived can do; and the
name has to be written by the **author**, because a tool that writes into your vault is a different
tool. `^blockid` answers all three, and a cloze block without one is refused rather than given an
identity that will not hold.

The arguments that produced each decision — including the alternatives eliminated on the way — are
in the beads the query above lists. What follows is the design as it stands.

---

## What names a block, and why nothing derived can

A card is anchored at a **node** of a note. `CardPath` already has three cases — a chain of
headings, a frontmatter property, the note itself — and each was cheap to add because each anchor
had an obvious **name**: the heading text, the property name, nothing at all. The codec reserves
room for a fourth (`model/CardKey.scala`, the leading-empty-token scheme), so adding `Block` costs
almost nothing structurally.

What it costs is a name. A paragraph does not have one. And a card whose anchor moves is a card
that detaches from its review history — the same failure `EVOLVABILITY.md` §4 calls the system's
cost centre, arriving one level further down.

Every candidate was judged on one question: **does the anchor survive the author editing the thing
it anchors?**

**The answer is `^blockid`, and it is the author's to write.** Nothing derived from a paragraph
survives the edit a card about that paragraph invites — not its position, not a hash of its text,
not a key projected onto the labels inside it. That is the signal to stop deriving and assign,
which is what a surrogate key is; and what made a surrogate look impossible is that it must be
written down, while this tool has never written to the vault. It does not have to. Obsidian
generates a block identifier with a keystroke, the author writes it, and a cloze block without one
is refused — the same contract a heading already has, where declaring the marker is how you ask for
the card.

**One consequence, recorded where somebody will meet it.** A block identity carries no heading
chain, so moving a paragraph between headings does not re-key its card — which is the point — but
it also means a block card cannot be sheltered by the rule that stops a section failing to build
from making the cards beneath it look deleted. That is the price of a location-independent
identity, and it is noted in `plan/Planner.scala` where the rule lives.

---

## What a card shows

**The block it sits in** — paragraph, list item, quoted block, table. The alternatives were
considered and are worth keeping, because each fails in a way somebody will re-propose.

**A sentence cannot be it.** Markdown has no concept of one and neither does Laika, so the tool
would be splitting on `.` against prose containing `e.g.`, decimals, `Fig. 3`, and — in this
vault — `$B^A$` and other maths. A silent-failure generator.

**A line cannot be it.** Inside a paragraph a line break is soft wrap, an artifact of how the file
happens to be wrapped. Reflowing a paragraph would silently re-cut every card in it.

**A block can.** Paragraph, list item, quoted block, table — real nodes in the parse tree, which
the tool already receives. It is also what Obsidian's own block references address and what
`metadataCache.sections` drills into.

Scoping to the block fixed the original complaint directly. Under a marked heading the body is the
whole **section**, so a section with three paragraphs and five highlights produces five cards *each
showing all three paragraphs*. That is the bundling this ended.

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

### One note per block, and what that buys

```markdown
The ==<<1|radius>>== and the ==<<2|ulna>>== are forearm bones. ^forearm
```

One Anki note, named `^forearm`, holding two cards. The alternative was one note **per group** —
two unrelated notes, each holding the sentence.

**The difference is sibling burying, and it decided the design.** Under per-group, five groups in
one paragraph are five unrelated notes, so all five can come up in the same session: you read the
same sentence five times with a different phrase hidden each time. Claude called that low-value
repetition to be tolerated. **Marc rejected that — preventing it is the point.** Sibling burying is
a study-quality property, not an internal tidiness one, and it is the only thing per-group gives
up.

Per-group was cheaper to build, because the label the author writes already names the note. Per
block needed a name for the block, which is what `^blockid` supplies.

### Why an unstable name is survivable

**A card whose key moves is not destroyed.** It is tagged `orphaned::`, suspended, and its review
history sits in Anki untouched. What is missing is only the RECONNECTION: nothing offers to bind
the orphan to the card that replaced it. The honest word is **disconnected, and recoverable if the
tool helps** — never *fragile*, which this document used repeatedly before Marc corrected it on
2026-08-29.

**So the missing piece is recovery, not naming**, and it is already designed. `oas-4ti` records
recovery tiers 3 and 4 — matching a broken identity by SIMILAR content — with tiers 1 and 2, exact
hash and exact fields, already built. The long comment at `plan/Planner.scala`'s `identityErrorFor`
carries the constraint that makes it safe: a suggestion may only ever NAME a candidate, because a
wrong rebind moves review history onto the wrong card, silently and irreversibly.

**That is what makes block-scoped labels workable.** Labels stay scoped to their block; a block
whose clozes are unlabelled re-keys when their text changes; and the sync puts the orphan aside and
proposes the pairing — the shape `REVIEW-QUEUE.md` describes, and what the standing fuzzy-matching
rule permits. **Similarity never decides.**

### What a later reader must not assume

**That an unlabelled cloze is stable.** A block whose clozes carry no `N|` label re-keys when their
text changes: the card is orphaned and suspended, its review history intact, and nothing yet offers
to reconnect it to the card that replaced it. That reconnection is `oas-4ti`, and labelling is how
an author buys stability in the meantime.

---

_This document holds the current design. Every decision's argument, and the alternatives rejected
on the way to it, are in the beads the query at the top lists — moved there on 2026-08-30 when this
document was cut from 598 lines to a third of that. Everything ever removed is in
`git log --follow -p docs/design/CLOZE-REDESIGN.md`, each removal attached to the commit that
explains it._
