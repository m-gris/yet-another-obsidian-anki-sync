"""Tests for the add-on's decisions. Runs anywhere Python does; needs no Anki.

    python3 -m unittest discover -s addon/obsidian_edit -p '*_test.py'
"""

import dataclasses
import unittest

from core import (
    identity,DRILL_PREFIX, IDENTITY_FIELD, JAVA_HINT, Explain, NotOurs, Open, command,
                  drill_deck_name, drill_search, drill_search_for_id, environment,
                  interpret, is_drill_deck,
                  missing_java, source_tag, verdict_without_identity)


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


class IdentityTest(unittest.TestCase):
    """Reading a card's identity from wherever it currently lives.

    IT MOVED FROM A TAG INTO A FIELD ON 2026-08-28, and a collection is in one of three states
    until every note has been rewritten: tag only, both, or field only. All three must work, or
    pressing `e` stops working somewhere in the middle of a migration nobody triggered.
    """

    def test_the_field_is_used_when_it_has_been_written(self) -> None:
        self.assertEqual(
            identity({"Identity": "src::n1::a/b"}, ["leech"]),
            "src::n1::a/b",
        )

    def test_a_collection_not_yet_re_synced_still_works_from_its_tag(self) -> None:
        """The state Marc's collection was in the day the field arrived: note types declared it,
        no sync had populated it, and every note still carried the tag."""
        self.assertEqual(identity({"Identity": ""}, ["src::n1::a/b"]), "src::n1::a/b")

    def test_a_note_carrying_both_prefers_the_field(self) -> None:
        """They hold the same string, so this cannot matter today. It is asserted so that the
        day they CAN differ, the answer is already decided and written down."""
        self.assertEqual(
            identity({"Identity": "src::field::x"}, ["src::tag::y"]),
            "src::field::x",
        )

    def test_a_field_of_whitespace_is_not_an_identity(self) -> None:
        self.assertEqual(identity({"Identity": "   "}, ["src::n1::a/b"]), "src::n1::a/b")

    def test_a_note_this_tool_never_touched_has_neither(self) -> None:
        self.assertIsNone(identity({"Front": "x"}, ["leech"]))


class VerdictWithoutIdentityTest(unittest.TestCase):
    """The choice between saying nothing and saying something, once no identity has been found.

    THE REPORTED BUG IS THE SECOND HALF OF THIS. Pressing Edit on a card whose note is on one of
    this tool's own note types, with its `Identity` field empty, opened Anki's editor and said
    nothing -- so the person saw a keypress behave as though the add-on were not installed.

    THE FIRST HALF IS WHY THE OBVIOUS FIX IS WRONG. Most cards in a collection were typed into
    Anki and were never in the vault; on those, opening Anki's editor in silence is the correct
    answer. Explaining on every identity-less note was tried and rejected: it would put a message
    on nearly every card in the collection to serve the rare one that is genuinely broken.
    """

    #: Notes on a note type this tool does not own: NO `Identity` KEY AT ALL. The tags are ones a
    #: person or another add-on writes -- plus `orphaned::`, which this tool does write, to cards
    #: it has disowned.
    NOT_OURS = (
        ({}, []),
        ({"Front": "Bonjour", "Back": "Hello"}, []),
        ({"Front": "x"}, ["leech", "marked"]),
        ({"Front": "x"}, ["my::own::hierarchy"]),
        ({"Front": "x"}, ["orphaned::abc::intro"]),
    )

    #: Notes on a type this tool DOES own -- the field is declared, so it is there -- carrying no
    #: identity in it, and no legacy `src::` tag to fall back on either.
    OURS_BUT_EMPTY = (
        ({"Identity": ""}, []),
        ({"Identity": "", "Front": "x", "Back": "y"}, ["leech"]),
        ({"Identity": " "}, []),
        ({"Identity": "   \t "}, ["marked"]),
        ({"Identity": "\n"}, ["orphaned::abc::intro"]),
    )

    def test_a_note_type_this_tool_does_not_own_is_handed_over_in_silence(self) -> None:
        """THE COMMON CASE, AND THE ONE THAT MUST NOT REGRESS INTO NOISE. Whatever tags such a
        note carries, the absence of the field settles it: this is a card the person made in
        Anki, and Anki's editor is where they asked to go."""
        for fields, tags in self.NOT_OURS:
            self.assertEqual(verdict_without_identity(fields, tags), NotOurs(), (fields, tags))

    def test_an_empty_identity_field_is_the_anomaly_worth_explaining(self) -> None:
        """Only a note type this tool created declares the field, so a note that HAS it and has
        nothing in it is one of ours missing something it should have -- which is a fault, and
        the person is the only one who can do anything about it."""
        for fields, tags in self.OURS_BUT_EMPTY:
            self.assertIsInstance(
                verdict_without_identity(fields, tags), Explain, (fields, tags))

    def test_whitespace_is_the_same_situation_as_empty(self) -> None:
        """`identity` strips before deciding, so a field holding a stray space that survived an
        edit is not a different fault from one holding nothing. Two verdicts that differed here
        would mean two messages for one situation."""
        blank = verdict_without_identity({"Identity": ""}, [])
        for whitespace in ("   ", "\t", "\n  \n"):
            self.assertEqual(verdict_without_identity({"Identity": whitespace}, []), blank,
                             repr(whitespace))

    def test_the_explanation_is_a_sentence_someone_can_act_on(self) -> None:
        """Asserted on shape rather than wording, so the sentence stays free to improve: it has
        to name the field, because that is the thing the person can go and look at in Anki's own
        editor, and it must not report the fault in the vocabulary of the code."""
        verdict = verdict_without_identity({"Identity": ""}, [])
        assert isinstance(verdict, Explain)
        self.assertTrue(verdict.message.strip(), "an empty message is a keypress that did nothing")
        self.assertIn(IDENTITY_FIELD, verdict.message)
        self.assertTrue(verdict.message.rstrip().endswith("."), verdict.message)
        self.assertGreaterEqual(len(verdict.message.split()), 5, verdict.message)
        for jargon in ("none", "null", "traceback", "exception"):
            self.assertNotIn(jargon, verdict.message.lower(), verdict.message)

    def test_the_explanation_recommends_no_repair_because_there_is_none_to_recommend(self) -> None:
        """THE ADVICE IS EXCLUDED, NOT MERELY ABSENT, and the shape test above cannot tell those
        two apart -- a confidently false sentence satisfies every assertion it makes. This test
        is where the ruling lives.

        The sentence used to end "Re-syncing the vault should fill it in". That is false, and it
        is dangerous. The search that enumerates every note this tool owns is
        `"Identity:src::*" or "tag:src::*"`, and a note whose field is empty and which carries no
        legacy tag matches NEITHER half -- so a sync cannot see the note, and therefore cannot
        fill anything in. What a sync CAN do with a note it cannot see is read it as a note that
        does not exist and create it over again, leaving a duplicate while the broken note keeps
        the review history. A repair that does not exist is worse advice than no advice.
        """
        verdict = verdict_without_identity({IDENTITY_FIELD: ""}, [])
        assert isinstance(verdict, Explain)
        lowered = verdict.message.lower()
        # One substring covers "sync", "re-sync", "resync" and every inflection of the three.
        self.assertNotIn("sync", lowered, verdict.message)
        # Markers of instruction rather than banned vocabulary: an observation reaches for none
        # of these, and a repair the person is being sent off to perform reaches for one.
        for advice in ("should", "try", "fix"):
            self.assertNotIn(advice, lowered, verdict.message)

    def test_a_tag_that_merely_looks_similar_changes_nothing(self) -> None:
        """`source::` and `srcfoo::` belong to somebody else, `SRCX::` only starts like ours, and
        `orphaned::` is this tool's own mark on a card it has DISOWNED. None of them is an
        identity, so none of them moves a note from one arm of this decision to the other: the
        field is the whole of it."""
        lookalikes = ["source::abc", "srcfoo::abc", "orphaned::abc::intro", "SRCX::abc"]
        self.assertEqual(verdict_without_identity({"Front": "x"}, lookalikes), NotOurs())
        self.assertIsInstance(verdict_without_identity({"Identity": ""}, lookalikes), Explain)

    def test_silence_carries_nothing_to_say(self) -> None:
        """THE REJECTED DESIGN, WRITTEN DOWN. `NotOurs` gains no message: a sentence attached to
        every note type this tool does not own is noise on nearly every card in the collection.
        The silence is the answer here, not a blank waiting to be filled in."""
        self.assertEqual(dataclasses.fields(NotOurs), ())
        self.assertEqual(NotOurs(), NotOurs())

    def test_every_case_here_is_one_identity_really_cannot_answer(self) -> None:
        """This decision lives entirely in the shadow of `identity` returning None -- the shell
        reaches it nowhere else. A fixture carrying a readable identity would be pinning a branch
        that never runs, and would pin it green."""
        for fields, tags in self.NOT_OURS + self.OURS_BUT_EMPTY:
            self.assertIsNone(identity(fields, tags), (fields, tags))

    def test_a_note_that_does_have_an_identity_is_refused_rather_than_answered(self) -> None:
        """THE OTHER HALF OF THE TEST ABOVE, ENFORCED INSTEAD OF ASSUMED. The two arms are told
        apart by whether the field is THERE, never by what is in it, so a note with a readable
        identity would come back answered and wrong -- `NotOurs` for a note that is plainly ours,
        or `Explain` about an empty field that is full. There is no verdict to give, so the
        caller is told what it broke rather than handed one of those."""
        for fields, tags in (
            ({IDENTITY_FIELD: "src::n1::a/b"}, []),
            ({IDENTITY_FIELD: "src::n1::a/b"}, ["src::n1::a/b"]),
            ({"Front": "x"}, ["src::n1::a/b"]),
        ):
            with self.assertRaises(ValueError) as caught:
                verdict_without_identity(fields, tags)
            self.assertIn(IDENTITY_FIELD, str(caught.exception), (fields, tags))


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


class DrillDeckTest(unittest.TestCase):
    def test_the_deck_is_named_after_the_note(self) -> None:
        self.assertEqual(drill_deck_name("Function Space"), DRILL_PREFIX + "Function Space")

    def test_a_nested_name_is_flattened(self) -> None:
        """`A::B` means B inside A in Anki, so a title containing it would leave an empty parent
        deck behind after the child is swept -- the residue this is meant to avoid."""
        self.assertNotIn("::", drill_deck_name("Maths::Sets"))

    def test_quotes_are_removed(self) -> None:
        """A deck name reaches Anki's search syntax, where a quote terminates a term. A deck
        nobody can search for is a deck nobody can empty."""
        self.assertNotIn('"', drill_deck_name('The "CAP" theorem'))

    def test_a_nameless_note_still_gets_a_deck(self) -> None:
        for title in ("", "   ", '""'):
            self.assertTrue(drill_deck_name(title).startswith(DRILL_PREFIX))
            self.assertNotEqual(drill_deck_name(title), DRILL_PREFIX)


class SweepTest(unittest.TestCase):
    def test_our_decks_are_recognised(self) -> None:
        self.assertTrue(is_drill_deck(drill_deck_name("anything")))

    def test_a_real_deck_is_never_claimed(self) -> None:
        """The prefix is the only thing between a tidy-up and somebody's collection."""
        for name in ("Obsidian", "Obsidian::System-Design", "Default", "French"):
            self.assertFalse(is_drill_deck(name), name)

    def test_the_prefix_must_be_at_the_start(self) -> None:
        """A deck the person named 'My Drill — French' is theirs."""
        self.assertFalse(is_drill_deck("My " + DRILL_PREFIX + "French"))


class DrillSearchTest(unittest.TestCase):
    def test_the_search_is_anchored_to_the_note(self) -> None:
        """BOTH HOMES ARE GATHERED, and this is asserted separately from the tag half because
        the tag half alone would still satisfy every assertion written before 2026-08-28. A
        drill that gathered only tag-carrying notes after a collection was re-synced would
        return an EMPTY deck, which reads as "nothing to drill" rather than as a fault."""
        both = drill_search_for_id("abc123")
        self.assertIn("Identity:src::abc123::*", both, "the field half is missing")
        self.assertIn("tag:src::abc123::*", both, "the tag half is missing")
        self.assertIn(" or ", both, "the two homes are not combined as alternatives")

    def test_a_full_tag_is_reduced_to_its_id(self) -> None:
        s = drill_search("src::abc123::cap%20theorem/definition")
        self.assertIn("tag:src::abc123::*", s)

    def test_suspended_cards_are_excluded_out_loud(self) -> None:
        """Filtered decks skip suspended cards anyway. Saying so means the search explains
        itself when it is read back in Anki's own filtered-deck dialog."""
        self.assertIn("-is:suspended", drill_search("src::abc123::x"))

    def test_a_tag_with_no_path_yields_no_id_rather_than_a_wild_search(self) -> None:
        """A malformed tag must not become `tag:src::*`, which would gather the whole
        collection into a drill deck."""
        self.assertNotIn("tag:src::*", drill_search("src::"))

    def test_an_empty_id_matches_nothing_at_all(self) -> None:
        """The dangerous failure here is not an error, it is a search that succeeds too well."""
        for empty in ("", "   "):
            s = drill_search_for_id(empty)
            self.assertNotIn("src::", s)
            self.assertEqual(s, "nid:0")

    def test_the_two_entry_points_agree(self) -> None:
        self.assertEqual(drill_search("src::abc123::x/y"), drill_search_for_id("abc123"))


if __name__ == "__main__":
    unittest.main()
