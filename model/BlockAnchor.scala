package obsidiananki.model

/** Obsidian's own name for one block — the `^abc123` an author writes at the end of it.
  *
  * Distinct from `ObsidianSyntax.BlockId`, which is a fact about a document: these characters
  * appeared in that position. This is a fact about identity, and validates accordingly.
  *
  * @see
  *   bead `oas-2zw` for why a block is named by the author rather than by anything derived, and
  *   for the candidates that were eliminated on the way there.
  */
opaque type BlockAnchor = String

object BlockAnchor:

  /** Obsidian's own set. The parser decides what it will RECOGNISE; this decides what may become
    * an IDENTITY, and the day they stop agreeing this is the one that must refuse.
    */
  private val Allowed = """[A-Za-z0-9-]+""".r

  def read(raw: String): Either[KeyError, BlockAnchor] =
    if raw.isEmpty then Left(KeyError.EmptyBlockAnchor(raw))
    else if !Allowed.matches(raw) then Left(KeyError.UnusableBlockAnchor(raw))
    else Right(raw.toLowerCase(java.util.Locale.ROOT))

  extension (a: BlockAnchor) def value: String = a
