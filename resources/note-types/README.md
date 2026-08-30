# Note type definitions

The five Anki note types this tool installs and writes to, one directory each. Every file in
here is the **exact text that goes into a collection** — a template body, a stylesheet, or the
`manifest.json` that says which is which.

These files are LOADED AND SENT, not merely a specification. `anki/NoteTypeAssets.scala` reads
them off the classpath, `anki/NoteTypeInstall.scala` creates the missing note types through
AnkiConnect's `createModel`, and the `install-note-types` command drives that. Two test suites
tie them to the Scala: `anki/NoteTypeAssets.test.scala` compares every manifest against
`model/Marker.scala` and every template against its own field list, and
`anki/NoteTypeInstall.test.scala` drives the installer against the in-memory fake collection.

_Amended 2026-08-21. This paragraph previously said nothing here was read by the Scala code._

These files HAVE been installed into a live collection. `install-note-types` was run against
profile `claude-POC-test` on 2026-08-21 — plain, then `--repair`, then a migrating `sync`; the
run and its before/after measurements are recorded in `docs/history/IN-FLIGHT.md` (43 notes before and 43
after, 21 moved to a new note type, **0** card ids changed, **0** scheduling values changed, 38
of 43 carrying a populated `Context`). Re-read later the same day, read-only, via `modelNames`,
`modelFieldNames`, `modelTemplates` and `modelStyling`: that profile holds all five note types,
and for every one of them the field list, both sides of every template and the stylesheet are
**byte-identical** to the files in this directory. `findNotes` + `notesInfo` over the five types
returns 44 notes, 38 with a non-empty `Context` — one note more than the run recorded, added
since by something these read-only calls cannot account for.

_Amended 2026-08-21 (second amendment). This paragraph previously said no agent had ever run
`install-note-types` against a live collection._

What is still NOT done: nobody has reviewed a card produced by one of these note types on a
screen.

---

## Why the tool has its own note types

Before this, the tool wrote notes onto Anki's **stock** `Basic`, `Basic (and reversed card)`
and `Cloze` — the same three note types the rest of Marc's collection uses. That made every
template change global: adding a breadcrumb to the question side of a synced card would have
added it to every unrelated card in the collection as well.

Ruled by Marc: the tool gets its own five, named below. A template change is then local to
cards this tool created, and the rest of the collection cannot be affected by anything the
tool does to a template.

The five names are Marc's, and they are exact:

| Directory | Model name (`manifest.json` → `name`) | Templates | `isCloze` | Where it came from |
|---|---|---|---|---|
| `basic/` | `Obsidian Basic` | 1 | false | copy of Anki's stock `Basic` |
| `basic-and-reversed/` | `Obsidian Basic (and reversed card)` | 2 | false | copy of Anki's stock `Basic (and reversed card)` |
| `cloze/` | `Obsidian Cloze` | 1 | **true** | copy of Anki's stock `Cloze` |
| `cloze-sequence/` | `Obsidian Cloze Sequence` | 1 | false | rename of the existing `Cloze Sequence`, itself derived from an MIT upstream |
| `concept-descriptor/` | `Obsidian Concept-Descriptor` | 3 | false | rename of the existing `3 way Concept-Descriptor` |

### The two renames are done by hand, and cannot be automated

`Cloze Sequence` and `3 way Concept-Descriptor` already exist in the collection and hold real
notes with real review history. They are **renamed**, not recreated.

**AnkiConnect has no action that renames a model.** Verified 2026-08-21 by asking the running
add-on for its own action list (`apiReflect`, 121 actions): it offers `modelFieldRename` and
`modelTemplateRename`, and nothing that renames the model itself. So the two renames are
Marc's to perform in Anki's *Tools → Manage Note Types*. No code here attempts them or works
around them.

Each of those two manifests therefore carries `renamedFrom` with the exact old name. That key
exists for one hazard: `createModel` raises only when the *new* name already exists, so a
naive create-if-missing installer, run before the hand-rename, would leave the collection
holding **two** note types — a new empty one and the old populated one, every note still on
the old — with no error anywhere. An installer that sees `name` absent *and* `renamedFrom`
present must refuse and name the pair.

**`renamedFrom` narrows that hazard; it does not close it.** The guard is an exact string
match — `asset.renamedFrom.filter(inCollection.contains)`, in `NoteTypeInstaller.statusOf`
(`anki/NoteTypeInstall.scala:275`, read 2026-08-21). A hand-rename that MISSPELLS the new name
therefore defeats it in silence: neither `name` nor `renamedFrom` is in the collection, the type
is classified `Absent`, and `NoteTypeInstaller.install` creates every `Absent` type
(`anki/NoteTypeInstall.scala:441`) — leaving an empty duplicate beside the populated,
misspelled one, which is precisely the outcome `renamedFrom` exists to prevent. Nothing compares
names loosely and nothing warns. So the hazard is not "cannot happen"; it is "cannot happen when
the hand-rename is spelled exactly right". A misspelled rename was reported during the live run
of 2026-08-21; it is not visible in the collection now — `modelNames` today lists only
`Obsidian Cloze Sequence` — so that half of this paragraph rests on the report, while the
mechanism above was read out of the code.

---

## Layout

```
resources/note-types/
  README.md                      this file
  basic/
    manifest.json
    styling.css
    templates/card-1.front.html
    templates/card-1.back.html
  basic-and-reversed/
    manifest.json
    styling.css
    templates/card-1.front.html   templates/card-1.back.html
    templates/card-2.front.html   templates/card-2.back.html
  cloze/
    manifest.json
    styling.css
    templates/cloze.front.html    templates/cloze.back.html
  cloze-sequence/
    manifest.json
    styling.css
    LICENSE.upstream              MIT, the one wholly-upstream file
    README.md                     provenance and the two upstream modifications
    templates/cloze-sequence.front.html
    templates/cloze-sequence.back.html
  concept-descriptor/
    manifest.json
    styling.css
    templates/card-1-recall-concept.front.html      … .back.html
    templates/card-2-recall-description.front.html  … .back.html
    templates/card-3-recall-descriptor.front.html   … .back.html
```

`templates/` exists even where a type has a single template, so that adding a second one is
two files and one array entry rather than a restructure, and so that no reader has to learn
two layouts.

**This directory moved here from `obsidian-anki-custom-sync/note-types/` on 2026-08-21**, in
the commit that added the loader, together with one `//> using resourceDir ./resources` line in
`project.scala`. The wrapper directory is what namespaces these files on the classpath: pointing
`resourceDir` at `note-types/` itself would put `/basic/`, `/cloze/` and a bare `/README.md` at
the classpath ROOT, where a dependency shipping the same path would shadow ours — and a shadowed
template is not an error, it is a note type whose cards render someone else's markup.

---

## The invariant everything else rests on

**Every file inside a type's directory is byte-identical to what is in, or goes into, the
collection.** No templating, no variable substitution, no shared partial concatenated at
install time.

Three consequences, stated so that nobody removes the duplication later:

- Each type's `styling.css` is that type's **complete** CSS. The `.context` rule below is
  therefore copied verbatim into all five files. Five copies look redundant; they are the
  price of the invariant.
- A drift check is `modelTemplates(name)` / `modelStyling(name)` compared to these files with
  string equality. Nothing is re-derived, so a mismatch is a diff a human reads.
- The repository is a faithful backup. That is the stated reason `cloze-sequence/` was
  committed in the first place (commit `72e8761`, whose message records that the note type
  existed only inside a live collection, so deleting one profile would have destroyed it).

Rejected for the same reason: a shared `note-types/context.css` concatenated at install. It is
the obvious de-duplication and it breaks the invariant — what the repository shows would no
longer be what the collection holds.

### File conventions that follow from it

- **Template files carry no trailing newline.** What the file holds is what is sent, so a
  trailing newline would be a trailing newline in the template. The two captured
  `cloze-sequence` templates have none — checked 2026-08-21 by comparing both files with what
  `modelTemplates("Obsidian Cloze Sequence")` returns from profile `claude-POC-test`: neither
  side ends in a newline, on disk or in the collection, and both are byte-identical to the
  collection's copy. That identity was briefly broken and has been restored: the front gained
  the `Context` line here after it was captured, and `install-note-types --repair` is what put
  that line into the collection. The authored templates match the captured ones.
- **Stylesheets end with a newline**, as Anki's own stock stylesheets do.
- **The `.context` block is preceded by exactly one blank line** in all five stylesheets. The
  blank line is what marks it as this tool's addition to an otherwise-captured stylesheet.

---

## `manifest.json`

One per type, parsed by `anki/NoteTypeAssets.scala` with a **strict decoder and no defaults**:
a manifest missing a key fails loudly rather than having a plausible value filled in, and so
does a manifest carrying a key that is not listed below. The unknown-key half is not fussiness —
a misspelled `renamdFrom` would otherwise decode cleanly, with the rename hazard described above
silently disarmed.

```json
{
  "name":        "Obsidian Cloze Sequence",
  "renamedFrom": "Cloze Sequence",
  "isCloze":     false,
  "fields":      ["Title", "Text", "Context"],
  "styling":     "styling.css",
  "templates": [
    { "name":  "Cloze Sequence",
      "front": "templates/cloze-sequence.front.html",
      "back":  "templates/cloze-sequence.back.html" }
  ],
  "derivedFrom": {
    "url":         "https://github.com/tekinosman/cloze-sequence",
    "licence":     "MIT",
    "licenceFile": "LICENSE.upstream",
    "modified":    true
  },
  "capturedFrom": { "profile": "claude-POC-test",
                    "action":  "modelTemplates, modelStyling",
                    "date":    "2026-08-21" }
}
```

| Key | Meaning |
|---|---|
| `name` | **The model name.** `createModel` sends this; `model/Marker.scala`'s `NoteTypes.*` is what Scala pattern-matches on. Two homes for one name, so a test can fail when they disagree. |
| `renamedFrom` | Present on exactly the two hand-renamed types, absent otherwise. See above. |
| `isCloze` | Mandatory, no default, **never inferred**. |
| `fields` | Ordered. Order is load-bearing twice: `createModel`'s `inOrderFields`, and `Planner.contentHash`, which hashes field names in order — so a wrong order makes every run plan an update for every card. |
| `styling` | Path, relative to the type's directory, of that type's complete CSS. |
| `templates` | An ordered **array of objects**, never a name-keyed object: template order is the card ordinal in Anki, and JSON objects carry no guaranteed order. |
| `templates[].name` | The template's name **as it exists (or will exist) in the collection**. Not derived from the file name, and the file name is not derived from it. |
| `derivedFrom` | Present when the type comes from a third party. Absent means it is not vendored from anyone. |
| `capturedFrom` | Present on the two renamed types. Records that those templates were **read out of a live collection rather than authored**, so that a later reader does not "tidy" them. |

### `isCloze` is never inferred, and `Cloze Sequence` is why

`Cloze Sequence` has "Cloze" in its name, defines `.cloze` and `.hidden-cloze` in its CSS, and
calls its hidden items clozes — and it is **not** a cloze note type. Its front template renders
`{{Text}}`, a plain field reference, not `{{cloze:Text}}`. Verified live 2026-08-21:
`findModelsByName` reports `type=0` for `Cloze Sequence` and `type=1` for stock `Cloze`. Every
available heuristic — the name, the CSS, the vocabulary — gets this one exactly backwards,
which is why the flag is a mandatory field rather than something computed.

### Why the template *name* must be exact

AnkiConnect's `updateModelTemplates` looks each of your templates up **by name** in the model
it is updating, and ignores names it does not recognise. A wrong name is therefore a
clean-exit no-op: no error, nothing written, and a repair that reports success while changing
nothing. That is why the two renamed types' template names were captured verbatim rather than
invented:

- `Cloze Sequence` → one template, named `Cloze Sequence`.
- `3 way Concept-Descriptor` → three templates, named
  `Card 1: Descriptor+Description -> Concept`,
  `Card 2: Concept+Descriptor -> Description`,
  `Card 3: Concept+Description -> Descriptor`.

(Both captured 2026-08-21 from profile `claude-POC-test` via `modelTemplates`. An earlier
report that these were called `Card 1` / `Card 2` / `Card 3` was wrong.)

The three copies of stock types are created from scratch, so their template names are ours to
choose; they are `Card 1`, `Card 1`/`Card 2` and `Cloze`, matching Anki's own stock names so
that the Browse screen reads familiarly.

---

## Ours, captured, and vendored

| Type | Model name and template names | Template bodies | CSS |
|---|---|---|---|
| `basic`, `basic-and-reversed`, `cloze` | ours | copied verbatim from Anki's stock types in profile `claude-POC-test`, 2026-08-21, then prefixed with the `Context` snippet | stock, **minus** `color` and `background-color`, plus the `.context` rule |
| `cloze-sequence` | renamed; template name captured | upstream, modified — see `cloze-sequence/README.md`; front gains one `Context` line | as it was, plus the `.context` rule |
| `concept-descriptor` | renamed; template names captured | captured verbatim, then the `Context` snippet added to all three fronts | as it was, plus the `.context` rule |

`cloze-sequence/LICENSE.upstream` is the only file here that is wholly someone else's.

**A correction worth recording**, because two ratified documents still say otherwise:
`srs-obsidian-anki/CARD-MODEL.md` and `srs-obsidian-anki/REQUIREMENTS.md` (Accepted 3) both
describe a defect in `3 way Concept-Descriptor` — that "all three templates render the answer
on both sides of the divider". That is **not** true of the live model as captured on
2026-08-21: all three backs are already the idiomatic
`{{FrontSide}}<hr id=answer>{{Answer field}}`. The templates here preserve what is actually
there. Amending those two documents is Marc's call.

---

## The `Context` field

Every one of the five gains a field named `Context`, **last** in the field list.

It holds the heading chain that led to the card, in display casing, joined with `" › "` — down
to but not including whatever the card's own face already shows. It exists because a card can
otherwise be unanswerable. Measured example from a live collection: heading path
`body shapes / cranial bones and their sutures / frontal / anterior border`, card face
`Concept: Frontal · Descriptor: Anterior border → Orbital rim`. Frontal *what*? The one segment
that disambiguates — "cranial bones and their sutures" — was dropped.

**It is a real field, not something parsed out of the `src::` identity tag.** The tag carries
the *canonicalised* path — lowercased and percent-encoded — because identity is deliberately
severed from display, so a tag-derived breadcrumb would read in permanent lowercase. The
extractor already computes the properly cased chain and currently discards all but its last
element.

**The field is populated.** `extract/CardContext.scala` renders the properly-cased chain,
`extract/Extractor.scala` and `extract/Tables.scala` pass the ancestor titles into the card
specs, and `CardSpec.fields` (`model/CardSpec.scala:275-326`, read 2026-08-21) emits
`Marker.ContextField` on every one of its five arms. Measured in profile `claude-POC-test` on
2026-08-21 with `findNotes` + `notesInfo`: 38 of the 44 notes on these five types carry a
non-empty `Context`, the first of them reading `Messaging Patterns › Cost / benefit`.

_Amended 2026-08-21. This paragraph previously said "Populating the field is a separate slice;
nothing writes it yet."_

`Context` is **last** on every type for three reasons: Anki's Sort Field defaults to field 1,
and a breadcrumb there would fill the Browse list; `modelFieldAdd` appends; and appending
keeps every existing field position stable.

### The snippet

One line, byte-identical everywhere it appears:

```html
{{#Context}}<div class="context">{{Context}}</div>{{/Context}}
```

It goes on the **question** side, and reaches the answer side through `{{FrontSide}}` — except
on `Obsidian Cloze`, whose back template does not use `{{FrontSide}}`, so there it is written
twice.

The `{{#Context}}` wrapper means an empty `Context` emits no `<div>`, and therefore no rule and
no margin, on cards whose chain is empty.

Two placement rules that are not cosmetic:

1. ⚠️ **On `concept-descriptor`'s third template the snippet sits INSIDE
   `{{#ThreeWay}}…{{/ThreeWay}}`.** Anki generates a card only when its front renders
   non-empty, and `{{Context}}` is a real field reference. Placed *outside* the wrapper, every
   plain `#flashcard/3way` note would generate a **third card** showing a breadcrumb and
   nothing else, answered by the Descriptor. It would look like a card.
   **Half of this is now measured, read-only.** In profile `claude-POC-test` on 2026-08-21,
   `findNotes` + `notesInfo` over the 21 notes on `Obsidian Concept-Descriptor` (counting each
   note's `cards`): the 16 notes with `ThreeWay` empty and `Context` non-empty have exactly
   **2** cards each, and the single note with `ThreeWay` set has 3. So the snippet where it
   actually sits — inside the wrapper, see
   `templates/card-3-recall-descriptor.front.html` — does not generate a third card, which is
   the check this paragraph used to ask for. The other half is still **unmeasured**: that
   placing the snippet OUTSIDE the wrapper WOULD generate that third card is a prediction from
   Anki's card-generation rule, not an observation — measuring it means writing a deliberately
   mis-placed template into a collection, which nobody has done.
2. **On `cloze-sequence` the snippet is a new first line, above `<h4>{{Title}}</h4>` and
   outside `<div id="text">`.** That front template hides every `#text li` and the stylesheet
   dims `#text` to `opacity: 0.5`; context is the frame, not dimmed answer.

### The styling

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

Three independent signals, because any one alone is weak on a phone screen: `text-align: left`
against the card's centred text (a *centred* breadcrumb above a centred prompt reads as a
title, i.e. as part of the question); 0.6em mid-grey for subordination; and a hairline rule
lighter than `<hr id=answer>`.

Two things about this block that are **not verified**: whether 0.6em is legible on a phone
(it is an educated starting point, not a measurement), and whether the night-mode class is
`.nightMode`, `.night_mode`, or both on the Anki versions Marc reviews with. Both selectors
are written. One data point in favour of `.nightMode`: Anki's own stock `Cloze` stylesheet, as
captured on 2026-08-21, uses `.nightMode .cloze`.

### One deliberate omission, and its limit

The three newly-created types drop stock's `.card { color: black; background-color: white; }`,
so that Anki's night mode is not defeated on a phone. That is a ruling in the design document,
and it is scoped there to the **three newly-created types**.

`cloze-sequence` still hardcodes `background: #fff; color: #111`, and `concept-descriptor`
still hardcodes `color: black; background-color: white` — both as captured. The same
night-mode argument applies to them, but their stylesheets are what Marc has already reviewed
live, so they are left alone here. Whether to drop those declarations too is an open question
for Marc, not a decision taken in this slice.
