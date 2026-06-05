# 一键打包（Windows PowerShell）：把 Node 版打成离线可部署的 zip。
# 本工程零第三方依赖，所以只需把「源码 + 静态 UI + package.json + README」打进包。
# 内网解压后：装好 Node>=18，`node src\index.js` 即可运行。
$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $PSScriptRoot           # 工程根目录（cli-proxy-logger）
$dist = Join-Path $root "dist"
New-Item -ItemType Directory -Force -Path $dist | Out-Null
$zip = Join-Path $dist "cli-proxy-logger-node.zip"
if (Test-Path $zip) { Remove-Item $zip }
$items = @("src", "public", "package.json", "README.md") | ForEach-Object { Join-Path $root $_ }
Compress-Archive -Path $items -DestinationPath $zip
Write-Host "built $zip"
