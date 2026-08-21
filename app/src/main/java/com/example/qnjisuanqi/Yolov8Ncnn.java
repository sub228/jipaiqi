package com.example.qnjisuanqi;

import android.content.res.AssetManager;

/**
 * Missing original wz.apk bridge that we had **NOT** yet included.
 *
 * In jadx NewFloatingWindowService.java lines 786..792 the NCNN model
 * files are loaded *before* the yolo JNI Init() call via:
 *
 *   Yolov8Ncnn yolov8ncnn = new Yolov8Ncnn();
 *   yolov8ncnn.loadModel(getAssets(), cachedAiModel, cachedCpuOrgpu,
 *                        cachedModelPlatform);
 *   boolean r7 = yoloAPI.Init();
 *
 * libyolov8ncnn.so exports both entry points:
 *   Java_com_example_qnjisuanqi_YoloAPI_Init     (only completes Init after
 *                                                 a prior loadModel() sets the
 *                                                 global Net handle)
 *   Java_com_example_qnjisuanqi_YoloAPI_Detect   (reads that global Net)
 *   Java_com_example_qnjisuanqi_Yolov8Ncnn_loadModel (parses yolo_n.bin/.param
 *                                                     via AAsset)
 *
 * This class must be declared here — mirroring jadx Yolov8Ncnn.java byte for
 * byte — so ART resolves the native symbol on first use.  Without this
 * explicit call, the NCNN runtime never sees a valid model and
 * {@code YoloAPI.Detect()} returns an EMPTY array 100% of the time on real
 * 斗地主 frames, which is exactly the user-observed symptom "three green
 * lights but nothing is ever recognised".
 *
 * Parameters (all literal from the original wz.apk default config):
 *   aiModel  = 0 → yolo_n (default 斗地主 model slot; the only
 *                  yolo_{m,n,s,x}.bin/param bundled in our assets today)
 *   cpuGpu   = 0 → CPU fallback (safest default; GPU paths require extra
 *                  Vulkan libs not in 3.0.1 apktool extracts)
 *   platform = 6 → MODE1 default.  1..7 corresponds to the Mode.java enum's
 *                  post-processed platform id (6 = MODE1).
 *
 * All three values in wz.apk default config are 0/0/6 on first start.
 */
public class Yolov8Ncnn {

    public static final int  AI_MODEL_DEFAULT = 0;
    public static final int  CPU_GPU_DEFAULT  = 0;
    public static final int  PLATFORM_MODE1   = 6;

    public native boolean closeCamera();
    public native boolean loadModel(AssetManager assetManager, int i2, int i3, int i4);
    public native boolean openCamera(int i2);
    public native boolean setOutputWindow(android.view.Surface surface);

    static {
        // Mirror jadx: loadLibrary is invoked ONCE by this class's static
        // initialiser; YoloAPI also does the same but duplicate
        // System.loadLibrary calls are no-ops.
        System.loadLibrary("yolov8ncnn");
    }
}
