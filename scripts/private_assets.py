"""Public release boundary for assets intentionally restricted to private experiments."""
import zipfile


def verify_public_apk(apk):
    with zipfile.ZipFile(apk) as archive:
        forbidden = [name for name in archive.namelist() if
                     name.startswith('assets/private/')]
    if forbidden:
        raise ValueError('Public distribution blocked: private experimental assets are present: ' + ', '.join(forbidden))


def verify_public_source_entries(names):
    if any('/src/main/assets/private/' in name for name in names):
        raise ValueError('Public source export blocked: private experimental assets are present')
