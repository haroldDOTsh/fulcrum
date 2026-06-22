$ErrorActionPreference = "Stop"

$docker = Get-Command docker -ErrorAction SilentlyContinue
if ($null -eq $docker) {
    Write-Error "Docker or a compatible container runtime is required to run fulcrum."
    exit 78
}

$scriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$operatorHome = Split-Path -Parent $scriptDir
$image = if ($env:FULCRUM_CLI_IMAGE) { $env:FULCRUM_CLI_IMAGE } else { "ghcr.io/harolddotsh/fulcrum-service-launcher:5.0.0-beta.1" }
$dockerSocket = if ($env:FULCRUM_DOCKER_SOCKET) { $env:FULCRUM_DOCKER_SOCKET } else { "/var/run/docker.sock" }
$dockerHost = if ($env:DOCKER_HOST) { $env:DOCKER_HOST } else { "unix:///var/run/docker.sock" }
$kubeDir = if ($env:FULCRUM_KUBE_DIR) { $env:FULCRUM_KUBE_DIR } else { Join-Path $HOME ".kube" }

$dockerArgs = @(
    "-i",
    "-v", "${PWD}:/workspace",
    "-w", "/workspace",
    "-v", "${operatorHome}:/opt/fulcrum/operator:ro",
    "-e", "FULCRUM_OPERATOR_HOME=/opt/fulcrum/operator",
    "-e", "FULCRUM_CASSANDRA_IMAGE",
    "-e", "FULCRUM_KAFKA_IMAGE",
    "-e", "FULCRUM_OBJECT_STORE_ACCESS_KEY",
    "-e", "FULCRUM_OBJECT_STORE_IMAGE",
    "-e", "FULCRUM_OBJECT_STORE_SECRET_KEY",
    "-e", "FULCRUM_PAPER_GAMESERVER_IMAGE",
    "-e", "FULCRUM_PAPER_SESSION_OWNER_TOKEN",
    "-e", "FULCRUM_POSTGRES_IMAGE",
    "-e", "FULCRUM_POSTGRES_PASSWORD",
    "-e", "FULCRUM_SERVICE_LAUNCHER_IMAGE",
    "-e", "FULCRUM_VALKEY_IMAGE",
    "-e", "FULCRUM_VELOCITY_PROXY_IMAGE",
    "-e", "DOCKER_HOST=$dockerHost"
)

if ($env:FULCRUM_DOCKER_SOCKET -or (Test-Path $dockerSocket)) {
    $dockerArgs += @("-v", "${dockerSocket}:/var/run/docker.sock")
}

if (Test-Path -Path $kubeDir -PathType Container) {
    $dockerArgs += @("-v", "${kubeDir}:/root/.kube:ro")
}

docker run --rm @dockerArgs $image @args
exit $LASTEXITCODE
