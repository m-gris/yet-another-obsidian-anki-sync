---
id: fix-linearizability
---

# Linearizability

The "Cost" heading below carries the good vault's only all-directions variant of the
three-way marker, so it is the sole check that the variant is recognised as distinct
from the plain three-way marker its sibling headings use.

## Definition #flashcard/3way

Every operation appears to take effect atomically at a single instant between its
invocation and its response, and that instant respects real time. Once a write is
acknowledged, every later read — from any client — must observe it or a newer value.

## Cost #flashcard/3way/all

A linearizable read or write has to be confirmed by a quorum before it can respond, so its
latency is bounded below by the slowest link in that quorum. During a network partition the
minority side must refuse requests rather than answer from state it cannot vouch for.

## Contrast with sequential consistency #flashcard/3way

Sequential consistency only requires that all replicas agree on *some* total order that
preserves each client's program order. Linearizability adds the real-time constraint: an
operation that completed before another started must be ordered before it.

## Notes

Herlihy and Wing introduced the term in 1990, in the context of concurrent objects rather
than distributed databases. This heading carries no marker, so it must produce no cards.
