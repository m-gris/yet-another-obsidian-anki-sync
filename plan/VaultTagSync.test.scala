package obsidiananki.plan

import cats.data.NonEmptyVector
import obsidiananki.anki.{Anki, AnkiError, DeckPath, InMemoryAnki}
import obsidiananki.model.*

/** OBSIDIAN IS THE SOURCE OF TRUTH AND ANKI FOLLOWS — ruled by Marc, 2026-08-29.
  *
  * Applied to an author's own tags, that means: a tag added in a note's frontmatter appears on
  * the note's cards, and a tag REMOVED from the frontmatter is removed from them. This file is
  * the second half — the first, a tag reaching a newly created note, is in `Planner.test.scala`.
  *
  * THE LAST TEST IS THE ONE THE NAMESPACE EXISTS FOR, and it is the reason a verbatim tag was
  * refused. Anki writes `leech` by itself when a card lapses too often, and `marked` when a card
  * is marked in the reviewer, both onto notes this tool generated. "Make Anki match the vault"
  * must not reach them.
  */
class VaultTagSyncTest extends munit.FunSuite:

  private type Result[A] = Either[AnkiError, A]

  private val deck: DeckPath = DeckPath(NonEmptyVector.of("Obsidian", "Coupling"))

  private def key(id: String, segments: String*): CardKey =
    CardKey(
      NoteId.fromFrontmatter(id).fold(e => fail(s"note id: $e"), identity),
      CardPath.Headings(
        HeadingPath(
          NonEmptyVector.fromVectorUnsafe(
            segments.toVector.map(s => HeadingSegment.fromExtractedText(s).fold(e => fail(s"$e"), identity))
          )
        )
      ),
    )

  private val k: CardKey = key("n1", "Coupling", "Temporal coupling")

  private def body(s: String): Body = Body.fromExtracted(s).getOrElse(fail("empty test body"))

  /** A note carrying whichever frontmatter tags the test is about. */
  private def noteTagged(tags: String*): SourcedSpec =
    SourcedSpec(
      CardSpec.TwoField(k, "front", body("back"), TwoFieldDirections.Forward, "Coupling"),
      SourceRef("Note.md", 1, SourceKind.Heading),
      Vector.empty,
      RecallText.none,
      tags.toVector.map(VaultTag.read),
    )

  private def observe(anki: InMemoryAnki): ObservedState =
    Observer.observe[Result](anki).fold(e => fail(s"observe failed: $e"), identity)

  /** A whole sync, through the PRODUCTION note builder. A local one would decide the tags this
    * file is asserting about, so the test would be asserting about itself.
    */
  private def sync(anki: InMemoryAnki, specs: SourcedSpec*): ExecutionReport =
    val plan = Planner
      .plan(VaultScan.from(specs.toVector, Vector.empty), observe(anki), _ => deck, Planner.newNoteFor)
      .fold(errs => fail(s"plan: ${errs.map(_.describe)}"), identity)
    Executor.run[Result](plan, anki, RetypePolicy.Apply, Set.empty).fold(e => fail(s"run: $e"), identity)

  private def tagsIn(anki: InMemoryAnki): Set[String] =
    val ids = anki.ownedNotes.fold(e => fail(s"$e"), identity)
    anki.notesInfo(ids).fold(e => fail(s"$e"), identity).flatMap(_.tags).toSet

  // ══════════════════════════════════════════════════════════════════════════════════

  test("a tag added in the vault reaches a note that already exists") {
    val anki = InMemoryAnki()
    sync(anki, noteTagged())
    assert(!tagsIn(anki).exists(_.startsWith("obsidian::")), s"unexpected tags: ${tagsIn(anki)}")

    sync(anki, noteTagged("backend/scala"))
    assert(tagsIn(anki).contains("obsidian::backend::scala"), s"the tag never arrived: ${tagsIn(anki)}")
  }

  /** THE HALF MARC'S RULING DECIDES. Anki follows, so a tag deleted from the frontmatter is
    * deleted from the note — a card must not go on appearing in a filtered deck it was removed
    * from, which is a difference nothing would ever show you.
    */
  test("a tag removed from the vault is removed from the note") {
    val anki = InMemoryAnki()
    sync(anki, noteTagged("backend/scala", "maths/topology"))
    assertEquals(
      tagsIn(anki).filter(_.startsWith("obsidian::")),
      Set("obsidian::backend::scala", "obsidian::maths::topology"),
    )

    sync(anki, noteTagged("backend/scala"))
    assertEquals(
      tagsIn(anki).filter(_.startsWith("obsidian::")),
      Set("obsidian::backend::scala"),
      "the deleted tag survived, so the note still says something the vault does not",
    )
  }

  test("removing the last tag leaves the note with none, rather than doing nothing") {
    val anki = InMemoryAnki()
    sync(anki, noteTagged("backend/scala"))
    sync(anki, noteTagged())
    assertEquals(tagsIn(anki).filter(_.startsWith("obsidian::")), Set.empty[String])
  }

  /** A RE-RUN OVER AN UNCHANGED VAULT MUST DO NOTHING, which is this tool's oldest guarantee.
    * A tag comparison that reported a difference every time — through case, or ordering — would
    * make every run rewrite every note while reporting work it did not need to do.
    */
  test("syncing twice with the same tags is the second time a no-op") {
    val anki = InMemoryAnki()
    sync(anki, noteTagged("Backend/Scala", "maths/topology"))
    val second = sync(anki, noteTagged("Backend/Scala", "maths/topology"))
    assertEquals(second.applied, Vector.empty, s"a settled vault still produced work: ${second.applied}")
  }

  /** THE TEST THE NAMESPACE EXISTS FOR.
    *
    * Anki adds `leech` on its own when a card lapses too often, and `marked` when a card is
    * marked in the reviewer — both onto notes this tool generated. Carrying tags verbatim would
    * have made "remove what the vault no longer names" delete them, destroying a record that can
    * only be earned back by failing the reviews again. Under a prefix, they are not even
    * candidates.
    */
  test("Anki's own tags survive a sync that removes every tag the vault named") {
    val anki = InMemoryAnki()
    sync(anki, noteTagged("backend/scala"))

    val ids = anki.ownedNotes.fold(e => fail(s"$e"), identity)
    anki
      .addTags(ids, Vector(OwnedTag.unsafeFromString("leech"), OwnedTag.unsafeFromString("marked")))
      .fold(e => fail(s"$e"), identity)

    sync(anki, noteTagged())

    val after = tagsIn(anki)
    assert(after.contains("leech"), s"Anki's leech tag was deleted by this tool: $after")
    assert(after.contains("marked"), s"Anki's marked tag was deleted by this tool: $after")
    assert(!after.exists(_.startsWith("obsidian::")), s"the vault's tag survived removal: $after")
  }
