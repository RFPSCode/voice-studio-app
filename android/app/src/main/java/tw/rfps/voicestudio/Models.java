package tw.rfps.voicestudio;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.net.HttpURLConnection;
import java.net.URL;

/** Model catalog (assets/models.json) and downloads with resume. */
public class Models {
    public interface Listener {
        void progress(String model, String file, long got, long total);
        boolean cancelled();
    }

    public final File dir;
    public JSONArray catalog;

    public Models(File dir, String catalogJson) throws Exception {
        this.dir = dir;
        dir.mkdirs();
        this.catalog = new JSONObject(catalogJson).getJSONArray("models");
    }

    public JSONObject find(String id) throws Exception {
        for (int i = 0; i < catalog.length(); i++) if (catalog.getJSONObject(i).getString("id").equals(id)) return catalog.getJSONObject(i);
        throw new IllegalArgumentException("unknown model " + id);
    }

    public File file(String name) { return new File(dir, name); }

    static boolean isGguf(File f) {
        if (!f.exists() || f.length() < 1024) return false;
        try (RandomAccessFile r = new RandomAccessFile(f, "r")) {
            byte[] b = new byte[4];
            r.readFully(b);
            return b[0] == 'G' && b[1] == 'G' && b[2] == 'U' && b[3] == 'F';
        } catch (IOException e) { return false; }
    }

    public boolean installed(String id) throws Exception {
        JSONArray fs = find(id).getJSONArray("files");
        for (int i = 0; i < fs.length(); i++) if (!isGguf(file(fs.getJSONObject(i).getString("name")))) return false;
        return true;
    }

    public File talker(String id) throws Exception { return file(find(id).getJSONArray("files").getJSONObject(0).getString("name")); }
    public File codec(String id) throws Exception { return file(find(id).getJSONArray("files").getJSONObject(1).getString("name")); }

    public JSONArray status() throws Exception {
        JSONArray out = new JSONArray();
        for (int i = 0; i < catalog.length(); i++) {
            JSONObject m = catalog.getJSONObject(i), o = new JSONObject();
            o.put("id", m.getString("id")).put("name", m.optString("name")).put("note", m.optString("note")).put("sizeMB", m.optInt("sizeMB"));
            JSONArray fs = m.getJSONArray("files"), fo = new JSONArray();
            boolean all = true;
            for (int k = 0; k < fs.length(); k++) {
                File f = file(fs.getJSONObject(k).getString("name"));
                File part = new File(f.getPath() + ".part");
                boolean ok = isGguf(f);
                all &= ok;
                fo.put(new JSONObject().put("name", f.getName()).put("ok", ok).put("bytes", ok ? f.length() : (part.exists() ? part.length() : 0)));
            }
            o.put("files", fo).put("installed", all);
            out.put(o);
        }
        return out;
    }

    /** Download every missing file of a model (resumes .part files). mirror: optional host replacing huggingface.co */
    public void download(String id, String mirror, Listener l) throws Exception {
        JSONArray fs = find(id).getJSONArray("files");
        for (int i = 0; i < fs.length() && !l.cancelled(); i++) {
            JSONObject f = fs.getJSONObject(i);
            File dst = file(f.getString("name"));
            if (isGguf(dst)) continue;
            String url = f.getString("url");
            if (mirror != null && !mirror.trim().isEmpty()) url = url.replace("https://huggingface.co", mirror.trim().replaceAll("/+$", ""));
            fetch(url, dst, id, l);
        }
    }

    void fetch(String url, File dst, String model, Listener l) throws Exception {
        File part = new File(dst.getPath() + ".part");
        for (int redirects = 0; redirects < 8; redirects++) {
            long have = part.exists() ? part.length() : 0;
            HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
            c.setInstanceFollowRedirects(false);
            c.setConnectTimeout(20000);
            c.setReadTimeout(60000);
            c.setRequestProperty("User-Agent", "VoiceStudio/1.0 (Android)");
            if (have > 0) c.setRequestProperty("Range", "bytes=" + have + "-");
            int code = c.getResponseCode();
            if (code >= 300 && code < 400) {
                String loc = c.getHeaderField("Location");
                c.disconnect();
                if (loc == null) throw new IOException("redirect without Location");
                url = new URL(new URL(url), loc).toString();
                continue;
            }
            if (code == 416) { c.disconnect(); break; } // already complete
            if (code != 200 && code != 206) {
                c.disconnect();
                throw new IOException("下載失敗（HTTP " + code + "）：" + url);
            }
            boolean append = code == 206;
            long total = c.getContentLengthLong();
            if (total > 0 && append) total += have;
            if (!append) have = 0;
            try (InputStream in = c.getInputStream(); FileOutputStream out = new FileOutputStream(part, append)) {
                byte[] buf = new byte[1 << 16];
                long got = have, last = 0;
                int n;
                while ((n = in.read(buf)) > 0) {
                    out.write(buf, 0, n);
                    got += n;
                    if (got - last > (1 << 20)) { last = got; l.progress(model, dst.getName(), got, total); }
                    if (l.cancelled()) throw new InterruptedException("cancelled");
                }
                l.progress(model, dst.getName(), got, total);
            } finally { c.disconnect(); }
            break;
        }
        if (!isGguf(part)) { throw new IOException("下載的檔案不是模型（GGUF）：" + dst.getName()); }
        if (!part.renameTo(dst)) throw new IOException("cannot rename " + part);
    }

    /** Copy a model file picked from phone storage; it goes to the slot whose name matches. */
    public String importFile(String displayName, InputStream in, Listener l) throws Exception {
        String target = null;
        for (int i = 0; i < catalog.length() && target == null; i++) {
            JSONArray fs = catalog.getJSONObject(i).getJSONArray("files");
            for (int k = 0; k < fs.length(); k++) if (fs.getJSONObject(k).getString("name").equalsIgnoreCase(displayName)) target = fs.getJSONObject(k).getString("name");
        }
        if (target == null) throw new IllegalArgumentException("檔名不符合任何模型檔：" + displayName);
        File part = new File(dir, target + ".part");
        try (FileOutputStream out = new FileOutputStream(part)) {
            byte[] buf = new byte[1 << 16];
            long got = 0, last = 0;
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
                got += n;
                if (got - last > (8 << 20)) { last = got; l.progress("import", target, got, -1); }
            }
        }
        if (!isGguf(part)) { part.delete(); throw new IOException("這不是 GGUF 模型檔"); }
        if (!part.renameTo(new File(dir, target))) throw new IOException("cannot rename");
        return target;
    }

    public void delete(String id) throws Exception {
        JSONArray fs = find(id).getJSONArray("files");
        for (int i = 0; i < fs.length(); i++) {
            String name = fs.getJSONObject(i).getString("name");
            boolean shared = false;
            for (int m = 0; m < catalog.length(); m++) {
                JSONObject other = catalog.getJSONObject(m);
                if (other.getString("id").equals(id) || !installed(other.getString("id"))) continue;
                JSONArray of = other.getJSONArray("files");
                for (int k = 0; k < of.length(); k++) if (of.getJSONObject(k).getString("name").equals(name)) shared = true;
            }
            if (!shared) { file(name).delete(); new File(file(name).getPath() + ".part").delete(); }
        }
    }
}
