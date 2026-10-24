package com.yg.pkwhitelist;

import android.content.res.ColorStateList;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.List;

public class AppListAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {

    private static final int TYPE_HEADER = 0;
    private static final int TYPE_APP = 1;

    public interface OnClick {
        void onClick(AppEntry entry);
    }

    private static class Row {
        int type;
        String title;
        AppEntry entry;
    }

    private final List<AppEntry> source = new ArrayList<>();
    private final List<Row> rows = new ArrayList<>();
    private final OnClick onClick;

    public AppListAdapter(OnClick onClick) {
        this.onClick = onClick;
    }

    /** 传入过滤后的应用列表，内部按白名单/非白名单分组 */
    public void submit(List<AppEntry> list) {
        source.clear();
        source.addAll(list);
        rebuild();
        notifyDataSetChanged();
    }

    /** 状态更新后重新分组（doze 状态变化会影响归属） */
    public void refresh() {
        rebuild();
        notifyDataSetChanged();
    }

    private void rebuild() {
        rows.clear();
        List<AppEntry> white = new ArrayList<>();
        List<AppEntry> other = new ArrayList<>();
        for (AppEntry e : source) {
            if (e.doze) white.add(e);
            else other.add(e);
        }
        if (!white.isEmpty()) {
            rows.add(header("白名单 · " + white.size()));
            for (AppEntry e : white) rows.add(app(e));
        }
        if (!other.isEmpty()) {
            rows.add(header("非白名单 · " + other.size()));
            for (AppEntry e : other) rows.add(app(e));
        }
    }

    private static Row header(String t) {
        Row r = new Row();
        r.type = TYPE_HEADER;
        r.title = t;
        return r;
    }

    private static Row app(AppEntry e) {
        Row r = new Row();
        r.type = TYPE_APP;
        r.entry = e;
        return r;
    }

    public List<AppEntry> items() {
        return source;
    }

    public AppEntry find(String pkg) {
        for (AppEntry e : source) {
            if (e.pkg.equals(pkg)) return e;
        }
        return null;
    }

    @Override
    public int getItemViewType(int position) {
        return rows.get(position).type;
    }

    @Override
    public int getItemCount() {
        return rows.size();
    }

    @NonNull
    @Override
    public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        LayoutInflater inf = LayoutInflater.from(parent.getContext());
        if (viewType == TYPE_HEADER) {
            return new HeaderVH(inf.inflate(R.layout.item_header, parent, false));
        }
        return new AppVH(inf.inflate(R.layout.item_app, parent, false));
    }

    @Override
    public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
        Row row = rows.get(position);
        if (row.type == TYPE_HEADER) {
            ((HeaderVH) holder).title.setText(row.title);
            return;
        }
        AppVH h = (AppVH) holder;
        AppEntry e = row.entry;
        h.title.setText(e.label);
        h.sub.setText(e.pkg);

        if (e.launcher) {
            chip(h.doze, "桌面", R.color.chip_gray_fg, R.color.chip_gray_bg);
        } else if (e.doze) {
            chip(h.doze, "Doze 白名单", R.color.chip_green_fg, R.color.chip_green_bg);
        } else {
            chip(h.doze, "非白名单", R.color.chip_red_fg, R.color.chip_red_bg);
        }

        String bg = e.bg == null ? "" : e.bg;
        switch (bg) {
            case "noRestrict":
                chip(h.policy, "无限制", R.color.chip_green_fg, R.color.chip_green_bg);
                break;
            case "miuiAuto":
                chip(h.policy, "智能", R.color.chip_blue_fg, R.color.chip_blue_bg);
                break;
            case "restrictBg":
                chip(h.policy, "受限", R.color.chip_orange_fg, R.color.chip_orange_bg);
                break;
            case "noBg":
                chip(h.policy, "禁止后台", R.color.chip_red_fg, R.color.chip_red_bg);
                break;
            default:
                chip(h.policy, "未知", R.color.chip_gray_fg, R.color.chip_gray_bg);
                break;
        }

        switch (e.mode) {
            case Bridge.MODE_ALLOW:
                chip(h.mode, "强制允许", R.color.chip_green_fg, R.color.chip_green_bg);
                break;
            case Bridge.MODE_DENY:
                chip(h.mode, "强制禁止", R.color.chip_red_fg, R.color.chip_red_bg);
                break;
            default:
                chip(h.mode, "跟随系统", R.color.chip_gray_fg, R.color.chip_gray_bg);
                break;
        }

        h.itemView.setOnClickListener(v -> {
            if (onClick != null) onClick.onClick(e);
        });
    }

    private static void chip(TextView v, String text, int fgRes, int bgRes) {
        v.setText(text);
        v.setTextColor(ContextCompat.getColor(v.getContext(), fgRes));
        v.setBackgroundTintList(ColorStateList.valueOf(
                ContextCompat.getColor(v.getContext(), bgRes)));
    }

    static class HeaderVH extends RecyclerView.ViewHolder {
        final TextView title;

        HeaderVH(@NonNull View itemView) {
            super(itemView);
            title = itemView.findViewById(R.id.tv_header);
        }
    }

    static class AppVH extends RecyclerView.ViewHolder {
        final TextView title;
        final TextView sub;
        final TextView doze;
        final TextView policy;
        final TextView mode;

        AppVH(@NonNull View itemView) {
            super(itemView);
            title = itemView.findViewById(R.id.tv_title);
            sub = itemView.findViewById(R.id.tv_sub);
            doze = itemView.findViewById(R.id.tv_doze);
            policy = itemView.findViewById(R.id.tv_policy);
            mode = itemView.findViewById(R.id.tv_mode);
        }
    }
}
