"""Production signing: private material stays outside Git and never enters command arguments."""
import argparse
import ctypes
import hashlib
import json
import os
from pathlib import Path
import secrets
import shutil
import subprocess

ROOT = Path(__file__).resolve().parents[1]
PRIVATE = ROOT / '.local' / 'signing'
ALIAS = 'nyanime-production'


def protect(data, decrypt=False):
    if os.name != 'nt':
        raise RuntimeError('Local signing credentials use Windows DPAPI; CI must supply environment secrets')
    from ctypes import wintypes

    class Blob(ctypes.Structure):
        _fields_ = [('size', wintypes.DWORD), ('data', ctypes.POINTER(ctypes.c_ubyte))]

    raw = ctypes.create_string_buffer(data)
    source = Blob(len(data), ctypes.cast(raw, ctypes.POINTER(ctypes.c_ubyte)))
    target = Blob()
    library = ctypes.WinDLL('crypt32', use_last_error=True)
    function = library.CryptUnprotectData if decrypt else library.CryptProtectData
    function.argtypes = [ctypes.POINTER(Blob), ctypes.c_void_p, ctypes.c_void_p,
                         ctypes.c_void_p, ctypes.c_void_p, wintypes.DWORD, ctypes.POINTER(Blob)]
    function.restype = wintypes.BOOL
    if not function(ctypes.byref(source), None, None, None, None, 1, ctypes.byref(target)):
        raise ctypes.WinError(ctypes.get_last_error())
    try:
        return ctypes.string_at(target.data, target.size)
    finally:
        kernel = ctypes.WinDLL('kernel32', use_last_error=True)
        kernel.LocalFree.argtypes = [ctypes.c_void_p]
        kernel.LocalFree(ctypes.cast(target.data, ctypes.c_void_p))


def credentials():
    return json.loads(protect((PRIVATE / 'credentials.dpapi').read_bytes(), decrypt=True))


def signing_environment():
    values = credentials()
    return {**os.environ, 'NYANIME_SIGNING_STORE_FILE': str(PRIVATE / 'production.p12'),
            'NYANIME_SIGNING_STORE_PASSWORD': values['store'],
            'NYANIME_SIGNING_KEY_PASSWORD': values['store'], 'NYANIME_SIGNING_KEY_ALIAS': ALIAS}


def setup(keytool):
    PRIVATE.mkdir(parents=True, exist_ok=True)
    if (PRIVATE / 'production.p12').exists() or (PRIVATE / 'credentials.dpapi').exists():
        raise RuntimeError('Production identity already exists; refusing to replace it')
    password = secrets.token_urlsafe(48)
    env = {**os.environ, 'NYANIME_KEY_PASSWORD': password}
    subprocess.run([keytool, '-genkeypair', '-noprompt', '-storetype', 'PKCS12',
                    '-keystore', str(PRIVATE / 'production.p12'), '-alias', ALIAS,
                    '-keyalg', 'RSA', '-keysize', '4096', '-validity', '14610',
                    '-dname', 'CN=Nyanime Production, OU=Android, O=Nyanime',
                    '-storepass:env', 'NYANIME_KEY_PASSWORD', '-keypass:env', 'NYANIME_KEY_PASSWORD'],
                   env=env, check=True, capture_output=True)
    (PRIVATE / 'credentials.dpapi').write_bytes(protect(json.dumps({'store': password}).encode()))
    certificate = subprocess.check_output([keytool, '-exportcert', '-keystore',
        str(PRIVATE / 'production.p12'), '-alias', ALIAS, '-storepass:env', 'NYANIME_KEY_PASSWORD'], env=env)
    (PRIVATE / 'production.der').write_bytes(certificate)
    fingerprint = hashlib.sha256(certificate).hexdigest().upper()
    backup = Path(os.environ['USERPROFILE']) / '.nyanime' / 'signing-backup'
    backup.mkdir(parents=True, exist_ok=True)
    for name in ('production.p12', 'credentials.dpapi', 'production.der'):
        shutil.copy2(PRIVATE / name, backup / name)
    username = subprocess.check_output(['whoami'], text=True).strip()
    for directory in (PRIVATE, backup):
        subprocess.run(['icacls', str(directory), '/inheritance:r', '/grant:r',
                        f'{username}:(OI)(CI)F'], check=True, capture_output=True)
    print(json.dumps({'certificateSha256': fingerprint, 'algorithm': 'RSA-4096',
                      'debugCertificate': False, 'protectedBackup': str(backup)}))


def main():
    parser = argparse.ArgumentParser()
    commands = parser.add_subparsers(dest='action', required=True)
    create = commands.add_parser('setup')
    create.add_argument('--keytool', required=True)
    run = commands.add_parser('run')
    run.add_argument('command', nargs=argparse.REMAINDER)
    args = parser.parse_args()
    if args.action == 'setup':
        setup(args.keytool)
    else:
        command = args.command[1:] if args.command[:1] == ['--'] else args.command
        if not command:
            parser.error('A build or signing command is required')
        raise SystemExit(subprocess.call(command, env=signing_environment()))


if __name__ == '__main__':
    main()
