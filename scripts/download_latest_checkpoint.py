"""
Download the latest AION checkpoint from GitHub releases (if any exists).
Used by the AION-SWARM workflow to bootstrap shard training from the
previous generation instead of starting from scratch every run.
"""
import os
import sys
import json
import urllib.request


def main():
    token = os.environ.get('GH_TOKEN', '')
    repo  = os.environ.get('REPO', '')
    if not token or not repo:
        print("Missing GH_TOKEN or REPO env vars, skipping download")
        return

    headers = {'Authorization': f'token {token}',
               'Accept': 'application/vnd.github+json'}

    try:
        req = urllib.request.Request(
            f'https://api.github.com/repos/{repo}/releases/latest',
            headers=headers)
        with urllib.request.urlopen(req, timeout=15) as r:
            release = json.loads(r.read())
    except Exception as e:
        print(f"No previous release found ({e}), starting fresh")
        return

    tag = release.get('tag_name', '')
    print(f"Latest release: {tag}")

    os.makedirs('checkpoints', exist_ok=True)
    downloaded = 0
    for asset in release.get('assets', []):
        name = asset['name']
        if name.endswith('.pt') or name == 'config.json' or name == 'tokenizer.json':
            url = asset['browser_download_url']
            dest = os.path.join('checkpoints', name)
            try:
                req = urllib.request.Request(url, headers=headers)
                with urllib.request.urlopen(req, timeout=60) as r, \
                     open(dest, 'wb') as f:
                    f.write(r.read())
                size_kb = os.path.getsize(dest) / 1024
                print(f"  Downloaded: {name} ({size_kb:.0f}KB)")
                downloaded += 1
            except Exception as e:
                print(f"  Failed to download {name}: {e}")

    print(f"Downloaded {downloaded} files from {tag}")


if __name__ == '__main__':
    main()
