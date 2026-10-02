"""Retain writable IAT sections when extending a packed client's import table."""
import struct

def writable_iats(data):
    result=bytearray(data);pe=struct.unpack_from('<I',data,60)[0];opt=pe+24
    if data[pe:pe+6]!=b'PE\0\0\x64\x86':raise ValueError('Expected x64 PE')
    table=opt+struct.unpack_from('<H',data,pe+20)[0]
    sections=[struct.unpack_from('<8sIIIIIIHHI',data,table+i*40) for i in range(struct.unpack_from('<H',data,pe+6)[0])]
    def section(rva):
        for i,s in enumerate(sections):
            if s[2]<=rva<s[2]+s[3]:return i,s
        raise ValueError('Unmapped import RVA: '+hex(rva))
    def offset(rva):
        _,s=section(rva);return s[4]+rva-s[2]
    cursor=offset(struct.unpack_from('<I',data,opt+120)[0]);changes=[]
    while any(data[cursor:cursor+20]):
        first=struct.unpack_from('<I',data,cursor+16)[0]
        if not first:raise ValueError('Import descriptor has no IAT')
        index,s=section(first)
        # Each destination thunk, including the terminator, must stay in this section.
        end=offset(first)
        while struct.unpack_from('<Q',data,end)[0]:end+=8
        if end+8>s[4]+s[3]:raise ValueError('IAT crosses section boundary')
        flags=struct.unpack_from('<I',result,table+index*40+36)[0]
        if not flags&0x80000000:
            struct.pack_into('<I',result,table+index*40+36,flags|0x80000000)
            changes.append(s[0].rstrip(b'\0').decode())
        cursor+=20
    changed=[i for i,(a,b) in enumerate(zip(data,result)) if a!=b]
    assert all(i in {table+j*40+39 for j in range(len(sections))} for i in changed)
    return bytes(result),changes
