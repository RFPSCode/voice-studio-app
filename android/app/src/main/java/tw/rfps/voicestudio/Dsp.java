package tw.rfps.voicestudio;

import java.util.Arrays;

/**
 * Audio helpers that run on the phone (no external libraries):
 * trimming/normalising, lip-sync digits, pitch estimation and the
 * "child voice" post-processing (pitch + formant shift, speed).
 * All audio is mono float PCM in [-1, 1].
 */
public final class Dsp {
    private Dsp() {}

    // ------------------------------------------------------------ basics
    public static short[] toPcm16(float[] x) {
        short[] s = new short[x.length];
        for (int i = 0; i < x.length; i++) {
            float v = Math.max(-1f, Math.min(1f, x[i]));
            s[i] = (short) Math.round(v * 32767f);
        }
        return s;
    }

    public static float[] fromPcm16(short[] s) {
        float[] x = new float[s.length];
        for (int i = 0; i < s.length; i++) x[i] = s[i] / 32768f;
        return x;
    }

    /** Cut leading/trailing silence (keeps a little padding) and normalise loudness. */
    public static float[] tidy(float[] a, int sr) {
        int n = a.length;
        if (n == 0) return a;
        float thr = 500f / 32768f;
        int s = 0, e = n - 1;
        while (s < n && Math.abs(a[s]) <= thr) s++;
        while (e > s && Math.abs(a[e]) <= thr) e--;
        if (s >= n) return new float[0];
        int from = Math.max(0, s - sr / 20), to = Math.min(n, e + sr / 10);
        float[] b = Arrays.copyOfRange(a, from, to);
        double sum = 0; float pk = 1e-6f;
        for (float v : b) { sum += v * v; pk = Math.max(pk, Math.abs(v)); }
        double rms = Math.sqrt(sum / Math.max(1, b.length));
        double g = Math.min((4500.0 / 32768.0) / Math.max(rms, 1e-6), 0.92 / pk);
        for (int i = 0; i < b.length; i++) b[i] = (float) Math.max(-1, Math.min(1, b[i] * g));
        return b;
    }

    /** Mouth openness, one digit (0..9) per 40 ms, same algorithm as the PC tools. */
    public static String lips(float[] a, int sr) {
        int n = (int) (sr * 0.04);
        int m = a.length / n;
        if (m <= 0) return "0";
        double[] r = new double[m];
        for (int i = 0; i < m; i++) {
            double s = 0;
            for (int j = i * n; j < (i + 1) * n; j++) s += a[j] * a[j];
            r[i] = Math.sqrt(s / n);
        }
        double[] srt = r.clone();
        Arrays.sort(srt);
        double lo = srt[(int) (m * .2)] * 1.2, hi = srt[Math.min(m - 1, (int) (m * .95))];
        double[] v = new double[m];
        for (int i = 0; i < m; i++) v[i] = Math.pow(Math.max(0, Math.min(1, (r[i] - lo) / Math.max(1e-9, hi - lo))), 0.8);
        StringBuilder sb = new StringBuilder(m);
        for (int i = 0; i < m; i++) {
            double w = 0.25 * v[Math.max(0, i - 1)] + 0.5 * v[i] + 0.25 * v[Math.min(m - 1, i + 1)];
            sb.append((char) ('0' + (int) Math.round(w * 9)));
        }
        return sb.toString();
    }

    // ------------------------------------------------------------ pitch
    /** Median fundamental frequency of the voiced parts (YIN), 0 when unvoiced. */
    public static double medianF0(float[] x, int sr) {
        int w = (int) (sr * 0.04), hop = (int) (sr * 0.01);
        int tauMin = sr / 600, tauMax = sr / 70;
        if (x.length < w + tauMax) return 0;
        double maxE = 0;
        int frames = (x.length - w - tauMax) / hop;
        double[] energy = new double[frames];
        for (int f = 0; f < frames; f++) {
            double s = 0;
            for (int j = 0; j < w; j++) { float v = x[f * hop + j]; s += v * v; }
            energy[f] = s;
            maxE = Math.max(maxE, s);
        }
        double[] d = new double[tauMax + 1];
        double[] f0s = new double[frames];
        int nv = 0;
        for (int f = 0; f < frames; f++) {
            if (energy[f] < maxE * 0.05) continue;
            int o = f * hop;
            for (int tau = 1; tau <= tauMax; tau++) {
                double s = 0;
                for (int j = 0; j < w; j++) { double dv = x[o + j] - x[o + j + tau]; s += dv * dv; }
                d[tau] = s;
            }
            double run = 0; int best = -1;
            double[] cm = new double[tauMax + 1];
            cm[0] = 1;
            for (int tau = 1; tau <= tauMax; tau++) {
                run += d[tau];
                cm[tau] = run > 0 ? d[tau] * tau / run : 1;
            }
            for (int tau = tauMin; tau < tauMax; tau++) {
                if (cm[tau] < 0.15) {
                    while (tau + 1 < tauMax && cm[tau + 1] < cm[tau]) tau++;
                    best = tau; break;
                }
            }
            if (best < 0) continue;
            double a = cm[best - 1], b = cm[best], c = cm[best + 1];
            double den = a - 2 * b + c;
            double t = best + (Math.abs(den) > 1e-12 ? 0.5 * (a - c) / den : 0);
            f0s[nv++] = sr / t;
        }
        if (nv < 3) return 0;
        double[] v = Arrays.copyOf(f0s, nv);
        Arrays.sort(v);
        return v[nv / 2];
    }

    // ------------------------------------------------------------ FFT
    static void fft(double[] re, double[] im, boolean inv) {
        int n = re.length;
        for (int i = 1, j = 0; i < n; i++) {
            int bit = n >> 1;
            for (; (j & bit) != 0; bit >>= 1) j ^= bit;
            j ^= bit;
            if (i < j) { double t = re[i]; re[i] = re[j]; re[j] = t; t = im[i]; im[i] = im[j]; im[j] = t; }
        }
        for (int len = 2; len <= n; len <<= 1) {
            double ang = 2 * Math.PI / len * (inv ? 1 : -1);
            double wr = Math.cos(ang), wi = Math.sin(ang);
            for (int i = 0; i < n; i += len) {
                double cr = 1, ci = 0;
                for (int k = 0; k < len / 2; k++) {
                    int p = i + k, q = p + len / 2;
                    double xr = re[q] * cr - im[q] * ci, xi = re[q] * ci + im[q] * cr;
                    re[q] = re[p] - xr; im[q] = im[p] - xi;
                    re[p] += xr; im[p] += xi;
                    double nr = cr * wr - ci * wi; ci = cr * wi + ci * wr; cr = nr;
                }
            }
        }
        if (inv) for (int i = 0; i < n; i++) { re[i] /= n; im[i] /= n; }
    }

    public static float[] fit(float[] a, int n) { return a.length == n ? a : Arrays.copyOf(a, n); }

    /** RBJ shelving filter. low=true: low shelf. gainDb +/- */
    public static float[] shelf(float[] x, int sr, double freq, double gainDb, boolean low) {
        if (Math.abs(gainDb) < 0.1) return x;
        double A = Math.pow(10, gainDb / 40), w = 2 * Math.PI * freq / sr, cs = Math.cos(w), sn = Math.sin(w);
        double alpha = sn / 2 * Math.sqrt(2), sq = 2 * Math.sqrt(A) * alpha;
        double b0, b1, b2, a0, a1, a2;
        if (low) {
            b0 = A * ((A + 1) - (A - 1) * cs + sq); b1 = 2 * A * ((A - 1) - (A + 1) * cs); b2 = A * ((A + 1) - (A - 1) * cs - sq);
            a0 = (A + 1) + (A - 1) * cs + sq; a1 = -2 * ((A - 1) + (A + 1) * cs); a2 = (A + 1) + (A - 1) * cs - sq;
        } else {
            b0 = A * ((A + 1) + (A - 1) * cs + sq); b1 = -2 * A * ((A - 1) + (A + 1) * cs); b2 = A * ((A + 1) + (A - 1) * cs - sq);
            a0 = (A + 1) - (A - 1) * cs + sq; a1 = 2 * ((A - 1) - (A + 1) * cs); a2 = (A + 1) - (A - 1) * cs - sq;
        }
        float[] y = new float[x.length];
        double x1 = 0, x2 = 0, y1 = 0, y2 = 0;
        for (int i = 0; i < x.length; i++) {
            double o = (b0 * x[i] + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2) / a0;
            x2 = x1; x1 = x[i]; y2 = y1; y1 = o;
            y[i] = (float) o;
        }
        return y;
    }

    // ------------------------------------------------------------ clean speech
    /** Per-frame (10 ms) voicing via a cheap normalised autocorrelation peak in the 75–500 Hz range. */
    static boolean[] voicedFrames(float[] x, int sr, double[] rmsOut) {
        int hop = sr / 100, w = (int) (sr * 0.03), tMin = sr / 500, tMax = sr / 75;
        int n = rmsOut.length;
        boolean[] v = new boolean[n];
        for (int f = 0; f < n; f++) {
            int o = f * hop;
            double e = 0;
            for (int j = 0; j < hop && o + j < x.length; j++) e += x[o + j] * x[o + j];
            rmsOut[f] = Math.sqrt(e / hop);
            if (o + w + tMax >= x.length) continue;
            double e0 = 0;
            for (int j = 0; j < w; j++) e0 += x[o + j] * x[o + j];
            if (e0 < 1e-7) continue;
            double best = 0;
            for (int t = tMin; t <= tMax; t += 2) {
                double c = 0, e1 = 0;
                for (int j = 0; j < w; j += 2) { c += x[o + j] * x[o + j + t]; e1 += x[o + j + t] * x[o + j + t]; }
                double r = c / Math.sqrt(e0 / 2 * e1 + 1e-12);
                if (r > best) best = r;
            }
            v[f] = best > 0.6;
        }
        return v;
    }

    /**
     * Remove breaths, clicks and long silences that the model adds around and between phrases:
     * keeps only real speech (voiced, or loud consonants next to it), shortens pauses to at most
     * maxPause seconds, and trims the start/end. Words written in the text (哈哈、嗚嗚) are voiced and stay.
     */
    public static float[] cleanSpeech(float[] x, int sr, double maxPause) {
        int hop = sr / 100, n = x.length / hop;
        if (n < 10) return x;
        double[] rms = new double[n];
        boolean[] voiced = voicedFrames(x, sr, rms);
        double[] srt = rms.clone();
        Arrays.sort(srt);
        double p95 = srt[(int) (n * 0.95)];
        if (p95 <= 1e-6) return x;
        boolean[] sp = new boolean[n];
        for (int f = 0; f < n; f++) sp[f] = (voiced[f] && rms[f] > 0.06 * p95) || rms[f] > 0.3 * p95;
        // remove isolated blips (< 40 ms) that are not voiced
        for (int f = 0; f < n; ) {
            if (!sp[f]) { f++; continue; }
            int g = f;
            boolean anyV = false;
            while (g < n && sp[g]) { anyV |= voiced[g]; g++; }
            if (g - f < 4 && !anyV) for (int k = f; k < g; k++) sp[k] = false;
            f = g;
        }
        // grow each speech run: 100 ms back (consonant onsets) and 80 ms forward (decays), while there is some energy
        boolean[] keep = sp.clone();
        for (int f = 0; f < n; f++) {
            if (!sp[f]) continue;
            if (f == 0 || !sp[f - 1]) for (int k = f - 1; k >= Math.max(0, f - 10) && rms[k] > 0.04 * p95; k--) keep[k] = true;
            if (f == n - 1 || !sp[f + 1]) for (int k = f + 1; k <= Math.min(n - 1, f + 8) && rms[k] > 0.02 * p95; k++) keep[k] = true;
        }
        int first = -1, last = -1;
        for (int f = 0; f < n; f++) if (keep[f]) { if (first < 0) first = f; last = f; }
        if (first < 0) return x;
        int maxGap = (int) Math.round(maxPause * 100);
        float[] out = new float[x.length + sr];
        int o = 0;
        int lead = Math.min(first, 5);                     // 50 ms of quiet lead-in
        for (int i = (first - lead) * hop; i < first * hop; i++) out[o++] = 0f;
        for (int f = first; f <= last; ) {
            if (keep[f]) {
                int g = f;
                while (g <= last && keep[g]) g++;
                int a = f * hop, b = Math.min(x.length, g * hop);
                double fin = 0.008 * sr;                       // 8 ms fade-in at the start of every speech run
                for (int i = a; i < b; i++) out[o++] = (float) (x[i] * Math.min(1, (i - a + 1) / fin));
                f = g;
            } else {
                int g = f;
                while (g <= last && !keep[g]) g++;
                int len = Math.min(g - f, maxGap) * hop;      // silence instead of the breath / long pause
                for (int i = 0; i < len; i++) out[o++] = 0f;
                f = g;
            }
        }
        int tail = Math.min(sr / 10, out.length - o);
        for (int i = 0; i < tail; i++) out[o++] = 0f;
        float[] y = Arrays.copyOf(out, o);
        // short fade-out at every speech->silence boundary
        for (int i = 1; i < y.length; i++) if (y[i] == 0f && y[i - 1] != 0f) {
            int k = i - 1, m = (int) (0.006 * sr);
            for (int j = 0; j < m && k - j >= 0; j++) y[k - j] *= (float) j / m;
        }
        return y;
    }
}
