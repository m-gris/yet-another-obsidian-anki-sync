package obsidiananki.plan

import cats.MonadError
import cats.syntax.all.*
import obsidiananki.anki.*
import cats.data.NonEmptyVector
import obsidiananki.model.{CardKey, KeyError, OwnedTag, TagCodec}

// Reading Anki's current state, and carrying out a plan. Both are written against the
// algebra rather than a concrete client, so the same code runs against the in-memory
// interpreter and, later, against AnkiConnect — which is what makes "a second run changes
// nothing" provable as a law without a live collection.

/** An action that could not be carried out, with the reason. */
final case class ExecutionFailure(action: SyncAction, error: AnkiError)

/** What a run of a plan came to.
  *
  * TWO VECTORS, NOT ONE, AND THEY ARE DIFFERENT FACTS. A FAILURE is an action that was
  * attempted and refused. A DEFERRED action was deliberately not attempted, because the run
  * was not asked to move notes between note types — see [[RetypePolicy]]. Reporting the second
  * as the first would tell someone their collection is misbehaving when the tool simply did
  * what it was told; reporting it as nothing at all would be the silent skip this project
  * keeps finding.
  *
  * `deferred` IS TYPED AS THE RETYPE CASE rather than as `SyncAction`, because that is the only
  * action any policy defers. A reader does not have to go and check which ones can appear here.
  */
final case class ExecutionReport(
    failures: Vector[ExecutionFailure],
    deferred: Vector[SyncAction.Retype],

    /** EVERY ACTION THAT WAS CARRIED OUT, in the order it was carried out.
      *
      * STATED BY THE EXECUTOR RATHER THAN DERIVED BY A READER. A caller holding the plan and this
      * report could compute "applied" as everything that is neither deferred nor failed, and that
      * subtraction is exactly the catch-all shape this codebase keeps removing: a fourth outcome
      * arrives one day and is silently counted as done. The executor knows which calls returned;
      * nothing else does.
      *
      * IT EXISTS BECAUSE A RETYPE BECAME THE DEFAULT (2026-08-27). Moving a note between note
      * types blanks every field and replaces every tag before writing them back — the largest
      * single write this tool performs — and until this field existed a run that did it said only
      * `attempted: 1 / failed: 0`. The report that names what moved is `Report.appliedRetypes`.
      */
    applied: Vector[SyncAction],

    /** CHANGES THE RUN WITHHELD BECAUSE NOBODY HAS ANSWERED FOR THEM, with the price each would
      * cost and the name to answer with.
      *
      * SEPARATE FROM `failures`, AND THAT SEPARATION IS THE POINT. These used to be raised as
      * errors, so a run that had made a correct and deliberate decision announced it under
      * `SOME ACTIONS FAILED`. Nothing failed: the tool declined to destroy cards without being
      * asked, which is the behaviour Marc ruled for on 2026-08-27.
      *
      * SEPARATE FROM `deferred` TOO, though both are withheld. A deferral is this tool obeying
      * an instruction it was given — `--no-migrate-note-types` — and needs no answer. This is a
      * question nobody has been asked yet, and it stays until somebody answers it.
      */
    pending: Vector[PendingRetype],

    /** Changes the author APPROVED BY NAME and that were carried out, with the price each was
      * quoted at. Reported so a run can say what it spent, in the same numbers it offered.
      */
    authorised: Vector[PendingRetype],

    /** Names the author gave that matched nothing waiting.
      *
      * REPORTED RATHER THAN RAISED, and the run still does everything else. A name that
      * resolves to nothing does not invalidate the rest of the plan — it usually means a typo
      * or a change already dealt with — so stopping the run would punish the other notes for
      * it. It makes the run not clean, and the block listing what IS waiting follows it.
      */
    unknownApprovals: Vector[DecisionHandle],
)

object Observer:

  /** ONE bulk query, not one per card.
    *
    * A lookup driven by the markdown's keys can never find an orphan, because an orphan is
    * precisely a key the markdown does not have. The whole observed set has to be gathered
    * for the difference to be computable at all.
    *
    * ⚠️ AND IT IS VAULT-BLIND, WHICH IS WHY ONE PROFILE HOLDS ONE VAULT. Nothing in a `src::`
    * tag records which vault a note came from — the tag is `(frontmatter id, card path)` and
    * stops there — so this query returns every note this tool has ever created in the open
    * collection, from any vault. A second vault's keys are absent from the current scan, so
    * `Planner` reads every one of them as a deleted heading and flags AND SUSPENDS it.
    *
    * NOTHING HERE CAN DETECT THAT, and no filter can be added without a vault to filter on.
    * The whole argument, and the operating rule that follows from it, is `README.md`, "ONE
    * VAULT PER ANKI PROFILE"; it is not restated here, because two copies of one statement is
    * how they drift.
    */
  def observe[F[_]: cats.Monad](anki: Anki[F]): F[ObservedState] =
    for
      ids   <- anki.findNotesByTagPrefix(s"${OwnedTag.SrcPrefix}::")
      notes <- anki.notesInfo(ids)
      decks <- notes.traverse(n => firstDeckOf(anki, n.id))
    yield
      val resolutions = notes.zip(decks).map(resolve)
      ObservedState(
        notes = resolutions.collect { case Right(card) => card },
        unresolved = resolutions.collect { case Left(problem) => problem },
      )

  /** Place ONE note, or say why it cannot be placed. Never silently neither.
    *
    * ═══ WHY THIS IS A PARTITION AND NOT A FILTER ═══
    *
    * This was `.find(…).flatMap(TagCodec.decode(_).toOption).map(…)` — a chain in which BOTH
    * steps could drop the note without a word. `.find` took an arbitrary tag when a note carried
    * several, and `.toOption` deleted the decoder's error outright. A dropped note is not merely
    * mis-filed: it is absent from `ObservedState`, and therefore never updated, never flagged as
    * gone, never prunable — while the planner, seeing no note for that key, creates a SECOND one.
    * The note holding the review history goes quiet and diverges, reported by nothing.
    *
    * `ObservedState.byKey`'s own docstring already condemned exactly this outcome for the case
    * of two notes claiming one identity. The rule was written down and broken eleven lines away,
    * which is why the shape matters more than the two fixes: every note the query returned now
    * lands in exactly one side of an `Either`, so a future edit cannot reintroduce a silent drop
    * without deleting a branch that has to be written on purpose.
    *
    * ═══ THE CASE-FOLDING ASYMMETRY, WHICH IS DELIBERATE ═══
    *
    * The tag is FOUND case-insensitively and DECODED case-sensitively, and the difference is not
    * an oversight. `OwnedTag.isOwned` treats `SRC::x` as ours precisely because Anki cannot tell
    * it from `src::x`, so a hand-typed capitalised tag must be recognised as an attempt at
    * identity rather than passed over as somebody else's tag. It then fails to decode and is
    * REPORTED — which is the point. Matching case-sensitively here would leave such a note
    * looking like a note this tool has nothing to do with, and it would vanish again.
    */
  private def resolve(
      note: ObservedNote,
      deck: Option[DeckPath],
  ): Either[UnplaceableNote, ObservedCard] =
    def unplaceable(problem: IdentityProblem) =
      UnplaceableNote(note.id, problem, recordedShaOf(note))

    val prefix = s"${OwnedTag.SrcPrefix}::"
    note.tags.filter(_.toLowerCase(java.util.Locale.ROOT).startsWith(prefix)) match
      case Vector(only) =>
        TagCodec
          .decode(only)
          .left
          .map(err => unplaceable(IdentityProblem.Unreadable(only, reasonFor(err))))
          .map(key => ObservedCard(key, note, deck))

      // The query that produced this note matched on the prefix, so an empty result here would
      // mean the collection changed under the run. Reported as unreadable rather than dropped:
      // "the note has no identity" is exactly as unplaceable as "its identity is malformed".
      case Vector() =>
        Left(
          unplaceable(
            IdentityProblem.Unreadable(
              "",
              s"the note matched a search for '$prefix' but carries no such tag now",
            )
          )
        )

      case several =>
        Left(unplaceable(IdentityProblem.Ambiguous(NonEmptyVector.fromVectorUnsafe(several))))

  /** The content hash the last successful sync recorded on this note, if exactly one is there.
    *
    * DUPLICATED FROM [[ObservedCard.recordedSha]] RATHER THAN SHARED, and the duplication is
    * forced: that one is an extension on a note that HAS been placed, and this is needed for one
    * that has not. Both refuse to choose between two hashes for the same reason — a note carrying
    * two says nothing trustworthy about what it holds.
    */
  private def recordedShaOf(note: ObservedNote): Option[String] =
    note.tags.filter(_.toLowerCase(java.util.Locale.ROOT).startsWith(s"${OwnedTag.ShaPrefix}::")) match
      case Vector(only) =>
        Some(only.drop(OwnedTag.ShaPrefix.length + 2).toLowerCase(java.util.Locale.ROOT))
      case _ => None

  /** The decoder's own words about ONE tag, in a form a person can act on.
    *
    * `KeyError` has no `describe` of its own, and this deliberately does not add one: it is a
    * `model/` type, and `model/` depends on nothing. The wording lives where the message is
    * built, which is here.
    */
  private def reasonFor(err: KeyError): String = err match
    case KeyError.BlankNoteId              => "the note id part is blank"
    case KeyError.EmptyHeadingSegment(raw) => s"a heading segment is empty in '$raw'"
    case KeyError.EmptyPropertyName(raw)   => s"a property name is empty in '$raw'"
    case KeyError.MalformedTag(_, reason)  => reason

  /** A note's deck, via its cards — decks are a per-CARD property while identity is
    * per-note. Taking the first card's deck is the honest simplification while every card
    * of a note shares a deck; a partial move that broke that assumption would show up as a
    * deck change on the next run rather than being silently ignored.
    */
  private def firstDeckOf[F[_]: cats.Monad](anki: Anki[F], id: AnkiNoteId): F[Option[DeckPath]] =
    anki.cardsOf(Vector(id)).flatMap { cards =>
      cards.headOption match
        case None       => cats.Monad[F].pure(None)
        case Some(card) => anki.deckOf(card)
    }

object Executor:

  /** Carry out a plan, reporting what FAILED and what was deliberately DEFERRED rather than
    * stopping at the first refusal.
    *
    * A plan of fifty actions whose thirtieth fails must not abandon the other twenty. The
    * remainder is applied, the failures are reported, and the caller exits non-zero — the
    * same shape as the scan's "sync what is sound, report what is not".
    *
    * THIS IS SAFE BECAUSE OF THE HASH, not because failures are rare: `sha::` records what
    * was actually written, so a half-applied plan leaves Anki in a state the next run reads
    * correctly and simply plans less. Partial application is resumable by construction.
    *
    * WRITE ORDERING IS PESSIMISTIC BY DESIGN. Within an update the fields are written
    * FIRST and the content hash describing them LAST, so an interruption between the two
    * leaves new content under a stale hash. The next run sees the mismatch and writes it
    * again — redundant work, harmless because the write is idempotent.
    *
    * THE REVERSE ORDER IS THE TRAP, and it is not obvious. Writing the hash first leaves
    * OLD content under the NEW hash, and [[Planner]] decides "nothing to do" by comparing
    * exactly those two — so the note is skipped by every later run, silently and
    * permanently. This is not hypothetical: it is the order this code was originally
    * written in, under a comment arguing it was the safe one.
    * `ExecutorInterruptionTest` is the property that caught it and is what stops it coming
    * back.
    *
    * The principle generalises: when an interruption must leave the system in one of two
    * wrong states, choose the one that makes work be REDONE over the one that makes work be
    * BELIEVED DONE.
    *
    * RETYPES ARE THE ONE THING A POLICY CAN SWITCH OFF, and under [[RetypePolicy.Defer]] they
    * are not attempted at all: no note type is read, nothing is written, and they come back in
    * [[ExecutionReport.deferred]] for the caller to report. Every other action runs either way.
    *
    * UNDER [[RetypePolicy.Apply]] THE NOTE-TYPE SHAPES ARE READ FIRST, before any action of any
    * kind runs, and a failure to read them ABORTS the whole run rather than being collected.
    * That is the one place this function departs from "collect and carry on", and it departs in
    * the safe direction: it happens before the first write, so a run that ends there has
    * changed nothing. The alternative — discovering per note that the gate cannot be evaluated
    * — would report one unreadable collection as N ordinary problems, the same conflation
    * `anki/Anki.scala` records for transport failures.
    *
    * The reads cost nothing when there is nothing to retype: `Retyping.noteTypesIn` is empty,
    * so no request is made.
    */
  def run[F[_]](
      plan: Plan,
      anki: Anki[F],
      policy: RetypePolicy,
      approved: Set[DecisionHandle],
  )(using
      F: MonadError[F, AnkiError]
  ): F[ExecutionReport] =
    policy match
      case RetypePolicy.Defer =>
        // SPLIT BY ASKING THE SUM, not by a catch-all. `plan.actions.filter { case _: Retype =>
        // false; case _ => true }` stood here, and its default swept every action it had not
        // heard of INTO execution — including, one day, the `prune` this project has already
        // announced, which DELETES cards. A deletion carried out by the run whose whole
        // contract is "do not act on an instruction you were not given" is the failure that
        // default made possible. `SyncAction.dispositionUnder` is written longhand, so a sixth
        // action must answer for itself before it compiles.
        val (setAside, rest) =
          plan.actions.partition(_.dispositionUnder(policy) == Disposition.Defer)

        // NARROWED BACK TO `Retype` FOR THE REPORT, because `ExecutionReport.deferred` promises
        // that type and its consumers read `from`/`to` off it. The `collect` is a projection of
        // an already-decided split rather than the decision itself — which is the difference
        // between a partial function used for its type and one used for its control flow.
        val deferred = setAside.collect { case retype: SyncAction.Retype => retype }
        // NOTHING IS WAITING UNDER `Defer`: no retype is attempted at all, so none of them
        // reaches the point where a price would be quoted.
        applyEach(rest, anki, Map.empty, policy, Set.empty).map((failures, applied) =>
          ExecutionReport(failures, deferred, applied, Vector.empty, Vector.empty, approved.toVector)
        )

      case RetypePolicy.Apply =>
        // PRICED BEFORE ANYTHING IS WRITTEN, AND THAT ORDER IS A MEASURED CONSTRAINT RATHER
        // THAN A PREFERENCE. Once a note sits on a narrower note type, AnkiConnect's
        // `cardsInfo` fails for the WHOLE note, so what a narrowing would have cost cannot be
        // read back afterwards. See `docs/EVOLVABILITY.md` § M4.
        //
        // AND SET ASIDE THE SAME WAY A DEFERRAL IS, rather than being refused inside the
        // executor. A change nobody has answered for is not attempted, so it cannot fail, so it
        // has no business in the failure count.
        for
          // ASKED, NOT RE-DERIVED. A dry run and a real run must not each work out what is
          // waiting and what it costs: two derivations of one answer is how the preview came
          // to contradict the run in the first place, and adding a second copy while fixing
          // that would be the same defect wearing a different hat.
          decisions <- decide(plan, anki, policy, approved)
          // WIDENED TO `SyncAction` SO THE COMPARISON IS THE ONE INTENDED. `waiting` is a set
          // of `Retype`, and asking it about a `SyncAction` compiles only once the element type
          // matches — the alternative would have been a cast, which would silently answer false
          // for every action and quietly execute the very changes being withheld.
          waiting: Set[SyncAction] = decisions.pending.map(_.retype: SyncAction).toSet
          rest = plan.actions.filterNot(waiting.contains)
          // BY IDENTITY, NOT BY ACTION. `runOne` receives one action and has to decide whether
          // THAT change was approved; the key is what both sides can name it by.
          authorisedKeys = decisions.authorised.map(_.retype.key).toSet
          outcome <- applyEach(rest, anki, decisions.shapes, policy, authorisedKeys)
        yield ExecutionReport(
          outcome._1,
          Vector.empty,
          outcome._2,
          decisions.pending,
          decisions.authorised,
          decisions.unknownApprovals,
        )

  /** WHAT A RUN DECIDES ABOUT EVERY RETYPE IN A PLAN, AND WHAT THE WITHHELD ONES WOULD COST.
    *
    * ONE ANSWER FOR BOTH CALLERS, WHICH IS THE WHOLE REASON IT IS A TYPE. A dry run renders it
    * and a real run acts on it; neither derives it. Two derivations of one answer is exactly
    * how the preview came to announce migrations the run then refused.
    *
    * IT CARRIES THE SHAPES IT READ so that the real run does not fetch them a second time. That
    * is a saving, but the reason is correctness rather than cost: a second read could return
    * something different, and the run would then execute against shapes the decision was not
    * made from.
    */
  final case class RetypeDecisions(
      verdicts: Vector[(SyncAction.Retype, RetypeVerdict)],

      /** Changes that would destroy cards and that NOBODY HAS ANSWERED FOR. */
      pending: Vector[PendingRetype],

      /** Changes that would destroy cards and that the author HAS answered for, by name.
        *
        * CARRIES THE PRICE THAT WAS QUOTED, not a way to recompute one. What is reported as
        * spent must be what was offered; recomputing invites the two to disagree across an edit.
        */
      authorised: Vector[PendingRetype],

      /** Names the author gave that match nothing waiting.
        *
        * DATA RATHER THAN AN EXCEPTION, on purpose. A name that resolves to nothing is an
        * ordinary mistake — a typo, or a note already dealt with — and the useful answer is
        * "that names nothing, and here is what IS waiting", which needs the pending list to
        * hand. Raising here would reach the person as a bare error with the list thrown away.
        */
      unknownApprovals: Vector[DecisionHandle],
      shapes: Map[String, NoteTypeShape],
  )

  /** WHAT A REAL RUN WOULD DECIDE ABOUT EVERY RETYPE, WITHOUT WRITING ANYTHING.
    *
    * THIS IS THE DRY RUN'S HALF OF THE FIX. `--dry-run` returns before [[run]] is ever called,
    * so until this existed the preview could see the policy and never the note-type shapes, and
    * announced migrations the real run then refused.
    *
    * IT PAYS THE SAME PRICE THE RUN PAYS, AND ONLY THAT. Under [[RetypePolicy.Defer]] no
    * request is made at all — the answer does not depend on the collection. Under
    * [[RetypePolicy.Apply]] it makes exactly the reads [[run]] makes: two per DISTINCT note
    * type named in the plan, de-duplicated by `Retyping.shapesOf`, and none at all when the
    * plan holds no retypes. A dry run is therefore no longer free in the one case where being
    * free meant being wrong.
    *
    * A FAILURE TO READ THE SHAPES PROPAGATES rather than being swallowed into a verdict. The
    * preview cannot both fail to look and claim to know; a run that cannot reach the collection
    * has a connection problem, and saying so is more useful than reporting every retype as
    * unmeasurable.
    */
  def decide[F[_]](
      plan: Plan,
      anki: Anki[F],
      policy: RetypePolicy,
      approved: Set[DecisionHandle],
  )(using
      F: MonadError[F, AnkiError]
  ): F[RetypeDecisions] =
    val retypes = plan.actions.collect { case retype: SyncAction.Retype => retype }

    // THE POLICY BRANCH DECIDES WHETHER TO READ, NOT WHAT TO ANSWER. Both arms hand the same
    // `policy` to the same `verdictFor`; the only difference is whether shapes were fetched
    // first. Answering `DeferredByPolicy` directly here would be a second copy of the decision,
    // which is the exact defect this function exists to remove.
    policy match
      case RetypePolicy.Defer =>
        F.pure(
          RetypeDecisions(
            retypes.map(r => r -> Retyping.verdictFor(r.from, r.to, policy, Map.empty)),
            // NOTHING IS WAITING, AND NOTHING WAS READ TO ESTABLISH IT. Under `Defer` no retype
            // is attempted at all, so none reaches the point where a price would be quoted.
            pending = Vector.empty,
            authorised = Vector.empty,
            // EVERY APPROVAL IS UNKNOWN HERE, and saying so beats silence. A run told both to
            // approve a change and not to migrate anything has been given two instructions that
            // contradict each other; answering "that name matches nothing" is how the person
            // finds out, rather than the approval vanishing without comment.
            unknownApprovals = approved.toVector,
            shapes = Map.empty,
          )
        )

      case RetypePolicy.Apply =>
        for
          shapes <- Retyping.shapesOf(anki, Retyping.noteTypesIn(plan))
          verdicts = retypes.map(r => r -> Retyping.verdictFor(r.from, r.to, policy, shapes))
          priced <- Retyping.pendingOf(anki, verdicts)
        yield
          // SPLIT ON THE NAME THE AUTHOR GAVE, and report the names that matched nothing. The
          // split happens HERE rather than in `run` so that a dry run previews an approval
          // exactly as the run will carry it out — the two must not disagree about what an
          // approval does, which is the divergence closed on 2026-08-28.
          val (authorised, pending) = priced.partition(p => approved.contains(p.handle))
          RetypeDecisions(
            verdicts,
            pending,
            authorised,
            unknownApprovals = (approved -- priced.map(_.handle).toSet).toVector,
            shapes,
          )

  private def applyEach[F[_]](
      actions: Vector[SyncAction],
      anki: Anki[F],
      shapes: Map[String, NoteTypeShape],
      policy: RetypePolicy,
      authorised: Set[CardKey],
  )(using F: MonadError[F, AnkiError]): F[(Vector[ExecutionFailure], Vector[SyncAction])] =
    // EACH ACTION ANSWERS FOR ITSELF, as a Left or a Right, rather than as an `Option` whose
    // `None` means success. The old shape discarded which actions had succeeded, so the report
    // could say how many failed and never what was done.
    actions
      .traverse(action =>
        runOne(action, anki, shapes, policy, authorised).attempt.map {
          case Left(error) => Left(ExecutionFailure(action, error))
          case Right(_)    => Right(action)
        }
      )
      .map(results => (results.collect { case Left(f) => f }, results.collect { case Right(a) => a }))

  private def runOne[F[_]](
      action: SyncAction,
      anki: Anki[F],
      shapes: Map[String, NoteTypeShape],
      policy: RetypePolicy,
      authorised: Set[CardKey],
  )(using F: MonadError[F, AnkiError]): F[Unit] =
    action match
      // The identity tag travels inside NewNote, so it is written by the call that creates
      // the note. There is no window in which an untagged, unenumerable note can exist.
      case SyncAction.Create(_, note) =>
        anki.addNote(note).void

      case SyncAction.Update(_, noteId, changes) =>
        changes.toVector.traverse_ {
          case Change.FieldsChanged(fields, newSha) =>
            for
              // FIELDS FIRST, HASH LAST. See the note on write ordering above: an
              // interruption here must leave work to be REDONE, never work believed done.
              _ <- anki.updateNoteFields(noteId, fields)
              _ <- replaceOwnedPrefix(anki, noteId, OwnedTag.ShaPrefix, OwnedTag.sha(newSha))
            yield ()

          case Change.DeckChanged(_, to) =>
            // Decks are per-card, so a note-level move must fan out to its cards.
            anki.cardsOf(Vector(noteId)).flatMap(anki.changeDeck(_, to))
        }

      case SyncAction.Retype(key, noteId, from, to, fields, ownedTags, preservedTags, deck) =>
        // ONE WRITE, carrying the whole field set and the whole tag set, because Anki blanks
        // and replaces both — see `anki/Anki.scala`'s `changeNoteType`. It is therefore
        // atomic in a way an Update is not: there is no window in which the note holds new
        // content under a stale hash, so none of the fields-first-hash-last ordering that
        // governs `Change.FieldsChanged` applies here.
        val what = s"move '${key.path.render}' from note type '$from' to '$to'"

        // THE MOVE ITSELF, NAMED ONCE, because two branches now reach it: a move that costs
        // nothing, and one the author approved by name. Writing it out twice would be two
        // copies of the write ordering below, free to drift apart.
        //
        // NOTE TYPE FIRST, DECK SECOND, and the order follows this file's own rule: leave work
        // to be REDONE rather than work believed done. Interrupted between the two, the note is
        // on its new type and its deck is still wrong — which the next run can see and plan,
        // because the note types now agree so the deck comparison is reached. The reverse order
        // would move the deck of a note whose type never changed, and the run would look
        // half-applied with no way to tell.
        // A `def`, NOT A `val`, AND THE DIFFERENCE IS A WRITE TO SOMEBODY'S COLLECTION.
        // `F` is not always lazy: `InMemoryAnki` interprets into `Either`, which is EAGER, so a
        // `val` here PERFORMS THE MOVE at the point of definition — before the match below has
        // decided whether it should happen at all. Written as a `val` first on 2026-08-28 and
        // caught the same day by a test asserting that a refused move leaves the note alone; it
        // had moved. The lazy `EitherT[IO, ...]` interpreter used against a real collection
        // would have hidden it entirely.
        def move =
          anki.changeNoteType(noteId, to, fields, ownedTags, preservedTags) *>
            deck.fold(F.unit)(d => anki.cardsOf(Vector(noteId)).flatMap(anki.changeDeck(_, d)))

        // ASKED, NOT RE-DECIDED. This branch used to read the two shapes and call
        // `refusalFor` itself, which is how the dry run came to disagree with the run: the
        // preview could reach the policy half of the decision and never this half. Both now
        // call `Retyping.verdictFor`, so there is one decision and it cannot drift.
        Retyping.verdictFor(from, to, policy, shapes) match
          // REFUSED PER NOTE, LOUDLY, and reported as a failure rather than as a deferral:
          // a deferral is this tool declining to act on instruction, whereas this is it
          // declining to act on evidence, and the person needs to see the difference.
          case RetypeVerdict.RefusedByShapes(refusal) =>
            F.raiseError(
              AnkiError.UnsupportedOperation(what, s"${refusal.describe} — ${refusal.remedy}")
            )

          // APPROVED BY NAME, SO CARRIED OUT. The author was shown what this costs and asked
          // for it specifically — `--approve` names one change, never a category — so the same
          // move runs as for any other. What is NOT done here is cleaning up afterwards: the
          // cards left behind cannot be deleted through AnkiConnect at all (VERIFIED against
          // the add-on's own action list, 2026-08-28), so they remain until the person runs
          // Anki's `Check Database`. The report says so rather than the run pretending.
          case RetypeVerdict.DestroysCards(_) if authorised.contains(key) => move

          // AN INVARIANT BREAK, NOT A CASE TO HANDLE. `Executor.run` prices every change that
          // would destroy cards and partitions out the ones nobody approved, before `applyEach`
          // is reached — so arriving here unapproved means that partition and this branch
          // disagree about which changes are withheld. Refusing politely would hide the
          // disagreement and leave a change reported as neither applied nor waiting.
          case RetypeVerdict.DestroysCards(_) =>
            F.raiseError(
              AnkiError.UnsupportedOperation(
                what,
                "a change awaiting an answer reached the executor — the partition in " +
                  "`Executor.run` and this branch disagree, which is a defect in this tool",
              )
            )

          // AN INVARIANT BREAK, NOT A CASE TO HANDLE GRACEFULLY. Under `Defer` every Retype is
          // partitioned out of execution by `dispositionUnder` before `applyEach` is reached,
          // so arriving here means that partition and this decision disagree about what
          // deferral means. Raising is the only honest answer: silently applying would perform
          // the one operation the policy exists to withhold, and silently skipping would
          // report a clean run over work that was never done.
          case RetypeVerdict.DeferredByPolicy =>
            F.raiseError(
              AnkiError.UnsupportedOperation(
                what,
                "a deferred retype reached the executor — the partition in `Executor.run` and " +
                  "`Retyping.verdictFor` disagree about what RetypePolicy.Defer means",
              )
            )

          case RetypeVerdict.WillApply => move

          // NOT REACHABLE from `run`, which reads the shape of every note type the plan names
          // before it applies anything. Raising rather than defaulting is still the right
          // shape: any default here is a decision about somebody's review history taken by a
          // branch nobody meant to write. It is now a NAMED case rather than a catch-all, so
          // a fifth verdict has to answer for itself here instead of being swept in with it.
          case RetypeVerdict.ShapesUnavailable(_, _) =>
            F.raiseError(
              AnkiError.UnsupportedOperation(
                what,
                "the shape of one of those note types was never read, so this move could not " +
                  "be checked for safety",
              )
            )

      // TAG THEN SUSPEND, AND THE ORDER IS THE PESSIMISTIC ONE. Interrupted between the two,
      // the note is tagged and still in the rotation — visible, findable, and put right by the
      // next run. The reverse leaves a card suspended with nothing saying why, which is a card
      // that has silently stopped being studied and cannot be found by searching for orphans.
      //
      // SUSPENSION IS PER CARD, THE TAG IS PER NOTE. A three-field note has up to three cards
      // and all of them must leave the queue: suspending one of three would leave the heading's
      // other directions still being asked.
      case SyncAction.Flag(key, noteId) =>
        anki.addTags(Vector(noteId), Vector(OwnedTag.orphaned(key))) *>
          anki.cardsOf(Vector(noteId)).flatMap(anki.suspend)

      // UNSUSPEND FIRST, THEN CLEAR THE TAG — the mirror of the above, and pessimistic for the
      // same reason. Interrupted here, the card is back in the queue and still tagged: it gets
      // studied and still appears in the orphan list, which is noisy and harmless. Clearing the
      // tag first would leave a suspended card that nothing reports and no later run repairs,
      // because an untagged live card is indistinguishable from a healthy one.
      case SyncAction.Unflag(key, noteId) =>
        anki.cardsOf(Vector(noteId)).flatMap(anki.unsuspend) *>
          anki.removeTags(Vector(noteId), Vector(OwnedTag.orphaned(key)))

  /** Replace whichever tag currently occupies one of OUR prefixes, leaving every other tag
    * alone.
    *
    * Needed because a stale `sha::` must not accumulate alongside the new one — two content
    * hashes on one note would make "has this changed?" unanswerable. Reads all tags to find
    * the old one, but writes only within our own prefix.
    */
  private def replaceOwnedPrefix[F[_]: cats.Monad](
      anki: Anki[F],
      noteId: AnkiNoteId,
      prefix: String,
      replacement: OwnedTag,
  ): F[Unit] =
    for
      notes <- anki.notesInfo(Vector(noteId))
      stale = notes
        .flatMap(_.tags)
        .filter(_.toLowerCase(java.util.Locale.ROOT).startsWith(s"$prefix::"))
        .filterNot(_ == replacement.value)
        .map(OwnedTag.unsafeFromString)
      _ <- if stale.isEmpty then cats.Monad[F].unit else anki.removeTags(Vector(noteId), stale)
      _ <- anki.addTags(Vector(noteId), Vector(replacement))
    yield ()
