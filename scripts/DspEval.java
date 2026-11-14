import tw.rfps.voicestudio.Dsp;
import tw.rfps.voicestudio.Store;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.*;

/** Post-processing on real takes, as on the phone. args: project.json takesDir outDir -> <id>_<role>_0clean.wav / _2new.wav */
public class DspEval {
    public static void main(String[] a) throws Exception {
        JSONObject p = new JSONObject(new String(Files.readAllBytes(Paths.get(a[0])), "UTF-8"));
        Map<String, JSONObject> roles = new HashMap<>();
        JSONArray rs = p.getJSONArray("roles");
        for (int i = 0; i < rs.length(); i++) roles.put(rs.getJSONObject(i).getString("id"), rs.getJSONObject(i));
        File[] fs = new File(a[1]).listFiles((d, n) -> n.matches("\\d+_[a-z_]+\\.wav") && !n.contains("_child"));
        Arrays.sort(fs);
        Map<String, List<Double>> f0 = new HashMap<>();
        Map<String, float[]> clean = new HashMap<>();
        for (File f : fs) {
            String role = f.getName().replaceAll("^\\d+_|\\.wav$", "");
            float[] c = Dsp.tidy(Dsp.cleanSpeech(Store.readWav(f), 24000, 0.3), 24000);
            clean.put(f.getName(), c);
            f0.computeIfAbsent(role, k -> new ArrayList<>()).add(Dsp.medianF0(c, 24000));
        }
        new File(a[2]).mkdirs();
        for (File f : fs) {
            String role = f.getName().replaceAll("^\\d+_|\\.wav$", "");
            List<Double> l = f0.get(role);
            Collections.sort(l);
            double base = l.get(l.size() / 2);
            float[] c = clean.get(f.getName());
            long t0 = System.nanoTime();
            float[] n = Store.applyPost(c, roles.get(role).getJSONObject("post"), base);
            String b = a[2] + "/" + f.getName().replace(".wav", "");
            Store.writeWav(new File(b + "_0clean.wav"), c);
            Store.writeWav(new File(b + "_2new.wav"), n);
            System.out.printf("%s  %.0f ms  f0 %.0f -> %.0f Hz  %.2f -> %.2f s%n", f.getName(), (System.nanoTime() - t0) / 1e6,
                Dsp.medianF0(c, 24000), Dsp.medianF0(n, 24000), c.length / 24000.0, n.length / 24000.0);
        }
    }
}
