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

    static double[] hann(int n) {
        double[] w = new double[n];
        for (int i = 0; i < n; i++) w[i] = 0.5 - 0.5 * Math.cos(2 * Math.PI * i / n);
        return w;
    }

    // ------------------------------------------------------------ time stretch (phase vocoder, phase locked)
    /** Returns a signal `factor` times as long with the same pitch. */
    public static float[] stretch(float[] x, double factor) {
        if (Math.abs(factor - 1) < 1e-4) return x.clone();
        final int N = 1024, ha = 256;
        final int hs = (int) Math.round(ha * factor);
        double[] win = hann(N);
        int frames = Math.max(1, (x.length + N) / ha);
        int outLen = frames * hs + N;
        double[] out = new double[outLen], norm = new double[outLen];
        int bins = N / 2 + 1;
        double[] prevPhase = new double[bins], sumPhase = new double[bins];
        double[] re = new double[N], im = new double[N], mag = new double[bins], ph = new double[bins], outPh = new double[bins];
        boolean first = true;
        for (int f = 0; f < frames; f++) {
            int pos = f * ha - N / 2;
            for (int i = 0; i < N; i++) {
                int k = pos + i;
                re[i] = (k >= 0 && k < x.length ? x[k] : 0) * win[i];
                im[i] = 0;
            }
            fft(re, im, false);
            for (int k = 0; k < bins; k++) { mag[k] = Math.hypot(re[k], im[k]); ph[k] = Math.atan2(im[k], re[k]); }
            if (first) {
                System.arraycopy(ph, 0, sumPhase, 0, bins);
                first = false;
            } else {
                // phase advance at spectral peaks; other bins are locked to their peak
                for (int k = 0; k < bins; k++) {
                    double omega = 2 * Math.PI * k * ha / N;
                    double dp = ph[k] - prevPhase[k] - omega;
                    dp -= 2 * Math.PI * Math.round(dp / (2 * Math.PI));
                    outPh[k] = sumPhase[k] + (omega + dp) * hs / ha;
                }
                int lastPeak = 0;
                for (int k = 0; k < bins; k++) {
                    boolean peak = k > 0 && k < bins - 1 && mag[k] >= mag[k - 1] && mag[k] > mag[k + 1];
                    if (peak) lastPeak = k;
                }
                // identity phase locking: assign each bin to the nearest peak
                int[] owner = new int[bins];
                int prevPk = -1;
                for (int k = 0; k < bins; k++) owner[k] = -1;
                java.util.ArrayList<Integer> peaks = new java.util.ArrayList<>();
                for (int k = 1; k < bins - 1; k++) if (mag[k] >= mag[k - 1] && mag[k] > mag[k + 1]) peaks.add(k);
                if (!peaks.isEmpty()) {
                    int pi = 0;
                    for (int k = 0; k < bins; k++) {
                        while (pi + 1 < peaks.size() && Math.abs(peaks.get(pi + 1) - k) <= Math.abs(peaks.get(pi) - k)) pi++;
                        owner[k] = peaks.get(pi);
                    }
                    for (int k = 0; k < bins; k++) {
                        int p = owner[k];
                        if (p != k) outPh[k] = outPh[p] + (ph[k] - ph[p]);
                    }
                }
                System.arraycopy(outPh, 0, sumPhase, 0, bins);
            }
            System.arraycopy(ph, 0, prevPhase, 0, bins);
            for (int k = 0; k < bins; k++) {
                re[k] = mag[k] * Math.cos(sumPhase[k]);
                im[k] = mag[k] * Math.sin(sumPhase[k]);
                if (k > 0 && k < N / 2) { re[N - k] = re[k]; im[N - k] = -im[k]; }
            }
            fft(re, im, true);
            int op = f * hs;
            for (int i = 0; i < N; i++) {
                out[op + i] += re[i] * win[i];
                norm[op + i] += win[i] * win[i];
            }
        }
        int want = (int) Math.round(x.length * factor);
        float[] y = new float[want];
        for (int i = 0; i < want; i++) {
            int k = i + N / 2;
            y[i] = k < outLen && norm[k] > 1e-6 ? (float) (out[k] / norm[k]) : 0f;
        }
        return y;
    }

    /** Resample by ratio (output length = input length / ratio), windowed-sinc. */
    public static float[] resample(float[] x, double ratio) {
        if (Math.abs(ratio - 1) < 1e-6) return x.clone();
        int n = (int) Math.floor(x.length / ratio);
        float[] y = new float[n];
        double cutoff = Math.min(1.0, 1.0 / ratio);
        int half = 12;
        for (int i = 0; i < n; i++) {
            double t = i * ratio;
            int c = (int) Math.floor(t);
            double s = 0, ws = 0;
            for (int k = c - half + 1; k <= c + half; k++) {
                if (k < 0 || k >= x.length) continue;
                double d = t - k;
                double sinc = Math.abs(d) < 1e-9 ? 1 : Math.sin(Math.PI * d * cutoff) / (Math.PI * d * cutoff);
                double win = 0.5 + 0.5 * Math.cos(Math.PI * d / half);
                double wgt = cutoff * sinc * win;
                s += x[k] * wgt; ws += wgt;
            }
            y[i] = (float) (ws != 0 ? s / ws * 1.0 : 0);
        }
        return y;
    }

    // ------------------------------------------------------------ formant warp
    /** Scale the spectral envelope along frequency by `ratio` (>1 moves formants up), keeping pitch. */
    public static float[] warpFormants(float[] x, double ratio) {
        if (Math.abs(ratio - 1) < 1e-3) return x.clone();
        final int N = 1024, hop = 256, lifter = 34;
        double[] win = hann(N);
        int frames = (x.length + N) / hop + 1;
        int outLen = frames * hop + N;
        double[] out = new double[outLen], norm = new double[outLen];
        double[] re = new double[N], im = new double[N], cr = new double[N], ci = new double[N];
        double[] env = new double[N / 2 + 1];
        for (int f = 0; f < frames; f++) {
            int pos = f * hop - N / 2;
            for (int i = 0; i < N; i++) {
                int k = pos + i;
                re[i] = (k >= 0 && k < x.length ? x[k] : 0) * win[i];
                im[i] = 0;
            }
            fft(re, im, false);
            // cepstral envelope
            for (int k = 0; k < N; k++) { cr[k] = Math.log(Math.hypot(re[k], im[k]) + 1e-9); ci[k] = 0; }
            fft(cr, ci, true);
            for (int k = lifter; k <= N - lifter; k++) { cr[k] = 0; ci[k] = 0; }
            fft(cr, ci, false);
            for (int k = 0; k <= N / 2; k++) env[k] = cr[k];
            for (int k = 0; k <= N / 2; k++) {
                double src = k / ratio;           // envelope value that should move to bin k
                int a = (int) Math.floor(src);
                double fr = src - a;
                double target = a + 1 <= N / 2 ? env[a] * (1 - fr) + env[a + 1] * fr : env[N / 2] - 3;
                double g = Math.exp(Math.max(-4, Math.min(4, target - env[k])));
                re[k] *= g; im[k] *= g;
                if (k > 0 && k < N / 2) { re[N - k] = re[k]; im[N - k] = -im[k]; }
            }
            fft(re, im, true);
            int op = f * hop;
            for (int i = 0; i < N; i++) {
                int t = op + i;
                out[t] += re[i] * win[i];
                norm[t] += win[i] * win[i];
            }
        }
        float[] y = new float[x.length];
        for (int i = 0; i < x.length; i++) {
            int k = i + N / 2;
            y[i] = norm[k] > 1e-6 ? (float) (out[k] / norm[k]) : 0f;
        }
        return y;
    }

    // ------------------------------------------------------------ child voice
    /**
     * Child-voice post-processing.
     * @param pitchRatio  new pitch / old pitch (1 = unchanged)
     * @param formant     formant (vocal-tract) scale, 1.15–1.25 sounds like a child
     * @param speed       1 = original speed, 1.1 = 10 % faster
     */
    public static float[] childFx(float[] x, int sr, double pitchRatio, double formant, double speed) {
        if (pitchRatio <= 0) pitchRatio = 1;
        if (speed <= 0) speed = 1;
        // 1) time-stretch so that the following resampling restores the length (and applies the speed)
        float[] y = stretch(x, pitchRatio / speed);
        // 2) resample: pitch and formants both move by pitchRatio
        y = resample(y, pitchRatio);
        // 3) move the formants back from pitchRatio to the wanted formant ratio
        y = warpFormants(y, formant / pitchRatio);
        float pk = 1e-6f;
        for (float v : y) pk = Math.max(pk, Math.abs(v));
        float g = Math.min(1f, 0.97f / pk);
        for (int i = 0; i < y.length; i++) y[i] *= g;
        return y;
    }

    // ------------------------------------------------------------ voice effects
    public static float[] fit(float[] a, int n) { return a.length == n ? a : Arrays.copyOf(a, n); }

    /** Add a pitch-shifted copy of the voice (octave down = demon, octave up = shimmer). */
    public static float[] layer(float[] x, int sr, double pitch, double formant, double mix) {
        if (mix <= 0) return x;
        float[] l = fit(childFx(x, sr, pitch, formant, 1.0), x.length);
        float[] y = new float[x.length];
        for (int i = 0; i < y.length; i++) y[i] = (float) (x[i] + mix * l[i]);
        return y;
    }

    /** Soft-clip drive plus a fast amplitude flutter: rough, growling voice. amount 0..1 */
    public static float[] growl(float[] x, int sr, double amount) {
        if (amount <= 0) return x;
        double g = 1 + amount * 7, norm = Math.tanh(g);
        float[] y = new float[x.length];
        for (int i = 0; i < x.length; i++) {
            double flutter = 1 - 0.35 * amount * (0.5 + 0.5 * Math.sin(2 * Math.PI * 32 * i / sr));
            double d = Math.tanh(g * x[i] * 2.5) / norm / 2.5;
            y[i] = (float) ((x[i] * (1 - amount) + d * amount) * flutter);
        }
        return y;
    }

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

    /** Feedback echo. delayMs 40..600, feedback 0..0.8, mix 0..1. Adds a tail. */
    public static float[] echo(float[] x, int sr, double delayMs, double feedback, double mix) {
        if (mix <= 0 || delayMs <= 0) return x;
        int d = Math.max(1, (int) (delayMs * sr / 1000));
        feedback = Math.max(0, Math.min(0.85, feedback));
        int repeats = feedback > 0 ? (int) Math.ceil(Math.log(0.02) / Math.log(feedback)) : 1;
        int tail = Math.min(sr * 2, d * Math.max(1, repeats));
        float[] y = new float[x.length + tail];
        double[] buf = new double[y.length];
        for (int i = 0; i < y.length; i++) {
            double in = i < x.length ? x[i] : 0;
            double delayed = i >= d ? buf[i - d] : 0;
            buf[i] = in + delayed * feedback;
            y[i] = (float) (in + mix * delayed);
        }
        return y;
    }

    /** Small Schroeder reverb (4 combs + 2 all-passes). mix 0..1. Adds a 1.2 s tail. */
    public static float[] reverb(float[] x, int sr, double mix) {
        if (mix <= 0) return x;
        int tail = (int) (sr * 1.2);
        int n = x.length + tail;
        double[] wet = new double[n];
        int[] combs = {1116, 1188, 1277, 1356};
        for (int c : combs) {
            int d = c * sr / 44100;
            double[] buf = new double[d];
            double fb = 0.80, damp = 0.25, store = 0;
            int p = 0;
            for (int i = 0; i < n; i++) {
                double in = i < x.length ? x[i] : 0;
                double out = buf[p];
                store = out * (1 - damp) + store * damp;
                buf[p] = in + store * fb;
                p = (p + 1) % d;
                wet[i] += out * 0.25;
            }
        }
        for (int a : new int[]{556, 441}) {
            int d = a * sr / 44100;
            double[] buf = new double[d];
            int p = 0;
            for (int i = 0; i < n; i++) {
                double b = buf[p], in = wet[i];
                double out = -in + b;
                buf[p] = in + b * 0.5;
                p = (p + 1) % d;
                wet[i] = out;
            }
        }
        float[] y = new float[n];
        for (int i = 0; i < n; i++) y[i] = (float) ((i < x.length ? x[i] : 0) * (1 - mix * 0.3) + wet[i] * mix);
        return y;
    }

    static float[] peakNorm(float[] y, float peak) {
        float pk = 1e-6f;
        for (float v : y) pk = Math.max(pk, Math.abs(v));
        float g = Math.min(4f, peak / pk);
        for (int i = 0; i < y.length; i++) y[i] *= g;
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
