package obsidiananki.model

/** What a card asks the reviewer to produce, in the RAW form a location segment carries.
  *
  * ==Why this exists==
  *
  * A card's deck path is printed on the card. Every one of the eight front templates opens
  * `<div class="context"><span class="deck">{{Deck}}</span>…`, at full body size — so a deck
  * segment naming the answer prints the answer above the question, exactly as a breadcrumb
  * would. `CardContext` has defended against that since it was written; the deck never has.
  *
  * ==Why RAW text, and why carried rather than read off the CardSpec==
  *
  * The answer is on the spec already — `CardSpec.ThreeField.concept` is the very string card 1
  * blanks. But it arrives there ESCAPED (`Extractor` wraps it in `Html.escape`), while heading
  * titles reach a deck path raw. `A & B` and `A &amp; B` are not equal, so a comparison against
  * the spec would silently miss every concept containing one of the six escaped characters —
  * failing exactly where an author used an ampersand in a heading. This is computed where the
  * raw text still exists and carried from there.
  *
  * ==Empty is the common case and is not a hole==
  *
  * Only two card shapes can ever ask for something a location also names: a two-way card, whose
  * reverse asks for the marked heading, and a three-field card, whose first card asks for the
  * concept. For a one-way, cloze, sequence or table card the answer is body prose or a table
  * cell, which no folder, file or heading is, so nothing is at risk and this is empty.
  */
opaque type RecallText = Vector[String]

object RecallText:
  val none: RecallText = Vector.empty

  /** From raw display text. The caller is the one place holding the unescaped strings. */
  def apply(raw: Vector[String]): RecallText = raw.filter(_.trim.nonEmpty)

  extension (r: RecallText) def values: Vector[String] = r

