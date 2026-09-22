package obsidiananki.plan

import cats.data.NonEmptyVector
import obsidiananki.anki.*
import obsidiananki.model.*
import obsidiananki.plan.SectionChain.{NoRecall, NoSectionChain}

/** RECOVERING A NOTE WHOSE CONTENT FINGERPRINT WAS RECORDED BEFORE THE `Topics` FIELD EXISTED.
  *
  * ==What breaks==
  *
  * `Planner.contentHash` hashes field NAMES as well as values, on purpose, so that a field
  * reordering or a note-type change shows up as a difference. A sixth field named `Topics` was
  * added to all five owned note types on 2026-09-22, so every fingerprint this tool computes from
  * that moment on differs from every fingerprint it recorded before — for EVERY card, including
  * cards with no topics at all, because the name alone changes the string being hashed.
  *
  * Change detection survives that: it compares a recorded fingerprint against a fresh one,
  * concludes "changed", rewrites the note once, and records the new fingerprint. RECOVERY DOES
  * NOT. `Planner.identityErrorFor`'s `byRecordedHash` compares a RECORDED fingerprint against
  * fingerprints computed FRESH FROM THE VAULT, so for a note recorded before the field arrived the
  * two sides can never agree, and the strongest evidence available about what an unreadable note
  * used to be is silently worth nothing.
  *
  * ==Why it does not simply age out==
  *
  * A read-only census of the reference collection on 2026-09-22 found 193 notes on owned note
  * types, every one with a well-formed identity — so nothing is in the broken state today, and
  * these tests are about a state that is reachable rather than one being repaired.
  *
  * 29 of those 193 are ORPHANED, and that is the half that never ages out. An orphan has no vault
  * source, so there is no spec to write it from, so no run ever rewrites it — which is exactly why
  * `Planner.plan`'s identity backfill had to be a separate pass. Such a note keeps its pre-`Topics`
  * fingerprint permanently. Break its identity tag by hand a year from now and, without the guard
  * these tests pin, the report would offer no suggestion about a note whose content still matches
  * the vault exactly.
  *
  * ==What these tests are careful about==
  *
  * The fingerprint a pre-`Topics` note carries is reconstructed here from the algorithm rather
  * than borrowed from the production function — see [[asRecordedBeforeTopics]] for why borrowing
  * it would make the whole suite agree with itself and measure nothing.
  */
class PreTopicsHashTest extends munit.FunSuite:

  type Result[A] = Either[AnkiError, A]

  // ---------------------------------------------------------------- fixtures ----

  def key(id: String, segments: String*): CardKey =
    CardKey(
      NoteId.fromFrontmatter(id).toOption.get,
      CardPath.Headings(HeadingPath(
        NonEmptyVector.fromVectorUnsafe(
          segments.toVector.map(s => HeadingSegment.fromExtractedText(s).toOption.get)
        )
      )),
    )

  val k1: CardKey = key("n1", "Coupling", "Temporal coupling")

  val defaultDeck: DeckPath = DeckPath(NonEmptyVector.of("Obsidian", "System-Design"))

  def body(s: String): Body = Body.fromExtracted(s).getOrElse(fail("empty test body"))

  def specOf(k: CardKey, back: String, bearings: Bearings): SourcedSpec =
    SourcedSpec(
      CardSpec.TwoField(k, "front", body(back), TwoFieldDirections.Forward, bearings),
      SourceRef("Note.md", 1, SourceKind.Heading),
      NoSectionChain,
      NoRecall,
      Vector.empty,
    )

  /** A NOTE'S FIELDS WITH THE IDENTITY FIELD BLANKED — "its identity is in the tag and nowhere
    * else", which is what a fixture for the recovery path has to mean. Without this, a note built
    * from a spec carries a perfectly good identity in its field alongside the broken tag it was
    * constructed to be about, the resolver reads the field first and places it happily, and the
    * test below never reaches the code it is named after. The same helper, and the same reason,
    * as in `plan/UnplaceableNote.test.scala`.
    */
  def tagOnlyFields(spec: CardSpec): Vector[(String, String)] =
    spec.fields.map {
      case (name, _) if name == Marker.IdentityField => name -> ""
      case other                                     => other
    }

  /** THE FINGERPRINT A NOTE SYNCED BEFORE 2026-09-22 ACTUALLY CARRIES, spelled out here from the
    * algorithm rather than obtained from `Planner.preTopicsContentHash`.
    *
    * ⚠️ BORROWING THE PRODUCTION FUNCTION WOULD MAKE EVERY TEST BELOW VACUOUS in the one direction
    * that matters. A `preTopicsContentHash` that excluded the wrong field, or joined with a
    * different separator, or truncated to a different length would agree with itself perfectly —
    * and this suite would stay green while reproducing no real collection's fingerprint at all. An
    * oracle derived from the thing it certifies can never disagree with it.
    *
    * WHAT IT IS: the body of `Planner.contentHash` as it stood before the `Topics` field existed.
    * The note type name, then each field's name and value in document order, joined with the
    * unit-separator control character; SHA-256; the first eight bytes in hex. The identity field is
    * excluded because it has been excluded since 2026-08-28, which is before the window this models.
    * `Topics` is excluded not by a rule but by absence — a note type that did not declare the field
    * gave its notes no such field to hash.
    */
  def asRecordedBeforeTopics(spec: CardSpec): String =
    val sep = "\u001f"
    val content = spec.fields.filterNot((name, _) =>
      name == Marker.IdentityField || name == Marker.TopicsField
    )
    val canonical =
      (spec.noteTypeName +: content.flatMap { case (n, v) => Vector(n, v) }).mkString(sep)
    java.security.MessageDigest
      .getInstance("SHA-256")
      .digest(canonical.getBytes(java.nio.charset.StandardCharsets.UTF_8))
      .take(8)
      .map("%02x".format(_))
      .mkString

  def observe(anki: InMemoryAnki): ObservedState =
    Observer.observe[Result](anki).fold(e => fail(s"observe failed: $e"), identity)

  /** One note whose identity tag cannot be decoded — `src::n1` has two components where the codec
    * requires three — carrying the fingerprint given here.
    */
  def noteWithUnreadableIdentity(spec: CardSpec, recordedSha: String): InMemoryAnki =
    val anki = InMemoryAnki()
    anki
      .addNote(
        NewNote(
          noteType = spec.noteTypeName,
          deck = defaultDeck,
          fields = tagOnlyFields(spec),
          tags = NonEmptyVector.of(
            OwnedTag.unsafeFromString("src::n1"),
            OwnedTag.sha(recordedSha),
          ),
        )
      )
      .fold(e => fail(s"$e"), identity)
    anki

  def suggestionFor(anki: InMemoryAnki, specs: Vector[SourcedSpec]): IdentityRecovery =
    Planner.identityErrorFor(observe(anki).unresolved.head, specs) match
      case PlanError.UnreadableIdentityInAnki(_, _, _, looksLike) => looksLike
      case other => fail(s"wrong error: $other")

  // ═══════════════════════════════════════════════ the premise, measured ══════

  /** THE TEST THAT MAKES THE REST OF THIS FILE MEAN SOMETHING. If the two fingerprints agreed,
    * every test below would pass with the guard deleted and would be proving nothing.
    *
    * The fixture deliberately has NO topics, so the only thing that differs between the two
    * digests is a field NAME. That is the strong form of the claim: it is not that cards with
    * topics changed, it is that every card did.
    */
  test("adding the Topics field changed every fingerprint, including for a card with no topics") {
    val spec = specOf(k1, "back", Bearings.breadcrumbOnly("Coupling")).spec

    assertEquals(
      spec.fields.collectFirst { case (name, value) if name == Marker.TopicsField => value },
      Some(""),
      "fixture premise broken: this spec was supposed to emit an EMPTY Topics field",
    )
    assertNotEquals(
      Planner.contentHash(spec),
      asRecordedBeforeTopics(spec),
      "a field name was added and the fingerprint did not move, so there is nothing to guard " +
        "against — and contentHash has stopped hashing field names",
    )
    assertEquals(
      Planner.preTopicsContentHash(spec),
      asRecordedBeforeTopics(spec),
      "the production digest does not reproduce what a pre-Topics note actually carries",
    )
  }

  // ══════════════════════════════════════════════════════ the recovery ══════

  /** THE HAZARD ITSELF. The note's identity is unreadable and its content is untouched, so the
    * recorded fingerprint is the only evidence of what it used to be — and it was recorded in a
    * shape no present-day computation reproduces.
    */
  test("a note whose fingerprint predates the Topics field is still identified by its content") {
    val spec = specOf(k1, "back", Bearings.breadcrumbOnly("Coupling"))
    val anki = noteWithUnreadableIdentity(spec.spec, asRecordedBeforeTopics(spec.spec))

    assertEquals(
      suggestionFor(anki, Vector(spec)),
      IdentityRecovery.LooksLike(k1),
      "a note recorded before the Topics field was added is no longer recoverable from its " +
        "content hash",
    )
  }

  /** THE VALUE IS DROPPED TOO, NOT ONLY THE NAME. A pre-`Topics` note was recorded when the field
    * did not exist, so neither its name nor any value was in the digest — while the vault produces
    * a topics value for that same card today. A guard that excluded only the name would reproduce
    * nothing for any card that actually has topics, which is the majority of tagged notes.
    */
  test("recovery works for a card the vault now gives topics to, not just for an empty one") {
    val spec = specOf(k1, "back", Bearings("Coupling", "backend, scala"))
    assertEquals(
      spec.spec.fields.collectFirst { case (name, value) if name == Marker.TopicsField => value },
      Some("backend, scala"),
      "fixture premise broken: this spec was supposed to emit a NON-EMPTY Topics field",
    )

    val anki = noteWithUnreadableIdentity(spec.spec, asRecordedBeforeTopics(spec.spec))
    assertEquals(suggestionFor(anki, Vector(spec)), IdentityRecovery.LooksLike(k1))
  }

  /** THE SILENCE IS NOT WIDENED EITHER. Accepting a second digest doubles the number of ways a
    * spec can match, so the refusal to choose between candidates is worth re-measuring rather than
    * assuming: a note whose content really has changed must still get no suggestion.
    */
  test("a pre-Topics fingerprint that matches nothing still yields no suggestion") {
    val recorded = specOf(k1, "back", Bearings.breadcrumbOnly("Coupling"))
    val edited   = specOf(k1, "edited since", Bearings.breadcrumbOnly("Coupling"))
    val anki     = noteWithUnreadableIdentity(recorded.spec, asRecordedBeforeTopics(recorded.spec))

    assertEquals(
      suggestionFor(anki, Vector(edited)),
      IdentityRecovery.FingerprintMatchedNothing(asRecordedBeforeTopics(recorded.spec)),
      "the second digest matched a card whose content had changed",
    )
  }

  // ═══════════════════════════ what the guard must NOT reach: change detection ══════

  /** ⚠️ THE MIGRATION MUST STILL HAPPEN. The guard is confined to recovery on purpose, and this is
    * the test that holds it there.
    *
    * `fieldsDiffer` compares against `contentHash` ALONE. If it accepted a pre-`Topics` fingerprint
    * as well, a note recorded before the field was added would report itself unchanged, would never
    * be rewritten, and its `Topics` field would stay empty forever — the same "skip it forever and
    * the field never arrives" failure the identity migration beside it already names, arriving from
    * a new direction and looking like a clean run.
    */
  test("a note carrying a pre-Topics fingerprint is still rewritten, so the field arrives") {
    val spec = specOf(k1, "back", Bearings("Coupling", "backend, scala"))
    val anki = InMemoryAnki()
    anki
      .addNote(Planner.newNoteFor(spec, defaultDeck, asRecordedBeforeTopics(spec.spec)))
      .fold(e => fail(s"$e"), identity)

    val scan = VaultScan.from(Vector(spec), Vector.empty)
    val plan = Planner
      .plan(scan, observe(anki), _ => defaultDeck, Planner.newNoteFor, HandBuiltCensus.of(scan))
      .fold(errs => fail(s"unexpected plan errors: ${errs.map(_.describe)}"), identity)

    val fieldWrites = plan.actions.collect { case u: SyncAction.Update => u }
      .flatMap(_.changes.toVector)
      .collect { case f: Change.FieldsChanged => f }

    assertEquals(
      fieldWrites.size,
      1,
      s"the note was not rewritten, so its Topics field never arrives: ${plan.actions}",
    )
    assertEquals(
      fieldWrites.head.newSha,
      Planner.contentHash(spec.spec),
      "the rewrite recorded something other than the present-day fingerprint, so the next run " +
        "would rewrite it again",
    )
  }
