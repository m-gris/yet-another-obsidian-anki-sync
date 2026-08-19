---
id: fix-coupling
---

# Coupling

Notes on the ways two components can become dependent on each other, and on the
vocabulary used to name those dependencies.

## Temporal coupling #flashcard/2way

Two components are temporally coupled when one can only do its work while the other
is available at that same moment. A synchronous HTTP call couples caller to callee in
time; putting a queue between them removes that coupling at the cost of latency and
ordering guarantees.

## Afferent coupling #flashcard/2way

The number of other modules that depend on a given module. High afferent coupling makes
a module expensive to change, because every change has to stay compatible with many
callers — it is a measure of responsibility, not of quality.

## Why does high efferent coupling make a module fragile? #flashcard/1way

Efferent coupling counts the modules a given module depends on. Each dependency is a way
for someone else's change to break it, so a module that reaches out to many others fails
for reasons that have nothing to do with its own code.

## Why is connascence of meaning harder to maintain than connascence of name? #flashcard/1way

Connascence of name is visible: rename the thing and a compiler or a grep finds every
site. Connascence of meaning lives in a shared convention — a magic value, an agreed
encoding — that nothing in the code records, so the matching sites cannot be found
mechanically.

## Connascence

Connascence names the cases where two pieces of code must change together, ranked by how
hard the coupling is to spot: name, type, position, meaning, timing, execution order. This
heading carries no marker, so it must produce no card at all.
