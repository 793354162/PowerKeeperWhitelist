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
    private AppListAdapter adapter;

    private final List<AppEntry> all = new ArrayList<>();
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final java.util.concurrent.ExecutorService io = Executors.newSingleThreadExecutor();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        etSearch = findViewById(R.id.et_search);
        cbSystem = findViewById(R.id.cb_system);
        tvStatus = findViewById(R.id.tv_status);
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

        load();
    }

    private void load() {
        tvStatus.setText("加载应用中...");
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
        for (int i = 0; i < list.size(); i += 30) {
            List<AppEntry> batch = list.subList(i, Math.min(i + 30, list.size()));
            ArrayList<String> pkgs = new ArrayList<>();
            for (AppEntry e : batch) pkgs.add(e.pkg);
            queryBatch(pkgs);
        }
    }

    private void queryBatch(ArrayList<String> pkgs) {
        Intent i = new Intent(Bridge.ACTION_QUERY).setPackage(Bridge.PKG);
        i.putStringArrayListExtra(Bridge.EXTRA_PKGS, pkgs);
        BroadcastReceiver rr = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                Bundle res = getResultExtras(false);
                if (res == null) return;
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
                adapter.notifyDataSetChanged();
            }
        };
        try {
            sendOrderedBroadcast(i, null, rr, ui, Activity.RESULT_OK, null, null);
        } catch (Throwable t) {
            Toast.makeText(this, "查询失败: " + t.getMessage(), Toast.LENGTH_SHORT).show();
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
        adapter.notifyDataSetChanged();
        sendSet(e.pkg, next);
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
        adapter.notifyDataSetChanged();
        Toast.makeText(this, "已重置为跟随系统", Toast.LENGTH_SHORT).show();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        io.shutdownNow();
    }
}
