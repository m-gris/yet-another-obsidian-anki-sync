---
id: fix-empty-value
author:
source: https://example.invalid/clipped-article
---

# Circuit breaker

REGRESSION TEST FIXTURE - not an example of good authoring. The `author:` key has an empty value, which is what web clippers leave behind when a page has no byline. Parsing this frontmatter as HOCON instead of YAML fails outright on it. Correct behaviour: a YAML parser accepts the empty value and the run is not aborted.

## Circuit breaker #flashcard/cloze

An open circuit breaker fails requests ==immediately== instead of waiting for a timeout, which protects the caller's ==thread pool== rather than the failing dependency.
