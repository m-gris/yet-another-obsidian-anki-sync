package obsidiananki.plan

import cats.data.NonEmptyVector
import obsidiananki.anki.{AnkiNoteId, DeckPath}
import obsidiananki.model.{CardKey, CardPath, Marker, OwnedTag}

/* WHAT THE EVIDENCE SAYS WHEN A CARD'S SOURCE MOVED, and — for one shape of evidence only —
 * what is done about it.
 *
 * ══ THE EVENT THIS IS ABOUT ══
 *
 * A card's identity is `(frontmatter id, the node it hangs off)`, and for the ordinary card that
 * node is a chain of ancestor headings. So making an `## Essential numbers` into a `### Essential
 * numbers` under some other H2 mints a NEW key, and the old key is then absent from the vault.
 * What the tool did with that until this file existed is exactly what it does with a deletion:
 * the old note is tagged `orphaned::` and every card of it suspended, and a second note is
 * created at the new key with no review history at all. Nothing paired the two. The history was
 * not destroyed — it was stranded on the suspended note — and `docs/findings/EVOLVABILITY.md` §4
 * names the state for what it is: "technically preserved and practically abandoned, which is a
 * slower version of losing it."
 *
 * ══ WHY THE MACHINERY THAT LOOKS LIKE IT COVERS THIS DOES NOT ══
 *
 * `Planner.identityErrorFor` names a likely card, prints the tag that would bind it, and applies
 * nothing — which is the right shape. It is reached ONLY from `ObservedState.unresolved`, i.e.
 * from a note whose identity STRING is unreadable or ambiguous. A moved heading leaves a
 * perfectly readable identity pointing at a path the vault no longer produces, so it never
 * enters that path; it takes the orphan branch and is offered nothing.
 *
 * Its evidence would not have helped either. `Planner.contentHash` covers the note type and
 * EVERY field but `Identity` — `Context` included — and `Context` is the breadcrumb of folders,
 * file name and ancestor headings (`extract/CardContext.scala`). So there is no card shape in
 * which a move leaves the hash intact: the one instrument that could witness the pairing is
 * structurally blind to the one event being witnessed. That is why this compares FIELDS and not
 * hashes, and it costs nothing to do so — `ObservedNote.fields` is already fetched.
 *
 * ══ IT ACTS, AND MARC RULED THAT IT SHOULD — 2026-09-05 ══
 *
 * AN UNAMBIGUOUS MATCH REASSIGNS AUTOMATICALLY. No approval handle, no waiting. The reasoning is
 * a risk asymmetry and is written out here because a later reader must be able to find it rather
 * than re-derive it:
 *
 *   1. NOT REASSIGNING IS A CERTAIN LOSS, EVERY TIME. The card is orphaned and suspended, a
 *      replacement is created at zero, and the review history is stranded on a card the author
 *      will never see again. That happens on every move, guaranteed.
 *   2. REASSIGNING WRONGLY IS A BOUNDED LOSS, RARELY — and bounded BY THE RULE ITSELF. The only
 *      way this design can pair two cards is byte-identical substance, so the worst available
 *      false positive is not "history moved onto an unrelated card"; it is "history moved onto
 *      one of two cards that say exactly the same thing".
 *   3. WAITING DESTROYS THE EVIDENCE. The pairing works because the body still matches. Edit that
 *      body later and the match is gone permanently, so a decision deferred is frequently a
 *      decision lost. The moment of the move is when the evidence is at its maximum.
 *   4. IT IS REVERSIBLE AND VISIBLE. A wrong reassignment leaves the card, its history, and a
 *      line in the report saying what was done. An unreconnected orphan accumulates silently
 *      until somebody goes looking.
 *
 * WHAT MAY AND MAY NOT REASSIGN, WHICH IS THE OTHER HALF OF THAT RULING. Only
 * [[MoveFinding.Corroborated]] — exact substance agreement, every name divergence accounted for
 * by the key, and MUTUAL uniqueness. [[MoveFinding.Ambiguous]], [[MoveFinding.Contested]],
 * [[MoveFinding.Unexplained]], [[MoveFinding.Unaccounted]], [[MoveFinding.Reparented]],
 * [[MoveFinding.NoVoucher]],
 * [[MoveFinding.RelabelUnvouched]] and [[MoveFinding.Incomparable]] are REPORTED and never
 * applied. That separation is load-bearing rather than stylistic: the only function in this
 * codebase that mints a [[SyncAction.Reassign]] is an extension on the corroborated case, so a
 * finding of any other shape cannot be widened into one.
 *
 * ══ WHICH PAIRINGS MAY BE CORROBORATED AT ALL — the ruled identity policy ══
 *
 * Marc's rulings of 2026-09-12 (`docs/design/IDENTITY-DECISION-SHEET.md`) did not change
 * "corroborated ⇒ apply". They changed WHICH pairings may be corroborated, and the change is
 * expressed by TYPING THE EXCEPTIONS APART rather than by a policy check anybody could forget:
 * a card whose SUBJECT changed while the subject it left goes on existing is a different card
 * (R2), and it comes back as [[MoveFinding.Reparented]], which has no `reassignment` method to
 * call. A subject change that also moved, or one whose premise the run could not establish, comes
 * back as [[MoveFinding.RelabelUnvouched]] for the same reason.
 *
 * The ruling of 2026-09-13 changed HOW "goes on existing" is established rather than what it
 * licenses: a subject goes on existing when the node census finds it in the card's own note, OR
 * when this same survey corroborated a card onto that subject. [[SubjectSurvival]] holds the
 * argument, and [[SurveyRead]] holds what it costs the shape of [[survey]].
 *
 * ══ THE STANDING RULE THIS OBEYS ══
 *
 * Identity remains a PURE FUNCTION OF THE VAULT — `oas-4ti`, ruled 2026-08-29. Nothing below
 * derives, alters or suggests a key: every [[CardKey]] in a finding was produced by the vault or
 * decoded from a note, and the same two inputs always yield the same findings. A reassignment
 * does not invent an identity; it decides WHICH EXISTING ANKI NOTE is given a key the vault has
 * already computed. And anything fuzzy may RANK candidates and never APPLY — which this respects
 * by a stronger measure than ranking, since there is no threshold, no distance and no score
 * anywhere in it. Two values are the same bytes or they are not.
 */

/** WHAT A FIELD'S VALUE IS A FUNCTION OF — which is what decides whether a MOVE can change it.
  *
  * This is the whole idea of the file in one type. A move changes WHERE a card is; it does not
  * change WHAT IT SAYS. So the fields divide by what they are computed from, and the division
  * says in advance which of them a move is entitled to disturb.
  *
  * ROLES, NOT IMPORTANCE. Nothing here ranks a field's value to a reviewer. It records how
  * `extract/Extractor.scala` and `extract/Tables.scala` fill it, which is a fact about this
  * codebase that a reader can check against those files.
  */
enum FieldRole:

  /** THE DISPLAY RENDERING OF ONE KEY-PATH SEGMENT — the card's own name, or its parent's.
    *
    * `Front` on a two-field card, `Title` on a sequence, and BOTH `Concept` and `Descriptor` on a
    * concept-descriptor card. The last of those is the one worth pausing on, because it unifies
    * two things that look unrelated: for a card built from a heading the concept is the NEAREST
    * ANCESTOR HEADING (`Extractor.buildSpecs`, the three-field arm), and for one built from a
    * table it is the ROW'S FIRST CELL (`Tables.cardsForRow`). In both cases `Concept` and
    * `Descriptor` are the display form of the LAST TWO SEGMENTS OF THE KEY, which is why one role
    * covers them.
    *
    * `fromEnd` IS COUNTED FROM THE END OF THE PATH, AND THAT IS NOT A CONVENIENCE. Paths differ
    * in depth — a card under one H1 and the same card five headings deep are both ordinary — so
    * there is no fixed position from the front that names "the marked heading". From the end
    * there is: the marked heading is always last, and its parent always second-to-last. `0` is
    * the last segment, `1` the one above it.
    *
    * WHAT THIS BUYS, WHICH IS THE REASON THE ROLE CARRIES AN INDEX AT ALL. A name field that
    * DIFFERS between the collection and the vault has to be explained before a pairing may be acted
    * on, and the key is the only thing that can explain it. The index is what makes the key ANSWER:
    * it names the segment this field is a display of, so the run can ask whether there IS one —
    * and, when there is, whether it moved.
    *
    * THE TWO QUESTIONS ARE ASKED IN DIFFERENT PLACES AND DECIDE DIFFERENT THINGS.
    * [[MoveEvidence.unaccountedFor]] asks only about PRESENCE: with no segment at that position
    * there is nothing the key can say, and [[MoveFinding.Unaccounted]] reports rather than acts. A
    * segment that is present but AGREES is not a defect in the pairing — it says the card's face was
    * re-rendered while its canonical name held, which is what bolding a heading or re-casing one
    * does, and Decision 4 of `docs/design/IDENTITY-DECISION-SHEET.md` ruled that rendering is not
    * identity. WHETHER the segment moved is then read one level up, by the subject gate: for the
    * concept-descriptor card, `Anchor(1)` is the card's parent concept, and a card whose parent
    * changed may be a different card rather than a moved one — see
    * [[MoveEvidence.underTheSubjectGate]].
    */
  case Anchor(fromEnd: Int)

  /** THE AUTHOR'S PROSE, OR A TABLE'S CELLS — what the card actually asserts.
    *
    * `Back`, `Description`, `Text`, `Back Extra`, and both renderings of a table row. A move
    * cannot touch any of them, and neither can renaming the heading above them, which is what
    * makes this the one part of a note that a reorganisation leaves alone. IT IS THEREFORE THE
    * FLOOR: [[MoveEvidence.survey]] admits no pairing whose Substance is not identical, byte for
    * byte, on both sides.
    */
  case Substance

  /** WHERE THE CARD SITS — folders, file name, ancestor headings.
    *
    * `Context` and nothing else. `extract/CardContext.compose` builds it from the card's whole
    * location minus whatever that card already shows as a field, so it changes when the card
    * moves, when its file moves between folders, and when any ancestor above it is reworded.
    *
    * ITS DIVERGENCE IS THE SIGNAL RATHER THAN THE NOISE. Every other role agreeing while this one
    * differs is precisely what a move looks like from the outside.
    *
    * IT IS NEVER ASKED TO ACCOUNT FOR ITSELF, unlike [[Anchor]]. A breadcrumb is DISPLAY text —
    * it keeps the author's casing and spacing — so re-casing an ancestor changes it while moving
    * no key segment at all. Demanding that the key explain a `Context` divergence would therefore
    * refuse pairings for edits the key deliberately ignores. What grades the pairing instead is
    * the key's own segments, which are canonicalised; see [[Agreement]].
    */
  case Bearing

  /** WHAT THE MARKER ASKED FOR, rather than anything the author wrote as prose.
    *
    * `ThreeWay`, `ValueOnly`, `SameShape`, `Reveal`, `ConceptLabel`. A move cannot change one, so
    * [[MoveEvidence.survey]] REQUIRES them to agree, and that requirement does real work rather
    * than being a formality: `SameShape` is empty on a heading's two-field card and `"1"` on a
    * table's row card, and `ConceptLabel` is empty on a heading's concept-descriptor card and the
    * first column's header on a table's — so demanding agreement stops a heading card ever being
    * paired with a table card that happens to read alike.
    *
    * THE COST IS A FALSE NEGATIVE AND IT IS THE SAFE DIRECTION. An author who moves a heading AND
    * edits its marker in one go changes a Setting field, and this reports nothing about the pair.
    * A marker change is a different event with its own machinery — the retype path and the shrink
    * gate — and letting one mechanism speak about both is how a report comes to assert something
    * it did not look for.
    */
  case Setting

  /** THE KEY ITSELF, EXCLUDED FROM EVERY COMPARISON.
    *
    * A role rather than an omission, so that the declared table below stays TOTAL over each note
    * type's field list and a field cannot be forgotten by being left out of it.
    *
    * EXCLUDED FOR THE REASON `Planner.contentHash` EXCLUDES IT, arriving from the other side.
    * There, including it would make every card's hash unique and destroy the comparison; here,
    * the two identities are the very thing in question and are different by construction — a
    * pairing between a stranded note and an unclaimed key exists only because they disagree. A
    * comparison that included it would admit nothing, ever.
    */
  case Identity

object FieldRole:

  /** WHICH ROLE EACH FIELD OF EACH NOTE TYPE PLAYS.
    *
    * ═══ WHY THIS IS CHECKED AGAINST `Marker.FieldOrder` AT CONSTRUCTION ═══
    *
    * `Marker.FieldOrder` is the declaration of what fields a note type has, and it is already
    * bound to two other things — `model/Marker.test.scala` compares it against what
    * `CardSpec.fields` emits, and `anki/NoteTypeAssets.test.scala` against the installed
    * manifests. This table is a third consumer of that declaration and must not be able to drift
    * from it, because a field that gained no role would be COMPARED AS THOUGH IT WERE ONE THING
    * while being another — silently, and in the direction that invents pairings.
    *
    * So [[rolesFor]] refuses unless the names here are exactly the names there. A field added to
    * a note type therefore fails LOUDLY AND IMMEDIATELY, naming what is missing, rather than
    * being quietly assigned a default. There is no default; a role is a judgement about how the
    * extractor fills the field and only a person can make it.
    *
    * ═══ WHY THE NAMES ARE WRITTEN OUT ═══
    *
    * Some have constants in `Marker` and some do not — `Concept`, `Descriptor`, `Description`,
    * `Title` and `Text` live only inside the zip operands `Marker.ConceptDescriptorFields` and
    * `Marker.ClozeSequenceFields`, whose own docstrings forbid growing them and offer no
    * per-name handle. Indexing those vectors positionally would make this table silently wrong
    * under a reordering; naming the fields makes it wrong LOUDLY under the check above. Where a
    * constant exists it is used.
    */
  private val declared: Map[String, Map[String, FieldRole]] = Map(
    Marker.NoteTypes.Basic            -> basicRoles,
    Marker.NoteTypes.BasicAndReversed -> basicRoles,
    Marker.NoteTypes.Cloze -> Map(
      Marker.ClozeFields.Text      -> Substance,
      Marker.ClozeFields.BackExtra -> Substance,
      Marker.ContextField          -> Bearing,
      Marker.IdentityField         -> Identity,
    ),
    Marker.NoteTypes.ClozeSequence -> Map(
      "Title"              -> Anchor(0),
      "Text"               -> Substance,
      Marker.ContextField  -> Bearing,
      Marker.RevealField   -> Setting,
      Marker.IdentityField -> Identity,
    ),
    Marker.NoteTypes.ConceptDescriptor -> Map(
      // THE DESCRIPTOR IS THE MARKED HEADING AND THE CONCEPT IS ITS PARENT, so the two indices
      // are `0` and `1` and in that order. `extract/Extractor.scala`'s three-field arm binds the
      // concept to `ancestorTitles.lastOption`, and `extract/Tables.scala` keys a pair card as
      // `…/{row concept}/{column header}` — one rule, two card kinds, because the key of both
      // ends with the descriptor and carries the concept immediately above it.
      "Concept"                -> Anchor(1),
      "Descriptor"             -> Anchor(0),
      "Description"            -> Substance,
      Marker.ThreeWayField     -> Setting,
      Marker.ContextField      -> Bearing,
      Marker.ConceptLabelField -> Setting,
      Marker.ValueOnlyField    -> Setting,
      Marker.IdentityField     -> Identity,
    ),
  )

  /** SHARED BY THE TWO BASIC TYPES, exactly as `Marker.FieldOrder.BasicAndReversed` is
    * `Marker.FieldOrder.Basic`. The two note types differ in their TEMPLATES and in nothing else
    * a field comparison can see, so giving them separate tables would create two places for one
    * fact to be stated and one place for it to go stale.
    *
    * `Front` IS AN ANCHOR EVEN THOUGH A TABLE'S ROW CARD FILLS IT WITH A RENDERED TABLE, and the
    * imprecision cannot mislead. That card's front and back are two renderings of the same row,
    * so `Back` — which is Substance and therefore required to agree — already carries everything
    * the front does: a row card whose `Front` differs has a differing `Back` too, the floor
    * fails, and no pairing is admitted for the projection to be consulted about.
    */
  private def basicRoles: Map[String, FieldRole] = Map(
    Marker.BasicFields.Front -> Anchor(0),
    Marker.BasicFields.Back  -> Substance,
    Marker.ContextField      -> Bearing,
    Marker.SameShapeField    -> Setting,
    Marker.IdentityField     -> Identity,
  )

  /** The roles for one note type, or `None` for a note type this tool does not declare.
    *
    * `None` RATHER THAN A THROW, because an undeclared note type is a state a real collection
    * reaches: a note synced before 2026-08-21 sits on Anki's stock `Basic` until a retype moves
    * it. That is not a programming error and must not crash a report — but it IS a note this
    * mechanism cannot compare, which [[MoveFinding.Incomparable]] says out loud rather than
    * folding into "nothing matched".
    *
    * THE THREE CHECKS BELOW ARE THROWS, and the asymmetry is deliberate. An unknown note type is
    * the world being untidy; a DECLARED note type whose roles are wrong is THIS FILE being wrong,
    * and there is no correct behaviour available to it.
    *
    * THE SECOND CHECK IS THE VACUITY GUARD, and it is structural rather than value-level. The
    * floor of the whole comparison is "every [[Substance]] field agreed" — so a note type
    * declaring NO Substance field would satisfy that floor vacuously, admission would rest on the
    * marker gates alone, and every card of that type would agree with every other one. The
    * value-level twin of this hazard — a blank value agreeing with every other blank value —
    * needs no guard, because nothing the vault produces can have a blank substance:
    * `Body.fromExtracted` refuses a blank body and `SpecError.EmptyBody` makes it a hard error,
    * so an all-blank note fails ordinary equality against every candidate. That dependency is
    * named here rather than left implicit, because it is the reason this file carries no
    * equivalent of `spike/RenameEvidence.scala`'s `CellValue`.
    *
    * THE THIRD CHECK IS WHAT MAKES [[Agreement]] DEFINABLE. The name segments have to be the LAST
    * `n` of the path for "everything above them" to be a vector one can take — so the declared
    * `fromEnd` indices must be exactly `0 until n`, contiguous and without repeats. A table
    * declaring `0` and `2` would leave a segment belonging to neither axis, and every grade below
    * would then be answering about a path it had silently cut a hole in.
    */
  def rolesFor(noteType: String): Option[Map[String, FieldRole]] =
    declared.get(noteType).map { roles =>
      val expected = Marker.FieldOrder.byNoteType(noteType).toSet
      if roles.keySet != expected then
        sys.error(
          s"the field roles declared for '$noteType' do not cover its declared fields: " +
            s"missing ${(expected -- roles.keySet).toVector.sorted.mkString(", ")}; " +
            s"unknown ${(roles.keySet -- expected).toVector.sorted.mkString(", ")}"
        )
      if !roles.values.exists(_ == Substance) then
        sys.error(
          s"'$noteType' declares no Substance field, so every card of it would agree with " +
            "every other one — see FieldRole.rolesFor"
        )
      val indices = roles.values.collect { case Anchor(fromEnd) => fromEnd }.toVector.sorted
      if indices != indices.indices.toVector then
        sys.error(
          s"'$noteType' declares Anchor fields at positions ${indices.mkString(", ")} counted " +
            s"from the end of the key path, which is not ${indices.indices.mkString(", ")} — the " +
            "name segments must be the LAST ones, or Agreement cannot say what sits above them"
        )
      roles
    }

  /** HOW MANY SEGMENTS AT THE END OF A KEY PATH THIS NOTE TYPE SHOWS AS FIELDS.
    *
    * Two for the concept-descriptor, one for the basic and sequence types, none for a cloze —
    * whose card carries its body and its breadcrumb and never its heading. Derived from the table
    * rather than declared beside it, so the two cannot disagree.
    */
  def nameDepth(roles: Map[String, FieldRole]): Int =
    roles.values.count {
      case Anchor(_)                            => true
      case Substance | Bearing | Setting | Identity => false
    }

/** WHAT THE AUTHOR DECLARED ABOUT THEIR OWN CONTENT: does a card's substance identify what the card
  * is about?
  *
  * ═══ WHY THIS IS A DECLARATION AND NOT A MEASUREMENT ═══
  *
  * `docs/design/IDENTITY-DECISION-SHEET.md`'s ruled principle of 2026-09-13: the card KIND is the
  * author's own statement about their content. `2way` is written exactly when they expect to recall
  * the heading from the body; `1way` exactly when they know they cannot. So "does this content
  * identify this card" is answered by reading the marker rather than by judging the prose — and the
  * answer is trusted absolutely while coherent, with no confidence weight anywhere near it.
  *
  * ═══ WHY THREE STATES AND NOT A BOOLEAN ═══
  *
  * Because [[Unstated]] is a different thing from [[NonIdentifying]], and a boolean would have to
  * lie about one of them. A cloze card's author was never offered the choice — there is no
  * `#flashcard/cloze/1way` — so silence from that kind is not a declaration that its passage
  * identifies nothing. Two rules read this type and they ask DIFFERENT questions of the three
  * states, which is the clearest evidence that collapsing them would be wrong:
  *
  *   - The relabel gate drops its place requirement only for [[Identifying]] (the entailment of
  *     2026-09-13 about declared-identifying kinds).
  *   - The voucher rule refuses a reattachment only for [[NonIdentifying]] ("no voucher, no edit",
  *     the same day) — silence refuses nothing, because no ruling has refused it.
  *
  * `MarkedHeadings` in `extract/VaultWalker.scala` is the precedent for the shape: three states
  * because there are three, and a boolean there "told a lie".
  */
enum SubstanceDeclaration:

  /** The author asked to recall the card's own name FROM its substance — `#flashcard/2way`,
    * `#flashcard/cdd/2way`, `#flashcard/cdd/3way`, a bare `#flashcard/table`, `#flashcard/table/2way`,
    * `#flashcard/table/3way`, and the older `#flashcard/3way` spellings. A content match on such a
    * card is same-card evidence, by declaration.
    */
  case Identifying

  /** The author asked one way only — `#flashcard/1way`, `#flashcard/cdd/1way`,
    * `#flashcard/table/1way`. They have said the content does NOT identify what it is about, so a
    * content match is evidence of nothing on its own.
    */
  case NonIdentifying

  /** THIS KIND CARRIES NO SUCH DECLARATION, so neither answer may be attributed to its author. A
    * cloze card and a sequence card are the two today: no marker spells a direction for either.
    */
  case Unstated

object SubstanceDeclaration:

  /** WHAT A NOTE TYPE AND ITS FIELDS SAY, and the two families answer in two different places.
    *
    * THE TWO-FIELD FAMILY ANSWERS BY NOTE TYPE. `#flashcard/1way` and `#flashcard/2way` differ in
    * the TEMPLATES Anki needs — one card or two — so they are two note types, and the note type
    * carries the declaration with no field to read.
    *
    * THE THREE-FIELD FAMILY ANSWERS BY FIELD, because every three-field way shares one note type and
    * the directions are conditionals inside its templates. The field is `Marker.ValueOnlyField`, set
    * exactly when a marker asked for the value direction ALONE. It is INVERTED by its own design —
    * the template tests `{{^ValueOnly}}` — so EMPTY means the concept-recall card is generated, which
    * is why `isEmpty` reads as [[Identifying]] here. That inversion also gives the right answer for a
    * note synced before the field existed, which is the reason it was defined that way round.
    *
    * A LOUD FAILURE ON AN UNDECLARED NOTE TYPE, and it is unreachable rather than defensive: every
    * caller asks about a pairing that got through [[MoveEvidence.survey]]'s `rolesOn`, which answers
    * [[MoveFinding.Incomparable]] for a note type this tool does not declare. Arriving here means
    * this tool has contradicted itself, and a sixth note type must be told what it declares rather
    * than inheriting somebody's default — `plan/MoveEvidence.test.scala` sweeps
    * `Marker.NoteTypes.All` so that the omission fails immediately.
    */
  def of(noteType: String, fields: Map[String, String]): SubstanceDeclaration = noteType match
    case Marker.NoteTypes.BasicAndReversed => Identifying
    case Marker.NoteTypes.Basic            => NonIdentifying
    case Marker.NoteTypes.Cloze | Marker.NoteTypes.ClozeSequence => Unstated
    case Marker.NoteTypes.ConceptDescriptor =>
      val valueOnly = fields.getOrElse(
        Marker.ValueOnlyField,
        sys.error(
          s"a '$noteType' card carries no '${Marker.ValueOnlyField}', which it declares — so what " +
            "its author said about the description cannot be read; see Marker.FieldOrder"
        ),
      )
      if valueOnly.isEmpty then Identifying else NonIdentifying
    case other =>
      sys.error(
        s"'$other' is not a note type this tool declares, so nothing can be read about what its " +
          "author declared — MoveEvidence.survey cannot admit such a note, so this is a defect in " +
          "this tool"
      )

/** ONE FIELD THAT DID NOT AGREE, with both values and what the field is made of.
  *
  * BOTH VALUES TRAVEL, because a person reading what a run did has to see what changed — "the
  * breadcrumb was `System Design › Essential Numbers` and is now `Scaling › Essential Numbers`"
  * is the sentence that makes a reassignment checkable, and neither half of it is derivable from
  * the other. Naming the field alone would leave a reader opening Anki to find out what this
  * already knows.
  *
  * IT IS IN THE TYPE RATHER THAN IN REPORT FORMATTING, and Marc's 2026-09-05 ruling is why: a
  * run that moves review history without saying what it moved and why is exactly the silent
  * change this project is built to prevent. Printing it is mandatory, so it must survive as far
  * as the printer.
  *
  * `inAnki` FIRST BECAUSE THAT IS THE OLDER VALUE. The observed note holds what the last
  * successful sync wrote; the spec holds what the vault says now.
  */
final case class Divergence(field: String, role: FieldRole, inAnki: String, inVault: String):
  def describe: String = s"$field: '$inAnki' → '$inVault'"

/** HOW MUCH AGREED, derived from WHICH PARTS OF THE KEY moved and never stored beside them.
  *
  * ═══ WHY FOUR CASES AND NOT A SCORE ═══
  *
  * A pairing is admitted only when Substance and Setting agree exactly and every name divergence
  * is accounted for, so the only things left free to differ are the card's OWN NAME and WHERE IT
  * SITS — two independent bits, and therefore four outcomes, each of which is a DIFFERENT EVENT
  * rather than a different amount of the same one. That is what makes this an enum rather than a
  * number: there is nothing here to be more or less of.
  *
  * ═══ COMPUTED FROM KEY SEGMENTS, NOT FROM THE RENDERED FIELDS ═══
  *
  * The two bits are read off the two CARD PATHS rather than off `Context` and the name fields,
  * and that is the one place this design departs from a field-by-field reading. Key segments are
  * CANONICALISED — NFC-normalised, whitespace-collapsed, case-folded, marker-stripped
  * (`model/CardKey.scala`) — while a breadcrumb and a card face are DISPLAY text that keeps every
  * one of those. So re-casing an ancestor heading changes `Context` and moves no segment, and a
  * grade read off the fields would call that a relocation. Read off the key it is what it is: the
  * card did not move.
  *
  * ═══ DERIVED, SO IT CANNOT DISAGREE WITH ITS OWN EVIDENCE ═══
  *
  * Computed from the finding's two keys every time it is asked for. Storing it alongside would
  * create two statements of one fact, and the failure that follows is the one this codebase has
  * met before — a summary that outlives the thing it summarises.
  */
enum Agreement:

  /** THE WHOLE PATH AGREED; only the note it belongs to changed.
    *
    * Reached when a file's frontmatter `id` changes while its heading tree does not, which
    * re-keys every card in the file and leaves every field alone — and by a heading carried
    * VERBATIM, ancestors and all, into a different note.
    */
  case Total

  /** Its own name agreed; where it sits did not.
    *
    * ═══ THIS IS WHAT A MOVE LOOKS LIKE ═══ and it is the case this file exists for. An `## X`
    * that became a `### X` somewhere else keeps its own heading text; the only thing that changed
    * is the chain above it. So does the file being dragged into another folder, in the cases
    * where a folder is part of the chain.
    *
    * THE NAME HALF IS VACUOUS FOR A NOTE TYPE WITH NO ANCHOR FIELD, which today is `Obsidian
    * Cloze` alone. For such a card this says "where it sits differs", which is everything there
    * is to say about it.
    */
  case NameAndSubstance

  /** Where it sits agreed; its own name did not.
    *
    * A heading REWORDED IN PLACE. Its own text is a key segment, so the key moves; the chain
    * above it did not.
    */
  case PlaceAndSubstance

  /** Neither its own name nor where it sits agreed — two edits at once, or ONE edit to a
    * concept-descriptor card built from a heading: such a card's `Concept` is its parent, so
    * moving it under a different parent moves a name segment and a place segment together.
    */
  case SubstanceAlone

  def describe: String = this match
    case Total            => "the same path, in a different note"
    case NameAndSubstance => "the same name, somewhere else"
    case PlaceAndSubstance => "a different name, in the same place"
    case SubstanceAlone   => "a different name, somewhere else"

/** HOW THIS RUN KNOWS THE SUBJECT A CARD LEFT GOES ON EXISTING — the three witnesses the rulings
  * of 2026-09-13 admit, and they are different claims rather than three strengths of one.
  *
  * ═══ WHY THE SECOND ONE EXISTS ═══
  *
  * The first is a fact about the note's own tree, and it was the whole check until the final
  * adversarial review found the two shapes it answers "gone" to while the concept plainly goes on:
  * the concept MOVED TO ANOTHER NOTE with one of its descriptors (the reviewer's S24A), and the
  * concept was RE-NESTED under a new ancestor so its node path changed (S24B). In both, the same
  * run pairs the travelling descriptor onto a key that spells the concept out — and then reads the
  * concept's absence from this note as its disappearance. One run, both halves of a contradiction.
  *
  * Marc ruled it on 2026-09-13 (`docs/design/IDENTITY-DECISION-SHEET.md`): a concept counts as
  * surviving when THIS SAME SURVEY corroborated a card onto it. The run's own conclusions are the
  * witness, which makes the contradiction structurally impossible rather than merely unlikely.
  *
  * ═══ WHY THE DISTINCTION IS CARRIED RATHER THAN COLLAPSED ═══
  *
  * [[RelabelDoubt]]'s precedent: what the report says must match what the run actually established.
  * "'kafka' is still in the vault" is what a reader of a same-note survival needs; a reader whose
  * note holds no `# Kafka` at all — S24A's — would open it, find nothing, and conclude the tool
  * was lying. The second case names the pairing instead, which is the fact and is checkable.
  *
  * ═══ WHY THE THIRD ONE EXISTS: THE FIRST TWO BOTH EXPIRE AT THE SYNC BOUNDARY ═══
  *
  * The attack swarm split S24A over TWO RUNS, which is all it took. Run one carries `# Kafka` and
  * `## Cost` into another note, and the corroboration onto `kafka / cost` happens THERE. Run two
  * relabels the `# Kafka` this note no longer has to `# NATS` and re-parents `## Definition` under
  * it — and now both of the first two witnesses answer "gone" honestly: the node is not in this
  * note, and run two pairs nothing onto Kafka, because run one already finished that job. The
  * descriptor's history follows a subject change the run cannot see it made.
  *
  * `docs/design/IDENTITY-DECISION-SHEET.md` records this as ENTAILED by the standing rulings
  * rather than newly ruled: a live `cdd`/table card whose `Concept` is Kafka DECLARES that Kafka
  * exists, declarations are contracts trusted absolutely, and no ruling anywhere says evidence
  * expires. So the collection the run is looking at is itself the third witness — no clock, no
  * ledger, no git, only labels Anki already holds. [[LiveDeclarations]] is the reading.
  */
enum SubjectSurvival:

  /** The node census found the subject's path still in the stranded card's own note. `path` is
    * addressed from the note's root, which is how a node is addressed.
    */
  case StillInTheNote(path: Vector[String])

  /** This survey corroborated `card` onto the subject that stood at `concept` — so the run itself
    * moved a card that was there, wherever in the vault it has now put it.
    *
    * `concept` IS THE CHAIN THE SUBJECT STOOD AT, addressed from the note's root exactly as
    * [[StillInTheNote]]'s path is, and read off the pairing's OLD key rather than its new one. That is
    * what lets a RELOCATED concept witness while a mere namesake cannot: the old key is where the
    * subject was, so it bridges to wherever the card went. _Corrected 2026-09-13 — this carried the
    * candidate's subject as a bare NAME, which had one note's `## Performance` testifying about
    * another's._
    */
  case CorroboratedOnto(concept: Vector[String], card: CardKey)

  /** A card the collection ALREADY HOLDS, which no run of this tool is orphaning, declares a subject
    * at the chain `concept` — so that concept goes on existing whether or not anything happened to it
    * in this run. `concept` is the chain, read the same way [[CorroboratedOnto]]'s is and qualified
    * for the same reason.
    */
  case StillInTheCollection(concept: Vector[String], card: CardKey)

  def describe: String = this match
    case StillInTheNote(path) => s"'${path.mkString(" / ")}' is still in the vault"
    case CorroboratedOnto(concept, card) =>
      s"'${concept.mkString(" / ")}' goes on existing: this same run pairs " +
        s"'${card.path.render}' in ${card.noteId.value} onto it"
    case StillInTheCollection(concept, card) =>
      s"'${concept.mkString(" / ")}' goes on existing: the collection already holds " +
        s"'${card.path.render}' in ${card.noteId.value} under it"

/** WHICH CONCEPTS THE COLLECTION ITSELF DECLARES — the third witness of survival, read off the
  * cards Anki already holds.
  *
  * ═══ WHAT COUNTS AS A DECLARATION, AND WHY IT IS PER-KIND ═══
  *
  * A card DECLARES a concept when its own kind makes the parent constitutive — which is exactly the
  * distinction [[FieldRole.nameDepth]] already draws, so this asks THAT and never a note type's
  * name. A window two segments wide has a subject above the card's own name; a window one segment
  * wide has nothing above it, so `dropRight(1)` leaves the empty vector and the card witnesses
  * nothing. That is not a coincidence to be tidied into a note-type check: `1way` filing under a
  * heading is precisely the case the per-kind ruling already refused as a witness, because such a
  * card's ancestor is FILING rather than a term of what the card asserts. A fifth note type would
  * join the witnesses or not according to the roles it declares, in one place, rather than by
  * somebody remembering to add its name here.
  *
  * A NOTE TYPE THIS TOOL DOES NOT DECLARE WITNESSES NOTHING EITHER, and for the same reason rather
  * than as a precaution: it has no `Concept` field, so it declares no concept. That is where every
  * note synced before this tool took note types of its own still sits, and
  * [[MoveFinding.Incomparable]] is what the survey says about such a note when it is the one being
  * explained.
  *
  * ═══ WHICH CARDS ARE "LIVE", WHICH IS THE CALLER'S ANSWER AND NOT THIS TYPE'S ═══
  *
  * `plan/Planner.scala` passes the notes whose keys the vault still accounts for — i.e. every note
  * this run is NOT orphaning, a parked orphan whose key has come back included. Sheltered cards are
  * among them: the vault could not read their section, so their label might be stale. Including
  * them is the SAFE direction rather than an oversight, because a witness can only ever turn a
  * follow into a park — it never licenses a reassignment — so the error it risks is a history left
  * where it is, never a history moved onto the wrong card.
  *
  * ═══ A CARD DECLARES A SUBJECT *AT A PLACE*, AND THE PLACE IS ITS CHAIN ═══
  *
  * _Corrected 2026-09-13._ This used to match on the subject as a bare NAME, vault-wide, and the
  * cost was written down here as a thing being paid for: "a live `# Kafka` descriptor about the
  * novelist blocks the message broker's rename from following". That cost is not payable, and R2 is
  * why — the parent concept is constitutive, so `Kafka.md`'s `## Performance` and `NATS.md`'s
  * `## Performance` are two concepts sharing a spelling rather than one concept in two files. A
  * witness must therefore stand at the chain the stranded card left, and a namesake at an unrelated
  * chain is silence. Marc, shown the pair: "you realize that there are no questions there?"
  *
  * STILL UNSCOPED BY NOTE, AND THAT IS NOT AN OVERSIGHT. The chain is compared; the note id is not.
  * It cannot be: the whole reason this witness exists is a concept that left for ANOTHER note in an
  * earlier sync, so requiring the note to agree would retire the witness altogether — the two-run
  * shape in [[SubjectSurvival]] is that case. What remains payable is narrow and worth naming: two
  * notes that hold the same chain, `# Kafka` / `## Performance` in each, still testify about one
  * another. That is a genuine ambiguity about where one concept lives rather than a namesake at an
  * unrelated place, and it errs toward parking, which is the direction that keeps a history rather
  * than moving it.
  *
  * ONE WITNESS PER CHAIN, THE FIRST IN THE COLLECTION'S SORTED ORDER, because the report names it and
  * two runs over an unchanged collection must name the same one.
  */
final class LiveDeclarations private (
    private val byConcept: Map[Vector[String], CardKey],
    /** The notes this was read from. Carried so that [[MoveEvidence.survey]] can refuse a caller
      * that hands it a note as BOTH a stranded note and a live witness — see there for why that
      * particular mistake is worth a hard error rather than a comment.
      */
    val noteIds: Set[AnkiNoteId],
):

  def witnessFor(concept: Vector[String]): Option[CardKey] = byConcept.get(concept)

object LiveDeclarations:

  def of(live: Vector[ObservedCard]): LiveDeclarations =
    val declared = live
      // SORTED HERE rather than relied on from the caller, so that the witness a report names is a
      // function of the collection and not of the order somebody happened to build a vector in.
      .sortBy(c => (c.key.noteId.value, c.key.path.render, c.note.id.value))
      .flatMap { card =>
        for
          roles <- FieldRole.rolesFor(card.note.noteType)
          segments = MoveEvidence.segmentsOf(card.key.path)
          // THE NAME WINDOW SAYS WHETHER THIS KIND DECLARES A SUBJECT AT ALL; THE CHAIN SAYS WHERE
          // IT STANDS. Two questions, and they must not be answered by one value: a chain is
          // non-empty above a plain heading card too, so keying by the chain without keeping the
          // window test would promote every filing heading to a witness.
          declaresASubject = MoveEvidence.nameSegments(segments, FieldRole.nameDepth(roles)).dropRight(1)
          if declaresASubject.nonEmpty
        yield segments.dropRight(1) -> card.key
      }
    new LiveDeclarations(
      declared.groupMap(_._1)(_._2).view.mapValues(_.head).toMap,
      live.map(_.note.id).toSet,
    )

  /** A collection that declares nothing — for the callers and tests whose question does not involve
    * one. NOT a default parameter anywhere: a survey run against no live cards at all is a
    * different question from one run against a collection, and a call site has to say which.
    */
  val none: LiveDeclarations = new LiveDeclarations(Map.empty, Set.empty)

/** WHY A SUBJECT CHANGE THAT LOOKS LIKE A RELABEL IS NOT FOLLOWED ANYWAY.
  *
  * TWO CASES, AND THEY ARE NOT TWO SEVERITIES OF ONE THING. The first is a fact about the vault —
  * two things moved at once, so the ruling grades the evidence weaker. The second is a fact about
  * THIS RUN — it could not establish the premise at all. A reader who is told "the cluster moved"
  * about a run that could not look has been told something false.
  */
enum RelabelDoubt:

  /** The subject changed and so did where the cluster sits, or the card crossed into another
    * note. Ruled a question rather than a follow (Decision 2, 2026-09-12).
    */
  case ClusterMoved

  /** The census could not say whether the old subject survives, so nothing may be concluded from
    * its absence. `reason` is [[NodeCensus.Answer.Unsurveyable]]'s own words, carried so the
    * report can say WHY it declined rather than merely that it did.
    */
  case CensusUnavailable(reason: String)

  def describe: String = this match
    case ClusterMoved => "the cluster moved as well, so the rename is not vouched for"
    case CensusUnavailable(reason) =>
      s"whether the old subject survives could not be established: $reason"

/** WHAT THE EVIDENCE SHOWS about ONE stranded note. Nine outcomes, and each says a different thing
  * to whoever reads it.
  *
  * ONLY ONE OF THEM IS AN INSTRUCTION, AND IT IS THE FIRST. Every other case says what agreed and
  * stops there; `spike/RenameEvidence.scala` settled that vocabulary for a renamed table column
  * and its reasoning transfers whole. What changed on 2026-09-05 is that the corroborated case
  * became actionable — see this file's header for Marc's ruling and the asymmetry behind it.
  *
  * THE COUNT KEEPS GROWING AND THAT IS THE DESIGN WORKING, not a type sprawling: each ruling of
  * 2026-09-12 and 2026-09-13 distinguished a population that had been answered with somebody else's
  * sentence, and the way this file refuses to act on evidence is by giving the evidence a case with
  * no `reassignment` method on it.
  */
enum MoveFinding:

  /** EXACTLY ONE unclaimed key agrees, no other stranded note agrees with that same key, and
    * every name divergence is accounted for by the key. THE ONE CASE THAT MAY REASSIGN.
    *
    * The mutual half is the one that is easy to forget: one candidate from this note's side is
    * not enough, because two notes cannot both have become the same card.
    *
    * IT CARRIES THE NOTE TYPE, which looks redundant beside two keys and is not. [[agreement]]
    * has to know how many segments at the end of the path this card shows as fields before it
    * can say which of them moved, and that is a fact about the note type. Carrying it keeps the
    * grade derived rather than stored.
    */
  case Corroborated(
      stranded: CardKey,
      noteId: AnkiNoteId,
      candidate: CardKey,
      where: SourceRef,
      noteType: String,
      divergences: Vector[Divergence],
  )

  /** SEVERAL unclaimed keys agree, so the evidence ranks nothing.
    *
    * The candidates are named and no pick is made. A consumer must be able to tell this from
    * [[Corroborated]] BY TYPE rather than by reading the length of a list, which is why it is its
    * own case: `plan/SyncAction.scala` already rules that a list of maybes is how a report stops
    * being read, and that ruling only holds if a maybe cannot be mistaken for an answer.
    */
  case Ambiguous(stranded: CardKey, noteId: AnkiNoteId, candidates: NonEmptyVector[CardKey])

  /** ONE unclaimed key agrees, but another stranded note agrees with it too.
    *
    * The pairing is not mutual, so it is evidence of nothing: two notes cannot both have become
    * the same card. Its own case rather than folded into [[Ambiguous]] because from THIS note's
    * side there genuinely is only one candidate, and reporting that as ambiguous with a
    * single-element list would read as a contradiction.
    */
  case Contested(
      stranded: CardKey,
      noteId: AnkiNoteId,
      candidate: CardKey,
      alsoClaimedBy: NonEmptyVector[CardKey],
  )

  /** ONE unclaimed key agrees and is uncontested, BUT THE CARD'S NAME CHANGED FOR A REASON THE
    * KEY DOES NOT EXPLAIN.
    *
    * ═══ WHAT THIS CATCHES, AND WHY IT IS NOT A REFUSAL ═══
    *
    * A name field is the display of a key segment ([[FieldRole.Anchor]]), so a pairing may be acted
    * on when the key HAS a segment at that field's position on both sides — whether it moved or
    * merely re-rendered. This case is what is left when there is NO SEGMENT THERE AT ALL to show
    * what the field displays: the concept-descriptor card whose marked heading has no ancestor and
    * whose `Concept` is therefore THE FILE NAME (`Extractor.buildSpecs`,
    * `ancestorTitles.lastOption.getOrElse(fileName)`). Rename that file and the concept changes with
    * nothing in the key to show for it.
    *
    * THE RE-BOLDED HEADING USED TO ARRIVE HERE AND NO LONGER DOES. Decision 4 of
    * `docs/design/IDENTITY-DECISION-SHEET.md` (2026-09-12) ruled that markup over an agreed segment
    * is rendering rather than identity, so that shape is [[Corroborated]] and follows its move; deck
    * scenario S11 pins it. What still reaches this case is the file-name-derived name — S27, S60 and
    * S64.
    *
    * REPORTED RATHER THAN REFUSED, AND THAT IS THE POINT OF GIVING IT A CASE. Folding it into
    * [[Unexplained]] would say "nothing the vault produces agrees with this note", which is
    * false: the substance agreed exactly and there is one candidate. The evidence is real and it
    * is merely not sufficient to act on — which is precisely the shape of thing
    * `docs/design/REVIEW-QUEUE.md` exists for, and precisely the shape of thing that must not be
    * silently dropped.
    *
    * `unaccountedFor` IS THE SUBSET THAT COULD NOT BE EXPLAINED, non-empty by construction —
    * a pairing with none of them is [[Corroborated]].
    */
  case Unaccounted(
      stranded: CardKey,
      noteId: AnkiNoteId,
      candidate: CardKey,
      where: SourceRef,
      unaccountedFor: NonEmptyVector[Divergence],
  )

  /** THE CARD'S SUBJECT CHANGED AND THE SUBJECT IT LEFT GOES ON EXISTING — so this is not the
    * same card, and there is nothing here to pair.
    *
    * ═══ WHAT IT IS, IN ONE SENTENCE ═══
    *
    * A concept-descriptor card's parent concept is CONSTITUTIVE — the card asserts a
    * three-way relation, and the concept is one of its three terms (standing ruling R2). So
    * `## Definition` moved from under `# Kafka` to under `# NATS`, while `# Kafka` still stands,
    * is a NEW card under NATS and a DELETED card under Kafka. Deck scenario S24.
    *
    * ═══ WHY IT IS NOT [[RelabelUnvouched]], WHICH IS THE DISTINCTION THAT EARNS IT A CASE ═══
    *
    * The two arise from the same key comparison and ask for opposite things from a reader. This
    * one says THERE IS NOTHING TO PAIR: a future review surface must not offer a human the choice
    * of moving this history, because the ruling has already answered it. That one says POSSIBLY
    * THE SAME CARD, relabelled and relocated at once, which is exactly the shape
    * `docs/design/REVIEW-QUEUE.md` exists for. Behind one name with a boolean they would be one
    * population with two remedies, which is the defect every world's policy confessed to.
    *
    * `survival` IS THE EVIDENCE, not a convenience: it is HOW this run knows the subject goes on
    * existing — a node the census found in the note, or a card this same survey paired onto that
    * subject (the ruling of 2026-09-13, see [[SubjectSurvival]]). Without it the report can say
    * "a different card" and not why.
    *
    * IT CARRIES EVERY DIVERGENCE, like [[Corroborated]] and under the same ruling of 2026-09-04:
    * a run that will not act must still say what it saw.
    */
  case Reparented(
      stranded: CardKey,
      noteId: AnkiNoteId,
      candidate: CardKey,
      where: SourceRef,
      survival: SubjectSurvival,
      divergences: Vector[Divergence],
  )

  /** THE CARD'S SUBJECT CHANGED, THE OLD SUBJECT IS GONE — AND SOMETHING ELSE MOVED TOO, so the
    * follow is not vouched for.
    *
    * ═══ THE RULING THIS IMPLEMENTS ═══
    *
    * Decision 2 of `docs/design/IDENTITY-DECISION-SHEET.md`, ruled 2026-09-12, follows a subject
    * change only when the cluster stayed in place: "the path weighs both ways — a name change
    * combined with a move grades weaker and becomes a question". This is that question, and it is
    * REPORTED rather than applied.
    *
    * [[RelabelDoubt]] says which of the two doubts it is, and the second is the honesty half: a
    * census that could not be taken cannot establish that the old subject is gone, and "I could
    * not look" must never be spent as "I looked and it was not there".
    *
    * ═══ WHAT IT IS NOT ═══
    *
    * NOT [[Unaccounted]], though both are "one candidate, nothing applied". That one says the
    * card's NAME changed for a reason the KEY does not explain. Here the key explains the name
    * perfectly; what is unvouched for is the INFERENCE from the key to sameness.
    *
    * NOT [[Ambiguous]] or [[Contested]] either: the pairing is mutually unique, and a reader who
    * saw those would go looking for a rival claimant that does not exist.
    */
  case RelabelUnvouched(
      stranded: CardKey,
      noteId: AnkiNoteId,
      candidate: CardKey,
      where: SourceRef,
      cause: RelabelDoubt,
      divergences: Vector[Divergence],
  )

  /** THE SUBSTANCE AGREES EXACTLY AND NOTHING VOUCHES THAT THE TWO ARE THE SAME CARD — so the
    * orphan stays parked and the new section starts at zero.
    *
    * ═══ THE RULE, WHICH IS A CONJUNCTION AND THEREFORE NEEDS NO DISCRIMINATOR FIELD ═══
    *
    * Resolved 2026-09-13 (`docs/design/IDENTITY-DECISION-SHEET.md`, "no voucher, no edit"): an
    * orphaned note may be EDITED onto a new section only when something vouches they are the same
    * card, and there are exactly three vouchers — the author's DECLARATION that the content
    * identifies what it is about, the LOCATION agreeing, or the SINGLE EDIT of a note this very run
    * stranded. This case is built only when all three are absent, so its existence IS the
    * conjunction: there is no variant of it to carry and no version of it a reader could mistake for
    * a different shape of doubt. [[MoveEvidence.underTheVoucherGate]] is the one place it is minted.
    *
    * ═══ WHY IT IS NOT ANY OF THE CASES THAT NEARLY FIT ═══
    *
    * NOT [[Unexplained]], which would be the tempting quiet answer and is a false one: the substance
    * agreed byte for byte and there IS one candidate. Saying "matches nothing the vault now
    * produces" about it would hide real evidence from the one report that exists to show it — the
    * argument [[Unaccounted]] already makes for having its own case.
    *
    * NOT [[RelabelUnvouched]], though the words are close. That one is about a SUBJECT that changed
    * and a path that cannot vouch for the rename; here nothing about the card's own name need have
    * changed at all — the canonical case is a heading recreated VERBATIM in another note, a sync
    * later.
    *
    * NOT [[Reparented]]: no subject survives anywhere, and nothing here is a different card under a
    * different subject. It may well be the same card; what is missing is anything entitled to say so.
    *
    * IT CARRIES EVERY DIVERGENCE, like every other reporting case and under the ruling of
    * 2026-09-04: a run that will not act must still say what it saw.
    */
  case NoVoucher(
      stranded: CardKey,
      noteId: AnkiNoteId,
      candidate: CardKey,
      where: SourceRef,
      divergences: Vector[Divergence],
  )

  /** Nothing the vault now produces agrees with this note.
    *
    * THE COMPARISON WAS MADE AND CAME BACK EMPTY, which is the whole difference between this and
    * [[Incomparable]]. The card was deleted, or it moved and its body was edited in the same
    * commit, or its marker changed as well — and nothing available here can distinguish those.
    */
  case Unexplained(stranded: CardKey, noteId: AnkiNoteId)

  /** THE COMPARISON COULD NOT BE MADE AT ALL, and saying "nothing matched" would be a lie.
    *
    * The distinction is `OrphanInference.Computed` versus `SuppressedIncompleteScan`, one level
    * down: a run that could not look must not be mistaken for a run that looked and found
    * nothing. Two states reach it, and `reason` says which — a note on a note type this tool does
    * not declare, which is where every note synced before it took note types of its own sits
    * until a retype moves it; and a note whose field NAMES are not its declared note type's,
    * which a half-finished note-type repair leaves behind.
    */
  case Incomparable(stranded: CardKey, noteId: AnkiNoteId, reason: String)

  /** THE NOTE THIS FINDING IS ABOUT. Every case has exactly one, which is what makes the
    * conservation property statable: every note the survey considered stranded appears in exactly
    * one finding.
    *
    * NAMED APART FROM THE FIELD IT READS, following `SyncAction.cardKey` over a `key` field and
    * for the same reason: an enum's cases are subclasses, so a method sharing a case field's name
    * is an override rather than a projection and will not compile.
    */
  def strandedNote: AnkiNoteId = this match
    case Corroborated(_, id, _, _, _, _)      => id
    case Ambiguous(_, id, _)                  => id
    case Contested(_, id, _, _)               => id
    case Unaccounted(_, id, _, _, _)          => id
    case Reparented(_, id, _, _, _, _)        => id
    case RelabelUnvouched(_, id, _, _, _, _)  => id
    case NoVoucher(_, id, _, _, _)            => id
    case Unexplained(_, id)                   => id
    case Incomparable(_, id, _)               => id

  /** For a person reading a run.
    *
    * EVERY SENTENCE NAMES THE ANKI NOTE, and only the first names an action — because only the
    * first IS one. `cli/Report.scala` decides where these lines sit; what they SAY is decided
    * here, so that the wording cannot drift from the case it describes.
    */
  def describe: String = this match
    case c @ Corroborated(stranded, id, candidate, where, _, divergences) =>
      s"note ${id.value}, which held '${stranded.path.render}' in ${stranded.noteId.value}, is " +
        s"'${candidate.path.render}' in ${candidate.noteId.value} — ${c.agreement.describe}, " +
        s"${where.describe}" +
        (if divergences.isEmpty then ""
         else s"; ${divergences.map(_.describe).mkString(", ")}")

    case Ambiguous(stranded, id, candidates) =>
      s"note ${id.value}, which held '${stranded.path.render}' in ${stranded.noteId.value}, " +
        s"agrees equally with ${candidates.length} cards, so nothing is established: " +
        candidates.toVector.map(k => s"'${k.path.render}' in ${k.noteId.value}").mkString("; ")

    case Contested(stranded, id, candidate, others) =>
      s"note ${id.value}, which held '${stranded.path.render}' in ${stranded.noteId.value}, " +
        s"agrees with '${candidate.path.render}' in ${candidate.noteId.value} — but so do " +
        others.toVector.map(k => s"'${k.path.render}'").mkString("; ") +
        ", so nothing is established"

    case Unaccounted(stranded, id, candidate, where, unaccountedFor) =>
      s"note ${id.value}, which held '${stranded.path.render}' in ${stranded.noteId.value}, " +
        s"says the same thing as '${candidate.path.render}' in ${candidate.noteId.value} " +
        s"(${where.describe}) — but its name changed for a reason the key does not explain, so " +
        s"nothing is applied: ${unaccountedFor.toVector.map(_.describe).mkString(", ")}"

    case Reparented(stranded, id, candidate, where, survival, divergences) =>
      s"note ${id.value}, which held '${stranded.path.render}' in ${stranded.noteId.value}, " +
        s"says the same thing as '${candidate.path.render}' in ${candidate.noteId.value} " +
        s"(${where.describe}) — but ${survival.describe}, so " +
        s"this is a DIFFERENT card under a different subject and nothing is applied" +
        (if divergences.isEmpty then ""
         else s"; ${divergences.map(_.describe).mkString(", ")}")

    case RelabelUnvouched(stranded, id, candidate, where, cause, divergences) =>
      s"note ${id.value}, which held '${stranded.path.render}' in ${stranded.noteId.value}, " +
        s"may have been renamed to '${candidate.path.render}' in ${candidate.noteId.value} " +
        s"(${where.describe}) — ${cause.describe}, so nothing is applied" +
        (if divergences.isEmpty then ""
         else s"; ${divergences.map(_.describe).mkString(", ")}")

    case NoVoucher(stranded, id, candidate, where, divergences) =>
      s"note ${id.value}, which held '${stranded.path.render}' in ${stranded.noteId.value}, " +
        s"says the same thing as '${candidate.path.render}' in ${candidate.noteId.value} " +
        s"(${where.describe}) — but nothing vouches that they are the same card: this content was " +
        "declared not to identify what it is about, it is in a different note, and this note was " +
        "parked by an earlier run, so nothing is applied and the new card starts at zero" +
        (if divergences.isEmpty then ""
         else s"; ${divergences.map(_.describe).mkString(", ")}")

    case Unexplained(stranded, id) =>
      s"note ${id.value}, which held '${stranded.path.render}' in ${stranded.noteId.value}, " +
        "matches nothing the vault now produces"

    case Incomparable(stranded, id, reason) =>
      s"note ${id.value}, which held '${stranded.path.render}' in ${stranded.noteId.value}, " +
        s"could not be compared at all: $reason"

object MoveFinding:

  extension (c: MoveFinding.Corroborated)

    /** See [[Agreement]] — computed from the two keys, never stored beside them.
      *
      * THE TWO BOOLEANS ARE THE WHOLE OF IT, and the match over them is exhaustive because there
      * are two of them.
      */
    def agreement: Agreement =
      val depth = MoveEvidence.nameDepthOf(c.noteType)
      val was = MoveEvidence.segmentsOf(c.stranded.path)
      val now = MoveEvidence.segmentsOf(c.candidate.path)
      val nameMoved  = MoveEvidence.nameSegments(was, depth) != MoveEvidence.nameSegments(now, depth)
      val placeMoved = was.dropRight(depth) != now.dropRight(depth)
      (nameMoved, placeMoved) match
        case (false, false) => Agreement.Total
        case (false, true)  => Agreement.NameAndSubstance
        case (true, false)  => Agreement.PlaceAndSubstance
        case (true, true)   => Agreement.SubstanceAlone

    /** THE ONE PLACE IN THIS CODEBASE THAT MINTS A REASSIGNMENT.
      *
      * ═══ WHY IT IS AN EXTENSION ON THIS CASE AND NOT A FUNCTION TAKING A `MoveFinding` ═══
      *
      * Because "only a corroborated finding may reassign" has to be UNREACHABLE-BY-CONSTRUCTION
      * rather than merely unwritten, and the receiver type is what makes it so. An enum's cases
      * are subclasses in Scala 3, so this method exists only on [[MoveFinding.Corroborated]]:
      * there is no widening from [[MoveFinding.Ambiguous]] to it, and a caller holding a
      * `MoveFinding` reaches it only by matching the sum — under `-Wconf:msg=exhaustive:e`, which
      * makes a match that forgets a case a compile error rather than a runtime surprise.
      *
      * The second half of the same guarantee is on the action: [[SyncAction.Reassign]] carries
      * the corroborated finding ITSELF as a field, so an executor holding one holds the evidence
      * that licensed it, and a value of any other finding shape cannot be put there.
      *
      * ═══ WHY A NEW ACTION AND NOT A COMPOSITION OF THE TWO THAT NEARLY FIT ═══
      *
      * [[SyncAction.CarryIdentity]] writes an identity into a note's `Identity` field and removes
      * its legacy tags, which is most of this. It is the wrong action anyway, and its own
      * docstring says why in two places. It carries THE NOTE'S OWN FIELDS BACK because an orphan
      * has no `CardSpec` to compute from — a reassigned note has one, and must be written from it
      * or its breadcrumb keeps naming the place it left. And `dispositionUnder` argues that a
      * `CarryIdentity` is undeferrable because it "adds a note's own identity to a field that is
      * empty and touches nothing else"; this touches every field, the content hash, the deck and
      * the tags. Reusing the case would make both sentences false about half their instances.
      *
      * [[SyncAction.Update]] is the other near fit and is refused for the reason `CarryIdentity`
      * gives for refusing it: an update is what happens to a note the planner FOUND at a key, and
      * this note is at a different key — `byKey` does not contain it. An `Update` that silently
      * changed which card a note is would make the one action that must be legible the one that
      * hides the most.
      *
      * [[SyncAction.Unflag]] is NOT folded in, and that is a composition this deliberately keeps.
      * A stranded note may or may not already be parked; unsuspending and clearing `orphaned::`
      * is exactly what `Unflag` does, it needs the OLD key to name the tag it removes, and the
      * planner emits it beside this action when the note carries the flag. Duplicating it here
      * would be a second implementation of the unsuspend-then-untag ordering that
      * `plan/Executor.scala` argues for at length.
      *
      * ═══ WHAT IT MUST NOT DO ═══
      *
      * IT NEVER DELETES AND RECREATES. That is the whole point: the note keeps its id, its cards,
      * their intervals, their ease and their review log, and what changes is which key it claims.
      * Nothing in [[SyncAction.Reassign]] or its executor arm can express a deletion, and the
      * algebra has no delete to reach for — `plan/SyncAction.scala` records that the removed
      * `Relink` case exists as a warning about exactly this.
      *
      * THE HASH IS COMPUTED HERE RATHER THAN PASSED IN, so that a caller cannot hand this the
      * hash of a different spec. The key is checked against the finding's candidate for the same
      * reason, and it is a hard error rather than a silent correction: a spec that is not the one
      * the evidence named would write another card's content onto this note.
      */
    def reassignment(
        sourced: SourcedSpec,
        vaultTags: Vector[OwnedTag],
        legacyTags: Vector[OwnedTag],
        deck: Option[DeckPath],
    ): SyncAction.Reassign =
      if sourced.key != c.candidate then
        sys.error(
          s"a reassignment was built for '${sourced.key.path.render}' from evidence about " +
            s"'${c.candidate.path.render}' — the two must be the same card"
        )
      SyncAction.Reassign(
        corroboration = c,
        fields = sourced.spec.fields,
        newSha = Planner.contentHash(sourced.spec),
        vaultTags = vaultTags,
        legacyTags = legacyTags,
        deck = deck,
      )

/** Pair what the vault now produces against what the collection still holds, and say what the
  * evidence is.
  */
object MoveEvidence:

  /** WHAT ONE PAIRING COMES TO AFTER THE SURVEY'S FIRST PASS — a finding, or the one question a
    * pairing cannot answer about itself.
    *
    * ═══ WHY THERE ARE TWO PASSES AT ALL ═══
    *
    * The ruling of 2026-09-13 makes one card's verdict depend on what the run concluded about
    * OTHER cards: a subject counts as surviving when this same survey corroborated a card onto it.
    * A single pass cannot answer that — the witness may be a pairing the walk has not reached yet —
    * and threading a mutable accumulator through the walk would make the answer depend on the order
    * cards happen to be visited, which is precisely the property this file spent its determinism
    * section establishing.
    *
    * So pass one decides everything a pairing can decide ALONE and carries the rest of the way the
    * facts it computed while the segments were in hand; pass two reads the run's conclusions off
    * pass one and resolves what waited. Order-independent, terminating, and readable in the order
    * the ruling states.
    */
  private enum SurveyRead:

    /** Nothing about the rest of the survey can change this. */
    case Concluded(finding: MoveFinding)

    /** The card's subject changed, so the verdict turns on whether the subject it left goes on
      * existing — and two of the three witnesses of that are facts about the whole survey.
      *
      * `subjectNode` is WHERE THE SUBJECT STOOD: the node the card hung off, addressed from its
      * note's root. Every witness is asked about that one value, which is what the entailment of
      * 2026-09-13 settled — a subject is its chain, so a same-spelled subject standing at an
      * unrelated place testifies to nothing. _It used to travel beside a `subject` field holding the
      * bare name, because two of the witnesses matched on that; nothing reads a bare name any more,
      * so the field is gone rather than left as a second answer to one question._
      */
    case AwaitingSurvival(
        card: ObservedCard,
        spec: SourcedSpec,
        divergences: Vector[Divergence],
        subjectNode: Vector[String],
        clusterMoved: Boolean,
    )

  /** SURVEY BOTH SIDES AND REPORT ON EACH STRANDED NOTE.
    *
    * ═══ WHAT THE CALLER MUST PASS, AND WHY THAT IS THE INTERESTING PART ═══
    *
    * `stranded` is the notes the collection holds whose key the vault no longer accounts for, and
    * `unclaimed` is the specs the vault produces that no note holds. `Planner.plan` computes both
    * already — the first through [[VaultAccounting]], the second as every spec taking the
    * `Create` branch — so this needs no new observation and makes no call.
    *
    * CHOOSING `stranded` IS THE RETROACTIVE QUESTION, and it is the caller's to answer rather
    * than this function's. Passing only notes not yet flagged confines the mechanism to moves
    * made from now on; passing the already-parked ones as well makes it reach back over orphans a
    * collection has been accumulating. Both are one line at the call site and neither is a
    * default here, because a default would BE the answer.
    *
    * ═══ THE RULE, AND WHY IT NEEDS NO THRESHOLD ═══
    *
    * A note and a spec may be paired when their two [[CardPath]]s are OF THE SAME KIND, they are
    * on the SAME NOTE TYPE, and every [[FieldRole.Substance]] and [[FieldRole.Setting]] field is
    * byte-identical. The pairing is then [[MoveFinding.Corroborated]] only when it is MUTUALLY
    * UNIQUE — one candidate from each side — and every [[FieldRole.Anchor]] field that differs is
    * ACCOUNTED FOR by the key having moved the segment that field renders.
    *
    * THE KIND CHECK IS FIRST AND IS NOT MERELY ONE MORE CONDITION. `CardPath` is a sum because a
    * note is a tree of nodes and a heading is one kind of node among four — a heading chain, a
    * frontmatter property, the note itself, one `^blockid`-anchored paragraph. Their segments do
    * not mean the same thing, so "the last segment agreed" across two kinds is not weak evidence,
    * it is a comparison of two different things that happen to be strings. Asking it first also
    * means the projection below is only ever applied to a path shape it was declared for.
    *
    * NO PROPORTION IS COMPUTED AND NONE SHOULD BE. There is no distance, no score and no number
    * anybody has to justify; `docs/reference/CARD-MODEL.md` §Deletion rejected the proportional
    * orphan guard on exactly that ground — "the proportion is a number nobody can justify" — and
    * `docs/findings/EVOLVABILITY.md` §4A makes the reframe this rests on: exact agreement on a
    * byte-identical field is evidence of the same class as the content hash, not a similarity
    * score.
    *
    * ═══ WHY THE CANDIDATE SEARCH IS NOT CONFINED TO ONE NOTE ID ═══
    *
    * `docs/findings/EVOLVABILITY.md` §4A proposes confining it, on the grounds that a card's key
    * begins with its file's frontmatter `id`. Two reasons not to. A heading MOVED FROM ONE NOTE
    * TO ANOTHER crosses that boundary, and it is one of the two cases Marc asked for — confining
    * the search would make it undetectable by construction. And confinement WEAKENS the claim
    * rather than strengthening it: uniqueness across the whole vault has excluded strictly more
    * candidates than uniqueness within one note, so the wide search is both the more sensitive
    * and the more conservative one. It costs a comparison over data already in memory.
    *
    * ═══ THE BLANK-AGREES-WITH-BLANK HAZARD, AND WHY THERE IS NO GUARD FOR IT HERE ═══
    *
    * Its structural half is refused at [[FieldRole.rolesFor]] — a note type with no Substance
    * field would make every card agree with every other. Its value-level half cannot arise,
    * because nothing the vault produces has a blank substance; the argument is written out at
    * that same place. An Anki note whose fields were all blanked by an interrupted
    * `changeNoteType` therefore comes back [[MoveFinding.Unexplained]] through ordinary equality.
    *
    * ═══ THE ORDER IS STABLE ACROSS RUNS ═══
    *
    * Findings come out sorted by the stranded key and then by note id, and candidates within a
    * finding by key, so two runs over an unchanged vault and an unchanged collection print the
    * same report — and, since 2026-09-05, APPLY THE SAME WRITES. Nothing here reads a `Map`'s
    * iteration order, which is a hash-table detail.
    *
    * ═══ THE CENSUS IS THE AFTER VAULT'S NODE TREE, FROM THE SAME SCAN AS `unclaimed` ═══
    *
    * It answers a question the other two inputs cannot: whether the subject a card left is still a
    * node of that card's own note. `unclaimed` is what the vault produces and `stranded` is what the
    * collection holds, and a concept heading that kept only prose is in neither. A census taken
    * from a DIFFERENT scan would answer about a vault that is not the one being planned; nothing
    * here can check that, so both arrive from one [[obsidiananki.extract.VaultIndex]].
    *
    * ═══ AND THE SURVEY'S OWN CONCLUSIONS ARE THE OTHER WITNESS OF SURVIVAL ═══
    *
    * Ruled 2026-09-13, which is why this runs in two passes rather than one: a subject also counts
    * as going on existing when THIS SURVEY corroborated a card onto it, wherever in the vault that
    * card now sits. [[SurveyRead]] says why one pass could not express it.
    *
    * ═══ AND `declared` IS THE THIRD WITNESS, WHICH IS THE ONE THAT OUTLIVES THE RUN ═══
    *
    * The other two expire at the sync boundary; [[SubjectSurvival]] walks through the two-run shape
    * that showed it. This one is the concepts the LIVE COLLECTION declares — the notes this run is
    * not orphaning, read through [[LiveDeclarations]] — and it is the caller's to compute for the
    * same reason `stranded` is: which notes count as live is a fact about the plan being built, not
    * about this comparison.
    *
    * THE TWO POPULATIONS MUST BE DISJOINT AND THAT IS CHECKED, not assumed. A note that is both
    * stranded and live would witness the survival of the very subject it is being orphaned from —
    * and the consequence is not local: every innocent concept rename would stop following, because
    * a renamed concept's OTHER descriptors are stranded by the same rename and would each declare
    * it still alive. Silent, total, and in the direction that loses history by leaving it behind.
    * `plan/Planner.scala` splits on `VaultAccounting.accountsFor`, so the two cannot overlap there;
    * this refuses loudly rather than resting on that sentence staying true.
    */
  def survey(
      stranded: Vector[ObservedCard],
      unclaimed: Vector[SourcedSpec],
      census: NodeCensus,
      declared: LiveDeclarations,
  ): Vector[MoveFinding] =
    val bothSides = stranded.map(_.note.id).toSet.intersect(declared.noteIds)
    if bothSides.nonEmpty then
      sys.error(
        "the move survey was given " +
          bothSides.toVector.map(_.value).sorted.mkString(", ") +
          " as BOTH a stranded note and a live witness — a note cannot declare that the subject " +
          "it is being orphaned from goes on existing; see MoveEvidence.survey"
      )

    // SORTED ONCE, AT THE TOP, so every list below inherits the order rather than each deciding
    // its own. The note id breaks a tie between two notes claiming one key — a state
    // `PlanError.DuplicateIdentityInAnki` refuses upstream, so it should not arrive, and a sort
    // that quietly depended on it not arriving would be one more thing to be wrong about.
    val orderedStranded =
      stranded.sortBy(c => (c.key.noteId.value, c.key.path.render, c.note.id.value))
    val orderedUnclaimed = unclaimed.sortBy(s => (s.key.noteId.value, s.key.path.render))

    // EITHER THE CANDIDATES, OR WHY THIS NOTE COULD NOT BE COMPARED AT ALL. Computed for every
    // stranded note before any of them is judged, because the mutual-uniqueness rule below is a
    // question about the whole survey and cannot be answered one note at a time.
    val examined: Vector[(ObservedCard, Either[String, Vector[(SourcedSpec, Vector[Divergence])]])] =
      orderedStranded.map { card =>
        card -> rolesOn(card).map { roles =>
          orderedUnclaimed.flatMap(spec => compare(roles, card, spec).map(spec -> _))
        }
      }

    // WHICH STRANDED NOTES CLAIM EACH CANDIDATE — the other half of the uniqueness rule. Keyed by
    // note id as well as by card key, so that excluding "this note" below excludes exactly one
    // note rather than everything sharing its key.
    val claimants: Map[CardKey, Vector[(AnkiNoteId, CardKey)]] =
      examined.flatMap {
        case (card, Right(candidates)) => candidates.map(_._1.key -> (card.note.id, card.key))
        case (_, Left(_))              => Vector.empty
      }.groupMap(_._1)(_._2)

    // ── PASS ONE: EVERYTHING ONE PAIRING CAN DECIDE ON ITS OWN ────────────────────────────
    //
    // Every arm below reaches a finding except the last, which reaches a question this pass
    // cannot answer — see [[SurveyRead]] for why the answer has to wait, and `underTheSurvivalCheck`
    // for what answers it.
    val read: Vector[SurveyRead] = examined.map {
      case (card, Left(reason)) =>
        SurveyRead.Concluded(MoveFinding.Incomparable(card.key, card.note.id, reason))

      case (card, Right(candidates)) =>
        candidates match
          case Vector() => SurveyRead.Concluded(MoveFinding.Unexplained(card.key, card.note.id))

          case Vector((spec, divergences)) =>
            // MUTUAL UNIQUENESS, WHICH IS THE HALF THAT IS EASY TO FORGET. One candidate from
            // this note's side is not enough: if another stranded note also agrees with the same
            // key, then two notes would both have become the same card, which is not a thing that
            // happened. `spike/RenameEvidence.scala` reached the identical rule for a renamed
            // table column and its `Contested` case is the precedent for this one.
            claimants(spec.key).filterNot(_._1 == card.note.id).map(_._2) match
              case Vector() =>
                // AND THEN THE KEY HAS TO EXPLAIN THE NAME. See [[MoveFinding.Unaccounted]]: a
                // card whose own name changed for a reason the key does not show did not simply
                // move, and this design applies only what a move explains.
                NonEmptyVector.fromVector(
                  unaccountedFor(card.key.path, spec.key.path, divergences)
                ) match
                  // AND FINALLY: WHICH OF THE CARD'S NAME SEGMENTS MOVED, WHICH IS NOT THE SAME
                  // QUESTION. Everything above establishes that this note and this spec say the
                  // same thing and that the key accounts for the difference in their faces. What
                  // the gate decides is whether the change the key made is a rewording or a change
                  // of SUBJECT — and a changed subject is a different card, not a moved one.
                  case None => underTheSubjectGate(card, spec, divergences)
                  case Some(unexplained) =>
                    SurveyRead.Concluded(
                      MoveFinding.Unaccounted(
                        card.key,
                        card.note.id,
                        spec.key,
                        spec.source,
                        unexplained,
                      )
                    )

              case others =>
                // Non-empty by the arm above, and already in `orderedStranded`'s order because it
                // was built by walking that vector.
                SurveyRead.Concluded(
                  MoveFinding.Contested(
                    card.key,
                    card.note.id,
                    spec.key,
                    NonEmptyVector.fromVectorUnsafe(others),
                  )
                )

          // TWO OR MORE, by the two arms above. Named and not ranked: the evidence distinguishes
          // nothing between them, and picking the first would be a guess wearing an answer's
          // clothes.
          case several =>
            SurveyRead.Concluded(
              MoveFinding.Ambiguous(
                card.key,
                card.note.id,
                NonEmptyVector.fromVectorUnsafe(several.map(_._1.key)),
              )
            )
    }

    // ── PASS TWO: THE RUN'S OWN CONCLUSIONS, THEN THE PAIRINGS THAT WAITED ON THEM ────────
    //
    // The witnesses are read off pass one's findings and nothing else, which is what makes this
    // terminate and makes it order-independent: no pairing resolved HERE can become a witness for
    // another one resolved here, so there is no second round to run and no pair of cards that could
    // each be the other's evidence. That restriction is not a convenience either — see
    // [[conceptsCorroboratedOnto]] for why a relabel-follow is evidence about a NEW name rather
    // than about a subject that survived.
    val corroboratedConcepts = conceptsCorroboratedOnto(read)

    read.map {
      case SurveyRead.Concluded(finding) => finding
      case pairing: SurveyRead.AwaitingSurvival =>
        underTheSurvivalCheck(pairing, census, corroboratedConcepts, declared)
    }

  /** THE CONCEPTS THIS SURVEY ITSELF PUT A CARD UNDER — the second witness of survival, per the
    * ruling of 2026-09-13.
    *
    * ═══ WHICH PAIRINGS COUNT, AND WHY IT IS NOT ALL OF THEM ═══
    *
    * A CORROBORATION AND NOT A CANDIDATE. The witness is what the survey CONCLUDED, not what it
    * compared: a contested or ambiguous claim under `# Kafka` is a claim the run will not act on,
    * and a run that has established nothing about Kafka may not spend it as a survival.
    *
    * A CORROBORATION FROM PASS ONE, so a pairing whose own subject moved is never a witness. That
    * is the stratification the two passes buy, and the reason is semantic rather than mechanical:
    * a pairing that follows a SUBJECT CHANGE says "what used to be called Kafka is now called
    * RabbitMQ" — evidence that RabbitMQ is a new spelling, not that RabbitMQ was there before. Only
    * a card the run puts back under an UNCHANGED subject witnesses that the subject goes on
    * existing. It also keeps the rule well-defined: two such cards could otherwise each be the
    * other's witness, with no determinate answer.
    *
    * ═══ KEYED BY THE PAIRING'S *OLD* CHAIN, WHICH IS THE BRIDGE ═══
    *
    * _Corrected 2026-09-13._ This used to key by the CANDIDATE's subject as a bare name — one
    * segment, no chain — and argued that matching node addresses "would miss both shapes that made
    * this witness necessary". That was half right and the half it got wrong is this whole function's
    * subtlety: matching the candidate's NEW address would indeed miss them, since a concept that
    * relocated or was re-nested is not where it was. The pairing's OLD key is not the same thing. It
    * is exactly where the subject STOOD, so it bridges the old chain to wherever the run has just put
    * the card — which is what the ruling means by "its own pairing moved it from P".
    *
    * WHAT THE BARE NAME COST, and it is why the correction was forced: `Kafka.md`'s `## Performance`
    * and `NATS.md`'s `## Performance` are two concepts sharing a spelling, so a name-keyed witness had
    * one testifying about the other. R2 already settles that they are different concepts — the parent
    * is constitutive, so the chain identifies it — which is why the sheet records this as entailed
    * rather than newly ruled.
    *
    * ═══ THE KIND TEST IS STILL THE NAME WINDOW, AND THAT SEPARATION IS LOAD-BEARING ═══
    *
    * Two different questions are asked of one pairing here. WHETHER this kind of card declares a
    * subject at all is the name window's answer — empty above the card's own name means no subject,
    * which is every note type but the concept-descriptor one, so filing under a plain heading
    * witnesses nothing. WHERE that subject stood is the chain's answer. Keying by the chain while
    * testing the window keeps the per-kind reach exactly as the ruling on kinds left it: a chain is
    * non-empty for a plain heading's parent too, so dropping the window test would silently promote
    * every filing heading to a witness.
    *
    * ONE WITNESS PER CHAIN, THE FIRST IN THE SURVEY'S ORDER, because the report names it and two
    * runs over the same vault must name the same one.
    */
  private def conceptsCorroboratedOnto(read: Vector[SurveyRead]): Map[Vector[String], CardKey] =
    read
      .collect { case SurveyRead.Concluded(c: MoveFinding.Corroborated) =>
        val was = segmentsOf(c.stranded.path)
        (nameSegments(was, nameDepthOf(c.noteType)).dropRight(1), was.dropRight(1), c.candidate)
      }
      .filter((declaresASubject, _, _) => declaresASubject.nonEmpty)
      .map((_, stoodAt, card) => stoodAt -> card)
      .groupMap(_._1)(_._2)
      .view
      .mapValues(_.head)
      .toMap

  /** IS THE CHANGE THE KEY RECORDS A REWORDING, OR A CHANGE OF SUBJECT? The last question between a
    * pairing and a reassignment.
    *
    * ═══ WHAT THIS ASKS THAT [[unaccountedFor]] DOES NOT ═══
    *
    * That one asks whether the KEY accounts for the card's face. This asks what the key accounting
    * for it MEANS. A segment inside the name window moved, so the two faces differ legitimately —
    * and for a card whose window is ONE segment long that is the end of the matter, because the
    * only segment there is the card's own name, and renaming a card in place is a rewording
    * (standing ruling R1). The concept-descriptor card is the one whose window is TWO segments, and
    * the upper of them is the card's SUBJECT: its parent concept is one of the three terms the card
    * asserts (standing ruling R2). A card that changed THAT did not move — it became a different
    * card — unless what changed was the subject's own NAME, which is a rewording one level up.
    *
    * ═══ THE FACT THAT SEPARATES THOSE TWO, AND WHY IT COMES FROM OUTSIDE ═══
    *
    * `# Kafka` relabelled `# RabbitMQ` with its descriptors following, and `## Definition` carried
    * from under `# Kafka` to under `# NATS`, PRODUCE THE SAME TWO KEYS. What tells them apart is
    * whether `# Kafka` goes on existing — a fact about the note's node tree and about the rest of
    * this survey, and about THIS pairing not at all. See [[NodeCensus]] for why neither of the
    * survey's other two inputs can hold the first half of it, and [[SubjectSurvival]] for why the
    * first half alone was not enough.
    *
    * So this function answers only the half it can: THE SUBJECT DID NOT MOVE is
    * [[MoveFinding.Corroborated]] outright, exactly as before the gate existed (Decisions 1 and 3
    * of 2026-09-12: a card's own name, or a descriptor's label, changing over an untouched subject
    * is a rewording, and history follows). A subject that DID move waits, carrying the facts read
    * off the two paths while they are in hand — [[underTheSurvivalCheck]] holds the routes from
    * there.
    *
    * ═══ WHY IT RUNS LAST, AFTER MUTUAL UNIQUENESS ═══
    *
    * Because its two unactionable outcomes must still OCCUPY the claimant bookkeeping. A gate that
    * decided first and then excused a re-parent from the uniqueness rule would leave the rival
    * claimant corroborated ALONE — a pairing asserted as unique precisely because its competitor had
    * been filed under another name.
    */
  private def underTheSubjectGate(
      card: ObservedCard,
      spec: SourcedSpec,
      divergences: Vector[Divergence],
  ): SurveyRead =
    val depth = nameDepthOf(card.note.noteType)
    val was   = segmentsOf(card.key.path)
    val now   = segmentsOf(spec.key.path)

    // THE NAME WINDOW MINUS ITS LAST SEGMENT IS THE SUBJECT, and asking it this way makes the gate
    // per-kind WITHOUT EVER ASKING THE KIND. Only the concept-descriptor note type declares a
    // second name field, so for every other type that window holds one segment and dropping it
    // leaves nothing — empty on both sides, equal, and the gate cannot fire. A note-type check
    // would say the same thing in a place where a fifth note type could contradict it; the two
    // guards in `plan/MoveEvidence.test.scala` pin the reach rather than the spelling.
    val subject      = nameSegments(was, depth).dropRight(1)
    val subjectMoved = subject != nameSegments(now, depth).dropRight(1)

    if !subjectMoved then
      SurveyRead.Concluded(underTheVoucherGate(card, spec, divergences))
    else
      SurveyRead.AwaitingSurvival(
        card,
        spec,
        divergences,
        // THE NODE THE CARD HUNG OFF — its WHOLE path minus its own name, because that is how a node
        // is addressed: from the note's root. A suffix of it would ask the census about a path that
        // is not in it, and get "gone" for a subject standing in plain sight. Since the entailment of
        // 2026-09-13 this is what ALL THREE witnesses are asked about, the two vault-wide ones
        // included: `subject` above decides WHETHER the subject changed, and this decides WHERE it
        // stood, which is the only thing a witness may be matched on.
        subjectNode = was.dropRight(1),
        // WHERE THE CARD SITS, WHICH IS A PATH AND A NOTE ID BOTH. A card key is both, so a card
        // that crossed into another note is a card whose cluster did not stay — the same answer the
        // ⚠️-flagged interpretation this gate used to carry gave, and confirmed by the entailment of
        // 2026-09-13 in the only place it still decides anything.
        //
        // WHAT CHANGED THAT DAY IS WHO ASKS THIS, NOT WHAT IT MEANS. It used to decide the relabel
        // for every kind; it now decides it only for a card whose author declared that its
        // description identifies nothing — see [[declaresItsConcept]] and [[underTheSurvivalCheck]].
        clusterMoved =
          was.dropRight(depth) != now.dropRight(depth) || card.key.noteId != spec.key.noteId,
      )

  /** MAY AN ORPHANED NOTE BE EDITED ONTO THIS SECTION AT ALL? The last question between an agreed
    * substance and a reassignment, and the one that is not about the cards but about what ENTITLES
    * this run to say they are the same card.
    *
    * ═══ THE THREE VOUCHERS, ANY ONE OF WHICH SUFFICES ═══
    *
    * Resolved 2026-09-13 (`docs/design/IDENTITY-DECISION-SHEET.md`, "no voucher, no edit") as
    * entailed by the standing rulings. Each conjunct below is one voucher being absent:
    *
    *   - THE DECLARATION. [[SubstanceDeclaration.Identifying]] says the author expects to recall this
    *     card from its content, so a byte-identical substance IS same-card evidence — and
    *     [[SubstanceDeclaration.Unstated]] refuses nothing, because no ruling has: a cloze author was
    *     never offered a direction to declare.
    *   - THE LOCATION. The section reappeared in THE SAME NOTE, so where it lives still identifies it
    *     whatever its content declares. That is the shape of Decision 5's fixture: a section deleted
    *     and recreated later in the note it was deleted from.
    *   - THE SINGLE EDIT. A note THIS RUN stranded vanished and reappeared in one edit, which is what
    *     a verbatim cross-note move looks like (deck S05). An `orphaned::` tag says the opposite: an
    *     earlier run parked this note, so the disappearance and the appearance are separate events
    *     and their agreement is a coincidence until something else says otherwise.
    *
    * ═══ THE LOCATION IS READ AT NOTE GRANULARITY, AND THAT IS THE RULING'S OWN WORDING ═══
    *
    * The sheet's fourth row is "`/1way` cross-sync AND cross-note", and its rows for the same-sync
    * case are drawn on the same axis — so the note is what this reads. A SHARPER reading is available
    * and is deliberately not taken: a section parked in one sync and re-parented under a different
    * chain of the SAME note in a later one has a location that also changed, and it goes on being
    * reattached today. `plan/MoveEvidence.test.scala` has carried that exact case since the
    * retroactive half of Decision 5 was ratified ("a note ALREADY parked as an orphan is unflagged,
    * unsuspended and reassigned"), and nothing on the sheet refuses it. It is the adjacent question,
    * not this rule's to answer.
    *
    * ═══ WHY THE ORPHAN FLAG IS THE RIGHT READING OF "IN ONE SYNC" ═══
    *
    * It is the only durable record of when the vault stopped accounting for a key, and it is one the
    * tool wrote itself. No clock, no ledger and no git history is consulted — the same discipline the
    * survival witnesses keep. `plan/Planner.scala` surveys the already-parked population
    * deliberately, which is what makes the distinction available here at all.
    *
    * ═══ WHY IT IS ASKED HERE AND NOT OF THE RELABEL ROUTE ═══
    *
    * Because that route already demands MORE than this rule does: a relabel follows only when the
    * declaration identifies or the place agreed ([[underTheSurvivalCheck]]), which is two of these
    * three vouchers with the single-edit signature not admitted at all. So a corroboration minted
    * there cannot be one this gate would refuse, and asking twice would only invite the two answers to
    * drift. A card whose SUBJECT changed is also better described by that route's own words than by
    * this one's.
    */
  private def underTheVoucherGate(
      card: ObservedCard,
      spec: SourcedSpec,
      divergences: Vector[Divergence],
  ): MoveFinding =
    val contentVouchesForNothing = declarationOn(spec) match
      case SubstanceDeclaration.NonIdentifying                              => true
      case SubstanceDeclaration.Identifying | SubstanceDeclaration.Unstated => false

    val crossedNotes = card.key.noteId != spec.key.noteId

    if contentVouchesForNothing && crossedNotes && card.isFlaggedOrphan then
      MoveFinding.NoVoucher(card.key, card.note.id, spec.key, spec.source, divergences)
    else
      MoveFinding.Corroborated(
        card.key,
        card.note.id,
        spec.key,
        spec.source,
        card.note.noteType,
        divergences,
      )

  /** WHAT THE AUTHOR DECLARED ABOUT THE CARD THE VAULT NOW PRODUCES — [[SubstanceDeclaration]] read
    * off one side of a pairing.
    *
    * ═══ WHY THE VAULT'S SIDE, AND WHY IT CANNOT MATTER WHICH ═══
    *
    * A declaration is the author's, and the vault is where the author writes; reading the spec makes
    * the sentence "what the author declares NOW" true as well as correct. It also cannot change a
    * verdict. The declaration lives in a note type and in a [[FieldRole.Setting]] field, [[compare]]
    * admits no pairing whose note types differ, and the comparison floor admits none whose Setting
    * fields differ — so by the time any pairing reaches a gate, both sides say the same thing.
    */
  private def declarationOn(spec: SourcedSpec): SubstanceDeclaration =
    SubstanceDeclaration.of(spec.spec.noteTypeName, spec.spec.fields.toMap)

  /** Whether the author declared that this card's substance identifies what the card is about — the
    * fact the relabel gate drops its place requirement for, entailed 2026-09-13.
    *
    * MATCHED RATHER THAN COMPARED, so that [[SubstanceDeclaration]]'s third state has to be answered
    * for rather than falling through a `==`. Silence is NOT a declaration of identifying-ness: it
    * cannot license dropping the place requirement, and the only kinds it applies to are the ones the
    * subject gate never fires for anyway, which is why the two spellings agree today.
    */
  private def declaresItsConcept(spec: SourcedSpec): Boolean =
    declarationOn(spec) match
      case SubstanceDeclaration.Identifying                                    => true
      case SubstanceDeclaration.NonIdentifying | SubstanceDeclaration.Unstated => false

  /** DOES THE SUBJECT THE CARD LEFT GO ON EXISTING? The question pass one could not answer, because
    * one of its three witnesses is a fact about the whole survey.
    *
    * ═══ THE THREE WITNESSES, AND WHAT DECIDES THE ORDER THEY ARE ASKED IN ═══
    *
    * They cannot disagree about the ANSWER — each is a positive declaration, and none of them can
    * say a subject is gone — so the order decides only WHICH ONE THE REPORT NAMES. Two things fix
    * it, and neither is taste.
    *
    * FIRST: A POSITIVE DECLARATION OUTRANKS "I COULD NOT LOOK". The census has three answers and one
    * of them is an admission; from that nothing may be concluded, which is why an unsurveyable
    * census turns a relabel into a question. But a survival ESTABLISHED by this run's own pairings,
    * or by a card the collection is holding, needs no census at all — and a census that could not be
    * taken cannot unestablish either of them. So the census's ADMISSION is asked last, after both
    * witnesses that do not depend on it.
    *
    * SECOND: NAME THE MOST DIRECT FACT AVAILABLE. Where the concept is still a node of the stranded
    * card's OWN NOTE, that is a sentence a reader checks by opening one file, so it is preferred over
    * "some other card elsewhere declares it" — which is why the census's POSITIVE answer is asked
    * before the collection's declarations even though the admission is asked after. Splitting the
    * census's two answers apart is the whole reason this reads as a list of witnesses rather than as
    * a nested match.
    *
    * ═══ THE ROUTES, WHICH ARE THE RULINGS OF 2026-09-12 AND 2026-09-13 ═══
    *
    *   - THIS SURVEY CORROBORATED A CARD ONTO THE SUBJECT — [[MoveFinding.Reparented]]. Ruled
    *     2026-09-13: the run's own conclusions are evidence the subject survives, wherever in the
    *     vault it now sits (the reviewer's S24A and S24B).
    *   - THE SUBJECT IS STILL A NODE OF THIS NOTE — [[MoveFinding.Reparented]] again. Ruling R2,
    *     deck scenarios S24 and S24C: there are two cards here, not one that moved. This is the only
    *     witness that can see a concept which kept nothing but prose.
    *   - A LIVE CARD IN THE COLLECTION DECLARES THE SUBJECT — [[MoveFinding.Reparented]] again.
    *     Resolved 2026-09-13 as entailed by the standing rulings, and it is the only witness that
    *     survives the sync boundary: the other two are facts about THIS run, and the attack that
    *     forced this simply put the concept's departure and the descriptor's re-parenting in
    *     different runs. See [[LiveDeclarations]].
    *   - IT IS GONE AND THE CLUSTER STAYED PUT — [[MoveFinding.Corroborated]]. Decision 2, revising
    *     R3: "Least Element" became "Bottom", and the same cards keep their history.
    *   - IT IS GONE, SOMETHING ELSE MOVED TOO, AND THE DESCRIPTION IDENTIFIES THE CONCEPT —
    *     [[MoveFinding.Corroborated]] as well. Entailed 2026-09-13: place was never part of what a
    *     `/2way` or `/3way` declaration claims, so there is nothing for the path to weigh once the
    *     description is unchanged and the old subject stands nowhere. See [[declaresItsConcept]].
    *   - IT IS GONE, SOMETHING ELSE MOVED TOO, AND THE DESCRIPTION IDENTIFIES NOTHING —
    *     [[MoveFinding.RelabelUnvouched]]. Decision 2's other half, which keeps its full force for a
    *     `/1way`: "the path weighs both ways — a name change combined with a move grades weaker and
    *     becomes a question". For such a card the location is the only voucher there could be.
    *   - THE CENSUS COULD NOT SAY, AND NOTHING ELSE ANSWERED — [[MoveFinding.RelabelUnvouched]],
    *     carrying the reason. "It is gone" is the premise the follow rests on, and a run that could
    *     not look has not established it.
    *
    * SURVIVAL IS ASKED BEFORE THE CLUSTER, AND THE ORDER IS LOAD-BEARING. A descriptor re-parented
    * under a surviving subject is a different card whether or not it also moved; telling somebody
    * "possibly renamed, but it moved as well" about one would invite them to pair what R2 has
    * already refused.
    */
  private def underTheSurvivalCheck(
      pairing: SurveyRead.AwaitingSurvival,
      census: NodeCensus,
      corroboratedConcepts: Map[Vector[String], CardKey],
      declared: LiveDeclarations,
  ): MoveFinding =
    // THE WAITING PAIRING TRAVELS AS ONE VALUE rather than as its fields, because a positional call
    // site could swap two of them and still compile.
    val card        = pairing.card
    val spec        = pairing.spec
    val divergences = pairing.divergences

    // WHERE THE SUBJECT STOOD, WHICH IS WHAT EVERY WITNESS IS ASKED ABOUT. All three compare chains
    // since the entailment of 2026-09-13: a subject is its chain, so a same-spelled subject standing
    // somewhere unrelated is silence. The census asks it of this note's own tree, and the other two
    // reach the whole vault — see this function's docstring for why the note is not part of the
    // comparison for those.
    val stoodAt = pairing.subjectNode

    def reparented(survival: SubjectSurvival) = MoveFinding.Reparented(
      card.key,
      card.note.id,
      spec.key,
      spec.source,
      survival,
      divergences,
    )

    def unvouched(cause: RelabelDoubt) = MoveFinding.RelabelUnvouched(
      card.key,
      card.note.id,
      spec.key,
      spec.source,
      cause,
      divergences,
    )

    // ASKED ONCE AND READ TWICE, because its POSITIVE answer and its ADMISSION sit at opposite ends
    // of the witness order — see this function's docstring for what puts them there.
    val counted = census.nodesOf(card.key.noteId)

    val stillInTheNote = counted match
      case NodeCensus.Answer.Surveyed(nodes) if nodes.contains(stoodAt) =>
        Some(SubjectSurvival.StillInTheNote(stoodAt))
      case NodeCensus.Answer.Surveyed(_) | NodeCensus.Answer.Unsurveyable(_) => None

    // THE THREE WITNESSES, IN ONE EXPRESSION AND IN THE ORDER THE DOCSTRING ARGUES FOR. Each is a
    // positive declaration, so the first one to answer decides — there is no case in which asking a
    // later one could contradict an earlier one, only one in which it would be named instead.
    val survived: Option[SubjectSurvival] =
      corroboratedConcepts
        .get(stoodAt)
        .map(SubjectSurvival.CorroboratedOnto(stoodAt, _))
        .orElse(stillInTheNote)
        .orElse(declared.witnessFor(stoodAt).map(SubjectSurvival.StillInTheCollection(stoodAt, _)))

    survived match
      case Some(survival) => reparented(survival)

      // NOTHING DECLARED THE SUBJECT. What is left is the two ways that can mean something and the
      // one way it can mean nothing, and the census's own answer is what separates them.
      case None =>
        counted match
          case NodeCensus.Answer.Unsurveyable(reason) =>
            unvouched(RelabelDoubt.CensusUnavailable(reason))

          case NodeCensus.Answer.Surveyed(_) =>
            // THE PLACE QUESTION IS ASKED OF THE KIND THAT HAS NOTHING ELSE TO ANSWER WITH. For a
            // card whose author declared that the description identifies its concept, place was
            // never part of that claim, so a moved cluster is not a doubt — the entailment of
            // 2026-09-13. For a card that declares the opposite, the location is the only thing that
            // could vouch for the pairing, and it changed.
            if pairing.clusterMoved && !declaresItsConcept(spec) then
              unvouched(RelabelDoubt.ClusterMoved)
            else
              MoveFinding.Corroborated(
                card.key,
                card.note.id,
                spec.key,
                spec.source,
                card.note.noteType,
                divergences,
              )

  /** THE CANONICALISED SEGMENTS OF A PATH, OUTERMOST FIRST, so the last one is always the node the
    * card hangs off.
    *
    * ═══ WHY IT IS DEFINED HERE AND NOT ON `CardPath` ═══
    *
    * Because it is this survey's reading of a path rather than a property of one. Promoting it to
    * `model/` would invite a consumer to treat a segment as a form of identity, which is exactly
    * what it is not: identity is the WHOLE key. `model/CardKey.scala` says what a path IS; this
    * flattens one so that the last `n` of it can be compared against the last `n` of another.
    *
    * IT IS ONLY EVER APPLIED TO TWO PATHS OF THE SAME KIND, which [[compare]] establishes first.
    * A property's name and a heading's last segment are both one string here and mean different
    * things; the kind check is what stops that mattering.
    */
  private[plan] def segmentsOf(path: CardPath): Vector[String] = path match
    case CardPath.Headings(headings) => headings.segments.toVector.map(_.value)
    case CardPath.Property(name)     => Vector(name.value)
    case CardPath.Block(anchor)      => Vector(anchor.value)
    // THE NOTE-ITSELF CARD HAS NO SEGMENTS AT ALL, which is not an omission — a card anchored at
    // the note has no name below the note. Two such cards therefore agree on their whole path by
    // construction, and what tells them apart is the frontmatter id, which is the other half of
    // the key and is never compared here.
    case CardPath.Note => Vector.empty

  /** THE LAST `depth` SEGMENTS, which are the ones a card of this note type shows as fields.
    *
    * `takeRight` RATHER THAN AN INDEXED READ, so that a path shorter than `depth` yields what it
    * has instead of throwing. That is the ancestorless concept-descriptor card — one segment, two
    * declared name fields — and it is a real shape rather than a defect. What it costs is decided
    * at [[unaccountedFor]], which refuses to call a divergence explained by a segment that is not
    * there.
    */
  private[plan] def nameSegments(segments: Vector[String], depth: Int): Vector[String] =
    segments.takeRight(depth)

  /** HOW MANY SEGMENTS AT THE END OF A PATH A CARD OF THIS NOTE TYPE SHOWS AS FIELDS — the width of
    * the window both [[underTheSubjectGate]] and [[MoveFinding.agreement]] read.
    *
    * A HARD ERROR ON AN UNDECLARED NOTE TYPE, AND IT IS UNREACHABLE RATHER THAN DEFENSIVE. [[survey]]
    * answers [[MoveFinding.Incomparable]] for such a note at [[rolesOn]], long before either caller
    * can be reached, so arriving here means this tool has contradicted itself and the only useful
    * thing to do is say so.
    *
    * SHARED BY THE TWO CALLERS so that the impossible state is described in ONE sentence. They ask
    * at different moments — the gate while deciding, the grade afterwards from the stored note type
    * — and two copies of this message would be two things to keep true about one defect.
    */
  private[plan] def nameDepthOf(noteType: String): Int =
    FieldRole
      .rolesFor(noteType)
      .map(FieldRole.nameDepth)
      .getOrElse(
        sys.error(
          s"a pairing was admitted on '$noteType', which this tool does not declare — " +
            "MoveEvidence.survey cannot admit such a note, so this is a defect in this tool"
        )
      )

  /** The roles for this note's fields, or WHY the comparison cannot be made.
    *
    * TWO STATES REACH THE `Left`, and both are real rather than defensive. A note on a note type
    * this tool does not declare is where every note synced before 2026-08-21 sits until a retype
    * moves it onto an `Obsidian *` type. A note whose field NAMES are not its declared note
    * type's is what a half-finished note-type repair leaves behind.
    *
    * COMPARING A SUBSET WOULD BE THE WRONG ANSWER TO THE SECOND, and it is worth saying why,
    * because a subset comparison is the obvious thing to reach for. A note missing `Context`
    * would have one fewer field to disagree on, so it would agree MORE readily than a complete
    * one — the evidence would be strongest exactly where the collection is most damaged. Saying
    * "I could not look" is the only honest answer available.
    */
  private def rolesOn(card: ObservedCard): Either[String, Map[String, FieldRole]] =
    FieldRole.rolesFor(card.note.noteType) match
      case None =>
        Left(
          s"'${card.note.noteType}' is not a note type this tool declares, so its fields carry " +
            "no roles and nothing about them can be compared"
        )
      case Some(roles) =>
        val present = card.note.fields.map(_._1).toSet
        if present == roles.keySet then Right(roles)
        else
          val complaints = Vector(
            Option.when((roles.keySet -- present).nonEmpty)(
              s"missing ${(roles.keySet -- present).toVector.sorted.mkString(", ")}"
            ),
            Option.when((present -- roles.keySet).nonEmpty)(
              s"unexpected ${(present -- roles.keySet).toVector.sorted.mkString(", ")}"
            ),
          ).flatten
          Left(
            s"the note's fields are not those of '${card.note.noteType}': ${complaints.mkString("; ")}"
          )

  /** `Some(divergences)` when this note and this spec may be paired at all, `None` when they may
    * not. The divergences are the [[FieldRole.Anchor]] and [[FieldRole.Bearing]] fields that
    * differ, IN THE NOTE TYPE'S DECLARED FIELD ORDER so that a report reads the same way twice.
    *
    * `Option` RATHER THAN A BOOLEAN BESIDE A LIST, because the two answers are not independent: a
    * divergence list is meaningful only for an admitted pairing, and returning both separately
    * would let a caller read the list of a pairing that was refused.
    *
    * THE PATH KIND IS CHECKED FIRST, THEN THE NOTE TYPE, AND NEITHER IS MERELY ONE MORE FIELD.
    * Two path kinds do not describe the same sort of node, and two note types do not share a
    * field vocabulary — so comparing across either is not weak evidence, it is no evidence, and
    * in the second case the values would be read out of a map by names that mean different
    * things.
    *
    * THE SPEC'S FIELDS ARE READ BY NAME AND BOTH LOOKUPS ARE ALLOWED TO FAIL LOUDLY, AND BY NAME.
    * That `CardSpec.fields` emits exactly the names `Marker.FieldOrder` declares is pinned by
    * `model/Marker.test.scala`, and that an observed note's fields are its note type's is
    * established by [[rolesOn]] before this is reached — so neither lookup can miss today. A bare
    * `map(field)` on either side would still be the wrong shape if one ever did: a raw
    * `NoSuchElementException` naming nothing is a message that costs an hour and teaches nothing,
    * which is the standard `PlanError.DuplicateKey` sets for both sides of a comparison.
    */
  private def compare(
      roles: Map[String, FieldRole],
      card: ObservedCard,
      spec: SourcedSpec,
  ): Option[Vector[Divergence]] =
    Option
      .when(
        sameKind(card.key.path, spec.key.path) &&
          card.note.noteType == spec.spec.noteTypeName
      ) {
        val inAnki  = card.note.fields.toMap
        val inVault = spec.spec.fields.toMap

        Marker.FieldOrder.byNoteType(card.note.noteType).map { field =>
          val was = inAnki.getOrElse(
            field,
            sys.error(
              s"Anki note ${card.note.id.value} holds no '$field', which " +
                s"'${card.note.noteType}' declares — see Marker.FieldOrder"
            ),
          )
          val now = inVault.getOrElse(
            field,
            sys.error(
              s"the spec for '${spec.key.path.render}' emits no '$field', which " +
                s"'${card.note.noteType}' declares — see Marker.FieldOrder"
            ),
          )
          (field, roles(field), was, now)
        }
      }
      .flatMap { compared =>
        // ── THE FLOOR. Substance is what the author wrote and Setting is what the marker asked
        // for, and a MOVE can change neither — so a pairing in which either differs is not weak
        // evidence of a move, it is evidence of something else entirely, and this says nothing
        // about it. See `FieldRole.Setting` for the false negative that costs, and why it is the
        // safe direction.
        val floorHolds = compared.forall {
          case (_, FieldRole.Substance | FieldRole.Setting, was, now) => was == now
          case _                                                     => true
        }

        Option.when(floorHolds)(
          compared.collect {
            // IDENTITY IS ABSENT FROM THIS LIST BY BEING ABSENT FROM THIS PATTERN, not by being
            // filtered earlier. The two identities differ by construction — a pairing exists only
            // because they do — so recording that as a divergence would put the question into the
            // answer.
            case (field, role @ (FieldRole.Anchor(_) | FieldRole.Bearing), was, now) if was != now =>
              Divergence(field, role, was, now)
          }
        )
      }

  /** Whether two paths describe the same KIND of node.
    *
    * MATCHED EXHAUSTIVELY OVER THE PAIR rather than compared by a `kind` projection, so that a
    * fifth kind of anchor cannot join by falling through a catch-all. `CardPath` gained its
    * fourth case after the first three shipped; the next one must be told what it may pair with.
    */
  private def sameKind(a: CardPath, b: CardPath): Boolean = (a, b) match
    case (CardPath.Headings(_), CardPath.Headings(_)) => true
    case (CardPath.Property(_), CardPath.Property(_)) => true
    case (CardPath.Note, CardPath.Note)               => true
    case (CardPath.Block(_), CardPath.Block(_))       => true
    case (CardPath.Headings(_) | CardPath.Property(_) | CardPath.Note | CardPath.Block(_), _) =>
      false

  /** THE NAME DIVERGENCES THE KEY DOES NOT EXPLAIN — empty when the pairing may be acted on.
    *
    * ═══ WHAT "EXPLAINED" MEANS, EXACTLY ═══
    *
    * A name field renders one segment of the key path, counted from the end
    * ([[FieldRole.Anchor]]). Its divergence is explained when BOTH paths HAVE a segment at that
    * position — the key then shows, in canonical form, what this field is a display of, and whether
    * that segment moved decides what KIND of change it was rather than whether there was one.
    *
    * WHAT IS LEFT OVER IS THE ONE SITUATION WHERE THERE IS NO SEGMENT TO SHOW, on one side or the
    * other. The concept-descriptor card whose marked heading has no ancestor takes its `Concept`
    * from THE FILE NAME, which is in no key at all; rename the file and the concept changes with
    * nothing in the key to account for it. Deck scenarios S27, S60 and S64 are that shape, and they
    * are reported and not applied.
    *
    * ═══ THE SEGMENTS AGREEING WHILE THE FIELD DIFFERS IS *NOT* LEFT OVER, SINCE 2026-09-12 ═══
    *
    * It was until then, and Decision 4 of `docs/design/IDENTITY-DECISION-SHEET.md` overruled it
    * outright. Bolding a heading does this — `model/CardKey.scala` strips markup out of a segment
    * precisely so that formatting cannot orphan a card — and so does re-casing one, or a heading
    * whose maths begins rendering. In Marc's words: "same front, same back... same card". The card
    * IS its front and its back, the WORDS of both are what the canonical segment preserves, and
    * MARKUP IS RENDERING RATHER THAN IDENTITY. So the pairing follows, the divergence still travels
    * in [[MoveFinding.Corroborated.divergences]] for the report to show, and the reassignment writes
    * the vault's fields — which is how the Anki face comes to show the new markup.
    *
    * ═══ WHY ONLY THE NAME FIELDS ARE ASKED ═══
    *
    * `Bearing` is `Context`, the breadcrumb, and its divergence IS the signal — a move is
    * precisely what changes it, so demanding an account of it would refuse every case this file
    * exists for. `Substance` and `Setting` never reach here: a pairing in which either differs
    * was refused by the floor. `Identity` is never recorded as a divergence at all.
    */
  private def unaccountedFor(
      was: CardPath,
      now: CardPath,
      divergences: Vector[Divergence],
  ): Vector[Divergence] =
    val oldSegments = segmentsOf(was)
    val newSegments = segmentsOf(now)

    def segmentAt(segments: Vector[String], fromEnd: Int): Option[String] =
      Option.when(fromEnd < segments.length)(segments(segments.length - 1 - fromEnd))

    divergences.filter { d =>
      d.role match
        case FieldRole.Anchor(fromEnd) =>
          (segmentAt(oldSegments, fromEnd), segmentAt(newSegments, fromEnd)) match
            // BOTH SEGMENTS PRESENT IS ENOUGH, and the two are deliberately not compared. A segment
            // that moved says the card's name changed; a segment that agreed says only the
            // RENDERING did, which Decision 4 ruled is not identity. Either way the key has shown
            // what this field displays, which is all that is being asked here. The match is kept
            // over the pair rather than collapsed to an `isEmpty` test because the question is
            // about presence on BOTH sides, and a reader should see both being asked.
            case (Some(_), Some(_)) => false
            case _                  => true
        case FieldRole.Bearing | FieldRole.Substance | FieldRole.Setting | FieldRole.Identity =>
          false
    }
