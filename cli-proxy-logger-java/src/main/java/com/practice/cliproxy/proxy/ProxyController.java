package com.practice.cliproxy.proxy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.practice.cliproxy.config.ProxyProperties;
import com.practice.cliproxy.model.Exchange;
import com.practice.cliproxy.model.NormalizedResponse;
import com.practice.cliproxy.parser.ParserFactory;
import com.practice.cliproxy.parser.StreamAggregator;
import com.practice.cliproxy.parser.WireParser;
import com.practice.cliproxy.recorder.ExchangeRecorder;
import com.practice.cliproxy.sse.SseParser;
import com.practice.cliproxy.translator.Translator;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Arrays;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashSet;
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

    // 「逐跳（hop-by-hop）」头只对单个传输连接有意义（RFC 7230 6.1），代理不能
    // 原样转发。这里还顺带去掉 host/content-length（为新连接重新计算）、
    // transfer-encoding（由 HttpURLConnection 重新分帧）、以及 accept-encoding
    // ——不带它就让 HttpURLConnection 自行协商 gzip 并透明解压，读到的即 identity。
    // 用 HashSet + Arrays.asList 构造（兼容 JDK 8；Java 9 的 Set.of 在 8 上不可用）。
    private static final Set<String> HOP_BY_HOP = Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
            "connection", "keep-alive", "proxy-authenticate", "proxy-authorization",
            "te", "trailer", "transfer-encoding", "upgrade", "host", "content-length", "accept-encoding")));

    // 协议翻译模式下，drop 掉这些 Anthropic 专属/会冲突的请求头（鉴权另行翻译）。
    private static final Set<String> COMPAT_DROP = Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
            "x-api-key", "anthropic-version", "anthropic-beta",
            "anthropic-dangerous-direct-browser-access", "content-type")));

    private final ProxyProperties props;
    private final UpstreamResolver resolver;
    private final ParserFactory parsers;
    private final ExchangeRecorder recorder;
    private final Translator translator;
    private final ObjectMapper mapper;

    public ProxyController(ProxyProperties props, UpstreamResolver resolver, ParserFactory parsers,
                           ExchangeRecorder recorder, Translator translator, ObjectMapper mapper) {
        this.props = props;
        this.resolver = resolver;
        this.parsers = parsers;
        this.recorder = recorder;
        this.translator = translator;
        this.mapper = mapper;
    }

    @RequestMapping("/v1/**")
    public void proxy(HttpServletRequest req, HttpServletResponse resp) throws Exception {
        long started = System.currentTimeMillis();
        // 步骤 1：缓冲请求体。CLI 的请求体是一段完整 JSON，整体读入最简单。
        byte[] reqBody = readAll(req.getInputStream());

        // 步骤 2：按路径选真实上游与 wire 格式。路径即可判断来源：
        //   /v1/messages == Claude Code（Anthropic）；
        //   /v1/responses 或 /v1/chat/completions == Codex。
        String path = req.getRequestURI();
        String query = req.getQueryString();
        UpstreamResolver.Upstream up = resolver.resolve(path, req.getHeader("x-api-key"), req.getHeader("anthropic-version"));

        // 兼容模式：ANTHROPIC_COMPAT=chat 且客户端说 Anthropic Messages 时，交给
        // 「翻译」路径（Anthropic -> OpenAI Chat），而非透明直通。这是唯一会同时
        // 改写请求与响应的分支。
        if (props.isAnthropicToChat() && "anthropic".equals(up.wire) && path.startsWith("/v1/messages")) {
            handleAnthropicToChat(req, resp, reqBody, started);
            return;
        }

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

        // 步骤 3+4：建立到上游的连接，并原样转发客户端请求头（含真实的
        // Authorization / x-api-key），让上游看到与原始 CLI 完全一致的请求——
        // 这也是为何上游针对 CLI 特有请求头的放行逻辑透过代理依然成立。
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
        // 步骤 5：把上游状态码与响应头回写给客户端（去掉逐跳头）。
        ex.resStatus = status;
        resp.setStatus(status);
        ex.resHeaders = copyResponseHeaders(conn, resp);

        // 步骤 6 准备：SSE 流式响应走增量解析器 + 按 wire 的聚合器重建文本与
        // 工具调用；非流式则缓冲整段字节，最后一次性解析。
        String contentType = conn.getContentType();
        boolean sse = contentType != null && contentType.contains("text/event-stream");

        InputStream upstream = status >= 400 ? conn.getErrorStream() : conn.getInputStream();
        if (upstream == null) {
            // 空流（兼容 JDK 8；InputStream.nullInputStream() 是 Java 11 才有）。
            upstream = new ByteArrayInputStream(new byte[0]);
        }

        StreamAggregator agg = sse ? parser.newAggregator() : null;
        SseParser sseParser = sse ? new SseParser(agg::feed) : null;
        ByteArrayOutputStream copy = sse ? null : new ByteArrayOutputStream();

        // 步骤 6：双路转发循环。每读到一块上游数据，先把「原始字节」回写给
        // CLI（保真优先：即便解析抛错也不影响 CLI），再把同一份喂给 SSE 解析器
        // 或缓冲区。
        OutputStream clientOut = resp.getOutputStream();
        byte[] buf = new byte[8192];
        int n;
        try {
            while ((n = upstream.read(buf)) != -1) {
                clientOut.write(buf, 0, n);   // 保真：先原样回写给 CLI
                clientOut.flush();            // flush 让 SSE 实时到达
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

        // 步骤 7：收尾——得到归一化响应并记录这条 Exchange。
        try {
            if (sse) {
                sseParser.flush();   // 冲出缓冲区里残留的最后一个事件
                ex.response = agg.result();
            } else {
                // ByteArrayOutputStream.toString(Charset) 是 Java 10+；用 toByteArray + new String 兼容 JDK 8。
                ex.response = parser.parseResponse(new String(copy.toByteArray(), StandardCharsets.UTF_8));
            }
        } catch (Exception e) {
            NormalizedResponse r = new NormalizedResponse();
            r.parseError = e.getMessage();
            ex.response = r;
        }
        ex.durationMs = System.currentTimeMillis() - started;
        recorder.record(ex);
    }

    // =================================================================
    // 兼容路径：Anthropic /v1/messages -> OpenAI /v1/chat/completions
    //
    // 与透明 proxy() 不同，这里「双向改写」：
    //   请求：Translator.anthropicRequestToChat() -> POST /v1/chat/completions
    //   响应：OpenAI Chat（SSE 或 JSON）-> Anthropic 事件流回给客户端
    // 客户端（Claude Code）完全不知道厂商说的是另一种协议。
    // =================================================================
    private void handleAnthropicToChat(HttpServletRequest req, HttpServletResponse resp, byte[] reqBody, long started) throws Exception {
        JsonNode anthBody = treeOrEmpty(new String(reqBody, StandardCharsets.UTF_8));
        boolean wantStream = anthBody.path("stream").asBoolean(false);
        String originalModel = anthBody.path("model").asText(null);
        String displayModel = originalModel;
        Map<String, String> modelMap = translator.parseModelMap(props.resolveModelMapRaw());

        // 1) 翻译请求体并选定映射后的模型。
        Map<String, Object> chatBody = translator.anthropicRequestToChat(anthBody, modelMap);
        String mappedModel = String.valueOf(chatBody.get("model"));
        if (displayModel == null) {
            displayModel = mappedModel;
        }
        byte[] chatBytes = translator.toJson(chatBody).getBytes(StandardCharsets.UTF_8);

        // 2) 构造发往 OpenAI 风格厂商的上游请求。
        String url = props.getOpenaiUpstream() + "/v1/chat/completions";

        // 3) 记录这条 Exchange：请求按 Anthropic（客户端发来的）记录，附 translation 标记。
        Exchange ex = new Exchange();
        ex.id = UUID.randomUUID().toString();
        ex.ts = Instant.ofEpochMilli(started).toString();
        ex.wire = "anthropic";
        ex.method = req.getMethod();
        ex.url = url;
        ex.reqHeaders = collectRequestHeaders(req);
        ex.requestBodyRaw = truncate(reqBody);
        ex.request = parsers.get("anthropic").parseRequest(new String(reqBody, StandardCharsets.UTF_8));
        Map<String, Object> translation = new LinkedHashMap<>();
        translation.put("from", "anthropic");
        translation.put("to", "chat");
        translation.put("model", originalModel);
        translation.put("upstreamModel", mappedModel);
        ex.translation = translation;

        // 4) 打开上游连接并发出翻译后的请求。
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setInstanceFollowRedirects(false);
        conn.setRequestMethod("POST");
        forwardCompatHeaders(req, conn);  // 含 x-api-key -> Bearer 鉴权翻译
        conn.setRequestProperty("Content-Type", "application/json");
        conn.setDoOutput(true);
        conn.setFixedLengthStreamingMode(chatBytes.length);
        try (OutputStream os = conn.getOutputStream()) {
            os.write(chatBytes);
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
            resp.getWriter().write(translator.toJson(translator.chatErrorToAnthropic(null, "upstream request failed")));
            return;
        }
        ex.resStatus = status;
        ex.resHeaders = new LinkedHashMap<>();
        String contentType = conn.getContentType();
        boolean sse = contentType != null && contentType.contains("text/event-stream");

        InputStream upstream = status >= 400 ? conn.getErrorStream() : conn.getInputStream();
        if (upstream == null) {
            upstream = new ByteArrayInputStream(new byte[0]);
        }

        WireParser chatParser = parsers.get("chat");

        if (sse) {
            // ---- 流式翻译 ----
            resp.setStatus(status);
            resp.setContentType("text/event-stream; charset=utf-8");
            resp.setHeader("Cache-Control", "no-cache");
            final OutputStream clientOut = resp.getOutputStream();
            final Translator.ChatToAnthropicStream tstream = translator.newStream(displayModel);
            final StreamAggregator agg = chatParser.newAggregator();  // 仅供落盘归一化

            SseParser sseParser = new SseParser(evt -> {
                agg.feed(evt);  // 落盘用的归一化响应
                String raw = evt.data == null ? "" : evt.data.trim();
                if (raw.isEmpty() || "[DONE]".equals(raw)) {
                    return;
                }
                JsonNode data = treeOrNull(raw);
                if (data == null) {
                    return;
                }
                try {
                    for (String frame : tstream.feed(data)) {
                        clientOut.write(frame.getBytes(StandardCharsets.UTF_8));
                        clientOut.flush();
                    }
                } catch (IOException io) {
                    throw new UncheckedIOException(io);
                }
            });

            byte[] buf = new byte[8192];
            int n;
            try {
                while ((n = upstream.read(buf)) != -1) {
                    sseParser.push(new String(buf, 0, n, StandardCharsets.UTF_8));
                }
                sseParser.flush();
                for (String frame : tstream.end()) {
                    clientOut.write(frame.getBytes(StandardCharsets.UTF_8));
                    clientOut.flush();
                }
            } catch (Exception e) {
                ex.error = "upstream stream error: " + e.getMessage();
            } finally {
                upstream.close();
            }
            try {
                ex.response = agg.result();
            } catch (Exception e) {
                NormalizedResponse r = new NormalizedResponse();
                r.parseError = e.getMessage();
                ex.response = r;
            }
        } else {
            // ---- 缓冲（非 SSE）翻译 ----
            // 覆盖普通 JSON chat completion 以及错误体。若客户端要求流式（但厂商回了
            // 单段 JSON），我们仍合成 Anthropic 事件序列补还给它。
            ByteArrayOutputStream copy = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            try {
                while ((n = upstream.read(buf)) != -1) {
                    copy.write(buf, 0, n);
                }
            } finally {
                upstream.close();
            }
            String bodyText = new String(copy.toByteArray(), StandardCharsets.UTF_8);
            JsonNode obj = treeOrNull(bodyText);
            boolean isError = status >= 400 || (obj != null && obj.has("error"));

            OutputStream clientOut = resp.getOutputStream();
            if (isError) {
                Map<String, Object> anthErr = translator.chatErrorToAnthropic(obj, bodyText);
                if (wantStream) {
                    resp.setStatus(status);
                    resp.setContentType("text/event-stream; charset=utf-8");
                    clientOut.write(translator.anthropicErrorSse(obj, bodyText).getBytes(StandardCharsets.UTF_8));
                } else {
                    resp.setStatus(status);
                    resp.setContentType("application/json; charset=utf-8");
                    clientOut.write(translator.toJson(anthErr).getBytes(StandardCharsets.UTF_8));
                }
                Object errObj = ((Map<String, Object>) anthErr.get("error")).get("message");
                ex.error = errObj == null ? null : String.valueOf(errObj);
            } else {
                Map<String, Object> anthObj = translator.chatResponseToAnthropic(
                        obj != null ? obj : mapper.createObjectNode(), displayModel);
                if (wantStream) {
                    resp.setStatus(status);
                    resp.setContentType("text/event-stream; charset=utf-8");
                    resp.setHeader("Cache-Control", "no-cache");
                    for (String frame : translator.anthropicMessageToSse(anthObj)) {
                        clientOut.write(frame.getBytes(StandardCharsets.UTF_8));
                    }
                } else {
                    resp.setStatus(status);
                    resp.setContentType("application/json; charset=utf-8");
                    clientOut.write(translator.toJson(anthObj).getBytes(StandardCharsets.UTF_8));
                }
            }
            clientOut.flush();
            try {
                ex.response = chatParser.parseResponse(bodyText);
            } catch (Exception e) {
                NormalizedResponse r = new NormalizedResponse();
                r.parseError = e.getMessage();
                ex.response = r;
            }
        }

        ex.durationMs = System.currentTimeMillis() - started;
        recorder.record(ex);
    }

    // 兼容模式转发请求头：去掉逐跳头 + Anthropic 专属头，并把 Claude Code 的
    // x-api-key 翻译成 OpenAI 厂商期望的 Authorization: Bearer。
    private void forwardCompatHeaders(HttpServletRequest req, HttpURLConnection conn) {
        Enumeration<String> names = req.getHeaderNames();
        while (names.hasMoreElements()) {
            String name = names.nextElement();
            String lk = name.toLowerCase(Locale.ROOT);
            if (HOP_BY_HOP.contains(lk) || COMPAT_DROP.contains(lk)) {
                continue;
            }
            conn.setRequestProperty(name, req.getHeader(name));
        }
        String auth = req.getHeader("authorization");
        String apiKey = req.getHeader("x-api-key");
        if (auth != null) {
            conn.setRequestProperty("Authorization", auth);
        } else if (apiKey != null) {
            conn.setRequestProperty("Authorization", "Bearer " + apiKey);
        }
    }

    private JsonNode treeOrNull(String body) {
        try {
            return mapper.readTree(body);
        } catch (Exception e) {
            return null;
        }
    }

    private JsonNode treeOrEmpty(String body) {
        JsonNode node = treeOrNull(body);
        return node != null ? node : mapper.createObjectNode();
    }

    // 整流读入为字节数组（兼容 JDK 8；InputStream.readAllBytes() 是 Java 9 才有）。
    private static byte[] readAll(InputStream in) throws java.io.IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) != -1) {
            out.write(buf, 0, n);
        }
        return out.toByteArray();
    }

    // 落盘前对凭证脱敏：转发给上游的仍是真实 key，只有写入日志的副本被打码，
    // 因此 JSONL 文件里不会出现可用的 API key。
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
