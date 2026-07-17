@echo off
setlocal EnableExtensions
set "ROOT_DIR=%~dp0"
set "TARGET_ROOT=%~dp0.."
set "RUNTIME_ROOT=%~dp0builder-runtime"
set "WORKSPACE=%~dp0workspace"
set "TOOLS_JAR=%~dp0builder-runtime\launcher\world-builder-tools.jar"

if "%WORLD_BUILDER_SKIP_UPDATE%"=="1" goto after_update
if not exist "%~dp0Update World Builder.cmd" goto after_update
call "%~dp0Update World Builder.cmd" --automatic
if errorlevel 1 echo WARNING: The automatic update check failed; continuing with the installed version.
:after_update

if defined WORLD_BUILDER_JAVA (
  set "JAVA_EXE=%WORLD_BUILDER_JAVA%"
) else if exist "%~dp0runtime\bin\java.exe" (
  set "JAVA_EXE=%~dp0runtime\bin\java.exe"
) else (
  set "JAVA_EXE=java"
)

if not exist "%TOOLS_JAR%" goto missing_tools
"%JAVA_EXE%" -version >nul 2>&1
if errorlevel 1 goto missing_java

if exist "%WORKSPACE%\project-source.json" goto run_existing
if exist "%WORKSPACE%" goto incomplete_workspace

if not defined WORLD_BUILDER_PORT set "WORLD_BUILDER_PORT=43615"
"%JAVA_EXE%" -jar "%TOOLS_JAR%" launch --server-root "%TARGET_ROOT%" --runtime-root "%RUNTIME_ROOT%" --workspace "%WORKSPACE%" --port "%WORLD_BUILDER_PORT%" --config server/myworld.conf --runtime-config server/myworld.conf
goto finished

:run_existing
"%JAVA_EXE%" -jar "%TOOLS_JAR%" run --workspace "%WORKSPACE%"
goto finished

:missing_tools
echo World Builder could not start: the packaged launcher is missing.
goto failed

:missing_java
echo World Builder could not start: Java 17 or newer was not found.
goto failed

:incomplete_workspace
echo World Builder could not start: the workspace folder exists but is incomplete.
echo Preserve it and review its contents before retrying.
goto failed

:finished
if errorlevel 1 goto failed
exit /b 0

:failed
pause
exit /b 1
