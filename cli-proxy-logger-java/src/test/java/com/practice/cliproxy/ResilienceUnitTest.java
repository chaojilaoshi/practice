package com.practice.cliproxy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.practice.cliproxy.resilience.BreakerRegistry;
import com.practice.cliproxy.resilience.Provider;
import com.practice.cliproxy.resilience.Providers;
import com.practice.cliproxy.resilience.Rectifier;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 弹性逻辑的纯单元测试（无 Spring）：熔断器状态机、供应商池解析、整流器检测/改写。
 * 与 Node {@code test/resilience.js} / Python {@code test_resilience.py} 的单元部分等价。
 */
class ResilienceUnitTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void breakerStateMachine() {
        AtomicLong clock = new AtomicLong(1000);
        BreakerRegistry reg = new BreakerRegistry(2, 100, 1, clock::get);

        assertTrue(reg.canRequest("a").allowed, "starts allowed/closed");
        reg.recordFailure("a", false);
        assertTrue(reg.canRequest("a").allowed, "one failure still allowed");
        reg.recordFailure("a", false);
        assertFalse(reg.canRequest("a").allowed, "opens at threshold (denied)");
        assertEquals(BreakerRegistry.OPEN, reg.snapshot().get("a").state, "snapshot shows open");

        clock.addAndGet(200); // past cooldown
        BreakerRegistry.Permit probe = reg.canRequest("a");
        assertTrue(probe.allowed && probe.halfOpen, "half-open grants one probe");
        assertFalse(reg.canRequest("a").allowed, "half-open caps concurrent probes");

        reg.recordSuccess("a", true); // probe ok -> closed + permit released
        assertEquals(BreakerRegistry.CLOSED, reg.snapshot().get("a").state, "success closes");
        assertTrue(reg.canRequest("a").allowed, "closed again after success");

        // neutral must release a half-open permit without changing health.
        reg.recordFailure("a", false);
        reg.recordFailure("a", false); // open
        clock.addAndGet(200);
        BreakerRegistry.Permit p2 = reg.canRequest("a");
        reg.recordNeutral("a", p2.halfOpen);
        assertTrue(reg.canRequest("a").allowed, "neutral releases probe permit");
    }

    @Test
    void providersParsingAndResolution() {
        Map<String, List<Provider>> pools = Providers.parse(
                "[{\"id\":\"x\",\"group\":\"openai\",\"baseUrl\":\"http://a/\"},"
                + "{\"wire\":\"messages\",\"baseUrl\":\"http://b\"}]", MAPPER);
        assertEquals(1, pools.get("openai").size(), "openai pool parsed");
        assertEquals("http://a", pools.get("openai").get(0).baseUrl, "trailing slash trimmed");
        assertEquals(1, pools.get("anthropic").size(), "wire alias maps to anthropic group");
        assertEquals("anthropic-0", pools.get("anthropic").get(0).id, "auto id generated");

        assertEquals("openai", Providers.wireToGroup("responses"), "responses -> openai");
        assertEquals("anthropic", Providers.wireToGroup("anthropic"), "anthropic -> anthropic");

        List<Provider> cands = Providers.resolveCandidates(pools, "chat", "http://u");
        assertEquals(1, cands.size());
        assertEquals("x", cands.get(0).id, "chat wire resolves to openai pool");

        Map<String, List<Provider>> empty = Providers.parse(null, MAPPER);
        List<Provider> fallback = Providers.resolveCandidates(empty, "anthropic", "http://legacy");
        assertEquals(1, fallback.size());
        assertEquals("http://legacy", fallback.get(0).baseUrl, "empty pool falls back to legacy upstream");
        assertNull(fallback.get(0).apiKey, "fallback forwards client credential (no apiKey)");
    }

    @Test
    void rectifierDetectionAndRewrite() throws Exception {
        assertEquals("signature", Rectifier.detect(400,
                MAPPER.readTree("{\"error\":{\"message\":\"thinking signature is invalid\"}}"), true, true));
        assertEquals("budget", Rectifier.detect(400,
                MAPPER.readTree("{\"error\":{\"message\":\"thinking.budget_tokens must be >= 1024\"}}"), true, true));
        assertNull(Rectifier.detect(503,
                MAPPER.readTree("{\"error\":{\"message\":\"signature\"}}"), true, true), "5xx ignored (failover instead)");
        assertNull(Rectifier.detect(400,
                MAPPER.readTree("{\"error\":{\"message\":\"bad signature\"}}"), false, true), "respects disabled signature rule");

        JsonNode sigBody = MAPPER.readTree(
                "{\"messages\":[{\"role\":\"assistant\",\"content\":["
                + "{\"type\":\"thinking\",\"thinking\":\"x\",\"signature\":\"sig\"},"
                + "{\"type\":\"text\",\"text\":\"hi\",\"signature\":\"sig2\"}]}]}");
        ObjectNode sigOut = Rectifier.rectifySignature(sigBody, MAPPER);
        JsonNode content = sigOut.get("messages").get(0).get("content");
        assertEquals(1, content.size(), "signature strips thinking blocks");
        assertEquals("text", content.get(0).get("type").asText(), "kept text block");
        assertFalse(content.get(0).has("signature"), "signature fields stripped");

        ObjectNode budOut = Rectifier.rectifyBudget(MAPPER.readTree("{\"max_tokens\":100}"), MAPPER);
        assertEquals("enabled", budOut.get("thinking").get("type").asText());
        assertEquals(32000, budOut.get("thinking").get("budget_tokens").asInt(), "valid budget set");
        assertEquals(64000, budOut.get("max_tokens").asInt(), "max_tokens raised above budget");
    }
}
