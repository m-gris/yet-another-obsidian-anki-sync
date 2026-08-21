# Owned note types, and a visible Context field

_Written 2026-08-21 as the design the build phase follows. Every claim below is marked
verified, derived, or predicted. Nothing here is remembered._

---

## 0. What this fixes, and why it is two things and not one

A card in a live collection reads:

```
Concept: Frontal      Descriptor: Anterior border      ->  Orbital rim
```

Frontal *what*? Bone, lobe, cortex? The segment that disambiguates — `cranial bones and
their sutures` — exists, is computed, and is thrown away. **Verified**: the fixture
`dummy-vault/Anatomy/Body-Shapes.md` has H1 `# Body shapes` and `## Cranial bones and their
sutures #flashcard/table`, and the golden's key for that card is
`src::fix-body-shapes::body%20shapes/cranial%20bones%20and%20their%20sutures/frontal/anterior%20border`
while its only display fields are `Concept ⟦Frontal⟧` and `Descriptor ⟦Anterior border⟧`.

Fixing it needs two independent things, and conflating them is the main way this slice can
go wrong:

1. **A `Context` field** carrying the properly-cased heading chain. It cannot come from the
   `src::` tag: the tag holds the canonicalised path — lowercased, whitespace-collapsed,
   percent-encoded — precisely because identity is severed from display. A tag-derived
   breadcrumb would read `body shapes > cranial bones and their sutures` in permanent
   lowercase.
2. **Five note types the tool owns**, because a `Context` field cannot be added to Anki's
   stock `Basic` / `Basic (and reversed card)` / `Cloze` without editing note types the rest
   of Marc's collection shares.

Marc's five names, to be used exactly:

```
Obsidian Basic
Obsidian Basic (and reversed card)
Obsidian Cloze
Obsidian Cloze Sequence          (hand-rename of the existing "Cloze Sequence")
Obsidian Concept-Descriptor      (hand-rename of the existing "3 way Concept-Descriptor")
```

**AnkiConnect has no rename-model action.** Verified this session by listing every
`def .*[Mm]odel` in the installed add-on (`~/Library/Application
Support/Anki2/addons21/2055492159/__init__.py`): there is `modelTemplateRename` and
`modelFieldRename`, and nothing that renames a *model*. The two renames are Marc's to do by
hand in Tools → Manage Note Types. Nothing in this design attempts them or works around
them.

---

## 1. Repository layout

`git mv obsidian-anki-custom-sync/note-types obsidian-anki-custom-sync/resources/note-types`,
then add one line to `project.scala`:

```
//> using resourceDir ./resources
```

**Verified this session, not predicted.** A scratch scala-cli project on Scala 3.8.4 with
`//> using resourceDir ./resources` and a file at `resources/note-types/basic/manifest.json`
returned that file from `getClass.getResourceAsStream("/note-types/basic/manifest.json")`
under both `scala-cli test` and `scala-cli run`. `project.scala` has no `resourceDir`
directive today (verified by reading it).

The full tree after the move:

```
obsidian-anki-custom-sync/
  project.scala                              (+ //> using resourceDir ./resources)
  resources/
    note-types/
      README.md                              layout, schema, the byte-identity invariant,
                                             the ours-vs-vendored convention, and the
                                             ruling that the tool writes only to its own
                                             five types

      basic/
        manifest.json
        styling.css
        templates/card-1.front.html
        templates/card-1.back.html

      basic-and-reversed/
        manifest.json
        styling.css
        templates/card-1.front.html
        templates/card-1.back.html
        templates/card-2.front.html
        templates/card-2.back.html

      cloze/
        manifest.json
        styling.css
        templates/cloze.front.html
        templates/cloze.back.html

      cloze-sequence/
        manifest.json
        styling.css
        LICENSE.upstream                     unchanged, MIT, the one wholly-upstream file
        README.md                            provenance, the two upstream modifications,
                                             the j/k key bindings and why not Enter
        templates/<captured>.front.html      was front.html
        templates/<captured>.back.html       was back.html

      concept-descriptor/
        manifest.json
        styling.css
        templates/<captured-1>.front.html
        templates/<captured-1>.back.html
        templates/<captured-2>.front.html
        templates/<captured-2>.back.html
        templates/<captured-3>.front.html
        templates/<captured-3>.back.html
```

`fields.json` **disappears** — its content moves into `manifest.json`.

`<captured>` means the template file slug is derived from a template name read out of the
live collection in Phase 0 (§7). It is not invented. See §3.

### Why `resources/note-types/` and not `note-types/`

Rejected: keeping the directory where it is and writing `//> using resourceDir
./note-types`. That puts `/cloze-sequence/`, `/basic/`, `/cloze/` at the **classpath root**,
which is a bet that no dependency ever ships those paths. The `resources/` wrapper costs one
directory and namespaces everything under `/note-types/`.

The counter-argument — that moving invalidates seven `note-types/cloze-sequence/<file>:<line>`
doc-comment references — is **almost entirely void, and I checked rather than assumed**. The
seven references are at `content/AsHtml.scala:500`, `model/Marker.scala:56,109,127`,
`extract/Extractor.scala:317,380`, `extract/Extractor.test.scala:994`. Six of the seven point
at `front.html`, `back.html` or `fields.json` — files that move into `templates/` or cease to
exist under *any* version of this design. Only `AsHtml.scala:500` (`styling.css`) would have
survived a no-move. So the references must be rewritten regardless; the move adds one file to
that rewrite.

**All seven must be updated in the same commit as the move.** A stale `file:line` in a
comment is exactly the class of untrue prose claim this project has been bitten by eleven
times.

### Why directory-per-type, with `templates/` even at one template

Arithmetic, not taste. Template counts:

| Note type | Templates | `isCloze` |
|---|---|---|
| Obsidian Basic | 1 | false |
| Obsidian Basic (and reversed card) | **2** | false |
| Obsidian Cloze | 1 | **true** |
| Obsidian Cloze Sequence | 1 | false |
| Obsidian Concept-Descriptor | **3** | false |

A flat `front.html` / `back.html` pair can express only the one-template case, and two of the
five need more. `templates/` exists even for a single template so that adding a second one is
two files and one array entry, never a restructure, and so that no reader has to learn two
layouts.

Rejected: `front-2.html` / a `reversed/` special case (two layouts to learn); one flat
directory of files named by type (unreadable at ~25 files, and `LICENSE.upstream` loses its
owner); templates embedded as JSON strings inside the manifest (`back.html` is ~40 lines of
commented JavaScript — as one escaped line it is un-lintable, un-diffable, and it breaks the
invariant in §2).

---

## 2. The invariant that makes everything else cheap

**Every file inside a type's directory is byte-identical to what is in, or goes into, the
collection.** No templating, no variable substitution, no shared CSS partial concatenated at
install time.

Three consequences, stated so nobody "DRYs" them away later:

- Each type's `styling.css` is its **complete** CSS. The `.context` block in §6 is copied
  verbatim into all five files. Five copies will look redundant. They are the price.
- A drift check is `modelTemplates(name)` and `modelStyling(name)` compared with `==`
  against the files. No re-derivation, so a mismatch is a diff a human can read.
- The repo file is a faithful backup, which is the stated reason the directory exists —
  commit `72e8761` ("Save the list note type into the repository"), whose message records
  that the note type existed only inside a live collection and one deleted profile would have
  destroyed it.

**Rejected: a shared `note-types/context.css` concatenated at install.** It is the obvious
DRY move and it breaks the invariant: what the repo shows would no longer be what the
collection holds, and the drift check would have to re-derive instead of compare. CSS is not
hashed (`Planner.contentHash` covers note type name plus fields, verified by reading it), so
restyling is free either way — the duplication costs nothing at runtime and buys a
string-equality drift check.

---

## 3. What a note type definition file contains

`manifest.json`, parsed with **circe** (already a dependency, `project.scala:9`), with a
**strict decoder and no defaults**. Precedent: `anki/AnkiConnect.scala`'s
`Decoder[ObservedNote]`, whose comment explains why filling in defaults manufactures
plausible garbage.

**Not YAML.** snakeyaml is in the tree with implicit typing deliberately disabled because its
resolver silently turned the id `2026-08-18` into `202608-18` (HANDOFF, hazard table). A
second YAML entry point re-arms a parser this codebase already has a scar from.

### Schema

```json
{
  "name":        "Obsidian Cloze Sequence",
  "renamedFrom": "Cloze Sequence",
  "isCloze":     false,
  "fields":      ["Title", "Text", "Context"],
  "styling":     "styling.css",
  "templates": [
    { "name":  "<exact template name as it exists in the collection>",
      "front": "templates/<slug>.front.html",
      "back":  "templates/<slug>.back.html" }
  ],
  "derivedFrom": {
    "url":         "https://github.com/tekinosman/cloze-sequence",
    "licence":     "MIT",
    "licenceFile": "LICENSE.upstream",
    "modified":    true
  },
  "capturedFrom": {
    "profile": "claude-POC-test",
    "action":  "modelTemplates",
    "date":    "<the Phase 0 date>"
  }
}
```

Key by key:

- **`name` — this is where the model name lives.** Today `model/Marker.scala:114` is, by its
  own doc-comment and by my own re-grep this session, the *sole* machine-readable statement
  of `"Cloze Sequence"` anywhere in the repository; nothing would notice if it drifted from a
  real collection. After this slice the name has two machine-readable homes and a test
  (§4, T1b) that fails if they disagree. `Marker.NoteTypes.*` remains what Scala
  pattern-matches on; the manifest is what `createModel` sends.
- **`renamedFrom`** — present on exactly the two hand-renamed types, absent otherwise. It
  exists for one specific hazard, §5.
- **`isCloze`** — mandatory, no default, never inferred. **Verified**:
  `note-types/cloze-sequence/front.html:3` is `<div id="text">{{Text}}</div>` — a plain field
  reference, not `{{cloze:Text}}` — while its `styling.css` defines `.cloze` and
  `.hidden-cloze` and its *name* contains "Cloze". So the one type whose name and CSS say
  "cloze" is the one that must be created with `isCloze: false`. Every available heuristic
  gets it exactly backwards.
- **`fields`** — an ordered array, replacing `fields.json`. Order is load-bearing twice:
  `createModel`'s `inOrderFields` (verified by reading the add-on, line 1120), and
  `Planner.contentHash`, which hashes field names in order, so a wrong order makes every run
  plan an Update for every card.
- **`templates`** — an ordered **array of objects**, never a name-keyed object, because
  template order is the card ordinal in Anki and JSON objects carry no guaranteed order.
- **`templates[].name` is not derived from the filename, and the filename is not derived from
  the name.** **Verified in the add-on source** (`updateModelTemplates`, lines 1294–1312): it
  iterates the *model's* templates and does `templates.get(ankiTemplate['name'])` — a name
  our payload gets wrong is a **clean-exit no-op**, no error, nothing written. An inferred
  name is therefore a silent no-op waiting to happen. The mapping is explicit and reviewable;
  the file slug is documentation only.
- **`derivedFrom`** — the ours-vs-vendored answer as one key rather than prose. Absent means
  wholly ours. `"modified": true` states the honest thing: of the six files in
  `note-types/cloze-sequence/` today, `LICENSE.upstream` is the only one unmodified upstream
  (its README documents both changes to the templates).
- **`capturedFrom`** — present on the two renamed types only. It records that those templates
  were **read out of a live collection**, not authored, which is why a later reader must not
  "tidy" them.

### Which template names are authored and which are captured

**Ruling, and it is not negotiable because of the silent-no-op above:**

- **`basic`, `basic-and-reversed`, `cloze`** are created from scratch by `createModel`. We
  author their template names, and I rule them `Card 1`; `Card 1` / `Card 2`; `Cloze` — the
  same names Anki's own stock types use, so the Browse screen reads familiarly.
- **`cloze-sequence`, `concept-descriptor`** already exist in the collection and are being
  *renamed*, not created. Their template names **must be captured verbatim in Phase 0** and
  written into the manifest exactly. Do not invent them, do not slugify them, do not "tidy"
  them.

A navigator reported reading `Card 1` / `Card 2` / `Card 3` off the live
`3 way Concept-Descriptor`. A superseded document (`poc-obsidian-vault/SETUP.md:28-42`, which
HANDOFF.md says to ignore entirely) instead shows descriptive names such as
`Descriptor+Description -> Concept`. **I did not read the live collection and I am not
resolving this from documents.** Phase 0 resolves it.

### Finding a manifest from a note type name

One explicit table in the install package:

```
Marker.NoteTypes.Basic            -> "basic"
Marker.NoteTypes.BasicAndReversed -> "basic-and-reversed"
Marker.NoteTypes.Cloze            -> "cloze"
Marker.NoteTypes.ClozeSequence    -> "cloze-sequence"
Marker.NoteTypes.ConceptDescriptor-> "concept-descriptor"
```

Names by **reference**, never re-typed as literals; only the slugs are string literals, and a
wrong slug is a loud missing-resource failure, not a silent one.

**Rejected: enumerating the classpath directory to discover the five.** Directory listing
works when the classpath entry is a directory and breaks when it is a jar — a Hyrum's-law
dependency on an undocumented packaging detail, for no benefit over an explicit list.

**The loader must fail loudly on a missing resource.** `getResourceAsStream` returns `null`.
A `null` read as an empty template produces a note type whose cards never generate — silent,
and exactly this project's signature failure. No `Option`, no default, no empty string.

---

## 4. The field-list contract, and the tests that prove it

### The Scala side

`model/Marker.scala` gains:

- `val ContextField: String = "Context"` — one field name for all five types.
- `NoteTypes.All: Vector[String]` — the five, in a fixed order.
- **One ordered field-name vector per note type**, which is what the manifest is tested
  against and what `createModel` receives. Call the holder `Marker.FieldOrder`.

**A trap that must be avoided by construction.** `CardSpec.fields` currently builds the
three-field arm as `ConceptDescriptorFields.zip(Vector(concept, descriptor, description)) :+
(ThreeWayField -> threeWay)`. `Vector.zip` **silently truncates** on unequal lengths. If
`ConceptDescriptorFields` simply grew a `"Context"` entry, the zip would drop it without a
word and the whole feature would vanish while every test stayed green — the exact
plausible-output-instead-of-failure shape this project has ten scars from. So:

- `ConceptDescriptorFields` / `ClozeSequenceFields` keep their present meaning (the values
  the zip pairs against) and do **not** grow;
- `FieldOrder.*` are the separate, complete, ordered vectors including `ThreeWay` and
  `Context`;
- test **T2** below asserts the two agree, exactly and in order, which is what makes a
  truncating zip a build failure instead of a silent omission.

Field order, **Context last on every type**:

```
Obsidian Basic                      Front, Back, Context
Obsidian Basic (and reversed card)  Front, Back, Context
Obsidian Cloze                      Text, Back Extra, Context
Obsidian Cloze Sequence             Title, Text, Context
Obsidian Concept-Descriptor         Concept, Descriptor, Description, ThreeWay, Context
```

Three reasons for last, all of which point the same way: Anki's Sort Field defaults to field
1, and a breadcrumb there would fill the Browse list; `modelFieldAdd` **appends** (verified in
the add-on, line 1433 — add-if-absent, reposition only when an `index` is passed), so the two
hand-renamed types need no `modelFieldReposition`; and appending leaves every existing field
position stable.

### The fake

`anki/InMemoryAnki.scala` holds a **third, undeclared copy** of this contract: lines 201–206
(`defaultNoteTypes`) and 59–66 (`cardCountOf`) spell the note type names and field lists as
string literals rather than referencing `Marker`. The file already imports from
`obsidiananki.model`, so it can use the constants.

**Predicted, not run**: with the names changed to `Obsidian *`, `cardCountOf`'s literal
`case "Basic (and reversed card)" => 2` stops matching and falls through to `case _ => 1`
(line 65), so the fake would silently claim a reversed note has one card.
`defaultNoteTypes` failing to match would at least be loud (`NoSuchNoteType`); `cardCountOf`
would not. `extract/FixtureVault.test.scala:100-104` already flags that fallthrough as "right
by fallthrough rather than by any rule naming it."

So: replace both sets of literals with `Marker.NoteTypes.*` / `Marker.FieldOrder.*`, and add
test **T4**.

### The four tests

All live in the install package (which may read the classpath), **never in `model/`** —
`model/Marker.scala` has no imports at all (verified by reading the file), and
`CardSpec.noteTypeName` is a pure total function on a closed ADT. Giving the dependency-free
base layer a classpath dependency to save one test is a bad trade.

**T1 — manifest ↔ Scala.** Loads all five manifests through the slug table and asserts:

1. every slug resolves to a readable `manifest.json`;
2. `manifests.map(_.name) == Marker.NoteTypes.All`, in order — *this is the assertion that
   closes the gap `model/Marker.scala:105-112` names in its own comment*;
3. per type, `manifest.fields == Marker.FieldOrder.<type>`, in order — *this closes the gap
   `model/Marker.scala:124-138` names in its own comment*;
4. every file a manifest names (`styling`, every template `front` and `back`) resolves on the
   classpath and is non-empty;
5. `derivedFrom.licenceFile`, when present, names a file that resolves;
6. `renamedFrom` is present on exactly `Obsidian Cloze Sequence` (`"Cloze Sequence"`) and
   `Obsidian Concept-Descriptor` (`"3 way Concept-Descriptor"`), and absent on the other
   three.

**T2 — `CardSpec.fields` ↔ `FieldOrder`.** For one representative spec of each of the six
shapes (`TwoField` Forward, `TwoField` Both, `ThreeField`, `Cloze`, `TableRow`, `Sequence`),
assert `spec.fields.map(_._1) == Marker.FieldOrder(spec.noteTypeName)` — exact, ordered.
This is the test that makes a truncating `zip` fail.

**T3 — templates ↔ fields, both directions.** Extract every `{{…}}` from every template file
of every manifest, normalising the four Anki forms: plain `{{F}}`, section `{{#F}}` /
`{{^F}}` / `{{/F}}`, and filtered `{{filter:F}}` (take the segment after the last `:`). Then
assert **both** directions, because each catches an opposite silent failure:

- **`refs \ specials ⊆ manifest.fields`.** A template naming a field the tool never populates
  renders blank forever with no error. `specials` is a short, explicit list of only the
  special names our own templates actually use — today exactly `FrontSide` (verified:
  `note-types/cloze-sequence/back.html:1` is `{{FrontSide}}`). An unrecognised reference must
  **fail**, forcing whoever adds `{{Deck}}` to declare it. Do not hardcode Anki's full
  special-name list; a too-generous list silently widens the check.
- **`manifest.fields ⊆ refs`.** A field the tool writes that no template mentions is a value
  pushed into the collection that nobody ever sees. **This is the exact way `Context` fails**:
  created, populated, hashed, synced, invisible, feature silently undelivered. Note that
  `ThreeWay` needs no exemption — it is referenced as `{{#ThreeWay}}`, and the normaliser
  strips the `#`.

**T4 — the fake's card counts.** For each of the five names, drive `InMemoryAnki` through
`addNote` and `cardsOf` and assert the count: 1, 2, 1, 1, and 2-or-3 for
`Obsidian Concept-Descriptor` depending on whether `ThreeWay` is set. Catches the
`case _ => 1` fallthrough named above.

### Mutants — run these before believing any of the four

Per the project methodology (HANDOFF, "Do not trust a green test"), each mutant must make a
**named** test fail:

| Mutant | Must fail |
|---|---|
| Remove `"Text"` from `FieldOrder.ClozeSequence` | T1.3 and T3 direction 1 |
| `{{Title}}` → `{{Titel}}` in the cloze-sequence front template | T3 direction 1 |
| Add `Context` to a `FieldOrder` without adding it to any template | T3 direction 2 |
| Delete the Context snippet from one template front | T3 direction 2 |
| Change one manifest's `"name"` back to the pre-rename name | T1.2 |
| Drop `:+ (ThreeWayField -> threeWay)` from `CardSpec.fields` | T2 |
| Change `cardCountOf`'s reversed case to fall through | T4 |

**Control that must SURVIVE** — without it the harness is only proving it fails on
everything: edit a comment inside `back.html`'s `<script>`, or change a colour in a
`styling.css`. Both must leave the suite green.

---

## 5. The install command

New CLI command, `install-note-types`. It writes to a collection, so it goes through
`Main.withVerifiedProfile` — the profile guardrail is not optional. **Never profile `User 1`.**

Per type, in this order:

1. Read `noteTypeNames`.
2. **If `manifest.name` is absent AND `manifest.renamedFrom` is present in the collection →
   REFUSE**, naming the exact old → new pair and Tools → Manage Note Types. Do not
   `createModel`.

   This branch is the whole reason `renamedFrom` exists. **Verified in the add-on source**
   (line 1126): `createModel` raises only when the *new* name already exists. So a naive
   create-if-missing installer run before Marc performs the renames would succeed, and the
   collection would then hold **two** note types — a new empty one and the old populated one,
   with every existing note still on the old, and nothing would error. Plausible output
   instead of failure, delivered by the very slice meant to fix a display bug.
3. If `manifest.name` is absent and `renamedFrom` is absent (or absent from the collection) →
   `createModel(name, fields, templates, css, isCloze)`. **Verified payload shape** (add-on
   line 1120): `createModel(modelName, inOrderFields, cardTemplates, css=None,
   isCloze=False)`, with each template a dict of `Name` / `Front` / `Back`; a missing `Name`
   silently becomes `"Card N"` (lines 1149–1151), so always send it.
4. If `manifest.name` is present → **report differences, do not repair.** Read
   `modelFieldNames`, `modelTemplates`, `modelStyling` and print every difference against the
   manifest. HANDOFF open item 5 records that repair-in-place has never been tested.

Repair lives behind an explicit `--repair` flag, and when it runs:

- compare the template-name **sets** first and fail loudly on any mismatch, because
  **`updateModelTemplates` silently ignores names it does not recognise** (verified, add-on
  1294–1312);
- use `updateModelTemplates` only for names present on both sides, and **never send an empty
  string** for a side — `if qfmt:` (line 1303) skips a falsy side silently;
- **never use `modelTemplateAdd` as an upsert.** Verified (add-on 1377–1397): when the named
  template already exists it mutates the dict and `return`s **without calling
  `self.save_model`**. Only the genuinely-new path saves. It looks like an upsert and its
  update path does not persist;
- use `modelFieldAdd` for a genuinely absent field — this is the route for putting `Context`
  onto the two hand-renamed models;
- use `updateModelStyling` for CSS.

---

## 6. The Context field

### What it holds

**The heading chain, in display casing, down to but not including whatever the card's own
face already shows**, segments joined with `" › "` (space, U+203A, space).

`extract/Extractor.scala:53-55` already carries `ancestorTitles: Vector[String]` beside the
canonicalised `ancestors: Vector[HeadingSegment]`; its own docstring says it keeps original
casing because it is shown on a card. Line 288 reads exactly one element of it —
`ancestorTitles.lastOption.getOrElse(fileName)` — and discards the rest. This design uses the
rest.

**Verified**: `ancestorTitles` starts at the **H1**, not the file stem.
`dummy-vault/Anatomy/Body-Shapes.md` has stem `Body-Shapes` and H1 `# Body shapes`, and the
golden key is `…::body%20shapes/…` — a space, so the segment came from the H1.

Per card shape, where `chain = ancestorTitles` (ancestors only, excluding the marked heading)
and `title = Marker.stripMarker(rawHeading)`:

| `CardSpec` | the face already shows | **Context** |
|---|---|---|
| `TwoField` (`1way` / `2way`) | `Front` = `title` | `chain` |
| `ThreeField` from a heading | `Concept` = `chain.last`, `Descriptor` = `title` | `chain.dropRight(1)` |
| `ThreeField` from a table cell pair | `Concept` = row cell, `Descriptor` = column header | `chain :+ title` |
| `TableRow` | `Front` = row cell | `chain :+ title` |
| `Cloze` | the body text only | `chain :+ title` |
| `Sequence` | `Title` = `title` | `chain` |

Worked against the golden — the motivating card first:

```
src::fix-body-shapes::…/cranial bones and their sutures/frontal/anterior border
    Context ⟦Body shapes › Cranial bones and their sutures⟧
    Concept ⟦Frontal⟧   Descriptor ⟦Anterior border⟧

src::fix-consistency::consistency/session guarantees/monotonic reads/definition
    Context ⟦Consistency › Session guarantees⟧   Concept ⟦Monotonic reads⟧

src::fix-consistency::consistency/definition
    Context ⟦⟧                                   Concept ⟦Consistency⟧

src::fix-messaging::messaging patterns/cost / benefit/queue/benefit
    Context ⟦Messaging patterns › Cost / benefit⟧   Concept ⟦Queue⟧
```

### The separator

`" › "` — U+203A, SINGLE RIGHT-POINTING ANGLE QUOTATION MARK.

Verified this session:

- it appears **nowhere** in `dummy-vault/` or `hostile-vaults/` (grepped, zero matches);
- `content/AsHtml.scala:322-333` — `Html.escape` touches exactly `& < > " { }`, so U+203A
  passes through untouched;
- `extract/Golden.test.scala`'s `EscapedTypes` set is `CONTROL, FORMAT, SURROGATE,
  UNASSIGNED, PRIVATE_USE, LINE_SEPARATOR, PARAGRAPH_SEPARATOR, SPACE_SEPARATOR`. U+203A's
  Unicode category is `Pf` (final punctuation), which is not in that set, so it stays
  **literal** in the golden rather than becoming `›` — the golden line reads
  `field ⟦Context⟧ ⟦Body shapes › Cranial bones and their sutures⟧`.

**Rejected `/`.** `dummy-vault/Patterns/Messaging.md` contains `## Cost / benefit` **on
purpose** — FIXTURES.md says "The `/` must not be tidied away". `Messaging patterns / Cost /
benefit` is indistinguishable from a three-level chain. It would also make the breadcrumb
look like the `src::` tag's join character, which is precisely the identity/display
conflation the design severs.

**Rejected `>`.** `dummy-vault/Patterns/Nested/Deep/Quorums.md` has a heading containing `>`
(its golden field reads `What does W + R &gt; N buy you?`), and `Html.escape` turns `>` into
`&gt;`, making the golden harder to read for no gain.

### The emptiness rule

Empty chain → the field is `""`, and the template's `{{#Context}}` wrapper emits nothing at
all — no `<div>`, no rule, no margin.

**Derived by hand from the fixture headings, not run: 5 of the 55 fixture cards get an empty
Context, 50 get a non-empty one.** The only way to be empty is a heading-derived `ThreeField`
whose ancestor chain is just the H1. Those are `Consistency.md`'s `## Definition` and `## Why
it is a spectrum`, and `Linearizability.md`'s `## Definition`, `## Cost`, and `## Contrast
with sequential consistency` — all `##` directly under an H1 (verified by grepping heading
levels in both files). `Multi-Topic.md`'s four `3way` cards sit under unmarked topic headings
and so have a two-element chain. The fixture therefore exercises both branches of the template
conditional with no new fixture note. **The driver gets the real number from the golden diff
and must reconcile it with this prediction before adopting.**

### Where it is built, and where escaping happens

Built in `extract/`, escaped **in the argument position**, exactly as `concept`, `descriptor`
and `title` already are (`Extractor.scala:275`, `:291-294`, `:383`). Join
already-escaped segments with an already-escaped separator, so the rule "every String that
becomes an Anki field value is escaped at its construction site in `extract/`" stays complete
and greppable with the one existing named exception (`TableRow`'s Back, built in `model/`).

**Three prohibitions, each with a measured witness:**

1. **Never derive Context from `key.path` or from `HeadingSegment`.** That is the lowercased,
   whitespace-collapsed, percent-encodable form. A breadcrumb built from it reads
   `body shapes › cranial bones and their sutures` in permanent lowercase — the outcome Marc's
   ruling explicitly rejects.
2. **Never escape `title` or `ancestorTitles` any earlier than the argument position.**
   `Extractor.scala:270-287` spells out why with the witness: the Quorums key carries `>` as
   `%3e` *inside the key*; escaping upstream of the display/identity fork turns it into
   `%26gt%3b` — one orphan plus one history-less card for every heading containing any of
   `& < > " { }`. **This is how HARD CONSTRAINT 1 gets violated.**
3. **Never give `context` a default value** on the `CardSpec` constructors. An omitted
   argument at a future construction site would ship a card with a silently missing
   breadcrumb. Scala 3's inexhaustive-match-as-build-error (`project.scala`'s
   `-Wconf:msg=exhaustive:e`) is what makes the no-default version cheap.

`context: String` is added as a parameter to all five `CardSpec` cases and appended in each
`fields` arm.

### `Tables` needs a new parameter

`extract/Tables.scala:116-120` — `fromSection(key, section, display)` — receives only the
`CardKey`, which holds the canonicalised form. It gains one parameter, **fully computed by
the caller** so that `Tables` holds no rule of its own:

```
contextTitles: Vector[String]      // = ancestorTitles :+ title, unescaped
```

**No default and no delegating overload**, for the reason that file's own comment already
gives about `display`: "production would drive one arity while the guard drove the other".

**Two call sites**, both in `Extractor.scala`, and both must be updated: the live one inside
`if marker == Marker.Table`, and the arm inside the `marker match` which
`Tables.scala:109-114` documents as unreachable-but-compiled (it exists only because an
inexhaustive match is a build error here). Only the first is exercised by any test.

### Which side it renders on

**The question side, inherited onto the answer side** — except Cloze, where it must be written
twice.

One snippet, byte-identical everywhere it appears:

```html
{{#Context}}<div class="context">{{Context}}</div>{{/Context}}
```

| Note type | Template | Front | Back |
|---|---|---|---|
| Obsidian Basic | Card 1 | `SNIPPET{{Front}}` | `{{FrontSide}}<hr id=answer>{{Back}}` |
| Obsidian Basic (and reversed card) | Card 1 | `SNIPPET{{Front}}` | `{{FrontSide}}<hr id=answer>{{Back}}` |
| | Card 2 | `SNIPPET{{Back}}` | `{{FrontSide}}<hr id=answer>{{Front}}` |
| Obsidian Cloze | Cloze | `SNIPPET{{cloze:Text}}` | `SNIPPET{{cloze:Text}}<br>{{Back Extra}}` |
| Obsidian Concept-Descriptor | 1 | `SNIPPET{{Descriptor}}<br>{{Description}}` | `{{FrontSide}}<hr id=answer>{{Concept}}` |
| | 2 | `SNIPPET{{Concept}}<br>{{Descriptor}}` | `{{FrontSide}}<hr id=answer>{{Description}}` |
| | 3 | `{{#ThreeWay}}SNIPPET{{Concept}}<br>{{Description}}{{/ThreeWay}}` | `{{FrontSide}}<hr id=answer>{{Descriptor}}` |
| Obsidian Cloze Sequence | (captured) | SNIPPET on a new first line, **above** `<h4>{{Title}}</h4>` and **outside** `<div id="text">` | unchanged — it opens `{{FrontSide}}` |

Two placement rules that are not cosmetic:

⚠️ **On Concept-Descriptor's third template the snippet must sit INSIDE
`{{#ThreeWay}}…{{/ThreeWay}}`.** Anki generates a card only when its front renders non-empty,
and `{{Context}}` is a real field reference. A snippet placed *outside* the wrapper would make
every plain `#flashcard/3way` note generate a **third card** — a breadcrumb and nothing else
on the front, with the Descriptor as its answer. It would look like a card. **I did not
verify this live; verification requires a write, which this phase forbids.** Treat
inside-the-wrapper as mandatory until measured. The measurement, for Phase 4: create one note
on the new model in `claude-POC-test` with `ThreeWay` empty and `Context` non-empty, then
`findCards` on it — expect **2**, and expect the outside-the-wrapper variant to give 3.

**On Cloze Sequence the snippet goes outside `<div id="text">`.** Verified from the files:
`front.html` adds `hidden-cloze` to every `#text li` and `styling.css:22-24` dims `#text` to
`opacity: 0.5` on the question side (`back.html` restores it to 1). Context is frame, not
dimmed answer, so it stays out of that div.

The `{{#Context}}` wrapper on the other four is not a card-generation concern — their fronts
always carry a real field. It exists so an empty Context emits no `<div>`, and therefore no
rule and no margin, on the 5 fixture cards that have none.

### Why the question side — the pedagogical argument

An answer-side breadcrumb leaves the question exactly as unanswerable as it is today; only the
post-mortem improves. The defect is that the **prompt** cannot be answered.

LEARNING-MODEL.md states the mechanism directly: "A content card met before its concept is
understood cannot be answered by recall. If you cannot derive it, you pattern-match the answer
string — and the card silently teaches you that you know something you do not." A card whose
prompt has lost its anchoring structure is in exactly that state, for a concept that *is*
understood. The same document's Bransford & Johnson citation is the positive form: supplying
the title before an abstract passage raised both comprehension and recall with the text
unchanged. Context is that title.

**The cost, stated rather than hidden.** On Concept-Descriptor's first template the answer
*is* the Concept, so Context necessarily narrows the answer to the siblings under that
heading — for a two-row table, close to a two-option multiple choice. The Descriptor and
Description still carry the discrimination, and template 1 is exactly where the reported
defect bites hardest (`Anterior border / Orbital rim → ?` is unanswerable without knowing the
domain is cranial bones). **Ruled: the snippet goes on all three templates.** This is a
pedagogical call and Marc can overrule it per template at zero cost to identity — templates
and CSS are not hashed.

**Rejected: putting the breadcrumb markup in the field instead of the template.** Two reasons.
The field value *is* hashed (`Planner.contentHash`), so a wrapper or CSS change would rewrite
every live note; with the wrapper in the template, restyling is free. And `content/AsHtml.scala`'s
`Html.Tag` is a closed set with no `div`/`span`, so emitting one from `extract/` means either
widening that enum or bypassing the opaque `Fragment` — reopening the hole that type exists to
shut. The **separator** stays in the field, because a template cannot join a variable number
of segments.

**Rejected: including the file name.** In the common case the H1 already *is* the document
title and is already segment 0 (`Consistency.md` → `# Consistency`), so prepending gives
`Consistency › Consistency › Session guarantees`. Worse, `fileName` is the raw stem
(`VaultWalker.scala:73`), so `Body-Shapes.md` would render `Body-Shapes › Body shapes › …`,
hyphen and all. The folder chain, which is what a file name gestures at, is already the deck.
Also rejected: prepending the stem *only* when the chain is empty — it makes one field mean
two different things depending on the document's shape, and makes a file rename churn the
content hash for a field the key does not depend on.

**Rejected: the whole chain unconditionally on every card.** On a heading-derived `3way` it
repeats the Concept immediately above the Concept field, and on Concept-Descriptor's first
template the Concept *is the answer* — so an unconditional chain prints the answer on the
question side. That is a correctness objection, not a style one.

**Rejected: truncating or eliding a long chain in the tool.** It would be a second place where
display diverges from source, and a length-dependent rule makes the content hash move when an
unrelated ancestor is renamed. Overflow is CSS's problem; the breadcrumb wraps.

---

## 6b. The styling rule

Appended verbatim to all five `styling.css` files:

```css
.context {
  font-size: 0.6em;
  line-height: 1.35;
  color: #8a8a8a;
  text-align: left;
  margin: 0 0 1.1em 0;
  padding-bottom: 0.4em;
  border-bottom: 1px solid #e3e3e3;
}
.nightMode .context, .night_mode .context {
  color: #8f8f8f;
  border-bottom-color: #3a3a3a;
}
```

Three independent signals, deliberately, because any one alone is weak on a phone screen:

1. **`text-align: left` against the card's centred text.** The strongest of the three. A
   *centred* breadcrumb sitting above a centred prompt reads as a **title** — that is, as part
   of the question. Breaking the alignment is what says "different layer".
2. **0.6em and mid-grey.** Subordinate in the hierarchy, still legible.
3. **A hairline rule beneath it**, lighter than `<hr id=answer>`, separating frame from prompt
   without competing with the answer divider.

**One further ruling, and it is a visible change Marc may want to overrule.** The three
newly-created types get **no hardcoded `.card { color: …; background-color: … }`**. A
navigator reported that the live `3 way Concept-Descriptor` hardcodes `color: black;
background-color: white`, and `note-types/cloze-sequence/styling.css` hardcodes `background:
#fff; color: #111` (that one I read myself). Both defeat night mode, and reviews happen on a
phone (REQUIREMENTS, Constraints). Since we author the CSS for all five and CSS is not hashed,
dropping those declarations costs nothing and is reversible in one edit.

---

## 7. Order of work

Five phases. The ordering is what keeps HARD CONSTRAINT 5 (one agent per live collection)
true, and what keeps the tree from ever being in a state where a sync writes a `Context` field
to a note type that has no such field.

**Phase 0 — live, read-only, single agent.** `getActiveProfile`; confirm `claude-POC-test`;
then capture and record verbatim:

- `modelTemplates("3 way Concept-Descriptor")` and `modelStyling(...)` — the three template
  **names** and both sides of each;
- `modelTemplates("Cloze Sequence")` and `modelStyling(...)` — the one template name;
- `modelFieldNames` for all five current types, which also settles the contradiction below.

**A contradiction to resolve in this phase**: `anki/InMemoryAnki.scala:198-200` says the stock
field names "are UNVERIFIED against a live collection", while `model/Marker.scala:150-152`
says they **were** verified live on 2026-08-19 via `modelFieldNames`. Both cannot be current.
One `modelFieldNames` call per stock type settles it, and the losing comment must be corrected
in the same commit — an untrue comment is the failure mode this project has eleven instances
of.

**Phase 1 — `Context`, stock note type names, pure.** `Marker.ContextField`,
`Marker.FieldOrder`; `context` on all five `CardSpec` cases; `Extractor` and `Tables` compute
it; golden adopts **55 new `field ⟦Context⟧` lines**. Note type names unchanged. No live
write. Test T2 lands here.

**Phase 2 — `Retype`, pure.** See §8. Lands *before* the rename so the working tree is never
in a state where `sync` fails on every note.

**Phase 3 — the note type asset system and the rename.** The `git mv`, `resourceDir`, five
manifests, five sets of templates and CSS, the slug table, tests T1/T3/T4, the
`InMemoryAnki` de-duplication, `Marker.NoteTypes.*` values become `Obsidian *`, and the
`install-note-types` command. Golden adopts **55 changed `note ⟦…⟧` lines**. No live write.

**Phase 4 — live, single agent.** Marc renames the two models by hand → run
`install-note-types` → sync a collision-free copy of the fixture vault (everything except
`Patterns/Table-Edge-Cases.md`, which holds three deliberate duplicate identities) → Marc
reviews a real card of each of the five types.

### Two golden movements, not one

Phase 1 moves 55 `field` lines. Phase 3 moves 55 `note` lines and every content hash. Landing
them in one commit produces a diff nobody can read against a file whose header says **"DO NOT
REGENERATE THIS FILE TO MAKE A FAILING TEST PASS. READ THE DIFF FIRST."** Two commits, two
adoptions, each read before it is adopted.

**`src::` tags do not move under any of this.** Context is built from `ancestorTitles` and
`title`, both already computed and currently discarded; `HeadingSegment` and `rawHeading` are
untouched. HARD CONSTRAINT 1 holds — *provided* prohibition 2 in §6 is obeyed. Check it with
`git diff` on `extract/golden/fixture-cards.txt`, looking specifically at the `card [src::…]`
lines.

### One hazard between Phase 1 and Phase 4

After Phase 1, `CardSpec.fields` writes a `Context` key to stock `Basic` / `Cloze` /
`3 way Concept-Descriptor`, which have no such field. **Verified from
`anki/AnkiConnect.scala:112-125`**: Anki reports a wrong field name on *create* as "cannot
create note because it is empty" — indistinguishable from a genuinely empty note — and that
file records that on the *update* path there is **no error at all**. (I did not verify the
update-path silence live; no test in this repo asserts it.)

**Therefore: do not run `sync` against any live collection between Phase 1 and Phase 4.** The
durable fix is the field-name preflight (HANDOFF open item 3), which stays deferred; Phase 3's
installer closes this particular window by making the five owned types exist.

---

## 8. `Retype`, and why it is a hard prerequisite

**`SyncAction.Retype` is emitted and cannot be executed.** `plan/Planner.scala:171-175` emits
it whenever the observed note type differs from the desired one; `plan/Executor.scala:112-124`
raises `AnkiError.UnsupportedOperation` for every one. `Anki[F]` has no operation that could
carry it out (verified: the trait's methods are `noteTypeNames`, `fieldNames`,
`findNotesByTagPrefix`, `notesInfo`, `addNote`, `updateNoteFields`, `addTags`, `removeTags`,
`cardsOf`, `deckOf`, `changeDeck` — nothing else).

The moment `Marker.NoteTypes.Basic` becomes `"Obsidian Basic"`, every already-synced note
plans as a `Retype`. A navigator reported 43 such notes in `claude-POC-test`. Per
`Executor.run` these are collected as per-action failures so the run continues and reports
them — nothing is corrupted — but **no note is migrated**.

Also settled and not in dispute: IN-FLIGHT.md records a live probe showing `updateNoteModel`
preserves the card id, `type`/`queue`/`interval`/`factor`/`reps`, and the review-log entry. So
retyping does not cost review history. It is simply unbuilt.

### The exact contract, read out of the add-on this session

`updateNoteModel` (add-on lines 849–899), verbatim behaviour:

```python
new_tags = note.get('tags', [])
...
anki_note.fields = [''] * len(new_model['flds'])
for name, value in new_fields.items():
    for anki_name in anki_note.keys():
        if name.lower() == anki_name.lower():
            anki_note[anki_name] = value
            break
anki_note.tags = new_tags
```

Three consequences, all load-bearing:

1. **Omitting `tags` does not preserve them — it ERASES them.** `anki_note.tags = new_tags` is
   unconditional and `new_tags` defaults to `[]`. This is *unlike* `updateNote`, where
   omitting the key preserves. For this tool, erasing tags erases `src::`, making the note not
   merely unmatched but **unenumerable** — invisible to lookup, reconciler and prune,
   permanently. The **whole** tag set must be sent, foreign tags included (`leech` is applied
   by Anki's own scheduler; HANDOFF records `marc-put-this-here` as a real observed foreign
   tag).
2. **Every field is blanked first.** The **whole** field set must be sent.
3. **A field name the new model does not have is silently ignored** — the inner loop simply
   never assigns. So a misspelled field name leaves that field empty with no error. A
   preflight against `fieldNames(to)` before the call is **mandatory, not optional**.

### The shape

`SyncAction.Retype` must carry: `key`, `noteId`, `from`, `to`, the new `fields`, and the full
tag set. The tag set is split in two so that `anki/Anki.scala`'s stated ownership asymmetry
stays meaningful — "reads carry raw `String` tags because Anki returns everything… writes take
`OwnedTag` because the tool may only ever set its own":

- `ownedTags: NonEmptyVector[OwnedTag]` — the `src::` identity and the new `sha::`, authored;
- `preservedTags: Vector[String]` — observed foreign tags, echoed **verbatim**, never minted.

The tool still cannot author a foreign tag; it can only pass back one it read. `Planner`
already has `existing.note.tags` and `sourced.spec.fields` in hand at the point it emits
`Retype`, so no new query is needed.

`Anki[F]` gains one operation; `InMemoryAnki` implements it (and must reproduce the
blank-then-fill behaviour, or the fake and the wire disagree); `AnkiConnect` implements it over
`updateNoteModel`; `Executor`'s raising arm is replaced.

---

## 9. Deliberately left for later

Named as deferred, so nobody treats any of them as an oversight:

- **Orphan suspension.** Ruled 2026-08-19, still not built. `Anki[F]` has no
  `suspend`/`unsuspend`, and `Unflag` must unsuspend. This matters here because HARD
  CONSTRAINT 1's consequence — a moved key is an orphan that stays in the daily review
  rotation — is only true *because* suspension is missing.
- **The `prune` command.**
- **A general field-name preflight for every write path** (HANDOFF open item 3). Phase 3's
  installer covers the note-type install path only.
- **The `Marker.Table` failure-key hole**, documented in `Extractor.scala`: a `buildSpecs`
  failure on a table section is recorded at the *section* key while the table's cards key one
  or two segments deeper, so `Planner:211`'s `accountedFor` claims none of them and all nine
  go to `SyncAction.Flag`. Untouched here. It needs a key *set* on the failure record.
- **The formatter and its keybinding** (IN-FLIGHT item 6).
- **Whether Context should be suppressed on Concept-Descriptor's first template** — ruled
  "no" above; reversible, Marc's to overrule.
- **Migrating notes in profile `User 1`.** Out of scope entirely. The tool writes only to its
  own five types from now on; nothing in Marc's real collection is touched by any of this.
- **Amending `srs-obsidian-anki/CARD-MODEL.md`.** Its §Lists still says progressive disclosure
  "cannot be expressed by one Anki note" and "requires generating N notes" — both false, and
  the second is the opposite of what is wanted. Its §"Known defect in the existing note type"
  and REQUIREMENTS.md §Accepted 3 may also be stale (§10 item 3). Document changes are Marc's
  call, dated.

---

## 10. Everything I am unsure of

Ordered roughly by how much damage a wrong guess does.

1. **Anki's card-generation rule for a non-conditional field reference on a template front**
   — the Concept-Descriptor template-3 hazard in §6. I know the documented rule as "a card is
   generated only when the front renders non-empty", and `{{Context}}` is a genuine field
   reference, but **I did not run it**. If I am wrong in the permissive direction nothing bad
   happens; if I am wrong in the other direction, a snippet placed outside the wrapper mints a
   spurious third card on every plain `3way` note. Test named in §6; treat inside-the-wrapper
   as mandatory until measured.

2. **The actual template NAMES on the live `3 way Concept-Descriptor` and `Cloze Sequence`.**
   A navigator reported `Card 1` / `Card 2` / `Card 3`; a superseded document shows
   descriptive names. **I did not read the collection.** This matters more than it looks
   because `updateModelTemplates` silently no-ops on an unrecognised name — a wrong name here
   is a repair that reports success and changes nothing. Phase 0 must capture them.

3. **Whether the live `3 way Concept-Descriptor` still has the back-side defect.**
   CARD-MODEL.md §"The three-field family" and REQUIREMENTS.md §Accepted 3 both say all three
   templates render the answer on both sides. A navigator reported reading the live model and
   finding all three backs already using the idiomatic `{{FrontSide}}<hr id=answer>`. **These
   cannot both be current and I cannot say which is.** Whoever writes the
   `concept-descriptor` manifest must decide explicitly whether to preserve or fix whatever
   Phase 0 finds — silently fixing it changes every card face.

4. **Whether the update path is really silent on an unknown field name.**
   `anki/AnkiConnect.scala:122-124` records it; no test in this repo asserts it; I did not run
   it. It is the reason for the "do not sync between Phase 1 and Phase 4" instruction. Verify
   with an `updateNote` against `claude-POC-test` using a deliberately misspelled field key,
   then read the note back.

5. **Which of `InMemoryAnki.scala:198-200` and `Marker.scala:150-152` is stale** about whether
   the stock field names were verified live. One `modelFieldNames` call per stock type
   settles it (Phase 0).

6. **My hand-derived fixture arithmetic** — 5 empty / 50 non-empty Contexts of 55. Derived
   from heading levels and the golden, **not run**. The golden diff gives the real number.

7. **Whether the night-mode class is `.nightMode`, `.night_mode`, or both** on the Anki
   versions Marc reviews with. I wrote both selectors defensively and verified neither. Toggle
   night mode on one card of each new type.

8. **12px legibility on a phone.** `font-size: 0.6em` against a 20px card is an educated
   starting point, not a measured one. Marc should see one real card before it is frozen.

9. **Whether AnkiMobile and AnkiDroid honour `{{#Field}}` conditionals.** I believe they do —
   it is core template syntax, not an add-on feature — but I did not verify, and the whole
   design leans on it.

10. **Whether `modelFieldAdd` on a model holding notes preserves the other fields' values.** I
    read the code (add-on 1433: add-if-absent, then `save_model`) and expect it appends an
    empty field to every note. Not verified. Test it on a throwaway model in
    `claude-POC-test` before touching the two that hold real notes.

11. **Whether Anki permits more than one template on a cloze model**, and how
    `updateModelTemplates` behaves on one. I read the AnkiConnect Python; I did not read
    Anki's own `models.py` and did not test. This bears on the `cloze` manifest having exactly
    one template entry, which I assert as a **prediction**. `modelTemplates("Cloze")` in
    Phase 0 settles it.

12. **Classpath namespace collisions** for `/note-types/…` against a dependency jar. The
    `resources/` wrapper makes this unlikely, but I did not enumerate what laika, cats,
    snakeyaml, decline, http4s, circe or munit ship. Cheap detection: T1.4 already reads every
    named file, so have it assert a known first line per file — a shadowing jar then shows up
    as wrong content rather than as a plausible template.

13. **Whether `//> using resourceDir` accepts multiple directives, or interacts with
    packaging.** I verified exactly one directive, under `run` and under `test`, on Scala
    3.8.4. I tested nothing else, and I found no packaging recipe for this tool.

14. **The exact `createModel` failure mode when a model name already exists.** I read the
    raise (`'Model name already exists'`, add-on line 1127) but did not exercise it, so I do
    not know how `AnkiConnect.scala`'s error classifier renders it. It decides whether §5's
    existence check is a convenience or a necessity — treat it as a necessity either way,
    because §5 step 2 needs the check for a different reason.

15. **The Cloze context leak.** Including `title` for a `Cloze` card means a heading that
    *names* what is blanked prints the answer on the front. I checked all five fixture cloze
    headings — "The three layers, blanked", "Bones of the forearm", "Bones of the hand, in two
    parts", "Anatomy of a long bone", "Cells that remodel bone" — and **0 of 5 leak**. Five
    data points, not a guarantee. The mitigation is an authoring rule of the same class
    CARD-MODEL already states for `3way` headings ("a facet name, meaningless alone"); the
    alternative is to use `chain` alone for Cloze and lose the single largest context gain in
    the design. **This is Marc's call and I have not made it** — the table in §6 currently says
    `chain :+ title`. The same residual exists for a table whose heading names a row; there is
    no fixture instance and I see no mechanical mitigation.
