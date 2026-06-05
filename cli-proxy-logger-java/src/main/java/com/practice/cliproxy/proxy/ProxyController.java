package com.practice.cliproxy.proxy;

import com.practice.cliproxy.config.ProxyProperties;
import com.practice.cliproxy.model.Exchange;
import com.practice.cliproxy.model.NormalizedResponse;
import com.practice.cliproxy.parser.ParserFactory;
import com.practice.cliproxy.parser.StreamAggregator;
import com.practice.cliproxy.parser.WireParser;
import com.practice.cliproxy.recorder.ExchangeRecorder;
import com.practice.cliproxy.sse.SseParser;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 反向代理核心：捕获 /v1/** 的全部请求，转发到真实上游，并在「先把上游字节
 * 原样回写给 CLI」之后，把同一份内容喂给对应 wire 的解析器，重建文本与工具调用。
 *
 * 说明：转发时刻意不带 Accept-Encoding，交给 HttpURLConnection 自行协商 gzip 并
 * 透明解压，这样我们读到/转发出去的都是 identity 字节，解析也无需再处理压缩。
 */
@RestController
public class ProxyController {

    private static final Set<String> HOP_BY_HOP = Set.of(
            "connection", "keep-alive", "proxy-authenticate", "proxy-authorization",
            "te", "trailer", "transfer-encoding", "upgrade", "host", "content-length", "accept-encoding");

    private final ProxyProperties props;
    private final UpstreamResolver resolver;
    private final ParserFactory parsers;
    private final ExchangeRecorder recorder;

    public ProxyController(ProxyProperties props, UpstreamResolver resolver, ParserFactory parsers, ExchangeRecorder recorder) {
        this.props = props;
        this.resolver = resolver;
        this.parsers = parsers;
        this.recorder = recorder;
    }

    @RequestMapping("/v1/**")
    public void proxy(HttpServletRequest req, HttpServletResponse resp) throws Exception {
        long started = System.currentTimeMillis();
        byte[] reqBody = req.getInputStream().readAllBytes();

        String path = req.getRequestURI();
        String query = req.getQueryString();
        UpstreamResolver.Upstream up = resolver.resolve(path, req.getHeader("x-api-key"), req.getHeader("anthropic-version"));
        WireParser parser = parsers.get(up.wire);

        Exchange ex = new Exchange();
        ex.id = UUID.randomUUID().toString();
        ex.ts = Instant.ofEpochMilli(started).toString();
        ex.wire = up.wire;
        ex.method = req.getMethod();
        ex.url = up.baseUrl + path + (query != null ? "?" + query : "");
        ex.reqHeaders = collectRequestHeaders(req);
        ex.requestBodyRaw = truncate(reqBody);
        ex.request = parser.parseRequest(new String(reqBody, StandardCharsets.UTF_8));

        HttpURLConnection conn = (HttpURLConnection) new URL(ex.url).openConnection();
        conn.setInstanceFollowRedirects(false);
        conn.setRequestMethod(req.getMethod());
        forwardRequestHeaders(req, conn);
        if (reqBody.length > 0) {
            conn.setDoOutput(true);
            conn.setFixedLengthStreamingMode(reqBody.length);
            try (OutputStream os = conn.getOutputStream()) {
                os.write(reqBody);
            }
        }

        int status;
        try {
            status = conn.getResponseCode();
        } catch (Exception e) {
            ex.error = "upstream request error: " + e.getMessage();
            ex.durationMs = System.currentTimeMillis() - started;
            recorder.record(ex);
            resp.setStatus(502);
            resp.setContentType("application/json");
            resp.getWriter().write("{\"error\":{\"type\":\"proxy_error\",\"message\":\"" + e.getMessage() + "\"}}");
            return;
        }
        ex.resStatus = status;
        resp.setStatus(status);
        ex.resHeaders = copyResponseHeaders(conn, resp);

        String contentType = conn.getContentType();
        boolean sse = contentType != null && contentType.contains("text/event-stream");

        InputStream upstream = status >= 400 ? conn.getErrorStream() : conn.getInputStream();
        if (upstream == null) {
            upstream = InputStream.nullInputStream();
        }

        StreamAggregator agg = sse ? parser.newAggregator() : null;
        SseParser sseParser = sse ? new SseParser(agg::feed) : null;
        ByteArrayOutputStream copy = sse ? null : new ByteArrayOutputStream();

        OutputStream clientOut = resp.getOutputStream();
        byte[] buf = new byte[8192];
        int n;
        try {
            while ((n = upstream.read(buf)) != -1) {
                clientOut.write(buf, 0, n);   // 保真：先原样回写给 CLI
                clientOut.flush();
                if (sse) {
                    sseParser.push(new String(buf, 0, n, StandardCharsets.UTF_8));
                } else if (copy.size() < props.getMaxBodyBytes()) {
                    copy.write(buf, 0, n);
                }
            }
        } catch (Exception e) {
            ex.error = "upstream stream error: " + e.getMessage();
        } finally {
            upstream.close();
        }

        try {
            if (sse) {
                sseParser.flush();
                ex.response = agg.result();
            } else {
                ex.response = parser.parseResponse(copy.toString(StandardCharsets.UTF_8));
            }
        } catch (Exception e) {
            NormalizedResponse r = new NormalizedResponse();
            r.parseError = e.getMessage();
            ex.response = r;
        }
        ex.durationMs = System.currentTimeMillis() - started;
        recorder.record(ex);
    }

    private Map<String, String> collectRequestHeaders(HttpServletRequest req) {
        Map<String, String> out = new LinkedHashMap<>();
        Enumeration<String> names = req.getHeaderNames();
        while (names.hasMoreElements()) {
            String name = names.nextElement();
            String value = req.getHeader(name);
            String lk = name.toLowerCase(Locale.ROOT);
            if (props.isRedactAuth() && (lk.equals("authorization") || lk.equals("x-api-key") || lk.equals("api-key"))) {
                out.put(name, redact(value));
            } else {
                out.put(name, value);
            }
        }
        return out;
    }

    private void forwardRequestHeaders(HttpServletRequest req, HttpURLConnection conn) {
        Enumeration<String> names = req.getHeaderNames();
        while (names.hasMoreElements()) {
            String name = names.nextElement();
            if (HOP_BY_HOP.contains(name.toLowerCase(Locale.ROOT))) {
                continue;
            }
            conn.setRequestProperty(name, req.getHeader(name));
        }
    }

    private Map<String, String> copyResponseHeaders(HttpURLConnection conn, HttpServletResponse resp) {
        Map<String, String> out = new LinkedHashMap<>();
        for (Map.Entry<String, List<String>> e : conn.getHeaderFields().entrySet()) {
            String name = e.getKey();
            if (name == null) {
                continue; // 状态行
            }
            if (HOP_BY_HOP.contains(name.toLowerCase(Locale.ROOT))) {
                continue;
            }
            String value = String.join(",", e.getValue());
            resp.setHeader(name, value);
            out.put(name, value);
        }
        return out;
    }

    private String truncate(byte[] body) {
        if (body.length <= props.getMaxBodyBytes()) {
            return new String(body, StandardCharsets.UTF_8);
        }
        return new String(body, 0, props.getMaxBodyBytes(), StandardCharsets.UTF_8)
                + "\n...[truncated " + (body.length - props.getMaxBodyBytes()) + " bytes]";
    }

    private String redact(String s) {
        if (s == null) {
            return null;
        }
        return s.length() <= 12 ? "***" : s.substring(0, 6) + "..." + s.substring(s.length() - 4);
    }
}
