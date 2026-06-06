package com.practice.cliproxy;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 过滤器/规则引擎的端到端测试（opt-in，proxy.filters=...）。验证转发上游前：
 *   - set_header 注入 anthropic-beta 头；
 *   - json_set 覆盖 body 的 thinking.type 与 thinking.budget_tokens（数值强转）；
 *   - delete_header 删除请求头。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "proxy.filters=[{\"name\":\"beta\",\"action\":\"set_header\",\"target\":\"anthropic-beta\",\"value\":\"context-1m-2025\",\"priority\":1},"
                + "{\"name\":\"think\",\"action\":\"json_set\",\"target\":\"thinking.type\",\"value\":\"adaptive\",\"priority\":2},"
                + "{\"name\":\"budget\",\"action\":\"json_set\",\"target\":\"thinking.budget_tokens\",\"value\":\"1024\",\"priority\":3},"
                + "{\"name\":\"drop\",\"action\":\"delete_header\",\"target\":\"x-drop-me\",\"priority\":4}]"
})
class ExtensionFiltersE2eTest {

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
    void filtersMutateOutboundRequest() throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL("http://127.0.0.1:" + proxyPort + "/v1/messages").openConnection();
        conn.setRequestMethod("POST");
        conn.setRequestProperty("Content-Type", "application/json");
        conn.setRequestProperty("x-api-key", "sk-test");
        conn.setRequestProperty("x-drop-me", "should-be-removed");
        conn.setDoOutput(true);
        try (OutputStream os = conn.getOutputStream()) {
            os.write("{\"model\":\"claude-sonnet-4-6\",\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}"
                    .getBytes(StandardCharsets.UTF_8));
        }
        assertEquals(200, conn.getResponseCode());

        assertEquals("context-1m-2025", up.lastHeaders.get("anthropic-beta"), "set_header injected anthropic-beta");
        assertNull(up.lastHeaders.get("x-drop-me"), "delete_header removed x-drop-me");
        String seen = up.lastBody;
        assertTrue(seen.contains("\"type\":\"adaptive\""), "json_set thinking.type applied");
        assertTrue(seen.contains("\"budget_tokens\":1024"), "json_set budget_tokens coerced to number");
    }
}
