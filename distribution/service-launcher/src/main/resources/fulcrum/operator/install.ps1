$ErrorActionPreference = "Stop"

$docker = Get-Command docker -ErrorAction SilentlyContinue
if ($null -eq $docker) {
    Write-Error "Docker or a compatible container runtime is required before installing fulcrum."
    exit 78
}

$sourceDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$targetDir = if ($env:FULCRUM_INSTALL_DIR) { $env:FULCRUM_INSTALL_DIR } else { Join-Path $HOME ".fulcrum\bin" }
New-Item -ItemType Directory -Force -Path $targetDir | Out-Null
Copy-Item -Force (Join-Path $sourceDir "bin\fulcrum.ps1") (Join-Path $targetDir "fulcrum.ps1")
Write-Output "Installed fulcrum to $(Join-Path $targetDir "fulcrum.ps1")"
