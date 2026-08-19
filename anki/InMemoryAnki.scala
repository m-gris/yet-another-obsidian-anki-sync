package obsidiananki.anki

import obsidiananki.model.OwnedTag

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
    noteTypes: Map[String, Vector[String]],
    allowDuplicate: Boolean,
) extends Anki[[A] =>> Either[AnkiError, A]]:

  private final case class StoredNote(
      noteType: String,
      fields: Vector[(String, String)],
      tags: Vector[String],
      modCount: Int,
  )

  private var notes: Map[Long, StoredNote]   = Map.empty
  private var cardDecks: Map[Long, DeckPath] = Map.empty
  private var cardsByNote: Map[Long, Vector[Long]] = Map.empty
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
    * Enough fidelity to make the card/note impedance real: a reversed note has two cards, a
    * three-field note two or three depending on whether the conditional switch is set.
    */
  private def cardCountOf(noteType: String, fields: Vector[(String, String)]): Int =
    val byName = fields.toMap
    noteType match
      case "Basic (and reversed card)" => 2
      case "3 way Concept-Descriptor" =>
        if byName.get("ThreeWay").exists(_.nonEmpty) then 3 else 2
      case "Cloze" => 1
      case _       => 1

  // ------------------------------------------------------------------ queries ----

  def noteTypeNames: Either[AnkiError, Vector[String]] = Right(noteTypes.keys.toVector.sorted)

  def fieldNames(noteType: String): Either[AnkiError, Vector[String]] =
    noteTypes.get(noteType).toRight(AnkiError.NoSuchNoteType(noteType))

  def findNotesByTagPrefix(prefix: String): Either[AnkiError, Vector[AnkiNoteId]] =
    val wanted = foldTag(prefix)
    Right(
      notes.collect {
        case (id, n) if n.tags.exists(t => foldTag(t).startsWith(wanted)) => AnkiNoteId(id)
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

  def deckOf(card: AnkiCardId): Either[AnkiError, Option[DeckPath]] =
    Right(cardDecks.get(card.value))

  /** Test-only: how many times a note's fields have been written. Lets a test assert that a
    * re-run really touched nothing, rather than trusting that it did.
    */
  def modCountOf(id: AnkiNoteId): Option[Int] = notes.get(id.value).map(_.modCount)

  // ------------------------------------------------------------------ writes ----

  private def checkFields(
      noteType: String,
      fields: Vector[(String, String)],
  ): Either[AnkiError, Unit] =
    for
      known <- noteTypes.get(noteType).toRight(AnkiError.NoSuchNoteType(noteType))
      _ <- fields
        .map(_._1)
        .find(!known.contains(_))
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
      notes += id -> StoredNote(note.noteType, note.fields, tagStrings, modCount = 0)
      val cards = Vector.fill(cardCountOf(note.noteType, note.fields))(fresh())
      cardsByNote += id -> cards
      cards.foreach(c => cardDecks += c -> note.deck)
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

  /** Test affordance: a person applying a tag of their own inside Anki.
    *
    * Not part of the algebra — the tool can never do this. It exists so a test can set up
    * the situation the ownership rule protects against.
    */
  def simulateUserTag(id: AnkiNoteId, tag: String): Unit =
    notes.get(id.value).foreach(n => notes += id.value -> n.copy(tags = n.tags :+ tag))

object InMemoryAnki:

  /** The stock note types, plus the project's concept-descriptor type with its conditional
    * switch field. Field names mirror Anki's documented defaults and are UNVERIFIED against
    * a live collection — confirming them is on the live-Anki checklist.
    */
  val defaultNoteTypes: Map[String, Vector[String]] = Map(
    "Basic"                    -> Vector("Front", "Back"),
    "Basic (and reversed card)" -> Vector("Front", "Back"),
    "Cloze"                    -> Vector("Text", "Back Extra"),
    "3 way Concept-Descriptor" -> Vector("Concept", "Descriptor", "Description", "ThreeWay"),
  )

  def apply(
      noteTypes: Map[String, Vector[String]] = defaultNoteTypes,
      allowDuplicate: Boolean = true,
  ): InMemoryAnki = new InMemoryAnki(noteTypes, allowDuplicate)
