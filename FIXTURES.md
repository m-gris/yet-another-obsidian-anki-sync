# Fixture manifest

This file is the declared intent of the test corpus: for every fixture note, which
specific case it exists to prove. It is the defence against the failure mode where a
fixture that quietly carries the only instance of a case gets deleted later as
redundant-looking duplication.

## The two trees

**`dummy-vault/`** is a single, coherent, well-formed vault. Everything in it must sync
cleanly and produce exactly the cards described below. It is the happy path, and it is
also where the *positive* structural cases live: nested folders, nested headings,
unmarked headings, every marker variant, Obsidian body syntax.

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
`/cloze`, `/table`. Unmarked headings generate nothing but still contribute to the path.
A card's identity key is `(frontmatter id, heading path)`, where the heading path is the
chain of ancestor headings joined with `/`. Decks mirror the **folder** path; the file is
not a deck level.

## `dummy-vault/` — must sync cleanly

Expected decks: `Anatomy`, `Patterns`, `Patterns::Nested::Deep`, `System-Design`.

| File | `id:` | Markers | The case it exists to prove |
| --- | --- | --- | --- |
| `Anatomy/Bones.md` | `fix-bones` | 2 × `cloze` | The **only cloze note in the good vault**, and the only note outside the `System-Design` / `Patterns` trees. Proves (a) several `==…==` deletions inside one card body, (b) a second top-level folder becoming a second deck root. Its subject matter is deliberately not distributed systems: the tool must not be coupled to one domain. |
| `Patterns/Messaging.md` | `fix-messaging` | 1 × `table` | Heading text containing the path-join character: `## Cost / benefit`. Probes whether `/` inside a segment is escaped before the heading path is joined. Also the only **well-formed** table card — three columns, one concept plus two descriptors — against which `Table-Edge-Cases.md` is the degenerate contrast. The `/` must not be tidied away. |
| `Patterns/Nested/Deep/Quorums.md` | `fix-quorums` | 2 × `1way` | The **only deep-folder note**. Proves the deck mirrors the full folder chain (`Patterns::Nested::Deep`) and that the file name does *not* become a deck level. |
| `Patterns/Table-Edge-Cases.md` | `fix-table-edges` | 3 × `table` | Degenerate but legal tables, one per section: concept column only with no descriptors; two rows sharing a concept; exactly one descriptor column. Each section isolates one variable. |
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

**Marker variants.** `1way` (6), `2way` (3), `3way` (10), `3way/all` (1), `cloze` (2),
`table` (4) in `dummy-vault/`. Every declared variant has at least one positive fixture.
`3way/all` and `cloze` are the thin ones — `3way/all` has exactly one instance in the
whole corpus, in `Linearizability.md`, and `cloze` exists in the good vault only in
`Bones.md`.

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
| Well-formed table card | ✱ `Patterns/Messaging.md` |
| Degenerate tables | ✱ `Patterns/Table-Edge-Cases.md` (three shapes) |
| Multiple cloze deletions in one body | ✱ `Anatomy/Bones.md` |
| Second deck root / non-distributed-systems subject | ✱ `Anatomy/Bones.md` |
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
