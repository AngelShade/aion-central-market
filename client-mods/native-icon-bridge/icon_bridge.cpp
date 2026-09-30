#define WIN32_LEAN_AND_MEAN
#define NOMINMAX
#include <windows.h>
#include <wincodec.h>
#include <wrl/client.h>
#include <bcrypt.h>
#include "icon_codec.h"
#include <filesystem>
#include <fstream>
#include <list>
#include <memory>
#include <mutex>
#include <string>
#include <unordered_map>

namespace {
using Microsoft::WRL::ComPtr;
using Ptr=void*;
using Callback=Ptr(__cdecl*)(Ptr,Ptr);
using Create=Ptr(__cdecl*)(int,int,bool);
using Set=void(__cdecl*)(Ptr,Callback);
using Destroy=void(__cdecl*)(Ptr);
Ptr(__cdecl* request_url)(Ptr);
size_t(__cdecl* to_utf8)(Ptr,char*,size_t);
Ptr(__cdecl* from_wide)(const wchar_t*,size_t);
void(__cdecl* destroy_string)(Ptr);
Ptr(__cdecl* response_create)(size_t,const unsigned char*,Ptr);
Set set_callback,original_set;
Create original_create;
Destroy original_destroy;
HMODULE own_module;
HANDLE archive=INVALID_HANDLE_VALUE;
std::mutex cache_mutex,callback_mutex;
std::once_flag initialize_once;
bool initialized=false;
std::filesystem::path log_path;
std::mutex log_mutex;
bool logged_market=false,logged_shop=false;
void log(const char* message){try{std::lock_guard<std::mutex> lock(log_mutex);if(!log_path.empty()){std::ofstream out(log_path,std::ios::app);out<<message<<'\n';}}catch(...){}}
struct Asset {uint32_t offset,compressed,size,crc,sprite_side;uint16_t method,key_size;uint8_t key[32];};
static_assert(sizeof(Asset)==56);
std::vector<Asset> assets;
std::unordered_map<uint32_t,uint32_t> item_assets;
std::unordered_map<Ptr,Callback> previous_callbacks;
std::list<uint32_t> recent;
struct Cached {std::shared_ptr<std::vector<uint8_t>> bytes;std::list<uint32_t>::iterator position;};
std::unordered_map<uint32_t,Cached> cache;
constexpr size_t callback_offset=0xe8;
template<typename T>T symbol(HMODULE module,const char* name){return reinterpret_cast<T>(GetProcAddress(module,name));}
void check(HRESULT result){if(FAILED(result))throw std::runtime_error("WIC failure");}

std::vector<uint8_t> png(const icons::Image& image){
    HRESULT apartment=CoInitializeEx(nullptr,COINIT_MULTITHREADED);
    struct Uninitialize{bool active;~Uninitialize(){if(active)CoUninitialize();}} uninitialize{SUCCEEDED(apartment)};
    if(FAILED(apartment)&&apartment!=RPC_E_CHANGED_MODE)check(apartment);
    ComPtr<IWICImagingFactory> factory;check(CoCreateInstance(CLSID_WICImagingFactory,nullptr,CLSCTX_INPROC_SERVER,IID_PPV_ARGS(&factory)));
    ComPtr<IWICBitmap> bitmap;check(factory->CreateBitmapFromMemory(image.width,image.height,GUID_WICPixelFormat32bppBGRA,image.width*4,UINT(image.bgra.size()),const_cast<BYTE*>(image.bgra.data()),&bitmap));
    ComPtr<IWICBitmapScaler> scaled;check(factory->CreateBitmapScaler(&scaled));check(scaled->Initialize(bitmap.Get(),64,64,WICBitmapInterpolationModeHighQualityCubic));
    ComPtr<IStream> stream;check(CreateStreamOnHGlobal(nullptr,TRUE,&stream));
    ComPtr<IWICBitmapEncoder> encoder;check(factory->CreateEncoder(GUID_ContainerFormatPng,nullptr,&encoder));check(encoder->Initialize(stream.Get(),WICBitmapEncoderNoCache));
    ComPtr<IWICBitmapFrameEncode> frame;check(encoder->CreateNewFrame(&frame,nullptr));check(frame->Initialize(nullptr));check(frame->SetSize(64,64));
    WICPixelFormatGUID format=GUID_WICPixelFormat32bppBGRA;check(frame->SetPixelFormat(&format));check(frame->WriteSource(scaled.Get(),nullptr));check(frame->Commit());check(encoder->Commit());
    STATSTG stat{};check(stream->Stat(&stat,STATFLAG_NONAME));if(stat.cbSize.QuadPart>1024*1024)throw std::runtime_error("PNG too large");
    LARGE_INTEGER zero{};check(stream->Seek(zero,STREAM_SEEK_SET,nullptr));std::vector<uint8_t> bytes(size_t(stat.cbSize.QuadPart));ULONG got=0;check(stream->Read(bytes.data(),ULONG(bytes.size()),&got));if(got!=bytes.size())throw std::runtime_error("Short PNG");return bytes;
}
std::array<uint8_t,32> file_hash(HANDLE file){
    BCRYPT_ALG_HANDLE algorithm=nullptr;BCRYPT_HASH_HANDLE hash=nullptr;
    struct Release{BCRYPT_ALG_HANDLE& a;BCRYPT_HASH_HANDLE& h;~Release(){if(h)BCryptDestroyHash(h);if(a)BCryptCloseAlgorithmProvider(a,0);}} release{algorithm,hash};
    if(BCryptOpenAlgorithmProvider(&algorithm,BCRYPT_SHA256_ALGORITHM,nullptr,0)<0||BCryptCreateHash(algorithm,&hash,nullptr,0,nullptr,0,0)<0)throw std::runtime_error("Hash initialization");
    std::array<uint8_t,65536> buffer{};DWORD got;LARGE_INTEGER zero{};if(!SetFilePointerEx(file,zero,nullptr,FILE_BEGIN))throw std::runtime_error("Archive seek");
    do{if(!ReadFile(file,buffer.data(),DWORD(buffer.size()),&got,nullptr))throw std::runtime_error("Archive hash read");if(got&&BCryptHashData(hash,buffer.data(),got,0)<0)throw std::runtime_error("Archive hash");}while(got);
    std::array<uint8_t,32> result{};if(BCryptFinishHash(hash,result.data(),DWORD(result.size()),0)<0)throw std::runtime_error("Archive hash finish");return result;
}
bool load_index(const std::filesystem::path& root){
    std::ifstream input(root/L"bin64/AionIconBridge.index",std::ios::binary|std::ios::ate);if(!input)return false;
    auto length=input.tellg();if(length<56||length>8*1024*1024)return false;
    std::vector<uint8_t> data(size_t(length),0);input.seekg(0);input.read(reinterpret_cast<char*>(data.data()),std::streamsize(data.size()));if(!input)return false;
    if(std::memcmp(data.data(),"AICON002",8))return false;
    uint32_t items=icons::u32(data.data()+8),count=icons::u32(data.data()+12);uint64_t archive_size;std::memcpy(&archive_size,data.data()+16,8);
    if(items>200000||count>30000||56+uint64_t(items)*8+uint64_t(count)*56!=data.size())return false;
    HANDLE file=CreateFileW((root/L"Data/Items/Items.pak").c_str(),GENERIC_READ,FILE_SHARE_READ,nullptr,OPEN_EXISTING,FILE_ATTRIBUTE_NORMAL,nullptr);
    if(file==INVALID_HANDLE_VALUE)return false;
    struct Close{HANDLE h;~Close(){if(h!=INVALID_HANDLE_VALUE)CloseHandle(h);}} close{file};
    LARGE_INTEGER size{};if(!GetFileSizeEx(file,&size)||uint64_t(size.QuadPart)!=archive_size)return false;
    auto hash=file_hash(file);if(std::memcmp(hash.data(),data.data()+24,32))return false;
    std::vector<Asset> new_assets(count);std::memcpy(new_assets.data(),data.data()+56+items*8,count*56);
    for(const auto& a:new_assets)if(uint64_t(a.offset)+a.compressed>archive_size||a.compressed>16*1024*1024||a.size>16*1024*1024||a.size<128||a.key_size>32||(a.method!=0&&a.method!=8)||(a.sprite_side!=40&&a.sprite_side!=64))return false;
    std::unordered_map<uint32_t,uint32_t> mapping;for(uint32_t i=0;i<items;++i){const uint8_t* p=data.data()+56+i*8;uint32_t index=icons::u32(p+4);if(index>=count||!mapping.emplace(icons::u32(p),index).second)return false;}
    archive=file;close.h=INVALID_HANDLE_VALUE;assets=std::move(new_assets);item_assets=std::move(mapping);return true;
}
std::shared_ptr<std::vector<uint8_t>> get_icon(uint32_t item){
    std::lock_guard<std::mutex> lock(cache_mutex);auto found=item_assets.find(item);if(found==item_assets.end())return {};
    uint32_t index=found->second;auto hit=cache.find(index);if(hit!=cache.end()){recent.splice(recent.begin(),recent,hit->second.position);return hit->second.bytes;}
    const auto& a=assets[index];std::vector<uint8_t> compressed(a.compressed);LARGE_INTEGER offset{};offset.QuadPart=a.offset;DWORD got=0;
    if(!SetFilePointerEx(archive,offset,nullptr,FILE_BEGIN)||!ReadFile(archive,compressed.data(),a.compressed,&got,nullptr)||got!=a.compressed)throw std::runtime_error("Archive read");
    for(unsigned i=0;i<a.key_size;++i)compressed[i]^=a.key[i];auto plain=a.method==8?icons::inflate(compressed,a.size):std::move(compressed);
    if(plain.size()!=a.size||icons::crc32(plain)!=a.crc)throw std::runtime_error("DDS CRC");
    auto bytes=std::make_shared<std::vector<uint8_t>>(png(icons::crop(icons::sprite(icons::dds(plain),a.sprite_side))));recent.push_front(index);cache.emplace(index,Cached{bytes,recent.begin()});
    if(cache.size()>512){cache.erase(recent.back());recent.pop_back();}return bytes;
}
uint32_t icon_id(const std::string& url){
    const std::string origin="http://127.0.0.1:";if(url.rfind(origin,0)!=0)return 0;size_t slash=url.find('/',origin.size());
    if(slash==std::string::npos||slash==origin.size()||slash-origin.size()>5)return 0;
    for(size_t i=origin.size();i<slash;++i)if(url[i]<'0'||url[i]>'9')return 0;
    size_t start=0;for(const auto* prefix:{"/market/media/icons/","/shop/media/icons/"}){size_t n=std::strlen(prefix);if(url.compare(slash,n,prefix)==0){start=slash+n;break;}}
    if(!start||url.size()<start+13||url.compare(start+9,4,".png")|| (url.size()!=start+13&&url[start+13]!='?'))return 0;
    uint32_t item=0;for(size_t i=start;i<start+9;++i){if(url[i]<'0'||url[i]>'9')return 0;item=item*10+url[i]-'0';}return item;
}
Ptr __cdecl on_resource(Ptr view,Ptr request){
    try{
        std::array<char,256> address{};Ptr value=request_url(request);size_t count=value?to_utf8(value,address.data(),address.size()):address.size();if(value)destroy_string(value);
        if(count<address.size()){auto bytes=get_icon(icon_id(std::string(address.data(),count)));if(bytes){Ptr mime=from_wide(L"image/png",9);if(mime){Ptr response=response_create(bytes->size(),bytes->data(),mime);destroy_string(mime);if(response){std::lock_guard<std::mutex> lock(log_mutex);bool& recorded=std::strstr(address.data(),"/market/")?logged_market:logged_shop;if(!recorded){recorded=true;if(!log_path.empty()){std::ofstream out(log_path,std::ios::app);out<<"Native icon served: "<<address.data()<<'\n';}}return response;}}}}
    }catch(...){/* Preserve callback chaining; the native-only server returns 404. */}
    Callback prior=nullptr;{std::lock_guard<std::mutex> lock(callback_mutex);auto found=previous_callbacks.find(view);if(found!=previous_callbacks.end())prior=found->second;}
    return prior&&prior!=on_resource?prior(view,request):nullptr;
}
void attach(Ptr view){
    if(!view)return;{std::lock_guard<std::mutex> lock(callback_mutex);auto prior=*reinterpret_cast<Callback*>(reinterpret_cast<uint8_t*>(view)+callback_offset);if(prior==on_resource)return;previous_callbacks[view]=prior;}
    (original_set?original_set:set_callback)(view,on_resource);log("Browser view attached");
}
Ptr __cdecl create_view(int width,int height,bool transparent){Ptr view=original_create(width,height,transparent);attach(view);return view;}
void __cdecl set_resource(Ptr view,Callback callback){if(!view){original_set(view,callback);return;}{std::lock_guard<std::mutex> lock(callback_mutex);previous_callbacks[view]=callback==on_resource?nullptr:callback;}original_set(view,on_resource);}
void __cdecl destroy_view(Ptr view){{std::lock_guard<std::mutex> lock(callback_mutex);previous_callbacks.erase(view);}original_destroy(view);}
void jump(uint8_t* p,void* destination){const uint8_t op[]={0xff,0x25,0,0,0,0};std::memcpy(p,op,6);std::memcpy(p+6,&destination,8);}
void* trampoline(void* target,size_t length){auto memory=static_cast<uint8_t*>(VirtualAlloc(nullptr,length+14,MEM_COMMIT|MEM_RESERVE,PAGE_EXECUTE_READWRITE));if(!memory)throw std::runtime_error("Trampoline allocation");std::memcpy(memory,target,length);jump(memory+length,static_cast<uint8_t*>(target)+length);return memory;}
void detour(void* target,void* destination,size_t length){DWORD old;if(!VirtualProtect(target,length,PAGE_EXECUTE_READWRITE,&old))throw std::runtime_error("Hook protection");jump(static_cast<uint8_t*>(target),destination);std::memset(static_cast<uint8_t*>(target)+14,0x90,length-14);DWORD ignored;VirtualProtect(target,length,old,&ignored);FlushInstructionCache(GetCurrentProcess(),target,length);}
bool setup(const std::filesystem::path& root){
    HMODULE module=GetModuleHandleW(L"Awesomium.dll");if(!module)return false;
    request_url=symbol<decltype(request_url)>(module,"awe_resource_request_get_url");to_utf8=symbol<decltype(to_utf8)>(module,"awe_string_to_utf8");from_wide=symbol<decltype(from_wide)>(module,"awe_string_create_from_wide");destroy_string=symbol<decltype(destroy_string)>(module,"awe_string_destroy");response_create=symbol<decltype(response_create)>(module,"awe_resource_response_create");set_callback=symbol<Set>(module,"awe_webview_set_callback_resource_request");
    return request_url&&to_utf8&&from_wide&&destroy_string&&response_create&&set_callback&&load_index(root);
}
}
extern "C" __declspec(dllexport) int __cdecl AionIconBridgeAttach(Ptr view,const wchar_t* root){try{if(!view||!root||!setup(root))return 0;attach(view);return 1;}catch(...){return 0;}}
extern "C" __declspec(dllexport) size_t __cdecl AionIconBridgeDecode(uint32_t item,uint8_t* output,size_t capacity){try{auto bytes=get_icon(item);if(!bytes)return 0;if(output&&capacity>=bytes->size())std::memcpy(output,bytes->data(),bytes->size());return bytes->size();}catch(...){return 0;}}
extern "C" __declspec(dllexport) int __cdecl AionIconBridgeInitialize(){
    std::call_once(initialize_once,[]{try{
        wchar_t path[32768];DWORD count=GetModuleFileNameW(own_module,path,DWORD(std::size(path)));if(!count||count>=std::size(path))return;
        auto root=std::filesystem::path(path).parent_path().parent_path();std::error_code error;std::filesystem::create_directories(root/L"Logs",error);log_path=root/L"Logs"/(L"NativeIcons."+std::to_wstring(GetCurrentProcessId())+L".log");
        if(!setup(root)){log("Initialization failed: client archive, index or browser API mismatch; native icons unavailable");return;}
        HMODULE module=GetModuleHandleW(L"Awesomium.dll");auto create=symbol<Create>(module,"awe_webcore_create_webview");auto destroy=symbol<Destroy>(module,"awe_webview_destroy");
        const uint8_t create_bytes[]={0x44,0x88,0x44,0x24,0x18,0x89,0x54,0x24,0x10,0x89,0x4c,0x24,0x08,0x48,0x83,0xec,0x48};
        const uint8_t destroy_bytes[]={0x48,0x89,0x4c,0x24,0x08,0x48,0x83,0xec,0x48,0x48,0x8b,0x44,0x24,0x50};
        const uint8_t set_bytes[]={0x48,0x89,0x54,0x24,0x10,0x48,0x89,0x4c,0x24,0x08,0x48,0x8b,0x4c,0x24,0x08};
        if(!create||!destroy||std::memcmp(reinterpret_cast<void*>(create),create_bytes,sizeof(create_bytes))||std::memcmp(reinterpret_cast<void*>(destroy),destroy_bytes,sizeof(destroy_bytes))||std::memcmp(reinterpret_cast<void*>(set_callback),set_bytes,sizeof(set_bytes))){log("Initialization failed: unsupported browser prologue; native icons unavailable");return;}
        original_create=reinterpret_cast<Create>(trampoline(reinterpret_cast<void*>(create),sizeof(create_bytes)));original_destroy=reinterpret_cast<Destroy>(trampoline(reinterpret_cast<void*>(destroy),sizeof(destroy_bytes)));original_set=reinterpret_cast<Set>(trampoline(reinterpret_cast<void*>(set_callback),sizeof(set_bytes)));
        detour(reinterpret_cast<void*>(set_callback),reinterpret_cast<void*>(set_resource),sizeof(set_bytes));detour(reinterpret_cast<void*>(destroy),reinterpret_cast<void*>(destroy_view),sizeof(destroy_bytes));detour(reinterpret_cast<void*>(create),reinterpret_cast<void*>(create_view),sizeof(create_bytes));initialized=true;log("Initialized: original Items.pak, 512-texture memory cache");
    }catch(...){log("Initialization failed: native exception; native icons unavailable");}});return initialized?1:0;
}
BOOL WINAPI DllMain(HINSTANCE module,DWORD reason,LPVOID){if(reason==DLL_PROCESS_ATTACH){own_module=module;DisableThreadLibraryCalls(module);}return TRUE;}
