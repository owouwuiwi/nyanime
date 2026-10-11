import unittest
import json
from pathlib import Path
import tempfile
import zipfile

from scripts.verify_normal_migration import apk, build_config, dependencies, source_entries
from scripts.verify_player_presentation import NOTICES
from scripts.tests import test_native_distribution as native_fixtures


class NormalMigrationTest(unittest.TestCase):
    def test_signed_configuration_keeps_the_updater_enabled_without_exposing_other_fields(self):
        with tempfile.TemporaryDirectory() as folder:
            path = Path(folder) / 'BuildConfig.java'
            path.write_text('public static final boolean DEBUG = false;\n'
                            'public static final boolean UPDATER_ENABLED = true;\n'
                            'public static final String EXAMPLE_PRIVATE_FIELD = "fixture";\n')
            build_config(path)

    def test_disabled_missing_duplicated_or_debug_configuration_is_rejected(self):
        cases = [(False, False, False), (True, True, False), (False, True, True)]
        with tempfile.TemporaryDirectory() as folder:
            path = Path(folder) / 'BuildConfig.java'
            for debug, enabled, duplicate in cases:
                path.write_text(f'public static final boolean DEBUG = {str(debug).lower()};\n'
                                f'public static final boolean UPDATER_ENABLED = {str(enabled).lower()};\n' +
                                ('public static final boolean UPDATER_ENABLED = true;\n' if duplicate else ''))
                with self.subTest(debug=debug, enabled=enabled, duplicate=duplicate), self.assertRaises(ValueError):
                    build_config(path)
            path.write_text('public static final boolean DEBUG = false;\n')
            with self.assertRaises(ValueError):
                build_config(path)

    def graphs(self, extra):
        return {name: ['androidx.compose.material3:material3:1.0', *extra]
                for name in ('previewCompileClasspath', 'previewRuntimeClasspath')}

    def test_common_androidx_dependencies_are_allowed(self):
        dependencies(self.graphs(['androidx.media3:media3-common:1.0']))

    def test_excluded_transitive_dependency_blocks_the_ordinary_build(self):
        for name in ('com.github.A-EDev:NewPipeExtractor:1.0', 'com.metrolist:innertubex:1.0',
                     'project :feature:online-video', 'project :feature:music:quickjs'):
            with self.subTest(name=name), self.assertRaises(ValueError):
                dependencies(self.graphs([name]))

    def test_incomplete_dependency_proof_is_rejected(self):
        with self.assertRaises(ValueError):
            dependencies({'previewRuntimeClasspath': ['androidx.compose:compose:1.0']})

    def test_public_source_cannot_silently_omit_the_renderer(self):
        with self.assertRaisesRegex(ValueError, 'corresponding source missing'):
            source_entries(['app/build.gradle.kts', 'LICENSE'])

    def test_nested_excluded_feature_is_not_accepted_as_ordinary_source(self):
        with self.assertRaisesRegex(ValueError, 'Excluded feature'):
            source_entries(['feature/music/quickjs/build.gradle.kts'])

    def test_excluded_engine_in_a_secondary_dex_is_rejected(self):
        with tempfile.TemporaryDirectory() as folder:
            path = Path(folder) / 'fixture.apk'
            with zipfile.ZipFile(path, 'w') as archive:
                archive.writestr('classes.dex', native_fixtures.NativeDistributionTest.dex(['example.Host']))
                archive.writestr('classes2.dex', native_fixtures.NativeDistributionTest.dex(['nyanime.video.Engine']))
            with self.assertRaisesRegex(ValueError, 'Excluded runtime classes'):
                apk(path)

    def test_both_original_and_adapted_ui_notices_are_required(self):
        module = Path(__file__).resolve().parents[2] / 'presentation-player'
        registry = [dict(asset='licenses/PlayerPresentation-LICENSE.txt', licenseName='Apache-2.0'),
                    dict(asset='licenses/FlowPlayerStyle-LICENSE.txt', licenseName='GPL-3.0-only')]
        with tempfile.TemporaryDirectory() as folder:
            for stale in (False, True):
                path = Path(folder) / f'fixture-{stale}.apk'
                with zipfile.ZipFile(path, 'w') as archive:
                    archive.writestr('assets/licenses/catalog.json', json.dumps(registry))
                    for asset, source in NOTICES:
                        content = (module / source).read_bytes()
                        if stale and asset == 'FlowPlayerStyle-NOTICE.txt':
                            content = b'old notice'
                        archive.writestr('assets/licenses/' + asset, content)
                if stale:
                    with self.assertRaisesRegex(ValueError, 'differs'):
                        apk(path)
                else:
                    apk(path)


if __name__ == '__main__':
    unittest.main()
