import tw.rfps.voicestudio.Dsp;
import tw.rfps.voicestudio.Store;
import java.io.File;

/** Apply the phone's child-voice post-processing to WAV files (used by the model-test workflow). */
public class DspCheck {
    public static void main(String[] a) throws Exception {
        // args: in.wav out.wav pitchHz formant
        float[] x = Store.readWav(new File(a[0]));
        double f0 = Dsp.medianF0(x, 24000), target = Double.parseDouble(a[2]);
        long t0 = System.currentTimeMillis();
        float[] y = Dsp.tidy(Dsp.childFx(x, 24000, f0 > 0 ? target / f0 : 1, Double.parseDouble(a[3]), 1.0), 24000);
        Store.writeWav(new File(a[1]), y);
        System.out.printf("%s f0 %.0f -> %.0f Hz (%d ms)%n", a[0], f0, Dsp.medianF0(y, 24000), System.currentTimeMillis() - t0);
    }
}
