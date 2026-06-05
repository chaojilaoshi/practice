"""Entry point: load config, start proxy + UI, print connection instructions.

Run from the ``cli-proxy-logger-py`` directory with:

    python -m cli_proxy_logger
"""

import time

from .config import load_config
from .proxy import start_proxy
from .recorder import Recorder
from .ui_server import start_ui


def main():
    config = load_config()
    recorder = Recorder(config)
    start_proxy(config, recorder)
    start_ui(config, recorder)

    proxy = f"http://127.0.0.1:{config['proxyPort']}"
    print(f"""
cli-proxy-logger (python) running.
  logs -> {config['logDir']}

Point Claude Code at the proxy:
  export ANTHROPIC_BASE_URL={proxy}
  # (non-official host disables MCP tool search by default)
  # export ENABLE_TOOL_SEARCH=true

Point Codex at the proxy (~/.codex/config.toml):
  openai_base_url = "{proxy}/v1"
  # or a custom provider:
  # [model_providers.proxy]
  # name = "local proxy"
  # base_url = "{proxy}/v1"
  # wire_api = "responses"
""")

    try:
        while True:
            time.sleep(3600)
    except KeyboardInterrupt:
        print("\nshutting down.")


if __name__ == "__main__":
    main()
