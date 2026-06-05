# Windows 服务安装脚本（Node 版，借助 nssm）。
# 前提：已安装 nssm（https://nssm.cc/ 下载解压即用，或 choco install nssm）。
# 以「管理员」PowerShell 运行本脚本。卸载：nssm remove cli-proxy-logger confirm
$ErrorActionPreference = "Stop"

# —— 按实际改这几项 ——
$ServiceName = "cli-proxy-logger"
$NodeExe     = (Get-Command node).Source                       # node.exe 绝对路径
$AppDir      = Split-Path -Parent $PSScriptRoot                # 工程根目录（cli-proxy-logger）
$EntryJs     = Join-Path $AppDir "src\index.js"
$LogDir      = "C:\ProgramData\cli-proxy-logger\logs"

New-Item -ItemType Directory -Force -Path $LogDir | Out-Null

# 安装服务：nssm install <name> <program> <args...>
nssm install $ServiceName $NodeExe $EntryJs
nssm set $ServiceName AppDirectory $AppDir
nssm set $ServiceName AppStdout (Join-Path $LogDir "service-stdout.log")
nssm set $ServiceName AppStderr (Join-Path $LogDir "service-stderr.log")
# 环境变量（一行一个 KEY=VALUE，用换行分隔）：
nssm set $ServiceName AppEnvironmentExtra `
    "PROXY_PORT=8788" "UI_PORT=8789" "LOG_DIR=$LogDir" `
    "OPENAI_UPSTREAM=https://api.openai.com" "ANTHROPIC_UPSTREAM=https://api.anthropic.com"
nssm set $ServiceName Start SERVICE_AUTO_START

nssm start $ServiceName
Write-Host "installed & started service '$ServiceName' (UI: http://127.0.0.1:8789)"
