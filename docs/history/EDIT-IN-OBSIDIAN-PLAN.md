# Edit in Obsidian — execution plan

_Written 2026-08-27. **Delete this file when the work below has landed**, the way `docs/history/IN-FLIGHT.md`
says of itself._

The design this executes is `docs/design/EDIT-IN-OBSIDIAN.md`. That document is the argument; this one
is the order of work and the state of it. Where the two disagree, the design doc is wrong and
should be amended rather than worked around — three of its claims were already corrected on
2026-08-27, and the corrections are recorded in it, dated, in place.

**What is being built.** An Anki add-on that intercepts the *Edit* action. If the card under
review carries a `src::` tag — meaning this tool created it from a markdown note — the add-on
opens that note in Obsidian instead of Anki's editor. Every other card opens Anki's editor
exactly as before. The point is a uniform editing habit: edits made in Anki do not reach the
vault and are destroyed by the next sync, so the only safe place to edit is the source.

## The decision that shapes everything below

**The canonical fold lives in Scala, and is reached by the add-on through a new `locate`
subcommand.** Settled with Marc on 2026-08-27.

The fold is `TagCodec.canonical` composed with heading-text extraction: NFC-normalise, trim,
collapse internal whitespace runs, lowercase under `Locale.ROOT`, strip any `#flashcard/…`
marker, and take Laika's *extracted text* of the heading rather than its raw markdown source.
It is the most identity-critical function in the system — every card's key passes through it,
and a change to it orphans cards and mints duplicates with no review history.

A Python reimplementation inside the add-on was rejected for that reason: it would be a second
implementation of that function, held honest only by a test, free to drift the moment anyone
touches the Scala side. A sidecar index emitted at sync time was rejected as a cache with a
staleness story nobody asked for.

**The accepted cost is a JVM start on each *Edit* press.** If that proves intolerable in use, the
answer is a native image, not a second implementation of the fold.

## Phase 0 — ANSWERED 2026-08-27

All three probes were run against the live installations, not against documentation. What
follows is what was observed. Versions are named because every one of these answers is a
statement about a specific build and none of them is guaranteed to survive an upgrade.

- [x] **What Obsidian's Advanced URI plugin actually accepts.** _Advanced URI 2.0.0, bundle read
      directly._ **`uid` composes with everything, and `heading` is unusable.**

      The dispatcher resolves `uid` to a file path at its very first step and then CLEARS the
      `uid` parameter. So by the time any handler runs, a uid-addressed call is indistinguishable
      from a path-addressed one, and `line`, `block` and `heading` all apply normally on top of
      it. That also means we never need an id-to-path index of our own.

      But heading matching is `heading.heading === parameter` — STRICT EQUALITY against the raw
      heading text as Obsidian's metadata cache holds it. No case folding, no whitespace
      collapse, no markdown stripping. Our tag holds the CANONICAL form, which is none of those
      things, so `heading=` can only ever match a heading that was already lowercase, singly
      spaced and unformatted. On a miss it shows a "Can't find heading" notice.

      **Therefore the anchor is delivered as `line=`, and `locate` must resolve the anchor to a
      line number itself.** That is consistent with the Phase 1 decision rather than a
      complication of it: the fold was going to run in Scala anyway, and now it has a reason to
      read the file as well.

      _The write-back hazard was chased down and is CLOSED._ The worry was that a uid which fails
      to resolve keeps the parameter set and leaves the plugin's create-and-stamp path live —
      a third-party write into the markdown, on our behalf, against the tool's central invariant.
      It cannot happen on this route. Every branch of the dispatcher that can open anything is
      guarded on a file path having been resolved, so a failed uid reaches no handler at all.
      Sending `uid` plus `line` and no path either opens the note (the parameter having been
      cleared, so the write-back branch is unreachable) or does nothing whatsoever.

      _"Does nothing whatsoever" is the new failure mode, and it is worse than an error._ No note
      opens, no notice appears, nothing is logged. Pressing the edit key simply looks broken —
      the exact shape §8 of the design doc says the add-on's types must make unrepresentable,
      arriving from outside the add-on where its types cannot reach it.

      _And it has a live trigger._ **The uid lookup is case-sensitive** — exact string comparison,
      no folding — while the id recovered from a `src::` tag has been through `canonical` and is
      LOWERCASED. Any uppercase character in a frontmatter `id` therefore produces exactly the
      silent no-op above. Marc's ids are lowercase hexadecimal UUIDs, so this is inert today,
      which is the state that ends without warning.

      **The mitigation is free and belongs to Phase 4.** `locate` reads the note anyway, to
      resolve an anchor to a line, so it holds the RAW frontmatter value and emits that as the
      `uid`. The canonical id stays what it has always been — the key — and never leaves Scala.

      _Unlooked-for, and worth having:_ the plugin's default `idField` is already `id`, which is
      exactly the frontmatter key this tool derives identity from. No plugin configuration is
      needed. Confirmed against a real note — `Function Space.md` carries `id:` in its
      frontmatter and the bundle's default is the same string.

- [x] **Whether Laika's extracted text diverges from a naive strip.** **Moot as posed, and the
      evidence vindicates the Phase 1 decision.**

      The question only had force while a Python reimplementation was on the table. With the fold
      running in Scala through the same Laika pipeline that produced the key, extraction is exact
      by construction and there is no second implementation to diverge from.

      The vault data is worth recording anyway, because it says how close a call this was.
      Headings carrying inline markup are ORDINARY here, not exotic: LaTeX (`$A$`), wikilinks
      with and without aliases, and inline code spans all appear in real headings. The single
      marked heading that carries markup is `# Notation  (Given 2 sets, $A$ and $B$)
      #flashcard/2way` — which also happens to contain a DOUBLE SPACE after the first word, the
      exact input `canonical`'s whitespace collapse exists for. A hand-rolled Python stripper
      would have had to get all of that right and stay right.

      _Consequence for Phase 2:_ a fuzzy second pass is now for tolerating FILE DRIFT only, not
      for absorbing implementation divergence. Recommendation is to leave it unbuilt in a first
      version — see Phase 2.

- [x] **Whether `aqt.dialogs.register_dialog` can be shimmed conditionally.** _aqt 25.9.5, the
      live venv, matching the running process._ **Yes, and the fall-through is clean.**

      There is exactly ONE choke point. The keyboard shortcut, the alternate-layout shortcut and
      the on-screen Edit button all call `mw.onEditCurrent()`, which does nothing but
      `aqt.dialogs.open("EditCurrent", self)`.

      `register_dialog(name, creator)` assigns `_dialogs[name] = [creator, instance]` and nothing
      more, and `open` calls that creator and stores whatever it returns as the live instance. So
      a shim can construct and return Anki's real `EditCurrent` for a card that is not ours — the
      dialog is then registered exactly as it would have been — or open Obsidian and return
      nothing for a card that is. Returning nothing leaves the slot empty, which is the correct
      state and means the next Edit press re-enters the shim. `markClosed` preserves the creator,
      so the shim survives the dialog being closed.

      _One caveat._ Returning nothing fires `dialog_manager_did_open_dialog` with a null instance.
      Anki itself does not mind; another add-on subscribing to that hook might. Worth a look if
      anything misbehaves.

      _Why this route and not monkeypatching `onEditCurrent`._ `register_dialog` carries a
      docstring addressed to add-on authors; the method it would replace does not. One is a
      contract, the other is an internal that happens to work.

## Phase 1 — types first — DONE (Scala) 2026-08-27

`locate/Locate.scala`. Compiles with zero warnings; every body is `???`.

- [x] The outcome type has its fourth case. It is `Located`, on the Scala side, with `Placed`,
      `Unplaced`, `NoteMissing` and `Undecodable`. `Unplaced` carries both a URI and a reason,
      because that outcome genuinely opens something — the note, at its top — and an `Option`
      would have had to either report it as success or throw away the note that WAS found.
- [x] Signatures declared and composed. `Locate.decide` is the one the add-on depends on;
      `Locate.note`, `Locate.anchor` and `Uri.of` are the pieces it is built from.

**Three things the compiler settled that prose had not:**

- **`FrontmatterId` is a type, not a string.** The raw frontmatter value and the canonical
  `NoteId` are now unsubstitutable. This is the Phase 0 case-sensitivity landmine made
  unrepresentable rather than merely documented — the wrong id cannot reach a URI by accident.
- **`UriTarget` lost its `Heading` arm.** The design sketch had one. It cannot exist: Advanced
  URI matches a heading by exact equality on raw text, and the only heading text this tool holds
  is canonical. The arm's absence is now enforced by the type being total over what a URI can
  actually address.
- **`Unplaceable` is a sum, not a flag.** "That heading is gone" and "that heading is in this
  note twice" produce identical behaviour and different messages, and a person acts on the
  message.

_Deferred, and it changed shape rather than being skipped._ The design document gives the add-on
a Python `Verdict` that decodes the tag itself. Under the Phase 1 decision it no longer does —
`locate` answers, and the Python maps that answer onto behaviour. What remains genuinely Python
is the `NotOurs` case, decided without a subprocess by the absence of a `src::` tag. That type
belongs with the add-on and is written in Phase 5.

## Phase 2 — the pure resolver

**RESHAPED 2026-08-27, before any of it was written.** The plan below used to describe a segment
walk: parse the note, apply the fold to each heading, match, count candidates. **That function
should not be written, because the vault scan already produces its answer.**

`VaultWalker.scan` yields a `SourcedSpec` for every card in the vault, carrying both its
`CardKey` and a `SourceRef` whose `line` is the file's own line number — recovered by
`Extractor`'s `LineIndex`, and pinned by `Extractor.test.scala` test B10 and by
`FixtureVault.test.scala`, which specifically asserts those numbers survive frontmatter removal
rather than being body-relative.

So the anchor is not recomputed. It is READ OFF THE SAME SCAN THE SYNC PLANS FROM — not merely
the same implementation of the fold, the same execution of it. A second traversal would have
agreed with the first by construction and by nothing else, which is the defect class this whole
design exists to avoid, reappearing one layer down.

_This was caught by reading `extract/Extractor.scala` before writing the search, not by the type
sketch. The sketch would have compiled perfectly around a function that had no reason to exist._

- [x] `Locate.anchor` — look the key up in the scan and read its line.
- [x] `Locate.note` — find the note by canonical id, hand back the RAW frontmatter id, since the
      scan records a card's FILE and the URI is addressed by id.
- [x] `Locate.decide` — compose them.
- [x] `Uri.of` and its escaping. **RFC 3986 unreserved, not form encoding** — `java.net.URLEncoder`
      writes a space as `+`, which Obsidian reads back as a literal plus in a vault name. Kept
      structurally separate from `TagCodec.encodeComponent`: the two escape for different
      grammars and agree only on `-` and `.`, which is the coincidence that would make a shared
      implementation look correct for as long as the ids stayed hexadecimal.
- [x] `locate/Locate.test.scala` — 10 tests. `E1` is the centrepiece: it pins that the RAW
      frontmatter id reaches the URI and the canonical one never does, which is the Phase 0
      landmine and would have stayed invisible while Marc's ids remained lowercase.

_Measured after this landed: **35 suites, 759 tests, 0 failures, 0 warnings.**_

**`Unplaceable` gained a third arm as a result, and they are three different kinds of statement:**

| Arm | What it says | Whose fault |
|---|---|---|
| `CardGone` | the note is there, this card is not in it any more | the vault moved on — a marked heading was reworded, which retires its card |
| `LineUnknown` | the card is in the scan, its line could not be recovered | a limit of this tool — `LineIndex` answers 0 when its match fails, and that 0 must not be passed on as a line number |
| `KeyedTwice` | two cards carry this one key | the vault cannot be synced at all in this state; met early by whoever pressed the edit key |

All three open the note at its top. None of them is an error — the card exists in Anki, so it was
derivable once, and every arm is a way the markdown has moved on since.

**The fuzzy second pass stays unbuilt**, and now for a second reason: with the anchor read from
the scan rather than searched for, there is nothing for a similarity metric to be similar to.

## Phase 3 — the differential test

- [ ] Resolve every `src::` tag in `extract/golden/fixture-cards.txt` against `dummy-vault` and
      assert each lands on the right heading. The golden is the oracle: it is 498 lines of pinned
      identity tags carrying `DO NOT REGENERATE THIS FILE` at its top, so it states what the keys
      are independently of whatever the resolver believes.
- [ ] Add `hostile-vaults/` to the corpus once that is green — that is where the exotic headings
      live.

_This replaces the spike proposed in §10 of the design doc, which cannot fail: on the decode path
`canonical` is reached only for the note id, so nothing that spike exercises is at risk._

## Phase 4 — URI construction — DONE, AND PROVEN END TO END 2026-08-27

- [x] `Uri.of`, addressing the note by `uid` and the anchor by `line`. No `heading` parameter.
- [x] The RAW frontmatter id is what is sent, never the canonical one from the tag.
- [x] `locate` refuses to emit a URI for a note it could not find.
- [x] The `locate` subcommand — `cli/Cli.scala`, `cli/Main.scala`, `cli/Report.scala`, with
      parsing tests in `cli/Cli.test.scala`. No profile: it reads a vault and no collection.

**THE LOOP WAS RUN AGAINST THE REAL VAULT AND THE REAL COLLECTION, and this is the first thing
in this document that is an observation rather than a deduction.**

Three `src::` tags were read out of Marc's live collection over AnkiConnect and fed to `locate`:

- **Two answered `NoteMissing`, correctly.** Neither id exists anywhere in the vault. Those cards
  came from notes since renamed away, deleted, or belonging to a different vault — which is the
  scenario `README.md`, "ONE VAULT PER ANKI PROFILE", describes. Worth knowing that the condition
  is not hypothetical in this collection.
- **One produced a URI**, for a table pair card keyed
  `module 1 thinking in patterns / cost benefit examples / queue / benefit`.

Opening that URI moved Obsidian's active leaf to the right note, in `source` mode. Confirmed by
reading `.obsidian/workspace.json`'s active leaf rather than by being told.

**What that settles, and what it does not.** The `uid` lookup works against the frontmatter `id`
with no plugin configuration. A vault name of `📖-obsidian-anki-srs-📖` survives percent-encoding
and round-trips — the emoji case was a guess in a unit test and is now a fact. What is NOT
confirmed is that the CURSOR landed on the line: `workspace.json` does not record it, so that
needs a pair of eyes.

**A limit found by running it: for a table card the anchor is the SECTION HEADING, not the row.**
The URI pointed at line 28, `## Cost Benefit Examples #flashcard/table/2way`, while the card is
the `Queue` × `Benefit` cell of the table below it. That is not a defect — `SourceRef` was built
for diagnostics, where naming the section is the right answer — but it means a table card lands
near its card rather than on it. Recorded rather than fixed; whether row-level anchoring is worth
it is a decision, not an oversight.

**Latency, measured on the packaged assembly (`just build`): 0.72s median**, down from 1.35s
through `scala-cli run`. Split: **0.30s** is JVM startup, **~0.42s** is reading and parsing the
22 notes of Marc's vault.

That split is the useful part. A GraalVM native image could remove the 0.30s and would not touch
the 0.42s — and the `Justfile` already rejects native-image on stronger grounds than speed: the
tool parses YAML through snakeyaml, which resolves classes by reflection, which native-image
cannot see unless told. The failure mode is a binary that builds and then breaks on a path only a
live run reaches.

**The half that grows is the vault half.** At 22 notes it is 0.42s and it scales with the vault,
not with the machine. If this ever becomes annoying the answer is scoping the scan, not a native
image. Recorded now so that the wrong lever is not pulled later.

**Two report defects, both found by Marc running it rather than by a test:**

- An empty tag rendered as `That is not a tag this tool wrote:` followed by nothing. A BLANK
  WHERE A VALUE SHOULD BE READS AS A LOST VALUE rather than an empty one, and those two call for
  opposite next actions. Empty and whitespace-only now say so, and point at where the tag is
  found in Anki.
- The refusal printed `MalformedTag(<tag>, not a src:: tag)` — an ADT's `toString` in a report
  meant for a person, which also repeated the tag the line above already showed, burying the one
  piece of information the reader did not have. `KeyError` is now rendered in words.

Both are pinned by tests in `cli/Main.test.scala`.

## Phase 5 — the add-on shell — DONE 2026-08-27, AND IT WORKS

`addon/obsidian_edit/`. Confirmed by Marc pressing `e` on a real card in a real review session.

- [x] `core.py` — every judgement the add-on makes, as pure functions of strings. **25 tests**,
      no Anki required, no interpreter newer than 3.9 required.
- [x] `__init__.py` — the wiring. Registers a replacement creator for `EditCurrent` through
      `aqt.dialogs.register_dialog`, hitting the single choke point all three Edit routes share.
- [x] `config.json`, `config.md`, `manifest.json`.

**The split is the point.** `aqt` imports only inside a running Anki, so nothing in the wiring
has a unit test, and the design document said so before any of it was written. Everything that
could be a decision was therefore moved into `core.py`, which is driven directly. What is left in
the shell is reading configuration, starting a process, and dispatching on three cases.

**Three outcomes, never a fourth.** Obsidian opens; or Anki's editor opens; or a message appears
AND Anki's editor opens. A keypress that does nothing is indistinguishable from a broken add-on,
so it is not a state the code can reach — which is what §8 of the design doc asked the types to
make unrepresentable, honoured here by the shell rather than by the types.

**`--uri-only` was added to `locate` for this**, and it splits channels rather than silencing
one: the link on standard output, the explanation on standard error, in a single run. A flag that
merely suppressed the prose would force a SECOND run — and a second JVM — to find out why nothing
came back.

_A defect that fell out of it, now fixed: a successful lookup was writing its URI to BOTH
channels, which reads as though something had gone wrong on the one reserved for saying so.
`Report.located` (for a person at a terminal) and `Report.explanation` (for a caller that already
holds the link) are now separate, and a placed card is silent on the second — so silence there
means "no caveats", which is a usable signal._

**What running it caught that no test would have:** Anki is launched from the Dock and does not
inherit a shell's `PATH`, so a version-manager JVM is invisible to it. See Phase 6 — this is the
single largest piece of setup friction, and it is the one a stranger meets first.

## Phase 6 — configuration, and `install-addon`

**Done for one machine, unsolved for everyone else.** The add-on works and is configured by hand.
What follows is the design for removing the hand.

### What a stranger has to do today

1. Build the tool and get it on disk.
2. Copy `addon/obsidian_edit/` into Anki's add-on directory.
3. Type in `binary`, `vault_path` and `java_home`.
4. Install and enable Advanced URI in Obsidian.
5. Run `install-note-types`.
6. Have `id:` in their notes' frontmatter.

**Steps 2 and 3 are the ones that lose people, and step 3 already lost Marc** — on his own
machine, with the person who wrote it watching. His JVM came from a version manager, so it sat on
a `PATH` that only a shell assembles, and the launcher reported "Unable to locate a Java Runtime"
— true, and thoroughly misleading, since the JVM was installed and merely unreachable from a
process Anki had started. That is the calibration for how a stranger's first hour would go.

### The reframe that makes this small

The add-on looks as though it carries an awkward extra dependency: a JVM binary. **It does not.**
Anyone installing it already has the sync tool, because the add-on is useless without cards the
tool created. There is no dependency to install — only a pointer to configure. So the whole
problem is *how that pointer gets set without a human typing three paths*.

### `install-addon`, a sibling to `install-note-types`

It can be genuinely zero-configuration, because **every value is available by introspection
rather than by asking**:

| Setting | Where it comes from | Checked |
|---|---|---|
| `java_home` | the tool IS a JVM process, so `java.home` answers | 2026-08-27: reports the version-manager path exactly, which `/usr/libexec/java_home` does not |
| `binary` | the tool knows its own path | — |
| `vault_path` | already resolved, and the vault registry is already read | — |

It should also **verify what it cannot set**, turning failures that are currently silent or
cryptic into refusals that name the remedy — the pattern the rest of the tool already follows:

- **Advanced URI ENABLED, not merely present.** `.obsidian/community-plugins.json` lists the
  enabled ones, so the distinction is available and worth making: a plugin that is installed and
  switched off fails exactly like one that is absent, and says nothing either way. _Checked
  2026-08-27 against Marc's vault._
- The note types installed, and the collection reachable — both already have refusal vocabulary.

### It should PRINT the Obsidian half, not write it

The reverse direction — a hotkey in Obsidian that opens Anki's Browse filtered to the current
note's cards — is a shell command in a third-party plugin's configuration, and setting it up here
by hand on 2026-08-27 cost three failed attempts. None of them were the idea; all three were that
plugin's undocumented behaviour:

1. Its Obsidian command id is `"shell-command-" + <the id in its own settings>`, which had to be
   read out of the bundle. A hotkey bound to the id without that infix silently does nothing.
2. It escapes variable values for the shell, turning a UUID's hyphens into `\-`. Interpolated
   into a JSON body those are an invalid JSON escape, AnkiConnect refuses the whole request, and
   nothing happens visibly at all.
3. Ordering: `open -a Anki` after `guiBrowse` raises the main window over the Browse window that
   just opened, which is indistinguishable from Browse never opening.

**Every one of those is invisible until it bites, and a stranger has no bundle to read.** So this
half is documented in `README.md` and printed by the command — never written into
`.obsidian/plugins/*/data.json`. Anki's add-on directory is a documented layout; a plugin's
private settings file and its internal id scheme are not, and they are free to change in a point
release.

_An opt-in `--write-shellcommands` could exist for people who accept the coupling, refusing
outright when it meets a `settings_version` it has not seen. Off by default._

### What it must not pretend to do

Install Advanced URI. Restart Anki, without which the add-on does not load. Choose a profile
silently — the `--profile` guardrail applies here as everywhere. And Anki's add-on directory is
platform-specific: a guess should be SHOWN and confirmed, never acted on.

### Why not AnkiWeb

**Recommendation: do not publish there.** An AnkiWeb add-on has to be self-contained and
configured by hand, which is precisely the friction this removes. The add-on cannot work without
the tool, so their versions should move together; a version skew between an AnkiWeb add-on and a
locally built binary is a bug report nobody can diagnose. Shipping the add-on inside this
repository, installed by the tool, keeps that impossible.

### The larger question this does not answer

How the TOOL itself is distributed. A package manager formula declaring a JVM dependency would
make the JVM someone else's problem entirely — the single biggest simplification available, and
it would delete `java_home` outright. A prebuilt assembly on a releases page is cheaper and
leaves the JVM to the user. Not decided, and out of scope for this feature.

## Out of scope for a first version

Writing anything back into the markdown. Editing in Anki at all. More than one vault. Block-level
(`^id`) anchors. Any change to the `src::` encoding — that is the one thing here that cannot be
undone cheaply once review history exists.

## Unverified assumptions

Phase 0 is closed. What remains unverified, and should be treated as such:

- **Everything above was READ, not RUN.** The bundle and the `aqt` sources say what they say, and
  the reasoning from them is recorded in full so it can be checked — but no URI has actually been
  fired at Obsidian and no shim has actually been registered in Anki. The first end-to-end
  attempt is where these stop being deductions.
- **Every Phase 0 answer is a statement about a version.** Advanced URI 2.0.0 and aqt 25.9.5.
  Nothing here is a promise either project made; upgrading either can invalidate any of it, and
  the failure would be silent — a heading that stops being found, or an Edit press that stops
  being intercepted.
