# Editing a card at its source

_Written 2026-08-27, from a design conversation between Marc and Claude. **Nothing here is
built.** Claims marked VERIFIED were established by reading the file cited or by running
something in this session; everything else is reasoning or expectation, and says so. It follows
this repository's convention of opening with the answer rather than the three-layer TLDR /
Summary / Full form, because its siblings — `ANCHORS-BELOW-A-HEADING.md`, `EVOLVABILITY.md` —
do._

---

## The one-paragraph answer

Reviewing is when you discover a card is badly worded, and Anki is the one place you must not
fix it: an edit made there is invisible to the vault and is overwritten by the next sync. So
pressing **Edit** on a card this tool created should open **the note in Obsidian**, not Anki's
editor. Anki supports this properly — `DialogManager.register_dialog` is documented add-on API
and every route to the reviewer's Edit passes through one function — so the mechanism is
settled. What is not settled is where the identity tag gets **decoded**, because the add-on is
Python and the codec is Scala, and a second implementation of card identity is the defect class
this project fights hardest.

---

## 1. What is being built

An Anki add-on that intercepts **Edit Current** and, for a note this tool created, opens the
source note in Obsidian instead of Anki's note editor. For every other note in the collection —
which for Marc is the majority — the behaviour is exactly what it is today.

The discriminator is free: every note this tool creates carries an identity tag beginning
`src::` (the tag that binds a markdown card to its Anki note; see `model/CardKey.scala`). A note
without one is not ours and is handed straight to the real editor.

**Scope, decided:** the reviewer's Edit only. Anki's **Browse** window has its own editor,
reached by a different path, and it is deliberately left alone — editing a note in the browser
is usually a deliberate act on a note you are looking at, not a reflex mid-review.

### The edit this makes one keystroke away is the expensive one

The opening paragraph names the moment: *reviewing is when you discover a card is badly worded.*
The natural fix for a badly worded card is to **reword its heading** — and that is precisely the
edit this tool charges for. `README.md` states it plainly: a card's identity is derived from the
frontmatter `id` plus the heading path, there is no rename detection, and **rewording a marked
heading retires that card and mints a new one with no review history**. The old note is flagged
`orphaned::` and suspended rather than deleted, so nothing is lost that cannot be reconciled by
hand — but the scheduling is gone.

So this feature shortens the path to the one edit that costs something. That is not an argument
against it; it is an argument for saying so here rather than letting it be discovered on the
first card somebody rewords.

**What is free, and what is not:**

| Edit | Cost |
|---|---|
| Rewriting the **body** of a marked section | none — new content, same key, one `Update` |
| Renaming the **file** | none for a heading card (the key holds the frontmatter `id`, not the name); none at all for a relation card, whose face is the only thing that changes |
| Rewording a **`cdd` descriptor's body**, or the text inside a **labelled** cloze `==1\|…==` | none — the label is the key |
| Rewording a **marked heading** | the card is retired and replaced, history gone |
| Editing the text of an **unlabelled** cloze `==…==` | same — the text *is* the key |

The mitigation is authorial rather than mechanical: when the wording is wrong, prefer fixing the
body; when the heading itself is wrong, know that you are trading the card's history for a
better question, and that the trade is sometimes worth making.

### The loop this feature does not close

Pressing Edit opens Obsidian. **Nothing in Anki changes until `sync` runs.** The card in front of
you keeps its old wording for the rest of the session, and the reviewer is not refreshed.

The whole cycle is therefore: press Edit → the note opens in Obsidian → edit it → run
`obsidian-anki-sync sync` → the card updates on the next review. Whether that is acceptable, or
whether the add-on should eventually offer to run the sync itself, is **undecided** and is
recorded in §9. What is decided is that this document states the cycle rather than implying the
edit lands immediately.

---

## 2. The mechanism (VERIFIED)

Read on 2026-08-27, in **the `aqt` package Anki is actually running**:
`~/Library/Application Support/AnkiProgramFiles/.venv/lib/python3.13/site-packages/aqt`, against
**Anki 25.09.5** (`buildhash 217701ba`) on **Python 3.13**.

_That path was arrived at by correction and the correction is worth keeping. This section first
cited `…/AnkiProgramFiles/cache/archive-v0/hUdGwXXH-AwUHOWW9ViRZ/aqt`, found by search and
labelled UNVERIFIED as to whether it was the running copy. It was not: `archive-v0` is uv's
download cache and holds forty-five such directories, while the live process
(`/…/.venv/bin/python -c "import aqt, sys; …; aqt.run()"`) loads from the virtual environment.
Every line number below was then re-checked against the venv copy and is identical, so the
conclusions never moved — but a citation into a download cache would have rotted silently._

**Line numbers are pinned to 25.09.5.** They are the kind of claim that goes stale on an upgrade
without anything failing, so the version is recorded here rather than left implicit.

- `AnkiQt.onEditCurrent` (`main.py:1289-1290`) is two lines:
  `aqt.dialogs.open("EditCurrent", self)`.
- Everything routes through it — the `e` shortcut (`reviewer.py:611`), the More menu
  (`reviewer.py:682`), and a Korean-layout variant (`reviewer.py:580`).
- `aqt.dialogs` holds a registry: `_dialogs["EditCurrent"] = [editcurrent.EditCurrent, None]`
  (`__init__.py:126-137`).
- `DialogManager.register_dialog(name, creator, instance)` (`__init__.py:188`) opens its own
  docstring with *"Allows add-ons to register a custom dialog…"*.

So the add-on re-registers `EditCurrent` with its own creator: inspect the note, open Obsidian
if it is ours, otherwise delegate to `editcurrent.EditCurrent`. One place, every route covered,
a supported extension point rather than a monkeypatch.

**SETTLED 2026-08-27 — THERE IS NO PHANTOM.** The concern was that `register_dialog`'s docstring
requires a registered dialog to define close behaviour, so a creator that fires a URL and returns
nothing might leave a stale entry behind. Read in the same 25.09.5 copy: `open` assigns whatever
the creator returned into the registry's instance slot, so returning nothing writes the *empty*
value — which is the state the manager already uses to mean "not open", and is what makes the
next press re-enter the creator instead of trying to raise a window that does not exist.
`markClosed` rebuilds the entry from the creator, so a shim survives the real dialog being
opened and closed. The docstring's requirement binds a dialog that IS a window; it does not bind
a creator that declines to make one.

**One residual, and it is about other people's add-ons rather than Anki.** Returning nothing
still fires `gui_hooks.dialog_manager_did_open_dialog` with an empty instance. Anki itself does
not read it. An add-on subscribed to that hook and assuming a window might. Cheap to check if
anything misbehaves, and not worth pre-empting.

---

## 3. The Obsidian side (VERIFIED)

[Advanced URI](https://github.com/Vinzent03/obsidian-advanced-uri) by Vinzent03. Read from its
source on 2026-08-27 rather than from its documentation, which is thin on this:

- the parameter is **`uid`** — `src/types.ts:161`, `uid?: string`;
- the setting that names which frontmatter key holds it is **"UID field in frontmatter"** —
  `src/settings.ts:122`;
- the scheme is **`obsidian://adv-uri`**;
- `heading` and `block` parameters exist for navigating within a file.

Point the UID setting at `id` and it reads the frontmatter every card-bearing note already
carries.

**A switch to know about:** "Use UID instead of file paths" governs the plugin's own
URI-*generating* commands and can write a UID into frontmatter when one is missing. Ours are
never missing. But given this repository's central property — *nothing generated is ever written
back into the markdown* — the existence of a setting that writes to frontmatter deserves to be
recorded rather than discovered.

### If the plugin is not installed, nothing happens and nothing says so

An `obsidian://adv-uri?…` URL with Advanced URI absent opens Obsidian and does nothing at all.
The operating system reports success — it launched the handler — so **the add-on cannot detect
this from the URL**. Pressing Edit would appear to do nothing, which is the failure shape this
whole project is built against, arriving through a dependency rather than through our own code.

A cheap mitigation exists and should be part of the first slice: the add-on already knows the
vault path (it passes it to `locate`), so it can check for
`<vault>/.obsidian/plugins/obsidian-advanced-uri/` once at startup and say so plainly if it is
missing. That is a directory-layout dependency of the same standing as `VaultRoot`'s reliance on
`.obsidian/` — a convention rather than a published contract, acceptable for the same reason:
**it fails loud and wrong rather than quiet and wrong**, and a false negative costs one
dismissible warning.

---

## 4. Where the tag gets decoded — the actual design question

The add-on is Python running inside Anki's process. No Java Virtual Machine, no `TagCodec`, no
`CardKey`. So a Python decoder is **a second implementation of card identity**: the `::` split
grammar, the `/` segment separator, the `/p/` and `/n` marks that distinguish a frontmatter
property and a whole-note card from a heading chain, and the percent-encoding safe set
`[A-Za-z0-9.-]`.

Two implementations of one thing, free to drift, where drift means a card opens the wrong note.
That is `Cloze.collectHighlights` versus `renderWithDeletions` again, and
`Tables.cellSource` versus `CellDisplay.Default`.

### Option A — decode in Python, pin with a conformance corpus

Fast at click time. The copy exists; drift becomes a red test rather than a silent mis-open.
See §6 for what makes such a corpus real rather than decorative.

### Option B — the sync tool writes the finished URI into an Anki field

The add-on becomes trivial and knows nothing about the encoding. **Declined**, and the reasons
compound:

- a new field on all five note types, so `Marker.FieldOrder` grows, so `CardSpec.fields` grows,
  so **every content hash changes and every note is rewritten once**;
- `extract/golden/fixture-cards.txt` moves by 55 records;
- `anki/NoteTypeAssets.test.scala` asserts every declared field is referenced by some template
  ("the field would be stored and never shown"), so a URI field must be *rendered on the card* —
  which reintroduces exactly the click-a-link workflow this feature exists to replace.

### Option C — a `locate` subcommand; the add-on shells out — **CHOSEN, CONDITIONALLY**

`obsidian-anki-sync locate --tag src::… --vault-path …` prints the URI. `TagCodec.decode`
remains the single implementation of card identity, permanently. There is **no cross-language
agreement surface at all**, which is the whole argument.

Its one cost is latency on a keypress, and §5 is about removing it.

**The condition, stated so the choice is not read as settled:** C is chosen *provided* a native
`locate` binary starts fast enough to sit under the `e` key. That number has not been measured
(§5, §10). If it disappoints, A is the fallback and §6 is what makes A responsible. Recording
this as conditional rather than as a decision is the difference between a choice and a bet.

---

## 5. Making option C fast: a native `locate` binary

`README.md` rejected GraalVM native-image for the main tool with a sound reason: this program
parses YAML through snakeyaml, which resolves classes by reflection — the one thing
native-image cannot see without being told — and the failure mode is a binary that builds clean
and breaks on a path only a live run reaches. Measured there: the JVM assembly starts in 0.57s.

**That objection does not reach `locate`.** Decoding an identity tag touches `TagCodec`,
`CardKey`, `CardPath`, `NoteId`, `HeadingSegment`, `PropertyName` and
`cats.data.NonEmptyVector`. No YAML, no Laika, no http4s, no circe. Hand-parse two arguments
instead of pulling in decline and **the reflective surface is essentially nil**.

**BUT THE RISK IS NOT ZERO — IT IS A DIFFERENT RISK, AND IT SITS ON THE ONE FUNCTION `locate`
DEPENDS ON.** `TagCodec.canonical` calls `java.text.Normalizer` for NFC and
`toLowerCase(Locale.ROOT)`. Reflection is not native-image's only failure mode: **locale data,
resource bundles and Unicode tables** are included only in part unless the build is told
otherwise, and those are exactly what those two calls reach for. So the honest claim is not
"nothing can go wrong" but "the snakeyaml objection does not apply, and a second class of
objection does". Both are settled by the same spike — build the binary and round-trip the
hostile inputs `model/CardKey.test.scala` already lists (`Café ☕`, `Cost / benefit`, `a  b`,
`100% of the time`) through it.

**UNVERIFIED: the startup time of such a binary has not been measured.** Expectation is tens of
milliseconds; expectation is not measurement, and this is the single number that justifies
option C over option A. Measuring it is a spike, not a commitment.

### Two hazards of the subprocess route, neither of them exotic

**A GUI-launched macOS application does not inherit a login shell's `PATH`.** Anki started from
the Dock or Finder sees launchd's minimal environment, so
`subprocess.run(["obsidian-anki-sync", …])` raises `FileNotFoundError` while the identical
command works perfectly in a terminal — the classic shape where a feature is "broken for the
user and fine for the developer". **The add-on must take an absolute path from its own
configuration**, and should say which path it tried when the executable is not there.

**The command is invoked with an argv list, never a shell string.** A `src::` tag is data: it
carries `::`, `%` escapes and whatever an author put in a heading. `TagCodec`'s safe set
excludes whitespace and Anki's wildcards, so a well-formed tag is inert — but the add-on may be
handed a hand-edited one, and a decoder is exactly the component that must not assume its input
is well-formed. Passing a list removes the question rather than answering it.

Alternatives surveyed and declined:

| Approach | Why not |
|---|---|
| **JPype** (JVM in Anki's process, via JNI) | Needs a native wheel built against Anki's exact CPython ABI, shipped per platform inside the add-on; a JVM fault takes Anki down with it. |
| **Py4J** (separate JVM over a socket) | Client is pure Python, which is a real advantage — but it means a long-lived JVM gateway beside Anki, with lifecycle and a port, to evaluate one string function. |
| **native-image shared library + `ctypes`** | Tightest possible: no process spawn. More build ceremony than this needs. Kept on the shelf if subprocess latency disappoints. |
| **Change the tag format so a schema tool owns it** | Costs the human-readable tag, which `model/CardKey.scala` rejected explicitly ("base64url was rejected: it would make orphan lists unreadable in Anki's browser"), and re-keys every card in the collection. |

---

## 6. If option A is ever the fallback: what a conformance suite must be

Recorded because A is the fallback, and because the reasoning is not obvious.

**Protobuf is the wrong tool.** It guarantees agreement about *message structure* — named typed
fields and a generated wire format. `TagCodec` is a **grammar plus a normalisation**: the split
grammar; the percent-encoding; and the canonicalisation (NFC, trim, whitespace-collapse,
`toLowerCase(Locale.ROOT)`). Protobuf expresses none of those. Adopting it means changing the
wire format, which is the last row of the table above.

**The industry answer for cross-language *semantics* is a conformance corpus, not codegen.**
Protobuf itself ships a conformance runner; JSON has JSON-test-suite; CommonMark has spec tests;
Unicode ships UCD test files. Every one is inputs plus expected outputs, executed by every
implementation.

Four properties would make one real here:

1. **Generated, not imagined** — built from `PlannerLawTest.genSegmentText`, which already leans
   hostile: `Cost / benefit`, `a_b*c`, `Café ☕`, `a  b`, `100% of the time`, case variants. A
   hand-written list of tricky inputs is a list of the tricky inputs one person thought of.
2. **Negative cases weigh more than positive ones** — a decoder's agreement is mostly about what
   it *refuses*: `src::onlyid`, `src::id::`, `src::n1::/z/whatever`, `src::n1::/p`,
   `src::n1::/n/extra`, a truncated percent escape, a bad hex pair. `model/CardKey.test.scala`
   already holds most of these.
3. **Non-vacuity asserted on both sides** — the guard `model/Marker.test.scala` already uses on
   its regex extraction: *"the extraction is broken, so this test is proving nothing. Fix the
   regex, do NOT delete the assertion."*
4. **Executed in continuous integration by both languages** — one artifact, two consumers.

### The trap, and it is the sharp one

**A regenerable corpus certifies agreement, not stability.** Change `TagCodec`, regenerate the
corpus, and the Python test goes green against the new behaviour. Both implementations now agree
— on something that silently re-keys every card in the collection.

The answer already exists in this repository: `extract/golden/fixture-cards.txt`, with
`DO NOT REGENERATE THIS FILE TO MAKE A FAILING TEST PASS` at the top, counts asserted as
literals in the *test source* where regeneration cannot reach them, and no code anywhere that
writes it. A conformance corpus needs the same treatment — and then it does two jobs: cross
-language conformance, and a second net under the identity codec itself.

### What actually has to agree — less than it looks

The add-on only decodes; it never encodes. And values in a tag are already canonical —
`TagCodec.canonical` runs at construction, before encoding — so a Python decoder that skips
canonicalisation entirely produces identical results **for any tag this tool wrote**. It
diverges only on a hand-edited tag, where refusal is arguably the right answer anyway.

The agreement surface is therefore: split grammar, percent-decode, mark recognition. Perhaps
forty lines.

---

## 7. Two gaps neither option closes for free

### The vault is not in the tag

**Nothing in a `src::` tag says which vault the card came from.** The tag is
`(frontmatter id, card path)` and stops there. Advanced URI needs `vault=<name>`.

So the vault name comes from add-on configuration — one vault, typed once. With a single vault
that is sufficient.

**Corrected 2026-08-27.** This paragraph ended: *"it is the first thing that breaks if two vaults
are ever synced into one collection, and it would break by opening the wrong vault rather than by
failing."* **It is not the first thing that breaks, and it is not the worst.** The reconciler's
own enumeration is vault-blind for the same reason this feature is — `Observer.observe` returns
every note carrying `src::` in the open collection — so a second vault synced into one profile
flags and **suspends the first vault's entire card set** long before any URI is built. The tag
that cannot address a vault is the tag that cannot scope an orphan search.

That constraint therefore belongs to the tool and predates this feature: `README.md`, "ONE VAULT
PER ANKI PROFILE". The add-on's single-vault configuration is downstream of it, not a new limit
this feature introduces — which makes it cheaper to accept than the original wording suggested.

### The identity tag case-folds the frontmatter id (VERIFIED)

`TagCodec.decode` routes the id through `NoteId.fromFrontmatter`, which calls
`TagCodec.canonical` — NFC, trim, whitespace-collapse, **and `toLowerCase(Locale.ROOT)`**. So the
`src::` tag carries a *lowercased* id, while Advanced URI's `uid=` would be matched against the
raw frontmatter value.

Marc's ids come from `crypto.randomUUID()` and are lowercase hexadecimal, so this is inert
today. It stops being inert the moment an id contains an uppercase character.

**SETTLED 2026-08-27 — IT IS CASE-SENSITIVE.** Read out of the Advanced URI 2.0.0 bundle: the
lookup walks every markdown file and compares the configured frontmatter field to the parameter
with an exact string comparison — `Array.includes` for a list-valued field, `==` between two
strings otherwise. Neither folds case.

So the concern above is real, and the failure is worse than "says nothing": a uid that does not
resolve leaves the plugin with no file path, and EVERY branch of its dispatcher that could open
something is guarded on having one. The call therefore falls through to the end and **does
nothing at all** — no note, no error, no notice. Pressing the edit key would appear to be broken.
That is precisely the shape §8 says the add-on's types must make unrepresentable, arriving from
outside the add-on, where its types cannot reach.

**The mitigation is free, because of a decision taken for another reason.** `locate` reads the
note itself in order to resolve an anchor to a line number, so it holds the RAW frontmatter value
and can emit that as the `uid`, rather than the case-folded one recovered from the tag. The
canonical id stays what it has always been — the key — and never leaves Scala. Recorded as a
constraint on Phase 4 in `EDIT-IN-OBSIDIAN-PLAN.md`.

_Marc's ids are lowercase hexadecimal UUIDs, so this is inert today, exactly as the paragraph
above says. It is written down because "inert today" is the state that ends without warning._

---

## 8. Type sketch

Types only. This is a design document; the implementation is a later slice.

### New Scala, for `locate`

**SUPERSEDED 2026-08-27 by `locate/Locate.scala`, which is real code and compiles.** The sketch
below is kept as the record of what was proposed, because one arm of it did not survive contact
with the plugin: `UriTarget.Heading` addressed a note by heading NAME, and Advanced URI matches
a heading by exact equality against its raw text, which the canonical form never equals. The
anchor became a LINE instead, `FrontmatterId` was added to keep the raw id and the canonical one
from being substituted for one another, and the outcome type gained the fourth case §10 asks for.
**Read the file, not this block.**

```scala
// A vault's NAME, which is what Obsidian's URI scheme addresses. Distinct from VaultRoot,
// which is a PATH that has been checked for Obsidian's marker directory.
opaque type VaultName = String

// The shapes a CardPath becomes, for a URI. Total over CardPath, so a fourth anchor kind
// must answer here before this compiles.
enum UriTarget:
  case Note(vault: VaultName, uid: NoteId)
  case Heading(vault: VaultName, uid: NoteId, chain: HeadingPath)
  case Property(vault: VaultName, uid: NoteId)   // opens the note; a property has no anchor

// Opaque, one constructor, one exit — the mechanism content.Html.Fragment already uses.
// Percent-encoding for a URI query is NOT TagCodec's encoding, and the two must not be
// able to touch each other.
opaque type ObsidianUri = String
```

Reused unchanged: `TagCodec.decode`, `CardKey`, `CardPath`, `NoteId`, `HeadingPath`,
`VaultRoot.at`, `Cli`'s validated-option idiom, `Report`'s pure lines-in-lines-out contract,
`Main`'s exit-code contract, and the refusal vocabulary.

**`Note` and `Property` are structurally identical and currently render the same URI**, because
Obsidian has no way to address a frontmatter property. So no test can tell those two arms apart,
and the distinction is **unpinnable today**. It is kept anyway, on the same grounds this project
keeps a type ahead of its implementation: collapsing them would turn "what should a relation card
open?" from a visible decision into an absent one. If Obsidian ever gains a property anchor, the
arm is already there to fill in.

### New Python, for the add-on

Typed under `pyright --strict`.

```python
Verdict = NotOurs | Locatable(uri) | Unreadable(tag, reason)
```

- `NotOurs` → delegate to the real `EditCurrent`. **This is the case that must never break**: it
  is every note in the collection this tool did not create.
- `Locatable` → open the URI.
- `Unreadable` → say so and delegate. Never guess.

Two things the types should make unrepresentable: opening without a decided verdict, and a
verdict that neither opens nor delegates — the shape where pressing `e` does nothing at all.

### How the Python is tested, which is not obvious

`aqt` is importable only inside Anki, so the `register_dialog` wiring **cannot be unit-tested at
all**. Read alone, that makes "test-drive the add-on" sound impossible. It is not — it means the
add-on needs the split `cli/Main.scala` already documents for itself, arriving in the other
language.

**The functional core needs no Anki.** `tag → Verdict`, `Verdict → ObsidianUri`, the URI query
encoding, the `/`-chain to `#a#b` translation: every one is a pure function of strings, driven
directly, with the negative cases from `model/CardKey.test.scala` as its corpus. This is where
test-first actually applies, and it is the part that can be wrong in a way that opens the wrong
note.

**The imperative shell is thin and is exercised by running it.** Registering the dialog, reading
configuration, spawning the subprocess, handing off to `editcurrent.EditCurrent`. The Scala side
already states this compromise in `Main.withChosenVault` — *"note what that leaves untested,
rather than implying otherwise"* — and the same sentence belongs on the add-on's entry point.

**The one case that must never break is the one with no test:** a note carrying no `src::` tag
must reach Anki's own editor untouched. That is every note in the collection this tool did not
create. The verdict function can be tested; the delegation cannot, and so it wants the smallest
possible body — ideally a single call, with nothing between it and `editcurrent.EditCurrent`
that could fail.

---

## 9. Open decisions

1. **Heading navigation now, or note-only first?** Note-only needs percent-decoding one
   component. Heading needs the full path grammar plus translating a `/`-separated chain into
   Obsidian's `#a#b` form. Note-only is most of the value for a fraction of the surface.
2. **Where does the add-on live** — this repository, or its own? If option C holds, this one:
   the `locate` command and the add-on are one contract, and splitting a contract across
   repositories is how the halves drift.
3. **Does the Browse editor stay untouched?** Currently decided yes (§1), on the grounds that a
   browser edit is deliberate where a reviewer edit is reflexive. Revisit if that proves wrong in
   use.
4. **Should the add-on offer to run `sync` after the edit?** §1 records that the loop is not
   closed: an edit made in Obsidian reaches Anki only on the next `sync`. Offering to run it
   would close the loop and would also mean an add-on that WRITES to the collection, which is a
   different and much larger promise than one that opens a URL. Deliberately not decided here.

---

## 10. What must be measured before building

- **Native-image startup for a `locate` binary.** The one number that decides option C over
  option A — which is why §4's choice is recorded as conditional.
- **That the same binary still canonicalises correctly.** The same spike, second half: round-trip
  `Café ☕`, `Cost / benefit`, `a  b` and `100% of the time` through it, because
  `java.text.Normalizer` and `Locale.ROOT` are exactly what native-image trims by default. §5.
- **Whether Advanced URI's UID lookup folds case.** §7.
- **Whether `register_dialog` tolerates a creator that opens no window.** §2.
- **Whether a `obsidian://adv-uri` URL reports anything when the plugin is absent.** Expected:
  no. If confirmed, the startup check in §3 is not optional. §3.

The convention for pending measurements in this repository is `IN-FLIGHT.md`, which
`plan/Retyping.scala` already cites for exactly this purpose. These three belong there when work
starts.
