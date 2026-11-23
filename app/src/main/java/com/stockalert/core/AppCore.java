package com.stockalert.core;

import com.stockalert.core.Store.Logx;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** 앱 본체: 설정 + 카카오 + 엔진 + 내장 웹서버(127.0.0.1) */
public final class AppCore {
    public static final String VERSION = "3.0.0";
    public final Store cfg;
    public final Kakao kakao;
    public final Engine engine;
    public final Trader trader;
    public final Kakao.Notifier notifier;
    public final String token;          // 내장 웹서버 접근 토큰 (다른 앱의 접근 차단)
    private final String page;
    private ServerSocket server;
    private volatile boolean serving = false;

    public AppCore(File configFile, String pageHtml, Kakao.Notifier notifier) {
        this.cfg = new Store(configFile);
        this.notifier = notifier;
        this.kakao = new Kakao(cfg, notifier);
        this.engine = new Engine(cfg, kakao, new File(configFile.getAbsoluteFile().getParentFile(), "watch.json"));
        this.trader = new Trader(cfg, kakao, engine, new File(configFile.getAbsoluteFile().getParentFile(), "trade.json"));
        engine.setTrader(trader);
        this.page = pageHtml;
        byte[] b = new byte[18];
        new SecureRandom().nextBytes(b);
        this.token = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(b);
    }

    public boolean isServing() {
        return serving;
    }

    public int port() {
        return (int) cfg.num("ui", "port");
    }

    public String appUrl() {
        return "http://127.0.0.1:" + port() + "/?t=" + token;
    }

    /** 서버 시작 + (설정 시) 자동 감시 시작 + 상태 알림 루프 */
    public void startAll() throws IOException {
        startServer();
        Logx.add("앱 시작 v" + VERSION);
        if (cfg.bool("alerts", "autostart") && !cfg.str("kiwoom", "appkey").isEmpty()) {
            Object[] r = engine.start();
            Logx.add("자동 시작: " + r[1]);
        }
        Thread t = new Thread(() -> {
            String last = null;
            while (serving) {
                try {
                    String s = engine.summary();
                    if (!s.equals(last) && notifier != null) {
                        notifier.status(s);
                        last = s;
                    }
                    Thread.sleep(5000);
                } catch (InterruptedException e) {
                    return;
                } catch (Exception ignored) {
                }
            }
        }, "status");
        t.setDaemon(true);
        t.start();
    }

    public void stopAll() {
        engine.stop();
        serving = false;
        try {
            if (server != null) server.close();
        } catch (IOException ignored) {
        }
    }

    public synchronized void startServer() throws IOException {
        if (serving) return;
        IOException lastErr = null;
        for (int i = 0; i < 20; i++) {          // 직전 종료 직후 재시작 시 포트가 잠시 사용 중일 수 있음
            try {
                ServerSocket ss = new ServerSocket();
                ss.setReuseAddress(true);
                ss.bind(new java.net.InetSocketAddress(InetAddress.getByName("127.0.0.1"), port()), 50);
                server = ss;
                lastErr = null;
                break;
            } catch (IOException e) {
                lastErr = e;
                try {
                    Thread.sleep(500);
                } catch (InterruptedException ie) {
                    break;
                }
            }
        }
        if (lastErr != null) throw new IOException("포트 " + port() + " 를 열 수 없습니다: " + lastErr.getMessage());
        serving = true;
        Thread t = new Thread(() -> {
            while (serving) {
                try {
                    final Socket s = server.accept();
                    Thread h = new Thread(() -> handle(s), "http");
                    h.setDaemon(true);
                    h.start();
                } catch (IOException e) {
                    if (serving) Logx.add("웹서버 오류: " + e.getMessage(), "error");
                }
            }
        }, "httpd");
        t.setDaemon(true);
        t.start();
    }

    // ───────────── HTTP 처리 ─────────────
    private static String readLine(InputStream in) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        int c;
        while ((c = in.read()) >= 0) {
            if (c == '\n') break;
            if (c != '\r') b.write(c);
            if (b.size() > 16384) throw new IOException("헤더가 너무 김");
        }
        if (c < 0 && b.size() == 0) return null;
        return new String(b.toByteArray(), StandardCharsets.UTF_8);
    }

    private static Map<String, String> query(String q) {
        Map<String, String> m = new LinkedHashMap<>();
        if (q == null || q.isEmpty()) return m;
        for (String kv : q.split("&")) {
            int e = kv.indexOf('=');
            try {
                String k = URLDecoder.decode(e >= 0 ? kv.substring(0, e) : kv, "UTF-8");
                String v = e >= 0 ? URLDecoder.decode(kv.substring(e + 1), "UTF-8") : "";
                m.put(k, v);
            } catch (Exception ignored) {
            }
        }
        return m;
    }

    private void handle(Socket s) {
        try {
            s.setSoTimeout(15000);
            InputStream in = new BufferedInputStream(s.getInputStream());
            OutputStream out = s.getOutputStream();
            String line = readLine(in);
            if (line == null) {
                s.close();
                return;
            }
            String[] parts = line.split(" ");
            if (parts.length < 2) {
                s.close();
                return;
            }
            String method = parts[0], target = parts[1];
            Map<String, String> headers = new LinkedHashMap<>();
            String h;
            while ((h = readLine(in)) != null && !h.isEmpty()) {
                int c = h.indexOf(':');
                if (c > 0) headers.put(h.substring(0, c).trim().toLowerCase(Locale.ROOT), h.substring(c + 1).trim());
            }
            int clen = 0;
            try {
                clen = Integer.parseInt(headers.containsKey("content-length") ? headers.get("content-length") : "0");
            } catch (NumberFormatException ignored) {
            }
            byte[] body = new byte[Math.max(0, Math.min(clen, 1_000_000))];
            int got = 0;
            while (got < body.length) {
                int n = in.read(body, got, body.length - got);
                if (n < 0) break;
                got += n;
            }
            String path = target, qs = "";
            int qi = target.indexOf('?');
            if (qi >= 0) {
                path = target.substring(0, qi);
                qs = target.substring(qi + 1);
            }
            Object[] resp = route(method, path, query(qs), headers, new String(body, 0, got, StandardCharsets.UTF_8));
            int code = (Integer) resp[0];
            String ctype = (String) resp[1];
            byte[] data = resp[2] instanceof byte[] ? (byte[]) resp[2] : String.valueOf(resp[2]).getBytes(StandardCharsets.UTF_8);
            StringBuilder hb = new StringBuilder();
            hb.append("HTTP/1.1 ").append(code).append(code == 200 ? " OK" : code == 302 ? " Found" : " Error").append("\r\n");
            hb.append("Content-Type: ").append(ctype).append("\r\n");
            hb.append("Content-Length: ").append(data.length).append("\r\n");
            hb.append("Cache-Control: no-store\r\nConnection: close\r\n");
            if (resp.length > 3 && resp[3] != null) hb.append("Location: ").append(resp[3]).append("\r\n");
            hb.append("\r\n");
            out.write(hb.toString().getBytes(StandardCharsets.UTF_8));
            out.write(data);
            out.flush();
        } catch (Exception e) {
            // 연결 끊김 등은 무시
        } finally {
            try {
                s.close();
            } catch (IOException ignored) {
            }
        }
    }

    private static final String JSON = "application/json; charset=utf-8";
    private static final String HTML = "text/html; charset=utf-8";

    private boolean authed(String given) {
        return given != null && given.equals(token);
    }

    private Object[] route(String method, String path, Map<String, String> q, Map<String, String> headers, String body) {
        if ("GET".equals(method)) {
            if ("/".equals(path) || "/index.html".equals(path)) return new Object[]{200, HTML, page};
            if ("/api/state".equals(path)) {
                if (!authed(headers.get("x-pin"))) return new Object[]{401, JSON, "{\"error\":\"PIN\"}"};
                return new Object[]{200, JSON, Json.stringify(state())};
            }
            if ("/kakao/login".equals(path)) {
                if (!authed(q.get("pin"))) return new Object[]{401, HTML, simplePage("앱에서 다시 '카카오 연결'을 눌러 주세요.")};
                if (cfg.str("kakao", "rest_key").isEmpty())
                    return new Object[]{400, HTML, simplePage("먼저 앱의 '설정'에서 카카오 REST API 키를 저장하세요.")};
                return new Object[]{302, HTML, "", kakao.authUrl(q.get("name"))};
            }
            if ("/kakao/callback".equals(path)) {
                if (q.containsKey("error")) {
                    String d = q.containsKey("error_description") ? q.get("error_description") : q.get("error");
                    return new Object[]{200, HTML, simplePage("카카오 동의 취소/오류: " + esc(d))};
                }
                try {
                    Map<String, Object> rc = kakao.exchange(q.get("code"), q.get("state"));
                    String nm = Json.s(rc.get("name"));
                    List<Object[]> res = kakao.sendNow("✅ 주식 알림 앱 연결 완료\n'" + nm + "' 님에게 알림이 전송됩니다.");
                    String extra = "";
                    for (Object[] r : res) if (nm.equals(r[0]) && !(Boolean) r[1]) extra = "<br><br>⚠ 테스트 전송 실패: " + esc(String.valueOf(r[2]));
                    return new Object[]{200, HTML, simplePage("✅ '" + esc(nm) + "' 카카오톡 연결 완료!<br>이제 <b>조건검색 알림 앱</b>으로 돌아가세요." + extra)};
                } catch (Exception e) {
                    return new Object[]{200, HTML, simplePage("❌ 연결 실패: " + esc(e.getMessage()))};
                }
            }
            return new Object[]{404, JSON, "{\"error\":\"not found\"}"};
        }
        if ("POST".equals(method)) {
            if (!authed(headers.get("x-pin"))) return new Object[]{401, JSON, "{\"error\":\"PIN\"}"};
            Map<String, Object> b;
            try {
                b = Json.obj(Json.parse(body.isEmpty() ? "{}" : body));
            } catch (RuntimeException e) {
                b = new LinkedHashMap<>();
            }
            Object[] r;
            try {
                r = post(path, b);
            } catch (Exception e) {
                Logx.add("요청 처리 오류: " + e, "error");
                r = new Object[]{false, String.valueOf(e.getMessage())};
            }
            return new Object[]{200, JSON, Json.stringify(Json.map("ok", r[0], "msg", r[1]))};
        }
        return new Object[]{405, JSON, "{}"};
    }

    private static String keep(Object nw, String old) {
        String n = nw == null ? "" : Json.s(nw).trim();
        return (n.isEmpty() || n.contains("•")) ? old : n;
    }

    private Object[] post(String path, Map<String, Object> b) throws Exception {
        switch (path) {
            case "/api/start": return engine.start();
            case "/api/stop": return engine.stop();
            case "/api/conditions": return engine.refreshConditions();
            case "/api/settings": {
                boolean changed;
                synchronized (cfg.lock) {
                    Map<String, Object> k = cfg.sec("kiwoom"), kk = cfg.sec("kakao");
                    String old = Json.s(k.get("appkey")) + "|" + Json.s(k.get("secretkey")) + "|" + Json.b(k.get("mock"));
                    k.put("appkey", keep(b.get("appkey"), Json.s(k.get("appkey"))));
                    k.put("secretkey", keep(b.get("secretkey"), Json.s(k.get("secretkey"))));
                    if (b.containsKey("mock")) k.put("mock", Json.b(b.get("mock")));
                    kk.put("rest_key", keep(b.get("kakao_rest_key"), Json.s(kk.get("rest_key"))));
                    kk.put("client_secret", keep(b.get("kakao_client_secret"), Json.s(kk.get("client_secret"))));
                    cfg.save();
                    changed = !old.equals(Json.s(k.get("appkey")) + "|" + Json.s(k.get("secretkey")) + "|" + Json.b(k.get("mock")));
                }
                String msg = "저장됨";
                if (changed && engine.running) {
                    engine.stop();
                    Object[] r = engine.start();
                    msg += " – 새 키움 설정으로 다시 연결합니다";
                }
                return new Object[]{true, msg};
            }
            case "/api/slots": {
                List<Object> slots = Json.arr(b.get("slots"));
                List<Object> clean = new ArrayList<>();
                for (int i = 0; i < Store.SLOT_COUNT; i++) {
                    Map<String, Object> s = i < slots.size() ? Json.obj(slots.get(i)) : new LinkedHashMap<String, Object>();
                    clean.add(Json.map("enabled", Json.b(s.get("enabled")), "seq", Json.s(s.get("seq")).trim(),
                            "name", Json.s(s.get("name")).trim(), "buy", Json.b(s.get("buy"))));
                }
                synchronized (cfg.lock) {
                    cfg.data.put("slots", clean);
                    cfg.save();
                }
                Object[] r = engine.applySlots();
                return new Object[]{true, "조건식 저장됨. " + r[1]};
            }
            case "/api/trade/settings": {
                String[] nums = {"buy_amount", "take_profit", "stop_loss", "trail_pct", "max_buys", "max_positions", "max_loss", "index_stop", "index_sell"};
                String[] bools = {"enabled", "real_ok", "bot_only"};
                String[] times = {"buy_start", "buy_end", "close_time"};
                synchronized (cfg.lock) {
                    Map<String, Object> tr = cfg.sec("trade");
                    for (String k : nums) {
                        if (!b.containsKey(k)) continue;
                        double v = Json.d(Json.s(b.get(k)).replace(",", ""), -1);
                        if (v < 0) return new Object[]{false, "'" + k + "' 값이 올바르지 않습니다"};
                        tr.put(k, v);
                    }
                    for (String k : bools) if (b.containsKey(k)) tr.put(k, Json.b(b.get(k)));
                    for (String k : times) {
                        if (!b.containsKey(k)) continue;
                        String v = Json.s(b.get(k)).trim();
                        if (!v.isEmpty() && !v.matches("([01][0-9]|2[0-3]):[0-5][0-9]")) return new Object[]{false, "시각은 09:05 형식으로 입력하세요"};
                        tr.put(k, v);
                    }
                    if (b.containsKey("order_type")) tr.put("order_type", "limit".equals(Json.s(b.get("order_type"))) ? "limit" : "market");
                    cfg.save();
                }
                trader.reload();
                boolean on = cfg.bool("trade", "enabled");
                if (b.containsKey("enabled") && b.size() == 1) {
                    Store.Logx.add("자동매매 " + (on ? "켜짐" : "꺼짐"));
                    kakao.push(on ? "🟢 자동매매 켜짐 " + (cfg.bool("kiwoom", "mock") ? "(모의투자)" : "(실전)") : "⚪ 자동매매 꺼짐");
                    return new Object[]{true, "자동매매 " + (on ? "ON" : "OFF")};
                }
                return new Object[]{true, "매매 설정 저장됨"};
            }
            case "/api/trade/sell":
                return trader.sell(Engine.cleanCode(b.get("code")), "수동 매도", true);
            case "/api/trade/sellall":
                return trader.sellAll("수동 전체 매도", true);
            case "/api/trade/resume":
                return trader.resume();
            case "/api/trade/refresh":
                trader.refreshNow();
                return new Object[]{true, "잔고 새로고침 요청"};
            case "/api/search":
                return engine.searchOnce(Json.s(b.get("seq")));
            case "/api/watch/remove":
                return engine.removeWatch(Json.s(b.get("code")), Json.s(b.get("group")));
            case "/api/watch/clear":
                return engine.clearGroup(Json.s(b.get("group")));
            case "/api/watch/add": {
                String code = Engine.cleanCode(b.get("code"));
                if (code.isEmpty()) return new Object[]{false, "종목코드가 없습니다"};
                synchronized (cfg.lock) {
                    String wl = Json.s(cfg.data.get("watchlist")).trim();
                    if (!("," + wl.replace(" ", "") + ",").contains("," + code + ","))
                        cfg.data.put("watchlist", wl.isEmpty() ? code : wl + ", " + code);
                    cfg.save();
                }
                engine.applyWatchlist();
                return new Object[]{true, engine.nameOf(code) + " 관심종목에 추가"};
            }
            case "/api/stock_alert":
            case "/api/index_alert": {
                boolean isIx = path.equals("/api/index_alert");
                String code = isIx ? Json.s(b.get("code")).trim() : Engine.cleanCode(b.get("code"));
                if (code.isEmpty()) return new Object[]{false, "코드가 없습니다"};
                String secName = isIx ? "index_alerts" : "stock_alerts";
                synchronized (cfg.lock) {
                    Map<String, Object> sec = cfg.sec(secName);
                    if (Json.b(b.get("reset")) && !isIx) {
                        sec.remove(code);
                    } else {
                        Map<String, Object> in = Json.obj(b.get("settings"));
                        Map<String, Object> o = new LinkedHashMap<>();
                        String[] keys = isIx ? new String[]{"step", "surge_pct", "surge_min", "price_up", "price_dn"}
                                : new String[]{"day_step", "entry_step", "surge_pct", "surge_min", "price_up", "price_dn"};
                        for (String k : keys) {
                            Object v = in.get(k);
                            String t = v == null ? "" : Json.s(v).replace(",", "").trim();
                            if (t.isEmpty()) {
                                if (isIx) o.put(k, 0.0);
                                continue;
                            }
                            double d = Json.d(t, -1);
                            if (d < 0) return new Object[]{false, "'" + k + "' 값이 올바르지 않습니다"};
                            o.put(k, d);
                        }
                        if (isIx) o.put("enabled", !in.containsKey("enabled") || Json.b(in.get("enabled")));
                        else o.put("mute", Json.b(in.get("mute")));
                        sec.put(code, o);
                    }
                    cfg.save();
                }
                engine.resetStock(code);
                return new Object[]{true, engine.nameOf(code) + " 알림 설정 저장됨"};
            }
            case "/api/watchlist": {
                synchronized (cfg.lock) {
                    cfg.data.put("watchlist", Json.s(b.get("text")));
                    cfg.save();
                }
                if (engine.connected) return engine.applyWatchlist();
                return new Object[]{true, "저장됨 (감시 시작 시 적용)"};
            }
            case "/api/alerts": {
                String[] bools = {"on_enter", "on_exit", "keep_after_exit", "quiet", "phone_notify", "kakao_notify", "autostart"};
                String[] nums = {"day_step", "entry_step", "surge_pct", "surge_min", "index_step", "cooldown_min", "max_watch", "scan_min"};
                synchronized (cfg.lock) {
                    Map<String, Object> a = cfg.sec("alerts");
                    for (String k : bools) if (b.containsKey(k)) a.put(k, Json.b(b.get(k)));
                    for (String k : nums) {
                        if (!b.containsKey(k)) continue;
                        double v = Json.d(b.get(k), -1);
                        if (v < 0) return new Object[]{false, "'" + k + "' 값이 올바르지 않습니다"};
                        a.put(k, v);
                    }
                    a.put("max_watch", (double) Math.max(1, Math.min(200, (int) Json.d(a.get("max_watch"), 100))));
                    cfg.save();
                }
                engine.resetLevels();
                if (b.containsKey("scan_min")) {
                    int sm = engine.scanMin();
                    String mode = sm > 0 ? sm + "분 주기 검색" : "실시간";
                    if (engine.connected) engine.applySlots();
                    return new Object[]{true, "조건검색 방식: " + mode};
                }
                return new Object[]{true, "알림 설정 저장됨"};
            }
            case "/api/myip": {
                engine.publicIp = Net.publicIp();
                return engine.publicIp.isEmpty()
                        ? new Object[]{false, "IP 를 확인할 수 없습니다 (인터넷 연결 확인)"}
                        : new Object[]{true, "현재 공인 IP: " + engine.publicIp + " → 키움 포털 '계좌 App Key 관리'에 등록하세요"};
            }
            case "/api/phone/test": {
                if (notifier == null) return new Object[]{false, "폰 알림을 사용할 수 없습니다"};
                notifier.alert("🔔 폰 알림 테스트\n알림이 보이면 정상입니다");
                return new Object[]{true, "폰 알림을 보냈습니다"};
            }
            case "/api/kakao/test": {
                List<Object[]> res = kakao.sendNow("✅ 테스트 알림입니다 (조건검색 알림 앱)");
                boolean all = true;
                StringBuilder sb = new StringBuilder();
                for (Object[] r : res) {
                    all &= (Boolean) r[1];
                    if (sb.length() > 0) sb.append(" / ");
                    sb.append(r[0]).append(": ").append(r[2]);
                }
                return new Object[]{all, sb.toString()};
            }
            case "/api/kakao/remove": {
                String name = Json.s(b.get("name"));
                synchronized (cfg.lock) {
                    List<Object> keepL = new ArrayList<>();
                    for (Object o : Json.arr(cfg.sec("kakao").get("recipients")))
                        if (!Json.s(Json.obj(o).get("name")).equals(name)) keepL.add(o);
                    cfg.sec("kakao").put("recipients", keepL);
                    cfg.save();
                }
                return new Object[]{true, "'" + name + "' 삭제"};
            }
            default:
                return new Object[]{false, "알 수 없는 요청"};
        }
    }

    private static String mask(String s) {
        if (s == null || s.isEmpty()) return "";
        return s.length() > 6 ? s.substring(0, 4) + "••••••••" : "••••••";
    }

    public Map<String, Object> state() {
        Map<String, Object> s = engine.snapshot();
        Map<String, Object> k = cfg.copy("kiwoom"), kk = cfg.copy("kakao");
        List<Object> recips = new ArrayList<>();
        SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd", Locale.KOREA);
        for (Object o : Json.arr(kk.get("recipients"))) {
            Map<String, Object> r = Json.obj(o);
            String scope = Json.s(r.get("scope"));
            recips.add(Json.map("name", Json.s(r.get("name")),
                    "ok_until", f.format(new Date((long) (Json.d(r.get("refresh_expires_at"), 0) * 1000))),
                    "scope_ok", scope.isEmpty() || scope.contains("talk_message")));
        }
        synchronized (cfg.lock) {
            s.put("slots", Json.copy(cfg.data.get("slots")));
            s.put("watchlist", Json.s(cfg.data.get("watchlist")));
        }
        s.put("version", VERSION);
        s.put("alerts", cfg.copy("alerts"));
        s.put("trade", trader.snapshot());
        s.put("trade_cfg", cfg.copy("trade"));
        s.put("stock_alerts", cfg.copy("stock_alerts"));
        s.put("index_alerts", cfg.copy("index_alerts"));
        s.put("logs", Logx.lines(150));
        s.put("alert_log", Logx.alerts(100));
        s.put("settings", Json.map("appkey", mask(Json.s(k.get("appkey"))), "secretkey", mask(Json.s(k.get("secretkey"))),
                "mock", Json.b(k.get("mock")), "kakao_rest_key", mask(Json.s(kk.get("rest_key"))),
                "kakao_client_secret", mask(Json.s(kk.get("client_secret"))), "port", port()));
        s.put("recipients", recips);
        s.put("redirect_uri", kakao.redirectUri());
        s.put("phone_api", notifier != null);
        s.put("public_ip", engine.publicIp);
        return s;
    }

    static String esc(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }

    static String simplePage(String msg) {
        return "<!doctype html><meta charset=utf-8><meta name=viewport content='width=device-width,initial-scale=1'>"
                + "<body style='font-family:sans-serif;padding:32px;line-height:1.6;background:#0f1420;color:#e8ecf4'>"
                + "<p style='font-size:18px'>" + msg + "</p></body>";
    }
}
