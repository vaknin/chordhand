package com.kivan.chordhand.data.listen

/** whisper.cpp through JNI (`src/main/cpp`). One transcription at a time. */
internal object WhisperNative {
    init {
        System.loadLibrary("chordhand_listen")
    }

    interface Listener {
        /** 0..100, on the transcribing thread. */
        fun onProgress(percent: Int)
    }

    /**
     * "startMs\tword" lines as UTF-8, or null if the model fails to load or decoding fails.
     * [pcm] is 16 kHz mono; [prompt] is text the recording likely contains.
     */
    external fun transcribe(modelPath: String, pcm: FloatArray, prompt: String, threads: Int, listener: Listener): ByteArray?
}
