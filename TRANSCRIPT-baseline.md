# The ruled identity policy's deck transcript

All 84 scenarios of `deck/fixtures`, run through the real extraction pipeline and the real
move-evidence survey, with the policy seam filled by `BaselinePolicy`
(`deck/MovePolicy.scala`) — the same "corroborated applies, everything else is reported" policy
the `move-build` Planner has always shipped. What changed is not the policy: it is *which*
pairings the survey is willing to call `Corroborated` in the first place, per the five rulings of
`docs/design/IDENTITY-DECISION-SHEET.md`. Regenerate this file with:

```
scala-cli run . --main-class obsidiananki.deck.runDeck
```

## What the rulings changed, as seen in this transcript

Of the 91 findings the deck's 84 scenarios now produce, `BaselinePolicy` applies 43 and reports
48 without acting. Three scenarios read differently from the pre-ruling mechanism:

- **S24** — a descriptor re-parented under a concept that goes on existing (`# Kafka` still holds
  `## Cost` after `## Definition` moves to `# NATS`) no longer auto-applies. The survey can now
  see that the old concept's path survives, so it reports "a DIFFERENT card under a different
  subject" instead of moving history onto it — Decision 2's ruling that a re-parent is never a
  rename.
- **S11** — bolding one word of a heading while also re-parenting it now applies. The name's
  markup changed, its text did not; Decision 4 rules markup is rendering, not identity, so the
  pairing is `Corroborated·NameAndSubstance` and the Anki face picks up the new markup on
  reassignment.
- **S41 / S42** — no longer share S33's and S34's fixtures. Each now has its own before/after
  vault under a table marked `#flashcard/table/rows` (row cards only), so the row card's own
  evidence — a subject swap or a typo fix, either way stranding `Unexplained` because a row
  card's Substance renders its subject — is visible on its own, with no pair card in the same
  block. This transcript is the first one where the S41/S42 blocks are not byte-identical to
  S33/S34's.

Every scenario the sheet's rulings named as already correct by accident (S22/S23, S33/S34's pair
cards) reads exactly as before — the ruling confirmed the shipped behaviour rather than changing
it.

## The full transcript

```text
WORLD: baseline — the move-build behaviour as shipped
==============================================================================
SCENARIO S01 — reword a plain heading in place   [plain heading card]
EDIT
  '## Temporal coupling' becomes '## Temporal coupling of services'; the body is untouched
DIFF
  create      'temporal coupling of services' in n2
  flag        'temporal coupling' in n2 — suspended, never deleted
EVIDENCE
  note 1, which held 'temporal coupling' in n2, is 'temporal coupling of services' in n2 — a different name, in the same place, Coupling.md:5 (heading); Front: 'Temporal coupling' → 'Temporal coupling of services'
POLICY
  apply-reassign: reassigned, keeping its review history — note 1, which held 'temporal coupling' in n2, is 'temporal coupling of services' in n2 — a different name, in the same place, Coupling.md:5 (heading); Front: 'Temporal coupling' → 'Temporal coupling of services'
LEDGER
  history FOLLOWED onto 'temporal coupling of services' — the existing note was reassigned, no new card

SCENARIO S02 — replace the subject in a plain heading   [plain heading card]
EDIT
  '## Kafka delivery' becomes '## NATS delivery'; the generic body is byte-identical — the same observable as S01
DIFF
  create      'nats delivery' in n2
  flag        'kafka delivery' in n2 — suspended, never deleted
EVIDENCE
  note 1, which held 'kafka delivery' in n2, is 'nats delivery' in n2 — a different name, in the same place, Coupling.md:5 (heading); Front: 'Kafka delivery' → 'NATS delivery'
POLICY
  apply-reassign: reassigned, keeping its review history — note 1, which held 'kafka delivery' in n2, is 'nats delivery' in n2 — a different name, in the same place, Coupling.md:5 (heading); Front: 'Kafka delivery' → 'NATS delivery'
LEDGER
  history FOLLOWED onto 'nats delivery' — the existing note was reassigned, no new card

SCENARIO S03 — reword an unmarked ancestor   [plain heading card]
EDIT
  the ancestor '## Messaging patterns' becomes '## Patterns of messaging'; the marked heading and body are untouched
DIFF
  create      'patterns of messaging / temporal coupling' in n2
  flag        'messaging patterns / temporal coupling' in n2 — suspended, never deleted
EVIDENCE
  note 1, which held 'messaging patterns / temporal coupling' in n2, is 'patterns of messaging / temporal coupling' in n2 — the same name, somewhere else, Coupling.md:9 (heading); Context: 'Coupling › Messaging patterns' → 'Coupling › Patterns of messaging'
POLICY
  apply-reassign: reassigned, keeping its review history — note 1, which held 'messaging patterns / temporal coupling' in n2, is 'patterns of messaging / temporal coupling' in n2 — the same name, somewhere else, Coupling.md:9 (heading); Context: 'Coupling › Messaging patterns' → 'Coupling › Patterns of messaging'
LEDGER
  history FOLLOWED onto 'patterns of messaging / temporal coupling' — the existing note was reassigned, no new card

SCENARIO S04 — re-parent a marked heading in the same note   [plain heading card]
EDIT
  the marked H2 moves to H3 under '## Notes'
DIFF
  create      'notes / temporal coupling' in n2
  flag        'temporal coupling' in n2 — suspended, never deleted
EVIDENCE
  note 1, which held 'temporal coupling' in n2, is 'notes / temporal coupling' in n2 — the same name, somewhere else, Coupling.md:9 (heading); Context: 'Coupling' → 'Coupling › Notes'
POLICY
  apply-reassign: reassigned, keeping its review history — note 1, which held 'temporal coupling' in n2, is 'notes / temporal coupling' in n2 — the same name, somewhere else, Coupling.md:9 (heading); Context: 'Coupling' → 'Coupling › Notes'
LEDGER
  history FOLLOWED onto 'notes / temporal coupling' — the existing note was reassigned, no new card

SCENARIO S05 — move a marked section to another note, verbatim   [plain heading card]
EDIT
  the heading and body are cut from Coupling.md into Inbox.md, chain verbatim
DIFF
  create      'temporal coupling' in n2b
  flag        'temporal coupling' in n2 — suspended, never deleted
EVIDENCE
  note 1, which held 'temporal coupling' in n2, is 'temporal coupling' in n2b — the same path, in a different note, Inbox.md:7 (heading); Context: 'Coupling' → 'Inbox'
POLICY
  apply-reassign: reassigned, keeping its review history — note 1, which held 'temporal coupling' in n2, is 'temporal coupling' in n2b — the same path, in a different note, Inbox.md:7 (heading); Context: 'Coupling' → 'Inbox'
LEDGER
  history FOLLOWED onto 'temporal coupling' — the existing note was reassigned, no new card

SCENARIO S06 — change the heading level with the chain unchanged   [plain heading card]
EDIT
  '##' becomes '###'; the ancestor chain does not change
DIFF
  non-event   'temporal coupling' in n2
EVIDENCE
  (empty — no stranded note to explain)
POLICY
  (no findings reached the seam)
LEDGER
  (no card's standing changed)

SCENARIO S07 — edit the body in place   [plain heading card]
EDIT
  one sentence is appended to the body
DIFF
  update      'temporal coupling' in n2
EVIDENCE
  (empty — no stranded note to explain)
POLICY
  (no findings reached the seam)
LEDGER
  history intact in place: 'temporal coupling' (updated)

SCENARIO S08 — move AND edit the body in one commit   [plain heading card]
EDIT
  S04's re-parent, and the body reworded, in one edit
DIFF
  create      'notes / temporal coupling' in n2
  flag        'temporal coupling' in n2 — suspended, never deleted
EVIDENCE
  note 1, which held 'temporal coupling' in n2, matches nothing the vault now produces
POLICY
  park-and-report: note 1, which held 'temporal coupling' in n2, matches nothing the vault now produces
LEDGER
  new card at ZERO: 'notes / temporal coupling'
  history STRANDED on the suspended note: 'temporal coupling'

SCENARIO S09 — reword the heading AND re-parent it   [plain heading card]
EDIT
  the heading is reworded and moved under '## Notes'; the body is untouched
DIFF
  create      'notes / temporal coupling of services' in n2
  flag        'temporal coupling' in n2 — suspended, never deleted
EVIDENCE
  note 1, which held 'temporal coupling' in n2, is 'notes / temporal coupling of services' in n2 — a different name, somewhere else, Coupling.md:9 (heading); Front: 'Temporal coupling' → 'Temporal coupling of services', Context: 'Coupling' → 'Coupling › Notes'
POLICY
  apply-reassign: reassigned, keeping its review history — note 1, which held 'temporal coupling' in n2, is 'notes / temporal coupling of services' in n2 — a different name, somewhere else, Coupling.md:9 (heading); Front: 'Temporal coupling' → 'Temporal coupling of services', Context: 'Coupling' → 'Coupling › Notes'
LEDGER
  history FOLLOWED onto 'notes / temporal coupling of services' — the existing note was reassigned, no new card

SCENARIO S10 — bold one word of the marked heading   [plain heading card]
EDIT
  'Temporal' is bolded; nothing else changes
DIFF
  update      'temporal coupling' in n2
EVIDENCE
  (empty — no stranded note to explain)
POLICY
  (no findings reached the seam)
LEDGER
  history intact in place: 'temporal coupling' (updated)

SCENARIO S11 — bold one word AND re-parent   [plain heading card]
EDIT
  the heading is bolded and moved under '## Notes'; the body is untouched
DIFF
  create      'notes / temporal coupling' in n2
  flag        'temporal coupling' in n2 — suspended, never deleted
EVIDENCE
  note 1, which held 'temporal coupling' in n2, is 'notes / temporal coupling' in n2 — the same name, somewhere else, Coupling.md:9 (heading); Front: 'Temporal coupling' → '<strong>Temporal</strong> coupling', Context: 'Coupling' → 'Coupling › Notes'
POLICY
  apply-reassign: reassigned, keeping its review history — note 1, which held 'temporal coupling' in n2, is 'notes / temporal coupling' in n2 — the same name, somewhere else, Coupling.md:9 (heading); Front: 'Temporal coupling' → '<strong>Temporal</strong> coupling', Context: 'Coupling' → 'Coupling › Notes'
LEDGER
  history FOLLOWED onto 'notes / temporal coupling' — the existing note was reassigned, no new card

SCENARIO S12 — delete the marked section   [plain heading card]
EDIT
  the marked heading and its body are removed
DIFF
  flag        'temporal coupling' in n2 — suspended, never deleted
EVIDENCE
  note 1, which held 'temporal coupling' in n2, matches nothing the vault now produces
POLICY
  park-and-report: note 1, which held 'temporal coupling' in n2, matches nothing the vault now produces
LEDGER
  history STRANDED on the suspended note: 'temporal coupling'

SCENARIO S13 — delete, then restore verbatim   [plain heading card]
EDIT
  run 1: the section is deleted; run 2: it is restored byte-identically
RUN 1
DIFF
  flag        'temporal coupling' in n2 — suspended, never deleted
EVIDENCE
  note 1, which held 'temporal coupling' in n2, matches nothing the vault now produces
POLICY
  park-and-report: note 1, which held 'temporal coupling' in n2, matches nothing the vault now produces
LEDGER
  history STRANDED on the suspended note: 'temporal coupling'
RUN 2
DIFF
  non-event   'temporal coupling' in n2
  unflag      'temporal coupling' in n2 — the key is back
EVIDENCE
  (empty — no stranded note to explain)
POLICY
  (no findings reached the seam)
LEDGER
  history restored to review: 'temporal coupling' (unflagged)

SCENARIO S14 — delete, park, then recreate reworded   [plain heading card]
EDIT
  run 1: the section is deleted and the orphan parks; run 2: it is recreated with a reworded heading, body verbatim
RUN 1
DIFF
  flag        'temporal coupling' in n2 — suspended, never deleted
EVIDENCE
  note 1, which held 'temporal coupling' in n2, matches nothing the vault now produces
POLICY
  park-and-report: note 1, which held 'temporal coupling' in n2, matches nothing the vault now produces
LEDGER
  history STRANDED on the suspended note: 'temporal coupling'
RUN 2
DIFF
  create      'temporal coupling of services' in n2
  parked      'temporal coupling' in n2 — already flagged by an earlier run
EVIDENCE
  note 1, which held 'temporal coupling' in n2, is 'temporal coupling of services' in n2 — a different name, in the same place, Coupling.md:5 (heading); Front: 'Temporal coupling' → 'Temporal coupling of services'
POLICY
  apply-reassign: reassigned, keeping its review history — note 1, which held 'temporal coupling' in n2, is 'temporal coupling of services' in n2 — a different name, in the same place, Coupling.md:5 (heading); Front: 'Temporal coupling' → 'Temporal coupling of services'
LEDGER
  history FOLLOWED onto 'temporal coupling of services' — the existing note was reassigned, no new card

SCENARIO S15 — split one section into two   [plain heading card]
EDIT
  the original heading keeps paragraph 1; a new marked sibling gets paragraph 2
DIFF
  update      'temporal coupling' in n2
  create      'temporal decoupling' in n2
EVIDENCE
  (empty — no stranded note to explain)
POLICY
  (no findings reached the seam)
LEDGER
  history intact in place: 'temporal coupling' (updated)
  new card at ZERO: 'temporal decoupling'

SCENARIO S15b — split, both halves reworded   [plain heading card]
EDIT
  the section is split and BOTH headings are new wordings
DIFF
  create      'coupling in time' in n2
  create      'decoupling in time' in n2
  flag        'temporal coupling' in n2 — suspended, never deleted
EVIDENCE
  note 1, which held 'temporal coupling' in n2, matches nothing the vault now produces
POLICY
  park-and-report: note 1, which held 'temporal coupling' in n2, matches nothing the vault now produces
LEDGER
  new card at ZERO: 'coupling in time'
  new card at ZERO: 'decoupling in time'
  history STRANDED on the suspended note: 'temporal coupling'

SCENARIO S16 — merge two sections into one   [plain heading card]
EDIT
  two marked siblings become one new marked heading whose body is the concatenation
DIFF
  create      'merged' in n2
  flag        'alpha' in n2 — suspended, never deleted
  flag        'beta' in n2 — suspended, never deleted
EVIDENCE
  note 1, which held 'alpha' in n2, matches nothing the vault now produces
  note 2, which held 'beta' in n2, matches nothing the vault now produces
POLICY
  park-and-report: note 1, which held 'alpha' in n2, matches nothing the vault now produces
  park-and-report: note 2, which held 'beta' in n2, matches nothing the vault now produces
LEDGER
  new card at ZERO: 'merged'
  history STRANDED on the suspended note: 'alpha'
  history STRANDED on the suspended note: 'beta'

SCENARIO S16b — merge two byte-identical sections   [plain heading card]
EDIT
  the two siblings' bodies are byte-identical and the merged body equals both
DIFF
  create      'merged' in n2
  flag        'alpha' in n2 — suspended, never deleted
  flag        'beta' in n2 — suspended, never deleted
EVIDENCE
  note 1, which held 'alpha' in n2, agrees with 'merged' in n2 — but so do 'beta', so nothing is established
  note 2, which held 'beta' in n2, agrees with 'merged' in n2 — but so do 'alpha', so nothing is established
POLICY
  park-and-report: note 1, which held 'alpha' in n2, agrees with 'merged' in n2 — but so do 'beta', so nothing is established
  park-and-report: note 2, which held 'beta' in n2, agrees with 'merged' in n2 — but so do 'alpha', so nothing is established
LEDGER
  new card at ZERO: 'merged'
  history STRANDED on the suspended note: 'alpha'
  history STRANDED on the suspended note: 'beta'

SCENARIO S17 — copy-paste creates a duplicate key   [plain heading card]
EDIT
  the marked section is duplicated under the same ancestor: two sources, one key
DIFF
  PLAN REFUSED — nothing in this run was planned:
    duplicate card key 'cap theorem / definition' (note 'n2') derived from two sources: Coupling.md:7 (heading) and Coupling.md:11 (heading)
EVIDENCE
  (not surveyed: the plan was refused before the survey could run)
POLICY
  (no findings reached the seam)
LEDGER
  EVERY card refused: the whole plan was rejected over a duplicate key

SCENARIO S18 — retag 2way to 1way in place   [plain heading card]
EDIT
  only the marker changes
DIFF
  retype      'temporal coupling' in n2 — Obsidian Basic (and reversed card) → Obsidian Basic (deferred unless asked)
EVIDENCE
  (empty — no stranded note to explain)
POLICY
  (no findings reached the seam)
LEDGER
  history intact in place: 'temporal coupling' (retype, deferred unless asked)

SCENARIO S19 — retag AND re-parent in one commit   [plain heading card]
EDIT
  the marker changes 2way to 1way and the heading moves under '## Notes'
DIFF
  create      'notes / temporal coupling' in n2
  flag        'temporal coupling' in n2 — suspended, never deleted
EVIDENCE
  note 1, which held 'temporal coupling' in n2, matches nothing the vault now produces
POLICY
  park-and-report: note 1, which held 'temporal coupling' in n2, matches nothing the vault now produces
LEDGER
  new card at ZERO: 'notes / temporal coupling'
  history STRANDED on the suspended note: 'temporal coupling'

SCENARIO S20 — reword a descriptor slot   [concept-descriptor heading card]
EDIT
  '## Definition' becomes '## Formal definition' under '# Kafka'; the body is untouched
DIFF
  non-event   'kafka / cost' in n1
  create      'kafka / formal definition' in n1
  non-event   'nats / guarantees' in n1
  flag        'kafka / definition' in n1 — suspended, never deleted
EVIDENCE
  note 2, which held 'kafka / definition' in n1, is 'kafka / formal definition' in n1 — a different name, in the same place, Messaging.md:7 (heading); Descriptor: 'Definition' → 'Formal definition'
POLICY
  apply-reassign: reassigned, keeping its review history — note 2, which held 'kafka / definition' in n1, is 'kafka / formal definition' in n1 — a different name, in the same place, Messaging.md:7 (heading); Descriptor: 'Definition' → 'Formal definition'
LEDGER
  history FOLLOWED onto 'kafka / formal definition' — the existing note was reassigned, no new card

SCENARIO S21 — replace a descriptor slot   [concept-descriptor heading card]
EDIT
  '## Definition' becomes '## Contrast' under '# Kafka'; the body is byte-identical — the same observable as S20
DIFF
  create      'kafka / contrast' in n1
  non-event   'kafka / cost' in n1
  non-event   'nats / guarantees' in n1
  flag        'kafka / definition' in n1 — suspended, never deleted
EVIDENCE
  note 2, which held 'kafka / definition' in n1, is 'kafka / contrast' in n1 — a different name, in the same place, Messaging.md:7 (heading); Descriptor: 'Definition' → 'Contrast'
POLICY
  apply-reassign: reassigned, keeping its review history — note 2, which held 'kafka / definition' in n1, is 'kafka / contrast' in n1 — a different name, in the same place, Messaging.md:7 (heading); Descriptor: 'Definition' → 'Contrast'
LEDGER
  history FOLLOWED onto 'kafka / contrast' — the existing note was reassigned, no new card

SCENARIO S22 — reword the concept in place   [concept-descriptor heading card]
EDIT
  '# Kafka' becomes '# Apache Kafka'; both descriptor sections are untouched
DIFF
  create      'apache kafka / cost' in n1
  create      'apache kafka / definition' in n1
  non-event   'nats / guarantees' in n1
  flag        'kafka / cost' in n1 — suspended, never deleted
  flag        'kafka / definition' in n1 — suspended, never deleted
EVIDENCE
  note 1, which held 'kafka / cost' in n1, is 'apache kafka / cost' in n1 — a different name, in the same place, Messaging.md:11 (heading); Concept: 'Kafka' → 'Apache Kafka'
  note 2, which held 'kafka / definition' in n1, is 'apache kafka / definition' in n1 — a different name, in the same place, Messaging.md:7 (heading); Concept: 'Kafka' → 'Apache Kafka'
POLICY
  apply-reassign: reassigned, keeping its review history — note 1, which held 'kafka / cost' in n1, is 'apache kafka / cost' in n1 — a different name, in the same place, Messaging.md:11 (heading); Concept: 'Kafka' → 'Apache Kafka'
  apply-reassign: reassigned, keeping its review history — note 2, which held 'kafka / definition' in n1, is 'apache kafka / definition' in n1 — a different name, in the same place, Messaging.md:7 (heading); Concept: 'Kafka' → 'Apache Kafka'
LEDGER
  history FOLLOWED onto 'apache kafka / cost' — the existing note was reassigned, no new card
  history FOLLOWED onto 'apache kafka / definition' — the existing note was reassigned, no new card

SCENARIO S23 — swap the concept's subject in place   [concept-descriptor heading card]
EDIT
  '# Kafka' becomes '# RabbitMQ'; bodies untouched — the same observable as S22
DIFF
  non-event   'nats / guarantees' in n1
  create      'rabbitmq / cost' in n1
  create      'rabbitmq / definition' in n1
  flag        'kafka / cost' in n1 — suspended, never deleted
  flag        'kafka / definition' in n1 — suspended, never deleted
EVIDENCE
  note 1, which held 'kafka / cost' in n1, is 'rabbitmq / cost' in n1 — a different name, in the same place, Messaging.md:11 (heading); Concept: 'Kafka' → 'RabbitMQ'
  note 2, which held 'kafka / definition' in n1, is 'rabbitmq / definition' in n1 — a different name, in the same place, Messaging.md:7 (heading); Concept: 'Kafka' → 'RabbitMQ'
POLICY
  apply-reassign: reassigned, keeping its review history — note 1, which held 'kafka / cost' in n1, is 'rabbitmq / cost' in n1 — a different name, in the same place, Messaging.md:11 (heading); Concept: 'Kafka' → 'RabbitMQ'
  apply-reassign: reassigned, keeping its review history — note 2, which held 'kafka / definition' in n1, is 'rabbitmq / definition' in n1 — a different name, in the same place, Messaging.md:7 (heading); Concept: 'Kafka' → 'RabbitMQ'
LEDGER
  history FOLLOWED onto 'rabbitmq / cost' — the existing note was reassigned, no new card
  history FOLLOWED onto 'rabbitmq / definition' — the existing note was reassigned, no new card

SCENARIO S24 — re-parent a descriptor under a different concept   [concept-descriptor heading card]
EDIT
  '## Definition' (body verbatim) moves from under '# Kafka' to under '# NATS'
DIFF
  non-event   'kafka / cost' in n1
  create      'nats / definition' in n1
  non-event   'nats / guarantees' in n1
  flag        'kafka / definition' in n1 — suspended, never deleted
EVIDENCE
  note 2, which held 'kafka / definition' in n1, says the same thing as 'nats / definition' in n1 (Messaging.md:13 (heading)) — but 'kafka' is still in the vault, so this is a DIFFERENT card under a different subject and nothing is applied; Concept: 'Kafka' → 'NATS'
POLICY
  park-and-report: note 2, which held 'kafka / definition' in n1, says the same thing as 'nats / definition' in n1 (Messaging.md:13 (heading)) — but 'kafka' is still in the vault, so this is a DIFFERENT card under a different subject and nothing is applied; Concept: 'Kafka' → 'NATS'
LEDGER
  new card at ZERO: 'nats / definition'
  history STRANDED on the suspended note: 'kafka / definition'

SCENARIO S25 — move a concept and its whole subtree to another note   [concept-descriptor heading card]
EDIT
  '# Kafka' and both descriptor sections move verbatim into Queues.md
DIFF
  non-event   'nats / guarantees' in n1
  create      'kafka / cost' in n1b
  create      'kafka / definition' in n1b
  flag        'kafka / cost' in n1 — suspended, never deleted
  flag        'kafka / definition' in n1 — suspended, never deleted
EVIDENCE
  note 1, which held 'kafka / cost' in n1, is 'kafka / cost' in n1b — the same path, in a different note, Queues.md:13 (heading); Context: 'Messaging' → 'Queues'
  note 2, which held 'kafka / definition' in n1, is 'kafka / definition' in n1b — the same path, in a different note, Queues.md:9 (heading); Context: 'Messaging' → 'Queues'
POLICY
  apply-reassign: reassigned, keeping its review history — note 1, which held 'kafka / cost' in n1, is 'kafka / cost' in n1b — the same path, in a different note, Queues.md:13 (heading); Context: 'Messaging' → 'Queues'
  apply-reassign: reassigned, keeping its review history — note 2, which held 'kafka / definition' in n1, is 'kafka / definition' in n1b — the same path, in a different note, Queues.md:9 (heading); Context: 'Messaging' → 'Queues'
LEDGER
  history FOLLOWED onto 'kafka / cost' — the existing note was reassigned, no new card
  history FOLLOWED onto 'kafka / definition' — the existing note was reassigned, no new card

SCENARIO S26 — rename the file of an ancestorless descriptor   [concept-descriptor heading card]
EDIT
  Kafka.md becomes NATS.md; the id and the body are untouched
DIFF
  update      'definition' in k1
EVIDENCE
  (empty — no stranded note to explain)
POLICY
  (no findings reached the seam)
LEDGER
  history intact in place: 'definition' (updated)

SCENARIO S27 — rename the file AND change the id   [concept-descriptor heading card]
EDIT
  Kafka.md becomes NATS.md and 'id: k1' becomes 'id: k2' in one commit
DIFF
  create      'definition' in k2
  flag        'definition' in k1 — suspended, never deleted
EVIDENCE
  note 1, which held 'definition' in k1, says the same thing as 'definition' in k2 (NATS.md:5 (heading)) — but its name changed for a reason the key does not explain, so nothing is applied: Concept: 'Kafka' → 'NATS'
POLICY
  park-and-report: note 1, which held 'definition' in k1, says the same thing as 'definition' in k2 (NATS.md:5 (heading)) — but its name changed for a reason the key does not explain, so nothing is applied: Concept: 'Kafka' → 'NATS'
LEDGER
  new card at ZERO: 'definition'
  history STRANDED on the suspended note: 'definition'

SCENARIO S28 — retag cdd/2way to cdd/3way in place   [concept-descriptor heading card]
EDIT
  only the marker changes
DIFF
  non-event   'kafka / cost' in n1
  update      'kafka / definition' in n1
  non-event   'nats / guarantees' in n1
EVIDENCE
  (empty — no stranded note to explain)
POLICY
  (no findings reached the seam)
LEDGER
  history intact in place: 'kafka / definition' (updated)

SCENARIO S28b — retag cdd/3way AND re-parent   [concept-descriptor heading card]
EDIT
  the marker changes and the descriptor moves under '# NATS' in one commit
DIFF
  non-event   'kafka / cost' in n1
  create      'nats / definition' in n1
  non-event   'nats / guarantees' in n1
  flag        'kafka / definition' in n1 — suspended, never deleted
EVIDENCE
  note 2, which held 'kafka / definition' in n1, matches nothing the vault now produces
POLICY
  park-and-report: note 2, which held 'kafka / definition' in n1, matches nothing the vault now produces
LEDGER
  new card at ZERO: 'nats / definition'
  history STRANDED on the suspended note: 'kafka / definition'

SCENARIO S29 — swap the two table rows   [table pair card]
EDIT
  the Queue and Cache rows change places; nothing else changes
DIFF
  non-event   'cost / benefit / cache' in n3
  non-event   'cost / benefit / cache / benefit' in n3
  non-event   'cost / benefit / cache / cost' in n3
  non-event   'cost / benefit / queue' in n3
  non-event   'cost / benefit / queue / benefit' in n3
  non-event   'cost / benefit / queue / cost' in n3
EVIDENCE
  (empty — no stranded note to explain)
POLICY
  (no findings reached the seam)
LEDGER
  (no card's standing changed)

SCENARIO S30 — edit one cell value   [table pair card]
EDIT
  'Delay' becomes 'Delay and duplication'
DIFF
  non-event   'cost / benefit / cache' in n3
  non-event   'cost / benefit / cache / benefit' in n3
  non-event   'cost / benefit / cache / cost' in n3
  update      'cost / benefit / queue' in n3
  non-event   'cost / benefit / queue / benefit' in n3
  update      'cost / benefit / queue / cost' in n3
EVIDENCE
  (empty — no stranded note to explain)
POLICY
  (no findings reached the seam)
LEDGER
  history intact in place: 'cost / benefit / queue' (updated)
  history intact in place: 'cost / benefit / queue / cost' (updated)

SCENARIO S31 — rename a column header, values distinct   [table pair card]
EDIT
  'Benefit' becomes 'Advantage'; the two rows hold distinct values in that column
DIFF
  update      'cost / benefit / cache' in n3
  create      'cost / benefit / cache / advantage' in n3
  non-event   'cost / benefit / cache / cost' in n3
  update      'cost / benefit / queue' in n3
  create      'cost / benefit / queue / advantage' in n3
  non-event   'cost / benefit / queue / cost' in n3
  flag        'cost / benefit / cache / benefit' in n3 — suspended, never deleted
  flag        'cost / benefit / queue / benefit' in n3 — suspended, never deleted
EVIDENCE
  note 2, which held 'cost / benefit / cache / benefit' in n3, is 'cost / benefit / cache / advantage' in n3 — a different name, in the same place, Patterns.md:5 (table pair card, row 2); Descriptor: 'Benefit' → 'Advantage'
  note 5, which held 'cost / benefit / queue / benefit' in n3, is 'cost / benefit / queue / advantage' in n3 — a different name, in the same place, Patterns.md:5 (table pair card, row 1); Descriptor: 'Benefit' → 'Advantage'
POLICY
  apply-reassign: reassigned, keeping its review history — note 2, which held 'cost / benefit / cache / benefit' in n3, is 'cost / benefit / cache / advantage' in n3 — a different name, in the same place, Patterns.md:5 (table pair card, row 2); Descriptor: 'Benefit' → 'Advantage'
  apply-reassign: reassigned, keeping its review history — note 5, which held 'cost / benefit / queue / benefit' in n3, is 'cost / benefit / queue / advantage' in n3 — a different name, in the same place, Patterns.md:5 (table pair card, row 1); Descriptor: 'Benefit' → 'Advantage'
LEDGER
  history intact in place: 'cost / benefit / cache' (updated)
  history FOLLOWED onto 'cost / benefit / cache / advantage' — the existing note was reassigned, no new card
  history intact in place: 'cost / benefit / queue' (updated)
  history FOLLOWED onto 'cost / benefit / queue / advantage' — the existing note was reassigned, no new card

SCENARIO S32 — rename a column header, values identical   [table pair card]
EDIT
  'Benefit' becomes 'Advantage'; both rows hold the byte-identical value in that column
DIFF
  update      'cost / benefit / cache' in n3
  create      'cost / benefit / cache / advantage' in n3
  non-event   'cost / benefit / cache / cost' in n3
  update      'cost / benefit / queue' in n3
  create      'cost / benefit / queue / advantage' in n3
  non-event   'cost / benefit / queue / cost' in n3
  flag        'cost / benefit / cache / benefit' in n3 — suspended, never deleted
  flag        'cost / benefit / queue / benefit' in n3 — suspended, never deleted
EVIDENCE
  note 2, which held 'cost / benefit / cache / benefit' in n3, agrees equally with 2 cards, so nothing is established: 'cost / benefit / cache / advantage' in n3; 'cost / benefit / queue / advantage' in n3
  note 5, which held 'cost / benefit / queue / benefit' in n3, agrees equally with 2 cards, so nothing is established: 'cost / benefit / cache / advantage' in n3; 'cost / benefit / queue / advantage' in n3
POLICY
  park-and-report: note 2, which held 'cost / benefit / cache / benefit' in n3, agrees equally with 2 cards, so nothing is established: 'cost / benefit / cache / advantage' in n3; 'cost / benefit / queue / advantage' in n3
  park-and-report: note 5, which held 'cost / benefit / queue / benefit' in n3, agrees equally with 2 cards, so nothing is established: 'cost / benefit / cache / advantage' in n3; 'cost / benefit / queue / advantage' in n3
LEDGER
  history intact in place: 'cost / benefit / cache' (updated)
  new card at ZERO: 'cost / benefit / cache / advantage'
  history intact in place: 'cost / benefit / queue' (updated)
  new card at ZERO: 'cost / benefit / queue / advantage'
  history STRANDED on the suspended note: 'cost / benefit / cache / benefit'
  history STRANDED on the suspended note: 'cost / benefit / queue / benefit'

SCENARIO S33 — replace a row's subject, values byte-identical   [table pair card]
EDIT
  the Queue row is deleted and a Stream row with the same values added
DIFF
  non-event   'cost / benefit / cache' in n3
  non-event   'cost / benefit / cache / benefit' in n3
  non-event   'cost / benefit / cache / cost' in n3
  create      'cost / benefit / stream' in n3
  create      'cost / benefit / stream / benefit' in n3
  create      'cost / benefit / stream / cost' in n3
  flag        'cost / benefit / queue' in n3 — suspended, never deleted
  flag        'cost / benefit / queue / benefit' in n3 — suspended, never deleted
  flag        'cost / benefit / queue / cost' in n3 — suspended, never deleted
EVIDENCE
  note 4, which held 'cost / benefit / queue' in n3, matches nothing the vault now produces
  note 5, which held 'cost / benefit / queue / benefit' in n3, is 'cost / benefit / stream / benefit' in n3 — a different name, in the same place, Patterns.md:5 (table pair card, row 1); Concept: 'Queue' → 'Stream'
  note 6, which held 'cost / benefit / queue / cost' in n3, is 'cost / benefit / stream / cost' in n3 — a different name, in the same place, Patterns.md:5 (table pair card, row 1); Concept: 'Queue' → 'Stream'
POLICY
  park-and-report: note 4, which held 'cost / benefit / queue' in n3, matches nothing the vault now produces
  apply-reassign: reassigned, keeping its review history — note 5, which held 'cost / benefit / queue / benefit' in n3, is 'cost / benefit / stream / benefit' in n3 — a different name, in the same place, Patterns.md:5 (table pair card, row 1); Concept: 'Queue' → 'Stream'
  apply-reassign: reassigned, keeping its review history — note 6, which held 'cost / benefit / queue / cost' in n3, is 'cost / benefit / stream / cost' in n3 — a different name, in the same place, Patterns.md:5 (table pair card, row 1); Concept: 'Queue' → 'Stream'
LEDGER
  new card at ZERO: 'cost / benefit / stream'
  history FOLLOWED onto 'cost / benefit / stream / benefit' — the existing note was reassigned, no new card
  history FOLLOWED onto 'cost / benefit / stream / cost' — the existing note was reassigned, no new card
  history STRANDED on the suspended note: 'cost / benefit / queue'

SCENARIO S34 — fix a typo in a row concept   [table pair card]
EDIT
  'Qeue' becomes 'Queue'; values untouched — the same observable as S33
DIFF
  non-event   'cost / benefit / cache' in n3
  non-event   'cost / benefit / cache / benefit' in n3
  non-event   'cost / benefit / cache / cost' in n3
  create      'cost / benefit / queue' in n3
  create      'cost / benefit / queue / benefit' in n3
  create      'cost / benefit / queue / cost' in n3
  flag        'cost / benefit / qeue' in n3 — suspended, never deleted
  flag        'cost / benefit / qeue / benefit' in n3 — suspended, never deleted
  flag        'cost / benefit / qeue / cost' in n3 — suspended, never deleted
EVIDENCE
  note 4, which held 'cost / benefit / qeue' in n3, matches nothing the vault now produces
  note 5, which held 'cost / benefit / qeue / benefit' in n3, is 'cost / benefit / queue / benefit' in n3 — a different name, in the same place, Patterns.md:5 (table pair card, row 1); Concept: 'Qeue' → 'Queue'
  note 6, which held 'cost / benefit / qeue / cost' in n3, is 'cost / benefit / queue / cost' in n3 — a different name, in the same place, Patterns.md:5 (table pair card, row 1); Concept: 'Qeue' → 'Queue'
POLICY
  park-and-report: note 4, which held 'cost / benefit / qeue' in n3, matches nothing the vault now produces
  apply-reassign: reassigned, keeping its review history — note 5, which held 'cost / benefit / qeue / benefit' in n3, is 'cost / benefit / queue / benefit' in n3 — a different name, in the same place, Patterns.md:5 (table pair card, row 1); Concept: 'Qeue' → 'Queue'
  apply-reassign: reassigned, keeping its review history — note 6, which held 'cost / benefit / qeue / cost' in n3, is 'cost / benefit / queue / cost' in n3 — a different name, in the same place, Patterns.md:5 (table pair card, row 1); Concept: 'Qeue' → 'Queue'
LEDGER
  new card at ZERO: 'cost / benefit / queue'
  history FOLLOWED onto 'cost / benefit / queue / benefit' — the existing note was reassigned, no new card
  history FOLLOWED onto 'cost / benefit / queue / cost' — the existing note was reassigned, no new card
  history STRANDED on the suspended note: 'cost / benefit / qeue'

SCENARIO S35 — add a column   [table pair card]
EDIT
  a Latency column with values is added
DIFF
  update      'cost / benefit / cache' in n3
  non-event   'cost / benefit / cache / benefit' in n3
  non-event   'cost / benefit / cache / cost' in n3
  create      'cost / benefit / cache / latency' in n3
  update      'cost / benefit / queue' in n3
  non-event   'cost / benefit / queue / benefit' in n3
  non-event   'cost / benefit / queue / cost' in n3
  create      'cost / benefit / queue / latency' in n3
EVIDENCE
  (empty — no stranded note to explain)
POLICY
  (no findings reached the seam)
LEDGER
  history intact in place: 'cost / benefit / cache' (updated)
  new card at ZERO: 'cost / benefit / cache / latency'
  history intact in place: 'cost / benefit / queue' (updated)
  new card at ZERO: 'cost / benefit / queue / latency'

SCENARIO S36 — delete a column   [table pair card]
EDIT
  the Cost column is removed
DIFF
  non-event   'cost / benefit / cache / benefit' in n3
  non-event   'cost / benefit / queue / benefit' in n3
  flag        'cost / benefit / cache' in n3 — suspended, never deleted
  flag        'cost / benefit / cache / cost' in n3 — suspended, never deleted
  flag        'cost / benefit / queue' in n3 — suspended, never deleted
  flag        'cost / benefit / queue / cost' in n3 — suspended, never deleted
EVIDENCE
  note 1, which held 'cost / benefit / cache' in n3, matches nothing the vault now produces
  note 3, which held 'cost / benefit / cache / cost' in n3, matches nothing the vault now produces
  note 4, which held 'cost / benefit / queue' in n3, matches nothing the vault now produces
  note 6, which held 'cost / benefit / queue / cost' in n3, matches nothing the vault now produces
POLICY
  park-and-report: note 1, which held 'cost / benefit / cache' in n3, matches nothing the vault now produces
  park-and-report: note 3, which held 'cost / benefit / cache / cost' in n3, matches nothing the vault now produces
  park-and-report: note 4, which held 'cost / benefit / queue' in n3, matches nothing the vault now produces
  park-and-report: note 6, which held 'cost / benefit / queue / cost' in n3, matches nothing the vault now produces
LEDGER
  history STRANDED on the suspended note: 'cost / benefit / cache'
  history STRANDED on the suspended note: 'cost / benefit / cache / cost'
  history STRANDED on the suspended note: 'cost / benefit / queue'
  history STRANDED on the suspended note: 'cost / benefit / queue / cost'

SCENARIO S36b — blank one value cell   [table pair card]
EDIT
  the Queue row's Cost cell is emptied
DIFF
  non-event   'cost / benefit / cache' in n3
  non-event   'cost / benefit / cache / benefit' in n3
  non-event   'cost / benefit / cache / cost' in n3
  non-event   'cost / benefit / queue / benefit' in n3
  flag        'cost / benefit / queue' in n3 — suspended, never deleted
  flag        'cost / benefit / queue / cost' in n3 — suspended, never deleted
EVIDENCE
  note 4, which held 'cost / benefit / queue' in n3, matches nothing the vault now produces
  note 6, which held 'cost / benefit / queue / cost' in n3, matches nothing the vault now produces
POLICY
  park-and-report: note 4, which held 'cost / benefit / queue' in n3, matches nothing the vault now produces
  park-and-report: note 6, which held 'cost / benefit / queue / cost' in n3, matches nothing the vault now produces
LEDGER
  history STRANDED on the suspended note: 'cost / benefit / queue'
  history STRANDED on the suspended note: 'cost / benefit / queue / cost'

SCENARIO S37 — reword the marked table heading   [table pair card]
EDIT
  '## Cost / benefit' becomes '## Trade-offs'; the table is untouched
DIFF
  create      'trade-offs / cache' in n3
  create      'trade-offs / cache / benefit' in n3
  create      'trade-offs / cache / cost' in n3
  create      'trade-offs / queue' in n3
  create      'trade-offs / queue / benefit' in n3
  create      'trade-offs / queue / cost' in n3
  flag        'cost / benefit / cache' in n3 — suspended, never deleted
  flag        'cost / benefit / cache / benefit' in n3 — suspended, never deleted
  flag        'cost / benefit / cache / cost' in n3 — suspended, never deleted
  flag        'cost / benefit / queue' in n3 — suspended, never deleted
  flag        'cost / benefit / queue / benefit' in n3 — suspended, never deleted
  flag        'cost / benefit / queue / cost' in n3 — suspended, never deleted
EVIDENCE
  note 1, which held 'cost / benefit / cache' in n3, is 'trade-offs / cache' in n3 — the same name, somewhere else, Patterns.md:5 (table row card, row 2); Context: 'Patterns › Cost / benefit' → 'Patterns › Trade-offs'
  note 2, which held 'cost / benefit / cache / benefit' in n3, is 'trade-offs / cache / benefit' in n3 — the same name, somewhere else, Patterns.md:5 (table pair card, row 2); Context: 'Patterns › Cost / benefit' → 'Patterns › Trade-offs'
  note 3, which held 'cost / benefit / cache / cost' in n3, is 'trade-offs / cache / cost' in n3 — the same name, somewhere else, Patterns.md:5 (table pair card, row 2); Context: 'Patterns › Cost / benefit' → 'Patterns › Trade-offs'
  note 4, which held 'cost / benefit / queue' in n3, is 'trade-offs / queue' in n3 — the same name, somewhere else, Patterns.md:5 (table row card, row 1); Context: 'Patterns › Cost / benefit' → 'Patterns › Trade-offs'
  note 5, which held 'cost / benefit / queue / benefit' in n3, is 'trade-offs / queue / benefit' in n3 — the same name, somewhere else, Patterns.md:5 (table pair card, row 1); Context: 'Patterns › Cost / benefit' → 'Patterns › Trade-offs'
  note 6, which held 'cost / benefit / queue / cost' in n3, is 'trade-offs / queue / cost' in n3 — the same name, somewhere else, Patterns.md:5 (table pair card, row 1); Context: 'Patterns › Cost / benefit' → 'Patterns › Trade-offs'
POLICY
  apply-reassign: reassigned, keeping its review history — note 1, which held 'cost / benefit / cache' in n3, is 'trade-offs / cache' in n3 — the same name, somewhere else, Patterns.md:5 (table row card, row 2); Context: 'Patterns › Cost / benefit' → 'Patterns › Trade-offs'
  apply-reassign: reassigned, keeping its review history — note 2, which held 'cost / benefit / cache / benefit' in n3, is 'trade-offs / cache / benefit' in n3 — the same name, somewhere else, Patterns.md:5 (table pair card, row 2); Context: 'Patterns › Cost / benefit' → 'Patterns › Trade-offs'
  apply-reassign: reassigned, keeping its review history — note 3, which held 'cost / benefit / cache / cost' in n3, is 'trade-offs / cache / cost' in n3 — the same name, somewhere else, Patterns.md:5 (table pair card, row 2); Context: 'Patterns › Cost / benefit' → 'Patterns › Trade-offs'
  apply-reassign: reassigned, keeping its review history — note 4, which held 'cost / benefit / queue' in n3, is 'trade-offs / queue' in n3 — the same name, somewhere else, Patterns.md:5 (table row card, row 1); Context: 'Patterns › Cost / benefit' → 'Patterns › Trade-offs'
  apply-reassign: reassigned, keeping its review history — note 5, which held 'cost / benefit / queue / benefit' in n3, is 'trade-offs / queue / benefit' in n3 — the same name, somewhere else, Patterns.md:5 (table pair card, row 1); Context: 'Patterns › Cost / benefit' → 'Patterns › Trade-offs'
  apply-reassign: reassigned, keeping its review history — note 6, which held 'cost / benefit / queue / cost' in n3, is 'trade-offs / queue / cost' in n3 — the same name, somewhere else, Patterns.md:5 (table pair card, row 1); Context: 'Patterns › Cost / benefit' → 'Patterns › Trade-offs'
LEDGER
  history FOLLOWED onto 'trade-offs / cache' — the existing note was reassigned, no new card
  history FOLLOWED onto 'trade-offs / cache / benefit' — the existing note was reassigned, no new card
  history FOLLOWED onto 'trade-offs / cache / cost' — the existing note was reassigned, no new card
  history FOLLOWED onto 'trade-offs / queue' — the existing note was reassigned, no new card
  history FOLLOWED onto 'trade-offs / queue / benefit' — the existing note was reassigned, no new card
  history FOLLOWED onto 'trade-offs / queue / cost' — the existing note was reassigned, no new card

SCENARIO S37b — move the whole table section to another note   [table pair card]
EDIT
  the section moves verbatim into Notes2.md
DIFF
  create      'cost / benefit / cache' in n3b
  create      'cost / benefit / cache / benefit' in n3b
  create      'cost / benefit / cache / cost' in n3b
  create      'cost / benefit / queue' in n3b
  create      'cost / benefit / queue / benefit' in n3b
  create      'cost / benefit / queue / cost' in n3b
  flag        'cost / benefit / cache' in n3 — suspended, never deleted
  flag        'cost / benefit / cache / benefit' in n3 — suspended, never deleted
  flag        'cost / benefit / cache / cost' in n3 — suspended, never deleted
  flag        'cost / benefit / queue' in n3 — suspended, never deleted
  flag        'cost / benefit / queue / benefit' in n3 — suspended, never deleted
  flag        'cost / benefit / queue / cost' in n3 — suspended, never deleted
EVIDENCE
  note 1, which held 'cost / benefit / cache' in n3, is 'cost / benefit / cache' in n3b — the same path, in a different note, Notes2.md:7 (table row card, row 2); Context: 'Patterns › Cost / benefit' → 'Notes2 › Cost / benefit'
  note 2, which held 'cost / benefit / cache / benefit' in n3, is 'cost / benefit / cache / benefit' in n3b — the same path, in a different note, Notes2.md:7 (table pair card, row 2); Context: 'Patterns › Cost / benefit' → 'Notes2 › Cost / benefit'
  note 3, which held 'cost / benefit / cache / cost' in n3, is 'cost / benefit / cache / cost' in n3b — the same path, in a different note, Notes2.md:7 (table pair card, row 2); Context: 'Patterns › Cost / benefit' → 'Notes2 › Cost / benefit'
  note 4, which held 'cost / benefit / queue' in n3, is 'cost / benefit / queue' in n3b — the same path, in a different note, Notes2.md:7 (table row card, row 1); Context: 'Patterns › Cost / benefit' → 'Notes2 › Cost / benefit'
  note 5, which held 'cost / benefit / queue / benefit' in n3, is 'cost / benefit / queue / benefit' in n3b — the same path, in a different note, Notes2.md:7 (table pair card, row 1); Context: 'Patterns › Cost / benefit' → 'Notes2 › Cost / benefit'
  note 6, which held 'cost / benefit / queue / cost' in n3, is 'cost / benefit / queue / cost' in n3b — the same path, in a different note, Notes2.md:7 (table pair card, row 1); Context: 'Patterns › Cost / benefit' → 'Notes2 › Cost / benefit'
POLICY
  apply-reassign: reassigned, keeping its review history — note 1, which held 'cost / benefit / cache' in n3, is 'cost / benefit / cache' in n3b — the same path, in a different note, Notes2.md:7 (table row card, row 2); Context: 'Patterns › Cost / benefit' → 'Notes2 › Cost / benefit'
  apply-reassign: reassigned, keeping its review history — note 2, which held 'cost / benefit / cache / benefit' in n3, is 'cost / benefit / cache / benefit' in n3b — the same path, in a different note, Notes2.md:7 (table pair card, row 2); Context: 'Patterns › Cost / benefit' → 'Notes2 › Cost / benefit'
  apply-reassign: reassigned, keeping its review history — note 3, which held 'cost / benefit / cache / cost' in n3, is 'cost / benefit / cache / cost' in n3b — the same path, in a different note, Notes2.md:7 (table pair card, row 2); Context: 'Patterns › Cost / benefit' → 'Notes2 › Cost / benefit'
  apply-reassign: reassigned, keeping its review history — note 4, which held 'cost / benefit / queue' in n3, is 'cost / benefit / queue' in n3b — the same path, in a different note, Notes2.md:7 (table row card, row 1); Context: 'Patterns › Cost / benefit' → 'Notes2 › Cost / benefit'
  apply-reassign: reassigned, keeping its review history — note 5, which held 'cost / benefit / queue / benefit' in n3, is 'cost / benefit / queue / benefit' in n3b — the same path, in a different note, Notes2.md:7 (table pair card, row 1); Context: 'Patterns › Cost / benefit' → 'Notes2 › Cost / benefit'
  apply-reassign: reassigned, keeping its review history — note 6, which held 'cost / benefit / queue / cost' in n3, is 'cost / benefit / queue / cost' in n3b — the same path, in a different note, Notes2.md:7 (table pair card, row 1); Context: 'Patterns › Cost / benefit' → 'Notes2 › Cost / benefit'
LEDGER
  history FOLLOWED onto 'cost / benefit / cache' — the existing note was reassigned, no new card
  history FOLLOWED onto 'cost / benefit / cache / benefit' — the existing note was reassigned, no new card
  history FOLLOWED onto 'cost / benefit / cache / cost' — the existing note was reassigned, no new card
  history FOLLOWED onto 'cost / benefit / queue' — the existing note was reassigned, no new card
  history FOLLOWED onto 'cost / benefit / queue / benefit' — the existing note was reassigned, no new card
  history FOLLOWED onto 'cost / benefit / queue / cost' — the existing note was reassigned, no new card

SCENARIO S38 — rename the first header cell   [table pair card]
EDIT
  'Pattern' becomes 'Approach'
DIFF
  update      'cost / benefit / cache' in n3
  update      'cost / benefit / cache / benefit' in n3
  update      'cost / benefit / cache / cost' in n3
  update      'cost / benefit / queue' in n3
  update      'cost / benefit / queue / benefit' in n3
  update      'cost / benefit / queue / cost' in n3
EVIDENCE
  (empty — no stranded note to explain)
POLICY
  (no findings reached the seam)
LEDGER
  history intact in place: 'cost / benefit / cache' (updated)
  history intact in place: 'cost / benefit / cache / benefit' (updated)
  history intact in place: 'cost / benefit / cache / cost' (updated)
  history intact in place: 'cost / benefit / queue' (updated)
  history intact in place: 'cost / benefit / queue / benefit' (updated)
  history intact in place: 'cost / benefit / queue / cost' (updated)

SCENARIO S38b — rename the first header AND move the section   [table pair card]
EDIT
  'Pattern' becomes 'Approach' and the section moves into Notes2.md, one commit
DIFF
  create      'cost / benefit / cache' in n3b
  create      'cost / benefit / cache / benefit' in n3b
  create      'cost / benefit / cache / cost' in n3b
  create      'cost / benefit / queue' in n3b
  create      'cost / benefit / queue / benefit' in n3b
  create      'cost / benefit / queue / cost' in n3b
  flag        'cost / benefit / cache' in n3 — suspended, never deleted
  flag        'cost / benefit / cache / benefit' in n3 — suspended, never deleted
  flag        'cost / benefit / cache / cost' in n3 — suspended, never deleted
  flag        'cost / benefit / queue' in n3 — suspended, never deleted
  flag        'cost / benefit / queue / benefit' in n3 — suspended, never deleted
  flag        'cost / benefit / queue / cost' in n3 — suspended, never deleted
EVIDENCE
  note 1, which held 'cost / benefit / cache' in n3, matches nothing the vault now produces
  note 2, which held 'cost / benefit / cache / benefit' in n3, matches nothing the vault now produces
  note 3, which held 'cost / benefit / cache / cost' in n3, matches nothing the vault now produces
  note 4, which held 'cost / benefit / queue' in n3, matches nothing the vault now produces
  note 5, which held 'cost / benefit / queue / benefit' in n3, matches nothing the vault now produces
  note 6, which held 'cost / benefit / queue / cost' in n3, matches nothing the vault now produces
POLICY
  park-and-report: note 1, which held 'cost / benefit / cache' in n3, matches nothing the vault now produces
  park-and-report: note 2, which held 'cost / benefit / cache / benefit' in n3, matches nothing the vault now produces
  park-and-report: note 3, which held 'cost / benefit / cache / cost' in n3, matches nothing the vault now produces
  park-and-report: note 4, which held 'cost / benefit / queue' in n3, matches nothing the vault now produces
  park-and-report: note 5, which held 'cost / benefit / queue / benefit' in n3, matches nothing the vault now produces
  park-and-report: note 6, which held 'cost / benefit / queue / cost' in n3, matches nothing the vault now produces
LEDGER
  new card at ZERO: 'cost / benefit / cache'
  new card at ZERO: 'cost / benefit / cache / benefit'
  new card at ZERO: 'cost / benefit / cache / cost'
  new card at ZERO: 'cost / benefit / queue'
  new card at ZERO: 'cost / benefit / queue / benefit'
  new card at ZERO: 'cost / benefit / queue / cost'
  history STRANDED on the suspended note: 'cost / benefit / cache'
  history STRANDED on the suspended note: 'cost / benefit / cache / benefit'
  history STRANDED on the suspended note: 'cost / benefit / cache / cost'
  history STRANDED on the suspended note: 'cost / benefit / queue'
  history STRANDED on the suspended note: 'cost / benefit / queue / benefit'
  history STRANDED on the suspended note: 'cost / benefit / queue / cost'

SCENARIO S39 — withdraw the cell cards   [table pair card]
EDIT
  the marker becomes #flashcard/table/rows
DIFF
  non-event   'cost / benefit / cache' in n3
  non-event   'cost / benefit / queue' in n3
  flag        'cost / benefit / cache / benefit' in n3 — suspended, never deleted
  flag        'cost / benefit / cache / cost' in n3 — suspended, never deleted
  flag        'cost / benefit / queue / benefit' in n3 — suspended, never deleted
  flag        'cost / benefit / queue / cost' in n3 — suspended, never deleted
EVIDENCE
  note 2, which held 'cost / benefit / cache / benefit' in n3, matches nothing the vault now produces
  note 3, which held 'cost / benefit / cache / cost' in n3, matches nothing the vault now produces
  note 5, which held 'cost / benefit / queue / benefit' in n3, matches nothing the vault now produces
  note 6, which held 'cost / benefit / queue / cost' in n3, matches nothing the vault now produces
POLICY
  park-and-report: note 2, which held 'cost / benefit / cache / benefit' in n3, matches nothing the vault now produces
  park-and-report: note 3, which held 'cost / benefit / cache / cost' in n3, matches nothing the vault now produces
  park-and-report: note 5, which held 'cost / benefit / queue / benefit' in n3, matches nothing the vault now produces
  park-and-report: note 6, which held 'cost / benefit / queue / cost' in n3, matches nothing the vault now produces
LEDGER
  history STRANDED on the suspended note: 'cost / benefit / cache / benefit'
  history STRANDED on the suspended note: 'cost / benefit / cache / cost'
  history STRANDED on the suspended note: 'cost / benefit / queue / benefit'
  history STRANDED on the suspended note: 'cost / benefit / queue / cost'

SCENARIO S39b — change the cell direction alone   [table pair card]
EDIT
  the marker becomes #flashcard/table/1way
DIFF
  non-event   'cost / benefit / cache' in n3
  update      'cost / benefit / cache / benefit' in n3
  update      'cost / benefit / cache / cost' in n3
  non-event   'cost / benefit / queue' in n3
  update      'cost / benefit / queue / benefit' in n3
  update      'cost / benefit / queue / cost' in n3
EVIDENCE
  (empty — no stranded note to explain)
POLICY
  (no findings reached the seam)
LEDGER
  history intact in place: 'cost / benefit / cache / benefit' (updated)
  history intact in place: 'cost / benefit / cache / cost' (updated)
  history intact in place: 'cost / benefit / queue / benefit' (updated)
  history intact in place: 'cost / benefit / queue / cost' (updated)

SCENARIO S40 — an unrelated failure inside the table section   [table pair card]
EDIT
  one value cell gains an embed: ![[x.png]]
DIFF
  refused     'cost / benefit' in n3 at Patterns.md:5 (heading): an Obsidian embed of 'x.png', at 'cost / benefit'
  sheltered   'cost / benefit / cache' in n3 — something above it failed to build, so absence proves nothing
  sheltered   'cost / benefit / cache / benefit' in n3 — something above it failed to build, so absence proves nothing
  sheltered   'cost / benefit / cache / cost' in n3 — something above it failed to build, so absence proves nothing
  sheltered   'cost / benefit / queue' in n3 — something above it failed to build, so absence proves nothing
  sheltered   'cost / benefit / queue / benefit' in n3 — something above it failed to build, so absence proves nothing
  sheltered   'cost / benefit / queue / cost' in n3 — something above it failed to build, so absence proves nothing
EVIDENCE
  (empty — no stranded note to explain)
POLICY
  (no findings reached the seam)
LEDGER
  refused, nothing written: 'cost / benefit' in n3 at Patterns.md:5 (heading): an Obsidian embed of 'x.png', at 'cost / benefit'
  history untouched, sheltered: 'cost / benefit / cache'
  history untouched, sheltered: 'cost / benefit / cache / benefit'
  history untouched, sheltered: 'cost / benefit / cache / cost'
  history untouched, sheltered: 'cost / benefit / queue'
  history untouched, sheltered: 'cost / benefit / queue / benefit'
  history untouched, sheltered: 'cost / benefit / queue / cost'

SCENARIO S41 — replace a row's subject on a row-only table, values byte-identical   [table row card]
EDIT
  the marker requests row cards only (#flashcard/table/rows); the Queue row is deleted and a Stream row with the same values added — the row card renders the concept into its Substance
DIFF
  non-event   'cost / benefit / cache' in n3
  create      'cost / benefit / stream' in n3
  flag        'cost / benefit / queue' in n3 — suspended, never deleted
EVIDENCE
  note 2, which held 'cost / benefit / queue' in n3, matches nothing the vault now produces
POLICY
  park-and-report: note 2, which held 'cost / benefit / queue' in n3, matches nothing the vault now produces
LEDGER
  new card at ZERO: 'cost / benefit / stream'
  history STRANDED on the suspended note: 'cost / benefit / queue'

SCENARIO S42 — fix a typo in a row concept on a row-only table   [table row card]
EDIT
  the marker requests row cards only; 'Qeue' becomes 'Queue', values untouched — the same observable as S41
DIFF
  non-event   'cost / benefit / cache' in n3
  create      'cost / benefit / queue' in n3
  flag        'cost / benefit / qeue' in n3 — suspended, never deleted
EVIDENCE
  note 2, which held 'cost / benefit / qeue' in n3, matches nothing the vault now produces
POLICY
  park-and-report: note 2, which held 'cost / benefit / qeue' in n3, matches nothing the vault now produces
LEDGER
  new card at ZERO: 'cost / benefit / queue'
  history STRANDED on the suspended note: 'cost / benefit / qeue'

SCENARIO S43 — edit one value, row card view   [table row card]
EDIT
  'Speed' becomes 'High speed' — the pair card and the row card both update in place
DIFF
  update      'cost / benefit / cache' in n3
  update      'cost / benefit / cache / benefit' in n3
  non-event   'cost / benefit / cache / cost' in n3
  non-event   'cost / benefit / queue' in n3
  non-event   'cost / benefit / queue / benefit' in n3
  non-event   'cost / benefit / queue / cost' in n3
EVIDENCE
  (empty — no stranded note to explain)
POLICY
  (no findings reached the seam)
LEDGER
  history intact in place: 'cost / benefit / cache' (updated)
  history intact in place: 'cost / benefit / cache / benefit' (updated)

SCENARIO S44 — reword a cloze heading in place   [cloze section card]
EDIT
  '## The three layers' becomes '## Skin layers'; the passage is untouched
DIFF
  non-event   'block '^forearm'' in n4
  create      'skin layers' in n4
  flag        'the three layers' in n4 — suspended, never deleted
EVIDENCE
  note 2, which held 'the three layers' in n4, is 'skin layers' in n4 — the same name, somewhere else, Anatomy.md:5 (heading); Context: 'Anatomy › The three layers' → 'Anatomy › Skin layers'
POLICY
  apply-reassign: reassigned, keeping its review history — note 2, which held 'the three layers' in n4, is 'skin layers' in n4 — the same name, somewhere else, Anatomy.md:5 (heading); Context: 'Anatomy › The three layers' → 'Anatomy › Skin layers'
LEDGER
  history FOLLOWED onto 'skin layers' — the existing note was reassigned, no new card

SCENARIO S44b — move the cloze section to another note   [cloze section card]
EDIT
  the section moves verbatim into Reference.md
DIFF
  non-event   'block '^forearm'' in n4
  create      'the three layers' in n4b
  flag        'the three layers' in n4 — suspended, never deleted
EVIDENCE
  note 2, which held 'the three layers' in n4, is 'the three layers' in n4b — the same path, in a different note, Reference.md:7 (heading); Context: 'Anatomy › The three layers' → 'Reference › The three layers'
POLICY
  apply-reassign: reassigned, keeping its review history — note 2, which held 'the three layers' in n4, is 'the three layers' in n4b — the same path, in a different note, Reference.md:7 (heading); Context: 'Anatomy › The three layers' → 'Reference › The three layers'
LEDGER
  history FOLLOWED onto 'the three layers' — the existing note was reassigned, no new card

SCENARIO S45 — edit cloze prose, deletions untouched   [cloze section card]
EDIT
  words are added around the highlights
DIFF
  non-event   'block '^forearm'' in n4
  update      'the three layers' in n4
EVIDENCE
  (empty — no stranded note to explain)
POLICY
  (no findings reached the seam)
LEDGER
  history intact in place: 'the three layers' (updated)

SCENARIO S46 — move the cloze section AND edit its prose   [cloze section card]
EDIT
  the section is re-parented and its prose edited, one commit
DIFF
  create      'anatomy of the skin / the three layers' in n4
  non-event   'block '^forearm'' in n4
  flag        'the three layers' in n4 — suspended, never deleted
EVIDENCE
  note 2, which held 'the three layers' in n4, matches nothing the vault now produces
POLICY
  park-and-report: note 2, which held 'the three layers' in n4, matches nothing the vault now produces
LEDGER
  new card at ZERO: 'anatomy of the skin / the three layers'
  history STRANDED on the suspended note: 'the three layers'

SCENARIO S47 — add an unlabelled highlight before the existing ones   [cloze section card]
EDIT
  a new sentence with an unlabelled deletion is prepended
DIFF
  non-event   'block '^forearm'' in n4
  update      'the three layers' in n4
EVIDENCE
  (empty — no stranded note to explain)
POLICY
  (no findings reached the seam)
LEDGER
  history intact in place: 'the three layers' (updated)

SCENARIO S48 — two unlabelled highlights with identical text   [cloze section card]
EDIT
  a second unlabelled ==<<epidermis>>== appears in the section
DIFF
  refused     'the three layers' in n4 at Anatomy.md:5 (heading): two unlabelled '==<<epidermis>>==' highlights at 'the three layers' cannot be told apart — label them, e.g. ==<<1|epidermis>>==
  non-event   'block '^forearm'' in n4
  sheltered   'the three layers' in n4 — something above it failed to build, so absence proves nothing
EVIDENCE
  (empty — no stranded note to explain)
POLICY
  (no findings reached the seam)
LEDGER
  refused, nothing written: 'the three layers' in n4 at Anatomy.md:5 (heading): two unlabelled '==<<epidermis>>==' highlights at 'the three layers' cannot be told apart — label them, e.g. ==<<1|epidermis>>==
  history untouched, sheltered: 'the three layers'

SCENARIO S49 — remove the cloze marker over an anchored block   [cloze section card]
EDIT
  the #flashcard/cloze marker comes off the heading; the block keeps its ^blockid
DIFF
  create      'block '^layers'' in n4c
  flag        'the three layers' in n4c — suspended, never deleted
EVIDENCE
  note 1, which held 'the three layers' in n4c, matches nothing the vault now produces
POLICY
  park-and-report: note 1, which held 'the three layers' in n4c, matches nothing the vault now produces
LEDGER
  new card at ZERO: 'block '^layers''
  history STRANDED on the suspended note: 'the three layers'

SCENARIO S50 — move an anchored block under a different heading   [cloze block card]
EDIT
  the ^forearm paragraph moves from '## Bones' to '## Joints', same note
DIFF
  update      'block '^forearm'' in n4
  non-event   'the three layers' in n4
EVIDENCE
  (empty — no stranded note to explain)
POLICY
  (no findings reached the seam)
LEDGER
  history intact in place: 'block '^forearm'' (updated)

SCENARIO S51 — move an anchored block to another note   [cloze block card]
EDIT
  the paragraph, ^forearm included, moves into Skeleton.md
DIFF
  non-event   'the three layers' in n4
  create      'block '^forearm'' in n4b
  flag        'block '^forearm'' in n4 — suspended, never deleted
EVIDENCE
  note 1, which held 'block '^forearm'' in n4, is 'block '^forearm'' in n4b — the same path, in a different note, Skeleton.md (block); Context: 'Anatomy › Bones' → 'Skeleton › Bones'
POLICY
  apply-reassign: reassigned, keeping its review history — note 1, which held 'block '^forearm'' in n4, is 'block '^forearm'' in n4b — the same path, in a different note, Skeleton.md (block); Context: 'Anatomy › Bones' → 'Skeleton › Bones'
LEDGER
  history FOLLOWED onto 'block '^forearm'' — the existing note was reassigned, no new card

SCENARIO S52 — rename the block anchor   [cloze block card]
EDIT
  '^forearm' becomes '^forearm-bones'; the text is untouched
DIFF
  create      'block '^forearm-bones'' in n4
  non-event   'the three layers' in n4
  flag        'block '^forearm'' in n4 — suspended, never deleted
EVIDENCE
  note 1, which held 'block '^forearm'' in n4, is 'block '^forearm-bones'' in n4 — the same name, somewhere else, Anatomy.md (block)
POLICY
  apply-reassign: reassigned, keeping its review history — note 1, which held 'block '^forearm'' in n4, is 'block '^forearm-bones'' in n4 — the same name, somewhere else, Anatomy.md (block)
LEDGER
  history FOLLOWED onto 'block '^forearm-bones'' — the existing note was reassigned, no new card

SCENARIO S53 — delete the block anchor, keep the deletions   [cloze block card]
EDIT
  the '^forearm' id is removed from the paragraph
DIFF
  refused     Anatomy.md:0: this block has ==<<cloze>>== deletions and no ^blockid, so its card would have no identity that survives an edit — add one (in Obsidian: copy the block link), or put the deletions under a #flashcard/cloze heading
  non-event   'the three layers' in n4
  flag        'block '^forearm'' in n4 — suspended, never deleted
EVIDENCE
  note 1, which held 'block '^forearm'' in n4, matches nothing the vault now produces
POLICY
  park-and-report: note 1, which held 'block '^forearm'' in n4, matches nothing the vault now produces
LEDGER
  refused, nothing written: Anatomy.md:0: this block has ==<<cloze>>== deletions and no ^blockid, so its card would have no identity that survives an edit — add one (in Obsidian: copy the block link), or put the deletions under a #flashcard/cloze heading
  history STRANDED on the suspended note: 'block '^forearm''

SCENARIO S54 — move the block into the cloze section   [cloze block card]
EDIT
  the paragraph moves under the #flashcard/cloze heading and loses its anchor
DIFF
  update      'the three layers' in n4
  flag        'block '^forearm'' in n4 — suspended, never deleted
EVIDENCE
  note 1, which held 'block '^forearm'' in n4, matches nothing the vault now produces
POLICY
  park-and-report: note 1, which held 'block '^forearm'' in n4, matches nothing the vault now produces
LEDGER
  history intact in place: 'the three layers' (updated)
  history STRANDED on the suspended note: 'block '^forearm''

SCENARIO S55 — reorder sequence items   [sequence card]
EDIT
  two list items change places
DIFF
  update      'deploy pipeline' in n7
EVIDENCE
  (empty — no stranded note to explain)
POLICY
  (no findings reached the seam)
LEDGER
  history intact in place: 'deploy pipeline' (updated)

SCENARIO S56 — reword the sequence title in place   [sequence card]
EDIT
  '## Deploy pipeline' becomes '## Deployment pipeline'; the list is untouched
DIFF
  create      'deployment pipeline' in n7
  flag        'deploy pipeline' in n7 — suspended, never deleted
EVIDENCE
  note 1, which held 'deploy pipeline' in n7, is 'deployment pipeline' in n7 — a different name, in the same place, Steps.md:5 (heading); Title: 'Deploy pipeline' → 'Deployment pipeline'
POLICY
  apply-reassign: reassigned, keeping its review history — note 1, which held 'deploy pipeline' in n7, is 'deployment pipeline' in n7 — a different name, in the same place, Steps.md:5 (heading); Title: 'Deploy pipeline' → 'Deployment pipeline'
LEDGER
  history FOLLOWED onto 'deployment pipeline' — the existing note was reassigned, no new card

SCENARIO S56b — replace the subject in the sequence title   [sequence card]
EDIT
  '## Deploy pipeline' becomes '## Release train'; the list is byte-identical — the same observable as S56
DIFF
  create      'release train' in n7
  flag        'deploy pipeline' in n7 — suspended, never deleted
EVIDENCE
  note 1, which held 'deploy pipeline' in n7, is 'release train' in n7 — a different name, in the same place, Steps.md:5 (heading); Title: 'Deploy pipeline' → 'Release train'
POLICY
  apply-reassign: reassigned, keeping its review history — note 1, which held 'deploy pipeline' in n7, is 'release train' in n7 — a different name, in the same place, Steps.md:5 (heading); Title: 'Deploy pipeline' → 'Release train'
LEDGER
  history FOLLOWED onto 'release train' — the existing note was reassigned, no new card

SCENARIO S57 — switch the sequence source in place   [sequence card]
EDIT
  #flashcard/sequence becomes #flashcard/sequence/headers on a heading that has both a list and subheadings
DIFF
  update      'deploy pipeline' in n7
EVIDENCE
  (empty — no stranded note to explain)
POLICY
  (no findings reached the seam)
LEDGER
  history intact in place: 'deploy pipeline' (updated)

SCENARIO S57b — change the reveal order AND move   [sequence card]
EDIT
  the marker gains /dfs — 'recursive' alone already means breadth-first, so /dfs is the suffix that changes the Reveal field — and the heading is re-parented, one commit
DIFF
  create      'ops / deploy pipeline' in n7
  flag        'deploy pipeline' in n7 — suspended, never deleted
EVIDENCE
  note 1, which held 'deploy pipeline' in n7, matches nothing the vault now produces
POLICY
  park-and-report: note 1, which held 'deploy pipeline' in n7, matches nothing the vault now produces
LEDGER
  new card at ZERO: 'ops / deploy pipeline'
  history STRANDED on the suspended note: 'deploy pipeline'

SCENARIO S58 — rename a whole-note card's file   [whole-note card]
EDIT
  Essential Numbers.md becomes Core Numbers.md; the id is untouched
DIFF
  update      'the note itself' in n5
EVIDENCE
  (empty — no stranded note to explain)
POLICY
  (no findings reached the seam)
LEDGER
  history intact in place: 'the note itself' (updated)

SCENARIO S59 — change only the id   [whole-note card]
EDIT
  'id: n5' becomes 'id: n5x'; the file name and body are untouched
DIFF
  create      'the note itself' in n5x
  flag        'the note itself' in n5 — suspended, never deleted
EVIDENCE
  note 1, which held 'the note itself' in n5, is 'the note itself' in n5x — the same path, in a different note, Essential Numbers.md:7 (heading)
POLICY
  apply-reassign: reassigned, keeping its review history — note 1, which held 'the note itself' in n5, is 'the note itself' in n5x — the same path, in a different note, Essential Numbers.md:7 (heading)
LEDGER
  history FOLLOWED onto 'the note itself' — the existing note was reassigned, no new card

SCENARIO S60 — change the id AND rename the file   [whole-note card]
EDIT
  both in one commit
DIFF
  create      'the note itself' in n5x
  flag        'the note itself' in n5 — suspended, never deleted
EVIDENCE
  note 1, which held 'the note itself' in n5, says the same thing as 'the note itself' in n5x (Core Numbers.md:7 (heading)) — but its name changed for a reason the key does not explain, so nothing is applied: Front: 'Essential Numbers' → 'Core Numbers'
POLICY
  park-and-report: note 1, which held 'the note itself' in n5, says the same thing as 'the note itself' in n5x (Core Numbers.md:7 (heading)) — but its name changed for a reason the key does not explain, so nothing is applied: Front: 'Essential Numbers' → 'Core Numbers'
LEDGER
  new card at ZERO: 'the note itself'
  history STRANDED on the suspended note: 'the note itself'

SCENARIO S61 — give the whole-note card a first heading   [whole-note card]
EDIT
  the frontmatter marker moves onto a new '# Essential Numbers' heading
DIFF
  create      'essential numbers' in n5
  flag        'the note itself' in n5 — suspended, never deleted
EVIDENCE
  note 1, which held 'the note itself' in n5, matches nothing the vault now produces
POLICY
  park-and-report: note 1, which held 'the note itself' in n5, matches nothing the vault now produces
LEDGER
  new card at ZERO: 'essential numbers'
  history STRANDED on the suspended note: 'the note itself'

SCENARIO S62 — delete the id from the frontmatter   [whole-note card]
EDIT
  the 'id:' line is removed; the marked heading stays
DIFF
  refused     Coupling.md: has #flashcard heading(s) but no 'id' in its frontmatter, so its cards cannot be keyed — add an id to the frontmatter
  flag        'temporal coupling' in n2 — suspended, never deleted
EVIDENCE
  note 1, which held 'temporal coupling' in n2, matches nothing the vault now produces
POLICY
  park-and-report: note 1, which held 'temporal coupling' in n2, matches nothing the vault now produces
LEDGER
  refused, nothing written: Coupling.md: has #flashcard heading(s) but no 'id' in its frontmatter, so its cards cannot be keyed — add an id to the frontmatter
  history STRANDED on the suspended note: 'temporal coupling'

SCENARIO S63 — rename a relation card's file   [relation card]
EDIT
  Function Space.md becomes Exponential Object.md; the property is untouched
DIFF
  update      'property 'special-case-of'' in n6
EVIDENCE
  (empty — no stranded note to explain)
POLICY
  (no findings reached the seam)
LEDGER
  history intact in place: 'property 'special-case-of'' (updated)

SCENARIO S64 — move the relation declaration to another note   [relation card]
EDIT
  the property and its rule move from Function Space.md into HomFunctor.md
DIFF
  create      'property 'special-case-of'' in n6b
  flag        'property 'special-case-of'' in n6 — suspended, never deleted
EVIDENCE
  note 1, which held 'property 'special-case-of'' in n6, says the same thing as 'property 'special-case-of'' in n6b (HomFunctor.md:2 (frontmatter property)) — but its name changed for a reason the key does not explain, so nothing is applied: Concept: 'Function Space' → 'HomFunctor'
POLICY
  park-and-report: note 1, which held 'property 'special-case-of'' in n6, says the same thing as 'property 'special-case-of'' in n6b (HomFunctor.md:2 (frontmatter property)) — but its name changed for a reason the key does not explain, so nothing is applied: Concept: 'Function Space' → 'HomFunctor'
LEDGER
  new card at ZERO: 'property 'special-case-of''
  history STRANDED on the suspended note: 'property 'special-case-of''

SCENARIO S65 — rename the predicate   [relation card]
EDIT
  'special-case-of' becomes 'instance-of' in the frontmatter and in the rule; the value is untouched
DIFF
  create      'property 'instance-of'' in n6
  flag        'property 'special-case-of'' in n6 — suspended, never deleted
EVIDENCE
  note 1, which held 'property 'special-case-of'' in n6, is 'property 'instance-of'' in n6 — a different name, in the same place, Function Space.md:2 (frontmatter property); Descriptor: 'special-case-of' → 'instance-of'
POLICY
  apply-reassign: reassigned, keeping its review history — note 1, which held 'property 'special-case-of'' in n6, is 'property 'instance-of'' in n6 — a different name, in the same place, Function Space.md:2 (frontmatter property); Descriptor: 'special-case-of' → 'instance-of'
LEDGER
  history FOLLOWED onto 'property 'instance-of'' — the existing note was reassigned, no new card

SCENARIO S66 — retidy the property's capitalisation   [relation card]
EDIT
  'special-case-of:' becomes 'Special-Case-Of:'; the rule and value are untouched
DIFF
  update      'property 'special-case-of'' in n6
EVIDENCE
  (empty — no stranded note to explain)
POLICY
  (no findings reached the seam)
LEDGER
  history intact in place: 'property 'special-case-of'' (updated)

SCENARIO S67 — add a second value   [relation card]
EDIT
  the property's value becomes a two-element list
DIFF
  update      'property 'special-case-of'' in n6
EVIDENCE
  (empty — no stranded note to explain)
POLICY
  (no findings reached the seam)
LEDGER
  history intact in place: 'property 'special-case-of'' (updated)

SCENARIO S68 — remove the rule from Properties-to-Flashcards   [relation card]
EDIT
  the declarations block is deleted; the property stays in the frontmatter
DIFF
  flag        'property 'special-case-of'' in n6 — suspended, never deleted
EVIDENCE
  note 1, which held 'property 'special-case-of'' in n6, matches nothing the vault now produces
POLICY
  park-and-report: note 1, which held 'property 'special-case-of'' in n6, matches nothing the vault now produces
LEDGER
  history STRANDED on the suspended note: 'property 'special-case-of''

SCENARIO S69 — a second note declares the same reversible relation   [relation card]
EDIT
  Currying.md arrives declaring the same (predicate, object) as 2way
DIFF
  refused     'property 'special-case-of'' in n6 at Function Space.md:2 (frontmatter property): 'special-case-of' is declared as a 2way edge, so it also asks which thing has 'HomSet' on the far end — and 2 notes answer that: Currying, Function Space. Declare 'special-case-of' as 1way, or make the relation one-to-one
  refused     'property 'special-case-of'' in n6c at Currying.md:2 (frontmatter property): 'special-case-of' is declared as a 2way edge, so it also asks which thing has 'HomSet' on the far end — and 2 notes answer that: Currying, Function Space. Declare 'special-case-of' as 1way, or make the relation one-to-one
  non-event   'property 'special-case-of'' in n6
  create      'property 'special-case-of'' in n6c
EVIDENCE
  (empty — no stranded note to explain)
POLICY
  (no findings reached the seam)
LEDGER
  refused, nothing written: 'property 'special-case-of'' in n6 at Function Space.md:2 (frontmatter property): 'special-case-of' is declared as a 2way edge, so it also asks which thing has 'HomSet' on the far end — and 2 notes answer that: Currying, Function Space. Declare 'special-case-of' as 1way, or make the relation one-to-one
  refused, nothing written: 'property 'special-case-of'' in n6c at Currying.md:2 (frontmatter property): 'special-case-of' is declared as a 2way edge, so it also asks which thing has 'HomSet' on the far end — and 2 notes answer that: Currying, Function Space. Declare 'special-case-of' as 1way, or make the relation one-to-one
  new card at ZERO: 'property 'special-case-of''

SCENARIO S70 — basename coincidence across folders   [relation card]
EDIT
  A/Kafka.md (id kx) is deleted; B/Kafka.md (id ky), same relation and value, is new this run
DIFF
  create      'property 'special-case-of'' in ky
  flag        'property 'special-case-of'' in kx — suspended, never deleted
EVIDENCE
  note 1, which held 'property 'special-case-of'' in kx, is 'property 'special-case-of'' in ky — the same path, in a different note, B/Kafka.md:2 (frontmatter property); Context: 'A' → 'B'
POLICY
  apply-reassign: reassigned, keeping its review history — note 1, which held 'property 'special-case-of'' in kx, is 'property 'special-case-of'' in ky — the same path, in a different note, B/Kafka.md:2 (frontmatter property); Context: 'A' → 'B'
LEDGER
  history FOLLOWED onto 'property 'special-case-of'' — the existing note was reassigned, no new card

SCENARIO S71 — re-express the relation as a marked heading   [relation card]
EDIT
  the frontmatter property becomes '## Special-Case-Of #flashcard/cdd/2way'
DIFF
  create      'special-case-of' in n6
  flag        'property 'special-case-of'' in n6 — suspended, never deleted
EVIDENCE
  note 1, which held 'property 'special-case-of'' in n6, matches nothing the vault now produces
POLICY
  park-and-report: note 1, which held 'property 'special-case-of'' in n6, matches nothing the vault now produces
LEDGER
  new card at ZERO: 'special-case-of'
  history STRANDED on the suspended note: 'property 'special-case-of''

SCENARIO S72 — move a file between folders   [cross-cutting]
EDIT
  Coupling.md moves from Topics/ to Patterns/; nothing inside it changes
DIFF
  update      'temporal coupling' in n2
EVIDENCE
  (empty — no stranded note to explain)
POLICY
  (no findings reached the seam)
LEDGER
  history intact in place: 'temporal coupling' (updated)

SCENARIO S73 — S04's move against a hand-edited note   [cross-cutting]
EDIT
  the same re-parent as S04, but the observed note's Back was edited by hand in Anki after the last sync
DIFF
  create      'notes / temporal coupling' in n2
  flag        'temporal coupling' in n2 — suspended, never deleted
EVIDENCE
  note 1, which held 'temporal coupling' in n2, matches nothing the vault now produces
POLICY
  park-and-report: note 1, which held 'temporal coupling' in n2, matches nothing the vault now produces
LEDGER
  new card at ZERO: 'notes / temporal coupling'
  history STRANDED on the suspended note: 'temporal coupling'

SCENARIO S74 — S04's move against a legacy note type   [cross-cutting]
EDIT
  the same re-parent as S04, but the observed note sits on stock 'Basic', which this tool does not declare
DIFF
  create      'notes / temporal coupling' in n2
  flag        'temporal coupling' in n2 — suspended, never deleted
EVIDENCE
  note 1, which held 'temporal coupling' in n2, could not be compared at all: 'Basic' is not a note type this tool declares, so its fields carry no roles and nothing about them can be compared
POLICY
  park-and-report: note 1, which held 'temporal coupling' in n2, could not be compared at all: 'Basic' is not a note type this tool declares, so its fields carry no roles and nothing about them can be compared
LEDGER
  new card at ZERO: 'notes / temporal coupling'
  history STRANDED on the suspended note: 'temporal coupling'
```
