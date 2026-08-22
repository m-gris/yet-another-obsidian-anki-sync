package obsidiananki.anki

import cats.data.NonEmptyVector
import obsidiananki.model.*

/** The fake's job is not to be convenient — it is to be UNFORGIVING in the same places the
  * real collection is. Every constraint asserted here is one this project has actually been
  * bitten by, or would have been.
  */
class InMemoryAnkiTest extends munit.FunSuite:

  def deck(segments: String*): DeckPath =
    DeckPath(NonEmptyVector.fromVectorUnsafe(segments.toVector))

  def key(id: String, segments: String*): CardKey =
    CardKey(
      NoteId.fromFrontmatter(id).toOption.get,
      HeadingPath(
        NonEmptyVector.fromVectorUnsafe(
          segments.toVector.map(s => HeadingSegment.fromExtractedText(s).toOption.get)
        )
      ),
    )

  def basicNote(tag: OwnedTag, front: String = "F", back: String = "B"): NewNote =
    NewNote(
      noteType = Marker.NoteTypes.Basic,
      deck = deck("Obsidian", "System-Design"),
      fields = Vector("Front" -> front, "Back" -> back),
      tags = NonEmptyVector.one(tag),
    )

  def added(anki: InMemoryAnki, note: NewNote): AnkiNoteId =
    anki.addNote(note).fold(e => fail(s"addNote failed: $e"), identity)

  // ------------------------------------------------- the constraint that matters ----

  /** This is the whole reason the fake exists. A fake that merely stored what it was given
    * would accept a tag with a space in it and every test would pass — right up until the
    * live collection tore it in two.
    */
  test("a tag containing whitespace is REJECTED, as Anki would tear it in two") {
    val anki = InMemoryAnki()
    val bad  = OwnedTag.unsafeFromString("src::n1::CAP Theorem/Definition")
    assertEquals(
      anki.addNote(basicNote(bad)),
      Left(AnkiError.TagContainsWhitespace("src::n1::CAP Theorem/Definition")),
    )
  }

  /** The other half of the same coin: what our encoder actually produces must be accepted.
    * This is the seam between the key model and the Anki model, tested end to end.
    */
  test("what TagCodec produces IS accepted, for the hostile real-world headings") {
    val anki = InMemoryAnki()
    val keys = List(
      key("fix-multi-topic", "Multi-Topic", "CAP Theorem", "Definition"),
      key("fix-messaging", "Messaging Patterns", "Cost / benefit"),
      key("2026-08-18", "Daily", "Note"),
      key("n1", "Café ☕ notes"),
    )
    keys.foreach { k =>
      val tag = TagCodec.encode(k)
      assert(
        anki.addNote(basicNote(tag)).isRight,
        s"the encoder produced a tag Anki would reject: ${tag.value}",
      )
    }
  }

  test("tag lookup is case-insensitive, as Anki's is") {
    val anki = InMemoryAnki()
    added(anki, basicNote(OwnedTag.unsafeFromString("src::n1::definition")))
    assertEquals(anki.findNotesByTagPrefix("SRC::N1").map(_.size), Right(1))
  }

  // ------------------------------------------------- create ----

  test("a created note is findable by its tag prefix and carries its fields") {
    val anki = InMemoryAnki()
    val tag  = TagCodec.encode(key("n1", "Coupling", "Temporal coupling"))
    val id   = added(anki, basicNote(tag, front = "Temporal coupling", back = "All up at once."))

    assertEquals(anki.findNotesByTagPrefix("src::"), Right(Vector(id)))
    val info = anki.notesInfo(Vector(id)).fold(e => fail(s"$e"), identity).head
    assertEquals(info.fields.toMap.get("Back"), Some("All up at once."))
    assertEquals(info.tags, Vector(tag.value))
  }

  test("B12: a note cannot be created without a tag — the type forbids it") {
    // NewNote.tags is a NonEmptyVector, so an unenumerable note (one with no src:: tag,
    // invisible to lookup, reconciler and prune forever) cannot be expressed at all.
    // This test documents that the guarantee is structural rather than checked.
    val note = basicNote(TagCodec.encode(key("n1", "X")))
    assertEquals(note.tags.length, 1)
  }

  // ------------------------------------------------- update ----

  /** B11's premise. If this were false, "zero changes on re-run" could be achieved by
    * sending everything and letting Anki dedupe — it cannot.
    */
  test("B11: updateNoteFields bumps the note even when the fields are IDENTICAL") {
    val anki   = InMemoryAnki()
    val id     = added(anki, basicNote(TagCodec.encode(key("n1", "X"))))
    val before = anki.modCountOf(id)

    val same = Vector("Front" -> "F", "Back" -> "B")
    assertEquals(anki.updateNoteFields(id, same), Right(()))

    assertNotEquals(anki.modCountOf(id), before, "an identical write must still count")
  }

  test("updating an unknown note fails rather than silently doing nothing") {
    val anki = InMemoryAnki()
    assert(anki.updateNoteFields(AnkiNoteId(999999L), Vector("Front" -> "x")).isLeft)
  }

  test("a field the note type does not have is rejected") {
    val anki = InMemoryAnki()
    val id   = added(anki, basicNote(TagCodec.encode(key("n1", "X"))))
    assert(anki.updateNoteFields(id, Vector("Nonexistent" -> "x")).isLeft)
  }

  // ------------------------------------------------- tag ownership ----

  /** The clobbering hazard. Anki being downstream makes bookkeeping there acceptable; it
    * does not hand us the tag namespace.
    */
  test("a person's own tags survive our tag writes untouched") {
    val anki = InMemoryAnki()
    val src  = TagCodec.encode(key("n1", "X"))
    val id   = added(anki, basicNote(src))

    anki.simulateUserTag(id, "leech")
    anki.simulateUserTag(id, "my::own::scope")

    val sha = OwnedTag.sha("deadbeef")
    assertEquals(anki.addTags(Vector(id), Vector(sha)), Right(()))
    assertEquals(anki.removeTags(Vector(id), Vector(src)), Right(()))

    val tags = anki.notesInfo(Vector(id)).fold(e => fail(s"$e"), identity).head.tags.toSet
    assert(tags.contains("leech"), s"a person's leech tag was wiped: $tags")
    assert(tags.contains("my::own::scope"), s"a person's own hierarchy was wiped: $tags")
    assert(tags.contains(sha.value))
    assert(!tags.contains(src.value), "our own tag should have been removed")
  }

  // ------------------------------------------------- cards vs notes ----

  /** The note/card impedance point: identity is per-note, decks and scheduling per-card. */
  test("a reversed note has two cards; a plain Basic has one") {
    val anki  = InMemoryAnki()
    val plain = added(anki, basicNote(TagCodec.encode(key("n1", "A"))))
    val rev = added(
      anki,
      basicNote(TagCodec.encode(key("n1", "B"))).copy(noteType = Marker.NoteTypes.BasicAndReversed),
    )
    assertEquals(anki.cardsOf(Vector(plain)).map(_.size), Right(1))
    assertEquals(anki.cardsOf(Vector(rev)).map(_.size), Right(2))
  }

  test("the ThreeWay switch decides whether a concept-descriptor note has 2 or 3 cards") {
    val anki = InMemoryAnki()
    def threeWay(tag: String, switch: String) =
      NewNote(
        noteType = Marker.NoteTypes.ConceptDescriptor,
        deck = deck("Obsidian"),
        fields = Vector(
          "Concept"     -> "Linearizability",
          "Descriptor"  -> "Definition",
          "Description" -> "…",
          "ThreeWay"    -> switch,
        ),
        tags = NonEmptyVector.one(OwnedTag.unsafeFromString(tag)),
      )
    val two   = added(anki, threeWay("src::n1::a", ""))
    val three = added(anki, threeWay("src::n1::b", "1"))
    assertEquals(anki.cardsOf(Vector(two)).map(_.size), Right(2))
    assertEquals(anki.cardsOf(Vector(three)).map(_.size), Right(3))
  }

  test("changeDeck moves every card of a note") {
    val anki = InMemoryAnki()
    val id = added(
      anki,
      basicNote(TagCodec.encode(key("n1", "B"))).copy(noteType = Marker.NoteTypes.BasicAndReversed),
    )
    val cards = anki.cardsOf(Vector(id)).fold(e => fail(s"$e"), identity)
    val moved = deck("Obsidian", "Patterns", "Nested")
    assertEquals(anki.changeDeck(cards, moved), Right(()))
    cards.foreach { c =>
      assertEquals(anki.deckOf(c), Right(Some(moved)))
    }
  }

  // ------------------------------------------------- note types ----

  test("note types are discoverable by name and report their fields") {
    val anki = InMemoryAnki()
    assert(anki.noteTypeNames.fold(_ => Nil, identity).contains(Marker.NoteTypes.ConceptDescriptor))
    assertEquals(
      anki.fieldNames(Marker.NoteTypes.ConceptDescriptor),
      Right(Vector("Concept", "Descriptor", "Description", "ThreeWay", "Context", "ConceptLabel")),
    )
    assert(anki.fieldNames("No Such Type").isLeft)
  }
