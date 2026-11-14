package tw.rfps.voicestudio;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * Makes the model read Taiwan Mandarin correctly (spec §2.5):
 *  1. 注音 annotations — 「縫{ㄈㄥˊ}」 or pronunciation entries {from, zhuyin} — become a simplified character
 *     that is always read that way (逢);
 *  2. words whose Taiwan reading differs from the mainland one (星期 ㄒㄧㄥ ㄑㄧˊ) get such characters too;
 *  3. everything else is converted to simplified characters (籃→篮、腳→脚), which the model knows better.
 * Data: assets/pron.json (scripts/build_pron_data.py). The same algorithm is in pc/語音專案編輯器.html.
 */
public final class Pron {
    final Map<String, String> ts = new HashMap<>(), tsp = new HashMap<>(), py2ch = new HashMap<>(), zy2py = new HashMap<>();
    final Map<String, String> tw = new HashMap<>(), twZy = new HashMap<>(), r1 = new HashMap<>();
    String poly = "";
    int maxZy = 1;

    public Pron(String json) throws Exception {
        JSONObject d = new JSONObject(json);
        load(d.getJSONObject("ts"), ts);
        load(d.getJSONObject("tsp"), tsp);
        load(d.getJSONObject("py2ch"), py2ch);
        load(d.getJSONObject("zy2py"), zy2py);
        load(d.getJSONObject("tw"), tw);
        load(d.getJSONObject("twZhuyin"), twZy);
        if (d.has("r1")) load(d.getJSONObject("r1"), r1);
        poly = d.optString("poly", "");
        for (String k : zy2py.keySet()) maxZy = Math.max(maxZy, k.length());
    }

    static void load(JSONObject o, Map<String, String> m) throws Exception {
        Iterator<String> it = o.keys();
        while (it.hasNext()) { String k = it.next(); m.put(k, o.getString(k)); }
    }

    static boolean han(char c) { return c >= 0x4e00 && c <= 0x9fff || c >= 0x3400 && c <= 0x4dbf; }
    static boolean zyChar(char c) { return c >= 0x3105 && c <= 0x312f || c == 'ˊ' || c == 'ˇ' || c == 'ˋ' || c == '˙' || c == ' ' || c == 'ˉ'; }

    /** "ㄒㄧㄥ ㄑㄧˊ" or "ㄒㄧㄥㄑㄧˊ" -> ["xing1","qi2"]; null if not understood. */
    public List<String> zhuyinToPinyin(String zy) {
        List<String> out = new ArrayList<>();
        String s = zy.replace("ˉ", "").replace("　", " ").trim();
        int i = 0;
        while (i < s.length()) {
            if (s.charAt(i) == ' ') { i++; continue; }
            String best = null;
            int bl = 0;
            for (int l = Math.min(maxZy, s.length() - i); l >= 1; l--) {
                String part = s.substring(i, i + l);
                String key = part;
                // neutral tone can be written before or after the syllable
                if (part.endsWith("˙")) key = "˙" + part.substring(0, part.length() - 1);
                if (zy2py.containsKey(key)) { best = zy2py.get(key); bl = l; break; }
            }
            if (best == null) return null;
            out.add(best);
            i += bl;
        }
        return out;
    }

    public static final class Change {
        public final String from, zhuyin, to, kind;
        Change(String from, String zhuyin, String to, String kind) { this.from = from; this.zhuyin = zhuyin; this.to = to; this.kind = kind; }
    }

    public static final class Result {
        public final String text;
        public final List<Change> changes;
        public final String poly;
        Result(String text, List<Change> changes, String poly) { this.text = text; this.changes = changes; this.poly = poly; }
    }

    /**
     * @param text        what to say (traditional characters, may contain 「字{注音}」)
     * @param entries     project "pronunciation" array (entries with "zhuyin"; entries with "to" are applied before, in Store.sayOf)
     * @param taiwan      use the built-in Taiwan reading table
     */
    public Result convert(String text, JSONArray entries, boolean taiwan) {
        List<Change> ch = new ArrayList<>();
        // 1) {from, zhuyin} entries become inline annotations
        if (entries != null) for (int i = 0; i < entries.length(); i++) {
            JSONObject e = entries.optJSONObject(i);
            if (e == null) continue;
            String from = e.optString("from"), zy = e.optString("zhuyin");
            if (from.isEmpty() || zy.isEmpty()) continue;
            text = text.replace(from, from + "{" + zy + "}");
        }
        // 2) walk the text: annotated characters become fixed simplified characters
        StringBuilder plain = new StringBuilder();
        List<Object> segs = new ArrayList<>();   // String = plain text (to convert later), String[] = fixed {text}
        int i = 0;
        while (i < text.length()) {
            char c = text.charAt(i);
            if (c == '{') {
                int j = text.indexOf('}', i);
                if (j > i) {
                    String zy = text.substring(i + 1, j);
                    List<String> py = zhuyinToPinyin(zy);
                    int n = py == null ? 0 : py.size();
                    // take the last n Han characters of the plain text
                    int k = plain.length(), got = 0;
                    while (k > 0 && got < n) { k--; if (han(plain.charAt(k))) got++; else if (!Character.isWhitespace(plain.charAt(k))) { k++; break; } }
                    if (py != null && got == n && n > 0) {
                        String orig = plain.substring(k).replaceAll("\\s", "");
                        StringBuilder rep = new StringBuilder();
                        boolean ok = orig.length() == py.size();
                        for (int q = 0; ok && q < py.size(); q++) {
                            String p = py.get(q), sc = ts.containsKey(orig.substring(q, q + 1)) ? ts.get(orig.substring(q, q + 1)) : orig.substring(q, q + 1);
                            // keep the character when the model already reads it this way; otherwise a character that always does
                            String r = p.equals(r1.get(sc)) ? sc : py2ch.get(p);
                            if (r == null) { ok = false; break; }
                            rep.append(r);
                        }
                        if (ok) {
                            String before = plain.substring(0, k);
                            if (!before.isEmpty()) segs.add(before);
                            segs.add(new String[]{rep.toString()});
                            ch.add(new Change(orig, zy.trim(), rep.toString(), "注音"));
                            plain.setLength(0);
                            i = j + 1;
                            continue;
                        }
                    }
                    i = j + 1;          // unknown annotation: drop it, keep the characters
                    continue;
                }
            }
            plain.append(c);
            i++;
        }
        if (plain.length() > 0) segs.add(plain.toString());
        // 3) Taiwan readings + simplified characters on the plain parts
        StringBuilder out = new StringBuilder();
        for (Object o : segs) {
            if (o instanceof String[]) { out.append(((String[]) o)[0]); continue; }
            String s = (String) o;
            int p = 0;
            while (p < s.length()) {
                String hit = null;
                if (taiwan) for (int l = Math.min(4, s.length() - p); l >= 1; l--) {
                    String w = s.substring(p, p + l);
                    if (tw.containsKey(w)) { hit = w; break; }
                }
                if (hit != null) {
                    out.append(tw.get(hit));
                    ch.add(new Change(hit, twZy.get(hit), tw.get(hit), "台灣讀音"));
                    p += hit.length();
                    continue;
                }
                String ph = null;
                for (int l = Math.min(6, s.length() - p); l >= 2; l--) {
                    String w = s.substring(p, p + l);
                    if (tsp.containsKey(w)) { ph = w; break; }
                }
                if (ph != null) { out.append(tsp.get(ph)); p += ph.length(); continue; }
                char c = s.charAt(p);
                String r = ts.get(String.valueOf(c));
                out.append(r != null ? r : String.valueOf(c));
                p++;
            }
        }
        StringBuilder pl = new StringBuilder();
        for (char c : text.toCharArray()) if (poly.indexOf(c) >= 0 && pl.indexOf(String.valueOf(c)) < 0) pl.append(c);
        return new Result(out.toString(), ch, pl.toString());
    }

    /** Remove 「{注音}」 annotations (for display). */
    public static String stripAnnotations(String s) { return s.replaceAll("\\{[^{}]*\\}", ""); }
}
