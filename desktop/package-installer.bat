@echo off
chcp 65001 >nul
setlocal EnableExtensions

echo ========================================
echo  Build Windows installer (jpackage)
echo ========================================
echo.

cd /d "%~dp0"

REM ---------- Maven ----------
set "MVN=%MAVEN_HOME%\bin\mvn.cmd"
if not exist "%MVN%" set "MVN="
if not defined MVN if exist "%USERPROFILE%\.trae-cn\tools\maven\latest\bin\mvn.cmd" set "MVN=%USERPROFILE%\.trae-cn\tools\maven\latest\bin\mvn.cmd"
if not defined MVN for /f "delims=" %%i in ('where mvn 2^>nul') do set "MVN=%%i"
rem Maven prints Chinese paths and Javac warnings with the JVM default charset;
rem force UTF-8 so chcp 65001 consoles and redirected logs stay readable.
set "MAVEN_OPTS=%MAVEN_OPTS% -Dfile.encoding=UTF-8"
if not defined MVN (
    echo ERROR: Maven not found.
    exit /b 1
)

REM ---------- JDK 17 ----------
set "JDK17=%USERPROFILE%\.jdks\corretto-17"
if exist "%JDK17%\bin\jpackage.exe" set "JAVA_HOME=%JDK17%"
if not defined JAVA_HOME goto :no_jdk
if not exist "%JAVA_HOME%\bin\jpackage.exe" goto :no_jdk

set "JAVAC_VER="
for /f "tokens=2" %%v in ('"%JAVA_HOME%\bin\javac.exe" -version 2^>^&1') do set "JAVAC_VER=%%v"
echo %JAVAC_VER% | findstr /b /r "17\." >nul
if errorlevel 1 goto :wrong_jdk

REM ---------- WiX 3 (required by jpackage for exe/msi) ----------
set "WIX_DIR="
if exist "%LOCALAPPDATA%\CryptoAssistant\build-tools\wix3\candle.exe" set "WIX_DIR=%LOCALAPPDATA%\CryptoAssistant\build-tools\wix3"
if not defined WIX_DIR if exist "%ProgramFiles(x86)%\WiX Toolset v3.14\bin\candle.exe" set "WIX_DIR=%ProgramFiles(x86)%\WiX Toolset v3.14\bin"
if not defined WIX_DIR if exist "%ProgramFiles(x86)%\WiX Toolset v3.11\bin\candle.exe" set "WIX_DIR=%ProgramFiles(x86)%\WiX Toolset v3.11\bin"
if not defined WIX_DIR for /f "delims=" %%i in ('where candle.exe 2^>nul') do set "WIX_DIR=%%~dpi"
if not defined WIX_DIR goto :no_wix
set "PATH=%WIX_DIR%;%PATH%"

echo JDK:     %JAVA_HOME%
echo Maven:   %MVN%
echo WiX:     %WIX_DIR%
echo.

REM ---------- shared crypto-core ----------
if exist "..\android\gradlew.bat" (
    echo Publishing shared crypto-core to Maven Local...
    pushd "..\android"
    call ".\gradlew.bat" -Dorg.gradle.jvmargs="-Xmx2048m -Dfile.encoding=UTF-8" :crypto-core:publishToMavenLocal
    if errorlevel 1 (
        popd
        echo ERROR: Failed to publish shared crypto-core.
        exit /b 1
    )
    popd
)

REM ---------- version ----------
set "APP_VERSION="
for /f "usebackq delims=" %%v in (`powershell -NoProfile -Command "[regex]::Match((Get-Content -Raw 'pom.xml'), '<revision>([^<]+)<').Groups[1].Value"`) do set "APP_VERSION=%%v"
if not defined APP_VERSION (
    echo ERROR: Cannot read revision from pom.xml.
    exit /b 1
)
echo Version: %APP_VERSION%
echo.

REM ---------- build fat jar ----------
echo [1/5] Building fat jar...
REM Record the source commit into the jar manifest (Implementation-Build), so the About
REM page can say which commit produced this build.
set "GIT_COMMIT=unknown"
for /f "delims=" %%i in ('git -C "%CD%\.." rev-parse --short=9 HEAD 2^>nul') do set "GIT_COMMIT=%%i"
call "%MVN%" clean package -DskipTests "-Dgit.commit=%GIT_COMMIT%"
if errorlevel 1 (
    echo ERROR: Maven build failed.
    exit /b 1
)

set "FATJAR=target\asymmetric-encryption-tool-%APP_VERSION%-jar-with-dependencies.jar"
if not exist "%FATJAR%" (
    echo ERROR: Fat jar not found: %FATJAR%
    exit /b 1
)

REM ---------- staging ----------
echo [2/5] Preparing staging directory...
set "STAGE=target\installer-build"
if exist "%STAGE%" rmdir /s /q "%STAGE%"
mkdir "%STAGE%\input"
copy /y "%FATJAR%" "%STAGE%\input\cryptoassistant.jar" >nul
if errorlevel 1 goto :copy_failed
copy /y "packaging\*.ico" "%STAGE%\app.ico" >nul
if errorlevel 1 goto :copy_failed

REM ---------- runtime ----------
echo [3/5] Building minimal Java runtime with jlink...
"%JAVA_HOME%\bin\jlink.exe" --add-modules java.base,java.desktop,java.logging,java.management,jdk.management,java.prefs,java.instrument --output "%STAGE%\runtime" --strip-debug --no-header-files --no-man-pages --compress=2
if errorlevel 1 (
    echo ERROR: jlink failed.
    exit /b 1
)

REM ---------- installer ----------
echo [4/5] Building EXE installer with jpackage + WiX...
if exist "target\installer" rmdir /s /q "target\installer"
"%JAVA_HOME%\bin\jpackage.exe" --type exe ^
  --input "%CD%\%STAGE%\input" ^
  --main-jar cryptoassistant.jar ^
  --main-class app.AppMain ^
  --name CryptoAssistant ^
  --app-version %APP_VERSION% ^
  --vendor CryptoAssistant ^
  --description "Local offline asymmetric encryption tool" ^
  --copyright "GPL-3.0-only" ^
  --icon "%CD%\%STAGE%\app.ico" ^
  --runtime-image "%CD%\%STAGE%\runtime" ^
  --java-options "-Dapp.home=$BINDIR" --java-options "-Dfile.encoding=UTF-8" ^
  --install-dir CryptoAssistant ^
  --win-menu ^
  --win-menu-group CryptoAssistant ^
  --win-shortcut ^
  --win-dir-chooser ^
  --win-upgrade-uuid 9fe18cc4-b435-46d7-983c-5247a4b5e4f1 ^
  --dest "%CD%\target\installer"
if errorlevel 1 (
    echo ERROR: jpackage failed. Is WiX 3 installed and on PATH?
    exit /b 1
)

set "SETUP=target\installer\CryptoAssistant-%APP_VERSION%.exe"
if not exist "%SETUP%" (
    echo ERROR: Installer not found: %SETUP%
    exit /b 1
)

copy /y "packaging\README-installer.txt" "target\installer\README-installer.txt" >nul

REM ---------- sha256 ----------
echo [5/5] Computing SHA-256...
powershell -NoProfile -ExecutionPolicy Bypass -Command "$p='%CD%\%SETUP%'; $h=(Get-FileHash -LiteralPath $p -Algorithm SHA256).Hash.ToLower(); $n=[IO.Path]::GetFileName($p); Set-Content -LiteralPath ($p + '.sha256') -Value ($h + '  ' + $n) -Encoding ascii"
if errorlevel 1 (
    echo ERROR: SHA-256 generation failed.
    exit /b 1
)

echo.
echo BUILD SUCCESS
echo   Installer: %CD%\%SETUP%
for %%f in ("%SETUP%") do echo   Size:      %%~zf bytes
echo   SHA-256:   %CD%\%SETUP%.sha256
echo.
echo Note: the installer is per-machine and needs administrator rights.
echo       Installed app data falls back to %%APPDATA%%\CryptoAssistant\data.
echo.
exit /b 0

:copy_failed
echo ERROR: Failed to copy a staging file.
exit /b 1

:no_jdk
echo ERROR: JDK 17 with jpackage.exe not found.
echo   Expected %JDK17% or a JAVA_HOME pointing at JDK 17.
exit /b 1

:wrong_jdk
echo ERROR: Installer must be built with JDK 17, but JAVA_HOME is %JAVAC_VER%.
exit /b 1

:no_wix
echo ERROR: WiX Toolset 3 not found (candle.exe/light.exe).
echo   Options:
echo     1. Download wix314-binaries.zip and extract to
echo        %%LOCALAPPDATA%%\CryptoAssistant\build-tools\wix3
echo     2. Install WiX Toolset 3.14 to Program Files
echo     3. Put candle.exe on PATH
exit /b 1
