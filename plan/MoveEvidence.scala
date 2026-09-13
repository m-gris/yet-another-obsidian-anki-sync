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

/** HOW THIS RUN KNOWS THE SUBJECT A CARD LEFT GOES ON EXISTING — the two witnesses the ruling of
  * 2026-09-13 admits, and they are different claims rather than two strengths of one.
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
  */
enum SubjectSurvival:

  /** The node census found the subject's path still in the stranded card's own note. `path` is
    * addressed from the note's root, which is how a node is addressed.
    */
  case StillInTheNote(path: Vector[String])

  /** This survey corroborated `card` onto `concept` — so the run itself puts a card under that
    * subject, wherever in the vault it now sits. `concept` is the subject as a card's own fields
    * show it, never a node address: it is compared between two cards, and matching node paths
    * would miss exactly the two shapes that made this witness necessary.
    */
  case CorroboratedOnto(concept: Vector[String], card: CardKey)

  def describe: String = this match
    case StillInTheNote(path) => s"'${path.mkString(" / ")}' is still in the vault"
    case CorroboratedOnto(concept, card) =>
      s"'${concept.mkString(" / ")}' goes on existing: this same run pairs " +
        s"'${card.path.render}' in ${card.noteId.value} onto it"

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

/** WHAT THE EVIDENCE SHOWS about ONE stranded note. Eight outcomes, and each says a different
  * thing to whoever reads it.
  *
  * ONLY ONE OF THEM IS AN INSTRUCTION, AND IT IS THE FIRST. The other five say what agreed and
  * stop there; `spike/RenameEvidence.scala` settled that vocabulary for a renamed table column
  * and its reasoning transfers whole. What changed on 2026-09-05 is that the corroborated case
  * became actionable — see this file's header for Marc's ruling and the asymmetry behind it.
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
      * existing — and one of the two witnesses of that is a fact about the whole survey.
      *
      * `subjectNode` is the node the card hung off, addressed from its note's root, which is what
      * the census can be asked about. `subject` is the same subject as the card's own fields show
      * it, which is what another card's pairing can be compared against. They differ exactly in
      * the two shapes that made the second witness necessary.
      */
    case AwaitingSurvival(
        card: ObservedCard,
        spec: SourcedSpec,
        divergences: Vector[Divergence],
        subjectNode: Vector[String],
        subject: Vector[String],
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
    */
  def survey(
      stranded: Vector[ObservedCard],
      unclaimed: Vector[SourcedSpec],
      census: NodeCensus,
  ): Vector[MoveFinding] =
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
        underTheSurvivalCheck(pairing, census, corroboratedConcepts)
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
    * ═══ THE SUBJECT AS A CARD'S FIELDS SHOW IT, NEVER A NODE ADDRESS ═══
    *
    * `nameSegments` minus its last is the same reading of a path [[underTheSubjectGate]] takes, so
    * the two sides of the comparison are the same kind of thing. Matching node addresses instead
    * would miss both shapes that made this witness necessary: the concept that moved to another
    * note, and the concept re-nested under a new ancestor. AN EMPTY SUBJECT IS DROPPED — every note
    * type but the concept-descriptor one shows a one-segment window, so its cards have no subject
    * above their own name, and an empty witness would answer for all of them at once.
    *
    * ONE WITNESS PER CONCEPT, THE FIRST IN THE SURVEY'S ORDER, because the report names it and two
    * runs over the same vault must name the same one.
    */
  private def conceptsCorroboratedOnto(read: Vector[SurveyRead]): Map[Vector[String], CardKey] =
    read
      .collect { case SurveyRead.Concluded(c: MoveFinding.Corroborated) =>
        nameSegments(segmentsOf(c.candidate.path), nameDepthOf(c.noteType)).dropRight(1) ->
          c.candidate
      }
      .filter((subject, _) => subject.nonEmpty)
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
      SurveyRead.Concluded(
        MoveFinding.Corroborated(
          card.key,
          card.note.id,
          spec.key,
          spec.source,
          card.note.noteType,
          divergences,
        )
      )
    else
      SurveyRead.AwaitingSurvival(
        card,
        spec,
        divergences,
        // THE NODE THE CARD HUNG OFF — its WHOLE path minus its own name, because that is how a node
        // is addressed: from the note's root. A suffix of it would ask the census about a path that
        // is not in it, and get "gone" for a subject standing in plain sight.
        subjectNode = was.dropRight(1),
        subject = subject,
        // ⚠️ THE NOTE-ID HALF IS AN INTERPRETATION AWAITING MARC'S CONFIRMATION, flagged here
        // rather than buried — and it is NOT the one the ruling of 2026-09-13 settled, which was the
        // other interpretation this gate carried. Decision 2 follows a relabel when "the cluster
        // stayed in place (the path agreed)", and a card key is A NOTE ID AND A PATH — so a card that
        // crossed into another note is read here as a cluster that did not stay. No deck scenario
        // exercises the combination; `plan/MoveEvidence.test.scala` is where it is pinned.
        clusterMoved =
          was.dropRight(depth) != now.dropRight(depth) || card.key.noteId != spec.key.noteId,
      )

  /** DOES THE SUBJECT THE CARD LEFT GO ON EXISTING? The question pass one could not answer, because
    * one of its two witnesses is a fact about the whole survey.
    *
    * ═══ THE TWO WITNESSES, AND WHY THE RUN'S OWN IS ASKED FIRST ═══
    *
    * The order is not an optimisation. The census has THREE answers, and one of them is "I could not
    * look" — from which nothing may be concluded, which is why an unsurveyable census turns a
    * relabel into a question. But a survival this run ESTABLISHED needs no census at all: the run
    * itself put a card under that subject, and a census that could not be taken cannot unestablish
    * it. Asking the census first would spend "I could not look" on a run that already knew.
    *
    * ═══ THE ROUTES, WHICH ARE THE RULINGS OF 2026-09-12 AND 2026-09-13 ═══
    *
    *   - THIS SURVEY CORROBORATED A CARD ONTO THE SUBJECT — [[MoveFinding.Reparented]]. Ruled
    *     2026-09-13: the run's own conclusions are evidence the subject survives, wherever in the
    *     vault it now sits (the reviewer's S24A and S24B).
    *   - THE SUBJECT IS STILL A NODE OF THIS NOTE — [[MoveFinding.Reparented]] again. Ruling R2,
    *     deck scenarios S24 and S24C: there are two cards here, not one that moved. This is the only
    *     witness that can see a concept which kept nothing but prose.
    *   - IT IS GONE AND THE CLUSTER STAYED PUT — [[MoveFinding.Corroborated]]. Decision 2, revising
    *     R3: "Least Element" became "Bottom", and the same cards keep their history.
    *   - IT IS GONE AND SOMETHING ELSE MOVED TOO — [[MoveFinding.RelabelUnvouched]]. Decision 2
    *     again: "the path weighs both ways — a name change combined with a move grades weaker and
    *     becomes a question".
    *   - THE CENSUS COULD NOT SAY, AND THIS RUN ESTABLISHED NOTHING — [[MoveFinding.RelabelUnvouched]],
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
  ): MoveFinding =
    // THE WAITING PAIRING TRAVELS AS ONE VALUE rather than as its six fields, because two of those
    // fields are `Vector[String]` and mean different things — the node address and the subject —
    // and a positional call site could swap them and still compile.
    val card        = pairing.card
    val spec        = pairing.spec
    val divergences = pairing.divergences
    val subject     = pairing.subject

    def reparented(survival: SubjectSurvival) = MoveFinding.Reparented(
      card.key,
      card.note.id,
      spec.key,
      spec.source,
      survival,
      divergences,
    )

    corroboratedConcepts.get(subject) match
      case Some(witness) =>
        reparented(SubjectSurvival.CorroboratedOnto(subject, witness))

      case None =>
        census.nodesOf(card.key.noteId) match
          case NodeCensus.Answer.Unsurveyable(reason) =>
            MoveFinding.RelabelUnvouched(
              card.key,
              card.note.id,
              spec.key,
              spec.source,
              RelabelDoubt.CensusUnavailable(reason),
              divergences,
            )

          case NodeCensus.Answer.Surveyed(nodes) =>
            if nodes.contains(pairing.subjectNode) then
              reparented(SubjectSurvival.StillInTheNote(pairing.subjectNode))
            else if pairing.clusterMoved then
              MoveFinding.RelabelUnvouched(
                card.key,
                card.note.id,
                spec.key,
                spec.source,
                RelabelDoubt.ClusterMoved,
                divergences,
              )
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
