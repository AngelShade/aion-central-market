"""Execute the new Game.dll initialization gate with isolated native callees."""
import ctypes as c
import struct
import sys
from pathlib import Path
from patch_client import SITE

b=Path(sys.argv[1]).read_bytes()
p=struct.unpack_from('<I',b,60)[0];o=p+24
count=struct.unpack_from('<H',b,p+6)[0]
table=o+struct.unpack_from('<H',b,p+20)[0]
size=struct.unpack_from('<I',b,o+56)[0]
k=c.WinDLL('kernel32',use_last_error=True)
k.VirtualAlloc.argtypes=[c.c_void_p,c.c_size_t,c.c_uint32,c.c_uint32];k.VirtualAlloc.restype=c.c_void_p
k.VirtualFree.argtypes=[c.c_void_p,c.c_size_t,c.c_uint32]
base=k.VirtualAlloc(None,size,0x3000,0x40);assert base
def put(rva,data):c.memmove(base+rva,data,len(data))
sections=[]
for i in range(count):
    name,length,rva,rawsize,raw=struct.unpack_from('<8sIIII',b,table+i*40)
    put(rva,b[raw:raw+rawsize]);sections.append((rva,rawsize,raw))
def offset(rva):
    for v,n,raw in sections:
        if v<=rva<v+n:return raw+rva-v
    raise ValueError(hex(rva))
try:
    # Verify a complete preserved import directory plus the new dependency.
    imports=struct.unpack_from('<I',b,o+120)[0];cursor=offset(imports);names=[];bridge_iat=None
    while any(b[cursor:cursor+20]):
        lookup,_,_,name,iat=struct.unpack_from('<IIIII',b,cursor)
        start=offset(name);module=b[start:b.index(0,start)].decode();names.append(module)
        if module=='AionIconBridge.dll':
            start=offset(struct.unpack_from('<Q',b,offset(lookup))[0])+2
            assert b[start:b.index(0,start)]==b'AionIconBridgeInitialize';bridge_iat=iat
        cursor+=20
    assert bridge_iat and 'Awesomium.dll' in names
    called=[]
    @c.CFUNCTYPE(c.c_int)
    def initialize():called.append(True);return 1
    put(bridge_iat,struct.pack('<Q',c.cast(initialize,c.c_void_p).value))
    captured=(c.c_uint64*4)()
    continuation=b'\x48\xb8'+struct.pack('<Q',c.addressof(captured))+bytes.fromhex('488908488950084c8940104c8948184881c498000000b834120000c3')
    put(SITE+10,continuation)
    function=c.CFUNCTYPE(c.c_int,c.c_uint64,c.c_uint64,c.c_uint64,c.c_uint64)(base+SITE)
    for args in [(1,2,3,4),(0xffffffffffffffff,0x123456789abcdef0,0,0xabcdef),(0,0,0,0)]:
        assert function(*args)==0x1234
        assert tuple(captured)==args,tuple(captured)
    assert len(called)==3
    print('PASS: new PE import, native initialization call, argument preservation, displaced stack prologue and return to original CreateWebView body')
finally:k.VirtualFree(base,0,0x8000)
