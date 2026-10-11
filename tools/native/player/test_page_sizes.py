import unittest
from elf_page_policy import relro_page_layout
from verify_native import validate_dependencies, REQUIRED_LIBS


class PageSizesTest(unittest.TestCase):
    def inventory(self):
        return {name: {'soname': name, 'needed': [], 'minimumLoadAlignment': 16384,
                       'relroPageLayoutCompatible': True} for name in REQUIRED_LIBS}

    def test_32_bit_ndk_runtime(self):
        inventory = self.inventory()
        inventory['libc++_shared.so']['minimumLoadAlignment'] = 4096
        validate_dependencies(inventory, 'armeabi-v7a')
        validate_dependencies(inventory, 'x86')

    def test_64_bit_runtime_requires_16k(self):
        inventory = self.inventory()
        inventory['libc++_shared.so']['minimumLoadAlignment'] = 4096
        for abi in ('arm64-v8a', 'x86_64'):
            with self.assertRaisesRegex(ValueError, 'alignment'):
                validate_dependencies(inventory, abi)

    def test_4k_relro_does_not_protect_following_mutable_pages(self):
        loads = [(0x1000, 0x1000, 6), (0x2000, 0x1000, 6)]
        self.assertTrue(relro_page_layout(loads, [(0x1000, 0x1000)], 4096)['relroPageLayoutCompatible'])
        self.assertFalse(relro_page_layout(loads, [(0x1000, 0x1000)], 16384)['relroPageLayoutCompatible'])


if __name__ == '__main__':
    unittest.main()
