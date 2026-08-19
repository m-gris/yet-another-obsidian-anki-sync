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

      case "modelNames" =>
        ok(state.notes.values.map(_.model).toVector.distinct.sorted.asJson)

      case "modelFieldNames" =>
        val model = p.downField("modelName").as[String].getOrElse("")
        state.notes.values.find(_.model == model) match
          case Some(note) => ok(note.fields.map(_._1).asJson)
          case None       => err(s"model was not found: $model")

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
