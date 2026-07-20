@echo off
chcp 65001 >nul
title WMS 前端服务 (端口 5173)
echo ========================================
echo   WMS 智能仓储系统 - 前端服务
echo   看到 Local: http://localhost:5173 说明启动成功
echo   注意: 关闭本窗口 = 停止前端服务
echo ========================================
cd /d "E:\毕业设计\WMS\wms-web"
set "NPM_CONFIG_PREFIX="
"D:\Node.js\npm.cmd" run dev
pause
