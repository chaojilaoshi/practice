package com.practice.cliproxy.model;

/**
 * 一次工具调用：id / 工具名 / 解析后的参数对象。
 */
public class ToolCall {
    public String id;
    public String name;
    public Object args;

    public ToolCall() {
    }

    public ToolCall(String id, String name, Object args) {
        this.id = id;
        this.name = name;
        this.args = args;
    }
}
