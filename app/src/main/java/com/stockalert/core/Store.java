package com.stockalert.core;

import java.io.File;
import java.io.FileOutputStream;
import java.io.FileInputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** 설정 저장소 + 로그 */
public final class Store {
    public static final int SLOT_COUNT = 5;
    private final File file;
    public final Object lock = new Object();
    public Map<String, Object> data;

    public Store(File file) {
        this.file = file;
        Map<String, Object> loaded = null;
        if (file.exists()) {
            try (FileInputStream in = new FileInputStream(file)) {
                loaded = Json.obj(Json.parse(Net.readAll(in)));
            } catch (Exception e) {
                Logx.add("설정 파일 읽기 실패, 기본값 사용: " + e.getMessage(), "error");
            }
        }
        data = merge(defaults(), loaded);
        List<Object> slots = Json.arr(data.get("slots"));
        List<Object> fixed = new ArrayList<>();
        for (int i = 0; i < SLOT_COUNT; i++) {
            fixed.add(i < slots.size() ? slots.get(i) : Json.map("enabled", false, "seq", "", "name", "", "buy", false));
        }
        data.put("slots", fixed);
        save();
    }

    public static Map<String, Object> defaults() {
        List<Object> slots = new ArrayList<>();
        for (int i = 0; i < SLOT_COUNT; i++) slots.add(Json.map("enabled", false, "seq", "", "name", ""));
        return Json.map(
                "kiwoom", Json.map("appkey", "", "secretkey", "", "mock", true),
                "kakao", Json.map("rest_key", "", "client_secret", "", "recipients", new ArrayList<Object>()),
                "slots", slots,
                "watchlist", "",
                "alerts", Json.map(
                        "on_enter", true, "on_exit", false,
                        "day_step", 3.0, "entry_step", 3.0,
                        "surge_pct", 2.0, "surge_min", 3.0,
                        "index_step", 1.0, "cooldown_min", 5.0,
                        "keep_after_exit", true, "max_watch", 100.0, "quiet", false,
                        "phone_notify", true, "kakao_notify", true, "autostart", true,
                        "scan_min", 0.0),
                // 종목별 개별 알림 설정 {종목코드: {mute, day_step, entry_step, surge_pct, surge_min, price_up, price_dn}}
                "stock_alerts", new java.util.LinkedHashMap<String, Object>(),
                // 지수별 알림 설정 (기본 켜짐)
                "index_alerts", Json.map(
                        "001", Json.map("enabled", true, "step", 1.0, "surge_pct", 0.5, "surge_min", 5.0, "price_up", 0.0, "price_dn", 0.0),
                        "101", Json.map("enabled", true, "step", 1.0, "surge_pct", 0.7, "surge_min", 5.0, "price_up", 0.0, "price_dn", 0.0)),
                // 자동매매 (기본 꺼짐, 실전 주문은 별도 허용 필요)
                "trade", Json.map("enabled", false, "real_ok", false, "buy_amount", 500000.0, "order_type", "market",
                        "take_profit", 3.0, "stop_loss", 2.0, "trail_pct", 0.0,
                        "max_buys", 10.0, "max_positions", 5.0, "max_loss", 300000.0,
                        "buy_start", "09:05", "buy_end", "15:00", "close_time", "15:15",
                        "index_stop", 1.5, "index_sell", 3.0, "bot_only", true,
                        "index_rules", new ArrayList<Object>()),
                "ui", Json.map("port", 8080.0, "password", ""));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> merge(Map<String, Object> base, Map<String, Object> over) {
        if (over == null) return base;
        for (Map.Entry<String, Object> e : over.entrySet()) {
            Object bv = base.get(e.getKey());
            if (bv instanceof Map && e.getValue() instanceof Map) {
                base.put(e.getKey(), merge((Map<String, Object>) bv, (Map<String, Object>) e.getValue()));
            } else {
                base.put(e.getKey(), e.getValue());
            }
        }
        return base;
    }

    public void save() {
        synchronized (lock) {
            try {
                File tmp = new File(file.getPath() + ".tmp");
                try (FileOutputStream o = new FileOutputStream(tmp)) {
                    o.write(Json.stringify(data).getBytes(StandardCharsets.UTF_8));
                }
                if (!tmp.renameTo(file)) {
                    try (FileOutputStream o = new FileOutputStream(file)) {
                        o.write(Json.stringify(data).getBytes(StandardCharsets.UTF_8));
                    }
                    tmp.delete();
                }
            } catch (Exception e) {
                Logx.add("설정 저장 실패: " + e.getMessage(), "error");
            }
        }
    }

    public Map<String, Object> sec(String name) {
        synchronized (lock) {
            return Json.obj(data.get(name));
        }
    }

    /** 섹션 사본 (읽기용) */
    public Map<String, Object> copy(String name) {
        synchronized (lock) {
            return Json.obj(Json.copy(data.get(name)));
        }
    }

    public String str(String sec, String key) {
        synchronized (lock) {
            return Json.s(sec(sec).get(key));
        }
    }

    public double num(String sec, String key) {
        synchronized (lock) {
            return Json.d(sec(sec).get(key), 0);
        }
    }

    public boolean bool(String sec, String key) {
        synchronized (lock) {
            return Json.b(sec(sec).get(key));
        }
    }

    // ───────── 로그 ─────────
    public static final class Logx {
        private static final ArrayDeque<Map<String, Object>> LINES = new ArrayDeque<>();
        private static final ArrayDeque<Map<String, Object>> ALERTS = new ArrayDeque<>();

        public static void add(String msg, String level) {
            Map<String, Object> l = Json.map("t", now("HH:mm:ss"), "lv", level, "msg", msg);
            synchronized (LINES) {
                LINES.addLast(l);
                while (LINES.size() > 400) LINES.removeFirst();
            }
            System.out.println("[" + l.get("t") + "] " + msg);
        }

        public static void add(String msg) { add(msg, "info"); }

        public static void alert(String msg) {
            synchronized (ALERTS) {
                ALERTS.addLast(Json.map("t", now("MM-dd HH:mm:ss"), "msg", msg));
                while (ALERTS.size() > 200) ALERTS.removeFirst();
            }
        }

        public static List<Object> lines(int n) {
            synchronized (LINES) {
                List<Object> all = new ArrayList<Object>(LINES);
                return all.subList(Math.max(0, all.size() - n), all.size());
            }
        }

        /** 최신순 */
        public static List<Object> alerts(int n) {
            synchronized (ALERTS) {
                List<Object> all = new ArrayList<Object>(ALERTS);
                List<Object> out = new ArrayList<>();
                for (int i = all.size() - 1; i >= 0 && out.size() < n; i--) out.add(all.get(i));
                return out;
            }
        }

        public static String now(String fmt) {
            return new SimpleDateFormat(fmt, Locale.KOREA).format(new Date());
        }
    }
}
