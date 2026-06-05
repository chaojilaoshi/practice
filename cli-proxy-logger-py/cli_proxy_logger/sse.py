"""Incremental Server-Sent Events parser.

Feed it raw decoded text chunks via ``push()``; it invokes the registered
listener once per complete SSE event with a dict ``{event, data, raw}``. Data
lines are joined with "\\n" per the SSE spec. The proxy uses this on a COPY of
the upstream stream -- the original bytes are always forwarded to the client
untouched, so a parse error here can never corrupt the CLI's stream.
"""


class SSEParser:
    def __init__(self):
        self._buf = ""
        self._listeners = []

    def on(self, _evt, cb):
        self._listeners.append(cb)
        return self

    def _emit(self, evt):
        for cb in self._listeners:
            try:
                cb(evt)
            except Exception:
                # ignore listener errors; parsing must never break forwarding
                pass

    def push(self, text):
        # Append the new text and try to peel off complete events. Because the
        # network delivers arbitrary byte boundaries, an event may be split
        # across two push() calls -- so we always retain the trailing, possibly
        # incomplete, fragment in self._buf for next time. This is the essence
        # of "incremental" parsing: never assume a chunk ends on an event
        # boundary.
        self._buf += text
        # SSE events are separated by a blank line. Handle \n\n and \r\n\r\n.
        normalized = self._buf.replace("\r\n", "\n")
        parts = normalized.split("\n\n")
        # The last element is whatever came after the final blank line; it may
        # be a partial event, so put it back in the buffer.
        self._buf = parts.pop() if parts else ""
        for block in parts:
            if block.strip() == "":
                continue
            self._emit(self._parse_block(block))

    def flush(self):
        if self._buf.strip() != "":
            self._emit(self._parse_block(self._buf.replace("\r\n", "\n")))
        self._buf = ""

    @staticmethod
    def _parse_block(block):
        event = None
        data_lines = []
        for line in block.split("\n"):
            if line.startswith(":"):
                continue  # comment
            idx = line.find(":")
            field = line if idx == -1 else line[:idx]
            value = "" if idx == -1 else line[idx + 1:]
            if value.startswith(" "):
                value = value[1:]
            if field == "event":
                event = value
            elif field == "data":
                data_lines.append(value)
        return {"event": event, "data": "\n".join(data_lines), "raw": block}
