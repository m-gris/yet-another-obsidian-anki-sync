# Requirements

_What this spaced-repetition setup must do, what it must not, and what remains undecided. The reasoning behind the pedagogical claims lives in [LEARNING-MODEL.md](./LEARNING-MODEL.md)._

---

## TLDR

Author in nvim, review in Anki — a stated asymmetry that makes Anki a preference satisfied rather than a compromise.

Cards live inside the documents that explain them: a heading marked under the `#flashcard/` root — `1way`, `2way`, `3way`, `3way/all`, `cloze` or `table` — becomes an Anki note, and unmarked headings generate nothing.

Card identity is **derived** from `(frontmatter id, heading path)` and stored as a tag inside Anki, so nothing generated is ever written into the markdown — superseding the earlier decision to accept an in-file identifier.

⚠️ That key has no room for a vault, so **one vault per Anki profile** — and nothing enforces it. Syncing a second vault into a collection flags and suspends the first vault's whole card set.

A survey of all 92 Obsidian spaced-repetition plugins found that **none generates concept–descriptor cards**, and that Obsidian_to_Anki — the only bridge that could carry a custom note type — is dead and delisted. Hence a standalone sync tool, decided over an Obsidian plugin.

How things look and how they are filed are options to compose, not decisions the tool makes for you; identity, history and refusing-rather-than-guessing are not, because an option to be silently wrong is a defect with a switch on it.

Deferred, not rejected: the MOC and authored route, new-card position pushing, the typed-edge graph, structure cards, and ordered-list disclosure.

---

## Summary

Every requirement carries an evidence class — **stated**, **verified**, **unratified**, or **derived** — because an earlier round of machine-generated documents had several assertions absorbed as settled preferences without anyone confirming them. That failure recurred during this session and is annotated wherever it happened.

**Accepted.** Authoring happens in nvim; reviewing in a GUI is fine. Cards live inside the documents they come from rather than in a parallel representation. A concept–descriptor–description note type exists and works, with three retrieval directions of which two are default. New material enters sequentially and reviews interleave. Scheduling is delegated to FSRS. Files stay plain text, locally owned and version-controlled. Metadata in the notes is a cost to minimise rather than a veto — and in the event, the design needs none: identity is derived from text already present, with the binding stored in Anki, which is a derived artifact and can carry bookkeeping the source should not.

**Composability — stated 2026-08-22.** Presentation and organisation are options the reviewer composes into a personal setup; correctness is not. Identity, review history, and refusing rather than guessing are rigid, because being wrong there costs data and an option to be silently wrong is a defect with a switch on it. Deck shape, what appears on a card, and which lens you review through are soft, because there is no single right way to organise knowledge. Where a decision is presentational, the mechanism is exposed rather than a preference embedded — and the failure to watch for is defending a taste as though it were an invariant.

**Rejected.** Automatic mastery gating with computed thresholds — though the *need* for gating is real and experienced, and only the mechanism is thrown out. Deck hierarchy as a lock, since a deck supplies a set rather than an order. "Master the high level before seeing details" in its strong form. Obsidian-only review. Generating cards from a parallel YAML file. And org-mode, whose format is better suited but whose review tooling is Emacs-resident.

**The landscape, surveyed.** All 92 spaced-repetition plugins in Obsidian's registry were enumerated and read. None generates concept–descriptor cards. Obsidian_to_Anki, the only bridge able to carry a custom note type, was last pushed in June 2024, carries 274 open issues, and has been delisted. Yanki forces one file per card and four fixed types; Decks makes the heading itself the card front, colliding with headings used for document structure; obsidian-spaced-repetition has no learning steps and rewrites scheduling comments on every review; AOSR alone has sub-day intervals but writes tags into notes and runs a custom algorithm. RemNote has the card model natively but its data model is a rose tree, so documents do not survive it.

**Decided.** A standalone sync tool rather than an Obsidian plugin. The card model, markers, identity scheme, deck mapping, cloze and table handling are specified in [CARD-MODEL.md](./CARD-MODEL.md).

**Deletion — decided 2026-08-18.** The sync only ever flags; a separate explicit `prune` command deletes flagged notes after the list has been reviewed. Delete-on-absence was rejected because an undetected heading rename would be indistinguishable from a deletion, silently destroying that card's scheduling history.

**⚠️ One vault per Anki profile — recorded 2026-08-27, and nothing enforces it.** The derived key
records no vault, and there is nowhere else it could be recorded either, so the orphan search that
sweeps the collection cannot be scoped to the vault being synced. Point the tool at a second vault
while a profile already holds a first one and every card the first vault owns is flagged **and
suspended**: recoverable, shown in the plan before anything is written, and still the worst thing
this tool can do to a review history. It is a consequence of the identity scheme rather than a bug
beside it, so it sits under _Constraints on any implementation_ below rather than under _Open_. The
argument, and the cost of a fix, is `README.md`, "ONE VAULT PER ANKI PROFILE".

_**Marker vocabulary note, added 2026-08-27.** This document nowhere names `cdd`, which since
2026-08-24 is how a concept-descriptor card is marked — `#flashcard/cdd/{1,2,3}way`, replacing
`3way` and `3way/all`, which survive as aliases. Nor does it name `sequence`. The authoritative
list is `model/Marker.scala`'s `Documented` table, which `--help` prints and which a test ties to
the source; `docs/reference/CARD-MODEL.md` carries a rendered copy. Nothing in this document's REASONING
depends on the spelling, which is why only this note is added rather than the text being rewritten._

**Deferred, not rejected.** The MOC and authored route; new-card position pushing, which rests on an assumption still untested; the typed-edge graph and its schema; structure cards; ordered-list progressive disclosure; deletion handling beyond flagging.

**Open.** What becomes of the superseded documents in `../poc-obsidian-vault/`. _(Resolved 2026-08-18: the `::` family is **not** used at all — every card kind, including plain one- and two-way cards, is marked by a heading tag, so no delimiter is in play.)_

---

## Full

### Provenance, and why it is marked

An earlier round of design documents in `../poc-obsidian-vault/` was largely machine-generated. Several of its assertions were subsequently treated as settled preferences when they had never been ratified by anyone. To prevent that recurring, every requirement below carries its evidence class:

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

The note type exists and works: three fields, three card templates testing different directions of recall. Its existence constrains tool choice — see _Rejected_.

_**No longer true — verified 2026-08-27** against the live `Obsidian Concept-Descriptor`: every
front blanks its own answer and every back shows the full triple. This described the hand-made note
type, not the ones installed from `resources/note-types/`. Original text follows._

_Known defect:_ all three templates currently render the answer field on both sides of the divider, so the prompt is not visible on the answer side. Idiomatic form places the question side above the divider and the answer below.

**4. Sequential introduction, interleaved review.** **[stated]** **[derived]**

New material enters in dependency order, one region at a time; once introduced, it interleaves freely with everything already learned. See the learning model for why these are two separate queues.

**5. The structural map is itself learnable content.** **[stated]**

The skeleton — what sits under what — is cheap, memorizable early, and makes later facts land somewhere.

_Deferred._ Nothing implements it, and nothing will until the basic card path is in daily use. It depends on the MOC, which is itself deferred.

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

The key is **derived** from text already present — `(frontmatter id, heading path)` — the ancestor chain, since a bare heading name is not unique within a file — extended with row and column for tables — and the **binding** to an Anki note is stored in Anki as a `src::` tag. Anki is a derived artifact, so bookkeeping there costs nothing; that is precisely what makes it unacceptable in the source. A local sidecar is a rebuildable cache, not an oracle.

The marker count in the markdown is therefore zero, and stays zero regardless of card count. This is only possible because the note shape is committed to — general-purpose tools cannot assume it, which is why every one of them mints and writes an identifier instead.

Full treatment, including failure modes, in [CARD-MODEL.md](./CARD-MODEL.md).

**11. Presentation and organisation are COMPOSABLE OPTIONS; correctness is not.** **[stated]** _Added 2026-08-22._

There are many defensible ways to organise knowledge, and this tool must not pick one and call
it the answer. The reviewer should be able to compose the features into a personal setup. Strong
conventions and contracts remain necessary — the point is not that everything is negotiable, but
that the negotiable and the non-negotiable are different sets and must not be confused.

The line runs between them like this:

| Rigid — being wrong costs data | Soft — it is how you think |
| --- | --- |
| card identity and its binding to a note | how decks are shaped |
| review history and scheduling | what appears on a card, and where |
| refusing rather than guessing | which lens you review through |
| no silent success | which markers you use, and how much you mark |

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

The general failure to watch for is defending a taste as though it were an invariant. When a
decision is presentational or organisational, build the mechanism and expose the choice.

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

> **Scope note, 2026-08-18.** Most of this section is **deferred**. The typed-edge graph, the authored route, and new-card position pushing are not in v0 and are not needed for a working card pipeline. What survives into v0 is narrow: decks mirror folder paths and carry filing only, and study scope comes from tags rather than the deck tree. The rest is recorded because the reasoning is sound and will be wanted later — not because it is being built.

**Three orthogonal layers over one set of atomic notes.** Not competing implementations — different projections answering different questions:

| Layer                 | Shape                | Answers                                                |
| --------------------- | -------------------- | ------------------------------------------------------ |
| Tags                  | unary classification | _what kind of thing is this?_ (tier, stratum, status)  |
| Typed link properties | binary typed edges   | _how do concepts relate?_ — the domain graph, emergent |
| An authored route     | an ordered sequence  | _what do I study next?_                                |

Tags cannot do structural work: a unary tag loses the target of the relation. Conversely, orthogonal facets such as tier or stratum are naturally tag-shaped and were repeatedly lost when forced into the structural hierarchy.

Neither the graph nor the route is authoritative over the other. A route that departs from the domain graph is a pedagogical choice, not an error. The graph can nonetheless _audit_ the route by query — concepts the route never visits, route steps whose prerequisites are scheduled later. **Detection, not enforcement:** Obsidian can encode typed edges but cannot check exhaustiveness or arity.

⚠️ **Delimiter collision — _resolved 2026-08-18, no longer applies._** Dataview inline fields — the natural per-block mechanism for typed properties — use `::`, the same delimiter as the card syntax. `kind-of:: [[Consistency]]` becomes a flashcard the moment a two-way card regex is configured. The edge schema must resolve this.

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
- ⚠️ **ONE VAULT PER ANKI PROFILE, and nothing enforces it.** _Added 2026-08-27._ The identity
  scheme of item 10 above derives a key from `(frontmatter id, heading path)` and stops there — it
  has no room for a vault, and the deck root is a command-line flag rather than a property of the
  collection. So the reconciler's orphan search, which must enumerate every note carrying a `src::`
  tag in the open collection, cannot be scoped to one vault: there is nothing to scope it by. Sync
  a second vault into a collection that already holds one and the first vault's entire card set
  reads as deleted headings, so it is flagged **and suspended** — recoverable, and visible in the
  printed plan beforehand, but the most destructive thing this tool can do. It falls out of the
  identity scheme rather than being a separable defect, which is why it is recorded here as a
  constraint. The argument in full, and what a fix would cost, is `README.md`, "ONE VAULT PER ANKI
  PROFILE".

### Unverified assumptions

**The position-number mechanism.** The architecture above rests on the claim that pushing a position to Anki causes new-card introduction to follow the authored route. This has _not_ been tested. It is cheap to test against the existing vault, and it should be tested before anything is built on it — if new-card gathering and sorting do not behave as assumed, the route-feeds-sequence design changes.

### Open decisions

1. ~~**CLI or Obsidian plugin?**~~ **Decided 2026-08-18: a standalone tool, not a plugin.** Authoring happens in nvim and reviewing in Anki, so Obsidian is infrastructure rather than an interface anyone lives in. The vault is markdown in a folder and AnkiConnect is an HTTP API; nothing requires plugin hosting. This also removes an entire class of plugin-abandonment risk — the one that just killed Obsidian_to_Anki.

2. **The edge schema** — **PARTLY BUILT, 2026-08-25**, and it arrived through a door nobody planned.

   It was deferred with the typed-edge graph on the grounds that neither was needed for v0. What
   revived it was not the graph: it was the observation that **a typed edge and a
   concept-descriptor card are the same shape**. `Function Space` / `special-case-of` / `HomSet`
   is subject-predicate-object, and a concept-descriptor card is three fields asked in one, two or
   three directions. So an edge needs no note type of its own, no new card shape, and nothing
   downstream of `CardSpec` — it inherits identity, hashing, decks, breadcrumbs, orphan handling
   and the whole reconciler unchanged.

   **BUILT AND WIRED 2026-08-26.** `extract/EdgeSchema.scala` reads the declarations a note makes
   for ITSELF, under a `# Properties-to-Flashcards` heading in its own body, and
   `extract/Edges.scala` turns each declared property into a `ThreeField` card. Verified against
   the live vault.

   **Scope is per note, not per vault** — a relation is worth carrying in frontmatter for querying
   and the graph whether or not you want to be drilled on it, and those are separate decisions.
   **The right-hand side is marker syntax**, read by `Marker.parse`, so a rule and a heading share
   one vocabulary rather than two that can drift.

   **It lives in the vault, and that is a ruling rather than a convenience.** Heading markers are
   universal — every vault that wants a two-way card writes the same token — so their vocabulary
   is this tool's business and belongs in its source. Edge kinds are not: `special-case-of` and
   `dual-of` are mathematics, another vault wants other words, and putting them in Scala would make
   one person's domain vocabulary a matter for this repository's release cycle. This is a different
   thing from the configuration that was tried and removed, which decided PRESENTATION and was
   rightly ruled a rule rather than a setting. A vocabulary is a dictionary, and the dictionary
   belongs to whoever owns the words.

   The three questions this entry left open are answered, all three in favour of the option that
   fails on a real condition rather than on a guess. The SUBJECT is the file name, carried as a
   field rather than in the key, so renaming a note costs nothing. A wikilink's brackets come off
   and an alias wins, because a card face is read rather than clicked. And `2way` is neither
   trusted nor forbidden but CHECKED: the tool holds the whole vault, so it can see whether the
   reverse question actually has several answers, and refuses only then.

3. ~~**What defines a block's card set**~~ — **deferred** with the route and linearization.

4. **Disposition of `../poc-obsidian-vault/`**, which still asserts conclusions rejected here. Still open.

5. ~~**Which delimiter, if any, for plain two-way cards and cloze alongside the heading form.**~~ **Resolved 2026-08-18: none.** Every card kind — including plain one- and two-way cards — is marked by a heading tag under `#flashcard/`, so the `::` family is not used anywhere and no delimiter competes with Dataview inline fields.
