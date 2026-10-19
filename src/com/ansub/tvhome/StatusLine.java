package com.ansub.tvhome;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.SystemClock;
import android.widget.TextView;

// Keeps the line under the clock up to date while the home screen is in front: memory and VPN
// every minute, weather in the background every 30 minutes (only if a city was set).
final class StatusLine {
    private static final String CITY = "city", TAILSCALE = "com.tailscale.ipn";
    private static final long TICK_MS = 60 * 1000, WEATHER_MS = 30 * 60 * 1000, RETRY_MS = 5 * 60 * 1000;
    private static final int MAX_CITY = 100;

    private final Activity activity;
    private final TextView view;
    private final SharedPreferences prefs;
    private String weather; // last good weather text, kept through failed refreshes for up to an hour
    private long weatherOkAt; // when weather last succeeded (elapsedRealtime)
    private long weatherAt; // when weather was last fetched (elapsedRealtime), 0 = fetch now
    private boolean hasTailscale; // checked once per start(), not every tick
    private final Runnable tick = new Runnable() {
        @Override public void run() {
            refresh();
            view.postDelayed(this, TICK_MS);
        }
    };

    StatusLine(Activity activity, TextView view, SharedPreferences prefs) {
        this.activity = activity;
        this.view = view;
        this.prefs = prefs;
    }

    void start() {
        hasTailscale = AppList.launchIntent(activity.getPackageManager(), TAILSCALE) != null;
        view.removeCallbacks(tick);
        tick.run();
    }

    void stop() {
        view.removeCallbacks(tick);
    }

    void setVisible(boolean visible) {
        view.setVisibility(visible ? android.view.View.VISIBLE : android.view.View.INVISIBLE);
    }

    // Saves the city passed with --es city "...". An empty city turns weather off.
    void saveCity(Intent intent) {
        String city = intent == null ? null : intent.getStringExtra(CITY);
        if (city == null) return;
        city = city.trim();
        if (city.length() > MAX_CITY) return; // any app can send this; a real city name is short
        if (city.equals(prefs.getString(CITY, ""))) return; // same city replayed: keep the weather
        if (city.isEmpty()) prefs.edit().remove(CITY).apply();
        else prefs.edit().putString(CITY, city).apply();
        weather = null;
        weatherAt = 0;
    }

    private void refresh() {
        show();
        final String city = prefs.getString(CITY, null);
        long now = SystemClock.elapsedRealtime();
        if (city == null || (weatherAt != 0 && now - weatherAt < WEATHER_MS)) return;
        weatherAt = now;
        new Thread(() -> {
            String got = null;
            try { got = Weather.now(city); } catch (Exception ignored) {}
            final String w = got;
            activity.runOnUiThread(() -> {
                if (!city.equals(prefs.getString(CITY, null))) return; // city changed while fetching
                if (w != null) {
                    weather = w;
                    weatherOkAt = SystemClock.elapsedRealtime();
                } else {
                    weatherAt = SystemClock.elapsedRealtime() - WEATHER_MS + RETRY_MS;
                }
                if (!activity.isDestroyed()) show();
            });
        }).start();
    }

    private void show() {
        String vpn = !hasTailscale ? null : SystemStatus.vpnUp() ? "Tailscale on" : "Tailscale off";
        boolean fresh = SystemClock.elapsedRealtime() - weatherOkAt < 2 * WEATHER_MS;
        view.setText(SystemStatus.line(fresh ? weather : null, SystemStatus.freeMemoryMb(), vpn));
    }
}
