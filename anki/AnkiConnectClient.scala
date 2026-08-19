package obsidiananki.anki

import cats.data.{EitherT, NonEmptyVector}
import cats.effect.Concurrent
import cats.syntax.all.*
import io.circe.{Decoder, Json}
import io.circe.syntax.*
import obsidiananki.anki.AnkiConnect.given
import obsidiananki.model.OwnedTag
import org.http4s.{Method, Request, Uri}
import org.http4s.circe.jsonEncoder
import org.http4s.client.Client

/** The [[Anki]] algebra over AnkiConnect's HTTP API.
  *
  * THE ERROR TYPE IS A DELIBERATE SPLIT, and it is the main thing to understand here.
  * `EitherT[F, AnkiError, *]` carries REFUSALS — Anki answered and said no — which
  * [[obsidiananki.plan.Executor]] collects per action so one bad card does not abandon the
  * rest of the plan. Being unable to reach Anki at all is NOT an AnkiError: it stays a
  * transport failure in `F` and aborts the run. Collecting fifty identical "connection
  * refused" entries would report a dead collection as forty-nine ordinary problems.
  *
  * WHAT THIS DELIBERATELY NEVER CALLS: `cardsInfo`, and `updateNoteFields`.
  *
  *   - `cardsInfo` returns rendered question/answer HTML containing raw control characters
  *     that strict JSON parsing rejects, AND returns null for an entire batch when a single
  *     card in it is corrupt. Both were observed. Neither needs handling, because the two
  *     things this tool wanted from it are available elsewhere: card ids come back inside
  *     `notesInfo`, and decks come from `getDecks`.
  *   - AnkiConnect's `updateNoteFields` action accepts a `tags` parameter and SILENTLY
  *     DISCARDS it. This tool uses `updateNote` instead — with no `tags` key at all, which
  *     was verified to leave existing tags untouched.
  *
  * AND WHAT IT MUST NEVER PASS: a `tags` array to `updateNote`. That parameter REPLACES the
  * note's whole tag set rather than merging, so writing our own tags through it destroys
  * both the human's tags and Anki's own scheduler state — `leech` among them. Tag changes go
  * through `addTags`/`removeTags`, which are additive and subtractive respectively.
  */
final class AnkiConnectClient[F[_]: Concurrent](client: Client[F], baseUri: Uri)
    extends Anki[[A] =>> EitherT[F, AnkiError, A]]:

  private type Result[A] = EitherT[F, AnkiError, A]

  /** One request/response exchange, with the envelope decoded into the payload type. */
  private def call[A: Decoder](action: String, params: Json): Result[A] =
    val request = Request[F](Method.POST, baseUri).withEntity(AnkiConnect.request(action, params))
    EitherT(client.expect[String](request).map(AnkiConnect.decodeAs[A](action, _)))

  /** For actions that answer `null` on success, with that asserted rather than assumed.
    *
    * See [[AnkiConnect.expectNoResult]] for why this is not written as `Option[Json]`: that
    * type admits every JSON value, so it would read as a check while checking nothing.
    */
  private def command(action: String, params: Json): Result[Unit] =
    call[Json](action, params).subflatMap(AnkiConnect.expectNoResult(action, _))

  // ---------------------------------------------------------------- note types ----

  def noteTypeNames: Result[Vector[String]] =
    call[Vector[String]]("modelNames", Json.obj())

  def fieldNames(noteType: String): Result[Vector[String]] =
    call[Vector[String]]("modelFieldNames", Json.obj("modelName" := noteType))

  // ---------------------------------------------------------------- reading ----

  /** Anki's search syntax, where `*` is the wildcard. The prefix is safe to interpolate
    * because [[obsidiananki.model.TagCodec]] percent-encodes everything outside
    * `[A-Za-z0-9.-]`, so it can contain neither whitespace nor a search metacharacter.
    */
  def findNotesByTagPrefix(prefix: String): Result[Vector[AnkiNoteId]] =
    call[Vector[Long]]("findNotes", Json.obj("query" := s"tag:$prefix*")).map(_.map(AnkiNoteId.apply))

  def notesInfo(ids: Vector[AnkiNoteId]): Result[Vector[ObservedNote]] =
    if ids.isEmpty then EitherT.pure(Vector.empty)
    else call[Vector[ObservedNote]]("notesInfo", Json.obj("notes" := ids.map(_.value)))

  /** Card ids come out of `notesInfo`, so this costs no extra request and never touches the
    * batch-poisoning action. See the note on the class.
    */
  def cardsOf(ids: Vector[AnkiNoteId]): Result[Vector[AnkiCardId]] =
    if ids.isEmpty then EitherT.pure(Vector.empty)
    else
      call[Vector[Json]]("notesInfo", Json.obj("notes" := ids.map(_.value))).subflatMap { entries =>
        entries
          .traverse(AnkiConnect.cardIdsOfNote.decodeJson(_))
          .bimap(
            f => AnkiError.MalformedResponse("notesInfo", s"could not read card ids: ${f.message}"),
            _.flatten,
          )
      }

  def deckOf(card: AnkiCardId): Result[Option[DeckPath]] =
    call[Json]("getDecks", Json.obj("cards" := Vector(card.value)))
      .subflatMap { json =>
        AnkiConnect.decksByCard
          .decodeJson(json)
          .leftMap(f => AnkiError.MalformedResponse("getDecks", s"could not read decks: ${f.message}"))
      }
      .map(_.get(card.value).flatMap(parseDeck))

  /** Anki reports a deck as its `::`-joined name; [[DeckPath]] holds the segments. A name
    * that yields no segments at all is reported as absent rather than as an empty path,
    * which [[DeckPath]] cannot represent anyway.
    */
  private def parseDeck(name: String): Option[DeckPath] =
    NonEmptyVector.fromVector(name.split("::").toVector.filter(_.nonEmpty)).map(DeckPath.apply)

  // ---------------------------------------------------------------- writing ----

  /** VERIFIED LIVE: `addNote` does NOT create a missing deck — it refuses with "deck was not
    * found" — while `changeDeck` DOES create one. The asymmetry is Anki's, not ours, so the
    * deck is created first. `createDeck` on a deck that already exists returns its id and
    * changes nothing, so this is safe to do unconditionally.
    *
    * `allowDuplicate` is set because this tool owns identity through the `src::` tag. Anki's
    * own first-field duplicate check is a competing identity mechanism, and with it enabled
    * the SECOND descriptor of any concept would be refused for sharing a first field with
    * the first.
    */
  def addNote(note: NewNote): Result[AnkiNoteId] =
    val payload = Json.obj(
      "note" := Json.obj(
        "deckName" := note.deck.render,
        "modelName" := note.noteType,
        "fields" := Json.obj(note.fields.map((name, value) => name := value)*),
        "options" := Json.obj("allowDuplicate" := true),
        // Tags travel WITH the creation, never as a follow-up call: a note that exists
        // without its src:: tag is unenumerable, and no later call could find it to fix it.
        "tags" := note.tags.toVector.map(_.value),
      )
    )
    for
      // createDeck answers with the deck's id, NOT with null, so it is read as the number it
      // is. Routing it through `command` would have required loosening that check to admit
      // any value, which would have disarmed it for every other action too.
      _  <- call[Long]("createDeck", Json.obj("deck" := note.deck.render))
      id <- call[Long]("addNote", payload)
    yield AnkiNoteId(id)

  /** Uses the `updateNote` ACTION, and passes no `tags` key. See the note on the class for
    * why neither half of that sentence is incidental.
    */
  def updateNoteFields(id: AnkiNoteId, fields: Vector[(String, String)]): Result[Unit] =
    command(
      "updateNote",
      Json.obj(
        "note" := Json.obj(
          "id" := id.value,
          "fields" := Json.obj(fields.map((name, value) => name := value)*),
        )
      ),
    )

  def addTags(ids: Vector[AnkiNoteId], tags: Vector[OwnedTag]): Result[Unit] =
    if ids.isEmpty || tags.isEmpty then EitherT.pure(())
    else
      command(
        "addTags",
        Json.obj("notes" := ids.map(_.value), "tags" := AnkiConnect.joinTags(tags)),
      )

  def removeTags(ids: Vector[AnkiNoteId], tags: Vector[OwnedTag]): Result[Unit] =
    if ids.isEmpty || tags.isEmpty then EitherT.pure(())
    else
      command(
        "removeTags",
        Json.obj("notes" := ids.map(_.value), "tags" := AnkiConnect.joinTags(tags)),
      )

  def changeDeck(cards: Vector[AnkiCardId], deck: DeckPath): Result[Unit] =
    if cards.isEmpty then EitherT.pure(())
    else command("changeDeck", Json.obj("cards" := cards.map(_.value), "deck" := deck.render))

object AnkiConnectClient:

  /** AnkiConnect's fixed local address. Not configurable: it is bound to loopback by the
    * add-on and pointing this tool at another host would mean writing to someone else's
    * collection.
    */
  val DefaultUri: Uri = Uri.unsafeFromString("http://localhost:8765")
