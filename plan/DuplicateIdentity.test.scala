package obsidiananki.plan

import cats.data.NonEmptyVector
import obsidiananki.anki.*
import obsidiananki.model.*
import obsidiananki.plan.SectionChain.{NoRecall, NoSectionChain}

/** TWO ANKI NOTES CLAIMING ONE CARD IDENTITY.
  *
  * The markdown side of this has been fatal from the start: two headings deriving the same
  * key stops the run, names both sources and writes nothing. The Anki side had no check at
  * all — the reconciler built its lookup with `.toMap`, so of two notes carrying the same
  * `src::` identity tag one simply won and the other became invisible to every later run.
  *
  * WHAT THE LOSER SUFFERS, which is why silence is not survivable: it is never updated, so
  * it holds whatever content it had forever; it is never flagged, because the orphan rule
  * excludes any observed card whose key IS present in the markdown — and the winner's key
  * is; and it is therefore never prunable either. It keeps appearing in reviews, diverging,
  * with nothing anywhere reporting it.
  *
  * IT IS REACHABLE TODAY. `allowDuplicate` is set deliberately — the design needs it, since
  * a concept's second descriptor shares a first field with its first — so Anki will not
  * refuse the second note. A retried creation after an interrupted run, a restored backup,
  * or a card duplicated by hand in Anki all produce it.
  */
class DuplicateIdentityTest extends munit.FunSuite:

  def key(id: String, segments: String*): CardKey =
    CardKey(
      NoteId.fromFrontmatter(id).toOption.get,
      HeadingPath(
        NonEmptyVector.fromVectorUnsafe(
          segments.toVector.map(s => HeadingSegment.fromExtractedText(s).toOption.get)
        )
      ),
    )

  val defaultDeck: DeckPath = DeckPath(NonEmptyVector.of("Obsidian", "System-Design"))

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

  val k: CardKey = key("n1", "Coupling", "Temporal coupling")

  /** Builds the state the check must catch: one card synced normally, then a SECOND Anki note
    * created carrying the same identity tag — exactly what a retried creation leaves behind.
    */
  def collidingCollection(): InMemoryAnki =
    val anki = InMemoryAnki()
    val scan = VaultScan.from(Vector(specOf(k, "the original")), Vector.empty)
    val plan = Planner
      .plan(scan, Observer.observe(anki).toOption.get, _ => defaultDeck, newNoteOf)
      .fold(e => fail(s"setup plan failed: $e"), identity)
    Executor.run(plan, anki, RetypePolicy.Defer).fold(e => fail(s"setup failed: $e"), identity)

    anki
      .addNote(
        NewNote(
          noteType = Marker.NoteTypes.Basic,
          deck = defaultDeck,
          fields = Vector("Front" -> "front", "Back" -> "a stray duplicate"),
          tags = NonEmptyVector.of(TagCodec.encode(k), OwnedTag.sha("deadbeef")),
        )
      )
      .fold(e => fail(s"could not create the colliding note: $e"), identity)
    anki

  test("two Anki notes with ONE identity are both observed, not silently merged") {
    val observed = Observer.observe(collidingCollection()).fold(e => fail(s"$e"), identity)
    assertEquals(
      observed.notes.count(_.key == k),
      2,
      "the observation itself lost a note — the collision is invisible before the planner sees it",
    )
  }

  /** The symmetry that makes this a defect rather than a preference: the same collision on
    * the markdown side stops the run and names both sources.
    */
  test("a collision in Anki is FATAL, exactly as a collision in the markdown is") {
    val anki = collidingCollection()
    val scan = VaultScan.from(Vector(specOf(k, "the original")), Vector.empty)

    Planner.plan(scan, Observer.observe(anki).toOption.get, _ => defaultDeck, newNoteOf) match
      case Left(errors) =>
        val described = errors.map(_.describe).mkString("\n")
        assert(
          described.contains(k.path.render),
          s"the error does not name the colliding key:\n$described",
        )
      case Right(plan) =>
        fail(s"a duplicated identity produced a plan instead of an error: ${plan.actions}")
  }

  /** Both note ids must be named. "There is a duplicate somewhere" is not actionable — a
    * human has to open both notes in Anki to decide which to delete.
    */
  test("the error names BOTH Anki notes, so a human can act on it") {
    val anki = collidingCollection()
    val ids  = Observer.observe(anki).toOption.get.notes.filter(_.key == k).map(_.note.id.value)
    val scan = VaultScan.from(Vector(specOf(k, "the original")), Vector.empty)

    Planner.plan(scan, Observer.observe(anki).toOption.get, _ => defaultDeck, newNoteOf) match
      case Left(errors) =>
        val described = errors.map(_.describe).mkString("\n")
        ids.foreach(id => assert(described.contains(id.toString), s"note $id unnamed:\n$described"))
      case Right(_) => fail("no error raised")
  }

  /** The control: an ordinary collection still plans normally. Without this the test above
    * would pass just as well if the planner refused everything.
    */
  test("CONTROL: a collection with no duplicated identity plans as usual") {
    val anki = InMemoryAnki()
    val scan = VaultScan.from(Vector(specOf(k, "the original")), Vector.empty)
    val plan = Planner
      .plan(scan, Observer.observe(anki).toOption.get, _ => defaultDeck, newNoteOf)
      .fold(e => fail(s"an ordinary collection was refused: ${e.map(_.describe)}"), identity)
    assertEquals(plan.actions.size, 1)
  }
