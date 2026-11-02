# One-time setup on Windows. Run from the gateway folder:
#   powershell -ExecutionPolicy Bypass -File scripts\setup.ps1
$ErrorActionPreference = "Stop"
Set-Location (Split-Path -Parent $PSScriptRoot)

$py = Get-Command py -ErrorAction SilentlyContinue
if ($py) { $python = "py"; $pyArgs = @("-3") } else { $python = "python"; $pyArgs = @() }

& $python @pyArgs -c "import sys; sys.exit(0 if sys.version_info >= (3, 11) else 1)"
if ($LASTEXITCODE -ne 0) { throw "Python 3.11 or newer is required (https://www.python.org/downloads/)." }

if (-not (Test-Path ".venv")) { & $python @pyArgs -m venv .venv }
& .\.venv\Scripts\python.exe -m pip install --upgrade pip
& .\.venv\Scripts\python.exe -m pip install -e .

if (-not (Test-Path "config.toml")) {
    Copy-Item config.example.toml config.toml
    Write-Host "Created config.toml (edit it if LM Studio/ComfyUI use non-default ports)."
}

& .\.venv\Scripts\local-ai-gateway.exe --show-key | Out-Null
Write-Host ""
Write-Host "Your API key (keep it secret):" -ForegroundColor Cyan
& .\.venv\Scripts\local-ai-gateway.exe --show-key
Write-Host ""
Write-Host "Start the gateway with: scripts\start.bat" -ForegroundColor Green
