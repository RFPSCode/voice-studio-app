import tw.rfps.voicestudio.Dsp;
import tw.rfps.voicestudio.Store;
import org.json.JSONObject;
import java.io.File;

/** Same processing as the phone: clean speech (no breaths / long pauses) + role post-processing. args: in.wav out.wav postJson maxPause [roleBaseF0] */
public class DspCheck {
    public static void main(String[] a) throws Exception {
        if (a[0].equals("--f0")) {   // print the median pitch of a cleaned file (to compute a role's typical pitch)
            System.out.printf("%.1f%n", Dsp.medianF0(Dsp.tidy(Dsp.cleanSpeech(Store.readWav(new File(a[1])), 24000, 0.3), 24000), 24000));
            return;
        }
        float[] x = Store.readWav(new File(a[0]));
        JSONObject post = new JSONObject(a[2]);
        long t0 = System.currentTimeMillis();
        float[] c = Dsp.tidy(Dsp.cleanSpeech(x, 24000, Double.parseDouble(a[3])), 24000);
        float[] y = Store.applyPost(c, post, a.length > 4 ? Double.parseDouble(a[4]) : 0);
        Store.writeWav(new File(a[1]), y);
        System.out.printf("%s [%s] %.2f s -> clean %.2f s -> post %.2f s, f0 %.0f -> %.0f Hz (%d ms)%n", a[0], post.optString("preset"),
            x.length / 24000.0, c.length / 24000.0, y.length / 24000.0, Dsp.medianF0(x, 24000), Dsp.medianF0(y, 24000), System.currentTimeMillis() - t0);
    }
}
