package obsidiananki.plan

import cats.MonadError
import cats.syntax.all.*
import obsidiananki.anki.*
import obsidiananki.model.{OwnedTag, TagCodec}

// Reading Anki's current state, and carrying out a plan. Both are written against the
// algebra rather than a concrete client, so the same code runs against the in-memory
// interpreter and, later, against AnkiConnect — which is what makes "a second run changes
// nothing" provable as a law without a live collection.

/** An action that could not be carried out, with the reason. */
final case class ExecutionFailure(action: SyncAction, error: AnkiError)

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
    yield ObservedState(
      notes.zip(decks).flatMap { (note, deck) =>
        note.tags
          .find(_.toLowerCase(java.util.Locale.ROOT).startsWith(s"${OwnedTag.SrcPrefix}::"))
          .flatMap(TagCodec.decode(_).toOption)
          .map(key => ObservedCard(key, note, deck))
      }
    )

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

  /** Carry out a plan, returning every action that FAILED rather than stopping at the first.
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
    */
  def run[F[_]](plan: Plan, anki: Anki[F])(using
      F: MonadError[F, AnkiError]
  ): F[Vector[ExecutionFailure]] =
    plan.actions
      .traverse(action => runOne(action, anki).attempt.map(_.left.toOption.map(ExecutionFailure(action, _))))
      .map(_.flatten)

  private def runOne[F[_]](action: SyncAction, anki: Anki[F])(using
      F: MonadError[F, AnkiError]
  ): F[Unit] =
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

      case SyncAction.Retype(key, _, from, to) =>
        // FAILS LOUDLY, deliberately. Whether AnkiConnect can change a note's type while
        // preserving scheduling is unverified, so this cannot be carried out yet — but a
        // silent no-op would report "nothing to do" while the reverse card the author asked
        // for never appeared, which is precisely what Retype exists to prevent.
        F.raiseError(
          AnkiError.UnsupportedOperation(
            s"retype '${key.path.render}' from '$from' to '$to'",
            "changing a note type is not implemented; verify whether AnkiConnect can do it "
              + "while preserving scheduling, otherwise it must be confirmed by a human",
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
