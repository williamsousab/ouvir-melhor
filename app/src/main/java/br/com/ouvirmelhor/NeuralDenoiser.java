package br.com.ouvirmelhor;

/**
 * Redução de ruído por rede neural (RNNoise). Trabalha a 48 kHz em blocos de 10 ms.
 * Separa a voz do resto: ótimo para conversa em lugar barulhento.
 */
final class NeuralDenoiser {

    static final int FRAME = 480;
    static final boolean AVAILABLE;

    static {
        boolean ok;
        try {
            System.loadLibrary("rnnoise_jni");
            ok = true;
        } catch (Throwable t) {
            ok = false;
        }
        AVAILABLE = ok;
    }

    private static native long nativeCreate();
    private static native void nativeDestroy(long handle);
    private static native float nativeProcess(long handle, float[] frame);

    private long handle;
    private final float[] frame = new float[FRAME];
    private final float[] out = new float[FRAME];
    private final float[] dry = new float[FRAME];
    private int pos = 0;
    private float wet = 1f;
    private volatile float voiceProb = 0f;

    NeuralDenoiser() {
        handle = AVAILABLE ? nativeCreate() : 0;
    }

    boolean ok() {
        return handle != 0;
    }

    /** 0..1: quanto do sinal limpo usar (1 = só o limpo). */
    void setWet(float w) {
        wet = Math.max(0f, Math.min(1f, w));
    }

    float voiceProbability() {
        return voiceProb;
    }

    /** Processa no próprio array, com 10 ms de atraso. */
    void process(float[] x, int n) {
        if (handle == 0) return;
        for (int i = 0; i < n; i++) {
            float y = wet * out[pos] + (1f - wet) * dry[pos];
            frame[pos] = x[i] * 32768f;
            x[i] = y;
            pos++;
            if (pos == FRAME) {
                pos = 0;
                for (int k = 0; k < FRAME; k++) dry[k] = frame[k] / 32768f;
                voiceProb = nativeProcess(handle, frame);
                for (int k = 0; k < FRAME; k++) out[k] = frame[k] / 32768f;
            }
        }
    }

    void release() {
        if (handle != 0) {
            nativeDestroy(handle);
            handle = 0;
        }
    }
}
