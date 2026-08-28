# What it would take for maths to reach a card

_Written 2026-08-28, from a design conversation between Marc and Claude. **Nothing here is
built.** Claims marked VERIFIED were established by running something in this session or by
reading the file cited; everything else is reasoning, and says so. It opens with the answer rather than the three-layer TLDR /
Summary / Full form, following its siblings — `PARSER-DISAGREEMENTS.md`, `CLOZE-REDESIGN.md`,
`EDIT-IN-OBSIDIAN.md`, `REVIEW-QUEUE.md`._

**This document is row 5 of `PARSER-DISAGREEMENTS.md`, opened up.** That document owns the
family and the argument for a check across it; this one owns the single member, because the
member turns out to carry a cost the family framing does not predict. Two amendments this
document owes back to row 5 are named at the foot.

---

## The one-paragraph answer

Obsidian renders `$…$` and `$$…$$` with MathJax; this tool has no notion of `$` at all, so the
delimiters and the TeX arrive on the card as literal text. That much is already row 5. What the
measurement adds is worse in one direction and better in another. Better: **almost all TeX
survives verbatim**, so the common case is a pure delimiter problem — Anki wants `\(…\)` and
`\[…\]`, not `$$`. Worse: **two constructs are silently corrupted before this tool ever sees
them**, because the TeX goes through Laika's inline parsers like any other prose — `\\` collapses
to `\`, destroying every multi-line `align`, and paired `_` is consumed as emphasis, so `x_1 + y_1`
extracts as `x1 + y1`. And worst: **the obvious fix is itself a re-keying event.** A maths node
shaped like `ObsidianComment` drops out of Laika's `extractText`, and a heading containing maths
therefore changes key the day maths starts being parsed — an orphan plus a history-less
replacement, for a card that was working. The feature and the loss are the same edit, and whether
they can be separated is a question about the TYPE rather than about the renderer.

---

## What the tool does today, measured

VERIFIED BY EXECUTION 2026-08-28, driving `ObsidianSyntax.markupParser` — the production parser,
laika-core 1.3.2 with `GitHubFlavor` and this project's six span parsers — over each snippet and
dumping the tree.

| source | parses to | `extractText` |
|---|---|---|
| `$$ \forall f  \text{Id} \circ f = f \circ \text{Id} = f ,   $$` | one `Text` | verbatim |
| `$$\text{Id}$$` | one `Text` | verbatim |
| `Notation (given 2 sets, $A$ and $B$)` | one `Text` | verbatim |
| `$$\begin{align} a &= b \\ c &= d \end{align}$$` | one `Text` | **`\\` → `\`** |
| `$$x_1 + y_1 = z_1$$` | `Text`, `Emphasized`, `Text` | **`$$x1 + y1 = z_1$$`** |

**Three findings, in order of how much they change.**

1. **Most TeX is untouched.** `\forall`, `\circ`, `\text{…}`, braces, the dollars themselves — all
   verbatim. A backslash before a letter is not a markdown escape, and braces are not markdown at
   all. So the card that prompted this conversation — `Identity Function.md` § *Special Property* —
   has nothing wrong with it but its delimiters.

2. **`\\` is eaten.** Markdown escapes ASCII punctuation, and `\` is ASCII punctuation, so `\\`
   is the escape for a literal backslash and arrives as one. `\\` is TeX's row separator inside
   `align`, `gather`, `array` and `cases` — so **every multi-line maths block in the vault is
   already corrupt before any of this tool's own code runs.** Nothing downstream can recover it;
   the second backslash is gone from the parse tree.

3. **Paired `_` becomes emphasis, and the underscores vanish.** `x_1 + y_1` has two underscores,
   so they pair: the span between them becomes `Emphasized` and both delimiters are consumed.
   `extractText` yields `x1 + y1`. Note the position-dependence, which is what makes this
   nastier than it first reads: in `$$x_1 + y_1 = z_1$$` the third `z_1` keeps its underscore,
   having no partner. **Whether a subscript survives depends on how many other subscripts are in
   the same paragraph.**

---

## Why the obvious fix costs a card

A maths node would follow the shape this project already uses for a construct that must not be
re-parsed as markdown: `ObsidianComment` and `ObsidianEmbed` both declare `text: String` rather
than `content: Seq[Span]`, which is deliberate — it is what keeps their inner text away from the
inline parsers.

That shape has a consequence on the identity path. Laika's `SpanContainer.extractText` is a trait
match with a silent `case _ => ""` (`laika/ast/containers.scala`), so a node which is not a
`SpanContainer` contributes nothing. VERIFIED BY EXECUTION 2026-08-28, over headings:

```
## Notation %%hidden%% here      →  extractText = "Notation  here"
## Notation ![[x.png]] here      →  extractText = "Notation  here"
## Notation [[HomSet]] here      →  extractText = "Notation HomSet here"
```

The comment and the embed are dropped; the wikilink survives, because `wikilinkParser` emits a
plain `Text` rather than a node of its own.

`Extractor` derives every non-table card's key from `HeadingSegment.fromExtractedText(
section.header.extractText)`. So:

- **today**, `## Notation (given 2 sets, $A$ and $B$)` keys as
  `notation (given 2 sets, $a$ and $b$)` — corroborated by the `inspect` run recorded in
  `PARSER-DISAGREEMENTS.md` row 5's evidence;
- **the day a maths node lands**, the same heading keys as `notation (given 2 sets,  and )`.

A changed key is not an updated card. It is an orphan — tagged, suspended, holding its review
history and claimed by nothing — plus a brand-new card starting from zero. **Shipping maths
rendering would, on that heading, cost the card it was meant to fix.**

DERIVED, not measured, and the distinction is worth keeping: the mechanism above is measured, but
"a maths node would be built that way" is a design choice nobody has made yet. It is precisely the
choice this document exists to put in front of somebody.

---

## Where it would fit

Named as placement rather than as a plan, because the shape is not decided.

| stage | what lands there |
|---|---|
| `parser/ObsidianSyntax.scala` | a seventh span parser, `.standalone` rather than `.recursive`, so the TeX is captured raw. `obsidianCommentParser` is the precedent — `("%%" ~> delimitedBy("%%"))` |
| `content/Content.scala` | one constructor carrying a `String`, on `Block.Code`'s shape; §(G), §(H) and §(I) each gain a row |
| `content/Lower.scala` | one arm, constructor to constructor |
| `content/AsText.scala` | a decision — see below. Identity-adjacent: cloze grouping and refusal B6 read it |
| `content/AsHtml.scala` | `\(…\)` / `\[…\]`, emitted through a new constructor inside `Html`, on `Html.clozeDeletion`'s pattern — wrapper applied AFTER escaping |

**Nothing in `model/`, `plan/`, `anki/` or `cli/`.** Maths is body content, not a card shape: no
new `CardSpec` case, no new marker, no new note type. The same reason a typed edge needed none.

**Nothing in Anki, either — with one caveat that is new here.** VERIFIED BY READING 2026-08-28,
from the config Anki actually ships: `_aqt/data/web/js/mathjax.js` in aqt 25.9.5, which is the
version this machine runs (pinned `aqt==25.9.5` in the launcher's `uv.lock`), against MathJax
3.2.2 bundled alongside it at `_aqt/data/web/js/vendor/mathjax`.

| setting | shipped value | consequence |
|---|---|---|
| `displayMath` | `[["\\[","\\]"]]` | `\[…\]` is the only display delimiter. `$$` is not one, and no config makes it one |
| `inlineMath` | not set | MathJax's own default stands, so `\(…\)` works — by default rather than by Anki's choice |
| `processEnvironments` | `false` | **a bare `\begin{align}…\end{align}` is NOT typeset** — an environment is only seen inside `\[…\]` |
| `processEscapes` | `false` | `$` carries no meaning to Anki at all, escaped or not |
| `packages` | `+noerrors`, `+mathtools`, **`−textmacros`** | AMS arrives in MathJax's default set. `mhchem` and `physics` are NOT loaded |

Obsidian renders `$…$` with MathJax 3 as well, so the TeX body still needs no translation between
the two and the bulk of the transport problem is still which characters mark where the maths
starts and stops.

**`processEnvironments: false` is the caveat, and it kills an idea.** It was raised early in the
conversation that produced this document that `\begin…\end` might serve on its own as the marker
Anki recognises. It cannot: Anki will not look at an environment that is not already wrapped in
`\[…\]`. Wrapping is therefore mandatory, not optional.

Whether wrapping is SUFFICIENT is NOT MEASURED, and it is the one place a TeX-level translation
might still be owed. `align` is itself a display environment, so `\[\begin{align}…\end{align}\]`
is the fragile spelling and `aligned` the robust one. If that distinction holds under Anki's
MathJax, the tool would have to rewrite the environment name and not merely swap delimiters —
which is precisely what the paragraph above claims is unnecessary. One card settles it.

**BRACES ARE ALREADY ESCAPED, AND MATHS WOULD BE THE FIRST CONTENT THAT IS FULL OF THEM.**
`Html.escape` maps `{` to `&#123;` and `}` to `&#125;` across all author text, so that a brace
somebody typed can never be read as Anki's cloze syntax. `content/AsHtml.scala` argues that
choice at length, and two of the facts it rests on are measurements maths would invalidate: that
the `dummy-vault` notes contain ZERO braces, and that the golden file's only brace-bearing lines
are the tool's own `{{cN::` wrappers. TeX is brace-dense — `\text{Id}`, `\frac{a}{b}`,
`\begin{align}` — so the day maths ships, a brace-bearing field stops being the exception.

That does not make escaping wrong. It makes it load-bearing somewhere new, and it settles
something the placement table above leaves open: **the TeX must be emitted through `Html.escape`
like any other text, never raw.** The same file names a hazard that is live at HEAD, where the
cloze wrapper is interpolated around its inner text without inspecting it, so an inner text
containing `}}` produces a field holding two `}}` sequences where the tool means one — an
ambiguous field with no error raised anywhere. `\frac{\text{a}}{b}` contains `}}`. Escaping is
what keeps that unreachable, and a raw-emitting maths node would walk straight into it.

**Raw in, escaped out, delimiters last** — and the apparent contradiction with the placement
table is not one, because the two demands sit at opposite ends of the pipeline. CAPTURE must be
raw so the inline parsers never run on the TeX; that is what saves `\\` and the subscripts.
EMISSION must be escaped so the cloze scanner never meets a brace the author wrote. The
delimiters go on last, after escaping, which is the order `Html.clozeDeletion` already uses and
the reason a `<` inside a deletion cannot break it.

---

## What is decided, and what is not

**Decided, and inherited rather than settled here** — from `PARSER-DISAGREEMENTS.md`:

- Report, never repair. Repair belongs to a formatter.
- Miss rather than over-report; a false refusal costs more than a missed one.

**Not decided, and each is Marc's:**

1. **Whether the identity path may see the maths.** Three candidates, and they are not
   equivalent. (a) The node participates in `extractText`, extracting to the raw TeX including
   delimiters — keys stay byte-identical, nothing moves, no migration, at the cost of making
   identity depend on a rendering decision that this codebase severs everywhere else and paid to
   sever. (b) The node does not participate — accept the re-key, make it visible in the golden,
   adopt it by hand as a stated migration. (c) Something at the type level makes the choice
   unrepresentable rather than remembered; no shape for this has been proposed.
2. **Whether `\\` and `_` are this feature's problem at all.** Capturing the TeX raw fixes both,
   because the inline parsers never run on it. But that is a side effect of the fix rather than
   its purpose, and it means the two corruptions are closed by row 5's remedy and not by the
   family-wide check `PARSER-DISAGREEMENTS.md` proposes. Which document owns them is open.
3. **Display-only first, or inline too.** `$$` alone is unambiguous and needs no guards. `$…$`
   needs the scanning rules — escaped `\$`, an opening `$` not followed by whitespace, a closing
   `$` not followed by a digit — which are the well-tested part of pandoc's `tex_math_dollars`
   and of `remark-math`. Inline maths already exists in the vault (`CLOZE-REDESIGN.md` names
   `$B^A$`), so display-only leaves live content broken.
4. **Whether display maths is a `Block` or an `Inline`.** A span in a paragraph is far cheaper and
   renders correctly (`<p>\[…\]</p>` is legal). A block is more honest about what display maths
   is, and forces two more exhaustive matches to answer.

---

## What must be measured

- **Does Obsidian's own renderer agree that `\\` is already broken?** The corruption measured
  above is this tool's parse. If Obsidian shows the multi-line block correctly, the two parsers
  disagree and it is a family member; if Obsidian is equally broken, the vault has been carrying
  broken maths and the fix is an improvement rather than a repair. One look in reading view.
- **How much maths is in the vault, and where.** In prose only, or in headings too? The heading
  count is the size of the re-keying cost, and it is the number decision 1 turns on. Read-only —
  a grep, and an `inspect` run.
- **Whether the vault uses maths packages Anki does not load.** The Anki half is now settled by
  reading the shipped config, above: no `mhchem`, no `physics`, and `textmacros` explicitly
  removed, so `\text{}` takes a narrower grammar than Obsidian allows. The open half is the vault
  half — whether any note reaches for them. A grep.
- **Whether `\[\begin{align}…\end{align}\]` typesets under Anki's MathJax, or whether `aligned` is
  required.** This is the only candidate found so far for a translation of the TeX BODY rather than
  of its delimiters, and it decides whether "delimiters only" survives as the shape of the feature.
  One hand-made card.
- **Whether MathJax typesets TeX whose braces arrived as `&#123;` and `&#125;`.** It should: a
  browser decodes character references while parsing the field's HTML, so MathJax reads the
  decoded text and `content/AsHtml.scala` already argues that post-decoding the emitted field is
  character-for-character what pass-through would have produced. But that argument was made about
  DISPLAY of ordinary prose, where a stray brace is a glyph, and here a brace is grammar — a
  wrong one silently changes what the TeX means rather than how it looks. It sits beside the
  question that file leaves open for the same reason, that neither can be settled without
  contacting a live collection. The same hand-made card answers both.

---

## Two amendments owed to `PARSER-DISAGREEMENTS.md` row 5

Named here rather than made there, because that file was untracked and mid-edit when this was
written and its author's work should not be swept into somebody else's commit.

1. **The description is too kind.** Row 5 reads *"prints the delimiters and the TeX verbatim"*.
   Verbatim is right for most TeX and wrong for two constructs; see the table above. Suggested:
   *"prints the delimiters, and the TeX verbatim except that `\\` collapses to `\` and paired `_`
   is consumed as emphasis"*.
2. **The status understates it.** Row 5 is SILENT in the same sense as row 4 — a construct
   printed on a card face. It is also the only row whose REMEDY is itself a silent re-keying, as
   argued above. That is a different and larger fact than the row records, and it is the reason
   this document exists.
