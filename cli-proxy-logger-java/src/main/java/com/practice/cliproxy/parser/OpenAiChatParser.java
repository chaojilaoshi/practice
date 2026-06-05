package com.practice.cliproxy.parser;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.practice.cliproxy.model.NormalizedRequest;
import com.practice.cliproxy.model.NormalizedResponse;
import com.practice.cliproxy.model.ToolCall;
import com.practice.cliproxy.sse.SseParser.SseEvent;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.TreeMap;

/**
 * OpenAI Chat Completions 解析器（Codex chat 模式 / 兼容 API，POST /v1/chat/completions）。
 *
 * 流式工具调用：choices[].delta.tool_calls[]，按 index 聚合 function.name + function.arguments；
 * 流以 "data: [DONE]" 结束。非流式：choices[].message.tool_calls[]。
 */
@Component
public class OpenAiChatParser extends AbstractWireParser {

    public OpenAiChatParser(ObjectMapper mapper) {
        super(mapper);
    }

    @Override
    public String wire() {
        return "chat";
    }

    @Override
    public NormalizedRequest parseRequest(String body) {
        NormalizedRequest req = new NormalizedRequest();
        req.wire = "chat";
        JsonNode root = tree(body);
        if (root == null) {
            req.raw = body;
            return req;
        }
        req.raw = toObject(root);
        req.model = root.path("model").asText(null);
        req.stream = root.path("stream").asBoolean(false);
        StringBuilder system = new StringBuilder();
        for (JsonNode m : root.path("messages")) {
            String role = m.path("role").asText("");
            if ("system".equals(role) || "developer".equals(role)) {
                if (system.length() > 0) {
                    system.append('\n');
                }
                system.append(m.path("content").asText(""));
            }
        }
        if (system.length() > 0) {
            req.system = system.toString();
        }
        for (JsonNode t : root.path("tools")) {
            String name = t.path("function").path("name").asText(t.path("name").asText(null));
            String desc = t.path("function").path("description").asText(t.path("description").asText(null));
            req.tools.add(new NormalizedRequest.ToolDef(name, desc));
        }
        return req;
    }

    @Override
    public NormalizedResponse parseResponse(String body) {
        NormalizedResponse res = new NormalizedResponse();
        JsonNode root = tree(body);
        if (root == null) {
            res.raw = body;
            return res;
        }
        res.raw = toObject(root);
        res.usage = toObject(root.get("usage"));
        JsonNode choice = root.path("choices").path(0);
        if (!choice.isMissingNode()) {
            res.stopReason = choice.path("finish_reason").asText(null);
            JsonNode msg = choice.path("message");
            if (msg.path("content").isTextual()) {
                res.textBuilder.append(msg.path("content").asText(""));
            }
            for (JsonNode tc : msg.path("tool_calls")) {
                res.toolCalls.add(new ToolCall(tc.path("id").asText(null), tc.path("function").path("name").asText(null),
                        parseToolArgs(tc.path("function").path("arguments").asText(""))));
            }
        }
        return res;
    }

    @Override
    public StreamAggregator newAggregator() {
        return new Agg();
    }

    // Chat Completions 流式发出「chunk」，其 choices[].delta 携带增量内容。与 Responses
    // API 不同，这里没有按工具的事件：一个工具调用被拆散在多个 chunk 中，仅靠位置
    // tool_calls[].index 标识。因此按 index 累积 name+arguments——某 index 的首个 chunk
    // 带 id+name，其余 chunk 带参数片段。"[DONE]" 是流终止哨兵，不是 JSON。
    private class Agg implements StreamAggregator {
        private final NormalizedResponse res = new NormalizedResponse();
        private final TreeMap<Integer, Call> calls = new TreeMap<>();
        private boolean built;

        @Override
        public void feed(SseEvent evt) {
            String raw = evt.data == null ? "" : evt.data.trim();
            if (raw.isEmpty() || "[DONE]".equals(raw)) {
                return;
            }
            JsonNode data = tree(raw);
            if (data == null) {
                return;
            }
            if (data.has("usage") && !data.get("usage").isNull()) {
                res.usage = toObject(data.get("usage"));
            }
            JsonNode choice = data.path("choices").path(0);
            if (choice.isMissingNode()) {
                return;
            }
            if (choice.path("finish_reason").isTextual()) {
                res.stopReason = choice.path("finish_reason").asText();
            }
            JsonNode delta = choice.path("delta");
            if (delta.path("content").isTextual()) {
                res.textBuilder.append(delta.path("content").asText(""));
            }
            for (JsonNode tc : delta.path("tool_calls")) {
                // 按 index 归组：首见即开槽，之后合并 id/name 并追加参数片段。
                int idx = tc.path("index").asInt(0);
                Call c = calls.computeIfAbsent(idx, k -> new Call());
                if (tc.path("id").isTextual()) {
                    c.id = tc.path("id").asText();
                }
                if (tc.path("function").path("name").isTextual()) {
                    c.name = tc.path("function").path("name").asText();
                }
                if (tc.path("function").path("arguments").isTextual()) {
                    c.argText.append(tc.path("function").path("arguments").asText());
                }
            }
        }

        @Override
        public NormalizedResponse result() {
            if (!built) {
                for (Map.Entry<Integer, Call> e : calls.entrySet()) {
                    Call c = e.getValue();
                    res.toolCalls.add(new ToolCall(c.id, c.name, parseToolArgs(c.argText.toString())));
                }
                built = true;
            }
            return res;
        }
    }

    private static class Call {
        String id;
        String name;
        final StringBuilder argText = new StringBuilder();
    }
}
