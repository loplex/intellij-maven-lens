#!/usr/bin/env python3
"""The rules check-readme.py holds, exercised without a README to check.

Run with `python3 -m unittest discover -s tools`, which is what CI does.
"""

import unittest

import importlib.util
from pathlib import Path

specification = importlib.util.spec_from_file_location(
    "check_readme", Path(__file__).resolve().parent / "check-readme.py"
)
check_readme = importlib.util.module_from_spec(specification)
specification.loader.exec_module(check_readme)

marketplace_references = check_readme.marketplace_references
marketplace_problems = check_readme.marketplace_problems


AGREEING = """
[![Version](https://img.shields.io/jetbrains/plugin/v/34153.svg)](https://plugins.jetbrains.com/plugin/34153-maven-lens)
[![Downloads](https://img.shields.io/jetbrains/plugin/d/34153.svg)](https://plugins.jetbrains.com/plugin/34153-maven-lens)

Open the [plugin page](https://plugins.jetbrains.com/plugin/34153-maven-lens) or its
[versions page](https://plugins.jetbrains.com/plugin/34153-maven-lens/versions).
"""


class WhatCountsAsAMarketplaceReference(unittest.TestCase):
    def test_a_badge_and_a_link_are_both_found(self):
        found = marketplace_references(
            "https://img.shields.io/jetbrains/plugin/v/34153.svg and https://plugins.jetbrains.com/plugin/34153-maven-lens"
        )
        self.assertEqual([(identifier, slug) for identifier, slug, _ in found], [("34153", None), ("34153", "maven-lens")])

    def test_the_metric_segment_of_a_badge_is_not_enumerated(self):
        """`v` and `d` are the two in use, but the pattern must not be the list of them."""
        for metric in ("v", "d", "stars", "something-new"):
            with self.subTest(metric=metric):
                found = marketplace_references(f"https://img.shields.io/jetbrains/plugin/{metric}/34153.svg")
                self.assertEqual([identifier for identifier, _, _ in found], ["34153"])

    def test_a_bare_id_with_no_slug_is_found(self):
        found = marketplace_references("https://plugins.jetbrains.com/plugin/34153")
        self.assertEqual([(identifier, slug) for identifier, slug, _ in found], [("34153", None)])

    def test_a_trailing_path_does_not_become_part_of_the_reference(self):
        found = marketplace_references("https://plugins.jetbrains.com/plugin/34153-maven-lens/versions")
        self.assertEqual([(identifier, slug) for identifier, slug, _ in found], [("34153", "maven-lens")])

    def test_a_vendor_link_is_not_a_plugin_reference(self):
        self.assertEqual(marketplace_references("https://plugins.jetbrains.com/vendor/loplex"), [])

    def test_prose_about_the_marketplace_is_not_a_reference(self):
        self.assertEqual(marketplace_references("published to JetBrains Marketplace as plugin 34153"), [])


class WhetherTheReferencesAgree(unittest.TestCase):
    def test_references_that_agree_are_no_problem(self):
        self.assertEqual(marketplace_problems(AGREEING), [])

    def test_one_occurrence_left_behind_is_caught(self):
        """The half-finished edit this check exists for: the badge moved, the link did not."""
        half_done = AGREEING.replace("plugin/v/34153.svg", "plugin/v/99999.svg")
        problems = marketplace_problems(half_done)
        self.assertEqual(len(problems), 1)
        self.assertIn("more than one Marketplace plugin id", problems[0])
        self.assertIn("99999", problems[0])

    def test_a_disagreement_says_where_it_is(self):
        half_done = AGREEING.replace("plugin/v/34153.svg", "plugin/v/99999.svg")
        self.assertIn("img.shields.io/jetbrains/plugin/v/99999.svg", marketplace_problems(half_done)[0])

    def test_a_slug_renamed_in_one_place_is_caught(self):
        renamed = AGREEING.replace("34153-maven-lens/versions", "34153-mavenlens/versions")
        problems = marketplace_problems(renamed)
        self.assertEqual(len(problems), 1)
        self.assertIn("slug more than one way", problems[0])

    def test_a_readme_naming_no_plugin_at_all_is_refused(self):
        """A check that passes over an empty README reports success for having looked at nothing."""
        problems = marketplace_problems("# Maven Lens\n\nNothing here points at the Marketplace.\n")
        self.assertEqual(len(problems), 1)
        self.assertIn("names no JetBrains Marketplace plugin", problems[0])


class TheReadmeInThisRepository(unittest.TestCase):
    def test_it_passes_its_own_check(self):
        readme = Path(__file__).resolve().parent.parent / "README.md"
        self.assertEqual(marketplace_problems(readme.read_text(encoding="utf-8")), [])


if __name__ == "__main__":
    unittest.main()
