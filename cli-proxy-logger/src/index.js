#!/usr/bin/env node
// Entry point: load config, start proxy + UI, print connection instructions.

import { loadConfig } from './config.js';
import { Recorder } from './recorder.js';
import { startProxy } from './proxy.js';
import { startUi } from './ui-server.js';

const config = loadConfig();
const recorder = new Recorder(config);
startProxy(config, recorder);
startUi(config, recorder);

const proxy = `http://127.0.0.1:${config.proxyPort}`;
console.log(`
cli-proxy-logger running.
  logs -> ${config.logDir}

Point Claude Code at the proxy:
  export ANTHROPIC_BASE_URL=${proxy}
  # (non-official host disables MCP tool search by default)
  # export ENABLE_TOOL_SEARCH=true

Point Codex at the proxy (~/.codex/config.toml):
  openai_base_url = "${proxy}/v1"
  # or a custom provider:
  # [model_providers.proxy]
  # name = "local proxy"
  # base_url = "${proxy}/v1"
  # wire_api = "responses"
`);
