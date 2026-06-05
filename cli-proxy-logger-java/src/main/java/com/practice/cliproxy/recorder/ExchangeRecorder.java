package com.practice.cliproxy.recorder;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.practice.cliproxy.config.ProxyProperties;
import com.practice.cliproxy.model.Exchange;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;

/**
 * 落盘（按天 JSONL）+ 内存保留最近若干条供 UI/API 使用。线程安全。
 */
@Component
public class ExchangeRecorder {

    private static final int MEM_LIMIT = 500;

    private final ProxyProperties props;
    private final ObjectMapper mapper;
    private final LinkedList<Exchange> recent = new LinkedList<>();

    public ExchangeRecorder(ProxyProperties props, ObjectMapper mapper) {
        this.props = props;
        this.mapper = mapper;
    }

    public synchronized void record(Exchange exchange) {
        recent.addFirst(exchange);
        while (recent.size() > MEM_LIMIT) {
            recent.removeLast();
        }
        try {
            Path dir = Paths.get(props.getLogDir());
            Files.createDirectories(dir);
            Path file = dir.resolve(LocalDate.now() + ".jsonl");
            String line = mapper.writeValueAsString(exchange) + "\n";
            Files.write(file, line.getBytes(StandardCharsets.UTF_8),
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            System.err.println("[recorder] failed to write log: " + e.getMessage());
        }
    }

    public synchronized List<Exchange> list(int limit) {
        List<Exchange> out = new ArrayList<>();
        for (Exchange e : recent) {
            if (out.size() >= limit) {
                break;
            }
            out.add(e);
        }
        return out;
    }

    public synchronized Exchange get(String id) {
        for (Exchange e : recent) {
            if (e.id.equals(id)) {
                return e;
            }
        }
        return null;
    }
}
