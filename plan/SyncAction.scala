package obsidiananki.plan

import cats.data.NonEmptyVector
import obsidiananki.anki.{AnkiNoteId, DeckPath, NewNote}
import obsidiananki.model.{CardKey, OwnedTag, TagCodec}

/** What the reconciler concluded, and what it may not conclude.
  *
  * The model separates WHAT DIFFERS from WHAT CALL TO MAKE. A deck move is a CHANGE, not a
  * kind of action, which is why "edited and moved" needs no special case.
  */

/** One dimension along which an existing note differs from what the markdown now says.
  *
  * Each change carries its own NEW VALUE, so an action is self-contained: an executor needs
  * no lookup table and no callback to carry it out, and a plan can be printed, reviewed or
  * stored without losing what it would actually do.
  */
enum Change:

  /** The author's own tags, as the vault now states them.
    *
    * THE WHOLE DESIRED SET, NOT A DIFFERENCE, because a difference computed here would have to
    * be recomputed against whatever the note holds by the time the write lands. The set is what
    * the vault says; the executor makes the note match it.
    *
    * IT NAMES ONLY TAGS UNDER THE VAULT NAMESPACE, and the executor touches nothing else. Anki
    * writes `leech` when a card lapses too often and `marked` when a card is marked, both onto
    * notes this tool generated; deleting those would destroy a record that can only be earned
    * back by failing reviews again. The namespace is what makes "make the note match" safe.
    *
    * SEPARATE FROM [[FieldsChanged]] RATHER THAN FOLDED INTO THE CONTENT HASH, and the reason is
    * honesty in both directions. The hash exists to answer "is there anything to write", and it
    * hashes CONTENT — putting tags in it would make a tag edit report itself as a field change
    * and rewrite every field to say so. Tags are compared directly instead, which costs nothing:
    * the note's current tags are already in hand when the comparison is made.
    */
  case TagsChanged(desired: Vector[OwnedTag])

  /** The rendered fields differ, together with the content hash of the new value.
    *
    * The hash travels with the fields because the two must be written together and IN THAT
    * ORDER — FIELDS FIRST, HASH LAST. An interruption between them then leaves new content
    * under a stale hash, which the next run sees as a difference and simply writes again.
    *
    * THE REVERSE IS THE TRAP: writing the hash first leaves OLD content under the NEW hash,
    * and [[Planner]] decides "nothing to do" by comparing exactly those two — so the note is
    * skipped by every later run, silently and permanently.
    *
    * _Corrected 2026-08-19._ This comment previously prescribed "hash first" and blamed the
    * permanent skip on the ordering that in fact prevents it. That inversion was live in
    * `Executor` and is fixed in `717d899`; the comment outlived the fix, in the file a reader
    * opens to learn what this case means. `ExecutorInterruptionTest` is what holds the code
    * right; nothing but review holds this sentence right.
    */
  case FieldsChanged(fields: Vector[(String, String)], newSha: String)

  case DeckChanged(from: Option[DeckPath], to: DeckPath)

  /** WHICH KIND OF CHANGE THIS IS, stripped of its payload.
    *
    * A report needs to say WHAT an update did, and a change's values are none of its business —
    * so this is the projection that keeps a summary line from pattern-matching on field vectors
    * and deck paths it will never print.
    */
  def kind: ChangeKind = this match
    case FieldsChanged(_, _) => ChangeKind.Fields
    case DeckChanged(_, _)   => ChangeKind.Deck
    case TagsChanged(_)      => ChangeKind.Tags

/** The kinds of change one update can carry, IN THE ORDER A REPORT NAMES THEM.
  *
  * Declaration order is load-bearing: `cli/Report.scala` sorts by it so that an update which
  * both rewrote content and moved deck always reads "update, and move to another deck" and
  * never the reverse. A summary whose wording depended on which change the planner happened to
  * compute first would be a diff nobody could review.
  */
enum ChangeKind:
  case Fields

  /** Ordered AFTER `Fields` and BEFORE `Deck`, which the declaration order decides for the
    * report: a run that rewrote a card and re-tagged it reads "update, and re-tag", never the
    * reverse. Tags sit beside content because they describe the same note; a deck move is about
    * where the card lives and reads last for that reason.
    */
  case Tags
  case Deck

  /** How a run report names this kind. Longhand, so a third kind cannot be added without
    * deciding what a person reading the summary is told about it.
    */
  def describe: String = this match
    case Fields => "update"
    case Tags   => "re-tag"
    case Deck   => "move to another deck"

/** Whether a run carries an action out, or sets it aside for a human to ask for by name.
  *
  * A THIRD KIND IS DELIBERATELY ABSENT. An action is attempted or it is deferred; "attempted
  * and failed" is an OUTCOME rather than a disposition, and lives in [[ExecutionReport]]
  * alongside what Anki said. Folding the two together is how a run comes to report a refusal
  * it never made.
  */
enum Disposition:
  case Attempt
  case Defer

enum SyncAction:
  /** In the markdown, not in Anki. The only case that legitimately carries no note id.
    *
    * The identity tag travels inside [[NewNote]], so it is written by the call that creates
    * the note. A note created without it would be unenumerable rather than merely unmatched.
    */
  case Create(key: CardKey, note: NewNote)

  /** In both, and differing.
    *
    * The note id is carried INSIDE the case rather than beside it, so "update something we
    * never found" cannot be constructed. The change set is non-empty, so "update that
    * changes nothing" cannot be constructed either — and it must not be, because the
    * `sha::` tag decides that before any call is made.
    */
  case Update(key: CardKey, noteId: AnkiNoteId, changes: NonEmptyVector[Change])

  /** The note is on the wrong NOTE TYPE, and must be moved onto the right one.
    *
    * Its own case because the failure it prevents is SILENT SUCCESS: `Obsidian Basic` and
    * `Obsidian Basic (and reversed card)` share field names, so an ordinary field update
    * succeeds, reports success, and the reverse card the author asked for never exists.
    *
    * TWO DIFFERENT THINGS PRODUCE IT, and only one of them is rare. A marker edited in the
    * vault changes the desired note type — that is the case this action was originally carved
    * out for. The other is a MIGRATION: when this tool stopped writing to Anki's stock `Basic`
    * / `Basic (and reversed card)` / `Cloze` and took note types of its own on 2026-08-21,
    * every note already synced became a note on the wrong type, all at once.
    *
    * IT CARRIES THE WHOLE FIELD SET AND THE WHOLE TAG SET, which is not redundancy with
    * [[Change.FieldsChanged]] but a requirement of the operation that carries it out: Anki's
    * `updateNoteModel` blanks every field and replaces every tag, so anything not passed is
    * destroyed. See [[obsidiananki.anki.Anki.changeNoteType]] for the add-on source that says
    * so.
    *
    * THE TAGS ARE SPLIT so that the ownership asymmetry stays legible: `ownedTags` are minted
    * by this tool — the `src::` identity and the `sha::` hash OF THE NEW CONTENT — while
    * `preservedTags` are foreign tags read off the note and echoed back verbatim. A stale
    * `sha::`, and any stale `orphaned::`, are simply not carried over; they are dropped by the
    * same write, which is why a retyped note needs no accompanying [[Unflag]].
    *
    * A CONTENT EDIT MADE IN THE SAME RUN TRAVELS WITH IT, because `fields` is the spec's
    * current field set rather than whatever the note held before — so a heading that was both
    * retagged and rewritten needs no accompanying [[Update]]. SO DOES A DECK MOVE, via `deck`.
    *
    * THAT LAST PART IS A CORRECTION, MADE 2026-08-22, and the argument it replaces is worth
    * keeping because it was wrong in an instructive way. This comment used to say a deck move
    * "does NOT travel with it", and that the next run would plan it "once the note types agree
    * — convergent rather than lossy". Convergent it was. What it also was: SILENT. `deckDiffers`
    * was computed only in the branch this action is the alternative to, so a note that changed
    * folder AND note type had its deck left wrong while the run reported itself CLEAN and exited
    * zero. The next run then moved the deck without being asked — which breaks the standing
    * property that a second run changes nothing, rather than hiding behind it.
    *
    * It mattered more than it looked. Deck changes are rare only while a vault is small and
    * still: notes and folders move constantly in a living one, and if decks are ever derived
    * from the heading path as well, every ancestor rename becomes a deck move.
    *
    * `deck` IS AN `Option` BECAUSE MOST RETYPES DO NOT MOVE ANYTHING. `None` means the note is
    * already where the vault says it should be, which keeps the executor from issuing a write
    * that changes nothing.
    *
    * THE COROLLARY IS THE THING TO KNOW WHEN THE MOVE IS DEFERRED: because everything about
    * such a note is carried by this one action, a run that does not make the move applies none
    * of it — not the edit, not the tags, not the deck. That is why a deferred move is reported
    * and makes the run non-clean, rather than being counted as nothing to do.
    */
  case Retype(
      key: CardKey,
      noteId: AnkiNoteId,
      from: String,
      to: String,
      fields: Vector[(String, String)],
      ownedTags: NonEmptyVector[OwnedTag],
      preservedTags: Vector[String],
      deck: Option[DeckPath],
  )

  /** In Anki, not in the markdown. Flagged, never deleted.
    *
    * Derivable ONLY from a complete scan. A partial scan cannot produce this case because
    * the data needed to justify it was never gathered.
    *
    * WHAT THIS DOES TODAY IS WRITE A TAG, AND NOTHING ELSE. The card stays in the daily
    * review rotation, so a card whose source heading is gone goes on being asked. Marc ruled
    * on 2026-08-19 that such a card should also be SUSPENDED — Anki's own mechanism, keeping
    * the card's deck and its entire scheduling state while removing it from the queue — and
    * that is not built: [[obsidiananki.anki.Anki]] has no suspend operation yet. Recorded
    * here because the gap is invisible from the case's name, and because the manual
    * reconciliation of a rename is only worth anything if the card is still there to reclaim.
    */
  case Flag(key: CardKey, noteId: AnkiNoteId)

  /** CARRY A NOTE'S IDENTITY INTO ITS `Identity` FIELD, and change nothing else.
    *
    * WHY IT EXISTS, MEASURED RATHER THAN ANTICIPATED. The identity moved from a tag into a field
    * on 2026-08-28, and the migration was written as a condition on the UPDATE path: a note
    * lacking the field is written once, on whatever run next reaches it. Marc's first real sync
    * afterwards showed what that missed — 56 of his 67 owned notes gained the field and eleven
    * did not, and the eleven were EXACTLY his orphans, holding twenty cards and thirty-two
    * reviews between them.
    *
    * AN ORPHAN IS NEVER UPDATED, so it was never reached. It is flagged and suspended once and
    * then deliberately left alone — a note already flagged is not flagged again — so no number
    * of runs would ever have written its field. Removing the tag at that point would have left
    * those notes with NO identity at all: unfindable by this tool, unfindable by `prune`, and
    * thirty-two reviews unreachable, with nothing reporting it.
    *
    * IT CARRIES THE NOTE'S OWN FIELDS BACK, with the identity added, rather than naming that one
    * field. `updateNoteFields`' behaviour when handed a SUBSET is not documented in this
    * repository and has not been measured here, and a wrong guess would blank the rest of an
    * orphan's content — the one thing worse than leaving it unmigrated. The observation already
    * read those fields, so writing them back costs nothing and relies on nothing unverified.
    *
    * IT IS NOT AN `Update`. An update is computed from a `CardSpec`, and an orphan has none —
    * its source is gone, which is what makes it an orphan. This action needs only what Anki
    * already holds plus a key, both of which the observation provides.
    */
  case CarryIdentity(
      key: CardKey,
      noteId: AnkiNoteId,
      fields: Vector[(String, String)],
      legacyTags: Vector[OwnedTag],
  )

  /** GIVE AN EXISTING NOTE THE KEY THE VAULT NOW PRODUCES FOR IT, because its heading moved.
    *
    * ═══ WHAT IT IS FOR ═══
    *
    * A card's identity is its heading path, so making an `## X` into a `### X` under some other
    * heading mints a NEW key and leaves the old one absent from the vault. Until 2026-09-05 that
    * was treated exactly as a deletion: the note holding the review history was flagged and
    * SUSPENDED, and a second note was created at the new key with nothing at all. This is the
    * action that stops that happening — it rewrites WHICH KEY AN EXISTING NOTE CLAIMS, and it
    * takes the place of the [[Create]] the moved card would otherwise have caused.
    *
    * ═══ IT PRESERVES THE CARD AND ITS HISTORY, WHICH IS THE ENTIRE POINT ═══
    *
    * The note keeps its Anki id, so it keeps its cards, and they keep their intervals, their
    * ease, their due dates and their whole review log. NOTHING HERE DELETES AND RECREATES, and
    * nothing could: the algebra has no delete, deliberately — `README.md`'s `prune` is the only
    * deletion this tool has ever contemplated, and the note at the removed `Relink` case below
    * exists to stop a deletion reaching the executor by falling through a catch-all.
    *
    * ═══ WHY IT MAY ONLY BE BUILT FROM ONE SHAPE OF EVIDENCE ═══
    *
    * `corroboration` IS THE FINDING ITSELF, not a copy of the two keys taken out of it, and that
    * is the type doing the work Marc's 2026-09-05 ruling asks of it. Only a
    * [[MoveFinding.Corroborated]] may reassign — exact substance agreement, every name divergence
    * accounted for by the key, and mutual uniqueness — and `MoveFinding`'s other five cases are
    * not subtypes of that one. So an [[MoveFinding.Ambiguous]] cannot be put here, and the only
    * function that constructs this action is an extension on the corroborated case
    * ([[MoveFinding.reassignment]]). "Ambiguous never applies" is therefore a fact about what can
    * be written down rather than a rule somebody has to keep.
    *
    * IT ALSO MAKES THE ACTION SELF-EXPLAINING. The finding carries what agreed and what differed,
    * so a run that moves review history can say why in the same line — which `cli/Report.scala`
    * does, and which the ruling makes mandatory rather than optional.
    *
    * ═══ WHAT IT WRITES, AND WHY EACH PART IS HERE ═══
    *
    * `fields` IS THE VAULT'S WHOLE FIELD SET FOR THE NEW CARD, identity included. Not the note's
    * own fields with one value swapped: the breadcrumb is one of them, and a note reassigned
    * without it would go on naming the place its heading left. It is also what makes the write
    * idempotent — afterwards the note holds exactly what a [[Create]] would have written.
    *
    * `newSha` TRAVELS WITH THE FIELDS AND IS WRITTEN AFTER THEM, exactly as
    * [[Change.FieldsChanged]] requires and for the reason recorded there: an interruption between
    * them leaves new content under a stale hash, which the next run sees as a difference and
    * simply writes again. The reverse leaves old content under a new hash and is skipped forever.
    *
    * `legacyTags` ARE THE `src::` TAGS OF A NOTE WRITTEN BEFORE THE IDENTITY MOVED INTO A FIELD,
    * removed by the same action for the reason [[CarryIdentity]] removes them: a note whose field
    * says one key and whose tag says another is a note two readers can disagree about. `Observer`
    * reads the field first, so such a tag is stale rather than dangerous — but a stale identity
    * that nothing ever clears is the state the migration exists to end.
    *
    * `vaultTags` AND `deck` KEEP THE RUN CONVERGENT IN ONE PASS. The note is landing at a key the
    * vault produces, so everything the ordinary update path would have made true of it has to be
    * true afterwards, or the next run plans work for a card this one just finished.
    *
    * ═══ WHAT IT DELIBERATELY DOES NOT DO ═══
    *
    * IT DOES NOT UNSUSPEND, AND IT DOES NOT CLEAR `orphaned::`. A stranded note may already have
    * been parked by an earlier run, and undoing that is exactly what [[Unflag]] is — including
    * the unsuspend-then-untag ordering `plan/Executor.scala` argues for at length. The planner
    * emits an `Unflag` BEFORE this action when the note carries the flag, naming the OLD key,
    * because that is the key the tag was minted from. Folding it in here would be a second
    * implementation of an ordering that already has one.
    */
  case Reassign(
      corroboration: MoveFinding.Corroborated,
      fields: Vector[(String, String)],
      newSha: String,
      vaultTags: Vector[OwnedTag],
      legacyTags: Vector[OwnedTag],
      deck: Option[DeckPath],
  )

  /** Previously flagged, now present again. Clears the orphan tag.
    *
    * Without this the flag set only grows, and a stale orphan becomes indistinguishable
    * from a live one in the list a human reviews before pruning.
    */
  case Unflag(key: CardKey, noteId: AnkiNoteId)

  /** Does a run under this policy CARRY THIS ACTION OUT, or set it aside?
    *
    * WRITTEN LONGHAND, WITH NO CATCH-ALL, AND THAT IS THE WHOLE POINT OF PUTTING IT HERE.
    * `Executor.run` used to answer this with `plan.actions.filter { case _: Retype => false;
    * case _ => true }` — a default that sweeps every action it has not heard of INTO execution.
    * `-Wconf:msg=exhaustive:e` is live, so asking the sum instead means a sixth action cannot
    * acquire a disposition by falling through: the compiler asks whether a policy defers it.
    *
    * THE CASE THIS GUARDS IS ALREADY NAMED IN `README.md`: `prune`, the command that deletes
    * flagged cards. Under the old catch-all a `Prune` action would have been handed to the
    * executor by a run whose entire contract is "do not act on an instruction you were not
    * given" — a deletion nobody asked for, in the mode chosen for caution.
    */
  /** WHICH CARD THIS ACTION IS ABOUT.
    *
    * NAMED `cardKey` RATHER THAN `key` because every case already has a field of that name, and
    * a method sharing it would shadow rather than summarise.
    *
    * IT LIVES ON THE TYPE BECAUSE TWO CALLERS ALREADY NEEDED IT. `Main` carried a private copy
    * whose own comment said it was in the wrong file, and the planner needed the same answer to
    * decide which notes an update already covers. A third copy would have been the point at
    * which they could start disagreeing about a case added later; here the match is exhaustive
    * and a new action must answer.
    */
  def cardKey: CardKey = this match
    case Create(key, _)                    => key
    case Update(key, _, _)                 => key
    case Retype(key, _, _, _, _, _, _, _)  => key
    case Flag(key, _)                      => key
    case Unflag(key, _)                    => key
    case CarryIdentity(key, _, _, _)       => key
    // THE KEY IT IS MOVING TO, NOT THE ONE IT IS LEAVING, and the choice is not a toss-up. This
    // method answers "which card is this action about", and after a reassignment the card IS the
    // one the vault now produces — which is what makes this action a substitute for the `Create`
    // the planner would otherwise have emitted, and what keeps `Planner`'s `updatedKeys` census
    // right. The key it left is on the finding, named as `stranded`.
    case Reassign(evidence, _, _, _, _, _) => evidence.candidate

  def dispositionUnder(policy: RetypePolicy): Disposition = this match
    case _: Create => Disposition.Attempt
    case _: Update => Disposition.Attempt
    case _: Flag   => Disposition.Attempt
    case _: Unflag => Disposition.Attempt
    // ATTEMPTED UNDER EVERY POLICY, and deliberately not deferrable. Deferring a retype is a
    // judgement about a large, destructive write; this one adds a note's own identity to a
    // field that is empty and touches nothing else. A run that declined to do it would leave a
    // note whose identity lives only in a tag this run has just stopped writing, which is the
    // failure this exists to prevent rather than a risk worth weighing. (It reaches only notes
    // that CAN hold the field, which is what `canHoldTheField` in the planner tests; a legacy
    // note on a stock type is reached by the retype path instead, which moves it somewhere it
    // can hold one.)
    case _: CarryIdentity => Disposition.Attempt
    // ATTEMPTED UNDER EVERY POLICY, AND THE ONE POLICY THAT EXISTS IS ABOUT SOMETHING ELSE.
    // `RetypePolicy` asks whether a run may move notes between NOTE TYPES; a reassignment moves
    // no note type and passes no shrink gate — it writes fields, a hash and an identity onto a
    // note that stays exactly where it is. Deferring it would also be the certain loss Marc's
    // 2026-09-05 ruling weighs against: the evidence for a pairing is the body still matching, so
    // a decision put off until the body is edited is a decision lost. See `MoveFinding` for the
    // whole asymmetry, and `Reassign` for what may and may not produce one.
    case _: Reassign => Disposition.Attempt
    case _: Retype =>
      policy match
        case RetypePolicy.Defer => Disposition.Defer
        case RetypePolicy.Apply => Disposition.Attempt

  // A `Relink` case sat here: a proposed pairing between an orphan and an unmatched key, for
  // a human to confirm. REMOVED 2026-08-19, when automatic rename detection was cut from v0
  // as a subsystem in its own right rather than a feature.
  //
  // Removed rather than kept-and-guarded, which is the usual remedy here: this project holds
  // that a type may be designed AHEAD of its implementation, and that an unconnected type
  // should be implemented or guarded, not deleted. That rule protects a design still on the
  // way in. Cut is not ahead — nothing in v0 will ever produce this, so it had no owner.
  //
  // The reconciliation it was for now happens by hand. That is lossless because an orphan is
  // SUSPENDED as well as tagged, so its card leaves the review queue with its scheduling intact
  // and nothing is deleted either way. What was
  // learned while
  // exploring detection is recorded in the design document under "Deliberately deferred" —
  // notably that candidates are confined to the cards sharing one note id, and that the
  // vault's git history is the input to any semantic approach rather than an alternative.

/** Why a plan could not be produced. Nothing is written when any of these is present. */
enum PlanError:
  /** Two sources derived the SAME key.
    *
    * Both sides name themselves — key, and each source's file, line and kind — because a
    * collision between a table cell and a deeply-nested heading is otherwise a message that
    * teaches nothing. Legibility here is a contract, not a nicety.
    */
  case DuplicateKey(key: CardKey, first: SourceRef, second: SourceRef)

  /** Two ANKI NOTES carry the same identity tag — the same collision as [[DuplicateKey]],
    * arriving from the other side.
    *
    * Fatal for the same reason, and the symmetry is the point: this side was silent for as
    * long as the lookup was built with `.toMap`, which kept one note and made the other
    * invisible to every later run. Both note ids are named because the remedy is manual — a
    * human has to open both in Anki and decide which to keep.
    */
  case DuplicateIdentityInAnki(key: CardKey, first: AnkiNoteId, second: AnkiNoteId)

  /** ONE Anki note carries MORE THAN ONE identity tag, so which card it is cannot be answered.
    *
    * The near-twin of [[DuplicateIdentityInAnki]] — that one is two notes claiming one identity,
    * this is one note claiming two — and it is fatal for the same reason. Whichever tag were
    * picked, the other key would look unclaimed, a second note would be created for it, and this
    * note would go on holding review history nobody could see.
    *
    * NOT REACHABLE THROUGH THIS TOOL'S OWN WRITES, established by reading every path that writes
    * tags: creation emits exactly one, an update touches only `sha::`, and a note-type move
    * rewrites the whole tag set and therefore HEALS this state rather than causing it. It is
    * reachable by editing tags in Anki, and the likely route is a sympathetic one: a heading is
    * renamed, the tool orphans the old note and creates a historyless new one, and the person
    * pastes the new tag onto the old note to rescue their history — without deleting the old.
    */
  case AmbiguousIdentityInAnki(
        noteId: AnkiNoteId,
        tags: NonEmptyVector[String],
        looksLike: Option[CardKey],
      )

  /** An Anki note's identity tag cannot be DECODED, so the note cannot be placed at all.
    *
    * THE WORST OF THE THREE, because such a note does not land in the wrong place — it leaves
    * the tool's field of view entirely. Until this case existed the decoding failure was thrown
    * away with `.toOption`, and the note was then never updated, never flagged, never prunable,
    * and provoked the creation of a duplicate for the very key it had been holding. Nothing
    * anywhere reported it.
    *
    * `reason` is the decoder's own words about THIS tag, carried rather than summarised: the
    * remedy is manual, and "malformed" without saying WHICH PART is malformed is not a remedy.
    */
  case UnreadableIdentityInAnki(
        noteId: AnkiNoteId,
        tag: String,
        reason: String,
        looksLike: Option[CardKey],
      )

  def describe: String = this match
    case DuplicateKey(key, first, second) =>
      s"duplicate card key '${key.path.render}' (note '${key.noteId.value}') derived from " +
        s"two sources: ${first.describe} and ${second.describe}"
    case DuplicateIdentityInAnki(key, first, second) =>
      s"two Anki notes claim the card key '${key.path.render}' (note '${key.noteId.value}'): " +
        s"note ids ${first.value} and ${second.value} — open both in Anki and delete one"
    case AmbiguousIdentityInAnki(noteId, tags, looksLike) =>
      s"Anki note ${noteId.value} carries ${tags.length} identity tags, so which card it is " +
        s"cannot be decided: ${tags.toVector.map("'" + _ + "'").mkString(", ")} — open it in " +
        "Anki and delete all but the one that belongs to it" + suggestionText(looksLike)
    case UnreadableIdentityInAnki(noteId, tag, reason, looksLike) =>
      s"Anki note ${noteId.value} has an identity tag this tool cannot read: '$tag' ($reason). " +
        "Until it is fixed the note is invisible to this tool — it will not be updated, will " +
        "not be reported as gone, and a second note will be created for whatever card it holds" +
        suggestionText(looksLike)

  /** The half of the message that makes the other half actionable.
    *
    * THE ENCODED TAG IS PRINTED IN FULL, and that is the whole reason this exists. The encoding
    * escapes spaces, `/`, `:` and Anki's two wildcard characters `_` and `*` — see
    * [[obsidiananki.model.TagCodec]] for why each is required — so `Cost / benefit` becomes
    * `cost%20%2f%20benefit`. Telling somebody to "fix the tag" without giving them the string is
    * telling them to do something they cannot reliably do; a near-miss puts the note straight
    * back into the state being reported.
    *
    * IT SAYS "LOOKS LIKE" AND STOPS THERE. The tool does not make the change, and the wording
    * must not imply it did. Where this suggestion comes from — a content hash that still matches
    * a card the vault produces — is evidence rather than a guess, but acting on it would move
    * review history between cards, silently and irreversibly, on the strength of the tool's own
    * reading of an ambiguous situation. That is a person's decision.
    *
    * SILENT WHEN THERE IS NOTHING TO SAY. No candidate, or several, prints nothing at all rather
    * than a hedge: a list of maybes is how a report stops being read.
    */
  private def suggestionText(looksLike: Option[CardKey]): String =
    looksLike.fold("") { key =>
      s". Its content still matches the card '${key.path.render}' in note " +
        s"'${key.noteId.value}', so it is most likely that card — if you agree, the tag it " +
        s"should carry is: ${TagCodec.encode(key).value}"
    }

/** Whether orphans were computed, and if not, why not. Reported rather than silent: a run
  * that could not look for orphans must not be mistaken for a run that found none.
  */
enum OrphanInference:
  case Computed
  case SuppressedIncompleteScan(reason: String)

final case class Plan(
    actions: Vector[SyncAction],
    orphanInference: OrphanInference,
    failures: Vector[BuildFailure],

    /** The cards ALREADY parked as orphaned when the collection was observed, BEFORE any of
      * `actions` is applied. Not a projection of what will be parked afterwards: this run may
      * park more (a `Flag`) or release some (an `Unflag`), and both are visible in `actions`.
      *
      * KEYS RATHER THAN A COUNT, because the count is derivable from the keys and the keys are
      * not derivable from the count. How much of this to show a reader is the report's
      * decision to make, not the planner's.
      *
      * NO DEFAULT VALUE, deliberately. A default would let a construction site omit the census
      * and report zero parked cards over a collection holding dozens — which is the shape of
      * silent wrongness this field exists to end.
      */
    parked: Vector[CardKey],

    /** WHAT THE VAULT AND THE COLLECTION SAID ABOUT EACH OTHER'S LOOSE ENDS — one finding per
      * note the vault has stopped accounting for, whatever the evidence came to.
      *
      * CONSERVATION: every note the survey considered stranded appears in EXACTLY ONE finding,
      * because `MoveEvidence.survey` maps over that set rather than filtering it. That is what
      * makes this readable as an account rather than as a highlight reel.
      *
      * IT CARRIES THE CORROBORATED ONES TOO, WHICH LOOK REDUNDANT BESIDE `actions` AND ARE NOT.
      * They are the SAME values the [[SyncAction.Reassign]] actions carry, not copies, and having
      * every finding in one place is what lets a reader ask "what did the run make of the notes
      * that went missing" and get a total answer. Filtering the applied ones out would make the
      * count here mean "the ones nothing was done about", which is a different and less useful
      * fact that a reader would have to be told about.
      *
      * EMPTY IS AMBIGUOUS ON PURPOSE AND `orphanInference` RESOLVES IT. Nothing stranded and a
      * scan that could not look both produce no findings; which of the two it was is the question
      * that field already answers, so a second flag saying the same thing would be one more
      * thing to keep in step.
      *
      * NO DEFAULT VALUE, following `parked` immediately above and for its reason: a default
      * would let a construction site omit the survey and report that nothing moved, over a run
      * that reassigned a dozen cards.
      */
    moveEvidence: Vector[MoveFinding],
)
