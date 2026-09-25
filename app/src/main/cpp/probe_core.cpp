#include <jni.h>
#include <string>
#include <unistd.h>
#include <sys/system_properties.h>

extern "C" JNIEXPORT jstring JNICALL
Java_com_devicecheck_app_MainActivity_getNativeBridgeStatus(JNIEnv* env, jobject /* this */) {
    char model[PROP_VALUE_MAX] = {0};
    __system_property_get("ro.product.model", model);

    std::string status = "C++20 Native Core Online (16KB Aligned) | Libc Model: " + std::string(model);
    return env->NewStringUTF(status.c_str());
}
