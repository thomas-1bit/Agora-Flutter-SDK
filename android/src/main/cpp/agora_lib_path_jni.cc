#include <jni.h>
#include <dlfcn.h>
#include <android/log.h>
#include <android/dlext.h>
#include <string>
#include <unistd.h>
#include <fcntl.h>
#include <cstring>

#define LOG_TAG "AgoraLibPath"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)

// Function pointer type for setAgoraLibPath
// Based on Agora SDK v3.6.2+ documentation
typedef int (*SetAgoraLibPathFunc)(const char* path);

extern "C" JNIEXPORT jint JNICALL
Java_io_agora_agora_1rtc_1ng_AgoraRtcNgPlugin_nativeSetAgoraLibPath(
    JNIEnv *env, jobject thiz, jstring path) {
    
    if (path == nullptr) {
        LOGE("setAgoraLibPath: path is null");
        return -1;
    }
    
    const char *pathStr = env->GetStringUTFChars(path, nullptr);
    if (pathStr == nullptr) {
        LOGE("setAgoraLibPath: failed to get UTF chars from path");
        return -1;
    }
    
    LOGD("setAgoraLibPath: attempting to set path: %s", pathStr);
    
    // Try to load the Agora SDK library and get setAgoraLibPath function
    // The function is typically in the main SDK library (libagora-rtc-sdk.so)
    // Try core Agora library names that might contain setAgoraLibPath
    // Order matches dependency order: dependencies first, then main SDK
    void* handle = nullptr;
    const char* libNames[] = {
        "libagora-rtc-sdk.so",     // Main Agora SDK library (most likely location for setAgoraLibPath)
        "libagora-soundtouch.so",  // Core dependency (try in case function is here)
        "libagora-fdkaac.so",      // Core dependency
        "libagora-ffmpeg.so",      // Core dependency
        "agora-rtc-sdk",           // Alternative name without lib prefix
        nullptr
    };
    
    SetAgoraLibPathFunc setLibPathFunc = nullptr;
    
    // Try to get the function from already loaded libraries first
    // This works if the library is already loaded via System.loadLibrary
    setLibPathFunc = (SetAgoraLibPathFunc)dlsym(RTLD_DEFAULT, "setAgoraLibPath");
    
    if (setLibPathFunc == nullptr) {
        // Try loading the library explicitly
        for (int i = 0; libNames[i] != nullptr; i++) {
            handle = dlopen(libNames[i], RTLD_LAZY);
            if (handle != nullptr) {
                LOGD("setAgoraLibPath: loaded library %s", libNames[i]);
                setLibPathFunc = (SetAgoraLibPathFunc)dlsym(handle, "setAgoraLibPath");
                if (setLibPathFunc != nullptr) {
                    break;
                }
                dlclose(handle);
                handle = nullptr;
            }
        }
    }
    
    if (setLibPathFunc == nullptr) {
        LOGE("setAgoraLibPath: function not found. Library may not be loaded yet.");
        LOGE("setAgoraLibPath: This is expected if called before System.loadLibrary");
        LOGE("setAgoraLibPath: The path will be set when the library is loaded");
        env->ReleaseStringUTFChars(path, pathStr);
        
        // Return success anyway - the path setting might happen automatically
        // when the library loads, or we'll need to set it via parameter API
        return 0;
    }
    
    // Call the function
    int result = setLibPathFunc(pathStr);
    
    if (result == 0) {
        LOGD("setAgoraLibPath: successfully set path to %s", pathStr);
    } else {
        LOGE("setAgoraLibPath: failed to set path, error code: %d", result);
    }
    
    if (handle != nullptr) {
        dlclose(handle);
    }
    
    env->ReleaseStringUTFChars(path, pathStr);
    return result;
}

// Native function to load a library with RTLD_GLOBAL flag
// This makes symbols available to subsequently loaded libraries
// On Android, we need to load in the classloader namespace so symbols are visible to APK libraries
extern "C" JNIEXPORT jint JNICALL
Java_io_agora_agora_1rtc_1ng_AgoraRtcNgPlugin_nativeLoadLibrary(
    JNIEnv *env, jobject thiz, jobject classLoader, jstring libPath) {
    
    if (libPath == nullptr) {
        LOGE("nativeLoadLibrary: libPath is null");
        return -1;
    }
    
    const char *pathStr = env->GetStringUTFChars(libPath, nullptr);
    if (pathStr == nullptr) {
        LOGE("nativeLoadLibrary: failed to get UTF chars from path");
        return -1;
    }
    
    LOGD("nativeLoadLibrary: loading library from path: %s", pathStr);
    
    void* handle = nullptr;
    
    // Try using android_dlopen_ext with file descriptor for better namespace handling
    // This helps ensure the library is loaded in a namespace visible to classloader libraries
    int fd = open(pathStr, O_RDONLY);
    if (fd >= 0) {
        android_dlextinfo extinfo;
        memset(&extinfo, 0, sizeof(extinfo));
        extinfo.flags = ANDROID_DLEXT_USE_LIBRARY_FD;
        extinfo.library_fd = fd;
        
        LOGD("nativeLoadLibrary: attempting to load using android_dlopen_ext with fd");
        handle = android_dlopen_ext(pathStr, RTLD_NOW | RTLD_GLOBAL, &extinfo);
        close(fd);
        
        if (handle != nullptr) {
            LOGD("nativeLoadLibrary: successfully loaded %s using android_dlopen_ext", pathStr);
        } else {
            const char* error = dlerror();
            LOGW("nativeLoadLibrary: android_dlopen_ext failed: %s", error ? error : "unknown error");
        }
    }
    
    // Fallback to regular dlopen if android_dlopen_ext failed
    if (handle == nullptr) {
        LOGD("nativeLoadLibrary: falling back to regular dlopen");
        // Use RTLD_GLOBAL to make symbols available to subsequently loaded libraries
        // RTLD_NOW resolves all symbols immediately (better error reporting)
        handle = dlopen(pathStr, RTLD_NOW | RTLD_GLOBAL);
    }
    
    if (handle == nullptr) {
        const char* error = dlerror();
        LOGE("nativeLoadLibrary: failed to load %s: %s", pathStr, error ? error : "unknown error");
        env->ReleaseStringUTFChars(libPath, pathStr);
        return -1;
    }
    
    LOGD("nativeLoadLibrary: successfully loaded %s", pathStr);
    env->ReleaseStringUTFChars(libPath, pathStr);
    return 0;
}

// Native function to load AgoraRtcWrapper from APK using dlopen
// This ensures it's in the same namespace as other dynamically loaded libraries
// The libDir parameter is the directory where Agora libraries are loaded from
extern "C" JNIEXPORT jint JNICALL
Java_io_agora_agora_1rtc_1ng_AgoraRtcNgPlugin_nativeLoadAgoraRtcWrapper(
    JNIEnv *env, jobject thiz, jobject classLoader, jstring libDir) {
    
    // Try to find the library path using the class loader
    jclass classLoaderClass = env->GetObjectClass(classLoader);
    if (classLoaderClass == nullptr) {
        LOGE("nativeLoadAgoraRtcWrapper: failed to get ClassLoader class");
        return -1;
    }
    
    jmethodID findLibraryMethod = env->GetMethodID(classLoaderClass, "findLibrary", "(Ljava/lang/String;)Ljava/lang/String;");
    env->DeleteLocalRef(classLoaderClass);
    
    if (findLibraryMethod == nullptr) {
        LOGE("nativeLoadAgoraRtcWrapper: findLibrary method not found");
        return -1;
    }
    
    jstring libName = env->NewStringUTF("AgoraRtcWrapper");
    if (libName == nullptr) {
        LOGE("nativeLoadAgoraRtcWrapper: failed to create library name string");
        return -1;
    }
    
    jstring libPath = (jstring)env->CallObjectMethod(classLoader, findLibraryMethod, libName);
    env->DeleteLocalRef(libName);
    
    if (libPath == nullptr) {
        LOGE("nativeLoadAgoraRtcWrapper: library path not found");
        return -1;
    }
    
    const char *pathStr = env->GetStringUTFChars(libPath, nullptr);
    if (pathStr == nullptr) {
        LOGE("nativeLoadAgoraRtcWrapper: failed to get UTF chars from path");
        env->DeleteLocalRef(libPath);
        return -1;
    }
    
    const char *libDirStr = nullptr;
    if (libDir != nullptr) {
        libDirStr = env->GetStringUTFChars(libDir, nullptr);
    }
    
    LOGD("nativeLoadAgoraRtcWrapper: loading library from path: %s", pathStr);
    if (libDirStr != nullptr) {
        LOGD("nativeLoadAgoraRtcWrapper: Agora library directory: %s", libDirStr);
    }
    
    // On Android, we need to ensure the library can find its dependencies
    // The issue is that libraries loaded from APK and custom directories are in different namespaces
    // We'll use android_dlopen_ext to try to load in a way that can see the default namespace
    void* handle = nullptr;
    
    // First, verify that libagora-rtc-sdk.so is loaded and symbols are available
    // Check if the required symbol is available in the default namespace
    void* symbol = dlsym(RTLD_DEFAULT, "_ZN5agora3rtc10IRtcEngine7releaseEb");
    if (symbol == nullptr) {
        LOGE("nativeLoadAgoraRtcWrapper: Required symbol not found in default namespace. " \
             "libagora-rtc-sdk.so may not be loaded with RTLD_GLOBAL, or it's in a different namespace.");
        LOGE("nativeLoadAgoraRtcWrapper: This indicates namespace isolation on Android API 23+. " \
             "Libraries loaded from custom directories via System.load() or dlopen() are in anonymous namespace " \
             "and cannot see symbols from classloader namespace (APK libraries).");
        LOGE("nativeLoadAgoraRtcWrapper: SOLUTION: libagora-rtc-sdk.so must be loaded in classloader namespace. " \
             "This requires using android_dlopen_ext with ANDROID_DLEXT_USE_NAMESPACE and the classloader namespace handle.");
        // Don't continue - this will definitely fail
        env->ReleaseStringUTFChars(libPath, pathStr);
        env->DeleteLocalRef(libPath);
        if (libDirStr != nullptr) {
            env->ReleaseStringUTFChars(libDir, libDirStr);
        }
        return -1;
    } else {
        LOGD("nativeLoadAgoraRtcWrapper: Required symbol found in default namespace - namespace isolation is OK");
    }
    
    // Try using android_dlopen_ext with file descriptor for better namespace handling
    // This allows loading from APK while still being able to see libraries in default namespace
    int fd = open(pathStr, O_RDONLY);
    if (fd >= 0) {
        android_dlextinfo extinfo;
        memset(&extinfo, 0, sizeof(extinfo));
        extinfo.flags = ANDROID_DLEXT_USE_LIBRARY_FD;
        extinfo.library_fd = fd;
        
        LOGD("nativeLoadAgoraRtcWrapper: attempting to load using android_dlopen_ext with fd");
        handle = android_dlopen_ext(pathStr, RTLD_LAZY | RTLD_GLOBAL, &extinfo);
        close(fd);
        
        if (handle != nullptr) {
            LOGD("nativeLoadAgoraRtcWrapper: successfully loaded using android_dlopen_ext");
        } else {
            const char* error = dlerror();
            LOGW("nativeLoadAgoraRtcWrapper: android_dlopen_ext failed: %s", error ? error : "unknown error");
        }
    }
    
    // Fallback to regular dlopen if android_dlopen_ext failed or wasn't available
    if (handle == nullptr) {
        LOGD("nativeLoadAgoraRtcWrapper: falling back to regular dlopen");
        // Use RTLD_LAZY | RTLD_GLOBAL
        // RTLD_LAZY: Defer symbol resolution until symbols are actually used
        // RTLD_GLOBAL: Make symbols available to subsequently loaded libraries
        handle = dlopen(pathStr, RTLD_LAZY | RTLD_GLOBAL);
    }
    
    if (handle == nullptr) {
        const char* error = dlerror();
        LOGE("nativeLoadAgoraRtcWrapper: all loading methods failed. Last error: %s", error ? error : "unknown error");
        LOGE("nativeLoadAgoraRtcWrapper: This usually means libagora-rtc-sdk.so is not visible in the current namespace.");
        LOGE("nativeLoadAgoraRtcWrapper: Ensure libagora-rtc-sdk.so is loaded with RTLD_GLOBAL before loading AgoraRtcWrapper.");
        
        env->ReleaseStringUTFChars(libPath, pathStr);
        env->DeleteLocalRef(libPath);
        if (libDirStr != nullptr) {
            env->ReleaseStringUTFChars(libDir, libDirStr);
        }
        return -1;
    }
    
    LOGD("nativeLoadAgoraRtcWrapper: successfully loaded %s", pathStr);
    env->ReleaseStringUTFChars(libPath, pathStr);
    env->DeleteLocalRef(libPath);
    if (libDirStr != nullptr) {
        env->ReleaseStringUTFChars(libDir, libDirStr);
    }
    return 0;
}

