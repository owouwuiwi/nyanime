"""Verify the shipped HRIRs without installing the generator's scientific dependencies."""
import array
import struct
import sys
import unittest
from pathlib import Path

ASSETS = Path(__file__).resolve().parents[2] / "app/src/main/assets/audio/cinema"


def read_float_wav(path):
    content = path.read_bytes()
    assert content[:4] == b"RIFF" and content[8:12] == b"WAVE"
    chunks = {}
    offset = 12
    while offset < len(content):
        name, size = struct.unpack_from("<4sI", content, offset)
        chunks[name] = content[offset + 8:offset + 8 + size]
        offset += 8 + size + size % 2
    fmt, channels, rate = struct.unpack_from("<HHI", chunks[b"fmt "])
    assert fmt == 3  # IEEE float, not integer PCM quantization.
    values = array.array("f", chunks[b"data"])
    if sys.byteorder != "little":
        values.byteswap()
    return rate, [values[index::channels] for index in range(channels)]


class HeadphoneAssetsTest(unittest.TestCase):
    def test_layouts_preserve_center_lfe_and_left_right_cues(self):
        for name, count in [("surround51", 6), ("surround51side", 6), ("surround71", 8)]:
            with self.subTest(layout=name):
                rate, channels = read_float_wav(ASSETS / (name + ".wav"))
                self.assertEqual(rate, 48000)
                self.assertEqual(len(channels), count * 2)
                self.assertTrue(all(len(channel) == 558 for channel in channels))
                self.assertEqual(channels[4], channels[5], "Front dialogue must remain centered")
                self.assertTrue(all(value == 0 for value in channels[6] + channels[7]))
                self.assertEqual(channels[0], channels[3])
                self.assertEqual(channels[1], channels[2])
                self.assertGreater(sum(v * v for v in channels[0]), sum(v * v for v in channels[1]))
                for channel in channels:
                    self.assertTrue(all(-1 < sample < 1 for sample in channel))

    def test_notices_travel_with_the_audio_data(self):
        self.assertIn("Bill Gardner", (ASSETS / "LICENSE.txt").read_text())
        self.assertIn("Keith Martin", (ASSETS / "README.txt").read_text())


if __name__ == "__main__":
    unittest.main()
