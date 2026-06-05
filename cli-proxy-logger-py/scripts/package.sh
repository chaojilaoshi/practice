#!/usr/bin/env bash
# 一键打包（Linux/macOS）：把 Python 版打成离线可部署的 tar.gz。
# 纯标准库零依赖，只需把「包目录 cli_proxy_logger/ + 静态 UI public/ + README」打进包。
# 内网解压后：装好 CPython 3.8+，`python -m cli_proxy_logger` 即可运行。
set -euo pipefail
root="$(cd "$(dirname "$0")/.." && pwd)"
dist="$root/dist"
mkdir -p "$dist"
out="$dist/cli-proxy-logger-py.tar.gz"
rm -f "$out"
# 排除 __pycache__，避免把本机的 .pyc 打进包。
tar --exclude='__pycache__' -czf "$out" -C "$root" cli_proxy_logger public README.md
echo "built $out"
