// Self-contained test: mock upstream + proxy, no API key needed.
// Verifies fidelity (client receives upstream bytes unchanged) AND that the
// proxy correctly parses tool calls for all three wire formats, streaming and
// non-streaming.

import http from 'node:http';
import assert from 'node:assert';
import { fileURLToPath } from 'node:url';
import { loadConfig } from '../src/config.js';
import { Recorder } from '../src/recorder.js';
import { startProxy } from '../src/proxy.js';

const ANTHROPIC_STREAM = [
  'event: message_start',
  'data: {"type":"message_start","message":{"usage":{"input_tokens":10}}}',
  '',
  'event: content_block_start',
  'data: {"type":"content_block_start","index":0,"content_block":{"type":"text"}}',
  '',
  'event: content_block_delta',
  'data: {"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"Let me check the weather."}}',
  '',
  'event: content_block_stop',
  'data: {"type":"content_block_stop","index":0}',
  '',
  'event: content_block_start',
  'data: {"type":"content_block_start","index":1,"content_block":{"type":"tool_use","id":"toolu_1","name":"get_weather"}}',
  '',
  'event: content_block_delta',
  'data: {"type":"content_block_delta","index":1,"delta":{"type":"input_json_delta","partial_json":"{\\"city\\": \\"San Fra"}}',
  '',
  'event: content_block_delta',
  'data: {"type":"content_block_delta","index":1,"delta":{"type":"input_json_delta","partial_json":"ncisco\\"}"}}',
  '',
  'event: content_block_stop',
  'data: {"type":"content_block_stop","index":1}',
  '',
  'event: message_delta',
  'data: {"type":"message_delta","delta":{"stop_reason":"tool_use"},"usage":{"output_tokens":20}}',
  '',
  'event: message_stop',
  'data: {"type":"message_stop"}',
  '',
  '',
].join('\n');

const RESPONSES_STREAM = [
  'event: response.created',
  'data: {"type":"response.created","response":{"id":"resp_1"}}',
  '',
  'event: response.output_item.added',
  'data: {"type":"response.output_item.added","item_id":"item_1","item":{"type":"function_call","id":"item_1","call_id":"call_1","name":"run_shell"}}',
  '',
  'event: response.function_call_arguments.delta',
  'data: {"type":"response.function_call_arguments.delta","item_id":"item_1","delta":"{\\"cmd\\": \\"ls"}',
  '',
  'event: response.function_call_arguments.delta',
  'data: {"type":"response.function_call_arguments.delta","item_id":"item_1","delta":" -la\\"}"}',
  '',
  'event: response.function_call_arguments.done',
  'data: {"type":"response.function_call_arguments.done","item_id":"item_1","arguments":"{\\"cmd\\": \\"ls -la\\"}"}',
  '',
  'event: response.output_item.done',
  'data: {"type":"response.output_item.done","item_id":"item_1","item":{"type":"function_call","id":"item_1","call_id":"call_1","name":"run_shell","arguments":"{\\"cmd\\": \\"ls -la\\"}"}}',
  '',
  'event: response.completed',
  'data: {"type":"response.completed","response":{"status":"completed","usage":{"input_tokens":5,"output_tokens":8}}}',
  '',
  '',
].join('\n');

const CHAT_STREAM = [
  'data: {"choices":[{"index":0,"delta":{"role":"assistant","content":"Sure."}}]}',
  '',
  'data: {"choices":[{"index":0,"delta":{"tool_calls":[{"index":0,"id":"call_9","function":{"name":"search","arguments":"{\\"q\\":"}}]}}]}',
  '',
  'data: {"choices":[{"index":0,"delta":{"tool_calls":[{"index":0,"function":{"arguments":"\\"cats\\"}"}}]}}]}',
  '',
  'data: {"choices":[{"index":0,"delta":{},"finish_reason":"tool_calls"}],"usage":{"total_tokens":42}}',
  '',
  'data: [DONE]',
  '',
  '',
].join('\n');

const ANTHROPIC_JSON = JSON.stringify({
  id: 'msg_1',
  content: [
    { type: 'text', text: 'hello' },
    { type: 'tool_use', id: 'toolu_2', name: 'lookup', input: { key: 'v' } },
  ],
  stop_reason: 'tool_use',
  usage: { input_tokens: 3, output_tokens: 4 },
});

function mockUpstream() {
  return http.createServer((req, res) => {
    let body = '';
    req.on('data', (c) => (body += c));
    req.on('end', () => {
      const reqObj = body ? JSON.parse(body) : {};
      const stream = !!reqObj.stream;
      if (req.url.startsWith('/v1/messages')) {
        if (stream) {
          res.writeHead(200, { 'content-type': 'text/event-stream' });
          res.end(ANTHROPIC_STREAM);
        } else {
          res.writeHead(200, { 'content-type': 'application/json' });
          res.end(ANTHROPIC_JSON);
        }
      } else if (req.url.startsWith('/v1/responses')) {
        res.writeHead(200, { 'content-type': 'text/event-stream' });
        res.end(RESPONSES_STREAM);
      } else if (req.url.startsWith('/v1/chat/completions')) {
        res.writeHead(200, { 'content-type': 'text/event-stream' });
        res.end(CHAT_STREAM);
      } else {
        res.writeHead(404);
        res.end('no');
      }
    });
  });
}

function post(port, path, bodyObj, headers = {}) {
  return new Promise((resolve, reject) => {
    const body = JSON.stringify(bodyObj);
    const req = http.request(
      { host: '127.0.0.1', port, path, method: 'POST', headers: { 'content-type': 'application/json', 'content-length': Buffer.byteLength(body), ...headers } },
      (res) => {
        let data = '';
        res.on('data', (c) => (data += c));
        res.on('end', () => resolve({ status: res.statusCode, body: data }));
      },
    );
    req.on('error', reject);
    req.write(body);
    req.end();
  });
}

const wait = (ms) => new Promise((r) => setTimeout(r, ms));

async function main() {
  const mock = mockUpstream();
  await new Promise((r) => mock.listen(0, '127.0.0.1', r));
  const mockPort = mock.address().port;
  const mockUrl = `http://127.0.0.1:${mockPort}`;

  const config = loadConfig({
    proxyPort: 0,
    uiPort: 0,
    logDir: fileURLToPath(new URL('./.tmp-logs', import.meta.url)),
    upstream: { anthropic: mockUrl, openai: mockUrl },
  });
  const recorder = new Recorder(config);
  const proxy = startProxy(config, recorder);
  await new Promise((r) => proxy.on('listening', r));
  const proxyPort = proxy.address().port;

  let pass = 0;
  const check = (name, cond) => {
    assert.ok(cond, name);
    console.log('  ok -', name);
    pass++;
  };

  // 1. Anthropic streaming
  const r1 = await post(proxyPort, '/v1/messages', { model: 'claude-x', stream: true, tools: [{ name: 'get_weather' }], messages: [{ role: 'user', content: 'weather?' }] }, { 'x-api-key': 'sk-secret-1234567890', 'anthropic-version': '2023-06-01' });
  await wait(50);
  check('anthropic stream: client receives raw SSE', r1.body === ANTHROPIC_STREAM);
  const e1 = recorder.recent[0];
  check('anthropic stream: wire', e1.wire === 'anthropic');
  check('anthropic stream: text reconstructed', e1.response.text === 'Let me check the weather.');
  check('anthropic stream: one tool call', e1.response.toolCalls.length === 1);
  check('anthropic stream: tool name', e1.response.toolCalls[0].name === 'get_weather');
  check('anthropic stream: tool args parsed', e1.response.toolCalls[0].args.city === 'San Francisco');
  check('anthropic stream: stop_reason', e1.response.stopReason === 'tool_use');
  check('anthropic stream: request tools captured', e1.request.tools[0].name === 'get_weather');
  check('anthropic stream: api key redacted', /\.\.\./.test(e1.reqHeaders['x-api-key']));

  // 2. Anthropic non-streaming
  const r2 = await post(proxyPort, '/v1/messages', { model: 'claude-x', messages: [{ role: 'user', content: 'hi' }] }, { 'x-api-key': 'sk-2', 'anthropic-version': '2023-06-01' });
  await wait(50);
  check('anthropic json: client receives raw json', r2.body === ANTHROPIC_JSON);
  const e2 = recorder.recent[0];
  check('anthropic json: tool call parsed', e2.response.toolCalls[0].name === 'lookup' && e2.response.toolCalls[0].args.key === 'v');

  // 3. OpenAI Responses streaming
  const r3 = await post(proxyPort, '/v1/responses', { model: 'gpt-5', stream: true, input: [{ role: 'user', content: 'list files' }] }, { authorization: 'Bearer sk-openai-secret' });
  await wait(50);
  check('responses stream: client receives raw SSE', r3.body === RESPONSES_STREAM);
  const e3 = recorder.recent[0];
  check('responses stream: wire', e3.wire === 'responses');
  check('responses stream: one tool call', e3.response.toolCalls.length === 1);
  check('responses stream: tool name', e3.response.toolCalls[0].name === 'run_shell');
  check('responses stream: tool args parsed', e3.response.toolCalls[0].args.cmd === 'ls -la');
  check('responses stream: usage captured', e3.response.usage && e3.response.usage.output_tokens === 8);
  check('responses stream: auth redacted', /\.\.\./.test(e3.reqHeaders['authorization']));

  // 4. OpenAI Chat streaming
  const r4 = await post(proxyPort, '/v1/chat/completions', { model: 'gpt-4o', stream: true, messages: [{ role: 'user', content: 'find cats' }] }, { authorization: 'Bearer sk-x' });
  await wait(50);
  check('chat stream: client receives raw SSE', r4.body === CHAT_STREAM);
  const e4 = recorder.recent[0];
  check('chat stream: wire', e4.wire === 'chat');
  check('chat stream: tool name', e4.response.toolCalls[0].name === 'search');
  check('chat stream: tool args parsed', e4.response.toolCalls[0].args.q === 'cats');
  check('chat stream: finish_reason', e4.response.stopReason === 'tool_calls');

  console.log(`\nAll ${pass} checks passed.`);
  proxy.close();
  mock.close();
}

main().catch((err) => {
  console.error('TEST FAILED:', err);
  process.exit(1);
});
