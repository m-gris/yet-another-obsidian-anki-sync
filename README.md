# obsidian-anki-custom-sync

A command-line tool that turns marked headings in an [Obsidian](https://obsidian.md) vault
into [Anki](https://apps.ankiweb.net) notes, and keeps them in step as the vault changes.

It is not an Obsidian plugin. It reads a folder of markdown files and talks to a running Anki
over [AnkiConnect](https://foosoft.net/projects/anki-connect/).

## The one property everything else serves

**Nothing generated is ever written back into your markdown.** No identifiers, no scheduling
comments, no note ids in frontmatter. Your notes are exactly what you typed.

A card's identity is *derived* from the note's frontmatter `id` plus the chain of headings
above it, and the binding to an Anki note is stored **in Anki**, as a tag beginning `src::`.
Anki is a derived artifact, so bookkeeping there costs nothing — which is precisely what makes
it unacceptable in the source.

The consequence you will feel: **rename a marked heading and its card loses its history.** The
tool has no rename detection. The old card is not deleted — it is flagged and suspended, so you
can reconcile it by hand — but it will not follow the heading. Move prose around freely; think
before you re-word a heading you have been reviewing for six months.

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

A file produces cards only if its frontmatter carries an `id`, and a heading produces a card
only if it carries a marker. Everything unmarked is ordinary prose.

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
| `#flashcard/cloze` | `==highlights==` blanked out | 1 per group |
| `#flashcard/sequence` | a list revealed one item at a time | 1 |
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

`==highlighted==` text is blanked out. Each highlight is its own deletion, keyed by its text —
so rewording the highlight starts a new card.

Prefix a digit to key the deletion by a **group** instead. Then the text may change freely and
the card keeps its history, and repeats of the same group are blanked together:

```markdown
## Bones of the forearm #flashcard/cloze

Two bones run between elbow and wrist. On the thumb side is the ==1|radius==; on the
little-finger side, the ==2|ulna==. In supination the ==1|radius== lies parallel to the
==2|ulna==.
```

Two *unlabelled* highlights with identical text are refused, because nothing but position could
tell them apart. Label them.

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

The tool would rather stop and tell you than produce a card that looks right and is not. It
refuses, naming the file and line, when:

- **two cards would have the same identity** — nothing at all is written for that run
- **a nested list is indented fewer than four spaces** — its own parser reads such a line as a
  new list rather than a sub-item, so the card would say something your note does not. Indent to
  four spaces or a tab. Obsidian's own Tab key writes one; two-space indentation usually arrives
  from another editor or a formatter
- **a marked heading has no prose of its own**, because a subheading follows it immediately
- **a table can produce no card** — no descriptor columns, or none whose header can name one
- **a `sequence` heading has no list**, or a `table` heading has no table
- **two unlabelled cloze deletions share their text**
- **a folder or heading contains `::`**, which is Anki's deck separator — and only when that
  part is actually being used as a deck level
- **the open Anki profile is not the one you named**

## Deletion, and orphans

**The sync never deletes anything.** A card in Anki whose source heading has gone is *flagged*
with an `orphaned::` tag and *suspended* — Anki's own mechanism, which keeps the card, its deck
and its whole scheduling state while taking it out of the daily rotation. The run reports what
it suspended. Put the heading back and the flag is cleared and the card unsuspended.

A separate `prune` command to delete flagged cards after you have read the list **is not built
yet**.

Because a rename is indistinguishable from a deletion followed by an unrelated creation, this is
also how a renamed heading surfaces: an orphan plus a new card, reconciled by hand. That is
lossless precisely because the orphan is still there.

## Exit codes

| Code | Meaning |
|---|---|
| 0 | it ran and nothing is wrong — a dry run with pending actions is 0 |
| 1 | it ran and something is wrong: actions failed, cards could not be built, or it stopped early |
| 2 | it **refused** and nothing was written |

One exception worth knowing for scripts: a command line rejected by the argument parser exits 1
on stderr, without the run having started.

## Not built yet

Named here so their absence is not mistaken for a promise:

- `prune`, the command that deletes flagged cards
- rename detection, deliberately cut — a rename is an orphan plus a create
- pushing new-card position, so introduction order follows an authored route

## Reading further

| File | What is in it |
|---|---|
| `docs/REQUIREMENTS.md` | what this must do and must not, with each claim marked as stated, verified or inferred |
| `docs/CARD-MODEL.md` | the card model, markers and identity scheme in full |
| `docs/LEARNING-MODEL.md` | the pedagogy the card shapes come from |
| `HANDOFF.md` | how the code is laid out, and the AnkiConnect behaviours it defends against |
| `FIXTURES.md` | what every file in `dummy-vault/` and `hostile-vaults/` is for, and which are meant to fail |
| `NOTE-TYPES-AND-CONTEXT-DESIGN.md` | why the note types are shaped as they are |
| `RECONCILER-SHAPE.md` | how the plan is computed and applied |

## Running the tests

```bash
just test
```

`dummy-vault/` contains deliberate failures and deliberate duplicate identities. They are
fixtures, not bugs — `FIXTURES.md` says which is which. It follows that `sync` cannot write
`dummy-vault` at all: copy it without `Patterns/Table-Edge-Cases.md` to exercise the write path.
