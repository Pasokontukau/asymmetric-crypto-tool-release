@echo off
setlocal
chcp 936 >nul

set "ROOT=%~dp0"
set "DESKTOP=%ROOT%desktop"
set "MODE=%~1"
if not defined MODE set "MODE=auto"

if /i "%MODE%"=="help" goto :usage
if /i "%MODE%"=="-h" goto :usage
if /i "%MODE%"=="--help" goto :usage
if /i "%MODE%"=="build" goto :build
if /i "%MODE%"=="run" goto :run
if /i "%MODE%"=="auto" goto :auto
goto :usage

:auto
if not exist "%DESKTOP%\app.jar" goto :build
echo [INFO] Using existing app.jar; run "start-desktop.bat build" to force a rebuild.
goto :run

:build
if not exist "%DESKTOP%\pom.xml" goto :no_project
echo.
echo [INFO] Building desktop app: publish shared core + Maven package...
call "%DESKTOP%\build.bat" nopause
if errorlevel 1 goto :build_failed

:run
if not exist "%DESKTOP%\app.jar" (
    if not exist "%DESKTOP%\target\classes\app\AppMain.class" goto :no_binary
)
echo.
echo [INFO] Data directory: app settings (default: %DESKTOP%\data)
echo [INFO] Starting desktop app; this window shows the exit code after it closes.
call "%DESKTOP%\run.bat" nopause
set "CODE=%ERRORLEVEL%"
if not "%CODE%"=="0" goto :run_failed
echo [INFO] Desktop app closed.
exit /b 0

:build_failed
echo.
echo [ERROR] Desktop build failed; see the output above.
pause
exit /b 1

:run_failed
echo.
echo [ERROR] Desktop app exited with code %CODE%; logs: %DESKTOP%\data\logs
pause
exit /b %CODE%

:no_project
echo [ERROR] Missing %DESKTOP%\pom.xml; check the repository layout.
pause
exit /b 1

:no_binary
echo [ERROR] No desktop build found; run start-desktop.bat build first.
pause
exit /b 1

:usage
echo Usage: start-desktop.bat [auto^|build^|run^|help]
echo   auto   default; launch existing app.jar, or build first if missing
echo   build  force rebuild, then launch
echo   run    launch existing build only, no build
pause
exit /b 1
