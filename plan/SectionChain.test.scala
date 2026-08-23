package obsidiananki.plan

/** The section chain a hand-built [[SourcedSpec]] carries in a test that is not about decks.
  *
  * WHY THIS EXISTS AS A NAME RATHER THAN A DEFAULT ON THE FIELD. `SourcedSpec.sectionTitles`
  * deliberately has no default value: a default would let a spec be built with a silently empty
  * chain, and the deck composed from it would be right by accident here and wrong in the field.
  * The planner tests genuinely do not model a document — they hand `Planner.plan` a fixed
  * `_ => deck` function and never reach deck composition at all — so the honest thing is to say
  * so at each construction site, in a word that says it.
  *
  * IT IS EMPTY, WHICH PRODUCTION NEVER IS. A real card always sits under at least the heading
  * that marked it. If a test ever starts caring what its cards' decks are, it must pass a real
  * chain rather than reach for this — the empty vector would compose to the bare root deck and
  * that would look like a pass.
  *
  * AN OBJECT RATHER THAN A TOP-LEVEL `val`, for a reason with nothing to do with design: a
  * top-level definition in a file named `SectionChain.test.scala` makes the compiler synthesise
  * a package named `SectionChain.test$package`, which it warns will be encoded on the classpath
  * and "can lead to undefined behaviour". This project builds with no warnings.
  */
object SectionChain:
  val NoSectionChain: Vector[String] = Vector.empty

  /** The counterpart for `recall`: what a hand-built spec asks the reviewer to produce, in a
    * test that is not about deck paths.
    *
    * EMPTY MEANS "ASKS FOR NOTHING A LOCATION NAMES", which is true of most card shapes — a
    * one-way card, a cloze, a sequence, a table card. So unlike `NoSectionChain` this is not a
    * sentinel standing in for a real value; it is a real and common value, and the planner
    * tests below genuinely have it.
    */
  val NoRecall: obsidiananki.model.RecallText = obsidiananki.model.RecallText.none
