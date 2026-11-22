package br.com.ouvirmelhor;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class MainActivity extends Activity {

    private static final int REQ_PERMS = 10;

    private static final int BG = Color.parseColor("#101418");
    private static final int CARD = Color.parseColor("#1B2229");
    private static final int BTN = Color.parseColor("#2A343E");
    private static final int ACCENT = Color.parseColor("#19B394");
    private static final int BOOST = Color.parseColor("#FF8A3D");
    private static final int STOP = Color.parseColor("#D9534F");
    private static final int TEXT = Color.WHITE;
    private static final int TEXT2 = Color.parseColor("#A9B4BE");

    private final Handler handler = new Handler(Looper.getMainLooper());
    private AudioManager am;

    private Button toggle;
    private TextView statusView, errorView, gainLabel, boostLabel, leftLabel, rightLabel;
    private TextView maxOutLabel, micHint, testStatus, presetLabel;
    private ProgressBar meter;
    private SeekBar gainBar, boostBar, leftBar, rightBar, maxOutBar;
    private final SeekBar[] eqBars = new SeekBar[Params.BANDS_HZ.length];
    private final TextView[] eqLabels = new TextView[Params.BANDS_HZ.length];
    private final Button[] presetButtons = new Button[Params.PRESET_NAMES.length];
    private final Button[] micButtons = new Button[Params.MIC_MODE_NAMES.length];
    private final Button[] boostButtons = new Button[3];
    private static final int[] BOOST_VALUES = {100, 150, 200};
    private Switch swNoise, swComp, swLowCut, swUseTest;
    private AudiogramView audiogram;
    private Button testButton;

    private final Runnable ticker = new Runnable() {
        @Override
        public void run() {
            refresh();
            handler.postDelayed(this, 100);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        am = (AudioManager) getSystemService(AUDIO_SERVICE);
        Params.load(this);
        getWindow().setStatusBarColor(BG);
        getWindow().setNavigationBarColor(BG);
        setContentView(buildUi());
        if (!Params.wasWarned(this)) showSafetyWarning();
    }

    @Override
    protected void onResume() {
        super.onResume();
        applyParamsToUi(); // pode ter voltado do teste de audição
        handler.post(ticker);
    }

    @Override
    protected void onPause() {
        super.onPause();
        handler.removeCallbacks(ticker);
        Params.save(this);
    }

    // ------------------------------------------------------------------ helpers de UI

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
        t.setLineSpacing(0, 1.1f);
        return t;
    }

    private GradientDrawable rounded(int color, float radiusDp) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(dp(radiusDp));
        return g;
    }

    private LinearLayout card(LinearLayout parent, String title, String subtitle) {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setBackground(rounded(CARD, 18));
        c.setPadding(dp(16), dp(14), dp(16), dp(14));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(14);
        parent.addView(c, lp);
        c.addView(text(title, 19, TEXT, true));
        if (subtitle != null) {
            TextView s = text(subtitle, 14, TEXT2, false);
            s.setPadding(0, dp(2), 0, dp(4));
            c.addView(s);
        }
        return c;
    }

    private SeekBar seek(LinearLayout parent, int max, int color) {
        SeekBar s = new SeekBar(this);
        s.setMax(max);
        s.setProgressTintList(ColorStateList.valueOf(color));
        s.setThumbTintList(ColorStateList.valueOf(color));
        s.setPadding(dp(14), dp(12), dp(14), dp(12));
        parent.addView(s, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(48)));
        return s;
    }

    private Button button(String label) {
        Button b = new Button(this);
        b.setText(label);
        b.setAllCaps(false);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        b.setTextColor(TEXT);
        b.setBackground(rounded(BTN, 14));
        b.setPadding(dp(6), 0, dp(6), 0);
        return b;
    }

    private void select(Button b, boolean selected, int color) {
        b.setBackground(rounded(selected ? color : BTN, 14));
        b.setTypeface(selected ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
    }

    /** Adiciona botões em linhas de "perRow". */
    private void grid(LinearLayout parent, Button[] buttons, int perRow, int heightDp) {
        LinearLayout row = null;
        for (int i = 0; i < buttons.length; i++) {
            if (i % perRow == 0) {
                row = new LinearLayout(this);
                row.setOrientation(LinearLayout.HORIZONTAL);
                LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, dp(heightDp));
                rlp.topMargin = dp(8);
                parent.addView(row, rlp);
            }
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.MATCH_PARENT, 1f);
            if (i % perRow != 0) lp.leftMargin = dp(8);
            row.addView(buttons[i], lp);
        }
        int rest = buttons.length % perRow;
        if (rest != 0 && row != null) {
            for (int k = rest; k < perRow; k++) {
                View filler = new View(this);
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, 1, 1f);
                lp.leftMargin = dp(8);
                row.addView(filler, lp);
            }
        }
    }

    private Switch toggleSwitch(LinearLayout parent, String label) {
        Switch s = new Switch(this);
        s.setText(label);
        s.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17);
        s.setTextColor(TEXT);
        s.setPadding(0, dp(10), 0, dp(10));
        s.setThumbTintList(ColorStateList.valueOf(ACCENT));
        parent.addView(s, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return s;
    }

    private abstract static class OnSeek implements SeekBar.OnSeekBarChangeListener {
        @Override public void onStartTrackingTouch(SeekBar s) { }
        @Override public void onStopTrackingTouch(SeekBar s) { }
    }

    // ------------------------------------------------------------------ montagem da tela

    private View buildUi() {
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(BG);
        scroll.setFitsSystemWindows(true);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(18), dp(18), dp(18), dp(36));
        scroll.addView(root);

        root.addView(text("Ouvir Melhor", 30, TEXT, true));
        root.addView(text("Amplificador de som para fone de ouvido", 15, TEXT2, false));

        statusView = text("", 16, TEXT2, false);
        statusView.setPadding(0, dp(12), 0, dp(4));
        root.addView(statusView);

        toggle = new Button(this);
        toggle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 26);
        toggle.setTypeface(Typeface.DEFAULT_BOLD);
        toggle.setTextColor(Color.WHITE);
        toggle.setAllCaps(true);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(92));
        tlp.topMargin = dp(8);
        root.addView(toggle, tlp);
        toggle.setOnClickListener(v -> {
            if (Params.serviceActive) stopService(new Intent(this, AudioService.class));
            else tryStart();
        });

        errorView = text("", 15, Color.parseColor("#FF8080"), false);
        errorView.setPadding(0, dp(8), 0, 0);
        errorView.setVisibility(View.GONE);
        root.addView(errorView);

        // Medidor
        LinearLayout meterCard = card(root, "Som captado pelo microfone", null);
        meter = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        meter.setMax(100);
        meter.setProgressTintList(ColorStateList.valueOf(ACCENT));
        meterCard.addView(meter, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(22)));

        buildVolume(root);
        buildBooster(root);
        buildPresets(root);
        buildHearingTest(root);
        buildEars(root);
        buildMic(root);
        buildEqualizer(root);
        buildOptions(root);
        buildProtection(root);

        LinearLayout tips = card(root, "Dicas para usar melhor", null);
        tips.addView(text(
                "• Deixe o celular perto de quem está falando, ou no bolso da camisa virado para a frente.\n"
                        + "• Para assistir TV: escolha o programa \"TV\" e o microfone \"Apontar o celular\", "
                        + "e deixe o celular perto da caixa de som da TV.\n"
                        + "• Se ouvir chiado, ligue \"Redução de chiado\" ou diminua o Booster.\n"
                        + "• O Bluetooth atrasa o som um pouquinho (normal). Fone com fio não tem atraso.\n"
                        + "• O app continua funcionando com a tela apagada. Para parar, use o botão "
                        + "\"Desligar\" aqui ou na notificação.\n"
                        + "• Se notar dor, zumbido ou piora, pare e procure um fonoaudiólogo.",
                15, TEXT2, false));

        return scroll;
    }

    private void buildVolume(LinearLayout root) {
        LinearLayout c = card(root, "Volume (amplificação)", null);
        gainLabel = text("", 16, TEXT2, false);
        c.addView(gainLabel);
        gainBar = seek(c, Params.MAX_GAIN_DB, ACCENT);
        gainBar.setOnSeekBarChangeListener(new OnSeek() {
            @Override
            public void onProgressChanged(SeekBar s, int p, boolean fromUser) {
                if (fromUser) { Params.gainDb = p; Params.changed(); }
                gainLabel.setText(String.format(Locale.ROOT, "+%d dB", p));
            }
        });
    }

    private void buildBooster(LinearLayout root) {
        LinearLayout c = card(root, "Booster (até 200%)",
                "Força extra para quando o volume no máximo ainda é pouco.");
        boostLabel = text("", 16, TEXT2, false);
        c.addView(boostLabel);
        boostBar = seek(c, Params.BOOST_MAX - Params.BOOST_MIN, BOOST);
        boostBar.setOnSeekBarChangeListener(new OnSeek() {
            @Override
            public void onProgressChanged(SeekBar s, int p, boolean fromUser) {
                if (fromUser) { Params.boostPct = Params.BOOST_MIN + p; Params.changed(); }
                updateBoost();
            }
        });
        String[] names = {"Desligado", "150%", "200%"};
        for (int i = 0; i < 3; i++) {
            final int val = BOOST_VALUES[i];
            boostButtons[i] = button(names[i]);
            boostButtons[i].setOnClickListener(v -> {
                Params.boostPct = val;
                Params.changed();
                applyParamsToUi();
            });
        }
        grid(c, boostButtons, 3, 48);
    }

    private void buildPresets(LinearLayout root) {
        LinearLayout c = card(root, "Programa (ambiente)",
                "Escolha onde a pessoa está. Cada programa ajusta o som para aquele lugar.");
        for (int i = 0; i < presetButtons.length; i++) {
            final int idx = i;
            presetButtons[i] = button(Params.PRESET_NAMES[i]);
            presetButtons[i].setOnClickListener(v -> {
                Params.applyPreset(idx);
                applyParamsToUi();
            });
        }
        grid(c, presetButtons, 2, 54);
        presetLabel = text("", 14, TEXT2, false);
        presetLabel.setPadding(0, dp(8), 0, 0);
        c.addView(presetLabel);
    }

    private void buildHearingTest(LinearLayout root) {
        LinearLayout c = card(root, "Ajuste pela audição da pessoa",
                "Faça o teste de audição (5 min). O app ajusta cada ouvido conforme o resultado.");
        testStatus = text("", 15, TEXT, false);
        testStatus.setPadding(0, dp(6), 0, dp(4));
        c.addView(testStatus);
        audiogram = new AudiogramView(this);
        c.addView(audiogram);
        swUseTest = toggleSwitch(c, "Usar o ajuste do teste");
        swUseTest.setOnCheckedChangeListener((b, checked) -> {
            Params.useTest = checked;
            Params.changed();
        });
        testButton = button("Fazer teste de audição");
        testButton.setBackground(rounded(ACCENT, 14));
        testButton.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        testButton.setTypeface(Typeface.DEFAULT_BOLD);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(60));
        lp.topMargin = dp(8);
        c.addView(testButton, lp);
        testButton.setOnClickListener(v ->
                startActivity(new Intent(this, HearingTestActivity.class)));
    }

    private void buildEars(LinearLayout root) {
        LinearLayout c = card(root, "Volume de cada ouvido",
                "Deixe mais alto o lado que escuta menos.");
        leftLabel = text("", 16, TEXT, false);
        leftLabel.setPadding(0, dp(8), 0, 0);
        c.addView(leftLabel);
        leftBar = seek(c, Params.EAR_MAX_DB - Params.EAR_MIN_DB, ACCENT);
        leftBar.setOnSeekBarChangeListener(new OnSeek() {
            @Override
            public void onProgressChanged(SeekBar s, int p, boolean fromUser) {
                if (fromUser) { Params.earLeftDb = Params.EAR_MIN_DB + p; Params.changed(); }
                leftLabel.setText("Ouvido ESQUERDO: " + earText(Params.EAR_MIN_DB + p));
            }
        });
        rightLabel = text("", 16, TEXT, false);
        c.addView(rightLabel);
        rightBar = seek(c, Params.EAR_MAX_DB - Params.EAR_MIN_DB, ACCENT);
        rightBar.setOnSeekBarChangeListener(new OnSeek() {
            @Override
            public void onProgressChanged(SeekBar s, int p, boolean fromUser) {
                if (fromUser) { Params.earRightDb = Params.EAR_MIN_DB + p; Params.changed(); }
                rightLabel.setText("Ouvido DIREITO: " + earText(Params.EAR_MIN_DB + p));
            }
        });
        Button equal = button("Igualar os dois lados");
        equal.setOnClickListener(v -> {
            Params.earLeftDb = 0;
            Params.earRightDb = 0;
            Params.changed();
            applyParamsToUi();
        });
        c.addView(equal, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(48)));
    }

    private void buildMic(LinearLayout root) {
        LinearLayout c = card(root, "Microfone", null);
        for (int i = 0; i < micButtons.length; i++) {
            final int idx = i;
            micButtons[i] = button(Params.MIC_MODE_NAMES[i]);
            micButtons[i].setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
            micButtons[i].setOnClickListener(v -> {
                if (Params.micMode == idx) return;
                Params.micMode = idx;
                Params.save(this);
                applyParamsToUi();
                if (Params.serviceActive) {
                    startService(new Intent(this, AudioService.class)
                            .setAction(AudioService.ACTION_RESTART));
                }
            });
        }
        grid(c, micButtons, 3, 56);
        micHint = text("", 14, TEXT2, false);
        micHint.setPadding(0, dp(8), 0, 0);
        c.addView(micHint);
    }

    private void buildEqualizer(LinearLayout root) {
        LinearLayout c = card(root, "Equalizador fino (graves → agudos)",
                "Para quem quer ajustar à mão. Somado ao programa e ao teste.");
        for (int i = 0; i < eqBars.length; i++) {
            final int band = i;
            eqLabels[i] = text("", 16, TEXT, false);
            eqLabels[i].setPadding(0, dp(6), 0, 0);
            c.addView(eqLabels[i]);
            eqBars[i] = seek(c, Params.EQ_RANGE_DB * 2, ACCENT);
            eqBars[i].setOnSeekBarChangeListener(new OnSeek() {
                @Override
                public void onProgressChanged(SeekBar s, int p, boolean fromUser) {
                    int db = p - Params.EQ_RANGE_DB;
                    if (fromUser) {
                        Params.eqDb[band] = db;
                        Params.preset = -1;
                        Params.changed();
                        updatePresetButtons();
                    }
                    updateEqLabel(band, db);
                }
            });
        }
    }

    private void buildOptions(LinearLayout root) {
        LinearLayout c = card(root, "Opções de som", null);
        swNoise = toggleSwitch(c, "Redução de chiado e ruído");
        swComp = toggleSwitch(c, "Realçar sons fracos (compressão)");
        swLowCut = toggleSwitch(c, "Cortar graves (vento, motor, batidas)");
        swNoise.setOnCheckedChangeListener((b, v) -> { Params.noiseReduction = v; Params.changed(); });
        swComp.setOnCheckedChangeListener((b, v) -> { Params.compressor = v; Params.changed(); });
        swLowCut.setOnCheckedChangeListener((b, v) -> { Params.lowCut = v; Params.changed(); });
    }

    private void buildProtection(LinearLayout root) {
        LinearLayout c = card(root, "Proteção do ouvido",
                "Volume máximo que o app deixa chegar no fone, mesmo com barulho forte "
                        + "(porta batendo, buzina).");
        maxOutLabel = text("", 16, TEXT2, false);
        c.addView(maxOutLabel);
        maxOutBar = seek(c, 100 - Params.MAXOUT_MIN, ACCENT);
        maxOutBar.setOnSeekBarChangeListener(new OnSeek() {
            @Override
            public void onProgressChanged(SeekBar s, int p, boolean fromUser) {
                if (fromUser) { Params.maxOutPct = Params.MAXOUT_MIN + p; Params.changed(); }
                maxOutLabel.setText(String.format(Locale.ROOT, "Volume máximo: %d%%",
                        Params.MAXOUT_MIN + p));
            }
        });
    }

    // ------------------------------------------------------------------ atualização

    private static String earText(int db) {
        if (db == 0) return "normal";
        int pct = (int) Math.round(Math.pow(10.0, db / 20.0) * 100);
        return String.format(Locale.ROOT, "%s%d dB (%d%%)", db > 0 ? "+" : "", db, pct);
    }

    private void updateBoost() {
        int p = Params.boostPct;
        boostLabel.setText(p <= Params.BOOST_MIN ? "Desligado (100%)"
                : String.format(Locale.ROOT, "%d%%  (+%.1f dB extra)", p, Params.boostDb()));
        for (int i = 0; i < 3; i++) select(boostButtons[i], p == BOOST_VALUES[i], BOOST);
    }

    private void updatePresetButtons() {
        for (int i = 0; i < presetButtons.length; i++) {
            select(presetButtons[i], Params.preset == i, ACCENT);
        }
        presetLabel.setText(Params.preset >= 0
                ? "Programa atual: " + Params.PRESET_NAMES[Params.preset]
                : "Programa atual: personalizado");
    }

    private void updateEqLabel(int band, int db) {
        String name = Params.BAND_LABELS[band] + " · " + Params.BAND_HINTS[band];
        eqLabels[band].setText(String.format(Locale.ROOT, "%s:  %s%d dB", name, db > 0 ? "+" : "", db));
    }

    private void applyParamsToUi() {
        gainBar.setProgress(Math.round(Params.gainDb));
        gainLabel.setText(String.format(Locale.ROOT, "+%d dB", Math.round(Params.gainDb)));
        boostBar.setProgress(Params.boostPct - Params.BOOST_MIN);
        updateBoost();
        updatePresetButtons();

        int l = Math.round(Params.earLeftDb), r = Math.round(Params.earRightDb);
        leftBar.setProgress(l - Params.EAR_MIN_DB);
        rightBar.setProgress(r - Params.EAR_MIN_DB);
        leftLabel.setText("Ouvido ESQUERDO: " + earText(l));
        rightLabel.setText("Ouvido DIREITO: " + earText(r));

        for (int i = 0; i < micButtons.length; i++) select(micButtons[i], Params.micMode == i, ACCENT);
        micHint.setText(Params.MIC_MODE_HINTS[Math.max(0, Math.min(2, Params.micMode))]);

        for (int i = 0; i < eqBars.length; i++) {
            int db = Math.round(Params.eqDb[i]);
            eqBars[i].setProgress(db + Params.EQ_RANGE_DB);
            updateEqLabel(i, db);
        }
        swNoise.setChecked(Params.noiseReduction);
        swComp.setChecked(Params.compressor);
        swLowCut.setChecked(Params.lowCut);

        maxOutBar.setProgress(Params.maxOutPct - Params.MAXOUT_MIN);
        maxOutLabel.setText(String.format(Locale.ROOT, "Volume máximo: %d%%", Params.maxOutPct));

        if (Params.hasTest) {
            testStatus.setText("Teste feito. O vermelho = ouvido direito, X azul = esquerdo "
                    + "(mais para baixo = escuta pior).");
            audiogram.setData(Params.lossL, Params.lossR);
            audiogram.setVisibility(View.VISIBLE);
            swUseTest.setVisibility(View.VISIBLE);
            swUseTest.setChecked(Params.useTest);
            testButton.setText("Refazer teste de audição");
        } else {
            testStatus.setText("Nenhum teste feito ainda.");
            audiogram.setVisibility(View.GONE);
            swUseTest.setVisibility(View.GONE);
            testButton.setText("Fazer teste de audição");
        }
    }

    private void refresh() {
        boolean on = Params.serviceActive;
        toggle.setText(on ? "Desligar" : "Ligar");
        toggle.setBackground(rounded(on ? STOP : ACCENT, 24));

        AudioDeviceInfo hp = AudioRouting.findHeadphone(am);
        statusView.setText(hp != null
                ? "Fone conectado: " + AudioRouting.describe(hp)
                : "Nenhum fone conectado — conecte o fone Bluetooth");

        if (Params.running) {
            int p = (int) ((Params.inputLevelDb + 70f) / 60f * 100f);
            meter.setProgress(Math.max(0, Math.min(100, p)));
        } else {
            meter.setProgress(0);
        }

        String err = Params.lastError;
        if (err != null && !on) {
            errorView.setText(err);
            errorView.setVisibility(View.VISIBLE);
        } else {
            errorView.setVisibility(View.GONE);
        }
    }

    // --------------------------------------------------------- ligar / permissões

    private void tryStart() {
        List<String> need = new ArrayList<>();
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            need.add(Manifest.permission.RECORD_AUDIO);
        }
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            need.add(Manifest.permission.POST_NOTIFICATIONS);
        }
        if (need.contains(Manifest.permission.RECORD_AUDIO)) {
            requestPermissions(need.toArray(new String[0]), REQ_PERMS);
            return;
        }
        // Notificação é opcional: pede uma vez, mas liga mesmo sem ela
        if (!need.isEmpty() && !getSharedPreferences("ajustes", MODE_PRIVATE).getBoolean("pediuNotif", false)) {
            getSharedPreferences("ajustes", MODE_PRIVATE).edit().putBoolean("pediuNotif", true).apply();
            requestPermissions(need.toArray(new String[0]), REQ_PERMS);
            return;
        }
        startIfHeadphones();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        if (requestCode != REQ_PERMS) return;
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            startIfHeadphones();
        } else {
            new AlertDialog.Builder(this)
                    .setTitle("Permissão do microfone")
                    .setMessage("O app precisa do microfone para captar o som e mandar para o fone. "
                            + "Toque em \"Abrir ajustes\", vá em Permissões e permita o Microfone.")
                    .setPositiveButton("Abrir ajustes", (d, w) -> startActivity(new Intent(
                            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                            Uri.fromParts("package", getPackageName(), null))))
                    .setNegativeButton("Agora não", null)
                    .show();
        }
    }

    private void startIfHeadphones() {
        if (AudioRouting.findHeadphone(am) == null) {
            new AlertDialog.Builder(this)
                    .setTitle("Conecte o fone")
                    .setMessage("Conecte o fone Bluetooth (ou com fio) antes de ligar.\n\n"
                            + "Sem fone o som sairia pelo alto-falante e faria um apito alto.")
                    .setPositiveButton("OK", null)
                    .show();
            return;
        }
        Params.lastError = null;
        Params.save(this);
        startForegroundService(new Intent(this, AudioService.class));
    }

    private void showSafetyWarning() {
        new AlertDialog.Builder(this)
                .setTitle("Antes de começar")
                .setMessage("1. Conecte o fone Bluetooth.\n"
                        + "2. Abaixe o volume do celular.\n"
                        + "3. Toque em LIGAR e aumente aos poucos.\n"
                        + "4. Recomendado: faça o \"Teste de audição\" para o app se ajustar à pessoa.\n\n"
                        + "Som muito alto pode machucar o ouvido. Use o Booster com cuidado.\n\n"
                        + "Este app ajuda a ouvir, mas não substitui um aparelho auditivo "
                        + "receitado por fonoaudiólogo.")
                .setPositiveButton("Entendi", (d, w) -> Params.setWarned(this))
                .setCancelable(false)
                .show();
    }
}
