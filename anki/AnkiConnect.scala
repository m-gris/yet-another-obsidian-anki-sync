package obsidiananki.anki

import io.circe.{Decoder, Json}
import io.circe.syntax.*
import obsidiananki.model.OwnedTag

/** The AnkiConnect wire protocol, as PURE FUNCTIONS.
  *
  * Nothing here performs a request. Building the body, reading the envelope and classifying
  * a refusal are all data-to-data, so the parts of the interpreter that have historically
  * gone wrong are testable without a collection, a socket, or a running Anki.
  *
  * EVERY SHAPE BELOW WAS CHECKED AGAINST A LIVE ANKI, not against the documentation. The
  * documented behaviour and the real behaviour have diverged repeatedly in this project, so
  * anything asserted here is asserted because it was observed.
  */
object AnkiConnect:

  /** AnkiConnect's API version. Sent on every request; the server rejects a mismatch. */
  val ApiVersion: Int = 6

  def request(action: String, params: Json): Json =
    if params == Json.obj() then
      Json.obj("action" := action, "version" := ApiVersion)
    else
      Json.obj("action" := action, "version" := ApiVersion, "params" := params)

  /** Read the `{result, error}` envelope and yield the RESULT payload.
    *
    * THE ENVELOPE IS NEVER SILENT. A failure arrives as `error` non-null with `result` null
    * — verified against a genuinely corrupt collection, where a batch read returned exactly
    * that. The silence in the original design would have been entirely ours, in reading a
    * null result as an empty collection. So the null result is returned as `Json.Null` and
    * left for the payload decoder to reject; there is deliberately no path here that turns
    * an absent result into an empty one.
    */
  def envelope(action: String, body: String): Either[AnkiError, Json] =
    io.circe.parser.parse(body) match
      case Left(failure) =>
        Left(AnkiError.MalformedResponse(action, s"response was not JSON: ${failure.message}"))
      case Right(json) =>
        val cursor = json.hcursor
        // THE ERROR KEY MUST BE PRESENT, and its absence is checked separately from its
        // value. `as[Option[String]]` cannot do this: circe answers Right(None) for an
        // ABSENT key exactly as it does for an explicit null, so a body carrying a result
        // and no `error` key at all would take the success path — and for a search that
        // means an empty list, which reads as "Anki holds nothing" rather than "this is not
        // an AnkiConnect response". Verified against circe 0.14.16 rather than assumed.
        cursor.downField("error").focus match
          case None =>
            Left(AnkiError.MalformedResponse(action, "response has no 'error' field"))
          case Some(errorJson) =>
            errorJson.asNull match
              case Some(_) =>
                cursor.downField("result").focus match
                  case None         => Left(AnkiError.MalformedResponse(action, "response has no 'result' field"))
                  case Some(result) => Right(result)
              case None =>
                errorJson.asString match
                  case Some(message) => Left(classify(action, message))
                  case None =>
                    Left(
                      AnkiError.MalformedResponse(
                        action,
                        s"'error' was neither null nor a string: ${errorJson.noSpaces.take(120)}",
                      )
                    )

  /** Decode a response into the payload type.
    *
    * The decoder is what refuses a null result, which is the point: `Decoder[Vector[A]]`
    * fails on `null` rather than yielding an empty vector, so "Anki could not tell us" can
    * never be read as "there is nothing there".
    */
  def decodeAs[A: Decoder](action: String, body: String): Either[AnkiError, A] =
    envelope(action, body).flatMap { result =>
      result
        .as[A]
        .left
        .map(f => AnkiError.MalformedResponse(action, s"could not read result: ${f.message}"))
    }

  /** Assert that an action which should report NOTHING back reported nothing back.
    *
    * A REAL TRIPWIRE, which matters because the obvious way to write this is not one:
    * decoding the payload as `Option[Json]` accepts every possible JSON value, since
    * `Decoder[Json]` is the identity decoder — so it reads as a check while asserting
    * nothing at all. Anki answering something where it used to answer null is exactly the
    * kind of wire change that should be noticed on the first run rather than the hundredth.
    *
    * Note that `createDeck` does NOT come through here: it answers with the deck's id, so it
    * is read as the number it is rather than being waved past by a check loose enough to
    * admit it.
    */
  def expectNoResult(action: String, result: Json): Either[AnkiError, Unit] =
    if result.isNull then Right(())
    else
      Left(
        AnkiError.MalformedResponse(
          action,
          s"expected no result, got: ${result.noSpaces.take(120)}",
        )
      )

  /** Turn a refusal message into a domain error where the message is unambiguous, and carry
    * it verbatim where it is not.
    *
    * ONLY EXACT, OBSERVED MESSAGES ARE MATCHED. Everything else becomes
    * [[AnkiError.Remote]] carrying the original text rather than being forced into the
    * nearest-looking case — a misclassified error is harder to diagnose than an unclassified
    * one, because it sends the reader somewhere confidently wrong.
    *
    * Note what is deliberately NOT classified: an unknown FIELD name comes back as "cannot
    * create note because it is empty", because Anki drops the unrecognised key and then sees
    * a note with nothing in it. Mapping that to [[AnkiError.UnknownField]] would be a guess;
    * a genuinely empty note produces the identical message.
    *
    * CONSEQUENCE, STATED PLAINLY BECAUSE IT IS A GAP AND NOT A DESIGN: [[AnkiError
    * .UnknownField]] is therefore UNREACHABLE through this interpreter, while [[InMemoryAnki]]
    * raises it on both write paths — so the two interpreters of the same algebra disagree
    * about that part of the contract. Closing it needs a preflight that checks field names
    * against each note type before any write, using `noteTypeNames`/`fieldNames`, which is
    * NOT YET BUILT. Until it is, a wrong field name surfaces as an unhelpful "empty note"
    * refusal on create, and on update as no error at all.
    */
  def classify(action: String, message: String): AnkiError =
    if message.startsWith("cannot create note because it is a duplicate") then
      AnkiError.DuplicateNote(message)
    else if message.startsWith("model was not found:") then
      AnkiError.NoSuchNoteType(message.stripPrefix("model was not found:").trim)
    else AnkiError.Remote(action, message)

  // ---------------------------------------------------------------- payload shapes ----

  /** One field as AnkiConnect reports it: `{"value": "...", "order": 0}`.
    *
    * The order is not decoration. Field ORDER decides the content hash, so reading the
    * fields out of a JSON object in whatever order the parser happened to produce would make
    * the hash unstable and every run would plan an update for every card.
    */
  private final case class WireField(value: String, order: Int)
  private given Decoder[WireField] = Decoder.forProduct2("value", "order")(WireField.apply)

  /** A note as `notesInfo` reports it.
    *
    * STRICT BY NECESSITY. `notesInfo` answers for a note id that does not exist with an
    * EMPTY OBJECT and `error: null` — verified live. A decoder that filled in defaults would
    * manufacture a note with no id, no type and no tags, and hand it to the reconciler as
    * though it were real. Requiring every field makes that response fail loudly instead.
    */
  given Decoder[ObservedNote] = Decoder.instance { cursor =>
    for
      id     <- cursor.downField("noteId").as[Long]
      model  <- cursor.downField("modelName").as[String]
      fields <- cursor.downField("fields").as[Map[String, WireField]]
      tags   <- cursor.downField("tags").as[Vector[String]]
    yield ObservedNote(
      id = AnkiNoteId(id),
      noteType = model,
      fields = fields.toVector.sortBy(_._2.order).map((name, f) => (name, f.value)),
      tags = tags,
    )
  }

  /** The card ids belonging to a note, read from the SAME `notesInfo` response.
    *
    * Deliberately not `cardsInfo`. That action is the one that returns rendered
    * question/answer HTML — the source of the raw control characters that break strict JSON
    * parsing — and it is also the one that returns null for an ENTIRE BATCH when a single
    * card in it is corrupt. Neither hazard needs handling if the call is never made, and the
    * card ids are already present here.
    */
  val cardIdsOfNote: Decoder[Vector[AnkiCardId]] =
    Decoder.instance(_.downField("cards").as[Vector[Long]].map(_.map(AnkiCardId.apply)))

  /** `getDecks` answers `{"Deck::Name": [cardId, ...]}`. Inverted here to card -> deck. */
  val decksByCard: Decoder[Map[Long, String]] =
    Decoder[Map[String, Vector[Long]]].map { byDeck =>
      byDeck.toVector.flatMap((deck, cards) => cards.map(_ -> deck)).toMap
    }

  /** Anki stores a note's tags as ONE space-delimited string, and `addTags`/`removeTags`
    * take them that way — passing a JSON array is refused with "bad argument type for
    * built-in operation", verified live.
    *
    * This is the second, independent reason the tag encoding forbids whitespace: even if
    * Anki's storage tolerated it, this parameter would tear such a tag into two.
    */
  def joinTags(tags: Vector[OwnedTag]): String = tags.map(_.value).mkString(" ")
