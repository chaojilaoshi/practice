package com.practice.cliproxy;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 测试用的极简出站代理：HTTP 转发代理 与 SOCKS5 代理，各记录它们看到的目标 host:port，
 * 用于验证「出站代理」特性确实让上游连接经由外部代理。与 Node {@code test/extensions.js}
 * 里的 makeConnectProxy / makeSocks5 等价。
 */
final class MockProxies {

    private MockProxies() {
    }

    /**
     * HTTP 转发代理。HttpURLConnection 对 http:// 上游用 Proxy.Type.HTTP 时，会以
     * 「绝对形式」请求行（POST http://host:port/path）发给代理。这里解析出目标、记录，
     * 然后直连目标转发，再把响应原样回写。
     */
    static final class HttpForwardProxy {
        final ServerSocket server;
        final List<String> seen = new CopyOnWriteArrayList<>();
        private volatile boolean running = true;

        HttpForwardProxy() throws Exception {
            server = new ServerSocket(0, 50, InetSocketAddress.createUnresolved("127.0.0.1", 0).getAddress());
            Thread t = new Thread(this::loop, "mock-http-proxy");
            t.setDaemon(true);
            t.start();
        }

        int port() {
            return server.getLocalPort();
        }

        private void loop() {
            while (running) {
                try {
                    Socket client = server.accept();
                    Thread h = new Thread(() -> handle(client), "mock-http-proxy-conn");
                    h.setDaemon(true);
                    h.start();
                } catch (Exception e) {
                    return;
                }
            }
        }

        private void handle(Socket client) {
            try (Socket c = client) {
                InputStream in = c.getInputStream();
                // 读请求头（直到 CRLFCRLF）。
                String head = readHead(in);
                if (head.isEmpty()) {
                    return;
                }
                String[] lines = head.split("\r\n");
                String[] reqLine = lines[0].split(" ");
                String method = reqLine[0];
                URI uri = URI.create(reqLine[1]); // 绝对形式
                String host = uri.getHost();
                int port = uri.getPort() > 0 ? uri.getPort() : 80;
                String pathq = uri.getRawPath() + (uri.getRawQuery() != null ? "?" + uri.getRawQuery() : "");
                seen.add(host + ":" + port);

                int contentLength = 0;
                List<String> fwdHeaders = new ArrayList<>();
                for (int i = 1; i < lines.length; i++) {
                    String line = lines[i];
                    int idx = line.indexOf(':');
                    if (idx < 0) {
                        continue;
                    }
                    String k = line.substring(0, idx).trim();
                    String v = line.substring(idx + 1).trim();
                    String lk = k.toLowerCase(Locale.ROOT);
                    if (lk.equals("content-length")) {
                        contentLength = Integer.parseInt(v);
                    }
                    if (lk.equals("proxy-connection") || lk.equals("connection") || lk.equals("host")) {
                        continue;
                    }
                    fwdHeaders.add(k + ": " + v);
                }
                byte[] body = new byte[contentLength];
                int read = 0;
                while (read < contentLength) {
                    int n = in.read(body, read, contentLength - read);
                    if (n < 0) {
                        break;
                    }
                    read += n;
                }

                // 直连目标转发。
                try (Socket up = new Socket(host, port)) {
                    OutputStream uo = up.getOutputStream();
                    StringBuilder sb = new StringBuilder();
                    sb.append(method).append(' ').append(pathq.isEmpty() ? "/" : pathq).append(" HTTP/1.1\r\n");
                    sb.append("Host: ").append(host).append(':').append(port).append("\r\n");
                    for (String hdr : fwdHeaders) {
                        sb.append(hdr).append("\r\n");
                    }
                    sb.append("Connection: close\r\n\r\n");
                    uo.write(sb.toString().getBytes(StandardCharsets.ISO_8859_1));
                    if (body.length > 0) {
                        uo.write(body);
                    }
                    uo.flush();
                    // 回写上游响应给客户端。
                    OutputStream co = c.getOutputStream();
                    InputStream ui = up.getInputStream();
                    byte[] buf = new byte[8192];
                    int n;
                    while ((n = ui.read(buf)) != -1) {
                        co.write(buf, 0, n);
                    }
                    co.flush();
                }
            } catch (Exception ignore) {
                // 测试用，忽略
            }
        }

        private static String readHead(InputStream in) throws Exception {
            java.io.ByteArrayOutputStream buf = new java.io.ByteArrayOutputStream();
            int prev = -1;
            int prev2 = -1;
            int prev3 = -1;
            int b;
            while ((b = in.read()) != -1) {
                buf.write(b);
                if (prev3 == '\r' && prev2 == '\n' && prev == '\r' && b == '\n') {
                    break;
                }
                prev3 = prev2;
                prev2 = prev;
                prev = b;
            }
            String s = new String(buf.toByteArray(), StandardCharsets.ISO_8859_1);
            int idx = s.indexOf("\r\n\r\n");
            return idx >= 0 ? s.substring(0, idx) : s;
        }

        void stop() {
            running = false;
            try {
                server.close();
            } catch (Exception ignore) {
            }
        }
    }

    /** 极简 SOCKS5（无鉴权，CONNECT）。记录目标 host:port，并双向转发。 */
    static final class Socks5Proxy {
        final ServerSocket server;
        final List<String> seen = new CopyOnWriteArrayList<>();
        private volatile boolean running = true;

        Socks5Proxy() throws Exception {
            server = new ServerSocket(0, 50, java.net.InetAddress.getByName("127.0.0.1"));
            Thread t = new Thread(this::loop, "mock-socks5");
            t.setDaemon(true);
            t.start();
        }

        int port() {
            return server.getLocalPort();
        }

        private void loop() {
            while (running) {
                try {
                    Socket client = server.accept();
                    Thread h = new Thread(() -> handle(client), "mock-socks5-conn");
                    h.setDaemon(true);
                    h.start();
                } catch (Exception e) {
                    return;
                }
            }
        }

        private void handle(Socket client) {
            try {
                InputStream in = client.getInputStream();
                OutputStream out = client.getOutputStream();
                // 1) 问候：VER, NMETHODS, METHODS...
                int ver = in.read();
                int nm = in.read();
                for (int i = 0; i < nm; i++) {
                    in.read();
                }
                if (ver != 0x05) {
                    client.close();
                    return;
                }
                out.write(new byte[]{0x05, 0x00}); // 无鉴权
                out.flush();
                // 2) 连接请求：VER CMD RSV ATYP ADDR PORT
                in.read(); // ver
                in.read(); // cmd
                in.read(); // rsv
                int atyp = in.read();
                String host;
                if (atyp == 0x01) {
                    int a = in.read();
                    int b = in.read();
                    int cc = in.read();
                    int d = in.read();
                    host = a + "." + b + "." + cc + "." + d;
                } else if (atyp == 0x03) {
                    int len = in.read();
                    byte[] hb = new byte[len];
                    int r = 0;
                    while (r < len) {
                        r += in.read(hb, r, len - r);
                    }
                    host = new String(hb, StandardCharsets.US_ASCII);
                } else {
                    client.close();
                    return;
                }
                int p1 = in.read();
                int p2 = in.read();
                int port = (p1 << 8) | p2;
                seen.add(host + ":" + port);
                // 3) 回复成功 + bind addr 0.0.0.0:0
                out.write(new byte[]{0x05, 0x00, 0x00, 0x01, 0, 0, 0, 0, 0, 0});
                out.flush();
                // 4) 双向转发。
                Socket up = new Socket(host, port);
                Thread a = pipe(in, up.getOutputStream());
                Thread b = pipe(up.getInputStream(), out);
                a.join();
                b.join();
                up.close();
                client.close();
            } catch (Exception ignore) {
                // 测试用，忽略
            }
        }

        private static Thread pipe(InputStream in, OutputStream out) {
            Thread t = new Thread(() -> {
                try {
                    byte[] buf = new byte[8192];
                    int n;
                    while ((n = in.read(buf)) != -1) {
                        out.write(buf, 0, n);
                        out.flush();
                    }
                } catch (Exception ignore) {
                }
            });
            t.setDaemon(true);
            t.start();
            return t;
        }

        void stop() {
            running = false;
            try {
                server.close();
            } catch (Exception ignore) {
            }
        }
    }
}
