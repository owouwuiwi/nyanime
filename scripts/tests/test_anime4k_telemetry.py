"""Execute the bundled MPV adapter under Lua 5.2 and LuaJIT with controlled native properties.

Run with: python -m pip install lupa==2.8
          python scripts/tests/test_anime4k_telemetry.py
This tests the actual Lua program and protocol, not an Android GPU or decoder.
"""
import importlib
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
SCRIPT = ROOT / "app/src/main/assets/aniyomi_anime4k.lua"
BOOTSTRAP = """
props = { pause = false, ['idle-active'] = false, ['frame-drop-count'] = 4294967296 }
snapshots = {}
reads = {}
messages = {}
writes = 0
package.preload['mp.utils'] = function()
    return { format_json = function(value)
        snapshots[#snapshots + 1] = value
        return 'snapshot'
    end }
end
mp = {
    get_property_native = function(name, default)
        reads[name] = (reads[name] or 0) + 1
        if props[name] == nil then return default end
        return props[name]
    end,
    set_property = function(name, value)
        assert(name == 'user-data/aniyomi-anime4k/telemetry')
        assert(value == 'snapshot')
        writes = writes + 1
    end,
    add_periodic_timer = function(interval, callback)
        assert(interval == 1)
        timer = {
            active = true,
            kill = function(self) self.active = false end,
            resume = function(self) self.active = true end,
            tick = function(self) if self.active then callback() end end,
        }
        return timer
    end,
    register_script_message = function(name, callback) messages[name] = callback end,
}
"""


class AdapterChecks:
    engine = None

    def setUp(self):
        self.lua = importlib.import_module("lupa." + self.engine).LuaRuntime(unpack_returned_tuples=True)
        self.lua.execute(BOOTSTRAP)
        self.lua.execute(SCRIPT.read_text(encoding="utf-8"))

    def enable(self, token="42"):
        self.lua.globals().messages["set-active"]("yes", token)

    def tick(self):
        self.lua.execute("timer:tick()")
        return self.lua.globals().snapshots[len(self.lua.globals().snapshots)]

    def test_disabled_adapter_does_no_polling(self):
        self.lua.execute("timer:tick()")
        self.assertEqual(0, self.lua.eval("#snapshots"))
        self.assertIsNone(self.lua.eval("next(reads)"))
        self.enable()
        self.tick()
        self.lua.globals().messages["set-active"]("no", "")
        self.lua.execute("timer:tick()")
        self.assertEqual(1, self.lua.eval("#snapshots"))

    def test_sums_native_nanoseconds_and_preserves_long_counters(self):
        self.enable()
        self.lua.execute("""
            props['vo-passes'] = {
                fresh = {{ avg = 1000000, count = 6 }, { avg = 2500000, count = 4 }},
                redraw = {{ avg = 500000, count = 2 }},
            }
        """)
        packet = self.tick()
        self.assertEqual(3.5, packet.renderTimeMillis)
        self.assertEqual(0.5, packet.redrawTimeMillis)
        self.assertEqual(4294967296, packet.outputDrops)
        self.assertEqual(1, packet.version)
        self.assertEqual("42", packet.token)
        self.assertTrue(packet.playing)

    def test_missing_timers_are_unknown_instead_of_fake_capacity(self):
        self.enable()
        for value in ("nil", "false", "{}", "{fresh={}}", "{fresh={{avg=0,count=0}}}",
                      "{fresh={{avg=0/0,count=4}}}", "{fresh={{avg=-1,count=4}}}",
                      "{fresh={{avg=1000000,count=4},{avg=1000000,count=0}}}"):
            with self.subTest(value=value):
                self.lua.execute("props['vo-passes'] = " + value)
                self.assertIsNone(self.tick().renderTimeMillis)

    def test_pause_cache_seek_idle_and_eof_never_sample_gpu(self):
        self.enable()
        for flag in ("pause", "paused-for-cache", "seeking", "idle-active", "eof-reached"):
            with self.subTest(flag=flag):
                self.lua.globals().props[flag] = True
                self.lua.execute("reads = {}")
                packet = self.tick()
                self.assertFalse(packet.playing)
                self.assertIsNone(packet.renderTimeMillis)
                self.assertIsNone(self.lua.globals().reads["vo-passes"])
                self.lua.globals().props[flag] = False
        self.assertTrue(self.tick().playing)

    def test_resuming_and_changing_session_never_reuses_sequence(self):
        self.enable()
        first = self.tick()
        self.lua.globals().messages["set-active"]("no", "")
        self.enable("43")
        second = self.tick()
        self.assertEqual("42", first.token)
        self.assertEqual("43", second.token)
        self.assertGreater(second.sequence, first.sequence)

    def test_malformed_counter_does_not_poison_the_snapshot(self):
        self.enable()
        for value in (-1, 1.5, float("nan"), float("inf"), 10**18, "unavailable"):
            with self.subTest(value=value):
                self.lua.globals().props["frame-drop-count"] = value
                packet = self.tick()
                self.assertIsNone(packet.outputDrops)
                self.assertTrue(packet.playing)

    def test_work_per_snapshot_is_bounded(self):
        self.enable()
        self.lua.execute("""
            props['vo-passes'] = { fresh = {} }
            for i = 1, 129 do props['vo-passes'].fresh[i] = {avg=1000000, count=4} end
        """)
        self.assertIsNone(self.tick().renderTimeMillis)


class Lua52Tests(AdapterChecks, unittest.TestCase):
    engine = "lua52"


class LuaJitTests(AdapterChecks, unittest.TestCase):
    engine = "luajit21"


if __name__ == "__main__":
    unittest.main(verbosity=2)
