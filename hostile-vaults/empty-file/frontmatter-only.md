---
# REGRESSION TEST FIXTURE - not an example of good authoring. This note has valid
# frontmatter carrying an id and no body whatsoever: no H1, no headings, no prose.
# It tests that a note with an identity but nothing marked produces zero cards, and
# that this is a normal empty result rather than an error. The explanation you are
# reading sits in YAML comments on purpose. The variable under test is "the body is
# empty", so putting the explanation in the body would destroy the fixture, and
# putting it in an extra frontmatter key would change the parsed mapping, which must
# stay exactly one entry: id.
#
# This file, together with its sibling empty.md, replaces the former
# hostile-vaults/empty-basename/.md fixture. That file was removed because a leading
# dot makes it a dotfile: Obsidian skips it, and so do most directory walkers and
# **/*.md globs, so it could never reach the code under test, and a test that
# silently never runs is worse than no test. It also confounded two variables at
# once, empty basename and empty file, so a failure could not be attributed to
# either. Please do not re-add it.
id: fix-frontmatter-only
---
