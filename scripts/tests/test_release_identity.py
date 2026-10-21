import os
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
from scripts.verify_release_identity import verify, verify_signing_certificate


class ReleaseIdentityTest(unittest.TestCase):
    identity = {'applicationId': 'app.example.production', 'certificateSha256': 'A' * 64}

    def outputs(self):
        return ['Signer #1 certificate SHA-256 digest: ' + 'a' * 64,
                "package: name='app.example.production' versionCode='200' versionName='1.2.3.4'\n"]

    def test_signed_production_apk_is_accepted(self):
        for label in ('Signer #1', 'V2 Signer:'):
            outputs = self.outputs()
            outputs[0] = outputs[0].replace('Signer #1', label)
            with self.subTest(label=label), patch('scripts.verify_release_identity.subprocess.check_output', side_effect=outputs):
                verify('app.apk', 'apksigner', 'aapt', self.identity, '1.2.3.4', 200)

    def test_legacy_package_certificate_missing_signer_extra_signer_and_wrong_version_are_rejected(self):
        for problem in ('package', 'certificate', 'missing', 'multiple', 'version', 'code', 'debug'):
            output = self.outputs()
            if problem == 'package': output[1] = output[1].replace('production', 'previous')
            elif problem == 'certificate': output[0] = output[0].replace('a' * 64, 'b' * 64)
            elif problem == 'missing': output[0] = 'No signers'
            elif problem == 'multiple': output[0] += '\nSigner #2 certificate SHA-256 digest: ' + 'a' * 64
            elif problem == 'version': output[1] = output[1].replace('1.2.3.4', '1.2.3.3')
            elif problem == 'code': output[1] = output[1].replace('200', '199')
            else: output[1] += 'application-debuggable\n'
            with self.subTest(problem=problem), patch('scripts.verify_release_identity.subprocess.check_output', side_effect=output):
                with self.assertRaises(ValueError):
                    verify('app.apk', 'apksigner', 'aapt', self.identity, '1.2.3.4', 200)

    def test_signature_verification_failure_is_not_swallowed(self):
        import subprocess
        with patch('scripts.verify_release_identity.subprocess.check_output', side_effect=subprocess.CalledProcessError(1, 'apksigner')):
            with self.assertRaises(subprocess.CalledProcessError):
                verify('app.apk', 'apksigner', 'aapt', self.identity, '1.2.3.4', 200)

    def test_sdk_37_scheme_labels_and_legacy_output_keep_the_same_pinned_certificate(self):
        for label in ('Signer #1', 'Signer #1:', 'V1 Signer:', 'V2 Signer:', 'V3.0 Signer:', 'V2 Signer #1:'):
            with self.subTest(label=label):
                verify_signing_certificate(label + ' certificate SHA-256 digest: ' + 'a' * 64, 'A' * 64)

    def test_unknown_signers_multiple_signers_wrong_digests_and_malformed_fingerprints_fail_closed(self):
        valid = 'V2 Signer: certificate SHA-256 digest: ' + 'a' * 64
        invalid = (
            valid.replace('a' * 64, 'b' * 64),
            valid + '\nV2 Signer #2: certificate SHA-256 digest: ' + 'a' * 64,
            valid + '\n' + valid,
            valid + '\nV99 Signer: certificate SHA-256 digest: ' + 'a' * 64,
            valid.replace('a' * 64, 'a' * 63),
            valid + ' extra',
            valid.replace('V2 Signer:', 'Signer #0:'),
            valid.replace('V2 Signer:', 'Unrecognized Signer:'),
        )
        for output in invalid:
            with self.subTest(output=output), self.assertRaises(ValueError):
                verify_signing_certificate(output, 'A' * 64)

    def test_source_stamp_or_public_key_cannot_substitute_for_the_application_certificate(self):
        stamp = 'Source Stamp Signer: certificate SHA-256 digest: ' + 'a' * 64
        key = 'V2 Signer: public key SHA-256 digest: ' + 'a' * 64
        for output in (stamp, key):
            with self.subTest(output=output), self.assertRaises(ValueError):
                verify_signing_certificate(output, 'A' * 64)
        verify_signing_certificate('V2 Signer: certificate SHA-256 digest: ' + 'a' * 64 + '\n' + stamp, 'A' * 64)

    def test_graph_verifier_supports_sdk_37_and_still_checks_package_version_and_abi(self):
        from scripts.release_graph import verify_android
        with tempfile.TemporaryDirectory() as temporary:
            sdk = Path(temporary)
            tools = sdk / 'build-tools/37.0.0'
            (tools / 'lib').mkdir(parents=True)
            (tools / 'lib/apksigner.jar').touch()
            (tools / ('aapt.exe' if os.name == 'nt' else 'aapt')).touch()
            node = dict(versionCode=200, versionName='1.2.3.4', abi='arm64-v8a')
            signing = 'V2 Signer: certificate SHA-256 digest: ' + 'a' * 64
            badging = self.outputs()[1] + "native-code: 'arm64-v8a'\n"
            for problem in (None, 'certificate', 'package', 'code', 'version', 'abi', 'signature'):
                output = [signing, badging]
                if problem == 'certificate': output[0] = signing.replace('a' * 64, 'b' * 64)
                elif problem == 'package': output[1] = badging.replace('production', 'previous')
                elif problem == 'code': output[1] = badging.replace('200', '199')
                elif problem == 'version': output[1] = badging.replace('1.2.3.4', '1.2.3.3')
                elif problem == 'abi': output[1] = badging.replace('arm64-v8a', 'x86')
                elif problem == 'signature':
                    import subprocess
                    output = subprocess.CalledProcessError(1, 'apksigner')
                with self.subTest(problem=problem), patch.dict(os.environ, {'ANDROID_SDK_ROOT': str(sdk)}), patch(
                    'scripts.release_graph.subprocess.check_output', side_effect=output,
                ):
                    if problem == 'signature':
                        with self.assertRaises(subprocess.CalledProcessError):
                            verify_android('app.apk', node, self.identity['applicationId'], 'A' * 64)
                    elif problem:
                        with self.assertRaises(ValueError):
                            verify_android('app.apk', node, self.identity['applicationId'], 'A' * 64)
                    else:
                        verify_android('app.apk', node, self.identity['applicationId'], 'A' * 64)
