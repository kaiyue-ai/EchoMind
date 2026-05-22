param(
    [switch]$SkipPackage
)

$ErrorActionPreference = "Stop"
$Root = Split-Path -Parent $PSScriptRoot
Set-Location $Root

. "$PSScriptRoot\load-compose-env.ps1"

function Invoke-Step {
    param(
        [string]$Name,
        [scriptblock]$Script
    )
    Write-Host "==> $Name"
    & $Script
    if ($LASTEXITCODE -ne 0) {
        throw "$Name failed with exit code $LASTEXITCODE"
    }
}

if (-not $SkipPackage) {
    Invoke-Step "Package backend and skills" {
        mvn.cmd -q clean package "-Dmaven.test.skip=true"
    }
}

Invoke-Step "Build backend runtime image" {
    docker build -f Dockerfile.runtime -t ai-agent-backend:latest .
}

Invoke-Step "Build client frontend runtime image" {
    docker build -f .\echomind-web\Dockerfile.runtime -t ai-agent-frontend:latest .\echomind-web
}

Invoke-Step "Build admin frontend runtime image" {
    docker build -f .\echomind-web\Dockerfile.admin-runtime -t ai-agent-admin-frontend:latest .\echomind-web
}

Write-Host "==> Apply MySQL migrations"
& "$PSScriptRoot\apply-mysql-migrations.ps1" -StartDatabase

Invoke-Step "Restart runtime containers" {
    docker compose up -d --remove-orphans backend frontend admin-frontend otel-collector jaeger
}
