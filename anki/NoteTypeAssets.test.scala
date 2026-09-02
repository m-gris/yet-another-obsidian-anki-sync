package obsidiananki.anki

import obsidiananki.model.Marker

/** WHETHER A SECTION RENDERS WHEN ITS FIELD HAS A VALUE OR WHEN IT HAS NONE.
  *
  * `{{#X}}` renders its body when `X` is non-empty; `{{^X}}` renders it when `X` is empty. THE
  * TWO ARE NOT INTERCHANGEABLE AND THE DIFFERENCE IS DESTRUCTIVE, which is why this is a type
  * and not a boolean nobody names. [[Marker.ValueOnlyField]] is inverted on purpose so that a
  * note predating the field keeps its concept-recall card; written `{{#ValueOnly}}` instead,
  * every such card would render blank and Anki's Tools > Empty Cards would offer to DELETE
  * cards holding real review history.
  */
enum FieldState:
  case Present
  case Absent

/** WHAT ANKI DOES WITH ONE `{{…}}` TAG, as distinct from WHICH FIELD it names.
  *
  * THE FIELD NAME IS HOISTED OUT OF THIS ENUM AND INTO [[TemplateReference]] because every tag
  * in Anki's template syntax names exactly one field — a fact worth stating in the type rather
  * than repeating as a parameter on each case.
  */
enum ReferenceRole:
  /** `{{Front}}`, `{{cloze:Text}}`, `{{text:cloze:Text}}` — the field's value is RENDERED.
    *
    * `filters` IS THE CHAIN IN SOURCE ORDER AND IS PART OF THE IDENTITY OF THE REFERENCE, which
    * is the whole reason this parameter exists. Until it did, `{{cloze:Text}}` and `{{Text}}`
    * were the same value to every test that consumed a reference, so the `cloze:` filter could
    * be dropped from the `Obsidian Cloze` templates and the suite would stay green — while the
    * card rendered the raw `{{c1::…}}` markup instead of a deletion. A test that cannot see a
    * difference is indistinguishable from one that has checked it.
    */
  case Rendered(filters: Vector[String])

  /** `{{#Field}}` or `{{^Field}}` — opens a section whose body renders conditionally. */
  case Section(rendersWhenFieldIs: FieldState)

  /** `{{/Field}}` — closes the nearest open section on that field. */
  case SectionEnd

/** ONE TAG'S MEANING: the field it names, and what Anki does with it. */
final case class TemplateReference(field: String, role: ReferenceRole)

/** One tag AS IT OCCURS IN A TEMPLATE: its meaning, plus the exact text it was written as.
  *
  * `source` IS KEPT RATHER THAN RECONSTRUCTED so that a structural check can compare against the
  * template's own text. Reconstructing `{{#ThreeWay}}` from the parsed form would silently
  * "correct" a tag written with stray whitespace, and a check that asserts nothing sits outside
  * a gate would then be comparing against text that is not in the file.
  */
final case class TemplateTag(source: String, reference: TemplateReference)

/** A TEMPLATE FRONT WHOSE WHOLE CONTENT SITS INSIDE ONE CONDITIONAL SECTION — so whether Anki
  * generates that card at all is decided by one field.
  *
  * ANKI GENERATES A CARD ONLY WHEN ITS FRONT RENDERS NON-EMPTY. That is the mechanism this
  * project uses to make a card opt-in, and it is the highest-consequence structure in any of
  * these templates: a character moved out of the wrapper mints cards for every note of the
  * type, and a polarity flipped the wrong way retires cards that carry review history.
  */
final case class CardGate(
    noteType: String,
    template: String,
    field: String,
    rendersWhenFieldIs: FieldState,
)

/** THE CONTRACT BETWEEN THE REPOSITORY'S NOTE TYPE DEFINITIONS AND THE SCALA THAT WRITES TO
  * THEM.
  *
  * WHY THIS SUITE EXISTS. Two descriptions of the same five note types live in this project:
  * `model/Marker.scala` says what field names the tool WRITES, and
  * the manifests under `resources/note-types` say what fields the note types it INSTALLS
  * declare. Until this
  * file existed nothing compared them. A disagreement would not have failed a build, would not
  * have failed a test, and would not have failed the plan — it would have surfaced per note, at
  * write time, against a real collection, AFTER the plan had been printed as though it would
  * work, and in the worst case not at all: Anki reports a field name it does not recognise as
  * "cannot create note because it is empty" on create, and as NO ERROR on update.
  *
  * `model/` DELIBERATELY DEPENDS ON NOTHING — `Marker.scala` has no imports at all — so the
  * comparison cannot live there and could not until there was a package that reads the files.
  *
  * THE OTHER HALF OF THE CONTRACT IS ELSEWHERE AND IS WORTH KNOWING ABOUT.
  * `model/Marker.test.scala` ties `CardSpec.fields` to `Marker.FieldOrder`, which is what makes
  * a silently truncating `Vector.zip` fail. This suite ties `Marker.FieldOrder` to the
  * manifests. Together the chain runs: what a card spec emits -> what Marker declares -> what
  * gets installed.
  */
class NoteTypeAssetsTest extends munit.FunSuite:

  /** THE ONE PLACE THE CLASSPATH IS READ, so a failure here reports once rather than in every
    * test below.
    */
  val assets: Vector[NoteTypeAsset] =
    NoteTypeAssets.all.fold(
      errors => fail(s"note type definitions did not load:\n  ${errors.map(_.describe).mkString("\n  ")}"),
      identity,
    )

  // -------------------------------------------------- the files are actually there ----

  /** The resource directory is on the classpath at all.
    *
    * A SEPARATE TEST FROM EVERY COMPARISON BELOW, because the failure it catches is a build
    * configuration failure — a missing `//> using resourceDir` line in `project.scala`, or the
    * files having been moved — and that reads nothing like a drifted field name.
    */
  test("all five note type definitions load off the classpath") {
    assertEquals(assets.size, 5)
    assertEquals(assets.map(_.slug), NoteTypeAssets.slugs.map(_._2))
  }

  /** `slugs` is what the installer iterates. A note type missing from it is one that never gets
    * created; a note type in it under the wrong name is one created beside the one that was
    * wanted.
    */
  test("the slug table names every note type in Marker.NoteTypes.All, in that order") {
    assertEquals(NoteTypeAssets.slugs.map(_._1), Marker.NoteTypes.All)
  }

  // -------------------------------------------------- manifest versus Marker ----

  /** THE TEST THE WHOLE SLICE EXISTS FOR, half one.
    *
    * Ordered, not set-compared. A set comparison would pass while the two disagreed about which
    * name belongs to which directory — which is exactly how the wrong template ends up on the
    * right-looking note type.
    */
  test("each manifest's model name is the Marker name for its slug, in order") {
    assertEquals(assets.map(_.spec.name), Marker.NoteTypes.All)
  }

  /** THE TEST THE WHOLE SLICE EXISTS FOR, half two.
    *
    * ORDER IS LOAD-BEARING TWICE, so this is not a set comparison either. `createModel` takes
    * `inOrderFields`, and Anki's Sort Field defaults to field 1 — so a reordering here would
    * install a note type whose Browse list shows a different column and whose fields sit in
    * different positions from the ones every template was written against.
    */
  test("each manifest's field list is exactly its note type's declared field order") {
    assets.foreach { asset =>
      assertEquals(
        asset.spec.fields.toVector,
        Marker.FieldOrder.byNoteType(asset.spec.name),
        s"'${asset.spec.name}' (${asset.slug}) declares different fields from Marker.FieldOrder",
      )
    }
  }

  /** Anki's Sort Field defaults to FIELD 1, so a breadcrumb there would fill the Browse list
    * with the same repeated prefix. That — not "last" — is the property.
    *
    * WEAKENED FROM "ends with Context" ON 2026-08-22, deliberately, when a second derived field
    * was added after it. The old wording asserted a position when the requirement is only that
    * this field is not the one Anki sorts by, and it made an ordinary addition look like a
    * violation. Order is otherwise not load-bearing: this tool writes fields BY NAME, and the
    * order in a collection decides the Browse columns rather than anything stored.
    *
    * THE ORDERING RULE THAT IS REAL, and which cost a debugging round to learn: Anki's
    * `modelFieldAdd` APPENDS. So a field added to an existing note type lands at the end
    * whatever the manifest says, and a manifest that declares it anywhere else leaves every
    * repaired collection permanently reporting a field-order difference this tool declines to
    * fix. New fields therefore go LAST in the manifest, in the order they were introduced.
    */
  test("no note type sorts by a derived field, and every one declares Context") {
    val derived = Set(Marker.ContextField, Marker.ConceptLabelField)
    assets.foreach { asset =>
      val fields = asset.spec.fields.toVector
      assert(
        fields.contains(Marker.ContextField),
        s"'${asset.spec.name}' has no ${Marker.ContextField} field",
      )
      assert(
        !derived.contains(fields.head),
        s"'${asset.spec.name}' sorts by '${fields.head}', a field this tool derives — Anki's " +
          "Sort Field is field 1, so the Browse list would repeat the same prefix on every row",
      )
    }
  }

  // -------------------------------------------------- the rename hazard ----

  /** `renamedFrom` IS THE KEY THAT STOPS A POPULATED NOTE TYPE BEING STRANDED.
    *
    * `createModel` refuses only when the NEW name already exists, so a create-if-missing
    * installer run before the two hand-renames would leave the collection holding two note
    * types — a new empty one and the old populated one, every note still on the old — and
    * nothing would report it. Pinned as literals, because the point is the exact strings that
    * exist in a live collection today; reading them back off the manifest would assert nothing.
    */
  test("exactly the two hand-renamed note types record the name they are renamed from") {
    val renames = assets.flatMap(a => a.renamedFrom.map(a.spec.name -> _)).toMap
    assertEquals(
      renames,
      Map(
        "Obsidian Cloze Sequence"     -> "Cloze Sequence",
        "Obsidian Concept-Descriptor" -> "3 way Concept-Descriptor",
      ),
    )
  }

  /** Those two definitions were READ OUT OF a live collection rather than authored, and say so,
    * so that a later reader does not "tidy" text that has to stay byte-identical to what is
    * there.
    */
  test("the two captured note types record where they were captured from") {
    val captured = assets.filter(_.manifest.capturedFrom.isDefined).map(_.spec.name)
    assertEquals(captured, Vector("Obsidian Cloze Sequence", "Obsidian Concept-Descriptor"))
  }

  /** A vendored note type names its licence file, and that file has to be there — otherwise the
    * repository ships someone else's work with the licence in the manifest and nowhere else.
    */
  test("every declared licence file resolves and is not empty") {
    val declared = assets.flatMap(a => a.manifest.derivedFrom.map(a.slug -> _))
    assert(declared.nonEmpty, "no note type declares a third-party origin; expected cloze-sequence")
    declared.foreach { (slug, derived) =>
      assert(
        NoteTypeAssets.readFile(slug, derived.licenceFile).isRight,
        s"$slug names licence file '${derived.licenceFile}', which does not resolve",
      )
    }
  }

  // -------------------------------------------------- templates versus fields ----

  /** Every `{{…}}` tag in a template, IN ORDER and carrying the text it was written as.
    *
    * ORDER AND SOURCE ARE WHY THIS EXISTS ALONGSIDE [[referencesIn]], which throws both away.
    * The card-gate tests below have to know which section opens FIRST and whether its matching
    * close is the LAST thing on the front — questions a set of references cannot answer.
    *
    * COMMENTS ARE STRIPPED FIRST, for the reason [[withoutHtmlComments]] gives.
    */
  def tagsIn(template: String): Vector[TemplateTag] =
    """\{\{([^}]*)\}\}""".r
      .findAllMatchIn(withoutHtmlComments(template))
      .map(found => TemplateTag(found.matched, referenceOf(found.group(1).trim, found.matched)))
      .toVector

  /** Classify what sits between one tag's braces.
    *
    * REFUSES WHAT IT CANNOT CLASSIFY RATHER THAN NORMALISING IT. The predecessor of this
    * function stripped the leading `#^/` and kept the segment after the last colon, so every
    * malformed tag became a plausible bare field name — and `{{cloze:Text}}` became the same
    * value as `{{Text}}`. Both are the failure this project is built against: a wrong answer
    * that looks like a right one.
    *
    * THROWS RATHER THAN CALLING munit's `fail`, so that the refusal is a property of the parser
    * and not of the suite it happens to live in — which is what lets a test assert the refusal
    * by its type rather than by matching on a message.
    */
  private def referenceOf(body: String, source: String): TemplateReference =
    body.headOption match
      case None      => refuse(s"'$source' is a tag with nothing in it")
      case Some('#') => section(body.tail, source, ReferenceRole.Section(FieldState.Present))
      case Some('^') => section(body.tail, source, ReferenceRole.Section(FieldState.Absent))
      case Some('/') => section(body.tail, source, ReferenceRole.SectionEnd)
      case Some(_) =>
        // `-1` KEEPS TRAILING EMPTY SEGMENTS, which Java's default `split` discards — so
        // `{{cloze:}}` would otherwise parse as a bare reference to a field called `cloze`.
        val segments = body.split(":", -1).toVector.map(_.trim)
        if segments.exists(_.isEmpty) then refuse(s"'$source' has an empty filter or field name")
        else TemplateReference(segments.last, ReferenceRole.Rendered(segments.init))

  /** A section marker, once its `#`, `^` or `/` has been taken off.
    *
    * ANKI PUTS NO FILTERS ON A SECTION MARKER — `{{#cloze:Text}}` is not a thing — so a colon
    * here is a template nobody can have tested, and is refused rather than read as a field name
    * with a colon in it.
    */
  private def section(name: String, source: String, role: ReferenceRole): TemplateReference =
    val trimmed = name.trim
    if trimmed.isEmpty then refuse(s"'$source' opens or closes a section on no field at all")
    else if trimmed.exists(_ == ':') then
      refuse(s"'$source' puts a filter on a section marker, which Anki does not support")
    else if "#^/".contains(trimmed.head) then refuse(s"'$source' stacks two section markers")
    else TemplateReference(trimmed, role)

  /** Refuse a tag outright. Named so that every refusal above reads as one thing. */
  private def refuse(why: String): Nothing =
    throw new IllegalArgumentException(s"$why — this is not a template Anki can render")

  /** Everything Anki treats as a field reference in a template, DEDUPLICATED BUT NOT FLATTENED.
    *
    * `{{Front}}` plain, `{{#Context}}` / `{{^X}}` / `{{/Context}}` section markers, and
    * `{{cloze:Text}}` / `{{text:cloze:Text}}` filter chains — the field is the segment after the
    * LAST colon, because filters chain.
    *
    * THE FILTERS AND THE SECTION POLARITY STAY IN THE VALUE, which is the difference from what
    * this returned before. A `Set[String]` made `{{cloze:Text}}` and `{{Text}}` the same
    * element, so no test that consumed this could see a dropped `cloze:` filter — and none did.
    * Use [[fieldsIn]] where only the NAMES matter.
    */
  def referencesIn(template: String): Set[TemplateReference] =
    tagsIn(template).map(_.reference).toSet

  /** The field NAMES a template mentions, in any tag position.
    *
    * SECTION MARKERS COUNT AS MENTIONS, and that is deliberate rather than sloppy: `SameShape`
    * and `Reveal` are never rendered — they exist only to be tested by `{{^SameShape}}` and
    * `{{#Reveal}}` — so a rule that only counted rendered references would call two live fields
    * unused.
    */
  def fieldsIn(template: String): Set[String] = referencesIn(template).map(_.field)

  /** Strip `<!-- … -->` BEFORE looking for field references.
    *
    * WITHOUT THIS, THE "IS THIS FIELD RENDERED ANYWHERE?" TEST IS SATISFIED BY A MENTION INSIDE
    * A COMMENT — which renders nothing at all. The failure that test exists to prevent is a
    * field "declared, populated, hashed and synced, and INVISIBLE", and a commented-out
    * reference produces exactly that while turning the test green. Found by review rather than
    * by the test itself, which is the point: the check had a hole shaped like its own purpose.
    *
    * NOT A GENERAL HTML PARSER, and it does not need to be: these are ten template files this
    * project writes and owns. Nested comments are not legal HTML and are not attempted here.
    */
  def withoutHtmlComments(template: String): String =
    """(?s)<!--.*?-->""".r.replaceAllIn(template, "")

  /** References Anki resolves itself, which are therefore not field names.
    *
    * AN EXPLICIT, SHORT LIST OF WHAT OUR TEMPLATES ACTUALLY USE, never a pattern. An
    * unrecognised reference has to FAIL rather than be waved through — a template referring to
    * `{{Contex}}` renders nothing and reports nothing, which is the failure shape this whole
    * project is built against.
    */
  val ankiSpecialReferences: Set[String] = Set("FrontSide", "Deck")

  /** DIRECTION ONE: a template may not refer to a field the note type does not declare.
    *
    * Anki renders such a reference as literal text or as nothing at all, depending on version —
    * either way the card is wrong and nothing errors.
    */
  test("every field a template refers to is declared by its note type") {
    assets.foreach { asset =>
      val declared = asset.spec.fields.toVector.toSet
      asset.spec.templates.toVector.foreach { (templateName, template) =>
        Vector("front" -> template.front, "back" -> template.back).foreach { (side, text) =>
          val unknown = fieldsIn(text) -- declared -- ankiSpecialReferences
          assertEquals(
            unknown,
            Set.empty[String],
            s"'${asset.spec.name}' template '$templateName' ($side) refers to " +
              s"${unknown.mkString(", ")}, which is neither a declared field nor a known Anki reference",
          )
        }
      }
    }
  }

  /** DIRECTION TWO, AND THIS IS THE ONE THAT CATCHES A NEW FIELD NOBODY WIRED UP.
    *
    * A field can be declared, populated, hashed and synced, and be INVISIBLE — because no
    * template mentions it. That is exactly how the `Context` field could have failed: every
    * test green, every note carrying its breadcrumb, and not one card showing it.
    */
  test("every field a note type declares is referred to by at least one of its templates") {
    assets.foreach { asset =>
      val referenced = asset.spec.templates.toVector.flatMap { (_, template) =>
        fieldsIn(template.front) ++ fieldsIn(template.back)
      }.toSet
      // ONE FIELD IS EXEMPT, AND IT IS EXEMPT ON PURPOSE RATHER THAN BY OVERSIGHT.
      //
      // `Identity` is the string this tool writes so a later run can recognise a card it
      // already made. It moved out of a tag and into a field on 2026-08-28 so that the
      // author's own tag tree stops carrying a machine's ledger. It is not meant to be seen,
      // so the failure this law exists to catch — a field somebody meant to SHOW and forgot to
      // wire up — cannot apply to it.
      //
      // AND PUTTING IT IN A TEMPLATE WOULD BE ACTIVELY UNSAFE, which is the stronger half of
      // the argument. Anki generates a card when a template's FRONT renders non-empty, and two
      // of this tool's templates are wrapped in conditionals on field values precisely to
      // suppress cards — `{{^ValueOnly}}` and `{{#ThreeWay}}`. A field present on every note
      // would make an otherwise-empty front non-empty, minting cards nobody asked for.
      val exempt = Set(obsidiananki.model.Marker.IdentityField)
      val unused = asset.spec.fields.toVector.toSet -- referenced -- exempt
      assertEquals(
        unused,
        Set.empty[String],
        s"'${asset.spec.name}' declares ${unused.mkString(", ")}, which no template renders — " +
          "the field would be stored and never shown",
      )
    }
  }

  /** The `.context` rule is what makes the breadcrumb subordinate rather than a title. Each
    * note type's stylesheet is COMPLETE — no shared partial is concatenated at install — so the
    * rule is duplicated five times on purpose, and this asserts the duplication is intact.
    */
  test("every note type's stylesheet carries the .context rule") {
    assets.foreach { asset =>
      assert(
        asset.spec.styling.contains(".context"),
        s"'${asset.spec.name}' has no .context rule, so its breadcrumb would inherit card styling",
      )
    }
  }

  /** A field mentioned ONLY inside an HTML comment is not rendered, and must not count.
    *
    * Pins [[withoutHtmlComments]] rather than any asset, because the hole was in the CHECK and
    * a future edit to the helper would otherwise reopen it silently.
    */
  test("a field referred to only inside an HTML comment counts as unreferenced") {
    assertEquals(fieldsIn("<!-- {{Context}} -->"), Set.empty[String])
    assertEquals(fieldsIn("<!-- {{Context}} -->{{Front}}"), Set("Front"))
    // A multi-line comment, since that is what a disabled block actually looks like.
    assertEquals(fieldsIn("<!--\n  <div>{{Context}}</div>\n-->{{Front}}"), Set("Front"))
  }

  /** A FILTERED REFERENCE IS NOT THE SAME REFERENCE AS A BARE ONE, and nothing asserted that.
    *
    * `referencesIn` used to return the segment after the last colon and nothing else, so
    * `{{cloze:Text}}` and `{{Text}}` were one value. Every test that consumed it — including the
    * two directions above — was therefore blind to a `cloze:` filter being deleted from a
    * template, which turns a cloze card into one that displays the raw `{{c1::…}}` markup.
    *
    * PINS THE PARSER RATHER THAN ANY ASSET, like the HTML-comment test above it and for the same
    * reason: the hole was in the CHECK, so an asset-side test would not have closed it.
    */
  test("a filtered reference, a bare one and a section marker are three different references") {
    val bare = TemplateReference("Text", ReferenceRole.Rendered(Vector.empty))
    assertEquals(referencesIn("{{Text}}"), Set(bare))
    assertEquals(
      referencesIn("{{cloze:Text}}"),
      Set(TemplateReference("Text", ReferenceRole.Rendered(Vector("cloze")))),
    )
    assertNotEquals(referencesIn("{{cloze:Text}}"), referencesIn("{{Text}}"))

    // Filters CHAIN, in source order, and the field is the last segment.
    assertEquals(
      referencesIn("{{text:cloze:Text}}"),
      Set(TemplateReference("Text", ReferenceRole.Rendered(Vector("text", "cloze")))),
    )

    // A section marker names the same field and is not a rendering of it; the two POLARITIES
    // are likewise distinct, because `{{#X}}` and `{{^X}}` select opposite sets of notes.
    assertEquals(
      referencesIn("{{#Context}}"),
      Set(TemplateReference("Context", ReferenceRole.Section(FieldState.Present))),
    )
    assertEquals(
      referencesIn("{{^Context}}"),
      Set(TemplateReference("Context", ReferenceRole.Section(FieldState.Absent))),
    )
    assertEquals(referencesIn("{{/Context}}"), Set(TemplateReference("Context", ReferenceRole.SectionEnd)))
    assertEquals(referencesIn("{{#Context}}{{^Context}}{{/Context}}{{Context}}").size, 4)

    // A field name may contain a space — `Back Extra` is one of Anki's own stock field names.
    assertEquals(fieldsIn("{{Back Extra}}"), Set("Back Extra"))
  }

  /** A TAG THIS PARSER CANNOT CLASSIFY IS REFUSED, never read as a plausible field name.
    *
    * The consequence of guessing is the one this whole suite exists to prevent: a template
    * referring to a field that does not exist renders nothing and reports nothing, and a check
    * that quietly turned the malformed tag into a name that happens to be declared would call
    * that template correct.
    */
  test("a malformed tag is refused rather than normalised into a field name") {
    Vector("{{}}", "{{#}}", "{{/}}", "{{cloze:}}", "{{:Text}}", "{{#cloze:Text}}", "{{##Text}}")
      .foreach { malformed =>
        intercept[IllegalArgumentException](referencesIn(malformed))
      }
  }

  /** THE `cloze:` FILTER IS WHAT MAKES A CLOZE CARD A CLOZE CARD, and it appears on exactly the
    * note types that declare `isCloze`.
    *
    * WITHOUT THE FILTER, on a cloze note type, the card shows the literal `{{c1::answer}}`
    * source text: the note is stored correctly, the card is generated, and it displays the
    * answer it was supposed to hide. Nothing errors.
    *
    * THE OTHER DIRECTION IS THE ONE THIS PROJECT HAS ALREADY BEEN BITTEN BY. `Obsidian Cloze
    * Sequence` has "Cloze" in its name, defines `.cloze` and `.hidden-cloze` in its stylesheet
    * and calls its hidden items clozes — and it is NOT a cloze note type (`isCloze: false`). It
    * renders `{{Text}}`, a plain reference, and a well-meaning edit "fixing" that to
    * `{{cloze:Text}}` would break every card it has. Every available heuristic — the name, the
    * CSS, the vocabulary — gets this one exactly backwards, so the manifest is the only
    * authority and this test is written against it.
    *
    * BOTH SIDES, not just the front. The front without the filter generates nothing usable; the
    * back without it shows the deletion markup beside the answer.
    */
  test("the cloze filter is used by exactly the note types that declare isCloze, on both sides") {
    val ClozeFilter = "cloze"
    assets.foreach { asset =>
      asset.spec.templates.toVector.foreach { (templateName, template) =>
        Vector("front" -> template.front, "back" -> template.back).foreach { (side, text) =>
          val clozed = referencesIn(text).collect {
            case TemplateReference(field, ReferenceRole.Rendered(filters))
                if filters.contains(ClozeFilter) =>
              field
          }
          if asset.spec.isCloze then
            assert(
              clozed.nonEmpty,
              s"'${asset.spec.name}' declares isCloze and its template '$templateName' ($side) " +
                s"renders no field through the $ClozeFilter filter, so the card would show the " +
                "literal {{c1::…}} markup instead of a deletion",
            )
          else
            assertEquals(
              clozed,
              Set.empty[String],
              s"'${asset.spec.name}' does NOT declare isCloze, yet its template '$templateName' " +
                s"($side) puts the $ClozeFilter filter on ${clozed.mkString(", ")} — on a " +
                "non-cloze note type that filter renders nothing at all",
            )
        }
      }
    }
  }

  /** EVERY TEMPLATE'S FRONT, not merely one of them.
    *
    * The direction-two test above unions all of a note type's templates together, so a field
    * rendered by ONE template satisfies it for ALL of them. `Obsidian Concept-Descriptor` has
    * three templates and `Obsidian Basic (and reversed card)` has two; under the union rule the
    * breadcrumb could be missing from every card but one and the suite would stay green.
    *
    * THE FRONT SPECIFICALLY, because that is what the field is FOR: context you need in order to
    * answer. Told after the fact that the question meant the frontal BONE, you have already
    * answered the wrong question.
    */
  test("every template's FRONT renders the Context field, not just one template per note type") {
    assets.foreach { asset =>
      asset.spec.templates.toVector.foreach { (templateName, template) =>
        assert(
          fieldsIn(template.front).contains(Marker.ContextField),
          s"'${asset.spec.name}' template '$templateName' does not render " +
            s"${Marker.ContextField} on its front, so that card shows no breadcrumb",
        )
      }
    }
  }

  /** The template must carry the class its own stylesheet styles.
    *
    * NOTHING TIED THESE TOGETHER BEFORE. The stylesheet test above proves a `.context` rule
    * exists; nothing proved a template ever uses it. Renaming the class in a template — or the
    * rule in a stylesheet — left the whole suite green and the breadcrumb rendering with the
    * card's own styling: full size, centred, indistinguishable from a title. The breadcrumb
    * being SUBORDINATE is the entire design; unstyled, it reads as the question.
    */
  test("the class a front puts the breadcrumb in is the class its stylesheet styles") {
    val ContextClass = "context"
    assets.foreach { asset =>
      assert(
        asset.spec.styling.contains(s".$ContextClass"),
        s"'${asset.spec.name}' stylesheet does not define .$ContextClass",
      )
      asset.spec.templates.toVector.foreach { (templateName, template) =>
        assert(
          template.front.contains(s"""class="$ContextClass""""),
          s"'${asset.spec.name}' template '$templateName' renders the breadcrumb without " +
            s"""class="$ContextClass", so the stylesheet's rule cannot reach it""",
        )
      }
    }
  }

  /** THE HIGHEST-CONSEQUENCE PLACEMENT IN ANY OF THESE TEMPLATES, and it had no test at all.
    *
    * ANKI GENERATES A CARD ONLY WHEN ITS FRONT RENDERS NON-EMPTY. `Obsidian
    * Concept-Descriptor`'s third card exists only for notes that set `ThreeWay`, which is why
    * its whole front is wrapped in `{{#ThreeWay}}…{{/ThreeWay}}`.
    *
    * Put the `{{#Context}}` block OUTSIDE that wrapper and the front renders the breadcrumb for
    * every note that has a breadcrumb — which is nearly all of them. The front is then non-empty,
    * Anki generates a third card that was never meant to exist, and it arrives with no review
    * history. Moving one block by a few characters silently creates cards.
    *
    * SPECIFIC RATHER THAN GENERAL, deliberately: this checks the one card-generating conditional
    * this project uses. If another is ever introduced, this test does NOT cover it and must be
    * extended — stated here because a test that looks general and is not is worse than one that
    * admits its scope.
    */
  test("on a conditional template, the Context block sits INSIDE the wrapper that gates the card") {
    val gated = assets.flatMap { asset =>
      asset.spec.templates.toVector.collect {
        case (name, template) if template.front.contains(s"{{#${Marker.ThreeWayField}}}") =>
          (asset.spec.name, name, template.front)
      }
    }

    assert(gated.nonEmpty, "no gated template found — this test has stopped covering anything")

    gated.foreach { (noteType, templateName, front) =>
      val opensGate  = front.indexOf(s"{{#${Marker.ThreeWayField}}}")
      val closesGate = front.indexOf(s"{{/${Marker.ThreeWayField}}}")
      val context    = front.indexOf(s"{{#${Marker.ContextField}}}")

      assert(context >= 0, s"'$noteType' template '$templateName' does not render the breadcrumb")
      assert(
        context > opensGate && context < closesGate,
        s"'$noteType' template '$templateName' renders the breadcrumb OUTSIDE " +
          s"{{#${Marker.ThreeWayField}}}, so this card would be generated for every note that " +
          "has a breadcrumb, whether or not it wants a third card",
      )

      // THE PROPERTY THAT ACTUALLY MATTERS, asserted as a whole rather than per-element:
      // NOTHING may render outside the gate. Anki generates a card whenever its front is
      // non-empty, so a single stray character out here — a heading, a rule, a breadcrumb that
      // does not depend on any field — generates a third card for every note of this type.
      //
      // Strengthened when the breadcrumb stopped being conditional. It renders `{{Deck}}`, which
      // is never empty, so from that point on "the breadcrumb is outside the gate" and "this
      // card is generated for every note" became the same sentence.
      assert(
        front.trim.startsWith(s"{{#${Marker.ThreeWayField}}}") &&
          front.trim.endsWith(s"{{/${Marker.ThreeWayField}}}"),
        s"'$noteType' template '$templateName' has content outside its " +
          s"{{#${Marker.ThreeWayField}}} gate, which generates a card for every note: $front",
      )
    }
  }

  // -------------------------------------------------- the decoder's strictness ----

  /** A manifest is read with a STRICT decoder: no defaults, and no unknown keys.
    *
    * THE MISSPELLED OPTIONAL KEY IS THE ONE THAT MATTERS. `isColze` at least fails, because
    * `isCloze` is then absent and has no default. `renamdFrom` would decode CLEANLY under
    * circe's default behaviour, with the rename hazard silently disarmed — and the collection
    * would end up with an empty duplicate beside the populated original.
    */
  test("an unknown key in a manifest is refused, not ignored") {
    val text =
      """{ "name": "Obsidian Basic", "isCloze": false, "fields": ["Front", "Back", "Context"],
        |  "styling": "styling.css", "renamdFrom": "Basic",
        |  "templates": [{ "name": "Card 1", "front": "f.html", "back": "b.html" }] }""".stripMargin
    NoteTypeAssets.parseManifest("test", text) match
      case Left(AssetError.Malformed(_, detail)) =>
        assert(detail.contains("renamdFrom"), s"the message does not name the offending key: $detail")
      case other => fail(s"expected a Malformed error naming 'renamdFrom', got: $other")
  }

  test("a manifest missing a mandatory key is refused rather than defaulted") {
    val text =
      """{ "name": "Obsidian Basic", "fields": ["Front"], "styling": "styling.css",
        |  "templates": [{ "name": "Card 1", "front": "f.html", "back": "b.html" }] }""".stripMargin
    assert(
      NoteTypeAssets.parseManifest("test", text).isLeft,
      "isCloze was absent and the manifest decoded anyway — it must never be inferred",
    )
  }

  /** A note type with no templates generates no cards at all. It installs, it looks present, and
    * it produces nothing — this project's signature failure shape, made unrepresentable.
    */
  test("a manifest declaring no templates and no fields is refused") {
    val noTemplates =
      """{ "name": "X", "isCloze": false, "fields": ["Front"], "styling": "s.css", "templates": [] }"""
    val noFields =
      """{ "name": "X", "isCloze": false, "fields": [], "styling": "s.css",
        |  "templates": [{ "name": "Card 1", "front": "f", "back": "b" }] }""".stripMargin
    assert(NoteTypeAssets.parseManifest("test", noTemplates).isLeft, "empty template list accepted")
    assert(NoteTypeAssets.parseManifest("test", noFields).isLeft, "empty field list accepted")
  }

  /** A null resource stream read as an empty string would give a note type whose templates are
    * blank — which Anki accepts, and which then generates nothing.
    */
  test("a file that is not on the classpath is reported, never read as empty") {
    assertEquals(
      NoteTypeAssets.readFile("basic", "templates/there-is-no-such-file.html"),
      Left(AssetError.ResourceMissing("/note-types/basic/templates/there-is-no-such-file.html")),
    )
  }
