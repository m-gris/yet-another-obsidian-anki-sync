package obsidiananki.extract

import obsidiananki.model.VaultTag

/** WHAT A CARD SHOWS FOR ITS SUBJECTS, and what it must not.
  *
  * The sibling of `CardContext.test.scala`. Where that file pins a PATH — ordered, joined by a
  * separator, truncated rather than filtered — this pins a SET: no separator, no order imposed,
  * and a member removed from the middle without disturbing its neighbours.
  */
class CardTopicsTest extends munit.FunSuite:

  /** Tags AS AN AUTHOR WROTE THEM, read through the production reader rather than constructed
    * by hand. Constructing `VaultTag.Carried` directly would let a test pass against a spelling
    * `VaultTag.read` never produces — which is the shape this repository has been caught by.
    */
  private def composed(tags: String*)(shown: String*): String =
    CardTopics.compose(tags.toVector.map(VaultTag.read), shown.toVector)

  // ════════════════════════════════════════════════ what reaches the card ══════

  test("a single subject is emitted in its own element, as the author spelled it") {
    assertEquals(composed("CS")(), """<span class="topic">CS</span>""")
  }

  /** NO SEPARATOR, and that is the difference from a breadcrumb rather than an oversight. The
    * spacing belongs to the stylesheet; a separator would make a set read as a sequence.
    */
  test("several subjects are separate elements, with nothing joining them") {
    assertEquals(
      composed("CS", "math", "PLT")(),
      """<span class="topic">CS</span><span class="topic">math</span><span class="topic">PLT</span>""",
    )
  }

  /** THE ORDER IS THE AUTHOR'S. Frontmatter is a list; sorting would impose an order nobody
    * chose, and nothing downstream needs one.
    */
  test("subjects keep the order they were written in") {
    assertEquals(
      composed("zeta", "alpha")(),
      """<span class="topic">zeta</span><span class="topic">alpha</span>""",
    )
  }

  /** THE WHOLE TAG, NOT ITS LEAF. Obsidian nests with `/`; the broader segment is often what
    * situates the card, and dropping it would discard what the author wrote.
    */
  test("a nested tag shows every segment, not just its last") {
    assertEquals(composed("backend/scala")(), """<span class="topic">backend/scala</span>""")
  }

  // ════════════════════════════════════════════════ what does not ══════════════

  test("no tags yields the empty string, with no element and no stray markup") {
    assertEquals(composed()(), "")
  }

  /** A tag Anki refused is the run report's business. Printing it would show the author a
    * subject their filtered decks cannot find.
    */
  test("a tag Anki cannot hold never reaches the card") {
    assertEquals(composed("my tag")(), "")
    assertEquals(composed("CS", "my tag")(), """<span class="topic">CS</span>""")
  }

  /** THE ANTI-SPOILER RULE, the same one the breadcrumb obeys: a term the card already carries
    * as a field is redundant on the question side and a spoiler on the answer side.
    */
  test("a subject the card already shows is not repeated") {
    assertEquals(composed("CS", "math")("math"), """<span class="topic">CS</span>""")
  }

  /** REMOVAL, NOT TRUNCATION — a set, so a member goes from the middle and its neighbours stay.
    * A deck path could not do this; see `Decks.clamp`.
    */
  test("a subject removed from the middle leaves its neighbours untouched") {
    assertEquals(
      composed("CS", "math", "PLT")("math"),
      """<span class="topic">CS</span><span class="topic">PLT</span>""",
    )
  }

  test("every subject being removed yields the empty string, not an empty element") {
    assertEquals(composed("CS")("CS"), "")
  }

  // ════════════════════════════════════════════════ how things are compared ════

  /** LOOSELY, matching the comparison the breadcrumb already uses for its own de-duplication:
    * a tag and a heading naming one thing may differ in case, in spacing, in Unicode normal
    * form, and in whether the words are joined by a hyphen.
    */
  test("the comparison survives case, spacing and hyphenation") {
    assertEquals(composed("type-theory")("Type Theory"), "")
    assertEquals(composed("CS")("cs"), "")
  }

  test("a repeated subject is said once") {
    assertEquals(composed("CS", "cs")(), """<span class="topic">CS</span>""")
  }

  /** Two subjects that merely OVERLAP are both kept — the rule removes a repeat, never a
    * relative.
    */
  test("subjects that merely overlap are both kept") {
    assertEquals(
      composed("type", "type-theory")(),
      """<span class="topic">type</span><span class="topic">type-theory</span>""",
    )
  }

  // ════════════════════════════════════════════════ escaping ═══════════════════

  /** ESCAPED HERE, the argument position for every caller, exactly as the breadcrumb is. A tag
    * arrives as the author wrote it; unescaped it would put their text into the markup.
    */
  test("a subject carrying markup characters reaches the card escaped") {
    assertEquals(composed("a&b")(), """<span class="topic">a&amp;b</span>""")
  }
