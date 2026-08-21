package obsidiananki.model

import cats.data.NonEmptyVector

class MarkerTest extends munit.FunSuite:

  def parsed(heading: String): Option[Marker] =
    Marker.parse(heading).fold(e => fail(s"unexpected marker error for '$heading': $e"), identity)

  // ---------------------------------------------------------------- parsing ----

  test("each of the seven markers parses to its own variant") {
    assertEquals(parsed("Term #flashcard/1way"), Some(Marker.TwoField(TwoFieldDirections.Forward)))
    assertEquals(parsed("Term #flashcard/2way"), Some(Marker.TwoField(TwoFieldDirections.Both)))
    assertEquals(
      parsed("Definition #flashcard/3way"),
      Some(Marker.ThreeField(ThreeFieldDirections.Default)),
    )
    assertEquals(
      parsed("Cost #flashcard/3way/all"),
      Some(Marker.ThreeField(ThreeFieldDirections.All)),
    )
    assertEquals(parsed("Bones #flashcard/cloze"), Some(Marker.Cloze))
    assertEquals(parsed("Cost / benefit #flashcard/table"), Some(Marker.Table))
    assertEquals(parsed("Path of blood #flashcard/sequence"), Some(Marker.Sequence))
  }

  test("3way and 3way/all are DIFFERENT variants, not the same one") {
    // The distinction decides whether a third card is generated, so collapsing them would
    // silently drop a card the author asked for.
    assertNotEquals(parsed("X #flashcard/3way"), parsed("X #flashcard/3way/all"))
  }

  test("an unmarked heading yields None — the ordinary case, not an error") {
    assertEquals(parsed("Introduction"), None)
    assertEquals(parsed("Best practices"), None)
    assertEquals(parsed(""), None)
  }

  test("an UNRECOGNISED marker fails loudly rather than counting as unmarked") {
    // A typo silently treated as "unmarked" means a card the author asked for never
    // appears — the same silent omission the whole design is built against.
    assert(Marker.parse("Term #flashcard/2-way").isLeft, "typo'd marker was silently ignored")
    assert(Marker.parse("Term #flashcard/reverse").isLeft)
    assert(Marker.parse("Term #flashcard/3way/some").isLeft)
    assert(Marker.parse("Term #flashcard").isLeft, "bare #flashcard selects no card kind")
  }

  test("two markers on one heading fail rather than silently picking one") {
    val result = Marker.parse("Term #flashcard/1way #flashcard/cloze")
    assert(result.isLeft, s"expected a Multiple error, got $result")
  }

  test("a marker anywhere in the heading is found, not only at the end") {
    assertEquals(parsed("#flashcard/1way Term"), Some(Marker.TwoField(TwoFieldDirections.Forward)))
  }

  // ---------------------------------------------------------------- note types ----

  test("note types are named, never numbered") {
    import Marker.*
    assertEquals(TwoField(TwoFieldDirections.Forward).noteTypeName, Some(NoteTypes.Basic))
    assertEquals(TwoField(TwoFieldDirections.Both).noteTypeName, Some(NoteTypes.BasicAndReversed))
    assertEquals(
      ThreeField(ThreeFieldDirections.Default).noteTypeName,
      Some(NoteTypes.ConceptDescriptor),
    )
    assertEquals(Marker.Cloze.noteTypeName, Some(NoteTypes.Cloze))
    assertEquals(Marker.Sequence.noteTypeName, Some(NoteTypes.ClozeSequence))
  }

  test("a table marker has no single note type") {
    // Its rows yield notes of two different types, so the note type belongs to the emitted
    // spec rather than to the marker.
    assertEquals(Marker.Table.noteTypeName, None)
  }

  test("B7: the concept-descriptor field order is Concept, Descriptor, Description") {
    assertEquals(Marker.ConceptDescriptorFields, Vector("Concept", "Descriptor", "Description"))
  }

  // ---------------------------------------------------------------- Body / B6 ----

  test("B6: an empty or blank body is rejected") {
    assertEquals(Body.fromExtracted(""), None)
    assertEquals(Body.fromExtracted("   "), None)
    assertEquals(Body.fromExtracted("\n\n  \n"), None)
  }

  test("B6: a real body is accepted and keeps its content") {
    val b = Body.fromExtracted("  Operations appear to take effect instantaneously.  ")
    assertEquals(b.map(_.value), Some("Operations appear to take effect instantaneously."))
  }

  // ---------------------------------------------------------------- CardSpec ----

  def aKey(segments: String*): CardKey =
    CardKey(
      NoteId.fromFrontmatter("fix-note").toOption.get,
      HeadingPath(
        NonEmptyVector.fromVectorUnsafe(
          segments.toVector.map(s => HeadingSegment.fromExtractedText(s).toOption.get)
        )
      ),
    )

  def body(s: String): Body = Body.fromExtracted(s).getOrElse(fail("test body was empty"))

  test("every spec reports its own key") {
    val k = aKey("Coupling", "Temporal coupling")
    val spec: CardSpec =
      CardSpec.TwoField(k, "Temporal coupling", body("All parties must be up."), TwoFieldDirections.Both)
    assertEquals(spec.key, k)
  }

  test("specs report their note type by name") {
    import Marker.NoteTypes
    val k = aKey("A", "B")
    assertEquals(
      CardSpec.TwoField(k, "f", body("b"), TwoFieldDirections.Forward).noteTypeName,
      NoteTypes.Basic,
    )
    assertEquals(
      CardSpec.TwoField(k, "f", body("b"), TwoFieldDirections.Both).noteTypeName,
      NoteTypes.BasicAndReversed,
    )
    assertEquals(
      CardSpec
        .ThreeField(k, "c", "d", body("desc"), ThreeFieldDirections.Default)
        .noteTypeName,
      NoteTypes.ConceptDescriptor,
    )
    assertEquals(
      CardSpec
        .Cloze(k, body("text"), NonEmptyVector.of(ClozeDeletion(1, ClozeGroup.Unlabelled("x"), Vector("x"))))
        .noteTypeName,
      NoteTypes.Cloze,
    )
    // The row card is a plain Basic: concept on the front, all descriptors on the back.
    assertEquals(
      CardSpec.TableRow(k, "Queue", NonEmptyVector.of(("Benefit", "Absorption"), ("Cost", "Delay")))
        .noteTypeName,
      NoteTypes.Basic,
    )
    assertEquals(
      CardSpec.Sequence(k, "Path of blood", body("<ul><li>a</li></ul>")).noteTypeName,
      NoteTypes.ClozeSequence,
    )
  }

  /** The field ORDER comes from [[Marker.ClozeSequenceFields]] and from nowhere else, which is
    * why this asserts the whole pair vector rather than the two values.
    *
    * WHAT THIS DOES NOT ASSERT, and it must not be read as asserting it: that the `Text` field
    * holds a list. `CardSpec.Sequence` guarantees nothing of the kind — that is established by
    * a refusal in `extract/`, and the value below is hand-built.
    */
  test("a sequence spec emits Title and Text, in that order") {
    val spec = CardSpec.Sequence(aKey("Anatomy", "Path"), "Path of blood", body("<ul><li>a</li></ul>"))
    assertEquals(
      spec.fields,
      Vector("Title" -> "Path of blood", "Text" -> "<ul><li>a</li></ul>"),
    )
    assertEquals(spec.fields.map(_._1), Marker.ClozeSequenceFields)
  }

  def threeField(directions: ThreeFieldDirections): CardSpec =
    CardSpec.ThreeField(
      aKey("Linearizability", "Definition"),
      concept = "Linearizability",
      descriptor = "Definition",
      description = body("Operations appear instantaneous."),
      directions = directions,
    )

  test("a three-field spec emits its content fields in the ruled order") {
    val spec = threeField(ThreeFieldDirections.Default)
    assertEquals(spec.fields.map(_._1).take(3), Marker.ConceptDescriptorFields)
    assertEquals(
      spec.fields.map(_._2).take(3),
      Vector("Linearizability", "Definition", "Operations appear instantaneous."),
    )
  }

  /** The third retrieval direction is switched on by a conditional field, not by a second
    * note type: Anki generates a card only when its front renders non-empty, so wrapping
    * Card 3's front in `{{#ThreeWay}}…{{/ThreeWay}}` makes it opt-in. If this field were
    * never set, `#flashcard/3way/all` would silently produce the same two cards as plain
    * `3way` — the project's signature failure in yet another place.
    */
  test("3way/all sets the ThreeWay switch and plain 3way leaves it empty") {
    def threeWayValue(d: ThreeFieldDirections): String =
      threeField(d).fields.toMap.getOrElse(
        Marker.ThreeWayField,
        fail(s"no ${Marker.ThreeWayField} field emitted"),
      )

    assert(threeWayValue(ThreeFieldDirections.All).nonEmpty, "third card would never generate")
    assertEquals(threeWayValue(ThreeFieldDirections.Default), "")
  }

  test("a table pair card is a three-field spec — no separate card model") {
    // "A table row is a set of concept-descriptor-description triples written compactly."
    val spec = CardSpec.ThreeField(
      aKey("Messaging", "Cost / benefit", "Queue", "Benefit"),
      concept = "Queue",
      descriptor = "Benefit",
      description = body("Load absorption."),
      directions = ThreeFieldDirections.Default,
    )
    assertEquals(spec.noteTypeName, Marker.NoteTypes.ConceptDescriptor)
    assertEquals(spec.fields.map(_._1).take(3), Marker.ConceptDescriptorFields)
  }

  test("table keys extend the heading path, so they reuse the same encoding") {
    val pair = aKey("Messaging", "Cost / benefit", "Queue", "Benefit")
    val row  = aKey("Messaging", "Cost / benefit", "Queue")
    assertNotEquals(TagCodec.encode(pair).value, TagCodec.encode(row).value)
    // And the slash inside the heading is still encoded, not posing as a separator.
    assert(TagCodec.encode(pair).value.contains("%2f"))
  }
