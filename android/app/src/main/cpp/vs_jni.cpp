// 語音工作室 — JNI bridge: Qwen3-TTS (qwentts.cpp) + MP3 encoder (shine)
#include <jni.h>
#include <atomic>
#include <cstring>
#include <mutex>
#include <string>
#include <vector>
#include <deque>

#include "qwen.h"

extern "C" {
#include "layer3.h"
}

#ifdef __ANDROID__
#include <android/log.h>
#define VS_LOG(...) __android_log_print(ANDROID_LOG_INFO, "VoiceStudio", __VA_ARGS__)
#else
#include <cstdio>
#define VS_LOG(...) (fprintf(stderr, __VA_ARGS__), fputc('\n', stderr))
#endif

static std::atomic<bool> g_cancel{false};
static std::mutex        g_log_mu;
static std::deque<std::string> g_log;

static void log_cb(enum qt_log_level level, const char * msg, void *) {
    if (level < QT_LOG_INFO) return;
    VS_LOG("%s", msg);
    std::lock_guard<std::mutex> lk(g_log_mu);
    g_log.emplace_back(msg);
    while (g_log.size() > 40) g_log.pop_front();
}

static bool cancel_cb(void *) { return g_cancel.load(); }

static std::string jstr(JNIEnv * env, jstring s) {
    if (!s) return std::string();
    const char * c = env->GetStringUTFChars(s, nullptr);
    std::string r(c ? c : "");
    if (c) env->ReleaseStringUTFChars(s, c);
    return r;
}

extern "C" {

JNIEXPORT jlong JNICALL Java_tw_rfps_voicestudio_Native_init(JNIEnv * env, jclass, jstring talker, jstring codec) {
    qt_log_set(log_cb, nullptr);
    std::string t = jstr(env, talker), c = jstr(env, codec);
    struct qt_init_params p;
    qt_init_default_params(&p);
    p.talker_path = t.c_str();
    p.codec_path  = c.c_str();
    p.device      = "CPU";
    struct qt_context * q = qt_init(&p);
    return (jlong) (intptr_t) q;
}

JNIEXPORT void JNICALL Java_tw_rfps_voicestudio_Native_free(JNIEnv *, jclass, jlong h) {
    qt_free((struct qt_context *) (intptr_t) h);
}

JNIEXPORT jstring JNICALL Java_tw_rfps_voicestudio_Native_lastError(JNIEnv * env, jclass) {
    const char * e = qt_last_error();
    return env->NewStringUTF(e ? e : "");
}

JNIEXPORT jstring JNICALL Java_tw_rfps_voicestudio_Native_recentLog(JNIEnv * env, jclass) {
    std::lock_guard<std::mutex> lk(g_log_mu);
    std::string all;
    for (auto & s : g_log) { all += s; all += '\n'; }
    return env->NewStringUTF(all.c_str());
}

JNIEXPORT jstring JNICALL Java_tw_rfps_voicestudio_Native_modelType(JNIEnv * env, jclass, jlong h) {
    const char * t = qt_model_type((struct qt_context *) (intptr_t) h);
    return env->NewStringUTF(t ? t : "");
}

JNIEXPORT jobjectArray JNICALL Java_tw_rfps_voicestudio_Native_speakers(JNIEnv * env, jclass, jlong h) {
    struct qt_context * q = (struct qt_context *) (intptr_t) h;
    int n = q ? qt_n_speakers(q) : 0;
    jobjectArray arr = env->NewObjectArray(n, env->FindClass("java/lang/String"), nullptr);
    for (int i = 0; i < n; i++) env->SetObjectArrayElement(arr, i, env->NewStringUTF(qt_speaker_name(q, i)));
    return arr;
}

JNIEXPORT void JNICALL Java_tw_rfps_voicestudio_Native_cancel(JNIEnv *, jclass) { g_cancel = true; }

// Returns mono float PCM at 24 kHz, or null on failure (see lastError).
JNIEXPORT jfloatArray JNICALL Java_tw_rfps_voicestudio_Native_synth(JNIEnv * env, jclass, jlong h, jstring text, jstring lang,
                                                                     jstring speaker, jstring instruct, jlong seed,
                                                                     jfloat temperature, jfloat topP) {
    struct qt_context * q = (struct qt_context *) (intptr_t) h;
    if (!q) return nullptr;
    g_cancel = false;
    std::string t = jstr(env, text), l = jstr(env, lang), s = jstr(env, speaker), ins = jstr(env, instruct);
    struct qt_tts_params p;
    qt_tts_default_params(&p);
    p.text = t.c_str();
    p.lang = l.empty() ? nullptr : l.c_str();
    p.speaker = s.empty() ? nullptr : s.c_str();
    p.instruct = ins.empty() ? nullptr : ins.c_str();
    p.seed = (int64_t) seed;
    if (temperature > 0) p.temperature = temperature;
    if (topP > 0) p.top_p = topP;
    p.cancel = cancel_cb;
    p.cancel_user_data = nullptr;
    struct qt_audio out = {};
    enum qt_status rc = qt_synthesize(q, &p, &out);
    if (rc != QT_STATUS_OK || out.n_samples <= 0 || !out.samples) {
        qt_audio_free(&out);
        return nullptr;
    }
    jfloatArray arr = env->NewFloatArray(out.n_samples);
    env->SetFloatArrayRegion(arr, 0, out.n_samples, out.samples);
    qt_audio_free(&out);
    return arr;
}

// 16-bit mono PCM -> MP3 (MPEG-2 layer III at 16/22.05/24 kHz, MPEG-1 at 32/44.1/48 kHz)
JNIEXPORT jbyteArray JNICALL Java_tw_rfps_voicestudio_Native_mp3(JNIEnv * env, jclass, jshortArray pcm, jint rate, jint kbps) {
    if (shine_check_config(rate, kbps) < 0) return nullptr;
    shine_config_t cfg;
    shine_set_config_mpeg_defaults(&cfg.mpeg);
    cfg.wave.channels   = PCM_MONO;
    cfg.wave.samplerate = rate;
    cfg.mpeg.mode       = MONO;
    cfg.mpeg.bitr       = kbps;
    shine_t sh = shine_initialise(&cfg);
    if (!sh) return nullptr;
    const int per = shine_samples_per_pass(sh);
    jsize n = env->GetArrayLength(pcm);
    std::vector<int16_t> buf((size_t) n);
    env->GetShortArrayRegion(pcm, 0, n, (jshort *) buf.data());
    std::vector<unsigned char> mp3;
    std::vector<int16_t> frame((size_t) per);
    for (jsize i = 0; i < n; i += per) {
        int m = std::min<int>(per, (int) (n - i));
        std::memset(frame.data(), 0, sizeof(int16_t) * (size_t) per);
        std::memcpy(frame.data(), buf.data() + i, sizeof(int16_t) * (size_t) m);
        int written = 0;
        unsigned char * d = shine_encode_buffer_interleaved(sh, frame.data(), &written);
        mp3.insert(mp3.end(), d, d + written);
    }
    int written = 0;
    unsigned char * d = shine_flush(sh, &written);
    mp3.insert(mp3.end(), d, d + written);
    shine_close(sh);
    jbyteArray arr = env->NewByteArray((jsize) mp3.size());
    env->SetByteArrayRegion(arr, 0, (jsize) mp3.size(), (const jbyte *) mp3.data());
    return arr;
}

}  // extern "C"
