# Head-of-line blocking

REGRESSION TEST FIXTURE - not an example of good authoring. This file has no YAML frontmatter at all, yet it carries marked headings, so its cards have no stable identity. Correct behaviour: the file is reported as an error naming the missing `id:`, never silently skipped.

## Why does one slow request stall an entire connection? #flashcard/1way

On a single ordered stream, responses must leave in the order the requests arrived. A slow response therefore holds the line for everything queued behind it, even for requests whose work already finished.

## Temporal coupling #flashcard/2way

Two components are temporally coupled when one must be available at the exact moment the other runs. Introducing a queue between them turns that hard timing dependency into a soft one, at the cost of eventual rather than immediate consistency.
