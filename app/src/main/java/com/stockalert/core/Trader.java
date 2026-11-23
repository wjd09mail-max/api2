package com.stockalert.core;

import com.stockalert.core.Store.Logx;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TimeZone;
import java.util.concurrent.LinkedBlockingQueue;

/**
 * 자동매매: 조건식 편입 매수 → 익절/손절/트레일링 매도, 장마감 정리, 지수 연동, 안전장치.
 * 주문: POST /api/dostk/ordr  (kt10000 매수 / kt10001 매도),  잔고: POST /api/dostk/acnt (kt00018)
 */
public final class Trader {
    static final TimeZone KST = TimeZone.getTimeZone("Asia/Seoul");

    static final class Pos {
        String code, name;
        int qty, avail;
        double avg, last, high;
        long buyAt, sellAt;
        boolean bot, sellPending, placeholder;
    }

    private final Store cfg;
    private final Kakao kakao;
    private final Engine eng;
    private final File stateFile;
    private final Object lock = new Object();
    private final Map<String, Pos> pos = new LinkedHashMap<>();
    private final Set<String> owned = new HashSet<>();          // 앱이 산 종목 (팔 때까지 유지)
    private final Set<String> boughtToday = new HashSet<>();
    private final List<Map<String, Object>> orders = new ArrayList<>();
    private int buysToday = 0;
    private double realized = 0;
    private boolean halted = false, closedToday = false, liquidated = false;
    private String haltReason = "", day = "";
    private final double[] ixRate = new double[2];               // 코스피, 코스닥
    private volatile long lastBalance = 0, balanceDue = 0;
    private volatile String balanceErr = "";
    private volatile Map<String, Object> t = new LinkedHashMap<>();
    private final LinkedBlockingQueue<Runnable> q = new LinkedBlockingQueue<>();

    public Trader(Store cfg, Kakao kakao, Engine eng, File stateFile) {
        this.cfg = cfg;
        this.kakao = kakao;
        this.eng = eng;
        this.stateFile = stateFile;
        reload();
        day = today();
        load();
        Thread w = new Thread(() -> {
            while (true) {
                try {
                    q.take().run();
                } catch (InterruptedException e) {
                    return;
                } catch (Exception e) {
                    Logx.add("자동매매 처리 오류: " + e, "error");
                }
            }
        }, "trader");
        w.setDaemon(true);
        w.start();
        Thread tm = new Thread(this::timerLoop, "trader-timer");
        tm.setDaemon(true);
        tm.start();
    }

    // ───────── 설정/유틸 ─────────
    public void reload() {
        t = cfg.copy("trade");
    }

    private double n(String k) {
        return Json.d(t.get(k), 0);
    }

    private boolean b(String k) {
        return Json.b(t.get(k));
    }

    static String today() {
        SimpleDateFormat f = new SimpleDateFormat("yyyyMMdd", Locale.KOREA);
        f.setTimeZone(KST);
        return f.format(new Date());
    }

    static String hhmm() {
        SimpleDateFormat f = new SimpleDateFormat("HH:mm", Locale.KOREA);
        f.setTimeZone(KST);
        return f.format(new Date());
    }

    static String hms() {
        SimpleDateFormat f = new SimpleDateFormat("HH:mm:ss", Locale.KOREA);
        f.setTimeZone(KST);
        return f.format(new Date());
    }

    static boolean weekday() {
        int d = Calendar.getInstance(KST).get(Calendar.DAY_OF_WEEK);
        return d != Calendar.SATURDAY && d != Calendar.SUNDAY;
    }

    private boolean mock() {
        return cfg.bool("kiwoom", "mock");
    }

    /** 주문 가능 여부 (null = 가능, 아니면 사유) */
    private String orderBlock() {
        if (!b("enabled")) return "자동매매 꺼짐";
        if (!eng.connected) return "키움 미연결";
        if (!mock() && !b("real_ok")) return "실전 주문 미허용 (매매 설정에서 '실전 주문 허용' 필요)";
        return null;
    }

    private void checkDay() {
        String d = today();
        if (!d.equals(day)) {
            day = d;
            boughtToday.clear();
            buysToday = 0;
            realized = 0;
            halted = false;
            haltReason = "";
            closedToday = false;
            liquidated = false;
            orders.clear();
            save();
        }
    }

    private void record(String side, String code, String name, int qty, double price, boolean ok, String msg, String reason) {
        Map<String, Object> o = Json.map("t", hms(), "side", side, "code", code, "name", name, "qty", (double) qty,
                "price", price, "ok", ok, "msg", msg, "reason", reason);
        synchronized (lock) {
            orders.add(0, o);
            while (orders.size() > 200) orders.remove(orders.size() - 1);
        }
        save();
    }

    private void halt(String why) {
        synchronized (lock) {
            if (halted) return;
            halted = true;
            haltReason = why;
        }
        Logx.add("자동매수 정지: " + why, "warn");
        kakao.push("⛔ 자동매수 정지\n" + why + "\n(매도 감시는 계속)");
        save();
    }

    // ───────── 매수 ─────────
    private boolean slotBuy(String cond) {
        synchronized (cfg.lock) {
            for (Object o : Json.arr(cfg.data.get("slots"))) {
                Map<String, Object> s = Json.obj(o);
                if (Json.b(s.get("buy")) && Json.b(s.get("enabled")) && cond.equals(Json.s(s.get("name")))) return true;
            }
        }
        return false;
    }

    /** 조건식 편입 신호 (Engine 에서 호출) */
    public void onSignal(final String code, final String cond, final double price, final String name) {
        if (!b("enabled") || !slotBuy(cond)) return;
        q.offer(() -> doBuy(code, cond, price, name));
    }

    private String buyBlock(String code) {
        String ob = orderBlock();
        if (ob != null) return ob;
        checkDay();
        if (halted) return "자동매수 정지 상태 (" + haltReason + ")";
        if (!weekday()) return "주말";
        String now = hhmm(), st = Json.s(t.get("buy_start")), en = Json.s(t.get("buy_end"));
        if (!st.isEmpty() && now.compareTo(st) < 0) return "매수 시작 전 (" + st + ")";
        if (!en.isEmpty() && now.compareTo(en) > 0) return "매수 마감 후 (" + en + ")";
        double ist = n("index_stop");
        if (ist > 0 && (ixRate[0] <= -ist || ixRate[1] <= -ist))
            return String.format(Locale.KOREA, "지수 하락으로 매수 중단 (코스피 %+.2f%%, 코스닥 %+.2f%%)", ixRate[0], ixRate[1]);
        synchronized (lock) {
            if (boughtToday.contains(code)) return "오늘 이미 매수한 종목";
            Pos p = pos.get(code);
            if (p != null && p.qty > 0) return "이미 보유 중";
            if (buysToday >= (int) n("max_buys")) return "하루 최대 매수 횟수(" + (int) n("max_buys") + "회) 도달";
            int held = 0;
            for (Pos x : pos.values()) if (x.qty > 0 && x.bot) held++;
            if (n("max_positions") > 0 && held >= (int) n("max_positions")) return "최대 보유 종목 수(" + (int) n("max_positions") + ") 도달";
        }
        return null;
    }

    private void doBuy(String code, String cond, double price, String name) {
        String why = buyBlock(code);
        String label = (name == null || name.isEmpty() || name.equals(code)) ? code : name + "(" + code + ")";
        if (why != null) {
            Logx.add("매수 건너뜀 " + label + ": " + why, "info");
            return;
        }
        try {
            Engine.Rest rest = eng.rest();
            if (price <= 0) price = (Double) rest.stockInfo(code)[1];
            double amt = n("buy_amount");
            int qty = price > 0 ? (int) Math.floor(amt / price) : 0;
            if (qty < 1) {
                Logx.add("매수 건너뜀 " + label + ": 금액 " + Engine.won(amt) + "원으로 1주도 살 수 없음 (현재가 " + Engine.won(price) + ")", "warn");
                return;
            }
            boolean market = !"limit".equals(Json.s(t.get("order_type")));
            Map<String, Object> body = Json.map("dmst_stex_tp", "KRX", "stk_cd", code, "ord_qty", String.valueOf(qty),
                    "ord_uv", market ? "" : String.valueOf((long) price), "trde_tp", market ? "3" : "0");
            Map<String, Object> r = rest.call("kt10000", "/api/dostk/ordr", body);
            boolean ok = "0".equals(Json.s(r.get("return_code")));
            String msg = Json.s(r.get("return_msg"));
            record("매수", code, name, qty, price, ok, ok ? "주문번호 " + Json.s(r.get("ord_no")) : msg, "[" + cond + "] 편입");
            if (!ok) {
                Logx.add("매수 주문 실패 " + label + ": " + msg, "error");
                kakao.push("❌ 매수 주문 실패\n" + label + "\n" + Engine.clip(msg, 80));
                return;
            }
            synchronized (lock) {
                buysToday++;
                boughtToday.add(code);
                owned.add(code);
                Pos p = pos.get(code);
                if (p == null) {
                    p = new Pos();
                    p.code = code;
                    pos.put(code, p);
                }
                p.name = name;
                p.qty = qty;
                p.avail = qty;
                p.avg = price;
                p.last = price;
                p.high = price;
                p.buyAt = System.currentTimeMillis();
                p.bot = true;
                p.placeholder = true;
            }
            eng.watchHeld(code, name);
            Logx.add("매수 주문 " + label + " " + qty + "주 " + (market ? "시장가" : Engine.won(price) + "원"));
            kakao.push("💰 매수 주문 " + (mock() ? "(모의)" : "(실전)") + "\n" + label + " " + qty + "주 "
                    + (market ? "시장가" : "@" + Engine.won(price)) + "\n≈" + Engine.won(qty * price) + "원 · [" + cond + "] 편입");
            balanceDue = System.currentTimeMillis() + 3000;
            save();
        } catch (Exception e) {
            Logx.add("매수 처리 오류 " + label + ": " + e.getMessage(), "error");
        }
    }

    // ───────── 매도 ─────────
    /** 실시간 체결가 (Engine 에서 호출) */
    public void onTick(String code, double p) {
        String reason = null;
        synchronized (lock) {
            Pos ps = pos.get(code);
            if (ps == null || ps.qty <= 0) return;
            ps.last = p;
            if (p > ps.high) ps.high = p;
            if (!ps.bot || ps.sellPending || ps.avg <= 0 || !b("enabled")) return;
            double pnl = (p / ps.avg - 1) * 100;
            double tp = n("take_profit"), sl = n("stop_loss"), tr = n("trail_pct");
            if (tp > 0 && pnl >= tp) reason = String.format(Locale.KOREA, "익절 %+.2f%%", pnl);
            else if (sl > 0 && pnl <= -sl) reason = String.format(Locale.KOREA, "손절 %+.2f%%", pnl);
            else if (tr > 0 && ps.high > ps.avg && pnl > 0 && (p / ps.high - 1) * 100 <= -tr)
                reason = String.format(Locale.KOREA, "트레일링(고점 %s 대비 -%.1f%%) %+.2f%%", Engine.won(ps.high), tr, pnl);
        }
        if (reason != null) sell(code, reason, false);
    }

    /** 매도 요청. manual=true 면 앱이 안 산 종목도 매도 */
    public Object[] sell(final String code, final String reason, final boolean manual) {
        // 수동 매도는 자동매매가 꺼져 있어도 가능
        if (!eng.connected) return new Object[]{false, "키움 미연결"};
        if (!mock() && !b("real_ok")) return new Object[]{false, "실전 주문 미허용 (매매 설정에서 '실전 주문 허용' 필요)"};
        if (!manual && !b("enabled")) return new Object[]{false, "자동매매 꺼짐"};
        synchronized (lock) {
            Pos p = pos.get(code);
            if (p == null || p.qty <= 0) return new Object[]{false, "보유하지 않은 종목입니다"};
            if (!manual && !p.bot) return new Object[]{false, "앱이 산 종목이 아님"};
            if (p.sellPending && System.currentTimeMillis() - p.sellAt < 30_000) return new Object[]{false, "매도 주문 처리 중"};
            p.sellPending = true;
            p.sellAt = System.currentTimeMillis();
        }
        q.offer(() -> doSell(code, reason));
        return new Object[]{true, "매도 주문 요청"};
    }

    private void doSell(String code, String reason) {
        Pos p;
        int qty;
        String label;
        synchronized (lock) {
            p = pos.get(code);
            if (p == null) return;
            qty = p.avail > 0 ? p.avail : p.qty;
            label = (p.name == null || p.name.isEmpty()) ? code : p.name + "(" + code + ")";
        }
        try {
            Map<String, Object> body = Json.map("dmst_stex_tp", "KRX", "stk_cd", code, "ord_qty", String.valueOf(qty),
                    "ord_uv", "", "trde_tp", "3");
            Map<String, Object> r = eng.rest().call("kt10001", "/api/dostk/ordr", body);
            boolean ok = "0".equals(Json.s(r.get("return_code")));
            String msg = Json.s(r.get("return_msg"));
            double est;
            boolean bot;
            synchronized (lock) {
                est = (p.last - p.avg) * qty;
                bot = p.bot;
            }
            record("매도", code, p.name, qty, p.last, ok, ok ? "주문번호 " + Json.s(r.get("ord_no")) : msg, reason);
            if (!ok) {
                synchronized (lock) {
                    p.sellPending = false;
                    p.sellAt = System.currentTimeMillis() + 30_000;     // 30초 후 재시도 허용
                }
                Logx.add("매도 주문 실패 " + label + ": " + msg, "error");
                kakao.push("❌ 매도 주문 실패\n" + label + " " + qty + "주\n" + Engine.clip(msg, 80));
                return;
            }
            double total;
            synchronized (lock) {
                if (bot) realized += est;          // 직접 산 종목은 자동매매 손익/손실한도에 넣지 않음
                total = realized;
            }
            Logx.add("매도 주문 " + label + " " + qty + "주 시장가 · " + reason);
            kakao.push("💸 매도 주문 " + (mock() ? "(모의)" : "(실전)") + "\n" + label + " " + qty + "주 시장가\n"
                    + reason + " · 예상손익 " + (est >= 0 ? "+" : "") + Engine.won(est) + "원");
            double ml = n("max_loss");
            if (ml > 0 && total <= -ml) halt("일일 손실 한도 도달 (예상 실현손익 " + Engine.won(total) + "원)");
            balanceDue = System.currentTimeMillis() + 3000;
            save();
        } catch (Exception e) {
            synchronized (lock) {
                p.sellPending = false;
            }
            Logx.add("매도 처리 오류 " + label + ": " + e.getMessage(), "error");
        }
    }

    public Object[] sellAll(String reason, boolean manual) {
        List<String> codes = new ArrayList<>();
        boolean botOnly = !manual && b("bot_only");
        synchronized (lock) {
            for (Pos p : pos.values()) if (p.qty > 0 && (!botOnly || p.bot)) codes.add(p.code);
        }
        int n = 0;
        String last = "";
        for (String c : codes) {
            Object[] r = sell(c, reason, manual);
            if ((Boolean) r[0]) n++;
            else last = (String) r[1];
        }
        return new Object[]{n > 0 || codes.isEmpty(), codes.isEmpty() ? "보유 종목이 없습니다" : n + "종목 매도 주문" + (last.isEmpty() ? "" : " (일부 실패: " + last + ")")};
    }

    // ───────── 지수 연동 ─────────
    public void onIndex(String code, double rate) {
        int i = "001".equals(code) ? 0 : ("101".equals(code) ? 1 : -1);
        if (i < 0) return;
        ixRate[i] = rate;
        double isell = n("index_sell");
        if (isell > 0 && rate <= -isell && b("enabled")) {
            boolean doIt;
            synchronized (lock) {
                doIt = !liquidated;
                liquidated = true;
            }
            if (doIt) {
                String nm = i == 0 ? "코스피" : "코스닥";
                String why = String.format(Locale.KOREA, "%s %+.2f%% 급락", nm, rate);
                Logx.add("지수 급락 – 전량 매도: " + why, "warn");
                sellAll("지수 급락 전량매도 (" + why + ")", false);
                halt(why);
            }
        }
    }

    // ───────── 타이머: 잔고 동기화, 장마감 정리 ─────────
    private void timerLoop() {
        while (true) {
            try {
                Thread.sleep(1000);
                checkDay();
                if (!eng.connected) continue;
                long now = System.currentTimeMillis();
                boolean active;
                synchronized (lock) {
                    active = b("enabled") || !pos.isEmpty();
                }
                if (active && (now - lastBalance > 20_000 || (balanceDue > 0 && now >= balanceDue))) {
                    balanceDue = 0;
                    lastBalance = now;
                    q.offer(this::refreshBalance);
                }
                String ct = Json.s(t.get("close_time"));
                if (b("enabled") && weekday() && !ct.isEmpty() && hhmm().compareTo(ct) >= 0 && hhmm().compareTo("15:30") < 0) {
                    boolean doIt;
                    synchronized (lock) {
                        doIt = !closedToday;
                        closedToday = true;
                    }
                    if (doIt) {
                        Logx.add("장마감 정리 시각(" + ct + ") – 보유 종목 매도");
                        Object[] r = sellAll("장마감 정리 (" + ct + ")", false);
                        if (!"보유 종목이 없습니다".equals(r[1])) kakao.push("🕒 장마감 정리\n" + r[1]);
                        save();
                    }
                }
            } catch (InterruptedException e) {
                return;
            } catch (Exception e) {
                Logx.add("자동매매 타이머 오류: " + e.getMessage(), "warn");
            }
        }
    }

    public void refreshNow() {
        q.offer(this::refreshBalance);
    }

    private void refreshBalance() {
        try {
            Engine.Rest rest = eng.rest();
            if (rest == null) return;
            Map<String, Object> r = rest.call("kt00018", "/api/dostk/acnt", Json.map("qry_tp", "1", "dmst_stex_tp", "KRX"));
            if (!"0".equals(Json.s(r.get("return_code")))) {
                balanceErr = Json.s(r.get("return_msg"));
                Logx.add("잔고 조회 실패: " + balanceErr, "warn");
                return;
            }
            balanceErr = "";
            Set<String> seen = new HashSet<>();
            List<String[]> newHeld = new ArrayList<>();
            synchronized (lock) {
                for (Object o : Json.arr(r.get("acnt_evlt_remn_indv_tot"))) {
                    Map<String, Object> m = Json.obj(o);
                    String code = Engine.cleanCode(m.get("stk_cd"));
                    int qty = (int) Json.d(m.get("rmnd_qty"), 0);
                    if (code.isEmpty() || qty <= 0) continue;
                    seen.add(code);
                    Pos p = pos.get(code);
                    if (p == null) {
                        p = new Pos();
                        p.code = code;
                        pos.put(code, p);
                        newHeld.add(new String[]{code, Json.s(m.get("stk_nm")).trim()});
                    }
                    p.name = Json.s(m.get("stk_nm")).trim();
                    p.qty = qty;
                    p.avail = (int) Json.d(m.get("trde_able_qty"), qty);
                    double avg = Math.abs(Json.d(m.get("pur_pric"), 0));
                    if (avg > 0) p.avg = avg;
                    double cur = Math.abs(Json.d(m.get("cur_prc"), 0));
                    if (cur > 0) p.last = cur;
                    if (p.high < p.last) p.high = p.last;
                    p.bot = owned.contains(code) || !b("bot_only");
                    p.placeholder = false;
                    if (p.sellPending && System.currentTimeMillis() - p.sellAt > 60_000) p.sellPending = false;
                }
                long now = System.currentTimeMillis();
                for (Pos p : new ArrayList<>(pos.values())) {
                    if (seen.contains(p.code)) continue;
                    if (p.placeholder && !p.sellPending && now - p.buyAt < 90_000) continue;     // 방금 낸 매수: 체결 대기
                    pos.remove(p.code);
                    owned.remove(p.code);                                       // 다 팔림
                }
            }
            for (String[] h : newHeld) eng.watchHeld(h[0], h[1]);
            save();
        } catch (Exception e) {
            balanceErr = String.valueOf(e.getMessage());
            Logx.add("잔고 조회 오류: " + e.getMessage(), "warn");
        }
    }

    public Object[] resume() {
        synchronized (lock) {
            halted = false;
            haltReason = "";
            liquidated = false;
        }
        save();
        return new Object[]{true, "자동매수 재개"};
    }

    // ───────── 저장/화면 ─────────
    private void save() {
        if (stateFile == null) return;
        Map<String, Object> st;
        synchronized (lock) {
            st = Json.map("day", day, "owned", new ArrayList<Object>(owned), "bought_today", new ArrayList<Object>(boughtToday),
                    "buys_today", (double) buysToday, "realized", realized, "halted", halted, "halt_reason", haltReason,
                    "closed_today", closedToday, "liquidated", liquidated, "orders", new ArrayList<Object>(orders));
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
            Logx.add("매매 상태 저장 실패: " + e.getMessage(), "warn");
        }
    }

    private void load() {
        if (stateFile == null || !stateFile.exists()) return;
        try {
            Map<String, Object> st = Json.obj(Json.parse(Net.readAll(new FileInputStream(stateFile))));
            synchronized (lock) {
                for (Object o : Json.arr(st.get("owned"))) owned.add(Json.s(o));
                if (day.equals(Json.s(st.get("day")))) {
                    for (Object o : Json.arr(st.get("bought_today"))) boughtToday.add(Json.s(o));
                    buysToday = (int) Json.d(st.get("buys_today"), 0);
                    realized = Json.d(st.get("realized"), 0);
                    halted = Json.b(st.get("halted"));
                    haltReason = Json.s(st.get("halt_reason"));
                    closedToday = Json.b(st.get("closed_today"));
                    liquidated = Json.b(st.get("liquidated"));
                    for (Object o : Json.arr(st.get("orders"))) orders.add(Json.obj(o));
                }
            }
        } catch (Exception e) {
            Logx.add("매매 상태 복원 실패: " + e.getMessage(), "warn");
        }
    }

    public Map<String, Object> snapshot() {
        synchronized (lock) {
            List<Object> ps = new ArrayList<>();
            double evalPl = 0;
            for (Pos p : pos.values()) {
                if (p.qty <= 0) continue;
                double pnl = p.avg > 0 && p.last > 0 ? (p.last / p.avg - 1) * 100 : 0;
                evalPl += (p.last - p.avg) * p.qty;
                ps.add(Json.map("code", p.code, "name", p.name == null ? p.code : p.name, "qty", (double) p.qty,
                        "avail", (double) p.avail, "avg", p.avg, "last", p.last, "pnl", pnl, "bot", p.bot,
                        "pending", p.sellPending, "wait", p.placeholder));
            }
            String block = orderBlock();
            return Json.map("enabled", b("enabled"), "mock", mock(), "real_ok", b("real_ok"),
                    "block", block == null ? "" : block, "halted", halted, "halt_reason", haltReason,
                    "buys_today", (double) buysToday, "max_buys", n("max_buys"), "realized", realized, "eval_pl", evalPl,
                    "positions", ps, "orders", new ArrayList<Object>(orders.subList(0, Math.min(60, orders.size()))),
                    "balance_err", balanceErr);
        }
    }

    public String shortStatus() {
        if (!b("enabled")) return "";
        synchronized (lock) {
            int held = 0;
            for (Pos p : pos.values()) if (p.qty > 0 && p.bot) held++;
            return " · 💰" + (halted ? "정지" : "ON") + " " + held + "종목";
        }
    }
}
