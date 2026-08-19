---
id: dup-idempotence
---

# Idempotence

REGRESSION TEST FIXTURE - not an example of good authoring. This note and `note-b.md` declare the same frontmatter id and the same heading text, so every card key `(id, heading path)` produced here is also produced there. Correct behaviour: extraction aborts loudly, naming both file paths, before any planning happens.

## Definition #flashcard/3way

An operation is idempotent when applying it more than once has the same effect as applying it once. HTTP `PUT` and `DELETE` are specified this way so that a client which never learns the outcome of a request can safely retry it.

## Failure mode #flashcard/3way

Idempotence is a property of the whole operation, not of the verb. A `PUT` whose handler appends to an audit row is no longer idempotent, and a retry storm will multiply those rows even though the resource itself converges.
