package obsidiananki.anki

/** HOW MANY CARDS A NOTE HAS, ANSWERED THE WAY ANKI ANSWERS IT.
  *
  * IT EXISTS BECAUSE THE IN-MEMORY COLLECTION WAS ANSWERING IT FROM THE NOTE TYPE'S NAME —
  * `IN-FLIGHT.md` item 31. `Basic (and reversed)` gave two, concept-descriptor gave two or
  * three, and **anything else gave one**, including a note type a test had just defined with
  * three templates. So a test seeding its own multi-template note type and asserting about its
  * cards was measuring something other than what it appeared to, and said nothing while doing
  * it. That happened on 2026-08-28.
  *
  * WHY THAT MATTERED MORE THAN TIDINESS. The feature that prices a note-type change — "this
  * destroys N cards holding M reviews, approve it by name" — is *about* card counts, and it is
  * verified against that fake. A double which is wrong about card counts cannot validate a
  * feature whose whole job is counting cards before review history is spent.
  *
  * COUNTING TEMPLATES WOULD BE WRONG TOO, which is why the item recorded the obvious fix as not
  * obviously right. Anki generates a card for a template only when that template's FRONT renders
  * to something. That is not a detail: it is the entire reason this project's concept-descriptor
  * note type has a gate field at all — `{{#ThreeWay}}` wraps a whole front, so the third card
  * exists only for notes that asked for three ways.
  *
  * SO THE RULE IS MODELLED RATHER THAN APPROXIMATED, and modelled narrowly: enough of Anki's
  * template language to decide EMPTINESS, and nothing more. Nothing here renders anything a
  * person looks at; it answers one yes-or-no question per template.
  *
  * NOT A RENDERER, AND MUST NOT BECOME ONE. If a later change needs the rendered HTML of a
  * card, that is a different thing with different correctness conditions — this one is allowed
  * to be crude about everything except which fields survive.
  */
object CardGeneration:

  /** Fields Anki does NOT count when deciding whether a front is empty.
    *
    * ANKI'S OWN RULE, AND IT IS NOT AN OPTIMISATION. A front consisting only of the deck name
    * and the card name would be non-empty as text while telling the reviewer nothing about the
    * note, so Anki requires at least one REAL field to survive. Every front in this repository
    * opens with `<span class="deck">{{Deck}}</span>`, so getting this wrong would make every
    * template look non-empty, every gate stop mattering, and this file become an elaborate way
    * of counting templates — the exact bug it replaces, wearing different clothes.
    */
  val SpecialFields: Set[String] =
    Set("Deck", "Subdeck", "Type", "Card", "CardFlag", "Tags", "FrontSide")

  /** How many cards a note on this note type has, given these field values. */
  def cardCount(spec: NoteTypeSpec, fields: Vector[(String, String)]): Int =
    // THE NOTE TYPE'S NAME IS NEVER READ, WHICH IS THE WHOLE FIX. Everything below comes from
    // the spec handed in and the fields given, so a note type defined in a test is judged by
    // exactly the rule that judges one shipped in this repository.
    if spec.isCloze then clozeOrdinals(fields).size
    else
      val byName = fields.toMap
      spec.templates.toVector.count((_, t) => frontRenders(t.front, byName))

  /** Whether one template's front renders to anything a reviewer would see.
    *
    * SEPARATE AND PUBLIC so it can be tested against this repository's real templates directly,
    * rather than only through a count that could be right for compensating reasons.
    */
  def frontRenders(front: String, fields: Map[String, String]): Boolean =
    // ANKI'S RULE, STATED AS ANKI STATES IT: a card is generated when at least one REAL field
    // referenced on the front is non-empty. Deciding it on the rendered TEXT instead would make
    // any template carrying a static label — `<div>Question:</div>` — look non-empty forever.
    fieldRefsIn(resolveSections(front, fields)).exists: name =>
      !SpecialFields.contains(name) && fields.get(name).exists(_.nonEmpty)

  /** The distinct cloze ordinals a note's fields mention, in ascending order.
    *
    * A CLOZE NOTE TYPE DOES NOT GENERATE PER TEMPLATE, which is why this sits beside the
    * template rule rather than inside it. Anki makes one card per distinct `{{cN::…}}` number
    * found in the note's fields. The fake used to answer ONE for every cloze note — so a note
    * with three groups looked like a note with one card, and ordinal drift, the failure the
    * cloze redesign is organised around, could not be reproduced against it at all.
    */
  def clozeOrdinals(fields: Vector[(String, String)]): Vector[Int] =
    fields
      .flatMap((_, value) => ClozeOrdinal.findAllMatchIn(value).map(_.group(1).toInt))
      .distinct
      .sorted

  // ══════════════════════════════════════════════════════════ the template subset ════

  /** A template with its conditional sections resolved away, leaving only what would render.
    *
    * NESTING IS HANDLED RATHER THAN ASSUMED ABSENT. This repository's own fronts nest three
    * deep — `{{#ThreeWay}}` around `{{#ConceptLabel}}` around `{{#Context}}` — and while no
    * template today nests the SAME name inside itself, matching the first close tag by name
    * would be a silent wrong answer on the day one did.
    */
  private def resolveSections(template: String, fields: Map[String, String]): String =
    SectionOpen.findFirstMatchIn(template) match
      case None => template
      case Some(m) =>
        val kind      = m.group(1)
        val name      = m.group(2)
        val bodyStart = m.end
        val closeAt   = closeOf(template, name, bodyStart)
        val body      = template.substring(bodyStart, closeAt)
        val after     = template.substring(closeAt + name.length + 5) // past `{{/name}}`

        // `#` KEEPS WHEN THE FIELD IS SET, `^` KEEPS WHEN IT IS NOT. Getting that backwards
        // would pass a test about the three-way gate while failing the value-only one, because
        // the two gates in this repository open in opposite directions.
        val present = fields.get(name).exists(_.nonEmpty)
        val keep    = if kind == "#" then present else !present

        template.substring(0, m.start) +
          (if keep then resolveSections(body, fields) else "") +
          resolveSections(after, fields)

  /** Where the section opened at `from` closes, counting sections of the same name. */
  private def closeOf(template: String, name: String, from: Int): Int =
    val close = s"{{/$name}}"
    val opens = Vector(s"{{#$name}}", s"{{^$name}}")

    @annotation.tailrec
    def go(i: Int, depth: Int): Int =
      val c = template.indexOf(close, i)
      // LOUD, BECAUSE THE ALTERNATIVE IS A CARD COUNT THAT IS QUIETLY WRONG. An unclosed
      // section means a broken template in this repository's own resources — not a condition a
      // caller can do anything about — and guessing where it closed would make some note
      // silently gain or lose a card.
      if c < 0 then sys.error(s"unclosed section {{#$name}} in a card template")
      opens.map(template.indexOf(_, i)).filter(o => o >= 0 && o < c).minOption match
        case Some(o) => go(o + 1, depth + 1)
        case None    => if depth == 1 then c else go(c + close.length, depth - 1)

    go(from, 1)

  /** Every plain field reference in a template, with any filter prefix removed.
    *
    * `{{cloze:Text}}` AND `{{text:Front}}` NAME THE SAME FIELDS as `{{Text}}` and `{{Front}}`.
    * Anki writes its filters before the field name, so treating the whole thing as the name
    * would mean a filtered reference matched no field and its card looked empty.
    */
  private def fieldRefsIn(template: String): Vector[String] =
    FieldRef.findAllMatchIn(template).map(_.group(1).split(':').last.trim).toVector

  /** `{{#Name}}` or `{{^Name}}` — the opening of a conditional section. */
  private val SectionOpen = """\{\{([#^])([^}]+)\}\}""".r

  /** `{{Name}}` or `{{filter:Name}}` — a reference, excluding the three section markers. */
  private val FieldRef = """\{\{([^#^/][^}]*)\}\}""".r

  private val ClozeOrdinal = """\{\{c(\d+)::""".r
