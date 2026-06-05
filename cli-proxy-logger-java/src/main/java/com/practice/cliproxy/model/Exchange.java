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
}
