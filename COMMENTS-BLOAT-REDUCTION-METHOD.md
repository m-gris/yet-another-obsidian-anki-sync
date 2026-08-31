# How to cut this project's prose without losing any of it

This repository carries roughly 10,400 lines of comment over 5,800 lines of production code, plus a documentation tree that says much of the same thing again. The work this file describes is reducing that hard — **without losing anything** — by moving what the prose asserts into something that enforces it: a type, a test, a single source of truth, a lint, a golden file, or a different arrangement of the code.

Written 2026-08-31, from doing it to `model/BlockAnchor.scala` and `model/CardKey.scala`. Every rule below cost something to learn; where a rule exists because of a specific mistake, the mistake is named, because the reasoning is what makes the rule worth keeping.

---

## The finding that reframes the job

**Volume was never the problem.** Measured across production: about 87% of comment lines are genuine rationale, not restatement. The defects lived in roughly 2% of the mass, and they were all one shape.

**A comment may describe the code it sits on. It may not describe the state of the project, or what another file does.** Those two have no coupling to the diff that would falsify them — nothing brings them into the change that makes them wrong. Every one of the ten wrong comments found so far was that class: 24 lines asserting what is or isn't built, 122 naming another source file, 69 pointing at a document's content.

So this is not a tidying exercise. It is a design review that uses the comments as the entry point, because comments are where a project's unencoded assumptions are visible.

---

## Order of work, per file

1. Read the file whole.
2. Check every claim against the code. Expect most defects to be rationale that is *factually wrong*, not restatement.
3. Encode what can be encoded.
4. Restore to beads what cannot.
5. **Only then cut.**
6. Mutate the production code and watch each new test fail.
7. Adversarial review.
8. Commit, split by concern.

**Code before documents.** The documents largely duplicate the comments, so correcting them first polishes text that is about to be deleted. This was learned the expensive way: a document section was rewritten in full on the morning of the day it was decided the documents come last.

---

## One comment, three possibilities

- **"We chose A over B."** Rationale. Permanent prose.
- **"B would have been worse."** A counterfactual about code that does not exist. Nothing to assert against. Permanent prose.
- **"…because otherwise X happens."** A claim about *behaviour*. **This is a test** — already written somewhere, or waiting to be.

The third wears the second's clothes, and it is where most of the prose actually is. Both times one was chased down, it turned out to be pinned already — once in four separate places, with the comment as the fifth telling.

---

## Cutting hard without losing anything

The question for a block of prose is not *"is this comment good?"* It is **"what would have to exist for this comment to be unnecessary?"** When the answer is "nothing", keep it — and that residue is small.

| the prose says | encode it as |
|---|---|
| "must" / "never" / "cannot" / "by construction" | **make it unrepresentable** — sum type, opaque type, smart constructor, `NonEmptyVector`. If the type *already* enforces it, the sentence is deletable on the spot, and noticing that is a finding. |
| the same fact in two places, or "X and Y must agree" | **one source** — a table both sides read, or derive one from the other |
| "for any X…", round-trips, idempotence, stability under an edit | **a property test over the domain**, not examples |
| "…because otherwise X happens" | **an example test**, with X as its failure message |
| "anyone adding a case here must also…" | **a test that reads the source**, so the build fails. `Marker.Documented` is the precedent |
| observable output must not drift | **a golden file** |
| two similarly-named things needing disambiguation | **rename one** |
| "the only caller is X" | **make it private** — the compiler then says it |
| "this lives here because…" | **move the code** |
| behaviour that lives in *another* file | **move the comment, or delete it** — never leave it |
| a convention nothing enforces | **a lint** — but only if the convention is real |

Two of those carry warnings. An exhaustive property test over a character domain caught a widening that a seven-point sample could not, so prefer the domain to examples. And a lint was nearly written for a package convention that turned out not to exist — nine other production files did the very thing the "convention" forbade.

**What makes aggression safe is the order:** encode, mutate to prove the encoding bites, *then* cut. Done that way, cutting cannot lose anything, because what is being deleted has already been proven redundant.

**On net lines:** an encoding that costs more than the prose it removes *and* buys no enforcement is not progress. One that costs more and *does* buy enforcement usually is. `CardKey.scala` shed 26% of its comments while gaining five tests and losing a live bug. Optimise what is checked, not the ratio.

---

## Rules, each paid for

- **A green test proves nothing.** Mutate the production code and watch it fail, or it is not a test. Two tests written for `BlockAnchor.scala` passed and tested nothing: one asserted at a layer where the property could not fail, the other sampled three points and called itself a universal. Prose had already been deleted on their strength, so the cut was a genuine loss until a review said so.
- **Cut only after the replacement exists.** "The bead already covers it" is checked sentence by sentence, and anything missing is appended *before* the cut. Checked by impression once; two arguments were lost.
- **Numbers in prose rot.** Delete the number, do not update it. Seven stale counts found so far, in comments, in documents, in test names and in a golden file's header.
- **Do not file — do.** A one-line inconsistency does not need a ticket. Beads are for decisions and for reasoning that has no other home.
- **Match the file's own wrapping.** Do not impose a column width on a file that soft-wraps.
- **Watch for hardening.** A hedge does not survive being restated by an agent, and it never gets restated back down. *"The tool never writes the vault"* began as an aspiration — avoid noise in notes, avoid brittleness — and became "the one design property everything else serves", then "structural", then "deserves absolute defence", then a promise printed in `--help`, across about nine retellings. On the way it foreclosed a design option: authors hand-place `^blockid` anchors because the tool "cannot" write them. **When a rule reads as absolute, find out who actually ruled it.** Never add a test that enforces an aspiration — that is the step that makes the escalation permanent.

---

## Agents

Multi-agent work is not optional decoration here. It is load-bearing for two structural reasons.

**A reader who has read the file cannot judge whether its comments are redundant.** By the time you reach a comment you already understand the design, so it looks redundant — which is exactly how the one irrecoverable thing gets deleted. Two hand passes took `CardKey.scala` from 267 comment lines to 243. Three fresh agents took it to 198, and found a live bug and five tests worth writing on the way.

**Nobody reviews their own tests honestly.** The two worthless tests above read as adequate to their author. An adversarial pass found both, in the first round.

The shape that worked:

- **Three positive lenses, one mechanism each** — types, tests, structure. They find different things. On one file they disagreed once, and it was settled properly: one agent guessed a method was dead code, another compiled a probe and disproved it.
- **An adversarial pass after every file**, briefed to refute rather than validate, and told to say plainly when a claim survives the attack. Knowing which parts held is as useful as knowing which failed.
- **The leverage is in the brief, not the count.** Each lens must name its mechanism, cite `file:line` on every finding, give a net line count on every proposed encoding, and be told outright that an encoding costing more than the prose it removes is not a finding. A vague brief produces padding.
- **Tell them to report finding nothing.** Otherwise they invent.
- **Tell them how to deliver.** A subagent's ordinary output reaches nobody; it has to send it explicitly.
- **Check the agents too.** One asserted a method was dead; another disproved it. One finding was relayed to the wrong author and the agent itself supplied the correction.

Standing gates worth keeping in every adversarial brief, because each was earned: mutation-check every test written to pay for deleted prose; verify "the bead already has it" sentence by sentence; prefer a single source of truth to a test when the invariant is "two definitions must agree"; and check that every clause of a test's name is supported by an assertion.

---

## Working together

Recommend, do not survey. Do not hand back a decision already reasoned through. Assume the reader does not have the file open and is doing something else — verbose is not thorough, it is noise. Act when the path is clear; ask only when the answer changes the work.
