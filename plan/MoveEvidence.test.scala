package obsidiananki.plan

import cats.data.NonEmptyVector
import obsidiananki.anki.*
import obsidiananki.model.*
import obsidiananki.plan.SectionChain.{NoRecall, NoSectionChain}

/** WHAT THE EVIDENCE SHOWS WHEN A CARD'S SOURCE MOVED, AND WHAT THE RUN DOES ABOUT IT.
  *
  * ==What this suite is FOR==
  *
  * It pins the line between EVIDENCE and A GUESS, and — since Marc's 2026-09-05 ruling — the line
  * between evidence that MAY BE ACTED ON and evidence that may not. The first group is the case
  * the whole mechanism exists for: a heading that moved and took its content with it, whose Anki
  * note is given the new key and keeps every review it has. The group after it is longer and
  * matters more, because it is every way the evidence FAILS: reassigning on a pairing that did
  * not happen moves review history onto the wrong card, silently, and that is worse than never
  * having offered.
  *
  * ==The vacuity guards are not decoration==
  *
  * Two of these tests assert only that a fixture really differs where it claims to. Without them
  * every corroboration test below could be passing against two specs that are simply equal, which
  * agrees with everything and therefore proves nothing.
  */
class MoveEvidenceTest extends munit.FunSuite:

  // ------------------------------------------------------------------ fixtures ----

  def key(id: String, segments: String*): CardKey =
    CardKey(
      NoteId.fromFrontmatter(id).toOption.get,
      CardPath.Headings(
        HeadingPath(
          NonEmptyVector.fromVectorUnsafe(
            segments.toVector.map(s => HeadingSegment.fromExtractedText(s).toOption.get)
          )
        )
      ),
    )

  def noteKey(id: String): CardKey =
    CardKey(NoteId.fromFrontmatter(id).toOption.get, CardPath.Note)

  def body(s: String): Body = Body.fromExtracted(s).getOrElse(fail("empty test body"))

  val deck: DeckPath = DeckPath(NonEmptyVector.of("Obsidian", "System-Design"))

  val where: SourceRef = SourceRef("System Design.md", 12, SourceKind.Heading)

  def sourced(spec: CardSpec, at: SourceRef = where): SourcedSpec =
    SourcedSpec(spec, at, NoSectionChain, NoRecall, Vector.empty)

  /** A two-field card. `context` is passed in rather than derived, because the breadcrumb is
    * exactly the thing these tests vary and deriving it would put the fixture and the assertion
    * on the same side of the question.
    */
  def twoField(k: CardKey, front: String, back: String, context: String): CardSpec =
    CardSpec.TwoField(k, front, body(back), TwoFieldDirections.Forward, context)

  def threeField(
      k: CardKey,
      concept: String,
      descriptor: String,
      description: String,
      context: String,
  ): CardSpec =
    CardSpec.ThreeField(k, concept, descriptor, body(description), ThreeFieldDirections.Default, context, "")

  def cloze(k: CardKey, text: String, context: String): CardSpec =
    CardSpec.Cloze(
      k,
      body(text),
      NonEmptyVector.one(ClozeDeletion(1, ClozeGroup.Labelled(1), Vector("x"))),
      context,
    )

  /** The Anki note this tool WOULD have written for a spec — the note type it asks for, its
    * fields verbatim, and the content hash it would have recorded.
    *
    * BUILT FROM A SPEC RATHER THAN BY HAND, because that is what makes a fixture honest here: the
    * whole mechanism reads what the last successful sync wrote, and the only faithful way to say
    * "this note is the old card" is to build it from the old card's own spec.
    */
  def observed(spec: CardSpec, id: Long, tags: Vector[String] = Vector.empty): ObservedCard =
    ObservedCard(
      spec.key,
      ObservedNote(
        AnkiNoteId(id),
        spec.noteTypeName,
        spec.fields,
        tags :+ OwnedTag.sha(Planner.contentHash(spec)).value,
      ),
      Some(deck),
    )

  /** THE SAME NOTE WITH ONE FIELD OVERWRITTEN, for the cases where what Anki holds is NOT what
    * any spec would produce — a heading that was bolded after it was synced, for instance.
    */
  def observedWith(spec: CardSpec, id: Long, overrides: (String, String)*): ObservedCard =
    val patched = overrides.toMap
    val base    = observed(spec, id)
    base.copy(note =
      base.note.copy(fields = base.note.fields.map((n, v) => n -> patched.getOrElse(n, v)))
    )

  // ── THE MOVE THIS FILE EXISTS FOR, AS A PAIR OF SPECS ────────────────────────────────
  //
  // `## Essential numbers` held a `### Scale`; the author made the whole thing a subtree of a new
  // `## System design interview framework`. The heading's own text is untouched, its body is
  // untouched, and its breadcrumb is not.

  val strandedKey: CardKey = key("system-design", "essential numbers", "scale")
  val movedKey: CardKey =
    key("system-design", "system design interview framework", "essential numbers", "scale")

  val beforeMove: CardSpec = twoField(strandedKey, "Scale", "10^9 users", "System design")
  val afterMove: CardSpec =
    twoField(movedKey, "Scale", "10^9 users", "System design › System design interview framework")

  // ------------------------------------------------- the fixture is not vacuous ----

  test("VACUITY GUARD — the moved pair really differs in its key and its breadcrumb, and nothing else") {
    assertNotEquals(beforeMove.key, afterMove.key)
    val differing = beforeMove.fields.zip(afterMove.fields).collect {
      case ((name, was), (_, now)) if was != now => name
    }
    assertEquals(differing, Vector(Marker.ContextField, Marker.IdentityField))
  }

  test("VACUITY GUARD — the content hash cannot witness this move, which is why fields are compared") {
    // The one instrument that already exists for repairing a lost identity hashes `Context`, and
    // `Context` is exactly what a move changes. Stated in executable form so that nobody has to
    // take the file header's word for it.
    assertNotEquals(Planner.contentHash(beforeMove), Planner.contentHash(afterMove))
  }

  // ============================================================ THE ROLES TABLE ====

  test("every note type this tool declares has a role for every field it declares") {
    // `FieldRole.rolesFor` throws when the two disagree, so ASKING is the assertion. A field
    // added to a note type without a role would be compared as though it were one thing while
    // being another — silently, and in the direction that invents pairings.
    Marker.NoteTypes.All.foreach { noteType =>
      assert(FieldRole.rolesFor(noteType).isDefined, s"'$noteType' carries no roles at all")
    }
  }

  test("a note type this tool does not declare has no roles, rather than a default set") {
    assertEquals(FieldRole.rolesFor("Basic"), None)
    assertEquals(FieldRole.rolesFor("Cloze"), None)
  }

  test("every declared note type has a Substance field, or the floor would hold vacuously") {
    Marker.NoteTypes.All.foreach { noteType =>
      val roles = FieldRole.rolesFor(noteType).getOrElse(fail(s"no roles for '$noteType'"))
      assert(
        roles.values.exists(_ == FieldRole.Substance),
        s"'$noteType' has no Substance field, so every card of it would agree with every other",
      )
    }
  }

  test("the name fields of a note type are the LAST segments of its key path, with no gaps") {
    // What `Agreement` rests on: the place axis is "everything above the name segments", which
    // is only a vector one can take when the name segments are contiguous at the end.
    Marker.NoteTypes.All.foreach { noteType =>
      val roles = FieldRole.rolesFor(noteType).getOrElse(fail(s"no roles for '$noteType'"))
      val indices = roles.values.collect { case FieldRole.Anchor(i) => i }.toVector.sorted
      assertEquals(indices, indices.indices.toVector, s"'$noteType' has a gap in its name fields")
    }
  }

  test("the concept-descriptor's two name fields are the last two segments, descriptor innermost") {
    val roles = FieldRole
      .rolesFor(Marker.NoteTypes.ConceptDescriptor)
      .getOrElse(fail("no roles for the concept-descriptor"))
    assertEquals(roles("Descriptor"), FieldRole.Anchor(0))
    assertEquals(roles("Concept"), FieldRole.Anchor(1))
  }

  // ================================ THE PROJECTION, AGAINST THE REAL EXTRACTOR ====

  /** WHAT THE WHOLE ACCOUNTING RULE RESTS ON, ESTABLISHED BY RUNNING THE EXTRACTOR RATHER THAN BY
    * READING IT.
    *
    * `FieldRole.Anchor(n)` declares that a name field renders the key-path segment `n` places from
    * the end, and every "is this name change accounted for by the key" decision is read off that
    * table. If the table is wrong about which segment a field renders, the rule is not merely
    * imprecise — it is answering about a different segment, and it would account for a name change
    * the key never made.
    *
    * SO THESE TWO TESTS BUILD REAL MARKDOWN AND WALK IT. Everything else in this file hands
    * hand-built `CardSpec`s to the survey, which is the right shape for testing the survey and
    * exactly the wrong shape for testing a claim ABOUT the extractor: a fixture written to match
    * the table would agree with it whatever either one said.
    *
    * IT COMPARES CANONICAL FORMS, not the field's bytes. A card's face is the DISPLAY reading of a
    * heading — HTML-escaped, markup rendered — while a key segment is the IDENTITY reading, plain
    * and canonicalised (`extract/Extractor.scala` carries both, and `model/CardKey.scala` says why
    * they must not be the same string). The headings here are deliberately plain, so the two
    * readings coincide and the assertion is about POSITION rather than about escaping.
    */
  def indexOf(files: (String, String)*): obsidiananki.extract.VaultIndex =
    obsidiananki.extract.VaultWalker.scan(
      files.toVector.map(obsidiananki.extract.VaultFile.apply.tupled),
      DeckPath(NonEmptyVector.one("Obsidian")),
      obsidiananki.extract.DeckShape.FoldersOnly,
    )

  def markdownNote(id: String, body: String): String = s"---\nid: $id\n---\n\n$body"

  def segmentFromEnd(k: CardKey, fromEnd: Int): String =
    val segments = MoveEvidence.segmentsOf(k.path)
    assert(fromEnd < segments.length, s"'${k.path.render}' has no segment $fromEnd from its end")
    segments(segments.length - 1 - fromEnd)

  test("a two-field card's Front really is the LAST segment of the key the extractor derived") {
    val index = indexOf(
      "A.md" -> markdownNote("n1", "# Top\n\n## Middle\n\n### Scale #flashcard/1way\n\n10^9 users\n")
    )
    val spec = index.scan.specs match
      case Vector(only) => only
      case several      => fail(s"the fixture should produce exactly one card: ${several.map(_.key)}")
    assertEquals(spec.spec.noteTypeName, Marker.NoteTypes.Basic)
    assertEquals(
      TagCodec.canonical(spec.spec.fields.toMap.apply(Marker.BasicFields.Front)),
      segmentFromEnd(spec.key, 0),
    )
  }

  test("a concept-descriptor card's Descriptor and Concept really are the last TWO segments") {
    // The claim `FieldRole.Anchor(1)` makes about `Concept`: it is the NEAREST ANCESTOR heading,
    // which is the segment immediately above the marked one in the key the extractor derived.
    val index = indexOf(
      "A.md" -> markdownNote("n1", "# Top\n\n## Kafka\n\n### Delivery #flashcard/cdd/2way\n\nAt least once.\n")
    )
    val spec = index.scan.specs match
      case Vector(only) => only
      case several      => fail(s"the fixture should produce exactly one card: ${several.map(_.key)}")
    assertEquals(spec.spec.noteTypeName, Marker.NoteTypes.ConceptDescriptor)

    val roles = FieldRole
      .rolesFor(spec.spec.noteTypeName)
      .getOrElse(fail("no roles for the concept-descriptor"))
    val fields = spec.spec.fields.toMap

    roles.foreach {
      case (field, FieldRole.Anchor(fromEnd)) =>
        assertEquals(
          TagCodec.canonical(fields(field)),
          segmentFromEnd(spec.key, fromEnd),
          s"'$field' does not render the segment $fromEnd from the end of '${spec.key.path.render}'",
        )
      case _ => ()
    }
  }

  // ==================================================== CORROBORATION: THE CASES ====

  def surveyOf(
      stranded: Vector[ObservedCard],
      unclaimed: Vector[SourcedSpec],
  ): Vector[MoveFinding] = MoveEvidence.survey(stranded, unclaimed)

  def onlyFinding(
      stranded: Vector[ObservedCard],
      unclaimed: Vector[SourcedSpec],
  ): MoveFinding =
    val findings = surveyOf(stranded, unclaimed)
    assertEquals(findings.size, 1, s"expected exactly one finding: $findings")
    findings.head

  def corroboration(
      stranded: Vector[ObservedCard],
      unclaimed: Vector[SourcedSpec],
  ): MoveFinding.Corroborated =
    onlyFinding(stranded, unclaimed) match
      case c: MoveFinding.Corroborated => c
      case other                       => fail(s"expected a corroborated finding, got: ${other.describe}")

  test("a moved heading is corroborated against the key the vault now produces") {
    val found = corroboration(Vector(observed(beforeMove, 1)), Vector(sourced(afterMove)))
    assertEquals(found.stranded, strandedKey)
    assertEquals(found.candidate, movedKey)
    assertEquals(found.noteId, AnkiNoteId(1))
  }

  test("a moved heading grades as the same name somewhere else, and names the breadcrumb that changed") {
    val found = corroboration(Vector(observed(beforeMove, 1)), Vector(sourced(afterMove)))
    assertEquals(found.agreement, Agreement.NameAndSubstance)
    assertEquals(
      found.divergences,
      Vector(
        Divergence(
          Marker.ContextField,
          FieldRole.Bearing,
          "System design",
          "System design › System design interview framework",
        )
      ),
    )
  }

  test("a frontmatter id that changed while the heading tree did not grades as the whole path agreeing") {
    val was = twoField(key("old-id", "a", "coupling"), "Coupling", "Two things move together.", "A")
    val now = twoField(key("new-id", "a", "coupling"), "Coupling", "Two things move together.", "A")
    val found = corroboration(Vector(observed(was, 1)), Vector(sourced(now)))
    assertEquals(found.agreement, Agreement.Total)
    assertEquals(found.divergences, Vector.empty)
  }

  test("a heading reworded in place grades as a different name in the same place") {
    val was = twoField(key("n1", "a", "coupling"), "Coupling", "Two things move together.", "A")
    val now = twoField(key("n1", "a", "temporal coupling"), "Temporal coupling", "Two things move together.", "A")
    val found = corroboration(Vector(observed(was, 1)), Vector(sourced(now)))
    assertEquals(found.agreement, Agreement.PlaceAndSubstance)
    assertEquals(found.divergences.map(_.field), Vector(Marker.BasicFields.Front))
  }

  test("a concept-descriptor card moved under a different parent moves a name segment and a place segment") {
    // Its `Concept` IS its parent heading, so this one edit moves both axes at once — the cost
    // `FieldRole.Anchor` records, arriving as a grade rather than as a refusal.
    val was = threeField(key("n1", "top", "kafka", "delivery"), "Kafka", "Delivery", "At least once.", "Top")
    val now = threeField(key("n1", "other", "nats", "delivery"), "NATS", "Delivery", "At least once.", "Other")
    val found = corroboration(Vector(observed(was, 1)), Vector(sourced(now)))
    assertEquals(found.agreement, Agreement.SubstanceAlone)
    assertEquals(found.divergences.map(_.field).toSet, Set("Concept", Marker.ContextField))
  }

  test("a heading carried into another note keeps its whole path, so the survey searches the whole vault") {
    // The second of Marc's two examples, and the one `EVOLVABILITY.md` §4A's confinement
    // proposal would make undetectable by construction.
    val was = twoField(key("source-note", "essential numbers", "scale"), "Scale", "10^9 users", "Source")
    val now = twoField(key("target-note", "essential numbers", "scale"), "Scale", "10^9 users", "Source")
    val found = corroboration(Vector(observed(was, 1)), Vector(sourced(now)))
    assertEquals(found.candidate.noteId.value, "target-note")
    assertEquals(found.agreement, Agreement.Total)
  }

  // ========================================================= THE WAYS IT REFUSES ====

  test("several candidates agreeing equally is ambiguous, and names them all without picking") {
    val was  = twoField(key("n1", "a", "one"), "One", "Same body.", "A")
    val alt1 = twoField(key("n1", "b", "one"), "One", "Same body.", "B")
    val alt2 = twoField(key("n1", "c", "one"), "One", "Same body.", "C")
    onlyFinding(Vector(observed(was, 1)), Vector(sourced(alt1), sourced(alt2))) match
      case MoveFinding.Ambiguous(_, _, candidates) =>
        assertEquals(candidates.toVector.toSet, Set(alt1.key, alt2.key))
      case other => fail(s"expected ambiguity, got: ${other.describe}")
  }

  test("two stranded notes agreeing with one candidate contest each other, and neither is corroborated") {
    val one  = twoField(key("n1", "a", "one"), "One", "Same body.", "A")
    val two  = twoField(key("n1", "b", "two"), "Two", "Same body.", "B")
    val only = twoField(key("n1", "c", "three"), "Three", "Same body.", "C")

    val findings = surveyOf(Vector(observed(one, 1), observed(two, 2)), Vector(sourced(only)))
    assertEquals(findings.size, 2)
    assert(
      findings.forall {
        case _: MoveFinding.Contested => true
        case _                        => false
      },
      s"two notes both claiming one card should establish nothing: ${findings.map(_.describe)}",
    )
  }

  test("a body edited in the same commit as the move is unexplained, not paired") {
    val edited = twoField(movedKey, "Scale", "10^9 users, give or take", afterMove match {
      case CardSpec.TwoField(_, _, _, _, c) => c
      case _                                => fail("the fixture is a two-field card")
    })
    onlyFinding(Vector(observed(beforeMove, 1)), Vector(sourced(edited))) match
      case _: MoveFinding.Unexplained => ()
      case other                      => fail(s"an edited body is not evidence of a move: ${other.describe}")
  }

  test("a marker-derived field that differs refuses the pairing outright") {
    // `SameShape` is empty on a heading's two-field card and "1" on a table's row card, so this
    // is what stops a heading card being paired with a table card that happens to read alike.
    val heading = twoField(key("n1", "a", "one"), "One", "Same body.", "A")
    val row     = CardSpec.TableRow(key("n1", "b", "one"), "One", "Same body.", "B")
    assertEquals(heading.noteTypeName, row.noteTypeName, "the fixture must share a note type to be a test")
    onlyFinding(Vector(observed(heading, 1)), Vector(sourced(row))) match
      case _: MoveFinding.Unexplained => ()
      case other => fail(s"a differing marker field is not a move: ${other.describe}")
  }

  test("two note types are never paired, however alike their content reads") {
    val basic = twoField(key("n1", "a", "one"), "One", "Same body.", "A")
    val cdd   = threeField(key("n1", "b", "one", "aspect"), "One", "Aspect", "Same body.", "B")
    onlyFinding(Vector(observed(basic, 1)), Vector(sourced(cdd))) match
      case _: MoveFinding.Unexplained => ()
      case other => fail(s"a cross-note-type pairing is no evidence at all: ${other.describe}")
  }

  test("two KINDS of card path are never paired, even on one note type with identical content") {
    // A whole-note cloze card and a heading cloze card carry the same fields and hang off
    // different kinds of node. Without the kind check they would pair: a cloze card shows no
    // name field, so the whole comparison would come down to substance, which agrees.
    val wholeNote = cloze(noteKey("n1"), "A {{c1::quorum}} is a majority.", "N1")
    val heading   = cloze(key("n2", "quorums"), "A {{c1::quorum}} is a majority.", "N1")
    onlyFinding(Vector(observed(wholeNote, 1)), Vector(sourced(heading))) match
      case _: MoveFinding.Unexplained => ()
      case other => fail(s"two kinds of anchor are not comparable: ${other.describe}")
  }

  test("a note on a note type this tool does not declare says so, rather than 'nothing matched'") {
    val spec   = twoField(strandedKey, "Scale", "10^9 users", "System design")
    val legacy = observed(spec, 1).pipe(c => c.copy(note = c.note.copy(noteType = "Basic")))
    onlyFinding(Vector(legacy), Vector(sourced(afterMove))) match
      case MoveFinding.Incomparable(_, _, reason) =>
        assert(reason.contains("Basic"), s"the reason must name the note type: $reason")
      case other => fail(s"a stock note type cannot be compared: ${other.describe}")
  }

  test("a note whose fields are not its note type's says so, rather than comparing what it has") {
    // A subset comparison would agree MORE readily than a complete one — the evidence would be
    // strongest exactly where the collection is most damaged.
    val damaged = observed(beforeMove, 1).pipe(c =>
      c.copy(note = c.note.copy(fields = c.note.fields.filterNot(_._1 == Marker.ContextField)))
    )
    onlyFinding(Vector(damaged), Vector(sourced(afterMove))) match
      case MoveFinding.Incomparable(_, _, reason) =>
        assert(reason.contains(Marker.ContextField), s"the reason must name the field: $reason")
      case other => fail(s"a damaged note cannot be compared: ${other.describe}")
  }

  extension [A](a: A) private def pipe[B](f: A => B): B = f(a)

  // =========================== THE NAME THE KEY DOES NOT ACCOUNT FOR ====

  test("a name that changed while its key segment did not is reported and NOT applied") {
    // The author bolded the heading in the same edit that moved it. `HeadingSegment` strips
    // markup, so the key segment is unchanged — which means the key does not explain why the
    // card's face is different, and this design applies only what a move explains.
    val heldInAnki = observedWith(beforeMove, 1, Marker.BasicFields.Front -> "<b>Scale</b>")
    onlyFinding(Vector(heldInAnki), Vector(sourced(afterMove))) match
      case MoveFinding.Unaccounted(_, _, candidate, _, unexplained) =>
        assertEquals(candidate, movedKey)
        assertEquals(unexplained.toVector.map(_.field), Vector(Marker.BasicFields.Front))
      case other => fail(s"an unexplained name change must not reassign: ${other.describe}")
  }

  test("a concept taken from the FILE NAME has no key segment to account for it, and is reported") {
    // `Extractor.buildSpecs` binds a concept-descriptor card's concept to the nearest ancestor
    // heading, or to the FILE NAME when the marked heading has none. A file rename therefore
    // changes the field with nothing in the key to show for it.
    val was = threeField(key("n1", "aspect"), "Old File Name", "Aspect", "The value.", "")
    val now = threeField(key("n2", "aspect"), "New File Name", "Aspect", "The value.", "")
    onlyFinding(Vector(observed(was, 1)), Vector(sourced(now))) match
      case MoveFinding.Unaccounted(_, _, _, _, unexplained) =>
        assertEquals(unexplained.toVector.map(_.field), Vector("Concept"))
      case other =>
        fail(s"a concept with no segment behind it cannot be accounted for: ${other.describe}")
  }

  test("every stranded note produces exactly one finding, whatever the evidence came to") {
    // CONSERVATION. A note that produced no finding is a note the reader never hears about,
    // which is the silent-nothing failure this project is built to prevent.
    val notes = Vector(
      observed(beforeMove, 1),
      observed(twoField(key("n9", "gone"), "Gone", "Deleted outright.", "N9"), 2),
      observed(twoField(key("n8", "a", "one"), "One", "Shared body.", "A"), 3),
      observed(twoField(key("n8", "b", "two"), "Two", "Shared body.", "B"), 4),
    )
    val specs = Vector(sourced(afterMove), sourced(twoField(key("n8", "c", "three"), "Three", "Shared body.", "C")))
    val findings = surveyOf(notes, specs)
    assertEquals(findings.map(_.strandedNote).toSet, notes.map(_.note.id).toSet)
    assertEquals(findings.size, notes.size)
  }

  test("the survey is stable under the order its inputs arrive in") {
    val notes = Vector(
      observed(beforeMove, 1),
      observed(twoField(key("n9", "gone"), "Gone", "Deleted outright.", "N9"), 2),
    )
    val specs = Vector(sourced(afterMove), sourced(twoField(key("n0", "new"), "New", "Brand new.", "N0")))
    assertEquals(
      surveyOf(notes, specs).map(_.describe),
      surveyOf(notes.reverse, specs.reverse).map(_.describe),
    )
  }

  // ================================================================ THE ACTION ====

  test("a reassignment writes the vault's whole field set, the new identity included") {
    val found  = corroboration(Vector(observed(beforeMove, 1)), Vector(sourced(afterMove)))
    val action = found.reassignment(sourced(afterMove), Vector.empty, Vector.empty, None)
    assertEquals(action.fields, afterMove.fields)
    val written = action.fields.toMap
    assertEquals(
      written(Marker.IdentityField),
      TagCodec.encode(movedKey).value,
      "the note must end up claiming the key the vault produces",
    )
    assertEquals(action.newSha, Planner.contentHash(afterMove))
    assertEquals(action.corroboration.noteId, AnkiNoteId(1))
  }

  test("a reassignment refuses a spec that is not the card the evidence named") {
    val found = corroboration(Vector(observed(beforeMove, 1)), Vector(sourced(afterMove)))
    val other = sourced(twoField(key("n0", "somewhere", "else"), "Else", "Other body.", "N0"))
    intercept[RuntimeException](found.reassignment(other, Vector.empty, Vector.empty, None))
  }

  test("the action a reassignment carries is about the key it moves TO") {
    val found  = corroboration(Vector(observed(beforeMove, 1)), Vector(sourced(afterMove)))
    val action = found.reassignment(sourced(afterMove), Vector.empty, Vector.empty, None)
    assertEquals((action: SyncAction).cardKey, movedKey)
  }

  test("a reassignment is attempted under every retype policy") {
    val found  = corroboration(Vector(observed(beforeMove, 1)), Vector(sourced(afterMove)))
    val action: SyncAction = found.reassignment(sourced(afterMove), Vector.empty, Vector.empty, None)
    assertEquals(action.dispositionUnder(RetypePolicy.Defer), Disposition.Attempt)
    assertEquals(action.dispositionUnder(RetypePolicy.Apply), Disposition.Attempt)
  }

  // ================================================= THE PLANNER, END TO END ====

  def scanOf(specs: SourcedSpec*): VaultScan = VaultScan.from(specs.toVector, Vector.empty)

  def planOf(scan: VaultScan, anki: InMemoryAnki): Plan =
    val state = Observer.observe(anki).fold(e => fail(s"observe failed: $e"), identity)
    Planner
      .plan(scan, state, _ => deck, Planner.newNoteFor)
      .fold(errs => fail(s"unexpected plan errors: ${errs.map(_.describe)}"), identity)

  def runPlan(p: Plan, anki: InMemoryAnki): Unit =
    val report = Executor
      .run(p, anki, RetypePolicy.Apply, Set.empty)
      .fold(e => fail(s"execution aborted entirely: $e"), identity)
    assert(report.failures.isEmpty, s"unexpected execution failures: ${report.failures}")

  /** Sync the BEFORE vault into an empty collection, give the resulting card a review history,
    * and hand back the collection with the note id and card ids that must survive the move.
    */
  def collectionHolding(spec: CardSpec, reviews: Int): (InMemoryAnki, AnkiNoteId, Vector[AnkiCardId]) =
    val anki = InMemoryAnki()
    runPlan(planOf(scanOf(sourced(spec)), anki), anki)
    val noteId = anki.ownedNotes.fold(e => fail(s"$e"), identity) match
      case Vector(only) => only
      case several      => fail(s"the fixture should have created exactly one note: $several")
    val cards = anki.cardsOf(Vector(noteId)).fold(e => fail(s"$e"), identity)
    cards.foreach(anki.recordReviews(_, reviews))
    (anki, noteId, cards)

  test("a moved heading plans a reassignment instead of a create, and no orphan flag at all") {
    val (anki, noteId, _) = collectionHolding(beforeMove, reviews = 32)

    val plan = planOf(scanOf(sourced(afterMove)), anki)

    assert(
      plan.actions.exists {
        case SyncAction.Reassign(c, _, _, _, _, _) =>
          c.stranded == strandedKey && c.candidate == movedKey && c.noteId == noteId
        case _ => false
      },
      s"the move was not planned as a reassignment: ${plan.actions}",
    )
    assert(
      !plan.actions.exists { case _: SyncAction.Create => true; case _ => false },
      s"a second note would have been created for a card that already exists: ${plan.actions}",
    )
    assert(
      !plan.actions.exists { case _: SyncAction.Flag => true; case _ => false },
      s"the card that was just reattached would have been suspended: ${plan.actions}",
    )
  }

  test("applying it keeps the Anki note, its cards and every review they carry") {
    // THE WHOLE POINT. A reassignment rewrites which key an existing note claims; it must never
    // delete and recreate, because the review log is the one thing this tool cannot recompute.
    val (anki, noteId, cards) = collectionHolding(beforeMove, reviews = 32)

    runPlan(planOf(scanOf(sourced(afterMove)), anki), anki)

    assertEquals(anki.ownedNotes.fold(e => fail(s"$e"), identity), Vector(noteId))
    assertEquals(anki.cardsOf(Vector(noteId)).fold(e => fail(s"$e"), identity), cards)
    assertEquals(
      anki.standingOf(cards).fold(e => fail(s"$e"), identity).map(_.reviews),
      cards.map(_ => 32),
    )
    assert(cards.forall(!anki.isSuspended(_)), "the reassigned card was suspended")
  }

  test("after a reassignment the note claims the key the vault now produces") {
    val (anki, _, _) = collectionHolding(beforeMove, reviews = 3)
    runPlan(planOf(scanOf(sourced(afterMove)), anki), anki)

    val state = Observer.observe(anki).fold(e => fail(s"observe failed: $e"), identity)
    assertEquals(state.notes.map(_.key), Vector(movedKey))
    val held = state.notes.head.note.fields.toMap
    assertEquals(
      held(Marker.ContextField),
      "System design › System design interview framework",
      "the breadcrumb still named the place the heading left",
    )
  }

  test("a run that reassigns converges: the next run has nothing to do") {
    val (anki, _, _) = collectionHolding(beforeMove, reviews = 3)
    runPlan(planOf(scanOf(sourced(afterMove)), anki), anki)
    assertEquals(planOf(scanOf(sourced(afterMove)), anki).actions, Vector.empty)
  }

  test("a note ALREADY parked as an orphan is unflagged, unsuspended and reassigned, in that order") {
    // The retroactive half — `EVOLVABILITY.md` §4A's "works retroactively, which no tag-based
    // scheme can". The note was flagged and suspended by an earlier run, and its heading turns
    // out to be alive somewhere else.
    val (anki, noteId, cards) = collectionHolding(beforeMove, reviews = 7)

    // An earlier run over a vault that had lost the heading entirely: flag and suspend.
    runPlan(planOf(scanOf(), anki), anki)
    assert(cards.forall(anki.isSuspended), "the fixture did not actually park the note")

    val plan = planOf(scanOf(sourced(afterMove)), anki)
    val kinds = plan.actions.map {
      case _: SyncAction.Unflag   => "unflag"
      case _: SyncAction.Reassign => "reassign"
      case other                  => s"unexpected: $other"
    }
    assertEquals(kinds, Vector("unflag", "reassign"))

    runPlan(plan, anki)
    assert(cards.forall(!anki.isSuspended(_)), "the reassigned card is still suspended")
    assertEquals(
      anki.standingOf(cards).fold(e => fail(s"$e"), identity).map(_.reviews),
      cards.map(_ => 7),
    )
    assertEquals(planOf(scanOf(sourced(afterMove)), anki).actions, Vector.empty)
  }

  test("a LEGACY note keeps its new identity: the backfill does not write the old one back over it") {
    // The note this tool wrote before 2026-08-28, when the identity lived in a `src::` tag: it is
    // on a note type this tool owns, its `Identity` field is EMPTY, and the tag says the old key.
    // Every other exclusion in the planner is BY KEY, and this is the one note where the two
    // sides disagree about what its key is — the observation says the old one, the action about
    // it is filed under the new one. So the identity backfill would fire on it, and it runs
    // AFTER the reassignment: it would write the OLD identity into the field the reassignment
    // just set, in the same run, silently undoing the whole thing.
    val anki = InMemoryAnki()
    val legacyId = anki
      .addNote(
        NewNote(
          noteType = beforeMove.noteTypeName,
          deck = deck,
          fields = beforeMove.fields.map((n, v) => if n == Marker.IdentityField then n -> "" else n -> v),
          tags = NonEmptyVector.of(
            TagCodec.encode(strandedKey),
            OwnedTag.sha(Planner.contentHash(beforeMove)),
          ),
        )
      )
      .fold(e => fail(s"$e"), identity)

    val plan = planOf(scanOf(sourced(afterMove)), anki)
    assert(
      !plan.actions.exists { case _: SyncAction.CarryIdentity => true; case _ => false },
      s"the identity backfill would have overwritten the reassignment: ${plan.actions}",
    )
    runPlan(plan, anki)

    val state = Observer.observe(anki).fold(e => fail(s"observe failed: $e"), identity)
    assertEquals(state.notes.map(_.key), Vector(movedKey))
    assertEquals(state.notes.map(_.note.id), Vector(legacyId), "a note was created or destroyed")
    assert(
      !state.notes.head.note.tags.exists(_.startsWith(s"${OwnedTag.SrcPrefix}::")),
      s"the stale legacy identity survived: ${state.notes.head.note.tags}",
    )
    assertEquals(planOf(scanOf(sourced(afterMove)), anki).actions, Vector.empty)
  }

  test("every corroborated finding on a plan has the reassignment that discharges it") {
    // THE INVARIANT `Report` READS. It prints a corroborated finding under "reassigned, keeping
    // its review history", which is a lie the moment a corroborated finding can reach a plan
    // without its action. Pinned here rather than defended in the report, because the planner is
    // where the two are decided together.
    val (anki, _, _) = collectionHolding(beforeMove, reviews = 3)
    val plan = planOf(scanOf(sourced(afterMove)), anki)

    val corroborated = plan.moveEvidence.collect { case c: MoveFinding.Corroborated => c }
    val discharged   = plan.actions.collect { case SyncAction.Reassign(c, _, _, _, _, _) => c }
    assert(corroborated.nonEmpty, "the fixture produced no corroboration to check")
    assertEquals(discharged, corroborated)
  }

  test("ambiguous evidence changes nothing: the create and the flag happen exactly as before") {
    val was  = twoField(key("n1", "a", "one"), "One", "Same body.", "A")
    val alt1 = twoField(key("n1", "b", "one"), "One", "Same body.", "B")
    val alt2 = twoField(key("n1", "c", "one"), "One", "Same body.", "C")

    val (anki, noteId, _) = collectionHolding(was, reviews = 5)
    val plan = planOf(scanOf(sourced(alt1), sourced(alt2)), anki)

    assertEquals(
      plan.actions.collect { case c: SyncAction.Create => c.key }.toSet,
      Set(alt1.key, alt2.key),
    )
    assertEquals(plan.actions.collect { case f: SyncAction.Flag => f.noteId }, Vector(noteId))
    assert(
      !plan.actions.exists { case _: SyncAction.Reassign => true; case _ => false },
      s"ambiguous evidence must never be applied: ${plan.actions}",
    )
  }

  test("the plan carries one finding for every note the vault stopped producing, applied or not") {
    val was  = twoField(key("n1", "a", "one"), "One", "Same body.", "A")
    val alt1 = twoField(key("n1", "b", "one"), "One", "Same body.", "B")
    val alt2 = twoField(key("n1", "c", "one"), "One", "Same body.", "C")

    val (anki, noteId, _) = collectionHolding(was, reviews = 5)
    val plan = planOf(scanOf(sourced(alt1), sourced(alt2)), anki)

    assertEquals(plan.moveEvidence.map(_.strandedNote), Vector(noteId))
    assert(
      plan.moveEvidence.forall {
        case _: MoveFinding.Ambiguous => true
        case _                        => false
      },
      s"the evidence should say why nothing was applied: ${plan.moveEvidence.map(_.describe)}",
    )
  }

  test("a partial scan reassigns nothing it could only have inferred from an unread vault") {
    // Absence from a vault that was not read in full proves nothing, which is the rule orphan
    // inference already obeys. A note not yet parked is therefore not even surveyed.
    val (anki, _, _) = collectionHolding(beforeMove, reviews = 3)
    val partial = VaultScan.from(
      Vector(sourced(afterMove)),
      Vector(BuildFailure.FileUnreadable("Other.md", "could not be read")),
    )
    val plan = planOf(partial, anki)
    assert(
      !plan.actions.exists { case _: SyncAction.Reassign => true; case _ => false },
      s"a partial scan must not decide that a heading moved: ${plan.actions}",
    )
    assertEquals(plan.moveEvidence, Vector.empty)
  }

  test("the run says what it did and what changed, because a silent history move is the failure") {
    val (anki, _, _) = collectionHolding(beforeMove, reviews = 3)
    val plan  = planOf(scanOf(sourced(afterMove)), anki)
    val lines = obsidiananki.cli.Report.plan(plan, RetypePolicy.Apply).mkString("\n")

    assert(lines.contains("review history"), s"the report did not say what was preserved:\n$lines")
    assert(lines.contains(strandedKey.path.render), s"the report did not name the old card:\n$lines")
    assert(lines.contains(movedKey.path.render), s"the report did not name the new card:\n$lines")
    assert(
      lines.contains("System design › System design interview framework"),
      s"the report did not say what changed on the card:\n$lines",
    )
  }
