package obsidiananki.cli

import io.circe.Json
import io.circe.syntax.*
import obsidiananki.plan.{DecisionHandle, PendingRetype}

/** WHAT A RUN DECIDED, FOR A PROGRAM RATHER THAN A PERSON.
  *
  * IT EXISTS BECAUSE THE ARCHITECTURE RULING SAID SO AND THE FIRST BUILD IGNORED IT.
  * `docs/REVIEW-QUEUE.md` states that enumerating pending decisions must return "structured
  * data, not prose", on the grounds that the terminal is one consumer among several and the
  * Obsidian plugin is the one that matters. What was built on 2026-08-27 emitted prose only, so
  * the only way to act on a waiting change from anywhere but a terminal was to scrape the
  * report — the coupling that ruling exists to prevent.
  *
  * MARC ALREADY DRIVES THIS TOOL FROM OBSIDIAN, through the Shell commands plugin, and already
  * pipes one of those commands through `jq`. So this is not groundwork for a hypothetical
  * consumer; it is the missing half of one that exists.
  *
  * HAND-WRITTEN ENCODERS RATHER THAN DERIVED ONES. Automatic derivation would make this file's
  * output a shadow of the internal types, so renaming a field would silently rename a key that
  * somebody's script reads. Writing the keys out makes the wire format a decision rather than a
  * consequence — and this file is the whole of that decision, in one place.
  *
  * KEYS ARE FOR THE READER OF A SCRIPT, not for this codebase's vocabulary. `cardsDestroyed`
  * rather than `price.cards`; `name` rather than `handle`. Somebody writing a `jq` filter has
  * not read `plan/Retyping.scala`.
  */
object AsJson:

  /** One change waiting on an answer.
    *
    * IT INCLUDES THE FLAG THAT ANSWERS IT, spelled out. A caller could assemble `--approve` plus
    * the name itself, but every caller doing so is a copy of a convention that lives here — and
    * the day the flag is spelled differently, every one of those copies is wrong and silent.
    */
  def waiting(p: PendingRetype): Json =
    Json.obj(
      "name"           := p.handle.value,
      "path"           := p.retype.key.path.render,
      "note"           := p.retype.key.noteId.value,
      "fromNoteType"   := p.loss.from,
      "toNoteType"     := p.loss.to,
      "cardsNow"       := p.loss.fromCount,
      "cardsAfter"     := p.loss.toCount,
      "cardsDestroyed" := p.price.cards,
      "reviewsLost"    := p.price.reviews,
      "approveWith"    := s"--approve ${p.handle.value}",
    )

  /** THE WHOLE ANSWER, INCLUDING THE EMPTY CASE.
    *
    * ALWAYS EMITS EVERY KEY, even when the list behind it is empty. A consumer that has to
    * distinguish "no changes are waiting" from "this version does not report waiting changes"
    * cannot do it if the key is absent in both, and the failure would be silent: a script would
    * read no waiting changes and carry on.
    */
  def syncResult(
      waitingOn: Vector[PendingRetype],
      approved: Vector[PendingRetype],
      unrecognised: Vector[DecisionHandle],
  ): Json =
    Json.obj(
      "waiting"      := waitingOn.map(waiting),
      "approved"     := approved.map(waiting),
      "unrecognised" := unrecognised.map(_.value),
    )
