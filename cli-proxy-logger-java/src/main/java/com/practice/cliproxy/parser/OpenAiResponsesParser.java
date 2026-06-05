package com.practice.cliproxy.parser;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.practice.cliproxy.model.NormalizedRequest;
import com.practice.cliproxy.model.NormalizedResponse;
import com.practice.cliproxy.model.ToolCall;
import com.practice.cliproxy.sse.SseParser.SseEvent;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

/**
 * OpenAI Responses API 解析器（Codex 默认，POST /v1/responses，wire_api="responses"）。
 *
 * 流式工具调用：response.output_item.added(item.type=function_call){call_id,name}
 *   -> response.function_call_arguments.delta 累积 arguments
 *   -> response.function_call_arguments.done / response.output_item.done
 * 非流式：output[] 里 type=function_call。
 */
@Component
public class OpenAiResponsesParser extends AbstractWireParser {

    public OpenAiResponsesParser(ObjectMapper mapper) {
        super(mapper);
    }

    @Override
    public String wire() {
        return "responses";
    }

    @Override
    public NormalizedRequest parseRequest(String body) {
        NormalizedRequest req = new NormalizedRequest();
        req.wire = "responses";
        JsonNode root = tree(body);
        if (root == null) {
            req.raw = body;
            return req;
        }
        req.raw = toObject(root);
        req.model = root.path("model").asText(null);
        JsonNode instr = root.get("instructions");
        if (instr != null && instr.isTextual()) {
            req.system = instr.asText();
        }
        req.stream = root.path("stream").asBoolean(false);
        for (JsonNode t : root.path("tools")) {
            String name = t.path("name").asText(t.path("function").path("name").asText(null));
            String desc = t.path("description").asText(t.path("function").path("description").asText(null));
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
        res.stopReason = root.path("status").asText(null);
        res.usage = toObject(root.get("usage"));
        for (JsonNode item : root.path("output")) {
            if ("function_call".equals(item.path("type").asText(""))) {
                String id = item.path("call_id").asText(item.path("id").asText(null));
                res.toolCalls.add(new ToolCall(id, item.path("name").asText(null), parseToolArgs(item.path("arguments").asText(""))));
            }
        }
        return res;
    }

    @Override
    public StreamAggregator newAggregator() {
        return new Agg();
    }

    // Responses API 流式发出「语义事件」，事件名本身已说明在发生什么
    // （response.output_item.added、...function_call_arguments.delta ...）。每个输出
    // 项有稳定的 item_id，因此按 item_id 累积工具调用参数，待该项 done 时产出 ToolCall。
    private class Agg implements StreamAggregator {
        private final NormalizedResponse res = new NormalizedResponse();
        private final Map<String, Call> calls = new HashMap<>();

        @Override
        public void feed(SseEvent evt) {
            JsonNode data = tree(evt.data);
            if (data == null) {
                return;
            }
            // 事件名可能在 SSE 的「event:」行，也可能在 JSON 的 type 字段。
            String type = evt.event != null ? evt.event : data.path("type").asText(null);
            if (type == null) {
                return;
            }
            switch (type) {
                case "response.output_item.added": {
                    // 新输出项出现；若是函数调用就记下它。
                    JsonNode item = data.path("item");
                    if ("function_call".equals(item.path("type").asText(""))) {
                        Call c = new Call();
                        c.id = item.path("call_id").asText(item.path("id").asText(null));
                        c.name = item.path("name").asText(null);
                        calls.put(item.path("id").asText(data.path("item_id").asText(null)), c);
                    }
                    break;
                }
                case "response.function_call_arguments.delta": {
                    // 参数 JSON 以片段流入 -> 按 item_id 拼接。
                    Call c = calls.get(data.path("item_id").asText(null));
                    if (c != null) {
                        c.argText.append(data.path("delta").asText(""));
                    }
                    break;
                }
                case "response.function_call_arguments.done": {
                    // 有些服务端在这里一次性给出完整 arguments 而非 delta；仅当从未
                    // 收到任何 delta 片段时才用它兜底。
                    Call c = calls.get(data.path("item_id").asText(null));
                    if (c != null && c.argText.length() == 0 && data.has("arguments")) {
                        c.argText.append(data.path("arguments").asText(""));
                    }
                    break;
                }
                case "response.output_item.done": {
                    // 该项结束 -> 产出工具调用（解析累积的参数）。
                    JsonNode item = data.path("item");
                    if ("function_call".equals(item.path("type").asText(""))) {
                        String key = item.path("id").asText(data.path("item_id").asText(null));
                        Call c = calls.remove(key);
                        if (c == null) {
                            c = new Call();
                            c.id = item.path("call_id").asText(item.path("id").asText(null));
                            c.name = item.path("name").asText(null);
                            c.argText.append(item.path("arguments").asText(""));
                        }
                        res.toolCalls.add(new ToolCall(c.id, c.name, parseToolArgs(c.argText.toString())));
                    }
                    break;
                }
                case "response.output_text.delta":
                    res.textBuilder.append(data.path("delta").asText(""));
                    break;
                case "response.completed":
                case "response.incomplete":
                case "response.failed": {
                    JsonNode r = data.path("response");
                    if (r.has("usage")) {
                        res.usage = toObject(r.get("usage"));
                    }
                    if (r.has("status")) {
                        res.stopReason = r.path("status").asText(null);
                    }
                    // 兜底：把未显式 done 的工具调用补上
                    for (Call c : calls.values()) {
                        res.toolCalls.add(new ToolCall(c.id, c.name, parseToolArgs(c.argText.toString())));
                    }
                    calls.clear();
                    break;
                }
                default:
                    break;
            }
        }

        @Override
        public NormalizedResponse result() {
            return res;
        }
    }

    private static class Call {
        String id;
        String name;
        final StringBuilder argText = new StringBuilder();
    }
}
