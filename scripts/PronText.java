import tw.rfps.voicestudio.Pron;
import tw.rfps.voicestudio.Store;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.File;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;

/** Print, for every line of a project, the text the phone sends to the model (JSON lines). args: pron.json project.json */
public class PronText {
    public static void main(String[] a) throws Exception {
        Store.pron = new Pron(new String(Files.readAllBytes(Paths.get(a[0])), StandardCharsets.UTF_8));
        JSONObject p = new JSONObject(new String(Files.readAllBytes(Paths.get(a[1])), StandardCharsets.UTF_8));
        PrintStream out = new PrintStream(System.out, true, "UTF-8");
        JSONArray lines = p.getJSONArray("lines");
        for (int i = 0; i < lines.length(); i++) {
            JSONObject l = lines.getJSONObject(i);
            Pron.Result r = Store.modelOf(p, null, l);
            JSONArray ch = new JSONArray();
            for (Pron.Change c : r.changes) ch.put(c.from + " " + c.zhuyin);
            out.println(new JSONObject().put("id", l.getString("id")).put("plain", Store.plainOf(p, l)).put("model", r.text).put("changes", ch).toString());
        }
    }
}
