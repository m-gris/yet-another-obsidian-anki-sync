package obsidiananki.plan

import cats.data.NonEmptyVector
import obsidiananki.anki.*
import obsidiananki.model.*
import obsidiananki.plan.SectionChain.{NoRecall, NoSectionChain}

/** WHY A RECOVERY FOUND NOTHING, AND WHY THAT USED TO BE UNSAYABLE.
  *
  * ==What was wrong==
  *
  * `Planner.identityErrorFor` asks the vault what an unplaceable note most likely is, by
  * comparing the content fingerprint the last sync recorded against fingerprints computed fresh
  * from the vault. Its answer was an `Option[CardKey]`, and `None` meant three different things:
  *
  *  - the note records no single usable fingerprint, so there was nothing to compare;
  *  - it records one, and no card the vault produces today has it;
  *  - it records one, and several cards share it.
  *
  * The run printed the same sentence for all three — "unreadable identity", and then silence. A
  * reader could not tell whether the evidence was missing, stale, or merely not decisive, which
  * is the difference between "go and look at the note", "go and look at the vault", and "there is
  * nothing to look at". The remedy for an unreadable identity is entirely manual, so telling
  * somebody nothing about why the tool could not help is telling them to start from scratch.
  *
  * ==Why it mattered more than it looked==
  *
  * On 2026-09-22 a sixth field named `Topics` was added to all five owned note types, which made
  * every fingerprint recorded before that date unreproducible — see `plan/PreTopicsHash.test.scala`.
  * Every affected note would have recovered as `None`, and the report would have said precisely
  * what it says for a note whose body was simply edited. A defect that turns one true sentence
  * into a different true-looking sentence leaves no trace at all. This is the distinction that
  * would have made it visible.
  *
  * ==What these tests hold==
  *
  * One per reachable conclusion, asserted on BOTH the value and the rendered message, because the
  * thing being fixed is what a run REPORTS rather than what it computes. Plus the property that
  * makes the set worth having: the three empty-handed messages differ from each other. A suite
  * that checked each message was non-empty would pass with all three saying the same thing, which
  * is the defect wearing a new coat.
  *
  * And the two standing rulings, re-asserted rather than assumed: the encoded tag is printed in
  * full when a card IS named, because nobody can type one by hand; and no message ever asserts —
  * a wrong rebind moves review history onto the wrong card, irreversibly, so the tool names and
  * never acts.
  */
class IdentityRecoveryTest extends munit.FunSuite:

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
  val k2: CardKey = key("n1", "Coupling", "Afferent coupling")

  val deck: DeckPath = DeckPath(NonEmptyVector.of("Obsidian", "System-Design"))

  def body(s: String): Body = Body.fromExtracted(s).getOrElse(fail("empty test body"))

  def specOf(k: CardKey, back: String): SourcedSpec =
    SourcedSpec(
      CardSpec.TwoField(k, "front", body(back), TwoFieldDirections.Forward, Bearings.breadcrumbOnly("Coupling")),
      SourceRef("Note.md", 1, SourceKind.Heading),
      NoSectionChain,
      NoRecall,
      Vector.empty,
    )

  /** A NOTE'S FIELDS WITH THE IDENTITY FIELD BLANKED — "its identity is in the tag and nowhere
    * else". Without it the resolver reads the good identity out of the field, places the note
    * happily, and nothing here reaches the code under test. Same helper and same reason as in
    * `plan/UnplaceableNote.test.scala`.
    */
  def tagOnlyFields(spec: CardSpec): Vector[(String, String)] =
    spec.fields.map {
      case (name, _) if name == Marker.IdentityField => name -> ""
      case other                                     => other
    }

  /** One note whose identity tag cannot be decoded — `src::n1` has two components where the codec
    * requires three — carrying exactly the tags given.
    *
    * THE TAGS ARE THE WHOLE POINT OF THE PARAMETER, since what distinguishes these cases is what
    * the note recorded: no `sha::` at all, one, or two. The broken identity tag is added here so
    * that every fixture reaches the unplaceable path for the same reason.
    */
  def brokenNoteCarrying(spec: CardSpec, shaTags: Vector[OwnedTag]): InMemoryAnki =
    val anki = InMemoryAnki()
    anki
      .addNote(
        NewNote(
          noteType = spec.noteTypeName,
          deck = deck,
          fields = tagOnlyFields(spec),
          tags = NonEmptyVector(OwnedTag.unsafeFromString("src::n1"), shaTags),
        )
      )
      .fold(e => fail(s"$e"), identity)
    anki

  def observe(anki: InMemoryAnki): ObservedState =
    Observer.observe[Result](anki).fold(e => fail(s"observe failed: $e"), identity)

  def errorFor(anki: InMemoryAnki, specs: Vector[SourcedSpec]): PlanError =
    Planner.identityErrorFor(observe(anki).unresolved.head, specs)

  def recoveryIn(error: PlanError): IdentityRecovery = error match
    case PlanError.UnreadableIdentityInAnki(_, _, _, looksLike) => looksLike
    case other => fail(s"wrong error: $other")

  // ════════════════════════════════════════════ one card named ══════

  /** The only conclusion worth acting on, and the two rulings that govern how it is said.
    */
  test("when one vault card has the recorded fingerprint, it is named, with the tag to copy in") {
    val spec  = specOf(k1, "back")
    val anki  = brokenNoteCarrying(spec.spec, Vector(OwnedTag.sha(Planner.contentHash(spec.spec))))
    val error = errorFor(anki, Vector(spec))

    assertEquals(recoveryIn(error), IdentityRecovery.LooksLike(k1))

    val message = error.describe
    // THE ENCODED TAG IN FULL. The encoding escapes spaces, `/`, `:` and Anki's two wildcards, so
    // naming the card without printing its tag leaves the reader unable to act on the answer.
    assert(message.contains(TagCodec.encode(k1).value), s"no tag to copy in: $message")
    // AND STILL TENTATIVE. The tool did not make this change and must not read as though it had.
    assert(
      message.contains("most likely") && message.contains("if you agree"),
      s"reads as a statement of fact rather than a suggestion: $message",
    )
  }

  // ═════════════════════════════════════ nothing to compare with ══════

  /** THE CASE THAT HAD NO WORDS AT ALL. The note records no fingerprint, so the vault was never
    * consulted — quite different from consulting it and coming back empty, which is what the
    * silence used to be indistinguishable from.
    */
  test("a note recording no fingerprint says so, rather than reporting an empty search") {
    val spec  = specOf(k1, "back")
    val anki  = brokenNoteCarrying(spec.spec, Vector.empty)
    val error = errorFor(anki, Vector(spec))

    assertEquals(recoveryIn(error), IdentityRecovery.NoUsableFingerprint)
    assert(
      error.describe.contains("no single usable content fingerprint"),
      s"the report does not say that there was nothing to compare against: ${error.describe}",
    )
  }

  /** THE MERGE, PINNED. A note carrying TWO `sha::` tags also arrives at `NoUsableFingerprint`,
    * and that is a decision rather than an accident: `ObservedCard.recordedSha` refuses to choose
    * between two hashes, because picking one would answer "has this changed?" correctly about
    * half the time and skip a note that needs writing.
    *
    * THIS TEST EXISTS TO STOP THE MERGE BEING "FIXED". Splitting the case in two would require
    * the producer to distinguish situations the observation deliberately does not — so the second
    * constructor would be unreachable, and the reader of the enum would be told about a
    * distinction the tool cannot make.
    */
  test("a note carrying two fingerprints reaches the same answer as one carrying none") {
    val spec = specOf(k1, "back")
    val two  = brokenNoteCarrying(
      spec.spec,
      Vector(OwnedTag.sha(Planner.contentHash(spec.spec)), OwnedTag.sha("bbbbbbbbbbbbbbbb")),
    )
    val none = brokenNoteCarrying(spec.spec, Vector.empty)

    assertEquals(recoveryIn(errorFor(two, Vector(spec))), IdentityRecovery.NoUsableFingerprint)
    assertEquals(
      recoveryIn(errorFor(two, Vector(spec))),
      recoveryIn(errorFor(none, Vector(spec))),
      "two hashes and no hash stopped being one answer, so the planner is now choosing between " +
        "hashes somewhere",
    )
  }

  // ═══════════════════════════════════ looked, and found nothing ══════

  /** The vault WAS consulted and the content has moved on. Naming the fingerprint is what makes
    * this actionable: it is the string a reader searches Anki for.
    */
  test("a fingerprint matching no vault card is reported as a search that came back empty") {
    val recorded = specOf(k1, "back")
    val anki     = brokenNoteCarrying(recorded.spec, Vector(OwnedTag.sha("0000000000000000")))
    val error    = errorFor(anki, Vector(specOf(k1, "edited since")))

    assertEquals(recoveryIn(error), IdentityRecovery.FingerprintMatchedNothing("0000000000000000"))
    assert(
      error.describe.contains("0000000000000000"),
      s"the report does not name the fingerprint it searched on: ${error.describe}",
    )
    assert(
      error.describe.contains("content has changed"),
      s"the report does not say what an empty search means: ${error.describe}",
    )
  }

  // ═══════════════════════════════ looked, and found too many ══════

  /** Two cards with identical content hash alike, so the tool declines — as it always did. What
    * is new is that declining is now distinguishable from having nothing to decline with.
    *
    * NO LIST OF MAYBES: the message names the COUNT, never the cards. A menu a reader has no
    * basis for choosing from is how a report stops being read, and choosing wrongly here moves
    * review history irreversibly.
    */
  test("a fingerprint several vault cards share is reported as undecidable, naming no card") {
    val spec = specOf(k1, "back")
    val twin = specOf(k2, "back") // identical fields, so an identical fingerprint
    assertEquals(
      Planner.contentHash(spec.spec),
      Planner.contentHash(twin.spec),
      "test premise broken: these two fixtures were supposed to hash alike",
    )

    val anki  = brokenNoteCarrying(spec.spec, Vector(OwnedTag.sha(Planner.contentHash(spec.spec))))
    val error = errorFor(anki, Vector(spec, twin))

    assertEquals(
      recoveryIn(error),
      IdentityRecovery.FingerprintMatchedSeveral(Planner.contentHash(spec.spec), NonEmptyVector.of(k1, k2)),
    )

    val message = error.describe
    assert(message.contains("2 cards"), s"the report does not say how many matched: $message")
    assert(
      !message.contains(TagCodec.encode(k1).value) && !message.contains(TagCodec.encode(k2).value),
      s"the report offered a menu of tags to choose between: $message",
    )
    assert(
      !message.contains("most likely"),
      s"an undecidable match was reported as a suggestion: $message",
    )
  }

  // ══════════════════════════ the property the whole change is for ══════

  /** ⚠️ THE TEST THAT WOULD HAVE CAUGHT THE ORIGINAL DEFECT, and the one to keep if any of the
    * others are ever merged away.
    *
    * Each of the three empty-handed conclusions must reach the reader as a DIFFERENT sentence.
    * Asserting that each message is non-empty would pass with all three identical, which is
    * exactly the state this change replaces: three conclusions, one face.
    */
  test("the three ways a recovery can come up empty read as three different reports") {
    val spec = specOf(k1, "back")
    val twin = specOf(k2, "back")

    val messages = Vector(
      "no fingerprint recorded" ->
        errorFor(brokenNoteCarrying(spec.spec, Vector.empty), Vector(spec)).describe,
      "recorded, matched nothing" ->
        errorFor(
          brokenNoteCarrying(spec.spec, Vector(OwnedTag.sha("0000000000000000"))),
          Vector(specOf(k1, "edited since")),
        ).describe,
      "recorded, matched several" ->
        errorFor(
          brokenNoteCarrying(spec.spec, Vector(OwnedTag.sha(Planner.contentHash(spec.spec)))),
          Vector(spec, twin),
        ).describe,
    )

    assertEquals(
      messages.map(_._2).distinct.size,
      3,
      "two or more of these conclusions reach the reader as the same sentence: " +
        messages.map((label, m) => s"\n  $label -> $m").mkString,
    )
  }
