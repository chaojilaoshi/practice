#!/usr/bin/env bash
# 一键打包（Linux/macOS）：构建自包含 fat jar，连同样例配置/部署文件放进 dist/。
# 必须在「能联网」的机器上跑（首次会从 Maven 中央仓库拉依赖）；产出的 jar 拷到
# 内网用 JDK 8+ 直接 `java -jar` 运行，无需 Maven、无需源码。
set -euo pipefail
root="$(cd "$(dirname "$0")/.." && pwd)"
cd "$root"

# 跳过测试加快打包；要连测试一起跑就去掉 -DskipTests。
mvn -q -DskipTests clean package

dist="$root/dist"
mkdir -p "$dist"
# pom 里 artifactId=cli-proxy-logger、version=1.0.0 → 产物名固定如下。
cp "$root/target/cli-proxy-logger-1.0.0.jar" "$dist/"
# 附带样例外置配置与服务模板，方便内网直接改。
[ -f "$root/deploy/application.yml.sample" ] && cp "$root/deploy/application.yml.sample" "$dist/application.yml"
echo "built $dist/cli-proxy-logger-1.0.0.jar (+ application.yml sample)"
