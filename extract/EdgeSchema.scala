package obsidiananki.extract

import cats.data.NonEmptyVector
import obsidiananki.model.{KeyError, PropertyName, ThreeFieldDirections}

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

  /** The direction is not one this tool knows. Names what was written AND what is available,
    * because a reader who mistypes `2-way` cannot guess the spelling from a refusal alone.
    */
  case UnknownDirection(property: String, raw: String)

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
    case UnknownDirection(property, raw) =>
      s"'$property' asks to be a '$raw' card, which is not a direction this tool knows. " +
        s"The choices are ${EdgeSchema.Directions.mkString(", ")} — the same words a heading uses"
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
          dirs <- directionOf(direction).toRight(EdgeSchemaError.UnknownDirection(name, direction))
        yield (property, name, dirs)
      case _ => Left(EdgeSchemaError.NotAnEntry(body))

  /** `1way` / `2way` / `3way`, and nothing else. */
  private[extract] def directionOf(raw: String): Option[ThreeFieldDirections] =
    raw.trim.toLowerCase(java.util.Locale.ROOT) match
      case "1way" => Some(ThreeFieldDirections.ValueOnly)
      case "2way" => Some(ThreeFieldDirections.Default)
      case "3way" => Some(ThreeFieldDirections.All)
      case _      => None

  /** The vocabulary, for a message that has to name the alternatives. Kept beside
    * [[directionOf]] so the two are read together; `EdgeSchemaTest` pins them against each other
    * so a word added to one and not the other fails rather than misinforming.
    */
  private[extract] val Directions: Vector[String] = Vector("1way", "2way", "3way")
