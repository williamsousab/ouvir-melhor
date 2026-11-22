package br.com.ouvirmelhor;

/**
 * Cadeia de processamento (igual à de um aparelho auditivo simples):
 * corte de graves -> compressão WDRC / redução de chiado -> amplificação + booster
 * -> equalizador por ouvido (programa + prescrição do teste) -> volume de cada ouvido
 * -> limitador de saída (volume máximo de proteção).
 */
final class Dsp {

    private static final double EQ_Q = 1.2;
    private static final double LOW_CUT_HZ = 150.0;

    // Compressão WDRC: sons fracos ganham mais, sons fortes ganham menos
    private static final double COMP_THRESHOLD_DB = -45.0;
    private static final double COMP_RATIO = 3.0;
    private static final double COMP_MAKEUP_DB = 10.0;

    // Redução de chiado (expansor): abaixa o som quando está quase em silêncio
    private static final double GATE_THRESHOLD_DB = -60.0;
    private static final double GATE_MAX_DB = 15.0;

    private static final double FULL_CEILING = 0.89; // ~ -1 dBFS

    private final double fs;
    private final Biquad hpf = new Biquad();
    private final Biquad[] eqL = new Biquad[Params.BANDS_HZ.length];
    private final Biquad[] eqR = new Biquad[Params.BANDS_HZ.length];

    private boolean comp, gate;
    private int noiseMode = 0;
    private final SpectralDenoiser spectral;
    private NeuralDenoiser neural;
    private float[] work = new float[256];
    private double ceiling = FULL_CEILING;
    private double targetGain = 1, gain = 1;
    private double targetL = 1, targetR = 1, gainL = 1, gainR = 1;
    private double env = 0, dynGainDb = 0, limEnv = 0;

    private final double atk, rel, gainSmooth, dynSmooth, limRel;

    Dsp(int sampleRate) {
        fs = sampleRate;
        spectral = new SpectralDenoiser(sampleRate);
        for (int i = 0; i < eqL.length; i++) {
            eqL[i] = new Biquad();
            eqR[i] = new Biquad();
        }
        atk = Math.exp(-1.0 / (0.005 * fs));
        rel = Math.exp(-1.0 / (0.150 * fs));
        gainSmooth = Math.exp(-1.0 / (0.030 * fs));
        dynSmooth = Math.exp(-1.0 / (0.010 * fs));
        limRel = Math.exp(-1.0 / (0.080 * fs));
        update();
    }

    void update() {
        comp = Params.compressor;
        gate = Params.noiseReduction;
        noiseMode = Params.noiseMode;
        if (noiseMode == 3 && fs == 48000 && NeuralDenoiser.AVAILABLE) {
            if (neural == null) neural = new NeuralDenoiser();
            if (!neural.ok()) noiseMode = 2;
        } else if (noiseMode == 3) {
            noiseMode = 2; // sem rede neural neste aparelho: usa o filtro forte
        }
        double st = Math.max(0, Math.min(100, Params.noiseStrength)) / 100.0;
        if (noiseMode == 1 || noiseMode == 2) {
            // 0% = quase nada (-6 dB), 100% = máximo (-30 dB)
            spectral.setStrength(1.0 + 2.4 * st, -(6.0 + 24.0 * st));
        } else if (noiseMode == 3) {
            neural.setWet((float) (0.4 + 0.6 * st));
            spectral.setStrength(1.0 + 1.0 * st, -(4.0 + 10.0 * st)); // complemento suave da IA
        }
        Params.neuralActive = noiseMode == 3;
        if (Params.lowCut) hpf.setHighPass(fs, LOW_CUT_HZ, 0.707);
        else hpf.setBypass();

        boolean fit = Params.useTest && Params.hasTest;
        int nb = eqL.length;
        double[] tl = new double[nb], tr = new double[nb];
        for (int i = 0; i < nb; i++) {
            double base = Params.eqDb[i];
            tl[i] = base + (fit ? Params.fitL[i] : 0);
            tr[i] = base + (fit ? Params.fitR[i] : 0);
        }
        double[] gl = solveGains(tl), gr = solveGains(tr);
        for (int i = 0; i < nb; i++) {
            eqL[i].setPeaking(fs, Params.BANDS_HZ[i], EQ_Q, gl[i]);
            eqR[i].setPeaking(fs, Params.BANDS_HZ[i], EQ_Q, gr[i]);
        }
        targetGain = Math.pow(10.0, (Params.gainDb + Params.boostDb()) / 20.0);
        targetL = Math.pow(10.0, Params.earLeftDb / 20.0);
        targetR = Math.pow(10.0, Params.earRightDb / 20.0);
        ceiling = FULL_CEILING * Math.max(Params.MAXOUT_MIN, Math.min(100, Params.maxOutPct)) / 100.0;
    }

    /**
     * As bandas vizinhas se somam; aqui corrigimos os ganhos para que a resposta
     * real em cada frequência central fique igual ao alvo (ajuste iterativo).
     */
    private double[] solveGains(double[] target) {
        int nb = target.length;
        double[] g = target.clone();
        double[] resp = new double[nb];
        Biquad tmp = new Biquad();
        for (int it = 0; it < 40; it++) {
            java.util.Arrays.fill(resp, 0);
            for (int i = 0; i < nb; i++) {
                tmp.setPeaking(fs, Params.BANDS_HZ[i], EQ_Q, g[i]);
                for (int j = 0; j < nb; j++) resp[j] += tmp.magnitudeDb(fs, Params.BANDS_HZ[j]);
            }
            double worst = 0;
            for (int j = 0; j < nb; j++) {
                double err = target[j] - resp[j];
                worst = Math.max(worst, Math.abs(err));
                g[j] = Math.max(-24.0, Math.min(36.0, g[j] + 0.6 * err));
            }
            if (worst < 0.2) break;
        }
        return g;
    }

    void release() {
        if (neural != null) {
            neural.release();
            neural = null;
        }
    }

    /** Entrada mono (n amostras) -> saída estéreo intercalada (2n amostras). */
    void process(short[] in, short[] out, int n) {
        double sumIn = 0, sumOut = 0;
        if (work.length < n) work = new float[n];
        for (int i = 0; i < n; i++) {
            double x = in[i] / 32768.0;
            sumIn += x * x;
            work[i] = (float) hpf.process(x);
        }
        // Redução de ruído: rede neural (voz) e/ou filtro espectral adaptativo
        if (noiseMode == 3 && neural != null) neural.process(work, n);
        if (noiseMode > 0) spectral.process(work, n);

        for (int i = 0; i < n; i++) {
            double x = work[i];

            // Detector de nível para compressão / redução de chiado
            double a = Math.abs(x);
            env = a > env ? atk * env + (1 - atk) * a : rel * env + (1 - rel) * a;
            double gDb = 0;
            if (comp || gate) {
                double envDb = 20.0 * Math.log10(env + 1e-9);
                if (comp) {
                    if (envDb > COMP_THRESHOLD_DB) {
                        gDb -= (envDb - COMP_THRESHOLD_DB) * (1.0 - 1.0 / COMP_RATIO);
                    }
                    gDb += COMP_MAKEUP_DB;
                }
                if (gate && envDb < GATE_THRESHOLD_DB) {
                    gDb -= Math.min(GATE_MAX_DB, (GATE_THRESHOLD_DB - envDb) * 1.5);
                }
            }
            dynGainDb = dynSmooth * dynGainDb + (1 - dynSmooth) * gDb;

            gain = gainSmooth * gain + (1 - gainSmooth) * targetGain;
            gainL = gainSmooth * gainL + (1 - gainSmooth) * targetL;
            gainR = gainSmooth * gainR + (1 - gainSmooth) * targetR;

            double y = x * gain * Math.pow(10.0, dynGainDb / 20.0);

            // Equalizador separado para cada ouvido
            double l = y, r = y;
            for (Biquad f : eqL) l = f.process(l);
            for (Biquad f : eqR) r = f.process(r);
            l *= gainL;
            r *= gainR;

            // Limitador de saída (os dois lados juntos)
            double ay = Math.max(Math.abs(l), Math.abs(r));
            limEnv = ay > limEnv ? ay : limRel * limEnv + (1 - limRel) * ay;
            if (limEnv > ceiling) {
                double k = ceiling / limEnv;
                l *= k;
                r *= k;
            }
            l = Math.max(-1.0, Math.min(1.0, l));
            r = Math.max(-1.0, Math.min(1.0, r));

            sumOut += 0.5 * (l * l + r * r);
            out[2 * i] = (short) (l * 32767.0);
            out[2 * i + 1] = (short) (r * 32767.0);
        }
        if (n > 0) {
            Params.inputLevelDb = (float) (10.0 * Math.log10(sumIn / n + 1e-12));
            Params.outputLevelDb = (float) (10.0 * Math.log10(sumOut / n + 1e-12));
        }
    }
}
