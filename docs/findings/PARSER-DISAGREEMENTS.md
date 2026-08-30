# Where this tool and Obsidian read the same markdown differently

> **Work on this document** — `bd list --all --spec docs/findings/PARSER-DISAGREEMENTS.md`
>
> Closed means built, and the closing reason says what shipped; open means outstanding. **This
> document records measurements and the reasoning around them, never progress** — a status kept in two places goes stale in one of
> them.

_Written 2026-08-27, from a design conversation between Marc and Claude. Claims marked VERIFIED were established by running something in this session or by
reading the file cited; everything else is reasoning, and says so. It follows this repository's
convention of opening with the answer rather than the three-layer TLDR / Summary / Full form,
because its siblings — `EVOLVABILITY.md`, `CLOZE-REDESIGN.md`, `EDIT-IN-OBSIDIAN.md` —
do._

---

## The one-paragraph answer

This tool parses with laika-core 1.3.2 and Obsidian parses with CommonMark, and they disagree
about real markdown that real people write. Six such disagreements are known. Three of them are
already loud — the tool refuses, or the parse fails outright. **Three are silent, and produce a
card whose content the author never wrote.** The silent three cannot be closed by a refusal,
because a refusal fires when a marked section FAILS TO BUILD and in these cases nothing fails:
the card builds, or the card was never asked for, or the card is keyed under a heading that does
not exist. What sees all three is a check over RAW SOURCE that asks a different question — *do
Obsidian and this tool agree about what this line is?* — which is what `extract/ListIndent.scala`
already is, for exactly one rule out of six. Whether that check ships as more rules in
`ListIndent`, as a `lint` command, or as a block inside `inspect` is open; whether it should
exist at all is the decision this document is for.

---

## What a parser disagreement is, and why it is its own category

A card's identity is `(frontmatter id, heading path)`, derived from the note and stored in Anki
as a `src::` tag. Nothing is written back into the markdown. Two consequences make markdown
parsing load-bearing in a way it would not otherwise be:

- **What the tool thinks is a heading decides a card's identity.** A heading the tool does not
  see is not a missing heading — it is a different key for everything beneath it, which is an
  orphan plus a history-less replacement.
- **What the tool thinks is content becomes the card's answer.** Something Obsidian hides, or
  renders as structure, can arrive as visible text on a card.

So a disagreement between the two parsers is not a rendering nicety. It is either a silent
re-keying or a silently wrong answer, and the author's only evidence is a card that looks
plausible.

**THE ASYMMETRY THAT SHAPES EVERYTHING BELOW.** A missed disagreement costs a card that is as
wrong as it was before anyone looked. A FALSE POSITIVE refuses a card that was fine, and an
author refused for no reason learns to distrust every refusal this tool makes. `ListIndent.scala`
already states this and is built to miss rather than to over-report; anything added here inherits
that posture.

---

## The family, and what the tool does with each member

| # | The disagreement | What Obsidian does | What this tool does | Status |
|---|---|---|---|---|
| 1 | a nested list item indented fewer than four columns | nests it | closes the list and opens a new one | **REFUSED**, by name, with line numbers — `SpecError.ListNestingUnreadable` |
| 2 | a `#` heading on the line directly after a list line | reads a heading | absorbs it into the list item | **SILENT** — see `EVOLVABILITY.md` §3.11 |
| 3 | an Obsidian callout, `> [!note]` | renders a callout | fails the WHOLE document: `unresolved link id reference: !note` | detected, but the message names Laika rather than the construct |
| 4 | a block reference, `^abc123` | hides it | prints it on the card face | **SILENT** |
| 5 | maths, `$…$` and `$$…$$` | renders it | prints the delimiters, and the TeX verbatim EXCEPT that `\\` collapses to `\` and paired `_` is eaten as emphasis | **SILENT** — and the only row whose REMEDY is itself a silent re-keying. See `MATHS-ON-A-CARD.md` |
| 6 | bracketed prose, `an index like [0]` | renders it | fails the whole document | refused loudly, and ruled an ACCEPTED trade |

**EVIDENCE, per row.**

1. VERIFIED. `extract/ListIndent.scala` documents the four-versus-two column divergence and
   states its numbers were run against laika-core 1.3.2 rather than predicted.
   `dummy-vault/Patterns/Shallow-Nesting.md` carries it end to end as a deliberate failure.
2. VERIFIED BY EXECUTION 2026-08-27, over pairs of vaults differing by exactly one blank line.
   Full account, including the three variants and the live instance in Marc's vault, at
   `EVOLVABILITY.md` §3.11.
3. VERIFIED BY EXECUTION by an earlier session; recorded as oas-yom. All 125
   block-id definitions in `References/Modern Mathematics.md` sit inside callouts, and the file
   is silent today only because it carries no `id:` in its frontmatter.
4. VERIFIED BY EXECUTION by an earlier session; recorded as oas-3lu.
   `The outermost layer is the ==epidermis==. ^abc123` renders as
   `<p>The outermost layer is the {{c1::epidermis}}. ^abc123</p>`.
5. VERIFIED BY READING, and since 2026-08-28 ALSO BY EXECUTION. `parser/ObsidianSyntax.bundle`
   registers six span parsers — embed, wikilink, task list, highlight, Obsidian comment, HTML
   comment — and `$` is a delimiter in none of them, nor in Laika's `Markdown.spanParsers`, nor
   in `GitHubFlavor`. The corruption half of the description was measured by driving the
   PRODUCTION parser over each construct, and is now pinned in `parser/ObsidianSyntax.test.scala`
   under the heading "maths: pinned, not supported" — five characterisation tests which are
   expected to go red the day maths is parsed, that redness being the re-keying. The table of
   what survives and what does not, and the argument that the remedy costs a card, are in
   `MATHS-ON-A-CARD.md`. Earlier corroboration, still the only live-vault datum: an `inspect`
   run over Marc's vault on 2026-08-27 emitted the card key
   `notation (given 2 sets, $a$ and $b$)`, so the dollars reached the key verbatim.
6. VERIFIED. `parser/ObsidianSyntax.test.scala`, "bare bracketed prose FAILS loudly under strict
   parsing — the accepted trade". This row is the CONTROL for the table: the family is not
   uniformly silent, and strictness already handles part of it.

---

## Why the silent three cannot be closed by a refusal

A refusal is attached to a `CardKey` and raised when a marked section fails to build. Row 2 alone
produces three shapes, and only the first is reachable that way — measured, not argued:

- **The swallowed heading carried the marker.** Its card is never created and its body lands on
  the card above. There IS a marked section here, but it is the section above, and it built
  successfully. Nothing failed.
- **The swallowed heading was unmarked, and a marked heading below it survived.** The card builds
  perfectly, under the wrong parent. Same card count, `failures: 0`, exit 0 — and the key is
  wrong, so the eventual correction re-keys it.
- **The swallowed heading was unmarked and took the marked heading with it.** `notes: 0`,
  `failures: 0`, `scan: complete`, exit 0. There is no marked section left to refuse.

Rows 4 and 5 are the same shape from the other direction: the construct becomes card CONTENT, so
there is no failure to hang a refusal on and no key to attach one to.

**This is the whole argument for a separate check.** A refusal answers *did this card fail?* The
question that sees the family is *did the two parsers read this file the same way?* — and that
one is answerable from raw source, before any card exists.

---

## The status column is not six facts. It is three mechanisms.

_Added 2026-08-29, from a later conversation between Marc and Claude, while `MATHS-ON-A-CARD.md`
was opening up row 5. DERIVED from the evidence already gathered above rather than separately
measured — but it PREDICTS every row of the table, which is the reason to trust it and the
reason it is worth writing down._

The six rows pair up. Each pair shares a mechanism, and the mechanism alone decides whether the
tool is loud or silent. How *important* the construct is does not enter into it, which is why
the status column reads as arbitrary until the pairs are visible.

| pair | rows | what the parser does | why the status follows |
|---|---|---|---|
| **recognised as the WRONG structure** | 1, 2 | both parsers see a construct and disagree about which one | the parse SUCCEEDS, so there is nothing to fail on. Row 1 is loud ONLY because `extract/ListIndent.scala` reads raw source; row 2 has no such check and is therefore silent |
| **recognised, then unresolvable** | 3, 6 | markdown reads a shortcut link reference and nothing defines it | strict mode reports it. Both fail the WHOLE document with the same Laika sentence, which is exactly why row 3 names Laika instead of naming a callout |
| **not recognised at all** | 4, 5 | no rule matches, so the characters remain text | text is always valid, so there is no error to report. It becomes card CONTENT |

Read this way the table proves the argument above, and proves it twice over.

**ROW 1 IS THE PROOF BY CONSTRUCTION.** It is the only loud member of its pair, and the only
difference between it and row 2 is that somebody wrote a raw-source check for it. The remedy
proposed below is therefore not speculative: one instance of it is already in the tree, already
shipping, and already the reason one row of this table reads differently from its twin.

**ROWS 4 AND 5 ARE THE PROOF BY IMPOSSIBILITY**, and this is the part worth stating carefully,
because the tempting version of it is wrong. It is tempting to say markdown has no syntax errors
and stop there. That is false here — strict parsing DOES fail, as rows 3 and 6 demonstrate. The
accurate statement is narrower and considerably worse:

> **THE FAILURE CHANNEL IS KEYED TO RESOLUTION, NOT TO RECOGNITION.** It fires when markdown
> recognises a construct and then cannot resolve it, which is what a link reference with no
> definition is. It CANNOT fire when markdown recognises nothing at all, because unrecognised
> text is not an error in markdown — it is a paragraph.

`$$x$$` and `^abc123` are not malformed. They are prose, and prose is always well-formed. There
is no input to a markdown parser that it can decline.

The consequence lands on machinery this codebase already has and is right to have. `Refusal`
carries `UnknownBlock(laikaClass)` and `UnknownSpan(laikaClass)` catch-alls, which make the
lowering TOTAL over Laika's tree — a real guarantee, and the reason an unhandled node cannot
reach a card in silence. But a catch-all can only fire on a node that EXISTS. Rows 4 and 5 never
produce one. **The net is strung below the gap rather than across it, and totality over the
syntax tree is not totality over the source.** No amount of care inside the front end converts
one into the other.

That is the principled form of what §"Why the silent three cannot be closed by a refusal"
establishes by measurement. The check has to read raw text and live outside the parser — not as
a workaround for a front end nobody has finished, but because the property wanted is not
expressible inside a front end for a language that cannot reject.

---

## What the tool does with a construct: four decisions, one function, and one accident

The family table has a column headed *What this tool does*. Spelled out across the whole
codebase rather than across these six rows, there are five deliberate answers — and one nobody
chose.

- **Pass through.** Ordinary prose, unchanged.
- **Unwrap.** Keep the payload, drop the Obsidian-only wrapper. A wikilink becomes its display
  text, because there is no vault on the far side to link into.
- **Translate.** Rewrite into the target's own vocabulary. A highlight becomes a cloze deletion;
  a markdown table becomes an HTML table.
- **Strip.** Remove deliberately. An Obsidian comment lowers to zero inlines.
- **Refuse.** Stop and name it in the author's words. `Refusal` has cases for embeds, task lists
  and images, and its `describe` is written so an author reads "a task list" rather than a class
  name.

And then, separately:

- **Fall through to prose.** Rows 4 and 5. Not a decision at all. The absence of one.

**THE FIRST FOUR ARE NOT FOUR MECHANISMS.** They are four possible return values of a single
function, `Lower.blocks`, and in that order they are identity, projection, rewrite and
annihilation. Nothing would be gained by building them separately, and nothing here should be
read as proposing it.

**REFUSAL IS THE ONE THAT DIFFERS IN KIND, AND IT IS ALREADY RIGHT.** It is the failure branch,
and `Validated[NonEmptyVector[Refusal], A]` accumulates rather than short-circuits, so an author
sees every refusal in a section instead of only the first. Choosing an accumulating applicative
over a monad is the substantive decision there, and it has already been made.

**WHAT HAS NO NAME IS THE DECISION ITSELF.** There is no way to look up a construct and read off
which of the six it receives. The answer is implicit in which branch of the lowering it reaches,
and for the sixth it is implicit in the ABSENCE of a parser. An enumeration would be reviewable,
and a reviewer could notice a gap in it.

**BE CLEAR ABOUT WHAT SUCH AN ENUMERATION WOULD NOT BUY, because the temptation is to overstate
it.** The compiler already forces an answer at every constructor of `Content`, since
`-Wconf:msg=exhaustive:e` makes a missing case a build error. It cannot force an answer per
OBSIDIAN construct, because that mapping is neither total nor injective. The gain would be
review, not proof. **Whether that is worth a type is Marc's**, and it is a smaller question than
it first appears.

**ONE THING CANNOT BE NAMED INTO EXISTENCE AT ALL** — recognition coverage, for the reason given
in the previous section. It has to be measured against source, which is what this document
proposes.

A note that generalises beyond row 5. A construct whose INTERIOR is a foreign grammar — maths is
the current instance, but any embedded language qualifies — must be captured without recursing
into it, or the host grammar mangles the payload before anything downstream can see it. Laika
draws exactly that distinction between a span parser built `.standalone` and one built
`.recursive`, and `obsidianCommentParser` already uses the former. `MATHS-ON-A-CARD.md` records
what the host grammar does to unprotected TeX, measured.

---

## What such a check would establish

Stated as what it must ANSWER rather than how, because the how is a design conversation that has
not happened. `extract/ListIndent.scala` already answers the first of these and already has the
shape the rest would need: it reads raw source, tracks fences, headings and open list items, and
reports findings with the FILE's own line numbers, keyed to the heading that owns them.

- for each list line, whether the next line is one the two parsers place differently
- for each `#` line, whether both parsers agree it is a heading at all
- for each construct the tool refuses or ignores wholesale — a callout, a block reference, a
  maths delimiter — whether it occurs in a position that will reach a card

**IT MUST REPORT, NEVER REPAIR.** This is a standing ruling and it transfers directly:
*"Under-indented nested lists are REFUSED, not repaired. The parser has already consumed the
indentation, so repair would be a guess."* The repair path is a FORMATTER, which is a different
tool with a different licence to change the author's file — oas-1tg already records
prettier in that role for the indentation case.

**AND IT MUST NAME THE CONSTRUCT, NOT THE PARSER.** Row 3 is the cautionary example: the tool
already detects callouts, and what it says is `markdown: unresolved link id reference: !note`.
That is Laika's sentence about a shortcut reference link, offered to somebody who typed a callout.

---

## Where it would run, and why the timing is the point

A refusal speaks at sync time; a check over source can speak at authoring time. That difference
is not convenience — it decides which of the three shapes above are reachable at all, and it
decides whether the author still remembers what they meant.

Candidates, not a recommendation:

- **more rules inside `ListIndent`**, reported through the existing `SpecError` path. Cheapest by
  a wide margin, and inherits the fixture, the report block and the line-number machinery. Closes
  only what a refusal can reach.
- **a `lint` subcommand.** Reads a vault, writes nothing, contacts no collection — the same
  standing as `inspect`. Reachable from a pre-commit hook or from `conform.nvim` on save, so it
  can speak before a sync exists.
- **a block inside `inspect`.** Already read-only, already walks the vault, already prints a
  failures section. No new command, and no way to run it at authoring time.
- **an Obsidian plugin.** Ruled out by `docs/reference/REQUIREMENTS.md`: this is a standalone tool, and
  plugin-abandonment risk is one of the stated reasons it is one.

---

## What is decided, and what is not

**Decided, and inherited rather than settled here:**

- Report, never repair. Repair belongs to a formatter.
- Miss rather than over-report. A false refusal costs more than a missed one.
- A refusal alone is structurally insufficient for this family — established above by
  measurement, not by argument.

**Not decided, and each is Marc's:**

1. **Whether this earns a subsystem at all.** One instance exists in the vault today. The
   counter-argument is that the family has gained a member every time anybody has looked, and
   that its silent members are the ones that cost review history.
2. **Where it runs** — the four candidates above.
3. **Whether rows 3, 4 and 5 belong in the same check as rows 1 and 2.** They are the same
   CLASS — the parsers disagree — but rows 1 and 2 are about structure the tool mis-reads, while
   4 and 5 are about constructs it does not read at all. One check or two is a real question.
4. **Whether the narrow refusal for row 2 ships first, or waits for the whole check.** Shipping
   it first closes the visible variant and leaves the expensive one open, which is a defensible
   trade and should be a stated one rather than an accident of sequencing.

---

## What was measured, and what was only reasoned

**MEASURED 2026-08-27:** ten headings in the vault have no blank line above them, and **exactly one
follows a list line**. That is one instance, not a rate — and it is the vault this tool was
developed against, which is shaped by what was being stretched at the time.

**REASONED, NOT MEASURED:** that Obsidian renders the swallowed line as a heading. It follows from
CommonMark's lazy-continuation rule and from the author having written it as a heading and expected
a card. Until somebody looks in reading view, it is an inference.

---

_What this document once listed as measurements to go and do is in the beads the query at the top
lists. Removals are in `git log --follow -p docs/findings/PARSER-DISAGREEMENTS.md`._
