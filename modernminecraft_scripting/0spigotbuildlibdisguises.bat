@echo off
setlocal

REM ============================================================================
REM  Spigot 1.21.10 Plugin Builder (drag & drop)
REM ============================================================================

set "IS_DRAGDROP="
if not "%~1"=="" set "IS_DRAGDROP=1"

if "%~1"=="" (
    echo Drag a plugin .java file onto this batch file to build it.
    goto :finish
)

set "WORK_DIR=%~dp0"
cd /d "%WORK_DIR%"

set "TARGET_JAVA=%~f1"
set "TARGET_BASE=%~n1"

set "SPIGOT_API_JAR=%WORK_DIR%spigot-api-1.21.10-R0.1-SNAPSHOT.jar"
set "PROTOCOLLIB_JAR=%WORK_DIR%ProtocolLib.jar"
set "GUAVA_JAR=%WORK_DIR%guava.jar"
set "LIBSDISGUISES_JAR=%WORK_DIR%LibsDisguises-11.0.13-Github.jar"
set "PACKETEVENTS_JAR=%WORK_DIR%packetevents-spigot-2.11.1.jar"

set "OUT_DIR=%WORK_DIR%build"
set "JAR_NAME=%TARGET_BASE%"
set "YAML_FILE=%WORK_DIR%%TARGET_BASE%.yaml"

echo Working directory: "%WORK_DIR%"
echo Target plugin base: "%TARGET_BASE%"

if exist "%OUT_DIR%" rd /s /q "%OUT_DIR%"
mkdir "%OUT_DIR%"

if not exist "%SPIGOT_API_JAR%" (
    echo [ERROR] Spigot API JAR not found:
    echo        "%SPIGOT_API_JAR%"
    goto :finish
)

if not exist "%PROTOCOLLIB_JAR%" (
    echo [ERROR] ProtocolLib JAR not found:
    echo        "%PROTOCOLLIB_JAR%"
    goto :finish
)

if not exist "%GUAVA_JAR%" (
    echo [ERROR] Guava JAR not found:
    echo        "%GUAVA_JAR%"
    goto :finish
)

if not exist "%LIBSDISGUISES_JAR%" (
    echo [ERROR] LibsDisguises JAR not found:
    echo        "%LIBSDISGUISES_JAR%"
    goto :finish
)

if not exist "%PACKETEVENTS_JAR%" (
    echo [ERROR] PacketEvents Spigot JAR not found:
    echo        "%PACKETEVENTS_JAR%"
    echo Put packetevents-spigot-2.11.1.jar next to this .bat and try again.
    goto :finish
)

if not exist "%YAML_FILE%" (
    echo [ERROR] Metadata YAML "%TARGET_BASE%.yaml" not found in "%WORK_DIR%".
    goto :finish
)

echo Java sources to compile:  "%TARGET_JAVA%"
echo.
echo Using Spigot API JAR: "%SPIGOT_API_JAR%"
echo Using ProtocolLib JAR: "%PROTOCOLLIB_JAR%"
echo Using Guava JAR: "%GUAVA_JAR%"
echo Using LibsDisguises JAR: "%LIBSDISGUISES_JAR%"
echo Using PacketEvents JAR: "%PACKETEVENTS_JAR%"
echo Using metadata file: "%YAML_FILE%"
echo Output JAR name will be: "%JAR_NAME%.jar"
echo.
echo Compiling Java sources...
echo.

echo javac -classpath ".;%SPIGOT_API_JAR%;%PROTOCOLLIB_JAR%;%GUAVA_JAR%;%LIBSDISGUISES_JAR%;%PACKETEVENTS_JAR%" -d "%OUT_DIR%" "%TARGET_JAVA%"
javac -classpath ".;%SPIGOT_API_JAR%;%PROTOCOLLIB_JAR%;%GUAVA_JAR%;%LIBSDISGUISES_JAR%;%PACKETEVENTS_JAR%" -d "%OUT_DIR%" "%TARGET_JAVA%"

if errorlevel 1 (
    echo.
    echo [ERROR] Compilation failed. See messages above.
    goto :finish
)

copy /Y "%YAML_FILE%" "%OUT_DIR%\plugin.yml" >nul

pushd "%OUT_DIR%"
jar cf "%WORK_DIR%%JAR_NAME%.jar" .
popd

echo.
echo Build complete: "%WORK_DIR%%JAR_NAME%.jar"
echo.

:finish
echo.
echo Build script finished.
echo.

if defined IS_DRAGDROP (
    cmd /k
)

endlocal
