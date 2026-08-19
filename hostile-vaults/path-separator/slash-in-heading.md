---
id: hostile-slash
---

# Slash in headings

REGRESSION TEST FIXTURE - not an example of good authoring. This file holds two structurally different shapes that collapse onto one string under a naive heading-path join: a single heading whose own text contains `/`, and a two-level nesting whose ancestor chain is joined with `/`. Both yield the path `Slash in headings/Backpressure/Load shedding`, so the join is not injective and whichever card is written second silently overwrites the first. Correct behaviour: the encoding keeps the two distinct - by escaping `/` inside a segment, or by keying on the list of segments rather than on a joined string - and an unescaped join is reported as a collision rather than accepted.

## Backpressure/Load shedding #flashcard/1way

Backpressure slows the producer down until it matches the slowest consumer; load shedding keeps the producer at full rate and drops the excess work instead. A component with no way to push backpressure onto its own callers is left with shedding as its only option.

## Backpressure

Unmarked heading - generates no card. It exists only to be the parent of the nested heading below, whose joined path collides with the single heading above.

### Load shedding #flashcard/1way

Shedding is what a component does once backpressure has nowhere left to propagate: it rejects work early and cheaply so that the requests it does accept still meet their deadline. Rejecting at admission costs far less than timing out after the work is half done.
