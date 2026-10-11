import copy
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
import zipfile

from scripts.delta_updates import prepare_deltas, sha256
from scripts.release_graph import (ABIS, FILE_NAME, MAX_BYTES, asset_url, apk_name, check_transition,
                                   load, prepare, publication_graph, reconstruct, route, validate, verify_capability)


class ReleaseGraphTest(unittest.TestCase):
    repository = 'example/application'
    application_id = 'it.owouwuiwi.nyanime'

    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.old = self.root / 'old'
        self.new = self.root / 'new'
        self.old.mkdir()
        self.new.mkdir()
        for version, directory in [('1.0.0.0', self.old), ('1.0.0.1', self.new)]:
            for abi in ABIS:
                file = directory / f'Nyanime-Production-{version}-{abi}.apk'
                with zipfile.ZipFile(file, 'w') as apk:
                    apk.writestr('classes.dex', abi.encode() + b'original fixture' * 100000)
                    apk.writestr('version', version)
                    apk.writestr('assets/ota-capabilities.json', '{"schema":1,"releaseGraph":2,"maxSteps":8}')
        self.previous = prepare(self.old, '1.0.0.0', 284, self.application_id, self.repository)
        prepare_deltas([self.old], self.new, '1.0.0.1', 285)
        self.graph = prepare(self.new, '1.0.0.1', 285, self.application_id, self.repository,
                             self.previous, patch_only=True)

    def test_cold_client_unknown_hash_and_arbitrarily_old_install_reach_checkpoint_then_patch(self):
        plan = route(self.graph, ['arm64-v8a'], available={'0' * 64})
        self.assertIsNotNone(plan['base'].get('url'))
        self.assertEqual(284, plan['base']['versionCode'])
        self.assertEqual(1, len(plan['steps']))
        warm = route(self.graph, ['arm64-v8a'], available={plan['base']['sha256']})
        self.assertLess(warm['downloadBytes'], plan['downloadBytes'])

    def test_replay_fetches_base_and_reconstructs_every_abi_with_exact_published_hash(self):
        requests, verified = [], []
        def fetch(url, path, size):
            requests.append(url)
            file = self.old / url.rsplit('/', 1)[-1]
            self.assertEqual(size, file.stat().st_size)
            path.write_bytes(file.read_bytes())
        for digest in self.graph['targets']:
            output = self.root / 'replayed.apk'
            reconstruct(self.graph, digest, output, self.new, fetch,
                        lambda file, node: verified.append(node['versionCode']))
            self.assertEqual(digest, sha256(output))
        self.assertEqual(5, len(requests))
        self.assertEqual([284, 285] * 5, verified)

    def test_tampered_patch_or_base_never_yields_an_output(self):
        def fetch(url, path, size):
            path.write_bytes(b'wrong signed bytes')
        output = self.root / 'wrong.apk'
        with self.assertRaisesRegex(ValueError, 'hash mismatch'):
            reconstruct(self.graph, self.graph['targets'][0], output, self.new, fetch)
        self.assertFalse(output.exists())
        plan = route(self.graph, ['arm64-v8a'])
        (self.new / plan['steps'][0]['url'].rsplit('/', 1)[-1]).write_bytes(b'corrupt patch')
        with self.assertRaisesRegex(ValueError, 'Patch replay hash mismatch'):
            reconstruct(self.graph, plan['steps'][0]['to'], output, self.new,
                        lambda url, path, size: path.write_bytes((self.old / url.rsplit('/', 1)[-1]).read_bytes()))

    def test_hostile_schema_cycle_abi_url_unknown_identity_and_unreachable_paths_are_rejected(self):
        for change in [lambda g: g.update(schema=3), lambda g: g.update(applicationId='wrong'),
                       lambda g: g['packages'][0].update(url='https://elsewhere.test/base.apk'),
                       lambda g: g['patches'][0].update(to=g['patches'][0]['from']),
                       lambda g: g['patches'][0].update(size=True), lambda g: g.update(patches=[]),
                       lambda g: g.update(targets=['a' * 64])]:
            graph = copy.deepcopy(self.graph)
            change(graph)
            with self.assertRaises(ValueError):
                validate(graph, self.repository, self.application_id, '1.0.0.1')

    def test_bounded_loader_rejects_size_depth_and_unknown_fields(self):
        file = self.root / 'hostile.json'
        for text in [' ' * (MAX_BYTES + 1), '[' * 100,
                     json.dumps(self.graph | {'unknown': True})]:
            file.write_text(text)
            with self.assertRaises(ValueError):
                load(file, self.repository, self.application_id, '1.0.0.1')

    def test_reviewed_metadata_cannot_invent_a_full_current_apk_or_drop_architectures(self):
        nodes = {node['sha256']: node for node in self.graph['packages']}
        metadata = {'applicationId': self.application_id, 'versionName': '1.0.0.1', 'versionCode': 285,
                    'delivery': 'patches', 'sha256': {apk_name(nodes[h]): h for h in self.graph['targets']},
                    'graphSha256': {FILE_NAME: sha256(self.new / FILE_NAME)},
                    'deltaSha256': {edge['url'].rsplit('/', 1)[-1]: edge['sha256'] for edge in self.graph['patches']}}
        self.assertEqual(self.graph, publication_graph(self.new, metadata, self.repository))
        with self.assertRaises(ValueError):
            publication_graph(self.new, metadata | {'delivery': 'full'}, self.repository)
        with self.assertRaises(ValueError):
            publication_graph(self.new, metadata | {'sha256': {}}, self.repository)

    def test_patch_only_stays_gated_until_real_transition_is_verified(self):
        file = self.root / 'transition.json'
        file.write_text(json.dumps({'schema': 1, 'versionName': '1.0.0.0', 'versionCode': 284, 'verifiedUpdate': False}))
        with self.assertRaisesRegex(ValueError, 'full OTA transition'):
            check_transition(file)
        for node in self.previous['packages']:
            verify_capability(self.old / apk_name(node))
        with zipfile.ZipFile(self.root / 'old-client.apk', 'w') as apk:
            apk.writestr('classes.dex', 'old client')
        with self.assertRaises(KeyError):
            verify_capability(self.root / 'old-client.apk')


if __name__ == '__main__':
    unittest.main()
