package com.practice.cliproxy.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.practice.cliproxy.proxy.ProxyController;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;

import java.awt.Desktop;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 可视化配置的运行期桥梁：把 {@link SettingsManager} 读出的「GUI 形状 settings」写回
 * {@link ProxyProperties}（运行期绑定对象），并触发 {@link ProxyController#reloadFromProps()}
 * 实现「保存即生效」。镜像 Node {@code index.js} 的 applySettings / 启动逻辑。
 *
 * <p>Java 版代理与 Web UI 共用单端口（server.port = settings.proxyPort）；端口变更需重启
 * 进程（当前监听器无法热重绑），保存时通过返回值告知前端。
 */
@Service
public class SettingsService {

    private final ProxyProperties props;
    private final ProxyController proxy;
    private final ObjectMapper mapper;
    private final Environment env;

    public SettingsService(ProxyProperties props, ProxyController proxy, ObjectMapper mapper, Environment env) {
        this.props = props;
        this.proxy = proxy;
        this.mapper = mapper;
        this.env = env;
    }

    /** GET /api/settings：返回当前可编辑 settings + 来源 + 文件路径。 */
    public Map<String, Object> read() {
        SettingsManager.Result r = SettingsManager.readSettings();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("settings", r.settings);
        out.put("source", r.source);
        out.put("file", r.file.toString());
        return out;
    }

    /** POST /api/settings：落盘 + 写回 props + 热生效；返回端口是否变化（变化需重启）。 */
    public Map<String, Object> apply(Map<String, Object> incoming) throws IOException {
        Map<String, Object> saved = SettingsManager.writeSettings(incoming);
        int currentPort = currentServerPort();
        int newPort = asInt(saved.get("proxyPort"), currentPort);
        boolean portChanged = newPort != currentPort;

        applyToProps(saved);
        proxy.reloadFromProps();

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("file", SettingsManager.configFilePath().toString());
        // 单端口：代理口即配置口。端口变更需重启进程，用 uiPortChanged 触发前端「请重启」提示。
        out.put("proxyPortChanged", false);
        out.put("uiPortChanged", portChanged);
        return out;
    }

    /** 启动后：若 config.json 存在或为打包态，则以文件为准写回 props；并在打包态自动开浏览器。 */
    @EventListener(ApplicationReadyEvent.class)
    public void onReady() {
        SettingsManager.Result r = SettingsManager.readSettings();
        if ("file".equals(r.source) || SettingsManager.isPackaged()) {
            applyToProps(r.settings);
            proxy.reloadFromProps();
        }
        int port = currentServerPort();
        String ui = "http://127.0.0.1:" + port;
        System.out.println("cli-proxy-logger (java) running.");
        System.out.println("  config UI / proxy -> " + ui);
        System.out.println("  settings          -> " + r.file + " (" + r.source + ")");

        boolean wantOpen = "1".equals(System.getenv("CLI_PROXY_OPEN"))
                || (SettingsManager.isPackaged() && !"0".equals(System.getenv("CLI_PROXY_OPEN")));
        if (wantOpen) {
            openBrowser(ui);
        }
    }

    private int currentServerPort() {
        return asInt(env.getProperty("server.port"), 8788);
    }

    /** 把 GUI settings 写回 ProxyProperties。空集合写成 "[]"/"{}" 以「明确为空」屏蔽 env 回退。 */
    @SuppressWarnings("unchecked")
    private void applyToProps(Map<String, Object> s) {
        Map<String, Object> upstream = asMap(s.get("upstream"));
        props.setAnthropicUpstream(str(upstream.get("anthropic"), "https://api.anthropic.com"));
        props.setOpenaiUpstream(str(upstream.get("openai"), "https://api.openai.com"));

        String logDir = str(s.get("logDir"), "");
        props.setLogDir(logDir.isEmpty()
                ? SettingsManager.baseDir().resolve("logs").toString() : logDir);

        Map<String, Object> compat = asMap(s.get("compat"));
        props.setAnthropicCompat(truthy(compat.get("enabled")) ? "chat" : "off");
        props.setModelMap(toJson(asMap(compat.get("modelMap"))));
        props.setModelMapFile(null);

        Map<String, Object> tn = asMap(s.get("toolName"));
        props.setToolNameCase(truthy(tn.get("enabled")));
        props.setToolNameRequest(notFalse(tn.get("request")));
        props.setToolNameResponse(notFalse(tn.get("response")));
        props.setToolNameRepairInput(notFalse(tn.get("repairInput")));
        props.setToolNameMap(toJson(asMap(tn.get("map"))));
        props.setToolNameMapFile(null);

        props.setFilters(toJson(s.get("filters") instanceof List ? s.get("filters") : java.util.Collections.emptyList()));
        props.setFiltersFile(null);

        Map<String, Object> outbound = asMap(s.get("outbound"));
        props.setUpstreamProxy(str(outbound.get("url"), ""));

        props.setProviders(toJson(flattenProviders(asMap(s.get("providers")))));
        props.setProvidersFile(null);

        Map<String, Object> breaker = asMap(s.get("breaker"));
        props.setBreaker(truthy(breaker.get("enabled")));
        props.setBreakerFailures(asInt(breaker.get("failureThreshold"), 5));
        props.setBreakerCooldownMs((long) asInt(breaker.get("cooldownMs"), 30000));
        props.setBreakerHalfOpenMax(asInt(breaker.get("halfOpenMax"), 1));
        props.setFailoverStatuses(joinStatuses(breaker.get("failoverStatuses")));

        Map<String, Object> rect = asMap(s.get("rectifier"));
        props.setRectify(truthy(rect.get("enabled")));
        props.setRectifySignature(notFalse(rect.get("signature")));
        props.setRectifyBudget(notFalse(rect.get("budget")));
    }

    /** 把 {anthropic:[...],openai:[...]} 摊平成 Providers.parse 接受的标记数组。 */
    @SuppressWarnings("unchecked")
    private List<Object> flattenProviders(Map<String, Object> providers) {
        java.util.List<Object> out = new java.util.ArrayList<>();
        for (String group : new String[]{"anthropic", "openai"}) {
            Object list = providers.get(group);
            if (!(list instanceof List)) {
                continue;
            }
            for (Object item : (List<Object>) list) {
                if (!(item instanceof Map)) {
                    continue;
                }
                Map<String, Object> p = (Map<String, Object>) item;
                Object baseUrl = p.get("baseUrl");
                if (baseUrl == null || baseUrl.toString().isEmpty()) {
                    continue;
                }
                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put("id", p.get("id"));
                entry.put("group", group);
                entry.put("baseUrl", baseUrl);
                entry.put("apiKey", p.get("apiKey"));
                out.add(entry);
            }
        }
        return out;
    }

    private void openBrowser(String url) {
        try {
            if (System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win")) {
                new ProcessBuilder("cmd", "/c", "start", "", url).start();
                return;
            }
            if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                Desktop.getDesktop().browse(URI.create(url));
            } else {
                String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
                String cmd = os.contains("mac") ? "open" : "xdg-open";
                new ProcessBuilder(cmd, url).start();
            }
        } catch (Exception ignored) {
            // best-effort
        }
    }

    // ---- small helpers --------------------------------------------------

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object o) {
        return o instanceof Map ? (Map<String, Object>) o : new LinkedHashMap<>();
    }

    private static String str(Object o, String fallback) {
        return o == null ? fallback : o.toString();
    }

    private static boolean truthy(Object o) {
        if (o instanceof Boolean) {
            return (Boolean) o;
        }
        return o != null && ("true".equalsIgnoreCase(o.toString()) || "1".equals(o.toString()));
    }

    /** 默认开（仅当显式 false 才关），对应 ProxyProperties 子开关语义。 */
    private static boolean notFalse(Object o) {
        return !(Boolean.FALSE.equals(o) || "false".equalsIgnoreCase(String.valueOf(o)) || "0".equals(String.valueOf(o)));
    }

    private static int asInt(Object o, int fallback) {
        if (o instanceof Number) {
            return ((Number) o).intValue();
        }
        try {
            return Integer.parseInt(String.valueOf(o).trim());
        } catch (Exception e) {
            return fallback;
        }
    }

    private String toJson(Object o) {
        try {
            return mapper.writeValueAsString(o);
        } catch (Exception e) {
            return "{}";
        }
    }

    @SuppressWarnings("unchecked")
    private static String joinStatuses(Object o) {
        if (!(o instanceof List)) {
            return "429,500,502,503,504";
        }
        StringBuilder sb = new StringBuilder();
        for (Object n : (List<Object>) o) {
            if (sb.length() > 0) {
                sb.append(',');
            }
            sb.append(asInt(n, 0));
        }
        return sb.length() == 0 ? "429,500,502,503,504" : sb.toString();
    }
}
