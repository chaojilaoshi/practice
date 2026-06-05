package com.practice.cliproxy.model;

import java.util.ArrayList;
import java.util.List;

/**
 * 归一化后的请求视图（三种 wire 格式共用）。
 */
public class NormalizedRequest {
    public String wire;
    public String model;
    public String system;
    public boolean stream;
    /** 提供给模型的工具定义（名称 + 描述）。 */
    public List<ToolDef> tools = new ArrayList<>();
    /** 原始请求体（解析失败时仍保留）。 */
    public Object raw;

    public static class ToolDef {
        public String name;
        public String description;

        public ToolDef() {
        }

        public ToolDef(String name, String description) {
            this.name = name;
            this.description = description;
        }
    }
}
