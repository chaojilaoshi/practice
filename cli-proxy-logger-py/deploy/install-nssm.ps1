# Windows 服务安装脚本（Python 版，借助 nssm）。
# 前提：已安装 nssm（https://nssm.cc/ 或 choco install nssm）。
# 以「管理员」PowerShell 运行。卸载：nssm remove cli-proxy-logger-py confirm
$ErrorActionPreference = "Stop"

# —— 按实际改这几项 ——
$ServiceName = "cli-proxy-logger-py"
$PythonExe   = (Get-Command python).Source                     # python.exe 绝对路径
$AppDir      = Split-Path -Parent $PSScriptRoot                # 工程根目录（cli-proxy-logger-py）
$LogDir      = "C:\ProgramData\cli-proxy-logger-py\logs"

New-Item -ItemType Directory -Force -Path $LogDir | Out-Null

# 用 -m 从包目录启动；AppDirectory 设为工程根，保证能 import cli_proxy_logger。
nssm install $ServiceName $PythonExe "-m" "cli_proxy_logger"
nssm set $ServiceName AppDirectory $AppDir
nssm set $ServiceName AppStdout (Join-Path $LogDir "service-stdout.log")
nssm set $ServiceName AppStderr (Join-Path $LogDir "service-stderr.log")
nssm set $ServiceName AppEnvironmentExtra `
    "PROXY_PORT=8788" "UI_PORT=8789" "LOG_DIR=$LogDir" `
    "OPENAI_UPSTREAM=https://api.openai.com" "ANTHROPIC_UPSTREAM=https://api.anthropic.com"
nssm set $ServiceName Start SERVICE_AUTO_START

nssm start $ServiceName
Write-Host "installed & started service '$ServiceName' (UI: http://127.0.0.1:8789)"
