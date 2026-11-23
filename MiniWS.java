package com.stockalert.core;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;

import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;

/** 의존성 없는 최소 WebSocket 클라이언트 (RFC6455: 텍스트, ping/pong, close, 분할 프레임) */
public final class MiniWS {
    public static final class Closed extends IOException {
        public Closed(String m) { super(m); }
    }

    private static final SecureRandom RND = new SecureRandom();
    private final Socket sock;
    private final InputStream in;
    private final OutputStream out;
    private final Object sendLock = new Object();
    private byte[] buf = new byte[65536];
    private int len = 0;
    private final java.io.ByteArrayOutputStream frag = new java.io.ByteArrayOutputStream();
    private volatile boolean closed = false;

    public MiniWS(String url) throws IOException {
        URI u = URI.create(url);
        boolean secure = "wss".equals(u.getScheme());
        String host = u.getHost();
        int port = u.getPort() > 0 ? u.getPort() : (secure ? 443 : 80);
        Socket raw = new Socket();
        raw.connect(new InetSocketAddress(host, port), 10000);
        raw.setSoTimeout(15000);
        raw.setTcpNoDelay(true);
        Socket s = raw;
        if (secure) {
            SSLSocket ss = (SSLSocket) ((SSLSocketFactory) SSLSocketFactory.getDefault()).createSocket(raw, host, port, true);
            SSLParameters p = ss.getSSLParameters();
            p.setEndpointIdentificationAlgorithm("HTTPS");   // 인증서 호스트명 검증
            ss.setSSLParameters(p);
            ss.startHandshake();
            s = ss;
        }
        sock = s;
        in = s.getInputStream();
        out = s.getOutputStream();
        byte[] kb = new byte[16];
        RND.nextBytes(kb);
        String key = java.util.Base64.getEncoder().encodeToString(kb);
        String path = (u.getRawPath() == null || u.getRawPath().isEmpty() ? "/" : u.getRawPath())
                + (u.getRawQuery() != null ? "?" + u.getRawQuery() : "");
        String req = "GET " + path + " HTTP/1.1\r\nHost: " + host + ":" + port + "\r\nUpgrade: websocket\r\n"
                + "Connection: Upgrade\r\nSec-WebSocket-Key: " + key + "\r\nSec-WebSocket-Version: 13\r\n\r\n";
        out.write(req.getBytes(StandardCharsets.ISO_8859_1));
        out.flush();
        // 응답 헤더 읽기
        int headEnd = -1;
        while (headEnd < 0) {
            fill();
            headEnd = indexOf(buf, len, new byte[]{13, 10, 13, 10});
            if (len > 65000) throw new Closed("핸드셰이크 응답이 너무 큼");
        }
        String head = new String(buf, 0, headEnd, StandardCharsets.ISO_8859_1);
        consume(headEnd + 4);
        String[] lines = head.split("\r\n");
        if (!(lines[0] + " ").contains(" 101 ")) throw new Closed("웹소켓 업그레이드 실패: " + lines[0]);
        String expect;
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-1");
            expect = java.util.Base64.getEncoder().encodeToString(
                    md.digest((key + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").getBytes(StandardCharsets.ISO_8859_1)));
        } catch (Exception e) {
            throw new IOException(e);
        }
        boolean ok = false;
        for (String l : lines) {
            int c = l.indexOf(':');
            if (c > 0 && l.substring(0, c).trim().equalsIgnoreCase("sec-websocket-accept")
                    && l.substring(c + 1).trim().equals(expect)) ok = true;
        }
        if (!ok) throw new Closed("Sec-WebSocket-Accept 불일치");
    }

    private static int indexOf(byte[] a, int n, byte[] pat) {
        outer:
        for (int i = 0; i + pat.length <= n; i++) {
            for (int j = 0; j < pat.length; j++) if (a[i + j] != pat[j]) continue outer;
            return i;
        }
        return -1;
    }

    private void fill() throws IOException {
        if (len == buf.length) buf = Arrays.copyOf(buf, buf.length * 2);
        int n = in.read(buf, len, buf.length - len);
        if (n < 0) {
            closed = true;
            throw new Closed("서버가 연결을 끊음");
        }
        len += n;
    }

    private void consume(int n) {
        System.arraycopy(buf, n, buf, 0, len - n);
        len -= n;
    }

    private void sendFrame(int op, byte[] payload) throws IOException {
        int n = payload.length;
        byte[] hdr;
        if (n < 126) {
            hdr = new byte[]{(byte) (0x80 | op), (byte) (0x80 | n)};
        } else if (n < 65536) {
            hdr = new byte[]{(byte) (0x80 | op), (byte) (0x80 | 126), (byte) (n >> 8), (byte) n};
        } else {
            hdr = new byte[10];
            hdr[0] = (byte) (0x80 | op);
            hdr[1] = (byte) (0x80 | 127);
            long ln = n;
            for (int i = 0; i < 8; i++) hdr[9 - i] = (byte) (ln >> (8 * i));
        }
        byte[] mask = new byte[4];
        RND.nextBytes(mask);
        byte[] frame = new byte[hdr.length + 4 + n];
        System.arraycopy(hdr, 0, frame, 0, hdr.length);
        System.arraycopy(mask, 0, frame, hdr.length, 4);
        for (int i = 0; i < n; i++) frame[hdr.length + 4 + i] = (byte) (payload[i] ^ mask[i & 3]);
        synchronized (sendLock) {
            out.write(frame);
            out.flush();
        }
    }

    public void send(String text) throws IOException {
        if (closed) throw new Closed("이미 닫힌 연결");
        sendFrame(0x1, text.getBytes(StandardCharsets.UTF_8));
    }

    /** 프레임 하나를 버퍼에서 꺼냄. 아직 다 안 왔으면 null (버퍼는 그대로) */
    private Object[] parse() {
        if (len < 2) return null;
        boolean fin = (buf[0] & 0x80) != 0;
        int op = buf[0] & 0x0F;
        boolean masked = (buf[1] & 0x80) != 0;
        long ln = buf[1] & 0x7F;
        int i = 2;
        if (ln == 126) {
            if (len < 4) return null;
            ln = ((buf[2] & 0xFF) << 8) | (buf[3] & 0xFF);
            i = 4;
        } else if (ln == 127) {
            if (len < 10) return null;
            ln = 0;
            for (int k = 2; k < 10; k++) ln = (ln << 8) | (buf[k] & 0xFF);
            i = 10;
        }
        byte[] mk = null;
        if (masked) {
            if (len < i + 4) return null;
            mk = Arrays.copyOfRange(buf, i, i + 4);
            i += 4;
        }
        if (ln > Integer.MAX_VALUE - 16) return null;
        if (len < i + ln) {
            if (buf.length < i + ln) buf = Arrays.copyOf(buf, (int) (i + ln) + 1024);
            return null;
        }
        byte[] pl = Arrays.copyOfRange(buf, i, (int) (i + ln));
        consume((int) (i + ln));
        if (mk != null) for (int k = 0; k < pl.length; k++) pl[k] ^= mk[k & 3];
        return new Object[]{fin, op, pl};
    }

    /** 텍스트 메시지 1개 반환, timeoutMs 안에 없으면 null */
    public String recv(int timeoutMs) throws IOException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (true) {
            Object[] fr = parse();
            if (fr == null) {
                long remain = deadline - System.currentTimeMillis();
                if (remain <= 0) return null;
                sock.setSoTimeout((int) Math.max(1, remain));
                try {
                    fill();
                } catch (SocketTimeoutException e) {
                    return null;
                }
                continue;
            }
            boolean fin = (Boolean) fr[0];
            int op = (Integer) fr[1];
            byte[] pl = (byte[]) fr[2];
            if (op == 0x8) {
                closed = true;
                try {
                    sendFrame(0x8, pl.length >= 2 ? Arrays.copyOf(pl, 2) : new byte[0]);
                } catch (IOException ignored) {
                }
                int code = pl.length >= 2 ? ((pl[0] & 0xFF) << 8) | (pl[1] & 0xFF) : 0;
                String reason = pl.length > 2 ? new String(pl, 2, pl.length - 2, StandardCharsets.UTF_8) : "";
                throw new Closed("서버 close(" + code + ") " + reason);
            }
            if (op == 0x9) {
                sendFrame(0xA, pl);
                continue;
            }
            if (op == 0xA) continue;
            if (op == 0x1 || op == 0x2) frag.reset();
            frag.write(pl, 0, pl.length);
            if (fin) {
                String s = new String(frag.toByteArray(), StandardCharsets.UTF_8);
                frag.reset();
                return s;
            }
        }
    }

    public boolean isClosed() { return closed; }

    public void close() {
        if (!closed) {
            closed = true;
            try {
                sendFrame(0x8, new byte[]{0x03, (byte) 0xE8});
            } catch (Exception ignored) {
            }
        }
        try {
            sock.close();
        } catch (Exception ignored) {
        }
    }
}
