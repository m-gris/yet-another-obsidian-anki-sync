---
id: 2026-08-18
---

# Write-ahead logging

REGRESSION TEST FIXTURE - not an example of good authoring. The id is an unquoted date-like scalar. Parsing this frontmatter as HOCON instead of YAML silently yields `202608-18`, quietly losing a hyphen from the primary key. Correct behaviour: a YAML parser reads the value verbatim as `2026-08-18` and the run succeeds.

## Why write the log before the page? #flashcard/1way

The log record is small and appended sequentially, so it can be forced to durable storage cheaply. Once it is durable, the in-place page write can be replayed after a crash, which is what makes the commit point earlier than the data write.
