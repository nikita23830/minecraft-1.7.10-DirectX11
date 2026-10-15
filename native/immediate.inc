struct Tex { ID3D11Texture2D* t = nullptr; ID3D11ShaderResourceView* srv = nullptr; ID3D11RenderTargetView* rtv = nullptr; int w = 0, h = 0, mips = 1; int params = 0; bool rt = false; };
static std::unordered_map<int, Tex> g_tex;
static ID3D11SamplerState* g_samp[8];

enum { TP_MIPMAP = 1, TP_CLAMP = 2, TP_BLUR = 4 };

static void texRelease(Tex& t) { SAFE_RELEASE(t.rtv); SAFE_RELEASE(t.srv); SAFE_RELEASE(t.t); }

static void texCreate(int id, int w, int h, int mips) {
    Tex& t = g_tex[id];
    texRelease(t);
    D3D11_TEXTURE2D_DESC td = {};
    int maxMips = 1; for (int s = std::max(1, std::max(w, h)); s > 1; s >>= 1) maxMips++;
    td.Width = std::max(1, w); td.Height = std::max(1, h); td.MipLevels = std::min(std::max(1, mips), maxMips); td.ArraySize = 1;
    td.Format = DXGI_FORMAT_B8G8R8A8_UNORM; td.SampleDesc.Count = 1; td.Usage = D3D11_USAGE_DEFAULT;
    td.BindFlags = D3D11_BIND_SHADER_RESOURCE | (t.rt ? D3D11_BIND_RENDER_TARGET : 0);
    if (td.Width > 16384 || td.Height > 16384) { logf("texCreate %d %dx%d: größer als 16384 – übersprungen", id, w, h); return; }
    HRESULT hr = g_dev->CreateTexture2D(&td, nullptr, &t.t);
    if (FAILED(hr)) { logf("texCreate %d %dx%d mips=%d failed hr=%08lx removed=%08lx", id, w, h, (int)td.MipLevels, hr, g_dev->GetDeviceRemovedReason()); return; }
    g_dev->CreateShaderResourceView(t.t, nullptr, &t.srv);
    t.w = w; t.h = h; t.mips = td.MipLevels;
}

static void createSamplers() {
    for (int i = 0; i < 8; i++) {
        bool blur = i & TP_BLUR, clamp = i & TP_CLAMP, mip = i & TP_MIPMAP;
        D3D11_SAMPLER_DESC sd = {};
        if (mip) sd.Filter = blur ? D3D11_FILTER_MIN_MAG_MIP_LINEAR : D3D11_FILTER_MIN_MAG_POINT_MIP_LINEAR;
        else sd.Filter = blur ? D3D11_FILTER_MIN_MAG_LINEAR_MIP_POINT : D3D11_FILTER_MIN_MAG_MIP_POINT;
        sd.AddressU = sd.AddressV = sd.AddressW = clamp ? D3D11_TEXTURE_ADDRESS_CLAMP : D3D11_TEXTURE_ADDRESS_WRAP;
        sd.MaxLOD = mip ? D3D11_FLOAT32_MAX : 0.0f;
        g_dev->CreateSamplerState(&sd, &g_samp[i]);
    }
}

#pragma pack(push, 1)
struct ImmVertex { float x, y, z, u, v; uint32_t color; uint32_t normal; };
struct ImmCmd {
    int32_t type;
    int32_t topology;
    int32_t first, count, tex, flags;
    int32_t blendSrc, blendDst, blendSrcA, blendDstA;
    float alphaRef;
    int32_t depthFunc, cull, colorMask;
    float proj[16];
    float light0[3], light1[3];
    float clearColor[4];
    float fogColor[3], fogStart, fogEnd, fogDensity; int32_t fogMode, pad2;
};
struct ImmInst { float proj[16]; float l0[4]; float l1[4]; uint32_t flags, alphaRef, pad0, pad1; float fogA[4]; float fogB[4]; };
#pragma pack(pop)
static_assert(sizeof(ImmVertex) == 28, "imm vertex");
static_assert(sizeof(ImmCmd) == 192, "imm cmd");
enum { IMM_DRAW = 1, IMM_CLEAR_DEPTH = 2, IMM_CLEAR_COLOR = 3, IMM_WORLD = 4, IMM_API_MESH = 5, IMM_API_NATIVE = 6,
       IMM_COPY_TEX = 7, IMM_TARGET = 8, IMM_BLIT = 9,
       IMM_WORLD_TRANS = 10 };

static ID3D11RenderTargetView* g_tgtRtv; static ID3D11DepthStencilView* g_tgtDsv; static ID3D11Texture2D* g_tgtRes;
static UINT g_tgtW = 1, g_tgtH = 1; static bool g_tgtFbo;
static void applyTarget();
static void bindTarget(int tex, int depthKey, int w, int h);
static void clearRect(const ImmCmd& c, bool color, float depth);
static void blitCmd(const ImmCmd& c);

enum { IF_BLEND = 1, IF_ALPHA = 2, IF_DEPTH = 4, IF_DEPTHWRITE = 8, IF_LIGHT = 16, IF_TEX = 32,
       IF_LIGHTMAP = 64, IF_FOG = 128, IF_RAWPROJ = 256,
       IF_REVZ = 512, IF_SCISSOR = 1024,
       IF_NHALF = 2048,
       IF_POLYOFF = 4096,
       IF_GLMESH = 8192 };

static ID3D11Buffer* g_immCb;
static float g_immFog[8];
static void immSetFog(const float* fogRgbStart, float fogEnd) {
    memcpy(g_immFog, fogRgbStart, 16); g_immFog[4] = fogEnd; g_immFog[5] = g_immFog[6] = g_immFog[7] = 0;
}

static ID3D11VertexShader* g_vsImm; static ID3D11PixelShader* g_psImm; static ID3D11InputLayout* g_layoutImm;
static ID3D11Buffer* g_immVB; static UINT g_immVBCap; static ID3D11Buffer* g_immInst; static UINT g_immInstCap;
static std::unordered_map<uint64_t, ID3D11BlendState*> g_blendCache;
static std::unordered_map<uint32_t, ID3D11DepthStencilState*> g_depthCache;
static ID3D11RasterizerState* g_rsImm[3];
static ID3D11RasterizerState* g_rsImmSc[3];
static std::map<uint64_t, ID3D11RasterizerState*> g_rsBias;

static ID3D11RasterizerState* biasState(ID3D11RasterizerState* base, int cullIdx, bool sc, bool revz, int32_t packed) {
    uint64_t key = (uint64_t)(uint32_t)cullIdx | (sc ? 4u : 0u) | (revz ? 8u : 0u) | ((uint64_t)(uint32_t)packed << 32);
    auto it = g_rsBias.find(key);
    if (it != g_rsBias.end()) return it->second;
    D3D11_RASTERIZER_DESC rd; base->GetDesc(&rd);
    float factor = (int16_t)(packed & 0xFFFF) / 16.0f, units = (int16_t)((uint32_t)packed >> 16) / 16.0f;
    float sgn = revz ? -1.0f : 1.0f;
    rd.DepthBias = (INT)lroundf(sgn * units);
    rd.SlopeScaledDepthBias = sgn * factor;
    rd.DepthBiasClamp = 0;
    ID3D11RasterizerState* st = nullptr;
    if (FAILED(g_dev->CreateRasterizerState(&rd, &st))) st = base;
    g_rsBias[key] = st;
    return st;
}
static std::vector<ImmInst> g_immInstData;
static int g_immDraws;

static const char* kImmHlsl = R"(
Texture2D tex : register(t0);
Texture2D lightmap : register(t1);
SamplerState samp : register(s0);
SamplerState sLinear : register(s1);
cbuffer Fog : register(b0) { float4 fogColor; float4 fogEnd; };
struct VSIn {
    float3 pos : POSITION; float2 uv : TEXCOORD0; float4 col : COLOR; float4 nrm : NORMAL;
    float4 p0 : PROJ0; float4 p1 : PROJ1; float4 p2 : PROJ2; float4 p3 : PROJ3;
    float4 l0 : LIGHTA; float4 l1 : LIGHTB; uint4 prm : PARAMS; float4 fogA : FOGA; float4 fogB : FOGB;
};
struct PSIn {
    float4 pos : SV_Position; float2 uv : TEXCOORD0; float4 col : COLOR; float3 nrm : NORMAL;
    float3 l0 : LIGHTA; float3 l1 : LIGHTB; nointerpolation uint3 prm : PARAMS; float dist : TEXCOORD1;
    nointerpolation float4 fogA : FOGA; nointerpolation float4 fogB : FOGB;
};
PSIn vs(VSIn i) {
    PSIn o;
    float4 p = float4(i.pos, 1);
    o.pos = float4(dot(i.p0, p), dot(i.p1, p), dot(i.p2, p), dot(i.p3, p));
    float4 tint = i.prm.w == 0 ? float4(1, 1, 1, 1)
        : float4((i.prm.w >> 16) & 255, (i.prm.w >> 8) & 255, i.prm.w & 255, i.prm.w >> 24) / 255.0;
    o.uv = i.uv; o.col = i.col * tint; o.nrm = i.nrm.xyz; o.l0 = i.l0.xyz; o.l1 = i.l1.xyz; o.prm = i.prm.xyz;
    o.dist = o.pos.w;
    o.fogA = i.fogA; o.fogB = i.fogB;
    return o;
}
float4 ps(PSIn i) : SV_Target {
    uint flags = i.prm.x;
    float4 c = i.col;
    if (flags & 32) c *= tex.Sample(samp, i.uv);
    if (flags & 16) {
        float3 n = (flags & 2048) ? i.nrm * 2 : normalize(i.nrm);
        float d = 0.4 + 0.6 * (saturate(dot(n, i.l0)) + saturate(dot(n, i.l1)));
        c.rgb *= saturate(d);
    }
    if (flags & 64) c.rgb *= lightmap.Sample(sLinear, (float2(i.prm.z & 0xFFFF, i.prm.z >> 16) + 8.0) / 256.0).rgb;
    if (flags & 128) {
        float f;
        if (i.fogB.z < 0.5) f = saturate((i.dist - i.fogA.w) / max(i.fogB.x - i.fogA.w, 0.001));
        else if (i.fogB.z < 1.5) f = 1 - saturate(exp(-i.fogB.y * i.dist));
        else f = 1 - saturate(exp(-(i.fogB.y * i.dist) * (i.fogB.y * i.dist)));
        c.rgb = lerp(c.rgb, i.fogA.rgb, f);
    }
    if (flags & 2) { if (!(c.a > asfloat(i.prm.y))) discard; }
    return c;
}
)";

static D3D11_BLEND glBlend(int f, bool alpha) {
    switch (f) {
        case 0: return D3D11_BLEND_ZERO;
        case 1: return D3D11_BLEND_ONE;
        case 0x300: return alpha ? D3D11_BLEND_SRC_ALPHA : D3D11_BLEND_SRC_COLOR;
        case 0x301: return alpha ? D3D11_BLEND_INV_SRC_ALPHA : D3D11_BLEND_INV_SRC_COLOR;
        case 0x302: return D3D11_BLEND_SRC_ALPHA;
        case 0x303: return D3D11_BLEND_INV_SRC_ALPHA;
        case 0x304: return D3D11_BLEND_DEST_ALPHA;
        case 0x305: return D3D11_BLEND_INV_DEST_ALPHA;
        case 0x306: return alpha ? D3D11_BLEND_DEST_ALPHA : D3D11_BLEND_DEST_COLOR;
        case 0x307: return alpha ? D3D11_BLEND_INV_DEST_ALPHA : D3D11_BLEND_INV_DEST_COLOR;
        case 0x308: return D3D11_BLEND_SRC_ALPHA_SAT;
        default: return D3D11_BLEND_ONE;
    }
}

static ID3D11BlendState* blendState(bool enable, int s, int d, int sa, int da, int mask) {
    uint64_t k = enable ? ((uint64_t)(s & 0xFFF) | ((uint64_t)(d & 0xFFF) << 12) | ((uint64_t)(sa & 0xFFF) << 24) | ((uint64_t)(da & 0xFFF) << 36)) : 0;
    k |= (uint64_t)(mask & 15) << 48 | (uint64_t)enable << 52;
    auto it = g_blendCache.find(k);
    if (it != g_blendCache.end()) return it->second;
    D3D11_BLEND_DESC bd = {};
    auto& rt = bd.RenderTarget[0];
    rt.BlendEnable = enable;
    rt.SrcBlend = glBlend(s, false); rt.DestBlend = glBlend(d, false); rt.BlendOp = D3D11_BLEND_OP_ADD;
    rt.SrcBlendAlpha = glBlend(sa, true); rt.DestBlendAlpha = glBlend(da, true); rt.BlendOpAlpha = D3D11_BLEND_OP_ADD;
    rt.RenderTargetWriteMask = (UINT8)mask;
    ID3D11BlendState* st = nullptr;
    g_dev->CreateBlendState(&bd, &st);
    g_blendCache[k] = st;
    return st;
}

static ID3D11DepthStencilState* depthState(bool test, bool write, int func) {
    uint32_t k = (test ? 1u : 0u) | (write ? 2u : 0u) | ((uint32_t)(func & 0xF) << 4);
    auto it = g_depthCache.find(k);
    if (it != g_depthCache.end()) return it->second;
    D3D11_DEPTH_STENCIL_DESC dd = {};
    dd.DepthEnable = test || write;
    dd.DepthWriteMask = write ? D3D11_DEPTH_WRITE_MASK_ALL : D3D11_DEPTH_WRITE_MASK_ZERO;
    dd.DepthFunc = test ? (D3D11_COMPARISON_FUNC)(1 + (func & 7)) : D3D11_COMPARISON_ALWAYS;
    if (!test) dd.DepthWriteMask = D3D11_DEPTH_WRITE_MASK_ZERO;
    ID3D11DepthStencilState* st = nullptr;
    g_dev->CreateDepthStencilState(&dd, &st);
    g_depthCache[k] = st;
    return st;
}

static int initImmediate() {
    ID3DBlob* b;
    if (!(b = compile(kImmHlsl, "vs", "vs_5_0"))) return -1;
    g_dev->CreateVertexShader(b->GetBufferPointer(), b->GetBufferSize(), nullptr, &g_vsImm);
    D3D11_INPUT_ELEMENT_DESC il[] = {
        { "POSITION", 0, DXGI_FORMAT_R32G32B32_FLOAT, 0, 0, D3D11_INPUT_PER_VERTEX_DATA, 0 },
        { "TEXCOORD", 0, DXGI_FORMAT_R32G32_FLOAT, 0, 12, D3D11_INPUT_PER_VERTEX_DATA, 0 },
        { "COLOR", 0, DXGI_FORMAT_R8G8B8A8_UNORM, 0, 20, D3D11_INPUT_PER_VERTEX_DATA, 0 },
        { "NORMAL", 0, DXGI_FORMAT_R8G8B8A8_SNORM, 0, 24, D3D11_INPUT_PER_VERTEX_DATA, 0 },
        { "PROJ", 0, DXGI_FORMAT_R32G32B32A32_FLOAT, 1, 0, D3D11_INPUT_PER_INSTANCE_DATA, 1 },
        { "PROJ", 1, DXGI_FORMAT_R32G32B32A32_FLOAT, 1, 16, D3D11_INPUT_PER_INSTANCE_DATA, 1 },
        { "PROJ", 2, DXGI_FORMAT_R32G32B32A32_FLOAT, 1, 32, D3D11_INPUT_PER_INSTANCE_DATA, 1 },
        { "PROJ", 3, DXGI_FORMAT_R32G32B32A32_FLOAT, 1, 48, D3D11_INPUT_PER_INSTANCE_DATA, 1 },
        { "LIGHTA", 0, DXGI_FORMAT_R32G32B32A32_FLOAT, 1, 64, D3D11_INPUT_PER_INSTANCE_DATA, 1 },
        { "LIGHTB", 0, DXGI_FORMAT_R32G32B32A32_FLOAT, 1, 80, D3D11_INPUT_PER_INSTANCE_DATA, 1 },
        { "PARAMS", 0, DXGI_FORMAT_R32G32B32A32_UINT, 1, 96, D3D11_INPUT_PER_INSTANCE_DATA, 1 },
        { "FOGA", 0, DXGI_FORMAT_R32G32B32A32_FLOAT, 1, 112, D3D11_INPUT_PER_INSTANCE_DATA, 1 },
        { "FOGB", 0, DXGI_FORMAT_R32G32B32A32_FLOAT, 1, 128, D3D11_INPUT_PER_INSTANCE_DATA, 1 },
    };
    g_dev->CreateInputLayout(il, 13, b->GetBufferPointer(), b->GetBufferSize(), &g_layoutImm);
    b->Release();
    if (!(b = compile(kImmHlsl, "ps", "ps_5_0"))) return -1;
    g_dev->CreatePixelShader(b->GetBufferPointer(), b->GetBufferSize(), nullptr, &g_psImm);
    b->Release();
    D3D11_RASTERIZER_DESC rd = {}; rd.FillMode = D3D11_FILL_SOLID; rd.FrontCounterClockwise = TRUE; rd.DepthClipEnable = TRUE;
    rd.CullMode = D3D11_CULL_NONE; g_dev->CreateRasterizerState(&rd, &g_rsImm[0]);
    rd.CullMode = D3D11_CULL_BACK; g_dev->CreateRasterizerState(&rd, &g_rsImm[1]);
    rd.CullMode = D3D11_CULL_FRONT; g_dev->CreateRasterizerState(&rd, &g_rsImm[2]);
    rd.ScissorEnable = TRUE;
    rd.CullMode = D3D11_CULL_NONE; g_dev->CreateRasterizerState(&rd, &g_rsImmSc[0]);
    rd.CullMode = D3D11_CULL_BACK; g_dev->CreateRasterizerState(&rd, &g_rsImmSc[1]);
    rd.CullMode = D3D11_CULL_FRONT; g_dev->CreateRasterizerState(&rd, &g_rsImmSc[2]);
    createSamplers();
    D3D11_BUFFER_DESC cbd = {}; cbd.ByteWidth = 32; cbd.Usage = D3D11_USAGE_DYNAMIC; cbd.BindFlags = D3D11_BIND_CONSTANT_BUFFER; cbd.CPUAccessFlags = D3D11_CPU_ACCESS_WRITE;
    g_dev->CreateBuffer(&cbd, nullptr, &g_immCb);
    return 0;
}

static void ensureDynamic(ID3D11Buffer*& buf, UINT& cap, UINT bytes) {
    if (bytes <= cap) return;
    SAFE_RELEASE(buf);
    cap = std::max(bytes, cap * 2);
    D3D11_BUFFER_DESC bd = {}; bd.ByteWidth = cap; bd.Usage = D3D11_USAGE_DYNAMIC;
    bd.BindFlags = D3D11_BIND_VERTEX_BUFFER; bd.CPUAccessFlags = D3D11_CPU_ACCESS_WRITE;
    g_dev->CreateBuffer(&bd, nullptr, &buf);
}

static void prepareImmediate(const ImmCmd* cmds, int ncmd, const ImmVertex* verts, int nverts) {
    if (ncmd <= 0) return;
    if (nverts > 0) {
        ensureDynamic(g_immVB, g_immVBCap, (UINT)nverts * sizeof(ImmVertex));
        D3D11_MAPPED_SUBRESOURCE ms;
        g_ctx->Map(g_immVB, 0, D3D11_MAP_WRITE_DISCARD, 0, &ms);
        memcpy(ms.pData, verts, (size_t)nverts * sizeof(ImmVertex));
        g_ctx->Unmap(g_immVB, 0);
    }
    g_immInstData.clear();
    bool fbo = g_tgtFbo;
    for (int i = 0; i < ncmd; i++) {
        const ImmCmd& c = cmds[i];
        if (c.type == IMM_TARGET) { fbo = c.tex != 0; continue; }
        if (c.type != 1 && c.type != 5) continue;
        ImmInst in;
        bool raw = (c.flags & IF_RAWPROJ) != 0;
        for (int k = 0; k < 4; k++) {
            in.proj[k] = c.proj[k];
            in.proj[4 + k] = c.proj[4 + k];
            if (raw) in.proj[8 + k] = c.proj[8 + k];
            else if (c.flags & IF_REVZ) in.proj[8 + k] = 0.5f * c.proj[12 + k] - 0.5f * c.proj[8 + k];
            else in.proj[8 + k] = 0.5f * c.proj[8 + k] + 0.5f * c.proj[12 + k];
            in.proj[12 + k] = c.proj[12 + k];
            if (fbo) in.proj[4 + k] = -in.proj[4 + k];
        }
        in.l0[0] = c.light0[0]; in.l0[1] = c.light0[1]; in.l0[2] = c.light0[2]; in.l0[3] = 0;
        in.l1[0] = c.light1[0]; in.l1[1] = c.light1[1]; in.l1[2] = c.light1[2]; in.l1[3] = 0;
        in.flags = (uint32_t)c.flags; memcpy(&in.alphaRef, &c.alphaRef, 4);
        memcpy(&in.pad0, &c.clearColor[0], 4); memcpy(&in.pad1, &c.clearColor[1], 4);
        if ((c.type == 5 && !(c.flags & IF_GLMESH)) || (c.flags & IF_RAWPROJ)) {
            memcpy(in.fogA, g_immFog, 16); in.fogB[0] = g_immFog[4]; in.fogB[1] = 0; in.fogB[2] = 0; in.fogB[3] = 0;
        } else {
            in.fogA[0] = c.fogColor[0]; in.fogA[1] = c.fogColor[1]; in.fogA[2] = c.fogColor[2]; in.fogA[3] = c.fogStart;
            in.fogB[0] = c.fogEnd; in.fogB[1] = c.fogDensity; in.fogB[2] = (float)c.fogMode; in.fogB[3] = 0;
        }
        g_immInstData.push_back(in);
    }
    if (!g_immInstData.empty()) {
        ensureDynamic(g_immInst, g_immInstCap, (UINT)(g_immInstData.size() * sizeof(ImmInst)));
        D3D11_MAPPED_SUBRESOURCE ms;
        g_ctx->Map(g_immInst, 0, D3D11_MAP_WRITE_DISCARD, 0, &ms);
        memcpy(ms.pData, g_immInstData.data(), g_immInstData.size() * sizeof(ImmInst));
        g_ctx->Unmap(g_immInst, 0);
    }
}

static void immCopyTex(const ImmCmd& c) {
    ID3D11Texture2D* srcRes = g_tgtFbo ? g_tgtRes : g_off;
    if (!srcRes) return;
    auto it = g_tex.find(c.tex);
    if (it == g_tex.end() || !it->second.t || it->second.t == srcRes) return;
    int v[6]; memcpy(v, c.proj, sizeof(v));
    int xo = v[0], yo = v[1], x = v[2], y = v[3], w = v[4], h = v[5];
    int sw = (int)g_tgtW, sh = (int)g_tgtH;
    for (int r = 0; r < h; r++) {
        int src = g_tgtFbo ? y + r : sh - 1 - (y + r), dst = yo + r;
        if (src < 0 || src >= sh || dst < 0 || dst >= it->second.h) continue;
        D3D11_BOX box = { (UINT)std::max(0, x), (UINT)src, 0, (UINT)std::min(sw, x + w), (UINT)src + 1, 1 };
        if (box.right <= box.left) continue;
        g_ctx->CopySubresourceRegion(it->second.t, (UINT)c.first, (UINT)xo, (UINT)dst, 0, srcRes, 0, &box);
    }
}

static void execImmediate(const ImmCmd* cmds, int from, int to, UINT& inst) {
    if (from >= to) return;
    applyTarget();
    g_ctx->IASetInputLayout(g_layoutImm);
    UINT strides[2] = { sizeof(ImmVertex), sizeof(ImmInst) }, offs[2] = { 0, 0 };
    ID3D11Buffer* vbs[2] = { g_immVB, g_immInst };
    g_ctx->IASetVertexBuffers(0, 2, vbs, strides, offs);
    g_ctx->IASetIndexBuffer(g_ib, DXGI_FORMAT_R32_UINT, 0);
    g_ctx->VSSetShader(g_vsImm, nullptr, 0);
    g_ctx->PSSetShader(g_psImm, nullptr, 0);
    ID3D11BlendState* curB = nullptr; ID3D11DepthStencilState* curD = nullptr; ID3D11RasterizerState* curR = nullptr;
    ID3D11ShaderResourceView* curT = (ID3D11ShaderResourceView*)1; ID3D11SamplerState* curS = nullptr; int curTopo = -1;
    {
        D3D11_MAPPED_SUBRESOURCE ms;
        g_ctx->Map(g_immCb, 0, D3D11_MAP_WRITE_DISCARD, 0, &ms);
        memcpy(ms.pData, g_immFog, 32);
        g_ctx->Unmap(g_immCb, 0);
        g_ctx->PSSetConstantBuffers(0, 1, &g_immCb);
        g_ctx->PSSetShaderResources(1, 1, &g_lightSrv);
        g_ctx->PSSetSamplers(1, 1, &g_sampLinear);
    }
    bool meshBound = false;
    for (int i = from; i < to; i++) {
        const ImmCmd& c = cmds[i];
        if (c.type == 2 || c.type == 3) {
            bool color = c.type == 3;
            if (color ? !g_tgtRtv : !g_tgtDsv) continue;
            float depth = (c.flags & IF_REVZ) ? 0.0f : 1.0f;
            if (!(c.flags & IF_SCISSOR) && (!color || (c.colorMask & 15) == 15)) {
                if (color) g_ctx->ClearRenderTargetView(g_tgtRtv, c.clearColor);
                else g_ctx->ClearDepthStencilView(g_tgtDsv, D3D11_CLEAR_DEPTH, depth, 0);
                continue;
            }
            clearRect(c, color, depth);
        } else if (c.type == IMM_COPY_TEX) { immCopyTex(c); continue; }
        else if (c.type == IMM_TARGET) {
            ID3D11ShaderResourceView* nul = nullptr; g_ctx->PSSetShaderResources(0, 1, &nul);
            int v[2]; memcpy(v, &c.proj[0], sizeof(v));
            bindTarget(c.tex, c.first, v[0], v[1]);
            applyTarget();
            curT = (ID3D11ShaderResourceView*)1;
            continue;
        } else if (c.type == IMM_BLIT) blitCmd(c);
        if (c.type == 2 || c.type == 3 || c.type == IMM_BLIT) {
            applyTarget();
            g_ctx->IASetInputLayout(g_layoutImm);
            g_ctx->IASetVertexBuffers(0, 2, vbs, strides, offs);
            g_ctx->IASetIndexBuffer(g_ib, DXGI_FORMAT_R32_UINT, 0);
            g_ctx->VSSetShader(g_vsImm, nullptr, 0); g_ctx->PSSetShader(g_psImm, nullptr, 0);
            g_ctx->PSSetConstantBuffers(0, 1, &g_immCb);
            g_ctx->PSSetShaderResources(1, 1, &g_lightSrv); g_ctx->PSSetSamplers(1, 1, &g_sampLinear);
            curB = nullptr; curD = nullptr; curR = nullptr; curT = (ID3D11ShaderResourceView*)1; curS = nullptr; curTopo = -1;
            meshBound = false;
            continue;
        }
        if (c.type == 6) {
            typedef void (*ApiNativeFn)(void*, void*, void*);
            ApiNativeFn fn; void* user;
            memcpy(&fn, &c.proj[0], sizeof(fn)); memcpy(&user, &c.proj[2], sizeof(user));
            if (fn) fn(g_dev, g_ctx, user);
            applyTarget();
            g_ctx->IASetInputLayout(g_layoutImm);
            g_ctx->IASetIndexBuffer(g_ib, DXGI_FORMAT_R32_UINT, 0);
            g_ctx->VSSetShader(g_vsImm, nullptr, 0); g_ctx->PSSetShader(g_psImm, nullptr, 0);
            g_ctx->PSSetConstantBuffers(0, 1, &g_immCb);
            g_ctx->PSSetShaderResources(1, 1, &g_lightSrv); g_ctx->PSSetSamplers(1, 1, &g_sampLinear);
            curB = nullptr; curD = nullptr; curR = nullptr; curT = (ID3D11ShaderResourceView*)1; curS = nullptr; curTopo = -1;
            meshBound = true;
            continue;
        }
        if (c.type != 1 && c.type != 5) continue;
        const ApiMesh* mesh = nullptr;
        if (c.type == 5) {
            mesh = apiMeshFind(c.first);
            if (!mesh) { inst++; continue; }
            ID3D11Buffer* vbs2[2] = { mesh->vb, g_immInst };
            g_ctx->IASetVertexBuffers(0, 2, vbs2, strides, offs);
            meshBound = true;
        } else if (meshBound) {
            g_ctx->IASetVertexBuffers(0, 2, vbs, strides, offs);
            meshBound = false;
        }
        ID3D11BlendState* bs = blendState(c.flags & IF_BLEND, c.blendSrc, c.blendDst, c.blendSrcA, c.blendDstA, c.colorMask);
        if (bs != curB) { g_ctx->OMSetBlendState(bs, nullptr, 0xffffffff); curB = bs; }
        int dfunc = c.depthFunc;
        if (c.flags & IF_REVZ) {
            switch (dfunc) { case 0x201: dfunc = 0x204; break; case 0x204: dfunc = 0x201; break; case 0x203: dfunc = 0x206; break; case 0x206: dfunc = 0x203; break; }
        }
        ID3D11DepthStencilState* ds = depthState(c.flags & IF_DEPTH, c.flags & IF_DEPTHWRITE, dfunc);
        if (ds != curD) { g_ctx->OMSetDepthStencilState(ds, 0); curD = ds; }
        int cullIdx = c.cull < 0 || c.cull > 2 ? 0 : c.cull;
        if (g_tgtFbo && cullIdx) cullIdx = 3 - cullIdx;
        ID3D11RasterizerState* rs = (c.flags & IF_SCISSOR) ? g_rsImmSc[cullIdx] : g_rsImm[cullIdx];
        if (c.flags & IF_POLYOFF) rs = biasState(rs, cullIdx, (c.flags & IF_SCISSOR) != 0, (c.flags & IF_REVZ) != 0, c.pad2);
        if (c.flags & IF_SCISSOR) {
            uint32_t xy, wh; memcpy(&xy, &c.clearColor[2], 4); memcpy(&wh, &c.clearColor[3], 4);
            LONG x = (LONG)(xy & 0xFFFF), y = (LONG)(xy >> 16), w = (LONG)(wh & 0xFFFF), h = (LONG)(wh >> 16);
            D3D11_RECT r = g_tgtFbo ? D3D11_RECT{ x, y, x + w, y + h } : D3D11_RECT{ x, (LONG)g_tgtH - (y + h), x + w, (LONG)g_tgtH - y };
            g_ctx->RSSetScissorRects(1, &r);
        }
        if (rs != curR) { g_ctx->RSSetState(rs); curR = rs; }
        ID3D11ShaderResourceView* srv = nullptr; ID3D11SamplerState* smp = g_samp[0];
        if (c.flags & IF_TEX) {
            auto it = g_tex.find(c.tex);
            if (it != g_tex.end()) { srv = it->second.srv; smp = g_samp[it->second.params & 7]; }
        }
        if (srv != curT) { g_ctx->PSSetShaderResources(0, 1, &srv); curT = srv; }
        if (smp != curS) { g_ctx->PSSetSamplers(0, 1, &smp); curS = smp; }
        int topo = c.topology == 2 ? 2 : 1;
        if (topo != curTopo) {
            g_ctx->IASetPrimitiveTopology(topo == 2 ? D3D11_PRIMITIVE_TOPOLOGY_LINELIST : D3D11_PRIMITIVE_TOPOLOGY_TRIANGLELIST);
            curTopo = topo;
        }
        if (mesh) {
            if (mesh->topo == 0) g_ctx->DrawIndexedInstanced((UINT)(mesh->count / 4) * 6, 1, 0, 0, inst);
            else g_ctx->DrawInstanced((UINT)mesh->count, 1, 0, inst);
        } else if (c.topology == 0) g_ctx->DrawIndexedInstanced((UINT)(c.count / 4) * 6, 1, 0, c.first, inst);
        else g_ctx->DrawInstanced((UINT)c.count, 1, (UINT)c.first, inst);
        inst++;
        g_immDraws++;
    }
    ID3D11ShaderResourceView* nul = nullptr;
    g_ctx->PSSetShaderResources(0, 1, &nul);
}
