package com.djydxs.tv;

import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.Collections;
import java.util.List;

/** 取本机有线/无线 MAC（TV 盒子优先 eth0，其次 wlan0，最后任意非 lo 接口）。 */
public final class MacUtil {
    private MacUtil() {}

    /** 返回格式 AA:BB:CC:DD:EE:FF；拿不到返回空串。 */
    public static String getMac() {
        try {
            List<NetworkInterface> all = Collections.list(NetworkInterface.getNetworkInterfaces());
            // 优先级：eth* > wlan* > 其它
            String eth = "", wlan = "", any = "";
            for (NetworkInterface nif : all) {
                try {
                    if (nif.isLoopback()) continue;
                    byte[] mac = nif.getHardwareAddress();
                    if (mac == null || mac.length != 6) continue;
                    String s = format(mac);
                    if (s.isEmpty() || "00:00:00:00:00:00".equals(s)) continue;
                    String name = nif.getName() == null ? "" : nif.getName().toLowerCase();
                    if (name.startsWith("eth") && eth.isEmpty()) eth = s;
                    else if (name.startsWith("wlan") && wlan.isEmpty()) wlan = s;
                    else if (any.isEmpty()) any = s;
                } catch (Exception ignored) {
                }
            }
            if (!eth.isEmpty()) return eth;
            if (!wlan.isEmpty()) return wlan;
            return any;
        } catch (Exception e) {
            return "";
        }
    }

    private static String format(byte[] mac) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < mac.length; i++) {
            if (i > 0) sb.append(':');
            sb.append(String.format("%02X", mac[i]));
        }
        return sb.toString();
    }
}
