package obsidiananki.extract

import obsidiananki.content as C

/** The breadcrumb a card shows above its prompt: where in the document this card came from.
  *
  * ==Why the card needs one==
  *
  * A card can be unanswerable because it lost its context, and the loss is invisible from
  * inside Anki. The example is in this repository, and both halves of it were re-read here on
  * 2026-08-21 after an earlier version of this paragraph got both halves wrong.
  *
  * THE NOTE. At `06b185c^` — the commit before `Put the heading chain on the card as a visible
  * Context field` — `extract/golden/fixture-cards.txt` recorded the note keyed
  * `…body%20shapes/cranial%20bones%20and%20their%20sutures/frontal/anterior%20border` on note
  * type `3 way Concept-Descriptor` with FOUR fields, not two: `Concept: Frontal`,
  * `Descriptor: Anterior border`, `Description: Orbital rim`, and an empty `ThreeWay`. _This
  * corrects a sentence that said "`Concept: Frontal`, `Descriptor: Anterior border` and nothing
  * else"._
  *
  * THE CARD FACES, WHICH THE GOLDEN DOES NOT SHOW. A golden entry is a note's FIELD SET; what a
  * reviewer sees is a template's rendering of it, and this note type has three.
  * `resources/note-types/concept-descriptor/manifest.json` names them in order, and their
  * template files say the same thing:
  *
  *   - Card 1, `Descriptor+Description -> Concept`: the front is `{{Descriptor}}<br>
  *     {{Description}}` and the back is `{{Concept}}`. **`Frontal` is the ANSWER on this
  *     template, not part of the question.** _This corrects a sentence that called the Concept
  *     and Descriptor together "the whole card face"._
  *   - Card 2, `Concept+Descriptor -> Description`: the front is `{{Concept}}<br>{{Descriptor}}`
  *     and the back is `{{Description}}`. **This is the face the argument is actually about** —
  *     the whole question was `Frontal` / `Anterior border`. Frontal WHAT: bone, lobe, cortex?
  *     The one segment that disambiguates, `Cranial bones and their sutures`, was dropped, so
  *     the card asks a question that cannot be answered without already knowing the answer.
  *   - Card 3, `Concept+Description -> Descriptor`: its whole front is wrapped in
  *     `{{#ThreeWay}}…{{/ThreeWay}}`, and `ThreeWay` is empty on this note, so no third card is
  *     generated for it.
  *
  * The same shape was reported from a live collection. `LEARNING-MODEL.md` names the
  * consequence: a card that cannot be derived gets answered by pattern-matching its answer
  * string, which "silently teaches you that you know something you do not".
  *
  * ==Why this is derived from the TITLES and never from the KEY==
  *
  * `CardKey`'s path is the CANONICAL form — case-folded, whitespace-collapsed, and
  * percent-encoded on the way into the `src::` tag — because identity is deliberately severed
  * from display (ruling B5). A breadcrumb derived from it would read
  * `body shapes > cranial bones and their sutures` in permanent lowercase. `Extractor.walk`
  * already carries the properly cased chain as `ancestorTitles` and used to discard all but
  * its last element.
  *
  * ==What the caller must pass==
  *
  * The chain DOWN TO BUT NOT INCLUDING whatever the card's own face already shows, so the
  * breadcrumb never prints the answer or repeats the prompt. That rule differs per card shape
  * and belongs to the caller that knows which shape it is building; this object only joins and
  * escapes. See `Extractor.buildSpecs` and `Tables.fromSection` for the six decisions.
  */
/** Everything a card's location can contribute to its breadcrumb, SPOILERS ALREADY REMOVED.
  *
  * THE SAME THREE INGREDIENTS THE DECK IS COMPOSED FROM — see `DeckSource` in
  * `extract/VaultWalker.scala`, which carries the identical triple for the identical reason.
  * They are two records rather than one only because generalising the deck's name is a rename,
  * and renames are Marc's to make; the duplication is noted so it is not mistaken for a
  * distinction.
  *
  * A PATH IS TWO FIELDS, NOT ONE STRING, and that is what makes "the whole path, not the
  * directories only" expressible: folders without the file, the file without its folders, or
  * both, are all reachable. A single joined path would force the pair.
  *
  * ==The contract, which is the existing one extended by one field==
  *
  * The caller passes only what the card's own face does NOT already show. That rule has always
  * governed `headings` — a three-field card drops the concept from its chain because the
  * concept is printed on the card — and it now governs `fileName` in exactly the same way. When
  * a marked heading has no ancestor, `Extractor` uses the FILE NAME as the concept; that file
  * name is then on the card, and putting it in the breadcrumb too would print the answer above
  * the question. The caller knows which case it is in, so the caller leaves it out.
  *
  * Nothing here re-derives that. This record is what survived the caller's judgement.
  */
final case class ContextSource(folders: Vector[String], fileName: String, headings: Vector[String])

/** Which parts of a card's location its breadcrumb shows.
  *
  * The presentational twin of `DeckShape`, and the same ruling covers both: REQUIREMENTS.md
  * item 11 makes folder path, file name and heading path COMPLEMENTARY segments, each optional,
  * and says to expose the mechanism rather than embed a preference.
  *
  * THE DECK AND THE BREADCRUMB ARE SEPARATE CHOICES, which is why this is a second type rather
  * than a reuse of the deck's. They answer different questions: a deck decides what can be
  * studied in isolation, a breadcrumb decides what can be read while answering. Wanting one
  * without the other is ordinary — a flat deck whose cards still say where they came from, or a
  * deeply filed deck whose cards stay uncluttered.
  */
final case class ContextShape(folders: Boolean, fileName: Boolean, headings: Boolean)

object ContextShape:

  /** What the breadcrumb showed before it was composable, and still the default.
    *
    * THE DEFAULT MUST NOT REWRITE EVERY CARD. The context is a FIELD, so changing it changes
    * each note's content hash and the next run reports an update for every note in the
    * collection. Review history survives — identity is untouched — but a run that rewrites a
    * whole collection is one the author must ask for.
    */
  val HeadingsOnly: ContextShape =
    ContextShape(folders = false, fileName = false, headings = true)

object CardContext:

  /** Build the breadcrumb from the parts the shape selects.
    *
    * EXPRESSED IN TERMS OF [[render]] RATHER THAN BESIDE IT. Selecting only the headings has to
    * mean exactly what the four existing call sites already produce, character for character:
    * the context is a card FIELD, so any drift would rewrite every note's content hash and the
    * next run would report an update for the whole collection. Routing through the same
    * function makes that identity structural instead of a coincidence two bodies maintain, and
    * `CardContext.test.scala` asserts it besides.
    *
    * SEGMENTS IN DOCUMENT ORDER — folders, then the file, then the headings — and no ordering
    * switch, for the reason `DeckShape` gives: a file lives in a folder and a heading lives in
    * a file, so a breadcrumb reading `Surjection › Functions` would describe a containment that
    * does not exist. What is optional is which parts appear.
    *
    * BLANKS DROP OUT. A file name the caller emptied to avoid spoiling the answer must cost
    * nothing — not a separator, not a gap — and the same holds for a folder that is whitespace
    * or a heading chain with a hole in it. Trimming happens here for the same reason it happens
    * in `Decks.compose`: this is the one place that sees the assembled selection.
    */
  def compose(shape: ContextShape, source: ContextSource): String =
    val selected =
      (if shape.folders then source.folders else Vector.empty) ++
        (if shape.fileName then Vector(source.fileName) else Vector.empty) ++
        (if shape.headings then source.headings else Vector.empty)
    render(selected.map(_.trim).filter(_.nonEmpty))

  /** `" › "` — space, U+203A SINGLE RIGHT-POINTING ANGLE QUOTATION MARK, space.
    *
    * CHOSEN BY ELIMINATION, and each rejection has a witness in this repository.
    *
    *   - `/` is out because `dummy-vault/Patterns/Messaging.md` carries `## Cost / benefit`
    *     ON PURPOSE, to probe the encoding (`FIXTURES.md`: the slash "must not be tidied
    *     away"). Joining with `/` would make the breadcrumb ambiguous about where one heading
    *     ends.
    *   - `>` is out because `Html.escape` renders it `&gt;`, so the separator would arrive on
    *     the card as an entity in the field value.
    *
    * U+203A SURVIVES BOTH RENDERING STEPS UNCHANGED, verified rather than assumed:
    * `content/AsHtml.scala`'s `Html.escape` touches exactly `& < > " { }` and nothing else,
    * and `Golden.test.scala`'s `EscapedTypes` covers the CONTROL, FORMAT, SURROGATE,
    * UNASSIGNED, PRIVATE_USE and the three separator categories — U+203A is category `Pf`
    * (final punctuation), so it stays literal and legible in the golden file's diff.
    */
  val Separator: String = " › "

  /** Join a chain of DISPLAY titles into an escaped HTML field value.
    *
    * ESCAPED HERE, WHICH IS THE ARGUMENT POSITION FOR EVERY CALLER. The titles must reach this
    * function unescaped: `Extractor` derives a card's KEY and a card's DISPLAY text from the
    * same raw heading string, and escaping upstream of that fork would move card keys — the
    * golden's Quorums key carries `>` as `%3e` INSIDE the key, which would become `%26gt%3b`,
    * an orphan plus a history-less card for every heading containing one of the six escaped
    * characters.
    *
    * JOIN-THEN-ESCAPE IS EXACTLY EQUIVALENT TO ESCAPE-THEN-JOIN here, and that is a property
    * of the escaper rather than a coincidence worth re-deriving: `Html.escape` is a per-
    * character map, so it distributes over concatenation, and [[Separator]] contains no
    * character it rewrites.
    *
    * AN EMPTY CHAIN YIELDS THE EMPTY STRING, which is a real and expected value — a `3way`
    * heading sitting directly under its note's H1 has nothing above it that the card does not
    * already show. The note types wrap the field in `{{#Context}}…{{/Context}}`, so an empty
    * value emits no markup at all.
    */
  def render(titles: Vector[String]): String =
    C.Html.escape(titles.mkString(Separator)).render
