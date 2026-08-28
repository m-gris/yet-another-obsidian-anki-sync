# =============================================================================
# BUILDING AND INSTALLING THE SYNC TOOL
# =============================================================================
# This repository IS the tool. README.md says what it does and how to write a vault it can
# read; these recipes only build and install it.
#
# THE RECIPE NAMES LOST THEIR `-sync-tool` SUFFIX when the tool moved out of the larger
# repository it grew up in. There, `build` would have been ambiguous among several projects and
# `build-sync-tool` said which one; here there is only one thing to build, and the suffix made
# every invocation longer for nothing.
#
# WHY AN ASSEMBLY AND NOT A GRAALVM NATIVE IMAGE. Measured 2026-08-22: the assembly starts in
# 0.57s against 1.01s for `scala-cli run`, and a native image would reach roughly a tenth of
# that. It was not worth buying: building one needs a GraalVM download, and the program parses
# YAML through snakeyaml — which resolves classes by reflection, the one thing native-image
# cannot see without being told. The failure mode is a binary that builds and then breaks on a
# path only a live run reaches. Half a second does not pay for that.
# =============================================================================

tool_bin := justfile_directory() / "target" / "obsidian-anki-sync"

# Build the tool into a single executable (needs a JVM on PATH to run)
build:
    @mkdir -p "{{justfile_directory()}}/target"
    scala-cli --power package "{{justfile_directory()}}" --assembly --preamble \
        -o "{{tool_bin}}" --force
    @echo "built: {{tool_bin}}"

# Refuse any default parameter value. See rules/no-default-parameters.yml for why, and for
# how to keep one deliberately: suppress at the site with a comment saying why, which is what
# gives the decision an author. There is no blanket exception, tests included.
lint:
    ast-grep scan --rule "{{justfile_directory()}}/rules/no-default-parameters.yml" \
        "{{justfile_directory()}}"
    @echo "lint: no default parameters"

# Run the test suite. DEPENDS ON `lint` ON PURPOSE: a green test run must not be reachable
# while a default parameter is sitting unjustified, or the gate is advice rather than a gate.
test: lint
    scala-cli test "{{justfile_directory()}}"

# Symlink the built tool into ~/.local/bin, which is on PATH
install: build
    @mkdir -p "$HOME/.local/bin"
    ln -sf "{{tool_bin}}" "$HOME/.local/bin/obsidian-anki-sync"
    @echo "linked: $HOME/.local/bin/obsidian-anki-sync -> {{tool_bin}}"
    @echo "the link points AT the build output, so a rebuild updates it in place"

# Remove the symlink; leaves the built executable alone
uninstall:
    rm -f "$HOME/.local/bin/obsidian-anki-sync"

# Report what the fixture vault yields — a quick end-to-end check that a build works
demo:
    scala-cli run "{{justfile_directory()}}" -- \
        inspect --vault-path "{{justfile_directory()}}/dummy-vault"
