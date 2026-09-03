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
about real markdown that real people write. Six such disagreements are known, and **none of them
is silent any more.** Three are loud because the tool refuses or the parse fails outright; two
were closed on 2026-08-29 by giving the parser the productions it lacked; and the last — row 2,
the heading a list swallows — was closed on 2026-09-03 by a check of its own.

A silent one cannot be closed by a refusal attached to a card, because such a refusal fires when
a marked section FAILS TO BUILD and in these cases nothing fails: the card builds, or the card
was never asked for, or the card is keyed under a heading that does not exist. What sees all
three is a check asking a different question — *do Obsidian and this tool agree about what this
line is?* — and it turned out to want TWO checks rather than one, because that question has two
halves living in different places. `extract/ListIndent.scala` reads RAW SOURCE, because
indentation is consumed by the parser and is gone by the time a tree exists.
`extract/UnreadHeadings.scala` reads the PARSE TREE, because whether this tool ended up TREATING
a heading as a heading is not a fact the source can be asked. Neither is the `lint` subcommand
weighed below; both run inside the extractor, where the answer can still stop a card being keyed.

> **WITHDRAWN 2026-09-03, and left standing above rather than deleted** because much of what
> follows rests on it. This paragraph used to say that what sees the family is *a check over RAW
> SOURCE*, on the strength of the only instance that then existed. That is right for row 1 and
> wrong for row 2 — the argument is under "what writing the second one taught", below. What
> survives the correction is the shared property: such a check reads something OTHER than the
> marked section that failed to build.

---

## What a parser disagreement is, and why it is its own category

A card's identity is the frontmatter `id` plus whichever node of the note the card hangs off — a
chain of headings, a block, a frontmatter property, or the note itself — derived from the note and
held on the Anki side, in a field. Nothing generated is written back into the markdown. Two consequences make markdown
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
| 2 | a `#` heading on the line directly after a list line | reads a heading | absorbs it into the list item | **CLOSED** 2026-09-03. The note is refused whole — `BuildFailure.KeyMisfiledInFile` |
| 3 | an Obsidian callout, `> [!note]` | renders a callout | fails the WHOLE document: `unresolved link id reference: !note` | detected, but the message names Laika rather than the construct |
| 4 | a block reference, `^abc123` | hides it | hides it — a production in the grammar since 2026-08-29, lowering to nothing | **CLOSED** |
| 5 | maths, `$…$` and `$$…$$` | renders it | captures it and re-emits `\(…\)` / `\[…\]`, which is what Anki reads — since 2026-08-29 | **CLOSED**. See `MATHS-ON-A-CARD.md` |
| 6 | bracketed prose, `an index like [0]` | renders it | fails the whole document | refused loudly, and ruled an ACCEPTED trade |

**EVIDENCE, per row.**

1. VERIFIED. `extract/ListIndent.scala` documents the four-versus-two column divergence and
   states its numbers were run against laika-core 1.3.2 rather than predicted.
   `dummy-vault/Patterns/Shallow-Nesting.md` carries it end to end as a deliberate failure.
2. VERIFIED BY EXECUTION 2026-08-27, over pairs of vaults differing by exactly one blank line.
   Full account, including the three variants and the live instance in Marc's vault, at
   `EVOLVABILITY.md` §3.11. CLOSED 2026-09-03 by `extract/UnreadHeadings.scala`, which reports
   every heading the section rewrite did not lift into a `Section` — so it closes all three
   variants, the MIS-KEYED one included, rather than only the two that lose a card.

   A FOURTH CONSEQUENCE WAS MEASURED WHILE CLOSING IT, and it is not in the three variants above:
   the list lines BELOW the swallowed heading rejoin the list ABOVE it, because one list spans the
   whole run. The card that absorbed the heading therefore also absorbs the items written under
   it and answers with a list the note does not contain — `# Latency`, written with one item
   above and one below, renders
   `<li><p>ten thousand requests</p><p>Latency</p></li><li>one millisecond</li>`.

   THREE FURTHER CAUSES OF AN UNREAD HEADING FELL OUT OF ASKING THE TREE, for nothing: the same
   absorption through a list item's LAZY CONTINUATION line, which `ListIndent` records as a known
   miss; the same absorption by an open BLOCKQUOTE, measured 2026-09-03 and not previously
   recorded anywhere; and a heading INDENTED inside a list item, which is a different animal — see
   the severity note below.

   ITS ONE MEASURED FALSE POSITIVE IS EXCLUDED BY NAME AND PINNED: laika reads `#tag` — no space
   after the hashes — as a heading and CommonMark does not, so a tag on its own line after a list
   line arrives looking identical to the defect. Reporting it would be worse than noise, because
   the remedy this check offers is a blank line above the heading, and following it there would
   make laika lift the tag into a real section — inserting a heading the author never wrote into
   the path of every card below it.

   **THE ROW IS TWO FACTS, AND THEY ARE NOT WORTH THE SAME.** A heading this tool cannot read
   costs the card that heading would have made. A heading that would have been TOP-LEVEL in the
   other reading also re-parents every heading below it, which is the expensive one, because those
   cards build perfectly and are merely filed wrong. The second holds only when the author wrote
   the `#` in the first column, where CommonMark ends the list or the quote above it; a heading
   deliberately indented inside a list item is placed identically by both parsers and moves
   nothing. So the two get different answers — the milder is reported and the note's cards are
   written as usual, the severe withholds every card in the note — and the distinction is carried
   in the type, `extract/UnreadHeadings.scala`'s `UnreadHeading`, rather than in a branch each
   consumer repeats.
3. VERIFIED BY EXECUTION by an earlier session; recorded as oas-yom. All 125
   block-id definitions in `References/Modern Mathematics.md` sit inside callouts, and the file
   is silent today only because it carries no `id:` in its frontmatter.
4. CLOSED 2026-08-29, when the block identifier became a production in the grammar and started
   lowering to nothing — it can no longer reach a card face. Before that, verified by execution:
   `The outermost layer is the ==epidermis==. ^abc123` rendered as
   `<p>The outermost layer is the {{c1::epidermis}}. ^abc123</p>`. Recorded as `oas-3lu`.
5. CLOSED 2026-08-29 by a `mathParser`. Before it, `parser/ObsidianSyntax.bundle` registered six
   span parsers — embed, wikilink, task list, highlight, Obsidian comment, HTML comment — and `$`
   was a delimiter in none of them, nor in Laika's `Markdown.spanParsers`, nor in `GitHubFlavor`;
   it now registers eight, `mathParser` and `blockIdParser` among them. The corruption was
   measured by driving the PRODUCTION parser over each construct, and the characterisation tests
   written to go red the day maths was parsed did exactly that — the sections in
   `parser/ObsidianSyntax.test.scala` now read "maths: recognised, captured raw". The table of
   what survived, and why the remedy cost a card, are in `MATHS-ON-A-CARD.md`. The re-key it
   predicted happened, to one heading in Marc's vault: `inspect` emitted
   `notation (given 2 sets, $a$ and $b$)` on 2026-08-27 and the same heading keys without the
   dollars today.
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
one is answerable before any card exists.

> _Amended 2026-09-03. The sentence above ended "answerable from raw source, before any card
> exists", and for row 2 the raw source is the wrong artefact to ask — see the section below.
> The heading of this section is also narrower than it reads: what row 2 could not be closed by
> is a refusal attached to a CARD KEY. `BuildFailure.KeyMisfiledInFile` is a refusal scoped to
> the NOTE, and that is what closed it._

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
| **recognised as the WRONG structure** | 1, 2 | both parsers see a construct and disagree about which one | the parse SUCCEEDS, so there is nothing to fail on. Each is loud only because a check was written for it: row 1 by `extract/ListIndent.scala` over raw source, row 2 by `extract/UnreadHeadings.scala` over the parse tree |
| **recognised, then unresolvable** | 3, 6 | markdown reads a shortcut link reference and nothing defines it | strict mode reports it. Both fail the WHOLE document with the same Laika sentence, which is exactly why row 3 names Laika instead of naming a callout |
| **not recognised at all** | 4, 5 | no rule matches, so the characters remain text | text is always valid, so there is no error to report. It becomes card CONTENT |

Read this way the table proves the argument above, and proves it twice over.

**ROW 1 WAS THE PROOF BY CONSTRUCTION**, and it has since been joined rather than left alone.
When this was written it was the only loud member of its pair, and the only difference between it
and row 2 was that somebody had written a check for it — which was the argument that the remedy
proposed below was not speculative. Row 2 now has one too, so the pair is loud on both sides and
the proof has become an example.

**WHAT WRITING THE SECOND ONE TAUGHT, and it is not what this document predicted.** The remedy is
described throughout as *a check over RAW SOURCE*, generalised from the single instance that
existed at the time. That is right for row 1 and wrong for row 2. Indentation has to be read from
source because the parser consumes it; but *did this tool treat the line as a heading?* is a
question about the tool's own reading, and the parse tree answers it outright while a source scan
can only re-derive it — needing fence tracking, a second definition of what a heading is, and a
rule per cause. Asking the tree instead caught three further causes nobody had enumerated.
**The shared property is that the check reads something OTHER than the marked section that failed
to build; which of the two artefacts it reads is decided per row.**

**AND ONE THING THE TREE STILL CANNOT ANSWER, which is why row 2's check reads a little of both.**
Whether the author wrote the heading at column zero — the fact that separates a heading a list
SWALLOWED from one the author INDENTED inside a list item — leaves no trace in the tree at all:
the two produce byte-identical shapes. So the tree says *this tool did not read your heading* and
the source says *you wrote it as a top-level heading*, and it takes both to know what the
disagreement costs.

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

_Row 2 shipped on 2026-09-03 as `extract/UnreadHeadings.scala`, which settles part of what
follows. The alternatives are left in place rather than deleted, so that what was chosen over
what stays visible. What it settles: it is a second CHECK and not a subsystem (item 1); it runs
inside the extractor rather than as a lint command (item 2); and it ships narrow rather than
waiting for the whole family (item 4) — though not on the terms item 4 anticipated, since the
expensive variant is the one it closes first rather than the one it leaves open. What it does not
settle is item 3, which is still open and now has evidence on both sides: rows 1 and 2 turned out
to need DIFFERENT artefacts to read, so "one check or two" was answered by the constructs rather
than chosen._

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

**STILL AN INFERENCE ON 2026-09-03**, after the row was closed, and worth saying plainly because a
shipped check reads as a settled question. Nobody has yet opened one of these files in Obsidian's
reading view. Everything the check knows about Obsidian's half of the disagreement is read off
CommonMark; everything it knows about this tool's half is measured.

**MEASURED 2026-09-03, while closing the row**, each by parsing the input with the production
parser and printing the tree:

- A heading at column zero directly below a QUOTED line is absorbed into the `QuotedBlock`,
  exactly as one below a list line is absorbed into the list item. Not previously recorded.
- A heading INDENTED inside a list item produces the same tree shape as a swallowed one — a
  heading nested in the list item — at two and at three columns of indent. This is what makes the
  tree alone unable to tell the two apart.
- `#flashcard/sequence` on its own line is parsed as a HEADING by laika-core 1.3.2. The test that
  established this was written expecting the opposite and failed.

**REASONED, NOT MEASURED, IN THE NEW WORK:** that column zero is the right discriminator for "would
Obsidian have made this a top-level heading". What CommonMark actually says is that a line ends the
enclosing container when it is indented less than that container's content column, so a heading
indented one column inside a two-column list item is top-level to Obsidian and is treated here as
the milder case. That is a deliberate under-report, in the direction this document rules the family
must fail in, and nobody has measured how often it occurs — the honest answer is that no instance
of it has ever been seen.

---

_What this document once listed as measurements to go and do is in the beads the query at the top
lists. Removals are in `git log --follow -p docs/findings/PARSER-DISAGREEMENTS.md`._
