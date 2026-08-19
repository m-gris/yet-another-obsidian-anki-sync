package obsidiananki.cli

import cats.effect.{ExitCode, IO}
import cats.syntax.all.*
import com.monovore.decline.Opts
import com.monovore.decline.effect.CommandIOApp
import obsidiananki.anki.DeckPath
import obsidiananki.extract.{VaultFile, VaultIndex, VaultWalker}
import obsidiananki.plan.Planner
import java.nio.file.{Files, Path}
import scala.jdk.CollectionConverters.*

/** The imperative shell.
  *
  * Everything below the CLI is pure: reading files and printing lines happen here and
  * nowhere else, which is why the walk, the extractor, the planner and the reporter can all
  * be tested without a filesystem, a network or captured output.
  */
object Main
    extends CommandIOApp(
      name = "obsidian-anki-sync",
      header = "Sync marked headings in an Obsidian vault into Anki.",
    ):

  def main: Opts[IO[ExitCode]] = Cli.command.map {
    case Command.Inspect(vault, deckRoot, verbose) => inspect(vault, deckRoot, verbose)
    case Command.Sync(_, profile, _, _)            => syncNotImplemented(profile)
  }

  /** Read the vault and say what it holds. Touches no collection. */
  private def inspect(vault: Path, deckRoot: DeckPath, verbose: Boolean): IO[ExitCode] =
    for
      files <- readVault(vault)
      index = VaultWalker.scan(files, deckRoot)
      _ <- IO.println(s"vault:    $vault")
      _ <- IO.println(s"files:    ${files.size}")
      _ <- Report.inspect(index, verbose).traverse_(IO.println)
    yield exitCodeFor(index)

  /** Anything that reaches a collection is refused until the live-Anki work is done.
    *
    * FAILS LOUDLY as unimplemented rather than doing nothing quietly. A command that
    * accepted its arguments, printed nothing alarming and changed no cards would be
    * indistinguishable from a successful no-op run.
    */
  private def syncNotImplemented(profile: String): IO[ExitCode] =
    IO.println(
      s"""|sync is not implemented yet.
          |
          |The AnkiConnect interpreter is deliberately unwritten: five questions about the
          |live API shape it, and answering them by guessing is how this project's worst
          |bugs were introduced. Use `inspect` to see what the vault would produce.
          |
          |(profile '$profile' was accepted and NOT used — nothing was contacted.)""".stripMargin
    ).as(ExitCode(2))

  /** Non-zero when anything was wrong, so a failure is visible to a script and not only to
    * a reader. Duplicate keys are fatal: nothing would be written.
    */
  private def exitCodeFor(index: VaultIndex): ExitCode =
    if Planner.checkUnique(index.scan.specs).nonEmpty then ExitCode(2)
    else if index.scan.failures.nonEmpty then ExitCode.Error
    else ExitCode.Success

  private def readVault(root: Path): IO[Vector[VaultFile]] =
    IO.blocking {
      Files
        .walk(root)
        .iterator
        .asScala
        .filter(p => p.toString.endsWith(".md") && !p.toString.contains("/.obsidian/"))
        .toVector
        .map(p => VaultFile(root.relativize(p).toString, Files.readString(p)))
        .sortBy(_.relativePath)
    }
