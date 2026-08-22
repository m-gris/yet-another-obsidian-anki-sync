package obsidiananki.plan

import cats.MonadError
import cats.syntax.all.*
import obsidiananki.anki.*
import cats.data.NonEmptyVector
import obsidiananki.model.{KeyError, OwnedTag, TagCodec}

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
)

object Observer:

  /** ONE bulk query, not one per card.
    *
    * A lookup driven by the markdown's keys can never find an orphan, because an orphan is
    * precisely a key the markdown does not have. The whole observed set has to be gathered
    * for the difference to be computable at all.
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
  def run[F[_]](plan: Plan, anki: Anki[F], policy: RetypePolicy)(using
      F: MonadError[F, AnkiError]
  ): F[ExecutionReport] =
    policy match
      case RetypePolicy.Defer =>
        val deferred = plan.actions.collect { case retype: SyncAction.Retype => retype }
        val rest     = plan.actions.filter {
          case _: SyncAction.Retype => false
          case _                    => true
        }
        applyEach(rest, anki, Map.empty).map(ExecutionReport(_, deferred))

      case RetypePolicy.Apply =>
        Retyping
          .shapesOf(anki, Retyping.noteTypesIn(plan))
          .flatMap(shapes => applyEach(plan.actions, anki, shapes).map(ExecutionReport(_, Vector.empty)))

  private def applyEach[F[_]](
      actions: Vector[SyncAction],
      anki: Anki[F],
      shapes: Map[String, NoteTypeShape],
  )(using F: MonadError[F, AnkiError]): F[Vector[ExecutionFailure]] =
    actions
      .traverse(action =>
        runOne(action, anki, shapes).attempt.map(_.left.toOption.map(ExecutionFailure(action, _)))
      )
      .map(_.flatten)

  private def runOne[F[_]](
      action: SyncAction,
      anki: Anki[F],
      shapes: Map[String, NoteTypeShape],
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

      case SyncAction.Retype(key, noteId, from, to, fields, ownedTags, preservedTags) =>
        // ONE WRITE, carrying the whole field set and the whole tag set, because Anki blanks
        // and replaces both — see `anki/Anki.scala`'s `changeNoteType`. It is therefore
        // atomic in a way an Update is not: there is no window in which the note holds new
        // content under a stale hash, so none of the fields-first-hash-last ordering that
        // governs `Change.FieldsChanged` applies here.
        val what = s"move '${key.path.render}' from note type '$from' to '$to'"
        (shapes.get(from), shapes.get(to)) match
          case (Some(fromShape), Some(toShape)) =>
            Retyping.refusalFor(from, fromShape, to, toShape) match
              // REFUSED PER NOTE, LOUDLY, and reported as a failure rather than as a deferral:
              // a deferral is this tool declining to act on instruction, whereas this is it
              // declining to act on evidence, and the person needs to see the difference.
              case Some(refusal) =>
                F.raiseError(
                  AnkiError.UnsupportedOperation(what, s"${refusal.describe} — ${refusal.remedy}")
                )
              case None =>
                anki.changeNoteType(noteId, to, fields, ownedTags, preservedTags)

          // NOT REACHABLE from `run`, which reads the shape of every note type the plan names
          // before it applies anything. Raising rather than defaulting is still the right
          // shape: any default here is a decision about somebody's review history taken by a
          // branch nobody meant to write.
          case _ =>
            F.raiseError(
              AnkiError.UnsupportedOperation(
                what,
                "the shape of one of those note types was never read, so this move could not " +
                  "be checked for safety",
              )
            )

      case SyncAction.Flag(key, noteId) =>
        anki.addTags(Vector(noteId), Vector(OwnedTag.orphaned(key)))

      case SyncAction.Unflag(key, noteId) =>
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
