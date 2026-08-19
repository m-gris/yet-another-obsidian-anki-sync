---
id: fix-no-h1-no-headings
---

REGRESSION TEST FIXTURE - not an example of good authoring. This note has an id and
ordinary body prose, but it contains no H1 and no headings of any level. Under the
rule "concept = H1 or filename" the concept must therefore fall back to the filename,
`no-h1-no-headings`, and because nothing is marked the note must still produce zero
cards without erroring. It replaces part of the former
hostile-vaults/empty-basename/.md fixture, which was removed because its leading dot
made it a dotfile that Obsidian and most `**/*.md` globs skip, so it never reached the
code under test. Please do not re-add it.

A partition-tolerant system must keep serving requests while some replicas are
unreachable, which is why CAP forces a choice between refusing writes and accepting
divergent ones. Choosing availability means the system will later have to reconcile
conflicting versions of the same key, so the conflict-resolution rule is part of the
design rather than an afterthought.
