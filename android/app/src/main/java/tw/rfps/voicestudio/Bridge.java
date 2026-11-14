package tw.rfps.voicestudio;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.provider.MediaStore;
import android.webkit.JavascriptInterface;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/** Everything the web UI (assets/ui/index.html) can call, as window.VS.* */
public class Bridge {
    private final MainActivity act;
    final Store store;
    final Models models;
    final Engine engine = new Engine();
    private final ExecutorService jobs = Executors.newSingleThreadExecutor();
    private final AtomicBoolean cancel = new AtomicBoolean(false);
    private volatile String busy = "";
    private final SharedPreferences prefs;
    private final String cpuVariant;
    private String notice = "";
    private JSONArray gpuList = null;

    Bridge(MainActivity act) throws Exception {
        this.act = act;
        store = new Store(new File(act.getFilesDir(), "data"));
        String cat;
        try (InputStream in = act.getAssets().open("models.json")) { cat = new String(Store.readAll(in), StandardCharsets.UTF_8); }
        models = new Models(new File(act.getFilesDir(), "models"), cat);
        prefs = act.getSharedPreferences("vs", 0);
        cpuVariant = Native.setup(act.getApplicationInfo().nativeLibraryDir);
        if (prefs.getBoolean("gpu_pending", false)) {   // the app died while using the GPU last time
            prefs.edit().putBoolean("gpu_pending", false).putString("device", "CPU").apply();
            notice = "上次使用 GPU 時 App 停止運作了，已經自動改回 CPU。";
        }
        try (InputStream in = act.getAssets().open("pron.json")) { Store.pron = new Pron(new String(Store.readAll(in), StandardCharsets.UTF_8)); }
        catch (Exception e) { Store.pron = null; }
    }

    // ------------------------------------------------------------ events to JS
    void emit(JSONObject ev) { act.sendToJs(ev.toString()); }

    void emit(String type, Object... kv) {
        try {
            JSONObject o = new JSONObject().put("type", type);
            for (int i = 0; i + 1 < kv.length; i += 2) o.put(String.valueOf(kv[i]), kv[i + 1]);
            emit(o);
        } catch (Exception ignored) {}
    }

    private interface Job { void run() throws Exception; }

    private void start(String name, Job job) {
        if (!busy.isEmpty()) { emit("error", "msg", "目前正在「" + busy + "」，請等它完成或按停止。"); return; }
        busy = name;
        cancel.set(false);
        act.keepScreenOn(true);
        emit("busy", "name", name);
        jobs.submit(() -> {
            try { job.run(); }
            catch (Throwable e) {
                String m = e.getMessage() != null ? e.getMessage() : e.toString();
                if (!cancel.get()) emit("error", "msg", m);
            } finally {
                busy = "";
                act.keepScreenOn(false);
                emit("idle", "name", name, "cancelled", cancel.get());
            }
        });
    }

    private Store.Progress progress() {
        return new Store.Progress() {
            public void line(String p, String l, String s, JSONObject info) {
                try { emit(new JSONObject().put("type", "line").put("project", p).put("line", l).put("status", s).put("info", info)); } catch (Exception ignored) {}
            }
            public void log(String msg) { emit("log", "msg", msg); }
            public boolean cancelled() { return cancel.get(); }
        };
    }

    private Models.Listener dlListener() {
        return new Models.Listener() {
            public void progress(String model, String file, long got, long total) { emit("download", "model", model, "file", file, "got", got, "total", total); }
            public boolean cancelled() { return cancel.get(); }
        };
    }

    String activeModel() throws Exception {
        String id = prefs.getString("model", "");
        if (id.isEmpty() || !exists(id)) id = models.catalog.getJSONObject(0).getString("id");
        return id;
    }

    private boolean exists(String id) {
        try { models.find(id); return true; } catch (Exception e) { return false; }
    }

    String device() { return prefs.getString("device", "CPU"); }
    int threads() { return prefs.getInt("threads", 0); }

    private void loadEngine() throws Exception { loadEngine(device(), threads()); }

    private void loadEngine(String device, int threads) throws Exception {
        String id = activeModel();
        if (!models.installed(id)) throw new IllegalStateException("還沒下載模型，請到「模型」分頁下載。");
        if (!engine.config().equals(id + "|" + device + "|" + threads)) {
            boolean gpu = !"CPU".equals(device);
            emit("log", "msg", "載入模型中（" + (gpu ? "GPU" : "CPU") + "，第一次約 10–30 秒）…");
            long t0 = System.currentTimeMillis();
            if (gpu) {
                if (gpus().length() == 0) throw new IllegalStateException("這支手機找不到可以用的 GPU（Vulkan），請改用 CPU。");
                prefs.edit().putBoolean("gpu_pending", true).commit();
            }
            try {
                engine.ensure(id, models.talker(id), models.codec(id), device, threads);
            } catch (Exception e) {
                if (gpu) { prefs.edit().putBoolean("gpu_pending", false).commit(); throw new IllegalStateException("GPU 載入失敗，請改用 CPU：" + e.getMessage()); }
                throw e;
            }
            if (gpu) prefs.edit().putBoolean("gpu_pending", false).commit();
            emit("log", "msg", "模型已載入（" + (System.currentTimeMillis() - t0) / 1000 + " 秒）");
        }
    }

    /** GPUs the Vulkan backend can use (loads it the first time). */
    synchronized JSONArray gpus() {
        if (gpuList != null) return gpuList;
        gpuList = new JSONArray();
        try {
            for (String g : Native.gpus()) {
                String[] f = g.split("\t");
                gpuList.put(new JSONObject().put("id", f[0]).put("name", f.length > 1 ? f[1] : f[0]).put("mb", f.length > 2 ? Long.parseLong(f[2]) : 0));
            }
        } catch (Throwable e) { emit("log", "msg", "GPU 偵測失敗：" + e.getMessage()); }
        return gpuList;
    }

    // ------------------------------------------------------------ speed (CPU / GPU)
    @JavascriptInterface
    public String compute() {
        try {
            return new JSONObject().put("device", device()).put("threads", threads()).put("cpu", cpuVariant)
                .put("cores", Runtime.getRuntime().availableProcessors()).put("gpus", prefs.getBoolean("gpu_checked", false) ? gpus() : null)
                .put("results", new JSONArray(prefs.getString("speed", "[]"))).toString();
        } catch (Exception e) { return "{}"; }
    }

    @JavascriptInterface
    public void setCompute(String device, int threads) {
        prefs.edit().putString("device", device == null || device.isEmpty() ? "CPU" : device).putInt("threads", Math.max(0, threads)).apply();
        emit("refresh");
    }

    /** Same sentence on CPU (4, 6, 8 threads) and GPU, each timed after a warm-up; the clips can be heard afterwards. */
    @JavascriptInterface
    public void speedTest() {
        start("速度測試", () -> {
            prefs.edit().putBoolean("gpu_checked", true).apply();
            JSONArray gs = gpus();
            java.util.List<Object[]> cfg = new java.util.ArrayList<>();
            int cores = Runtime.getRuntime().availableProcessors();
            cfg.add(new Object[]{"CPU", 0});
            for (int t : new int[]{6, 8}) if (t <= cores && t != cores / 2) cfg.add(new Object[]{"CPU", t});
            for (int i = 0; i < gs.length(); i++) cfg.add(new Object[]{gs.getJSONObject(i).getString("id"), 0});
            File dir = new File(store.root, "speedtest");
            dir.mkdirs();
            JSONArray res = new JSONArray();
            String text = "小朋友，下課的時候不要在走廊上奔跑，要慢慢走，才不會撞到別人喔！";
            String model = Store.pron != null ? Store.pron.convert(text, null, true).text : text;
            for (int i = 0; i < cfg.size() && !cancel.get(); i++) {
                String dev = (String) cfg.get(i)[0];
                int th = (Integer) cfg.get(i)[1];
                String label = "CPU".equals(dev) ? "CPU " + (th == 0 ? Math.max(1, cores / 2) : th) + " 核心" : "GPU（" + dev + "）";
                emit("log", "msg", "速度測試 " + (i + 1) + "/" + cfg.size() + "：" + label + "…");
                JSONObject r = new JSONObject().put("device", dev).put("threads", th).put("label", label);
                try {
                    loadEngine(dev, th);
                    boolean gpu = !"CPU".equals(dev);
                    if (gpu) prefs.edit().putBoolean("gpu_pending", true).commit();
                    engine.synth("你好。", "Chinese", "vivian", "", 1, 0, 0);   // warm-up
                    long t0 = System.nanoTime();
                    float[] a = engine.synth(model, "Chinese", "vivian", "", 12345, 0, 0);
                    double secs = (System.nanoTime() - t0) / 1e9, dur = a.length / 24000.0;
                    if (gpu) prefs.edit().putBoolean("gpu_pending", false).commit();
                    File f = new File(dir, (gpu ? "gpu" : "cpu" + th) + ".wav");
                    Store.writeWav(f, a);
                    double peak = 0;
                    for (float x : a) peak = Math.max(peak, Math.abs(x));
                    r.put("secs", Math.round(secs * 10) / 10.0).put("dur", Math.round(dur * 100) / 100.0).put("rtf", Math.round(secs / Math.max(0.1, dur) * 100) / 100.0)
                     .put("file", "speedtest/" + f.getName()).put("odd", dur < 2.5 || dur > 12 || peak < 0.01);
                } catch (Throwable e) {
                    prefs.edit().putBoolean("gpu_pending", false).commit();
                    r.put("error", e.getMessage() != null ? e.getMessage() : e.toString());
                }
                res.put(r);
                emit(new JSONObject().put("type", "speed").put("results", res));
            }
            engine.release();
            prefs.edit().putString("speed", res.toString()).apply();
            emit("refresh");
        });
    }

    // ------------------------------------------------------------ queries
    @JavascriptInterface
    public String info() {
        try {
            JSONObject o = new JSONObject();
            o.put("models", models.status());
            o.put("active", activeModel());
            o.put("loaded", engine.loadedModel());
            o.put("mirror", prefs.getString("mirror", ""));
            o.put("notice", notice);
            notice = "";
            o.put("busy", busy);
            JSONArray ps = new JSONArray();
            for (String id : store.projectIds()) {
                try {
                    JSONObject s = store.summary(id, activeModel());
                    JSONObject p = s.getJSONObject("project");
                    ps.put(new JSONObject().put("id", id).put("title", p.optString("title")).put("total", s.getInt("total"))
                        .put("done", s.getInt("done")).put("changed", s.getInt("changed")).put("updated", p.optString("updated")));
                } catch (Exception e) { ps.put(new JSONObject().put("id", id).put("title", id).put("error", e.getMessage())); }
            }
            o.put("projects", ps);
            o.put("device", Build.MANUFACTURER + " " + Build.MODEL + "（Android " + Build.VERSION.RELEASE + "）");
            o.put("version", BuildConfig.VERSION_NAME);
            return o.toString();
        } catch (Exception e) { return "{\"error\":" + JSONObject.quote(String.valueOf(e.getMessage())) + "}"; }
    }

    @JavascriptInterface
    public String summary(String pid) {
        try { return store.summary(pid, activeModel()).toString(); }
        catch (Exception e) { return "{\"error\":" + JSONObject.quote(String.valueOf(e.getMessage())) + "}"; }
    }

    @JavascriptInterface
    public String nativeLog() { try { return Native.recentLog(); } catch (Throwable t) { return t.toString(); } }

    // ------------------------------------------------------------ models
    @JavascriptInterface
    public void setActiveModel(String id) {
        if (exists(id)) prefs.edit().putString("model", id).apply();
        emit("refresh");
    }

    @JavascriptInterface
    public void setMirror(String m) { prefs.edit().putString("mirror", m == null ? "" : m.trim()).apply(); }

    @JavascriptInterface
    public void downloadModel(String id) {
        start("下載模型", () -> {
            models.download(id, prefs.getString("mirror", ""), dlListener());
            if (!cancel.get()) {
                prefs.edit().putString("model", id).apply();
                emit("log", "msg", "✓ 模型下載完成");
            }
            emit("refresh");
        });
    }

    @JavascriptInterface
    public void deleteModel(String id) {
        start("刪除模型", () -> {
            if (engine.loadedModel().equals(id)) engine.release();
            models.delete(id);
            emit("refresh");
        });
    }

    @JavascriptInterface
    public void pickModelFile() { act.pick(MainActivity.REQ_MODEL, "*/*"); }

    void onModelPicked(Uri uri) {
        start("匯入模型檔", () -> {
            String name = act.displayName(uri);
            try (InputStream in = act.getContentResolver().openInputStream(uri)) {
                String t = models.importFile(name, in, dlListener());
                emit("log", "msg", "✓ 已匯入模型檔 " + t);
            }
            emit("refresh");
        });
    }

    // ------------------------------------------------------------ projects
    @JavascriptInterface
    public void pickProject() { act.pick(MainActivity.REQ_PROJECT, "*/*"); }

    void onProjectPicked(Uri uri) {
        start("匯入專案", () -> {
            String json;
            try (InputStream in = act.getContentResolver().openInputStream(uri)) { json = new String(Store.readAll(in), StandardCharsets.UTF_8); }
            importJson(json);
        });
    }

    private void importJson(String json) throws Exception {
        if (json.startsWith("﻿")) json = json.substring(1);
        String msg = store.importProject(json);
        JSONObject p = new JSONObject(json);
        emit("imported", "msg", msg, "project", p.getString("id"));
        emit("refresh");
    }

    @JavascriptInterface
    public void importUrl(String url) {
        start("從網址匯入", () -> {
            String u = url.trim();
            // GitHub page links -> raw
            if (u.matches("https://github\\.com/[^/]+/[^/]+/blob/.+")) u = u.replace("https://github.com/", "https://raw.githubusercontent.com/").replace("/blob/", "/");
            HttpURLConnection c = (HttpURLConnection) new URL(u).openConnection();
            c.setConnectTimeout(20000);
            c.setReadTimeout(60000);
            int code = c.getResponseCode();
            if (code != 200) throw new IllegalStateException("下載失敗（HTTP " + code + "）");
            String json;
            try (InputStream in = c.getInputStream()) { json = new String(Store.readAll(in), StandardCharsets.UTF_8); }
            importJson(json);
        });
    }

    @JavascriptInterface
    public void deleteProject(String pid) { store.deleteProject(pid); emit("refresh"); }

    // ------------------------------------------------------------ generation
    @JavascriptInterface
    public void generate(String pid, String idsJson, int takes) { run(pid, idsJson, takes, false); }

    @JavascriptInterface
    public void regenerate(String pid, String idsJson) { run(pid, idsJson, 1, true); }

    private void run(String pid, String idsJson, int takes, boolean chooseNew) {
        start("產生語音", () -> {
            List<String> only = null;
            if (idsJson != null && !idsJson.trim().isEmpty()) {
                JSONArray a = new JSONArray(idsJson);
                only = new ArrayList<>();
                for (int i = 0; i < a.length(); i++) only.add(a.getString(i));
            }
            loadEngine();
            long t0 = System.currentTimeMillis();
            int n = store.generate(pid, only, Math.max(1, Math.min(4, takes)), activeModel(), engine, progress(), chooseNew);
            emit("log", "msg", (cancel.get() ? "■ 已停止。" : "✓ 完成。") + "這次產生 " + n + " 個版本，用了 " + (System.currentTimeMillis() - t0) / 1000 + " 秒");
            emit("refresh");
        });
    }

    @JavascriptInterface
    public String choose(String pid, String lid, int k) {
        try { store.choose(pid, lid, k); return "ok"; } catch (Exception e) { return String.valueOf(e.getMessage()); }
    }

    @JavascriptInterface
    public void setRolePost(String pid, String role, String postJson) {
        start("兒童音色後製", () -> {
            JSONObject post = postJson == null || postJson.isEmpty() ? null : new JSONObject(postJson);
            int n = store.setRolePost(pid, role, post, progress());
            emit("log", "msg", "✓ 已重新後製 " + n + " 句");
            emit("refresh");
        });
    }

    /** Phone-side 注音 fix for a word (empty zhuyin = remove). Returns "ok" or an error message. */
    @JavascriptInterface
    public String setPron(String pid, String from, String zhuyin) {
        try { store.setPron(pid, from, zhuyin); emit("refresh"); return "ok"; } catch (Exception e) { return String.valueOf(e.getMessage()); }
    }

    /** Preview what the model would get for a text (for the 注音 dialog). */
    @JavascriptInterface
    public String previewText(String pid, String text) {
        try {
            JSONObject p = store.project(pid), st = store.state(pid);
            JSONObject l = new JSONObject().put("id", "x").put("role", "").put("text", text);
            Pron.Result r = Store.modelOf(p, st, l);
            return r.text;
        } catch (Exception e) { return String.valueOf(e.getMessage()); }
    }

    @JavascriptInterface
    public void setRoleSpeaker(String pid, String role, String speaker) {
        try { store.setRoleSpeaker(pid, role, speaker); } catch (Exception e) { emit("error", "msg", e.getMessage()); }
        emit("refresh");
    }

    /** Hear a speaker + post settings on the role's first line (needs the model, ~10 s). */
    @JavascriptInterface
    public void audition(String pid, String role, String speaker, String postJson) {
        start("試聽音色", () -> {
            loadEngine();
            File f = store.audition(pid, role, speaker, postJson == null || postJson.isEmpty() ? null : new JSONObject(postJson), engine);
            emit("preview", "url", act.fileUrl(f));
        });
    }

    /** One tap: record every line that is missing or changed, apply post-processing, export. */
    @JavascriptInterface
    public void makeAll(String pid, boolean share) {
        start("一鍵完成", () -> {
            loadEngine();
            long t0 = System.currentTimeMillis();
            int n = store.generate(pid, null, 1, activeModel(), engine, progress());
            if (cancel.get()) { emit("log", "msg", "■ 已停止。錄好的句子都保留著。"); emit("refresh"); return; }
            int m = store.repostStale(pid, progress());
            JSONObject info = store.export(pid, Native::mp3);
            File ex = new File(store.dir(pid), "export");
            Uri a = saveToDownloads(pid, new File(ex, "all.txt"));
            Uri b = saveToDownloads(pid, new File(ex, "map.txt"));
            info.put("folder", "Download/語音工作室/" + pid).put("made", n).put("posted", m).put("minutes", Math.round((System.currentTimeMillis() - t0) / 6000.0) / 10.0);
            emit(new JSONObject().put("type", "exported").put("info", info));
            emit("refresh");
            if (share) act.share(new Uri[]{a, b});
        });
    }

    @JavascriptInterface
    public void previewPost(String pid, String lid, String postJson) {
        start("試聽後製", () -> {
            File f = store.previewPost(pid, lid, new JSONObject(postJson));
            emit("preview", "url", act.fileUrl(f));
        });
    }

    @JavascriptInterface
    public void cancel() {
        cancel.set(true);
        try { Native.cancel(); } catch (Throwable ignored) {}
    }

    // ------------------------------------------------------------ export
    @JavascriptInterface
    public void export(String pid, boolean share) {
        start("匯出", () -> {
            JSONObject info = store.export(pid, Native::mp3);
            File ex = new File(store.dir(pid), "export");
            Uri a = saveToDownloads(pid, new File(ex, "all.txt"));
            Uri m = saveToDownloads(pid, new File(ex, "map.txt"));
            info.put("folder", "Download/語音工作室/" + pid);
            emit(new JSONObject().put("type", "exported").put("info", info));
            if (share) act.share(new Uri[]{a, m});
        });
    }

    private Uri saveToDownloads(String pid, File f) throws Exception {
        ContentResolver cr = act.getContentResolver();
        String rel = "Download/語音工作室/" + pid + "/";
        Uri col = MediaStore.Downloads.EXTERNAL_CONTENT_URI;
        // replace an earlier export of ours
        try (Cursor c = cr.query(col, new String[]{MediaStore.MediaColumns._ID},
                MediaStore.MediaColumns.RELATIVE_PATH + "=? AND " + MediaStore.MediaColumns.DISPLAY_NAME + "=?",
                new String[]{rel, f.getName()}, null)) {
            while (c != null && c.moveToNext()) {
                try { cr.delete(Uri.withAppendedPath(col, String.valueOf(c.getLong(0))), null, null); } catch (Exception ignored) {}
            }
        }
        ContentValues v = new ContentValues();
        v.put(MediaStore.MediaColumns.DISPLAY_NAME, f.getName());
        v.put(MediaStore.MediaColumns.MIME_TYPE, "text/plain");
        v.put(MediaStore.MediaColumns.RELATIVE_PATH, rel);
        Uri uri = cr.insert(col, v);
        if (uri == null) throw new IllegalStateException("無法寫入下載資料夾");
        try (OutputStream o = cr.openOutputStream(uri); InputStream in = new FileInputStream(f)) {
            byte[] b = new byte[1 << 16];
            int n;
            while ((n = in.read(b)) > 0) o.write(b, 0, n);
        }
        return uri;
    }

    @JavascriptInterface
    public void openUrl(String url) { act.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url))); }
}
