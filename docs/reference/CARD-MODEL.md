# Card Model

> **Work on this document** — `bd list --all --spec docs/reference/CARD-MODEL.md`
>
> Closed means built, and the closing reason says what shipped; open means outstanding.
> **This document describes the card model as it stands, never progress** — a status kept in
> two places goes stale in one of them.

_How markdown becomes Anki cards: what is marked, what is generated, and how identity survives editing. Requirements and their evidence live in [REQUIREMENTS.md](./REQUIREMENTS.md); the pedagogy behind them in [LEARNING-MODEL.md](./LEARNING-MODEL.md)._

---

## TLDR

A heading marked under one `#flashcard/` root becomes an Anki note, at any depth; unmarked headings generate nothing. `--help` lists every marker, from a table a test ties to the source. Three other routes need no marked heading at all: a marker in a note's frontmatter makes the whole note a card, a block holding `==<<cloze>>==` deletions becomes one wherever it sits, and a relation declared in frontmatter becomes one too.

A card's concept comes from its note title or nearest ancestor heading, its descriptor from the marked heading, and its description from that section's body — a shape no existing bridge can express, because they all match lines rather than positions in the document tree.

Identity is derived from the frontmatter `id` plus whichever node of the note the card hangs off, and is held in a field on the Anki note rather than written into the markdown.

Decks mirror folder paths and carry filing only — never learning order, which comes from tags and new-card position instead.

The MOC, the authored route and the typed-edge graph itself are deferred until the basic path is in daily use. A relation already becomes a card; nothing yet traverses or queries the graph those relations describe.

---
## Summary

Every existing Obsidian→Anki bridge matches lines with regular expressions and has no notion of a line's position in the document tree. That is why none can express a heading naming a concept with the sections beneath it as its descriptors — and it is the only honest justification for building rather than adopting.

**Marking is explicit and namespaced.** A heading at any depth opts in under `#flashcard/`; unmarked headings generate nothing, so ordinary prose sections stay ordinary. The numbering means different things in the two families: `1way` and `2way` count retrieval directions over a heading-and-body card, while the `cdd` family counts *fields* and defaults to two directions. Silent card creation is the failure mode worth designing against.

**The card shape** is Concept–Descriptor–Description with three possible directions: recall the concept given the other two, recall the description given concept and descriptor, and — only with `#flashcard/3way/all` — recall the descriptor given concept and description. The Anki note type for this already exists with exactly those three templates, and the third is made optional by a conditional field rather than a second type. It carries more than the three named fields: gates selecting how many directions a marker asks for, the on-card breadcrumb, a label for what kind of thing a table's concept is, and the card's identity.

**The concept comes from the note's H1 or filename**, or the nearest ancestor heading in multi-topic files. The description is the whole section body — prose, lists, formulae — which is why headings beat an inline one-line form, and which leaves the `::` family unused entirely, so no delimiter ever competes with Dataview inline fields.

**Identity is derived, not written.** The key is `(frontmatter id, anchor)`, where an anchor names WHICH NODE OF THE NOTE the card hangs off — built from text already present. _Widened 2026-08-25 from `(frontmatter id, heading path)`; see **What a card can be anchored to** below._ The binding to an Anki note is held on the Anki side, in an `Identity` field — and, on notes written before that field existed, in a `src::` tag still read for them. A heading card writes nothing into the markdown at any card count; a cloze block is the exception, needing one author-written `^blockid` per block. The sync never deletes: absent cards are suspended in place and flagged, and removing them is a separate explicit act, because an undetected rename is indistinguishable from a deletion. A local sidecar is a cache, rebuildable from what Anki holds. Renaming a heading breaks its key: the old card is suspended and reported with its review history intact, and pairing it with the renamed one is done by hand — automatic rename detection is **not built**. _Amended 2026-08-19._

**Decks mirror folder paths** under a root prefix, with the file deliberately *not* a deck level; otherwise every concept becomes its own two-card deck. Decks are filing only. Study scope comes from filtered decks over tags, and introduction order from new-card position — conflating the three is what sank the earlier design.

**Cloze** is written `==<<text>>==` — the brackets are what make a card, so a bare `==highlight==` stays ordinary emphasis — and is converted to Anki syntax on the way out. A cloze note is scoped to a **block**, not a section, and a block holding deletions with no `^blockid` is refused rather than given an identity that will not hold; a `#flashcard/cloze` heading is a second route, claiming its whole section. Deletions are **grouped**, optionally: `==<<N|text>>==` joins group `N`, and unlabelled groups take the lowest unclaimed number in order of first appearance — so adding a label anywhere renumbers the unlabelled groups around it. Two *unlabelled* highlights with identical text are refused, because only position could tell them apart.

**Tables** are concept–descriptor–description triples written compactly: first cell the concept, column header the descriptor, cell the description. Each row also yields a synthesis card carrying all descriptors together, since a benefit divorced from its cost is trivia. Because both axes are named, tables produce the most robust keys in the design.

**Unordered lists** are covered by plain multi-cloze. **Ordered lists** with progressive disclosure cannot be expressed by a single Anki note and are deferred.

Also deferred, and not rejected: the MOC and authored route, new-card position pushing, the typed-edge graph, structure cards, and automatic deletion — the sync only flags and suspends, and the command that would remove a flagged note is unbuilt.

---
## Full

### Why this exists

Every existing Obsidian→Anki bridge matches **lines** with regular expressions. None of them knows a line's position in the document tree. That is the reason none can express the shape wanted here — a heading that names a concept, with the sections beneath it as its descriptors.

The design below is therefore not a replacement for a dead plugin. It does something structurally unavailable in the current ecosystem, which is the only honest justification for building anything.

### The markers

A heading opts in explicitly. Headings without a marker generate nothing, so ordinary prose sections — `## Introduction`, `## Best practices` — stay ordinary. Marked headings may sit at **any depth**; nothing depends on the level.

_**Vocabulary corrected 2026-08-27**, against `model/Marker.scala`'s `Documented` table — which
`--help` prints and which a test ties to the source, so it is the one place that cannot drift. This
table had said `3way` / `3way/all` since before the 2026-08-24 rename, and had never mentioned
`sequence` at all, though `sequence` has a note type, a fixture and a section in the README. **The
one-card concept-descriptor shape, `cdd/1way`, did not appear in the ratified model at any point.**_

| Marker | Fields | Cards | Anki note type |
|---|---|---|---|
| `#flashcard/1way` | heading, body | 1 | Basic |
| `#flashcard/2way` | heading, body | 2 — heading ⇄ body | Basic (and reversed) |
| `#flashcard/cdd/1way` | concept, descriptor, description | 1 — recall the description | Concept-Descriptor, `ValueOnly` set |
| `#flashcard/cdd/2way` | the same | **2** — and also which thing has this | the same, no gate set |
| `#flashcard/cdd/3way` | the same | 3 — and also which aspect this is | the same, `ThreeWay` set |
| `#flashcard/cloze` | section body | 1 per deletion **group** | Cloze |
| `#flashcard/sequence` | heading, a list | 1 — items revealed one at a time, on one schedule | Cloze Sequence |
| `#flashcard/table` | per row and column | n pair cards + 1 row card | mixed |

`table` takes direction suffixes (`table/1way`, `/2way`, `/3way`) and a scope suffix (`/cells`,
`/rows`), and the two do not combine freely: `table/{n}way/cells` exists, `table/rows` exists, and
`table/{n}way/rows` deliberately does not — with no cell cards there is nothing for a direction to
apply to, so the spelling would name a choice that changes nothing. `--help` lists every spelling.

**`3way` and `3way/all` remain as ALIASES** of `cdd/2way` and `cdd/3way` — the same values, so
rewriting a vault's markers changes no key, no note type and no field, and syncs nothing.
oas-3h7 carries the open question of whether to retire them.

**Why the rename.** `Nway` counts RETRIEVAL DIRECTIONS everywhere, and the ceiling is a property of
the SHAPE — a heading-and-body card has two fields and so at most two directions, a
concept-descriptor card has three. `#flashcard/3way` broke that: it used a direction word to select
a SHAPE and then produced two cards, needing `/all` for a third. The shape is now named, mirroring
`table`, which was always coherent. Front-back stays unprefixed — it is what you get when you name
no shape.

**Every marker uses the identical markdown shape — a heading and its body.** They differ only in how those two pieces map to fields, and whether an ancestor heading is pulled in as a third:

```markdown
# Linearizability                        ← ancestor concept

## Definition #flashcard/3way            heading  → Descriptor
Operations appear to take effect...      body     → Description
                                         ancestor → Concept

## Temporal coupling #flashcard/2way     heading  → front
All parties must be up simultaneously.   body     → back
                                         ancestor   ignored
```

`3way` is `2way` plus the ancestor. Nothing else changes.

The numbering, however, means different things in each family — worth stating plainly rather than discovering later:

- **`1way` / `2way` count retrieval directions** over a two-field card: the heading is the front, its section body the back. `2way` reverses as well.
- **`3way` counts fields, not cards.** It selects the concept–descriptor shape, whose default is *two* directions (recall the concept; recall the description). `/all` adds the third (recall the descriptor).

**The two families need opposite qualities from the heading**, which is the one constraint to keep in mind while writing:

| | The heading must be | Example |
|---|---|---|
| `1way` / `2way` | **self-contained** | *Temporal coupling*, *Why does a quorum need a majority?* |
| `3way` | a **facet name**, meaningless alone | *Definition*, *Cost*, *Contrast* |

So markers are not freely swappable. Retagging `## Definition` as `#flashcard/2way` produces a card whose front is the bare word "Definition" — nonsense. The choice is coupled to how the heading was named, which matters more once there are two hundred of them.

Every MARKER lives under one `#flashcard/` root, so the tag pane groups marked headings by kind. It does not find every card: a cloze block is scanned in an unmarked section and a relation is declared under its own heading, so neither carries a `#flashcard` tag anywhere. Opt-in rather than opt-out: silent card creation is the failure mode worth avoiding, and an unmarked heading doing nothing is easy to reason about.

### The two-field family

```markdown
#### Why does a quorum need a majority? #flashcard/1way

So that any two quorums intersect, guaranteeing at least one node
has seen both writes.

### Temporal coupling #flashcard/2way

A situation in which all parties must be up at the same time for
the system to work.
```

The heading is the front, the section body the back. `#flashcard/2way` additionally reverses — given the body, recall the heading — which suits term-and-definition pairs and reads badly for questions, so the choice between them is yours per heading.

Depth is arbitrary: an `####` under three levels of prose works exactly as an `##` does. This is the simplest shape and probably the one most sections want; the concept–descriptor form earns its extra field only when a single concept has several named facets.

### The three-field family

Concept, Descriptor and Description — three fields, three possible directions, of which two are generated by default:

| Direction | Front | Back | Default |
|---|---|---|---|
| 1 | descriptor + description | **concept** | yes |
| 2 | concept + descriptor | **description** | yes |
| 3 | concept + description | **descriptor** | only with `#flashcard/3way/all` |

The Anki note type is `Obsidian Concept-Descriptor` — resolved **by name**, because note-type ids are collection-local and hardcoding one breaks the moment a profile is duplicated — with exactly these three templates in this order. The third is optional without a second note type: a `ThreeWay` field gates Card 3's *front*, and Anki generates a card only when its front renders non-empty, which is how the stock "Basic (optional reversed card)" works.

> **THIS DEFECT NO LONGER EXISTS. Verified 2026-08-27** by reading `Obsidian Concept-Descriptor`
> out of the live collection: all three FRONTS carry a `class="blank"` marker where their own
> answer would go, and all three BACKS render the whole triple. The paragraph below described the
> HAND-MADE note type this tool inherited; the note types it now installs from
> `resources/note-types/` are idiomatic. `docs/history/NOTE-TYPES-AND-CONTEXT-DESIGN.md` recorded a reader
> reaching the same conclusion and being unable to say which document was current — so it is said
> here, with what was read.

### Where the concept comes from

Default: the note's `# H1`, or its filename. For files holding several topics: the nearest ancestor heading above the marked one.

```markdown
# Linearizability                 ← concept
## Definition #flashcard/3way               ← descriptor
Operations appear to take effect instantaneously at some single point
between their invocation and their response.
```

The description is the whole section body — prose, lists, formulae, code. This is the reason headings beat an inline `descriptor ::: description` form: real answers are rarely one line.

A secondary benefit: **no `::` delimiter is used anywhere in this design.** Every card kind is marked by a heading tag, so nothing competes with Dataview inline fields and no delimiter collision has to be adjudicated.

### Identity

The binding between a markdown card and its Anki note must survive editing, or every edit duplicates.

**The key is derived from what is already written:**

```
concept/descriptor cards   (frontmatter id, heading path)
table cards                (frontmatter id, heading path, row concept, column header)
cloze sections             (frontmatter id, heading path)
cloze blocks               (frontmatter id, block identifier)
typed-edge cards           (frontmatter id, property name)
the note itself            (frontmatter id, nothing below it)
```

### What a card can be anchored to

_Widened 2026-08-25. Until then a heading was the only markable thing, so an anchor could be a bare
chain of heading names._

A note is a **tree of nodes** and a card hangs off one of them. A heading is one kind of node, in
the same way a directory is one kind of filesystem entry. A frontmatter property is a node. The
note itself is a node. Neither is reachable through a chain of headings and neither is a special
case of one.

**A mixed anchor is not representable, and that is correct rather than restrictive.** A property
belongs to the NOTE and never to a heading inside it, because Obsidian has no per-heading
frontmatter — so `headings / property` is not a shape the domain has. Modelling the anchor as a
list of kinded segments would admit it, and every consumer would then need a rule for something
that cannot occur.

**The KIND is part of identity, not a label beside it**, and the reason is a collision that was
about to be real. Writing a relation as the property `special-case-of:` and writing it as the
heading `# Special-Case-Of` were both genuinely on the table as ways of saying the same thing, and
an anchor of bare names gives those two different cards ONE key. One key for two cards is a
duplicate identity, which refuses the entire run until somebody renames something.

### Cards made from a relation

_Built 2026-08-26._ A relation declared in frontmatter — `special-case-of: "[[HomSet]]"` — is a
TRIPLE, and so is a concept–descriptor card. They are the same shape, which is why a relation
needs no note type, no marker and no card shape of its own: it is a `ThreeField` spec, and it
inherits identity, hashing, decks, breadcrumbs, orphan handling and the whole reconciler unchanged.
Subject is concept, predicate is descriptor, object is description.

**The subject is the FILE NAME, and it is only a field.** A heading card takes its concept from
the nearest ancestor heading and falls back to the file name; a relation has no ancestor heading at
all, so the file name is the only candidate — and it is the right one, since the file is what the
note is about. Because it is a field rather than part of the key, **renaming the file rewrites what
the card says and moves no identity**, and rewording every heading in the note does nothing to it.
That is a stronger guarantee than a heading card gets.

**Several values make ONE card**, answered as a set. Keying per value would put the value in the
key, so correcting a typo in one would retire that card and mint a replacement with no history.

**A relation needs no parsed body**, which is a property rather than an accident: it lives in
frontmatter, and the frontmatter parsed or the note would have no id. A body the strict parser
refuses costs the note its heading cards and none of its relations.

**Which properties are relations is declared BY EACH NOTE, for itself**, under a
`# Properties-to-Flashcards` heading in its own body. _It was one vault-wide declaration until
2026-08-26._ A relation earns its place in frontmatter for querying and for the graph, which is most
of its value; whether to be DRILLED on it is a separate decision belonging to the note that carries
it. A vault-wide vocabulary turns every occurrence everywhere into a card whether that was meant or
not — the difference between a lexically scoped expansion and a global rewrite.

**A declarations block is a set of REWRITE RULES, and that framing does work rather than decorating.**
`CardSpec` is the intermediate representation; markers and relations are two surface syntaxes over
it. A rule's right-hand side names what the property expands INTO, so it must be written in the
target language and read by the target language's parser — which is why `cdd/1way` in a rule is the
same token as `#flashcard/cdd/1way` on a heading, through `Marker.parse`. A rule written in a
vocabulary of its own was the first attempt, and it made one token mean two things: `1way` on a
heading is a two-field card. The frame catches that before the clash is noticed.

**A declarations block is metadata, not structure.** It does not count as a heading for the purpose
of deciding whether a note is headingless — otherwise declaring a relation on a headingless note
would silently retire its whole-note card.

The heading is matched leniently about spaces and hyphens, because a note with no declarations is
the ordinary case and therefore cannot be an error, which makes a near-miss the worst outcome
available.

**A reversible relation is CHECKED, not trusted or forbidden.** A `2way` or `3way` relation also
asks which thing has the far end, and for a many-to-one relation several notes answer that — the
same question on several cards, each holding a different right answer. Refusing `2way` outright
would ban the relations that genuinely are one-to-one; trusting the author asks them to know in
advance what the tool can see by looking, since it holds the whole vault at that moment. It is a
property of the VAULT and not of a note, so adding a third answer months later breaks two cards
that were fine, and the run that does it says so.

**The note-itself anchor was admitted by the type a day before anything produced one**, on
purpose: identity is the most expensive thing in this system to change once review history has
accumulated and the cheapest while a collection is nearly empty. A note carrying a marker in its
frontmatter and no heading it could have fallen off now produces one, and a test pins that.

**The heading *path*, not the heading text.** The path is the chain of ancestor headings down to the marked one, joined — `CAP Theorem/Definition`, not `Definition`.

This matters because the marked heading alone is *not unique within a file*, and the design's own multi-topic case is precisely where it collides:

```markdown
## CAP Theorem
### Definition #flashcard/3way     ← would key as (id, "Definition")
## Quorum
### Definition #flashcard/3way     ← would key identically
```

Two different cards, one key — the second would simply overwrite the first's Anki note. Keying on the full path removes it, generalises to arbitrary heading depth, and matches how table keys already extend with row and column.

_Amended 2026-08-19. This previously said the collision was **silent**, in contrast to the rename case. It is no longer either: the reconciler refuses to plan when two sources derive one key, and — since 2026-08-19 — when two **Anki notes** claim one key, which had been collapsing silently in the opposite direction and making the losing note invisible to every later run. Those checks are a backstop, not a substitute. Full-path keying removes the collision; a check only reports it._

**The binding is stored in Anki**, as a tag on each note: `src::{id}::{anchor}`. Anki is a derived artifact, so bookkeeping there costs nothing — which is precisely the objection that applies to putting it in the source.

### What Anki actually is

_Added 2026-08-27, correcting the sentence immediately above rather than deleting it, because the
sentence is half true and the half that is true is load-bearing._

**An Anki collection is not a derived artifact. It is a JOIN of a derived layer and an accumulated
one**, and the `src::` tag is what joins them.

| layer | what is in it | can it be recomputed? |
| --- | --- | --- |
| derived | fields, decks, note types, the tags this tool owns | **yes** — from the markdown, at any time, for nothing |
| accumulated | interval, ease, due date, lapses, the review log | **no** — from nothing, ever. It is a record of one person's forgetting, earned one review at a time |

So "bookkeeping there costs nothing" is true of the first row and false of the second, and every
hard decision in this system sits on the seam. **What is NOT weakened is the invariant beside it:
the tool never writes the vault.** That one is structural — no production source opens a vault file for writing — and it is not what this correction is about.

**Two rulings that read as unrelated are the same ruling seen twice.** An orphan is the case where
the derived side says *gone* and the accumulated side says *but I hold value*, so the tool suspends
and waits for a person. A deferred retype is the case where the derived side says *change shape*
and the accumulated side says *that might cost me*, so the tool defers and waits for a person. Both
are **the tool declining to reconcile the two layers and handing the conflict back**, and neither
is a failure — it is the only honest answer available when one layer is regenerable and the other
is not.

The tool has no word for that state, which is why the two read differently in a report: one prints
as *parked* and the other as *safe*. See `docs/findings/EVOLVABILITY.md` for what each costs.

**A heading anchor encodes exactly as it always has**, `{seg}/{seg}/…`, and that was a requirement
rather than a nicety: `extract/golden/fixture-cards.txt` pins every identity tag the fixture vault produces and opens
with `DO NOT REGENERATE THIS FILE`, so rewriting all of them by hand is indistinguishable, in a
diff, from the blind regeneration it exists to catch. The other anchors therefore take a shape no
heading anchor can produce — a **leading empty token**, then `/p/{property}`, `/n` or `/b/{anchor}` — which is
unambiguous because a heading segment is refused when empty both at construction and when decoded,
so percent-encoding one can never yield an empty string. That invariant is pinned by a test rather
than trusted, including against headings named `p`, `n` and `b`, and one containing a slash.

**The tag must be encoded, and this is not optional.** _Amended 2026-08-19._ The naive form above cannot work: **Anki tags are whitespace-delimited**, and most headings in a real vault contain spaces — `src::x::CAP Theorem/Definition` silently becomes *two* tags. (Corroboration: the one leftover note in the `POC-test` profile is tagged `Obsidian_to_Anki`; the dead plugin hit the same wall and solved it with an underscore.)

| | |
|---|---|
| **Encoding** | percent-encode everything outside `[A-Za-z0-9.-]` |
| **Why that set** | whitespace splits tags; `_` and `*` are **wildcards** in Anki tag search, so leaving either raw turns every exact lookup fuzzy; `/` occurs in real headings (`Cost / benefit`); `:` is Anki's own hierarchy separator |
| **Equality** | NFC + case-fold + collapse internal whitespace, applied **at construction** — so encoding stays pure transport |

Case-folding is required because Anki matches tags case-insensitively. Collapsing internal whitespace is required because a formatter or linter will normalise a stray double space, and a key that isn't stable under formatting silently orphans cards.

Rejected: base64url (unreadable in Anki's tag browser), and picking a "rarer separator" (no character is impossible in a heading).

**Nothing generated is written into the markdown.** The marker count does not scale with card count; it is zero at one descriptor and zero at fifty. This is only possible because the note shape is committed to: general-purpose tools cannot assume it, which is why all of them mint and write an identifier instead.

A local sidecar mapping keys to Anki note ids is a **cache**, not an oracle. Deleting it is harmless — it rebuilds by reading the `src::` tags back out of Anki.

**Sync becomes:** read markdown → compute keys → query Anki for `src::` tags → match updates, unmatched creates, and anything in Anki with no markdown counterpart is **flagged, never deleted by the sync**.

**What breaks:** renaming a heading, or moving it under a different ancestor, changes its key. This is visible rather than silent — Anki holds the expected key, markdown holds a new one, and nothing else claims either — so it surfaces as an orphaned card: suspended, tagged and listed in the report, next to the newly created one. Failing loudly is the requirement; never failing is not on offer.

_Amended 2026-08-19. This previously ended "so it surfaces as a relink prompt". Pairing an orphan with its renamed counterpart automatically is **not built**, and was cut from v0 — see *Deletion*. Reconciliation is manual, and suspension is what makes it worth doing: the card keeps its full review history, so restoring the heading restores the card intact rather than starting it over._

`id:` in frontmatter becomes load-bearing. It is an existing convention, not a new tax, but deleting one orphans that note's cards.

### Deletion

**Decided 2026-08-18. Extended 2026-08-19.** The sync itself never deletes. A section that disappears from the markdown leaves its Anki note in place, **suspended** and tagged `orphaned::`, and the run reports what it suspended. Removing such a note is a separate, explicit act, done only after the list has been seen. The command for it is unbuilt.

**Suspension, not relocation.** _Decided 2026-08-19._ A tag alone was too quiet: the card stayed in the daily review rotation, so a card whose source heading no longer exists went on being asked, and the only sign was a tag nobody reads. Suspension is Anki's own mechanism for exactly this — the card keeps its deck, its interval and its whole scheduling state, and simply leaves the queue until unsuspended. Moving orphans into a holding deck was considered and rejected: decks mirror **folders** while the identity tag encodes the **heading path**, so once a heading is gone the original folder is not recomputable and the card's current deck is the only surviving record of it. Any mirrored path would be a copy of that record, going stale the moment folders are reorganised.

The reason the sync never deletes is that **deletion and rename detection interact**, and the interaction is where history gets lost silently.

Renaming a heading changes its key: Anki holds a key nothing claims, markdown holds a key nothing matches. _Amended 2026-08-19: this previously said "and the tool detects that heuristically". **It does not.** Automatic rename detection was cut from v0 — see *Deliberately deferred* — so every rename is an undetected one._ That makes the argument stronger rather than weaker: if the sync deleted on absence, a rename would be indistinguishable from a delete followed by a create — the card's scheduling history destroyed, a fresh card in its place, no signal. Delete-on-absence is only as safe as a rename heuristic, and there is no heuristic at all, let alone one fit to justify an irreversible operation.

Flag-then-prune gets the same tidiness without that bet. Nothing is destroyed except on request, after the list has been seen.

**A known hazard, accepted for v0 rather than solved.** _Recorded 2026-08-19._ The tool cannot distinguish *"this heading was deleted"* from *"I could not see this heading"*. A vault that yields no marked headings is a legitimate **complete** scan of nothing — and a complete scan is precisely what licenses orphan detection — so every card in the collection is then absent from the markdown, gets flagged, and the run reports success.

Two of the three ways in are now closed at the command line, by requiring the vault argument to be a directory carrying Obsidian's `.obsidian` marker rather than merely to exist: pointing at the vault's **parent**, and pointing at an **unrelated folder**, are both refused before anything is read.

The third is not closed and cannot be by this means: **a vault whose files have not finished arriving**. With the vault on Dropbox or iCloud, `.obsidian` may land before the notes do, and a partially materialised vault is indistinguishable from one whose headings were removed.

> **⚠️ THE CONDITION ON THIS ACCEPTANCE HAS EXPIRED. Amended 2026-08-27, not withdrawn.** The
> acceptance below is explicitly conditional — it says the hazard is cheap "because the damage is
> self-repairing" and that it "stops being cheap the moment flagged cards are also **suspended**",
> and instructs a reader to revisit it then. **Suspension shipped on 2026-08-19 and nobody
> revisited.** So the paragraph below currently reads as a live, valid acceptance of a hazard whose
> own stated precondition no longer holds: a mistimed run over a half-materialised vault now
> empties the review queue rather than merely tagging it, and a `prune` command would make that
> irreversible. `docs/findings/EVOLVABILITY.md` §3.4 carries the full chain, and bead `oas-oyg` carries the
> candidate guard — refuse a run whose COMPLETE scan yields zero cards while the collection holds
> some, which needs no proportion and therefore no unjustifiable number.

It is accepted for v0 because the damage is self-repairing: flagging is reversible, and the next run over a complete vault clears the flags. It stops being cheap the moment flagged cards are also **suspended** — a mistimed sync would then empty the review queue until the next correct run. Revisit before or alongside that work; the candidates considered and not taken were refusing when the vault yields zero cards while the collection holds some, and refusing above some proportion of the collection going orphan at once. The second was rejected on the grounds that the proportion is a number nobody can justify.

It costs the reconciler nothing: `Flag` is already one of the `SyncAction` cases, and removal is a separate act reading those tags.

### Decks

**Folder path → deck path, under a root prefix. The file is not a deck level.**

```
References/Design-Gurus/Module 1.md   →   Obsidian::References::Design-Gurus
```

Every note in a folder contributes to that folder's deck. Making the file a deck level would produce one deck per concept — hundreds holding two cards each.

The root prefix isolates synced cards from any deck made by hand, so the subtree can be deleted and rebuilt without touching anything else.

Moving a note between folders changes its deck. AnkiConnect's `changeDeck` handles this without resetting scheduling.

**Decks deliberately carry no learning order.** They are filing. Study scope comes from filtered decks over tags; introduction order comes from new-card sort position. Conflating these is what sank the earlier `TARGET DECK` design.

### Cloze

A cloze card blanks part of a passage. Write `==<<text>>==` — the brackets are what make it a card. A bare `==highlight==` is an ordinary Obsidian highlight and generates nothing, so you can see at a glance which of your highlights are cards. Anki's `{{cN::…}}` is generated on the way out and never typed by hand.

**A cloze note is scoped to a block, not a section.** Any paragraph, list item, quoted block or table holding a deletion becomes one Anki note, provided the author has given it an Obsidian block identifier:

```markdown
The ==<<1|radius>>== and the ==<<2|ulna>>== are forearm bones. ^forearm
```

Two cards, siblings of one note. **Sibling burying is why the block and not the group is the unit**: Anki can keep siblings out of one session, so five gaps in a paragraph are not five encounters with the same sentence. It is also why a card shows its own block and nothing around it.

**A block with deletions and no `^blockid` is refused.** Nothing derived from a paragraph — its position, a hash of its text, the labels inside it — survives the edit that a card about that paragraph invites; and this tool never writes to the vault, so the name has to be the author's. Obsidian generates one with a keystroke. The refusal names both remedies: add the identifier, or put the deletions under a `#flashcard/cloze` heading.

**A `#flashcard/cloze` heading is the other route**, keyed by its heading path rather than by a block, claiming every highlight in its section. Block scanning runs only where no marker claims the blocks, so the two never make the same card twice. A section under any other marker is left alone — its body belongs to that card.

**Deletions are grouped, and grouping is optional.** One group makes one card.

- `==<<text>>==` — its own group of one.
- `==<<N|text>>==` — part of group `N`; highlights sharing a label are blanked together.

**Ordinals.** A labelled group's `cN` is its label. Unlabelled groups take the lowest number no label has claimed, in order of first appearance — so adding a label anywhere renumbers the unlabelled groups around it, and Anki schedules by ordinal, not by text.

**Two *unlabelled* highlights with identical text in one section are refused**, with the remedy named: label them. They are separate groups by rule, identical in text, and distinguishable only by position — and a positional tiebreak is the hazard the ordinal rule exists to avoid.

⚠️ The `|` in a label collides with a markdown table row: `| ==<<1|x>>== |` breaks the table. Acceptable only because cloze inside a table cell is not in the design — tables are their own card kind.

### Tables

A table row is a set of concept–descriptor–description triples written compactly. It needs no separate card model.

```markdown
## Cost / benefit #flashcard/table

| Pattern | Benefit         | Cost                |
| ------- | --------------- | ------------------- |
| Queue   | Load Absorption | Delay & Duplication |
```

- first cell → **Concept**
- column header → **Descriptor**
- cell → **Description**

Plus one **row card** per row, as a Basic note: the row rendered as a table with its values blanked on the front, and the same table filled in on the back, so the answer replaces the question rather than appearing beneath it. This is the card that preserves the relation — a benefit divorced from its cost is trivia, and the contrast is the point. Emit it only when a row carries **two or more** descriptors; below that it merely duplicates the pair card.

**Tables give the most robust keys in the design**, because both axes are named. Reordering rows or columns leaves keys intact. Adding a column adds cards without disturbing existing ones. Editing a cell's value updates a card in place.

### Lists

**Unordered** — membership is the knowledge, not sequence. Plain multi-cloze covers it, with the existing key scheme unchanged.

> **SHIPPED, CONTRADICTING THE SENTENCE BELOW. Amended 2026-08-27.** `#flashcard/sequence` exists,
> has its own note type (`Obsidian Cloze Sequence`, **one** template), a fixture, and a section in
> `README.md` describing it as *"one card, whose items reveal one at a time, on one schedule"*.
> `docs/history/NOTE-TYPES-AND-CONTEXT-DESIGN.md` argued that generating N notes was "the opposite of what is
> wanted", and the built shape agrees with it. The sentence below is left in place because its
> REASONING about what one Anki note can express is what led to the cloze-sequence design; it is
> the conclusion that was overtaken.

**Ordered** — `#flashcard/sequence` makes one note whose items reveal one at a time on one
schedule. The reasoning that got there is worth keeping: progressive disclosure in the strict sense
— each card revealing prior items and hiding the rest — cannot be expressed by one Anki note, since
a cloze note has one text and its cards differ only in which deletion is hidden. Generating N notes
was rejected, and what shipped reveals within a single note instead.

### Deliberately deferred

Not rejected — out of scope until the basic path is in daily use:

- **MOC parsing and the authored route**, and therefore new-card position pushing
- **The typed-edge GRAPH** (`kind-of`, `part-of`) and anything derived from it. A relation already
  becomes a card — see *Cards made from a relation* — but nothing builds, traverses or queries the
  graph those relations describe, and *Structure cards* below is the thing that would be derived
  from it
- **Structure cards** generated from the map
- **Automatic deletion.** Sync only ever suspends and flags; removing a flagged note is a separate explicit act, and the command for it is unbuilt — see *Deletion*
- **Automatic rename detection.** _Cut 2026-08-19, having been assumed by earlier drafts of this document._ Judged a subsystem rather than a feature: the approaches explored were note-id scoping, deterministic analysis of the vault's git history, a language model reading those diffs, similarity ranking into review buckets, Anki-side evidence, author-maintained per-heading identifiers, an explicit `relink` command, and the interaction and safety questions around confirming a match. Two findings worth keeping: the key's `(frontmatter id, heading path)` shape means candidates are confined to the **cards sharing one note id**, never a collection-wide search; and the vault's git history is the *input* to any semantic approach rather than an alternative to it, since a renamed heading is a one-line diff — a record of the edit, not an inference from two strings. Until it exists, a rename is reconciled by hand, which suspension makes lossless

### v0

The shape is a **diff of two sets**, not a lookup per card. A markdown-driven loop —
`findNotes(key) → update, else addNote` — is structurally incapable of detecting an orphan, because
an orphan is a key present in Anki and absent from markdown, which such a loop never visits. Orphan
detection is what the flag-then-suspend ruling rests on, so the shape follows from it.

```
desired  = scan the vault            → Map[CardKey, CardSpec]
observed = one query for every note this   → then batched notesInfo
           tool owns, by field or legacy tag   → Map[CardKey, ObservedNote]

diff:  in desired, not observed   → Create
       in both, content differs   → Update      (a deck move is a CHANGE inside one)
       in both, note type differs → Retype      (deferred unless asked — history may cost)
       in observed, not desired   → Flag        (never delete — see Deletion)
       previously flagged, back   → Unflag
       legacy tag, no field       → CarryIdentity
```

Two details the shape depends on:

- **Identity goes inline on `addNote`.** Creating and then writing it is two calls, and an interruption between them leaves a note nothing can find — not merely unmatched but *unenumerable*, invisible to lookup and reconciler forever.
- **`updateNoteFields` has no early-out** — it bumps `mod`/`usn` even when the text is identical. So "zero changes on re-run" has to be decided *before* the call, by carrying a `sha::<hex>` tag over a canonical serialisation of (notetype, fields), with the identity field excluded from the hash. That is free, since the bulk `notesInfo` already returns tags.

Nothing else. If a MOC parser appears before a single card has been reviewed, the scope has been lost.
