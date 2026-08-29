package obsidiananki.extract

import obsidiananki.content as C
import obsidiananki.model.*
import obsidiananki.plan.{BuildFailure, SourceKind, SourceRef, SourcedSpec}

/** CARDS MADE FROM A NOTE'S TYPED EDGES — the relations declared in its frontmatter.
  *
  * ==Why an edge needs no card shape of its own==
  *
  * A typed edge is a TRIPLE: `Function Space` —`special-case-of`→ `HomSet`. So is a
  * concept-descriptor card: a concept, an aspect of it, and the value of that aspect. They are the
  * same shape, which is why this file produces `CardSpec.ThreeField` and inherits identity,
  * hashing, decks, breadcrumbs, orphan handling and the whole reconciler without adding anything.
  * Subject is concept, predicate is descriptor, object is description.
  *
  * That correspondence is the whole reason this feature was cheap, and it is worth stating
  * plainly because it is easy to mistake for a coincidence. It is not: a card that asks about one
  * aspect of one thing IS a binary relation, and always was.
  *
  * ==Why the subject is the FILE NAME==
  *
  * A heading card takes its concept from the nearest ancestor heading, falling back to the file
  * name when there is none — see `Extractor`, which already does exactly this. An edge has no
  * ancestor heading at all: it is declared in frontmatter, which belongs to the whole note. The
  * file name is therefore not a fallback here but the only candidate, and it is the right one —
  * `Function Space.md` is what the note is ABOUT, which is precisely what a triple's subject is.
  *
  * IT IS A FIELD AND NOT PART OF THE KEY, which keeps renaming a file free: the key is
  * `(frontmatter id, property name)`, so renaming `Function Space.md` rewrites the card's face and
  * moves no identity. That is a stronger guarantee than a heading card gets, where rewording the
  * heading re-keys the card.
  *
  * ==Why several values make ONE card and not several==
  *
  * `special-case-of: [A, B]` is two edges but one question — "what is this a special case of?" —
  * whose honest answer is both. Keying a card per value would put the VALUE in the key, so
  * correcting a typo in `A` would retire that card and mint a new one with no review history. One
  * card per property keeps the key stable under every edit to the answer.
  */
object Edges:

  /** Every card a note's frontmatter declares, and every reason one could not be made.
    *
    * A PROPERTY NOBODY DECLARED IS PASSED OVER IN SILENCE, not reported. Almost every property in
    * a real note is not an edge — `created`, `aliases`, `cssclasses`, whatever a plugin wrote —
    * and a tool that remarked on each would be unreadable. Only a property the schema names is
    * this function's business, and once named, everything that goes wrong with it is reported.
    *
    * @param noteName
    *   the file stem — the subject of every triple this note declares.
    * @param location
    *   the note's whole location, for the `Context` breadcrumb, exactly as a heading card gets it.
    * @param rawFrontmatter
    *   the unparsed block, used ONLY to find the line a property sits on so a failure can point at
    *   it. Nothing is read from it.
    */
  def specsFor(
      noteId: NoteId,
      noteName: String,
      relativePath: String,
      location: Vector[String],
      properties: Map[String, PropertyValue],
      rawFrontmatter: String,
      schema: EdgeSchema,
      /** The author's own frontmatter tags, already classified. Computed once by the walker,
        * which is the only layer that sees frontmatter, and handed down so that every spec a
        * note produces carries the same set rather than deriving it twice.
        */
      vaultTags: Vector[VaultTag],
  ): (Vector[SourcedSpec], Vector[BuildFailure]) =
    // SORTED BY THE NAME AS WRITTEN, so a note yields the same cards in the same order on every
    // run. A `Map` has no order, and "a second run changes nothing" must not depend on one.
    val declared = properties.toVector.sortBy(_._1).flatMap { (rawName, value) =>
      PropertyName.fromFrontmatter(rawName).toOption
        .flatMap(name => schema.directionsFor(name).map((name, rawName, value, _)))
    }

    val built = declared.map { (name, rawName, value, directions) =>
      val key    = CardKey(noteId, CardPath.Property(name))
      val source = SourceRef(relativePath, lineOf(rawFrontmatter, rawName), SourceKind.Property)

      objectsOf(value)
        .flatMap { objects =>
          // JOINED WITH A COMMA because the answer is a SET and reads as one inline. A newline
          // would be wrong twice over: Anki renders a field as HTML, so it would show as a space
          // anyway, and the question of whether a field is text or markup is not settled.
          val answer = objects.map(o => C.Html.escape(plainLink(o)).render).mkString(", ")
          Body
            .fromExtracted(answer)
            .toRight(s"'$rawName' is declared as a card but its value is empty")
        }
        .map { body =>
          SourcedSpec(
            CardSpec.ThreeField(
              key,
              C.Html.escape(noteName).render,
              C.Html.escape(rawName).render,
              body,
              directions,
              // THE SAME BREADCRUMB RULE AS A HEADING CARD: the whole location, minus whatever
              // this card already carries as a field. Both the subject and the predicate are
              // fields, so neither may be shown twice.
              CardContext.compose(location, Vector(noteName, rawName)),
              "",
            ),
            source,
            // NO SECTION CHAIN AND NOTHING TO AVOID SPOILING. A property hangs off the note, not
            // off any heading, so there is no chain of ancestors to record; and the deck-path
            // clamp exists to stop a deck naming the answer, which for an edge is the value —
            // never a location segment. Passing `none` says that rather than leaving it inferred.
            Vector.empty,
            RecallText.none,
            // THE SAME TAGS A HEADING CARD FROM THIS NOTE GETS. A property card and a heading
            // card are two cards of one note, and the author's tags describe the note.
            vaultTags,
          )
        }
        .left
        .map(reason => BuildFailure.KeyKnown(key, source, reason))
    }

    (built.collect { case Right(spec) => spec }, built.collect { case Left(f) => f })

  /** EDGES WHOSE REVERSE CARD WOULD ASK ONE QUESTION AND HOLD SEVERAL ANSWERS.
    *
    * A `2way` or `3way` edge card also asks the reverse: "what is a special case of HomSet?" That
    * is only a sound question when the pair (predicate, object) determines the subject — and for
    * a many-to-one relation it does not. Three notes that are each a special case of HomSet
    * produce three cards asking the identical question, each holding a different right answer, so
    * whichever comes up you are marked wrong two times in three.
    *
    * DETECTED RATHER THAN FORBIDDEN OR TRUSTED, and that is the whole point. Refusing `2way` for
    * all edges would ban the many relations that ARE one-to-one — `dual-of`, `inverse-of` — on
    * account of the ones that are not. Trusting the author asks them to know, in advance and for
    * every relation, something the tool can simply look at: it holds every note in the vault at
    * this moment, so it can see whether the collision is actually there.
    *
    * IT IS A PROPERTY OF THE VAULT AND NOT OF A NOTE, so it can only be checked here, after
    * everything is extracted. Adding a third special case of `HomSet` months later breaks the two
    * that were fine — and the run that does it says so.
    */
  def reverseCollisions(specs: Vector[SourcedSpec]): Vector[BuildFailure] =
    val reversible = specs.collect {
      case s @ SourcedSpec(t: CardSpec.ThreeField, _, _, _, _)
          if t.directions != ThreeFieldDirections.ValueOnly && isEdge(s) =>
        (t.descriptor, t.description.value) -> s
    }

    reversible
      .groupBy(_._1)
      .toVector
      .sortBy(_._1)
      .flatMap {
        case (_, group) if group.sizeIs > 1 =>
          val subjects = group.map(_._2.spec).collect { case t: CardSpec.ThreeField => t.concept }.sorted
          group.map { case (_, s) =>
            val t = s.spec.asInstanceOf[CardSpec.ThreeField]
            BuildFailure.KeyKnown(
              s.key,
              s.source,
              s"'${t.descriptor}' is declared as a ${directionWord(t.directions)} edge, so it also " +
                s"asks which thing has '${t.description.value}' on the far end — and " +
                s"${subjects.size} notes answer that: ${subjects.mkString(", ")}. Declare " +
                s"'${t.descriptor}' as 1way, or make the relation one-to-one",
            )
          }
        case _ => Vector.empty
      }

  private def isEdge(s: SourcedSpec): Boolean = s.key.path match
    case CardPath.Property(_) => true
    case _                    => false

  private def directionWord(d: ThreeFieldDirections): String = d match
    case ThreeFieldDirections.ValueOnly => "1way"
    case ThreeFieldDirections.Default   => "2way"
    case ThreeFieldDirections.All       => "3way"

  /** The values on the far end of the edge, or why there are none to use.
    *
    * An empty value is a FAILURE rather than a silence: the author declared this property makes a
    * card and then gave it nothing, so a card cannot be made and the gap between the two is
    * exactly what wants reporting. That is the same judgement `B6` makes about a marked heading
    * with an empty body.
    */
  private[extract] def objectsOf(value: PropertyValue): Either[String, Vector[String]] =
    value match
      case PropertyValue.One(text) =>
        if text.trim.isEmpty then Left("its value is empty") else Right(Vector(text))

      case PropertyValue.Many(items) =>
        val kept = items.map(_.trim).filter(_.nonEmpty)
        if kept.isEmpty then Left("its value is an empty list") else Right(kept)

      case PropertyValue.Unreadable(shape) =>
        Left(s"its value is $shape, which this tool cannot read as one or more plain values")

  /** `[[HomSet]]` → `HomSet`, and `[[HomSet|the hom-set]]` → `the hom-set`.
    *
    * THE BRACKETS COME OFF because a card face is read, not clicked: Anki cannot follow a wikilink
    * whatever it looks like, so the brackets are noise on every review forever. The ALIAS wins
    * when present, because an author who wrote one has already said how they want that link to
    * read.
    *
    * Anything that is not a wikilink is returned untouched — a plain scalar edge such as
    * `status: draft` is as legitimate as a link.
    *
    * ==UNSPLIT — a second reader of one construct, and the two already disagree==
    *
    * Observation, not a prescription, and a different member of the family enumerated at
    * `Extractor.headingFace`: not one construct with two readings, but one reading implemented
    * twice. `ObsidianSyntax.displayText` answers the same question — what does a wikilink SAY —
    * for wikilinks in prose. This answers it for wikilinks in frontmatter, where there is no
    * markdown parse to reach for, which is why it exists at all.
    *
    * THEY DIVERGE TODAY, in two ways, VERIFIED BY READING both on 2026-08-29:
    *
    *   - THE PIPE. That one splits on the FIRST `|`; this one splits on the LAST, and the
    *     comment below cites Obsidian as the reason. Both cannot be right about Obsidian.
    *   - THE FRAGMENT. That one strips a trailing `#heading` or `^blockid`; this one does not.
    *     So `[[Note#Section]]` reads as `Note` in a body and as `Note#Section` in a property.
    *
    * Neither divergence is measured against Obsidian itself, so which is correct is open. What
    * is not open is that one construct has two answers and nothing holds them in step.
    */
  private[extract] def plainLink(raw: String): String =
    val trimmed = raw.trim
    if trimmed.startsWith("[[") && trimmed.endsWith("]]") then
      val inner = trimmed.drop(2).dropRight(2)
      // The alias is whatever follows the LAST `|`, matching Obsidian: a target may not contain
      // one, so a `|` in the display text is the author's and stays.
      inner.lastIndexOf('|') match
        case -1 => inner.trim
        case i  => inner.drop(i + 1).trim
    else trimmed

  /** The 1-based line a property sits on, for a message that points somewhere.
    *
    * Falls back to the first line of the note when the name cannot be found, which can happen
    * when a plugin writes the block in a spelling the search does not match. A line that is
    * slightly wrong is worth more than no line: it puts the reader in the frontmatter.
    */
  private[extract] def lineOf(rawFrontmatter: String, property: String): Int =
    val wanted = s"$property:"
    rawFrontmatter.linesIterator.zipWithIndex
      .collectFirst { case (line, i) if line.trim.startsWith(wanted) => i + 1 }
      .getOrElse(1)
