# yet-another-obsidian-anki-sync

A command-line tool that turns marked headings — and highlighted phrases, frontmatter
relations, and whole notes — in an [Obsidian](https://obsidian.md) vault into
[Anki](https://apps.ankiweb.net) notes, and keeps them in step as the vault changes.

It is not an Obsidian plugin. It reads a folder of markdown files and talks to a running Anki
over [AnkiConnect](https://foosoft.net/projects/anki-connect/).

## What it does not write

**The tool writes nothing into your markdown.** No identifiers, no scheduling comments, no note
ids in frontmatter. A card's identity is *derived* from what you already wrote — the note's
frontmatter `id` plus where in the note the card came from: a chain of headings, a frontmatter
property, one block named by its `^blockid`, or the note itself. The binding to an Anki note is
stored in Anki, in a field called `Identity`.

**Why it was built that way.** The usual approach is to write an identifier, and sometimes review
state, into the note itself. That is noise in the source, and it is brittle: bookkeeping and prose
end up sharing a file, so an edit to one can break the other. Deriving the identity avoids both.
It also has a consequence worth having on its own — the tool only ever reads your vault, so
pointing it at one cannot damage what you wrote.

**This is not a rule the design may not revisit.** Nothing in the architecture forbids writing to
the vault; the tool has had no reason to. One case is open and genuinely undecided: a repair tool
that reconnects a card to a note whose heading was reworded may need to write the vault side of
that repair — or may do the whole thing in Anki. Nobody has worked it out.

> **The identity used to be a tag**, `src::…`, and moved into a field on 2026-08-29, so that a
> machine's ledger is not sitting in your own tag tree. Notes on a note type this tool does not
> own cannot hold a field, so their identity is still read from the tag.

**The consequence you will feel today:** rename a marked heading and its card loses its history.
The tool has no rename detection. The old card is not deleted — it is flagged and suspended, so
you can reconcile it by hand — but it will not follow the heading. Move prose around freely; think
before you re-word a heading you have been reviewing for six months.

## One vault per Anki profile, for now

**Nothing this tool writes into Anki records which vault a note came from.** So if you sync a
second vault into the same Anki profile, the first vault's cards are absent from the second's
markdown — and absent from the markdown is what *orphaned* means. Every one of them gets flagged
and suspended.

The reason it cannot simply be avoided: the reconciler has to enumerate every note it owns in one
query, because an orphan is by definition a key the markdown does *not* have, so it can never be
found by looking things up from the markdown. That enumeration has nothing to filter on, because
no vault is recorded anywhere.

**Until that is fixed, give each vault its own Anki profile.** It is not a rule the design
believes in — it is a defect with a known fix, written up as item 42 in `IN-FLIGHT.md`: a `Vault`
field written beside the identity, holding the vault directory's name, so the enumeration has
something to filter on. The same entry covers making decks read `Obsidian::<vault>::…`, which is
what makes two vaults legible in one collection rather than merely possible.

## What you need

- **Anki**, running, with the **AnkiConnect** add-on installed (it listens on
  `http://localhost:8765`). The tool never launches Anki and never switches your profile for
  you — it checks that the profile you named is the one already open, and refuses otherwise.
- **A JVM 17** on your `PATH`, to run it.
- **[scala-cli](https://scala-cli.virtuslab.org)** and **[just](https://just.systems)**, to
  build it.

## Building it

From the repository root:

```bash
just install
```

That packages the tool into a single executable at `target/obsidian-anki-sync` and symlinks it
into `~/.local/bin`, which is on your `PATH`. The link points *at* the build output, so
`just build` afterwards updates the installed command in place. `just uninstall` removes the
link and leaves the executable. `just demo` runs `inspect` over the fixture vault, as a check
that a build works end to end.

The executable is a self-contained JAR with a shell preamble — it carries every dependency and
the five note-type definitions, but still needs a JVM to run. It is **not** a GraalVM native
image: measured on one machine, the assembly starts in 0.57s against 1.01s for `scala-cli run`,
and a native image would reach roughly a tenth of that — but this program parses YAML through
snakeyaml, which resolves classes by reflection, the one thing native-image cannot see without
being told. The failure mode is a binary that builds and then breaks on a path only a live run
reaches. Half a second does not pay for that.

You can also skip the build entirely and run from source, which is what the test suite does:
`scala-cli run . -- inspect --vault-path ~/my-vault` from this directory.

## Getting started

**1. Create the note types.** Once per Anki profile, before the first sync.

```bash
obsidian-anki-sync install-note-types --profile 'My Profile'
```

This creates five note types of the tool's own — it never writes to Anki's stock `Basic`,
`Basic (and reversed card)` or `Cloze`, so nothing you already have is touched. It creates only
what is absent and overwrites nothing. If a note type is already there and differs from this
repository's version, it says so and stops; `--repair` is how you ask for it to be brought into
line, and it is opt-in because a template you improved inside Anki is yours.

**2. See what your vault would produce.** This touches no collection at all, so it needs no
profile and can be run at any time.

```bash
obsidian-anki-sync inspect --vault-path ~/my-vault
```

**3. See what would change in Anki, without changing it.**

```bash
obsidian-anki-sync sync --vault-path ~/my-vault --profile 'My Profile' --dry-run
```

**4. Do it.** Drop `--dry-run`.

Omit `--vault-path` and the tool lists the vaults Obsidian has opened and asks. It never picks
one for you, not even the vault Obsidian currently has open.

## Marking a heading

A file produces cards only if its frontmatter carries an `id`. Within such a file, three things
make a card and nothing else does: **a heading carrying a marker**, **a frontmatter relation** you
have declared, and **a block holding `==<<highlights>>==`**. Everything else is ordinary prose.

Markers are the main route, and the rest of this section is about them; the other two have their
own sections below.

```markdown
---
id: replication
---

# Replication

## Read-your-writes consistency #flashcard/2way

A guarantee that a client always sees its own prior writes, even when reads are
served by lagging replicas.
```

The `id` is yours to choose and must not change — it is half of every card's identity in that
file. The marker is stripped before the heading is shown on a card.

### The markers

| Marker | Card shape | Cards per note |
|---|---|---|
| `#flashcard/1way` | heading → body | 1 |
| `#flashcard/2way` | heading ⇄ body | 2 |
| `#flashcard/cdd/1way` | concept–descriptor–description | 1 |
| `#flashcard/cdd/2way` | the same, plus "which thing has this?" | 2 |
| `#flashcard/cdd/3way` | the same, plus "which aspect is this?" | 3 |
| `#flashcard/cloze` | `==<<highlights>>==` blanked out | 1 per group |
| `#flashcard/sequence` | a list revealed one item at a time | 1 |
| `#flashcard/sequence/headers` | this heading's subheadings, one at a time | 1 |
| `#flashcard/sequence/headers/recursive` | the whole subtree beneath it | 1 |
| `#flashcard/table` | see *Tables* below | many |

**`cdd` is concept–descriptor–description** — the three-field shape. `1way`, `2way`, `3way`
count **retrieval directions**, and the ceiling is a property of the shape: a front/back card
has two fields and so at most two directions; a `cdd` card has three fields and so three.

| | shown | you recall | reads as |
|---|---|---|---|
| `cdd/1way` | concept + descriptor | the **description** | *"Scaphoid, blood supply → ?"* |
| `cdd/2way` | + descriptor + description | the **concept** | *"which bone has this blood supply?"* |
| `cdd/3way` | + concept + description | the **descriptor** | *"Scaphoid, retrograde → which aspect?"* |

Each level adds a card to the one before. The third direction is opt-in because "which aspect
is this?" is often a guessing game.

Front/back needs no shape name — `#flashcard/1way` and `#flashcard/2way` are what you get when
you name no shape at all.

> **Older spellings.** `#flashcard/3way` and `#flashcard/3way/all` still work and mean
> `cdd/2way` and `cdd/3way`. They were renamed because `3way` named a *shape* with a *direction*
> word and then produced two cards, not three. Rewriting them changes nothing — a marker is
> stripped from its heading before that heading becomes part of a card's identity, so the key,
> the note type and every field stay the same and the next sync reports nothing.

**Notes and cards are not the same thing, and the tool counts notes.** One marked heading
becomes one Anki *note*; the note's type decides how many *cards* Anki generates from it. So
`inspect` reporting `notes: 55` is consistent with Anki showing you rather more than 55 cards —
the test collection holds 43 notes and 82 cards. The vault cannot be asked for the card number
without counting cloze groups and reading each note type's templates, so the tool reports the
number it actually knows.

### One-way and two-way

The heading is the question and the body is the answer. `2way` also asks it backwards.

```markdown
## Why does synchronous replication trade availability for durability? #flashcard/1way

The leader cannot acknowledge a write until at least one follower has confirmed it, so a
slow or unreachable follower stalls every writer.
```

### Concept–descriptor–description (`cdd`)

For a fact that is *an aspect of a thing*. The **concept** is the nearest ancestor heading, the
**descriptor** is the marked heading, and the **description** is the body. One file can
therefore feed several concepts, and two facets with the same name stay distinct because the
key is the heading *path*, not the heading text.

```markdown
## CAP Theorem

### Definition #flashcard/cdd/2way

When a network partition splits a distributed system, the system must choose between
answering with possibly stale data and refusing to answer at all.

### Failure mode #flashcard/cdd/2way

Treating the C-versus-A choice as a permanent architectural setting rather than a
per-partition decision.

## Quorum

### Definition #flashcard/cdd/2way

...
```

Those four headings give four distinct cards, `CAP Theorem / Definition` through
`Quorum / Failure mode`, rather than two that overwrite each other.

**When the marked heading has no ancestor, the concept is the file name.** This is what makes a
one-heading note work:

```markdown
<!-- System Design Pattern.md -->
# 3 Components #flashcard/cdd/1way

- A Problem
- A Solution
- A Cost
```

The card asks *"System Design Pattern → 3 Components?"*, with the file name on the front as the
concept. Marked `#flashcard/1way` instead, that note produces a front/back card whose entire
question is "3 Components" — three components **of what?** — because front/back has no concept
field and nothing else on the card names the subject. Reach for `cdd/1way` whenever the note's
title is the thing and the heading is an aspect of it.

### Cloze

**The `<<…>>` is what makes it a card.** A bare `==highlight==` is an ordinary Obsidian
highlight — it renders as one, and it makes nothing. That costs four characters and buys the one
thing reserving `==` outright could not: you can see, by looking at a note, which of its
highlights are cards and which are emphasis.

> An Obsidian plugin can hide the brackets and give clozes their own colour. Without one you
> still get a real highlight, just with the brackets showing — which is why the syntax is
> `==<<x>>==` and not a bare `<<x>>`, since that would render as literal text and no highlight
> at all.

`==<<highlighted>>==` text is blanked out. Each one is its own deletion, keyed by its text —
so rewording the highlight starts a new card.

Prefix a digit to key the deletion by a **group** instead. Then the text may change freely and
the card keeps its history, and repeats of the same group are blanked together:

```markdown
## Bones of the forearm #flashcard/cloze

Two bones run between elbow and wrist. On the thumb side is the ==<<1|radius>>==; on the
little-finger side, the ==<<2|ulna>>==. In supination the ==<<1|radius>>== lies parallel to the
==<<2|ulna>>==.
```

Two *unlabelled* highlights with identical text are refused, because nothing but position could
tell them apart. Label them.

#### A cloze without a heading

You do not need a marked heading at all. **Highlight a phrase anywhere in a note and give its
block a `^blockid`**, and that block becomes a card:

```markdown
The ==<<radius>>== and the ==<<ulna>>== are the forearm bones. ^forearm
```

One Anki note, two cards, and the card shows **that block alone** — not the whole section around
it. That last part is the point: under a marked heading, a card's text is the entire section, so
a section with three paragraphs shows all three whichever phrase was highlighted.

Because the two deletions belong to one note, they are **siblings**, and Anki can deliberately
keep them off the same day rather than asking you the same sentence twice in one sitting.

**The `^forearm` is Obsidian's own block identifier**, and it is required here. In Obsidian you
get one by copying a block link — the identifier is written into the note for you. It is what
lets the card survive editing: reword the sentence, fix a typo, move the paragraph to another
heading, and the card keeps its review history, because the identifier did not move.

A block with deletions and **no** identifier is refused by name rather than made into a card that
would silently lose its history on your next edit.

> **Any block works** — a paragraph, a list item, a quoted line. And the note needs no headings at
> all; a file of plain prose with highlights in it produces cards.

**Labels work the same way**, and they are scoped to their own block — so `1` in one paragraph and
`1` in the next are different cards, and you never have to remember which numbers you have already
spent.

### Sequence

For when **the order is the knowledge**. One card, whose items reveal one at a time, on one
schedule.

```markdown
## Path of blood through the right heart #flashcard/sequence

From the body back towards the lungs:

- superior vena cava
- right atrium
- tricuspid valve
- right ventricle
```

The marker is required and cannot be inferred: the tool can see that a list is present, but
never that its order is what you are learning.

**Write nothing after the list.** Everything in the body that is not a list item is printed on
the question side, so a sentence following the list gives the answer away before the first
reveal.

### Structure

For when **the shape of a document is the knowledge**. The subheadings become the items, so you
are tested on the outline itself rather than on anything written under it.

```markdown
## Request lifecycle #flashcard/sequence/headers

### Parse
### Route
### Respond
```

One card, titled *Request lifecycle*, revealing `Parse`, `Route` and `Respond` one at a time.

**Prose under the marked heading does not reach the card.** The marker asks for structure, and
prose is not structure — so write as much as you like there. It belongs to the note, and to any
card *its own* heading makes, but not to this one. That is the opposite of `#flashcard/sequence`
above, where the body is the card's material because you wrote that list in order to be a card.

Add **`/recursive`** and the whole subtree comes with it, nested:

```markdown
## Request lifecycle #flashcard/sequence/headers/recursive

### Parse
#### Tokenise
#### Build tree
### Route
```

Two orders are available, and they show the *same* nested list — only the order the reveal key
walks it in differs:

| token | reveals |
|---|---|
| `…/recursive` or `…/recursive/bfs` | a level at a time — `Parse`, `Route`, then `Tokenise`, `Build tree` |
| `…/recursive/dfs` | each heading then its own children — `Parse`, `Tokenise`, `Build tree`, `Route` |

A level at a time is the default because it lets you learn the top level *as a level* before
dropping into any of it. An order written on the non-recursive marker is refused: a flat list has
no levels, so the two would name the same thing.

### A marker on the note itself

A marker written in the frontmatter `tags:` instead of on a heading applies to **the whole note**,
and the file name becomes the card's front.

```yaml
---
id: 2ac356b7-c1b2-4e2a-9c6f-a7b89c590f35
tags:
  - flashcard/sequence/headers/recursive
---
```

For the structure markers this is often the most natural form — *learn the outline of this note* —
and its items are the note's top-level headings.

**For every other marker the note must have no headings**, and a marker in frontmatter on a note
that has them is reported as a mistake. That is not pedantry: typing a marker into the body is
exactly what makes Obsidian's editor lift it up into `tags:`, leaving a note that looks marked and
produces nothing. The structure markers are the exception, because headings are what they are made
of rather than where they went missing from.

### Tables

A table is a compact way to write many concept–descriptor–description triples. The first column
names the things; every other column is an aspect of them.

```markdown
## Cranial bones and their sutures #flashcard/table

| Bone     | Anterior border | Posterior border |
| -------- | --------------- | ---------------- |
| Frontal  | Orbital rim     | Coronal suture   |
| Parietal | Coronal suture  | Lambdoid suture  |
```

Each row yields a **cell card** per column — the table drawn with that one cell blanked — and a
**row card** asking for the whole row at once. The row card exists because a value divorced from
its siblings is trivia and the contrast is the point; it is only produced for rows with two or
more usable columns, since with one it would merely duplicate the cell card.

Two independent axes refine that, and they compose:

| Axis | Tokens | Means |
|---|---|---|
| direction | `/1way` `/2way` `/3way` | how many ways one cell is asked |
| scope | `/cells` `/rows` | whether the cards are about cells, whole rows, or both |

So `#flashcard/table/3way/cells` is three directions, cell cards only. Bare `#flashcard/table`
is `2way` over both scopes. A direction combined with `/rows` is refused, because a direction
has nothing to apply to when there are no cell cards.

## Your own tags, in Anki

Tags written in a note's frontmatter are carried onto the Anki notes it produces, so you can
study by them:

```yaml
---
id: 7c5ab5c8-8780-4cac-83fc-f31833ccca85
tags:
  - backend/scala
  - maths/topology
---
```

becomes `obsidian::backend::scala` and `obsidian::maths::topology` in Anki. Obsidian nests with
`/` and Anki with `::`, so your tag tree keeps its shape in Anki's sidebar. A filtered deck then
studies a subject with `tag:obsidian::backend::*`.

**The vault decides.** Add a tag and it appears; remove one and it goes. Anki follows.

**Why they are namespaced rather than carried as-is.** Anki writes tags of its own — `leech` when
a card lapses too often, `marked` when you mark one in the reviewer — onto the very notes this
tool generates. If your `scala` were carried verbatim, this tool could not tell its own tags from
Anki's or from yours, and "remove what the vault no longer names" would eventually delete a record
of which cards are giving you trouble. Under a prefix it owns, it can only ever touch what it
wrote. Everything else on the note is left exactly alone.

**Two things are not carried.** A `#flashcard/…` marker is an instruction to this tool rather than
a subject, so it stays out of your Anki tags. And a tag Anki cannot hold is refused by name rather
than repaired — a space would make Anki read one tag as two, and `::` is its own nesting
separator, so write `/` instead.

## Relations in the frontmatter

A marker on a heading is not the only way to make a card. A **relation** written as a frontmatter
property makes one too — if the note says so:

```markdown
---
id: 22e55b6a-9efe-4722-bd2e-0bfa84401d99
special-case-of: "[[HomSet]]"
---

# Definition #flashcard/cdd/2way

The set of all functions from a domain to a codomain.

# Properties-to-Flashcards

- special-case-of: cdd/1way
```

`Function Space.md` now yields **two** cards: the definition, from the marked heading, and

> **Function Space** — *special-case-of* — ? → **HomSet**

### Why this is a concept–descriptor card

A relation is a triple — this note, the relation, the thing on the far end. So is a
concept–descriptor card: a thing, an aspect of it, the value of that aspect. They are the same
shape, which is why a relation needs no new note type and no new marker. It is a `cdd` card whose
descriptor happens to be a property name.

That also means `cdd/3way` works on a relation, and asks the card you cannot get any other way:
*Function Space — ? — HomSet*. Not "what is it related to", but **which relation holds**.

### Declaring, per note

**No property makes a card unless its own note says so.** A relation earns its place in frontmatter
for querying and for the graph — that is most of its value — and whether you want to be *drilled*
on it is a separate decision. So the declaration lives in the note that carries the property, under
a `# Properties-to-Flashcards` heading:

```markdown
# Properties-to-Flashcards

Relations I want drilled on this note.

- special-case-of: cdd/1way
- dual-of: cdd/3way
```

It declares for **that note only**. Another note may carry `special-case-of` and declare nothing,
and gets no card. Two notes may declare the same property differently. Prose around the rules is
ignored, so explain your vocabulary beside it.

The right-hand side is **the same token a heading marker uses**, read by the same parser — `cdd/1way`
here means what `#flashcard/cdd/1way` means on a heading. `flashcard/cdd/1way` and
`#flashcard/cdd/1way` are accepted too, but the bare form is recommended: a literal `#flashcard/…`
typed into a note's body is what Obsidian's editor lifts into the frontmatter `tags` property.

Anything that isn't a `cdd` shape is refused by name. `cloze` is a real marker and still wrong for a
relation; so is a bare `1way`, which on a heading is a **two-field** card and has no third field for
the relation to go in.

### Before you write `cdd/2way` or `cdd/3way`

A reversible card also asks the question backwards: *what is a special case of HomSet?* That is only
a sound question when **one** note answers it. If three notes are each a special case of HomSet,
three cards ask the identical question holding three different right answers, and whichever comes up
you are wrong twice out of three times.

The tool checks rather than trusting you or forbidding it, because it holds the whole vault at once
and can simply look. If the collision is there it refuses those cards and names every note involved.
Note that adding a third answer months later breaks two cards that were fine until then — the run
that does it will say so.

`cdd/1way` is never affected: pointing many notes at the same thing is the normal case.

### What it does with the value

- `[[HomSet]]` shows as **HomSet** — a card face is read, not clicked, and Anki cannot follow a
  wikilink whatever it looks like.
- `[[HomSet|the hom-set]]` shows as **the hom-set** — you already said how you wanted it to read.
- Several values make **one** card, answered `A, B`. It is one question whose honest answer is both;
  and it keeps the card's identity stable when you correct a typo in one of them.
- Anything that is not a link is left exactly as written, so `status: draft` is as good a relation
  as a link.

### Two things worth knowing

**Renaming the file is free.** A relation card is identified by the note's `id` and the property
name, so the file name is only something the card *displays*. Rename the note, reword every heading
in it — the card keeps its review history. Heading cards do not have this property: rewording a
marked heading retires that card and makes a new one.

**A relation survives a body the parser refuses.** Parsing is strict, and an array index written as
`[0]` in a sentence is enough to fail it. Such a note loses its heading cards and keeps its
relations, because relations live in the frontmatter — and the declarations block is read as lines,
so it survives too.

## Decks

By default a card's deck mirrors its **folder path** under a root deck named `Obsidian`. The
file is not a deck level, and neither are its headings.

`--deck-from` changes that. It names which parts of a card's location become deck levels:

```bash
--deck-from folders            # the default
--deck-from folders,headings
--deck-from folders,file,headings
--deck-from none               # one flat deck
```

`System-Design/Replication.md`, heading `## Read-your-writes consistency`, gives:

| `--deck-from` | deck |
|---|---|
| `folders` | `Obsidian::System-Design` |
| `folders,headings` | `Obsidian::System-Design::Replication::Read-your-writes consistency` |
| `none` | `Obsidian` |

In that example `Replication` comes from the file's `# Replication` heading, not from its name.
If your files open with an H1 restating their own name — most do — then selecting both `file`
and `headings` repeats it. That repeat is shown rather than quietly removed: a rule that dropped
it could not tell your H1 convention apart from a heading that genuinely repeats its parent.

`--deck-root` changes the root, which exists so that everything this tool creates lives in one
subtree you can delete and rebuild without touching decks you made by hand.

**Changing the deck shape moves cards; it never harms them.** A deck move keeps a card's entire
scheduling state. The run reports it as `move to another deck` rather than as an update, so you
can tell a re-filing from a re-writing at a glance.

Decks carry **filing only**, never learning order. Study scope comes from filtered decks over
tags, and introduction order from new-card position. Conflating the three is a trap.

## What it refuses to do

The tool would rather stop and tell you than produce a card that looks right and is not. Every
refusal names the file and the line.

### It refuses the whole run, writing nothing at all

- **two things in your vault derive the same identity** — two sources, one key
- **two notes in Anki carry the same identity** — which the vault cannot fix, so nothing is
  written until you delete one

### It refuses one card, and syncs the rest

**Because of what the note says:**

- **a marked heading has no prose of its own**, because a subheading follows it immediately —
  *except* for `#flashcard/sequence/headers`, where that is the ordinary shape and the
  subheadings themselves are the card
- **a `#flashcard/cloze` heading has no `==<<highlight>>==`** in its body. A bare `==highlight==`
  is emphasis and makes no card, which is the usual cause
- **a block holds cloze deletions and carries no `^blockid`** — it would have no identity that
  survives an edit. Add one (in Obsidian, copy the block link), or put the deletions under a
  `#flashcard/cloze` heading
- **two unlabelled cloze deletions share their text** — nothing could tell their cards apart.
  Label them: `==<<1|quorum>>==`
- **a `#flashcard/sequence` heading has no list**, or its list items all render to nothing
- **a `#flashcard/sequence/headers` heading has no subheadings**
- **a `#flashcard/table` heading has no table**, or the table has no descriptor columns, or no
  column header that could name one
- **a `#flashcard/table/rows` heading has no rows to make row cards from**
- **a whole-note marker asks for a `cdd` shape** — that needs three parts and a note has two, its
  name and its body

**Because of what the note contains:**

- **an image, an Obsidian embed, or a task list** anywhere in the body. Each would reach the card
  as something the tool cannot render honestly, so it refuses rather than ship a card with a
  broken image or a silently dropped line. Embeds are the one worth knowing about — see *Not
  built yet*
- **markdown this tool's parser does not recognise.** Parsing is strict on purpose: an unknown
  construct must fail loudly rather than lose its text quietly

**Because of how it is written:**

- **a nested list is indented fewer than four spaces** — this tool's parser reads such a line as a
  new list rather than a sub-item, so the card would say something your note does not. Indent to
  four spaces or a tab. Obsidian's own Tab key writes one; two-space indentation usually arrives
  from another editor or a formatter

**Because of where it would go:**

- **a folder or heading contains `::`**, which is Anki's deck separator — and only when that part
  is actually being used as a deck level
- **the open Anki profile is not the one you named**

## Changing your mind about a card's shape

Change a marker and you have changed the card's **shape** — how many fields it has and how many
ways it is asked. Anki calls that a *note type*, and a note can only be on one at a time, so the
note has to move.

**`sync` moves it for you, and every card keeps its review history.** Interval, ease, due date and
the whole review log survive the move; that was measured rather than assumed. The run says which
notes it moved and between which note types:

```
MOVED: 1 note put on the note type the vault asks for.

  Obsidian Basic (and reversed card)  ->  Obsidian Concept-Descriptor

  'system design interview / framework'
```

A dry run tells you the same thing before anything is written, under `WOULD MOVE`. It is worth
looking, because a move rewrites **every field and every tag** of the note before writing them
back — the largest single write this tool makes.

If you would rather sync your content today and think about shapes another time, pass
**`--no-migrate-note-types`** and the notes are left exactly where they are. The run then names
them and says what it did not do.

### The change that needs no move at all

`cdd/1way`, `cdd/2way` and `cdd/3way` are all the **same** note type with a field flipped, so
changing between them is an ordinary update.

**Widening generates the new card by itself.** Go from `cdd/2way` to `cdd/3way` and Anki creates
the third card, with fresh scheduling, while the two you already had keep everything. Nothing is
migrated and nothing is at risk.

### Narrowing, which is the one to know about

Going the other way — `cdd/3way` back to `cdd/2way`, or `2way` to `1way` — is where care is
needed, and the two cases behave differently.

**Within the `cdd` family**, narrowing does *not* delete the card it retires. It stays, holding its
review history, with nothing on its front, so it never comes up again. Anki's **Tools → Empty
Cards** will offer to delete exactly those, so read that dialogue rather than confirming it.
_Reporting and flagging such a card is decided and not yet built; see `IN-FLIGHT.md` item 23._

**Across note types** — anything that would leave a card on a note type with fewer templates than
it needs — is **refused**, whatever flags you pass. Not out of caution about your files: nobody has
measured what Anki does with a card whose template no longer exists, and the honest answer is that
it might be kept, orphaned, or destroyed. The run tells you which notes it refused and why, and
suggests doing it by hand in Anki's own *Change Note Type* dialogue, which shows you the mapping
before it acts.

That refusal runs **regardless of the flag above**. `--no-migrate-note-types` only decides whether
moves that have already been judged safe are carried out; it cannot make an unmeasured one happen,
and dropping it cannot either.

## Deletion, and orphans

**The sync never deletes anything.** A card in Anki whose source heading has gone is *flagged*
with an `orphaned::` tag and *suspended* — Anki's own mechanism, which keeps the card, its deck
and its whole scheduling state while taking it out of the daily rotation. Put the heading back and
the flag is cleared and the card unsuspended.

_**Corrected 2026-08-27.** This said "The run reports what it suspended", and it does not. The run
that suspends a card prints `N flag as orphaned` — the word *suspend* is not in that line. Every
run **afterwards** does say it, in the standing count of parked notes. So the run that takes cards
out of your review queue is the quiet one, and the ones that change nothing are loud about it.
Naming the suspension in the plan line is programme item 5 of `docs/findings/EVOLVABILITY.md`; this sentence
is corrected rather than left describing the intention._

A separate `prune` command to delete flagged cards after you have read the list **is not built
yet**.

Because a rename is indistinguishable from a deletion followed by an unrelated creation, this is
also how a renamed heading surfaces: an orphan plus a new card, reconciled by hand. That is
lossless precisely because the orphan is still there.

## Editing a card at its source

Reviewing is when you notice a card is badly worded, and **Anki is the one place you must not fix
it**: an edit made there never reaches your vault and is overwritten by the next sync.

So there is an Anki add-on, in `addon/obsidian_edit/`, that redirects Anki's **Edit** action. For
a card this tool created, pressing `e` opens the source note in Obsidian, at the card's own line.
For every other card in the collection it opens Anki's editor exactly as before — which is most
of them, and is the case that must never break.

**Nothing changes in Anki until you sync.** The card in front of you keeps its old wording for
the rest of the session. The loop is: press `e` → edit in Obsidian → run `sync` → the card
updates on the next review.

**It shortens the path to the expensive edit, and that is worth knowing before you use it.** The
natural fix for a badly worded card is to reword its heading, and *Changing your mind about a
card's shape* above explains what that costs: the card is retired and a new one is minted with no
review history. When the wording is wrong, prefer fixing the body. The add-on says so at the
moment it matters — if the card you are opening has already lost its anchor, it tells you.

### The command underneath it

The add-on decodes nothing. It reads the card's identity — from its `Identity` field, or from a
`src::` tag if the note is on a note type this tool does not own — and asks the tool:

```bash
obsidian-anki-sync locate --vault-path ~/my-vault 'src::abc123::cap%20theorem/definition'
```

which prints an `obsidian://` URI, or says why it cannot. `--uri-only` puts the URI on standard
output and any explanation on standard error, which is how the add-on reads it. It needs no
`--profile`, because it reads no collection.

That split is the whole design. Decoding the tag inside the add-on would mean a **second
implementation of card identity**, in a second language, kept honest only by a test — the defect
class this project fights hardest. The tool that wrote the tag is the thing that reads it.

`locate` is useful on its own. Run it over every tag in your collection and you have an audit of
which cards still point at live notes.

### Setting it up

**1. The Obsidian side.** Install and enable
[Advanced URI](https://github.com/Vinzent03/obsidian-advanced-uri). Its default *UID field in
frontmatter* setting is already `id`, which is the field this tool derives identity from, so
there is normally nothing to configure.

**2. The Anki side.** Put `addon/obsidian_edit/` where Anki looks for add-ons — on macOS,
`~/Library/Application Support/Anki2/addons21/`. A symlink works and keeps it updated in place.

**3. Configure it** in Anki under *Tools → Add-ons → Edit in Obsidian → Config*:

| Setting | What it is |
|---|---|
| `binary` | full path to `obsidian-anki-sync`. A bare name will not do — see below |
| `vault_path` | the vault directory, the one holding `.obsidian` |
| `vault_name` | usually empty. Only if Obsidian shows your vault under a name other than its directory's |
| `java_home` | usually empty. See below |

**4. Restart Anki.** The add-on does not load until you do.

### ⚠️ Anki does not inherit your shell's `PATH`

It is launched from the Dock or a launcher, not from a terminal, so it sees the session's
environment rather than the one your shell builds. Two consequences, and both have already caught
someone:

- **`binary` must be an absolute path.** `which obsidian-anki-sync` and paste what it prints.
- **`java_home` must be set if your JVM came from a version manager** — mise, asdf, sdkman. Those
  put `java` on a `PATH` that only a shell assembles, so Anki cannot see it. The symptom is
  *"Unable to locate a Java Runtime"*, which is true and thoroughly misleading: the JVM is
  installed and merely unreachable from a process Anki started. macOS's own
  `/usr/libexec/java_home` does not find a version-manager install either. Run `echo $JAVA_HOME`
  and paste that. Leave it empty if `java` works from a bare `/usr/bin:/bin` path.

The add-on adds that instruction to the error when it recognises the failure, but it is written
here too, because the error you get is from a launcher that does not know why it cannot see a JVM.

### The other direction — the cards a note produced

The dual of the above: from a note in Obsidian, a keystroke that opens Anki's **Browse** window
filtered to the cards that note produced. Together the two close the loop — a card takes you to
its source, and a note takes you to everything it became.

**Ask the tool where the cards are; do not work it out yourself.** A card's identity is this
tool's to spell, and it has changed twice in two days — it moved from a tag into a field, and the
tag will stop being written. So the command below hands over a note's frontmatter id and nothing
else, and every volatile part stays inside the binary.

> **This used to be a `curl` one-liner that built the Anki search itself**, and the README called
> that its whole trick: composable without asking the tool anything, so the binding cost nothing.
> What it actually bought was a copy of the identity format in a config file this repository
> cannot read, test, or migrate — so moving the identity would have turned the keystroke into an
> **empty Browse window**, which reads as *this note made no cards* rather than as a fault. It was
> found by someone asking, not by anything failing. Changed 2026-08-29.

It uses [Shell commands](https://github.com/Taitava/obsidian-shellcommands). Create a shell
command with this as its body, give it an alias, and bind it:

```bash
open -a Anki ; obsidian-anki-sync browse --profile "Your Profile" --note-id {{yaml_value:id}}
```

`open -a Anki` is macOS. Elsewhere, whatever raises a window: `wmctrl -a Anki`, or nothing at all
if your window manager already follows focus.

**`open -a Anki` runs FIRST**, and that is the one detail still worth knowing. Anki opens the
Browse window and raises it *within* Anki, but macOS will not let Anki pull itself in front of
Obsidian, so the app has to be raised separately. Do that last and it raises Anki's *main* window
over the Browse window that just opened — which looks exactly like Browse never opening.

**Two hazards this command used to carry are simply gone**, and they are recorded because they
are what the change bought. The id no longer passes through a JSON body, so the plugin's escaping
— which turns every hyphen of a UUID into `\-` and made AnkiConnect reject the whole request
silently — can no longer break anything. And the search is no longer written by hand, so it
cannot fall behind the identity it is searching for.

**Orphaned cards appear too**, which is a feature rather than an accident: a flagged card keeps
its identity beside its `orphaned::` tag. A note whose heading you reworded shows the live card
and the retired one side by side, which is the clearest view you will get of what a rewording cost.

### Drilling a note's cards, ignoring the schedule

The third of the three: from a note in Obsidian, study **that note's cards right now**, whatever
their due dates say. Before an exam, or after rewriting a section and wanting to check the cards
still make sense.

This builds what Anki's own *Custom Study* builds — a **filtered deck**. That is the right
mechanism precisely because it is temporary: cards are borrowed rather than moved, each one
remembers the deck it came from, and emptying the deck sends them all home.

**Rescheduling is off.** Answers here do not touch a card's real interval, so the same note can be
drilled ten times in an evening without distorting the schedule you have built. It is the
difference between practising and reviewing. Suspended cards are not gathered, which means a card
retired by a reworded heading will not appear — correct, and worth knowing.

**Nothing is left behind.** The deck is named after the note and lives at the top level, never
nested — a nested name would leave an empty parent deck in your list. Finished drills are removed
when you start the next one, and *every* drill is removed when Anki closes. Each removal empties
the deck first, because emptying is the operation that returns the cards home; deletion is then
only ever the deletion of an empty deck.

**Unlike the other two directions, this one needs the add-on**, which adds two ways in:

- **In Anki**, the Browse window gains *Cards → "Study these cards now (temporary deck)"*, which
  drills whatever search you are looking at. Deliberately not restricted to this tool's cards.
- **From Obsidian**, the add-on registers a `studyFromNote` action on AnkiConnect, so a shell
  command can reach it:

```bash
open -a Anki ; ID={{yaml_value:id}} ; T={{title}} ; R=$(curl -sS localhost:8765 -X POST -d "$(jq -nc --arg id "$ID" --arg t "$T" '{action:"studyFromNote",version:6,params:{noteId:$id,title:$t}}')") ; case "$R" in *'"error": null'*|*'"error":null'*) ;; *) echo "$R" >&2 ;; esac
```

**Why `jq` and why the `case`.** The payload carries the note's *title*, which can contain quotes;
`jq --arg` escapes its arguments correctly by construction, where a hand-stitched JSON string
does not. And AnkiConnect answers a failed action with **HTTP 200 and an `error` field**, so
`curl` exits 0 and a discarded body hides the reason entirely — the `case` puts anything that is
not a success on stderr, where the plugin shows it. Both of those cost a debugging round before
they were written down.

Registering an action on AnkiConnect means reaching into another add-on: it dispatches by
inspecting its own methods, and this attaches one. That is a knowing choice, and a different risk
from the silent traps above — if AnkiConnect ever changes, the action is simply not found and the
reply says so.

## Keys and commands, in one place

Nothing here is installed for you, and the keys are **suggestions**: bind whatever fits your
layout. What matters is which command each is bound to.

| Where | Suggested key | Command | Needs |
|---|---|---|---|
| Anki, reviewing | `e` (Anki's own) | open this card's note in Obsidian | the add-on; Advanced URI |
| Anki, Browse | — (menu item) | *Cards → Study these cards now* | the add-on |
| Obsidian | `Cmd+Alt+A` | open Anki's Browse on this note's cards | Shell commands; AnkiConnect |
| Obsidian | `Cmd+Ctrl+Alt+D` | drill this note's cards now | Shell commands; AnkiConnect; the add-on; `jq` |

**On picking keys.** `Cmd+Alt+D` looks free and is not: macOS uses it to toggle Dock hiding, and
that shortcut is built into the Dock rather than registered in the database every other system
shortcut lives in — so it is invisible to any check you can run. Obsidian's own hotkey settings
will warn about a clash with Obsidian, and about nothing else. Adding `Ctrl` steps out of the way.

`open -a Anki` is macOS. Elsewhere use whatever raises a window, or nothing if your window
manager follows focus. Raising Anki must come **first** in these commands — see the note on
ordering above.

## Exit codes

| Code | Meaning |
|---|---|
| 0 | it ran and nothing is wrong — a dry run with pending actions is 0 |
| 1 | it ran and something needs your attention: an action failed, a card could not be built, a note was left on the wrong note type, or it stopped early |
| 2 | it **refused** and nothing was written |

One exception worth knowing for scripts: a command line rejected by the argument parser exits 1
on stderr, without the run having started.

_**Corrected 2026-08-27.** The `1` row listed three causes and there are four: a note left on a
different note type from the one the vault asks for also exits 1 (`cli/Main.scala:1159-1170` folds
it into the same reasons list). That matters for a script, because it is not a failure — nothing
went wrong and nothing was written — it is the tool declining to restructure a note without being
asked. A caller cannot currently tell the two apart from the exit code alone._

## Not built yet

Named here so their absence is not mistaken for a promise:

- `prune`, the command that deletes flagged cards
- rename detection, deliberately cut — a rename is an orphan plus a create
- pushing new-card position, so introduction order follows an authored route
- **`install-addon`.** Every value the *Editing a card at its source* setup asks you to type is
  one this tool could work out for itself — it is a JVM process, so `java.home` is its own
  location; it knows its own path; it already resolves the vault. A sibling to
  `install-note-types` would write the add-on into Anki's add-on directory fully configured, and
  check what it cannot set: that Advanced URI is *enabled* rather than merely present, and that
  the note types are installed. The design is `docs/history/EDIT-IN-OBSIDIAN-PLAN.md`, Phase 6
- **any check that a collection belongs to the vault being synced.** See *One vault per Anki
  profile* above: the tool cannot tell one vault's notes from another's, so it cannot warn you.
  A `vault::` tag written beside `src::` would give it the means — tags are not hashed, so that
  would re-key nothing and move no golden — but notes already synced carry no such tag, and
  adopting them needs an action the plan model does not have

## Reading further

| File | What is in it |
|---|---|
| `docs/reference/REQUIREMENTS.md` | what this must do and must not, with each claim marked as stated, verified or inferred |
| `docs/reference/CARD-MODEL.md` | the card model, markers and identity scheme in full |
| `docs/reference/LEARNING-MODEL.md` | the pedagogy the card shapes come from |
| `docs/design/EDIT-IN-OBSIDIAN.md` | why Edit redirects to Obsidian, and where the identity tag gets decoded |
| `docs/history/EDIT-IN-OBSIDIAN-PLAN.md` | what was built for it, what was measured, and what is left |
| `docs/reference/FIXTURES.md` | what every file in `dummy-vault/` and `hostile-vaults/` is for, and which are meant to fail |
| `docs/history/HANDOFF.md` | how the code is laid out, and the AnkiConnect behaviours it defends against |
| `docs/history/NOTE-TYPES-AND-CONTEXT-DESIGN.md` | why the note types are shaped as they are |
| `docs/history/RECONCILER-SHAPE.md` | how the plan is computed and applied |
| `docs/README.md` | what each documentation directory claims, and which of it may be out of date |

## Running the tests

```bash
just test
```

`dummy-vault/` contains deliberate failures and deliberate duplicate identities. They are
fixtures, not bugs — `FIXTURES.md` says which is which. It follows that `sync` cannot write
`dummy-vault` at all: copy it without `Patterns/Table-Edge-Cases.md` to exercise the write path.
