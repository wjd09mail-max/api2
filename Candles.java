package com.stockalert.core;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 분봉 (ka10080 주식분봉차트조회요청  POST /api/dostk/chart)
 *  body {stk_cd, tic_scope: 1/3/5/10/15/30/45/60, upd_stkpc_tp: "1"}
 *  응답 stk_min_pole_chart_qry[] {cntr_tm YYYYMMDDHHmmss, open_pric, cur_prc(종가), high_pric, low_pric} 최신순
 *
 * 봉 시각(cntr_tm)이 '시작 시각'인지 '끝 시각'인지 문서마다 달라서,
 * 오늘 첫 봉이 09:00 이면 시작 시각, 09:00+n분 이면 끝 시각으로 판단합니다.
 * 둘 다 아니면 가장 최근 봉은 아직 만들어지는 중으로 보고 버립니다.
 */
final class Candles {
    static final int[] SCOPES = {1, 3, 5, 10, 15, 30, 45, 60};

    static final class Bar {
        long end;          // 봉이 끝나는 시각 (ms)
        double open, close;

        boolean up() {
            return close > open;
        }

        boolean down() {
            return close < open;
        }
    }

    static boolean validScope(int m) {
        for (int s : SCOPES) if (s == m) return true;
        return false;
    }

    static long parse(String t) {
        try {
            SimpleDateFormat f = new SimpleDateFormat("yyyyMMddHHmmss", Locale.KOREA);
            f.setTimeZone(Trader.KST);
            return f.parse(t).getTime();
        } catch (Exception e) {
            return 0;
        }
    }

    static long todayAt(int h, int m) {
        Calendar c = Calendar.getInstance(Trader.KST);
        c.set(Calendar.HOUR_OF_DAY, h);
        c.set(Calendar.MINUTE, m);
        c.set(Calendar.SECOND, 0);
        c.set(Calendar.MILLISECOND, 0);
        return c.getTimeInMillis();
    }

    /** 완성된 봉 (최신순) */
    static List<Bar> completed(Engine.Rest rest, String code, int min, long now) throws Exception {
        Map<String, Object> r = rest.call("ka10080", "/api/dostk/chart",
                Json.map("stk_cd", code, "tic_scope", String.valueOf(min), "upd_stkpc_tp", "1"));
        if (!"0".equals(Json.s(r.get("return_code")).trim())) {
            String m = Json.s(r.get("return_msg"));
            throw new Exception(m.isEmpty() ? "분봉 응답 없음" : m);
        }
        return completed(Json.arr(r.get("stk_min_pole_chart_qry")), min, now);
    }

    static List<Bar> completed(List<Object> raw, int min, long now) {
        List<long[]> times = new ArrayList<>();
        List<double[]> px = new ArrayList<>();
        long open = todayAt(9, 0), step = min * 60_000L;
        long first = Long.MAX_VALUE;
        for (Object o : raw) {
            Map<String, Object> m = Json.obj(o);
            long t = parse(Json.s(m.get("cntr_tm")).trim());
            double c = Math.abs(Account.r(m.get("cur_prc"))), op = Math.abs(Account.r(m.get("open_pric")));
            if (t <= 0 || c <= 0 || op <= 0) continue;
            times.add(new long[]{t});
            px.add(new double[]{op, c});
            if (t >= open - 3600_000L && t < first) first = t;
        }
        // 0: 시작 시각, 1: 끝 시각, -1: 모름
        int conv = first == open ? 0 : first == open + step ? 1 : -1;
        List<Bar> out = new ArrayList<>();
        for (int i = 0; i < times.size(); i++) {
            long t = times.get(i)[0];
            Bar b = new Bar();
            b.end = conv == 1 ? t : t + step;
            b.open = px.get(i)[0];
            b.close = px.get(i)[1];
            if (conv == -1 && i == 0) continue;            // 모르면 최신 봉은 미완성으로 간주
            if (b.end > now) continue;                      // 아직 진행 중인 봉
            out.add(b);
        }
        out.sort((a, b) -> Long.compare(b.end, a.end));
        return out;
    }

    /** 최신 완성봉부터 연속으로 조건(up/down)을 만족하는 개수. since 이후에 끝난 봉만 셈 */
    static int streak(List<Bar> bars, boolean up, long since) {
        int n = 0;
        for (Bar b : bars) {
            if (b.end <= since) break;
            if (up ? b.up() : b.down()) n++;
            else break;
        }
        return n;
    }
}
