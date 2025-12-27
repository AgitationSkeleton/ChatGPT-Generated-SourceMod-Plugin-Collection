@echo off
setlocal

REM ============================================================================
REM  Spigot 1.21.10 Plugin Builder (drag & drop)
REM  - Includes Citizens API (Citizens-2.0.41-b4026.jar)
REM  - Drop a single .java file onto this .bat
REM  - Assumes matching .yaml with the same base name
REM  - Produces <BaseName>.jar in the same folder
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
set "CITIZENS_JAR=%WORK_DIR%Citizens-2.0.41-b4026.jar"
set "OUT_DIR=%WORK_DIR%build"
set "JAR_NAME=%TARGET_BASE%"
set "YAML_FILE=%WORK_DIR%%TARGET_BASE%.yaml"

echo Working directory: "%WORK_DIR%"
echo Target plugin base: "%TARGET_BASE%"

REM Clean old build dir
if exist "%OUT_DIR%" rd /s /q "%OUT_DIR%"
mkdir "%OUT_DIR%"

REM Sanity check: Spigot API jar
if not exist "%SPIGOT_API_JAR%" (
    echo [ERROR] Spigot API JAR not found:
    echo        "%SPIGOT_API_JAR%"
    goto :finish
)

REM Sanity check: Citizens jar
if not exist "%CITIZENS_JAR%" (
    echo [ERROR] Citizens JAR not found:
    echo        "%CITIZENS_JAR%"
    echo Put Citizens-2.0.41-b4026.jar next to this .bat and try again.
    goto :finish
)

REM Sanity check: metadata yaml
if not exist "%YAML_FILE%" (
    echo [ERROR] Metadata YAML "%TARGET_BASE%.yaml" not found in "%WORK_DIR%".
    goto :finish
)

REM Java source to compile
echo Java sources to compile:  "%TARGET_JAVA%"
echo.
echo Using Spigot API JAR: "%SPIGOT_API_JAR%"
echo Using Citizens API JAR: "%CITIZENS_JAR%"
echo Using metadata file: "%YAML_FILE%"
echo Output JAR name will be: "%JAR_NAME%.jar"
echo.
echo Compiling Java sources...
echo (This may take a moment)
echo.

REM Compile
echo javac -classpath ".;%SPIGOT_API_JAR%;%CITIZENS_JAR%" -d "%OUT_DIR%" "%TARGET_JAVA%"
javac -classpath ".;%SPIGOT_API_JAR%;%CITIZENS_JAR%" -d "%OUT_DIR%" "%TARGET_JAVA%"

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

REM If launched via drag & drop, keep window open
if defined IS_DRAGDROP (
    cmd /k
)

endlocal
