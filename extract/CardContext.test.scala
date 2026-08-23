package obsidiananki.extract

/** Composing a card's breadcrumb from where the card lives.
  *
  * ==What this is for==
  *
  * A card under review has to say enough about itself to be answerable. `Surjection /
  * Definition` does not say it is about FUNCTIONS, and the file it came from — `Functions.md` —
  * knew that and could not say so: the breadcrumb was built from heading ancestors alone, so a
  * note two levels deep produced an EMPTY context. Marc's ruling in `docs/REQUIREMENTS.md` item
  * 11 — folder path, file name and heading path are complementary segments, each optional —
  * had been applied to the deck and never here.
  *
  * ==The one rule these tests exist to protect==
  *
  * A breadcrumb must never print the answer. `CardContext`'s long-standing contract is that the
  * CALLER passes only what the card's face does not already show, and that rule now covers the
  * file name as well as the headings: when a marked heading has no ancestor, `Extractor` uses
  * the FILE NAME as the card's concept, and a breadcrumb repeating it would put the answer
  * above the question.
  *
  * That judgement is the caller's and is not re-derived here — which is exactly why the tests
  * below drive `compose` with sources that have already been pruned. What they pin is that
  * `compose` shows what it is given and nothing more.
  */
class CardContextTest extends munit.FunSuite:

  /** `Functions.md`, at the vault root, holding `# Surjection` / `## Definition #flashcard/3way`.
    *
    * `headings` is what the THREE-FIELD caller passes: the ancestor chain minus the concept,
    * because the concept is printed on the card. For this note that leaves nothing — which is
    * precisely why the card came out with no context at all, and why the file name matters.
    */
  private val functions: ContextSource =
    ContextSource(folders = Vector.empty, fileName = "Functions", headings = Vector.empty)

  /** The same note moved into a folder, and with a heading left over after the concept is
    * dropped — so that every field has something in it and selection is observable.
    */
  private val nested: ContextSource =
    ContextSource(
      folders = Vector("Maths", "Analysis"),
      fileName = "Functions",
      headings = Vector("Injectivity and friends"),
    )

  private val sep = CardContext.Separator

  test("the default shape is what the breadcrumb showed before it was composable") {
    assertEquals(
      CardContext.compose(ContextShape.HeadingsOnly, nested),
      "Injectivity and friends",
    )
  }

  /** THE CASE THAT PROMPTED ALL THIS. A note whose only ancestor is the concept has an empty
    * heading chain, so under the old behaviour its card said nothing about where it came from.
    * Selecting the file name is the whole fix.
    */
  test("a card whose heading chain is empty can still say which file it came from") {
    assertEquals(CardContext.compose(ContextShape.HeadingsOnly, functions), "")
    assertEquals(
      CardContext.compose(ContextShape(folders = false, fileName = true, headings = true), functions),
      "Functions",
    )
  }

  test("segments appear in document order: folders, then the file, then the headings") {
    assertEquals(
      CardContext.compose(ContextShape(folders = true, fileName = true, headings = true), nested),
      s"Maths${sep}Analysis${sep}Functions${sep}Injectivity and friends",
    )
  }

  test("each part can be selected on its own") {
    val only = (f: Boolean, n: Boolean, h: Boolean) =>
      CardContext.compose(ContextShape(folders = f, fileName = n, headings = h), nested)
    assertEquals(only(true, false, false), s"Maths${sep}Analysis")
    assertEquals(only(false, true, false), "Functions")
    assertEquals(only(false, false, true), "Injectivity and friends")
    assertEquals(only(true, false, true), s"Maths${sep}Analysis${sep}Injectivity and friends")
  }

  /** Selecting nothing is a legal composition and means a card with no breadcrumb. The note
    * types wrap the field in `{{#Context}}`, so an empty value emits no markup at all.
    */
  test("selecting nothing yields the empty string, which the templates render as nothing") {
    assertEquals(
      CardContext.compose(ContextShape(folders = false, fileName = false, headings = false), nested),
      "",
    )
  }

  /** THE ANTI-SPOILER RULE, EXPRESSED THE ONLY WAY IT CAN BE HERE. `compose` cannot know
    * whether the file name is on the card — only the caller building that card knows. So the
    * caller hands over a source with the spoiler already gone, and what this pins is that
    * `compose` adds nothing back: asking for a file name that is not there yields no separator,
    * no empty segment, and no gap.
    */
  test("a source with its spoiler removed composes to nothing extra") {
    val spoilerRemoved = nested.copy(fileName = "")
    assertEquals(
      CardContext.compose(ContextShape(folders = true, fileName = true, headings = true), spoilerRemoved),
      s"Maths${sep}Analysis${sep}Injectivity and friends",
    )
  }

  test("blank and whitespace-only segments contribute nothing rather than an empty step") {
    val ragged = ContextSource(
      folders = Vector("Maths", "   ", "Analysis"),
      fileName = "  Functions  ",
      headings = Vector("", "Injectivity"),
    )
    assertEquals(
      CardContext.compose(ContextShape(folders = true, fileName = true, headings = true), ragged),
      s"Maths${sep}Analysis${sep}Functions${sep}Injectivity",
    )
  }

  /** ESCAPED, BECAUSE THE RESULT IS AN HTML FIELD. A folder or file name is author text like
    * any other and can hold the six characters `Html.escape` rewrites; `render` already does
    * this for headings and `compose` must not find a way around it.
    */
  test("every part is escaped, not just the headings") {
    val hostile =
      ContextSource(folders = Vector("A & B"), fileName = "<script>", headings = Vector("x > y"))
    val out =
      CardContext.compose(ContextShape(folders = true, fileName = true, headings = true), hostile)
    assert(out.contains("&amp;"), s"a folder name was not escaped: $out")
    assert(out.contains("&lt;script&gt;"), s"a file name was not escaped: $out")
    assert(out.contains("&gt;"), s"a heading was not escaped: $out")
    assert(!out.contains("<script>"), s"raw markup reached a card field: $out")
  }

  /** `compose` and `render` must not drift: selecting only the headings has to mean exactly
    * what the four existing call sites already produce, or wiring `compose` in would silently
    * rewrite every card's context and every content hash with it.
    */
  test("headings-only composition is identical to what render already produces") {
    val chain = Vector("Body shapes", "Cranial bones")
    assertEquals(
      CardContext.compose(ContextShape.HeadingsOnly, ContextSource(Vector.empty, "", chain)),
      CardContext.render(chain),
    )
  }
