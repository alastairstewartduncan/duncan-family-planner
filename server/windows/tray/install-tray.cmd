@echo off
rem Duncan Family Planner - install the tray app (the little icon by the clock).
rem Double-click this file. No administrator needed. Safe to run again.

powershell -NoProfile -ExecutionPolicy Bypass -Command "Get-ChildItem -LiteralPath '%~dp0' -File -ErrorAction SilentlyContinue | Unblock-File -ErrorAction SilentlyContinue; & '%~dp0install-tray.ps1'"
echo.
pause
