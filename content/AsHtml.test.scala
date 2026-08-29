package obsidiananki.content

/** The HTML renderer, pinned shape by shape.
  *
  * ==Why every value here is hand-built, and why that is not the banned thing==
  *
  * `AsText.test.scala:26-33` already argues this: the ban in this project is on hand-building a
  * `laika.ast` value, because a hand-built Laika tree can encode an input the parser cannot
  * produce. `Content` values are this project's own closed algebra and every shape in it is
  * legal by construction, so building one by hand encodes nothing illegal.
  *
  * THERE IS NO `import laika` IN THIS FILE AND NONE SHOULD BE ADDED. "Which markdown produces
  * which `Content`" is `Lower.test.scala`'s question, not this file's. (And a parsing test here
  * would be actively misleading for two of the cases below: laika-core 1.3.2 nests lists only at
  * FOUR spaces, and `dummy-vault` contains no nested list and no ragged table at all — so the
  * nesting and raggedness pinned here have no fixture witness to parse from.)
  */
class AsHtmlSuite extends munit.FunSuite:

  // ══════════════════════════════════════════════════════ helpers ════

  /** Sugar: a paragraph of plain words. */
  def p(s: String): Block = Block.Paragraph(Vector(Inline.Text(s)))

  /** Sugar: a tight list item of plain words. */
  def li(s: String): Item = Item(Vector(p(s)))

  /** Sugar: a table cell of plain words. */
  def cell(s: String): Cell = Cell(Vector(Inline.Text(s)))

  def rendered(blocks: Vector[Block]): String = AsHtml.plain(blocks).render

  /** The deletion labels `AsText.text` fires, in the order it fires them.
    *
    * Copied in shape from `Cloze.fromLowered:57-60`, which is the production caller whose
    * ordinal assignment depends on this order.
    */
  def labelsThroughText(blocks: Vector[Block]): Vector[Option[Int]] =
    val found = Vector.newBuilder[Option[Int]]
    val _     = AsText.text(blocks, (label, inner) => { found += label; inner })
    found.result()

  /** The deletion labels `AsHtml.html` fires, in the order it fires them. */
  def labelsThroughHtml(blocks: Vector[Block]): Vector[Option[Int]] =
    val found = Vector.newBuilder[Option[Int]]
    val _     = AsHtml.html(blocks, (label, inner) => { found += label; inner })
    found.result()

  // ══════════════════════════════════════════════════════ the two halves of the job ════

  /** TEST 1 — the trap, and the reason this renderer exists.
    *
    * A newline INSIDE a paragraph is emitted VERBATIM and nothing else is done about it. The
    * author hard-wraps prose at 80 columns; HTML collapses that newline to a space by itself,
    * which is exactly what is wanted. Emitting `<br>` instead would print the author's wrap
    * onto the card as a line break.
    *
    * This reads as an omission — "surely a newline should become something?" — so it is pinned
    * with its reason attached. NOTE the limit, stated so no reader mistakes this for
    * preservation of the author's intent: `Lower.scala:421` DROPS `ast.LineBreak`, so by the
    * time we hold a `Block` a deliberate two-space hard break is indistinguishable from a soft
    * wrap. This renderer cannot preserve one and does not claim to.
    */
  test("TRAP: a newline inside a paragraph is emitted verbatim, never as <br>") {
    assertEquals(rendered(Vector(p("alpha\nbeta"))), "<p>alpha\nbeta</p>")
  }

  /** TEST 2 — the other half. Separation between blocks comes from the block ELEMENT and never
    * from inserted whitespace, so there is no whitespace between block tags at all.
    */
  test("a block boundary does not collapse, and no whitespace sits between block tags") {
    assertEquals(rendered(Vector(p("a"), p("b"))), "<p>a</p><p>b</p>")
  }

  // ══════════════════════════════════════════════════════ lists ════

  test("a bulleted list becomes <ul> of <li>") {
    assertEquals(rendered(Vector(Block.Bullets(Vector(li("a"), li("b"))))), "<ul><li>a</li><li>b</li></ul>")
  }

  /** TEST 4 — the FIRST place the `Bullets`/`Numbered` distinction is visible at all. `AsText`
    * renders the two identically (`AsText.scala:159-160`), by design and pinned there.
    */
  test("a numbered list becomes <ol>, distinctly from a bulleted one") {
    val items = Vector(li("a"), li("b"))
    assertEquals(rendered(Vector(Block.Numbered(items))), "<ol><li>a</li><li>b</li></ol>")
    assertNotEquals(rendered(Vector(Block.Numbered(items))), rendered(Vector(Block.Bullets(items))))
  }

  /** TEST 5 — the tight-`<li>` asymmetry, pinned DELIBERATELY rather than tolerated.
    *
    * An item holding exactly one `Block.Paragraph` renders its inlines bare inside the `<li>`;
    * every other item renders its blocks normally, so the outer item here DOES get a `<p>`
    * while its leaf children do not. See the file comment in `AsHtml.scala` for why that is a
    * choice rather than a reproduction of CommonMark, and for the predicted rendering reason.
    */
  test("nested list: a single-paragraph item is tight, a multi-block item is not") {
    val nested = Block.Bullets(
      Vector(
        Item(Vector(p("Fruit"), Block.Bullets(Vector(li("apple"), li("pear")))))
      )
    )
    assertEquals(
      rendered(Vector(nested)),
      "<ul><li><p>Fruit</p><ul><li>apple</li><li>pear</li></ul></li></ul>",
    )
  }

  // ══════════════════════════════════════════════════════ tables ════

  test("a table puts header cells in <thead><th> and body cells in <tbody><td>") {
    val t = Block.Table(
      header = Vector(cell("h1"), cell("h2")),
      rows = Vector(Vector(cell("a"), cell("b"))),
    )
    assertEquals(
      rendered(Vector(t)),
      "<table><thead><tr><th>h1</th><th>h2</th></tr></thead>" +
        "<tbody><tr><td>a</td><td>b</td></tr></tbody></table>",
    )
  }

  /** TEST 6b — A CELL IS NEVER FILTERED INDIVIDUALLY, and this is the asymmetry with `AsText`.
    *
    * Laika's `applyColumnOptions` PADS a short row with a zero-block cell, so an empty `Cell`
    * is a legal, parser-produced value. `AsText` drops an empty cell entirely — correct for a
    * flat newline join, catastrophic for a grid, because every later column in that row would
    * shift left with no error anywhere. Column count is preserved here instead.
    */
  test("a ragged table keeps its column count: an empty cell emits <td></td>") {
    val t = Block.Table(
      header = Vector(cell("h1"), cell("h2")),
      rows = Vector(Vector(cell("a"), Cell(Vector.empty))),
    )
    assertEquals(
      rendered(Vector(t)),
      "<table><thead><tr><th>h1</th><th>h2</th></tr></thead>" +
        "<tbody><tr><td>a</td><td></td></tr></tbody></table>",
    )
  }

  // ══════════════════════════════════════════════════════ code ════

  test("an inline code span becomes <code>") {
    assertEquals(rendered(Vector(Block.Paragraph(Vector(Inline.CodeSpan("foo"))))), "<p><code>foo</code></p>")
  }

  /** TEST 7b — `<pre>` IS MANDATORY, NOT STYLISTIC. Without it the `sql` block in
    * `dummy-vault/Replication.md` collapses onto one line — the exact defect this work exists
    * to fix, arriving inside code. The internal newline is pinned for that reason.
    *
    * `class="language-sql"` is pinned too. IT ENABLES NOTHING: nothing in this project or in
    * stock Anki highlights code. It is a hook a note type we own could use later, and the
    * escaper that covers `& < > "` is what makes it safe to put in a double-quoted attribute.
    */
  test("a code block becomes <pre><code class=...> with newlines intact and its content escaped") {
    assertEquals(
      rendered(Vector(Block.Code(Some("sql"), "select 1\nfrom <script>"))),
      "<pre><code class=\"language-sql\">select 1\nfrom &lt;script&gt;</code></pre>",
    )
  }

  test("a code block with no language becomes bare <pre><code>") {
    assertEquals(rendered(Vector(Block.Code(None, "x"))), "<pre><code>x</code></pre>")
  }

  // ══════════════════════════════════════════════════════ escaping ════

  test("the four HTML-significant characters are escaped") {
    assertEquals(rendered(Vector(p("""a < b > c & d " e"""))), "<p>a &lt; b &gt; c &amp; d &quot; e</p>")
  }

  /** TEST 9 — ESCAPING HAPPENS EXACTLY ONCE, AND IS NOT IDEMPOTENT.
    *
    * An author who literally typed `&amp;` gets `&amp;amp;`, which renders as the five
    * characters they typed. If this ever produced `&amp;` the escaper would have been applied
    * zero or two times somewhere, and the corresponding `<` case would be a hole.
    *
    * The structural half of this claim is not testable and is stated in `AsHtml.scala`:
    * `Html.escape` is the only `String => Fragment`, and `Html.escape(Html.escape(x))` does not
    * typecheck.
    */
  test("escaping is applied exactly once and is not idempotent") {
    assertEquals(rendered(Vector(p("&amp;"))), "<p>&amp;amp;</p>")
  }

  /** TEST 10 — braces in AUTHOR text.
    *
    * ASSERTS ONLY WHAT THE TOOL EMITS. Whether Anki's cloze scan reads the stored field before
    * or after entity decoding is UNVERIFIED here and not asserted anywhere.
    */
  test("a literal {{c1:: sequence in author text is emitted with its braces escaped") {
    assertEquals(rendered(Vector(p("{{c1::x}}"))), "<p>&#123;&#123;c1::x&#125;&#125;</p>")
  }

  /** TEST 11 — the tool's OWN deletion wrapper goes on AFTER escaping. That ordering, not a
    * remembered special case, is why a `<` inside a deletion cannot break the deletion: it is
    * already `&lt;` when the braces arrive.
    */
  test("a deletion wraps already-escaped inner content, braces intact") {
    assertEquals(Html.clozeDeletion(1, Html.escape("a < b")).render, "{{c1::a &lt; b}}")
  }

  /** TEST 12 — and the converse: after `Html.clozeDeletion`, the only bare `}}` in the fragment
    * is the tool's own closing wrapper. Author braces are entities.
    */
  test("author braces inside a deletion are entities, so the tool's wrapper is the only bare }}") {
    val f = Html.clozeDeletion(2, Html.escape("a mapN over }} two validations")).render
    assertEquals(f, "{{c2::a mapN over &#125;&#125; two validations}}")
    assertEquals(f.sliding(2).count(_ == "}}"), 1)
  }

  // ══════════════════════════════════════════════════════ emphasis ════

  test("emphasis and strong become <em> and <strong>") {
    val blocks = Vector(
      Block.Paragraph(
        Vector(
          Inline.Emphasis(Vector(Inline.Text("a"))),
          Inline.Strong(Vector(Inline.Text("b"))),
        )
      )
    )
    assertEquals(rendered(blocks), "<p><em>a</em><strong>b</strong></p>")
  }

  /** TEST 15b — rule 1 of the emptiness filter. `CodeSpan`, `Emphasis` and `Strong` are the
    * only inlines that can turn empty content into non-empty output, so each emits its tag only
    * when its inner is non-empty. Without this, `Paragraph(Vector(Emphasis(Vector.empty)))`
    * would render `<p><em></em></p>` where `AsText` renders `""`.
    */
  test("an empty emphasis, strong or code span emits no tag at all") {
    val blocks = Vector(
      Block.Paragraph(
        Vector(
          Inline.Text("x"),
          Inline.Emphasis(Vector.empty),
          Inline.Strong(Vector.empty),
          Inline.CodeSpan(""),
        )
      )
    )
    assertEquals(rendered(blocks), "<p>x</p>")
  }

  // ══════════════════════════════════════════════════════ ruled refusal B6 ════

  /** TEST 13 — B6 PRESERVATION, the sharpest trap in this slice.
    *
    * `Body.fromExtracted` is `raw.trim.isEmpty` (`model/CardSpec.scala:24-26`), so
    * `SpecError.EmptyBody` (ruled B6) fires on RENDERED emptiness. `<p></p>` is seven
    * non-whitespace characters: an UNCONDITIONAL `<p>` wrapper would stop B6 firing, and a
    * section holding only `%%a private comment%%` would ship as a card with a visually blank
    * Back. Nothing reddens that by itself — a more permissive tool makes MORE cards, not fewer
    * — which is why it is pinned here before anything calls this renderer.
    */
  test("B6: a structurally non-empty block that renders empty contributes NOTHING") {
    assertEquals(rendered(Vector(Block.Paragraph(Vector.empty))), "")
    assertEquals(rendered(Vector(Block.Bullets(Vector(Item(Vector.empty))))), "")
    assertEquals(rendered(Vector(Block.Bullets(Vector.empty))), "")
    assertEquals(
      rendered(
        Vector(
          Block.Table(
            header = Vector(Cell(Vector.empty), Cell(Vector.empty)),
            rows = Vector(Vector(Cell(Vector.empty), Cell(Vector.empty))),
          )
        )
      ),
      "",
    )
  }

  /** TEST 13b — the aggregate. For each of these, `AsHtml` renders empty exactly when `AsText`
    * does, which is what makes "B6 goes on firing" an argument rather than a hope.
    *
    * SCOPE, stated honestly: this compares the two renderers' EMPTINESS, over the shapes the
    * four-rule filter is responsible for. It says nothing about blocks whose rendered content
    * is whitespace-only — `AsText.text` ends in a `.trim` that has no counterpart in
    * `AsHtml.html`, and that divergence is NAMED, not closed, in `AsHtml.scala`.
    */
  test("B6 aggregate: AsHtml renders empty exactly when AsText does") {
    val cases: Vector[Vector[Block]] = Vector(
      Vector(Block.Paragraph(Vector.empty)),
      Vector(Block.Paragraph(Vector(Inline.Emphasis(Vector.empty)))),
      Vector(Block.Paragraph(Vector(Inline.Strong(Vector(Inline.CodeSpan("")))))),
      Vector(Block.Bullets(Vector(Item(Vector.empty)))),
      Vector(Block.Numbered(Vector.empty)),
      Vector(Block.Code(None, "")),
      Vector(Block.Table(Vector(Cell(Vector.empty)), Vector(Vector(Cell(Vector.empty))))),
      Vector(p("a")),
      Vector(Block.Bullets(Vector(li("a")))),
      Vector(Block.Code(Some("sql"), "select 1")),
      Vector(Block.Table(Vector(cell("h")), Vector(Vector(cell("v"))))),
    )
    cases.foreach { bs =>
      assertEquals(
        AsHtml.plain(bs).render.isEmpty,
        AsText.plain(bs).isEmpty,
        s"emptiness diverged for $bs (html=${AsHtml.plain(bs).render}, text=${AsText.plain(bs)})",
      )
    }
  }

  // ══════════════════════════════════════════════════════ the alignment invariant ════

  /** TEST 14 — THE CALLBACK-ALIGNMENT DIFFERENTIAL.
    *
    * `Cloze.render` (`extract/Cloze.scala:120-133`) assigns cloze ordinals by walking a
    * pre-computed iterator in step with the renderer's callbacks, and calls `misaligned` — a
    * `sys.error` — if the two ever fall out of step. That rests on a NAMED invariant of
    * `AsText.text`: one callback per `Inline.Deletion`, in document order, identically for any
    * hook, because the block-level filter runs AFTER the inner render returns.
    *
    * `AsHtml.html` must hold the SAME invariant. This test converts that from a prose claim
    * about two walks into evidence, BEFORE anything depends on it. Distinct labels are used so
    * ORDER is pinned and not merely count.
    *
    * The inner payloads are `String` on one side and `Html.Fragment` on the other, by design —
    * the two hook types are deliberately incompatible so pass 1 of `Cloze.fromLowered` cannot
    * be pointed at `AsHtml` by accident. So labels, count and order are what is compared.
    */
  test("AsText and AsHtml fire the same deletion callbacks, in the same order") {
    val blocks: Vector[Block] = Vector(
      // in a paragraph
      Block.Paragraph(Vector(Inline.Text("x "), Inline.Deletion(Some(1), Vector(Inline.Text("one"))))),
      // in a list item
      Block.Bullets(
        Vector(Item(Vector(Block.Paragraph(Vector(Inline.Deletion(Some(2), Vector(Inline.Text("two"))))))))
      ),
      // in a table cell — header cell first, then a body cell
      Block.Table(
        header = Vector(Cell(Vector(Inline.Deletion(Some(3), Vector(Inline.Text("three")))))),
        rows = Vector(Vector(Cell(Vector(Inline.Deletion(Some(4), Vector(Inline.Text("four"))))))),
      ),
      // nested inside an Emphasis
      Block.Paragraph(
        Vector(Inline.Emphasis(Vector(Inline.Deletion(Some(5), Vector(Inline.Text("five"))))))
      ),
      // sibling content renders empty — the filter must run AFTER the callback, not instead
      Block.Paragraph(
        Vector(Inline.Emphasis(Vector.empty), Inline.Deletion(Some(6), Vector(Inline.Text("six"))))
      ),
      // and the deletion's OWN inner renders empty, so the whole block is filtered away
      Block.Paragraph(Vector(Inline.Deletion(Some(7), Vector.empty))),
    )
    val expected = Vector(1, 2, 3, 4, 5, 6, 7).map(Some(_))
    assertEquals(labelsThroughText(blocks), expected)
    assertEquals(labelsThroughHtml(blocks), expected)
  }

  // ------------------------------------------------------------------ maths ----

  /** MATHS IS THE FIRST CONSTRUCT WHOSE FIELD SYNTAX IS NOT A TAG, and `Html.Tag` is closed
    * with no `span` in it, so the wrapper cannot be an element. It is emitted the way
    * `Html.clozeDeletion` is: characters placed around an ALREADY-ESCAPED fragment, so a `<`
    * inside the TeX is `&lt;` before the wrapper goes on rather than because anyone remembered.
    *
    * THE DELIMITERS ARE ANKI'S, NOT OBSIDIAN'S, and that asymmetry is the whole feature. Read
    * out of the config Anki ships at `_aqt/data/web/js/mathjax.js` in aqt 25.9.5: `displayMath`
    * is `\[…\]` and nothing else, and `inlineMath` is unset so MathJax's own `\(…\)` default
    * stands. `$$` is a delimiter to Anki in neither mode.
    */
  test("inline maths is wrapped in Anki's inline delimiters, not the author's dollars") {
    assertEquals(rendered(Vector(Block.Paragraph(Vector(Inline.MathInline("B^A"))))),
      """<p>\(B^A\)</p>""")
  }

  test("display maths is wrapped in Anki's display delimiters") {
    assertEquals(rendered(Vector(Block.Paragraph(Vector(Inline.MathDisplay("B^A"))))),
      """<p>\[B^A\]</p>""")
  }

  /** THE TeX IS ESCAPED LIKE ANY OTHER AUTHOR TEXT, and braces are the case that matters
    * because TeX is full of them. Escaping them is what keeps `\frac{\text{a}}{b}` from
    * putting a literal `}}` into a field that the cloze wrapper also uses — see the brace
    * argument in this file's header. A browser decodes the reference before MathJax reads the
    * text, so the maths still typesets; that half is reasoned and NOT yet measured against a
    * live collection, and `docs/MATHS-ON-A-CARD.md` records it as owed.
    */
  test("braces in TeX are escaped, exactly as they are in prose") {
    assertEquals(rendered(Vector(Block.Paragraph(Vector(Inline.MathInline("""\text{Id}"""))))),
      """<p>\(\text&#123;Id&#125;\)</p>""")
  }

  /** RULE 1, and it is not imitation. An empty `\(\)` is four characters of non-empty output
    * built from empty content, which is exactly what retires ruled refusal B6 — the same
    * argument the emphasis and highlight cases above make for their tags.
    */
  test("maths with no TeX in it contributes nothing, so it cannot revive an empty body") {
    assertEquals(rendered(Vector(Block.Paragraph(Vector(Inline.MathInline(""))))), "")
    assertEquals(rendered(Vector(Block.Paragraph(Vector(Inline.MathDisplay(""))))), "")
  }
