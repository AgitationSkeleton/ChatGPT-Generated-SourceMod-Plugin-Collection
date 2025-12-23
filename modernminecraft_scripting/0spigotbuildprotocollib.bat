@echo off
setlocal

REM ============================================================================
REM  Spigot 1.21.10 Plugin Builder (drag & drop)
REM  - Drop a single .java file onto this .bat
REM  - Assumes matching .yaml with the same base name
REM  - Produces <BaseName>.jar in the same folder
REM  - Uses Spigot API JAR: spigot-api-1.21.10-R0.1-SNAPSHOT.jar
REM  - Keeps window open after drag-drop for diagnosis (cmd /k)
REM ============================================================================

REM Remember if we got an argument (typical for drag & drop)
set "IS_DRAGDROP="
if not "%~1"=="" set "IS_DRAGDROP=1"

REM No argument? Tell user what to do and bail.
if "%~1"=="" (
    echo Drag a plugin .java file onto this batch file to build it.
    goto :finish
)

REM Working directory = folder where this .bat lives
set "WORK_DIR=%~dp0"
cd /d "%WORK_DIR%"

REM Target plugin main file (what you dropped)
set "TARGET_JAVA=%~f1"
set "TARGET_BASE=%~n1"

REM Paths
set "SPIGOT_API_JAR=%WORK_DIR%spigot-api-1.21.10-R0.1-SNAPSHOT.jar"
set "PROTOCOLLIB_JAR=%WORK_DIR%ProtocolLib.jar"
set "GUAVA_JAR=%WORK_DIR%guava.jar"
set "OUT_DIR=%WORK_DIR%build"
set "JAR_NAME=%TARGET_BASE%"
set "YAML_FILE=%WORK_DIR%%TARGET_BASE%.yaml"

echo Working directory: "%WORK_DIR%"
echo Target plugin base: "%TARGET_BASE%"

REM Clean old build dir
if exist "%OUT_DIR%" rd /s /q "%OUT_DIR%"
mkdir "%OUT_DIR%"

REM Sanity check: API jar
if not exist "%SPIGOT_API_JAR%" (
    echo [ERROR] Spigot API JAR not found:
    echo        "%SPIGOT_API_JAR%"
    echo Put the API jar next to this .bat and try again.
    goto :finish
)

REM Sanity check: ProtocolLib jar
if not exist "%PROTOCOLLIB_JAR%" (
    echo [ERROR] ProtocolLib JAR not found:
    echo        "%PROTOCOLLIB_JAR%"
    echo Put ProtocolLib.jar next to this .bat and try again.
    goto :finish
)

REM Sanity check: Guava jar
if not exist "%GUAVA_JAR%" (
    echo [ERROR] Guava JAR not found:
    echo        "%GUAVA_JAR%"
    echo Put guava.jar next to this .bat and try again.
    goto :finish
)

REM Sanity check: metadata yaml
if not exist "%YAML_FILE%" (
    echo [ERROR] Metadata YAML "%TARGET_BASE%.yaml" not found in "%WORK_DIR%".
    goto :finish
)

REM Java source to compile: ONLY the plugin you dropped
echo Java sources to compile:  "%TARGET_JAVA%"
echo.
echo Using Spigot API JAR: "%SPIGOT_API_JAR%"
echo Using ProtocolLib JAR: "%PROTOCOLLIB_JAR%"
echo Using Guava JAR: "%GUAVA_JAR%"
echo Using metadata file: "%YAML_FILE%"
echo Output JAR name will be: "%JAR_NAME%.jar"
echo.
echo Compiling Java sources...
echo (This may take a moment)
echo.

REM Compile
echo javac -classpath ".;%SPIGOT_API_JAR%;%PROTOCOLLIB_JAR%;%GUAVA_JAR%" -d "%OUT_DIR%" "%TARGET_JAVA%"
javac -classpath ".;%SPIGOT_API_JAR%;%PROTOCOLLIB_JAR%;%GUAVA_JAR%" -d "%OUT_DIR%" "%TARGET_JAVA%"

if errorlevel 1 (
    echo.
    echo [ERROR] Compilation failed. See messages above.
    goto :finish
)

REM Copy metadata to plugin.yml
copy /Y "%YAML_FILE%" "%OUT_DIR%\plugin.yml" >nul

REM Build the JAR
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

REM If launched via drag & drop (i.e., new cmd /c), drop to an interactive shell
if defined IS_DRAGDROP (
    cmd /k
)

endlocal
