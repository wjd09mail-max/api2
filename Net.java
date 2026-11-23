package com.stockalert.core;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/** HTTP 호출 도우미 (HttpURLConnection 사용, 안드로이드/PC 공통) */
public final class Net {
    private Net() {}

    public static final class Resp {
        public int status;
        public Map<String, Object> json;
        public String raw;
    }

    public static String form(Map<String, String> f) {
        StringBuilder b = new StringBuilder();
        try {
            for (Map.Entry<String, String> e : f.entrySet()) {
                if (b.length() > 0) b.append('&');
                b.append(URLEncoder.encode(e.getKey(), "UTF-8")).append('=')
                        .append(URLEncoder.encode(e.getValue() == null ? "" : e.getValue(), "UTF-8"));
            }
        } catch (IOException ignored) {
        }
        return b.toString();
    }

    public static String enc(String s) {
        try {
            return URLEncoder.encode(s, "UTF-8");
        } catch (IOException e) {
            return s;
        }
    }

    public static Resp postJson(String url, Map<String, String> headers, Object body) throws IOException {
        return request("POST", url, headers, Json.stringify(body), "application/json;charset=UTF-8");
    }

    public static Resp postForm(String url, Map<String, String> headers, Map<String, String> form) throws IOException {
        return request("POST", url, headers, form(form), "application/x-www-form-urlencoded;charset=utf-8");
    }

    public static Resp get(String url) throws IOException {
        return request("GET", url, null, null, null);
    }

    public static Resp request(String method, String url, Map<String, String> headers, String body, String ctype)
            throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(10000);
        c.setReadTimeout(15000);
        c.setRequestMethod(method);
        c.setInstanceFollowRedirects(false);
        if (headers != null) for (Map.Entry<String, String> e : headers.entrySet()) c.setRequestProperty(e.getKey(), e.getValue());
        if (body != null) {
            c.setDoOutput(true);
            if (ctype != null) c.setRequestProperty("Content-Type", ctype);
            byte[] data = body.getBytes(StandardCharsets.UTF_8);
            c.setFixedLengthStreamingMode(data.length);
            OutputStream os = c.getOutputStream();
            os.write(data);
            os.close();
        }
        Resp r = new Resp();
        r.status = c.getResponseCode();
        InputStream in = r.status >= 400 ? c.getErrorStream() : c.getInputStream();
        r.raw = in == null ? "" : readAll(in);
        c.disconnect();
        try {
            Object o = Json.parse(r.raw.isEmpty() ? "{}" : r.raw);
            r.json = Json.obj(o);
        } catch (RuntimeException e) {
            r.json = Json.map("_raw", r.raw.length() > 300 ? r.raw.substring(0, 300) : r.raw);
        }
        return r;
    }

    public static String readAll(InputStream in) throws IOException {
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
        in.close();
        return new String(bo.toByteArray(), StandardCharsets.UTF_8);
    }

    public static String publicIp() {
        String[] urls = {"https://api.ipify.org?format=json", "https://api64.ipify.org?format=json"};
        for (String u : urls) {
            try {
                Resp r = get(u);
                String ip = Json.s(r.json.get("ip"));
                if (r.status == 200 && !ip.isEmpty()) return ip;
            } catch (Exception ignored) {
            }
        }
        return "";
    }
}
