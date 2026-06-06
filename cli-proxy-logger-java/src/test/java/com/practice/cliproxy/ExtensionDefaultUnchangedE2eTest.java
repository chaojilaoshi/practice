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
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 兜底：不开启任何扩展特性时，默认透明路径行为字节级不变。请求里的小写工具名原样
 * 转发、上游响应原样回写，不做任何工具名/过滤器/代理处理。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ExtensionDefaultUnchangedE2eTest {

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
    void noOptInLeavesRequestAndResponseVerbatim() throws Exception {
        String upstreamBody = "{\"type\":\"message\",\"content\":[{\"type\":\"tool_use\",\"name\":\"todowrite\",\"input\":{\"paths\":\"[\\\"a\\\"]\"}}]}";
        up.setDefault(200, upstreamBody, "application/json");
        String reqBody = "{\"model\":\"claude-sonnet-4-6\",\"tools\":[{\"name\":\"todowrite\"}],"
                + "\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}";

        HttpURLConnection conn = (HttpURLConnection) new URL("http://127.0.0.1:" + proxyPort + "/v1/messages").openConnection();
        conn.setRequestMethod("POST");
        conn.setRequestProperty("Content-Type", "application/json");
        conn.setRequestProperty("x-api-key", "sk-test");
        conn.setDoOutput(true);
        try (OutputStream os = conn.getOutputStream()) {
            os.write(reqBody.getBytes(StandardCharsets.UTF_8));
        }
        int status = conn.getResponseCode();
        InputStream in = status >= 400 ? conn.getErrorStream() : conn.getInputStream();
        String respBody = in == null ? "" : new String(MockUpstream.readAll(in), StandardCharsets.UTF_8);

        assertEquals(200, status);
        assertTrue(up.lastBody.contains("\"todowrite\""), "lowercase tool name forwarded unchanged (no normalization)");
        assertEquals(upstreamBody, respBody, "response returned byte-for-byte (no rewrite/repair)");
    }
}
