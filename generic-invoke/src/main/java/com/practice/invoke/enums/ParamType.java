package com.practice.invoke.enums;

/**
 * 入参形态：决定入参如何拼到 HTTP 请求上。
 */
public enum ParamType {
    /** 入参作为 JSON 请求体（POST/PUT 常用）。 */
    BODY,
    /** 入参作为 URL query 参数（GET 常用）。 */
    QUERY,
    /** 入参作为路径变量，path 中以 {key} 占位，运行时替换。 */
    PATH
}
