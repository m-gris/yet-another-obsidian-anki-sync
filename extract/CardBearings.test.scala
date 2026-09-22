package obsidiananki.extract

import obsidiananki.model.VaultTag

/** THE PROPERTY THIS WHOLE DESIGN EXISTS FOR: the breadcrumb and the subjects subtract the SAME
  * thing, because they are built from one argument at one place.
  *
  * `CardContext` and `CardTopics` each have their own tests, which pin what each does in
  * isolation. This file pins the only claim neither of them can make alone — that the two AGREE
  * about what the card already shows.
  *
  * ==Why that claim needs a test of its own==
  *
  * Because the failure it guards against does not look like a bug. Built from two separately
  * chosen vectors, the two would agree on the day they were written and drift later, and the
  * symptom would be a breadcrumb correctly hiding the answer beside a subject line printing it —
  * on some card kinds and not others, found by an author mid-review rather than by a suite.
  *
  * An earlier design joined the subjects to the card at the point the Anki note is assembled,
  * which would have forced the card to RE-DERIVE what it displays. It was rejected on this
  * ground rather than on cost.
  */
class CardBearingsTest extends munit.FunSuite:

  private def tags(raw: String*): Vector[VaultTag] = raw.toVector.map(VaultTag.read)

  /** THE CASE THAT MOTIVATES THE TYPE. One word is in the note's location AND in its tags AND on
    * the card's face — a note filed under `Surjection`, tagged `Surjection`, whose card asks
    * about the concept `Surjection`. Printing it either way would print the answer above the
    * question.
    */
  test("a term the card shows is absent from BOTH halves, not just the breadcrumb") {
    val bearings = CardBearings.of(
      location = Vector("Maths", "Surjection", "Definition"),
      tags = tags("Surjection", "CS"),
      shownOnCard = Vector("Surjection", "Definition"),
    )
    assert(
      !bearings.breadcrumb.contains("Surjection"),
      s"the breadcrumb printed the answer: '${bearings.breadcrumb}'",
    )
    assert(
      !bearings.topics.contains("Surjection"),
      s"the subjects printed the answer: '${bearings.topics}'",
    )
    // AND THE REST SURVIVES, so the assertions above cannot pass by emptying everything.
    assertEquals(bearings.breadcrumb, "Maths")
    assertEquals(bearings.topics, """<span class="topic">CS</span>""")
  }

  /** The same agreement under the LOOSER comparison both halves share: a tag hyphenated and a
    * heading spaced are one word, and a leak on that difference is exactly the kind that would
    * go unnoticed.
    */
  test("the two halves agree under case, spacing and hyphenation too") {
    val bearings = CardBearings.of(
      location = Vector("CS", "Type Theory", "Definition"),
      tags = tags("type-theory", "PLT"),
      shownOnCard = Vector("Type theory"),
    )
    assert(!bearings.breadcrumb.toLowerCase.contains("type"), bearings.breadcrumb)
    assert(!bearings.topics.toLowerCase.contains("type-theory"), bearings.topics)
    assertEquals(bearings.topics, """<span class="topic">PLT</span>""")
  }

  /** Both may be empty at once, and that is a real state rather than a failure: a root note with
    * no tags whose card shows its only heading. The note types guard each field separately, so
    * nothing is emitted.
    */
  test("a card with nothing above it and nothing tagging it gets two empty halves") {
    val bearings = CardBearings.of(Vector("Bijection", "Definition"), tags(), Vector("Bijection", "Definition"))
    assertEquals(bearings.breadcrumb, "")
    assertEquals(bearings.topics, "")
  }

  /** THE CASE THE FEATURE WAS BUILT FOR — a root note whose breadcrumb empties completely, where
    * the subjects are the only context the card has left.
    */
  test("a root note whose breadcrumb empties still says what the card is about") {
    val bearings = CardBearings.of(
      location = Vector("Bijection", "Definition"),
      tags = tags("math", "CS"),
      shownOnCard = Vector("Bijection", "Definition"),
    )
    assertEquals(bearings.breadcrumb, "")
    assertEquals(
      bearings.topics,
      """<span class="topic">math</span><span class="topic">CS</span>""",
    )
  }
