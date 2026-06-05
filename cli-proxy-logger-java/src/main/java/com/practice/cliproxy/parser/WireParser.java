package com.practice.cliproxy.parser;

import com.practice.cliproxy.model.NormalizedRequest;
import com.practice.cliproxy.model.NormalizedResponse;

/**
 * 针对某种 wire 格式（anthropic / responses / chat）的请求与响应解析器。
 */
public interface WireParser {
    /** wire 标识：anthropic / responses / chat。 */
    String wire();

    NormalizedRequest parseRequest(String body);

    /** 解析完整（非流式）响应体。 */
    NormalizedResponse parseResponse(String body);

    /** 新建一个流式聚合器。 */
    StreamAggregator newAggregator();
}
