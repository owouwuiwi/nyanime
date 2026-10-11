"""Reject a release APK with the wrong package, signing certificate or version."""
import argparse
from pathlib import Path
import re
import subprocess


def verify(apk, apksigner, aapt, identity, version, code):
    signing = subprocess.check_output([str(apksigner), 'verify', '--verbose', '--print-certs', str(apk)], text=True)
    certificates = re.findall(r'^Signer #\d+ certificate SHA-256 digest: ([a-fA-F0-9]{64})$', signing, re.MULTILINE)
    if len(certificates) != 1 or certificates[0].upper() != identity['certificateSha256']:
        raise ValueError('Unexpected release signing certificate')
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
