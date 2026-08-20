package obsidiananki.extract

import cats.data.NonEmptyVector
import obsidiananki.content as C
import obsidiananki.model.*

/** Turning a `#flashcard/cloze` section into one Anki note holding all of its deletions.
  *
  * ONE SECTION IS ONE NOTE. Adding a highlight therefore adds a CARD to an existing note
  * rather than creating a new note, so the note's key never churns.
  *
  * Within that note, deletions are grouped, and the grouping is what makes numbering stable:
  * a labelled group's number IS its label, chosen by the author and immune to text edits.
  */
object Cloze:

  /** Build the cloze note for a section's ALREADY-LOWERED body.
    *
    * IT TAKES BLOCKS, NOT A `Section`, AND IT CANNOT REFUSE FOR CONTENT REASONS. The refusal
    * already happened upstream, in `Extractor.bodyBlocks`, which is the one place a
    * `content.Refusal` becomes a `SpecError`. What used to sit here was a SECOND call to
    * `Extractor.bodyText` whose only purpose was those refusals — and it was dead code even
    * then: `buildSpecs` already called `bodyText(ownBody(section))` over the same blocks with
    * the same function before it ever reached this object, so a cloze section containing an
    * embed already failed with the refusal. Deleting it is unobservable.
    *
    * Numbering:
    *   - a LABELLED group takes its label as its `cN`, so it is stable by construction —
    *     nothing the tool computes can move it.
    *   - an UNLABELLED group takes the lowest number no label has claimed, in order of first
    *     appearance. That is stable only while the set of deletions is stable, which is
    *     precisely the fragility the ruling makes visible: labelling is how you avoid it.
    *
    * WHAT MOVED, RECORDED AS A RULING RATHER THAN DISCOVERED. An unlabelled group's key, and
    * every string in `ClozeDeletion.texts`, used to come from Laika's `Highlighted.extractText`
    * while the rendered body came from a walk beside it. They now both come from the ONE
    * `AsText`-rendered `inner` string: two lattices collapse to one. The evidence they agreed
    * on the fixture is `content/Lower.test.scala`'s cloze differential, which passed green
    * across all five fixture cloze sections immediately before it was deleted in this same
    * slice. `ClozeDeletion.texts` is invisible to `extract/golden/fixture-cards.txt` (which
    * pins the Cloze `Text` field, not the deletion texts); the group key is visible only
    * through `AmbiguousClozeDeletion`'s reason string, which `Extractor.test.scala` now pins.
    */
  def fromLowered(key: CardKey, blocks: Vector[C.Block]): Either[SpecError, CardSpec] =
    val where = key.path.render

    // ── PASS 1: collect the occurrences, discarding the string ──────────────────────
    //
    // DERIVED FROM `text`, NEVER WRITTEN BESIDE IT. This is `content/AsText.scala`'s own
    // instruction, and it is the direct lesson of the drift scar this refactor exists to close:
    // `collectHighlights` and `renderWithDeletions` used to be two walks over one tree with
    // different notions of "container", so a cloze section whose highlight sat inside a table
    // was told it had "no ==highlight==" — a message naming a remedy the author had already
    // applied. Calling the renderer with a collecting hook makes a second walk impossible.
    //
    // The hook returns `inner` unchanged, so this pass renders exactly what `AsText.Plain`
    // would; the returned string is discarded on purpose.
    val found = Vector.newBuilder[(Option[Int], String)]
    val _ = C.AsText.text(blocks, (label, inner) => { found += (label -> inner); inner })
    val occurrences = found.result()

    if occurrences.isEmpty then Left(SpecError.ClozeWithoutDeletions(where))
    else
      // Two unlabelled highlights with the same text are indistinguishable by anything but
      // position, so they are refused with the remedy named rather than tie-broken.
      val unlabelledTexts = occurrences.collect { case (None, text) => text }
      unlabelledTexts.groupBy(identity).collectFirst { case (t, dups) if dups.sizeIs > 1 => t } match
        case Some(duplicate) => Left(SpecError.AmbiguousClozeDeletion(where, duplicate))
        case None =>
          val (numbered, perOccurrence) = number(occurrences)
          for
            deletions <- NonEmptyVector
              .fromVector(numbered)
              .toRight(SpecError.ClozeWithoutDeletions(where))
            body <- Body
              .fromExtracted(render(blocks, perOccurrence, where))
              .toRight(SpecError.EmptyBody(where))
          yield CardSpec.Cloze(key, body, deletions)

  /** The body as Anki must receive it: `{{cN::…}}` where the author wrote a highlight.
    *
    * THIS IS THE STEP THAT WAS ONCE MISSING, and its absence was invisible because everything
    * around it was right. The grouping is ruled on, parsed, numbered and tested; the note
    * type and its field names are correct; the only thing never done was putting the two
    * together. A comment on the field renderer asserted this happened "when the note is
    * rendered", which is why nobody looked — the seventh untrue claim in this project, and
    * the one that hid an actual defect rather than merely overstating a guarantee.
    *
    * It cost two cards on the first live run: Anki REFUSES a Cloze note containing no
    * deletion, with "cannot create note for unknown reason". Nothing caught it earlier
    * because both fakes accept such a note happily — the fake and the code shared the
    * misunderstanding, so the suite was green and wrong together.
    *
    * RENDERED FROM THE STRUCTURE, NOT BY SUBSTITUTING INTO THE EXTRACTED TEXT. By the time the
    * body has been flattened to a string the `==` markers are gone, so a deletion could only
    * be located by matching its text — and a word that also appears elsewhere in the body
    * would be blanked in the wrong place, or in several.
    *
    * ORDINALS ARE CONSUMED POSITIONALLY, NOT BY LOOKING A GROUP UP. Two reasons, both
    * concrete. A keyed lookup can only ever detect a MISSING key and never a SURPLUS one, so
    * it is blind in one direction. And it would have to re-derive `label.map(Labelled)
    * .getOrElse(Unlabelled(inner))` here in pass 2 — which is exactly the hook
    * `content/Lower.test.scala` labelled HARNESS-ONLY precisely so that nobody would lift it
    * into production. Position is also immune to a NESTED deletion, where pass 1's plain
    * `inner` and pass 2's `{{cN::…}}` `inner` are different strings for the same occurrence.
    *
    * `misaligned` IS NOT CLAIMED UNREACHABLE. It is a LOUD, TWO-SIDED ALIGNMENT CHECK on a
    * NAMED INVARIANT of `content.AsText.text`: it fires its `deletion` callback exactly once
    * per `Inline.Deletion`, in document order, and identically for any hook — identically
    * because the block-level `.filter(_.nonEmpty)` runs AFTER `blockText` has returned, so no
    * hook can make a block vanish before its callbacks have fired. Too few ordinals fails
    * INSIDE the render; too many fails after it. That invariant lives in a file this slice may
    * not edit, so it is NAMED here, not assumed to be a type. This project has already shipped
    * one comment asserting a branch was unreachable that was not.
    */
  private def render(blocks: Vector[C.Block], perOccurrence: Vector[Int], where: String): String =
    def misaligned(detail: String): Nothing =
      sys.error(
        s"cloze rendering misaligned at '$where': $detail. " +
          s"`AsText.text` fired a different number of deletion callbacks than the collecting " +
          s"pass over the same blocks did (${perOccurrence.size} ordinals)."
      )

    val remaining = perOccurrence.iterator
    val rendered = C.AsText.text(
      blocks,
      (_, inner) =>
        if remaining.hasNext then s"{{c${remaining.next()}::$inner}}"
        else misaligned("the render asked for more ordinals than were collected"),
    )
    if remaining.hasNext then misaligned("the render used fewer ordinals than were collected")
    rendered

  /** Assign each group its `cN`, and say which group each OCCURRENCE belongs to.
    *
    * TWO RESULTS BECAUSE GROUPING IS NOT A BIJECTION. `deletions` is one entry per GROUP; the
    * second vector is one entry per OCCURRENCE, aligned with `found`. They are different
    * lengths whenever a label is shared, which the fixture does contain — `Body-Shapes.md`
    * § "Bones of the forearm" is `Some(1), Some(2), Some(1), Some(2)`: four occurrences, two
    * groups. A naive scheme that indexed `deletions` by occurrence position would mis-number
    * exactly that section.
    *
    * THE INDEX IS IN RANGE BY CONSTRUCTION, which is why no `Map` lookup and no `getOrElse`
    * appears here — the same argument `Tables.scala:186-188` already uses. Every index pushed
    * onto `idxs` is either `acc.size` (the position the group is being appended at) or the
    * `indexWhere` hit on that same growing vector, and `.map` is size-preserving, so
    * `numbered(i)` is total for every `i` the fold produced.
    */
  private def number(found: Vector[(Option[Int], String)]): (Vector[ClozeDeletion], Vector[Int]) =
    val labels = found.collect { case (Some(n), _) => n }.toSet

    def groupOf(label: Option[Int], text: String): ClozeGroup = label match
      case Some(n) => ClozeGroup.Labelled(n)
      case None    => ClozeGroup.Unlabelled(text)

    // Grouped in order of FIRST APPEARANCE, so the result is deterministic.
    val (groups, idxs) =
      found.foldLeft((Vector.empty[(ClozeGroup, Vector[String])], Vector.empty[Int])) {
        case ((acc, is), (label, text)) =>
          val group = groupOf(label, text)
          acc.indexWhere(_._1 == group) match
            case -1 => (acc :+ (group -> Vector(text)), is :+ acc.size)
            case i  => (acc.updated(i, group -> (acc(i)._2 :+ text)), is :+ i)
      }

    var nextFree = 1
    val numbered = groups.map { (group, texts) =>
      val ordinal = group match
        case ClozeGroup.Labelled(n) => n
        case ClozeGroup.Unlabelled(_) =>
          // Skip anything a label already claims, or two groups would share a card.
          while labels.contains(nextFree) do nextFree += 1
          val n = nextFree
          nextFree += 1
          n
      ClozeDeletion(ordinal, group, texts)
    }

    (numbered, idxs.map(i => numbered(i).ordinal))
