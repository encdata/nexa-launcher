#include <cerrno>
#include <jni.h>
#include <android/log.h>
#include <android/bitmap.h>
#include <android/hardware_buffer.h>
#include <android/hardware_buffer_jni.h>
#include <android/native_window.h>
#include <android/native_window_jni.h>
#include <android/api-level.h>

#include <EGL/egl.h>
#include <EGL/eglext.h>

#include <csignal>
#include <cstring>
#include <cstdint>

#include <atomic>


static std::atomic<PFNEGLCREATEIMAGEKHRPROC> createImage{nullptr};
static std::atomic<PFNEGLGETNATIVECLIENTBUFFERANDROIDPROC> getClientBuffer{nullptr};

extern "C"
JNIEXPORT jlong JNICALL
Java_com_termux_gui_hbuffers_HBuffers_00024Companion_nativeHardwareBufferToEGLImageKHR(JNIEnv *env,
                                                                                       jobject thiz,
                                                                                       jlong dispJ,
                                                                                       jobject bJ) {
    auto disp = (EGLDisplay) dispJ;
    AHardwareBuffer* b = AHardwareBuffer_fromHardwareBuffer(env, bJ);
    if (createImage.load() == nullptr) {
        createImage = (PFNEGLCREATEIMAGEKHRPROC) eglGetProcAddress("eglCreateImageKHR");
        if (createImage.load() == nullptr) {
            jclass nullPointerException = env->FindClass("java/lang/NullPointerException");
            if (nullPointerException == nullptr) {
                __android_log_print( ANDROID_LOG_ERROR,"nativeHardwareBufferToEGLImageKHR", "Could not find class NullPointerException\n");
            } else {
                env->ThrowNew(nullPointerException, "Could not get eglCreateImageKHR address");
            }
            return reinterpret_cast<jlong>(EGL_NO_IMAGE_KHR);
        }
    }
    if (getClientBuffer.load() == nullptr) {
        getClientBuffer = (PFNEGLGETNATIVECLIENTBUFFERANDROIDPROC) eglGetProcAddress("eglGetNativeClientBufferANDROID");
        if (getClientBuffer.load() == nullptr) {
            jclass nullPointerException = env->FindClass("java/lang/NullPointerException");
            if (nullPointerException == nullptr) {
                __android_log_print( ANDROID_LOG_ERROR,"nativeHardwareBufferToEGLImageKHR", "Could not find class NullPointerException\n");
            } else {
                env->ThrowNew(nullPointerException, "Could not get eglGetNativeClientBufferANDROID address");
            }
            return reinterpret_cast<jlong>(EGL_NO_IMAGE_KHR);
        }
    }
    EGLClientBuffer cb = getClientBuffer.load()(b);
    if (cb == nullptr) {
        return reinterpret_cast<jlong>(EGL_NO_IMAGE_KHR);
    }
    EGLint attribs[] = {EGL_IMAGE_PRESERVED_KHR, EGL_TRUE, EGL_NONE};
    EGLImageKHR img = createImage.load()(disp, EGL_NO_CONTEXT, EGL_NATIVE_BUFFER_ANDROID, cb, attribs);
    return reinterpret_cast<jlong>(img);
}
extern "C"
JNIEXPORT jint JNICALL
Java_com_termux_gui_hbuffers_HBuffers_00024Companion_sendHardwareBuffer(JNIEnv *env, jobject thiz,
                                                                        jint fd, jobject bJ) {
    AHardwareBuffer* b = AHardwareBuffer_fromHardwareBuffer(env, bJ);
    // Ignore sigpipe as a precaution, AHardwareBuffer_sendHandleToUnixSocket doesn't use the NO_SIGNAL flag for sendmsg
    signal(SIGPIPE, SIG_IGN);
    return AHardwareBuffer_sendHandleToUnixSocket(b, fd);
}

extern "C"
JNIEXPORT jint JNICALL
Java_com_termux_gui_hbuffers_HBuffers_00024Companion_copyBitmapToBuffer(JNIEnv *env, jobject thiz,
                                                                        jobject bitmap, jobject bufferJ) {
    AndroidBitmapInfo bitmapInfo{};
    if (AndroidBitmap_getInfo(env, bitmap, &bitmapInfo) != ANDROID_BITMAP_RESULT_SUCCESS
        || bitmapInfo.format != ANDROID_BITMAP_FORMAT_RGBA_8888) {
        return -1;
    }

    AHardwareBuffer* buffer = AHardwareBuffer_fromHardwareBuffer(env, bufferJ);
    if (buffer == nullptr) return -2;

    AHardwareBuffer_Desc desc{};
    AHardwareBuffer_describe(buffer, &desc);
    if (desc.format != AHARDWAREBUFFER_FORMAT_R8G8B8A8_UNORM
        || desc.width != bitmapInfo.width || desc.height != bitmapInfo.height) {
        return -3;
    }

    void* source = nullptr;
    if (AndroidBitmap_lockPixels(env, bitmap, &source) != ANDROID_BITMAP_RESULT_SUCCESS) {
        return -4;
    }

    void* destination = nullptr;
    const int lockResult = AHardwareBuffer_lock(
        buffer, AHARDWAREBUFFER_USAGE_CPU_WRITE_OFTEN, -1, nullptr, &destination);
    if (lockResult != 0 || destination == nullptr) {
        AndroidBitmap_unlockPixels(env, bitmap);
        return lockResult == 0 ? -5 : lockResult;
    }

    const auto* sourceBytes = static_cast<const uint8_t*>(source);
    auto* destinationBytes = static_cast<uint8_t*>(destination);
    for (uint32_t y = 0; y < desc.height; ++y) {
        const auto* sourceRow = sourceBytes + y * bitmapInfo.stride;
        auto* destinationRow = destinationBytes + y * desc.stride * 4;
        std::memcpy(destinationRow, sourceRow, desc.width * 4);
    }

    const int unlockResult = AHardwareBuffer_unlock(buffer, nullptr);
    AndroidBitmap_unlockPixels(env, bitmap);
    return unlockResult;
}
