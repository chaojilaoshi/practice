package com.practice.cliproxy.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

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

    // ---- 弹性（全部 opt-in；不配置时默认行为完全不变）-------------------------
    /** 故障转移供应商池（JSON 数组）。回退读环境变量 PROVIDERS。 */
    private String providers;
    /** 供应商池 JSON 文件路径。回退读环境变量 PROVIDERS_FILE。 */
    private String providersFile;
    /** 开启熔断器（配置了供应商池时自动开启）。回退读环境变量 BREAKER。 */
    private Boolean breaker;
    /** provider 打开熔断前的失败次数。回退读环境变量 BREAKER_FAILURES。 */
    private Integer breakerFailures;
    /** OPEN 状态冷却毫秒数。回退读环境变量 BREAKER_COOLDOWN_MS。 */
    private Long breakerCooldownMs;
    /** 并发 half-open 探测数。回退读环境变量 BREAKER_HALFOPEN_MAX。 */
    private Integer breakerHalfOpenMax;
    /** 触发故障转移的 HTTP 状态码逗号列表。回退读环境变量 FAILOVER_STATUSES。 */
    private String failoverStatuses;
    /** 开启 Anthropic thinking 整流。回退读环境变量 RECTIFY / RECTIFIER。 */
    private Boolean rectify;
    /** 关闭 signature 子规则（设 false）。回退读环境变量 RECTIFY_SIGNATURE（"0" 关）。 */
    private Boolean rectifySignature;
    /** 关闭 budget 子规则（设 false）。回退读环境变量 RECTIFY_BUDGET（"0" 关）。 */
    private Boolean rectifyBudget;

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

    // ---- 弹性配置的 getter/setter + 解析助手 ---------------------------------

    public String getProviders() {
        return providers;
    }

    public void setProviders(String providers) {
        this.providers = providers;
    }

    public String getProvidersFile() {
        return providersFile;
    }

    public void setProvidersFile(String providersFile) {
        this.providersFile = providersFile;
    }

    public Boolean getBreaker() {
        return breaker;
    }

    public void setBreaker(Boolean breaker) {
        this.breaker = breaker;
    }

    public Integer getBreakerFailures() {
        return breakerFailures;
    }

    public void setBreakerFailures(Integer breakerFailures) {
        this.breakerFailures = breakerFailures;
    }

    public Long getBreakerCooldownMs() {
        return breakerCooldownMs;
    }

    public void setBreakerCooldownMs(Long breakerCooldownMs) {
        this.breakerCooldownMs = breakerCooldownMs;
    }

    public Integer getBreakerHalfOpenMax() {
        return breakerHalfOpenMax;
    }

    public void setBreakerHalfOpenMax(Integer breakerHalfOpenMax) {
        this.breakerHalfOpenMax = breakerHalfOpenMax;
    }

    public String getFailoverStatuses() {
        return failoverStatuses;
    }

    public void setFailoverStatuses(String failoverStatuses) {
        this.failoverStatuses = failoverStatuses;
    }

    public Boolean getRectify() {
        return rectify;
    }

    public void setRectify(Boolean rectify) {
        this.rectify = rectify;
    }

    public Boolean getRectifySignature() {
        return rectifySignature;
    }

    public void setRectifySignature(Boolean rectifySignature) {
        this.rectifySignature = rectifySignature;
    }

    public Boolean getRectifyBudget() {
        return rectifyBudget;
    }

    public void setRectifyBudget(Boolean rectifyBudget) {
        this.rectifyBudget = rectifyBudget;
    }

    /** 解析出供应商池的「原始 JSON 字符串」（交给 Providers.parse 解析）。 */
    public String resolveProvidersRaw() {
        String file = (providersFile != null && !providersFile.isEmpty()) ? providersFile : System.getenv("PROVIDERS_FILE");
        if (file != null && !file.isEmpty()) {
            try {
                return new String(Files.readAllBytes(Paths.get(file)), StandardCharsets.UTF_8);
            } catch (IOException e) {
                System.err.println("[config] failed to read PROVIDERS_FILE: " + e.getMessage());
            }
        }
        if (providers != null && !providers.isEmpty()) {
            return providers;
        }
        return System.getenv("PROVIDERS");
    }

    private static boolean truthy(String v) {
        if (v == null) {
            return false;
        }
        String s = v.toLowerCase(Locale.ROOT);
        return s.equals("1") || s.equals("on") || s.equals("true") || s.equals("yes");
    }

    /** 是否开启熔断器。配置项优先，否则回退裸环境变量 BREAKER。 */
    public boolean isBreakerEnabled() {
        if (breaker != null) {
            return breaker;
        }
        return truthy(System.getenv("BREAKER"));
    }

    public int resolveBreakerFailures() {
        if (breakerFailures != null) {
            return breakerFailures;
        }
        return intEnv("BREAKER_FAILURES", 5);
    }

    public long resolveBreakerCooldownMs() {
        if (breakerCooldownMs != null) {
            return breakerCooldownMs;
        }
        return intEnv("BREAKER_COOLDOWN_MS", 30000);
    }

    public int resolveBreakerHalfOpenMax() {
        if (breakerHalfOpenMax != null) {
            return breakerHalfOpenMax;
        }
        return intEnv("BREAKER_HALFOPEN_MAX", 1);
    }

    /** 触发故障转移的状态码集合。配置项优先，否则回退环境变量，最后默认 429/5xx。 */
    public Set<Integer> resolveFailoverStatuses() {
        String raw = (failoverStatuses != null && !failoverStatuses.isEmpty())
                ? failoverStatuses : System.getenv("FAILOVER_STATUSES");
        Set<Integer> out = new LinkedHashSet<>();
        if (raw != null && !raw.trim().isEmpty()) {
            for (String tok : raw.split(",")) {
                try {
                    out.add(Integer.parseInt(tok.trim()));
                } catch (NumberFormatException ignore) {
                    // skip
                }
            }
        }
        if (out.isEmpty()) {
            out.addAll(Arrays.asList(429, 500, 502, 503, 504));
        }
        return out;
    }

    /** 是否开启 Anthropic thinking 整流。配置项优先，否则回退 RECTIFY / RECTIFIER。 */
    public boolean isRectifyEnabled() {
        if (rectify != null) {
            return rectify;
        }
        return truthy(System.getenv("RECTIFY")) || truthy(System.getenv("RECTIFIER"));
    }

    public boolean isRectifySignatureEnabled() {
        if (rectifySignature != null) {
            return rectifySignature;
        }
        return !"0".equals(System.getenv("RECTIFY_SIGNATURE"));
    }

    public boolean isRectifyBudgetEnabled() {
        if (rectifyBudget != null) {
            return rectifyBudget;
        }
        return !"0".equals(System.getenv("RECTIFY_BUDGET"));
    }

    private static int intEnv(String name, int fallback) {
        String v = System.getenv(name);
        if (v == null || v.isEmpty()) {
            return fallback;
        }
        try {
            return Integer.parseInt(v.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }
}
