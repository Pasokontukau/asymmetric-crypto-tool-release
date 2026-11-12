@echo off
cd /d "%~dp0"

echo ========================================
echo    Encryption Tool - Build & Run
echo ========================================
echo.

call build.bat nopause
if errorlevel 1 exit /b 1

echo.
echo ========================================
echo    Launching application...
echo ========================================
echo.

call run.bat nopause
exit /b %ERRORLEVEL%
