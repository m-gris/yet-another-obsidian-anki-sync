package obsidiananki.anki

import cats.data.NonEmptyVector
import cats.effect.IO
import cats.effect.unsafe.implicits.global
import obsidiananki.model.OwnedTag
import org.http4s.Uri
import org.http4s.client.Client

/** The AnkiConnect interpreter, driven against a fake AnkiConnect server in process.
  *
  * No socket, no running Anki, and no mocks: http4s' `Client.fromHttpApp` wires the real
  * interpreter to [[FakeAnkiConnect]], which reproduces the behaviours observed on a live
  * collection — including the ones that destroy data. So these tests exercise the actual
  * request bodies and the actual decoders, and assert on what the collection ENDS UP
  * HOLDING.
  */
class AnkiConnectClientTest extends munit.FunSuite:

  val deck: DeckPath = DeckPath(NonEmptyVector.of("Obsidian", "Patterns"))

  def fixture: (FakeAnkiConnect.State, AnkiConnectClient[IO]) =
    val state  = FakeAnkiConnect.State()
    val client = Client.fromHttpApp(FakeAnkiConnect.app(state))
    (state, AnkiConnectClient[IO](client, Uri.unsafeFromString("http://localhost:8765")))

  extension [A](result: cats.data.EitherT[IO, AnkiError, A])
    def get: A =
      result.value.unsafeRunSync() match
        case Right(a) => a
        case Left(e)  => fail(s"unexpected refusal: $e")
    def refusal: AnkiError =
      result.value.unsafeRunSync() match
        case Left(e)  => e
        case Right(a) => fail(s"expected a refusal, got $a")

  def tag(s: String): OwnedTag = OwnedTag.unsafeFromString(s)

  // ================================================ creating ====

  /** The deck must be created first, because `addNote` refuses a missing one. If the
    * interpreter skipped that step this fails with "deck was not found".
    */
  test("a note is created in a deck that did not exist yet") {
    val (state, anki) = fixture
    val id = anki
      .addNote(
        NewNote("Basic", deck, Vector("Front" -> "f", "Back" -> "b"), NonEmptyVector.one(tag("src::x")))
      )
      .get

    assertEquals(state.notes(id.value).fields, Vector("Front" -> "f", "Back" -> "b"))
    assertEquals(state.notes(id.value).tags, Vector("src::x"))
    assert(state.decks.contains("Obsidian::Patterns"), "the deck was not created")
  }

  test("the identity tag is present from the moment the note exists") {
    val (state, anki) = fixture
    val id = anki
      .addNote(NewNote("Basic", deck, Vector("Front" -> "f"), NonEmptyVector.of(tag("src::a"), tag("sha::b"))))
      .get
    assertEquals(state.notes(id.value).tags.sorted, Vector("sha::b", "src::a"))
  }

  // ================================================ THE TAG-LOSS TRAPS ====

  /** THE most expensive finding in this project, as a test.
    *
    * `updateNote` given a `tags` key replaces the note's whole tag set. `leech` is not a
    * user tag — Anki's scheduler applies it — so an interpreter that wrote tags through this
    * action would silently undo the scheduler's own decision on every sync.
    *
    * Asserted on the OUTCOME: the foreign tags are still there afterwards.
    */
  test("updating fields leaves tags the tool does not own completely untouched") {
    val (state, anki) = fixture
    val id = state.seed(
      "Basic",
      Vector("Front" -> "f", "Back" -> "old"),
      Vector("src::x", "sha::aaaa", "leech", "marc-put-this-here"),
      "Obsidian::Patterns",
    )

    anki.updateNoteFields(AnkiNoteId(id), Vector("Front" -> "f", "Back" -> "new")).get

    assertEquals(state.notes(id).fields, Vector("Front" -> "f", "Back" -> "new"))
    assertEquals(
      state.notes(id).tags.sorted,
      Vector("leech", "marc-put-this-here", "sha::aaaa", "src::x"),
      "a field update destroyed tags — including Anki's own scheduler state",
    )
  }

  /** The other half: our own tags are changed surgically, and only ours. */
  test("a content hash is replaced without disturbing any other tag") {
    val (state, anki) = fixture
    val id = state.seed(
      "Basic",
      Vector("Front" -> "f"),
      Vector("src::x", "sha::old", "leech"),
      "Obsidian::Patterns",
    )

    anki.removeTags(Vector(AnkiNoteId(id)), Vector(tag("sha::old"))).get
    anki.addTags(Vector(AnkiNoteId(id)), Vector(tag("sha::new"))).get

    assertEquals(state.notes(id).tags.sorted, Vector("leech", "sha::new", "src::x"))
  }

  /** Tags reach `addTags` as one space-delimited string. Passing an array is refused by
    * Anki outright, so this failing would be a hard error rather than a silent one — but it
    * is the reason the tag encoding forbids whitespace, and worth pinning.
    */
  test("several tags are applied in ONE call, space-delimited") {
    val (state, anki) = fixture
    val id = state.seed("Basic", Vector("Front" -> "f"), Vector("src::x"), "Obsidian::Patterns")

    anki.addTags(Vector(AnkiNoteId(id)), Vector(tag("a::1"), tag("b::2"))).get

    assertEquals(state.notes(id).tags.sorted, Vector("a::1", "b::2", "src::x"))
  }

  // ================================================ reading ====

  /** THE SEARCH STRING IS THE WHOLE RECONCILER'S ONE ENUMERATION, so its shape is pinned
    * rather than left to the fake's tolerance.
    *
    * Against real Anki a query without the `tag:` qualifier is a free-text search over
    * FIELDS and cannot match a tag: it would return nothing, every card would look absent,
    * and since identity lookup runs off this call the entire vault would be re-created —
    * accepted, because `allowDuplicate` is set. The fake refuses any query that is not a
    * tag-prefix search, so that mutation fails here instead.
    */
  test("the enumeration searches by TAG prefix, with the wildcard, not by free text") {
    val (state, anki) = fixture
    state.seed("Basic", Vector("Front" -> "f"), Vector("src::x"), "Obsidian::Patterns")
    // Reaches the fake's strict query check; a query of the wrong shape is refused there.
    assertEquals(anki.findNotesByTagPrefix("src::").get.size, 1)
  }

  test("notes are found by tag prefix, and read back with fields in order") {
    val (state, anki) = fixture
    state.seed("Basic", Vector("Front" -> "f", "Back" -> "b"), Vector("src::x"), "Obsidian::Patterns")
    state.seed("Basic", Vector("Front" -> "g"), Vector("unrelated::y"), "Obsidian::Patterns")

    val ids = anki.findNotesByTagPrefix("src::").get
    assertEquals(ids.size, 1, "the tag prefix search matched the wrong set")

    val notes = anki.notesInfo(ids).get
    assertEquals(notes.head.fields, Vector("Front" -> "f", "Back" -> "b"))
    assertEquals(notes.head.tags, Vector("src::x"))
  }

  /** VERIFIED LIVE: an unknown note id comes back as an empty object with no error. Read
    * leniently that becomes a note with no id and no tags, handed to the reconciler as real.
    */
  test("a note that no longer exists is a REFUSAL, not a blank note") {
    val (_, anki) = fixture
    anki.notesInfo(Vector(AnkiNoteId(424242L))).refusal match
      case AnkiError.MalformedResponse(_, _) => ()
      case other                             => fail(s"expected a refusal, got $other")
  }

  test("card ids are read without ever calling cardsInfo") {
    val (state, anki) = fixture
    val id = state.seed("Basic", Vector("Front" -> "f"), Vector("src::x"), "Obsidian::Patterns")
    // The fake would answer `cardsInfo` with a poisoned null for this card. Reaching it at
    // all would therefore fail this test rather than return the ids.
    state.corruptCards += state.notes(id).cards.head

    assertEquals(anki.cardsOf(Vector(AnkiNoteId(id))).get.map(_.value), state.notes(id).cards)
  }

  test("a card's deck is read back as a path") {
    val (state, anki) = fixture
    val id   = state.seed("Basic", Vector("Front" -> "f"), Vector("src::x"), "Obsidian::Patterns")
    val card = AnkiCardId(state.notes(id).cards.head)

    assertEquals(anki.deckOf(card).get.map(_.render), Some("Obsidian::Patterns"))
  }

  test("moving cards to a new deck creates it") {
    val (state, anki) = fixture
    val id     = state.seed("Basic", Vector("Front" -> "f"), Vector("src::x"), "Obsidian::Patterns")
    val card   = AnkiCardId(state.notes(id).cards.head)
    val target = DeckPath(NonEmptyVector.of("Obsidian", "Moved"))

    anki.changeDeck(Vector(card), target).get

    assertEquals(anki.deckOf(card).get.map(_.render), Some("Obsidian::Moved"))
  }

  // ================================================ refusals ====

  test("a refusal names the action and carries Anki's own words") {
    val (_, anki) = fixture
    anki.updateNoteFields(AnkiNoteId(999L), Vector("Front" -> "x")).refusal match
      case AnkiError.Remote("updateNote", message) =>
        assert(message.contains("999"), s"the message lost the note id: $message")
      case other => fail(s"expected a Remote refusal, got $other")
  }

  test("a missing note type is refused by name") {
    val (_, anki) = fixture
    assertEquals(anki.fieldNames("No Such Model").refusal, AnkiError.NoSuchNoteType("No Such Model"))
  }

  /** An empty batch must not become a request at all — the reconciler calls these with
    * whatever the scan produced, and an empty vault is an ordinary case rather than an error.
    */
  test("empty batches make no request and answer emptily") {
    val (_, anki) = fixture
    assertEquals(anki.notesInfo(Vector.empty).get, Vector.empty)
    assertEquals(anki.cardsOf(Vector.empty).get, Vector.empty)
    anki.addTags(Vector.empty, Vector(tag("a::b"))).get
    anki.changeDeck(Vector.empty, deck).get
  }

  // ================================================ note types ====

  /** THE ROUND TRIP, through the real request bodies and the real decoders.
    *
    * WHAT IT CATCHES that a pure test on `createModelParams` cannot: the fake server reads
    * `modelName`, `inOrderFields`, `cardTemplates`, `css` and `isCloze` — the parameter names
    * transcribed from the installed add-on's own source — so a client sending any of them under
    * a different name creates a note type with no fields, no templates, or the wrong name, and
    * the read-back below disagrees.
    *
    * A FRESH PROFILE, deliberately. `FakeAnkiConnect.State` starts with this tool's five note
    * types already installed, which is what every other test here assumes; emptying it is what
    * makes this the case the slice exists for.
    */
  test("a note type is created and reads back with the fields, templates and css it was given") {
    val (state, anki) = fixture
    state.models = Map.empty

    val spec = NoteTypeSpec(
      name = "Obsidian Basic",
      isCloze = false,
      fields = NonEmptyVector.of("Front", "Back", "Context"),
      templates = NonEmptyVector.of(
        "Card 1" -> CardTemplate("{{Front}}", "{{FrontSide}}<hr id=answer>{{Back}}")
      ),
      styling = ".card { font-size: 20px; }",
    )

    assertEquals(anki.noteTypeNames.get, Vector.empty)
    anki.createNoteType(spec).get

    assertEquals(anki.noteTypeNames.get, Vector("Obsidian Basic"))
    assertEquals(anki.fieldNames("Obsidian Basic").get, Vector("Front", "Back", "Context"))
    assertEquals(anki.noteTypeStyling("Obsidian Basic").get, ".card { font-size: 20px; }")
    assertEquals(
      anki.noteTypeTemplates("Obsidian Basic").get,
      Map("Card 1" -> CardTemplate("{{Front}}", "{{FrontSide}}<hr id=answer>{{Back}}")),
    )
  }

  /** `isCloze` MUST SURVIVE THE WIRE. It is the one flag no heuristic gets right — `Cloze
    * Sequence` has "Cloze" in its name, defines `.cloze` in its stylesheet, and is not a cloze
    * note type — so a client that dropped it would install the wrong kind of note type while
    * every other assertion still passed.
    */
  test("isCloze reaches the collection") {
    val (state, anki) = fixture
    state.models = Map.empty
    val base = NoteTypeSpec(
      "X",
      isCloze = false,
      NonEmptyVector.of("Text"),
      NonEmptyVector.of("Cloze" -> CardTemplate("{{cloze:Text}}", "{{cloze:Text}}")),
      "",
    )
    anki.createNoteType(base.copy(name = "Plain", isCloze = false)).get
    anki.createNoteType(base.copy(name = "Clozed", isCloze = true)).get
    assertEquals(state.models("Plain").isCloze, false)
    assertEquals(state.models("Clozed").isCloze, true)
  }

  /** `createModel` IS NOT AN UPSERT, and the refusal is re-labelled with the name the request
    * asked for — Anki's own message is `Model name already exists` and does not say which.
    *
    * This is what keeps the two interpreters of this algebra agreeing: `InMemoryAnki` raises
    * the same case for the same situation.
    */
  test("creating a note type that already exists is refused, and the refusal names it") {
    val (_, anki) = fixture
    val spec = NoteTypeSpec(
      name = "Obsidian Basic",
      isCloze = false,
      fields = NonEmptyVector.of("Front"),
      templates = NonEmptyVector.of("Card 1" -> CardTemplate("{{Front}}", "{{Front}}")),
      styling = "",
    )
    assertEquals(anki.createNoteType(spec).refusal, AnkiError.NoteTypeExists("Obsidian Basic"))
  }

  test("asking for the templates or the styling of a note type that is not there names it") {
    val (_, anki) = fixture
    assertEquals(anki.noteTypeTemplates("Nope").refusal, AnkiError.NoSuchNoteType("Nope"))
    assertEquals(anki.noteTypeStyling("Nope").refusal, AnkiError.NoSuchNoteType("Nope"))
  }
