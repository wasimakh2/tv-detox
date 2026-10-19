package com.ansub.tvhome;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.media.tv.TvContract;
import android.media.tv.TvInputInfo;
import android.media.tv.TvInputManager;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

// The TV's HDMI inputs: listing them and switching to one.
final class Inputs {
    private Inputs() {}

    // Live TV apps an HDMI tile can open, in order. Droidlogic (Amlogic) TVs ship Live TV under their
    // own package, with TvActivity as its entry point.
    private static final String[][] LIVE_TV = {{"com.android.tv", "com.android.tv.MainActivity"},
            {"com.droidlogic.android.tv", "com.android.tv.TvActivity"}};

    // Opens an HDMI input: the stock Live TV app first, then Droidlogic's, then whatever the TV picks.
    // Each one is tried only when the one before it can't start, so a TV where the stock app already
    // worked takes exactly the same path as before.
    static void openInput(Activity a, String id) {
        Intent view = new Intent(Intent.ACTION_VIEW, TvContract.buildChannelUriForPassthroughInput(id));
        List<Intent> tries = new ArrayList<>();
        for (String[] live : LIVE_TV) {
            Intent t = new Intent(view).setClassName(live[0], live[1]);
            if (t.resolveActivity(a.getPackageManager()) != null) tries.add(t);
        }
        tries.add(view);
        for (Intent t : tries) {
            t.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            try {
                a.startActivity(t);
                return;
            } catch (Exception ignored) {}
        }
    }

    static boolean isInput(String key) {
        return key.indexOf('/') > 0;
    }

    // The TV's own HDMI inputs (not devices behind them), in order, skipping hidden ones.
    static List<TvInputInfo> hdmiInputs(Context c, int max) {
        List<TvInputInfo> out = new ArrayList<>();
        TvInputManager tim = (TvInputManager) c.getSystemService(Context.TV_INPUT_SERVICE);
        if (tim == null) return out;
        for (TvInputInfo in : tim.getTvInputList()) {
            if (in.getType() == TvInputInfo.TYPE_HDMI && in.getParentId() == null && (android.os.Build.VERSION.SDK_INT < 24 || !in.isHidden(c))) out.add(in);
        }
        Collections.sort(out, (a, b) -> a.getId().compareTo(b.getId()));
        return out.size() > max ? out.subList(0, max) : out;
    }

    // The name set in Settings > Inputs (like "Bedroom"), else the TV's own name (like "HDMI 1").
    static String inputLabel(Context c, TvInputInfo in) {
        CharSequence custom = android.os.Build.VERSION.SDK_INT >= 24 ? in.loadCustomLabel(c) : null;
        if (custom != null && custom.length() > 0) return custom.toString();
        CharSequence label = in.loadLabel(c);
        return label != null && label.length() > 0 ? label.toString() : "HDMI";
    }
}
