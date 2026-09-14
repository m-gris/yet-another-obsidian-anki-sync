package obsidiananki.plan

/** A [[Ledger]] FOR A SUITE THAT IS NOT ASKING ABOUT THE LEDGER.
  *
  * WHY THIS IS A NAME RATHER THAN A DEFAULT ON THE PARAMETER, which is the argument
  * [[HandBuiltCensus]] makes next door and for the same reason. `Executor.run` DEMANDS a ledger
  * because in production every automatic move of review history must be recorded (Marc's ruling,
  * 2026-09-12), and a default would let a real call site acquire "record nothing" by omission — the
  * one failure mode that ruling names. `Plan.parked`'s docstring makes the general case: a default
  * is how a construction site comes to report zero over a collection holding dozens. So every test
  * says what it is passing, in a word that says it.
  *
  * WHAT PASSING IT MEANS, STATED SO A PASS IS NOT MISREAD: the suite's assertions are about the
  * collection, and this accepts the moves without looking at them. A suite asserting that a move WAS
  * recorded, or that it was recorded before the first write, must not reach for this — against it,
  * both of those assertions are vacuous. `plan/Ledger.test.scala` holds the fakes that observe.
  *
  * IT LIVES IN A `.test.scala` FILE ON PURPOSE. A ledger that discards everything is exactly what
  * must not be reachable from production code, so it is compiled only into the test configuration.
  */
object RecordedNowhere:

  /** `Applicative` RATHER THAN A PURE VALUE PASSED IN, so a call site names the fake and nothing
    * else. Over a dozen suites pass this; each having to spell out its own `Right(())` or
    * `EitherT.pure(())` would be a dozen copies of one uninteresting fact.
    */
  def ledger[F[_]](using F: cats.Applicative[F]): Ledger[F] = new Ledger[F]:
    def record(moves: Vector[HistoryMove]): F[Unit] = F.unit
