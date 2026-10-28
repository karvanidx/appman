package io.github.karvanidx.appman;

import android.content.Context;
import android.graphics.Color;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.CheckBox;
import android.widget.ImageView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public class AppAdapter extends BaseAdapter {
    private final LayoutInflater inflater;
    private List<AppEntry> items = new ArrayList<>();
    private boolean selectionMode = false;
    private Set<String> selected = new HashSet<>();
    private final int selectedColor = Color.rgb(227, 242, 253);

    public AppAdapter(Context ctx) {
        inflater = LayoutInflater.from(ctx);
    }

    public void setItems(List<AppEntry> items) {
        this.items = items;
    }

    public void setSelectionState(boolean mode, Set<String> selectedPkgs) {
        this.selectionMode = mode;
        this.selected = selectedPkgs == null ? new HashSet<String>() : selectedPkgs;
        notifyDataSetChanged();
    }

    @Override
    public int getCount() {
        return items.size();
    }

    @Override
    public AppEntry getItem(int position) {
        return items.get(position);
    }

    @Override
    public long getItemId(int position) {
        return position;
    }

    static class Holder {
        CheckBox check;
        ImageView icon;
        TextView name, pkg, size, type;
        View root;
    }

    @Override
    public View getView(int position, View convertView, ViewGroup parent) {
        Holder h;
        if (convertView == null) {
            convertView = inflater.inflate(R.layout.row_app, parent, false);
            h = new Holder();
            h.root = convertView;
            h.check = convertView.findViewById(R.id.check);
            h.icon = convertView.findViewById(R.id.icon);
            h.name = convertView.findViewById(R.id.name);
            h.pkg = convertView.findViewById(R.id.pkg);
            h.size = convertView.findViewById(R.id.size);
            h.type = convertView.findViewById(R.id.type);
            convertView.setTag(h);
        } else {
            h = (Holder) convertView.getTag();
        }

        AppEntry e = items.get(position);
        boolean isSelected = selected.contains(e.pkg);

        h.name.setText(e.label);
        h.pkg.setText(e.pkg);
        h.size.setText(Utils.humanSize(e.apkSize));
        h.type.setText(e.system ? "System" : "User");

        if (e.icon != null) h.icon.setImageDrawable(e.icon);
        else h.icon.setImageResource(android.R.drawable.sym_def_app_icon);

        h.check.setVisibility(selectionMode ? View.VISIBLE : View.GONE);
        h.check.setChecked(isSelected);

        if (selectionMode && isSelected) {
            h.root.setBackgroundColor(selectedColor);
        } else {
            h.root.setBackgroundColor(Color.WHITE);
        }
        return convertView;
    }
}
