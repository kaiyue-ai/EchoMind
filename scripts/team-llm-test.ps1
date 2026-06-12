[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
$ErrorActionPreference = "Stop"

$BaseUrl = "http://localhost:8080/api"
$PollInterval = 5
$MaxWaitMinutes = 15

$TestCases = @(
    @{ Name = "simple-task-1"; Task = "计算 2024 年是否为闰年，并说明判断依据" },
    @{ Name = "simple-task-2"; Task = "总结 Spring Boot 3 的三个核心新特性" },
    @{ Name = "complex-task-1"; Task = "设计一个在线书店系统的技术方案，包括用户注册登录、图书搜索、购物车和订单支付四个模块，给出每个模块的关键技术选型和接口设计" },
    @{ Name = "complex-task-2"; Task = "分析 Redis 和 Memcached 的区别，从数据结构、持久化、集群、适用场景四个维度对比，并给出选型建议" },
    @{ Name = "complex-task-3"; Task = "设计一个短链接生成系统的架构，要求支持高并发写入、快速重定向、自定义短链和访问统计" }
)

function Write-Status($msg) {
    Write-Host "[$(Get-Date -Format 'HH:mm:ss')] $msg"
}

function Wait-RunComplete($teamId, $runId, $maxWaitMin) {
    $deadline = (Get-Date).AddMinutes($maxWaitMin)
    while ((Get-Date) -lt $deadline) {
        try {
            $run = Invoke-RestMethod "$BaseUrl/teams/$teamId/runs/$runId" -TimeoutSec 10
            $status = $run.status
            if ($status -eq "COMPLETED") { return @{ Status = "COMPLETED"; Run = $run } }
            if ($status -eq "FAILED") { return @{ Status = "FAILED"; Run = $run } }
        } catch {
            Write-Status "  WARN: Poll error: $_"
        }
        Start-Sleep -Seconds $PollInterval
    }
    return @{ Status = "TIMEOUT"; Run = $null }
}

function Get-AvailableAgents {
    $agents = Invoke-RestMethod "$BaseUrl/agents" -TimeoutSec 10
    return $agents | Where-Object { $_.agentId -ne "mock" }
}

function Build-TeamSpec($agents) {
    $planner = $agents[0]
    $executor = $agents[0]
    $reviewer = $agents[0]
    if ($agents.Count -ge 2) { $executor = $agents[1] }
    if ($agents.Count -ge 3) { $reviewer = $agents[2] }

    return @{
        name = "Test-Team"
        members = @(
            @{ agentId = $planner.agentId; role = "PLANNER"; capabilityTags = @("planning"); sortOrder = 10 }
            @{ agentId = $executor.agentId; role = "EXECUTOR"; capabilityTags = @("general"); sortOrder = 20 }
            @{ agentId = $reviewer.agentId; role = "REVIEWER"; capabilityTags = @("review"); sortOrder = 30 }
        )
    }
}

$agents = Get-AvailableAgents
if ($agents.Count -eq 0) { throw "No agents available" }
Write-Status "Available agents: $($agents.agentId -join ', ')"

$teamSpec = Build-TeamSpec $agents
$teamBody = $teamSpec | ConvertTo-Json -Depth 5
Write-Status "Creating team..."
$team = Invoke-RestMethod -Method Post "$BaseUrl/teams" -ContentType "application/json" -Body $teamBody -TimeoutSec 10
$teamId = $team.teamId
Write-Status "Team created: $teamId"

$results = @()

foreach ($tc in $TestCases) {
    Write-Status ""
    Write-Status "===== Test: $($tc.Name) ====="
    Write-Status "Task: $($tc.Task)"

    $createBody = @{ task = $tc.Task } | ConvertTo-Json
    try {
        $run = Invoke-RestMethod -Method Post "$BaseUrl/teams/$teamId/runs" `
            -ContentType "application/json" -Body $createBody -TimeoutSec 30
        $runId = $run.runId
        Write-Status "Run created: $runId"
    } catch {
        Write-Status "ERROR: Failed to create run: $_"
        $results += @{ Name = $tc.Name; Status = "CREATE_FAILED"; Steps = 0; Events = 0; Duration = "N/A"; Error = $_.ToString() }
        continue
    }

    $sw = [System.Diagnostics.Stopwatch]::StartNew()
    $result = Wait-RunComplete $teamId $runId $MaxWaitMinutes
    $sw.Stop()
    $duration = [math]::Round($sw.Elapsed.TotalSeconds, 1)

    $stepCount = 0
    $eventCount = 0
    $finalOutput = ""
    $errorMsg = ""
    if ($result.Run) {
        $stepCount = $result.Run.steps.Count
        $eventCount = $result.Run.events.Count
        $finalOutput = if ($result.Run.finalOutput) { $result.Run.finalOutput.Substring(0, [math]::Min(200, $result.Run.finalOutput.Length)) } else { "" }
        if ($result.Status -eq "FAILED") {
            $errorMsg = $finalOutput
        }
    }

    Write-Status "Result: $($result.Status) | Steps: $stepCount | Events: $eventCount | Duration: ${duration}s"
    if ($finalOutput -and $result.Status -eq "COMPLETED") {
        Write-Status "Output preview: $($finalOutput.Substring(0, [math]::Min(150, $finalOutput.Length)))..."
    }
    if ($errorMsg) {
        Write-Status "Error: $($errorMsg.Substring(0, [math]::Min(200, $errorMsg.Length)))"
    }

    $results += @{
        Name = $tc.Name
        Status = $result.Status
        Steps = $stepCount
        Events = $eventCount
        Duration = $duration
        Error = $errorMsg
    }
}

Write-Status ""
Write-Status "========================================="
Write-Status "           TEST RESULTS SUMMARY          "
Write-Status "========================================="

$completed = ($results | Where-Object { $_.Status -eq "COMPLETED" }).Count
$failed = ($results | Where-Object { $_.Status -eq "FAILED" }).Count
$timeout = ($results | Where-Object { $_.Status -eq "TIMEOUT" }).Count
$total = $results.Count

Write-Status "Total: $total | Completed: $completed | Failed: $failed | Timeout: $timeout"
Write-Status "Success Rate: $([math]::Round($completed / $total * 100, 1))%"
Write-Status ""

foreach ($r in $results) {
    $mark = switch ($r.Status) {
        "COMPLETED" { "[PASS]" }
        "FAILED" { "[FAIL]" }
        default { "[!!!!]" }
    }
    Write-Status "$mark $($r.Name) | $($r.Status) | Steps=$($r.Steps) | Events=$($r.Events) | $($r.Duration)s"
    if ($r.Error) {
        Write-Status "     Error: $($r.Error.Substring(0, [math]::Min(200, $r.Error.Length)))"
    }
}

Write-Status ""
Write-Status "Cleaning up team $teamId..."
try {
    Invoke-RestMethod -Method Delete "$BaseUrl/teams/$teamId" -TimeoutSec 10
    Write-Status "Team deleted."
} catch {
    Write-Status "WARN: Failed to delete team: $_"
}
