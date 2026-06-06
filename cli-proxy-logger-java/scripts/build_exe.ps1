# 把 Java 版打包成 Windows「单文件夹应用」(jpackage app-image)：
#   一个 cli-proxy-logger.exe 启动器 + 内置精简 JRE（用户无需安装 Java）。
#   双击 exe 即启动代理 + 可视化配置页，并自动打开浏览器。
#
# 用法（在装有 JDK 17（含 jpackage）+ Maven 的 Windows 上）：
#   powershell -ExecutionPolicy Bypass -File scripts\build_exe.ps1
#
# 产物：dist\app-image\cli-proxy-logger\   （整个文件夹即「绿色版」，可整体拷贝分发）
#         └─ cli-proxy-logger.exe          ← 双击启动
$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $PSScriptRoot
Set-Location $root

# 0) 需要 JDK 17 的 jpackage（JDK 14+ 自带；本仓推荐 Temurin 17）
$jpackage = (Get-Command jpackage -ErrorAction SilentlyContinue)
if (-not $jpackage) { throw "jpackage not found on PATH. Install JDK 17 (Temurin) and retry." }

# 1) 构建 Spring Boot 可执行 fat jar（跳过测试以加快打包；要带测试去掉 -DskipTests）
mvn -q -DskipTests clean package
if ($LASTEXITCODE -ne 0) { throw "maven package failed" }

$jar = "cli-proxy-logger-1.0.0.jar"
$stage = Join-Path $root "dist\stage"
$out   = Join-Path $root "dist\app-image"
Remove-Item -Recurse -Force $stage, $out -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Force -Path $stage | Out-Null
Copy-Item (Join-Path $root "target\$jar") $stage -Force

# 2) jpackage 生成 app-image（exe 启动器 + jlink 运行时镜像）。
#    Spring Boot 可执行 jar 的真正入口是 JarLauncher。
jpackage `
  --type app-image `
  --name cli-proxy-logger `
  --input $stage `
  --main-jar $jar `
  --main-class org.springframework.boot.loader.JarLauncher `
  --dest $out `
  --java-options "-Dfile.encoding=UTF-8" `
  --java-options "-Xmx256m"
if ($LASTEXITCODE -ne 0) { throw "jpackage failed" }

Remove-Item -Recurse -Force $stage -ErrorAction SilentlyContinue
Write-Output ""
Write-Output ("built " + (Join-Path $out "cli-proxy-logger\cli-proxy-logger.exe"))
Write-Output "整个 dist\app-image\cli-proxy-logger\ 文件夹即可拷贝分发；双击其中的 exe 启动。"
