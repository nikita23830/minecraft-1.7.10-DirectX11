struct FbDepth { ID3D11Texture2D* t = nullptr; ID3D11DepthStencilView* dsv = nullptr; UINT w = 0, h = 0; };
static std::unordered_map<int, FbDepth> g_fbDepth;
static int g_tgtTex, g_tgtDepth;

static DXGI_FORMAT depthFormat() { return (g_exp & 2) ? DXGI_FORMAT_D24_UNORM_S8_UINT : DXGI_FORMAT_D32_FLOAT; }

static bool texEnsureRT(Tex& t) {
    if (!t.t) return false;
    if (t.rtv) return true;
    D3D11_TEXTURE2D_DESC td; t.t->GetDesc(&td);
    if (!(td.BindFlags & D3D11_BIND_RENDER_TARGET)) {
        td.BindFlags |= D3D11_BIND_RENDER_TARGET;
        ID3D11Texture2D* nt = nullptr; ID3D11ShaderResourceView* ns = nullptr;
        if (FAILED(g_dev->CreateTexture2D(&td, nullptr, &nt))) { logf("texEnsureRT %dx%d: CreateTexture2D failed", t.w, t.h); return false; }
        g_ctx->CopyResource(nt, t.t);
        g_dev->CreateShaderResourceView(nt, nullptr, &ns);
        SAFE_RELEASE(t.srv); SAFE_RELEASE(t.t);
        t.t = nt; t.srv = ns;
    }
    t.rt = true;
    D3D11_RENDER_TARGET_VIEW_DESC rd = {}; rd.Format = td.Format; rd.ViewDimension = D3D11_RTV_DIMENSION_TEXTURE2D; rd.Texture2D.MipSlice = 0;
    return SUCCEEDED(g_dev->CreateRenderTargetView(t.t, &rd, &t.rtv));
}

static FbDepth* fbDepthEnsure(int key, UINT w, UINT h) {
    if (key == 0 || w == 0 || h == 0) return nullptr;
    FbDepth& d = g_fbDepth[key];
    if (d.t && d.w == w && d.h == h) return &d;
    SAFE_RELEASE(d.dsv); SAFE_RELEASE(d.t);
    D3D11_TEXTURE2D_DESC td = {};
    td.Width = w; td.Height = h; td.MipLevels = 1; td.ArraySize = 1; td.Format = depthFormat();
    td.SampleDesc.Count = 1; td.Usage = D3D11_USAGE_DEFAULT; td.BindFlags = D3D11_BIND_DEPTH_STENCIL;
    if (FAILED(g_dev->CreateTexture2D(&td, nullptr, &d.t))) { logf("fbDepth %d %ux%u failed", key, w, h); return nullptr; }
    g_dev->CreateDepthStencilView(d.t, nullptr, &d.dsv);
    d.w = w; d.h = h;
    g_ctx->ClearDepthStencilView(d.dsv, D3D11_CLEAR_DEPTH, 1.0f, 0);
    return &d;
}

static void setDefaultTarget() {
    g_tgtRtv = g_rtv; g_tgtDsv = g_dsv; g_tgtRes = g_off; g_tgtW = g_w; g_tgtH = g_h; g_tgtFbo = false; g_tgtTex = 0; g_tgtDepth = 0;
}

static void bindTarget(int tex, int depthKey, int w, int h) {
    if (tex == 0 && depthKey == 0) { setDefaultTarget(); return; }
    g_tgtFbo = true; g_tgtTex = tex; g_tgtDepth = depthKey;
    g_tgtRtv = nullptr; g_tgtDsv = nullptr; g_tgtRes = nullptr;
    if (tex) {
        auto it = g_tex.find(tex);
        if (it != g_tex.end() && texEnsureRT(it->second)) {
            g_tgtRtv = it->second.rtv; g_tgtRes = it->second.t; w = it->second.w; h = it->second.h;
        }
    }
    g_tgtW = (UINT)std::max(1, w); g_tgtH = (UINT)std::max(1, h);
    FbDepth* d = fbDepthEnsure(depthKey, g_tgtW, g_tgtH);
    g_tgtDsv = d ? d->dsv : nullptr;
}

static void applyTarget() {
    if (g_tgtRtv) g_ctx->OMSetRenderTargets(1, &g_tgtRtv, g_tgtDsv);
    else g_ctx->OMSetRenderTargets(0, nullptr, g_tgtDsv);
    D3D11_VIEWPORT vp = { 0, 0, (float)g_tgtW, (float)g_tgtH, 0, 1 };
    g_ctx->RSSetViewports(1, &vp);
}

static void fbDelete(int depthKey) {
    auto it = g_fbDepth.find(depthKey);
    if (it == g_fbDepth.end()) return;
    SAFE_RELEASE(it->second.dsv); SAFE_RELEASE(it->second.t);
    g_fbDepth.erase(it);
}

static const char* kFbHlsl = R"(
cbuffer P : register(b0) { float4 color; float4 a; float4 b; float4 c; float4 d; };
Texture2D src : register(t0);
SamplerState samp : register(s0);
float4 vsFull(uint id : SV_VertexID) : SV_Position {
    float2 uv = float2((id << 1) & 2, id & 2);
    return float4(uv * float2(2, -2) + float2(-1, 1), d.x, 1);
}
float4 psClear(float4 pos : SV_Position) : SV_Target { return color; }
float4 psBlit(float4 pos : SV_Position) : SV_Target {
    float gx = pos.x, gy = c.y > 0.5 ? c.x - pos.y : pos.y;
    float sx = b.x + (gx - a.x) * a.z, sy = b.y + (gy - a.y) * a.w;
    float v = c.w > 0.5 ? c.z - sy : sy;
    return src.SampleLevel(samp, float2(sx * b.z, v * b.w), 0);
}
)";
static ID3D11VertexShader* g_vsFull; static ID3D11PixelShader* g_psClear; static ID3D11PixelShader* g_psBlit;
static ID3D11Buffer* g_fbCb;
static ID3D11Texture2D* g_blitTmp; static ID3D11ShaderResourceView* g_blitTmpSrv; static UINT g_blitTmpW, g_blitTmpH;

static int initFramebuffer() {
    ID3DBlob* b;
    if (!(b = compile(kFbHlsl, "vsFull", "vs_5_0"))) return -1;
    g_dev->CreateVertexShader(b->GetBufferPointer(), b->GetBufferSize(), nullptr, &g_vsFull); b->Release();
    if (!(b = compile(kFbHlsl, "psClear", "ps_5_0"))) return -1;
    g_dev->CreatePixelShader(b->GetBufferPointer(), b->GetBufferSize(), nullptr, &g_psClear); b->Release();
    if (!(b = compile(kFbHlsl, "psBlit", "ps_5_0"))) return -1;
    g_dev->CreatePixelShader(b->GetBufferPointer(), b->GetBufferSize(), nullptr, &g_psBlit); b->Release();
    D3D11_BUFFER_DESC cbd = {}; cbd.ByteWidth = 80; cbd.Usage = D3D11_USAGE_DYNAMIC; cbd.BindFlags = D3D11_BIND_CONSTANT_BUFFER; cbd.CPUAccessFlags = D3D11_CPU_ACCESS_WRITE;
    return SUCCEEDED(g_dev->CreateBuffer(&cbd, nullptr, &g_fbCb)) ? 0 : -1;
}

static void fbConstants(const float* v20) {
    D3D11_MAPPED_SUBRESOURCE ms;
    g_ctx->Map(g_fbCb, 0, D3D11_MAP_WRITE_DISCARD, 0, &ms);
    memcpy(ms.pData, v20, 80);
    g_ctx->Unmap(g_fbCb, 0);
}

static D3D11_RECT glRect(int x, int y, int w, int h) {
    return g_tgtFbo ? D3D11_RECT{ x, y, x + w, y + h } : D3D11_RECT{ x, (LONG)g_tgtH - (y + h), x + w, (LONG)g_tgtH - y };
}

static void fullscreenPass(ID3D11PixelShader* ps) {
    g_ctx->IASetInputLayout(nullptr);
    g_ctx->IASetPrimitiveTopology(D3D11_PRIMITIVE_TOPOLOGY_TRIANGLELIST);
    g_ctx->VSSetShader(g_vsFull, nullptr, 0);
    g_ctx->PSSetShader(ps, nullptr, 0);
    g_ctx->VSSetConstantBuffers(0, 1, &g_fbCb);
    g_ctx->PSSetConstantBuffers(0, 1, &g_fbCb);
    g_ctx->Draw(3, 0);
}

static void clearRect(const ImmCmd& c, bool color, float depth) {
    D3D11_RECT r = { 0, 0, (LONG)g_tgtW, (LONG)g_tgtH };
    if (c.flags & IF_SCISSOR) { int v[4]; memcpy(v, c.proj, sizeof(v)); r = glRect(v[0], v[1], v[2], v[3]); }
    if (r.right <= r.left || r.bottom <= r.top) return;
    float k[20] = { 0 };
    if (color) memcpy(k, c.clearColor, 16);
    k[16] = depth;
    fbConstants(k);
    applyTarget();
    g_ctx->RSSetState(g_rsImmSc[0]);
    g_ctx->RSSetScissorRects(1, &r);
    g_ctx->OMSetBlendState(blendState(false, 1, 0, 1, 0, color ? c.colorMask & 15 : 0), nullptr, 0xffffffff);
    g_ctx->OMSetDepthStencilState(color ? depthState(false, false, 0x207) : depthState(true, true, 0x207), 0);
    fullscreenPass(g_psClear);
}

struct FbSurf { ID3D11Texture2D* color = nullptr; ID3D11ShaderResourceView* srv = nullptr; ID3D11RenderTargetView* rtv = nullptr;
                ID3D11Texture2D* depth = nullptr; ID3D11DepthStencilView* dsv = nullptr; UINT w = 0, h = 0; bool fbo = false; };

static FbSurf fbSurf(int tex, int depthKey) {
    FbSurf s;
    if (tex == 0 && depthKey == 0) {
        s.color = g_off; s.rtv = g_rtv; s.depth = g_depth; s.dsv = g_dsv; s.w = g_w; s.h = g_h;
        if (g_off && g_offSrv) s.srv = g_offSrv;
        return s;
    }
    s.fbo = true;
    if (tex) {
        auto it = g_tex.find(tex);
        if (it != g_tex.end() && it->second.t) {
            texEnsureRT(it->second);
            s.color = it->second.t; s.srv = it->second.srv; s.rtv = it->second.rtv; s.w = it->second.w; s.h = it->second.h;
        }
    }
    if (depthKey) {
        auto it = g_fbDepth.find(depthKey);
        if (it != g_fbDepth.end()) { s.depth = it->second.t; s.dsv = it->second.dsv; if (!s.w) { s.w = it->second.w; s.h = it->second.h; } }
    }
    return s;
}

static void blitCmd(const ImmCmd& c) {
    int v[14]; memcpy(v, c.proj, sizeof(v));
    FbSurf src = fbSurf(v[10], v[11]), dst = fbSurf(v[12], v[13]);
    int mask = v[8];
    if ((mask & 0x4000) && src.color && dst.rtv && src.srv && v[6] != v[4] && v[7] != v[5]) {
        ID3D11ShaderResourceView* srv = src.srv;
        if (src.color == dst.color) {
            if (!g_blitTmp || g_blitTmpW != src.w || g_blitTmpH != src.h) {
                SAFE_RELEASE(g_blitTmpSrv); SAFE_RELEASE(g_blitTmp);
                D3D11_TEXTURE2D_DESC td; src.color->GetDesc(&td);
                td.BindFlags = D3D11_BIND_SHADER_RESOURCE; td.MipLevels = 1; td.MiscFlags = 0;
                g_dev->CreateTexture2D(&td, nullptr, &g_blitTmp);
                if (g_blitTmp) g_dev->CreateShaderResourceView(g_blitTmp, nullptr, &g_blitTmpSrv);
                g_blitTmpW = src.w; g_blitTmpH = src.h;
            }
            if (!g_blitTmp) return;
            g_ctx->CopySubresourceRegion(g_blitTmp, 0, 0, 0, 0, src.color, 0, nullptr);
            srv = g_blitTmpSrv;
        }
        float k[20] = { 0 };
        k[4] = (float)v[4]; k[5] = (float)v[5];
        k[6] = (float)(v[2] - v[0]) / (float)(v[6] - v[4]); k[7] = (float)(v[3] - v[1]) / (float)(v[7] - v[5]);
        k[8] = (float)v[0]; k[9] = (float)v[1]; k[10] = 1.0f / (float)src.w; k[11] = 1.0f / (float)src.h;
        k[12] = (float)dst.h; k[13] = dst.fbo ? 0.0f : 1.0f; k[14] = (float)src.h; k[15] = src.fbo ? 0.0f : 1.0f;
        fbConstants(k);

        int x0 = std::min(v[4], v[6]), x1 = std::max(v[4], v[6]), y0 = std::min(v[5], v[7]), y1 = std::max(v[5], v[7]);
        D3D11_RECT r = dst.fbo ? D3D11_RECT{ x0, y0, x1, y1 } : D3D11_RECT{ x0, (LONG)dst.h - y1, x1, (LONG)dst.h - y0 };
        r.left = std::max<LONG>(0, r.left); r.top = std::max<LONG>(0, r.top);
        r.right = std::min<LONG>((LONG)dst.w, r.right); r.bottom = std::min<LONG>((LONG)dst.h, r.bottom);
        if (r.right > r.left && r.bottom > r.top) {
            ID3D11ShaderResourceView* nul = nullptr; g_ctx->PSSetShaderResources(0, 1, &nul);
            g_ctx->OMSetRenderTargets(1, &dst.rtv, nullptr);
            D3D11_VIEWPORT vp = { 0, 0, (float)dst.w, (float)dst.h, 0, 1 };
            g_ctx->RSSetViewports(1, &vp);
            g_ctx->RSSetState(g_rsImmSc[0]);
            g_ctx->RSSetScissorRects(1, &r);
            g_ctx->OMSetBlendState(blendState(false, 1, 0, 1, 0, 15), nullptr, 0xffffffff);
            g_ctx->OMSetDepthStencilState(depthState(false, false, 0x207), 0);
            g_ctx->PSSetShaderResources(0, 1, &srv);
            ID3D11SamplerState* smp = g_samp[(v[9] == 0x2601 ? TP_BLUR : 0) | TP_CLAMP];
            g_ctx->PSSetSamplers(0, 1, &smp);
            fullscreenPass(g_psBlit);
            g_ctx->PSSetShaderResources(0, 1, &nul);
        }
    }
    if (mask & 0x100) {
        if (src.depth && dst.depth && src.depth != dst.depth && src.w == dst.w && src.h == dst.h
            && v[0] == v[4] && v[1] == v[5] && v[2] == v[6] && v[3] == v[7]) {
            g_ctx->CopyResource(dst.depth, src.depth);
        } else if (src.depth && dst.depth && src.depth != dst.depth) {
            static bool warned; if (!warned) { warned = true; logf("glBlitFramebuffer: Tiefe nur 1:1 bei gleicher Größe (%ux%u -> %ux%u)", src.w, src.h, dst.w, dst.h); }
        }
    }
}

static ID3D11Texture2D* g_stage; static D3D11_TEXTURE2D_DESC g_stageDesc;

static ID3D11Texture2D* stagingFor(const D3D11_TEXTURE2D_DESC& like, UINT w, UINT h) {
    if (g_stage && g_stageDesc.Width == w && g_stageDesc.Height == h && g_stageDesc.Format == like.Format && g_stageDesc.MipLevels == 1) return g_stage;
    SAFE_RELEASE(g_stage);
    D3D11_TEXTURE2D_DESC td = {};
    td.Width = w; td.Height = h; td.MipLevels = 1; td.ArraySize = 1; td.Format = like.Format; td.SampleDesc.Count = 1;
    td.Usage = D3D11_USAGE_STAGING; td.CPUAccessFlags = D3D11_CPU_ACCESS_READ;
    if (FAILED(g_dev->CreateTexture2D(&td, nullptr, &g_stage))) { logf("staging %ux%u fmt=%d failed", w, h, (int)like.Format); return nullptr; }
    g_stageDesc = td;
    return g_stage;
}

static int fbRead(int tex, int depthKey, int kind, int x, int y, int w, int h, uint8_t* out) {
    FbSurf s = fbSurf(tex, depthKey);
    ID3D11Texture2D* res = kind ? s.depth : s.color;
    memset(out, 0, (size_t)w * h * 4);
    if (!res || w <= 0 || h <= 0) return -1;
    D3D11_TEXTURE2D_DESC td; res->GetDesc(&td);
    int x0 = std::max(0, x), x1 = std::min((int)s.w, x + w), y0 = std::max(0, y), y1 = std::min((int)s.h, y + h);
    if (x1 <= x0 || y1 <= y0) return 0;
    int top = s.fbo ? y0 : (int)s.h - y1, bottom = s.fbo ? y1 : (int)s.h - y0;
    ID3D11Texture2D* st;
    if (kind) {
        st = stagingFor(td, td.Width, td.Height);
        if (!st) return -2;
        g_ctx->CopyResource(st, res);
    } else {
        st = stagingFor(td, (UINT)(x1 - x0), (UINT)(bottom - top));
        if (!st) return -2;
        D3D11_BOX box = { (UINT)x0, (UINT)top, 0, (UINT)x1, (UINT)bottom, 1 };
        g_ctx->CopySubresourceRegion(st, 0, 0, 0, 0, res, 0, &box);
    }
    D3D11_MAPPED_SUBRESOURCE ms;
    if (FAILED(g_ctx->Map(st, 0, D3D11_MAP_READ, 0, &ms))) return -3;
    for (int gy = y0; gy < y1; gy++) {
        int d3dRow = s.fbo ? gy : (int)s.h - 1 - gy;
        int row = kind ? d3dRow : d3dRow - top, col = kind ? x0 : 0;
        const uint8_t* src = (const uint8_t*)ms.pData + (size_t)row * ms.RowPitch + (size_t)col * 4;
        uint8_t* dst = out + ((size_t)(gy - y) * w + (x0 - x)) * 4;
        if (kind && td.Format == DXGI_FORMAT_D24_UNORM_S8_UINT) {
            for (int i = 0; i < x1 - x0; i++) { uint32_t d; memcpy(&d, src + i * 4, 4); float f = (float)(d & 0xFFFFFF) / 16777215.0f; memcpy(dst + i * 4, &f, 4); }
        } else memcpy(dst, src, (size_t)(x1 - x0) * 4);
    }
    g_ctx->Unmap(st, 0);
    return 0;
}

static int texRead(int id, int level, uint8_t* out, size_t cap) {
    auto it = g_tex.find(id);
    if (it == g_tex.end() || !it->second.t || level >= it->second.mips) return -1;
    UINT lw = (UINT)std::max(1, it->second.w >> level), lh = (UINT)std::max(1, it->second.h >> level);
    if ((size_t)lw * lh * 4 > cap) return -4;
    D3D11_TEXTURE2D_DESC td; it->second.t->GetDesc(&td);
    ID3D11Texture2D* st = stagingFor(td, lw, lh);
    if (!st) return -2;
    g_ctx->CopySubresourceRegion(st, 0, 0, 0, 0, it->second.t, (UINT)level, nullptr);
    D3D11_MAPPED_SUBRESOURCE ms;
    if (FAILED(g_ctx->Map(st, 0, D3D11_MAP_READ, 0, &ms))) return -3;
    for (UINT r = 0; r < lh; r++) memcpy(out + (size_t)r * lw * 4, (const uint8_t*)ms.pData + (size_t)r * ms.RowPitch, (size_t)lw * 4);
    g_ctx->Unmap(st, 0);
    return 0;
}
