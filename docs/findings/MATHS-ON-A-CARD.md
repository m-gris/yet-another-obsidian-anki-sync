# What it took for maths to reach a card

> **Work on this document** — `bd list --all --spec docs/findings/MATHS-ON-A-CARD.md`
>
> Closed means built, and the closing reason says what shipped; open means outstanding. **This
> document records a measurement and the reasoning around it, never progress** — a status kept in two places goes stale in one of
> them.

_Written 2026-08-28, from a design conversation between Marc and Claude. Claims marked VERIFIED were established by running something in this session or by
reading the file cited; everything else is reasoning, and says so. It opens with the answer rather than the three-layer TLDR /
Summary / Full form, following its siblings — `PARSER-DISAGREEMENTS.md`, `CLOZE-REDESIGN.md`,
`EDIT-IN-OBSIDIAN.md`, `REVIEW-QUEUE.md`._

**This document is row 5 of `PARSER-DISAGREEMENTS.md`, opened up.** That document owns the
family and the argument for a check across it; this one owns the single member, because the
member turns out to carry a cost the family framing does not predict.

**Read the family document first if you want the WHY rather than the WHAT.** Two sections there,
added on 2026-08-29 out of the same conversation as this one, carry the general form of the
problem and deliberately do not live here: *"The status column is not six facts. It is three
mechanisms."* — which shows that maths is silent for the same reason a block reference is, that
neither can ever be caught inside the parser, and that markdown's failure channel is keyed to
resolution rather than to recognition. And *"What the tool does with a construct"* — which sets
out the five deliberate dispositions and the one accident, and locates maths in the accident.

---

## The one-paragraph answer

**Maths reaches a card, since 2026-08-29.** `$…$` and `$$…$$` are captured by a span parser and
re-emitted as `\(…\)` and `\[…\]`, which is what Anki reads. The measurement below is what the
tool did BEFORE that, and the reasoning is kept because it is what chose the shape.

Three things the measurement established, all of which still explain the design. Most TeX survives
verbatim, so the common case was only ever a delimiter problem. Two constructs were silently
corrupted before this tool saw them, because the TeX went through Laika's inline parsers like any
other prose — `\\` collapsed to `\`, destroying every multi-line `align`, and paired `_` was
consumed as emphasis, so `x_1 + y_1` extracted as `x1 + y1`. And the fix was itself a re-keying
event: a heading containing maths changes key the day maths starts being parsed.

That last one was accepted rather than routed around, and it is recorded as a one-time cost in
`content/AsText.scala`. The alternative — a node that drops out of `extractText` entirely, keying
the heading as `notation (given 2 sets,  and )` — was rejected, because it collapses two headings
differing only in their maths onto one key. What shipped is a third shape: the TeX contributes to
the key WITHOUT its dollars.

---

## What the tool did before the maths parser, measured

VERIFIED BY EXECUTION 2026-08-28, driving `ObsidianSyntax.markupParser` — the production parser,
laika-core 1.3.2 with `GitHubFlavor` and this project's span parsers, of which there were then six and are now eight — over each snippet and
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
   `align`, `gather`, `array` and `cases` — so **every multi-line maths block in the vault was
   corrupt before any of this tool's own code ran.** Nothing downstream can recover it;
   the second backslash is gone from the parse tree.

3. **Paired `_` became emphasis, and the underscores vanished.** `x_1 + y_1` has two underscores,
   so they pair: the span between them becomes `Emphasized` and both delimiters are consumed.
   `extractText` yields `x1 + y1`. Note the position-dependence, which is what makes this
   nastier than it first reads: in `$$x_1 + y_1 = z_1$$` the third `z_1` keeps its underscore,
   having no partner. **Whether a subscript survived depended on how many other subscripts were
   in the same paragraph.** The maths parser closed both of these by keeping the TeX away from
   Laika's inline parsers, which is what the next section argues for.

---

## Why the fix cost a card, and why that was accepted

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

- **before the maths parser**, `## Notation (given 2 sets, $A$ and $B$)` keyed as
  `notation (given 2 sets, $a$ and $b$)`;
- **had the node dropped out of `extractText`** — the shape this section argues against — the same
  heading would have keyed as `notation (given 2 sets,  and )`, collapsing two headings that differ
  only in their maths onto one key;
- **as shipped**, it keys as `notation (given 2 sets, a and b)`: the TeX contributes, the dollars
  do not. Pinned by `parser/ObsidianSyntax.test.scala`.

A changed key is not an updated card. It is an orphan — tagged, suspended, holding its review
history and claimed by nothing — plus a brand-new card starting from zero. **Shipping maths
rendering cost, on that heading, the card it was meant to fix.** One card in the vault carried
maths in its heading; the cost was accepted for the feature and is recorded as a one-time re-key
in `content/AsText.scala`.

DERIVED, not measured, and the distinction is worth keeping: the mechanism above is measured, but
"a maths node would be built that way" is a design choice nobody has made yet. It is precisely the
choice this document exists to put in front of somebody.

---

## Where it landed

Written as placement before it was built; this is where it went.

| stage | what lands there |
|---|---|
| `parser/ObsidianSyntax.scala` | one more span parser — seventh in the registration list, eighth overall — `.standalone` rather than `.recursive`, so the TeX is captured raw. `obsidianCommentParser` is the precedent — `("%%" ~> delimitedBy("%%"))` |
| `content/Content.scala` | two constructors carrying a `String`, `MathInline` and `MathDisplay`, on `Block.Code`'s shape; §(G), §(H) and §(I) each gain a row |
| `content/Lower.scala` | two arms, constructor to constructor |
| `content/AsText.scala` | decided: the TeX contributes, the dollars do not. Identity-adjacent — cloze grouping and the empty-body refusal read it |
| `content/AsHtml.scala` | `\(…\)` / `\[…\]`, emitted through a new constructor inside `Html`, on `Html.clozeDeletion`'s pattern — wrapper applied AFTER escaping |

**Nothing in `model/`, `plan/`, `anki/` or `cli/`.** Maths is body content, not a card shape: no
new `CardSpec` case, no new marker, no new note type. The same reason a typed edge needed none.

**Nothing in Anki, either — with one caveat that is new here.** VERIFIED BY READING 2026-08-28,
from the config Anki actually ships: `_aqt/data/web/js/mathjax.js` in aqt 25.9.5, which is the
version this machine ran when this was read, against MathJax
3.2.2 bundled alongside it at `_aqt/data/web/js/vendor/mathjax`.

| setting | shipped value | consequence |
|---|---|---|
| `displayMath` | `[["\\[","\\]"]]` | `\[…\]` is the only display delimiter. `$$` is not one, and no config makes it one |
| `inlineMath` | not set | MathJax's own default stands, so `\(…\)` works — by default rather than by Anki's choice |
| `processEnvironments` | `false` | **a bare `\begin{align}…\end{align}` is NOT typeset** — an environment is only seen inside `\[…\]` |
| `processEscapes` | `false` | `$` carries no meaning to Anki at all, escaped or not |
| `packages` | `+noerrors`, `+mathtools`, **`−textmacros`** | AMS arrives in MathJax's default set. `mhchem` and `physics` are NOT loaded |

Obsidian is understood to render `$…$` with MathJax 3 as well — reasoning, not measured; nobody has examined Obsidian's side — so the TeX body needs no translation between
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
choice at length, and it rested on the fixture vault holding almost no braces of its own. TeX is
brace-dense — `\text{Id}`, `\frac{a}{b}`, `\begin{align}` — so once maths shipped, a
brace-bearing field stopped being the exception. The fixture vault now carries a brace-dense note
for exactly that reason.

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

**Settled by building it**, and the reasoning for each sits at
`parser/ObsidianSyntax.scala`'s maths node rather than here, because that is where a later reader
meets it:

- **The identity path DOES see the maths.** The node is a `TextContainer`, so `extractText` yields
  the TeX body. The alternative — invisible to extraction, copied from the comment node — was
  tried and **cost a card key**: `# Notation (Given 2 sets, $A$ and $B$)` keyed on
  `notation (given 2 sets, and )`, letters gone rather than merely dollars, so two headings
  differing only in their maths collapsed onto one key. The parser's tests pin that they stay
  distinct.
- **Inline as well as display.** `$…$` ships with the scanning rules — an opening `$` not followed
  by whitespace, a closing one not preceded by whitespace nor followed by a digit. Display-only
  would have left live content broken.
- **Both are inlines**, not blocks. Display maths as a span in a paragraph renders correctly and
  costs two fewer exhaustive matches.

**Still open, and Marc's:**

1. **Whether `\\` and `_` are this feature's problem at all.** Capturing the TeX raw fixes both,
   because the inline parsers never run on it — but that is a side effect of the fix rather than
   its purpose, and it means the two corruptions are closed by row 5's remedy rather than by the
   family-wide check `PARSER-DISAGREEMENTS.md` proposes. Which document owns them is open.

---

## Two amendments owed to `PARSER-DISAGREEMENTS.md` row 5 — MADE 2026-08-29

Kept here as a record of what changed and why, rather than deleted once done. When this document
was first written the family document was untracked and mid-edit, so the amendments were named
rather than made; both have since been applied at Marc's instruction, in the same pass that added
the two framing sections named at the top.

1. **The description was too kind.** Row 5 read *"prints the delimiters and the TeX verbatim"*.
   Verbatim is right for most TeX and wrong for two constructs; see the table above. It now reads
   *"prints the delimiters, and the TeX verbatim EXCEPT that `\\` collapses to `\` and paired `_`
   is eaten as emphasis"*.
2. **The status understated it.** Row 5 is SILENT in the same sense as row 4 — a construct
   printed on a card face. It is also the only row whose REMEDY is itself a silent re-keying, as
   argued above. That is a different and larger fact than the row recorded, and it is the reason
   this document exists. The status cell now says so and points here.

The evidence note for row 5 was updated in the same pass, to cite the executed measurements and
the five characterisation tests in `parser/ObsidianSyntax.test.scala` rather than reading alone.
