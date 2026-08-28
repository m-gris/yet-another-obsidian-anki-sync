package obsidiananki.plan

import cats.data.NonEmptyVector
import obsidiananki.anki.*
import obsidiananki.model.*
import obsidiananki.plan.SectionChain.{NoRecall, NoSectionChain}

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
      CardPath.Headings(HeadingPath(
        NonEmptyVector.fromVectorUnsafe(
          segments.toVector.map(s => HeadingSegment.fromExtractedText(s).toOption.get)
        )
      )),
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

  def scanOf(specs: SourcedSpec*): VaultScan = VaultScan.from(specs.toVector, Vector.empty)

  def observe(anki: InMemoryAnki): ObservedState =
    Observer.observe[Result](anki).fold(e => fail(s"observe failed: $e"), identity)

  def runSync(anki: InMemoryAnki, scan: VaultScan): ExecutionReport =
    val plan = Planner
      .plan(scan, observe(anki), _ => deck, newNoteOf)
      .fold(errs => fail(s"plan errors: ${errs.map(_.describe)}"), identity)
    Executor.run[Result](plan, anki, RetypePolicy.Apply, Set.empty).fold(e => fail(s"run failed: $e"), identity)

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

  // ══════════════════ coming back ON A DIFFERENT NOTE TYPE ═══════════════════

  /** THE SAME RETURN, THROUGH THE OTHER BRANCH — and it strands the card.
    *
    * Everything above exercises a heading that comes back UNCHANGED, which reaches the `Update`
    * branch, which computes an `Unflag`, which unsuspends. A heading that comes back with a
    * DIFFERENT MARKER — `#flashcard/1way` retagged `#flashcard/sequence`, say — reaches the
    * `Retype` branch instead, and that branch computes no `Unflag` at all.
    *
    * WHY THAT LOOKED CORRECT. `Retype` rebuilds the note's owned tags from scratch, so the
    * `orphaned::` tag really does disappear by the same write, and `plan/Planner.scala:307-312`
    * says so and concludes a separate `Unflag` is unnecessary. The reasoning is sound about
    * TAGS and silently incomplete: `Unflag` does two things, and the second one — unsuspending
    * every card of the note — has no other home.
    *
    * WHAT THE CARD IS LEFT AS. Correctly keyed, correctly typed, in the right deck, carrying no
    * orphan tag, holding its full review history — and suspended forever. Nothing reports it:
    * `Report.parkedNote` counts notes carrying the tag, and this note no longer carries it. No
    * later run repairs it: content and deck now match, so no action is planned. It is exactly
    * the state `plan/Executor.scala:367-371` argues the `Unflag` ORDERING exists to prevent —
    * "an untagged live card is indistinguishable from a healthy one" — reintroduced through the
    * sibling branch.
    *
    * REACHABLE WITHOUT ANYTHING UNUSUAL: both note types here are standard with one template
    * each, so the retype gate admits the move. It needs `--migrate-note-types`, which is what
    * `RetypePolicy.Apply` means here.
    */
  test("a heading that comes back on a DIFFERENT note type is not left suspended") {
    val anki = InMemoryAnki()

    // A one-way card: ONE Anki card, on `Obsidian Basic`.
    val oneWay = SourcedSpec(
      CardSpec.TwoField(k, "front", body("back"), TwoFieldDirections.Forward, "Coupling"),
      SourceRef("Note.md", 1, SourceKind.Heading),
      NoSectionChain,
      NoRecall,
    )
    runSync(anki, scanOf(oneWay))

    // The heading goes away: tagged and suspended.
    runSync(anki, scanOf())
    cardsOf(anki).foreach(c => assert(anki.isSuspended(c), "setup: the card should be suspended"))

    // It comes back as a SEQUENCE — same key, same one-template shape, different note type.
    val sequence = SourcedSpec(
      CardSpec.Sequence(k, "front", body("first item"), "Coupling"),
      SourceRef("Note.md", 1, SourceKind.Heading),
      NoSectionChain,
      NoRecall,
    )
    runSync(anki, scanOf(sequence))

    val ids = anki.findNotesByTagPrefix("src::").fold(e => fail(s"$e"), identity)
    val notes = anki.notesInfo(ids).fold(e => fail(s"$e"), identity)

    // The retype itself happened — without this the test could pass by the move never occurring.
    assertEquals(
      notes.map(_.noteType),
      Vector(Marker.NoteTypes.ClozeSequence),
      "the note was not retyped, so this test is not exercising the branch it is about",
    )

    // And the tag really is gone, which is the half the planner reasoned about correctly.
    assert(
      !notes.flatMap(_.tags).exists(_.startsWith(OwnedTag.OrphanedPrefix)),
      "the orphan tag survived the retype — a different defect from the one this test is for",
    )

    // THE DEFECT. Nothing carries the unsuspend, so the card stays out of the queue with no
    // tag to explain it and nothing that will ever look at it again.
    cardsOf(anki).foreach { c =>
      assert(
        !anki.isSuspended(c),
        s"card ${c.value} came back on a new note type and was left SUSPENDED with its orphan " +
          "tag removed — invisible to every report and unreachable by every later run",
      )
    }
  }

  /** THE CONTROL. Retyping a note that was never orphaned must not unsuspend anything, because
    * a card the tool did not suspend is a card somebody suspended by hand — and the settled
    * ruling is that this tool cannot tell its own suspension from Marc's. Without this, the fix
    * above is satisfiable by unsuspending unconditionally on every retype.
    */
  test("retyping a note that was never orphaned leaves a hand-suspended card alone") {
    val anki = InMemoryAnki()

    val oneWay = SourcedSpec(
      CardSpec.TwoField(k, "front", body("back"), TwoFieldDirections.Forward, "Coupling"),
      SourceRef("Note.md", 1, SourceKind.Heading),
      NoSectionChain,
      NoRecall,
    )
    runSync(anki, scanOf(oneWay))

    // Somebody suspends it in Anki, by hand. No orphan tag: the tool never flagged it.
    val byHand = cardsOf(anki)
    anki.suspend(byHand).fold(e => fail(s"$e"), identity)

    val sequence = SourcedSpec(
      CardSpec.Sequence(k, "front", body("first item"), "Coupling"),
      SourceRef("Note.md", 1, SourceKind.Heading),
      NoSectionChain,
      NoRecall,
    )
    runSync(anki, scanOf(sequence))

    cardsOf(anki).foreach { c =>
      assert(
        anki.isSuspended(c),
        s"card ${c.value} was un-suspended by a retype, overriding a decision the tool did not " +
          "make and cannot distinguish from its own",
      )
    }
  }

  // ═════════════════════════════════════════════════════════ re-running ══════

  test("flagging twice changes nothing the second time") {
    val anki = collectionWithAnOrphan()
    val report = runSync(anki, scanOf())
    assertEquals(report.failures, Vector.empty)
    cardsOf(anki).foreach(c => assert(anki.isSuspended(c), "a second run un-suspended an orphan"))
  }

  // ══════════════════════ a broken section is not a deleted one ══════════

  /** THE WORST FAILURE THIS FILE GUARDS, and it was live until 2026-08-22.
    *
    * A build failure is recorded at the key of the SECTION that failed. A table's cards are
    * keyed DEEPER — `…/cost / benefit` fails while its cards are `…/cost / benefit/queue/cost`
    * — and the orphan check compared keys for EQUALITY, so every card the section had ever
    * produced looked deleted. Measured against the fixture vault: pasting an image into ONE
    * cell flagged 15 live cards, and since suspension landed it also took all 15 out of review.
    * The run reported "1 card could not be built" and never mentioned the fifteen.
    *
    * The rule was already written down beside the check — "a card that merely failed to build
    * is not absent from the markdown" — and the check did not implement it.
    */
  test("a card under a section that FAILED to build is not flagged as deleted") {
    val anki    = InMemoryAnki()
    val section = key("n1", "Coupling")
    val card    = key("n1", "Coupling", "Temporal coupling")

    runSync(anki, scanOf(specOf(card)))
    cardsOf(anki).foreach(c => assert(!anki.isSuspended(c), "setup: nothing should be suspended"))

    // The section is now BROKEN rather than gone: nothing built, and the failure is recorded
    // at the section's own key — one segment shallower than the card's.
    val broken = VaultScan.from(
      Vector.empty,
      Vector(BuildFailure.KeyKnown(section, SourceRef("Note.md", 1, SourceKind.Heading), "boom")),
    )
    val plan = Planner
      .plan(broken, observe(anki), _ => deck, newNoteOf)
      .fold(errs => fail(s"plan errors: ${errs.map(_.describe)}"), identity)

    assertEquals(
      plan.actions,
      Vector.empty,
      s"a broken section flagged its own live cards as deleted: ${plan.actions}",
    )
  }

  /** THE OTHER HALF, so the fix cannot be "never flag anything". A card whose section builds
    * FINE and which is simply gone from the markdown must still be flagged and suspended.
    */
  test("a card that is genuinely gone is still flagged, even while a SIBLING section fails") {
    val anki    = InMemoryAnki()
    val gone    = key("n1", "Coupling", "Temporal coupling")
    val broken  = key("n2", "Elsewhere")

    runSync(anki, scanOf(specOf(gone)))

    // A failure under a DIFFERENT note entirely. It must not shelter this card.
    val scan = VaultScan.from(
      Vector.empty,
      Vector(BuildFailure.KeyKnown(broken, SourceRef("Other.md", 1, SourceKind.Heading), "boom")),
    )
    val plan = Planner
      .plan(scan, observe(anki), _ => deck, newNoteOf)
      .fold(errs => fail(s"plan errors: ${errs.map(_.describe)}"), identity)

    assert(
      plan.actions.exists(_.isInstanceOf[SyncAction.Flag]),
      s"a genuinely deleted card was not flagged: ${plan.actions}",
    )
  }

  /** The shelter is by ANCESTRY, not by prefix of the rendered string. `Coupling` must not
    * shelter `Couplings`, which is a different heading that merely starts the same way.
    */
  test("a sibling whose name merely STARTS THE SAME is not sheltered") {
    val anki = InMemoryAnki()
    val card = key("n1", "Couplings", "Temporal coupling")
    runSync(anki, scanOf(specOf(card)))

    val scan = VaultScan.from(
      Vector.empty,
      Vector(
        BuildFailure.KeyKnown(key("n1", "Coupling"), SourceRef("Note.md", 1, SourceKind.Heading), "boom")
      ),
    )
    val plan = Planner
      .plan(scan, observe(anki), _ => deck, newNoteOf)
      .fold(errs => fail(s"plan errors: ${errs.map(_.describe)}"), identity)

    assert(
      plan.actions.exists(_.isInstanceOf[SyncAction.Flag]),
      s"'Coupling' sheltered 'Couplings', which is a different heading: ${plan.actions}",
    )
  }
