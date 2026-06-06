package com.practice.cliproxy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.practice.cliproxy.filters.FilterEngine;
import com.practice.cliproxy.outbound.OutboundProxy;
import com.practice.cliproxy.transform.ToolNameTransformer;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * opt-in 扩展特性的纯单元测试（无 Spring）：工具名规范化、过滤器/规则引擎、出站代理
 * URL 解析。与 Node {@code test/extensions.js} / Python {@code test_extensions.py} 的单元部分
 * 1:1 对应。
 */
class ExtensionUnitTest {

    private static final ObjectMapper M = new ObjectMapper();
    private static final Map<String, String> DEF = ToolNameTransformer.DEFAULT_TOOL_NAME_MAP;

    @Test
    void toolNameMappingUnit() throws Exception {
        assertEquals("TodoWrite", ToolNameTransformer.mapToolName("todowrite", DEF), "built-in map");
        assertEquals("Read", ToolNameTransformer.mapToolName("read", new LinkedHashMap<>()), "capitalizes unknown");
        assertEquals("WebFetch", ToolNameTransformer.mapToolName("WebFetch", DEF), "case-insensitive map hit");
        assertNull(ToolNameTransformer.mapToolName(null, new LinkedHashMap<>()), "leaves non-strings");
        Map<String, String> explicit = new LinkedHashMap<>();
        explicit.put("foo", "BarBaz");
        assertEquals("BarBaz", ToolNameTransformer.mapToolName("foo", explicit), "explicit map overrides");

        ObjectNode reqBody = (ObjectNode) M.readTree("{"
                + "\"tools\":[{\"name\":\"read\"},{\"name\":\"todowrite\"},{\"name\":\"AlreadyPascal\"}],"
                + "\"messages\":[{\"role\":\"assistant\",\"content\":[{\"type\":\"tool_use\",\"name\":\"write\",\"id\":\"t1\",\"input\":{}}]},"
                + "{\"role\":\"user\",\"content\":[{\"type\":\"text\",\"text\":\"hi\"}]}]}");
        int changed = ToolNameTransformer.normalizeRequestToolNames(reqBody, DEF);
        assertEquals("Read", reqBody.get("tools").get(0).get("name").asText(), "request tool 0 renamed");
        assertEquals("TodoWrite", reqBody.get("tools").get(1).get("name").asText(), "request tool 1 renamed");
        assertEquals("AlreadyPascal", reqBody.get("tools").get(2).get("name").asText(), "already-pascal untouched");
        assertEquals("Write", reqBody.get("messages").get(0).get("content").get(0).get("name").asText(),
                "history tool_use renamed");
        assertEquals(3, changed, "normalizeRequest reports changed count");

        ObjectNode resBody = (ObjectNode) M.readTree("{\"content\":[{\"type\":\"tool_use\",\"name\":\"read\","
                + "\"input\":{\"paths\":\"[\\\"a\\\",\\\"b\\\"]\",\"note\":\"plain\",\"obj\":\"{\\\"k\\\":1}\"}}]}");
        ToolNameTransformer.rewriteResponseToolNames(resBody, DEF);
        assertEquals("Read", resBody.get("content").get(0).get("name").asText(), "response tool_use renamed");
        int repaired = ToolNameTransformer.repairToolUseInput(resBody, M);
        JsonNode input = resBody.get("content").get(0).get("input");
        assertTrue(input.get("paths").isArray() && input.get("paths").size() == 2
                && "a".equals(input.get("paths").get(0).asText()), "input array string repaired");
        assertTrue(input.get("obj").isObject() && input.get("obj").get("k").asInt() == 1, "input object string repaired");
        assertEquals("plain", input.get("note").asText(), "plain string left alone");
        assertEquals(2, repaired, "repair reports count");

        ObjectNode evt = (ObjectNode) M.readTree("{\"type\":\"content_block_start\",\"index\":0,"
                + "\"content_block\":{\"type\":\"tool_use\",\"name\":\"webfetch\"}}");
        boolean did = ToolNameTransformer.rewriteStreamEventToolName(evt, DEF);
        assertEquals("WebFetch", evt.get("content_block").get("name").asText(), "SSE content_block_start renamed");
        assertTrue(did, "SSE rewrite returns true on change");
        ObjectNode evt2 = (ObjectNode) M.readTree("{\"type\":\"content_block_delta\",\"delta\":{}}");
        assertFalse(ToolNameTransformer.rewriteStreamEventToolName(evt2, DEF), "SSE non-start untouched");
    }

    @Test
    void filtersRulesEngineUnit() {
        List<FilterEngine.Filter> filters = FilterEngine.parseFilters("["
                + "{\"name\":\"beta\",\"action\":\"set_header\",\"target\":\"anthropic-beta\",\"value\":\"ctx-1m\",\"priority\":10},"
                + "{\"name\":\"think-type\",\"action\":\"json_set\",\"target\":\"thinking.type\",\"value\":\"adaptive\",\"priority\":1},"
                + "{\"name\":\"budget\",\"action\":\"json_set\",\"target\":\"thinking.budget_tokens\",\"value\":\"1024\",\"priority\":2},"
                + "{\"name\":\"disabled\",\"action\":\"set_header\",\"target\":\"x-no\",\"value\":\"1\",\"enabled\":false,\"priority\":20},"
                + "{\"name\":\"scoped\",\"action\":\"set_header\",\"target\":\"x-scoped\",\"value\":\"yes\",\"scope\":\"provider:vendorA\",\"priority\":30},"
                + "{\"name\":\"bad\",\"action\":\"nonsense\",\"target\":\"x\"}]", M);
        assertTrue(filters.stream().noneMatch(f -> "bad".equals(f.name)), "parseFilters drops invalid action");
        StringBuilder order = new StringBuilder();
        for (FilterEngine.Filter f : filters) {
            order.append(f.name).append(',');
        }
        assertEquals("think-type,budget,beta,disabled,scoped,", order.toString(), "parseFilters sorts by priority");

        Map<String, String> headers = new LinkedHashMap<>();
        ObjectNode body = M.createObjectNode();
        FilterEngine.Result res = FilterEngine.applyFilters(filters, null, headers, body, M);
        assertEquals("ctx-1m", headers.get("anthropic-beta"), "set_header applied (lowercased)");
        assertEquals("adaptive", body.get("thinking").get("type").asText(), "json_set string value");
        assertEquals(1024, body.get("thinking").get("budget_tokens").asInt(), "json_set numeric coercion");
        assertNull(headers.get("x-no"), "disabled rule skipped");
        assertNull(headers.get("x-scoped"), "out-of-scope provider rule skipped");
        assertFalse(res.applied.contains("disabled") || res.applied.contains("scoped"),
                "applied list excludes disabled/scoped");

        Map<String, String> headers2 = new LinkedHashMap<>();
        ObjectNode body2 = M.createObjectNode();
        FilterEngine.applyFilters(filters, "vendorA", headers2, body2, M);
        assertEquals("yes", headers2.get("x-scoped"), "in-scope provider rule applied");

        List<FilterEngine.Filter> fdel = FilterEngine.parseFilters("["
                + "{\"name\":\"del-h\",\"action\":\"delete_header\",\"target\":\"X-Remove\"},"
                + "{\"name\":\"del-b\",\"action\":\"json_delete\",\"target\":\"metadata.user_id\"}]", M);
        Map<String, String> h3 = new LinkedHashMap<>();
        h3.put("x-remove", "gone");
        h3.put("keep", "yes");
        ObjectNode b3 = M.createObjectNode();
        ObjectNode meta = b3.putObject("metadata");
        meta.put("user_id", "u1");
        meta.put("session", "s1");
        FilterEngine.applyFilters(fdel, null, h3, b3, M);
        assertTrue(h3.get("x-remove") == null && "yes".equals(h3.get("keep")),
                "delete_header removes case-insensitively");
        assertTrue(!b3.get("metadata").has("user_id") && "s1".equals(b3.get("metadata").get("session").asText()),
                "json_delete removes nested key");

        Map<String, Object> summary = FilterEngine.summarizeFilters(fdel).get(0);
        assertEquals("del-h", summary.get("name"));
        assertEquals(true, summary.get("enabled"));
        assertEquals(0, summary.get("priority"));
        assertEquals("all", summary.get("scope"));
        assertEquals("delete_header", summary.get("action"));
        assertEquals("X-Remove", summary.get("target"));

        assertTrue(FilterEngine.parseFilters("{not json", M).isEmpty(), "parseFilters bad JSON -> []");
    }

    @Test
    void outboundProxyUrlParsingUnit() {
        OutboundProxy http = OutboundProxy.create("http://h:8080");
        assertEquals("http", http.kind);
        assertEquals("h", http.host);
        assertEquals(8080, http.port);
        assertEquals("", http.username);
        assertEquals("", http.password);

        OutboundProxy https = OutboundProxy.create("https://h");
        assertEquals("https", https.kind);
        assertEquals(443, https.port, "https proxy default port");

        OutboundProxy socks = OutboundProxy.create("socks5://h");
        assertEquals("socks5", socks.kind);
        assertEquals(1080, socks.port, "socks5 default port");

        assertEquals("socks5", OutboundProxy.create("socks://h:1081").kind, "socks alias");
        assertEquals("socks5", OutboundProxy.create("socks5h://h").kind, "socks5h alias");

        OutboundProxy auth = OutboundProxy.create("http://u:p@h:3128");
        assertEquals("h", auth.host);
        assertEquals(3128, auth.port);
        assertEquals("u", auth.username);
        assertEquals("p", auth.password);
        assertTrue(auth.hasAuth(), "auth parsed");

        assertNull(OutboundProxy.create(""), "empty -> null");
        assertNull(OutboundProxy.create("ftp://h"), "unknown scheme -> null");
        assertNull(OutboundProxy.create(null), "null -> null");
    }
}
