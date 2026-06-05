package com.practice.cliproxy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Anthropic thinking 整流端到端（opt-in，单上游、无 provider 池、无熔断）。验证：
 *   - 签名错误（400 + "signature ... invalid"）-> 删除 thinking 块后对同一上游重试一次 -> 200；
 *   - budget 错误（400 + "budget_tokens ... >= 1024"）-> 开启 thinking/调高 budget+max_tokens 后重试 -> 200；
 *   - 不可整流的 400 -> 不重试，原样回写客户端。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "proxy.rectify=true"
})
class RectifierE2eTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static MockUpstream up;

    @LocalServerPort
    int proxyPort;

    @BeforeAll
    static void start() throws Exception {
        up = new MockUpstream();
    }

    @AfterAll
    static void stop() {
        up.stop();
    }

    @BeforeEach
    void reset() {
        up.reset();
    }

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("proxy.anthropic-upstream", () -> up.baseUrl());
    }

    @Test
    void signatureRectifyStripsThinkingAndRetries() throws Exception {
        up.enqueue(400, "{\"type\":\"error\",\"error\":{\"type\":\"invalid_request_error\","
                + "\"message\":\"messages.0: thinking signature is invalid\"}}", "application/json");
        // second request: default 200.
        String body = "{\"model\":\"claude-sonnet-4-6\",\"messages\":[{\"role\":\"assistant\",\"content\":["
                + "{\"type\":\"thinking\",\"thinking\":\"abc\",\"signature\":\"S\"},"
                + "{\"type\":\"text\",\"text\":\"hi\"}]}]}";
        Resp r = post(body);

        assertEquals(200, r.status, "rectified retry succeeds");
        assertEquals(2, up.hits.get(), "exactly one retry against the same provider");
        JsonNode retry = MAPPER.readTree(up.lastBody);
        JsonNode content = retry.get("messages").get(0).get("content");
        for (JsonNode block : content) {
            assertFalse("thinking".equals(block.path("type").asText()), "thinking blocks stripped on retry");
        }
    }

    @Test
    void budgetRectifyEnablesThinkingAndRetries() throws Exception {
        up.enqueue(400, "{\"type\":\"error\",\"error\":{\"type\":\"invalid_request_error\","
                + "\"message\":\"thinking.budget_tokens: must be greater than or equal to 1024\"}}", "application/json");
        Resp r = post("{\"model\":\"claude-sonnet-4-6\",\"max_tokens\":100,"
                + "\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}");

        assertEquals(200, r.status, "rectified retry succeeds");
        assertEquals(2, up.hits.get(), "exactly one retry");
        JsonNode retry = MAPPER.readTree(up.lastBody);
        assertEquals("enabled", retry.path("thinking").path("type").asText(), "thinking enabled on retry");
        assertEquals(32000, retry.path("thinking").path("budget_tokens").asInt());
        assertEquals(64000, retry.path("max_tokens").asInt(), "max_tokens raised above budget");
    }

    @Test
    void nonRectifiableErrorReturnedAsIs() throws Exception {
        up.enqueue(400, "{\"type\":\"error\",\"error\":{\"message\":\"unrelated bad request\"}}", "application/json");
        Resp r = post("{\"model\":\"claude-sonnet-4-6\",\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}");
        assertEquals(400, r.status, "non-rectifiable 400 returned verbatim");
        assertEquals(1, up.hits.get(), "no retry for non-rectifiable error");
        assertTrue(r.body.contains("unrelated bad request"));
    }

    private Resp post(String body) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL("http://127.0.0.1:" + proxyPort + "/v1/messages").openConnection();
        conn.setRequestMethod("POST");
        conn.setRequestProperty("Content-Type", "application/json");
        conn.setRequestProperty("x-api-key", "sk-test");
        conn.setDoOutput(true);
        try (OutputStream os = conn.getOutputStream()) {
            os.write(body.getBytes(StandardCharsets.UTF_8));
        }
        int status = conn.getResponseCode();
        InputStream in = status >= 400 ? conn.getErrorStream() : conn.getInputStream();
        String respBody = in == null ? "" : new String(MockUpstream.readAll(in), StandardCharsets.UTF_8);
        Resp r = new Resp();
        r.status = status;
        r.body = respBody;
        return r;
    }

    static class Resp {
        int status;
        String body;
    }
}
