---
id: fix-single-item
aliases:
  - Some Alias
---

# Read repair

REGRESSION TEST FIXTURE - not an example of good authoring. The `aliases` key holds a one-item YAML block sequence. Parsing this frontmatter as HOCON instead of YAML silently reads it as the string `- Some Alias` rather than a list. Correct behaviour: a YAML parser produces a real one-element list and the run succeeds.

## Read repair #flashcard/2way

When a quorum read observes replicas that disagree, the coordinator writes the winning value back to the stale replicas as a side effect of serving the read. Convergence is therefore driven by read traffic, so cold keys stay divergent until something touches them.
