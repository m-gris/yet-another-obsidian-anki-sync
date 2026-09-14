package obsidiananki.cli

import io.circe.Json
import io.circe.syntax.*
import java.io.IOException
import java.nio.file.Path
import java.time.format.DateTimeFormatter
import java.time.{Instant, ZoneOffset}
import obsidiananki.model.{CardKey, TagCodec}
import obsidiananki.plan.{Agreement, HistoryMove}

/** WHICH RUN OF THE TOOL A LEDGER LINE BELONGS TO.
  *
  * IT EXISTS SO THAT ONE SYNC'S MOVES READ BACK AS ONE EVENT. Timestamps alone cannot answer "did
  * those four cards move together, or in four separate runs a week apart?", and that is the first
  * question anybody asks when a reassignment looks wrong.
  *
  * IT IS ALSO WHAT MAKES THE PESSIMISTIC APPEND ORDER LEGIBLE. Recording before writing means an
  * interruption can leave a line whose write never happened; the next run corroborates the same
  * card and records it again. Two lines with the same keys and DIFFERENT run ids are that story,
  * told plainly. Two with the same run id would be a defect.
  *
  * AN INSTANT PLUS A SHORT SUFFIX, because neither half suffices. The instant alone collides
  * whenever two runs start inside the same second, which a script looping over vaults does
  * routinely. A random token alone is unsortable, and a trail nobody can sort is a trail nobody
  * reads. It is deliberately not a UUID: this string is read by people grepping a file.
  */
opaque type RunId = String

object RunId:

  /** Compact UTC, then the suffix: `20260913T004500Z-a3f9c1`.
    *
    * NOT `Instant.toString`, whose colons and fractional seconds make an id awkward to select with
    * a double-click and awkward to put in a filename — and a run id gets pasted into both. The
    * ordering property survives the change: this format sorts lexicographically in time order.
    *
    * THE SUFFIX IS DEMANDED RATHER THAN DEFAULTED. A default of `""` would make every run within
    * one second collide silently, and that collision is the only thing the suffix is for.
    */
  private val Stamp: DateTimeFormatter =
    DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC)

  def of(startedAt: Instant, suffix: String): RunId = s"${Stamp.format(startedAt)}-$suffix"

  extension (id: RunId) def value: String = id

/** THE RUN LEDGER COULD NOT BE APPENDED TO, SO THE RUN DID NOTHING.
  *
  * AN `IOException` RATHER THAN A CASE OF [[obsidiananki.anki.AnkiError]], for the reason that
  * enum's own docstring gives for leaving transport failures out of itself: this is not a fact
  * about one action. A collection that cannot be reached and a disk that cannot be written to are
  * both conditions under which the run has no business continuing, so they abort it rather than
  * being collected per card. Modelling it as an `AnkiError` would also be a lie about who refused —
  * Anki was never asked.
  *
  * NAMED RATHER THAN RE-RAISED RAW, because the exception a person sees has to say what the tool
  * was doing. `java.nio.file.AccessDeniedException: /Users/…/ledger.jsonl` names a file and no
  * purpose, and leaves the reader to guess whether their collection was written to anyway.
  */
final class LedgerUnwritable(file: Path, cause: IOException)
    extends IOException(
      s"the run ledger at $file could not be appended to, so nothing was written to Anki either " +
        s"— a history-carrying action that cannot be recorded must not happen: $cause",
      cause,
    )

/** ONE LINE OF THE LEDGER: a history move, stamped with when it was recorded and by which run.
  *
  * THE SPLIT FROM [[HistoryMove]] IS THE PORT/ADAPTER LINE. What the run knows is the move; when it
  * happened and which invocation it was are facts about the process, known here at the edge.
  * `plan/Ledger.scala` says why that matters: keeping the clock out of the core is what leaves the
  * derivation a pure function anybody can test as data.
  */
final case class LedgerEntry(at: Instant, run: RunId, moved: HistoryMove)

/** THE TOOL'S FIRST LOCAL ARTIFACT, AND ITS WIRE FORMAT.
  *
  * ═══ WHY A LOCAL FILE, WHEN NOTHING ELSE THIS TOOL OWNS IS ONE ═══
  *
  * Everything durable this tool keeps lives IN THE ANKI COLLECTION — the `Identity` field, the
  * `sha::` content hash, the `orphaned::` flag. It writes no state file and it never writes the
  * vault (Obsidian is the source of truth, ruled 2026-08-29), so a new local artifact had to be
  * argued for rather than assumed. The argument is that neither existing home can hold this: Anki
  * offers no append-only, greppable store — a note field is not a log, and tags are a set — and the
  * vault is forbidden. So the ledger is a file, and it follows the ONE local-file precedent this
  * codebase has, `cli/VaultRegistry.scala`: a single relative constant, no platform branch, and the
  * home directory passed in so a test can displace it.
  *
  * ═══ JSON LINES, FOR TWO READERS AT ONCE ═══
  *
  * One object per line, no pretty-printing. A program reads it with `jq`; a person greps it for a
  * card's rendered path and gets the whole event on the line they matched. A single JSON array
  * would serve the first reader and not the second, and would stop being appendable: it would have
  * to be re-serialised on every append, so an interrupted write could corrupt records that were
  * already safe. Append-only in the strict sense — adding a record cannot rewrite a byte of an
  * earlier one — is the property an audit trail lives or dies by.
  *
  * ═══ HAND-WRITTEN ENCODING, FOR THE REASON `cli/AsJson.scala` GIVES ═══
  *
  * Derivation would make this file's output a shadow of [[HistoryMove]], so renaming a Scala field
  * would silently rename a key in somebody's `jq` filter. That argument is stronger here than for a
  * sync result: a ledger is read MONTHS after it is written, by which time nothing in the file
  * reveals that the format moved — the old lines still carry the old key, and the reader has no
  * reason to doubt them.
  */
object LedgerFile:

  /** Where the trail lives on macOS, relative to the home directory.
    *
    * ONE CONSTANT AND NO PLATFORM BRANCH, which is [[VaultRegistry.RelativeLocation]]'s reasoning
    * carried over: a branch on `os.name` depends on a string the JVM is free to change, whereas a
    * single path names the assumption plainly and fails visibly on a system that keeps application
    * support somewhere else.
    *
    * UNDER THIS TOOL'S OWN NAME, not Obsidian's and not Anki's. The file is this tool's record of
    * what it did, and filing it under an application it merely talks to would put it where an
    * uninstall of that application could take it away.
    */
  val RelativeLocation: String = "Library/Application Support/obsidian-anki-sync/ledger.jsonl"

  /** Total, and cannot fail: this computes a path, it does not consult one.
    *
    * `home` is a parameter rather than a call to `user.home` so that a test can displace it, exactly
    * as [[VaultRegistry.locate]] is written and for the same reason.
    */
  def locate(home: Path): Path = home.resolve(RelativeLocation)

  /** ONE ENTRY AS THE OBJECT THAT GOES ON THE WIRE.
    *
    * `action` IS A CONSTANT, AND IT IS HERE RATHER THAN ON THE TYPE. A reassignment is the only
    * action in the algebra that moves review history without being asked, so [[LedgerEntry]] needs
    * no field to say which kind it is — a field that can hold only one value is a field that can one
    * day hold the wrong one. It is still EMITTED, because a consumer's filter
    * (`select(.action == "reassign")`) has to keep working on the day a second kind of action earns a
    * line, and a line that never said what it was cannot be told apart from one that did.
    *
    * `divergences` IS ALWAYS PRESENT, EVEN WHEN EMPTY, which is `cli/AsJson.scala`'s always-emit rule
    * applied here: a consumer distinguishing "nothing differed" from "this version does not report
    * what differed" cannot do it if the key is absent in both cases, and the failure would be silent.
    * A grade of `Total` reaches this with an empty list routinely.
    *
    * KEYS ARE NAMED FOR THE READER OF A SCRIPT rather than for this codebase's vocabulary — `was`
    * and `now` rather than `inAnki` and `inVault`. Somebody writing a `jq` filter has not read
    * `plan/MoveEvidence.scala`.
    */
  def json(entry: LedgerEntry): Json =
    Json.obj(
      "at"          := entry.at.toString,
      "run"         := entry.run.value,
      "action"      := "reassign",
      "ankiNote"    := entry.moved.ankiNote.value,
      "from"        := key(entry.moved.from),
      "to"          := key(entry.moved.to),
      "grade"       := gradeName(entry.moved.grade),
      "noteType"    := entry.moved.noteType,
      "divergences" := entry.moved.divergences.map(d =>
        Json.obj("field" := d.field, "was" := d.inAnki, "now" := d.inVault)
      ),
      "unflagged" := entry.moved.unflagged,
    )

  /** ONE KEY IN BOTH OF THE FORMS THE TRAIL'S TWO READERS NEED.
    *
    * `tag` is the `TagCodec` encoding — the form a program decodes back into a `CardKey` exactly,
    * which is what makes a line checkable against a collection. `note` and `path` are what a person
    * greps: nobody hunting a lost card remembers its percent-encoding.
    *
    * BOTH, NOT A CHOICE BETWEEN THEM. Only the encoded form would make the trail unusable from a
    * terminal. Only the rendered form would make it lossy, because `render` joins segments with
    * " / " and a segment containing that string is free to forge one.
    */
  private def key(k: CardKey): Json =
    Json.obj(
      "tag"  := TagCodec.encode(k).value,
      "note" := k.noteId.value,
      "path" := k.path.render,
    )

  /** SPELLED OUT RATHER THAN `toString`, so the wire format is a decision and not a consequence.
    *
    * The four names happen to equal the enum's case names today. Written as a match, renaming a case
    * is a compile error here and therefore a deliberate choice about the wire; written as `toString`
    * it would be a silent change to what every future reader of an old line sees.
    */
  private def gradeName(grade: Agreement): String = grade match
    case Agreement.Total             => "Total"
    case Agreement.NameAndSubstance  => "NameAndSubstance"
    case Agreement.PlaceAndSubstance => "PlaceAndSubstance"
    case Agreement.SubstanceAlone    => "SubstanceAlone"

  /** One line, compact, carrying no newline of its own. */
  def line(entry: LedgerEntry): String = json(entry).noSpaces

  /** A WHOLE RUN'S LINES, ready to append — every one newline-TERMINATED, including the last.
    *
    * TERMINATED RATHER THAN SEPARATED, and that is what makes the file appendable at all: a
    * separated rendering would leave this run's last line unterminated, so the NEXT run's first
    * object would land on the same line and neither would parse.
    *
    * ONE INSTANT AND ONE RUN ID FOR THE WHOLE BATCH, TAKEN AS PARAMETERS HERE RATHER THAN CARRIED
    * IN PER ENTRY. A run appending its moves together is one event, so this is the only way to
    * write the file and a caller cannot produce a batch whose lines disagree about when they
    * happened. [[LedgerEntry]] is still the value [[json]] and [[line]] work on — a named stamped
    * line — but assembling a mixed batch is not something this interface can express.
    *
    * Empty in, empty out: a run that moved no history appends nothing, not a blank line.
    */
  def document(moves: Vector[HistoryMove], at: Instant, run: RunId): String =
    moves.map(move => line(LedgerEntry(at, run, move)) + "\n").mkString
