# Starts the shop-computer side of Till Recorder.
$ErrorActionPreference = "Stop"
Set-Location (Join-Path $PSScriptRoot "nvr")

if (-not (Test-Path .venv)) {
    python -m venv .venv
}

& .\.venv\Scripts\python.exe -m pip install -r requirements.txt

try {
    $rule = Get-NetFirewallRule -DisplayName "Till Recorder NVR" -ErrorAction SilentlyContinue
    if (-not $rule) {
        New-NetFirewallRule -DisplayName "Till Recorder NVR" -Direction Inbound -Protocol TCP -LocalPort 8787 -Action Allow -Profile Private | Out-Null
        Write-Output "Allowed port 8787 on private networks so the tablets can connect."
    }
} catch {
    Write-Output "Could not add a firewall rule. If a tablet cannot connect, allow TCP port 8787 on private networks."
}

& .\.venv\Scripts\python.exe server.py
