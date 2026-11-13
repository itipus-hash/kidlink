package com.kidlink.beacon.push;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import org.json.JSONObject;

import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * PushPlus 微信推送。
 * 接口：POST https://www.pushplus.plus/api/send
 * 负载：{"token": "...", "title": "...", "content": "...", "template": "txt"}
 * 返回：{"code": 200, "msg": "..."} —— code == 200 视为成功。
 */
public class PushClient {

    private static final String TAG = "PushClient";
    private static final String ENDPOINT = "https://www.pushplus.plus/api/send";

    public interface Callback {
        void onDone(boolean ok, String message);
    }

    private final Handler main = new Handler(Looper.getMainLooper());

    /**
     * 一次推给多条通道。空项自动忽略，全部成功才算成功。
     */
    public void sendAll(final java.util.List<String> tokenList, final String title,
                        final String content, final Callback cb) {
        java.util.List<String> tokens = new java.util.ArrayList<>();
        if (tokenList != null) {
            for (String t : tokenList) {
                String s = t == null ? "" : t.trim();
                if (!s.isEmpty()) tokens.add(s);
            }
        }
        if (tokens.isEmpty()) {
            if (cb != null) cb.onDone(false, "没有可用的 token");
            return;
        }

        final java.util.concurrent.atomic.AtomicInteger remaining =
                new java.util.concurrent.atomic.AtomicInteger(tokens.size());
        final java.util.concurrent.atomic.AtomicInteger failed =
                new java.util.concurrent.atomic.AtomicInteger(0);
        final StringBuilder lastMsg = new StringBuilder();

        for (String t : tokens) {
            send(t, title, content, (ok, message) -> {
                if (!ok) {
                    failed.incrementAndGet();
                    lastMsg.append(message).append(" ");
                }
                if (remaining.decrementAndGet() == 0) {
                    boolean allOk = failed.get() == 0;
                    if (cb != null) cb.onDone(allOk,
                            allOk ? "ok" : (lastMsg.length() > 0 ? lastMsg.toString().trim() : "失败"));
                }
            });
        }
    }

    public void send(final String token, final String title, final String content, final Callback cb) {
        if (token == null || token.trim().isEmpty()) {
            if (cb != null) cb.onDone(false, "没有填写 token");
            return;
        }

        new Thread(() -> {
            boolean ok = false;
            String msg = "";
            HttpURLConnection conn = null;
            try {
                JSONObject payload = new JSONObject();
                payload.put("token", token.trim());
                payload.put("title", title);
                payload.put("content", content);
                payload.put("template", "txt");

                byte[] body = payload.toString().getBytes(StandardCharsets.UTF_8);

                conn = (HttpURLConnection) new URL(ENDPOINT).openConnection();
                conn.setRequestMethod("POST");
                conn.setConnectTimeout(10000);
                conn.setReadTimeout(10000);
                conn.setDoOutput(true);
                conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
                conn.setRequestProperty("Content-Length", String.valueOf(body.length));

                try (OutputStream os = conn.getOutputStream()) {
                    os.write(body);
                }

                int httpCode = conn.getResponseCode();
                String text = readAll(conn);

                if (httpCode == 200) {
                    try {
                        JSONObject resp = new JSONObject(text);
                        int code = resp.optInt("code", -1);
                        ok = code == 200;
                        msg = resp.optString("msg", text);
                    } catch (Exception e) {
                        msg = text;
                    }
                } else {
                    msg = "HTTP " + httpCode;
                }
                Log.d(TAG, "push result ok=" + ok + " msg=" + msg);
            } catch (Exception e) {
                Log.e(TAG, "push failed", e);
                msg = e.getClass().getSimpleName();
            } finally {
                if (conn != null) conn.disconnect();
            }

            final boolean fOk = ok;
            final String fMsg = msg;
            main.post(() -> {
                if (cb != null) cb.onDone(fOk, fMsg);
            });
        }).start();
    }

    private String readAll(HttpURLConnection conn) {
        try (java.io.InputStream in = conn.getErrorStream() != null
                ? conn.getErrorStream() : conn.getInputStream()) {
            if (in == null) return "";
            java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[1024];
            int n;
            while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
            return new String(bos.toByteArray(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            return "";
        }
    }
}
