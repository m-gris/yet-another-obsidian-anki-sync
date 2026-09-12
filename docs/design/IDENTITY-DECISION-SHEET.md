# The identity decision sheet — five rulings the machinery cannot make

_Written 2026-09-12. This is two things at once: **the open policy questions** left by the
identity-worlds deck (84 scenarios run under three rival policies on branches
`world-conservative`, `world-perkind`, `world-maximal`, all off `world-deck`), and **a hand-built
mock of the review artifact** that `REVIEW-QUEUE.md` envisions — each question is presented the
way the future review surface would present a pending decision: the edit, the evidence, the
candidate actions, their costs. Every quoted message below is verbatim from a committed
transcript (`TRANSCRIPT-<world>.md` on the world's branch); every count is from running the
deck, not from reasoning about it._

_To rule: tick one box per decision, or annotate. Nothing here is a recommendation unless
marked as one._

**The standing rulings this sheet works under** (from the move-detection record, bead `oas-ugc`):

- **R1** — rewording a card in place is the same card; review history must follow.
- **R2** — a concept-descriptor card is a three-way relation; its parent concept is constitutive,
  so moving a descriptor under a different concept makes a different card.
- **R3** — *(revised 2026-09-12; original 2026-09-05 said: when a card's subject changed,
  history must not silently move)* — a subject change alongside changed substance never pairs
  (the comparison floor refuses it already); a subject change where **all** descriptors and
  descriptions are unchanged **and the cluster stayed in place** (the path agreed) is a
  **rename** — same cards, history follows ("Least Element" → "Bottom"). The path weighs both
  ways: a name change combined with a move grades weaker and becomes a question. What must
  still never follow is a **re-parent under a concept that goes on existing** (R2, scenario
  S24) — and the discriminating fact is itself a path fact the seam does not yet consume:
  whether the old concept's path survives anywhere. Building that discrimination is the
  remaining gate work.
- **R4** — an unambiguous corroborated match applies automatically (a wrong orphan costs more
  than a wrong, visible reassignment).
- **R5** — a duplicate key refuses only the cards involved, never the whole run.

**The fact underneath every question here, proven by the deck:** _"the author reworded this
card"_ and _"the author replaced its subject"_ produce **byte-identical evidence**. Five fixture
pairs demonstrate it (S01/S02, S20/S21, S22/S23, S33/S34, S56/S56b). Under R3 as originally
stated, no policy seeing only derived evidence could honour R1 and R3 at once. **Resolved
2026-09-12 by revising R3**: with substance unchanged, the edit is read as a rewording — a
"replacement" that keeps every description byte-identical is priced as not a real case, since a
genuine replacement changes the descriptions and then fails the floor by itself.

---

## Decision 1 — a plain card's own heading changed, nothing else did

**The instance (S02):** in a note, `## Kafka delivery` became `## NATS delivery`; the body is
byte-identical. The evidence reads: _"note 1, which held 'kafka delivery' in n2, is 'nats
delivery' in n2 — a different name, in the same place, over the same substance."_ S01 is the
same observable produced by an innocent rewording (`## Temporal coupling` → `## Temporal
coupling of services`).

**The question, as the review tool would ask it:**
> Did you reword this card, or replace it with a different one? The tool cannot tell.

**What the three worlds did:**

| world | action | its own words (abridged) |
|---|---|---|
| conservative | queue for review | "held for you to decide — the card's OWN NAME changed, and this world never moves review history on that evidence" |
| per-kind | apply | "reassigned, keeping its review history: nothing this card holds as its subject moved — the Basic note type shows no ancestor as one of the card's own fields" |
| maximal | apply | "REVIEW HISTORY WAS MOVED — Anki note 1 now answers to 'nats delivery', keeping every review it has" |

**RULED — 2026-09-12, by Marc, in conversation:**

- [x] **Follow, silently — with the ledger promoted from option to standing property.** In
  Marc's words: "this is not a cdd card.. but... in the same obsidian note... the odds are
  that it should follow silently" — and, on the ledger: "should probably have been offered
  from the start !!! audit trail !!! should probably [be] used / logged ALL the time".
  So the silently/ledger split was a false dichotomy: **every** automatic history-carrying
  action is logged, always, durably (the run-ledger bead `oas-ij3`, un-deferred by this
  ruling); "silently" means no interruption at sync time, never unrecorded.
- [ ] ~~Always ask~~

**Members:** S02, S09 (reword + re-parent together), S14 run 2 (recreate reworded after a
parked deletion), S56b (sequence-card twin). Settled contrast: S01, S56 — the reword half,
which R1 already rules must follow.

---

## Decision 2 — the *subject slot* changed: a concept heading, or a table row's subject

**The instance (S23 / S22):** `# Kafka` became `# RabbitMQ` with both descriptor bodies
untouched (S23) — and the byte-identical observable arises from `# Kafka` → `# Apache Kafka`,
an innocent rename (S22). Same shape on tables: the `Queue` row's subject cell became `Stream`
with every value identical (S33) versus the typo fix `Qeue` → `Queue` (S34).

Here R2/R3 already settle half the question: **blind auto-follow is wrong** — a rule with no
discriminator cannot apply the rename without also applying the replacement (this is the
baseline's standing defect, inherited knowingly by the maximal world). What remains open is what
to do *instead*:

**What the three worlds did (S23, per card):** conservative queues ("held for you to decide");
per-kind parks ("NOT reassigned — 'Concept' is not filing: it renders this card's PARENT, part
of what the card asserts, and it changed"); maximal applies ("REVIEW HISTORY WAS MOVED") — the
flagged defect.

**RULED — 2026-09-12, by Marc, in conversation (and it revises R3, see the rulings list):**

- [x] **Follow when it is a rename in place** — in Marc's words: "if all descriptors &
  descriptions are unchanged and the concept did [change], then it is the same card... just
  reworded the concept (example: 'Least Element' could become 'Bottom')" — and, his addition,
  the **path** weighs in: the cluster stayed where it was. The uniform rule covers the table
  row (`Qeue → Queue`) identically — a row is the same cluster shape one kind over, and Marc
  confirmed no distinction ("how does that differ then from the case i just ruled?").
- [ ] ~~Park always~~ — rejected: strands every innocent rename.
- [ ] ~~Ask always~~ — rejected: the substance-and-place agreement already answers it.

The gate this requires (build work, not configuration): follow when substance unchanged AND
place unchanged AND the old concept's path survives nowhere; when the old path goes on existing
it is a re-parent — a different card by R2 (S24), never followed. A name change combined with a
move (path changed too) grades weaker and becomes a question rather than a follow.

**Members:** S22, S23 (concept heading), S33, S34 (table row subject). S41, S42 nominally
belong here but see the harness anomaly in the appendix.

---

## Decision 3 — the *descriptor slot* changed, the concept did not

**The instance (S21 / S20):** under `# Kafka`, `## Definition` became `## Contrast` with the
body byte-identical (S21) — indistinguishable from `## Definition` → `## Formal definition`, a
rewording (S20). Same shape one level over: a table column header renamed (S31), a relation's
predicate renamed (S65).

This differs from Decision 2 in what the changed segment *is*: the concept — the card's subject
— is untouched; what changed is the facet's label. And a *replaced* descriptor over an untouched
body is semantically degenerate (a "Contrast" holding a definition's text), which is why the
spec leans toward treating this as wording. The lean is a suggestion, not a ruling.

**What the three worlds did (S21):** conservative queues; per-kind applies ("the parent is
constitutive, and it is unchanged — what changed is the card's own name"); maximal applies.

**RULED — 2026-09-12, by Marc, in conversation:**

- [x] **Follow** — in Marc's words: same description under the same concept, descriptor
  changed — "this is actually the same card, just reworded." History follows. Accepts that a
  deliberate slot repurposing over an unchanged body — if that is ever a real thing —
  would silently carry history.
- [ ] ~~Ask~~
- [ ] ~~Park~~

The ruling was stated for concept-descriptor-description cards; it extends to the whole group
because the other members are the same shape, not analogues — a relation card IS a cdd card
("subject is concept, predicate is descriptor, object is description", `extract/Edges.scala:15`),
and a table column header is the descriptor position of every pair card in that column.

**Members:** S20, S21 (descriptor heading), S31 (column header, values distinct), S65
(relation predicate).

---

## Decision 4 — the name's *markup* changed, its text did not, and the card also moved

**The instance (S11):** one word of the heading was bolded, and the section moved under
`## Notes`. The key's segment is markup-free so it still agrees; the rendered front field
differs. Today this is deliberately `Unaccounted` → report-only (the mechanism's docstring
names "the author re-bolded the heading" explicitly).

**What the three worlds did:** conservative and maximal queue it (maximal loudly regretting:
"THIS WORLD WOULD HAVE REASSIGNED THIS AUTOMATICALLY AND THE SEAM WILL NOT ALLOW IT");
per-kind parks it.

**RULED — 2026-09-12, by Marc, in conversation:**

- [x] **Count it as the same card** — in Marc's words: "flashcard 2way... same front, same
  back... same card." The card is its front and its back — the words of the heading and the
  body. Both unchanged ⇒ same card; **markup is rendering, not identity.** The card follows
  its move with history intact, and its Anki face updates to show the new markup.
- [ ] ~~Keep it report-only~~

Mechanically: segment-agreeing markup divergence becomes accounted-for instead of blocking —
the seam's `Unaccounted` outcome for this shape (deliberate today, per the mechanism's own
docstring) is overruled by this ruling.

**Member:** S11.

---

## Decision 5 — ratify (or not) the retroactive default

**The instance (S14):** run 1 deletes a section; the orphan parks. Run 2 recreates it,
reworded. The built Planner already lets the parked orphan participate in run 2's recovery —
retroactive-inclusive, parked-only on a partial scan — citing the ruling of 2026-09-05. The
record notes this default was elected by the builder, not ratified by you.

**RULED — 2026-09-12, by Marc, in conversation:**

- [x] **Ratify** — parked orphans from any earlier run participate in every later survey,
  for as long as they live (i.e. until pruned — see the prune ruling on `oas-m0a`). The
  builder's elected default is confirmed as the answer.
- [ ] ~~Restrict to same-run only~~

**Member:** S14. (Note: whatever Decision 1 says about the reworded recreation applies to run 2
here — this decision is only about *whether the old orphan is in the candidate pool at all*.)

_Context ruled 2026-09-12 (recorded on the prune bead, `oas-m0a`): a vault deletion must
EVENTUALLY really delete — park at sync time stays, and `prune` becomes mandatory rather than
optional. That bounds this decision: an orphan can only claim a later recreation while it
lives, i.e. between its parking and its pruning._

---

## Deferred, deliberately: the report's flavours

Five contested scenarios (S16b, S27, S32, S60, S64) diverge **only in how the worlds say "I
will not act"** — queue-for-review vs park-and-report vs refuse. In every one, the settled
mechanism already forbids applying (equal claimants in S16b and S32; the
corroborated-but-unvouched id changes of S27, S60, S64 — all report-with-residual by prior
ruling). Choosing among the flavours is choosing the semantics of the review surface itself —
what "queued" means, what "parked" means, whether "refuse" is a distinct state — and that is
the `REVIEW-QUEUE.md` / `oas-1zq` design, taken up separately. Ruling on them here would
pre-design that tool through a side door.

---

## Appendix A — settled already, but the shipped mechanism must change

_(Revised 2026-09-12, after the R3 revision.)_ The maximal world's flags on S23, S33 and S41
**dissolve** under R3-as-revised: with substance and place agreed, following is now the ruled
behaviour, so on those scenarios the shipped baseline was right by accident. What remains
forced before `move-build` merges is narrower and sharper: **S24** — a descriptor re-parented
under a concept that goes on existing is a different card by R2, and the baseline moves its
history today because the seam cannot yet see the discriminating fact (whether the old
concept's path survives). The gate work is that discrimination, per Decision 2's ruling.

The conservative world's two flags (S01, S56) are the mirror image — its deliberate departure
from R1's required-follow — and stand or fall with Decision 1.

## Appendix B — a harness anomaly, so it is not mistaken for evidence

S41/S42 are billed as the *row-card* view of S33/S34, whose point is that a row card renders
the subject into its `Substance` and should therefore fail the comparison floor rather than
pair. All three transcripts show their evidence blocks byte-identical to S33/S34's *pair-card*
blocks — the deck harness reused the synthesized notes, and the row-card mechanic is not
actually exercised anywhere. A defect of the deck on `world-deck`, identical across worlds,
upstream of every policy; do not count S41/S42 as independent members of Decision 2.
