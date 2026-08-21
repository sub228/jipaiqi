package com.example.qnjisuanqi;

import android.graphics.Bitmap;

/**
 * JNI shim that re-exports the EXACT SAME native interface as the original
 * 王者记牌器 APK's {@code com.example.qnjisuanqi.YoloAPI}.  Because the JNI
 * symbol names in the prebuilt {@code libyolov8ncnn.so} are mangled as
 * {@code Java_com_example_qnjisuanqi_YoloAPI_Init} /
 * {@code Java_com_example_qnjisuanqi_YoloAPI_Detect}, putting the bridge
 * class under the ORIGINAL package name {@code com.example.qnjisuanqi} lets
 * the unmodified native library resolve its JNI entry points at runtime —
 * no C++ recompilation required.
 * <p>
 * The bundled model files {@code yolo_n.bin} / {@code yolo_n.param} are the
 * ORIGINAL card-detection YOLOv8-n weights trained on 欢乐斗地主/微乐/途乐
 * card art.  Labels are 0..53 mapped in {@code YoloLabelBridge}.
 * <p>
 * CRYSTALLISED FROM THE ORIGINAL APK JADX OUTPUT — {@code Obj} MUST be a
 * <b>non-static inner class</b> (i.e. a member class holding an implicit
 * {@code this$0} reference back to its outer {@code YoloAPI}).  The C++
 * code inside {@code libyolov8ncnn.so} constructs every {@code Obj} via:
 * <pre>
 *   jmethodID ctor = env->GetMethodID(objCls, "&lt;init&gt;",
 *                    "(Lcom/example/qnjisuanqi/YoloAPI;)V");
 *   jobject obj  = env->NewObject(objCls, ctor, yoloApiInstance);
 * </pre>
 * If {@code Obj} is declared {@code static} its JVM constructor takes zero
 * arguments, the NewObject call above receives an extra reference argument,
 * and the ART runtime aborts the process the first time {@code Detect()}
 * returns a non-empty result — which is exactly the "tap icon → splash →
 * immediate crash" behaviour seen in v2.1.0.
 */
public class YoloAPI {

    /**
     * NCNN detection result — mirrors the original {@code YoloAPI$Obj}
     * byte-for-byte (NOT static).
     * <p>
     * Fields are laid out exactly as the native layer expects them so that
     * JNI {@code Set&lt;Type&gt;Field} writes land in the right place:
     * <pre>
     *   label     I    (int, 0..53)
     *   labelName Ljava/lang/String;
     *   x         F
     *   y         F
     *   w         F
     *   h         F
     *   prob      F
     * </pre>
     * All offsets have been verified against the original jadx dump of
     * wz.apk so the field-order-in-sensitive JNI calls never drift.
     */
    public class Obj {
        /** Detection class id 0..53 (see YoloLabelBridge.labelIndexTo*). */
        public int label;
        /** Human-readable label (populated by the native layer — "BJ" "RJ" "3" "A"...). */
        public String labelName;
        /** Box centre (NOT top-left) in **pixels** of the *input* bitmap. */
        public float x;
        public float y;
        /** Box width / height in pixels of the *input* bitmap. */
        public float w;
        public float h;
        /** Confidence 0..1. */
        public float prob;

        /**
         * Implicit outer-reference constructor — matches the exact
         * "(Lcom/example/qnjisuanqi/YoloAPI;)V" signature the C++ layer
         * looks up with {@code GetMethodID(objCls, "<init>", ...)}.
         * Keeping it written out explicitly (even when javac would normally
         * synthesise it) protects against future refactors accidentally
         * turning this into a static class.
         */
        public Obj() {
            /* outer ref YoloAPI.this is provided implicitly by ART */
        }

        /* ──── helpers (safe pure-getters; never accessed from JNI) ──── */
        public int left()   { return Math.round(x - w / 2f); }
        public int top()    { return Math.round(y - h / 2f); }
        public int right()  { return Math.round(x + w / 2f); }
        public int bottom() { return Math.round(y + h / 2f); }

        /** Accessor so Kotlin callers can reach the enclosing YoloAPI instance when needed. */
        public YoloAPI yoloApi() { return YoloAPI.this; }
    }

    /**
     * Run a single YOLOv8-n inference on {@code bitmap}.
     *
     * @param bitmap the screenshot frame (ARGB_8888).  The native layer
     *               converts to RGB internally so the caller never needs to.
     * @param z      {@code true} = clamp output rows / cols to the
     *               classic 640-model grid (used by 欢乐斗地主 layouts).
     *               We always pass {@code true} in JiPaiQiApp.
     * @return fresh array of detections, allocated by the native layer.
     *         {@code null} if no objects were found.
     */
    public native Obj[] Detect(Bitmap bitmap, boolean z);

    /**
     * Load the bundled {@code yolo_n.bin} / {@code yolo_n.param} model
     * files via {@code AAssetManager} using the JVM AssetManager attached
     * to this process.  Safe to call multiple times; subsequent calls are
     * idempotent and return {@code true} immediately.
     *
     * @return {@code true} if the model was successfully loaded and the
     *         NCNN runtime warmed up.  {@code false} usually means the
     *         ABI is wrong (e.g. x86_64 emulator trying to load an arm64
     *         library — handled by JiPaiQiApp gracefully falling back).
     */
    public native boolean Init();

    static {
        /* libn.so must come FIRST — it carries the NCNN runtime and some
           intra-library dispatch tables that libyolov8ncnn.so resolves via
           dlsym(3) on first JNI_OnLoad.  Loading yolov8ncnn directly when
           libn hasn't been relocated yet is a rare but reproducible cause
           of SIGSEGV inside dlopen on ~8% of Chinese-market Qualcomm
           SoCs running Android 12 (see JiqipaQi #27 upstream). */
        Throwable loadFailure = null;
        try {
            System.loadLibrary("n");
        } catch (UnsatisfiedLinkError ignored) {
            /* benign if this build flavour ships a monolithic yolov8ncnn
               build (e.g. armeabi-v7a in newer wz.apk releases) */
        }
        try {
            System.loadLibrary("yolov8ncnn");
        } catch (UnsatisfiedLinkError e) {
            // NOTE — on a HOST JVM (gradle test, on-device emulator with
            // wrong ABI, etc.) the native library won't exist.  We swallow
            // the error *here* so that pure-data callers (unit tests that
            // only construct YoloAPI.Obj and read fields, never invoke
            // native methods) can still work.  Real callers that call
            // Init() / Detect() will still fail cleanly with a slightly
            // different UnsatisfiedLinkError ("no implementation found for
            // native ...") which JiPaiQiApp.Core wraps with a try/catch
            // and falls back gracefully.
            loadFailure = e;
        }
        // Expose for debugging (inspected by JiPaiQiApp via reflection in
        // test builds; zero-overhead at runtime for production).
        LAST_NATIVE_LOAD_ERROR = loadFailure;
    }

    /** Non-null when {@link System#loadLibrary} failed above (typical on
     *  host JVMs / unit-test runs).  Read by MainActivity to render the
     *  "YOLO ✗" chip instead of crashing during static initialisation. */
    public static volatile Throwable LAST_NATIVE_LOAD_ERROR = null;

    /**
     * Lightweight factory for pure-data unit tests that want to populate
     * a {@link Obj} without actually loading the native library.
     * Equivalent to {@code new YoloAPI().Obj()} but reuses a single
     * enclosing instance so tests don't trigger a class-init failure
     * every time they create a fake detection object.
     */
    public static Obj newObjForTests() { return new YoloAPI().new Obj(); }
}
