"""Keep the stock model key while giving both signed archive loaders an addon key."""
import hashlib
import struct

ORIGINAL_SHA256 = 'ebbef56fbde816bff93a002b2ae038105c489f2c2e6041409d08e5f73e819084'
PLUGIN_KEY_LEA = 0x4a3fc
KEY_STORAGE = 0x4a520
KEY_NAME = b'Addon.key\0'
ORIGINAL_LEA = bytes.fromhex('488d15459c1c00')
ARCHIVE_KEY_REFERENCES = {
    PLUGIN_KEY_LEA: ORIGINAL_LEA,
    0x4a4b7: bytes.fromhex('488d158a9b1c00'),
    0x4c372: bytes.fromhex('488d15cf7c1c00'),
}


def patch_plugin_key(data):
    original = bytes(data)
    if original[KEY_STORAGE:KEY_STORAGE + len(KEY_NAME)] == KEY_NAME:
        restored = bytearray(original)
        for site, stock in ARCHIVE_KEY_REFERENCES.items():
            expected = b'\x48\x8d\x15' + struct.pack('<i', KEY_STORAGE - site - 7)
            if original[site:site + 7] not in (stock, expected):
                raise ValueError('Archive key reference contains unexpected changes')
            restored[site:site + 7] = stock
        restored[KEY_STORAGE:KEY_STORAGE + len(KEY_NAME)] = b'\xcc' * len(KEY_NAME)
        if hashlib.sha256(restored).hexdigest() != ORIGINAL_SHA256:
            raise ValueError('Patched CrySystem contains unexpected changes')
        original = bytes(restored)
    if hashlib.sha256(original).hexdigest() != ORIGINAL_SHA256:
        raise ValueError('Unsupported CrySystem build')
    if original[PLUGIN_KEY_LEA:PLUGIN_KEY_LEA + 7] != ORIGINAL_LEA or original[KEY_STORAGE:KEY_STORAGE + len(KEY_NAME)] != b'\xcc' * len(KEY_NAME):
        raise ValueError('Plugin key reference or function padding does not match')
    result = bytearray(original)
    for site, stock in ARCHIVE_KEY_REFERENCES.items():
        if original[site:site + 7] != stock:
            raise ValueError('Archive key reference does not match')
        result[site:site + 7] = b'\x48\x8d\x15' + struct.pack('<i', KEY_STORAGE - site - 7)
    result[KEY_STORAGE:KEY_STORAGE + len(KEY_NAME)] = KEY_NAME
    # Model signatures continue to use the untouched original Pub.key reference.
    for start, end in [(0x49a90, 0x49a9c)]:
        assert result[start:end] == original[start:end]
    return bytes(result)
