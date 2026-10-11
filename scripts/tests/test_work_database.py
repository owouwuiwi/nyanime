import contextlib
import io
from pathlib import Path
import tempfile
import unittest
import zipfile

from scripts.tests.test_compose_abi import fixture
from scripts.verify_work_database import OWNER, dependencies, verify


CTOR = ("<init>", "()V")


class WorkDatabaseTest(unittest.TestCase):
    def graphs(self, work="2.11.2", room="2.8.4"):
        return {name: ["androidx.work:work-runtime:" + work, "androidx.room:room-runtime:" + room]
                for name in ("previewCompileClasspath", "previewRuntimeClasspath")}

    def test_accept_deployed_or_newer_stable_database_dependencies(self):
        dependencies(self.graphs())
        dependencies(self.graphs(work="2.12.0", room="2.9.0"))

    def test_reject_schema_and_room_runtime_downgrades(self):
        for work, room in [("2.10.0", "2.8.4"), ("2.11.2", "2.6.1")]:
            with self.subTest(work=work, room=room), self.assertRaisesRegex(ValueError, "downgrade"):
                dependencies(self.graphs(work=work, room=room))

    def test_both_classpaths_must_be_present(self):
        with self.assertRaisesRegex(ValueError, "Missing"):
            dependencies({"previewRuntimeClasspath": self.graphs()["previewRuntimeClasspath"]})

    def test_ambiguous_and_unverified_prerelease_versions_are_rejected(self):
        values = self.graphs()
        values["previewRuntimeClasspath"].append("androidx.work:work-runtime:2.11.2")
        with self.assertRaisesRegex(ValueError, "ambiguous"):
            dependencies(values)
        with self.assertRaisesRegex(ValueError, "ambiguous"):
            dependencies(self.graphs(work="2.12.0-alpha01"))

    def check_apk(self, *dex_files):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "test.apk"
            with zipfile.ZipFile(path, "w") as apk:
                for index, data in enumerate(dex_files):
                    apk.writestr("classes" + (str(index + 1) if index else "") + ".dex", data)
            with contextlib.redirect_stdout(io.StringIO()):
                verify(path)

    def test_accept_declared_no_argument_constructor(self):
        self.check_apk(fixture((CTOR,), (CTOR,), owner=OWNER))

    def test_accept_constructor_defined_in_a_later_dex(self):
        self.check_apk(fixture((CTOR,), owner=OWNER), fixture((CTOR,), (CTOR,), owner=OWNER))

    def test_reference_does_not_replace_a_declaration(self):
        with self.assertRaisesRegex(ValueError, "constructor"):
            self.check_apk(fixture((CTOR,), owner=OWNER))

    def test_parameterized_constructor_is_insufficient(self):
        other = ("<init>", "(Ljava/lang/Object;)V")
        with self.assertRaisesRegex(ValueError, "constructor"):
            self.check_apk(fixture((other,), (other,), owner=OWNER))

    def test_constructor_on_another_class_is_insufficient(self):
        with self.assertRaisesRegex(ValueError, "constructor"):
            self.check_apk(fixture((CTOR,), (CTOR,), owner="Lexample/OtherDatabase;"))

    def test_reject_missing_or_invalid_dex(self):
        with self.assertRaisesRegex(ValueError, "no DEX"):
            self.check_apk()
        with self.assertRaisesRegex(ValueError, "standard DEX"):
            self.check_apk(b"not a dex")


if __name__ == "__main__":
    unittest.main()
