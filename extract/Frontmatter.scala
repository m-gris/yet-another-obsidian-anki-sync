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

/** ONE PROPERTY'S VALUE, in the shapes this tool is prepared to model.
  *
  * WHY THIS TYPE EXISTS. `parse` used to answer `Map[String, String]`, keeping scalars and
  * DISCARDING everything else — so a property written as a YAML list was not merely unread, it
  * was indistinguishable from a property that was never written. That was deliberate and it was
  * right while the only property this tool read was `id`, which is a scalar. It stopped being
  * right the moment frontmatter became a source of cards: Obsidian writes `tags:` as a LIST, so
  * a note asking to be a `#flashcard/sequence` had its marker dropped on the floor and the tool
  * could tell only that the word "flashcard" appeared somewhere in the raw block.
  *
  * THE SHAPE IS PRESERVED RATHER THAN NORMALISED. `tags: math` and `tags: [math]` mean the same
  * thing to Obsidian and are written differently by different tools, so a consumer that treats
  * them alike should say so itself. Collapsing them here would decide that question once, for
  * every consumer, at the point that knows least about it.
  *
  * THERE ARE NO PROJECTIONS ON THIS TYPE, AND THAT IS THE POINT. No `asString`, no
  * `valuesOrEmpty`. Every such helper has to answer for [[Unreadable]], and the only answers
  * available to it are a lie (empty) or a crash. A consumer that MATCHES is forced by the
  * compiler to say what it wants to happen — which is different for each of them, and is
  * information only they have.
  */
enum PropertyValue:

  /** A single scalar. Always a `String`: implicit typing is off, so `007` and `2026-08-18` stay
    * as written rather than becoming an integer and a date.
    */
  case One(text: String)

  /** A sequence of scalars, possibly EMPTY — `tags: []` is a property the author declared and
    * left empty, which is not the same as one they never wrote.
    */
  case Many(items: Vector[String])

  /** Present, and not a shape this tool models: a nested mapping, or a sequence with a
    * non-scalar in it.
    *
    * IT IS A CASE RATHER THAN A SILENT DROP because "absent" and "there but unreadable" lead a
    * reader to different actions. A nested-mapping `id` reported as absent produces "no 'id' in
    * its frontmatter — add an id", which sends someone to add a key they can see already. The
    * `shape` string is for that message and nothing else; nothing branches on it.
    */
  case Unreadable(shape: String)

/** A note's frontmatter and body, separated.
  *
  * @param bodyFirstLine
  *   the line of the ORIGINAL FILE that `body` starts on, 1-based as an editor counts.
  *
  * CARRIED BECAUSE EVERY DIAGNOSTIC DOWNSTREAM IS COUNTED IN THE WRONG UNITS WITHOUT IT.
  * Everything after this point sees only `body`, so a line number computed there is relative to
  * the body and is short by however much frontmatter was removed — for a note with the usual
  * three-line `---` block plus a blank line, by four. That number is printed as
  * `Note.md:15 (heading)`, which reads as a file position and is not one; an author who follows
  * it lands four lines above the thing being complained about. Measured on
  * `dummy-vault/System-Design/Replication.md`, where a heading on file line 19 was reported as
  * line 15.
  */
final case class SplitNote(frontmatter: Option[String], body: String, bodyFirstLine: Int)

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
    if !lines.headOption.exists(_.stripLineEnd.trim == Fence) then
      Right(SplitNote(None, content, bodyFirstLine = 1))
    else
      lines.indexWhere(_.stripLineEnd.trim == Fence, from = 1) match
        // Opened but never closed: the whole file could be frontmatter or none of it.
        // Guessing would silently discard the document, so it is refused.
        case -1 => Left(FrontmatterError.Unterminated)
        case closing =>
          val afterFence = lines.drop(closing + 1)
          // COUNTED, NOT ASSUMED. The blank lines between the closing fence and the first real
          // line are dropped below, so they have to be counted here or `bodyFirstLine` is short
          // by however many there were. `takeWhile` on the same predicate the drop uses is what
          // keeps the two from drifting apart.
          val blanksDropped = afterFence.takeWhile(_.forall(c => c == '\r' || c == '\n')).size
          Right(
            SplitNote(
              frontmatter = Some(lines.slice(1, closing).mkString),
              body = afterFence.mkString.dropWhile(c => c == '\r' || c == '\n'),
              // +1 for the closing fence line itself, +1 to move from a 0-based index to the
              // 1-based line number an editor shows.
              bodyFirstLine = closing + 1 + blanksDropped + 1,
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
  def parse(block: String): Either[FrontmatterError, Map[String, PropertyValue]] =
    if block.trim.isEmpty then Right(Map.empty)
    else
      try
        Option(yaml.load[Any](block)) match
          case None => Right(Map.empty)
          case Some(m: java.util.Map[?, ?]) =>
            import scala.jdk.CollectionConverters.*
            Right(
              m.asScala.iterator.flatMap { (k, v) => shapeOf(v).map(k.toString -> _) }.toMap
            )
          case Some(_) => Left(FrontmatterError.NotAMapping)
      catch case e: org.yaml.snakeyaml.error.YAMLException => Left(FrontmatterError.Malformed(e.getMessage))

  /** Split and parse in one step. */
  /** Returns the parsed keys alongside the WHOLE [[SplitNote]] rather than just its body text,
    * so that `bodyFirstLine` reaches the caller. It used to return the body as a bare `String`,
    * which is precisely how every downstream line number came to be counted from the wrong
    * origin — the information was discarded here, one call before it was needed.
    */
  def read(content: String): Either[FrontmatterError, (Map[String, PropertyValue], SplitNote)] =
    for
      note <- split(content)
      keys <- note.frontmatter.fold(Right(Map.empty[String, PropertyValue]))(parse)
    yield (keys, note)

  /** One snakeyaml value as a [[PropertyValue]], or `None` when the property carries nothing.
    *
    * `None` MEANS ABSENT, and exactly one thing reaches it: a null, which is what YAML gives for
    * `aliases:` with nothing after it. That is a property the author declared and gave no value,
    * and every consumer here treats it the same as one never written. Obsidian's own template
    * leaves several behind in each note.
    *
    * EVERY OTHER VALUE IS REPRESENTED RATHER THAN DROPPED, including the ones this tool cannot
    * model. A nested mapping becomes [[PropertyValue.Unreadable]] rather than vanishing, so the
    * message about it can say what is actually there.
    *
    * A SEQUENCE IS ALL-OR-NOTHING. One containing a mapping is unreadable in full rather than
    * read down to its scalars: a partial answer would silently drop the part that was not
    * understood, which is the behaviour this whole change is undoing.
    *
    * Scalars are `String` because the loader below turns implicit typing off. A non-`String`
    * scalar therefore should not occur, and if one ever does it is unreadable rather than
    * coerced — being wrong about `007` or `2026-08-18` is the corruption this file exists for.
    */
  private def shapeOf(value: Any): Option[PropertyValue] =
    import scala.jdk.CollectionConverters.*
    value match
      case null      => None
      case s: String => Some(PropertyValue.One(s))

      case xs: java.util.List[?] =>
        val items = xs.asScala.toVector
        val scalars = items.collect { case s: String => s }
        if scalars.sizeIs == items.size then Some(PropertyValue.Many(scalars))
        else
          Some(
            PropertyValue.Unreadable(
              s"a list of ${items.size} value(s), ${items.size - scalars.size} of which " +
                "is not a plain value"
            )
          )

      case _: java.util.Map[?, ?] => Some(PropertyValue.Unreadable("a nested mapping"))
      case other                  => Some(PropertyValue.Unreadable(s"a ${other.getClass.getSimpleName}"))

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
