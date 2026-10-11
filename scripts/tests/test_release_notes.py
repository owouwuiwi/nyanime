import unittest
from unittest.mock import patch

from scripts.release_notes import CATEGORIES, previous_tag, release_notes


class ReleaseNotesTest(unittest.TestCase):
    def test_additional_categories_keep_entries_without_republishing_history(self):
        old = "## Older\n- Previously released.\n"
        current = "## Older\n### Compatibilità\n- Previously released.\n"
        current += "## Current\n" + "".join(
            f"### {category}\n- Entry {index}.\n" for index, category in enumerate(CATEGORIES)
        )
        notes = release_notes(current, old, "v0.42.0.0")
        self.assertNotIn("Previously released.", notes)
        for category in CATEGORIES:
            self.assertIn("#### " + category, notes)
        self.assertEqual(notes.count("- Entry "), len(CATEGORIES))

    def test_unknown_category_does_not_inherit_the_previous_heading(self):
        current = "## Current\n### Novità\n- Feature.\n### Future category\n- Original note.\n"
        notes = release_notes(current, None, "v0.42.0.0")
        self.assertIn("#### Note originali\n\n- Original note.", notes)
        self.assertIn("#### Novità\n\n- Feature.", notes)

    def test_numeric_notes_never_expose_a_revision_prefix(self):
        notes = release_notes("## Current\n\n- New.\n", None, "v0.19.0.0")
        self.assertIn("Nyanime 0.19.0.0", notes)
        self.assertNotIn("Nyanime v0", notes)

    def test_previous_release_ignores_current_aliases_and_unrelated_platforms(self):
        def fake_git(*args):
            if args[0] == "tag":
                return "r42\nv0.19.0.0\nr99\nv0.19.0.1\ntv-r100"
            return {"r42..HEAD": "3", "v0.19.0.0..HEAD": "3", "r99..HEAD": "0"}[args[-1]]
        with patch("scripts.release_notes.git", side_effect=fake_git):
            self.assertEqual(previous_tag("v0.19.0.1"), "v0.19.0.0")

    def test_only_new_entries_are_published(self):
        old = "## Older section\n\n- Previously released change.\n"
        current = """## Current section

- New change spanning
  two lines.

## Older section

- Previously released change.
"""
        notes = release_notes(current, old, "r42")

        self.assertIn("New change spanning two lines.", notes)
        self.assertNotIn("Previously released change.", notes)
        self.assertNotIn("Older section", notes)

    def test_categories_are_explicit_and_old_notes_are_not_guessed(self):
        current = "## 12 October — 0.41.0.8: Updates\n\n### Novità\n- New screen.\n\n" \
                  "### Correzioni\n- Retry fixed.\n\n### Prestazioni\n- Less work.\n\n" \
                  "## 11 October — 0.41.0.7: Old notes\n- Fixed old feature.\n"
        notes = release_notes(current, None, "v0.41.0.8")
        self.assertTrue(notes.startswith("<!-- nyanime-changelog:1 -->"))
        for category in ("Novità", "Correzioni", "Prestazioni"):
            self.assertIn("#### " + category, notes)
        self.assertNotIn("#### Altre modifiche", notes)
        self.assertIn("- Fixed old feature.", notes)

    def test_categorizing_old_entries_does_not_publish_them_again(self):
        old = "## Old\n- Already published.\n"
        current = "## Old\n### Correzioni\n- Already published.\n- Newly fixed.\n"
        notes = release_notes(current, old, "v0.41.0.8")
        self.assertNotIn("Already published.", notes)
        self.assertEqual(notes.count("- Newly fixed."), 1)


if __name__ == "__main__":
    unittest.main()
