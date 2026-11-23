package com.stockalert.core;

import com.stockalert.core.Store.Logx;

import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;

/** 키움 REST/WebSocket 감시 엔진 */
public final class Engine {
    static String REST_REAL = "https://api.kiwoom.com", REST_MOCK = "https://mockapi.kiwoom.com";
    static String WS_REAL = "wss://api.kiwoom.com:10000/api/dostk/websocket";
    static String WS_MOCK = "wss://mockapi.kiwoom.com:10000/api/dostk/websocket";
    static String REST_OVERRIDE = null, WS_OVERRIDE = null;
    static final String[][] INDEXES = {{"001", "코스피"}, {"101", "코스닥"}};

    public static void setOverrides(String rest, String ws) {
        REST_OVERRIDE = rest;
        WS_OVERRIDE = ws;
    }

    /** 인증 오류 (키/IP) */
    static final class AuthError extends Exception {
        AuthError(String m) { super(m); }
    }

    // ───────── 키움 REST ─────────
    static final class Rest {
        final String appkey, secretkey, base, wsUrl;
        String token;
        long expires;
        long last;
        final Object tlock = new Object(), rlock = new Object();

        Rest(String appkey, String secretkey, boolean mock) {
            this.appkey = appkey;
            this.secretkey = secretkey;
            base = REST_OVERRIDE != null ? REST_OVERRIDE : (mock ? REST_MOCK : REST_REAL);
            wsUrl = WS_OVERRIDE != null ? WS_OVERRIDE : (mock ? WS_MOCK : WS_REAL);
        }

        String getToken(boolean force) throws Exception {
            synchronized (tlock) {
                if (!force && token != null && System.currentTimeMillis() < expires - 600_000) return token;
                Net.Resp r = Net.postJson(base + "/oauth2/token", null,
                        Json.map("grant_type", "client_credentials", "appkey", appkey, "secretkey", secretkey));
                String tok = Json.s(r.json.get("token"));
                if (r.status != 200 || tok.isEmpty()) {
                    Object m = r.json.get("return_msg");
                    throw new AuthError("키움 토큰 발급 실패(" + r.status + "): " + (m != null ? Json.s(m) : Json.stringify(r.json)));
                }
                try {
                    expires = new SimpleDateFormat("yyyyMMddHHmmss", Locale.KOREA).parse(Json.s(r.json.get("expires_dt"))).getTime();
                } catch (Exception e) {
                    expires = System.currentTimeMillis() + 12 * 3600_000L;
                }
                token = tok;
                return tok;
            }
        }

        Map<String, Object> call(String apiId, String path, Object body) throws Exception {
            for (int attempt = 0; attempt < 3; attempt++) {
                synchronized (rlock) {
                    long wait = 340 - (System.currentTimeMillis() - last);
                    if (wait > 0) Thread.sleep(wait);
                    last = System.currentTimeMillis();
                }
                Map<String, String> h = new HashMap<>();
                h.put("api-id", apiId);
                h.put("authorization", "Bearer " + getToken(false));
                h.put("cont-yn", "N");
                h.put("next-key", "");
                Net.Resp r = Net.postJson(base + path, h, body);
                if (r.status == 429) {
                    Thread.sleep(1000L + attempt * 1000L);
                    continue;
                }
                if (r.status == 401) {
                    getToken(true);
                    continue;
                }
                return r.json;
            }
            return new HashMap<>();
        }

        /** [이름, 현재가, 등락률] */
        Object[] stockInfo(String code) throws Exception {
            Map<String, Object> js = call("ka10001", "/api/dostk/stkinfo", Json.map("stk_cd", code));
            return new Object[]{Json.s(js.get("stk_nm")).trim(), Math.abs(Json.d(js.get("cur_prc"), 0)), Json.d(js.get("flu_rt"), 0)};
        }
    }

    // ───────── 감시 종목 ─────────
    static final class Watch {
        final String code;
        String name;
        final Set<String> sources = new TreeSet<>();
        boolean manual = false;
        String status = "감시";
        long enteredAt = 0;
        double entryPrice = 0, price = 0, rate = 0;
        boolean inited = false;
        int dayUp = 0, dayDn = 0, entUp = 0, entDn = 0;
        ArrayDeque<double[]> hist = new ArrayDeque<>();   // [시각ms, 저가, 고가]
        final Map<String, Long> last = new HashMap<>();
        final long added = System.currentTimeMillis();

        Watch(String code) {
            this.code = code;
            this.name = code;
        }

        String label() {
            return name.equals(code) ? code : name + "(" + code + ")";
        }
    }

    static final class Ix {
        final String name;
        double price = 0, rate = 0;
        int up = 0, dn = 0;
        boolean inited = false;

        Ix(String n) { name = n; }
    }

    private final Store cfg;
    private final Kakao kakao;
    private final Object lock = new Object();
    public volatile boolean running = false, connected = false;
    public volatile String status = "중지됨", error = "", publicIp = "";
    private volatile MiniWS ws;
    private volatile Rest rest;
    private Thread thread;
    private volatile List<String[]> conditions = new ArrayList<>();
    private final Map<String, String> active = new LinkedHashMap<>();
    private final ArrayDeque<String> pendingReq = new ArrayDeque<>();
    private final Map<String, Watch> watch = new LinkedHashMap<>();
    private final Map<String, Ix> indices = new LinkedHashMap<>();
    private final Map<String, String> names = new ConcurrentHashMap<>();
    private final LinkedBlockingQueue<String[]> infoQ = new LinkedBlockingQueue<>();
    private String lastAuthErr = "";

    public Engine(Store cfg, Kakao kakao) {
        this.cfg = cfg;
        this.kakao = kakao;
        for (String[] ix : INDEXES) indices.put(ix[0], new Ix(ix[1]));
        Thread t = new Thread(this::infoWorker, "info");
        t.setDaemon(true);
        t.start();
    }

    // ───────── 유틸 ─────────
    static String cleanCode(Object o) {
        String c = Json.s(o).trim();
        int u = c.indexOf('_');
        if (u >= 0) c = c.substring(0, u);
        if (c.length() == 7 && "AJQ".indexOf(c.charAt(0)) >= 0) c = c.substring(1);
        return c;
    }

    static String won(double p) {
        return p >= 100 ? String.format(Locale.KOREA, "%,.0f", p) : String.format(Locale.KOREA, "%,.2f", p);
    }

    static String pct(double r) {
        return String.format(Locale.KOREA, "%+.2f%%", r);
    }

    static String g(double v) {
        return v == Math.rint(v) ? Long.toString((long) v) : String.valueOf(v);
    }

    private Map<String, Object> alerts() {
        return cfg.copy("alerts");
    }

    private static double ad(Map<String, Object> a, String k) {
        return Json.d(a.get(k), 0);
    }

    // ───────── 제어 ─────────
    public synchronized Object[] start() {
        if (running) return new Object[]{true, "이미 실행 중"};
        String ak = cfg.str("kiwoom", "appkey"), sk = cfg.str("kiwoom", "secretkey");
        if (ak.isEmpty() || sk.isEmpty()) return new Object[]{false, "키움 App Key / Secret Key 를 먼저 입력하세요"};
        rest = new Rest(ak, sk, cfg.bool("kiwoom", "mock"));
        running = true;
        error = "";
        thread = new Thread(this::run, "kiwoom");
        thread.setDaemon(true);
        thread.start();
        return new Object[]{true, "감시 시작"};
    }

    public synchronized Object[] stop() {
        running = false;
        MiniWS w = ws;
        if (w != null) w.close();
        connected = false;
        status = "중지됨";
        synchronized (lock) {
            active.clear();
        }
        Logx.add("감시 중지");
        return new Object[]{true, "중지"};
    }

    private boolean send(Map<String, Object> obj) {
        MiniWS w = ws;
        if (w == null || !connected) return false;
        try {
            w.send(Json.stringify(obj));
            return true;
        } catch (Exception e) {
            Logx.add("전송 실패: " + e.getMessage(), "error");
            return false;
        }
    }

    private void sleepWhileRunning(long ms) {
        long end = System.currentTimeMillis() + ms;
        while (running && System.currentTimeMillis() < end) {
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                return;
            }
        }
    }

    private void run() {
        int backoff = 3;
        while (running) {
            try {
                session();
                backoff = 3;
            } catch (AuthError e) {
                String ip = Net.publicIp();
                publicIp = ip;
                String msg = e.getMessage() + (ip.isEmpty() ? "" : " · 현재 IP " + ip);
                if (!msg.equals(lastAuthErr)) {
                    lastAuthErr = msg;
                    Logx.add(msg, "error");
                    String em = e.getMessage();
                    kakao.push("⚠️ 키움 접속 실패\n" + (em.length() > 80 ? em.substring(0, 80) : em)
                            + "\n현재 IP: " + (ip.isEmpty() ? "확인불가" : ip)
                            + "\n키움 포털 '계좌 App Key 관리'에 이 IP를 등록하세요");
                }
                error = e.getMessage();
                status = "인증 오류 – IP 등록 확인 (" + (ip.isEmpty() ? "IP 확인불가" : ip) + ")";
                connected = false;
                closeWs();
                if (rest != null) rest.token = null;
                sleepWhileRunning(120_000);
                continue;
            } catch (Exception e) {
                error = String.valueOf(e.getMessage());
                if (running) Logx.add("연결 오류: " + e.getMessage(), "error");
            }
            connected = false;
            closeWs();
            if (!running) break;
            status = backoff + "초 후 재연결";
            sleepWhileRunning(backoff * 1000L);
            backoff = Math.min(backoff * 2, 60);
        }
        connected = false;
        status = "중지됨";
    }

    private void closeWs() {
        MiniWS w = ws;
        ws = null;
        if (w != null) w.close();
        synchronized (lock) {
            active.clear();
            pendingReq.clear();
        }
    }

    private void session() throws Exception {
        status = "토큰 발급 중";
        String token = rest.getToken(false);
        status = "서버 연결 중";
        MiniWS w = new MiniWS(rest.wsUrl);
        ws = w;
        w.send(Json.stringify(Json.map("trnm", "LOGIN", "token", token)));
        status = "로그인 중";
        while (running) {
            String msg = w.recv(1000);
            if (msg == null) continue;
            Map<String, Object> js;
            try {
                js = Json.obj(Json.parse(msg));
            } catch (RuntimeException e) {
                continue;
            }
            String tr = Json.s(js.get("trnm"));
            String rcode = Json.s(js.get("return_code"));
            switch (tr) {
                case "PING":
                    w.send(msg);
                    break;
                case "LOGIN":
                    if (!"0".equals(rcode)) throw new AuthError("키움 웹소켓 로그인 실패: " + Json.s(js.get("return_msg")));
                    connected = true;
                    error = "";
                    if (!lastAuthErr.isEmpty()) kakao.push("✅ 키움 재접속 성공");
                    lastAuthErr = "";
                    status = "실시간 감시 중" + (cfg.bool("kiwoom", "mock") ? " (모의)" : "");
                    Logx.add("키움 실시간 서버 로그인 성공");
                    afterLogin();
                    break;
                case "CNSRLST":
                    onCondList(js);
                    break;
                case "CNSRREQ":
                    onCondReq(js);
                    break;
                case "CNSRCLR":
                    break;
                case "REAL":
                    for (Object it : Json.arr(js.get("data"))) {
                        try {
                            onReal(Json.obj(it));
                        } catch (Exception e) {
                            Logx.add("실시간 처리 오류: " + e, "error");
                        }
                    }
                    break;
                case "REG":
                case "REMOVE":
                    if (!rcode.isEmpty() && !"0".equals(rcode)) Logx.add(tr + " 실패: " + Json.s(js.get("return_msg")), "error");
                    break;
                default:
                    if (!rcode.isEmpty() && !"0".equals(rcode)) Logx.add(tr + ": " + Json.s(js.get("return_msg")), "warn");
            }
        }
    }

    private void afterLogin() {
        List<Object> items = new ArrayList<>();
        for (String[] ix : INDEXES) items.add(ix[0]);
        send(Json.map("trnm", "REG", "grp_no", "2", "refresh", "1",
                "data", Collections.singletonList(Json.map("item", items, "type", Collections.singletonList("0J")))));
        applyWatchlist();
        List<String> codes;
        synchronized (lock) {
            codes = new ArrayList<>(watch.keySet());
        }
        reg(codes);
        send(Json.map("trnm", "CNSRLST"));
    }

    // ───────── 조건식 ─────────
    public Object[] refreshConditions() {
        if (!send(Json.map("trnm", "CNSRLST")))
            return new Object[]{false, "먼저 감시를 시작(키움 연결)해야 조건식 목록을 불러올 수 있습니다"};
        return new Object[]{true, "조건식 목록 요청함"};
    }

    private void onCondList(Map<String, Object> js) {
        if (!"0".equals(Json.s(js.get("return_code")))) {
            Logx.add("조건식 목록 실패: " + Json.s(js.get("return_msg")), "error");
            return;
        }
        List<String[]> conds = new ArrayList<>();
        for (Object row : Json.arr(js.get("data"))) {
            if (row instanceof List && ((List<?>) row).size() >= 2) {
                List<?> l = (List<?>) row;
                conds.add(new String[]{Json.s(l.get(0)).trim(), Json.s(l.get(1)).trim()});
            } else if (row instanceof Map) {
                Map<String, Object> m = Json.obj(row);
                conds.add(new String[]{Json.s(m.get("seq")).trim(), Json.s(m.get("name")).trim()});
            }
        }
        conditions = conds;
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < Math.min(10, conds.size()); i++) sb.append(i > 0 ? ", " : "").append(conds.get(i)[1]);
        Logx.add("조건식 " + conds.size() + "개 불러옴: " + sb);
        applySlots();
    }

    private String[] resolveSlot(Map<String, Object> slot) {
        String seq = Json.s(slot.get("seq")).trim(), name = Json.s(slot.get("name")).trim();
        for (String[] c : conditions) if (!seq.isEmpty() && c[0].equals(seq) && (name.isEmpty() || c[1].equals(name))) return c;
        for (String[] c : conditions) if (!name.isEmpty() && c[1].equals(name)) return c;
        return null;
    }

    public Object[] applySlots() {
        if (!connected || conditions.isEmpty()) return new Object[]{false, "키움 연결 후 자동 적용됩니다"};
        Map<String, String> want = new LinkedHashMap<>();
        List<String> unresolved = new ArrayList<>();
        List<Object> slots;
        synchronized (cfg.lock) {
            slots = Json.arr(Json.copy(cfg.data.get("slots")));
        }
        for (Object so : slots) {
            Map<String, Object> s = Json.obj(so);
            if (!Json.b(s.get("enabled"))) continue;
            String[] r = resolveSlot(s);
            if (r != null) want.put(r[0], r[1]);
            else if (!Json.s(s.get("name")).isEmpty() || !Json.s(s.get("seq")).isEmpty())
                unresolved.add(!Json.s(s.get("name")).isEmpty() ? Json.s(s.get("name")) : Json.s(s.get("seq")));
        }
        synchronized (lock) {
            for (String seq : new ArrayList<>(active.keySet())) {
                if (!want.containsKey(seq)) {
                    send(Json.map("trnm", "CNSRCLR", "seq", seq));
                    Logx.add("조건 실시간 해제: " + active.remove(seq));
                }
            }
            for (Map.Entry<String, String> e : want.entrySet()) {
                if (!active.containsKey(e.getKey())) {
                    active.put(e.getKey(), e.getValue());
                    pendingReq.addLast(e.getKey());
                    send(Json.map("trnm", "CNSRREQ", "seq", e.getKey(), "search_type", "1", "stex_tp", "K",
                            "cont_yn", "N", "next_key", ""));
                    Logx.add("조건 실시간 등록 요청: [" + e.getKey() + "] " + e.getValue());
                }
            }
        }
        String msg = "적용: " + want.size() + "개 감시";
        if (!unresolved.isEmpty()) {
            msg += " / 찾을 수 없는 조건식: " + String.join(", ", unresolved);
            Logx.add("영웅문4에 없는 조건식 이름: " + String.join(", ", unresolved), "warn");
        }
        return new Object[]{true, msg};
    }

    private void onCondReq(Map<String, Object> js) {
        String seq = Json.s(js.get("seq")).trim();
        String name;
        synchronized (lock) {
            if (pendingReq.contains(seq)) pendingReq.remove(seq);
            else if (!pendingReq.isEmpty()) seq = pendingReq.pollFirst();
            name = active.containsKey(seq) ? active.get(seq) : seq;
        }
        if (!"0".equals(Json.s(js.get("return_code")))) {
            Logx.add("조건 [" + name + "] 등록 실패: " + Json.s(js.get("return_msg")), "error");
            synchronized (lock) {
                active.remove(seq);
            }
            return;
        }
        List<String> codes = new ArrayList<>();
        for (Object row : Json.arr(js.get("data"))) {
            if (row instanceof Map) {
                Map<String, Object> m = Json.obj(row);
                Object c = m.get("jmcode") != null ? m.get("jmcode") : (m.get("9001") != null ? m.get("9001") : m.get("stk_cd"));
                if (c != null) codes.add(cleanCode(c));
            }
        }
        for (String c : codes) addSource(c, name, true);
        Logx.add("조건 [" + name + "] 실시간 감시 시작 – 현재 " + codes.size() + "종목");
        kakao.push("🟢 [" + name + "] 감시 시작\n현재 편입 " + codes.size() + "종목");
    }

    // ───────── 종목 관리 ─────────
    private void reg(List<String> codes) {
        for (int i = 0; i < codes.size(); i += 50) {
            List<Object> part = new ArrayList<Object>(codes.subList(i, Math.min(codes.size(), i + 50)));
            if (!part.isEmpty())
                send(Json.map("trnm", "REG", "grp_no", "1", "refresh", "1",
                        "data", Collections.singletonList(Json.map("item", part, "type", Collections.singletonList("0B")))));
        }
    }

    private void unreg(List<String> codes) {
        if (codes.isEmpty()) return;
        send(Json.map("trnm", "REMOVE", "grp_no", "1", "refresh", "1",
                "data", Collections.singletonList(Json.map("item", new ArrayList<Object>(codes), "type", Collections.singletonList("0B")))));
    }

    /** lock 보유 상태에서 호출. [Watch, 신규여부] */
    private Object[] ensure(String code) {
        Watch w = watch.get(code);
        if (w != null) return new Object[]{w, false};
        int mx = (int) Math.max(1, cfg.num("alerts", "max_watch"));
        if (watch.size() >= mx) {
            Watch oldest = null;
            for (Watch x : watch.values())
                if (x.sources.isEmpty() && !x.manual && (oldest == null || x.added < oldest.added)) oldest = x;
            if (oldest == null) {
                Logx.add("감시 종목 한도(" + mx + ") 초과 – " + code + " 제외", "warn");
                return new Object[]{null, false};
            }
            drop(oldest.code);
        }
        w = new Watch(code);
        String n = names.get(code);
        if (n != null) w.name = n;
        watch.put(code, w);
        reg(Collections.singletonList(code));
        return new Object[]{w, true};
    }

    private void drop(String code) {
        watch.remove(code);
        unreg(Collections.singletonList(code));
    }

    private void addSource(String code, String src, boolean initial) {
        synchronized (lock) {
            Watch w = (Watch) ensure(code)[0];
            if (w == null) return;
            w.sources.add(src);
            w.status = "편입";
            if (!initial) {
                w.enteredAt = System.currentTimeMillis();
                w.entryPrice = 0;
                w.entUp = w.entDn = 0;
            } else if (w.enteredAt == 0) {
                w.enteredAt = System.currentTimeMillis();
            }
        }
        infoQ.offer(new String[]{code, initial ? null : src});
    }

    private void removeSource(String code, String src) {
        Watch w;
        synchronized (lock) {
            w = watch.get(code);
            if (w == null) return;
            w.sources.remove(src);
            if (w.sources.isEmpty()) {
                w.status = w.manual ? "관심" : "이탈";
                if (!w.manual && !cfg.bool("alerts", "keep_after_exit")) drop(code);
            }
        }
        Map<String, Object> a = alerts();
        if (Json.b(a.get("on_exit")) && cool(w, "exit:" + src, a))
            kakao.push("⚪ [" + src + "] 이탈\n" + w.label() + " " + won(w.price) + "원 (" + pct(w.rate) + ")");
    }

    public Object[] applyWatchlist() {
        String text;
        synchronized (cfg.lock) {
            text = Json.s(cfg.data.get("watchlist"));
        }
        Set<String> want = new HashSet<>();
        for (String c : text.replace("\n", ",").replace(" ", ",").split(",")) {
            String cc = cleanCode(c);
            if (!cc.isEmpty()) want.add(cc);
        }
        synchronized (lock) {
            for (Watch w : new ArrayList<>(watch.values())) {
                if (w.manual && !want.contains(w.code)) {
                    w.manual = false;
                    if (w.sources.isEmpty()) drop(w.code);
                }
            }
            for (String c : want) {
                Object[] r = ensure(c);
                Watch w = (Watch) r[0];
                if (w != null) {
                    w.manual = true;
                    if (w.sources.isEmpty()) w.status = "관심";
                    if ((Boolean) r[1]) infoQ.offer(new String[]{c, null});
                }
            }
        }
        return new Object[]{true, "관심종목 " + want.size() + "개"};
    }

    private void infoWorker() {
        while (true) {
            String[] job;
            try {
                job = infoQ.take();
            } catch (InterruptedException e) {
                return;
            }
            String code = job[0], enterSrc = job[1];
            Watch w = null;
            try {
                String name = names.get(code);
                double p = 0, r = 0;
                Rest rs = rest;
                if (rs != null && (name == null || enterSrc != null)) {
                    Object[] info = rs.stockInfo(code);
                    String n2 = (String) info[0];
                    p = (Double) info[1];
                    r = (Double) info[2];
                    if (!n2.isEmpty()) {
                        name = n2;
                        names.put(code, n2);
                    }
                }
                synchronized (lock) {
                    w = watch.get(code);
                    if (w != null) {
                        if (name != null) w.name = name;
                        if (p > 0 && w.price == 0) {
                            w.price = p;
                            w.rate = r;
                            initLevels(w);
                        }
                        if (enterSrc != null && p > 0 && w.entryPrice == 0) w.entryPrice = p;
                    }
                }
                if (enterSrc != null && w != null) {
                    Map<String, Object> a = alerts();
                    if (Json.b(a.get("on_enter")) && cool(w, "enter:" + enterSrc, a)) {
                        String tm = Logx.now("HH:mm:ss");
                        kakao.push("🔔 [" + enterSrc + "] 편입\n" + w.label() + "\n" + won(w.price) + "원 (" + pct(w.rate) + ") " + tm);
                    }
                }
            } catch (Exception e) {
                Logx.add("종목정보 조회 실패 " + code + ": " + e.getMessage(), "warn");
                if (enterSrc != null) kakao.push("🔔 [" + enterSrc + "] 편입\n" + code);
            }
        }
    }

    // ───────── 실시간 처리 ─────────
    private boolean cool(Watch w, String kind, Map<String, Object> a) {
        long now = System.currentTimeMillis();
        synchronized (w.last) {
            Long l = w.last.get(kind);
            if (l != null && now - l < ad(a, "cooldown_min") * 60_000) return false;
            w.last.put(kind, now);
            return true;
        }
    }

    private void initLevels(Watch w) {
        double st = cfg.num("alerts", "day_step");
        int lvl = st > 0 ? (int) (w.rate / st) : 0;
        w.dayUp = Math.max(0, lvl);
        w.dayDn = Math.min(0, lvl);
        w.inited = true;
    }

    private void onReal(Map<String, Object> item) {
        String typ = Json.s(item.get("type"));
        Map<String, Object> v = Json.obj(item.get("values"));
        if ("02".equals(typ)) {
            String code = cleanCode(v.get("9001") != null ? v.get("9001") : item.get("item"));
            String seq = Json.s(v.get("841")).trim();
            String name;
            synchronized (lock) {
                name = active.get(seq);
            }
            if (name == null || code.isEmpty()) return;
            String io = Json.s(v.get("843"));
            if ("I".equals(io)) addSource(code, name, false);
            else if ("D".equals(io)) removeSource(code, name);
        } else if ("0B".equals(typ)) {
            onTick(cleanCode(item.get("item")), Math.abs(Json.d(v.get("10"), 0)), Json.d(v.get("12"), 0));
        } else if ("0J".equals(typ)) {
            onIndex(Json.s(item.get("item")).trim(), Math.abs(Json.d(v.get("10"), 0)), Json.d(v.get("12"), 0));
        }
    }

    private void onTick(String code, double p, double r) {
        if (p <= 0) return;
        Map<String, Object> a = alerts();
        List<String> out = new ArrayList<>();
        synchronized (lock) {
            Watch w = watch.get(code);
            if (w == null) return;
            w.price = p;
            w.rate = r;
            if (!w.inited) initLevels(w);
            double st = ad(a, "day_step");
            if (st > 0) {
                int lvl = (int) (r / st);
                if (lvl > w.dayUp && lvl > 0) {
                    w.dayUp = lvl;
                    out.add("📈 " + w.label() + "\n전일대비 +" + g(lvl * st) + "% 돌파\n" + won(p) + "원 (" + pct(r) + ")");
                } else if (lvl < w.dayDn && lvl < 0) {
                    w.dayDn = lvl;
                    out.add("📉 " + w.label() + "\n전일대비 " + g(lvl * st) + "% 하락 돌파\n" + won(p) + "원 (" + pct(r) + ")");
                }
            }
            if (w.enteredAt > 0 && w.entryPrice == 0) w.entryPrice = p;
            double es = ad(a, "entry_step");
            if (es > 0 && w.entryPrice > 0) {
                double er = (p / w.entryPrice - 1) * 100;
                int lvl = (int) (er / es);
                if (lvl > w.entUp && lvl > 0) {
                    w.entUp = lvl;
                    out.add("🚀 " + w.label() + "\n편입가 대비 +" + g(lvl * es) + "%\n" + won(w.entryPrice) + " → " + won(p)
                            + "원 (" + String.format(Locale.KOREA, "%+.2f%%", er) + ")");
                } else if (lvl < w.entDn && lvl < 0) {
                    w.entDn = lvl;
                    out.add("🔻 " + w.label() + "\n편입가 대비 " + g(lvl * es) + "%\n" + won(w.entryPrice) + " → " + won(p)
                            + "원 (" + String.format(Locale.KOREA, "%+.2f%%", er) + ")");
                }
            }
            double sp = ad(a, "surge_pct"), sm = ad(a, "surge_min");
            if (sp > 0 && sm > 0) {
                long now = System.currentTimeMillis();
                double[] lastB = w.hist.peekLast();
                if (lastB == null || now - lastB[0] >= 1000) {
                    w.hist.addLast(new double[]{now, p, p});
                } else {
                    lastB[1] = Math.min(lastB[1], p);
                    lastB[2] = Math.max(lastB[2], p);
                }
                while (!w.hist.isEmpty() && now - w.hist.peekFirst()[0] > sm * 60_000) w.hist.pollFirst();
                double lo = Double.MAX_VALUE, hi = 0;
                for (double[] b : w.hist) {
                    lo = Math.min(lo, b[1]);
                    hi = Math.max(hi, b[2]);
                }
                double up = (p / lo - 1) * 100, dn = (p / hi - 1) * 100;
                if (up >= sp && cool(w, "surge_up", a)) {
                    out.add("⚡ 급등 " + w.label() + "\n" + g(sm) + "분 내 +" + String.format(Locale.KOREA, "%.2f", up) + "%\n"
                            + won(lo) + " → " + won(p) + "원 (전일 " + pct(r) + ")");
                    w.hist.clear();
                    w.hist.addLast(new double[]{now, p, p});
                } else if (dn <= -sp && cool(w, "surge_dn", a)) {
                    out.add("💧 급락 " + w.label() + "\n" + g(sm) + "분 내 " + String.format(Locale.KOREA, "%.2f", dn) + "%\n"
                            + won(hi) + " → " + won(p) + "원 (전일 " + pct(r) + ")");
                    w.hist.clear();
                    w.hist.addLast(new double[]{now, p, p});
                }
            }
        }
        for (String m : out) kakao.push(m);
    }

    private void onIndex(String code, double p, double r) {
        String msg = null;
        synchronized (lock) {
            Ix ix = indices.get(code);
            if (ix == null || p <= 0) return;
            ix.price = p;
            ix.rate = r;
            double st = cfg.num("alerts", "index_step");
            int lvl = st > 0 ? (int) (r / st) : 0;
            if (!ix.inited) {
                ix.up = Math.max(0, lvl);
                ix.dn = Math.min(0, lvl);
                ix.inited = true;
                return;
            }
            if (st > 0) {
                if (lvl > ix.up && lvl > 0) {
                    ix.up = lvl;
                    msg = "🏛 " + ix.name + " 지수 +" + g(lvl * st) + "% 돌파\n" + String.format(Locale.KOREA, "%,.2f", p) + " (" + pct(r) + ")";
                } else if (lvl < ix.dn && lvl < 0) {
                    ix.dn = lvl;
                    msg = "🏛 " + ix.name + " 지수 " + g(lvl * st) + "% 하락 돌파\n" + String.format(Locale.KOREA, "%,.2f", p) + " (" + pct(r) + ")";
                }
            }
        }
        if (msg != null) kakao.push(msg);
    }

    public void resetLevels() {
        synchronized (lock) {
            for (Watch w : watch.values()) {
                w.inited = false;
                w.entUp = w.entDn = 0;
            }
            for (Ix ix : indices.values()) ix.inited = false;
        }
    }

    /** 상단 알림용 한 줄 요약 */
    public String summary() {
        if (!connected) return status;
        synchronized (lock) {
            Ix k = indices.get("001");
            String s = "감시 중 · " + watch.size() + "종목 · 조건 " + active.size() + "개";
            if (k != null && k.price > 0) s += " · 코스피 " + pct(k.rate);
            return s;
        }
    }

    public Map<String, Object> snapshot() {
        synchronized (lock) {
            List<Map<String, Object>> rows = new ArrayList<>();
            SimpleDateFormat f = new SimpleDateFormat("HH:mm:ss", Locale.KOREA);
            for (Watch w : watch.values()) {
                Object er = (w.entryPrice > 0 && w.price > 0) ? (Object) ((w.price / w.entryPrice - 1) * 100) : null;
                rows.add(Json.map("code", w.code, "name", w.name, "sources", new ArrayList<Object>(w.sources),
                        "manual", w.manual, "status", w.status, "price", w.price, "rate", w.rate,
                        "entry_price", w.entryPrice, "entry_rate", er,
                        "entered_at", w.enteredAt > 0 ? f.format(new Date(w.enteredAt)) : "",
                        "_t", (double) w.enteredAt));
            }
            Collections.sort(rows, (x, y) -> Double.compare(Json.d(y.get("_t"), 0), Json.d(x.get("_t"), 0)));
            List<Object> conds = new ArrayList<>();
            for (String[] c : conditions) conds.add(java.util.Arrays.asList((Object) c[0], c[1]));
            Map<String, Object> ixs = new LinkedHashMap<>();
            for (Map.Entry<String, Ix> e : indices.entrySet()) {
                Ix ix = e.getValue();
                ixs.put(e.getKey(), Json.map("name", ix.name, "price", ix.price, "rate", ix.rate));
            }
            return Json.map("running", running, "connected", connected, "status", status, "error", error,
                    "conditions", conds, "active", new LinkedHashMap<String, Object>(active),
                    "watch", rows, "indices", ixs);
        }
    }
}
