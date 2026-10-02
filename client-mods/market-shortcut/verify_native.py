"""Execute the actual native hooks and compiled callback in isolated fixture memory."""
import ctypes as C,sys,struct,json,subprocess
from pathlib import Path
from capstone import Cs,CS_ARCH_X86,CS_MODE_64
HERE=Path(__file__).resolve().parent;OUT=Path(sys.argv[1] if len(sys.argv)>1 else 'game-server/target/market-client-staged').resolve()

from loader_permissions import writable_iats
k=C.WinDLL('kernel32');k.VirtualAlloc.argtypes=[C.c_void_p,C.c_size_t,C.c_ulong,C.c_ulong];k.VirtualAlloc.restype=C.c_void_p;k.VirtualFree.argtypes=[C.c_void_p,C.c_size_t,C.c_ulong]
F=C.CFUNCTYPE(C.c_size_t,C.c_size_t,C.c_size_t,C.c_size_t,C.c_size_t)
def main():
    m=json.loads((OUT/'manifest.json').read_text());game_path=OUT/'bin64/Game.dll'
    if not game_path.exists():game_path=Path(m['clientRoot'])/'bin64/Game.dll'
    b=game_path.read_bytes();assert writable_iats(b)[0]==b
    pe=struct.unpack_from('<I',b,60)[0];opt=pe+24;table=opt+struct.unpack_from('<H',b,pe+20)[0]
    sec=[struct.unpack_from('<8sIIIIIIHHI',b,table+i*40) for i in range(struct.unpack_from('<H',b,pe+6)[0])]
    def off(r):
        for s in sec:
            if s[2]<=r<s[2]+s[3]:return s[4]+r-s[2]
        raise ValueError(hex(r))
    size=struct.unpack_from('<I',b,opt+56)[0]+0x1000;mem=k.VirtualAlloc(None,size,0x3000,0x40);assert mem
    own=next(s for s in sec if s[0].rstrip(b'\0')==b'.mkt');C.memmove(mem+own[2],b[own[4]:own[4]+own[3]],own[3])
    cursor=off(struct.unpack_from('<I',b,opt+120)[0])
    while any(b[cursor+20:cursor+40]):cursor+=20
    iat=struct.unpack_from('<I',b,cursor+16)[0];records={};handled=1
    cb0=F(lambda a,b,c,d:records.update(layout=a) or 0);cb1=F(lambda a,b,c,d:records.update(command=(a,b,c,d)) or handled)
    for i,h in enumerate(m['marketHud']['hooks']):
        C.c_size_t.from_address(mem+iat+i*8).value=C.cast([cb0,cb1][i],C.c_void_p).value
        site=int(h['site'],16);hook=int(h['hook'],16);original=bytes.fromhex(h['original'])
        C.memmove(mem+site,b[off(site):off(site)+len(original)],len(original))
        ins=list(Cs(CS_ARCH_X86,CS_MODE_64).disasm(b[off(hook):off(hook)+h['size']],hook));assert sum(x.size for x in ins)==h['size']
        continuation=bytes.fromhex('488b4424284883c4305bc3') if i==0 else bytes.fromhex('488b5c24504883c448b82a000000c3')
        C.memmove(mem+site+len(original),continuation,len(continuation))
    site=int(m['marketHud']['hooks'][0]['site'],16);wrapper=mem+size-0x1000
    prefix=bytes.fromhex('534883ec30488bd948894c2428')
    code=prefix+b'\xe9'+struct.pack('<i',mem+site-(wrapper+len(prefix)+5));C.memmove(wrapper,code,len(code))
    assert F(wrapper)(123,456,789,101)==123 and records['layout']==123
    click=F(mem+int(m['marketHud']['hooks'][1]['site'],16))
    assert click(11,22,33,44)==1 and records['command']==(11,22,33,44)
    handled=0;assert click(11,22,33,44)==42
    # Compile the same callback with an explicitly supplied fixture Game address.
    work=OUT.parent/(OUT.name+'-hud-build');work.mkdir(exist_ok=True);script=work/'test.cmd';dll=work/'MarketTest.dll'
    vcvars=r'C:\Program Files (x86)\Microsoft Visual Studio\2022\BuildTools\VC\Auxiliary\Build\vcvars64.bat'
    script.write_text(f'@echo off\ncall "{vcvars}" >nul\ncl /nologo /std:c++17 /EHsc /O2 /MT /LD /DMARKET_TEST /Fo:"{work / "test.obj"}" "{HERE / "market_shortcut.cpp"}" /link /OUT:"{dll}" /IMPLIB:"{work / "test.lib"}"\n')
    subprocess.run(f'cmd.exe /d /s /c ""{script}""',check=True)
    native=C.WinDLL(str(dll));native.AionMarketTestBase.argtypes=[C.c_void_p];native.AionMarketTestBase(mem)
    names=C.create_string_buffer(b'central_market_button');events=[];callbacks=[];vt=(C.c_void_p*200)()
    def bind(slot,signature,fn):
        cb=signature(fn);callbacks.append(cb);vt[slot//8]=C.cast(cb,C.c_void_p).value
    widgets={}
    for name in ['central_market_button','item_shop_container','item_ingame_web_shop','item_shop','item_shop_gf']:
        buf=C.create_string_buffer(0x300);C.c_size_t.from_buffer(buf).value=C.addressof(vt);widgets[name]=buf
    find=C.CFUNCTYPE(C.c_void_p,C.c_void_p,C.c_char_p,C.c_int)
    bind(0x338,find,lambda o,n,t:C.addressof(widgets[n.decode()]) if n.decode() in widgets else None)
    visible={C.addressof(widgets['item_shop']):1}
    bind(0xb8,C.CFUNCTYPE(C.c_int,C.c_void_p),lambda o:visible.get(o,0))
    bind(0xa8,C.CFUNCTYPE(C.c_void_p,C.c_void_p),lambda o:C.addressof(names))
    class Rect(C.Structure):_fields_=[(n,C.c_double) for n in ['x','y','w','h']]
    bind(0x1a8,C.CFUNCTYPE(None,C.c_void_p,C.POINTER(Rect)),lambda o,r:C.memmove(o+0x50,r,32))
    dispatcher=C.CFUNCTYPE(C.c_int,C.c_char_p)(lambda cmd:events.append(cmd) or 1);callbacks.append(dispatcher)
    stub=b'\x48\xb8'+struct.pack('<Q',C.cast(dispatcher,C.c_void_p).value)+b'\xff\xe0';C.memmove(mem+0x628270,stub,len(stub))
    native.AionMarketLayout.argtypes=[C.c_void_p];native.AionMarketCommand.argtypes=[C.c_void_p,C.c_void_p];native.AionMarketCommand.restype=C.c_int
    for scale in [.75,1,1.5,2]:
        C.c_double.from_address(mem+0x1378ec8).value=scale
        for name,values in [('item_shop_container',(100,11,64,64)),('item_shop',(17,24,30,35))]:
            r=Rect(*(v*scale for v in values));C.memmove(C.addressof(widgets[name])+0x50,C.byref(r),32)
        native.AionMarketLayout(C.addressof(widgets['central_market_button']))
        r=Rect.from_buffer(widgets['central_market_button'],0x50);assert (r.x,r.y,r.w,r.h)==(77*scale,35*scale,34*scale,36*scale)
    button=C.c_void_p(C.addressof(widgets['central_market_button']))
    assert native.AionMarketCommand(None,C.byref(button))==1 and events==[b'/privatewarehouse']
    for variant in ['item_shop','item_shop_gf','item_ingame_web_shop']:
        names=C.create_string_buffer(variant.encode());assert native.AionMarketCommand(None,C.byref(button))==0
    assert len(events)==1
    names=C.create_string_buffer(b'fly_gauge');assert native.AionMarketCommand(None,C.byref(button))==0 and len(events)==1
    assert native.AionMarketCommand(None,None)==0
    k.VirtualFree(mem,0,0x8000)
    print('PASS: both machine-code hooks, preserved registers/stack, native command passthrough, Market dispatcher and original Shop passthrough, and placement at four UI scales.')
if __name__=='__main__':main()
