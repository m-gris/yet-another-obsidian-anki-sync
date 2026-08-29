package obsidiananki.anki

import cats.data.NonEmptyVector
import cats.syntax.all.*
import obsidiananki.model.{Marker, OwnedTag}

/** A working in-memory Anki collection: a FAKE, not a mock.
  *
  * Tests assert on OUTCOMES — what notes exist, what tags they carry, which deck their
  * cards are in — never on which calls were made. Interaction verification would couple the
  * tests to the implementation; this couples them to the behaviour.
  *
  * MORE IMPORTANTLY, IT ENFORCES ANKI'S REAL CONSTRAINTS. A fake that merely stores what it
  * is given would happily accept `src::id::CAP Theorem/Definition` and every test would
  * pass, right up until the live collection tore that tag into two at the space. The
  * constraints modelled here are the ones this project has actually been bitten by:
  *
  *   - TAGS CANNOT CONTAIN WHITESPACE. Anki stores a note's tags as one space-delimited
  *     string, so a tag with a space in it is not one tag. Rejected loudly here.
  *   - TAG MATCHING IS CASE-INSENSITIVE, and Anki unifies tags that differ only in case.
  *     Modelled, so a reconciler that compares tags exactly fails here rather than in
  *     production.
  *   - `updateNoteFields` HAS NO EARLY-OUT: the modification counter moves even when the
  *     text is identical. Modelled so that "zero changes on re-run" cannot be satisfied by
  *     accident.
  *   - CREATING A NOTE AND TAGGING IT IS ONE OPERATION, because [[NewNote]] carries its
  *     tags. There is deliberately no way to create an untagged note here.
  */
final class InMemoryAnki private (
    initialNoteTypes: Map[String, NoteTypeSpec],
    allowDuplicate: Boolean,
) extends Anki[[A] =>> Either[AnkiError, A]]:

  /** MUTABLE, since 2026-08-21, because `createNoteType` exists.
    *
    * A collection's SHAPE can now change during a run, which it could not before. Modelling
    * that is the whole point: `InMemoryAnki(noteTypes = Map.empty)` is a FRESH PROFILE, into
    * which the installer must be able to create all five types and after which a write must
    * succeed — and that sequence is precisely what could not be tested while this was a
    * constructor-fixed map.
    */
  private var noteTypes: Map[String, NoteTypeSpec] = initialNoteTypes

  private final case class StoredNote(
      noteType: String,
      fields: Vector[(String, String)],
      tags: Vector[String],
      modCount: Int,
  )

  private var notes: Map[Long, StoredNote]   = Map.empty
  private var cardDecks: Map[Long, DeckPath] = Map.empty
  private var cardsByNote: Map[Long, Vector[Long]] = Map.empty

  /** HOW MUCH REVIEW EACH CARD CARRIES, and the ONLY fact here that is not already derivable.
    *
    * A card's ORDINAL is not stored, because `cardsByNote` holds its note's cards in order and
    * the ordinal is the position — storing it too would be a second copy that can disagree
    * with the first. Review counts have no such source, so they are held explicitly.
    *
    * EVERY CARD IS ENTERED HERE AT CREATION, at zero, rather than being absent until reviewed.
    * An absent entry would make "never reviewed" and "no such card" the same observation, and
    * telling those apart is the whole job of [[standingOf]]: pricing a destructive move at zero
    * because the card could not be found is the failure this fake exists to make impossible.
    */
  private var cardReviews: Map[Long, Int] = Map.empty
  private var nextId: Long                   = 1000L

  private def fresh(): Long = { nextId += 1; nextId }

  /** Anki case-folds tags when matching and unifying them. */
  private def foldTag(t: String): String = t.toLowerCase(java.util.Locale.ROOT)

  private def rejectWhitespace(tags: Vector[String]): Either[AnkiError, Unit] =
    tags.find(t => t.exists(_.isWhitespace)) match
      case Some(bad) => Left(AnkiError.TagContainsWhitespace(bad))
      case None      => Right(())

  /** How many cards a note of this type generates.
    *
    * ANSWERED FROM THE NOTE TYPE'S SPEC, NEVER FROM ITS NAME, since 2026-08-28 — `IN-FLIGHT.md`
    * item 31. The rule itself lives in [[CardGeneration]], where it is tested against this
    * repository's real templates rather than only through this fake.
    *
    * WHAT IT USED TO DO, kept visible because the failure shape is the lesson. It matched on the
    * note type's NAME: reversed gave two, concept-descriptor gave two or three by its gate
    * field, and **everything else gave one**. So a note type a test had just defined with three
    * templates produced a note with ONE card, silently, and any assertion about that note's
    * cards measured something other than what it appeared to. Not hypothetical: a test on
    * 2026-08-28 was quietly re-pointed at a named note type to work around exactly this.
    *
    * WHY IT MATTERED MORE THAN TIDINESS. The feature that prices a note-type change — "this
    * destroys N cards holding M reviews, approve it by name" — is ABOUT card counts, and it is
    * verified against this fake. A double that is wrong about card counts cannot validate the
    * one feature whose job is counting cards before review history is spent.
    *
    * AN UNKNOWN NOTE TYPE IS AN ERROR HERE, NOT A ONE. `addNote` has already refused an unknown
    * note type before reaching this point, so arriving without a spec would mean the fake had
    * contradicted itself. Returning a plausible number instead is precisely how the previous
    * version stayed wrong for a week.
    */
  private def cardCountOf(noteType: String, fields: Vector[(String, String)]): Int =
    noteTypes
      .get(noteType)
      .map(spec => CardGeneration.cardCount(spec, fields))
      .getOrElse(
        sys.error(s"the fake was asked for the card count of an unknown note type '$noteType'")
      )

  // ------------------------------------------------------------------ queries ----

  def noteTypeNames: Either[AnkiError, Vector[String]] = Right(noteTypes.keys.toVector.sorted)

  private def noteTypeAt(name: String): Either[AnkiError, NoteTypeSpec] =
    noteTypes.get(name).toRight(AnkiError.NoSuchNoteType(name))

  def fieldNames(noteType: String): Either[AnkiError, Vector[String]] =
    noteTypeAt(noteType).map(_.fields.toVector)

  def noteTypeTemplates(noteType: String): Either[AnkiError, Map[String, CardTemplate]] =
    noteTypeAt(noteType).map(_.templates.toVector.toMap)

  def noteTypeStyling(noteType: String): Either[AnkiError, String] =
    noteTypeAt(noteType).map(_.styling)

  /** Answered from the flag the note type was CREATED with, never inferred from its name, its
    * stylesheet or its templates — see [[Anki.noteTypeIsCloze]] for the note type that makes
    * every such inference get it backwards.
    */
  def noteTypeIsCloze(noteType: String): Either[AnkiError, Boolean] =
    noteTypeAt(noteType).map(_.isCloze)

  /** THE SEARCHES A CALLER ASKED TO BROWSE, in order, so a test can assert WHAT was asked for.
    *
    * NOTHING IS SEARCHED. This fake models a collection, and browsing is a window-opening
    * operation on a running application — there is no in-memory equivalent to reproduce. Faking
    * the SEARCH here would invent an Anki query engine and let a test pass against a dialect
    * Anki does not speak, which is worse than recording the request and saying so.
    */
  private val browsed = scala.collection.mutable.ArrayBuffer.empty[String]

  def browsedQueries: Vector[String] = browsed.toVector

  def browse(query: String): Either[AnkiError, Unit] =
    browsed += query
    Right(())

  /** BOTH HOMES, MODELLED AS THE MEANING RATHER THAN AS A QUERY. The wire client asks Anki with
    * a search string; this answers the same question by looking, which is the whole reason the
    * port method is semantic — a fake reproducing Anki's query dialect would let a test pass
    * against a language Anki does not speak.
    */
  def ownedNotes: Either[AnkiError, Vector[AnkiNoteId]] =
    val prefix = foldTag(s"${OwnedTag.SrcPrefix}::")
    Right(
      notes.collect {
        case (id, n)
            if n.tags.exists(t => foldTag(t).startsWith(prefix)) ||
              n.fields.exists((name, v) => name == Marker.IdentityField && v.trim.nonEmpty) =>
          AnkiNoteId(id)
      }.toVector.sortBy(_.value)
    )

  def notesInfo(ids: Vector[AnkiNoteId]): Either[AnkiError, Vector[ObservedNote]] =
    ids.foldLeft[Either[AnkiError, Vector[ObservedNote]]](Right(Vector.empty)) { (acc, id) =>
      for
        soFar <- acc
        n     <- notes.get(id.value).toRight(AnkiError.NoSuchNote(id))
      yield soFar :+ ObservedNote(id, n.noteType, n.fields, n.tags)
    }

  def cardsOf(ids: Vector[AnkiNoteId]): Either[AnkiError, Vector[AnkiCardId]] =
    Right(ids.flatMap(i => cardsByNote.getOrElse(i.value, Vector.empty)).map(AnkiCardId(_)))

  /** WHERE EACH CARD SITS AND WHAT IT CARRIES.
    *
    * THE ORDINAL IS DERIVED, NOT STORED. `cardsByNote` holds a note's cards in the order Anki
    * generated them, so a card's ordinal IS its position in that vector. Deriving it keeps one
    * source of truth; storing it beside would let the two disagree, and a disagreement about an
    * ordinal is a disagreement about which card a narrowing destroys.
    *
    * AN UNKNOWN CARD IS A LOUD FAILURE, WHICH IS THE POINT. The real interpreter's `cardsInfo`
    * answers `{}` for a card that does not exist rather than erroring (MEASURED 2026-08-27), so
    * a caller that shrugged at a missing card would price a destructive move at zero reviews —
    * the one answer that makes it look free. This fake refuses instead, so that any code with
    * that shape fails here rather than in somebody's collection.
    */
  def standingOf(cards: Vector[AnkiCardId]): Either[AnkiError, Vector[CardStanding]] =
    cards.traverse { card =>
      for
        ordinal <- cardsByNote.collectFirst {
          case (_, siblings) if siblings.contains(card.value) => siblings.indexOf(card.value)
        }.toRight(
          AnkiError.UnsupportedOperation(
            s"read the standing of card ${card.value}",
            "no note in this collection holds that card, so it has no ordinal",
          )
        )
        reviews <- cardReviews
          .get(card.value)
          .toRight(
            AnkiError.UnsupportedOperation(
              s"read the standing of card ${card.value}",
              "that card has no review record, which cannot happen for a card this collection created",
            )
          )
      yield CardStanding(card, ordinal, reviews)
    }

  /** Test-only: give a card a review history, so that a priced decision can be driven.
    *
    * A COUNT RATHER THAN REVIEWS. Nothing in this tool replays a review, and
    * [[CardStanding]] carries a count for the same reason — modelling individual reviews here
    * would invite code written as though the history could be restored, and it cannot.
    *
    * REFUSES AN UNKNOWN CARD, so a test that mistypes an id fails on the setup line rather
    * than by quietly asserting against a price that was never recorded.
    */
  def recordReviews(card: AnkiCardId, reviews: Int): Unit =
    if !cardReviews.contains(card.value) then
      throw IllegalArgumentException(s"no such card in this collection: ${card.value}")
    cardReviews += card.value -> reviews

  def deckOf(card: AnkiCardId): Either[AnkiError, Option[DeckPath]] =
    Right(cardDecks.get(card.value))

  /** Test-only: how many times a note's fields have been written. Lets a test assert that a
    * re-run really touched nothing, rather than trusting that it did.
    */
  def modCountOf(id: AnkiNoteId): Option[Int] = notes.get(id.value).map(_.modCount)

  // ------------------------------------------------------------------ writes ----

  /** ANKI REFUSES A DUPLICATE NAME; `createModel` is not an upsert.
    *
    * Modelled here because the alternative a fake could offer — quietly replacing the existing
    * definition — would let a test prove that an installer "repairs" a note type when against a
    * real collection it would simply be refused. The installer's own protection is that it
    * reads `noteTypeNames` first and creates only what is absent; this is the backstop that
    * makes a caller which skips that step fail rather than appear to work.
    */
  def createNoteType(spec: NoteTypeSpec): Either[AnkiError, Unit] =
    if noteTypes.contains(spec.name) then Left(AnkiError.NoteTypeExists(spec.name))
    else Right(noteTypes += spec.name -> spec)

  /* The three repair operations, modelled on what Anki ACTUALLY does rather than on what a
   * caller would like it to do. Each quirk below is reproduced deliberately: a fake that is
   * kinder than the real thing lets a test prove something a real collection will not honour. */

  /** IDEMPOTENT AND ORDER-PRESERVING, mirroring `__init__.py:1437-1441`, where the add is
    * guarded by `if fieldName not in fieldMap`. A field already declared stays exactly where it
    * is; a fake that appended unconditionally would let a duplicate field pass a test.
    */
  def addNoteTypeField(noteType: String, field: String): Either[AnkiError, Unit] =
    withNoteType(noteType) { spec =>
      if spec.fields.toVector.contains(field) then ()
      else noteTypes += noteType -> spec.copy(fields = spec.fields :+ field)
    }

  /** SILENTLY IGNORES A TEMPLATE NAME THE TYPE DOES NOT HAVE, which is the single most important
    * behaviour in this file to reproduce faithfully. `__init__.py:1301-1303` iterates the
    * COLLECTION's templates and looks each one up in what it was given, so a caller that renamed
    * a template in the repository and never in the collection gets a clean exit and no change.
    *
    * A fake that instead inserted the unknown template, or refused it, would conceal exactly the
    * failure [[NoteTypeInstaller.repair]] refuses IN ADVANCE in order to avoid.
    */
  def setNoteTypeTemplates(
      noteType: String,
      templates: Map[String, CardTemplate],
  ): Either[AnkiError, Unit] =
    withNoteType(noteType) { spec =>
      val updated = spec.templates.map((name, existing) => name -> templates.getOrElse(name, existing))
      noteTypes += noteType -> spec.copy(templates = updated)
    }

  def setNoteTypeStyling(noteType: String, css: String): Either[AnkiError, Unit] =
    withNoteType(noteType)(spec => noteTypes += noteType -> spec.copy(styling = css))

  /** All three repairs fail the same way on a name the collection does not hold — Anki raises
    * `model was not found` at `__init__.py:1298` and `:1320`, and `getModel` raises for the
    * field action — so the check lives in one place rather than in three.
    */
  private def withNoteType(name: String)(f: NoteTypeSpec => Unit): Either[AnkiError, Unit] =
    noteTypes.get(name).toRight(AnkiError.NoSuchNoteType(name)).map(f)

  private def checkFields(
      noteType: String,
      fields: Vector[(String, String)],
  ): Either[AnkiError, Unit] =
    for
      known <- noteTypeAt(noteType)
      _ <- fields
        .map(_._1)
        .find(!known.fields.toVector.contains(_))
        .map(f => AnkiError.UnknownField(noteType, f))
        .toLeft(())
    yield ()

  def addNote(note: NewNote): Either[AnkiError, AnkiNoteId] =
    val tagStrings = note.tags.toVector.map(_.value)
    for
      _ <- rejectWhitespace(tagStrings)
      _ <- checkFields(note.noteType, note.fields)
      _ <-
        if allowDuplicate then Right(())
        else
          val firstField = note.fields.headOption.map(_._2).getOrElse("")
          notes.values
            .find(n => n.noteType == note.noteType && n.fields.headOption.exists(_._2 == firstField))
            .map(_ => AnkiError.DuplicateNote(firstField))
            .toLeft(())
    yield
      val id = fresh()
      // EVERY FIELD THE NOTE TYPE DECLARES, WHETHER THE CALLER NAMED IT OR NOT.
      //
      // ANKI CANNOT REPRESENT A NOTE MISSING ONE. A note's fields ARE its note type's field
      // list — adding a field to a type gives every existing note that field, empty — so a note
      // holding fewer is a state no collection can be in.
      //
      // THE FAKE USED TO ALLOW IT, and on 2026-08-29 a test built exactly that impossible note
      // to model "a note synced before the `Identity` field existed". It then proved the wrong
      // thing twice over: `updateNoteFields` merges over the fields a note ALREADY has, so
      // writing the missing one was silently dropped while still bumping the modification
      // count. Both the test and the behaviour it measured were fictional.
      //
      // THE REAL PRE-MIGRATION STATE IS THE FIELD PRESENT AND EMPTY, which is what a collection
      // looks like after `install-note-types --repair` adds it. Padding here makes the fake
      // produce that state rather than one Anki has no way to reach.
      val declared = noteTypes.get(note.noteType).map(_.fields.toVector).getOrElse(Vector.empty)
      val supplied = note.fields.toMap
      val padded   = declared.map(name => name -> supplied.getOrElse(name, ""))
      notes += id -> StoredNote(note.noteType, padded, tagStrings, modCount = 0)
      val cards = Vector.fill(cardCountOf(note.noteType, note.fields))(fresh())
      cardsByNote += id -> cards
      cards.foreach(c => cardDecks += c -> note.deck)
      cards.foreach(c => cardReviews += c -> 0)
      AnkiNoteId(id)

  /** No early-out, deliberately. Anki moves the modification stamp even for an identical
    * write, which is why "zero changes" has to be decided before the call rather than by it.
    */
  def updateNoteFields(
      id: AnkiNoteId,
      fields: Vector[(String, String)],
  ): Either[AnkiError, Unit] =
    for
      existing <- notes.get(id.value).toRight(AnkiError.NoSuchNote(id))
      _        <- checkFields(existing.noteType, fields)
    yield
      val merged = existing.fields.map((name, old) => name -> fields.toMap.getOrElse(name, old))
      notes += id.value -> existing.copy(fields = merged, modCount = existing.modCount + 1)

  /** MODELS THE TRAP RATHER THAN THE INTENTION, which is the whole reason this fake exists.
    *
    * Anki's `updateNoteModel` blanks every field of the new note type and then fills in only
    * the names it was given, matching CASE-INSENSITIVELY and ignoring any name the new type
    * does not have — with no error on either count. That is reproduced here exactly. Note in
    * particular what is NOT done: [[checkFields]] is deliberately not called, so a field name
    * the target does not declare is dropped SILENTLY here, exactly as it is dropped silently by
    * Anki. A fake that rejected it would let a test prove a safety this tool does not have.
    *
    * The tag set is REPLACED, never merged, for the same reason: `anki_note.tags = new_tags` is
    * unconditional in the add-on. Everything the caller wants the note to keep must be in one
    * of the two tag arguments.
    *
    * WHAT THIS FAKE DOES NOT MODEL, stated so it is not mistaken for a guarantee: card
    * generation. The note's cards are left exactly as they are, with their ids and their decks.
    * That matches what a live probe reported for a move between two note types of the SAME
    * shape, and it is deliberately not extrapolated to the case where the ordinals no longer
    * fit — that case is refused by [[obsidiananki.plan.Retyping]] rather than simulated here.
    */
  def changeNoteType(
      id: AnkiNoteId,
      to: String,
      fields: Vector[(String, String)],
      ownedTags: NonEmptyVector[OwnedTag],
      preservedTags: Vector[String],
  ): Either[AnkiError, Unit] =
    val allTags = ownedTags.toVector.map(_.value) ++ preservedTags
    for
      existing <- notes.get(id.value).toRight(AnkiError.NoSuchNote(id))
      target   <- noteTypeAt(to)
      _        <- rejectWhitespace(allTags)
    yield
      val supplied = fields.map((name, value) => name.toLowerCase(java.util.Locale.ROOT) -> value).toMap
      val blanked = target.fields.toVector.map { name =>
        name -> supplied.getOrElse(name.toLowerCase(java.util.Locale.ROOT), "")
      }
      notes += id.value -> existing.copy(
        noteType = to,
        fields = blanked,
        tags = allTags,
        modCount = existing.modCount + 1,
      )

  private def mutateTags(
      ids: Vector[AnkiNoteId],
      tags: Vector[OwnedTag],
  )(f: (Vector[String], Vector[String]) => Vector[String]): Either[AnkiError, Unit] =
    val incoming = tags.map(_.value)
    for
      _ <- rejectWhitespace(incoming)
      _ <- ids
        .find(i => !notes.contains(i.value))
        .map(AnkiError.NoSuchNote(_))
        .toLeft(())
    yield ids.foreach { i =>
      val n = notes(i.value)
      notes += i.value -> n.copy(tags = f(n.tags, incoming))
    }

  /** Adds only the tags given. Tags already on the note — including a person's own — are
    * left exactly as they are.
    */
  def addTags(ids: Vector[AnkiNoteId], tags: Vector[OwnedTag]): Either[AnkiError, Unit] =
    mutateTags(ids, tags) { (existing, incoming) =>
      val present = existing.map(foldTag).toSet
      existing ++ incoming.filterNot(t => present.contains(foldTag(t)))
    }

  /** Removes only the tags given, matched case-insensitively as Anki matches them. */
  def removeTags(ids: Vector[AnkiNoteId], tags: Vector[OwnedTag]): Either[AnkiError, Unit] =
    mutateTags(ids, tags) { (existing, incoming) =>
      val doomed = incoming.map(foldTag).toSet
      existing.filterNot(t => doomed.contains(foldTag(t)))
    }

  def changeDeck(cards: Vector[AnkiCardId], deck: DeckPath): Either[AnkiError, Unit] =
    Right(cards.foreach(c => cardDecks += c.value -> deck))

  /** SUSPENSION IS A FACT SEPARATE FROM THE DECK, and modelling it that way is the point.
    *
    * In Anki a suspended card keeps its deck, its interval, its ease and its whole review log —
    * `suspended(card)` is nothing but `card.queue == -1` (`__init__.py:1065-1067`). A fake that
    * moved the card, or forgot its deck, would let a test prove suspension is lossless when the
    * real thing had quietly cost something. So it is one flag, beside state nothing else touches.
    *
    * IDEMPOTENT IN BOTH DIRECTIONS, as Anki is: suspending twice, or unsuspending a card that
    * was never suspended, changes nothing and reports nothing.
    */
  private var suspendedCards: Set[Long] = Set.empty

  def suspend(cards: Vector[AnkiCardId]): Either[AnkiError, Unit] =
    Right(suspendedCards ++= cards.map(_.value))

  def unsuspend(cards: Vector[AnkiCardId]): Either[AnkiError, Unit] =
    Right(suspendedCards --= cards.map(_.value))

  /** Test affordance rather than part of the algebra: nothing in this tool ASKS whether a card
    * is suspended, so an operation for it would have no caller. A test needs it to assert an
    * outcome rather than an interaction.
    */
  def isSuspended(card: AnkiCardId): Boolean = suspendedCards.contains(card.value)

  /** Test affordance: a person applying a tag of their own inside Anki.
    *
    * Not part of the algebra — the tool can never do this. It exists so a test can set up
    * the situation the ownership rule protects against.
    */
  def simulateUserTag(id: AnkiNoteId, tag: String): Unit =
    notes.get(id.value).foreach(n => notes += id.value -> n.copy(tags = n.tags :+ tag))

object InMemoryAnki:

  /** A COLLECTION INTO WHICH THIS TOOL'S FIVE NOTE TYPES HAVE ALREADY BEEN INSTALLED, read
    * from the very files `createModel` is given — each type's `manifest.json` under
    * `resources/note-types/`, and the templates and stylesheets it names.
    *
    * DERIVED, NOT RESTATED, AND THE SOURCE OF THE DERIVATION MOVED ON 2026-08-21. It used to
    * be `Marker.FieldOrder.byNoteType`, which was one step better than the string literals
    * before it. Reading the manifests instead ties the fake to what a real collection will
    * actually be given, which closes the disagreement that produces Anki's least helpful
    * error: a wrong field name is reported on create as "cannot create note because it is
    * empty", indistinguishable from a genuinely empty note, and on update as no error at all.
    *
    * THIS IS ALSO THE BROADEST DETECTOR THE PROJECT HAS for a manifest drifting away from
    * `model/Marker.scala`, and it is worth knowing about because it is not obvious. The fake
    * refuses a write naming a field its note type does not declare, so a manifest that lost
    * `Context`, or a manifest whose `name` no longer matches `Marker.NoteTypes`, fails tests in
    * suites that have nothing to do with note type assets.
    *
    * MEASURED 2026-08-21, in a scratch copy of this project, suite size 511:
    *   - `Context` deleted from `basic/manifest.json` — 38 failed, across ten suites.
    *   - `Context` deleted from ALL FIVE manifests — 43 failed.
    *   - `basic/manifest.json`'s `name` changed to `Obsidian Basik` — 57 failed, across twelve
    *     suites, `PlannerTest` (15), `NoteTypeInstallTest` (8) and `InMemoryAnkiTest` (7)
    *     worst hit.
    *
    * So a MINORITY of the suite in every case — this comment used to say "most of the suite" —
    * but a minority scattered across `plan`, `cli`, `extract` and `anki` rather than confined
    * to one test, and the spread is what makes it a detector at all.
    * `anki/NoteTypeAssets.test.scala` is what turns that into a message a reader can act on.
    *
    * IT THROWS IF THE FILES CANNOT BE READ, deliberately and with every error named. There is
    * no honest fallback: a fake built from a partially-loaded set of note types would let tests
    * pass while asserting against a collection shape that does not exist.
    *
    * ⚠️ IT DOES NOT MODEL AN UNINSTALLED COLLECTION — but that is no longer a loss, because
    * `InMemoryAnki(noteTypes = Map.empty)` now does, and the installer's tests use it. Before
    * `createNoteType` existed there was no way to get from one to the other.
    */
  val defaultNoteTypes: Map[String, NoteTypeSpec] =
    NoteTypeAssets.all match
      case Right(assets) => assets.map(asset => asset.spec.name -> asset.spec).toMap
      case Left(errors) =>
        throw new IllegalStateException(
          "the note type definitions under resources/note-types/ could not be loaded: " +
            errors.map(_.describe).mkString("; ")
        )

  def apply(
      // A FIXTURE DEFAULT, KEPT DELIBERATELY, AND THE FIRST OF THESE IS BORDERLINE.
      // `allowDuplicate` is plainly the loud kind: a test about duplicate refusal that got the
      // wrong value fails. `noteTypes` is less clear — a test meaning to start from an EMPTY
      // profile and forgetting would silently receive this tool's five note types, and an
      // installation test could then pass for the wrong reason. Kept because the class docstring
      // names the default as a decision ('a test wanting a FRESH profile sets this to Map.empty'),
      // which is what having an author means. Revisit if an installation test is ever puzzling.
      // ast-grep-ignore: default-parameter
      noteTypes: Map[String, NoteTypeSpec] = defaultNoteTypes,
      // ast-grep-ignore: default-parameter
      allowDuplicate: Boolean = true,
  ): InMemoryAnki = new InMemoryAnki(noteTypes, allowDuplicate)
