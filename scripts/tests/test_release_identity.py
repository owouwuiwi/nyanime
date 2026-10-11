import unittest
from unittest.mock import patch
from scripts.verify_release_identity import verify


class ReleaseIdentityTest(unittest.TestCase):
    identity = {'applicationId': 'app.example.production', 'certificateSha256': 'A' * 64}

    def outputs(self):
        return ['Signer #1 certificate SHA-256 digest: ' + 'a' * 64,
                "package: name='app.example.production' versionCode='200' versionName='1.2.3.4'\n"]

    def test_signed_production_apk_is_accepted(self):
        with patch('scripts.verify_release_identity.subprocess.check_output', side_effect=self.outputs()):
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
