@echo off
chcp 65001 >nul 2>&1
setlocal enabledelayedexpansion

title JavaToolBox

:: ── 配置 ──
set "TOOL_DIR=%~dp0tools"
set "BUILD=%~dp0tools\.build"
set "PORT=8080"

:: ── 检查 JDK ──
where javac >nul 2>&1
if %errorlevel% neq 0 (
    echo.
    echo  [错误] 未找到 javac，请确认已安装 JDK 并配置 PATH
    echo.
    pause
    exit /b 1
)

:: ── 目录 ──
if not exist "%TOOL_DIR%" mkdir "%TOOL_DIR%"
if not exist "%BUILD%" mkdir "%BUILD%"

echo.
echo   ◆ JavaToolBox
echo   ├─ 编译中...
echo.

:: ── 编译（只用 -encoding UTF-8，不要带 -D 参数） ──
javac -encoding UTF-8 -d "%BUILD%" "%~dp0JavaToolBox.java"
if %errorlevel% neq 0 (
    echo.
    echo  [错误] 编译失败，请检查代码
    echo.
    pause
    exit /b 1
)

echo   ├─ 编译成功
echo   ├─ 工具目录: %TOOL_DIR%
echo   ├─ 端口: %PORT%
echo   └─ 启动中...
echo.

:: ── 启动（-Dfile.encoding 只放在这里） ──
java -Dfile.encoding=UTF-8 -cp "%BUILD%" JavaToolBox "%TOOL_DIR%" %PORT%

:: ── 异常退出 ──
echo.
echo   已停止运行
echo.
pause
