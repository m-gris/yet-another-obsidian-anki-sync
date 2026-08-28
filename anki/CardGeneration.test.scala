package obsidiananki.anki

import cats.data.NonEmptyVector
import obsidiananki.model.Marker

/** WHETHER A CARD EXISTS, DECIDED THE WAY ANKI DECIDES IT.
  *
  * THE REAL TEMPLATES ARE THE SUBJECT, NOT IMITATIONS OF THEM. Most assertions below load this
  * repository's own note type files, because those are the ones that must be judged correctly —
  * a test against a hand-written mirror of a template proves the mirror right and says nothing
  * about the file that ships.
  *
  * WRITTEN AGAINST `???` FIRST, so every assertion failed once for the right reason.
  */
class CardGenerationTest extends munit.FunSuite:

  private def asset(slug: String): NoteTypeAsset =
    NoteTypeAssets.load(slug).fold(e => fail(s"load $slug: $e"), identity)

  private def frontOf(slug: String, templateName: String): String =
    val a = asset(slug)
    a.spec.templates.toVector
      .collectFirst { case (n, t) if n == templateName => t.front }
      .getOrElse(fail(s"no template '$templateName' in $slug: ${a.spec.templates.toVector.map(_._1)}"))

  // ══════════════════════════════════ the gates that really gate ════════════════════════════

  /** THE GATE THIS WHOLE FILE EXISTS TO RESPECT. The third concept-descriptor card's entire
    * front is wrapped in `{{#ThreeWay}}`, so a note that did not ask for three ways has two
    * cards and not three. Counting templates would give three for both, which is why the item
    * recorded the obvious fix as wrong.
    */
  test("the third concept-descriptor card exists only when its gate field is set") {
    val front = frontOf("concept-descriptor", "Card 3: Concept+Description -> Descriptor")
    val base = Map("Concept" -> "radius", "Descriptor" -> "bone", "Description" -> "forearm")
    assert(!CardGeneration.frontRenders(front, base), "a third card was generated without the gate")
    assert(CardGeneration.frontRenders(front, base + (Marker.ThreeWayField -> "1")), "the gate did not open")
  }

  /** THE MIRROR GATE, AND IT OPENS THE OTHER WAY. The first card is wrapped in
    * `{{^ValueOnly}}`, so setting the field REMOVES a card. A rule that treated every section
    * as "present means render" would get this exactly backwards while passing the test above.
    */
  test("the first concept-descriptor card disappears when the value-only field is set") {
    val front = frontOf("concept-descriptor", "Card 1: Descriptor+Description -> Concept")
    val base = Map("Concept" -> "radius", "Descriptor" -> "bone", "Description" -> "forearm")
    assert(CardGeneration.frontRenders(front, base), "the ordinary card was not generated")
    assert(
      !CardGeneration.frontRenders(front, base + (Marker.ValueOnlyField -> "1")),
      "value-only did not retire the concept-recall card",
    )
  }

  /** EVERY ORDINARY FRONT IN THIS REPOSITORY MUST RENDER for a note with ordinary content.
    * A rule that was too strict would make cards vanish, which is the more dangerous direction:
    * the tool would price a change as destroying cards that were never there.
    */
  test("every ungated front in the repository renders for a filled-in note") {
    val filled = Map(
      "Front" -> "q", "Back" -> "a", "Title" -> "t", "Text" -> "<ul><li>i</li></ul>",
      "Concept" -> "c", "Descriptor" -> "d", "Description" -> "e", "Context" -> "ctx",
    )
    val gated = Set("Card 3: Concept+Description -> Descriptor")
    NoteTypeAssets.all.fold(
      es => fail(s"assets: $es"),
      _.foreach: a =>
        a.spec.templates.toVector.foreach: (name, t) =>
          if !gated.contains(name) then
            assert(
              CardGeneration.frontRenders(t.front, filled),
              s"${a.slug} / '$name' rendered empty for a filled-in note",
            ),
    )
  }

  // ══════════════════════════════════ Anki's emptiness rule ═════════════════════════════════

  /** THE RULE THAT MAKES THE GATES WORK AT ALL. Every front here opens with the deck name, so
    * if the deck counted as content then no front would ever be empty, no gate would ever
    * close, and this file would be an elaborate way of counting templates.
    */
  test("a front holding only special fields is empty") {
    assert(!CardGeneration.frontRenders("""<span class="deck">{{Deck}}</span>""", Map.empty))
    assert(!CardGeneration.frontRenders("{{Deck}}{{Card}}{{Tags}}", Map("Front" -> "q")))
  }

  test("a front holding one real non-empty field is not empty") {
    assert(CardGeneration.frontRenders("<div>{{Front}}</div>", Map("Front" -> "q")))
  }

  test("a front whose only real field is empty is empty") {
    assert(!CardGeneration.frontRenders("<div>{{Front}}</div>", Map("Front" -> "")))
  }

  /** STATIC TEXT IS NOT CONTENT. A template that printed a label and nothing else would be a
    * card with no question on it, which is the shape Anki refuses to generate.
    */
  test("static markup with no field is empty") {
    assert(!CardGeneration.frontRenders("<div class='pair'>Question:</div>", Map("Front" -> "q")))
  }

  // ══════════════════════════════════ cloze ═════════════════════════════════════════════════

  /** ONE CARD PER ORDINAL, NOT ONE CARD. The fake previously answered one for every cloze note,
    * so a note with three groups looked like a note with one card — and ordinal drift, the
    * failure the cloze redesign is organised around, could not be reproduced against it.
    */
  test("a cloze note has one card per distinct ordinal") {
    assertEquals(
      CardGeneration.clozeOrdinals(Vector("Text" -> "The {{c1::radius}} and {{c2::ulna}}.")),
      Vector(1, 2),
    )
  }

  test("an ordinal used twice is still one card, and ordinals come back in order") {
    assertEquals(
      CardGeneration.clozeOrdinals(Vector("Text" -> "{{c3::a}} {{c1::b}} {{c3::c}}")),
      Vector(1, 3),
    )
  }

  test("ordinals are found across every field, not only the first") {
    assertEquals(
      CardGeneration.clozeOrdinals(Vector("Text" -> "{{c1::a}}", "Extra" -> "{{c2::b}}")),
      Vector(1, 2),
    )
  }

  test("text with no cloze at all yields no ordinals") {
    assertEquals(CardGeneration.clozeOrdinals(Vector("Text" -> "plain prose")), Vector.empty)
  }

  // ══════════════════════════════════ the count itself ══════════════════════════════════════

  /** THE REGRESSION THAT MOTIVATED ALL OF THIS — `IN-FLIGHT.md` item 31. A note type defined
    * in a test with three templates produced ONE card, silently, because the fake matched on
    * the note type's NAME and fell through to a default. A test doing this on 2026-08-28 was
    * pointed at a named note type instead, with a comment explaining why; this is the fix that
    * comment was standing in for.
    */
  test("a locally defined note type with three ungated templates has three cards") {
    def tmpl(n: Int) = (s"Card $n", CardTemplate(s"<div>{{Front}}</div>$n", "{{Back}}"))
    val spec = NoteTypeSpec(
      "Locally Defined",
      isCloze = false,
      NonEmptyVector.of("Front", "Back"),
      NonEmptyVector.of(tmpl(1), tmpl(2), tmpl(3)),
      "",
    )
    assertEquals(CardGeneration.cardCount(spec, Vector("Front" -> "q", "Back" -> "a")), 3)
  }

  test("the count skips a template whose front is gated shut") {
    val spec = asset("concept-descriptor").spec
    val two = Vector("Concept" -> "radius", "Descriptor" -> "bone", "Description" -> "forearm")
    assertEquals(CardGeneration.cardCount(spec, two), 2, "a two-way note should have two cards")
    assertEquals(
      CardGeneration.cardCount(spec, two :+ (Marker.ThreeWayField -> "1")),
      3,
      "setting the gate field should add the third card",
    )
  }

  /** THE NAME IS NOT CONSULTED, WHICH IS THE POINT. The same templates and the same fields
    * under a different name must give the same answer — the previous implementation could not
    * have passed this, since the name was the only thing it read.
    */
  test("renaming a note type does not change how many cards it has") {
    val original = asset("basic-and-reversed").spec
    val renamed  = original.copy(name = "Something Else Entirely")
    val fields   = Vector("Front" -> "q", "Back" -> "a")
    assertEquals(CardGeneration.cardCount(renamed, fields), CardGeneration.cardCount(original, fields))
  }

  test("a cloze note type counts ordinals rather than templates") {
    val spec = asset("cloze").spec
    assertEquals(CardGeneration.cardCount(spec, Vector("Text" -> "{{c1::a}} {{c2::b}}")), 2)
  }
