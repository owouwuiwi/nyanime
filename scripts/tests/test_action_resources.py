from pathlib import Path
import unittest
import xml.etree.ElementTree as ET


ROOT = Path(__file__).resolve().parents[2]


class ActionResourcesTest(unittest.TestCase):
    def test_italian_download_actions_have_labels_for_one_and_multiple_episodes(self):
        path = ROOT / 'i18n-aniyomi/src/commonMain/moko-resources/it/plurals.xml'
        plural = ET.parse(path).find(".//plurals[@name='download_amount_anime']")
        labels = {item.attrib['quantity']: (item.text or '').strip() for item in plural}
        self.assertTrue(labels['one'])
        for quantity in ('many', 'other'):
            self.assertIn('%d', labels[quantity])
            for count in (5, 10, 25):
                self.assertIn(str(count), labels[quantity] % count)

    def test_detail_sheet_resources_have_unique_names(self):
        for locale in ('values', 'values-it'):
            path = ROOT / f'app/src/main/res/{locale}/title_details.xml'
            names = [item.attrib['name'] for item in ET.parse(path).getroot()]
            self.assertEqual(len(names), len(set(names)), locale)


if __name__ == '__main__':
    unittest.main()
