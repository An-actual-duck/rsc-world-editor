@echo off
setlocal EnableExtensions
set "TARGET_ROOT=%~dp0.."
set "WORKSPACE=%~dp0workspace"
set "TOOLS_JAR=%~dp0builder-runtime\launcher\world-builder-tools.jar"

if defined WORLD_BUILDER_JAVA (
  set "JAVA_EXE=%WORLD_BUILDER_JAVA%"
) else if exist "%~dp0runtime\bin\java.exe" (
  set "JAVA_EXE=%~dp0runtime\bin\java.exe"
) else (
  set "JAVA_EXE=java"
)

if not exist "%TOOLS_JAR%" goto missing_tools
if not exist "%WORKSPACE%\project-source.json" goto missing_project

"%JAVA_EXE%" -jar "%TOOLS_JAR%" undo-latest-import --workspace "%WORKSPACE%" --target-root "%TARGET_ROOT%"
if errorlevel 1 goto failed
exit /b 0

:missing_tools
echo Map undo could not start: the packaged launcher is missing.
goto failed

:missing_project
echo Map undo could not start: no World Builder project was found.
goto failed

:failed
pause
exit /b 1
