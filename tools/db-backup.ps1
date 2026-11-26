# ============================================================
# WMS 数据库备份 / 回滚脚本
# 用法（PowerShell，在项目根目录执行）：
#   .\tools\db-backup.ps1 backup          # 导一份当前库快照
#   .\tools\db-backup.ps1 rollback        # 回滚到最近一次备份（2 分钟内恢复）
#   .\tools\db-backup.ps1 list            # 列出所有备份
# ============================================================

param(
    [Parameter(Position = 0)]
    [ValidateSet('backup', 'rollback', 'list')]
    [string]$Action = 'backup'
)

$ErrorActionPreference = 'Stop'

$mysqlBin = 'C:\Program Files\MySQL\MySQL Server 8.0\bin'
$env:Path = "$mysqlBin;$env:Path"

$db = 'wms'
$user = 'root'
$pass = '123456'
$backupDir = Join-Path $PSScriptRoot 'db-backup'

if (-not (Test-Path $backupDir)) {
    New-Item -ItemType Directory -Force -Path $backupDir | Out-Null
}

switch ($Action) {

    'backup' {
        $stamp = Get-Date -Format 'yyyyMMdd-HHmmss'
        $file = Join-Path $backupDir "wms-full-$stamp.sql"
        mysqldump -u$user "-p$pass" --default-character-set=utf8mb4 `
            --single-transaction --routines --triggers $db > $file
        if ($LASTEXITCODE -ne 0) { throw "mysqldump 失败，退出码 $LASTEXITCODE" }
        Write-Host "[backup] 已导出：$file" -ForegroundColor Green
    }

    'rollback' {
        $latest = Get-ChildItem $backupDir -Filter 'wms-full-*.sql' |
            Sort-Object LastWriteTime -Descending | Select-Object -First 1
        if (-not $latest) {
            throw '没有找到任何备份文件，无法回滚'
        }
        Write-Host "[rollback] 将使用：$($latest.FullName)" -ForegroundColor Yellow

        # 重建库
        mysql -u$user "-p$pass" -e "DROP DATABASE IF EXISTS $db; CREATE DATABASE $db DEFAULT CHARACTER SET utf8mb4;"
        if ($LASTEXITCODE -ne 0) { throw "重建库失败，退出码 $LASTEXITCODE" }

        # 灌回数据
        Get-Content $latest.FullName -Raw | mysql -u$user "-p$pass" --default-character-set=utf8mb4 $db
        if ($LASTEXITCODE -ne 0) { throw "恢复数据失败，退出码 $LASTEXITCODE" }

        Write-Host "[rollback] 已回滚到 $($latest.Name)" -ForegroundColor Green
    }

    'list' {
        Get-ChildItem $backupDir -Filter '*.sql' |
            Sort-Object LastWriteTime -Descending |
            Select-Object Name, @{N = 'SizeKB'; E = { [math]::Round($_.Length / 1KB, 1) } }, LastWriteTime |
            Format-Table -AutoSize
    }
}
