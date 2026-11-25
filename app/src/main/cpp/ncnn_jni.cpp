/*
 * Copyright 2026 Christian Kierdorf
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

// JNI bridge between de.schliweb.makeacopy.ml.ncnn.NcnnNet and NCNN.
//
// One native handle is one loaded network. A network is immutable after loading, so any number of
// threads may run it at the same time; each run gets its own extractor. Destroying a network while
// a run is in flight is the caller's bug, the Java side excludes it with a lock.

#include <android/asset_manager_jni.h>
#include <jni.h>

#include <cstring>
#include <string>
#include <vector>

#include "net.h"

namespace {

const char* const kInputBlob = "in0";

struct ScopedUtf {
    JNIEnv* env;
    jstring string;
    const char* chars;
    ScopedUtf(JNIEnv* e, jstring s) : env(e), string(s), chars(s ? e->GetStringUTFChars(s, nullptr) : nullptr) {}
    ~ScopedUtf() {
        if (chars) env->ReleaseStringUTFChars(string, chars);
    }
};

ncnn::Net* newNet(jint threads) {
    ncnn::Net* net = new ncnn::Net();
    net->opt.num_threads = threads < 1 ? 1 : threads;
    net->opt.use_vulkan_compute = false;
    // Single precision from end to end. NCNN would otherwise switch to half precision on CPUs
    // that offer it, and that changes recognised text.
    net->opt.use_fp16_packed = false;
    net->opt.use_fp16_storage = false;
    net->opt.use_fp16_arithmetic = false;
    net->opt.use_bf16_packed = false;
    net->opt.use_bf16_storage = false;
    net->opt.use_int8_packed = false;
    net->opt.use_int8_storage = false;
    net->opt.use_int8_arithmetic = false;
    net->opt.use_int8_inference = false;
    // Free intermediate results as soon as no later layer needs them.
    net->opt.lightmode = true;
    return net;
}

void throwIllegalState(JNIEnv* env, const std::string& message) {
    jclass cls = env->FindClass("java/lang/IllegalStateException");
    if (cls) env->ThrowNew(cls, message.c_str());
}

}  // namespace

extern "C" {

JNIEXPORT jlong JNICALL Java_de_schliweb_makeacopy_ml_ncnn_NcnnNet_nativeLoadAssets(
        JNIEnv* env, jclass, jobject assetManager, jstring paramPath, jstring binPath, jint threads) {
    AAssetManager* manager = AAssetManager_fromJava(env, assetManager);
    ScopedUtf param(env, paramPath);
    ScopedUtf bin(env, binPath);
    if (!manager || !param.chars || !bin.chars) return 0;
    ncnn::Net* net = newNet(threads);
    if (net->load_param(manager, param.chars) != 0 || net->load_model(manager, bin.chars) != 0) {
        delete net;
        return 0;
    }
    return reinterpret_cast<jlong>(net);
}

JNIEXPORT jlong JNICALL Java_de_schliweb_makeacopy_ml_ncnn_NcnnNet_nativeLoadFiles(
        JNIEnv* env, jclass, jstring paramPath, jstring binPath, jint threads) {
    ScopedUtf param(env, paramPath);
    ScopedUtf bin(env, binPath);
    if (!param.chars || !bin.chars) return 0;
    ncnn::Net* net = newNet(threads);
    if (net->load_param(param.chars) != 0 || net->load_model(bin.chars) != 0) {
        delete net;
        return 0;
    }
    return reinterpret_cast<jlong>(net);
}

JNIEXPORT void JNICALL Java_de_schliweb_makeacopy_ml_ncnn_NcnnNet_nativeDestroy(JNIEnv*, jclass, jlong handle) {
    delete reinterpret_cast<ncnn::Net*>(handle);
}

// Runs the network on one CHW float tensor. Returns one float[] per requested output, flattened in
// C,H,W order (rows of a 2-D output follow each other), and writes three ints per output into
// `dims`: channels, height, width. Missing leading dimensions are 1.
JNIEXPORT jobjectArray JNICALL Java_de_schliweb_makeacopy_ml_ncnn_NcnnNet_nativeRun(
        JNIEnv* env, jclass, jlong handle, jfloatArray input, jint channels, jint height, jint width,
        jobjectArray outputNames, jintArray dims) {
    ncnn::Net* net = reinterpret_cast<ncnn::Net*>(handle);
    const jsize outputCount = env->GetArrayLength(outputNames);
    const size_t plane = static_cast<size_t>(height) * static_cast<size_t>(width);

    ncnn::Mat in(width, height, channels);
    if (in.empty()) {
        throwIllegalState(env, "out of memory for the network input");
        return nullptr;
    }
    for (int c = 0; c < channels; c++) {
        env->GetFloatArrayRegion(input, static_cast<jsize>(c * plane), static_cast<jsize>(plane), in.channel(c));
    }

    ncnn::Extractor extractor = net->create_extractor();
    if (extractor.input(kInputBlob, in) != 0) {
        throwIllegalState(env, "network has no input named in0");
        return nullptr;
    }

    jclass floatArrayClass = env->FindClass("[F");
    jobjectArray result = env->NewObjectArray(outputCount, floatArrayClass, nullptr);
    std::vector<jint> shape(static_cast<size_t>(outputCount) * 3);
    for (jsize i = 0; i < outputCount; i++) {
        jstring nameString = static_cast<jstring>(env->GetObjectArrayElement(outputNames, i));
        ncnn::Mat out;
        int status;
        {
            ScopedUtf name(env, nameString);
            status = extractor.extract(name.chars, out);
            if (status != 0 || out.empty()) {
                throwIllegalState(env, std::string("network produced no output named ") + name.chars);
                return nullptr;
            }
        }
        env->DeleteLocalRef(nameString);

        const int outChannels = out.dims >= 3 ? out.c : 1;
        const int outHeight = out.dims >= 3 ? out.h * out.d : (out.dims == 2 ? out.h : 1);
        const int outWidth = out.w;
        const jsize outPlane = static_cast<jsize>(outHeight) * outWidth;
        jfloatArray values = env->NewFloatArray(outChannels * outPlane);
        if (!values) return nullptr;  // OutOfMemoryError is pending
        if (out.dims >= 3) {
            for (int c = 0; c < outChannels; c++) {
                env->SetFloatArrayRegion(values, c * outPlane, outPlane, out.channel(c));
            }
        } else {
            env->SetFloatArrayRegion(values, 0, outPlane, static_cast<const float*>(out));
        }
        env->SetObjectArrayElement(result, i, values);
        env->DeleteLocalRef(values);
        shape[i * 3] = outChannels;
        shape[i * 3 + 1] = outHeight;
        shape[i * 3 + 2] = outWidth;
    }
    env->SetIntArrayRegion(dims, 0, outputCount * 3, shape.data());
    return result;
}

}  // extern "C"
