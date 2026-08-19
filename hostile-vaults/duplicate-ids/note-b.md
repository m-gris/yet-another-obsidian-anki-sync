---
id: dup-idempotence
---

# Idempotence

REGRESSION TEST FIXTURE - not an example of good authoring. This note was copied from `note-a.md` and re-pointed at message consumers, but the author never changed the frontmatter id. Correct behaviour: extraction rejects the vault loudly, naming both file paths, rather than letting one note's cards silently overwrite the other's.

## Definition #flashcard/3way

For a message consumer, idempotence means that redelivering the same message leaves the consumer's state unchanged. This is what makes at-least-once delivery usable: the broker guarantees duplicates, and the consumer absorbs them.

## Failure mode #flashcard/3way

Deduplicating on a message id only works while the dedup store outlives the redelivery window. If the store forgets entries after an hour and the broker retries after a day, the duplicate looks new again and the effect is applied twice.
