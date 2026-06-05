package com.practice.cliproxy;

import com.practice.cliproxy.proxy.ProxyController;
import com.practice.cliproxy.resilience.BreakerRegistry;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
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

/**
 * 熔断端到端：阈值=2 的两 provider 池（b-1 总是 500，b-2 总是 200）。前两次请求把
 * b-1 的失败累计到阈值并打开熔断；第三次请求时 b-1 应被直接跳过（命中数停在 2），
 * 由 b-2 服务，且 b-1 的熔断状态为 open。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "proxy.breaker-failures=2",
        "proxy.breaker-cooldown-ms=60000",
        "proxy.breaker-half-open-max=1"
})
class BreakerE2eTest {

    private static MockUpstream b1;
    private static MockUpstream b2;

    @LocalServerPort
    int proxyPort;

    @Autowired
    ProxyController controller;

    @BeforeAll
    static void start() throws Exception {
        b1 = new MockUpstream();
        b1.setDefault(500, "{\"error\":{\"message\":\"always down\"}}", "application/json");
        b2 = new MockUpstream(); // default 200
    }

    @AfterAll
    static void stop() {
        b1.stop();
        b2.stop();
    }

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("proxy.providers", () -> "[{\"id\":\"b-1\",\"group\":\"anthropic\",\"baseUrl\":\"" + b1.baseUrl()
                + "\"},{\"id\":\"b-2\",\"group\":\"anthropic\",\"baseUrl\":\"" + b2.baseUrl() + "\"}]");
    }

    @Test
    void breakerOpensAndSkipsFailingProvider() throws Exception {
        assertEquals(200, post().status, "req1 fails over to b-2");
        assertEquals(200, post().status, "req2 fails over to b-2 (b-1 now at threshold)");

        assertEquals(BreakerRegistry.OPEN, controller.getBreakers().snapshot().get("b-1").state,
                "b-1 circuit opened after reaching failure threshold");

        int hitsBefore = b1.hits.get();
        assertEquals(200, post().status, "req3 still succeeds via b-2");
        assertEquals(hitsBefore, b1.hits.get(), "b-1 skipped while circuit open (no new hit)");
        assertEquals(2, hitsBefore, "b-1 was only hit while circuit was closed");
    }

    private Resp post() throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL("http://127.0.0.1:" + proxyPort + "/v1/messages").openConnection();
        conn.setRequestMethod("POST");
        conn.setRequestProperty("Content-Type", "application/json");
        conn.setRequestProperty("x-api-key", "sk-test");
        conn.setDoOutput(true);
        try (OutputStream os = conn.getOutputStream()) {
            os.write("{\"model\":\"claude-sonnet-4-6\",\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}"
                    .getBytes(StandardCharsets.UTF_8));
        }
        int status = conn.getResponseCode();
        InputStream in = status >= 400 ? conn.getErrorStream() : conn.getInputStream();
        if (in != null) {
            MockUpstream.readAll(in);
        }
        Resp r = new Resp();
        r.status = status;
        return r;
    }

    static class Resp {
        int status;
    }
}
