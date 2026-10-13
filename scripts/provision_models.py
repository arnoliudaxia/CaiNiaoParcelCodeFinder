#!/usr/bin/env python
"""Provision ignored Android model assets; stdlib only. Never extracts untrusted tar paths.
python scripts/provision_models.py [--cache .model-cache]
Existing cache archives use NAME_STAGE.tar; downloads and every installed file are SHA256 checked.
"""
import argparse
import hashlib
import json
from pathlib import Path
import tarfile
import urllib.request


def sha256(data):
    return hashlib.sha256(data).hexdigest()


def main():
    root = Path(__file__).resolve().parents[1]
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--cache', type=Path, default=root / '.model-cache')
    args = parser.parse_args()
    args.cache.mkdir(parents=True, exist_ok=True)
    manifest = json.loads((root / 'scripts/models-manifest.json').read_text(encoding='utf-8'))
    for entry in manifest['archives']:
        archive = args.cache / entry['cache_name']
        if not archive.exists():
            temporary = archive.with_suffix('.download')
            urllib.request.urlretrieve(entry['url'], temporary)
            if sha256(temporary.read_bytes()) != entry['sha256']:
                temporary.unlink()
                raise ValueError(f'Archive SHA256 mismatch: {entry["url"]}')
            temporary.replace(archive)
        if sha256(archive.read_bytes()) != entry['sha256']:
            raise ValueError(f'Archive SHA256 mismatch: {archive}')
        with tarfile.open(archive) as tar:
            for asset in entry['files']:
                stream = tar.extractfile(asset['member'])
                if stream is None:
                    raise ValueError(f'Missing member: {asset["member"]}')
                with stream:
                    data = stream.read()
                if sha256(data) != asset['sha256']:
                    raise ValueError(f'Asset SHA256 mismatch: {asset["member"]}')
                target = root / 'ppocr-sdk/src/main/assets' / asset['asset']
                target.parent.mkdir(parents=True, exist_ok=True)
                temporary = target.with_suffix(target.suffix + '.tmp')
                temporary.write_bytes(data)
                temporary.replace(target)
                print(f'{asset["asset"]}: {len(data):,} bytes (verified)')


if __name__ == '__main__':
    main()
