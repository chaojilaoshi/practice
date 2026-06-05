package com.practice.cliproxy.sse;

import java.util.function.Consumer;

/**
 * 增量解析 Server-Sent Events。
 *
 * 喂入解码后的文本片段（push），每凑齐一个完整事件（以空行分隔）就回调一次
 * {@link SseEvent}。它只作用在上游响应的「副本」上——原始字节始终原样转发给
 * CLI，因此这里解析出错也绝不会影响 CLI 的正常使用。
 */
public class SseParser {

    /** 单个 SSE 事件：event 行 + 合并后的 data。 */
    public static class SseEvent {
        public final String event;
        public final String data;

        public SseEvent(String event, String data) {
            this.event = event;
            this.data = data;
        }
    }

    private final StringBuilder buf = new StringBuilder();
    private final Consumer<SseEvent> listener;

    public SseParser(Consumer<SseEvent> listener) {
        this.listener = listener;
    }

    public void push(String text) {
        // 追加新文本后逐个剥出完整事件。由于网络按任意字节边界切块，一个事件可能
        // 被拆在两次 push 之间——所以末尾「可能不完整」的片段始终留在 buf 里等下次。
        // 这正是「增量解析」的精髓：绝不假设某块数据恰好落在事件边界上。
        buf.append(text.replace("\r\n", "\n"));
        int idx;
        // 事件之间以空行（\n\n）分隔。
        while ((idx = buf.indexOf("\n\n")) >= 0) {
            String block = buf.substring(0, idx);
            buf.delete(0, idx + 2);
            if (!block.trim().isEmpty()) {
                emit(block);
            }
        }
    }

    public void flush() {
        if (buf.toString().trim().length() > 0) {
            emit(buf.toString());
        }
        buf.setLength(0);
    }

    private void emit(String block) {
        String event = null;
        StringBuilder data = new StringBuilder();
        for (String line : block.split("\n")) {
            if (line.startsWith(":")) {
                continue; // 注释行
            }
            int colon = line.indexOf(':');
            String field = colon == -1 ? line : line.substring(0, colon);
            String value = colon == -1 ? "" : line.substring(colon + 1);
            if (value.startsWith(" ")) {
                value = value.substring(1);
            }
            if ("event".equals(field)) {
                event = value;
            } else if ("data".equals(field)) {
                if (data.length() > 0) {
                    data.append('\n');
                }
                data.append(value);
            }
        }
        try {
            listener.accept(new SseEvent(event, data.toString()));
        } catch (RuntimeException ignored) {
            // 解析回调出错不影响转发
        }
    }
}
