# The Anki add-on, and the three bindings

Setting up the keystrokes that connect Anki and Obsidian in both directions. The main
[`README.md`](../README.md) says what each one *does*; this says how to make it work.

There are three, and only two need this add-on:

| Direction | What it does | Needs |
|---|---|---|
| Anki → Obsidian | `e` on a card opens its source note, at the card's line | **this add-on**, Advanced URI |
| Obsidian → Anki | opens Browse on the cards a note produced | Shell commands only |
| Obsidian → Anki | drills a note's cards now, ignoring due dates | **this add-on**, Shell commands, `jq` |

---

## What the add-on actually does

**It decodes nothing.** It reads the card's identity — from its `Identity` field, or from a
`src::` tag if the note is on a note type this tool does not own — and asks the tool:

```bash
obsidian-anki-sync locate --vault-path ~/my-vault 'src::abc123::cap%20theorem/definition'
```

which prints an `obsidian://` URI, or says why it cannot. `--uri-only` puts the URI on standard
output and any explanation on standard error, which is how the add-on reads it. It needs no
`--profile`, because it reads no collection.

**That split is the whole design.** Decoding the identity inside the add-on would mean a second
implementation of card identity, in a second language, kept honest only by a test — the defect
class this project fights hardest. The tool that wrote the identity is the thing that reads it.

`locate` is useful on its own: run it over every identity in your collection and you have an audit
of which cards still point at live notes.

---

## Setting it up

**1. The Obsidian side.** Install and enable
[Advanced URI](https://github.com/Vinzent03/obsidian-advanced-uri). Its default *UID field in
frontmatter* setting is already `id`, which is the field this tool derives identity from, so there
is normally nothing to configure.

**2. The Anki side.** Put `addon/obsidian_edit/` where Anki looks for add-ons — on macOS,
`~/Library/Application Support/Anki2/addons21/`. A symlink works and keeps it updated in place.

**3. Configure it** in Anki under *Tools → Add-ons → Edit in Obsidian → Config*:

| Setting | What it is |
|---|---|
| `binary` | full path to `obsidian-anki-sync`. A bare name will not do — see below |
| `vault_path` | the vault directory, the one holding `.obsidian` |
| `vault_name` | usually empty. Only if Obsidian shows your vault under a name other than its directory's |
| `java_home` | usually empty. See below |

**4. Restart Anki.** The add-on does not load until you do.

### ⚠️ Anki does not inherit your shell's `PATH`

It is launched from the Dock or a launcher, not from a terminal, so it sees the session's
environment rather than the one your shell builds. Two consequences, and both have already caught
someone:

- **`binary` must be an absolute path.** `which obsidian-anki-sync` and paste what it prints.
- **`java_home` must be set if your JVM came from a version manager** — mise, asdf, sdkman. Those
  put `java` on a `PATH` that only a shell assembles, so Anki cannot see it. The symptom is
  *"Unable to locate a Java Runtime"*, which is true and thoroughly misleading: the JVM is
  installed and merely unreachable from a process Anki started. macOS's own
  `/usr/libexec/java_home` does not find a version-manager install either. Run `echo $JAVA_HOME`
  and paste that. Leave it empty if `java` works from a bare `/usr/bin:/bin` path.

The add-on adds that instruction to the error when it recognises the failure, but it is written
here too, because the error you get is from a launcher that does not know why it cannot see a JVM.

---

## The Obsidian side: two shell commands

Both use [Shell commands](https://github.com/Taitava/obsidian-shellcommands). Create a shell
command with the body below, give it an alias, and bind a key to it.

### Browse the cards this note produced

```bash
open -a Anki ; obsidian-anki-sync browse --profile "Your Profile" --note-id {{yaml_value:id}}
```

`open -a Anki` is macOS. Elsewhere, whatever raises a window: `wmctrl -a Anki`, or nothing at all
if your window manager already follows focus.

**`open -a Anki` runs FIRST**, and that is the one ordering detail that matters. Anki opens the
Browse window and raises it *within* Anki, but macOS will not let Anki pull itself in front of
Obsidian, so the app has to be raised separately. Do that last and it raises Anki's *main* window
over the Browse window that just opened — which looks exactly like Browse never opening.

**Ask the tool where the cards are; do not work it out yourself.** A card's identity is this
tool's to spell, and it changed twice in two days — it moved from a tag into a field, and the tag
is now only for notes on note types this tool does not own. The command hands over a note's
frontmatter id and nothing else; every volatile part stays inside the binary.

> **This used to be a `curl` one-liner that built the Anki search itself**, and the main README
> called that its whole trick: composable without asking the tool anything, so the binding cost
> nothing. What it actually bought was a copy of the identity format in a config file this
> repository cannot read, test, or migrate — so moving the identity would have turned the
> keystroke into an **empty Browse window**, which reads as *this note made no cards* rather than
> as a fault. It was found by someone asking, not by anything failing. Changed 2026-08-29.
>
> Two hazards went with it. The id no longer passes through a JSON body, so the plugin's escaping
> — which turns every hyphen of a UUID into `\-` and made AnkiConnect reject the whole request
> silently — can no longer break anything. And the search is no longer written by hand, so it
> cannot fall behind the identity it is searching for.

### Drill this note's cards now

```bash
open -a Anki ; ID={{yaml_value:id}} ; T={{title}} ; R=$(curl -sS localhost:8765 -X POST -d "$(jq -nc --arg id "$ID" --arg t "$T" '{action:"studyFromNote",version:6,params:{noteId:$id,title:$t}}')") ; case "$R" in *'"error": null'*|*'"error":null'*) ;; *) echo "$R" >&2 ;; esac
```

**Why `jq` and why the `case`.** The payload carries the note's *title*, which can contain quotes;
`jq --arg` escapes its arguments correctly by construction, where a hand-stitched JSON string does
not. And AnkiConnect answers a failed action with **HTTP 200 and an `error` field**, so `curl`
exits 0 and a discarded body hides the reason entirely — the `case` puts anything that is not a
success on stderr, where the plugin shows it. Both of those cost a debugging round before they
were written down.

**This one needs the add-on**, which registers a `studyFromNote` action on AnkiConnect. That means
reaching into another add-on: AnkiConnect dispatches by inspecting its own methods, and this
attaches one. A knowing choice, and a different risk from the silent traps above — if AnkiConnect
ever changes, the action is simply not found and the reply says so.

The add-on also adds *Cards → "Study these cards now (temporary deck)"* to Anki's Browse window,
which drills whatever search you are looking at. Deliberately not restricted to this tool's cards.

---

## Picking keys

The keys are suggestions; bind whatever fits your layout.

`Cmd+Alt+D` looks free and is not: macOS uses it to toggle Dock hiding, and that shortcut is built
into the Dock rather than registered in the database every other system shortcut lives in — so it
is invisible to any check you can run. Obsidian's own hotkey settings will warn about a clash with
Obsidian, and about nothing else. Adding `Ctrl` steps out of the way.

---

## Running the add-on's tests

They import nothing from Anki, so they run without it:

```bash
just addon-test
```

`just test` depends on that, so the add-on's tests run with everything else.
