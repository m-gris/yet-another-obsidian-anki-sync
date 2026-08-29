package obsidiananki.extract

import obsidiananki.model.*
import obsidiananki.plan.{BuildFailure, SourceKind, SourcedSpec}

/** CARDS MADE FROM A NOTE'S FRONTMATTER RELATIONS.
  *
  * The fixture throughout is the note that prompted the feature — `Function Space.md`, whose
  * frontmatter says `special-case-of: "[[HomSet]]"` and whose headings name aspects of the concept
  * rather than the concept itself. That last part is why the subject can only come from the file
  * name: nothing in the body says "Function Space".
  */
class EdgesTest extends munit.FunSuite:

  import ThreeFieldDirections.*

  val noteId: NoteId = NoteId.fromFrontmatter("n1").toOption.get

  def schemaOf(text: String): EdgeSchema =
    EdgeSchema.parse(text).fold(e => fail(s"bad fixture schema: $e"), identity)

  val schema: EdgeSchema = schemaOf("- special-case-of: cdd/1way\n- dual-of: cdd/3way\n- status: cdd/2way\n")

  def run(
      properties: (String, PropertyValue)*,
  ): (Vector[SourcedSpec], Vector[BuildFailure]) =
    Edges.specsFor(
      noteId = noteId,
      noteName = "Function Space",
      relativePath = "Function Space.md",
      location = Vector("Function Space"),
      properties = properties.toMap,
      rawFrontmatter = properties.map((k, _) => s"$k: something").mkString("\n"),
      schema = schema,
      vaultTags = Vector.empty,
    )

  def only(properties: (String, PropertyValue)*): CardSpec.ThreeField =
    val (specs, failures) = run(properties*)
    assertEquals(failures, Vector.empty, s"unexpected failures: ${failures.map(_.toString)}")
    assertEquals(specs.size, 1, s"expected exactly one card: ${specs.map(_.key)}")
    specs.head.spec match
      case t: CardSpec.ThreeField => t
      case other                  => fail(s"an edge must be a three-field card, got $other")

  // ------------------------------------------------- the triple, end to end ----

  test("a declared property becomes a three-field card: subject, predicate, object") {
    val card = only("special-case-of" -> PropertyValue.One("[[HomSet]]"))

    assertEquals(card.concept, "Function Space", "the subject is the note, named by its file")
    assertEquals(card.descriptor, "special-case-of", "the predicate is the property")
    assertEquals(card.description.value, "HomSet", "the object is the value")
    assertEquals(card.directions, ValueOnly, "the schema said 1way")
  }

  /** THE KEY IS THE PROPERTY NAME, WHICH IS WHAT MAKES THIS KIND OF CARD CHEAP TO KEEP. Renaming
    * the file rewrites the card's face and moves no identity; rewording every heading in the note
    * does nothing to it at all. A heading card has neither guarantee.
    */
  test("the key names the property, not the file and not the value") {
    val card = only("special-case-of" -> PropertyValue.One("[[HomSet]]"))
    assertEquals(
      card.key,
      CardKey(noteId, CardPath.Property(PropertyName.fromFrontmatter("special-case-of").toOption.get)),
    )
  }

  test("a card built from a property says so in its source, and points at the frontmatter") {
    val (specs, _) = run("special-case-of" -> PropertyValue.One("[[HomSet]]"))
    assertEquals(specs.head.source.kind, SourceKind.Property)
    assertEquals(specs.head.source.file, "Function Space.md")
    assert(specs.head.source.line >= 1, "a source with no line sends the reader nowhere")
  }

  test("the direction comes from the schema, per property") {
    assertEquals(only("special-case-of" -> PropertyValue.One("A")).directions, ValueOnly)
    assertEquals(only("dual-of" -> PropertyValue.One("A")).directions, All)
    assertEquals(only("status" -> PropertyValue.One("draft")).directions, Default)
  }

  // ------------------------------------------------------------ what is ignored ----

  /** THE ORDINARY CASE FOR ALMOST EVERY PROPERTY IN A REAL NOTE, and it must be a silence rather
    * than a complaint. A tool that remarked on `created:` in every note would not be read.
    */
  test("a property nobody declared makes no card and no complaint") {
    val (specs, failures) = run(
      "created"  -> PropertyValue.One("2026-08-25T19:03:44"),
      "aliases"  -> PropertyValue.Many(Vector("FS")),
      "tags"     -> PropertyValue.Many(Vector("math")),
    )
    assertEquals(specs, Vector.empty)
    assertEquals(failures, Vector.empty)
  }

  test("a note with no declared properties at all yields nothing, quietly") {
    assertEquals(run(), (Vector.empty, Vector.empty))
  }

  // ------------------------------------------------- several values, one card ----

  /** ONE CARD, NOT SEVERAL, and the reason is the key. "What is this a special case of?" is one
    * question whose honest answer is both. A card per value would put the VALUE in the key, so
    * correcting a typo in one would retire that card and mint a replacement with no history.
    */
  test("several values make one card whose answer holds them all") {
    val card = only("special-case-of" -> PropertyValue.Many(Vector("[[HomSet]]", "[[Exponential Object]]")))
    assertEquals(card.description.value, "HomSet, Exponential Object")
    assertEquals(
      card.key,
      CardKey(noteId, CardPath.Property(PropertyName.fromFrontmatter("special-case-of").toOption.get)),
      "the key must not depend on the values",
    )
  }

  test("adding a value changes the answer and not the identity") {
    val one  = only("special-case-of" -> PropertyValue.One("[[HomSet]]"))
    val more = only("special-case-of" -> PropertyValue.Many(Vector("[[HomSet]]", "[[Something Else]]")))
    assertEquals(one.key, more.key)
    assertNotEquals(one.description.value, more.description.value)
  }

  // --------------------------------------------------------------- wikilinks ----

  test("a wikilink loses its brackets, because a card face is read and not clicked") {
    assertEquals(Edges.plainLink("[[HomSet]]"), "HomSet")
  }

  test("an aliased wikilink shows the alias, which is how the author asked it to read") {
    assertEquals(Edges.plainLink("[[HomSet|the hom-set]]"), "the hom-set")
  }

  test("a value that is not a link is left exactly as written") {
    assertEquals(Edges.plainLink("draft"), "draft")
    assertEquals(Edges.plainLink("O(n log n)"), "O(n log n)")
  }

  // ------------------------------------------------------- what is refused, and why ----

  /** A DECLARED EDGE WITH NOTHING ON THE FAR END. The author said this property makes a card and
    * then gave it no value; the gap between the two is exactly what wants reporting. Obsidian's
    * own template leaves empty properties behind, which is why this is a real case rather than a
    * hypothetical — but only for a property the schema NAMES.
    */
  test("a declared property with an empty value is reported, not silently skipped") {
    val (specs, failures) = run("special-case-of" -> PropertyValue.One(""))
    assertEquals(specs, Vector.empty)
    assertEquals(failures.size, 1, s"expected one failure: $failures")
  }

  test("a declared property whose value has no readable shape is reported") {
    val (specs, failures) = run("special-case-of" -> PropertyValue.Unreadable("a nested mapping"))
    assertEquals(specs, Vector.empty)
    assertEquals(failures.size, 1, s"expected one failure: $failures")
  }

  test("an empty list on a declared property is reported like an empty value") {
    val (specs, failures) = run("special-case-of" -> PropertyValue.Many(Vector.empty))
    assertEquals(specs, Vector.empty)
    assertEquals(failures.size, 1, s"expected one failure: $failures")
  }

  /** ONE BAD EDGE MUST NOT COST THE GOOD ONES, exactly as one bad section does not cost a note its
    * other cards. Failures are collected per property.
    */
  test("a broken edge does not stop the note's other edges") {
    val (specs, failures) = run(
      "special-case-of" -> PropertyValue.One(""),
      "dual-of"         -> PropertyValue.One("[[Product]]"),
    )
    assertEquals(specs.size, 1, s"the good edge was lost: ${specs.map(_.key)}")
    assertEquals(failures.size, 1)
  }

  // ------------------------------------------------------------------ the breadcrumb ----

  /** The `Context` field is composed exactly as it is for a heading card — the whole location,
    * minus what the card already carries as a field. The subject and the predicate are both
    * fields, so neither may appear in the breadcrumb as well.
    */
  test("the breadcrumb does not repeat what the card already shows") {
    val (specs, _) = Edges.specsFor(
      noteId = noteId,
      noteName = "Function Space",
      relativePath = "Maths/Function Space.md",
      location = Vector("Maths", "Function Space"),
      properties = Map("special-case-of" -> PropertyValue.One("[[HomSet]]")),
      rawFrontmatter = "special-case-of: \"[[HomSet]]\"",
      schema = schema,
      vaultTags = Vector.empty,
    )
    val card = specs.head.spec.asInstanceOf[CardSpec.ThreeField]
    assert(!card.context.contains("Function Space"), s"the subject is repeated in: '${card.context}'")
    assert(!card.context.contains("special-case-of"), s"the predicate is repeated in: '${card.context}'")
    assert(card.context.contains("Maths"), s"the folder was lost from: '${card.context}'")
  }
