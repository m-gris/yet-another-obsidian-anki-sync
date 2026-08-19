---
id: fix-messaging
---

# Messaging Patterns

Three ways one service can hand work to another, each trading a different
property of the call for a different property of the system.

The `/` in the heading "Cost / benefit" below is DELIBERATE, not a typo. `/` is the
character that joins heading-path segments into a card's identity key, so a heading
containing one probes whether that join is escaped. Do not tidy it away: rewriting it
as "Cost and benefit" silently deletes the test.

## Cost / benefit #flashcard/table

| Pattern | Benefit | Cost |
| --- | --- | --- |
| Queue | Absorbs load spikes: the producer keeps accepting work while a slow consumer drains the backlog at its own rate. | Latency becomes unbounded under sustained overload, and the queue itself is state that must be sized, monitored and drained. |
| Pub/Sub | The producer names an event, not a recipient, so new consumers are added without touching or redeploying the publisher. | Nobody owns the end-to-end path: a subscriber that silently stops consuming looks identical to one that was never there. |
| Request-Response | The caller learns the outcome synchronously, so errors surface at the call site and can be retried with the original context in hand. | Availability multiplies down the chain — the caller is only as available as the slowest dependency it blocks on. |
