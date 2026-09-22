package obsidiananki.extract

import obsidiananki.model.{Bearings, VaultTag}

/** THE ONE PLACE A CARD'S TWO DERIVED DISPLAY VALUES ARE BUILT, and the reason it is one place.
  *
  * [[CardContext.compose]] answers WHERE a card came from and [[CardTopics.compose]] answers
  * WHAT it is about. Each subtracts the same thing: what the card already shows, because a term
  * on the question side is redundant and one on the answer side is a spoiler.
  *
  * ==Why this exists rather than two calls side by side==
  *
  * BECAUSE `shownOnCard` MUST BE THE SAME VECTOR FOR BOTH, and nothing else enforces it. Two
  * calls written out at each of the seven extraction sites would be seven chances to pass a
  * slightly different list to one and not the other — and the result would not look like a bug.
  * It would look like a breadcrumb that correctly hides the answer beside a subject line that
  * prints it, on some card kinds and not others, discovered by an author during a review.
  *
  * Held here, the two take one argument and cannot disagree. That is the whole of this file, and
  * it is the reason the design puts both values on one [[Bearings]] record rather than leaving
  * them as two loose strings a caller might fill in independently.
  */
object CardBearings:

  /** Everything a card shows about its own provenance, from everywhere it came from.
    *
    * `location` is the card's whole position — folders, file name, ancestor headings — in the
    * order they nest. `tags` is every reading of the note's frontmatter tags, including the ones
    * Anki refused, which [[CardTopics.compose]] drops. `shownOnCard` is what this card already
    * carries as fields, which only the caller building it can know.
    */
  def of(
      location: Vector[String],
      tags: Vector[VaultTag],
      shownOnCard: Vector[String],
  ): Bearings =
    Bearings(
      breadcrumb = CardContext.compose(location, shownOnCard),
      topics = CardTopics.compose(tags, shownOnCard),
    )
