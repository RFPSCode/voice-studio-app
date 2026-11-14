package tw.rfps.voicestudio;

import java.io.File;

/** Keeps one Qwen3-TTS model loaded (native memory) and implements Store.Synth. */
public class Engine implements Store.Synth {
    private long handle = 0;
    private String loaded = "";

    public synchronized String loadedModel() { return handle != 0 ? loaded : ""; }

    public synchronized void ensure(String modelId, File talker, File codec) throws Exception {
        if (handle != 0 && loaded.equals(modelId)) return;
        release();
        long h = Native.init(talker.getAbsolutePath(), codec.getAbsolutePath());
        if (h == 0) throw new IllegalStateException("模型載入失敗：" + Native.lastError());
        String type = Native.modelType(h);
        if (!"custom_voice".equals(type)) {
            Native.free(h);
            throw new IllegalStateException("這個模型不是 CustomVoice（" + type + "）");
        }
        handle = h;
        loaded = modelId;
    }

    public synchronized void release() {
        if (handle != 0) Native.free(handle);
        handle = 0;
        loaded = "";
    }

    @Override
    public synchronized float[] synth(String text, String lang, String speaker, String instruct, long seed, float temperature, float topP) throws Exception {
        if (handle == 0) throw new IllegalStateException("模型還沒載入");
        float[] a = Native.synth(handle, text, lang, speaker, instruct, seed, temperature, topP);
        if (a == null) throw new IllegalStateException(Native.lastError());
        return a;
    }
}
