package com.stockalert.core;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 엑셀 리포트
 *  1) 조건검색 검출 리포트: 조건명/종목명/검출시점/검출시 등락률/검출후 등락률/금일 최저·최고(%)
 *  2) 금일 거래내역: 조건명/종목명/매수금액/손익/수익률/매수·매도 시간/매수후 최저·최고(%)
 */
public final class Reports {
    private final Engine eng;
    private final Trader tr;
    private final Account acc;

    public Reports(Engine eng, Trader tr, Account acc) {
        this.eng = eng;
        this.tr = tr;
        this.acc = acc;
    }

    static String hms(long ms) {
        if (ms <= 0) return "";
        SimpleDateFormat f = new SimpleDateFormat("HH:mm:ss", Locale.KOREA);
        f.setTimeZone(Trader.KST);
        return f.format(new Date(ms));
    }

    static String today(String fmt) {
        SimpleDateFormat f = new SimpleDateFormat(fmt, Locale.KOREA);
        f.setTimeZone(Trader.KST);
        return f.format(new Date());
    }

    static Double pct(double a, double base) {
        return a > 0 && base > 0 ? (a / base - 1) * 100 : null;
    }

    static Object P(Double v) {
        return v == null ? null : new Xlsx.Pct(v);
    }

    /** 종목 현재가·금일 고가·저가·기준가 (ka10001) */
    static final class Quote {
        double cur, rate, high, low, base;
    }

    private Map<String, Quote> quotes(List<String> codes, int max) {
        Map<String, Quote> out = new HashMap<>();
        Engine.Rest rest;
        try {
            rest = acc.restForQuery();
        } catch (Exception e) {
            return out;
        }
        int n = 0;
        for (String code : codes) {
            if (n++ >= max) break;
            try {
                Map<String, Object> js = rest.call("ka10001", "/api/dostk/stkinfo", Json.map("stk_cd", code));
                if (!"0".equals(Json.s(js.get("return_code")).trim())) continue;
                Quote q = new Quote();
                q.cur = Math.abs(Account.r(js.get("cur_prc")));
                q.rate = Engine.fixRate(Account.r(js.get("flu_rt")));
                q.high = Math.abs(Account.r(js.get("high_pric")));
                q.low = Math.abs(Account.r(js.get("low_pric")));
                q.base = Math.abs(Account.r(js.get("base_pric")));
                if (q.base <= 0 && q.cur > 0) q.base = q.cur / (1 + q.rate / 100);
                out.put(code, q);
            } catch (Exception ignored) {
            }
        }
        return out;
    }

    // ───────── 1) 조건검색 검출 리포트 ─────────
    public Object[] condReport() {
        try {
            List<Engine.Det> ds = eng.detections();
            List<String> codes = new ArrayList<>();
            for (Engine.Det d : ds) if (!codes.contains(d.code)) codes.add(d.code);
            Map<String, Quote> qs = quotes(codes, 150);
            ds.sort((a, b) -> a.cond.equals(b.cond) ? Long.compare(a.first, b.first) : a.cond.compareTo(b.cond));
            List<Object[]> rows = new ArrayList<>();
            Map<String, double[]> sum = new LinkedHashMap<>();   // 조건 → {종목수, 검출후합, 상승수, 최고, 최저, 평균최고합}
            for (Engine.Det d : ds) {
                Quote q = qs.get(d.code);
                double cur = q != null && q.cur > 0 ? q.cur : eng.priceOf(d.code);
                double curRate = q != null && q.cur > 0 ? q.rate : eng.rateOf(d.code);
                Double after = pct(cur, d.p0);
                Double aLo = pct(d.lo, d.p0), aHi = pct(d.hi, d.p0);
                Double dLo = q != null ? pct(q.low, q.base) : null, dHi = q != null ? pct(q.high, q.base) : null;
                String nm = eng.nameOf(d.code);
                rows.add(new Object[]{d.cond, nm, d.code, hms(d.first), hms(d.last), (double) d.hits,
                        d.p0 > 0 ? new Xlsx.Won(d.p0, false) : null, d.p0 > 0 ? new Xlsx.Pct(d.r0) : null,
                        cur > 0 ? new Xlsx.Won(cur, false) : null, cur > 0 ? new Xlsx.Pct(curRate) : null,
                        P(after), P(aLo), P(aHi), P(dLo), P(dHi)});
                double[] s = sum.get(d.cond);
                if (s == null) sum.put(d.cond, s = new double[]{0, 0, 0, -1e9, 1e9, 0, 0});
                s[0]++;
                if (after != null) {
                    s[1] += after;
                    s[6]++;
                    if (after > 0) s[2]++;
                    s[3] = Math.max(s[3], after);
                    s[4] = Math.min(s[4], after);
                }
                if (aHi != null) s[5] += aHi;
            }
            Xlsx x = new Xlsx();
            String title = "조건검색 검출 리포트 · " + today("yyyy-MM-dd HH:mm") + " 기준";
            x.sheet("검출목록", title, new String[]{"조건명", "종목명", "종목코드", "최초 검출시각", "마지막 검출", "검출 횟수",
                            "검출가", "검출시 등락률(%)", "현재가", "현재 등락률(%)", "검출후 등락률(%)", "검출후 최저(%)", "검출후 최고(%)",
                            "금일 최저점(%)", "금일 최고점(%)"},
                    rows, new double[]{16, 16, 9, 11, 11, 8, 10, 11, 10, 11, 11, 11, 11, 11, 11});
            List<Object[]> srows = new ArrayList<>();
            for (Map.Entry<String, double[]> e : sum.entrySet()) {
                double[] s = e.getValue();
                double n = s[6];
                srows.add(new Object[]{e.getKey(), s[0], n > 0 ? new Xlsx.Pct(s[1] / n) : null, s[2],
                        n > 0 ? new Xlsx.Pct(s[2] * 100 / n) : null, n > 0 ? new Xlsx.Pct(s[3]) : null, n > 0 ? new Xlsx.Pct(s[4]) : null,
                        n > 0 ? new Xlsx.Pct(s[5] / s[0]) : null});
            }
            x.sheet("조건식별 요약", "조건식별 요약 · " + today("yyyy-MM-dd HH:mm"),
                    new String[]{"조건명", "검출 종목 수", "평균 검출후 등락률(%)", "상승 종목 수", "상승 비율(%)", "최고 검출후(%)", "최저 검출후(%)", "평균 검출후 최고(%)"},
                    srows, new double[]{18, 11, 14, 11, 11, 12, 12, 14});
            x.sheet("설명", null, new String[]{"항목", "설명"}, notes(new String[][]{
                    {"검출가 / 검출시 등락률", "조건식에 처음 검출됐을 때의 가격과 전일 대비 등락률"},
                    {"검출후 등락률", "현재가 ÷ 검출가 − 1"},
                    {"검출후 최저/최고", "검출 이후 앱이 받은 체결가 중 최저·최고 (검출가 대비). 감시 중일 때만 기록됨"},
                    {"금일 최저점/최고점", "오늘 저가·고가의 전일 종가(기준가) 대비 % (키움 ka10001 조회값)"},
                    {"검출 횟수", "오늘 그 조건식에 잡힌 횟수 (실시간 편입 + 주기 검색 재편입)"},
                    {"현재가", "리포트를 만든 시각의 가격 (최대 150종목 조회, 나머지는 앱의 마지막 가격)"}}), new double[]{22, 80});
            return new Object[]{true, "검출 " + rows.size() + "건", x.build(), "조건검색리포트_" + today("yyyyMMdd_HHmm") + ".xlsx"};
        } catch (Exception e) {
            return new Object[]{false, "리포트 만들기 실패: " + e.getMessage()};
        }
    }

    private static List<Object[]> notes(String[][] a) {
        List<Object[]> l = new ArrayList<>();
        for (String[] r : a) l.add(new Object[]{r[0], r[1]});
        return l;
    }

    // ───────── 2) 금일 거래내역 ─────────
    public Object[] tradeReport() {
        try {
            Map<String, Map<String, Object>> rec = new LinkedHashMap<>();
            for (Map<String, Object> m : tr.tradeRecords()) rec.put(Json.s(m.get("code")), m);
            // 키움 당일매매일지 (매수+매도 전체)
            Object[] dr = acc.diary("", "all");
            List<Object> drows = new ArrayList<>();
            Map<String, Object> dsum = null;
            String diaryErr = "";
            if ((Boolean) dr[0]) {
                Map<String, Object> d = Json.obj(dr[2]);
                drows = Json.arr(d.get("rows"));
                dsum = Json.obj(d.get("summary"));
            } else {
                diaryErr = String.valueOf(dr[1]);
            }
            Map<String, Map<String, Object>> dmap = new LinkedHashMap<>();
            for (Object o : drows) {
                Map<String, Object> m = Json.obj(o);
                dmap.put(Json.s(m.get("code")), m);
            }
            List<String> codes = new ArrayList<>(dmap.keySet());
            for (String c : rec.keySet()) if (!codes.contains(c)) codes.add(c);
            List<Object[]> rows = new ArrayList<>();
            double tBuy = 0, tSell = 0, tPl = 0, tFee = 0;
            for (String code : codes) {
                Map<String, Object> d = dmap.get(code), r = rec.get(code);
                String name = d != null ? Json.s(d.get("name")) : Json.s(r.get("name"));
                if (name.isEmpty() || name.equals(code)) name = eng.nameOf(code);
                double buyAvg = d != null ? Json.d(d.get("buy_avg"), 0) : 0;
                if (buyAvg <= 0 && r != null) buyAvg = Json.d(r.get("avg"), Json.d(r.get("ref"), 0));
                double buyQty = d != null ? Json.d(d.get("buy_qty"), 0) : 0;
                double buyAmt = d != null ? Json.d(d.get("buy_amt"), 0) : 0;
                double sellAvg = d != null ? Json.d(d.get("sell_avg"), 0) : 0;
                double sellQty = d != null ? Json.d(d.get("sell_qty"), 0) : 0;
                double sellAmt = d != null ? Json.d(d.get("sell_amt"), 0) : 0;
                double fee = d != null ? Json.d(d.get("fee"), 0) : 0;
                Double pl = d != null ? Json.d(d.get("pl"), 0) : null;
                Double rate = d != null ? Json.d(d.get("rate"), 0) : null;
                if (d == null && r != null) {          // 키움 일지에 없으면 앱 주문 기록으로 추정
                    buyQty = Json.d(r.get("buy_qty"), 0);
                    buyAmt = Json.d(r.get("buy_amt"), 0);
                    sellQty = Json.d(r.get("sell_qty"), 0);
                    sellAmt = Json.d(r.get("sell_amt"), 0);
                    if (sellQty > 0) sellAvg = sellAmt / sellQty;
                    if (sellQty > 0 && buyAvg > 0) {
                        pl = sellAmt - buyAvg * sellQty;
                        rate = (sellAmt / (buyAvg * sellQty) - 1) * 100;
                    }
                }
                double lo = r != null ? Json.d(r.get("lo"), 0) : 0, hi = r != null ? Json.d(r.get("hi"), 0) : 0;
                String cond = r != null ? Json.s(r.get("cond")) : "";
                String br = r != null ? Json.s(r.get("buy_reason")) : "";
                if (cond.isEmpty()) cond = r == null ? "(앱 밖 매매)" : br.startsWith("🎯") ? "매수 예약" : br.isEmpty() ? "" : br;
                String sellT = r != null ? Json.s(r.get("sell_t")) : "";
                if (sellT.isEmpty() && r != null && Json.b(r.get("open"))) sellT = "보유 중";
                tBuy += buyAmt;
                tSell += sellAmt;
                tFee += fee;
                if (pl != null) tPl += pl;
                rows.add(new Object[]{cond, name, code, r != null ? Json.s(r.get("buy_t")) : "", sellT,
                        buyQty > 0 ? buyQty : null, buyAvg > 0 ? new Xlsx.Won(buyAvg, false) : null, buyAmt > 0 ? new Xlsx.Won(buyAmt, false) : null,
                        sellAvg > 0 ? new Xlsx.Won(sellAvg, false) : null, sellQty > 0 ? sellQty : null, sellAmt > 0 ? new Xlsx.Won(sellAmt, false) : null,
                        pl != null ? new Xlsx.Won(pl, true) : null, rate != null ? new Xlsx.Pct(rate) : null, fee > 0 ? new Xlsx.Won(fee, false) : null,
                        P(pct(lo, buyAvg)), P(pct(hi, buyAvg)),
                        r != null ? Json.s(r.get("sell_reason")) : ""});
            }
            if (dsum != null) {
                tBuy = Json.d(dsum.get("buy"), tBuy);
                tSell = Json.d(dsum.get("sell"), tSell);
                tPl = Json.d(dsum.get("pl"), tPl);
                tFee = Json.d(dsum.get("fee"), tFee);
            }
            rows.add(new Object[0]);
            rows.add(new Object[]{"합계", rows.size() - 1 + "종목", null, null, null, null, null, new Xlsx.Won(tBuy, false), null, null,
                    new Xlsx.Won(tSell, false), new Xlsx.Won(tPl, true), dsum != null ? new Xlsx.Pct(Json.d(dsum.get("rate"), 0)) : null,
                    new Xlsx.Won(tFee, false), null, null, dsum != null ? "정산금액 " + Engine.won(Json.d(dsum.get("settle"), 0)) + "원" : ""});
            Xlsx x = new Xlsx();
            String title = "금일 거래내역 · " + today("yyyy-MM-dd HH:mm") + " 기준" + (diaryErr.isEmpty() ? "" : "  (키움 매매일지 조회 실패: " + diaryErr + ")");
            x.sheet("거래내역", title, new String[]{"검출 조건명", "종목명", "종목코드", "매수시간", "매도시간", "매수수량", "매수평균가", "매수금액",
                            "매도평균가", "매도수량", "매도금액", "손익금액", "수익률(%)", "수수료+세금", "매수후 최저점(%)", "매수후 최고점(%)", "매도 사유"},
                    rows, new double[]{16, 16, 9, 10, 10, 8, 10, 12, 10, 8, 12, 11, 9, 10, 11, 11, 30});
            List<Object[]> orows = new ArrayList<>();
            List<Map<String, Object>> ol = tr.orderList();
            for (int i = ol.size() - 1; i >= 0; i--) {
                Map<String, Object> o = ol.get(i);
                orows.add(new Object[]{Json.s(o.get("t")), Json.s(o.get("side")), Json.s(o.get("name")), Json.s(o.get("code")),
                        Json.d(o.get("qty"), 0), Json.d(o.get("price"), 0) > 0 ? new Xlsx.Won(Json.d(o.get("price"), 0), false) : null,
                        Json.b(o.get("ok")) ? "접수" : "실패", Json.s(o.get("reason")), Json.s(o.get("msg"))});
            }
            x.sheet("주문내역", "앱 주문 내역 · " + today("yyyy-MM-dd"), new String[]{"시각", "구분", "종목명", "종목코드", "수량", "주문 기준가", "결과", "사유", "메시지"},
                    orows, new double[]{9, 6, 16, 9, 7, 11, 7, 34, 30});
            x.sheet("설명", null, new String[]{"항목", "설명"}, notes(new String[][]{
                    {"매수/매도 금액·손익·수익률·수수료", "키움 당일매매일지(ka10170) 값"},
                    {"검출 조건명", "앱이 조건식 자동매수로 산 경우 조건식 이름. ‘매수 예약’, ‘수동 매수’, 앱 밖에서 거래한 종목은 ‘(앱 밖 매매)’"},
                    {"매수시간 / 매도시간", "앱이 주문을 낸 시각 (첫 매수 · 마지막 매도). 앱 밖 거래는 비어 있음"},
                    {"앱 기록 추정", "키움 일지에 아직 없는 종목은 앱 주문 기준가로 추정 (수수료 제외)"},
                    {"매수후 최저점/최고점", "매수 주문 뒤 매도할 때까지 앱이 받은 체결가 중 최저·최고 (매수평균가 대비 %)"}}), new double[]{26, 80});
            return new Object[]{true, "거래 " + codes.size() + "종목", x.build(), "금일거래내역_" + today("yyyyMMdd_HHmm") + ".xlsx"};
        } catch (Exception e) {
            return new Object[]{false, "리포트 만들기 실패: " + e.getMessage()};
        }
    }
}
