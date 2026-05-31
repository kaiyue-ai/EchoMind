[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
$ErrorActionPreference = "Stop"

$BaseUrl = "http://localhost:8080/api"
$PollInterval = 10
$MaxWaitMinutes = 20

$TestCases = @(
    @{ Name = "complex-1"; Task = "设计一个在线书店系统的技术方案，包括用户注册登录、图书搜索、购物车和订单支付四个模块，给出每个模块的关键技术选型和接口设计" },
    @{ Name = "complex-2"; Task = "分析 Redis 和 Memcached 的区别，从数据结构、持久化、集群、适用场景四个维度对比，并给出选型建议" },
    @{ Name = "complex-3"; Task = "设计一个短链接生成系统的架构，要求支持高并发写入、快速重定向、自定义短链和访问统计" }
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
            if ($status -eq "NEEDS_CLARIFICATION") {
                Write-Status "  Run needs clarification, auto-answering..."
                try {
                    $run = Invoke-RestMethod -Method Post "$BaseUrl/teams/$teamId/runs/$runId/resume" `
                        -ContentType "application/json" `
                        -Body (@{ clarificationAnswer = "请按照你的专业判断继续执行" } | ConvertTo-Json) `
                        -TimeoutSec 10
                    Write-Status "  Clarification answered, continuing..."
                    continue
                } catch {
                    Write-Status "  WARN: Resume failed: $_"
                }
                return @{ Status = "NEEDS_CLARIFICATION"; Run = $run }
            }
        } catch {
            Write-Status "  WARN: Poll error: $_"
        }
        Start-Sleep -Seconds $PollInterval
    }
    return @{ Status = "TIMEOUT"; Run = $null }
}

$agents = Invoke-RestMethod "$BaseUrl/agents" -TimeoutSec 10
$a = $agents[0]
$body = @{
    name = "LLM-Test-Complex"
    members = @(
        @{ agentId = $a.agentId; role = "PLANNER"; capabilityTags = @("planning"); sortOrder = 10 }
        @{ agentId = $a.agentId; role = "EXECUTOR"; capabilityTags = @("general"); sortOrder = 20 }
        @{ agentId = $a.agentId; role = "REVIEWER"; capabilityTags = @("review"); sortOrder = 30 }
    )
} | ConvertTo-Json -Depth 5
$team = Invoke-RestMethod -Method Post "$BaseUrl/teams" -ContentType "application/json" -Body $body -TimeoutSec 10
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
        $results += @{ Name = $tc.Name; Status = "CREATE_FAILED"; Steps = 0; Duration = "N/A"; Error = $_.ToString() }
        continue
    }

    $sw = [System.Diagnostics.Stopwatch]::StartNew()
    $result = Wait-RunComplete $teamId $runId $MaxWaitMinutes
    $sw.Stop()
    $duration = [math]::Round($sw.Elapsed.TotalSeconds, 1)

    $stepCount = 0
    $finalOutput = ""
    $errorMsg = ""
    if ($result.Run) {
        $stepCount = $result.Run.steps.Count
        $finalOutput = if ($result.Run.finalOutput) { $result.Run.finalOutput.Substring(0, [math]::Min(200, $result.Run.finalOutput.Length)) } else { "" }
        if ($result.Status -eq "FAILED") { $errorMsg = $finalOutput }
    }

    Write-Status "Result: $($result.Status) | Steps: $stepCount | Duration: ${duration}s"
    if ($finalOutput -and $result.Status -eq "COMPLETED") {
        Write-Status "Output: $($finalOutput.Substring(0, [math]::Min(150, $finalOutput.Length)))..."
    }
    if ($errorMsg) {
        Write-Status "Error: $($errorMsg.Substring(0, [math]::Min(200, $errorMsg.Length)))"
    }

    $results += @{
        Name = $tc.Name
        Status = $result.Status
        Steps = $stepCount
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
$clarified = ($results | Where-Object { $_.Status -eq "NEEDS_CLARIFICATION" }).Count
$total = $results.Count

Write-Status "Total: $total | Completed: $completed | Failed: $failed | Timeout: $timeout | Clarified: $clarified"
Write-Status "Success Rate: $([math]::Round($completed / $total * 100, 1))%"
Write-Status ""

foreach ($r in $results) {
    $mark = switch ($r.Status) {
        "COMPLETED" { "[PASS]" }
        "FAILED" { "[FAIL]" }
        default { "[!!!!]" }
    }
    Write-Status "$mark $($r.Name) | $($r.Status) | Steps=$($r.Steps) | $($r.Duration)s"
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
