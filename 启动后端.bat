@echo off
chcp 65001 >nul
title WMS 后端服务 (端口 8080)
echo ========================================
echo   WMS 智能仓储系统 - 后端服务
echo   看到 Started 字样说明启动成功(约15秒)
echo   注意: 关闭本窗口 = 停止后端服务
echo ========================================
set "JAVA_HOME=C:\Program Files\Android\Android Studio\jbr"
cd /d "D:\Desktop\wms-main"
"%JAVA_HOME%\bin\java.exe" -jar target\wms-server.jar --server.port=8080
pause
