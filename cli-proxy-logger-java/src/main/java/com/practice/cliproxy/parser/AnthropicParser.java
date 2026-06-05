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
 * Anthropic Messages API 解析器（Claude Code，POST /v1/messages）。
 *
 * 流式工具调用：content_block_start(type=tool_use){id,name}
 *   -> content_block_delta(input_json_delta).partial_json 累积
 *   -> content_block_stop
 * 非流式：content[] 里 type=tool_use。
 */
@Component
public class AnthropicParser extends AbstractWireParser {

    public AnthropicParser(ObjectMapper mapper) {
        super(mapper);
    }

    @Override
    public String wire() {
        return "anthropic";
    }

    @Override
    public NormalizedRequest parseRequest(String body) {
        NormalizedRequest req = new NormalizedRequest();
        req.wire = "anthropic";
        JsonNode root = tree(body);
        if (root == null) {
            req.raw = body;
            return req;
        }
        req.raw = toObject(root);
        req.model = root.path("model").asText(null);
        JsonNode system = root.get("system");
        if (system != null && system.isTextual()) {
            req.system = system.asText();
        }
        req.stream = root.path("stream").asBoolean(false);
        for (JsonNode t : root.path("tools")) {
            req.tools.add(new NormalizedRequest.ToolDef(t.path("name").asText(null), t.path("description").asText(null)));
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
        res.stopReason = root.path("stop_reason").asText(null);
        res.usage = toObject(root.get("usage"));
        for (JsonNode b : root.path("content")) {
            String type = b.path("type").asText("");
            if ("text".equals(type)) {
                res.textBuilder.append(b.path("text").asText(""));
            } else if ("tool_use".equals(type)) {
                res.toolCalls.add(new ToolCall(b.path("id").asText(null), b.path("name").asText(null), toObject(b.get("input"))));
            }
        }
        return res;
    }

    @Override
    public StreamAggregator newAggregator() {
        return new Agg();
    }

    private class Agg implements StreamAggregator {
        private final NormalizedResponse res = new NormalizedResponse();
        private final Map<Integer, Block> blocks = new HashMap<>();

        @Override
        public void feed(SseEvent evt) {
            JsonNode data = tree(evt.data);
            if (data == null) {
                return;
            }
            String type = evt.event != null ? evt.event : data.path("type").asText(null);
            if (type == null) {
                return;
            }
            switch (type) {
                case "message_start":
                    res.usage = toObject(data.path("message").get("usage"));
                    break;
                case "content_block_start": {
                    JsonNode cb = data.path("content_block");
                    Block b = new Block();
                    b.type = cb.path("type").asText("");
                    b.id = cb.path("id").asText(null);
                    b.name = cb.path("name").asText(null);
                    blocks.put(data.path("index").asInt(), b);
                    break;
                }
                case "content_block_delta": {
                    JsonNode d = data.path("delta");
                    String dt = d.path("type").asText("");
                    if ("text_delta".equals(dt)) {
                        res.textBuilder.append(d.path("text").asText(""));
                    } else if ("input_json_delta".equals(dt)) {
                        Block b = blocks.get(data.path("index").asInt());
                        if (b != null) {
                            b.argText.append(d.path("partial_json").asText(""));
                        }
                    }
                    break;
                }
                case "content_block_stop": {
                    Block b = blocks.get(data.path("index").asInt());
                    if (b != null && "tool_use".equals(b.type)) {
                        res.toolCalls.add(new ToolCall(b.id, b.name, parseToolArgs(b.argText.toString())));
                    }
                    break;
                }
                case "message_delta":
                    JsonNode sr = data.path("delta").get("stop_reason");
                    if (sr != null && !sr.isNull()) {
                        res.stopReason = sr.asText();
                    }
                    if (data.has("usage")) {
                        res.usage = toObject(data.get("usage"));
                    }
                    break;
                default:
                    break;
            }
        }

        @Override
        public NormalizedResponse result() {
            return res;
        }
    }

    private static class Block {
        String type;
        String id;
        String name;
        final StringBuilder argText = new StringBuilder();
    }
}
