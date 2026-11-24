package com.stockalert.core;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 계좌현황 (키움 앱 '국내잔고' / '당일매매' 화면과 같은 내용)
 *  - 잔고: kt00018 계좌평가잔고내역요청  POST /api/dostk/acnt
 *  - 예수금: kt00001 예수금상세현황요청
 *  - 실현손익: ka10074 일자별실현손익요청 (당일)
 *  - 당일매매: ka10170 당일매매일지요청
 * 조회 전용 — 주문은 하지 않습니다.
 */
public final class Account {
    private final Store cfg;
    private final Engine eng;
    private Engine.Rest own;          // 감시 시작 전에도 조회할 수 있게 별도 연결
    private String ownKey = "";

    public Account(Store cfg, Engine eng) {
        this.cfg = cfg;
        this.eng = eng;
    }

    private synchronized Engine.Rest rest() throws Exception {
        Engine.Rest r = eng.rest();
        if (r != null && eng.running) return r;
        String ak = cfg.str("kiwoom", "appkey"), sk = cfg.str("kiwoom", "secretkey");
        if (ak.isEmpty() || sk.isEmpty()) throw new Exception("키움 App Key / Secret Key 를 먼저 입력하세요");
        boolean mock = cfg.bool("kiwoom", "mock");
        String key = ak + "|" + sk + "|" + mock;
        if (own == null || !key.equals(ownKey)) {
            own = new Engine.Rest(ak, sk, mock);
            ownKey = key;
        }
        return own;
    }

    static long n(Object o) {
        String s = Json.s(o).trim().replace(",", "");
        if (s.isEmpty()) return 0;
        try {
            return Math.round(Double.parseDouble(s));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    static double r(Object o) {
        String s = Json.s(o).trim().replace(",", "").replace("%", "");
        if (s.isEmpty()) return 0;
        try {
            return Double.parseDouble(s);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /** 키움은 일부 값에 부호(+/-)를 붙이거나 0으로 채워 보냄 → 금액은 절대값, 손익은 부호 유지 */
    private static boolean ok(Map<String, Object> m) {
        return "0".equals(Json.s(m.get("return_code")).trim());
    }

    private static String err(Map<String, Object> m) {
        String s = Json.s(m.get("return_msg")).trim();
        return s.isEmpty() ? "응답 없음 (서버/IP 등록 확인)" : s;
    }

    private static String today() {
        SimpleDateFormat f = new SimpleDateFormat("yyyyMMdd", Locale.KOREA);
        f.setTimeZone(Trader.KST);
        return f.format(new Date());
    }

    /** 국내잔고 */
    public Object[] balance() {
        try {
            Engine.Rest rest = rest();
            Map<String, Object> js = rest.call("kt00018", "/api/dostk/acnt", Json.map("qry_tp", "1", "dmst_stex_tp", "KRX"));
            if (!ok(js)) return new Object[]{false, "잔고 조회 실패: " + err(js)};
            List<Object> rows = new ArrayList<>();
            long sumPur = 0, sumEv = 0, sumPl = 0;
            for (Object o : Json.arr(js.get("acnt_evlt_remn_indv_tot"))) {
                Map<String, Object> m = Json.obj(o);
                String code = Engine.cleanCode(m.get("stk_cd"));
                long qty = Math.abs(n(m.get("rmnd_qty")));
                if (code.isEmpty() || qty <= 0) continue;
                long avg = Math.abs(n(m.get("pur_pric")));
                long cur = Math.abs(n(m.get("cur_prc")));
                long pur = Math.abs(n(m.get("pur_amt")));
                if (pur == 0) pur = avg * qty;
                long ev = Math.abs(n(m.get("evlt_amt")));
                if (ev == 0) ev = cur * qty;
                String plRaw = Json.s(m.get("evltv_prft")).trim();
                long pl = plRaw.isEmpty() ? ev - pur : n(plRaw);
                double rate = m.containsKey("prft_rt") ? r(m.get("prft_rt")) : (pur > 0 ? pl * 100.0 / pur : 0);
                sumPur += pur;
                sumEv += ev;
                sumPl += pl;
                rows.add(Json.map("code", code, "name", Json.s(m.get("stk_nm")).trim(),
                        "qty", (double) qty, "avail", (double) Math.abs(n(m.get("trde_able_qty"))),
                        "avg", (double) avg, "cur", (double) cur, "pur", (double) pur, "eval", (double) ev,
                        "pl", (double) pl, "rate", rate, "weight", r(m.get("poss_rt"))));
            }
            long totPur = Math.abs(n(js.get("tot_pur_amt")));
            long totEv = Math.abs(n(js.get("tot_evlt_amt")));
            String tpl = Json.s(js.get("tot_evlt_pl")).trim();
            long totPl = tpl.isEmpty() ? sumPl : n(tpl);
            if (totPur == 0) totPur = sumPur;
            if (totEv == 0) totEv = sumEv;
            double totRate = js.containsKey("tot_prft_rt") ? r(js.get("tot_prft_rt")) : (totPur > 0 ? totPl * 100.0 / totPur : 0);
            long asset = Math.abs(n(js.get("prsm_dpst_aset_amt")));

            // 예수금 (실패해도 잔고는 보여줌)
            Map<String, Object> dep = Json.map();
            try {
                Map<String, Object> d = rest.call("kt00001", "/api/dostk/acnt", Json.map("qry_tp", "3"));
                if (ok(d)) {
                    dep.put("entr", (double) n(d.get("entr")));
                    dep.put("d2", (double) n(d.get("d2_entra")));
                    dep.put("order", (double) n(d.get("ord_alow_amt")));
                    dep.put("withdraw", (double) n(d.get("pymn_alow_amt")));
                }
            } catch (Exception ignored) {
            }

            // 당일 실현손익
            Object realized = null;
            try {
                String t = today();
                Map<String, Object> d = rest.call("ka10074", "/api/dostk/acnt", Json.map("stex_tp", "0", "strt_dt", t, "end_dt", t));
                if (ok(d) && d.containsKey("rlzt_pl")) realized = (double) n(d.get("rlzt_pl"));
            } catch (Exception ignored) {
            }

            Map<String, Object> sum = Json.map("pur", (double) totPur, "eval", (double) totEv, "pl", (double) totPl,
                    "rate", totRate, "asset", (double) asset, "realized", realized, "count", (double) rows.size());
            return new Object[]{true, "잔고 " + rows.size() + "종목",
                    Json.map("summary", sum, "deposit", dep, "rows", rows, "time", Trader.hms(),
                            "mock", cfg.bool("kiwoom", "mock"))};
        } catch (Exception e) {
            return new Object[]{false, "잔고 조회 오류: " + e.getMessage()};
        }
    }

    /**
     * 당일매매 (매매일지)
     * @param date YYYYMMDD (빈값=오늘)
     * @param kind "sell"=당일매도(손익), "all"=당일매수+매도
     */
    public Object[] diary(String date, String kind) {
        try {
            String d = date == null ? "" : date.replace("-", "").trim();
            if (d.isEmpty()) d = today();
            if (!d.matches("\\d{8}")) return new Object[]{false, "날짜 형식이 올바르지 않습니다"};
            Engine.Rest rest = rest();
            Map<String, Object> js = rest.call("ka10170", "/api/dostk/acnt",
                    Json.map("base_dt", d, "ottks_tp", "all".equals(kind) ? "1" : "2", "ch_crd_tp", "0"));
            if (!ok(js)) return new Object[]{false, "매매일지 조회 실패: " + err(js)};
            List<Object> rows = new ArrayList<>();
            long sSell = 0, sBuy = 0, sFee = 0, sPl = 0;
            for (Object o : Json.arr(js.get("tdy_trde_diary"))) {
                Map<String, Object> m = Json.obj(o);
                String name = Json.s(m.get("stk_nm")).trim();
                String code = Engine.cleanCode(m.get("stk_cd"));
                if (name.isEmpty() && code.isEmpty()) continue;
                long sellAmt = Math.abs(n(m.get("sell_amt"))), buyAmt = Math.abs(n(m.get("buy_amt")));
                long fee = Math.abs(n(m.get("cmsn_alm_tax")));
                long pl = n(m.get("pl_amt"));
                sSell += sellAmt;
                sBuy += buyAmt;
                sFee += fee;
                sPl += pl;
                rows.add(Json.map("code", code, "name", name.isEmpty() ? code : name,
                        "buy_avg", (double) Math.abs(n(m.get("buy_avg_pric"))), "buy_qty", (double) Math.abs(n(m.get("buy_qty"))),
                        "sell_avg", (double) Math.abs(n(m.get("sel_avg_pric"))), "sell_qty", (double) Math.abs(n(m.get("sell_qty"))),
                        "buy_amt", (double) buyAmt, "sell_amt", (double) sellAmt, "fee", (double) fee,
                        "pl", (double) pl, "rate", r(m.get("prft_rt"))));
            }
            long sell = js.containsKey("tot_sell_amt") ? Math.abs(n(js.get("tot_sell_amt"))) : sSell;
            long buy = js.containsKey("tot_buy_amt") ? Math.abs(n(js.get("tot_buy_amt"))) : sBuy;
            long fee = js.containsKey("tot_cmsn_tax") ? Math.abs(n(js.get("tot_cmsn_tax"))) : sFee;
            long pl = js.containsKey("tot_pl_amt") ? n(js.get("tot_pl_amt")) : sPl;
            long exct = js.containsKey("tot_exct_amt") ? n(js.get("tot_exct_amt")) : sell - buy - fee;
            double rate = js.containsKey("tot_prft_rt") ? r(js.get("tot_prft_rt")) : 0;
            Map<String, Object> sum = Json.map("sell", (double) sell, "buy", (double) buy, "fee", (double) fee,
                    "pl", (double) pl, "rate", rate, "settle", (double) exct, "count", (double) rows.size());
            return new Object[]{true, "매매일지 " + rows.size() + "종목",
                    Json.map("summary", sum, "rows", rows, "date", d, "kind", "all".equals(kind) ? "all" : "sell",
                            "time", Trader.hms(), "mock", cfg.bool("kiwoom", "mock"))};
        } catch (Exception e) {
            return new Object[]{false, "매매일지 조회 오류: " + e.getMessage()};
        }
    }
}
