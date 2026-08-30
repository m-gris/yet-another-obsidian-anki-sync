# Learning Model

*The pedagogical reasoning behind this spaced-repetition setup. Deliberately tool-agnostic — if Obsidian, Anki, and markdown were all replaced tomorrow, everything below would still hold.*

> **Provenance of the citations.** Every study named here — Bransford & Johnson, Ausubel, Rohrer & Taylor, Kornell & Bjork, Sweller, the method of loci, and SuperMemo's rules — was recalled by the assistant and has **not been independently checked**. [REQUIREMENTS.md](./REQUIREMENTS.md) marks the evidence class of every claim; this document did not, which let recalled literature read as established fact. The direction of each finding is standard and widely reported; specific figures, dates and experimental details should be verified before being quoted anywhere that matters.

---

## TLDR

Learning is three activities, not two — understanding, then installing, then maintaining — and spaced-repetition schedulers serve only the third.

Cards come in two kinds with different preconditions: **structure cards** (the map — arbitrary, cheap, legitimately memorized *before* understanding) and **content cards** (the concepts — worthless before understanding, because they can only be answered by derivation).

An outline's indentation silently conflates four relations: **kind-of** (a sum type, parent learnable first), **part-of** (a product type, parent *not* learnable first), **needed-for** (an ordering) and **detail-of** (a resolution level). The first two are facts about the domain, the last two facts about the learner, and they should not share a representation.

Memorization wants to be linear, because fluency in A is an input to learning B; consolidation wants to be random. These are not in conflict — they are two different queues, and the well-known evidence for interleaving concerns only the second.

A block graduates when every card in it passes a one-week horizon, beyond which "mastery" needs no definition at all.

---

## Summary

Spaced-repetition tools assume two phases — learn, then review — and that collapse hides the phase where the difficulty lives. There are three: **understanding** (following a derivation with the material present), **learning** (installing it, building the index), and **reviewing** (fighting forgetting). Schedulers serve the third and presuppose the first. The second is the gap.

Two kinds of card follow from that. **Structure cards** encode the map — what sits under what. They are arbitrary, nameable material, cheap to memorize, and legitimately authored *before* understanding; a pre-memorized structure measurably improves later comprehension and recall. **Content cards** encode the concepts, are answerable only by derivation, and are worthless before understanding — a content card met too early trains you to pattern-match its answer string. This is why both bulk and incremental authoring are wanted: they produce different kinds of card with different preconditions.

An outline expresses hierarchy with one gesture, but indentation is asked to carry four distinct relations. Two are structural and correspond to type constructors: **kind-of** is a sum type, so the parent stands alone and variants are deferrable; **part-of** is a product type, so the parent has no inhabitants until every component exists and cannot be mastered first. The other two are not types at all: **needed-for** is an ordering over the learner's state, **detail-of** is a resolution level. The structural relations are facts about the domain — stable, true for everyone. The ordering relations are facts about the learner — personal and revisable. They have different lifetimes and should not share a representation, or editing a study path silently edits the domain model. A route is a linear extension of the partial order the structural relations define; many valid routes exist, and choosing one is a pedagogical act rather than a derivation.

Memorization wants to be linear, consolidation random. If B builds on A, then A must be retrievable without effort before practising B is worth anything, or every B repetition is spent re-deriving A. This is not an argument against interleaving, which is well supported — it is an argument that there are **two queues**. Interleaving evidence concerns the review queue; the objection concerns the new-card queue, where meeting a card whose prerequisite you have never met is noise rather than desirable difficulty. Conflating the two produces the standard non-answer.

A block graduates when *every* card in it passes a one-week review horizon — minimum over the block, not average, since a weak card must not hide behind strong ones. The rule should latch (a lapse must not un-graduate a block), should count un-introduced cards explicitly, and should treat a long-stalled block as evidence of a badly written card. Beyond this, "mastery" needs no definition: the learner decides when to widen scope.

---

## Full

### Three activities, not two

Spaced-repetition tooling usually assumes two phases: you learn something, then you review it. That collapse hides the phase where most of the difficulty lives.

| Activity | What it means | What governs it |
|---|---|---|
| **Understanding** | "I can follow the derivation with the material in front of me" | dialogue, reading, tracing examples |
| **Learning** | "I have it" — first encoding, building the index | order, structure, sequence |
| **Reviewing** | fighting forgetting over months | spacing, interleaving |

Spaced-repetition schedulers serve the third. They *assume* the first already happened — SuperMemo's own formulation of the rules for writing flashcards opens with "do not learn if you do not understand" and "learn before you memorize."

The second activity is the gap: not comprehension, not maintenance, but installation — and the phase where ordering matters most.

### Two kinds of card

| Kind | Content | Legitimate to author before understanding? |
|---|---|---|
| **Structure cards** | the map — what sits under what | **Yes.** Names in an order. Arbitrary material, cheap to memorize. |
| **Content cards** | the concepts themselves | **No.** Relational; answerable only by derivation. |

A content card met before its concept is understood cannot be answered by recall. If you cannot derive it, you pattern-match the answer string — and the card silently teaches you that you know something you do not.

Structure cards have the opposite property. "System Design contains CAP Theorem, which splits into Consistency and Availability" is a structural fact, and requires understanding none of the three.

This distinction is the reason both bulk and incremental authoring are wanted. They are not rival workflows over one artifact — they produce different kinds of card with different preconditions. Bulk-draft the map; write content cards as understanding lands.

**Why memorize the map first.** Bransford & Johnson (1972) gave subjects a deliberately abstract passage about sorting items into groups; providing the title beforehand raised both comprehension ratings and recall substantially, with the text unchanged. Ausubel's subsumption theory says the same thing structurally: new material anchors to existing superordinate structure. The method of loci is the extreme demonstration — even an *arbitrary* pre-memorized structure improves retention of unrelated items, so a *meaningful* structure should do at least as well.

**A distinction worth keeping sharp.** "Memorize the map first" is not the same claim as "master each level before seeing the next." The first is cheap and well-supported. The second runs into composition: abstractions are frequently *made of* the details that would be gated, so mastering the abstraction first often means memorizing a slogan. The two card kinds above are what separate them.

### Four relations, not one

An outline expresses hierarchy with a single gesture — indentation. But indentation is asked to carry relations that have *opposite* implications for learning order.

Consider an outline where "CAP Theorem" contains "Consistency", which in turn contains "Linearizability". The same visual gesture encodes two different things: Consistency is *one of the three components* of the CAP theorem, whereas Linearizability is *a stronger kind of* consistency.

| Relation | Type-theoretic shape | Can the parent be learned first? |
|---|---|---|
| **kind-of** (specialization) | **sum type** — inhabited by one variant | **Yes.** The parent stands alone; variants are deferrable depth. |
| **part-of** (composition) | **product type** — needs every field | **No.** The parent has no inhabitants until its components exist. |
| **needed-for** (prerequisite) | not a type — an ordering | **Yes**, by definition. |
| **detail-of** (elaboration) | not a type — a resolution level | **Yes.** Detail is optional magnification. |

The product framing is what makes the objection precise: there is no "CAP Theorem" to master before Consistency, Availability and Partition tolerance exist.

The last two relations feel different in kind because they are not type constructors. A prerequisite is an ordering over the *learner's* state — the later concept's definition literally mentions the earlier one, so it cannot even be stated first. An elaboration is the same content at higher magnification, not a different thing.

**The consequential split:**

- **Structural** (kind-of, part-of) — facts about the **domain**. Stable. True for everyone. Linearizability is a kind of consistency whether or not anyone is studying it.
- **Ordering and resolution** (needed-for, detail-of) — facts about the **learner**. Personal, revisable, different for someone with a distributed-systems background.

Encoding both with one gesture means editing a learning path silently edits the domain model, and vice versa. They have different lifetimes and should not share a representation.

A route through a set of concepts is a **linear extension** of the partial order the structural relations define: a directed acyclic graph admits many topological sorts, and a curriculum picks one. Many valid routes exist over the same domain; choosing among them is a pedagogical act, not a derivation.

### Blocked first, interleaved after

**Memorization wants to be linear. Consolidation wants to be random.**

The argument is compositional. If concept B is built on concept A, then A must be retrievable *without effort* before practising B is worth anything — otherwise every repetition of B is spent re-deriving A. Fluency in A is an **input** to learning B, not merely a nicer ordering. Cognitive-load theory says the same from the other side: interleaving imposes a switching cost that is unaffordable while a schema is still being constructed, and affordable once it has been automated.

**What this is not a claim about.** Interleaving at *review* time is well supported — Rohrer & Taylor (2007) on interleaved mathematics practice, Kornell & Bjork (2008) on learning painters' styles, where participants *judged* blocked practice better while *performing* better after interleaved practice. None of that is in dispute here.

The distinction is that there are **two queues**, and the evidence above concerns only the second:

- **New-card queue** — material being met for the first time. Meeting a card about linearizability before ever meeting consistency is not a desirable difficulty; it is noise.
- **Review queue** — material already met. Shuffle freely.

Conflating them produces the standard rebuttal ("shuffling is better, the algorithm handles it"), which defends the review queue against an objection about the introduction queue.

**Caveat.** Over-extending the blocked phase produces *context-bound* fluency: you can do it when you know it is coming. The blocked phase should end at fluency, not at comfort.

### Siblings must not share a session

A fact asked several ways produces several cards, and each card's answer is visible on its siblings' prompts. A three-way concept-descriptor card is the clearest case: every card shows two of the three fields and asks for the third, so each answer is printed on the front of both the others. Meeting two of them in one sitting makes the second a recognition test rather than a recall one — the same failure mode as a content card met before its concept is understood, and just as invisible from the inside.

This is an **interference** claim, not an ordering one. It concerns the several askings of a single fact, not the sequence of distinct concepts, and it applies at introduction, in review and mid-learning alike.

*Realised on 2026-08-27 by Anki's three burying settings — new, review and interday learning siblings — which defer a sibling by a day and re-defer it on each encounter. It is the smallest available step toward the ordering programme above: nothing is authored, computed or written, and it is a checkbox rather than a mechanism.*

### Ending the blocked phase

A block graduates when **every** card in it is past a one-week review horizon.

- **Every, not average.** A weak card must not hide behind strong ones; the measure is the minimum over the block.
- **Why a week.** A card scheduled more than seven days out has survived successful recalls *with gaps*, which is evidence of more than session-local comfort — the caveat above, addressed directly.
- **Configurable.** The horizon is a knob, not a constant.

Three refinements:

1. **Latch it.** A lapse drops a mature card's interval back below the horizon, which would silently un-graduate the block and cause oscillation. Once a block has passed, it stays passed.
2. **Define membership against un-introduced cards.** If a block still holds cards never seen, the rule is either trivially satisfied by the introduced subset or never satisfiable, depending on how membership is counted. The intended reading is: all cards introduced, *and* all past the horizon.
3. **Treat a stalled block as a signal.** One pathological card can hold a block indefinitely — and such a card is usually badly written rather than genuinely hard. "This block has not graduated in three weeks" is useful information about card quality, and should surface rather than be worked around.

### What deliberately is not computed

**"Mastery" needs no definition beyond the horizon rule above.** Attempts to specify it — average interval, predicted retention, proportion of mature cards — multiply thresholds without adding information the learner does not already have.

The learner decides when to widen scope. Judgment stays where judgment lives.
