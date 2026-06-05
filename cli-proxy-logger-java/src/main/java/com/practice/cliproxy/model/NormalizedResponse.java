package com.practice.cliproxy.model;

import com.fasterxml.jackson.annotation.JsonIgnore;

import java.util.ArrayList;
import java.util.List;

/**
 * 归一化后的响应视图（三种 wire 格式共用）。
 */
public class NormalizedResponse {
    @JsonIgnore
    public StringBuilder textBuilder = new StringBuilder();
    public List<ToolCall> toolCalls = new ArrayList<>();
    public String stopReason;
    public Object usage;
    public Object raw;
    public String parseError;

    /** 供 Jackson 序列化为 "text" 字段。 */
    public String getText() {
        return textBuilder.toString();
    }
}
