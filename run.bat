@echo off
chcp 65001 >nul
title Project EasyAI - AI Backend

echo ============================================================
echo   Project EasyAI - 一键启动脚本 (Windows)
echo ============================================================
echo.

REM ============================================================
REM 1. 检查 Python 环境
REM ============================================================
where python >nul 2>nul
if %errorlevel% neq 0 (
    echo [错误] 未找到 Python，请先安装 Python 3.10+
    pause
    exit /b 1
)

REM ============================================================
REM 2. 检查并安装依赖
REM ============================================================
echo [1/3] 检查 Python 依赖...
python -c "import websockets, aiohttp, rich, prompt_toolkit" 2>nul
if %errorlevel% neq 0 (
    echo [1/3] 正在安装依赖包...
    pip install -r "%~dp0requirements.txt" -i https://mirrors.aliyun.com/pypi/simple/
    if %errorlevel% neq 0 (
        echo [错误] 依赖安装失败
        pause
        exit /b 1
    )
) else (
    echo [1/3] 依赖已就绪
)

REM ============================================================
REM 3. 检查日志目录
REM ============================================================
if not exist "%~dp0logs" (
    mkdir "%~dp0logs"
)

REM ============================================================
REM 4. 启动 Python 后端
REM ============================================================
echo [2/3] 启动 Project EasyAI AI 后端...
echo [3/3] WebSocket 服务器将在 127.0.0.1:8765 监听
echo.
echo ============================================================
echo   请在 PCL2 中启动加载了 EasyAI Mod 的 Minecraft 客户端
echo   日志栏将显示 "[系统] 本地端口8765握手成功，AI实体已注入"
echo ============================================================
echo.

cd /d "%~dp0"
python "%~dp0python\main.py"

pause
