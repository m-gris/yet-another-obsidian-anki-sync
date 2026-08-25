package obsidiananki.extract

import obsidiananki.model.{PropertyName, ThreeFieldDirections}

/** READING THE VAULT'S DECLARED VOCABULARY OF TYPED EDGES.
  *
  * ==What a schema is for==
  *
  * A frontmatter property becomes a card only when the author has said so. Without that, every
  * property in every note would mint one — `created`, `aliases`, whatever a plugin wrote — and a
  * typo would mint a DIFFERENT card under a different key rather than being refused. The schema
  * is the dictionary that makes an unrecognised property a silence and a mistyped DIRECTION a
  * loud refusal.
  *
  * ==What these tests are shaped around==
  *
  * That the schema lives in a note a person edits by hand, in Obsidian, in prose. So the reader
  * has to tolerate what people actually write — blank lines, explanation, both list bullets,
  * spacing around the separator — while refusing anything it cannot read rather than guessing.
  */
class EdgeSchemaTest extends munit.FunSuite:

  import ThreeFieldDirections.*

  def prop(raw: String): PropertyName =
    PropertyName.fromFrontmatter(raw).fold(e => fail(s"bad property '$raw': $e"), identity)

  def parsed(text: String): EdgeSchema =
    EdgeSchema.parse(text).fold(errs => fail(s"parse failed: ${errs.toVector.map(_.describe)}"), identity)

  def errorsOf(text: String): Vector[EdgeSchemaError] =
    EdgeSchema.parse(text).fold(_.toVector, ok => fail(s"expected errors, parsed: ${ok.rules}"))

  // ------------------------------------------------------------- the ordinary case ----

  test("a schema maps each declared property to how it is asked") {
    val schema = parsed(
      """- special-case-of: 1way
        |- dual-of: 3way
        |- defined-in: 2way
        |""".stripMargin
    )

    assertEquals(schema.directionsFor(prop("special-case-of")), Some(ValueOnly))
    assertEquals(schema.directionsFor(prop("dual-of")), Some(All))
    assertEquals(schema.directionsFor(prop("defined-in")), Some(Default))
  }

  /** THE ANSWER FOR EVERY PROPERTY NOBODY DECLARED, and it is a silence rather than a complaint.
    * Almost every property in a real note is not an edge; a tool that remarked on each would be
    * unreadable, and one that made cards from them would fill a collection with `created:`.
    */
  test("a property nobody declared is simply not a card") {
    val schema = parsed("- special-case-of: 1way\n")
    assertEquals(schema.directionsFor(prop("created")), None)
    assertEquals(schema.directionsFor(prop("aliases")), None)
    assertEquals(schema.directionsFor(prop("special-case-off")), None, "a typo is not a near-miss")
  }

  test("an empty schema is legitimate and declares nothing") {
    assert(parsed("").isEmpty)
    assert(parsed("\n\n").isEmpty)
  }

  // ------------------------------------- what a person actually writes in a note ----

  /** PROSE BENEATH THE HEADING IS IGNORED, NOT REFUSED, and that is the whole point of keeping
    * this in the vault rather than in a configuration file. An author who cannot explain their
    * own vocabulary beside it has gained nothing over a dotfile.
    */
  test("explanation around the rules is ignored rather than refused") {
    val schema = parsed(
      """These are the relations I use. A one-way card asks for the target only.
        |
        |- special-case-of: 1way
        |
        |Three-way also asks which relation holds between two things, which is the hard one.
        |
        |- dual-of: 3way
        |""".stripMargin
    )
    assertEquals(schema.rules.size, 2, s"prose was read as rules, or rules were lost: ${schema.rules}")
    assertEquals(schema.directionsFor(prop("dual-of")), Some(All))
  }

  test("both list bullets are accepted, and spacing around the separator does not matter") {
    val schema = parsed(
      """* special-case-of:1way
        |-    dual-of   :   3way
        |""".stripMargin
    )
    assertEquals(schema.directionsFor(prop("special-case-of")), Some(ValueOnly))
    assertEquals(schema.directionsFor(prop("dual-of")), Some(All))
  }

  /** The same canonicalisation a heading gets, for the same reason: tidying how a name is
    * spelled must not change which card it makes.
    */
  test("a property name is canonicalised, so its spelling in the schema need not match the note") {
    val schema = parsed("- Special-Case-Of: 1way\n")
    assertEquals(schema.directionsFor(prop("special-case-of")), Some(ValueOnly))
  }

  // --------------------------------------------------------- what is refused, and why ----

  test("an unknown direction is refused, and the refusal names what was written") {
    val errs = errorsOf("- special-case-of: 2-way\n")
    assertEquals(errs.size, 1, s"expected exactly one error: $errs")
    errs.head match
      case EdgeSchemaError.UnknownDirection(property, raw) =>
        assertEquals(property, "special-case-of")
        assertEquals(raw, "2-way")
      case other => fail(s"expected UnknownDirection, got $other")
  }

  test("a list item with no separator is refused rather than guessed at") {
    val errs = errorsOf("- special-case-of 1way\n")
    assert(
      errs.exists { case EdgeSchemaError.NotAnEntry(_) => true; case _ => false },
      s"expected NotAnEntry, got $errs",
    )
  }

  /** DECLARING A PROPERTY TWICE IS REFUSED RATHER THAN RESOLVED. First-wins and last-wins are
    * both defensible and neither is visible to the author, who sees a schema that reads as
    * though it says two things.
    */
  test("the same property declared twice is refused, even when the spellings differ") {
    val errs = errorsOf("- special-case-of: 1way\n- Special-Case-Of: 3way\n")
    assert(
      errs.exists { case EdgeSchemaError.DeclaredTwice(_) => true; case _ => false },
      s"expected DeclaredTwice — the two spellings canonicalise alike: $errs",
    )
  }

  /** EVERY BAD LINE IS REPORTED, NOT ONLY THE FIRST. Somebody fixing a schema wants the whole
    * list; one error per run is a conversation with a compiler that answers one question at a
    * time.
    */
  test("every unreadable line is reported, not just the first") {
    val errs = errorsOf(
      """- special-case-of: 2-way
        |- dual-of: sideways
        |- this line has no separator
        |""".stripMargin
    )
    assertEquals(errs.size, 3, s"only some failures were reported: $errs")
  }

  /** ONE BAD LINE MUST NOT COST THE GOOD ONES their diagnosis — but it does cost the whole
    * schema, and that is deliberate. A half-read vocabulary would silently stop making cards for
    * the properties it failed to read, which looks exactly like an author having deleted them.
    */
  test("a schema with any unreadable line yields no schema at all") {
    assert(EdgeSchema.parse("- special-case-of: 1way\n- dual-of: sideways\n").isLeft)
  }

  test("every error describes itself in words a reader can act on") {
    val errs = errorsOf("- special-case-of: 2-way\n- no separator here\n")
    errs.foreach { e =>
      assert(e.describe.nonEmpty, s"$e described itself as nothing")
      assert(e.describe.sizeIs > 20, s"$e described itself too thinly: '${e.describe}'")
    }
  }

  // ------------------------------------------------------------------ the vocabulary ----

  /** THE SAME WORDS A HEADING USES. `1way` here means what `#flashcard/cdd/1way` means on a
    * heading, so an author learns one vocabulary rather than two. If these ever diverge, the
    * divergence should be a decision rather than a drift.
    */
  test("the three directions are the ones a heading marker already offers") {
    assertEquals(EdgeSchema.directionOf("1way"), Some(ValueOnly))
    assertEquals(EdgeSchema.directionOf("2way"), Some(Default))
    assertEquals(EdgeSchema.directionOf("3way"), Some(All))
    assertEquals(EdgeSchema.directionOf("4way"), None)
    assertEquals(EdgeSchema.directionOf(""), None)
  }

  // ------------------------------------------- finding the schema inside a note ----

  /** READ OFF RAW MARKDOWN, NOT A PARSED DOCUMENT. A schema note is prose with a list in it, and
    * parsing is strict — an array index written as `[0]` in a sentence fails. Were the schema read
    * from a parsed document, a paragraph having nothing to do with the vocabulary could cost the
    * vault its whole vocabulary. Reading lines cannot fail.
    */
  test("the schema is the text under its heading, ending at the next heading") {
    val note =
      """# Function Space
        |
        |Some prose. An array index like [0] would break a strict parser.
        |
        |# Properties-to-Flashcards
        |
        |- special-case-of: 1way
        |
        |# Something Else
        |
        |- not-a-rule: 3way
        |""".stripMargin

    val found = EdgeSchema.findIn(note).getOrElse(fail("the schema heading was not found"))
    val schema = parsed(found)
    assertEquals(schema.directionsFor(prop("special-case-of")), Some(ValueOnly))
    assertEquals(schema.directionsFor(prop("not-a-rule")), None, "it read past the next heading")
  }

  /** SPACES AND HYPHENS ARE THE SAME HERE, and that leniency is load-bearing rather than
    * indulgent. A vault with no schema note is the ordinary case, so a missing schema cannot be
    * reported as an error — which makes a NEAR-MISS the worst outcome available: write the
    * heading with spaces and you get no schema, no cards, and no message about either.
    */
  test("the heading is matched loosely enough that a near-miss cannot fail silently") {
    assert(EdgeSchema.findIn("## properties to flashcards\n- a: 1way\n").isDefined)
    assert(EdgeSchema.findIn("### PROPERTIES-TO-FLASHCARDS\n- a: 1way\n").isDefined)
    assert(EdgeSchema.findIn("# Properties To Flashcards\n- a: 1way\n").isDefined)
    assert(EdgeSchema.findIn("#  properties_to_flashcards  \n- a: 1way\n").isDefined)

    // Still a heading match, not a substring one.
    assertEquals(EdgeSchema.findIn("# Properties to Flashcards and more\n- a: 1way\n"), None)
  }

  test("a note without the heading carries no schema") {
    assertEquals(EdgeSchema.findIn("# Function Space\n\nProse.\n"), None)
  }
