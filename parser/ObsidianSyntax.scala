package obsidiananki.parser

import laika.api.MarkupParser
import laika.api.bundle.{ExtensionBundle, ParserBundle, SpanParserBuilder}
import laika.format.Markdown
import laika.ast.*
import laika.parse.builders.*
import laika.parse.syntax.*

/** Laika extensions for the Obsidian markdown dialect.
  *
  * Two constructs, both absent from CommonMark, both load-bearing:
  *
  *   - `[[wikilink]]` — 54 occurrences in the reference vault.
  *   - `==highlight==` — the cloze deletion marker.
  *
  * WHY THIS IS NOT COSMETIC. Without the wikilink parser, CommonMark reads `[[X]]` as a
  * shortcut reference link, fails to resolve it, and Laika suppresses header-id generation
  * for the enclosing heading. A `Header` with no id then fails Laika's section-building
  * rewrite, so THE HEADING NEVER BECOMES A `Section`. Its `#flashcard/…` marker is never
  * seen, and every heading beneath it re-parents to the wrong ancestor — silently changing
  * the heading path, which is half the card identity key. 22 heading lines in the reference
  * vault carry a wikilink.
  *
  * PARSING IS STRICT. Lenient mode (`failOnMessages(MessageFilter.None)`) was adopted only
  * because unresolved wikilinks broke section-building, and later because GFM task lists —
  * which Laika does not implement — failed the same way. Both constructs are now recognised
  * explicitly, so the mask is gone and every FUTURE unknown construct errors instead of
  * silently dropping its text. That is the point of removing it: a mechanism that hides one
  * class of failure hides all the others too.
  */
object ObsidianSyntax:

  /** An `![[embed]]`.
    *
    * Deliberately a distinct node rather than rendered content. v0 builds no media pipeline;
    * an embed must be REJECTED LOUDLY by the extraction stage rather than silently producing
    * a card with a broken image. Keeping it in the AST is what makes that rejection possible
    * — and reversible, if a media pipeline is ever wanted.
    */
  case class ObsidianEmbed(target: String, options: Options = Options.empty) extends Span:
    type Self = ObsidianEmbed
    def withOptions(newOptions: Options): ObsidianEmbed = copy(options = newOptions)

  /** A GFM task-list marker, `[ ]` or `[x]`.
    *
    * RULED: task lists are NOT SUPPORTED and must be rejected LOUDLY AND BY NAME.
    *
    * Recognised here rather than left to fail as a mangled link reference, for two reasons.
    * First, Laika's GitHubFlavor does not implement them, so CommonMark reads `[ ]` as a
    * shortcut reference link, fails to resolve it, and the text lands in an `InvalidSpan`
    * that `extractText` walks past — silent content loss, the same mechanism as the
    * wikilink bug. Second, the resulting message would be "unresolved link id reference",
    * which tells an author nothing about what is actually wrong.
    *
    * Recognising the construct lets extraction reject it BY TYPE with an error that names
    * it — and, the real prize, means the parser no longer needs LENIENT MODE to get through
    * a document containing one. Strict parsing is back on because of this node.
    */
  case class TaskListMarker(checked: Boolean, options: Options = Options.empty) extends Span:
    type Self = TaskListMarker
    def withOptions(newOptions: Options): TaskListMarker = copy(options = newOptions)

  /** A comment the author wrote for nobody: `%%Obsidian style%%` or `<!-- HTML style -->`.
    *
    * MODELLED BECAUSE IT MUST BE DROPPED, and dropping it is only safe once it is a TYPE.
    * Laika's Markdown does not model comments at all — `<!-- x -->` arrives as an ordinary
    * `Text` span, indistinguishable from an author who literally typed those characters. So a
    * comment landed on the card. Obsidian hides comments in reading view, which is precisely
    * why they get used for private notes, and precisely why printing one on a flashcard is a
    * disclosure rather than a formatting slip.
    *
    * IT CANNOT BE FIXED DOWNSTREAM. No match over the parsed tree can tell a comment from
    * text, because by then it IS text. The distinction only exists where the syntax does,
    * which is here — the same argument that produced [[Highlighted]], [[ObsidianEmbed]] and
    * [[TaskListMarker]]. If the tool must treat a construct differently, the construct is a
    * node; it is never a string sniffed later.
    *
    * Carries its text so a diagnostic can quote it. Nothing renders it.
    */
  case class ObsidianComment(text: String, options: Options = Options.empty) extends Span:
    type Self = ObsidianComment
    def withOptions(newOptions: Options): ObsidianComment = copy(options = newOptions)

  /** A `==highlight==`, the cloze deletion marker, with its optional GROUP LABEL.
    *
    * `==text==`   -> Highlighted(None, …)      its own group of one
    * `==2|text==` -> Highlighted(Some(2), …)   part of group 2
    *
    * LABELLING IS HOW AN AUTHOR BUYS STABILITY. An unlabelled deletion is keyed by its own
    * text, so editing the text — even to fix a typo — retires the key and the card starts
    * over. A labelled one is keyed by its group, which the author controls, so the text can
    * change freely and the card keeps its review history. Two characters, and the trade is
    * visible in the source rather than hidden in the tool.
    *
    * Grouping also lets one sentence hold several INDEPENDENT deletions that blank together
    * — ten highlights forming groups of 3, 3 and 4 — which no per-highlight scheme can say.
    *
    * KNOWN CONSTRAINT: the pipe collides with a markdown table row, so `| ==1|x== |` breaks
    * the table. Acceptable only because cloze inside a table cell is not in the design —
    * tables are their own card kind. If cloze-in-tables is ever wanted, this syntax must be
    * revisited rather than patched around.
    */
  case class Highlighted(group: Option[Int], content: Seq[Span], options: Options = Options.empty)
      extends Span
      with SpanContainer:
    type Self = Highlighted
    def withContent(newContent: Seq[Span]): Highlighted = copy(content = newContent)
    def withOptions(newOptions: Options): Highlighted   = copy(options = newOptions)

  /** A `==highlight==` THAT IS NOT A CLOZE — no `<<`, so no card.
    *
    * SEPARATE FROM [[Highlighted]] AT THE PARSER rather than downstream, because the decision
    * is made by looking at characters and nothing later has them. By the time spans exist, the
    * `<<` has either been consumed as a marker or kept as text, and no consumer can tell which
    * without re-parsing.
    */
  case class PlainHighlight(content: Seq[Span], options: Options = Options.empty)
      extends Span
      with SpanContainer:
    type Self = PlainHighlight
    def withContent(newContent: Seq[Span]): PlainHighlight = copy(content = newContent)
    def withOptions(newOptions: Options): PlainHighlight   = copy(options = newOptions)

  /** The display text of a wikilink's inner content.
    *
    * Priority — alias, then target, then fragment:
    *   `[[Target]]`             -> "Target"
    *   `[[Target|Alias]]`       -> "Alias"
    *   `[[Target#Heading]]`     -> "Target"
    *   `[[Note^blockid]]`       -> "Note"
    *   `[[#Heading]]`           -> "Heading"   (no target to fall back on)
    *
    * THE ALIAS WINS ON EVIDENCE, NOT TASTE. `[[Availability|Availabilities]] multiply` must
    * not render "Availability multiply". And a target can be an opaque frontmatter id with
    * no human meaning at all — `[[1786868697-SMIR|Module 2 - Moving Data]]` occurs in the
    * reference vault. The target is also the half Obsidian rewrites on file rename, while
    * the pipe-alias survives byte-identical, so preferring the target would *cause* the
    * rename-orphaning that preferring display text avoids.
    */
  def displayText(inner: String): String =
    val (targetPart, aliasPart) = inner.indexOf('|') match
      case -1 => (inner, None)
      case i  => (inner.take(i), Some(inner.drop(i + 1)))

    aliasPart.map(_.trim).filter(_.nonEmpty).getOrElse {
      // Strip a #heading or ^blockid suffix; whichever comes first wins.
      val cut = Seq(targetPart.indexOf('#'), targetPart.indexOf('^')).filter(_ >= 0) match
        case Nil  => targetPart.length
        case idxs => idxs.min
      val bare     = targetPart.take(cut).trim
      val fragment = targetPart.drop(cut + 1).trim
      if bare.nonEmpty then bare else fragment
    }

  /** `![[embed]]`. Registered ahead of the wikilink parser: without it, `!` would be read as
    * plain text and the remaining `[[...]]` would parse as an ordinary wikilink, silently
    * turning an embed into a text reference.
    */
  val embedParser: SpanParserBuilder =
    SpanParserBuilder.standalone {
      ("![[" ~> delimitedBy("]]").failOn('\n')).map(inner => ObsidianEmbed(inner.trim))
    }

  /** `[[wikilink]]`.
    *
    * Three deliberate choices:
    *   - `failOn('\n')` — an unclosed `[[dangling` degrades to plain text rather than
    *     swallowing the rest of the document.
    *   - emits `Text`, not `SpanLink` — so `extractText` and heading-path computation work
    *     unchanged. `Styles("wikilink")` is carried so the styling decision stays reversible
    *     without re-parsing.
    *   - STANDALONE, not recursive — Obsidian does not render markdown inside display text.
    */
  val wikilinkParser: SpanParserBuilder =
    SpanParserBuilder.standalone {
      ("[[" ~> delimitedBy("]]").failOn('\n')).map { inner =>
        Text(displayText(inner), Styles("wikilink"))
      }
    }

  /** `[ ]` / `[x]` / `[X]`, recognised only so that it can be rejected by name.
    *
    * Deliberately narrow: exactly three characters, so ordinary bracketed prose and real
    * link references are untouched. It is registered AFTER the wikilink parser, which
    * matches on `[[`, so a wikilink is never mistaken for a task marker.
    */
  val taskListParser: SpanParserBuilder =
    SpanParserBuilder.standalone {
      ("[" ~> oneOf(' ', 'x', 'X') <~ literal("]")).map(c => TaskListMarker(c != " "))
    }

  /** `%%Obsidian comment%%` — the native form, hidden in reading view. */
  val obsidianCommentParser: SpanParserBuilder =
    SpanParserBuilder.standalone {
      ("%%" ~> delimitedBy("%%")).map(t => ObsidianComment(t))
    }

  /** `<!-- HTML comment -->`, which Obsidian also hides.
    *
    * Registered as its own parser rather than folded into the one above because the delimiters
    * differ at both ends; sharing one parser would mean accepting `%%…-->`.
    */
  val htmlCommentParser: SpanParserBuilder =
    SpanParserBuilder.standalone {
      ("<!--" ~> delimitedBy("-->")).map(t => ObsidianComment(t))
    }

  /** `==highlight==`, the cloze deletion marker. Recursive: inline markup inside a highlight
    * is ordinary markdown and should be parsed as such.
    */
  val highlightParser: SpanParserBuilder =
    SpanParserBuilder.recursive { recParsers =>
      ("==" ~> recParsers.recursiveSpans(delimitedBy("==").failOn('\n')))
        .map { spans =>
          // ── WHICH KIND OF HIGHLIGHT THIS IS ─────────────────────────────────────────────
          //
          // RULED BY MARC 2026-08-28: a cloze is `==<<text>>==`. The brackets declare the
          // intent, which leaves a bare `==text==` meaning what Obsidian says it means — a
          // highlight, drawn as a `<mark>`, making no card.
          //
          // WHY THE BRACKETS RATHER THAN RESERVING `==` OUTRIGHT. Both were on the table.
          // Reserving `==` is less to type, but then NOTHING DISTINGUISHES A CARD FROM
          // EMPHASIS BY LOOKING — every highlight in the vault would be a card and the only
          // way to know would be to remember. The brackets cost four characters and buy the
          // author the ability to see, at a glance, which of their highlights are cards.
          //
          // WHY IT DEGRADES WELL, which is the argument against a bare custom delimiter like
          // `<<text>>` with no `==` around it: without the Obsidian plugin that hides the
          // brackets, this still renders as a REAL HIGHLIGHT — on mobile, in a preview, on
          // somebody else's machine — merely with visible brackets inside it. A bare delimiter
          // renders as literal text and no highlight at all.
          stripClozeBrackets(spans) match
            case Some(inner) =>
              val (group, rest) = splitGroupLabel(inner)
              Highlighted(group, rest)
            case None => PlainHighlight(spans)
        }
    }

  /** Peel `<<` and `>>` off a highlight's content, or say it is not a cloze.
    *
    * ON THE FIRST AND LAST SPANS' TEXT, not on a re-rendered string. The content is already
    * parsed spans — `==<<*a* b>>==` holds an `Emphasis` between the brackets — so the opening
    * `<<` lives at the head of the first span and the closing `>>` at the tail of the last,
    * and everything between them is left untouched whatever it is.
    *
    * BOTH OR NEITHER. A lone `==<<text==` is NOT a cloze and is not an error either: it is a
    * highlight whose text begins with two less-than signs, which is what the author typed and
    * what Obsidian will show them. Guessing at a missing bracket is the fuzzy matching this
    * project rules may rank but never apply.
    */
  private def stripClozeBrackets(spans: Seq[Span]): Option[Seq[Span]] =
    (spans.headOption, spans.lastOption) match
      case (Some(Text(first, fo)), Some(Text(last, lo))) if spans.sizeIs == 1 =>
        // ONE SPAN IS ITS OWN CASE because the head and the last are the SAME span: stripping
        // both ends of it separately would drop `>>` from a value that no longer holds it.
        val t = first
        Option.when(t.startsWith("<<") && t.endsWith(">>") && t.length >= 4)(
          Seq(Text(t.drop(2).dropRight(2), fo))
        )
      case (Some(Text(first, fo)), Some(Text(last, lo))) =>
        Option.when(first.startsWith("<<") && last.endsWith(">>"))(
          Text(first.drop(2), fo) +: spans.slice(1, spans.size - 1) :+ Text(last.dropRight(2), lo)
        )
      case _ => None

  /** Peel a leading `N|` off a highlight's content.
    *
    * DIGITS ONLY, deliberately. A general label would make `==a|b==` ambiguous — is `a` a
    * group or is the text literally `a|b`? Restricting to digits keeps the ambiguity to
    * text that genuinely begins with a number followed by a pipe, which is vanishingly rare
    * and, unlike the general case, obvious when it happens.
    */
  private def splitGroupLabel(spans: Seq[Span]): (Option[Int], Seq[Span]) =
    spans.headOption match
      case Some(Text(content, opts)) =>
        val LabelPrefix = """^(\d+)\|(.*)$""".r
        content match
          case LabelPrefix(digits, remainder) =>
            digits.toIntOption match
              case Some(n) => (Some(n), Text(remainder, opts) +: spans.tail)
              case None    => (None, spans)
          case _ => (None, spans)
      case _ => (None, spans)

  /** The Obsidian-dialect span parsers on their own. Prefer [[markupParser]] — this is
    * exposed only for tests that want to isolate the dialect from the rest of the config.
    */
  object bundle extends ExtensionBundle:
    val description: String =
      "Obsidian dialect: [[wikilinks]], ![[embeds]], ==highlights==, rejected task lists"
    override def parsers: ParserBundle =
      // Embed before wikilink: "![[" must win over "[[".
      ParserBundle(spanParsers =
        Seq(embedParser, wikilinkParser, taskListParser, highlightParser,
            obsidianCommentParser, htmlCommentParser))

  /** THE canonical parser for this project. Build it here and nowhere else — every element
    * below is load-bearing and two of them are silent when omitted.
    *
    *   - `GitHubFlavor` — NOT optional, and not merely about polish. Laika's base `Markdown`
    *     format has NO table support: a markdown table parses as a single `Paragraph` whose
    *     text is the literal pipe characters, and `#flashcard/table` therefore produces one
    *     garbage card instead of n pair cards plus a row card. Verified, not assumed. The
    *     same bundle is what turns a fenced block into a `CodeBlock` carrying its language
    *     rather than a `Literal` with the language swallowed into the content.
    *
    *   - `bundle` — the Obsidian dialect. Without it a wikilink in a heading stops that
    *     heading becoming a `Section` at all; see the note on this object.
    *
    *   - NO `failOnMessages` OVERRIDE. Parsing is strict on purpose: an unknown construct
    *     must error rather than lose its text quietly. Lenient mode existed only to get past
    *     wikilinks and task lists, and both are handled explicitly now.
    *
    * Frontmatter is deliberately NOT wired to Laika's config system here. Laika parses a
    * `---` block as HOCON, which silently corrupts values — `id: 2026-08-18` comes back as
    * `202608-18`, and a one-item YAML list comes back as a string. The `---` block must be
    * split off and parsed with a real YAML parser before the body reaches this parser.
    */
  def markupParser: MarkupParser =
    MarkupParser
      .of(Markdown)
      .using(Markdown.GitHubFlavor)
      .using(bundle)
      .build
