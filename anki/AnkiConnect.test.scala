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
