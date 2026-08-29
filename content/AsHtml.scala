package obsidiananki.content

/* ═══════════════════════════════════════════════════════════════════════════════════════════
 * THIS FILE DOES NOT IMPORT `laika` AT ALL, AND THAT IS A STRUCTURAL GUARANTEE
 * ═══════════════════════════════════════════════════════════════════════════════════════════
 *
 * `Lower.scala` carries a PROSE ban on calling Laika's `SpanContainer.extractText` — itself a
 * trait match with a silent `case _ => ""`, which is the exact defect shape this refactor
 * exists to abolish. Here the ban is stronger and cheaper to check: with no `laika` import,
 * calling `extractText` is IMPOSSIBLE rather than merely forbidden, and verifying it takes
 * reading the first ten lines instead of auditing every branch. Do not add one.
 *
 * It is load-bearing twice over in this file. Laika also owns an HTML renderer, and reaching
 * for it would hand this project a second, differently-behaved definition of what a card field
 * contains — one written for a documentation site, not for an Anki field.
 *
 * ═══════════════════════════════════════════════════════════════════════════════════════════
 * WHAT THIS IS
 * ═══════════════════════════════════════════════════════════════════════════════════════════
 *
 * The SECOND renderer over the closed algebra. `AsText` produces the plain string this tool
 * sends TODAY; this one produces the HTML fragment an Anki field actually holds.
 *
 * ANKI FIELDS ARE HTML. A literal newline in a field renders as a SPACE — read back from a real
 * note — so the tool's plain text destroys every piece of the author's formatting on the way
 * out. A bulleted answer arrived on the card as
 *
 *     stratum corneum stratum granulosum stratum spinosum stratum basale
 *
 * on one line. That is not cosmetic; it is the reason the closed representation was built.
 *
 * THE DISTINCTION THAT IS THE WHOLE JOB:
 *
 *   - a newline INSIDE a paragraph MUST go on collapsing. The author hard-wraps prose at 80
 *     columns and that wrap must never appear on the card as a line break.
 *   - a boundary BETWEEN blocks must NOT collapse. That is what today's renderer loses.
 *
 * So: block separation comes from the block ELEMENT, never from inserted whitespace, and a
 * newline inside `Inline.Text` is emitted verbatim and left for HTML to collapse. `<br>` IS
 * NEVER EMITTED.
 *
 * NAMED LOSS, so that no comment here overclaims: `Lower.scala:421` DROPS `ast.LineBreak`. By
 * the time this renderer holds a `Block`, an author's deliberate two-space hard break is
 * INDISTINGUISHABLE from a soft wrap. This file therefore CANNOT preserve a deliberate break
 * and does not claim to. Recovering it costs a `Content` constructor plus a `Lower` case — a
 * different slice.
 *
 * `extract/Extractor.scala` and `extract/Cloze.scala` both reach this file, so what it renders
 * is what an Anki field holds. `extract/Tables.scala` reaches its `Html` type as well, for cell
 * escaping.
 *
 * THIS HEADER USED TO SAY that nothing outside `content/` called it, and that
 * `extract/golden/fixture-cards.txt` therefore could not move. The wiring landed and the golden
 * moved with it. The golden is now this file's regression net rather than evidence that it is
 * safely inert — which is the opposite standing, and worth reading carefully before changing
 * anything below.
 *
 * THE SHAPE MIRRORS `AsText` ON PURPOSE — `Plain`, `html`/`text`, `plain`, and the same private
 * `block`/`item`/`inline` spine. A reader is expected to put the two files side by side, and
 * every place they differ is commented below as a difference rather than left to be discovered.
 *
 * ═══════════════════════════════════════════════════════════════════════════════════════════
 * ESCAPING IS STRUCTURAL, NOT REMEMBERED — AND THE CLAIM IS SCOPED
 * ═══════════════════════════════════════════════════════════════════════════════════════════
 *
 * "Remember to escape" is not a design. `Html.Fragment` is an OPAQUE TYPE and `Html.escape` is
 * the ONLY `String => Fragment` in it. Compiler-verified on scala 3.8.4 with this project's
 * exact options:
 *
 *   `val sneak: Fragment = "raw"` inside `object AsHtml`, in THIS FILE   → type error.
 *       Opacity is scoped to the DEFINING OBJECT'S BODY, not the file and not the package,
 *       so one file is enough and a second file would buy nothing.
 *   `Html.escape(Html.escape(x))`                                        → type error.
 *       Double escaping is UNREPRESENTABLE, which is what "escaping is not idempotent" has to
 *       mean if it is a design rather than a caution.
 *   `escapedFragment + "raw"`                                            → type error.
 *       Scala 3 dropped `any2stringadd`.
 *   a `Fragment` put through `render` and assigned back to a `Fragment`  → type error.
 *       The exit is one-way. (Described rather than quoted, for the reason given below.)
 *
 * THE TWO REAL RESIDUAL HOLES, named rather than papered over: `s"$f"` and
 * `Vector(f).mkString` both COMPILE, and both yield `String`. Neither can be RETURNED from an
 * arm typed `Fragment`, so neither smuggles raw text into the output — but the sentence "raw
 * text cannot reach the output" would be false as written, and is not written here.
 *
 * THE CONTAINMENT CLAIM, and it is the only one that is true and checkable: THIS FILE NEVER
 * CALLS `render`. Grep it for a dot immediately followed by `render` and there are no hits.
 *
 * KEEPING THAT GREP CLEAN is why the probe above is described rather than quoted, and why the
 * three mentions of the unrelated `render` in `extract/Cloze.scala` below are written
 * `Cloze#render`. Do not tidy either back into dotted form: a claim nobody can check
 * mechanically is a claim that goes stale without anyone noticing, and this project has already
 * shipped ten prose claims that were not true.
 *
 * AND THE CLAIM IS SCOPED TO THIS RENDERER, because a slice cannot discharge it alone.
 * VERIFIED against `model/CardSpec.scala:178-214`: only `Body` routes through `content/`. These
 * four field values are built elsewhere and DO NOT PASS THROUGH HERE —
 *
 *   `TwoField.front`        ← `Marker.stripMarker(rawHeading)`
 *   `ThreeField.concept`    ← `ancestorTitles.lastOption`
 *   `ThreeField.descriptor` ← the same title
 *   `TableRow`'s back       ← `s"$d: $v"`, joined inside `model/`, which imports nothing here
 *
 * — so RAW TEXT CANNOT REACH THE OUTPUT **OF THIS RENDERER**, and can still reach a card
 * through those four. It is already visible in the golden: line 160 is
 * `field ⟦Front⟧ ⟦What does W + R > N buy you?⟧` and line 161 carries the same `W + R > N` in
 * the Back. Once the body is rendered through this file, the Back's `>` becomes `&gt;` and the
 * Front's stays raw. The hole closes only in the later slice that changes `CardSpec.fields` to
 * carry `Fragment`.
 *
 * RECORDED SO THAT LATER SLICE IS NOT BLOCKED BY AN UNFOUNDED FEAR, verified at
 * `extract/Extractor.scala:57-60`: `HeadingSegment.fromExtractedText(rawHeading)` and
 * `Marker.stripMarker(rawHeading)` are SEPARATE calls on the raw string. Display and identity
 * ALREADY fork there, so escaping the title would NOT move card identity.
 *
 * ALSO RECORDED, for whoever writes the field-by-field prose when the golden does move: Laika's
 * markdown emits NO raw-HTML node — checked against `Lower.scala`'s arms — so an author's
 * `<b>foo</b>` arrives as an `ast.Text`. Escaping therefore changes behaviour in BOTH
 * directions: a literal `<b>` stops being interpreted as bold by Anki, and prose like `if x < y`
 * stops being swallowed as a malformed tag. Measured on `dummy-vault` (10 notes) on 2026-08-21:
 * 2 lines carry `< > &`, 10 lines carry `"`, and 0 lines carry a brace.
 *
 * ═══════════════════════════════════════════════════════════════════════════════════════════
 * THE EMPTY-CONTENT FILTER — the sharpest trap in this file
 * ═══════════════════════════════════════════════════════════════════════════════════════════
 *
 * VERIFIED: `Body.fromExtracted(raw)` is `raw.trim.isEmpty` (`model/CardSpec.scala:24-26`), so
 * ruled refusal B6 — `SpecError.EmptyBody` — is decided on RENDERED emptiness. `<p></p>` is
 * seven non-whitespace characters. An UNCONDITIONAL wrapper would therefore make B6 STOP
 * FIRING once this renderer is wired in: a section holding only `%%a private comment%%` lowers
 * to `Paragraph(Vector())`, would render `<p></p>`, and would ship as a card with a visually
 * blank Back — and for a `2way` marker Anki then declines the reverse card, so the marker
 * silently produces one card where it promised two. Refusal → card is the ONE direction
 * `Content.scala` §(D) forbids, and NO TEST REDDENS IT BY ITSELF, because a more permissive
 * tool makes MORE cards rather than fewer.
 *
 * FOUR RULES, and together they are what makes "B6 goes on firing" an argument:
 *
 *   1. `CodeSpan` / `Emphasis` / `Strong` emit their tag ONLY if the inner is non-empty. These
 *      three are the only inlines that can turn empty content into non-empty output.
 *   2. `Inline.Deletion` IS NEVER FILTERED and its hook ALWAYS FIRES — see the alignment
 *      invariant below. Applying rule 1 naively to every inline would plant exactly that
 *      landmine.
 *   3. Block and Item render their inner FIRST; if the inner is empty the block or item
 *      contributes NOTHING — not an empty tag. This filters at the levels `AsText` already
 *      filters, on the RENDERED value, NEVER structurally.
 *   4. A whole `Block.Table` contributes nothing iff the concatenation of ALL its cells'
 *      rendered inlines is empty. `AsText` filters an all-empty table away entirely; without
 *      this rule the `<table>` scaffolding would survive and diverge.
 *
 * Because `Html.escape` is INJECTIVE and maps empty to empty, `AsHtml` block-emptiness ⟺
 * `AsText` block-emptiness for every constructor of the algebra. That is the whole reason the
 * escaper does NO trimming, NO whitespace normalisation and NO Unicode normalisation.
 *
 * `<thead>` / `<tbody>` are gated on a STRUCTURALLY empty header / rows. That is scaffolding
 * INSIDE a block which has already passed rule 4, not a structural emptiness test standing in
 * for a rendered one; it is said here so it does not read as a violation of rule 3.
 *
 * ONE DIVERGENCE FROM `AsText` IS NAMED AND NOT CLOSED. `AsText.text` ends in a trailing
 * `.trim`; `AsHtml.html` has no counterpart, because trimming an HTML fragment is a different
 * operation on a different thing. So for content that renders to WHITESPACE ONLY — a
 * `Paragraph(Vector(Text("   ")))` — `AsText` yields `""` while this renders `<p>   </p>`, and
 * B6 would not fire on it. NOBODY HAS ESTABLISHED whether `Lower` can produce such a block from
 * a parsed note; this is a stated gap, not a measured occurrence, and it is not something this
 * slice was asked to decide. `AsHtml.test.scala`'s B6 aggregate states the same scope limit.
 *
 * ═══════════════════════════════════════════════════════════════════════════════════════════
 * CLOZE
 * ═══════════════════════════════════════════════════════════════════════════════════════════
 *
 * `Html.clozeDeletion` is the one place `{{`, `::` and `}}` are meant to exist in this project
 * once the extract layer moves over. It applies the wrapper AFTER escaping, which is exactly
 * why a `<` inside a deletion cannot break the deletion — it is already `&lt;` when the wrapper
 * goes on, not because anyone remembered.
 *
 * BRACES IN AUTHOR TEXT ARE ESCAPED UNIFORMLY, `{`→`&#123;` and `}`→`&#125;`. The hazard is
 * live at HEAD: `extract/Cloze.scala:128` interpolates `s"{{c${n}::$inner}}"` without inspecting
 * `inner`, so a highlight reading `==a mapN over }} two validations==` emits a field holding TWO
 * `}}` sequences where the tool intends exactly one — an ambiguous field, produced with no error
 * anywhere. What Anki then makes of it is the unverified part, and is not asserted.
 *
 * WHAT IS ASSERTED HERE IS ONLY WHAT THE TOOL EMITS. Whether Anki's cloze scan reads the stored
 * field BEFORE or AFTER entity decoding is UNVERIFIED — it cannot be verified without contacting
 * a live collection — so no claim about what Anki will then do appears in this file or in its
 * test. Escaping is chosen because it is MONOTONE-SAFE with respect to that unverified
 * mechanism, and the two facts that argument rests on are BOTH ABOUT WHAT THE TOOL EMITS: the
 * emitted field carries no author-written brace at all, only the wrappers `Html.clozeDeletion`
 * put there; and post-decoding the emitted field is character-for-character what pass-through
 * would have produced. Browsers decode `&#123;` in text either way, so the card DISPLAY is
 * identical. Measured: zero braces in all 10 `dummy-vault` notes, and
 * the golden's only 5 brace-bearing lines are the tool's own `{{cN::`, so this moves the golden
 * by zero bytes and is reversible for zero bytes if the author rules otherwise.
 *
 * PARTIAL (cloze-only) escaping was rejected: it makes one body text render two ways depending
 * on the note type, which is the `cellSource` / `CellDisplay` shape this codebase paid to make
 * deliberate, re-introduced by accident.
 *
 * ── THE HOOK TYPE IS DELIBERATELY INCOMPATIBLE WITH `AsText`'S ──────────────────────────────
 *
 * `(Option[Int], Fragment) => Fragment` here versus `(Option[Int], String) => String` there.
 * That is not an oversight: it means `Cloze.fromLowered`'s PASS 1 CANNOT BE POINTED AT THIS
 * FILE by accident, and pass 1 must not be, for two independent reasons.
 *
 *   IDENTITY. `extract/Cloze.scala:58-59` uses pass 1's `inner` STRING as the unlabelled
 *   group's KEY, and that key decides grouping → ordinals → which `cN` carries which scheduling
 *   history. Escaping is injective, so escaping alone moves nothing; RENDERING is not. `==foo==`
 *   and `==*foo*==` both key as `foo` today and correctly fire
 *   `SpecError.AmbiguousClozeDeletion`; keyed on HTML they are `foo` and `<em>foo</em>` — two
 *   distinct groups, and the ratified refusal SILENTLY BECOMES TWO CARDS.
 *
 *   THE AUTHOR. `SpecError.AmbiguousClozeDeletion(where, duplicate)` shows that key back to the
 *   author. An HTML key would report `<em>foo</em>` — a refusal written in a markup language
 *   they never typed.
 *
 * `AsText` stays IDENTITY-ADJACENT (pass 1); `AsHtml` is DISPLAY-ONLY (pass 2). Same fork as
 * `Tables.cellSource` versus `Tables.CellDisplay`.
 *
 * ── THE ALIGNMENT INVARIANT, WHICH NOW SPANS TWO FUNCTIONS ──────────────────────────────────
 *
 * `AsHtml.html` FIRES ITS `deletion` HOOK EXACTLY ONCE PER `Inline.Deletion`, IN DOCUMENT
 * ORDER, IDENTICALLY FOR ANY HOOK. It is identical for any hook because every filter in this
 * file runs AFTER the inner render has returned — rules 2 and 3 above are what make that true,
 * and a cell is rendered exactly once even though its emptiness is consulted twice.
 *
 * `AsText.text` states and holds the same invariant, and `extract/Cloze.scala:120-133` depends
 * on it: `Cloze#render` walks a pre-computed ordinal iterator in step with the callbacks and
 * calls `misaligned` — a `sys.error` — if they fall out of step. When the extract layer moves
 * over, that becomes a claim that TWO walks agree. This project has already shipped one comment
 * asserting a branch unreachable that was not, so the claim is not left to prose:
 * `AsHtml.test.scala`'s differential compares the label sequences the two renderers fire over
 * the same blocks — deletions in a paragraph, in a list item, in a table cell, nested inside an
 * `Emphasis`, one whose sibling renders empty, and one whose own inner renders empty.
 *
 * ORDINALS ARE NOT COMPUTED HERE and no ordinal-consuming hook ships in this file. They are
 * assigned positionally in `Cloze#render`, and that logic stays there.
 *
 * ═══════════════════════════════════════════════════════════════════════════════════════════
 * WHAT IS DELIBERATELY ABSENT
 * ═══════════════════════════════════════════════════════════════════════════════════════════
 *
 * NO ERROR CHANNEL. `AsHtml` is TOTAL. Refusal already happened in `Lower`, upstream of key
 * derivation. An `Either` here would be the one way this file could reach the orphan machinery:
 * a refusal at render time is an absent key, and an absent key is an orphan flagged against a
 * real synced note.
 *
 * NO HTML `cellDisplay`. Nothing would call it, and it would be this project's first unpinned
 * constructor path. THE OWED SHAPE, recorded so it is not invented under pressure later: when
 * table cards are rendered to HTML they must go through the INJECTED `Tables.CellDisplay`,
 * while `Tables.cellSegment` is FROZEN and feeds identity.
 *
 * NO `deletions` COLLECTOR. `AsText.scala:82-91` rules it: derive occurrences by calling the
 * renderer with a collecting hook, never write a second walk beside it. Two walks over one tree
 * with different notions of "container" is this project's signature defect.
 *
 * NO `progressive:` OR LIST-STYLE KNOB. The plain `<ul><li>` this renderer already emits IS the
 * field the "Cloze Sequence" note type wants; the difference between a list shown whole and a
 * list revealed step by step lives entirely in the note type and its templates. Which of the two
 * a section gets is the author's explicit marker, never inferred from the body.
 *
 * NO GENERAL `attrs` PARAMETER and no `element(name: String, …)`. Either would reopen the hole
 * the opaque type just shut. Tag names come from the enum; the single attribute this renderer
 * emits comes from `codeBlock`.
 *
 * ═══════════════════════════════════════════════════════════════════════════════════════════
 * NAMED LIMIT: TABLE BORDERS
 * ═══════════════════════════════════════════════════════════════════════════════════════════
 *
 * Anki's stock Basic and Cloze CSS has no table rules, so a `<table>` sent to one of those note
 * types renders as UNRULED columns. This file does not inline a `style=` attribute into every
 * field to compensate. Styling belongs to a note type, and the note types this tool writes to
 * are not ours to restyle from inside a field value.
 */
object Html:

  /** An HTML fragment: text that is SAFE TO CONCATENATE into a field value because everything
    * in it has already been escaped or is a tag this file emitted.
    *
    * OPAQUE, and the opacity is the whole mechanism — see the file comment. `Html.escape` is the
    * only way in; `render` is the only way out, and it is one-way.
    */
  opaque type Fragment = String

  /** Every element this renderer can emit. A CLOSED SET, so a tag name is never a caller's
    * string.
    */
  enum Tag:
    case P, Ul, Ol, Li, Table, Thead, Tbody, Tr, Th, Td, Pre, Code, Em, Strong, Mark

  /** THE ONLY PLACE A TAG NAME IS A STRING IN THIS PROJECT. */
  private def name(t: Tag): String = t match
    case Tag.P      => "p"
    case Tag.Ul     => "ul"
    case Tag.Ol     => "ol"
    case Tag.Li     => "li"
    case Tag.Table  => "table"
    case Tag.Thead  => "thead"
    case Tag.Tbody  => "tbody"
    case Tag.Tr     => "tr"
    case Tag.Th     => "th"
    case Tag.Td     => "td"
    case Tag.Pre    => "pre"
    case Tag.Code   => "code"
    case Tag.Em     => "em"
    case Tag.Strong => "strong"
    case Tag.Mark   => "mark"

  val empty: Fragment = ""

  /** THE ONLY `String => Fragment`.
    *
    * ONE CHARACTER-WISE PASS, deliberately NOT a chain of `.replace` calls. A chain is
    * order-dependent — it is correct only while `&` stays first — and the next editor reorders
    * it. A single pass has no order to get wrong.
    *
    * `'` IS NOT ESCAPED, and the reason is STRUCTURAL rather than aesthetic: the only
    * attribute-bearing constructor in this file is `codeBlock`, and it always DOUBLE-quotes. One
    * escaper covering `& < > "` is correct in text position AND in double-quoted-attribute
    * position, which is why `class="language-…"` needs no second escaper.
    *
    * `{` and `}` are escaped for the cloze reason argued in the file comment. What is asserted
    * is only what this function emits.
    *
    * INJECTIVE, AND MAPS EMPTY TO EMPTY. Both are load-bearing: they are what make `AsHtml`
    * block-emptiness coincide with `AsText` block-emptiness, and hence what preserves ruled
    * refusal B6. So there is NO trimming, NO whitespace normalisation and NO Unicode
    * normalisation here — ever.
    */
  def escape(raw: String): Fragment =
    val out = new StringBuilder(raw.length)
    raw.foreach {
      case '&' => out.append("&amp;")
      case '<' => out.append("&lt;")
      case '>' => out.append("&gt;")
      case '"' => out.append("&quot;")
      case '{' => out.append("&#123;")
      case '}' => out.append("&#125;")
      case c   => out.append(c)
    }
    out.toString

  /** NO SEPARATOR. Block separation comes from the block element, never from whitespace, so
    * `<p>a</p><p>b</p>` and not `<p>a</p>\n<p>b</p>`.
    *
    * Decided against a separator for source legibility: the golden escapes `\n` one-way inside
    * `⟦…⟧`, so a separator would make each golden line LONGER rather than more readable, it
    * would need a `<pre>` exception (a second rule), and it would make whitespace carry meaning
    * in a renderer whose one rule is that only tags do. Cheap to add later against a golden it
    * can be measured on.
    */
  def concat(fs: Vector[Fragment]): Fragment = fs.mkString

  /** DUMB BY DESIGN: it wraps whatever it is given and NEVER filters.
    *
    * Every emptiness decision in this project belongs to the caller that knows which level it
    * is at — see the four rules in the file comment. A filter hidden in here would apply the
    * same rule to a `<td>` (which must survive empty, to keep a ragged table's columns aligned)
    * and to a `<p>` (which must not, or ruled refusal B6 stops firing).
    */
  def element(t: Tag, inner: Fragment): Fragment =
    s"<${name(t)}>$inner</${name(t)}>"

  /** `<pre>` IS MANDATORY, NOT STYLISTIC. Without it the `sql` block in
    * `dummy-vault/Replication.md` collapses onto one line — the exact defect this work exists to
    * fix, arriving inside code.
    *
    * `class="language-…"` ENABLES NOTHING TODAY. Nothing in this project and nothing in stock
    * Anki highlights code. It is carried because Laika really reports the language, so emitting
    * it promises something real about the input; it is a hook for a note type we own, not a
    * feature.
    *
    * HAZARD, stated as a hazard and NOT as a guarantee: HTML drops a newline immediately
    * following a `<pre>` start tag. Whether `Block.Code.text` can begin with one is NOT
    * established here, and no defensive leading newline is inserted to pre-empt it.
    */
  def codeBlock(language: Option[String], inner: Fragment): Fragment =
    val code = language match
      case Some(l) => s"""<${name(Tag.Code)} class="language-${escape(l)}">$inner</${name(Tag.Code)}>"""
      case None    => element(Tag.Code, inner)
    element(Tag.Pre, code)

  /** ONE TABLE ROW, drawn with its headers, and any cell may be BLANKED.
    *
    * WHY THIS LIVES HERE AND NOT IN `extract/`. It takes RAW text and escapes it, so the only
    * String-to-Fragment step is still [[escape]]. Built in `extract/` instead, it would have to
    * concatenate markup around values that were escaped somewhere else — which is exactly the
    * shape the opaque `Fragment` exists to make impossible, and the shape that would let one
    * un-escaped value through on the day somebody adds a fourth caller.
    *
    * THE BLANK IS MARKUP THIS FILE OWNS, never author text, so it cannot be forged by a table
    * cell that happens to contain the same characters.
    *
    * SHAPE, NOT PROSE. A row card used to render its answer as `"$header: $value"` joined by
    * newlines — which HTML collapses to spaces, so it arrived as one run-on line. The table is
    * the point: which column a value sits under is what distinguishes one descriptor from
    * another, and a joined string throws that away.
    */
  def rowTable(headers: Vector[String], cells: Vector[Option[String]]): Fragment =
    val head = element(
      Tag.Tr,
      concat(headers.map(h => element(Tag.Th, escape(h)))),
    )
    val body = element(
      Tag.Tr,
      concat(cells.map {
        case Some(value) => element(Tag.Td, escape(value))
        case None        => element(Tag.Td, blankCell)
      }),
    )
    element(Tag.Table, concat(Vector(head, body)))

  /** The placeholder standing in for a blanked cell. A `Fragment` built from a literal this
    * file controls — the one place raw markup legitimately becomes a Fragment without passing
    * through [[escape]], and it contains no author text at all.
    */
  private val blankCell: Fragment = """<span class="blank">[&hellip;]</span>"""

  /** THE ONE PLACE `{{`, `::` AND `}}` BELONG.
    *
    * The wrapper goes on AFTER escaping — that ordering, and not a remembered special case, is
    * why a `<` inside a deletion cannot break the deletion. `inner` is already a `Fragment`, so
    * it cannot be raw author text.
    */
  def clozeDeletion(ordinal: Int, inner: Fragment): Fragment =
    s"{{c${ordinal}::$inner}}"

  /** THE TWO SPELLINGS ANKI RECOGNISES FOR MATHS, and the only place either appears.
    *
    * NOT AN ELEMENT, BECAUSE THERE IS NO TAG TO REACH FOR. [[Tag]] is closed and holds no
    * `span`, deliberately. MathJax does not want one either: it scans a field's TEXT for these
    * delimiters, so wrapping in markup would hide the maths rather than mark it.
    *
    * READ OUT OF THE CONFIG ANKI SHIPS, not assumed — `_aqt/data/web/js/mathjax.js` in aqt
    * 25.9.5, against the MathJax 3.2.2 bundled beside it. `displayMath` is `[["\\[","\\]"]]`
    * and nothing else; `inlineMath` is unset, so MathJax's own `\(…\)` default stands. `$` is
    * a delimiter in NEITHER mode, and `processEscapes` is off, so the author's dollars mean
    * nothing here and must not be carried over.
    *
    * THE WRAPPER GOES ON AFTER ESCAPING, for [[clozeDeletion]]'s reason rather than by
    * imitation: `inner` is already a `Fragment`, so no author character can reach these
    * delimiters and split them. Braces inside the TeX are therefore `&#123;` in the stored
    * field. A browser decodes those while parsing, so MathJax reads the braces it needs —
    * REASONED, and recorded as owed a measurement in `docs/MATHS-ON-A-CARD.md`.
    */
  def mathInline(inner: Fragment): Fragment = s"""\\($inner\\)"""

  /** Display maths. Same argument as [[mathInline]], the other delimiter pair. */
  def mathDisplay(inner: Fragment): Fragment = s"""\\[$inner\\]"""

  /** Emptiness of the fragment itself. Written as a length test rather than `f.isEmpty` so that
    * a reader inside this object — where `Fragment` is transparently `String` — does not have to
    * work out whether it recurses.
    */
  extension (f: Fragment) def isEmpty: Boolean = f.length == 0

  /** THE ONE-WAY EXIT. There is no `Fragment` constructor taking this back. */
  extension (f: Fragment) def render: String = f

object AsHtml:

  /** The hook that renders a deletion as its bare inner content — no `{{cN::…}}`.
    *
    * Mirrors `AsText.Plain`, which is what an ordinary (non-cloze) card wants: the `==` markers
    * are the author's instruction to the tool, not content.
    */
  val Plain: (Option[Int], Html.Fragment) => Html.Fragment = (_, inner) => inner

  /** Blocks to the HTML fragment an Anki field holds.
    *
    * The block-level filter lives HERE, mirroring `AsText.text`, and it is on the RENDERED
    * value. There is NO trailing `.trim` — see the named, unclosed divergence in the file
    * comment.
    *
    * @param deletion how an `Inline.Deletion` renders, given its group label (`None` for an
    *                 unlabelled `==text==`) and its ALREADY-RENDERED inner fragment. Fires
    *                 exactly once per deletion, in document order, for any hook. No index and
    *                 no node is passed: `Content.scala` §(D) bans handing a caller the derived
    *                 structural `==` on `Inline.Deletion`.
    */
  def html(
      blocks: Vector[Block],
      deletion: (Option[Int], Html.Fragment) => Html.Fragment,
  ): Html.Fragment =
    Html.concat(blocks.map(blockHtml(_, deletion)).filter(f => !f.isEmpty))

  /** The body as an ordinary (non-cloze) card carries it. */
  def plain(blocks: Vector[Block]): Html.Fragment = html(blocks, Plain)

  /** A RUN OF INLINES WITH NO ENCLOSING BLOCK, which is what a HEADING is.
    *
    * NOT `plain(Vector(Block.Paragraph(is)))`, and the difference is visible on a card: that
    * would wrap the heading in `<p>`, and a heading is a field's whole value rather than a
    * paragraph inside one. It pairs with `Lower.spans`, which exists for the same reason —
    * Laika keeps a `Section`'s header outside its content, so a heading is a run of spans and
    * never a block.
    *
    * RULE 1 IS NOT APPLIED HERE and must not be. Emptiness of a heading is decided upstream, by
    * the key derivation, which fails a whole file when a heading extracts to nothing. Filtering
    * here would turn that loud failure into a card with a blank face.
    */
  def spans(is: Vector[Inline]): Html.Fragment = inlines(is, Plain)

  // ══════════════════════════════════════════════════════════════════ blocks ════

  private def blockHtml(
      b: Block,
      deletion: (Option[Int], Html.Fragment) => Html.Fragment,
  ): Html.Fragment = b match
    // RULE 3 THROUGHOUT THIS MATCH: render the inner FIRST, then decide. An unconditional
    // wrapper would emit `<p></p>` — seven non-whitespace characters — and retire ruled refusal
    // B6, which is decided on `raw.trim.isEmpty`.
    case Block.Paragraph(content) =>
      wrap(Html.Tag.P, inlines(content, deletion))

    // THE FIRST PLACE THE `Bullets` / `Numbered` DISTINCTION IS VISIBLE AT ALL. `AsText` renders
    // the two identically, deliberately and pinned there; the algebra has carried the
    // distinction since `Content.scala` precisely so that this renderer could use it.
    case Block.Bullets(items) =>
      wrap(Html.Tag.Ul, Html.concat(items.map(itemHtml(_, deletion)).filter(f => !f.isEmpty)))

    case Block.Numbered(items) =>
      wrap(Html.Tag.Ol, Html.concat(items.map(itemHtml(_, deletion)).filter(f => !f.isEmpty)))

    case Block.Code(language, text) =>
      val inner = Html.escape(text)
      if inner.isEmpty then Html.empty else Html.codeBlock(language, inner)

    // CELLS ARE NEVER FILTERED INDIVIDUALLY, AND THAT IS A DELIBERATE ASYMMETRY WITH `AsText`.
    // It will read as an inconsistency, so: `AsText` drops an empty cell ENTIRELY, which is
    // right for a flat newline join and CATASTROPHIC for a grid. Laika's `applyColumnOptions`
    // PADS a short row with a zero-block cell, so an empty `Cell` is a legal parser-produced
    // value; dropping it would shift every later column in that row left, silently, producing a
    // wrong-looking table with no error anywhere. So an empty cell emits `<td></td>`.
    //
    // RULE 4 is applied to the table AS A WHOLE instead: it contributes nothing iff every one of
    // its cells renders empty, which is exactly when `AsText` filters the whole table away.
    //
    // EACH CELL IS RENDERED EXACTLY ONCE, before either decision is taken. That is not a
    // micro-optimisation — the deletion hook must fire once per deletion, and rendering a cell
    // twice to test it for emptiness would fire it twice.
    case Block.Table(header, rows) =>
      val headerCells = header.map(c => inlines(c.content, deletion))
      val bodyRows    = rows.map(_.map(c => inlines(c.content, deletion)))
      val anyContent =
        headerCells.exists(f => !f.isEmpty) || bodyRows.exists(_.exists(f => !f.isEmpty))
      if !anyContent then Html.empty
      else
        // `<thead>` / `<tbody>` are gated STRUCTURALLY, on whether there are any header cells or
        // any body rows at all. That is scaffolding inside a block which has already passed rule
        // 4 — not a structural stand-in for a rendered emptiness test.
        val thead =
          if headerCells.isEmpty then Html.empty
          else
            Html.element(
              Html.Tag.Thead,
              Html.element(Html.Tag.Tr, Html.concat(headerCells.map(Html.element(Html.Tag.Th, _)))),
            )
        val tbody =
          if bodyRows.isEmpty then Html.empty
          else
            Html.element(
              Html.Tag.Tbody,
              Html.concat(
                bodyRows.map(r =>
                  Html.element(Html.Tag.Tr, Html.concat(r.map(Html.element(Html.Tag.Td, _))))
                )
              ),
            )
        Html.element(Html.Tag.Table, Html.concat(Vector(thead, tbody)))

  /** TIGHT `<li>` — AND IT IS A CHOICE, NOT A REPRODUCTION.
    *
    * An item holding exactly one `Block.Paragraph` renders its inlines BARE inside the `<li>`;
    * every other item renders its blocks normally. CommonMark decides tight versus loose from
    * blank lines in the SOURCE, and that information is GONE by the time we hold a `Block`: a
    * loose item's `ForcedParagraph` lowers to the same `Block.Paragraph` as a tight one. So this
    * is a rule this project picked, not markdown's rule reproduced.
    *
    * THE REASON IS A PREDICTION AND IS WORDED AS ONE. NOBODY HAS RENDERED THIS CARD. Read off
    * `resources/note-types/cloze-sequence/styling.css`: hiding is `.hidden-cloze { color: transparent;
    * font-size: 0 }` (lines 12-15) with `.hidden-cloze::before { content: "(...)"; font-size:
    * 20px }` (lines 16-20) — NOT `display: none`. A `<p>`'s user-agent `margin: 1em 0` resolves
    * against its own computed font-size, so `<li><p>x</p></li>` would give an item zero margin
    * while hidden and roughly 20px once revealed. PREDICTED CONSEQUENCE: the sequence card
    * reflows and everything below it jumps, once per reveal keystroke.
    *
    * DECLINED ALTERNATIVE, recorded with its reason: unwrapping only a LEADING paragraph, so
    * that a nested item reads `<li>Fruit<ul>…</ul></li>`, is closer to CommonMark — but it
    * special-cases by POSITION rather than by SHAPE, which reads as a bug and gets "fixed".
    * `dummy-vault` contains ZERO nested lists (`Content.scala` §(I)), so either choice moves the
    * golden by zero bytes and this is revisitable at no cost.
    */
  private def itemHtml(
      item: Item,
      deletion: (Option[Int], Html.Fragment) => Html.Fragment,
  ): Html.Fragment = item match
    case Item(Vector(Block.Paragraph(spans))) =>
      wrap(Html.Tag.Li, inlines(spans, deletion))
    case Item(blocks) =>
      wrap(Html.Tag.Li, Html.concat(blocks.map(blockHtml(_, deletion)).filter(f => !f.isEmpty)))

  /** Rule 3 in one place: wrap, unless the inner rendered empty, in which case contribute
    * NOTHING — not an empty tag.
    */
  private def wrap(t: Html.Tag, inner: Html.Fragment): Html.Fragment =
    if inner.isEmpty then Html.empty else Html.element(t, inner)

  // ══════════════════════════════════════════════════════════════════ inlines ════

  /** NO SEPARATOR AND NO FILTER between inlines, mirroring `AsText`'s bare `.mkString`. The
    * filtering levels are block and item, and nowhere else.
    */
  private def inlines(
      content: Vector[Inline],
      deletion: (Option[Int], Html.Fragment) => Html.Fragment,
  ): Html.Fragment =
    Html.concat(content.map(inlineHtml(_, deletion)))

  private def inlineHtml(
      i: Inline,
      deletion: (Option[Int], Html.Fragment) => Html.Fragment,
  ): Html.Fragment = i match
    // THE NEWLINE IS LEFT ALONE. This is the hard-wrap rule and the whole point of the file:
    // HTML collapses a newline inside a block to a space by itself, which is what turns the
    // author's 80-column wrap back into flowing prose. NEVER `<br>`.
    case Inline.Text(text) => Html.escape(text)

    // RULE 1 for the next three: emit the tag ONLY if the inner is non-empty. These are the only
    // inlines that can turn empty content into non-empty output, and `<p><em></em></p>` would
    // retire ruled refusal B6 exactly as `<p></p>` would.
    case Inline.CodeSpan(text) =>
      val inner = Html.escape(text)
      if inner.isEmpty then Html.empty else Html.element(Html.Tag.Code, inner)

    case Inline.Emphasis(content) =>
      val inner = inlines(content, deletion)
      if inner.isEmpty then Html.empty else Html.element(Html.Tag.Em, inner)

    // A HIGHLIGHT THAT IS NOT A CLOZE — `==text==` since 2026-08-28, when `==<<text>>==`
    // became the cloze. `<mark>` is what Obsidian itself renders a highlight as, so a note
    // reads on a card the way it reads in the editor.
    //
    // RULE 1 APPLIES HERE TOO, and for the reason given above rather than by imitation: an
    // empty `<mark></mark>` is non-empty output built from empty content, which is exactly
    // what ruled refusal B6 is decided against.
    case Inline.Highlight(content) =>
      val inner = inlines(content, deletion)
      if inner.isEmpty then Html.empty else Html.element(Html.Tag.Mark, inner)

    case Inline.Strong(content) =>
      val inner = inlines(content, deletion)
      if inner.isEmpty then Html.empty else Html.element(Html.Tag.Strong, inner)

    // RULE 2. NEVER FILTERED, AND THE HOOK ALWAYS FIRES — even when the inner renders empty and
    // even when the enclosing block is about to be filtered away. Applying rule 1 here would
    // break the alignment invariant that `Cloze#render`'s `misaligned` check rests on, and it
    // would break it silently, because a hook that is not called leaves no trace.
    case Inline.Deletion(label, content) =>
      deletion(label, inlines(content, deletion))

    // RULE 1 AGAIN, and for the same reason as the tags above rather than by imitation: an
    // empty `\(\)` is four characters of non-empty output built from empty content, which is
    // precisely what would retire ruled refusal B6. The TeX is escaped first and the
    // delimiters go on after, so no author character can reach them.
    case Inline.MathInline(tex) =>
      val inner = Html.escape(tex)
      if inner.isEmpty then Html.empty else Html.mathInline(inner)

    case Inline.MathDisplay(tex) =>
      val inner = Html.escape(tex)
      if inner.isEmpty then Html.empty else Html.mathDisplay(inner)
