"""Create Central Market PNG artwork from a locally owned original Items.pak."""

import argparse
import csv
import io
from pathlib import Path

from PIL import Image

from codec import read_pak


def normalize(image):
    image = image.convert('RGBA')
    bounds = image.getchannel('A').getbbox()
    if bounds and bounds != (0, 0, image.width, image.height):
        artwork = image.crop(bounds)
        side = max(artwork.size)
        canvas = Image.new('RGBA', (side, side))
        canvas.paste(artwork, ((side - artwork.width) // 2, (side - artwork.height) // 2))
        image = canvas
    return image.resize((64, 64), Image.Resampling.LANCZOS)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--items-pak', type=Path, required=True, action='append',
                        help='An item artwork PAK from the matching original Aion 4.8 NA client; repeat if needed')
    parser.add_argument('--media', type=Path, default=Path(__file__).resolve().parents[2] /
                        'game-server/config/central-market/media')
    args = parser.parse_args()

    required = {}
    with (args.media / 'icon_sources.tsv').open(encoding='utf-8', newline='') as source:
        for row in csv.DictReader(source, delimiter='\t'):
            png, dds = row['file'].lower(), row['client_icon'].lower()
            if png in required and required[png] != dds:
                raise ValueError(f'Conflicting DDS source for {png}')
            required[png] = dds

    target = args.media / 'icons'
    target.mkdir(parents=True, exist_ok=True)
    missing = set(required)
    for pak in args.items_pak:
        with read_pak(pak) as archive:
            by_name = {}
            for name in archive.namelist():
                if name.lower().endswith('.dds'):
                    by_name.setdefault(Path(name.replace('\\', '/')).name.lower(), name)
            for png in list(missing):
                name = by_name.get(required[png])
                if name is None:
                    continue
                with Image.open(io.BytesIO(archive.read(name))) as image:
                    normalize(image).save(target / png, optimize=True)
                missing.remove(png)
        if not missing:
            break
    if missing:
        raise ValueError(f'{len(missing)} mapped icons are absent from the supplied PAK files; first: {sorted(missing)[:5]}')
    print(f'Extracted {len(required)} client icons to {target}')


if __name__ == '__main__':
    main()
