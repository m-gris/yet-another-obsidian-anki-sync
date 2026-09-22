package obsidiananki.spike

import obsidiananki.model.VaultTag

/** SPIKE — can ONE field on the card face hold "what this card is about", whatever said so?
  *
  * **BELONGS TO `oas-ptm.1`**, the first bead of `oas-ptm` (put frontmatter subject tags on the
  * card face). Nothing imports this and nothing here renders anything.
  *
  * ==The question this settles, and why it is asked before anything is built==
  *
  * A card built from a note at the vault ROOT often ships with an EMPTY breadcrumb. `Context` is
  * composed from folders, file name and ancestor headings; a root note has no folders; and the
  * file name is then stripped whenever the card already shows it as a field. Marc's vault is 84%
  * flat — 77 of 92 notes at the root, measured 2026-09-22 — so this is the ordinary case rather
  * than an edge one.
  *
  * The author's frontmatter subject tags — `CS`, `math`, `PLT` — hold exactly the missing
  * information, already travel into Anki as `obsidian::…` tags, and never reach the face.
  *
  * Marc's ruling is that the new field is a GENERAL SUBJECT SLOT, not a tags-only field: tags
  * fill it now, another source may fill it later, and when that happens it must arrive as a new
  * INPUT rather than as a second field. That is a claim about types, so it was cheapest to put
  * to the compiler before any of it was implemented.
  *
  * ==FINDING 1 — MEASURED, THEN FIXED==
  *
  * The obvious signature is `from(tags: Vector[VaultTag])`, `VaultTag` being the type that
  * already means "what becomes of one tag written in a note's frontmatter". On 2026-09-22 it did
  * not work, and the compiler said why:
  *
  * {{{
  * value raw is not a member of obsidiananki.model.OwnedTag
  * }}}
  *
  * `VaultTag.read` folded case and namespaced on the way in, so `CS` became `obsidian::cs` and
  * the author's spelling was destroyed at the moment of reading. The folding is CORRECT — Anki
  * case-folds tags, and writing both spellings would write a tag Anki cannot tell apart from the
  * other — but it was applied to the wrong thing. One value meant both "what the author wrote"
  * and "what Anki stores", so the second overwrote the first.
  *
  * That was invisible while the only consumer was the tag writer, for which the folded form is
  * exactly right. A card face is the consumer that makes it visible: `CS` is an acronym, `cs`
  * reads as a typo, `PLT` lowercased reads as noise.
  *
  * RESOLVED BY `oas-ptm.12`: `VaultTag.Carried` now carries the author's spelling alongside the
  * Anki-facing tag, so the folding is a property of the DERIVED tag rather than of the
  * frontmatter tag. Nothing about what reaches Anki changed, and a test asserts exactly that.
  * This file's signature is the direct beneficiary — it takes `VaultTag` as it always should
  * have, and the stand-in shape an earlier revision of this spike carried is gone.
  *
  * ==FINDING 2 — the general slot composes, and its generality is not yet load-bearing==
  *
  * [[Topic]] composes: a subject is display text plus where it came from, and nothing in
  * [[fromFrontmatterTags]] is specific to tags beyond the arm that reads them. A second source
  * would be a second CONSTRUCTOR, not a second field on the note type, which is what the ruling
  * asked for.
  *
  * Stated plainly rather than sold: NOTHING BRANCHES ON [[TopicSource]] TODAY. It is one case.
  * Marc ruled to keep it, the reasoning being that it is the plan made structural and costs one
  * field to remove later if it never earns its place. It earns it if a second source ever needs
  * telling apart — for ordering, or for styling.
  *
  * ==What is deliberately NOT decided here==
  *
  * The separator, the ordering, whether a nested tag shows whole or by its leaf, and whether
  * duplicate subjects from different sources collapse. Those are rendering questions and belong
  * to `oas-ptm.3`. This file shows only that the values compose.
  */
object Topics:

  /** WHERE A SUBJECT CAME FROM.
    *
    * One case today. It exists so that a second source arrives as a second CASE rather than as a
    * second field on the note type — see FINDING 2, including what would justify removing it.
    */
  enum TopicSource:
    case FrontmatterTag

  /** ONE SUBJECT, as a reader sees it.
    *
    * `display` is the author's own spelling, never the namespaced tag: `CS`, not `obsidian::cs`.
    */
  final case class Topic(display: String, source: TopicSource)

  /** THE SUBJECTS OF ONE CARD.
    *
    * Opaque so that "the subjects of a card" cannot quietly decay into "some strings", the same
    * reason `OwnedTag` is opaque.
    */
  opaque type Topics = Vector[Topic]

  object Topics:
    val none: Topics                      = Vector.empty
    def of(topics: Vector[Topic]): Topics = topics
    extension (t: Topics) def values: Vector[Topic] = t

  /** Read the author's subject tags as subjects.
    *
    * TAKES EVERY READING AND NOT ONLY THE CARRIED ONES, so that the match is exhaustive and an
    * outcome added to `VaultTag` later has to say whether it shows on a card. `Unusable` is a
    * tag Anki refused: the report's business, never the card's.
    */
  def fromFrontmatterTags(tags: Vector[VaultTag]): Topics = ???

  /** The text the card shows, with anything the card already says removed.
    *
    * `shownOnCard` is the argument `extract/CardContext.compose` takes, for the same two reasons:
    * a subject repeated from the question side is noise, and one repeated from the answer side is
    * a spoiler. One list covers both.
    *
    * SEPARATE FROM [[fromFrontmatterTags]] rather than folded into it, because reading and
    * rendering are different jobs and a second source will want the first without the second.
    */
  def render(topics: Topics, shownOnCard: Vector[String]): String = ???
