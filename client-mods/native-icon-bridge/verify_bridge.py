"""Isolated real Awesomium test: original DDS decoding, both routes and fallback.

Run against a disposable client root with bin64/AionIconBridge.dll + index and
Data/Items/Items.pak. --browser-bin points to the original client's bin64.
"""
import argparse
import ctypes as c
import http.server
import io
import os
import struct
import threading
import time
from pathlib import Path
from PIL import Image
from PIL import ImageChops, ImageStat
from prepare_index import read_pak

def verify_legacy_sprites(root, decode, data, items):
    # These original textures contain opaque/stray pixels beyond the native
    # 40x40 sprite. Compare with independently decoded client DDS artwork.
    names = ('textile01g_l', 'leather01d', 'textile01f_l', 'herb06_r',
             'herb09_r', 'mirror03', 'herb03_r', 'bucket02',
             'scroll_speed_run_01', 'herb05_r')
    records = 56 + items * 8
    examples = {}
    for i in range(items):
        item, index = struct.unpack_from('<II', data, 56 + i * 8)
        offset = struct.unpack_from('<I', data, records + index * 56)[0]
        examples.setdefault(offset, item)
    with read_pak(root / 'Data/Items/Items.pak') as archive:
        entries = {Path(i.filename).stem.lower(): i for i in archive.infolist()}
        for name in names:
            entry = entries['icon_item_' + name]
            offset = entry.header_offset + 30 + len(entry.filename.encode()) + len(entry.extra)
            item = examples[offset]
            if name == 'herb06_r':
                # Fresh Umblia must resolve to this texture and sprite size.
                index = next(struct.unpack_from('<II', data, 56 + i * 8)[1]
                             for i in range(items)
                             if struct.unpack_from('<I', data, 56 + i * 8)[0] == 152000408)
                assert struct.unpack_from('<IIIII', data, records + index * 56)[::4] == (offset, 40)
            source = Image.open(io.BytesIO(archive.read(entry.filename))).convert('RGBA').crop((0, 0, 40, 40))
            bounds = source.getchannel('A').getbbox()
            assert bounds
            source = source.crop(bounds)
            side = max(source.size)
            square = Image.new('RGBA', (side, side))
            square.paste(source, ((side - source.width) // 2, (side - source.height) // 2))
            expected = square.resize((64, 64), Image.Resampling.BICUBIC)
            length = decode(item, None, 0)
            buffer = c.create_string_buffer(length)
            assert decode(item, buffer, length) == length
            actual = Image.open(io.BytesIO(buffer.raw)).convert('RGBA')
            error = ImageStat.Stat(ImageChops.difference(actual, expected)).mean
            assert max(error) < 8, (name, item, 'Sprite mismatch', error)
    print('PASS: Fresh Umblia and all 10 legacy padding exceptions match native 40x40 artwork')

def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--root',type=Path,required=True)
    parser.add_argument('--browser-bin',type=Path,required=True)
    args=parser.parse_args()
    args.root=args.root.resolve();args.browser_bin=args.browser_bin.resolve()
    directory=os.add_dll_directory(str(args.browser_bin))
    library=c.CDLL(str(args.browser_bin/'Awesomium.dll'))
    def api(name,result,*parameters):
        f=getattr(library,name);f.restype=result;f.argtypes=list(parameters);return f
    ptr=c.c_void_p
    initialize=api('awe_webcore_initialize_default',None)
    shutdown=api('awe_webcore_shutdown',None)
    update=api('awe_webcore_update',None)
    create=api('awe_webcore_create_webview',ptr,c.c_int,c.c_int,c.c_bool)
    destroy=api('awe_webview_destroy',None,ptr)
    make=api('awe_string_create_from_wide',ptr,c.c_wchar_p,c.c_size_t)
    free=api('awe_string_destroy',None,ptr)
    load=api('awe_webview_load_url',None,ptr,ptr,ptr,ptr,ptr)
    evaluate=api('awe_webview_execute_javascript_with_result',ptr,ptr,ptr,ptr)
    text=api('awe_jsvalue_to_string',ptr,ptr)
    jsfree=api('awe_jsvalue_destroy',None,ptr)
    utf8=api('awe_string_to_utf8',c.c_size_t,ptr,ptr,c.c_size_t)
    set_resource=api('awe_webview_set_callback_resource_request',None,ptr,ptr)
    callback_type=c.CFUNCTYPE(ptr,ptr,ptr)
    forwarded=[]
    @callback_type
    def prior(view,request):forwarded.append(request);return None
    initialize()
    bridge=c.CDLL(str(args.root/'bin64/AionIconBridge.dll'))
    start=bridge.AionIconBridgeInitialize;start.restype=c.c_int;start.argtypes=[]
    assert start()==1, 'Bridge initialization failed'
    decode=bridge.AionIconBridgeDecode;decode.restype=c.c_size_t;decode.argtypes=[c.c_uint32,ptr,c.c_size_t]
    data=(args.root/'bin64/AionIconBridge.index').read_bytes()
    assert data[:8] == b'AICON002', 'Expected sprite-rectangle index'
    items,count=struct.unpack_from('<II',data,8)
    examples={}
    for i in range(items):
        item,index=struct.unpack_from('<II',data,56+i*8);examples.setdefault(index,item)
    for index,item in examples.items():
        length=decode(item,None,0);assert length>0, (index,item,'DDS decoding failed')
        buffer=c.create_string_buffer(length);assert decode(item,buffer,length)==length
        image=Image.open(io.BytesIO(buffer.raw));image.load();assert image.size==(64,64)
        assert image.getbbox(), (item,'Empty icon')
    assert len(examples)==count
    assert decode(999999999,None,0)==0
    print(f'Decoded {count} original textures for {items} item IDs')
    verify_legacy_sprites(args.root, decode, data, items)
    requests=[]
    class Handler(http.server.BaseHTTPRequestHandler):
        def do_GET(self):
            requests.append(self.path)
            if self.path!='/':self.send_error(404);return
            # Exercise actual padding exceptions through both shop routes,
            # including all three affected Cash Shop running-scroll IDs.
            html=b'<html><body><img id="market" src="/market/media/icons/152000408.png?v=native-3"><img id="shop" src="/shop/media/icons/164000074.png?v=native-3"><img id="shop75" src="/shop/media/icons/164000075.png?v=native-3"><img id="shop76" src="/shop/media/icons/164000076.png?v=native-3"><img id="missing" src="/market/media/icons/999999999.png"><img id="other" src="/other/100000001.png"></body></html>'
            self.send_response(200);self.send_header('Content-Type','text/html');self.send_header('Content-Length',str(len(html)));self.end_headers();self.wfile.write(html)
        def log_message(self,*args):pass
    server=http.server.HTTPServer(('127.0.0.1',0),Handler)
    threading.Thread(target=server.serve_forever,daemon=True).start()
    views=[];empty=make('',0)
    try:
        for attempt in range(2):
            view=create(600,200,False);assert view;views.append(view)
            set_resource(view,prior)  # Later callbacks must retain the icon bridge.
            url=f'http://127.0.0.1:{server.server_port}/';value=make(url,len(url));load(view,value,empty,empty,empty);free(value)
            deadline=time.monotonic()+12
            query="['market','shop','shop75','shop76','missing','other'].map(function(id){var a=document.getElementById(id);return a&&a.complete?a.naturalWidth:-1}).join(',')"
            result=''
            while time.monotonic()<deadline:
                update();time.sleep(.05)
                script=make(query,len(query));value=evaluate(view,script,empty);free(script)
                if value:
                    string=text(value);buffer=c.create_string_buffer(128);utf8(string,buffer,len(buffer));result=buffer.value.decode();free(string);jsfree(value)
                if result=='64,64,64,64,0,0':break
            assert result=='64,64,64,64,0,0',result
            destroy(view);views.remove(view)
        assert not any(f'/{item}.png?v=native-3' in r for item in (152000408,164000074,164000075,164000076) for r in requests),requests
        assert requests.count('/market/media/icons/999999999.png')>=1 and requests.count('/other/100000001.png')>=1,requests
        assert len(forwarded)>=4,len(forwarded)
        print('PASS: automatic attachment, callback chaining, create/destroy reuse, Cash Shop + Central Market icons, zero HTTP downloads for native icons, missing-icon fallback')
    finally:
        for view in views:destroy(view)
        free(empty);shutdown();server.shutdown();directory.close()

if __name__=='__main__':main()
