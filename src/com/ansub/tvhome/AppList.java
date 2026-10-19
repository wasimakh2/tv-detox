package com.ansub.tvhome;

import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.TreeMap;

// Which apps the home screen shows and offers, and how to open them.
final class AppList {
    private AppList() {}

    static final int PER_ROW = 6;
    private static final int MAX_SAVED = 100;
    static final List<String> NOT_APPS = Arrays.asList("com.android.vending", "com.android.tv.settings",
            "com.android.tv", "com.google.android.tvlauncher", "com.google.android.apps.tv.launcherx",
            "com.google.android.leanbacklauncher");
    static final String PLAY_STORE = "com.android.vending";

    // Top-row apps: the saved list (or apps.txt), topped up with installed TV apps when nothing was saved.
    static List<String> forHome(Context c, String saved) {
        PackageManager pm = c.getPackageManager();
        List<String> out = new ArrayList<>();
        String[] wanted = saved != null ? saved.split("[,\\s]+") : Apps.LIST;
        // Any app can send a list, so look at no more than MAX_SAVED names.
        for (int i = 0; i < Math.min(wanted.length, MAX_SAVED); i++) {
            String p = wanted[i];
            if (!p.isEmpty() && !out.contains(p) && launchIntent(pm, p) != null) out.add(p);
        }
        if (saved == null) {
            // Top up with other TV apps people installed (or updated), skipping launchers and system tools.
            Intent q = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER);
            for (ResolveInfo ri : pm.queryIntentActivities(q, 0)) {
                if (out.size() >= PER_ROW) break;
                String p = ri.activityInfo.packageName;
                int flags = ri.activityInfo.applicationInfo.flags;
                boolean stockSystem = (flags & ApplicationInfo.FLAG_SYSTEM) != 0
                        && (flags & ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) == 0;
                if (stockSystem || out.contains(p) || NOT_APPS.contains(p) || p.equals(c.getPackageName())) continue;
                out.add(p);
            }
        }
        return out;
    }

    // Every launchable app that isn't shown yet, by name.
    static TreeMap<String, String> addable(Context c, List<String> shown) {
        PackageManager pm = c.getPackageManager();
        TreeMap<String, String> byLabel = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        for (String category : new String[] {Intent.CATEGORY_LEANBACK_LAUNCHER, Intent.CATEGORY_LAUNCHER}) {
            Intent q = new Intent(Intent.ACTION_MAIN).addCategory(category);
            for (ResolveInfo ri : pm.queryIntentActivities(q, 0)) {
                String p = ri.activityInfo.packageName;
                if (shown.contains(p) || byLabel.containsValue(p) || p.equals(c.getPackageName())
                        || (NOT_APPS.contains(p) && !p.equals(PLAY_STORE))) continue;
                String label = labelOf(c, p);
                if (!byLabel.containsKey(label)) byLabel.put(label, p);
            }
        }
        return byLabel;
    }

    static String labelOf(Context c, String pkg) {
        PackageManager pm = c.getPackageManager();
        try { return pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString(); }
        catch (Exception e) { return pkg; }
    }

    static Intent launchIntent(PackageManager pm, String pkg) {
        Intent i = pm.getLeanbackLaunchIntentForPackage(pkg);
        return i != null ? i : pm.getLaunchIntentForPackage(pkg);
    }
}
