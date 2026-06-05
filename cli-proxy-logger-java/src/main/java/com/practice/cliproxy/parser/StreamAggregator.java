package com.practice.cliproxy.parser;

import com.practice.cliproxy.model.NormalizedResponse;
import com.practice.cliproxy.sse.SseParser.SseEvent;

/**
 * 流式响应聚合器：逐个吃进 SSE 事件，最终给出归一化响应。
 */
public interface StreamAggregator {
    void feed(SseEvent event);

    NormalizedResponse result();
}
