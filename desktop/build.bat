@echo off
chcp 65001 >nul

echo ========================================
echo  Build ^& Package (Maven)
echo ========================================
echo.

cd /d "%~dp0"

set "MVN=%MAVEN_HOME%\bin\mvn.cmd"
if not exist "%MVN%" set "MVN="
REM This machine keeps Maven under .trae-cn but not on PATH (see docs/agent/environment.md).
if not defined MVN if exist "%USERPROFILE%\.trae-cn\tools\maven\latest\bin\mvn.cmd" set "MVN=%USERPROFILE%\.trae-cn\tools\maven\latest\bin\mvn.cmd"
if not defined MVN for /f "delims=" %%i in ('where mvn 2^>nul') do set "MVN=%%i"
rem Maven prints Chinese paths and Javac warnings with the JVM default charset;
rem force UTF-8 so chcp 65001 consoles and redirected logs stay readable.
set "MAVEN_OPTS=%MAVEN_OPTS% -Dfile.encoding=UTF-8"

if not defined MVN (
    echo ERROR: Maven not found.
    echo   Install Maven, or set MAVEN_HOME, or add mvn.cmd to PATH.
    echo   IntelliJ / Android Studio bundle one under their plugins\maven directory.
    if /i not "%~1"=="nopause" pause
    exit /b 1
)

REM pom.xml targets Java 17 and the portable package bundles a JDK 17 runtime.
REM Prefer the documented Corretto 17 when JAVA_HOME still points at JDK 8;
REM otherwise fail with a clear message instead of silently building on Java 8.
set "JDK17=%USERPROFILE%\.jdks\corretto-17"
if defined JAVA_HOME (
    for /f "tokens=2" %%v in ('"%JAVA_HOME%\bin\javac.exe" -version 2^>^&1') do set "JAVAC_VERSION=%%v"
)
if defined JAVAC_VERSION (
    echo %JAVAC_VERSION% | findstr /b /r "1\." >nul
    if not errorlevel 1 (
        if exist "%JDK17%\bin\javac.exe" (
            set "JAVA_HOME=%JDK17%"
            echo [INFO] JAVA_HOME was JDK 8; switched to %JDK17%
        ) else (
            echo ERROR: Desktop build needs JDK 17, but JAVA_HOME is JDK 8.
            echo   Set JAVA_HOME to a JDK 17 installation, then rerun build.bat.
            if /i not "%~1"=="nopause" pause
            exit /b 1
        )
    )
)
if not defined JAVA_HOME if exist "%JDK17%\bin\javac.exe" set "JAVA_HOME=%JDK17%"

REM Monorepo note: kernel sources live in ..\shared\crypto-core, Gradle build in ..\android.
if exist "..\android\gradlew.bat" (
    echo Publishing shared crypto-core to Maven Local...
    pushd "..\android"
    call ".\gradlew.bat" -Dorg.gradle.jvmargs="-Xmx2048m -Dfile.encoding=UTF-8" :crypto-core:publishToMavenLocal
    if errorlevel 1 (
        popd
        echo ERROR: Failed to publish shared crypto-core.
        if /i not "%~1"=="nopause" pause
        exit /b 1
    )
    popd
)

echo Maven: %MVN%
echo Running build...
echo.

REM Record the source commit and dirty flag into the jar manifest, so the About page
REM and dist/BUILD-INFO.txt can prove which tree produced this build.
set "GIT_COMMIT=unknown"
set "GIT_DIRTY=unknown"
set "GIT_COMMIT_FILE=%TEMP%\aegix-git-commit-%RANDOM%.txt"
git -C "%CD%" rev-parse --short=9 HEAD > "%GIT_COMMIT_FILE%" 2>nul
if exist "%GIT_COMMIT_FILE%" (
    set /p GIT_COMMIT=<"%GIT_COMMIT_FILE%"
    del "%GIT_COMMIT_FILE%" >nul 2>nul
)
if not "%GIT_COMMIT%"=="unknown" (
    set "GIT_STATUS_FILE=%TEMP%\aegix-git-status-%RANDOM%.txt"
    git -C "%CD%" status --porcelain --untracked-files=no > "%GIT_STATUS_FILE%" 2>nul
    set "GIT_DIRTY=false"
    for %%A in ("%GIT_STATUS_FILE%") do if %%~zA GTR 0 set "GIT_DIRTY=true"
    del "%GIT_STATUS_FILE%" >nul 2>nul
)
echo [INFO] Source commit: %GIT_COMMIT% (dirty=%GIT_DIRTY%)
if "%GIT_DIRTY%"=="true" echo [WARN] Uncommitted tracked changes detected; this build is NOT reproducible.
call "%MVN%" clean package -DskipTests "-Dgit.commit=%GIT_COMMIT%" "-Dgit.dirty=%GIT_DIRTY%"
if errorlevel 1 (
    echo.
    echo BUILD FAILED
    if /i not "%~1"=="nopause" pause
    exit /b 1
)

echo.
echo BUILD SUCCESS
if exist "target\*-jar-with-dependencies.jar" (
    for %%f in ("target\*-jar-with-dependencies.jar") do (
        copy /y "%%f" app.jar >nul
        echo Fat JAR: app.jar
        echo Run: run.bat
    )
) else (
    echo Output: target\classes
)

echo.
if /i not "%~1"=="nopause" pause
exit /b 0
