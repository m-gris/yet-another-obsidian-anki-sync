package obsidiananki.plan

import cats.data.NonEmptyVector
import obsidiananki.model.*

/** WHAT A HANDLE HAS TO GUARANTEE, which is narrower than "it is a hash".
  *
  * The handle exists so that answering a pending decision cannot act on a note other than the
  * one whose price was read. Everything below tests that property from some angle; nothing tests
  * the digest itself, which is not this project's to verify.
  */
class DecisionHandleTest extends munit.FunSuite:

  def key(id: String, segments: String*): CardKey =
    CardKey(
      NoteId.fromFrontmatter(id).toOption.get,
      CardPath.Headings(
        HeadingPath(
          NonEmptyVector.fromVectorUnsafe(
            segments.toVector.map(s => HeadingSegment.fromExtractedText(s).toOption.get)
          )
        )
      ),
    )

  /** THE WHOLE POINT, STATED FIRST. Two runs must agree, or the second command acts on the
    * wrong note — which is the failure this type exists to make impossible.
    */
  test("the same card always gets the same handle") {
    val k = key("note-a", "reads")
    assertEquals(
      DecisionHandle.of(k).value,
      DecisionHandle.of(k).value,
      "a handle that varied between calls would name a different note on the second run",
    )
  }

  /** THE CASE THAT MADE A SHORT NAME UNUSABLE. Marc's vault holds two notes whose card path is
    * exactly `definition`, in different files — measured 2026-08-27. A name the author types
    * must tell them apart, and the heading alone cannot.
    */
  test("two cards with the same heading in different notes get different handles") {
    assertNotEquals(
      DecisionHandle.of(key("note-a", "definition")).value,
      DecisionHandle.of(key("note-b", "definition")).value,
      "these are the two notes a heading-only name could not distinguish; the handle must",
    )
  }

  test("cards differing only in heading get different handles") {
    assertNotEquals(
      DecisionHandle.of(key("note-a", "reads")).value,
      DecisionHandle.of(key("note-a", "writes")).value,
    )
  }

  /** A HANDLE IS READ OFF A TERMINAL AND TYPED BACK, so its shape is part of its usability
    * rather than an implementation detail.
    */
  test("a handle is exactly the declared number of lowercase hex characters") {
    val h = DecisionHandle.of(key("note-a", "reads")).value
    assertEquals(h.length, DecisionHandle.Length, s"handle was '$h'")
    assert(h.forall(c => c.isDigit || ('a' to 'f').contains(c)), s"not lowercase hex: '$h'")
  }

  test("a handle the tool printed is one the tool accepts back") {
    val h = DecisionHandle.of(key("note-a", "reads"))
    assertEquals(DecisionHandle.parse(h.value).map(_.value), Some(h.value))
  }

  /** TYPED BY HAND, SO TYPED IMPERFECTLY. Refusing a handle over letter case would be a refusal
    * the author cannot act on, since the two look identical when read aloud or written down.
    */
  test("a handle is accepted whatever case it is typed in, and surrounding space is ignored") {
    val h = DecisionHandle.of(key("note-a", "reads")).value
    assertEquals(DecisionHandle.parse(h.toUpperCase).map(_.value), Some(h))
    assertEquals(DecisionHandle.parse(s"  $h  ").map(_.value), Some(h))
  }

  /** REFUSED ON SHAPE, SEPARATELY FROM BEING UNKNOWN. A mistyped handle and a handle for a note
    * that is no longer waiting are different situations and deserve different sentences, so this
    * layer answers only the first.
    */
  test("something that is not a handle at all is refused") {
    assertEquals(DecisionHandle.parse(""), None, "empty")
    assertEquals(DecisionHandle.parse("abc"), None, "too short")
    assertEquals(DecisionHandle.parse("abcdefg"), None, "too long")
    assertEquals(DecisionHandle.parse("abcdez"), None, "z is not hex")
    assertEquals(DecisionHandle.parse("2"), None, "a list position is not a handle")
  }

  /** COLLISIONS ARE THE ONE FAILURE THAT WOULD BE SILENT, because two notes sharing a handle
    * means answering one could act on the other — precisely what this type promises cannot
    * happen. Asserted over far more cards than any real collection holds, so the guarantee is
    * not merely true of a handful of examples.
    */
  test("a thousand distinct cards produce a thousand distinct handles") {
    val handles =
      (1 to 1000).map(i => DecisionHandle.of(key(s"note-$i", s"heading-$i")).value).toSet
    assertEquals(handles.size, 1000, "two different cards were given the same handle")
  }
