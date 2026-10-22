#include <jni.h>
#include <string>
#include <vector>
#include <android/log.h>
#include "proot_executor.hpp"
#include "framebuffer_capture.hpp"
#include "input_injector.hpp"

#define ALOGD(...) __android_log_print(ANDROID_LOG_DEBUG, "LocalBotsNative", __VA_ARGS__)
#define ALOGE(...) __android_log_print(ANDROID_LOG_ERROR, "LocalBotsNative", __VA_ARGS__)

extern "C" {

JNIEXPORT jboolean JNICALL
Java_com_localbots_app_domain_nativebridge_NativeBridge_nativeInitEnvironment(
        JNIEnv *env,
        jobject /* this */,
        jstring rootfsPath_,
        jstring vfsMountPath_) {
    const char *rootfsPath = env->GetStringUTFChars(rootfsPath_, nullptr);
    const char *vfsMountPath = env->GetStringUTFChars(vfsMountPath_, nullptr);

    bool result = localbots::ProotExecutor::getInstance().initialize(rootfsPath, vfsMountPath);

    env->ReleaseStringUTFChars(rootfsPath_, rootfsPath);
    env->ReleaseStringUTFChars(vfsMountPath_, vfsMountPath);
    return result ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jobject JNICALL
Java_com_localbots_app_domain_nativebridge_NativeBridge_nativeExecuteCommand(
        JNIEnv *env,
        jobject /* this */,
        jstring command_,
        jint timeoutSec) {
    const char *command = env->GetStringUTFChars(command_, nullptr);

    localbots::CommandResult res = localbots::ProotExecutor::getInstance().execute(command, timeoutSec);
    env->ReleaseStringUTFChars(command_, command);

    jclass resultClass = env->FindClass("com/localbots/app/domain/nativebridge/NativeCommandResult");
    if (!resultClass) {
        ALOGE("Failed to find NativeCommandResult class");
        return nullptr;
    }

    jmethodID constructor = env->GetMethodID(resultClass, "<init>", "(ILjava/lang/String;Ljava/lang/String;D)V");
    if (!constructor) {
        ALOGE("Failed to find NativeCommandResult constructor");
        return nullptr;
    }

    jstring stdoutStr = env->NewStringUTF(res.stdoutOutput.c_str());
    jstring stderrStr = env->NewStringUTF(res.stderrOutput.c_str());

    jobject obj = env->NewObject(resultClass, constructor, res.exitCode, stdoutStr, stderrStr, res.durationSeconds);
    return obj;
}

JNIEXPORT jbyteArray JNICALL
Java_com_localbots_app_domain_nativebridge_NativeBridge_nativeCaptureFramebuffer(
        JNIEnv *env,
        jobject /* this */,
        jint displayNum,
        jstring format_) {
    const char *format = env->GetStringUTFChars(format_, nullptr);

    localbots::ScreenCaptureData data = localbots::FramebufferCapture::getInstance().captureDisplay(displayNum, "full", format);
    env->ReleaseStringUTFChars(format_, format);

    if (data.pixels.empty()) {
        return nullptr;
    }

    jbyteArray byteArray = env->NewByteArray(data.pixels.size());
    env->SetByteArrayRegion(byteArray, 0, data.pixels.size(), reinterpret_cast<const jbyte*>(data.pixels.data()));
    return byteArray;
}

JNIEXPORT jboolean JNICALL
Java_com_localbots_app_domain_nativebridge_NativeBridge_nativeInjectMouseMove(
        JNIEnv *env,
        jobject /* this */,
        jint x,
        jint y,
        jint z) {
    return localbots::InputInjector::getInstance().injectMouseMove(x, y, z) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_com_localbots_app_domain_nativebridge_NativeBridge_nativeInjectMouseClick(
        JNIEnv *env,
        jobject /* this */,
        jint button,
        jint x,
        jint y) {
    return localbots::InputInjector::getInstance().injectMouseClick(button, x, y) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_com_localbots_app_domain_nativebridge_NativeBridge_nativeInjectMouseDrag(
        JNIEnv *env,
        jobject /* this */,
        jint startX,
        jint startY,
        jint endX,
        jint endY) {
    return localbots::InputInjector::getInstance().injectMouseDrag(startX, startY, endX, endY) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_com_localbots_app_domain_nativebridge_NativeBridge_nativeInjectMouseScroll(
        JNIEnv *env,
        jobject /* this */,
        jstring direction_,
        jint delta) {
    const char *direction = env->GetStringUTFChars(direction_, nullptr);
    bool res = localbots::InputInjector::getInstance().injectMouseScroll(direction, delta);
    env->ReleaseStringUTFChars(direction_, direction);
    return res ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_com_localbots_app_domain_nativebridge_NativeBridge_nativeInjectKeyboardType(
        JNIEnv *env,
        jobject /* this */,
        jstring text_) {
    const char *text = env->GetStringUTFChars(text_, nullptr);
    bool res = localbots::InputInjector::getInstance().injectKeyboardType(text);
    env->ReleaseStringUTFChars(text_, text);
    return res ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_com_localbots_app_domain_nativebridge_NativeBridge_nativeInjectKeyboardShortcut(
        JNIEnv *env,
        jobject /* this */,
        jobjectArray keysArray) {
    std::vector<std::string> keys;
    int len = env->GetArrayLength(keysArray);
    for (int i = 0; i < len; ++i) {
        jstring keyStr = (jstring)env->GetObjectArrayElement(keysArray, i);
        const char *chars = env->GetStringUTFChars(keyStr, nullptr);
        keys.push_back(std::string(chars));
        env->ReleaseStringUTFChars(keyStr, chars);
        env->DeleteLocalRef(keyStr);
    }
    return localbots::InputInjector::getInstance().injectKeyboardShortcut(keys) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_com_localbots_app_domain_nativebridge_NativeBridge_nativeCheckBinary(
        JNIEnv *env,
        jobject /* this */,
        jstring binaryName_) {
    const char *binaryName = env->GetStringUTFChars(binaryName_, nullptr);
    bool res = localbots::ProotExecutor::getInstance().isBinaryAvailable(binaryName);
    env->ReleaseStringUTFChars(binaryName_, binaryName);
    return res ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_com_localbots_app_domain_nativebridge_NativeBridge_nativeMountVfs(
        JNIEnv *env,
        jobject /* this */,
        jstring hostPath_,
        jstring guestPath_) {
    const char *hostPath = env->GetStringUTFChars(hostPath_, nullptr);
    const char *guestPath = env->GetStringUTFChars(guestPath_, nullptr);
    ALOGD("Mounted VFS host: %s to guest: %s", hostPath, guestPath);
    env->ReleaseStringUTFChars(hostPath_, hostPath);
    env->ReleaseStringUTFChars(guestPath_, guestPath);
    return JNI_TRUE;
}

}
