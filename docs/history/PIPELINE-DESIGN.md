> **SUPERSEDED. MOVED HERE 2026-08-30, AFTER ITS SURVEYS WERE HARVESTED.**
>
> Its recommendations did not survive their own review — see `PIPELINE-DESIGN-REVIEW.md` beside
> this file. Its **surveys** were sound, so before it was moved every row of its back-end fact
> table was re-checked against the code, and the ordinal-drift analysis with it. That audit is
> the reason this is safe to file as history: nothing live is buried here.
>
> **Two rows had been fixed since it was written.** The observed note's fields, recorded as
> "consumed by nothing", are now read in five places by the identity work. The in-memory
> collection's card count, recorded as name-based and wrong for one gate, now derives from the
> note type's templates.
>
> **Five findings were still true and are now tracked**, with their reasoning carried across:
> `oas-d27` a zero cloze label reaching Anki as `{{c0::}}`; `oas-ksv` a note type's cloze-ness
> declared, sent, and never compared by the drift check; `oas-89w` two holes in the template
> tests; `oas-h9a` the ordinal-drift trigger set being wider than recorded, **and the documented
> remedy being one of the triggers**; and the stringly-typed Anki seam, already `oas-1cj`.
>
> **One of its central judgements was inverted by what shipped.** It rejected `^blockid` as an
> anchor on five grounds. The load-bearing one — that a block reference prints on the card face —
> turned out to be a *missing production in the grammar*, not a property of block references. The
> grammar has one now, the node lowers to nothing so no renderer can print it, and a block
> reference is what identifies a headingless cloze card. See `design/CLOZE-REDESIGN.md`.
>
> _Read it for its surveys and its rejected alternatives. Do not implement from it._

> # ⚠️ THIS DOCUMENT DID NOT SURVIVE ITS OWN REVIEW. READ `PIPELINE-DESIGN-REVIEW.md` FIRST.
>
> It is kept because its **surveys are sound** — an adversary opened nine of the eleven rows in its
> back-end fact table and found all nine accurate at the cited lines — and because two of its
> findings are verified by execution and change what should be built:
>
> - ~~**An Obsidian block reference prints on the card face.**~~ **FIXED 2026-08-29**, and the
>   finding turned out to be load-bearing in the opposite direction: `^` being a delimiter nowhere
>   in the parser stack was a missing production, not a reason to abandon block references. The
>   grammar has one now, the node lowers to nothing so no renderer can print it, and a block
>   reference is what identifies a headingless cloze card. See `design/CLOZE-REDESIGN.md`.
> - **Every block-id definition in `References/Modern Mathematics.md` sits inside a callout, and
>   callouts fail this tool's strict parse.** Still true, still open: **`oas-yom`**. It is silent
>   only because that note has no `id:` — giving it one, so its annotations can become cards, is
>   what detonates it.
>
> **What did not survive** — see the review for all seventeen findings, with counterexamples:
>
> - §1 contradicts §4, §5 and §6 about what is gated on what.
> - §2's five-trigger cloze list has **two false items**, disproved in four lines of markdown, and
>   its worked example cites a message that fires only for a case the example does not trigger.
> - §3 rejects `^blockid` for unverified per-file uniqueness, then proposes author-typed anchors
>   which have **no uniqueness mechanism at all** — duplicate a paragraph and the run refuses.
> - §5 targets the wrong sentence: the false one for the note that motivated this is the printed
>   remedy `Ask with: sync --migrate-note-types`, which for that note does not work.
>
> **Do not implement from this document.** Take the surveys as evidence, take the review's
> corrections, and decide separately.

# Cloze as a Sniper, and the Card That Asked the Wrong Question

*A decision document. Every file:line below comes from the three surveys, not from my own reading of the source; where a survey marked something VERIFIED BY EXECUTION or read the AnkiConnect add-on directly, I say so. I have modified nothing.*

---

## 1. The one-paragraph answer

Build **Design 3, "Marked Nodes, Typed Roles"**, as the spine: a card becomes anchorable at a *block* — a paragraph, a list item, a table — carried by `%%card cloze: some-name%%`, an Obsidian comment that this codebase already parses as a node, already lowers to zero inlines, and already hides from the card face; the author writes the anchor name, so identity depends on nothing outside the file. Graft onto it Design 2's **role model at the single erasure point** (`model/CardSpec.scala:313`) and Design 2's **`GeneratedCards`** replacing `NoteTypeShape`'s template count — but keep `CardSpec.fields` as a byte-identical *derived* projection, not as a new wire type, so the golden file is the migration gate. Ship none of it first: ship Design 1's **read-back report** — print the mis-typed note's observed fields against the wanted fields, and delete the sentence in `cli/Report.scala:295-296` that told you deferral was safe — because that is the live harm, it costs a dozen compiler-guarded edits, it writes nothing to the collection, and it is the first consumer of machinery three later steps need anyway. Reject Obsidian block references (`^blockid`) as the anchor mechanism: they are card *content* today, they are unreachable in your actual literature notes until callouts parse, and their per-file uniqueness — on which `(noteId, Block(id))` being a key entirely depends — is unverified. On cloze ordinal drift, this design **reports it and shrinks its blast radius; it does not prevent it**, and prevention (a stored ordinal binding) is staged behind one measurement that cannot be settled by reading.

---

## 2. What the surveys established

### The crux: can Laika see a block reference?

**VERIFIED BY EXECUTION.** It can't — and worse, it doesn't drop it either. Run end-to-end through this project's own `parser/ObsidianSyntax.scala` + `content/Lower.scala` + `content/AsHtml.scala` (laika-core 1.3.2), `The outermost layer is the ==epidermis==. ^abc123` renders as `<p>The outermost layer is the {{c1::epidermis}}. ^abc123</p>`. **A block id inside a marked section prints on the card face today.**

The mechanism is structural, not accidental: `^` is not a delimiter anywhere in the stack — not in `Markdown.spanParsers.all`, not in `GitHubFlavor`, not in `InlineParsers.escapedChar`, not in the six parsers `ObsidianSyntax.bundle` registers (`parser/ObsidianSyntax.scala:229-236`). `Html.escape` covers `& < > " { }` and leaves `^` alone (`content/AsHtml.scala:322-333`). So no future Laika change will start eating carets — and equally, nothing will start dropping them without deliberate work.

Three further facts, all VERIFIED, that jointly kill `^id` as *the* anchor:

- **All 125 block-id definitions in `References/Modern Mathematics.md` sit inside Obsidian callouts, and callouts fail this tool's strict parse** — `> [!note] Page 31` yields `unresolved link id reference: !note`, because CommonMark reads `[!note]` as a shortcut reference link, the same mechanism `parser/ObsidianSyntax.scala:17-23` describes for wikilinks. VERIFIED BY EXECUTION against the real file. It is silent only because that note has no `id:` frontmatter, so the walk takes `extract/VaultWalker.scala:550-568`'s `CouldNotLook => ()` arm. **Giving that note an `id:` so its annotations can become cards is exactly what makes the failure appear.**
- **The finest addressable unit is a paragraph or a list item — never a sentence.** `obsidian-copy-block-link`'s `getBlock` resolves against `metadataCache.sections`, drilling into `listItems` and `headings`. There is no sentence-level entry in that cache. A table *row* is not addressable either — `shouldInsertAfter` lists `table`, so the id lands after the whole table.
- **Nothing maintains a block id.** The plugin's only write is one `editor.replaceRange` in `handleBlock`; the file contains no rename, migrate, or repair path. The brief's framing — "Obsidian generates and maintains them" — is half right. Generation, yes. Maintenance, no. That absence is *why* an id is immune to rewording, and equally why it survives a cut-and-repaste that leaves it attached to the wrong text.

**UNVERIFIED and load-bearing:** Obsidian's guarantee that block ids are unique within a file. One weak-positive datum (no duplicates among the 125) is a property of a Zotero export, not of Obsidian. If it doesn't hold, `(noteId, Block(id))` is not a key — the exact failure `model/CardKey.scala:119-127` says the kinded path exists to prevent.

**Cheap and unblocked:** a fourth `CardPath` case costs nothing in the codec. `TagCodec` reserves a leading empty token as "not a heading path" with marks `p` and `n` (`model/CardKey.scala:331-351`); `/b/<name>` is the same arity as `Property`, no existing tag byte moves, and `isSafe` (`:266-268`) leaves any plausible name unencoded so `src::<id>::/b/function-as-graph` stays legible in Anki's browser.

### Is the cloze ordinal-drift defect real?

**VERIFIED from code, and the trigger set is wider than `docs/findings/EVOLVABILITY.md` §3.2 states.** `Cloze.number` builds `labels` as the set of *every* explicit label in the section, groups occurrences by first appearance, and assigns each unlabelled group the lowest number no label claims (`extract/Cloze.scala:200-230`, skip loop at `:223`). An unlabelled group's ordinal therefore moves when:

1. an earlier unlabelled highlight is **inserted** (§3.2's one named trigger),
2. an earlier unlabelled highlight is **deleted**,
3. highlights are **reordered**,
4. a **label is added** anywhere with a number ≤ the count of unlabelled groups,
5. a **label is removed** (`labels` shrinks; the skip loop stops skipping).

Cases 4 and 5 are the alarming ones, because **the documented remedy is itself a trigger**. `==a== ==b==` gives a→c1, b→c2. The author follows the tool's own advice (`extract/Extractor.scala:298-299` literally suggests `==1|$t==`; also `model/CardSpec.scala:42-47`, `docs/reference/CARD-MODEL.md:346`) and writes `==2|a==`. Now `labels={2}`, so a→c2 and b→c1: **the two cards swap content while the key is untouched.** And the tool displays a group's current ordinal nowhere (`README.md:146-151` says it counts notes, not cards), so the author cannot pick the number that would make the edit free.

**The chain closes on the vault side, VERIFIED.** Ordinals live inside the `Text` string; `contentHash` digests note type plus every field name and value (`plan/Planner.scala:146-155`); the key is unchanged; the planner emits `Change.FieldsChanged` inside an ordinary `Update` (`:367-383`). Nothing distinguishes a rotation from a typo fix.

**The documented consolation does not exist — CONFIRMED.** `model/CardSpec.scala:42-47` and `docs/reference/CARD-MODEL.md:343` both promise a retired group "is flagged as an orphan, visible in the prune list". Orphan detection is over `CardKey`s (`plan/Planner.scala:422-448`) and there is one per section. `ClozeGroup`, `ClozeDeletion` and "ordinal" appear nowhere in `plan/` outside `Retyping`'s prose. Two ratified documents promise a safety net that is not there.

**The Anki half is UNVERIFIED and cannot be settled by reading.** AnkiConnect's `updateNoteFields` writes the fields then calls `self.collection().update_note(ankiNote, skip_undo_entry=True)` — one call into the compiled Rust backend (VERIFIED: read at `.../addons21/2055492159/__init__.py:823-833`). Whether existing cloze cards are re-pointed at new content for their ordinal, or regenerated, is not decided by the add-on, and Anki's own library is frozen on this machine.

### The back end, in facts

| Claim | Status |
|---|---|
| The IR **already** models field roles — as constructor parameter names — and erases them at exactly one line, `model/CardSpec.scala:313` | VERIFIED |
| `ObservedNote.fields` is the identical `Vector[(String,String)]` arriving from Anki, sorted by Anki's `order` (`anki/Anki.scala:88-93`, `anki/AnkiConnect.scala:163-174`), consumed by nothing | VERIFIED |
| `Marker.FieldOrder.byNoteType` — written so "a consumer holding a `CardSpec` can ask" — has **zero production consumers** (`model/Marker.scala:378-391`) | VERIFIED |
| `NoteTypeShape(templateCount, isCloze)` measures **templates**, while gate fields make two notes on one type carry different card counts (`plan/Retyping.scala:51,216`) | VERIFIED |
| `InMemoryAnki.cardCountOf` models `ThreeWay`, **not** `ValueOnly`, and hard-codes Cloze to 1 (`anki/InMemoryAnki.scala:80-87`) | VERIFIED |
| The gate-completeness test is scoped by construction to `{{#ThreeWay}}`; `{{^ValueOnly}}` shipped uncovered, and the test's own docstring said another gate "must extend" it (`anki/NoteTypeAssets.test.scala:319-373`) | VERIFIED |
| `isCloze` is declared in every manifest, sent on `createModel`, read live by the retype gate — and **never compared** by `driftBetween` (`anki/NoteTypeInstall.scala:394-402`) | VERIFIED |
| `referencesIn` normalises to the segment after the **last** colon, so `{{cloze:Text}}` and `{{Text}}` are indistinguishable to every template test (`anki/NoteTypeAssets.test.scala:176`) | VERIFIED |
| `CardSpec.Cloze.deletions` is **write-only in production** — built at `extract/Cloze.scala:114`, wildcarded out at `model/CardSpec.scala:339`, read only by tests | VERIFIED |
| `==0\|text==` is accepted and emits `{{c0::text}}`; regex `^(\d+)\|(.*)$` at `parser/ObsidianSyntax.scala:217`, no positivity check at `extract/Cloze.scala:204,220`. What Anki does with c0 | code VERIFIED / Anki behaviour UNVERIFIED |
| `changeNoteType` blanks every field of the new type and matches names **case-insensitively** (`anki/Anki.scala:283-296`, from the add-on source) | VERIFIED |
| Whether `addNote`/`updateNote` silently ignore an undeclared field name; whether they case-fold; whether a gate-blanked card keeps its revlog until `Tools > Empty Cards`; whether the live collection's `Obsidian Cloze` actually carries `MODEL_CLOZE` | **UNVERIFIED** (the last was read once on 2026-08-21 and never re-checked) |

### One live-vault datum that unsettles a premise all three designs inherited

`extract/Extractor.scala:99-106` records that a `#flashcard/…` typed into body prose is lifted by Obsidian's editor into frontmatter `tags`. Judge 3 found a counter-datum in the live vault: `Function.md` carries `#check` in body prose while its frontmatter `tags:` holds only `math` and `CS`. That does not refute the recorded behaviour — it may be path-dependent on how the tag was typed — but it means the flat statement is narrower than three designs assumed. **UNVERIFIED; see §8.**

---

## 3. The recommendation

### Front end — the marker gets a second carrier; the vocabulary does not change

**A card is anchored at a node. Today only three node kinds can be marked. Add a fourth: a block.**

The marker rides in an Obsidian comment:

```markdown
One definition represents a function as a set of pairs (x,y) such that
==no two pairs share a first component==.  %%card cloze: function-as-graph%%
```

Placement rule, matching the convention the author's own plugin already writes (`shouldInsertAfter`): a marker *inside* a block attaches to that block; a marker *alone in its own paragraph* attaches to the block above it. So a table gets its marker on the line below, a paragraph gets it inline.

**Why this carrier and not `^blockid`.** `ObsidianComment` is already a parser node carrying its own text (`parser/ObsidianSyntax.scala:81`), already lowered to zero inlines by its own named case (`content/Lower.scala:428-433`), and already hidden by Obsidian in reading view — which is why the node exists (`:64-72`). `docs/reference/CARD-MODEL.md:352` already records `%%…%%` as "kept in reserve, since Obsidian hides comments in reading view". `^blockid` is none of those three. Choosing the comment deletes two prerequisites (`^id` span parser, callout parser) and one unverified external guarantee (block-id uniqueness) from the critical path.

**The shape token after `card` is the existing vocabulary, verbatim** — `cloze`, `sequence`, `table/cells`, `cdd/2way`. This is *not* a new marker language. It is the same right-hand side `Marker.parse` already reads, on a second carrier. Heading markers are unchanged and stay the common case.

**Identity is the author-written name.** `CardPath.Block(name)`, key `(noteId, Block("function-as-graph"))`, encoded `/b/<name>`, decoded above the unknown-mark guard at `model/CardKey.scala:409` and owing a test that `/b` alone is malformed (mirroring `src::n1::/p` at `model/CardKey.test.scala:124`). Consequences: reword the paragraph freely, move it anywhere in the note, rename the file, reword every heading above it — none of those are in the key. That is **stronger than a heading card's**, which re-keys on rewording.

**This is not `docs/findings/EVOLVABILITY.md` §4C's deferred per-heading anchor.** §4C's costs are all *adoption* costs — "a heading that gains an anchor while its derived key is live orphans its own card." A paragraph has no card today. No live key, no adoption event, no orphan, nothing waiting on the unrun M1 measurement. That deferral does not bind here.

**The declarations block widens its left-hand side** from *frontmatter properties* to *nodes of this note*, so `- q: cloze` lets `%%card q%%` abbreviate. **A wildcard selector (`- ^*: cloze`) is refused by name, with the reason in the message** — not left merely unparseable, because "unrecognised" invites someone to invent a spelling that works. It is silent card creation, which `docs/reference/CARD-MODEL.md:94` names as the failure the whole design exists against.

**Two honest costs.** `docs/reference/REQUIREMENTS.md:110`'s "the marker count in the markdown is zero, and stays zero regardless of card count" stops being true of block cards and must be *restated*, not quietly broken: *the tool writes nothing into the markdown; what a card is anchored to, the author writes.* And **you lose Obsidian's tag autocomplete inside a comment** — `Card markers.md` exists in your vault for exactly that reason, stated in its own first line. That is a real regression in what it is like to write, and neither design that proposed a comment carrier noticed it.

**Sentence-level cloze is refused.** A sentence is not a node in Laika's tree, not in Obsidian's metadata cache, and not in `content.Block`. The honest offer is: *the card unit is the block; the deletion unit is the highlight.* Highlighting one word inside one sentence of a three-sentence paragraph already blanks that word — the face just shows the other two sentences too.

### IR — stop the erasure; do not change the wire

Add a closed `FieldRole` sum and a per-arm `roles` projection. **`fields: Vector[(String,String)]` stays, as `roles` composed with the note type's role→name binding, and must be byte-identical for all five existing shapes.** That last clause is the migration's acceptance invariant, not a nicety: `contentHash` digests `noteTypeName +: fields.flatMap(...)` (`plan/Planner.scala:150`), so byte-identity means *zero planned actions*.

**This is the adjudication against Design 2.** Design 2 wanted `fields` itself to become a role-keyed `FieldSet`. That is a type change on the wire format of the whole write path — `NewNote.fields`, `SyncAction.Retype.fields`, `Change.FieldsChanged`, the `Anki` algebra, `AnkiConnectClient.scala:253`, twelve sites in `InMemoryAnki`, `NoteTypeInstall.scala:401,402,632` — and it buys nothing the derived projection doesn't, because the *other* side (`ObservedNote.fields`) arrives from Anki untyped regardless. Parse the observed side at the boundary instead; that is the move this codebase already makes and documents at `anki/Anki.scala:81-87`.

Two things the role model retires by construction: the `Vector.zip` at `model/CardSpec.scala:333,381` over `ConceptDescriptorFields` and `ClozeSequenceFields` — the two `⚠️ MUST NOT GROW` operands whose only enforcement is a comment and one test — and the hand-built-representatives test at `model/Marker.test.scala:341-375`, whose own docstring concedes a sixth variant's wrong field name would not fail it.

Add `GeneratedCards` — `Fixed(byOrdinal: Vector[(Int, FieldRole)])` for standard types, `FromContent` for cloze — replacing `NoteTypeShape`'s template count. Three places currently re-derive a note's card set independently and two get it wrong; this makes them one derivation.

`CardSpec.Cloze.deletions` gains its first production consumers (the census, the drift detector).

**`CardSpec` keeps its five cases.** Not collapsed into a generic role map: the variants carry different arity and different obligations, and `-Wconf:msg=exhaustive:e` is what makes the compiler ask about a new shape at every consumer. A map does not ask.

### Back end — a role binding, and deferral as a state

Each manifest gains a `roles` key beside `fields`, mapping role to field name in declared order. Total in both directions: every role a spec can emit has a name, and every declared field has a role. The second direction matters — it is how a field comes to be "computed, written, hashed and synced onto 21 of 43 live notes AND RENDERED NOWHERE" (`anki/Anki.scala`'s `createNoteType` docstring, recording that incident).

`ObservedNote.noteType` is parsed at the boundary into `Known(NoteType) | Foreign(raw)`, replacing the bare `!=` at `plan/Planner.scala:334`. Two silent failures close: a one-character difference currently plans a `Retype` forever and, under the default `Defer` policy, never converges; and a foreign type is currently indistinguishable from a mis-cased one.

**Deferral becomes a state of the card.** A note whose observed `NoteType` disagrees with its wanted one carries a *misalignment* — a role permutation, computed from data already fetched — reported **every run, whether or not the retype is deferred, counted separately from failures and deferrals**. The shape to copy is `Report.parkedNote` (`cli/Report.scala:108-117`), which exists because six parked notes sat unmentioned under a run printing "nothing to do".

`isCloze` enters `driftBetween` — but as its **own drift case, marked unrepairable**, because no AnkiConnect action changes a model's kind. Design 1's warning is decisive here and overrides the other two designs' enthusiasm: `InstallOutcome.isClean` goes false on any drift (`anki/NoteTypeInstall.scala:357-360`), so an unrepairable case becomes standing noise unless its wording says "this tool cannot repair this; here is what it means for the retype gate". `FieldsDiffer` splits at the same time into *missing* (writes silently store nothing) and *reordered* (cosmetic, deliberately never repaired), because today both are one case and the reordered one makes `isClean` permanently false already.

### What becomes unrepresentable

1. **A field value with no role.** `fields` is derived from `roles`; a spec arm cannot construct a bare `(String, String)`.
2. **A spec emitting a field name no note type declares.** With `FieldRole` closed and the binding total, a name exists only because a binding declared it. Today the protection is a test over hand-built representatives that concedes it would not catch a new variant — and the failure is invisible on both sides of the wire.
3. **A claim about a note type's card set that ignores its gates.** `NoteTypeShape`'s template count becomes unstateable.
4. **A cloze deletion anchored at a position.** `CardPath.Block` requires a name. No positional case, no content-hash case, no inferred fallback.
5. **A note-type-to-field-list contract nothing consults.** The binding is on the render path; nothing can produce a note without going through it. (`FieldOrder.byNoteType` is the cautionary precedent — the last artefact of that shape drifted inert within days.)
6. **A note type installed without its cloze-ness compared.** `GeneratedCards` cannot be computed without `isCloze`, so `driftBetween` must read it.
7. **An anonymous ordinal rotation.** A cloze note whose ordinal map moved cannot be reported as undifferentiated `FieldsChanged`.
8. **`{{c0::…}}`.**

### What stays representable, deliberately

- **A field role mismatch.** `Front='Framework'` is still a well-typed pair on both sides. The tool now *says so*. It cannot *refuse* you — refusing needs the retype gate, which needs M4.
- **Two `CardSpec` variants on one note type.** `TwoField(Forward)` and `TableRow` both map to `Obsidian Basic` (`model/CardSpec.scala:287,292`), separated only by the `SameShape` gate value. Forcing them apart mints a sixth note type and a migration for every table-row card, to buy a distinction the role model already *expresses*.
- **A gate flip narrowing a note's card set.** Named and counted, not refused — refusing bans a legitimate authoring edit. Whether the blanked card keeps its history until `Empty Cards` is UNVERIFIED (M5).
- **A cloze ordinal rotation.** Reported, blast-radius-shrunk, not prevented. See §4.
- **A wildcard selector.** Refused by rule, not by grammar. Weaker guarantee, chosen deliberately so the refusal can carry a reason.

---

## 4. Cloze, specifically

### The "sniper" complaint

**Answered directly, and the code barely moves.** `Cloze.fromLowered` already takes `(key, blocks, context)` — *blocks*, not a `Section` (`extract/Cloze.scala:51-55`). Handing it one block instead of a section's body needs **no change to that function**. The card is that paragraph, with its highlights blanked. The heading requirement disappears; the bundling disappears with it.

The ceiling is honest and lower than the brief hoped: **paragraph and list item, yes. Sentence, no. Table row, no.** That is still a large win over "promote it to a heading", which is a re-key of the whole section.

### The ordinal drift

**Partially mitigated, reported, not prevented. Stated plainly.**

*Mitigation.* `Cloze.number` shares one counter and one `labels` set across **one call**, and one call is one `fromLowered` over one block vector. Per-block anchoring gives each unit its own counter and its own label set, so **an edit to paragraph A cannot rotate an ordinal in paragraph B**. For the sniper use case — one paragraph, two or three highlights — the drift largely dissolves for new cards. It does nothing for existing section-level clozes. The cost of finer granularity is more keys, hence more things a heading rename or an `id:` change orphans; that trade is what the unrun M1 git-replay measurement would price, and it is unpriced.

*Reporting, two pieces, both free of any new Anki call.*

- **The census.** Print each cloze unit's card count and each group's current ordinal and text, read out of `CardSpec.Cloze.deletions`. This is the missing half of a remedy the project already documents: the tool tells you to write `==1|text==` and has never shown you which number would make that edit free. Without it, following the advice rotates the ordinals it was meant to stabilise.
- **The detector.** At the Update branch the planner holds the observed note's `Text` (already fetched, consumed by nothing) and the `Text` it is about to write. Both are output of the same renderer — `Html.clozeDeletion`'s `{{c${ordinal}::$inner}}` at `content/AsHtml.scala:411-418` — so parsing both into `Map[Int, String]` compares like for like. **This avoids the escaping trap that forced `RecallText` to carry a second copy of its string** (spec HTML-escaped, location raw, so `A & B` and `A &amp; B` fail to match — `model/RecallText.scala`). It fires on exactly one shape: content that was under ordinal *n* and is now under *m ≠ n*. A pure rewording of c1 does not fire.

**Adjudication on placement — Judge 1 is right and Design 3 is wrong.** The finding does **not** go in `Change`. `Change.FieldsChanged` is the executor's write payload (`plan/SyncAction.scala:19-48`); routing a warning through it forces the executor to answer for a non-action. It goes on `Plan` beside `parked`, which has a documented "NO DEFAULT VALUE, deliberately" precedent at `plan/SyncAction.scala:332-344`.

*What would actually prevent it.* **Make the ordinal a stored binding rather than a per-run recomputation** — a fourth `OwnedTag` prefix recording group-identity→ordinal per cloze note, written with the same operation as `sha::`, on which `addTags` is VERIFIED PRESERVES at note level. Every surviving group keeps the ordinal it already holds; only genuinely new groups are allocated. That also makes the retired-group prune entry that `model/CardSpec.scala:42-47` promises **detectable for the first time**, because nothing can detect a group that is gone if nothing remembers it was there.

**Three reasons it is staged after the report, not instead of it.** (a) It mints new stored state in the collection — the one class `docs/findings/EVOLVABILITY.md` §7 says gets pricier with time — under a precondition no test can enforce: adoption must follow a *clean* sync, or it pins a rotation that already happened. The detector is what tells you the sync is clean. (b) Design 2's version does not survive one of its own worked cases: when a label collides with a surviving binding (`==a== ==b==` then `==2|a==`), the labelled group takes 2 by rule and `b`'s binding for 2 must be broken; the resolution rule is unwritten. And the group key for an unlabelled group *is its text* (`ClozeGroup.Unlabelled(text)`), which contains whitespace and cannot go in an Anki tag — so a hash is needed and the design never names it. (c) Whether Anki re-points or regenerates cloze cards on a `Text` rewrite is UNVERIFIED, and if it regenerates, the binding is a mitigation rather than a fix.

*One free guard alongside:* refuse `==0|…==` with a `SpecError`. Grep the real vault for `==0|` first — a refusal that fires on existing content is a behaviour change even when a safe one (the failure is `BuildFailure.KeyKnown`, so `plan/Planner.scala:422-448` unions it into `builtKeys` and the note is neither flagged nor suspended).

*One thing none of the three designs proposed, and it belongs here:* a stray `==highlight==` outside any cloze section is discarded in total silence. Judge 3 found `System Design Pattern.md:8` — `composes into ==architectures==` in preamble prose above the first heading, in a note that has an `id:` and a marked heading. It is scanned and its highlight is dropped without a word. In a project whose posture is no silent success, that deserves one report line — and under this design it is exactly the prompt that says "a `%%card cloze:%%` would work here."

---

## 5. The card that asked the wrong question

**This deserves a separate, smaller answer, and it ships first, alone.**

The harm was not that the note was on the wrong note type. It was that the run reported *"Leaving them is safe: a note that is not moved simply stays on the note type it is on"* (`cli/Report.scala:295-296`, repeated at `:344-345`) — a true claim about **data**, answering a worry the reader has about **review** — and you believed it for 34.5 seconds of a wrong-question card.

Three changes, in order of value per unit of risk:

1. **Rewrite the sentence.** What is true: the note stays on its old note type, so its cards keep asking whatever *that* note type's templates ask — which is not what the vault asks — and every deferred day is a day of reviews spent on a question nobody authored. Cost: two prose blocks. Judge 2 verified that **no test asserts on that text at all**; grep for "Leaving them is safe" hits only `cli/Report.scala`.

2. **Print the evidence beside it.** At `plan/Planner.scala:334` the planner already holds both `existing.note.fields` and `sourced.spec.fields`. Printing them against each other under each named deferred note says, with no role vocabulary at all: *the value the vault calls the Descriptor is sitting in the slot called Front; the value the vault calls the Concept is sitting in the breadcrumb.* Mechanically this needs `SyncAction.Retype` to carry the observed fields — **and that is precedented in the same constructor call**, since `Retype` already carries `preservedTags`, built from the observation at `plan/Planner.scala:357`. Judge 2 verified the blast radius: 3 construction sites, 6 positional patterns, all compiler-guarded.

3. **Count it every run, whether or not the retype is deferred.** `RetypeVerdict.DeferredByPolicy` (`plan/Retyping.scala:142`) names what the *tool* did; the card's condition has no name. Give the misalignment its own report block and its own count, on the `Report.parkedNote` model.

This is also the first consumer of the exact-field-comparison machinery `docs/findings/EVOLVABILITY.md` §4A calls "the strongest thing to come out of this brainstorm" — building it here, for one report, is a strictly smaller first step than building it for rename recovery, and the same code serves both.

**It does not move the note.** Moving it is the growth direction of the retype gate, which is gated on M4 and refused on purpose.

---

## 6. Staging

The governing fact (`docs/findings/EVOLVABILITY.md` §7): **work that touches stored data is the only class that gets pricier with time.** Identity work is cheapest now. Everything else costs the same whenever it is done — which means everything else should be sequenced by *what it unblocks*, not by urgency.

| # | Step | Writes? | Worth on its own | Gate in front of it |
|---|---|---|---|---|
| 0 | Corrected deferral prose + observed-vs-wanted field block + misalignment counted every run | **none** | Answers the measured harm. Standalone. | — |
| 1 | Cloze census (ordinals + texts, from `deletions`); stray-highlight report line; `{{c0::}}` refusal | **none** | Makes the documented labelling remedy usable for the first time | grep vault for `==0\|` |
| 2 | Ordinal-drift detector on `Plan`, beside `parked` | **none** | Rotation stops being indistinguishable from a typo fix; also the precondition detector for step 6 | — |
| 3 | Role model: `FieldRole` + `roles`, `fields` **derived byte-identical**; `ObservedNote.noteType` parsed | **none** | Retires both zip operands and the weak representatives test | **golden file unchanged**, arm-for-arm differential green |
| 4 | `GeneratedCards`; `NoteTypeShape` and `InMemoryAnki.cardCountOf` derived; gate-completeness test generalised to all gates | **none** | Fixes the `ValueOnly` hole in the fake — expect green-and-wrong tests to redden; read each one | step 3 |
| 5 | `isCloze` into `driftBetween` as an **unrepairable** case; `FieldsDiffer` split | **none** | Closes the one property the retype gate calls load-bearing | **M-cloze-kind measured first** (§8) |
| 6 | Cloze ordinal binding (fourth owned prefix, adopt-on-first-run) | **new stored state** | The only prevention | run reports zero actions; **M-cloze-rewrite answered**; keying + label-collision rules written |
| 7 | `CardPath.Block` + the `%%card%%` carrier | **new keys** | The sniper | **M-carrier measured**; steps 0–4 green |
| 8 | `^blockid` stripped from rendered bodies (a latent cosmetic defect regardless) | one Update per affected card | Prerequisite for any future `^id` work; commits to nothing | count affected bodies first |

**Steps 0–5 are `[SAME-COST-WHENEVER]` and reversible: reverting the code reverts the behaviour, because nothing was written differently.** Steps 6–7 are the ones §7 prices as now-or-pricier — which is the argument for doing 7 while the collection is small, and equally the argument for not doing it before the steps that make it observable.

**The one non-obvious ordering constraint:** step 2 gates step 6. You cannot safely adopt existing ordinals as bindings without knowing no rotation is already pending, and step 2 is the only thing that can tell you.

---

## 7. What was rejected

**Obsidian block references as the anchor** (Design 2's front end). Attractive — the vault is full of them, they are immune to rewording, they cost nothing in the tag codec. Killed by four verified facts and one unverified one: they print on the card face today; they need a new span parser; the owner's 125 real ones sit inside callouts the strict parser refuses, so the mechanism is unreachable in the actual corpus until a callout parser exists; they cannot address a sentence or a table row anyway; and their per-file uniqueness is unverified, which means `(noteId, Block(id))` may not be a key at all. Also: `^id` in this vault already means "Zotero link target" 125 times and "card" zero times, so it cannot signal *card* on its own — Design 2 concedes it needs `%%flashcard/cloze%%` alongside, which is two constructs where the comment carrier is one.

**`CardSpec.fields` becoming a role-keyed type on the wire** (Design 2's IR). The diagnosis is right — the erasure is one line — but the fix as proposed changes the wire format of the whole write path, is one atomic change rather than three shippable ones, and cannot type the observed side anyway. The derived-projection version gets the same guarantees and keeps the golden file as the gate.

**Doing nothing but reporting** (Design 1, taken whole). Its observations are the sharpest in the set and its sequencing discipline governs this document. But scored on what becomes unrepresentable it makes exactly one wrong state illegal, and it answers a project whose most-recorded failure mode is prose decay (`docs/findings/EVOLVABILITY.md` §3.10) with two more prose blocks. Its own concession — "I am adding to the surface that decays" — is accurate. It is the right *first slice*, not the right *design*.

**`Change.OrdinalsMoved`** (Design 3's placement). A finding that is not an action does not belong in the executor's write payload.

**Sentence-level cloze.** Not a node anywhere in the stack. Refused rather than faked.

**An ordinal in `CardKey`.** Contradicts `docs/reference/CARD-MODEL.md:330` and misdescribes Anki: a cloze note's cards come from content, so a per-ordinal key would name a thing the tool cannot independently create or delete. It would also re-key every existing cloze card — the one change in this space that costs review history.

**Wildcard selectors in a declarations block.** `- ^*: cloze` would turn one literature note into 125 cards from one line. Silent card creation, refused by name.

**Disambiguating `Obsidian Basic`'s two meanings.** Real (`TwoField(Forward)` and `TableRow` mean structurally different things), and left alone: refusing it mints a sixth note type and a migration for every table-row card to buy a distinction the role model already reports.

---

## 8. What must be measured

| Measurement | How | What it decides |
|---|---|---|
| **M-cloze-rewrite.** Does `updateNoteFields` re-point existing cloze cards at new content for their ordinal, or regenerate them? Does the revlog follow the card row or the ordinal? | Throwaway profile. `{{c1::a}} {{c2::b}}`, review both, record card ids + `ord` + ivl/reps via `cardsInfo` (`ord` is exposed — VERIFIED at add-on `__init__.py:1551-1555`). Rewrite to `{{c1::z}} {{c2::a}} {{c3::b}}`, re-read. **Cannot be settled by reading** — the add-on delegates to one call into the compiled Rust backend. | Whether §3.2's harm story is the right harm story at all, and whether the ordinal binding (step 6) is a fix or a mitigation. Gates any claim that cloze editing is safe. |
| **M-carrier.** Does Obsidian's editor leave a `#`-less token inside `%%…%%` alone? And is the recorded tag-lift behaviour at `extract/Extractor.scala:99-106` as broad as stated, given `Function.md` carries `#check` in body prose un-lifted? | Live Obsidian, throwaway vault. Not `/Users/marc/srs-in-obsidian-test/`. | Step 7 entirely. If it goes the wrong way, the block-name identity work has nothing to carry it. Minutes, and it gates a change to key derivation. |
| **M-block-unique.** Does Obsidian enforce (or deterministically resolve) block-id uniqueness within a file? | Throwaway vault: duplicate one `^id`, click a `[[file#^id]]` link. | Only matters if `^id` is ever revived as an anchor. Currently below the line. |
| **M-cloze-kind.** Does the live collection's `Obsidian Cloze` carry `MODEL_CLOZE` (type=1) and `Obsidian Cloze Sequence` `MODEL_STD`? Read once 2026-08-21, never re-checked. | Read-only `findModelsByName`, read the `type` key (0=STD, 1=CLOZE per `anki/consts.py`, cited at `anki/Anki.scala:195`). Anki running, no writes. | Step 5. If the answer is wrong, the drift case fires on the first run and makes `install-note-types` permanently non-clean with no repair path — so the wording must exist before the check does. Also: whether the retype gate's first and most consequential test is currently reading the truth. |
| **M5 (`docs/findings/EVOLVABILITY.md` §6).** Is a card whose front renders empty (gate field cleared) kept with its revlog until `Tools > Empty Cards`, and does Empty Cards take the revlog rows? | Throwaway profile; read the dialogue's report before confirming. | Whether a narrowed card set is a missing *report* or a live *loss*, and whether any richer role model that adds gate fields is cheap or is minting destroyable state. |
| **M-addnote-fields.** Does `addNote` report an undeclared field as "cannot create note because it is empty" and `updateNote` ignore it silently? Are names case-folded there as they demonstrably are in `changeNoteType`? | Read the add-on's `__init__.py` around those actions and the `anki.notes.Note` assignment path, and cite lines — the treatment `changeNoteType` already got. **No collection needed.** | How much `NoteTypeInstaller.readiness` actually buys, and whether a runtime emitted-shape check adds anything over the test-only chain. |
| **M-vault-census.** How many `#flashcard/cloze` sections in the real vault use unlabelled deletions, and how many each? How many synced bodies already contain a stray `^id`? How many notes are currently role-mismatched? | Read-only, existing `inspect` command / a grep. | Whether the drift is the common case or an edge case — the fixture's 4-of-5 unlabelled ratio suggests the former, which would mean "labelling is how you buy stability" describes a discipline nobody follows. Also sizes step 8 and tells you whether misalignment is a report line or a subsystem. |
| **M1 (`docs/findings/EVOLVABILITY.md` §6, unrun).** Git-replay: how often do headings actually get reworded? | As specified in §6. | Prices the granularity trade — finer cloze units buy ordinal isolation and pay in key count, and nothing currently knows the exchange rate. |