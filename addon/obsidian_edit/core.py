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
