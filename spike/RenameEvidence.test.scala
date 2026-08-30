package obsidiananki.spike

import obsidiananki.model.HeadingSegment
import org.scalacheck.Gen
import org.scalacheck.Prop.forAll

/** SPIKE — what the evidence shows when a column vanishes and another appears.
  *
  * ==What these tests are FOR==
  *
  * The suite's job is to pin the line between EVIDENCE and A GUESS. The first test is the case the
  * whole idea exists for. The five after it are the ways the evidence fails, and they matter more,
  * because a recovery mechanism that reports a rename which did not happen would move review
  * history onto the wrong card — worse than never having offered.
  *
  * Nothing here asserts that anything is renamed. The findings describe what agreed.
  */
class RenameEvidenceTest extends munit.ScalaCheckSuite:

  import CellValue.text

  // ------------------------------------------------------------------ fixtures ----

  private def orFail[E, A](result: Either[E, A]): A =
    result.fold(error => fail(s"expected a value, got $error"), identity)

  private def seg(text: String): HeadingSegment =
    HeadingSegment
      .fromExtractedText(text)
      .fold(error => sys.error(s"'$text' is not a heading segment: $error"), identity)

  private def cell(raw: String): CellValue =
    CellValue.of(raw).getOrElse(sys.error(s"'$raw' is not a usable cell value"))

  private def evidence(
      was: Vector[(String, String)],
      now: Vector[(String, String)],
  ): RowEvidence =
    orFail(
      RowEvidence.of(
        was.map((key, value) => seg(key) -> cell(value)).toMap,
        now.map((key, value) => seg(key) -> cell(value)).toMap,
      )
    )

  /** Three rows of a table whose `Benefit` column was renamed to `Upside` and nothing else
    * changed. The values are what makes it evidence.
    */
  private val CleanRename: Vector[RowEvidence] = Vector(
    evidence(Vector("Benefit" -> "consistency"), Vector("Upside" -> "consistency")),
    evidence(Vector("Benefit" -> "durability"), Vector("Upside" -> "durability")),
    evidence(Vector("Benefit" -> "ordering"), Vector("Upside" -> "ordering")),
  )

  // ------------------------------------------------------ the fixture is not vacuous ----

  test("VACUITY GUARD — the fixture really carries three rows with distinct, non-empty values") {
    // Without this, the corroboration test below could pass against a table whose every value was
    // the same string, which corroborates everything and therefore nothing.
    assertEquals(CleanRename.size, 3)
    assertEquals(
      CleanRename.flatMap(_.wasAt.values).map(_.text).distinct.size,
      3,
    )
    assertNotEquals(seg("Benefit"), seg("Upside"))
  }

  // --------------------------------------------------------- the case it exists for ----

  test("CORROBORATED — every row agrees, so the pairing is evidence") {
    // This is the shape §4A of `docs/findings/EVOLVABILITY.md` calls exact agreement rather than
    // similarity: the same bytes in every row, and different bytes in none.
    assertEquals(
      RenameEvidence.survey(CleanRename),
      Vector(Finding.Corroborated(seg("Benefit"), seg("Upside"), 3)),
    )
  }

  test("THE ROW COUNT IS REPORTED, because three agreeing rows read differently from one") {
    val oneRow = Vector(CleanRename.head)
    assertEquals(
      RenameEvidence.survey(oneRow),
      Vector(Finding.Corroborated(seg("Benefit"), seg("Upside"), 1)),
    )
  }

  test("TWO INDEPENDENT RENAMES in one edit are paired correctly, not crossed") {
    val both = Vector(
      evidence(
        Vector("Benefit" -> "consistency", "Risk" -> "partition"),
        Vector("Upside" -> "consistency", "Danger" -> "partition"),
      ),
      evidence(
        Vector("Benefit" -> "durability", "Risk" -> "latency"),
        Vector("Upside" -> "durability", "Danger" -> "latency"),
      ),
    )

    // Sorted by key, so `benefit` precedes `risk` deterministically.
    assertEquals(
      RenameEvidence.survey(both),
      Vector(
        Finding.Corroborated(seg("Benefit"), seg("Upside"), 2),
        Finding.Corroborated(seg("Risk"), seg("Danger"), 2),
      ),
    )
  }

  // ------------------------------------------------- the ways the evidence fails ----

  test("ONE DISSENTING ROW IS ENOUGH — a rename plus an edit is not recoverable") {
    // The author renamed the column AND fixed a value in the same commit. Nothing distinguishes
    // that from a deletion and an unrelated addition, so the honest answer is that this column is
    // unexplained. NO PROPORTION IS COMPUTED: two of three rows agreeing is not a weak yes, it is
    // the fact that the columns hold different content.
    val renamedAndEdited = CleanRename.updated(
      2,
      evidence(Vector("Benefit" -> "ordering"), Vector("Upside" -> "total ordering")),
    )

    assertEquals(
      RenameEvidence.survey(renamedAndEdited),
      Vector(Finding.Unexplained(seg("Benefit"))),
    )
  }

  test("AMBIGUOUS — two new columns agree, so the evidence ranks nothing") {
    val twoCandidates = Vector(
      evidence(
        Vector("Benefit" -> "consistency"),
        Vector("Upside" -> "consistency", "Gain" -> "consistency"),
      )
    )

    assertEquals(
      RenameEvidence.survey(twoCandidates),
      Vector(Finding.Ambiguous(seg("Benefit"), Vector(seg("Gain"), seg("Upside")))),
    )
  }

  test("CONTESTED — one candidate, but another vanished column claims it too") {
    // Two old columns held identical values, so both agree with the single new one. From either
    // side there is exactly one candidate, which is why this is not `Ambiguous`: the failure is
    // that the pairing is not MUTUAL. Two columns cannot both have become the same one.
    val contested = Vector(
      evidence(
        Vector("Benefit" -> "consistency", "Gain" -> "consistency"),
        Vector("Upside" -> "consistency"),
      )
    )

    assertEquals(
      RenameEvidence.survey(contested),
      Vector(
        Finding.Contested(seg("Benefit"), seg("Upside"), Vector(seg("Gain"))),
        Finding.Contested(seg("Gain"), seg("Upside"), Vector(seg("Benefit"))),
      ),
    )
  }

  test("UNEXPLAINED — a plain deletion offers nothing to pair with") {
    val deleted = Vector(evidence(Vector("Benefit" -> "consistency"), Vector.empty))
    assertEquals(RenameEvidence.survey(deleted), Vector(Finding.Unexplained(seg("Benefit"))))
  }

  test("A BLANK CELL CORROBORATES NOTHING, which is why CellValue refuses one") {
    // If blanks were comparable, every blank column would agree with every other and this file
    // would confidently report nonsense. The refusal is at construction, so a survey never sees
    // one.
    assertEquals(CellValue.of(""), None)
    assertEquals(CellValue.of("   "), None)
    assertEquals(CellValue.of("\t\n "), None)
    assertEquals(CellValue.of(" consistency ").map(_.text), Some("consistency"))
  }

  test("REFUSED — one key offered as both vanished and new in the same row") {
    // That describes a column which simultaneously left and arrived, which is a Kept column
    // misreported by the caller.
    assertEquals(
      RowEvidence.of(
        Map(seg("Benefit") -> cell("consistency")),
        Map(seg("Benefit") -> cell("consistency")),
      ),
      Left(EvidenceError.KeyBothStrandedAndFresh(seg("Benefit"))),
    )
  }

  // ------------------------------------------------------------------ the law ----

  property("A DISSENTING ROW ANYWHERE FORBIDS CORROBORATION") {
    // The safety law, and the one worth stating over generated input rather than three examples.
    // Reporting a rename that did not happen would move review history onto the wrong card, which
    // is strictly worse than never having offered — so this is the property that licenses the
    // whole mechanism, not merely a nice-to-have.
    val values = Gen.oneOf("consistency", "durability", "ordering")
    val pair   = for was <- values; now <- values yield (was, now)

    forAll(Gen.listOfN(4, pair)) { pairs =>
      val rows = pairs.toVector.map((was, now) =>
        evidence(Vector("Benefit" -> was), Vector("Upside" -> now))
      )
      val corroborated =
        RenameEvidence.survey(rows).collect { case f: Finding.Corroborated => f }.nonEmpty

      if pairs.exists((was, now) => was != now) then !corroborated else corroborated
    }
  }
