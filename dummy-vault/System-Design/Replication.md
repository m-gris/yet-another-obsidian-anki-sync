---
id: fix-replication
---

# Replication

This is the good vault's only note whose card bodies carry Obsidian and Markdown block
syntax — plain, aliased and heading-anchored wikilinks, a nested bullet list and a fenced
code block — so it is the sole check that such content survives conversion intact.

## Why does synchronous replication trade availability for durability? #flashcard/1way

The leader cannot acknowledge a write until at least one follower has confirmed
it, so a slow or unreachable follower stalls every writer. You buy the guarantee
that an acknowledged write survives leader loss — see [[Durability]] and
[[Failover]] — by giving up availability during partitions, which is exactly the
tension [[CAP Theorem|CAP]] describes.

## Read-your-writes consistency #flashcard/2way

A guarantee that a client always sees its own prior writes, even when reads are
served by lagging replicas. It is strictly weaker than
[[Consistency Models#Linearizability]]: other clients may still observe the old
value. Typical implementations:

- Route reads to the leader for a short window after a write.
  - Simple, but concentrates read load on one node.
- Pin the client to a replica whose applied log position is at least the
  position returned by its last write.
  - Needs the server to hand the client a version token, e.g. `last_lsn`.

## How does a follower catch up after a network partition? #flashcard/1way

It reconnects and asks the leader for everything after the last log position it
durably applied; the leader replays from that offset if the segment still exists,
otherwise the follower must take a fresh snapshot. See [[Write-Ahead Log]] for
why the position, not the wall clock, is the resume point.

```sql
SELECT client_addr, state, sent_lsn, replay_lsn,
       sent_lsn - replay_lsn AS lag_bytes
FROM pg_stat_replication;
```

A follower whose `replay_lsn` stops advancing while `sent_lsn` moves is applying
too slowly, not disconnected — a distinction [[Monitoring]] dashboards routinely
blur.
