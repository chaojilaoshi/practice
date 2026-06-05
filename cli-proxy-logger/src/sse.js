// Incremental Server-Sent Events parser.
//
// Feed it raw decoded text chunks via push(); it emits one object per complete
// SSE event: { event: string|null, data: string, raw: string }.
// Data lines are joined with "\n" per the SSE spec. The proxy uses this on a
// COPY of the upstream stream — the original bytes are always forwarded to the
// client untouched, so a parse error here can never corrupt the CLI's stream.

export class SSEParser {
  constructor() {
    this._buf = '';
    this._listeners = [];
  }

  on(_evt, cb) {
    this._listeners.push(cb);
    return this;
  }

  _emit(evt) {
    for (const cb of this._listeners) {
      try {
        cb(evt);
      } catch {
        // ignore listener errors; parsing must never break forwarding
      }
    }
  }

  push(text) {
    // Append the new text and peel off complete events. Because the network
    // delivers arbitrary byte boundaries, an event may be split across two
    // push() calls — so we always retain the trailing, possibly incomplete,
    // fragment in this._buf for next time. That is the essence of "incremental"
    // parsing: never assume a chunk ends on an event boundary.
    this._buf += text;
    // SSE events are separated by a blank line. Handle \n\n and \r\n\r\n.
    const normalized = this._buf.replace(/\r\n/g, '\n');
    const parts = normalized.split('\n\n');
    // The last element is whatever came after the final blank line; it may be
    // a partial event, so put it back in the buffer.
    this._buf = parts.pop() ?? '';
    for (const block of parts) {
      if (block.trim() === '') continue;
      this._emit(this._parseBlock(block));
    }
  }

  flush() {
    if (this._buf.trim() !== '') {
      this._emit(this._parseBlock(this._buf.replace(/\r\n/g, '\n')));
    }
    this._buf = '';
  }

  _parseBlock(block) {
    let event = null;
    const dataLines = [];
    for (const line of block.split('\n')) {
      if (line.startsWith(':')) continue; // comment
      const idx = line.indexOf(':');
      const field = idx === -1 ? line : line.slice(0, idx);
      let value = idx === -1 ? '' : line.slice(idx + 1);
      if (value.startsWith(' ')) value = value.slice(1);
      if (field === 'event') event = value;
      else if (field === 'data') dataLines.push(value);
    }
    return { event, data: dataLines.join('\n'), raw: block };
  }
}
