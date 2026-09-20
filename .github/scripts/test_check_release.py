#!/usr/bin/env python3
"""The rules check-release.py holds, exercised without a repository to release.

Run with `python3 -m unittest discover -s .github/scripts`, which is what CI does.
"""

import contextlib
import subprocess
import tempfile
import unittest

import importlib.util
from pathlib import Path

specification = importlib.util.spec_from_file_location(
    "check_release", Path(__file__).resolve().parent / "check-release.py"
)
check_release = importlib.util.module_from_spec(specification)
specification.loader.exec_module(check_release)

check_version = check_release.check_version
check_changelog = check_release.check_changelog
precedence = check_release.precedence
next_worked_on = check_release.next_worked_on
check_ancestry = check_release.check_ancestry
channel_of = check_release.channel_of
unprotected = check_release.unprotected
read_properties = check_release.properties
with_version = check_release.with_version
repository_root = check_release.repository_root


class Precedence(unittest.TestCase):
    def test_a_release_outranks_its_own_pre_releases(self):
        self.assertGreater(precedence("0.2.0"), precedence("0.2.0-rc.1"))

    def test_a_numeric_identifier_is_compared_as_a_number(self):
        self.assertLess(precedence("0.2.0-beta.9"), precedence("0.2.0-beta.10"))

    def test_more_identifiers_outrank_fewer_of_the_same_prefix(self):
        self.assertLess(precedence("0.2.0-rc.1"), precedence("0.2.0-rc.1.1"))

    def test_the_numbers_come_before_the_suffix(self):
        self.assertLess(precedence("0.9.9"), precedence("1.0.0-rc.1"))


class FirstRelease(unittest.TestCase):
    def test_anything_valid_may_be_the_first(self):
        self.assertEqual(check_version("0.1.0", []), [])
        self.assertEqual(check_version("7.3.1", []), [])

    def test_a_version_that_is_not_one_is_refused(self):
        self.assertTrue(check_version("0.2", []))
        self.assertTrue(check_version("v0.2.0", []))
        self.assertTrue(check_version("0.2.0.1", []))


class TheStep(unittest.TestCase):
    def test_the_three_successors_are_allowed(self):
        for candidate in ("1.2.4", "1.3.0", "2.0.0"):
            with self.subTest(candidate=candidate):
                self.assertEqual(check_version(candidate, ["1.2.3"]), [])

    def test_a_skipped_number_is_refused(self):
        self.assertTrue(check_version("1.4.0", ["1.2.3"]))

    def test_a_part_that_is_not_zeroed_is_refused(self):
        self.assertTrue(check_version("1.3.1", ["1.2.3"]))

    def test_the_version_already_released_is_refused(self):
        self.assertTrue(check_version("1.2.3", ["1.2.3"]))

    def test_going_backwards_is_refused(self):
        self.assertTrue(check_version("1.2.2", ["1.2.3"]))


class ThePreReleaseTrain(unittest.TestCase):
    def test_a_pre_release_may_open_a_permitted_core(self):
        self.assertEqual(check_version("0.2.0-rc.1", ["0.1.0"]), [])

    def test_the_train_may_go_on_without_advancing_the_core(self):
        self.assertEqual(check_version("0.2.0-rc.2", ["0.1.0", "0.2.0-rc.1"]), [])

    def test_the_train_may_end_in_the_release_it_was_for(self):
        self.assertEqual(check_version("0.2.0", ["0.1.0", "0.2.0-rc.1", "0.2.0-rc.2"]), [])

    def test_going_backwards_inside_the_train_is_refused(self):
        self.assertTrue(check_version("0.2.0-rc.1", ["0.1.0", "0.2.0-rc.2"]))

    def test_a_stable_hotfix_may_go_out_while_a_train_is_open(self):
        """0.1.1 goes to everyone and only has to outrank the last release everyone was offered, 0.1.0. The
        subscribers who see 0.2.0-rc.1 are not offered a downgrade by it either: 0.1.1 sorts below the rc."""
        self.assertEqual(check_version("0.1.1", ["0.1.0", "0.2.0-rc.1"]), [])

    def test_a_hotfix_does_not_close_the_train(self):
        tags = ["0.1.0", "0.2.0-rc.1", "0.1.1"]
        self.assertEqual(check_version("0.2.0-rc.2", tags), [])
        self.assertEqual(check_version("0.2.0", tags), [])

    def test_a_pre_release_has_to_outrank_every_tag(self):
        """Subscribers to a channel see everything, so a pre-release below the highest tag is one they are never
        offered - which is what the base invariant refuses."""
        problems = check_version("0.1.2-rc.1", ["0.1.0", "0.1.1", "0.2.0-rc.1"])
        self.assertTrue(problems)
        self.assertIn("0.2.0-rc.1", problems[0])

    def test_a_hotfix_cannot_be_tried_on_a_channel_while_a_train_is_open(self):
        """The price of the rule above: 0.1.1 may go out, but 0.1.1-rc.1 sorts below 0.2.0-rc.1, so the hotfix
        cannot be offered to a channel first. Named here so that nobody finds it out from a red run."""
        problems = check_version("0.1.1-rc.1", ["0.1.0", "0.2.0-rc.1"])
        self.assertTrue(problems)
        self.assertIn("0.2.0-rc.1", problems[0])

    def test_a_final_release_still_has_to_outrank_the_last_final_one(self):
        problems = check_version("0.1.1", ["0.1.0", "0.2.0-rc.1", "0.2.0"])
        self.assertTrue(problems)
        self.assertIn("0.2.0", problems[0])

    def test_an_open_train_cannot_be_abandoned_for_a_higher_core(self):
        """The step is measured against the last final release, which an open train does not advance: the way
        past 0.2.0-rc.1 is 0.2.0, not 0.3.0."""
        problems = check_version("0.3.0", ["0.1.0", "0.2.0-rc.1"])
        self.assertTrue(problems)
        self.assertIn("does not follow 0.1.0", problems[0])


class WhatFollowsARelease(unittest.TestCase):
    def test_a_final_release_is_followed_by_a_patch(self):
        self.assertEqual(next_worked_on("0.1.1"), "0.1.2-SNAPSHOT")
        self.assertEqual(next_worked_on("1.9.0"), "1.9.1-SNAPSHOT")

    def test_a_pre_release_goes_on_heading_for_the_release_it_was_for(self):
        """0.2.0-rc.1 was a step towards 0.2.0, so work carries on towards it. Counting the patch up here would
        skip the very release the train was running to, and the step check would refuse it afterwards."""
        self.assertEqual(next_worked_on("0.2.0-rc.1"), "0.2.0-SNAPSHOT")
        self.assertEqual(next_worked_on("0.2.0-beta.1"), "0.2.0-SNAPSHOT")

    def test_what_follows_may_itself_be_released(self):
        """The two rules have to agree: what is worked on next must be something the step check accepts."""
        for released, tags in (("0.1.1", ["0.1.0", "0.1.1"]), ("0.2.0-rc.1", ["0.1.0", "0.2.0-rc.1"])):
            with self.subTest(released=released):
                following = next_worked_on(released).removesuffix("-SNAPSHOT")
                self.assertEqual(check_version(following, tags), [])


class TheChannel(unittest.TestCase):
    """What the pre-release suffix is for. The rule is spelled a second time, in Kotlin, in build.gradle.kts, and
    the two have to say the same thing: the build uploads to the channel it names, and the workflows ask the
    Marketplace for the one named here."""

    def test_a_final_release_goes_to_everyone(self):
        self.assertEqual(channel_of("0.3.0"), "default")

    def test_a_pre_release_goes_to_the_channel_its_suffix_names(self):
        self.assertEqual(channel_of("0.3.0-beta.1"), "beta")
        self.assertEqual(channel_of("1.0.0-rc.2"), "rc")
        self.assertEqual(channel_of("1.0.0-eap"), "eap")

    def test_the_version_being_worked_on_is_not_a_channel_anyone_subscribes_to(self):
        """publishPlugin refuses a -SNAPSHOT before this matters, but the rule still has to answer the same as the
        Kotlin one does: substringAfter('-') then substringBefore('.') gives SNAPSHOT too."""
        self.assertEqual(channel_of("0.2.1-SNAPSHOT"), "SNAPSHOT")


class ReleasedSections(unittest.TestCase):
    AT_TAG = """# Changelog

## [Unreleased]

## [0.1.0] - 2026-09-15

### Added

- A
- B

[0.1.0]: https://example.invalid/commits/v0.1.0
"""

    def test_an_untouched_section_passes(self):
        self.assertEqual(check_changelog(self.AT_TAG, {"0.1.0": self.AT_TAG}), [])

    def test_work_added_under_unreleased_is_free(self):
        head = self.AT_TAG.replace("## [Unreleased]\n", "## [Unreleased]\n\n### Added\n\n- C\n")
        self.assertEqual(check_changelog(head, {"0.1.0": self.AT_TAG}), [])

    def test_the_link_definitions_are_not_part_of_a_section(self):
        head = self.AT_TAG.replace(
            "[0.1.0]: https://example.invalid/commits/v0.1.0",
            "[Unreleased]: https://example.invalid/compare/v0.1.0...HEAD\n"
            "[0.1.0]: https://example.invalid/commits/v0.1.0",
        )
        self.assertEqual(check_changelog(head, {"0.1.0": self.AT_TAG}), [])

    def test_an_entry_merged_into_a_released_section_is_caught(self):
        """The hazard the check exists for: a three-way merge puts an entry that was added to [Unreleased] after
        the branch point into the released section instead, cleanly and without a conflict."""
        head = self.AT_TAG.replace("- B\n", "- B\n- C\n")
        self.assertTrue(check_changelog(head, {"0.1.0": self.AT_TAG}))

    def test_a_reworded_released_entry_is_caught(self):
        head = self.AT_TAG.replace("- A\n", "- A, said better\n")
        self.assertTrue(check_changelog(head, {"0.1.0": self.AT_TAG}))

    def test_a_section_that_has_gone_is_caught(self):
        head = "# Changelog\n\n## [Unreleased]\n"
        self.assertTrue(check_changelog(head, {"0.1.0": self.AT_TAG}))

    def test_a_tag_older_than_the_section_is_passed_over(self):
        before = "# Changelog\n\n## [Unreleased]\n"
        self.assertEqual(check_changelog(self.AT_TAG, {"0.1.0": before}), [])

    def test_what_was_passed_over_is_named_rather_than_counted_as_protected(self):
        """The report has to be honest about a section it could not compare: with every tag older than its
        section, "all N sections still read as released" would be true of nothing."""
        before = "# Changelog\n\n## [Unreleased]\n"
        later = self.AT_TAG.replace("[0.1.0]", "[0.2.0]")
        self.assertEqual(unprotected({"0.1.0": before, "0.2.0": later}), ["0.1.0"])
        self.assertEqual(unprotected({"0.1.0": self.AT_TAG}), [])


    def test_a_section_missing_while_its_release_is_off_this_history_is_not_called_deleted(self):
        """Between a release being published and its branch reaching the default branch, the section exists
        only where the tag does. `ancestry` already fails for it; this one saying the section is gone as well
        reads as a second, separate accusation about somebody having deleted text."""
        problems = check_changelog("# Changelog\n\n## [Unreleased]\n", {"0.1.0": self.AT_TAG}, {"0.1.0"})
        self.assertEqual(len(problems), 1)
        self.assertNotIn("is gone", problems[0])
        self.assertIn("has yet to reach here", problems[0])

    def test_a_section_missing_while_its_release_is_on_this_history_is_a_deletion(self):
        problems = check_changelog("# Changelog\n\n## [Unreleased]\n", {"0.1.0": self.AT_TAG}, set())
        self.assertEqual(len(problems), 1)
        self.assertIn("is gone", problems[0])


class ReachableTags(unittest.TestCase):
    def test_tags_that_are_still_on_the_history_pass(self):
        self.assertEqual(check_ancestry({"0.1.0": True, "0.2.0": True}, "v"), [])

    def test_a_tag_a_rewrite_has_orphaned_is_caught(self):
        """What a squash merge, a rebase merge, GitHub's `Update with rebase` or a force-push all do to the
        release commit. The check asks after the property rather than after the cause, so it holds for a way of
        rewriting a branch that nobody has thought of yet."""
        problems = check_ancestry({"0.1.0": True, "0.2.0": False}, "v")
        self.assertEqual(len(problems), 1)
        self.assertIn("v0.2.0", problems[0])

    def test_an_orphan_is_named_the_way_this_repository_tags(self):
        """Both spellings are in use across the projects this tooling is shared with, and a report that names a
        tag nobody can look up is a report that sends its reader to the wrong place."""
        bare = check_ancestry({"0.2.0": False}, "")
        self.assertIn("0.2.0 is released", bare[0])
        self.assertNotIn("v0.2.0", bare[0])

    def test_every_orphan_is_reported_in_release_order(self):
        problems = check_ancestry({"0.2.0": False, "0.1.0": False, "0.1.1": True}, "v")
        self.assertEqual(len(problems), 2)
        self.assertIn("v0.1.0", problems[0])
        self.assertIn("v0.2.0", problems[1])

    def test_a_repository_with_nothing_released_passes(self):
        self.assertEqual(check_ancestry({}, "v"), [])

    def test_a_branch_that_still_holds_the_tag_is_named_with_both_readings(self):
        """The state every release passes through between being published and reaching the default branch -
        and, indistinguishably from the outside, what a squash or a rebase merge leaves behind, since both
        replay the release commit and leave the branch that holds the original standing. Failing is right
        either way; naming only the innocent reading sends the reader away from damage that is there."""
        problems = check_ancestry({"0.2.0": False}, "v", {"0.2.0": "origin/release/0.2.0"})
        self.assertEqual(len(problems), 1)
        self.assertIn("origin/release/0.2.0", problems[0])
        self.assertIn("carried back", problems[0])
        self.assertIn("rebase", problems[0])

    def test_an_orphan_no_branch_holds_is_still_blamed_on_a_rewrite(self):
        """Told apart by whether any branch holds the tag, so a tag that a rebase merge left behind while its
        branch was deleted - the shape that is easiest to mistake for the other - still reads as a rewrite."""
        problems = check_ancestry({"0.2.0": False}, "v", {"0.2.0": ""})
        self.assertEqual(len(problems), 1)
        self.assertIn("rewrite", problems[0])


class RewritingTheVersion(unittest.TestCase):
    """What a release does to gradle.properties before it builds. Here rather than in a pattern in a workflow,
    because a reader and a writer that have to agree about how the line is written can drift apart in silence:
    the reading goes on working and only the writing stops, which is the half nothing notices."""

    TIGHT = "group=cz.loplex\nversion=0.1.1-SNAPSHOT\ntagPrefix=v\n"
    SPACED = "group = cz.loplex\nversion = 0.2.1-SNAPSHOT\ntagPrefix =\n"

    def test_a_tight_separator_stays_tight(self):
        self.assertIn("version=0.1.1\n", with_version(self.TIGHT, "0.1.1"))

    def test_a_spaced_separator_stays_spaced(self):
        self.assertIn("version = 0.2.1\n", with_version(self.SPACED, "0.2.1"))

    def test_nothing_but_the_version_line_moves(self):
        written = with_version(self.SPACED, "0.2.1")
        self.assertIn("group = cz.loplex\n", written)
        self.assertIn("tagPrefix =\n", written)
        self.assertEqual(len(written.splitlines()), len(self.SPACED.splitlines()))

    def test_a_key_that_merely_ends_in_version_is_left_alone(self):
        text = "pluginVersion = 9.9.9\nversion = 0.1.0\n"
        written = with_version(text, "0.2.0")
        self.assertIn("pluginVersion = 9.9.9\n", written)
        self.assertIn("version = 0.2.0\n", written)

    def test_the_version_is_written_as_data(self):
        """`&` and a backreference are characters in a version, not instructions. They would not be under sed
        or under a regular expression's own substitution, which is half of why this is neither."""
        self.assertIn("version = 1.0.0-a&b\\1\n", with_version(self.SPACED, "1.0.0-a&b\\1"))

    def test_a_file_naming_no_version_is_refused_rather_than_left_as_it_was(self):
        with self.assertRaises(SystemExit):
            with_version("group = cz.loplex\n", "0.2.1")


class WhichTagsCountAsReleases(unittest.TestCase):
    """A repository carries tags that are not releases - the backups a history rewrite leaves behind - and it
    carries its releases under whichever spelling it tags with. The prefix has to sort both out, and in both
    directions: a project tagging bare versions must not count `v0.1.0`, and one tagging `v*` must not count
    `0.1.0`. Getting that wrong is quiet, because what it produces is an empty list and a check that passes."""

    PLANTED = [
        "v0.1.0", "v1.2.3-rc.1", "v1.2", "version-1.0",
        "0.1.0", "0.9.9",
        "backup/v9.9.9", "backup/1.0.0", "backup/pre-squash-20260904",
    ]

    def tags_under(self, prefix):
        repository = Path(self.enterContext(tempfile.TemporaryDirectory())).resolve()
        subprocess.run(["git", "init", "-q"], cwd=repository, check=True)
        subprocess.run(["git", "-c", "user.name=t", "-c", "user.email=t@t",
                        "commit", "-q", "--allow-empty", "-m", "init"], cwd=repository, check=True)
        for tag in self.PLANTED:
            subprocess.run(["git", "tag", tag], cwd=repository, check=True)
        (repository / "gradle.properties").write_text(
            f"version = 9.9.9-SNAPSHOT\ntagPrefix = {prefix}\n", encoding="utf-8")

        original = check_release.REPO
        check_release.REPO = repository
        self.addCleanup(setattr, check_release, "REPO", original)
        return sorted(check_release.tags())

    def test_a_v_prefix_takes_the_v_tags_and_hands_back_versions(self):
        self.assertEqual(self.tags_under("v"), ["0.1.0", "1.2.3-rc.1"])

    def test_no_prefix_takes_the_bare_tags_and_leaves_the_v_ones(self):
        self.assertEqual(self.tags_under(""), ["0.1.0", "0.9.9"])

    def test_what_is_not_a_version_is_not_a_release_under_either(self):
        """`v1.2` has two numbers and `version-1.0` merely starts with the letter."""
        for prefix in ("v", ""):
            with self.subTest(prefix=prefix):
                found = self.tags_under(prefix)
                self.assertNotIn("1.2", found)
                self.assertNotIn("ersion-1.0", found)

    def test_a_backup_never_counts_even_when_it_looks_like_a_version(self):
        for prefix in ("v", ""):
            with self.subTest(prefix=prefix):
                found = self.tags_under(prefix)
                self.assertNotIn("9.9.9", found)
                self.assertNotIn("1.0.0", found)


class WhichRepositoryIsBeingChecked(unittest.TestCase):
    """Not the one this file lives in. Shared as a composite action, the script is checked out beside the
    action, and a root taken from its own path would name that checkout instead - which has a
    gradle.properties and a CHANGELOG.md of its own, so the mistake would not announce itself."""

    def test_the_root_is_the_repository_the_run_is_in(self):
        elsewhere = Path(self.enterContext(tempfile.TemporaryDirectory())).resolve()
        subprocess.run(["git", "init", "-q"], cwd=elsewhere, check=True)
        self.enterContext(contextlib.chdir(elsewhere))
        self.assertEqual(repository_root(), elsewhere)

    def test_running_outside_a_repository_is_refused(self):
        nowhere = Path(self.enterContext(tempfile.TemporaryDirectory()))
        self.enterContext(contextlib.chdir(nowhere))
        with self.assertRaises(SystemExit):
            repository_root()


class TheDeclarationsGradleHolds(unittest.TestCase):
    """gradle.properties is where a release reads the version it is asked to be and the name its tag will
    carry. Gradle accepts spaces around the `=` and projects use both spellings, so a reader that knows only
    one of them reports a file that declares nothing when it declares both."""

    def properties_of(self, text):
        written = Path(self.enterContext(tempfile.TemporaryDirectory()))
        (written / "gradle.properties").write_text(text, encoding="utf-8")
        original = check_release.REPO
        check_release.REPO = written
        self.addCleanup(setattr, check_release, "REPO", original)
        return read_properties()

    def test_a_declaration_written_tight_is_read(self):
        self.assertEqual(self.properties_of("version=0.2.1-SNAPSHOT\n")["version"], "0.2.1-SNAPSHOT")

    def test_a_declaration_written_with_spaces_is_read(self):
        self.assertEqual(self.properties_of("version = 0.2.1-SNAPSHOT\n")["version"], "0.2.1-SNAPSHOT")

    def test_a_prefix_declared_empty_is_not_a_prefix_left_out(self):
        """The distinction the whole parametrisation rests on: a project that tags bare versions says so, and
        is not to be confused with one that forgot to say anything."""
        self.assertEqual(self.properties_of("tagPrefix =\n").get("tagPrefix"), "")
        self.assertIsNone(self.properties_of("version = 1.0.0\n").get("tagPrefix"))

    def test_comments_and_blank_lines_declare_nothing(self):
        found = self.properties_of("# version=9.9.9\n\n  \nversion = 1.0.0\n")
        self.assertEqual(found, {"version": "1.0.0"})


if __name__ == "__main__":
    unittest.main()
