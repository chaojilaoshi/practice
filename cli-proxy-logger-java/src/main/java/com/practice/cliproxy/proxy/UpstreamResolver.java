package com.practice.cliproxy.proxy;

import com.practice.cliproxy.config.ProxyProperties;
import org.springframework.stereotype.Component;

/**
 * 据请求路径 / 鉴权头判断目标上游与 wire 类型，让同一个端口同时服务
 * Claude Code（Anthropic）与 Codex（OpenAI）。
 */
@Component
public class UpstreamResolver {

    public static class Upstream {
        public final String baseUrl;
        public final String wire;

        public Upstream(String baseUrl, String wire) {
            this.baseUrl = baseUrl;
            this.wire = wire;
        }
    }

    private final ProxyProperties props;

    public UpstreamResolver(ProxyProperties props) {
        this.props = props;
    }

    public Upstream resolve(String path, String xApiKey, String anthropicVersion) {
        String p = path == null ? "" : path;
        if (p.startsWith("/v1/messages")) {
            return new Upstream(props.getAnthropicUpstream(), "anthropic");
        }
        if (p.startsWith("/v1/responses")) {
            return new Upstream(props.getOpenaiUpstream(), "responses");
        }
        if (p.startsWith("/v1/chat/completions")) {
            return new Upstream(props.getOpenaiUpstream(), "chat");
        }
        // 兜底：Anthropic 用 x-api-key + anthropic-version，OpenAI 用 Bearer。
        if (xApiKey != null || anthropicVersion != null) {
            return new Upstream(props.getAnthropicUpstream(), "anthropic");
        }
        return new Upstream(props.getOpenaiUpstream(), "chat");
    }
}
