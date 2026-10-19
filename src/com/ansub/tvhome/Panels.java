package com.ansub.tvhome;

import android.app.Activity;
import android.app.Dialog;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.BaseAdapter;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;

import java.util.List;

// The side panel (pick an app) and the small dropdown menu (move, replace, remove).
final class Panels {
    interface Pick { void at(int which); }

    private final Activity a;
    private final Ui ui;

    Panels(Activity a, Ui ui) {
        this.a = a;
        this.ui = ui;
    }

    // A side panel in the home screen's own look: a title, then one row per choice, with the same
    // glass focus as the tiles. Back closes it. iconPkgs (or null) puts each app's icon by its name.
    void panel(String title, final List<String> labels, final List<String> iconPkgs, final Pick pick) {
        final Dialog d = new Dialog(a, android.R.style.Theme_Material_Dialog_NoActionBar);
        LinearLayout box = new LinearLayout(a);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(ui.px(48), ui.px(92), ui.px(48), ui.px(48));

        TextView head = ui.text(ui.px(40), Color.WHITE, Ui.semibold);
        head.setText(title);
        head.setSingleLine(true);
        head.setEllipsize(android.text.TextUtils.TruncateAt.END);
        head.setPadding(ui.px(24), 0, ui.px(24), 0);
        box.addView(head);

        ListView list = new ListView(a);
        list.setDivider(null);
        list.setVerticalScrollBarEnabled(false);
        list.setOverScrollMode(View.OVER_SCROLL_NEVER);
        GradientDrawable focus = new GradientDrawable();
        focus.setCornerRadius(ui.px(Ui.RADIUS));
        focus.setColor(0x38FFFFFF);
        list.setSelector(focus);
        // Icons load as rows appear, drawn small to save RAM.
        final PackageManager pm = a.getPackageManager();
        final int size = ui.px(48);
        final Drawable[] icons = new Drawable[labels.size()];
        list.setAdapter(new BaseAdapter() {
            @Override public int getCount() { return labels.size(); }
            @Override public Object getItem(int pos) { return labels.get(pos); }
            @Override public long getItemId(int pos) { return pos; }
            @Override public View getView(int pos, View convert, ViewGroup parent) {
                TextView t = convert != null ? (TextView) convert : ui.text(ui.px(30), 0xE6FFFFFF, Ui.regular);
                t.setText(labels.get(pos));
                t.setSingleLine(true);
                t.setEllipsize(android.text.TextUtils.TruncateAt.END);
                t.setGravity(Gravity.CENTER_VERTICAL);
                t.setMinHeight(ui.px(88));
                t.setPadding(ui.px(24), 0, ui.px(24), 0);
                Drawable icon = null;
                if (iconPkgs != null) {
                    if (icons[pos] == null) {
                        try { icons[pos] = ui.fit(pm.getApplicationIcon(iconPkgs.get(pos)), size, size); }
                        catch (Exception e) { icons[pos] = new ColorDrawable(Color.TRANSPARENT); }
                        icons[pos].setBounds(0, 0, size, size);
                    }
                    icon = icons[pos];
                }
                t.setCompoundDrawablesRelative(icon, null, null, null);
                t.setCompoundDrawablePadding(ui.px(24));
                return t;
            }
        });
        list.setOnItemClickListener((parent, v, pos, id) -> {
            d.dismiss();
            pick.at(pos);
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, 0, 1f);
        lp.topMargin = ui.px(36);
        box.addView(list, lp);

        d.setContentView(box);
        Window w = d.getWindow();
        w.setBackgroundDrawable(new ColorDrawable(0xF7161113));
        w.setLayout(ui.px(640), -1);
        w.setGravity(Gravity.END);
        w.setDimAmount(0.5f);
        d.show();
    }

    // A small menu that drops down under a tile, in the same glass as the tiles. Opens above the
    // tile when there's no room below. Back closes it.
    void dropdown(View anchor, String[] items, final Pick pick) {
        final Dialog d = new Dialog(a, android.R.style.Theme_Material_Dialog_NoActionBar);
        LinearLayout box = new LinearLayout(a);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = ui.px(10), rowH = ui.px(64);
        box.setPadding(pad, pad, pad, pad);
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(ui.px(Ui.RADIUS));
        bg.setColor(0xFF231D1F);
        bg.setStroke(Math.max(1, ui.px(2)), 0x17FFFFFF);
        box.setBackground(bg);
        for (int i = 0; i < items.length; i++) {
            final int which = i;
            final TextView t = ui.text(ui.px(26), 0xD9FFFFFF, Ui.regular);
            t.setText(items[i]);
            t.setSingleLine(true);
            t.setGravity(Gravity.CENTER_VERTICAL);
            t.setPadding(ui.px(22), 0, ui.px(22), 0);
            final GradientDrawable focus = new GradientDrawable();
            focus.setCornerRadius(ui.px(Ui.RADIUS - pad));
            focus.setColor(Color.TRANSPARENT);
            t.setBackground(focus);
            t.setFocusable(true);
            t.setOnFocusChangeListener((v, has) -> {
                focus.setColor(has ? 0x38FFFFFF : Color.TRANSPARENT);
                t.setTextColor(has ? Color.WHITE : 0xD9FFFFFF);
            });
            t.setOnClickListener(v -> {
                d.dismiss();
                pick.at(which);
            });
            box.addView(t, new LinearLayout.LayoutParams(-1, rowH));
        }
        d.setContentView(box);

        // Line it up with the tile as drawn (focused tiles are scaled up), below its name.
        int[] at = new int[2];
        anchor.getLocationOnScreen(at);
        int tileBottom = at[1] + Math.round(anchor.getHeight() * anchor.getScaleY());
        int w = Math.max(Math.round(anchor.getWidth() * anchor.getScaleX()), ui.px(320));
        int h = items.length * rowH + 2 * pad;
        int y = tileBottom + ui.px(64);
        if (y + h > a.getResources().getDisplayMetrics().heightPixels - ui.px(24)) y = at[1] - h - ui.px(16);
        Window win = d.getWindow();
        win.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        win.clearFlags(android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND);
        win.setGravity(Gravity.TOP | Gravity.START);
        android.view.WindowManager.LayoutParams lp = win.getAttributes();
        lp.x = at[0];
        lp.y = y;
        lp.width = w;
        lp.height = h;
        win.setAttributes(lp);
        d.show();
    }
}
