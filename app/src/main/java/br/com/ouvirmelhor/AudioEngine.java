package br.com.ouvirmelhor;

import android.annotation.SuppressLint;
import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioDeviceInfo;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.AudioTrack;
import android.media.MediaRecorder;
import android.media.audiofx.NoiseSuppressor;
import android.os.Process;

/** Thread que capta o microfone, processa e toca no fone em tempo real. */
final class AudioEngine implements Runnable {

    private static final int FRAMES = 256; // ~5 ms por bloco

    private final Context ctx;
    private final Runnable onFinished;
    private Thread thread;
    private volatile boolean stopRequested;

    AudioEngine(Context context, Runnable onFinished) {
        this.ctx = context.getApplicationContext();
        this.onFinished = onFinished;
    }

    void start() {
        stopRequested = false;
        thread = new Thread(this, "OuvirMelhor-Audio");
        thread.start();
    }

    void stop() {
        stopRequested = true;
        Thread t = thread;
        if (t != null) {
            try {
                t.join(1500);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        }
        thread = null;
    }

    private static int pickSampleRate(AudioManager am) {
        // 48 kHz primeiro: é a taxa que a rede neural de ruído usa
        int[] candidates = {48000, 0, 44100, 32000, 16000};
        String prop = am.getProperty(AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE);
        if (prop != null) {
            try {
                candidates[1] = Integer.parseInt(prop);
            } catch (NumberFormatException ignored) {
            }
        }
        for (int fs : candidates) {
            if (fs <= 0) continue;
            int rec = AudioRecord.getMinBufferSize(fs, AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT);
            int play = AudioTrack.getMinBufferSize(fs, AudioFormat.CHANNEL_OUT_STEREO,
                    AudioFormat.ENCODING_PCM_16BIT);
            if (rec > 0 && play > 0) return fs;
        }
        return -1;
    }

    @SuppressLint("MissingPermission")
    @Override
    public void run() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO);
        AudioManager am = (AudioManager) ctx.getSystemService(Context.AUDIO_SERVICE);

        AudioRecord rec = null;
        AudioTrack track = null;
        Dsp dsp = null;
        NoiseSuppressor ns = null;
        try {
            int fs = pickSampleRate(am);
            if (fs <= 0) throw new IllegalStateException("Este celular não aceitou o formato de áudio.");

            int recMin = AudioRecord.getMinBufferSize(fs, AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT);
            AudioFormat inFormat = new AudioFormat.Builder()
                    .setSampleRate(fs)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                    .build();
            int micMode = Params.micMode;
            int source;
            if (micMode == 1) {
                boolean unprocessed = "true".equals(
                        am.getProperty(AudioManager.PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED));
                source = unprocessed ? MediaRecorder.AudioSource.UNPROCESSED
                        : MediaRecorder.AudioSource.MIC;
            } else if (micMode == 2) {
                source = MediaRecorder.AudioSource.CAMCORDER;
            } else {
                source = MediaRecorder.AudioSource.VOICE_RECOGNITION;
            }
            rec = new AudioRecord.Builder()
                    .setAudioSource(source)
                    .setAudioFormat(inFormat)
                    .setBufferSizeInBytes(Math.max(recMin * 2, FRAMES * 2 * 4))
                    .build();
            if (rec.getState() != AudioRecord.STATE_INITIALIZED) {
                throw new IllegalStateException("Não foi possível abrir o microfone.");
            }
            // Usa sempre o microfone do celular (não o do fone)
            if (micMode != 2) {
                AudioDeviceInfo mic = AudioRouting.findBuiltinMic(am);
                if (mic != null) rec.setPreferredDevice(mic);
            }

            int playMin = AudioTrack.getMinBufferSize(fs, AudioFormat.CHANNEL_OUT_STEREO,
                    AudioFormat.ENCODING_PCM_16BIT);
            AudioFormat outFormat = new AudioFormat.Builder()
                    .setSampleRate(fs)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                    .build();
            AudioAttributes attrs = new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build();
            track = new AudioTrack.Builder()
                    .setAudioAttributes(attrs)
                    .setAudioFormat(outFormat)
                    .setBufferSizeInBytes(Math.max(playMin * 2, FRAMES * 4 * 4))
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
                    .build();
            if (track.getState() != AudioTrack.STATE_INITIALIZED) {
                throw new IllegalStateException("Não foi possível tocar no fone.");
            }

            if (NoiseSuppressor.isAvailable()) {
                try {
                    ns = NoiseSuppressor.create(rec.getAudioSessionId());
                } catch (RuntimeException e) {
                    ns = null;
                }
            }
            boolean nsEnabled = !Params.noiseReduction; // força aplicar na 1ª volta

            dsp = new Dsp(fs);
            int lastVersion = Params.version;
            short[] in = new short[FRAMES];
            short[] out = new short[FRAMES * 2];

            rec.startRecording();
            track.play();
            Params.running = true;

            while (!stopRequested) {
                int n = rec.read(in, 0, FRAMES);
                if (n < 0) {
                    if (n == AudioRecord.ERROR_DEAD_OBJECT) {
                        throw new IllegalStateException("O microfone parou de responder.");
                    }
                    continue;
                }
                if (n == 0) continue;

                int v = Params.version;
                if (v != lastVersion) {
                    lastVersion = v;
                    dsp.update();
                }
                if (ns != null && nsEnabled != Params.noiseReduction) {
                    nsEnabled = Params.noiseReduction;
                    try {
                        ns.setEnabled(nsEnabled);
                    } catch (RuntimeException ignored) {
                    }
                }

                dsp.process(in, out, n);
                int w = track.write(out, 0, n * 2);
                if (w == AudioTrack.ERROR_DEAD_OBJECT) {
                    throw new IllegalStateException("A saída de som foi desligada.");
                }
            }
        } catch (Exception e) {
            String msg = e.getMessage();
            Params.lastError = (msg != null) ? msg : ("Erro de áudio: " + e.getClass().getSimpleName());
        } finally {
            if (ns != null) {
                try { ns.release(); } catch (RuntimeException ignored) { }
            }
            if (rec != null) {
                try { rec.stop(); } catch (RuntimeException ignored) { }
                rec.release();
            }
            if (track != null) {
                try { track.stop(); } catch (RuntimeException ignored) { }
                track.release();
            }
            if (dsp != null) dsp.release();
            Params.running = false;
            Params.inputLevelDb = -100f;
            Params.outputLevelDb = -100f;
            if (!stopRequested && onFinished != null) onFinished.run();
        }
    }
}
