package br.com.ouvirmelhor;

/**
 * Redução de ruído espectral adaptativa (filtro de Wiener com SNR "decision-directed").
 * Aprende sozinho o ruído constante (bomba de piscina, ventilador, geladeira, motor,
 * ar-condicionado) acompanhando o mínimo de energia em cada faixa de frequência,
 * e remove só esse ruído, preservando a voz.
 */
final class SpectralDenoiser {

    private static final int N = 512;
    private static final int HOP = N / 2;
    private static final int BINS = N / 2 + 1;

    private final double[] win = new double[N];
    private final double[] re = new double[N];
    private final double[] im = new double[N];
    private final double[] inBuf = new double[N];
    private final double[] outAcc = new double[N];
    private final double[] outHop = new double[HOP];
    private final double[] newHop = new double[HOP];
    private int pos = 0;

    private final double[] smooth = new double[BINS];
    private final double[] noise = new double[BINS];
    private final double[] prevG = new double[BINS];
    private final double[] prevPost = new double[BINS];
    private long frames = 0;

    private final double rise;
    private double alpha = 1.6;   // sobre-subtração
    private double floor = 0.25;  // ganho mínimo (não zera o som)

    private final int[] rev = new int[N];
    private final double[] cosT = new double[N / 2];
    private final double[] sinT = new double[N / 2];

    SpectralDenoiser(int fs) {
        for (int i = 0; i < N; i++) {
            win[i] = Math.sqrt(0.5 - 0.5 * Math.cos(2.0 * Math.PI * i / N));
        }
        // estimativa de ruído pode subir até ~2 dB por segundo
        rise = Math.pow(10.0, (2.0 * HOP / fs) / 10.0);
        int bits = Integer.numberOfTrailingZeros(N);
        for (int i = 0; i < N; i++) rev[i] = Integer.reverse(i) >>> (32 - bits);
        for (int i = 0; i < N / 2; i++) {
            cosT[i] = Math.cos(2.0 * Math.PI * i / N);
            sinT[i] = Math.sin(2.0 * Math.PI * i / N);
        }
        java.util.Arrays.fill(prevG, 1.0);
        java.util.Arrays.fill(prevPost, 1.0);
    }

    void setStrength(double overSubtraction, double floorDb) {
        alpha = overSubtraction;
        floor = Math.pow(10.0, floorDb / 20.0);
    }

    /** Processa no próprio array (atraso de ~11 ms). */
    void process(float[] x, int n) {
        for (int i = 0; i < n; i++) {
            double y = outHop[pos];
            newHop[pos] = x[i];
            x[i] = (float) y;
            pos++;
            if (pos == HOP) {
                pos = 0;
                frame();
            }
        }
    }

    private void frame() {
        System.arraycopy(inBuf, HOP, inBuf, 0, N - HOP);
        System.arraycopy(newHop, 0, inBuf, N - HOP, HOP);
        for (int i = 0; i < N; i++) {
            re[i] = inBuf[i] * win[i];
            im[i] = 0;
        }
        fft(re, im, false);
        frames++;

        for (int k = 0; k < BINS; k++) {
            double p = re[k] * re[k] + im[k] * im[k];
            if (frames == 1) {
                smooth[k] = p;
                noise[k] = p;
            } else {
                smooth[k] = 0.7 * smooth[k] + 0.3 * p;
                if (smooth[k] < noise[k]) noise[k] = 0.8 * noise[k] + 0.2 * smooth[k];
                else noise[k] *= rise;
            }
            double nk = alpha * noise[k] + 1e-20;
            double post = Math.min(p / nk, 1e5);
            double prio = 0.98 * prevG[k] * prevG[k] * prevPost[k] + 0.02 * Math.max(post - 1.0, 0.0);
            double g = prio / (1.0 + prio);
            if (g < floor) g = floor;
            prevG[k] = g;
            prevPost[k] = post;

            re[k] *= g;
            im[k] *= g;
            if (k > 0 && k < N / 2) {
                re[N - k] *= g;
                im[N - k] *= g;
            }
        }

        fft(re, im, true);
        for (int i = 0; i < N; i++) outAcc[i] += re[i] * win[i];
        System.arraycopy(outAcc, 0, outHop, 0, HOP);
        System.arraycopy(outAcc, HOP, outAcc, 0, N - HOP);
        java.util.Arrays.fill(outAcc, N - HOP, N, 0.0);
    }

    /** FFT radix-2 no próprio array. inverse=true já divide por N. */
    private void fft(double[] r, double[] i, boolean inverse) {
        for (int a = 0; a < N; a++) {
            int b = rev[a];
            if (b > a) {
                double t = r[a]; r[a] = r[b]; r[b] = t;
                t = i[a]; i[a] = i[b]; i[b] = t;
            }
        }
        for (int size = 2; size <= N; size <<= 1) {
            int half = size >> 1;
            int step = N / size;
            for (int start = 0; start < N; start += size) {
                for (int j = 0; j < half; j++) {
                    double wr = cosT[j * step];
                    double wi = inverse ? sinT[j * step] : -sinT[j * step];
                    int a = start + j, b = a + half;
                    double tr = r[b] * wr - i[b] * wi;
                    double ti = r[b] * wi + i[b] * wr;
                    r[b] = r[a] - tr;
                    i[b] = i[a] - ti;
                    r[a] += tr;
                    i[a] += ti;
                }
            }
        }
        if (inverse) {
            for (int a = 0; a < N; a++) {
                r[a] /= N;
                i[a] /= N;
            }
        }
    }
}
