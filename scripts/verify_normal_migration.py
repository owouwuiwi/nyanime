"""Verify the ordinary app's feature boundary in source, dependency reports and APKs."""
import argparse
import json
from pathlib import Path
import re
import zipfile

from scripts.verify_native_distribution import dex_defined_classes

EXCLUDED_ROOTS = ('feature/online-video/', 'feature/music/', 'vendor/flow/',
                  'vendor/meld/', 'vendor/music-dependencies/')
EXCLUDED_MODULE = re.compile(r'aedev|newpipe|metrolist|innertubex|tinypinyin', re.I)
EXCLUDED_CLASSES = (b'Lio/github/aedev/flow/', b'Lnyanime/video/', b'Lnyanime/music/',
                    b'Lcom/metrolist/', b'Lorg/schabi/newpipe/', b'Lit/lorelore/nyanime/video/')


def source_entries(names):
    for name in names:
        if (name.rstrip('/') + '/').startswith(EXCLUDED_ROOTS):
            raise ValueError('Excluded feature in ordinary source archive: ' + name)
    required = ('presentation-player/LICENSE', 'presentation-player/NOTICE',
                'presentation-player/build.gradle.kts',
                'presentation-player/third-party/flow-ui/LICENSE',
                'presentation-player/third-party/flow-ui/NOTICE',
                'presentation-player/third-party/flow-ui/PROVENANCE.json',
                'presentation-player/src/main/java/it/lorelore/nyanime/presentation/player/PlayerChrome.kt')
    if missing := set(required) - set(names):
        raise ValueError('Renderer corresponding source missing: ' + ', '.join(sorted(missing)))


def dependencies(data):
    if set(data) != {'previewCompileClasspath', 'previewRuntimeClasspath'}:
        raise ValueError('Both ordinary compile/runtime graphs must be provided')
    for name, values in data.items():
        if not isinstance(values, list) or not values:
            raise ValueError('Empty dependency graph: ' + name)
        if any(not isinstance(value, str) or EXCLUDED_MODULE.search(value) or
               ':feature:online-video' in value or ':feature:music' in value for value in values):
            raise ValueError('Excluded runtime in dependency graph: ' + name)


def build_config(path):
    """Check generated release inputs without exposing embedded client credentials."""
    text = path.read_text(encoding='utf-8')
    fields = re.findall(r'public static final boolean (DEBUG|UPDATER_ENABLED)\s*=\s*(true|false)\s*;', text)
    if len(fields) != 2 or dict(fields) != {'DEBUG': 'false', 'UPDATER_ENABLED': 'true'}:
        raise ValueError('Ordinary signed APK must be non-debuggable and retain its updater')


def apk(path):
    with zipfile.ZipFile(path) as package:
        for name in package.namelist():
            if name.startswith(('assets/solver/', 'assets/licenses/online-video/', 'assets/licenses/music/')) or \
                    name.endswith(('/FLOW-LICENSE.txt', '/FLOW-NOTICE.txt', '/libnyanime_music_quickjs.so')):
                raise ValueError('Excluded feature payload in ordinary APK: ' + name)
            if re.fullmatch(r'classes(?:\d+)?\.dex', name):
                if any(value.startswith(EXCLUDED_CLASSES) for value in dex_defined_classes(package.read(name))):
                    raise ValueError('Excluded runtime classes in ordinary APK: ' + name)
        # Exact current original-source grant and attributions must reach recipients.
        if b'Apache License' not in package.read('assets/licenses/PlayerPresentation-LICENSE.txt'):
            raise ValueError('Renderer Apache grant missing from ordinary APK')
        registry = json.loads(package.read('assets/licenses/catalog.json'))
        renderer = [row for row in registry if row.get('asset') == 'licenses/PlayerPresentation-LICENSE.txt']
        if len(renderer) != 1 or renderer[0].get('licenseName') != 'Apache-2.0':
            raise ValueError('Renderer attribution is missing, duplicated or stale')
        style = [row for row in registry if row.get('asset') == 'licenses/FlowPlayerStyle-LICENSE.txt']
        if len(style) != 1 or style[0].get('licenseName') != 'GPL-3.0-only':
            raise ValueError('Adapted UI GPL attribution is missing, duplicated or stale')
        root = Path(__file__).resolve().parents[1] / 'presentation-player'
        from scripts.verify_player_presentation import NOTICES
        for asset, original in NOTICES:
            if package.read('assets/licenses/' + asset) != (root / original).read_bytes():
                raise ValueError('Packaged renderer licence or notice differs: ' + asset)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--dependencies', type=Path)
    parser.add_argument('--source', type=Path)
    parser.add_argument('--build-config', type=Path)
    parser.add_argument('--apk', type=Path, nargs='*', default=[])
    args = parser.parse_args()
    if args.dependencies:
        dependencies(json.loads(args.dependencies.read_text(encoding='utf-8')))
    if args.build_config:
        build_config(args.build_config)
    if args.source:
        with zipfile.ZipFile(args.source) as package:
            names = package.namelist()
            # Source ZIPs include a single versioned top-level directory.
            prefix = names[0].split('/')[0] + '/' if names and names[0].startswith('Nyanime-') else ''
            source_entries([name.removeprefix(prefix) for name in names])
    for path in args.apk:
        apk(path)
    print('ORDINARY_MIGRATION_BOUNDARY_OK')


if __name__ == '__main__':
    main()
