# The scenario deck — every edit-event of the card model, run without Anki

This directory holds a **deck of 84 scenarios** (S01–S74 plus ten `b`-variants) covering every
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

prints the whole deck's transcript under the baseline policy (the behaviour the `move-build`
Planner ships today: a corroborated finding auto-applies per the 2026-09-05 ruling; every other
finding is reported and never acted on). Redirect it to a file to diff against another world's.

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

Multi-step scenarios (S13, S14) render one `DIFF…LEDGER` block per run, the collection evolving
between runs by the evidence-free diff alone.

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
Where a standing ruling says the current mechanism is wrong, the test asserts the defective
current output and carries the ruling in its name — S17 (R5: a duplicate key should refuse only
the cards involved, not the whole run), S24 (R2: a concept-descriptor's parent is constitutive,
so re-parenting a descriptor must NOT carry history), S33 (R3: a table row whose subject changed
must NOT silently keep its history).

## Fixture layout

```
deck/fixtures/<Sxx>/before/   the vault as last synced (must extract cleanly — the harness refuses otherwise)
deck/fixtures/<Sxx>/after/    the vault after the edit
deck/fixtures/<Sxx>/after2/   (three-step scenarios only) the vault after the second edit
```

No scenario shares a fixture with another. S41/S42 read the same shape of edit as S33/S34 (a
row's subject swapped, or a typo in it fixed) but from their own directories, under a marker
that mints row cards only (`#flashcard/table/rows`) — so their transcript block holds the row
card's own evidence in isolation, with no pair card sharing it. Bodies that must be
byte-identical across states are **copied, never retyped**. Scenarios whose
observed collection must differ from anything a spec produces (S73's hand-edited field, S74's
stock note type, S14's parked orphan) perturb the synthesized cards in `WorldDeck.scenarios`,
and only those.
