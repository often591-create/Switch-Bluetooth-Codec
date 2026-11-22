package com.often.codecswitch;

import android.bluetooth.BluetoothA2dp;
import android.bluetooth.BluetoothCodecConfig;
import android.bluetooth.BluetoothCodecStatus;
import android.bluetooth.BluetoothCodecType;
import android.bluetooth.BluetoothDevice;
import android.companion.AssociationInfo;
import android.companion.CompanionDeviceManager;
import android.content.Context;
import android.net.MacAddress;
import android.os.Build;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

final class CodecUtil {
    private CodecUtil() {}

    static final String ACTION_CODEC_CONFIG_CHANGED = "android.bluetooth.a2dp.profile.action.CODEC_CONFIG_CHANGED";

    static BluetoothCodecStatus getStatus(BluetoothA2dp a2dp, BluetoothDevice d) throws Exception {
        Method m = BluetoothA2dp.class.getMethod("getCodecStatus", BluetoothDevice.class);
        try {
            return (BluetoothCodecStatus) m.invoke(a2dp, d);
        } catch (InvocationTargetException e) {
            throw unwrap(e);
        }
    }

    static void setConfig(BluetoothA2dp a2dp, BluetoothDevice d, BluetoothCodecConfig c) throws Exception {
        Method m = BluetoothA2dp.class.getMethod("setCodecConfigPreference", BluetoothDevice.class, BluetoothCodecConfig.class);
        try {
            m.invoke(a2dp, d, c);
        } catch (InvocationTargetException e) {
            throw unwrap(e);
        }
    }

    private static Exception unwrap(InvocationTargetException e) {
        Throwable t = e.getCause();
        if (t instanceof Exception) return (Exception) t;
        return new RuntimeException(String.valueOf(t));
    }

    static String err(Throwable t) {
        String m = t.getMessage();
        return t.getClass().getSimpleName() + (m == null ? "" : ": " + m);
    }

    static final int[] RATE_BITS = {0x1, 0x2, 0x4, 0x8, 0x10, 0x20};
    static final String[] RATE_NAMES = {"44.1 kHz", "48 kHz", "88.2 kHz", "96 kHz", "176.4 kHz", "192 kHz"};
    static final int[] DEPTH_BITS = {0x1, 0x2, 0x4};
    static final String[] DEPTH_NAMES = {"16 bit", "24 bit", "32 bit"};
    static final long[] LDAC_VALUES = {1000, 1001, 1002, 1003};
    static final String[] LDAC_NAMES = {"高音质 990kbps", "标准 660kbps", "连接优先 330kbps", "自适应 ABR"};

    private static String nameOfType(int t) {
        switch (t) {
            case 0: return "SBC";
            case 1: return "AAC";
            case 2: return "aptX";
            case 3: return "aptX HD";
            case 4: return "LDAC";
            case 5: return "LC3";
            case 6: return "Opus";
            default: return null;
        }
    }

    @SuppressWarnings("deprecation")
    static String name(BluetoothCodecConfig c) {
        if (c == null) return "未知";
        String n = nameOfType(c.getCodecType());
        if (n != null) return n;
        if (Build.VERSION.SDK_INT >= 35) {
            BluetoothCodecType x = c.getExtendedCodecType();
            if (x != null && x.getCodecName() != null) return x.getCodecName();
        }
        return "其他编码 (" + c.getCodecType() + ")";
    }

    /** Unique key so two configs of the same codec compare equal. */
    @SuppressWarnings("deprecation")
    static String key(BluetoothCodecConfig c) {
        if (c == null) return "";
        if (Build.VERSION.SDK_INT >= 35) {
            BluetoothCodecType x = c.getExtendedCodecType();
            if (x != null) return "x" + x.getCodecId();
        }
        return "t" + c.getCodecType();
    }

    @SuppressWarnings("deprecation")
    static int type(BluetoothCodecConfig c) {
        return c == null ? -1 : c.getCodecType();
    }

    @SuppressWarnings("deprecation")
    static boolean isLdac(BluetoothCodecConfig c) {
        return c != null && c.getCodecType() == 4;
    }

    static String label(int value, int[] bits, String[] names) {
        for (int i = 0; i < bits.length; i++) if (bits[i] == value) return names[i];
        return null;
    }

    static String ldacLabel(long v) {
        for (int i = 0; i < LDAC_VALUES.length; i++) if (LDAC_VALUES[i] == v) return LDAC_NAMES[i];
        return null;
    }

    static String describe(BluetoothCodecConfig c) {
        if (c == null) return "未知";
        StringBuilder sb = new StringBuilder(name(c));
        String r = label(c.getSampleRate(), RATE_BITS, RATE_NAMES);
        if (r != null) sb.append(" · ").append(r);
        String b = label(c.getBitsPerSample(), DEPTH_BITS, DEPTH_NAMES);
        if (b != null) sb.append(" · ").append(b);
        if (isLdac(c)) {
            String q = ldacLabel(c.getCodecSpecific1());
            if (q != null) sb.append(" · ").append(q);
        }
        return sb.toString();
    }

    @SuppressWarnings("deprecation")
    static BluetoothCodecConfig build(BluetoothCodecConfig cap, int sampleRate, int bits, long specific1) {
        BluetoothCodecConfig.Builder b = new BluetoothCodecConfig.Builder();
        b.setCodecType(cap.getCodecType());
        if (Build.VERSION.SDK_INT >= 35 && cap.getExtendedCodecType() != null) {
            b.setExtendedCodecType(cap.getExtendedCodecType());
        }
        b.setCodecPriority(BluetoothCodecConfig.CODEC_PRIORITY_HIGHEST);
        b.setSampleRate(sampleRate);
        b.setBitsPerSample(bits);
        b.setChannelMode(BluetoothCodecConfig.CHANNEL_MODE_NONE);
        b.setCodecSpecific1(specific1);
        return b.build();
    }

    static boolean isAssociated(Context ctx, BluetoothDevice d) {
        CompanionDeviceManager cdm = ctx.getSystemService(CompanionDeviceManager.class);
        if (cdm == null || d == null) return false;
        try {
            for (AssociationInfo ai : cdm.getMyAssociations()) {
                MacAddress m = ai.getDeviceMacAddress();
                if (m != null && m.toString().equalsIgnoreCase(d.getAddress())) return true;
            }
        } catch (Exception ignored) {
        }
        return false;
    }
}
