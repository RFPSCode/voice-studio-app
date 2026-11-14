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

    Bridge(MainActivity act) throws Exception {
        this.act = act;
        store = new Store(new File(act.getFilesDir(), "data"));
        String cat;
        try (InputStream in = act.getAssets().open("models.json")) { cat = new String(Store.readAll(in), StandardCharsets.UTF_8); }
        models = new Models(new File(act.getFilesDir(), "models"), cat);
        prefs = act.getSharedPreferences("vs", 0);
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

    private void loadEngine() throws Exception {
        String id = activeModel();
        if (!models.installed(id)) throw new IllegalStateException("還沒下載模型，請到「模型」分頁下載。");
        if (!engine.loadedModel().equals(id)) {
            emit("log", "msg", "載入模型中（第一次約 10–30 秒）…");
            long t0 = System.currentTimeMillis();
            engine.ensure(id, models.talker(id), models.codec(id));
            emit("log", "msg", "模型已載入（" + (System.currentTimeMillis() - t0) / 1000 + " 秒）");
        }
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
    public void generate(String pid, String idsJson, int takes) {
        start("產生語音", () -> {
            List<String> only = null;
            if (idsJson != null && !idsJson.trim().isEmpty()) {
                JSONArray a = new JSONArray(idsJson);
                only = new ArrayList<>();
                for (int i = 0; i < a.length(); i++) only.add(a.getString(i));
            }
            loadEngine();
            long t0 = System.currentTimeMillis();
            int n = store.generate(pid, only, Math.max(1, Math.min(4, takes)), activeModel(), engine, progress());
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
