/*
 * Copyright (C) 2026 The halogenOS Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

#include <jni.h>
#include <nativehelper/JNIHelp.h>

// Defined in bionic/libc/bionic/system_property_api.cpp
extern "C" void __system_property_spoof_add(const char* name, const char* value);
extern "C" void __system_property_spoof_enable();
extern "C" void __system_property_spoof_hide(const char* name);

static void nativeSpoofSysProp(JNIEnv* env, jclass, jstring jname, jstring jvalue) {
    const char* name = env->GetStringUTFChars(jname, nullptr);
    const char* value = env->GetStringUTFChars(jvalue, nullptr);
    __system_property_spoof_add(name, value);
    env->ReleaseStringUTFChars(jname, name);
    env->ReleaseStringUTFChars(jvalue, value);
}

static void nativeHideSysProp(JNIEnv* env, jclass, jstring jname) {
    const char* name = env->GetStringUTFChars(jname, nullptr);
    __system_property_spoof_hide(name);
    env->ReleaseStringUTFChars(jname, name);
}

static void nativeEnableSysPropSpoof(JNIEnv*, jclass) {
    __system_property_spoof_enable();
}

static const JNINativeMethod gMethods[] = {
    {"nativeSpoofSysProp", "(Ljava/lang/String;Ljava/lang/String;)V",
     reinterpret_cast<void*>(nativeSpoofSysProp)},
    {"nativeHideSysProp", "(Ljava/lang/String;)V",
     reinterpret_cast<void*>(nativeHideSysProp)},
    {"nativeEnableSysPropSpoof", "()V",
     reinterpret_cast<void*>(nativeEnableSysPropSpoof)},
};

namespace android {

int register_com_android_internal_util_SimplePropImitation(JNIEnv* env) {
    return jniRegisterNativeMethods(env, "com/android/internal/util/SimplePropImitation",
                                    gMethods, NELEM(gMethods));
}

}  // namespace android
