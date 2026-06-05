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

function Wait-RabbitMqHealthy {
    $container = "echomind-rabbitmq"
    for ($i = 0; $i -lt 40; $i++) {
        $status = docker inspect -f "{{if .State.Health}}{{.State.Health.Status}}{{else}}{{.State.Status}}{{end}}" $container 2>$null
        if ($LASTEXITCODE -eq 0 -and ($status -eq "healthy" -or $status -eq "running")) {
            return
        }
        Start-Sleep -Seconds 3
    }
    throw "RabbitMQ did not become healthy in time"
}

function Remove-RabbitQueueIfExists {
    param([string]$QueueName)

    $output = cmd /c "docker exec echomind-rabbitmq rabbitmqctl delete_queue -p / $QueueName 2>&1"
    $exitCode = $LASTEXITCODE
    if ($exitCode -eq 0) {
        Write-Host "Deleted RabbitMQ queue: $QueueName"
        return
    }
    $text = $output -join "`n"
    if ($text -match "not_found" -or $text -match "no queue" -or $text -match "Queue not found") {
        Write-Host "RabbitMQ queue not present: $QueueName"
        $global:LASTEXITCODE = 0
        return
    }
    throw "Failed to delete RabbitMQ queue '$QueueName' with exit code ${exitCode}: $text"
}

function Reset-ReliableRabbitQueues {
    $memoryQueueBase = if ($env:ECHOMIND_MEMORY_PERSIST_QUEUE_NAME) { $env:ECHOMIND_MEMORY_PERSIST_QUEUE_NAME } else { "echomind.chat-memory.persist.requests" }
    $memoryShardCount = 8
    if ($env:ECHOMIND_MEMORY_PERSIST_SHARDS) {
        $parsed = 0
        if ([int]::TryParse($env:ECHOMIND_MEMORY_PERSIST_SHARDS, [ref]$parsed) -and $parsed -gt 0) {
            $memoryShardCount = $parsed
        }
    }
    $userMemoryQueue = if ($env:USER_MEMORY_QUEUE_NAME) { $env:USER_MEMORY_QUEUE_NAME } else { "echomind.user-memory.requests" }

    $queues = New-Object System.Collections.Generic.List[string]
    $queues.Add("echomind.chat.requests")
    for ($i = 0; $i -lt $memoryShardCount; $i++) {
        $queues.Add("$memoryQueueBase.shard.$i")
    }
    $queues.Add($userMemoryQueue)
    $queues.Add("echomind.chat.requests.dlq")
    $queues.Add("echomind.chat-memory.persist.requests.dlq")
    $queues.Add("echomind.user-memory.requests.dlq")

    foreach ($queue in $queues) {
        Remove-RabbitQueueIfExists $queue
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

Invoke-Step "Build open-websearch runtime image" {
    docker build -f docker/open-websearch/Dockerfile -t ai-agent-open-websearch:latest .
}

Invoke-Step "Build user memory runtime image" {
    docker build -f Dockerfile.user-memory -t ai-agent-user-memory:latest .
}

Invoke-Step "Build client frontend dist" {
    Push-Location .\echomind-web
    try {
        npm.cmd run build
    } finally {
        Pop-Location
    }
}

Invoke-Step "Build client frontend runtime image" {
    docker build -f .\echomind-web\Dockerfile.runtime -t ai-agent-frontend:latest .\echomind-web
}

Invoke-Step "Build admin frontend dist" {
    Push-Location .\echomind-web
    try {
        npm.cmd run build:admin
    } finally {
        Pop-Location
    }
}

Invoke-Step "Build admin frontend runtime image" {
    docker build -f .\echomind-web\Dockerfile.admin-runtime -t ai-agent-admin-frontend:latest .\echomind-web
}

Write-Host "==> Apply MySQL migrations"
& "$PSScriptRoot\apply-mysql-migrations.ps1" -StartDatabase

Invoke-Step "Reset RabbitMQ reliable queues" {
    docker compose up -d rabbitmq
    Wait-RabbitMqHealthy
    docker compose stop backend user-memory
    Reset-ReliableRabbitQueues
}

Invoke-Step "Restart runtime containers" {
    docker compose up -d --remove-orphans open-websearch backend user-memory frontend admin-frontend otel-collector jaeger
}
