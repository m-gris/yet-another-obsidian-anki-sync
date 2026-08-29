package obsidiananki.model

/** OBSIDIAN'S OWN NAME FOR ONE BLOCK — the `^abc123` an author writes at the end of it.
  *
  * WHY THIS TYPE EXISTS AT ALL, WHICH IS A LONGER STORY THAN ITS SIZE SUGGESTS. A card scoped to
  * a block needs an identity, and a block has nothing intrinsic to offer: its position moves when
  * something is inserted above it, and its text changes for the very edits a card is about. The
  * cloze document spent three days eliminating candidates — a content hash, a similarity
  * fingerprint, a key projected onto the labels inside the block — and every one of them failed
  * because a name that must be DERIVED from something unstable is unstable.
  *
  * THE ANSWER IS A SURROGATE, WHICH IS THE TEXTBOOK ONE. When no natural key is stable, you
  * assign an arbitrary one and store it. What made that look impossible here is that a surrogate
  * has to be written down, and this tool has never written to the vault. Marc's answer, 2026-08-29:
  * it does not have to. The AUTHOR writes it — Obsidian generates one with a keystroke — and a
  * cloze block without one is REFUSED, exactly as a heading without a marker produces nothing.
  * Declaring the anchor is the same contract as declaring the card.
  *
  * IT SURVIVES EVERYTHING THE OTHER CANDIDATES DID NOT: editing the clozed text, rewording the
  * prose around it, reflowing the paragraph, inserting blocks above it. That is what buys a
  * cloze card a review history that outlives a typo fix.
  *
  * SEPARATE FROM `ObsidianSyntax.BlockId`, WHICH IS THE PARSED NODE. That one is a fact about a
  * document — these characters appeared in that position. This one is a fact about identity, and
  * it validates accordingly. The same split as `HeadingSegment` against Laika's `Header`.
  */
opaque type BlockAnchor = String

object BlockAnchor:

  /** Obsidian's own character set: letters, digits and hyphens.
    *
    * PINNED HERE AS WELL AS IN THE PARSER, and that is not duplication for its own sake. The
    * parser decides what it will RECOGNISE in a document; this decides what may become part of
    * an identity written into Anki. They agree today, and the day they stop agreeing this is the
    * one that must refuse — an identity is forever, and a parse is only until the next run.
    */
  private val Allowed = """[A-Za-z0-9-]+""".r

  /** Read an anchor the parser found, or say why it cannot be one.
    *
    * FOLDED TO LOWER CASE, because it ends up inside an Anki tag and Anki folds tag case — so
    * `^ABC` and `^abc` are one identity in the collection whatever the vault says. Producing
    * both spellings would produce two identities Anki cannot tell apart, which is how a card
    * comes to be created twice.
    */
  def fromParsed(raw: String): Either[KeyError, BlockAnchor] =
    val trimmed = raw.trim
    if trimmed.isEmpty then Left(KeyError.EmptyBlockAnchor(raw))
    else if !Allowed.matches(trimmed) then Left(KeyError.UnusableBlockAnchor(raw))
    else Right(trimmed.toLowerCase(java.util.Locale.ROOT))

  /** For a value recovered from an identity already written into Anki.
    *
    * IT VALIDATES RATHER THAN TRUSTING, for the reason [[HeadingSegment.fromDecoded]] gives about
    * its own inputs: a tag read back may have been edited by hand in Anki, and a value that
    * cannot round-trip must fail here rather than key a card to something unreachable.
    */
  def fromDecoded(raw: String): Either[KeyError, BlockAnchor] = fromParsed(raw)

  extension (a: BlockAnchor) def value: String = a
