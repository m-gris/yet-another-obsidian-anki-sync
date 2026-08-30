package obsidiananki.model

import cats.data.NonEmptyVector

/** THE SEAM NOBODY WAS GUARDING.
  *
  * Five things consumed a card's identity and only one of them wrote it. The four readers were
  * each tested against their own idea of the format, and nothing asserted the five agreed — so
  * moving the identity from a tag into a field would have broken four workflows with every suite
  * still green. It took Marc asking, twice, for anyone to look.
  *
  * The first test below is the one that closes it: it does not describe the search, it asserts
  * the search MATCHES WHAT THE TOOL ACTUALLY WROTE. It fails if either side moves alone.
  */
class CardSearchTest extends munit.FunSuite:

  private def key(noteId: String, segments: String*): CardKey =
    CardKey(
      NoteId.fromFrontmatter(noteId).fold(e => fail(s"note id: $e"), identity),
      CardPath.Headings(
        HeadingPath(
          NonEmptyVector.fromVectorUnsafe(
            segments.toVector.map(s => HeadingSegment.fromExtractedText(s).fold(e => fail(s"$e"), identity))
          )
        )
      ),
    )

  /** THE LAW. The search is built from a note's frontmatter id; the identity is built from a
    * whole card key. They are produced by different functions for different callers, and the
    * only thing that makes the feature work is that one finds the other.
    *
    * ASSERTED AS A PREFIX MATCH, which is what Anki's trailing `*` means. A search naming the
    * identity exactly would find one card of a note that has several.
    */
  test("the search this tool hands out matches the identity this tool writes") {
    val k        = key("7c5ab5c8-8780-4cac-83fc-f31833ccca85", "Anatomy", "Long bone")
    val identity = TagCodec.encode(k).value
    val search   = CardSearch.forNoteId("7c5ab5c8-8780-4cac-83fc-f31833ccca85")

    val prefix = search
      .split(" or ")
      .head
      .stripPrefix("\"")
      .stripPrefix(s"${Marker.IdentityField}:")
      // THE QUOTE COMES OFF BEFORE THE WILDCARD, because the term ends `*"` — stripping in the
      // other order silently does nothing to the star and leaves it in the prefix.
      .stripSuffix("\"")
      .stripSuffix("*")

    assert(
      identity.startsWith(prefix),
      s"the search looks for '$prefix' and the tool writes '$identity' — they have drifted apart",
    )
  }

  /** BOTH HOMES, WHILE A LEGACY NOTE COULD EXIST. Asserted separately from the law above,
    * because the law would still hold if one half were dropped — and dropping either is
    * invisible: a Browse window listing no cards reads as "this note made none".
    *
    * THIS TEST REFUSES A PREMATURE TIDYING, NOT A PERMANENT ONE. The tag half serves notes
    * written before the identity had a field, and removing it while any remain strands exactly
    * the notes that still need migrating. When that population is gone, this test and its half
    * go together — see oas-ktm.
    */
  test("the search names both the field and the tag") {
    val s = CardSearch.forNoteId("abc123")
    assert(s.contains(s"${Marker.IdentityField}:src::abc123::*"), s"no field half: $s")
    assert(s.contains("tag:src::abc123::*"), s"no tag half: $s")
    assert(s.contains(" or "), s"the two homes are not alternatives: $s")
  }

  /** THE ONE THAT WOULD BE CATASTROPHIC RATHER THAN MERELY WRONG. `tag:src::*` matches every card
    * this tool has ever made, so an empty id must match NOTHING — a Browse window holding the
    * whole collection, or a drill deck gathering it, is far worse than an empty one.
    */
  test("an empty id matches nothing, never everything") {
    for empty <- Vector("", "   ", "\t") do
      val s = CardSearch.forNoteId(empty)
      assertEquals(s, "nid:0", s"an empty id produced a real search: $s")
      assert(!s.contains("src::"), s"an empty id produced an identity search: $s")
  }

  /** THE SEARCH IS QUOTED, and that is required rather than decorative: a term holding `:` is
    * read by Anki as a field query, so an unquoted `Identity:src::x::*` would be parsed as a
    * field named `Identity` holding `src`, followed by nonsense.
    */
  test("each half is quoted, because the value contains colons") {
    val s = CardSearch.forNoteId("abc123")
    s.split(" or ").foreach(half => assert(half.trim.startsWith("\""), s"unquoted half: $half"))
    s.split(" or ").foreach(half => assert(half.trim.endsWith("\""), s"unquoted half: $half"))
  }

  /** AN ID IS FOLDED AND ENCODED THE SAME WAY THE IDENTITY FOLDS AND ENCODES IT, or the search
    * misses notes whose id contains anything unusual. This is the half a hand-written `curl`
    * command could never have got right, and did not have to until somebody used such an id.
    */
  test("an id is encoded exactly as the identity encodes it") {
    val awkward = "Weird Id"
    val k       = key(awkward, "Heading")
    val written = TagCodec.encode(k).value
    val search  = CardSearch.forNoteId(awkward)
    val encoded = TagCodec.encodeComponent(awkward.toLowerCase(java.util.Locale.ROOT))

    assert(search.contains(encoded), s"the search does not use the encoded id '$encoded': $search")
    assert(
      written.contains(encoded),
      s"the identity does not use the encoded id '$encoded': $written — the premise is broken",
    )
  }
