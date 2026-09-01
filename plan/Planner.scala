package obsidiananki.plan

import cats.data.NonEmptyVector
import obsidiananki.anki.{AnkiNoteId, DeckPath, NewNote, ObservedNote}
import obsidiananki.model.{CardKey, CardPath, CardSpec, Marker, OwnedTag, TagCodec, VaultTag}

/** Why ONE note could not be placed. A fact about the note, carrying no advice.
  *
  * SEPARATE FROM [[PlanError]] ON PURPOSE. Observation knows what a note looks like and knows
  * nothing about the vault; only the planner holds both sides, and only the planner can say
  * "this looks like the card at …". Building the finished error here would either mean the
  * observer guessing without evidence, or the planner rewriting a message it did not compose.
  */
enum IdentityProblem:
  case Ambiguous(tags: NonEmptyVector[String])
  case Unreadable(tag: String, reason: String)

/** A note the identity search found and nothing could place.
  *
  * `recordedSha` is carried because it is the strongest evidence available for what this note
  * USED to be: the content hash the last successful sync wrote. It is a fact about the note, so
  * it is gathered here; what it is worth is decided by the planner, which can compare it against
  * the vault.
  */
final case class UnplaceableNote(
    id: AnkiNoteId,
    problem: IdentityProblem,
    recordedSha: Option[String],
)

/** What Anki currently holds, as the planner needs to see it.
  *
  * Assembled from ONE bulk query over the `src::` tag prefix plus one `notesInfo`, not one
  * lookup per card: a per-card lookup driven by markdown keys can never find an orphan,
  * because an orphan is a key the markdown does not have.
  */
final case class ObservedState(
    notes: Vector[ObservedCard],
    unresolved: Vector[UnplaceableNote] = Vector.empty,
):

  /** CONSERVATION: every note the `src::` query returned is in EXACTLY ONE of `notes` and
    * `unresolved`. [[Observer.observe]] partitions rather than filters, so this holds by
    * construction rather than by discipline.
    *
    * THIS FIELD EXISTS BECAUSE THERE WAS NOWHERE TO PUT SUCH A NOTE BEFORE, and one whose
    * identity tag could not be read was therefore DISCARDED between the query and the plan:
    * found by Anki, dropped on the floor, and thereafter never updated, never flagged, never
    * prunable — while the tool, believing no note held that key, created a second one. That is
    * precisely the damage the `byKey` docstring below condemns, arriving from a third direction
    * and reported by nothing.
    *
    * A NOTE HERE IS NOT AN ORPHAN, and must never be filed as one. An orphan is a note whose
    * identity WAS read and whose key is absent from the markdown. These are notes whose identity
    * could not be read at all, so nothing is known about where they belong — including whether
    * they belong anywhere. The remedy is a person's, which is why they travel as [[PlanError]]
    * rather than as actions.
    */
  def isFullyResolved: Boolean = unresolved.isEmpty

  /** The notes this tool has ALREADY parked as orphaned, as observed BEFORE this run.
    *
    * THE ONE DEFINITION OF "PARKED", so nothing can hold a second, disagreeing one. It is a
    * fact about the collection rather than about the plan, which is why it lives here and is
    * DERIVED rather than stored: a stored copy could disagree with the tags it claims to
    * count, and this cannot.
    *
    * IT IS AVAILABLE EVEN WHEN ORPHANS CANNOT BE COMPUTED, and that is the point rather than an
    * accident. Inferring a NEW orphan needs a complete scan — a key absent from the markdown is
    * only evidence of deletion if the markdown was read in full. Reading a tag off a note the
    * collection already returned needs nothing. So a partial scan still knows exactly what is
    * parked, and a run that says "orphans NOT computed" can still say how many are waiting.
    *
    * WHY THIS EXISTS AT ALL. A parked note produces no action on any later run — it is already
    * flagged, so the planner skips it — and the report names orphans only as WORK. So the run
    * that parks a note mentions it once and every run afterwards is silent, while the note sits
    * suspended out of review indefinitely. Six were sitting in a real collection, unmentioned by
    * a run that printed "nothing to do", when this was written.
    */
  def parkedOrphans: Vector[ObservedCard] = notes.filter(_.isFlaggedOrphan)

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
    *
    * THE IDENTITY FIELD IS EXCLUDED, AND THAT IS LOAD-BEARING RATHER THAN TIDY. Since
    * 2026-08-28 a card's identity is a field, so it would otherwise be hashed along with the
    * content — and every card's identity is different by construction, so no two cards could
    * ever hash alike. That would silently destroy the recovery this hash exists to enable:
    * `byRecordedHash` below finds the vault card matching a note WHOSE IDENTITY IS UNREADABLE,
    * which is precisely the case where the identity cannot contribute to the comparison.
    *
    * A CONTENT HASH HASHES CONTENT. Anything that identifies a card rather than describing it
    * belongs outside.
    */
  def contentHash(spec: CardSpec): String =
    // Fields are joined with a unit-separator control character, which cannot occur in
    // field content. Plain concatenation would let ("ab","c") and ("a","bc") hash alike.
    val sep = "\u001f"
    val content = spec.fields.filterNot((name, _) => name == Marker.IdentityField)
    val parts   = spec.noteTypeName +: content.flatMap { case (n, v) => Vector(n, v) }
    val canonical = parts.mkString(sep)
    val digest = java.security.MessageDigest
      .getInstance("SHA-256")
      .digest(canonical.getBytes(java.nio.charset.StandardCharsets.UTF_8))
    digest.take(8).map("%02x".format(_)).mkString

  /** Build the note a `Create` will write.
    *
    * IDENTITY AND HASH ARE PRESENT FROM THE MOMENT THE NOTE EXISTS, which is why this is a
    * function rather than a create-then-tag sequence. A note created without its identity is not
    * merely unmatched but UNENUMERABLE — invisible to the key lookup and to the reconciler,
    * permanently, with no later call able to find it and repair it. Since 2026-08-29 that
    * identity travels in a FIELD, written by the same `addNote` call, so what the non-empty tag
    * vector now carries is the content hash.
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
      // NO IDENTITY TAG SINCE 2026-08-29. A note this tool creates is always on a note type
      // it owns, so it always has an `Identity` field to hold its identity — and writing the tag
      // as well would put a machine's ledger back into the author's own tag tree, which is the
      // whole reason the identity moved. The content hash stays a tag: it is not an identity,
      // and it is read by a search that has no other home.
      tags = NonEmptyVector.one(OwnedTag.sha(sha)) ++ carried(sourced.vaultTags),
    )

  /** The author's own tags, as tags to write. Everything else this note's frontmatter said is
    * not a tag Anki can hold, and is the report's business rather than this function's.
    *
    * MATCHED EXHAUSTIVELY RATHER THAN COLLECTED, so an outcome added to [[VaultTag]] later has
    * to say whether it is written. A partial function would skip it silently, which is how a
    * tag the author wrote comes to do nothing at all.
    */
  private def carried(read: Vector[VaultTag]): Vector[OwnedTag] =
    read.flatMap {
      case VaultTag.Carried(t)     => Vector(t)
      case VaultTag.Unusable(_, _) => Vector.empty
    }

  /** Reject a key derived by more than one source, before anything is written.
    *
    * The id half of the key is validated at construction and the path half by the encoding,
    * but neither prevents two DIFFERENT sources arriving at the same key — two identical
    * sibling headings under one ancestor, two table rows with the same first cell, or a
    * table cell that happens to coincide with a nested heading.
    */
  /** Turn "this note could not be placed" into a message a person can act on, by asking the
    * vault what the note most likely is.
    *
    * ═══ THE SUGGESTION IS EVIDENCE, NOT A GUESS ═══
    *
    * `sha::` is a hash of the note type and every field value, written by the last successful
    * sync ([[contentHash]]). So a note whose identity is unreadable but whose hash still matches
    * a card the vault produces today is not being GUESSED at — sixty-four bits agree. The only
    * way to be wrong is for two different cards to hash identically, which is the same
    * assumption "nothing to do" already rests on for every note in the collection.
    *
    * IT WORKS ONLY WHILE THE CONTENT IS UNCHANGED. Edit the body after breaking the tag and the
    * hash no longer matches, and this correctly offers nothing rather than something. Matching
    * on similar CONTENT is the designed next tier and is not built; when it is, it must stay on
    * the suggest side of the line, because a similarity score is exactly the guess this is not.
    *
    * ═══ WHY IT NEVER APPLIES ITSELF ═══
    *
    * A wrong rebind moves review history onto the wrong card, silently and irreversibly. So this
    * only ever NAMES the card and prints the tag that would bind it; a person types nothing and
    * decides everything. Printing the tag is the point — the encoding escapes spaces, slashes,
    * colons and Anki's two wildcard characters, so a correct tag cannot be written out by hand.
    *
    * AMBIGUITY IS ANSWERED FROM THE VAULT FIRST. When a note carries several identity tags, the
    * useful question is which of them the vault still has a card for — a stronger signal than
    * the hash, and it needs no hash at all. The hash is the fallback for when that is still
    * undecided, which is why the two are tried in this order.
    */
  private[plan] def identityErrorFor(
      note: UnplaceableNote,
      specs: Vector[SourcedSpec],
  ): PlanError =
    def byRecordedHash: Option[CardKey] =
      note.recordedSha.flatMap { sha =>
        specs.filter(s => contentHash(s.spec) == sha) match
          case Vector(only) => Some(only.key)
          case _            => None // none matched, or several did: say nothing rather than pick
      }

    note.problem match
      case IdentityProblem.Unreadable(tag, reason) =>
        PlanError.UnreadableIdentityInAnki(note.id, tag, reason, byRecordedHash)

      case IdentityProblem.Ambiguous(tags) =>
        val live = specs.map(_.key).toSet
        val claimed = tags.toVector.flatMap(TagCodec.decode(_).toOption).filter(live.contains).distinct
        val suggestion = claimed match
          case Vector(only) => Some(only)
          case _            => byRecordedHash
        PlanError.AmbiguousIdentityInAnki(note.id, tags, suggestion)

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

    // NOTES THAT COULD NOT BE PLACED AT ALL, reported ALONGSIDE the other two rather than
    // instead of them, so one run tells the author everything. Planning past them is not an
    // option: a note whose identity cannot be read is a note whose key looks unclaimed, so the
    // plan would create a duplicate for a card that already exists and already has history.
    //
    // ORDERED FIRST because the remedy is the most mechanical — open the note, fix one tag —
    // and because the other two errors may simply disappear once it is done.
    val unplaceable = observed.unresolved.map(identityErrorFor(_, scan.specs))

    observed.byKey match
      case _ if unplaceable.nonEmpty       => Left(unplaceable ++ duplicates)
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
              // The note is on the wrong note type. NOT an Update: an ordinary field write
              // succeeds against a same-shaped note type and the requested card never
              // appears — silent success, which is the failure this case exists to prevent.
              //
              // EVERYTHING THE NOTE MUST STILL HOLD AFTERWARDS IS GATHERED HERE, because the
              // operation that carries this out blanks the fields and replaces the tags
              // wholesale. Nothing extra is read to do it: the fields come from the spec that
              // is already in hand, and the foreign tags from the observation already made.
              //
              // THE OWNED TAGS ARE REBUILT RATHER THAN CARRIED OVER, which stops a stale hash
              // surviving the move: the note ends up with exactly the identity tag and the hash
              // of the content being written, and any `sha::` or `orphaned::` it held before is
              // gone by the same write. The key is present in the markdown — that is why this
              // branch was reached at all — so an `orphaned::` tag on it was stale.
              //
              // ⚠️ THAT IS TRUE OF THE TAG AND WAS NOT ENOUGH. This comment used to end by
              // concluding "which is what makes a separate Unflag unnecessary here", and that
              // conclusion STRANDED CARDS for as long as it stood. `Unflag` does TWO things —
              // it unsuspends every card of the note and THEN clears the tag — and only the
              // second has another home. A note that was flagged and comes back on a different
              // note type had its tag rebuilt away and was never unsuspended: correctly keyed,
              // correctly typed, in the right deck, carrying its whole review history, and
              // suspended forever. Nothing reported it, because every report counts notes
              // CARRYING the tag and this one no longer did; and no later run repaired it,
              // because content and deck then matched so nothing was planned. It is precisely
              // the state `plan/Executor.scala`'s `Unflag` ordering exists to prevent — "an
              // untagged live card is indistinguishable from a healthy one" — arriving through
              // the sibling branch. Fixed 2026-08-25 by emitting the `Unflag` this branch had
              // argued itself out of.
              // DECIDED ABOVE THE BRANCH, ON PURPOSE. This used to be computed only in the
              // `else`, so a note that changed BOTH its note type and its folder had its deck
              // silently left behind: the run reported itself clean, and the NEXT run moved the
              // deck unasked — breaking "a second run changes nothing" rather than hiding
              // behind it. Both branches now answer the same question before choosing.
              val deckDiffers = !existing.deck.contains(deck)

              if existing.note.noteType != sourced.spec.noteTypeName then
                // UNFLAG FIRST, RETYPE SECOND, and the order is the pessimistic one this file
                // and the executor both use: interrupted between the two, the note is back in
                // the queue on its OLD note type — visible, studiable, and fully repairable by
                // the next run, which will see the note types still disagree and plan the move
                // again. The reverse order would leave the retype done and the card suspended,
                // which is the stranded state itself.
                //
                // ONLY WHEN THE NOTE IS ACTUALLY FLAGGED. Unsuspending unconditionally would
                // override a suspension somebody made by hand in Anki, and the settled ruling is
                // that this tool cannot tell its own suspension from a person's. The tag is the
                // only evidence that the suspension was ours.
                Option
                  .when(existing.isFlaggedOrphan)(SyncAction.Unflag(key, existing.note.id))
                  .toVector ++
                Vector(
                  SyncAction.Retype(
                    key = key,
                    noteId = existing.note.id,
                    from = existing.note.noteType,
                    to = sourced.spec.noteTypeName,
                    fields = sourced.spec.fields,
                    // THE IDENTITY IS IN THE FIELDS THIS MOVE WRITES, not in a tag — see the
                    // note at the create above. A retype replaces the note's whole tag set, so
                    // naming the identity tag here would reinstate it on every moved note.
                    ownedTags = NonEmptyVector.one(OwnedTag.sha(sha)),
                    preservedTags = existing.note.tags.filterNot(OwnedTag.isOwned),
                    // TRAVELS WITH THE MOVE, so one action is still one whole note. Emitting a
                    // companion `Update` instead would be smaller here and wrong there: the
                    // executor sets retypes aside BY TYPE when a run is not asked to make them,
                    // and could not know a sibling Update belonged to a deferred note — so a
                    // deferred run would move the deck of a note whose fields it did not write.
                    deck = Option.when(deckDiffers)(deck),
                  )
                )
              else
                // AN UNCHANGED HASH IS NOT ENOUGH ON ITS OWN, since 2026-08-28. The identity
                // moved from a tag into a field and is deliberately NOT hashed (see
                // [[contentHash]]), so a note written before the move has identical content and
                // an identical hash while its identity field is still empty. Judging on the
                // hash alone would skip it forever and the field would never arrive.
                //
                // THIS IS THE WHOLE MIGRATION, and it needs no command and no separate pass: a
                // note lacking the field is written once, on whatever run next reaches it, and
                // the condition is false ever after. Notes that already carry it are untouched,
                // so a collection converges and then stays quiet.
                val identityMissing = !existing.note.fields.exists((name, value) =>
                  name == Marker.IdentityField && value.nonEmpty
                )
                val fieldsDiffer = identityMissing || !existing.recordedSha.contains(sha)

                // ── THE AUTHOR'S OWN TAGS, COMPARED DIRECTLY ────────────────────────
                //
                // NOT THROUGH THE CONTENT HASH, and that is a choice about honesty rather than
                // about cost. The hash answers "is there anything to write" and it hashes
                // CONTENT; putting tags into it would make re-tagging a note report itself as a
                // field change and rewrite every field to say so. Comparing here costs nothing:
                // the note's current tags are already in hand.
                //
                // CASE-FOLDED ON BOTH SIDES, because Anki folds tag case — so `Backend` and
                // `backend` are ONE tag in the collection, and comparing them case-sensitively
                // would report a difference on every run that no write could ever settle.
                //
                // ONLY UNDER THE VAULT PREFIX. `leech` and `marked`, which Anki writes by
                // itself onto these notes, are not in either set and so can never be a
                // difference to act on.
                val fold        = (t: String) => t.toLowerCase(java.util.Locale.ROOT)
                val desiredTags = carried(sourced.vaultTags)
                val tagsDiffer =
                  existing.note.tags.filter(t => fold(t).startsWith(s"${VaultTag.Prefix}::")).map(fold).toSet !=
                    desiredTags.map(t => fold(t.value)).toSet

                val changes = Vector(
                  Option.when(fieldsDiffer)(Change.FieldsChanged(sourced.spec.fields, sha)),
                  Option.when(tagsDiffer)(Change.TagsChanged(desiredTags)),
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
        //
        // ⚠️ IT ALSO ASSUMES THE COLLECTION HOLDS ONE VAULT, and nothing checks that.
        // `observed.notes` is every note this tool created in the open collection, from any
        // vault — see `Observer.observe`, which cannot filter by a vault no tag records. Sync a
        // second vault into the same profile and every card the first one owns is absent from
        // this scan, so every one of them is flagged and suspended below. `README.md`, "ONE
        // VAULT PER ANKI PROFILE", carries the argument.
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
            //
            // ═══ AND EVERYTHING BENEATH A FAILED KEY IS ALSO ACCOUNTED FOR ═══
            //
            // The rule above was right and the check was too narrow: it compared keys for
            // EQUALITY, while a failure is recorded at the key of the SECTION that failed
            // (`extract/Extractor.scala`, the `KeyKnown` in the marked-heading branch) and a
            // table's cards are keyed one or two segments DEEPER — `…/cost / benefit` fails,
            // while its cards are `…/cost / benefit/queue/benefit`. Those deeper keys matched
            // nothing in `accountedFor`, so every card the section had ever produced was read
            // as deleted.
            //
            // MEASURED, not reasoned about: pasting an image into ONE cell of one table in the
            // fixture vault flagged 15 live cards, and — since orphan suspension landed — also
            // SUSPENDED them, taking them out of review with their history intact but invisible.
            // The run reported "1 card could not be built" and said nothing about the fifteen.
            //
            // THE SAFE DIRECTION OF ERROR, stated because this suppresses as well as protects: a
            // card genuinely deleted from a table WHOSE SECTION ALSO FAILS is now not flagged
            // this run. That is correct rather than merely convenient — while the section is
            // broken the tool cannot tell a deleted row from one it failed to read — and it
            // converges: the moment the section builds again, a genuinely absent card is flagged
            // as it always was.
            val failed      = scan.failedKeys
            val suppressed  = scan.suppressedNoteIds
            val builtKeys   = scan.builtKeys

            // SHELTERING IS A HEADING-PATH RELATION, and the other kinds are not "not yet
            // handled" — they are outside the relation entirely.
            //
            // The rule protects cards whose key extends the key of a section that failed to
            // build, because a broken section must not read as a deleted one. A frontmatter
            // property is not inside any section: it is read from the note's frontmatter, which
            // parses independently of whether the body's markdown does. A section failing says
            // nothing about it, so sheltering it would hide a genuinely deleted property. The
            // same holds for the note-itself card.
            def underAFailedSection(card: CardKey): Boolean =
              (card.path match
                case CardPath.Headings(cardPath) =>
                  failed.exists { f =>
                    f.noteId == card.noteId && (f.path match
                      case CardPath.Headings(failedPath) =>
                        failedPath.segments.length < cardPath.segments.length &&
                          cardPath.segments.toVector.startsWith(failedPath.segments.toVector)
                      case CardPath.Property(_) | CardPath.Note | CardPath.Block(_) => false)
                  }

                // ── A BLOCK CARD IS OUTSIDE THE RELATION, AND THAT IS A COST RATHER THAN A
                // TIDY FIT. A property and the note-itself card genuinely sit outside any
                // section. A BLOCK does not — it is inside one — but its key deliberately
                // records no heading chain, because carrying one would make moving a paragraph
                // between headings re-key its card, which is the fragility the `^blockid`
                // anchor exists to remove.
                //
                // SO THE RELATION CANNOT BE COMPUTED FOR IT, and a block card inside a section
                // that failed to build reads as deleted rather than as sheltered. That is a
                // real gap, not a case awaiting an implementation, and it is the honest price
                // of a location-independent identity. Filed rather than papered over.
                case CardPath.Property(_) | CardPath.Note | CardPath.Block(_) => false)

            val orphans = observed.notes.filter { card =>
              !builtKeys.contains(card.key) &&
              !failed.contains(card.key) &&
              !underAFailedSection(card.key) &&
              !suppressed.contains(card.key.noteId) &&
              !card.isFlaggedOrphan
            }
            (orphans.map(c => SyncAction.Flag(c.key, c.note.id)), OrphanInference.Computed)

        // ── BACKFILLING AN IDENTITY THE UPDATE PATH CANNOT REACH ────────────────────────
        //
        // WHY A SEPARATE PASS RATHER THAN A CONDITION ON AN UPDATE. The identity moved into a
        // field on 2026-08-28 and the migration was written as exactly that condition: a note
        // lacking the field is written on whatever run next updates it. Marc's first real sync
        // showed what it missed — 56 of 67 owned notes gained the field and ELEVEN did not, and
        // the eleven were precisely his orphans, holding 20 cards and 32 reviews.
        //
        // AN ORPHAN IS NEVER UPDATED. It is flagged and suspended once and then left alone, and
        // a note already flagged is not flagged again — so no number of runs would have reached
        // it. Removing the tag at that point leaves those notes with no identity at all:
        // unfindable by this tool, unfindable by `prune`, and their review history unreachable,
        // with nothing reporting it.
        //
        // IT DOES NOT DEPEND ON THE SCAN BEING COMPLETE, which is why it sits outside the
        // orphan inference above. Whether a key is absent from the markdown is a question about
        // the vault and needs a whole one; whether a note Anki holds is missing a field is a
        // question about that note alone, and the observation already answered it.
        //
        // NOTES BEING UPDATED ANYWAY ARE EXCLUDED, so this never duplicates work: an update
        // writes every field including the identity, and the update path already forces one for
        // a live note that lacks it.
        val updatedKeys = perSpec.map(_.cardKey).toSet
        // WHICH NOTES CAN HOLD THE FIELD AT ALL, WHICH IS NOT ALL OF THEM. A note on a note
        // type this tool does not own has no `Identity` field, and this tool is ruled never to
        // write to a note type it did not create — so its identity stays in its tag,
        // permanently, and it must be left alone rather than repeatedly failed against.
        //
        // TOLD APART BY THE FIELD NAME'S PRESENCE, not by asking Anki a second time: a note's
        // fields ARE its note type's list, so if the name is absent the type does not declare it.
        def canHoldTheField(card: ObservedCard): Boolean =
          card.note.fields.exists((name, _) => name == Marker.IdentityField)

        def fieldIsEmpty(card: ObservedCard): Boolean =
          !card.note.fields.exists((name, value) => name == Marker.IdentityField && value.nonEmpty)

        def legacyTagsOn(card: ObservedCard): Vector[OwnedTag] =
          card.note.tags
            .filter(_.toLowerCase(java.util.Locale.ROOT).startsWith(s"${OwnedTag.SrcPrefix}::"))
            .map(OwnedTag.unsafeFromString)

        val backfill = observed.notes.collect {
          case card
              if !updatedKeys.contains(card.key) && canHoldTheField(card) &&
                (fieldIsEmpty(card) || legacyTagsOn(card).nonEmpty) =>
            SyncAction.CarryIdentity(
              card.key,
              card.note.id,
              // THE NOTE'S OWN FIELDS, WITH THE IDENTITY SET. Named in full rather than alone
              // because `updateNoteFields`' behaviour on a subset is unverified here, and a
              // wrong guess would blank the rest of an orphan's content.
              card.note.fields.filterNot(_._1 == Marker.IdentityField) :+
                (Marker.IdentityField -> TagCodec.encode(card.key).value),
              // THE TAG GOES WHEN THE FIELD ARRIVES, in one action, so that no note is ever left
              // holding the identity in a place nothing writes. Removing it is the entire point
              // of the move: a machine's ledger stops filling the author's own tag tree.
              legacyTagsOn(card),
            )
        }

        Right(
          Plan(
            perSpec ++ orphanActions ++ backfill,
            inference,
            scan.failures,
            observed.parkedOrphans.map(_.key),
          )
        )
