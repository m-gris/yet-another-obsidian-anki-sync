package obsidiananki.extract

/** The breadcrumb a card shows above its prompt.
  *
  * ==What is NOT here, and why==
  *
  * These tests once drove a `CardContext.compose(shape, source)`, which let a vault-level
  * setting choose which parts of a card's location the breadcrumb showed. That was deleted on
  * 2026-08-23, unwired, along with the `ContextShape`/`ContextSource` pair — see the commit for
  * the argument. The short form: WHAT A BREADCRUMB MAY SHOW IS NOT A PREFERENCE. It is
  * everything the card's own face does not already give away, which differs per card shape and
  * is therefore decided by the caller building that card, not by a dial the whole vault shares.
  *
  * That contract is stated at the top of `CardContext.scala` and implemented at five call
  * sites — `Extractor.scala:312, 363, 386, 487` and `Tables.scala:156`. `render` is the shared
  * half: it joins and escapes, and it decides nothing.
  */
class CardContextTest extends munit.FunSuite:

  private val sep = CardContext.Separator

  test("a chain is joined by the separator, outermost first") {
    assertEquals(
      CardContext.render(Vector("Maths", "Analysis", "Functions")),
      s"Maths${sep}Analysis${sep}Functions",
    )
  }

  /** A REAL AND EXPECTED VALUE, not an edge case. A `3way` heading sitting directly under its
    * note's H1 has nothing above it that the card does not already show, so its chain is empty
    * once the caller has removed the concept. The note types wrap the field in
    * `{{#Context}}…{{/Context}}`, so an empty value emits no markup at all.
    */
  test("an empty chain yields the empty string") {
    assertEquals(CardContext.render(Vector.empty), "")
  }

  test("a single segment needs no separator") {
    assertEquals(CardContext.render(Vector("Functions")), "Functions")
  }

  /** ESCAPED, BECAUSE THE RESULT IS AN HTML FIELD. Heading text is author text and can hold any
    * of the six characters `Html.escape` rewrites.
    */
  test("segments are escaped on the way into the field") {
    val out = CardContext.render(Vector("A & B", "x > y", "<script>"))
    assert(out.contains("&amp;"), out)
    assert(out.contains("&gt;"), out)
    assert(out.contains("&lt;script&gt;"), out)
    assert(!out.contains("<script>"), s"raw markup reached a card field: $out")
  }

  /** THE SEPARATOR MUST SURVIVE ESCAPING UNCHANGED, which is why it is U+203A and not `>`.
    * `Html.escape` would render `>` as `&gt;`, putting an entity between every two segments.
    */
  test("the separator is not itself escaped") {
    assertEquals(CardContext.render(Vector("a", "b")), s"a${sep}b")
    assert(!CardContext.render(Vector("a", "b")).contains("&gt;"))
  }

  /** JOIN-THEN-ESCAPE EQUALS ESCAPE-THEN-JOIN, which is a property of the escaper rather than a
    * coincidence: `Html.escape` is a per-character map, so it distributes over concatenation,
    * and the separator contains no character it rewrites. Worth pinning because a future author
    * reordering those two steps would change every card's Context field, and with it every
    * note's content hash.
    */
  test("escaping distributes over the join") {
    val parts = Vector("A & B", "C > D")
    assertEquals(
      CardContext.render(parts),
      parts.map(p => CardContext.render(Vector(p))).mkString(sep),
    )
  }

  /** THE ANTI-SPOILER CONTRACT, EXPRESSED THE ONLY WAY IT CAN BE HERE. `render` cannot know
    * what is on a card's face; only the caller building that card knows. So the caller hands
    * over a chain with the spoiler already removed, and what this pins is that `render` adds
    * nothing back — no trailing separator, no empty step, no gap where the dropped segment was.
    *
    * _Rewritten 2026-08-23 from a version that drove the deleted `compose`. The property is
    * unchanged; only the function carrying it moved._
    */
  test("a chain with its spoiler removed renders with no trace of the gap") {
    val full    = Vector("Body shapes", "Cranial bones", "Frontal")
    val trimmed = full.dropRight(1)
    assertEquals(CardContext.render(trimmed), s"Body shapes${sep}Cranial bones")
    assert(!CardContext.render(trimmed).contains("Frontal"))
    assert(!CardContext.render(trimmed).endsWith(sep), "a dropped segment left a dangling separator")
  }
