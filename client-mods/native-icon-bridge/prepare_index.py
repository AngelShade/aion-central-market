"""Index original client DDS offsets without extracting or distributing artwork."""
import argparse
import binascii
import hashlib
import struct
import sys
import zlib
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / 'expanded-warehouse'))
from codec import read_pak, binary_xml, KEYS

RECORD = struct.Struct('<IIIIIHH32s')

def prepare(client, output):
    path = client / 'Data/Items/Items.pak'
    raw = path.read_bytes()
    with read_pak(path) as archive:
        entries = {Path(i.filename).stem.lower(): i for i in archive.infolist() if i.filename.lower().endswith('.dds')}
        mapping = {}
        for filename in archive.namelist():
            if not filename.lower().startswith('client_items_') or not filename.lower().endswith('.xml'):
                continue
            for item in binary_xml(archive.read(filename)):
                ident = item.findtext('id', '')
                if not ident.isdigit() or int(ident) in mapping:
                    continue
                icon = next(((n.text or '').lower().removesuffix('.dds') for n in item.iter()
                             if (n.text or '').lower().removesuffix('.dds') in entries), None)
                if icon:
                    mapping[int(ident)] = entries.get(icon + '_64', entries[icon]).filename
        assets = sorted(set(mapping.values()))
        indices = {name: i for i, name in enumerate(assets)}
        records = []
        for name in assets:
            info = archive.getinfo(name)
            off = info.header_offset
            sig, version, flags, method, tm, date, crc, compressed, size, namesz, extra = struct.unpack_from('<4sHHHHHIIIHH', raw, off)
            if flags & 8 or method not in (0, 8) or sig != bytes.fromhex('afb4fcfb'):
                raise ValueError('Unsupported original DDS archive entry: ' + name)
            start = off + 30 + namesz + extra
            key = None
            for candidate, table in enumerate(KEYS, 1):
                key_offset = (compressed & 31) * 32 if candidate == 1 else compressed & 1023
                keybytes = table[key_offset:key_offset + min(32, compressed)]
                trial = bytearray(raw[start:start + compressed])
                for i, value in enumerate(keybytes):
                    trial[i] ^= value
                try:
                    plain = zlib.decompress(trial, -15) if method == 8 else trial
                except zlib.error:
                    continue
                if len(plain) == size and binascii.crc32(plain) == crc:
                    key = keybytes
                    break
            if key is None or plain[:4] != b'DDS ':
                raise ValueError('DDS decoding check failed: ' + name)
            # The native client uses the top-left 40px sprite in legacy DDS
            # canvases. Their unused padding is sometimes opaque gray, so
            # alpha bounds alone cannot identify the sprite rectangle.
            side = 64 if Path(name).stem.lower().endswith('_64') else 40
            height, width = struct.unpack_from('<II', plain, 12)
            if width < side or height < side:
                raise ValueError('DDS sprite rectangle exceeds texture: ' + name)
            records.append(RECORD.pack(start, compressed, size, crc, side, method, len(key), key.ljust(32, b'\0')))
    header = struct.pack('<8sIIQ32s', b'AICON002', len(mapping), len(assets), len(raw), hashlib.sha256(raw).digest())
    data = header + b''.join(struct.pack('<II', ident, indices[name]) for ident, name in sorted(mapping.items())) + b''.join(records)
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_bytes(data)
    return {'items': len(mapping), 'textures': len(assets), 'archiveSha256': hashlib.sha256(raw).hexdigest(), 'indexBytes': len(data)}

if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--client', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    print(prepare(args.client, args.output))
