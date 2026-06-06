"""Entry point: load config, start proxy + UI, print connection instructions.

Run from the ``cli-proxy-logger-py`` directory with:

    python -m cli_proxy_logger

When a ``config.json`` exists next to the program it is the single source of
truth (the GUI workflow). A packaged build (PyInstaller) also always uses the
settings path (seeded from env on first run), so config.json and logs anchor
beside the executable. An unpackaged run with no config.json falls back to the
full env-var behavior (load_config), keeping existing setups working unchanged.
"""

import os
import subprocess
import sys
import threading
import time

from .config import load_config
from .proxy import start_proxy
from .recorder import Recorder
from .settings import (
    read_settings,
    build_config_from_settings,
    write_settings,
    is_packaged,
)
from .ui_server import start_ui


def _open_browser(url):
    """Open the default browser to the config UI (best-effort, cross-platform)."""
    try:
        if sys.platform == "win32":
            subprocess.Popen(["cmd", "/c", "start", "", url], shell=False,
                             stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        elif sys.platform == "darwin":
            subprocess.Popen(["open", url], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        else:
            subprocess.Popen(["xdg-open", url], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    except OSError:
        pass


def main():
    packaged = is_packaged()
    settings, source, file = read_settings()
    config = build_config_from_settings(settings) if (source == "file" or packaged) else load_config()

    recorder = Recorder(config)
    proxy_holder = [start_proxy(config, recorder)]

    def apply_settings(new_settings):
        # Persist config.json, rebuild the runtime config, then mutate the shared
        # `config` dict in place so the proxy/recorder (which read config per
        # request) pick up changes without a restart. Proxy-port changes re-bind
        # the listener; UI-port changes need an app restart (the page you're on is
        # served by that listener) and are reported back to the caller.
        saved, _f = write_settings(new_settings)
        built = build_config_from_settings(saved)
        proxy_port_changed = built["proxyPort"] != config["proxyPort"]
        ui_port_changed = built["uiPort"] != config["uiPort"]

        for k in list(built.keys()):
            config[k] = built[k]
        try:
            os.makedirs(config["logDir"], exist_ok=True)
        except OSError:
            pass

        # Breakers share their opts object by reference, so mutating it in place
        # updates every existing breaker without dropping accumulated state.
        breakers = getattr(proxy_holder[0], "breakers", None)
        if breakers is not None:
            breakers.opts["failureThreshold"] = config["breaker"]["failureThreshold"]
            breakers.opts["cooldownMs"] = config["breaker"]["cooldownMs"]
            breakers.opts["halfOpenMax"] = config["breaker"]["halfOpenMax"]

        if proxy_port_changed:
            try:
                proxy_holder[0].shutdown()
                proxy_holder[0].server_close()
            except OSError:
                pass
            proxy_holder[0] = start_proxy(config, recorder)
        return {"proxyPortChanged": proxy_port_changed, "uiPortChanged": ui_port_changed}

    # Only enable the visual editor when this is the file/packaged (GUI) workflow.
    editable = source == "file" or packaged
    start_ui(config, recorder,
             apply_settings=apply_settings if editable else None,
             config_file=file)

    proxy = f"http://127.0.0.1:{config['proxyPort']}"
    ui = f"http://127.0.0.1:{config['uiPort']}"
    print(f"""
cli-proxy-logger (python) running.
  config UI -> {ui}
  proxy     -> {proxy}
  settings  -> {file} ({source})
  logs      -> {config['logDir']}

Point Claude Code at the proxy:
  export ANTHROPIC_BASE_URL={proxy}

Point Codex at the proxy (~/.codex/config.toml):
  openai_base_url = "{proxy}/v1"
""")

    # Auto-open the browser to the config UI in the double-click (packaged) flow,
    # or when CLI_PROXY_OPEN=1. Never in dev/test/headless runs (CLI_PROXY_OPEN=0).
    want_open = os.environ.get("CLI_PROXY_OPEN") == "1" or (packaged and os.environ.get("CLI_PROXY_OPEN") != "0")
    if want_open:
        threading.Timer(0.6, lambda: _open_browser(ui)).start()

    try:
        while True:
            time.sleep(3600)
    except KeyboardInterrupt:
        print("\nshutting down.")


if __name__ == "__main__":
    main()
