@echo off
setlocal

REM ========================================================================
REM  Spigot 1.21.10 Plugin Builder (drag & drop) WITH JDA FAT-JAR SHADING
REM  - Drop a single .java file onto this .bat
REM  - Assumes matching .yaml with the same base name
REM  - Produces <BaseName>.jar in the same folder
REM  - Uses Spigot API JAR: spigot-api-1.21.10-R0.1-SNAPSHOT.jar
REM  - Uses JDA JAR: JDA-5.6.1-withDependencies.jar
REM  - "Shades" JDA by unpacking it into build/ before jar cf
REM ========================================================================

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
set "JDA_JAR=%WORK_DIR%JDA-5.6.1-withDependencies.jar"

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

if not exist "%JDA_JAR%" (
    echo [ERROR] JDA jar not found:
    echo        "%JDA_JAR%"
    echo Put JDA-5.6.1-withDependencies.jar next to this .bat
    goto :finish
)

if not exist "%YAML_FILE%" (
    echo [ERROR] Metadata YAML "%TARGET_BASE%.yaml" not found in "%WORK_DIR%".
    goto :finish
)

echo Java sources to compile:  "%TARGET_JAVA%"
echo.
echo Using Spigot API JAR: "%SPIGOT_API_JAR%"
echo Using JDA JAR:       "%JDA_JAR%"
echo Using metadata file: "%YAML_FILE%"
echo Output JAR name will be: "%JAR_NAME%.jar"
echo.
echo Compiling Java sources...
echo.

echo javac -classpath ".;%SPIGOT_API_JAR%;%JDA_JAR%" -d "%OUT_DIR%" "%TARGET_JAVA%"
javac -classpath ".;%SPIGOT_API_JAR%;%JDA_JAR%" -d "%OUT_DIR%" "%TARGET_JAVA%"

if errorlevel 1 (
    echo.
    echo [ERROR] Compilation failed.
    goto :finish
)

copy /Y "%YAML_FILE%" "%OUT_DIR%\plugin.yml" >nul

REM Shade JDA by unpacking it into build/
echo.
echo Unpacking JDA into build folder (fat jar)...
pushd "%OUT_DIR%"
jar xf "%JDA_JAR%"
REM Remove signature metadata that can sometimes cause warnings
if exist "META-INF\*.SF" del /q "META-INF\*.SF" >nul 2>nul
if exist "META-INF\*.DSA" del /q "META-INF\*.DSA" >nul 2>nul
if exist "META-INF\*.RSA" del /q "META-INF\*.RSA" >nul 2>nul
popd

echo.
echo Building plugin jar...
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
