package obsidiananki.deck

import obsidiananki.plan.MoveFinding

/** THE POLICY SEAM — the one thing a "world" may change.
  *
  * ══ WHAT A WORLD IS ══
  *
  * The scenario deck (`deck/WorldDeck.scala`) runs every scenario through the REAL extraction
  * pipeline and the REAL move-evidence survey (`plan/MoveEvidence.survey`, branch `move-build`),
  * and then hands each finding to a [[MovePolicy]]. A world is one implementation of that trait,
  * plus its own tests at this seam — nothing else. Worlds exist so the user can FEEL how
  * different answers to the open questions (the subject/wording discriminator, the retroactive
  * default, below-floor report richness…) read across the whole deck, transcript against
  * transcript.
  *
  * ══ WHAT A WORLD MAY NOT DO — hard constraints, enforced where they can be ══
  *
  *   - It may not touch identity derivation. Identity stays a pure function of the vault
  *     (`oas-4ti`, ruled 2026-08-29); a policy only decides what is DONE with findings the
  *     mechanism produced. The runner never lets a policy near a key.
  *   - It may not introduce thresholds or scores. Exact bytes or nothing — the mechanism itself
  *     is frozen, and a policy receives findings, not field values to re-compare.
  *   - It may not mint a reassignment from any finding other than one it received:
  *     [[PolicyDecision.ApplyReassign]] carries the [[MoveFinding.Corroborated]] that licenses
  *     it, and the runner refuses (hard error) a decision whose carried evidence is not the very
  *     finding the policy was asked about. A `Corroborated` is the only case that CAN be carried,
  *     so no other finding shape can be widened into an application — the same construction
  *     `SyncAction.Reassign` uses.
  */
enum PolicyDecision:

  /** Apply the reassignment the evidence licenses: the stranded Anki note is given the key the
    * vault now produces, review history intact. Only a [[MoveFinding.Corroborated]] can license
    * one, and it travels with the decision so the runner can check it is the finding decided on.
    */
  case ApplyReassign(evidence: MoveFinding.Corroborated, text: String)

  /** The built behaviour for everything else: the orphan parks (flag + suspend), the report says
    * why, and a person decides.
    */
  case ParkAndReport(text: String)

  /** A vocabulary a world may add: refuse the event outright, naming why. The baseline never
    * uses it.
    */
  case Refuse(text: String)

  /** A vocabulary a world may add: hold the pairing for a human review queue. The baseline never
    * uses it.
    */
  case QueueForReview(text: String)

  def label: String = this match
    case ApplyReassign(_, _) => "apply-reassign"
    case ParkAndReport(_)    => "park-and-report"
    case Refuse(_)           => "refuse"
    case QueueForReview(_)   => "queue-for-review"

  def message: String = this match
    case ApplyReassign(_, m) => m
    case ParkAndReport(m)    => m
    case Refuse(m)           => m
    case QueueForReview(m)   => m

/** One world's answer to "what is done with a finding, and what does the run say about it". */
trait MovePolicy:

  /** Printed at the top of the world's transcript. */
  def name: String

  /** The decision and the exact user-facing message for ONE finding. Total over the sum: a
    * policy that has no opinion about a case still has to say what happens to it.
    */
  def decide(finding: MoveFinding): PolicyDecision

/** THE PLACEHOLDER WORLD: exactly what the `move-build` Planner does today.
  *
  * `Corroborated` auto-applies (Marc's 2026-09-05 ruling — risk asymmetry: a wrong orphan is a
  * certain loss, a wrong reassignment a bounded one the report reveals); every other finding is
  * reported and never acted on.
  */
object BaselinePolicy extends MovePolicy:

  val name: String = "baseline — the move-build behaviour as shipped"

  def decide(finding: MoveFinding): PolicyDecision = finding match
    case c: MoveFinding.Corroborated =>
      PolicyDecision.ApplyReassign(
        c,
        s"reassigned, keeping its review history — ${c.describe}",
      )
    case other =>
      PolicyDecision.ParkAndReport(other.describe)
