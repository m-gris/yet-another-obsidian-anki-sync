# Obsidian Cloze Sequence — a note type this tool installs

A list that reveals itself one item at a time, as **one Anki card on one schedule**.

> **This note type is being renamed.** It exists in the collection as `Cloze Sequence` and
> becomes `Obsidian Cloze Sequence`, so that the five note types this tool writes to are its
> own and a template change can never reach the rest of the collection. AnkiConnect has no
> action that renames a model (verified 2026-08-21 against the running add-on's own action
> list), so the rename is done by hand in *Tools → Manage Note Types*. `manifest.json` records
> both names — see the `renamedFrom` key and `../README.md`.

## Why a note type at all

Anki ships nothing list-shaped. The obvious alternatives were tried and rejected, and the
reason matters more than the conclusion:

- **Plain cloze** (`{{c1::a}} {{c2::b}}`) hides one item and shows the rest. Not disclosure.
- **Nested cloze** (`{{c1::{{c2::b}}}}`) *does* produce progressive disclosure — verified live —
  but as **three independently scheduled cards**. Anki will happily show card 3, which reveals
  the first two items, long before card 1 has ever been seen. The order exists in the text and
  nothing enforces it in the review.
- **A note type with one template per step** has the same defect for the same reason.

One card is the whole point: a list is a single unit of knowledge, so it gets a single schedule.

## Provenance

Upstream: <https://github.com/tekinosman/cloze-sequence>, MIT (`LICENSE.upstream`).
The templates here are **modified** — see below. Nothing is vendored that upstream still owns
except the licence.

The upstream project ships a packaged deck to import by hand. This tool does not use it: the
files here are installed through AnkiConnect's `createModel`, so **nothing has to be installed
on any device**, including phones.

## What was changed, and why

**Keyboard reveal.** Upstream reveals by clicking. Anki registers its reviewer shortcuts
*above* the web view, so a card's script cannot intercept a key Anki already binds — an attempt
to use Enter lost that race and revealed everything at once. `j` / `↓` reveal the next item and
`k` / `↑` hide the last; Enter, Space and 1-4 keep their usual meanings. Clicking still works.

**The reveal moved to the answer side.** Enter now means "show answer" on this card exactly as
it does on every other card in the collection, and revealing happens after it. Upstream's back
template was `{{FrontSide}}` plus an opacity change, so pressing "show answer" appeared to do
nothing at all.

**The delimiter is the `li` tag**, as upstream's repository (not its packaged deck) already had
it. So a plain markdown list needs no special syntax in the vault: every `<li>` becomes an item.

**A `Context` breadcrumb on the question side.** Added by this repository, not by upstream. The
front template gains one line above `<h4>{{Title}}</h4>`, and the stylesheet gains a `.context`
rule. The reason, the placement rule and the two things about it that are unverified are all in
`../README.md`; the short version is that the heading chain a card came from is what makes its
prompt answerable, and it must sit **outside** `<div id="text">` because that div is the dimmed,
hidden answer.

## Fields

`Title`, `Text`, `Context` — declared in `manifest.json`, which replaced the earlier
`fields.json`. `Text` holds an HTML list. `Context` is last, and is empty on cards whose
heading chain is empty; `{{#Context}}` then emits nothing at all.

## Installing it

`createModel`, driven by `manifest.json`: the field list, the model name, the one template name
(`Cloze Sequence`) and the paths of the three files that carry its text — `styling.css` and the
two under `templates/`. It is a plain note type: no add-on, no Python, and it reviews on
AnkiMobile and AnkiDroid.

The installer is `anki/NoteTypeInstall.scala`, driven by the `install-note-types` command. It
creates only note types that are ABSENT; one that is present and differs from these files is
reported and left alone. **It has not been run against a live collection by any agent.**

**Template names must match the collection exactly.** AnkiConnect's `updateModelTemplates`
looks each template up by name and silently skips names it does not recognise (read out of the
add-on source, `__init__.py:1294-1312`, on 2026-08-21) — so a wrong name is a repair that
reports success and changes nothing. This type's single template is named `Cloze Sequence`,
captured verbatim from the live collection; the file slug under `templates/` is documentation
only.

## Byte-identity

The two files under `templates/` and `styling.css` were all three **byte-identical** to what
profile `claude-POC-test` holds — checked on 2026-08-21 against `modelTemplates` and
`modelStyling` respectively. Since that check, `templates/cloze-sequence.front.html` has gained
exactly one line (the `Context` snippet) and `styling.css` one appended `.context` rule;
`templates/cloze-sequence.back.html`, which carries the keyboard-reveal script, is untouched.
Keeping these files equal to the collection is what lets a drift check be a string comparison
rather than a re-derivation.
