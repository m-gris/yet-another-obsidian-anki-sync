---
id: fix-multi-topic
---

# Multi-Topic

This note deliberately holds two unrelated topics, and each of them has a facet heading
with exactly the same text, "Definition", and again "Failure mode". The repetition is not
a copy-paste mistake: it is the case that distinguishes a card key built from heading TEXT
from a card key built from the heading PATH. Under a text key, "Definition" appears twice
and the second card silently overwrites the first, so two of the four cards below vanish
without any error. Under a path key the four cards are "CAP Theorem/Definition",
"CAP Theorem/Failure mode", "Quorum/Definition" and "Quorum/Failure mode", all distinct,
and all four survive. Each marked heading also takes its Concept from the nearest ancestor
heading rather than from the note title, so the same file feeds two different concepts.

## CAP Theorem

### Definition #flashcard/3way

When a network partition splits a distributed system, the system must choose between
answering with possibly stale data and refusing to answer at all. Consistency and
availability cannot both be preserved while the partition lasts.

### Failure mode #flashcard/3way

Treating the C-versus-A choice as a permanent architectural setting rather than a
per-partition decision. Outside a partition a system can offer both, so the tradeoff only
becomes real once messages start being dropped.

## Quorum

### Definition #flashcard/3way

A subset of replicas large enough that any two such subsets share at least one member.
Requiring every read and every write to reach a quorum makes the newest accepted value
visible to every subsequent reader.

### Failure mode #flashcard/3way

Sizing the quorum against the number of live nodes instead of the configured replica count.
As nodes fail the quorum shrinks with them, two disjoint groups can each believe they hold
a majority, and split-brain follows.
