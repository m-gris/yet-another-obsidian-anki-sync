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

  /** Which collection is actually open, so the `--profile` argument can be checked against
    * reality rather than trusted.
    *
    * DELIBERATELY NOT `loadProfile`. Switching profiles would make the argument a command,
    * and a command that closes whatever collection the person currently has open — mid
    * review, possibly — is a side effect nobody asked for. As an assertion it is stronger:
    * passing the wrong name does not take the run to that collection, it stops the run.
    *
    * THE GUARANTEE IS "CHECKED ONCE, AT THE START OF THE RUN", and no more than that. A
    * profile switched inside Anki while the run is in flight is not detected. An earlier
    * version of this comment claimed the run "cannot reach the wrong collection", which
    * overstates it in exactly the direction that would stop someone looking for the gap.
    *
    * NOT part of [[Anki]]: the algebra is about cards, and "which collection am I talking to"
    * is a property of the connection. [[InMemoryAnki]] has no meaningful answer to give.
    */
  def activeProfile: Result[String] =
    call[String]("getActiveProfile", Json.obj())

  // ---------------------------------------------------------------- note types ----

  def noteTypeNames: Result[Vector[String]] =
    call[Vector[String]]("modelNames", Json.obj())

  def fieldNames(noteType: String): Result[Vector[String]] =
    call[Vector[String]]("modelFieldNames", Json.obj("modelName" := noteType))

  /** VERIFIED LIVE 2026-08-21, read-only, in profile `claude-POC-test`: asked for a model the
    * collection does not hold, `modelTemplates` answers `error: "model was not found: <name>"`
    * with a null result — so [[AnkiConnect.classify]]'s `model was not found:` branch turns it
    * into [[AnkiError.NoSuchNoteType]] rather than an unclassified refusal. `modelStyling`
    * behaves identically, checked the same way.
    */
  def noteTypeTemplates(noteType: String): Result[Map[String, CardTemplate]] =
    call[Map[String, CardTemplate]]("modelTemplates", Json.obj("modelName" := noteType))

  def noteTypeStyling(noteType: String): Result[String] =
    call("modelStyling", Json.obj("modelName" := noteType))(using AnkiConnect.modelStylingCss)

  /** THE ONE PLACE THIS TOOL READS ANKI'S INTERNAL NOTE-TYPE DICTIONARY, and it reads exactly
    * one integer out of it — see [[Anki.noteTypeIsCloze]] for why the fact cannot be inferred
    * and [[AnkiConnect.firstModelIsCloze]] for what is decoded.
    *
    * `findModelsByName` is used rather than `modelNamesAndIds` or `modelTemplates` because it
    * is the only action that returns the flag at all. It takes a LIST and answers with a LIST,
    * so one name goes in and the first entry comes out; a name the collection does not hold is
    * refused with `model was not found: <name>`, which [[AnkiConnect.classify]] already turns
    * into [[AnkiError.NoSuchNoteType]] — the same shape `modelTemplates` and `modelStyling`
    * produce, verified live on 2026-08-21.
    */
  def noteTypeIsCloze(noteType: String): Result[Boolean] =
    call("findModelsByName", Json.obj("modelNames" := Vector(noteType)))(using
      AnkiConnect.firstModelIsCloze
    )

  /** NOT ROUTED THROUGH `command`, AND NOT ASSERTED TO ANSWER ANYTHING IN PARTICULAR.
    *
    * `createModel` answers with the MODEL IT CREATED, not with null, so
    * [[AnkiConnect.expectNoResult]] would reject a successful call. (Read out of the add-on's
    * own source in this repository rather than exercised: `__init__.py:1159-1160` ends
    * `mm.add(m); return m`, and `web.py:286` puts that return value straight into `result`.)
    *
    * The payload is therefore decoded as `Json` and discarded, and it is worth saying what that
    * costs: `Decoder[Json]` accepts anything, so unlike `addTags` or `changeDeck` a change in
    * what this action answers would NOT be noticed here. Asserting the shape of a model dict
    * would mean pinning Anki's internal note-type representation, which is a far larger surface
    * than this tool has any business depending on.
    *
    * WHAT IS RELIED ON IS THE ENVELOPE, NOT THE PAYLOAD: a refusal still arrives as `error`
    * non-null, which [[AnkiConnect.envelope]] turns into a `Left` on every action alike.
    *
    * THE DUPLICATE-NAME REFUSAL IS RE-LABELLED HERE, AND ONLY HERE, because this is the only
    * place that knows both halves. Anki's message is exactly `Model name already exists` and
    * does not say WHICH model, so `AnkiConnect.classify` — which sees the message and never the
    * parameters — could not name it. Mapping it here rather than leaving it as a bare
    * [[AnkiError.Remote]] is what keeps the two interpreters of this algebra agreeing about the
    * contract: [[InMemoryAnki]] raises [[AnkiError.NoteTypeExists]] for the same situation.
    */
  def createNoteType(spec: NoteTypeSpec): Result[Unit] =
    call[Json]("createModel", AnkiConnect.createModelParams(spec)).void
      .leftMap {
        case AnkiError.Remote("createModel", AnkiConnect.ModelNameAlreadyExists) =>
          AnkiError.NoteTypeExists(spec.name)
        case other => other
      }

  /** All three repair actions answer `null`, so [[AnkiConnect.expectNoResult]] is the tripwire:
    * decoding into `Option[Json]` would accept ANY answer, including one shaped like a failure.
    *
    * A NOTE TYPE THAT VANISHED BETWEEN THE SURVEY AND THE REPAIR — renamed or deleted in the
    * Anki window while this ran — comes back as Anki's own "model was not found: <name>", which
    * is mapped to [[AnkiError.NoteTypeMissing]] so the caller reads a name rather than a remote
    * string. This is a real race, not a theoretical one: a repair is something a person runs
    * WITH Anki open in front of them.
    */
  def addNoteTypeField(noteType: String, field: String): Result[Unit] =
    command("modelFieldAdd", AnkiConnect.modelFieldAddParams(noteType, field))
      .leftMap(missingNoteType(noteType))

  def setNoteTypeTemplates(noteType: String, templates: Map[String, CardTemplate]): Result[Unit] =
    command("updateModelTemplates", AnkiConnect.updateModelTemplatesParams(noteType, templates))
      .leftMap(missingNoteType(noteType))

  def setNoteTypeStyling(noteType: String, css: String): Result[Unit] =
    command("updateModelStyling", AnkiConnect.updateModelStylingParams(noteType, css))
      .leftMap(missingNoteType(noteType))

  /** Matched on the MESSAGE and not on the action name, because all three repair actions raise
    * the identical string and mapping them separately would be three copies of one fact.
    */
  /** The boolean is DISCARDED, deliberately — see [[AnkiConnect.suspendResult]]. It is decoded
    * rather than ignored so that a shape change fails loudly, and then thrown away because
    * `false` means "already suspended", which is the ordinary result of a second run and not
    * something a caller should be able to mistake for a failure.
    *
    * AN EMPTY LIST IS NOT SENT. Anki answers `false` for one, harmlessly, but a call that cannot
    * change anything is a call worth not making — and `Flag` on a note whose cards could not be
    * read would otherwise reach here silently.
    */
  def suspend(cards: Vector[AnkiCardId]): Result[Unit] =
    if cards.isEmpty then EitherT.pure(())
    else call("suspend", AnkiConnect.suspendParams(cards))(using AnkiConnect.suspendResult).void

  /** ANSWERS `null`, NOT A BOOLEAN, unlike its twin: `__init__.py:1060` calls through without
    * returning. Decoded with the null-asserting helper for that reason.
    */
  def unsuspend(cards: Vector[AnkiCardId]): Result[Unit] =
    if cards.isEmpty then EitherT.pure(())
    else command("unsuspend", AnkiConnect.suspendParams(cards))

  private def missingNoteType(noteType: String): AnkiError => AnkiError =
    case AnkiError.Remote(_, message) if message == AnkiConnect.modelNotFound(noteType) =>
      AnkiError.NoSuchNoteType(noteType)
    case other => other

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

  /** Uses the `updateNoteModel` ACTION, and this is the ONE write in this interpreter that
    * sends a `tags` array on purpose.
    *
    * The class note above forbids passing `tags` to `updateNote`, because there it REPLACES the
    * note's whole tag set and would destroy the person's own tags along with Anki's `leech`.
    * Here replacement is not avoidable: `updateNoteModel` replaces the tag set whether or not
    * the key is sent, and sending nothing erases it. So the rule is inverted rather than
    * broken — everything the note must keep is sent, foreign tags included, which is why
    * [[Anki.changeNoteType]] takes them as a separate echoed argument.
    *
    * The two tag vectors are concatenated OWNED FIRST. Order is not significant to Anki, which
    * stores tags as one space-delimited string and sorts them itself; owned-first simply keeps
    * the request readable when someone is looking at the wire.
    *
    * ANSWERS `null` ON SUCCESS — the add-on's `updateNoteModel` ends on
    * `collection.update_note(...)` and returns nothing — so it goes through [[command]], where
    * anything else is a loud failure rather than a shrug.
    */
  def changeNoteType(
      id: AnkiNoteId,
      to: String,
      fields: Vector[(String, String)],
      ownedTags: NonEmptyVector[OwnedTag],
      preservedTags: Vector[String],
  ): Result[Unit] =
    command(
      "updateNoteModel",
      AnkiConnect.updateNoteModelParams(
        id,
        to,
        fields,
        ownedTags.toVector.map(_.value) ++ preservedTags,
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
