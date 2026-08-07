package com.adskipper.core.vlm

import android.graphics.Bitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import timber.log.Timber

/**
 * JNI wrapper around llama.cpp (mtmd) for local VLM grounding.
 * Singleton per process; inference is serialized via a Mutex.
 */
class VlmEngine {

    enum class State { NOT_LOADED, LOADING, READY, ERROR }

    private val _state = MutableStateFlow(State.NOT_LOADED)
    val state: StateFlow<State> = _state

    val isReady: Boolean get() = _state.value == State.READY

    private val mutex = Mutex()

    suspend fun load(modelPath: String, mmprojPath: String, threads: Int): Boolean =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                _state.value = State.LOADING
                val ok = try {
                    nativeLoadModel(modelPath, mmprojPath, threads)
                } catch (t: Throwable) {
                    Timber.e(t, "nativeLoadModel crashed")
                    false
                }
                _state.value = if (ok) State.READY else State.ERROR
                ok
            }
        }

    suspend fun infer(bitmap: Bitmap, prompt: String, maxTokens: Int = 64): String? =
        withContext(Dispatchers.Default) {
            if (!isReady) return@withContext null
            mutex.withLock {
                try {
                    // JNI expects ARGB_8888 (RGBA byte order).
                    val argb = if (bitmap.config == Bitmap.Config.ARGB_8888) bitmap
                        else bitmap.copy(Bitmap.Config.ARGB_8888, false)
                    if (argb == null) return@withLock null
                    nativeInfer(argb, prompt, maxTokens).takeIf { it.isNotBlank() }
                } catch (t: Throwable) {
                    Timber.e(t, "nativeInfer crashed")
                    null
                }
            }
        }

    suspend fun release() = withContext(Dispatchers.IO) {
        mutex.withLock {
            try {
                nativeRelease()
            } catch (t: Throwable) {
                Timber.w(t, "nativeRelease failed")
            }
            _state.value = State.NOT_LOADED
        }
    }

    private external fun nativeLoadModel(modelPath: String, mmprojPath: String, threads: Int): Boolean
    private external fun nativeInfer(bitmap: Bitmap, prompt: String, maxTokens: Int): String
    private external fun nativeRelease()

    companion object {
        init {
            System.loadLibrary("vlm_jni")
        }
    }
}
