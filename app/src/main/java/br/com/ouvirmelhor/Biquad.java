package br.com.ouvirmelhor;

/** Filtro biquad (fórmulas do "Audio EQ Cookbook" de R. Bristow-Johnson). */
final class Biquad {
    private double b0 = 1, b1, b2, a1, a2;
    private double z1, z2;

    void setBypass() {
        b0 = 1;
        b1 = b2 = a1 = a2 = 0;
    }

    void setPeaking(double fs, double f0, double q, double gainDb) {
        if (f0 >= fs * 0.45 || Math.abs(gainDb) < 0.01) {
            setBypass();
            return;
        }
        double a = Math.pow(10.0, gainDb / 40.0);
        double w0 = 2.0 * Math.PI * f0 / fs;
        double cos = Math.cos(w0);
        double alpha = Math.sin(w0) / (2.0 * q);
        double a0 = 1.0 + alpha / a;
        b0 = (1.0 + alpha * a) / a0;
        b1 = (-2.0 * cos) / a0;
        b2 = (1.0 - alpha * a) / a0;
        a1 = (-2.0 * cos) / a0;
        a2 = (1.0 - alpha / a) / a0;
    }

    void setHighPass(double fs, double f0, double q) {
        double w0 = 2.0 * Math.PI * f0 / fs;
        double cos = Math.cos(w0);
        double alpha = Math.sin(w0) / (2.0 * q);
        double a0 = 1.0 + alpha;
        b0 = ((1.0 + cos) / 2.0) / a0;
        b1 = (-(1.0 + cos)) / a0;
        b2 = b0;
        a1 = (-2.0 * cos) / a0;
        a2 = (1.0 - alpha) / a0;
    }

    /** Resposta do filtro (em dB) na frequência f. */
    double magnitudeDb(double fs, double f) {
        double w = 2.0 * Math.PI * f / fs;
        double c1 = Math.cos(w), s1 = Math.sin(w), c2 = Math.cos(2 * w), s2 = Math.sin(2 * w);
        double nr = b0 + b1 * c1 + b2 * c2, ni = -(b1 * s1 + b2 * s2);
        double dr = 1.0 + a1 * c1 + a2 * c2, di = -(a1 * s1 + a2 * s2);
        double num = nr * nr + ni * ni, den = dr * dr + di * di;
        return 10.0 * Math.log10((num + 1e-30) / (den + 1e-30));
    }

    double process(double x) {
        double y = b0 * x + z1;
        z1 = b1 * x - a1 * y + z2;
        z2 = b2 * x - a2 * y;
        return y;
    }
}
