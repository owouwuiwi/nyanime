import unittest

from scripts.release_version import ReleaseVersion, parse


class ReleaseVersionTest(unittest.TestCase):
    def test_version_is_four_components_and_code_increases_independently(self):
        version = ReleaseVersion((0, 19, 2, 9), 134, "recommended")
        self.assertEqual(parse(version.properties()), version)
        self.assertEqual(version.bumped("fix"), ReleaseVersion((0, 19, 2, 10), 135, "preview"))
        self.assertEqual(version.bumped("improvement").parts, (0, 19, 3, 0))
        self.assertEqual(version.bumped("feature").parts, (0, 20, 0, 0))
        self.assertEqual(version.bumped("major", "recommended").parts, (1, 0, 0, 0))

    def test_malformed_versions_and_channels_are_rejected(self):
        for name in ["r9000", "0.19.0", "0.19.0.0.1", "0.019.0.0", "0.19.0.0-9000", "-1.0.0.0"]:
            with self.subTest(name=name), self.assertRaises(ValueError):
                parse(f"versionName={name}\nversionCode=134\nchannel=preview\n")
        for code, channel in [(133, "preview"), (2_100_000_001, "recommended"), (134, "other")]:
            with self.subTest(code=code, channel=channel), self.assertRaises(ValueError):
                parse(f"versionName=0.19.0.0\nversionCode={code}\nchannel={channel}\n")

    def test_future_versions_keep_numeric_order_without_using_a_commit_revision(self):
        old = ReleaseVersion((0, 19, 0, 0), 134, "recommended")
        for _ in range(999):
            old = old.bumped("fix")
        self.assertEqual(old.name, "0.19.0.999")
        self.assertEqual(old.code, 1133)


if __name__ == "__main__":
    unittest.main()
