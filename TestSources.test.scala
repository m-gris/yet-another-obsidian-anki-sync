package obsidiananki

/** Reading this repository's own source, for the tests that check a table against the code it
  * documents.
  *
  * ==Why this exists, and the bug that produced it==
  *
  * Two tests read `.scala` files: `model/Marker.test.scala` checks every marker token against
  * the parser's `case` literals, and `extract/VaultWalker.test.scala` does the same for the
  * `--deck-from` tokens. Both find the file by walking up from `sys.props("user.dir")`.
  *
  * THAT IS THE SHELL'S WORKING DIRECTORY, NOT THE PROJECT'S. Measured 2026-08-24: with a shell
  * sitting in `backend-interview-prep/srs-obsidian-anki`, the walk resolved
  * `backend-interview-prep/obsidian-anki-custom-sync/extract/VaultWalker.scala` — a STALE COPY
  * of this whole tool, left behind in the repository it was extracted from on 2026-08-23.
  *
  * So the marker drift test had been reading a file frozen the day before the extraction. It
  * passed, because the marker `case` literals happen not to have changed since — a green test
  * proving nothing about this repository, which is the precise failure this project's
  * methodology exists to catch. The new deck-source test failed instead of passing by luck,
  * only because its subject did not exist in the stale copy at all, and its vacuity guard
  * refused to compare two sets it had found nothing for.
  *
  * ==What this anchors on instead==
  *
  * The location of the COMPILED TEST CLASSES, which is this project's build output and cannot
  * be another repository's. From there it walks up to the directory holding `project.scala` —
  * the build definition, and the one file guaranteed to sit at this repository's root.
  */
object TestSources:

  /** The repository root, found from the caller's own compiled location.
    *
    * `owner` must be a class from THIS project — pass `getClass` from a test suite. Its code
    * source is the compiled test tree, so the walk starts inside the build output rather than
    * wherever a shell happened to be.
    */
  def repoRoot(owner: Class[?]): java.nio.file.Path =
    val start =
      Option(owner.getProtectionDomain.getCodeSource)
        .flatMap(cs => Option(cs.getLocation))
        .map(url => java.nio.file.Paths.get(url.toURI).toAbsolutePath)
        .getOrElse(
          sys.error(
            s"no code source for ${owner.getName} — cannot locate the repository from the " +
              "compiled classes, and falling back to the working directory is what this " +
              "object exists to avoid"
          )
        )

    // Deep enough for `.scala-build/<project>_<hash>/classes/test/` and a little slack; not
    // unbounded, so a missing marker fails here rather than walking to the filesystem root.
    Iterator
      .iterate(start)(_.getParent)
      .takeWhile(_ != null)
      .take(12)
      .find(dir => java.nio.file.Files.exists(dir.resolve("project.scala")))
      .getOrElse(
        sys.error(s"no project.scala above $start — where is this repository's root?")
      )

  /** A directory inside this repository, located from the caller's own compiled position.
    *
    * The fixture vault is reached through here for the same reason the source files are: a walk
    * up from `user.dir` found `backend-interview-prep/obsidian-anki-custom-sync/dummy-vault`,
    * so the GOLDEN TEST — this project's acceptance artifact — was comparing a stale vault's
    * cards against a stale golden. Both copies were identical at the time, so it passed; a
    * change to either would have gone unnoticed in one direction and unexplainable in the other.
    */
  def dir(owner: Class[?], relativePath: String): java.nio.file.Path =
    val d = repoRoot(owner).resolve(relativePath)
    if !java.nio.file.Files.isDirectory(d) then
      sys.error(s"$relativePath is not a directory under ${repoRoot(owner)}")
    d

  /** One of this repository's source files, read as text.
    *
    * FAILS LOUDLY RATHER THAN RETURNING EMPTY, because every caller compares what it finds
    * against a set and would otherwise report agreement between two things it never read.
    */
  def read(owner: Class[?], relativePath: String): String =
    val file = repoRoot(owner).resolve(relativePath)
    if !java.nio.file.Files.exists(file) then
      sys.error(s"$relativePath does not exist under ${repoRoot(owner)}")
    java.nio.file.Files.readString(file)
