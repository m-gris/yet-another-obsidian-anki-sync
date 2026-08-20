---
id: fix-body-shapes
---

# Body shapes

Every construct the design promises a card body may hold — "prose, lists,
formulae, code" — with one card each. Until 2026-08-20 four of the five reached
Anki as **nothing at all**, with the card created and looking correct, so this
file exists to make that visible in the fixture rather than only in a unit test.

## Layers of the epidermis #flashcard/2way

Named from the outside inwards:

- stratum corneum
- stratum granulosum
- stratum spinosum
- stratum basale

## Running the test suite #flashcard/2way

From the tool directory:

```
scala-cli test .
```

The suite is the only thing that proves a refactor kept its promises.

## Cranial bones and their sutures #flashcard/table

| Bone     | Anterior border | Posterior border |
| -------- | --------------- | ---------------- |
| Frontal  | Orbital rim     | Coronal suture   |
| Parietal | Coronal suture  | Lambdoid suture  |

## The three layers, blanked #flashcard/cloze

The outermost layer is the ==epidermis==, beneath it lies the ==dermis==, and
under both sits the ==hypodermis==, which is mostly fat.

## Bones of the forearm #flashcard/cloze

Two bones run between elbow and wrist. On the thumb side is the ==1|radius==;
on the little-finger side, the ==2|ulna==. In supination the ==1|radius== lies
parallel to the ==2|ulna==, and in pronation it crosses over it — which is why
both are labelled: their text may be rewritten without either card losing its
review history.
