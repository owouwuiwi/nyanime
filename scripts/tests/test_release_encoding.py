import unittest

from scripts.public_distribution import validate_release_notes


class ReleaseEncodingTest(unittest.TestCase):
    def test_real_accents_and_versioned_notes_remain_intact(self):
        validate_release_notes(
            "<!-- nyanime-changelog:1 -->\n## Novità di Nyanime 1.2.3.4\n"
            "### 1.2.3.4: Update\n"
            "#### Compatibilità\n- Più stabilità. São, française, 日本語."
        )

    def test_double_decoded_utf8_is_rejected_before_publication(self):
        for original in ["Novità", "più", "l’app", "—", "✓"]:
            corrupted = original.encode("utf-8").decode("cp1252")
            with self.subTest(original=original), self.assertRaises(ValueError):
                validate_release_notes(corrupted)
        with self.assertRaises(ValueError):
            validate_release_notes("Replacement character: \ufffd")


if __name__ == "__main__":
    unittest.main()
