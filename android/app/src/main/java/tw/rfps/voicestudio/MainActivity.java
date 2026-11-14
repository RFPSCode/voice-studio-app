package tw.rfps.voicestudio;

import android.app.Activity;
import android.content.Intent;
import android.database.Cursor;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.view.WindowManager;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;

/**
 * One WebView. The UI lives in assets/ui and is served from https://app.local/ui/ ;
 * project files are served from https://app.local/files/ (read-only) so the page can play them.
 */
public class MainActivity extends Activity {
    static final int REQ_PROJECT = 11, REQ_MODEL = 12;
    static final String HOST = "app.local";
    private WebView web;
    private Bridge bridge;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().setStatusBarColor(Color.parseColor("#1d2466"));
        web = new WebView(this);
        setContentView(web);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(false);
        try {
            bridge = new Bridge(this);
        } catch (Exception e) {
            web.loadData("<h3>啟動失敗</h3><pre>" + e + "</pre>", "text/html; charset=utf-8", "utf-8");
            return;
        }
        web.addJavascriptInterface(bridge, "VS");
        web.setWebChromeClient(new WebChromeClient());   // needed for alert() / confirm() in the page
        web.setWebViewClient(new WebViewClient() {
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest req) {
                Uri u = req.getUrl();
                if (!HOST.equals(u.getHost())) return null;
                String path = u.getPath() == null ? "/" : u.getPath();
                try {
                    if (path.startsWith("/ui/")) {
                        String rel = path.substring(1);
                        if (rel.contains("..")) return notFound();
                        return resp(mime(rel), getAssets().open(rel));
                    }
                    if (path.startsWith("/files/")) {
                        String rel = path.substring(7);
                        if (rel.contains("..")) return notFound();
                        File f = new File(bridge.store.root, rel);
                        if (!f.isFile()) return notFound();
                        return resp(mime(rel), new FileInputStream(f));
                    }
                } catch (Exception e) { return notFound(); }
                return notFound();
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest req) {
                Uri u = req.getUrl();
                if (HOST.equals(u.getHost())) return false;
                startActivity(new Intent(Intent.ACTION_VIEW, u));
                return true;
            }
        });
        web.loadUrl("https://" + HOST + "/ui/index.html");
        handleIntent(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        handleIntent(intent);
    }

    /** Opening a .voiceproj.json from a file manager / LINE / Drive imports it. */
    private void handleIntent(Intent it) {
        if (it == null || bridge == null) return;
        Uri u = null;
        if (Intent.ACTION_VIEW.equals(it.getAction())) u = it.getData();
        else if (Intent.ACTION_SEND.equals(it.getAction())) u = it.getParcelableExtra(Intent.EXTRA_STREAM);
        if (u != null) {
            final Uri uu = u;
            web.postDelayed(() -> bridge.onProjectPicked(uu), 800);
        }
    }

    static WebResourceResponse resp(String mime, InputStream in) {
        Map<String, String> h = new HashMap<>();
        h.put("Cache-Control", "no-store");
        h.put("Access-Control-Allow-Origin", "*");
        return new WebResourceResponse(mime, "utf-8", 200, "OK", h, in);
    }

    static WebResourceResponse notFound() {
        return new WebResourceResponse("text/plain", "utf-8", 404, "Not Found", new HashMap<String, String>(), null);
    }

    static String mime(String p) {
        if (p.endsWith(".html")) return "text/html";
        if (p.endsWith(".js")) return "application/javascript";
        if (p.endsWith(".css")) return "text/css";
        if (p.endsWith(".json")) return "application/json";
        if (p.endsWith(".wav")) return "audio/wav";
        if (p.endsWith(".mp3")) return "audio/mpeg";
        if (p.endsWith(".png")) return "image/png";
        if (p.endsWith(".svg")) return "image/svg+xml";
        return "text/plain";
    }

    String fileUrl(File f) {
        String rel = bridge.store.root.toURI().relativize(f.toURI()).getPath();
        return "https://" + HOST + "/files/" + rel + "?t=" + System.currentTimeMillis();
    }

    void sendToJs(String json) {
        runOnUiThread(() -> web.evaluateJavascript("window.onVS&&window.onVS(" + json + ")", null));
    }

    void keepScreenOn(boolean on) {
        runOnUiThread(() -> {
            if (on) getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            else getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        });
    }

    void pick(int req, String mime) {
        runOnUiThread(() -> {
            Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            i.addCategory(Intent.CATEGORY_OPENABLE);
            i.setType(mime);
            startActivityForResult(i, req);
        });
    }

    String displayName(Uri uri) {
        try (Cursor c = getContentResolver().query(uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (c != null && c.moveToFirst()) return c.getString(0);
        } catch (Exception ignored) {}
        String p = uri.getLastPathSegment();
        return p == null ? "" : p.substring(p.lastIndexOf('/') + 1);
    }

    void share(Uri[] uris) {
        runOnUiThread(() -> {
            Intent i = new Intent(Intent.ACTION_SEND_MULTIPLE);
            i.setType("text/plain");
            ArrayList<Uri> list = new ArrayList<>();
            for (Uri u : uris) list.add(u);
            i.putParcelableArrayListExtra(Intent.EXTRA_STREAM, list);
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(Intent.createChooser(i, "分享語音檔"));
        });
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (res != RESULT_OK || data == null || data.getData() == null) return;
        if (req == REQ_PROJECT) bridge.onProjectPicked(data.getData());
        if (req == REQ_MODEL) bridge.onModelPicked(data.getData());
    }

    @Override
    public void onBackPressed() {
        web.evaluateJavascript("window.goBack?window.goBack():false", v -> {
            if (!"true".equals(v)) MainActivity.super.onBackPressed();
        });
    }
}
