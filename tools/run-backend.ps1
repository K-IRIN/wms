# ============================================================
# 启动后端（带日志文件，供 tools/api.py 自动取验证码）
# 用法：  .\tools\run-backend.ps1
# 日志：  .\logs\backend.out.log
# 说明： 产物名为 target\wms-server.jar（finalName 取自 pom，非版本号形式）
# ============================================================

$ErrorActionPreference = 'Stop'

. (Join-Path $PSScriptRoot 'env.ps1')

$root = Split-Path $PSScriptRoot -Parent

# ---------- 本地密钥（不入库，见 .tmp/README-secrets.md） ----------
$secrets = Join-Path $root '.tmp\secrets.ps1'
if (Test-Path $secrets) { . $secrets }
if (-not $env:DB_PASSWORD -or -not $env:JWT_SECRET) {
    throw "缺少 DB_PASSWORD / JWT_SECRET。请在 $secrets 中设置（模板见 .tmp\README-secrets.md）"
}

$logDir = Join-Path $root 'logs'
if (-not (Test-Path $logDir)) { New-Item -ItemType Directory -Force -Path $logDir | Out-Null }
$logFile = Join-Path $logDir 'backend.out.log'

Set-Location $root

if ($args -contains '--rebuild') {
    Write-Host "[backend] 重新构建..." -ForegroundColor Cyan
    mvn -q -DskipTests package
    if ($LASTEXITCODE -ne 0) { throw "构建失败，退出码 $LASTEXITCODE" }
}

$jar = Join-Path $root 'target\wms-server.jar'
if (-not (Test-Path $jar)) {
    throw "未找到 $jar，请先执行：mvn -DskipTests package"
}

Write-Host "[backend] 日志文件：$logFile" -ForegroundColor Cyan
Write-Host "[backend] 启动 wms-server（Ctrl+C 停止）" -ForegroundColor Cyan

# 阶段 9.5：WebSocket 阻塞写超时 2s。慢客户端不读 socket 时，发送线程不会被
# 内核的 TCP 阻塞无限期挂住，而是超时抛异常，由 AgvWebSocketHandler 摘除并关闭连接。
java "-Dorg.apache.tomcat.websocket.BLOCKING_SEND_TIMEOUT=2000" -jar $jar 2>&1 | Tee-Object -FilePath $logFile
