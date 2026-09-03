package obsidiananki.plan

import cats.data.NonEmptyVector
import obsidiananki.model.*

/** What a build failure shelters from orphan inference.
  *
  * ==Why this file exists==
  *
  * An orphan is TAGGED AND SUSPENDED — a live card, with its whole review history, silently out
  * of the review queue. So every question about what orphan inference may claim is a question
  * about whether a card the author still wants is about to disappear from their day.
  *
  * Three consumers used to ask three different questions of `BuildFailure`, each with its own
  * partial match and each silent about a case it had not heard of: `VaultScan.from` used
  * `case _ => false`, `failedKeys` and `suppressedNoteIds` used `collect`. A sixth case had to
  * be remembered in three places and forgetting any of them compiled clean. `BuildFailure` grew
  * TWICE in the week before this was written.
  *
  * They are one question. These tests pin the four answers; the compiler pins that a new case
  * must give one, because `shelters` is written longhand under `-Wconf:msg=exhaustive:e`.
  */
class VaultScanTest extends munit.FunSuite:

  private def key(id: String, segments: String*): CardKey =
    CardKey(
      NoteId.fromFrontmatter(id).toOption.get,
      CardPath.Headings(HeadingPath(
        NonEmptyVector.fromVectorUnsafe(
          segments.toVector.map(s => HeadingSegment.fromExtractedText(s).toOption.get)
        )
      )),
    )

  private def noteId(id: String): NoteId = NoteId.fromFrontmatter(id).toOption.get
  private val ref                        = SourceRef("Note.md", 1, SourceKind.Heading)

  // ------------------------------------------------------ what each failure shelters ----

  /** The key was derivable, so the card's Anki counterpart is excluded BY ITSELF. This is the
    * difference between "broken" and "deleted", and it is the whole reason the case exists.
    */
  test("a failure whose key is known shelters exactly that key") {
    val k = key("n1", "Coupling", "Definition")
    assertEquals(BuildFailure.KeyKnown(k, ref, "empty body").shelters, OrphanShelter.OneKey(k))
  }

  /** With no derivable key there is nothing to exclude per-card, so the blast radius widens to
    * the note — the smallest unit still reasonable about.
    */
  test("a failure with no derivable key shelters the whole note") {
    assertEquals(
      BuildFailure.KeyUnderivableInFile(noteId("n1"), ref, "heading extracts to nothing").shelters,
      OrphanShelter.WholeNote(noteId("n1")),
    )
  }

  /** THE SAME SHELTER FOR THE OPPOSITE REASON, which is why it is asserted separately rather than
    * folded into the test above. There, no key could be derived. Here every key WAS derived and
    * they are the wrong ones, because a heading is missing from the tree they are derived from —
    * so the keys the file really owns cannot be enumerated at all.
    *
    * WITHOUT THIS SHELTER THE REMEDY WOULD COST MORE THAN THE DEFECT. Declining to build the
    * file's cards makes every Anki note it has already produced look DELETED, and an inferred
    * orphan is tagged and SUSPENDED — live cards with real review history out of the review queue
    * because of one missing blank line.
    */
  test("a file whose keys were derived from a misread heading tree shelters the whole note") {
    assertEquals(
      BuildFailure.KeyMisfiledInFile(noteId("n1"), ref, "a heading was not read as one").shelters,
      OrphanShelter.WholeNote(noteId("n1")),
    )
  }

  /** THE MILDER OF THE TWO SHELTERS THE SAME THING, and this is the less obvious of the pair. Its
    * note's cards ARE written — the heading this tool could not read is one CommonMark also keeps
    * where it is, so no other key moved — which makes it tempting to shelter nothing, as
    * [[BuildFailure.MarkerNotOnHeading]] does on the grounds that the tool can see exactly what
    * the file produces.
    *
    * That is the one thing it cannot see here. A heading it does not read is a card it cannot
    * enumerate, so if that card already exists in Anki it would be inferred an orphan and
    * SUSPENDED — for a heading the author never deleted.
    */
  test("a file holding a heading this tool cannot read shelters the whole note") {
    assertEquals(
      BuildFailure.HeadingUnreadInFile(noteId("n1"), ref, "a heading was not read as one").shelters,
      OrphanShelter.WholeNote(noteId("n1")),
    )
  }

  /** BOTH OF THESE ARE FILES WITH NO USABLE ID, and a card's identity begins with the id — so
    * neither has ever produced an Anki note and neither owns a key inference could claim.
    * Sheltering nothing is a FINDING here, not an omission: it is why these two cases were
    * carved out of `FileUnreadable` in the first place, rather than a gap left by carving them.
    */
  test("a file with no usable id shelters nothing, because it owns nothing") {
    assertEquals(BuildFailure.MarkerNotOnHeading("A.md", "…").shelters, OrphanShelter.Nothing)
    assertEquals(BuildFailure.MarkedWithoutNoteId("A.md", "…").shelters, OrphanShelter.Nothing)
  }

  /** THE ONE THAT COSTS THE WHOLE VAULT. Frontmatter that will not parse might have carried an
    * id we failed to read, so the file may own notes under a name we cannot see. That is a
    * statement about the tool's knowledge rather than about the file — which is why it is not
    * a bigger `WholeNote` but a different answer entirely.
    */
  test("frontmatter that cannot be parsed shelters an unknowable amount") {
    assertEquals(BuildFailure.FileUnreadable("A.md", "yaml").shelters, OrphanShelter.Unknowable)
  }

  // ------------------------------------------------------ what the consumers read ----

  private def scanOf(failures: BuildFailure*): VaultScan =
    VaultScan.from(Vector.empty, failures.toVector)

  test("only an unknowable shelter degrades the scan") {
    assert(scanOf().canInferOrphans)
    assert(scanOf(BuildFailure.KeyKnown(key("n1", "A"), ref, "x")).canInferOrphans)
    assert(scanOf(BuildFailure.MarkerNotOnHeading("A.md", "x")).canInferOrphans)
    assert(scanOf(BuildFailure.MarkedWithoutNoteId("A.md", "x")).canInferOrphans)
    assert(
      !scanOf(BuildFailure.FileUnreadable("A.md", "x")).canInferOrphans,
      "an unreadable file must suppress orphan inference for the whole vault",
    )
  }

  /** ONE UNKNOWABLE AMONG MANY STILL DEGRADES. Asserted separately because a fold that took the
    * last answer, or the first, rather than the worst, would pass every test above.
    */
  test("an unknowable shelter degrades the scan even when other failures are scoped") {
    val scan = scanOf(
      BuildFailure.KeyKnown(key("n1", "A"), ref, "x"),
      BuildFailure.FileUnreadable("Bad.md", "yaml"),
      BuildFailure.MarkerNotOnHeading("C.md", "x"),
    )
    assert(!scan.canInferOrphans)
  }

  test("the sheltered keys and notes reach the consumers that exclude them") {
    val k    = key("n1", "Coupling", "Definition")
    val scan = scanOf(
      BuildFailure.KeyKnown(k, ref, "empty body"),
      BuildFailure.KeyUnderivableInFile(noteId("n2"), ref, "no heading"),
      BuildFailure.MarkerNotOnHeading("C.md", "x"),
    )
    assertEquals(scan.failedKeys, Set(k))
    assertEquals(scan.suppressedNoteIds, Set(noteId("n2")))
  }

  /** A failure that shelters nothing must not leak into either exclusion set — otherwise a
    * file that owns nothing would shelter a key belonging to some other file.
    */
  test("a failure that shelters nothing excludes no key and no note") {
    val scan = scanOf(
      BuildFailure.MarkerNotOnHeading("A.md", "x"),
      BuildFailure.MarkedWithoutNoteId("B.md", "x"),
    )
    assertEquals(scan.failedKeys, Set.empty[CardKey])
    assertEquals(scan.suppressedNoteIds, Set.empty[NoteId])
  }
