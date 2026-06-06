package com.practice.cliproxy;

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
 * 工具名规范化的端到端测试（opt-in，proxy.tool-name-case=true）。验证：
 *   - 请求侧：tools[].name 与历史 messages tool_use.name 小写 -> PascalCase 后发给上游；
 *   - 响应侧（非流式 JSON）：tool_use.name 改写 + tool_use.input 里被序列化的数组修复；
 *   - 响应侧（SSE 流式）：content_block_start 里的 tool_use.name 改写。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "proxy.tool-name-case=true",
        "proxy.tool-name-request=true",
        "proxy.tool-name-response=true",
        "proxy.tool-name-repair-input=true"
})
class ExtensionToolNameE2eTest {

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
    void requestToolNamesNormalizedBeforeUpstream() throws Exception {
        String body = "{\"model\":\"claude-sonnet-4-6\",\"tools\":[{\"name\":\"todowrite\"},{\"name\":\"read\"}],"
                + "\"messages\":[{\"role\":\"assistant\",\"content\":[{\"type\":\"tool_use\",\"id\":\"t1\",\"name\":\"webfetch\",\"input\":{}}]}]}";
        post(body);
        String seen = up.lastBody;
        assertTrue(seen.contains("\"TodoWrite\"") && seen.contains("\"Read\""),
                "upstream sees PascalCase tool names in tools[]");
        assertTrue(seen.contains("\"WebFetch\""), "upstream sees PascalCase history tool_use name");
        assertFalse(seen.contains("\"todowrite\"") || seen.contains("\"webfetch\""),
                "no lowercase tool names leak to upstream");
    }

    @Test
    void responseJsonToolNameAndInputRepaired() throws Exception {
        up.setDefault(200, "{\"type\":\"message\",\"role\":\"assistant\",\"content\":[{\"type\":\"tool_use\","
                + "\"id\":\"t1\",\"name\":\"todowrite\",\"input\":{\"paths\":\"[\\\"a\\\",\\\"b\\\"]\"}}]}",
                "application/json");
        Resp r = post("{\"model\":\"claude-sonnet-4-6\",\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}");
        assertEquals(200, r.status);
        assertTrue(r.body.contains("\"TodoWrite\""), "client gets PascalCase tool name");
        assertTrue(r.body.contains("\"paths\":[\"a\",\"b\"]"), "serialized-array input repaired to real array");
    }

    @Test
    void sseResponseToolNameRewritten() throws Exception {
        String sse = "event: content_block_start\n"
                + "data: {\"type\":\"content_block_start\",\"index\":0,\"content_block\":{\"type\":\"tool_use\",\"id\":\"t1\",\"name\":\"todowrite\",\"input\":{}}}\n\n"
                + "event: message_stop\n"
                + "data: {\"type\":\"message_stop\"}\n\n";
        up.setDefault(200, sse, "text/event-stream");
        Resp r = post("{\"model\":\"claude-sonnet-4-6\",\"stream\":true,\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}");
        assertEquals(200, r.status);
        assertTrue(r.body.contains("\"TodoWrite\""), "SSE content_block_start tool name rewritten");
        assertFalse(r.body.contains("\"todowrite\""), "no lowercase tool name in streamed response");
        assertTrue(r.body.contains("message_stop"), "other SSE events preserved");
    }

    private Resp post(String body) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL("http://127.0.0.1:" + proxyPort + "/v1/messages").openConnection();
        conn.setRequestMethod("POST");
        conn.setRequestProperty("Content-Type", "application/json");
        conn.setRequestProperty("x-api-key", "sk-test");
        conn.setRequestProperty("anthropic-version", "2023-06-01");
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
