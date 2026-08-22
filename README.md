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
- **[scala-cli](https://scala-cli.virtuslab.org)** and a **JVM 17**. There is no packaged
  binary yet; you run it from source.

## Getting started

`<tool>` below is the path to this directory. scala-cli takes it as an argument, so you can run
these from anywhere.

**1. Create the note types.** Once per Anki profile, before the first sync.

```bash
scala-cli run <tool> -- install-note-types --profile 'My Profile'
```

This creates five note types of the tool's own — it never writes to Anki's stock `Basic`,
`Basic (and reversed card)` or `Cloze`, so nothing you already have is touched. It creates only
what is absent and overwrites nothing. If a note type is already there and differs from this
repository's version, it says so and stops; `--repair` is how you ask for it to be brought into
line, and it is opt-in because a template you improved inside Anki is yours.

**2. See what your vault would produce.** This touches no collection at all, so it needs no
profile and can be run at any time.

```bash
scala-cli run <tool> -- inspect --vault-path ~/my-vault
```

**3. See what would change in Anki, without changing it.**

```bash
scala-cli run <tool> -- sync --vault-path ~/my-vault --profile 'My Profile' --dry-run
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
| `#flashcard/3way` | concept–descriptor–description | 2 |
| `#flashcard/3way/all` | the same, plus recall the descriptor | 3 |
| `#flashcard/cloze` | `==highlights==` blanked out | 1 per group |
| `#flashcard/sequence` | a list revealed one item at a time | 1 |
| `#flashcard/table` | see *Tables* below | many |

`3way` counts **fields, not cards**. It selects the three-field shape whose default is two
directions — recall the concept, and recall the description. `3way/all` adds the third.

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

### Concept–descriptor

For a fact that is *an aspect of a thing*. The **concept** is the nearest ancestor heading, the
**descriptor** is the marked heading, and the **description** is the body. One file can
therefore feed several concepts, and two facets with the same name stay distinct because the
key is the heading *path*, not the heading text.

```markdown
## CAP Theorem

### Definition #flashcard/3way

When a network partition splits a distributed system, the system must choose between
answering with possibly stale data and refusing to answer at all.

### Failure mode #flashcard/3way

Treating the C-versus-A choice as a permanent architectural setting rather than a
per-partition decision.

## Quorum

### Definition #flashcard/3way

...
```

Those four headings give four distinct cards, `CAP Theorem / Definition` through
`Quorum / Failure mode`, rather than two that overwrite each other.

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
- a packaged binary

## Reading further

| File | What is in it |
|---|---|
| `../srs-obsidian-anki/REQUIREMENTS.md` | what this must do and must not, with each claim marked as stated, verified or inferred |
| `../srs-obsidian-anki/CARD-MODEL.md` | the card model, markers and identity scheme in full |
| `../srs-obsidian-anki/LEARNING-MODEL.md` | the pedagogy the card shapes come from |
| `HANDOFF.md` | how the code is laid out, and the AnkiConnect behaviours it defends against |
| `FIXTURES.md` | what every file in `dummy-vault/` and `hostile-vaults/` is for, and which are meant to fail |
| `NOTE-TYPES-AND-CONTEXT-DESIGN.md` | why the note types are shaped as they are |
| `RECONCILER-SHAPE.md` | how the plan is computed and applied |

## Running the tests

```bash
scala-cli test <tool>
```

`dummy-vault/` contains deliberate failures and deliberate duplicate identities. They are
fixtures, not bugs — `FIXTURES.md` says which is which. It follows that `sync` cannot write
`dummy-vault` at all: copy it without `Patterns/Table-Edge-Cases.md` to exercise the write path.
