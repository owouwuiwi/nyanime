#!/usr/bin/env python3
"""Hand a verified private build to the public bot publisher and await its result."""
import json
import os
import time
import urllib.request
import urllib.error
import subprocess
import re
from pathlib import Path
from urllib.parse import urlparse

PUBLIC_REPOSITORY = 'owouwuiwi/nyanime'
SOURCE_REPOSITORY = 'owouwuiwi/nyanime-source'

class SafeRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        redirected = super().redirect_request(req, fp, code, msg, headers, newurl)
        if redirected is not None and urlparse(req.full_url).hostname != urlparse(newurl).hostname:
            redirected.remove_header('Authorization')
        return redirected


def api(path, token, method='GET', data=None, binary=False):
    request = urllib.request.Request('https://api.github.com/' + path,
        headers={'Authorization': 'Bearer ' + token,
                 'Accept': 'application/octet-stream' if binary else 'application/vnd.github+json',
                 'Cache-Control': 'no-cache',
                 'X-GitHub-Api-Version': '2022-11-28', 'User-Agent': 'Nyanime-release'},
        data=json.dumps(data).encode() if data is not None else None, method=method)
    with urllib.request.build_opener(SafeRedirect()).open(request, timeout=120) as response:
        content = response.read()
        return content if binary else (json.loads(content) if content else None)

def remove_published_artifact(artifact_id, token):
    """Release intermediate storage only after the public publisher succeeds."""
    path = f'repos/{SOURCE_REPOSITORY}/actions/artifacts/{int(artifact_id)}'
    remove_published_temporary(path, token)


def remove_published_temporary(path, token):
    for attempt in range(3):
        try:
            api(path, token, 'DELETE')
            print('Published APKs retained in Releases; temporary publication bundle removed.')
            return
        except urllib.error.HTTPError as error:
            if error.code == 404:
                return  # A concurrent cleanup already removed this exact artifact.
            if error.code in (429, 500, 502, 503, 504) and attempt < 2:
                time.sleep(2 ** (attempt + 1))
                continue
            reason = f'HTTP {error.code}'
        except OSError:
            if attempt < 2:
                time.sleep(2 ** (attempt + 1))
                continue
            reason = 'network error'
        # Cleanup failure must not turn a verified public release into a failure.
        print(f'::warning::Public release is available; temporary bundle cleanup failed ({reason}).')
        return


def staging_payload(path, run_id, version, revision):
    stage = json.loads(Path(path).read_text())
    if set(stage) != {'release_id', 'asset_id', 'bundle_sha256', 'source_run', 'source_attempt', 'source_sha', 'version'}:
        raise ValueError('Unexpected publication staging metadata')
    for name in ('release_id', 'asset_id', 'source_run', 'source_attempt'):
        if type(stage[name]) is not int or stage[name] <= 0:
            raise ValueError('Invalid publication staging identifier')
    if stage['source_run'] != run_id or stage['source_sha'] != revision or stage['version'] != version:
        raise ValueError('Publication staging belongs to another build')
    if not re.fullmatch('[a-f0-9]{64}', stage['bundle_sha256']):
        raise ValueError('Invalid publication bundle hash')
    return stage


def main():
    if os.environ['GITHUB_REPOSITORY'] != SOURCE_REPOSITORY:
        raise SystemExit('Build publication is allowed only from the private source repository')
    run_id = int(os.environ['GITHUB_RUN_ID'])
    token = os.environ['PUBLIC_RELEASE_TOKEN']
    version = os.environ['VERSION']
    if os.environ.get('PUBLICATION_STAGING'):
        stage = staging_payload(os.environ['PUBLICATION_STAGING'], run_id, version, os.environ['GITHUB_SHA'])
        payload = {key: stage[key] for key in ('source_run', 'source_attempt', 'release_id', 'asset_id', 'bundle_sha256', 'version')}
        cleanup = f'repos/{SOURCE_REPOSITORY}/releases/{stage["release_id"]}'
    else:
        # Older already-prepared runs can still be retried through their artifacts.
        artifacts = api(f'repos/{SOURCE_REPOSITORY}/actions/runs/{run_id}/artifacts', os.environ['GITHUB_TOKEN'])['artifacts']
        bundle = next(item for item in artifacts if item['name'] == os.environ['PUBLICATION_ARTIFACT'])
        payload = {'source_run': run_id, 'artifact_id': bundle['id'], 'version': version}
        cleanup = f'repos/{SOURCE_REPOSITORY}/actions/artifacts/{bundle["id"]}'
    runs_path = f'repos/{PUBLIC_REPOSITORY}/actions/workflows/publish.yml/runs?event=repository_dispatch&per_page=30'
    # A retry must not accept an earlier successful run of the same source build
    # and remove its new staging bundle before the new publisher has opened it.
    previous_runs = {item['id'] for item in api(runs_path, token)['workflow_runs']}
    api(f'repos/{PUBLIC_REPOSITORY}/dispatches', token, 'POST', {
        'event_type': 'publish-nyanime',
        'client_payload': payload,
    })
    deadline = time.monotonic() + 1200
    title = f'Publish Nyanime {version} from build {run_id}'
    while time.monotonic() < deadline:
        runs = api(runs_path, token)['workflow_runs']
        run = next((item for item in runs if item['id'] not in previous_runs and item['display_title'] == title), None)
        if run and run['status'] == 'completed':
            if run['conclusion'] != 'success':
                raise SystemExit('Public publication failed: ' + run['html_url'])
            for tag in (os.environ['TAG'],):
                previous = subprocess.run(['git', 'rev-parse', '-q', '--verify', f'{tag}^{{commit}}'], text=True, capture_output=True)
                if previous.returncode == 0:
                    if previous.stdout.strip() != os.environ['GITHUB_SHA']:
                        raise SystemExit('Private release tag belongs to another source revision')
                else:
                    subprocess.run(['git', 'tag', tag, os.environ['GITHUB_SHA']], check=True)
                    subprocess.run(['git', 'push', 'origin', f'refs/tags/{tag}'], check=True)
            remove_published_temporary(cleanup, os.environ['GITHUB_TOKEN'])
            print('Public OTA release verified:', f'https://github.com/{PUBLIC_REPOSITORY}/releases/tag/{os.environ["TAG"]}')
            return
        time.sleep(20)
    if os.environ.get('PUBLICATION_STAGING'):
        raise SystemExit('Public publisher did not complete; verified bundle remains in private staging for retry')
    raise SystemExit('Public publisher did not complete; APK artifact remains in the private build for retry')

if __name__ == '__main__':
    main()
