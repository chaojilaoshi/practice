package com.practice.cliproxy;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 本地 CLI 请求拦截/日志工具入口（学习用 Spring Boot 版）。
 *
 * 代理 + Web UI 共用一个端口（默认 8788）：
 *   - 代理上游请求：/v1/**（Claude Code 的 /v1/messages、Codex 的 /v1/responses 等）
 *   - 浏览界面：    /            （static/index.html）
 *   - 查询接口：    /api/exchanges、/api/exchanges/{id}
 */
@SpringBootApplication
public class CliProxyLoggerApplication {

    public static void main(String[] args) {
        SpringApplication.run(CliProxyLoggerApplication.class, args);
    }
}
