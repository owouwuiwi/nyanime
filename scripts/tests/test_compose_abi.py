"""Exercise the APK guard with small DEX fixtures, including cross-DEX linkage."""

import contextlib
import io
from pathlib import Path
import re
import struct
import tempfile
import unittest
import zipfile

from scripts.verify_compose_abi import OWNER, verify
from scripts.verify_viewmodel_abi import store_members


COLORS = ("toggleButtonColors-5tl4gsc", "(JJJJJJLandroidx/compose/runtime/Composer;II)Landroidx/compose/material3/ToggleButtonColors;")
PADDING = ("getContentPadding", "()Landroidx/compose/foundation/layout/PaddingValues;")


def uleb(value):
    result = bytearray()
    while value >= 128:
        result.append((value & 127) | 128)
        value >>= 7
    result.append(value)
    return result


def fixture(members=(), declared=(), owner=OWNER):
    """Build the standard DEX tables consumed by the artifact verifier."""
    members = list(members)
    signatures = sorted({signature for _, signature in members})
    params = {signature: re.findall(r"\[*L[^;]+;|\[*[ZBCSIJFD]", signature[1:signature.index(")")]) for signature in signatures}
    returns = {signature: signature.split(")")[1] for signature in signatures}
    types = sorted({owner, "Ljava/lang/Object;", *returns.values(), *(item for values in params.values() for item in values)})
    strings = sorted({*types, *(name for name, _ in members)})
    data = bytearray(112)
    data[:8] = b"dex\n035\0"

    def table(count, size, header):
        offset = len(data)
        data.extend(bytes(count * size))
        struct.pack_into("<II", data, header, count, offset)
        return offset

    string_table = table(len(strings), 4, 56)
    type_table = table(len(types), 4, 64)
    proto_table = table(len(signatures), 12, 72)
    method_table = table(len(members), 8, 88)
    class_table = table(1 if declared else 0, 32, 96)
    for index, value in enumerate(strings):
        struct.pack_into("<I", data, string_table + index * 4, len(data))
        data.extend(uleb(len(value)) + value.encode() + b"\0")
    for index, value in enumerate(types):
        struct.pack_into("<I", data, type_table + index * 4, strings.index(value))
    for index, signature in enumerate(signatures):
        offset = len(data) if params[signature] else 0
        if offset:
            data.extend(struct.pack("<I", len(params[signature])))
            for value in params[signature]:
                data.extend(struct.pack("<H", types.index(value)))
        struct.pack_into("<III", data, proto_table + index * 12, 0, types.index(returns[signature]), offset)
    for index, (name, signature) in enumerate(members):
        struct.pack_into("<HHI", data, method_table + index * 8, types.index(owner), signatures.index(signature), strings.index(name))
    if declared:
        struct.pack_into("<I", data, class_table, types.index(owner))
        struct.pack_into("<I", data, class_table + 8, types.index("Ljava/lang/Object;"))
        struct.pack_into("<I", data, class_table + 24, len(data))
        data.extend(uleb(0) + uleb(0) + uleb(0) + uleb(len(declared)))
        previous = 0
        for index in sorted(members.index(member) for member in declared):
            data.extend(uleb(index - previous) + uleb(1) + uleb(0))
            previous = index
    return bytes(data)


class ComposeAbiTest(unittest.TestCase):
    def check_apk(self, *dex_files):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "test.apk"
            with zipfile.ZipFile(path, "w") as apk:
                for index, data in enumerate(dex_files):
                    apk.writestr("classes" + (str(index + 1) if index else "") + ".dex", data)
            with contextlib.redirect_stdout(io.StringIO()):
                verify(path)

    def test_accept_matching_methods(self):
        self.check_apk(fixture((COLORS, PADDING), (COLORS, PADDING)))

    def test_reject_removed_color_method_with_same_runtime_owner(self):
        new_colors = ("colors-5tl4gsc", COLORS[1])
        with self.assertRaisesRegex(ValueError, "toggleButtonColors-5tl4gsc"):
            self.check_apk(fixture((COLORS, new_colors), (new_colors,)))

    def test_reject_removed_padding_getter(self):
        with self.assertRaisesRegex(ValueError, "getContentPadding"):
            self.check_apk(fixture((PADDING,)))

    def test_accept_definition_in_later_dex(self):
        self.check_apk(fixture((COLORS,)), fixture((COLORS,), (COLORS,)))

    def test_reject_same_name_with_different_descriptor(self):
        incompatible = (COLORS[0], "()V")
        with self.assertRaisesRegex(ValueError, "Material3"):
            self.check_apk(fixture((COLORS, incompatible), (incompatible,)))

    def test_accept_inherited_object_methods(self):
        self.check_apk(fixture((("hashCode", "()I"),)))

    def test_accept_completely_inlined_object(self):
        self.check_apk(fixture())

    def test_reject_missing_or_invalid_dex(self):
        with self.assertRaisesRegex(ValueError, "no DEX"):
            self.check_apk()
        with self.assertRaisesRegex(ValueError, "standard DEX"):
            self.check_apk(b"not a dex")

    def test_generic_parser_preserves_lifecycle_owner(self):
        owner = "Landroidx/lifecycle/ViewModelStore;"
        member = ("put", "(Ljava/lang/String;Landroidx/lifecycle/ViewModel;)V")
        used, defined = store_members(fixture((member,), (member,), owner=owner))
        self.assertEqual(used, {(owner, *member)})
        self.assertEqual(defined, used)


if __name__ == "__main__":
    unittest.main()
