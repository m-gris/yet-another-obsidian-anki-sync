# In flight — delete this file when the work below has landed

_Rewritten 2026-08-22. Everything here was checked, not remembered. **Nothing is running.**
Working tree clean under `obsidian-anki-custom-sync/`; 574 tests, 0 failures._

## Where things stand

Card identity has not moved once across everything below: no `src::` line in
`extract/golden/fixture-cards.txt` has changed, so every note is an UPDATE and keeps its review
history. That is the acceptance invariant and it still holds.

Verified live against profile `claude-POC-test`, not asserted:

- table cards render as **tables with the asked-for cell blanked**, the same table on both sides
- the row card renders as one table (was a run-on line)
- orphans are **suspended** and recover when the heading returns
- a broken table section no longer retires the cards it produced

## OPEN — decisions only Marc can make

1. **Should sync report cards that will render blank?** Switching a table to fewer directions
   leaves cards whose front is empty. Anki keeps them and shows "The front of this card is
   blank" until Tools → Empty Cards is run. Options: say nothing (Anki's own message is clear),
   or have sync count them and say so. **There are 4 such cards in `claude-POC-test` right now**,
   left by a `3way` experiment.
2. **Content-duplicate report.** Two cards with identical fields under different keys trip
   nothing: `allowDuplicate` is on, uniqueness is checked by key, nothing compares content. It
   also silently weakens the hash-based repair hint, which only fires when exactly one card
   matches a hash. Cheap to detect. Refusing would be wrong — two tables legitimately defining
   one term is the author's business — so it would have to be a report. Marc raised this as
   "shouldn't we have dedup?".
3. **Row scope at `2way`/`3way`** — a row card that blanks the CONCEPT rather than the values
   ("which bone has these two borders?"). Needs a second template on the plain note type. Not
   designed.

## OPEN — work with no decision left in it

4. ~~**A `#flashcard/table` section can yield ZERO cards and say nothing.**~~ **CLOSED
   2026-08-22** (`1632133`, `257596c`). The gate in `extract/Tables.scala` is now "at least one
   descriptor column whose header can name a card" rather than "at least one descriptor
   column", so a blank or marker-only header refuses instead of dropping its column in silence.
   Measured on a scratch vault: before, `inspect` said 0 cards, 0 failures, `scan: complete`,
   exit 0; after, it names the file, the line and the remedy, exit 1. A table with one unusable
   column among usable ones is unaffected — that is the control test.
   _The empty-rows route is deliberately NOT an error except under `rows` — a table with a
   header and no rows yet is work in progress._
5. ~~**Composable deck path.**~~ **CLOSED 2026-08-22** (`2dd3ff9`, `b4b967c`, `7cd080a`,
   `7d18960`, `9c3cafd`). `inspect` and `sync` take `--deck-from`, a list naming which of a
   card's folder path, file name and heading path become deck levels — `folders`, `file`,
   `headings`, or `none` for one flat deck. The default is `folders`, which is where every
   already-synced card sits.
   Marc's worked example is `--deck-from folders,headings`, giving
   `Obsidian::System-Design::Replication::Read-your-writes consistency`; the file name is left
   out because this vault's H1 already carries it, and selecting both would repeat it. That
   repeat is shown rather than de-duplicated — a rule dropping it would also drop a heading
   that genuinely repeats its parent.
   Verified live against `claude-POC-test`, dry-run only: the default shape plans **no deck
   move at all** for the 43 synced cards, and `folders,headings` plans **43 moves and no
   content change**.
6. **`prune`** — the command that deletes flagged cards after the list has been reviewed.
7. **A formatter, and a keybinding** — re-indent nested lists to four spaces so `ListIndent`
   stops refusing them. Marc: "probably trivial", separate session.
8. **Recovery tiers 3 and 4** — matching a broken identity tag by SIMILAR content when the body
   was edited too. Tiers 1 and 2 (exact hash, exact fields) are built. Anything fuzzy may only
   RANK candidates, never apply one.
9. **`ZZ-probe-delete-me`** — a note type left in `claude-POC-test` by a migration probe.
   AnkiConnect has no delete-model action; Tools → Manage Note Types.

## Rulings that are settled — do not reopen

- **The list marker is EXPLICIT.** The tool can see a list is present; it can never see whether
  the ORDER is the knowledge.
- **Under-indented nested lists are REFUSED, not repaired.** The parser has already consumed the
  indentation, so repair would be a guess.
- **The tool writes only to its OWN five note types**, never Anki's stock ones.
- **A note type that already exists is never overwritten without `--repair`.**
- **Suspending an orphan is a POSTCONDITION of flagging, not an invariant.** Anki records no
  authorship for a suspended card, so the tool cannot tell its own suspension from Marc's, and
  an invariant it cannot enforce would be a claim of ownership it does not have. It never
  re-suspends and never warns. See HANDOFF.md § "Things that look like bugs and are not".
- **New note-type fields go LAST in a manifest.** `modelFieldAdd` APPENDS, so any other position
  leaves a repaired collection permanently reporting a field-order difference.
- **Gate fields are INVERTED — empty means the old behaviour.** Written the other way round,
  every note predating a field would render blank between the note type gaining it and the next
  sync, and Anki would offer to delete cards holding real history.
- **Presentation and organisation are composable OPTIONS; correctness is not.** REQUIREMENTS.md
  item 11. An option to be silently wrong is a defect with a switch on it.
- **Never touch profile `User 1`** or `/Users/marc/srs-in-obsidian-test/`.

## Fixtures that must not be "tidied"

- `dummy-vault/Patterns/Shallow-Nesting.md` — its two-space nesting IS the fixture.
- `dummy-vault/Patterns/Table-Edge-Cases.md` — deliberate duplicate identities. Excluded from a
  live sync by copying the vault and deleting this file; leaving it in aborts the plan.

## Method that has been catching real defects

Write the test, then MUTATE the production code and check the test dies — with a control mutant
that must survive. It has caught a wrong assertion, a hollow ordering test, and a verification
step that reported intentions rather than readings. **Commit before mutating**: the restore step
is `git checkout`.
