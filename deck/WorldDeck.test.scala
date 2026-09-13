package obsidiananki.deck

import obsidiananki.TestSources
import obsidiananki.plan.{Agreement, MoveFinding, RelabelDoubt, SubjectSurvival}

/** THE DECK'S OWN TESTS — every scenario's finding pinned, and the seam's contract enforced.
  *
  * ==What is asserted, and what deliberately is not==
  *
  * A **[SETTLED]** scenario asserts the finding, its grade, AND the baseline policy's action —
  * that is the behaviour some ruling or the card model already fixes. Where a ruling says the
  * CURRENT mechanism is wrong, the test asserts the defective current output and carries the
  * ruling in its NAME, so a red test after the fix points straight at the expectation that must
  * flip. **Only S17 (the whole-plan refusal over a duplicate key, R5) is left in that state**;
  * S24's and S33's were discharged by the gate of 2026-09-12 and their names no longer claim the
  * mechanism is wrong.
  *
  * An **[OPEN]** scenario asserts only the mechanism's output — the finding and its grade —
  * never a policy verdict. What is done with such a finding is exactly the question the worlds
  * exist to vary, and a policy assertion here would close it in the wrong file. A world's own
  * tests, at the [[MovePolicy]] seam, are where those verdicts belong.
  *
  * **[SETTLED-RULING D1…D5]** marks a scenario the rulings of 2026-09-12
  * (`docs/design/IDENTITY-DECISION-SHEET.md`) closed: it names the decision, and it asserts the
  * policy verdict, because that verdict is no longer a world's to vary. Fifteen scenarios
  * graduated from [OPEN] on that date; the decision sheet is the authority, and the test name is
  * the pointer back to it.
  *
  * ==Why one shared run per scenario==
  *
  * Every test reads from one lazily-computed run of the whole deck under [[BaselinePolicy]].
  * The deck is deterministic by construction (see [[WorldDeck]]'s docstring), and the
  * determinism test below is what pins that claim rather than assuming it.
  */
class WorldDeckTest extends munit.FunSuite:

  private lazy val fixtures =
    TestSources.repoRoot(getClass).resolve("deck").resolve("fixtures")

  private lazy val runs: Map[String, WorldDeck.ScenarioRun] =
    WorldDeck.scenarios
      .map(sc => sc.id -> WorldDeck.runScenario(fixtures, sc, BaselinePolicy))
      .toMap

  // ------------------------------------------------------------------ helpers ----

  private def scenarioRun(id: String): WorldDeck.ScenarioRun =
    runs.getOrElse(id, fail(s"no scenario '$id' in the deck"))

  private def lastStep(id: String): WorldDeck.StepResult = scenarioRun(id).steps.last

  /** One finding as a comparable label: the case, and for a corroboration its grade. */
  private def shape(f: MoveFinding): String = f match
    case c: MoveFinding.Corroborated     => s"corroborated/${c.agreement}"
    case _: MoveFinding.Ambiguous        => "ambiguous"
    case _: MoveFinding.Contested        => "contested"
    case _: MoveFinding.Unaccounted      => "unaccounted"
    case _: MoveFinding.Reparented       => "reparented"
    case r: MoveFinding.RelabelUnvouched => s"relabel-unvouched/${doubt(r.cause)}"
    case _: MoveFinding.Unexplained      => "unexplained"
    case _: MoveFinding.Incomparable     => "incomparable"

  /** WHICH DOUBT, IN THE LABEL, because the two are different claims about the run: one says the
    * vault moved two things at once, the other says this run could not look. A scenario asserting
    * only "relabel-unvouched" would pass on either.
    */
  private def doubt(cause: RelabelDoubt): String = cause match
    case RelabelDoubt.ClusterMoved         => "cluster-moved"
    case RelabelDoubt.CensusUnavailable(_) => "census-unavailable"

  private def shapes(step: WorldDeck.StepResult): Vector[String] = step.findings.map(shape)

  private def decisionLabels(step: WorldDeck.StepResult): Vector[String] =
    step.decisions.map(_._2.label)

  private val reassign = s"corroborated/${Agreement.PlaceAndSubstance}"

  /** The evidence-free classifications of a step, as their leading words ("create", "flag"…). */
  private def diffWords(step: WorldDeck.StepResult): Vector[String] =
    step.diff.map(_.render.trim.takeWhile(!_.isWhitespace))

  // ── kind 1: plain heading cards ─────────────────────────────────────────────────

  test("S01 [SETTLED R1+R4] reword in place: Corroborated·PlaceAndSubstance, reassigned"):
    assertEquals(shapes(lastStep("S01")), Vector(s"corroborated/${Agreement.PlaceAndSubstance}"))
    assertEquals(decisionLabels(lastStep("S01")), Vector("apply-reassign"))

  test("S02 [SETTLED-RULING D1] subject swap on a plain heading: byte-indistinguishable from S01, and it follows"):
    assertEquals(shapes(lastStep("S02")), Vector(s"corroborated/${Agreement.PlaceAndSubstance}"))
    // THE deck's central exhibit: the mechanism cannot tell S01 from S02.
    assertEquals(shapes(lastStep("S02")), shapes(lastStep("S01")))
    // RULED 2026-09-12, Decision 1: a plain card's own heading changing in place is a rewording
    // and history follows — "this is not a cdd card... the odds are that it should follow
    // silently". The mechanism's blindness here is priced rather than paid for.
    assertEquals(decisionLabels(lastStep("S02")), Vector("apply-reassign"))

  test("S03 [SETTLED-MODEL] ancestor reworded: Corroborated·NameAndSubstance, reassigned"):
    assertEquals(shapes(lastStep("S03")), Vector(s"corroborated/${Agreement.NameAndSubstance}"))
    assertEquals(decisionLabels(lastStep("S03")), Vector("apply-reassign"))

  test("S04 [SETTLED-RULING] re-parent in the same note: Corroborated·NameAndSubstance, reassigned"):
    assertEquals(shapes(lastStep("S04")), Vector(s"corroborated/${Agreement.NameAndSubstance}"))
    assertEquals(decisionLabels(lastStep("S04")), Vector("apply-reassign"))

  test("S05 [SETTLED-RULING] verbatim move to another note: Corroborated·Total, reassigned"):
    assertEquals(shapes(lastStep("S05")), Vector(s"corroborated/${Agreement.Total}"))
    assertEquals(decisionLabels(lastStep("S05")), Vector("apply-reassign"))

  test("S06 [SETTLED-MODEL] heading level change is a non-event"):
    assertEquals(shapes(lastStep("S06")), Vector.empty)
    assertEquals(diffWords(lastStep("S06")), Vector("non-event"))

  test("S07 [SETTLED-MODEL] body edit in place is an Update, no finding"):
    assertEquals(shapes(lastStep("S07")), Vector.empty)
    assertEquals(diffWords(lastStep("S07")), Vector("update"))

  test("S08 [SETTLED action / OPEN report richness] move+edit in one commit: Unexplained, parked"):
    assertEquals(shapes(lastStep("S08")), Vector("unexplained"))
    assertEquals(decisionLabels(lastStep("S08")), Vector("park-and-report"))

  test("S09 [SETTLED-RULING D1] reword AND re-parent a plain heading: SubstanceAlone, and it follows"):
    assertEquals(shapes(lastStep("S09")), Vector(s"corroborated/${Agreement.SubstanceAlone}"))
    // THE GRADE THE SUBJECT GATE DOES NOT TOUCH. A two-field card shows one name field, so its
    // changed last segment is a rewording and not a subject change — the gate reads the name
    // window MINUS its last segment, which is empty here. Decision 1 rules this follows.
    assertEquals(decisionLabels(lastStep("S09")), Vector("apply-reassign"))

  test("S10 [SETTLED-MODEL] bolding a heading word re-renders the field at the same key: Update"):
    assertEquals(shapes(lastStep("S10")), Vector.empty)
    assertEquals(diffWords(lastStep("S10")), Vector("update"))

  test("S11 [SETTLED-RULING D4] markup change AND re-parent: markup is rendering, so it follows"):
    // FLIPPED BY DECISION 4 OF 2026-09-12, which overruled the mechanism's deliberate refusal here
    // — its docstring named "the author re-bolded the heading" as the blocking example. In Marc's
    // words: "same front, same back... same card". The key segment already agreed, so the pairing
    // is now corroborated, and the reassignment writes the vault's fields — which is how the Anki
    // face comes to show the new markup.
    assertEquals(shapes(lastStep("S11")), Vector(s"corroborated/${Agreement.NameAndSubstance}"))
    assertEquals(decisionLabels(lastStep("S11")), Vector("apply-reassign"))

  test("S12 [SETTLED-RULING] deletion: Unexplained, flagged and suspended, never deleted"):
    assertEquals(shapes(lastStep("S12")), Vector("unexplained"))
    assertEquals(decisionLabels(lastStep("S12")), Vector("park-and-report"))
    assert(diffWords(lastStep("S12")).contains("flag"))

  test("S13 [SETTLED-MODEL] delete then restore verbatim: run 2 unflags, history intact"):
    val steps = scenarioRun("S13").steps
    assertEquals(shapes(steps(0)), Vector("unexplained"))
    assertEquals(shapes(steps(1)), Vector.empty)
    assert(diffWords(steps(1)).contains("unflag"))

  test("S14 [SETTLED-RULING D5+D1] a PARKED orphan re-enters the survey, corroborates and is applied"):
    val steps = scenarioRun("S14").steps
    assertEquals(shapes(steps(0)), Vector("unexplained"))
    // RATIFIED 2026-09-12, Decision 5: the builder's elected retroactive-INCLUSIVE default
    // (plan/Planner.scala, the `isFlaggedOrphan || canInferOrphans` filter) is now the ruled
    // answer — a parked orphan participates in every later survey for as long as it lives, which
    // is until `prune` removes it. Decision 1 then rules run 2's reworded recreation a follow.
    assertEquals(shapes(steps(1)), Vector(s"corroborated/${Agreement.PlaceAndSubstance}"))
    assertEquals(decisionLabels(steps(1)), Vector("apply-reassign"))
    assert(diffWords(steps(1)).contains("parked"))

  test("S15 [SETTLED-MODEL] split keeping the heading: Update + Create, no finding"):
    assertEquals(shapes(lastStep("S15")), Vector.empty)
    assertEquals(diffWords(lastStep("S15")).sorted, Vector("create", "update"))

  test("S15b [OPEN history ownership] split with both halves reworded: one Unexplained, two Creates"):
    assertEquals(shapes(lastStep("S15b")), Vector("unexplained"))
    assertEquals(diffWords(lastStep("S15b")).count(_ == "create"), 2)

  test("S16 [OPEN] merge of two differing sections: Unexplained twice"):
    assertEquals(shapes(lastStep("S16")), Vector("unexplained", "unexplained"))

  test("S16b [SETTLED mechanism / OPEN outcome] merge of byte-identical sections: Contested twice"):
    assertEquals(shapes(lastStep("S16b")), Vector("contested", "contested"))

  test("S17 [RULED R5: refuse only the cards involved — the CURRENT mechanism wrongly refuses the WHOLE plan]"):
    val step = lastStep("S17")
    assert(step.refused)
    assertEquals(step.findings, Vector.empty)
    assert(step.diff.exists(_.render.contains("PLAN REFUSED")))

  test("S18 [SETTLED-MODEL] retag 2way→1way in place: Retype, deferred, no finding"):
    assertEquals(shapes(lastStep("S18")), Vector.empty)
    assertEquals(diffWords(lastStep("S18")), Vector("retype"))

  test("S19 [SETTLED-MODEL] retag AND re-parent: note types differ, comparison refused → Unexplained"):
    assertEquals(shapes(lastStep("S19")), Vector("unexplained"))

  // ── kind 2: concept-descriptor heading cards ────────────────────────────────────

  test("S20 [SETTLED-RULING D3] descriptor slot reworded: PlaceAndSubstance, and it follows"):
    assertEquals(shapes(lastStep("S20")), Vector(reassign))
    assertEquals(decisionLabels(lastStep("S20")), Vector("apply-reassign"))

  test("S21 [SETTLED-RULING D3] descriptor slot REPLACED: indistinguishable from S20, and it follows"):
    assertEquals(shapes(lastStep("S21")), shapes(lastStep("S20")))
    // RULED 2026-09-12, Decision 3: the concept — the card's subject — is untouched, and what
    // changed is the facet's label. "This is actually the same card, just reworded." The subject
    // gate therefore does not fire, whether or not `# Kafka` is still standing: it reads the name
    // window minus its last segment, and only the last segment moved.
    assertEquals(decisionLabels(lastStep("S21")), Vector("apply-reassign"))

  test("S22 [SETTLED-RULING D2] concept reworded in place: both cards follow, the old path surviving nowhere"):
    // The label on this test used to read "(D5: always this grade)", which was a slip — D5 is the
    // retroactive ruling. The ruling that governs here is Decision 2.
    assertEquals(shapes(lastStep("S22")), Vector(reassign, reassign))
    assertEquals(decisionLabels(lastStep("S22")), Vector("apply-reassign", "apply-reassign"))

  test("S23 [SETTLED-RULING D2] concept subject SWAPPED: indistinguishable from S22, and it follows too"):
    // RULED 2026-09-12, Decision 2, revising R3: with every descriptor and description unchanged
    // and the cluster in place, the edit is read as a relabel — "Least Element" becoming "Bottom".
    // A genuine replacement would change the descriptions and fail the floor by itself, so a
    // replacement that keeps them byte-identical is priced as not a real case.
    //
    // THE GATE IS CONSULTED HERE AND LETS IT THROUGH: the subject moved, and `# Kafka` is a path
    // the census finds nowhere in the note. Contrast S24, where it is still standing.
    assertEquals(shapes(lastStep("S23")), shapes(lastStep("S22")))
    assertEquals(decisionLabels(lastStep("S23")), Vector("apply-reassign", "apply-reassign"))

  test("S24 [SETTLED-RULING R2] a descriptor re-parented under a surviving concept is a DIFFERENT card"):
    // FLIPPED FROM apply TO park. The concept is constitutive — a concept-descriptor card asserts
    // a three-way relation and its parent is one of the three terms — so a descriptor moved under
    // `# NATS` while `# Kafka` goes on existing is a new card there and a deleted card here.
    //
    // WHAT MAKES THE DIFFERENCE VISIBLE TO THE SEAM: the node census finds `kafka` still in the
    // note (it holds `## Cost`), which is exactly the fact S23's vault does not have. The evidence
    // is typed apart rather than filtered, so `Reparented` has no reassignment to mint.
    assertEquals(shapes(lastStep("S24")), Vector("reparented"))
    assertEquals(decisionLabels(lastStep("S24")), Vector("park-and-report"))
    assert(diffWords(lastStep("S24")).contains("flag"), diffWords(lastStep("S24")).toString)
    assert(diffWords(lastStep("S24")).contains("create"), diffWords(lastStep("S24")).toString)

  test("S24A [SETTLED-RULING 2026-09-13] the concept moved to ANOTHER NOTE: the descriptor still parks"):
    // THE CASE THE FINAL ADVERSARIAL REVIEW CONSTRUCTED, and the reason the survival check had to
    // grow a second witness. `# Kafka` leaves Messaging.md for Queues.md with `## Cost` under it
    // while `## Definition` re-parents under `# NATS`. A check that asks only "is `kafka` a node of
    // THIS note" answers no — truthfully — and the run then moves Cost's history onto
    // `kafka / cost` in Queues.md WHILE moving Definition's history on the grounds that Kafka
    // vanished. One run, both halves of a contradiction.
    //
    // RULED 2026-09-13: the run's own corroboration is the witness, so Kafka survives and the
    // re-parent parks (R2). Cost's own pairing is untouched by the ruling and still applies.
    assertEquals(shapes(lastStep("S24A")), Vector(s"corroborated/${Agreement.Total}", "reparented"))
    assertEquals(decisionLabels(lastStep("S24A")), Vector("apply-reassign", "park-and-report"))

  test("S24B [SETTLED-RULING 2026-09-13] the concept RE-NESTED in the same note: the descriptor still parks"):
    // `# Kafka` becomes `## Kafka` under a new `# Archive`, so the node `kafka` is genuinely absent
    // from the note — a node is addressed from the note's root, and this one is now
    // `archive / kafka`. The per-note census therefore answers "gone" here too.
    //
    // THE RULING'S COHERENCE ARGUMENT IS WHAT DECIDES THIS ONE: the corroboration onto
    // `archive / kafka / cost` is this run putting a card back under Kafka, so Kafka goes on
    // existing. Nothing about the re-nesting is a rename of Kafka, and a run may not both keep a
    // concept and declare it gone.
    assertEquals(
      shapes(lastStep("S24B")),
      Vector(s"corroborated/${Agreement.NameAndSubstance}", "reparented"),
    )
    assertEquals(decisionLabels(lastStep("S24B")), Vector("apply-reassign", "park-and-report"))

  test("S24C [SETTLED-RULING 2026-09-13] the concept keeping only PROSE parks it, as it already did"):
    // THE WITNESS THE RULING ADDS TO RATHER THAN REPLACES. `# Kafka` stays in the note holding
    // nothing but prose, and `## Cost` is deleted — so no card and no corroboration can show
    // Kafka, and the per-note census is the only thing that can. This scenario passed before the
    // ruling and must go on passing after it; it is what stops the second witness being built as a
    // replacement for the first.
    assertEquals(shapes(lastStep("S24C")), Vector("unexplained", "reparented"))
    assertEquals(decisionLabels(lastStep("S24C")), Vector("park-and-report", "park-and-report"))

  test("S24D [SETTLED-RULING 2026-09-13] the concept left a run EARLIER: the descriptor still parks"):
    // S24A's edit, split across a sync boundary — which is all it took to get past both witnesses
    // S24A and S24B installed. Run one moves `# Kafka` and `## Cost` to Queues.md and pairs Cost
    // there; run two relabels the `# Kafka` heading that `## Definition` still hangs off. By then the
    // corroboration onto Kafka belongs to a finished run, and the node was never in this note, so
    // BOTH of those witnesses answer "gone" honestly and the descriptor's history would follow a
    // subject change — which R2 forbids.
    //
    // WHAT ANSWERS IN RUN TWO is the card run one created: a live `kafka / cost` in the collection,
    // declaring that the concept exists. That is the third witness, resolved 2026-09-13 as entailed
    // by the standing rulings rather than newly ruled.
    val steps = scenarioRun("S24D").steps
    // Run one is S24A's own Cost pairing and nothing else: the same path in a different note.
    assertEquals(shapes(steps(0)), Vector(s"corroborated/${Agreement.Total}"))
    assertEquals(decisionLabels(steps(0)), Vector("apply-reassign"))
    // Run two. The first finding is the note run one left parked, whose key the vault no longer
    // produces at all; the second is the re-parented descriptor, and it must NOT be corroborated.
    assertEquals(shapes(steps(1)), Vector("unexplained", "reparented"))
    assertEquals(decisionLabels(steps(1)), Vector("park-and-report", "park-and-report"))
    // AND THE REPORT MUST NAME THE CARD IT READ, not a note the reader would open and find empty:
    // Messaging.md holds no `# Kafka` by run two, so "still in the vault" would read as a lie.
    val witnessed = steps(1).findings.collectFirst { case r: MoveFinding.Reparented => r.survival }
    assertEquals(
      witnessed.map {
        case SubjectSurvival.StillInTheCollection(concept, card) => concept -> card.path.render
        case other => fail(s"the live collection must be the witness here, not: ${other.describe}")
      },
      Some(Vector("kafka") -> "kafka / cost"),
    )

  test("S25 [SETTLED-MODEL+R2+R4] whole subtree moved cross-note: Corroborated·Total ×2, reassigned"):
    assertEquals(
      shapes(lastStep("S25")),
      Vector(s"corroborated/${Agreement.Total}", s"corroborated/${Agreement.Total}"),
    )
    assertEquals(decisionLabels(lastStep("S25")), Vector("apply-reassign", "apply-reassign"))

  test("S26 [SETTLED mechanics / OPEN visibility] file rename of an ancestorless cdd: silent subject drift, NO finding"):
    assertEquals(shapes(lastStep("S26")), Vector.empty)
    assertEquals(diffWords(lastStep("S26")), Vector("update"))

  test("S27 [SETTLED-RULING] file rename AND id change: Unaccounted — the Concept has no key projection"):
    assertEquals(shapes(lastStep("S27")), Vector("unaccounted"))
    assertEquals(decisionLabels(lastStep("S27")), Vector("park-and-report"))

  test("S28 [SETTLED-MODEL] retag cdd/2way→cdd/3way in place: Update, no finding"):
    assertEquals(shapes(lastStep("S28")), Vector.empty)

  test("S28b [SETTLED-MODEL] cdd retag AND re-parent: the ThreeWay Setting flips, floor fails → Unexplained"):
    assertEquals(shapes(lastStep("S28b")), Vector("unexplained"))

  // ── kind 3: table pair cards ────────────────────────────────────────────────────

  test("S29 [SETTLED-MODEL] row and column swaps are non-events"):
    assertEquals(shapes(lastStep("S29")), Vector.empty)
    assertEquals(diffWords(lastStep("S29")).distinct, Vector("non-event"))

  test("S30 [SETTLED-MODEL] cell value edit: Update in place"):
    assertEquals(shapes(lastStep("S30")), Vector.empty)
    assert(diffWords(lastStep("S30")).contains("update"))

  test("S31 [SETTLED-RULING D3] column header renamed, values distinct: both rows follow"):
    // A column header is the DESCRIPTOR position of every pair card in that column, so this is
    // Decision 3 one card kind over. The row concept — the subject — is untouched.
    assertEquals(shapes(lastStep("S31")), Vector(reassign, reassign))
    assertEquals(decisionLabels(lastStep("S31")), Vector("apply-reassign", "apply-reassign"))

  test("S32 [SETTLED mechanism D6] column header renamed, values identical: Ambiguous, report only"):
    assertEquals(shapes(lastStep("S32")), Vector("ambiguous", "ambiguous"))
    assertEquals(decisionLabels(lastStep("S32")), Vector("park-and-report", "park-and-report"))

  test("S33 [SETTLED-RULING D2] a row's subject replaced: the pair cards follow, the row card strands"):
    // THE NAME OF THIS TEST USED TO CLAIM THE GATE WAS WRONG HERE. Decision 2 of 2026-09-12
    // revised R3 and made this the ruled outcome: a table row is the same cluster shape one card
    // kind over — Marc confirmed no distinction — so with every value byte-identical and the row
    // still under the same heading, the pair cards follow. The gate is consulted and lets them
    // through because the node `cost / benefit / queue` is gone from the vault.
    //
    // The row card strands while both pair cards corroborate — the pair/row asymmetry inside ONE
    // run, and the cost that is deliberately left visible rather than ruled away.
    assertEquals(shapes(lastStep("S33")).sorted, Vector(reassign, reassign, "unexplained").sorted)
    assertEquals(
      decisionLabels(lastStep("S33")).sorted,
      Vector("apply-reassign", "apply-reassign", "park-and-report").sorted,
    )

  test("S34 [SETTLED-RULING D2] row concept typo fix: indistinguishable from S33, and it follows too"):
    assertEquals(shapes(lastStep("S34")).sorted, shapes(lastStep("S33")).sorted)
    assertEquals(decisionLabels(lastStep("S34")).sorted, decisionLabels(lastStep("S33")).sorted)

  test("S35 [SETTLED-MODEL] adding a column adds Creates and disturbs nothing"):
    assertEquals(shapes(lastStep("S35")), Vector.empty)
    assert(diffWords(lastStep("S35")).contains("create"))

  test("S36 [SETTLED-RULING] deleting a column: Unexplained per vanished key, flagged"):
    assertEquals(shapes(lastStep("S36")), Vector.fill(4)("unexplained"))

  test("S36b [SETTLED-RULING] blanking one value cell retires that pair card and the row card"):
    assertEquals(shapes(lastStep("S36b")), Vector.fill(2)("unexplained"))

  test("S37 [SETTLED-MODEL+R4] table heading reworded: NameAndSubstance ×6, reassigned"):
    assertEquals(shapes(lastStep("S37")), Vector.fill(6)(s"corroborated/${Agreement.NameAndSubstance}"))
    assertEquals(decisionLabels(lastStep("S37")), Vector.fill(6)("apply-reassign"))

  test("S37b [SETTLED-MODEL+R4] table section moved cross-note: Total ×6, reassigned"):
    assertEquals(shapes(lastStep("S37b")), Vector.fill(6)(s"corroborated/${Agreement.Total}"))

  test("S38 [SETTLED-MODEL] first header cell renamed: ConceptLabel is a Setting, shown never keyed → Update ×6"):
    assertEquals(shapes(lastStep("S38")), Vector.empty)
    assertEquals(diffWords(lastStep("S38")), Vector.fill(6)("update"))

  test("S38b [SETTLED-MODEL] first header renamed AND moved: the ConceptLabel Setting floor fails → Unexplained ×6"):
    assertEquals(shapes(lastStep("S38b")), Vector.fill(6)("unexplained"))

  test("S39 [SETTLED-MODEL] withdrawing cell cards (table/rows): deletion semantics for the withdrawn"):
    assertEquals(shapes(lastStep("S39")), Vector.fill(4)("unexplained"))

  test("S39b [SETTLED-MODEL] direction change alone (table/1way): Setting flip → Update"):
    assertEquals(shapes(lastStep("S39b")), Vector.empty)
    assert(diffWords(lastStep("S39b")).contains("update"))

  test("S40 [SETTLED-MODEL — the shelter CLOSED the spec's D10 blast radius] an embed in one cell shelters the whole table"):
    // The reconciliation predicted Unexplained ×N and a mass Flag, reading the stale comment
    // at extract/Extractor.scala's buildSpecs. On move-build, VaultAccounting.underAFailedSection
    // shelters every card keyed BENEATH the failed section key, so nothing is stranded and the
    // survey has nothing to say. The cost that remains: the whole table's cards go silent while
    // the section is broken.
    val step = lastStep("S40")
    assertEquals(shapes(step), Vector.empty)
    assertEquals(diffWords(step).count(_ == "sheltered"), 6)
    assertEquals(diffWords(step).count(_ == "flag"), 0)

  // ── kind 4: table row cards ─────────────────────────────────────────────────────

  test("S41 [SETTLED-MODEL] subject swap on an isolated row-only table: the row card strands alone"):
    // Own fixture, not shared with S33: the marker is #flashcard/table/rows, so the table mints
    // row cards only — no pair card shares this transcript block, unlike the harness anomaly
    // this scenario used to reproduce (IDENTITY-DECISION-SHEET.md, Appendix B). The row card's
    // Front and Back both render the row's subject cell, so a subject swap fails the comparison
    // floor on its own, isolated from any pair-card corroboration.
    assertEquals(shapes(lastStep("S41")), Vector("unexplained"))
    val rowFinding = lastStep("S41").findings.collectFirst {
      case u: MoveFinding.Unexplained => u.stranded.path.render
    }
    assertEquals(rowFinding, Some("cost / benefit / queue"))
    assertEquals(decisionLabels(lastStep("S41")), Vector("park-and-report"))
    assertEquals(diffWords(lastStep("S41")).count(_ == "create"), 1)
    assertEquals(diffWords(lastStep("S41")).count(_ == "non-event"), 1)

  test("S42 [OPEN] typo fix on an isolated row-only table: the same stranding as S41, unruled"):
    // History is lost on a wording fix, because for a row card the subject cell sits inside
    // Substance and the floor cannot be asked to excuse it — the pair/row asymmetry S33's
    // shared run showed at once (pair cards followed, the row card stranded), now visible from
    // the row side alone, with no pair card in the same block to contrast it against.
    assertEquals(shapes(lastStep("S42")), Vector("unexplained"))
    val rowFinding = lastStep("S42").findings.collectFirst {
      case u: MoveFinding.Unexplained => u.stranded.path.render
    }
    assertEquals(rowFinding, Some("cost / benefit / qeue"))
    assertEquals(diffWords(lastStep("S42")).count(_ == "create"), 1)

  test("S43 [SETTLED-MODEL] a value edit updates the pair card and the row card in place"):
    assertEquals(shapes(lastStep("S43")), Vector.empty)
    assertEquals(diffWords(lastStep("S43")).count(_ == "update"), 2)

  // ── kind 5: cloze section cards ─────────────────────────────────────────────────

  test("S44 [SETTLED-MODEL+R1+R4] cloze heading reworded: NameAndSubstance (the heading is pure filing), reassigned"):
    assertEquals(shapes(lastStep("S44")), Vector(s"corroborated/${Agreement.NameAndSubstance}"))
    assertEquals(decisionLabels(lastStep("S44")), Vector("apply-reassign"))

  test("S44b [SETTLED-MODEL] cloze section moved cross-note, path verbatim: Total, reassigned"):
    assertEquals(shapes(lastStep("S44b")), Vector(s"corroborated/${Agreement.Total}"))

  test("S45 [SETTLED-MODEL] prose edit around deletions: Update"):
    assertEquals(shapes(lastStep("S45")), Vector.empty)
    assert(diffWords(lastStep("S45")).contains("update"))

  test("S46 [SETTLED action / OPEN report richness] cloze move AND prose edit: Unexplained"):
    assertEquals(shapes(lastStep("S46")), Vector("unexplained"))

  test("S47 [SETTLED-MODEL — documented hazard] a new unlabelled highlight renumbers ordinals invisibly: Update"):
    assertEquals(shapes(lastStep("S47")), Vector.empty)
    assert(diffWords(lastStep("S47")).contains("update"))

  test("S48 [SETTLED-MODEL, R5-consistent] duplicate unlabelled highlight refuses THAT card alone, sheltered"):
    val step = lastStep("S48")
    assertEquals(shapes(step), Vector.empty)
    assert(diffWords(step).contains("refused"))
    assert(diffWords(step).contains("sheltered"))
    // The block card beside it is untouched:
    assert(diffWords(step).contains("non-event"))

  test("S49 [SETTLED mechanism / OPEN transition] marker off, block anchor on: kinds never pair → Unexplained + Create"):
    assertEquals(shapes(lastStep("S49")), Vector("unexplained"))
    assert(diffWords(lastStep("S49")).contains("create"))

  // ── kind 6: cloze block cards ───────────────────────────────────────────────────

  test("S50 [SETTLED-MODEL] block moved under a different heading, same note: the anchor holds, Update only"):
    assertEquals(shapes(lastStep("S50")), Vector.empty)

  test("S51 [SETTLED-RULING R4] block moved cross-note with its anchor: Total, reassigned"):
    assertEquals(shapes(lastStep("S51")), Vector(s"corroborated/${Agreement.Total}"))
    assertEquals(decisionLabels(lastStep("S51")), Vector("apply-reassign"))

  test("S52 [SETTLED-MODEL] block anchor renamed: the id is transport, not content — reassigned"):
    assertEquals(shapes(lastStep("S52")), Vector(s"corroborated/${Agreement.NameAndSubstance}"))
    assertEquals(decisionLabels(lastStep("S52")), Vector("apply-reassign"))

  test("S53 [SETTLED-MODEL] anchor deleted, deletions kept: the refusal carries no key → Unexplained"):
    assertEquals(shapes(lastStep("S53")), Vector("unexplained"))
    assert(diffWords(lastStep("S53")).contains("refused"))

  test("S54 [OPEN transition] block absorbed into the cloze section: cross-kind → Unexplained"):
    assertEquals(shapes(lastStep("S54")), Vector("unexplained"))

  // ── kind 7: sequence cards ──────────────────────────────────────────────────────

  test("S55 [SETTLED-MODEL] items reordered: Update — one note, one schedule"):
    assertEquals(shapes(lastStep("S55")), Vector.empty)
    assertEquals(diffWords(lastStep("S55")), Vector("update"))

  test("S56 [SETTLED-RULING R1] title reworded in place: PlaceAndSubstance, reassigned"):
    assertEquals(shapes(lastStep("S56")), Vector(reassign))
    assertEquals(decisionLabels(lastStep("S56")), Vector("apply-reassign"))

  test("S56b [SETTLED-RULING D1] sequence title subject swapped: indistinguishable from S56, and it follows"):
    assertEquals(shapes(lastStep("S56b")), shapes(lastStep("S56")))
    // A sequence card shows ONE name field, so its title is its own name and not its subject —
    // the same shape as S02, one card kind over. Decision 1 rules it a follow.
    assertEquals(decisionLabels(lastStep("S56b")), Vector("apply-reassign"))

  test("S57 [SETTLED-MODEL] sequence source switched in place: same key, Update"):
    assertEquals(shapes(lastStep("S57")), Vector.empty)
    assertEquals(diffWords(lastStep("S57")), Vector("update"))

  test("S57b [SETTLED-MODEL] reveal order changed AND moved: the Reveal Setting floor fails → Unexplained"):
    assertEquals(shapes(lastStep("S57b")), Vector("unexplained"))

  // ── kind 8: whole-note cards ────────────────────────────────────────────────────

  test("S58 [SETTLED mechanics / OPEN visibility] file rename: the Front rewrites silently, NO finding"):
    assertEquals(shapes(lastStep("S58")), Vector.empty)
    assertEquals(diffWords(lastStep("S58")), Vector("update"))

  test("S59 [SETTLED-MODEL+R4] id change alone: Total (empty segment vectors agree), reassigned"):
    assertEquals(shapes(lastStep("S59")), Vector(s"corroborated/${Agreement.Total}"))
    assertEquals(decisionLabels(lastStep("S59")), Vector("apply-reassign"))

  test("S60 [SETTLED-RULING] id change AND file rename: Unaccounted — the Front diverges over an empty segment vector"):
    assertEquals(shapes(lastStep("S60")), Vector("unaccounted"))
    assertEquals(decisionLabels(lastStep("S60")), Vector("park-and-report"))

  test("S61 [SETTLED for the flag / OPEN transition] a first heading arrives: Note vs Headings never pair"):
    assertEquals(shapes(lastStep("S61")), Vector("unexplained"))
    assert(diffWords(lastStep("S61")).contains("create"))

  test("S62 [SETTLED-RULING] id deleted: the note is ineligible, its card strands"):
    assertEquals(shapes(lastStep("S62")), Vector("unexplained"))
    assert(diffWords(lastStep("S62")).contains("refused"))

  // ── kind 9: relation cards ──────────────────────────────────────────────────────

  test("S63 [SETTLED-MODEL] relation file rename: the Concept rewrites silently, NO finding"):
    assertEquals(shapes(lastStep("S63")), Vector.empty)
    assertEquals(diffWords(lastStep("S63")), Vector("update"))

  test("S64 [SETTLED-RULING] relation moved cross-note: Unaccounted — a genuine move and a coincidence are indistinguishable"):
    assertEquals(shapes(lastStep("S64")), Vector("unaccounted"))
    assertEquals(decisionLabels(lastStep("S64")), Vector("park-and-report"))

  test("S65 [SETTLED-RULING D3] predicate renamed: PlaceAndSubstance, and it follows"):
    // A relation card IS a concept-descriptor card — subject is the concept, predicate is the
    // descriptor (`extract/Edges.scala`) — so renaming the predicate is Decision 3 exactly. Its
    // path is a single frontmatter property, which is also why the subject gate cannot fire on it:
    // the name window is one segment long, so there is nothing above the name to have moved.
    assertEquals(shapes(lastStep("S65")), Vector(reassign))
    assertEquals(decisionLabels(lastStep("S65")), Vector("apply-reassign"))

  test("S66 [SETTLED-MODEL] property re-capitalised: the KEY is canonical and holds; the field re-renders → Update"):
    // The reconciliation table said non-event; the Descriptor field carries the author's own
    // casing, so the content hash moves while the key does not — the same shape as S10.
    assertEquals(shapes(lastStep("S66")), Vector.empty)
    assertEquals(diffWords(lastStep("S66")), Vector("update"))

  test("S67 [SETTLED-MODEL] a second value: values are fields, not key → Update"):
    assertEquals(shapes(lastStep("S67")), Vector.empty)
    assertEquals(diffWords(lastStep("S67")), Vector("update"))

  test("S68 [SETTLED-RULING] rule removed from Properties-to-Flashcards: the card strands"):
    assertEquals(shapes(lastStep("S68")), Vector("unexplained"))

  test("S69 [SETTLED-MODEL, R5-consistent] reverse collision refuses the involved cards only, by name"):
    val step = lastStep("S69")
    assertEquals(shapes(step), Vector.empty)
    assertEquals(diffWords(step).count(_ == "refused"), 2)
    assert(!step.refused, "the collision must not refuse the whole plan")

  test("S70 [SETTLED-RULING R4] basename coincidence: Corroborated·Total reassigns onto the named residual"):
    assertEquals(shapes(lastStep("S70")), Vector(s"corroborated/${Agreement.Total}"))
    assertEquals(decisionLabels(lastStep("S70")), Vector("apply-reassign"))

  test("S71 [SETTLED-MODEL] property re-expressed as a heading: the kind is identity, never pairs"):
    assertEquals(shapes(lastStep("S71")), Vector("unexplained"))
    assert(diffWords(lastStep("S71")).contains("create"))

  // ── cross-cutting ───────────────────────────────────────────────────────────────

  test("S72 [SETTLED-MODEL] folder move: keys intact, a deck move is a change inside one → Update"):
    assertEquals(shapes(lastStep("S72")), Vector.empty)
    assertEquals(diffWords(lastStep("S72")), Vector("update"))

  test("S73 [SETTLED-MODEL — stated limit] a hand-edited note fails the floor: Unexplained"):
    assertEquals(shapes(lastStep("S73")), Vector("unexplained"))

  test("S74 [SETTLED-MODEL] a legacy note type is Incomparable, never mistaken for 'nothing matched'"):
    assertEquals(shapes(lastStep("S74")), Vector("incomparable"))

  // ── the harness's own contract ──────────────────────────────────────────────────

  test("every scenario id from S01 to S74 is in the deck, in order, each variant in its parent's run"):
    // A CORE ID IS THREE CHARACTERS ('S24') AND A VARIANT IS A CORE ID PLUS A SUFFIX ('S15b',
    // 'S24A') — which is what the test asks, rather than asking for the suffix 'b' by name. It used
    // to: ten `b`-variants were all there was until the three cases of 2026-09-13 arrived, and a
    // test naming one spelling would have failed on scenarios it has no opinion about.
    val ids  = WorldDeck.scenarios.map(_.id)
    val core = (1 to 74).map(n => f"S$n%02d")
    assertEquals(ids.filter(_.length == 3), core.toVector)
    // and a variant sits inside its parent's run — directly after the parent, or after an earlier
    // variant of the same parent, so that 'S24, S24A, S24B, S24C' is in order and a stray is not:
    ids.zipWithIndex.filter((id, _) => id.length > 3).foreach { (variant, i) =>
      assertEquals(
        ids(i - 1).take(3),
        variant.take(3),
        s"$variant must sit in ${variant.take(3)}'s run",
      )
    }

  test("the transcript is deterministic: two runs render byte-identically"):
    val one = WorldDeck.transcript(fixtures, BaselinePolicy)
    val two = WorldDeck.transcript(fixtures, BaselinePolicy)
    assertEquals(one, two)

  test("conservation: every stranded note appears in exactly one finding"):
    runs.values.foreach { run =>
      run.steps.foreach { step =>
        val noted = step.findings.map(_.strandedNote)
        assertEquals(noted.distinct.size, noted.size, s"${run.scenario.id}: a note appears twice")
      }
    }

  test("the transcript renders every scenario with all five sections"):
    val rendered = WorldDeck.transcript(fixtures, BaselinePolicy)
    WorldDeck.scenarios.foreach { sc =>
      assert(rendered.contains(s"SCENARIO ${sc.id} — "), s"${sc.id} missing from the transcript")
    }
    Vector("EDIT", "DIFF", "EVIDENCE", "POLICY", "LEDGER").foreach { section =>
      assert(rendered.linesIterator.count(_ == section) >= WorldDeck.scenarios.size,
        s"section '$section' missing somewhere")
    }

  test("the seam refuses a reassignment minted from evidence the policy was not asked about"):
    // A rogue world that answers every finding with the FIRST corroboration it ever saw —
    // exactly the laundering the runner must reject.
    val stolen = lastStep("S01").findings.collectFirst { case c: MoveFinding.Corroborated => c }
      .getOrElse(fail("S01 must corroborate for this test to have a weapon"))
    object Rogue extends MovePolicy:
      val name = "rogue"
      def decide(finding: MoveFinding): PolicyDecision =
        PolicyDecision.ApplyReassign(stolen, "laundered")
    val s04 = WorldDeck.scenarios.find(_.id == "S04").getOrElse(fail("no S04"))
    val e = intercept[RuntimeException](WorldDeck.runScenario(fixtures, s04, Rogue))
    assert(e.getMessage.contains("may only be minted from the finding it answers"))

  test("a world adding Refuse / QueueForReview vocabulary runs the whole deck and its choices show"):
    object Cautious extends MovePolicy:
      val name = "cautious"
      def decide(finding: MoveFinding): PolicyDecision = finding match
        case c: MoveFinding.Corroborated => PolicyDecision.QueueForReview(s"queued — ${c.describe}")
        case other                       => PolicyDecision.ParkAndReport(other.describe)
    val rendered = WorldDeck.transcript(fixtures, Cautious)
    assert(rendered.contains("queue-for-review: queued — "))
    assert(!rendered.contains("apply-reassign"))

  test("vacuity guard: the discriminator pairs really are DIFFERENT edits, not one fixture twice"):
    // If a pair's fixtures were accidentally identical, 'byte-indistinguishable observables'
    // would be proven by nothing. The stranded/candidate paths must differ between the twins.
    def candidatePaths(id: String): Vector[String] =
      lastStep(id).findings.collect { case c: MoveFinding.Corroborated => c.candidate.path.render }
    assertNotEquals(candidatePaths("S01"), candidatePaths("S02"))
    assertNotEquals(candidatePaths("S20"), candidatePaths("S21"))
    assertNotEquals(candidatePaths("S22"), candidatePaths("S23"))
    assertNotEquals(candidatePaths("S56"), candidatePaths("S56b"))
    // S41/S42 strand rather than corroborate, so the discriminator is the stranded path, not
    // the candidate path — but the same guard applies: two genuinely different edits.
    def strandedPaths(id: String): Vector[String] =
      lastStep(id).findings.collect { case u: MoveFinding.Unexplained => u.stranded.path.render }
    assertNotEquals(strandedPaths("S41"), strandedPaths("S42"))
