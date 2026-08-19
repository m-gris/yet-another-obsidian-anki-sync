package obsidiananki.cli

import java.nio.file.{Files, Path}

/** A directory that is an OBSIDIAN VAULT, not merely a directory that exists.
  *
  * WHY THIS IS A TYPE AND NOT A CHECK. The argument used to be a bare `Path` validated with
  * `Files.isDirectory`, which accepts `/tmp`, an empty folder, and — the case that matters —
  * the PARENT of the vault you meant. Downstream, nothing could tell the difference, so "we
  * were handed a directory that is not a vault" stayed representable all the way to the point
  * where it becomes destructive.
  *
  * THE FAILURE IT EXISTS TO PREVENT, stated plainly, because it is not obvious. This tool
  * treats a card present in Anki and absent from the markdown as one whose heading you
  * deleted. It cannot distinguish that from "I was looking in the wrong place". So pointing
  * `sync` at a directory with no marked headings is not a no-op: it is a complete scan of
  * nothing, every card in the collection looks deleted, and the run flags all of them and
  * reports success. Requiring the vault marker turns the two most likely wrong directories —
  * the parent, and an unrelated folder — into a refusal at parse time, before anything is
  * read or written.
  *
  * WHAT IT DOES NOT CATCH, and this is a real limit rather than a caveat: a vault whose files
  * have not finished arriving. With the vault on Dropbox or iCloud, `.obsidian/` may land
  * before the notes do, and a partially materialised vault is indistinguishable from one
  * whose headings were removed. Nothing here helps with that.
  *
  * ON DEPENDING ON `.obsidian/` AT ALL. It is Obsidian's own marker for a vault root, but it
  * is a layout convention rather than a published contract, so this is a dependency on
  * someone else's directory structure. It is acceptable here only because of the direction it
  * fails in: if Obsidian ever moved or renamed it, this refuses a valid vault with a message
  * naming exactly what it looked for. Loud and wrong beats quiet and wrong. The tool already
  * relied on this name in one place — the walk skips everything under `.obsidian/` — so the
  * dependency existed already and was merely unstated.
  */
opaque type VaultRoot = Path

object VaultRoot:

  /** Obsidian's marker for a vault root. The walk already excludes everything beneath it. */
  val MarkerDirectory: String = ".obsidian"

  /** Parse a path into a vault, or say precisely why it is not one.
    *
    * Returns the REASON rather than an `Option`, because the whole value of checking here is
    * the message: "not a directory", "no `.obsidian` directory — is this the vault itself, or
    * its parent?" and "vault does not exist" send a person to three different places.
    */
  def at(raw: Path): Either[String, VaultRoot] =
    val path = raw.toAbsolutePath.normalize
    if !Files.exists(path) then Left(s"vault does not exist: $path")
    else if !Files.isDirectory(path) then Left(s"not a directory: $path")
    else if !Files.isDirectory(path.resolve(MarkerDirectory)) then
      Left(
        s"not an Obsidian vault: $path has no '$MarkerDirectory' directory. " +
          "Did you mean a folder inside it?"
      )
    else Right(path)

  /** For tests and fixtures ONLY — a vault root without the marker check.
    *
    * Named to be conspicuous at the call site. Every production path goes through [[at]]; this
    * exists so a fixture directory can be used without the test suite depending on a hidden
    * directory being present in the repository, which git handles badly.
    */
  def unsafeAt(path: Path): VaultRoot = path.toAbsolutePath.normalize

  extension (root: VaultRoot)
    def path: Path = root
    def render: String = root.toString
