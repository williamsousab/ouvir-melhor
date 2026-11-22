package br.com.ouvirmelhor;

import android.content.Context;
import android.content.SharedPreferences;

/** Configurações compartilhadas entre as telas e o serviço de áudio. */
public final class Params {

    public static final int[] BANDS_HZ = {250, 500, 1000, 2000, 4000, 8000};
    public static final String[] BAND_LABELS = {"250 Hz", "500 Hz", "1 kHz", "2 kHz", "4 kHz", "8 kHz"};
    public static final String[] BAND_HINTS = {"graves", "vogais", "voz", "consoantes", "clareza", "agudos"};

    public static final int MAX_GAIN_DB = 40;
    public static final int EQ_RANGE_DB = 12;

    // ---------------- Programas (como nos aparelhos auditivos) ----------------
    public static final String[] PRESET_NAMES = {
            "Natural", "Conversa", "TV", "Restaurante / barulho",
            "Música", "Ao ar livre (vento)", "Perda nos agudos"
    };
    public static final float[][] PRESET_EQ = {
            {0, 0, 0, 0, 0, 0},
            {-3, 0, 3, 6, 6, 3},
            {0, 0, 3, 5, 4, 2},
            {-8, -4, 2, 6, 5, 0},
            {3, 1, 0, 1, 2, 3},
            {-10, -4, 1, 4, 4, 2},
            {-2, 0, 3, 7, 10, 12},
    };
    // {redução de ruído, compressão, corte de graves}
    public static final boolean[][] PRESET_FLAGS = {
            {false, true, false},
            {true, true, true},
            {false, true, true},
            {true, true, true},
            {false, false, false},
            {true, true, true},
            {true, true, true},
    };

    // ---------------- Modo do microfone ----------------
    public static final String[] MIC_MODE_NAMES = {"Voz (padrão)", "Ambiente natural", "Apontar o celular"};
    public static final String[] MIC_MODE_HINTS = {
            "Melhor para conversar com quem está perto.",
            "Som mais natural, capta tudo em volta.",
            "Use o microfone da câmera: aponte o celular para a TV ou para quem fala."
    };

    // ---------------- Ajustes do usuário ----------------
    public static volatile float gainDb = 12f;
    public static final float[] eqDb = {-3, 0, 3, 6, 6, 3};
    public static volatile int preset = 1;

    public static final int EAR_MIN_DB = -24;
    public static final int EAR_MAX_DB = 12;
    public static volatile float earLeftDb = 0f;
    public static volatile float earRightDb = 0f;

    public static final int BOOST_MIN = 100;
    public static final int BOOST_MAX = 200;
    public static volatile int boostPct = 100;

    /** Volume máximo de saída (proteção), em % do máximo possível. */
    public static final int MAXOUT_MIN = 30;
    public static volatile int maxOutPct = 85;

    public static volatile int micMode = 0;
    public static volatile boolean noiseReduction = true; // = noiseMode > 0

    // ---------------- Redução de ruído ----------------
    public static final String[] NOISE_MODE_NAMES = {"Desligada", "Normal", "Forte", "IA (voz)"};
    public static final String[] NOISE_MODE_HINTS = {
            "Sem filtro: som ambiente completo.",
            "Tira ruídos constantes (ventilador, geladeira, bomba de piscina) sem mexer na voz.",
            "Tira bem mais ruído constante. A voz pode ficar um pouco \"abafada\".",
            "Rede neural que separa a voz do barulho. Melhor para conversar em lugar barulhento; "
                    + "outros sons (música, TV) ficam mais baixos."
    };
    // Programa -> modo de ruído
    public static final int[] PRESET_NOISE = {0, 1, 0, 3, 0, 2, 1};
    public static volatile int noiseMode = 1;
    /** Intensidade da redução de ruído, 0..100%. */
    public static volatile int noiseStrength = 30;
    public static final int STRENGTH_NORMAL = 30, STRENGTH_STRONG = 75;

    public static void setNoiseMode(int m) {
        noiseMode = Math.max(0, Math.min(3, m));
        noiseReduction = noiseMode > 0;
        if (noiseMode == 1) noiseStrength = STRENGTH_NORMAL;
        else if (noiseMode == 2) noiseStrength = STRENGTH_STRONG;
        changed();
    }
    public static volatile boolean compressor = true;
    public static volatile boolean lowCut = true;

    // ---------------- Resultado do teste de audição ----------------
    public static volatile boolean hasTest = false;
    public static volatile boolean useTest = false;
    /** Dificuldade relativa (dB) em cada frequência: 0 = melhor ponto. */
    public static final float[] lossL = new float[6];
    public static final float[] lossR = new float[6];
    /** Ganho prescrito por frequência para cada ouvido. */
    public static final float[] fitL = new float[6];
    public static final float[] fitR = new float[6];

    public static volatile int version = 0;

    // ---------------- Estado do motor ----------------
    public static volatile boolean serviceActive = false;
    public static volatile boolean running = false;
    public static volatile boolean neuralActive = false;
    public static volatile float inputLevelDb = -100f;
    public static volatile float outputLevelDb = -100f;
    public static volatile String lastError = null;

    private Params() {}

    public static void changed() {
        version = version + 1;
    }

    /** 200% -> +10 dB, que o ouvido percebe como o dobro do volume. */
    public static double boostDb() {
        return 10.0 * (Math.log(boostPct / 100.0) / Math.log(2.0));
    }

    public static void applyPreset(int idx) {
        preset = idx;
        System.arraycopy(PRESET_EQ[idx], 0, eqDb, 0, eqDb.length);
        noiseMode = PRESET_NOISE[idx];
        noiseReduction = noiseMode > 0;
        if (noiseMode == 1) noiseStrength = STRENGTH_NORMAL;
        else if (noiseMode == 2) noiseStrength = STRENGTH_STRONG;
        else if (noiseMode == 3) noiseStrength = 60;
        compressor = PRESET_FLAGS[idx][1];
        lowCut = PRESET_FLAGS[idx][2];
        changed();
    }

    // Limiar típico de quem ouve bem, relativo a 1 kHz (forma aproximada da ISO 226).
    private static final float[] NORMAL_REF = {10, 4, 0, -2, -4, 8};
    // Fração da perda que vira ganho (regra da meia-perda, menos ganho nos graves, estilo NAL).
    private static final float[] GAIN_FACTOR = {0.30f, 0.40f, 0.50f, 0.50f, 0.50f, 0.45f};
    private static final float MAX_FIT_DB = 24f;

    /**
     * Recebe os limiares medidos no teste (dB relativos) e calcula a prescrição.
     * Sem calibração o teste mede a FORMA da perda e a diferença entre ouvidos,
     * não o valor absoluto — o volume geral continua no controle de volume.
     */
    public static void setTestResult(float[] thrL, float[] thrR) {
        float best = Float.MAX_VALUE;
        for (int i = 0; i < 6; i++) {
            best = Math.min(best, thrL[i] - NORMAL_REF[i]);
            best = Math.min(best, thrR[i] - NORMAL_REF[i]);
        }
        for (int i = 0; i < 6; i++) {
            lossL[i] = Math.max(0f, thrL[i] - NORMAL_REF[i] - best);
            lossR[i] = Math.max(0f, thrR[i] - NORMAL_REF[i] - best);
            fitL[i] = Math.min(MAX_FIT_DB, lossL[i] * GAIN_FACTOR[i]);
            fitR[i] = Math.min(MAX_FIT_DB, lossR[i] * GAIN_FACTOR[i]);
        }
        hasTest = true;
        useTest = true;
        changed();
    }

    // ---------------- Salvar / carregar ----------------

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences("ajustes", Context.MODE_PRIVATE);
    }

    public static void load(Context c) {
        SharedPreferences p = prefs(c);
        gainDb = p.getFloat("gain", gainDb);
        for (int i = 0; i < 6; i++) {
            eqDb[i] = p.getFloat("eq" + i, eqDb[i]);
            lossL[i] = p.getFloat("lossL" + i, 0f);
            lossR[i] = p.getFloat("lossR" + i, 0f);
            fitL[i] = p.getFloat("fitL" + i, 0f);
            fitR[i] = p.getFloat("fitR" + i, 0f);
        }
        preset = p.getInt("preset", preset);
        earLeftDb = p.getFloat("earL", earLeftDb);
        earRightDb = p.getFloat("earR", earRightDb);
        boostPct = p.getInt("boost", boostPct);
        maxOutPct = p.getInt("maxout", maxOutPct);
        micMode = p.getInt("mic", micMode);
        noiseMode = Math.max(0, Math.min(3, p.getInt("noiseMode", noiseMode)));
        noiseReduction = noiseMode > 0;
        noiseStrength = Math.max(0, Math.min(100, p.getInt("noiseStrength", noiseStrength)));
        compressor = p.getBoolean("comp", compressor);
        lowCut = p.getBoolean("lowcut", lowCut);
        hasTest = p.getBoolean("hasTest", false);
        useTest = p.getBoolean("useTest", false);
        changed();
    }

    public static void save(Context c) {
        SharedPreferences.Editor e = prefs(c).edit();
        e.putFloat("gain", gainDb);
        for (int i = 0; i < 6; i++) {
            e.putFloat("eq" + i, eqDb[i]);
            e.putFloat("lossL" + i, lossL[i]);
            e.putFloat("lossR" + i, lossR[i]);
            e.putFloat("fitL" + i, fitL[i]);
            e.putFloat("fitR" + i, fitR[i]);
        }
        e.putInt("preset", preset);
        e.putFloat("earL", earLeftDb);
        e.putFloat("earR", earRightDb);
        e.putInt("boost", boostPct);
        e.putInt("maxout", maxOutPct);
        e.putInt("mic", micMode);
        e.putInt("noiseMode", noiseMode);
        e.putInt("noiseStrength", noiseStrength);
        e.putBoolean("comp", compressor);
        e.putBoolean("lowcut", lowCut);
        e.putBoolean("hasTest", hasTest);
        e.putBoolean("useTest", useTest);
        e.apply();
    }

    public static boolean wasWarned(Context c) {
        return prefs(c).getBoolean("avisado", false);
    }

    public static void setWarned(Context c) {
        prefs(c).edit().putBoolean("avisado", true).apply();
    }
}
