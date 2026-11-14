// 語音工作室 — JNI bridge: Qwen3-TTS (qwentts.cpp) + MP3 encoder (shine)
#include <jni.h>
#include <atomic>
#include <cstring>
#include <mutex>
#include <string>
#include <vector>
#include <deque>

#include "qwen.h"
#include "ggml-backend.h"

#include <dirent.h>
#include <dlfcn.h>
#include <cstdlib>

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

static std::mutex  g_be_mu;
static std::string g_lib_dir, g_cpu_variant;
static bool        g_vk_tried = false;

extern "C" {

// Load the best CPU backend module for this phone (libggml-cpu-<variant>.so in the app's lib folder).
// Must run before init(). Returns the variant file name, or "" when none could be loaded.
JNIEXPORT jstring JNICALL Java_tw_rfps_voicestudio_Native_setup(JNIEnv * env, jclass, jstring libDir) {
    std::lock_guard<std::mutex> lk(g_be_mu);
    if (!g_cpu_variant.empty()) return env->NewStringUTF(g_cpu_variant.c_str());
    ggml_log_set([](enum ggml_log_level lv, const char * m, void *) { if (lv >= GGML_LOG_LEVEL_WARN) VS_LOG("%s", m); }, nullptr);
    g_lib_dir = jstr(env, libDir);
    std::string best; int best_score = 0;
    if (DIR * d = opendir(g_lib_dir.c_str())) {
        while (struct dirent * e = readdir(d)) {
            std::string n = e->d_name;
            if (n.rfind("libggml-cpu-", 0) != 0 || n.size() < 4 || n.compare(n.size() - 3, 3, ".so") != 0) continue;
            std::string path = g_lib_dir + "/" + n;
            void * h = dlopen(path.c_str(), RTLD_NOW | RTLD_LOCAL);
            if (!h) { VS_LOG("skip %s: %s", n.c_str(), dlerror()); continue; }
            auto score = (int (*)(void)) dlsym(h, "ggml_backend_score");
            int s = score ? score() : 0;
            VS_LOG("cpu variant %s score %d", n.c_str(), s);
            if (s > best_score) { best_score = s; best = n; }
            dlclose(h);
        }
        closedir(d);
    }
    if (best.empty()) {  // no variants: a plain libggml-cpu.so
        std::string path = g_lib_dir + "/libggml-cpu.so";
        if (ggml_backend_load(path.c_str())) g_cpu_variant = "libggml-cpu.so";
    } else if (ggml_backend_load((g_lib_dir + "/" + best).c_str())) {
        g_cpu_variant = best;
    }
    return env->NewStringUTF(g_cpu_variant.c_str());
}

// Load the Vulkan backend (once) and list the GPUs: "Vulkan0\tdescription" per entry.
JNIEXPORT jobjectArray JNICALL Java_tw_rfps_voicestudio_Native_gpus(JNIEnv * env, jclass) {
    {
        std::lock_guard<std::mutex> lk(g_be_mu);
        if (!g_vk_tried) {
            g_vk_tried = true;
            std::string path = g_lib_dir + "/libggml-vulkan.so";
            if (!ggml_backend_load(path.c_str())) VS_LOG("Vulkan backend not loaded");
        }
    }
    std::vector<std::string> out;
    for (size_t i = 0; i < ggml_backend_dev_count(); i++) {
        ggml_backend_dev_t d = ggml_backend_dev_get(i);
        enum ggml_backend_dev_type t = ggml_backend_dev_type(d);
        if (t == GGML_BACKEND_DEVICE_TYPE_GPU || t == GGML_BACKEND_DEVICE_TYPE_IGPU) {
            size_t fr = 0, tot = 0;
            ggml_backend_dev_memory(d, &fr, &tot);
            out.push_back(std::string(ggml_backend_dev_name(d)) + "\t" + ggml_backend_dev_description(d) + "\t" + std::to_string(tot >> 20));
        }
    }
    jobjectArray arr = env->NewObjectArray((jsize) out.size(), env->FindClass("java/lang/String"), nullptr);
    for (size_t i = 0; i < out.size(); i++) env->SetObjectArrayElement(arr, (jsize) i, env->NewStringUTF(out[i].c_str()));
    return arr;
}

// device: "CPU" or a GPU name from gpus() (e.g. "Vulkan0"); threads: CPU threads, 0 = default (half the cores)
JNIEXPORT jlong JNICALL Java_tw_rfps_voicestudio_Native_init(JNIEnv * env, jclass, jstring talker, jstring codec, jstring device, jint threads) {
    qt_log_set(log_cb, nullptr);
    std::string t = jstr(env, talker), c = jstr(env, codec), dev = jstr(env, device);
    if (dev.empty()) dev = "CPU";
    if (threads > 0) setenv("VS_THREADS", std::to_string(threads).c_str(), 1); else unsetenv("VS_THREADS");
    struct qt_init_params p;
    qt_init_default_params(&p);
    p.talker_path = t.c_str();
    p.codec_path  = c.c_str();
    p.device      = dev.c_str();
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
