package com.ansub.tvhome;

import java.io.BufferedReader;
import java.io.FileReader;
import java.net.NetworkInterface;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

// The small line under the clock: weather, free memory and whether the VPN is up.
final class SystemStatus {
    private SystemStatus() {}

    // Free memory in MB, or -1 if it can't be read. Some MediaTek kernels report a MemAvailable
    // bigger than the TV's whole memory, so then it falls back to free + buffers + page cache.
    static long freeMemoryMb() {
        long total = 0, avail = 0, free = 0, buffers = 0, cached = 0, shmem = 0;
        try (BufferedReader r = new BufferedReader(new FileReader("/proc/meminfo"))) {
            for (String line; (line = r.readLine()) != null; ) {
                String[] f = line.split("\\s+");
                if (f.length < 2 || !f[1].matches("\\d+")) continue;
                long kb = Long.parseLong(f[1]);
                switch (f[0]) {
                    case "MemTotal:": total = kb; break;
                    case "MemAvailable:": avail = kb; break;
                    case "MemFree:": free = kb; break;
                    case "Buffers:": buffers = kb; break;
                    case "Cached:": cached = kb; break;
                    case "Shmem:": shmem = kb; break;
                }
            }
        } catch (Exception e) {
            return -1;
        }
        if (total <= 0) return -1;
        long kb = avail > 0 && avail <= total ? avail : free + buffers + cached - shmem;
        return Math.max(0, Math.min(kb, total)) / 1024;
    }

    // True when a VPN tunnel (Tailscale uses tun0) is up. Needs no permission.
    static boolean vpnUp() {
        try {
            for (NetworkInterface n : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                String name = n.getName();
                if (n.isUp() && (name.startsWith("tun") || name.startsWith("tailscale"))) return true;
            }
        } catch (Exception ignored) {}
        return false;
    }

    // Joins the parts that are known, like "28°C Cloudy · 412 MB memory free · Tailscale on".
    // weather and vpn are null, and freeMb -1, when that part isn't shown.
    static String line(String weather, long freeMb, String vpn) {
        List<String> parts = new ArrayList<>();
        if (weather != null) parts.add(weather);
        if (freeMb >= 0) parts.add(freeMb + " MB memory free");
        if (vpn != null) parts.add(vpn);
        return android.text.TextUtils.join("  ·  ", parts);
    }
}
