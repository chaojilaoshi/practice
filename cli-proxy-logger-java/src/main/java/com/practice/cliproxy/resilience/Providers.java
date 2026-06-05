package com.practice.cliproxy.resilience;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 供应商池（供应商池）—— 可故障转移的有序上游列表，按上游「组」分类。Node
 * {@code src/providers.js} / Python {@code providers.py} 的等价实现。
 *
 * <p>两个组对应代理已经会说的两种协议族：
 * <ul>
 *   <li>{@code anthropic}：Anthropic wire（/v1/messages）</li>
 *   <li>{@code openai}：OpenAI wires（/v1/responses、/v1/chat/completions）</li>
 * </ul>
 *
 * 配置来源（先非空者胜）：PROVIDERS_FILE（JSON 文件路径）然后 PROVIDERS（内联
 * JSON）。某组没有配置池时回退到单个 legacy 上游——所以不显式配置池时默认行为
 * 完全不变。
 */
public final class Providers {

    private Providers() {
    }

    /** 把自由格式的 group/wire 提示归一到两个组之一。 */
    public static String normGroup(String g) {
        String s = g == null ? "" : g.toLowerCase();
        if ("anthropic".equals(s) || "messages".equals(s)) {
            return "anthropic";
        }
        return "openai";
    }

    /** 把请求 wire（anthropic|responses|chat）映射到其组。 */
    public static String wireToGroup(String wire) {
        return "anthropic".equals(wire) ? "anthropic" : "openai";
    }

    /**
     * 解析 providers 数组（JSON 字符串）成 {@code {anthropic:[...], openai:[...]}}。
     * 非法条目跳过。返回的两个 key 永远存在（可能是空列表）。
     */
    public static Map<String, List<Provider>> parse(String raw, ObjectMapper mapper) {
        Map<String, List<Provider>> pools = new LinkedHashMap<>();
        pools.put("anthropic", new ArrayList<>());
        pools.put("openai", new ArrayList<>());
        if (raw == null || raw.trim().isEmpty()) {
            return pools;
        }
        JsonNode arr;
        try {
            arr = mapper.readTree(raw.trim());
        } catch (Exception e) {
            return pools;
        }
        if (arr == null || !arr.isArray()) {
            return pools;
        }
        int auto = 0;
        for (JsonNode p : arr) {
            if (p == null || !p.isObject()) {
                continue;
            }
            String baseUrl = text(p, "baseUrl");
            if (baseUrl == null || baseUrl.isEmpty()) {
                continue;
            }
            String hint = firstNonNull(text(p, "group"), text(p, "wire"), text(p, "upstream"));
            String group = normGroup(hint);
            String id = text(p, "id");
            if (id == null || id.isEmpty()) {
                id = group + "-" + auto;
                auto++;
            }
            String apiKey = text(p, "apiKey");
            pools.get(group).add(new Provider(id, group, stripTrailingSlash(baseUrl),
                    (apiKey == null || apiKey.isEmpty()) ? null : apiKey));
        }
        return pools;
    }

    /**
     * 某个请求 wire 的有序候选列表。匹配组的池为空时回退到单个 legacy 上游。
     * 回退条目的 apiKey 为 null（转发客户端自己的凭证）。
     */
    public static List<Provider> resolveCandidates(Map<String, List<Provider>> pools, String wire, String fallbackBaseUrl) {
        String group = wireToGroup(wire);
        List<Provider> pool = pools == null ? null : pools.get(group);
        if (pool != null && !pool.isEmpty()) {
            return pool;
        }
        return Collections.singletonList(new Provider("default-" + group, group,
                stripTrailingSlash(fallbackBaseUrl), null));
    }

    private static String text(JsonNode node, String field) {
        JsonNode v = node.get(field);
        return (v == null || v.isNull()) ? null : v.asText();
    }

    private static String firstNonNull(String... vals) {
        for (String v : vals) {
            if (v != null && !v.isEmpty()) {
                return v;
            }
        }
        return null;
    }

    private static String stripTrailingSlash(String s) {
        if (s == null) {
            return null;
        }
        int end = s.length();
        while (end > 0 && s.charAt(end - 1) == '/') {
            end--;
        }
        return s.substring(0, end);
    }
}
