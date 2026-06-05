# 一键打包（Windows PowerShell）：把 Python 版打成离线可部署的 zip。
# 纯标准库零依赖，只需把「包目录 cli_proxy_logger\ + 静态 UI public\ + README」打进包。
# 内网解压后：装好 CPython 3.8+，`python -m cli_proxy_logger` 即可运行。
$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $PSScriptRoot           # 工程根目录（cli-proxy-logger-py）
$dist = Join-Path $root "dist"
New-Item -ItemType Directory -Force -Path $dist | Out-Null
$zip = Join-Path $dist "cli-proxy-logger-py.zip"
if (Test-Path $zip) { Remove-Item $zip }

# 先把要打包的内容拷到临时目录，顺便剔除 __pycache__，再压缩。
$staging = Join-Path $env:TEMP ("cpl-py-" + [System.Guid]::NewGuid().ToString("N"))
New-Item -ItemType Directory -Force -Path $staging | Out-Null
try {
    Copy-Item (Join-Path $root "cli_proxy_logger") $staging -Recurse
    Copy-Item (Join-Path $root "public") $staging -Recurse
    Copy-Item (Join-Path $root "README.md") $staging
    Get-ChildItem $staging -Recurse -Directory -Filter "__pycache__" | Remove-Item -Recurse -Force
    Compress-Archive -Path (Join-Path $staging "*") -DestinationPath $zip
    Write-Host "built $zip"
}
finally {
    Remove-Item $staging -Recurse -Force
}
