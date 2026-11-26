# ============================================================
# WMS 本地开发环境变量（一条命令起环境）
# 用法：先执行  . .\tools\env.ps1  再执行 mvn / npm 命令
# 依赖：JDK 21（jbr-21.0.11）、MySQL 8.0（wms 库）、Node 18+
# ============================================================

$ErrorActionPreference = 'Stop'

# ---------- JDK 21 ----------
$jdk21 = 'C:\Users\16134\.jdks\jbr-21.0.11'
if (Test-Path $jdk21) {
    $env:JAVA_HOME = $jdk21
    $env:Path = "$jdk21\bin;$env:Path"
    Write-Host "[env] JAVA_HOME -> $jdk21" -ForegroundColor Green
} else {
    Write-Warning "[env] 未找到 JDK21：$jdk21，请手动设置 JAVA_HOME"
}

# ---------- MySQL 8.0 ----------
$mysqlBin = 'C:\Program Files\MySQL\MySQL Server 8.0\bin'
if (Test-Path $mysqlBin) {
    $env:Path = "$mysqlBin;$env:Path"
    Write-Host "[env] MySQL bin -> $mysqlBin" -ForegroundColor Green
}

# ---------- 项目路径 ----------
$env:WMS_HOME = 'D:\Desktop\wms-main'
$env:WMS_WEB = 'D:\Desktop\wms-main\wms-web'

# ---------- 数据库连接（与应用配置保持一致） ----------
$env:WMS_DB = 'wms'
$env:WMS_DB_USER = 'root'
$env:WMS_DB_PASS = '123456'

Write-Host "[env] 就绪：java -version 应显示 21；mysql -e `"SELECT 1`" 应可用" -ForegroundColor Cyan
