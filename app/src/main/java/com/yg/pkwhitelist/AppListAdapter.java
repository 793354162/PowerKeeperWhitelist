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

public class AppListAdapter extends RecyclerView.Adapter<AppListAdapter.VH> {

    public interface OnClick {
        void onClick(AppEntry entry);
    }

    private final List<AppEntry> data = new ArrayList<>();
    private final OnClick onClick;

    public AppListAdapter(OnClick onClick) {
        this.onClick = onClick;
    }

    public void submit(List<AppEntry> list) {
        data.clear();
        data.addAll(list);
        notifyDataSetChanged();
    }

    public List<AppEntry> items() {
        return data;
    }

    public AppEntry find(String pkg) {
        for (AppEntry e : data) {
            if (e.pkg.equals(pkg)) return e;
        }
        return null;
    }

    @NonNull
    @Override
    public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_app, parent, false);
        return new VH(v);
    }

    @Override
    public void onBindViewHolder(@NonNull VH h, int position) {
        AppEntry e = data.get(position);
        h.title.setText(e.label);
        h.sub.setText(e.pkg);

        if (e.doze) {
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

    @Override
    public int getItemCount() {
        return data.size();
    }

    static class VH extends RecyclerView.ViewHolder {
        final TextView title;
        final TextView sub;
        final TextView doze;
        final TextView policy;
        final TextView mode;

        VH(@NonNull View itemView) {
            super(itemView);
            title = itemView.findViewById(R.id.tv_title);
            sub = itemView.findViewById(R.id.tv_sub);
            doze = itemView.findViewById(R.id.tv_doze);
            policy = itemView.findViewById(R.id.tv_policy);
            mode = itemView.findViewById(R.id.tv_mode);
        }
    }
}
