package obsidiananki.plan

import cats.data.NonEmptyVector
import obsidiananki.anki.*
import obsidiananki.model.*

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
      HeadingPath(
        NonEmptyVector.fromVectorUnsafe(
          segments.toVector.map(s => HeadingSegment.fromExtractedText(s).toOption.get)
        )
      ),
    )

  val deck: DeckPath = DeckPath(NonEmptyVector.of("Obsidian", "System-Design"))

  def body(s: String): Body = Body.fromExtracted(s).getOrElse(fail("empty test body"))

  def specOf(k: CardKey, back: String): SourcedSpec =
    SourcedSpec(
      CardSpec.TwoField(k, "front", body(back), TwoFieldDirections.Forward, "Coupling"),
      SourceRef("Note.md", 1, SourceKind.Heading),
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
    assertEquals(observed.unresolved.size, 1, s"got ${observed.unresolved.map(_.describe)}")
    assert(!observed.isFullyResolved)
  }

  test("a note with two identities is REPORTED, rather than one tag arbitrarily winning") {
    val observed = observe(noteWithTwoIdentities())
    assertEquals(observed.notes, Vector.empty, "one of two identity tags was picked")
    assertEquals(observed.unresolved.size, 1, s"got ${observed.unresolved.map(_.describe)}")
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
    val message = observe(noteWithUnreadableIdentity()).unresolved.head.describe
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
  }

  test("a healthy collection reports nothing unresolved") {
    val anki = InMemoryAnki()
    anki.addNote(newNoteOf(specOf(k1, "back"), deck, "aaaa")).fold(e => fail(s"$e"), identity)
    val observed = observe(anki)
    assertEquals(observed.unresolved, Vector.empty)
    assert(observed.isFullyResolved)
    assertEquals(observed.notes.size, 1)
  }
