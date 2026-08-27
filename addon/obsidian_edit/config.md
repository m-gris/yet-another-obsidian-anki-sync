## Edit in Obsidian

**`binary`** — full path to the `obsidian-anki-sync` executable. A bare name is not enough:
Anki does not run with your shell's `PATH`, so `which obsidian-anki-sync` in a terminal and
paste what it prints.

**`vault_path`** — the vault directory, the one holding `.obsidian`. The same path you pass to
`--vault-path` when you sync.

**`vault_name`** — optional, and usually left empty. Obsidian addresses a vault by name in a
URI, and the name is normally the vault directory's own name. Set this only if Obsidian shows
your vault under a different name.

**`java_home`** — leave empty if `java -version` works from a plain `/usr/bin:/bin` PATH. Set it
if your JVM came from a version manager (mise, asdf, sdkman): Anki is launched from the Dock and
does not inherit your shell's PATH, so it cannot see one. Run `echo $JAVA_HOME` in a terminal and
paste what it prints. The symptom without it is "Unable to locate a Java Runtime", which is true
and misleading — the JVM is installed, just unreachable from a process Anki started.

Requires the [Advanced URI](https://github.com/Vinzent03/obsidian-advanced-uri) plugin in
Obsidian. Its default "UID field in frontmatter" setting is already `id`, which is the field
this tool derives card identity from, so there is normally nothing to configure there.
