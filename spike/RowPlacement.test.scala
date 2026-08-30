package obsidiananki.spike

import obsidiananki.model.HeadingSegment
import org.scalacheck.Gen
import org.scalacheck.Prop.forAll

/** SPIKE — the edits a person makes to a table, and what each one does to review history.
  *
  * ==What these tests are FOR==
  *
  * Every test in the middle section is one authoring edit. The suite as a whole is the claim that
  * a row-as-note design keeps the property today's design has for free: **column POSITION is no
  * part of card identity.** `extract/Tables.scala` gets that by keying a cell card on its column
  * header; `RowPlacement` has to earn it, because scheduling follows a slot number.
  *
  * Read the edits in the order they appear — reorder, insert, remove, rename, round trip. That is
  * the sequence in which a table is actually edited, and the third and fourth are where history is
  * at stake.
  */
class RowPlacementTest extends munit.ScalaCheckSuite:

  import Capacity.width
  import StoredSlots.entries

  // ------------------------------------------------------------------ fixtures ----

  /** SIX, MATCHING THE ARITHMETIC IN `docs/design/TABLE-CARDS-REDESIGN.md` §3 — and that document says in
    * as many words that six is used for arithmetic rather than proposed. The real cap should come
    * from the widest table in the vault, which nobody has looked at, WITH HEADROOM FOR CHURN,
    * because a stranded slot is not reusable until a person runs Tools then Empty Cards.
    */
  private val Width: Int = 6

  private val Where: String = "Quorum"

  private def orFail[E, A](result: Either[E, A]): A =
    result.fold(error => fail(s"expected a value, got $error"), identity)

  private val Cap: Capacity = orFail(Capacity.of(Width))

  private def seg(text: String): HeadingSegment =
    HeadingSegment
      .fromExtractedText(text)
      .fold(error => sys.error(s"'$text' is not a heading segment: $error"), identity)

  private def col(text: String): VaultColumn = VaultColumn(seg(text), text)

  private def slot(index: Int): Slot = Slot.fromIndex(index)

  /** Anki holding these column keys at slots 0, 1, … and nothing at the rest. */
  private def anki(keys: String*): StoredSlots =
    val occupied = keys.toVector.map(key => Some(seg(key)))
    orFail(StoredSlots.of(Cap, occupied ++ Vector.fill(Width - keys.size)(None), Where))

  private def place(
      stored: StoredSlots,
      columns: Vector[VaultColumn],
  ): Either[PlacementError, RowPlacement] =
    RowPlacement.place(Cap, stored, columns, Where)

  /** Key to slot, which is the only thing scheduling actually follows. */
  private def slots(placement: RowPlacement): Map[String, Slot] =
    placement.seated.map(s => s.column.key.value -> s.slot).toMap

  private def provenances(placement: RowPlacement): Map[String, Provenance] =
    placement.seated.map(s => s.column.key.value -> s.provenance).toMap

  // ------------------------------------------------------ the fixture is not vacuous ----

  test("VACUITY GUARD — the fixture really does record two distinct columns at known slots") {
    // Without this, every edit test below could pass against a `stored` that matched nothing,
    // since "matched nothing" and "matched everything correctly" both yield a placement.
    assertEquals(anki("Cost", "Benefit").entries.flatten.map(_.value), Vector("cost", "benefit"))
    assertEquals(anki("Cost", "Benefit").entries.size, Width)
    assertNotEquals(seg("Cost"), seg("Benefit"))
  }

  // ------------------------------------------------------------------- the edits ----

  test("REORDER — writing the columns the other way round moves nothing") {
    val forwards = orFail(place(anki("Cost", "Benefit"), Vector(col("Cost"), col("Benefit"))))
    val swapped  = orFail(place(anki("Cost", "Benefit"), Vector(col("Benefit"), col("Cost"))))

    assertEquals(slots(swapped), slots(forwards))
    assertEquals(slots(swapped), Map("cost" -> slot(0), "benefit" -> slot(1)))
    assertEquals(swapped.stranded, Vector.empty)
    // Both are Kept, so both cards keep their history. This is the property the whole design
    // hangs on: today it is free, and here it is bought.
    assertEquals(provenances(swapped).values.toSet, Set(Provenance.Kept))
  }

  test("INSERT — a new column in the MIDDLE leaves the existing two where they were") {
    // The edit that breaks a position-based rule: `Risk` is written between `Cost` and `Benefit`,
    // so by position it would take slot 1 and `Benefit` would inherit a card that is not its own.
    val after =
      orFail(place(anki("Cost", "Benefit"), Vector(col("Cost"), col("Risk"), col("Benefit"))))

    assertEquals(slots(after)("cost"), slot(0))
    assertEquals(slots(after)("benefit"), slot(1))
    assertEquals(provenances(after)("cost"), Provenance.Kept)
    assertEquals(provenances(after)("benefit"), Provenance.Kept)

    // The newcomer takes the lowest FREE slot, which is 2 — not the position it occupies in the
    // markdown, which is 1.
    assertEquals(slots(after)("risk"), slot(2))
    assertEquals(provenances(after)("risk"), Provenance.Fresh)
    assertEquals(after.stranded, Vector.empty)
  }

  test("REMOVE — deleting a column strands its slot and leaves the other alone") {
    val after = orFail(place(anki("Cost", "Benefit"), Vector(col("Cost"))))

    assertEquals(slots(after), Map("cost" -> slot(0)))
    assertEquals(provenances(after)("cost"), Provenance.Kept)

    // The stranded card. It is NOT deleted — Anki keeps it blank-fronted with its history until a
    // person runs Tools then Empty Cards, measured 2026-08-28 and recorded in
    // `docs/findings/ANKICONNECT-BEHAVIOUR.md`.
    assertEquals(after.stranded, Vector(StrandedSlot(slot(1), seg("Benefit"))))
    assertEquals(after.states(1), SlotState.Stranded(seg("Benefit")))
    assertEquals(after.bySlot(1), None)
  }

  test("RENAME — the accepted loss, stated rather than hidden") {
    // `Benefit` becomes `Upside`. Nothing in the vault records that this was a rename, so it is
    // indistinguishable from deleting one column and adding another, and the tool must not guess.
    // This is the cost `README.md` already declares for renaming a marked heading.
    val after = orFail(place(anki("Cost", "Benefit"), Vector(col("Cost"), col("Upside"))))

    assertEquals(provenances(after)("cost"), Provenance.Kept)
    assertEquals(provenances(after)("upside"), Provenance.Fresh)
    assertEquals(after.stranded, Vector(StrandedSlot(slot(1), seg("Benefit"))))

    // NOT slot 1, and this is the assertion that caught the first implementation. Slot 1 still
    // holds `Benefit`'s card, with `Benefit`'s history. Putting `Upside` there would hand that
    // history to a different question, silently — the same theft as the positional rule, reached
    // by a friendlier-looking route.
    assertNotEquals(slots(after)("upside"), slot(1))
    assertEquals(slots(after)("upside"), slot(2))
  }

  test("ROUND TRIP — a column removed and restored comes back to its ORIGINAL slot") {
    // This is the test the live measurement licenses. Restoring a cleared gate field returned the
    // same card id, ordinal, review count and due timestamp (2026-08-28), so returning a column to
    // its old slot returns its history with it.
    //
    // Anki still records `Benefit` at slot 1 throughout: clearing the field strands the card, it
    // does not erase what the note says about that slot.
    val removed  = orFail(place(anki("Cost", "Benefit"), Vector(col("Cost"))))
    val restored = orFail(place(anki("Cost", "Benefit"), Vector(col("Cost"), col("Benefit"))))

    assertEquals(removed.stranded, Vector(StrandedSlot(slot(1), seg("Benefit"))))
    assertEquals(slots(restored), Map("cost" -> slot(0), "benefit" -> slot(1)))
    assertEquals(provenances(restored)("benefit"), Provenance.Kept)
    assertEquals(restored.stranded, Vector.empty)
  }

  test("NO NOTE YET — every column is Fresh, and assignment falls back to POSITION") {
    // Safe precisely because there is no history to protect. This is also the only case in which
    // markdown order decides anything at all.
    val first = orFail(
      place(StoredSlots.noNoteYet(Cap), Vector(col("Cost"), col("Benefit"), col("Risk")))
    )

    assertEquals(
      slots(first),
      Map("cost" -> slot(0), "benefit" -> slot(1), "risk" -> slot(2)),
    )
    assertEquals(provenances(first).values.toSet, Set(Provenance.Fresh))
    assertEquals(first.stranded, Vector.empty)
  }

  // ------------------------------------ refusals about the table a person wrote ----

  test("REFUSED — more columns than the note type has slots") {
    val tooWide = (1 to Width + 1).toVector.map(n => col(s"Column $n"))
    assertEquals(
      place(StoredSlots.noNoteYet(Cap), tooWide),
      Left(PlacementError.TooManyColumns(Where, Width + 1, Width)),
    )
  }

  test("REFUSED — two columns whose headers canonicalise to the same key") {
    // `Cost` and `COST` are the SAME key. `TagCodec.canonical` normalises to NFC, trims, collapses
    // runs of whitespace and lower-cases, so case is not a distinction a column can rely on.
    //
    // CORRECTED WHILE WRITING THIS: the first version used `**Cost**`, on the strength of
    // `model/CardKey.scala`'s note that `## **CAP**` and `## CAP` are the same key. They are — but
    // that equality is produced by the markdown PARSER, whose `extractText` has already discarded
    // the emphasis before a segment is built. Handing raw asterisks to `fromExtractedText`
    // bypasses the parser and keeps them.
    assertEquals(
      place(StoredSlots.noNoteYet(Cap), Vector(col("Cost"), col("COST"))),
      Left(PlacementError.DuplicateColumnKey(Where, seg("Cost"))),
    )
  }

  test("REFUSED — a new column when every remaining slot still holds a stranded card") {
    // The case the RENAME test uncovered, taken to its limit. Anki holds six columns; the author
    // deletes five and adds one. One column is Kept, five slots are stranded — and NONE of those
    // five is available, because each still holds a card carrying its column's history.
    val full = anki("Column 1", "Column 2", "Column 3", "Column 4", "Column 5", "Column 6")

    assertEquals(
      place(full, Vector(col("Column 1"), col("Something New"))),
      Left(
        PlacementError.NoFreeSlot(
          Where,
          seg("Something New"),
          Vector(1, 2, 3, 4, 5).map(slot),
        )
      ),
    )
  }

  // ------------------------------- refusals about the shape of the description ----

  test("REFUSED — a note type with no slots") {
    // Not a narrow table: a note type that cannot hold a card at all.
    assertEquals(Capacity.of(0), Left(ShapeError.CapacityNotPositive(0)))
    assertEquals(Capacity.of(-1), Left(ShapeError.CapacityNotPositive(-1)))
    assert(Capacity.of(1).isRight)
  }

  test("REFUSED — a stored description that is not the note type's width") {
    // Held at construction rather than inside `place`, so no placement can ever see one.
    assertEquals(
      StoredSlots.of(Cap, Vector(Some(seg("Cost"))), Where),
      Left(ShapeError.ArityMismatch(Where, 1, Width)),
    )
  }

  test("REFUSED — the same key recorded at two slots of one note") {
    val corrupt = Vector(Some(seg("Cost")), Some(seg("Cost"))) ++ Vector.fill(Width - 2)(None)
    assertEquals(
      StoredSlots.of(Cap, corrupt, Where),
      Left(ShapeError.DuplicateStoredKey(Where, seg("Cost"))),
    )
  }

  // ------------------------------------------------------------------ the laws ----

  property("PERMUTING THE COLUMNS NEVER MOVES A CARD") {
    // The suite's central claim, stated over every ordering rather than the two the reorder test
    // happens to name. If this ever reddens, a card's identity has acquired a positional component
    // and the design has lost the property it exists to keep.
    val columns = Vector("Cost", "Benefit", "Risk", "Latency").map(col)
    val stored  = anki("Cost", "Benefit")

    forAll(Gen.pick(columns.size, columns)) { shuffled =>
      val reference = orFail(place(stored, columns))
      val permuted  = orFail(place(stored, shuffled.toVector))
      slots(permuted) == slots(reference) && permuted.stranded == reference.stranded
    }
  }

  property("A PLACEMENT ALWAYS HAS EXACTLY ONE STATE PER SLOT") {
    // Structural rather than behavioural, and it is here because the FIRST version of this design
    // could not make the claim: it returned a list of records each carrying its own slot number,
    // which admitted two at one slot, a slot past the capacity, and a slot both occupied and
    // vacated. Holding one state per slot positionally made all three unrepresentable. This
    // property is what that change bought, asserted rather than assumed.
    val pool = Vector("Cost", "Benefit", "Risk", "Latency", "Upside").map(col)

    forAll(Gen.choose(0, pool.size).flatMap(Gen.pick(_, pool))) { chosen =>
      val placement = orFail(place(anki("Cost", "Benefit"), chosen.toVector))
      placement.states.sizeIs == Cap.width &&
      placement.seated.size + placement.stranded.size <= Cap.width &&
      placement.bySlot.sizeIs == Cap.width
    }
  }
