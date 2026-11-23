package com.stockalert.core;

import com.stockalert.core.Store.Logx;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/** 카카오톡 '나에게 보내기' + 폰 알림 분배 */
public final class Kakao {
    public interface Notifier {
        void alert(String text);
        void status(String text);
    }

    static String KAUTH = "https://kauth.kakao.com";
    static String KAPI = "https://kapi.kakao.com";
    private static final int LIMIT = 190;

    private final Store cfg;
    private final Notifier phone;
    private final LinkedBlockingQueue<String> q = new LinkedBlockingQueue<>();
    private final Map<String, Object[]> pending = new HashMap<>();
    private final SecureRandom rnd = new SecureRandom();
    private volatile boolean warnedEmpty = false;

    public Kakao(Store cfg, Notifier phone) {
        this.cfg = cfg;
        this.phone = phone;
        Thread t = new Thread(this::worker, "kakao");
        t.setDaemon(true);
        t.start();
    }

    public static void setBases(String auth, String api) {
        if (auth != null && !auth.isEmpty()) KAUTH = auth;
        if (api != null && !api.isEmpty()) KAPI = api;
    }

    public String redirectUri() {
        return "http://localhost:" + (int) cfg.num("ui", "port") + "/kakao/callback";
    }

    public String authUrl(String name) {
        byte[] b = new byte[12];
        rnd.nextBytes(b);
        String state = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(b);
        synchronized (pending) {
            pending.put(state, new Object[]{name == null || name.isEmpty() ? "나" : name, System.currentTimeMillis()});
        }
        return KAUTH + "/oauth/authorize?response_type=code&client_id=" + Net.enc(cfg.str("kakao", "rest_key"))
                + "&redirect_uri=" + Net.enc(redirectUri()) + "&state=" + Net.enc(state) + "&scope=talk_message";
    }

    private Map<String, String> baseForm() {
        Map<String, String> f = new LinkedHashMap<>();
        f.put("client_id", cfg.str("kakao", "rest_key"));
        String cs = cfg.str("kakao", "client_secret");
        if (!cs.isEmpty()) f.put("client_secret", cs);
        return f;
    }

    public Map<String, Object> exchange(String code, String state) throws Exception {
        Object[] info;
        synchronized (pending) {
            info = pending.remove(state);
        }
        if (info == null || System.currentTimeMillis() - (Long) info[1] > 900_000)
            throw new Exception("만료되었거나 잘못된 요청입니다. 앱에서 다시 '카카오 연결'을 눌러 주세요.");
        Map<String, String> f = baseForm();
        f.put("grant_type", "authorization_code");
        f.put("redirect_uri", redirectUri());
        f.put("code", code);
        Net.Resp r = Net.postForm(KAUTH + "/oauth/token", null, f);
        if (r.status != 200 || !r.json.containsKey("access_token"))
            throw new Exception("토큰 발급 실패(" + r.status + "): " + desc(r.json));
        long now = System.currentTimeMillis() / 1000;
        Map<String, Object> rc = Json.map(
                "name", info[0],
                "access_token", Json.s(r.json.get("access_token")),
                "refresh_token", Json.s(r.json.get("refresh_token")),
                "expires_at", (double) (now + (long) Json.d(r.json.get("expires_in"), 21599)),
                "refresh_expires_at", (double) (now + (long) Json.d(r.json.get("refresh_token_expires_in"), 5183999)),
                "scope", Json.s(r.json.get("scope")));
        synchronized (cfg.lock) {
            List<Object> lst = Json.arr(cfg.sec("kakao").get("recipients"));
            List<Object> keep = new ArrayList<>();
            for (Object o : lst) if (!Json.s(Json.obj(o).get("name")).equals(info[0])) keep.add(o);
            keep.add(rc);
            cfg.sec("kakao").put("recipients", keep);
            cfg.save();
        }
        warnedEmpty = false;
        Logx.add("카카오 수신자 '" + info[0] + "' 연결 완료");
        return rc;
    }

    private static String desc(Map<String, Object> js) {
        Object d = js.get("error_description");
        return d != null ? Json.s(d) : Json.stringify(js);
    }

    private void refresh(Map<String, Object> rc) throws Exception {
        Map<String, String> f = baseForm();
        f.put("grant_type", "refresh_token");
        f.put("refresh_token", Json.s(rc.get("refresh_token")));
        Net.Resp r = Net.postForm(KAUTH + "/oauth/token", null, f);
        if (r.status != 200 || !r.json.containsKey("access_token"))
            throw new Exception("카카오 토큰 갱신 실패(" + r.status + "): " + desc(r.json) + ". 다시 연결해 주세요.");
        long now = System.currentTimeMillis() / 1000;
        synchronized (cfg.lock) {
            rc.put("access_token", Json.s(r.json.get("access_token")));
            rc.put("expires_at", (double) (now + (long) Json.d(r.json.get("expires_in"), 21599)));
            if (r.json.get("refresh_token") != null) {
                rc.put("refresh_token", Json.s(r.json.get("refresh_token")));
                rc.put("refresh_expires_at", (double) (now + (long) Json.d(r.json.get("refresh_token_expires_in"), 5183999)));
            }
        }
    }

    private void sendOne(Map<String, Object> rc, String text) throws Exception {
        long now = System.currentTimeMillis() / 1000;
        if (now > Json.d(rc.get("expires_at"), 0) - 300) refresh(rc);
        Map<String, Object> tpl = Json.map("object_type", "text", "text", text,
                "link", Json.map("web_url", "https://finance.naver.com", "mobile_web_url", "https://m.stock.naver.com"));
        for (int attempt = 0; attempt < 2; attempt++) {
            Map<String, String> h = new HashMap<>();
            h.put("Authorization", "Bearer " + Json.s(rc.get("access_token")));
            Map<String, String> f = new LinkedHashMap<>();
            f.put("template_object", Json.stringify(tpl));
            Net.Resp r = Net.postForm(KAPI + "/v2/api/talk/memo/default/send", h, f);
            if (r.status == 200 && Json.d(r.json.get("result_code"), 0) == 0) return;
            if (r.status == 401 && attempt == 0) {
                refresh(rc);
                continue;
            }
            int code = (int) Json.d(r.json.get("code"), 0);
            String hint = code == -402 ? " (동의항목 '카카오톡 메시지 전송' 설정/동의 필요)" : "";
            throw new Exception("카톡 전송 실패(" + r.status + ", " + code + "): "
                    + (r.json.get("msg") != null ? Json.s(r.json.get("msg")) : Json.stringify(r.json)) + hint);
        }
    }

    /** 반환: [이름, 성공여부, 메시지] 목록 */
    public List<Object[]> sendNow(String text) {
        List<Object[]> res = new ArrayList<>();
        List<Object> recips;
        synchronized (cfg.lock) {
            recips = new ArrayList<Object>(Json.arr(cfg.sec("kakao").get("recipients")));
        }
        if (recips.isEmpty()) {
            res.add(new Object[]{"-", false, "연결된 카카오 수신자가 없습니다"});
            return res;
        }
        for (Object o : recips) {
            Map<String, Object> rc = Json.obj(o);
            String name = Json.s(rc.get("name"));
            try {
                sendOne(rc, text);
                res.add(new Object[]{name, true, "OK"});
            } catch (Exception e) {
                res.add(new Object[]{name, false, e.getMessage()});
            }
        }
        cfg.save();
        return res;
    }

    public void push(String text) {
        Logx.alert(text);
        Map<String, Object> a = cfg.copy("alerts");
        if (Json.b(a.get("quiet"))) return;
        if (Json.b(a.get("phone_notify")) && phone != null) {
            try {
                phone.alert(text);
            } catch (Exception e) {
                Logx.add("폰 알림 실패: " + e.getMessage(), "warn");
            }
        }
        if (Json.b(a.get("kakao_notify"))) q.offer(text);
    }

    public static List<String> chunk(List<String> lines) {
        List<String> out = new ArrayList<>();
        String cur = "";
        for (String ln : lines) {
            while (ln.length() > LIMIT) {
                if (!cur.isEmpty()) {
                    out.add(cur);
                    cur = "";
                }
                out.add(ln.substring(0, LIMIT));
                ln = ln.substring(LIMIT);
            }
            String add = cur.isEmpty() ? ln : cur + "\n\n" + ln;
            if (add.length() > LIMIT) {
                out.add(cur);
                cur = ln;
            } else {
                cur = add;
            }
        }
        if (!cur.isEmpty()) out.add(cur);
        return out;
    }

    private void worker() {
        while (true) {
            try {
                String first = q.take();
                List<String> batch = new ArrayList<>();
                batch.add(first);
                long end = System.currentTimeMillis() + 1500;
                while (true) {
                    long remain = end - System.currentTimeMillis();
                    if (remain <= 0) break;
                    String n = q.poll(remain, TimeUnit.MILLISECONDS);
                    if (n == null) break;
                    batch.add(n);
                }
                for (String text : chunk(batch)) {
                    for (Object[] r : sendNow(text)) {
                        if (!(Boolean) r[1]) {
                            if ("-".equals(r[0])) {
                                if (!warnedEmpty && phone == null) {
                                    Logx.add("알림 발생했지만 카카오 수신자가 없어 화면에만 기록합니다", "warn");
                                    warnedEmpty = true;
                                }
                            } else {
                                Logx.add("[" + r[0] + "] " + r[2], "error");
                            }
                        }
                    }
                    Thread.sleep(300);
                }
            } catch (InterruptedException e) {
                return;
            } catch (Exception e) {
                Logx.add("카톡 발송 오류: " + e.getMessage(), "error");
            }
        }
    }
}
