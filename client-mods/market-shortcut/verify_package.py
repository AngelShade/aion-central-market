"""Verify standalone archive preservation and install/restore on a disposable client."""
import copy
import hashlib
import json
import os
import shutil
import subprocess
import sys
import time
from pathlib import Path
from xml.etree import ElementTree as ET

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE.parent / 'central-market'))
from codec import read_pak, binary_xml

sha = lambda path: hashlib.sha256(path.read_bytes()).hexdigest()


def main():
    output = Path(sys.argv[1]).resolve()
    manifest = json.loads((output / 'manifest.json').read_text())
    root = Path(manifest['clientRoot'])
    assert manifest['marketHud'] and len(manifest['files']) == 16
    assert len({entry['path'] for entry in manifest['files']}) == 16
    for entry in manifest['files']:
        assert sha(output / entry['path']) == entry['staged']
        if entry['original'] is not None:
            assert sha(root / entry['path']) == entry['original']
    allowed = {
        'Data/ui/ui.pak': {'UI_Preload.xml'},
        'Textures/ui/ui.pak': {'mkt_scales.dds'},
        'L10N/enu/Data/data.pak': {'ui/ui_preload.xml', 'ui/game_hud_s1/start_dialog.xml', 'ui/game_hud_s2/start_dialog.xml'},
        'Data/ui/game_hud_s1/game_hud_s1.pak': {'start_dialog.xml'},
        'Data/ui/game_hud_s2/game_hud_s2.pak': {'start_dialog.xml'},
    }
    for relative, changed in allowed.items():
        with read_pak(root / relative) as before, read_pak(output / relative) as after:
            assert after.testzip() is None
            assert set(after.namelist()) == set(before.namelist()) | changed
            for name in before.namelist():
                if name not in changed:
                    assert before.read(name) == after.read(name), (relative, name)
            for name in changed:
                if not name.endswith('.xml'):
                    continue
                old, new = binary_xml(before.read(name)), binary_xml(after.read(name))
                if name.endswith('start_dialog.xml'):
                    button = new.find("./Widget[@name='central_market_button']")
                    assert button is not None and button.get('preset') == 'mkt_scales_button'
                    new.remove(button)
                else:
                    for parent in new.iter():
                        for child in list(parent):
                            if child.get('name', '').startswith('mkt_scales'):
                                parent.remove(child)
                assert ET.tostring(old) == ET.tostring(new), (relative, name)
    with read_pak(output / 'Plugin/RelicCalc/RelicCalc.pak') as addon:
        lua = addon.read('PrivateMenus.lua')
        assert b'{label = "Central Market"' not in lua
        assert b'SLASH_PRIVATEWAREHOUSE1 = "/privatewarehouse"' in lua
        assert b'LoadUrlWithWebAuth(PRIVATE_CENTRAL_MARKET_URL)' in lua

    fixture = output.parent / ('market-install-check-' + str(time.time_ns()))
    prepared = fixture / 'prepared'
    prepared.mkdir(parents=True)
    for relative in ('bin64/Awesomium.dll', 'Data/Items/Items.pak'):
        destination = fixture / relative
        destination.parent.mkdir(parents=True, exist_ok=True)
        os.link(root / relative, destination)
    for entry in manifest['files']:
        destination = prepared / entry['path']
        destination.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(output / entry['path'], destination)
        if entry['original'] is not None:
            destination = fixture / entry['path']
            destination.parent.mkdir(parents=True, exist_ok=True)
            shutil.copy2(root / entry['path'], destination)
    manifest = copy.deepcopy(manifest)
    manifest['clientRoot'] = str(fixture)
    (prepared / 'manifest.json').write_text(json.dumps(manifest))
    environment = dict(os.environ)
    environment['PSModulePath'] = str(Path(os.environ['SystemRoot']) / 'System32/WindowsPowerShell/v1.0/Modules')

    def run(script, arguments):
        # The test targets its own disposable files. Model a closed Aion process
        # without requiring the real player to exit their unrelated running client.
        harness = fixture / 'run-check.ps1'
        quote = lambda value: "'" + str(value).replace("'", "''") + "'"
        harness.write_text("$ErrorActionPreference='Stop'\nfunction Get-Process { param($Name) @() }\n& " +
                           quote(script) + ' ' + ' '.join(str(value) if str(value).startswith('-') else quote(value) for value in arguments) + '\n')
        result = subprocess.run(['powershell.exe', '-NoProfile', '-ExecutionPolicy', 'Bypass', '-File', str(harness)],
                                capture_output=True, text=True, env=environment)
        return result

    installer = HERE.parent / 'central-market/Install.ps1'
    result = run(installer, ['-ClientPath', fixture, '-PreparedPath', prepared])
    assert result.returncode == 0, (result.stdout, result.stderr)
    backup = max((fixture / 'TransmogMenu-backups').iterdir(), key=lambda path: path.name)
    for entry in manifest['files']:
        assert sha(fixture / entry['path']) == entry['staged']
        if entry['original'] is not None:
            assert sha(backup / entry['path']) == entry['original']
    before = {entry['path']: sha(fixture / entry['path']) for entry in manifest['files']}
    result = run(installer, ['-ClientPath', fixture, '-PreparedPath', prepared])
    assert result.returncode != 0 and 'File changed since preparation' in result.stderr
    assert all(sha(fixture / path) == digest for path, digest in before.items())
    result = run(HERE.parent / 'central-market/Restore.ps1', ['-ClientPath', fixture, '-BackupPath', backup])
    assert result.returncode == 0, (result.stdout, result.stderr)
    for entry in manifest['files']:
        if entry['original'] is None:
            assert not (fixture / entry['path']).exists()
        else:
            assert sha(fixture / entry['path']) == entry['original']
    print('PASS: 16-file manifest, untouched native archive entries, authenticated HUD registration, installer, backups, changed-client rejection, and exact restore.')


if __name__ == '__main__':
    main()
