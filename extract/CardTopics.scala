package obsidiananki.extract

import obsidiananki.content as C
import obsidiananki.model.VaultTag

/** WHAT A CARD IS ABOUT, shown on the card beside — and distinctly from — its breadcrumb.
  *
  * The sibling of [[CardContext]], and the two divide one question. `CardContext` answers WHERE
  * a card came from: an ordered chain, folders down to headings, which is a filing address. This
  * answers WHAT it is about: an unordered set of overlapping facets, where one note is `CS` and
  * `PLT` and `type-theory` at once.
  *
  * ==Why the card needs one==
  *
  * A note at the vault ROOT has no folders, so its breadcrumb is built from the file name and
  * the headings alone — and the file name is removed whenever the card already shows it as a
  * field, which is exactly what a concept-descriptor card does when its concept falls back to
  * it. The breadcrumb then renders EMPTY and the card asks a question nothing on it can answer.
  * 77 of the 92 notes in the vault this tool is developed against are at the root (measured
  * 2026-09-22), so that is the ordinary case rather than an edge one.
  *
  * ==The six decisions, each with its reason==
  *
  * NO SEPARATOR STRING, UNLIKE [[CardContext.Separator]]. Each subject is emitted as its own
  * element and the spacing is the stylesheet's; nothing is joined. A separator would make the
  * subjects read as a sequence, and they are a set — the same reason they are not a deck level.
  *
  * THE WHOLE TAG, NEVER JUST ITS LEAF. Obsidian nests tags with `/`, so `backend/scala` could
  * show as `scala`. It does not: the author wrote both segments and the broader one is often
  * the one that situates the card. Truncating would discard information to save a few
  * characters, which is the trade this field exists to refuse.
  *
  * IN THE ORDER THE AUTHOR WROTE THEM. Frontmatter is a list and its order is the author's;
  * sorting would impose one they did not choose. Nothing downstream depends on the order.
  *
  * REPEATS COLLAPSE, compared loosely. `CS` and `cs` are one subject and printing both is
  * noise, exactly as a repeated breadcrumb segment is.
  *
  * A SUBJECT THE CARD ALREADY SHOWS IS REMOVED, for the two reasons [[CardContext.compose]]
  * gives for its own rule: a term on the question side is redundant, and one on the answer side
  * is a spoiler. One list covers both, and only the caller building the card knows which fields
  * it is filling.
  *
  * A TAG ANKI REFUSED NEVER REACHES A CARD. [[VaultTag.Unusable]] is the run report's business —
  * the author is told which tag and why. Printing it here would show the author a subject that
  * their filtered decks cannot find, which is worse than not showing it.
  *
  * ==What this deliberately does NOT do==
  *
  * It does not decide WHICH tags are subjects. `extract/VaultWalker.scala` already excluded
  * markers by the time a [[VaultTag]] exists, and re-deciding here would duplicate that
  * classification and duplicate it worse — the same mistake `VaultTag` records having made once
  * already.
  */
object CardTopics:

  /** The subjects of one card, as an HTML field value.
    *
    * EACH SUBJECT IN ITS OWN ELEMENT, so the stylesheet can render them as chips and so no
    * separator has to be invented. A field holding plain text would still render; this holds
    * the structure instead, because the structure is the point.
    *
    * ESCAPED HERE, which is the argument position for every caller, exactly as
    * [[CardContext.render]] escapes there. A tag reaches this function as the author wrote it.
    *
    * AN EMPTY RESULT IS A REAL AND EXPECTED VALUE — a note with no subject tags, or one whose
    * only tag is already on the card. The note types wrap the field in `{{#Topics}}…{{/Topics}}`,
    * so nothing is emitted rather than an empty rule and a gap.
    */
  def compose(tags: Vector[VaultTag], shownOnCard: Vector[String]): String =
    // COMPARED UNDER `CardContext.loosely`, the same reduction the breadcrumb uses for its own
    // de-duplication — one definition, because a spoiler that leaks on a hyphen in one place
    // and not the other is a difference nobody would think to look for.
    val hide = shownOnCard.map(CardContext.loosely).filter(_.nonEmpty).toSet

    // MATCHED EXHAUSTIVELY RATHER THAN COLLECTED, for the reason `Planner.carried` records: an
    // outcome added to `VaultTag` later must be forced to say whether it shows on a card. A
    // partial function would skip it in silence, which is how a tag the author wrote comes to
    // do nothing at all.
    val authored = tags.flatMap {
      case VaultTag.Carried(_, asWritten) => Vector(asWritten)
      case VaultTag.Unusable(_, _)        => Vector.empty
    }

    val kept = authored
      .map(_.trim)
      .filter(_.nonEmpty)
      .filterNot(tag => hide.contains(CardContext.loosely(tag)))

    // THE FIRST SPELLING WINS, WHERE THE BREADCRUMB'S LAST ONE DOES — and the difference is in
    // the inputs rather than a matter of taste. There, the two candidates are a file stem and a
    // heading, so the later is the author's prose and the earlier a filesystem artifact. Here
    // both are entries in one frontmatter list, equally the author's, so keeping the first is
    // the only rule that leaves the order they wrote undisturbed.
    val once = kept.foldLeft(Vector.empty[String]) { (seen, tag) =>
      if seen.exists(s => CardContext.loosely(s) == CardContext.loosely(tag)) then seen
      else seen :+ tag
    }

    // ONE ELEMENT EACH AND NOTHING BETWEEN THEM. The spacing is the stylesheet's; see the note
    // on this object for why a set must not be given a separator.
    once.map(tag => s"""<span class="topic">${C.Html.escape(tag).render}</span>""").mkString
