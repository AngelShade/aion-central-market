"""Stage the Market HUD shortcut into the standalone, original-client package."""
import copy
import io
import os
import struct
import subprocess
import sys
import zipfile
from pathlib import Path
from xml.etree import ElementTree as ET
from PIL import Image, ImageEnhance

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE.parent / 'central-market'))
from codec import read_pak, encode_pak, binary_xml, encode_binary_xml
from patch_binary import patch


def rewrite(source, replacements):
    data = io.BytesIO()
    with read_pak(source) as original, zipfile.ZipFile(data, 'w', zipfile.ZIP_DEFLATED) as output:
        for info in original.infolist():
            output.writestr(info, replacements.get(info.filename, original.read(info.filename)))
        for name, payload in replacements.items():
            if name not in original.namelist():
                output.writestr(name, payload)
    return encode_pak(data.getvalue())


def skins(payload, definitions):
    tree = binary_xml(payload)
    category = tree.find(".//Category[@name='free_version']")
    if tree.tag != 'SkinLibrary' or category is None:
        raise ValueError('Unexpected native skin library')
    for definition in definitions:
        if tree.find(".//*[@name='" + definition.get('name') + "']") is not None:
            raise ValueError('Market HUD skin already exists')
        category.append(copy.deepcopy(definition))
    return encode_binary_xml(tree)


def hud(payload, style):
    tree = binary_xml(payload)
    if tree.get('name') != 'start_dialog' or tree.find(".//Widget[@name='central_market_button']") is not None:
        raise ValueError('Unexpected or already modified HUD')
    ET.SubElement(tree, 'Widget', name='central_market_button', type='button',
                  frame='64,53,34,36' if style == 1 else '77,35,34,36',
                  flag='visible', preset='mkt_scales_button', tooltip='Central Market')
    return encode_binary_xml(tree)


def prepare(client, output, game_dll):
    image = Image.open(HERE / 'assets/scales-outlined.png').convert('RGBA')
    image = image.crop(image.getbbox())
    image.thumbnail((32, 34), Image.Resampling.LANCZOS)
    atlas = Image.new('RGBA', (128, 64))
    definitions = []
    for index, (state, brightness) in enumerate([('up', 1), ('over', 1.28), ('down', .72)]):
        atlas.alpha_composite(ImageEnhance.Brightness(image).enhance(brightness),
                              (index * 40 + (34 - image.width) // 2, (36 - image.height) // 2))
        definitions.append(ET.Element('Skin', name='mkt_scales_' + state,
                           src_image=f'{index * 40},0,34,36', texture='Textures/UI/mkt_scales'))
    preset = ET.Element('Preset', name='mkt_scales_button', type='button')
    for state in ('up', 'over', 'down'):
        ET.SubElement(preset, 'SkinRef', main_state='0', name='mkt_scales_' + state, sub_state=state)
    definitions.append(preset)
    header = [124, 0x100f, 64, 128, 512, 0, 0] + [0]*11 + [32, 0x41, 0, 32, 0xff, 0xff00, 0xff0000, 0xff000000, 0x1000, 0, 0, 0, 0]
    texture = b'DDS ' + struct.pack('<31I', *header) + atlas.tobytes()
    replacements = {
        'Data/ui/ui.pak': {'UI_Preload.xml': skins(read_pak(client / 'Data/ui/ui.pak').read('UI_Preload.xml'), definitions)},
        'Textures/ui/ui.pak': {'mkt_scales.dds': texture},
    }
    locale = 'L10N/enu/Data/data.pak'
    with read_pak(client / locale) as archive:
        replacements[locale] = {'ui/ui_preload.xml': skins(archive.read('ui/ui_preload.xml'), definitions)}
        for style in (1, 2):
            name = f'ui/game_hud_s{style}/start_dialog.xml'
            replacements[locale][name] = hud(archive.read(name), style)
    for style in (1, 2):
        name = f'Data/ui/game_hud_s{style}/game_hud_s{style}.pak'
        replacements[name] = {'start_dialog.xml': hud(read_pak(client / name).read('start_dialog.xml'), style)}
    for relative, entries in replacements.items():
        destination = output / relative
        destination.parent.mkdir(parents=True, exist_ok=True)
        destination.write_bytes(rewrite(client / relative, entries))
        with read_pak(client / relative) as before, read_pak(destination) as after:
            assert after.testzip() is None
            assert set(after.namelist()) == set(before.namelist()) | set(entries)
            for name in before.namelist():
                if name not in entries:
                    assert before.read(name) == after.read(name), (relative, name)
    work = output.parent / (output.name + '-hud-build')
    work.mkdir(parents=True, exist_ok=True)
    vcvars = Path(os.environ.get('ProgramFiles(x86)', r'C:\Program Files (x86)')) / 'Microsoft Visual Studio/2022/BuildTools/VC/Auxiliary/Build/vcvars64.bat'
    dll = output / 'bin64/AionMarketShortcut.dll'
    script = work / 'compile.cmd'
    script.write_text(f'@echo off\ncall "{vcvars}" >nul\ncl /nologo /std:c++17 /EHsc /O2 /MT /LD /Fo:"{work / "market.obj"}" "{HERE / "market_shortcut.cpp"}" /link /OUT:"{dll}" /IMPLIB:"{work / "market.lib"}"\n')
    subprocess.run(f'cmd.exe /d /s /c ""{script}""', check=True)
    patched, hooks = patch(game_dll)
    return patched, {'hooks': hooks, 'resources': list(replacements)}
