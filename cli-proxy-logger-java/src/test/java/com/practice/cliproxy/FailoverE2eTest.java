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
 * 故障转移 + 终止错误的端到端测试。配置一个含两个 provider 的 anthropic 池
 * （p-a 优先，p-b 次之），熔断阈值设很高（不在本类内打开），逐场景验证：
 *   - 首选返回 503（可故障转移）-> 自动切到 p-b，客户端拿到 200；
 *   - 首选返回 401（不可故障转移）-> 原样回写客户端，不触碰 p-b；
 *   - 全部返回 503 -> 回放最后一次错误给客户端（503）。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "proxy.breaker-failures=50"
})
class FailoverE2eTest {

    private static MockUpstream pa;
    private static MockUpstream pb;

    @LocalServerPort
    int proxyPort;

    @BeforeAll
    static void start() throws Exception {
        pa = new MockUpstream();
        pb = new MockUpstream();
    }

    @AfterAll
    static void stop() {
        pa.stop();
        pb.stop();
    }

    @BeforeEach
    void reset() {
        pa.reset();
        pb.reset();
    }

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("proxy.providers", () -> "[{\"id\":\"p-a\",\"group\":\"anthropic\",\"baseUrl\":\"" + pa.baseUrl()
                + "\"},{\"id\":\"p-b\",\"group\":\"anthropic\",\"baseUrl\":\"" + pb.baseUrl() + "\"}]");
    }

    @Test
    void failsOverOn503() throws Exception {
        pa.enqueue(503, "{\"error\":{\"message\":\"overloaded\"}}", "application/json");
        // pb uses default 200.
        Resp r = post("{\"model\":\"claude-sonnet-4-6\",\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}");
        assertEquals(200, r.status, "client gets the successful provider's 200");
        assertEquals(1, pa.hits.get(), "primary was tried once");
        assertEquals(1, pb.hits.get(), "secondary served the request after failover");
    }

    @Test
    void terminalErrorNotFailedOver() throws Exception {
        // 400 是「客户端请求错误」——不在故障转移状态集合里，应原样回写、不切下一个 provider。
        // （不用 401/407：JDK 的 HttpURLConnection 会拦截这两个鉴权状态并吞掉错误体。）
        pa.enqueue(400, "{\"error\":{\"message\":\"bad request\"}}", "application/json");
        Resp r = post("{\"model\":\"claude-sonnet-4-6\",\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}");
        assertEquals(400, r.status, "non-failover status returned to client verbatim");
        assertTrue(r.body.contains("bad request"), "error body preserved");
        assertEquals(1, pa.hits.get(), "primary tried once");
        assertEquals(0, pb.hits.get(), "secondary NOT tried for terminal error");
    }

    @Test
    void allProvidersFailReplaysLastError() throws Exception {
        pa.enqueue(503, "{\"error\":{\"message\":\"a down\"}}", "application/json");
        pb.enqueue(503, "{\"error\":{\"message\":\"b down\"}}", "application/json");
        Resp r = post("{\"model\":\"claude-sonnet-4-6\",\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}");
        assertEquals(503, r.status, "exhausted pool replays last upstream status");
        assertEquals(1, pa.hits.get());
        assertEquals(1, pb.hits.get());
        assertTrue(r.body.contains("b down"), "last error body replayed");
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
