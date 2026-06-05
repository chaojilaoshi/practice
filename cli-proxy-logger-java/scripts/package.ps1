# 一键打包（Windows PowerShell）：构建自包含 fat jar，连同样例配置放进 dist\。
# 必须在「能联网」的机器上跑（首次会从 Maven 中央仓库拉依赖）；产出的 jar 拷到
# 内网用 JDK 8+ 直接 `java -jar` 运行，无需 Maven、无需源码。
$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $PSScriptRoot           # 工程根目录（cli-proxy-logger-java）
Set-Location $root

# 跳过测试加快打包；要连测试一起跑就去掉 -DskipTests。
mvn -q -DskipTests clean package
if ($LASTEXITCODE -ne 0) { throw "maven package failed" }

$dist = Join-Path $root "dist"
New-Item -ItemType Directory -Force -Path $dist | Out-Null
Copy-Item (Join-Path $root "target\cli-proxy-logger-1.0.0.jar") $dist -Force
$sample = Join-Path $root "deploy\application.yml.sample"
if (Test-Path $sample) { Copy-Item $sample (Join-Path $dist "application.yml") -Force }
Write-Host "built $dist\cli-proxy-logger-1.0.0.jar (+ application.yml sample)"
