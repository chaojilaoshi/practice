package com.practice.cliproxy.parser;

import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 按 wire 标识取对应的解析器。
 */
@Component
public class ParserFactory {

    private final Map<String, WireParser> byWire = new HashMap<>();

    public ParserFactory(List<WireParser> parsers) {
        for (WireParser p : parsers) {
            byWire.put(p.wire(), p);
        }
    }

    public WireParser get(String wire) {
        return byWire.get(wire);
    }
}
