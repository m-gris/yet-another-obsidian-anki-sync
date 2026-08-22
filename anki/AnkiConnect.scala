package obsidiananki.anki

import io.circe.{Decoder, DecodingFailure, Json}
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
    * .UnknownField]] is UNREACHABLE through this interpreter, while [[InMemoryAnki]] raises it
    * on both write paths — so the two interpreters of the same algebra disagree about that part
    * of the contract.
    *
    * _Amended 2026-08-21. This used to end "closing it needs a preflight … which is NOT YET
    * BUILT". A preflight now exists and it is worth being exact about what it does and does not
    * cover._ `obsidiananki.anki.NoteTypeInstaller.readiness` reads `noteTypeNames` and one
    * `modelFieldNames` per note type, and `cli/Main.scala` runs it before a sync observes the
    * collection: a note type that is absent, or that does not declare a field this tool writes,
    * refuses the whole run before anything is written.
    *
    * WHAT REMAINS OPEN is narrower than it was. The preflight checks the five note types this
    * tool OWNS against the field lists it declares; it does not check an individual write's
    * field names against the note type it names. Those two coincide today, because every write
    * is built by `CardSpec.fields` and `anki/NoteTypeAssets.test.scala` ties that to the
    * manifests — but they are different claims, and if a write path ever built field names some
    * other way, this interpreter would still report the wrong one as "cannot create note
    * because it is empty" on create and as no error at all on update.
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

  /** One card template as `modelTemplates` reports it: `{"Front": "...", "Back": "..."}`.
    *
    * THE KEYS ARE CAPITALISED, and they are Anki's, not ours. Verified on 2026-08-21 by asking
    * the running add-on for `3 way Concept-Descriptor` in profile `claude-POC-test`, which came
    * back as an object keyed by template name whose values held exactly `Front` and `Back`.
    * `createModel` wants the same capitalisation plus a `Name` key.
    */
  given Decoder[CardTemplate] = Decoder.forProduct2("Front", "Back")(CardTemplate.apply)

  /** `modelStyling` answers `{"css": "..."}`, not a bare string.
    *
    * Verified the same way and on the same day, against `Cloze Sequence`. A decoder reading the
    * response as a `String` would fail loudly, so this is a convenience rather than a guard —
    * but the wrapper is easy to forget and the failure it produces names circe rather than
    * Anki.
    */
  val modelStylingCss: Decoder[String] = Decoder.instance(_.downField("css").as[String])

  /** Anki's refusal when `createModel` is given a name the collection already holds.
    *
    * VERBATIM, AND IT DOES NOT NAME THE MODEL — `__init__.py:1127` raises
    * `Exception('Model name already exists')` and `web.py:290` puts `str(exception)` into the
    * envelope unchanged. Both read out of the add-on installed on this machine rather than
    * exercised, because exercising it means creating a model.
    *
    * NOT MATCHED IN [[classify]], deliberately. [[classify]] sees a message and never the
    * request, so it could produce only a nameless error; `AnkiConnectClient.createNoteType`
    * knows which name it asked for and does the mapping there.
    */
  val ModelNameAlreadyExists: String = "Model name already exists"

  /** The `createModel` parameters for a note type.
    *
    * EVERY PARAMETER NAME HERE WAS READ OFF THE ADD-ON INSTALLED ON THIS MACHINE, not off the
    * documentation: `__init__.py:1120` declares
    * `createModel(self, modelName, inOrderFields, cardTemplates, css=None, isCloze=False)`, and
    * `:1155-1156` reads `card['Front']` and `card['Back']` — capitalised, and required, since a
    * missing key is a `KeyError`.
    *
    * `Name` IS SENT FOR EVERY TEMPLATE, and its absence would NOT be an error: `:1149-1151`
    * silently substitutes `"Card N"`. That is why it is always sent — a template whose name is
    * not the one the manifest records is a template `updateModelTemplates` can never find
    * again, and its failure to find one is a clean-exit no-op rather than a refusal.
    *
    * THE TWO EMPTINESS CHECKS AT `:1122-1125` CANNOT FIRE, because [[NoteTypeSpec]] holds both
    * lists as `NonEmptyVector`. That is the point of the type: a note type with no templates
    * installs, looks present, and generates no cards.
    *
    * SEPARATE FROM THE CLIENT so that the exact body sent is testable without a socket, which
    * is the same reason every other shape in this file is a pure function.
    */
  def createModelParams(spec: NoteTypeSpec): Json =
    Json.obj(
      "modelName" := spec.name,
      "inOrderFields" := spec.fields.toVector,
      "css" := spec.styling,
      "isCloze" := spec.isCloze,
      "cardTemplates" := spec.templates.toVector.map { (name, template) =>
        Json.obj("Name" := name, "Front" := template.front, "Back" := template.back)
      },
    )

  /** The `modelFieldAdd` parameters.
    *
    * FLAT, NOT WRAPPED IN A `model` OBJECT, and the asymmetry with the two below is Anki's own,
    * not this file's: `__init__.py:1433` declares `modelFieldAdd(self, modelName, fieldName,
    * index=None)` while `:1294` and `:1316` each declare a single `model` dictionary. Read off
    * the add-on installed on this machine, because guessing the wrapper wrong produces a
    * missing-argument error naming Python rather than naming the mistake.
    *
    * `index` IS NOT SENT: omitting it appends, and this tool writes fields by NAME.
    */
  def modelFieldAddParams(noteType: String, field: String): Json =
    Json.obj("modelName" := noteType, "fieldName" := field)

  /** The `updateModelTemplates` parameters.
    *
    * THE TEMPLATE MAP IS KEYED BY TEMPLATE NAME, and the add-on resolves each with
    * `templates.get(ankiTemplate['name'])` (`__init__.py:1302`). A key matching no template in
    * the collection is skipped IN SILENCE — so this function cannot surface that mistake and
    * the caller must have ruled it out beforehand. See [[Anki.setNoteTypeTemplates]].
    *
    * `Front` AND `Back` ARE CAPITALISED, matching `:1304` and `:1308` and matching
    * [[createModelParams]] — but here they are the only two keys, with no `Name` inside the
    * object, because the name is the map KEY rather than a field of the value.
    */
  def updateModelTemplatesParams(noteType: String, templates: Map[String, CardTemplate]): Json =
    Json.obj(
      "model" := Json.obj(
        "name" := noteType,
        "templates" := Json.obj(
          templates.toVector.map { (name, template) =>
            name -> Json.obj("Front" := template.front, "Back" := template.back)
          }*
        ),
      )
    )

  /** The `updateModelStyling` parameters. `__init__.py:1316-1322` — a whole-stylesheet assignment. */
  def updateModelStylingParams(noteType: String, css: String): Json =
    Json.obj("model" := Json.obj("name" := noteType, "css" := css))

  /** Anki's refusal when an action names a note type the collection does not hold.
    *
    * VERBATIM from `__init__.py:1298` and `:1320` — `'model was not found: {}'.format(...)` — so
    * unlike [[ModelNameAlreadyExists]] this one DOES name the model. Matched by the client so
    * that a repair aimed at a type somebody renamed mid-run fails by name rather than as an
    * opaque remote error.
    */
  def modelNotFound(noteType: String): String = s"model was not found: $noteType"

  /** The `suspend` and `unsuspend` parameters. Both take one list of card ids.
    *
    * `__init__.py:1041` declares `suspend(self, cards, suspend=True)` and `:1060` declares
    * `unsuspend(self, cards)` as a call straight through to it. The flag is NOT sent from here:
    * the two actions are separate names in the algebra, and passing a boolean would let a
    * caller ask one of them to do the other's job.
    */
  def suspendParams(cards: Vector[AnkiCardId]): Json =
    Json.obj("cards" := cards.map(_.value))

  /** THE TWO ACTIONS ANSWER DIFFERENT SHAPES, which is why they are not decoded the same way.
    *
    * `suspend` RETURNS A BOOLEAN — `False` when it found nothing to change, `True` otherwise
    * (`__init__.py:1046-1058`). `unsuspend` has no `return` statement at all, so Python answers
    * `None` and the envelope carries `null`. Read off the add-on installed on this machine;
    * decoding either as the other fails loudly, which is how this was found.
    *
    * FALSE IS NOT A FAILURE, and must never be treated as one: it means every card named was
    * already in the state asked for. That is the ordinary result of a second run.
    *
    * AND THE BOOLEAN CANNOT BE TRUSTED AS "SOMETHING CHANGED" ANYWAY. `:1042-1044` iterates
    * `cards` while removing from it, which skips elements — a real bug in the add-on, not a
    * simplification here. A mixed list therefore keeps some already-suspended cards, the
    * early-out at `:1046` does not fire, and the answer is `True` although nothing changed.
    * Harmless, because suspending a suspended card is a no-op; recorded so that nobody later
    * builds a report on that boolean.
    */
  val suspendResult: Decoder[Boolean] = Decoder[Boolean]

  /** Anki's own value for a CLOZE note type, in the `type` key of a note-type dictionary.
    *
    * READ OUT OF `anki/consts.py` ON THIS MACHINE on 2026-08-21 — `MODEL_STD = 0` and
    * `MODEL_CLOZE = 1` — rather than recalled. The add-on sets exactly this key when
    * `createModel` is given `isCloze` (`__init__.py:1134-1135`, `m['type'] = MODEL_CLOZE`),
    * which is the other half of the round trip.
    */
  val ClozeModelType: Int = 1

  /** Whether the FIRST model in a `findModelsByName` answer is a cloze type.
    *
    * `findModelsByName` takes a LIST of names and answers with a LIST of note-type
    * dictionaries (`__init__.py:1184-1193`), raising `model was not found: <name>` for any name
    * it cannot resolve — so an empty list means the request itself asked for nothing, and is
    * reported here rather than defaulted to `false`. Defaulting would answer "standard" for a
    * response nobody could read, and "standard" is the answer that lets a migration proceed.
    *
    * ONLY THE `type` KEY IS READ. Anki's note-type dictionary is a large internal structure
    * this tool has no business pinning; decoding it into a record would make every future Anki
    * release a potential wire break for a fact worth one integer.
    */
  val firstModelIsCloze: Decoder[Boolean] = Decoder.instance { cursor =>
    cursor.as[Vector[Json]].flatMap {
      case Vector() =>
        Left(DecodingFailure("findModelsByName answered with no model at all", cursor.history))
      case models =>
        models.head.hcursor.downField("type").as[Int].map(_ == ClozeModelType)
    }
  }

  /** The `updateNoteModel` parameters for moving one note onto another note type.
    *
    * EVERY PARAMETER NAME READ OFF THE ADD-ON INSTALLED ON THIS MACHINE
    * (`__init__.py:849-899`), not off the documentation: the action takes a single `note`
    * object carrying `id`, `modelName`, `fields` and `tags`, and raises a `ValueError` when any
    * of the first three is absent or empty.
    *
    * `tags` IS ALWAYS SENT, INCLUDING WHEN IT IS SHORT. `new_tags = note.get('tags', [])`
    * followed by an unconditional `anki_note.tags = new_tags` means an omitted key ERASES the
    * note's tags — see [[Anki.changeNoteType]]. There is deliberately no branch here that omits
    * it: an empty vector would be a bug, and it is [[obsidiananki.plan.SyncAction.Retype]]'s
    * non-empty owned-tag vector that makes one unconstructable rather than a check at this
    * layer.
    */
  def updateNoteModelParams(
      id: AnkiNoteId,
      to: String,
      fields: Vector[(String, String)],
      tags: Vector[String],
  ): Json =
    Json.obj(
      "note" := Json.obj(
        "id" := id.value,
        "modelName" := to,
        "fields" := Json.obj(fields.map((name, value) => name := value)*),
        "tags" := tags,
      )
    )

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
