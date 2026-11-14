import tw.rfps.voicestudio.Dsp;
import tw.rfps.voicestudio.Store;
import org.json.JSONObject;
import java.io.File;

/** Apply the phone's post-processing to a WAV file (used by the model-test workflow). args: in.wav out.wav postJson */
public class DspCheck {
    public static void main(String[] a) throws Exception {
        float[] x = Store.readWav(new File(a[0]));
        JSONObject post = new JSONObject(a[2]);
        long t0 = System.currentTimeMillis();
        float[] y = Store.applyPost(x, post, 0);
        Store.writeWav(new File(a[1]), y);
        System.out.printf("%s [%s] f0 %.0f -> %.0f Hz, %.2f -> %.2f s (%d ms)%n", a[0], post.optString("preset"), Dsp.medianF0(x, 24000),
            Dsp.medianF0(y, 24000), x.length / 24000.0, y.length / 24000.0, System.currentTimeMillis() - t0);
    }
}
