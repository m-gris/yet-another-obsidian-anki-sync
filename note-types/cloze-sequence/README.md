# Cloze Sequence — a note type this tool installs

A list that reveals itself one item at a time, as **one Anki card on one schedule**.

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

## Fields

`Title`, `Text` — see `fields.json`. `Text` holds an HTML list.

## Installing it

`createModel` with these four files. It is a plain note type: no add-on, no Python, and it
reviews on AnkiMobile and AnkiDroid.
