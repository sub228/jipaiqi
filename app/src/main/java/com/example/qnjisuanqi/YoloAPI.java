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
 * card art.  Labels are 0..53 mapped in {@code Card.labelToRank()}.
 */
public class YoloAPI {

    /** NCNN detection result — mirrors the original {@code YoloAPI$Obj}. */
    public static class Obj {
        /** Detection class id 0..53. */
        public int label;
        /** Human-readable label (populated by the native layer, e.g. "BJ" "RJ" "3" "A"...). */
        public String labelName;
        /** Box centre (not top-left!) in **pixels** of the *input* bitmap. */
        public float x;
        public float y;
        /** Box width / height in pixels of the *input* bitmap. */
        public float w;
        public float h;
        /** Confidence 0..1. */
        public float prob;

        public Obj() {}

        public int left()   { return Math.round(x - w / 2f); }
        public int top()    { return Math.round(y - h / 2f); }
        public int right()  { return Math.round(x + w / 2f); }
        public int bottom() { return Math.round(y + h / 2f); }
    }

    public native Obj[] Detect(Bitmap bitmap, boolean z);
    public native boolean Init();

    static {
        System.loadLibrary("yolov8ncnn");
    }
}
