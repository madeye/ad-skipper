// Native YOLO11n skip-button detector on the ggml Vulkan backend (CPU
// fallback). Runs the op-program produced by ggml-yolo/convert.py, validated
// numerically against ONNX Runtime. Replaces the TFLite GPU delegate, which
// required OpenGL ES 3.1; ggml-vulkan only needs Vulkan 1.0.
#include <jni.h>
#include <android/bitmap.h>
#include <android/log.h>

#include <cstdint>
#include <cstdio>
#include <cstring>
#include <fstream>
#include <map>
#include <sstream>
#include <string>
#include <vector>

#include "ggml.h"
#include "ggml-backend.h"
#include "ggml-cpu.h"
#include "gguf.h"

#define LOG_TAG "YoloJni"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace {

constexpr int IN = 640;

struct Op {
    std::string type;
    std::vector<std::string> outs, ins;
    std::map<std::string, std::string> attr;
};

struct Engine {
    ggml_backend_t backend = nullptr;
    ggml_backend_buffer_t wbuf = nullptr;
    ggml_context* wctx = nullptr;       // weights
    ggml_context* cctx = nullptr;       // compute graph
    ggml_gallocr_t alloc = nullptr;
    ggml_cgraph* graph = nullptr;
    ggml_tensor* input = nullptr;
    ggml_tensor* output = nullptr;
    std::string backend_name = "none";
};

Engine g;

std::vector<std::string> split(const std::string& s, char d) {
    std::vector<std::string> v; std::string t; std::stringstream ss(s);
    while (std::getline(ss, t, d)) if (!t.empty()) v.push_back(t);
    return v;
}
std::vector<int> ints(const std::string& s) {
    std::vector<int> v; for (auto& x : split(s, ',')) v.push_back(std::atoi(x.c_str()));
    return v;
}
std::map<std::string, std::string> parse_attr(const std::string& s) {
    std::map<std::string, std::string> m;
    for (auto& kv : split(s, ';')) {
        auto p = kv.find('=');
        if (p != std::string::npos) m[kv.substr(0, p)] = kv.substr(p + 1);
    }
    return m;
}

void release() {
    if (g.alloc)  { ggml_gallocr_free(g.alloc);          g.alloc = nullptr; }
    if (g.cctx)   { ggml_free(g.cctx);                   g.cctx  = nullptr; }
    if (g.wbuf)   { ggml_backend_buffer_free(g.wbuf);    g.wbuf  = nullptr; }
    if (g.wctx)   { ggml_free(g.wctx);                   g.wctx  = nullptr; }
    if (g.backend){ ggml_backend_free(g.backend);        g.backend = nullptr; }
    g.graph = nullptr; g.input = nullptr; g.output = nullptr;
    g.backend_name = "none";
}

bool load_weights(const std::string& path) {
    struct gguf_init_params gp = { /*no_alloc*/ true, /*ctx*/ &g.wctx };
    gguf_context* gctx = gguf_init_from_file(path.c_str(), gp);
    if (!gctx) { LOGE("gguf load failed: %s", path.c_str()); return false; }

    g.wbuf = ggml_backend_alloc_ctx_tensors(g.wctx, g.backend);
    if (!g.wbuf) { LOGE("alloc weight tensors failed"); gguf_free(gctx); return false; }

    std::ifstream f(path, std::ios::binary);
    const size_t data_off = gguf_get_data_offset(gctx);
    std::vector<char> tmp;
    for (ggml_tensor* t = ggml_get_first_tensor(g.wctx); t; t = ggml_get_next_tensor(g.wctx, t)) {
        int idx = gguf_find_tensor(gctx, t->name);
        if (idx < 0) { LOGE("tensor %s not in gguf", t->name); gguf_free(gctx); return false; }
        size_t off = data_off + gguf_get_tensor_offset(gctx, idx);
        size_t n = ggml_nbytes(t);
        tmp.resize(n);
        f.seekg(off);
        f.read(tmp.data(), n);
        ggml_backend_tensor_set(t, tmp.data(), 0, n);
    }
    gguf_free(gctx);
    LOGI("loaded weights: %s", path.c_str());
    return true;
}

std::vector<Op> parse_prog(const std::string& path) {
    std::vector<Op> ops;
    std::ifstream pf(path);
    std::string line;
    auto clean = [](std::string s) { std::string o; for (char c : s) if (c != ' ') o += c; return o; };
    while (std::getline(pf, line)) {
        if (line.empty() || line[0] == '#') continue;
        std::stringstream ss(line);
        Op op; ss >> op.type;
        std::string rest; std::getline(ss, rest);
        auto arrow = rest.find("<-"); auto bar = rest.find('|');
        op.outs = split(clean(rest.substr(0, arrow)), ',');
        std::string ci = clean(rest.substr(arrow + 2, bar - (arrow + 2)));
        if (ci != "-") op.ins = split(ci, ',');
        op.attr = parse_attr(clean(rest.substr(bar + 1)));
        ops.push_back(op);
    }
    return ops;
}

// Build the ggml graph from the op-program (ported from the host validator).
bool build_graph(const std::vector<Op>& ops) {
    std::map<std::string, ggml_tensor*> T;
    for (ggml_tensor* t = ggml_get_first_tensor(g.wctx); t; t = ggml_get_next_tensor(g.wctx, t))
        T[t->name] = t;

    g.input = ggml_new_tensor_4d(g.cctx, GGML_TYPE_F32, IN, IN, 3, 1);
    ggml_set_name(g.input, "images");
    ggml_set_input(g.input);
    T["images"] = g.input;

    ggml_context* ctx = g.cctx;
    auto need = [&](const std::string& n) -> ggml_tensor* {
        auto it = T.find(n);
        if (it == T.end()) { LOGE("missing tensor %s", n.c_str()); return nullptr; }
        return it->second;
    };
    auto CONT = [&](ggml_tensor* t) { return ggml_cont(ctx, t); };

    for (auto& op : ops) {
        ggml_tensor* y = nullptr;
        auto A = op.attr;
        const std::string& ty = op.type;
        if (ty == "Conv") {
            ggml_tensor* x = need(op.ins[0]); ggml_tensor* w = need(op.ins[1]);
            if (!x || !w) return false;
            auto s = ints(A["s"]); auto p = ints(A["p"]);
            int dw = A.count("dw") ? std::atoi(A["dw"].c_str()) : 0;
            if (dw) {
                ggml_tensor* w16 = ggml_cast(ctx, w, GGML_TYPE_F16);
                y = ggml_conv_2d_dw(ctx, w16, x, s[0], s[1], p[0], p[1], 1, 1);
            } else {
                y = ggml_conv_2d(ctx, w, x, s[0], s[1], p[0], p[1], 1, 1);
            }
            if (op.ins.size() > 2) {
                ggml_tensor* b = need(op.ins[2]); if (!b) return false;
                y = ggml_add(ctx, y, ggml_reshape_4d(ctx, b, 1, 1, b->ne[0], 1));
            }
        } else if (ty == "Sigmoid") {
            y = ggml_sigmoid(ctx, need(op.ins[0]));
        } else if (ty == "Mul") { y = ggml_mul(ctx, need(op.ins[0]), need(op.ins[1]));
        } else if (ty == "Add") { y = ggml_add(ctx, need(op.ins[0]), need(op.ins[1]));
        } else if (ty == "Sub") { y = ggml_sub(ctx, need(op.ins[0]), need(op.ins[1]));
        } else if (ty == "Div") { y = ggml_div(ctx, need(op.ins[0]), need(op.ins[1]));
        } else if (ty == "Concat") {
            int dim = std::atoi(A["dim"].c_str());
            y = need(op.ins[0]);
            for (size_t k = 1; k < op.ins.size(); k++) y = ggml_concat(ctx, y, need(op.ins[k]), dim);
        } else if (ty == "MaxPool") {
            auto k = ints(A["k"]); auto s = ints(A["s"]); auto p = ints(A["p"]);
            y = ggml_pool_2d(ctx, need(op.ins[0]), GGML_OP_POOL_MAX, k[0], k[1], s[0], s[1], (float)p[0], (float)p[1]);
        } else if (ty == "Reshape") {
            auto ne = ints(A["ne"]);
            y = ggml_reshape_4d(ctx, CONT(need(op.ins[0])), ne[0], ne[1], ne[2], ne[3]);
        } else if (ty == "Transpose") {
            auto pm = ints(A["perm"]);
            y = CONT(ggml_permute(ctx, need(op.ins[0]), pm[0], pm[1], pm[2], pm[3]));
        } else if (ty == "Softmax") {
            int dim = std::atoi(A["dim"].c_str());
            ggml_tensor* x = need(op.ins[0]);
            if (dim == 0) y = ggml_soft_max(ctx, x);
            else {
                int ax[4] = {0, 1, 2, 3}; ax[0] = dim; ax[dim] = 0;
                ggml_tensor* xp = CONT(ggml_permute(ctx, x, ax[0], ax[1], ax[2], ax[3]));
                y = CONT(ggml_permute(ctx, ggml_soft_max(ctx, xp), ax[0], ax[1], ax[2], ax[3]));
            }
        } else if (ty == "Resize") {
            y = ggml_upscale(ctx, need(op.ins[0]), 2, GGML_SCALE_MODE_NEAREST);
        } else if (ty == "Split") {
            int dim = std::atoi(A["dim"].c_str());
            auto sizes = ints(A["sizes"]);
            ggml_tensor* x = need(op.ins[0]); if (!x) return false;
            size_t off = 0;
            for (size_t k = 0; k < op.outs.size(); k++) {
                int64_t ne[4] = { x->ne[0], x->ne[1], x->ne[2], x->ne[3] };
                ne[dim] = sizes[k];
                ggml_tensor* v = ggml_view_4d(ctx, x, ne[0], ne[1], ne[2], ne[3],
                                              x->nb[1], x->nb[2], x->nb[3], off * x->nb[dim]);
                off += sizes[k];
                T[op.outs[k]] = CONT(v);
            }
            continue;
        } else if (ty == "Slice") {
            int dim = std::atoi(A["dim"].c_str());
            int st = std::atoi(A["start"].c_str()), en = std::atoi(A["end"].c_str());
            ggml_tensor* x = need(op.ins[0]); if (!x) return false;
            int64_t ne[4] = { x->ne[0], x->ne[1], x->ne[2], x->ne[3] };
            ne[dim] = en - st;
            y = CONT(ggml_view_4d(ctx, x, ne[0], ne[1], ne[2], ne[3],
                                  x->nb[1], x->nb[2], x->nb[3], (size_t)st * x->nb[dim]));
        } else if (ty == "MatMul") {
            ggml_tensor* Ag = need(op.ins[0]); ggml_tensor* Bg = need(op.ins[1]);
            if (!Ag || !Bg) return false;
            y = ggml_mul_mat(ctx, CONT(ggml_transpose(ctx, Bg)), Ag);
        } else {
            LOGE("unhandled op %s", ty.c_str()); return false;
        }
        if (!y) return false;
        ggml_set_name(y, op.outs[0].c_str());
        T[op.outs[0]] = y;
    }

    g.output = need("output0");
    if (!g.output) return false;
    ggml_set_output(g.output);
    g.graph = ggml_new_graph_custom(g.cctx, 8192, false);
    ggml_build_forward_expand(g.graph, g.output);
    return true;
}

} // namespace

extern "C" JNIEXPORT jstring JNICALL
Java_com_adskipper_core_detect_YoloNative_nativeInit(
        JNIEnv* env, jobject, jstring jgguf, jstring jprog) {
    release();
    const char* gguf_path = env->GetStringUTFChars(jgguf, nullptr);
    const char* prog_path = env->GetStringUTFChars(jprog, nullptr);

    // GPU (Vulkan) via the backend registry; CPU fallback.
    ggml_backend_dev_t dev = ggml_backend_dev_by_type(GGML_BACKEND_DEVICE_TYPE_GPU);
    if (dev) g.backend = ggml_backend_dev_init(dev, nullptr);
    if (g.backend) g.backend_name = "gpu";
    else { g.backend = ggml_backend_cpu_init(); g.backend_name = "cpu"; }
    LOGI("backend: %s", g.backend_name.c_str());

    bool ok = load_weights(gguf_path);
    std::vector<Op> ops;
    if (ok) {
        ops = parse_prog(prog_path);
        ok = !ops.empty();
    }
    if (ok) {
        size_t mem = ggml_tensor_overhead() * 6144 + ggml_graph_overhead_custom(8192, false);
        struct ggml_init_params cp = { mem, nullptr, /*no_alloc*/ true };
        g.cctx = ggml_init(cp);
        ok = build_graph(ops);
    }
    if (ok) {
        g.alloc = ggml_gallocr_new(ggml_backend_get_default_buffer_type(g.backend));
        ok = ggml_gallocr_alloc_graph(g.alloc, g.graph);
    }

    env->ReleaseStringUTFChars(jgguf, gguf_path);
    env->ReleaseStringUTFChars(jprog, prog_path);
    if (!ok) { release(); return env->NewStringUTF("error"); }
    LOGI("YOLO ggml engine ready on %s", g.backend_name.c_str());
    return env->NewStringUTF(g.backend_name.c_str());
}

// Input: a 640x640 ARGB_8888 bitmap (letterboxed in Kotlin). Returns the
// [5*8400] output0 (xywh + class score, input-pixel coords) as a float[].
extern "C" JNIEXPORT jfloatArray JNICALL
Java_com_adskipper_core_detect_YoloNative_nativeInfer(
        JNIEnv* env, jobject, jobject bitmap) {
    if (!g.backend || !g.graph) return nullptr;

    AndroidBitmapInfo info;
    if (AndroidBitmap_getInfo(env, bitmap, &info) != ANDROID_BITMAP_RESULT_SUCCESS) return nullptr;
    if (info.width != IN || info.height != IN ||
        info.format != ANDROID_BITMAP_FORMAT_RGBA_8888) {
        LOGE("bad bitmap %ux%u fmt=%d", info.width, info.height, info.format);
        return nullptr;
    }
    void* pixels = nullptr;
    if (AndroidBitmap_lockPixels(env, bitmap, &pixels) != ANDROID_BITMAP_RESULT_SUCCESS) return nullptr;

    // Planar RGB float, /255, into images ne=[W,H,C,N] (W fastest).
    std::vector<float> in((size_t)IN * IN * 3);
    const uint8_t* px = (const uint8_t*)pixels;
    const size_t plane = (size_t)IN * IN;
    for (size_t i = 0; i < plane; i++) {
        in[i]             = px[i * 4 + 0] / 255.0f; // R
        in[plane + i]     = px[i * 4 + 1] / 255.0f; // G
        in[2 * plane + i] = px[i * 4 + 2] / 255.0f; // B
    }
    AndroidBitmap_unlockPixels(env, bitmap);

    ggml_backend_tensor_set(g.input, in.data(), 0, in.size() * sizeof(float));
    if (ggml_backend_graph_compute(g.backend, g.graph) != GGML_STATUS_SUCCESS) {
        LOGE("graph compute failed");
        return nullptr;
    }

    const size_t n = ggml_nelements(g.output); // 5 * 8400
    std::vector<float> out(n);
    ggml_backend_tensor_get(g.output, out.data(), 0, n * sizeof(float));

    jfloatArray arr = env->NewFloatArray((jsize)n);
    env->SetFloatArrayRegion(arr, 0, (jsize)n, out.data());
    return arr;
}

extern "C" JNIEXPORT void JNICALL
Java_com_adskipper_core_detect_YoloNative_nativeRelease(JNIEnv*, jobject) {
    release();
}
