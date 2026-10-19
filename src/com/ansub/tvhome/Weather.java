package com.ansub.tvhome;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.Locale;

import org.json.JSONArray;
import org.json.JSONObject;

// Current weather from Open-Meteo: free, no account, no API key. With a city set at install time
// (--es city "...") it sends that name and then the city's coordinates. Without one it first asks
// GeoJS (free, no account) where the TV's internet address is, so the area is only as close as the
// internet provider's, and then sends those coordinates.
final class Weather {
    private Weather() {}

    private static final int MAX_BYTES = 64 * 1024; // real answers are under 2 KB

    // Blocking: call off the main thread. city null means "where the TV is". Returns text like
    // "28°C Cloudy", or throws.
    static String now(String city) throws Exception {
        JSONObject place = city == null ? get("https://get.geojs.io/v1/ip/geo.json") : find(city);
        JSONObject current = get("https://api.open-meteo.com/v1/forecast?current=temperature_2m,weather_code"
                + String.format(Locale.US, "&latitude=%.4f&longitude=%.4f", // never 1.0E-4
                        place.getDouble("latitude"), place.getDouble("longitude")))
                .getJSONObject("current");
        return (Math.round(current.getDouble("temperature_2m")) + "\u00B0C " + describe(current.getInt("weather_code"))).trim();
    }

    private static JSONObject find(String city) throws Exception {
        JSONArray found = get("https://geocoding-api.open-meteo.com/v1/search?count=1&name="
                + URLEncoder.encode(city, "UTF-8")).optJSONArray("results");
        if (found == null || found.length() == 0) throw new Exception("city not found: " + city);
        return found.getJSONObject(0);
    }

    // WMO weather codes, as Open-Meteo documents them, in a word or two.
    static String describe(int code) {
        if (code == 0) return "Clear";
        if (code == 1) return "Mostly clear";
        if (code == 2) return "Partly cloudy";
        if (code == 3) return "Cloudy";
        if (code == 45 || code == 48) return "Fog";
        if (code >= 51 && code <= 57) return "Drizzle";
        if ((code >= 61 && code <= 67) || (code >= 80 && code <= 82)) return "Rain";
        if ((code >= 71 && code <= 77) || code == 85 || code == 86) return "Snow";
        if (code >= 95) return "Thunderstorm";
        return "";
    }

    private static JSONObject get(String url) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(10000);
        c.setReadTimeout(10000);
        try (InputStream in = c.getInputStream()) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            for (int n; (n = in.read(buf)) > 0; ) {
                out.write(buf, 0, n);
                if (out.size() > MAX_BYTES) throw new Exception("response too big");
            }
            return new JSONObject(out.toString("UTF-8"));
        } finally {
            c.disconnect();
        }
    }
}
