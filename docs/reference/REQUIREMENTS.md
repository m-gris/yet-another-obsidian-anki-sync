# Requirements

> **Work on this document** — `bd list --all --spec docs/reference/REQUIREMENTS.md`
>
> Closed means built, and the closing reason says what shipped; open means outstanding.
> **This document records what the tool must do and why, never progress** — a status kept in
> two places goes stale in one of them.

_What this spaced-repetition setup must do, what it must not, and what remains undecided. The reasoning behind the pedagogical claims lives in [LEARNING-MODEL.md](./LEARNING-MODEL.md)._

---

## TLDR

Author in nvim, review in Anki — a stated asymmetry that makes Anki a preference satisfied rather than a compromise.

Cards live inside the documents that explain them: a heading marked under the `#flashcard/` root becomes an Anki note, and unmarked headings generate nothing. `--help` lists every marker the tool accepts, from a table a test ties to the source.

Card identity is **derived** from text already in the note and held on the Anki side, so nothing generated is ever written into the markdown — superseding the earlier decision to accept an in-file identifier.

The key records no vault, so a collection cannot yet tell one vault's cards from another's. **Ruled 2026-09-01:** the note carries its vault's absolute path in a field, and the leaf of that path is what a run matches on — so moving the vault does not orphan the collection. The leaf also becomes a deck level, for legibility rather than for correctness.

A survey of all 92 Obsidian spaced-repetition plugins found that **none generates concept–descriptor cards**, and that Obsidian_to_Anki — the only bridge that could carry a custom note type — is dead and delisted. Hence a standalone sync tool, decided over an Obsidian plugin.

How things are filed is an option to compose, not a decision the tool makes for you; identity, history, refusing-rather-than-guessing, and what a card shows beside its prompt are not, because an option to be silently wrong is a defect with a switch on it.

Deferred, not rejected: the MOC and authored route, and new-card position pushing.

---

## Summary

Every requirement carries an evidence class — **stated**, **verified**, **unratified**, or **derived** — because an earlier round of machine-generated documents had several assertions absorbed as settled preferences without anyone confirming them. That failure recurred during this session and is annotated wherever it happened.

**Accepted.** Authoring happens in nvim; reviewing in a GUI is fine. Cards live inside the documents they come from rather than in a parallel representation. A concept–descriptor–description note type exists and works, with three retrieval directions of which two are default. New material enters sequentially and reviews interleave. Scheduling is delegated to FSRS. Files stay plain text, locally owned and version-controlled. Metadata in the notes is a cost to minimise rather than a veto — and in the event, the design needs none: identity is derived from text already present, with the binding held on the Anki side, which can carry bookkeeping the source should not.

**Composability — stated 2026-08-22.** Presentation and organisation are options the reviewer composes into a personal setup; correctness is not. Identity, review history, and refusing rather than guessing are rigid, because being wrong there costs data and an option to be silently wrong is a defect with a switch on it. Deck shape and which lens you review through are soft, because there is no single right way to organise knowledge. What a card DISPLAYS is not: it is whatever the card's own face does not already give away, which is a rule rather than a taste. Where a decision is presentational, the mechanism is exposed rather than a preference embedded — and the failure to watch for is defending a taste as though it were an invariant.

**Rejected.** Automatic mastery gating with computed thresholds — though the *need* for gating is real and experienced, and only the mechanism is thrown out. Deck hierarchy as a lock, since a deck supplies a set rather than an order. "Master the high level before seeing details" in its strong form. Obsidian-only review. Generating cards from a parallel YAML file. And org-mode, whose format is better suited but whose review tooling is Emacs-resident.

**The landscape, surveyed.** All 92 spaced-repetition plugins in Obsidian's registry were enumerated and read. None generates concept–descriptor cards. Obsidian_to_Anki, the only bridge able to carry a custom note type, was last pushed in June 2024, carries 274 open issues, and has been delisted. Yanki forces one file per card and four fixed types; Decks makes the heading itself the card front, colliding with headings used for document structure; obsidian-spaced-repetition has no learning steps and rewrites scheduling comments on every review; AOSR alone has sub-day intervals but writes tags into notes and runs a custom algorithm. RemNote has the card model natively but its data model is a rose tree, so documents do not survive it.

**Decided.** A standalone sync tool rather than an Obsidian plugin: the vault is markdown in a folder and AnkiConnect is an HTTP API, so nothing requires plugin hosting — and not hosting a plugin removes an entire class of abandonment risk, the one that killed Obsidian_to_Anki. The card model, markers, identity scheme, deck mapping, cloze and table handling are specified in [CARD-MODEL.md](./CARD-MODEL.md).

**Deletion — decided 2026-08-18.** The sync only ever flags and suspends; deleting a flagged note is a separate explicit act, and the command for it is not built. Delete-on-absence was rejected because an undetected heading rename would be indistinguishable from a deletion, silently destroying that card's scheduling history.

**One collection cannot tell one vault's cards from another's.** The derived key records no
vault, so the orphan search that sweeps a collection cannot be scoped to the vault being synced:
point the tool at a second vault while a profile already holds a first, and the first vault's whole
card set reads as deleted headings and is flagged and suspended. **Ruled 2026-09-01:** the vault
absolute path is carried in a field on each note. Its LEAF is what a run matches on, so moving the
vault keeps the match while a whole path would break it; the leaf also becomes a deck level, so
`Obsidian::<vault>::…` reads apart in the deck tree — legibility, not the discriminator, since a
filtered deck can relocate a card and the field cannot. Two vaults whose paths end in the same leaf
are refused by name. The long-form argument, and what a fix costs, is `README.md`, "ONE VAULT PER
ANKI PROFILE".

**Deferred, not rejected.** The MOC and authored route, and new-card position pushing, which rests
on an assumption still untested.

**Open.** What becomes of the superseded design documents that preceded this one. They still assert
conclusions rejected here, and the path this document gives for them no longer resolves.

---

## Full

### Provenance, and why it is marked

An earlier round of design documents, which preceded this repository and are no longer beside it, was largely machine-generated. Several of its assertions were subsequently treated as settled preferences when they had never been ratified by anyone. To prevent that recurring, every requirement below carries its evidence class:

| Mark             | Meaning                                                                     |
| ---------------- | --------------------------------------------------------------------------- |
| **[stated]**     | Said explicitly by the author                                               |
| **[verified]**   | Checked against an artifact on disk                                         |
| **[unratified]** | Asserted in the earlier generated documents; plausible, but never confirmed |
| **[derived]**    | Follows from the learning model rather than from preference                 |

Requirements marked **[unratified]** should not drive irreversible decisions until confirmed.

### Accepted

**1. Authoring happens in nvim; reviewing may happen in a GUI.** **[stated]**

This asymmetry is load-bearing. Note-taking outside nvim was a top-three reason for abandoning RemNote; reviewing in a GUI was explicitly fine. Anki is therefore not a compromise to be minimised — it satisfies a stated preference.

**2. Cards live inside the documents they come from.** **[stated]**

One artifact, not two kept in sync. Facts are written where they belong — inside the prose that explains them — rather than extracted into a parallel representation.

_Superseded 2026-08-18._ This previously read "indentation should be simultaneously structure and card context", marked **[stated]** and credited to "roughly two years of daily use". Both were wrong: the indentation framing was an assistant inference from a machine-generated document, never said by the author, and the duration figure came from the same source. The underlying preference — cards inside documents, authored in nvim — is genuine and survives. The mechanism is now headings and tables, specified in [CARD-MODEL.md](./CARD-MODEL.md).

**3. A concept–descriptor–description card shape, generating multiple retrieval paths.** **[verified]**

The note type exists and works: three card templates testing different directions of recall. Its existence constrains tool choice — see _Rejected_.

It carries more than the three named fields — gates selecting how many directions a marker asks
for, the breadcrumb, and the card's identity — which is how one note type serves every
concept-descriptor marker rather than needing a second type per direction.

**4. Sequential introduction, interleaved review.** **[stated]** **[derived]**

New material enters in dependency order, one region at a time; once introduced, it interleaves freely with everything already learned. See the learning model for why these are two separate queues.

**5. The structural map is itself learnable content.** **[stated]**

The skeleton — what sits under what — is cheap, memorizable early, and makes later facts land somewhere.

_Built._ A heading can ask for a card made from its own subheadings, revealed one at a time. It
turned out not to need the MOC at all.

**6. Scheduling is delegated to FSRS.** **[unratified]**

FSRS, trained on large review corpora, outperforms the 1980s-era SM-2 by a wide margin, and the difference compounds over years at high daily volume. Marked unratified because the specific figures quoted in the earlier documents were machine-generated and have not been independently checked; the direction of the claim is not in doubt, the magnitude is.

**7. Plain text, locally owned, version-controlled.** **[stated]**

Proprietary format was the original reason for leaving RemNote.

**8. Metadata in the notes is a cost to minimise, not a veto.** **[stated]**

Asked directly which objection to an in-file identifier he held — visual noise, foreign ownership, coupling, or fragility — the author answered: _"a bit of all of them... but i'm not stupidly stuborn... tracking has to happen somehow, somewhere."_

_Corrected 2026-08-18._ This previously read "no scheduling metadata in the notes", justified by an argument about **churn** (high-frequency machine state versus low-frequency prose). That argument was the assistant's, was marked **[unratified]** at the time, and was then repeatedly cited back as the author's own criterion — including as a filter for ranking plugins. The author's actual position is the sentence above.

**9. Single source of truth — no duplicating content into a second representation.** **[unratified]**

**10. Card identity must be tracked, and nothing generated should be written into the notes.** **[derived]**

_Superseded 2026-08-18._ This previously accepted an in-file write-once identifier, on the argument that every alternative fails and only that one fails _visibly_. That reasoning was sound given the options then known, but a better one exists.

The key is **derived** from text already present: the frontmatter `id`, plus whichever node of the
note the card hangs off — a chain of ancestor headings, a frontmatter property, one block, or the
note itself. A table card extends the heading path with the row's own first cell and the column's
header, both as text, so reordering a table changes nothing while renaming either retires that
card. The **binding** to an Anki note is held on the Anki side, in a field. Bookkeeping is
acceptable there and unacceptable in the source, because the source is what a person writes. A
local sidecar is a rebuildable cache, not an oracle.

The marker count in the markdown is therefore zero, and stays zero regardless of card count. This is only possible because the note shape is committed to — general-purpose tools cannot assume it, which is why every one of them mints and writes an identifier instead.

Full treatment, including failure modes, in [CARD-MODEL.md](./CARD-MODEL.md).

**11. Organisation is a COMPOSABLE OPTION; correctness is not — and presentation turned out to sit with correctness.** **[stated]** _Added 2026-08-22._

There are many defensible ways to organise knowledge, and this tool must not pick one and call
it the answer. The reviewer should be able to compose the features into a personal setup. Strong
conventions and contracts remain necessary — the point is not that everything is negotiable, but
that the negotiable and the non-negotiable are different sets and must not be confused.

The line runs between them like this:

| Rigid — being wrong costs data | Soft — it is how you think |
| --- | --- |
| card identity and its binding to a note | how decks are shaped |
| review history and scheduling | which lens you review through |
| refusing rather than guessing | which markers you use, and how much you mark |
| no silent success | |
| what a card shows beside its prompt | |

An option to be silently wrong is a defect with a switch on it, so nothing in the left column
becomes configurable. Everything in the right column is a mechanism to be exposed rather than a
preference to be embedded.

WHY THIS IS WRITTEN DOWN RATHER THAN ASSUMED: it was got wrong in the other direction on
2026-08-22. Deck shape had been settled as "decks mirror folder paths, filing only", and a
request to derive decks from the HEADING path instead was argued down as though it violated a
contract. It does not — it is a second strategy for deriving a deck, and which one suits a
person is theirs to decide. The two also COMPOSE with the on-card breadcrumb rather than
competing with it: the deck decides what can be studied in isolation, the breadcrumb decides
what can be read while answering, and either may be wanted without the other.

The general failure to watch for is defending a taste as though it were an invariant — and its
mirror, made here on 2026-08-23. What a card DISPLAYS sat in the soft column until a configuration
layer for it was built and then deleted: what a breadcrumb may show is not a preference, it is
everything the card's own face does not already give away, which differs per card shape rather than
per person. The line moves in both directions.

### Rejected

**Automatic mastery gating with computed thresholds.** The earlier design specified suspended child decks, monitored parent-deck maturity, and automatic unsuspension — and left "what counts as mastered" open with five candidate metrics and no answer. The horizon rule in the learning model replaces all of it, and needs no add-on.

Note what is _not_ rejected: **the need for gating is real and experienced**, not inferred. It was raised with RemNote's makers and answered with a non-answer. Only the elaborate mechanism is rejected.

**Deck hierarchy as a lock.** A deck tree does not supply an order — it supplies a _set_. Nesting is useful for boundary and aggregation, not for enforcing sequence. See _Architecture_.

**"Master the high level before seeing lower-level details," in its strong form.** Memorizing the map first is cheap and supported. Gating content level-by-level is a different claim and runs into composition — see the learning model's treatment of product types.

**Obsidian-only review.** **[verified]** Reviews happen on both desktop and phone, and Anki's mobile clients are materially better than reviewing in Obsidian. More decisively, the full plugin survey below found that **no Obsidian plugin generates concept–descriptor cards**, which is the requirement that drove the whole search.

_Upgraded from [unratified] on 2026-08-18._ The previous entry repeated two claims from the machine-generated documents; both are now superseded by a direct survey of the registry.

**Generating cards from a parallel YAML file.** Requires duplicating content that already exists in the notes, defeating single-source-of-truth.

**org-mode.** The format is genuinely better suited — per-headline properties rather than per-file, real subtree operations, and `org-id` as native infrastructure rather than foreign bookkeeping. But org-drill is Emacs-resident, so `orgmode.nvim` gets the better outline format and _loses_ the integration that motivated the move. It also fails the phone-review constraint outright.

Three ideas are worth stealing from it regardless: per-node typed properties, subtree move-and-fold operations, and the framing of identifiers as infrastructure.

### Landscape, surveyed 2026-08-18 **[verified]**

All 92 flashcard/spaced-repetition plugins in Obsidian's `community-plugins.json` were enumerated, with repository metadata and READMEs read. Findings that bear on the decision:

**Nothing generates concept–descriptor cards.** Not one of the 92, and not any Anki bridge among them. Checked by reading, not inferred. Four apparent hits (`decks`, `spaceforge`, `the-queue`, `syro`) were keyword false positives — "Core Concepts", "Key Concepts" — and Decks' `MULTIWAY` flag refers to preserving *imported* Anki templates, not authoring.

**Obsidian_to_Anki is dead.** Last pushed **2024-06-16**, 274 open issues, and **absent from the community registry** — delisted, so it cannot be installed from the plugin browser. It was the only bridge that could carry a custom note type. This is the tooling gap the custom sync exists to fill; the *capability* was never missing from Anki.

**The healthy alternatives each fail structurally, not incidentally:**

| Tool | Why not |
|---|---|
| **Yanki** (12.6k dl, active) | one Obsidian note = one Anki note; four fixed note types, no custom types; `noteId` written to frontmatter |
| **Decks** (9k dl, active) | heading *is* the card front, which collides with headings used for document structure |
| **obsidian-spaced-repetition** (580k dl) | no learning steps — `learning_steps` is hardcoded to `0` in its FSRS wiring, and requested since 2022 (#388, #794, #1397, all open); `<!--SR:-->` rewritten every review |
| **AOSR** (15.8k dl) | the only native plugin with sub-day intervals, but writes ` #AOSR/<hash>` **tags** into notes, uses a custom algorithm, and was last pushed 2025-11 |
| **RemNote (wholesale)** | authoring — its data model is a rose tree, so headings flatten to siblings and tables are silently dropped. Measured, not assumed. |
| **RemNote (via MCP bridge)** | works — verified live this session, creating native concept/descriptor cards from markdown — but two `0.x` components that must version-match, one maintainer, three processes that must all be running |
| **Mochi** | genuine REST API with update-by-ID; three-way reachable only via hidden-text groups; cloud-only and paid |

### Architecture that follows

> **Scope note.** The authored route, new-card position pushing, and THE GRAPH ITSELF are still
> deferred — nothing traverses or queries relations, and the audit-the-route-by-query described
> below does not exist. What shipped 2026-08-26 is the edge SCHEMA, which turns a declared relation
> into a card; that is a card-shaped edge, not a graph. Decks carry
> filing only, and which parts of a card's location become deck levels is now a choice the
> reviewer composes rather than a fixed rule; study scope comes from tags rather than the deck
> tree. The rest is recorded because the reasoning is sound and will be wanted later.

**Three orthogonal layers over one set of atomic notes.** Not competing implementations — different projections answering different questions:

| Layer                 | Shape                | Answers                                                |
| --------------------- | -------------------- | ------------------------------------------------------ |
| Tags                  | unary classification | _what kind of thing is this?_ (tier, stratum, status)  |
| Typed link properties | binary typed edges   | _how do concepts relate?_ — the domain graph, emergent |
| An authored route     | an ordered sequence  | _what do I study next?_                                |

Tags cannot do structural work: a unary tag loses the target of the relation. Conversely, orthogonal facets such as tier or stratum are naturally tag-shaped and were repeatedly lost when forced into the structural hierarchy.

Neither the graph nor the route is authoritative over the other. A route that departs from the domain graph is a pedagogical choice, not an error. The graph can nonetheless _audit_ the route by query — concepts the route never visits, route steps whose prerequisites are scheduled later. **Detection, not enforcement:** Obsidian can encode typed edges but cannot check exhaustiveness or arity.

**Three independent levers in Anki**, which must not be conflated:

| Lever               | Provides                                                      |
| ------------------- | ------------------------------------------------------------- |
| A deck              | **boundary** — which cards are in scope                       |
| New-card sort order | **sequence** — the order of introduction within that scope    |
| The parent deck     | **widened scope** — aggregation for interleaved consolidation |

"Learn a region linearly, consolidate it randomly" requires all three. Scopes that cut across the deck tree — anything tag-shaped — are served by query-defined filtered decks instead.

**The route feeds the sort order, not deck assignment.** This is much lighter than injecting a target deck per note: decks stay coarse, and the route supplies sequence within them.

### Prior art

AnkiMorphs and MorphMan, built for language learning, already implement this architecture with morphemes as the atoms. They score each _new_ card by how many of its components are not yet known, sort ascending so that a card is met only when all but one component is familiar, and **explicitly do not touch the scheduling of cards already learned**. That is the linear-introduction / interleaved-review split, in production.

Requests for a general, subject-agnostic version recur on the Anki forums and have not been fulfilled. The blocker they identify is consistent: there is nowhere in Anki to record relationships between cards belonging to different notes.

**That blocker does not apply here**, because the graph lives upstream in the vault. Anki never needs to understand dependencies — it needs to be told a position number.

### Constraints on any implementation

- nvim-native authoring.
- Reviews happen on both desktop and phone.
- Fail fast, loud and clear; no defensive handling that hides a problem.
- Detection over enforcement, since the encoding layer cannot type-check itself.
- A card must say which vault it came from. _Ruled 2026-09-01._ The key records no vault, so the
  orphan search cannot be scoped to the vault being synced, and syncing a second vault into a
  collection flags and suspends the first vault's whole card set. Each note carries its vault's
  absolute path in a field, and the LEAF of that path is what a run matches on — a moved vault
  keeps its leaf, where matching on the whole path would read every card as foreign after one
  `mv`. The leaf also becomes a deck level, for legibility; the field, not the deck, is what
  answers whether a note belongs to the vault being synced. Two vaults whose paths end in the same
  leaf are refused by name.

  _Reverses part of a design settled 2026-08-30, which stored the leaf and recorded the path beside
  it. Storing both writes one fact twice. Deriving the leaf keeps what that design was protecting —
  a vault that is MOVED keeps its leaf, so the match survives — while leaving the path available to
  say where notes came from. Renaming the vault directory defeats both designs equally._

### Unverified assumptions

**The position-number mechanism.** The architecture above rests on the claim that pushing a position to Anki causes new-card introduction to follow the authored route. This has _not_ been tested. It is cheap to test against the existing vault, and it should be tested before anything is built on it — if new-card gathering and sorting do not behave as assumed, the route-feeds-sequence design changes.

### Decisions

1. **The edge schema** — built 2026-08-26, and it arrived through a door nobody planned.

   It was deferred with the typed-edge graph on the grounds that neither was needed for v0. What
   revived it was not the graph: it was the observation that **a typed edge and a
   concept-descriptor card are the same shape**. `Function Space` / `special-case-of` / `HomSet`
   is subject-predicate-object, and a concept-descriptor card is three fields asked in one, two or
   three directions. So an edge needs no note type of its own and nothing downstream of
   `CardSpec`. The mechanism is in [CARD-MODEL.md](./CARD-MODEL.md).

   **The vocabulary lives in the vault, and that is a ruling rather than a convenience.** Heading
   markers are universal — every vault that wants a two-way card writes the same token — so their
   vocabulary is this tool's business and belongs in its source. Edge kinds are not:
   `special-case-of` and `dual-of` are mathematics, another vault wants other words, and putting
   them in Scala would make one person's domain vocabulary a matter for this repository's release
   cycle. A vocabulary is a dictionary, and the dictionary belongs to whoever owns the words.

2. **Disposition of the superseded design documents that preceded this one.** They still assert
   conclusions rejected here. Still open, and the path this document gave for them no longer
   resolves.
