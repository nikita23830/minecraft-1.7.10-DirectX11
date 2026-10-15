#define WIN32_LEAN_AND_MEAN
#define NOMINMAX
#include <windows.h>
#include <d3d11_1.h>
#include <dxgi1_6.h>
#include <d3dcompiler.h>
#include <jni.h>
#include <cstdint>
#include <cstdio>
#include <cstring>
#include <cmath>
#include <vector>
#include <map>
#include <unordered_map>
#include <algorithm>

#ifndef PD3D_GLFW
#include <GL/gl.h>
#endif

#define SAFE_RELEASE(p) do { if (p) { (p)->Release(); (p) = nullptr; } } while (0)

static FILE* g_log = nullptr;
static void logf(const char* fmt, ...) {
    if (!g_log) g_log = fopen("logs/minecraftd3d-native.log", "w");
    if (!g_log) return;
    va_list ap; va_start(ap, fmt); vfprintf(g_log, fmt, ap); va_end(ap);
    fputc('\n', g_log); fflush(g_log);
}

struct Vertex { uint16_t px, py, pz, pad; uint16_t u, v; uint32_t color; uint16_t block, sky; };
static_assert(sizeof(Vertex) == 20, "vertex");

static const UINT PAGE_VERTS = (64u << 20) / sizeof(Vertex);
static const UINT MAX_QUADS_PER_DRAW = 1u << 18;

struct Page {
    ID3D11Buffer* vb = nullptr;
    std::map<UINT, UINT> freeList;
};
struct Alloc { int page = -1; UINT offset = 0, count = 0; };
struct Section { int x, y, z; Alloc pass[2]; std::vector<uint8_t> trans; float sortX = 0, sortY = 0, sortZ = 0; bool sorted = false; };

static HWND g_parent, g_child;
static ID3D11Device* g_dev;
static ID3D11DeviceContext* g_ctx;
static IDXGISwapChain1* g_swap;
static ID3D11RenderTargetView* g_rtv;
static ID3D11Texture2D* g_depth;
static ID3D11DepthStencilView* g_dsv;
static UINT g_w, g_h;
static bool g_tearing;
static char g_adapter[768];

static ID3D11VertexShader* g_vsWorld; static ID3D11PixelShader* g_psOpaque; static ID3D11PixelShader* g_psTrans;
static ID3D11VertexShader* g_vsQuad; static ID3D11PixelShader* g_psQuad;
static ID3D11InputLayout* g_layout;
static ID3D11Buffer* g_ib; static ID3D11Buffer* g_instVB; static UINT g_instCap; static ID3D11Buffer* g_cb;
static ID3D11RasterizerState* g_rsWorld; static ID3D11RasterizerState* g_rsNoCull;
static ID3D11DepthStencilState* g_dsWrite; static ID3D11DepthStencilState* g_dsRead; static ID3D11DepthStencilState* g_dsOff;
static ID3D11BlendState* g_bsOff; static ID3D11BlendState* g_bsAlpha; static ID3D11BlendState* g_bsPremul;
static ID3D11SamplerState* g_sampAtlas; static ID3D11SamplerState* g_sampLinear;
static ID3D11Texture2D* g_atlas; static ID3D11ShaderResourceView* g_atlasSrv; static int g_atlasW, g_atlasH, g_atlasMips;
static ID3D11Texture2D* g_light; static ID3D11ShaderResourceView* g_lightSrv;

static ID3D11Texture2D* g_gui; static ID3D11ShaderResourceView* g_guiSrv;
#ifndef PD3D_GLFW
typedef HANDLE(WINAPI* PFN_wglDXOpenDeviceNV)(void*);
typedef BOOL(WINAPI* PFN_wglDXCloseDeviceNV)(HANDLE);
typedef HANDLE(WINAPI* PFN_wglDXRegisterObjectNV)(HANDLE, void*, GLuint, GLenum, GLenum);
typedef BOOL(WINAPI* PFN_wglDXUnregisterObjectNV)(HANDLE, HANDLE);
typedef BOOL(WINAPI* PFN_wglDXLockObjectsNV)(HANDLE, GLint, HANDLE*);
static PFN_wglDXOpenDeviceNV pOpenDev;
static PFN_wglDXCloseDeviceNV pCloseDev;
static PFN_wglDXRegisterObjectNV pRegister;
static PFN_wglDXUnregisterObjectNV pUnregister;
static PFN_wglDXLockObjectsNV pLock, pUnlock;
static HANDLE g_glDev, g_glObj; static GLuint g_glTex; static bool g_glLocked;
#endif

static std::vector<Page> g_pages;
static std::unordered_map<int64_t, Section> g_sections;

static ID3D11Query* g_qDisjoint[8]; static int g_qFrame;
static double g_gpuMs; static LARGE_INTEGER g_freq;
struct Stats { float cpuMs, gpuMs, presentMs; int draws, visible, total, quads; int64_t memBytes, uploadBytes; int immDraws, immVerts; };
static Stats g_stats;

static int g_exp;
static int g_worldRepeat = 1;
static char g_label[64] = "x";
static ID3D11Texture2D* g_off;
static ID3D11ShaderResourceView* g_offSrv;
enum { PQ = 8, PT = 5 };
static ID3D11Query* g_pTs[PQ][PT]; static int g_pDraws[PQ]; static float g_pCpu[PQ], g_pPresent[PQ]; static int g_curQ = -1;
struct ProfRec { int frame, draws; float seg[4], total, cpu, present; };
static std::vector<ProfRec> g_prof;
static int g_marked;
static void mark(int i) {
    if (g_curQ < 0) return;
    for (int k = 0; k <= i; k++)
        if (!(g_marked & (1 << k))) { g_ctx->End(g_pTs[g_curQ][k]); g_marked |= 1 << k; }
}
static int64_t g_uploadAccum;
static inline int64_t key(int x, int y, int z) {
    return ((int64_t)(x & 0x3FFFFF) << 42) | ((int64_t)(z & 0x3FFFFF) << 20) | (int64_t)(y & 0xFFFFF);
}

static const char* kWorldHlsl = R"(
cbuffer Frame : register(b0) {
    row_major float4x4 viewProj;
    float4 fogColor;
    float4 fogParams;
};
Texture2D atlas : register(t0);
Texture2D lightmap : register(t1);
SamplerState sAtlas : register(s0);
SamplerState sLinear : register(s1);
struct VSIn { uint4 pos : POSITION; float2 uv : TEXCOORD0; float4 col : COLOR; uint2 light : TEXCOORD1; float4 origin : TEXCOORD2; };
struct PSIn { float4 pos : SV_Position; float2 uv : TEXCOORD0; float4 col : COLOR; float2 luv : TEXCOORD1; float dist : TEXCOORD2; };
PSIn vs(VSIn i) {
    PSIn o;
    float3 p = float3(i.pos.xyz) / 2048.0 - 8.0 + i.origin.xyz;
    o.pos = mul(viewProj, float4(p, 1));
    o.uv = i.uv; o.col = i.col;
    o.luv = (float2(i.light) + 8.0) / 256.0;
    o.dist = fogParams.w > 0.5 ? length(p) : o.pos.w;
    return o;
}
float4 shade(PSIn i, float4 t) {
    float3 l = lightmap.Sample(sLinear, i.luv).rgb;
    float4 c = t * i.col * float4(l, 1);
    float f;
    if (fogParams.y < 0.5) f = saturate((i.dist - fogColor.w) / max(fogParams.x - fogColor.w, 0.001));
    else if (fogParams.y < 1.5) f = 1 - saturate(exp(-fogParams.z * i.dist));
    else f = 1 - saturate(exp(-(fogParams.z * i.dist) * (fogParams.z * i.dist)));
    c.rgb = lerp(c.rgb, fogColor.rgb, f);
    return c;
}
float4 psOpaque(PSIn i) : SV_Target {
    float4 t = atlas.Sample(sAtlas, i.uv);
    clip(t.a - 0.1);
    return float4(shade(i, t).rgb, 1);
}
float4 psOpaqueNoClip(PSIn i) : SV_Target {
    float4 t = atlas.Sample(sAtlas, i.uv);
    return float4(shade(i, t).rgb, 1);
}
float4 psTrans(PSIn i) : SV_Target {
    float4 t = atlas.Sample(sAtlas, i.uv);
    clip(t.a - 0.004);
    return shade(i, t);
}
)";

static const char* kQuadHlsl = R"(
Texture2D gui : register(t0);
SamplerState sPoint : register(s0);
struct PSIn { float4 pos : SV_Position; float2 uv : TEXCOORD0; };
PSIn vs(uint id : SV_VertexID) {
    PSIn o;
    float2 uv = float2((id << 1) & 2, id & 2);
    o.pos = float4(uv * float2(2, -2) + float2(-1, 1), 0, 1);
    o.uv = float2(uv.x, 1 - uv.y);
    return o;
}
float4 ps(PSIn i) : SV_Target { return gui.Sample(sPoint, i.uv); }
)";

static ID3DBlob* compile(const char* src, const char* entry, const char* target) {
    ID3DBlob* code = nullptr; ID3DBlob* err = nullptr;
    HRESULT hr = D3DCompile(src, strlen(src), nullptr, nullptr, nullptr, entry, target, D3DCOMPILE_OPTIMIZATION_LEVEL3, 0, &code, &err);
    if (FAILED(hr)) { logf("shader %s: %s", entry, err ? (const char*)err->GetBufferPointer() : "?"); SAFE_RELEASE(err); return nullptr; }
    SAFE_RELEASE(err);
    return code;
}

#include "api.inc"
#include "immediate.inc"
#include "framebuffer.inc"

static LRESULT CALLBACK childProc(HWND h, UINT m, WPARAM w, LPARAM l) {
    if (m == WM_NCHITTEST) return HTTRANSPARENT;
    if (m == WM_ERASEBKGND) return 1;
    return DefWindowProcW(h, m, w, l);
}

static BOOL CALLBACK findLwjgl(HWND h, LPARAM out) {
    wchar_t cls[64]; GetClassNameW(h, cls, 64);
    if (IsWindowVisible(h) && wcsstr(cls, L"LWJGL")) { *(HWND*)out = h; return FALSE; }
    return TRUE;
}

static void createTargets() {
    ID3D11Texture2D* bb = nullptr;
    g_swap->GetBuffer(0, __uuidof(ID3D11Texture2D), (void**)&bb);
    if (!(g_exp & 4)) {
        D3D11_TEXTURE2D_DESC od; bb->GetDesc(&od);
        od.BindFlags = D3D11_BIND_RENDER_TARGET | D3D11_BIND_SHADER_RESOURCE; od.MiscFlags = 0; od.CPUAccessFlags = 0; od.Usage = D3D11_USAGE_DEFAULT;
        g_dev->CreateTexture2D(&od, nullptr, &g_off);
        g_dev->CreateRenderTargetView(g_off, nullptr, &g_rtv);
        g_dev->CreateShaderResourceView(g_off, nullptr, &g_offSrv);
    } else {
        g_dev->CreateRenderTargetView(bb, nullptr, &g_rtv);
    }
    SAFE_RELEASE(bb);
    D3D11_TEXTURE2D_DESC td = {};
    td.Width = g_w; td.Height = g_h; td.MipLevels = 1; td.ArraySize = 1; td.Format = (g_exp & 2) ? DXGI_FORMAT_D24_UNORM_S8_UINT : DXGI_FORMAT_D32_FLOAT;
    td.SampleDesc.Count = 1; td.Usage = D3D11_USAGE_DEFAULT; td.BindFlags = D3D11_BIND_DEPTH_STENCIL;
    g_dev->CreateTexture2D(&td, nullptr, &g_depth);
    g_dev->CreateDepthStencilView(g_depth, nullptr, &g_dsv);
}

static DXGI_SWAP_CHAIN_DESC1 g_sd;
#ifndef PD3D_GLFW
static int rebindWindow();
#endif

static void checkResize() {
#ifndef PD3D_GLFW
    if (!IsWindow(g_parent) && rebindWindow() != 0) return;
#endif
    RECT rc; GetClientRect(g_parent, &rc);
    if (IsIconic(g_parent) || rc.right - rc.left <= 0 || rc.bottom - rc.top <= 0) return;
    UINT w = (UINT)(rc.right - rc.left), h = (UINT)(rc.bottom - rc.top);
    if (w == g_w && h == g_h) return;
    g_w = w; g_h = h;
    if (g_child != g_parent) MoveWindow(g_child, 0, 0, w, h, FALSE);
    g_ctx->OMSetRenderTargets(0, nullptr, nullptr);
    SAFE_RELEASE(g_rtv); SAFE_RELEASE(g_dsv); SAFE_RELEASE(g_depth); SAFE_RELEASE(g_offSrv); SAFE_RELEASE(g_off);
    g_swap->ResizeBuffers(0, w, h, DXGI_FORMAT_UNKNOWN, g_tearing ? DXGI_SWAP_CHAIN_FLAG_ALLOW_TEARING : 0);
    createTargets();
    logf("resize %ux%u", w, h);
}

static void makeQuadIndices() {
    std::vector<uint32_t> idx(MAX_QUADS_PER_DRAW * 6);
    for (UINT q = 0; q < MAX_QUADS_PER_DRAW; q++) {
        uint32_t b = q * 4;
        uint32_t* p = &idx[q * 6];
        p[0] = b; p[1] = b + 1; p[2] = b + 2; p[3] = b + 2; p[4] = b + 3; p[5] = b;
    }
    D3D11_BUFFER_DESC bd = {}; bd.ByteWidth = (UINT)(idx.size() * 4); bd.Usage = D3D11_USAGE_IMMUTABLE; bd.BindFlags = D3D11_BIND_INDEX_BUFFER;
    D3D11_SUBRESOURCE_DATA sd = { idx.data() };
    g_dev->CreateBuffer(&bd, &sd, &g_ib);
}

static void ensureInstCap(UINT n) {
    if (n <= g_instCap) return;
    SAFE_RELEASE(g_instVB);
    g_instCap = std::max<UINT>(n, g_instCap * 2);
    D3D11_BUFFER_DESC bd = {}; bd.ByteWidth = g_instCap * 16; bd.Usage = D3D11_USAGE_DYNAMIC;
    bd.BindFlags = D3D11_BIND_VERTEX_BUFFER; bd.CPUAccessFlags = D3D11_CPU_ACCESS_WRITE;
    g_dev->CreateBuffer(&bd, nullptr, &g_instVB);
}

static void describeAdapter(IDXGIAdapter* ad, IDXGIFactory2* f2, DXGI_SWAP_EFFECT eff) {
    char name[256] = "?";
    unsigned long long vram = 0;
    IDXGIAdapter1* a1 = nullptr;
    if (SUCCEEDED(ad->QueryInterface(__uuidof(IDXGIAdapter1), (void**)&a1))) {
        DXGI_ADAPTER_DESC1 d; a1->GetDesc1(&d);
        WideCharToMultiByte(CP_UTF8, 0, d.Description, -1, name, sizeof(name), nullptr, nullptr);
        vram = d.DedicatedVideoMemory;
        a1->Release();
    }
    char drv[64] = "?";
    LARGE_INTEGER umd;
    if (SUCCEEDED(ad->CheckInterfaceSupport(__uuidof(IDXGIDevice), &umd)))
        snprintf(drv, sizeof(drv), "%u.%u.%u.%u", (unsigned)(umd.HighPart >> 16), (unsigned)(umd.HighPart & 0xFFFF),
                 (unsigned)(umd.LowPart >> 16), (unsigned)(umd.LowPart & 0xFFFF));
    D3D_FEATURE_LEVEL fl = g_dev->GetFeatureLevel();
    ID3D11Device1* d1 = nullptr;
    bool rt111 = SUCCEEDED(g_dev->QueryInterface(__uuidof(ID3D11Device1), (void**)&d1));
    if (d1) d1->Release();
    const char* dxgi = "1.2";
    IUnknown* f = nullptr;
    if (SUCCEEDED(f2->QueryInterface(__uuidof(IDXGIFactory6), (void**)&f))) dxgi = "1.6";
    else if (SUCCEEDED(f2->QueryInterface(__uuidof(IDXGIFactory5), (void**)&f))) dxgi = "1.5";
    if (f) f->Release();
    snprintf(g_adapter, sizeof(g_adapter),
             "Direct3D %s (Feature-Level %d_%d), DXGI %s - %s, Treiber %s, Videospeicher %.0f GB, Ausgabe %s%s",
             rt111 ? "11.1" : "11.0", (fl >> 12) & 0xF, (fl >> 8) & 0xF, dxgi, name, drv, vram / (1024.0 * 1024 * 1024),
             eff == DXGI_SWAP_EFFECT_FLIP_DISCARD ? "flip" : "blt", g_tearing ? " (tearing)" : "");
    logf("%s", g_adapter);
}

static int g_flags;

#ifndef PD3D_GLFW
static int rebindWindow() {
    HWND hwnd = nullptr;
    EnumThreadWindows(GetCurrentThreadId(), findLwjgl, (LPARAM)&hwnd);
    if (!hwnd) { logf("rebind: LWJGL-Fenster nicht gefunden"); return -1; }
    g_ctx->OMSetRenderTargets(0, nullptr, nullptr);
    g_ctx->Flush();
    SAFE_RELEASE(g_rtv); SAFE_RELEASE(g_dsv); SAFE_RELEASE(g_depth); SAFE_RELEASE(g_offSrv); SAFE_RELEASE(g_off);
    SAFE_RELEASE(g_swap);
    if (g_child && g_child != g_parent && IsWindow(g_child)) DestroyWindow(g_child);
    g_parent = hwnd;
    SetWindowLongPtrW(g_parent, GWL_STYLE, GetWindowLongPtrW(g_parent, GWL_STYLE) | WS_CLIPCHILDREN);
    RECT rc; GetClientRect(g_parent, &rc);
    g_w = std::max<LONG>(1, rc.right); g_h = std::max<LONG>(1, rc.bottom);
    if (g_flags & 1) g_child = g_parent;
    else {
        g_child = CreateWindowExW(WS_EX_NOPARENTNOTIFY, L"MinecraftD3DChild", L"", WS_CHILD | WS_VISIBLE | WS_DISABLED | WS_CLIPSIBLINGS,
                                  0, 0, g_w, g_h, g_parent, nullptr, GetModuleHandleW(nullptr), nullptr);
        if (!g_child) { logf("rebind: CreateWindow child failed %lu", GetLastError()); return -2; }
    }
    IDXGIDevice1* xd; g_dev->QueryInterface(__uuidof(IDXGIDevice1), (void**)&xd);
    IDXGIAdapter* ad; xd->GetAdapter(&ad);
    IDXGIFactory2* f2; ad->GetParent(__uuidof(IDXGIFactory2), (void**)&f2);
    DXGI_SWAP_CHAIN_DESC1 sd = g_sd; sd.Width = g_w; sd.Height = g_h;
    HRESULT hr = f2->CreateSwapChainForHwnd(g_dev, g_child, &sd, nullptr, nullptr, &g_swap);
    f2->MakeWindowAssociation(g_child, DXGI_MWA_NO_ALT_ENTER);
    f2->Release(); ad->Release(); xd->Release();
    if (FAILED(hr)) { logf("rebind: CreateSwapChainForHwnd %08lx", hr); return -4; }
    createTargets();
    logf("rebind: neues LWJGL-Fenster %p, %ux%u", (void*)g_parent, g_w, g_h);
    return 0;
}
#endif

static int initDevice(HWND hwnd) {
#ifdef PD3D_GLFW
    if (!hwnd || !IsWindow(hwnd)) { logf("bad HWND %p", (void*)hwnd); return -1; }
    g_parent = hwnd;
    g_flags |= 1;
#else
    if (!hwnd) EnumThreadWindows(GetCurrentThreadId(), findLwjgl, (LPARAM)&hwnd);
    if (!hwnd) { logf("LWJGL window not found on this thread"); return -1; }
    g_parent = hwnd;
    SetWindowLongPtrW(g_parent, GWL_STYLE, GetWindowLongPtrW(g_parent, GWL_STYLE) | WS_CLIPCHILDREN);
#endif

    WNDCLASSW wc = {}; wc.lpfnWndProc = childProc; wc.hInstance = GetModuleHandleW(nullptr); wc.lpszClassName = L"MinecraftD3DChild";
    wc.hCursor = nullptr;
    RegisterClassW(&wc);
    RECT rc; GetClientRect(g_parent, &rc);
    g_w = std::max<LONG>(1, rc.right); g_h = std::max<LONG>(1, rc.bottom);
    if (g_flags & 1) {
        g_child = g_parent;
    } else {
        g_child = CreateWindowExW(WS_EX_NOPARENTNOTIFY, L"MinecraftD3DChild", L"", WS_CHILD | WS_VISIBLE | WS_DISABLED | WS_CLIPSIBLINGS,
                                  0, 0, g_w, g_h, g_parent, nullptr, wc.hInstance, nullptr);
        if (!g_child) { logf("CreateWindow child failed %lu", GetLastError()); return -2; }
    }

    D3D_FEATURE_LEVEL fls[] = { D3D_FEATURE_LEVEL_11_1, D3D_FEATURE_LEVEL_11_0 };
    UINT flags = D3D11_CREATE_DEVICE_BGRA_SUPPORT;
    HRESULT hr = D3D11CreateDevice(nullptr, D3D_DRIVER_TYPE_HARDWARE, nullptr, flags, fls, 2, D3D11_SDK_VERSION, &g_dev, nullptr, &g_ctx);
    if (FAILED(hr)) { logf("D3D11CreateDevice %08lx", hr); return -3; }

    IDXGIDevice1* xd; g_dev->QueryInterface(__uuidof(IDXGIDevice1), (void**)&xd);
    if (g_flags & 2) xd->SetMaximumFrameLatency(1);
    IDXGIAdapter* ad; xd->GetAdapter(&ad);
    DXGI_ADAPTER_DESC adesc; ad->GetDesc(&adesc);
    logf("adapter: %ls", adesc.Description);
    IDXGIFactory2* f2; ad->GetParent(__uuidof(IDXGIFactory2), (void**)&f2);
    IDXGIFactory5* f5 = nullptr;
    if (SUCCEEDED(f2->QueryInterface(__uuidof(IDXGIFactory5), (void**)&f5))) {
        BOOL t = FALSE; f5->CheckFeatureSupport(DXGI_FEATURE_PRESENT_ALLOW_TEARING, &t, sizeof(t)); g_tearing = t; f5->Release();
    }
    DXGI_SWAP_CHAIN_DESC1 sd = {};
    sd.Width = g_w; sd.Height = g_h; sd.Format = DXGI_FORMAT_B8G8R8A8_UNORM; sd.SampleDesc.Count = 1;
    sd.BufferUsage = DXGI_USAGE_RENDER_TARGET_OUTPUT; sd.BufferCount = 2; sd.SwapEffect = DXGI_SWAP_EFFECT_FLIP_DISCARD;
    if (g_flags & 4) {
        g_tearing = false;
        sd.SwapEffect = DXGI_SWAP_EFFECT_DISCARD; sd.BufferCount = 1;
    }
    sd.Flags = g_tearing ? DXGI_SWAP_CHAIN_FLAG_ALLOW_TEARING : 0;
    g_sd = sd;
    hr = f2->CreateSwapChainForHwnd(g_dev, g_child, &sd, nullptr, nullptr, &g_swap);
    f2->MakeWindowAssociation(g_child, DXGI_MWA_NO_ALT_ENTER);
    describeAdapter(ad, f2, sd.SwapEffect);
    f2->Release(); ad->Release(); xd->Release();
    if (FAILED(hr)) { logf("CreateSwapChainForHwnd %08lx", hr); return -4; }
    createTargets();

    ID3DBlob* b;
    if (!(b = compile(kWorldHlsl, "vs", "vs_5_0"))) return -5;
    g_dev->CreateVertexShader(b->GetBufferPointer(), b->GetBufferSize(), nullptr, &g_vsWorld);
    D3D11_INPUT_ELEMENT_DESC il[] = {
        { "POSITION", 0, DXGI_FORMAT_R16G16B16A16_UINT, 0, 0, D3D11_INPUT_PER_VERTEX_DATA, 0 },
        { "TEXCOORD", 0, DXGI_FORMAT_R16G16_UNORM, 0, 8, D3D11_INPUT_PER_VERTEX_DATA, 0 },
        { "COLOR", 0, DXGI_FORMAT_R8G8B8A8_UNORM, 0, 12, D3D11_INPUT_PER_VERTEX_DATA, 0 },
        { "TEXCOORD", 1, DXGI_FORMAT_R16G16_UINT, 0, 16, D3D11_INPUT_PER_VERTEX_DATA, 0 },
        { "TEXCOORD", 2, DXGI_FORMAT_R32G32B32A32_FLOAT, 1, 0, D3D11_INPUT_PER_INSTANCE_DATA, 1 },
    };
    g_dev->CreateInputLayout(il, 5, b->GetBufferPointer(), b->GetBufferSize(), &g_layout);
    b->Release();
    if (!(b = compile(kWorldHlsl, (g_exp & 8) ? "psOpaqueNoClip" : "psOpaque", "ps_5_0"))) return -5;
    g_dev->CreatePixelShader(b->GetBufferPointer(), b->GetBufferSize(), nullptr, &g_psOpaque); b->Release();
    if (!(b = compile(kWorldHlsl, "psTrans", "ps_5_0"))) return -5;
    g_dev->CreatePixelShader(b->GetBufferPointer(), b->GetBufferSize(), nullptr, &g_psTrans); b->Release();
    if (!(b = compile(kQuadHlsl, "vs", "vs_5_0"))) return -5;
    g_dev->CreateVertexShader(b->GetBufferPointer(), b->GetBufferSize(), nullptr, &g_vsQuad); b->Release();
    if (!(b = compile(kQuadHlsl, "ps", "ps_5_0"))) return -5;
    g_dev->CreatePixelShader(b->GetBufferPointer(), b->GetBufferSize(), nullptr, &g_psQuad); b->Release();

    makeQuadIndices();
    ensureInstCap(16384);
    D3D11_BUFFER_DESC cbd = {}; cbd.ByteWidth = 256; cbd.Usage = D3D11_USAGE_DYNAMIC; cbd.BindFlags = D3D11_BIND_CONSTANT_BUFFER; cbd.CPUAccessFlags = D3D11_CPU_ACCESS_WRITE;
    g_dev->CreateBuffer(&cbd, nullptr, &g_cb);

    D3D11_RASTERIZER_DESC rd = {}; rd.FillMode = D3D11_FILL_SOLID; rd.CullMode = D3D11_CULL_BACK; rd.FrontCounterClockwise = TRUE; rd.DepthClipEnable = TRUE;
    g_dev->CreateRasterizerState(&rd, &g_rsWorld);
    rd.CullMode = D3D11_CULL_NONE; g_dev->CreateRasterizerState(&rd, &g_rsNoCull);
    D3D11_DEPTH_STENCIL_DESC dd = {}; dd.DepthEnable = TRUE; dd.DepthWriteMask = D3D11_DEPTH_WRITE_MASK_ALL; dd.DepthFunc = (g_exp & 1) ? D3D11_COMPARISON_LESS_EQUAL : D3D11_COMPARISON_GREATER_EQUAL;
    g_dev->CreateDepthStencilState(&dd, &g_dsWrite);
    dd.DepthWriteMask = D3D11_DEPTH_WRITE_MASK_ZERO; g_dev->CreateDepthStencilState(&dd, &g_dsRead);
    dd.DepthEnable = FALSE; g_dev->CreateDepthStencilState(&dd, &g_dsOff);
    D3D11_BLEND_DESC bd = {}; bd.RenderTarget[0].RenderTargetWriteMask = D3D11_COLOR_WRITE_ENABLE_ALL;
    g_dev->CreateBlendState(&bd, &g_bsOff);
    bd.RenderTarget[0].BlendEnable = TRUE;
    bd.RenderTarget[0].SrcBlend = D3D11_BLEND_SRC_ALPHA; bd.RenderTarget[0].DestBlend = D3D11_BLEND_INV_SRC_ALPHA; bd.RenderTarget[0].BlendOp = D3D11_BLEND_OP_ADD;
    bd.RenderTarget[0].SrcBlendAlpha = D3D11_BLEND_ONE; bd.RenderTarget[0].DestBlendAlpha = D3D11_BLEND_INV_SRC_ALPHA; bd.RenderTarget[0].BlendOpAlpha = D3D11_BLEND_OP_ADD;
    g_dev->CreateBlendState(&bd, &g_bsAlpha);
    bd.RenderTarget[0].SrcBlend = D3D11_BLEND_ONE;
    g_dev->CreateBlendState(&bd, &g_bsPremul);
    D3D11_SAMPLER_DESC sm = {}; sm.Filter = D3D11_FILTER_MIN_MAG_POINT_MIP_LINEAR; sm.AddressU = sm.AddressV = sm.AddressW = D3D11_TEXTURE_ADDRESS_CLAMP; sm.MaxLOD = D3D11_FLOAT32_MAX;
    g_dev->CreateSamplerState(&sm, &g_sampAtlas);
    sm.Filter = D3D11_FILTER_MIN_MAG_MIP_LINEAR; g_dev->CreateSamplerState(&sm, &g_sampLinear);

    D3D11_TEXTURE2D_DESC td = {}; td.Width = 16; td.Height = 16; td.MipLevels = 1; td.ArraySize = 1; td.Format = DXGI_FORMAT_B8G8R8A8_UNORM;
    td.SampleDesc.Count = 1; td.Usage = D3D11_USAGE_DEFAULT; td.BindFlags = D3D11_BIND_SHADER_RESOURCE;
    g_dev->CreateTexture2D(&td, nullptr, &g_light); g_dev->CreateShaderResourceView(g_light, nullptr, &g_lightSrv);

    QueryPerformanceFrequency(&g_freq);
    for (int i = 0; i < PQ; i++) {
        D3D11_QUERY_DESC qd = { D3D11_QUERY_TIMESTAMP_DISJOINT, 0 };
        g_dev->CreateQuery(&qd, &g_qDisjoint[i]);
        qd.Query = D3D11_QUERY_TIMESTAMP;
        for (int k = 0; k < PT; k++) g_dev->CreateQuery(&qd, &g_pTs[i][k]);
    }
    logf("exp=%d", g_exp);
    if (initImmediate() != 0) return -6;
    if (initFramebuffer() != 0) return -7;
    logf("init ok %ux%u tearing=%d", g_w, g_h, (int)g_tearing);
    return 0;
}

#ifndef PD3D_GLFW
static void releaseGui() {
    if (g_glObj) {
        if (g_glLocked) { pUnlock(g_glDev, 1, &g_glObj); g_glLocked = false; }
        pUnregister(g_glDev, g_glObj); g_glObj = nullptr;
    }
    if (g_glTex) { glDeleteTextures(1, &g_glTex); g_glTex = 0; }
    SAFE_RELEASE(g_guiSrv); SAFE_RELEASE(g_gui);
}

static int initGui(int w, int h) {
    if (!pOpenDev) {
        pOpenDev = (PFN_wglDXOpenDeviceNV)wglGetProcAddress("wglDXOpenDeviceNV");
        pCloseDev = (PFN_wglDXCloseDeviceNV)wglGetProcAddress("wglDXCloseDeviceNV");
        pRegister = (PFN_wglDXRegisterObjectNV)wglGetProcAddress("wglDXRegisterObjectNV");
        pUnregister = (PFN_wglDXUnregisterObjectNV)wglGetProcAddress("wglDXUnregisterObjectNV");
        pLock = (PFN_wglDXLockObjectsNV)wglGetProcAddress("wglDXLockObjectsNV");
        pUnlock = (PFN_wglDXLockObjectsNV)wglGetProcAddress("wglDXUnlockObjectsNV");
        if (!pOpenDev || !pRegister || !pLock) { logf("WGL_NV_DX_interop missing"); return -1; }
        g_glDev = pOpenDev(g_dev);
        if (!g_glDev) { logf("wglDXOpenDeviceNV failed %lu", GetLastError()); return -1; }
    }
    releaseGui();
    D3D11_TEXTURE2D_DESC td = {}; td.Width = w; td.Height = h; td.MipLevels = 1; td.ArraySize = 1; td.Format = DXGI_FORMAT_R8G8B8A8_UNORM;
    td.SampleDesc.Count = 1; td.Usage = D3D11_USAGE_DEFAULT; td.BindFlags = D3D11_BIND_SHADER_RESOURCE | D3D11_BIND_RENDER_TARGET;
    if (FAILED(g_dev->CreateTexture2D(&td, nullptr, &g_gui))) return -2;
    g_dev->CreateShaderResourceView(g_gui, nullptr, &g_guiSrv);
    glGenTextures(1, &g_glTex);
    g_glObj = pRegister(g_glDev, g_gui, g_glTex, GL_TEXTURE_2D, 0x0002  );
    if (!g_glObj) { logf("wglDXRegisterObjectNV failed %lu", GetLastError()); return -3; }
    g_glLocked = pLock(g_glDev, 1, &g_glObj) != 0;
    logf("gui interop %dx%d gltex=%u", w, h, g_glTex);
    return (int)g_glTex;
}
#endif

static bool allocFromPage(Page& p, UINT n, UINT& off) {
    for (auto it = p.freeList.begin(); it != p.freeList.end(); ++it) {
        if (it->second >= n) {
            off = it->first; UINT rest = it->second - n; p.freeList.erase(it);
            if (rest) p.freeList[off + n] = rest;
            return true;
        }
    }
    return false;
}

static Alloc allocVerts(UINT n) {
    Alloc a; a.count = n;
    if (n > PAGE_VERTS) { logf("section too big: %u verts", n); a.count = 0; return a; }
    for (size_t i = 0; i < g_pages.size(); i++)
        if (allocFromPage(g_pages[i], n, a.offset)) { a.page = (int)i; return a; }
    Page p;
    D3D11_BUFFER_DESC bd = {}; bd.ByteWidth = PAGE_VERTS * sizeof(Vertex); bd.Usage = D3D11_USAGE_DEFAULT; bd.BindFlags = D3D11_BIND_VERTEX_BUFFER;
    if (FAILED(g_dev->CreateBuffer(&bd, nullptr, &p.vb))) { logf("page alloc failed"); a.count = 0; return a; }
    p.freeList[0] = PAGE_VERTS;
    g_pages.push_back(p);
    logf("new vertex page #%zu", g_pages.size() - 1);
    allocFromPage(g_pages.back(), n, a.offset); a.page = (int)g_pages.size() - 1;
    return a;
}

static void freeVerts(Alloc& a) {
    if (a.page < 0 || !a.count) { a = Alloc(); return; }
    auto& fl = g_pages[a.page].freeList;
    UINT off = a.offset, n = a.count;
    auto next = fl.lower_bound(off);
    if (next != fl.end() && off + n == next->first) { n += next->second; next = fl.erase(next); }
    if (next != fl.begin()) {
        auto prev = std::prev(next);
        if (prev->first + prev->second == off) { prev->second += n; a = Alloc(); return; }
    }
    fl[off] = n;
    a = Alloc();
}

static int64_t usedBytes() {
    int64_t used = 0;
    for (auto& p : g_pages) { int64_t fr = 0; for (auto& f : p.freeList) fr += f.second; used += (int64_t)(PAGE_VERTS - fr) * sizeof(Vertex); }
    return used;
}

#pragma pack(push, 1)
struct FrameDesc {
    int32_t flags;
    int32_t camSec[3];
    float camFrac[3];
    float viewProj[16];
    float fog[4];
    float fogEnd;
    float sky[3];
    int32_t renderDistSections;
    uint32_t lightmap[256];
    int32_t atlasTex;
    int32_t fogMode;
    float fogDensity;
};
#pragma pack(pop)

struct DrawItem { const Section* s; float ox, oy, oz; float dist2; };
static std::vector<DrawItem> g_opaque, g_trans;
static std::vector<float> g_inst;

static void extractPlanes(const float* m, float planes[6][4]) {
    const float* r0 = m; const float* r1 = m + 4; const float* r2 = m + 8; const float* r3 = m + 12;
    for (int i = 0; i < 4; i++) {
        planes[0][i] = r3[i] + r0[i]; planes[1][i] = r3[i] - r0[i];
        planes[2][i] = r3[i] + r1[i]; planes[3][i] = r3[i] - r1[i];
        planes[4][i] = r2[i];         planes[5][i] = r3[i] - r2[i];
    }
}

static bool boxVisible(const float planes[6][4], float x, float y, float z) {
    for (int i = 0; i < 6; i++) {
        const float* p = planes[i];
        float px = p[0] >= 0 ? x + 16 : x, py = p[1] >= 0 ? y + 16 : y, pz = p[2] >= 0 ? z + 16 : z;
        if (p[0] * px + p[1] * py + p[2] * pz + p[3] < 0) return false;
    }
    return true;
}

static void drawList(std::vector<DrawItem>& list, int pass, UINT instBase) {
    int curPage = -1;
    UINT strides[2] = { sizeof(Vertex), 16 }, offs[2] = { 0, 0 };
    for (size_t i = 0; i < list.size(); i++) {
        const Alloc& a = list[i].s->pass[pass];
        if (a.page != curPage) {
            ID3D11Buffer* vbs[2] = { g_pages[a.page].vb, g_instVB };
            g_ctx->IASetVertexBuffers(0, 2, vbs, strides, offs);
            curPage = a.page;
        }
        UINT quads = a.count / 4;
        if (g_exp & 32) continue;
        if (g_exp & 16) quads = std::min(quads, 1u);
        for (UINT q = 0; q < quads; q += MAX_QUADS_PER_DRAW) {
            UINT n = std::min(quads - q, MAX_QUADS_PER_DRAW);
            g_ctx->DrawIndexedInstanced(n * 6, 1, 0, (INT)(a.offset + q * 4), instBase + (UINT)i);
            g_stats.draws++;
            g_stats.quads += n;
        }
    }
}

static int g_worldMask = 15;
static void renderWorld(const FrameDesc* fd, bool splitTranslucent, const float* viewProj);
static void renderWorldTranslucent();
static void finishFrame(const FrameDesc* fd, int qi, LARGE_INTEGER t0);

static bool g_frameOpen, g_worldDrawn;
static LARGE_INTEGER g_frameT0;
static int g_frameVerts;

static void beginFrame(const FrameDesc* fd) {
    if (g_frameOpen) return;
    g_frameOpen = true; g_worldDrawn = false; g_frameVerts = 0;
    QueryPerformanceCounter(&g_frameT0);
    g_stats.draws = 0; g_stats.visible = 0; g_stats.quads = 0; g_immDraws = 0;
    checkResize();

    g_curQ = g_qFrame % PQ; g_marked = 0;
    g_ctx->Begin(g_qDisjoint[g_curQ]);
    mark(0);
#ifndef PD3D_GLFW
    if (g_glLocked) { pUnlock(g_glDev, 1, &g_glObj); g_glLocked = false; }
#endif

    float clear[4] = { fd->sky[0], fd->sky[1], fd->sky[2], 1 };
    if (!(fd->flags & 1)) { clear[0] = clear[1] = clear[2] = 0; }
    g_ctx->ClearRenderTargetView(g_rtv, clear);
    const float worldDepth = (g_exp & 1) ? 1.0f : 0.0f;
    g_ctx->ClearDepthStencilView(g_dsv, D3D11_CLEAR_DEPTH, worldDepth, 0);
    setDefaultTarget();
    applyTarget();
}

static void runSegment(const FrameDesc* fd, const ImmCmd* imm, int nimm, const ImmVertex* iv, int niv) {
    const float worldDepth = (g_exp & 1) ? 1.0f : 0.0f;
    immSetFog(fd->fog, fd->fogEnd);
    prepareImmediate(imm, nimm, iv, niv);
    g_frameVerts += niv;
    UINT immInst = 0;
    int from = 0;
    for (int i = 0; i < nimm; i++) {
        if (imm[i].type == IMM_WORLD) {
            execImmediate(imm, from, i, immInst);
            g_ctx->ClearDepthStencilView(g_dsv, D3D11_CLEAR_DEPTH, worldDepth, 0);
            g_ctx->OMSetRenderTargets(1, &g_rtv, g_dsv);
            D3D11_VIEWPORT vp = { 0, 0, (float)g_w, (float)g_h, 0, 1 };
            g_ctx->RSSetViewports(1, &vp);
            bool eye = (imm[i].topology & 2) != 0;
            g_worldMask = eye ? imm[i].colorMask : 15;
            renderWorld(fd, (imm[i].topology & 1) != 0, eye ? imm[i].proj : fd->viewProj);
            g_worldDrawn = true;
            from = i + 1;
        } else if (imm[i].type == IMM_WORLD_TRANS) {
            execImmediate(imm, from, i, immInst);
            renderWorldTranslucent();
            from = i + 1;
        }
    }
    if (!g_worldDrawn && imm == nullptr) { g_worldMask = 15; renderWorld(fd, false, fd->viewProj); g_worldDrawn = true; }
    execImmediate(imm, from, nimm, immInst);
}

static void renderFrame(const FrameDesc* fd, const ImmCmd* imm, int nimm, const ImmVertex* iv, int niv) {
    beginFrame(fd);
    LARGE_INTEGER t0 = g_frameT0;
    int qi = g_curQ;
    runSegment(fd, imm, nimm, iv, niv);
    g_stats.immDraws = g_immDraws; g_stats.immVerts = g_frameVerts;
    setDefaultTarget();

    if (g_guiSrv) {
        g_ctx->OMSetRenderTargets(1, &g_rtv, nullptr);
        g_ctx->IASetInputLayout(nullptr);
        g_ctx->IASetPrimitiveTopology(D3D11_PRIMITIVE_TOPOLOGY_TRIANGLELIST);
        g_ctx->VSSetShader(g_vsQuad, nullptr, 0);
        g_ctx->PSSetShader(g_psQuad, nullptr, 0);
        g_ctx->PSSetShaderResources(0, 1, &g_guiSrv);
        g_ctx->PSSetSamplers(0, 1, &g_sampAtlas);
        g_ctx->RSSetState(g_rsNoCull);
        g_ctx->OMSetDepthStencilState(g_dsOff, 0);
        g_ctx->OMSetBlendState(g_bsPremul, nullptr, 0xffffffff);
        g_ctx->Draw(3, 0);
        ID3D11ShaderResourceView* nul = nullptr; g_ctx->PSSetShaderResources(0, 1, &nul);
    }
    finishFrame(fd, qi, t0);
    g_frameOpen = false;
}

static int g_sortBudget = 16, g_sortedThisFrame;
static std::vector<std::pair<float, int>> g_sortKeys;
static std::vector<uint8_t> g_sortTmp;

static void sortSection(Section& s, float camX, float camY, float camZ) {
    const UINT stride = (UINT)sizeof(Vertex);
    size_t nq = s.trans.size() / (stride * 4);
    if (nq == 0 || !s.pass[1].count) return;
    g_sortKeys.resize(nq);
    for (size_t q = 0; q < nq; q++) {
        float sx = 0, sy = 0, sz = 0;
        for (int v = 0; v < 4; v++) {
            const Vertex* vx = (const Vertex*)(s.trans.data() + (q * 4 + v) * stride);
            sx += vx->px; sy += vx->py; sz += vx->pz;
        }
        float cx = sx / (4 * 2048.0f) - 8 - camX, cy = sy / (4 * 2048.0f) - 8 - camY, cz = sz / (4 * 2048.0f) - 8 - camZ;
        g_sortKeys[q] = { cx * cx + cy * cy + cz * cz, (int)q };
    }
    std::stable_sort(g_sortKeys.begin(), g_sortKeys.end(), [](const std::pair<float, int>& a, const std::pair<float, int>& b) { return a.first > b.first; });
    g_sortTmp.resize(s.trans.size());
    for (size_t q = 0; q < nq; q++) memcpy(g_sortTmp.data() + q * 4 * stride, s.trans.data() + (size_t)g_sortKeys[q].second * 4 * stride, 4 * stride);
    s.trans.swap(g_sortTmp);
    Alloc& a = s.pass[1];
    UINT cnt = std::min<UINT>(a.count, (UINT)(nq * 4));
    D3D11_BOX box = { a.offset * stride, 0, 0, (a.offset + cnt) * stride, 1, 1 };
    g_ctx->UpdateSubresource(g_pages[a.page].vb, 0, &box, s.trans.data(), 0, 0);
    s.sortX = camX; s.sortY = camY; s.sortZ = camZ; s.sorted = true;
    g_sortedThisFrame++;
}

static void sortTranslucent() {
    g_sortedThisFrame = 0;
    if (g_exp & 64) return;
    for (size_t i = g_trans.size(); i-- > 0 && g_sortedThisFrame < g_sortBudget;) {
        DrawItem& d = g_trans[i];
        Section& s = const_cast<Section&>(*d.s);
        float camX = -d.ox, camY = -d.oy, camZ = -d.oz;
        if (s.sorted) {
            float dx = camX - s.sortX, dy = camY - s.sortY, dz = camZ - s.sortZ;
            if (dx * dx + dy * dy + dz * dz < 1.0f) continue;
        }
        sortSection(s, camX, camY, camZ);
    }
}

static bool g_transPending;
static ID3D11ShaderResourceView* g_worldAtlasSrv;

static void bindWorldPipeline() {
    UINT strides[2] = { sizeof(Vertex), 16 }, offs[2] = { 0, 0 };
    (void)strides; (void)offs;
    g_ctx->IASetInputLayout(g_layout);
    g_ctx->IASetIndexBuffer(g_ib, DXGI_FORMAT_R32_UINT, 0);
    g_ctx->IASetPrimitiveTopology(D3D11_PRIMITIVE_TOPOLOGY_TRIANGLELIST);
    g_ctx->VSSetShader(g_vsWorld, nullptr, 0);
    g_ctx->VSSetConstantBuffers(0, 1, &g_cb);
    g_ctx->PSSetConstantBuffers(0, 1, &g_cb);
    ID3D11ShaderResourceView* srvs[2] = { g_worldAtlasSrv, g_lightSrv };
    g_ctx->PSSetShaderResources(0, 2, srvs);
    ID3D11SamplerState* ss[2] = { g_sampAtlas, g_sampLinear };
    g_ctx->PSSetSamplers(0, 2, ss);
}

static void drawTranslucentPass() {
    g_ctx->OMSetDepthStencilState(g_dsRead, 0);
    g_ctx->OMSetBlendState(g_worldMask == 15 ? g_bsAlpha : blendState(true, 0x302, 0x303, 1, 0x303, g_worldMask), nullptr, 0xffffffff);
    g_ctx->RSSetState(g_rsWorld);
    g_ctx->PSSetShader(g_psTrans, nullptr, 0);
    drawList(g_trans, 1, (UINT)g_opaque.size());
}

static void renderWorldTranslucent() {
    if (!g_transPending) return;
    g_transPending = false;
    if (g_trans.empty()) return;
    g_ctx->OMSetRenderTargets(1, &g_rtv, g_dsv);
    D3D11_VIEWPORT vp = { 0, 0, (float)g_w, (float)g_h, 0, 1 };
    g_ctx->RSSetViewports(1, &vp);
    bindWorldPipeline();
    drawTranslucentPass();
    ID3D11ShaderResourceView* nul[2] = { nullptr, nullptr }; g_ctx->PSSetShaderResources(0, 2, nul);
}

static void renderWorld(const FrameDesc* fd, bool splitTranslucent, const float* viewProj) {
    ID3D11ShaderResourceView* atlasSrv = g_atlasSrv;
    if (fd->atlasTex) { auto it = g_tex.find(fd->atlasTex); atlasSrv = it != g_tex.end() ? it->second.srv : nullptr; }
    mark(1);
    if ((fd->flags & 1) && atlasSrv && !g_sections.empty()) {
        g_ctx->UpdateSubresource(g_light, 0, nullptr, fd->lightmap, 16 * 4, 0);
        D3D11_MAPPED_SUBRESOURCE ms;
        g_ctx->Map(g_cb, 0, D3D11_MAP_WRITE_DISCARD, 0, &ms);
        float* cb = (float*)ms.pData;
        memcpy(cb, viewProj, 64);
        if (g_exp & 1) for (int k = 0; k < 4; k++) cb[8 + k] = viewProj[12 + k] - viewProj[8 + k];
        memcpy(cb + 16, fd->fog, 16);
        cb[20] = fd->fogEnd; cb[21] = (float)fd->fogMode; cb[22] = fd->fogDensity; cb[23] = (g_exp & 128) ? 1.0f : 0.0f;
        g_ctx->Unmap(g_cb, 0);

        float planes[6][4]; extractPlanes(viewProj, planes);
        g_opaque.clear(); g_trans.clear();
        const float half = (2 * fd->renderDistSections + 1) * 8.0f;
        const float fx = floorf(fd->camFrac[0]), fz = floorf(fd->camFrac[2]);
        int rd = fd->renderDistSections + 1;
        for (auto& kv : g_sections) {
            const Section& s = kv.second;
            int dx = s.x - fd->camSec[0], dz = s.z - fd->camSec[2];
            if (dx < -rd || dx > rd || dz < -rd || dz > rd) continue;
            float ox = dx * 16.0f - fd->camFrac[0], oy = (s.y - fd->camSec[1]) * 16.0f - fd->camFrac[1], oz = dz * 16.0f - fd->camFrac[2];
            float wx = dx * 16.0f - fx + 8.0f, wz = dz * 16.0f - fz + 8.0f;
            if (wx < -half || wx >= half || wz < -half || wz >= half) continue;
            if (!boxVisible(planes, ox, oy, oz)) continue;
            float cx = ox + 8, cy = oy + 8, cz = oz + 8;
            DrawItem di = { &s, ox, oy, oz, cx * cx + cy * cy + cz * cz };
            if (s.pass[0].count) g_opaque.push_back(di);
            if (s.pass[1].count) g_trans.push_back(di);
            g_stats.visible++;
        }
        std::sort(g_opaque.begin(), g_opaque.end(), [](const DrawItem& a, const DrawItem& b) { return a.dist2 < b.dist2; });
        std::sort(g_trans.begin(), g_trans.end(), [](const DrawItem& a, const DrawItem& b) { return a.dist2 > b.dist2; });
        sortTranslucent();
        UINT n = (UINT)(g_opaque.size() + g_trans.size());
        ensureInstCap(n + 1);
        g_ctx->Map(g_instVB, 0, D3D11_MAP_WRITE_DISCARD, 0, &ms);
        float* inst = (float*)ms.pData;
        for (auto& d : g_opaque) { inst[0] = d.ox; inst[1] = d.oy; inst[2] = d.oz; inst[3] = 0; inst += 4; }
        for (auto& d : g_trans) { inst[0] = d.ox; inst[1] = d.oy; inst[2] = d.oz; inst[3] = 0; inst += 4; }
        g_ctx->Unmap(g_instVB, 0);

        g_worldAtlasSrv = atlasSrv;
        bindWorldPipeline();

        for (int rep = 0; rep < g_worldRepeat; rep++) {
            if (rep) g_ctx->ClearDepthStencilView(g_dsv, D3D11_CLEAR_DEPTH, (g_exp & 1) ? 1.0f : 0.0f, 0);
            g_ctx->RSSetState(g_rsWorld);
            g_ctx->OMSetDepthStencilState(g_dsWrite, 0);
            g_ctx->OMSetBlendState(g_worldMask == 15 ? g_bsOff : blendState(false, 1, 0, 1, 0, g_worldMask), nullptr, 0xffffffff);
            g_ctx->PSSetShader(g_psOpaque, nullptr, 0);
            drawList(g_opaque, 0, 0);
            if (g_worldRepeat == 1) mark(2);

            if (splitTranslucent && g_worldRepeat == 1) g_transPending = true;
            else drawTranslucentPass();
        }
        mark(3);
    }
}

static void finishFrame(const FrameDesc* fd, int qi, LARGE_INTEGER t0) {
    LARGE_INTEGER t1, t2;
    if (g_off) {
        ID3D11Texture2D* bb = nullptr;
        g_swap->GetBuffer(0, __uuidof(ID3D11Texture2D), (void**)&bb);
        g_ctx->CopyResource(bb, g_off);
        bb->Release();
    }
    mark(4);
    g_ctx->End(g_qDisjoint[qi]);
    g_curQ = -1;
    QueryPerformanceCounter(&t1);
    bool vsync = (fd->flags & 2) != 0;
    g_swap->Present(vsync ? 1 : 0, (!vsync && g_tearing) ? DXGI_PRESENT_ALLOW_TEARING : 0);
    QueryPerformanceCounter(&t2);
#ifndef PD3D_GLFW
    if (g_glObj) g_glLocked = pLock(g_glDev, 1, &g_glObj) != 0;
#endif

    g_stats.cpuMs = (float)((t1.QuadPart - t0.QuadPart) * 1000.0 / g_freq.QuadPart);
    g_stats.presentMs = (float)((t2.QuadPart - t1.QuadPart) * 1000.0 / g_freq.QuadPart);
    g_pDraws[qi] = g_stats.draws; g_pCpu[qi] = g_stats.cpuMs; g_pPresent[qi] = g_stats.presentMs;

    g_qFrame++;
    if (g_qFrame >= PQ) {
        int r = g_qFrame % PQ;
        D3D11_QUERY_DATA_TIMESTAMP_DISJOINT dj; UINT64 ts[PT];
        bool ok = g_ctx->GetData(g_qDisjoint[r], &dj, sizeof(dj), D3D11_ASYNC_GETDATA_DONOTFLUSH) == S_OK && !dj.Disjoint;
        for (int k = 0; ok && k < PT; k++) ok = g_ctx->GetData(g_pTs[r][k], &ts[k], sizeof(UINT64), D3D11_ASYNC_GETDATA_DONOTFLUSH) == S_OK;
        if (ok) {
            double toMs = 1000.0 / (double)dj.Frequency;
            g_gpuMs = (double)(ts[4] - ts[0]) * toMs;
            ProfRec pr; pr.frame = g_qFrame - PQ; pr.draws = g_pDraws[r];
            for (int k = 0; k < 4; k++) pr.seg[k] = (float)((double)(ts[k + 1] - ts[k]) * toMs);
            pr.total = (float)g_gpuMs; pr.cpu = g_pCpu[r]; pr.present = g_pPresent[r];
            if (g_prof.size() < 200000) g_prof.push_back(pr);
        }
    }
    g_stats.gpuMs = (float)g_gpuMs;
    g_stats.total = (int)g_sections.size();
    g_stats.memBytes = usedBytes();
    g_stats.uploadBytes = g_uploadAccum; g_uploadAccum = 0;
}

extern "C" {
static bool sysProp(JNIEnv* env, const char* name, char* out, size_t cap) {
    jclass sys = env->FindClass("java/lang/System");
    jmethodID gp = env->GetStaticMethodID(sys, "getProperty", "(Ljava/lang/String;)Ljava/lang/String;");
    jstring v = (jstring)env->CallStaticObjectMethod(sys, gp, env->NewStringUTF(name));
    if (!v) return false;
    const char* c = env->GetStringUTFChars(v, nullptr);
    snprintf(out, cap, "%s", c);
    env->ReleaseStringUTFChars(v, c);
    return true;
}

JNIEXPORT jint JNICALL Java_com_nikita23830_minecraftd3d_D3DNative_init(JNIEnv* env, jclass, jlong hwnd, jint flags) {
    g_flags = flags;
    char buf[64];
    if (sysProp(env, "minecraftd3d.exp", buf, sizeof(buf))) g_exp = atoi(buf);
    if (sysProp(env, "minecraftd3d.worldRepeat", buf, sizeof(buf))) g_worldRepeat = std::max(1, atoi(buf));
    sysProp(env, "minecraftd3d.bench", g_label, sizeof(g_label));
    logf("flags=%d", flags);
    return initDevice((HWND)(intptr_t)hwnd);
}

JNIEXPORT jstring JNICALL Java_com_nikita23830_minecraftd3d_D3DNative_adapterInfo(JNIEnv* env, jclass) {
    return env->NewStringUTF(g_adapter);
}

static void writeProfile() {
    if (g_prof.empty()) return;
    char name[128]; snprintf(name, sizeof(name), "logs/minecraftd3d-gpuprof-%s.csv", g_label);
    FILE* f = fopen(name, "w");
    if (!f) return;
    fprintf(f, "frame,draws,pre,opaque,trans,post,total,cpu,present\n");
    for (auto& r : g_prof)
        fprintf(f, "%d,%d,%.4f,%.4f,%.4f,%.4f,%.4f,%.4f,%.4f\n", r.frame, r.draws, r.seg[0], r.seg[1], r.seg[2], r.seg[3], r.total, r.cpu, r.present);
    fclose(f);
    g_prof.clear();
}

JNIEXPORT void JNICALL Java_com_nikita23830_minecraftd3d_D3DNative_atlasCreate(JNIEnv*, jclass, jint w, jint h, jint mips) {
    SAFE_RELEASE(g_atlasSrv); SAFE_RELEASE(g_atlas);
    D3D11_TEXTURE2D_DESC td = {}; td.Width = w; td.Height = h; td.MipLevels = mips; td.ArraySize = 1; td.Format = DXGI_FORMAT_B8G8R8A8_UNORM;
    td.SampleDesc.Count = 1; td.Usage = D3D11_USAGE_DEFAULT; td.BindFlags = D3D11_BIND_SHADER_RESOURCE;
    if (FAILED(g_dev->CreateTexture2D(&td, nullptr, &g_atlas))) { logf("atlas create failed"); return; }
    g_dev->CreateShaderResourceView(g_atlas, nullptr, &g_atlasSrv);
    g_atlasW = w; g_atlasH = h; g_atlasMips = mips;
    logf("atlas %dx%d mips=%d", w, h, mips);
}

JNIEXPORT void JNICALL Java_com_nikita23830_minecraftd3d_D3DNative_atlasUpload(JNIEnv* env, jclass, jint level, jint x, jint y, jint w, jint h, jobject buf) {
    if (!g_atlas || level >= g_atlasMips) return;
    void* p = env->GetDirectBufferAddress(buf);
    D3D11_BOX box = { (UINT)x, (UINT)y, 0, (UINT)(x + w), (UINT)(y + h), 1 };
    g_ctx->UpdateSubresource(g_atlas, level, &box, p, w * 4, 0);
}

JNIEXPORT void JNICALL Java_com_nikita23830_minecraftd3d_D3DNative_sectionSet(JNIEnv* env, jclass, jint x, jint y, jint z, jobject buf, jint opaqueVerts, jint transVerts) {
    int64_t k = key(x, y, z);
    auto it = g_sections.find(k);
    if (opaqueVerts == 0 && transVerts == 0) {
        if (it != g_sections.end()) { freeVerts(it->second.pass[0]); freeVerts(it->second.pass[1]); g_sections.erase(it); }
        return;
    }
    if (it == g_sections.end()) { Section s; s.x = x; s.y = y; s.z = z; it = g_sections.emplace(k, s).first; }
    Section& s = it->second;
    const uint8_t* src = (const uint8_t*)env->GetDirectBufferAddress(buf);
    int counts[2] = { opaqueVerts, transVerts };
    for (int pass = 0; pass < 2; pass++) {
        freeVerts(s.pass[pass]);
        if (pass == 1) { s.trans.assign(src, src + (size_t)counts[1] * sizeof(Vertex)); s.sorted = false; }
        if (counts[pass] > 0) {
            s.pass[pass] = allocVerts((UINT)counts[pass]);
            Alloc& a = s.pass[pass];
            if (a.count) {
                D3D11_BOX box = { a.offset * (UINT)sizeof(Vertex), 0, 0, (a.offset + a.count) * (UINT)sizeof(Vertex), 1, 1 };
                g_ctx->UpdateSubresource(g_pages[a.page].vb, 0, &box, src, 0, 0);
                g_uploadAccum += (int64_t)a.count * sizeof(Vertex);
            }
        }
        src += (size_t)counts[pass] * sizeof(Vertex);
    }
}

JNIEXPORT void JNICALL Java_com_nikita23830_minecraftd3d_D3DNative_clearSections(JNIEnv*, jclass) {
    for (auto& kv : g_sections) { freeVerts(kv.second.pass[0]); freeVerts(kv.second.pass[1]); }
    g_sections.clear();
}

JNIEXPORT void JNICALL Java_com_nikita23830_minecraftd3d_D3DNative_frame(JNIEnv* env, jclass, jobject desc) {
    if (!g_dev) return;
    renderFrame((const FrameDesc*)env->GetDirectBufferAddress(desc), nullptr, 0, nullptr, 0);
}

JNIEXPORT void JNICALL Java_com_nikita23830_minecraftd3d_D3DNative_frameEngine(JNIEnv* env, jclass, jobject desc, jobject cmds, jint ncmd, jobject verts, jint nverts) {
    if (!g_dev) return;
    renderFrame((const FrameDesc*)env->GetDirectBufferAddress(desc), (const ImmCmd*)env->GetDirectBufferAddress(cmds), ncmd,
                (const ImmVertex*)env->GetDirectBufferAddress(verts), nverts);
}

static void retarget(int tex, int depthKey) {
    if (g_tgtFbo && ((tex && g_tgtTex == tex) || (depthKey && g_tgtDepth == depthKey))) bindTarget(g_tgtTex, g_tgtDepth, (int)g_tgtW, (int)g_tgtH);
}

JNIEXPORT void JNICALL Java_com_nikita23830_minecraftd3d_D3DNative_flush(JNIEnv* env, jclass, jobject desc, jobject cmds, jint ncmd, jobject verts, jint nverts) {
    if (!g_dev) return;
    const FrameDesc* fd = (const FrameDesc*)env->GetDirectBufferAddress(desc);
    beginFrame(fd);
    runSegment(fd, (const ImmCmd*)env->GetDirectBufferAddress(cmds), ncmd, (const ImmVertex*)env->GetDirectBufferAddress(verts), nverts);
}

JNIEXPORT jint JNICALL Java_com_nikita23830_minecraftd3d_D3DNative_fbRead(JNIEnv* env, jclass, jint tex, jint depthKey, jint kind, jint x, jint y, jint w, jint h, jobject out) {
    if (!g_dev) return -1;
    if (env->GetDirectBufferCapacity(out) < (jlong)w * h * 4) return -4;
    return fbRead(tex, depthKey, kind, x, y, w, h, (uint8_t*)env->GetDirectBufferAddress(out));
}

JNIEXPORT jint JNICALL Java_com_nikita23830_minecraftd3d_D3DNative_texRead(JNIEnv* env, jclass, jint id, jint level, jobject out) {
    if (!g_dev) return -1;
    return texRead(id, level, (uint8_t*)env->GetDirectBufferAddress(out), (size_t)env->GetDirectBufferCapacity(out));
}

JNIEXPORT void JNICALL Java_com_nikita23830_minecraftd3d_D3DNative_fbDepthDelete(JNIEnv*, jclass, jint key) { if (g_dev) { fbDelete(key); retarget(0, key); } }

JNIEXPORT void JNICALL Java_com_nikita23830_minecraftd3d_D3DNative_texCreate(JNIEnv*, jclass, jint id, jint w, jint h, jint mips) { if (g_dev) { texCreate(id, w, h, mips); retarget(id, 0); } }

JNIEXPORT void JNICALL Java_com_nikita23830_minecraftd3d_D3DNative_texUpload(JNIEnv* env, jclass, jint id, jint level, jint x, jint y, jint w, jint h, jobject buf) {
    auto it = g_tex.find(id);
    if (it == g_tex.end() || !it->second.t || level >= it->second.mips || w <= 0 || h <= 0) return;
    int lw = std::max(1, it->second.w >> level), lh = std::max(1, it->second.h >> level);
    if (x < 0 || y < 0 || x >= lw || y >= lh) return;
    void* src = env->GetDirectBufferAddress(buf);
    jlong cap = env->GetDirectBufferCapacity(buf);
    if (!src) return;
    int cw = std::min(w, lw - x), ch = std::min(h, lh - y);
    if (cap >= 0 && (jlong)w * 4 * (ch - 1) + (jlong)cw * 4 > cap * ((jlong)4)) { logf("texUpload %d: zu wenig Daten", id); return; }
    D3D11_BOX box = { (UINT)x, (UINT)y, 0, (UINT)(x + cw), (UINT)(y + ch), 1 };
    g_ctx->UpdateSubresource(it->second.t, level, &box, src, w * 4, 0);
}

JNIEXPORT void JNICALL Java_com_nikita23830_minecraftd3d_D3DNative_texParams(JNIEnv*, jclass, jint id, jint params) {
    auto it = g_tex.find(id);
    if (it != g_tex.end()) it->second.params = params;
}

JNIEXPORT void JNICALL Java_com_nikita23830_minecraftd3d_D3DNative_texDelete(JNIEnv*, jclass, jint id) {
    auto it = g_tex.find(id);
    if (it != g_tex.end()) { texRelease(it->second); g_tex.erase(it); retarget(id, 0); }
}

JNIEXPORT void JNICALL Java_com_nikita23830_minecraftd3d_D3DNative_stats(JNIEnv* env, jclass, jobject out) {
    memcpy(env->GetDirectBufferAddress(out), &g_stats, sizeof(g_stats));
}

#ifndef PD3D_GLFW
JNIEXPORT jint JNICALL Java_com_nikita23830_minecraftd3d_D3DNative_initGui(JNIEnv*, jclass, jint w, jint h) { return g_dev ? initGui(w, h) : -1; }

JNIEXPORT void JNICALL Java_com_nikita23830_minecraftd3d_D3DNative_glRelease(JNIEnv*, jclass) { wglMakeCurrent(nullptr, nullptr); logf("GL context released"); }
#endif

JNIEXPORT void JNICALL Java_com_nikita23830_minecraftd3d_D3DNative_shutdown(JNIEnv*, jclass) {
    writeProfile();
#ifndef PD3D_GLFW
    releaseGui();
    if (g_glDev && pCloseDev) { pCloseDev(g_glDev); g_glDev = nullptr; }
#endif
    for (auto& p : g_pages) SAFE_RELEASE(p.vb);
    g_pages.clear(); g_sections.clear();
    if (g_ctx) g_ctx->ClearState();
    SAFE_RELEASE(g_swap); SAFE_RELEASE(g_ctx); SAFE_RELEASE(g_dev);
    if (g_child && g_child != g_parent) DestroyWindow(g_child);
    g_child = nullptr;
    logf("shutdown");
}
}
