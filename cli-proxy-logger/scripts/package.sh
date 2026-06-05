#!/usr/bin/env bash
# 一键打包（Linux/macOS）：把 Node 版打成离线可部署的 tar.gz。
# 本工程零第三方依赖，所以只需把「源码 + 静态 UI + package.json + README」打进包。
# 内网解压后：装好 Node>=18，`node src/index.js` 即可运行。
set -euo pipefail
root="$(cd "$(dirname "$0")/.." && pwd)"
dist="$root/dist"
mkdir -p "$dist"
out="$dist/cli-proxy-logger-node.tar.gz"
rm -f "$out"
# -C 让包内是相对路径（解压即得 src/ public/ ...），不带机器上的绝对路径。
tar -czf "$out" -C "$root" src public package.json README.md
echo "built $out"
