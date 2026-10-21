// SPDX-License-Identifier: Apache-2.0
// Original bounded CPU adapter. NCNN and model notices are distributed separately.
#include <jni.h>
#include <android/bitmap.h>
#include <net.h>
#include <mat.h>
#include <algorithm>
#include <cmath>
#include <memory>
#include <stdexcept>
#include <string>
#include <vector>

namespace {
struct Engine {
    ncnn::Net net;
    bool cugan;
};
struct Utf {
    JNIEnv* env;
    jstring value;
    const char* text;
    Utf(JNIEnv* e, jstring s) : env(e), value(s), text(s ? e->GetStringUTFChars(s, nullptr) : nullptr) {}
    ~Utf() { if (text) env->ReleaseStringUTFChars(value, text); }
};
void fail(JNIEnv* env, const char* reason) {
    env->ThrowNew(env->FindClass("java/lang/IllegalStateException"), reason);
}
struct Pixels {
    JNIEnv* env;
    jobject bitmap;
    void* data = nullptr;
    Pixels(JNIEnv* e, jobject b) : env(e), bitmap(b) {
        if (AndroidBitmap_lockPixels(env, bitmap, &data) != ANDROID_BITMAP_RESULT_SUCCESS)
            throw std::runtime_error("Image memory unavailable");
    }
    ~Pixels() { if (data) AndroidBitmap_unlockPixels(env, bitmap); }
};
}

extern "C" JNIEXPORT jlong JNICALL
Java_it_lorelore_nyanime_reader_quality_NcnnNative_create(JNIEnv* env, jobject, jstring param, jstring weights, jboolean cugan) {
    try {
        Utf p(env, param), w(env, weights);
        if (!p.text || !w.text) throw std::runtime_error("Missing verified model");
        auto engine = std::make_unique<Engine>();
        engine->cugan = cugan;
        engine->net.opt.num_threads = 2;
        engine->net.opt.use_vulkan_compute = false;
        // A reader visits many differently shaped edge tiles. NCNN's Net-local
        // pool retains their intermediate buffers for the entire book session.
        // Keep the model loaded, but release each inference's temporary tensors.
        engine->net.opt.use_local_pool_allocator = false;
        engine->net.opt.use_fp16_storage = false;
        engine->net.opt.use_fp16_arithmetic = false;
        if (engine->net.load_param(p.text) || engine->net.load_model(w.text))
            throw std::runtime_error("Cannot load verified model");
        return reinterpret_cast<jlong>(engine.release());
    } catch (const std::exception& error) { fail(env, error.what()); return 0; }
}

extern "C" JNIEXPORT void JNICALL
Java_it_lorelore_nyanime_reader_quality_NcnnNative_destroy(JNIEnv*, jobject, jlong handle) {
    delete reinterpret_cast<Engine*>(handle);
}

extern "C" JNIEXPORT void JNICALL
Java_it_lorelore_nyanime_reader_quality_NcnnNative_process(JNIEnv* env, jobject, jlong handle, jobject source, jobject destination) {
    try {
        auto* engine = reinterpret_cast<Engine*>(handle);
        if (!engine || !source || !destination) throw std::runtime_error("Invalid image request");
        AndroidBitmapInfo a{}, b{};
        if (AndroidBitmap_getInfo(env, source, &a) || AndroidBitmap_getInfo(env, destination, &b) ||
            a.format != ANDROID_BITMAP_FORMAT_RGBA_8888 || b.format != ANDROID_BITMAP_FORMAT_RGBA_8888 ||
            a.width < 32 || a.height < 32 || a.width > 256 || a.height > 256 ||
            b.width != a.width * 2 || b.height != a.height * 2)
            throw std::runtime_error("Invalid image dimensions");
        ncnn::Mat input;
        {
            Pixels src(env, source);
            const auto* bytes = static_cast<const unsigned char*>(src.data);
            // Transparency is not flattened or silently discarded.
            for (unsigned y = 0; y < a.height; ++y)
                for (unsigned x = 0; x < a.width; ++x)
                    if (bytes[y * a.stride + x * 4 + 3] != 255)
                        throw std::runtime_error("Transparent image requires original");
            input = ncnn::Mat::from_pixels(bytes, ncnn::Mat::PIXEL_RGBA2RGB, a.width, a.height, a.stride);
        }
        if (input.empty()) throw std::runtime_error("Image allocation failed");
        const float normalization[] = {1.f / 255.f, 1.f / 255.f, 1.f / 255.f};
        input.substract_mean_normalize(nullptr, normalization);
        const int pad = engine->cugan ? 18 : 16;
        const int extraX = engine->cugan ? a.width % 2 : 0;
        const int extraY = engine->cugan ? a.height % 2 : 0;
        ncnn::Mat padded;
        ncnn::copy_make_border(input, padded, pad, pad + extraY, pad, pad + extraX,
            ncnn::BORDER_REFLECT, 0.f, engine->net.opt);
        if (padded.empty()) throw std::runtime_error("Image padding failed");
        ncnn::Extractor ex = engine->net.create_extractor();
        ncnn::Mat result;
        if (ex.input(engine->cugan ? "in0" : "data", padded) ||
            ex.extract(engine->cugan ? "out0" : "output", result) || result.empty() || result.c != 3)
            throw std::runtime_error("Image inference failed");
        const int offset = engine->cugan ? 0 : pad * 2;
        if (result.w < offset + static_cast<int>(b.width) || result.h < offset + static_cast<int>(b.height))
            throw std::runtime_error("Model geometry differs from declared scale");
        Pixels dst(env, destination);
        auto* bytes = static_cast<unsigned char*>(dst.data);
        for (unsigned y = 0; y < b.height; ++y) {
            const float* red = result.channel(0).row(y + offset) + offset;
            const float* green = result.channel(1).row(y + offset) + offset;
            const float* blue = result.channel(2).row(y + offset) + offset;
            auto* row = bytes + y * b.stride;
            for (unsigned x = 0; x < b.width; ++x) {
                row[x * 4] = std::clamp(std::lround(red[x] * 255.f), 0L, 255L);
                row[x * 4 + 1] = std::clamp(std::lround(green[x] * 255.f), 0L, 255L);
                row[x * 4 + 2] = std::clamp(std::lround(blue[x] * 255.f), 0L, 255L);
                row[x * 4 + 3] = 255;
            }
        }
    } catch (const std::exception& error) { fail(env, error.what()); }
}
