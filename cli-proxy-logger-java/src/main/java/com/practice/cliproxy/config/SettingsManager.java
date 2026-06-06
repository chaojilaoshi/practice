package com.practice.cliproxy.config;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * GUI 可视化配置的持久化层：一个 config.json 作为「双击启动」工作流的唯一配置来源，
 * 镜像 Node {@code src/settings.js} / Python {@code settings.py}。
 *
 * <p>两种数据形状：
 * <ul>
 *   <li>"settings"：GUI 表单形状、可编辑、存进 config.json（与 Node/Python 同 schema）。</li>
 *   <li>运行期配置：由 {@link SettingsService} 把 settings 写回 {@link ProxyProperties}。</li>
 * </ul>
 *
 * <p>没有 config.json 时从环境变量「种子」出初始 settings，使既有的 env 工作流不变。
 */
public final class SettingsManager {

    private static final ObjectMapper M = new ObjectMapper();

    private SettingsManager() {
    }

    /** 读取结果：settings（GUI 形状）、来源（"file"/"env"）、文件路径。 */
    public static final class Result {
        public final Map<String, Object> settings;
        public final String source;
        public final Path file;

        public Result(Map<String, Object> settings, String source, Path file) {
            this.settings = settings;
            this.source = source;
            this.file = file;
        }
    }

    /** 是否为 jpackage 打包后的 app-image（双击 .exe 启动）。 */
    public static boolean isPackaged() {
        return System.getProperty("jpackage.app-path") != null;
    }

    /** 可执行文件所在目录（打包时 .exe 旁；否则 jar 所在目录；开发态为当前工作目录）。 */
    public static Path baseDir() {
        String appPath = System.getProperty("jpackage.app-path");
        if (appPath != null && !appPath.isEmpty()) {
            Path p = Paths.get(appPath).toAbsolutePath().getParent();
            if (p != null) {
                return p;
            }
        }
        try {
            Path loc = Paths.get(SettingsManager.class.getProtectionDomain()
                    .getCodeSource().getLocation().toURI());
            if (Files.isRegularFile(loc) && loc.toString().endsWith(".jar")) {
                Path parent = loc.toAbsolutePath().getParent();
                if (parent != null) {
                    return parent;
                }
            }
        } catch (Exception ignored) {
            // fall through to working directory
        }
        return Paths.get("").toAbsolutePath();
    }

    /** config.json 位置：CONFIG_FILE 环境变量 &gt; 可执行文件旁 &gt; 当前目录。 */
    public static Path configFilePath() {
        String override = System.getenv("CONFIG_FILE");
        if (override != null && !override.isEmpty()) {
            return Paths.get(override).toAbsolutePath();
        }
        return baseDir().resolve("config.json");
    }

    /** 默认可编辑 settings（全部关闭 = 透明直通）。 */
    public static Map<String, Object> defaultSettings() {
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("proxyPort", 8788);
        s.put("uiPort", 8789);
        s.put("logDir", "");
        s.put("upstream", map("anthropic", "https://api.anthropic.com", "openai", "https://api.openai.com"));
        s.put("compat", map("enabled", false, "modelMap", new LinkedHashMap<>()));
        s.put("toolName", map("enabled", false, "request", true, "response", true,
                "repairInput", true, "map", new LinkedHashMap<>()));
        s.put("filters", new ArrayList<>());
        s.put("outbound", map("url", ""));
        s.put("providers", map("anthropic", new ArrayList<>(), "openai", new ArrayList<>()));
        Map<String, Object> breaker = new LinkedHashMap<>();
        breaker.put("enabled", false);
        breaker.put("failureThreshold", 5);
        breaker.put("cooldownMs", 30000);
        breaker.put("halfOpenMax", 1);
        breaker.put("failoverStatuses", new ArrayList<>(Arrays.asList(429, 500, 502, 503, 504)));
        s.put("breaker", breaker);
        s.put("rectifier", map("enabled", false, "signature", true, "budget", true));
        return s;
    }

    private static Map<String, Object> map(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) {
            m.put((String) kv[i], kv[i + 1]);
        }
        return m;
    }

    private static boolean bool(String v) {
        if (v == null) {
            return false;
        }
        String s = v.toLowerCase(Locale.ROOT);
        return s.equals("1") || s.equals("on") || s.equals("true") || s.equals("yes");
    }

    private static int intOr(String v, int fallback) {
        try {
            return Integer.parseInt(v.trim());
        } catch (Exception e) {
            return fallback;
        }
    }

    /** 从环境变量种子出 settings（仅当没有 config.json 时使用）。 */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> settingsFromEnv() {
        Map<String, Object> s = defaultSettings();
        String v;
        if ((v = System.getenv("PROXY_PORT")) != null) {
            s.put("proxyPort", intOr(v, 8788));
        }
        if ((v = System.getenv("UI_PORT")) != null) {
            s.put("uiPort", intOr(v, 8789));
        }
        if ((v = System.getenv("LOG_DIR")) != null) {
            s.put("logDir", v);
        }
        Map<String, Object> upstream = (Map<String, Object>) s.get("upstream");
        if ((v = System.getenv("ANTHROPIC_UPSTREAM")) != null) {
            upstream.put("anthropic", v);
        }
        if ((v = System.getenv("OPENAI_UPSTREAM")) != null) {
            upstream.put("openai", v);
        }
        Map<String, Object> compat = (Map<String, Object>) s.get("compat");
        compat.put("enabled", "chat".equalsIgnoreCase(System.getenv("ANTHROPIC_COMPAT")));
        Map<String, Object> modelMap = parseJsonObject(System.getenv("MODEL_MAP"));
        if (modelMap != null) {
            compat.put("modelMap", modelMap);
        }
        Map<String, Object> toolName = (Map<String, Object>) s.get("toolName");
        toolName.put("enabled", bool(System.getenv("TOOL_NAME_CASE")));
        toolName.put("request", !"0".equals(System.getenv("TOOL_NAME_REQUEST")));
        toolName.put("response", !"0".equals(System.getenv("TOOL_NAME_RESPONSE")));
        toolName.put("repairInput", !"0".equals(System.getenv("TOOL_NAME_REPAIR_INPUT")));
        Map<String, Object> tnMap = parseJsonObject(System.getenv("TOOL_NAME_MAP"));
        if (tnMap != null) {
            toolName.put("map", tnMap);
        }
        List<Object> filters = parseJsonArray(System.getenv("FILTERS"));
        if (filters != null) {
            s.put("filters", filters);
        }
        Map<String, Object> outbound = (Map<String, Object>) s.get("outbound");
        String proxy = firstNonEmpty(System.getenv("UPSTREAM_PROXY"),
                System.getenv("HTTPS_PROXY"), System.getenv("HTTP_PROXY"));
        outbound.put("url", proxy == null ? "" : proxy);
        Map<String, Object> rectifier = (Map<String, Object>) s.get("rectifier");
        rectifier.put("enabled", bool(System.getenv("RECTIFY")) || bool(System.getenv("RECTIFIER")));
        return s;
    }

    private static String firstNonEmpty(String... vals) {
        for (String v : vals) {
            if (v != null && !v.isEmpty()) {
                return v;
            }
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> parseJsonObject(String raw) {
        if (raw == null || !raw.trim().startsWith("{")) {
            return null;
        }
        try {
            return M.readValue(raw, Map.class);
        } catch (Exception e) {
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    private static List<Object> parseJsonArray(String raw) {
        if (raw == null || !raw.trim().startsWith("[")) {
            return null;
        }
        try {
            return M.readValue(raw, List.class);
        } catch (Exception e) {
            return null;
        }
    }

    /** 深合并 override 到 base（用默认值兜底新增字段）。 */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> merge(Map<String, Object> base, Map<String, Object> override) {
        Map<String, Object> out = new LinkedHashMap<>(base);
        if (override == null) {
            return out;
        }
        for (Map.Entry<String, Object> e : override.entrySet()) {
            Object bv = base.get(e.getKey());
            Object ov = e.getValue();
            if (bv instanceof Map && ov instanceof Map) {
                out.put(e.getKey(), merge((Map<String, Object>) bv, (Map<String, Object>) ov));
            } else if (ov != null) {
                out.put(e.getKey(), ov);
            }
        }
        return out;
    }

    /** 读取可编辑 settings：有 config.json 用文件，否则用环境变量种子。 */
    @SuppressWarnings("unchecked")
    public static Result readSettings() {
        Path file = configFilePath();
        try {
            if (Files.exists(file)) {
                Map<String, Object> parsed = M.readValue(
                        new String(Files.readAllBytes(file), StandardCharsets.UTF_8), Map.class);
                return new Result(merge(defaultSettings(), parsed), "file", file);
            }
        } catch (Exception e) {
            System.err.println("[settings] failed to read config.json: " + e.getMessage());
        }
        return new Result(settingsFromEnv(), "env", file);
    }

    /** 持久化 settings 到 config.json（美化打印，便于人工查看）。 */
    public static Map<String, Object> writeSettings(Map<String, Object> settings) throws IOException {
        Path file = configFilePath();
        Map<String, Object> clean = merge(defaultSettings(), settings);
        Files.write(file, (M.writerWithDefaultPrettyPrinter().writeValueAsString(clean) + "\n")
                .getBytes(StandardCharsets.UTF_8));
        return clean;
    }
}
