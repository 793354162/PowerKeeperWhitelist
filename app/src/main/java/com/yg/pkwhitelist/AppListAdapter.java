package com.yg.pkwhitelist;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
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
        h.status.setText((e.doze ? "Doze:✓" : "Doze:✗") + "  " + e.bgText() + "  " + e.modeText());
        h.itemView.setOnClickListener(v -> {
            if (onClick != null) onClick.onClick(e);
        });
    }

    @Override
    public int getItemCount() {
        return data.size();
    }

    static class VH extends RecyclerView.ViewHolder {
        final TextView title;
        final TextView sub;
        final TextView status;

        VH(@NonNull View itemView) {
            super(itemView);
            title = itemView.findViewById(R.id.tv_title);
            sub = itemView.findViewById(R.id.tv_sub);
            status = itemView.findViewById(R.id.tv_status);
        }
    }
}
