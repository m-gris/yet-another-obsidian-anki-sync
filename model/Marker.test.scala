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
    assertEquals(parsed("Cost / benefit #flashcard/table"), Some(Marker.Table(ThreeFieldDirections.Default, TableScope.Both)))
    assertEquals(parsed("Path of blood #flashcard/sequence"), Some(Marker.Sequence))
  }

  /** THE TABLE FAMILY, and the property that matters most is the DEFAULT.
    *
    * Bare `#flashcard/table` must keep meaning exactly what it meant before the family existed.
    * Any other choice would silently change what every already-marked table produces — adding
    * cards nobody asked for, or retiring cards that hold review history — on the next sync
    * after an upgrade, with no edit to any vault.
    */
  test("bare #flashcard/table is the two-direction default, unchanged by the family") {
    assertEquals(
      parsed("Cost / benefit #flashcard/table"),
      Some(Marker.Table(ThreeFieldDirections.Default, TableScope.Both)),
    )
    assertEquals(
      parsed("Cost / benefit #flashcard/table/2way"),
      Some(Marker.Table(ThreeFieldDirections.Default, TableScope.Both)),
      "the explicit 2way must mean the same as the bare marker, or upgrading a vault to the " +
        "explicit form would change what it produces",
    )
  }

  test("the table family selects one, two or three directions") {
    assertEquals(parsed("T #flashcard/table/1way"), Some(Marker.Table(ThreeFieldDirections.ValueOnly, TableScope.Both)))
    assertEquals(parsed("T #flashcard/table/2way"), Some(Marker.Table(ThreeFieldDirections.Default, TableScope.Both)))
    assertEquals(parsed("T #flashcard/table/3way"), Some(Marker.Table(ThreeFieldDirections.All, TableScope.Both)))
  }

  /** SCOPE IS THE SECOND AXIS, and independent of direction.
    *
    * Measured on a live collection before this existed: a table marked bare produced 8 cell
    * cards AND 2 row cards, and the row cards were a constant regardless of direction. So a row
    * card is not a fourth "way" — it is a different question about the same row, and the two
    * choices compose rather than laddering.
    */
  test("scope selects cells, rows, or both — independently of direction") {
    assertEquals(parsed("T #flashcard/table"), Some(Marker.Table(ThreeFieldDirections.Default, TableScope.Both)))
    assertEquals(parsed("T #flashcard/table/cells"), Some(Marker.Table(ThreeFieldDirections.Default, TableScope.CellsOnly)))
    assertEquals(parsed("T #flashcard/table/3way/cells"), Some(Marker.Table(ThreeFieldDirections.All, TableScope.CellsOnly)))
    assertEquals(parsed("T #flashcard/table/rows"), Some(Marker.Table(ThreeFieldDirections.Default, TableScope.RowsOnly)))
  }

  /** NAMING A DIRECTION ALONGSIDE `rows` IS REFUSED, not accepted and ignored.
    *
    * With no cell cards there is nothing for a direction to apply to, so such a marker would
    * name a choice that changes nothing at all — and a marker whose qualifier does nothing is
    * exactly the silent no-op this project treats as a defect rather than a convenience.
    */
  test("a direction alongside rows-only is refused, since it could not apply to anything") {
    assert(Marker.parse("T #flashcard/table/2way/rows").isLeft)
    assert(Marker.parse("T #flashcard/table/3way/rows").isLeft)
  }

  /** An unknown qualifier is a LOUD failure, not a silent fallback to the default. A typo like
    * `#flashcard/table/2ways` must not quietly produce the default set of cards.
    */
  test("an unrecognised table qualifier is refused, never defaulted") {
    assert(Marker.parse("T #flashcard/table/2ways").isLeft)
    assert(Marker.parse("T #flashcard/table/all").isLeft)
  }

  /** THE GATE IS INVERTED, AND THAT IS THE SAFETY PROPERTY. `ValueOnly` is EMPTY for the two
    * variants that keep the concept-recall card, so a note synced before the field existed —
    * whose value is empty — keeps that card rather than having it render blank and become a
    * candidate for Anki's Tools > Empty Cards, which would delete real review history.
    */
  test("only the one-direction variant sets the gate that hides the concept card") {
    def valueOnlyOf(d: ThreeFieldDirections): String =
      val spec = CardSpec.ThreeField(
        aKey("T", "Row"), "c", "d", body("v"), d, "ctx", "Kind",
      )
      spec.fields.toMap.apply(Marker.ValueOnlyField)

    assertEquals(valueOnlyOf(ThreeFieldDirections.ValueOnly), "1")
    assertEquals(valueOnlyOf(ThreeFieldDirections.Default), "")
    assertEquals(valueOnlyOf(ThreeFieldDirections.All), "")
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
    assertEquals(Marker.Table(ThreeFieldDirections.Default, TableScope.Both).noteTypeName, None)
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
        .ThreeField(k, "c", "d", body("desc"), ThreeFieldDirections.Default, "ctx", "Bone")
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
        .TableRow(k, "<table><tr><th>P</th></tr><tr><td>Queue</td></tr></table>", "<table></table>", "ctx")
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
      // A card built from HEADINGS, so nothing names the concept's kind. The table-built
      // sibling in `Tables.test.scala` is where a real label is asserted.
      conceptLabel = "",
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
      // FROM A TABLE, so the first column\'s header names what the concept is.
      conceptLabel = "Pattern",
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
    * half of that contract is `resources/note-types/<slug>/manifest.json`, which `model/`
    * cannot read — `model/` deliberately depends on nothing. _Amended 2026-08-21: this used to
    * end "the manifest-versus-Scala comparison belongs to the slice that writes the installer,
    * and until it exists a disagreement is silent". That slice landed._ The comparison is
    * `anki/NoteTypeAssets.test.scala`. What remains unproven is the last hop only: that the
    * live collection matches the manifests, which `install-note-types` reports at run time and
    * no test can settle.
    */
  test("every spec's field NAMES are exactly its note type's declared field order") {
    val k = aKey("A", "B")
    val representatives: Vector[CardSpec] = Vector(
      CardSpec.TwoField(k, "f", body("b"), TwoFieldDirections.Forward, "ctx"),
      CardSpec.TwoField(k, "f", body("b"), TwoFieldDirections.Both, "ctx"),
      CardSpec.ThreeField(k, "c", "d", body("desc"), ThreeFieldDirections.Default, "ctx", "Bone"),
      CardSpec.ThreeField(k, "c", "d", body("desc"), ThreeFieldDirections.All, "ctx", "Bone"),
      CardSpec.Cloze(
        k,
        body("text"),
        NonEmptyVector.of(ClozeDeletion(1, ClozeGroup.Unlabelled("x"), Vector("x"))),
        "ctx",
      ),
      CardSpec.TableRow(k, "<table></table>", "<table></table>", "ctx"),
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

  test("no note type sorts by a derived field, and every one declares Context") {
    Marker.NoteTypes.All.foreach { nt =>
      val order = Marker.FieldOrder.byNoteType(nt)
      assert(order.contains(Marker.ContextField), s"'$nt' has no ${Marker.ContextField} field")
      // Anki's Sort Field is field 1; a derived value there repeats down the Browse list.
      // WEAKENED FROM `order.last` on 2026-08-22 — see the twin in `anki/NoteTypeAssets.test`
      // for why position is not the property, and for the append rule that IS one.
      assert(
        !Set(Marker.ContextField, Marker.ConceptLabelField).contains(order.head),
        s"'$nt' sorts by '${order.head}', which this tool derives",
      )
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
