package com.yg.pkwhitelist;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;

public class MainActivity extends AppCompatActivity {

    private EditText etSearch;
    private CheckBox cbSystem;
    private TextView tvStatus;
    private TextView tvDiag;
    private AppListAdapter adapter;

    private final List<AppEntry> all = new ArrayList<>();
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final java.util.concurrent.ExecutorService io = Executors.newSingleThreadExecutor();
    private volatile boolean gotResponse;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        etSearch = findViewById(R.id.et_search);
        cbSystem = findViewById(R.id.cb_system);
        tvStatus = findViewById(R.id.tv_status);
        tvDiag = findViewById(R.id.tv_diag);
        RecyclerView rv = findViewById(R.id.rv);

        adapter = new AppListAdapter(this::cycleMode);
        rv.setLayoutManager(new LinearLayoutManager(this));
        rv.setAdapter(adapter);

        etSearch.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) { applyFilter(); }
            @Override public void afterTextChanged(Editable s) { }
        });
        cbSystem.setOnCheckedChangeListener((v, b) -> applyFilter());
        findViewById(R.id.btn_refresh).setOnClickListener(v -> load());
        findViewById(R.id.btn_reset).setOnClickListener(v -> resetAll());
        findViewById(R.id.btn_clear).setOnClickListener(v -> confirmClear());

        load();
    }

    private void load() {
        tvStatus.setText("加载应用中…");
        io.execute(() -> {
            List<AppEntry> list = buildList();
            ui.post(() -> {
                all.clear();
                all.addAll(list);
                applyFilter();
                queryAll();
            });
        });
    }

    private List<AppEntry> buildList() {
        PackageManager pm = getPackageManager();
        List<AppEntry> out = new ArrayList<>();
        for (ApplicationInfo ai : pm.getInstalledApplications(0)) {
            if (getPackageName().equals(ai.packageName)) continue;
            boolean sys = (ai.flags & ApplicationInfo.FLAG_SYSTEM) != 0;
            AppEntry e = new AppEntry(ai.packageName, String.valueOf(ai.loadLabel(pm)), sys);
            e.uid = ai.uid;
            out.add(e);
        }
        out.sort((a, b) -> a.label.compareToIgnoreCase(b.label));
        return out;
    }

    private void applyFilter() {
        String q = etSearch.getText().toString().trim().toLowerCase();
        boolean showSys = cbSystem.isChecked();
        List<AppEntry> filtered = new ArrayList<>();
        for (AppEntry e : all) {
            if (!showSys && e.system) continue;
            if (!q.isEmpty() && !e.label.toLowerCase().contains(q) && !e.pkg.toLowerCase().contains(q)) continue;
            filtered.add(e);
        }
        adapter.submit(filtered);
        tvStatus.setText("共 " + all.size() + " 个应用，当前显示 " + filtered.size());
    }

    private void queryAll() {
        List<AppEntry> list = adapter.items();
        gotResponse = false;
        tvStatus.setText("查询中…");
        for (int i = 0; i < list.size(); i += 30) {
            List<AppEntry> batch = list.subList(i, Math.min(i + 30, list.size()));
            ArrayList<String> pkgs = new ArrayList<>();
            for (AppEntry e : batch) pkgs.add(e.pkg);
            queryBatch(pkgs);
        }
        ui.postDelayed(() -> {
            if (!gotResponse) {
                tvStatus.setText("未收到 PowerKeeper 响应：确认 LSPosed 已启用模块并重启，作用域应为 com.miui.powerkeeper");
                tvDiag.setText("diag: no response");
            }
        }, 4000);
    }

    private void queryBatch(ArrayList<String> pkgs) {
        Intent i = new Intent(Bridge.ACTION_QUERY).setPackage(Bridge.PKG);
        i.putStringArrayListExtra(Bridge.EXTRA_PKGS, pkgs);
        BroadcastReceiver rr = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                Bundle res = getResultExtras(false);
                if (res == null) return;
                gotResponse = true;
                String diag = res.getString(Bridge.KEY_DIAG);
                if (diag != null) tvDiag.setText("diag: " + diag);
                ArrayList<Bundle> list = res.getParcelableArrayList(Bridge.EXTRA_RESULT);
                if (list == null) return;
                for (Bundle b : list) {
                    AppEntry e = adapter.find(b.getString(Bridge.KEY_PKG));
                    if (e == null) continue;
                    e.doze = b.getBoolean(Bridge.KEY_DOZE, false);
                    e.bg = b.getString(Bridge.KEY_BG, "");
                    e.mode = b.getInt(Bridge.KEY_MODE, Bridge.MODE_FOLLOW);
                    String label = b.getString(Bridge.KEY_LABEL);
                    if (label != null) e.label = label;
                }
                adapter.refresh();
                tvStatus.setText("已同步 " + list.size() + " 个应用");
            }
        };
        try {
            sendOrderedBroadcast(i, null, rr, ui, Activity.RESULT_OK, null, null);
        } catch (Throwable t) {
            tvStatus.setText("查询失败: " + t.getMessage());
        }
    }

    private void cycleMode(AppEntry e) {
        int next;
        if (e.mode == Bridge.MODE_FOLLOW) next = Bridge.MODE_ALLOW;
        else if (e.mode == Bridge.MODE_ALLOW) next = Bridge.MODE_DENY;
        else next = Bridge.MODE_FOLLOW;
        e.mode = next;
        if (next == Bridge.MODE_ALLOW) e.doze = true;
        if (next == Bridge.MODE_DENY) e.doze = false;
        adapter.refresh();
        sendSet(e.pkg, next);
        Toast.makeText(this, e.label + " → " + e.modeText(), Toast.LENGTH_SHORT).show();
    }

    private void sendSet(String pkg, int mode) {
        Intent i = new Intent(Bridge.ACTION_SET).setPackage(Bridge.PKG);
        i.putExtra(Bridge.EXTRA_PKG, pkg);
        i.putExtra(Bridge.EXTRA_MODE, mode);
        sendBroadcast(i);
    }

    private void resetAll() {
        Intent i = new Intent(Bridge.ACTION_APPLY_ALL).setPackage(Bridge.PKG);
        sendOrderedBroadcast(i, null, null, null, Activity.RESULT_OK, null, null);
        for (AppEntry e : all) e.mode = Bridge.MODE_FOLLOW;
        adapter.refresh();
        Toast.makeText(this, "已重置为跟随系统", Toast.LENGTH_SHORT).show();
    }

    private void confirmClear() {
        new androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("彻底清除")
                .setMessage("会把动过的东西全部还原到第一次强制之前的状态：\n\n"
                        + "· doze 白名单按原始快照恢复\n"
                        + "· 后台策略恢复原值\n"
                        + "· 删除模块插入的 userTable 行\n"
                        + "· 常驻 always-white 集合里被踢掉的包加回去\n"
                        + "· 清空模块自己的 SharedPreferences\n\n"
                        + "清除后建议重启一次 PowerKeeper 进程（重启手机最稳）。")
                .setNegativeButton("取消", null)
                .setPositiveButton("清除", (d, w) -> doClear())
                .show();
    }

    private void doClear() {
        tvStatus.setText("清除中…");
        Intent i = new Intent(Bridge.ACTION_CLEAR).setPackage(Bridge.PKG);
        BroadcastReceiver rr = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                Bundle res = getResultExtras(false);
                String report = res == null ? null : res.getString(Bridge.KEY_REPORT);
                tvDiag.setText("diag: " + (report == null ? "no response" : report));
                if (report == null) {
                    tvStatus.setText("未收到 PowerKeeper 响应");
                    Toast.makeText(MainActivity.this, "未收到响应", Toast.LENGTH_LONG).show();
                    return;
                }
                tvStatus.setText("已清除");
                Toast.makeText(MainActivity.this, report, Toast.LENGTH_LONG).show();
                for (AppEntry e : all) {
                    e.mode = Bridge.MODE_FOLLOW;
                    e.bg = "";
                }
                load();
            }
        };
        try {
            sendOrderedBroadcast(i, null, rr, ui, Activity.RESULT_OK, null, null);
        } catch (Throwable t) {
            tvStatus.setText("清除失败: " + t.getMessage());
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        io.shutdownNow();
    }
}
