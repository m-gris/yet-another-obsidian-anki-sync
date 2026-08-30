# What Anki and AnkiConnect actually do, as measured

_Written 2026-08-28. Every claim here was established by running something against a live
collection or by reading the installed add-on's own source, and each one says which. It follows
this repository's convention of opening with the answer rather than the three-layer TLDR /
Summary / Full form, because its siblings — `EVOLVABILITY.md`, `CLOZE-REDESIGN.md`,
`PARSER-DISAGREEMENTS.md` — do._

---

## The one-paragraph answer

This tool depends on an add-on it does not ship, talking to an application it does not control,
and several of that add-on's behaviours are neither documented nor guessable from the action's
name. **One action does something entirely unlike what it is called, one returns success for
work it has only scheduled, and two operations the tool might reasonably want do not exist at
all.** This document is where such findings are collected so the next person meets them before
the collection does. It is an index rather than a new home: the findings that are load-bearing
for a particular decision stay in the comment beside that decision, and are linked from here.

## The coordinates every claim below is pinned to

None of this is a property of "Anki". It is a property of these versions, and it must be
re-measured if any of them move.

```
Anki                      25.09
AnkiConnect add-on        2055492159
  meta.json               min_point_version 45, max_point_version 45
  __init__.py:18          required_anki_version = (23, 10, 0)
JSON API version          6   (what the `version` action returns)
measured in profile       claude-POC-test   (never `User 1`, never the real vault's profile)
```

Re-read them with `defaults read /Applications/Anki.app/Contents/Info.plist
CFBundleShortVersionString` and by reading `meta.json` in the add-on directory. The add-on's
source is on disk and is the primary reference for anything below marked READ.

## The findings

### `loadProfile` reports success for work it has only scheduled

READ, `__init__.py:474-498`. When the main window is visible and the requested profile differs
from the open one, it calls `unloadProfileAndShowProfileManager`, then starts a one-second
`QTimer` that polls until the window has closed and only then loads the target. **It returns
`True` at `:498` having merely started that waiter.** The return value therefore means
"scheduled", not "switched", and there is an interval afterwards during which no collection is
loaded.

Its own comment says the waiter exists because calling `loadProfile` while a sync is still
running makes things go wrong.

**Consequence.** Any caller would have to poll `getActiveProfile` until it reported the target.
This tool does not call it at all, for the independent and stronger reason recorded at
`anki/AnkiConnectClient.scala:60`: switching profiles would close whatever collection the person
currently has open, possibly mid-review, which is a side effect nobody asked for.

### A profile assertion is worth nothing unless it gates the write

MEASURED 2026-08-28, by getting it wrong. A script issued `getActiveProfile` and `createDeck` in
one batch. The profile had changed since the previous command, so the assertion reported the
mismatch *after* the deck had already been created in the wrong collection. The deck was empty
and was removed, but the lesson is structural rather than procedural.

**Consequence.** Assert, read the answer, then write. `cli/Main.scala`'s `withVerifiedProfile`
already has the correct shape, wrapping the operation rather than accompanying it. Ad-hoc probing
must copy that shape rather than approximate it.

### `createModel` returns Anki's own card-generation rule

MEASURED 2026-08-28. The response includes a `req` array stating, per template, which fields must
be non-empty for that template to produce a card. For a note type whose second and third fronts
were wrapped in `{{#Value2}}` and `{{#Value3}}`:

```
"req": [[0, "any", [0, 1]], [1, "all", [4]], [2, "all", [6]]]
```

Read as template ordinal, quantifier, field ordinals. Template 2 requires field 6, which is
`Value3`.

**Consequence.** Conditional card generation is an inspectable property of the note type rather
than something inferred from template text. A drift check could assert it directly.

### Filling a gated field on an existing note adds a card and disturbs nothing

**FIRST MEASURED 2026-08-26 as M5's flip half** (`docs/findings/EVOLVABILITY.md` §6), on the shipped
concept-descriptor note type. What follows is a REPRODUCTION on a purpose-built note type, run on
2026-08-28 while designing something else and without checking §6 first. It is recorded because
the reproduction generalises the result from one shipped note type to conditional generation as
such, not because it discovered anything.

MEASURED 2026-08-28. A note was created with its third column empty, producing two cards. Both
were answered so they carried real scheduling. The third column's fields were then filled through
`updateNoteFields`.

A third card appeared. The two existing cards kept their card id, their ordinal, their review
count, their due timestamp and their queue, all unchanged.

**Consequence.** Widening a note that uses conditional generation is an ordinary field update. It
needs no note type change, mints no new note, and moves no review history.

### Emptying a gated field strands the card rather than destroying it

**ALSO FIRST ESTABLISHED BY M5 on 2026-08-26**, and reproduced here. The round trip in the next
finding is the part that was genuinely open.

MEASURED 2026-08-28, same session. The third column was answered once, then emptied.

The card was still there. It kept its review count and its due timestamp, and its rendered
question became Anki's own placeholder, beginning `The front of this card is blank.` Anki removes
such cards only through Tools then Empty Cards, run by a person.

**Consequence.** Narrowing is not symmetric with widening. Something has to be decided about the
stranded card, and the tool cannot clear it. See the next finding for the trap waiting there.

### The round trip is lossless

MEASURED 2026-08-28. Restoring the emptied column brought back the same card id, the same
ordinal, the same review count and the same due timestamp.

**Consequence.** A card that returns to its original template ordinal recovers its history in
full. Any scheme that places a value back into the slot it previously occupied inherits this
for free.

### `removeEmptyNotes` deletes unused NOTE TYPES

READ, `__init__.py:1771-1775`. It iterates every model in the collection and removes each one
whose `use_count` is zero. It does not touch notes, and it does not touch empty cards.

**Consequence.** This is the action a reader will find when searching the add-on for a way to
tidy blank cards, and calling it would instead delete every note type that currently has no
notes. Name recognition is actively misleading here.

### Two operations do not exist at all

READ, by searching the add-on's action definitions.

- **No empty-cards action.** Nothing corresponds to Tools then Empty Cards. A stranded card can
  only be cleared by a person inside Anki.
- **No model deletion.** There is no `deleteModel`. A note type created for an experiment stays
  until removed by hand through Tools then Manage Note Types.

### `deleteDecks` refuses to spare the cards

MEASURED 2026-08-28. Called with `cardsToo` false it fails with:

```
Since Anki 2.1.28 it's not possible to delete decks without deleting cards as well
```

**Consequence.** Deleting a deck is always potentially destructive, so the count of cards in it
must be checked before the call rather than relying on a flag to make the call safe.

## What is not measured

**Whether Anki's scheduler serves a card whose front renders blank.** The stranded card retained
its queue value and its due timestamp, which is evidence that it stays in scheduling, but no test
established whether it is actually presented in a review session. This matters because entry 23
of `docs/history/IN-FLIGHT.md` rules for a per-card flag over suspension partly on the grounds that "a retired
card's front renders empty and Anki never queues it." That sentence is load-bearing and is
currently in doubt.

The measurement is small: give such a card a review-state due date, empty its gating field, and
compare the deck's scheduler counts before and after.

## What belongs in this document, and what does not

A finding belongs here when it is a fact about **the add-on or the application as an external
dependency**, version-scoped, and surprising enough that someone would otherwise rediscover it by
damaging a collection.

A finding does not move here when it is load-bearing for a specific decision in the code. Those
stay in the comment beside the decision, where the person changing that code will actually read
them, and this document links to them instead. Existing examples worth knowing about:

- `anki/AnkiConnectClient.scala:60` — why `loadProfile` is never called.
- `anki/AnkiConnectClient.scala:84` — what `modelTemplates` answers for a model that is absent.
- `anki/Anki.scala:111-113` — `createModel` is not an upsert, and the wire message for a
  duplicate name does not name the model.
