package obsidiananki.model

/** WHAT BECOMES OF ONE TAG WRITTEN IN A NOTE'S FRONTMATTER.
  *
  * REQUESTED BY MARC 2026-08-22, so that an Obsidian tag can drive an Anki filtered deck — study
  * everything tagged `backend/scala`, without maintaining a second list of what that means.
  *
  * A SUM TYPE RATHER THAN AN `Option`, because a tag that is not carried is not merely absent —
  * the author needs telling WHICH one and why, and a silent `None` is the shape of failure this
  * project designs against.
  *
  * IT DOES NOT DECIDE WHAT A MARKER IS, and deliberately: [[TagReading]] has already answered
  * that, better than a check here could. It catches a marker, a marker spelled wrongly, and a
  * `flashcard/` prefix with an unrecognised tail — three outcomes a first-segment test here
  * would have flattened into one. Only a tag it classified as the author's own reaches this.
  */
enum VaultTag:

  /** Carried into Anki under this tool's namespace. */
  case Carried(tag: OwnedTag)

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

    else Carried(namespaced(trimmed))

  /** NAMESPACED RATHER THAN CARRIED VERBATIM, AND THAT IS NOT TIDINESS. A verbatim `scala` in
    * Anki is indistinguishable from a `scala` somebody added by hand, so removing a tag deleted
    * in the vault would mean deleting a tag this tool never wrote. Under a prefix it owns, the
    * set is a pure function of the vault, and it can never touch a tag it did not write.
    *
    * THE HAZARD IS ANKI ITSELF, NOT THE AUTHOR, which is why discipline could not have replaced
    * this. Anki adds `leech` on its own when a card lapses too often, and `marked` when a card is
    * marked in the reviewer. Both land on notes this tool generated, and a verbatim sync that
    * removed whatever the vault no longer named would delete Anki's own record of which cards are
    * giving the author trouble — which can only be earned back by failing reviews again.
    */
  private def namespaced(nested: String): OwnedTag =
    OwnedTag.unsafe(s"$Prefix::${nested.replace("/", "::").toLowerCase(java.util.Locale.ROOT)}")
