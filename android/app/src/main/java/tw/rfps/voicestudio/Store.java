package tw.rfps.voicestudio;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Projects on the phone (no Android classes here, so it can be tested on a PC):
 *
 * projects/&lt;id&gt;/project.json   the imported voice-studio-project (spec v1)
 * projects/&lt;id&gt;/state.json     per-line status: chosen take, signature, takes; role overrides
 * projects/&lt;id&gt;/takes/&lt;line&gt;_&lt;k&gt;.wav   raw model output (trimmed)
 * projects/&lt;id&gt;/out/&lt;line&gt;.wav        chosen take after child-voice post-processing
 * projects/&lt;id&gt;/export/all.txt, map.txt
 */
public class Store {
    public static final int SR = 24000;
    public static final String[] SPEAKERS = {"vivian", "serena", "uncle_fu", "dylan", "eric", "ryan", "aiden", "ono_anna", "sohee"};
    /** decoder delay of the shine MP3 stream (measured), added to every start time */
    public static final double MP3_DELAY = 1057.0 / SR;

    public interface Synth {
        /** returns mono float PCM at 24 kHz or throws */
        float[] synth(String text, String lang, String speaker, String instruct, long seed, float temperature, float topP) throws Exception;
    }

    public interface Progress {
        void line(String projectId, String lineId, String status, JSONObject info);
        void log(String msg);
        boolean cancelled();
    }

    public final File root;

    public Store(File root) {
        this.root = root;
        new File(root, "projects").mkdirs();
    }

    // ------------------------------------------------------------------ io helpers
    public static String readText(File f) throws IOException {
        try (InputStream in = new FileInputStream(f)) { return new String(readAll(in), StandardCharsets.UTF_8); }
    }

    public static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        byte[] b = new byte[65536];
        int n;
        while ((n = in.read(b)) > 0) bo.write(b, 0, n);
        return bo.toByteArray();
    }

    public static void writeAtomic(File f, byte[] data) throws IOException {
        f.getParentFile().mkdirs();
        File tmp = new File(f.getPath() + ".part");
        try (FileOutputStream o = new FileOutputStream(tmp)) { o.write(data); }
        if (!tmp.renameTo(f)) {
            f.delete();
            if (!tmp.renameTo(f)) throw new IOException("cannot write " + f);
        }
    }

    public static void writeText(File f, String s) throws IOException { writeAtomic(f, s.getBytes(StandardCharsets.UTF_8)); }

    public static void writeWav(File f, float[] x) throws IOException {
        short[] s = Dsp.toPcm16(x);
        ByteBuffer bb = ByteBuffer.allocate(44 + s.length * 2).order(ByteOrder.LITTLE_ENDIAN);
        bb.put("RIFF".getBytes(StandardCharsets.US_ASCII)).putInt(36 + s.length * 2).put("WAVEfmt ".getBytes(StandardCharsets.US_ASCII))
          .putInt(16).putShort((short) 1).putShort((short) 1).putInt(SR).putInt(SR * 2).putShort((short) 2).putShort((short) 16)
          .put("data".getBytes(StandardCharsets.US_ASCII)).putInt(s.length * 2);
        for (short v : s) bb.putShort(v);
        writeAtomic(f, bb.array());
    }

    public static float[] readWav(File f) throws IOException {
        byte[] b;
        try (InputStream in = new FileInputStream(f)) { b = readAll(in); }
        ByteBuffer bb = ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN);
        int pos = 12, ch = 1, rate = SR, bits = 16, dataOff = 44, dataLen = b.length - 44;
        while (pos + 8 <= b.length) {
            String id = new String(b, pos, 4, StandardCharsets.US_ASCII);
            int len = bb.getInt(pos + 4);
            if (id.equals("fmt ")) { ch = bb.getShort(pos + 10); rate = bb.getInt(pos + 12); bits = bb.getShort(pos + 22); }
            if (id.equals("data")) { dataOff = pos + 8; dataLen = Math.min(len, b.length - dataOff); break; }
            pos += 8 + len + (len & 1);
        }
        if (bits != 16) throw new IOException("only 16-bit WAV");
        int n = dataLen / 2 / ch;
        float[] x = new float[n];
        for (int i = 0; i < n; i++) x[i] = bb.getShort(dataOff + i * 2 * ch) / 32768f;
        return rate == SR ? x : Dsp.resample(x, (double) rate / SR);
    }

    static String md5(String s) {
        try {
            byte[] d = MessageDigest.getInstance("MD5").digest(s.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 6; i++) sb.append(String.format("%02x", d[i]));
            return sb.toString();
        } catch (Exception e) { return Integer.toHexString(s.hashCode()); }
    }

    // ------------------------------------------------------------------ validation
    /** Returns a list of problems (empty = valid). Follows docs/語音專案規格書.md §5. */
    public static List<String> validate(JSONObject p) {
        List<String> err = new ArrayList<>();
        if (!"voice-studio-project".equals(p.optString("format"))) err.add("format 必須是 \"voice-studio-project\"");
        if (p.optInt("version", 0) != 1) err.add("version 必須是 1（這個 App 支援第 1 版）");
        if (!p.optString("id").matches("^[a-z0-9][a-z0-9-]{1,63}$")) err.add("id 只能用小寫英數和 -（2–64 字）");
        if (p.optString("title").trim().isEmpty()) err.add("缺少 title");
        JSONArray roles = p.optJSONArray("roles"), lines = p.optJSONArray("lines");
        if (roles == null || roles.length() == 0) err.add("roles 不能是空的");
        if (lines == null || lines.length() == 0) err.add("lines 不能是空的");
        if (!err.isEmpty()) return err;
        Map<String, JSONObject> rm = new LinkedHashMap<>();
        for (int i = 0; i < roles.length(); i++) {
            JSONObject r = roles.optJSONObject(i);
            if (r == null) { err.add("roles[" + i + "] 不是物件"); continue; }
            String id = r.optString("id");
            if (!id.matches("^[A-Za-z0-9_-]{1,40}$")) err.add("角色 id 格式不對：" + id);
            if (rm.containsKey(id)) err.add("角色 id 重複：" + id);
            rm.put(id, r);
            boolean ok = false;
            for (String s : SPEAKERS) if (s.equals(r.optString("speaker"))) ok = true;
            if (!ok) err.add("角色 " + id + " 的 speaker 不是內建音色：" + r.optString("speaker"));
            JSONObject post = r.optJSONObject("post");
            if (post != null && post.optBoolean("enabled")) {
                double ph = post.optDouble("pitchHz", 0), fo = post.optDouble("formant", 1), sp = post.optDouble("speed", 1);
                if (ph != 0 && (ph < 80 || ph > 500)) err.add("角色 " + id + " 的 pitchHz 超出範圍");
                if (fo < 0.8 || fo > 1.4) err.add("角色 " + id + " 的 formant 超出範圍 0.8–1.4");
                if (sp < 0.7 || sp > 1.3) err.add("角色 " + id + " 的 speed 超出範圍 0.7–1.3");
            }
        }
        Map<String, String> ids = new LinkedHashMap<>(), keys = new LinkedHashMap<>();
        for (int i = 0; i < lines.length(); i++) {
            JSONObject l = lines.optJSONObject(i);
            if (l == null) { err.add("lines[" + i + "] 不是物件"); continue; }
            String id = l.optString("id");
            if (!id.matches("^[A-Za-z0-9_-]{1,40}$")) err.add("句子 id 格式不對：" + id);
            if (ids.containsKey(id)) err.add("句子 id 重複：" + id);
            ids.put(id, id);
            if (!rm.containsKey(l.optString("role"))) err.add("句子 " + id + " 的角色不存在：" + l.optString("role"));
            String text = l.optString("text");
            if (text.trim().isEmpty()) err.add("句子 " + id + " 的 text 是空的");
            if (text.length() > 300) err.add("句子 " + id + " 太長（超過 300 字）");
            String say = sayOf(p, l);
            if (say.matches(".*[<\\[][^>\\]]{1,20}[>\\]].*")) err.add("句子 " + id + " 的文字裡有 <…> 或 […] 標記，模型會照唸");
            String k = keyOf(p, l);
            if (keys.containsKey(k) && !sameVoice(p, l, ids.get(keys.get(k)))) err.add("句子 " + id + " 的 key 和 " + keys.get(k) + " 重複");
            keys.put(k, id);
        }
        return err;
    }

    private static boolean sameVoice(JSONObject p, JSONObject l, String otherId) {
        JSONArray lines = p.optJSONArray("lines");
        for (int i = 0; i < lines.length(); i++) {
            JSONObject o = lines.optJSONObject(i);
            if (o != null && o.optString("id").equals(otherId))
                return o.optString("role").equals(l.optString("role")) && sayOf(p, o).equals(sayOf(p, l));
        }
        return false;
    }

    // ------------------------------------------------------------------ spec helpers
    public static String sayOf(JSONObject p, JSONObject l) {
        String s = l.optString("say", "");
        if (s.trim().isEmpty()) s = l.optString("text");
        JSONArray pr = p.optJSONArray("pronunciation");
        if (pr != null) for (int i = 0; i < pr.length(); i++) {
            JSONObject e = pr.optJSONObject(i);
            if (e != null && !e.optString("from").isEmpty()) s = s.replace(e.optString("from"), e.optString("to"));
        }
        return s.trim();
    }

    public static JSONObject roleOf(JSONObject p, String id) {
        JSONArray roles = p.optJSONArray("roles");
        for (int i = 0; roles != null && i < roles.length(); i++) {
            JSONObject r = roles.optJSONObject(i);
            if (r != null && r.optString("id").equals(id)) return r;
        }
        return new JSONObject();
    }

    public static String instructOf(JSONObject p, JSONObject l) {
        StringBuilder sb = new StringBuilder();
        for (String s : new String[]{p.optString("instruct"), roleOf(p, l.optString("role")).optString("instruct"), l.optString("instruct")}) {
            s = s == null ? "" : s.trim();
            if (s.isEmpty()) continue;
            if (sb.length() > 0) sb.append("；");
            sb.append(s);
        }
        return sb.toString();
    }

    public static String keyOf(JSONObject p, JSONObject l) {
        String k = l.optString("key", "");
        if (!k.isEmpty()) return k;
        JSONObject out = p.optJSONObject("output");
        String fmt = out != null ? out.optString("keyFormat", "{role}|{text}") : "{role}|{text}";
        return fmt.replace("{role}", l.optString("role")).replace("{text}", l.optString("text")).replace("{id}", l.optString("id"));
    }

    public static String sigOf(JSONObject p, JSONObject l, String model) {
        JSONObject e = p.optJSONObject("engine");
        String lang = e != null ? e.optString("language", "chinese") : "chinese";
        return md5(sayOf(p, l) + "\u0001" + roleOf(p, l.optString("role")).optString("speaker") + "\u0001" + instructOf(p, l) + "\u0001" + lang + "\u0001" + model);
    }

    // ------------------------------------------------------------------ projects
    public File dir(String id) { return new File(new File(root, "projects"), id); }

    public JSONObject project(String id) throws IOException, JSONException { return new JSONObject(readText(new File(dir(id), "project.json"))); }

    public JSONObject state(String id) {
        try { return new JSONObject(readText(new File(dir(id), "state.json"))); }
        catch (Exception e) {
            JSONObject s = new JSONObject();
            try { s.put("lines", new JSONObject()); s.put("roles", new JSONObject()); } catch (JSONException ignored) {}
            return s;
        }
    }

    public synchronized void saveState(String id, JSONObject s) throws IOException {
        try { writeText(new File(dir(id), "state.json"), s.toString(1)); } catch (JSONException e) { throw new IOException(e); }
    }

    /** Import (or update) a project. Returns a short summary string. */
    public synchronized String importProject(String json) throws Exception {
        JSONObject p = new JSONObject(json);
        List<String> err = validate(p);
        if (!err.isEmpty()) {
            StringBuilder sb = new StringBuilder("專案檔有 " + err.size() + " 個問題：\n");
            for (int i = 0; i < Math.min(12, err.size()); i++) sb.append("‧").append(err.get(i)).append('\n');
            throw new IllegalArgumentException(sb.toString());
        }
        String id = p.getString("id");
        boolean existed = new File(dir(id), "project.json").exists();
        writeText(new File(dir(id), "project.json"), p.toString(1));
        int n = p.getJSONArray("lines").length();
        return (existed ? "已更新" : "已匯入") + "「" + p.optString("title") + "」：" + n + " 句";
    }

    public List<String> projectIds() {
        List<String> out = new ArrayList<>();
        File[] fs = new File(root, "projects").listFiles();
        if (fs != null) for (File f : fs) if (new File(f, "project.json").exists()) out.add(f.getName());
        return out;
    }

    public void deleteProject(String id) { deleteTree(dir(id)); }

    static void deleteTree(File f) {
        File[] fs = f.listFiles();
        if (fs != null) for (File c : fs) deleteTree(c);
        f.delete();
    }

    public File takeFile(String id, String line, int k) { return new File(new File(dir(id), "takes"), line + "_" + k + ".wav"); }
    public File outFile(String id, String line) { return new File(new File(dir(id), "out"), line + ".wav"); }

    /** Effective post settings: role.post overridden by state.roles[role].post. */
    public static JSONObject postOf(JSONObject p, JSONObject state, String role) {
        JSONObject o = state.optJSONObject("roles") != null ? state.optJSONObject("roles").optJSONObject(role) : null;
        if (o != null && o.optJSONObject("post") != null) return o.optJSONObject("post");
        JSONObject r = roleOf(p, role).optJSONObject("post");
        return r != null ? r : new JSONObject();
    }

    /** Per-line status for the UI. */
    public JSONObject summary(String id, String model) throws Exception {
        JSONObject p = project(id), st = state(id), ls = st.optJSONObject("lines");
        if (ls == null) ls = new JSONObject();
        JSONArray lines = p.getJSONArray("lines");
        JSONArray rows = new JSONArray();
        int done = 0, changed = 0;
        for (int i = 0; i < lines.length(); i++) {
            JSONObject l = lines.getJSONObject(i), s = ls.optJSONObject(l.getString("id"));
            JSONObject r = new JSONObject();
            r.put("id", l.getString("id"));
            r.put("role", l.optString("role"));
            r.put("text", l.optString("text"));
            r.put("say", sayOf(p, l));
            r.put("instruct", instructOf(p, l));
            r.put("note", l.optString("note"));
            String status = "todo";
            if (s != null && s.optInt("chosen", -1) >= 0 && outFile(id, l.getString("id")).exists()) {
                status = s.optString("sig").equals(sigOf(p, l, model)) ? "done" : "changed";
                r.put("takes", s.optJSONArray("takes") != null ? s.optJSONArray("takes") : new JSONArray());
                r.put("chosen", s.optInt("chosen"));
                r.put("dur", s.optDouble("dur", 0));
            } else if (s != null && s.optJSONArray("takes") != null && s.optJSONArray("takes").length() > 0) {
                r.put("takes", s.optJSONArray("takes"));
                r.put("chosen", -1);
            }
            if (status.equals("done")) done++;
            if (status.equals("changed")) changed++;
            r.put("status", status);
            rows.put(r);
        }
        JSONObject res = new JSONObject();
        res.put("project", p);
        res.put("lines", rows);
        res.put("done", done);
        res.put("changed", changed);
        res.put("total", lines.length());
        JSONObject posts = new JSONObject();
        JSONArray roles = p.getJSONArray("roles");
        for (int i = 0; i < roles.length(); i++) {
            String rid = roles.getJSONObject(i).getString("id");
            posts.put(rid, postOf(p, st, rid));
        }
        res.put("posts", posts);
        return res;
    }

    // ------------------------------------------------------------------ generation
    /**
     * Generate takes for the given lines (null = every line that is not done).
     * takes = how many versions per line (1–4). The first new take is chosen automatically
     * when the line has no chosen take yet.
     */
    public int generate(String id, List<String> only, int takes, String model, Synth engine, Progress pr) throws Exception {
        return generate(id, only, takes, model, engine, pr, false);
    }

    /** chooseNew: the first new take of every line becomes the chosen one ("regenerate all"). */
    public int generate(String id, List<String> only, int takes, String model, Synth engine, Progress pr, boolean chooseNew) throws Exception {
        JSONObject p = project(id);
        JSONObject eng = p.optJSONObject("engine") != null ? p.optJSONObject("engine") : new JSONObject();
        String lang = eng.optString("language", "chinese");
        float temp = (float) eng.optDouble("temperature", 0.8);
        float topP = (float) eng.optDouble("topP", 0);
        long seed0 = eng.optLong("seed", -1);
        JSONArray lines = p.getJSONArray("lines");
        baseF0.clear();
        int made = 0;
        for (int i = 0; i < lines.length(); i++) {
            if (pr.cancelled()) break;
            JSONObject l = lines.getJSONObject(i);
            String lid = l.getString("id");
            JSONObject st = state(id), ls = st.getJSONObject("lines"), s = ls.optJSONObject(lid);
            String sig = sigOf(p, l, model);
            if (only == null) {
                if (s != null && sig.equals(s.optString("sig")) && s.optInt("chosen", -1) >= 0 && outFile(id, lid).exists()) continue;
            } else if (!only.contains(lid)) continue;
            String role = l.optString("role"), speaker = roleOf(p, role).optString("speaker");
            String text = sayOf(p, l), ins = instructOf(p, l);
            boolean fresh = s == null || !sig.equals(s.optString("sig"));
            JSONArray tk = fresh || s.optJSONArray("takes") == null ? new JSONArray() : s.getJSONArray("takes");
            for (int t = 0; t < takes && !pr.cancelled(); t++) {
                long seed = (seed0 >= 0 ? seed0 : roleSeed(roleOf(p, role))) + 7919L * tk.length();
                pr.line(id, lid, "running", new JSONObject().put("take", tk.length() + 1).put("of", takes));
                long t0 = System.currentTimeMillis();
                float[] a;
                try {
                    a = engine.synth(text, lang, speaker, ins, seed, temp, topP);
                } catch (Exception e) {
                    if (pr.cancelled()) break;
                    pr.line(id, lid, "error", new JSONObject().put("msg", String.valueOf(e.getMessage())));
                    break;
                }
                if (a == null) { if (pr.cancelled()) break; pr.line(id, lid, "error", new JSONObject().put("msg", "沒有產生聲音")); break; }
                a = Dsp.tidy(a, SR);
                double dur = a.length / (double) SR;
                double secs = (System.currentTimeMillis() - t0) / 1000.0;
                // sanity: about 0.1–0.9 s per character; otherwise retry once
                int chars = Math.max(1, text.replaceAll("[\\s，。！？、；：「」『』（）…—,.!?;:()\"']", "").length());
                boolean odd = dur < 0.3 || dur / chars > 0.9 || dur / chars < 0.08;
                int k = tk.length();
                writeWav(takeFile(id, lid, k), a);
                tk.put(new JSONObject().put("k", k).put("dur", Math.round(dur * 100) / 100.0).put("odd", odd));
                s = ls.optJSONObject(lid);
                if (s == null || fresh) { s = new JSONObject(); ls.put(lid, s); fresh = false; }
                s.put("sig", sig).put("takes", tk);
                if (s.optInt("chosen", -1) < 0 || (chooseNew && t == 0)) {
                    s.put("chosen", k);
                }
                saveState(id, st);
                if (s.optInt("chosen") == k) postLine(id, lid);
                made++;
                pr.line(id, lid, "take", new JSONObject().put("take", k).put("dur", dur).put("rtf", secs / Math.max(0.1, dur)).put("odd", odd));
                st = state(id); ls = st.getJSONObject("lines");
            }
        }
        return made;
    }

    public void choose(String id, String lid, int k) throws Exception {
        JSONObject st = state(id), s = st.getJSONObject("lines").optJSONObject(lid);
        if (s == null || !takeFile(id, lid, k).exists()) throw new IllegalArgumentException("沒有這個版本");
        s.put("chosen", k);
        saveState(id, st);
        postLine(id, lid);
    }

    // ------------------------------------------------------------------ post-processing
    private final Map<String, Double> baseF0 = new LinkedHashMap<>();

    /** Typical pitch of a role's raw takes (median of up to 8 chosen takes). */
    public double roleBaseF0(String id, String role) throws Exception {
        String key = id + "/" + role;
        Double c = baseF0.get(key);
        if (c != null) return c;
        JSONObject p = project(id), st = state(id), ls = st.getJSONObject("lines");
        JSONArray lines = p.getJSONArray("lines");
        List<Double> v = new ArrayList<>();
        for (int i = 0; i < lines.length() && v.size() < 8; i++) {
            JSONObject l = lines.getJSONObject(i);
            if (!l.optString("role").equals(role)) continue;
            JSONObject s = ls.optJSONObject(l.getString("id"));
            if (s == null || s.optInt("chosen", -1) < 0) continue;
            File f = takeFile(id, l.getString("id"), s.getInt("chosen"));
            if (!f.exists()) continue;
            double f0 = Dsp.medianF0(readWav(f), SR);
            if (f0 > 0) v.add(f0);
        }
        if (v.isEmpty()) return 0;
        java.util.Collections.sort(v);
        double m = v.get(v.size() / 2);
        if (v.size() >= 3) baseF0.put(key, m);
        return m;
    }

    public void invalidateBase(String id, String role) { baseF0.remove(id + "/" + role); }

    /** Ready-made post-processing sets (same names/values as the web UI, the PC editor and the spec). */
    public static final String PRESETS_JSON = "{"
        + "\"男童\":{\"pitchHz\":270,\"pitchLock\":0.6,\"formant\":1.17,\"trebleDb\":1},"
        + "\"女童\":{\"pitchHz\":300,\"pitchLock\":0.6,\"formant\":1.2,\"trebleDb\":1},"
        + "\"可愛女聲\":{\"pitchHz\":290,\"pitchLock\":0.5,\"formant\":1.1,\"speed\":1.03,\"trebleDb\":2},"
        + "\"渾厚男聲\":{\"pitchHz\":100,\"pitchLock\":0.5,\"formant\":0.92,\"bassDb\":4,\"trebleDb\":-1},"
        + "\"一般女聲\":{\"pitchHz\":220,\"pitchLock\":0.5,\"formant\":1.0},"
        + "\"一般男聲\":{\"pitchHz\":125,\"pitchLock\":0.5,\"formant\":1.0},"
        + "\"惡魔\":{\"pitchHz\":150,\"pitchLock\":0.5,\"formant\":0.95,\"octDown\":0.2,\"growl\":0.12,\"bassDb\":2,\"echoMs\":100,\"echoFeedback\":0.25,\"echoMix\":0.2},"
        + "\"天使\":{\"pitchHz\":290,\"pitchLock\":0.5,\"formant\":1.08,\"octUp\":0.18,\"trebleDb\":3,\"reverb\":0.35}"
        + "}";

    static double num(JSONObject o, String k, double d, double lo, double hi) {
        double v = o.optDouble(k, d);
        if (Double.isNaN(v)) v = d;
        return Math.max(lo, Math.min(hi, v));
    }

    /**
     * Post-processing chain (spec §2.3.1):
     * pitch/formant/speed → octave layers → growl → bass/treble → loudness → echo → reverb.
     * baseF0 = the role's typical pitch; pitchLock 0 keeps each line's own pitch relative to it, 1 makes every line hit pitchHz.
     */
    public static float[] applyPost(float[] a, JSONObject post, double baseF0) {
        if (post == null || !post.optBoolean("enabled")) return a;
        double target = num(post, "pitchHz", 0, 0, 500), lock = num(post, "pitchLock", 0.5, 0, 1);
        double formant = num(post, "formant", 1, 0.8, 1.4), speed = num(post, "speed", 1, 0.7, 1.3);
        double ratio = 1;
        if (target > 0) {
            double line = Dsp.medianF0(a, SR);
            double base = baseF0 > 0 ? baseF0 : line;
            if (line <= 0) line = base;
            if (base > 0) ratio = Math.max(0.4, Math.min(3.0, target / (Math.pow(base, 1 - lock) * Math.pow(line, lock))));
        }
        float[] y = (Math.abs(ratio - 1) > 1e-3 || Math.abs(formant - 1) > 1e-3 || Math.abs(speed - 1) > 1e-3)
            ? Dsp.childFx(a, SR, ratio, formant, speed) : a.clone();
        y = Dsp.layer(y, SR, 0.5, 0.8, num(post, "octDown", 0, 0, 1));
        y = Dsp.layer(y, SR, 2.0, 1.15, num(post, "octUp", 0, 0, 1) * 0.6);
        y = Dsp.growl(y, SR, num(post, "growl", 0, 0, 1));
        y = Dsp.shelf(y, SR, 220, num(post, "bassDb", 0, -12, 12), true);
        y = Dsp.shelf(y, SR, 3800, num(post, "trebleDb", 0, -12, 12), false);
        y = Dsp.tidy(y, SR);
        y = Dsp.echo(y, SR, num(post, "echoMs", 0, 0, 800), num(post, "echoFeedback", 0.3, 0, 0.85), num(post, "echoMix", 0, 0, 1));
        y = Dsp.reverb(y, SR, num(post, "reverb", 0, 0, 1));
        return Dsp.peakNorm(y, 0.95f);
    }

    /** Fixed starting seed for a role, so every line of a character starts from the same voice. */
    public static long roleSeed(JSONObject role) {
        if (role.has("seed")) return role.optLong("seed", 1);
        String id = role.optString("id");
        long h = 1125899906842597L;
        for (int i = 0; i < id.length(); i++) h = 31 * h + id.charAt(i);
        return (h & 0x7fffffffL) % 1000000007L;
    }

    /** chosen take -> out/&lt;line&gt;.wav with the role's post settings */
    public void postLine(String id, String lid) throws Exception {
        JSONObject p = project(id), st = state(id), s = st.getJSONObject("lines").optJSONObject(lid);
        if (s == null || s.optInt("chosen", -1) < 0) return;
        JSONObject l = null;
        JSONArray lines = p.getJSONArray("lines");
        for (int i = 0; i < lines.length(); i++) if (lines.getJSONObject(i).getString("id").equals(lid)) l = lines.getJSONObject(i);
        if (l == null) return;
        String role = l.optString("role");
        JSONObject post = postOf(p, st, role);
        float[] a = readWav(takeFile(id, lid, s.getInt("chosen")));
        float[] o = applyPost(a, post, post.optBoolean("enabled") ? roleBaseF0(id, role) : 0);
        writeWav(outFile(id, lid), o);
        s.put("dur", Math.round(o.length * 100.0 / SR) / 100.0);
        saveState(id, st);
    }

    /** Change a role's post settings on the phone (overrides the project file) and re-process its lines. */
    public int setRolePost(String id, String role, JSONObject post, Progress pr) throws Exception {
        JSONObject st = state(id);
        JSONObject roles = st.optJSONObject("roles");
        if (roles == null) { roles = new JSONObject(); st.put("roles", roles); }
        JSONObject r = roles.optJSONObject(role);
        if (r == null) { r = new JSONObject(); roles.put(role, r); }
        if (post == null) r.remove("post"); else r.put("post", post);
        saveState(id, st);
        invalidateBase(id, role);
        return repost(id, role, pr);
    }

    public int repost(String id, String role, Progress pr) throws Exception {
        JSONObject p = project(id);
        JSONArray lines = p.getJSONArray("lines");
        int n = 0;
        for (int i = 0; i < lines.length(); i++) {
            if (pr != null && pr.cancelled()) break;
            JSONObject l = lines.getJSONObject(i);
            if (role != null && !l.optString("role").equals(role)) continue;
            JSONObject s = state(id).getJSONObject("lines").optJSONObject(l.getString("id"));
            if (s == null || s.optInt("chosen", -1) < 0) continue;
            postLine(id, l.getString("id"));
            n++;
            if (pr != null) pr.line(id, l.getString("id"), "post", new JSONObject());
        }
        return n;
    }

    /** Preview the post settings on one line without saving. Returns the preview file. */
    public File previewPost(String id, String lid, JSONObject post) throws Exception {
        JSONObject p = project(id), s = state(id).getJSONObject("lines").optJSONObject(lid);
        if (s == null || s.optInt("chosen", -1) < 0) throw new IllegalArgumentException("這一句還沒錄");
        String role = "";
        JSONArray lines = p.getJSONArray("lines");
        for (int i = 0; i < lines.length(); i++) if (lines.getJSONObject(i).getString("id").equals(lid)) role = lines.getJSONObject(i).optString("role");
        float[] a = readWav(takeFile(id, lid, s.getInt("chosen")));
        JSONObject q = new JSONObject(post.toString()).put("enabled", true);
        File f = new File(dir(id), "preview.wav");
        writeWav(f, applyPost(a, q, roleBaseF0(id, role)));
        return f;
    }

    // ------------------------------------------------------------------ export (game-voice-v1)
    public interface Mp3 { byte[] encode(short[] pcm, int rate, int kbps); }

    /** Writes export/all.txt + export/map.txt. Returns info JSON. */
    public JSONObject export(String id, Mp3 mp3) throws Exception {
        JSONObject p = project(id), st = state(id), ls = st.getJSONObject("lines");
        JSONObject out = p.optJSONObject("output") != null ? p.optJSONObject("output") : new JSONObject();
        double lead = out.optDouble("leadSec", 0.5), gap = out.optDouble("gapSec", 0.4);
        int kbps = out.optInt("mp3Kbps", 64);
        JSONArray lines = p.getJSONArray("lines");
        List<float[]> parts = new ArrayList<>();
        JSONObject map = new JSONObject();
        double pos = lead;
        int missing = 0, total = 0;
        Map<String, Boolean> seen = new LinkedHashMap<>();
        for (int i = 0; i < lines.length(); i++) {
            JSONObject l = lines.getJSONObject(i);
            String lid = l.getString("id"), key = keyOf(p, l);
            JSONObject s = ls.optJSONObject(lid);
            File f = outFile(id, lid);
            if (s == null || s.optInt("chosen", -1) < 0 || !f.exists()) { missing++; continue; }
            if (seen.containsKey(key)) continue;
            seen.put(key, true);
            float[] a = readWav(f);
            double d = a.length / (double) SR;
            JSONArray v = new JSONArray();
            v.put(Math.round((pos + MP3_DELAY) * 1000) / 1000.0);
            v.put(Math.round(d * 1000) / 1000.0);
            v.put(Dsp.lips(a, SR));
            map.put(key, v);
            parts.add(a);
            pos += d + gap;
            total++;
        }
        if (total == 0) throw new IllegalStateException("還沒有任何錄好的句子");
        int n = (int) Math.round(lead * SR);
        for (float[] a : parts) n += a.length + (int) Math.round(gap * SR);
        short[] pcm = new short[n];
        int o = (int) Math.round(lead * SR);
        for (float[] a : parts) {
            short[] s16 = Dsp.toPcm16(a);
            System.arraycopy(s16, 0, pcm, o, s16.length);
            o += s16.length + (int) Math.round(gap * SR);
        }
        byte[] m = mp3.encode(pcm, SR, kbps);
        if (m == null || m.length == 0) throw new IllegalStateException("MP3 編碼失敗");
        String b64 = java.util.Base64.getEncoder().encodeToString(m);
        File ex = new File(dir(id), "export");
        writeText(new File(ex, "all.txt"), b64);
        writeText(new File(ex, "map.txt"), map.toString());
        JSONObject info = new JSONObject();
        info.put("lines", total).put("missing", missing).put("seconds", Math.round(pos)).put("mb", Math.round(m.length / 1e5) / 10.0);
        return info;
    }

    @SuppressWarnings("unused")
    static Iterator<String> keys(JSONObject o) { return o.keys(); }
}
