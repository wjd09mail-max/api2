package com.stockalert.core;

import com.stockalert.core.Store.Logx;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;

/** 키움 REST/WebSocket 감시 엔진 */
public final class Engine {
    static String REST_REAL = "https://api.kiwoom.com", REST_MOCK = "https://mockapi.kiwoom.com";
    static String WS_REAL = "wss://api.kiwoom.com:10000/api/dostk/websocket";
    static String WS_MOCK = "wss://mockapi.kiwoom.com:10000/api/dostk/websocket";
    static String REST_OVERRIDE = null, WS_OVERRIDE = null;
    static final String[][] INDEXES = {{"001", "코스피"}, {"101", "코스닥"}};
    public static final String MANUAL = "__manual__";

    public static void setOverrides(String rest, String ws) {
        REST_OVERRIDE = rest;
        WS_OVERRIDE = ws;
    }

    /** 인증 오류 (키/IP) */
    static final class AuthError extends Exception {
        private static final long serialVersionUID = 1L;
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
            return new Object[]{Json.s(js.get("stk_nm")).trim(), Math.abs(Json.d(js.get("cur_prc"), 0)), fixRate(Json.d(js.get("flu_rt"), 0))};
        }

        /** 업종 현재가 [현재가, 등락률] (실패 시 null) */
        double[] indexInfo(String code) throws Exception {
            Map<String, Object> js = call("ka20001", "/api/dostk/sect",
                    Json.map("mrkt_tp", "101".equals(code) ? "1" : "0", "inds_cd", code));
            double p = Math.abs(Json.d(js.get("cur_prc"), 0));
            if (p <= 0) return null;
            return new double[]{p, Json.d(js.get("flu_rt"), 0)};
        }
    }

    // ───────── 데이터 구조 ─────────
    /** 종목이 어떤 조건식에서 검색됐는지 */
    static final class Src {
        int hits;
        long first, last;
        boolean active;
    }

    static final class Watch {
        final String code;
        String name;
        final LinkedHashMap<String, Src> groups = new LinkedHashMap<>();
        boolean manual = false;
        boolean held = false;          // 자동매매 보유 종목 (목록에서 빠지지 않음)
        long enteredAt = 0;
        double entryPrice = 0, price = 0, rate = 0;
        boolean inited = false, tgtUpHit = false, tgtDnHit = false;
        int dayUp = 0, dayDn = 0, entUp = 0, entDn = 0;
        ArrayDeque<double[]> hist = new ArrayDeque<>();   // [시각ms, 저가, 고가]
        final Map<String, Long> last = new HashMap<>();
        long added = System.currentTimeMillis();

        Watch(String code) {
            this.code = code;
            this.name = code;
        }

        String label() {
            return name.equals(code) ? code : name + "(" + code + ")";
        }

        boolean anyActive() {
            for (Src s : groups.values()) if (s.active) return true;
            return false;
        }
    }

    static final class Ix {
        final String code, name;
        double price = 0, rate = 0;
        int up = 0, dn = 0;
        boolean inited = false, tgtUpHit = false, tgtDnHit = false;
        ArrayDeque<double[]> hist = new ArrayDeque<>();
        final Map<String, Long> last = new HashMap<>();

        Ix(String c, String n) {
            code = c;
            name = n;
        }
    }

    private final Store cfg;
    private final Kakao kakao;
    private final File stateFile;
    private final Object lock = new Object();
    public volatile boolean running = false, connected = false;
    public volatile String status = "중지됨", error = "", publicIp = "";
    private volatile MiniWS ws;
    private volatile Rest rest;
    private Thread thread;
    private volatile List<String[]> conditions = new ArrayList<>();
    private final Map<String, String> active = new LinkedHashMap<>();          // 실시간 등록된 seq → 이름
    private final ArrayDeque<String[]> pendingReq = new ArrayDeque<>();        // [seq, search_type, tag]
    private final Set<String> rtReg = new HashSet<>();                          // 실시간 등록(CNSRREQ type 1)된 seq
    private final java.util.concurrent.LinkedBlockingDeque<String[]> scanQueue = new java.util.concurrent.LinkedBlockingDeque<>();
    private final Map<String, Set<String>> pageCodes = new ConcurrentHashMap<>();
    private volatile long nextScan = 0;
    private final Map<String, Integer> pageCount = new ConcurrentHashMap<>();             // 연속조회 누적 종목수
    private final Map<String, Watch> watch = new LinkedHashMap<>();
    private final Map<String, Ix> indices = new LinkedHashMap<>();
    private final Map<String, String> names = new ConcurrentHashMap<>();
    private final Map<String, Integer> hitMemo = new HashMap<>();               // "조건|종목" → 오늘 검색 횟수
    private String day = today();
    private final LinkedBlockingQueue<String[]> infoQ = new LinkedBlockingQueue<>();
    private String lastAuthErr = "";
    private boolean loggedReal02 = false, loggedReal0J = false;
    private volatile boolean dirty = false;
    private volatile Trader trader;

    public void setTrader(Trader t) {
        trader = t;
    }

    Rest rest() {
        return rest;
    }

    /** 보유 종목을 실시간 시세 감시에 고정 */
    public void watchHeld(String code, String name) {
        synchronized (lock) {
            Watch w = (Watch) ensure(code)[0];
            if (w == null) return;
            w.held = true;
            if (name != null && !name.isEmpty() && !name.equals(code)) {
                w.name = name;
                names.put(code, name);
            }
        }
    }

    // 캐시된 설정 (틱마다 설정 파일을 복사하지 않도록)
    private volatile Map<String, Object> gAlerts = new HashMap<>();
    private volatile Map<String, Object> stockAlerts = new HashMap<>();
    private volatile Map<String, Object> indexAlerts = new HashMap<>();

    public Engine(Store cfg, Kakao kakao, File stateFile) {
        this.cfg = cfg;
        this.kakao = kakao;
        this.stateFile = stateFile;
        for (String[] ix : INDEXES) indices.put(ix[0], new Ix(ix[0], ix[1]));
        reloadSettings();
        loadState();
        Thread t = new Thread(this::infoWorker, "info");
        t.setDaemon(true);
        t.start();
        Thread sc = new Thread(this::scanLoop, "scan");
        sc.setDaemon(true);
        sc.start();
        Thread s = new Thread(() -> {
            while (true) {
                try {
                    Thread.sleep(5000);
                    if (dirty) saveState();
                } catch (InterruptedException e) {
                    return;
                } catch (Exception ignored) {
                }
            }
        }, "state");
        s.setDaemon(true);
        s.start();
    }

    public Engine(Store cfg, Kakao kakao) {
        this(cfg, kakao, null);
    }

    // ───────── 유틸 ─────────
    static String today() {
        return new SimpleDateFormat("yyyyMMdd", Locale.KOREA).format(new Date());
    }

    static String cleanCode(Object o) {
        String c = Json.s(o).trim();
        int u = c.indexOf('_');
        if (u >= 0) c = c.substring(0, u);
        if (c.length() == 7 && "AJQ".indexOf(c.charAt(0)) >= 0) c = c.substring(1);
        return c;
    }

    static String won(double p) {
        return Math.abs(p) >= 100 ? String.format(Locale.KOREA, "%,.0f", p) : String.format(Locale.KOREA, "%,.2f", p);
    }

    static String pct(double r) {
        return String.format(Locale.KOREA, "%+.2f%%", r);
    }

    static String g(double v) {
        return v == Math.rint(v) ? Long.toString((long) v) : String.valueOf(v);
    }

    /** 국내 주식 등락률은 ±30% 이내. 범위를 벗어나면 100배 값으로 보고 보정 */
    static double fixRate(double r) {
        return Math.abs(r) > 30.5 ? r / 100.0 : r;
    }

    /** 조건검색 결과의 등락률: 소수점 없이 오면 100배 값(예: -647 = -6.47%) */
    static double searchRate(Object raw) {
        String t = Json.s(raw).trim();
        double d = Json.d(raw, 0);
        if (!t.isEmpty() && t.indexOf('.') < 0) d = d / 100.0;
        return fixRate(d);
    }

    public int scanMin() {
        double d = scanMinD();
        return d <= 0 ? 0 : (int) Math.max(1, Math.round(d));
    }

    double scanMinD() {
        return Math.max(0, Json.d(gAlerts.get("scan_min"), 0));
    }

    static String clip(String s, int n) {
        return s.length() > n ? s.substring(0, n) + "…" : s;
    }

    /** 설정 변경 후 호출: 캐시 갱신 + 단계 초기화 */
    public void reloadSettings() {
        gAlerts = cfg.copy("alerts");
        stockAlerts = cfg.copy("stock_alerts");
        indexAlerts = cfg.copy("index_alerts");
    }

    private static final String[] OVR = {"day_step", "entry_step", "surge_pct", "surge_min", "price_up", "price_dn"};

    /** 종목별 실제 적용 설정 (개별 설정이 있으면 덮어씀) */
    private Map<String, Object> eff(String code) {
        Map<String, Object> a = new HashMap<>(gAlerts);
        a.put("price_up", 0.0);
        a.put("price_dn", 0.0);
        a.put("mute", false);
        Map<String, Object> o = Json.obj(stockAlerts.get(code));
        for (String k : OVR) if (o.get(k) != null && !Json.s(o.get(k)).isEmpty()) a.put(k, Json.d(o.get(k), 0));
        a.put("mute", Json.b(o.get("mute")));
        return a;
    }

    private static double ad(Map<String, Object> a, String k) {
        return Json.d(a.get(k), 0);
    }

    private boolean cool(Map<String, Long> last, String kind, double minutes) {
        long now = System.currentTimeMillis();
        synchronized (last) {
            Long l = last.get(kind);
            if (l != null && now - l < minutes * 60_000) return false;
            last.put(kind, now);
            return true;
        }
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
            pendingReq.clear();
            rtReg.clear();
        }
        scanQueue.clear();
        saveState();
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
                    kakao.push("⚠️ 키움 접속 실패\n" + clip(e.getMessage(), 80)
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
            pageCount.clear();
            rtReg.clear();
        }
        scanQueue.clear();
        pageCodes.clear();
    }

    private void session() throws Exception {
        status = "토큰 발급 중";
        String token = rest.getToken(false);
        status = "서버 연결 중";
        MiniWS w = new MiniWS(rest.wsUrl);
        ws = w;
        loggedReal02 = loggedReal0J = false;
        w.send(Json.stringify(Json.map("trnm", "LOGIN", "token", token)));
        status = "로그인 중";
        while (running) {
            String msg = w.recv(1000);
            if (msg == null) continue;
            Map<String, Object> js;
            try {
                js = Json.obj(Json.parse(msg));
            } catch (RuntimeException e) {
                Logx.add("해석 불가 메시지: " + clip(msg, 200), "debug");
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
                    status = "감시 중" + (cfg.bool("kiwoom", "mock") ? " (모의)" : "");
                    Logx.add("키움 실시간 서버 로그인 성공" + (cfg.bool("kiwoom", "mock") ? " – 모의투자 서버" : " – 실전 서버"));
                    afterLogin();
                    break;
                case "CNSRLST":
                    Logx.add("수신 CNSRLST: " + clip(msg, 300), "debug");
                    onCondList(js);
                    break;
                case "CNSRREQ":
                    Logx.add("수신 CNSRREQ: " + clip(msg, 300), "debug");
                    onCondReq(js);
                    break;
                case "CNSRCLR":
                    break;
                case "REAL":
                    for (Object it : Json.arr(js.get("data"))) {
                        try {
                            onReal(Json.obj(it), msg);
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
                    else Logx.add("기타 수신: " + clip(msg, 200), "debug");
            }
        }
    }

    private void afterLogin() {
        List<Object> items = new ArrayList<>();
        for (String[] ix : INDEXES) items.add(ix[0]);
        send(Json.map("trnm", "REG", "grp_no", "2", "refresh", "1",
                "data", Collections.singletonList(Json.map("item", items, "type", Collections.singletonList("0J")))));
        for (String[] ix : INDEXES) infoQ.offer(new String[]{"IX:" + ix[0], null});
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
            return new Object[]{false, "먼저 ▶시작(키움 연결)을 해야 조건식 목록을 불러올 수 있습니다"};
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
        if (conds.isEmpty()) Logx.add("조건식이 0개입니다 – 영웅문4 [0150]에서 조건식을 '저장'했는지 확인하세요", "warn");
        else Logx.add("조건식 " + conds.size() + "개 불러옴: " + sb);
        applySlots();
    }

    private String condName(String seq) {
        for (String[] c : conditions) if (c[0].equals(seq)) return c[1];
        return seq;
    }

    private String[] resolveSlot(Map<String, Object> slot) {
        String seq = Json.s(slot.get("seq")).trim(), name = Json.s(slot.get("name")).trim();
        for (String[] c : conditions) if (!seq.isEmpty() && c[0].equals(seq) && (name.isEmpty() || c[1].equals(name))) return c;
        for (String[] c : conditions) if (!name.isEmpty() && c[1].equals(name)) return c;
        return null;
    }

    private void sendCondReq(String seq, String type, String contYn, String nextKey) {
        sendCondReq(seq, type, contYn, nextKey, "1".equals(type) ? "real" : "manual");
    }

    private void sendCondReq(String seq, String type, String contYn, String nextKey, String tag) {
        synchronized (lock) {
            pendingReq.addLast(new String[]{seq, type, tag});
        }
        send(Json.map("trnm", "CNSRREQ", "seq", seq, "search_type", type, "stex_tp", "K",
                "cont_yn", contYn, "next_key", nextKey));
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
        int sm = scanMin();
        boolean scanMode = scanMinD() > 0;
        List<String[]> toReq = new ArrayList<>();
        List<String> toScan = new ArrayList<>();
        synchronized (lock) {
            for (String seq : new ArrayList<>(active.keySet())) {
                boolean modeChanged = scanMode == rtReg.contains(seq);
                if (!want.containsKey(seq) || modeChanged) {
                    if (rtReg.remove(seq)) send(Json.map("trnm", "CNSRCLR", "seq", seq));
                    String nm = active.remove(seq);
                    if (!want.containsKey(seq)) {
                        Logx.add("조건 감시 해제: " + nm);
                        for (Watch w : watch.values()) {
                            Src s = w.groups.get(nm);
                            if (s != null) s.active = false;
                        }
                    }
                    dirty = true;
                }
            }
            for (Map.Entry<String, String> e : want.entrySet()) {
                if (!active.containsKey(e.getKey())) {
                    active.put(e.getKey(), e.getValue());
                    if (scanMode) toScan.add(e.getKey());
                    else {
                        rtReg.add(e.getKey());
                        toReq.add(new String[]{e.getKey(), e.getValue()});
                    }
                }
            }
        }
        for (String[] r : toReq) {
            pageCount.put(r[0] + "|1", 0);
            sendCondReq(r[0], "1", "N", "");
            Logx.add("조건 실시간 등록 요청: [" + r[0] + "] " + r[1]);
        }
        for (String seq : toScan) {
            scanQueue.addLast(new String[]{seq, "scanFirst"});
            Logx.add("조건 " + sm + "분 주기 검색 등록: [" + seq + "] " + condName(seq));
        }
        if (scanMode) nextScan = System.currentTimeMillis() + (long) (scanMinD() * 60_000);
        String msg = "적용: " + want.size() + "개 " + (scanMode ? sm + "분 주기 검색" : "실시간 감시");
        if (!unresolved.isEmpty()) {
            msg += " / 찾을 수 없는 조건식: " + String.join(", ", unresolved);
            Logx.add("영웅문4에 없는 조건식 이름: " + String.join(", ", unresolved), "warn");
        }
        return new Object[]{true, msg};
    }

    /** 주기 검색 스케줄러: N분마다 감시 중인 조건식을 다시 검색 */
    private void scanLoop() {
        long lastSent = 0;
        while (true) {
            try {
                Thread.sleep(300);
                if (!connected) continue;
                double sm = scanMinD();
                long now = System.currentTimeMillis();
                if (sm > 0 && nextScan > 0 && now >= nextScan) {
                    nextScan = now + (long) (sm * 60_000);
                    List<String> seqs;
                    synchronized (lock) {
                        seqs = new ArrayList<>(active.keySet());
                    }
                    for (String q : seqs) scanQueue.addLast(new String[]{q, "scan"});
                }
                if (!scanQueue.isEmpty() && now - lastSent >= 1500) {   // 조건식 사이 1.5초 간격
                    String[] job = scanQueue.pollFirst();
                    if (job == null) continue;
                    synchronized (lock) {
                        if (!active.containsKey(job[0])) continue;
                    }
                    pageCount.put(job[0] + "|0", 0);
                    pageCodes.put(job[0] + "|0", new HashSet<String>());
                    sendCondReq(job[0], "0", "N", "", job[1]);
                    lastSent = now;
                }
            } catch (InterruptedException e) {
                return;
            } catch (Exception e) {
                Logx.add("주기 검색 오류: " + e.getMessage(), "warn");
            }
        }
    }

    /** 조건식 즉시 검색 (1회 조회, search_type 0) */
    public Object[] searchOnce(String seqOrName) {
        if (!connected) return new Object[]{false, "먼저 ▶시작(키움 연결)을 하세요"};
        String seq = null;
        for (String[] c : conditions) if (c[0].equals(seqOrName) || c[1].equals(seqOrName)) seq = c[0];
        if (seq == null) return new Object[]{false, "조건식을 찾을 수 없습니다: " + seqOrName + " (목록 불러오기 확인)"};
        pageCount.put(seq + "|0", 0);
        sendCondReq(seq, "0", "N", "");
        Logx.add("조건 즉시 검색 요청: [" + seq + "] " + condName(seq));
        return new Object[]{true, "'" + condName(seq) + "' 검색 요청 – 결과는 감시 탭에 표시됩니다"};
    }

    private void onCondReq(Map<String, Object> js) {
        String seq = Json.s(js.get("seq")).trim();
        String type = Json.s(js.get("search_type")).trim();
        String[] req = null;
        synchronized (lock) {
            for (Iterator<String[]> it = pendingReq.iterator(); it.hasNext(); ) {
                String[] p = it.next();
                if ((seq.isEmpty() || p[0].equals(seq)) && (type.isEmpty() || p[1].equals(type))) {
                    req = p;
                    it.remove();
                    break;
                }
            }
            if (req == null && !pendingReq.isEmpty()) req = pendingReq.pollFirst();
        }
        if (req == null) req = new String[]{seq, type.isEmpty() ? "1" : type, type.equals("0") ? "manual" : "real"};
        seq = req[0];
        type = req[1];
        String tag = req.length > 2 ? req[2] : "";
        boolean scanTag = "scan".equals(tag) || "scanFirst".equals(tag);
        String name = condName(seq);
        boolean realtime = "1".equals(type);
        if (!"0".equals(Json.s(js.get("return_code")))) {
            Logx.add("조건 [" + name + "] " + (realtime ? "실시간 등록" : "검색") + " 실패: " + Json.s(js.get("return_msg")), "error");
            kakao.push("⚠️ [" + name + "] 조건검색 실패\n" + clip(Json.s(js.get("return_msg")), 100));
            if (realtime) synchronized (lock) {
                active.remove(seq);
            }
            return;
        }
        int n = 0;
        for (Object row : Json.arr(js.get("data"))) {
            if (!(row instanceof Map)) continue;
            Map<String, Object> m = Json.obj(row);
            Object c = m.get("jmcode") != null ? m.get("jmcode") : (m.get("9001") != null ? m.get("9001") : m.get("stk_cd"));
            if (c == null) continue;
            String code = cleanCode(c);
            if (code.isEmpty()) continue;
            String nm = Json.s(m.get("302")).trim();
            if (!nm.isEmpty()) names.put(code, nm);
            double p = Math.abs(Json.d(m.get("10"), 0));
            addSource(code, name, realtime ? "init" : (scanTag ? tag : "search"), p, searchRate(m.get("12")));
            if (scanTag) {
                Set<String> set = pageCodes.get(seq + "|0");
                if (set != null) set.add(code);
            }
            n++;
        }
        String key = seq + "|" + type;
        int total = (pageCount.containsKey(key) ? pageCount.get(key) : 0) + n;
        pageCount.put(key, total);
        String cont = Json.s(js.get("cont_yn")), next = Json.s(js.get("next_key"));
        if ("Y".equals(cont) && !next.isEmpty()) {          // 연속조회: 나머지 이어받기
            sendCondReq(seq, type, "Y", next, tag);
            return;
        }
        pageCount.remove(key);
        if (scanTag) {
            Set<String> found = pageCodes.remove(seq + "|0");
            if (found == null) found = new HashSet<>();
            List<String> gone = new ArrayList<>();
            synchronized (lock) {
                for (Watch w : watch.values()) {
                    Src s = w.groups.get(name);
                    if (s != null && s.active && !found.contains(w.code)) gone.add(w.code);
                }
            }
            for (String c : gone) removeSource(c, name);
            if ("scanFirst".equals(tag)) {
                Logx.add("조건 [" + name + "] " + scanMin() + "분 주기 감시 시작 – 현재 " + total + "종목");
                kakao.push("🟢 [" + name + "] " + scanMin() + "분 주기 감시 시작\n현재 " + total + "종목");
            } else {
                Logx.add("조건 [" + name + "] 주기 검색 " + total + "종목 (빠짐 " + gone.size() + ")");
            }
            return;
        }
        if (realtime) {
            Logx.add("조건 [" + name + "] 실시간 감시 시작 – 현재 " + total + "종목");
            kakao.push("🟢 [" + name + "] 감시 시작\n현재 편입 " + total + "종목");
        } else {
            Logx.add("조건 [" + name + "] 검색 결과 " + total + "종목");
            kakao.push("🔍 [" + name + "] 검색 결과\n" + total + "종목");
        }
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
        int mx = (int) Math.max(1, Json.d(gAlerts.get("max_watch"), 100));
        if (watch.size() >= mx) {
            Watch oldest = null;
            for (Watch x : watch.values())
                if (!x.anyActive() && !x.manual && !x.held && (oldest == null || x.added < oldest.added)) oldest = x;
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

    private void checkDay() {
        String t = today();
        if (!t.equals(day)) {        // 날짜가 바뀌면 검색 횟수 초기화
            day = t;
            hitMemo.clear();
            for (Watch w : watch.values()) for (Src s : w.groups.values()) s.hits = 0;
        }
    }

    /** kind: real(실시간 편입) / init(실시간 등록 시 현재 종목) / search(즉시 검색) */
    private void addSource(String code, String cond, String kind, double p0, double r0) {
        boolean real = "real".equals(kind);
        boolean alertIt = false;
        boolean count;
        synchronized (lock) {
            checkDay();
            Watch w = (Watch) ensure(code)[0];
            if (w == null) return;
            Src s = w.groups.get(cond);
            boolean wasActive = s != null && s.active;
            long now = System.currentTimeMillis();
            String key = cond + "|" + code;
            if (s == null) {
                s = new Src();
                s.hits = hitMemo.containsKey(key) ? hitMemo.get(key) : 0;
                s.first = now;
                w.groups.put(cond, s);
            }
            // 실시간 편입은 항상 +1, 즉시검색은 새로 잡혔을 때만, 재접속 시 현재목록(init)은 처음 보는 종목만
            if (real) count = true;
            else if ("search".equals(kind) || "scan".equals(kind)) count = !wasActive;
            else count = s.hits == 0;                         // init / scanFirst
            boolean entered = real || ("scan".equals(kind) && !wasActive);
            alertIt = entered;
            if (count) {
                s.hits++;
                hitMemo.put(key, s.hits);
                s.last = now;
            }
            s.active = true;
            if (entered && !wasActive) {
                w.enteredAt = now;
                w.entryPrice = 0;
                w.entUp = w.entDn = 0;
            } else if (w.enteredAt == 0) {
                w.enteredAt = now;
            }
            if (p0 > 0 && (w.price == 0 || !"init".equals(kind))) {
                w.price = p0;
                w.rate = r0;
            }
            dirty = true;
        }
        infoQ.offer(new String[]{code, alertIt ? cond : null});
    }

    private void removeSource(String code, String cond) {
        Watch w;
        synchronized (lock) {
            w = watch.get(code);
            if (w == null) return;
            Src s = w.groups.get(cond);
            if (s != null) s.active = false;
            if (!Json.b(gAlerts.get("keep_after_exit"))) {
                w.groups.remove(cond);
                if (w.groups.isEmpty() && !w.manual && !w.held) drop(code);
            }
            dirty = true;
        }
        Map<String, Object> a = eff(code);
        if (Json.b(gAlerts.get("on_exit")) && !Json.b(a.get("mute")) && cool(w.last, "exit:" + cond, ad(gAlerts, "cooldown_min")))
            kakao.push("⚪ [" + cond + "] 이탈\n" + w.label() + " " + won(w.price) + "원 (" + pct(w.rate) + ")");
    }

    /** 감시 목록에서 삭제. group: 조건식 이름 / __manual__ / "" (모든 그룹) */
    public Object[] removeWatch(String code, String group) {
        String name;
        synchronized (lock) {
            Watch w = watch.get(code);
            if (w == null) return new Object[]{false, "목록에 없는 종목입니다"};
            name = w.label();
            if (group == null || group.isEmpty()) {
                w.groups.clear();
                w.manual = false;
            } else if (MANUAL.equals(group)) {
                w.manual = false;
            } else {
                w.groups.remove(group);
            }
            if (w.groups.isEmpty() && !w.manual && !w.held) drop(code);
            dirty = true;
        }
        if (group == null || group.isEmpty() || MANUAL.equals(group)) removeFromWatchlistCfg(code);
        return new Object[]{true, name + " 삭제됨" + (MANUAL.equals(group) || group == null || group.isEmpty() ? "" : " (다시 검색되면 횟수를 이어서 표시)")};
    }

    /** 그룹(조건식) 전체 비우기 */
    public Object[] clearGroup(String group) {
        int n = 0;
        synchronized (lock) {
            for (Watch w : new ArrayList<>(watch.values())) {
                boolean hit = MANUAL.equals(group) ? w.manual : w.groups.containsKey(group);
                if (!hit) continue;
                if (MANUAL.equals(group)) w.manual = false;
                else w.groups.remove(group);
                if (w.groups.isEmpty() && !w.manual && !w.held) drop(w.code);
                n++;
            }
            dirty = true;
        }
        if (MANUAL.equals(group)) {
            synchronized (cfg.lock) {
                cfg.data.put("watchlist", "");
                cfg.save();
            }
        }
        return new Object[]{true, n + "종목 삭제됨"};
    }

    private void removeFromWatchlistCfg(String code) {
        synchronized (cfg.lock) {
            String text = Json.s(cfg.data.get("watchlist"));
            List<String> keep = new ArrayList<>();
            for (String c : text.replace("\n", ",").replace(" ", ",").split(",")) {
                String cc = cleanCode(c);
                if (!cc.isEmpty() && !cc.equals(code)) keep.add(cc);
            }
            cfg.data.put("watchlist", String.join(", ", keep));
            cfg.save();
        }
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
                    if (w.groups.isEmpty() && !w.held) drop(w.code);
                }
            }
            for (String c : want) {
                Object[] r = ensure(c);
                Watch w = (Watch) r[0];
                if (w != null) {
                    w.manual = true;
                    if ((Boolean) r[1] || w.name.equals(w.code)) infoQ.offer(new String[]{c, null});
                }
            }
            dirty = true;
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
            if (code.startsWith("IX:")) {
                String ic = code.substring(3);
                try {
                    Rest rs = rest;
                    double[] v = rs == null ? null : rs.indexInfo(ic);
                    if (v != null) synchronized (lock) {
                        Ix ix = indices.get(ic);
                        if (ix != null && ix.price == 0) {
                            ix.price = v[0];
                            ix.rate = v[1];
                        }
                    }
                } catch (Exception e) {
                    Logx.add("지수 현재가 조회 실패 " + ic + ": " + e.getMessage(), "debug");
                }
                continue;
            }
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
                    if (!n2.isEmpty() && !n2.equals(code)) {
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
                        }
                        if (enterSrc != null && w.entryPrice == 0 && w.price > 0) w.entryPrice = w.price;
                        dirty = true;
                    }
                }
                if (enterSrc != null && w != null) {
                    Trader tr = trader;
                    if (tr != null) tr.onSignal(code, enterSrc, w.price, w.name);
                    Map<String, Object> a = eff(code);
                    int hits = 0;
                    synchronized (lock) {
                        Src s = w.groups.get(enterSrc);
                        if (s != null) hits = s.hits;
                    }
                    if (Json.b(gAlerts.get("on_enter")) && !Json.b(a.get("mute"))
                            && cool(w.last, "enter:" + enterSrc, ad(gAlerts, "cooldown_min"))) {
                        String tm = Logx.now("HH:mm:ss");
                        kakao.push("🔔 [" + enterSrc + "] 편입" + (hits > 1 ? " (" + hits + "회째)" : "") + "\n" + w.label() + "\n"
                                + won(w.price) + "원 (" + pct(w.rate) + ") " + tm);
                    }
                }
            } catch (Exception e) {
                Logx.add("종목정보 조회 실패 " + code + ": " + e.getMessage(), "warn");
                if (enterSrc != null) kakao.push("🔔 [" + enterSrc + "] 편입\n" + code);
            }
        }
    }

    // ───────── 실시간 처리 ─────────
    private void initLevels(Watch w, Map<String, Object> a) {
        double st = ad(a, "day_step");
        int lvl = st > 0 ? (int) (w.rate / st) : 0;
        w.dayUp = Math.max(0, lvl);
        w.dayDn = Math.min(0, lvl);
        w.inited = true;
    }

    private void onReal(Map<String, Object> item, String raw) {
        String typ = Json.s(item.get("type"));
        Map<String, Object> v = Json.obj(item.get("values"));
        if ("02".equals(typ)) {
            if (!loggedReal02) {
                loggedReal02 = true;
                Logx.add("첫 실시간 조건 신호: " + clip(raw, 300), "debug");
            }
            String code = cleanCode(v.get("9001") != null ? v.get("9001") : item.get("item"));
            String seq = Json.s(v.get("841")).trim();
            String name;
            synchronized (lock) {
                name = active.get(seq);
            }
            if (name == null || code.isEmpty()) return;
            String io = Json.s(v.get("843"));
            if ("I".equals(io)) addSource(code, name, "real", 0, 0);
            else if ("D".equals(io)) removeSource(code, name);
        } else if ("0B".equals(typ)) {
            onTick(cleanCode(item.get("item")), Math.abs(Json.d(v.get("10"), 0)), fixRate(Json.d(v.get("12"), 0)));
        } else if ("0J".equals(typ)) {
            if (!loggedReal0J) {
                loggedReal0J = true;
                Logx.add("첫 실시간 지수 신호: " + clip(raw, 200), "debug");
            }
            onIndex(cleanCode(item.get("item")), Math.abs(Json.d(v.get("10"), 0)), Json.d(v.get("12"), 0));
        }
    }

    /** 급등락 판정 (버킷 갱신 포함). 반환: [상승%, 하락%, 저가, 고가] */
    private static double[] surge(ArrayDeque<double[]> hist, double p, double minutes) {
        long now = System.currentTimeMillis();
        double[] lastB = hist.peekLast();
        if (lastB == null || now - lastB[0] >= 1000) {
            hist.addLast(new double[]{now, p, p});
        } else {
            lastB[1] = Math.min(lastB[1], p);
            lastB[2] = Math.max(lastB[2], p);
        }
        while (!hist.isEmpty() && now - hist.peekFirst()[0] > minutes * 60_000) hist.pollFirst();
        double lo = Double.MAX_VALUE, hi = 0;
        for (double[] b : hist) {
            lo = Math.min(lo, b[1]);
            hi = Math.max(hi, b[2]);
        }
        return new double[]{(p / lo - 1) * 100, (p / hi - 1) * 100, lo, hi};
    }

    private void onTick(String code, double p, double r) {
        if (p <= 0) return;
        Trader tr = trader;
        if (tr != null) tr.onTick(code, p);
        Map<String, Object> a = eff(code);
        boolean mute = Json.b(a.get("mute"));
        double cd = ad(gAlerts, "cooldown_min");
        List<String> out = new ArrayList<>();
        synchronized (lock) {
            Watch w = watch.get(code);
            if (w == null) return;
            w.price = p;
            w.rate = r;
            dirty = true;
            if (!w.inited) initLevels(w, a);
            if (w.enteredAt > 0 && w.entryPrice == 0 && w.anyActive()) w.entryPrice = p;
            if (mute) return;
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
            double es = ad(a, "entry_step");
            if (es > 0 && w.entryPrice > 0) {
                double er = (p / w.entryPrice - 1) * 100;
                int lvl = (int) (er / es);
                String ers = String.format(Locale.KOREA, "%+.2f%%", er);
                if (lvl > w.entUp && lvl > 0) {
                    w.entUp = lvl;
                    out.add("🚀 " + w.label() + "\n편입가 대비 +" + g(lvl * es) + "%\n" + won(w.entryPrice) + " → " + won(p) + "원 (" + ers + ")");
                } else if (lvl < w.entDn && lvl < 0) {
                    w.entDn = lvl;
                    out.add("🔻 " + w.label() + "\n편입가 대비 " + g(lvl * es) + "%\n" + won(w.entryPrice) + " → " + won(p) + "원 (" + ers + ")");
                }
            }
            double pu = ad(a, "price_up"), pd = ad(a, "price_dn");
            if (pu > 0 && p >= pu && !w.tgtUpHit) {
                w.tgtUpHit = true;
                out.add("🎯 목표가 도달 " + w.label() + "\n" + won(p) + "원 ≥ " + won(pu) + "원 (" + pct(r) + ")");
            }
            if (pd > 0 && p <= pd && !w.tgtDnHit) {
                w.tgtDnHit = true;
                out.add("⛔ 손절가 도달 " + w.label() + "\n" + won(p) + "원 ≤ " + won(pd) + "원 (" + pct(r) + ")");
            }
            double sp = ad(a, "surge_pct"), sm = ad(a, "surge_min");
            if (sp > 0 && sm > 0) {
                double[] s = surge(w.hist, p, sm);
                long now = System.currentTimeMillis();
                if (s[0] >= sp && cool(w.last, "surge_up", cd)) {
                    out.add("⚡ 급등 " + w.label() + "\n" + g(sm) + "분 내 +" + String.format(Locale.KOREA, "%.2f", s[0]) + "%\n"
                            + won(s[2]) + " → " + won(p) + "원 (전일 " + pct(r) + ")");
                    w.hist.clear();
                    w.hist.addLast(new double[]{now, p, p});
                } else if (s[1] <= -sp && cool(w.last, "surge_dn", cd)) {
                    out.add("💧 급락 " + w.label() + "\n" + g(sm) + "분 내 " + String.format(Locale.KOREA, "%.2f", s[1]) + "%\n"
                            + won(s[3]) + " → " + won(p) + "원 (전일 " + pct(r) + ")");
                    w.hist.clear();
                    w.hist.addLast(new double[]{now, p, p});
                }
            }
        }
        for (String m : out) kakao.push(m);
    }

    private void onIndex(String code, double p, double r) {
        Trader tr = trader;
        if (tr != null && p > 0) tr.onIndex(code, r);
        List<String> out = new ArrayList<>();
        synchronized (lock) {
            Ix ix = indices.get(code);
            if (ix == null || p <= 0) return;
            ix.price = p;
            ix.rate = r;
            Map<String, Object> s = Json.obj(indexAlerts.get(code));
            boolean on = !s.containsKey("enabled") || Json.b(s.get("enabled"));
            double st = s.containsKey("step") ? Json.d(s.get("step"), 0) : ad(gAlerts, "index_step");
            int lvl = st > 0 ? (int) (r / st) : 0;
            if (!ix.inited) {
                ix.up = Math.max(0, lvl);
                ix.dn = Math.min(0, lvl);
                ix.inited = true;
                return;
            }
            if (!on) return;
            String ps = String.format(Locale.KOREA, "%,.2f", p);
            if (st > 0) {
                if (lvl > ix.up && lvl > 0) {
                    ix.up = lvl;
                    out.add("🏛 " + ix.name + " 지수 +" + g(lvl * st) + "% 돌파\n" + ps + " (" + pct(r) + ")");
                } else if (lvl < ix.dn && lvl < 0) {
                    ix.dn = lvl;
                    out.add("🏛 " + ix.name + " 지수 " + g(lvl * st) + "% 하락 돌파\n" + ps + " (" + pct(r) + ")");
                }
            }
            double pu = Json.d(s.get("price_up"), 0), pd = Json.d(s.get("price_dn"), 0);
            if (pu > 0 && p >= pu && !ix.tgtUpHit) {
                ix.tgtUpHit = true;
                out.add("🎯 " + ix.name + " 지수 " + ps + " ≥ " + g(pu) + " 도달 (" + pct(r) + ")");
            }
            if (pd > 0 && p <= pd && !ix.tgtDnHit) {
                ix.tgtDnHit = true;
                out.add("⛔ " + ix.name + " 지수 " + ps + " ≤ " + g(pd) + " 도달 (" + pct(r) + ")");
            }
            double sp = Json.d(s.get("surge_pct"), 0), sm = Json.d(s.get("surge_min"), 0);
            if (sp > 0 && sm > 0) {
                double[] v = surge(ix.hist, p, sm);
                double cd = ad(gAlerts, "cooldown_min");
                long now = System.currentTimeMillis();
                if (v[0] >= sp && cool(ix.last, "surge_up", cd)) {
                    out.add("⚡ " + ix.name + " 지수 급등\n" + g(sm) + "분 내 +" + String.format(Locale.KOREA, "%.2f", v[0]) + "% → " + ps + " (" + pct(r) + ")");
                    ix.hist.clear();
                    ix.hist.addLast(new double[]{now, p, p});
                } else if (v[1] <= -sp && cool(ix.last, "surge_dn", cd)) {
                    out.add("💧 " + ix.name + " 지수 급락\n" + g(sm) + "분 내 " + String.format(Locale.KOREA, "%.2f", v[1]) + "% → " + ps + " (" + pct(r) + ")");
                    ix.hist.clear();
                    ix.hist.addLast(new double[]{now, p, p});
                }
            }
        }
        for (String m : out) kakao.push(m);
    }

    /** 설정 저장 후: 캐시 갱신 + 단계/목표가 재무장 */
    public void resetLevels() {
        reloadSettings();
        synchronized (lock) {
            for (Watch w : watch.values()) {
                w.inited = false;
                w.entUp = w.entDn = 0;
                w.tgtUpHit = w.tgtDnHit = false;
            }
            for (Ix ix : indices.values()) {
                ix.inited = false;
                ix.tgtUpHit = ix.tgtDnHit = false;
            }
        }
    }

    public void resetStock(String code) {
        reloadSettings();
        synchronized (lock) {
            Watch w = watch.get(code);
            if (w != null) {
                w.inited = false;
                w.tgtUpHit = w.tgtDnHit = false;
            }
            Ix ix = indices.get(code);
            if (ix != null) {
                ix.inited = false;
                ix.tgtUpHit = ix.tgtDnHit = false;
            }
        }
    }

    public double priceOf(String code) {
        synchronized (lock) {
            Watch w = watch.get(code);
            return w == null ? 0 : w.price;
        }
    }

    public String nameOf(String code) {
        synchronized (lock) {
            Watch w = watch.get(code);
            if (w != null) return w.name;
            Ix ix = indices.get(code);
            if (ix != null) return ix.name;
        }
        String n = names.get(code);
        return n != null ? n : code;
    }

    // ───────── 상태 저장/복원 (앱 재시작 시 목록 유지) ─────────
    public void saveState() {
        if (stateFile == null) return;
        Map<String, Object> st;
        synchronized (lock) {
            dirty = false;
            List<Object> rows = new ArrayList<>();
            for (Watch w : watch.values()) {
                Map<String, Object> gs = new LinkedHashMap<>();
                for (Map.Entry<String, Src> e : w.groups.entrySet())
                    gs.put(e.getKey(), Json.map("hits", (double) e.getValue().hits, "first", (double) e.getValue().first,
                            "last", (double) e.getValue().last, "active", e.getValue().active));
                rows.add(Json.map("code", w.code, "name", w.name, "manual", w.manual, "groups", gs,
                        "entered_at", (double) w.enteredAt, "entry_price", w.entryPrice, "price", w.price, "rate", w.rate,
                        "added", (double) w.added));
            }
            Map<String, Object> hm = new LinkedHashMap<>();
            for (Map.Entry<String, Integer> e : hitMemo.entrySet()) hm.put(e.getKey(), (double) e.getValue());
            st = Json.map("day", day, "watch", rows, "hit_memo", hm);
        }
        try {
            File tmp = new File(stateFile.getPath() + ".tmp");
            try (FileOutputStream o = new FileOutputStream(tmp)) {
                o.write(Json.stringify(st).getBytes(StandardCharsets.UTF_8));
            }
            if (!tmp.renameTo(stateFile)) {
                try (FileOutputStream o = new FileOutputStream(stateFile)) {
                    o.write(Json.stringify(st).getBytes(StandardCharsets.UTF_8));
                }
            }
        } catch (Exception e) {
            Logx.add("감시목록 저장 실패: " + e.getMessage(), "warn");
        }
    }

    private void loadState() {
        if (stateFile == null || !stateFile.exists()) return;
        try {
            Map<String, Object> st = Json.obj(Json.parse(Net.readAll(new FileInputStream(stateFile))));
            boolean sameDay = today().equals(Json.s(st.get("day")));
            synchronized (lock) {
                if (sameDay) {
                    for (Map.Entry<String, Object> e : Json.obj(st.get("hit_memo")).entrySet())
                        hitMemo.put(e.getKey(), (int) Json.d(e.getValue(), 0));
                }
                for (Object o : Json.arr(st.get("watch"))) {
                    Map<String, Object> m = Json.obj(o);
                    String code = Json.s(m.get("code"));
                    if (code.isEmpty()) continue;
                    boolean manual = Json.b(m.get("manual"));
                    if (!sameDay && !manual) continue;          // 새 날이면 조건식 검색 결과는 비움
                    Watch w = new Watch(code);
                    w.name = Json.s(m.get("name")).isEmpty() ? code : Json.s(m.get("name"));
                    names.put(code, w.name);
                    w.manual = manual;
                    w.added = (long) Json.d(m.get("added"), System.currentTimeMillis());
                    if (sameDay) {
                        for (Map.Entry<String, Object> e : Json.obj(m.get("groups")).entrySet()) {
                            Map<String, Object> g = Json.obj(e.getValue());
                            Src s = new Src();
                            s.hits = (int) Json.d(g.get("hits"), 0);
                            s.first = (long) Json.d(g.get("first"), 0);
                            s.last = (long) Json.d(g.get("last"), 0);
                            s.active = false;                    // 재접속 후 실시간 등록 시 다시 활성화
                            w.groups.put(e.getKey(), s);
                        }
                        w.enteredAt = (long) Json.d(m.get("entered_at"), 0);
                        w.entryPrice = Json.d(m.get("entry_price"), 0);
                        w.price = Json.d(m.get("price"), 0);
                        w.rate = fixRate(Json.d(m.get("rate"), 0));
                    }
                    if (!w.groups.isEmpty() || w.manual) watch.put(code, w);
                }
            }
            Logx.add("저장된 감시목록 복원: " + watch.size() + "종목" + (sameDay ? "" : " (새 거래일 – 관심종목만 유지)"));
        } catch (Exception e) {
            Logx.add("감시목록 복원 실패: " + e.getMessage(), "warn");
        }
    }

    /** 상단 알림용 한 줄 요약 */
    public String summary() {
        if (!connected) return status;
        synchronized (lock) {
            Ix k = indices.get("001"), q = indices.get("101");
            int sm = scanMin();
            String s = "감시 " + watch.size() + "종목 · 조건 " + active.size() + "개" + (sm > 0 ? "(" + sm + "분)" : "(실시간)");
            if (k != null && k.price > 0) s += " · 코스피 " + pct(k.rate);
            if (q != null && q.price > 0) s += " · 코스닥 " + pct(q.rate);
            Trader tr = trader;
            if (tr != null) s += tr.shortStatus();
            return s;
        }
    }

    public Map<String, Object> snapshot() {
        synchronized (lock) {
            List<Map<String, Object>> rows = new ArrayList<>();
            SimpleDateFormat f = new SimpleDateFormat("HH:mm:ss", Locale.KOREA);
            Set<String> groupNames = new java.util.LinkedHashSet<>(active.values());
            for (Watch w : watch.values()) {
                Object er = (w.entryPrice > 0 && w.price > 0) ? (Object) ((w.price / w.entryPrice - 1) * 100) : null;
                List<Object> gs = new ArrayList<>();
                for (Map.Entry<String, Src> e : w.groups.entrySet()) {
                    Src s = e.getValue();
                    groupNames.add(e.getKey());
                    gs.add(Json.map("name", e.getKey(), "hits", (double) s.hits, "active", s.active,
                            "first", s.first > 0 ? f.format(new Date(s.first)) : "",
                            "last", s.last > 0 ? f.format(new Date(s.last)) : ""));
                }
                Map<String, Object> o = Json.obj(stockAlerts.get(w.code));
                boolean custom = false;
                for (String k : OVR) if (o.get(k) != null && !Json.s(o.get(k)).isEmpty()) custom = true;
                rows.add(Json.map("code", w.code, "name", w.name, "groups", gs, "manual", w.manual,
                        "price", w.price, "rate", w.rate, "entry_price", w.entryPrice, "entry_rate", er,
                        "entered_at", w.enteredAt > 0 ? f.format(new Date(w.enteredAt)) : "",
                        "mute", Json.b(o.get("mute")), "custom", custom, "_t", (double) w.enteredAt));
            }
            Collections.sort(rows, (x, y) -> Double.compare(Json.d(y.get("_t"), 0), Json.d(x.get("_t"), 0)));
            List<Object> conds = new ArrayList<>();
            for (String[] c : conditions) conds.add(java.util.Arrays.asList((Object) c[0], c[1]));
            List<Object> ixs = new ArrayList<>();
            for (Ix ix : indices.values()) {
                Map<String, Object> s = Json.obj(indexAlerts.get(ix.code));
                ixs.add(Json.map("code", ix.code, "name", ix.name, "price", ix.price, "rate", ix.rate,
                        "enabled", !s.containsKey("enabled") || Json.b(s.get("enabled"))));
            }
            return Json.map("running", running, "connected", connected, "status", status, "error", error,
                    "conditions", conds, "active", new LinkedHashMap<String, Object>(active),
                    "groups", new ArrayList<Object>(groupNames),
                    "scan_min", scanMin(),
                    "next_scan_sec", (scanMin() > 0 && nextScan > 0 && connected) ? Math.max(0, (nextScan - System.currentTimeMillis()) / 1000) : -1,
                    "watch", rows, "indices", ixs);
        }
    }
}
