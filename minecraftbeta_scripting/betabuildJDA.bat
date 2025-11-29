@echo off
setlocal

REM ============================================================================
REM  Beta 1.7.3 Plugin Builder (drag & drop, with JDA support)
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
set "TARGET_JAVA=%~nx1"
set "TARGET_BASE=%~n1"

REM Paths
set "BUKKIT_JAR=%WORK_DIR%server.jar"
REM JDA 5 withDependencies jar (download and place next to this .bat)
set "JDA_JAR=%WORK_DIR%JDA-5.6.1-withDependencies.jar"
set "OUT_DIR=%WORK_DIR%build"
set "JAR_NAME=%TARGET_BASE%"
set "YAML_FILE=%TARGET_BASE%.yaml"

echo Working directory: "%WORK_DIR%"
echo Target plugin base: "%TARGET_BASE%"

REM Clean old build dir
if exist "%OUT_DIR%" rd /s /q "%OUT_DIR%"
mkdir "%OUT_DIR%"

REM Sanity check: metadata yaml
if not exist "%YAML_FILE%" (
    echo [ERROR] Metadata YAML "%YAML_FILE%" not found in "%WORK_DIR%".
    goto :finish
)

REM Sanity check: JDA jar
if not exist "%JDA_JAR%" (
    echo [ERROR] JDA jar not found at "%JDA_JAR%".
    echo Download JDA-5.6.1-withDependencies.jar and place it here.
    goto :finish
)

REM Java sources to compile: ONLY the plugin you dropped
echo Java sources to compile:  "%WORK_DIR%%TARGET_JAVA%"

echo.
echo Using Bukkit JAR: "%BUKKIT_JAR%"
echo Using JDA JAR:    "%JDA_JAR%"
echo Using metadata file: "%YAML_FILE%"
echo Output JAR name will be: "%JAR_NAME%.jar"
echo.
echo Compiling Java sources...
echo (This may take a moment)
echo.

REM Compile just this one plugin main class, with Bukkit + JDA on the classpath
javac -source 8 -target 8 -cp "%BUKKIT_JAR%;%JDA_JAR%" -d "%OUT_DIR%" "%TARGET_JAVA%"
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
