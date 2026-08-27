"""Redirect Anki's Edit action to the source note in Obsidian.

Reviewing is when you notice a card is badly worded, and Anki is the one place the wording must
not be fixed: an edit made there never reaches the vault and is overwritten by the next sync.
So for a card this tool created, Edit opens the note in Obsidian. For every other card in the
collection it opens Anki's editor, exactly as before.

==Why a dialog registration and not a monkeypatch==

Every route to the reviewer's Edit -- the `e` shortcut, its alternate-layout twin, and the More
menu -- calls `AnkiQt.onEditCurrent`, which does nothing but `aqt.dialogs.open("EditCurrent")`.
One choke point. `DialogManager.register_dialog` is documented for add-on authors to replace
what that opens; `onEditCurrent` is an internal that merely happens to be patchable. Verified
against Anki 25.09.5.

==What is untested here, said plainly rather than implied==

`aqt` imports only inside a running Anki, so NOTHING IN THIS FILE HAS A UNIT TEST. That is why
it is thin: every judgement lives in `core.py`, which is a pure function of strings and is
tested. What is left here is reading configuration, starting a process, and handing the result
to one of three branches. `cli/Main.scala` makes the same admission about its own shell.

THE BRANCH THAT MATTERS MOST IS THE ONE THAT CANNOT BE TESTED AT ALL: a note with no `src::`
tag must reach Anki's editor untouched. It is every note this tool did not create. It is kept
to a single call with nothing between it and `EditCurrent` that could fail.
"""

from __future__ import annotations

import os
import subprocess
from typing import Any

import aqt
from aqt import gui_hooks
from aqt.utils import openLink, tooltip

from . import core

#: Long enough for a cold JVM on a large vault, short enough that a wedged process does not look
#: like a frozen Anki. A `locate` run against a small vault takes well under a second.
TIMEOUT_SECONDS = 20


def _delegate(main_window: Any) -> Any:
    """Anki's own editor, unchanged.

    RETURNED, NOT JUST CALLED. The dialog manager stores whatever the creator hands back as the
    live instance, so returning the dialog is what registers it. Returning nothing here would
    leave Anki believing no editor is open while one is on screen.

    This names `EditCurrent` directly rather than reading whatever was registered before us,
    because the registry entry is private. The cost is that we do not chain with another add-on
    that has replaced the same dialog -- worth knowing, and not worth reaching into a private
    field for.
    """
    return aqt.editcurrent.EditCurrent(main_window)


def _locate(tag: str) -> core.Verdict:
    """Ask the sync tool where the card came from.

    THE SUBPROCESS IS THE WHOLE POINT OF THE DESIGN. Decoding the identity tag here would mean a
    second implementation of the normalisation every card's identity passes through, in a second
    language, held honest only by a test. The tool that wrote the tag is the thing that reads it.
    """
    config = aqt.mw.addonManager.getConfig(__name__) or {}
    binary = config.get("binary")
    vault_path = config.get("vault_path")
    if not binary or not vault_path:
        return core.Explain(
            "This add-on has not been configured. Open Tools > Add-ons, select "
            "'Edit in Obsidian', press Config, and fill in 'binary' and 'vault_path'."
        )

    java_home = config.get("java_home")
    argv = core.command(binary, vault_path, tag, config.get("vault_name"))
    try:
        done = subprocess.run(
            argv,
            capture_output=True,
            text=True,
            timeout=TIMEOUT_SECONDS,
            check=False,
            env=core.environment(os.environ, java_home),
        )
    except FileNotFoundError:
        return core.Explain(f"Could not run '{binary}'. Check 'binary' in this add-on's config.")
    except subprocess.TimeoutExpired:
        return core.Explain(f"'{binary} locate' did not finish within {TIMEOUT_SECONDS} seconds.")

    verdict = core.interpret(done.stdout, done.stderr)
    # The launcher's "Unable to locate a Java Runtime" is true and unhelpful: it says a JVM is
    # missing when one is installed and merely unreachable from a process Anki started. The
    # underlying message is still shown; this only adds the sentence that says what to change.
    if isinstance(verdict, core.Explain) and not java_home and core.missing_java(verdict.message):
        return core.Explain(verdict.message + "\n\n" + core.JAVA_HINT)
    return verdict


def edit_current(main_window: Any) -> Any:
    """The replacement creator. Called by Anki every time Edit is pressed.

    ONE OF THREE THINGS ALWAYS HAPPENS, and never nothing: Obsidian opens, or Anki's editor
    opens, or both a message and Anki's editor. A keypress that does nothing is indistinguishable
    from a broken add-on, so it is not a state this function can reach.
    """
    reviewer = getattr(main_window, "reviewer", None)
    card = getattr(reviewer, "card", None) if reviewer else None
    if card is None:
        return _delegate(main_window)

    tag = core.source_tag(card.note().tags)
    if tag is None:
        return _delegate(main_window)

    verdict = _locate(tag)

    if isinstance(verdict, core.Open):
        if verdict.caveat:
            # Shown BECAUSE the note still opens. This is the caveat that a heading was reworded
            # -- which retires the card and loses its review history -- and it is the last moment
            # anyone can act on that.
            tooltip(verdict.caveat, period=6000)
        openLink(verdict.uri)
        # Nothing is returned, which is the dialog manager's own value for "no window is open".
        # It is also what makes the NEXT press re-enter this function rather than try to raise a
        # window that was never created.
        return None

    if isinstance(verdict, core.Explain):
        tooltip(verdict.message, period=8000)
        return _delegate(main_window)

    return _delegate(main_window)


# ------------------------------------------------------------------ drilling ----
#
# The other direction: gather one note's cards into a temporary deck and study them now,
# whatever their due dates say. This is what Anki's own Custom Study builds -- a FILTERED deck --
# and it is the right mechanism precisely because it is temporary: cards are borrowed, not moved,
# and every card remembers the deck it came from.
#
# ANKICONNECT CANNOT DO THIS. Its 121 actions include `createDeck` and `changeDeck`, and neither
# is this: `changeDeck` moves cards PERMANENTLY, which is the wrong tool wearing the right name.
# Anki's own Python has the API, and this add-on runs inside Anki, so it is reachable from here.

#: More than any one note will ever produce. A filtered deck needs a limit; this one is not
#: trying to be a limit.
DRILL_LIMIT = 9999


def _drill_decks() -> list[Any]:
    """Every deck this add-on built, and nothing else.

    THE PREFIX IS THE ONLY THING between a tidy-up and somebody's collection, which is why the
    test for it lives in `core.py` with its own tests rather than being written inline here.
    """
    return [
        d for d in aqt.mw.col.decks.all_names_and_ids(include_filtered=True)
        if core.is_drill_deck(d.name)
    ]


def sweep_drills(only_finished: bool) -> int:
    """Remove drill decks, returning their cards home first.

    EMPTYING BEFORE REMOVING IS NOT BELT AND BRACES. `empty_filtered_deck` is the operation that
    puts each card back in the deck it was borrowed from; what `remove` does with a deck that
    still holds cards lives in Anki's Rust backend, where this add-on cannot read it. Emptying
    first means the removal is only ever the removal of an empty deck, which needs no assumption.

    `only_finished` skips decks that still hold cards, so triggering a second drill does not
    destroy a session in progress. The unconditional sweep is for shutdown.
    """
    from anki.decks import DeckId

    col = aqt.mw.col
    removing = []
    for deck in _drill_decks():
        did = DeckId(deck.id)
        if only_finished and col.decks.card_count(did, include_subdecks=False) > 0:
            continue
        col.sched.empty_filtered_deck(did)
        removing.append(did)
    if removing:
        col.decks.remove(removing)
    return len(removing)


def drill(search: str, deck_name: str) -> None:
    """Gather what `search` matches into a temporary deck and start studying it.

    RESCHEDULING IS OFF. Answers here do not touch a card's real interval, so the same note can
    be drilled before an exam as often as you like without distorting the schedule you have
    built. It is the difference between practising and reviewing.

    AN EMPTY RESULT REMOVES THE DECK AGAIN rather than leaving one behind saying "Congratulations".
    A drill that gathered nothing is a question about the note -- is anything marked, has it ever
    synced -- and it should read as an answer to that, not as a finished session.
    """
    from anki.decks import DeckId
    from anki.decks_pb2 import Deck

    col = aqt.mw.col
    sweep_drills(only_finished=True)

    deck = col.sched.get_or_create_filtered_deck(DeckId(0))
    deck.name = deck_name
    deck.config.reschedule = False
    del deck.config.search_terms[:]
    term = deck.config.search_terms.add()
    term.search = search
    term.limit = DRILL_LIMIT
    term.order = Deck.Filtered.SearchTerm.Order.Value("RANDOM")

    did = DeckId(col.sched.add_or_update_filtered_deck(deck).id)
    col.sched.rebuild_filtered_deck(did)

    gathered = col.decks.card_count(did, include_subdecks=False)
    if gathered == 0:
        col.sched.empty_filtered_deck(did)
        col.decks.remove([did])
        tooltip("Nothing to drill: this note has no unsuspended cards in the collection.", period=6000)
        return

    col.decks.select(did)
    aqt.mw.moveToState("overview")
    tooltip(f"{gathered} card{'s' if gathered != 1 else ''} gathered — nothing here is rescheduled.")


def drill_note(note_id: str, title: str) -> None:
    """The entry point Obsidian reaches: one note's frontmatter id, and what to call the deck."""
    drill(core.drill_search_for_id(note_id), core.drill_deck_name(title))


def _drill_current_browser_search(browser: Any) -> None:
    """Drill whatever the Browse window is showing.

    Deliberately NOT restricted to this tool's cards: someone looking at a search in Anki and
    wanting to study it has the same need, and the mechanism does not care where the cards
    came from.
    """
    search = browser.current_search().strip()
    if not search:
        tooltip("Type a search in the browser first.")
        return
    drill(search, core.drill_deck_name(search[:60]))


def _add_browser_action(browser: Any) -> None:
    action = browser.form.menu_Cards.addAction("Study these cards now (temporary deck)")
    action.triggered.connect(lambda _=False, b=browser: _drill_current_browser_search(b))


def _register_ankiconnect_action() -> None:
    """Let Obsidian reach `drill_from_tag` through AnkiConnect, which is already listening.

    ANKICONNECT DISPATCHES BY INSPECTING ITS OWN METHODS for an `api` attribute, so an action can
    be added to its class from here. That is an internal of another add-on and it is being used
    knowingly. It is a different risk from the traps this project met while wiring the Obsidian
    side: if AnkiConnect ever changes how it dispatches, the action is simply not found and the
    caller is told so. LOUD, not silent.

    Failure to attach is not fatal -- the Browse action still works, and this add-on's main job,
    redirecting Edit, does not involve AnkiConnect at all.
    """
    import sys

    module = sys.modules.get("2055492159")  # AnkiConnect's add-on id, and its module name
    if module is None or not hasattr(module, "AnkiConnect"):
        return

    def studyFromNote(self: Any, noteId: str = "", title: str = "") -> int:  # noqa: N802
        drill_note(noteId, title)
        return 1

    studyFromNote.api = True  # type: ignore[attr-defined]
    setattr(module.AnkiConnect, "studyFromNote", studyFromNote)


def _on_main_window_init() -> None:
    # AFTER the main window exists, so load order between add-ons is not being relied on.
    _register_ankiconnect_action()


gui_hooks.main_window_did_init.append(_on_main_window_init)
gui_hooks.browser_menus_did_init.append(_add_browser_action)
# NOTHING THIS ADD-ON BUILT SURVIVES THE SESSION. Unconditional, because a half-finished drill
# is not worth keeping: the cards are already home, and re-triggering it costs one keystroke.
gui_hooks.profile_will_close.append(lambda: sweep_drills(only_finished=False))

aqt.dialogs.register_dialog("EditCurrent", edit_current)
