"""Reject a release APK with the wrong package, signing certificate or version."""
import argparse
from pathlib import Path
import re
import subprocess


def verify_signing_certificate(signing, expected):
    """Accept known apksigner 36/37 labels, never an extra or unrecognized app signer."""
    if not re.fullmatch(r'[a-fA-F0-9]{64}', expected):
        raise ValueError('Invalid expected signing identity')
    certificates = []
    for line in signing.splitlines():
        line = line.strip()
        if 'certificate SHA-256 digest:' not in line:
            continue
        # A source stamp is not the identity Android uses to install/update this application.
        if line.startswith('Source Stamp Signer: certificate SHA-256 digest: '):
            continue
        signer = re.fullmatch(
            r'(?:Signer #1|V(?:1|2|3\.0) Signer(?: #1)?):? certificate SHA-256 digest: ([a-fA-F0-9]{64})',
            line,
        )
        if signer is None:
            raise ValueError('Unrecognized or additional APK signing identity: ' + line)
        certificates.append(signer[1].upper())
    if certificates != [expected.upper()]:
        raise ValueError('Unexpected release signing certificate')


def verify(apk, apksigner, aapt, identity, version, code):
    signing = subprocess.check_output([str(apksigner), 'verify', '--verbose', '--print-certs', str(apk)], text=True)
    verify_signing_certificate(signing, identity['certificateSha256'])
    badging = subprocess.check_output([str(aapt), 'dump', 'badging', str(apk)], text=True)
    header = re.search(r"^package: name='([^']+)' versionCode='(\d+)' versionName='([^']+)'", badging, re.MULTILINE)
    if not header or header.groups() != (identity['applicationId'], str(code), version):
        raise ValueError('Unexpected release package or version')
    if re.search(r'^application-debuggable\b', badging, re.MULTILINE):
        raise ValueError('A debuggable APK cannot be published')


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--apksigner', required=True, type=Path)
    parser.add_argument('--aapt', required=True, type=Path)
    parser.add_argument('--version', required=True)
    parser.add_argument('--code', required=True, type=int)
    parser.add_argument('apks', nargs='+', type=Path)
    args = parser.parse_args()
    identity = dict(line.split('=', 1) for line in (Path(__file__).resolve().parents[1] / 'app-identity.properties').read_text().splitlines() if line.strip())
    for apk in args.apks:
        verify(apk, args.apksigner, args.aapt, identity, args.version, args.code)
        print('Verified production package: ' + apk.name)
