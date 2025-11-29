@echo off
setlocal ENABLEDELAYEDEXPANSION

REM ============================================================
REM  Generic Bukkit plugin builder for Beta 1.7.3-style plugins
REM  - Multiple plugins in the same directory (Treefeller, WolfProtect, etc.)
REM  - The file you drag onto the .bat decides which plugin is built:
REM      TreefellerPlugin.java   -> compile TreefellerPlugin.java
REM      TreefellerPlugin.yaml   -> compile TreefellerPlugin.java (if present)
REM  - Metadata:
REM      Prefer <BaseName>.yaml / <BaseName>.yml
REM      Then plugin.yml
REM      Then first *.yaml / *.yml in the folder
REM ============================================================

REM --- Where this script lives (used to find server.jar) ---
set "SCRIPT_DIR=%~dp0"

REM --- Detect plugin directory and base name ---
set "PLUGIN_DIR="
set "PLUGIN_BASENAME="

if "%~1"=="" (
    REM No argument: use directory of the .bat itself
    set "PLUGIN_DIR=%SCRIPT_DIR%"
) else (
    if exist "%~1\" (
        REM A folder was dropped
        set "PLUGIN_DIR=%~1"
        for %%I in ("%~1") do set "PLUGIN_BASENAME=%%~nI"
    ) else (
        REM A file was dropped; use its parent directory and file base name
        set "PLUGIN_DIR=%~dp1"
        for %%I in ("%~1") do set "PLUGIN_BASENAME=%%~nI"
    )
)

REM Normalize: remove trailing backslash if present
if "%PLUGIN_DIR:~-1%"=="\" set "PLUGIN_DIR=%PLUGIN_DIR:~0,-1%"

pushd "%PLUGIN_DIR%" >nul

echo Working directory: "%PLUGIN_DIR%"
if defined PLUGIN_BASENAME echo Target plugin base: "%PLUGIN_BASENAME%"
echo.

REM --- Locate Bukkit/CraftBukkit server jar ---
set "BUKKIT_JAR=%SCRIPT_DIR%server.jar"

if not exist "%BUKKIT_JAR%" (
    for %%F in ("%SCRIPT_DIR%craftbukkit*.jar") do (
        set "BUKKIT_JAR=%%F"
        goto :have_bukkit
    )
)

:have_bukkit
if not exist "%BUKKIT_JAR%" (
    echo [ERROR] Could not find server.jar or craftbukkit*.jar next to this .bat.
    echo Edit this script and set BUKKIT_JAR to your Bukkit jar path manually.
    echo.
    pause
    popd
    exit /b 1
)

echo Using Bukkit JAR: "%BUKKIT_JAR%"
echo.

REM --- Collect Java files for THIS plugin ---
set "JAVA_FILES="

if defined PLUGIN_BASENAME (
    set "TARGET_JAVA=%PLUGIN_DIR%\%PLUGIN_BASENAME%.java"
    if exist "%TARGET_JAVA%" (
        REM Compile only this plugin's main source file, like spcomp
        set "JAVA_FILES=\"%TARGET_JAVA%\""
    )
)

REM Fallback: if we didn't find a specific target, compile all .java (old behavior)
if not defined JAVA_FILES (
    for /R "%PLUGIN_DIR%" %%F in (*.java) do (
        set "JAVA_FILES=!JAVA_FILES! "%%F""
    )
)

if not defined JAVA_FILES (
    echo [ERROR] No .java files found under "%PLUGIN_DIR%".
    echo Make sure your source files are there.
    echo.
    pause
    popd
    exit /b 1
)

echo Java sources to compile: %JAVA_FILES%
echo.

REM --- Find metadata file: prefer matching base name, then generic ---
set "PLUGIN_META_SRC="

if defined PLUGIN_BASENAME (
    if exist "%PLUGIN_DIR%\%PLUGIN_BASENAME%.yaml" set "PLUGIN_META_SRC=%PLUGIN_DIR%\%PLUGIN_BASENAME%.yaml"
    if not defined PLUGIN_META_SRC if exist "%PLUGIN_DIR%\%PLUGIN_BASENAME%.yml" set "PLUGIN_META_SRC=%PLUGIN_DIR%\%PLUGIN_BASENAME%.yml"
)

REM If still not set, prefer plugin.yml
if not defined PLUGIN_META_SRC (
    if exist "plugin.yml" set "PLUGIN_META_SRC=%PLUGIN_DIR%\plugin.yml"
)

REM If there is still no metadata, pick first *.yaml then *.yml
if not defined PLUGIN_META_SRC (
    for %%F in ("%PLUGIN_DIR%\*.yaml") do (
        set "PLUGIN_META_SRC=%%F"
        goto :have_meta
    )
)

if not defined PLUGIN_META_SRC (
    for %%F in ("%PLUGIN_DIR%\*.yml") do (
        set "PLUGIN_META_SRC=%%F"
        goto :have_meta
    )
)

:have_meta

if not defined PLUGIN_META_SRC (
    echo [WARNING] No plugin.yml or *.yaml/*.yml found in "%PLUGIN_DIR%".
    echo The jar will build but will NOT be a valid Bukkit plugin without metadata.
    echo.
) else (
    echo Using metadata file: "%PLUGIN_META_SRC%"
    echo.
)

REM --- Determine JAR name ---
set "JAR_NAME="

REM Try to read name: from the metadata file
if defined PLUGIN_META_SRC (
    for /f "tokens=1* delims=:" %%A in ('findstr /b /i "name:" "%PLUGIN_META_SRC%"') do (
        set "JAR_NAME=%%B"
        goto :got_name
    )
)

:got_name
REM Trim leading spaces from JAR_NAME if set
if defined JAR_NAME (
    for /f "tokens=* delims= " %%A in ("%JAR_NAME%") do set "JAR_NAME=%%A"
)

REM Fallback: use plugin base name, then folder name
if not defined JAR_NAME (
    if defined PLUGIN_BASENAME (
        set "JAR_NAME=%PLUGIN_BASENAME%"
    ) else (
        for %%I in ("%PLUGIN_DIR%") do set "JAR_NAME=%%~nI"
    )
)

echo Output JAR name will be: "%JAR_NAME%.jar"
echo.

REM --- Prepare build output directory ---
set "OUT_DIR=%PLUGIN_DIR%\build"

if exist "%OUT_DIR%" (
    echo Cleaning old build directory...
    rd /s /q "%OUT_DIR%"
)
mkdir "%OUT_DIR%" >nul

REM --- Compile .java files ---
echo Compiling Java sources...
echo (This may take a moment)
echo.

javac -source 8 -target 8 -cp "%BUKKIT_JAR%" -d "%OUT_DIR%" %JAVA_FILES%
if errorlevel 1 (
    echo.
    echo [ERROR] Compilation failed. See messages above.
    echo.
    pause
    popd
    exit /b 1
)

echo.
echo Compilation successful.
echo.

REM --- Stage plugin.yml inside OUT_DIR ---
if defined PLUGIN_META_SRC (
    copy /Y "%PLUGIN_META_SRC%" "%OUT_DIR%\plugin.yml" >nul
)

REM --- Create the plugin JAR in the plugin directory ---
echo Creating JAR: "%JAR_NAME%.jar"

jar cf "%JAR_NAME%.jar" -C "%OUT_DIR%" .

if errorlevel 1 (
    echo.
    echo [ERROR] jar command failed.
    echo.
    pause
    popd
    exit /b 1
)

echo.
echo Done. Output: "%PLUGIN_DIR%\%JAR_NAME%.jar"
echo.

popd >nul
pause
endlocal
