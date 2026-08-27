"""Tests for the add-on's decisions. Runs anywhere Python does; needs no Anki.

    python3 -m unittest discover -s addon/obsidian_edit -p '*_test.py'
"""

import unittest

from core import (JAVA_HINT, Explain, NotOurs, Open, command, environment,
                  interpret, missing_java, source_tag)


class SourceTagTest(unittest.TestCase):
    def test_a_note_this_tool_never_touched_is_not_ours(self) -> None:
        self.assertIsNone(source_tag(["leech", "marked", "my::own::hierarchy"]))

    def test_no_tags_at_all_is_not_ours(self) -> None:
        self.assertIsNone(source_tag([]))

    def test_the_identity_tag_is_found_among_others(self) -> None:
        tags = ["leech", "sha::4c9c3137cf1b7ee2", "src::abc::intro", "marked"]
        self.assertEqual(source_tag(tags), "src::abc::intro")

    def test_anki_folds_tag_case_so_the_match_must_too(self) -> None:
        """`SRC::x` and `src::x` are ONE tag in a collection. Matching case-sensitively here
        would call a tag foreign that Anki cannot tell apart from ours."""
        self.assertEqual(source_tag(["SRC::abc::intro"]), "SRC::abc::intro")

    def test_a_tag_that_merely_starts_similarly_is_not_ours(self) -> None:
        self.assertIsNone(source_tag(["source::abc", "srcfoo::abc"]))

    def test_an_orphan_flag_alone_is_not_an_identity_tag(self) -> None:
        self.assertIsNone(source_tag(["orphaned::abc::intro"]))

    def test_a_flagged_card_still_has_its_identity_tag(self) -> None:
        """The sync flags a card by ADDING `orphaned::`; the `src::` tag stays beside it."""
        tags = ["orphaned::abc::intro", "src::abc::intro"]
        self.assertEqual(source_tag(tags), "src::abc::intro")


class CommandTest(unittest.TestCase):
    def test_arguments_are_a_vector_so_no_shell_can_see_them(self) -> None:
        """A vault path holds spaces and emoji and an identity tag holds `%` and `/`. Every one
        of those is an invitation once a shell is involved."""
        argv = command("/bin/oas", "/Users/m/📖 my vault 📖", "src::a::b%20c/d", None)
        self.assertIn("/Users/m/📖 my vault 📖", argv)
        self.assertEqual(argv[-1], "src::a::b%20c/d")
        self.assertNotIn(" ", argv[0])

    def test_the_vault_name_is_omitted_when_not_configured(self) -> None:
        self.assertNotIn("--vault-name", command("oas", "/v", "src::a::b", None))
        self.assertNotIn("--vault-name", command("oas", "/v", "src::a::b", ""))

    def test_the_vault_name_is_passed_when_configured(self) -> None:
        argv = command("oas", "/v", "src::a::b", "Study")
        self.assertEqual(argv[argv.index("--vault-name") + 1], "Study")

    def test_the_tag_is_always_last_so_a_flag_cannot_swallow_it(self) -> None:
        for name in (None, "Study"):
            self.assertEqual(command("oas", "/v", "src::a::b", name)[-1], "src::a::b")


class InterpretTest(unittest.TestCase):
    def test_a_uri_on_stdout_is_something_to_open(self) -> None:
        v = interpret("obsidian://adv-uri?vault=V&uid=n1&line=9\n", "")
        self.assertEqual(v, Open(uri="obsidian://adv-uri?vault=V&uid=n1&line=9", caveat=None))

    def test_an_explanation_is_carried_even_when_a_uri_came_back(self) -> None:
        """`locate` writes to stderr for a card it opened the note for but could not place --
        a reworded heading. That is the moment the person can still act on it."""
        v = interpret("obsidian://x\n", "The note is there; the card is not: intro\n")
        self.assertIsInstance(v, Open)
        assert isinstance(v, Open)
        self.assertEqual(v.caveat, "The note is there; the card is not: intro")

    def test_no_uri_means_explain_rather_than_open(self) -> None:
        v = interpret("", "No note in this vault carries the id 'n1'.\n")
        self.assertEqual(v, Explain(message="No note in this vault carries the id 'n1'."))

    def test_silence_on_both_channels_still_produces_a_message(self) -> None:
        """A verdict that neither opens nor explains would be a keypress that does nothing,
        which is the one shape these types exist to rule out."""
        v = interpret("", "")
        self.assertIsInstance(v, Explain)
        assert isinstance(v, Explain)
        self.assertTrue(v.message)

    def test_whitespace_on_stdout_is_not_a_uri(self) -> None:
        self.assertIsInstance(interpret("   \n", "something went wrong"), Explain)


class TotalityTest(unittest.TestCase):
    def test_every_verdict_either_opens_or_explains_or_delegates(self) -> None:
        """Read as a checklist rather than a test: these three are the whole space, and the
        shell must handle each. There is no fourth, and in particular no 'do nothing'."""
        for v in (NotOurs(), Open(uri="obsidian://x", caveat=None), Explain(message="why")):
            self.assertTrue(isinstance(v, (NotOurs, Open, Explain)))


class EnvironmentTest(unittest.TestCase):
    def test_an_unset_java_home_leaves_the_environment_alone(self) -> None:
        base = {"PATH": "/usr/bin:/bin"}
        self.assertEqual(environment(base, None), base)
        self.assertEqual(environment(base, ""), base)

    def test_the_jvm_is_prepended_so_nothing_else_stops_resolving(self) -> None:
        env = environment({"PATH": "/usr/bin:/bin"}, "/opt/jdk")
        self.assertEqual(env["PATH"], "/opt/jdk/bin:/usr/bin:/bin")
        self.assertEqual(env["JAVA_HOME"], "/opt/jdk")

    def test_a_trailing_slash_does_not_double_up(self) -> None:
        self.assertTrue(environment({}, "/opt/jdk/")["PATH"].startswith("/opt/jdk/bin"))

    def test_an_absent_path_is_not_an_error(self) -> None:
        self.assertEqual(environment({}, "/opt/jdk")["PATH"], "/opt/jdk/bin")

    def test_the_caller_s_environment_is_not_mutated(self) -> None:
        """A shell that edited os.environ in place would change it for Anki itself."""
        base = {"PATH": "/usr/bin"}
        environment(base, "/opt/jdk")
        self.assertEqual(base, {"PATH": "/usr/bin"})


class MissingJavaTest(unittest.TestCase):
    def test_the_launcher_s_own_words_are_recognised(self) -> None:
        self.assertTrue(missing_java(
            "The operation couldn\u2019t be completed. Unable to locate a Java Runtime."))

    def test_an_unrelated_failure_is_not_mistaken_for_it(self) -> None:
        self.assertFalse(missing_java("No note in this vault carries the id 'n1'."))

    def test_the_hint_names_the_setting_that_fixes_it(self) -> None:
        """A hint that does not say what to change is decoration."""
        self.assertIn("java_home", JAVA_HINT)


if __name__ == "__main__":
    unittest.main()
