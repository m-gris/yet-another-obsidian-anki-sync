---
id: fix-quorums
---

# Quorums

This note sits three folder levels deep (Patterns / Nested / Deep) so that deck mapping
through nested folders can be checked. The file name itself must not become a deck level.

## What does W + R > N buy you? #flashcard/1way

With N replicas, writing to W and reading from R such that W + R > N forces the read set
and the write set to intersect. The reader therefore always sees at least one replica
holding the latest acknowledged write, which is what makes the read strongly consistent.

## Why do sloppy quorums weaken consistency? #flashcard/1way

A sloppy quorum accepts writes on any W reachable nodes, not necessarily the N nodes that
own the key. The write and read sets are then drawn from different populations, so the
overlap guarantee is lost until hinted handoff has returned the data to its home replicas.
