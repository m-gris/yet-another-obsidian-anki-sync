package obsidiananki.anki

import obsidiananki.model.Marker

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

  /** Anki's Sort Field defaults to field 1; a breadcrumb there would fill the Browse list with
    * the same repeated prefix. Asserted against the MANIFEST rather than against `Marker`,
    * which `model/Marker.test.scala` already covers — the point here is the installed shape.
    */
  test("every installed note type ends with the Context field") {
    assets.foreach { asset =>
      assertEquals(
        asset.spec.fields.last,
        Marker.ContextField,
        s"'${asset.spec.name}' does not end with the Context field",
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

  /** Everything Anki treats as a field reference in a template, normalised to a bare name.
    *
    * `{{Front}}` plain, `{{#Context}}` / `{{^X}}` / `{{/Context}}` section markers, and
    * `{{cloze:Text}}` / `{{text:X}}` filters — the field is the segment after the LAST colon,
    * because filters chain (`{{text:cloze:Field}}`).
    */
  def referencesIn(template: String): Set[String] =
    """\{\{([^}]*)\}\}""".r
      .findAllMatchIn(withoutHtmlComments(template))
      .map(_.group(1))
      .map(_.dropWhile(c => c == '#' || c == '^' || c == '/'))
      .map(ref => ref.split(':').lastOption.getOrElse(ref))
      .map(_.trim)
      .toSet

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
  val ankiSpecialReferences: Set[String] = Set("FrontSide")

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
          val unknown = referencesIn(text) -- declared -- ankiSpecialReferences
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
        referencesIn(template.front) ++ referencesIn(template.back)
      }.toSet
      val unused = asset.spec.fields.toVector.toSet -- referenced
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
    assertEquals(referencesIn("<!-- {{Context}} -->"), Set.empty[String])
    assertEquals(referencesIn("<!-- {{Context}} -->{{Front}}"), Set("Front"))
    // A multi-line comment, since that is what a disabled block actually looks like.
    assertEquals(referencesIn("<!--\n  <div>{{Context}}</div>\n-->{{Front}}"), Set("Front"))
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
          referencesIn(template.front).contains(Marker.ContextField),
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
