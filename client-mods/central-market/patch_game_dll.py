"""Build a version-checked menu hook. This module never patches a running process."""
import hashlib
import struct
from pathlib import Path

ORIGINAL_SHA256 = '5334cf2164468678e45fe1a5decf58a0fbc4fd7f22cfdcbb87d28edce8d2c11c'
HOOK_RVA = 0x144d100
CALLER_RVA = 0x629e93
SEND_CHAT_RVA = 0x33d790
ORIGINAL_EXEC_RVA = 0x628270
BROWSER_HOOK_RVA = 0x144d800
BROWSER_AUTH_RVA = 0x61e580
BROWSER_LOAD_RVA = 0x12cc80
BROWSER_MANAGER_RVA = 0x130b850
BROWSER_PROLOGUE = bytes.fromhex('4883ec284889742440')
MARKET_AUTH_RVA = 0x4de4a0
MARKET_AUTH_HOOK_RVA = 0x144d400
MARKET_AUTH_ORIGINAL = bytes.fromhex('b868210000e806606700482be0')
NATIVE_SECURITY_TOKEN_RVA = 0x130c8f0
PREVIEW_DOCK_RVA = 0x806e29
PREVIEW_DOCK_HOOK_RVA = 0x144dc00
PREVIEW_DOCK_ORIGINAL = bytes.fromhex('3d6b0100000f8581000000')
PREVIEW_DOCK_POSITION_RVA = 0x806e34
PREVIEW_DOCK_SKIP_RVA = 0x806eb5
BROWSER_TOOLTIP_MODES = (0x131948, 0x131958)
BROWSER_TOOLTIP_MODE_ORIGINAL = bytes.fromhex('c7450403000000')
MARKET_RECT_RVA = 0x5ac890
MARKET_RECT_HOOK_RVA = 0x144d640
MARKET_RECT_ORIGINAL = bytes.fromhex('488bc44881ecb8000000')
UI_WIDTH_RVA, UI_HEIGHT_RVA, UI_SCALE_RVA = 0x1378ea8, 0x1378eb0, 0x1378ec8


def build_market_rect_code():
    """Fit only the named shop/market widgets in pixels on every layout pass.

    Addon Dialogs center their XML rectangle inside a scaled 1280x960 area.
    SetRect receives pixels; converting the browser's title inset once here
    avoids both the 4:3 footprint and applying UI scale to screen width twice.
    """
    a = Assembler(MARKET_RECT_HOOK_RVA)
    a.emit(bytes.fromhex('5356574883ec604889cb4889542440'))
    a.emit(bytes.fromhex('488b01ff90a80000004885c0'))  # native widget name
    a.branch(b'\x0f\x84', 'original')
    a.emit(bytes.fromhex('4889c74889442420'))
    a.branch(b'\x48\x8d\x35', 'prefix')
    a.emit(bytes.fromhex('b910000000f3a6'))  # exact 16-byte common prefix
    a.branch(b'\x0f\x85', 'cashshop')
    a.branch(b'\x48\x8d\x05', 'prefix')
    a.branch(b'\xe9', 'matched_prefix')
    a.label('cashshop')
    a.emit(bytes.fromhex('488b7c2420'))
    a.branch(b'\x48\x8d\x35', 'cash_prefix')
    a.emit(bytes.fromhex('b90f000000f3a6'))
    a.branch(b'\x0f\x85', 'original')
    a.branch(b'\x48\x8d\x05', 'cash_prefix')
    a.label('matched_prefix')
    a.emit(bytes.fromhex('4889442450'))  # browser lookup name for this dialog
    a.emit(bytes.fromhex('803f00'))
    a.branch(b'\x0f\x84', 'dialog')
    a.branch(b'\x48\x8d\x35', 'suffix')
    a.emit(bytes.fromhex('b908000000f3a6'))  # Browser and terminating NUL
    a.branch(b'\x0f\x85', 'original')
    a.emit(bytes.fromhex('c744244801000000'))
    a.relative(b'\xf2\x0f\x10\x1d', UI_SCALE_RVA)
    a.branch(b'\xf2\x0f\x59\x1d', 'title_height')
    a.branch(b'\xe9', 'rect')
    a.label('dialog')
    a.emit(bytes.fromhex('c744244800000000'))
    a.emit(bytes.fromhex('660fefdb'))  # no top inset for the outer dialog
    a.label('rect')
    a.emit(bytes.fromhex('660fefc0'))
    a.relative(b'\xf2\x0f\x10\x0d', UI_WIDTH_RVA)
    a.relative(b'\xf2\x0f\x10\x15', UI_HEIGHT_RVA)
    a.emit(bytes.fromhex('660f2fc8'))  # reject nonpositive/NaN viewport
    a.branch(b'\x0f\x86', 'original')
    a.emit(bytes.fromhex('660f2fd3'))
    a.branch(b'\x0f\x86', 'original')
    a.emit(bytes.fromhex('f20f5cd3f20f11442420f20f115c2428f20f114c2430f20f11542438'))
    a.emit(bytes.fromhex('4889d9488d542420'))
    a.branch(b'\xe8', 'native')
    # Stretch the browser after a dialog layout pass, including a resolution
    # change while open. Lookup is safe before the XML child has been created.
    a.emit(bytes.fromhex('837c244800'))
    a.branch(b'\x0f\x85', 'done')
    a.emit(bytes.fromhex('4889d9488b542450'))
    a.emit(bytes.fromhex('41b827200000488b03ff90380300004885c0'))
    a.branch(b'\x0f\x84', 'done')
    a.emit(bytes.fromhex('4889c1488d542420488b00ff90a8010000'))
    a.label('done')
    a.emit(bytes.fromhex('4883c4605f5e5bc3'))
    a.label('original')
    a.emit(bytes.fromhex('4889d9488b5424404883c4605f5e5b'))
    a.label('native')
    a.emit(MARKET_RECT_ORIGINAL)
    a.relative(b'\xe9', MARKET_RECT_RVA + len(MARKET_RECT_ORIGINAL))
    a.label('prefix')
    a.emit(b'PrivateWarehouseBrowser\0')
    a.label('suffix')
    a.emit(b'Browser\0')
    a.label('cash_prefix')
    a.emit(b'PrivateCashShopBrowser\0')
    a.label('title_height')
    a.emit(struct.pack('<d', 25))
    return a.finish()


def build_preview_dock_code():
    """Let the native paper-doll positioning use the private Cash Shop bounds."""
    asm = Assembler(PREVIEW_DOCK_HOOK_RVA)
    asm.emit(b'\x3d\x6b\x01\0\0')  # keep the original supported dialog
    asm.branch(b'\x0f\x84', 'position')
    # Native addon dialogs use the client's reserved 0x20e..0x221 pool.
    asm.emit(b'\x3d\x0e\x02\0\0')
    asm.branch(b'\x0f\x82', 'skip')
    asm.emit(b'\x3d\x21\x02\0\0')
    asm.branch(b'\x0f\x87', 'skip')
    # The native widget lookup verifies the exact Cash Shop browser child.
    # The surrounding native function already owns the ABI shadow space.
    asm.emit(b'\x48\x89\xf9')  # rcx = focused dialog (rdi)
    asm.branch(b'\x48\x8d\x15', 'browser_name')
    asm.emit(b'\x41\xb8\x27\x20\0\0\x48\x8b\x07\xff\x90\x38\x03\0\0')
    asm.emit(b'\x48\x85\xc0')
    asm.branch(b'\x0f\x85', 'position')
    asm.emit(b'\x48\x89\xf9')
    asm.branch(b'\x48\x8d\x15', 'warehouse_browser_name')
    asm.emit(b'\x41\xb8\x27\x20\0\0\x48\x8b\x07\xff\x90\x38\x03\0\0')
    asm.emit(b'\x48\x85\xc0')
    asm.branch(b'\x0f\x84', 'skip')
    asm.label('position')
    asm.relative(b'\xe9', PREVIEW_DOCK_POSITION_RVA)
    asm.label('skip')
    asm.relative(b'\xe9', PREVIEW_DOCK_SKIP_RVA)
    asm.label('browser_name')
    asm.emit(b'PrivateCashShopBrowser\0')
    asm.label('warehouse_browser_name')
    asm.emit(b'PrivateWarehouseBrowser\0')
    return asm.finish()


class Assembler:
    def __init__(self, base):
        self.base = base
        self.code = bytearray()
        self.labels = {}
        self.fixups = []

    def emit(self, data):
        self.code.extend(data)

    def label(self, name):
        self.labels[name] = len(self.code)

    def branch(self, opcode, label):
        self.emit(opcode)
        self.fixups.append((len(self.code), label))
        self.emit(bytes(4))

    def relative(self, opcode, target):
        self.emit(opcode)
        self.emit(struct.pack('<i', target - self.base - len(self.code) - 4))

    def finish(self):
        for pos, name in self.fixups:
            self.code[pos:pos + 4] = struct.pack('<i', self.labels[name] - pos - 4)
        return bytes(self.code)


def build_hook_code(commands):
    asm = Assembler(HOOK_RVA)
    for index, command in enumerate(commands):
        # Compare one byte at a time, including NUL. Never read beyond a short command.
        for offset, value in enumerate(('/say .' + command).encode('ascii') + b'\0'):
            asm.emit(b'\x80\x79' + bytes([offset, value]))  # cmp byte [rcx+offset], value
            asm.branch(b'\x0f\x85', 'next_' + str(index))
        asm.emit(b'\x48\x83\xec\x28\x31\xd2')  # ABI stack alignment/shadow space; public chat
        asm.branch(b'\x4c\x8d\x05', 'text_' + str(index))  # r8 = wide chat message
        asm.relative(b'\xe8', SEND_CHAT_RVA)
        asm.emit(b'\xb8\x01\0\0\0\x48\x83\xc4\x28\xc3')
        asm.label('next_' + str(index))
    asm.relative(b'\xe9', ORIGINAL_EXEC_RVA)
    for index, command in enumerate(commands):
        asm.label('text_' + str(index))
        asm.emit(('.' + command + '\0').encode('utf-16le'))
    return asm.finish()


def build_browser_hook_code(url):
    # The publisher authentication path redirects through its login service.
    # Our local shop authenticates on the private server; queue its exact URL
    # directly on the same browser view. All other URLs retain the original path.
    urls = [url] if isinstance(url, str) else url
    if not urls or len(set(urls)) != len(urls):
        raise ValueError('Browser routes must be nonempty and unique')
    asm = Assembler(BROWSER_HOOK_RVA)
    asm.emit(b'\x48\x85\xd2')  # test rdx, rdx
    asm.branch(b'\x0f\x84', 'original')
    for index, route in enumerate(urls):
        payload = route.encode('ascii') + b'\0'
        if not route.startswith('http://127.0.0.1:8091/') or len(payload) > 128 or any(c < 32 for c in payload[:-1]):
            raise ValueError('Embedded navigation supports exact loopback URLs of at most 127 ASCII characters')
        for offset, value in enumerate(payload):
            asm.emit(b'\x80\x7a' + bytes([offset, value]))
            asm.branch(b'\x0f\x85', 'next_url_' + str(index))
        asm.branch(b'\xe9', 'authenticated_load' if route.endswith('/market') else 'load')
        asm.label('next_url_' + str(index))
    asm.branch(b'\xe9', 'original')
    asm.label('authenticated_load')
    asm.emit(b'\x48\x8b\x41\x10\x48\x85\xc0')
    asm.branch(b'\x0f\x84', 'return')
    asm.emit(b'\x48\x89\xd1\x8b\x90\x40\x03\0\0\x85\xd2')
    asm.branch(b'\x0f\x88', 'return')
    asm.emit(b'\x41\xb0\x01')  # use native pending-token request and callback
    asm.relative(b'\xe9', MARKET_AUTH_RVA)
    asm.label('load')
    asm.emit(b'\x48\x8b\x41\x10\x48\x85\xc0')  # native browser = [wrapper+0x10]
    asm.branch(b'\x0f\x84', 'return')
    asm.emit(b'\x49\x89\xd0')  # r8 = URL
    asm.emit(b'\x8b\x90\x40\x03\0\0\x85\xd2')  # edx = browser view index
    asm.branch(b'\x0f\x88', 'return')
    asm.relative(b'\x48\x8d\x0d', BROWSER_MANAGER_RVA)
    asm.relative(b'\xe9', BROWSER_LOAD_RVA)  # tail call preserves the caller's ABI frame
    asm.label('return')
    asm.emit(b'\xc3')
    asm.label('original')
    asm.emit(BROWSER_PROLOGUE)  # displaced complete instructions, no relative operands
    asm.relative(b'\xe9', BROWSER_AUTH_RVA + len(BROWSER_PROLOGUE))
    return asm.finish()


def build_market_auth_code(url):
    """Use the native token request callback, then navigate to the authenticated market URL."""
    route = url.encode('ascii') + b'\0'
    if not url.startswith('http://127.0.0.1:8091/') or len(route) > 128:
        raise ValueError('Market authentication requires the configured loopback route')
    asm = Assembler(MARKET_AUTH_HOOK_RVA)
    asm.emit(b'\x48\x85\xc9')
    asm.branch(b'\x0f\x84', 'original')
    for offset, value in enumerate(route):
        asm.emit(b'\x80\x79' + bytes([offset,value]))
        asm.branch(b'\x0f\x85', 'original')
    asm.relative(b'\x4c\x8d\x1d', NATIVE_SECURITY_TOKEN_RVA)  # r11 = native 16-byte token
    asm.emit(b'\x49\x8b\x03\x49\x0b\x43\x08')
    asm.branch(b'\x0f\x84', 'original')  # native code requests token and queues this URL
    asm.emit(b'\x48\x81\xec\x28\x01\0\0')  # shadow space and local URL, aligned
    asm.emit(b'\x89\x94\x24\x18\x01\0\0\x4c\x8d\x44\x24\x20\x45\x31\xc9')
    asm.label('copy')
    asm.emit(b'\x42\x8a\x04\x09\x84\xc0')
    asm.branch(b'\x0f\x84', 'suffix')
    asm.emit(b'\x43\x88\x04\x08\x49\xff\xc1')
    asm.branch(b'\xe9', 'copy')
    asm.label('suffix')
    asm.emit(b'\x4d\x01\xc8')  # r8 points at end of route
    for offset,value in enumerate(b'?session_id='):
        asm.emit(b'\x41\xc6\x40' + bytes([offset,value]))
    asm.emit(b'\x49\x83\xc0\x0c\x45\x31\xc9')
    asm.branch(b'\x48\x8d\x0d', 'hex_digits')
    asm.label('hex')
    asm.emit(b'\x43\x0f\xb6\x04\x0b\x89\xc2\xc1\xe8\x04\x83\xe2\x0f')
    asm.emit(b'\x8a\x04\x01\x41\x88\x00\x8a\x04\x11\x41\x88\x40\x01')
    asm.emit(b'\x49\x83\xc0\x02\x49\xff\xc1\x49\x83\xf9\x10')
    asm.branch(b'\x0f\x82', 'hex')
    asm.emit(b'\x41\xc6\x00\0\x4c\x8d\x44\x24\x20\x8b\x94\x24\x18\x01\0\0')
    asm.relative(b'\x48\x8d\x0d', BROWSER_MANAGER_RVA)
    asm.relative(b'\xe8', BROWSER_LOAD_RVA)
    asm.emit(b'\x48\x81\xc4\x28\x01\0\0\xc3')
    asm.label('original')
    asm.emit(b'\xb8\x68\x21\0\0')
    asm.relative(b'\xe8', 0xb544b0)  # relocate the original stack probe
    asm.emit(b'\x48\x2b\xe0')
    asm.relative(b'\xe9', MARKET_AUTH_RVA + len(MARKET_AUTH_ORIGINAL))
    asm.label('hex_digits')
    asm.emit(b'0123456789abcdef')
    return asm.finish()


def build_dll(original_path, commands, cash_shop_url):
    data = bytearray(Path(original_path).read_bytes())
    if hashlib.sha256(data).hexdigest() != ORIGINAL_SHA256:
        raise ValueError('Unsupported original Game.dll. This patch supports only the verified 4.8 NA build.')
    if data[CALLER_RVA:CALLER_RVA + 5] != bytes.fromhex('e8d8e3ffff'):
        raise ValueError('Original menu call does not match')
    hook = build_hook_code(commands)
    if len(hook) > BROWSER_HOOK_RVA - HOOK_RVA or any(data[HOOK_RVA:HOOK_RVA + len(hook)]):
        raise ValueError('Hook does not fit the verified empty code region')
    data[HOOK_RVA:HOOK_RVA + len(hook)] = hook
    data[CALLER_RVA:CALLER_RVA + 5] = b'\xe8' + struct.pack('<i', HOOK_RVA - CALLER_RVA - 5)
    if data[BROWSER_AUTH_RVA:BROWSER_AUTH_RVA + len(BROWSER_PROLOGUE)] != BROWSER_PROLOGUE:
        raise ValueError('Original browser authentication entry does not match')
    browser_hook = build_browser_hook_code(cash_shop_url)
    if len(browser_hook) > PREVIEW_DOCK_HOOK_RVA - BROWSER_HOOK_RVA or any(data[BROWSER_HOOK_RVA:BROWSER_HOOK_RVA + len(browser_hook)]):
        raise ValueError('Browser hook does not fit the verified empty code region')
    data[BROWSER_HOOK_RVA:BROWSER_HOOK_RVA + len(browser_hook)] = browser_hook
    data[BROWSER_AUTH_RVA:BROWSER_AUTH_RVA + len(BROWSER_PROLOGUE)] = (
        b'\xe9' + struct.pack('<i', BROWSER_HOOK_RVA - BROWSER_AUTH_RVA - 5) + b'\x90' * 4)
    routes = [cash_shop_url] if isinstance(cash_shop_url,str) else cash_shop_url
    market_routes = [route for route in routes if route.endswith('/market')]
    if market_routes:
        if len(market_routes)!=1 or data[MARKET_AUTH_RVA:MARKET_AUTH_RVA+len(MARKET_AUTH_ORIGINAL)] != MARKET_AUTH_ORIGINAL:
            raise ValueError('Native account authentication entry does not match')
        auth = build_market_auth_code(market_routes[0])
        if len(auth)>BROWSER_HOOK_RVA-MARKET_AUTH_HOOK_RVA or any(data[MARKET_AUTH_HOOK_RVA:MARKET_AUTH_HOOK_RVA+len(auth)]):
            raise ValueError('Market authentication does not fit its verified code region')
        data[MARKET_AUTH_HOOK_RVA:MARKET_AUTH_HOOK_RVA+len(auth)] = auth
        data[MARKET_AUTH_RVA:MARKET_AUTH_RVA+len(MARKET_AUTH_ORIGINAL)] = b'\xe9'+struct.pack('<i',MARKET_AUTH_HOOK_RVA-MARKET_AUTH_RVA-5)+b'\x90'*(len(MARKET_AUTH_ORIGINAL)-5)
        rect = build_market_rect_code()
        if (MARKET_AUTH_HOOK_RVA + len(auth) > MARKET_RECT_HOOK_RVA
                or MARKET_RECT_HOOK_RVA + len(rect) > BROWSER_HOOK_RVA
                or any(data[MARKET_RECT_HOOK_RVA:MARKET_RECT_HOOK_RVA + len(rect)])
                or data[MARKET_RECT_RVA:MARKET_RECT_RVA + len(MARKET_RECT_ORIGINAL)] != MARKET_RECT_ORIGINAL):
            raise ValueError('Market resize hook does not match its verified entry and padding')
        data[MARKET_RECT_HOOK_RVA:MARKET_RECT_HOOK_RVA + len(rect)] = rect
        data[MARKET_RECT_RVA:MARKET_RECT_RVA + len(MARKET_RECT_ORIGINAL)] = (
            b'\xe9' + struct.pack('<i', MARKET_RECT_HOOK_RVA - MARKET_RECT_RVA - 5)
            + b'\x90' * (len(MARKET_RECT_ORIGINAL) - 5))
        # Browser title ItemTooltip/ItemDescTooltip use the mouse placement mode.
        # Mode 3 expects a native item cell rectangle; Browser widgets have no cell.
        for offset in BROWSER_TOOLTIP_MODES:
            if data[offset:offset+7] != BROWSER_TOOLTIP_MODE_ORIGINAL:
                raise ValueError('Native browser tooltip placement does not match')
            data[offset+3:offset+7] = bytes(4)
    if data[PREVIEW_DOCK_RVA:PREVIEW_DOCK_RVA + len(PREVIEW_DOCK_ORIGINAL)] != PREVIEW_DOCK_ORIGINAL:
        raise ValueError('Native preview positioning does not match the verified client')
    preview_dock = build_preview_dock_code()
    if len(preview_dock) > 0x400 or any(data[PREVIEW_DOCK_HOOK_RVA:PREVIEW_DOCK_HOOK_RVA + len(preview_dock)]):
        raise ValueError('Native preview docking hook does not fit the empty code region')
    data[PREVIEW_DOCK_HOOK_RVA:PREVIEW_DOCK_HOOK_RVA + len(preview_dock)] = preview_dock
    data[PREVIEW_DOCK_RVA:PREVIEW_DOCK_RVA + len(PREVIEW_DOCK_ORIGINAL)] = (
        b'\xe9' + struct.pack('<i', PREVIEW_DOCK_HOOK_RVA - PREVIEW_DOCK_RVA - 5)
        + b'\x90' * (len(PREVIEW_DOCK_ORIGINAL) - 5))
    return bytes(data)
