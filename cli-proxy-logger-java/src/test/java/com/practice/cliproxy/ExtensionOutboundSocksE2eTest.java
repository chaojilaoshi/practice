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
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 出站 SOCKS5 代理端到端测试（opt-in，proxy.upstream-proxy=socks5://...）。
 * 验证去上游的连接确实经由 SOCKS5 代理：代理记录到目标 host:port，且上游仍收到请求。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ExtensionOutboundSocksE2eTest {

    private static MockUpstream up;
    private static MockProxies.Socks5Proxy proxy;

    @LocalServerPort
    int proxyPort;

    @BeforeAll
    static void start() throws Exception {
        up = new MockUpstream();
        proxy = new MockProxies.Socks5Proxy();
    }

    @AfterAll
    static void stop() {
        up.stop();
        proxy.stop();
    }

    @BeforeEach
    void reset() {
        up.reset();
        proxy.seen.clear();
    }

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("proxy.anthropic-upstream", () -> up.baseUrl());
        registry.add("proxy.upstream-proxy", () -> "socks5://127.0.0.1:" + proxy.port());
    }

    @Test
    void outboundGoesThroughSocks5Proxy() throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL("http://127.0.0.1:" + proxyPort + "/v1/messages").openConnection();
        conn.setRequestMethod("POST");
        conn.setRequestProperty("Content-Type", "application/json");
        conn.setRequestProperty("x-api-key", "sk-test");
        conn.setDoOutput(true);
        try (OutputStream os = conn.getOutputStream()) {
            os.write("{\"model\":\"claude-sonnet-4-6\",\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}"
                    .getBytes(StandardCharsets.UTF_8));
        }
        assertEquals(200, conn.getResponseCode(), "client gets 200 via SOCKS5 proxy");
        assertEquals(1, up.hits.get(), "upstream received the forwarded request");
        int upPort = up.server.getAddress().getPort();
        assertTrue(proxy.seen.contains("127.0.0.1:" + upPort),
                "SOCKS5 proxy observed the upstream target " + upPort + " in " + proxy.seen);
    }
}
