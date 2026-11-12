@echo off
echo ========================================
echo   Environment Diagnostic Tool
echo ========================================
echo.
echo [1] Checking Java...
where java 2>nul
if errorlevel 1 (
    echo   [!] java.exe NOT found on PATH
) else (
    echo   [OK] Found java.exe
    java -version 2>&1
)
echo.

echo [2] Checking Java compiler...
where javac 2>nul
if errorlevel 1 (
    echo   [!] javac.exe NOT found on PATH
) else (
    echo   [OK] Found javac.exe
)
echo.

echo [3] Checking JAVA_HOME...
if "%JAVA_HOME%"=="" (
    echo   [!] JAVA_HOME is not set
) else (
    echo   [OK] JAVA_HOME=%JAVA_HOME%
)
echo.

echo [4] Checking Maven...
where mvn 2>nul
if errorlevel 1 (
    echo   [!] mvn NOT found on PATH
) else (
    echo   [OK] Found mvn.cmd
    mvn -version 2>&1
)
echo.

echo [5] Checking project pom.xml...
if exist "%~dp0pom.xml" (
    echo   [OK] pom.xml exists
) else (
    echo   [!] pom.xml not found at: %~dp0
)
echo.

echo [6] Checking maven_path.txt...
if exist "%~dp0maven_path.txt" (
    echo   [OK] maven_path.txt exists
    echo   Contents:
    type "%~dp0maven_path.txt"
) else (
    echo   [OK] maven_path.txt not required
)
echo.

echo ========================================
echo   Summary
echo ========================================
echo.

if errorlevel 1 (
    echo   [FAIL]  Some required tools are missing
    echo.
    echo   Fix:
    echo   - Install JDK (with JAVA_HOME set)
    echo   - Install Maven and add bin to PATH
    echo   - Or edit maven_path.txt with the Maven path
) else (
    echo   [PASS]  All tools are ready
    echo.
    echo   You can now run build.bat or create_fat_jar.bat
)
echo.
pause
