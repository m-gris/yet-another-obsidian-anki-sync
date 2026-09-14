package obsidiananki.cli

import cats.data.NonEmptyVector
import cats.effect.unsafe.implicits.global
import io.circe.parser.parse
import java.nio.file.{Files, Path, Paths}
import java.time.Instant
import obsidiananki.anki.AnkiNoteId
import obsidiananki.model.{CardKey, CardPath, HeadingPath, HeadingSegment, NoteId, TagCodec}
import obsidiananki.plan.{Agreement, Divergence, FieldRole, HistoryMove}

/** THE LEDGER AS IT LANDS ON DISK — the line format, the path, and the run id.
  *
  * ==Why the wire format is tested apart from what earns a line==
  *
  * `plan/Ledger.test.scala` pins WHICH actions are recorded; this pins WHAT A LINE SAYS. The split
  * follows the code's own: the core derives moves and knows nothing about JSON, the edge stamps and
  * encodes them. Tested together, a change to the derivation and a change to the format would be
  * indistinguishable in a failure.
  *
  * ==Why these assertions name keys as literal strings==
  *
  * The keys are a PUBLISHED INTERFACE. `cli/AsJson.scala` already argues that hand-written encoders
  * exist so that renaming a Scala field cannot silently rename a key somebody's `jq` filter reads,
  * and the argument is stronger for a trail than for a sync result: a ledger is read months after it
  * is written, by which time nothing in the file reveals that the format moved — the old lines still
  * carry the old key and the reader has no reason to doubt them. So changing a key here has to be a
  * deliberate act with a failing test in front of it.
  */
class LedgerFileTest extends munit.FunSuite:

  // ------------------------------------------------------------------ fixtures ----

  def key(id: String, segments: String*): CardKey =
    CardKey(
      NoteId.fromFrontmatter(id).toOption.get,
      CardPath.Headings(
        HeadingPath(
          NonEmptyVector.fromVectorUnsafe(
            segments.toVector.map(s => HeadingSegment.fromExtractedText(s).toOption.get)
          )
        )
      ),
    )

  val strandedKey: CardKey = key("system-design", "essential numbers", "scale")
  val movedKey: CardKey    = key("system-design", "framework", "essential numbers", "scale")

  val at: Instant = Instant.parse("2026-09-13T00:45:00Z")
  val run: RunId  = RunId.of(at, "a3f9c1")

  val moved: HistoryMove = HistoryMove(
    ankiNote = AnkiNoteId(1757),
    from = strandedKey,
    to = movedKey,
    grade = Agreement.NameAndSubstance,
    noteType = "Obsidian Basic",
    // `Bearing` is the role the breadcrumb plays — see `FieldRole.declared`, where
    // `Marker.ContextField` maps to it for every note type this tool declares.
    divergences = Vector(
      Divergence("Context", FieldRole.Bearing, "System design", "System design › Framework")
    ),
    unflagged = true,
  )

  val entry: LedgerEntry = LedgerEntry(at, run, moved)

  def objectOf(line: String) =
    parse(line)
      .getOrElse(fail(s"the line is not JSON: $line"))
      .asObject
      .getOrElse(fail(s"the line is not a JSON object: $line"))

  // ================================================================ the line ====

  test("one entry is one JSON object, on one line, with every key the format promises") {
    val line = LedgerFile.line(entry)
    assert(!line.contains("\n"), s"a ledger line must not contain a newline: $line")

    assertEquals(
      objectOf(line).keys.toVector.sorted,
      Vector(
        "action",
        "ankiNote",
        "at",
        "divergences",
        "from",
        "grade",
        "noteType",
        "run",
        "to",
        "unflagged",
      ),
    )
  }

  test("the scalars say what happened, when, and under which run") {
    val obj = objectOf(LedgerFile.line(entry))

    def str(k: String): String =
      obj(k).flatMap(_.asString).getOrElse(fail(s"no string at '$k'"))

    assertEquals(str("at"), "2026-09-13T00:45:00Z")
    assertEquals(str("run"), "20260913T004500Z-a3f9c1")
    assertEquals(str("grade"), "NameAndSubstance")
    assertEquals(str("noteType"), "Obsidian Basic")
    assertEquals(obj("ankiNote").flatMap(_.asNumber).map(_.toString), Some("1757"))
    assertEquals(obj("unflagged").flatMap(_.asBoolean), Some(true))
  }

  test("`action` is emitted even though a reassignment is the only thing recorded") {
    // A consumer's filter — select(.action == "reassign") — has to keep working on the day a second
    // kind of action earns a line, and a line that never said what it was cannot be told apart from
    // one that did. It is a constant in the encoder rather than a field on the type, because a field
    // that can hold only one value is a field that can one day hold the wrong one.
    assertEquals(
      objectOf(LedgerFile.line(entry))("action").flatMap(_.asString),
      Some("reassign"),
    )
  }

  test("BOTH KEYS travel in both forms — decodable for a program, greppable for a person") {
    val line = LedgerFile.line(entry)
    val obj  = objectOf(line)

    def side(name: String): Map[String, String] =
      obj(name)
        .flatMap(_.asObject)
        .map(_.toMap.flatMap((k, v) => v.asString.map(k -> _)))
        .getOrElse(fail(s"no object at '$name'"))

    assertEquals(side("from").keySet, Set("tag", "note", "path"))
    assertEquals(side("to").keySet, Set("tag", "note", "path"))

    // THE DECODE CLAIM, ROUND-TRIPPED RATHER THAN ASSUMED. The encoded form is on the line so a
    // program can recover the exact key; asserting that it decodes back to the key we started from
    // is what makes that true rather than merely plausible.
    assertEquals(TagCodec.decode(side("from")("tag")), Right(strandedKey))
    assertEquals(TagCodec.decode(side("to")("tag")), Right(movedKey))

    assertEquals(side("from")("path"), strandedKey.path.render)
    assertEquals(side("to")("note"), movedKey.noteId.value)

    // THE GREP CLAIM, ALSO ASSERTED. The whole reason a rendered path is on the line is that
    // somebody hunting a lost card greps for it. Were it encoded, this would fail and nothing else
    // in the suite would notice.
    assert(
      line.contains("essential numbers / scale"),
      s"a person could not grep this line for the card's path: $line",
    )
  }

  test("every divergence is named with what Anki held and what the vault now says") {
    assertEquals(
      objectOf(LedgerFile.line(entry))("divergences").flatMap(_.asArray).map(_.map(_.noSpaces)),
      Some(
        Vector("""{"field":"Context","was":"System design","now":"System design › Framework"}""")
      ),
    )
  }

  test("a move that explained nothing still produces the key, as an empty list") {
    // A grade of Total is reached with no divergence at all — the same path in a different note. The
    // key has to be present anyway: a consumer distinguishing "nothing differed" from "this version
    // does not report what differed" cannot do it if the key is absent in both cases, and the failure
    // would be silent. This is `cli/AsJson.scala`'s always-emit rule, applied here.
    val silent = LedgerFile.line(LedgerEntry(at, run, moved.copy(divergences = Vector.empty)))
    assertEquals(objectOf(silent)("divergences").flatMap(_.asArray), Some(Vector.empty))
  }

  // ============================================================ the document ====

  test("a run's lines are newline-TERMINATED, so an append cannot run onto the previous run") {
    val two = LedgerFile.document(Vector(moved, moved), at, run)
    assertEquals(two, LedgerFile.line(entry) + "\n" + LedgerFile.line(entry) + "\n")
    assert(two.endsWith("\n"))
  }

  test("nothing recorded is nothing appended — not a blank line") {
    assertEquals(LedgerFile.document(Vector.empty, at, run), "")
  }

  test("every line of one run carries that run's instant and id, because the batch is one event") {
    // The interface is what makes this true: the stamp is taken once, for the whole batch, so a
    // caller cannot assemble a run whose lines disagree about when they happened.
    val document = LedgerFile.document(Vector(moved, moved.copy(unflagged = false)), at, run)
    val lines    = document.linesIterator.toVector
    assertEquals(lines.size, 2)
    assertEquals(
      lines.map(l => objectOf(l)("run").flatMap(_.asString)).distinct,
      Vector(Some("20260913T004500Z-a3f9c1")),
    )
    assertEquals(
      lines.map(l => objectOf(l)("at").flatMap(_.asString)).distinct,
      Vector(Some("2026-09-13T00:45:00Z")),
    )
  }

  // ================================================================ the path ====

  test("the ledger lives under this tool's own name, below the home it is given") {
    assertEquals(
      LedgerFile.locate(Paths.get("/Users/someone")).toString,
      "/Users/someone/Library/Application Support/obsidian-anki-sync/ledger.jsonl",
    )
  }

  test("the run id sorts in time order, which is what makes the trail readable") {
    val earlier = RunId.of(Instant.parse("2026-09-13T00:45:00Z"), "zzzzzz")
    val later   = RunId.of(Instant.parse("2026-09-13T00:45:01Z"), "aaaaaa")
    assert(earlier.value < later.value, s"'${earlier.value}' should sort before '${later.value}'")
  }

  test("two runs starting in the same second are still told apart") {
    val together = Instant.parse("2026-09-13T00:45:00Z")
    assertNotEquals(RunId.of(together, "a3f9c1").value, RunId.of(together, "b7e204").value)
  }

  // ══════════════════════════════════ THE APPENDER, AGAINST A REAL FILESYSTEM ════
  //
  // Everything above is pure, and every one of those assertions could hold while nothing ever
  // reached a disk. The ruling of 2026-09-12 is about DURABILITY, so the claim that a file appears —
  // and that a second run adds to it rather than replacing it — is the one that has to be made
  // against a real filesystem. A temporary directory, never the real home: these tests must not
  // write where the tool writes.

  def inTempHome[A](use: Path => A): A =
    val home = Files.createTempDirectory("yaoas-ledger-test")
    try use(home)
    finally
      // Depth-first, because a directory cannot be deleted while it holds anything.
      Files
        .walk(home)
        .sorted(java.util.Comparator.reverseOrder[Path])
        .forEach(p => Files.deleteIfExists(p): Unit)

  def append(home: Path, moves: Vector[HistoryMove], run: RunId): Unit =
    Main
      .appendingLedger(LedgerFile.locate(home), at, run)
      .record(moves)
      .value
      .unsafeRunSync()
      .fold(e => fail(s"the append reported an Anki error, which it cannot have: $e"), identity)

  test("a first run creates the directory it has never had, and writes one line per move") {
    inTempHome { home =>
      append(home, Vector(moved), run)

      val file = LedgerFile.locate(home)
      assert(Files.exists(file), s"no ledger was written at $file")
      val lines = Files.readString(file).linesIterator.toVector
      assertEquals(lines.size, 1)
      assertEquals(objectOf(lines.head)("run").flatMap(_.asString), Some(run.value))
    }
  }

  test("a second run APPENDS — the first run's line is still there afterwards") {
    // The property the whole file format exists for, and the one a wrong open flag would destroy
    // silently: `WRITE` without `APPEND` truncates, and the loss would only be noticed by somebody
    // looking for a record that had been there a moment ago.
    inTempHome { home =>
      val second = RunId.of(Instant.parse("2026-09-13T09:00:00Z"), "b7e204")
      append(home, Vector(moved), run)
      append(home, Vector(moved.copy(unflagged = false)), second)

      val lines = Files.readString(LedgerFile.locate(home)).linesIterator.toVector
      assertEquals(lines.size, 2, "the second run did not append to the first run's trail")
      assertEquals(
        lines.map(l => objectOf(l)("run").flatMap(_.asString)),
        Vector(Some(run.value), Some(second.value)),
        "the runs are out of order, or one overwrote the other",
      )
    }
  }

  test("a run that moved no history leaves no file at all") {
    // Its absence is information: somebody who has never had a card reassigned should not find a
    // ledger. An empty file would say "this tool has moved history and here is the record", which is
    // the opposite of true.
    inTempHome { home =>
      append(home, Vector.empty, run)
      assert(
        !Files.exists(LedgerFile.locate(home)),
        "an empty batch created a ledger file anyway",
      )
    }
  }

  test("a ledger that cannot be written raises an error naming the file and the consequence") {
    // Modelled by handing the appender a home that is a FILE, so the directory beneath it cannot be
    // created. What matters is that the failure is not swallowed and not reported as an Anki error:
    // the run has to stop, because an automatic action that cannot be recorded must not happen.
    val notADirectory = Files.createTempFile("yaoas-ledger-not-a-dir", "")
    try
      val raised = intercept[LedgerUnwritable] {
        Main
          .appendingLedger(LedgerFile.locate(notADirectory), at, run)
          .record(Vector(moved))
          .value
          .unsafeRunSync()
      }
      assert(
        raised.getMessage.contains("ledger.jsonl"),
        s"the failure does not name the file it could not write: ${raised.getMessage}",
      )
      assert(
        raised.getMessage.contains("nothing was written to Anki"),
        s"the failure does not say the collection was left alone: ${raised.getMessage}",
      )
    finally Files.deleteIfExists(notADirectory): Unit
  }
