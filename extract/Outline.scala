package obsidiananki.extract

import cats.data.NonEmptyVector
import laika.ast.*
import obsidiananki.content as C
import obsidiananki.model.{HeadingReach, Marker}

/** ONE HEADING, AND THE HEADINGS BENEATH IT.
  *
  * A ROSE TREE, WHICH IS WHAT BOTH SIDES OF THIS PROBLEM ALREADY ARE. A section is a heading
  * plus nested sections; a bullet item is blocks, one of which may be a list of further items.
  * Turning one into the other is therefore a structure-preserving map over the recursive
  * positions, not a conversion, and saying so in a type keeps it that way.
  *
  * IT EXISTS SO THE FOLD DOES NOT DEPEND ON LAIKA, for the reason `content/Content.scala`
  * already gives about its own closed algebra: laika's `Block` is an OPEN hierarchy, so a match
  * over it can never be exhaustive and the compiler can never confirm a case was handled. This
  * type is closed and holds exactly what an outline needs — a title and its children — which
  * also means every law below can be stated over values a test writes by hand rather than over
  * a parse tree it has to construct.
  *
  * THE TITLE IS ALREADY STRIPPED OF ITS MARKER when a node is built. A subheading may carry a
  * marker of its own (`### Parse #flashcard/2way` both makes its own card AND appears as an item
  * here), and the text shown must be the heading without it.
  */
final case class HeadingNode(title: String, children: Vector[HeadingNode]):

  /** Every title in this subtree, including this node's own, in document order.
    *
    * FOR THE CONSERVATION LAW rather than for production. A fold that silently drops a branch is
    * the failure this shape is prone to, and comparing what went in against what came out is the
    * only assertion that catches losing and duplicating at the same time.
    */
  def titles: Vector[String] = title +: children.flatMap(_.titles)

/** A HEADING'S SUBHEADINGS, AS THE ORDERED ITEMS OF ONE SEQUENCE CARD.
  *
  * WHAT THIS IS FOR. `#flashcard/sequence/headers` makes the STRUCTURE of a document the thing
  * recalled — a table of contents you can be tested on — rather than only the scaffolding that
  * other cards hang off. Filed as `IN-FLIGHT.md` item 28, requested by Marc 2026-08-27.
  *
  * IT ADDS NO RENDERING CODE, WHICH IS THE STRONGEST FACT ABOUT IT. The blocks built here are
  * the SAME `Block.Bullets` a hand-written markdown list already lowers to, so `AsHtml` emits
  * the same `<ul><li>` it always did and the note type's front template — which hides `#text li`,
  * a DESCENDANT selector — hides them at every depth with no change. The nested form works for
  * the same reason, not by a second mechanism.
  *
  * TWO STEPS, NOT ONE, AND THE SPLIT IS THE POINT. [[read]] understands laika; [[render]] builds
  * blocks. Neither knows what the other is for, so the fold can be tested on hand-written trees
  * and the laika reading can be tested on parsed markdown, instead of every assertion having to
  * go through both.
  */
object Outline:

  /** THE HEADING TREE BENEATH A SECTION, as far as `reach` allows.
    *
    * THE SECTION'S OWN HEADING IS NOT IN THE RESULT, and that is a rule rather than an accident:
    * the marked heading becomes the card's `Title` field, printed above the list by the note
    * type's own `<h4>{{Title}}</h4>`, so an outline containing it would print it twice. The same
    * reasoning already governs the breadcrumb on this card — see the sequence arm in
    * `Extractor.scala`, which excludes the marked title from the context for this exact reason.
    *
    * EMPTY IS A LEGITIMATE ANSWER HERE, NOT AN ERROR. A heading with no subheadings under a
    * marker asking for subheadings is a refusal the CALLER makes, with the heading path in hand
    * to name in the message. This function reports what is there.
    */
  def read(section: Section, reach: HeadingReach): Vector[HeadingNode] =
    // THE TWO REACHES ARE WRITTEN SEPARATELY ON PURPOSE, though one is visibly the other with
    // its branches cut. Implementing the flat form AS `deep(...).map(_.copy(children = empty))`
    // would make the law asserting exactly that TAUTOLOGICAL — it would hold by construction
    // and could never catch the two drifting apart. Written independently, the law is a claim
    // about two pieces of code rather than a restatement of one.
    reach match
      case HeadingReach.DirectChildren => childrenOf(section).map(s => HeadingNode(titleOf(s), Vector.empty))
      case HeadingReach.WholeSubtree   => childrenOf(section).map(deep)

  /** The nested form: a heading, and the same treatment applied to each heading below it.
    *
    * STRUCTURAL RECURSION WITH NOTHING GUARDING IT, AND THAT IS SAFE HERE RATHER THAN OPTIMISTIC.
    * The tree comes from a parsed file, so it is finite; and markdown admits at most six heading
    * levels, so the depth is bounded by the GRAMMAR rather than by a convention somebody has to
    * keep. No fuel parameter, no trampoline, no `Eval`.
    */
  private def deep(section: Section): HeadingNode =
    HeadingNode(titleOf(section), childrenOf(section).map(deep))

  /** The sections DIRECTLY inside this one.
    *
    * CONCRETELY, `case c: Section`, NEVER A TRAIT. Laika's `Block` is an open hierarchy, so this
    * match can never be exhaustive and the compiler cannot confirm it — the same reason
    * `ownBody` in `Extractor.scala` and `sectionOf` in `Tables.test.scala` are written this way.
    * Laika nests a heading inside the nearest shallower one, so "directly inside" is already the
    * parse tree's own answer and nothing here re-derives it from heading levels.
    */
  private def childrenOf(section: Section): Vector[Section] =
    section.content.collect { case c: Section => c }.toVector

  /** The heading as the author wrote it, without its marker.
    *
    * THROUGH `Marker.stripMarker`, WHICH IS THE SAME FUNCTION THE KEY DERIVATION USES, not a
    * second regex that agrees with it today. A subheading may carry a marker of its own and
    * still be an item here, so this path is reached in ordinary use rather than at an edge.
    */
  private def titleOf(section: Section): String =
    Marker.stripMarker(section.header.extractText.trim)

  /** THE BULLET LIST A HEADING TREE BECOMES.
    *
    * IT TAKES A NON-EMPTY VECTOR AND RETURNS ONE DEFINITE BLOCK, so "what if there is nothing to
    * render?" is not a question anybody downstream can ask. The emptiness is decided ONCE, by the
    * caller, as a PARSE — `NonEmptyVector.fromVector(read(...)).toRight(refusal)` — rather than by
    * this function checking and then everyone after it checking again. Parse, do not validate.
    *
    * WHY THIS SHAPE AND NOT THE ONE `CardSpec.Sequence` WAS FORCED INTO. That type carries the
    * same invariant and CANNOT express it: `model/` imports nothing from the rest of this project,
    * so `Item` is not reachable there, and its docstring says outright that a value built by hand
    * "can hold anything at all". That defeat is a LAYERING rule, and it does not reach here —
    * `extract/` may see `content/`. Where the type system can hold the invariant, it holds it.
    *
    * AND WHERE IT STOPS, WHICH IS WORTH KNOWING. One step further on, these blocks are rendered
    * into a card field that is a STRING. No type survives that, which is precisely why the
    * existing sequence refusal gates on the RENDERER rather than on a type. Types up to the
    * render boundary; a checked gate past it.
    */
  def render(nodes: NonEmptyVector[HeadingNode]): C.Block.Bullets =
    C.Block.Bullets(nodes.toVector.map(bullet))

  /** One heading as one bullet: its own text, then whatever list its children make.
    *
    * MUTUALLY RECURSIVE WITH [[render]], AND THE LEAF CASE NEEDS NO BRANCH — `render` of no
    * children is no blocks, so a leaf item holds its paragraph and nothing else without this
    * function testing for it.
    *
    * THE TITLE IS NOT ESCAPED HERE, AND MUST NOT BE. It enters as `Inline.Text`, and
    * `AsHtml` escapes that constructor itself (`content/AsHtml.scala:582`), so escaping it here
    * would double-escape a heading containing `&` or `<` — `A &amp;amp; B` on the card face.
    * This differs from the titles handed to `CardSpec` as raw strings in `Extractor.scala`,
    * which ARE escaped in the argument position because nothing downstream will do it for them;
    * the rule is the same one either way, applied once each.
    *
    * `Block.Bullets` RATHER THAN `Block.Numbered`, though the items are ordered. The note type's
    * templates select `#text li`, which both produce; the reveal is what carries the order, and
    * printing numbers beside items that appear one at a time would number them against the
    * order they are revealed in.
    */
  private def bullet(node: HeadingNode): C.Item =
    // A LEAF HAS NO CHILDREN, AND THAT IS NOT THE SAME QUESTION `render` REFUSED TO ANSWER.
    // "This heading has nothing under it" is ordinary; "an outline with no headings at all" is
    // the state the caller already ruled out. Turning the first into an `Option` keeps them
    // distinct instead of collapsing both into an empty vector.
    val nested = NonEmptyVector.fromVector(node.children).fold(Vector.empty[C.Block])(kids => Vector(render(kids)))
    C.Item(C.Block.Paragraph(Vector(C.Inline.Text(node.title))) +: nested)
