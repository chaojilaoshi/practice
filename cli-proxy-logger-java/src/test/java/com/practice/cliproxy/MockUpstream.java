package com.practice.cliproxy;

import com.sun.net.httpserver.HttpServer;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 测试用的可编程上游：每个实例监听一个随机端口，对 /v1/** 的每次请求弹出一个
 * 预设响应（状态码 + content-type + body）。用队列精确控制「第 N 次请求返回什么」，
 * 队列空了就回落到默认响应。同时记录命中次数与最后一次请求体，便于断言。
 */
class MockUpstream {
    final HttpServer server;
    final AtomicInteger hits = new AtomicInteger();
    volatile String lastBody;
    volatile String lastAuth;
    volatile java.util.Map<String, String> lastHeaders = new java.util.HashMap<>();
    private final Deque<int[]> statusQueue = new ArrayDeque<>(); // [status]
    private final Deque<String> bodyQueue = new ArrayDeque<>();
    private final Deque<String> ctypeQueue = new ArrayDeque<>();
    private volatile int defaultStatus = 200;
    private volatile String defaultBody = "{\"type\":\"message\",\"role\":\"assistant\",\"content\":[]}";
    private volatile String defaultCtype = "application/json";

    MockUpstream() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            hits.incrementAndGet();
            lastBody = new String(readAll(exchange.getRequestBody()), StandardCharsets.UTF_8);
            java.util.Map<String, String> hdrs = new java.util.HashMap<>();
            for (java.util.Map.Entry<String, java.util.List<String>> e : exchange.getRequestHeaders().entrySet()) {
                hdrs.put(e.getKey().toLowerCase(java.util.Locale.ROOT), String.join(",", e.getValue()));
            }
            lastHeaders = hdrs;
            lastAuth = exchange.getRequestHeaders().getFirst("x-api-key");
            if (lastAuth == null) {
                lastAuth = exchange.getRequestHeaders().getFirst("Authorization");
            }
            int status;
            String body;
            String ctype;
            synchronized (MockUpstream.this) {
                if (!statusQueue.isEmpty()) {
                    status = statusQueue.poll()[0];
                    body = bodyQueue.poll();
                    ctype = ctypeQueue.poll();
                } else {
                    status = defaultStatus;
                    body = defaultBody;
                    ctype = defaultCtype;
                }
            }
            byte[] out = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", ctype);
            exchange.sendResponseHeaders(status, out.length == 0 ? -1 : out.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(out);
            }
        });
        server.start();
    }

    synchronized MockUpstream enqueue(int status, String body, String ctype) {
        statusQueue.add(new int[]{status});
        bodyQueue.add(body);
        ctypeQueue.add(ctype);
        return this;
    }

    synchronized MockUpstream setDefault(int status, String body, String ctype) {
        defaultStatus = status;
        defaultBody = body;
        defaultCtype = ctype;
        return this;
    }

    synchronized void reset() {
        statusQueue.clear();
        bodyQueue.clear();
        ctypeQueue.clear();
        hits.set(0);
        lastBody = null;
        lastAuth = null;
    }

    String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    void stop() {
        server.stop(0);
    }

    static byte[] readAll(InputStream in) throws java.io.IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        int n;
        while ((n = in.read(buf)) != -1) {
            out.write(buf, 0, n);
        }
        return out.toByteArray();
    }
}
