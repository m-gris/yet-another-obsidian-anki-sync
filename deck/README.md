# The scenario deck — every edit-event of the card model, run without Anki

This directory holds a **deck of 90 scenarios** (S01–S74, twelve `b`-variants, S24A/S24B/S24C — the
three cases the final adversarial review constructed for the ruling of 2026-09-13 — and S24D, S33b
and S14b, the two-run case, the standing-table-row case and the no-voucher case that the attack swarm
and the sheet's own open cases produced) covering every
card kind the tool produces — plain heading, concept-descriptor, table pair, table row, cloze
section, cloze block, sequence, whole-note, relation — and, for each kind, every edit an author
can make: reword, move, retag, split, merge, delete, restore, rename a file, change an id, and
the combinations of those that collide.

Each scenario is a **before-fixture vault, an edit, and one sync run** through the real
machinery: the production extraction pipeline (`extract/VaultWalker.scan`), a synthesized Anki
collection whose fields are **byte-copied** from what the extractor produced (never re-rendered
— the move-evidence floor is byte-identity, and a normalising fake would silently pass the
scenarios the floor should decide), and the real move-evidence survey
(`plan/MoveEvidence.survey`, branch `move-build`). **No Anki, no AnkiConnect, no vault outside
this repository is ever touched.**

The point of the deck is the **policy seam**: the one place where different answers to the open
design questions — most sharply, *"did the author reword this card, or replace its subject?"*,
which produces byte-identical evidence in both cases (compare S01 with S02, S22 with S23, S33
with S34) — can be tried out and *felt*, transcript against transcript, without touching the
mechanism itself.

## Running the deck

From the repository root:

```
scala-cli run . --main-class obsidiananki.deck.runDeck
```

prints the whole deck's transcript under the baseline policy: a corroborated finding auto-applies
per the 2026-09-05 ruling, every other finding is reported and never acted on. That policy itself
has not changed; what has is which pairings the survey is willing to call `Corroborated`, per the
rulings of `docs/design/IDENTITY-DECISION-SHEET.md` — so this command now prints the ruled
policy's transcript, not a pre-ruling one. A committed copy, annotated with what reads
differently from before those rulings landed, is `TRANSCRIPT-baseline.md` at the repository root.
Redirect a fresh run to a file to diff against another world's.

The deck's own tests:

```
scala-cli test . --test-only 'obsidiananki.deck.*'
```

## Reading a transcript

Every scenario renders the same six sections, in the same order, so two worlds' transcripts are
byte-comparable and `diff` shows exactly — and only — where their policies disagree:

| section    | what it holds |
|------------|---------------|
| `SCENARIO` | id, one-line title, card kind |
| `EDIT`     | what the author did, in one or two lines |
| `DIFF`     | the evidence-free classification of every touched card: create / update / retype / flag / unflag / non-event / sheltered / refused — including the S17 whole-plan refusal. This section is identical across worlds; it exists so the events **no finding can represent** (a silent subject drift on a file rename, S26/S58/S63) stay visible |
| `EVIDENCE` | each `MoveFinding.describe` line from the real survey, verbatim; an empty survey is stated, never omitted |
| `POLICY`   | the seam's decision and exact user-facing message, per finding — **the only section a world may change** |
| `LEDGER`   | one line per card: history followed / history stranded / new card at zero / refused |

Multi-step scenarios (S13, S14, S14b, S24D) render one `DIFF…LEDGER` block per run, the collection
evolving between runs by the evidence-free diff alone. S24D and S14b are what those runs are FOR:
what a run may conclude sometimes turns on whether an edit happened inside it or in an earlier one —
two of the three witnesses that a concept survives are facts about one run, and one of the three
vouchers that license reattaching an orphan is the signature of a single edit.

## Implementing a world

A world is **one implementation of the `MovePolicy` trait** (`deck/MovePolicy.scala`) plus its
own tests at that seam. Nothing else.

```scala
object MyWorld extends MovePolicy:
  val name = "my-world — one sentence saying what question this world answers differently"
  def decide(finding: MoveFinding): PolicyDecision = finding match
    case c: MoveFinding.Corroborated => ... // apply, park, refuse, or queue — your call
    case other                       => PolicyDecision.ParkAndReport(other.describe)
```

The decision vocabulary is `PolicyDecision`: `ApplyReassign` (history follows; only a
`Corroborated` finding can license one, and it travels inside the decision), `ParkAndReport`
(the built behaviour for everything the baseline does not apply), and two cases the baseline
never uses that a world may: `Refuse` and `QueueForReview`. Every decision carries the exact
message the run prints — the wording is the world's, not the runner's.

Render your world's transcript with
`WorldDeck.transcript(fixturesRoot, MyWorld)` (see `runDeck` for how the fixtures root is
found), and diff it against the baseline's.

### What a world may NOT do — hard constraints

- **It may not touch identity derivation.** Identity stays a pure function of the vault
  (`oas-4ti`, ruled 2026-08-29). A policy receives findings; it never sees, derives or suggests
  a key.
- **It may not introduce thresholds or scores.** Exact bytes or nothing. The policy receives
  typed findings, not field values to re-compare.
- **It may not mint a reassignment from any finding other than the one it was asked about.**
  `ApplyReassign` carries the `MoveFinding.Corroborated` that licenses it, and the runner hard-errors
  on a decision whose carried evidence is not the finding it answers. No other finding
  shape can be widened into an application — the same construction `SyncAction.Reassign` uses.

Events that generate no finding (S06, S07, S10, S26, S29, S30, S58, S63, S66, S67, S72) are
outside the seam by construction and identical across worlds.

### Writing a world's tests

Assert your policy's verdicts at the seam — `MyWorld.decide(finding)` against findings taken
from `WorldDeck.runScenario(...)` steps, or against hand-built ones. The deck's own suite
(`deck/WorldDeck.test.scala`) already pins the **mechanism's** output for every scenario:
`[SETTLED]` scenarios assert finding, grade and the baseline action; `[OPEN]` scenarios assert
the finding and grade only, because the verdict on those is precisely what a world decides.
Where a standing ruling once said the mechanism was wrong, the gate built for
`docs/design/IDENTITY-DECISION-SHEET.md`'s rulings now makes most of those scenarios read
correctly, and their test names no longer claim otherwise: S24 (R2) and S33/S34 (Decision 2) are
now `[SETTLED-RULING]`, as are S24A/S24B/S24C, S24D, S33b and S14b (the rulings of 2026-09-13). Only **S17** is still
left asserting a defective current output, carrying the ruling its fix must satisfy in its name
(R5: a duplicate key should refuse only the cards involved, not the whole run).

## Fixture layout

```
deck/fixtures/<Sxx>/before/   the vault as last synced (must extract cleanly — the harness refuses otherwise)
deck/fixtures/<Sxx>/after/    the vault after the edit
deck/fixtures/<Sxx>/after2/   (three-step scenarios only) the vault after the second edit
```

A variant carries its parent's number and a suffix — the twelve `b`-variants, and
S24A/S24B/S24C/S24D — and sits directly after it in `WorldDeck.scenarios`, which the deck's own id
contract asserts.

No scenario shares a fixture with another. S41/S42 read the same shape of edit as S33/S34 (a
row's subject swapped, or a typo in it fixed) but from their own directories, under a marker
that mints row cards only (`#flashcard/table/rows`) — so their transcript block holds the row
card's own evidence in isolation, with no pair card sharing it. Bodies that must be
byte-identical across states are **copied, never retyped**. Scenarios whose
observed collection must differ from anything a spec produces (S73's hand-edited field, S74's
stock note type, S14's parked orphan) perturb the synthesized cards in `WorldDeck.scenarios`,
and only those.
