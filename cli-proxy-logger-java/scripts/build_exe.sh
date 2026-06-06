#!/usr/bin/env bash
# 把 Java 版打包成「单文件夹应用」(jpackage app-image)：一个原生启动器 + 内置精简 JRE。
# 在 Windows 上产物是 cli-proxy-logger.exe；在 macOS/Linux 上是对应的原生启动器。
# 双击/运行启动器即起代理 + 可视化配置页，并自动打开浏览器。
#
# 用法（需 JDK 17（含 jpackage）+ Maven）：
#   bash scripts/build_exe.sh
#
# 产物：dist/app-image/cli-proxy-logger/   （整个文件夹即「绿色版」，可整体拷贝分发）
set -euo pipefail
root="$(cd "$(dirname "$0")/.." && pwd)"
cd "$root"

command -v jpackage >/dev/null 2>&1 || { echo "jpackage not found on PATH. Install JDK 17 (Temurin) and retry." >&2; exit 1; }

# 1) 构建 Spring Boot 可执行 fat jar（跳过测试以加快打包；要带测试去掉 -DskipTests）
mvn -q -DskipTests clean package

jar="cli-proxy-logger-1.0.0.jar"
stage="$root/dist/stage"
out="$root/dist/app-image"
rm -rf "$stage" "$out"
mkdir -p "$stage"
cp "$root/target/$jar" "$stage/"

# 2) jpackage app-image（启动器 + jlink 运行时镜像）。Spring Boot 可执行 jar 入口是 JarLauncher。
jpackage \
  --type app-image \
  --name cli-proxy-logger \
  --input "$stage" \
  --main-jar "$jar" \
  --main-class org.springframework.boot.loader.JarLauncher \
  --dest "$out" \
  --java-options "-Dfile.encoding=UTF-8" \
  --java-options "-Xmx256m"

rm -rf "$stage"
echo ""
echo "built $out/cli-proxy-logger/  (整个文件夹即可拷贝分发；运行其中的启动器即可)"
