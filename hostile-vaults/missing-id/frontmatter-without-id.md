---
tags:
  - distributed-systems
created: 2026-08-18
---

# Backpressure

REGRESSION TEST FIXTURE - not an example of good authoring. The frontmatter parses cleanly but omits the `id:` key, so the marked headings below cannot be given a stable card key. Correct behaviour: the file is reported as an error naming the missing `id:`, never silently skipped.

## What does backpressure actually propagate? #flashcard/1way

It propagates the consumer's rate limit upstream, so that a producer slows down instead of a buffer growing without bound. Dropping the signal does not remove the problem, it relocates it to whichever queue is willing to keep accepting work.

## Bounded queues #flashcard/cloze

A bounded queue converts an ==unbounded memory leak== into an ==explicit rejection==, which is the failure the operator can actually see and act on.
