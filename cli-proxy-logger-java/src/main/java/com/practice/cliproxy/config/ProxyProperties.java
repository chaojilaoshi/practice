package com.practice.cliproxy.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 代理配置（前缀 proxy.*，见 application.yml）。
 */
@Component
@ConfigurationProperties(prefix = "proxy")
public class ProxyProperties {

    /** Anthropic 上游（Claude Code）。 */
    private String anthropicUpstream = "https://api.anthropic.com";
    /** OpenAI 上游（Codex）。 */
    private String openaiUpstream = "https://api.openai.com";
    /** JSONL 日志目录。 */
    private String logDir = "./logs";
    /** 落盘时是否对 x-api-key / authorization 脱敏。 */
    private boolean redactAuth = true;
    /** 单条 body 落盘上限（字节），超出截断。 */
    private int maxBodyBytes = 2_000_000;

    public String getAnthropicUpstream() {
        return anthropicUpstream;
    }

    public void setAnthropicUpstream(String anthropicUpstream) {
        this.anthropicUpstream = anthropicUpstream;
    }

    public String getOpenaiUpstream() {
        return openaiUpstream;
    }

    public void setOpenaiUpstream(String openaiUpstream) {
        this.openaiUpstream = openaiUpstream;
    }

    public String getLogDir() {
        return logDir;
    }

    public void setLogDir(String logDir) {
        this.logDir = logDir;
    }

    public boolean isRedactAuth() {
        return redactAuth;
    }

    public void setRedactAuth(boolean redactAuth) {
        this.redactAuth = redactAuth;
    }

    public int getMaxBodyBytes() {
        return maxBodyBytes;
    }

    public void setMaxBodyBytes(int maxBodyBytes) {
        this.maxBodyBytes = maxBodyBytes;
    }
}
