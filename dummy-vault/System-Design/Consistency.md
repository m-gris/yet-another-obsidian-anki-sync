---
id: fix-consistency
---

# Consistency

This note carries the deepest heading nesting in the good vault: the two facet headings
under "Session guarantees" then "Monotonic reads" sit four levels down, so their heading
paths are built through two ancestor headings that are themselves unmarked.

## Definition #flashcard/3way

A consistency model is a contract between a storage system and its clients stating which
interleavings of reads and writes the system is allowed to expose. It is a restriction on
the set of histories the system may produce, not a property of any single operation.

## Why it is a spectrum #flashcard/3way

Stronger models forbid more histories, which makes application reasoning easier but forces
more coordination between replicas. Weaker models permit anomalies in exchange for lower
latency and continued availability while the network is partitioned.

## Session guarantees

### Monotonic reads

#### Definition #flashcard/3way

Once a client has observed a value, later reads within the same session never return an
older one. The guarantee is scoped to the session, so two different clients may still
disagree about which value is current.

#### Failure mode #flashcard/3way

A client whose requests are spread across replicas can read a fresh value from an
up-to-date replica and then a stale one from a lagging replica, so time appears to run
backwards. Sticky routing or a per-session version token prevents it.
