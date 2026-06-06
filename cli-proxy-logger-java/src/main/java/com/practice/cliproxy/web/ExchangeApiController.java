package com.practice.cliproxy.web;

import com.practice.cliproxy.config.SettingsService;
import com.practice.cliproxy.model.Exchange;
import com.practice.cliproxy.proxy.ProxyController;
import com.practice.cliproxy.recorder.ExchangeRecorder;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;

import java.util.Collections;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 供 Web UI 使用的查询接口。
 */
@RestController
@RequestMapping("/api")
public class ExchangeApiController {

    private final ExchangeRecorder recorder;
    private final ProxyController proxyController;
    private final SettingsService settingsService;

    public ExchangeApiController(ExchangeRecorder recorder, ProxyController proxyController,
                                 SettingsService settingsService) {
        this.recorder = recorder;
        this.proxyController = proxyController;
        this.settingsService = settingsService;
    }

    /** 当前生效的 opt-in 配置只读快照（无密钥）。供 UI 的「配置」面板展示。 */
    @GetMapping("/config")
    public Map<String, Object> config() {
        return proxyController.configSummary();
    }

    /** 可视化配置表单的可编辑 settings（GUI 形状，与 Node/Python 同 schema）。 */
    @GetMapping("/settings")
    public Map<String, Object> getSettings() {
        return settingsService.read();
    }

    /** 保存并热生效编辑后的 settings。端口变更需重启进程（返回 uiPortChanged=true 提示）。 */
    @PostMapping("/settings")
    public Map<String, Object> postSettings(@RequestBody Map<String, Object> settings) throws IOException {
        return settingsService.apply(settings);
    }

    /** 最近请求的摘要列表。 */
    @GetMapping("/exchanges")
    public List<Map<String, Object>> list(@RequestParam(defaultValue = "100") int limit) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Exchange e : recorder.list(limit)) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", e.id);
            m.put("ts", e.ts);
            m.put("durationMs", e.durationMs);
            m.put("wire", e.wire);
            m.put("method", e.method);
            m.put("url", e.url);
            m.put("model", e.request != null ? e.request.model : null);
            m.put("stream", e.request != null && e.request.stream);
            m.put("resStatus", e.resStatus);
            List<String> names = new ArrayList<>();
            if (e.response != null) {
                e.response.toolCalls.forEach(tc -> names.add(tc.name));
            }
            m.put("toolCallNames", names);
            m.put("error", e.error);
            out.add(m);
        }
        return out;
    }

    /** 单条完整详情。 */
    @GetMapping("/exchanges/{id}")
    public ResponseEntity<Exchange> get(@PathVariable String id) {
        Exchange e = recorder.get(id);
        return e != null ? ResponseEntity.ok(e) : ResponseEntity.notFound().build();
    }

    /** 清空内存列表（UI 的「清空」按钮）。磁盘上的 JSONL 日志保留。 */
    @DeleteMapping("/exchanges")
    public Map<String, Object> clear() {
        int cleared = recorder.clear();
        return Collections.singletonMap("cleared", cleared);
    }
}
