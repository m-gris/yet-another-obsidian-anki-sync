# =============================================================================
# THE OBSIDIAN → ANKI SYNC TOOL
# =============================================================================
# `obsidian-anki-custom-sync/` is a Scala 3 command-line program that turns marked headings in
# an Obsidian vault into Anki notes. Its own README explains what it does; these recipes only
# build and install it.
#
# WHY AN ASSEMBLY AND NOT A GRAALVM NATIVE IMAGE. Measured 2026-08-22 on this machine: the
# assembly starts in 0.57s against 1.01s for `scala-cli run`, and a native image would reach
# roughly a tenth of that. It was not worth buying: building one needs a GraalVM download, and
# the program parses YAML through snakeyaml — which resolves classes by reflection, the one
# thing native-image cannot see without being told. The failure mode is a binary that builds and
# then breaks on a path only a live run reaches. Half a second does not pay for that.
# =============================================================================

sync_tool_dir := justfile_directory() / "obsidian-anki-custom-sync"
sync_tool_bin := sync_tool_dir / "target" / "obsidian-anki-sync"

# Build the sync tool into a single executable (needs a JVM on PATH to run)
build-sync-tool:
    @mkdir -p "{{sync_tool_dir}}/target"
    scala-cli --power package "{{sync_tool_dir}}" --assembly --preamble \
        -o "{{sync_tool_bin}}" --force
    @echo "built: {{sync_tool_bin}}"

# Run the sync tool's test suite
test-sync-tool:
    scala-cli test "{{sync_tool_dir}}"

# Symlink the built tool into ~/.local/bin, which is on PATH
install-sync-tool: build-sync-tool
    @mkdir -p "$HOME/.local/bin"
    ln -sf "{{sync_tool_bin}}" "$HOME/.local/bin/obsidian-anki-sync"
    @echo "linked: $HOME/.local/bin/obsidian-anki-sync -> {{sync_tool_bin}}"
    @echo "the link points AT the build output, so a rebuild updates it in place"

# Remove the symlink; leaves the built executable alone
uninstall-sync-tool:
    rm -f "$HOME/.local/bin/obsidian-anki-sync"
