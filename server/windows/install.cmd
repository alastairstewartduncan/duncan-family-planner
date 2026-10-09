@echo off
rem Duncan Family Planner - double-click (or right-click > Run as administrator) to install or update.
rem Runs install.ps1 without changing the PC's PowerShell script policy.

net session >nul 2>&1
if %errorlevel% neq 0 (
  echo Asking for administrator rights...
  powershell -NoProfile -Command "Start-Process -FilePath '%~f0' -Verb RunAs"
  exit /b
)

powershell -NoProfile -ExecutionPolicy Bypass -Command "Get-ChildItem -LiteralPath '%~dp0..' -Recurse -File -ErrorAction SilentlyContinue | Unblock-File -ErrorAction SilentlyContinue; & '%~dp0install.ps1'"
echo.
pause
