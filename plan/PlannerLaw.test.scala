package obsidiananki.plan

import cats.data.NonEmptyVector
import obsidiananki.anki.*
import obsidiananki.model.*
import obsidiananki.plan.SectionChain.{NoRecall, NoSectionChain}
import org.scalacheck.{Arbitrary, Gen, Shrink}
import org.scalacheck.Prop.forAll

/** Idempotence as a PROPERTY, not as three examples.
  *
  * Hand-built scenarios encode the author's assumptions, and the assumption is exactly what
  * needs attacking — a point already proved in this project the hard way, when a
  * hand-written orphan test turned out to be passing for the wrong reason and would have
  * kept passing with the feature deleted.
  *
  * The generators lean deliberately towards nasty input: heading text that contains the
  * path separator, the search wildcards, spaces, unicode and case variation, and ids that
  * repeat across cards so that whole notes, not just single cards, move together.
  */
class PlannerLawTest extends munit.ScalaCheckSuite:

  override def scalaCheckTestParameters =
    super.scalaCheckTestParameters.withMinSuccessfulTests(200)

  /** SHRINKING IS DISABLED, deliberately.
    *
    * ScalaCheck's default shrinker walks a failing case towards a minimal one. On these
    * nested generated structures that search does not terminate in any useful time — a
    * failing property HANGS instead of reporting, which was discovered while trying to
    * verify these properties by mutation: the mutant could not be shown to die because the
    * suite never finished.
    *
    * A property that cannot report its own failure is worse than no property, so the raw
    * counterexample is preferred over a minimal one that never arrives.
    */
  given [A]: Shrink[A] = Shrink.shrinkAny

  // ---------------------------------------------------------------- generators ----

  /** Segment text chosen to exercise the encoding rather than to look tidy. */
  val genSegmentText: Gen[String] = Gen.oneOf(
    Gen.const("Definition"),
    Gen.const("Cost / benefit"),
    Gen.const("CAP Theorem"),
    Gen.const("a_b*c"),
    Gen.const("Café ☕"),
    Gen.const("Costs"),
    Gen.const("COSTS"),
    Gen.const("a  b"),
    Gen.const("100% of the time"),
    Gen.alphaLowerStr.suchThat(_.nonEmpty).map(_.take(8)),
  )

  val genNoteIdText: Gen[String] =
    Gen.oneOf("n1", "n2", "fix-multi-topic", "2026-08-18", "MOC-Design-Gurus")

  val genKey: Gen[CardKey] =
    for
      idText <- genNoteIdText
      depth  <- Gen.choose(1, 3)
      segs   <- Gen.listOfN(depth, genSegmentText)
      id     <- Gen.const(NoteId.fromFrontmatter(idText).toOption).suchThat(_.isDefined).map(_.get)
      parsed = segs.flatMap(s => HeadingSegment.fromExtractedText(s).toOption).toVector
      if parsed.nonEmpty
    yield CardKey(id, CardPath.Headings(HeadingPath(NonEmptyVector.fromVectorUnsafe(parsed))))

  val genBody: Gen[Body] =
    Gen.oneOf("Some body.", "Another body.", "Operations appear instantaneous.", "x")
      .map(s => Body.fromExtracted(s).get)

  def genSpecFor(k: CardKey): Gen[CardSpec] =
    Gen.oneOf(
      for
        f <- Gen.oneOf("front", "Front", "term")
        b <- genBody
        d <- Gen.oneOf(TwoFieldDirections.Forward, TwoFieldDirections.Both)
      yield CardSpec.TwoField(k, f, b, d, "Coupling"),
      for
        b <- genBody
        d <- Gen.oneOf(ThreeFieldDirections.Default, ThreeFieldDirections.All)
        // VARIED, so both shapes go through every property in this file: a card built from a
        // TABLE carries the header naming what kind of thing its concept is, and one built
        // from HEADINGS has nothing to put there.
        l <- Gen.oneOf("", "Bone", "Pattern")
      yield CardSpec.ThreeField(k, "Concept", "Descriptor", b, d, "Coupling", l),
    )

  val genSourced: Gen[SourcedSpec] =
    for
      k    <- genKey
      spec <- genSpecFor(k)
      line <- Gen.choose(1, 200)
      kind <- Gen.oneOf(SourceKind.Heading, SourceKind.TablePair, SourceKind.TableRow)
    yield SourcedSpec(spec, SourceRef("Note.md", line, kind), NoSectionChain, NoRecall)

  /** A scan with no duplicate keys — duplicates are a separate, already-tested rejection,
    * and including them here would only exercise that path.
    */
  val genScan: Gen[VaultScan] =
    Gen
      .listOfN(6, genSourced)
      .map { specs =>
        val deduped = specs.groupBy(_.key).values.map(_.head).toVector
        VaultScan.from(deduped, Vector.empty)
      }

  given Arbitrary[VaultScan] = Arbitrary(genScan)

  // ---------------------------------------------------------------- harness ----

  val defaultDeck: DeckPath = DeckPath(NonEmptyVector.of("Obsidian", "System-Design"))

  def newNoteOf(s: SourcedSpec, d: DeckPath, sha: String): NewNote =
    NewNote(
      noteType = s.spec.noteTypeName,
      deck = d,
      fields = s.spec.fields,
      tags = NonEmptyVector.of(TagCodec.encode(s.key), OwnedTag.sha(sha)),
    )

  def planOf(scan: VaultScan, anki: InMemoryAnki, deck: DeckPath = defaultDeck): Plan =
    val observed = Observer.observe(anki).fold(e => fail(s"observe failed: $e"), identity)
    Planner
      .plan(scan, observed, _ => deck, newNoteOf)
      .fold(errs => fail(s"plan errors: ${errs.map(_.describe)}"), identity)

  /** `RetypePolicy.Apply`, so that a generated scan which happens to move a note between note
    * types is ATTEMPTED here rather than set aside. Deferring would leave the action unapplied
    * and the property below would then fail for a reason about policy rather than about the
    * planner.
    */
  def runPlan(p: Plan, anki: InMemoryAnki): Vector[ExecutionFailure] =
    Executor
      .run(p, anki, RetypePolicy.Apply)
      .fold(e => fail(s"execution aborted: $e"), identity)
      .failures

  // ---------------------------------------------------------------- properties ----

  property("plan-apply-plan is empty, for any vault") {
    forAll { (scan: VaultScan) =>
      val anki = InMemoryAnki()
      val failures = runPlan(planOf(scan, anki), anki)
      failures.isEmpty && planOf(scan, anki).actions.isEmpty
    }
  }

  property("applying twice is the same as applying once") {
    forAll { (scan: VaultScan) =>
      val once  = InMemoryAnki()
      runPlan(planOf(scan, once), once)
      val afterOnce = Observer.observe(once).toOption.get.notes.map(_.key).toSet

      val twice = InMemoryAnki()
      runPlan(planOf(scan, twice), twice)
      runPlan(planOf(scan, twice), twice)
      val afterTwice = Observer.observe(twice).toOption.get.notes.map(_.key).toSet

      afterOnce == afterTwice
    }
  }

  property("a partially applied plan re-plans to the remainder and then converges") {
    forAll { (scan: VaultScan) =>
      val anki = InMemoryAnki()
      val full = planOf(scan, anki)
      val half = full.actions.take(full.actions.size / 2)

      runPlan(Plan(half, full.orphanInference, Vector.empty, full.parked), anki)
      val resumed = planOf(scan, anki)

      // The remainder is no bigger than what was left, and finishing it converges.
      val remainderIsSane = resumed.actions.size == full.actions.size - half.size
      runPlan(resumed, anki)
      remainderIsSane && planOf(scan, anki).actions.isEmpty
    }
  }

  property("every derived tag is one Anki can actually store") {
    forAll { (scan: VaultScan) =>
      scan.specs.forall { s =>
        val tag = TagCodec.encode(s.key).value
        !tag.exists(_.isWhitespace) && !tag.contains("*") && !tag.contains("_")
      }
    }
  }

  property("encode/decode round-trips for any generated key") {
    forAll(genKey) { (k: CardKey) =>
      TagCodec.decode(TagCodec.encode(k).value) == Right(k)
    }
  }

  property("removing everything flags exactly what was there, and never twice") {
    forAll { (scan: VaultScan) =>
      val anki = InMemoryAnki()
      runPlan(planOf(scan, anki), anki)

      val empty   = VaultScan.from(Vector.empty, Vector.empty)
      val flagged = planOf(empty, anki)
      runPlan(flagged, anki)

      // Every card is flagged once, and a second pass finds nothing more to flag.
      flagged.actions.size == scan.specs.size && planOf(empty, anki).actions.isEmpty
    }
  }

  // ============================================== SAFETY PROPERTIES ====
  // The six above say the right things HAPPEN. These two say the wrong things DO NOT,
  // which is the asymmetry where data loss lives.

  /** THE property that Marc's flag-then-prune ruling exists to protect.
    *
    * A card you did not delete must never reach the prune list, no matter what else
    * changed around it. Stated over an arbitrary vault rather than the three hand-built
    * suppression scenarios, because those encode my assumptions about how a card goes
    * missing — and the assumption is what needs attacking.
    */
  /** How a card can go missing from a scan WITHOUT having been deleted. Generating these is
    * the whole point: a generator that only ever produces clean scans cannot attack the
    * suppression logic, and a property that cannot attack it is decorative.
    */
  enum Disappearance:
    case Built                 // still there
    case FailedButKeyed        // (a) key derivable, card unbuildable
    case KeyUnderivable        // (b) no key, but the note id is known
    case Deleted               // genuinely removed — the ONLY case that may be flagged

  val genDisappearance: Gen[Disappearance] =
    Gen.oneOf(Disappearance.Built, Disappearance.FailedButKeyed, Disappearance.KeyUnderivable, Disappearance.Deleted)

  property("SAFETY: no live card is ever flagged") {
    forAll(genScan, Gen.listOfN(8, genDisappearance)) { (scan, fates) =>
      val anki = InMemoryAnki()
      runPlan(planOf(scan, anki), anki)

      val ref = SourceRef("Note.md", 1, SourceKind.Heading)
      val assigned = scan.specs.zip(LazyList.continually(fates).flatten)

      val stillBuilt = assigned.collect { case (s, Disappearance.Built) => s }
      val failures = assigned.collect {
        case (s, Disappearance.FailedButKeyed) => BuildFailure.KeyKnown(s.key, ref, "empty body")
        case (s, Disappearance.KeyUnderivable) =>
          BuildFailure.KeyUnderivableInFile(s.key.noteId, ref, "heading extracted to nothing")
      }
      val next = VaultScan.from(stillBuilt, failures)

      val flagged = planOf(next, anki).actions.collect { case SyncAction.Flag(k, _) => k }.toSet

      // A card may be flagged ONLY if it was genuinely deleted: not built, not failed-but-
      // keyed, and not belonging to a note whose keys we could not enumerate.
      val protectedKeys =
        next.builtKeys ++ next.failedKeys ++
          scan.specs.map(_.key).filter(k => next.suppressedNoteIds.contains(k.noteId)).toSet

      flagged.intersect(protectedKeys).isEmpty
    }
  }

  /** And the partial-scan level: a run that could not read a file must flag NOTHING, and
    * must say that it could not look rather than reporting an empty orphan set.
    */
  property("SAFETY: a partial scan flags nothing at all") {
    forAll { (scan: VaultScan) =>
      val anki = InMemoryAnki()
      runPlan(planOf(scan, anki), anki)

      val partial = VaultScan.from(
        Vector.empty,
        Vector(BuildFailure.FileUnreadable("Broken.md", "unreadable")),
      )
      val plan = planOf(partial, anki)
      !plan.actions.exists(_.isInstanceOf[SyncAction.Flag]) &&
      plan.orphanInference.isInstanceOf[OrphanInference.SuppressedIncompleteScan]
    }
  }

  /** The stronger form: across a SEQUENCE of edits that never removes a card, no note is
    * ever flagged at any point. Editing bodies, moving decks and re-running must not put a
    * live card on the prune list.
    */
  property("SAFETY: no card is flagged across any edit sequence that removes nothing") {
    forAll(genScan, Gen.listOfN(3, Gen.oneOf(true, false))) { (scan, edits) =>
      val anki = InMemoryAnki()
      runPlan(planOf(scan, anki), anki)

      edits.zipWithIndex.forall { (editBodies, i) =>
        // Same key set every time — only content and deck move.
        val mutated = VaultScan.from(
          scan.specs.map { s =>
            if !editBodies then s
            else
              s.spec match
                case CardSpec.TwoField(k, f, _, d, c) =>
                  s.copy(spec = CardSpec.TwoField(k, f, Body.fromExtracted(s"edit $i").get, d, c))
                case _ => s
          },
          Vector.empty,
        )
        val deck = if editBodies then DeckPath(NonEmptyVector.of("Obsidian", s"Moved$i")) else defaultDeck
        val plan = planOf(mutated, anki, deck)
        val flaggedAny = plan.actions.exists(_.isInstanceOf[SyncAction.Flag])
        runPlan(plan, anki)
        !flaggedAny
      }
    }
  }

  /** Tags a person applied themselves must survive every operation we perform.
    *
    * Generated adversarially rather than hand-picked: tags that merely LOOK like ours, that
    * differ only in case (Anki folds case, so this is the subtle one), and deep hierarchies
    * containing our separator.
    */
  val genForeignTag: Gen[String] = Gen.oneOf(
    "leech",
    "marked",
    "Obsidian_to_Anki",
    "source::x",       // looks like src:: but is not
    "src2::x",         // shares a prefix but not a component
    "srcx",
    "my::own::hierarchy",
    "deep::a::b::c::d",
    "SOURCE::X",
    "shadow::thing",   // starts with 'sha' as a substring, not as a component
  )

  property("SAFETY: foreign tags survive every operation we perform") {
    forAll(genScan, Gen.listOfN(4, genForeignTag)) { (scan, foreign) =>
      val anki = InMemoryAnki()
      runPlan(planOf(scan, anki), anki)

      // A person tags their cards in Anki, however they like.
      val observedBefore = Observer.observe(anki).toOption.get
      observedBefore.notes.foreach(n => foreign.foreach(t => anki.simulateUserTag(n.note.id, t)))

      // Now put the tool through everything it can do: edit, move, remove, restore.
      val edited = VaultScan.from(
        scan.specs.map { s =>
          s.spec match
            case CardSpec.TwoField(k, f, _, d, c) =>
              s.copy(spec = CardSpec.TwoField(k, f, Body.fromExtracted("changed").get, d, c))
            case _ => s
        },
        Vector.empty,
      )
      runPlan(planOf(edited, anki, DeckPath(NonEmptyVector.of("Obsidian", "Elsewhere"))), anki)
      runPlan(planOf(VaultScan.from(Vector.empty, Vector.empty), anki), anki) // flag everything
      runPlan(planOf(edited, anki), anki)                                     // and unflag it

      // Every foreign tag is still there, on every note, untouched.
      Observer.observe(anki).toOption.get.notes.forall { card =>
        val tags = card.note.tags.toSet
        foreign.forall(tags.contains)
      }
    }
  }

  /** The rule that makes the above possible: ownership is decided by the FIRST component,
    * case-insensitively, because Anki cannot tell `SRC::x` from `src::x`.
    */
  property("only our three prefixes are owned, whatever the case") {
    forAll(genForeignTag) { (t: String) =>
      !OwnedTag.isOwned(t)
    }
  }

  property("our own tags are recognised as ours whatever the case") {
    forAll(Gen.oneOf("src", "sha", "orphaned", "SRC", "Sha", "ORPHANED")) { (p: String) =>
      OwnedTag.isOwned(s"$p::anything")
    }
  }
