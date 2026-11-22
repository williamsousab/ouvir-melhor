package br.com.ouvirmelhor;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.Locale;

/**
 * Teste de audição caseiro (audiometria tonal simplificada).
 * Para cada ouvido e frequência toca bipes que vão ficando mais altos;
 * a pessoa toca no botão assim que ouvir. Faz uma passada grossa (5 dB)
 * e uma fina (3 dB) para achar o limiar.
 */
public class HearingTestActivity extends Activity {

    private static final int FS = 48000;
    private static final int[] ORDER = {2, 3, 4, 5, 1, 0}; // 1k, 2k, 4k, 8k, 500, 250
    private static final double START_DB = -80, MAX_DB = -3;
    private static final double COARSE_STEP = 5, FINE_STEP = 3;
    private static final long LATENCY_MS = 450; // buffer + Bluetooth + tempo mínimo de reação
    private static final float NO_RESPONSE = (float) (MAX_DB + 6);

    private static final int BG = Color.parseColor("#101418");
    private static final int ACCENT = Color.parseColor("#19B394");
    private static final int TEXT2 = Color.parseColor("#A9B4BE");

    private final Handler ui = new Handler(Looper.getMainLooper());
    private AudioManager am;
    private int savedVolume = -1;

    private volatile long tapTime = 0;
    private volatile boolean cancelled = false;
    private Thread worker;

    private final float[] thrL = new float[6];
    private final float[] thrR = new float[6];

    private TextView stepView, earView;
    private ProgressBar progress;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        am = (AudioManager) getSystemService(AUDIO_SERVICE);
        getWindow().setStatusBarColor(BG);
        getWindow().setNavigationBarColor(BG);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        // O amplificador precisa estar desligado durante o teste
        stopService(new Intent(this, AudioService.class));
        showIntro();
    }

    @Override
    protected void onDestroy() {
        stopWorker();
        restoreVolume();
        super.onDestroy();
    }

    // ------------------------------------------------------------- telas

    private int dp(float v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                getResources().getDisplayMetrics());
    }

    private TextView text(String s, float sp, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setLineSpacing(0, 1.15f);
        return t;
    }

    private Button bigButton(String label, int color, int heightDp) {
        Button btn = new Button(this);
        btn.setText(label);
        btn.setAllCaps(false);
        btn.setTextColor(Color.WHITE);
        btn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
        btn.setTypeface(Typeface.DEFAULT_BOLD);
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(dp(22));
        btn.setBackground(g);
        btn.setLayoutParams(lp(heightDp, 14));
        return btn;
    }

    private LinearLayout.LayoutParams lp(int heightDp, int topDp) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                heightDp > 0 ? dp(heightDp) : ViewGroup.LayoutParams.WRAP_CONTENT);
        p.topMargin = dp(topDp);
        return p;
    }

    private LinearLayout page() {
        ScrollView sv = new ScrollView(this);
        sv.setBackgroundColor(BG);
        sv.setFitsSystemWindows(true);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(20), dp(20), dp(20), dp(32));
        sv.addView(root);
        setContentView(sv);
        return root;
    }

    private void showIntro() {
        LinearLayout root = page();
        root.addView(text("Teste de audição", 28, Color.WHITE, true));
        root.addView(text(
                "Leva uns 5 minutos. Com o resultado, o app ajusta o som de cada ouvido "
                        + "do jeito que a pessoa precisa.\n\n"
                        + "Como fazer:\n"
                        + "1. Vá para um lugar silencioso.\n"
                        + "2. Coloque o fone nos dois ouvidos (o lado certo: E = esquerdo, D = direito).\n"
                        + "3. Vão tocar bipes, um ouvido de cada vez, começando bem baixinho.\n"
                        + "4. Toque no botão grande ASSIM QUE OUVIR o bipe, mesmo que bem fraquinho.\n"
                        + "5. Não mexa no volume do celular durante o teste (o app ajusta sozinho).\n\n"
                        + "Importante: é um teste aproximado, feito em casa. Ele não substitui a "
                        + "audiometria feita por um fonoaudiólogo ou otorrino.",
                17, TEXT2, false), lp(0, 12));

        Button start = bigButton("Começar o teste", ACCENT, 72);
        root.addView(start);
        start.setOnClickListener(v -> {
            if (AudioRouting.findHeadphone(am) == null) {
                new AlertDialog.Builder(this)
                        .setTitle("Conecte o fone")
                        .setMessage("O teste precisa do fone de ouvido conectado.")
                        .setPositiveButton("OK", null)
                        .show();
                return;
            }
            startTest();
        });
        Button back = bigButton("Voltar", Color.parseColor("#2A343E"), 60);
        root.addView(back);
        back.setOnClickListener(v -> finish());
    }

    private void showTesting() {
        LinearLayout root = page();
        earView = text("", 30, Color.WHITE, true);
        earView.setGravity(Gravity.CENTER);
        root.addView(earView, lp(0, 8));
        stepView = text("", 17, TEXT2, false);
        stepView.setGravity(Gravity.CENTER);
        root.addView(stepView, lp(0, 6));

        progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progress.setMax(12);
        root.addView(progress, lp(16, 14));

        Button heard = bigButton("ESTOU OUVINDO", ACCENT, 260);
        heard.setTextSize(TypedValue.COMPLEX_UNIT_SP, 30);
        // Registra no momento do toque (mais preciso que o clique)
        heard.setOnTouchListener((v, ev) -> {
            if (ev.getAction() == MotionEvent.ACTION_DOWN) {
                tapTime = SystemClock.elapsedRealtime();
                v.setAlpha(0.6f);
            } else if (ev.getAction() == MotionEvent.ACTION_UP
                    || ev.getAction() == MotionEvent.ACTION_CANCEL) {
                v.setAlpha(1f);
                v.performClick();
            }
            return true;
        });
        root.addView(heard);

        root.addView(text("Toque assim que ouvir o bipe, mesmo bem baixinho.", 16, TEXT2, false), lp(0, 14));

        Button cancel = bigButton("Cancelar teste", Color.parseColor("#2A343E"), 56);
        root.addView(cancel);
        cancel.setOnClickListener(v -> {
            stopWorker();
            restoreVolume();
            showIntro();
        });
    }

    private void showResult() {
        restoreVolume();
        LinearLayout root = page();
        root.addView(text("Resultado", 28, Color.WHITE, true));

        // Calcula a perda relativa só para mostrar (sem salvar ainda)
        float[] keepL = Params.lossL.clone(), keepR = Params.lossR.clone();
        float[] keepFL = Params.fitL.clone(), keepFR = Params.fitR.clone();
        boolean keepHas = Params.hasTest, keepUse = Params.useTest;
        Params.setTestResult(thrL, thrR);
        float[] lossL = Params.lossL.clone(), lossR = Params.lossR.clone();
        float[] fitL = Params.fitL.clone(), fitR = Params.fitR.clone();
        // volta ao estado anterior até a pessoa confirmar
        System.arraycopy(keepL, 0, Params.lossL, 0, 6);
        System.arraycopy(keepR, 0, Params.lossR, 0, 6);
        System.arraycopy(keepFL, 0, Params.fitL, 0, 6);
        System.arraycopy(keepFR, 0, Params.fitR, 0, 6);
        Params.hasTest = keepHas;
        Params.useTest = keepUse;
        Params.changed();

        AudiogramView chart = new AudiogramView(this);
        chart.setData(lossL, lossR);
        root.addView(chart, lp(0, 12));
        root.addView(text("O vermelho = ouvido direito    X azul = ouvido esquerdo\n"
                + "Quanto mais para baixo, mais difícil de ouvir aquela frequência.",
                14, TEXT2, false), lp(0, 4));

        root.addView(text(summary(lossL, lossR), 17, Color.WHITE, false), lp(0, 16));

        StringBuilder sb = new StringBuilder("Reforço que será aplicado:\n");
        for (int i = 0; i < 6; i++) {
            sb.append(String.format(Locale.ROOT, "%s:  esquerdo +%.0f dB   direito +%.0f dB\n",
                    Params.BAND_LABELS[i], fitL[i], fitR[i]));
        }
        root.addView(text(sb.toString(), 15, TEXT2, false), lp(0, 12));

        Button save = bigButton("Salvar e usar este ajuste", ACCENT, 72);
        root.addView(save);
        save.setOnClickListener(v -> {
            Params.setTestResult(thrL, thrR);
            Params.save(this);
            finish();
        });
        Button redo = bigButton("Refazer o teste", Color.parseColor("#2A343E"), 60);
        root.addView(redo);
        redo.setOnClickListener(v -> startTest());
        Button discard = bigButton("Descartar", Color.parseColor("#2A343E"), 60);
        root.addView(discard);
        discard.setOnClickListener(v -> finish());
    }

    private String summary(float[] l, float[] r) {
        StringBuilder s = new StringBuilder();
        float avgL = (l[1] + l[2] + l[3] + l[4]) / 4f;
        float avgR = (r[1] + r[2] + r[3] + r[4]) / 4f;
        float diff = avgL - avgR;
        if (diff >= 8) {
            s.append(String.format(Locale.ROOT,
                    "• O ouvido ESQUERDO parece escutar uns %.0f dB pior que o direito. "
                            + "O app vai reforçar mais o lado esquerdo.\n\n", diff));
        } else if (diff <= -8) {
            s.append(String.format(Locale.ROOT,
                    "• O ouvido DIREITO parece escutar uns %.0f dB pior que o esquerdo. "
                            + "O app vai reforçar mais o lado direito.\n\n", -diff));
        } else {
            s.append("• Os dois ouvidos parecem escutar de forma parecida.\n\n");
        }
        float highs = (l[3] + l[4] + l[5] + r[3] + r[4] + r[5]) / 6f;
        float lows = (l[0] + l[1] + r[0] + r[1]) / 4f;
        if (highs - lows >= 10) {
            s.append("• Os sons agudos (consoantes como S, F, T, CH) estão mais difíceis. "
                    + "Isso é muito comum na perda auditiva da idade. O app vai reforçar os agudos, "
                    + "o que ajuda a entender as palavras.\n\n");
        } else if (lows - highs >= 10) {
            s.append("• Os sons graves estão mais difíceis que os agudos. O app vai reforçar os graves.\n\n");
        }
        int noResp = 0;
        for (int i = 0; i < 6; i++) {
            if (thrL[i] >= NO_RESPONSE) noResp++;
            if (thrR[i] >= NO_RESPONSE) noResp++;
        }
        if (noResp > 0) {
            s.append("• Em ").append(noResp).append(noResp == 1 ? " ponto" : " pontos")
                    .append(" não houve resposta nem no volume máximo do teste.\n\n");
        }
        s.append("Este resultado é uma estimativa. Se a pessoa ainda não fez, vale procurar um "
                + "fonoaudiólogo para uma audiometria completa.");
        return s.toString();
    }

    // ------------------------------------------------------------- teste

    private void startTest() {
        stopWorker();
        cancelled = false;
        int max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
        if (savedVolume < 0) savedVolume = am.getStreamVolume(AudioManager.STREAM_MUSIC);
        try {
            am.setStreamVolume(AudioManager.STREAM_MUSIC, Math.max(1, Math.round(max * 0.5f)), 0);
        } catch (SecurityException ignored) {
        }
        showTesting();
        worker = new Thread(this::runTest, "TesteAudicao");
        worker.start();
    }

    private void stopWorker() {
        cancelled = true;
        Thread w = worker;
        if (w != null) {
            try {
                w.join(1500);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        }
        worker = null;
    }

    private void restoreVolume() {
        if (savedVolume >= 0) {
            try {
                am.setStreamVolume(AudioManager.STREAM_MUSIC, savedVolume, 0);
            } catch (SecurityException ignored) {
            }
            savedVolume = -1;
        }
    }

    private void runTest() {
        AudioTrack track = null;
        try {
            int min = AudioTrack.getMinBufferSize(FS, AudioFormat.CHANNEL_OUT_STEREO,
                    AudioFormat.ENCODING_PCM_FLOAT);
            track = new AudioTrack.Builder()
                    .setAudioAttributes(new AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                            .build())
                    .setAudioFormat(new AudioFormat.Builder()
                            .setSampleRate(FS)
                            .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                            .build())
                    .setBufferSizeInBytes(Math.max(min, FS / 10 * 8))
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .build();
            track.play();
            silence(track, 1200); // acorda o Bluetooth

            int n = 0;
            for (int ear = 1; ear >= 0 && !cancelled; ear--) { // direito, depois esquerdo
                final String earName = ear == 1 ? "Ouvido DIREITO" : "Ouvido ESQUERDO";
                for (int k = 0; k < ORDER.length && !cancelled; k++) {
                    final int band = ORDER[k];
                    final int step = ++n;
                    ui.post(() -> {
                        earView.setText(earName);
                        stepView.setText(String.format(Locale.ROOT, "%s  •  teste %d de 12",
                                Params.BAND_LABELS[band], step));
                        progress.setProgress(step - 1);
                    });
                    float thr = measure(track, ear, Params.BANDS_HZ[band]);
                    if (cancelled) break;
                    if (ear == 0) thrL[band] = thr;
                    else thrR[band] = thr;
                    silence(track, 700);
                }
                if (ear == 1 && !cancelled) {
                    ui.post(() -> {
                        earView.setText("Agora o ouvido ESQUERDO");
                        stepView.setText("Prepare-se…");
                    });
                    silence(track, 2000);
                }
            }
        } catch (Exception e) {
            cancelled = true;
            final String msg = e.getMessage();
            ui.post(() -> new AlertDialog.Builder(this)
                    .setTitle("Erro no teste")
                    .setMessage(msg != null ? msg : "Não foi possível tocar o som.")
                    .setPositiveButton("OK", (d, w) -> showIntro())
                    .show());
        } finally {
            if (track != null) {
                try { track.stop(); } catch (RuntimeException ignored) { }
                track.release();
            }
        }
        if (!cancelled) ui.post(this::showResult);
    }

    private float measure(AudioTrack t, int ear, int freq) {
        double coarse = ascend(t, ear, freq, START_DB, COARSE_STEP, MAX_DB);
        if (Double.isNaN(coarse)) return NO_RESPONSE;
        if (cancelled) return NO_RESPONSE;
        silence(t, 900);
        double from = Math.max(START_DB - 6, coarse - 12);
        double fine = ascend(t, ear, freq, from, FINE_STEP, Math.min(MAX_DB, coarse + 9));
        if (Double.isNaN(fine)) return (float) coarse;
        return (float) fine;
    }

    /** Toca bipes subindo o volume até a pessoa tocar no botão. Devolve o nível ouvido. */
    private double ascend(AudioTrack t, int ear, int freq, double from, double step, double to) {
        long[] starts = new long[64];
        double[] levels = new double[64];
        int count = 0;
        tapTime = 0;
        for (double level = from; level <= to + 0.01 && !cancelled && count < 64; level += step) {
            starts[count] = SystemClock.elapsedRealtime();
            levels[count] = level;
            count++;
            tone(t, ear, freq, level, 300);
            for (int i = 0; i < 70 && !cancelled; i++) {
                long tap = tapTime;
                if (tap != 0) {
                    long heardAt = tap - LATENCY_MS;
                    for (int k = count - 1; k >= 0; k--) {
                        if (starts[k] <= heardAt) return levels[k];
                    }
                    tapTime = 0; // toque antes de qualquer bipe: ignora
                }
                silence(t, 10);
            }
        }
        return Double.NaN;
    }

    private void tone(AudioTrack t, int ear, int freq, double levelDb, int ms) {
        int n = FS * ms / 1000;
        int ramp = FS * 20 / 1000;
        float[] buf = new float[n * 2];
        double amp = Math.pow(10.0, levelDb / 20.0);
        double w = 2.0 * Math.PI * freq / FS;
        for (int i = 0; i < n; i++) {
            double env = 1.0;
            if (i < ramp) env = 0.5 - 0.5 * Math.cos(Math.PI * i / ramp);
            else if (i > n - ramp) env = 0.5 - 0.5 * Math.cos(Math.PI * (n - i) / ramp);
            float s = (float) (amp * env * Math.sin(w * i));
            buf[2 * i] = ear == 0 ? s : 0f;
            buf[2 * i + 1] = ear == 1 ? s : 0f;
        }
        t.write(buf, 0, buf.length, AudioTrack.WRITE_BLOCKING);
    }

    private void silence(AudioTrack t, int ms) {
        float[] z = new float[FS * ms / 1000 * 2];
        t.write(z, 0, z.length, AudioTrack.WRITE_BLOCKING);
    }
}
