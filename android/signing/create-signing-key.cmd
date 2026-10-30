@echo off
rem Duncan Family Planner - make the permanent signing key (once). Double-click this file.
rem No administrator needed. Safe to run again: it never replaces an existing key.

powershell -NoProfile -ExecutionPolicy Bypass -Command "Get-ChildItem -LiteralPath '%~dp0' -File -ErrorAction SilentlyContinue | Unblock-File -ErrorAction SilentlyContinue; & '%~dp0create-signing-key.ps1'"
echo.
pause
