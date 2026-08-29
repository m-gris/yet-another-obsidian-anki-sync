package obsidiananki.model

/** WHAT BECOMES OF ONE TAG WRITTEN IN A NOTE'S FRONTMATTER.
  *
  * REQUESTED BY MARC 2026-08-22, so that an Obsidian tag can drive an Anki filtered deck — study
  * everything tagged `backend/scala`, without maintaining a second list of what that means.
  *
  * A SUM TYPE RATHER THAN AN `Option`, because three things can happen and only one of them is
  * "carried". An `Option` would collapse *this is an instruction to the tool* and *Anki cannot
  * hold this* into one silent `None`, and the second of those is something the author needs
  * telling about — it is the shape of failure this project designs against.
  */
enum VaultTag:

  /** Carried into Anki under this tool's namespace. */
  case Carried(tag: OwnedTag)

  /** A `#flashcard/…` marker: an instruction addressed to this tool, not a description of the
    * note.
    *
    * EXCLUDED BECAUSE IT IS ALREADY SPENT. By the time anything is written to Anki the marker has
    * been read and obeyed — it decided which cards exist and what shape they are. Carrying it
    * across would file an instruction in the author's tag tree as though it were a subject, so
    * `obsidian::flashcard::sequence::headers::recursive` would sit beside `obsidian::backend`
    * looking like something you might want to study.
    */
  case Marker

  /** Anki cannot hold this tag, so it is not carried and the author is told which one.
    *
    * NOT SILENTLY MANGLED, AND NOT PERCENT-ENCODED. Both were available and both are worse.
    * Mangling invents a spelling the author never chose; encoding produces `obsidian::my%20tag`,
    * which is a tag nobody can type into the filtered-deck search this feature exists to enable.
    * Refusing one tag by name costs the author one edit and costs the collection nothing.
    */
  case Unusable(raw: String, why: String)

object VaultTag:

  /** The namespace this tool owns for an author's own tags. */
  val Prefix: String = "obsidian"

  /** Read one frontmatter tag.
    *
    * OBSIDIAN NESTS WITH `/` AND ANKI WITH `::`, so `backend/scala` becomes
    * `obsidian::backend::scala` and the author's tag tree appears in Anki's sidebar with its
    * shape intact. That translation is the whole of the mapping; nothing else is rewritten.
    *
    * LOWERCASED, BECAUSE ANKI FOLDS TAG CASE. `Backend` and `backend` are one tag in a
    * collection, so producing both would be producing a tag Anki cannot distinguish from the
    * other — the same reasoning [[OwnedTag.isOwned]] already records for matching.
    *
    * A LEADING `#` IS TOLERATED. Obsidian writes tags bare in frontmatter and with a `#` in the
    * body, and an author moving one into frontmatter by hand brings the `#` often enough that
    * refusing it would be pedantry rather than a guard.
    */
  def read(raw: String): VaultTag =
    val trimmed = raw.trim.stripPrefix("#").trim

    if trimmed.isEmpty then Unusable(raw, "it is empty")

    // ANKI SEPARATES TAGS WITH WHITESPACE, so a tag containing any would silently become two.
    // Such a value is not a valid Obsidian tag either, so nothing is being lost that Obsidian
    // itself would have honoured.
    else if trimmed.exists(_.isWhitespace) then
      Unusable(raw, "an Anki tag cannot contain a space — it would become two tags")

    // `::` IS ANKI'S OWN SEPARATOR. A vault tag containing one would arrive as nesting the
    // author did not write, and this tool would then own a branch it could not map back.
    else if trimmed.contains("::") then
      Unusable(raw, "'::' is Anki's nesting separator — write '/' to nest an Obsidian tag")

    else if isMarker(trimmed) then Marker
    else Carried(OwnedTag.vault(trimmed))

  /** Whether a tag is one of this tool's own markers rather than the author's subject.
    *
    * MATCHED ON THE FIRST SEGMENT ONLY, so a genuine topic called `flashcards` or
    * `flashcard-design` is carried. The marker vocabulary is `flashcard/…` exactly, and
    * `Marker.parse` is what decides that everywhere else — this is the same first segment that
    * `VaultWalker` already filters on when it looks for a whole-note marker.
    */
  private def isMarker(tag: String): Boolean =
    tag.split("/", -1).headOption.exists(_.equalsIgnoreCase("flashcard"))
