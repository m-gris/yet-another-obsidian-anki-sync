package obsidiananki.content

import laika.ast
import obsidiananki.extract.{CellDisplay, Frontmatter}
import obsidiananki.parser.ObsidianSyntax
import java.nio.file.{Files, Path, Paths}
import scala.jdk.CollectionConverters.*

/** The CELL projection, and two traps in the inline renderer.
  *
  * ==Two limits on the cell differential, written here because they change what it means==
  *
  * FIRST: the comparison can only reach the PUBLIC `CellDisplay.Default`, not the private
  * `Tables.cellSource` that the identity freeze actually names. Those two are verbatim copies
  * of one another today, deliberately (`Tables.scala:53-67` argues the duplication), and
  * NOTHING catches a drift between them — `Tables.scala:62-64` records that re-merging them
  * would leave its own guard green. So if they ever diverge, this test is measuring the wrong
  * one, and it would say nothing about identity. It is a display-path test only.
  *
  * SECOND: `AsText.cellDisplay`'s trailing `.trim` is PINNED BY NOTHING, exactly as
  * `Golden.test.scala`'s mutant M5 recorded for the function it copies. `LineSource.trim`
  * (`laika/parse/SourceCursor.scala:231-235`) is applied by `rowRest` to every cell BEFORE
  * span parsing, so no parser-produced cell carries edge whitespace and no differential over
  * parsed input can tell trim from no-trim. THIS TEST DOES NOT KILL M5. Saying otherwise would
  * be the eleventh untrue prose claim in this project.
  *
  * ==Why a hand-built value is allowed in the trap pins and nowhere else==
  *
  * The two trap pins below construct `Content` values directly. That is not the banned thing:
  * the ban is on hand-building a `laika.ast` value, because a hand-built Laika tree can encode
  * an input the parser cannot produce. `Content` values are this project's own closed algebra
  * and every shape in it is legal by construction.
  */
class AsTextSuite extends munit.FunSuite:

  /** The same upward walk `Golden.test.scala:278-287` uses; see `Lower.test.scala`. */
  lazy val vaultRoot: Path =
    Iterator
      .iterate(Paths.get(sys.props("user.dir")).toAbsolutePath)(_.getParent)
      .takeWhile(_ != null)
      .take(6)
      .flatMap(dir =>
        Iterator(dir.resolve("dummy-vault"), dir.resolve("obsidian-anki-custom-sync/dummy-vault"))
      )
      .find(Files.isDirectory(_))
      .getOrElse(fail("dummy-vault not found from " + sys.props("user.dir")))

  def parse(markdown: String): ast.RootElement =
    ObsidianSyntax.markupParser
      .parse(markdown)
      .fold(e => fail(s"snippet did not parse: $e"), _.content)

  /** Every `ast.Table` anywhere in the tree.
    *
    * `Table` is named explicitly because it is NOT an `ElementContainer` and has no `content`
    * member — the defect that made a whole table vanish from a card. Its head and body ARE
    * containers, which is why everything below them is reached generically.
    */
  def tablesIn(element: ast.Element): Vector[ast.Table] = element match
    case t: ast.Table               => Vector(t)
    case ec: ast.ElementContainer[?] => ec.content.toVector.flatMap(tablesIn)
    case _                           => Vector.empty

  test("every dummy-vault table cell displays identically through the lowering") {
    val files = Files
      .walk(vaultRoot)
      .iterator
      .asScala
      .filter(p => p.toString.endsWith(".md"))
      .toVector
      .sortBy(_.toString)

    val tables = files.flatMap { p =>
      val (_, body) =
        Frontmatter.read(Files.readString(p)).fold(e => fail(s"$p frontmatter: $e"), identity)
      parse(body).content.toVector.flatMap(tablesIn)
    }
    assert(tables.nonEmpty, s"no table found under $vaultRoot")

    var checked = 0
    tables.foreach { t =>
      val cells = (t.head.content ++ t.body.content).flatMap(_.content)
      cells.foreach { c =>
        assertEquals(
          Lower.cell(c).map(AsText.cellDisplay),
          Right(CellDisplay.Default.text(c)),
          s"cell display diverged: $c",
        )
        checked += 1
      }
    }
    assert(checked >= 20, s"only $checked cells swept; the fixture holds more")
  }

  /** The RAGGED case, which `dummy-vault` does not contain.
    *
    * `laika/internal/markdown/github/Tables.scala` `applyColumnOptions` pads a short row with
    * `CellType.BodyCell.empty` — a cell holding ZERO blocks. That is why `Lower.cell` needs
    * both a one-paragraph arm and an empty arm, and why the brief's "a GFM table cell is
    * ALWAYS exactly Seq(Paragraph(spans))" is overstated: the honest form is "exactly one
    * Paragraph, or nothing".
    */
  test("a padded cell in a ragged table displays identically through the lowering") {
    val tables = parse("| head one | head two |\n| --- | --- |\n| only one cell |\n").content.toVector
      .flatMap(tablesIn)
    assertEquals(tables.size, 1, "expected exactly one table")

    val cells = (tables.head.head.content ++ tables.head.body.content).flatMap(_.content)
    val empty = cells.filter(_.content.isEmpty)
    assert(empty.nonEmpty, s"expected a zero-block padded cell, got ${cells.map(_.content.size)}")

    cells.foreach { c =>
      assertEquals(
        Lower.cell(c).map(AsText.cellDisplay),
        Right(CellDisplay.Default.text(c)),
        s"cell display diverged: $c",
      )
    }
  }

  /** TRAP ONE. `Inline.CodeSpan` READS as though it would emit backticks, and a future author
    * will "fix" it into a golden diff. It must not: `laika.ast.Literal` is a `TextContainer`
    * and `SpanContainer.extractText` matches `TextContainer` first, so `` `foo` `` is `foo`
    * today, with no backticks anywhere.
    */
  test("TRAP: a code span renders without backticks") {
    assertEquals(AsText.plain(Vector(Block.Paragraph(Vector(Inline.CodeSpan("foo"))))), "foo")
  }

  /** TRAP TWO. `Block.Code` CARRIES its language because Laika really reports it — but nothing
    * renders it, and today's output contains no language tag. A carried-but-unrendered field
    * looks like an omission; it is a deliberate one, kept so a later HTML slice can use it.
    */
  test("TRAP: a code block's language is carried but not rendered") {
    assertEquals(AsText.plain(Vector(Block.Code(Some("sql"), "select 1"))), "select 1")
  }
