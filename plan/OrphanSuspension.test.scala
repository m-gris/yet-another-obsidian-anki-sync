package obsidiananki.plan

import cats.data.NonEmptyVector
import obsidiananki.anki.*
import obsidiananki.model.*

/** A CARD WHOSE HEADING IS GONE MUST LEAVE THE REVIEW QUEUE, AND COME BACK IF THE HEADING DOES.
  *
  * ==What this fixes==
  *
  * Flagging used to write a tag and nothing else. The card kept being asked, every day, for a
  * heading the vault no longer had — and the tag, which is invisible during review, was the only
  * sign. Ruled 2026-08-19 and unbuilt until now.
  *
  * ==Why suspension and not something cheaper==
  *
  * Measured against a live collection on 2026-08-22: a filtered deck searching
  * `deck:Obsidian -tag:orphaned::*` does exclude an orphan — 81 cards rather than 82 — so the
  * effect is reachable with no code at all. It is FAIL-OPEN: studying the deck the ordinary way
  * puts the orphan straight back in front of you. Suspension is fail-closed and needs no
  * discipline, and it is the only Anki mechanism that removes a card from study while keeping
  * its deck, its interval, its ease and its whole review log.
  *
  * ==What these tests assert==
  *
  * Outcomes in the collection — which cards are suspended, which decks they are in — never which
  * calls were made. The one thing deliberately NOT asserted is scheduling state, because
  * [[InMemoryAnki]] does not model intervals at all; that half was measured live instead, and
  * saying so here is more honest than a test that appears to cover it.
  */
class OrphanSuspensionTest extends munit.FunSuite:

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

  /** A TWO-WAY card on purpose: it has TWO Anki cards behind one note, which is the case a
    * per-note suspension would get wrong by suspending one of them.
    */
  def specOf(k: CardKey): SourcedSpec =
    SourcedSpec(
      CardSpec.TwoField(k, "front", body("back"), TwoFieldDirections.Both, "Coupling"),
      SourceRef("Note.md", 1, SourceKind.Heading),
    )

  def newNoteOf(s: SourcedSpec, d: DeckPath, sha: String): NewNote =
    NewNote(
      noteType = s.spec.noteTypeName,
      deck = d,
      fields = s.spec.fields,
      tags = NonEmptyVector.of(TagCodec.encode(s.key), OwnedTag.sha(sha)),
    )

  val k: CardKey = key("n1", "Coupling", "Temporal coupling")

  def scanOf(specs: SourcedSpec*): VaultScan = VaultScan.from(specs.toVector, Vector.empty)

  def observe(anki: InMemoryAnki): ObservedState =
    Observer.observe[Result](anki).fold(e => fail(s"observe failed: $e"), identity)

  def runSync(anki: InMemoryAnki, scan: VaultScan): ExecutionReport =
    val plan = Planner
      .plan(scan, observe(anki), _ => deck, newNoteOf)
      .fold(errs => fail(s"plan errors: ${errs.map(_.describe)}"), identity)
    Executor.run[Result](plan, anki, RetypePolicy.Apply).fold(e => fail(s"run failed: $e"), identity)

  def cardsOf(anki: InMemoryAnki): Vector[AnkiCardId] =
    val ids = anki.findNotesByTagPrefix("src::").fold(e => fail(s"$e"), identity)
    anki.cardsOf(ids).fold(e => fail(s"$e"), identity)

  /** Sync a vault holding the card, then sync one that does not — which is what deleting a
    * heading looks like from the tool's side.
    */
  def collectionWithAnOrphan(): InMemoryAnki =
    val anki = InMemoryAnki()
    runSync(anki, scanOf(specOf(k)))
    runSync(anki, scanOf())
    anki

  // ═══════════════════════════════════════════════════════ leaving the queue ══

  test("every card of an orphaned note is suspended, not just the first") {
    val anki  = collectionWithAnOrphan()
    val cards = cardsOf(anki)

    assertEquals(cards.size, 2, "the fixture must be a note with TWO cards, or this proves little")
    cards.foreach { c =>
      assert(anki.isSuspended(c), s"card ${c.value} was left in the review queue")
    }
  }

  test("the orphan tag is still written — suspension REPLACES nothing") {
    // Without the tag the card is invisible AND unfindable: suspended, unexplained, and absent
    // from every orphan search a person would run to reconcile it.
    val anki = collectionWithAnOrphan()
    val ids  = anki.findNotesByTagPrefix("src::").fold(e => fail(s"$e"), identity)
    val tags = anki.notesInfo(ids).fold(e => fail(s"$e"), identity).flatMap(_.tags)
    assert(
      tags.exists(_.startsWith(OwnedTag.OrphanedPrefix)),
      s"the orphan tag is gone, so nothing explains the suspension: $tags",
    )
  }

  test("suspending does NOT move the card out of its deck") {
    // The whole argument for suspension over relocation is that it costs nothing: the card stays
    // exactly where it was, so reinstating it needs no memory of where it came from.
    val anki = collectionWithAnOrphan()
    cardsOf(anki).foreach { c =>
      assertEquals(anki.deckOf(c).fold(e => fail(s"$e"), identity), Some(deck))
    }
  }

  test("a healthy card is never suspended") {
    val anki = InMemoryAnki()
    runSync(anki, scanOf(specOf(k)))
    cardsOf(anki).foreach(c => assert(!anki.isSuspended(c), s"card ${c.value} was suspended"))
  }

  // ═════════════════════════════════════════════════════ coming back ══════════

  /** THE HALF THAT MAKES A RENAME RECOVERABLE. Without it the flag is cleared, every report says
    * the card is live, and it is never shown again — a worse state than the one suspension fixes,
    * because it looks fixed.
    */
  test("a heading that comes back brings its cards back into the queue") {
    val anki = collectionWithAnOrphan()
    cardsOf(anki).foreach(c => assert(anki.isSuspended(c), "setup: the card should be suspended"))

    runSync(anki, scanOf(specOf(k)))

    cardsOf(anki).foreach { c =>
      assert(!anki.isSuspended(c), s"card ${c.value} stayed suspended after its heading returned")
    }
    val ids  = anki.findNotesByTagPrefix("src::").fold(e => fail(s"$e"), identity)
    val tags = anki.notesInfo(ids).fold(e => fail(s"$e"), identity).flatMap(_.tags)
    assert(!tags.exists(_.startsWith(OwnedTag.OrphanedPrefix)), s"orphan tag not cleared: $tags")
  }

  test("no note is created or destroyed by going away and coming back") {
    // The point of flagging rather than deleting: the SAME note survives, so its history does.
    val anki   = InMemoryAnki()
    runSync(anki, scanOf(specOf(k)))
    val before = anki.findNotesByTagPrefix("src::").fold(e => fail(s"$e"), identity)

    runSync(anki, scanOf())
    runSync(anki, scanOf(specOf(k)))

    assertEquals(anki.findNotesByTagPrefix("src::").fold(e => fail(s"$e"), identity), before)
  }

  // ═════════════════════════════════════════════════════════ re-running ══════

  test("flagging twice changes nothing the second time") {
    val anki = collectionWithAnOrphan()
    val report = runSync(anki, scanOf())
    assertEquals(report.failures, Vector.empty)
    cardsOf(anki).foreach(c => assert(anki.isSuspended(c), "a second run un-suspended an orphan"))
  }
