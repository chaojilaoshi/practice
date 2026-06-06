package com.practice.cliproxy;

import com.practice.cliproxy.config.SettingsManager;
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
        // 端口必须在监听器绑定前确定：GUI/打包工作流以 config.json（或首启的环境变量种子）
        // 里的 proxyPort 为准，写入 server.port。其余配置在启动后由 SettingsService 写回。
        // 纯开发/测试（无 config.json、未打包）保持 application.yml / 命令行参数原有行为。
        if (System.getProperty("server.port") == null) {
            SettingsManager.Result r = SettingsManager.readSettings();
            if ("file".equals(r.source) || SettingsManager.isPackaged()) {
                Object port = r.settings.get("proxyPort");
                if (port != null) {
                    System.setProperty("server.port", String.valueOf(port));
                }
            }
        }
        SpringApplication.run(CliProxyLoggerApplication.class, args);
    }
}
