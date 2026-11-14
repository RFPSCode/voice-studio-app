package tw.rfps.voicestudio;

import java.util.Arrays;

/**
 * High-quality voice transformation for post-processing (mono float PCM).
 *
 * <ul>
 *   <li>Pitch / speed: TD-PSOLA (pitch-synchronous overlap-add). The waveform of every glottal pulse is
 *       kept, so there is none of the "phasiness" or metallic smearing of an FFT phase vocoder.
 *       F0 is tracked with YIN (FFT difference function, 5 ms hop), pitch marks are placed on the
 *       glottal pulses of a zero-phase low-passed copy, unvoiced sounds are copied with a fixed 10 ms grain
 *       taken at the exact analysis time (no repeated grains, so no buzzy consonants).</li>
 *   <li>Formants (vocal-tract size): band-limited Kaiser-windowed sinc resampling before PSOLA; PSOLA then
 *       sets the pitch and the length, so the spectral envelope moves by exactly the formant factor.</li>
 *   <li>Growl: period doubling (every other pulse quieter) plus a little period jitter — the way a real
 *       rough voice sounds — instead of clipping distortion.</li>
 *   <li>Echo: feedback delay with a darkening filter in the loop (tape / room style repeats).</li>
 *   <li>Reverb: 8-line feedback delay network (Householder matrix), input diffusers, frequency-dependent
 *       decay, pre-delay; dense and smooth instead of the ringing of a small Schroeder reverb.</li>
 *   <li>Output: DC removal, loudness levelling on the speech parts, look-ahead peak limiter.</li>
 * </ul>
 */
public final class Voice {
    private Voice() {}

    // ================================================================ F0 (YIN)
    /** F0 track, one value per hop (Hz, 0 = unvoiced). */
    public static final class Track {
        public final double[] f0;
        public final int hop;
        Track(double[] f0, int hop) { this.f0 = f0; this.hop = hop; }

        /** F0 at sample position i (linear interpolation, 0 when unvoiced). */
        double at(double i) {
            double p = i / hop;
            int a = (int) Math.floor(p);
            if (a < 0) a = 0;
            if (a >= f0.length - 1) return f0[f0.length - 1];
            double fa = f0[a], fb = f0[a + 1], t = p - a;
            if (fa <= 0) return t > 0.5 ? fb : 0;
            if (fb <= 0) return t < 0.5 ? fa : 0;
            return fa + (fb - fa) * t;
        }
    }

    public static Track track(float[] x, int sr) {
        final int hop = sr / 200;                     // 5 ms
        final int tauMin = sr / 650, tauMax = sr / 60;
        final int W = (int) (sr * 0.032);             // 32 ms
        int N = 1;
        while (N < W + tauMax + W) N <<= 1;
        int frames = Math.max(1, x.length / hop);
        double[] f0 = new double[frames];
        // prefix sums of energy
        double[] cs = new double[x.length + 1];
        for (int i = 0; i < x.length; i++) cs[i + 1] = cs[i] + (double) x[i] * x[i];
        double maxE = 0;
        double[] fe = new double[frames];
        for (int f = 0; f < frames; f++) {
            int a = Math.max(0, f * hop - W / 2), b = Math.min(x.length, a + W);
            fe[f] = (cs[b] - cs[a]) / Math.max(1, b - a);
            maxE = Math.max(maxE, fe[f]);
        }
        double[] re = new double[N], im = new double[N], r2 = new double[N], i2 = new double[N];
        double[] d = new double[tauMax + 2];
        for (int f = 0; f < frames; f++) {
            if (fe[f] < maxE * 1e-3) continue;        // below -30 dB: silence
            int o = f * hop - W / 2;
            if (o < 0 || o + W + tauMax >= x.length) continue;
            Arrays.fill(re, 0); Arrays.fill(im, 0); Arrays.fill(r2, 0); Arrays.fill(i2, 0);
            for (int j = 0; j < W + tauMax; j++) re[j] = x[o + j];
            for (int j = 0; j < W; j++) r2[j] = x[o + j];
            Dsp.fft(re, im, false);
            Dsp.fft(r2, i2, false);
            for (int k = 0; k < N; k++) {             // conj(Y) * X
                double a = r2[k], b = -i2[k], c = re[k], e = im[k];
                re[k] = a * c - b * e;
                im[k] = a * e + b * c;
            }
            Dsp.fft(re, im, true);                     // re[tau] = sum x[j] x[j+tau] (fft() inverse is normalised)
            double e0 = cs[o + W] - cs[o];
            for (int tau = 1; tau <= tauMax; tau++) {
                double et = cs[o + tau + W] - cs[o + tau];
                d[tau] = e0 + et - 2 * re[tau];
            }
            double run = 0;
            int best = -1;
            double bestV = 1e9;
            double[] cm = new double[tauMax + 1];
            for (int tau = 1; tau <= tauMax; tau++) {
                run += d[tau];
                cm[tau] = run > 0 ? d[tau] * tau / run : 1;
            }
            for (int tau = tauMin; tau < tauMax; tau++) {
                if (cm[tau] < 0.15) {
                    while (tau + 1 < tauMax && cm[tau + 1] < cm[tau]) tau++;
                    best = tau;
                    break;
                }
                if (cm[tau] < bestV) { bestV = cm[tau]; }
            }
            if (best < 0) {                            // no dip under the threshold: accept the global minimum if clear enough
                if (bestV > 0.3) continue;
                for (int tau = tauMin; tau < tauMax; tau++) if (cm[tau] == bestV) { best = tau; break; }
            }
            if (best <= tauMin || best >= tauMax) continue;
            double a = cm[best - 1], b = cm[best], c = cm[best + 1];
            double den = a - 2 * b + c;
            double t = best + (Math.abs(den) > 1e-12 ? 0.5 * (a - c) / den : 0);
            f0[f] = sr / t;
        }
        // octave errors against the line median
        double med = median(f0);
        if (med > 0) for (int f = 0; f < frames; f++) {
            if (f0[f] <= 0) continue;
            if (f0[f] > med * 1.75) f0[f] /= 2;
            else if (f0[f] < med * 0.56) f0[f] *= 2;
        }
        // 5-point median inside voiced runs, drop runs shorter than 20 ms
        double[] sm = f0.clone();
        for (int f = 0; f < frames; f++) {
            if (f0[f] <= 0) continue;
            double[] w = new double[5];
            int n = 0;
            for (int k = f - 2; k <= f + 2; k++) if (k >= 0 && k < frames && f0[k] > 0) w[n++] = f0[k];
            Arrays.sort(w, 0, n);
            sm[f] = w[n / 2];
        }
        for (int f = 0; f < frames; ) {
            if (sm[f] <= 0) { f++; continue; }
            int g = f;
            while (g < frames && sm[g] > 0) g++;
            if (g - f < 4) for (int k = f; k < g; k++) sm[k] = 0;
            f = g;
        }
        return new Track(sm, hop);
    }

    static double median(double[] v) {
        double[] a = new double[v.length];
        int n = 0;
        for (double x : v) if (x > 0) a[n++] = x;
        if (n == 0) return 0;
        Arrays.sort(a, 0, n);
        return a[n / 2];
    }

    // ================================================================ pitch marks
    static final class Marks {
        int[] pos;
        double[] period;
        boolean[] voiced;
        int n;
    }

    /** Zero-phase 2nd-order Butterworth low-pass (forward + backward). */
    static double[] lowpassZero(float[] x, int sr, double fc) {
        double w = Math.tan(Math.PI * fc / sr), k = w * w, q = Math.sqrt(2);
        double a0 = 1 + q * w + k;
        double b0 = k / a0, b1 = 2 * k / a0, b2 = k / a0, a1 = 2 * (k - 1) / a0, a2 = (1 - q * w + k) / a0;
        double[] y = new double[x.length];
        double x1 = 0, x2 = 0, y1 = 0, y2 = 0;
        for (int i = 0; i < x.length; i++) {
            double o = b0 * x[i] + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2;
            x2 = x1; x1 = x[i]; y2 = y1; y1 = o; y[i] = o;
        }
        x1 = x2 = y1 = y2 = 0;
        for (int i = x.length - 1; i >= 0; i--) {
            double in = y[i];
            double o = b0 * in + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2;
            x2 = x1; x1 = in; y2 = y1; y1 = o; y[i] = o;
        }
        return y;
    }

    static Marks marks(float[] x, int sr, Track tr) {
        final int uStep = sr / 100;                    // 10 ms grains for unvoiced sounds
        double[] lp = lowpassZero(x, sr, 900);
        // polarity of the glottal pulses: the side with the bigger peaks
        double s3 = 0;
        for (int i = 0; i < x.length; i++) if (tr.at(i) > 0) s3 += lp[i] * lp[i] * lp[i];
        double sg = s3 >= 0 ? 1 : -1;
        int cap = x.length / Math.max(1, sr / 700) + 16;
        Marks m = new Marks();
        m.pos = new int[cap]; m.period = new double[cap]; m.voiced = new boolean[cap];
        int i = 0, last = -uStep;
        while (i < x.length) {
            double f = tr.at(i);
            if (f <= 0) {                              // unvoiced: regular marks
                int p = Math.max(i, last + uStep);
                if (p >= x.length) break;
                if (tr.at(p) > 0) { i = p; continue; }
                add(m, p, uStep, false);
                last = p;
                i = p + 1;
                continue;
            }
            // voiced run [i, e)
            int e = i;
            while (e < x.length && tr.at(e) > 0) e += Math.max(1, tr.hop / 2);
            e = Math.min(e, x.length);
            double P = sr / f;
            int s = i, best = i;
            double bv = -1e30;
            for (int k = s; k < Math.min(e, s + (int) P); k++) if (sg * lp[k] > bv) { bv = sg * lp[k]; best = k; }
            if (m.n > 0 && !m.voiced[m.n - 1] && best - m.pos[m.n - 1] < uStep / 2) m.n--;   // too close to the last unvoiced mark
            int mk = best;
            while (mk < e) {
                double Pm = sr / Math.max(50, tr.at(mk) > 0 ? tr.at(mk) : f);
                add(m, mk, Pm, true);
                last = mk;
                int c = mk + (int) Math.round(Pm);
                int lo = c - (int) (0.2 * Pm), hi = c + (int) (0.2 * Pm);
                if (lo >= e) break;
                int nb = c;
                bv = -1e30;
                for (int k = Math.max(lo, mk + 1); k <= Math.min(hi, x.length - 1); k++) if (sg * lp[k] > bv) { bv = sg * lp[k]; nb = k; }
                if (nb <= mk) nb = mk + (int) Math.max(1, Math.round(Pm));
                mk = nb;
                f = tr.at(mk) > 0 ? tr.at(mk) : f;
            }
            i = Math.max(e, last + 1);
        }
        // periods from the neighbouring marks (voiced) for the analysis windows
        for (int k = 0; k < m.n; k++) {
            if (!m.voiced[k]) continue;
            double l = k > 0 && m.voiced[k - 1] ? m.pos[k] - m.pos[k - 1] : m.period[k];
            double r = k + 1 < m.n && m.voiced[k + 1] ? m.pos[k + 1] - m.pos[k] : m.period[k];
            double P = m.period[k];
            m.period[k] = Math.max(0.6 * P, Math.min(1.6 * P, 0.5 * (l + r)));
        }
        return m;
    }

    static void add(Marks m, int p, double per, boolean v) {
        if (m.n == m.pos.length) {
            int c = m.n * 2;
            m.pos = Arrays.copyOf(m.pos, c); m.period = Arrays.copyOf(m.period, c); m.voiced = Arrays.copyOf(m.voiced, c);
        }
        m.pos[m.n] = p; m.period[m.n] = per; m.voiced[m.n] = v; m.n++;
    }

    // ================================================================ TD-PSOLA
    /**
     * @param pitch  new F0 / old F0
     * @param time   new length / old length
     * @param growl  0..1 period doubling + jitter
     */
    public static float[] psola(float[] x, int sr, double pitch, double time, double growl) {
        return psola(x, sr, pitch, time, growl, 1);
    }

    /**
     * @param formant  formant (vocal-tract) factor: every grain is read `formant` times faster (band-limited),
     *                 which moves the spectral envelope while the grain spacing alone sets the pitch.
     */
    public static float[] psola(float[] x, int sr, double pitch, double time, double growl, double formant) {
        if (x.length < sr / 20) return x.clone();
        Track tr = track(x, sr);
        Marks m = marks(x, sr, tr);
        if (m.n < 2) return Math.abs(time - 1) < 1e-6 ? x.clone() : Dsp.fit(x, (int) Math.round(x.length * time));
        int L = (int) Math.round(x.length * time);
        // pass 1: synthesis marks (output position, source centre, voiced, cap = how far the grain may reach)
        int cap0 = 64;
        double[] sPos = new double[cap0];
        int[] sSrc = new int[cap0], capL = new int[cap0], capR = new int[cap0];
        double[] sGain = new double[cap0];
        int ns = 0;
        long rnd = 0x9E3779B97F4A7C15L;
        double ts = m.pos[0] * time;
        int k = 0;
        final int U = sr / 100;
        while (ts < L) {
            double ta = ts / time;
            while (k + 1 < m.n && Math.abs(m.pos[k + 1] - ta) <= Math.abs(m.pos[k] - ta)) k++;
            while (k > 0 && Math.abs(m.pos[k - 1] - ta) < Math.abs(m.pos[k] - ta)) k--;
            boolean v = m.voiced[k]
                && !(ta > m.pos[k] + 0.55 * m.period[k] && !linked(m, k))          // past the end of a voiced run
                && !(ta < m.pos[k] - 0.55 * m.period[k] && !linked(m, k - 1));     // before its start
            if (ns == sPos.length) {
                int c2 = ns * 2;
                sPos = Arrays.copyOf(sPos, c2); sSrc = Arrays.copyOf(sSrc, c2); capL = Arrays.copyOf(capL, c2); capR = Arrays.copyOf(capR, c2); sGain = Arrays.copyOf(sGain, c2);
            }
            sPos[ns] = ts;
            if (v) {
                double Pa = m.period[k];
                int c = m.pos[k];
                // a grain may reach to the neighbouring pulses, never past them
                capL[ns] = linked(m, k - 1) ? c - m.pos[k - 1] : (int) Math.round(Pa);
                capR[ns] = linked(m, k) ? m.pos[k + 1] - c : (int) Math.round(Pa);
                double fwdIn = capR[ns];
                if (formant != 1) { capL[ns] = (int) Math.floor(capL[ns] / formant); capR[ns] = (int) Math.floor(capR[ns] / formant); }
                sSrc[ns] = c;
                sGain[ns] = (growl > 0 && (ns & 1) == 1) ? 1 - 0.55 * growl : 1;
                // the real distance to the next pulse (keeps the natural rhythm, also of creaky / irregular pulses)
                double fwd = fwdIn;
                double Ps = fwd / pitch;
                if (growl > 0) {
                    rnd ^= rnd << 13; rnd ^= rnd >>> 7; rnd ^= rnd << 17;
                    double u = ((rnd >>> 11) * 0x1.0p-53) * 2 - 1;
                    Ps *= 1 + growl * 0.05 * u;
                }
                ns++;
                ts += Math.max(2, Ps);
            } else {
                sSrc[ns] = (int) Math.round(ta);
                capL[ns] = capR[ns] = (int) (3 * U / formant);   // noise grains may stretch to fill a junction
                sGain[ns] = 1;
                ns++;
                double next = ts + U;
                // entering a voiced part: land exactly on its first pulse
                int q = Math.max(0, k - 1);
                while (q < m.n && m.pos[q] * time <= ts + 1) q++;
                while (q < m.n && !m.voiced[q] && m.pos[q] * time < next + U * 0.5) q++;
                if (q < m.n && m.voiced[q] && m.pos[q] * time < next + U * 0.5) next = m.pos[q] * time;
                ts = next;
            }
        }
        if (System.getProperty("vsdbg") != null) for (int i = 0; i < ns; i++) if (Math.abs(sSrc[i] - sPos[i]) > 2) { System.out.println("drift at " + i + " pos " + sPos[i] + " src " + sSrc[i] + " capL " + capL[i] + " capR " + capR[i] + (i > 0 ? " prev " + sPos[i - 1] + "/" + sSrc[i - 1] : "")); break; }
        // pass 2: overlap-add. At each junction between two synthesis marks (distance D) the outgoing and the
        // incoming grain cross-fade over the same stretch of length J = min(D, reach of both); the rest of D is
        // covered by flat window parts as far as each grain may reach. So the windows add up to exactly 1
        // (no build-up when the pitch goes up, nothing changed at pitch 1 / time 1); only when the pitch goes far
        // down and neither pulse may reach further is there a short quiet gap, as in a real low voice.
        int[] rf = new int[ns], rj = new int[ns], lf = new int[ns], lj = new int[ns];
        for (int i = 0; i < ns; i++) {
            if (i == 0) { lf[i] = 0; lj[i] = capL[i]; }
            if (i + 1 >= ns) { rf[i] = 0; rj[i] = capR[i]; continue; }
            int D = (int) Math.round(sPos[i + 1]) - (int) Math.round(sPos[i]);
            int J = Math.max(1, Math.min(D, Math.min(capR[i], capL[i + 1])));
            int E = Math.max(0, D - J);
            int fi = Math.min(E, Math.max(0, capR[i] - J));
            int fn = Math.min(E - fi, Math.max(0, capL[i + 1] - J));
            rf[i] = fi; rj[i] = J; lf[i + 1] = fn; lj[i + 1] = J;
        }
        double[] out = new double[L + sr / 10];
        for (int i = 0; i < ns; i++)
            if (formant == 1) grain(x, sSrc[i], lf[i], lj[i], rf[i], rj[i], out, (int) Math.round(sPos[i]), sGain[i]);
            else grainScaled(x, sSrc[i], formant, lf[i], lj[i], rf[i], rj[i], out, (int) Math.round(sPos[i]), sGain[i]);
        float[] y = new float[L];
        for (int i = 0; i < L; i++) y[i] = (float) out[i];
        return y;
    }

    /** Marks k and k+1 are two pulses of the same voiced stretch. */
    static boolean linked(Marks m, int k) {
        return k >= 0 && k + 1 < m.n && m.voiced[k] && m.voiced[k + 1] && m.pos[k + 1] - m.pos[k] <= 2.5 * Math.min(m.period[k], m.period[k + 1]);
    }

    /** Grain centred on input sample c, added at output position at: flat parts lf / rf, raised-cosine fades lj / rj. */
    static void grain(float[] x, int c, int lf, int lj, int rf, int rj, double[] out, int at, double gain) {
        for (int d = -(lf + lj) + 1; d < rf + rj; d++) {
            int si = c + d, oi = at + d;
            if (oi < 0 || oi >= out.length || si < 0 || si >= x.length) continue;
            double w;
            if (d >= -lf && d <= rf) w = 1;
            else if (d > rf) w = 0.5 + 0.5 * Math.cos(Math.PI * (d - rf) / rj);
            else w = 0.5 + 0.5 * Math.cos(Math.PI * (-lf - d) / lj);
            out[oi] += x[si] * w * gain;
        }
    }

    /** Like grain(), but the source is read `rate` times faster around c (Kaiser-windowed sinc, band-limited). */
    static void grainScaled(float[] x, int c, double rate, int lf, int lj, int rf, int rj, double[] out, int at, double gain) {
        double[] kw = kaiser();
        double fc = Math.min(1.0, 1.0 / rate) * 0.97, half = GR_ZC / fc;
        for (int d = -(lf + lj) + 1; d < rf + rj; d++) {
            int oi = at + d;
            if (oi < 0 || oi >= out.length) continue;
            double w;
            if (d >= -lf && d <= rf) w = 1;
            else if (d > rf) w = 0.5 + 0.5 * Math.cos(Math.PI * (d - rf) / rj);
            else w = 0.5 + 0.5 * Math.cos(Math.PI * (-lf - d) / lj);
            double t = c + d * rate, s = 0;
            int a = (int) Math.ceil(t - half), b = (int) Math.floor(t + half);
            for (int k = Math.max(0, a); k <= Math.min(x.length - 1, b); k++) {
                double u = (t - k) * fc;
                double sinc = Math.abs(u) < 1e-9 ? 1 : Math.sin(Math.PI * u) / (Math.PI * u);
                double p = Math.abs(u) / GR_ZC * (RS_ZC * RS_RES);
                int pi = (int) p;
                double kv = pi + 1 < kw.length ? kw[pi] + (kw[pi + 1] - kw[pi]) * (p - pi) : 0;
                s += x[k] * fc * sinc * kv;
            }
            out[oi] += s * w * gain;
        }
    }

    private static final int GR_ZC = 12;

    // ================================================================ resampling (Kaiser-windowed sinc)
    private static final int RS_ZC = 24, RS_RES = 256;
    private static double[] kaiserTable;

    private static synchronized double[] kaiser() {
        if (kaiserTable != null) return kaiserTable;
        double beta = 9.0, i0b = bessel0(beta);
        double[] t = new double[RS_ZC * RS_RES + 2];
        for (int i = 0; i < t.length; i++) {
            double r = (double) i / (RS_ZC * RS_RES);
            t[i] = r >= 1 ? 0 : bessel0(beta * Math.sqrt(1 - r * r)) / i0b;
        }
        return kaiserTable = t;
    }

    static double bessel0(double x) {
        double s = 1, term = 1;
        for (int k = 1; k < 50; k++) {
            term *= (x / (2 * k)) * (x / (2 * k));
            s += term;
            if (term < 1e-12 * s) break;
        }
        return s;
    }

    /** Read the signal `ratio` times faster (frequencies x ratio, length / ratio), band-limited. */
    public static float[] resample(float[] x, double ratio) {
        if (Math.abs(ratio - 1) < 1e-6) return x.clone();
        double[] kw = kaiser();
        int n = (int) Math.floor(x.length / ratio);
        float[] y = new float[n];
        double fc = Math.min(1.0, 1.0 / ratio) * 0.97;           // cutoff (relative to the input Nyquist)
        double half = RS_ZC / fc;                                 // filter half-length in input samples
        for (int i = 0; i < n; i++) {
            double t = i * ratio;
            int a = (int) Math.ceil(t - half), b = (int) Math.floor(t + half);
            double s = 0;
            for (int k = Math.max(0, a); k <= Math.min(x.length - 1, b); k++) {
                double d = t - k, u = d * fc;
                double sinc = Math.abs(u) < 1e-9 ? 1 : Math.sin(Math.PI * u) / (Math.PI * u);
                double p = Math.abs(u) / RS_ZC * (RS_ZC * RS_RES);
                int pi = (int) p;
                double w = pi + 1 < kw.length ? kw[pi] + (kw[pi + 1] - kw[pi]) * (p - pi) : 0;
                s += x[k] * fc * sinc * w;
            }
            y[i] = (float) s;
        }
        return y;
    }

    // ================================================================ voice change
    /**
     * Pitch x pitchRatio, formants x formant, speed x speed, optional growl — one PSOLA pass after the
     * formant resampling, so the voice goes through only two high-quality steps.
     */
    public static float[] change(float[] x, int sr, double pitchRatio, double formant, double speed, double growl) {
        if (pitchRatio <= 0) pitchRatio = 1;
        if (formant <= 0) formant = 1;
        if (speed <= 0) speed = 1;
        if (Math.abs(formant - 1) < 1e-3) formant = 1;
        return psola(x, sr, pitchRatio, 1 / speed, growl, formant);
    }

    // ================================================================ echo
    /**
     * Feedback echo whose repeats get darker and thinner (low-pass 3.2 kHz + high-pass 180 Hz in the loop), and
     * ducked: while the voice is speaking the repeats are kept down (to 35 %), they come up in the gaps and at
     * the end — the broadcast trick that keeps an echo from blurring the words.
     */
    public static float[] echo(float[] x, int sr, double delayMs, double feedback, double mix) {
        if (mix <= 0 || delayMs <= 0) return x;
        double D = delayMs * sr / 1000.0;
        feedback = Math.max(0, Math.min(0.85, feedback));
        int repeats = feedback > 0 ? (int) Math.ceil(Math.log(0.01) / Math.log(feedback)) : 1;
        int tail = (int) Math.min(sr * 2.5, D * Math.max(1, repeats) + sr * 0.05);
        int n = x.length + tail;
        float[] y = new float[n];
        double[] buf = new double[n];
        double lpA = Math.exp(-2 * Math.PI * 3200 / sr), hpA = Math.exp(-2 * Math.PI * 180 / sr);
        double lp = 0, hpX = 0, hpY = 0;
        // ducking envelope of the dry voice: fast attack (5 ms), slow release (120 ms)
        double att = Math.exp(-1.0 / (0.005 * sr)), relD = Math.exp(-1.0 / (0.12 * sr)), env = 0, pk = 1e-9;
        for (float v : x) pk = Math.max(pk, Math.abs(v));
        for (int i = 0; i < n; i++) {
            double lvl = i < x.length ? Math.abs(x[i]) / pk : 0;
            env = lvl > env ? lvl + (env - lvl) * att : lvl + (env - lvl) * relD;
            double duck = 1 - 0.65 * Math.min(1, env * 4);
            double in = i < x.length ? x[i] : 0;
            double t = i - D;
            double del = 0;
            if (t >= 1) { int a = (int) t; double fr = t - a; del = buf[a] * (1 - fr) + buf[a + 1 < n ? a + 1 : a] * fr; }
            lp = (1 - lpA) * del + lpA * lp;                 // darken
            double hp = hpA * (hpY + lp - hpX);               // thin
            hpX = lp; hpY = hp;
            buf[i] = in + hp * feedback;
            y[i] = (float) (in + mix * hp * duck);
        }
        return y;
    }

    /** Echo without ducking (comparison). */
    public static float[] echoPlain(float[] x, int sr, double delayMs, double feedback, double mix) {
        if (mix <= 0 || delayMs <= 0) return x;
        double D = delayMs * sr / 1000.0;
        feedback = Math.max(0, Math.min(0.85, feedback));
        int repeats = feedback > 0 ? (int) Math.ceil(Math.log(0.01) / Math.log(feedback)) : 1;
        int tail = (int) Math.min(sr * 2.5, D * Math.max(1, repeats) + sr * 0.05);
        int n = x.length + tail;
        float[] y = new float[n];
        double[] buf = new double[n];
        double lpA = Math.exp(-2 * Math.PI * 3200 / sr), hpA = Math.exp(-2 * Math.PI * 180 / sr);
        double lp = 0, hpX = 0, hpY = 0;
        for (int i = 0; i < n; i++) {
            double in = i < x.length ? x[i] : 0;
            double t = i - D;
            double del = 0;
            if (t >= 1) { int a = (int) t; double fr = t - a; del = buf[a] * (1 - fr) + buf[a + 1 < n ? a + 1 : a] * fr; }
            lp = (1 - lpA) * del + lpA * lp;                 // darken
            double hp = hpA * (hpY + lp - hpX);               // thin
            hpX = lp; hpY = hp;
            buf[i] = in + hp * feedback;
            y[i] = (float) (in + mix * hp);
        }
        return y;
    }

    // ================================================================ reverb (FDN)
    /** 8-line feedback delay network reverb, ~1.4 s low / ~0.6 s high decay. mix 0..1. */
    public static float[] reverb(float[] x, int sr, double mix) { return reverb(x, sr, mix, 1.4, 0.0); }

    /** rt60: low-frequency decay (s); duck: 0..1 how much the reverb is held down while the voice speaks. */
    public static float[] reverb(float[] x, int sr, double mix, double rt60, double duck) {
        if (mix <= 0) return x;
        double rtHigh = rt60 * 0.4;
        int tail = (int) (sr * 1.6);
        int n = x.length + tail;
        // pre-delay + input diffusion (4 all-passes)
        int pre = (int) (0.018 * sr);
        double[] in = new double[n];
        for (int i = 0; i < x.length; i++) if (i + pre < n) in[i + pre] = x[i];
        // band-limit what goes into the tank (no mud, no fizz)
        in = onePoleHp(in, sr, 200);
        in = onePoleLp(in, sr, 6500);
        double[] apMs = {4.77, 3.59, 12.73, 9.30};
        for (int a = 0; a < 4; a++) in = allpass(in, (int) (apMs[a] * sr / 1000), a < 2 ? 0.75 : 0.625);
        double[] dMs = {29.7, 37.1, 41.1, 43.7, 53.3, 59.9, 67.3, 73.1};
        int N = 8;
        int[] len = new int[N];
        double[][] line = new double[N][];
        int[] p = new int[N];
        double[] g = new double[N], dampA = new double[N], st = new double[N];
        for (int k = 0; k < N; k++) {
            len[k] = (int) (dMs[k] * sr / 1000) | 1;
            line[k] = new double[len[k]];
            double dsec = (double) len[k] / sr;
            g[k] = Math.pow(10, -3 * dsec / rt60);                       // decay at low frequencies
            double gh = Math.pow(10, -3 * dsec / rtHigh);                  // decay at high frequencies
            // one-pole low-pass in the loop: DC gain 1, Nyquist gain gh/g
            double r = gh / g[k];
            dampA[k] = (1 - r) / (1 + r);
        }
        double[] wet = new double[n], o = new double[N];
        for (int i = 0; i < n; i++) {
            double sum = 0;
            for (int k = 0; k < N; k++) {
                double v = line[k][p[k]];
                st[k] = (1 - dampA[k]) * v + dampA[k] * st[k];
                o[k] = st[k] * g[k];
                sum += o[k];
            }
            double h = 2.0 / N * sum;                                      // Householder: o - 2/N * sum
            double w = 0;
            for (int k = 0; k < N; k++) {
                double fb = o[k] - h;
                line[k][p[k]] = in[i] * (k % 2 == 0 ? 0.5 : -0.5) + fb;
                if (++p[k] >= len[k]) p[k] = 0;
                w += (k % 2 == 0 ? o[k] : -o[k]);
            }
            wet[i] = w;
        }
        // level: wet energy relative to the dry signal
        double ed = 0, ew = 0;
        for (int i = 0; i < x.length; i++) ed += x[i] * x[i];
        for (int i = 0; i < n; i++) ew += wet[i] * wet[i];
        double gw = ew > 0 ? Math.sqrt(ed / ew) * 0.55 * mix : 0;
        float[] y = new float[n];
        double att = Math.exp(-1.0 / (0.01 * sr)), relD = Math.exp(-1.0 / (0.25 * sr)), env = 0, pk = 1e-9;
        for (float v : x) pk = Math.max(pk, Math.abs(v));
        for (int i = 0; i < n; i++) {
            double lvl = i < x.length ? Math.abs(x[i]) / pk : 0;
            env = lvl > env ? lvl + (env - lvl) * att : lvl + (env - lvl) * relD;
            double dk = 1 - duck * Math.min(1, env * 4);
            y[i] = (float) ((i < x.length ? x[i] : 0) + gw * wet[i] * dk);
        }
        return y;
    }

    static double[] allpass(double[] x, int d, double g) {
        double[] buf = new double[Math.max(1, d)];
        double[] y = new double[x.length];
        int p = 0;
        for (int i = 0; i < x.length; i++) {
            double b = buf[p];
            double v = x[i] + g * b;
            y[i] = b - g * v;
            buf[p] = v;
            if (++p >= buf.length) p = 0;
        }
        return y;
    }

    static double[] onePoleLp(double[] x, int sr, double fc) {
        double a = Math.exp(-2 * Math.PI * fc / sr), s = 0;
        double[] y = new double[x.length];
        for (int i = 0; i < x.length; i++) { s = (1 - a) * x[i] + a * s; y[i] = s; }
        return y;
    }

    static double[] onePoleHp(double[] x, int sr, double fc) {
        double a = Math.exp(-2 * Math.PI * fc / sr), px = 0, py = 0;
        double[] y = new double[x.length];
        for (int i = 0; i < x.length; i++) { py = a * (py + x[i] - px); px = x[i]; y[i] = py; }
        return y;
    }

    // ================================================================ output stage
    /**
     * DC removal, speech loudness levelling (RMS of the active 10 ms frames to targetDb, gain limited to
     * -12..+12 dB), then a 3 ms look-ahead limiter at ceiling.
     */
    public static float[] finish(float[] x, int sr, double targetDb, float ceiling) {
        if (x.length == 0) return x;
        double[] d = new double[x.length];
        for (int i = 0; i < x.length; i++) d[i] = x[i];
        d = onePoleHp(d, sr, 25);
        int fr = sr / 100, nf = Math.max(1, d.length / fr);
        double[] e = new double[nf];
        double mx = 0;
        for (int f = 0; f < nf; f++) {
            double s = 0;
            for (int i = f * fr; i < Math.min(d.length, (f + 1) * fr); i++) s += d[i] * d[i];
            e[f] = s / fr;
            mx = Math.max(mx, e[f]);
        }
        double s = 0;
        int c = 0;
        for (int f = 0; f < nf; f++) if (e[f] > mx * 1e-3) { s += e[f]; c++; }
        double rms = c > 0 ? Math.sqrt(s / c) : 0;
        double gain = rms > 1e-6 ? Math.pow(10, targetDb / 20) / rms : 1;
        gain = Math.max(0.25, Math.min(4, gain));
        for (int i = 0; i < d.length; i++) d[i] *= gain;
        // look-ahead limiter
        int la = Math.max(1, (int) (0.003 * sr));
        double rel = Math.exp(-1.0 / (0.08 * sr));
        float[] y = new float[d.length];
        double env = 1;
        // sliding maximum of |d| over [i, i+la]
        double[] need = new double[d.length];
        java.util.ArrayDeque<Integer> q = new java.util.ArrayDeque<>();
        for (int i = d.length - 1; i >= 0; i--) {
            while (!q.isEmpty() && Math.abs(d[q.peekLast()]) <= Math.abs(d[i])) q.pollLast();
            q.addLast(i);
            while (q.peekFirst() > i + la) q.pollFirst();
            double pk = Math.abs(d[q.peekFirst()]);
            need[i] = pk > ceiling ? ceiling / pk : 1;
        }
        // attack: average the needed gain over the look-ahead -> a smooth ramp that is already low at the peak
        double[] cs = new double[d.length + 1];
        for (int i = 0; i < d.length; i++) cs[i + 1] = cs[i] + need[i];
        for (int i = 0; i < d.length; i++) {
            int a = Math.max(0, i - la);
            double target = (cs[i + 1] - cs[a]) / (i + 1 - a);
            env = target < env ? target : target + (env - target) * rel;
            y[i] = (float) Math.max(-ceiling, Math.min(ceiling, d[i] * env));
        }
        return y;
    }
}
