package com.practice.cliproxy.resilience;

/**
 * 供应商池里的一个上游条目。Node/Python 里的 {id, group, baseUrl, apiKey}。
 *
 * <p>{@code apiKey} 可选：存在时用它替换客户端的鉴权头（不同厂商不同 key）；
 * 不存在（null）时原样转发客户端自己的凭证（透明默认）。
 */
public final class Provider {
    public final String id;
    public final String group;   // "anthropic" | "openai"
    public final String baseUrl; // 末尾斜杠已去除
    public final String apiKey;  // 可空

    public Provider(String id, String group, String baseUrl, String apiKey) {
        this.id = id;
        this.group = group;
        this.baseUrl = baseUrl;
        this.apiKey = apiKey;
    }
}
