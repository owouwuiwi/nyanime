"""Encrypt the local Telegram application credentials into GitHub Actions secrets."""
import base64
import json
from pathlib import Path
import re
import subprocess
import urllib.error
import urllib.request


def main():
    from nacl.public import PublicKey, SealedBox

    root = Path(__file__).resolve().parents[1]
    path = root / ".local/telegram.properties"
    if not path.is_file():
        raise RuntimeError("Use configure_telegram.ps1 before configuring CI")
    settings = dict(line.split("=", 1) for line in path.read_text().splitlines() if "=" in line)
    api_id, api_hash = settings.get("api_id", ""), settings.get("api_hash", "")
    if not api_id.isdigit() or int(api_id) <= 0 or not re.fullmatch("[a-fA-F0-9]{32}", api_hash):
        raise RuntimeError("Invalid private Telegram configuration")
    remote = subprocess.check_output(["git", "remote", "get-url", "origin"], cwd=root, text=True).strip()
    match = re.fullmatch(r"https://github.com/([\w.-]+/[\w.-]+?)(?:\.git)?", remote)
    if not match:
        raise RuntimeError("Expected a GitHub HTTPS origin")
    credentials = subprocess.run(["git", "credential", "fill"],
        input="protocol=https\nhost=github.com\n\n", capture_output=True, text=True, cwd=root, check=True)
    values = dict(line.split("=", 1) for line in credentials.stdout.splitlines() if "=" in line)
    token = values.get("password", "")
    if not token:
        raise RuntimeError("GitHub authentication is unavailable")
    base = "https://api.github.com/repos/" + match.group(1) + "/actions/secrets"

    def request(url, payload=None):
        headers = {"Authorization": "Bearer " + token, "Accept": "application/vnd.github+json", "User-Agent": "Nyanime-build-setup"}
        body = json.dumps(payload).encode() if payload else None
        try:
            with urllib.request.urlopen(urllib.request.Request(url, data=body, headers=headers,
                method="PUT" if payload else "GET"), timeout=30) as response:
                data = response.read()
                return json.loads(data) if data else None
        except urllib.error.HTTPError as error:
            raise RuntimeError("GitHub secret configuration failed: HTTP " + str(error.code)) from None

    key = request(base + "/public-key")
    cipher = SealedBox(PublicKey(base64.b64decode(key["key"])))
    for name, value in {"TELEGRAM_API_ID": api_id, "TELEGRAM_API_HASH": api_hash}.items():
        request(base + "/" + name, {"key_id": key["key_id"],
            "encrypted_value": base64.b64encode(cipher.encrypt(value.encode())).decode()})
        print("Configured encrypted repository secret: " + name)


if __name__ == "__main__":
    main()
