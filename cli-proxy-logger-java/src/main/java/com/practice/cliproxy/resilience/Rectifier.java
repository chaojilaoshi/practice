package com.practice.cliproxy.resilience;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.Iterator;

/**
 * 请求整流器（请求整流器）—— 针对部分 provider 会拒绝的 Anthropic「thinking」
 * 请求的、可选的自动修复。Node {@code src/rectifier.js} / Python {@code rectifier.py}
 * 的等价实现。
 *
 * <p>仅作用于 Anthropic wire（/v1/messages），因为 thinking 及其 {@code signature}
 * 字段是 Anthropic 概念。代理检测到特定上游错误后，对请求体做最小改写，对同一
 * provider 重试一次；仍失败则回到正常流程（可能再故障转移到下一个 provider）。
 *
 * <p>两种整流（各自可独立开关）：
 * <ol>
 *   <li>signature —— 删掉历史 thinking/redacted_thinking 块以及别的 provider 认不了的
 *       {@code signature} 字段，再重试。</li>
 *   <li>budget —— 用安全的 budget(32000) 开启 thinking，并在需要时把 {@code max_tokens}
 *       提到至少 64000，再重试。</li>
 * </ol>
 */
public final class Rectifier {

    public static final String SIGNATURE = "signature";
    public static final String BUDGET = "budget";

    private Rectifier() {
    }

    /** 返回 "signature" | "budget" | null。 */
    public static String detect(int status, JsonNode errObj, boolean signatureOn, boolean budgetOn) {
        // 只有 4xx 客户端请求错误可整流；5xx 是上游故障（应走故障转移）。
        if (status >= 500) {
            return null;
        }
        String text = errorText(errObj).toLowerCase();
        if (text.isEmpty()) {
            return null;
        }
        if (budgetOn) {
            if (text.contains("budget_tokens") || (text.contains("budget") && text.contains("thinking"))) {
                return BUDGET;
            }
        }
        if (signatureOn) {
            if (text.contains("signature")) {
                return SIGNATURE;
            }
        }
        return null;
    }

    private static String errorText(JsonNode errObj) {
        if (errObj == null || errObj.isNull()) {
            return "";
        }
        if (errObj.isTextual()) {
            return errObj.asText();
        }
        if (errObj.isObject()) {
            JsonNode err = errObj.get("error");
            if (err != null && err.isObject() && err.hasNonNull("message")) {
                return err.get("message").asText();
            }
            if (errObj.hasNonNull("message")) {
                return errObj.get("message").asText();
            }
            return errObj.toString();
        }
        return errObj.toString();
    }

    /** 删掉 thinking/redacted_thinking 块，并去掉剩余块的 signature 字段。 */
    public static ObjectNode rectifySignature(JsonNode body, ObjectMapper mapper) {
        ObjectNode clone = cloneObj(body, mapper);
        JsonNode messages = clone.get("messages");
        if (messages != null && messages.isArray()) {
            for (JsonNode m : messages) {
                if (!m.isObject()) {
                    continue;
                }
                JsonNode content = m.get("content");
                if (content == null || !content.isArray()) {
                    continue;
                }
                ArrayNode kept = mapper.createArrayNode();
                for (JsonNode block : content) {
                    if (block.isObject()) {
                        String type = block.path("type").asText("");
                        if ("thinking".equals(type) || "redacted_thinking".equals(type)) {
                            continue; // 丢弃整块
                        }
                        ObjectNode b = (ObjectNode) block.deepCopy();
                        b.remove(SIGNATURE);
                        kept.add(b);
                    } else {
                        kept.add(block);
                    }
                }
                ((ObjectNode) m).set("content", kept);
            }
        }
        return clone;
    }

    /** 开启 thinking（budget 32000），并把 max_tokens 提到至少 64000。 */
    public static ObjectNode rectifyBudget(JsonNode body, ObjectMapper mapper) {
        ObjectNode clone = cloneObj(body, mapper);
        ObjectNode thinking = mapper.createObjectNode();
        thinking.put("type", "enabled");
        thinking.put("budget_tokens", 32000);
        clone.set("thinking", thinking);
        JsonNode mt = clone.get("max_tokens");
        if (mt == null || !mt.isInt() || mt.asInt() < 64000) {
            clone.put("max_tokens", 64000);
        }
        return clone;
    }

    public static JsonNode apply(String kind, JsonNode body, ObjectMapper mapper) {
        if (SIGNATURE.equals(kind)) {
            return rectifySignature(body, mapper);
        }
        if (BUDGET.equals(kind)) {
            return rectifyBudget(body, mapper);
        }
        return body;
    }

    private static ObjectNode cloneObj(JsonNode body, ObjectMapper mapper) {
        if (body != null && body.isObject()) {
            return (ObjectNode) body.deepCopy();
        }
        return mapper.createObjectNode();
    }
}
