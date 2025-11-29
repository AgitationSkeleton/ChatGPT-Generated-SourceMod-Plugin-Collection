@echo off
setlocal

REM ============================================================================
REM  Beta 1.7.3 Dynmap Plugin Builder (drag & drop)
REM  - Drop a single .java file onto this .bat
REM  - Assumes matching .yaml with the same base name
REM  - Produces <BaseName>.jar in the same folder
REM  - Includes dynmap.jar on the compile classpath
REM ============================================================================

REM No argument? Tell user what to do and bail.
if "%~1"=="" (
    echo Drag a Dynmap-based plugin .java file onto this batch file to build it.
    pause
    goto :EOF
)

REM Working directory = folder where this .bat lives
set "WORK_DIR=%~dp0"
cd /d "%WORK_DIR%"

REM Target plugin main file (what you dropped)
set "TARGET_JAVA=%~nx1"
set "TARGET_BASE=%~n1"

REM Paths
set "BUKKIT_JAR=%WORK_DIR%server.jar"
set "DYNDMAP_JAR=%WORK_DIR%dynmap.jar"
set "OUT_DIR=%WORK_DIR%build"
set "JAR_NAME=%TARGET_BASE%"
set "YAML_FILE=%TARGET_BASE%.yaml"

echo Working directory: "%WORK_DIR%"
echo Target plugin base: "%TARGET_BASE%"

REM Sanity check: server.jar
if not exist "%BUKKIT_JAR%" (
    echo [ERROR] Bukkit server JAR not found: "%BUKKIT_JAR%"
    pause
    goto :EOF
)

REM Sanity check: dynmap.jar
if not exist "%DYNDMAP_JAR%" (
    echo [ERROR] Dynmap JAR not found: "%DYNDMAP_JAR%"
    echo Place dynmap.jar in the same directory as this batch file.
    pause
    goto :EOF
)

REM Clean old build dir
if exist "%OUT_DIR%" rd /s /q "%OUT_DIR%"
mkdir "%OUT_DIR%"

REM Sanity check: metadata yaml
if not exist "%YAML_FILE%" (
    echo [ERROR] Metadata YAML "%YAML_FILE%" not found in "%WORK_DIR%".
    pause
    goto :EOF
)

REM Java sources to compile: ONLY the plugin you dropped
echo Java sources to compile:  "%WORK_DIR%%TARGET_JAVA%"
echo.

echo Using Bukkit JAR: "%BUKKIT_JAR%"
echo Using Dynmap JAR: "%DYNDMAP_JAR%"
echo Using metadata file: "%YAML_FILE%"
echo Output JAR name will be: "%JAR_NAME%.jar"
echo.
echo Compiling Java sources...
echo (This may take a moment)
echo.

REM Compile just this one plugin main class, with dynmap on the classpath
javac -source 8 -target 8 -cp "%BUKKIT_JAR%;%DYNDMAP_JAR%" -d "%OUT_DIR%" "%TARGET_JAVA%"
if errorlevel 1 (
    echo.
    echo [ERROR] Compilation failed. See messages above.
    pause
    goto :EOF
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

pause
endlocal
