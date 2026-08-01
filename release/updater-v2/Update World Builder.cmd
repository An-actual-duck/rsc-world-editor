@echo off
setlocal EnableExtensions
set "AUTOMATIC_ARG="
if /I "%~1"=="--automatic" set "AUTOMATIC_ARG=-Automatic"

powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0Update World Builder.ps1" %AUTOMATIC_ARG%
set "UPDATE_EXIT=%ERRORLEVEL%"
if not "%UPDATE_EXIT%"=="0" if not defined AUTOMATIC_ARG pause
exit /b %UPDATE_EXIT%
