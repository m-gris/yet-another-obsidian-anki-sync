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
  // ------------------------------------------ what a card is worth ----

  /** SEEDS A NOTE HOLDING SEVERAL CARDS, which `State.seed` cannot do — it makes one card,
    * because until now nothing needed a note with more than one. A price is about the cards a
    * narrowing leaves behind, so a single-card note cannot exercise it at all.
    */
  def seedWithCards(state: FakeAnkiConnect.State, howMany: Int): (Long, Vector[Long]) =
    val note  = state.fresh()
    val cards = Vector.fill(howMany)(state.fresh())
    state.notes += note -> FakeAnkiConnect.Note("Obsidian Concept-Descriptor", Vector.empty, Vector.empty, cards)
    (note, cards)

  test("a card's standing is its position in its note and the reviews it carries") {
    val (state, anki) = fixture
    val (_, cards)    = seedWithCards(state, 3)
    state.reviews += cards(0) -> 9
    state.reviews += cards(1) -> 4
    state.reviews += cards(2) -> 1

    assertEquals(
      anki.standingOf(cards.map(AnkiCardId.apply)).get,
      Vector(
        CardStanding(AnkiCardId(cards(0)), 0, 9),
        CardStanding(AnkiCardId(cards(1)), 1, 4),
        CardStanding(AnkiCardId(cards(2)), 2, 1),
      ),
      "each card must come back with its own ordinal and its own review count, in the order asked",
    )
  }

  /** THE FOOTGUN THIS EXISTS TO CATCH, and it was MEASURED rather than imagined. AnkiConnect
    * answers `{}` for a card that is not in the collection, and does NOT report an error. An
    * implementation that shrugged at that would price a destructive change at ZERO REVIEWS —
    * the single answer that makes destroying somebody's cards look free.
    *
    * SO THE REFUSAL IS THE FEATURE. A card the plan believes in and the collection does not is
    * a disagreement, and this must be the loudest thing that happens rather than the quietest.
    */
  test("a card the collection does not have is refused, not priced at zero") {
    val (state, anki) = fixture
    val (_, cards)    = seedWithCards(state, 2)
    val ghost         = AnkiCardId(999999L)

    val refusal = anki.standingOf(cards.map(AnkiCardId.apply) :+ ghost).refusal
    assert(
      refusal.toString.contains("999999"),
      s"the refusal must name the card that is missing, or nobody can act on it: $refusal",
    )
  }

  /** ASSERTED AS AN OUTCOME, NOT AS AN INTERACTION. The first draft of this checked that no
    * `cardsInfo` call was made, which this fake's own docstring rules out: interaction
    * assertions pin the interpreter's current shape, whereas these pin Anki's behaviour.
    */
  test("asking about no cards answers with no standings") {
    val (_, anki) = fixture
    assertEquals(anki.standingOf(Vector.empty).get, Vector.empty)
  }

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
    assertEquals(anki.ownedNotes.get.size, 1)
  }

  test("notes are found by tag prefix, and read back with fields in order") {
    val (state, anki) = fixture
    state.seed("Basic", Vector("Front" -> "f", "Back" -> "b"), Vector("src::x"), "Obsidian::Patterns")
    state.seed("Basic", Vector("Front" -> "g"), Vector("unrelated::y"), "Obsidian::Patterns")

    val ids = anki.ownedNotes.get
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

  // ================================================ moving a note between types ====

  /** The read that decides whether a move is safe at all, over the wire.
    *
    * `Obsidian Cloze` IS A CLOZE TYPE AND `Obsidian Cloze Sequence` IS NOT, which is exactly
    * the pair no name-based or stylesheet-based heuristic gets right — the second has "Cloze"
    * in its name and `.cloze` in its stylesheet. Both are read here from the shipped
    * definitions, so a manifest that flipped either flag fails here as well as in
    * `anki/NoteTypeAssets.test.scala`.
    */
  test("whether a note type is cloze survives the wire, for the pair that defeats every heuristic") {
    val (_, anki) = fixture
    assertEquals(anki.noteTypeIsCloze("Obsidian Cloze").get, true)
    assertEquals(anki.noteTypeIsCloze("Obsidian Cloze Sequence").get, false)
    assertEquals(anki.noteTypeIsCloze("Nope").refusal, AnkiError.NoSuchNoteType("Nope"))
  }

  /** THE MIGRATION WRITE, over the wire and against the fake's transcription of the add-on.
    *
    * Three things are asserted on the collection because Anki destroys all three: the note's
    * type, its fields — every field of the NEW type, since they are all blanked first — and its
    * whole tag set, which is replaced rather than merged.
    *
    * THE FOREIGN TAG IS THE POINT OF THE `preservedTags` ARGUMENT. `leech` is applied by
    * Anki's own scheduler and is not the tool's to invent; here it is read off the note and
    * handed back, which is the only way it survives a call that replaces the tag set outright.
    */
  test("a note moves onto another note type, keeping the tags it was handed and nothing else") {
    val (state, anki) = fixture
    val id = state.seed(
      "Basic",
      Vector("Front" -> "Term", "Back" -> "definition"),
      Vector("src::n1::term", "sha::deadbeef", "leech"),
      "Obsidian::Patterns",
    )

    anki
      .changeNoteType(
        AnkiNoteId(id),
        "Obsidian Basic",
        Vector(
          "Front" -> "Term", "Back" -> "definition", "Context" -> "Coupling", "SameShape" -> "",
          "Identity" -> "src::n1::term",
        ),
        NonEmptyVector.of(tag("src::n1::term"), tag("sha::feedface")),
        preservedTags = Vector("leech"),
      )
      .get

    val moved = state.notes(id)
    assertEquals(moved.model, "Obsidian Basic")
    assertEquals(
      moved.fields,
      Vector(
        "Front" -> "Term", "Back" -> "definition", "Context" -> "Coupling", "SameShape" -> "",
        "Identity" -> "src::n1::term",
      ),
    )
    assertEquals(moved.tags.sorted, Vector("leech", "sha::feedface", "src::n1::term"))
    assert(!moved.tags.contains("sha::deadbeef"), s"the stale hash survived: ${moved.tags}")
  }

  /** THE TRAP THIS OPERATION EXISTS AROUND, asserted rather than described: a field the new
    * note type does not declare is dropped SILENTLY, and one it declares but the call does not
    * name is left EMPTY. Neither produces an error.
    *
    * This is why `sync` refuses before it writes when a note type lacks a field the tool
    * writes: at this layer there is nothing left to notice it.
    */
  test("a move silently ignores an unknown field name and silently empties an unnamed one") {
    val (state, anki) = fixture
    val id = state.seed("Basic", Vector("Front" -> "Term", "Back" -> "definition"), Vector("src::n1::term"), "Default")

    anki
      .changeNoteType(
        AnkiNoteId(id),
        "Obsidian Basic",
        Vector("Front" -> "Term", "Contxet" -> "typo"),
        NonEmptyVector.one(tag("src::n1::term")),
        preservedTags = Vector.empty,
      )
      .get

    assertEquals(
      state.notes(id).fields,
      Vector("Front" -> "Term", "Back" -> "", "Context" -> "", "SameShape" -> "", "Identity" -> ""),
      "the wire fake did not reproduce blank-then-fill, so nothing here would catch the trap",
    )
  }

  test("a move onto a note type the collection does not have is refused, naming it in Anki's own words") {
    val (state, anki) = fixture
    val id = state.seed("Basic", Vector("Front" -> "f"), Vector("src::n1::a"), "Default")
    anki
      .changeNoteType(
        AnkiNoteId(id),
        "No Such Note Type",
        Vector("Front" -> "f"),
        NonEmptyVector.one(tag("src::n1::a")),
        Vector.empty,
      )
      .refusal match
      case AnkiError.Remote("updateNoteModel", message) =>
        assert(message.contains("No Such Note Type"), s"the refusal does not name it: $message")
      case other => fail(s"expected a remote refusal, got $other")

    assertEquals(state.notes(id).model, "Basic", "a refused move changed the note anyway")
  }
