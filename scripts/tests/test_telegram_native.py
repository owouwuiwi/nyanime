import struct
import unittest
from scripts.verify_telegram_native import verify_elf


class TelegramNativeVerificationTest(unittest.TestCase):
    def elf(self, alignment=16384, machine=183):
        data = bytearray(256)
        data[:6] = b"\x7fELF\x02\x01"
        struct.pack_into("<H", data, 18, machine)
        struct.pack_into("<Q", data, 32, 64)
        struct.pack_into("<HH", data, 54, 56, 1)
        struct.pack_into("<IIQQQQQQ", data, 64, 1, 5, 0, 0, 0, 200, 200, alignment)
        data[120:120] = b"JNI_OnLoad org/drinkless/tdlib/JsonClient"
        return bytes(data)

    def test_accept_aligned_native_engine(self):
        verify_elf(self.elf(), "arm64-v8a")

    def test_reject_wrong_architecture_and_old_page_alignment(self):
        with self.assertRaises(ValueError):
            verify_elf(self.elf(machine=62), "arm64-v8a")
        with self.assertRaises(ValueError):
            verify_elf(self.elf(alignment=4096), "arm64-v8a")

    def test_reject_missing_jni_bindings(self):
        with self.assertRaises(ValueError):
            verify_elf(self.elf().replace(b"JNI_OnLoad", b"XXXXXXXXXX"), "arm64-v8a")
