package obsidiananki.spike

import obsidiananki.model.HeadingSegment

/** SPIKE — deciding which slot each table column occupies, when a table ROW becomes one Anki note.
  *
  * **BELONGS TO oas-hnr** — making a table row into one Anki note. This spike settles
  * which slot each column occupies, and the design it feeds is `docs/design/TABLE-CARDS-REDESIGN.md`.
  *
  * ==NOT WIRED IN, AND DELIBERATELY SO==
  *
  * Nothing imports this. `docs/design/TABLE-CARDS-REDESIGN.md` §8 says explicitly that whether to make a
  * row into a note is undecided, and the one-time cost of deciding it is every existing table
  * card's review history. This file exists to make the RULE examinable before that is paid, and it
  * should be deleted rather than quietly adopted if the answer is no.
  *
  * ==The problem it solves==
  *
  * Anki attaches review history to a CARD, and a card is identified inside its note by which
  * template produced it — a fixed slot number. So under a row-as-note design each column must
  * occupy a numbered slot, and the scheduling follows the slot rather than the column.
  *
  * Assign slots by POSITION and inserting a column silently moves history: a table with `Cost` and
  * `Benefit` that gains a `Risk` between them leaves the card at slot two holding the scheduling it
  * earned asking about `Benefit`, while now asking about `Risk`. Today that cannot happen, because
  * a cell card is keyed by its column header and position is no part of identity
  * (`extract/Tables.scala`). Preserving that is the whole job of this file.
  *
  * ==Why matching is possible at all==
  *
  * The note must record each column's key regardless (see [[VaultColumn]]), so Anki itself holds
  * the answer to what each slot currently means, and `ObservedNote.fields` is already fetched by
  * the planner. Placement therefore reads the artifact it is about to overwrite, which is what an
  * executor does. IDENTITY STAYS A PURE FUNCTION OF THE VAULT — only PLACEMENT is read from Anki,
  * and nothing is remembered between runs, so the standing position that a sidecar is a cache and
  * never an oracle is not engaged.
  */

/** How many slots the note type has.
  *
  * AN OPAQUE TYPE RATHER THAN AN `Int`, and the reason is [[Slot]]'s. A width and an index are
  * both "a number about slots", they are never interchangeable, and confusing them puts a card's
  * review history somewhere nobody chose.
  */
opaque type Capacity = Int

object Capacity:

  /** Refuses a width that names no note type. Zero slots is not a narrow table, it is a note type
    * that cannot hold a card, and a negative width is a caller bug wearing a plausible face.
    *
    * NOT GATED AT TWO, though a row card needs two or more descriptors to be worth emitting
    * (`extract/Tables.scala`). That rule belongs to whatever decides which cards a table declares,
    * not to the note type's shape, and enforcing it here would put one rule in two places.
    */
  def of(width: Int): Either[ShapeError, Capacity] =
    Either.cond(width >= 1, width, ShapeError.CapacityNotPositive(width))

  extension (capacity: Capacity)
    def width: Int = capacity

    /** Every slot this note type has, in order. THE ONLY WAY A [[Slot]] IS MINTED, so a `Slot` in
      * hand is always in range for the capacity it came from.
      */
    def slots: Vector[Slot] = (0 until capacity).toVector.map(Slot.fromIndex)

/** WHICH TEMPLATE'S CARD a column's scheduling follows.
  *
  * ==Why this is not an `Int`==
  *
  * This is the most identity-critical value in the design: Anki attaches a card's interval, ease,
  * due date and review log to it. As a bare `Int` it had the same type as a capacity, a column
  * count and a vector size, so nothing stopped one being passed where another belonged — and the
  * cost of that confusion is a card silently inheriting another card's history.
  *
  * This repository already makes opaque types of `NoteId`, `HeadingSegment`, `PropertyName` and
  * `DecisionHandle`, none of which is as dangerous to confuse as this one.
  */
opaque type Slot = Int

object Slot:
  /** Package-private ON PURPOSE: [[Capacity.slots]] is the only mint, so range is a property of
    * construction rather than something every consumer has to re-check. Tests share this package
    * and can therefore name a slot directly, which is the point of `private[spike]` rather than
    * `private`.
    */
  private[spike] def fromIndex(index: Int): Slot = index

  extension (slot: Slot) def index: Int = slot

/** A column as the vault declares it.
  *
  * TWO PROJECTIONS, AND THE SEVERANCE IS THE POINT — the same severance `extract/Tables.scala`
  * already holds between `cellSource` and `CellDisplay`. `key` is the IDENTITY projection, frozen,
  * and the ONLY thing placement is allowed to match on. `header` is what a person READS on the
  * card, and is free to change.
  *
  * ==A FINDING THAT FALLS OUT OF WRITING THIS DOWN, and it changes the note type==
  *
  * `docs/design/TABLE-CARDS-REDESIGN.md` assumed a slot needs two fields, a header and a value. It needs
  * THREE. Matching on the rendered header would make placement depend on the RENDERER: today a
  * header is escaped for HTML, and `extract/Tables.scala` records that rendering a cell's
  * STRUCTURE — bold, inline code — is a later slice. Escaping is injective, so matching would
  * survive it; rendering structure is not, so matching would NOT survive that. A renderer change
  * would then move placements, and moving a placement moves review history.
  *
  * So the note must carry the frozen key per slot as its own field, beside the displayed header,
  * and it must be there from the first version — added later it cannot recover the placements
  * already made without it.
  */
final case class VaultColumn(key: HeadingSegment, header: String)

/** Why a column ended up where it did. Reported rather than inferred, because the two cases have
  * different consequences for review history and a caller must be able to tell them apart.
  */
enum Provenance:

  /** The slot this column ALREADY occupied on the existing note. Its card keeps its history. */
  case Kept

  /** A slot with no prior claim. A new card, starting from nothing. */
  case Fresh

/** WHAT ONE SLOT HOLDS AFTER PLACEMENT — the representation that makes the illegal states go away.
  *
  * ==Why a state per slot rather than a list of placements==
  *
  * The first version of this file returned a list of `Seated` records each carrying its own slot
  * number, plus a separate list of vacated ones. That admitted two records at one slot, a slot
  * beyond the capacity, and the same slot appearing as both occupied and vacated. None of those
  * could be produced by the placement function, but the TYPE allowed them, and one consumer
  * (`bySlot`) resolved a duplicate silently through a `Map` — the very silent-drop failure the
  * comment beside it warned about for `Vector.zip`.
  *
  * One state per slot, held positionally, makes all four unrepresentable rather than merely
  * unproduced.
  */
enum SlotState:

  /** A column lives here, and [[Provenance]] says whether its card keeps its history. */
  case Occupied(column: VaultColumn, provenance: Provenance)

  /** ANKI HOLDS A CARD HERE AND THE VAULT NAMES NO COLUMN FOR IT.
    *
    * The card does not go away. MEASURED 2026-08-28 (`docs/findings/ANKICONNECT-BEHAVIOUR.md`): clearing a
    * gate field leaves the card in place with its full history, rendering Anki's blank-front
    * placeholder, until a person runs Tools then Empty Cards. No add-on action can do it.
    *
    * SO THIS SLOT IS NOT REUSABLE, which is why it is a state of its own rather than the same
    * thing as [[Free]]. Handing it to a new column would give that column the old one's schedule.
    */
  case Stranded(key: HeadingSegment)

  /** Anki holds nothing here. The only state a new column may be placed into. */
  case Free

/** Refusals about the SHAPE OF THE DESCRIPTION — a capacity or a set of stored slots that could
  * not have come from this tool.
  *
  * SEPARATE FROM [[PlacementError]] BECAUSE THE AUDIENCE DIFFERS. These are caller bugs and their
  * messages are for whoever is writing the code; a [[PlacementError]] is about the table a person
  * wrote and its message is for that person. Collapsing the two would mean one sum whose cases
  * cannot all be phrased for the same reader.
  */
enum ShapeError:

  /** A note type with no slots holds no cards, so no width below one names anything. */
  case CapacityNotPositive(width: Int)

  /** The stored description is not the note type's width, so it describes a note this note type
    * could not have produced.
    */
  case ArityMismatch(where: String, supplied: Int, capacity: Int)

  /** The same key recorded at two slots of one note. Corrupt rather than merely unusual: nothing
    * this tool writes could produce it, so it is refused rather than resolved.
    */
  case DuplicateStoredKey(where: String, key: HeadingSegment)

/** Refusals about the TABLE AS WRITTEN. Every message here is for the person who wrote it. */
enum PlacementError:

  /** More columns than the note type has slots. The honest answer is to refuse and name the cap;
    * silently dropping a column is a card the author asked for and did not get.
    */
  case TooManyColumns(where: String, declared: Int, capacity: Int)

  /** Two columns whose headers canonicalise to the same key. They would compete for one slot, and
    * whichever lost would silently take the other's history.
    */
  case DuplicateColumnKey(where: String, key: HeadingSegment)

  /** A new column with nowhere to go, because every remaining slot is [[SlotState.Stranded]].
    *
    * ==This case was found by a test, and finding it is why the spike exists==
    *
    * The first implementation read a free slot as one no KEPT column had claimed. That let a
    * newcomer take a slot being vacated in the same placement — and a vacated slot's card is not
    * gone. Refilling its gate field hands the removed column's history to a different question,
    * silently: §4's failure reached by a friendlier-looking route.
    *
    * The remedy is a person's, and the message must say so: Tools then Empty Cards releases the
    * stranded slots, because no add-on action can.
    */
  case NoFreeSlot(where: String, column: HeadingSegment, stranded: Vector[Slot])

/** WHAT ANKI RECORDS AT EACH SLOT TODAY, validated on the way in.
  *
  * The width invariant and the no-duplicate-keys invariant used to be checked inside the placement
  * function, which meant every caller could hand it a vector of the wrong shape and the errors for
  * doing so lived in the author-facing sum. Holding them here makes both unrepresentable
  * downstream: a `StoredSlots` in hand is already the right width with distinct keys.
  */
opaque type StoredSlots = Vector[Option[HeadingSegment]]

object StoredSlots:

  import Capacity.width

  /** From what the planner read off the note. `None` at a slot means Anki records no column there.
    */
  def of(
      capacity: Capacity,
      entries: Vector[Option[HeadingSegment]],
      where: String,
  ): Either[ShapeError, StoredSlots] =
    for
      _ <- Either.cond(
        entries.sizeIs == capacity.width,
        (),
        ShapeError.ArityMismatch(where, entries.size, capacity.width),
      )
      _ <- firstDuplicate(entries.flatten)
        .map(ShapeError.DuplicateStoredKey(where, _))
        .toLeft(())
    yield entries

  /** A row with no Anki note yet. NAMED RATHER THAN SPELLED `Vector.fill(n)(None)` at each call
    * site, because "I have not looked at Anki" and "Anki holds nothing" are the same value and
    * very much not the same claim — and the difference between them is whether a card keeps its
    * history.
    */
  def noNoteYet(capacity: Capacity): StoredSlots = Vector.fill(capacity.width)(None)

  extension (stored: StoredSlots) def entries: Vector[Option[HeadingSegment]] = stored

/** One column, placed. A PROJECTION of [[RowPlacement]] and never an independent representation —
  * `RowPlacement` is the authority, and these exist because a named record reads better at a call
  * site than a tuple.
  */
final case class Seated(slot: Slot, column: VaultColumn, provenance: Provenance)

/** One slot holding a card the vault no longer names. A projection, as [[Seated]] is. */
final case class StrandedSlot(slot: Slot, key: HeadingSegment)

/** Where every column of one row went: one [[SlotState]] per slot, in slot order. */
opaque type RowPlacement = Vector[SlotState]

object RowPlacement:

  import Capacity.width
  import StoredSlots.entries

  /** Place one row's columns into the note's slots.
    *
    * NO DEFAULT PARAMETERS, and `rules/no-default-parameters.yml` is not the only reason. A default
    * `capacity` would let production and a test drive different widths, and a default `stored`
    * would silently turn "I did not look at Anki" into "Anki holds nothing" — which is the
    * difference between keeping a card's history and starting it over.
    */
  def place(
      capacity: Capacity,
      stored: StoredSlots,
      columns: Vector[VaultColumn],
      where: String,
  ): Either[PlacementError, RowPlacement] =
    val entries = stored.entries
    val wanted  = columns.map(_.key).toSet

    // MATCHED ON THE FROZEN KEY, NEVER ON THE DISPLAYED HEADER. See [[VaultColumn]] for why that
    // distinction is a field in the note type rather than a nicety here. Injective by
    // construction: `StoredSlots.of` has already refused a duplicate key.
    val slotOfStoredKey = entries.zipWithIndex.collect { case (Some(key), at) => key -> at }.toMap

    val kept     = columns.filter(column => slotOfStoredKey.contains(column.key))
    val newcomer = columns.filterNot(column => slotOfStoredKey.contains(column.key))

    // FREE MEANS ANKI RECORDS NOTHING HERE — not merely that no kept column claimed it. A slot
    // being vacated by this very placement is NOT free; its card still exists, blank-fronted,
    // holding the removed column's history. See [[PlacementError.NoFreeSlot]].
    val freeAt     = entries.zipWithIndex.collect { case (None, at) => at }
    val strandedAt =
      entries.zipWithIndex.collect { case (Some(key), at) if !wanted.contains(key) => at }

    for
      _ <- Either.cond(
        columns.sizeIs <= capacity.width,
        (),
        PlacementError.TooManyColumns(where, columns.size, capacity.width),
      )
      _ <- firstDuplicate(columns.map(_.key))
        .map(PlacementError.DuplicateColumnKey(where, _))
        .toLeft(())

      // REFUSED BEFORE THE `zip` BELOW, which is what makes that zip exact rather than truncating.
      // `Vector.zip` drops the tail of the longer side WITHOUT COMPLAINT — the silent loss
      // `model/CardSpec.scala` records as the reason `Context` is appended rather than zipped —
      // and a column dropped here is a card the author asked for and never got.
      _ <- newcomer
        .drop(freeAt.size)
        .headOption
        .map(column =>
          PlacementError.NoFreeSlot(where, column.key, strandedAt.map(Slot.fromIndex))
        )
        .toLeft(())
    yield
      // BUILT POSITIONALLY, BY OVERWRITING A BASELINE. Every stored key starts out `Stranded` and
      // is promoted to `Occupied` only if a column still claims it, so a slot can never end up in
      // two states and no lookup can silently lose one. This is the shape that replaced a
      // `Map`-keyed reconstruction whose collisions would have been invisible.
      val baseline: Vector[SlotState] = entries.map {
        case Some(key) => SlotState.Stranded(key)
        case None      => SlotState.Free
      }

      val withKept = kept.foldLeft(baseline)((acc, column) =>
        acc.updated(slotOfStoredKey(column.key), SlotState.Occupied(column, Provenance.Kept))
      )

      newcomer
        .zip(freeAt)
        .foldLeft(withKept)((acc, placed) =>
          acc.updated(placed._2, SlotState.Occupied(placed._1, Provenance.Fresh))
        )

  extension (placement: RowPlacement)

    /** The slots, in slot order. */
    def states: Vector[SlotState] = placement

    /** For building the note's fields. `None` is a gate field left empty, which is what stops Anki
      * generating that card — including at a [[SlotState.Stranded]] slot, where that is precisely
      * the mechanism rather than an omission.
      *
      * TOTAL, AND WITH NO LOOKUP. It reads the positional representation straight through, so the
      * silent collision the previous `Map`-based version could suffer is gone by construction.
      */
    def bySlot: Vector[Option[VaultColumn]] = placement.map {
      case SlotState.Occupied(column, _) => Some(column)
      case SlotState.Stranded(_)         => None
      case SlotState.Free                => None
    }

    def seated: Vector[Seated] = placement.zipWithIndex.collect {
      case (SlotState.Occupied(column, provenance), at) =>
        Seated(Slot.fromIndex(at), column, provenance)
    }

    def stranded: Vector[StrandedSlot] = placement.zipWithIndex.collect {
      case (SlotState.Stranded(key), at) => StrandedSlot(Slot.fromIndex(at), key)
    }

/** The first key that occurs more than once, or `None`.
  *
  * TOP LEVEL because both [[StoredSlots]] and [[RowPlacement]] ask the question, of different
  * vectors, and one of the two answers must not drift from the other.
  *
  * `diff` REMOVES ONE OCCURRENCE PER ELEMENT, so `keys.diff(keys.distinct)` is exactly the repeats
  * — and its head is the first of them in the order the author wrote their columns, which is the
  * one worth naming in a message.
  */
private def firstDuplicate(keys: Vector[HeadingSegment]): Option[HeadingSegment] =
  keys.diff(keys.distinct).headOption
