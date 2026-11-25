package com.stockalert.core;

import com.stockalert.core.Store.Logx;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 전 종목 이름표 (종목명 검색용)
 *  - ka10099 종목정보 리스트 POST /api/dostk/stkinfo  {mrkt_tp: "0"코스피 / "10"코스닥}
 *  - stocks.json 에 저장, 3일마다 새로 받음
 *  - 검색: 종목코드, 이름 일부, 초성(ㅅㅅㅈㅈ → 삼성전자)
 */
public final class Stocks {
    private static final char[] CHO = {'ㄱ', 'ㄲ', 'ㄴ', 'ㄷ', 'ㄸ', 'ㄹ', 'ㅁ', 'ㅂ', 'ㅃ', 'ㅅ', 'ㅆ', 'ㅇ', 'ㅈ', 'ㅉ', 'ㅊ', 'ㅋ', 'ㅌ', 'ㅍ', 'ㅎ'};

    static final class Item {
        String code, name, market, key, cho;
    }

    private final Store cfg;
    private final Account acc;
    private final File file;
    private volatile List<Item> items = new ArrayList<>();
    private volatile long loadedAt = 0;
    private volatile String lastErr = "";
    private volatile boolean loading = false;

    public Stocks(Store cfg, Account acc, File file) {
        this.cfg = cfg;
        this.acc = acc;
        this.file = file;
        loadFile();
    }

    static String norm(String s) {
        return s == null ? "" : s.replace(" ", "").toLowerCase(Locale.ROOT);
    }

    static String cho(String s) {
        StringBuilder b = new StringBuilder();
        for (char c : s.toCharArray()) {
            if (c >= 0xAC00 && c <= 0xD7A3) b.append(CHO[(c - 0xAC00) / 588]);
            else b.append(Character.toLowerCase(c));
        }
        return b.toString();
    }

    private static boolean allCho(String q) {
        if (q.isEmpty()) return false;
        for (char c : q.toCharArray()) if (c < 0x3131 || c > 0x314E) return false;
        return true;
    }

    private void set(List<Item> list, long at) {
        for (Item it : list) {
            it.key = norm(it.name);
            it.cho = cho(it.key);
        }
        items = list;
        loadedAt = at;
    }

    private void loadFile() {
        if (file == null || !file.exists()) return;
        try (FileInputStream in = new FileInputStream(file)) {
            Map<String, Object> m = Json.obj(Json.parse(Net.readAll(in)));
            List<Item> list = new ArrayList<>();
            for (Object o : Json.arr(m.get("items"))) {
                List<Object> a = Json.arr(o);
                if (a.size() < 2) continue;
                Item it = new Item();
                it.code = Json.s(a.get(0));
                it.name = Json.s(a.get(1));
                it.market = a.size() > 2 ? Json.s(a.get(2)) : "";
                list.add(it);
            }
            set(list, (long) Json.d(m.get("at"), 0));
        } catch (Exception e) {
            Logx.add("종목 목록 읽기 실패: " + e.getMessage(), "warn");
        }
    }

    public int size() {
        return items.size();
    }

    /** 목록이 없거나 오래됐으면 새로 받기 (호출 스레드에서 실행) */
    public synchronized String ensure(boolean force) {
        boolean stale = items.isEmpty() || System.currentTimeMillis() - loadedAt > 3L * 86400_000L;
        if (!force && !stale) return "";
        loading = true;
        try {
            Engine.Rest rest = acc.restForQuery();
            List<Item> list = new ArrayList<>();
            String[][] mk = {{"0", "코스피"}, {"10", "코스닥"}};
            for (String[] m : mk) {
                Map<String, Object> r = rest.callAll("ka10099", "/api/dostk/stkinfo", Json.map("mrkt_tp", m[0]), "list", 20);
                if (!"0".equals(Json.s(r.get("return_code")).trim())) {
                    String msg = Json.s(r.get("return_msg"));
                    throw new Exception(msg.isEmpty() ? "응답 없음" : msg);
                }
                for (Object o : Json.arr(r.get("list"))) {
                    Map<String, Object> x = Json.obj(o);
                    String code = Engine.cleanCode(x.get("code"));
                    String name = Json.s(x.get("name")).trim();
                    if (code.isEmpty() || name.isEmpty()) continue;
                    Item it = new Item();
                    it.code = code;
                    it.name = name;
                    it.market = m[1];
                    list.add(it);
                }
            }
            if (list.isEmpty()) throw new Exception("종목 목록이 비어 있습니다");
            long now = System.currentTimeMillis();
            set(list, now);
            List<Object> rows = new ArrayList<>();
            for (Item it : list) {
                List<Object> a = new ArrayList<>();
                a.add(it.code);
                a.add(it.name);
                a.add(it.market);
                rows.add(a);
            }
            if (file != null) {
                try (FileOutputStream o = new FileOutputStream(file)) {
                    o.write(Json.stringify(Json.map("at", (double) now, "items", rows)).getBytes(StandardCharsets.UTF_8));
                }
            }
            lastErr = "";
            Logx.add("종목 목록 " + list.size() + "개 받음");
            return "";
        } catch (Exception e) {
            lastErr = String.valueOf(e.getMessage());
            Logx.add("종목 목록 받기 실패: " + lastErr, "warn");
            return lastErr;
        } finally {
            loading = false;
        }
    }

    public Object[] search(String q0, Engine eng) {
        String q = norm(q0);
        if (q.isEmpty()) return new Object[]{false, "검색어를 입력하세요"};
        String err = ensure(false);
        List<Item> all = items;
        List<Object> out = new ArrayList<>();
        if (all.isEmpty()) {
            // 목록을 못 받았으면 6자리 코드만 처리
            String code = Engine.cleanCode(q);
            if (code.length() == 6) {
                out.add(Json.map("code", code, "name", eng.nameOf(code), "market", ""));
                return new Object[]{true, "종목 목록 없음 — 코드로만 검색", Json.map("rows", out)};
            }
            return new Object[]{false, "종목 목록을 받지 못했습니다: " + err + " (키 입력·IP 등록 확인)"};
        }
        boolean ch = allCho(q);
        List<Item> exact = new ArrayList<>(), pre = new ArrayList<>(), mid = new ArrayList<>();
        for (Item it : all) {
            if (it.code.equals(q) || it.key.equals(q)) exact.add(it);
            else if (it.code.startsWith(q) || it.key.startsWith(q) || (ch && it.cho.startsWith(q))) pre.add(it);
            else if (it.key.contains(q) || (ch && it.cho.contains(q))) mid.add(it);
        }
        java.util.Comparator<Item> byLen = (a, b) -> a.name.length() != b.name.length() ? a.name.length() - b.name.length() : a.name.compareTo(b.name);
        pre.sort(byLen);
        mid.sort(byLen);
        List<Item> res = new ArrayList<>(exact);
        res.addAll(pre);
        res.addAll(mid);
        for (int i = 0; i < res.size() && i < 30; i++) {
            Item it = res.get(i);
            out.add(Json.map("code", it.code, "name", it.name, "market", it.market));
        }
        return new Object[]{true, res.size() + "건", Json.map("rows", out, "total", (double) res.size())};
    }
}
