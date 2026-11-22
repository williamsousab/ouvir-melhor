package br.com.ouvirmelhor;

import android.media.AudioDeviceInfo;
import android.media.AudioManager;
import android.os.Build;

/** Descobre fones conectados e o microfone do celular. */
final class AudioRouting {

    private AudioRouting() {}

    static boolean isHeadphoneOutput(AudioDeviceInfo d) {
        int t = d.getType();
        switch (t) {
            case AudioDeviceInfo.TYPE_BLUETOOTH_A2DP:
            case AudioDeviceInfo.TYPE_BLUETOOTH_SCO:
            case AudioDeviceInfo.TYPE_WIRED_HEADPHONES:
            case AudioDeviceInfo.TYPE_WIRED_HEADSET:
            case AudioDeviceInfo.TYPE_USB_HEADSET:
                return true;
            default:
                break;
        }
        if (Build.VERSION.SDK_INT >= 28 && t == AudioDeviceInfo.TYPE_HEARING_AID) return true;
        if (Build.VERSION.SDK_INT >= 31
                && (t == AudioDeviceInfo.TYPE_BLE_HEADSET || t == AudioDeviceInfo.TYPE_BLE_SPEAKER)) {
            return true;
        }
        return false;
    }

    static AudioDeviceInfo findHeadphone(AudioManager am) {
        AudioDeviceInfo fallback = null;
        for (AudioDeviceInfo d : am.getDevices(AudioManager.GET_DEVICES_OUTPUTS)) {
            if (!isHeadphoneOutput(d)) continue;
            // Prefere o perfil de música (A2DP / LE Audio) ao de ligação (SCO)
            if (d.getType() != AudioDeviceInfo.TYPE_BLUETOOTH_SCO) return d;
            fallback = d;
        }
        return fallback;
    }

    static String describe(AudioDeviceInfo d) {
        String kind;
        switch (d.getType()) {
            case AudioDeviceInfo.TYPE_WIRED_HEADPHONES:
            case AudioDeviceInfo.TYPE_WIRED_HEADSET:
                kind = "fone com fio";
                break;
            case AudioDeviceInfo.TYPE_USB_HEADSET:
                kind = "fone USB";
                break;
            default:
                kind = "Bluetooth";
                break;
        }
        CharSequence name = d.getProductName();
        if (name == null || name.length() == 0) return kind;
        return name + " (" + kind + ")";
    }

    static AudioDeviceInfo findBuiltinMic(AudioManager am) {
        for (AudioDeviceInfo d : am.getDevices(AudioManager.GET_DEVICES_INPUTS)) {
            if (d.getType() == AudioDeviceInfo.TYPE_BUILTIN_MIC) return d;
        }
        return null;
    }
}
