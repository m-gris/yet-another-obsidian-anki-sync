package obsidiananki.anki

import cats.effect.IO
import io.circe.{Json, JsonObject}
import io.circe.syntax.*
import org.http4s.{HttpApp, Response, Status}
import org.http4s.circe.{jsonDecoder, jsonEncoder}

/** A fake AnkiConnect SERVER, answering over http4s' in-process client.
  *
  * A FAKE, NOT A MOCK, and the distinction is the whole design. Tests against it assert on
  * OUTCOMES — what tags a note ends up with, what fields it holds — never on which HTTP
  * calls were made. Interaction assertions would pin the interpreter's current shape in
  * place; these pin Anki's behaviour instead, which is the thing that must not be violated.
  *
  * EVERY BEHAVIOUR BELOW WAS OBSERVED ON A LIVE ANKI. That is what gives the fake its
  * teeth: it reproduces the traps rather than the happy path, so an interpreter that falls
  * into one fails here instead of in someone's collection.
  *
  *   - `updateNote` given a `tags` key REPLACES the whole tag set, destroying the human's
  *     tags and Anki's own `leech` flag along with them.
  *   - `updateNote` with NO `tags` key leaves tags untouched.
  *   - `updateNoteFields` accepts a `tags` parameter and SILENTLY DISCARDS it — the action
  *     this tool must never use, kept here precisely so that using it fails a test.
  *   - `addNote` REFUSES a deck that does not exist, while `changeDeck` creates one.
  *   - `addTags` requires a space-delimited STRING; an array is refused.
  *   - `notesInfo` answers for an unknown id with an EMPTY OBJECT and no error.
  *   - `cardsInfo` returns null for the WHOLE batch when any card is corrupt.
  */
object FakeAnkiConnect:

  final case class Note(
      model: String,
      fields: Vector[(String, String)],
      tags: Vector[String],
      cards: Vector[Long],
  )

  final class State:
    var profile: String         = "claude-POC-test"

    /** THE NOTE TYPES THIS COLLECTION HOLDS, state of their own since 2026-08-21.
      *
      * Before that, `modelNames` was derived from the models named by existing NOTES and
      * `modelFieldNames` from the first note using one. That was a fiction with a consequence:
      * an empty collection appeared to hold no note types at all, and a note type could never
      * be asked what it looked like until a note already used it. Neither is true of Anki, and
      * both matter now that the tool creates note types and checks for them before writing.
      *
      * DEFAULTS TO THIS TOOL'S FIVE, ALREADY INSTALLED, which is what every pre-existing test
      * here implicitly assumed. A test wanting a FRESH profile sets this to `Map.empty`.
      */
    var models: Map[String, NoteTypeSpec] = InMemoryAnki.defaultNoteTypes

    var notes: Map[Long, Note]  = Map.empty
    var decks: Set[String]      = Set("Default")
    var cardDeck: Map[Long, String] = Map.empty
    var corruptCards: Set[Long] = Set.empty
    private var next: Long      = 5000L
    def fresh(): Long = { next += 1; next }

    /** Seed a note directly, bypassing the wire, for tests that need a starting state. */
    def seed(model: String, fields: Vector[(String, String)], tags: Vector[String], deck: String): Long =
      val id   = fresh()
      val card = fresh()
      notes += id -> Note(model, fields, tags, Vector(card))
      decks += deck
      cardDeck += card -> deck
      id

  private def ok(result: Json): Json  = Json.obj("result" := result, "error" := Json.Null)
  private def err(message: String): Json =
    Json.obj("result" := Json.Null, "error" := message)

  /** Anki reports a note's fields as `{name: {value, order}}`. */
  private def renderFields(fields: Vector[(String, String)]): Json =
    Json.obj(fields.zipWithIndex.map { case ((name, value), i) =>
      name := Json.obj("value" := value, "order" := i)
    }*)

  private def fieldsFrom(obj: JsonObject): Vector[(String, String)] =
    obj.keys.toVector.flatMap(k => obj(k).flatMap(_.asString).map(k -> _))

  /** The `modelName` parameter resolved against the collection, or Anki's own refusal. */
  private def withModel(state: State, params: io.circe.HCursor)(
      answer: (String, NoteTypeSpec) => Json
  ): Json =
    val name = params.downField("modelName").as[String].getOrElse("")
    state.models.get(name) match
      case Some(spec) => answer(name, spec)
      case None       => err(s"model was not found: $name")

  def app(state: State): HttpApp[IO] = HttpApp[IO] { request =>
    request.as[Json].map { body =>
      val cursor = body.hcursor
      val action = cursor.downField("action").as[String].getOrElse("")
      val params = cursor.downField("params")
      Response[IO](Status.Ok).withEntity(dispatch(state, action, params.focus.getOrElse(Json.obj())))
    }
  }

  private def dispatch(state: State, action: String, params: Json): Json =
    val p = params.hcursor
    action match

      case "getActiveProfile" =>
        ok(state.profile.asJson)

      case "modelNames" =>
        ok(state.models.keys.toVector.sorted.asJson)

      // VERIFIED LIVE 2026-08-21, read-only: a model the collection does not hold is refused
      // with exactly `model was not found: <name>`, on all three of the actions below.
      case "modelFieldNames" =>
        withModel(state, p) { (_, spec) => ok(spec.fields.toVector.asJson) }

      /** VERIFIED LIVE 2026-08-21, read-only, against `3 way Concept-Descriptor`: an OBJECT
        * KEYED BY TEMPLATE NAME whose values carry `Front` and `Back`, capitalised. Not a list
        * — which is why nothing in this tool reads a card ordinal out of this response.
        */
      case "modelTemplates" =>
        withModel(state, p) { (_, spec) =>
          ok(
            Json.obj(
              spec.templates.toVector.map { (name, template) =>
                name := Json.obj("Front" := template.front, "Back" := template.back)
              }*
            )
          )
        }

      /** VERIFIED LIVE 2026-08-21, read-only, against `Cloze Sequence`: the stylesheet arrives
        * WRAPPED under a `css` key rather than as a bare string.
        */
      case "modelStyling" =>
        withModel(state, p) { (_, spec) => ok(Json.obj("css" := spec.styling)) }

      /** READ OUT OF THE INSTALLED ADD-ON'S SOURCE, not exercised — creating a model is a
        * write, and the session that added this had read-only access to a live collection.
        * `__init__.py:1120` declares the parameter names used below; `:1122-1127` raise on an
        * empty field list, an empty template list, and a name that already exists;
        * `:1149-1151` substitute `Card N` when a template carries no `Name`; `:1155-1156` read
        * `card['Front']` and `card['Back']`.
        *
        * THE PARAMETER NAMES ARE TRANSCRIBED FROM THAT SOURCE RATHER THAN FROM THE CLIENT, so
        * that a client sending `fields` instead of `inOrderFields` fails here.
        */
      case "createModel" =>
        val name = p.downField("modelName").as[String].getOrElse("")
        val fields = p.downField("inOrderFields").as[Vector[String]].getOrElse(Vector.empty)
        val templates = p
          .downField("cardTemplates")
          .as[Vector[Json]]
          .getOrElse(Vector.empty)
          .map { card =>
            val c = card.hcursor
            (
              c.downField("Name").as[String].getOrElse("Card 1"),
              CardTemplate(
                c.downField("Front").as[String].getOrElse(""),
                c.downField("Back").as[String].getOrElse(""),
              ),
            )
          }
        if fields.isEmpty then err("Must provide at least one field for inOrderFields")
        else if templates.isEmpty then err("Must provide at least one card for cardTemplates")
        else if state.models.contains(name) then err("Model name already exists")
        else
          val spec = NoteTypeSpec(
            name = name,
            isCloze = p.downField("isCloze").as[Boolean].getOrElse(false),
            fields = cats.data.NonEmptyVector.fromVectorUnsafe(fields),
            templates = cats.data.NonEmptyVector.fromVectorUnsafe(templates),
            styling = p.downField("css").as[String].getOrElse(""),
          )
          state.models += name -> spec
          // The add-on returns the model it created, not null.
          ok(Json.obj("name" := name))

      /** STRICT ABOUT THE QUERY, because a lenient fake here pins nothing.
        *
        * Reducing the search with `stripPrefix("tag:").stripSuffix("*")` accepts `tag:src::*`,
        * `tag:src::` and bare `src::*` as the same thing — both strips being silent identity
        * when the affix is absent. That leaves the interpreter free to send a query with no
        * `tag:` qualifier, which against real Anki is a free-text search over FIELDS and can
        * never match a tag. It would return nothing, every card would look absent, and
        * because identity lookup is driven off this one enumeration every card in the vault
        * would be re-created — accepted, since `allowDuplicate` is set. The suite would stay
        * green throughout.
        *
        * So the shape is required rather than tolerated: this is the single enumeration the
        * whole reconciler depends on.
        */
      case "findNotes" =>
        p.downField("query").as[String] match
          case Left(_) => err("bad argument type for built-in operation")
          case Right(query) if !query.startsWith("tag:") || !query.endsWith("*") =>
            err(s"fake: refusing a search that is not a tag-prefix query: '$query'")
          case Right(query) =>
            val prefix = query.drop("tag:".length).dropRight(1).toLowerCase
            ok(
              state.notes
                .filter((_, n) => n.tags.exists(_.toLowerCase.startsWith(prefix)))
                .keys
                .toVector
                .sorted
                .asJson
            )

      case "notesInfo" =>
        val ids = p.downField("notes").as[Vector[Long]].getOrElse(Vector.empty)
        ok(ids.map { id =>
          state.notes.get(id) match
            // VERIFIED LIVE: an unknown id yields an empty object, NOT an error.
            case None => Json.obj()
            case Some(n) =>
              Json.obj(
                "noteId" := id,
                "modelName" := n.model,
                "fields" := renderFields(n.fields),
                "tags" := n.tags,
                "cards" := n.cards,
                "mod" := 1787143130L,
                "profile" := "fake",
              )
        }.asJson)

      // VERIFIED LIVE: one corrupt card nulls the ENTIRE batch. Present so that any future
      // use of this action fails loudly here rather than in a collection.
      case "cardsInfo" =>
        val cards = p.downField("cards").as[Vector[Long]].getOrElse(Vector.empty)
        if cards.exists(state.corruptCards.contains) then err("missing template")
        else ok(cards.map(c => Json.obj("cardId" := c)).asJson)

      case "getDecks" =>
        val cards = p.downField("cards").as[Vector[Long]].getOrElse(Vector.empty)
        ok(
          cards
            .flatMap(c => state.cardDeck.get(c).map(_ -> c))
            .groupMap(_._1)(_._2)
            .asJson
        )

      case "createDeck" =>
        val deck = p.downField("deck").as[String].getOrElse("")
        state.decks += deck
        ok(1234L.asJson)

      case "addNote" =>
        val note   = p.downField("note")
        val deck   = note.downField("deckName").as[String].getOrElse("")
        val model  = note.downField("modelName").as[String].getOrElse("")
        val fields = note.downField("fields").as[JsonObject].map(fieldsFrom).getOrElse(Vector.empty)
        val tags   = note.downField("tags").as[Vector[String]].getOrElse(Vector.empty)
        // VERIFIED LIVE: addNote does not create the deck.
        if !state.decks.contains(deck) then err(s"deck was not found: $deck")
        else if fields.isEmpty then err("cannot create note because it is empty")
        else
          val id   = state.fresh()
          val card = state.fresh()
          state.notes += id -> Note(model, fields, tags, Vector(card))
          state.cardDeck += card -> deck
          ok(id.asJson)

      case "updateNote" =>
        val note = p.downField("note")
        val id   = note.downField("id").as[Long].getOrElse(-1L)
        state.notes.get(id) match
          case None => err(s"Note was not found: $id")
          case Some(existing) =>
            val fields = note.downField("fields").as[JsonObject].map(fieldsFrom).toOption
            // VERIFIED LIVE: the tags key REPLACES the whole set. Absent, tags are untouched.
            val tags = note.downField("tags").as[Vector[String]].toOption
            state.notes += id -> existing.copy(
              fields = fields.getOrElse(existing.fields),
              tags = tags.getOrElse(existing.tags),
            )
            ok(Json.Null)

      /** VERIFIED LIVE: this accepts a `tags` parameter and silently discards it. Modelled
        * faithfully so that an interpreter reaching for it — it has the more obvious name —
        * loses the tags here, in a test, rather than in a collection.
        */
      case "updateNoteFields" =>
        val note = p.downField("note")
        val id   = note.downField("id").as[Long].getOrElse(-1L)
        state.notes.get(id) match
          case None => err(s"Note was not found: $id")
          case Some(existing) =>
            val fields = note.downField("fields").as[JsonObject].map(fieldsFrom).toOption
            state.notes += id -> existing.copy(fields = fields.getOrElse(existing.fields))
            ok(Json.Null)

      case "addTags" | "removeTags" =>
        val ids = p.downField("notes").as[Vector[Long]].getOrElse(Vector.empty)
        // VERIFIED LIVE: an array here is refused; tags arrive space-delimited.
        p.downField("tags").as[String] match
          case Left(_) => err("bad argument type for built-in operation")
          case Right(joined) =>
            val tags = joined.split(" ").toVector.filter(_.nonEmpty)
            ids.foreach { id =>
              state.notes.get(id).foreach { n =>
                val updated =
                  if action == "addTags" then (n.tags ++ tags).distinct
                  else n.tags.filterNot(t => tags.exists(_.equalsIgnoreCase(t)))
                state.notes += id -> n.copy(tags = updated)
              }
            }
            ok(Json.Null)

      case "changeDeck" =>
        val cards = p.downField("cards").as[Vector[Long]].getOrElse(Vector.empty)
        val deck  = p.downField("deck").as[String].getOrElse("")
        // VERIFIED LIVE: changeDeck DOES create the deck, unlike addNote.
        state.decks += deck
        cards.foreach(c => state.cardDeck += c -> deck)
        ok(Json.Null)

      case other => err(s"unsupported action: $other")
