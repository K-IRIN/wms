@echo off
chcp 65001 >nul
title WMS 前端服务 (端口 5173)
echo ========================================
echo   WMS 智能仓储系统 - 前端服务
echo ========================================
echo.
echo   选择启动模式:
echo    [1] 开发模式 - 本地编写代码、调试用 (文件多，加载较慢)
echo    [2] 演示模式 - 花生壳穿透、答辩演示用 (打包构建，加载快)
echo.
set /p choice="   请输入 1 或 2 后回车: "

if "%choice%"=="1" goto dev
if "%choice%"=="2" goto preview

echo   无效输入，默认使用 演示模式...

:preview
echo.
echo ========================================
echo   演示模式: 先构建，再提供打包后的文件
echo   花生壳穿透时加载速度大幅提升
echo ========================================
cd /d "E:\毕业设计\WMS\wms-web"
set "NPM_CONFIG_PREFIX="
echo   正在构建前端...
call "D:\Node.js\npm.cmd" run build
echo.
echo   构建完成，启动预览服务器...
"D:\Node.js\npm.cmd" run preview
pause
exit

:dev
echo.
echo ========================================
echo   开发模式: 源码热更新，适合本地调代码
echo   注意: 花生壳演示请用演示模式
echo ========================================
cd /d "E:\毕业设计\WMS\wms-web"
set "NPM_CONFIG_PREFIX="
"D:\Node.js\npm.cmd" run dev
pause
exit
