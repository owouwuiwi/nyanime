"""Exercise the CI launcher without compiling or starting a real JVM."""
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest


BASH = (
    "C:/Program Files/Git/bin/bash.exe"
    if Path("C:/Program Files/Git/bin/bash.exe").is_file() else None
) or shutil.which("bash")
SCRIPT = Path(__file__).resolve().parents[1] / "ci_gradle.sh"


@unittest.skipUnless(BASH, "Bash is required by the Linux CI launcher")
class CiGradleTest(unittest.TestCase):
    def launch(self, exit_code):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / "ci_gradle.sh").write_text(SCRIPT.read_text(), encoding="utf-8", newline="\n")
            (root / "gradlew").write_text(
                '#!/usr/bin/env bash\nprintf "%s\\n" "$@" > invocation.txt\n'
                f'/bin/sleep .2\nexit {exit_code}\n', encoding="utf-8", newline="\n"
            )
            (root / "gradlew").chmod(0o755)
            commands = root / "commands"
            commands.mkdir()
            for name in ("free", "ps"):
                mock = commands / name
                mock.write_text('#!/usr/bin/env bash\necho diagnostic-sample\n', encoding="utf-8", newline="\n")
                mock.chmod(0o755)
            # Keep the resource-monitor probe quick and bounded in this test.
            sleeper = commands / "sleep"
            sleeper.write_text('#!/usr/bin/env bash\n/bin/sleep .05\n', encoding="utf-8", newline="\n")
            sleeper.chmod(0o755)
            environment = os.environ.copy()
            environment.update({
                "PATH": str(commands) + os.pathsep + environment["PATH"],
                "CI_GRADLE_PHASE": "assemble",
                "CI_GRADLE_HEAP": "8g",
                "RUNNER_TEMP": root.as_posix(),
            })
            completed = subprocess.run(
                [BASH, "ci_gradle.sh", ":app:assemblePreview", "-Penable-updater"],
                cwd=root, env=environment, capture_output=True, text=True, timeout=10,
            )
            invocation = (root / "invocation.txt").read_text(encoding="utf-8").splitlines()
            resources = (root / "nyanime-gradle" / "assemble-resources.log").read_text()
            return completed, invocation, resources

    def test_fresh_jvm_heap_diagnostics_and_task_arguments(self):
        completed, invocation, resources = self.launch(0)
        self.assertEqual(completed.returncode, 0, completed.stderr)
        self.assertEqual(invocation[:2], [":app:assemblePreview", "-Penable-updater"])
        self.assertIn("--no-daemon", invocation)
        self.assertIn("--no-parallel", invocation)
        self.assertIn("--max-workers=2", invocation)
        options = next(arg for arg in invocation if arg.startswith("-Dorg.gradle.jvmargs="))
        self.assertIn("-Xmx8g", options)
        self.assertIn("assemble-gc.log", options)
        self.assertIn("filecount=2,filesize=10M", options)
        self.assertIn("CI build assemble:", resources)

    def test_gradle_failure_is_not_hidden_by_monitor(self):
        completed, _, _ = self.launch(23)
        self.assertEqual(completed.returncode, 23, completed.stderr)


if __name__ == "__main__":
    unittest.main()
