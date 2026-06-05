# Windows 服务安装脚本（Java 版，借助 nssm）。
# 前提：已安装 nssm（https://nssm.cc/ 或 choco install nssm）和 JDK 8+。
# 以「管理员」PowerShell 运行。卸载：nssm remove cli-proxy-logger-java confirm
$ErrorActionPreference = "Stop"

# —— 按实际改这几项 ——
$ServiceName = "cli-proxy-logger-java"
$JavaExe     = (Get-Command java).Source                       # java.exe 绝对路径
$AppDir      = Split-Path -Parent $PSScriptRoot                # 工程根目录（cli-proxy-logger-java）
$Jar         = Join-Path $AppDir "target\cli-proxy-logger-1.0.0.jar"   # 或指向 dist\ 下的 jar
$LogDir      = "C:\ProgramData\cli-proxy-logger-java\logs"

New-Item -ItemType Directory -Force -Path $LogDir | Out-Null

# 端口/上游用命令行参数覆盖（也可放 application.yml 在 jar 同级目录）。
$javaArgs = "-jar `"$Jar`" --server.port=8788 --proxy.log-dir=`"$LogDir`"" + `
            " --proxy.openai-upstream=https://api.openai.com" + `
            " --proxy.anthropic-upstream=https://api.anthropic.com"
nssm install $ServiceName $JavaExe $javaArgs
nssm set $ServiceName AppDirectory $AppDir
nssm set $ServiceName AppStdout (Join-Path $LogDir "service-stdout.log")
nssm set $ServiceName AppStderr (Join-Path $LogDir "service-stderr.log")
nssm set $ServiceName Start SERVICE_AUTO_START

nssm start $ServiceName
Write-Host "installed & started service '$ServiceName' (UI: http://127.0.0.1:8788)"
