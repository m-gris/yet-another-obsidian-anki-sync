package obsidiananki.cli

import obsidiananki.model.Marker

/** The text `obsidian-anki-sync --help` prints above its options.
  *
  * ==Why the markers are here at all==
  *
  * Every flag was already described, and the help was still not enough to use the tool: a flag
  * list cannot tell you the one thing you cannot guess, which is what to write in your markdown.
  * Someone reading `--help` learnt that a vault gets synced and nothing whatsoever about how a
  * heading becomes a card. The two facts that unlock it — a heading needs a marker, a file needs
  * an `id` — cost fifteen lines to state and were nowhere.
  *
  * ==Why it is generated and not typed out==
  *
  * The list comes from [[Marker.Documented]], which sits beside the parser it describes. A
  * hand-written list here would be free to drift from what the tool accepts, and a help text
  * that names a marker the parser rejects is worse than one that says nothing: it sends someone
  * to write a heading that will never make a card, and they will look for the fault in their
  * vault. `model/Marker.test.scala` holds the other end of that, by reading the parser's own
  * `case` literals and requiring the two sets to match exactly.
  */
object Help:

  /** Two spaces of indent, then the token padded to the widest one, then its gloss.
    *
    * MEASURED FROM THE DATA rather than hard-coded, so adding a longer marker cannot leave the
    * column ragged — the longest today, `#flashcard/table/1way/cells`, is 27 characters, and
    * nothing should have to remember that.
    */
  private def markerLines: Vector[String] =
    val width = Marker.Documented.map(_._1.length).maxOption.getOrElse(0)
    Marker.Documented.map((token, gloss) => s"  ${token.padTo(width, ' ')}  $gloss")

  val header: String =
    (Vector(
      "Sync marked headings in an Obsidian vault into Anki.",
      "",
      "A heading becomes a card when it carries a marker, and a file yields cards only when",
      "its frontmatter carries an id:",
      "",
      "    ---",
      "    id: replication",
      "    ---",
      "",
      "    ## Read-your-writes consistency #flashcard/2way",
      "",
      "    A guarantee that a client always sees its own prior writes.",
      "",
      "Markers:",
      "",
    ) ++ markerLines ++ Vector(
      "",
      "Nothing generated is ever written back into your markdown: a card's identity is derived",
      "from the id and the heading path, and stored in Anki as a tag. Renaming a marked heading",
      "therefore costs that card its review history — the old card is suspended, not deleted.",
      "",
      "See README.md for the full guide.",
    )).mkString("\n")
