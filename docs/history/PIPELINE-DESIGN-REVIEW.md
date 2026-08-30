# Adversarial review — *"Cloze as a Sniper, and the Card That Asked the Wrong Question"*

Everything below was checked against the source. Where I say VERIFIED I opened the file at the cited line. I modified nothing and did not touch Anki.

---

## Part 0 — What is sound (one line each, so effort goes elsewhere)

- **§2's back-end fact table.** I opened nine of its eleven rows. All nine are accurate at the cited lines: `model/CardSpec.scala:313`, `:333`, `:339`, `:381`; `model/Marker.scala:378-391` (grep confirms **zero** production consumers — only `anki/NoteTypeAssets.test.scala:82`, `model/Marker.test.scala:361,369,393`, and a comment at `anki/InMemoryAnki.scala:359`); `plan/Retyping.scala:51,216`; `anki/InMemoryAnki.scala:80-87`; `anki/NoteTypeInstall.scala:394-402` (`driftBetween` does not even *take* `isCloze` as a parameter, so "never compared" is structural, not incidental); `anki/NoteTypeAssets.test.scala:176`, `:319-373`; `anki/Anki.scala:283-296`.
- **The `^` mechanism argument.** `Html.escape` at `content/AsHtml.scala:322-333` covers exactly `& < > " { }` and leaves `^`; `ObsidianSyntax.bundle` at `:229-236` registers exactly six span parsers, none of them caret-delimited. The structural claim holds.
- **The comment carrier's three properties.** `ObsidianComment` is a node (`parser/ObsidianSyntax.scala:81`), lowers to zero inlines by its own named case (`content/Lower.scala:428-433`), and exists *because* Obsidian hides it (`:64-72`). All three verified.
- **The golden file really is a byte-identity gate.** `extract/golden/fixture-cards.txt` emits `field ⟦Front⟧ ⟦…⟧` per field in order, 498 lines, `DO NOT REGENERATE` at the top. Step 3's gate is genuine, not decorative.
- **The `Retype` blast radius.** "3 construction sites, 6 positional patterns" is exactly right: constructions at `plan/Planner.scala:350`, `plan/Retyping.test.scala:809`, `cli/Cli.test.scala:352`; patterns at `plan/Executor.scala:296`, `plan/Retyping.scala:222`, `plan/Retyping.test.scala:349`, `plan/Planner.test.scala:250,592`, `cli/Main.scala:1068`.
- **Putting the drift finding on `Plan` and not in `Change`.** `plan/SyncAction.scala:19-48` is the executor's write payload; `:332-344` is the `parked` precedent with its "NO DEFAULT VALUE" note. The adjudication is correct and correctly grounded.
- **`Cloze.fromLowered` needs no change.** `extract/Cloze.scala:51-55` takes `(key, blocks, context)`. Verified.

---

## Part 1 — Findings, most severe first

### F1. The sentence the document sets out to fix is not the false one. The *actionable* sentence beside it is false, for the exact card that motivated the document. **[claim not established + cost understated + owner's harm not answered]**

**What it is.** §5 identifies the harm as `cli/Report.scala:295-296` ("Leaving them is safe…") and prescribes rewriting it, cost "two prose blocks." But that sentence sits at the bottom of `Report.deferredRetypes` (`cli/Report.scala:273-297`), whose operative content is three lines above it:

```
291	"writing them back, so this tool does not do it unless asked. Ask with:"
293	"  sync --migrate-note-types ..."
```

**Why it matters.** For the `framework` note that produced the 34.5 seconds, that remedy does not work. `plan/Retyping.scala:165-177` asks the **policy first** — under `Defer` the verdict is `DeferredByPolicy` and *no shapes are read at all* (the docstring at `:159-163` says so and defends it as load-bearing). Ask with `--migrate-note-types` and `refusalFor` (`:187-197`) fires `TemplateCountDiffers`, because `IN-FLIGHT.md:179-185` records this note as `Obsidian Basic (and reversed card)` (2 templates) → `Obsidian Concept-Descriptor` (3) — "the GROWTH direction," which the gate refuses and `docs/findings/EVOLVABILITY.md:49` marks UNMEASURED pending M4.

So the run tells the reader: *nothing was written; leaving them is safe; ask with `--migrate-note-types`.* Two of those three are misleading, and the document only found one. The reader who follows the printed remedy lands on `Report.retypePreview`'s "OF THE MOVES COUNTED ABOVE, 1 WILL NOT HAPPEN" and a *different* reassurance at `:344-345` — which is where the 34.5 seconds actually come from.

**The cost this hides.** Telling the truth in the deferred block ("and this one would be refused even if you asked") requires knowing the shapes, and under `Defer` the tool deliberately does not read them (`plan/Retyping.scala:159-163`). So step 0 is not "two prose blocks": it is either (a) reading shapes under `Defer` — two requests per note-type pair, reversing a documented ruling — or (b) leaving a false remedy standing under a newly-honest warning, which is worse than today because the warning now makes the reader act on it.

**What the document should say instead.** Step 0 must state that a deferred retype has two futures, not one, and that the report conflates them: it may become `WillApply` or it may become `RefusedByShapes`. Name the choice explicitly — read shapes under `Defer` (and price the extra requests), or reword the remedy conditionally ("some of these will be refused when you ask; run `--dry-run --migrate-note-types` to see which"). And §5's third change must fire on **both** the deferred and the refused paths, not just "whether or not the retype is deferred."

---

### F2. §1 does not survive §6. The thing the owner asked for is gated on an unrun measurement, and §1 does not say so. **[internal contradiction]**

**Where.** §1: *"Build **Design 3, 'Marked Nodes, Typed Roles'**, as the spine: a card becomes anchorable at a *block*…"* — followed by four sentences of gating, none of which mention the sniper. §6 step 7: `CardPath.Block` + the `%%card%%` carrier, gate: **"M-carrier measured; steps 0–4 green."** M-carrier is unrun (§8).

**Why it matters.** This is the exact failure the brief describes from last time. §1 discloses one gate (the ordinal binding's measurement) and conceals the other (the sniper's). A reader who reads only §1 believes the sniper is decided and being built; §6 says it is decided *conditionally*, seventh of nine, behind a live-Obsidian experiment nobody has run.

**Fix.** §1 must carry the same sentence §6 carries: *the block carrier is chosen, and it is gated on M-carrier — minutes of work in a throwaway vault, and until it is run the front end does not move.*

---

### F3. §1 says the ordinal fix is blocked by "one measurement." §4 says it is blocked by three things, two of which are unwritten design. **[internal contradiction]**

§1: *"prevention (a stored ordinal binding) is staged behind **one measurement** that cannot be settled by reading."*

§4 (*What would actually prevent it*) gives three reasons, and §6 step 6's gate lists three conditions: *"run reports zero actions; M-cloze-rewrite answered; keying + label-collision rules written."* Two of those are not measurements at all — they are design work the document declines to do: an unresolved collision rule (a label colliding with a surviving binding) and an unnamed hash for `ClozeGroup.Unlabelled(text)`, which cannot go in an Anki tag because it contains whitespace.

**Fix.** §1 should read: *staged behind one measurement and two rules this document does not write.* Say which two, in §1, in one clause.

---

### F4. §1 says "delete the sentence"; §5 says "rewrite" it. Neither is right, and deletion is actively worse. **[internal contradiction + wrong prescription]**

§1: *"delete the sentence in `cli/Report.scala:295-296` that told you deferral was safe."*
§5.1: *"**Rewrite the sentence.** What is true: …"*

Deleting it leaves `:289-293` — "Nothing was written… Ask with: `sync --migrate-note-types`" — as the block's whole content, i.e. leaves the falser half (F1) standing alone and unqualified. The two sections must agree, and they must agree on *rewrite*, not delete.

Related: §5 says the sentence is *"repeated at `:344-345`."* It is not repeated; `:344-345` is a **different** sentence in a **different** function (`retypePreview`, reached only under `--dry-run --migrate-note-types`). §5's own evidence — *"grep for 'Leaving them is safe' hits only `cli/Report.scala`"* — would not find `:344-345` at all. I confirmed: the string occurs once in source. Say "and an analogous claim at `:344-345`, which the grep does not find."

---

### F5. The five-trigger cloze list is presented as VERIFIED. Items 4 and 5 are false as stated, and the worked example does not do what the document says. **[claim not established — and it is §2's punchline]**

I traced `extract/Cloze.scala:200-230` by hand.

**Item 4** — *"a label is added anywhere with a number ≤ the count of unlabelled groups."*
Counterexample: `==a== ==b== ==c==` → 1, 2, 3. Label the middle one `==2|b==`: `labels={2}`; a takes 1; b takes 2; c skips 2 and takes 3. **Nothing moves**, and 2 ≤ 3.
Counterexample in the other direction: `==a== ==b==`, label b `==3|b==`: a→1, b→**3** (was 2). It moved, and 3 > 2.

**Item 5** — *"a label is removed (`labels` shrinks; the skip loop stops skipping)."*
Counterexample: `==1|a== ==b==` → 1, 2. Remove the label → 1, 2. Nothing moves.

**The worked example.** §2 says: *"The author follows the tool's own advice (`extract/Extractor.scala:298-299` literally suggests `==1|$t==`) and writes `==2|a==`."* Two problems. (a) The tool's advice is `==1|…==`, and following it *literally* in that example is free: `==1|a== ==b==` → a=1, b=2, unchanged. The rotation requires the author to pick `2`, which the tool never suggested. (b) `extract/Extractor.scala:298-299` is `AmbiguousClozeDeletion`'s message, which fires **only for two unlabelled highlights with identical text** — a case the example (`a`, `b`) does not trigger. The citation does not support the sentence it is attached to.

**Why it matters.** This list is the load-bearing justification for the census (step 1) and for calling the drift wider than `docs/findings/EVOLVABILITY.md:81-89` states. The census is still justified — the *real* rule makes the case better, not worse — but the document as written hands the owner a claim he can falsify in four lines of markdown.

**Fix.** State the actual invariant and derive the triggers from it: *an unlabelled group's ordinal is the k-th positive integer no label claims, where k is its rank in first-appearance order among unlabelled groups. Any edit that changes either that rank or the label set can move it; an edit that changes neither cannot.* Then note the real sting: **labelling a group with the number it already holds is free, and the tool shows that number nowhere** — which is exactly what the census fixes, and needs no engineered example. Cite `docs/reference/CARD-MODEL.md:344,346` for the labelling advice, not the refusal message.

---

### F6. "§4C's costs are all *adoption* costs" is false, and the cost it omits is the one that kills the proposed anchor. **[claim not established + cost understated]**

**Where.** §3, front end: *"This is not `docs/findings/EVOLVABILITY.md` §4C's deferred per-heading anchor. §4C's costs are all *adoption* costs… That deferral does not bind here."*

`docs/findings/EVOLVABILITY.md:204` lists five costs before it reaches adoption: three coordinated strip sites; **"copy-paste of a section becomes a duplicate-key *whole-run refusal* (`plan/Planner.scala:239-248,286`), which the current scheme also suffers but far more rarely"**; the retirement of `REQUIREMENTS.md:110`; doing nothing for ancestor headings; and retiring `underAFailedSection` if the anchor replaces path structure. Only the last sentence is about adoption.

**Why the omitted one is fatal to the argument.** The document kills `^blockid` partly because *"their per-file uniqueness — on which `(noteId, Block(id))` being a key entirely depends — is unverified."* An author-typed name has **no** uniqueness mechanism at all. Duplicate a paragraph in Obsidian — the single most common operation on a literature note — and you have two `%%card cloze: function-as-graph%%` markers, one key, and per `model/CardKey.scala:123-125` **the whole run refuses until a name is changed.** Obsidian at least *generates* block ids; a human copying a paragraph reproduces the anchor verbatim. The document applies a uniqueness standard to `^blockid` that its own preferred mechanism fails harder.

**Fix.** Say plainly: block anchors reintroduce §4C's duplicate-key whole-run refusal, at higher frequency than headings because paragraphs are copied more often than sections. Then decide: refuse the run (status quo, and say so), or specify a per-note duplicate-anchor refusal with a message naming both occurrences. Do not leave it unmentioned while using the same hazard to reject the alternative.

---

### F7. The requirements cost is cited to the wrong line, and the real one is a reversal of a ratified decision. **[cost understated]**

§3 offers as an "honest cost" that `docs/reference/REQUIREMENTS.md:110` — *"the marker count in the markdown is zero, and stays zero regardless of card count"* — must be restated to *"the tool writes nothing into the markdown; what a card is anchored to, the author writes."*

That restatement is not a concession; it is a paraphrase of what requirement 10 already says. The actual cost is one line up, at `docs/reference/REQUIREMENTS.md:106-108`: requirement 10 **superseded** an in-file write-once identifier, and replaced it with *"The key is **derived** from text already present — `(frontmatter id, heading path)`."* A block anchor name is not text already present. It is a token whose only purpose is identity, typed by the author — which is precisely the option that was superseded, and the "the author writes it, not the tool" defence is precisely the argument the superseded position made (`:106`, "only that one fails *visibly*").

**Fix.** Name it as what it is: a **partial revival of the superseded in-file identifier**, scoped to block-anchored cards, requiring an amendment to requirement 10 with a date and a reason — not a restatement of its consequence sentence. And note that `docs/findings/EVOLVABILITY.md:204` already recorded this retirement as a cost of option C, so the document is paying option C's price while telling the reader §4C "does not bind here."

---

### F8. `- q: cloze` reverses a refusal ratified two days ago, and changes the schema's value type. **[cost understated + decision dodged]**

§3: *"The declarations block widens its left-hand side from *frontmatter properties* to *nodes of this note*, so `- q: cloze` lets `%%card q%%` abbreviate."*

Three things this glosses:

1. `EdgeSchema.rules: Map[PropertyName, ThreeFieldDirections]` (`extract/EdgeSchema.scala:43`). The value type is *not* a general marker. `- q: cloze` requires changing it.
2. `EdgeSchemaError.NotAnEdgeShape`'s docstring (`extract/EdgeSchema.scala:75-78`) says: *"A relation is a triple… so the only shapes it can take are the concept-descriptor ones. **`cloze` and `sequence` parse perfectly well and are refused here for that reason**, which is a better message than 'unrecognised'."* — a deliberate, reasoned refusal, amended `2026-08-26`. The document reverses it in a subordinate clause without naming it.
3. **Namespace collision.** `rules` is keyed by `PropertyName`. If block anchors share that key space, `- q: cloze` is ambiguous between *"frontmatter property `q` expands to a cloze card"* and *"block anchor `q` is a cloze card"* — and `CardPath.Property(q)` and `CardPath.Block(q)` are different keys. This is the same class of collision `model/CardKey.scala:119-127` says the kinded path exists to prevent, reintroduced one level up in the rule table.

**Fix.** Either drop the declarations-block widening from this document (it is not needed for the sniper and it is a separate decision), or decide it properly: separate rule sections, or a syntactic kind mark on the left-hand side, plus an explicit reversal of the `cloze`-in-a-rule refusal with its own reason.

---

### F9. Converting an existing cloze section into block clozes destroys its review history, and the document's own "no adoption event" argument does not cover it. **[cost understated]**

§3 argues: *"A paragraph has no card today. No live key, no adoption event, no orphan."* True for a paragraph that never carried a card. False for the migration the sniper invites: a one-paragraph `#flashcard/cloze` section moved to `%%card cloze: …%%` produces a **byte-identical `Text` field under a new key** — `AsText`/`AsHtml` render the same single block either way — so the planner sees an orphan plus a historyless create (`docs/findings/EVOLVABILITY.md:55`). The exact-field pairing that would catch it (§4A) is not built (`docs/findings/EVOLVABILITY.md:194`: *"IN-FLIGHT.md:174-176 claims tier 2 ('exact fields') is built; it is not"*).

**Fix.** Say it: *adopting a block anchor for content that already has a section-level cloze card orphans that card. Nothing detects the pairing today. The sniper is for new content until §4A ships.*

---

### F10. A block marker inside an already-marked section produces two notes over the same content, and the document never says what happens. **[decision dodged]**

Nothing in §3 or §4 says what a `%%card cloze: x%%` inside a `#flashcard/cloze` section means. Both markers are legal under the proposal; both produce a note; the block's content is a subset of the section's. That is *"two notes holding the same fact, on two schedules, forever, with nothing comparing content across keys to notice"* — the project's own words, at `model/CardSpec.scala:182-186`, given there as the reason a whole refusal (`TableRowsWithoutRows`) exists. `IN-FLIGHT.md:141-145` records content-duplicate detection as open and unbuilt.

**Fix.** Decide it, in §3, in one sentence. The candidates are: refuse a block marker inside a marked section (`SpecError`, message naming both); or let the block marker *suppress* the section card; or admit both and count them. Do not leave it to the implementer.

---

### F11. Two ratified documents are confirmed to promise a safety net that does not exist, and nothing in the staging table fixes them. **[asked for and unanswered]**

§2 establishes, correctly, that `model/CardSpec.scala:42-47` and `docs/reference/CARD-MODEL.md:343` both promise a retired cloze group is *"flagged as an orphan, visible in the prune list,"* and that no such thing happens (I confirmed: `plan/Planner.scala:422-453` filters over `CardKey`s; nothing cloze-aware exists in `plan/`). §2 closes: *"Two ratified documents promise a safety net that is not there."*

Then §6's nine steps never correct them. `docs/findings/EVOLVABILITY.md:293` ranks that correction as item **2** of the whole programme — *"Free, and they are actively misleading the next reader about the mechanism that protects history"* — in a document that the reviewed text itself cites (§7) for prose decay being the project's most-recorded failure mode.

**Fix.** Add it to step 0. It is free, it writes nothing, and leaving a confirmed-false promise standing in two ratified documents while shipping a nine-step programme is the exact failure mode being diagnosed.

---

### F12. The project's own #1 priority is absent from the staging table, and §6 re-reads the governing fact in a way that licenses its absence. **[decision dodged]**

§6 quotes `docs/findings/EVOLVABILITY.md` §7 correctly (`:286`) and then draws a conclusion §7 does not: *"which means everything else should be sequenced by **what it unblocks**, not by urgency."*

§7 does the opposite. Its item 1 is `[SAME-COST-WHENEVER]` and is nevertheless first, on urgency: *"Fix the retype-over-orphan stranding… **the only confirmed permanent removal of accumulated value in the system** (§3.1). Every day this exists is a day a stranded card can be created, and nothing will ever find it."* The reviewed document's table does not mention §3.1 at all.

**Fix.** Either schedule §3.1 (it is one `Unflag` in the `Retype` branch at `plan/Planner.scala:346-365`, and it is a *write*-path fix, so it does not belong in a "writes: none" column without saying so), or state explicitly that this document is scoped to exclude it and that §7 item 1 still stands ahead of everything here.

---

### F13. Half the owner's stringly-typed complaint is never addressed. **[asked for and unanswered]**

The brief names two: `ObservedNote.noteType: String` **and** `CardSpec.noteTypeName: String`, "compared with `!=`". §3's back-end section addresses only the observed side (`Known(NoteType) | Foreign(raw)`).

This is not an oversight that can be waved through, because the spec side is load-bearing in a way the observed side is not: `plan/Planner.scala:150` feeds `spec.noteTypeName` into `contentHash` as a raw string. Typing it means either a `.render` that must produce the identical byte sequence forever, or a hash change that rewrites `sha::` on every note in the collection. That is a real decision and the document does not make it.

**Fix.** Say what happens to `CardSpec.noteTypeName`. If it stays a `String` deliberately, say why — the honest answer is probably "the hash is derived from it and a rendered enum is the same string with a new way to get it wrong" — and say that the comparison at `:334` becomes `observed.parse != NoteType.of(spec)`, with the spec side total by construction.

---

### F14. "A spec emitting a field name no note type declares" is not made unrepresentable — it is made *runtime-checked*. **[claim not established, in the section whose whole job is precision]**

§3, item 2: *"With `FieldRole` closed and the binding total, a name exists only because a binding declared it."*

The binding lives in the manifest (§3, back end: *"Each manifest gains a `roles` key beside `fields`"*), which is JSON read at runtime from `resources/note-types/*/manifest.json`. That is a runtime totality, enforced by a test over five files — the same shape as `Marker.FieldOrder.byNoteType`, which item 5 of the same list names as *"the cautionary precedent — the last artefact of that shape drifted inert within days."* The list contradicts itself two items apart.

**Fix.** Downgrade item 2 to what it is: *checked at load, totally, over the five manifests, and it fails the run rather than the build.* Move it out of "unrepresentable."

---

### F15. `roles` beside `fields` in the manifest is a new drift surface, in a proposal sold as removing them. **[cost understated]**

The manifest would then declare its field names **twice** — once in `fields`, once as the values of `roles`. Nothing in §3 says what enforces their agreement. Note also `anki/NoteTypeAssets.scala:285` closes the manifest's key set to exactly `{name, renamedFrom, isCloze, fields, styling, templates, derivedFrom, capturedFrom}`, and `:290` decodes strictly, so `roles` is a decoder change as well as a data change.

**Fix.** One sentence: either `fields` becomes derived from `roles` in the manifest (one declaration), or a test asserts `roles.values.toVector == fields` per manifest — and say which.

---

### F16. Step 3 is not "writes: none." **[cost understated / staging not honest]**

§6's table marks step 3 `Writes? **none**`, and the paragraph beneath says steps 0–5 are *"reversible: reverting the code reverts the behaviour, **because nothing was written differently**."*

Step 3 replaces the bare `!=` at `plan/Planner.scala:334`. §3 states the point of doing so: *"a one-character difference currently plans a `Retype` forever and, under the default `Defer` policy, never converges."* Closing that changes **which `Retype` actions the planner emits** — and under `--migrate-note-types` those are writes. The behaviour is not revertible in the sense claimed; a note that stopped being retyped, or started being retyped, has been written differently.

**Fix.** Split the column, or qualify the sentence: *no new stored-state format; step 3 does change which retypes are planned, and therefore what an `--migrate-note-types` run writes.*

---

### F17. `anki/Anki.scala:81-87` is cited for the opposite of what it says. **[wrong citation]**

§3: *"Parse the observed side at the boundary instead; that is the move this codebase already makes and documents at `anki/Anki.scala:81-87`."*

That docstring documents **not** parsing: *"reads carry RAW `String` tags because Anki returns everything, including tags a person applied themselves; writes take `OwnedTag` because the tool may only ever set its own."* It is an argument for keeping the observed side untyped at the boundary and deciding ownership later.

The move the document wants *does* have a precedent — `TagCodec.decode` (`model/CardKey.scala:373-385`) turns a raw observed tag into a typed `CardKey` or a `KeyError`. Cite that.

---

### F18. M-cloze-kind's provenance is invented. **[claim about Anki not established — the class the brief flags]**

§2's table and §8 both say the live collection's `Obsidian Cloze` carrying `MODEL_CLOZE` *"was read once on 2026-08-21 and never re-checked."*

Nothing in the repository records reading a live model's `type`. All three 2026-08-21 citations — `anki/Anki.scala:195`, `anki/AnkiConnect.scala:330-331`, `anki/FakeAnkiConnect.test.scala:289-290` — record reading **`anki/consts.py`** for the *constant values* `MODEL_STD = 0`, `MODEL_CLOZE = 1`. That is a different fact.

Worse, the document misses evidence that is actually there and is stronger than what it invented: `anki/AnkiConnect.scala:332` records, from the add-on source, that *"The add-on sets exactly this key when `createModel` is given `isCloze` (`__init__.py:1134-1135`, `m['type'] = MODEL_CLOZE`)"*, and `resources/note-types/cloze/manifest.json:3` declares `"isCloze": true`. So the model was installed with the flag by a code path that sets `type`. The residual risk is narrow: a model created before that path, or altered by hand.

**Fix.** Strike the false provenance. State the real position: *the add-on sets `type` from `isCloze` on `createModel` (VERIFIED, `anki/AnkiConnect.scala:330-332`), so the risk is confined to a model not created that way; M-cloze-kind confirms it in one read.*

---

### F19. The `Marker.test` docstring is quoted with its second half removed. **[claim not established]**

§3: *"the hand-built-representatives test at `model/Marker.test.scala:341-375`, **whose own docstring concedes a sixth variant's wrong field name would not fail it**."*

The docstring (`:326-329`) reads: *"Adding a sixth variant will not fail this test; **it will fail `CardSpec.fields`'s own exhaustive match first, which is a build error here**."* The test *claims* the protection exists, one layer up. The document's underlying point is defensible — the compiler catches a missing arm, not a wrong field *name* inside a new arm — but as written it attributes to the docstring a concession the docstring does not make, in a document whose credibility rests on quoting this codebase accurately.

**Fix.** *"…whose docstring says a sixth variant is caught by the exhaustive match instead. That covers a missing arm, not a new arm naming the wrong field, which is the case the role model closes."*

---

### F20. `plan/Planner.scala:422-448` does not do what §4 says it does. **[wrong mechanism]**

§4: *"the failure is `BuildFailure.KeyKnown`, so `plan/Planner.scala:422-448` **unions it into `builtKeys`** and the note is neither flagged nor suspended."*

`builtKeys` is `scan.specs.map(_.key).toSet` (`plan/VaultScan.scala:279`) — built specs only. `failedKeys` (`:284`) is derived separately from `BuildFailure.shelters`, and the orphan filter (`plan/Planner.scala:447-453`) excludes it in its own clause: `!builtKeys.contains(...) && !failed.contains(...) && …`. The outcome the document describes is right; the mechanism is not. Fix the sentence — this document's value is that its citations survive being opened.

---

### F21. §5's reuse claim overstates what step 2 buys for §4A. **[cost/benefit understated]**

§5: *"the same code serves both"* — step 2's observed-vs-wanted field print, and §4A's rename recovery.

They share only a field-vector comparison. §4A's difficulty is elsewhere: it is a **search** over unmatched orphans confined to one frontmatter id, and `docs/findings/EVOLVABILITY.md:194` states its governing constraint — *"it must speak only when exactly one candidate matches, and stay silent otherwise"* — plus the recorded weakening of the hash-based repair hint. Step 2 matches by key, has exactly one candidate by construction, and builds none of that.

**Fix.** *"It is not the same code as §4A; §4A's hard part is the candidate search and the exactly-one rule. What step 2 establishes is the smaller thing: that observed fields are worth fetching and printing."*

---

### F22. `{{c0::…}}` is listed as unrepresentable and is actually conditional. **[minor internal inconsistency]**

§3's "unrepresentable" list, item 8, is a bare `{{c0::…}}`. §4 makes it conditional (*"Grep the real vault for `==0|` first — a refusal that fires on existing content is a behaviour change"*) and §6 step 1 makes the grep a gate. Mark item 8 as *unrepresentable after step 1, if the vault census permits.*

---

### F23. M-vault-census is listed as deciding a question §6 has already answered. **[residual "it depends"]**

§8: M-vault-census *"tells you whether misalignment is a report line or a subsystem."* §6 step 0 already builds it as a report block with its own count. Either the measurement decides it (and step 0 waits), or step 0 decides it (and the measurement's stated purpose shrinks to sizing). Pick one. Same pattern, weaker, for M1: §4 says the granularity trade "is unpriced" and §6 ships step 7 without pricing it.

---

## Part 2 — Two smaller notes

- **Marker discovery is new traversal work and is not costed.** `ObsidianComment` is a `Span` (`parser/ObsidianSyntax.scala:81`), so a block marker is found by scanning inlines of every block in every note — not by the heading-text regex `Marker.MarkerPattern` (`model/Marker.scala:393`) that finds markers today. Say so; "already parses as a node" reads as though discovery is free.
- **`docs/reference/CARD-MODEL.md:232-236` pins a test that step 7 must change.** *"A test asserts that extraction produces only heading anchors, so the day one produces a note anchor is a decision somebody made rather than a drift nobody noticed."* Adding `CardPath.Block` trips that guard by design; the staging table should name it so the implementer does not read a red test as breakage.
- **`docs/reference/CARD-MODEL.md:352`'s reserve is for a different construct.** The line reserves `==text==%%1%%` as a *group-label* syntax, rejected as verbose. Using it as prior blessing for a *card-anchor* carrier is a stretch worth one honest clause.