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

  /** A card anchored at a frontmatter property — a relation card. Its path is ONE segment however
    * many name fields its note type declares, which is what the subject gate has to survive.
    */
  def propertyKey(id: String, name: String): CardKey =
    CardKey(
      NoteId.fromFrontmatter(id).toOption.get,
      CardPath.Property(PropertyName.fromFrontmatter(name).toOption.get),
    )

  def body(s: String): Body = Body.fromExtracted(s).getOrElse(fail("empty test body"))

  val deck: DeckPath = DeckPath(NonEmptyVector.of("Obsidian", "System-Design"))

  val where: SourceRef = SourceRef("System Design.md", 12, SourceKind.Heading)

  def sourced(spec: CardSpec, at: SourceRef): SourcedSpec =
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

  /** A TWO-FIELD CARD DECLARED `#flashcard/2way` — the body recalled from the heading AND the
    * heading from the body, which is the author declaring that this body identifies its heading.
    *
    * [[twoField]] is `TwoFieldDirections.Forward`, i.e. `#flashcard/1way`, which declares the
    * opposite. The two differ by NOTE TYPE rather than by a field — `Obsidian Basic` against
    * `Obsidian Basic (and reversed)` — and since the voucher rule of 2026-09-13 that difference
    * decides whether an orphan may be reattached on content alone.
    */
  def twoFieldBothWays(k: CardKey, front: String, back: String, context: String): CardSpec =
    CardSpec.TwoField(k, front, body(back), TwoFieldDirections.Both, context)

  /** THE SAME CARD SHAPE DECLARED `#flashcard/cdd/1way` — the description recalled and NOTHING
    * ELSE, so the author has declared that this description does NOT identify its concept.
    *
    * [[threeField]]'s `ThreeFieldDirections.Default` is `cdd/2way`, which declares the opposite.
    * The two helpers differ in that one value and it is the whole point: since the ruling of
    * 2026-09-13 the relabel gate reads the declaration, so a fixture's direction is no longer
    * incidental to what the survey concludes about it.
    */
  def threeFieldOneWay(
      k: CardKey,
      concept: String,
      descriptor: String,
      description: String,
      context: String,
  ): CardSpec =
    CardSpec.ThreeField(k, concept, descriptor, body(description), ThreeFieldDirections.ValueOnly, context, "")

  /** THE SAME CARD SHAPE DECLARED `#flashcard/cdd/3way` — every direction, so it claims the backward
    * reading exactly as `cdd/2way` does and differs from it only in the `ThreeWay` Setting field.
    *
    * That one field is load-bearing in two directions: it puts this card in the same
    * declared-identifying family for every rule that reads a declaration, AND it keeps a pairing with
    * a `2way` card off the comparison floor, which is what lets a test place a rival card that is not
    * also a candidate.
    */
  def threeFieldAllWays(
      k: CardKey,
      concept: String,
      descriptor: String,
      description: String,
      context: String,
  ): CardSpec =
    CardSpec.ThreeField(k, concept, descriptor, body(description), ThreeFieldDirections.All, context, "")

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
  def observed(spec: CardSpec, id: Long, tags: Vector[String]): ObservedCard =
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
    val base    = observed(spec, id, Vector.empty)
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

  /** THE SURVEY OVER A VAULT THAT HOLDS NOTHING BUT THESE UNCLAIMED CARDS.
    *
    * The census is derived from the same specs, which is the honest reading of a fixture built out
    * of specs alone: no heading exists here that no card hangs off, so the only nodes are the ones
    * ABOVE these cards. A test that needs a node no card proves — a concept heading that kept only
    * prose — must build its own census, which [[surveyWith]] is for.
    */
  def surveyOf(
      stranded: Vector[ObservedCard],
      unclaimed: Vector[SourcedSpec],
  ): Vector[MoveFinding] =
    surveyWith(stranded, unclaimed, HandBuiltCensus.of(VaultScan.from(unclaimed, Vector.empty)))

  /** THE SURVEY OVER A COLLECTION THAT HOLDS NOTHING BUT THE STRANDED NOTES — which is what every
    * test reaching this helper is about. The third witness of survival is the concepts a LIVE card
    * declares, and a collection with no live card declares none; [[surveyDeclaring]] is where a
    * test says otherwise. Stated by passing [[LiveDeclarations.none]] at every call rather than by a
    * default, so that no test is silently about a collection it did not describe.
    */
  def surveyWith(
      stranded: Vector[ObservedCard],
      unclaimed: Vector[SourcedSpec],
      census: NodeCensus,
  ): Vector[MoveFinding] =
    MoveEvidence.survey(stranded, unclaimed, census, LiveDeclarations.none)

  /** THE SURVEY OVER A COLLECTION THAT STILL HOLDS LIVE CARDS, with the vault's heading tree stated
    * outright — the two facts the third witness is a question about.
    *
    * `live` IS THE NOTES THIS RUN IS NOT ORPHANING, which is what `plan/Planner.scala` passes.
    * Nothing here checks that against `stranded`, because [[MoveEvidence.survey]] refuses an overlap
    * itself and one test below is about exactly that refusal.
    */
  def surveyDeclaring(
      stranded: Vector[ObservedCard],
      unclaimed: Vector[SourcedSpec],
      outlines: NodeCensus.Outlines,
      live: Vector[ObservedCard],
  ): Vector[MoveFinding] =
    MoveEvidence.survey(
      stranded,
      unclaimed,
      NodeCensus.of(VaultScan.from(unclaimed, Vector.empty), outlines),
      LiveDeclarations.of(live),
    )

  def noteIdOf(id: String): NoteId =
    NoteId.fromFrontmatter(id).getOrElse(fail(s"unusable test note id '$id'"))

  /** THE SURVEY OVER A VAULT WHOSE HEADING TREE IS STATED OUTRIGHT.
    *
    * This is how a test says "the concept the card left is still there" or "it is not", which is
    * the one fact no card can prove either way: a concept heading that kept only prose produces no
    * spec, so [[surveyOf]]'s census cannot see it in either direction.
    */
  def surveyOver(
      stranded: Vector[ObservedCard],
      unclaimed: Vector[SourcedSpec],
      outlines: NodeCensus.Outlines,
  ): Vector[MoveFinding] =
    surveyWith(stranded, unclaimed, NodeCensus.of(VaultScan.from(unclaimed, Vector.empty), outlines))

  /** THE SURVEY OVER A VAULT THIS RUN COULD NOT READ IN FULL — one file's frontmatter would not
    * parse, so no note's node tree may be relied on.
    *
    * `live` IS TAKEN EXPLICITLY rather than assumed empty, because the interesting question about a
    * blind census is what happens when something ELSE can still answer: a collection holding a live
    * card under the old subject establishes the survival the census could not look for.
    */
  def surveyBlind(
      stranded: Vector[ObservedCard],
      unclaimed: Vector[SourcedSpec],
      live: Vector[ObservedCard],
  ): Vector[MoveFinding] =
    MoveEvidence.survey(
      stranded,
      unclaimed,
      NodeCensus.of(
        VaultScan.from(
          unclaimed,
          Vector(BuildFailure.FileUnreadable("Elsewhere.md", "frontmatter: will not parse")),
        ),
        Map.empty,
      ),
      LiveDeclarations.of(live),
    )

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
    val found = corroboration(Vector(observed(beforeMove, 1, Vector.empty)), Vector(sourced(afterMove, where)))
    assertEquals(found.stranded, strandedKey)
    assertEquals(found.candidate, movedKey)
    assertEquals(found.noteId, AnkiNoteId(1))
  }

  test("a moved heading grades as the same name somewhere else, and names the breadcrumb that changed") {
    val found = corroboration(Vector(observed(beforeMove, 1, Vector.empty)), Vector(sourced(afterMove, where)))
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
    val found = corroboration(Vector(observed(was, 1, Vector.empty)), Vector(sourced(now, where)))
    assertEquals(found.agreement, Agreement.Total)
    assertEquals(found.divergences, Vector.empty)
  }

  test("a heading reworded in place grades as a different name in the same place") {
    val was = twoField(key("n1", "a", "coupling"), "Coupling", "Two things move together.", "A")
    val now = twoField(key("n1", "a", "temporal coupling"), "Temporal coupling", "Two things move together.", "A")
    val found = corroboration(Vector(observed(was, 1, Vector.empty)), Vector(sourced(now, where)))
    assertEquals(found.agreement, Agreement.PlaceAndSubstance)
    assertEquals(found.divergences.map(_.field), Vector(Marker.BasicFields.Front))
  }

  test("a cdd/1way card whose concept AND place both moved is a question, not a follow") {
    // ⚠️ THIS TEST HAS BEEN MOVED TWICE AND BOTH MOVES ARE THE POINT OF IT. It first asserted a
    // corroboration graded `SubstanceAlone`; Decision 2 of 2026-09-12 made it a question, on the
    // ground that "a name change combined with a move grades weaker". Then the entailment of
    // 2026-09-13 asked WHICH KINDS that sentence can be true of, and the answer narrowed it to
    // one: a `cdd/1way` card, whose author has declared that its description identifies nothing.
    // For such a card the location is the only thing that could vouch, so a moved location leaves
    // nothing — which is why THIS fixture is `threeFieldOneWay` where it used to be `threeField`.
    // The test below it is the same shape declared `cdd/2way`, and it follows.
    val was =
      threeFieldOneWay(key("n1", "top", "kafka", "delivery"), "Kafka", "Delivery", "At least once.", "Top")
    val now =
      threeFieldOneWay(key("n1", "other", "nats", "delivery"), "NATS", "Delivery", "At least once.", "Other")
    surveyOver(
      Vector(observed(was, 1, Vector.empty)),
      Vector(sourced(now, where)),
      Map(noteIdOf("n1") -> Vector(Vector("other"), Vector("other", "nats"))),
    ) match
      case Vector(MoveFinding.RelabelUnvouched(_, _, candidate, _, cause, divergences)) =>
        assertEquals(candidate, now.key)
        assertEquals(cause, RelabelDoubt.ClusterMoved)
        assertEquals(divergences.map(_.field).toSet, Set("Concept", Marker.ContextField))
      case other => fail(s"a 1way subject change that also moved must not be applied: $other")
  }

  test("the SAME shape declared cdd/2way follows, because place was never part of that claim") {
    // RESOLVED 2026-09-13 as ENTAILED by the standing rulings (`docs/design/IDENTITY-DECISION-SHEET.md`,
    // "a relabel's follow does not depend on place for declared-identifying kinds"). A `cdd/2way`
    // declares that the description identifies its concept — Marc: "a 2way card... same descriptor,
    // same description" — and place was never part of that claim. So with the description unchanged
    // and the old subject standing nowhere, this is the same concept respelled and rehomed, and the
    // history follows.
    //
    // BYTE-FOR-BYTE THE TEST ABOVE apart from one Setting field, which is what makes the pair worth
    // reading together: the fixtures differ only in what the author declared, and that is now the
    // whole of the difference in outcome.
    val was = threeField(key("n1", "top", "kafka", "delivery"), "Kafka", "Delivery", "At least once.", "Top")
    val now = threeField(key("n1", "other", "nats", "delivery"), "NATS", "Delivery", "At least once.", "Other")
    surveyOver(
      Vector(observed(was, 1, Vector.empty)),
      Vector(sourced(now, where)),
      Map(noteIdOf("n1") -> Vector(Vector("other"), Vector("other", "nats"))),
    ) match
      case Vector(c: MoveFinding.Corroborated) =>
        assertEquals(c.candidate, now.key)
        assertEquals(c.agreement, Agreement.SubstanceAlone)
      case other => fail(s"a 2way relabel needs no place evidence: $other")
  }

  test("a heading carried into another note keeps its whole path, so the survey searches the whole vault") {
    // The second of Marc's two examples, and the one `EVOLVABILITY.md` §4A's confinement
    // proposal would make undetectable by construction.
    val was = twoField(key("source-note", "essential numbers", "scale"), "Scale", "10^9 users", "Source")
    val now = twoField(key("target-note", "essential numbers", "scale"), "Scale", "10^9 users", "Source")
    val found = corroboration(Vector(observed(was, 1, Vector.empty)), Vector(sourced(now, where)))
    assertEquals(found.candidate.noteId.value, "target-note")
    assertEquals(found.agreement, Agreement.Total)
  }

  // ========================================================= THE WAYS IT REFUSES ====

  test("several candidates agreeing equally is ambiguous, and names them all without picking") {
    val was  = twoField(key("n1", "a", "one"), "One", "Same body.", "A")
    val alt1 = twoField(key("n1", "b", "one"), "One", "Same body.", "B")
    val alt2 = twoField(key("n1", "c", "one"), "One", "Same body.", "C")
    onlyFinding(Vector(observed(was, 1, Vector.empty)), Vector(sourced(alt1, where), sourced(alt2, where))) match
      case MoveFinding.Ambiguous(_, _, candidates) =>
        assertEquals(candidates.toVector.toSet, Set(alt1.key, alt2.key))
      case other => fail(s"expected ambiguity, got: ${other.describe}")
  }

  test("two stranded notes agreeing with one candidate contest each other, and neither is corroborated") {
    val one  = twoField(key("n1", "a", "one"), "One", "Same body.", "A")
    val two  = twoField(key("n1", "b", "two"), "Two", "Same body.", "B")
    val only = twoField(key("n1", "c", "three"), "Three", "Same body.", "C")

    val findings = surveyOf(Vector(observed(one, 1, Vector.empty), observed(two, 2, Vector.empty)), Vector(sourced(only, where)))
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
    onlyFinding(Vector(observed(beforeMove, 1, Vector.empty)), Vector(sourced(edited, where))) match
      case _: MoveFinding.Unexplained => ()
      case other                      => fail(s"an edited body is not evidence of a move: ${other.describe}")
  }

  test("a marker-derived field that differs refuses the pairing outright") {
    // `SameShape` is empty on a heading's two-field card and "1" on a table's row card, so this
    // is what stops a heading card being paired with a table card that happens to read alike.
    val heading = twoField(key("n1", "a", "one"), "One", "Same body.", "A")
    val row     = CardSpec.TableRow(key("n1", "b", "one"), "One", "Same body.", "B")
    assertEquals(heading.noteTypeName, row.noteTypeName, "the fixture must share a note type to be a test")
    onlyFinding(Vector(observed(heading, 1, Vector.empty)), Vector(sourced(row, where))) match
      case _: MoveFinding.Unexplained => ()
      case other => fail(s"a differing marker field is not a move: ${other.describe}")
  }

  test("two note types are never paired, however alike their content reads") {
    val basic = twoField(key("n1", "a", "one"), "One", "Same body.", "A")
    val cdd   = threeField(key("n1", "b", "one", "aspect"), "One", "Aspect", "Same body.", "B")
    onlyFinding(Vector(observed(basic, 1, Vector.empty)), Vector(sourced(cdd, where))) match
      case _: MoveFinding.Unexplained => ()
      case other => fail(s"a cross-note-type pairing is no evidence at all: ${other.describe}")
  }

  test("two KINDS of card path are never paired, even on one note type with identical content") {
    // A whole-note cloze card and a heading cloze card carry the same fields and hang off
    // different kinds of node. Without the kind check they would pair: a cloze card shows no
    // name field, so the whole comparison would come down to substance, which agrees.
    val wholeNote = cloze(noteKey("n1"), "A {{c1::quorum}} is a majority.", "N1")
    val heading   = cloze(key("n2", "quorums"), "A {{c1::quorum}} is a majority.", "N1")
    onlyFinding(Vector(observed(wholeNote, 1, Vector.empty)), Vector(sourced(heading, where))) match
      case _: MoveFinding.Unexplained => ()
      case other => fail(s"two kinds of anchor are not comparable: ${other.describe}")
  }

  test("a note on a note type this tool does not declare says so, rather than 'nothing matched'") {
    val spec   = twoField(strandedKey, "Scale", "10^9 users", "System design")
    val legacy = observed(spec, 1, Vector.empty).pipe(c => c.copy(note = c.note.copy(noteType = "Basic")))
    onlyFinding(Vector(legacy), Vector(sourced(afterMove, where))) match
      case MoveFinding.Incomparable(_, _, reason) =>
        assert(reason.contains("Basic"), s"the reason must name the note type: $reason")
      case other => fail(s"a stock note type cannot be compared: ${other.describe}")
  }

  test("a note whose fields are not its note type's says so, rather than comparing what it has") {
    // A subset comparison would agree MORE readily than a complete one — the evidence would be
    // strongest exactly where the collection is most damaged.
    val damaged = observed(beforeMove, 1, Vector.empty).pipe(c =>
      c.copy(note = c.note.copy(fields = c.note.fields.filterNot(_._1 == Marker.ContextField)))
    )
    onlyFinding(Vector(damaged), Vector(sourced(afterMove, where))) match
      case MoveFinding.Incomparable(_, _, reason) =>
        assert(reason.contains(Marker.ContextField), s"the reason must name the field: $reason")
      case other => fail(s"a damaged note cannot be compared: ${other.describe}")
  }

  extension [A](a: A) private def pipe[B](f: A => B): B = f(a)

  // =========================== THE NAME THE KEY DOES NOT ACCOUNT FOR ====

  test("a name whose MARKUP changed over an agreeing key segment follows the move, and says what changed") {
    // ⚠️ THIS TEST USED TO ASSERT `Unaccounted`, AND DECISION 4 OF 2026-09-12 OVERRULED IT. The
    // author bolded the heading in the same edit that moved it. In Marc's words: "flashcard
    // 2way... same front, same back... same card" — a card IS its front and its back, the words of
    // both are unchanged, and markup is rendering rather than identity.
    //
    // THE DIVERGENCE STILL TRAVELS, which is the other half of the ruling: the run says the face
    // changed, and the reassignment writes the vault's fields, so Anki ends up showing the new
    // markup.
    val heldInAnki = observedWith(beforeMove, 1, Marker.BasicFields.Front -> "<b>Scale</b>")
    onlyFinding(Vector(heldInAnki), Vector(sourced(afterMove, where))) match
      case c: MoveFinding.Corroborated =>
        assertEquals(c.candidate, movedKey)
        assertEquals(c.agreement, Agreement.NameAndSubstance)
        assertEquals(
          c.divergences.map(_.field).toSet,
          Set(Marker.BasicFields.Front, Marker.ContextField),
        )
      case other => fail(s"markup is not identity, so this must follow: ${other.describe}")
  }

  test("a concept taken from the FILE NAME has no key segment to account for it, and is reported") {
    // `Extractor.buildSpecs` binds a concept-descriptor card's concept to the nearest ancestor
    // heading, or to the FILE NAME when the marked heading has none. A file rename therefore
    // changes the field with nothing in the key to show for it.
    val was = threeField(key("n1", "aspect"), "Old File Name", "Aspect", "The value.", "")
    val now = threeField(key("n2", "aspect"), "New File Name", "Aspect", "The value.", "")
    onlyFinding(Vector(observed(was, 1, Vector.empty)), Vector(sourced(now, where))) match
      case MoveFinding.Unaccounted(_, _, _, _, unexplained) =>
        assertEquals(unexplained.toVector.map(_.field), Vector("Concept"))
      case other =>
        fail(s"a concept with no segment behind it cannot be accounted for: ${other.describe}")
  }

  // ============================ THE SUBJECT GATE: RELABEL, RE-PARENT, OR A QUESTION ====

  /** EVERY ROUTE THROUGH THE GATE THE RULINGS OF 2026-09-12 INSTALLED.
    *
    * The whole gate turns on one question — did the card's SUBJECT change, and if so, is the
    * subject it left still in the vault — so each test below fixes that answer explicitly rather
    * than letting a fixture imply it. The deck (`deck/WorldDeck.test.scala`) runs the same routes
    * end to end over real markdown; these say what the rule IS, including the two routes no
    * fixture exercises.
    *
    * THE FIXTURES ARE CONCEPT-DESCRIPTOR CARDS BECAUSE THAT IS THE ONLY SHAPE THE GATE CAN FIRE
    * FOR, and not by a check on the note type: the gate asks whether the name window MINUS ITS
    * LAST SEGMENT moved, and that window is one segment long for every note type but this one.
    * Two of the tests below are the guards on exactly that.
    */
  val definitionUnderKafka: CardSpec =
    threeField(key("n1", "top", "kafka", "definition"), "Kafka", "Definition", "A durable log.", "Top")

  /** The same descriptor and the same body, under a differently-named concept. */
  def definitionUnder(concept: String, noteIdText: String, place: String): CardSpec =
    threeField(
      key(noteIdText, place, concept, "definition"),
      concept,
      "Definition",
      "A durable log.",
      place.capitalize,
    )

  /** What a note's heading tree looks like once the descriptor sits under `concept`. */
  def treeWith(place: String, concept: String): Vector[Vector[String]] =
    Vector(Vector(place), Vector(place, concept), Vector(place, concept, "definition"))

  test("the DESCRIPTOR changing under an unchanged concept is corroborated, census or no census") {
    // THE GUARD THAT KEEPS THE GATE NARROW. The concept is the subject; the descriptor is the
    // facet's label. `# Kafka` is plainly still there, and that must not block anything — Decision
    // 3 of 2026-09-12 rules this a rewording and follows it.
    val now = threeField(key("n1", "top", "kafka", "contrast"), "Kafka", "Contrast", "A durable log.", "Top")
    surveyOver(
      Vector(observed(definitionUnderKafka, 1, Vector.empty)),
      Vector(sourced(now, where)),
      Map(noteIdOf("n1") -> treeWith("top", "kafka")),
    ) match
      case Vector(c: MoveFinding.Corroborated) =>
        assertEquals(c.agreement, Agreement.PlaceAndSubstance)
      case other => fail(s"a descriptor rewording under an unchanged concept must follow: $other")
  }

  test("a descriptor RE-PARENTED under a concept that goes on existing is a different card") {
    // Standing ruling R2, deck scenario S24: the parent concept is constitutive, so this is a new
    // card under NATS and a deleted one under Kafka. `# Kafka` survives — holding its other
    // descriptor, or merely prose — and that is the fact the census supplies.
    val now = definitionUnder("NATS", "n1", "top")
    surveyOver(
      Vector(observed(definitionUnderKafka, 1, Vector.empty)),
      Vector(sourced(now, where)),
      Map(noteIdOf("n1") -> (treeWith("top", "nats") :+ Vector("top", "kafka"))),
    ) match
      case Vector(MoveFinding.Reparented(stranded, _, candidate, _, survival, divergences)) =>
        assertEquals(stranded, definitionUnderKafka.key)
        assertEquals(candidate, now.key)
        // THE CENSUS IS THE WITNESS HERE, and the finding says so: `# Kafka` is still a node of
        // this note. The other witness — a card this same run paired onto Kafka — is the ruling of
        // 2026-09-13's, and the tests further down are where it is pinned.
        assertEquals(survival, SubjectSurvival.StillInTheNote(Vector("top", "kafka")))
        assertEquals(divergences.map(_.field), Vector("Concept"))
      case other => fail(s"a re-parent under a surviving concept must never follow: $other")
  }

  test("a concept RELABELLED in place, its old path surviving nowhere, is corroborated") {
    // Decision 2 of 2026-09-12: "Least Element" becomes "Bottom". Every descriptor and description
    // is unchanged and the cluster stayed where it was, so these are the same cards.
    val now = definitionUnder("RabbitMQ", "n1", "top")
    surveyOver(
      Vector(observed(definitionUnderKafka, 1, Vector.empty)),
      Vector(sourced(now, where)),
      Map(noteIdOf("n1") -> treeWith("top", "rabbitmq")),
    ) match
      case Vector(c: MoveFinding.Corroborated) =>
        assertEquals(c.agreement, Agreement.PlaceAndSubstance)
        assertEquals(c.divergences.map(_.field), Vector("Concept"))
      case other => fail(s"a relabel in place must follow: $other")
  }

  test("a 2way concept relabelled AND the cluster moved FOLLOWS — place is not part of its claim") {
    // ⚠️ THIS TEST ASSERTED A QUESTION UNTIL 2026-09-13, on Decision 2's "the path weighs both
    // ways". The entailment recorded that day asked which KINDS that sentence can be true of: a
    // `cdd/2way` declares that its description identifies its concept, and place was never part of
    // that claim, so with the description unchanged and the old subject standing nowhere there is
    // nothing left for the path to weigh. Marc: "here I have the impression that it is a
    // non-question". The `/1way` twin below is where the question survives.
    val now = definitionUnder("RabbitMQ", "n1", "elsewhere")
    surveyOver(
      Vector(observed(definitionUnderKafka, 1, Vector.empty)),
      Vector(sourced(now, where)),
      Map(noteIdOf("n1") -> treeWith("elsewhere", "rabbitmq")),
    ) match
      case Vector(c: MoveFinding.Corroborated) =>
        assertEquals(c.candidate, now.key)
      case other => fail(s"a 2way relabel that also moved must follow: $other")
  }

  test("a 2way concept relabelled into ANOTHER NOTE follows too, and that WAS the open question") {
    // ⚠️ THIS TEST CARRIED THE FLAG "AN INTERPRETATION AWAITING MARC'S CONFIRMATION", and this is
    // the confirmation — in the negative. The interpretation was that a card key is a note id AND a
    // path, so crossing into another note is a cluster that did not stay. Walked against the
    // standing rulings on 2026-09-13 it collapsed for the declared-identifying kinds: the note id is
    // part of WHERE the card is, and where it is was never what a `2way` declaration claimed. So a
    // relabel follows across notes or not, gated by the survival witnesses alone.
    val now = definitionUnder("RabbitMQ", "n2", "top")
    surveyOver(
      Vector(observed(definitionUnderKafka, 1, Vector.empty)),
      Vector(sourced(now, where)),
      Map(noteIdOf("n2") -> treeWith("top", "rabbitmq")),
    ) match
      case Vector(c: MoveFinding.Corroborated) =>
        assertEquals(c.candidate.noteId.value, "n2")
      case other => fail(s"a 2way relabel across notes must follow: $other")
  }

  test("a 1way concept relabelled into ANOTHER NOTE stays a question — nothing vouches for it") {
    // THE GUARD THAT KEEPS THE REFINEMENT PER-DECLARATION RATHER THAN A BLANKET WIDENING, and the
    // one test that keeps `RelabelDoubt.ClusterMoved` reachable at all: no deck scenario produces
    // it. A `cdd/1way` author has declared that this description identifies nothing, so the content
    // match is not evidence of sameness and the location is the only thing that could have been —
    // and it changed. Same fixture as the 2way test above, one Setting field apart.
    val was =
      threeFieldOneWay(key("n1", "top", "kafka", "definition"), "Kafka", "Definition", "A durable log.", "Top")
    val now =
      threeFieldOneWay(key("n2", "top", "rabbitmq", "definition"), "RabbitMQ", "Definition", "A durable log.", "Top")
    surveyOver(
      Vector(observed(was, 1, Vector.empty)),
      Vector(sourced(now, where)),
      Map(noteIdOf("n2") -> treeWith("top", "rabbitmq")),
    ) match
      case Vector(r: MoveFinding.RelabelUnvouched) => assertEquals(r.cause, RelabelDoubt.ClusterMoved)
      case other => fail(s"a 1way relabel across notes must stay a question: $other")
  }

  test("a STANDING old subject still parks a 2way relabel, wherever the cluster went") {
    // THE CONTRADICTION CASE THE REFINEMENT DOES NOT TOUCH, and the reason the ruling says "gated
    // only by the survival witnesses" rather than "ungated". Place stopped being evidence; the old
    // subject going on existing never was place evidence — it is the R2 question, and a descriptor
    // under a subject that still stands is a different card however far it travelled.
    val now = definitionUnder("NATS", "n2", "top")
    surveyOver(
      Vector(observed(definitionUnderKafka, 1, Vector.empty)),
      Vector(sourced(now, where)),
      Map(
        noteIdOf("n1") -> Vector(Vector("top"), Vector("top", "kafka")),
        noteIdOf("n2") -> treeWith("top", "nats"),
      ),
    ) match
      case Vector(r: MoveFinding.Reparented) =>
        assertEquals(r.survival, SubjectSurvival.StillInTheNote(Vector("top", "kafka")))
      case other => fail(s"a surviving old subject must still park a cross-note relabel: $other")
  }

  test("a census that could not be taken is never spent as 'the old subject is gone'") {
    // THE HONESTY HALF, and the one route whose absence would be invisible: with the census
    // silenced, this fixture is byte-for-byte the corroborated relabel above. A run that could not
    // look must not be mistaken for a run that looked and found nothing — the argument
    // `MoveFinding.Incomparable` makes one level down.
    val now = definitionUnder("RabbitMQ", "n1", "top")
    surveyBlind(Vector(observed(definitionUnderKafka, 1, Vector.empty)), Vector(sourced(now, where)), Vector.empty) match
      case Vector(MoveFinding.RelabelUnvouched(_, _, _, _, RelabelDoubt.CensusUnavailable(reason), _)) =>
        assert(reason.nonEmpty, "the report must be able to say WHY it declined")
      case other => fail(s"an unsurveyable census must not license a follow: $other")
  }

  test("a NAMESAKE HEADING elsewhere, with no card this run paired onto it, proves nothing") {
    // RULED 2026-09-13, and this test's reading was the one confirmed. The sheet says the old
    // concept must "survive nowhere"; read as vault-wide matching on canonical TEXT, an innocent
    // rename in one note would be blocked whenever any other note happened to hold a heading of the
    // same name — and `# Notes` exists everywhere. So the census is asked about the card's own note,
    // and the vault-wide half of the question is answered by EVIDENCE instead: a concept survives
    // when this same survey corroborated a card onto it, which a bare namesake heading has not.
    //
    // The two readings the deck could not distinguish are therefore both live, and each has its own
    // fixtures now: this test for the namesake that witnesses nothing, and the three below it for
    // the concept the run itself keeps a card under.
    val now = definitionUnder("RabbitMQ", "n1", "top")
    surveyOver(
      Vector(observed(definitionUnderKafka, 1, Vector.empty)),
      Vector(sourced(now, where)),
      Map(
        noteIdOf("n1") -> treeWith("top", "rabbitmq"),
        noteIdOf("n9") -> Vector(Vector("top"), Vector("top", "kafka")),
      ),
    ) match
      case Vector(_: MoveFinding.Corroborated) => ()
      case other =>
        fail(s"an unrelated note's heading of the same name must not block a rename: $other")
  }

  // ============ THE SECOND WITNESS OF SURVIVAL: THIS SURVEY'S OWN CORROBORATIONS ====

  /** RULED 2026-09-13 (`docs/design/IDENTITY-DECISION-SHEET.md`, "the survival check answers with
    * evidence, not spelling"): a concept counts as SURVIVING when THIS SAME SURVEY corroborated a
    * card onto it. The per-note census stays as the first witness — it is the only thing that can
    * see a concept which kept nothing but prose — and this is the second, which reaches the whole
    * vault without ever matching on spelling alone.
    *
    * WHAT THE THREE TESTS BELOW PIN, AND WHY THE RULE NEEDED A SECOND WITNESS AT ALL. The final
    * adversarial review constructed a run that asserted both halves of a contradiction at once:
    * `# Kafka` leaves for another note with `## Cost` under it, so the run moves Cost's history
    * onto `kafka / cost` THERE — while moving `## Definition`'s history on the grounds that Kafka
    * had vanished. The per-note check answers honestly and is asking too narrow a question: the
    * node `kafka` is indeed gone from this note. What the run may not do is spend that answer
    * while its own conclusions say otherwise.
    */
  val costUnderKafka: CardSpec =
    threeField(key("n1", "top", "kafka", "cost"), "Kafka", "Cost", "Operational complexity.", "Top")

  test("a concept this survey put a card back under has SURVIVED, though it left this note") {
    // The reviewer's S24A, as a survey. `## Cost` follows `# Kafka` into another note and pairs
    // there — subject unchanged, so that pairing needs no survival check and is decided first.
    // Kafka therefore goes on existing, and `## Definition`'s move under `# NATS` is a re-parent:
    // a different card by standing ruling R2, never a rename.
    val definitionNow = definitionUnder("NATS", "n1", "top")
    val costNow =
      threeField(key("n2", "top", "kafka", "cost"), "Kafka", "Cost", "Operational complexity.", "Top")
    surveyOver(
      Vector(observed(definitionUnderKafka, 1, Vector.empty), observed(costUnderKafka, 2, Vector.empty)),
      Vector(sourced(definitionNow, where), sourced(costNow, where)),
      Map(
        noteIdOf("n1") -> treeWith("top", "nats"),
        noteIdOf("n2") -> Vector(Vector("top"), Vector("top", "kafka")),
      ),
    ) match
      // Findings come out in the stranded keys' order, so the descriptor that stayed under Kafka
      // ('… / cost') precedes the one that left ('… / definition').
      case Vector(cost: MoveFinding.Corroborated, reparented: MoveFinding.Reparented) =>
        assertEquals(cost.candidate, costNow.key, "the witness is the pairing this run acts on")
        assertEquals(
          reparented.survival,
          SubjectSurvival.CorroboratedOnto(Vector("top", "kafka"), costNow.key),
          "the report must name the pairing it read, not a note the reader will find empty",
        )
      case other =>
        fail(s"a concept this run kept a card under must not be read as gone: $other")
  }

  test("a concept RE-NESTED under a new ancestor has survived too, though its node path is gone") {
    // The reviewer's S24B. `# Kafka` becomes `## Kafka` under a new `# Archive`, so the node
    // `top / kafka` is genuinely absent from the note — the per-note census answers "gone" here as
    // well. The corroboration onto `top / archive / kafka / cost` is what says otherwise, and the
    // ruling's coherence argument is what decides it: re-nesting a concept is not renaming it, and
    // one run may not both keep Kafka and declare it gone.
    val definitionNow = definitionUnder("NATS", "n1", "top")
    val costNow = threeField(
      key("n1", "top", "archive", "kafka", "cost"),
      "Kafka",
      "Cost",
      "Operational complexity.",
      "Top › Archive",
    )
    surveyOver(
      Vector(observed(definitionUnderKafka, 1, Vector.empty), observed(costUnderKafka, 2, Vector.empty)),
      Vector(sourced(definitionNow, where), sourced(costNow, where)),
      Map(
        noteIdOf("n1") -> (treeWith("top", "nats") ++ Vector(
          Vector("top", "archive"),
          Vector("top", "archive", "kafka"),
        ))
      ),
    ) match
      case Vector(_: MoveFinding.Corroborated, reparented: MoveFinding.Reparented) =>
        // THE SUBJECT, NOT THE NODE: the node is now `top / archive / kafka`, and it is the concept
        // as the paired card's own fields show it that the two sides are compared on.
        assertEquals(
          reparented.survival,
          SubjectSurvival.CorroboratedOnto(Vector("top", "kafka"), costNow.key),
        )
      case other => fail(s"a re-nested concept is still a concept the run kept: $other")
  }

  test("a corroboration onto some OTHER concept witnesses nothing about this one") {
    // THE VACUITY GUARD ON THE WITNESS. Without it, "did this run corroborate anything at all"
    // would pass every test above while blocking every innocent rename that happened to share a
    // run with an unrelated move. `# ZooKeeper`'s descriptor moves note-to-note and pairs; the
    // concept it lands under is ZooKeeper, and `# Kafka`'s rename to `# RabbitMQ` still follows.
    val zooWas =
      threeField(key("n1", "top", "zookeeper", "cost"), "ZooKeeper", "Cost", "Ensembles are odd-sized.", "Top")
    val zooNow =
      threeField(key("n2", "top", "zookeeper", "cost"), "ZooKeeper", "Cost", "Ensembles are odd-sized.", "Top")
    val definitionNow = definitionUnder("RabbitMQ", "n1", "top")
    surveyOver(
      Vector(observed(definitionUnderKafka, 1, Vector.empty), observed(zooWas, 2, Vector.empty)),
      Vector(sourced(definitionNow, where), sourced(zooNow, where)),
      Map(
        noteIdOf("n1") -> treeWith("top", "rabbitmq"),
        noteIdOf("n2") -> Vector(Vector("top"), Vector("top", "zookeeper")),
      ),
    ) match
      case Vector(_: MoveFinding.Corroborated, _: MoveFinding.Corroborated) => ()
      case other => fail(s"an unrelated concept's pairing must not block a rename: $other")
  }

  test("a PAIRING the run will not act on is no witness — the run consults its conclusions") {
    // The witness is what the survey CONCLUDED, not what it merely compared. Two stranded notes
    // agree with one `# Kafka` descriptor in another note, so that claim is Contested and nothing
    // is applied; a run that has established nothing about Kafka may not spend it as a survival.
    // This is the fork the implementation must get right: witnesses are read off the findings, and
    // reading them off the raw candidate lists would make this test fail.
    val rivalHere =
      threeField(key("n1", "top", "kafka", "cost"), "Kafka", "Cost", "Operational complexity.", "Top")
    val rivalElsewhere =
      threeField(key("n1", "other", "kafka", "cost"), "Kafka", "Cost", "Operational complexity.", "Top")
    val costNow =
      threeField(key("n2", "top", "kafka", "cost"), "Kafka", "Cost", "Operational complexity.", "Top")
    val definitionNow = definitionUnder("RabbitMQ", "n1", "top")
    val findings = surveyOver(
      Vector(
        observed(definitionUnderKafka, 1, Vector.empty),
        observed(rivalHere, 2, Vector.empty),
        observed(rivalElsewhere, 3, Vector.empty),
      ),
      Vector(sourced(definitionNow, where), sourced(costNow, where)),
      Map(
        noteIdOf("n1") -> treeWith("top", "rabbitmq"),
        noteIdOf("n2") -> Vector(Vector("top"), Vector("top", "kafka")),
      ),
    )
    assertEquals(
      findings.collect { case c: MoveFinding.Contested => c.stranded }.size,
      2,
      s"the fixture must contest the Kafka claim for this test to have a weapon: $findings",
    )
    findings.collectFirst { case c: MoveFinding.Corroborated => c } match
      case Some(c) => assertEquals(c.candidate, definitionNow.key)
      case None    => fail(s"the rename must still follow: $findings")
  }

  // ======== THE THIRD WITNESS OF SURVIVAL: WHAT THE LIVE COLLECTION ALREADY DECLARES ====

  /** RESOLVED 2026-09-13 (`docs/design/IDENTITY-DECISION-SHEET.md`, "survival evidence has no sync
    * boundary") as ENTAILED by the standing rulings rather than newly ruled.
    *
    * WHAT THE ATTACK WAS, BECAUSE IT IS THE ONLY THING THAT MAKES THIS SECTION LEGIBLE. Take the
    * shape the second witness was built for — `# Kafka` leaves with `## Cost` for another note while
    * `## Definition` re-parents under `# NATS` — and split it across TWO SYNCS. Run one moves Kafka
    * and Cost; the corroboration onto `kafka / cost` happens there and is over. Run two relabels the
    * heading Definition still hangs off and re-parents it. Both earlier witnesses now answer "gone"
    * HONESTLY: the node is not in this note, and this run pairs nothing onto Kafka. The descriptor's
    * history follows a subject change, which standing ruling R2 forbids.
    *
    * WHAT ANSWERS IT, AND WHY IT NEEDED NO NEW RULING. Run one left a card in the collection whose
    * `Concept` is Kafka, and a card's kind is its author's declaration about its own content — so
    * that card DECLARES the concept exists, and a declaration is a contract trusted absolutely. No
    * ruling anywhere says evidence expires. The witness set therefore gains every LIVE card of a
    * parent-constitutive kind whose declared concept is the old subject: no clock, no ledger, no
    * git, only labels the collection already holds.
    *
    * THE TESTS COME IN PAIRS ON PURPOSE. Each positive case is followed by the negative that bounds
    * it, because a witness that fires too widely does not merely report oddly — it stops innocent
    * renames from following, silently, which is the loss this whole file exists to prevent.
    */
  val costUnderKafkaElsewhere: CardSpec =
    threeField(key("n2", "top", "kafka", "cost"), "Kafka", "Cost", "Operational complexity.", "Top")

  test("a concept a LIVE card still declares has survived, though THIS run pairs nothing onto it") {
    // The two-run attack, as one survey: the collection is what run two is handed, and the card run
    // one put under `# Kafka` in another note is still in it. The census answers honestly that this
    // note's `kafka` node is gone, and there is no corroboration onto Kafka anywhere in this run —
    // run one did that job. Without the third witness the descriptor's history follows.
    val definitionNow = definitionUnder("NATS", "n1", "top")
    surveyDeclaring(
      Vector(observed(definitionUnderKafka, 1, Vector.empty)),
      Vector(sourced(definitionNow, where)),
      Map(noteIdOf("n1") -> treeWith("top", "nats")),
      Vector(observed(costUnderKafkaElsewhere, 101, Vector.empty)),
    ) match
      case Vector(MoveFinding.Reparented(stranded, _, candidate, _, survival, _)) =>
        assertEquals(stranded, definitionUnderKafka.key)
        assertEquals(candidate, definitionNow.key)
        assertEquals(
          survival,
          SubjectSurvival.StillInTheCollection(Vector("top", "kafka"), costUnderKafkaElsewhere.key),
          "the report must name the card it read, which is the fact a reader can check",
        )
      case other =>
        fail(s"a concept a live card declares must not be read as gone: $other")
  }

  test("a live card of a FILING kind declares nothing, so an innocent rename still follows") {
    // THE NEGATIVE THAT BOUNDS THE WITNESS, and the shape is the one that makes the cost concrete:
    // `# Kafka` the novelist, filed in somebody's reading notes, must not stop `# Kafka` the message
    // broker being renamed. Both live cards here hang off a heading of that name and NEITHER
    // declares a concept — a plain `1way` heading card and a table's row card both show ONE name
    // field, so the window minus its last segment is empty. Their ancestor is filing, which the
    // per-kind ruling already refused as a witness; this pins that the refusal falls out of the
    // roles table rather than out of a note-type check somebody has to maintain.
    val novelist =
      twoField(key("n9", "franz kafka", "born"), "Born", "Prague, 1883.", "Reading notes")
    val row = CardSpec.TableRow(key("n9", "kafka", "novels"), "Novels", "The Trial; The Castle.", "Reading notes")
    assertEquals(
      MoveEvidence.nameDepthOf(novelist.noteTypeName),
      1,
      "the fixture must be a one-name-field kind for this test to have a weapon",
    )
    assertEquals(MoveEvidence.nameDepthOf(row.noteTypeName), 1)

    val now = definitionUnder("RabbitMQ", "n1", "top")
    surveyDeclaring(
      Vector(observed(definitionUnderKafka, 1, Vector.empty)),
      Vector(sourced(now, where)),
      Map(noteIdOf("n1") -> treeWith("top", "rabbitmq")),
      Vector(observed(novelist, 101, Vector.empty), observed(row, 102, Vector.empty)),
    ) match
      case Vector(_: MoveFinding.Corroborated) => ()
      case other =>
        fail(s"filing under a same-named heading must not block a rename: $other")
  }

  test("a live TABLE PAIR card declares its row concept, exactly as a heading's descriptor does") {
    // THE OTHER HALF OF THE PER-KIND REACH. The sheet names `cdd/*` AND `table` as the
    // parent-constitutive kinds, and a table's pair card is a concept-descriptor card whose concept
    // is the row's first cell — so the same window, read the same way, and no second rule.
    // ITS CHAIN IS THE STRANDED CARD'S SUBJECT CHAIN, which the ruling of 2026-09-13 requires of
    // every witness: a table under `# Top` whose row is Kafka puts a card at `top / kafka / …`, the
    // same place `## Definition` hung off. A namesake row under some other ancestor would testify to
    // nothing, and the test below this one is that case.
    val pairCard = CardSpec.ThreeField(
      key("n2", "top", "kafka", "throughput"),
      "Kafka",
      "Throughput",
      body("Millions of messages a second."),
      ThreeFieldDirections.Default,
      "Comparison",
      // The first column's header, which is what makes this a TABLE pair card rather than a
      // heading's — `FieldRole.Setting`, and empty on the heading shape.
      "System",
    )
    val definitionNow = definitionUnder("NATS", "n1", "top")
    surveyDeclaring(
      Vector(observed(definitionUnderKafka, 1, Vector.empty)),
      Vector(sourced(definitionNow, where)),
      Map(noteIdOf("n1") -> treeWith("top", "nats")),
      Vector(observed(pairCard, 101, Vector.empty)),
    ) match
      case Vector(r: MoveFinding.Reparented) =>
        assertEquals(r.survival, SubjectSurvival.StillInTheCollection(Vector("top", "kafka"), pairCard.key))
      case other => fail(s"a table row's concept is a concept: $other")
  }

  // ======= A CONCEPT IS ITS CHAIN: A NAMESAKE AT AN UNRELATED PLACE IS SILENCE ====

  /** RESOLVED 2026-09-13 (`docs/design/IDENTITY-DECISION-SHEET.md`, "a concept is its chain;
    * namesakes at unrelated places are silence"), and entailed by standing ruling R2.
    *
    * THE SHAPE, WHICH IS AS ORDINARY AS A VAULT GETS. `Kafka.md` holds `# Kafka` / `## Performance`
    * with a card under it; `NATS.md` holds `# NATS` / `## Performance` with its own. Those are TWO
    * CONCEPTS SHARING A SPELLING, not one concept in two files — R2 says as much already, since the
    * parent concept is constitutive and the chain is what identifies it. Marc, shown the pair: "you
    * realize that there are no questions there?"
    *
    * WHY THE WITNESSES WERE BARE-NAME IN THE FIRST PLACE, because it was deliberate rather than
    * careless: a RELOCATED concept still has to witness. When `# Kafka` leaves for another note with
    * one of its descriptors, the pairing that lands there is what says Kafka goes on existing (the
    * reviewer's S24A), and its new chain is not its old one. Matching the name alone was the cheapest
    * thing that kept that working.
    *
    * THE RECONCILIATION, WHICH IS THE WHOLE OF THIS SECTION. A witness vouches for the subject at
    * place P only if it STANDS AT P, or if ITS OWN PAIRING MOVED IT FROM P in this run — and that
    * pairing's OLD key is the bridge between the chains. A bare name somewhere else with no bridge
    * testifies to nothing. Both halves are tested: the two tests here are the namesakes that must
    * fall silent, and the S24A, S24B and cross-note-live tests above are the bridges and the
    * same-chain standings that must go on working.
    */
  val throughputUnderKafkaPerformance: CardSpec =
    threeField(
      key("k1", "kafka", "performance", "throughput"),
      "Performance",
      "Throughput",
      "Millions of messages a second.",
      "Kafka",
    )

  /** The same descriptor moved under a SIBLING subject of its own note — an ordinary re-filing, and
    * the event both namesake tests put a stranger's `## Performance` in the way of.
    */
  val throughputUnderKafkaDurability: CardSpec =
    threeField(
      key("k1", "kafka", "durability", "throughput"),
      "Durability",
      "Throughput",
      "Millions of messages a second.",
      "Kafka",
    )

  /** `NATS.md`'s own `## Performance` cluster: the same subject SPELLING under a different parent, in
    * a different note, untouched by the run and bridged to nothing.
    */
  val latencyUnderNatsPerformance: CardSpec =
    threeField(
      key("n1", "nats", "performance", "latency"),
      "Performance",
      "Latency",
      "Sub-millisecond, in memory.",
      "NATS",
    )

  /** What `Kafka.md` looks like once Throughput sits under `## Durability`: its `## Performance` is
    * gone, and `NATS.md`'s is exactly where it always was.
    */
  val afterTheReFiling: NodeCensus.Outlines = Map(
    noteIdOf("k1") -> Vector(
      Vector("kafka"),
      Vector("kafka", "durability"),
      Vector("kafka", "durability", "throughput"),
    ),
    noteIdOf("n1") -> Vector(
      Vector("nats"),
      Vector("nats", "performance"),
      Vector("nats", "performance", "latency"),
    ),
  )

  test("a LIVE namesake subject at an unrelated chain is no witness, so the re-filing follows") {
    // THE THIRD WITNESS, QUALIFIED. `NATS.md`'s live `## Performance` card is in the collection and
    // declares the subject spelled `performance` — and it stands at `nats / performance`, which is not
    // the `kafka / performance` this card left. Nothing bridges the two, so it testifies to nothing
    // and the run proceeds exactly as if no namesake existed: the old subject is gone from this note,
    // the cluster stayed, and Decision 2 follows the relabel.
    surveyDeclaring(
      Vector(observed(throughputUnderKafkaPerformance, 1, Vector.empty)),
      Vector(sourced(throughputUnderKafkaDurability, where)),
      afterTheReFiling,
      Vector(observed(latencyUnderNatsPerformance, 101, Vector.empty)),
    ) match
      case Vector(c: MoveFinding.Corroborated) =>
        assertEquals(c.candidate, throughputUnderKafkaDurability.key)
      case other =>
        fail(s"another note's same-named subject must not block an ordinary re-filing: $other")
  }

  test("a PAIRING onto a namesake subject at an unrelated chain is no witness either") {
    // THE SECOND WITNESS, QUALIFIED, AND IT NEEDS ITS OWN TEST BECAUSE IT HAS ITS OWN BRIDGE. Here
    // `NATS.md`'s Performance cluster is not merely standing — it MOVES, to another note, in this very
    // run, so the survey corroborates a card whose subject is spelled `performance`. That pairing
    // bridges `nats / performance` to wherever it went; it says nothing about `kafka / performance`,
    // and the re-filing in Kafka.md must follow regardless.
    val latencyMoved =
      threeField(
        key("n2", "nats", "performance", "latency"),
        "Performance",
        "Latency",
        "Sub-millisecond, in memory.",
        "NATS",
      )
    val findings = surveyOver(
      Vector(observed(throughputUnderKafkaPerformance, 1, Vector.empty), observed(latencyUnderNatsPerformance, 2, Vector.empty)),
      Vector(sourced(throughputUnderKafkaDurability, where), sourced(latencyMoved, where)),
      afterTheReFiling ++ Map(
        noteIdOf("n2") -> Vector(Vector("nats"), Vector("nats", "performance"))
      ),
    )
    assert(
      findings.exists {
        case c: MoveFinding.Corroborated => c.candidate == latencyMoved.key
        case _                           => false
      },
      s"the fixture must corroborate the NATS move for this test to have a weapon: $findings",
    )
    findings.find(_.strandedNote == AnkiNoteId(1)) match
      case Some(c: MoveFinding.Corroborated) =>
        assertEquals(c.candidate, throughputUnderKafkaDurability.key)
      case other =>
        fail(s"an unrelated chain's pairing must not park this one: $other")
  }

  test("two notes holding the SAME chain still testify about one another — the residual cost") {
    // THE BOUNDARY OF THE QUALIFICATION, PINNED SO THAT IT IS A MEASURED COST RATHER THAN A CLAIM IN
    // A DOCSTRING. The chain is compared and the note id is not, and it cannot be: the
    // live-collection witness exists precisely for a concept that left for ANOTHER note in an earlier
    // sync, so requiring the note to agree would retire the witness altogether (the two-run shape).
    // The price is this fixture — two notes that each hold `# Kafka` / `## Definition`, so renaming
    // one note's Kafka is parked by the other note's.
    //
    // IT IS A DIFFERENT CASE FROM THE NAMESAKE ABOVE, which is why it is priced differently: there
    // the two subjects sat under different parents and were plainly two concepts, while here the vault
    // says the same concept lives in two places. Parking errs toward keeping a history rather than
    // moving it, which is the safe direction, and it is reported rather than silent. Whether a vault
    // may hold one concept's chain twice is the author's question, not this survey's.
    val twinElsewhere =
      threeField(key("n2", "top", "kafka", "cost"), "Kafka", "Cost", "Operational complexity.", "Top")
    val now = definitionUnder("RabbitMQ", "n1", "top")
    surveyDeclaring(
      Vector(observed(definitionUnderKafka, 1, Vector.empty)),
      Vector(sourced(now, where)),
      Map(noteIdOf("n1") -> treeWith("top", "rabbitmq")),
      Vector(observed(twinElsewhere, 101, Vector.empty)),
    ) match
      case Vector(r: MoveFinding.Reparented) =>
        assertEquals(
          r.survival,
          SubjectSurvival.StillInTheCollection(Vector("top", "kafka"), twinElsewhere.key),
        )
      case other =>
        fail(s"the same chain in another note is still a witness, by the rule as ruled: $other")
  }

  test("a live card under some OTHER concept witnesses nothing about this one") {
    // THE VACUITY GUARD ON THE THIRD WITNESS, the twin of the one the second witness carries.
    // Without it, "does the collection declare anything at all" would pass every test above while
    // blocking every rename in a collection that holds any concept-descriptor card — which is every
    // real collection.
    val zooKeeper =
      threeField(key("n2", "top", "zookeeper", "cost"), "ZooKeeper", "Cost", "Ensembles are odd-sized.", "Top")
    val now = definitionUnder("RabbitMQ", "n1", "top")
    surveyDeclaring(
      Vector(observed(definitionUnderKafka, 1, Vector.empty)),
      Vector(sourced(now, where)),
      Map(noteIdOf("n1") -> treeWith("top", "rabbitmq")),
      Vector(observed(zooKeeper, 101, Vector.empty)),
    ) match
      case Vector(_: MoveFinding.Corroborated) => ()
      case other => fail(s"an unrelated concept's live card must not block a rename: $other")
  }

  /** THE SAME RULING ONE LEVEL FLATTER — `docs/design/IDENTITY-DECISION-SHEET.md`, "a concept is its
    * chain; namesakes at unrelated places are silence" — for the subject that has NO chain above it.
    *
    * WHY THE TESTS ABOVE DO NOT COVER IT, which is the whole reason this block exists. There the two
    * namesakes sat under different parents (`kafka / performance` against `nats / performance`), so
    * comparing chains told them apart on its own. THE COMMONEST VAULT SHAPE THERE IS has no parent at
    * all: `Broker.md` opens on `# Kafka`, `Novelist.md` opens on its own `# Kafka` about Franz Kafka,
    * and each chain is the single segment `kafka`. Compare those two chains and you are comparing
    * bare names — which is exactly what the ruling forbids, arrived at by a route that looks like
    * obeying it.
    *
    * WHAT THE PLACE IS WHEN THE CHAIN RUNS OUT. A subject's place is what stands above it. Inside a
    * note that is its ancestor chain; with nothing above it, the subject stands at the note's ROOT,
    * and the note is then the only place-fact there is. So a root-level subject is matched on its
    * note as well as its name, and a subject with ancestors goes on being matched on those alone —
    * which is what keeps "two notes holding the same chain" (above) the priced residual cost it was
    * ruled to be rather than something this block quietly widens.
    *
    * ONE OF THE TWO VAULT-WIDE WITNESSES READS IT, AND THE SPLIT IS NOT TIDINESS. THIS RUN'S OWN
    * PAIRINGS do: the ruling's bridge clause is "its own pairing moved it from P THIS RUN", a fact
    * wholly inside the run, so a pairing out of `Novelist.md`'s root can be told from one out of
    * `Broker.md`'s and the second test below is that. THE LIVE COLLECTION does not, because at this
    * exact shape the same day's ruling that survival evidence has no sync boundary wants the opposite
    * answer and deck scenario S24D pins it — the first test below carries that conflict in full and
    * is flagged ⚠️ because it records an open question rather than a ruled one.
    */
  val brokerDefinitionAtRoot: CardSpec =
    threeField(
      key("n1", "kafka", "definition"),
      "Kafka",
      "Definition",
      "A distributed event log with partitioned, replicated topics.",
      "Broker",
    )

  /** The same card after the author respelled the concept and moved the note: `# NATS` in
    * `Systems.md`, description byte-identical. The ruled follow.
    */
  val systemsDefinitionAtRoot: CardSpec =
    threeField(
      key("n3", "nats", "definition"),
      "NATS",
      "Definition",
      "A distributed event log with partitioned, replicated topics.",
      "Systems",
    )

  /** `Novelist.md`'s own root-level `# Kafka` — Franz Kafka, a different concept sharing a spelling,
    * with its own descriptor and its own description so that nothing but the name can collide.
    */
  val novelistOriginAtRoot: CardSpec =
    threeField(
      key("n2", "kafka", "origin"),
      "Kafka",
      "Origin",
      "Born in Prague in 1883, he became a major fiction writer.",
      "Novelist",
    )

  /** `Broker.md` after the edit: prose only, so the concept the stranded card left is gone from the
    * note it left, and `Systems.md` holds the respelled one.
    */
  val afterTheRootRelabel: NodeCensus.Outlines = Map(
    noteIdOf("n1") -> Vector.empty,
    noteIdOf("n3") -> Vector(Vector("nats"), Vector("nats", "definition")),
  )

  test("⚠️ a LIVE namesake at ANOTHER NOTE'S ROOT still parks — two rulings disagree here") {
    // ⚠️ THIS ASSERTS THE CURRENT ANSWER TO AN OPEN QUESTION, NOT A RULED ONE, and it is written down
    // as a test rather than left in a docstring so that whoever rules it finds the fixture already
    // built. Everything below is what the run does today; whether it is right is Marc's to say.
    //
    // WHAT THE JUDGE MEASURED, and it is a real cost. `Novelist.md`'s live `## Origin` card is about
    // Franz Kafka. It declares a subject spelled `kafka` standing at Novelist.md's ROOT — not at
    // Broker.md's, where the message broker's subject stood. By "a concept is its chain; namesakes at
    // unrelated places are silence" it should testify to nothing, and the relabel should follow as
    // the control above it does. Instead it parks, and the run TELLS THE AUTHOR that 'kafka' goes on
    // existing under the place this card left — which is false about a concept that is gone.
    //
    // WHY IT IS NOT SIMPLY FIXED. The witness matching compares canonicalised chains, and at the flat
    // shape — a note whose first heading IS its concept — the chain is one segment, so comparing
    // chains is comparing bare names. Adding the note to the key at that shape, which is what the
    // namesake ruling reads like, breaks deck scenario S24D: there a live `kafka / cost` in ANOTHER
    // NOTE is the only thing that stops a re-parent following a subject change, and the sheet's
    // "survival evidence has no sync boundary" entry ruled exactly that it must.
    //
    // AND THE TWO CASES ARE ISOMORPHIC. Stranded card left note X, candidate is in note Y, witness
    // stands in note Z: the judge's JN-LIVE is X≠Y≠Z, S24D run 2 is X=Y, Z elsewhere. The only fact
    // that differs is where the vault now produces the CARD — and the entailment of the same day took
    // that out of a `/2way` card's claim. So no rule keyed on the witness can honour both.
    //
    // WHY THE UNRESOLVED STATE IS LEFT THIS WAY ROUND. Parking strands a history where it is;
    // following moves one onto a card R2 calls a different card. The first is recoverable and
    // reported, the second is the thing this whole gate exists to prevent.
    surveyDeclaring(
      Vector(observed(brokerDefinitionAtRoot, 1, Vector.empty)),
      Vector(sourced(systemsDefinitionAtRoot, where)),
      afterTheRootRelabel,
      Vector(observed(novelistOriginAtRoot, 101, Vector.empty)),
    ) match
      case Vector(r: MoveFinding.Reparented) =>
        assertEquals(
          r.survival,
          SubjectSurvival.StillInTheCollection(Vector("kafka"), novelistOriginAtRoot.key),
        )
      case other =>
        fail(s"the flat-shape namesake question is open, and this is the answer on record: $other")
  }

  test("a PAIRING onto a namesake at ANOTHER NOTE'S ROOT is no bridge either") {
    // THE SECOND WITNESS AT THE FLAT SHAPE, and it needs its own test because it has its own bridge.
    // Here the novelist's cluster does not merely stand — it verbatim-moves `Novelist.md` →
    // `Writers.md` in this very run, so the survey corroborates a card whose subject is spelled
    // `kafka`. That pairing bridges NOVELIST.MD'S root to wherever it went; Broker.md's root is not
    // an end of it, and the message broker's relabel must follow regardless.
    val novelistMoved =
      threeField(
        key("n4", "kafka", "origin"),
        "Kafka",
        "Origin",
        "Born in Prague in 1883, he became a major fiction writer.",
        "Writers",
      )
    val findings = surveyOver(
      Vector(observed(brokerDefinitionAtRoot, 1, Vector.empty), observed(novelistOriginAtRoot, 2, Vector.empty)),
      Vector(sourced(systemsDefinitionAtRoot, where), sourced(novelistMoved, where)),
      afterTheRootRelabel ++ Map(
        noteIdOf("n2") -> Vector.empty,
        noteIdOf("n4") -> Vector(Vector("kafka"), Vector("kafka", "origin")),
      ),
    )
    assert(
      findings.exists {
        case c: MoveFinding.Corroborated => c.candidate == novelistMoved.key
        case _                           => false
      },
      s"the fixture must corroborate the novelist's move for this test to have a weapon: $findings",
    )
    findings.find(_.strandedNote == AnkiNoteId(1)) match
      case Some(c: MoveFinding.Corroborated) =>
        assertEquals(c.candidate, systemsDefinitionAtRoot.key)
      case other =>
        fail(s"a pairing out of another note's root must not park this one: $other")
  }

  test("the SAME note's root still witnesses, which is the bridge this must not break") {
    // THE GUARD IN THE OTHER DIRECTION, and the reason the note is read rather than simply ignored at
    // the flat shape. When `# Kafka` leaves Broker.md for another note WITH one of its descriptors,
    // that pairing's old key is Broker.md's root — the very place this stranded card's subject stood —
    // so it bridges, and the re-parent left behind must be refused. This is the reviewer's S24A with
    // no ancestor heading above the concept.
    val brokerCost =
      threeField(key("n1", "kafka", "cost"), "Kafka", "Cost", "Operational complexity.", "Broker")
    val costMovedOut =
      threeField(key("n5", "kafka", "cost"), "Kafka", "Cost", "Operational complexity.", "Queues")
    val findings = surveyOver(
      Vector(observed(brokerDefinitionAtRoot, 1, Vector.empty), observed(brokerCost, 2, Vector.empty)),
      Vector(sourced(systemsDefinitionAtRoot, where), sourced(costMovedOut, where)),
      afterTheRootRelabel ++ Map(
        noteIdOf("n5") -> Vector(Vector("kafka"), Vector("kafka", "cost"))
      ),
    )
    findings.find(_.strandedNote == AnkiNoteId(1)) match
      case Some(r: MoveFinding.Reparented) =>
        assertEquals(
          r.survival,
          SubjectSurvival.CorroboratedOnto(Vector("kafka"), costMovedOut.key),
        )
      case other =>
        fail(s"the subject's own note pairing it elsewhere is still the bridge: $other")
  }

  // ==== THE PAIR VETO: A DECLARATION CAUGHT BREAKING IS REFUSED FOR THE CARDS INVOLVED ====

  /** RULED 2026-09-13 (`docs/design/IDENTITY-DECISION-SHEET.md`, "the duplicate veto fires on the
    * (descriptor, description) PAIR").
    *
    * WHAT A `/2way` DECLARATION ACTUALLY CLAIMS, which is the precision the ruling turns on. Its
    * backward card asks "WHICH THING has this DESCRIPTOR with this DESCRIPTION?" — so the claim's unit
    * is the PAIR, not the description. Two cards sharing a description under different descriptors are
    * innocent text reuse and each still has one true answer. The same descriptor AND description under
    * two different concepts is a measurable contradiction: the backward card has two true answers, so
    * the card is broken as a flashcard regardless of anything this survey concludes.
    *
    * WHAT HAPPENS THEN, per the ruled principle that declarations are contracts: a declaration is
    * trusted absolutely WHILE COHERENT, and a detected contradiction is refused loudly for exactly the
    * cards involved — R5's scoping — and reported as the author's edit to make. So this is not "one
    * voucher is missing, try the others": nothing is applied, and the remedy named is theirs.
    *
    * WHY THE AMBIGUITY GUARD DOES NOT ALREADY COVER IT, which is the question to ask of any new
    * refusal here. It covers the SYMMETRIC case, where both colliding cards are inside this sync's
    * delta — deck S32 renames a column while two rows hold the byte-identical value and comes back
    * `Ambiguous`, applying nothing. It cannot see a rival that simply STANDS: an unchanged live card is
    * in neither `stranded` nor `unclaimed`, so nothing in the delta mentions it. That asymmetric shape
    * is what the ruling was made about, and the first test below is it.
    */
  val definitionUnderKafkaAlone: CardSpec =
    threeField(key("k1", "kafka", "definition"), "Kafka", "Definition", "A durable log.", "Kafka")

  /** `NATS.md`'s own `## Definition`, with the byte-identical description: the same backward question
    * answered by a different concept, standing and untouched.
    */
  val definitionUnderNatsAlone: CardSpec =
    threeField(key("n1", "nats", "definition"), "NATS", "Definition", "A durable log.", "NATS")

  test("a STANDING twin at the same (descriptor, description) breaks the claim, so nothing follows") {
    // THE RULED SHAPE. `Kafka.md`'s `# Kafka` is relabelled `# Streaming`, which every gate before this
    // one reads as an innocent rename: the substance agrees, the old subject stands nowhere, the
    // cluster did not move, and the card declares its description identifying — so it followed. But
    // `NATS.md` holds `## Definition` over the same description, so the claim that this description
    // identifies its concept is false in the vault, and the card that rests on it may not be moved on
    // its word.
    val relabelled =
      threeField(key("k1", "streaming", "definition"), "Streaming", "Definition", "A durable log.", "Kafka")
    surveyDeclaring(
      Vector(observed(definitionUnderKafkaAlone, 1, Vector.empty)),
      Vector(sourced(relabelled, where)),
      Map(noteIdOf("k1") -> Vector(Vector("streaming"), Vector("streaming", "definition"))),
      Vector(observed(definitionUnderNatsAlone, 101, Vector.empty)),
    ) match
      case Vector(MoveFinding.ClaimBroken(stranded, _, candidate, _, claim, alsoAnswered, _)) =>
        assertEquals(stranded, definitionUnderKafkaAlone.key)
        assertEquals(candidate, relabelled.key)
        assertEquals(claim.descriptor, "definition")
        assertEquals(claim.concept, "streaming")
        // BOTH STANDING PLACES ARE NAMED, which is the whole of the remedy: without the twin's key the
        // author is told their vault is inconsistent and left to find where.
        assertEquals(alsoAnswered.toVector.map(_.at), Vector(definitionUnderNatsAlone.key))
        assertEquals(alsoAnswered.head.concept, "nats")
      case other =>
        fail(s"a description answering two concepts may not vouch for anything: $other")
  }

  test("the SAME description under a DIFFERENT descriptor is innocent, and must not trip the veto") {
    // THE NEGATIVE GUARD, and the reason the unit is the pair. Addition's `## Definition` and
    // multiplication's `## Nature` may both read "a binary operation": each card's backward question
    // is its own, each has exactly one true answer, and neither author has claimed anything false.
    // Without this the veto would fire on ordinary text reuse and strand histories for nothing.
    val natureUnderNats =
      threeField(key("n1", "nats", "nature"), "NATS", "Nature", "A durable log.", "NATS")
    val relabelled =
      threeField(key("k1", "streaming", "definition"), "Streaming", "Definition", "A durable log.", "Kafka")
    assertEquals(
      natureUnderNats.fields.toMap.apply("Description"),
      relabelled.fields.toMap.apply("Description"),
      "the fixture must share the description for this guard to have a weapon",
    )
    surveyDeclaring(
      Vector(observed(definitionUnderKafkaAlone, 1, Vector.empty)),
      Vector(sourced(relabelled, where)),
      Map(noteIdOf("k1") -> Vector(Vector("streaming"), Vector("streaming", "definition"))),
      Vector(observed(natureUnderNats, 101, Vector.empty)),
    ) match
      case Vector(c: MoveFinding.Corroborated) => assertEquals(c.candidate, relabelled.key)
      case other => fail(s"a shared description under another descriptor is not a contradiction: $other")
  }

  test("a twin the SAME concept answers is no contradiction — one true answer, in two places") {
    // THE OTHER HALF OF THE PAIR TEST, and it is not the same guard. Here the descriptor AND the
    // description agree with the twin, and so does the CONCEPT: the backward card's answer is
    // 'kafka' either way, so the author can answer it. What the vault has is one concept written in
    // two places — the residual ambiguity slice 5 priced — and not a broken claim.
    val twinInAnotherNote =
      threeField(key("n1", "kafka", "definition"), "Kafka", "Definition", "A durable log.", "Kafka")
    val reworded =
      threeField(key("k1", "kafka", "formal definition"), "Kafka", "Formal definition", "A durable log.", "Kafka")
    surveyDeclaring(
      Vector(observed(definitionUnderKafkaAlone, 1, Vector.empty)),
      Vector(sourced(reworded, where)),
      Map(noteIdOf("k1") -> Vector(Vector("kafka"), Vector("kafka", "formal definition"))),
      Vector(observed(twinInAnotherNote, 101, Vector.empty)),
    ) match
      case Vector(c: MoveFinding.Corroborated) => assertEquals(c.candidate, reworded.key)
      case other => fail(s"one concept answering in two places is not a contradiction: $other")
  }

  test("a cdd/1way card is refused nothing by the veto, though its own fact still breaks others") {
    // THE TWO QUESTIONS, KEPT APART. Whose claim can BREAK is a question about a declaration, so a
    // `cdd/1way` card — which claims no backward reading at all — is never vetoed, even with a twin
    // standing at its pair. Whether the question stands ANSWERED TWICE is a question about the world,
    // so that same 1way card counts as a rival against a `/2way` card elsewhere. The second half is
    // what the third assertion here pins.
    val oneWayKafka =
      threeFieldOneWay(key("k1", "kafka", "definition"), "Kafka", "Definition", "A durable log.", "Kafka")
    val oneWayRelabelled =
      threeFieldOneWay(key("k1", "streaming", "definition"), "Streaming", "Definition", "A durable log.", "Kafka")
    val outline = Map(noteIdOf("k1") -> Vector(Vector("streaming"), Vector("streaming", "definition")))

    // Its own claim cannot break, because it made none.
    surveyDeclaring(
      Vector(observed(oneWayKafka, 1, Vector.empty)),
      Vector(sourced(oneWayRelabelled, where)),
      outline,
      Vector(observed(definitionUnderNatsAlone, 101, Vector.empty)),
    ) match
      case Vector(_: MoveFinding.Corroborated) => ()
      case other => fail(s"a 1way card claims no backward reading, so it cannot break one: $other")

    // But the fact it states stands, and it breaks a 2way card's claim just the same.
    val oneWayNats =
      threeFieldOneWay(key("n1", "nats", "definition"), "NATS", "Definition", "A durable log.", "NATS")
    val twoWayRelabelled =
      threeField(key("k1", "streaming", "definition"), "Streaming", "Definition", "A durable log.", "Kafka")
    surveyDeclaring(
      Vector(observed(definitionUnderKafkaAlone, 1, Vector.empty)),
      Vector(sourced(twoWayRelabelled, where)),
      outline,
      Vector(observed(oneWayNats, 101, Vector.empty)),
    ) match
      case Vector(b: MoveFinding.ClaimBroken) =>
        assertEquals(b.alsoAnswered.toVector.map(_.at), Vector(oneWayNats.key))
      case other =>
        fail(s"a standing fact contradicts a 2way claim whatever its own author declared: $other")
  }

  test("a rival the VAULT is creating this run breaks the claim too, not only a standing one") {
    // THE OTHER SIDE OF THE POPULATION. The twin need not have stood for months: a section created in
    // this very sync under another concept, with the same descriptor and description, makes the claim
    // just as false. The specs the vault now produces are read alongside the live cards for that
    // reason.
    //
    // THE RIVAL IS `cdd/3way` HERE FOR A MECHANICAL REASON WORTH KNOWING. A rival with the same
    // descriptor and description would ordinarily also be a CANDIDATE for this stranded note — the
    // comparison floor is substance agreement, which it meets — and two candidates make the finding
    // `Ambiguous` before the claim is ever examined. A differing Setting field keeps the pairing
    // unique: `3way` sets `ThreeWay` and `2way` does not, so the floor refuses that pairing while the
    // card still stands, still answers the same backward question, and still claims it. Which is
    // itself the point of the veto — the ambiguity guard only sees rivals that compete for the same
    // note.
    val relabelled =
      threeField(key("k1", "streaming", "definition"), "Streaming", "Definition", "A durable log.", "Kafka")
    val bornThisRun =
      threeFieldAllWays(key("n1", "nats", "definition"), "NATS", "Definition", "A durable log.", "NATS")
    val findings = surveyOver(
      Vector(observed(definitionUnderKafkaAlone, 1, Vector.empty)),
      Vector(sourced(relabelled, where), sourced(bornThisRun, where)),
      Map(noteIdOf("k1") -> Vector(Vector("streaming"), Vector("streaming", "definition"))),
    )
    assertEquals(findings.size, 1, s"only one note is stranded here: $findings")
    findings.head match
      case b: MoveFinding.ClaimBroken =>
        assertEquals(b.alsoAnswered.toVector.map(_.at), Vector(bornThisRun.key))
      case other => fail(s"a rival created this run breaks the claim as well: $other")
  }

  /** THE VETO ACROSS THE TWO AUTHORING SHAPES OF ONE CARD KIND — the population Decision 3 says is
    * one ("a table column header is the descriptor position of every pair card in that column"), and
    * the one the check could not see across.
    *
    * WHY THE BYTES DIVERGE WHEN THE AUTHOR'S SENTENCE DOES NOT. A concept-descriptor card is built two
    * ways and its `Description` is rendered by two different routes. A heading section goes through
    * `extract/Extractor.scala`'s `AsHtml.plain`, which renders BLOCKS, so one sentence of prose comes
    * back inside a paragraph element. A table cell goes through `extract/Tables.scala`'s
    * `CellDisplay.Escaped`, which escapes the cell's text and emits no block at all. Same author
    * sentence, permanently different field bytes — and `ReverseClaim` compares that field verbatim, so
    * the collision the ruling exists to catch could never be detected between the two shapes.
    *
    * WHY THE RULING'S "WHEN THAT PAIR-COLLISION IS DETECTED" DOES NOT EXCUSE IT. That conditional, and
    * principle (3)'s undetected false declaration, describe a contradiction the census cannot see.
    * Here the census holds BOTH cards, of one note type, over one author sentence; the divergence is
    * manufactured by this tool's own rendering. The reading that fixes it removes exactly that framing
    * and touches no byte the author wrote — which is what the last test in this block pins, because
    * the line is only sharp while a difference the AUTHOR made goes on being a difference.
    */
  val durableLogFromAHeading: String = "<p>A durable log.</p>"

  /** The same author sentence as `durableLogFromAHeading`, as a table cell renders it. */
  val durableLogFromATable: String = "A durable log."

  test("a TABLE twin at the same pair breaks a heading card's claim, framing or no framing") {
    // THE JUDGE'S `TABLETWIN`. Every gate before the veto reads this as an innocent rename: substance
    // agrees, the old subject stands nowhere, and the card declares its description identifying. But
    // `Stores`' table answers the very same backward question — "which thing has this Definition with
    // this description?" — under `nats`, so the claim the follow rests on is false in the vault.
    assertNotEquals(
      durableLogFromAHeading,
      durableLogFromATable,
      "the fixture must carry the two renderings for this test to have a weapon",
    )
    val strandedHeading =
      threeField(key("h1", "kafka", "definition"), "Kafka", "Definition", durableLogFromAHeading, "Kafka")
    val relabelled =
      threeField(key("h1", "streaming", "definition"), "Streaming", "Definition", durableLogFromAHeading, "Kafka")
    val tableTwin =
      threeField(key("t1", "stores", "nats", "definition"), "NATS", "Definition", durableLogFromATable, "Stores")
    surveyDeclaring(
      Vector(observed(strandedHeading, 1, Vector.empty)),
      Vector(sourced(relabelled, where)),
      Map(noteIdOf("h1") -> Vector(Vector("streaming"), Vector("streaming", "definition"))),
      Vector(observed(tableTwin, 101, Vector.empty)),
    ) match
      case Vector(b: MoveFinding.ClaimBroken) =>
        assertEquals(b.alsoAnswered.toVector.map(_.at), Vector(tableTwin.key))
        assertEquals(b.alsoAnswered.head.concept, "nats")
      case other =>
        fail(s"a table pair card answering the same question is the contradiction, not a stranger: $other")
  }

  test("a HEADING twin at the same pair breaks a table row card's claim — the mirror") {
    // THE JUDGE'S `PV-REV`, and it is a separate test rather than a parameter because the blind spot
    // was symmetric: each shape's veto worked among its own kind, so only running the pair both ways
    // shows that the population is one. Here the row `Queue` becomes `Stream` with its value
    // untouched, while `NATS.md`'s heading-built `## Definition` stands over the same sentence.
    val strandedRow =
      threeField(key("t1", "brokers", "queue", "definition"), "Queue", "Definition", durableLogFromATable, "Brokers")
    val relabelledRow =
      threeField(key("t1", "brokers", "stream", "definition"), "Stream", "Definition", durableLogFromATable, "Brokers")
    val headingTwin =
      threeField(key("n1", "nats", "definition"), "NATS", "Definition", durableLogFromAHeading, "NATS")
    surveyDeclaring(
      Vector(observed(strandedRow, 1, Vector.empty)),
      Vector(sourced(relabelledRow, where)),
      Map(
        noteIdOf("t1") -> Vector(
          Vector("brokers"),
          Vector("brokers", "stream"),
          Vector("brokers", "stream", "definition"),
        )
      ),
      Vector(observed(headingTwin, 101, Vector.empty)),
    ) match
      case Vector(b: MoveFinding.ClaimBroken) =>
        assertEquals(b.alsoAnswered.toVector.map(_.at), Vector(headingTwin.key))
        assertEquals(b.alsoAnswered.head.concept, "nats")
      case other =>
        fail(s"a heading card answering the same question breaks a row card's claim too: $other")
  }

  test("a byte the AUTHOR wrote differently is still a different description, not a framing") {
    // THE BOUNDARY, AND IT IS WHAT KEEPS THE FIX HONEST. The two descriptions here differ by a
    // NO-BREAK SPACE the author typed, which is a different sentence by the byte-identity floor this
    // whole file compares on — "trust is binary, never a sliding scale". Removing the renderer's own
    // paragraph framing must never become a whitespace or markup normaliser, so this must go on
    // following: there is no contradiction here to refuse.
    // SPELT OUT AS A CODE POINT ON PURPOSE: a literal no-break space in this source would be
    // invisible to the next reader, and the whole point of the fixture is that one byte differs.
    val noBreakSpace        = 0x00a0.toChar
    val authorsNoBreakSpace = s"A durable${noBreakSpace}log."
    val strandedHeading =
      threeField(key("h1", "kafka", "definition"), "Kafka", "Definition", s"<p>$authorsNoBreakSpace</p>", "Kafka")
    val relabelled =
      threeField(key("h1", "streaming", "definition"), "Streaming", "Definition", s"<p>$authorsNoBreakSpace</p>", "Kafka")
    val tableTwin =
      threeField(key("t1", "stores", "nats", "definition"), "NATS", "Definition", durableLogFromATable, "Stores")
    surveyDeclaring(
      Vector(observed(strandedHeading, 1, Vector.empty)),
      Vector(sourced(relabelled, where)),
      Map(noteIdOf("h1") -> Vector(Vector("streaming"), Vector("streaming", "definition"))),
      Vector(observed(tableTwin, 101, Vector.empty)),
    ) match
      case Vector(c: MoveFinding.Corroborated) => assertEquals(c.candidate, relabelled.key)
      case other =>
        fail(s"a description the author wrote differently answers a different question: $other")
  }

  test("a collection declaring the old subject answers even when the census could NOT be taken") {
    // "I could not look" may never outrank a positive declaration. With the census silenced this
    // fixture used to become `RelabelUnvouched` on the honesty ground — and that ground does not
    // apply here, because the survival is ESTABLISHED rather than inferred from an absence: a
    // census that could not be taken cannot unestablish a card the collection is holding.
    val now = definitionUnder("RabbitMQ", "n1", "top")
    surveyBlind(
      Vector(observed(definitionUnderKafka, 1, Vector.empty)),
      Vector(sourced(now, where)),
      Vector(observed(costUnderKafkaElsewhere, 101, Vector.empty)),
    ) match
      case Vector(r: MoveFinding.Reparented) =>
        assertEquals(r.survival, SubjectSurvival.StillInTheCollection(Vector("top", "kafka"), costUnderKafkaElsewhere.key))
      case other =>
        fail(s"an unreadable vault does not unestablish what the collection holds: $other")
  }

  test("the card's OWN NOTE outranks the collection, so the report names what a reader will find") {
    // WHICH WITNESS IS NAMED IS NOT ARBITRARY, and this is the pin on the order. Where the concept
    // is still a node of the stranded card's own note, that is the most direct thing to say and the
    // one a reader can verify by opening the file — so it is named even though a live card elsewhere
    // would have answered too. Deck scenario S24's transcript line depends on this staying true.
    val definitionNow = definitionUnder("NATS", "n1", "top")
    surveyDeclaring(
      Vector(observed(definitionUnderKafka, 1, Vector.empty)),
      Vector(sourced(definitionNow, where)),
      Map(noteIdOf("n1") -> (treeWith("top", "nats") :+ Vector("top", "kafka"))),
      Vector(observed(costUnderKafkaElsewhere, 101, Vector.empty)),
    ) match
      case Vector(r: MoveFinding.Reparented) =>
        assertEquals(r.survival, SubjectSurvival.StillInTheNote(Vector("top", "kafka")))
      case other => fail(s"the census's own answer must be the one reported: $other")
  }

  test("a note handed in as BOTH stranded and live is refused outright") {
    // THE CONTRACT THAT KEEPS THE WITNESS FROM EATING ITSELF. A note being orphaned may not declare
    // that the subject it is being orphaned FROM goes on existing — and the damage would not be
    // local: a renamed concept's other descriptors are stranded by the very same rename, so each
    // would vouch for the old name and no rename would ever follow again. `plan/Planner.scala`
    // splits the collection on one predicate so the two cannot overlap; this refuses rather than
    // trusting that sentence to stay true.
    val card = observed(definitionUnderKafka, 1, Vector.empty)
    val thrown = intercept[RuntimeException] {
      surveyDeclaring(
        Vector(card),
        Vector(sourced(definitionUnder("NATS", "n1", "top"), where)),
        Map(noteIdOf("n1") -> treeWith("top", "nats")),
        Vector(card),
      )
    }
    assert(
      thrown.getMessage.contains("1"),
      s"the refusal must name the note that arrived on both sides: ${thrown.getMessage}",
    )
  }

  // ============== NO VOUCHER, NO EDIT: WHAT LICENSES REATTACHING AN ORPHAN AT ALL ====

  /** RESOLVED 2026-09-13 (`docs/design/IDENTITY-DECISION-SHEET.md`, "no voucher, no edit"), and
    * entailed by the standing rulings rather than newly ruled.
    *
    * THE SHAPE THAT FORCED IT, which the sheet had carried as an open case. `add.md` holds
    * `# Nature #flashcard/1way` over "a binary operation"; that section is deleted in one sync, and a
    * byte-identical one appears in `multiply.md` in a LATER sync. Each is unique within its own
    * sync's delta, so the mutual-uniqueness guard passes and addition's review history was reattached
    * onto multiplication's card. A false move, and Marc on the story: "story 1 is about a 1way
    * card... I don't see what we could even ask".
    *
    * WHAT MAY REATTACH AN ORPHAN, stated as three vouchers, any one of which suffices:
    *
    *   - THE DECLARATION — `/2way`, `/3way` and every three-field way that asks for concept recall.
    *     The author has said the content identifies what it is about, so a content match IS
    *     same-card evidence.
    *   - THE LOCATION — the place agreed: same note, same chain above the card's own name. Then the
    *     location identifies it whatever its content declares, which is Decision 5's shape.
    *   - THE SINGLE EDIT — the note was stranded by THIS run, so the disappearance and the
    *     appearance are one edit. That signature is what makes a verbatim cross-note move a move
    *     (deck S05) rather than a coincidence.
    *
    * A `/1way` card parked by an earlier run and claiming a section in a different note has none of
    * the three: its content identifies nothing by declaration, its location changed, and the two
    * events are in different syncs. Nothing vouches, so nothing is edited.
    *
    * WHY EVERY PIN BELOW IS AS LOAD-BEARING AS THE RED ONE. Each names a voucher that must go on
    * working, and the failure they guard against is silent in the expensive direction: a reattachment
    * refused is a review history stranded on a suspended note, which is the certain loss the ruling
    * of 2026-09-05 weighs against a bounded, visible, wrong reassignment.
    */
  val oneWayInAdd: CardSpec =
    twoField(key("add", "nature"), "Nature", "A binary operation.", "Add")

  /** The same note, already flagged `orphaned::` by an earlier run — which is the only way the
    * survey can tell "vanished and reappeared in one sync" from "parked, then something similar
    * turned up later". `plan/Planner.scala` surveys both populations deliberately.
    */
  def parked(spec: CardSpec, id: Long): ObservedCard =
    observed(spec, id, Vector(OwnedTag.orphaned(spec.key).value))

  test("a 1way orphan parked by an EARLIER run may not claim a section in another note") {
    // THE add/multiply CASE. Nothing vouches: the declaration says this body identifies nothing, the
    // note changed, and the parking says the two events are in different syncs.
    val inMultiply = twoField(key("multiply", "nature"), "Nature", "A binary operation.", "Multiply")
    assertEquals(
      oneWayInAdd.noteTypeName,
      Marker.NoteTypes.Basic,
      "the fixture must be a 1way card for this test to have a weapon",
    )
    surveyOf(Vector(parked(oneWayInAdd, 1)), Vector(sourced(inMultiply, where))) match
      case Vector(MoveFinding.NoVoucher(stranded, _, candidate, _, _)) =>
        assertEquals(stranded, oneWayInAdd.key)
        assertEquals(candidate, inMultiply.key)
      case other =>
        fail(s"a 1way orphan's cross-sync claim on another note must not be applied: $other")
  }

  test("the same claim made INSIDE one sync still follows — the single edit vouches") {
    // Deck S05's settled behaviour, and the reason the rule reads the orphan FLAG rather than the
    // keys alone: a note this run stranded vanished and reappeared in one edit, which is what a move
    // looks like. The fixture differs from the test above in nothing but the tag.
    val inMultiply = twoField(key("multiply", "nature"), "Nature", "A binary operation.", "Multiply")
    surveyOf(Vector(observed(oneWayInAdd, 1, Vector.empty)), Vector(sourced(inMultiply, where))) match
      case Vector(c: MoveFinding.Corroborated) =>
        assertEquals(c.candidate, inMultiply.key)
        assertEquals(c.agreement, Agreement.Total)
      case other => fail(s"a verbatim cross-note move inside one sync must follow: $other")
  }

  test("a 1way orphan recreated in the SAME NOTE still follows — the location vouches") {
    // Decision 5's shape, in the kind the deck does not cover: deck S14's own fixture is `2way`, so
    // the deck pins the DECLARATION route and this pins the LOCATION route. The card's own name
    // changed and the note did not, so where it lives still identifies it whatever its content
    // declares.
    //
    // THE LOCATION IS READ AT NOTE GRANULARITY, which is the sheet's own axis ("cross-sync AND
    // cross-note"). A sharper reading is available and deliberately not taken — a parked 1way
    // re-parented under a different chain of the same note also has a changed location and goes on
    // being reattached. That case is pinned further down by "a note ALREADY parked as an orphan is
    // unflagged, unsuspended and reassigned", which has asserted it since the retroactive half of
    // Decision 5 was ratified; nothing on the sheet refuses it.
    val reworded = twoField(key("add", "the nature of it"), "The nature of it", "A binary operation.", "Add")
    surveyOf(Vector(parked(oneWayInAdd, 1)), Vector(sourced(reworded, where))) match
      case Vector(c: MoveFinding.Corroborated) =>
        assertEquals(c.agreement, Agreement.PlaceAndSubstance)
      case other => fail(s"a parked 1way recreated in place must follow: $other")
  }

  test("a 2way orphan's cross-sync claim on another note DOES follow — the declaration vouches") {
    // The same two syncs and the same two notes as the refused case, with `#flashcard/2way` in place
    // of `/1way`. The author has declared that this body identifies its heading, so a content match
    // is same-card evidence and the parking changes nothing about that.
    val was = twoFieldBothWays(key("add", "nature"), "Nature", "A binary operation.", "Add")
    val now = twoFieldBothWays(key("multiply", "nature"), "Nature", "A binary operation.", "Multiply")
    assertEquals(was.noteTypeName, Marker.NoteTypes.BasicAndReversed, "the fixture must be a 2way card")
    surveyOf(Vector(parked(was, 1)), Vector(sourced(now, where))) match
      case Vector(c: MoveFinding.Corroborated) => assertEquals(c.candidate, now.key)
      case other => fail(s"a 2way declaration vouches across syncs and notes: $other")
  }

  test("a cdd/3way orphan's cross-sync claim on another note follows too") {
    // The three-field half of the declaration route. `cdd/3way` asks for concept recall, so the
    // description identifies its concept and the rule has no quarrel with it.
    val was = threeField(key("n1", "top", "kafka", "cost"), "Kafka", "Cost", "Operational complexity.", "Top")
    val now = threeField(key("n2", "top", "kafka", "cost"), "Kafka", "Cost", "Operational complexity.", "Top")
    surveyOf(Vector(parked(was, 1)), Vector(sourced(now, where))) match
      case Vector(c: MoveFinding.Corroborated) => assertEquals(c.candidate, now.key)
      case other => fail(s"a concept-recall declaration vouches: $other")
  }

  test("a cdd/1way orphan parked earlier may not claim a section in another note either") {
    // THE THREE-FIELD HALF OF THE REFUSAL, and the reason the declaration is read per kind rather
    // than per note type: this card and the one above share the `Obsidian Concept-Descriptor` note
    // type and differ in one Setting field, which is where the three-field family writes its
    // declaration.
    val was =
      threeFieldOneWay(key("n1", "top", "kafka", "cost"), "Kafka", "Cost", "Operational complexity.", "Top")
    val now =
      threeFieldOneWay(key("n2", "top", "kafka", "cost"), "Kafka", "Cost", "Operational complexity.", "Top")
    assertEquals(was.noteTypeName, now.noteTypeName, "the fixture must share the note type to be a test")
    surveyOf(Vector(parked(was, 1)), Vector(sourced(now, where))) match
      case Vector(_: MoveFinding.NoVoucher) => ()
      case other => fail(s"a cdd/1way declaration vouches for nothing: $other")
  }

  test("a CLOZE orphan's cross-sync claim is unchanged, because no declaration speaks to it") {
    // THE THIRD STATE, AND THE ONE THAT MUST NOT BE GUESSED. A cloze card carries no direction
    // declaration at all — there is no `cloze/1way` — so the voucher table of 2026-09-13 has nothing
    // to read about it, and a rule that treated silence as "identifies nothing" would refuse
    // reattachments no ruling has refused. Behaviour here is therefore deliberately unchanged, and
    // whether a cloze passage should vouch for itself is an open question rather than this rule's.
    val was = cloze(key("n1", "layers"), "The ==<<epidermis>>== is outermost.", "One")
    val now = cloze(key("n2", "layers"), "The ==<<epidermis>>== is outermost.", "One")
    surveyOf(Vector(parked(was, 1)), Vector(sourced(now, where))) match
      case Vector(c: MoveFinding.Corroborated) => assertEquals(c.candidate, now.key)
      case other => fail(s"an unstated declaration must change nothing: $other")
  }

  test("every note type this tool declares says what its substance declares, or the rule is partial") {
    // The twin of the roles-table guard above: a sixth note type must state whether its content
    // identifies what it is about, because the voucher rule reads that per kind. Asking is the
    // assertion — the reading raises on a note type it has no answer for.
    Marker.NoteTypes.All.foreach { noteType =>
      SubstanceDeclaration.of(noteType, Map(Marker.ValueOnlyField -> ""))
    }
  }

  test("a plain heading's own rewording is not a subject change, however deep its path") {
    // THE SECOND GUARD ON THE GATE'S REACH. A two-field card shows ONE name field, so its window
    // is one segment and the window-minus-its-last is empty on both sides. Deck S01 and S09 depend
    // on this: a reworded plain heading follows, and so does one reworded and re-parented at once.
    val was = twoField(key("n1", "notes", "coupling"), "Coupling", "Two things move together.", "Notes")
    val now =
      twoField(key("n1", "notes", "temporal coupling"), "Temporal coupling", "Two things move together.", "Notes")
    surveyOver(
      Vector(observed(was, 1, Vector.empty)),
      Vector(sourced(now, where)),
      Map(noteIdOf("n1") -> Vector(Vector("notes"), Vector("notes", "temporal coupling"))),
    ) match
      case Vector(c: MoveFinding.Corroborated) =>
        assertEquals(c.agreement, Agreement.PlaceAndSubstance)
      case other => fail(s"a plain heading has no subject segment to move: $other")
  }

  /** A card anchored at one `^blockid`-marked paragraph. The anchor is the WHOLE of its path, which
    * is what makes the test below about the anchor and nothing else.
    */
  def blockKey(id: String, anchor: String): CardKey =
    CardKey(
      NoteId.fromFrontmatter(id).toOption.get,
      CardPath.Block(BlockAnchor.read(anchor).getOrElse(fail(s"unusable test anchor '$anchor'"))),
    )

  test("a cloze block's ^anchor rewritten IN PLACE over identical text follows — the anchor is a name") {
    // RULED 2026-09-13 (`docs/design/IDENTITY-DECISION-SHEET.md`, "the undeclared kinds take the 1way
    // treatment; a cloze anchor is a name"). Marc's correction: the `^anchor` is NOT an identity
    // contract — "not unique, arbitrary, manual, re-writable" — it is part of the block's ADDRESS. So
    // rewriting it over a byte-identical block, in place, is Decision 1 one level down: the block's
    // name changed, its substance and its place agreed, and the same card keeps its history.
    //
    // THIS PINS BEHAVIOUR THAT WAS ALREADY CORRECT rather than changing any. It earns a test because
    // nothing else in this suite drives a block card through the gates, and because the outcome rests
    // on two decisions taken for other reasons: a cloze note type declares no name field, so the
    // subject gate cannot fire for it, and its `SubstanceDeclaration` is `Unstated`, so the voucher
    // rule refuses nothing. Either of those moving would move this silently.
    //
    // THE GRADE READS THE ANCHOR AS PLACE, not as name, and that is a fact about the CARD rather than
    // about the markdown: a cloze card shows no name field at all, so `Agreement`'s name half is
    // vacuous for it — see that type's own note on `NameAndSubstance`. The ruling's "the block's name
    // changed" is about the author's text; the card's fields never carried it.
    val was = cloze(blockKey("n1", "forearm"), "The ==<<ulna>>== is medial.", "Bones")
    val now = cloze(blockKey("n1", "ulna-note"), "The ==<<ulna>>== is medial.", "Bones")
    assertEquals(
      MoveEvidence.nameDepthOf(was.noteTypeName),
      0,
      "a cloze card must show no name field for this test to be about what it says it is",
    )
    surveyOf(Vector(observed(was, 1, Vector.empty)), Vector(sourced(now, where))) match
      case Vector(c: MoveFinding.Corroborated) =>
        assertEquals(c.candidate, now.key)
        assertEquals(c.agreement, Agreement.NameAndSubstance)
      case other => fail(s"an anchor rewritten in place must not orphan the card: $other")
  }

  test("a relation card's predicate rename is not a subject change either") {
    // A relation card IS a concept-descriptor card — subject is the concept, predicate is the
    // descriptor — but its path is a single frontmatter property, so its two declared name fields
    // read a window one segment long. Deck S65 follows this, and the gate must not fire on it.
    val was = threeField(propertyKey("n1", "special-case-of"), "Function Space", "Special-Case-Of", "An exponential object.", "")
    val now = threeField(propertyKey("n1", "instance-of"), "Function Space", "Instance-Of", "An exponential object.", "")
    surveyOver(Vector(observed(was, 1, Vector.empty)), Vector(sourced(now, where)), Map.empty) match
      case Vector(c: MoveFinding.Corroborated) =>
        assertEquals(c.agreement, Agreement.PlaceAndSubstance)
      case other => fail(s"a property has no subject segment above its name: $other")
  }

  test("an unactionable subject-gate finding still occupies mutual uniqueness") {
    // The gate runs AFTER the uniqueness rule, and it must stay there. If a re-parent were decided
    // first and then excused from the claimant bookkeeping, the surviving claimant would be
    // corroborated alone — a pairing asserted uniquely because its rival had been filed elsewhere.
    val alsoClaiming =
      threeField(key("n1", "top", "zookeeper", "definition"), "ZooKeeper", "Definition", "A durable log.", "Top")
    val now = definitionUnder("NATS", "n1", "top")
    val findings = surveyOver(
      Vector(observed(definitionUnderKafka, 1, Vector.empty), observed(alsoClaiming, 2, Vector.empty)),
      Vector(sourced(now, where)),
      Map(noteIdOf("n1") -> (treeWith("top", "nats") :+ Vector("top", "kafka"))),
    )
    assertEquals(findings.size, 2)
    assert(
      findings.forall {
        case _: MoveFinding.Contested => true
        case _                        => false
      },
      s"two notes claiming one card establish nothing, gate or no gate: ${findings.map(_.describe)}",
    )
  }

  test("every stranded note produces exactly one finding, whatever the evidence came to") {
    // CONSERVATION. A note that produced no finding is a note the reader never hears about,
    // which is the silent-nothing failure this project is built to prevent.
    val notes = Vector(
      observed(beforeMove, 1, Vector.empty),
      observed(twoField(key("n9", "gone"), "Gone", "Deleted outright.", "N9"), 2, Vector.empty),
      observed(twoField(key("n8", "a", "one"), "One", "Shared body.", "A"), 3, Vector.empty),
      observed(twoField(key("n8", "b", "two"), "Two", "Shared body.", "B"), 4, Vector.empty),
    )
    val specs = Vector(sourced(afterMove, where), sourced(twoField(key("n8", "c", "three"), "Three", "Shared body.", "C"), where))
    val findings = surveyOf(notes, specs)
    assertEquals(findings.map(_.strandedNote).toSet, notes.map(_.note.id).toSet)
    assertEquals(findings.size, notes.size)
  }

  test("the survey is stable under the order its inputs arrive in") {
    val notes = Vector(
      observed(beforeMove, 1, Vector.empty),
      observed(twoField(key("n9", "gone"), "Gone", "Deleted outright.", "N9"), 2, Vector.empty),
    )
    val specs = Vector(sourced(afterMove, where), sourced(twoField(key("n0", "new"), "New", "Brand new.", "N0"), where))
    assertEquals(
      surveyOf(notes, specs).map(_.describe),
      surveyOf(notes.reverse, specs.reverse).map(_.describe),
    )
  }

  // ================================================================ THE ACTION ====

  test("a reassignment writes the vault's whole field set, the new identity included") {
    val found  = corroboration(Vector(observed(beforeMove, 1, Vector.empty)), Vector(sourced(afterMove, where)))
    val action = found.reassignment(sourced(afterMove, where), Vector.empty, Vector.empty, None)
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
    val found = corroboration(Vector(observed(beforeMove, 1, Vector.empty)), Vector(sourced(afterMove, where)))
    val other = sourced(twoField(key("n0", "somewhere", "else"), "Else", "Other body.", "N0"), where)
    intercept[RuntimeException](found.reassignment(other, Vector.empty, Vector.empty, None))
  }

  test("the action a reassignment carries is about the key it moves TO") {
    val found  = corroboration(Vector(observed(beforeMove, 1, Vector.empty)), Vector(sourced(afterMove, where)))
    val action = found.reassignment(sourced(afterMove, where), Vector.empty, Vector.empty, None)
    assertEquals((action: SyncAction).cardKey, movedKey)
  }

  test("a reassignment is attempted under every retype policy") {
    val found  = corroboration(Vector(observed(beforeMove, 1, Vector.empty)), Vector(sourced(afterMove, where)))
    val action: SyncAction = found.reassignment(sourced(afterMove, where), Vector.empty, Vector.empty, None)
    assertEquals(action.dispositionUnder(RetypePolicy.Defer), Disposition.Attempt)
    assertEquals(action.dispositionUnder(RetypePolicy.Apply), Disposition.Attempt)
  }

  // ================================================= THE PLANNER, END TO END ====

  def scanOf(specs: SourcedSpec*): VaultScan = VaultScan.from(specs.toVector, Vector.empty)

  def planOf(scan: VaultScan, anki: InMemoryAnki): Plan =
    val state = Observer.observe(anki).fold(e => fail(s"observe failed: $e"), identity)
    Planner
      .plan(scan, state, _ => deck, Planner.newNoteFor, HandBuiltCensus.of(scan))
      .fold(errs => fail(s"unexpected plan errors: ${errs.map(_.describe)}"), identity)

  def runPlan(p: Plan, anki: InMemoryAnki): Unit =
    val report = Executor
      .run(p, anki, RetypePolicy.Apply, Set.empty, RecordedNowhere.ledger)
      .fold(e => fail(s"execution aborted entirely: $e"), identity)
    assert(report.failures.isEmpty, s"unexpected execution failures: ${report.failures}")

  /** Sync the BEFORE vault into an empty collection, give the resulting card a review history,
    * and hand back the collection with the note id and card ids that must survive the move.
    */
  def collectionHolding(spec: CardSpec, reviews: Int): (InMemoryAnki, AnkiNoteId, Vector[AnkiCardId]) =
    val anki = InMemoryAnki()
    runPlan(planOf(scanOf(sourced(spec, where)), anki), anki)
    val noteId = anki.ownedNotes.fold(e => fail(s"$e"), identity) match
      case Vector(only) => only
      case several      => fail(s"the fixture should have created exactly one note: $several")
    val cards = anki.cardsOf(Vector(noteId)).fold(e => fail(s"$e"), identity)
    cards.foreach(anki.recordReviews(_, reviews))
    (anki, noteId, cards)

  test("a moved heading plans a reassignment instead of a create, and no orphan flag at all") {
    val (anki, noteId, _) = collectionHolding(beforeMove, reviews = 32)

    val plan = planOf(scanOf(sourced(afterMove, where)), anki)

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

    runPlan(planOf(scanOf(sourced(afterMove, where)), anki), anki)

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
    runPlan(planOf(scanOf(sourced(afterMove, where)), anki), anki)

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
    runPlan(planOf(scanOf(sourced(afterMove, where)), anki), anki)
    assertEquals(planOf(scanOf(sourced(afterMove, where)), anki).actions, Vector.empty)
  }

  test("a note ALREADY parked as an orphan is unflagged, unsuspended and reassigned, in that order") {
    // The retroactive half — `EVOLVABILITY.md` §4A's "works retroactively, which no tag-based
    // scheme can". The note was flagged and suspended by an earlier run, and its heading turns
    // out to be alive somewhere else.
    val (anki, noteId, cards) = collectionHolding(beforeMove, reviews = 7)

    // An earlier run over a vault that had lost the heading entirely: flag and suspend.
    runPlan(planOf(scanOf(), anki), anki)
    assert(cards.forall(anki.isSuspended), "the fixture did not actually park the note")

    val plan = planOf(scanOf(sourced(afterMove, where)), anki)
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
    assertEquals(planOf(scanOf(sourced(afterMove, where)), anki).actions, Vector.empty)
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

    val plan = planOf(scanOf(sourced(afterMove, where)), anki)
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
    assertEquals(planOf(scanOf(sourced(afterMove, where)), anki).actions, Vector.empty)
  }

  test("every corroborated finding on a plan has the reassignment that discharges it") {
    // THE INVARIANT `Report` READS. It prints a corroborated finding under "reassigned, keeping
    // its review history", which is a lie the moment a corroborated finding can reach a plan
    // without its action. Pinned here rather than defended in the report, because the planner is
    // where the two are decided together.
    val (anki, _, _) = collectionHolding(beforeMove, reviews = 3)
    val plan = planOf(scanOf(sourced(afterMove, where)), anki)

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
    val plan = planOf(scanOf(sourced(alt1, where), sourced(alt2, where)), anki)

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
    val plan = planOf(scanOf(sourced(alt1, where), sourced(alt2, where)), anki)

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
      Vector(sourced(afterMove, where)),
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
    val plan  = planOf(scanOf(sourced(afterMove, where)), anki)
    val lines = obsidiananki.cli.Report.plan(plan, RetypePolicy.Apply).mkString("\n")

    assert(lines.contains("review history"), s"the report did not say what was preserved:\n$lines")
    assert(lines.contains(strandedKey.path.render), s"the report did not name the old card:\n$lines")
    assert(lines.contains(movedKey.path.render), s"the report did not name the new card:\n$lines")
    assert(
      lines.contains("System design › System design interview framework"),
      s"the report did not say what changed on the card:\n$lines",
    )
  }
