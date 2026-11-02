@echo off
rem Starts the gateway. Double-click, or run from a terminal.
cd /d "%~dp0\.."
if not exist ".venv\Scripts\local-ai-gateway.exe" (
  echo Run scripts\setup.ps1 first.
  pause
  exit /b 1
)
".venv\Scripts\local-ai-gateway.exe" --config config.toml %*
