package obsidiananki.plan

import cats.data.NonEmptyVector
import obsidiananki.anki.{DeckPath, NewNote, ObservedNote}
import obsidiananki.model.{CardKey, CardSpec, OwnedTag, TagCodec}

/** What Anki currently holds, as the planner needs to see it.
  *
  * Assembled from ONE bulk query over the `src::` tag prefix plus one `notesInfo`, not one
  * lookup per card: a per-card lookup driven by markdown keys can never find an orphan,
  * because an orphan is a key the markdown does not have.
  */
final case class ObservedState(notes: Vector[ObservedCard]):
  /** The lookup from card identity to the note holding it — OR the collisions that make such
    * a lookup a lie.
    *
    * IT RETURNS AN EITHER SO THAT COLLISIONS CANNOT BE SKIPPED. The obvious `.toMap` silently
    * keeps one of two notes claiming the same identity, and the loser then disappears from
    * the reconciler completely: never updated, so it holds its old content forever; never
    * flagged, because a card is only an orphan when its key is ABSENT from the markdown and
    * the winner's key is present; and therefore never prunable either. It goes on appearing
    * in reviews, diverging, with nothing anywhere saying so.
    *
    * This is the same failure the markdown side already treats as fatal, arriving from the
    * other direction — and it is reachable: `allowDuplicate` is set deliberately, so Anki
    * will not refuse the second note, and a retried creation after an interrupted run, a
    * restored backup, or a card duplicated by hand all produce it.
    */
  def byKey: Either[Vector[PlanError], Map[CardKey, ObservedCard]] =
    val collisions = notes
      .groupBy(_.key)
      .toVector
      .sortBy(_._1.path.render)
      .flatMap { (key, group) =>
        // Ordered by note id so the report is stable run to run, and both sides are named:
        // "there is a duplicate somewhere" cannot be acted on without opening every note.
        group.sortBy(_.note.id.value) match
          case first +: rest =>
            rest.map(other => PlanError.DuplicateIdentityInAnki(key, first.note.id, other.note.id))
          case _ => Vector.empty
      }
    if collisions.nonEmpty then Left(collisions) else Right(notes.map(n => n.key -> n).toMap)

/** One Anki note, resolved against its identity tag. */
final case class ObservedCard(
    key: CardKey,
    note: ObservedNote,
    deck: Option[DeckPath],
):
  /** The content hash recorded on the note by the last successful sync, if any.
    *
    * NONE WHEN THE NOTE CARRIES MORE THAN ONE, which is the whole reason this is not a
    * `find`. Two hashes make "has this changed?" unanswerable, and picking whichever came
    * first would answer it anyway — landing on "unchanged" half the time and skipping a
    * note that needs writing. Reporting no recorded hash makes the planner treat the note
    * as changed, so the next run rewrites it and `Executor` clears the stale tag: the state
    * CONVERGES rather than being prevented, which is the only option available given it can
    * arise from an interrupted tag write.
    */
  def recordedSha: Option[String] =
    note.tags
      .filter(_.toLowerCase(java.util.Locale.ROOT).startsWith(s"${OwnedTag.ShaPrefix}::")) match
      case Vector(only) => Some(only.drop(OwnedTag.ShaPrefix.length + 2).toLowerCase(java.util.Locale.ROOT))
      case _            => None

  def isFlaggedOrphan: Boolean =
    note.tags.exists(_.toLowerCase(java.util.Locale.ROOT).startsWith(s"${OwnedTag.OrphanedPrefix}::"))

object Planner:

  /** The content hash that decides "nothing to do" BEFORE any call is made.
    *
    * Necessary because `updateNoteFields` has no early-out: it moves the note's
    * modification stamp even when the text is identical, so "a second run changes nothing"
    * cannot be delegated to Anki. Hashes the note type together with the fields IN ORDER,
    * so a field reordering or a note-type change is visible as a difference.
    */
  def contentHash(spec: CardSpec): String =
    // Fields are joined with a unit-separator control character, which cannot occur in
    // field content. Plain concatenation would let ("ab","c") and ("a","bc") hash alike.
    val sep = "\u001f"
    val parts = spec.noteTypeName +: spec.fields.flatMap { case (n, v) => Vector(n, v) }
    val canonical = parts.mkString(sep)
    val digest = java.security.MessageDigest
      .getInstance("SHA-256")
      .digest(canonical.getBytes(java.nio.charset.StandardCharsets.UTF_8))
    digest.take(8).map("%02x".format(_)).mkString

  /** Build the note a `Create` will write.
    *
    * BOTH OWNED TAGS ARE PRESENT FROM THE MOMENT THE NOTE EXISTS, which is why this is a
    * function rather than a create-then-tag sequence. `NewNote` requires a non-empty tag
    * vector precisely so that a note without its `src::` identity cannot be constructed: such
    * a note is not merely unmatched but UNENUMERABLE — invisible to the key lookup, the
    * reconciler and prune, permanently, with no later call able to find it and repair it.
    *
    * The `sha::` hash goes on at creation for the same reason it is written last on update:
    * a note whose content is written without its hash would be rewritten on the next run,
    * which is wasteful but safe, whereas the reverse is not.
    *
    * `plan` still takes this as a parameter rather than calling it directly, so a test can
    * substitute one — but this is the definition the tool actually uses, and it lives here
    * rather than in the shell because it is a decision about identity, not about wiring.
    */
  def newNoteFor(sourced: SourcedSpec, deck: DeckPath, sha: String): NewNote =
    NewNote(
      noteType = sourced.spec.noteTypeName,
      deck = deck,
      fields = sourced.spec.fields,
      tags = NonEmptyVector.of(TagCodec.encode(sourced.key), OwnedTag.sha(sha)),
    )

  /** Reject a key derived by more than one source, before anything is written.
    *
    * The id half of the key is validated at construction and the path half by the encoding,
    * but neither prevents two DIFFERENT sources arriving at the same key — two identical
    * sibling headings under one ancestor, two table rows with the same first cell, or a
    * table cell that happens to coincide with a nested heading.
    */
  def checkUnique(specs: Vector[SourcedSpec]): Vector[PlanError] =
    specs
      .groupBy(_.key)
      .toVector
      .sortBy(_._1.path.render)
      .flatMap { (key, group) =>
        group.sortBy(s => (s.source.file, s.source.line)) match
          case first +: rest => rest.map(other => PlanError.DuplicateKey(key, first.source, other.source))
          case _             => Vector.empty
      }

  /** Compare what the markdown says with what Anki holds.
    *
    * Fails with every duplicate-key error at once rather than the first, so one run tells
    * the author everything that needs fixing.
    */
  def plan(
      scan: VaultScan,
      observed: ObservedState,
      deckOf: CardKey => DeckPath,
      newNoteOf: (SourcedSpec, DeckPath, String) => NewNote,
  ): Either[Vector[PlanError], Plan] =
    // BOTH SIDES ARE CHECKED BEFORE EITHER IS REPORTED, so one run tells the author
    // everything that needs fixing rather than revealing the Anki-side collision only after
    // the markdown-side one has been dealt with.
    //
    // MATCHED RATHER THAN UNWRAPPED WITH A DEFAULT, which matters more than it looks. Reading
    // the map out with `getOrElse(Map.empty)` under a comment saying the guard above makes it
    // safe would leave that safety resting on a sentence: an early return added later, or the
    // guard reordered, and the default yields an EMPTY observed collection instead of failing.
    // Every key would then miss, every card would become a Create, and the next sync would
    // duplicate the entire collection — the worst outcome this design can reach. Matching
    // makes the impossible case impossible to write rather than merely commented against.
    val duplicates = checkUnique(scan.specs)

    observed.byKey match
      case Left(collisions)                => Left(duplicates ++ collisions)
      case Right(_) if duplicates.nonEmpty => Left(duplicates)
      case Right(byKey) =>
        val perSpec = scan.specs.flatMap { sourced =>
          val key  = sourced.key
          val sha  = contentHash(sourced.spec)
          val deck = deckOf(key)

          byKey.get(key) match
            case None =>
              Vector(SyncAction.Create(key, newNoteOf(sourced, deck, sha)))

            case Some(existing) =>
              // The marker changed the note type. NOT an Update: an ordinary field write
              // succeeds against a same-shaped note type and the requested card never
              // appears — silent success, which is the failure this case exists to prevent.
              if existing.note.noteType != sourced.spec.noteTypeName then
                Vector(
                  SyncAction.Retype(key, existing.note.id, existing.note.noteType, sourced.spec.noteTypeName)
                )
              else
                val fieldsDiffer = !existing.recordedSha.contains(sha)
                val deckDiffers  = !existing.deck.contains(deck)

                val changes = Vector(
                  Option.when(fieldsDiffer)(Change.FieldsChanged(sourced.spec.fields, sha)),
                  Option.when(deckDiffers)(Change.DeckChanged(existing.deck, deck)),
                ).flatten

                // A key that is present again must have any stale orphan flag cleared, or the
                // flag set only grows and the prune list a human reviews becomes untrustworthy.
                val unflag =
                  Option.when(existing.isFlaggedOrphan)(SyncAction.Unflag(key, existing.note.id))

                val update = NonEmptyVector
                  .fromVector(changes)
                  .map(SyncAction.Update(key, existing.note.id, _))

                unflag.toVector ++ update.toVector
        }

        // ORPHANS. "Present in Anki, absent from markdown" is sound only if the markdown side
        // was seen in full AND every key it owns is accounted for.
        val (orphanActions, inference) =
          if !scan.canInferOrphans then
            (
              Vector.empty,
              OrphanInference.SuppressedIncompleteScan(
                "at least one file could not be read, so absence from the markdown proves nothing"
              ),
            )
          else
            // Built AND failed-but-keyed. A card that merely failed to build is not absent
            // from the markdown — it is present and broken, and flagging it would send a live
            // card to the prune list.
            val accountedFor = scan.builtKeys ++ scan.failedKeys
            val suppressed   = scan.suppressedNoteIds
            val orphans = observed.notes.filter { card =>
              !accountedFor.contains(card.key) &&
              !suppressed.contains(card.key.noteId) &&
              !card.isFlaggedOrphan
            }
            (orphans.map(c => SyncAction.Flag(c.key, c.note.id)), OrphanInference.Computed)

        Right(Plan(perSpec ++ orphanActions, inference, scan.failures))
