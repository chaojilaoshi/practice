package com.practice.cliproxy.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Locale;

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

    /**
     * 协议翻译开关。设为 "chat" 时，把进来的 Anthropic /v1/messages 翻译成 OpenAI
     * /v1/chat/completions 发往 openaiUpstream（见 Translator）。空=透明直通（默认）。
     * 可用 proxy.anthropic-compat（即 PROXY_ANTHROPIC_COMPAT）配置；为与 Node/Python
     * 版统一，未配置时回退读取裸环境变量 ANTHROPIC_COMPAT。
     */
    private String anthropicCompat;
    /** 模型映射（JSON 对象 {"a":"b"} 或逗号分隔 "a=b,c=d"）。回退读环境变量 MODEL_MAP。 */
    private String modelMap;
    /** 模型映射文件路径（JSON）。回退读环境变量 MODEL_MAP_FILE。 */
    private String modelMapFile;

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

    public String getAnthropicCompat() {
        return anthropicCompat;
    }

    public void setAnthropicCompat(String anthropicCompat) {
        this.anthropicCompat = anthropicCompat;
    }

    public String getModelMap() {
        return modelMap;
    }

    public void setModelMap(String modelMap) {
        this.modelMap = modelMap;
    }

    public String getModelMapFile() {
        return modelMapFile;
    }

    public void setModelMapFile(String modelMapFile) {
        this.modelMapFile = modelMapFile;
    }

    /** 是否开启 Anthropic -> Chat 翻译。配置项优先，否则回退裸环境变量 ANTHROPIC_COMPAT。 */
    public boolean isAnthropicToChat() {
        String v = anthropicCompat;
        if (v == null || v.isEmpty()) {
            v = System.getenv("ANTHROPIC_COMPAT");
        }
        return v != null && "chat".equals(v.toLowerCase(Locale.ROOT));
    }

    /**
     * 解析出模型映射的「原始字符串」（交给 Translator.parseModelMap 解析）。
     * 优先级：proxy.model-map-file / MODEL_MAP_FILE 文件内容 &gt; proxy.model-map / MODEL_MAP。
     * 没有任何配置时返回 null。
     */
    public String resolveModelMapRaw() {
        String file = (modelMapFile != null && !modelMapFile.isEmpty()) ? modelMapFile : System.getenv("MODEL_MAP_FILE");
        if (file != null && !file.isEmpty()) {
            try {
                return new String(Files.readAllBytes(Paths.get(file)), StandardCharsets.UTF_8);
            } catch (IOException e) {
                System.err.println("[config] failed to read MODEL_MAP_FILE: " + e.getMessage());
            }
        }
        if (modelMap != null && !modelMap.isEmpty()) {
            return modelMap;
        }
        return System.getenv("MODEL_MAP");
    }
}
