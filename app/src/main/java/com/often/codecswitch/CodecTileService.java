package com.often.codecswitch;

import android.Manifest;
import android.app.PendingIntent;
import android.bluetooth.BluetoothA2dp;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothCodecConfig;
import android.bluetooth.BluetoothCodecStatus;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Handler;
import android.os.Looper;
import android.service.quicksettings.Tile;
import android.service.quicksettings.TileService;
import android.widget.Toast;

import java.util.List;

/** Quick settings tile: tap to cycle through the codecs the connected device supports. */
public class CodecTileService extends TileService {
    private final Handler handler = new Handler(Looper.getMainLooper());
    private BluetoothAdapter adapter;
    private BluetoothA2dp a2dp;

    private final BluetoothProfile.ServiceListener listener = new BluetoothProfile.ServiceListener() {
        @Override
        public void onServiceConnected(int profile, BluetoothProfile proxy) {
            a2dp = (BluetoothA2dp) proxy;
            updateTile();
        }

        @Override
        public void onServiceDisconnected(int profile) {
            a2dp = null;
            updateTile();
        }
    };

    private boolean hasPerm() {
        return checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED;
    }

    @Override
    public void onStartListening() {
        super.onStartListening();
        BluetoothManager bm = getSystemService(BluetoothManager.class);
        adapter = bm == null ? null : bm.getAdapter();
        if (adapter != null && hasPerm() && a2dp == null) {
            adapter.getProfileProxy(this, listener, BluetoothProfile.A2DP);
        }
        updateTile();
    }

    @Override
    public void onStopListening() {
        if (adapter != null && a2dp != null) adapter.closeProfileProxy(BluetoothProfile.A2DP, a2dp);
        a2dp = null;
        super.onStopListening();
    }

    private BluetoothDevice firstDevice() {
        if (a2dp == null || !hasPerm()) return null;
        try {
            List<BluetoothDevice> devs = a2dp.getConnectedDevices();
            return devs.isEmpty() ? null : devs.get(0);
        } catch (Exception e) {
            return null;
        }
    }

    private BluetoothCodecStatus status(BluetoothDevice d) {
        try {
            return a2dp.getCodecStatus(d);
        } catch (Exception e) {
            return null;
        }
    }

    private void updateTile() {
        Tile t = getQsTile();
        if (t == null) return;
        t.setLabel("蓝牙编码");
        BluetoothDevice d = firstDevice();
        BluetoothCodecStatus st = d == null ? null : status(d);
        if (st == null) {
            t.setSubtitle("未连接");
            t.setState(Tile.STATE_INACTIVE);
        } else {
            t.setSubtitle(CodecUtil.name(st.getCodecConfig()));
            t.setState(Tile.STATE_ACTIVE);
        }
        t.updateTile();
    }

    private void openApp() {
        Intent i = new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        PendingIntent pi = PendingIntent.getActivity(this, 0, i,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        startActivityAndCollapse(pi);
    }

    @Override
    public void onClick() {
        super.onClick();
        BluetoothDevice d = firstDevice();
        if (d == null) {
            openApp();
            return;
        }
        if (!CodecUtil.isAssociated(this, d)) {
            // First switch needs the one-time authorization screen inside the app.
            openApp();
            return;
        }
        BluetoothCodecStatus st = status(d);
        if (st == null) return;
        List<BluetoothCodecConfig> caps = st.getCodecsSelectableCapabilities();
        if (caps == null || caps.isEmpty()) return;
        String curKey = CodecUtil.key(st.getCodecConfig());
        int idx = -1;
        for (int i = 0; i < caps.size(); i++) {
            if (CodecUtil.key(caps.get(i)).equals(curKey)) idx = i;
        }
        BluetoothCodecConfig next = caps.get((idx + 1) % caps.size());
        try {
            a2dp.setCodecConfigPreference(d, CodecUtil.build(next, 0, 0, 0));
            Tile t = getQsTile();
            if (t != null) {
                t.setSubtitle(CodecUtil.name(next) + "…");
                t.updateTile();
            }
        } catch (Exception e) {
            Toast.makeText(this, "切换失败：" + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
        handler.postDelayed(this::updateTile, 1500);
    }
}
