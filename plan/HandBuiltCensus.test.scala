package obsidiananki.plan

/** THE NODE CENSUS A HAND-BUILT [[VaultScan]] IMPLIES, for a test that does not model a document.
  *
  * WHY THIS EXISTS AS A NAME RATHER THAN A DEFAULT ON THE PARAMETER, which is the argument
  * [[SectionChain]] makes one file over and for the same reason. `Planner.plan` and
  * `MoveEvidence.survey` take the census explicitly because in production it must come from the
  * same walk as the scan, and a default would let a call site fall silently back to an emptier
  * vault than the one being planned. So each test says what it is passing, in a word that says it.
  *
  * WHAT IT IS: the census of a vault nobody walked. A real one reads the note's own document —
  * every heading, including those that make no card, and every table row's subject cell, including
  * rows whose value cells are all empty; this one has only what the scan's own keys imply, which is
  * every node above a card the scan accounts for. It is a WEAKER census, never a wrong one: it can
  * say "this node is here" only where a card proves it.
  *
  * WHICH DIRECTION THAT ERRS IN, SO A READER KNOWS WHAT A PASS IS WORTH. Fewer nodes means fewer
  * surviving parents, so a subject change looks more like a relabel and less like a re-parent. A
  * test that wants the re-parent outcome must therefore build a census with the surviving node in
  * it, rather than reach for this.
  */
object HandBuiltCensus:

  def of(scan: VaultScan): NodeCensus = NodeCensus.of(scan, Map.empty)
