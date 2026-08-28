package obsidiananki.extract

import cats.data.NonEmptyVector
import laika.ast.*
import obsidiananki.content as C
import obsidiananki.model.*
import obsidiananki.parser.ObsidianSyntax

/** WHAT AN OUTLINE HAS TO GUARANTEE.
  *
  * WRITTEN BEFORE THE FOLD EXISTED, against `???`, so every assertion here failed once for the
  * right reason before it passed for any reason.
  *
  * THE ORDER OF THIS FILE IS THE ORDER OF THE ARGUMENT. The reading of laika's tree comes first
  * and is checked against markdown a person can read; the building of blocks comes second and is
  * checked against trees a person can write. Neither half is asserted through the other, which is
  * the whole reason they are separate functions.
  */
class OutlineTest extends munit.FunSuite:

  /** A DOCUMENT WITH EVERY SHAPE THAT MATTERS, once each.
    *
    *   - a marked heading with three children, so ORDER can be asserted;
    *   - one of those children has children of its own, so DEPTH is present;
    *   - a SIBLING of the marked heading, which must never appear;
    *   - a child carrying a MARKER OF ITS OWN, which makes its own card and must still appear
    *     here as plain text.
    */
  private val fixture: String =
    """|# Doc
       |
       |## Request lifecycle #flashcard/sequence/headers
       |
       |### Parse #flashcard/2way
       |
       |Some prose under Parse.
       |
       |#### Tokenise
       |
       |#### Build tree
       |
       |### Route
       |
       |### Respond
       |
       |## Elsewhere
       |
       |### Not mine
       |""".stripMargin

  /** Collected the way `Tables.test.scala` collects one — concretely, `case s: Section`, never a
    * trait, because laika's `Block` is an open hierarchy and a match over it proves nothing.
    */
  private def marked(markdown: String): Section =
    val root = ObsidianSyntax.markupParser.parse(markdown).fold(e => fail(s"parse: $e"), _.content)
    def every(s: Section): Vector[Section] =
      s +: s.content.collect { case c: Section => c }.toVector.flatMap(every)
    root.content
      .collect { case s: Section => s }
      .toVector
      .flatMap(every)
      .find(_.header.extractText.contains("#flashcard/sequence/headers"))
      .getOrElse(fail(s"no marked section in:\n$markdown"))

  /** THE ORACLE FOR THE CONSERVATION LAW, and it walks LAIKA rather than calling `read`.
    *
    * A law checked against the thing it is a law about proves nothing. This descends the parse
    * tree independently, so "every heading beneath the marked one" is computed twice by two
    * different routes and the test is the claim that they agree.
    */
  private def headingsBeneath(section: Section): Vector[String] =
    val kids = section.content.collect { case c: Section => c }.toVector
    kids.flatMap(k => Marker.stripMarker(k.header.extractText.trim) +: headingsBeneath(k))

  private def node(title: String, children: HeadingNode*): HeadingNode =
    HeadingNode(title, children.toVector)

  private def tree(nodes: HeadingNode*): NonEmptyVector[HeadingNode] =
    NonEmptyVector.fromVectorUnsafe(nodes.toVector)

  // ══════════════════════════════════════════ reading laika's tree ══════════════════════════

  test("the direct children are the items, in document order") {
    assertEquals(
      Outline.read(marked(fixture), HeadingReach.DirectChildren).map(_.title),
      Vector("Parse", "Route", "Respond"),
    )
  }

  /** DIRECT MEANS DIRECT. `Tokenise` sits under `Parse`, not under the marked heading, so it is
    * not an item of this card — the author asked for one level and gets one level.
    */
  test("direct children carry no children of their own") {
    val read = Outline.read(marked(fixture), HeadingReach.DirectChildren)
    assertEquals(read.flatMap(_.children), Vector.empty, "a deeper heading leaked into the flat form")
  }

  test("the whole subtree nests, and keeps document order at every level") {
    assertEquals(
      Outline.read(marked(fixture), HeadingReach.WholeSubtree),
      Vector(
        node("Parse", node("Tokenise"), node("Build tree")),
        node("Route"),
        node("Respond"),
      ),
    )
  }

  /** THE MARKED HEADING IS THE CARD'S TITLE FIELD, printed above the list by the note type's own
    * template. An outline containing it would print it twice.
    */
  test("the marked heading never appears inside its own outline") {
    HeadingReach.values.foreach: reach =>
      assert(
        !Outline.read(marked(fixture), reach).flatMap(_.titles).contains("Request lifecycle"),
        s"the marked heading appeared as one of its own items, at $reach",
      )
  }

  /** A SECTION'S BODY STOPS AT THE NEXT HEADING OF ANY LEVEL — ruled B6 — and the same boundary
    * governs this. `Elsewhere` is a SIBLING of the marked heading, not a child of it.
    */
  test("a sibling of the marked heading, and its children, are not items") {
    val titles = Outline.read(marked(fixture), HeadingReach.WholeSubtree).flatMap(_.titles)
    assert(!titles.contains("Elsewhere"), s"a sibling heading became an item: $titles")
    assert(!titles.contains("Not mine"), s"a sibling's child became an item: $titles")
  }

  /** CONSERVATION — NO MIRACLES AND NO BLACK HOLES.
    *
    * Every heading beneath the marked one appears exactly once, and nothing else appears at all.
    * This is the assertion that catches a fold silently dropping a branch AND one duplicating a
    * subtree, which are the two failures this shape is prone to and which no example-based test
    * reliably notices.
    */
  test("the whole subtree conserves headings exactly — none lost, none invented, none doubled") {
    val section = marked(fixture)
    assertEquals(
      Outline.read(section, HeadingReach.WholeSubtree).flatMap(_.titles).sorted,
      headingsBeneath(section).sorted,
    )
  }

  /** THE TWO REACHES ARE ONE FOLD OVER DIFFERENTLY PRUNED TREES, so they cannot be free to
    * disagree: the flat form is exactly the nested form with its branches cut.
    */
  test("the flat reach is the nested reach truncated to one level") {
    val section = marked(fixture)
    assertEquals(
      Outline.read(section, HeadingReach.DirectChildren),
      Outline.read(section, HeadingReach.WholeSubtree).map(_.copy(children = Vector.empty)),
    )
  }

  /** A SUBHEADING MAY CARRY ITS OWN MARKER, and `### Parse #flashcard/2way` in the fixture does.
    * It still makes its own two-field card; here it is an item, and the item is the text without
    * the marker.
    */
  test("a child's own marker is stripped from the item text") {
    val titles = Outline.read(marked(fixture), HeadingReach.WholeSubtree).map(_.title)
    assert(titles.contains("Parse"), s"expected the stripped heading among $titles")
    assert(!titles.exists(_.contains("#flashcard")), s"a marker reached the card face: $titles")
  }

  test("a heading with no subheadings reads as no items, rather than failing") {
    val none = marked("""|# Doc
                         |
                         |## Alone #flashcard/sequence/headers
                         |
                         |Just prose.
                         |""".stripMargin)
    HeadingReach.values.foreach(r => assertEquals(Outline.read(none, r), Vector.empty, s"at $r"))
  }

  // ══════════════════════════════════════════ building the blocks ═══════════════════════════

  /** Every `Item` anywhere in the rendered blocks, however deep. */
  private def itemsIn(blocks: Vector[C.Block]): Int = blocks.map {
    case C.Block.Bullets(items)  => items.map(i => 1 + itemsIn(i.blocks)).sum
    case C.Block.Numbered(items) => items.map(i => 1 + itemsIn(i.blocks)).sum
    case _                       => 0
  }.sum

  /** THE SHAPE IS THE ONE A HAND-WRITTEN LIST ALREADY LOWERS TO, which is what buys this feature
    * its renderer and its template for free. Asserted concretely, once, so that a change to it is
    * a change somebody had to make on purpose.
    */
  test("one heading becomes one bullet holding one paragraph") {
    assertEquals(
      Outline.render(tree(node("Parse"))),
      C.Block.Bullets(Vector(C.Item(Vector(C.Block.Paragraph(Vector(C.Inline.Text("Parse"))))))),
    )
  }

  test("a heading with children becomes a bullet holding its own paragraph and a nested list") {
    assertEquals(
      Outline.render(tree(node("Parse", node("Tokenise")))),
      C.Block.Bullets(
        Vector(
          C.Item(
            Vector(
              C.Block.Paragraph(Vector(C.Inline.Text("Parse"))),
              C.Block.Bullets(
                Vector(C.Item(Vector(C.Block.Paragraph(Vector(C.Inline.Text("Tokenise"))))))
              ),
            )
          )
        )
      ),
    )
  }

  /* THE TEST THAT USED TO BE HERE IS GONE, AND ITS ABSENCE IS THE POINT.
   *
   * It asserted that rendering no headings produced no blocks. `render` now takes a
   * `NonEmptyVector` and returns one definite `Bullets`, so that call no longer COMPILES — the
   * state it guarded stopped being representable rather than stopped being wrong. Deciding
   * emptiness is the caller's single parse step:
   *
   *     NonEmptyVector.fromVector(Outline.read(section, reach))
   *       .toRight(SpecError.SequenceWithoutItems(where, "the heading has no subheadings"))
   *       .map(Outline.render)
   *
   * `read` returning an empty vector is still tested above — that IS a legitimate answer, and a
   * different question from "render nothing".
   */

  /** CONSERVATION AGAIN, ON THE OTHER HALF. One heading anywhere in the tree becomes exactly one
    * bullet anywhere in the blocks — which is also the property the note type depends on, since
    * its front template hides one `li` per item and reveals them one at a time.
    */
  test("every heading in the tree becomes exactly one bullet, at any depth") {
    val nodes = tree(node("Parse", node("Tokenise"), node("Build tree", node("Deeper"))), node("Route"))
    assertEquals(itemsIn(Vector(Outline.render(nodes))), nodes.toVector.flatMap(_.titles).size)
  }
