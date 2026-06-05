package com.practice.cliproxy.model;

import java.util.Map;

/**
 * 一次完整的请求/响应往返记录（落盘 + 提供给 UI）。
 */
public class Exchange {
    public String id;
    public String ts;
    public long durationMs;
    public String wire;
    public String method;
    public String url;
    public Map<String, String> reqHeaders;
    public String requestBodyRaw;
    public NormalizedRequest request;
    public int resStatus;
    public Map<String, String> resHeaders;
    public NormalizedResponse response;
    public String error;
    /**
     * 协议翻译元数据（仅当 ANTHROPIC_COMPAT=chat 把 /v1/messages 转成
     * /v1/chat/completions 时存在）：记录 {from,to,model,upstreamModel}。
     * 透明转发时为 null。
     */
    public Map<String, Object> translation;
}
