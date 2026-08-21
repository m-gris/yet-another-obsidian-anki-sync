# Fixture manifest

This file is the declared intent of the test corpus: for every fixture note, which
specific case it exists to prove. It is the defence against the failure mode where a
fixture that quietly carries the only instance of a case gets deleted later as
redundant-looking duplication.

## The two trees

**`dummy-vault/`** is a single, coherent vault, and it is where the *positive* structural
cases live: nested folders, nested headings, unmarked headings, every marker variant,
Obsidian body syntax.

⚠️ **It is NOT wholly a happy path, and this paragraph used to claim it was.** _Amended
2026-08-21, from a run of `inspect --vault-path dummy-vault` rather than from memory._ Two
of its twelve notes are deliberate traps that a well-meaning tidy-up would silently retire,
so the vault as it stands **cannot be synced at all** — the run reports 12 files, 55 cards,
**2 expected failures** and **3 deliberate duplicate keys**, and exits 2:

- `Patterns/Shallow-Nesting.md` — its two-space list indentation IS the fixture. Re-indent
  it to four spaces and the refusal stops firing, this file's expected failure disappears,
  and the test that counts the vault's failures goes green while proving nothing.
- `Patterns/Table-Edge-Cases.md` — its repeated `Retry` row mints two cards with **the same
  identity**, on purpose. Duplicate identities are fatal by design, so nothing is planned
  and nothing is written. To exercise the write path, copy the vault WITHOUT this file
  rather than editing it.

Neither is a bug to fix. Both are named again in the table below and in the row-level notes
there, because this is exactly the kind of file someone deletes for looking broken.

**`hostile-vaults/`** is not a vault. It is a directory of self-contained mini-vaults,
one per case, each of which is a **regression test against silent corruption**. They are
not examples of good authoring, and nothing in them should be copied into a real vault.
Each hostile note states in its own prose that it is a fixture and what the correct
behaviour is, so that a reader who opens the file without this manifest is not misled.

They live *outside* `dummy-vault/` for a structural reason, not a tidiness one: a vault
containing two notes with the same `id:` is *supposed* to be rejected. If it sat inside
the good vault, the good vault could never sync, and the happy-path test would be
permanently red. Each hostile case therefore needs its own root so it can be pointed at
the extractor in isolation — hence one directory per case, and hence two files (not one)
in the cases that need a *pair* of notes to be hostile at all.

Two kinds of hostile expectation appear below, and they are not the same thing:

- **Abort loudly.** The input is genuinely unusable — a duplicate identity key, a missing
  `id:`, a heading path that is not injective. The run must stop and name the offending
  paths. The sin being guarded against is proceeding and overwriting cards.
- **Accept correctly.** The input is legal but is the shape that a *wrong* parser
  mangles without complaining — an empty YAML value, a one-item list, an unquoted
  date-like scalar, an empty body. The run must succeed with the right parse. The sin
  being guarded against is a plausible-looking wrong value reaching the card key.

Both are regressions against silence. Only the first is an error.

## Card model, in brief

A heading opts in by carrying a marker: `#flashcard/1way`, `/2way`, `/3way`, `/3way/all`,
`/cloze`, `/table`, `/sequence`. (`/sequence` was added after this list was first written;
the seven cases are `Marker.fromToken` in `model/Marker.scala`, read 2026-08-21.) Unmarked
headings generate nothing but still contribute to the path.
A card's identity key is `(frontmatter id, heading path)`, where the heading path is the
chain of ancestor headings joined with `/`. Decks mirror the **folder** path; the file is
not a deck level.

## `dummy-vault/` — the positive corpus, two deliberate failures included

Expected decks: `Anatomy`, `Patterns`, `Patterns::Nested::Deep`, `System-Design` — confirmed
by an `inspect` run on 2026-08-21, which also reports the per-deck counts 14 / 21 / 2 / 18.

All twelve notes are listed. _Three were missing from this table until 2026-08-21:
`Anatomy/Body-Shapes.md`, `Anatomy/Sequences.md` and `Patterns/Shallow-Nesting.md`._

| File | `id:` | Markers | The case it exists to prove |
| --- | --- | --- | --- |
| `Anatomy/Body-Shapes.md` | `fix-body-shapes` | 2 × `2way`, 3 × `cloze`, 1 × `table` | **The body-CONTENT corpus: one section per construct a card body may hold** — a bullet list, a fenced code block, a table, a plain-prose cloze, a labelled-group cloze, and a cloze whose body is several blocks. Its own prose gives the reason: until 2026-08-20 four of those reached Anki as *nothing at all*, with the card created and looking correct, so the note exists to make that visible in the fixture and not only in a unit test. Two further sole instances live here. (a) The card that motivated the `Context` field — `## Cranial bones and their sutures` yields `Frontal` / `Anterior border`, which on the concept-descriptor note type's second template is the whole question and cannot be answered without knowing whether "Frontal" is a bone, a lobe or a cortex; the worked example is at `extract/CardContext.scala`. (b) `## Bones of the hand, in two parts` is the only cloze section in the corpus whose body is **more than one block**, so it is the only fixture that reaches the separator joining one block to the next. |
| `Anatomy/Bones.md` | `fix-bones` | 2 × `cloze` | The first of the two notes outside the `System-Design` / `Patterns` trees. Proves (a) several `==…==` deletions inside one card body, (b) a second top-level folder becoming a second deck root. Its subject matter is deliberately not distributed systems: the tool must not be coupled to one domain. ⚠️ Its own opening prose still calls it "the good vault's only cloze note", which stopped being true when `Body-Shapes.md` was added; the file itself was not edited here. |
| `Anatomy/Sequences.md` | `fix-sequences` | 1 × `sequence` | The **only `#flashcard/sequence` note in the whole corpus** — one card whose list items are revealed one at a time, on one schedule. It also demonstrates the shape that *works*, and the demonstration is the point: everything in the body that is not a list item is printed on the QUESTION side, so a lead-in line is a gift and a sentence written after the list is a spoiler. That inversion is not refused by the tool, only documented (`model/Marker.scala`, `case Sequence`), which is why a fixture has to carry it. |
| `Patterns/Messaging.md` | `fix-messaging` | 1 × `table` | Heading text containing the path-join character: `## Cost / benefit`. Probes whether `/` inside a segment is escaped before the heading path is joined. Also the only **well-formed** table card — three columns, one concept plus two descriptors — against which `Table-Edge-Cases.md` is the degenerate contrast. The `/` must not be tidied away. This one section is nine cards, which makes it the file that shows the blast radius of open item 1 in `HANDOFF.md`: one refusal inside a `/table` section orphans every card that table produced. |
| `Patterns/Nested/Deep/Quorums.md` | `fix-quorums` | 2 × `1way` | The **only deep-folder note**. Proves the deck mirrors the full folder chain (`Patterns::Nested::Deep`) and that the file name does *not* become a deck level. |
| `Patterns/Shallow-Nesting.md` | `fix-shallow-nesting` | 1 × `1way` | ⚠️ **A DELIBERATE FAILURE. DO NOT RE-INDENT IT.** Its nested list items are indented two spaces, which CommonMark and Obsidian read as nesting and this tool's parser reads as the start of a NEW list — so the card would say something the note does not. The note exists to exercise the refusal END TO END: a real file on disk, through the scan, to a failure carrying the file's own line numbers (`inspect` names lines 26 and 28 of this file). Re-indenting to four spaces makes the check stop firing, removes this file's expected failure, and turns the test that counts the vault's failures green while proving nothing. Nothing about the input is exotic — Prettier, web clippers and any editor configured for two-space indentation all produce it. |
| `Patterns/Table-Edge-Cases.md` | `fix-table-edges` | 3 × `table` | Degenerate but legal tables, one per section, each isolating one variable: a concept column with no descriptor columns; two rows sharing a concept; exactly one descriptor column. ⚠️ **THE SECOND AND THIRD OF THOSE ARE TRAPS, NOT OVERSIGHTS.** The `## Concept column only` section is one of the vault's two expected failures — `inspect` reports "has a concept column but no descriptor columns, so it yields no cards". The repeated `Retry` row mints **three duplicate card keys** (the row card, and both of its pair cards), which is fatal by design and is why `sync` cannot write `dummy-vault` at all. Do not deduplicate the rows and do not add a descriptor column: copy the vault without this file instead. |
| `System-Design/Consistency.md` | `fix-consistency` | 4 × `3way` | The **deepest heading nesting** in the corpus: two `####` facets under `## Session guarantees` → `### Monotonic reads`. Proves a heading path can be assembled through ancestors that are themselves unmarked and generate nothing. |
| `System-Design/Coupling.md` | `fix-coupling` | 2 × `2way`, 2 × `1way` | Two marker variants coexisting in one note, plus a trailing **unmarked `## Connascence`** heading that must produce no card despite sitting among marked siblings. |
| `System-Design/Linearizability.md` | `fix-linearizability` | 2 × `3way`, **1 × `3way/all`** | The **only `3way/all` heading in the corpus**. Proves the all-directions variant is recognised as distinct from plain `3way` — and, being adjacent to plain `3way` siblings, that the marker parser does not prefix-match one onto the other. Also carries an unmarked `## Notes`. |
| `System-Design/Multi-Topic.md` | `fix-multi-topic` | 4 × `3way` | **Heading TEXT key vs heading PATH key.** Two unmarked topic headings each own a `### Definition` and a `### Failure mode`. Under a text key the second of each pair silently overwrites the first and two cards vanish with no error; under a path key all four survive. Also proves Concept comes from the nearest ancestor heading, so one file feeds two concepts. |
| `System-Design/Replication.md` | `fix-replication` | 2 × `1way`, 1 × `2way` | The **only note with rich body syntax inside card answers**: plain `[[wikilinks]]`, an aliased `[[CAP Theorem\|CAP]]`, a heading-anchored `[[Consistency Models#Linearizability]]`, a nested bullet list, inline code and a fenced SQL block. Proves such content survives conversion rather than being flattened, dropped, or mistaken for a marker. |

## `hostile-vaults/` — must fail loudly, or parse correctly and never silently

| File | `id:` | Markers | The case it exists to prove | Expected |
| --- | --- | --- | --- | --- |
| `corrupt-frontmatter/empty-value.md` | `fix-empty-value` | 1 × `cloze` | `author:` with an empty value — what a web clipper leaves when a page has no byline. A HOCON parser fails outright on it. | Accept: YAML reads the empty value, run continues. |
| `corrupt-frontmatter/single-item-list.md` | `fix-single-item` | 1 × `2way` | `aliases:` holding a **one-item** YAML block sequence. A HOCON parser silently reads the string `- Some Alias` instead of a list. | Accept: a real one-element list. |
| `corrupt-frontmatter/unquoted-date-id.md` | `2026-08-18` | 1 × `1way` | An unquoted date-like scalar **as the id**. A HOCON parser silently yields `202608-18`, losing a hyphen from the primary key — corruption of the identity key itself, with no error. | Accept: verbatim `2026-08-18`. |
| `duplicate-ids/note-a.md` | `dup-idempotence` | 2 × `3way` | Two notes declaring the same `id:` **and** the same heading texts, so every card key `(id, heading path)` produced by one is also produced by the other. Needs both files to exist to be hostile at all. | Abort, naming both paths, before planning. |
| `duplicate-ids/note-b.md` | `dup-idempotence` | 2 × `3way` | The realistic origin of the collision: copied from `note-a.md`, re-pointed at message consumers, `id:` never changed. Card bodies differ, so an overwrite loses real content. | Abort, naming both paths. |
| `empty-concept/no-h1-no-headings.md` | `fix-no-h1-no-headings` | none | Id present, real body prose, **no H1 and no headings at any level**. Isolates the "concept = H1 or filename" fallback: concept must fall back to the basename. | Zero cards, no error. |
| `empty-file/empty.md` | — (none) | none | A genuinely **zero-byte** file with an ordinary, reachable basename. Isolates "the document is empty" from every other variable. | No crash, zero cards. |
| `empty-file/frontmatter-only.md` | `fix-frontmatter-only` | none | Valid frontmatter parsing to exactly one entry, `id`, and **nothing after the closing delimiter**. Identity present, nothing marked. Its explanation lives in YAML *comments* because body prose would destroy the variable under test and an extra key would change the parsed mapping. | Zero cards, and that is a normal result, not an error. |
| `missing-id/frontmatter-without-id.md` | — (absent) | 1 × `1way`, 1 × `cloze` | Frontmatter that parses cleanly but **omits `id:`**, while marked headings below demand a stable key. | Error naming the missing `id:` — never a silent skip. |
| `missing-id/no-frontmatter.md` | — (no frontmatter block) | 1 × `1way`, 1 × `2way` | **No frontmatter at all**, marked headings present. Distinct from the above: the failure is a missing block, not a missing key, and the two can be handled by different code paths. | Error naming the missing `id:` — never a silent skip. |
| `path-separator/slash-in-heading.md` | `hostile-slash` | 2 × `1way` | The **join is not injective**. One heading whose own text contains `/`, and a two-level nesting whose ancestors are joined with `/`, both produce `Slash in headings/Backpressure/Load shedding`. The two cards carry different content, so the overwrite is observable. | Keep them distinct (escape `/` within a segment, or key on the segment list) and report an unescaped join as a collision. |

## Coverage

**Marker variants in `dummy-vault/`.** `1way` (7), `2way` (5), `3way` (10), `3way/all` (1),
`cloze` (5), `table` (5), `sequence` (1). _Re-counted 2026-08-21 by grepping the vault; four
of these numbers were stale, and `sequence` was absent._ Every one of the seven variants
`Marker.fromToken` accepts has at least one positive fixture.

The thin ones are `3way/all` and `sequence`, each with **exactly one instance in the whole
corpus** — `System-Design/Linearizability.md` and `Anatomy/Sequences.md` respectively.
_`cloze` used to be listed as thin, "in the good vault only in `Bones.md`". That is no longer
true: `Anatomy/Body-Shapes.md` carries three cloze sections, including the only multi-block
one. `Bones.md`'s own prose still makes the superseded claim._

**Structural cases, and where each one lives.** Sole-instance cases are marked ✱ — these
are the fixtures that look deletable and are not.

| Case | Fixture |
| --- | --- |
| Deck from nested folder chain | ✱ `Patterns/Nested/Deep/Quorums.md` |
| File name is not a deck level | `Patterns/Nested/Deep/Quorums.md` |
| Heading path deeper than two levels | ✱ `System-Design/Consistency.md` (`####`) |
| Unmarked heading generates nothing | `Coupling.md`, `Linearizability.md`, `Multi-Topic.md`, `slash-in-heading.md` |
| Unmarked heading still contributes to the path | `Multi-Topic.md`, `Consistency.md` |
| Concept from nearest ancestor heading, two concepts in one file | ✱ `System-Design/Multi-Topic.md` |
| Identical heading text at different paths | ✱ `System-Design/Multi-Topic.md` |
| `/` inside a heading segment (benign) | ✱ `Patterns/Messaging.md` |
| `/` join collision (hostile) | ✱ `path-separator/slash-in-heading.md` |
| Well-formed table card (concept + two descriptor columns) | `Patterns/Messaging.md`, `Anatomy/Body-Shapes.md` |
| Degenerate tables | ✱ `Patterns/Table-Edge-Cases.md` (three shapes) |
| Multiple cloze deletions in one body | `Anatomy/Bones.md`, `Anatomy/Body-Shapes.md` |
| Cloze body spanning MORE THAN ONE BLOCK | ✱ `Anatomy/Body-Shapes.md` (`## Bones of the hand, in two parts`) |
| Labelled cloze groups (`==1\|x==`), so text may change without losing history | ✱ `Anatomy/Body-Shapes.md` (`## Bones of the forearm`) |
| Non-distributed-systems subject | `Anatomy/Bones.md`, `Anatomy/Body-Shapes.md`, `Anatomy/Sequences.md` |
| Second deck root | `Anatomy/` (three notes) |
| A list revealed one item at a time (`/sequence`) | ✱ `Anatomy/Sequences.md` |
| The `Context` breadcrumb's motivating card | ✱ `Anatomy/Body-Shapes.md` (`## Cranial bones and their sutures`) |
| Under-indented nested list — REFUSED, end to end from a real file | ✱ `Patterns/Shallow-Nesting.md` |
| Duplicate identity keys WITHIN one file | ✱ `Patterns/Table-Edge-Cases.md` (the repeated `Retry` row) |
| Wikilinks, aliases, heading anchors, lists, code fences in a card body | ✱ `System-Design/Replication.md` |
| Duplicate identity key across files | ✱ `duplicate-ids/` (both notes) |
| Missing `id:` key | ✱ `missing-id/frontmatter-without-id.md` |
| Missing frontmatter block | ✱ `missing-id/no-frontmatter.md` |
| YAML shapes a HOCON parser mangles | ✱ `corrupt-frontmatter/` (three shapes) |
| Empty document | ✱ `empty-file/empty.md` |
| Identity present, body empty | ✱ `empty-file/frontmatter-only.md` |
| Concept falls back to filename | ✱ `empty-concept/no-h1-no-headings.md` |

**Known gap.** The *empty basename* case — a file whose stem is empty — is not covered.
It was previously attempted as `hostile-vaults/empty-basename/.md`, which was removed:
the leading dot made it a dotfile that Obsidian and ordinary `**/*.md` walkers skip, so
it never reached the code under test, and it confounded empty basename with empty file.
Any reachable file name has a non-empty stem, so this case is not expressible as a vault
fixture at all; if the guard matters it belongs in a unit test over the path-parsing
function. Please do not re-add the dotfile.

**Conventions for adding a fixture.** Frontmatter in `dummy-vault/` stays minimal — an
`id:` and nothing else. Each hostile mini-vault gets its own directory and opens with a
`REGRESSION TEST FIXTURE` paragraph naming the correct behaviour. One variable per
fixture: if a failure could be attributed to either of two properties, split it into two
files. And add a row here — a fixture whose purpose is recorded only in a commit message
is a fixture someone deletes.
