package com.often.codecswitch;

import android.Manifest;
import android.app.Activity;
import android.bluetooth.BluetoothA2dp;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothCodecConfig;
import android.bluetooth.BluetoothCodecStatus;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.companion.AssociationInfo;
import android.companion.AssociationRequest;
import android.companion.BluetoothDeviceFilter;
import android.companion.CompanionDeviceManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.IntentSender;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.List;

public class MainActivity extends Activity {
    private static final int REQ_PERM = 1;
    private static final int REQ_ASSOC = 2;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private BluetoothAdapter adapter;
    private BluetoothA2dp a2dp;
    private LinearLayout root;
    private boolean receiverOn;

    private BluetoothDevice device;
    private BluetoothCodecConfig selCap;
    private int selRate;
    private int selBits;
    private long selLdac;

    private BluetoothDevice pendingDev;
    private final java.util.Set<String> autoAsked = new java.util.HashSet<>();
    private BluetoothCodecConfig pendingCfg;

    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent i) {
            selCap = null;
            handler.postDelayed(MainActivity.this::refresh, 300);
        }
    };

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        ScrollView sv = new ScrollView(this);
        sv.setFitsSystemWindows(true);
        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int p = dp(16);
        root.setPadding(p, p, p, p);
        sv.addView(root);
        setContentView(sv);

        BluetoothManager bm = getSystemService(BluetoothManager.class);
        adapter = bm == null ? null : bm.getAdapter();

        if (checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            showNeedPermission();
            requestPermissions(new String[]{Manifest.permission.BLUETOOTH_CONNECT}, REQ_PERM);
        } else {
            init();
        }
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] results) {
        super.onRequestPermissionsResult(code, perms, results);
        if (code == REQ_PERM) {
            if (results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED) init();
            else showNeedPermission();
        }
    }

    private void showNeedPermission() {
        root.removeAllViews();
        addText("需要「附近的设备」权限才能读取和切换蓝牙编码。");
        Button btn = addButton("授予权限");
        btn.setOnClickListener(v -> requestPermissions(new String[]{Manifest.permission.BLUETOOTH_CONNECT}, REQ_PERM));
    }

    private void init() {
        if (adapter == null) {
            refresh();
            return;
        }
        if (!receiverOn) {
            IntentFilter f = new IntentFilter();
            f.addAction(CodecUtil.ACTION_CODEC_CONFIG_CHANGED);
            f.addAction(BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED);
            f.addAction(BluetoothAdapter.ACTION_STATE_CHANGED);
            registerReceiver(receiver, f, Context.RECEIVER_EXPORTED);
            receiverOn = true;
        }
        android.content.SharedPreferences prefs = getSharedPreferences("app", MODE_PRIVATE);
        if (!prefs.getBoolean("bulkAuthDone", false)) {
            prefs.edit().putBoolean("bulkAuthDone", true).apply();
            handler.postDelayed(() -> startBulkAuth(true), 600);
        }
        if (a2dp == null) {
            adapter.getProfileProxy(this, new BluetoothProfile.ServiceListener() {
                @Override
                public void onServiceConnected(int profile, BluetoothProfile proxy) {
                    a2dp = (BluetoothA2dp) proxy;
                    refresh();
                }

                @Override
                public void onServiceDisconnected(int profile) {
                    a2dp = null;
                    refresh();
                }
            }, BluetoothProfile.A2DP);
        }
        refresh();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED) {
            if (a2dp == null) init(); else refresh();
        }
    }

    @Override
    protected void onDestroy() {
        if (receiverOn) {
            unregisterReceiver(receiver);
            receiverOn = false;
        }
        if (adapter != null && a2dp != null) adapter.closeProfileProxy(BluetoothProfile.A2DP, a2dp);
        super.onDestroy();
    }

    private void refresh() {
        if (isFinishing()) return;
        root.removeAllViews();

        if (adapter == null) {
            addText("这台手机没有蓝牙功能。");
            return;
        }
        if (!adapter.isEnabled()) {
            addText("请先打开蓝牙，并连接你的耳机或音箱。");
            addRefreshButton();
            return;
        }
        if (a2dp == null) {
            addText("正在连接蓝牙服务…");
            return;
        }

        List<BluetoothDevice> devs;
        try {
            devs = a2dp.getConnectedDevices();
        } catch (SecurityException e) {
            showNeedPermission();
            return;
        }
        if (devs.isEmpty()) {
            device = null;
            addText("目前没有连接中的蓝牙耳机或音箱。连接后这里会自动显示。");
            addRefreshButton();
            return;
        }
        if (device == null || !devs.contains(device)) {
            device = devs.get(0);
            selCap = null;
        }

        if (devs.size() > 1) {
            addHeader("设备");
            RadioGroup g = new RadioGroup(this);
            for (BluetoothDevice d : devs) {
                RadioButton rb = radio(deviceName(d), d.equals(device));
                rb.setOnClickListener(v -> {
                    device = d;
                    selCap = null;
                    refresh();
                });
                g.addView(rb);
            }
            root.addView(g);
        }

        BluetoothCodecStatus st;
        String readErr = null;
        boolean needAuth = false;
        try {
            st = CodecUtil.getStatus(a2dp, device);
        } catch (SecurityException e) {
            st = null;
            needAuth = true;
            readErr = CodecUtil.err(e);
        } catch (Exception e) {
            st = null;
            readErr = CodecUtil.err(e);
        }
        if (st == null && (needAuth || !CodecUtil.isAssociated(this, device))) {
            addHeader(deviceName(device));
            addText("第一次连这副耳机需要授权一次：在系统跳出的视窗里选这副耳机并按「允许」。之后再连就不用了。如果视窗没跳出来，按下面的按钮。");
            Button auth = addButton("授权「" + deviceName(device) + "」");
            BluetoothDevice target = device;
            if (!authBusy && authQueue.isEmpty() && autoAsked.add(target.getAddress())) {
                handler.postDelayed(() -> associate(target), 400);
            }
            auth.setOnClickListener(v -> {
                pendingDev = null;
                pendingCfg = null;
                associate(target);
            });
            addRefreshButton();
            return;
        }
        if (st == null) {
            addText("读取不到「" + deviceName(device) + "」的编码信息。先播放一下音乐，再按刷新试试。"
                    + (readErr == null ? "" : "\n\n错误信息：" + readErr));
            addRefreshButton();
            return;
        }

        BluetoothCodecConfig cur = st.getCodecConfig();
        List<BluetoothCodecConfig> caps = st.getCodecsSelectableCapabilities();

        addHeader(deviceName(device));
        TextView now = addText("目前使用：" + CodecUtil.describe(cur));
        now.setTypeface(Typeface.DEFAULT_BOLD);

        if (caps == null || caps.isEmpty()) {
            addText("这个设备没有回报可选的编码。");
            addRefreshButton();
            return;
        }

        addHeader("一键模式");
        Button q = addButton("🎵 音质优先（最高码率，较耗电，距离远易断）");
        q.setOnClickListener(v -> applyPreset(caps, 0));
        Button m = addButton("⚖️ 均衡（自适应码率，自动降码率防断线）");
        m.setOnClickListener(v -> applyPreset(caps, 1));
        Button s = addButton("🔋 稳定省电（低码率，最不容易断，最省电）");
        s.setOnClickListener(v -> applyPreset(caps, 2));

        if (selCap == null) {
            selCap = caps.get(0);
            for (BluetoothCodecConfig c : caps) {
                if (CodecUtil.key(c).equals(CodecUtil.key(cur))) selCap = c;
            }
            boolean same = CodecUtil.key(selCap).equals(CodecUtil.key(cur));
            selRate = same && cur != null ? cur.getSampleRate() : 0;
            selBits = same && cur != null ? cur.getBitsPerSample() : 0;
            selLdac = same && cur != null ? cur.getCodecSpecific1() : 0;
        } else {
            for (BluetoothCodecConfig c : caps) {
                if (CodecUtil.key(c).equals(CodecUtil.key(selCap))) selCap = c;
            }
        }

        addHeader("手动设定 · 编码");
        RadioGroup codecs = new RadioGroup(this);
        for (BluetoothCodecConfig c : caps) {
            RadioButton rb = radio(CodecUtil.name(c), CodecUtil.key(c).equals(CodecUtil.key(selCap)));
            rb.setOnClickListener(v -> {
                selCap = c;
                selRate = 0;
                selBits = 0;
                selLdac = 0;
                refresh();
            });
            codecs.addView(rb);
        }
        root.addView(codecs);

        List<BluetoothCodecConfig> local = null;
        try {
            local = st.getCodecsLocalCapabilities();
        } catch (Exception ignored) {
        }
        boolean ldacHidden = false;
        if (local != null) {
            StringBuilder off = new StringBuilder();
            for (BluetoothCodecConfig l : local) {
                boolean offered = false;
                for (BluetoothCodecConfig c : caps) {
                    if (CodecUtil.key(c).equals(CodecUtil.key(l))) offered = true;
                }
                if (!offered) {
                    if (off.length() > 0) off.append("、");
                    off.append(CodecUtil.name(l));
                    if (CodecUtil.isLdac(l)) ldacHidden = true;
                }
            }
            if (off.length() > 0) {
                TextView t = addText("手机支持、但这副耳机目前没开放：" + off);
                t.setAlpha(0.6f);
            }
        }
        String dn = deviceName(device).toUpperCase();
        if (ldacHidden && (dn.contains("HUAWEI") || dn.contains("HONOR") || dn.contains("FREEBUDS") || dn.contains("FREECLIP"))) {
            TextView t = addText("华为耳机要先在耳机那边打开 LDAC：打开「智慧生活 / AI Life」app → 耳机卡片 → 音质与音效 → 选「音质优先」，再回来按刷新，LDAC 就会出现。");
            t.setTypeface(Typeface.DEFAULT_BOLD);
        }

        addOptions("采样率", selCap.getSampleRate(), CodecUtil.RATE_BITS, CodecUtil.RATE_NAMES, selRate, v -> selRate = v);
        addOptions("位深度", selCap.getBitsPerSample(), CodecUtil.DEPTH_BITS, CodecUtil.DEPTH_NAMES, selBits, v -> selBits = v);

        if (CodecUtil.isLdac(selCap)) {
            addHeader("LDAC 音质");
            RadioGroup g = new RadioGroup(this);
            RadioButton def = radio("系统默认", CodecUtil.ldacLabel(selLdac) == null);
            def.setOnClickListener(v -> selLdac = 0);
            g.addView(def);
            for (int i = 0; i < CodecUtil.LDAC_VALUES.length; i++) {
                long val = CodecUtil.LDAC_VALUES[i];
                RadioButton rb = radio(CodecUtil.LDAC_NAMES[i], selLdac == val);
                rb.setOnClickListener(v -> selLdac = val);
                g.addView(rb);
            }
            root.addView(g);
        }

        Button apply = addButton("套用");
        apply.setOnClickListener(v -> apply());
        addRefreshButton();
        Button bulk = addButton("一次授权所有已配对的耳机/音箱");
        bulk.setOnClickListener(v -> startBulkAuth(false));

        TextView tip = addText("\n小技巧：下拉通知栏 → 编辑快捷开关，把「蓝牙编码」拖进去，以后点一下就能轮流切换编码。\n\n" +
                "注意：手机和耳机都要支持的编码才会出现在列表里。第一次切换时系统会请你允许这个 app 管理这副耳机，按「允许」即可。");
        tip.setAlpha(0.7f);
    }

    private interface IntSetter { void set(int v); }

    private void addOptions(String title, int mask, int[] bits, String[] names, int selected, IntSetter setter) {
        int count = 0;
        for (int bit : bits) if ((mask & bit) != 0) count++;
        if (count <= 1) return;
        addHeader(title);
        RadioGroup g = new RadioGroup(this);
        boolean any = false;
        for (int bit : bits) if (bit == selected && (mask & bit) != 0) any = true;
        RadioButton auto = radio("自动", !any);
        auto.setOnClickListener(v -> setter.set(0));
        g.addView(auto);
        for (int i = 0; i < bits.length; i++) {
            if ((mask & bits[i]) == 0) continue;
            int bit = bits[i];
            RadioButton rb = radio(names[i], bit == selected);
            rb.setOnClickListener(v -> setter.set(bit));
            g.addView(rb);
        }
        root.addView(g);
    }

    /** mode 0 = quality, 1 = balanced, 2 = stable / low power. */
    private void applyPreset(List<BluetoothCodecConfig> caps, int mode) {
        // Codec type ids: 0 SBC, 1 AAC, 2 aptX, 3 aptX HD, 4 LDAC
        int[] order;
        if (mode == 0) order = new int[]{4, 3, 2, 1, 0};
        else if (mode == 1) order = new int[]{4, 2, 1, 0, 3};
        else order = new int[]{1, 0};

        BluetoothCodecConfig pick = null;
        outer:
        for (int t : order) {
            for (BluetoothCodecConfig c : caps) {
                if (CodecUtil.type(c) == t) {
                    pick = c;
                    break outer;
                }
            }
        }
        if (pick == null) pick = caps.get(0);

        int rate = 0;
        int bits = 0;
        long spec = 0;
        boolean ldac = CodecUtil.isLdac(pick);
        if (mode == 0) {
            rate = Integer.highestOneBit(pick.getSampleRate());
            bits = Integer.highestOneBit(pick.getBitsPerSample());
            if (ldac) spec = 1000; // 990 kbps
        } else if (mode == 1) {
            if (ldac) spec = 1003; // adaptive bit rate
        } else {
            rate = Integer.lowestOneBit(pick.getSampleRate());
            bits = Integer.lowestOneBit(pick.getBitsPerSample());
            if (ldac) spec = 1002; // 330 kbps
        }
        selCap = pick;
        selRate = rate;
        selBits = bits;
        selLdac = spec;
        apply();
    }

    private void apply() {
        if (device == null || selCap == null || a2dp == null) return;
        long spec = CodecUtil.isLdac(selCap) ? selLdac : 0;
        BluetoothCodecConfig cfg = CodecUtil.build(selCap, selRate, selBits, spec);
        if (!CodecUtil.isAssociated(this, device)) {
            pendingDev = device;
            pendingCfg = cfg;
            associate(device);
            return;
        }
        doApply(device, cfg);
    }

    private void doApply(BluetoothDevice d, BluetoothCodecConfig cfg) {
        if (a2dp == null) return;
        try {
            CodecUtil.setConfig(a2dp, d, cfg);
            toast("正在切换到 " + CodecUtil.name(cfg) + "…");
        } catch (Exception e) {
            Toast.makeText(this, "切换失败：" + CodecUtil.err(e), Toast.LENGTH_LONG).show();
        }
        selCap = null;
        handler.postDelayed(this::refresh, 1500);
    }

    private void applyPending() {
        if (pendingDev == null || pendingCfg == null) {
            selCap = null;
            refresh();
            return;
        }
        {
            BluetoothDevice d = pendingDev;
            BluetoothCodecConfig c = pendingCfg;
            pendingDev = null;
            pendingCfg = null;
            doApply(d, c);
        }
    }

    // ---- device authorization (CompanionDeviceManager) ----

    private final java.util.ArrayList<BluetoothDevice> authQueue = new java.util.ArrayList<>();
    private boolean authBusy;

    /** Queue every paired audio device that isn't authorized yet, then ask for them one after another. */
    private void startBulkAuth(boolean quietIfNone) {
        if (adapter == null || authBusy) return;
        authQueue.clear();
        java.util.Set<BluetoothDevice> bonded;
        try {
            bonded = adapter.getBondedDevices();
        } catch (SecurityException e) {
            return;
        }
        if (bonded != null) {
            for (BluetoothDevice d : bonded) {
                android.bluetooth.BluetoothClass bc = d.getBluetoothClass();
                boolean audio = bc != null && bc.getMajorDeviceClass() == android.bluetooth.BluetoothClass.Device.Major.AUDIO_VIDEO;
                if (audio && !CodecUtil.isAssociated(this, d)) authQueue.add(d);
            }
        }
        if (authQueue.isEmpty()) {
            if (!quietIfNone) toast("已配对的耳机/音箱都授权过了");
            return;
        }
        Toast.makeText(this, "接下来会依序跳出 " + authQueue.size() + " 个授权视窗，每个按「允许」即可", Toast.LENGTH_LONG).show();
        associateNext();
    }

    private void associateNext() {
        if (authQueue.isEmpty()) return;
        BluetoothDevice d = authQueue.remove(0);
        autoAsked.add(d.getAddress());
        associate(d);
    }

    /** Called once per finished authorization attempt (allowed, denied or failed). */
    private void onAuthDone() {
        if (!authBusy) return;
        authBusy = false;
        if (!authQueue.isEmpty()) {
            handler.postDelayed(this::associateNext, 500);
        } else {
            applyPending();
        }
    }

    private void associate(BluetoothDevice d) {
        CompanionDeviceManager cdm = getSystemService(CompanionDeviceManager.class);
        if (cdm == null) {
            toast("这台手机不支持设备授权，无法切换编码。");
            return;
        }
        authBusy = true;
        BluetoothDeviceFilter filter = new BluetoothDeviceFilter.Builder().setAddress(d.getAddress()).build();
        AssociationRequest req = new AssociationRequest.Builder()
                .addDeviceFilter(filter)
                .setSingleDevice(true)
                .build();
        cdm.associate(req, getMainExecutor(), new CompanionDeviceManager.Callback() {
            @Override
            public void onAssociationPending(IntentSender sender) {
                try {
                    startIntentSenderForResult(sender, REQ_ASSOC, null, 0, 0, 0);
                } catch (IntentSender.SendIntentException e) {
                    toast("无法打开授权视窗：" + e.getMessage());
                    onAuthDone();
                }
            }

            @Override
            public void onAssociationCreated(AssociationInfo info) {
                onAuthDone();
            }

            @Override
            public void onFailure(CharSequence error) {
                toast("「" + deviceName(d) + "」授权失败：" + error);
                pendingDev = null;
                pendingCfg = null;
                onAuthDone();
            }
        });
    }

    @Override
    protected void onActivityResult(int code, int result, Intent data) {
        super.onActivityResult(code, result, data);
        if (code == REQ_ASSOC) {
            if (result != RESULT_OK) {
                pendingDev = null;
                pendingCfg = null;
            }
            handler.postDelayed(this::onAuthDone, 300);
        }
    }

    // ---- small UI helpers ----

    private String deviceName(BluetoothDevice d) {
        try {
            String n = d.getName();
            return n == null ? d.getAddress() : n;
        } catch (SecurityException e) {
            return d.getAddress();
        }
    }

    private void addTitle(String s) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(24);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setPadding(0, 0, 0, dp(12));
        root.addView(t);
    }

    private void addHeader(String s) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(18);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setPadding(0, dp(16), 0, dp(4));
        root.addView(t);
    }

    private TextView addText(String s) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(15);
        t.setPadding(0, dp(4), 0, dp(4));
        root.addView(t);
        return t;
    }

    private Button addButton(String s) {
        Button b = new Button(this);
        b.setText(s);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(12);
        root.addView(b, lp);
        return b;
    }

    private void addRefreshButton() {
        Button b = addButton("刷新");
        b.setOnClickListener(v -> {
            selCap = null;
            refresh();
        });
    }

    private RadioButton radio(String text, boolean checked) {
        RadioButton rb = new RadioButton(this);
        rb.setId(View.generateViewId());
        rb.setText(text);
        rb.setTextSize(16);
        rb.setChecked(checked);
        rb.setPadding(dp(4), dp(8), 0, dp(8));
        return rb;
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
