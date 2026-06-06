package com.practice.cliproxy.filters;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 请求过滤器 / 规则引擎（过滤器/规则引擎）—— opt-in。Node {@code src/filters.js} /
 * Python {@code filters.py} 的等价实现。
 *
 * <p>灵感来自 claude-code-hub 的「编辑过滤器」：一组有序规则，在请求发往上游「之前」
 * 改写请求（请求头 和/或 JSON 体）。典型用途（见截图）：
 * <ul>
 *   <li>给每个请求加 {@code anthropic-beta} 头（开启 1M 上下文等）；</li>
 *   <li>强制把体里的 {@code thinking.type = "adaptive"} / {@code thinking.budget_tokens = 1024}
 *       覆盖掉，使上游的 thinking-budget 校验通过。</li>
 * </ul>
 *
 * <p>规则字段：name / enabled / priority / scope(all|provider:&lt;id&gt;) / stage(pre) /
 * domain(header|body) / action(set_header|delete_header|json_set|json_delete) / target / value。
 * body 动作的 value 能解析成 JSON 时按 JSON 解释（"1024"->1024、"true"->true、"[\"x\"]"->数组），
 * 否则按裸字符串（"adaptive" 仍是字符串）。请求头 value 总是字符串。
 *
 * <p>未配置 {@code FILTERS} / {@code FILTERS_FILE} 时全部关闭。
 */
public final class FilterEngine {

    private static final Set<String> ACTIONS = new HashSet<>(java.util.Arrays.asList(
            "set_header", "delete_header", "json_set", "json_delete"));

    private FilterEngine() {
    }

    /** scope 归一化结果。 */
    public static final class Scope {
        public final String type; // "all" | "provider"
        public final String id;   // provider id (type=provider 时)

        Scope(String type, String id) {
            this.type = type;
            this.id = id;
        }
    }

    /** 单条规则。 */
    public static final class Filter {
        public String name;
        public boolean enabled;
        public int priority;
        public Scope scope;
        public String stage;
        public String domain; // header | body
        public String action;
        public String target;
        public JsonNode value;
        public int seq;
    }

    /** applyFilters 的返回值。 */
    public static final class Result {
        public final List<String> applied;
        public final boolean bodyChanged;

        Result(List<String> applied, boolean bodyChanged) {
            this.applied = applied;
            this.bodyChanged = bodyChanged;
        }
    }

    /**
     * 从已解析的 JSON 数组字符串解析过滤器。无效项跳过；结果按 priority 升序排序
     * （priority 相同保持稳定的输入顺序）以保证应用顺序确定。
     */
    public static List<Filter> parseFilters(String raw, ObjectMapper mapper) {
        List<Filter> out = new ArrayList<>();
        if (raw == null) {
            return out;
        }
        String t = raw.trim();
        if (t.isEmpty()) {
            return out;
        }
        JsonNode arr;
        try {
            arr = mapper.readTree(t);
        } catch (Exception e) {
            return out;
        }
        if (arr == null || !arr.isArray()) {
            return out;
        }
        int auto = 0;
        for (JsonNode r : arr) {
            if (r == null || !r.isObject()) {
                continue;
            }
            String action = r.path("action").asText("").toLowerCase(Locale.ROOT);
            if (!ACTIONS.contains(action)) {
                continue;
            }
            String target = r.hasNonNull("target") ? r.get("target").asText() : "";
            if (target.isEmpty()) {
                continue;
            }
            Filter f = new Filter();
            f.name = r.hasNonNull("name") ? r.get("name").asText() : ("filter-" + auto);
            f.enabled = !(r.has("enabled") && !r.get("enabled").asBoolean(true)); // 默认开启
            f.priority = r.path("priority").isNumber() ? r.get("priority").asInt() : 0;
            f.scope = normScope(r.get("scope"));
            f.stage = "pre";
            f.domain = action.startsWith("json") ? "body" : "header";
            f.action = action;
            f.target = target;
            f.value = r.get("value");
            f.seq = auto++;
            out.add(f);
        }
        out.sort((a, b) -> {
            int c = Integer.compare(a.priority, b.priority);
            return c != 0 ? c : Integer.compare(a.seq, b.seq);
        });
        return out;
    }

    private static Scope normScope(JsonNode scope) {
        if (scope == null || scope.isNull()) {
            return new Scope("all", null);
        }
        if (scope.isTextual()) {
            String s = scope.asText();
            if (s.isEmpty() || "all".equals(s)) {
                return new Scope("all", null);
            }
            if (s.startsWith("provider:")) {
                String id = s.substring("provider:".length()).trim();
                if (!id.isEmpty()) {
                    return new Scope("provider", id);
                }
            }
            return new Scope("all", null);
        }
        if (scope.isObject() && "provider".equals(scope.path("type").asText(""))
                && scope.hasNonNull("id")) {
            return new Scope("provider", scope.get("id").asText());
        }
        return new Scope("all", null);
    }

    private static boolean inScope(Filter rule, String providerId) {
        if ("all".equals(rule.scope.type)) {
            return true;
        }
        return providerId != null && rule.scope.id.equals(providerId);
    }

    /** body 值强转：能解析成 JSON 就按 JSON，否则保持裸字符串（作为 TextNode）。 */
    private static JsonNode coerceValue(JsonNode value, ObjectMapper mapper) {
        if (value == null) {
            return mapper.nullNode();
        }
        if (!value.isTextual()) {
            return value; // 数字/布尔/对象等原样
        }
        String t = value.asText().trim();
        try {
            return mapper.readTree(t);
        } catch (Exception e) {
            return value; // 非 JSON 字符串保持原样
        }
    }

    private static void setPath(ObjectNode obj, String path, JsonNode value, ObjectMapper mapper) {
        String[] parts = splitPath(path);
        if (parts.length == 0) {
            return;
        }
        ObjectNode cur = obj;
        for (int i = 0; i < parts.length - 1; i++) {
            JsonNode next = cur.get(parts[i]);
            if (next == null || !next.isObject()) {
                ObjectNode created = mapper.createObjectNode();
                cur.set(parts[i], created);
                cur = created;
            } else {
                cur = (ObjectNode) next;
            }
        }
        cur.set(parts[parts.length - 1], value);
    }

    private static void deletePath(ObjectNode obj, String path) {
        String[] parts = splitPath(path);
        if (parts.length == 0) {
            return;
        }
        ObjectNode cur = obj;
        for (int i = 0; i < parts.length - 1; i++) {
            JsonNode next = cur.get(parts[i]);
            if (next == null || !next.isObject()) {
                return;
            }
            cur = (ObjectNode) next;
        }
        cur.remove(parts[parts.length - 1]);
    }

    private static String[] splitPath(String path) {
        String[] raw = path.split("\\.");
        List<String> parts = new ArrayList<>();
        for (String p : raw) {
            if (!p.isEmpty()) {
                parts.add(p);
            }
        }
        return parts.toArray(new String[0]);
    }

    /**
     * 对一次出站请求应用匹配且启用的过滤器。
     *
     * @param headers 小写键的可变请求头 map（原地修改）
     * @param body    已解析的请求体对象（非 JSON 时传 null）
     * @return {applied: 命中规则名列表, bodyChanged}
     */
    public static Result applyFilters(List<Filter> filters, String providerId,
                                      Map<String, String> headers, ObjectNode body, ObjectMapper mapper) {
        List<String> applied = new ArrayList<>();
        boolean bodyChanged = false;
        if (filters == null || filters.isEmpty()) {
            return new Result(applied, bodyChanged);
        }
        for (Filter rule : filters) {
            if (!rule.enabled || !inScope(rule, providerId)) {
                continue;
            }
            if ("header".equals(rule.domain)) {
                String key = rule.target.toLowerCase(Locale.ROOT);
                if ("set_header".equals(rule.action)) {
                    removeHeaderCaseInsensitive(headers, key);
                    String v = rule.value == null || rule.value.isNull() ? "" : asHeaderString(rule.value);
                    headers.put(key, v);
                    applied.add(rule.name);
                } else if ("delete_header".equals(rule.action)) {
                    boolean hit = removeHeaderCaseInsensitive(headers, key);
                    if (hit) {
                        applied.add(rule.name);
                    }
                }
            } else if ("body".equals(rule.domain) && body != null) {
                if ("json_set".equals(rule.action)) {
                    setPath(body, rule.target, coerceValue(rule.value, mapper), mapper);
                    bodyChanged = true;
                    applied.add(rule.name);
                } else if ("json_delete".equals(rule.action)) {
                    deletePath(body, rule.target);
                    bodyChanged = true;
                    applied.add(rule.name);
                }
            }
        }
        return new Result(applied, bodyChanged);
    }

    private static String asHeaderString(JsonNode value) {
        return value.isTextual() ? value.asText() : value.toString();
    }

    private static boolean removeHeaderCaseInsensitive(Map<String, String> headers, String lowerKey) {
        boolean hit = false;
        java.util.Iterator<String> it = headers.keySet().iterator();
        while (it.hasNext()) {
            if (it.next().toLowerCase(Locale.ROOT).equals(lowerKey)) {
                it.remove();
                hit = true;
            }
        }
        return hit;
    }

    /** UI / 内省用的紧凑摘要（无密钥）。 */
    public static List<Map<String, Object>> summarizeFilters(List<Filter> filters) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (filters == null) {
            return out;
        }
        for (Filter f : filters) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", f.name);
            m.put("enabled", f.enabled);
            m.put("priority", f.priority);
            m.put("scope", "all".equals(f.scope.type) ? "all" : ("provider:" + f.scope.id));
            m.put("action", f.action);
            m.put("target", f.target);
            out.add(m);
        }
        return out;
    }
}
