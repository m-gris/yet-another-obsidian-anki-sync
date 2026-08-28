package obsidiananki.plan

import cats.data.NonEmptyVector
import obsidiananki.anki.*
import obsidiananki.model.*
import obsidiananki.plan.SectionChain.{NoRecall, NoSectionChain}

/** AN ANKI NOTE THIS TOOL FOUND AND COULD NOT PLACE.
  *
  * ==What was wrong==
  *
  * `Observer.observe` used to read a note's identity with
  * `.find(…).flatMap(TagCodec.decode(_).toOption)`. Both steps could drop the note in silence:
  * `.find` took an arbitrary tag when a note carried several, and `.toOption` deleted the
  * decoder's error outright. A dropped note is absent from `ObservedState` altogether, so it is
  * never updated, never flagged as gone, and never prunable — and the planner, seeing no note
  * for that key, CREATES A SECOND ONE. The note holding the review history goes quiet and
  * diverges, with nothing anywhere reporting it.
  *
  * `ObservedState.byKey`'s docstring already condemned exactly that outcome for two notes
  * claiming one identity. The rule was written down and broken eleven lines away.
  *
  * ==Why the tests below assert on a REFUSAL and never on which tag won==
  *
  * Picking a tag is the thing this must not do. A test asserting "the first tag wins" would pin
  * the behaviour being removed. It would also be untestable here: `InMemoryAnki` appends tags
  * while Anki canonicalises their order, so tag ORDER is not a property the fake can testify to.
  *
  * ==Reachability, established by reading every path that writes tags==
  *
  * Not reachable through this tool's own writes. Creation emits exactly one identity tag; an
  * update touches only `sha::`; and a note-type move rewrites the whole tag set, so it HEALS
  * this state rather than causing it. It is reachable by editing tags in Anki — which is why
  * [[InMemoryAnki.simulateUserTag]] exists — and the likely route is sympathetic rather than
  * careless: a heading is renamed, the tool orphans the old note and creates a historyless new
  * one, and the person pastes the new tag onto the old note to rescue their review history
  * without deleting the old tag.
  */
class UnplaceableNoteTest extends munit.FunSuite:

  type Result[A] = Either[AnkiError, A]

  def key(id: String, segments: String*): CardKey =
    CardKey(
      NoteId.fromFrontmatter(id).toOption.get,
      CardPath.Headings(HeadingPath(
        NonEmptyVector.fromVectorUnsafe(
          segments.toVector.map(s => HeadingSegment.fromExtractedText(s).toOption.get)
        )
      )),
    )

  val deck: DeckPath = DeckPath(NonEmptyVector.of("Obsidian", "System-Design"))

  def body(s: String): Body = Body.fromExtracted(s).getOrElse(fail("empty test body"))

  def specOf(k: CardKey, back: String): SourcedSpec =
    SourcedSpec(
      CardSpec.TwoField(k, "front", body(back), TwoFieldDirections.Forward, "Coupling"),
      SourceRef("Note.md", 1, SourceKind.Heading),
      NoSectionChain,
      NoRecall,
    )

  def newNoteOf(s: SourcedSpec, d: DeckPath, sha: String): NewNote =
    NewNote(
      noteType = s.spec.noteTypeName,
      deck = d,
      fields = s.spec.fields,
      tags = NonEmptyVector.of(TagCodec.encode(s.key), OwnedTag.sha(sha)),
    )

  val k1: CardKey = key("n1", "Coupling", "Temporal coupling")
  val k2: CardKey = key("n1", "Coupling", "Afferent coupling")

  /** The finished message for the first unplaceable note, composed the way a run composes it:
    * observation reports the FACT, the planner turns it into ADVICE by consulting the vault.
    */
  // A fixture default meaning 'no candidates offered'. The tests that check a suggestion pass
  // specs and assert the suggestion names one; the rest assert that none is offered.
  // ast-grep-ignore: default-parameter
  def messageFor(anki: InMemoryAnki, specs: Vector[SourcedSpec] = Vector.empty): String =
    Planner.identityErrorFor(observe(anki).unresolved.head, specs).describe

  def observe(anki: InMemoryAnki): ObservedState =
    Observer.observe[Result](anki).fold(e => fail(s"observe failed: $e"), identity)

  def planOver(anki: InMemoryAnki, specs: Vector[SourcedSpec]): Either[Vector[PlanError], Plan] =
    Planner.plan(
      VaultScan.from(specs, Vector.empty),
      observe(anki),
      _ => deck,
      (s, d, sha) => newNoteOf(s, d, sha),
    )

  /** One note synced normally, then given a SECOND identity tag by hand — the manual-rebind
    * story from the class docstring.
    */
  def noteWithTwoIdentities(): InMemoryAnki =
    val anki = InMemoryAnki()
    val id   = anki.addNote(newNoteOf(specOf(k1, "back"), deck, "aaaa")).fold(e => fail(s"$e"), identity)
    anki.simulateUserTag(id, TagCodec.encode(k2).value)
    anki

  /** One note whose only identity tag cannot be decoded — `src::n1` has two components where
    * the codec requires three, so it is found by the prefix search and refused by the decoder.
    */
  def noteWithUnreadableIdentity(): InMemoryAnki =
    val anki = InMemoryAnki()
    anki
      .addNote(
        NewNote(
          noteType = specOf(k1, "back").spec.noteTypeName,
          deck = deck,
          fields = specOf(k1, "back").spec.fields,
          tags = NonEmptyVector.of(OwnedTag.unsafeFromString("src::n1"), OwnedTag.sha("aaaa")),
        )
      )
      .fold(e => fail(s"$e"), identity)
    anki

  // ═════════════════════════════════════════════════════ conservation ══════

  /** THE PROPERTY THAT CATCHES THE WHOLE CLASS, not just the two known causes.
    *
    * Any future reason a note falls out between "found by the query" and "handed to the planner"
    * breaks this, whether or not anyone anticipated it.
    */
  test("every note the identity search finds is either resolved or reported — never neither") {
    Vector(
      "two identity tags" -> noteWithTwoIdentities(),
      "an unreadable tag" -> noteWithUnreadableIdentity(),
    ).foreach { (label, anki) =>
      val found    = anki.findNotesByTagPrefix("src::").fold(e => fail(s"$e"), identity)
      val observed = observe(anki)
      assertEquals(
        observed.notes.size + observed.unresolved.size,
        found.size,
        s"$label: ${found.size} notes found, but ${observed.notes.size} resolved and " +
          s"${observed.unresolved.size} reported — the difference vanished",
      )
    }
  }

  test("a note with an unreadable identity is REPORTED, where it used to be discarded") {
    val observed = observe(noteWithUnreadableIdentity())
    assertEquals(observed.notes, Vector.empty, "an unreadable note was resolved to a card anyway")
    assertEquals(observed.unresolved.size, 1, s"got ${observed.unresolved.map(_.problem)}")
    assert(!observed.isFullyResolved)
  }

  test("a note with two identities is REPORTED, rather than one tag arbitrarily winning") {
    val observed = observe(noteWithTwoIdentities())
    assertEquals(observed.notes, Vector.empty, "one of two identity tags was picked")
    assertEquals(observed.unresolved.size, 1, s"got ${observed.unresolved.map(_.problem)}")
  }

  // ══════════════════════════════ recovering what the note used to be ══════

  /** THE RECOVERY THAT IS NOT A GUESS.
    *
    * `sha::` is a hash of the note type and every field value, written by the last successful
    * sync. A broken note whose hash still matches a card the vault produces today is identified
    * by sixty-four bits agreeing — the same assumption "nothing to do" already rests on for
    * every note in the collection.
    */
  test("a note whose content still matches a vault card is identified by its content hash") {
    val spec = specOf(k1, "back")
    val anki = InMemoryAnki()
    anki
      .addNote(
        NewNote(
          noteType = spec.spec.noteTypeName,
          deck = deck,
          fields = spec.spec.fields,
          // The identity is broken; the content hash is intact and correct.
          tags = NonEmptyVector.of(
            OwnedTag.unsafeFromString("src::n1"),
            OwnedTag.sha(Planner.contentHash(spec.spec)),
          ),
        )
      )
      .fold(e => fail(s"$e"), identity)

    val error = Planner.identityErrorFor(observe(anki).unresolved.head, Vector(spec))
    error match
      case PlanError.UnreadableIdentityInAnki(_, _, _, looksLike) =>
        assertEquals(looksLike, Some(k1), "the content hash did not identify the card")
      case other => fail(s"wrong error: $other")

    // AND THE MESSAGE HANDS OVER THE FINISHED TAG. Nobody can type one of these by hand — the
    // encoding escapes spaces, slashes, colons and Anki's wildcards — so a suggestion that named
    // the card without printing the tag would still leave the person stuck.
    val message = error.describe
    assert(message.contains(TagCodec.encode(k1).value), s"no tag to copy in: $message")

    // THE WORDING MUST STAY TENTATIVE. The tool did not make this change and must not read as
    // though it had, nor as an instruction: the evidence is strong, and the decision is still
    // the reader's because acting on it moves review history between cards.
    assert(
      message.contains("most likely") && message.contains("if you agree"),
      s"reads as a statement of fact rather than a suggestion: $message",
    )
  }

  /** SILENT WHEN IT DOES NOT KNOW. The body was edited after the tag broke, so the recorded hash
    * matches nothing — and a report that guessed anyway would be worse than one that did not,
    * because acting on it moves review history onto the wrong card.
    */
  test("a note whose content has since changed gets no suggestion rather than a wrong one") {
    val anki = InMemoryAnki()
    anki
      .addNote(
        NewNote(
          noteType = specOf(k1, "back").spec.noteTypeName,
          deck = deck,
          fields = specOf(k1, "back").spec.fields,
          tags = NonEmptyVector.of(
            OwnedTag.unsafeFromString("src::n1"),
            OwnedTag.sha("0000000000000000"),
          ),
        )
      )
      .fold(e => fail(s"$e"), identity)

    val error = Planner.identityErrorFor(observe(anki).unresolved.head, Vector(specOf(k1, "edited")))
    error match
      case PlanError.UnreadableIdentityInAnki(_, _, _, looksLike) => assertEquals(looksLike, None)
      case other                                                  => fail(s"wrong error: $other")
    assert(!error.describe.toLowerCase.contains("looks like"), error.describe)
  }

  /** For a note carrying SEVERAL identities the vault answers directly, and better than a hash
    * could: of the keys claimed, exactly one still exists.
    */
  test("of two identity tags, the one the vault still has a card for is named") {
    val anki = noteWithTwoIdentities()
    // Only k2 remains in the vault, so the note is most likely k2's.
    val error = Planner.identityErrorFor(observe(anki).unresolved.head, Vector(specOf(k2, "back")))
    error match
      case PlanError.AmbiguousIdentityInAnki(_, _, looksLike) => assertEquals(looksLike, Some(k2))
      case other                                              => fail(s"wrong error: $other")
  }

  test("when BOTH claimed keys still exist, nothing is suggested") {
    val anki  = noteWithTwoIdentities()
    val error = Planner.identityErrorFor(
      observe(anki).unresolved.head,
      Vector(specOf(k1, "back"), specOf(k2, "back")),
    )
    error match
      case PlanError.AmbiguousIdentityInAnki(_, _, looksLike) =>
        assertEquals(looksLike, None, "picked one of two live candidates")
      case other => fail(s"wrong error: $other")
  }

  /** Two cards hashing alike is the one way the hash could mislead, so it must decline rather
    * than take the first. Same posture as `recordedSha` refusing to choose between two hashes.
    */
  test("when two vault cards share a content hash, nothing is suggested") {
    val spec     = specOf(k1, "back")
    val twin     = specOf(k2, "back") // identical fields, so an identical hash
    assertEquals(Planner.contentHash(spec.spec), Planner.contentHash(twin.spec), "test premise broken")

    val anki = InMemoryAnki()
    anki
      .addNote(
        NewNote(
          noteType = spec.spec.noteTypeName,
          deck = deck,
          fields = spec.spec.fields,
          tags = NonEmptyVector.of(
            OwnedTag.unsafeFromString("src::n1"),
            OwnedTag.sha(Planner.contentHash(spec.spec)),
          ),
        )
      )
      .fold(e => fail(s"$e"), identity)

    Planner.identityErrorFor(observe(anki).unresolved.head, Vector(spec, twin)) match
      case PlanError.UnreadableIdentityInAnki(_, _, _, looksLike) =>
        assertEquals(looksLike, None, "picked one of two cards with the same content")
      case other => fail(s"wrong error: $other")
  }

  // ══════════════════════════════════════════ what the planner does ══════

  /** THE DAMAGE, ASSERTED DIRECTLY. This is the test that fails against the old code for the
    * right reason: it used to plan a `Create` for a key a live note was already holding.
    */
  test("no second note is planned for a key an unplaceable note may already hold") {
    val result = planOver(noteWithUnreadableIdentity(), Vector(specOf(k1, "back")))
    result match
      case Right(plan) =>
        fail(s"planned ${plan.actions.map(_.getClass.getSimpleName).mkString(", ")} beside a note that could not be placed")
      case Left(errors) =>
        assert(
          errors.exists(_.isInstanceOf[PlanError.UnreadableIdentityInAnki]),
          s"refused, but not for the right reason: ${errors.map(_.describe)}",
        )
  }

  test("an ambiguous note stops the run and names the note id") {
    planOver(noteWithTwoIdentities(), Vector(specOf(k1, "back"), specOf(k2, "back"))) match
      case Right(_) => fail("planned past a note carrying two identities")
      case Left(errors) =>
        val ambiguous = errors.collect { case e: PlanError.AmbiguousIdentityInAnki => e }
        assertEquals(ambiguous.size, 1, s"got ${errors.map(_.describe)}")
        assertEquals(ambiguous.head.tags.length, 2)
  }

  /** The message has to name the note and say what to do, because the remedy is entirely
    * manual — this tool cannot repair a tag it cannot read.
    */
  test("the report names the note id, the offending tag, and the remedy") {
    val message = messageFor(noteWithUnreadableIdentity())
    assert(message.contains("src::n1"), message)
    assert(message.toLowerCase.contains("cannot read"), message)
    assert(message.toLowerCase.contains("second note"), message)
  }

  /** A CAPITALISED tag must be reported, not passed over.
    *
    * Anki cannot tell `SRC::x` from `src::x`, and `OwnedTag.isOwned` treats it as ours for that
    * reason. If the search here were case-SENSITIVE such a note would look like somebody else's
    * and vanish again — the same defect wearing different clothes.
    */
  test("a capitalised identity tag is reported, not treated as another tool's tag") {
    val anki = InMemoryAnki()
    anki
      .addNote(
        NewNote(
          noteType = specOf(k1, "back").spec.noteTypeName,
          deck = deck,
          fields = specOf(k1, "back").spec.fields,
          tags = NonEmptyVector.of(OwnedTag.unsafeFromString("SRC::n1::coupling"), OwnedTag.sha("aaaa")),
        )
      )
      .fold(e => fail(s"$e"), identity)

    val observed = observe(anki)
    assertEquals(observed.notes, Vector.empty, "a capitalised tag was decoded as an identity")
    assertEquals(observed.unresolved.size, 1, "a capitalised identity tag was silently ignored")

    // THE MESSAGE MUST NAME THE OFFENDING TAG, and this half of the assertion is the half with
    // teeth. Counting the reports is not enough: with a case-SENSITIVE search the tag is not
    // recognised at all, the note falls into the "found by the search but carries no such tag"
    // branch, and it is still reported — just with a reason that names nothing the person can
    // act on. Measured: mutating the search to be case-sensitive left this test green until
    // this line was added.
    val message = messageFor(anki)
    assert(
      message.contains("SRC::n1::coupling"),
      s"the report does not name the tag the person has to fix: $message",
    )
  }

  test("a healthy collection reports nothing unresolved") {
    val anki = InMemoryAnki()
    anki.addNote(newNoteOf(specOf(k1, "back"), deck, "aaaa")).fold(e => fail(s"$e"), identity)
    val observed = observe(anki)
    assertEquals(observed.unresolved, Vector.empty)
    assert(observed.isFullyResolved)
    assertEquals(observed.notes.size, 1)
  }
