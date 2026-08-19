package obsidiananki.extract

/** Splitting a note's YAML frontmatter from its body, and reading it.
  *
  * THIS IS A LOAD-BEARING SPLIT AND IT IS WHY THIS FILE EXISTS SEPARATELY. Laika parses a
  * `---` block as HOCON, which SILENTLY corrupts values — `id: 2026-08-18` comes back as
  * `202608-18`, and a one-item YAML list comes back as a string. Obsidian frontmatter is
  * YAML, so it is split off textually here and handed to a real YAML parser; Laika only ever
  * sees the body.
  *
  * The split itself is then a new place for the same class of failure, so it is defined
  * narrowly and tested against every non-frontmatter use of `---` that occurs in real
  * markdown: a thematic break, a table's separator row, a fence containing dashes, a leading
  * blank line, and a file with no frontmatter at all.
  */

/** Why a note's frontmatter could not be read. */
enum FrontmatterError:
  /** An opening `---` with no closing `---`. Ambiguous — the whole file could be
    * frontmatter or none of it could be — so it is refused rather than guessed at.
    */
  case Unterminated

  /** The block is not valid YAML. */
  case Malformed(reason: String)

  /** The block parsed but is not a mapping — a bare list or scalar at the top level. */
  case NotAMapping

/** A note's frontmatter and body, separated. */
final case class SplitNote(frontmatter: Option[String], body: String)

object Frontmatter:

  private val Fence = "---"

  /** Separate the frontmatter block from the body.
    *
    * Frontmatter exists ONLY when the very first line is exactly `---`. That single rule is
    * what makes every hostile case fall out correctly rather than needing to be special-cased:
    * a thematic break, a table separator and a fenced block all sit below line 1, so none of
    * them can open a frontmatter block, and a leading blank line means there is none.
    *
    * Returns the body unchanged when there is no frontmatter, so a note without one is an
    * ordinary note rather than an error.
    */
  def split(content: String): Either[FrontmatterError, SplitNote] =
    val lines = content.linesWithSeparators.toVector
    // The ENTIRE rule: the very first line must be exactly the fence. Every hostile case
    // follows from it without a special case — a thematic break, a table separator and a
    // fenced block all sit below line 1, and a leading blank line means line 1 is not it.
    if !lines.headOption.exists(_.stripLineEnd.trim == Fence) then Right(SplitNote(None, content))
    else
      lines.indexWhere(_.stripLineEnd.trim == Fence, from = 1) match
        // Opened but never closed: the whole file could be frontmatter or none of it.
        // Guessing would silently discard the document, so it is refused.
        case -1 => Left(FrontmatterError.Unterminated)
        case closing =>
          Right(
            SplitNote(
              frontmatter = Some(lines.slice(1, closing).mkString),
              body = lines.drop(closing + 1).mkString.dropWhile(c => c == '\r' || c == '\n'),
            )
          )

  /** Parse a frontmatter block into its string values.
    *
    * EVERY VALUE IS READ AS A STRING, deliberately. YAML's implicit typing would resolve
    * `2026-08-18` to a date and `007` to an integer — the exact corruption that made HOCON
    * unusable, one library further on. The keys here feed a card's identity, so they must
    * survive verbatim.
    *
    * Values that are not scalars (lists, nested mappings) are simply absent from the result
    * rather than an error: `aliases:` and `tags:` are none of this tool's business, and a
    * note must not fail to sync because of a key it does not use.
    */
  def parse(block: String): Either[FrontmatterError, Map[String, String]] =
    if block.trim.isEmpty then Right(Map.empty)
    else
      try
        Option(yaml.load[Any](block)) match
          case None => Right(Map.empty)
          case Some(m: java.util.Map[?, ?]) =>
            import scala.jdk.CollectionConverters.*
            Right(
              m.asScala.iterator.collect {
                // Scalars only. A list or nested mapping is absent rather than an error:
                // `aliases:` and `tags:` are none of this tool's business, and a note must
                // not fail to sync because of a key it does not use.
                case (k, v: String) => k.toString -> v
              }.toMap
            )
          case Some(_) => Left(FrontmatterError.NotAMapping)
      catch case e: org.yaml.snakeyaml.error.YAMLException => Left(FrontmatterError.Malformed(e.getMessage))

  /** Split and parse in one step. */
  def read(content: String): Either[FrontmatterError, (Map[String, String], String)] =
    for
      note <- split(content)
      keys <- note.frontmatter.fold(Right(Map.empty[String, String]))(parse)
    yield (keys, note.body)

  /** A YAML loader with IMPLICIT TYPING TURNED OFF, so every scalar stays a string.
    *
    * This is the whole reason snakeyaml is configured rather than used out of the box. Its
    * default resolver types `2026-08-18` as a date and `007` as an integer — which is
    * precisely the corruption that made Laika's HOCON parsing unusable, one library further
    * on. A frontmatter `id` feeds the card identity key, so it must survive verbatim.
    */
  private def yaml: org.yaml.snakeyaml.Yaml =
    val options = org.yaml.snakeyaml.LoaderOptions()
    val resolver = new org.yaml.snakeyaml.resolver.Resolver:
      // Adding no implicit resolvers is what keeps every scalar a string.
      override protected def addImplicitResolvers(): Unit = ()
    org.yaml.snakeyaml.Yaml(
      org.yaml.snakeyaml.constructor.SafeConstructor(options),
      org.yaml.snakeyaml.representer.Representer(org.yaml.snakeyaml.DumperOptions()),
      org.yaml.snakeyaml.DumperOptions(),
      options,
      resolver,
    )
