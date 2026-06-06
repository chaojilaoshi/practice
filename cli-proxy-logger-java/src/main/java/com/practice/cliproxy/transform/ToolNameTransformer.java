package com.practice.cliproxy.transform;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * 工具名规范化（工具名规范化）—— opt-in。Node {@code src/transform.js} /
 * Python {@code transform.py} 的等价实现。
 *
 * <p>为什么需要：部分 Anthropic 兼容上游（如 anyrouter）会「校验工具名」，并拒绝
 * opencode 等客户端发出的小写名（{@code read}、{@code write}、{@code edit}……）。把
 * 它们改成 PascalCase（{@code Read}、{@code Write}、{@code Edit}）后上游才接受。本模块
 * 在请求上改写工具名（关键修复），并可选地在响应上同样改写以保持往返一致；同时
 * 修复被上游序列化成 JSON「字符串」的 tool_use input 中的数组/对象值。
 *
 * <p>默认全关。开启 {@code TOOL_NAME_CASE} 后，代理对该 Anthropic 流量不再是字节级
 * 透明转发——这正是该特性的目的，且只影响主动开启的请求。
 */
public final class ToolNameTransformer {

    /** 简单首字母大写不符合预期时的内置特例。可用 TOOL_NAME_MAP / TOOL_NAME_MAP_FILE 扩展/覆盖。 */
    public static final Map<String, String> DEFAULT_TOOL_NAME_MAP;

    static {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("todowrite", "TodoWrite");
        m.put("todoread", "TodoRead");
        m.put("webfetch", "WebFetch");
        m.put("websearch", "WebSearch");
        m.put("google_search", "Google_Search");
        m.put("multiedit", "MultiEdit");
        m.put("notebookedit", "NotebookEdit");
        m.put("notebookread", "NotebookRead");
        DEFAULT_TOOL_NAME_MAP = Collections.unmodifiableMap(m);
    }

    private ToolNameTransformer() {
    }

    /**
     * 构造工具名映射：内置特例打底，叠加用户 JSON（TOOL_NAME_MAP 内联或文件）。
     * 键大小写敏感；内置键为小写。raw 为空或非 JSON 对象时只返回内置表。
     */
    public static Map<String, String> buildToolNameMap(String raw, ObjectMapper mapper) {
        Map<String, String> map = new LinkedHashMap<>(DEFAULT_TOOL_NAME_MAP);
        if (raw == null) {
            return map;
        }
        String t = raw.trim();
        if (!t.startsWith("{")) {
            return map;
        }
        try {
            JsonNode obj = mapper.readTree(t);
            if (obj != null && obj.isObject()) {
                Iterator<Map.Entry<String, JsonNode>> it = obj.fields();
                while (it.hasNext()) {
                    Map.Entry<String, JsonNode> e = it.next();
                    if (e.getValue() != null && e.getValue().isTextual()) {
                        map.put(e.getKey(), e.getValue().asText());
                    }
                }
            }
        } catch (Exception ignore) {
            // 忽略畸形 map
        }
        return map;
    }

    /**
     * 映射单个工具名。map 中精确命中优先；否则小写键再查一次；都没有就首字母大写
     * （与用户给的参考反代行为一致）。
     */
    public static String mapToolName(String name, Map<String, String> map) {
        if (name == null || name.isEmpty()) {
            return name;
        }
        Map<String, String> m = map != null ? map : Collections.emptyMap();
        if (m.containsKey(name)) {
            return m.get(name);
        }
        String lower = name.toLowerCase(Locale.ROOT);
        if (m.containsKey(lower)) {
            return m.get(lower);
        }
        return Character.toUpperCase(name.charAt(0)) + name.substring(1);
    }

    /**
     * 在 Anthropic /v1/messages「请求」体上原地改写工具名：覆盖 {@code tools[].name}
     * 与 messages 历史里的任意 {@code tool_use} 块。返回改动的名字数量。
     */
    public static int normalizeRequestToolNames(JsonNode body, Map<String, String> map) {
        if (body == null || !body.isObject()) {
            return 0;
        }
        int changed = 0;
        JsonNode tools = body.get("tools");
        if (tools != null && tools.isArray()) {
            for (JsonNode tool : tools) {
                if (tool != null && tool.isObject() && tool.path("name").isTextual()) {
                    changed += rename((ObjectNode) tool, map);
                }
            }
        }
        JsonNode messages = body.get("messages");
        if (messages != null && messages.isArray()) {
            for (JsonNode msg : messages) {
                if (msg == null || !msg.path("content").isArray()) {
                    continue;
                }
                for (JsonNode block : msg.get("content")) {
                    if (block != null && block.isObject()
                            && "tool_use".equals(block.path("type").asText(""))
                            && block.path("name").isTextual()) {
                        changed += rename((ObjectNode) block, map);
                    }
                }
            }
        }
        return changed;
    }

    private static int rename(ObjectNode obj, Map<String, String> map) {
        String current = obj.path("name").asText();
        String next = mapToolName(current, map);
        if (!next.equals(current)) {
            obj.put("name", next);
            return 1;
        }
        return 0;
    }

    /** 在 Anthropic「响应」体上原地改写 tool_use 名字。返回改动数量。 */
    public static int rewriteResponseToolNames(JsonNode body, Map<String, String> map) {
        if (body == null || !body.path("content").isArray()) {
            return 0;
        }
        int changed = 0;
        for (JsonNode block : body.get("content")) {
            if (block != null && block.isObject()
                    && "tool_use".equals(block.path("type").asText(""))
                    && block.path("name").isTextual()) {
                changed += rename((ObjectNode) block, map);
            }
        }
        return changed;
    }

    /**
     * 修复 tool_use input：上游把数组/对象值序列化成了 JSON「字符串」（某些厂商的
     * 已知怪癖）。把这些字符串解析回真正的数组/对象，原地修改。返回修复的值数量。
     */
    public static int repairToolUseInput(JsonNode body, ObjectMapper mapper) {
        if (body == null || !body.path("content").isArray()) {
            return 0;
        }
        int repaired = 0;
        for (JsonNode block : body.get("content")) {
            if (block == null || !block.isObject() || !"tool_use".equals(block.path("type").asText(""))) {
                continue;
            }
            JsonNode input = block.get("input");
            if (input == null || !input.isObject()) {
                continue;
            }
            ObjectNode in = (ObjectNode) input;
            Iterator<String> names = in.fieldNames();
            // 拷一份键，避免边遍历边改。
            java.util.List<String> keys = new java.util.ArrayList<>();
            while (names.hasNext()) {
                keys.add(names.next());
            }
            for (String key : keys) {
                JsonNode val = in.get(key);
                if (val == null || !val.isTextual()) {
                    continue;
                }
                String t = val.asText().trim();
                if (!(t.startsWith("[") || t.startsWith("{"))) {
                    continue;
                }
                try {
                    in.set(key, mapper.readTree(t));
                    repaired += 1;
                } catch (Exception ignore) {
                    // 非 JSON 字符串保持原样
                }
            }
        }
        return repaired;
    }

    /**
     * 改写已解析的 SSE {@code content_block_start} 事件对象里携带的工具名，原地修改。
     * 名字有变化时返回 true。
     */
    public static boolean rewriteStreamEventToolName(JsonNode data, Map<String, String> map) {
        if (data != null && data.isObject()
                && "content_block_start".equals(data.path("type").asText(""))) {
            JsonNode cb = data.get("content_block");
            if (cb != null && cb.isObject()
                    && "tool_use".equals(cb.path("type").asText(""))
                    && cb.path("name").isTextual()) {
                String current = cb.path("name").asText();
                String next = mapToolName(current, map);
                if (!next.equals(current)) {
                    ((ObjectNode) cb).put("name", next);
                    return true;
                }
            }
        }
        return false;
    }
}
