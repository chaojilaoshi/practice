package com.practice.cliproxy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.URL;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 协议翻译（Anthropic /v1/messages -> OpenAI /v1/chat/completions）端到端测试。
 *
 * 起一个 mock OpenAI Chat 上游（com.sun.net.httpserver），把代理的
 * proxy.openai-upstream 指向它（@DynamicPropertySource），并开启
 * proxy.anthropic-compat=chat + 一张模型映射表。然后用真实 HTTP 打代理的
 * /v1/messages，断言：
 *   - 上游收到的是翻译后的 chat 请求（映射模型、system 消息、tools schema、Bearer 鉴权）
 *   - 客户端收到的是 Anthropic 事件流 / message（流式与非流式都覆盖）
 *
 * 与 Node/Python 的 mock 测试等价，只是用 JUnit + 内嵌 mock 服务器表达。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "proxy.anthropic-compat=chat",
        "proxy.model-map={\"claude-sonnet-4-6\":\"gpt-4o\"}"
})
class CompatTranslationTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static HttpServer mockUpstream;

    // mock 捕获的最后一次 /v1/chat/completions 请求体与请求头。
    static volatile String lastChatBody;
    static volatile String lastAuthHeader;

    private static final String CHAT_STREAM = String.join("\n",
            "data: {\"choices\":[{\"index\":0,\"delta\":{\"role\":\"assistant\",\"content\":\"Sure.\"}}]}",
            "",
            "data: {\"choices\":[{\"index\":0,\"delta\":{\"tool_calls\":[{\"index\":0,\"id\":\"call_9\",\"function\":{\"name\":\"search\",\"arguments\":\"{\\\"q\\\":\"}}]}}]}",
            "",
            "data: {\"choices\":[{\"index\":0,\"delta\":{\"tool_calls\":[{\"index\":0,\"function\":{\"arguments\":\"\\\"cats\\\"}\"}}]}}]}",
            "",
            "data: {\"choices\":[{\"index\":0,\"delta\":{},\"finish_reason\":\"tool_calls\"}],\"usage\":{\"prompt_tokens\":5,\"completion_tokens\":7,\"total_tokens\":12}}",
            "",
            "data: [DONE]",
            "",
            "");

    private static final String CHAT_JSON =
            "{\"id\":\"chatcmpl_1\",\"object\":\"chat.completion\",\"model\":\"gpt-4o-mini\",\"choices\":[{\"index\":0,"
            + "\"message\":{\"role\":\"assistant\",\"content\":\"Here you go.\",\"tool_calls\":[{\"id\":\"call_7\","
            + "\"type\":\"function\",\"function\":{\"name\":\"search\",\"arguments\":\"{\\\"q\\\":\\\"dogs\\\"}\"}}]},"
            + "\"finish_reason\":\"tool_calls\"}],\"usage\":{\"prompt_tokens\":5,\"completion_tokens\":9,\"total_tokens\":14}}";

    @LocalServerPort
    int proxyPort;

    @BeforeAll
    static void startMock() throws Exception {
        mockUpstream = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        mockUpstream.createContext("/v1/chat/completions", exchange -> {
            byte[] body = readAll(exchange.getRequestBody());
            lastChatBody = new String(body, StandardCharsets.UTF_8);
            lastAuthHeader = exchange.getRequestHeaders().getFirst("Authorization");
            boolean stream = lastChatBody.contains("\"stream\":true");
            byte[] out;
            if (stream) {
                exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
                out = CHAT_STREAM.getBytes(StandardCharsets.UTF_8);
            } else {
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                out = CHAT_JSON.getBytes(StandardCharsets.UTF_8);
            }
            exchange.sendResponseHeaders(200, out.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(out);
            }
        });
        mockUpstream.start();
    }

    @AfterAll
    static void stopMock() {
        if (mockUpstream != null) {
            mockUpstream.stop(0);
        }
    }

    @DynamicPropertySource
    static void upstreamProps(DynamicPropertyRegistry registry) {
        registry.add("proxy.openai-upstream", () -> "http://127.0.0.1:" + mockUpstream.getAddress().getPort());
    }

    // ---- streaming (Anthropic in -> Anthropic SSE out) --------------------
    @Test
    void streamingTranslation() throws Exception {
        String reqBody = "{\"model\":\"claude-sonnet-4-6\",\"stream\":true,\"system\":\"be brief\","
                + "\"tools\":[{\"name\":\"search\",\"description\":\"web\","
                + "\"input_schema\":{\"type\":\"object\",\"properties\":{\"q\":{\"type\":\"string\"}}}}],"
                + "\"messages\":[{\"role\":\"user\",\"content\":\"find cats\"}]}";
        Resp r = post("/v1/messages", reqBody, "sk-anthropic-secret-123");

        // 上游收到的是翻译后的 chat 请求。
        JsonNode chat = MAPPER.readTree(lastChatBody);
        assertEquals("gpt-4o", chat.path("model").asText(), "model should be mapped");
        assertEquals("system", chat.path("messages").path(0).path("role").asText());
        assertEquals("be brief", chat.path("messages").path(0).path("content").asText());
        assertEquals("function", chat.path("tools").path(0).path("type").asText());
        assertEquals("search", chat.path("tools").path(0).path("function").path("name").asText());
        assertEquals("Bearer sk-anthropic-secret-123", lastAuthHeader, "x-api-key should translate to Bearer");

        // 客户端收到 Anthropic 事件流。
        assertTrue(r.body.contains("event: message_start"), "message_start present");
        assertTrue(r.body.contains("tool_use") && r.body.contains("search"), "tool_use block present");
        assertTrue(r.body.contains("input_json_delta"), "input_json_delta present");
        assertTrue(r.body.contains("event: message_stop"), "message_stop present");
        assertTrue(r.body.contains("cats"), "mapped-back tool args present");
    }

    // ---- non-streaming (Anthropic in -> Anthropic JSON out) ---------------
    @Test
    void nonStreamingTranslation() throws Exception {
        String reqBody = "{\"model\":\"claude-sonnet-4-6\",\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}";
        Resp r = post("/v1/messages", reqBody, "sk-anthropic-secret-456");

        JsonNode chat = MAPPER.readTree(lastChatBody);
        assertEquals("gpt-4o", chat.path("model").asText());

        JsonNode anth = MAPPER.readTree(r.body);
        assertEquals("message", anth.path("type").asText());
        assertEquals("assistant", anth.path("role").asText());
        assertEquals("text", anth.path("content").path(0).path("type").asText());
        assertEquals("Here you go.", anth.path("content").path(0).path("text").asText());
        assertEquals("tool_use", anth.path("content").path(1).path("type").asText());
        assertEquals("search", anth.path("content").path(1).path("name").asText());
        assertEquals("tool_use", anth.path("stop_reason").asText(), "finish_reason maps to tool_use");
        assertEquals(5, anth.path("usage").path("input_tokens").asInt());
        assertEquals(9, anth.path("usage").path("output_tokens").asInt());
    }

    // ---- unmapped model passes through unchanged --------------------------
    @Test
    void unmappedModelPassesThrough() throws Exception {
        String reqBody = "{\"model\":\"gpt-4o-mini\",\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}";
        post("/v1/messages", reqBody, "sk-x");
        JsonNode chat = MAPPER.readTree(lastChatBody);
        assertEquals("gpt-4o-mini", chat.path("model").asText(), "unmapped model passes through");
    }

    private Resp post(String path, String body, String apiKey) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL("http://127.0.0.1:" + proxyPort + path).openConnection();
        conn.setRequestMethod("POST");
        conn.setRequestProperty("Content-Type", "application/json");
        conn.setRequestProperty("x-api-key", apiKey);
        conn.setRequestProperty("anthropic-version", "2023-06-01");
        conn.setDoOutput(true);
        try (OutputStream os = conn.getOutputStream()) {
            os.write(body.getBytes(StandardCharsets.UTF_8));
        }
        int status = conn.getResponseCode();
        InputStream in = status >= 400 ? conn.getErrorStream() : conn.getInputStream();
        String respBody = in == null ? "" : new String(readAll(in), StandardCharsets.UTF_8);
        Resp r = new Resp();
        r.status = status;
        r.body = respBody;
        assertNotNull(r.body);
        return r;
    }

    private static byte[] readAll(InputStream in) throws java.io.IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        int n;
        while ((n = in.read(buf)) != -1) {
            out.write(buf, 0, n);
        }
        return out.toByteArray();
    }

    static class Resp {
        int status;
        String body;
    }
}
