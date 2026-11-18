@echo off
chcp 65001 >nul

cd /d "%~dp0"

REM The desktop app targets Java 17 and the portable package bundles its own
REM runtime. Prefer the documented JDK 17 for local runs so the acceptance
REM matrix stays on one JDK; JAVA_HOME / PATH are fallbacks for development.
set "JDK17=%USERPROFILE%\.jdks\corretto-17"
set "JAVA=%JDK17%\bin\java.exe"
if not exist "%JAVA%" set "JAVA="
if not defined JAVA if defined JAVA_HOME if exist "%JAVA_HOME%\bin\java.exe" set "JAVA=%JAVA_HOME%\bin\java.exe"
if not defined JAVA for /f "delims=" %%i in ('where java 2^>nul') do set "JAVA=%%i"

if not defined JAVA (
    echo ERROR: java.exe not found
    if /i not "%~1"=="nopause" pause
    exit /b 1
)

echo Using Java: %JAVA%

set "RUN_CODE=0"
if not exist app.jar goto :target_classes

echo Running: java -jar app.jar
echo.
"%JAVA%" -Dfile.encoding=UTF-8 -jar app.jar
set "RUN_CODE=%ERRORLEVEL%"
goto :end

:target_classes
if not exist target\classes\app\AppMain.class goto :missing

echo Running from: target\classes
echo.
"%JAVA%" -Dfile.encoding=UTF-8 -cp "target\classes" app.AppMain
set "RUN_CODE=%ERRORLEVEL%"
goto :end

:missing
echo ERROR: No compiled application found.
echo Please run build.bat first.
set "RUN_CODE=1"

:end
if /i not "%~1"=="nopause" pause
exit /b %RUN_CODE%
