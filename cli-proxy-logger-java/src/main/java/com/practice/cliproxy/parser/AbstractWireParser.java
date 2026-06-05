package com.practice.cliproxy.parser;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 解析器基类：封装 Jackson 的安全解析与工具参数解析。
 */
public abstract class AbstractWireParser implements WireParser {

    protected final ObjectMapper mapper;

    protected AbstractWireParser(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    /** 安全解析为 JsonNode；失败返回 null。 */
    protected JsonNode tree(String body) {
        if (body == null || body.isEmpty()) {
            return null;
        }
        try {
            return mapper.readTree(body);
        } catch (Exception e) {
            return null;
        }
    }

    /** 把累积的工具参数 JSON 字符串解析为对象；非法 JSON 时保留原文。 */
    protected Object parseToolArgs(String raw) {
        if (raw == null || raw.isEmpty()) {
            return new LinkedHashMap<String, Object>();
        }
        try {
            return mapper.readValue(raw, Object.class);
        } catch (Exception e) {
            Map<String, Object> fallback = new LinkedHashMap<>();
            fallback.put("_raw", raw);
            return fallback;
        }
    }

    protected Object toObject(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        try {
            return mapper.treeToValue(node, Object.class);
        } catch (Exception e) {
            return null;
        }
    }
}
