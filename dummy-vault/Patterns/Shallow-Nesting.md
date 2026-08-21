---
id: fix-shallow-nesting
---

# Shallow nesting

A note whose sub-items are indented too little for this tool's markdown parser to read
them as sub-items. It exists so the refusal is exercised END TO END — from a real file on
disk, through the scan, to a build failure with the file's own line numbers in it — rather
than only in a unit test that hands the scanner a string.

DO NOT "FIX" THE INDENTATION BELOW. It is the fixture. Re-indenting it to four spaces
would make the check stop firing, this file's expected failure would vanish, and the test
that counts the vault's failures would go green while proving nothing.

Note that nothing here is exotic: this is what Prettier, most web clippers, and a hand
edit in an editor configured for two-space indentation all produce, and it is what
CommonMark and Obsidian both read as nesting. Only the parser disagrees.

## Why is a sloppy quorum weaker than a strict one? #flashcard/1way

A strict quorum always draws its read set and its write set from the same N replicas, so
the two sets are forced to overlap. Sloppy quorums give that up in two ways:

- The write goes to any W reachable nodes.
  - So the write set may share no member at all with the read set.
- The data comes home later, by hinted handoff.
  - So the overlap is restored only once handoff has finished.
