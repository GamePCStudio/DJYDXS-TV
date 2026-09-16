package com.supermov.tv;

import org.json.JSONObject;

/** 云端 MAC 授权检查：GET /api/check/<mac>（对齐 check-mac-exists.md）。 */
public final class MacAuth {
    public static final String BASE = "https://mac.800915.xyz";
    public static final String TOKEN = "4f942f9387a7862f369c6afcb12a2578567998d4fdb0be60";

    public static class Result {
        public boolean networkOk;      // 网络请求成功
        public boolean authorized;     // 在库且有效（唯一放行依据）
        public String reason = "";     // ok/not_found/revoked/expired/invalid
        public String note = "";
    }

    private MacAuth() {}

    public static Result check(String mac) {
        Result r = new Result();
        try {
            java.util.Map<String, String> h = new java.util.HashMap<>();
            h.put("Authorization", "Bearer " + TOKEN);
            Http.Resp resp = Http.request("GET", BASE + "/api/check/" + mac, null, h, true);
            r.networkOk = resp.code == 200 && !resp.body.isEmpty();
            if (!r.networkOk) return r;
            JSONObject o = new JSONObject(resp.body);
            r.authorized = o.optBoolean("authorized", false);
            r.reason = o.optString("reason", "");
            r.note = o.optString("note", "");
            return r;
        } catch (Exception e) {
            r.networkOk = false;
            return r;
        }
    }
}
