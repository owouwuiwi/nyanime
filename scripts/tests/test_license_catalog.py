import unittest
from scripts.verify_license_catalog import validate


class LicenseCatalogTest(unittest.TestCase):
    def test_all_declared_license_texts_are_required(self):
        catalog = {'libraries': [{'uniqueId': 'example:component', 'licenses': ['A', 'B']}],
                   'licenses': {'A': {'content': 'License A'}}}
        self.assertEqual(validate(catalog), ['example:component: missing license text for B'])
        catalog['licenses']['B'] = {'content': 'License B'}
        self.assertEqual(validate(catalog), [])

    def test_missing_metadata_and_whitespace_text_do_not_pass(self):
        self.assertTrue(validate({'libraries': [], 'licenses': {}}))
        self.assertEqual(validate({'libraries': [{'uniqueId': 'example:component'}],
                                  'licenses': {'A': {'content': 'License A'}}}),
                         ['example:component: missing license declaration'])
        self.assertTrue(validate({'libraries': [{'uniqueId': 'example:component', 'licenses': ['A']}],
                                  'licenses': {'A': {'content': '   '}}}))
