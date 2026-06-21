$ErrorActionPreference = "Stop"

$docker = Get-Command docker -ErrorAction SilentlyContinue
if ($null -eq $docker) {
    Write-Error "Docker or a compatible container runtime is required to run fulcrum."
    exit 78
}

$scriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$operatorHome = Split-Path -Parent $scriptDir
$image = if ($env:FULCRUM_CLI_IMAGE) { $env:FULCRUM_CLI_IMAGE } else { "ghcr.io/harolddotsh/fulcrum-service-launcher:0.1.0-SNAPSHOT" }

docker run --rm -i `
    -v "${PWD}:/workspace" `
    -w /workspace `
    -v "${operatorHome}:/opt/fulcrum/operator:ro" `
    -e FULCRUM_OPERATOR_HOME=/opt/fulcrum/operator `
    $image @args
exit $LASTEXITCODE
