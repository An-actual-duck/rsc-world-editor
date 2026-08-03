@echo off
setlocal EnableExtensions
set "ROOT_DIR=%~dp0"
set "TARGET_ROOT=%~dp0.."
set "RUNTIME_ROOT=%~dp0builder-runtime"
set "WORKSPACE=%~dp0workspace"
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
if not exist "%RELEASE_IDENTITY%" goto missing_identity
findstr /C:"rsc-world-editor-v2" "%RELEASE_IDENTITY%" >nul
if errorlevel 1 goto wrong_identity
"%JAVA_EXE%" -version >nul 2>&1
if errorlevel 1 goto missing_java

if exist "%WORKSPACE%" if not exist "%PROJECT_REGISTRY%" goto historical_workspace

if not defined WORLD_BUILDER_PORT set "WORLD_BUILDER_PORT=43615"
if defined WORLD_BUILDER_CONFIGURATION_ROLE (
  "%JAVA_EXE%" -jar "%TOOLS_JAR%" launch-adaptive --installation-root "%ROOT_DIR%" --runtime-root "%RUNTIME_ROOT%" --target-root "%TARGET_ROOT%" --port "%WORLD_BUILDER_PORT%" --configuration-role "%WORLD_BUILDER_CONFIGURATION_ROLE%"
) else (
  "%JAVA_EXE%" -jar "%TOOLS_JAR%" launch-adaptive --installation-root "%ROOT_DIR%" --runtime-root "%RUNTIME_ROOT%" --target-root "%TARGET_ROOT%" --port "%WORLD_BUILDER_PORT%"
)
goto finished

:missing_tools
echo World Builder 2 could not start: the packaged launcher is missing.
goto failed

:missing_identity
echo World Builder 2 could not start: release identity is missing.
goto failed

:wrong_identity
echo World Builder 2 could not start: this package has the wrong product identity.
goto failed

:missing_java
echo World Builder could not start: Java 17 or newer was not found.
goto failed

:historical_workspace
echo World Builder 2 could not start: a historical World Builder 2 workspace is present.
echo It was preserved, but the adaptive launcher will not migrate or replace it.
echo Keep this installation intact for matching-version recovery, or use a separate adaptive installation.
goto failed

:finished
if errorlevel 1 goto failed
exit /b 0

:failed
pause
exit /b 1
