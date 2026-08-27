package obsidiananki.plan

import obsidiananki.model.{CardKey, TagCodec}

/** A SHORT, TYPEABLE NAME FOR ONE DECISION THE TOOL IS WAITING ON.
  *
  * WHY NOT A POSITION IN THE LIST, which is the obvious thing and was Marc's first choice on
  * 2026-08-27. A run prints the notes it is waiting on and the author answers with a second
  * command, so the two runs must agree about which note is which. A NUMBER cannot guarantee
  * that: it means "the second one in the list", and the list is recomputed by the second command
  * from a vault the author may have edited in between. Edit a note, and `apply 2` acts on a
  * different note than the one whose price was read — destroying cards that were never shown.
  * That is the "approve something you did not read" failure `REVIEW-QUEUE.md` argues against,
  * arriving by a different route.
  *
  * A POSITION ALSO NEEDS A STABLE ORDER, which is a second thing to get right: pending notes
  * coming out of a map rather than a sorted sequence could renumber between two runs over an
  * IDENTICAL vault.
  *
  * SO THE HANDLE IS DERIVED FROM THE NOTE, NOT FROM THE LIST. It either finds the note whose
  * price was read, or it finds nothing and the tool refuses. **It cannot find a different one.**
  * No recorded listing, no ordering requirement, and no window between the two commands — the
  * identity does the work a saved list would otherwise have to do.
  *
  * FROM THE IDENTITY, NEVER FROM THE CONTENT, and the distinction matters here. A handle over
  * the note's TEXT would change the moment a typo was fixed, so the handle just read would stop
  * resolving — refusing safely, but for a reason that looks arbitrary. An identity changes only
  * when the card genuinely becomes a different card, which is exactly when the old handle SHOULD
  * stop working.
  *
  * HEX RATHER THAN ANY SHORTER ALPHABET, deliberately. Base32 and friends buy a character or two
  * at the cost of confusable glyphs; hex has no letter O and no letter L to mistake for a digit,
  * and these are read off a terminal and typed back by hand.
  */
opaque type DecisionHandle = String

object DecisionHandle:

  /** SIX HEX CHARACTERS — sixteen million values.
    *
    * It only has to be unique among the handful of decisions ONE RUN is waiting on, where three
    * would do. Six is chosen so that a handle is unique across a whole collection in practice,
    * which means it can be quoted in a report, a document or a message and still resolve later —
    * a property a listing-scoped code would not have.
    */
  val Length: Int = 6

  /** The handle for this card's decision. Stable for as long as the card keeps its identity.
    *
    * OVER THE ENCODED IDENTITY TAG, not over the key's fields joined by hand. That tag is
    * already this project's canonical spelling of "which card is this" — normalised, case-folded
    * and percent-encoded by [[TagCodec]] — and it is what Anki stores. Hashing it means the
    * handle agrees with the identity by construction rather than by two pieces of code
    * remembering to canonicalise the same way, which is the duplication this project fights
    * hardest.
    */
  def of(key: CardKey): DecisionHandle =
    val identity = TagCodec.encode(key).value
    java.security.MessageDigest
      .getInstance("SHA-256")
      .digest(identity.getBytes(java.nio.charset.StandardCharsets.UTF_8))
      // Two hex characters per byte, so only half as many bytes are needed as characters.
      .take((Length + 1) / 2)
      .map("%02x".format(_))
      .mkString
      .take(Length)

  /** Read a handle somebody typed, or refuse its shape.
    *
    * SHAPE ONLY. Whether a well-formed handle names anything is a different question, answered
    * by looking it up among the decisions actually pending — and answered there so that "nothing
    * is waiting under that name" can be said with the list to hand.
    */
  def parse(raw: String): Option[DecisionHandle] =
    // TRIMMED AND CASE-FOLDED BEFORE JUDGING, because these are read off a terminal and typed
    // back by hand. Refusing over letter case would be a refusal nobody can act on: the two
    // spellings are indistinguishable read aloud or written down.
    val normalised = raw.trim.toLowerCase(java.util.Locale.ROOT)
    Option.when(normalised.matches(s"[0-9a-f]{$Length}"))(normalised)

  extension (h: DecisionHandle) def value: String = h
