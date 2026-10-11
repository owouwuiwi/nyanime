#!/usr/bin/env python3
"""Bundle notices from the pinned, verified build, without approving distribution."""
import argparse
import hashlib
import html
import json
from pathlib import Path
import re
import subprocess
import tarfile


def digest(path):
    with path.open('rb') as stream:
        return hashlib.file_digest(stream, 'sha256').hexdigest()


def first_comment(path):
    text = path.read_text()
    comment = re.search(r'/\*(.*?)\*/', text, re.S)
    if not comment:
        raise ValueError(f'Missing upstream copyright header: {path}')
    # linebreak.c starts with a vim modeline, so locate the copyright block.
    for comment in re.finditer(r'/\*(.*?)\*/', text, re.S):
        if 'Copyright' in comment[1] and 'Permission' in comment[1]:
            return re.sub(r'^\s*\* ?', '', comment[1], flags=re.M).strip() + '\n'
    raise ValueError(f'Missing upstream licence header: {path}')


def corresponding_text(path, build, archive, encoding='utf-8'):
    relative = path.relative_to(build).as_posix()
    stream = archive.extractfile(relative)
    if stream is None:
        raise ValueError(f'Missing corresponding notice source: {relative}')
    content = stream.read()
    if content != path.read_bytes():
        raise ValueError(f'Notice source differs from corresponding archive: {relative}')
    return content.decode(encoding)


def package(build, output):
    lock = json.loads((build / 'sources.lock.json').read_text())
    verification = json.loads((build / 'native-verification-arm64-v8a.json').read_text())
    if verification.get('state') != 'native_checks_passed_not_release_approval':
        raise ValueError('Native verification has not passed')
    for source in lock['gitSources']:
        actual = subprocess.check_output(['git', '-C', str(build / source['path']),
                                          'rev-parse', 'HEAD'], text=True).strip()
        if actual != source['commit']:
            raise ValueError(f"Source revision changed: {source['path']}")
    archive = build / 'corresponding-sources.tar.gz'
    if not archive.is_file():
        raise ValueError('Missing corresponding-source archive')
    deps = build / 'mpv-android/buildscripts/deps'
    # Retain the upstream documents and the GPL configuration of this build.
    specifications = [
        ('mpv-android', 'MIT', build / 'mpv-android', ['LICENSE']),
        ('ffmpeg-kit', 'LGPL-3.0-or-later', build / 'ffmpeg-kit', ['LICENSE']),
        ('mpv', 'GPL-2.0-or-later; combined GPL-3.0-or-later build', deps / 'mpv', ['Copyright', 'LICENSE.GPL', 'LICENSE.LGPL']),
        ('ffmpeg', 'GPL-3.0-or-later (GPL/version3 enabled; nonfree disabled)', deps / 'ffmpeg',
         ['LICENSE.md', 'COPYING.LGPLv3', 'COPYING.GPLv3']),
        ('mbedtls', 'Apache-2.0', deps / 'mbedtls', ['LICENSE']),
        ('libxml2', 'MIT', deps / 'libxml2', ['Copyright']),
        ('dav1d', 'BSD-2-Clause', deps / 'dav1d', ['COPYING']),
        ('freetype2', 'FreeType License', deps / 'freetype2', ['docs/FTL.TXT']),
        ('fribidi', 'LGPL-2.1-or-later', deps / 'fribidi', ['COPYING']),
        ('harfbuzz', 'MIT', deps / 'harfbuzz', ['COPYING']),
        ('libass', 'ISC', deps / 'libass', ['COPYING']),
        ('libplacebo', 'LGPL-2.1-or-later', deps / 'libplacebo', ['LICENSE']),
        ('cpu-features', 'Apache-2.0', build / 'cpu-features', ['LICENSE']),
    ]
    index = []
    contents_by_name = {}
    target = output / 'assets/licenses/native-player'
    source_map = {source['path']: source for source in lock['gitSources'] + lock['archives']}
    with tarfile.open(archive, 'r:gz') as bundle:
        for name, licence, root, files in specifications:
            source = source_map[root.relative_to(build).as_posix()]
            contents = '\n\n'.join(f'--- {file} ---\n{corresponding_text(root / file, build, bundle)}'
                                    for file in files)
            contents = (f'{name}\n{source["url"]}\nRevision: {source.get("commit", source.get("sha256"))}\n'
                        f'Licence for this build: {licence}\n\n' + contents)
            contents_by_name[f'{name}.txt'] = contents
            index.append({'name': name, 'website': source['url'].removesuffix('.git'),
                          'licenseName': licence, 'asset': f'licenses/native-player/{name}.txt'})
        # Verify copyright headers too, not just complete standalone licence documents.
        lua_html = corresponding_text(deps / 'lua/doc/readme.html', build, bundle, 'latin-1')
        corresponding_text(deps / 'unibreak/src/linebreak.c', build, bundle)
    lua_text = html.unescape(re.sub(r'<[^>]+>', '',
                                   lua_html.split('<BLOCKQUOTE STYLE="padding-bottom: 0em">')[1]
                                   .split('</BLOCKQUOTE>')[0]))
    extra = [('Lua 5.2.4', 'https://www.lua.org', 'MIT', 'lua', lua_text),
             ('libunibreak', 'https://github.com/adah1972/libunibreak', 'Zlib', 'unibreak',
              first_comment(deps / 'unibreak/src/linebreak.c'))]
    for name, website, licence, stem, contents in extra:
        contents_by_name[f'{stem}.txt'] = contents
        index.append({'name': name, 'website': website, 'licenseName': licence,
                      'asset': f'licenses/native-player/{stem}.txt'})
    contents_by_name['catalog.json'] = json.dumps(index, indent=2) + '\n'
    hashes = {f'licenses/native-player/{name}': hashlib.sha256(text.encode('utf-8')).hexdigest()
              for name, text in sorted(contents_by_name.items())}
    report = {'state': 'notices_bundled_not_distribution_approval',
              'correspondingSourcesSha256': digest(archive), 'files': hashes}
    report_file = output / 'native-notices-verification.json'
    if target.exists() or report_file.exists():
        if not report_file.is_file() or json.loads(report_file.read_text()) != report:
            raise ValueError('Existing notice package differs; use a fresh output directory')
        if {file.name for file in target.iterdir()} != set(contents_by_name):
            raise ValueError('Unexpected existing notice files')
        for path, expected in hashes.items():
            if digest(output / 'assets' / path) != expected:
                raise ValueError(f'Existing notice file changed: {path}')
    else:
        # Validate all inputs before creating assets; publish the proof only after all writes.
        target.mkdir(parents=True, exist_ok=False)
        for name, text in contents_by_name.items():
            (target / name).write_text(text, encoding='utf-8', newline='\n')
        report_file.write_text(json.dumps(report, indent=2) + '\n')
    print(json.dumps({'notices': len(index), 'files': len(hashes)}))


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--build', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    package(args.build.resolve(), args.output.resolve())
