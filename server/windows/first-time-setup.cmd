@echo off
rem Duncan Family Planner - first-time setup of the home server on this PC.
rem Double-click this file. It installs the server, asks you for the family PIN
rem and sets up the family. Safe to run again.

net session >nul 2>&1
if %errorlevel% neq 0 (
  echo Asking for administrator rights...
  powershell -NoProfile -Command "Start-Process -FilePath '%~f0' -Verb RunAs"
  exit /b
)

powershell -NoProfile -ExecutionPolicy Bypass -Command "Get-ChildItem -LiteralPath '%~dp0..' -Recurse -File -ErrorAction SilentlyContinue | Unblock-File -ErrorAction SilentlyContinue; & '%~dp0first-time-setup.ps1'"
echo.
pause
