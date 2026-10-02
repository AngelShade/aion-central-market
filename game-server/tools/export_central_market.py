"""Export this branch's source integration kit, hashes, and patch without runtime assets."""
import argparse
import hashlib
import subprocess
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
BASE = '267ce6033f39e8d297d2ac2657e5a6e930723578'
HASHES = 'docs/CENTRAL_MARKET_SHA256SUMS'


def git(*arguments):
    return subprocess.check_output(['git', '-C', str(ROOT), *arguments])


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--check', action='store_true', help='Verify the committed checksum list without writing it')
    args = parser.parse_args()
    paths = set(git('diff', '--name-only', BASE).decode().splitlines())
    paths.update(git('ls-files', '--others', '--exclude-standard').decode().splitlines())
    paths.add('LICENSE')
    paths.discard(HASHES)
    allowed = {'.gitignore', 'README.md', 'LICENSE'}
    for name in paths:
        path = ROOT / name
        if not path.is_file():
            raise ValueError('Missing source: ' + name)
        if name not in allowed and not name.startswith(('client-mods/', 'docs/', 'game-server/')):
            raise ValueError('Unexpected source path: ' + name)
        if any(part in ('target', '__pycache__', 'backups') for part in path.relative_to(ROOT).parts):
            raise ValueError('Runtime output in source kit: ' + name)
        if path.suffix.lower() in ('.dll', '.exe', '.pak', '.sig', '.key', '.jar', '.class', '.pyc', '.zip', '.rar'):
            raise ValueError('Compiled/client/deployment asset in source kit: ' + name)
        if path.suffix.lower() == '.png' and name != 'client-mods/market-shortcut/assets/scales-outlined.png':
            raise ValueError('Only original generated HUD artwork belongs in this kit: ' + name)
    # Git blobs keep checksums and exports identical across LF/CRLF checkouts.
    # Stage new/edited files first; exporting never stages or commits them.
    contents = {name: git('show', ':' + name) for name in sorted(paths)}
    hashes = ''.join(hashlib.sha256(data).hexdigest() + '  ' + name + '\n' for name, data in contents.items()).encode()
    checksum = ROOT / HASHES
    if args.check:
        assert checksum.read_text(encoding='utf-8').encode() == hashes, 'Source checksums changed; regenerate and review before publishing'
    else:
        checksum.write_bytes(hashes)
    contents[HASHES] = hashes
    contents['central-market.patch'] = git('diff', '--binary', BASE)
    contents['SOURCE_KIT.md'] = b'''# Central Market source integration kit

This archive contains the market branch's changed source files, generated HUD artwork,
source checksums, and a binary-safe Git patch. No original client assets, compiled
libraries, player databases, credentials, or signing keys are included.

The full buildable checkout is https://github.com/AngelShade/aion-central-market .
For integration, start with Beyond Aion upstream commit
267ce6033f39e8d297d2ac2657e5a6e930723578 and run `git apply --check central-market.patch`.
Apply only after reviewing conflicts against your emulator. See README.md and
docs/CENTRAL_MARKET.md for building, simulation, client preparation and deployment.
Client outputs and server update packages must be built locally for your installation.
'''
    output = args.output.resolve()
    if output == ROOT or output.suffix.lower() != '.zip':
        raise ValueError('Output must be a ZIP file')
    output.parent.mkdir(parents=True, exist_ok=True)
    with zipfile.ZipFile(output, 'w', zipfile.ZIP_DEFLATED) as archive:
        for name, data in sorted(contents.items()):
            info = zipfile.ZipInfo(name, (2026, 10, 2, 0, 0, 0))
            info.compress_type = zipfile.ZIP_DEFLATED
            archive.writestr(info, data)
    with zipfile.ZipFile(output) as archive:
        assert archive.testzip() is None
        assert set(archive.namelist()) == set(contents)
        for name, data in contents.items():
            assert archive.read(name) == data
    digest = hashlib.sha256(output.read_bytes()).hexdigest()
    output.with_suffix('.zip.sha256').write_text(digest + '  ' + output.name + '\n', encoding='utf-8')
    print(f'PASS: {len(contents)} source-kit entries; CRC, contents, and SHA-256 verified.')


if __name__ == '__main__':
    main()
