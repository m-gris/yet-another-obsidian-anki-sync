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


aqt.dialogs.register_dialog("EditCurrent", edit_current)
