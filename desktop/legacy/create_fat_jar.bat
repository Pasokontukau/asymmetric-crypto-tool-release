@echo off
echo [LEGACY] Manual fat-jar flow. Prefer build.bat (Maven) unless reproducing an old build.
echo Creating fat jar for AsymmetricEncryptionTool...

set JAR_NAME=app.jar
set BIN_DIR=bin
set LIB_DIR=lib
set MANIFEST=MANIFEST.MF
set TEMP_DIR=temp_fat_jar
set JDK_BIN=%JAVA_HOME%\bin

if exist "%JAR_NAME%" del "%JAR_NAME%"
if exist "%TEMP_DIR%" rmdir /s /q "%TEMP_DIR%"
mkdir "%TEMP_DIR%"

echo Copying classes from bin directory...
xcopy /s /e /i /y "%BIN_DIR%" "%TEMP_DIR%" >nul

echo Extracting flatlaf classes...
"%JDK_BIN%\jar.exe" -xf "%LIB_DIR%\flatlaf-3.5.2.jar" -C "%TEMP_DIR%"

if exist "%LIB_DIR%\gson*.jar" (
    echo Extracting gson classes...
    for %%f in ("%LIB_DIR%\gson*.jar") do (
        "%JDK_BIN%\jar.exe" -xf "%%f" -C "%TEMP_DIR%"
    )
)

if exist "%LIB_DIR%\jna*.jar" (
    echo Extracting jna classes...
    for %%f in ("%LIB_DIR%\jna*.jar") do (
        "%JDK_BIN%\jar.exe" -xf "%%f" -C "%TEMP_DIR%"
    )
)

echo Creating fat jar...
"%JDK_BIN%\jar.exe" -cvfm "%JAR_NAME%" "%MANIFEST%" -C "%TEMP_DIR%" .

if exist "%TEMP_DIR%" rmdir /s /q "%TEMP_DIR%"

echo.
echo ========================================
echo    Fat jar created successfully: %JAR_NAME%
echo ========================================
echo.
pause
