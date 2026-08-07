// Native YOLO11n skip-button detector on NCNN (Vulkan compute, CPU/NEON
// fallback). The model is the official Ultralytics NCNN export (pnnx) of the
// trained skip_v2 weights, fp16 storage. Replaces the hand-converted ggml
// op-program: ncnn's Vulkan backend carries the Adreno/Mali driver
// workarounds that ggml-vulkan (desktop-focused) lacks, and simplevk means no
// dependency on the NDK Vulkan loader.
#include <jni.h>
#include <android/bitmap.h>
#include <android/log.h>

#include <cstring>
#include <string>
#include <vector>

#include "net.h"
#include "gpu.h"

#define LOG_TAG "YoloJni"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace {

constexpr int IN = 640;
constexpr int ANCHORS = 8400;
constexpr int CHANNELS = 5; // xywh + class score

ncnn::Net* g_net = nullptr;
std::string g_backend = "none";

void release_engine() {
    delete g_net;
    g_net = nullptr;
    g_backend = "none";
}

// Pipelines are compiled at load time, so a broken Vulkan driver fails here
// rather than at first inference.
bool try_load(const char* param, const char* bin, bool vulkan) {
    release_engine();
    g_net = new ncnn::Net();
    g_net->opt.use_vulkan_compute = vulkan;
    g_net->opt.num_threads = 4;
    if (g_net->load_param(param) != 0 || g_net->load_model(bin) != 0) {
        release_engine();
        return false;
    }
    g_backend = vulkan ? "gpu" : "cpu";
    return true;
}

} // namespace

extern "C" JNIEXPORT jstring JNICALL
Java_com_adskipper_core_detect_YoloNative_nativeInit(
        JNIEnv* env, jobject, jstring jparam, jstring jbin) {
    const char* param = env->GetStringUTFChars(jparam, nullptr);
    const char* bin = env->GetStringUTFChars(jbin, nullptr);

    int gpus = ncnn::get_gpu_count(); // lazily creates the gpu instance
    LOGI("ncnn %s, vulkan devices: %d", NCNN_VERSION_STRING, gpus);

    // Only real GPUs: ncnn's pipeline creation SIGSEGVs (not error-returns) on
    // the emulator's gfxstream driver, and software rasterizers are pointless.
    bool gpu_usable = false;
    if (gpus > 0) {
        const ncnn::GpuInfo& info = ncnn::get_gpu_info(0);
        const char* name = info.device_name();
        // type: 0 discrete, 1 integrated, 2 virtual, 3 cpu
        gpu_usable = (info.type() == 0 || info.type() == 1) &&
                     !strcasestr(name, "gfxstream") && !strcasestr(name, "swiftshader") &&
                     !strcasestr(name, "llvmpipe");
        LOGI("vulkan device 0: %s type=%d usable=%d", name, info.type(), (int)gpu_usable);
    }

    bool ok = gpu_usable && try_load(param, bin, true);
    if (!ok) ok = try_load(param, bin, false);

    env->ReleaseStringUTFChars(jparam, param);
    env->ReleaseStringUTFChars(jbin, bin);
    if (!ok) return env->NewStringUTF("error");
    LOGI("backend: %s", g_backend.c_str());
    return env->NewStringUTF(g_backend.c_str());
}

// Input: a 640x640 ARGB_8888 bitmap (letterboxed in Kotlin). Returns out0 as
// float[5*8400] (xywh + class score, input-pixel coords), same layout the
// ggml/TFLite versions produced.
extern "C" JNIEXPORT jfloatArray JNICALL
Java_com_adskipper_core_detect_YoloNative_nativeInfer(
        JNIEnv* env, jobject, jobject bitmap) {
    if (!g_net) return nullptr;

    AndroidBitmapInfo info;
    if (AndroidBitmap_getInfo(env, bitmap, &info) != ANDROID_BITMAP_RESULT_SUCCESS) return nullptr;
    if (info.width != IN || info.height != IN ||
        info.format != ANDROID_BITMAP_FORMAT_RGBA_8888) {
        LOGE("bad bitmap %ux%u fmt=%d", info.width, info.height, info.format);
        return nullptr;
    }

    ncnn::Mat in = ncnn::Mat::from_android_bitmap(env, bitmap, ncnn::Mat::PIXEL_RGBA2RGB);
    if (in.empty()) { LOGE("from_android_bitmap failed"); return nullptr; }
    const float norm[3] = { 1 / 255.f, 1 / 255.f, 1 / 255.f };
    in.substract_mean_normalize(nullptr, norm);

    ncnn::Extractor ex = g_net->create_extractor();
    ex.input("in0", in);
    ncnn::Mat out;
    if (ex.extract("out0", out) != 0) { LOGE("extract failed"); return nullptr; }
    if ((int)(out.w * out.h * out.c) != CHANNELS * ANCHORS) {
        LOGE("bad out %dx%dx%d", out.w, out.h, out.c);
        return nullptr;
    }

    // out is [h=5, w=8400]; copy row-wise (cstep may pad between channels,
    // rows within a channel are contiguous).
    std::vector<float> buf((size_t)CHANNELS * ANCHORS);
    ncnn::Mat flat = out.reshape(ANCHORS, CHANNELS);
    for (int r = 0; r < CHANNELS; r++)
        std::memcpy(buf.data() + (size_t)r * ANCHORS, flat.row(r), ANCHORS * sizeof(float));

    jfloatArray arr = env->NewFloatArray(CHANNELS * ANCHORS);
    env->SetFloatArrayRegion(arr, 0, CHANNELS * ANCHORS, buf.data());
    return arr;
}

extern "C" JNIEXPORT void JNICALL
Java_com_adskipper_core_detect_YoloNative_nativeRelease(JNIEnv*, jobject) {
    release_engine();
    ncnn::destroy_gpu_instance();
}
