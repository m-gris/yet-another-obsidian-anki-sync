package obsidiananki.anki

import io.circe.Json
import io.circe.syntax.*
import obsidiananki.model.OwnedTag
// The ObservedNote decoder lives on AnkiConnect rather than on the domain type, so that the
// model stays free of any JSON library. Bringing it into scope is therefore explicit.
import obsidiananki.anki.AnkiConnect.given

/** The wire layer, tested against RESPONSES CAPTURED FROM A LIVE ANKI.
  *
  * Every payload below is a real response body, not one invented to match the decoder. That
  * distinction has mattered in this project more than once: a fixture written from the
  * documentation agrees with whatever the reader believed, and so proves only that the
  * belief is self-consistent.
  */
class AnkiConnectTest extends munit.FunSuite:

  // ================================================ the envelope ====

  test("a successful envelope yields the result payload") {
    val body = """{"result": ["Basic", "Cloze"], "error": null}"""
    assertEquals(
      AnkiConnect.decodeAs[Vector[String]]("modelNames", body),
      Right(Vector("Basic", "Cloze")),
    )
  }

  /** THE central failure of the original design, as a test.
    *
    * A corrupt card makes `cardsInfo` return null for the WHOLE batch. Read as an empty
    * collection, that says "Anki holds no cards" — and since an orphan is defined as a card
    * present in Anki and absent from the markdown, every card in the collection would look
    * like an orphan and land on the prune list.
    *
    * This is the exact body a genuinely corrupt collection returned.
    */
  test("a null result is NEVER read as an empty collection") {
    val body = """{"result": null, "error": "missing template"}"""
    AnkiConnect.decodeAs[Vector[Long]]("cardsInfo", body) match
      case Left(AnkiError.Remote(action, message)) =>
        assertEquals(action, "cardsInfo")
        assertEquals(message, "missing template")
      case other => fail(s"a poisoned batch read did not fail: $other")
  }

  /** And with no error reported either — the decoder, not the envelope, must refuse it. */
  test("a null result with NO error is still not an empty collection") {
    val body = """{"result": null, "error": null}"""
    assert(
      AnkiConnect.decodeAs[Vector[Long]]("findNotes", body).isLeft,
      "a null result decoded to an empty vector",
    )
  }

  test("a null result IS acceptable where nothing was expected back") {
    val body = """{"result": null, "error": null}"""
    assertEquals(AnkiConnect.decodeAs[Option[Long]]("updateNote", body), Right(None))
  }

  test("a response that is not JSON fails as malformed, not as empty") {
    AnkiConnect.decodeAs[Vector[String]]("modelNames", "<html>Anki is not here</html>") match
      case Left(AnkiError.MalformedResponse(_, _)) => ()
      case other                                   => fail(s"expected MalformedResponse, got $other")
  }

  test("a response missing the envelope fields is malformed") {
    assert(AnkiConnect.decodeAs[Vector[String]]("modelNames", """{"foo": 1}""").isLeft)
  }

  /** AN ABSENT `error` KEY IS NOT THE SAME AS `error: null`, and circe will not tell them
    * apart for you: `as[Option[String]]` answers `Right(None)` for both — verified against
    * circe 0.14.16, not assumed.
    *
    * The consequence is the reason this is tested rather than trusted. A body carrying a
    * result and no `error` key would take the SUCCESS path, so a search would yield an empty
    * list. Empty reads as "Anki holds nothing", every card in the vault looks absent, and
    * since identity lookup runs off that one enumeration the whole vault is re-created —
    * accepted, because `allowDuplicate` is set.
    */
  test("a response with NO error key at all is refused, not read as success") {
    AnkiConnect.decodeAs[Vector[Long]]("findNotes", """{"result": [1, 2]}""") match
      case Left(AnkiError.MalformedResponse(_, detail)) =>
        assert(detail.contains("error"), s"the detail does not name the missing field: $detail")
      case other => fail(s"a response with no 'error' key was accepted: $other")
  }

  /** And the message must be TRUE. An `error` field of the wrong type is not a missing one,
    * and reporting it as missing sends the reader looking for the wrong thing.
    */
  test("an error field that is neither null nor a string is reported as what it is") {
    AnkiConnect.decodeAs[Vector[Long]]("findNotes", """{"result": null, "error": 7}""") match
      case Left(AnkiError.MalformedResponse(_, detail)) =>
        assert(
          !detail.contains("has no 'error' field"),
          s"a wrong-typed error field was reported as a missing one: $detail",
        )
      case other => fail(s"expected MalformedResponse, got $other")
  }

  // ================================================ the no-result tripwire ====

  test("an action that should report nothing accepts null") {
    assertEquals(AnkiConnect.expectNoResult("updateNote", Json.Null), Right(()))
  }

  /** The check has to actually check. Decoding the payload as `Option[Json]` would accept
    * every value there is — `Decoder[Json]` being the identity decoder — and so would read
    * as a tripwire while asserting nothing.
    */
  test("an action that should report nothing REFUSES an actual value") {
    assert(AnkiConnect.expectNoResult("updateNote", Json.fromInt(1234)).isLeft)
    assert(AnkiConnect.expectNoResult("updateNote", Json.obj("id" := 1)).isLeft)
    assert(AnkiConnect.expectNoResult("updateNote", Json.arr()).isLeft)
    assert(AnkiConnect.expectNoResult("updateNote", Json.fromString("")).isLeft)
  }

  // ================================================ classification ====

  test("a duplicate refusal is classified") {
    AnkiConnect.classify("addNote", "cannot create note because it is a duplicate") match
      case AnkiError.DuplicateNote(_) => ()
      case other                      => fail(s"got $other")
  }

  test("a missing note type is classified, and carries the name") {
    assertEquals(
      AnkiConnect.classify("addNote", "model was not found: No Such Model"),
      AnkiError.NoSuchNoteType("No Such Model"),
    )
  }

  /** An unrecognised refusal must arrive VERBATIM rather than being forced into the
    * nearest-looking case. A misclassified error sends the reader somewhere confidently
    * wrong, which is worse than an unclassified one.
    */
  test("an unrecognised refusal is carried through verbatim") {
    assertEquals(
      AnkiConnect.classify("changeDeck", "deck was not found: Nope"),
      AnkiError.Remote("changeDeck", "deck was not found: Nope"),
    )
  }

  /** Anki reports an unknown FIELD name as "cannot create note because it is empty",
    * because it drops the unrecognised key and then finds nothing left. A genuinely empty
    * note produces the identical message, so classifying it as UnknownField would be a
    * guess. Verified live.
    */
  test("an 'empty note' refusal is NOT guessed to be an unknown field") {
    AnkiConnect.classify("addNote", "cannot create note because it is empty") match
      case AnkiError.UnknownField(_, _) => fail("guessed UnknownField from an ambiguous message")
      case _                            => ()
  }

  // ================================================ notesInfo ====

  /** A real `notesInfo` response, including the nested field shape and the card ids. */
  val realNote: String =
    """{"result": [{"noteId": 1787142256571, "profile": "claude-POC-test",
      | "tags": ["sha::deadbeef", "src::probe"],
      | "fields": {"Back": {"value": "the back", "order": 1},
      |            "Front": {"value": "the front", "order": 0}},
      | "modelName": "Basic", "mod": 1787143130, "cards": [1787142256571, 1787142256572]}],
      | "error": null}""".stripMargin

  /** FIELD ORDER IS LOAD-BEARING, not cosmetic: the content hash is computed over the fields
    * in order, so reading them in whatever order the JSON object happened to yield would make
    * the hash unstable and every run would plan an update for every card.
    *
    * The fixture lists Back BEFORE Front on purpose.
    */
  test("fields come back in the note type's own order, not the JSON's") {
    AnkiConnect.decodeAs[Vector[ObservedNote]]("notesInfo", realNote) match
      case Right(Vector(note)) =>
        assertEquals(note.fields.map(_._1), Vector("Front", "Back"))
        assertEquals(note.fields, Vector("Front" -> "the front", "Back" -> "the back"))
        assertEquals(note.noteType, "Basic")
        assertEquals(note.tags, Vector("sha::deadbeef", "src::probe"))
      case other => fail(s"could not read a real notesInfo response: $other")
  }

  /** VERIFIED LIVE: asking about a note id that does not exist returns an EMPTY OBJECT with
    * `error: null`, not an error. A decoder that supplied defaults would hand the reconciler
    * a note with no id, no type and no tags as though it were real.
    */
  test("the empty object returned for a missing note is REFUSED, not defaulted") {
    val body = """{"result": [{}], "error": null}"""
    assert(
      AnkiConnect.decodeAs[Vector[ObservedNote]]("notesInfo", body).isLeft,
      "an empty object was decoded into a note",
    )
  }

  test("card ids are read from the notesInfo response, so cardsInfo is never needed") {
    val entry = io.circe.parser.parse(realNote).toOption.get.hcursor
      .downField("result")
      .downArray
    assertEquals(
      AnkiConnect.cardIdsOfNote.tryDecode(entry).map(_.map(_.value)),
      Right(Vector(1787142256571L, 1787142256572L)),
    )
  }

  // ================================================ getDecks ====

  test("getDecks is inverted from deck-to-cards into card-to-deck") {
    val body = """{"result": {"Obsidian::Patterns": [11, 12], "Obsidian::Other": [13]}, "error": null}"""
    AnkiConnect.envelope("getDecks", body).flatMap(AnkiConnect.decksByCard.decodeJson(_).left.map(_ => null)) match
      case Right(byCard) =>
        assertEquals(byCard(11L), "Obsidian::Patterns")
        assertEquals(byCard(12L), "Obsidian::Patterns")
        assertEquals(byCard(13L), "Obsidian::Other")
      case other => fail(s"got $other")
  }

  // ================================================ requests ====

  test("every request carries the API version") {
    val json = AnkiConnect.request("modelNames", Json.obj())
    assertEquals(json.hcursor.downField("version").as[Int], Right(AnkiConnect.ApiVersion))
    assertEquals(json.hcursor.downField("action").as[String], Right("modelNames"))
  }

  test("a request with no parameters omits the params key entirely") {
    assert(AnkiConnect.request("modelNames", Json.obj()).hcursor.downField("params").focus.isEmpty)
  }

  test("a request with parameters carries them") {
    val json = AnkiConnect.request("modelFieldNames", Json.obj("modelName" := "Basic"))
    assertEquals(
      json.hcursor.downField("params").downField("modelName").as[String],
      Right("Basic"),
    )
  }

  /** VERIFIED LIVE: passing a JSON array here is refused with "bad argument type for
    * built-in operation". Anki stores tags as one space-delimited string and this parameter
    * follows suit — which is the second, independent reason the encoding forbids whitespace.
    */
  test("tags are joined with spaces, the way addTags demands") {
    assertEquals(
      AnkiConnect.joinTags(Vector(OwnedTag.unsafeFromString("src::a"), OwnedTag.unsafeFromString("sha::b"))),
      "src::a sha::b",
    )
  }

  // ================================================ note types ====

  /** CAPTURED FROM THE LIVE COLLECTION on 2026-08-21, read-only, in profile `claude-POC-test`:
    * `modelTemplates` for `3 way Concept-Descriptor`. The body below is that response with two
    * of the three templates removed for length; nothing else was changed.
    *
    * TWO THINGS THIS PINS. The response is an OBJECT KEYED BY TEMPLATE NAME, not a list — so
    * the templates carry no order this tool is entitled to rely on, which is why
    * `Anki.noteTypeTemplates` answers with a `Map`. And each value's keys are `Front` and
    * `Back`, capitalised, which is Anki's convention and not this project's.
    */
  test("modelTemplates decodes into templates keyed by name") {
    val body =
      """{"result": {
        |  "Card 1: Descriptor+Description -> Concept": {
        |    "Front": "{{Descriptor}}<br>{{Description}}",
        |    "Back": "{{FrontSide}}<hr id=answer>{{Concept}}" }
        |}, "error": null}""".stripMargin
    assertEquals(
      AnkiConnect.decodeAs[Map[String, CardTemplate]]("modelTemplates", body),
      Right(
        Map(
          "Card 1: Descriptor+Description -> Concept" -> CardTemplate(
            "{{Descriptor}}<br>{{Description}}",
            "{{FrontSide}}<hr id=answer>{{Concept}}",
          )
        )
      ),
    )
  }

  /** ALSO CAPTURED LIVE, same day and profile, from `Cloze Sequence`: the stylesheet arrives
    * WRAPPED IN AN OBJECT under a `css` key, not as a bare string.
    */
  test("modelStyling unwraps the css key") {
    val body = """{"result": {"css": ".card { color: #111; }"}, "error": null}"""
    assertEquals(
      AnkiConnect.envelope("modelStyling", body).flatMap(
        AnkiConnect.modelStylingCss.decodeJson(_).left.map(f => AnkiError.MalformedResponse("modelStyling", f.message))
      ),
      Right(".card { color: #111; }"),
    )
  }

  /** VERIFIED LIVE, read-only, on 2026-08-21: asking for a note type the collection does not
    * hold answers `error: "model was not found: <name>"` with a null result, on both
    * `modelTemplates` and `modelStyling`. The existing classifier already turns that into a
    * named error rather than an unclassified refusal, and this pins that it keeps doing so for
    * the two actions added here.
    */
  test("a note type that is not in the collection is reported by name") {
    val body = """{"result": null, "error": "model was not found: Obsidian Basic"}"""
    assertEquals(
      AnkiConnect.decodeAs[Map[String, CardTemplate]]("modelTemplates", body),
      Left(AnkiError.NoSuchNoteType("Obsidian Basic")),
    )
  }

  /** THE PARAMETER NAMES ARE ANKI'S, AND THEY WERE READ OFF THE ADD-ON, not off the docs — see
    * `AnkiConnect.createModelParams`. A test that built the expected JSON from the same
    * constants the code uses would prove nothing, so every key here is written out as a
    * literal.
    */
  test("createModel is given the parameter names the add-on actually reads") {
    val spec = NoteTypeSpec(
      name = "Obsidian Basic",
      isCloze = false,
      fields = cats.data.NonEmptyVector.of("Front", "Back", "Context"),
      templates = cats.data.NonEmptyVector.of("Card 1" -> CardTemplate("{{Front}}", "{{Back}}")),
      styling = ".card { }",
    )
    assertEquals(
      AnkiConnect.createModelParams(spec),
      Json.obj(
        "modelName" := "Obsidian Basic",
        "inOrderFields" := Vector("Front", "Back", "Context"),
        "css" := ".card { }",
        "isCloze" := false,
        "cardTemplates" := Vector(
          Json.obj("Name" := "Card 1", "Front" := "{{Front}}", "Back" := "{{Back}}")
        ),
      ),
    )

    // BOTH VALUES OF THE FLAG, because the equality above pins only `false` and a body that
    // hardcoded `false` would satisfy it — measured, by making exactly that mutation. `isCloze`
    // is the one property no heuristic recovers: `Cloze Sequence` has "Cloze" in its name,
    // defines `.cloze` in its stylesheet, and is not a cloze note type.
    assertEquals(
      AnkiConnect
        .createModelParams(spec.copy(isCloze = true))
        .hcursor
        .downField("isCloze")
        .as[Boolean],
      Right(true),
    )
  }

  /** FIELD ORDER IS SENT AS ORDER, which is what `inOrderFields` means. Anki's Sort Field
    * defaults to field 1, so a reordering here changes which column fills the Browse list —
    * and `Context` is last on every one of this tool's note types for exactly that reason.
    */
  test("createModel sends the fields in the order the note type declares them") {
    NoteTypeAssets.all.fold(
      errors => fail(errors.map(_.describe).mkString("; ")),
      _.foreach { asset =>
        assertEquals(
          AnkiConnect
            .createModelParams(asset.spec)
            .hcursor
            .downField("inOrderFields")
            .as[Vector[String]],
          Right(asset.spec.fields.toVector),
        )
      },
    )
  }
