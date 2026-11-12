@echo off
chcp 936 >nul
echo ========================================
echo    Encryption Tool - Debug Mode
echo ========================================
echo.

REM Switch to script directory
cd /d "%~dp0"

REM Check if app.jar exists
if not exist "app.jar" (
    echo [ERROR] app.jar not found!
    echo.
    echo Please run build.bat and then create_fat_jar.bat first
    echo.
    pause
    exit /b 1
)

REM Prefer the documented JDK 17, then JAVA_HOME, then system java.
set "JDK17=%USERPROFILE%\.jdks\corretto-17"
set "JAVA=%JDK17%\bin\java.exe"
if not exist "%JAVA%" set "JAVA="
if not defined JAVA if defined JAVA_HOME if exist "%JAVA_HOME%\bin\java.exe" set "JAVA=%JAVA_HOME%\bin\java.exe"
if not defined JAVA for /f "delims=" %%i in ('where java 2^>nul') do set "JAVA=%%i"

if not defined JAVA (
    echo [ERROR] java.exe not found
    pause
    exit /b 1
)

echo [INFO] Using Java: %JAVA%
"%JAVA%" -jar app.jar

echo.
echo ========================================
echo Program exited
echo ========================================
echo.
pause
