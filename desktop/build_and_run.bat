@echo off
cd /d "%~dp0"

echo ========================================
echo    Encryption Tool - Build & Run
echo ========================================
echo.

call ".uild.bat" nopause
if errorlevel 1 exit /b 1

echo.
echo ========================================
echo    Launching application...
echo ========================================
echo.

call ".un.bat" nopause
exit /b %ERRORLEVEL%
