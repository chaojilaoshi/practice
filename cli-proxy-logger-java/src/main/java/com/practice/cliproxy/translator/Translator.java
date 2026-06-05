package com.practice.cliproxy.translator;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 协议翻译：Anthropic Messages  &lt;-&gt;  OpenAI Chat Completions。
 *
 * <h3>这个类为什么存在</h3>
 * 代理通常是「透明」直通：把客户端字节原样转发给上游，再把回应逐字节回写。
 * 当 CLI 与上游说同一种 wire 格式时，这样就够了。
 *
 * <p>但有些第三方厂商只暴露 {@code /v1/chat/completions}（OpenAI Chat 格式），
 * 而 Claude Code（及 opencode 的 anthropic provider）只会说 Anthropic
 * {@code /v1/messages}。要打通它们，必须在「两个方向」上翻译：
 *
 * <pre>
 *   客户端 (Anthropic /v1/messages 请求)
 *        -&gt; [anthropicRequestToChat]  -&gt; 厂商 (OpenAI /v1/chat/completions)
 *   厂商 (OpenAI Chat 回应, JSON 或 SSE)
 *        -&gt; [chatResponseToAnthropic / ChatToAnthropicStream] -&gt; 客户端 (Anthropic)
 * </pre>
 *
 * <p>这是 opt-in（{@code ANTHROPIC_COMPAT=chat}）。关闭时走透明路径，本类完全不被触碰。
 *
 * <p>最难的是「流式」回应翻译：OpenAI 流式发出 {@code choices[].delta} chunk
 * （文本在 {@code delta.content}，工具调用按 {@code delta.tool_calls[].index} 拆散），
 * 而 Anthropic 流式发出一串「带 index 的 content block」（message_start -&gt;
 * content_block_start/delta/stop ... -&gt; message_delta -&gt; message_stop）。我们边收边
 * 重建 Anthropic 事件序列，见 {@link ChatToAnthropicStream}。
 */
@Component
public class Translator {

    private final ObjectMapper mapper;

    public Translator(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    public String toJson(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (Exception e) {
            return "{}";
        }
    }

    /** 安全解析 JSON 为对象；失败返回 null。 */
    public Object parseJson(String raw) {
        if (raw == null || raw.isEmpty()) {
            return null;
        }
        try {
            return mapper.readValue(raw, Object.class);
        } catch (Exception e) {
            return null;
        }
    }

    // ----- 模型映射 ---------------------------------------------------------
    // 用户配置一张映射表，把 CLI 发来的模型名（如 "claude-sonnet-4-6"）映射成厂商
    // 实际提供的模型名（如 "gpt-4o"）。不在表里的模型原样透传。
    public static String mapModel(String model, Map<String, String> modelMap) {
        if (model == null) {
            return null;
        }
        if (modelMap != null && modelMap.containsKey(model)) {
            return modelMap.get(model);
        }
        return model;
    }

    // ----- helpers ---------------------------------------------------------

    // Anthropic 的 system 可能是字符串，也可能是 text block 数组 -> 拍平成纯文本。
    private static String systemToText(JsonNode system) {
        if (system == null || system.isNull()) {
            return "";
        }
        if (system.isTextual()) {
            return system.asText();
        }
        if (system.isArray()) {
            StringBuilder sb = new StringBuilder();
            for (JsonNode b : system) {
                if (b.isObject() && "text".equals(b.path("type").asText()) && b.path("text").isTextual()) {
                    sb.append(b.path("text").asText());
                }
            }
            return sb.toString();
        }
        return "";
    }

    // Anthropic tool_result 的 content（字符串或 block 数组）-> 纯字符串，因为
    // OpenAI 的 tool 消息只接受字符串。
    private String toolResultToText(JsonNode content) {
        if (content == null || content.isNull()) {
            return "";
        }
        if (content.isTextual()) {
            return content.asText();
        }
        if (content.isArray()) {
            StringBuilder sb = new StringBuilder();
            for (JsonNode b : content) {
                if (b.isTextual()) {
                    sb.append(b.asText());
                } else if (b.isObject() && "text".equals(b.path("type").asText())) {
                    sb.append(b.path("text").asText(""));
                } else {
                    sb.append(b.toString());
                }
            }
            return sb.toString();
        }
        return content.toString();
    }

    // Anthropic 的 user content block -> OpenAI content（字符串，或含 text + image_url
    // 的 parts 列表）。纯文本这一常见情形折叠成字符串。
    private Object userContentToChat(JsonNode content) {
        if (content == null || content.isNull()) {
            return "";
        }
        if (content.isTextual()) {
            return content.asText();
        }
        if (!content.isArray()) {
            return "";
        }
        List<Map<String, Object>> parts = new ArrayList<>();
        for (JsonNode b : content) {
            if (!b.isObject()) {
                continue;
            }
            String type = b.path("type").asText();
            if ("text".equals(type)) {
                Map<String, Object> part = new LinkedHashMap<>();
                part.put("type", "text");
                part.put("text", b.path("text").asText(""));
                parts.add(part);
            } else if ("image".equals(type) && b.path("source").isObject()) {
                JsonNode src = b.path("source");
                String srcType = src.path("type").asText();
                Map<String, Object> imageUrl = new LinkedHashMap<>();
                if ("base64".equals(srcType)) {
                    imageUrl.put("url", "data:" + src.path("media_type").asText() + ";base64," + src.path("data").asText());
                } else if ("url".equals(srcType)) {
                    imageUrl.put("url", src.path("url").asText());
                } else {
                    continue;
                }
                Map<String, Object> part = new LinkedHashMap<>();
                part.put("type", "image_url");
                part.put("image_url", imageUrl);
                parts.add(part);
            }
        }
        if (parts.size() == 1 && "text".equals(parts.get(0).get("type"))) {
            return parts.get(0).get("text");
        }
        if (parts.isEmpty()) {
            return "";
        }
        return parts;
    }

    // ----- 请求：Anthropic /v1/messages -> OpenAI /v1/chat/completions ------
    //
    // Anthropic 请求形状：
    //   { model, system, max_tokens, temperature, top_p, stop_sequences, stream,
    //     tools:[{name,description,input_schema}], tool_choice,
    //     messages:[{role:'user'|'assistant', content: 字符串 | block[]}] }
    //   block 类型：text | image | tool_use(assistant) | tool_result(user)
    //
    // OpenAI Chat 请求形状：
    //   { model, messages:[{role, content, tool_calls, tool_call_id}],
    //     tools:[{type:'function',function:{name,description,parameters}}],
    //     tool_choice, max_tokens, temperature, top_p, stop, stream, stream_options }
    public Map<String, Object> anthropicRequestToChat(JsonNode anth, Map<String, String> modelMap) {
        List<Map<String, Object>> messages = new ArrayList<>();

        // 1) system 提示 -> 一条置顶的 system 消息。
        String sysText = systemToText(anth.get("system"));
        if (!sysText.isEmpty()) {
            Map<String, Object> sys = new LinkedHashMap<>();
            sys.put("role", "system");
            sys.put("content", sysText);
            messages.add(sys);
        }

        // 2) 逐轮遍历对话。
        JsonNode msgs = anth.path("messages");
        if (msgs.isArray()) {
            for (JsonNode m : msgs) {
                if (!m.isObject()) {
                    continue;
                }
                JsonNode content = m.get("content");
                String role = m.path("role").asText("");

                if ("assistant".equals(role)) {
                    // assistant 轮：text -> content；每个 tool_use -> 一个 tool_calls 项
                    // （OpenAI 把工具调用放在 content 之外）。
                    StringBuilder text = new StringBuilder();
                    List<Map<String, Object>> toolCalls = new ArrayList<>();
                    if (content != null && content.isTextual()) {
                        text.append(content.asText());
                    } else if (content != null && content.isArray()) {
                        for (JsonNode b : content) {
                            if (!b.isObject()) {
                                continue;
                            }
                            String type = b.path("type").asText();
                            if ("text".equals(type)) {
                                text.append(b.path("text").asText(""));
                            } else if ("tool_use".equals(type)) {
                                JsonNode input = b.get("input");
                                String arguments = (input == null || input.isNull()) ? "{}" : input.toString();
                                Map<String, Object> fn = new LinkedHashMap<>();
                                fn.put("name", b.path("name").asText(null));
                                fn.put("arguments", arguments);
                                Map<String, Object> call = new LinkedHashMap<>();
                                call.put("id", b.path("id").asText(null));
                                call.put("type", "function");
                                call.put("function", fn);
                                toolCalls.add(call);
                            }
                        }
                    }
                    Map<String, Object> msg = new LinkedHashMap<>();
                    msg.put("role", "assistant");
                    msg.put("content", text.length() > 0 ? text.toString() : null);
                    if (!toolCalls.isEmpty()) {
                        msg.put("tool_calls", toolCalls);
                    }
                    messages.add(msg);
                    continue;
                }

                // user 轮：每个 tool_result block 变成一条单独的 `tool` 消息（通过
                // tool_call_id 关联回 assistant 的调用）；其余 text/image block 合成
                // 一条 `user` 消息。
                if (content != null && content.isArray()) {
                    List<JsonNode> rest = new ArrayList<>();
                    for (JsonNode b : content) {
                        if (b.isObject() && "tool_result".equals(b.path("type").asText())) {
                            Map<String, Object> toolMsg = new LinkedHashMap<>();
                            toolMsg.put("role", "tool");
                            toolMsg.put("tool_call_id", b.path("tool_use_id").asText(null));
                            toolMsg.put("content", toolResultToText(b.get("content")));
                            messages.add(toolMsg);
                        } else {
                            rest.add(b);
                        }
                    }
                    // 把剩余 block 重新组成一个数组节点交给 userContentToChat。
                    com.fasterxml.jackson.databind.node.ArrayNode restArr = mapper.createArrayNode();
                    for (JsonNode b : rest) {
                        restArr.add(b);
                    }
                    Object userContent = userContentToChat(restArr);
                    boolean empty = "".equals(userContent)
                            || (userContent instanceof List && ((List<?>) userContent).isEmpty());
                    if (!empty) {
                        Map<String, Object> userMsg = new LinkedHashMap<>();
                        userMsg.put("role", "user");
                        userMsg.put("content", userContent);
                        messages.add(userMsg);
                    }
                } else {
                    Map<String, Object> userMsg = new LinkedHashMap<>();
                    userMsg.put("role", "user");
                    userMsg.put("content", userContentToChat(content));
                    messages.add(userMsg);
                }
            }
        }

        Map<String, Object> out = new LinkedHashMap<>();
        boolean stream = anth.path("stream").asBoolean(false);
        out.put("model", mapModel(anth.path("model").asText(null), modelMap));
        out.put("messages", messages);
        out.put("stream", stream);

        // 3) 采样 / 上限参数（仅在出现时带上）。
        if (anth.hasNonNull("max_tokens")) {
            out.put("max_tokens", anth.get("max_tokens").asInt());
        }
        if (anth.hasNonNull("temperature")) {
            out.put("temperature", anth.get("temperature").asDouble());
        }
        if (anth.hasNonNull("top_p")) {
            out.put("top_p", anth.get("top_p").asDouble());
        }
        if (anth.path("stop_sequences").isArray() && anth.path("stop_sequences").size() > 0) {
            List<String> stop = new ArrayList<>();
            for (JsonNode s : anth.path("stop_sequences")) {
                stop.add(s.asText());
            }
            out.put("stop", stop);
        }

        // 4) 工具定义：Anthropic input_schema -> OpenAI function.parameters。
        if (anth.path("tools").isArray() && anth.path("tools").size() > 0) {
            List<Map<String, Object>> tools = new ArrayList<>();
            for (JsonNode t : anth.path("tools")) {
                Map<String, Object> fn = new LinkedHashMap<>();
                fn.put("name", t.path("name").asText(null));
                fn.put("description", t.path("description").asText(""));
                JsonNode schema = t.get("input_schema");
                if (schema != null && !schema.isNull()) {
                    fn.put("parameters", jsonNodeToObject(schema));
                } else {
                    Map<String, Object> empty = new LinkedHashMap<>();
                    empty.put("type", "object");
                    empty.put("properties", new LinkedHashMap<>());
                    fn.put("parameters", empty);
                }
                Map<String, Object> tool = new LinkedHashMap<>();
                tool.put("type", "function");
                tool.put("function", fn);
                tools.add(tool);
            }
            out.put("tools", tools);
        }

        // 5) tool_choice: {type:'auto'|'any'|'tool', name?} -> 'auto'|'required'|{...}。
        JsonNode tc = anth.get("tool_choice");
        if (tc != null && tc.isObject()) {
            String type = tc.path("type").asText();
            if ("auto".equals(type)) {
                out.put("tool_choice", "auto");
            } else if ("any".equals(type)) {
                out.put("tool_choice", "required");
            } else if ("tool".equals(type) && tc.path("name").isTextual()) {
                Map<String, Object> fn = new LinkedHashMap<>();
                fn.put("name", tc.path("name").asText());
                Map<String, Object> choice = new LinkedHashMap<>();
                choice.put("type", "function");
                choice.put("function", fn);
                out.put("tool_choice", choice);
            }
        }

        // 6) 请求厂商在流式最后一个 chunk 里带上 token usage，以便我们在 Anthropic
        // 的 message_delta 事件里回传真实用量。
        if (stream) {
            Map<String, Object> streamOptions = new LinkedHashMap<>();
            streamOptions.put("include_usage", true);
            out.put("stream_options", streamOptions);
        }

        return out;
    }

    private Object jsonNodeToObject(JsonNode node) {
        try {
            return mapper.treeToValue(node, Object.class);
        } catch (Exception e) {
            return new LinkedHashMap<String, Object>();
        }
    }

    // ----- finish_reason / usage 映射 --------------------------------------
    static String mapStopReason(String finishReason, boolean hadToolCalls) {
        if (hadToolCalls) {
            return "tool_use";
        }
        if ("stop".equals(finishReason)) {
            return "end_turn";
        }
        if ("length".equals(finishReason)) {
            return "max_tokens";
        }
        if ("tool_calls".equals(finishReason) || "function_call".equals(finishReason)) {
            return "tool_use";
        }
        if ("content_filter".equals(finishReason)) {
            return "end_turn";
        }
        return finishReason != null ? "end_turn" : null;
    }

    static Map<String, Object> mapUsage(JsonNode usage) {
        if (usage == null || !usage.isObject()) {
            return null;
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("input_tokens", usage.path("prompt_tokens").asInt(0));
        out.put("output_tokens", usage.path("completion_tokens").asInt(0));
        return out;
    }

    private static String newMessageId() {
        return "msg_" + UUID.randomUUID().toString().replace("-", "");
    }

    private static String newToolUseId() {
        return "toolu_" + UUID.randomUUID().toString().replace("-", "");
    }

    // ----- 回应（非流式）：OpenAI Chat JSON -> Anthropic message -------------
    public Map<String, Object> chatResponseToAnthropic(JsonNode chatObj, String displayModel) {
        JsonNode choice = chatObj.path("choices").path(0);
        JsonNode msg = choice.path("message");
        List<Map<String, Object>> content = new ArrayList<>();

        if (msg.path("content").isTextual() && !msg.path("content").asText().isEmpty()) {
            Map<String, Object> block = new LinkedHashMap<>();
            block.put("type", "text");
            block.put("text", msg.path("content").asText());
            content.add(block);
        }
        JsonNode toolCalls = msg.path("tool_calls");
        boolean hadToolCalls = toolCalls.isArray() && toolCalls.size() > 0;
        if (toolCalls.isArray()) {
            for (JsonNode tc : toolCalls) {
                Object args = parseJson(tc.path("function").path("arguments").asText(""));
                Map<String, Object> block = new LinkedHashMap<>();
                block.put("type", "tool_use");
                block.put("id", tc.path("id").asText(newToolUseId()));
                block.put("name", tc.path("function").path("name").asText(""));
                block.put("input", args != null ? args : new LinkedHashMap<String, Object>());
                content.add(block);
            }
        }
        if (content.isEmpty()) {
            Map<String, Object> block = new LinkedHashMap<>();
            block.put("type", "text");
            block.put("text", "");
            content.add(block);
        }

        Map<String, Object> usage = mapUsage(chatObj.get("usage"));
        if (usage == null) {
            usage = new LinkedHashMap<>();
            usage.put("input_tokens", 0);
            usage.put("output_tokens", 0);
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", chatObj.path("id").asText(newMessageId()));
        out.put("type", "message");
        out.put("role", "assistant");
        String model = displayModel != null && !displayModel.isEmpty() ? displayModel : chatObj.path("model").asText("");
        out.put("model", model);
        out.put("content", content);
        out.put("stop_reason", mapStopReason(choice.path("finish_reason").asText(null), hadToolCalls));
        out.put("stop_sequence", null);
        out.put("usage", usage);
        return out;
    }

    // OpenAI 错误体 -> Anthropic 错误信封，让 Claude Code 能理解。
    public Map<String, Object> chatErrorToAnthropic(JsonNode obj, String fallbackText) {
        JsonNode e = (obj != null && obj.isObject() && obj.has("error")) ? obj.get("error") : obj;
        Map<String, Object> error = new LinkedHashMap<>();
        String type = (e != null && e.path("type").isTextual()) ? e.path("type").asText() : "api_error";
        String message;
        if (e != null && e.path("message").isTextual()) {
            message = e.path("message").asText();
        } else if (fallbackText != null && !fallbackText.isEmpty()) {
            message = fallbackText;
        } else {
            message = "upstream error";
        }
        error.put("type", type);
        error.put("message", message);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("type", "error");
        out.put("error", error);
        return out;
    }

    // ----- SSE 序列化 -------------------------------------------------------
    // Anthropic 的 SSE 帧在 `event:` 行命名事件类型，同时在 JSON `data:` 载荷里
    // 重复一次 —— Claude Code 两处都会读。
    String sse(String event, Object data) {
        return "event: " + event + "\ndata: " + toJson(data) + "\n\n";
    }

    // ----- 回应（流式）：OpenAI Chat SSE -> Anthropic SSE --------------------
    public ChatToAnthropicStream newStream(String displayModel) {
        return new ChatToAnthropicStream(displayModel);
    }

    /**
     * 把一串 OpenAI Chat chunk 翻译成 Anthropic 事件序列。
     *
     * <p>OpenAI 每个 chunk：{@code choices[0].delta = { content?, tool_calls?[{index,id,function:{name,arguments}}] }}
     * <p>Anthropic block：第 0 块通常是 assistant 文本；每个工具调用各自成块。一个块
     * 必须被「开启（content_block_start）→ 流式（content_block_delta）→ 关闭
     * （content_block_stop）」。
     *
     * <p>映射规则：
     * <ul>
     *   <li>首个 chunk            -&gt; 发 message_start</li>
     *   <li>首个 text delta       -&gt; 开一个 text 块，随后流式 text_delta</li>
     *   <li>某工具的首个 chunk    -&gt; 关闭当前块，开一个 tool_use 块（带 id+name），随后流式 input_json_delta</li>
     *   <li>流结束               -&gt; 关闭当前块，发 message_delta（含 stop_reason+usage）再发 message_stop</li>
     * </ul>
     *
     * <p>{@link #feed(JsonNode)} 与 {@link #end()} 各自「返回」一组待写出的 SSE 字符串。
     */
    public class ChatToAnthropicStream {
        private final String displayModel;
        private boolean started;
        private int nextIndex;           // 下一个要分配的 Anthropic block index
        private Integer openIndex;       // 当前已开启块的 Anthropic index（null=无）
        private Integer textIndex;       // （唯一的）text 块的 Anthropic index
        private final Map<Integer, Integer> tools = new LinkedHashMap<>(); // OpenAI tool index -> Anthropic index
        private String finishReason;
        private JsonNode usage;
        private final List<String> out = new ArrayList<>();

        ChatToAnthropicStream(String displayModel) {
            this.displayModel = displayModel != null ? displayModel : "";
        }

        private void emit(String event, Object data) {
            out.add(sse(event, data));
        }

        private List<String> flush() {
            List<String> o = new ArrayList<>(out);
            out.clear();
            return o;
        }

        private void ensureStarted() {
            if (started) {
                return;
            }
            started = true;
            Map<String, Object> usageObj = new LinkedHashMap<>();
            usageObj.put("input_tokens", 0);
            usageObj.put("output_tokens", 0);
            Map<String, Object> message = new LinkedHashMap<>();
            message.put("id", newMessageId());
            message.put("type", "message");
            message.put("role", "assistant");
            message.put("model", displayModel);
            message.put("content", new ArrayList<>());
            message.put("stop_reason", null);
            message.put("stop_sequence", null);
            message.put("usage", usageObj);
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("type", "message_start");
            data.put("message", message);
            emit("message_start", data);
        }

        private void closeOpen() {
            if (openIndex != null) {
                Map<String, Object> data = new LinkedHashMap<>();
                data.put("type", "content_block_stop");
                data.put("index", openIndex);
                emit("content_block_stop", data);
                if (textIndex != null && textIndex.equals(openIndex)) {
                    textIndex = null;
                }
                openIndex = null;
            }
        }

        public List<String> feed(JsonNode chunk) {
            ensureStarted();
            if (chunk.has("usage") && !chunk.get("usage").isNull()) {
                usage = chunk.get("usage");
            }
            JsonNode choice = chunk.path("choices").path(0);
            if (choice.isMissingNode()) {
                return flush();
            }
            if (choice.path("finish_reason").isTextual()) {
                finishReason = choice.path("finish_reason").asText();
            }
            JsonNode delta = choice.path("delta");

            // --- 文本 ---
            if (delta.path("content").isTextual() && !delta.path("content").asText().isEmpty()) {
                if (openIndex == null || !openIndex.equals(textIndex)) {
                    closeOpen();
                    textIndex = nextIndex++;
                    openIndex = textIndex;
                    Map<String, Object> block = new LinkedHashMap<>();
                    block.put("type", "text");
                    block.put("text", "");
                    Map<String, Object> data = new LinkedHashMap<>();
                    data.put("type", "content_block_start");
                    data.put("index", textIndex);
                    data.put("content_block", block);
                    emit("content_block_start", data);
                }
                Map<String, Object> deltaObj = new LinkedHashMap<>();
                deltaObj.put("type", "text_delta");
                deltaObj.put("text", delta.path("content").asText());
                Map<String, Object> data = new LinkedHashMap<>();
                data.put("type", "content_block_delta");
                data.put("index", textIndex);
                data.put("delta", deltaObj);
                emit("content_block_delta", data);
            }

            // --- 工具调用 ---
            JsonNode toolCalls = delta.path("tool_calls");
            if (toolCalls.isArray()) {
                for (JsonNode tc : toolCalls) {
                    int oidx = tc.path("index").asInt(0);
                    Integer aidx = tools.get(oidx);
                    if (aidx == null) {
                        // 某工具调用的首个片段：关闭当前块，开一个 tool_use 块
                        // （带 id+name，参数随后流式追加）。
                        closeOpen();
                        aidx = nextIndex++;
                        tools.put(oidx, aidx);
                        openIndex = aidx;
                        JsonNode fn = tc.path("function");
                        Map<String, Object> block = new LinkedHashMap<>();
                        block.put("type", "tool_use");
                        block.put("id", tc.path("id").asText(newToolUseId()));
                        block.put("name", fn.path("name").asText(""));
                        block.put("input", new LinkedHashMap<>());
                        Map<String, Object> data = new LinkedHashMap<>();
                        data.put("type", "content_block_start");
                        data.put("index", aidx);
                        data.put("content_block", block);
                        emit("content_block_start", data);
                    }
                    String argFrag = tc.path("function").path("arguments").asText("");
                    if (!argFrag.isEmpty()) {
                        Map<String, Object> deltaObj = new LinkedHashMap<>();
                        deltaObj.put("type", "input_json_delta");
                        deltaObj.put("partial_json", argFrag);
                        Map<String, Object> data = new LinkedHashMap<>();
                        data.put("type", "content_block_delta");
                        data.put("index", aidx);
                        data.put("delta", deltaObj);
                        emit("content_block_delta", data);
                    }
                }
            }

            return flush();
        }

        public List<String> end() {
            ensureStarted();  // 优雅处理空流
            closeOpen();
            Map<String, Object> deltaObj = new LinkedHashMap<>();
            deltaObj.put("stop_reason", mapStopReason(finishReason, !tools.isEmpty()));
            deltaObj.put("stop_sequence", null);
            Map<String, Object> usageObj = mapUsage(usage);
            if (usageObj == null) {
                usageObj = new LinkedHashMap<>();
                usageObj.put("output_tokens", 0);
            }
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("type", "message_delta");
            data.put("delta", deltaObj);
            data.put("usage", usageObj);
            emit("message_delta", data);

            Map<String, Object> stop = new LinkedHashMap<>();
            stop.put("type", "message_stop");
            emit("message_stop", stop);
            return flush();
        }
    }

    /**
     * 由一份「完整的」chat completion 构造整段 Anthropic SSE 序列。
     *
     * <p>当客户端要求流式、但厂商回了单段 JSON body 时使用——我们仍欠客户端一个事件流。
     */
    @SuppressWarnings("unchecked")
    public List<String> anthropicMessageToSse(Map<String, Object> anthObj) {
        List<String> frames = new ArrayList<>();

        Map<String, Object> usageIn = (Map<String, Object>) anthObj.get("usage");
        Object inputTokens = usageIn != null ? usageIn.get("input_tokens") : 0;

        Map<String, Object> usageObj = new LinkedHashMap<>();
        usageObj.put("input_tokens", inputTokens != null ? inputTokens : 0);
        usageObj.put("output_tokens", 0);
        Map<String, Object> message = new LinkedHashMap<>();
        message.put("id", anthObj.get("id"));
        message.put("type", "message");
        message.put("role", "assistant");
        message.put("model", anthObj.get("model"));
        message.put("content", new ArrayList<>());
        message.put("stop_reason", null);
        message.put("stop_sequence", null);
        message.put("usage", usageObj);
        Map<String, Object> startData = new LinkedHashMap<>();
        startData.put("type", "message_start");
        startData.put("message", message);
        frames.add(sse("message_start", startData));

        List<Map<String, Object>> content = (List<Map<String, Object>>) anthObj.get("content");
        for (int index = 0; index < content.size(); index++) {
            Map<String, Object> block = content.get(index);
            String type = String.valueOf(block.get("type"));
            if ("text".equals(type)) {
                Map<String, Object> cb = new LinkedHashMap<>();
                cb.put("type", "text");
                cb.put("text", "");
                Map<String, Object> startBlock = new LinkedHashMap<>();
                startBlock.put("type", "content_block_start");
                startBlock.put("index", index);
                startBlock.put("content_block", cb);
                frames.add(sse("content_block_start", startBlock));

                Map<String, Object> deltaObj = new LinkedHashMap<>();
                deltaObj.put("type", "text_delta");
                deltaObj.put("text", block.get("text"));
                Map<String, Object> deltaData = new LinkedHashMap<>();
                deltaData.put("type", "content_block_delta");
                deltaData.put("index", index);
                deltaData.put("delta", deltaObj);
                frames.add(sse("content_block_delta", deltaData));
            } else if ("tool_use".equals(type)) {
                Map<String, Object> cb = new LinkedHashMap<>();
                cb.put("type", "tool_use");
                cb.put("id", block.get("id"));
                cb.put("name", block.get("name"));
                cb.put("input", new LinkedHashMap<>());
                Map<String, Object> startBlock = new LinkedHashMap<>();
                startBlock.put("type", "content_block_start");
                startBlock.put("index", index);
                startBlock.put("content_block", cb);
                frames.add(sse("content_block_start", startBlock));

                Map<String, Object> deltaObj = new LinkedHashMap<>();
                deltaObj.put("type", "input_json_delta");
                deltaObj.put("partial_json", toJson(block.get("input") != null ? block.get("input") : new LinkedHashMap<>()));
                Map<String, Object> deltaData = new LinkedHashMap<>();
                deltaData.put("type", "content_block_delta");
                deltaData.put("index", index);
                deltaData.put("delta", deltaObj);
                frames.add(sse("content_block_delta", deltaData));
            }
            Map<String, Object> stopData = new LinkedHashMap<>();
            stopData.put("type", "content_block_stop");
            stopData.put("index", index);
            frames.add(sse("content_block_stop", stopData));
        }

        Map<String, Object> deltaObj = new LinkedHashMap<>();
        deltaObj.put("stop_reason", anthObj.get("stop_reason"));
        deltaObj.put("stop_sequence", anthObj.get("stop_sequence"));
        Map<String, Object> usageOut = new LinkedHashMap<>();
        Map<String, Object> usageSrc = (Map<String, Object>) anthObj.get("usage");
        usageOut.put("output_tokens", usageSrc != null ? usageSrc.get("output_tokens") : 0);
        Map<String, Object> deltaData = new LinkedHashMap<>();
        deltaData.put("type", "message_delta");
        deltaData.put("delta", deltaObj);
        deltaData.put("usage", usageOut);
        frames.add(sse("message_delta", deltaData));

        Map<String, Object> stop = new LinkedHashMap<>();
        stop.put("type", "message_stop");
        frames.add(sse("message_stop", stop));
        return frames;
    }

    /** Anthropic 流式 error 事件（厂商出错且客户端处于流式模式时用）。 */
    public String anthropicErrorSse(JsonNode obj, String fallbackText) {
        return sse("error", chatErrorToAnthropic(obj, fallbackText));
    }

    // ----- 模型映射解析 -----------------------------------------------------
    // 支持两种写法：JSON 对象 {"a":"b"} 或逗号分隔 "a=b,c=d"。
    public Map<String, String> parseModelMap(String raw) {
        Map<String, String> map = new LinkedHashMap<>();
        if (raw == null || raw.trim().isEmpty()) {
            return map;
        }
        String trimmed = raw.trim();
        if (trimmed.startsWith("{")) {
            try {
                JsonNode node = mapper.readTree(trimmed);
                node.fields().forEachRemaining(e -> {
                    if (e.getValue().isTextual()) {
                        map.put(e.getKey(), e.getValue().asText());
                    }
                });
                return map;
            } catch (Exception ignored) {
                // 落到逗号分隔解析
            }
        }
        for (String pair : trimmed.split(",")) {
            int eq = pair.indexOf('=');
            if (eq > 0) {
                map.put(pair.substring(0, eq).trim(), pair.substring(eq + 1).trim());
            }
        }
        return map;
    }
}
