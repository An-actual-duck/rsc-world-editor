@echo off
setlocal EnableExtensions
set "ROOT_DIR=%~dp0"
set "TOOLS_JAR=%~dp0builder-runtime\launcher\world-builder-tools.jar"
set "PROJECT_REGISTRY=%~dp0project-registry.json"
set "RELEASE_IDENTITY=%~dp0RELEASE-IDENTITY.json"

if defined WORLD_BUILDER_JAVA (
  set "JAVA_EXE=%WORLD_BUILDER_JAVA%"
) else if exist "%~dp0runtime\bin\java.exe" (
  set "JAVA_EXE=%~dp0runtime\bin\java.exe"
) else (
  set "JAVA_EXE=java"
)

if not exist "%TOOLS_JAR%" goto missing_tools
if not exist "%RELEASE_IDENTITY%" goto wrong_identity
findstr /C:"rsc-world-editor-v2" "%RELEASE_IDENTITY%" >nul
if errorlevel 1 goto wrong_identity
if not exist "%PROJECT_REGISTRY%" goto missing_project

"%JAVA_EXE%" -jar "%TOOLS_JAR%" recover-active-adaptive --installation-root "%ROOT_DIR%"
if errorlevel 1 goto failed
exit /b 0

:missing_tools
echo Map recovery could not start: the packaged launcher is missing.
goto failed

:wrong_identity
echo Map recovery could not start: this is not a World Builder 2 release.
goto failed

:missing_project
echo Map recovery could not start: no adaptive project registry was found.
goto failed

:failed
pause
exit /b 1
