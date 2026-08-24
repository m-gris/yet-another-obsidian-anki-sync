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

  // ============ composing the breadcrumb from the whole location ====

  /** ==The bug these exist for==
    *
    * The breadcrumb was built from HEADING ANCESTORS ONLY. A note whose marked heading is its
    * H1 has none, so the field rendered empty — and the file name, which is very often the
    * missing word, was never used. Measured on a real card: `System Design Pattern.md` holding
    * `# 3 Components`, synced, `Context = ''`, and the card asks "3 Components" with nothing on
    * it saying three components OF WHAT.
    *
    * The rule is now: SHOW THE WHOLE LOCATION, MINUS WHATEVER THE CARD ALREADY CARRIES AS A
    * FIELD. Not "minus the answer" — a segment on the question side is redundant and a segment
    * on the answer side is a spoiler, and both are excluded for their own reason.
    */
  private def composed(location: Vector[String], shown: String*): String =
    CardContext.compose(location, shown.toVector)

  test("a card whose heading chain is empty still says which file it came from") {
    assertEquals(
      composed(Vector("System Design Pattern", "3 Components"), "3 Components"),
      "System Design Pattern",
    )
  }

  test("folders, the file and the headings all reach the breadcrumb, in that order") {
    assertEquals(
      composed(Vector("Maths", "Analysis", "Functions", "Surjection", "Definition"), "Definition"),
      s"Maths${sep}Analysis${sep}Functions${sep}Surjection",
    )
  }

  /** THE `cdd` CASE WHERE THE FILE NAME IS THE CONCEPT. `Bijection.md` holding
    * `# Definition #flashcard/cdd/2way` has no ancestor heading, so the concept falls back to
    * the file name — which is then a FIELD on the card, and the answer to one of its two cards.
    * Repeating it in the breadcrumb would print the answer above the question.
    */
  test("a file name that became the concept is not repeated in the breadcrumb") {
    assertEquals(composed(Vector("Bijection", "Definition"), "Bijection", "Definition"), "")
  }

  /** REMOVAL, NOT TRUNCATION — the property that separates a breadcrumb from a deck path. A
    * deck path is prefix-closed, so cutting the concept costs every segment below it; a
    * breadcrumb drops the offending element and keeps its neighbours.
    */
  test("a segment in the middle is dropped without taking its neighbours") {
    assertEquals(
      composed(Vector("Anatomy", "Scaphoid", "Blood supply"), "Scaphoid"),
      s"Anatomy${sep}Blood supply",
    )
  }

  test("a card that carries no location segment as a field keeps the whole chain") {
    assertEquals(
      composed(Vector("Anatomy", "Bones", "Sutures")),
      s"Anatomy${sep}Bones${sep}Sutures",
    )
  }

  /** A breadcrumb that repeated the answer because of a capital letter would be worse than
    * none, so the comparison uses the canonical form card keys are already built on.
    */
  test("the comparison survives case, spacing and normal form") {
    assertEquals(composed(Vector("Anatomy", "SCAPHOID"), "Scaphoid"), "Anatomy")
    assertEquals(composed(Vector("Bones  of   the hand"), "Bones of the hand"), "")
    assertEquals(composed(Vector("Café"), "Café"), "")
  }

  test("blank segments and blank exclusions are both ignored") {
    assertEquals(composed(Vector("Anatomy", "   ", "Bones"), "", "  "), s"Anatomy${sep}Bones")
  }

  /** The result is still an HTML field, so escaping is not optional just because the segments
    * now come from folders and file names as well as headings.
    */
  test("every part is escaped, wherever it came from") {
    val out = composed(Vector("A & B", "<script>", "x > y"))
    assert(out.contains("&amp;"), out)
    assert(out.contains("&lt;script&gt;"), out)
    assert(!out.contains("<script>"), s"raw markup reached a card field: $out")
  }

  // ---------------------------------------- not saying the same word twice ----

  /** ==Why the breadcrumb de-duplicates and the deck path must not==
    *
    * They are different kinds of thing. A deck path is a filing ADDRESS and keeps every repeat,
    * for the reason `Decks.compose` gives in its own docstring. A breadcrumb is a SENTENCE read
    * while answering, and `Anatomy › Bones › Bones › Cells that remodel bone` tells the reviewer
    * nothing the shorter form does not.
    *
    * It is needed because the commonest Obsidian convention is a file whose H1 restates its own
    * name — `dummy-vault` does it in all twelve files. On a `cdd` card both copies are already
    * removed by the concept filter, since the H1 is a FIELD there; on a one-way, cloze, sequence
    * or table card it is not a field and both copies survive.
    */
  test("a file name repeated by its own H1 is said once") {
    assertEquals(composed(Vector("Anatomy", "Bones", "Bones", "Cells that remodel bone")),
                 s"Anatomy${sep}Bones${sep}Cells that remodel bone")
  }

  /** THE LATER OCCURRENCE WINS, and that is what makes a kebab-cased stem lose to its title. A
    * file stem is a filesystem artifact; the heading is the author's prose.
    */
  test("a kebab-cased file stem loses to the spaced title it restates") {
    assertEquals(composed(Vector("Anatomy", "Body-Shapes", "Body shapes")), s"Anatomy${sep}Body shapes")
    assertEquals(composed(Vector("Snake_Case", "Snake case")), "Snake case")
  }

  /** NOT A PREFIX MATCH. `Messaging.md` opening `# Messaging Patterns` says two different
    * things, and collapsing them would drop a word the author wrote. Only whole-segment repeats
    * go.
    */
  test("segments that merely overlap are both kept") {
    assertEquals(
      composed(Vector("Patterns", "Messaging", "Messaging Patterns", "Cost / benefit")),
      s"Patterns${sep}Messaging${sep}Messaging Patterns${sep}Cost / benefit",
    )
  }

  test("a repeat separated by other segments is still a repeat") {
    assertEquals(composed(Vector("Bones", "Anatomy", "Bones")), s"Anatomy${sep}Bones")
  }

  /** The looser comparison is for DISPLAY only and must never be confused with the one card
    * keys are built on — this pins that it is strictly more forgiving, not different.
    */
  test("the dedup comparison also ignores case and spacing") {
    assertEquals(composed(Vector("BONES", "Bones")), "Bones")
    assertEquals(composed(Vector("Bones  of   the hand", "Bones of the hand")), "Bones of the hand")
  }
