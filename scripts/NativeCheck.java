import tw.rfps.voicestudio.Native;
import java.io.FileOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/** Host check of the JNI library: args libDir [talker codec device threads speaker text out.wav]. */
public class NativeCheck {
    public static void main(String[] a) throws Exception {
        System.out.println("cpu variant: " + Native.setup(a[0]));
        for (String g : Native.gpus()) System.out.println("gpu: " + g.replace("\t", " | "));
        if (a.length < 8) return;
        long t0 = System.nanoTime();
        long h = Native.init(a[1], a[2], a[3], Integer.parseInt(a[4]));
        if (h == 0) { System.out.println("init failed: " + Native.lastError()); System.exit(1); }
        double load = (System.nanoTime() - t0) / 1e9;
        t0 = System.nanoTime();
        float[] pcm = Native.synth(h, a[6], "Chinese", a[5], "", 12345, 0, 0);
        double dt = (System.nanoTime() - t0) / 1e9;
        if (pcm == null) { System.out.println("synth failed: " + Native.lastError()); System.exit(1); }
        double dur = pcm.length / 24000.0;
        System.out.printf("device=%s threads=%s load=%.1fs synth=%.1fs audio=%.2fs rtf=%.2f%n", a[3], a[4], load, dt, dur, dt / dur);
        System.out.println(Native.recentLog());
        ByteBuffer b = ByteBuffer.allocate(44 + pcm.length * 2).order(ByteOrder.LITTLE_ENDIAN);
        b.put("RIFF".getBytes()).putInt(36 + pcm.length * 2).put("WAVEfmt ".getBytes()).putInt(16).putShort((short) 1).putShort((short) 1)
         .putInt(24000).putInt(48000).putShort((short) 2).putShort((short) 16).put("data".getBytes()).putInt(pcm.length * 2);
        for (float f : pcm) b.putShort((short) Math.max(-32768, Math.min(32767, Math.round(f * 32767))));
        try (FileOutputStream o = new FileOutputStream(a[7])) { o.write(b.array()); }
        Native.free(h);
    }
}
