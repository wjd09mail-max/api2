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
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TimeZone;
import java.util.concurrent.LinkedBlockingQueue;

/**
 * 매매: 감시 종목 수동 매수/매도, 조건식 편입 자동매수, 종목별 자동매도(익절/손절/트레일링/목표가),
 * 장마감 정리, 지수 연동, 안전장치.
 * 주문: POST /api/dostk/ordr (kt10000 매수 / kt10001 매도), 잔고: POST /api/dostk/acnt (kt00018)
 */
public final class Trader {
    static final TimeZone KST = TimeZone.getTimeZone("Asia/Seoul");
    static final String[] POS_KEYS = {"take_profit", "stop_loss", "trail_pct", "tp_price", "sl_price", "bear_min", "bear_cnt", "sl_confirm", "tp_bear_min"};

    static final class Pos {
        String code, name;
        int qty, avail;
        double avg, last, high;
        long buyAt, sellAt;
        boolean bot, sellPending, placeholder;
        long slWaitAt;          // 손절 1분 확인 대기 시작 시각 (0=대기 아님)
        double slLevel;         // 확인할 손절 기준가
        String slWhy = "";
        long tpArmedAt;         // 익절가 도달 → 음봉 기다리는 중 (0=아님)
        String tpWhy = "";
    }

    private final Store cfg;
    private final Kakao kakao;
    private final Engine eng;
    private final File stateFile;
    private final Object lock = new Object();
    private final Map<String, Pos> pos = new LinkedHashMap<>();
    private final Set<String> owned = new HashSet<>();                       // 앱으로 산 종목
    private final Map<String, Map<String, Object>> posCfg = new LinkedHashMap<>();   // 종목별 매도 설정
    private final Set<String> boughtToday = new HashSet<>();
    private final List<Map<String, Object>> orders = new ArrayList<>();
    private int buysToday = 0;
    private double realized = 0;
    private boolean halted = false, closedToday = false, liquidated = false;
    private String haltReason = "", day = "";
    private final double[] ixRate = new double[2];
    private final double[] ixPrice = new double[2];
    private final java.util.ArrayDeque<double[]>[] ixHist = newHist();
    private final Map<String, String> ruleFired = new LinkedHashMap<>();       // 규칙 id → 실행한 날짜
    private final Map<String, Map<String, Object>> plans = new LinkedHashMap<>();   // 종목별 매수 예약
    private final Map<String, Long> candlePolled = new HashMap<>();                 // "code|분" → 마지막 조회한 봉 경계
    private final Map<String, Long> planWarned = new HashMap<>();
    private final Set<String> planBusy = new HashSet<>();

    @SuppressWarnings("unchecked")
    private static java.util.ArrayDeque<double[]>[] newHist() {
        return new java.util.ArrayDeque[]{new java.util.ArrayDeque<double[]>(), new java.util.ArrayDeque<double[]>()};
    }
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
                    Logx.add("매매 처리 오류: " + e, "error");
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

    static String fmt(String p) {
        SimpleDateFormat f = new SimpleDateFormat(p, Locale.KOREA);
        f.setTimeZone(KST);
        return f.format(new Date());
    }

    static String today() {
        return fmt("yyyyMMdd");
    }

    static String hhmm() {
        return fmt("HH:mm");
    }

    static String hms() {
        return fmt("HH:mm:ss");
    }

    static boolean weekday() {
        int d = Calendar.getInstance(KST).get(Calendar.DAY_OF_WEEK);
        return d != Calendar.SATURDAY && d != Calendar.SUNDAY;
    }

    private boolean mock() {
        return cfg.bool("kiwoom", "mock");
    }

    /** 주문을 보낼 수 있는지 (null = 가능) */
    private String orderBlock() {
        if (!eng.connected) return "키움 미연결 (▶시작 필요)";
        if (!mock() && !b("real_ok")) return "실전 주문 미허용 (매매 탭 설정에서 '실전 주문 허용' 필요)";
        return null;
    }

    private void checkDay() {
        String d = today();
        if (!d.equals(day)) {
            synchronized (lock) {
                day = d;
                boughtToday.clear();
                buysToday = 0;
                realized = 0;
                halted = false;
                haltReason = "";
                closedToday = false;
                liquidated = false;
                orders.clear();
            }
            save();
        }
    }

    private void record(String side, String code, String name, int qty, double price, boolean ok, String msg, String reason) {
        Map<String, Object> o = Json.map("t", hms(), "side", side, "code", code, "name", name == null ? code : name,
                "qty", (double) qty, "price", price, "ok", ok, "msg", msg, "reason", reason);
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
        Logx.add("조건식 자동매수 정지: " + why, "warn");
        kakao.push("⛔ 자동매수 정지\n" + why + "\n(종목별 자동매도는 계속)");
        save();
    }

    static String label(String code, String name) {
        return (name == null || name.isEmpty() || name.equals(code)) ? code : name + "(" + code + ")";
    }

    /** 종목별 자동매도 사용 여부 (lock 보유 상태) */
    private boolean autoOn(Pos p) {
        Map<String, Object> c = posCfg.get(p.code);
        if (c != null && c.containsKey("auto")) return Json.b(c.get("auto"));
        return owned.contains(p.code) || !b("bot_only");
    }

    /** 종목별 적용 값 (개별 설정이 없으면 매매 탭 기본값) */
    private double effv(String code, String key) {
        Map<String, Object> c = posCfg.get(code);
        if (c != null && c.get(key) != null && !Json.s(c.get(key)).isEmpty()) return Json.d(c.get(key), 0);
        if ("tp_price".equals(key) || "sl_price".equals(key)) return 0;
        return n(key);
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

    /** 조건식 편입 신호 → 자동매수 (조건식 자동매수 ON 일 때만) */
    public void onSignal(final String code, final String cond, final double price, final String name) {
        if (!b("enabled") || !slotBuy(cond)) return;
        final String mode = Json.s(t.get("auto_mode"));
        if (!mode.isEmpty() && !"now".equals(mode)) {
            q.offer(() -> autoPlan(code, cond, price, name, mode));
            return;
        }
        q.offer(() -> {
            String why = autoBuyBlock(code);
            if (why != null) {
                Logx.add("자동매수 건너뜀 " + label(code, name) + ": " + why);
                return;
            }
            placeBuy(code, name, price, n("buy_amount"), 0, !"limit".equals(Json.s(t.get("order_type"))), 0, true,
                    "[" + cond + "] 편입", true);
        });
    }

    private String autoBuyBlock(String code) {
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
            for (Pos x : pos.values()) if (x.qty > 0 && owned.contains(x.code)) held++;
            if (n("max_positions") > 0 && held >= (int) n("max_positions")) return "최대 보유 종목 수(" + (int) n("max_positions") + ") 도달";
        }
        return null;
    }

    /** 감시 탭에서 수동 매수 */
    public Object[] manualBuy(final String code, final String name, final double amount, final int qty,
                              final boolean market, final double limitPrice, final boolean manage) {
        String ob = orderBlock();
        if (ob != null) return new Object[]{false, ob};
        if (amount <= 0 && qty <= 0) return new Object[]{false, "매수 금액 또는 수량을 입력하세요"};
        if (!market && limitPrice <= 0) return new Object[]{false, "지정가 가격을 입력하세요"};
        q.offer(() -> placeBuy(code, name, market ? 0 : limitPrice, amount, qty, market, limitPrice, manage, "수동 매수", false));
        return new Object[]{true, "매수 주문 요청"};
    }

    private void placeBuy(String code, String name, double price, double amount, int qty, boolean market,
                          double limitPrice, boolean manage, String reason, boolean auto) {
        String lb = label(code, name);
        try {
            Engine.Rest rest = eng.rest();
            if (rest == null) throw new Exception("키움 미연결");
            double ref = !market && limitPrice > 0 ? limitPrice : price;
            if (ref <= 0) {
                double cached = eng.priceOf(code);
                ref = cached > 0 ? cached : (Double) rest.stockInfo(code)[1];
            }
            if (qty <= 0) qty = ref > 0 ? (int) Math.floor(amount / ref) : 0;
            if (qty < 1) {
                String m = "금액 " + Engine.won(amount) + "원으로 1주도 살 수 없음 (현재가 " + Engine.won(ref) + ")";
                Logx.add("매수 건너뜀 " + lb + ": " + m, "warn");
                if (!auto) kakao.push("❌ 매수 불가\n" + lb + "\n" + m);
                return;
            }
            Map<String, Object> body = Json.map("dmst_stex_tp", "KRX", "stk_cd", code, "ord_qty", String.valueOf(qty),
                    "ord_uv", market ? "" : String.valueOf((long) limitPrice), "trde_tp", market ? "3" : "0");
            Map<String, Object> r = rest.call("kt10000", "/api/dostk/ordr", body);
            boolean ok = "0".equals(Json.s(r.get("return_code")));
            String msg = Json.s(r.get("return_msg"));
            record("매수", code, name, qty, ref, ok, ok ? "주문번호 " + Json.s(r.get("ord_no")) : msg, reason);
            if (!ok) {
                Logx.add("매수 주문 실패 " + lb + ": " + msg, "error");
                kakao.push("❌ 매수 주문 실패\n" + lb + "\n" + Engine.clip(msg, 80));
                return;
            }
            synchronized (lock) {
                if (auto) {
                    buysToday++;
                    boughtToday.add(code);
                }
                if (manage) owned.add(code);
                Map<String, Object> c = posCfg.get(code);
                if (!manage) {
                    if (c == null) posCfg.put(code, c = new LinkedHashMap<>());
                    c.put("auto", false);
                }
                Pos p = pos.get(code);
                if (p == null || p.qty <= 0) {
                    p = new Pos();
                    p.code = code;
                    pos.put(code, p);
                    p.qty = qty;
                    p.avail = qty;
                    p.avg = ref;
                    p.high = ref;
                    p.buyAt = System.currentTimeMillis();
                    p.placeholder = true;
                } else {                                   // 추가 매수: 평단 대략 갱신 (잔고 조회 시 정확히 맞춰짐)
                    p.avg = (p.avg * p.qty + ref * qty) / (p.qty + qty);
                    p.qty += qty;
                    p.avail += qty;
                }
                p.name = name;
                p.last = ref;
                p.bot = owned.contains(code);
            }
            eng.watchHeld(code, name);
            Logx.add("매수 주문 " + lb + " " + qty + "주 " + (market ? "시장가" : Engine.won(limitPrice) + "원") + " · " + reason);
            kakao.push("💰 매수 주문 " + (mock() ? "(모의)" : "(실전)") + "\n" + lb + " " + qty + "주 "
                    + (market ? "시장가" : "@" + Engine.won(limitPrice)) + "\n≈" + Engine.won(qty * ref) + "원 · " + reason);
            balanceDue = System.currentTimeMillis() + 3000;
            save();
        } catch (Exception e) {
            Logx.add("매수 처리 오류 " + lb + ": " + e.getMessage(), "error");
            if (!auto) kakao.push("❌ 매수 오류\n" + lb + "\n" + Engine.clip(String.valueOf(e.getMessage()), 80));
        }
    }

    // ───────── 매도 ─────────
    /** 실시간 체결가 → 종목별 자동매도 판단 */
    public void onTick(String code, double p, double rate) {
        onTick(code, p);
        checkDip(code, p, rate, true);
    }

    public void onTick(String code, double p) {
        String reason = null, notice = null;
        synchronized (lock) {
            Pos ps = pos.get(code);
            if (ps == null || ps.qty <= 0) return;
            ps.last = p;
            if (p > ps.high) ps.high = p;
            if (ps.sellPending || ps.avg <= 0 || !autoOn(ps)) return;
            double pnl = (p / ps.avg - 1) * 100;
            double tp = effv(code, "take_profit"), sl = effv(code, "stop_loss"), tr = effv(code, "trail_pct");
            double tpp = effv(code, "tp_price"), slp = effv(code, "sl_price");
            String pct = String.format(Locale.KOREA, "%+.2f%%", pnl);
            String tpHit = null, slHit = null;
            double slLv = 0;
            if (tpp > 0 && p >= tpp) tpHit = "익절가 " + Engine.won(tpp) + " 도달 " + pct;
            else if (tp > 0 && pnl >= tp) tpHit = "익절 " + pct;
            if (slp > 0 && p <= slp) {
                slHit = "손절가 " + Engine.won(slp) + " 도달 " + pct;
                slLv = slp;
            } else if (sl > 0 && pnl <= -sl) {
                slHit = "손절 " + pct;
                slLv = Math.floor(ps.avg * (1 - sl / 100));
            }
            int tbm = (int) effv(code, "tp_bear_min");
            if (tpHit != null && ps.tpArmedAt == 0 && tbm > 0 && Candles.validScope(tbm)) {
                // 익절가 도달 → 바로 팔지 않고 n분봉 음봉이 나올 때 매도
                ps.tpArmedAt = System.currentTimeMillis();
                ps.tpWhy = tpHit;
                notice = "🎯 익절 조건 도달 → " + tbm + "분봉 음봉 나오면 매도\n" + label(code, ps.name) + "\n" + tpHit;
            } else if (tpHit != null && tbm <= 0) {
                reason = tpHit;
            } else if (slHit != null) {
                if (effv(code, "sl_confirm") > 0) {
                    if (ps.slWaitAt == 0) {               // 1분 뒤 다시 확인
                        ps.slWaitAt = System.currentTimeMillis();
                        ps.slLevel = slLv;
                        ps.slWhy = slHit;
                        notice = "⏳ 손절 조건 도달 → 1분 후 확인\n" + label(code, ps.name) + "\n" + slHit + " (기준 " + Engine.won(slLv) + "원)";
                    }
                } else {
                    reason = slHit;
                }
            } else if (tr > 0 && ps.high > ps.avg && pnl > 0 && (p / ps.high - 1) * 100 <= -tr) {
                reason = String.format(Locale.KOREA, "트레일링(고점 %s 대비 -%.1f%%) %+.2f%%", Engine.won(ps.high), tr, pnl);
            }
        }
        if (notice != null) {
            Logx.add(notice.replace("\n", " · "));
            kakao.push(notice);
        }
        if (reason != null) sell(code, 0, reason, false);
    }

    /** 매도 요청. qty=0 이면 전량. manual=false 는 자동매도(종목 자동매도 ON 필요) */
    public Object[] sell(final String code, final int qty, final String reason, final boolean manual) {
        String ob = orderBlock();
        if (ob != null) return new Object[]{false, ob};
        synchronized (lock) {
            Pos p = pos.get(code);
            if (p == null || p.qty <= 0) return new Object[]{false, "보유하지 않은 종목입니다"};
            if (!manual && !autoOn(p)) return new Object[]{false, "자동매도 꺼진 종목"};
            if (p.sellPending && System.currentTimeMillis() - p.sellAt < 30_000) return new Object[]{false, "매도 주문 처리 중"};
            if (qty > Math.max(p.avail, p.qty)) return new Object[]{false, "매도 가능 수량(" + Math.max(p.avail, p.qty) + "주) 초과"};
            p.sellPending = true;
            p.sellAt = System.currentTimeMillis();
        }
        q.offer(() -> doSell(code, qty, reason));
        return new Object[]{true, "매도 주문 요청"};
    }

    private void doSell(String code, int want, String reason) {
        Pos p;
        int qty;
        String lb;
        synchronized (lock) {
            p = pos.get(code);
            if (p == null) return;
            int all = p.avail > 0 ? p.avail : p.qty;
            qty = want > 0 ? Math.min(want, all) : all;
            lb = label(code, p.name);
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
                bot = owned.contains(code);
            }
            record("매도", code, p.name, qty, p.last, ok, ok ? "주문번호 " + Json.s(r.get("ord_no")) : msg, reason);
            if (!ok) {
                synchronized (lock) {
                    p.sellPending = false;
                    p.sellAt = System.currentTimeMillis() + 30_000;
                }
                Logx.add("매도 주문 실패 " + lb + ": " + msg, "error");
                kakao.push("❌ 매도 주문 실패\n" + lb + " " + qty + "주\n" + Engine.clip(msg, 80));
                return;
            }
            double total;
            synchronized (lock) {
                if (bot) realized += est;
                total = realized;
                if (qty < p.qty) {                       // 부분 매도: 나머지는 계속 관리
                    p.qty -= qty;
                    p.avail = Math.max(0, p.avail - qty);
                    p.sellPending = false;
                }
            }
            Logx.add("매도 주문 " + lb + " " + qty + "주 시장가 · " + reason);
            kakao.push("💸 매도 주문 " + (mock() ? "(모의)" : "(실전)") + "\n" + lb + " " + qty + "주 시장가\n"
                    + reason + " · 예상손익 " + (est >= 0 ? "+" : "") + Engine.won(est) + "원");
            double ml = n("max_loss");
            if (ml > 0 && total <= -ml) halt("일일 손실 한도 도달 (예상 실현손익 " + Engine.won(total) + "원)");
            balanceDue = System.currentTimeMillis() + 3000;
            save();
        } catch (Exception e) {
            synchronized (lock) {
                p.sellPending = false;
            }
            Logx.add("매도 처리 오류 " + lb + ": " + e.getMessage(), "error");
        }
    }

    /** manual=true: 보유 전체 / false: 자동매도 켜진 종목만 */
    public Object[] sellAll(String reason, boolean manual) {
        List<String> codes = new ArrayList<>();
        synchronized (lock) {
            for (Pos p : pos.values()) if (p.qty > 0 && (manual || autoOn(p))) codes.add(p.code);
        }
        int n = 0;
        String last = "";
        for (String c : codes) {
            Object[] r = sell(c, 0, reason, manual);
            if ((Boolean) r[0]) n++;
            else last = (String) r[1];
        }
        return new Object[]{n > 0 || codes.isEmpty(), codes.isEmpty() ? "매도할 보유 종목이 없습니다"
                : n + "종목 매도 주문" + (last.isEmpty() ? "" : " (일부 실패: " + last + ")")};
    }

    // ───────── 종목별 매도 설정 ─────────
    public Object[] setPos(String code, Map<String, Object> in, boolean reset) {
        synchronized (lock) {
            if (reset) {
                posCfg.remove(code);
            } else {
                Map<String, Object> c = new LinkedHashMap<>();
                for (String k : POS_KEYS) {
                    String v = in.get(k) == null ? "" : Json.s(in.get(k)).replace(",", "").trim();
                    if (v.isEmpty()) continue;
                    double d = Json.d(v, -1);
                    if (d < 0) return new Object[]{false, "'" + k + "' 값이 올바르지 않습니다"};
                    c.put(k, d);
                }
                if (in.containsKey("auto")) c.put("auto", Json.b(in.get("auto")));
                if (c.containsKey("bear_min")) {
                    Map<String, Object> old = posCfg.get(code);
                    boolean same = old != null && Json.d(old.get("bear_min"), -1) == Json.d(c.get("bear_min"), -2)
                            && Json.d(old.get("bear_cnt"), -1) == Json.d(c.get("bear_cnt"), -1) && old.get("bear_at") != null;
                    c.put("bear_at", same ? old.get("bear_at") : (Object) (double) System.currentTimeMillis());
                }
                double tbv = Json.d(c.get("tp_bear_min"), 0);
                if (tbv > 0 && !Candles.validScope((int) tbv)) return new Object[]{false, "익절 후 음봉 분봉은 1/3/5/10/15/30/45/60 중 하나"};
                if (c.containsKey("sl_confirm")) c.put("sl_confirm", Json.d(c.get("sl_confirm"), 0) > 0 ? 1.0 : 0.0);
                double bmv = Json.d(c.get("bear_min"), 0);
                if (bmv > 0 && !Candles.validScope((int) bmv)) return new Object[]{false, "음봉 매도 분봉은 1/3/5/10/15/30/45/60 중 하나"};
                if (c.containsKey("bear_cnt") && (Json.d(c.get("bear_cnt"), 0) < 1 || Json.d(c.get("bear_cnt"), 0) > 10)) return new Object[]{false, "음봉 개수는 1~10"};
                Pos cur = pos.get(code);
                if (cur != null && cur.avg > 0) {
                    double tpp = Json.d(c.get("tp_price"), 0), slp = Json.d(c.get("sl_price"), 0);
                    if (tpp > 0 && tpp <= cur.avg)
                        return new Object[]{false, "익절가(" + Engine.won(tpp) + ")가 평단(" + Engine.won(cur.avg) + ") 이하입니다. 손실 정리는 손절가를 쓰세요"};
                    if (slp > 0 && cur.last > 0 && slp >= cur.last)
                        return new Object[]{false, "손절가(" + Engine.won(slp) + ")가 현재가(" + Engine.won(cur.last) + ") 이상이라 즉시 매도됩니다"};
                }
                posCfg.put(code, c);
            }
            Pos p = pos.get(code);
            if (p != null) p.high = Math.max(p.last, p.avg);     // 트레일링 기준 재설정
        }
        save();
        return new Object[]{true, label(code, nameOf(code)) + " 매도 설정 저장됨"};
    }

    private String nameOf(String code) {
        synchronized (lock) {
            Pos p = pos.get(code);
            if (p != null && p.name != null) return p.name;
        }
        return eng.nameOf(code);
    }

    // ───────── 지수 연동 ─────────
    static String ixName(String code) {
        return "001".equals(code) ? "코스피" : ("101".equals(code) ? "코스닥" : code);
    }

    /** 지수 실시간 (Engine 에서 호출) */
    public void onIndex(String code, double price, double rate) {
        int i = "001".equals(code) ? 0 : ("101".equals(code) ? 1 : -1);
        if (i < 0 || price <= 0) return;
        ixRate[i] = rate;
        ixPrice[i] = price;
        // 급등락 계산용 1초 버킷 (최대 60분)
        long now = System.currentTimeMillis();
        java.util.ArrayDeque<double[]> h = ixHist[i];
        synchronized (h) {
            double[] lb = h.peekLast();
            if (lb == null || now - lb[0] >= 1000) h.addLast(new double[]{now, price, price});
            else {
                lb[1] = Math.min(lb[1], price);
                lb[2] = Math.max(lb[2], price);
            }
            while (!h.isEmpty() && now - h.peekFirst()[0] > 3_600_000) h.pollFirst();
        }
        // 기본 안전장치: 지수 급락 시 자동매도 종목 전량 매도
        double isell = n("index_sell");
        if (isell > 0 && rate <= -isell) {
            boolean doIt;
            synchronized (lock) {
                doIt = !liquidated;
                liquidated = true;
            }
            if (doIt) {
                String why = String.format(Locale.KOREA, "%s %+.2f%% 급락", ixName(code), rate);
                Logx.add("지수 급락 – 자동매도 종목 전량 매도: " + why, "warn");
                sellAll("지수 급락 전량매도 (" + why + ")", false);
                halt(why);
            }
        }
        checkRules(code, i, price, rate);
    }

    /** 최근 minutes 분 동안 [저점 대비 상승%, 고점 대비 하락%] */
    private double[] ixMove(int i, double price, double minutes) {
        long now = System.currentTimeMillis();
        double lo = Double.MAX_VALUE, hi = 0;
        java.util.ArrayDeque<double[]> h = ixHist[i];
        synchronized (h) {
            for (double[] b : h) {
                if (now - b[0] > minutes * 60_000) continue;
                lo = Math.min(lo, b[1]);
                hi = Math.max(hi, b[2]);
            }
        }
        if (hi <= 0) return new double[]{0, 0};
        return new double[]{(price / lo - 1) * 100, (price / hi - 1) * 100};
    }

    static String condText(Map<String, Object> r) {
        String c = Json.s(r.get("cond"));
        double v = Json.d(r.get("value"), 0);
        String vs = Engine.g(v);
        switch (c) {
            case "rate_le": return "등락률 −" + vs.replace("-", "") + "% 이하";
            case "rate_ge": return "등락률 +" + vs + "% 이상";
            case "price_le": return "지수 " + vs + " 이하";
            case "price_ge": return "지수 " + vs + " 이상";
            case "surge_up": return Engine.g(Json.d(r.get("minutes"), 5)) + "분 내 +" + vs + "% 급등";
            case "surge_dn": return Engine.g(Json.d(r.get("minutes"), 5)) + "분 내 −" + vs + "% 급락";
            default: return c;
        }
    }

    static String actText(Map<String, Object> r) {
        String a = Json.s(r.get("action"));
        String scope = "all".equals(Json.s(r.get("scope"))) ? "보유 전체" : "자동매도 종목";
        switch (a) {
            case "sell_all": return scope + " 전량 매도";
            case "sell_pct": return scope + " " + Engine.g(Json.d(r.get("pct"), 50)) + "% 매도";
            case "buy": return Json.s(r.get("name")).isEmpty() ? Json.s(r.get("code")) + " " + Engine.won(Json.d(r.get("amount"), 0)) + "원 매수"
                    : Json.s(r.get("name")) + "(" + Json.s(r.get("code")) + ") " + Engine.won(Json.d(r.get("amount"), 0)) + "원 매수";
            case "halt_buy": return "조건식 자동매수 중지";
            case "resume_buy": return "조건식 자동매수 재개";
            default: return a;
        }
    }

    private static final boolean ANYTIME = System.getenv("STOCK_ALERT_ANYTIME") != null;   // 테스트 전용

    private void checkRules(String code, int i, double price, double rate) {
        if (!ANYTIME) {
            if (!weekday()) return;
            String now = hhmm();
            if (now.compareTo("09:00") < 0 || now.compareTo("15:20") > 0) return;
        }
        List<Object> rules = Json.arr(t.get("index_rules"));
        String d = today();
        for (Object o : rules) {
            Map<String, Object> r = Json.obj(o);
            if (!Json.b(r.get("enabled")) || !code.equals(Json.s(r.get("index")))) continue;
            String id = Json.s(r.get("id"));
            synchronized (lock) {
                if (d.equals(ruleFired.get(id))) continue;           // 하루 1회
            }
            String c = Json.s(r.get("cond"));
            double v = Json.d(r.get("value"), 0);
            boolean hit;
            switch (c) {
                case "rate_le": hit = rate <= -Math.abs(v); break;
                case "rate_ge": hit = rate >= Math.abs(v); break;
                case "price_le": hit = v > 0 && price <= v; break;
                case "price_ge": hit = v > 0 && price >= v; break;
                case "surge_up": hit = v > 0 && ixMove(i, price, Json.d(r.get("minutes"), 5))[0] >= v; break;
                case "surge_dn": hit = v > 0 && ixMove(i, price, Json.d(r.get("minutes"), 5))[1] <= -v; break;
                default: hit = false;
            }
            if (!hit) continue;
            synchronized (lock) {
                ruleFired.put(id, d);
            }
            save();
            runRule(r, String.format(Locale.KOREA, "%s %s (현재 %,.2f, %+.2f%%)", ixName(code), condText(r), price, rate));
        }
    }

    private void runRule(Map<String, Object> r, String why) {
        String a = Json.s(r.get("action"));
        String reason = "지수 규칙: " + why;
        String result;
        String ob = orderBlock();
        boolean all = "all".equals(Json.s(r.get("scope")));
        switch (a) {
            case "sell_all": {
                result = ob != null ? "주문 불가: " + ob : (String) sellAll(reason, all)[1];
                break;
            }
            case "sell_pct": {
                if (ob != null) {
                    result = "주문 불가: " + ob;
                    break;
                }
                double pct = Math.max(1, Math.min(100, Json.d(r.get("pct"), 50)));
                List<String[]> jobs = new ArrayList<>();
                synchronized (lock) {
                    for (Pos p : pos.values()) {
                        if (p.qty <= 0 || (!all && !autoOn(p))) continue;
                        int q = Math.max(1, (int) Math.floor(p.qty * pct / 100.0));
                        jobs.add(new String[]{p.code, String.valueOf(Math.min(q, p.qty))});
                    }
                }
                int n = 0;
                for (String[] j : jobs) if ((Boolean) sell(j[0], Integer.parseInt(j[1]), reason, true)[0]) n++;
                result = jobs.isEmpty() ? "매도할 보유 종목이 없습니다" : n + "종목 " + Engine.g(pct) + "% 매도 주문";
                break;
            }
            case "buy": {
                final String code = Engine.cleanCode(r.get("code"));
                final double amt = Json.d(r.get("amount"), 0);
                if (ob != null) result = "주문 불가: " + ob;
                else if (halted) result = "자동매수 정지 상태라 매수 안 함 (" + haltReason + ")";
                else {
                    final String nm = Json.s(r.get("name")).isEmpty() ? eng.nameOf(code) : Json.s(r.get("name"));
                    q.offer(() -> placeBuy(code, nm, 0, amt, 0, true, 0, true, reason, false));
                    result = label(code, nm) + " " + Engine.won(amt) + "원 매수 주문";
                }
                break;
            }
            case "halt_buy":
                halt(why);
                result = "조건식 자동매수 중지";
                break;
            case "resume_buy":
                resume();
                result = "조건식 자동매수 재개";
                break;
            default:
                result = "알 수 없는 동작";
        }
        Logx.add("지수 규칙 실행 – " + why + " → " + actText(r) + " : " + result, "warn");
        kakao.push("🏛 지수 연동 매매\n" + why + "\n→ " + actText(r) + "\n" + result);
    }

    /** 규칙 추가/삭제/켜기 (AppCore 에서 호출) */
    public Object[] editRule(String op, Map<String, Object> in) {
        synchronized (cfg.lock) {
            Map<String, Object> tr = cfg.sec("trade");
            List<Object> rules = new ArrayList<>(Json.arr(tr.get("index_rules")));
            String id = Json.s(in.get("id"));
            if ("del".equals(op) || "toggle".equals(op) || "rearm".equals(op)) {
                Map<String, Object> found = null;
                for (Object o : rules) if (id.equals(Json.s(Json.obj(o).get("id")))) found = Json.obj(o);
                if (found == null) return new Object[]{false, "규칙을 찾을 수 없습니다"};
                if ("del".equals(op)) rules.remove(found);
                else if ("toggle".equals(op)) found.put("enabled", !Json.b(found.get("enabled")));
                else synchronized (lock) {
                    ruleFired.remove(id);
                }
            } else {
                String ix = Json.s(in.get("index"));
                String c = Json.s(in.get("cond")), a = Json.s(in.get("action"));
                if (!"001".equals(ix) && !"101".equals(ix)) return new Object[]{false, "지수를 선택하세요"};
                if (!java.util.Arrays.asList("rate_le", "rate_ge", "price_le", "price_ge", "surge_up", "surge_dn").contains(c))
                    return new Object[]{false, "조건을 선택하세요"};
                if (!java.util.Arrays.asList("sell_all", "sell_pct", "buy", "halt_buy", "resume_buy").contains(a))
                    return new Object[]{false, "동작을 선택하세요"};
                double v = Math.abs(Json.d(Json.s(in.get("value")).replace(",", ""), -1));
                if (Json.s(in.get("value")).trim().isEmpty() || v <= 0) return new Object[]{false, "조건 값을 입력하세요"};
                Map<String, Object> r = Json.map("id", "r" + System.currentTimeMillis(), "enabled", true, "index", ix,
                        "cond", c, "value", v, "minutes", Math.max(1, Json.d(in.get("minutes"), 5)),
                        "action", a, "scope", "all".equals(Json.s(in.get("scope"))) ? "all" : "auto",
                        "pct", Math.max(1, Math.min(100, Json.d(in.get("pct"), 50))));
                if ("buy".equals(a)) {
                    String code = Engine.cleanCode(in.get("code"));
                    double amt = Json.d(Json.s(in.get("amount")).replace(",", ""), 0);
                    if (!code.matches("[0-9A-Z]{6}")) return new Object[]{false, "매수할 종목코드 6자리를 입력하세요"};
                    if (amt <= 0) return new Object[]{false, "매수 금액을 입력하세요"};
                    r.put("code", code);
                    r.put("amount", amt);
                    r.put("name", Json.s(in.get("name")).trim());
                }
                if (rules.size() >= 20) return new Object[]{false, "규칙은 최대 20개까지"};
                rules.add(r);
                id = Json.s(r.get("id"));
                tr.put("index_rules", rules);
                cfg.save();
                reload();
                return new Object[]{true, ixName(ix) + " 규칙 추가: " + condText(r) + " → " + actText(r)};
            }
            tr.put("index_rules", rules);
            cfg.save();
        }
        reload();
        save();
        return new Object[]{true, "del".equals(op) ? "규칙 삭제" : ("toggle".equals(op) ? "규칙 켜기/끄기 변경" : "오늘 다시 실행 가능")};
    }

    // ───────── 매수 예약 (하락 매수 / 시초가 매수 / n분봉 양봉 매수) ─────────
    static final String[] PLAN_TYPES = {"dip", "open", "bull", "bear", "touch"};
    static final String[] AUTO_MODES = {"now", "dip", "bull", "bear", "touch"};

    /** 다시 시도해도 소용없는 자동매수 차단 사유 */
    private static boolean permanentBlock(String why) {
        return why.contains("오늘 이미") || why.contains("이미 보유") || why.contains("하루 최대") || why.contains("최대 보유") || why.contains("매수 마감");
    }

    /** 조건식 편입 → 바로 사지 않고 '자동 예약'을 걸어 둠 (매매 탭 설정의 매수 방식) */
    private void autoPlan(String code, String cond, double price, String name, String mode) {
        String lb = label(code, name);
        String why = autoBuyBlock(code);
        if (why != null && permanentBlock(why)) {
            Logx.add("자동매수 건너뜀 " + lb + ": " + why);
            return;
        }
        synchronized (lock) {
            Map<String, Object> old = plans.get(code);
            if (old != null) {
                if (!Json.b(old.get("auto"))) Logx.add("자동매수 건너뜀 " + lb + ": 이미 직접 건 매수 예약이 있음");
                return;                                       // 이미 대기 중
            }
        }
        Map<String, Object> in = Json.map("code", code, "name", name, "type", mode, "amount", n("buy_amount"),
                "basis", "price", "base", price, "pct", n("auto_pct"), "min", n("auto_min"), "cnt", n("auto_cnt"));
        Object[] r = buildPlan(in);
        if (!(Boolean) r[0]) {
            Logx.add("자동매수 예약 실패 " + lb + ": " + r[1], "warn");
            return;
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> pl = (Map<String, Object>) r[2];
        long now = System.currentTimeMillis();
        double wait = n("auto_wait");
        long exp;
        if (wait > 0) exp = now + (long) (wait * 60_000);
        else {
            String en = Json.s(t.get("buy_end"));
            int hh = 15, mm = 20;
            if (en.matches("\\d\\d:\\d\\d")) {
                hh = Integer.parseInt(en.substring(0, 2));
                mm = Integer.parseInt(en.substring(3));
            }
            exp = Candles.todayAt(hh, mm);
            if (exp <= now) exp = now + 60_000;
        }
        pl.put("auto", true);
        pl.put("cond", cond);
        pl.put("expires", (double) exp);
        synchronized (lock) {
            if (plans.size() >= 30) {
                Logx.add("자동매수 예약 건너뜀 " + lb + ": 예약이 30개로 가득 참", "warn");
                return;
            }
            plans.put(code, pl);
        }
        eng.watchHeld(code, Json.s(pl.get("name")));
        save();
        Logx.add("[" + cond + "] 편입 → 자동매수 대기 " + lb + ": " + planText(pl));
        kakao.push("⏳ 자동매수 대기 [" + cond + "]\n" + lb + "\n" + planText(pl) + "\n(" + hm(exp) + "까지)");
    }

    static String hm(long ms) {
        SimpleDateFormat f = new SimpleDateFormat("HH:mm", Locale.KOREA);
        f.setTimeZone(KST);
        return f.format(new Date(ms));
    }

    static String planText(Map<String, Object> pl) {
        String ty = Json.s(pl.get("type"));
        String s;
        if ("dip".equals(ty)) {
            s = "rate".equals(Json.s(pl.get("basis")))
                    ? String.format(Locale.KOREA, "전일 대비 -%.1f%% 이하", Json.d(pl.get("pct"), 0))
                    : String.format(Locale.KOREA, "%s원 이하 (기준 %s원 −%.1f%%)", Engine.won(Json.d(pl.get("target"), 0)),
                    Engine.won(Json.d(pl.get("base"), 0)), Json.d(pl.get("pct"), 0));
        } else if ("open".equals(ty)) {
            s = "시초가 (09:00 시장가)";
        } else if ("touch".equals(ty)) {
            s = "시가 " + Engine.won(Json.d(pl.get("open"), 0)) + "원 도달 (" + ("down".equals(Json.s(pl.get("side"))) ? "내려오면" : "올라오면") + ")";
        } else {
            int c = (int) Json.d(pl.get("cnt"), 1);
            s = (int) Json.d(pl.get("min"), 1) + "분봉 " + ("bear".equals(ty) ? "음봉" : "양봉") + (c > 1 ? " " + c + "개 연속" : "");
        }
        String pre = Json.b(pl.get("auto")) ? "[" + Json.s(pl.get("cond")) + "] " : "";
        return pre + s + " → " + Engine.won(Json.d(pl.get("amount"), 0)) + "원 매수";
    }

    static String bearText(double min, double cnt) {
        if (min <= 0) return "";
        return (int) min + "분봉 음봉" + (cnt > 1 ? " " + (int) cnt + "개 연속" : "") + " 시 매도";
    }

    /** 예약 내용 검사/작성 → {ok, msg, plan}. 네트워크 조회가 있을 수 있음 */
    private Object[] buildPlan(Map<String, Object> in) {
        String code = Engine.cleanCode(in.get("code"));
        if (code.isEmpty()) return new Object[]{false, "종목코드가 없습니다"};
        String ty = Json.s(in.get("type"));
        if (!java.util.Arrays.asList(PLAN_TYPES).contains(ty)) return new Object[]{false, "매수 조건을 선택하세요"};
        double amount = Json.d(Json.s(in.get("amount")).replace(",", ""), 0);
        if (amount <= 0) amount = n("buy_amount");
        if (amount <= 0) return new Object[]{false, "매수 금액을 입력하세요"};
        String nm0 = eng.nameOf(code), given = Json.s(in.get("name")).trim();
        if ((nm0 == null || nm0.isEmpty() || nm0.equals(code)) && !given.isEmpty()) nm0 = given;
        Map<String, Object> pl = Json.map("code", code, "name", nm0, "type", ty, "amount", amount,
                "created", (double) System.currentTimeMillis(), "day", today());
        if ("dip".equals(ty)) {
            double pct = Json.d(Json.s(in.get("pct")), 0);
            if (pct <= 0 || pct >= 30) return new Object[]{false, "하락 % 는 0~30 사이로 입력하세요"};
            pl.put("pct", pct);
            String basis = "rate".equals(Json.s(in.get("basis"))) ? "rate" : "price";
            pl.put("basis", basis);
            if ("price".equals(basis)) {
                double base = Json.d(Json.s(in.get("base")).replace(",", ""), 0);
                if (base <= 0) base = eng.priceOf(code);
                if (base <= 0 && eng.rest() != null) {
                    try {
                        base = (Double) eng.rest().stockInfo(code)[1];
                    } catch (Exception ignored) {
                    }
                }
                if (base <= 0) return new Object[]{false, "기준가를 알 수 없습니다. 기준가(원)를 입력하세요"};
                pl.put("base", base);
                pl.put("target", (double) Math.floor(base * (1 - pct / 100)));
            }
        } else if ("bull".equals(ty) || "bear".equals(ty)) {
            int min = (int) Json.d(in.get("min"), 0), cnt = (int) Json.d(in.get("cnt"), 1);
            if (!Candles.validScope(min)) return new Object[]{false, "분봉은 1/3/5/10/15/30/45/60 중에서 고르세요"};
            if (cnt < 1 || cnt > 10) return new Object[]{false, "봉 개수는 1~10"};
            pl.put("min", (double) min);
            pl.put("cnt", (double) cnt);
        } else if ("touch".equals(ty)) {
            double op = 0, cur = Json.d(Json.s(in.get("base")).replace(",", ""), 0);
            try {
                if (eng.rest() == null) return new Object[]{false, "키움 미연결 (▶시작 후 예약하세요)"};
                Object[] si = eng.rest().stockInfo(code);
                op = si.length > 3 ? (Double) si[3] : 0;
                double c2 = (Double) si[1];
                if (c2 > 0) cur = c2;
            } catch (Exception e) {
                return new Object[]{false, "시가 조회 실패: " + e.getMessage()};
            }
            if (op <= 0) return new Object[]{false, "오늘 시가가 아직 없습니다 (장 시작 후 예약하세요)"};
            if (cur <= 0) cur = eng.priceOf(code);
            pl.put("open", op);
            pl.put("base", cur);
            pl.put("side", cur > op ? "down" : "up");
            if (cur > 0 && cur == op) pl.put("hit_now", true);
        }
        int bm = (int) Json.d(in.get("bear_min"), 0), bc = (int) Json.d(in.get("bear_cnt"), 1);
        if (bm > 0) {
            if (!Candles.validScope(bm)) return new Object[]{false, "음봉 매도 분봉 값이 올바르지 않습니다"};
            if (bc < 1 || bc > 10) return new Object[]{false, "음봉 개수는 1~10"};
            pl.put("bear_min", (double) bm);
            pl.put("bear_cnt", (double) bc);
        }
        return new Object[]{true, "", pl};
    }

    public Object[] addPlan(Map<String, Object> in) {
        Object[] r = buildPlan(in);
        if (!(Boolean) r[0]) return r;
        @SuppressWarnings("unchecked")
        Map<String, Object> pl = (Map<String, Object>) r[2];
        String code = Json.s(pl.get("code")), ty = Json.s(pl.get("type"));
        int bm = (int) Json.d(pl.get("bear_min"), 0), bc = (int) Json.d(pl.get("bear_cnt"), 1);
        synchronized (lock) {
            if (!plans.containsKey(code) && plans.size() >= 30) return new Object[]{false, "매수 예약은 최대 30개까지"};
            plans.put(code, pl);
            planWarned.remove(code);
        }
        eng.watchHeld(code, Json.s(pl.get("name")));
        save();
        String lb = label(code, Json.s(pl.get("name")));
        String extra = bm > 0 ? "\n매수 후 " + bearText(bm, bc) : "";
        Logx.add("매수 예약 " + lb + ": " + planText(pl) + extra.replace("\n", " · "));
        kakao.push("🎯 매수 예약\n" + lb + "\n" + planText(pl) + extra);
        String note = "open".equals(ty) && hhmm().compareTo("09:00") >= 0 ? " (다음 거래일 09:00 실행)" : "";
        return new Object[]{true, "매수 예약됨: " + planText(pl) + note};
    }

    public Object[] cancelPlan(String code) {
        Map<String, Object> pl;
        synchronized (lock) {
            pl = plans.remove(code);
        }
        if (pl == null) return new Object[]{false, "예약이 없습니다"};
        save();
        Logx.add("매수 예약 취소 " + label(code, Json.s(pl.get("name"))));
        return new Object[]{true, "매수 예약 취소"};
    }

    /** 장중 + 주문 가능할 때만 예약 실행 (null = 가능) */
    private String planBlock() {
        String ob = orderBlock();
        if (ob != null) return ob;
        checkDay();
        if (!weekday()) return "주말";
        String now = hhmm();
        if (now.compareTo("09:00") < 0 || now.compareTo("15:20") >= 0) return "장 시간 아님";
        if (halted) return "자동매수 정지 상태 (" + haltReason + ")";
        return null;
    }

    private void warnPlan(String code, String why) {
        long now = System.currentTimeMillis();
        synchronized (lock) {
            Long w = planWarned.get(code);
            if (w != null && now - w < 10 * 60_000L) return;
            planWarned.put(code, now);
        }
        Logx.add("매수 예약 대기 " + code + ": " + why);
    }

    /** 조건 충족 → 예약 실행 (예약은 1회 실행 후 삭제) */
    private void firePlan(String code, String why, double price) {
        Map<String, Object> pl;
        synchronized (lock) {
            pl = plans.get(code);
            if (pl == null || planBusy.contains(code)) return;
            planBusy.add(code);
        }
        try {
            boolean auto = Json.b(pl.get("auto"));
            String blk = planBlock();
            if (blk == null && auto) blk = autoBuyBlock(code);
            if (blk != null) {
                if (auto && permanentBlock(blk)) {
                    synchronized (lock) {
                        plans.remove(code);
                    }
                    save();
                    Logx.add("자동매수 대기 취소 " + label(code, Json.s(pl.get("name"))) + ": " + blk);
                    return;
                }
                warnPlan(code, blk);
                return;
            }
            synchronized (lock) {
                plans.remove(code);
                double bm = Json.d(pl.get("bear_min"), 0);
                if (bm > 0) {
                    Map<String, Object> c = posCfg.get(code);
                    if (c == null) posCfg.put(code, c = new LinkedHashMap<>());
                    c.put("bear_min", bm);
                    c.put("bear_cnt", Json.d(pl.get("bear_cnt"), 1));
                    c.put("bear_at", (double) System.currentTimeMillis());
                }
            }
            save();
            String name = Json.s(pl.get("name"));
            if (name.isEmpty() || name.equals(code)) name = eng.nameOf(code);
            Logx.add((auto ? "🤖 [" + Json.s(pl.get("cond")) + "] 자동매수 실행 " : "🎯 매수 예약 실행 ") + label(code, name) + ": " + why);
            final String nm = name;
            if (auto) {
                synchronized (lock) {
                    buysToday++;                          // 주문 전에 미리 세서 한도 초과 방지 (placeBuy 가 auto 면 다시 세지 않음)
                    boughtToday.add(code);
                }
                String cond = Json.s(pl.get("cond"));
                q.offer(() -> {
                    synchronized (lock) {
                        buysToday--;
                        boughtToday.remove(code);
                    }
                    placeBuy(code, nm, price, Json.d(pl.get("amount"), 0), 0, true, 0, true, "[" + cond + "] " + why, true);
                });
            } else {
                q.offer(() -> placeBuy(code, nm, price, Json.d(pl.get("amount"), 0), 0, true, 0, true, "🎯 " + why, false));
            }
        } finally {
            synchronized (lock) {
                planBusy.remove(code);
            }
        }
    }

    /** 하락 매수 확인 (체결가 들어올 때 / 타이머) */
    private void checkDip(String code, double p, double rate, boolean fromTick) {
        if (p <= 0) return;
        Map<String, Object> pl;
        synchronized (lock) {
            pl = plans.get(code);
        }
        if (pl == null) return;
        String ty = Json.s(pl.get("type"));
        String why = null;
        if ("touch".equals(ty)) {
            double op = Json.d(pl.get("open"), 0);
            boolean down = "down".equals(Json.s(pl.get("side")));
            if (op > 0 && (Json.b(pl.get("hit_now")) || (down ? p <= op : p >= op)))
                why = "시가 " + Engine.won(op) + "원 도달 (현재가 " + Engine.won(p) + ")";
        } else if (!"dip".equals(ty)) {
            return;
        } else if ("rate".equals(Json.s(pl.get("basis")))) {
            double pct = Json.d(pl.get("pct"), 0);
            if (rate <= -pct) why = String.format(Locale.KOREA, "전일 대비 %+.2f%% (−%.1f%% 이하)", rate, pct);
        } else {
            double tg = Json.d(pl.get("target"), 0);
            if (tg > 0 && p <= tg) why = "현재가 " + Engine.won(p) + " ≤ 목표 " + Engine.won(tg) + "원";
        }
        if (why != null) {
            final String w = why;
            if (fromTick) q.offer(() -> firePlan(code, w, p));
            else firePlan(code, w, p);
        }
    }

    /** 타이머(1초)에서 호출: 시초가 예약, 하락 예약 보조 확인, 분봉 조회 일정 */
    /** 손절 1분 확인: 1분 뒤 현재가가 손절 기준가보다 높으면 취소, 같거나 낮으면 매도 */
    private void slConfirmTick(long now) {
        List<Object[]> todo = new ArrayList<>();
        synchronized (lock) {
            for (Pos p : pos.values()) {
                if (p.slWaitAt == 0 || now - p.slWaitAt < 60_000) continue;
                double cur = eng.priceOf(p.code);
                if (cur <= 0) cur = p.last;
                todo.add(new Object[]{p.code, p.name, cur, p.slLevel, p.slWhy});
                p.slWaitAt = 0;
            }
        }
        for (Object[] x : todo) {
            String code = (String) x[0], lb = label(code, (String) x[1]);
            double cur = (Double) x[2], lv = (Double) x[3];
            if (cur > lv) {
                String m = "↩ 손절 취소 " + lb + ": 1분 후 " + Engine.won(cur) + "원 > 손절 기준 " + Engine.won(lv) + "원";
                Logx.add(m);
                kakao.push(m);
            } else {
                sell(code, 0, x[4] + " · 1분 확인 " + Engine.won(cur) + "≤" + Engine.won(lv), false);
            }
        }
    }

    private void planTick(long now) {
        slConfirmTick(now);
        List<Map<String, Object>> ps;
        synchronized (lock) {
            ps = new ArrayList<>(plans.values());
        }
        String hm = hhmm(), d = today();
        long open = Candles.todayAt(9, 0);
        boolean session = weekday() && hm.compareTo("09:00") >= 0 && hm.compareTo("15:31") < 0;
        // 분봉이 필요한 (종목, 분) 모으기
        Map<String, int[]> need = new LinkedHashMap<>();
        for (Map<String, Object> pl : ps) {
            String code = Json.s(pl.get("code")), ty = Json.s(pl.get("type"));
            if (Json.b(pl.get("auto")) && (now > Json.d(pl.get("expires"), 0) || !d.equals(Json.s(pl.get("day"))))) {
                synchronized (lock) {
                    if (plans.get(code) == pl) plans.remove(code);
                }
                save();
                Logx.add("자동매수 대기 만료 " + label(code, Json.s(pl.get("name"))) + ": " + planText(pl));
                continue;
            }
            if ("open".equals(ty)) {
                boolean createdBefore = Json.d(pl.get("created"), 0) < open;
                if (weekday() && hm.compareTo("09:00") >= 0 && hm.compareTo("09:10") < 0 && createdBefore && !d.equals(Json.s(pl.get("tried")))) {
                    synchronized (lock) {
                        pl.put("tried", d);
                    }
                    q.offer(() -> firePlan(code, "시초가 매수", 0));
                }
            } else if (("dip".equals(ty) || "touch".equals(ty)) && now / 1000 % 3 == 0) {
                double p = eng.priceOf(code);
                if (p > 0) {
                    final double rate = eng.rateOf(code);
                    q.offer(() -> checkDip(code, p, rate, false));
                }
            } else if ("bull".equals(ty) || "bear".equals(ty)) {
                int min = (int) Json.d(pl.get("min"), 1);
                need.put(code + "|" + min, new int[]{min});
            }
        }
        synchronized (lock) {
            for (Pos p : pos.values()) {
                if (p.qty <= 0 || !autoOn(p)) continue;
                int bm = (int) effv(p.code, "bear_min");
                if (bm > 0 && Candles.validScope(bm)) need.put(p.code + "|" + bm, new int[]{bm});
                int tb = (int) effv(p.code, "tp_bear_min");
                if (p.tpArmedAt > 0 && tb > 0 && Candles.validScope(tb)) need.put(p.code + "|" + tb, new int[]{tb});
            }
        }
        if (!session || need.isEmpty() || !eng.connected) return;
        for (Map.Entry<String, int[]> e : need.entrySet()) {
            int min = e.getValue()[0];
            long step = min * 60_000L;
            long boundary = open + (now - open) / step * step;
            if (boundary <= open || now < boundary + 3000) continue;   // 첫 봉 완성 전
            Long last = candlePolled.get(e.getKey());
            if (last != null && last == boundary) continue;
            candlePolled.put(e.getKey(), boundary);
            String code = e.getKey().substring(0, e.getKey().indexOf('|'));
            q.offer(() -> evalCandles(code, min));
        }
    }

    /** 완성된 분봉으로 양봉 매수 / 음봉 매도 판단 */
    private void evalCandles(String code, int min) {
        List<Candles.Bar> bars;
        long now = System.currentTimeMillis();
        try {
            Engine.Rest rest = eng.rest();
            if (rest == null) return;
            bars = Candles.completed(rest, code, min, now);
        } catch (Exception e) {
            Logx.add("분봉 조회 실패 " + code + " " + min + "분: " + e.getMessage(), "warn");
            return;
        }
        if (bars.isEmpty()) return;
        if (bars.get(0).end < now - min * 60_000L - 120_000L) return;     // 최근 봉이 아님 (거래정지 등)
        Map<String, Object> pl;
        Pos p;
        double bm, bc;
        boolean auto;
        synchronized (lock) {
            pl = plans.get(code);
            p = pos.get(code);
            bm = effv(code, "bear_min");
            bc = Math.max(1, effv(code, "bear_cnt"));
            auto = p != null && p.qty > 0 && autoOn(p);
        }
        long dayOpen = Candles.todayAt(9, 0);
        String pty = pl == null ? "" : Json.s(pl.get("type"));
        if (("bull".equals(pty) || "bear".equals(pty)) && (int) Json.d(pl.get("min"), 0) == min) {
            boolean up = "bull".equals(pty);
            int need = (int) Json.d(pl.get("cnt"), 1);
            long since = Math.max((long) Json.d(pl.get("created"), 0), dayOpen);
            int got = Candles.streak(bars, up, since);
            if (got >= need) firePlan(code, min + "분봉 " + (up ? "양봉 " : "음봉 ") + got + "개" + (need > 1 ? " 연속" : ""), bars.get(0).close);
        }
        if (auto && p.tpArmedAt > 0 && (int) effv(code, "tp_bear_min") == min) {
            if (Candles.streak(bars, false, p.tpArmedAt) >= 1) {
                double pnl = p.avg > 0 ? (bars.get(0).close / p.avg - 1) * 100 : 0;
                sell(code, 0, String.format(Locale.KOREA, "%s → %d분봉 음봉 %+.2f%%", p.tpWhy, min, pnl), false);
                return;
            }
        }
        if (auto && (int) bm == min) {
            long since = Math.max(Math.max(p.buyAt, dayOpen), bearAt(code));
            int got = Candles.streak(bars, false, since);
            if (got >= (int) bc) {
                double pnl = p.avg > 0 ? (bars.get(0).close / p.avg - 1) * 100 : 0;
                sell(code, 0, String.format(Locale.KOREA, "%d분봉 음봉 %d개%s %+.2f%%", min, got, bc > 1 ? " 연속" : "", pnl), false);
            }
        }
    }

    /** 음봉 매도 설정을 켠 시각 (그 뒤에 완성된 봉부터 셈) */
    private long bearAt(String code) {
        synchronized (lock) {
            Map<String, Object> c = posCfg.get(code);
            if (c != null && c.get("bear_min") != null && !Json.s(c.get("bear_min")).isEmpty()) return (long) Json.d(c.get("bear_at"), 0);
        }
        return (long) n("bear_at");
    }

    private List<Object> plansView() {
        List<Object> out = new ArrayList<>();
        for (Map<String, Object> pl : plans.values()) {
            String code = Json.s(pl.get("code"));
            String nm = Json.s(pl.get("name"));
            if (nm.isEmpty() || nm.equals(code)) {
                nm = eng.nameOf(code);
                if (nm != null && !nm.equals(code)) pl.put("name", nm);
            }
            out.add(Json.map("code", code, "name", nm, "type", Json.s(pl.get("type")), "text", planText(pl),
                    "sell", bearText(Json.d(pl.get("bear_min"), 0), Json.d(pl.get("bear_cnt"), 1)),
                    "price", eng.priceOf(code), "rate", eng.rateOf(code), "target", Json.d(pl.get("target"), 0),
                    "created", Json.d(pl.get("created"), 0), "auto", Json.b(pl.get("auto")),
                    "until", Json.b(pl.get("auto")) ? hm((long) Json.d(pl.get("expires"), 0)) : ""));
        }
        return out;
    }

    // ───────── 타이머: 잔고 동기화, 장마감 정리 ─────────
    private void timerLoop() {
        while (true) {
            try {
                Thread.sleep(1000);
                checkDay();
                if (!eng.connected) continue;
                long now = System.currentTimeMillis();
                planTick(now);
                boolean active;
                synchronized (lock) {
                    active = b("enabled") || !pos.isEmpty();
                }
                if (now - lastBalance > (active ? 20_000 : 60_000) || (balanceDue > 0 && now >= balanceDue)) {
                    balanceDue = 0;
                    lastBalance = now;
                    q.offer(this::refreshBalance);
                }
                String ct = Json.s(t.get("close_time"));
                if (weekday() && !ct.isEmpty() && hhmm().compareTo(ct) >= 0 && hhmm().compareTo("15:30") < 0) {
                    boolean doIt;
                    synchronized (lock) {
                        doIt = !closedToday;
                        closedToday = true;
                    }
                    if (doIt) {
                        Object[] r = sellAll("장마감 정리 (" + ct + ")", false);
                        if (!"매도할 보유 종목이 없습니다".equals(r[1])) {
                            Logx.add("장마감 정리(" + ct + "): " + r[1]);
                            kakao.push("🕒 장마감 정리\n" + r[1]);
                        }
                        save();
                    }
                }
            } catch (InterruptedException e) {
                return;
            } catch (Exception e) {
                Logx.add("매매 타이머 오류: " + e.getMessage(), "warn");
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
                    p.bot = owned.contains(code);
                    p.placeholder = false;
                    if (p.sellPending && System.currentTimeMillis() - p.sellAt > 60_000) p.sellPending = false;
                }
                long now = System.currentTimeMillis();
                for (Pos p : new ArrayList<>(pos.values())) {
                    if (seen.contains(p.code)) continue;
                    if (p.placeholder && !p.sellPending && now - p.buyAt < 90_000) continue;   // 매수 체결 대기
                    pos.remove(p.code);
                    owned.remove(p.code);
                    posCfg.remove(p.code);                                                  // 다 팔리면 설정도 정리
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
            st = Json.map("day", day, "owned", new ArrayList<Object>(owned), "pos_cfg", new LinkedHashMap<String, Object>(posCfg),
                    "bought_today", new ArrayList<Object>(boughtToday),
                    "buys_today", (double) buysToday, "realized", realized, "halted", halted, "halt_reason", haltReason,
                    "closed_today", closedToday, "liquidated", liquidated, "orders", new ArrayList<Object>(orders),
                    "rule_fired", new LinkedHashMap<String, Object>(ruleFired), "plans", new ArrayList<Object>(plans.values()));
            st = Json.obj(Json.copy(st));
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
                for (Map.Entry<String, Object> e : Json.obj(st.get("pos_cfg")).entrySet()) posCfg.put(e.getKey(), Json.obj(e.getValue()));
                for (Map.Entry<String, Object> e : Json.obj(st.get("rule_fired")).entrySet()) ruleFired.put(e.getKey(), Json.s(e.getValue()));
                for (Object o : Json.arr(st.get("plans"))) {
                    Map<String, Object> pl = Json.obj(o);
                    String c = Json.s(pl.get("code"));
                    if (!c.isEmpty()) plans.put(c, pl);
                }
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
                Map<String, Object> c = posCfg.get(p.code);
                boolean custom = false;
                if (c != null) for (String k : POS_KEYS) if (c.containsKey(k)) custom = true;
                ps.add(Json.map("code", p.code, "name", p.name == null ? p.code : p.name, "qty", (double) p.qty,
                        "avail", (double) p.avail, "avg", p.avg, "last", p.last, "high", p.high, "pnl", pnl,
                        "bot", owned.contains(p.code), "auto", autoOn(p), "custom", custom,
                        "cfg", c == null ? new LinkedHashMap<String, Object>() : new LinkedHashMap<String, Object>(c),
                        "eff", Json.map("take_profit", effv(p.code, "take_profit"), "stop_loss", effv(p.code, "stop_loss"),
                                "trail_pct", effv(p.code, "trail_pct"), "tp_price", effv(p.code, "tp_price"), "sl_price", effv(p.code, "sl_price"),
                                "bear_min", effv(p.code, "bear_min"), "bear_cnt", effv(p.code, "bear_cnt"),
                                "sl_confirm", effv(p.code, "sl_confirm"), "tp_bear_min", effv(p.code, "tp_bear_min")),
                        "sl_wait", p.slWaitAt > 0 ? (double) Math.max(0, 60 - (System.currentTimeMillis() - p.slWaitAt) / 1000) : -1.0,
                        "sl_level", p.slLevel, "tp_armed", p.tpArmedAt > 0,
                        "bear", bearText(effv(p.code, "bear_min"), Math.max(1, effv(p.code, "bear_cnt"))),
                        "pending", p.sellPending, "wait", p.placeholder));
            }
            String block = orderBlock();
            return Json.map("enabled", b("enabled"), "mock", mock(), "real_ok", b("real_ok"),
                    "block", block == null ? "" : block, "halted", halted, "halt_reason", haltReason,
                    "buys_today", (double) buysToday, "max_buys", n("max_buys"), "realized", realized, "eval_pl", evalPl,
                    "positions", ps, "orders", new ArrayList<Object>(orders.subList(0, Math.min(60, orders.size()))),
                    "balance_err", balanceErr, "rules", rulesView(), "plans", plansView(), "ix", Json.map("001", Json.map("price", ixPrice[0], "rate", ixRate[0]),
                            "101", Json.map("price", ixPrice[1], "rate", ixRate[1])));
        }
    }

    private List<Object> rulesView() {
        List<Object> out = new ArrayList<>();
        String d = today();
        for (Object o : Json.arr(t.get("index_rules"))) {
            Map<String, Object> r = Json.obj(o);
            out.add(Json.map("id", Json.s(r.get("id")), "index", Json.s(r.get("index")), "name", ixName(Json.s(r.get("index"))),
                    "enabled", Json.b(r.get("enabled")), "cond", condText(r), "action", actText(r),
                    "fired", d.equals(ruleFired.get(Json.s(r.get("id"))))));
        }
        return out;
    }

    public String shortStatus() {
        synchronized (lock) {
            int held = 0;
            for (Pos p : pos.values()) if (p.qty > 0) held++;
            if (!b("enabled") && held == 0) return "";
            return " · 💰" + (b("enabled") ? (halted ? "정지" : "자동") : "수동") + " " + held + "종목";
        }
    }
}
