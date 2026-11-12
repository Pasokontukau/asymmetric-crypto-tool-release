@echo off
chcp 936 >nul
setlocal EnableExtensions

echo ========================================
echo  Build portable Windows package
echo ========================================
echo.

cd /d "%~dp0"

REM ---------- Maven ----------
set "MVN=%MAVEN_HOME%\bin\mvn.cmd"
if not exist "%MVN%" set "MVN="
if not defined MVN if exist "%USERPROFILE%\.trae-cn\tools\maven\latest\bin\mvn.cmd" set "MVN=%USERPROFILE%\.trae-cn\tools\maven\latest\bin\mvn.cmd"
if not defined MVN for /f "delims=" %%i in ('where mvn 2^>nul') do set "MVN=%%i"
if not defined MVN (
    echo ERROR: Maven not found.
    echo   Install Maven, or set MAVEN_HOME, or add mvn.cmd to PATH.
    exit /b 1
)

REM ---------- JDK 17 ----------
REM The bundled runtime is jlink'ed from this JDK, so it must be 17.
set "JDK17=%USERPROFILE%\.jdks\corretto-17"
if exist "%JDK17%\bin\jpackage.exe" set "JAVA_HOME=%JDK17%"
if not defined JAVA_HOME goto :no_jdk
if not exist "%JAVA_HOME%\bin\jpackage.exe" goto :no_jdk

set "JAVAC_VER="
for /f "tokens=2" %%v in ('"%JAVA_HOME%\bin\javac.exe" -version 2^>^&1') do set "JAVAC_VER=%%v"
echo %JAVAC_VER% | findstr /b /r "17\." >nul
if errorlevel 1 goto :wrong_jdk

echo JDK:     %JAVA_HOME%
echo Maven:   %MVN%
echo.

REM ---------- shared crypto-core ----------
REM monorepo 中先发布最新内核；独立桌面仓库没有这些目录时跳过。
if exist "..\android\gradlew.bat" (
    echo Publishing shared crypto-core to Maven Local...
    pushd "..\android"
    call ".\gradlew.bat" -Dorg.gradle.jvmargs="-Xmx2048m -Dfile.encoding=GBK" :crypto-core:publishToMavenLocal
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
call "%MVN%" clean package -DskipTests
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
set "PORTABLE=target\portable"
if exist "%PORTABLE%" rmdir /s /q "%PORTABLE%"
mkdir "%PORTABLE%\input"
copy /y "%FATJAR%" "%PORTABLE%\input\cryptoassistant.jar" >nul
if errorlevel 1 goto :copy_failed

REM One .ico exists in packaging/; stage it as ASCII app.ico.
copy /y "packaging\*.ico" "%PORTABLE%\app.ico" >nul
if errorlevel 1 goto :copy_failed

REM ---------- runtime ----------
echo [3/5] Building minimal Java runtime with jlink...
REM java.instrument is not required by the app, but keeps -javaagent usable for
REM the portable smoke test and future diagnostics; it costs well under 1 MB.
"%JAVA_HOME%\bin\jlink.exe" --add-modules java.base,java.desktop,java.logging,java.management,jdk.management,java.prefs,java.instrument --output "%PORTABLE%\runtime" --strip-debug --no-header-files --no-man-pages --compress=2
if errorlevel 1 (
    echo ERROR: jlink failed.
    exit /b 1
)

REM ---------- app image ----------
echo [4/5] Building app-image with jpackage...
"%JAVA_HOME%\bin\jpackage.exe" --type app-image --input "%CD%\%PORTABLE%\input" --main-jar cryptoassistant.jar --main-class app.AppMain --name CryptoAssistant --app-version %APP_VERSION% --vendor CryptoAssistant --icon "%CD%\%PORTABLE%\app.ico" --runtime-image "%CD%\%PORTABLE%\runtime" --java-options "-Dapp.home=$BINDIR" --dest "%CD%\%PORTABLE%"
if errorlevel 1 (
    echo ERROR: jpackage failed.
    exit /b 1
)

copy /y "packaging\README-portable.txt" "%PORTABLE%\CryptoAssistant\README.txt" >nul
if errorlevel 1 goto :copy_failed

REM ---------- zip + sha256 ----------
echo [5/5] Creating ZIP and SHA-256...
set "ZIP=%CD%\%PORTABLE%\CryptoAssistant-%APP_VERSION%-win64.zip"
powershell -NoProfile -ExecutionPolicy Bypass -Command "Compress-Archive -LiteralPath '%CD%\%PORTABLE%\CryptoAssistant' -DestinationPath '%ZIP%' -CompressionLevel Optimal -Force"
if errorlevel 1 (
    echo ERROR: Compress-Archive failed.
    exit /b 1
)

powershell -NoProfile -ExecutionPolicy Bypass -Command "$p='%ZIP%'; $h=(Get-FileHash -LiteralPath $p -Algorithm SHA256).Hash.ToLower(); $n=[IO.Path]::GetFileName($p); Set-Content -LiteralPath ($p + '.sha256') -Value ($h + '  ' + $n) -Encoding ascii"
if errorlevel 1 (
    echo ERROR: SHA-256 generation failed.
    exit /b 1
)

echo.
echo BUILD SUCCESS
echo   App image: %PORTABLE%\CryptoAssistant
echo   ZIP:       %ZIP%
for %%f in ("%ZIP%") do echo   ZIP size:  %%~zf bytes
echo   SHA-256:   %ZIP%.sha256
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
echo ERROR: Portable package must be built with JDK 17, but JAVA_HOME is %JAVAC_VER%.
echo   Set JAVA_HOME to a JDK 17 installation, then rerun package-portable.bat.
exit /b 1
