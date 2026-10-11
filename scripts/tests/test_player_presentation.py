"""Reject a renderer that silently starts depending on an optional feature."""

import contextlib
import io
from pathlib import Path
import struct
import tempfile
import unittest
import zipfile

from scripts.verify_player_presentation import NOTICES, REQUIRED, class_strings, verify


def bytecode(text):
    raw = text.encode()
    return b"\xca\xfe\xba\xbe\x00\x00\x00\x3d\x00\x02\x01" + struct.pack(">H", len(raw)) + raw


class PlayerPresentationBoundaryTest(unittest.TestCase):
    def fixture(self, classes=None, license_text=b"private source", native=False):
        root = Path(self.directory.name)
        for _, name in NOTICES:
            (root / name).parent.mkdir(parents=True, exist_ok=True)
            (root / name).write_bytes(b"private source")
        jar = io.BytesIO()
        with zipfile.ZipFile(jar, "w") as output:
            for name, raw in (classes or {name: bytecode("java/lang/Object") for name in REQUIRED}).items():
                output.writestr(name, raw)
        aar = root / "renderer.aar"
        with zipfile.ZipFile(aar, "w") as output:
            output.writestr("classes.jar", jar.getvalue())
            for asset, _ in NOTICES:
                output.writestr("assets/licenses/" + asset, license_text)
            if native:
                output.writestr("jni/arm64-v8a/libengine.so", b"unexpected")
        return aar, root

    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)

    def run_check(self, **kwargs):
        with contextlib.redirect_stdout(io.StringIO()):
            verify(*self.fixture(**kwargs))

    def test_accept_standalone_ui(self):
        self.run_check()

    def test_reject_feature_class_reference(self):
        classes = {name: bytecode("java/lang/Object") for name in REQUIRED}
        classes[next(iter(REQUIRED))] = bytecode("Lio/github/aedev/flow/ui/Component;")
        with self.assertRaisesRegex(ValueError, "Optional feature"):
            self.run_check(classes=classes)

    def test_reject_reflection_dependency(self):
        classes = {name: bytecode("org.schabi.newpipe.extractor.Stream") for name in REQUIRED}
        with self.assertRaisesRegex(ValueError, "Optional feature"):
            self.run_check(classes=classes)

    def test_reject_missing_ui(self):
        with self.assertRaisesRegex(ValueError, "classes missing"):
            self.run_check(classes={"Unrelated.class": bytecode("java/lang/Object")})

    def test_reject_stale_license(self):
        with self.assertRaisesRegex(ValueError, "notice differs"):
            self.run_check(license_text=b"stale license")

    def test_reject_native_runtime_in_presentation(self):
        with self.assertRaisesRegex(ValueError, "native engines"):
            self.run_check(native=True)

    def test_reject_non_bytecode(self):
        with self.assertRaisesRegex(ValueError, "Invalid Java"):
            class_strings(b"not a class")


if __name__ == "__main__":
    unittest.main()
