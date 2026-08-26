package obsidiananki.extract

import cats.data.NonEmptyVector
import obsidiananki.model.{KeyError, Marker, MarkerError, PropertyName, ThreeFieldDirections}

/** THE VAULT'S DECLARED VOCABULARY OF TYPED EDGES — which frontmatter properties make cards, and
  * how many ways each is asked.
  *
  * ==Why a vocabulary is required at all==
  *
  * Without one, every property would be a card. `created:`, `aliases:` and whatever a plugin
  * writes would all mint cards, and a typo — `special-case-off:` — would silently mint a
  * DIFFERENT card under a different key rather than being refused. Card-bearing properties are a
  * closed set for the same reason heading markers are a closed set: `Marker.Documented` is
  * exhaustive and compiler-checked precisely so that an unrecognised token is a loud refusal.
  *
  * ==Why it lives in the vault rather than in this repository==
  *
  * Heading markers are UNIVERSAL: every vault that wants a two-way card writes `#flashcard/2way`,
  * so their vocabulary is this tool's business and belongs in its source. Edge kinds are not.
  * `special-case-of` and `dual-of` are mathematics; a systems vault would want something else
  * entirely; a language vault something else again. Putting them in Scala would make one
  * person's domain vocabulary a matter for this repository's release cycle.
  *
  * That is a different thing from the configuration this project tried and removed. What was
  * removed decided PRESENTATION — what a breadcrumb may show — and the ruling was that a
  * breadcrumb is a rule rather than a setting. This declares what a WORD MEANS, which is a
  * dictionary, and the tool already keeps one for markers. The dictionary simply belongs to
  * whoever owns the words.
  *
  * ==Why only a direction, and no shape==
  *
  * A typed edge is a TRIPLE — subject, predicate, object — and so is a concept-descriptor
  * card. `Function Space` / `special-case-of` / `HomSet` is exactly the shape
  * `CardSpec.ThreeField` already holds, which is why edges need no note type of their own. The
  * shape therefore never varies and there is nothing to declare about it. What does vary is how
  * many of the three you are asked to recall, which is what a direction is.
  *
  * The words are the same ones a heading uses, deliberately: `1way`, `2way` and `3way` in a
  * schema mean what `#flashcard/cdd/1way` and its siblings mean on a heading, so an author
  * learns one vocabulary rather than two.
  */
final case class EdgeSchema(rules: Map[PropertyName, ThreeFieldDirections]):

  /** How this property is to be asked, or `None` if it is not a card-bearing property.
    *
    * `None` IS THE ORDINARY ANSWER and carries no complaint. Almost every property in a real
    * note — `created`, `aliases`, `cssclasses`, whatever a plugin left behind — is not an edge,
    * and a tool that remarked on each would be unreadable. A property is a card only when the
    * author has said so here.
    */
  def directionsFor(property: PropertyName): Option[ThreeFieldDirections] = rules.get(property)

  def isEmpty: Boolean = rules.isEmpty

/** Why a line of the schema could not be read.
  *
  * COLLECTED RATHER THAN FATAL, like every other per-item failure in this codebase: one
  * mistyped line must not cost the author the other nine rules, and a reader fixing their
  * schema wants the whole list at once rather than one error per run.
  */
enum EdgeSchemaError:

  /** The entry has no separator, so there is no way to tell the property from the direction. */
  case NotAnEntry(line: String)

  /** The right-hand side does not name a shape a relation can take.
    *
    * IT IS READ BY THE SAME PARSER A HEADING'S MARKER IS, so `cdd/1way` here and
    * `#flashcard/cdd/1way` on a heading are the same tokens through the same code. _Until
    * 2026-08-26 this was a bare `1way`, which was a second vocabulary and, worse, a clashing one:
    * `#flashcard/1way` on a heading is a TWO-field card, while `1way` here meant a three-field
    * one. One token, two note types._
    *
    * A relation is a triple — this note, the relation, the thing on the far end — so the only
    * shapes it can take are the concept-descriptor ones. `cloze` and `sequence` parse perfectly
    * well and are refused here for that reason, which is a better message than "unrecognised".
    */
  case NotAnEdgeShape(property: String, raw: String, reason: String)

  /** The property name canonicalises to nothing. */
  case UnusableProperty(raw: String, why: KeyError)

  /** THE SAME PROPERTY DECLARED TWICE, which is refused rather than resolved.
    *
    * Last-wins and first-wins are both defensible and neither is discoverable: the author sees a
    * schema that reads as though it says two things and a tool that silently obeys one of them.
    * Since the two spellings may differ only in case — `Special-Case-Of` and `special-case-of`
    * canonicalise alike — the duplicate may not even look like one, which makes silence worse.
    */
  case DeclaredTwice(property: String)

  def describe: String = this match
    case NotAnEntry(line) =>
      s"'$line' is a list item under the schema heading but names no property: a rule reads " +
        "'- property-name: 1way'. Prose is ignored here; a bullet is taken as a rule"
    case NotAnEdgeShape(property, raw, reason) =>
      s"'$property' asks to be a '$raw' card, and $reason. A relation is a triple — this note, " +
        "the relation, and the thing on the far end — so it may be 'cdd/1way', 'cdd/2way' or " +
        "'cdd/3way', which are the same tokens a heading marker uses"
    case UnusableProperty(raw, why) =>
      s"'$raw' cannot be a property name ($why)"
    case DeclaredTwice(property) =>
      s"'$property' is declared more than once, and the two declarations may not look alike — " +
        "names are compared with case and spacing folded away, so 'Special-Case-Of' and " +
        "'special-case-of' are the same property. Keep one"

object EdgeSchema:

  val empty: EdgeSchema = EdgeSchema(Map.empty)

  /** The heading that introduces the schema, canonicalised as headings are.
    *
    * ONE HEADING IN ONE NOTE, found by scanning rather than configured by a path, because a path
    * would be a setting and this is a note like any other — one the author can rename, move, and
    * link to. Two notes carrying it is refused where the scan happens, not here.
    */
  val Heading: String = "properties-to-flashcards"

  /** The raw text beneath the schema heading, if this note carries one.
    *
    * READ OFF THE RAW MARKDOWN RATHER THAN THE PARSED DOCUMENT, and that is deliberate. A schema
    * note is prose with a list in it; parsing it as a document would make the schema unreadable
    * whenever the note contained anything the strict parser refuses — an array index in a
    * sentence is enough — so a vault could lose its whole vocabulary because of a paragraph that
    * has nothing to do with it. Reading lines cannot fail.
    *
    * The heading is matched CANONICALLY, so `# Properties-to-Flashcards` and
    * `## properties to flashcards` are the same heading, at any level. It ends at the next
    * heading of any level, which is the same rule a section body follows.
    */
  def findIn(body: String): Option[String] =
    val lines = body.linesIterator.toVector
    lines.indexWhere(isSchemaHeading) match
      case -1 => None
      case at =>
        val rest = lines.drop(at + 1)
        Some(rest.takeWhile(l => !l.trim.startsWith("#")).mkString("\n"))

  /** DELIBERATELY LENIENT ABOUT SPACES AND HYPHENS, and the reason is that the alternative is
    * silence rather than an error.
    *
    * A vault with no schema note is the ordinary case — most have no typed edges at all — so a
    * missing schema CANNOT be reported as a failure. That makes a near-miss the worst outcome
    * available: write `# Properties to Flashcards` instead of `# Properties-to-Flashcards` and you
    * get no cards, no schema, and nothing said about either. Treating the two alike removes the
    * whole class.
    *
    * IT DOES NOT TOUCH `TagCodec.canonical`, which folds case and whitespace and is used to build
    * KEYS. Widening that would merge headings that are currently distinct and re-key live cards.
    * The leniency lives here, where its only effect is which heading is recognised.
    */
  private def isSchemaHeading(line: String): Boolean =
    val trimmed = line.trim
    def loosely(raw: String): String =
      obsidiananki.model.TagCodec.canonical(raw).replace('-', ' ').replace('_', ' ')
    trimmed.startsWith("#") && loosely(trimmed.dropWhile(_ == '#')) == loosely(Heading)

  /** Read the schema from the prose beneath its heading.
    *
    * THE RULES ARE THE LIST ITEMS AND NOTHING ELSE. A line that is not a list item is ignored
    * rather than refused, so that an author may explain their vocabulary in prose beneath the
    * heading — which is the whole advantage of keeping this in the vault instead of a config
    * file, and would be lost if the tool insisted every line be machine-readable.
    */
  def parse(text: String): Either[NonEmptyVector[EdgeSchemaError], EdgeSchema] =
    val entries = text.linesIterator.map(_.trim).filter(isBullet).map(readEntry).toVector

    val errors = entries.collect { case Left(e) => e }
    val rules  = entries.collect { case Right(r) => r }

    // DUPLICATES ARE FOUND ON THE CANONICAL NAME, which is the only comparison that can see
    // them: two declarations differing only in case are one property and would otherwise
    // silently overwrite each other on the way into the map.
    val duplicates = rules
      .groupBy(_._1)
      .collect { case (_, rs) if rs.sizeIs > 1 => EdgeSchemaError.DeclaredTwice(rs.head._2) }
      .toVector

    NonEmptyVector.fromVector(errors ++ duplicates).toLeft(EdgeSchema(rules.map(r => r._1 -> r._3).toMap))

  /** A bullet is a rule; everything else is prose. See [[parse]] for why prose is welcome. */
  private def isBullet(line: String): Boolean =
    (line.startsWith("- ") || line.startsWith("* ") || line.startsWith("-") || line.startsWith("*")) &&
      line.drop(1).trim.nonEmpty

  /** One rule, as `(canonical name, name as written, direction)`.
    *
    * The name AS WRITTEN is carried alongside the canonical one purely so a message can quote
    * the author back to themselves. Reporting a canonicalised name would show somebody a string
    * they never typed, and sending them to search their schema for it would fail.
    */
  private def readEntry(line: String): Either[EdgeSchemaError, (PropertyName, String, ThreeFieldDirections)] =
    val body = line.drop(1).trim
    body.split(":", 2) match
      case Array(rawName, rawDirection) =>
        val name      = rawName.trim
        val direction = rawDirection.trim
        for
          property <- PropertyName.fromFrontmatter(name).left.map(EdgeSchemaError.UnusableProperty(name, _))
          dirs     <- shapeOf(name, direction)
        yield (property, name, dirs)
      case _ => Left(EdgeSchemaError.NotAnEntry(body))

  /** `1way` / `2way` / `3way`, and nothing else. */
  /** THE RIGHT-HAND SIDE OF A REWRITE RULE, READ BY THE PARSER OF THE LANGUAGE IT REWRITES INTO.
    *
    * This is the whole of the vocabulary question, and it needs no vocabulary of its own. A
    * declaration says what a property expands into; what it expands into is a card marker; so the
    * text is normalised to marker syntax and handed to [[Marker.parse]]. `cdd/1way` here and
    * `#flashcard/cdd/1way` on a heading are then the same tokens through the same code, and there
    * is nothing that can drift apart.
    *
    * THE `#` IS TOLERATED AND NOT RECOMMENDED. People will write `#flashcard/cdd/1way` because
    * that is what they type on a heading, so refusing it would be pedantry. But a literal
    * `#flashcard/...` typed into a note's BODY is exactly what Obsidian's editor lifts out into
    * the frontmatter `tags` property — the accident this tool already reports elsewhere — so the
    * bare form is the one the documentation gives.
    *
    * ONLY THE CONCEPT-DESCRIPTOR SHAPES ARE ADMITTED, and the others are refused by NAME rather
    * than as gibberish. A relation is a triple, so `cloze` and `sequence` parse perfectly well and
    * are still wrong here; saying which is more use than "unrecognised".
    */
  /** A marker named for somebody reading a refusal, rather than dumped as a case class.
    *
    * Total on purpose: a marker added later has to be given words here before this compiles, and
    * a refusal that names the shape is the entire value of reusing the marker parser — "`1way` is
    * a two-field card" says why it is wrong where "unrecognised" would not.
    */
  private def inWords(m: Marker): String = m match
    case Marker.TwoField(_)      => "a two-field card, and a relation needs three"
    case Marker.ThreeField(_)    => "a concept-descriptor card"
    case Marker.Cloze            => "a cloze card, which fills gaps in prose rather than relating two things"
    case Marker.Sequence         => "a sequence card, which reveals a list in order"
    case Marker.Table(_, _)      => "a table card, which needs a table in the body"

  private[extract] def shapeOf(
      property: String,
      raw: String,
  ): Either[EdgeSchemaError, ThreeFieldDirections] =
    val token = raw.trim.stripPrefix("#").stripPrefix("flashcard/")
    Marker.parse(s"#flashcard/$token") match
      case Right(Some(Marker.ThreeField(directions))) => Right(directions)

      case Right(Some(other)) =>
        Left(EdgeSchemaError.NotAnEdgeShape(property, raw, s"that is ${inWords(other)}"))

      case Right(None) =>
        Left(EdgeSchemaError.NotAnEdgeShape(property, raw, "that names no card shape at all"))

      // The marker parser's own refusals, passed through rather than flattened to one message:
      // "several markers at once" and "unrecognised" send a reader to different places.
      case Left(MarkerError.Unrecognised(token)) =>
        Left(EdgeSchemaError.NotAnEdgeShape(property, raw, s"'$token' is not a marker this tool knows"))
      case Left(MarkerError.Multiple(tokens)) =>
        Left(
          EdgeSchemaError.NotAnEdgeShape(
            property,
            raw,
            s"that names several markers at once (${tokens.mkString(", ")}) — a rule names one",
          )
        )
