@echo off
chcp 65001 >nul
title WMS 后端服务 (端口 8080)
echo ========================================
echo   WMS 智能仓储系统 - 后端服务
echo   密钥读取：.tmp\secrets.ps1（DB_PASSWORD / JWT_SECRET）
echo   运行日志：logs\backend.out.log
echo   注意: 关闭本窗口 = 停止后端服务
echo ========================================
cd /d "%~dp0"
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0tools\run-backend.ps1"
pause
