@echo off
chcp 65001 >nul
setlocal EnableExtensions

REM ============================================================
REM  Desktop error-path acceptance (real Swing, five scenarios)
REM  long / disk-full / readonly / file-in-use / locked-file
REM ============================================================
cd /d "%~dp0"

REM ---------- JDK 17 ----------
set "JDK17=%USERPROFILE%\.jdks\corretto-17"
if exist "%JDK17%\bin\javac.exe" set "JAVA_HOME=%JDK17%"
if not defined JAVA_HOME goto :no_jdk
if not exist "%JAVA_HOME%\bin\javac.exe" goto :no_jdk
if not exist "%JAVA_HOME%\bin\java.exe" goto :no_jdk

REM ---------- Maven ----------
set "MVN=%MAVEN_HOME%\bin\mvn.cmd"
if not exist "%MVN%" set "MVN="
if not defined MVN if exist "%USERPROFILE%\.trae-cn\tools\maven\latest\bin\mvn.cmd" set "MVN=%USERPROFILE%\.trae-cn\tools\maven\latest\bin\mvn.cmd"
if not defined MVN for /f "delims=" %%i in ('where mvn 2^>nul') do set "MVN=%%i"
if not defined MVN goto :no_maven

where icacls >nul 2>nul
if errorlevel 1 goto :no_icacls

echo JDK:   %JAVA_HOME%
echo Maven: %MVN%
echo.

REM ---------- version ----------
set "APP_VERSION="
for /f "usebackq delims=" %%v in (`powershell -NoProfile -Command "[regex]::Match((Get-Content -Raw 'pom.xml'), '<revision>([^<]+)<').Groups[1].Value"`) do set "APP_VERSION=%%v"
if not defined APP_VERSION goto :no_version
echo Version: %APP_VERSION%
echo.

set "TARGET=target\e2e-error-paths"

echo [1/3] Building fat jar...
REM Do not "clean": it would delete target\portable and the release ZIP.
call "%MVN%" -B package -DskipTests
if errorlevel 1 goto :build_failed

set "FATJAR=target\asymmetric-encryption-tool-%APP_VERSION%-jar-with-dependencies.jar"
if not exist "%FATJAR%" goto :no_fatjar

if exist "%TARGET%" rmdir /s /q "%TARGET%"
mkdir "%TARGET%\classes" 2>nul
if not exist "%TARGET%\classes" goto :prepare_failed

echo [2/3] Compiling Swing driver...
"%JAVA_HOME%\bin\javac.exe" -encoding UTF-8 -cp "%FATJAR%" -d "%TARGET%\classes" "tools\e2e\ErrorPathDriver.java"
if errorlevel 1 goto :compile_failed

set "CP=%FATJAR%;%TARGET%\classes"
set "FAILED="

echo [3/3] Running real Swing scenarios...
call :run_case long "%TARGET%\long" ""
call :run_case disk-full "%TARGET%\disk-full" "-Daegix.test.failWrite=disk-full"
call :run_case file-in-use "%TARGET%\file-in-use" "-Daegix.test.failWrite=file-in-use"
call :run_case locked-file "%TARGET%\locked-file" ""

REM ---------- readonly output directory ----------
set "ROOT=%TARGET%\readonly"
set "RO=%ROOT%\output"
mkdir "%ROOT%" 2>nul
mkdir "%RO%" 2>nul
if not exist "%RO%" goto :prepare_failed
for /f "delims=" %%u in ('whoami') do set "CURRENT_USER=%%u"
if not defined CURRENT_USER goto :no_user

echo.
echo ==== readonly ====
icacls "%RO%" /deny "%CURRENT_USER%:(W)" >nul
if errorlevel 1 goto :acl_failed
"%JAVA_HOME%\bin\java.exe" -cp "%CP%" ErrorPathDriver readonly "%ROOT%"
if errorlevel 1 set "FAILED=1"
icacls "%RO%" /remove:d "%CURRENT_USER%" >nul

if defined FAILED goto :failed
echo.
echo ALL ERROR-PATH SCENARIOS PASSED
exit /b 0

:run_case
set "MODE=%~1"
set "HOME_DIR=%~2"
set "EXTRA=%~3"
echo.
echo ==== %MODE% ====
"%JAVA_HOME%\bin\java.exe" %EXTRA% -cp "%CP%" ErrorPathDriver "%MODE%" "%HOME_DIR%"
if errorlevel 1 set "FAILED=1"
exit /b 0

:no_jdk
echo ERROR: JDK 17 with javac.exe not found.
echo   Expected %JDK17% or a JAVA_HOME pointing at JDK 17.
exit /b 1

:no_maven
echo ERROR: Maven not found.
echo   Install Maven, or set MAVEN_HOME, or add mvn.cmd to PATH.
exit /b 1

:no_icacls
echo ERROR: icacls not found; Windows is required for the readonly scenario.
exit /b 1

:no_version
echo ERROR: Cannot read revision from pom.xml.
exit /b 1

:prepare_failed
echo ERROR: Cannot create the e2e work directory.
exit /b 1

:build_failed
echo ERROR: Maven build failed.
exit /b 1

:no_fatjar
echo ERROR: Fat jar not found: %FATJAR%
exit /b 1

:compile_failed
echo ERROR: Cannot compile tools\e2e\ErrorPathDriver.java.
exit /b 1

:no_user
echo ERROR: whoami did not return the current user.
exit /b 1

:acl_failed
echo ERROR: Cannot apply the deny-write ACL to the test output directory.
exit /b 1

:failed
echo.
echo ERROR: One or more error-path scenarios failed.
exit /b 1
