package obsidiananki.content

import laika.ast
import obsidiananki.parser.ObsidianSyntax

/** THE PINS FOR THE LOWERING, AFTER ITS ORACLE WAS CONSUMED.
  *
  * ==What changed in S9, and why this file no longer holds a differential==
  *
  * S8 wrote every assertion here as a DIFFERENTIAL against `Extractor.bodyText` and
  * `Cloze.fromSection` — the hand-written walks the lowering was built to replace. S9 deleted
  * both of those functions, so there is no oracle left to compare against.
  *
  *   - The VAULT differential and the CLOZE differential are GONE. Re-pointing either at the
  *     new production path would compare `Lower + AsText` against `Lower + AsText`: a tautology
  *     wearing a differential's clothes, which reads as coverage forever and cannot fail. What
  *     they covered is named at their former sites below rather than left implicit.
  *   - The SNIPPET differentials are KEPT, CONVERTED. Their oracle was INLINED WHILE IT STILL
  *     EXISTED: each expected string below was printed by `Extractor.bodyText` on the
  *     unmodified pre-S9 tree and pasted here verbatim. The assertion is unchanged in strength
  *     — same input, same expected bytes — not weakened; only the way the expected value is
  *     obtained changed, from computed-at-run-time to captured-once. The capture ran BEFORE the
  *     deletion, deliberately: reversed, the new implementation would have laundered itself
  *     into its own expectation.
  *   - The REFUSAL tests are KEPT VERBATIM. They never touched the old walk.
  *
  * ==What these snippets are still for==
  *
  * `extract/golden/fixture-cards.txt` pins none of them. Measured on 2026-08-20, `dummy-vault`
  * witnesses ZERO of the five constructs the lowering FLATTENS (a blockquote, a strikethrough,
  * an inline link, a loose list's `ForcedParagraph`, a paragraph-interrupted-by-list's
  * `BlockSequence`) and ZERO of the three it DROPS (an Obsidian or HTML comment, a horizontal
  * rule, a hard line break). Counted in the vault: `%%` 0, `<!--` 0, `>` 0, `~~` 0, `](` 0,
  * ordered list 0, trailing-double-space 0, `**` inside a marked body 0. The golden cannot
  * cover them and this file is the only thing that does.
  *
  * ==Parsing a string is not hand-building an AST==
  *
  * `Golden.test.scala:133-137` refuses to add synthetic input, on the grounds that a
  * hand-built multi-block `laika.ast.Cell` would pin an input Laika's GFM parser cannot
  * produce — a net fighting the refactor it exists to protect. That objection does not reach
  * the snippets below, and the distinction is the whole reason they are legitimate: every
  * snippet here is a MARKDOWN STRING pushed through the production
  * `ObsidianSyntax.markupParser`. Whatever the parser emits is by definition producible from
  * authored markdown. Hand-constructing a `laika.ast` value remains forbidden in this file.
  *
  * ==The one surviving helper==
  *
  * `parse`, the production parser. S8 also carried a `dummy-vault` locator and a recursive
  * `ast.Section` collector; both went with the vault sweeps that used them.
  * `Content.test.scala` banned test-local helpers because a FLATTENER is an oracle in disguise
  * — a second implementation of the thing under test, which someone later lifts into main. A
  * call to the production parser cannot become one. NOTHING IN THIS FILE PRODUCES A CARD
  * STRING except `AsText`.
  *
  * ==Why the drop cases are provable at all==
  *
  * `Lower`'s default arm REFUSES. So deleting the `ObsidianComment`, `Rule` or `LineBreak`
  * case does not make its construct render as `""` — it makes the construct fall through to
  * the refusing default, which turns that snippet's differential from `Right` into `Left` and
  * the test RED. With a `case _ => ""` default instead, no mutation of any drop case could
  * redden anything, because dropping and falling through would be the same behaviour. The
  * refusing default is not only the safety property this slice exists for; it is also what
  * makes the drop cases testable. All three mutations were run and each was seen RED — the
  * result is reported with the slice, not asserted here.
  */
class LowerSuite extends munit.FunSuite:

  // ═════════════════════════════════════════════════════════════ the one helper ════

  /** The PRODUCTION parser, never a hand-built tree. See the class comment. */
  def parse(markdown: String): ast.RootElement =
    ObsidianSyntax.markupParser
      .parse(markdown)
      .fold(e => fail(s"snippet did not parse: $e"), _.content)

  // ══════════════════════════════════════ (A) the vault differential — DELETED ════
  //
  // S8 swept every `Section` of all ten `dummy-vault` files, marked and unmarked, at every
  // depth, and asserted `Lower.blocks(ownBody(s)).map(AsText.plain) == Extractor.bodyText(
  // ownBody(s))`. Its oracle no longer exists, and re-pointing it at the new production path
  // would assert `Lower + AsText` against `Lower + AsText`.
  //
  // WHAT IS LOST, NAMED RATHER THAN WAVED AT — the golden does NOT supersede it exactly:
  //   - It swept UNMARKED sections. Those produce no card and appear nowhere in the golden.
  //   - It computed `bodyText` for `#flashcard/table` sections — a string production computes
  //     and then discards, so the golden never sees it either.
  //   - S8 MEASURED that this sweep closed the named uncovered gap `Golden.test.scala:129-131`
  //     records (`Extractor.elementText`'s `case t: Table` join): mutating the table separator
  //     reddened it. That coverage is inherited by the converted GFM-table literal below, and
  //     by nothing else.

  // ══════════════════════════════════════ (B) the cloze differential — DELETED ════
  //
  // S8 rendered every `#flashcard/cloze` section through `AsText.text` with a `{{cN::…}}` hook
  // and compared it against `Cloze.fromSection`'s spec text. Same tautology problem, plus
  // `Cloze.fromSection` no longer exists — it is `Cloze.fromLowered` now, and it renders
  // through `AsText.text` itself.
  //
  // The RENDERED half is genuinely superseded: `extract/golden/fixture-cards.txt` pins the
  // Cloze `Text` field value for all five fixture cloze sections. The AMBIGUITY half is not,
  // because the golden records a failure's case name and key and never its reason — that is
  // what `Extractor.test.scala`'s "two unlabelled highlights with identical text are refused"
  // now pins, and why it was added in this slice rather than later.
  //
  // ITS ONE-TIME VALUE IS SPENT AND WORTH RECORDING: passing green on the pre-S9 tree is the
  // evidence that today's TWO cloze key derivations agreed on all five fixture sections —
  // `Cloze.collectHighlights` keyed an unlabelled group by Laika's `Highlighted.extractText`
  // while `Cloze.renderWithDeletions` keyed it by its own span concatenation. S9 collapses
  // them onto the single `AsText`-rendered string, and that green run is the only measurement
  // anyone took before the collapse.

  // ═══════════════════════════════════════════════════════ (C) snippet pins ════

  /** One snippet, parsed with NO heading, rendered through the lowering, compared to bytes.
    *
    * `root.content` rather than a section body, so nothing here depends on section building.
    *
    * @param expected what `Extractor.bodyText` returned for this exact snippet on the
    *                 unmodified pre-S9 tree, captured by running it and pasted verbatim. It is
    *                 a CHARACTERIZATION of the old walk, not a judgement about what the
    *                 rendering ought to be — several of these strings are ugly (a table loses
    *                 its pipes, a comment leaves a double space behind) and that is precisely
    *                 what a later, golden-visible slice is expected to change.
    */
  def differential(name: String, markdown: String, expected: String): Unit =
    test(s"C: $name renders identically through the lowering") {
      val blocks = parse(markdown).content.toVector
      assertEquals(Lower.blocks(blocks).map(AsText.plain), Right(expected))
    }

  // The five FLATTENS. None occurs in dummy-vault.
  differential("a blockquote", "> quoted prose\n> on two lines\n", "quoted prose\non two lines")
  differential(
    "a blockquote containing a heading",
    "> ## Nested heading\n>\n> quoted prose\n",
    "Nested heading\nquoted prose",
  )
  differential("strikethrough", "prose with ~~a deletion~~ in it\n", "prose with a deletion in it")
  differential(
    "an inline link",
    "prose with [link text](http://example.com/x) in it\n",
    "prose with link text in it",
  )
  differential("a loose list (ForcedParagraph)", "- first item\n\n- second item\n", "first item\nsecond item")
  differential(
    "a paragraph interrupted by a list (BlockSequence)",
    "lead-in prose\n- first item\n",
    "lead-in prose\nfirst item",
  )

  // Constructors with no fixture witness.
  differential("an ordered list", "1. first item\n2. second item\n", "first item\nsecond item")
  differential("bold in ordinary prose", "prose with **bold words** in it\n", "prose with bold words in it")
  differential("a four-space nested list", "- outer item\n    - inner item\n", "outer item\ninner item")

  // The three DROPS. Each falls to the REFUSING default if its case is deleted, which turns
  // its `Right` into a `Left` and reddens the test — see the class comment.
  //
  // NOTE THE DOUBLE SPACE in both comment expectations. The comment is dropped from the middle
  // of the paragraph and the spaces the author typed around it are not, so `before %%x%% after`
  // renders `before  after`. That is today's behaviour, captured, not endorsed.
  differential("an Obsidian comment", "before %%hidden from the reader%% after\n", "before  after")
  differential("an HTML comment", "before <!--hidden from the reader--> after\n", "before  after")
  differential("a horizontal rule", "prose above\n\n---\n\nprose below\n", "prose above\nprose below")
  differential("a hard line break", "first line  \nsecond line\n", "first line\nsecond line")

  // Table and inline-code paths.
  differential("an inline code span", "prose with `code()` in it\n", "prose with code() in it")
  differential(
    "a GFM table",
    "| head one | head two |\n| --- | --- |\n| cell one | cell two |\n",
    "head one\nhead two\ncell one\ncell two",
  )
  differential(
    "a ragged table with a short row",
    "| head one | head two |\n| --- | --- |\n| only one cell |\n",
    "head one\nhead two\nonly one cell",
  )

  /** THE PIN FOR THE BLOCK-LEVEL NON-EMPTY FILTER.
    *
    * A paragraph holding ONLY a comment lowers to `Block.Paragraph(Vector.empty)`, which
    * renders to `""`. The filter that removes it is on the RENDERED STRING at block level. Get
    * that level wrong — filter structurally, or not at all — and a blank line appears between
    * the two surviving paragraphs. One line, not two, and the literal says so out loud.
    */
  test("C: a paragraph that renders empty is removed, not turned into a blank line") {
    val blocks = parse("prose above\n\n%%just a comment%%\n\nprose below\n").content.toVector
    assertEquals(Lower.blocks(blocks).map(AsText.plain), Right("prose above\nprose below"))
  }
  // ══════════════════════════════════════════════════════════════ (D) refusals ════

  test("D: an Obsidian embed refuses by name, carrying the target the author greps for") {
    Lower.blocks(parse("prose with ![[diagram.png]] in it\n").content.toVector) match
      case Left(refusals) =>
        assertEquals(refusals.toVector, Vector(Refusal.Embed("diagram.png")))
        val described = refusals.head.describe
        assert(described.contains("embed"), s"does not name the construct: $described")
        assert(described.contains("diagram.png"), s"does not carry the target: $described")
      case Right(lowered) => fail(s"an embed must refuse, got $lowered")
  }

  test("D: a task list refuses by name") {
    Lower.blocks(parse("- [ ] something to do\n").content.toVector) match
      case Left(refusals) =>
        assertEquals(refusals.toVector, Vector(Refusal.TaskList))
        assert(
          refusals.head.describe.contains("task list"),
          s"does not name the construct: ${refusals.head.describe}",
        )
      case Right(lowered) => fail(s"a task list must refuse, got $lowered")
  }

  /** A PLAIN MARKDOWN IMAGE IS AN EMBED TOO, and it refuses by name.
    *
    * NOTE THE LEADING SLASH, which is not a typo and not ours. The author wrote
    * `pictures/x.png`; Laika resolves a relative image reference to an `InternalTarget`, whose
    * `underlying` is a Laika VIRTUAL PATH rooted at the (notional) input tree — so
    * `t.underlying.toString` is `/pictures/x.png`. `Lower.imageTarget` derives the string
    * exactly as `Extractor.bodyText:189-191` derives it today, leading slash included, because
    * this slice may not change what a refusal says any more than it may change what a card says.
    *
    * THE CONSEQUENCE, NAMED RATHER THAN SMOOTHED OVER: for an INTERNAL target the payload is
    * therefore NOT byte-identical to what the author typed, so "the string they grep their
    * vault for" holds only up to that slash. A `![[embed]]` and an EXTERNAL `![](http://…)`
    * both carry their literal text. Whether the virtual path should be normalised back is an
    * output-shape question for a later, golden-visible slice.
    */
  test("D: a plain markdown image refuses by name, carrying its path") {
    Lower.blocks(parse("prose with ![alt text](pictures/x.png) in it\n").content.toVector) match
      case Left(refusals) =>
        assertEquals(refusals.toVector, Vector(Refusal.Image("/pictures/x.png")))
        val described = refusals.head.describe
        assert(described.contains("image"), s"does not name the construct: $described")
        assert(described.contains("pictures/x.png"), s"does not carry the path: $described")
      case Right(lowered) => fail(s"an image must refuse, got $lowered")
  }

  /** ACCUMULATION AND ORDER, both observable and both pinned.
    *
    * Order is DOCUMENT ORDER. It is worth pinning rather than leaving to chance: two
    * implementations that both "accumulate" can disagree about order silently, and the report
    * an author reads is a list. This WAS a change: the walk S9 deleted ran three separate
    * `collectFirst`s and returned one error, so an embed anywhere beat a task list that came
    * earlier in the document, and the author learned about the second construct only on the
    * next run. Invisible to the golden, which pins a failure's case name and key but never its
    * reason — `Extractor.test.scala`'s "a body holding two refusable constructs reports BOTH"
    * is what pins the change end to end.
    */
  test("D: two refusable constructs accumulate, in document order") {
    Lower.blocks(parse("- [ ] a task first\n\nthen ![[diagram.png]]\n").content.toVector) match
      case Left(refusals) =>
        assertEquals(refusals.toVector, Vector(Refusal.TaskList, Refusal.Embed("diagram.png")))
      case Right(lowered) => fail(s"both constructs must refuse, got $lowered")
  }
