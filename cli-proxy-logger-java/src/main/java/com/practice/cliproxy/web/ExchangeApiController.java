package com.practice.cliproxy.web;

import com.practice.cliproxy.model.Exchange;
import com.practice.cliproxy.recorder.ExchangeRecorder;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

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

    public ExchangeApiController(ExchangeRecorder recorder) {
        this.recorder = recorder;
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
