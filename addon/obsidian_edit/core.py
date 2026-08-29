"""Everything the add-on decides, with no Anki in sight.

`aqt` imports only inside a running Anki, so the wiring in `__init__.py` cannot be unit-tested
at all. This module is the other half of that split: every judgement the add-on makes is a pure
function of strings and lives here, where it can be driven directly. What is left in the shell
is registering a dialog, reading configuration and starting a process.

THE ONE CASE THAT MUST NEVER BREAK IS THE ONE WITH NO TEST: a note carrying no `src::` tag has
to reach Anki's own editor untouched. That is every note in the collection this tool did not
create. `source_tag` is what decides it, and it is the first thing this module does.
"""

from __future__ import annotations

from dataclasses import dataclass
from typing import Mapping, Optional, Sequence, Union

SRC_PREFIX = "src::"

# THE FIELD THE IDENTITY MOVED INTO on 2026-08-28. It holds the same string the tag holds,
# `src::` prefix and all, because the move changed where an identity is kept and nothing about
# what it is.
#
# WHY BOTH ARE STILL READ. A collection carries the tag until the next sync rewrites its notes,
# so a reader that consulted only the field would stop working the moment the note types gained
# it and start working again some runs later. Reading the field first and the tag second is
# correct throughout, and the tag half becomes dead once no collection carries one.
IDENTITY_FIELD = "Identity"


@dataclass(frozen=True)
class NotOurs:
    """No `src::` tag. Hand the note to Anki's editor and do nothing else."""


@dataclass(frozen=True)
class Open:
    """Open this URI in Obsidian.

    `caveat` carries something the person should know about the card they just opened -- its
    heading was reworded, say -- and is shown alongside. It is NOT a failure: the URI works.
    """

    uri: str
    caveat: str | None


@dataclass(frozen=True)
class Explain:
    """No URI could be produced. Say why, and still open Anki's editor.

    OPENING ANKI'S EDITOR HERE IS DELIBERATE. The alternative is a keypress that does nothing,
    which is indistinguishable from a broken add-on. Whatever went wrong, the person pressed
    Edit and must end up somewhere they can edit.
    """

    message: str


# `Union[...]` RATHER THAN `A | B`, and the reason is not style. Anki ships Python 3.13, where
# either spelling works -- but this module is deliberately runnable by whatever `python3` a
# machine happens to have, so that its tests need no Anki and no particular interpreter. The
# `|` form is evaluated at import time and raises below 3.10. Annotations elsewhere use the
# modern spelling freely: `from __future__ import annotations` leaves those as strings.
Verdict = Union[NotOurs, Open, Explain]


def identity(fields: Mapping[str, str], tags: Sequence[str]) -> str | None:
    """A card's identity, from the field if it has one and from the tag if it does not.

    NOT A FALLBACK, AND NOT TRANSITIONAL. A note on a note type the sync tool does not own -
    Anki's stock Basic and Cloze among them - cannot be given a field, because that tool is
    ruled never to write to a note type it did not create. For such a note the tag is the only
    possible home, permanently. These are two kinds of note keeping the same value in the only
    place each can, so neither branch may be removed.

    THE FIELD IS READ FIRST because a note that HAS one is on a type the tool owns, and that is
    the answer that cannot be stale.

    IT IS THE SAME STRING EITHER WAY, which is what keeps this a lookup rather than a second
    code path: whatever comes back goes to `locate` unread, exactly as before.
    """
    written = fields.get(IDENTITY_FIELD, "").strip()
    if written:
        return written
    return source_tag(tags)


def source_tag(tags: Sequence[str]) -> str | None:
    """The identity tag this tool wrote, if the note carries one.

    MATCHES THE PREFIX CASE-INSENSITIVELY because Anki folds tag case: a note saved as `SRC::x`
    and one saved as `src::x` are the same tag in the collection, and a case-sensitive test here
    would call one of them foreign.

    `orphaned::` tags are deliberately NOT matched. A flagged card still carries its `src::` tag
    beside the flag, so the ordinary path already covers it -- and a card whose only tag was
    `orphaned::` is one this tool has disowned.
    """
    for tag in tags:
        if tag.lower().startswith(SRC_PREFIX):
            return tag
    return None


def command(binary: str, vault_path: str, tag: str, vault_name: str | None) -> list[str]:
    """The `locate` invocation, as a list rather than a string.

    A LIST BECAUSE A SHELL MUST NEVER SEE THIS. Vault paths contain spaces and emoji, and an
    identity tag contains `%` and `/` by construction; handed to a shell any of those is an
    invitation. Passing the arguments as a vector removes the question rather than escaping it.
    """
    argv = [binary, "locate", "--vault-path", vault_path, "--uri-only"]
    if vault_name:
        argv += ["--vault-name", vault_name]
    return argv + [tag]


def interpret(stdout: str, stderr: str) -> Verdict:
    """What `locate` said.

    THE EXIT CODE IS NOT CONSULTED, and that is on purpose. What the add-on needs to know is
    whether it has something to open, and standard output answers exactly that: `--uri-only`
    prints the URI there and nothing else, or prints nothing. Reading the code as well would be
    a second source of truth for one fact, free to disagree with the first.

    STDERR IS SHOWN EVEN WHEN A URI CAME BACK. `locate` writes an explanation there for an
    outcome that opens the note but could not place the card in it -- a reworded heading, most
    often -- and that is the moment the person can still do something about the review history
    they are about to lose.
    """
    uri = stdout.strip()
    note = stderr.strip()
    if uri:
        return Open(uri=uri, caveat=note or None)
    return Explain(message=note or "obsidian-anki-sync said nothing at all.")


def environment(base: Mapping[str, str], java_home: str | None) -> dict[str, str]:
    """The environment the `locate` process runs in.

    ANKI DOES NOT RUN WITH YOUR SHELL'S ENVIRONMENT. It is launched from the Dock or from
    Spotlight, so it inherits the session's environment and not the one your shell builds. The
    sync tool is a JVM assembly whose launcher calls `java` by name, and a `java` installed by a
    version manager -- mise, asdf, sdkman -- exists only on a PATH that a shell assembled. From
    inside Anki the launcher therefore reports "Unable to locate a Java Runtime" and stops.

    THE JVM IS CONFIGURED, NOT DISCOVERED, and that is deliberate. Guessing means encoding
    somebody's version manager's directory layout -- `~/.local/share/mise/installs/java/...` --
    which is that tool's private business and free to change under us. macOS's own
    `/usr/libexec/java_home` does not answer for a version-manager install either; it was tried
    and reported the same failure. One configured path is a contract we hold; a guessed one is a
    dependency on somebody else's internals.

    PREPENDED RATHER THAN REPLACING PATH, so that anything else the launcher reaches for still
    resolves, and so that a system JVM keeps working for anyone who has one and leaves this
    setting empty.
    """
    env = dict(base)
    if not java_home:
        return env
    env["JAVA_HOME"] = java_home
    binaries = java_home.rstrip("/") + "/bin"
    existing = env.get("PATH", "")
    env["PATH"] = binaries + ":" + existing if existing else binaries
    return env


def missing_java(stderr: str) -> bool:
    """Whether a failure looks like the JVM simply not being on the path.

    MATCHES THE LAUNCHER'S OWN WORDS, which is a weak test and is treated as one: it decides
    whether to ADD a hint, never whether to hide anything. The underlying message is always
    shown, so a false negative costs a hint and a false positive costs a redundant sentence.
    Nothing branches on this except the wording.
    """
    lowered = stderr.lower()
    return "java runtime" in lowered or "java_home" in lowered or "no java" in lowered


JAVA_HINT = (
    "Anki does not inherit your shell's PATH, so a java installed by mise, asdf or sdkman is "
    "invisible to it. Set 'java_home' in this add-on's config: run `echo $JAVA_HOME` in a "
    "terminal and paste what it prints."
)


# ---------------------------------------------------------------- drilling ----

#: Every deck this add-on builds starts with this, and NOTHING ELSE IS EVER TOUCHED. The sweep
#: that removes finished drills matches on it, so the prefix is the only thing standing between
#: a tidy-up and somebody's real deck. It is deliberately unlike a name anyone would type.
DRILL_PREFIX = "Drill — "


def drill_deck_name(title: str) -> str:
    """The temporary deck a note's cards are drilled in.

    TOP LEVEL, NOT NESTED. `A::B` in Anki means B inside A, so a nested name would leave an empty
    parent deck behind after the child is swept -- exactly the residue this is supposed to avoid.
    A `::` in the note's own title would do the same by accident, so it is flattened.

    QUOTES ARE REMOVED because a deck name reaches Anki's search syntax, where `"` terminates a
    quoted term. A deck nobody can search for is a deck nobody can empty.
    """
    cleaned = title.replace("::", "-").replace('"', "").strip()
    return DRILL_PREFIX + (cleaned or "untitled")


def is_drill_deck(name: str) -> bool:
    """Whether a deck is one of ours, and therefore ours to remove.

    Matches the prefix at the START only. A deck the person named `My Drill — French` is theirs,
    and this must never claim it.
    """
    return name.startswith(DRILL_PREFIX)


def drill_search_for_id(note_id: str) -> str:
    """The Anki search a drill deck is built from, for one note's frontmatter id.

    `-is:suspended` is stated rather than left implicit: a filtered deck will not gather
    suspended cards anyway, but writing it down means the search explains itself when it is read
    back in Anki's own filtered-deck dialog.

    AN EMPTY ID YIELDS A SEARCH THAT MATCHES NOTHING, never `tag:src::*`, which would gather the
    entire collection into a drill deck.
    """
    if not note_id.strip():
        return "nid:0"
    # BOTH HOMES, FOR THE SAME REASON `identity` READS BOTH: a collection holds the tag until
    # its notes are next written, and a drill that gathered only field-carrying notes would
    # quietly return an EMPTY deck — which reads as "nothing to drill" rather than as a fault.
    # That is the failure mode this whole project is built against, so it is worth the `or`.
    ident = note_id.strip()
    return (
        f'("Identity:src::{ident}::*" or "tag:src::{ident}::*") -is:suspended'
    )


def drill_search(tag: str) -> str:
    """The same search, for callers holding a full identity tag rather than a bare id."""
    return drill_search_for_id(tag.split("::")[1] if tag.count("::") >= 2 else "")
