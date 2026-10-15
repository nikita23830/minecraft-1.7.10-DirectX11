struct ApiMesh { ID3D11Buffer* vb = nullptr; int count = 0; int topo = 0; };
static std::unordered_map<int, ApiMesh> g_apiMeshes;

static const ApiMesh* apiMeshFind(int id) {
    auto it = g_apiMeshes.find(id);
    return it != g_apiMeshes.end() && it->second.vb ? &it->second : nullptr;
}

extern "C" {
JNIEXPORT void JNICALL Java_com_nikita23830_minecraftd3d_D3DNative_apiMeshCreate(JNIEnv* env, jclass, jint id, jobject buf, jint count, jint topo) {
    ApiMesh& m = g_apiMeshes[id];
    SAFE_RELEASE(m.vb);
    m.count = count; m.topo = topo;
    if (count <= 0) return;
    D3D11_BUFFER_DESC bd = {}; bd.ByteWidth = (UINT)count * 28; bd.Usage = D3D11_USAGE_IMMUTABLE; bd.BindFlags = D3D11_BIND_VERTEX_BUFFER;
    D3D11_SUBRESOURCE_DATA sd = { env->GetDirectBufferAddress(buf) };
    if (FAILED(g_dev->CreateBuffer(&bd, &sd, &m.vb))) logf("apiMeshCreate %d: CreateBuffer failed", id);
}

JNIEXPORT void JNICALL Java_com_nikita23830_minecraftd3d_D3DNative_apiMeshDelete(JNIEnv*, jclass, jint id) {
    auto it = g_apiMeshes.find(id);
    if (it != g_apiMeshes.end()) { SAFE_RELEASE(it->second.vb); g_apiMeshes.erase(it); }
}

JNIEXPORT jlong JNICALL Java_com_nikita23830_minecraftd3d_D3DNative_apiDevice(JNIEnv*, jclass) { return (jlong)(intptr_t)g_dev; }

JNIEXPORT jlong JNICALL Java_com_nikita23830_minecraftd3d_D3DNative_apiContext(JNIEnv*, jclass) { return (jlong)(intptr_t)g_ctx; }
}
