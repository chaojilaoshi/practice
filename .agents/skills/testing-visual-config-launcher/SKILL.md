---
name: testing-visual-config-launcher
description: End-to-end test the cli-proxy-logger single-file launchers + visual config editor (Node/Python/Java). Use when verifying the .exe launcher, /api/settings form, config.json persistence, or live-apply behavior.
---

# Testing the visual-config packaged launchers

Three sibling implementations expose an identical web UI and config schema:
- `cli-proxy-logger/` (Node, SEA single .exe)
- `cli-proxy-logger-py/` (Python, PyInstaller `--onefile` .exe)
- `cli-proxy-logger-java/` (Java, jpackage app-image folder w/ exe launcher + embedded JRE)

Each: double-click exe -> serves config UI -> `设置 / 配置` button opens an editable form (fetches `/api/settings`) -> `保存并生效` POSTs `/api/settings`, writes `config.json` next to the exe, and **live-applies** without restart (except port changes). Default (all off) = transparent passthrough.

## Ports
- Node / Python: proxy `8788` + config UI `8789` (open the UI on **8789**).
- Java: proxy + UI share `8788` (open on **8788**).
- Only one impl can bind these ports at a time — stop others first (`Get-Process cli-proxy-logger,node | Stop-Process -Force`).

## Build (if exe not already in dist/)
- Node: `cd cli-proxy-logger && npm install && npm run build:exe` -> `dist/cli-proxy-logger.exe`
- Python: `cd cli-proxy-logger-py && python scripts/build_exe.py` (needs `pip install pyinstaller`) -> `dist/cli-proxy-logger.exe`
- Java: `cd cli-proxy-logger-java && mvn clean package` then `scripts/build_exe.ps1` (jpackage, JDK 17) -> `dist/app-image/cli-proxy-logger/cli-proxy-logger.exe`

## Run for testing
Set `CLI_PROXY_OPEN=0` to suppress the auto-open browser when launching from a shell. Pre-seed `config.json` next to the exe to control the start state (upstream URL, filters, toggles) — `source` then shows `file` in the UI. Allow ~10s for the Java launcher (JVM) to boot.

## Mock upstream (proves outbound mutation)
Use a mock that echoes the request it received and returns a lowercase `tool_use.name` + serialized-array `input`. `mock-anthropic-upstream.mjs` in the home dir does this (`PORT=9301 node mock-anthropic-upstream.mjs`). Point the launcher's Anthropic upstream at `http://127.0.0.1:9301`.

## Decisive live-apply test (works vs broken look different)
1. Seed config with tool-name normalization OFF, upstream -> mock.
2. Send a lowercase-tool request through the proxy; confirm mock sees `["todowrite","webfetch"]` and response stays lowercase.
3. In the GUI, tick `启用工具名规范化` and click `保存并生效` (expect `已保存并生效`; `config.json` now `enabled=true`).
4. Re-send the SAME request (no restart): mock must now see `["TodoWrite","WebFetch"]` and response `TodoWrite` with `input` repaired to a real array. If it stays lowercase, live-apply is broken.

## GUI quirks in this Windows env (important)
- **Address bar / text inputs drop shift-chars** (colons, underscores, uppercase). To navigate, copy the URL via `printf 'http://127.0.0.1:8788/' | clip.exe` then Ctrl+A, Ctrl+V in the address bar. For decisive assertions prefer **checkbox toggles** (no typing) over typing into fields.
- **The `保存并生效` button can sit behind the Windows taskbar** at the bottom and be unclickable. Press **F11 (fullscreen)** to auto-hide the taskbar and reveal it, then click. Verify the save actually persisted by reading `config.json` on disk — a missed click silently leaves `enabled=false`.
- Recording tools (start_recording/annotate_recording) have been unavailable in this env; fall back to stepwise screenshots. They might work in future — try first.

## Devin Secrets Needed
None. All testing uses local mock upstreams; no external API keys required.
