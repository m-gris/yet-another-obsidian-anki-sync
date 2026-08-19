---
id: fix-table-edges
---

# Table Edge Cases

Degenerate tables that a table-marked heading may legally contain. Each section
isolates exactly one of them.

## Concept column only, no descriptor columns #flashcard/table

| Consistency model |
| --- |
| Linearizability |
| Sequential consistency |
| Eventual consistency |

## Duplicate row concepts #flashcard/table

| Mechanism | Purpose | Failure mode |
| --- | --- | --- |
| Retry | Recovers from transient faults such as a dropped packet or a brief leader election. | Amplifies load exactly when the dependency is already struggling. |
| Retry | Turns an at-most-once delivery into an at-least-once one, which is the cheap half of exactly-once. | Duplicates reach the receiver, so the handler must be idempotent or it corrupts state. |
| Timeout | Bounds how long a caller holds a connection and a thread waiting on a peer. | Cannot distinguish a slow peer from a dead one, so it will cancel work that was about to succeed. |

## Exactly one descriptor column #flashcard/table

| Partitioning strategy | Definition |
| --- | --- |
| Range partitioning | Keys are split into contiguous intervals, so a scan over neighbouring keys touches few partitions. |
| Hash partitioning | A hash of the key selects the partition, spreading load evenly but destroying key locality. |
| Consistent hashing | Keys and nodes map onto one ring, so adding a node moves only the keys between it and its predecessor. |
