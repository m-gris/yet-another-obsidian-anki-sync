# Anchoring a card below a heading

_Written 2026-08-27, from a design conversation between Marc and Claude. Nothing here is built.
Claims marked VERIFIED were established by running something; everything else is reasoning, and
says so. It follows this repository's convention of opening with the answer rather than the
three-layer form, because its siblings — `EVOLVABILITY.md`, `PIPELINE-DESIGN.md` — do._

---

## The one-paragraph answer

Cloze is meant to be the escape hatch — the tool you reach for when knowledge does not fit a
rigid shape — and it is currently the most rigid thing in the system: the marker goes on a
**heading**, and the whole section body becomes one card's text, so every gap in a five-paragraph
section shows all five paragraphs. Fixing that means letting a card hang off something **smaller
than a heading**, which turns out not to be a syntax problem but an **identity** problem: what
names a paragraph, such that the card survives you editing the sentence it is about? Obsidian's own
block references are eliminated — VERIFIED, they print on the card face. An invented author-typed
anchor was proposed and has an unanswered objection. Two candidates remain, both Marc's: the cloze
**label** is already an author-supplied stable identifier, and a **similarity fingerprint traced
across git commits** can migrate a binding whose key has moved. They are complementary rather than
rival, and neither is blocked on anything but a decision.

---

## Why this is an identity problem and not a syntax one

A card is anchored at a **node** of a note. `CardPath` already has three cases — a chain of
headings, a frontmatter property, the note itself — and each was cheap to add because each anchor
had an obvious **name**: the heading text, the property name, nothing at all. The codec reserves
room for a fourth (`model/CardKey.scala`, the leading-empty-token scheme), so adding `Block` costs
almost nothing structurally.

What it costs is a name. A paragraph does not have one. And a card whose anchor moves is a card
that detaches from its review history — the same failure `EVOLVABILITY.md` §4 calls the system's
cost centre, arriving one level further down.

So every candidate below is judged on one question: **does the anchor survive the author editing
the thing it anchors?**

---

## What has been eliminated, and on what evidence

### Obsidian block references (`^blockid`) — ELIMINATED

The obvious candidate, and Marc's vault is full of them (`[[Modern Mathematics#^Z4YC85FV]]`, with
`obsidian-copy-block-link` installed). Obsidian generates them, they survive edits to the block,
and they are not prose — everything an anchor should be.

**VERIFIED BY EXECUTION, and it kills them:** run through this project's own parser,
`The outermost layer is the ==epidermis==. ^abc123` renders as
`<p>The outermost layer is the {{c1::epidermis}}. ^abc123</p>`. **A block id prints on the card
face.** It is card *content*, not metadata. `^` is a delimiter nowhere in the stack — not in
Laika's `Markdown.spanParsers`, not in `GitHubFlavor`, not among the six parsers
`ObsidianSyntax.bundle` registers — and `Html.escape` covers `& < > " { }` and leaves it alone.

A second finding, also VERIFIED, would have blocked them anyway: **every one of the 125 block-id
definitions in `References/Modern Mathematics.md` sits inside an Obsidian callout, and callouts
fail this tool's strict parse** — `> [!note] Page 31` yields `unresolved link id reference: !note`,
because CommonMark reads `[!note]` as a shortcut reference link. It is silent today only because
that note has no frontmatter `id`; giving it one, which is exactly what somebody does when they
want its annotations to become cards, is what makes the failure appear.

_Both findings are recorded as open items 20 and 21 in `IN-FLIGHT.md`._

### A plain content hash — ELIMINATED, and instructively

Hashing a paragraph and using the digest as its name fails for a reason worth stating, because it
generalises: **a cryptographic hash is designed to do the opposite of what an anchor needs.**
Avalanche is the point — flip one bit of input, every output bit changes. So fixing a typo re-keys
the card. Git blob SHAs fail identically, and per-file besides.

### An invented author-typed anchor (`%%card cloze: some-name%%`) — OBJECTION UNANSWERED

Proposed by the pipeline brainstorm (`PIPELINE-DESIGN.md`) and genuinely clever in its carrier:
`%%…%%` is an Obsidian comment, which VERIFIED is already a parsed node
(`parser/ObsidianSyntax.scala:81`), already lowers to zero inlines
(`content/Lower.scala:428-433`), and is already hidden by Obsidian — so unlike `^blockid` it cannot
reach a card face.

Its adversarial review found the objection: **an author-typed name has no uniqueness mechanism.**
Duplicate a paragraph — the commonest operation on a literature note — and you have two identical
anchors, one key, and a whole-run refusal. The proposal rejected `^blockid` partly on *unverified*
per-file uniqueness while proposing something that fails the same test harder.

The objection is answerable — refuse per note rather than per run, naming both occurrences — but
nobody has answered it, and the option should not be adopted until somebody does.

---

## What the scope of such a card would be

Distinct from the anchor question, and settled more easily. What delineates the text a cloze card
shows?

**A sentence cannot be it.** Markdown has no concept of one and neither does Laika, so the tool
would be splitting on `.` against prose containing `e.g.`, decimals, `Fig. 3`, and — in this
vault — `$B^A$` and other maths. A silent-failure generator.

**A line cannot be it.** Inside a paragraph a line break is soft wrap, an artifact of how the file
happens to be wrapped. Reflowing a paragraph would silently re-cut every card in it.

**A block can.** Paragraph, list item, quoted block, table — real nodes in the parse tree, which
the tool already receives. It is also what Obsidian's own block references address and what
`metadataCache.sections` drills into.

Scoping to the block fixes the original complaint directly: today the body is the whole **section**,
so a section with three paragraphs and five highlights produces five cards *each showing all three
paragraphs*. That is the bundling.

---

## The two candidates that remain

Both are Marc's, from the conversation of 2026-08-26/27. They solve different halves and compose.

### A. The cloze label as the anchor

`==2|epidermis==` — the `2` is author-supplied, note-scoped, and **survives rewriting
`epidermis`**, rewording the sentence around it, and reflowing the paragraph. It is precisely the
author-typed anchor the `%%card%%` proposal wanted to invent, except it already exists in the
syntax and is already used.

It also sidesteps that proposal's objection: a duplicated paragraph carries a duplicated **label**,
which is note-scoped and which the tool can refuse by name.

**And it can dissolve the ordinal-drift defect rather than guard against it.** Today one section is
one Anki note with N cards, and a card's ordinal is its cloze number — so inserting a highlight
earlier renumbers the later ones and review history re-attaches to different content
(`EVOLVABILITY.md` §3.2, still open). If each label-group becomes **its own Anki note with one
card**, there are no ordinals to rotate. The defect stops being something to guard and becomes
unrepresentable.

**A generalisation worth keeping even if the rest is dropped:** do not hash the content — *project
it onto the part that is already stable*. In a cloze block that part is the labels. A key of
`(frontmatter id, heading path, the labels in this block)` is content-derived, needs no new syntax,
and moves only when the author deliberately renumbers. A block with **no** labels then has no
stable identity, which is the truth rather than a failure, and is the argument for labels being how
an author says *"I intend to keep this card."*

**The fork it forces.** If a paragraph holds three groups, do they become one Anki note with three
cards, or three notes with one card each?

| | one note per block | one note per label-group |
| --- | --- | --- |
| ordinal drift | still possible for **unlabelled** groups | **unrepresentable** |
| anchor needed for the block | **yes — unsolved** | **no** — the label is the anchor |
| Anki sibling burying | kept | **lost** |
| paragraph text | stored once | duplicated per note |

The second makes the anchor problem disappear rather than solving it. **Its only real cost is
sibling burying** — Anki's ability to keep two gaps from one paragraph off the same day — and that
is the open question for Marc: is seeing them together fine?

### B. A similarity fingerprint, traced across git commits

Marc's, and it corrects a claim made against it in conversation. The objection was that a
similarity fingerprint cannot be an identifier because similarity needs a threshold, and a
threshold is a number nobody can justify — the same grounds on which `EVOLVABILITY.md` rejected a
proportional orphan guard.

**That objection holds for a single comparison and fails for a traced one.** Comparing two
arbitrary states six months apart is hard. Comparing **consecutive commits** is easy, and chaining
small deltas crosses the six months without ever making a large comparison. This is exactly how
`git log --follow` tracks a file across renames, and it works.

It also answers the threshold objection rather than dodging it: git ships a similarity threshold
(`-M`, 50% by default) that has been exercised on every repository in the world for twenty years.
Inheriting a battle-tested default is categorically different from inventing a number.

**Keep the two mechanisms separate**, because they have different properties:

- **The fingerprint is the key** — a pure function of current content, derivable from the vault
  alone with no memory. That preserves the property the design rests on: a sidecar is a cache,
  never an oracle (`CARD-MODEL.md`).
- **Git is the rename detector** — consulted only to *migrate a binding* whose key has moved:
  *"Anki holds key A, the vault now has key B, and here is the commit where A became B."*

Kept separate, identity never becomes a function of history — which would be the expensive change.
History is evidence for a **repair**, and the design already has a shape for that: `relink`, the
manual version, reattaching an orphan to a new key. Git-as-evidence is the same operation with the
pairing **supplied rather than guessed**, which satisfies the standing ruling that anything fuzzy
may rank but never apply. Git is not guessing; it is reading a record of the edit.

**Degradation is graceful, which is rare and worth having.** No commits means no trace, which means
today's behaviour — orphan plus create. The tool is strictly better when git is used and no worse
when it is not. As Marc put it: one should not fight indiscipline.

---

## The precondition nobody has met

**The vault is not under git.** VERIFIED 2026-08-27: `git rev-parse` fails in
`/Users/marc/📖-obsidian-anki-srs-📖/`.

That blocks candidate B outright, and it blocks something already on the books: **`EVOLVABILITY.md`
lists M1 — the git replay that classifies every key death and would settle whether heading renames
actually dominate the cost of change — as gating the identity decision, and describes it as "an
afternoon, no Anki needed". It cannot run.** It walks the vault's git history and there is none.
Running it against the fixture vault in this repository would measure how the fixtures were
authored, not how Marc edits notes, and it would be easy to mistake the result for the real one.

One `git init` and a habit of committing unblocks both.

---

## What is decided, and what is not

**Decided:**

- `^blockid` is out. It reaches the card face.
- A plain content hash is out. Avalanche is the wrong property.
- The scope of a sub-heading card is the **block**. Sentences and lines are not things the parser
  can see.
- Fuzzy matching belongs in **repair**, never in a key — the standing ruling holds, and candidate B
  respects it by supplying evidence rather than a guess.

**Not decided, and each is Marc's:**

1. **Sibling burying** — is it worth keeping? It is the only thing "one note per label-group"
   costs, and the thing that makes the anchor problem disappear.
2. **Unlabelled highlights** — refused, keyed by their text with the honest consequence that
   editing the text retires the card, or something else? _Note that the current documentation
   already claims the second and it demonstrably does not happen: the planner has no cloze
   awareness at all, so no orphan is ever produced for a retired group._
3. **Whether the vault goes under git**, which gates candidate B and M1 alike.
4. **The `%%card%%` uniqueness objection** — answerable by refusing per note rather than per run,
   but unanswered.
