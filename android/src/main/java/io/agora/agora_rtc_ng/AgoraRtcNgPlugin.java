package io.agora.agora_rtc_ng;

import android.content.Context;
import android.os.Build;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.ref.WeakReference;
import java.lang.reflect.Array;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.HashMap;

import dalvik.system.DexFile;
import dalvik.system.PathClassLoader;

import io.flutter.embedding.engine.plugins.FlutterPlugin;
import io.flutter.plugin.common.MethodCall;
import io.flutter.plugin.common.MethodChannel;

public class AgoraRtcNgPlugin implements FlutterPlugin, MethodChannel.MethodCallHandler {

    private static final String LIB_DIR_NAME = "agora_libs";
    private static boolean isLibPathSet = false;
    private static String currentLibPath = null;

    // List of required .so files for Agora SDK (arm64-v8a and armeabi-v7a)
    // All Agora-related libraries from the build output
    // Order is critical: dependencies must be loaded before libraries that depend on them
    // Based on error logs:
    // - libagora-rtc-sdk.so needs libagora-soundtouch.so and libaosl.so first
    // - All extension libraries need libagora-rtc-sdk.so to be loaded first
    private static final String[] AGORA_CORE_LIBS = {
        // Core dependencies (must be loaded first, in dependency order)
        "libagora-soundtouch.so",  // Required by libagora-rtc-sdk.so
        "libagora-fdkaac.so",      // Core dependency
        "libagora-ffmpeg.so",      // Core dependency
        "libaosl.so",              // Required by libagora-rtc-sdk.so
        // Main SDK (must be loaded after all dependencies)
        "libagora-rtc-sdk.so",     // Main SDK - all extensions depend on this
     
    };

    private MethodChannel channel;
    private WeakReference<FlutterPluginBinding> flutterPluginBindingRef;
    private VideoViewController videoViewController;
    @Nullable
    private Context applicationContext;

    // Native method to set Agora library path
    // Returns 0 on success, negative value on error
    private native int nativeSetAgoraLibPath(String path);
    
    // Native method to load a library with RTLD_GLOBAL flag in classloader namespace
    // This makes symbols available to subsequently loaded libraries, especially APK libraries
    // Returns 0 on success, negative value on error
    private native int nativeLoadLibrary(ClassLoader classLoader, String libPath);
    

    @Override
    public void onAttachedToEngine(@NonNull FlutterPluginBinding flutterPluginBinding) {
        applicationContext = flutterPluginBinding.getApplicationContext();
        channel = new MethodChannel(flutterPluginBinding.getBinaryMessenger(), "agora_rtc_ng");
        channel.setMethodCallHandler(this);
        flutterPluginBindingRef = new WeakReference<>(flutterPluginBinding);
        videoViewController = new VideoViewController(
                flutterPluginBinding.getTextureRegistry(),
                flutterPluginBinding.getBinaryMessenger());

        flutterPluginBinding.getPlatformViewRegistry().registerViewFactory(
                "AgoraTextureView",
                new AgoraPlatformViewFactory(
                        "AgoraTextureView",
                        flutterPluginBinding.getBinaryMessenger(),
                        new AgoraPlatformViewFactory.PlatformViewProviderTextureView(),
                        this.videoViewController));

        flutterPluginBinding.getPlatformViewRegistry().registerViewFactory(
                "AgoraSurfaceView",
                new AgoraPlatformViewFactory(
                        "AgoraSurfaceView",
                        flutterPluginBinding.getBinaryMessenger(),
                        new AgoraPlatformViewFactory.PlatformViewProviderSurfaceView(),
                        this.videoViewController));
    }

    @Override
    public void onDetachedFromEngine(@NonNull FlutterPluginBinding binding) {
        applicationContext = null;
        channel.setMethodCallHandler(null);
        videoViewController.dispose();
    }

    @Override
    public void onMethodCall(@NonNull MethodCall call, @NonNull MethodChannel.Result result) {
        if ("getAssetAbsolutePath".equals(call.method)) {
            getAssetAbsolutePath(call, result);
        } else if ("androidInit".equals(call.method)) {
            androidInit(result);
        } else if ("setAgoraLibPath".equals(call.method)) {
            setAgoraLibPath(call, result);
        } else if ("downloadAgoraLibs".equals(call.method)) {
            downloadAgoraLibs(call, result);
        } else {
            result.notImplemented();
        }
    }

    private void androidInit(MethodChannel.Result result) {
        try {
            // Based on Agora's LoadUtils: https://github.com/AgoraIO-Community/Agora-Dynamic-Loading-Sample-App-Android
            // The key is to copy libraries to jniLibs directory and inject the path into classloader
            if (isLibPathSet && currentLibPath != null) {
                // Step 1: Copy libraries to jniLibs directory (following LoadUtils.addToSearchPath)
                String loadPath = addToSearchPath(currentLibPath);
                if (loadPath == null) {
                    result.error("LIB_LOAD_ERROR", 
                        "Failed to copy libraries to jniLibs and inject into classloader.", null);
                    return;
                }
                android.util.Log.d("AgoraRtcNgPlugin", 
                    "Libraries copied to jniLibs and injected into classloader: " + loadPath);
                
                // Step 2: Set the Agora library path (for SDK internal use)
                try {
                    nativeSetAgoraLibPath(currentLibPath);
                    android.util.Log.d("AgoraRtcNgPlugin", 
                        "Set Agora library path to: " + currentLibPath);
                } catch (Exception e) {
                    android.util.Log.w("AgoraRtcNgPlugin", 
                        "setAgoraLibPath not available (this is OK): " + e.getMessage());
                }
                
                // Step 3: Load libraries using System.loadLibrary() after path injection
                // After path injection, System.loadLibrary() will find libraries in the injected path
                // and load them in the classloader namespace (same namespace as libAgoraRtcWrapper.so from APK)
                // This ensures all libraries are in the same namespace and can see each other's symbols
                // Using System.loadLibrary() ensures all libraries are loaded in the classloader namespace
                // with the same caller context, making symbols visible to each other
                boolean rtcSdkLoaded = false;
                
                for (String libName : AGORA_CORE_LIBS) {
                    File libFile = new File(loadPath, libName);
                    if (!libFile.exists()) {
                        android.util.Log.w("AgoraRtcNgPlugin", 
                            "Library file not found: " + libFile.getAbsolutePath());
                        continue;
                    }
                    
                    // Validate it's a valid ELF file before loading
                    if (!isValidElfFile(libFile)) {
                        android.util.Log.e("AgoraRtcNgPlugin", 
                            "File " + libName + " is not a valid ELF file. It may be corrupted or an HTML error page.");
                        continue;
                    }
                    
                    try {
                        String absolutePath = libFile.getAbsolutePath();
                        
                        if (libName.equals("libagora-rtc-sdk.so")) {
                            // For libagora-rtc-sdk.so, use nativeLoadLibrary with RTLD_GLOBAL
                            // This ensures its symbols are globally visible to AgoraRtcWrapper
                            // Even though nativeLoadLibrary uses dlopen (default namespace),
                            // the RTLD_GLOBAL flag makes symbols visible across namespaces
                            int loadResult = nativeLoadLibrary(applicationContext.getClassLoader(), absolutePath);
                            if (loadResult != 0) {
                                throw new UnsatisfiedLinkError("nativeLoadLibrary returned error code: " + loadResult);
                            }
                            rtcSdkLoaded = true;
                            android.util.Log.d("AgoraRtcNgPlugin", 
                                "Loaded libagora-rtc-sdk.so with RTLD_GLOBAL: " + absolutePath);
                        } else {
                            // For dependencies, use System.loadLibrary() after path injection
                            // This ensures they're loaded in the classloader namespace (same as libAgoraRtcWrapper.so)
                            // Extract library name without "lib" prefix and ".so" suffix
                            String libraryName = libName;
                            if (libraryName.startsWith("lib") && libraryName.endsWith(".so")) {
                                libraryName = libraryName.substring(3, libraryName.length() - 3);
                            }
                            System.loadLibrary(libraryName);
                            android.util.Log.d("AgoraRtcNgPlugin", 
                                "Loaded library (System.loadLibrary from injected path): " + libraryName);
                        }
                    } catch (UnsatisfiedLinkError e) {
                        android.util.Log.e("AgoraRtcNgPlugin", 
                            "Failed to load " + libName + ": " + e.getMessage());
                        
                        // If the main SDK fails to load, we can't continue
                        if (libName.equals("libagora-rtc-sdk.so")) {
                            result.error("LIB_LOAD_ERROR", 
                                "Failed to load libagora-rtc-sdk.so: " + e.getMessage() + 
                                ". This is required for all extension libraries.", null);
                            return;
                        }
                        // Continue loading other libraries even if one fails (except for rtc-sdk)
                    } catch (Exception e) {
                        android.util.Log.e("AgoraRtcNgPlugin", 
                            "Exception loading " + libName + ": " + e.getMessage());
                        
                        // If the main SDK fails to load, we can't continue
                        if (libName.equals("libagora-rtc-sdk.so")) {
                            result.error("LIB_LOAD_ERROR", 
                                "Exception loading libagora-rtc-sdk.so: " + e.getMessage(), null);
                            return;
                        }
                    }
                }
                
                // Verify that the main SDK loaded before proceeding
                if (!rtcSdkLoaded) {
                    result.error("LIB_LOAD_ERROR", 
                        "libagora-rtc-sdk.so failed to load. Cannot proceed with initialization.", null);
                    return;
                }
                
                // Step 4: Load AgoraRtcWrapper after all Agora core libraries are loaded
                // This ensures symbols from libagora-rtc-sdk.so are available when AgoraRtcWrapper loads
                // AgoraRtcWrapper is required by the Dart FFI code (native_iris_api_engine_binding_delegate.dart)
                try {
                    System.loadLibrary("AgoraRtcWrapper");
                    android.util.Log.d("AgoraRtcNgPlugin", 
                        "Loaded AgoraRtcWrapper after Agora core libraries");
                } catch (UnsatisfiedLinkError e) {
                    android.util.Log.e("AgoraRtcNgPlugin", 
                        "Failed to load AgoraRtcWrapper: " + e.getMessage());
                    result.error("LIB_LOAD_ERROR", 
                        "Failed to load AgoraRtcWrapper: " + e.getMessage() + 
                        ". This usually means libagora-rtc-sdk.so symbols are not visible.", null);
                    return;
                } catch (Exception e) {
                    android.util.Log.e("AgoraRtcNgPlugin", 
                        "Exception loading AgoraRtcWrapper: " + e.getMessage());
                    result.error("LIB_LOAD_ERROR", 
                        "Exception loading AgoraRtcWrapper: " + e.getMessage(), null);
                    return;
                }
                
                result.success(true);
            } else {
                // Backward compatibility: try loading AgoraRtcWrapper without dynamic loading
                // Libraries should be in default path (APK jniLibs)
                try {
                    System.loadLibrary("AgoraRtcWrapper");
                    result.success(true);
                } catch (UnsatisfiedLinkError e) {
                    result.error("LIB_LOAD_ERROR", 
                        "Library path not set. Please call setAgoraLibPath first, or ensure libraries are in default path. Error: " + e.getMessage(), 
                        null);
                }
            }
        } catch (UnsatisfiedLinkError e) {
            result.error("LIB_LOAD_ERROR", e.getMessage(), null);
        } catch (Exception e) {
            result.error("INIT_ERROR", e.getMessage(), null);
        }
    }

    private void setAgoraLibPath(MethodCall call, MethodChannel.Result result) {
        final String libPath = call.arguments();
        if (libPath == null || libPath.isEmpty()) {
            result.error("INVALID_PATH", "Library path cannot be null or empty", null);
            return;
        }

        try {
            File pathDir = new File(libPath);
            if (!pathDir.exists() || !pathDir.isDirectory()) {
                result.error("INVALID_PATH", "Library path does not exist or is not a directory: " + libPath, null);
                return;
            }

            // Call native method to set the library path
            int nativeResult = nativeSetAgoraLibPath(libPath);
            if (nativeResult == 0) {
                currentLibPath = libPath;
                isLibPathSet = true;
                result.success(true);
            } else {
                result.error("SET_PATH_ERROR", "Failed to set library path, native error code: " + nativeResult, null);
            }
        } catch (UnsatisfiedLinkError e) {
            result.error("SET_PATH_ERROR", "Native library not loaded: " + e.getMessage(), null);
        } catch (Exception e) {
            result.error("SET_PATH_ERROR", e.getMessage(), null);
        }
    }

    private void downloadAgoraLibs(MethodCall call, MethodChannel.Result result) {
        if (applicationContext == null) {
            result.error("CONTEXT_NULL", "Application context is null", null);
            return;
        }

        final String baseUrl = call.argument("baseUrl");
        if (baseUrl == null || baseUrl.isEmpty()) {
            result.error("INVALID_URL", "Base URL cannot be null or empty", null);
            return;
        }

        // Run download in background thread
        new Thread(() -> {
            try {
                // Get the device architecture (arm64-v8a or armeabi-v7a)
                String arch = getArchitecture();
                File libDir = getLibDirectory();
                if (!libDir.exists()) {
                    libDir.mkdirs();
                }
                
                // Create architecture-specific subdirectory for downloaded libraries
                // This matches the structure expected by addToSearchPath()
                File archLibDir = new File(libDir, arch);
                if (!archLibDir.exists()) {
                    archLibDir.mkdirs();
                }

                boolean allDownloaded = true;
                for (String libName : AGORA_CORE_LIBS) {
                    // Download from architecture-specific URL path
                    // Expected URL structure: baseUrl/arm64-v8a/libagora-rtc-sdk.so or baseUrl/armeabi-v7a/libagora-rtc-sdk.so
                    // This matches the structure in agora_libso folder: agora_libso/<abi>/<libname>.so
                    String url = baseUrl + "/" + arch + "/" + libName;
                    File libFile = new File(archLibDir, libName);

                    if (!libFile.exists()) {
                        try {
                            downloadFile(url, libFile);
                        } catch (Exception e) {
                            // Log error but continue with other libraries
                            android.util.Log.e("AgoraRtcNgPlugin", 
                                "Failed to download " + libName + " for " + arch + ": " + e.getMessage());
                            allDownloaded = false;
                        }
                    }
                }

                if (allDownloaded || archLibDir.listFiles() != null && archLibDir.listFiles().length > 0) {
                    // Return the path to the downloaded libraries (architecture-specific directory)
                    result.success(archLibDir.getAbsolutePath());
                } else {
                    result.error("DOWNLOAD_ERROR", "Failed to download required libraries for " + arch, null);
                }
            } catch (Exception e) {
                result.error("DOWNLOAD_ERROR", e.getMessage(), null);
            }
        }).start();
    }

    private String getArchitecture() {
        // This project supports arm64-v8a and armeabi-v7a architectures
        // Prefer arm64-v8a if available, fall back to armeabi-v7a
        String[] supportedAbis = Build.SUPPORTED_ABIS;
        
        // Check for arm64-v8a first (preferred)
        for (String abi : supportedAbis) {
            if (abi.equals("arm64-v8a")) {
                android.util.Log.d("AgoraRtcNgPlugin", 
                    "Selected architecture: arm64-v8a");
                return "arm64-v8a";
            }
        }
        
        // Fall back to armeabi-v7a
        for (String abi : supportedAbis) {
            if (abi.equals("armeabi-v7a")) {
                android.util.Log.d("AgoraRtcNgPlugin", 
                    "Selected architecture: armeabi-v7a");
                return "armeabi-v7a";
            }
        }
        
        // If neither is found, use the first supported ABI and log a warning
        String selectedAbi = supportedAbis.length > 0 ? supportedAbis[0] : "arm64-v8a";
        android.util.Log.w("AgoraRtcNgPlugin", 
            "Device does not support arm64-v8a or armeabi-v7a. " +
            "Supported ABIs: " + java.util.Arrays.toString(supportedAbis) + 
            ". Using: " + selectedAbi);
        
        return selectedAbi;
    }

    private File getLibDirectory() {
        if (applicationContext == null) return null;
        return new File(applicationContext.getFilesDir(), LIB_DIR_NAME);
    }

    /**
     * Copies libraries to jniLibs directory and injects the path into classloader.
     * This follows LoadUtils.addToSearchPath() exactly from:
     * https://github.com/AgoraIO-Community/Agora-Dynamic-Loading-Sample-App-Android
     * 
     * @param srcPath The source path containing the native libraries (may have ABI subdirectories or be flat)
     * @return The injected path if successful, null otherwise
     */
    private String addToSearchPath(String srcPath) {
        if (applicationContext == null || srcPath == null || srcPath.isEmpty()) {
            return null;
        }
        
        String loadPath = null;
        File des = applicationContext.getDir("jniLibs", Context.MODE_PRIVATE);
        File srcDir = new File(srcPath);
        
        if (Build.VERSION.SDK_INT >= 21) {
            String[] abis = Build.SUPPORTED_ABIS;
            for (String abi : abis) {
                File desAbi = new File(des, abi);
                File src = new File(srcPath, abi);
                
                // Check if ABI subdirectory exists (LoadUtils format)
                if (src.exists() && src.isDirectory()) {
                    android.util.Log.d("AgoraRtcNgPlugin", 
                        "Copying libraries from " + src.getAbsolutePath() + " to " + desAbi.getAbsolutePath());
                    copyToDes(src, desAbi);
                    loadPath = desAbi.getAbsolutePath();
                    android.util.Log.d("AgoraRtcNgPlugin", 
                        "Libraries copied to: " + loadPath + " (will be injected into classloader namespace)");
                    break;
                }
                // If no ABI subdirectory, check if libraries are directly in srcPath (flat structure)
                else if (srcDir.exists() && srcDir.isDirectory()) {
                    // Check if there are any .so files in the source directory
                    String[] files = srcDir.list();
                    boolean hasSoFiles = false;
                    if (files != null) {
                        for (String file : files) {
                            if (file.endsWith(".so")) {
                                hasSoFiles = true;
                                break;
                            }
                        }
                    }
                    
                    if (hasSoFiles) {
                        // Copy .so files directly to ABI subdirectory
                        if (!desAbi.exists()) {
                            desAbi.mkdirs();
                        }
                        for (String file : files) {
                            if (file.endsWith(".so")) {
                                File srcFile = new File(srcDir, file);
                                File destFile = new File(desAbi, file);
                                copyToDes(srcFile, destFile);
                            }
                        }
                        loadPath = desAbi.getAbsolutePath();
                        break;
                    }
                }
            }
        } else {
            // API < 21: Use CPU_ABI, with fallback to CPU_ABI2 or armeabi-v7a
            String abi = Build.CPU_ABI;
            File desAbi = new File(des, abi);
            File src = new File(srcPath, abi);
            
            // Try primary ABI
            if (src.exists() && src.isDirectory()) {
                copyToDes(src, desAbi);
                loadPath = desAbi.getAbsolutePath();
            } 
            // Try CPU_ABI2 if primary doesn't exist
            else if (Build.CPU_ABI2 != null) {
                src = new File(srcPath, Build.CPU_ABI2);
                if (src.exists() && src.isDirectory()) {
                    copyToDes(src, desAbi);
                    loadPath = desAbi.getAbsolutePath();
                }
                // Try armeabi-v7a as fallback
                else {
                    src = new File(srcPath, "armeabi-v7a");
                    if (src.exists() && src.isDirectory()) {
                        copyToDes(src, desAbi);
                        loadPath = desAbi.getAbsolutePath();
                    }
                }
            }
            // Check if flat structure (libraries directly in srcPath)
            else if (!src.exists() && srcDir.exists() && srcDir.isDirectory()) {
                String[] files = srcDir.list();
                if (files != null) {
                    boolean hasSoFiles = false;
                    for (String file : files) {
                        if (file.endsWith(".so")) {
                            hasSoFiles = true;
                            break;
                        }
                    }
                    if (hasSoFiles) {
                        if (!desAbi.exists()) {
                            desAbi.mkdirs();
                        }
                        for (String file : files) {
                            if (file.endsWith(".so")) {
                                File srcFile = new File(srcDir, file);
                                File destFile = new File(desAbi, file);
                                copyToDes(srcFile, destFile);
                            }
                        }
                        loadPath = desAbi.getAbsolutePath();
                    }
                }
            }
        }
        
        if (loadPath != null) {
            android.util.Log.d("AgoraRtcNgPlugin", 
                "Injecting native library path into classloader: " + loadPath);
            String injectedPath = initNativeDirectory(loadPath);
            if (injectedPath != null) {
                android.util.Log.d("AgoraRtcNgPlugin", 
                    "Successfully injected path into classloader. Libraries in this path will be accessible via System.loadLibrary()");
            } else {
                android.util.Log.w("AgoraRtcNgPlugin", 
                    "Failed to inject path into classloader. System.loadLibrary() may not find libraries.");
            }
            return injectedPath;
        }
        return null;
    }
    
    /**
     * Copies files from source to destination (following LoadUtils.copyToDes).
     */
    private void copyToDes(File src, File dest) {
        if (src.isDirectory()) {
            if (!dest.exists()) {
                dest.mkdirs();
            }
            
            String[] files = src.list();
            if (files != null) {
                for (String file : files) {
                    File srcFile = new File(src, file);
                    File destFile = new File(dest, file);
                    copyToDes(srcFile, destFile);
                }
            }
        } else {
            InputStream in = null;
            OutputStream out = null;
            try {
                in = new FileInputStream(src);
                out = new FileOutputStream(dest);
                
                byte[] buffer = new byte[1024];
                int length;
                
                while ((length = in.read(buffer)) > 0) {
                    out.write(buffer, 0, length);
                }
                out.flush();
            } catch (IOException e) {
                android.util.Log.e("AgoraRtcNgPlugin", "Error copying file: " + e.getMessage());
                e.printStackTrace();
            } finally {
                try {
                    if (in != null) {
                        in.close();
                    }
                    if (out != null) {
                        out.close();
                    }
                } catch (IOException e) {
                    android.util.Log.e("AgoraRtcNgPlugin", "Error closing streams: " + e.getMessage());
                }
            }
        }
    }
    
    /**
     * Initializes native directory by injecting path into classloader (following LoadUtils.initNativeDirectory).
     */
    private String initNativeDirectory(String path) {
        if (path == null || path.isEmpty()) {
            return null;
        }
        
        String retPath = null;
        
        if (hasDexClassLoader()) {
            try {
                retPath = createNewNativeDir(path);
            } catch (Exception e) {
                android.util.Log.e("AgoraRtcNgPlugin", "Error initializing native directory: " + e.getMessage());
                e.printStackTrace();
            }
        }
        
        return retPath;
    }
    
    /**
     * Checks if BaseDexClassLoader is available (following LoadUtils.hasDexClassLoader).
     */
    private boolean hasDexClassLoader() {
        try {
            Class.forName("dalvik.system.BaseDexClassLoader");
        } catch (ClassNotFoundException e) {
            return false;
        }
        return true;
    }
    
    /**
     * Creates new native directory by injecting path into classloader (following LoadUtils.createNewNativeDir).
     */
    private String createNewNativeDir(String path) throws Exception {
        if (applicationContext == null) {
            return null;
        }
        
        String createPath = null;
        
        PathClassLoader pathClassLoader = (PathClassLoader) applicationContext.getClassLoader();
        Field declaredField = Class.forName("dalvik.system.BaseDexClassLoader").getDeclaredField("pathList");
        declaredField.setAccessible(true);
        Object dexPathList = declaredField.get(pathClassLoader);
        
        // API < M & API >= M findLibrary while search diff path, see code at
        // /libcore/dalvik/src/main/java/dalvik/system/DexPathList.java
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            Object nativeLibraryDirectories = dexPathList.getClass().getDeclaredField("nativeLibraryDirectories");
            ((Field) nativeLibraryDirectories).setAccessible(true);
            
            File[] files = (File[]) ((Field) nativeLibraryDirectories).get(dexPathList);
            Object filesss = Array.newInstance(File.class, files.length + 1);
            Array.set(filesss, 0, new File(path));
            for (int i = 1; i < files.length + 1; i++) {
                Array.set(filesss, i, files[i - 1]);
            }
            ((Field) nativeLibraryDirectories).set(dexPathList, filesss);
            
            createPath = path;
        } else {
            Class<?>[] innerClz = Class.forName("dalvik.system.DexPathList").getDeclaredClasses();
            Class<?> eleClz = null;
            for (Class<?> clz : innerClz) {
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O && clz.getSimpleName().equals("Element")) {
                    eleClz = clz;
                    break;
                } else if (clz.getSimpleName().equals("NativeLibraryElement")) {
                    eleClz = clz;
                    break;
                }
            }
            
            Constructor<?> ele;
            if (eleClz != null) {
                Object nativeLibraryPathElements;
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
                    ele = eleClz.getDeclaredConstructor(new Class<?>[]{File.class, Boolean.TYPE, File.class, DexFile.class});
                } else {
                    ele = eleClz.getDeclaredConstructor(new Class<?>[]{File.class});
                }
                
                ele.setAccessible(true);
                
                nativeLibraryPathElements = dexPathList.getClass().getDeclaredField("nativeLibraryPathElements");
                ((Field) nativeLibraryPathElements).setAccessible(true);
                
                Object[] objs = (Object[]) ((Field) nativeLibraryPathElements).get(dexPathList);
                Object ob;
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
                    ob = ele.newInstance(new File(path), true, null, null);
                } else {
                    ob = ele.newInstance(new File(path));
                }
                Object eles = Array.newInstance(eleClz, objs.length + 1);
                Array.set(eles, 0, ob);
                for (int i = 1; i < objs.length + 1; i++) {
                    Array.set(eles, i, objs[i - 1]);
                }
                ((Field) nativeLibraryPathElements).set(dexPathList, eles);
                
                createPath = path;
            }
        }
        
        return createPath;
    }

    /**
     * Copies a file from source to destination.
     */
    private void copyFile(File source, File destination) throws IOException {
        try (java.io.FileInputStream fis = new java.io.FileInputStream(source);
             java.io.FileOutputStream fos = new java.io.FileOutputStream(destination)) {
            byte[] buffer = new byte[8192];
            int bytesRead;
            while ((bytesRead = fis.read(buffer)) != -1) {
                fos.write(buffer, 0, bytesRead);
            }
        }
    }

    /**
     * Validates that a file is a valid ELF binary by checking the ELF magic number.
     * ELF files start with bytes: 0x7F 0x45 0x4C 0x46 ("\x7FELF")
     */
    private boolean isValidElfFile(File file) {
        if (file == null || !file.exists() || file.length() < 4) {
            return false;
        }
        
        try (java.io.FileInputStream fis = new java.io.FileInputStream(file)) {
            byte[] header = new byte[4];
            int bytesRead = fis.read(header);
            if (bytesRead != 4) {
                return false;
            }
            
            // Check ELF magic: 0x7F 0x45 0x4C 0x46 ("\x7FELF")
            return header[0] == 0x7F && header[1] == 0x45 && header[2] == 0x4C && header[3] == 0x46;
        } catch (IOException e) {
            android.util.Log.e("AgoraRtcNgPlugin", "Error validating ELF file: " + e.getMessage());
            return false;
        }
    }

    private void downloadFile(String urlString, File destination) throws IOException {
        URL url = new URL(urlString);
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        connection.setConnectTimeout(10000); // 10 seconds
        connection.setReadTimeout(30000); // 30 seconds
        connection.connect();

        int responseCode = connection.getResponseCode();
        if (responseCode != HttpURLConnection.HTTP_OK) {
            // Try to read error message
            String errorMessage = "Server returned HTTP " + responseCode;
            try (InputStream errorStream = connection.getErrorStream()) {
                if (errorStream != null) {
                    byte[] errorBytes = new byte[1024];
                    int read = errorStream.read(errorBytes);
                    if (read > 0) {
                        errorMessage += ": " + new String(errorBytes, 0, read);
                    }
                }
            }
            throw new IOException(errorMessage + " for " + urlString);
        }

        // Check content type
        String contentType = connection.getContentType();
        if (contentType != null && contentType.contains("text/html")) {
            throw new IOException("Server returned HTML instead of binary file. URL may be incorrect: " + urlString);
        }

        try (InputStream input = connection.getInputStream();
             FileOutputStream output = new FileOutputStream(destination)) {
            byte[] buffer = new byte[4096];
            int bytesRead;
            long totalBytes = 0;
            byte[] header = new byte[4];
            boolean headerRead = false;
            
            while ((bytesRead = input.read(buffer)) != -1) {
                // Check first 4 bytes for ELF magic number (0x7F "ELF")
                if (!headerRead && totalBytes + bytesRead >= 4) {
                    if (totalBytes == 0) {
                        // Read header from first chunk
                        System.arraycopy(buffer, 0, header, 0, 4);
                    } else {
                        // Header was split across chunks, read from file
                        try (java.io.FileInputStream fis = new java.io.FileInputStream(destination)) {
                            fis.read(header);
                        }
                    }
                    headerRead = true;
                    
                    // Check ELF magic: 0x7F 0x45 0x4C 0x46 ("\x7FELF")
                    if (header[0] != 0x7F || header[1] != 0x45 || header[2] != 0x4C || header[3] != 0x46) {
                        // Check if it's HTML (starts with "<!DO" or "<htm")
                        if ((header[0] == 0x3C && header[1] == 0x21) || // "<!"
                            (header[0] == 0x3C && header[1] == 0x68 && header[2] == 0x74 && header[3] == 0x6D)) { // "<htm"
                            destination.delete(); // Delete invalid file
                            throw new IOException("Downloaded file is HTML, not a valid .so file. URL may be incorrect: " + urlString);
                        }
                        // Not ELF, but might be valid - log warning but continue
                        android.util.Log.w("AgoraRtcNgPlugin", 
                            "Downloaded file does not appear to be a valid ELF file. First bytes: " + 
                            String.format("%02X %02X %02X %02X", header[0], header[1], header[2], header[3]));
                    }
                }
                
                output.write(buffer, 0, bytesRead);
                totalBytes += bytesRead;
            }
            
            if (totalBytes < 1024) {
                // File is too small to be a valid .so file
                destination.delete();
                throw new IOException("Downloaded file is too small (" + totalBytes + " bytes) to be a valid .so file");
            }
            
            android.util.Log.d("AgoraRtcNgPlugin", 
                "Downloaded " + destination.getName() + " (" + totalBytes + " bytes)");
        } finally {
            connection.disconnect();
        }
    }

    private void getAssetAbsolutePath(MethodCall call, MethodChannel.Result result) {
        final String path = call.arguments();

        if (path != null) {
            if (this.flutterPluginBindingRef.get() != null
            ) {
                final String assetKey = this.flutterPluginBindingRef.get()
                        .getFlutterAssets()
                        .getAssetFilePathByName(path);
                try {
                    this.flutterPluginBindingRef.get().getApplicationContext()
                            .getAssets()
                            .openFd(assetKey)
                            .close();
                    result.success("/assets/" + assetKey);
                    return;
                } catch (IOException e) {
                    result.error(e.getClass().getSimpleName(), e.getMessage(), e.getCause());
                    return;
                }
            }
        }
        result.error("IllegalArgumentException", "The parameter should not be null", null);
    }

    static {
        // Load the native library that contains the JNI functions
        // This library is built from CMakeLists.txt and contains agora_lib_path_jni.cc
        try {
            System.loadLibrary("iris_rendering_android");
        } catch (UnsatisfiedLinkError e) {
            android.util.Log.e("AgoraRtcNgPlugin", 
                "Failed to load iris_rendering_android library: " + e.getMessage());
        }
    }
}
