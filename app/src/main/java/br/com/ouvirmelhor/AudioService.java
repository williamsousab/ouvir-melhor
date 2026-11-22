package br.com.ouvirmelhor;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ServiceInfo;
import android.graphics.drawable.Icon;
import android.media.AudioDeviceCallback;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;

/** Serviço em primeiro plano: mantém o som funcionando com a tela apagada. */
public class AudioService extends Service {

    public static final String ACTION_STOP = "br.com.ouvirmelhor.STOP";
    public static final String ACTION_RESTART = "br.com.ouvirmelhor.RESTART";
    private static final String CHANNEL_ID = "ouvir_melhor";
    private static final int NOTIF_ID = 1;

    private final Handler main = new Handler(Looper.getMainLooper());
    private AudioManager am;
    private AudioEngine engine;
    private boolean callbacksRegistered;

    private final AudioDeviceCallback deviceCallback = new AudioDeviceCallback() {
        @Override
        public void onAudioDevicesRemoved(AudioDeviceInfo[] removed) {
            if (AudioRouting.findHeadphone(am) == null) {
                Params.lastError = "O fone foi desconectado. O app desligou para não apitar no alto-falante.";
                stopSelf();
            }
        }
    };

    private final BroadcastReceiver noisyReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (AudioManager.ACTION_AUDIO_BECOMING_NOISY.equals(intent.getAction())) {
                Params.lastError = "O fone foi desconectado. O app desligou para não apitar no alto-falante.";
                stopSelf();
            }
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        am = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        NotificationChannel ch = new NotificationChannel(CHANNEL_ID, "Ouvir Melhor ligado",
                NotificationManager.IMPORTANCE_LOW);
        ch.setDescription("Aparece enquanto o som está sendo amplificado");
        getSystemService(NotificationManager.class).createNotificationChannel(ch);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            stopSelf();
            return START_NOT_STICKY;
        }
        if (intent != null && ACTION_RESTART.equals(intent.getAction())) {
            // Troca de microfone: reinicia só o motor de áudio
            if (engine != null) {
                engine.stop();
                engine = new AudioEngine(this, () -> main.post(() -> stopSelf()));
                engine.start();
            }
            return START_NOT_STICKY;
        }

        Notification n = buildNotification();
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE);
        } else {
            startForeground(NOTIF_ID, n);
        }
        Params.serviceActive = true;

        if (engine == null) {
            if (AudioRouting.findHeadphone(am) == null) {
                Params.lastError = "Nenhum fone conectado.";
                stopSelf();
                return START_NOT_STICKY;
            }
            Params.lastError = null;
            engine = new AudioEngine(this, () -> main.post(() -> stopSelf()));
            engine.start();

            am.registerAudioDeviceCallback(deviceCallback, main);
            IntentFilter f = new IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY);
            if (Build.VERSION.SDK_INT >= 33) {
                registerReceiver(noisyReceiver, f, Context.RECEIVER_NOT_EXPORTED);
            } else {
                registerReceiver(noisyReceiver, f);
            }
            callbacksRegistered = true;
        }
        return START_NOT_STICKY;
    }

    private Notification buildNotification() {
        Intent open = new Intent(this, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent openPi = PendingIntent.getActivity(this, 0, open,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);

        Intent stop = new Intent(this, AudioService.class).setAction(ACTION_STOP);
        PendingIntent stopPi = PendingIntent.getService(this, 1, stop,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);

        Notification.Action stopAction = new Notification.Action.Builder(
                Icon.createWithResource(this, R.drawable.ic_notif), "Desligar", stopPi).build();

        return new Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notif)
                .setContentTitle("Ouvir Melhor está ligado")
                .setContentText("Captando o som e enviando para o fone")
                .setOngoing(true)
                .setContentIntent(openPi)
                .addAction(stopAction)
                .build();
    }

    @Override
    public void onDestroy() {
        if (engine != null) {
            engine.stop();
            engine = null;
        }
        if (callbacksRegistered) {
            am.unregisterAudioDeviceCallback(deviceCallback);
            try {
                unregisterReceiver(noisyReceiver);
            } catch (IllegalArgumentException ignored) {
            }
            callbacksRegistered = false;
        }
        stopForeground(STOP_FOREGROUND_REMOVE);
        Params.serviceActive = false;
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
