package com.adskipper.core.detect

/** JNI bridge to the native ncnn YOLO detector (libyolo_jni). */
internal object YoloNative {
    init {
        System.loadLibrary("yolo_jni")
    }

    /** Loads the ncnn param+bin, builds pipelines on GPU (Vulkan) or CPU.
     *  Returns the backend name ("gpu"/"cpu") or "error". */
    external fun nativeInit(paramPath: String, binPath: String): String

    /** Runs inference on a 640x640 ARGB_8888 (letterboxed) bitmap.
     *  Returns out0 as float[5*8400] (xywh + class score, 640px coords). */
    external fun nativeInfer(bitmap: android.graphics.Bitmap): FloatArray?

    external fun nativeRelease()
}
