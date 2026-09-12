package obsidiananki.plan

import cats.data.NonEmptyVector
import obsidiananki.model.*
import obsidiananki.plan.SectionChain.{NoRecall, NoSectionChain}

/** WHICH NODES A NOTE STILL HOLDS, AND WHEN THE ANSWER IS THAT NOBODY KNOWS.
  *
  * ==What this suite is FOR==
  *
  * [[NodeCensus]] exists to let the move survey say "the old concept's path survives nowhere",
  * which is the one sentence separating a relabelled concept (history follows) from a re-parented
  * descriptor (history must never follow). So the tests come in two halves, and the second matters
  * as much as the first:
  *
  *   - the census SEES the two kinds of node a key cannot show — a heading that kept only prose,
  *     and a table row, which is no heading at all;
  *   - and it REFUSES to answer whenever the run could not read what it would have to have read.
  *     An `Unsurveyable` that leaked through as an empty node set would read as "survives nowhere"
  *     and move review history onto a different card.
  */
class NodeCensusTest extends munit.FunSuite:

  // ------------------------------------------------------------------ fixtures ----

  def noteId(id: String): NoteId =
    NoteId.fromFrontmatter(id).getOrElse(fail(s"unusable test note id '$id'"))

  def key(id: String, segments: String*): CardKey =
    CardKey(
      noteId(id),
      CardPath.Headings(
        HeadingPath(
          NonEmptyVector.fromVectorUnsafe(
            segments.toVector.map(s =>
              HeadingSegment.fromExtractedText(s).getOrElse(fail(s"unusable test segment '$s'"))
            )
          )
        )
      ),
    )

  def propertyKey(id: String, name: String): CardKey =
    CardKey(
      noteId(id),
      CardPath.Property(PropertyName.fromFrontmatter(name).getOrElse(fail(s"unusable '$name'"))),
    )

  def blockKey(id: String, anchor: String): CardKey =
    CardKey(noteId(id), CardPath.Block(BlockAnchor.read(anchor).getOrElse(fail(s"unusable '$anchor'"))))

  def noteKey(id: String): CardKey = CardKey(noteId(id), CardPath.Note)

  val where: SourceRef = SourceRef("Kafka.md", 7, SourceKind.Heading)

  /** A card at a key. WHAT the card is does not matter here and must not: a census reads key
    * PATHS, so a fixture that varied note types would be varying something this type cannot see.
    */
  def sourced(k: CardKey): SourcedSpec =
    SourcedSpec(
      CardSpec.TwoField(
        k,
        "Front",
        Body.fromExtracted("Back.").getOrElse(fail("empty test body")),
        TwoFieldDirections.Forward,
        "Context",
      ),
      where,
      NoSectionChain,
      NoRecall,
      Vector.empty,
    )

  def censusOf(
      built: Vector[CardKey],
      failures: Vector[BuildFailure],
      outlines: NodeCensus.Outlines,
  ): NodeCensus =
    NodeCensus.of(VaultScan.from(built.map(sourced), failures), outlines)

  def nodesOf(census: NodeCensus, id: String): Set[Vector[String]] =
    census.nodesOf(noteId(id)) match
      case NodeCensus.Answer.Surveyed(nodes)  => nodes
      case NodeCensus.Answer.Unsurveyable(why) => fail(s"expected a surveyed census, got: $why")

  def unsurveyableReason(census: NodeCensus, id: String): String =
    census.nodesOf(noteId(id)) match
      case NodeCensus.Answer.Unsurveyable(why) => why
      case NodeCensus.Answer.Surveyed(nodes)   => fail(s"expected no answer, got $nodes")

  // ===================================== WHAT THE KEYS ALONE COULD NEVER SHOW ====

  test("a heading that kept only prose is a node, though no key anywhere mentions it") {
    // THE CASE THE WHOLE TYPE EXISTS FOR. `# Kafka` still stands and holds a paragraph; every
    // descriptor under it was moved elsewhere. Nothing in `scan.specs` names it.
    val census = censusOf(
      built = Vector(key("n1", "nats", "definition")),
      failures = Vector.empty,
      outlines = Map(noteId("n1") -> Vector(Vector("kafka"), Vector("nats"), Vector("nats", "definition"))),
    )
    assert(nodesOf(census, "n1").contains(Vector("kafka")), nodesOf(census, "n1"))
  }

  test("a table row is a node, though it is no heading: it arrives as a key's proper prefix") {
    // `## Cost / benefit`'s pair cards key as `…/{row concept}/{column header}`, so the ROW is
    // a node that exists in no heading tree and in no key of its own.
    val census = censusOf(
      built = Vector(key("n1", "cost / benefit", "queue", "benefit")),
      failures = Vector.empty,
      outlines = Map(noteId("n1") -> Vector(Vector("cost / benefit"))),
    )
    assertEquals(
      nodesOf(census, "n1"),
      Set(Vector("cost / benefit"), Vector("cost / benefit", "queue")),
    )
  }

  test("a key does NOT make its own position a node — only what sits above it") {
    // A node here is a place a card can hang OFF. Whether the stranded card's own key came back
    // is a question the survey has already answered by the time it asks this one; what it asks
    // about is the PARENT.
    val census = censusOf(
      built = Vector(key("n1", "cost / benefit", "queue", "benefit")),
      failures = Vector.empty,
      outlines = Map.empty,
    )
    assert(!nodesOf(census, "n1").contains(Vector("cost / benefit", "queue", "benefit")))
  }

  test("a card that failed to build is present and broken, so what is above it is still a node") {
    // The same rule orphan inference obeys: a `KeyKnown` failure is a card the vault accounts
    // for. Reading its absence as a vanished node would say a concept had gone because one of
    // its cards would not build.
    val census = censusOf(
      built = Vector.empty,
      failures = Vector(
        BuildFailure.KeyKnown(key("n1", "cost / benefit", "queue", "benefit"), where, "deck: nope")
      ),
      outlines = Map.empty,
    )
    assertEquals(
      nodesOf(census, "n1"),
      Set(Vector("cost / benefit"), Vector("cost / benefit", "queue")),
    )
  }

  test("a property, a block and the note itself contribute no node, and never an empty one") {
    // None of the three sits inside a chain: a property and the whole-note card anchor at the
    // NOTE, and a block deliberately records no heading above it. An empty node would be the
    // note's own root, which is not a place this census speaks about.
    val census = censusOf(
      built = Vector(propertyKey("n1", "special-case-of"), noteKey("n1"), blockKey("n1", "forearm")),
      failures = Vector.empty,
      outlines = Map(noteId("n1") -> Vector.empty),
    )
    assertEquals(nodesOf(census, "n1"), Set.empty[Vector[String]])
  }

  test("nodes are scoped to the note that holds them") {
    val census = censusOf(
      built = Vector(key("n2", "cost / benefit", "queue", "benefit")),
      failures = Vector.empty,
      outlines = Map(noteId("n1") -> Vector(Vector("kafka")), noteId("n2") -> Vector(Vector("cost / benefit"))),
    )
    assertEquals(nodesOf(census, "n1"), Set(Vector("kafka")))
    assertEquals(
      nodesOf(census, "n2"),
      Set(Vector("cost / benefit"), Vector("cost / benefit", "queue")),
    )
  }

  test("a note the vault no longer holds at all is SURVEYED and empty, not unanswerable") {
    // The deleted file. The scan was complete, so "this id appears nowhere" is a finding rather
    // than a gap — and it is exactly the finding that lets a relabel-in-place be followed.
    val census = censusOf(
      built = Vector(key("n1", "kafka")),
      failures = Vector.empty,
      outlines = Map(noteId("n1") -> Vector(Vector("kafka"))),
    )
    assertEquals(nodesOf(census, "gone"), Set.empty[Vector[String]])
  }

  // ================================== WHEN THE RUN MAY NOT ANSWER AT ALL ====

  test("one unreadable file makes EVERY note's census unanswerable, the ones it read included") {
    // The argument `Planner.plan`'s orphan branch already makes: frontmatter that will not parse
    // might have carried any id, so the file may hold nodes belonging to a note we think we know.
    val census = censusOf(
      built = Vector(key("n1", "kafka")),
      failures = Vector(BuildFailure.FileUnreadable("Other.md", "frontmatter: bad yaml")),
      outlines = Map(noteId("n1") -> Vector(Vector("kafka"))),
    )
    // THE REASON NAMES THE STATE RATHER THAN THE FILE, following `MarkedHeadings.CouldNotLook`:
    // the run reports that failure by name in its own block, and a second wording of it here
    // would be a third place one failure is put into words.
    assert(
      unsurveyableReason(census, "n1").contains("could not be read"),
      s"the reason must say what could not be done: ${unsurveyableReason(census, "n1")}",
    )
    assert(unsurveyableReason(census, "n2").nonEmpty)
  }

  test("a failure that shelters one note makes THAT note unanswerable and leaves its siblings alone") {
    // Three failures say "this note's heading tree is not the one its author wrote, or could not
    // be read at all" — and a census read off such a tree would be answering about a different
    // document.
    val outlines = Map(
      noteId("n1") -> Vector(Vector("kafka")),
      noteId("n2") -> Vector(Vector("nats")),
    )
    Vector(
      BuildFailure.KeyUnderivableInFile(noteId("n1"), where, "markdown: will not parse"),
      BuildFailure.KeyMisfiledInFile(noteId("n1"), where, "a heading is not read as a heading"),
      BuildFailure.HeadingUnreadInFile(noteId("n1"), where, "a marked heading is not read as one"),
    ).foreach { failure =>
      val census = censusOf(Vector(key("n1", "kafka")), Vector(failure), outlines)
      assert(
        unsurveyableReason(census, "n1").nonEmpty,
        s"$failure left note n1 answerable",
      )
      assertEquals(nodesOf(census, "n2"), Set(Vector("nats")), s"$failure reached note n2")
    }
  }

  test("a failure that shelters nothing says nothing about the node tree either") {
    // Every remaining build failure. Each is about a marker, a declaration block or a missing id
    // — none of them about whether a heading exists — so the census stays answerable. Listed
    // longhand so that widening this set is a decision somebody makes rather than one that
    // happens.
    Vector(
      BuildFailure.MarkerNotOnHeading("Kafka.md", "the marker is in the frontmatter"),
      BuildFailure.MarkerMisspelled("Kafka.md", "flashard/2way"),
      BuildFailure.ClozeBlockUnanchored("Kafka.md", 3, "no ^blockid"),
      BuildFailure.MarkedWithoutNoteId("Kafka.md", "no id"),
      BuildFailure.EdgeVocabularyUnusable("Kafka.md", "unusable rule"),
      BuildFailure.MarkerUnknowable("Kafka.md", "markdown will not parse and there is no id"),
    ).foreach { failure =>
      val census = censusOf(
        Vector(key("n1", "kafka")),
        Vector(failure),
        Map(noteId("n1") -> Vector(Vector("kafka"))),
      )
      assertEquals(nodesOf(census, "n1"), Set(Vector("kafka")), s"$failure suppressed the census")
    }
  }
