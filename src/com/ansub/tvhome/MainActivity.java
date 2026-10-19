package com.ansub.tvhome;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.media.tv.TvInputInfo;
import android.os.Bundle;
import android.provider.Settings;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextClock;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.Collections;
import java.util.TreeMap;
import java.util.List;

public class MainActivity extends Activity {

    // Top-row apps, in this order of preference:
    //  1. a list saved at install time:  am start -n com.ansub.tvhome/.MainActivity --es apps "pkg1,pkg2"
    //     (--es apps auto  goes back to 2; add --es stamp <unique> so it's applied only once, see saveConfig)
    //  2. apps.txt (built in as Apps.LIST), topped up with other TV apps installed on this TV.
    // Missing apps are skipped. Topping up stops at six apps, but a chosen list can be any length:
    // apps wrap into rows of six, like Apple TV, and the page scrolls down once they don't all fit.
    // On the TV, hold OK on an app (or press Menu) to move or remove it, or add another; that saves list 1.
    private static final String PREFS = "config";
    private static final String SETTINGS = "settings";
    private static final String ADD = "add";
    private static final String[] EDIT_ITEMS = {"Move", "Replace", "Remove from home"};
    private static final int PER_ROW = AppList.PER_ROW, MAX_INPUTS = 4;

    // Layout is designed at 1920x1080 and scaled to the TV's real UI size by ui.px().
    private static final int MARGIN = 96, GAP = 36;
    private static final int ROW1_TOP = 604, ROW_STEP = 224;
    private static final int GRID_MIN_TOP = 400; // more rows push the grid up, but not into the clock
    private Ui ui;
    private Panels panels;

    private FrameLayout root; // the page, inside a vertical scroller
    private int tileW, tileH, gridTop; // app grid geometry, set by buildTiles
    private final List<View> tiles = new ArrayList<>();
    private boolean dirty = true;
    private String focusedKey;
    private final List<String> rowApps = new ArrayList<>(); // the top row as shown, in order
    private String movingKey;   // the app being moved with left/right, if any
    private TextView moveHint;  // shown above the apps while moving
    private boolean longPressed; // OK was held: swallow the key-up so the app doesn't open
    private StatusLine statusLine; // weather, free memory and VPN, under the clock

    private final BroadcastReceiver packagesChanged = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) { dirty = true; }
    };

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        saveConfig(getIntent());
        Ui.loadFonts();
        ui = new Ui(this, getResources().getDisplayMetrics().widthPixels / 1920f);
        panels = new Panels(this, ui);
        getWindow().setBackgroundDrawable(ui.background());

        ScrollView scroller = new ScrollView(this);
        scroller.setFillViewport(true);
        scroller.setVerticalScrollBarEnabled(false);
        scroller.setOverScrollMode(View.OVER_SCROLL_NEVER);
        scroller.setClipChildren(false);
        root = new FrameLayout(this);
        root.setClipChildren(false);
        scroller.addView(root, new FrameLayout.LayoutParams(-1, -2));

        LinearLayout clock = new LinearLayout(this);
        clock.setOrientation(LinearLayout.VERTICAL);
        TextClock date = ui.clock("EEEE, d MMMM", "EEEE, d MMMM", ui.px(30), 0xA8FFFFFF, Ui.regular);
        TextClock time = ui.clock("h:mm", "H:mm", ui.px(176), Color.WHITE, Ui.semibold);
        time.setLetterSpacing(-0.028f);
        time.setFontFeatureSettings("tnum");
        time.setIncludeFontPadding(false);
        clock.addView(date);
        LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(-2, -2);
        tp.topMargin = ui.px(-20); // pull the big digits up under the date
        clock.addView(time, tp);
        TextView status = ui.text(ui.px(30), 0xA8FFFFFF, Ui.regular);
        status.setSingleLine(true);
        status.setEllipsize(android.text.TextUtils.TruncateAt.END);
        status.setFontFeatureSettings("tnum"); // digits keep their width as memory changes
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(
                getResources().getDisplayMetrics().widthPixels - 2 * ui.px(MARGIN), -2);
        sp.topMargin = ui.px(8);
        clock.addView(status, sp);
        statusLine = new StatusLine(this, status, getSharedPreferences(PREFS, 0));
        statusLine.saveCity(getIntent());
        root.addView(clock, Ui.at(ui.px(MARGIN), ui.px(92), -2, -2));

        moveHint = ui.text(ui.px(24), 0xA8FFFFFF, Ui.regular);
        moveHint.setText("Use the arrows to move it, then press OK");
        moveHint.setVisibility(View.GONE);
        root.addView(moveHint, Ui.at(ui.px(MARGIN), ui.px(ROW1_TOP - 64), -2, -2));

        setContentView(scroller);

        IntentFilter f = new IntentFilter();
        f.addAction(Intent.ACTION_PACKAGE_ADDED);
        f.addAction(Intent.ACTION_PACKAGE_REMOVED);
        f.addAction(Intent.ACTION_PACKAGE_CHANGED);
        f.addDataScheme("package");
        if (android.os.Build.VERSION.SDK_INT >= 33) registerReceiver(packagesChanged, f, Context.RECEIVER_EXPORTED);
        else registerReceiver(packagesChanged, f);
    }

    @Override
    protected void onDestroy() {
        unregisterReceiver(packagesChanged);
        super.onDestroy();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        statusLine.saveCity(intent);
        if (saveConfig(intent)) buildTiles();
    }

    // Saves an app list passed with --es apps "...". Returns true if it changed.
    // When Android kills the home screen in the background, it can start it again with the same old
    // intent; the stamp install.sh sends stops that from undoing changes made on the TV since.
    private boolean saveConfig(Intent intent) {
        String apps = intent == null ? null : intent.getStringExtra("apps");
        if (apps == null) return false;
        SharedPreferences prefs = getSharedPreferences(PREFS, 0);
        String stamp = intent.getStringExtra("stamp");
        if (stamp != null && stamp.equals(prefs.getString("stamp", null))) return false;
        apps = apps.trim();
        SharedPreferences.Editor e = prefs.edit();
        if (stamp != null) e.putString("stamp", stamp);
        if (apps.isEmpty() || apps.equalsIgnoreCase("auto")) e.remove("apps");
        else e.putString("apps", apps);
        e.apply();
        dirty = true;
        return true;
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Rebuild only when apps were installed, removed or updated.
        if (dirty) {
            dirty = false;
            buildTiles();
        }
        View f = getCurrentFocus();
        if (f == null && !tiles.isEmpty()) tiles.get(0).requestFocus();
        statusLine.start();
    }

    @Override
    protected void onPause() {
        statusLine.stop();
        super.onPause();
    }

    // Number keys open the app in that spot (1 is the first). Red opens Settings, blue adds an app.
    @Override
    public boolean onKeyDown(int code, KeyEvent ev) {
        boolean shortcut = (code >= KeyEvent.KEYCODE_1 && code <= KeyEvent.KEYCODE_9)
                || code == KeyEvent.KEYCODE_PROG_RED || code == KeyEvent.KEYCODE_PROG_BLUE;
        if (shortcut && (ev.getRepeatCount() > 0 || movingKey != null)) return true; // once per press; not mid-move
        if (code >= KeyEvent.KEYCODE_1 && code <= KeyEvent.KEYCODE_9) {
            int i = code - KeyEvent.KEYCODE_1;
            if (i < rowApps.size()) open(rowApps.get(i));
            return true;
        }
        if (code == KeyEvent.KEYCODE_PROG_RED) { open(SETTINGS); return true; }
        if (code == KeyEvent.KEYCODE_PROG_BLUE) { open(ADD); return true; }
        return super.onKeyDown(code, ev);
    }

    @Override
    public void onBackPressed() { /* home screen: nothing to go back to */ }

    private void buildTiles() {
        // While tiles are swapped out, removing the focused one would hand focus down the row (and
        // overwrite focusedKey), so hold focus off until the new tiles are in.
        String want = focusedKey;
        root.setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);
        for (View t : tiles) {
            View wrap = (View) t.getParent();
            ((ViewGroup) wrap.getParent()).removeView(wrap);
        }
        tiles.clear();
        rowApps.clear();
        movingKey = null;
        moveHint.setVisibility(View.GONE);
        statusLine.setVisible(true);

        PackageManager pm = getPackageManager();
        int m = ui.px(MARGIN), g = ui.px(GAP);
        int w = (getResources().getDisplayMetrics().widthPixels - 2 * m - g * (PER_ROW - 1)) / PER_ROW;
        int h = w * 9 / 16;
        tileW = w;
        tileH = h;

        List<String> apps = AppList.forHome(this, getSharedPreferences(PREFS, 0).getString("apps", null));
        // Apps fill rows of six. With more rows the grid starts higher, so up to two rows of apps and
        // the bottom row still fit on screen; past that the page scrolls.
        int launchable = 0;
        for (String pkg : apps) if (AppList.launchIntent(pm, pkg) != null) launchable++;
        int rows = Math.max(1, (launchable + PER_ROW - 1) / PER_ROW);
        gridTop = Math.max(ui.px(GRID_MIN_TOP), ui.px(ROW1_TOP) - (rows - 1) * ui.px(ROW_STEP));
        int bottomTop = gridTop + rows * ui.px(ROW_STEP);
        ((FrameLayout.LayoutParams) moveHint.getLayoutParams()).topMargin = gridTop - ui.px(64);
        root.setMinimumHeight(bottomTop + h + ui.px(MARGIN));

        for (String pkg : apps) {
            Intent li = AppList.launchIntent(pm, pkg);
            if (li == null) continue;
            Drawable banner = null;
            try {
                banner = pm.getActivityBanner(li);
                if (banner == null) banner = pm.getApplicationBanner(pkg);
            } catch (Exception ignored) {}
            String label = pkg;
            try { label = pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString(); } catch (Exception ignored) {}
            // Apps without a TV banner get a plain tile with their normal icon.
            Drawable icon = null;
            if (banner == null) try { icon = pm.getApplicationIcon(pkg); } catch (Exception ignored) {}
            int i = rowApps.size();
            rowApps.add(pkg);
            addTile(root, pkg, label, banner, null, icon, gridX(i), gridY(i), w, h);
        }

        // Bottom row: Play Store, HDMI inputs, Settings and Add app, six at most.
        int col = 0;
        boolean store = !apps.contains(AppList.PLAY_STORE) && AppList.launchIntent(pm, AppList.PLAY_STORE) != null;
        List<TvInputInfo> inputs = Inputs.hdmiInputs(this, MAX_INPUTS);
        int room = PER_ROW - 2 - (store ? 1 : 0);
        if (inputs.size() > room) inputs = inputs.subList(0, room);
        if (store)
            addTile(root, AppList.PLAY_STORE, "Play Store", null, LineIcon.PLAY, null, m + col++ * (w + g), bottomTop, w, h);
        for (TvInputInfo in : inputs)
            addTile(root, in.getId(), Inputs.inputLabel(this, in), null, LineIcon.HDMI, null, m + col++ * (w + g), bottomTop, w, h);
        addTile(root, SETTINGS, "Settings", null, LineIcon.SETTINGS, null, m + col++ * (w + g), bottomTop, w, h);
        addTile(root, ADD, "Add app", null, LineIcon.PLUS, null, m + col * (w + g), bottomTop, w, h);

        root.setDescendantFocusability(ViewGroup.FOCUS_AFTER_DESCENDANTS);
        View restore = null;
        for (View t : tiles) if (t.getTag().equals(want)) restore = t;
        if (restore != null) restore.requestFocus();
    }

    private void addTile(ViewGroup parent, final String key, String label, Drawable banner, int[] lineIcon, Drawable appIcon,
                         int x, int y, int w, int h) {
        FrameLayout card = new FrameLayout(this);
        final GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(ui.px(Ui.RADIUS));
        if (banner != null) {
            bg.setColor(0xFF1E1A1C);
            ImageView iv = new ImageView(this);
            iv.setImageDrawable(ui.fit(banner, w, h));
            card.addView(iv, new FrameLayout.LayoutParams(-1, -1));
        } else {
            // Quiet "glass" tile: icon above label.
            bg.setColor(0x13FFFFFF);
            bg.setStroke(Math.max(1, ui.px(2)), 0x17FFFFFF);
            LinearLayout inner = new LinearLayout(this);
            inner.setOrientation(LinearLayout.VERTICAL);
            inner.setGravity(Gravity.CENTER);
            ImageView iv = new ImageView(this);
            int size = lineIcon != null ? ui.px(44) : ui.px(64);
            if (lineIcon != null) iv.setImageDrawable(new LineIcon(lineIcon));
            else if (appIcon != null) iv.setImageDrawable(ui.fit(appIcon, size, size));
            inner.addView(iv, new LinearLayout.LayoutParams(size, size));
            TextView t = ui.text(ui.px(24), 0xE0FFFFFF, Ui.regular);
            t.setText(label);
            t.setSingleLine(true);
            t.setEllipsize(android.text.TextUtils.TruncateAt.END);
            t.setGravity(Gravity.CENTER);
            t.setPadding(ui.px(16), 0, ui.px(16), 0);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
            lp.topMargin = ui.px(12);
            inner.addView(t, lp);
            card.addView(inner, new FrameLayout.LayoutParams(-1, -1));
        }
        card.setBackground(bg);
        card.setClipToOutline(true);
        card.setFocusable(true);
        card.setTag(key);
        card.setContentDescription(label); // banner tiles have no visible name until focused

        // Banner tiles show their name underneath when focused.
        final TextView caption = banner == null ? null : ui.text(ui.px(24), Color.WHITE, Ui.regular);
        if (caption != null) {
            caption.setText(label);
            caption.setGravity(Gravity.CENTER_HORIZONTAL);
            caption.setVisibility(View.INVISIBLE);
        }

        final boolean glass = banner == null;
        card.setOnFocusChangeListener((v, has) -> {
            if (has) focusedKey = key;
            if (glass) bg.setColor(has ? 0x38FFFFFF : 0x13FFFFFF);
            v.animate().scaleX(has ? 1.1f : 1f).scaleY(has ? 1.1f : 1f).translationZ(has ? ui.px(26) : 0)
                    .setDuration(140).withLayer().start();
            if (caption != null) caption.setVisibility(has ? View.VISIBLE : View.INVISIBLE);
        });
        final boolean editable = rowApps.contains(key);
        card.setOnClickListener(v -> open(key));
        if (editable) card.setOnLongClickListener(v -> { showEditMenu(key); return true; });
        card.setOnKeyListener((v, code, ev) -> {
            boolean ok = code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER
                    || code == KeyEvent.KEYCODE_NUMPAD_ENTER;
            boolean down = ev.getAction() == KeyEvent.ACTION_DOWN;
            if (key.equals(movingKey)) return moveKey(key, code, ok, down);
            if (editable && code == KeyEvent.KEYCODE_MENU) {
                if (!down) showEditMenu(key);
                return true;
            }
            if (!ok) return false;
            if (down) {
                if (ev.getRepeatCount() == 0) longPressed = false;
                else if (editable && !longPressed && (ev.isLongPress()
                        || ev.getEventTime() - ev.getDownTime() >= ViewConfiguration.getLongPressTimeout())) {
                    longPressed = true;
                    showEditMenu(key);
                }
            } else if (!longPressed) {
                open(key);
            }
            return true;
        });

        FrameLayout wrap = new FrameLayout(this);
        wrap.setClipChildren(false);
        wrap.addView(card, new FrameLayout.LayoutParams(w, h));
        if (caption != null) {
            FrameLayout.LayoutParams cp = new FrameLayout.LayoutParams(w, -2);
            cp.topMargin = h + ui.px(22);
            wrap.addView(caption, cp);
        }
        parent.addView(wrap, Ui.at(x, y, w, -2));
        tiles.add(card);
    }

    private void open(String key) {
        Intent i;
        if (ADD.equals(key)) {
            showAddMenu(-1);
            return;
        } else if (SETTINGS.equals(key)) {
            i = new Intent(Settings.ACTION_SETTINGS);
        } else if (Inputs.isInput(key)) {
            Inputs.openInput(this, key);
            return;
        } else {
            i = AppList.launchIntent(getPackageManager(), key);
            if (i == null) return;
        }
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try { startActivity(i); } catch (Exception ignored) {}
    }

    // Hold OK (or Menu) on a top-row app: move it, swap it for another, or take it off the home
    // screen. Removing only takes the tile away: the app stays installed and can be added back.
    private void showEditMenu(final String key) {
        final int index = rowApps.indexOf(key);
        if (index < 0) return;
        panels.dropdown(tile(key), EDIT_ITEMS, which -> {
            if (which == 0) startMove(key);
            else if (which == 1) showAddMenu(index);
            else {
                rowApps.remove(index);
                saveRow();
                focusedKey = rowApps.isEmpty() ? ADD : rowApps.get(Math.min(index, rowApps.size() - 1));
                buildTiles();
            }
        });
    }

    // Every launchable app that isn't on the top row yet, by name. The pick is added at the end,
    // or takes the place of the app at replaceIndex (-1 to add).
    private void showAddMenu(final int replaceIndex) {
        TreeMap<String, String> byLabel = AppList.addable(this, rowApps);
        if (byLabel.isEmpty()) {
            panels.panel("Every app is already here", Collections.singletonList("OK"), null, which -> {});
            return;
        }
        final List<String> pkgs = new ArrayList<>(byLabel.values());
        String title = replaceIndex < 0 ? "Add an app" : "Replace " + AppList.labelOf(this, rowApps.get(replaceIndex));
        panels.panel(title, new ArrayList<>(byLabel.keySet()), pkgs, which -> {
            if (replaceIndex < 0) rowApps.add(pkgs.get(which));
            else rowApps.set(replaceIndex, pkgs.get(which));
            saveRow();
            focusedKey = pkgs.get(which);
            buildTiles();
        });
    }

    // Saves the top row as the app list, so it no longer follows apps.txt.
    private void saveRow() {
        getSharedPreferences(PREFS, 0).edit().putString("apps", android.text.TextUtils.join(",", rowApps)).apply();
    }

    // Where app number i sits in the grid.
    private int gridX(int i) {
        return ui.px(MARGIN) + (i % PER_ROW) * (tileW + ui.px(GAP));
    }

    private int gridY(int i) {
        return gridTop + (i / PER_ROW) * ui.px(ROW_STEP);
    }

    private View tile(String key) {
        for (View t : tiles) if (t.getTag().equals(key)) return t;
        return null;
    }

    // Move mode: the tile gets an outline, the arrows move it through the grid, OK or Back finishes.
    private void startMove(String key) {
        View card = tile(key);
        if (card == null) return;
        movingKey = key;
        GradientDrawable outline = new GradientDrawable();
        outline.setCornerRadius(ui.px(Ui.RADIUS));
        outline.setStroke(ui.px(4), Color.WHITE);
        ((FrameLayout) card).setForeground(outline);
        moveHint.setVisibility(View.VISIBLE);
        statusLine.setVisible(false); // the hint sits where the status line is when the grid is high
        card.requestFocus();
    }

    private boolean moveKey(String key, int code, boolean ok, boolean down) {
        int step = code == KeyEvent.KEYCODE_DPAD_LEFT ? -1 : code == KeyEvent.KEYCODE_DPAD_RIGHT ? 1
                : code == KeyEvent.KEYCODE_DPAD_UP ? -PER_ROW : code == KeyEvent.KEYCODE_DPAD_DOWN ? PER_ROW : 0;
        if (step != 0) {
            int from = rowApps.indexOf(key), to = from + step;
            if (down && to >= 0 && to < rowApps.size()) {
                // Slide the tiles into their new places: no rebuild, so it stays quick on slow TVs.
                rowApps.add(to, rowApps.remove(from));
                for (int i = Math.min(from, to); i <= Math.max(from, to); i++) {
                    View wrap = (View) tile(rowApps.get(i)).getParent();
                    FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) wrap.getLayoutParams();
                    lp.leftMargin = gridX(i);
                    lp.topMargin = gridY(i);
                    wrap.setLayoutParams(lp);
                }
                // Scroll along with it once it has moved.
                final View card = tile(key);
                card.post(() -> card.requestRectangleOnScreen(
                        new android.graphics.Rect(0, 0, card.getWidth(), card.getHeight())));
            }
            return true;
        }
        if (ok || code == KeyEvent.KEYCODE_BACK || code == KeyEvent.KEYCODE_MENU) {
            if (!down) {
                movingKey = null;
                longPressed = false;
                ((FrameLayout) tile(key)).setForeground(null);
                moveHint.setVisibility(View.GONE);
                statusLine.setVisible(true);
                saveRow();
            }
            return true;
        }
        return true; // up/down and everything else stay put while moving
    }

}
