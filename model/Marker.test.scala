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
      CardSpec.TwoField(
        k,
        "Temporal coupling",
        body("All parties must be up."),
        TwoFieldDirections.Both,
        context = "Coupling",
      )
    assertEquals(spec.key, k)
  }

  test("specs report their note type by name") {
    import Marker.NoteTypes
    val k = aKey("A", "B")
    assertEquals(
      CardSpec.TwoField(k, "f", body("b"), TwoFieldDirections.Forward, "ctx").noteTypeName,
      NoteTypes.Basic,
    )
    assertEquals(
      CardSpec.TwoField(k, "f", body("b"), TwoFieldDirections.Both, "ctx").noteTypeName,
      NoteTypes.BasicAndReversed,
    )
    assertEquals(
      CardSpec
        .ThreeField(k, "c", "d", body("desc"), ThreeFieldDirections.Default, "ctx")
        .noteTypeName,
      NoteTypes.ConceptDescriptor,
    )
    assertEquals(
      CardSpec
        .Cloze(
          k,
          body("text"),
          NonEmptyVector.of(ClozeDeletion(1, ClozeGroup.Unlabelled("x"), Vector("x"))),
          "ctx",
        )
        .noteTypeName,
      NoteTypes.Cloze,
    )
    // The row card is a plain Basic: concept on the front, all descriptors on the back.
    assertEquals(
      CardSpec
        .TableRow(k, "Queue", NonEmptyVector.of(("Benefit", "Absorption"), ("Cost", "Delay")), "ctx")
        .noteTypeName,
      NoteTypes.Basic,
    )
    assertEquals(
      CardSpec.Sequence(k, "Path of blood", body("<ul><li>a</li></ul>"), "ctx").noteTypeName,
      NoteTypes.ClozeSequence,
    )
  }

  /** The field ORDER comes from [[Marker.FieldOrder.ClozeSequence]] and from nowhere else,
    * which is why this asserts the whole pair vector rather than the three values.
    *
    * WHAT THIS DOES NOT ASSERT, and it must not be read as asserting it: that the `Text` field
    * holds a list. `CardSpec.Sequence` guarantees nothing of the kind — that is established by
    * a refusal in `extract/`, and the value below is hand-built.
    */
  test("a sequence spec emits Title, Text and Context, in that order") {
    val spec =
      CardSpec.Sequence(aKey("Anatomy", "Path"), "Path of blood", body("<ul><li>a</li></ul>"), "Anatomy")
    assertEquals(
      spec.fields,
      Vector(
        "Title"   -> "Path of blood",
        "Text"    -> "<ul><li>a</li></ul>",
        "Context" -> "Anatomy",
      ),
    )
    assertEquals(spec.fields.map(_._1), Marker.FieldOrder.ClozeSequence)
  }

  def threeField(directions: ThreeFieldDirections): CardSpec =
    CardSpec.ThreeField(
      aKey("Linearizability", "Definition"),
      concept = "Linearizability",
      descriptor = "Definition",
      description = body("Operations appear instantaneous."),
      directions = directions,
      context = "System design",
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
      context = "Messaging \u203a Cost / benefit",
    )
    assertEquals(spec.noteTypeName, Marker.NoteTypes.ConceptDescriptor)
    assertEquals(spec.fields.map(_._1).take(3), Marker.ConceptDescriptorFields)
  }

  // ------------------------------------------------ the declared field lists ----

  /** THE TEST THAT MAKES A TRUNCATING ZIP FAIL.
    *
    * `CardSpec.fields` builds two of its five arms with `Vector.zip`, and `zip` truncates to
    * the shorter side WITHOUT COMPLAINT. So `Marker.ConceptDescriptorFields` and
    * `Marker.ClozeSequenceFields` are ZIP OPERANDS whose length is load-bearing, while
    * `Marker.FieldOrder` is the DECLARATION of what each note type actually holds. Nothing but
    * this test ties the two together: without it, appending `"Context"` to a zip operand would
    * drop the field on the floor and leave every other test in the project green.
    *
    * ONE REPRESENTATIVE OF EACH OF THE SIX SHAPES, exhaustively — the five `CardSpec` variants
    * plus the second `TwoFieldDirections`, which is the only field-list-relevant distinction
    * inside a variant. Adding a sixth variant will not fail this test; it will fail
    * `CardSpec.fields`'s own exhaustive match first, which is a build error here.
    *
    * WHAT THIS DOES NOT PROVE, stated so it is not read as more than it is: that
    * `Marker.FieldOrder` agrees with the note types installed in a real collection. The other
    * half of that contract is `note-types/<slug>/manifest.json`, which `model/` cannot read —
    * `model/` deliberately depends on nothing. The manifest-versus-Scala comparison belongs to
    * the slice that writes the installer, and until it exists a disagreement is silent.
    */
  test("every spec's field NAMES are exactly its note type's declared field order") {
    val k = aKey("A", "B")
    val representatives: Vector[CardSpec] = Vector(
      CardSpec.TwoField(k, "f", body("b"), TwoFieldDirections.Forward, "ctx"),
      CardSpec.TwoField(k, "f", body("b"), TwoFieldDirections.Both, "ctx"),
      CardSpec.ThreeField(k, "c", "d", body("desc"), ThreeFieldDirections.Default, "ctx"),
      CardSpec.ThreeField(k, "c", "d", body("desc"), ThreeFieldDirections.All, "ctx"),
      CardSpec.Cloze(
        k,
        body("text"),
        NonEmptyVector.of(ClozeDeletion(1, ClozeGroup.Unlabelled("x"), Vector("x"))),
        "ctx",
      ),
      CardSpec.TableRow(k, "Queue", NonEmptyVector.of(("Benefit", "Absorption")), "ctx"),
      CardSpec.Sequence(k, "Title", body("<ul><li>a</li></ul>"), "ctx"),
    )

    representatives.foreach { spec =>
      assertEquals(
        spec.fields.map(_._1),
        Marker.FieldOrder.byNoteType(spec.noteTypeName),
        s"field names for note type '${spec.noteTypeName}' do not match its declared order",
      )
    }
  }

  test("Context is the LAST field of every note type, and every note type has one") {
    Marker.NoteTypes.All.foreach { nt =>
      val order = Marker.FieldOrder.byNoteType(nt)
      assertEquals(order.last, Marker.ContextField, s"'$nt' does not end with the Context field")
      assertEquals(
        order.count(_ == Marker.ContextField),
        1,
        s"'$nt' declares the Context field more than once",
      )
    }
  }

  /** `NoteTypes.All` is what an installer will iterate, so a name missing from it is a note
    * type that never gets created — and a name in it with no field list is a `createModel`
    * call that cannot be built.
    */
  test("NoteTypes.All and FieldOrder.byNoteType name the same five note types") {
    assertEquals(Marker.NoteTypes.All.size, 5)
    assertEquals(Marker.NoteTypes.All.distinct, Marker.NoteTypes.All)
    assertEquals(Marker.FieldOrder.byNoteType.keySet, Marker.NoteTypes.All.toSet)
  }

  /** RULED BY MARC, 2026-08-21: this tool writes only to note types it owns, so that changing
    * a template can never reach the rest of the collection. Pinned as literals rather than
    * derived from the constants, because the point is the exact strings a live collection must
    * hold — a test that read them back off `Marker` would pass no matter what they said.
    */
  test("the five note type names are the ones Marc chose") {
    assertEquals(
      Marker.NoteTypes.All,
      Vector(
        "Obsidian Basic",
        "Obsidian Basic (and reversed card)",
        "Obsidian Cloze",
        "Obsidian Cloze Sequence",
        "Obsidian Concept-Descriptor",
      ),
    )
  }

  test("table keys extend the heading path, so they reuse the same encoding") {
    val pair = aKey("Messaging", "Cost / benefit", "Queue", "Benefit")
    val row  = aKey("Messaging", "Cost / benefit", "Queue")
    assertNotEquals(TagCodec.encode(pair).value, TagCodec.encode(row).value)
    // And the slash inside the heading is still encoded, not posing as a separator.
    assert(TagCodec.encode(pair).value.contains("%2f"))
  }
