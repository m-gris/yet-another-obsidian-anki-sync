package obsidiananki.extract

import cats.data.NonEmptyVector
import obsidiananki.anki.DeckPath
import obsidiananki.model.*
import obsidiananki.plan.*
import java.nio.file.{Files, Path, Paths}
import scala.jdk.CollectionConverters.*

/** A GOLDEN PIN of everything the fixture vault turns into today, so that a change to how
  * text is rendered arrives as a diff a human reads rather than as a surprise in a real
  * collection.
  *
  * ==What this exists to catch==
  *
  * A card's identity is a tag stored in Anki and derived from the markdown: `(frontmatter
  * id, heading path)`, extended for a table card with the row concept and the column header.
  * Change how text is RENDERED and you may change what a card IS. That is not an update to a
  * card — it is an orphan plus a brand-new card with no review history. Roughly half of this
  * fixture's cards key off table cell text. Orphan suspension is ruled but NOT BUILT, so
  * such an orphan also stays in the daily review rotation with only a tag to show for it.
  *
  * `Planner.contentHash` makes the quiet case quieter still: an `Update` is emitted only when
  * the recorded `sha::` tag differs, so a field whose text is byte-identical produces no
  * action at all. Nothing downstream of extraction reports "the same text arrived by a
  * different route".
  *
  * ==What is pinned, and what is deliberately not==
  *
  * PINNED, for every `SourcedSpec` the whole vault produces: the encoded `src::` identity
  * tag, the note type name, and every (field name, field value) pair in field order. Plus,
  * for every `BuildFailure`: its case name and its single identifying value.
  *
  * NOT PINNED: the deck (`VaultIndex.deckOf` falls back to the root deck, so pinning it
  * could pin the DEFAULT rather than the real deck — "could not look" reading as "nothing
  * there"); the `sha::` content hash (derived from exactly the triple above, so it is a
  * column that trains the reader to skip lines); the source file and line (see the C1 mutant
  * below — provenance is out precisely because a control mutant must survive); and the
  * failure `reason` strings (they are `Extractor.describe` prose).
  *
  * ==Construction rules that are load-bearing==
  *
  *   - The spec record is built from `spec.key`, `spec.noteTypeName` and `spec.fields` ONLY.
  *     There is deliberately NO `match` on `CardSpec` in this file, so a fifth variant cannot
  *     be silently omitted from the golden — the existing exhaustive matches inside `CardSpec`
  *     carry it, and an inexhaustive match is a build ERROR here (`-Wconf:msg=exhaustive:e`).
  *   - The failure record DOES match exhaustively on `BuildFailure`, so a fourth failure case
  *     is a compile error rather than a silently unpinned record.
  *   - No `Map[CardKey, _]`, no `.toSet` over keys, no `groupBy` on the key alone: the fixture
  *     contains THREE DELIBERATE duplicate identities (`Patterns/Table-Edge-Cases.md`, two
  *     `Retry` rows) and they must survive as separate records. They are the densest
  *     `Tables.cellText` coverage in the fixture.
  *   - ESCAPING IS ONE-WAY. Comparison happens over escaped strings on BOTH sides and nothing
  *     in this file unescapes. There is no inverse function, so there is none to drift out of
  *     step with the escaper — the `Cloze.collectHighlights` vs `renderWithDeletions` failure
  *     mode this project already has a scar from.
  *
  * ==The duplicated vault walk==
  *
  * `vaultRoot` and `loadVault` below duplicate `FixtureVaultTest`. That duplication is
  * FORCED, not chosen: this slice may not modify `FixtureVault.test.scala` and may not add a
  * production file. Two walks over one tree drift.
  *
  * ==What this net was MEASURED to catch, and measured NOT to catch==
  *
  * Every row below was RUN on 2026-08-20, against this file and this golden, by mutating the
  * production source, running the whole suite, and reverting. None of it is a prediction.
  * "suite" means every other test in the project; the golden's own three tests are named
  * separately.
  *
  * {{{
  * mutant                                                    golden      rest of the suite
  * ------------------------------------------------------------------------------------------
  * M1 Extractor.elementText, ElementContainer branch:        FAILS       all green
  *    mkString("\n") -> mkString("|")                        (content)
  * M2 Extractor.bodyText, block join:                        FAILS       all green
  *    mkString("\n") -> mkString("|")                        (content)
  * M3 Cloze.elementRender, SpanContainer branch:             FAILS       3 ExtractorTest cases
  *    mkString -> mkString("|")                              (content)   also fail
  * C1 LineIndex.lineOf returns 0 unconditionally             ALL GREEN   ExtractorTest "B10:
  *    (the CONTROL)                                                      heading positions are
  *                                                                       real line numbers" and
  *                                                                       FixtureVaultTest's
  *                                                                       ".md:" assertion fail
  * M4 Tables.cellText: mkString(" ") -> mkString("@@")       ALL GREEN   all green
  * M5 Tables.cellText: trailing .trim removed                ALL GREEN   all green
  * }}}
  *
  * M1 and M2 were caught by NOTHING ELSE IN THE PROJECT. That is the case this file exists
  * for: a rendering change that alters a card's text, produces no error, and — because
  * `Planner.contentHash` only emits an `Update` when the recorded `sha::` differs — would
  * have gone to a real collection as a silent rewrite.
  *
  * C1 is the control, and it must stay one. A net that catches every mutant proves only that
  * the harness fails on everything. C1 changes a `SourceRef` line number, which reaches
  * `SourcedSpec.source` and never `spec.key` or `spec.fields` — so it is invisible here BY
  * DESIGN, and that is precisely why source provenance is excluded from the record. It still
  * breaks its own two dedicated tests, so it is a mutant something catches, not a dead one.
  *
  * ==Measured: `Tables.cellText` is pinned by OUTPUT VALUE ONLY==
  *
  * M4 and M5 SURVIVED — the whole suite, this golden included, stayed green. Stated plainly,
  * because a reader will otherwise assume this file covers the table path completely: THE
  * GOLDEN PINS `Tables.cellText`'s OUTPUT VALUE ONLY. Its `mkString(" ")` separator and its
  * `.trim` are unreachable through Laika's GFM parser and are pinned by NOTHING; only the
  * `sc.extractText` inside it is observable.
  *
  * The mechanism, read out of laika-core 1.3.2 rather than inferred:
  * `laika/internal/markdown/github/Tables.scala:38` builds every cell as
  * `Cell(cellType, Seq(Paragraph(spans)))` — one block, unconditionally — and
  * `applyColumnOptions` pads a short row with `CellType.BodyCell.empty`, which has zero
  * blocks. A one-or-zero-element `mkString(sep)` emits no separator for ANY `sep`. And
  * `laika/parse/SourceCursor.scala:231-235` `LineSource.trim` calls `input.trim`, applied by
  * `rowRest` to every cell BEFORE span parsing, so there is no edge whitespace left for
  * `cellText`'s own `.trim` to remove.
  *
  * This matters beyond the golden: `cellText`'s `mkString(" ")` READS as "handles multi-block
  * cells" and cannot, and the planned lowering will be written by someone reading that line
  * as a specification. The honest remedy is a production change — match the single
  * `Paragraph` and fail loudly otherwise — and production is out of this slice.
  *
  * ==Named gaps: reachable in markdown, absent from this fixture==
  *
  * These join sites are NOT covered, and the reason is the fixture rather than the parser.
  * Closing them needs two small `dummy-vault` additions — a `#flashcard/cloze` section
  * containing a list or two paragraphs, and a `#flashcard/2way` section containing a GFM
  * table:
  *
  *   - `Extractor.elementText`'s `case t: Table` join — no table sits under a non-table
  *     marker anywhere in the vault.
  *   - `Cloze.elementRender`'s `Table` join, its `ElementContainer` join, and its final block
  *     join — all four cloze bodies in the fixture are a single paragraph.
  *
  * No synthetic input is added here to close them, deliberately. A hand-built multi-block
  * `Cell` would pin an input Laika's GFM parser cannot produce, which would make dead code
  * harder to delete and would actively BLOCK the planned lowering from making multi-block
  * cells unrepresentable — a net fighting the refactor it exists to protect.
  */
class GoldenTest extends munit.FunSuite:

  // ================================================ the header prose ====

  /** The data file's header, held HERE rather than only in the data file, so that a hand `cp`
    * of the rendered actual output reproduces the warning it carries instead of erasing it.
    *
    * The assertion further down compares only the FIRST line of the data file against the
    * first line of this constant. Its value is narrow and worth stating narrowly: it stops
    * the DO-NOT-REGENERATE warning being silently deleted from the checked-in file. It does
    * not stop a blind regeneration — that is the job of the count literals in this source.
    */
  val HeaderProse: Vector[String] = Vector(
    "# DO NOT REGENERATE THIS FILE TO MAKE A FAILING TEST PASS. READ THE DIFF FIRST.",
    "#",
    "# For every card the fixture vault produces today: its src:: identity tag, its Anki note",
    "# type, and every field name and value. A card's identity is a tag stored in Anki and",
    "# derived from the markdown, so a change to how text is RENDERED can change what a card",
    "# IS — not an update to a card but an orphan plus a brand-new card with no review",
    "# history. Orphan suspension is ruled but NOT BUILT, so such an orphan also stays in the",
    "# daily review rotation with only a tag to show for it.",
    "#",
    "# There is deliberately NO code in this repository that writes this file. When a test",
    "# fails it renders the actual output to a temp file and names its absolute path; adopting",
    "# it is a human `cp` AFTER reading the printed delta.",
    "#",
    "# ORDER IS EMISSION ORDER AND MUST NOT BE SORTED. VaultWalker.scan already sorts files by",
    "# relative path and document order within a file is fixed, so this is deterministic across",
    "# runs and machines without a comparator. Sorting would retire what emission order",
    "# additionally pins: spec order is Planner action order is Anki note-creation order is",
    "# new-card introduction position. It also keeps the two deliberate Retry rows adjacent and",
    "# in row order, and it makes a heading rename read as an adjacent -old-tag/+new-tag pair —",
    "# the orphan signature — instead of a deletion and an unrelated addition far apart.",
    "#",
    "# Every emitted value is wrapped in U+27E6/U+27E7 brackets so that leading and trailing",
    "# whitespace is visible BY POSITION, and escaped so that an invisible character is visible",
    "# at all. Escaping is one-way: nothing ever unescapes this file.",
  )

  // ================================================ escaping ====

  /** Character categories whose members are escaped to `\\uXXXX`.
    *
    * A RULE OVER THE WHOLE OF UNICODE rather than a hand-list of invisibles. A hand-list is a
    * bet on which invisibles exist — the same shape as matching a trait and betting on what
    * else implements it, which this project has a scar from. This set covers NBSP, NNBSP,
    * ZWSP, ZWJ/ZWNJ, soft hyphen, BOM, DEL and all of C0/C1, including the ones nobody
    * thought of.
    *
    * SURROGATE is in the set, so a supplementary-plane character escapes as its two surrogate
    * halves. That is lossless and unambiguous, and it is why this is not a code-point walk.
    */
  private val EscapedTypes: Set[Int] = Set(
    Character.CONTROL,
    Character.FORMAT,
    Character.SURROGATE,
    Character.UNASSIGNED,
    Character.PRIVATE_USE,
    Character.LINE_SEPARATOR,
    Character.PARAGRAPH_SEPARATOR,
    Character.SPACE_SEPARATOR,
  ).map(_.toInt)

  /** `Locale.ROOT` is not decoration: `Formatter`'s `%x` localises its digits under a locale
    * with non-ASCII numerals, which would make this file's escapes depend on where it was
    * generated.
    */
  private def hexEscape(c: Char): String =
    String.format(java.util.Locale.ROOT, "\\u%04x", Integer.valueOf(c.toInt))

  /** PRINTABLE NON-ASCII STAYS LITERAL, because readability of the diff is the deliverable.
    * The fixture's only non-ASCII characters are em-dashes (U+2014), one of them inside a
    * `Patterns/Messaging.md` table cell; escaping them would buy no invisibility and cost
    * legibility.
    */
  private def escape(raw: String): String =
    val out = new StringBuilder
    var i   = 0
    while i < raw.length do
      val c = raw.charAt(i)
      if c == '\\' then out.append("\\\\")
      else if c == '⟦' || c == '⟧' then out.append(hexEscape(c))
      else if c == '\n' then out.append("\\n")
      else if c == '\r' then out.append("\\r")
      else if c == '\t' then out.append("\\t")
      // Before the category test, which would otherwise catch it as SPACE_SEPARATOR. An
      // ordinary space must stay a space or every line becomes unreadable.
      else if c == ' ' then out.append(' ')
      else if EscapedTypes.contains(Character.getType(c)) then out.append(hexEscape(c))
      else out.append(c)
      i += 1
    out.toString

  /** For the MISMATCH REPORT only: force every non-ASCII character in an already-escaped
    * string to `\\uXXXX`, so that an NFC-to-NFD change or a homoglyph — which differ as
    * strings but look identical in a diff — is VISIBLE rather than merely detected.
    */
  private def forceAscii(escaped: String): String =
    escaped.map(c => if c.toInt < 0x80 then c.toString else hexEscape(c)).mkString

  // ================================================ the record shapes ====

  /** Every string in these two records is ALREADY ESCAPED. Nothing unescapes. */
  final case class CardRecord(tag: String, noteType: String, fields: Vector[(String, String)])
  final case class FailRecord(caseName: String, value: String)

  /** A record's position for pairing: its tag plus how many records with that same tag came
    * before it in emission order. Total over both sides, uniform for multiplicity 1 and >1,
    * and it needs no special case for the fixture's three deliberate duplicate identities.
    */
  final case class Slot(tag: String, ordinal: Int)

  private def slotted(cards: Vector[CardRecord]): Vector[(Slot, CardRecord)] =
    val seen = scala.collection.mutable.Map.empty[String, Int]
    cards.map { c =>
      val n = seen.getOrElse(c.tag, 0)
      seen(c.tag) = n + 1
      Slot(c.tag, n) -> c
    }

  // ================================================ rendering ====

  private def cardLines(c: CardRecord): Vector[String] =
    (s"card ⟦${c.tag}⟧" +: s"  note ⟦${c.noteType}⟧" +:
      c.fields.map((n, v) => s"  field ⟦$n⟧ ⟦$v⟧")) :+ ""

  private def render(cards: Vector[CardRecord], fails: Vector[FailRecord]): String =
    val lines =
      HeaderProse ++
        Vector(s"specs ${cards.size}", s"failures ${fails.size}", "") ++
        cards.flatMap(cardLines) ++
        fails.map(f => s"fail ⟦${f.caseName}⟧ ⟦${f.value}⟧")
    lines.mkString("\n") + "\n"

  // ================================================ the live scan ====

  /** The same upward walk `FixtureVaultTest.vaultRoot` uses. See the class comment: the
    * duplication is forced by this slice's file list, not chosen.
    */
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

  /** Derived from `vaultRoot` rather than walked for separately: one walk, so there is only
    * one thing to keep right.
    */
  lazy val goldenPath: Path = vaultRoot.getParent.resolve("extract/golden/fixture-cards.txt")

  /** THE WHOLE VAULT, with no exclusion — `Patterns/Table-Edge-Cases.md` included. Its three
    * deliberate duplicate identities are the point, not a nuisance. `Planner.checkUnique` is
    * never called here: this pins what extraction EMITS, and the gate is `FixtureVaultTest`'s
    * subject.
    */
  lazy val index: VaultIndex =
    val files = Files
      .walk(vaultRoot)
      .iterator
      .asScala
      .filter(p => p.toString.endsWith(".md"))
      .toVector
      .map(p => VaultFile(vaultRoot.relativize(p).toString, Files.readString(p)))
      .sortBy(_.relativePath)
    VaultWalker.scan(files, DeckPath(NonEmptyVector.one("Obsidian")))

  lazy val actualCards: Vector[CardRecord] =
    // ONLY key, noteTypeName and fields. No match on CardSpec — see the class comment.
    index.scan.specs.map { s =>
      CardRecord(
        tag = escape(TagCodec.encode(s.spec.key).value),
        noteType = escape(s.spec.noteTypeName),
        fields = s.spec.fields.map((n, v) => escape(n) -> escape(v)),
      )
    }

  lazy val actualFails: Vector[FailRecord] =
    // Exhaustive on purpose: a new BuildFailure case must not be silently unpinned.
    index.scan.failures.map {
      case BuildFailure.KeyKnown(key, _, _) =>
        FailRecord(escape("KeyKnown"), escape(TagCodec.encode(key).value))
      case BuildFailure.KeyUnderivableInFile(noteId, _, _) =>
        FailRecord(escape("KeyUnderivableInFile"), escape(noteId.value))
      case BuildFailure.FileUnreadable(file, _) =>
        FailRecord(escape("FileUnreadable"), escape(file))
    }

  // ================================================ reading the golden ====

  final case class ParsedGolden(
      firstLine: String,
      declaredSpecs: Int,
      declaredFailures: Int,
      cards: Vector[CardRecord],
      fails: Vector[FailRecord],
  )

  private val CardLine     = """^card ⟦([^⟦⟧]*)⟧$""".r
  private val NoteLine     = """^  note ⟦([^⟦⟧]*)⟧$""".r
  private val FieldLine    = """^  field ⟦([^⟦⟧]*)⟧ ⟦([^⟦⟧]*)⟧$""".r
  private val FailLine     = """^fail ⟦([^⟦⟧]*)⟧ ⟦([^⟦⟧]*)⟧$""".r
  private val SpecsLine    = """^specs (\d+)$""".r
  private val FailuresLine = """^failures (\d+)$""".r

  /** Read the golden's bytes and decode them as UTF-8 with BOTH error actions set to REPORT.
    *
    * NOT `Files.readString`, which substitutes U+FFFD for malformed input: a corrupted byte
    * would then arrive as a replacement character and be compared as content — "could not
    * read it" reading as "that is what it says".
    */
  private def readGolden(): String =
    if !Files.isRegularFile(goldenPath) then
      fail(
        s"""|The golden file does not exist, and NOTHING IN THIS REPOSITORY CREATES IT.
            |  expected at: ${goldenPath.toAbsolutePath}
            |  vault root:  ${vaultRoot.toAbsolutePath}
            |First creation is a deliberate hand act, never an automatic one: create the file
            |(the header prose is the constant `HeaderProse` in this test source, followed by
            |`specs 0`, `failures 0` and a blank line), run the suite, and adopt the rendered
            |actual output from the temp file the mismatch names — after reading the delta.""".stripMargin
      )
    val decoder = java.nio.charset.StandardCharsets.UTF_8
      .newDecoder()
      .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
      .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
    try decoder.decode(java.nio.ByteBuffer.wrap(Files.readAllBytes(goldenPath))).toString
    catch
      case e: java.nio.charset.CharacterCodingException =>
        fail(s"${goldenPath.toAbsolutePath} is not valid UTF-8: $e")

  /** Split into lines, refusing CRLF BY NAME.
    *
    * There is no `.gitattributes` anywhere in this repository. A checkout with
    * `core.autocrlf=true` would put a `\r` at the end of every line here and fail the golden
    * wholesale and mysteriously; this turns that into one named diagnosis. It does not fix
    * it — the fix is a `.gitattributes`, which is outside this slice.
    */
  private def goldenLines(text: String): Vector[String] =
    val all = text.split("\n", -1).toVector
    // Exactly one trailing empty element, from the file's terminating newline. Dropping only
    // one keeps an extra blank line at the end visible to the strict parser below.
    val lines = if all.nonEmpty && all.last.isEmpty then all.init else all
    lines.zipWithIndex.find((l, _) => l.endsWith("\r")) match
      case Some((_, i)) =>
        fail(
          s"""|${goldenPath.toAbsolutePath} line ${i + 1} ends in CR: the file has CRLF line endings.
              |This is almost certainly a checkout with core.autocrlf=true. There is no
              |.gitattributes in this repository to pin the golden as LF; adding one is the fix.
              |The golden itself is not wrong — do not regenerate it to make this pass.""".stripMargin
        )
      case None => lines

  /** Strict: every line matches exactly one grammar shape in exactly one legal position, and
    * anything else fails naming its 1-based line number. NOTHING is skipped — a parser that
    * skips what it does not recognise turns a hand-edited golden into a silently smaller one.
    */
  private def parseGolden(lines: Vector[String]): ParsedGolden =
    def bad(i: Int, what: String): Nothing =
      fail(s"${goldenPath.toAbsolutePath} line ${i + 1}: $what\n  >> ${lines.lift(i).getOrElse("<end of file>")}")

    var i = 0
    while i < lines.size && lines(i).startsWith("#") do i += 1
    if i == 0 then bad(0, "expected the header, whose every line starts with '#'")

    val declaredSpecs = lines.lift(i) match
      case Some(SpecsLine(n)) => i += 1; n.toInt
      case _                  => bad(i, "expected 'specs <n>'")
    val declaredFailures = lines.lift(i) match
      case Some(FailuresLine(n)) => i += 1; n.toInt
      case _                     => bad(i, "expected 'failures <n>'")
    lines.lift(i) match
      case Some("") => i += 1
      case _        => bad(i, "expected a blank line after the counts")

    val cards    = Vector.newBuilder[CardRecord]
    val fails    = Vector.newBuilder[FailRecord]
    var seenFail = false

    while i < lines.size do
      lines(i) match
        case CardLine(tag) =>
          if seenFail then bad(i, "a card record after a fail record: specs come first, then failures")
          i += 1
          val noteType = lines.lift(i) match
            case Some(NoteLine(nt)) => i += 1; nt
            case _                  => bad(i, "expected '  note ⟦…⟧' directly after a card line")
          val fields = Vector.newBuilder[(String, String)]
          var more   = true
          while more do
            lines.lift(i) match
              case Some(FieldLine(n, v)) => fields += (n -> v); i += 1
              case _                     => more = false
          lines.lift(i) match
            case Some("") => i += 1
            case _        => bad(i, "expected a blank line after a card record's fields")
          cards += CardRecord(tag, noteType, fields.result())

        case FailLine(name, value) =>
          seenFail = true
          fails += FailRecord(name, value)
          i += 1

        case _ => bad(i, "not a card record, a fail record, or a header line")

    ParsedGolden(lines.head, declaredSpecs, declaredFailures, cards.result(), fails.result())

  lazy val golden: ParsedGolden =
    val parsed = parseGolden(goldenLines(readGolden()))
    // The declared counts catch a TRUNCATED or hand-edited-inconsistent file that would
    // otherwise match as a prefix. They do NOT catch a blind regeneration, which rewrites
    // them — that is the job of the count literals asserted against the LIVE scan below.
    assertEquals(
      parsed.cards.size,
      parsed.declaredSpecs,
      s"${goldenPath.toAbsolutePath} declares 'specs ${parsed.declaredSpecs}' but holds ${parsed.cards.size} card records",
    )
    assertEquals(
      parsed.fails.size,
      parsed.declaredFailures,
      s"${goldenPath.toAbsolutePath} declares 'failures ${parsed.declaredFailures}' but holds ${parsed.fails.size} fail records",
    )
    parsed

  // ================================================ the mismatch report ====

  /** Written on first access, to a TEMP file rather than beside the golden: this repository's
    * working tree already carries a couple of dozen untracked entries the author uses
    * `git status` to find their own work in, and suppressing one more would need a
    * `.gitignore` edit outside this slice's file list.
    */
  lazy val dumpPath: Path =
    val p = Files.createTempFile("fixture-cards-actual-", ".txt")
    Files.writeString(p, render(actualCards, actualFails))
    p

  private def adoptionNote: String =
    s"""|The actual output has been rendered in full to:
        |  ${dumpPath.toAbsolutePath}
        |ONLY the delta is printed above, deliberately: copy-pasting a whole new golden out of a
        |failure message is not meant to be a path. If — after reading the delta — this change is
        |wanted, adopt it by hand:
        |  cp ${dumpPath.toAbsolutePath} ${goldenPath.toAbsolutePath}""".stripMargin

  private def capped(label: String, entries: Vector[String]): String =
    val shown = entries.take(20)
    val more  = entries.size - shown.size
    val tail  = if more > 0 then s"\n  … and ${more} more not shown" else ""
    s"$label — ${entries.size} in total:\n${shown.map("  " + _).mkString("\n")}$tail"

  private lazy val goldenSlots: Vector[(Slot, CardRecord)] = slotted(golden.cards)
  private lazy val actualSlots: Vector[(Slot, CardRecord)] = slotted(actualCards)
  private lazy val goldenBySlot: Map[Slot, CardRecord]     = goldenSlots.toMap
  private lazy val actualBySlot: Map[Slot, CardRecord]     = actualSlots.toMap

  private def describeSlot(s: Slot): String =
    if s.ordinal == 0 then s.tag else s"${s.tag}   (occurrence ${s.ordinal + 1})"

  // ================================================ bucket 1: identity ====

  test("no card identity appeared or disappeared") {
    val gone    = goldenSlots.map(_._1).filterNot(actualBySlot.contains)
    val arrived = actualSlots.map(_._1).filterNot(goldenBySlot.contains)

    if gone.nonEmpty || arrived.nonEmpty then
      val parts = Vector.newBuilder[String]
      if gone.nonEmpty then
        parts += capped(
          "CARD IDENTITIES NO LONGER PRODUCED — each one ORPHANS a note in a real collection",
          gone.map(describeSlot),
        )
        parts +=
          """|Each of the identities above is an ORPHAN: the Anki note still exists, still holds its
             |whole review history, and nothing in the markdown claims it any more. Orphan
             |suspension is RULED BUT NOT BUILT, so an orphan is not removed from the daily review
             |rotation — it goes on being asked, with only a tag to show for it. If these
             |disappeared because text is now rendered differently, the matching new identities
             |below are the same cards starting over from zero.""".stripMargin
      if arrived.nonEmpty then
        parts += capped("CARD IDENTITIES THAT ARE NEW — each one creates a note with no history", arrived.map(describeSlot))
      parts += adoptionNote
      fail(parts.result().mkString("\n\n"))
  }

  // ================================================ bucket 2: content ====

  test("no card content changed") {
    val shared = goldenSlots.map(_._1).filter(actualBySlot.contains)

    val changed = shared.flatMap { slot =>
      val g = goldenBySlot(slot)
      val a = actualBySlot(slot)
      if g.noteType == a.noteType && g.fields == a.fields then None
      else
        val detail = Vector.newBuilder[String]
        if g.noteType != a.noteType then
          detail += s"    note type: golden ⟦${g.noteType}⟧  actual ⟦${a.noteType}⟧"
        val n = math.max(g.fields.size, a.fields.size)
        (0 until n).foreach { i =>
          (g.fields.lift(i), a.fields.lift(i)) match
            case (Some(gf), Some(af)) if gf == af => ()
            case (Some((gn, gv)), Some((an, av))) =>
              if gn != an then detail += s"    field $i name: golden ⟦$gn⟧  actual ⟦$an⟧"
              if gv != av then
                detail += s"    field ⟦$gn⟧:"
                detail += s"      golden ⟦$gv⟧"
                detail += s"      actual ⟦$av⟧"
                // Two values can differ as strings and look identical in a diff — an NFC/NFD
                // change, a homoglyph. Printing both with every non-ASCII character forced to
                // \\uXXXX makes the difference visible rather than merely detected.
                detail += s"      golden (all non-ASCII forced) ⟦${forceAscii(gv)}⟧"
                detail += s"      actual (all non-ASCII forced) ⟦${forceAscii(av)}⟧"
            case (Some((gn, gv)), None) => detail += s"    field ⟦$gn⟧ ⟦$gv⟧ is GONE"
            case (None, Some((an, av))) => detail += s"    field ⟦$an⟧ ⟦$av⟧ is NEW"
            case (None, None)           => ()
        }
        Some(s"${describeSlot(slot)}\n${detail.result().mkString("\n")}")
    }

    if changed.nonEmpty then
      fail(
        Vector(
          capped("CARDS WHOSE IDENTITY IS UNCHANGED BUT WHOSE CONTENT MOVED", changed),
          adoptionNote,
        ).mkString("\n\n")
      )
  }

  // ================================================ bucket 3: anti-weakening ====

  /** The fixture vault is OUTSIDE this slice's file list, so the net could be hollowed out by
    * an edit that never appears in this file's diff — delete eight cards from `dummy-vault`,
    * regenerate the golden, and both buckets above go green over a smaller world.
    *
    * These assert POSITIVELY, as literals in this source, which regeneration does not touch.
    * A refactor that silently drops cards then fails a second, differently-keyed assertion
    * whose fix is a small human-scale diff.
    */
  test("the golden's own coverage has not been hollowed out") {
    assert(index.scan.specs.nonEmpty, "the live scan produced NO specs at all")

    // Against the LIVE scan, not the golden. Verified: `inspect --vault-path …/dummy-vault`
    // reports cards: 53, failures: 1, scan: complete, 3 duplicate keys.
    assertEquals(index.scan.specs.size, 53, "the fixture vault no longer produces 53 specs")
    assertEquals(index.scan.failures.size, 1, "the fixture vault no longer produces exactly 1 build failure")

    val values = golden.cards.flatMap(_.fields.map(_._2))

    assert(
      values.exists(_.contains("\\n")),
      "no golden field value contains an escaped newline: the multi-block body coverage " +
        "(Anatomy/Body-Shapes.md 'Running the test suite' — Paragraph, LiteralBlock, Paragraph) is gone",
    )

    val clozeText = escape(Marker.ClozeFields.Text)
    assert(
      golden.cards.exists(c =>
        c.noteType == escape(Marker.NoteTypes.Cloze) &&
          c.fields.exists((n, v) => n == clozeText && v.contains("{{c1::"))
      ),
      "no Cloze record has a Text field containing '{{c1::': the rendered-deletion coverage is gone",
    )

    assert(
      golden.cards.exists(_.tag.contains("%2f")),
      "no golden tag contains '%2f': the encoded-slash coverage ('Cost / benefit', 'Pub/Sub') is gone",
    )

    val noteTypes = golden.cards.map(_.noteType).toSet
    Vector(
      Marker.NoteTypes.Basic,
      Marker.NoteTypes.BasicAndReversed,
      Marker.NoteTypes.ConceptDescriptor,
      Marker.NoteTypes.Cloze,
    ).foreach(nt => assert(noteTypes.contains(escape(nt)), s"no golden record has note type '$nt'"))

    assertEquals(
      golden.firstLine,
      HeaderProse.head,
      "the golden's DO-NOT-REGENERATE warning has been changed or removed",
    )
  }
