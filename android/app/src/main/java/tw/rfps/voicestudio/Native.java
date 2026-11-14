package tw.rfps.voicestudio;

/** JNI entry points implemented in cpp/vs_jni.cpp (Qwen3-TTS via qwentts.cpp, MP3 via shine). */
public final class Native {
    static { System.loadLibrary("voicestudio"); }
    private Native() {}

    /** Load the best CPU backend for this phone from the app's native lib folder; call once before init(). Returns its file name ("" = failed). */
    public static native String setup(String libDir);
    /** Load the Vulkan backend (experimental) and list GPUs as "name\tdescription\tMiB". */
    public static native String[] gpus();
    /** Load a talker GGUF + codec GGUF on device ("CPU" or a name from gpus()) with that many CPU threads (0 = default).
     *  Returns a handle (0 on failure, see {@link #lastError()}). */
    public static native long init(String talkerPath, String codecPath, String device, int threads);
    public static native void free(long handle);
    public static native String lastError();
    public static native String recentLog();
    public static native String modelType(long handle);
    public static native String[] speakers(long handle);
    /** Ask a running synth() to stop (checked about every 83 ms). */
    public static native void cancel();
    /** Mono float PCM at 24 kHz, or null on failure. seed -1 = random. temperature/topP <= 0 keep the defaults. */
    public static native float[] synth(long handle, String text, String lang, String speaker, String instruct,
                                       long seed, float temperature, float topP);
    /** 16-bit mono PCM to MP3 bytes. */
    public static native byte[] mp3(short[] pcm, int sampleRate, int kbps);
}
