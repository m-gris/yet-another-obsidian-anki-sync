package obsidiananki.model

/** WHAT MAY BE CARRIED FROM A NOTE'S FRONTMATTER INTO ANKI, AND WHAT MAY NOT.
  *
  * Nothing here is plumbed yet — this file pins the mapping before anything writes a tag, so the
  * decisions are made once and visibly rather than discovered later in a collection.
  */
class VaultTagTest extends munit.FunSuite:

  private def carried(raw: String): String = VaultTag.read(raw) match
    case VaultTag.Carried(t)   => t.value
    case VaultTag.Marker       => fail(s"'$raw' was treated as a marker")
    case VaultTag.Unusable(_, w) => fail(s"'$raw' was refused: $w")

  private def refused(raw: String): String = VaultTag.read(raw) match
    case VaultTag.Unusable(_, why) => why
    case other                     => fail(s"'$raw' was accepted as $other")

  // ══════════════════════════════════════════════════ what is carried ══════════

  /** THE WHOLE POINT: an author's tag becomes an Anki tag they can put in a filtered deck. */
  test("a plain tag is carried under this tool's namespace") {
    assertEquals(carried("scala"), "obsidian::scala")
  }

  /** OBSIDIAN NESTS WITH `/`, ANKI WITH `::`. Asserted at two levels because a one-level
    * translation could be a `replaceFirst` and would then be wrong for the case that matters.
    */
  test("nesting is translated, at every level") {
    assertEquals(carried("backend/scala"), "obsidian::backend::scala")
    assertEquals(carried("backend/scala/cats/effect"), "obsidian::backend::scala::cats::effect")
  }

  /** ANKI FOLDS TAG CASE, so producing `obsidian::Scala` would produce a tag Anki cannot tell
    * apart from `obsidian::scala` — two spellings of one tag, which is how a set stops being a
    * function of the vault.
    */
  test("case is folded, because Anki folds it") {
    assertEquals(carried("Backend/Scala"), "obsidian::backend::scala")
  }

  /** TOLERATED RATHER THAN REQUIRED. Obsidian writes a tag bare in frontmatter and with a `#` in
    * the body, so an author moving one by hand brings the `#` often enough.
    */
  test("a leading hash is tolerated") {
    assertEquals(carried("#backend/scala"), "obsidian::backend::scala")
    assertEquals(carried("  #backend/scala  "), "obsidian::backend::scala")
  }

  /** NON-ASCII IS CARRIED AS ITSELF. Anki holds unicode tags, so encoding one would produce
    * something the author could not type into the search this feature exists to enable.
    */
  test("an accented tag is carried unchanged") {
    assertEquals(carried("maths/algèbre"), "obsidian::maths::algèbre")
  }

  // ══════════════════════════════════════════════════ what is not ══════════════

  /** A MARKER IS AN INSTRUCTION, ALREADY OBEYED BY THE TIME ANYTHING IS WRITTEN. Carrying it
    * would file `obsidian::flashcard::sequence::headers` beside `obsidian::backend`, looking like
    * a subject somebody might want to study.
    */
  test("a flashcard marker is not a subject and is not carried") {
    assertEquals(VaultTag.read("flashcard/cloze"), VaultTag.Marker)
    assertEquals(VaultTag.read("flashcard/sequence/headers/recursive"), VaultTag.Marker)
    assertEquals(VaultTag.read("#flashcard/2way"), VaultTag.Marker)
  }

  /** THE FIRST SEGMENT ONLY, so a genuine subject that merely begins with the same letters is
    * still the author's tag and is still carried.
    */
  test("a topic that merely resembles the marker prefix is carried") {
    assertEquals(carried("flashcards/design"), "obsidian::flashcards::design")
    assertEquals(carried("flashcard-design"), "obsidian::flashcard-design")
  }

  /** ANKI SEPARATES TAGS WITH WHITESPACE, so carrying this would silently produce TWO tags —
    * neither of which the author wrote. Refused by name rather than mangled into a spelling they
    * never chose, or percent-encoded into one they could not type.
    */
  test("a tag containing a space is refused, not silently split") {
    assert(refused("my tag").contains("space"), refused("my tag"))
    assert(refused("backend/my tag").contains("space"))
  }

  /** `::` IS ANKI'S OWN SEPARATOR, so a vault tag holding one would arrive as nesting the author
    * did not write, under a branch this tool could not map back to anything.
    */
  test("a tag containing Anki's separator is refused") {
    assert(refused("backend::scala").contains("::"), refused("backend::scala"))
  }

  test("an empty tag is refused rather than producing a bare namespace") {
    assert(refused("").contains("empty"))
    assert(refused("   ").contains("empty"))
    assert(refused("#").contains("empty"))
  }

  // ══════════════════════════════════════════════════ the ownership law ════════

  /** THE LAW THAT MAKES REMOVAL SAFE, and the reason for the namespace at all.
    *
    * Every tag this produces must be recognised as this tool's own, or the sync could not remove
    * a tag the author deleted from a note without risking one they added by hand in Anki. And
    * Anki's own tags must NOT be recognised — it writes `leech` when a card lapses too often and
    * `marked` when a card is marked, both onto notes this tool generated, and deleting those
    * would destroy a record that can only be earned back by failing reviews.
    */
  test("what this produces is owned, and what Anki produces is not") {
    assert(OwnedTag.isOwned(carried("backend/scala")), "the tool would not recognise its own tag")
    assert(!OwnedTag.isOwned("leech"), "Anki's leech tag would be treated as this tool's")
    assert(!OwnedTag.isOwned("marked"), "Anki's marked tag would be treated as this tool's")
    assert(!OwnedTag.isOwned("scala"), "a hand-written tag would be treated as this tool's")
  }
