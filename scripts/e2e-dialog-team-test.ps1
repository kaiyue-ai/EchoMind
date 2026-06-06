param(
    [string]$BaseUrl = "http://localhost:8080",
    [int]$ChatConcurrency = 6,
    [int]$TeamConcurrency = 2,
    [int]$TimeoutSeconds = 300,
    [string]$OutputDir = "tmp/e2e-dialog-team"
)

[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
$ErrorActionPreference = "Stop"
Add-Type -AssemblyName System.Net.Http

$ApiBase = "$BaseUrl/api"
$RunId = Get-Date -Format "yyyyMMdd-HHmmss"
$OutputPath = Join-Path $OutputDir $RunId
New-Item -ItemType Directory -Force -Path $OutputPath | Out-Null

function Write-Status([string]$Message) {
    Write-Host "[$(Get-Date -Format 'HH:mm:ss')] $Message"
}

function ConvertTo-JsonUtf8($Object, [int]$Depth = 12) {
    return $Object | ConvertTo-Json -Depth $Depth -Compress
}

function Invoke-Json($Method, $Url, $Body = $null, $Headers = @{}, [int]$TimeoutSec = 60) {
    $params = @{
        Method = $Method
        Uri = $Url
        Headers = $Headers
        TimeoutSec = $TimeoutSec
    }
    if ($null -ne $Body) {
        $params.ContentType = "application/json; charset=utf-8"
        $params.Body = ConvertTo-JsonUtf8 $Body 16
    }
    return Invoke-RestMethod @params
}

function Login-Admin {
    try {
        $login = Invoke-Json "Post" "$ApiBase/admin/auth/login" @{ username = "admin"; password = "admin123" }
        return @{ Authorization = "Bearer $($login.token)" }
    } catch {
        Write-Status "WARN: admin login failed, trace details will be limited: $($_.Exception.Message)"
        return @{}
    }
}

function Get-RabbitQueues {
    try {
        $json = docker exec echomind-rabbitmq rabbitmqctl list_queues name messages_ready messages_unacknowledged consumers --formatter json 2>$null
        return $json | ConvertFrom-Json
    } catch {
        return @()
    }
}

function Get-DockerStats {
    try {
        $lines = docker stats --no-stream --format "{{.Name}}|{{.CPUPerc}}|{{.MemUsage}}|{{.NetIO}}|{{.BlockIO}}" echomind-backend echomind-user-memory echomind-rabbitmq echomind-db echomind-cache echomind-milvus 2>$null
        return @($lines | ForEach-Object {
            $p = $_ -split "\|"
            [pscustomobject]@{
                name = $p[0]
                cpu = $p[1]
                memory = $p[2]
                netIO = $p[3]
                blockIO = $p[4]
            }
        })
    } catch {
        return @()
    }
}

function Get-TraceSummary($TraceId, $Headers) {
    if ([string]::IsNullOrWhiteSpace($TraceId) -or $Headers.Count -eq 0) {
        return $null
    }
    try {
        $trace = Invoke-RestMethod -Headers $Headers "$ApiBase/observability/traces/$TraceId" -TimeoutSec 20
        $spans = @($trace.trace.spans)
        $stageSpans = @($spans | Where-Object { $_.operationName -eq "echomind.pipeline.stage" } | ForEach-Object {
            [pscustomobject]@{
                name = $_.tags.'echomind.pipeline.stage'
                order = $_.tags.'echomind.pipeline.order'
                durationMs = [math]::Round($_.durationMicros / 1000.0, 2)
                hasError = $_.hasError
            }
        })
        $teamSpans = @($spans | Where-Object { $_.operationName -like "echomind.team.*" } | ForEach-Object {
            [pscustomobject]@{
                name = $_.operationName
                durationMs = [math]::Round($_.durationMicros / 1000.0, 2)
                hasError = $_.hasError
            }
        })
        $llmSpans = @($spans | Where-Object { $_.operationName -like "echomind.llm.*" } | ForEach-Object {
            [pscustomobject]@{
                name = $_.operationName
                provider = $_.tags.'echomind.provider_id'
                model = $_.tags.'echomind.model_name'
                durationMs = [math]::Round($_.durationMicros / 1000.0, 2)
                promptTokens = $_.tags.'echomind.prompt_tokens'
                completionTokens = $_.tags.'echomind.completion_tokens'
                totalTokens = $_.tags.'echomind.total_tokens'
                hasError = $_.hasError
            }
        })
        $toolSpans = @($spans | Where-Object { $_.operationName -like "echomind.tool.*" } | ForEach-Object {
            [pscustomObject]@{
                name = $_.operationName
                tool = $_.tags.'echomind.tool_name'
                durationMs = [math]::Round($_.durationMicros / 1000.0, 2)
                hasError = $_.hasError
            }
        })
        return [pscustomobject]@{
            traceId = $TraceId
            durationMs = [math]::Round($trace.trace.durationMicros / 1000.0, 2)
            hasError = $trace.trace.hasError
            fields = $trace.trace.fields
            stages = $stageSpans
            llm = $llmSpans
            tools = $toolSpans
            team = $teamSpans
            spanCount = $spans.Count
        }
    } catch {
        return [pscustomobject]@{
            traceId = $TraceId
            error = $_.Exception.Message
        }
    }
}

function ShortPreview($Values, [int]$MaxChars = 240) {
    $text = @($Values | Where-Object { -not [string]::IsNullOrWhiteSpace([string]$_) } | Select-Object -First 1)
    if ($text.Count -eq 0) {
        return ""
    }
    $normalized = ([string]$text[0]) -replace "\s+", " "
    return $normalized.Substring(0, [Math]::Min($MaxChars, $normalized.Length))
}

function Read-SseStream($RequestId, [int]$TimeoutSec) {
    $url = "$ApiBase/chat/stream/$RequestId"
    $client = [System.Net.Http.HttpClient]::new()
    $client.Timeout = [TimeSpan]::FromSeconds($TimeoutSec)
    $events = @()
    $result = ""
    $failure = $null
    $metaTraceId = $null
    $metaSessionId = $null
    $tokens = New-Object System.Text.StringBuilder
    $sw = [System.Diagnostics.Stopwatch]::StartNew()
    try {
        $stream = $client.GetStreamAsync($url).GetAwaiter().GetResult()
        $reader = [System.IO.StreamReader]::new($stream, [System.Text.Encoding]::UTF8)
        $currentEvent = $null
        while (-not $reader.EndOfStream) {
            if ($sw.Elapsed.TotalSeconds -gt $TimeoutSec) {
                throw "SSE timeout after ${TimeoutSec}s"
            }
            $line = $reader.ReadLine()
            if ($null -eq $line) { break }
            if ($line.StartsWith("event:")) {
                $currentEvent = $line.Substring(6).Trim()
                continue
            }
            if ($line.StartsWith("data:")) {
                $data = $line.Substring(5).Trim()
                $events += [pscustomobject]@{ event = $currentEvent; data = $data; atMs = [math]::Round($sw.Elapsed.TotalMilliseconds, 1) }
                if ($currentEvent -eq "meta") {
                    try {
                        $m = $data | ConvertFrom-Json
                        $metaTraceId = $m.traceId
                        $metaSessionId = $m.sessionId
                    } catch {}
                } elseif ($currentEvent -eq "token") {
                    try {
                        $t = $data | ConvertFrom-Json
                        [void]$tokens.Append($t.token)
                    } catch {}
                } elseif ($currentEvent -eq "result") {
                    $result = $data
                    break
                } elseif ($currentEvent -eq "failure") {
                    $failure = $data
                    break
                }
            }
        }
    } finally {
        $sw.Stop()
        $client.Dispose()
    }
    $streamStatus = "UNKNOWN"
    if ($failure) {
        $streamStatus = "FAILED"
    } elseif ($result) {
        $streamStatus = "COMPLETED"
    }
    return [pscustomobject]@{
        status = $streamStatus
        durationMs = [math]::Round($sw.Elapsed.TotalMilliseconds, 1)
        traceId = $metaTraceId
        sessionId = $metaSessionId
        tokenText = $tokens.ToString()
        result = $result
        failure = $failure
        events = $events
    }
}

function Invoke-ChatScenario($Scenario, $AdminHeaders) {
    $sessionId = "codex-$RunId-$($Scenario.name)-$([guid]::NewGuid().ToString('N').Substring(0, 8))"
    $body = @{
        agentId = $Scenario.agentId
        message = $Scenario.message
        sessionId = $sessionId
        modelId = $Scenario.modelId
    }
    $submitSw = [System.Diagnostics.Stopwatch]::StartNew()
    $submit = Invoke-Json "Post" "$ApiBase/chat" $body @{} 30
    $submitSw.Stop()
    $sse = Read-SseStream $submit.requestId $TimeoutSeconds
    $traceId = $submit.traceId
    if ($sse.traceId) {
        $traceId = $sse.traceId
    }
    Start-Sleep -Seconds 2
    return [pscustomobject]@{
        type = "chat"
        name = $Scenario.name
        status = $sse.status
        requestId = $submit.requestId
        sessionId = $submit.sessionId
        traceId = $traceId
        submitMs = [math]::Round($submitSw.Elapsed.TotalMilliseconds, 1)
        sseMs = $sse.durationMs
        eventCount = @($sse.events).Count
        tokenEventCount = @($sse.events | Where-Object { $_.event -eq "token" }).Count
        preview = ShortPreview -Values @($sse.result, $sse.tokenText, $sse.failure)
        trace = Get-TraceSummary $traceId $AdminHeaders
    }
}

function Ensure-Agent($AgentId, $Name, $SystemPrompt, $ModelId, $SkillIds) {
    $body = @{
        agentId = $AgentId
        name = $Name
        systemPrompt = $SystemPrompt
        modelId = $ModelId
        skillIds = $SkillIds
    }
    return Invoke-Json "Post" "$ApiBase/agents" $body @{} 30
}

function Upload-Knowledge($AgentId, $FilePath) {
    $curl = "curl.exe -sS -X POST -F `"file=@$FilePath;type=text/plain`" `"$ApiBase/agents/$AgentId/knowledge`""
    $output = Invoke-Expression $curl
    try { return $output | ConvertFrom-Json } catch { return $output }
}

function Create-Team($Name, $AgentIds) {
    $members = @(
        @{ agentId = $AgentIds[0]; role = "PLANNER"; capabilityTags = @("planning", "architecture"); sortOrder = 10 },
        @{ agentId = $AgentIds[1]; role = "EXECUTOR"; capabilityTags = @("backend", "analysis"); sortOrder = 20 },
        @{ agentId = $AgentIds[2]; role = "REVIEWER"; capabilityTags = @("review", "quality"); sortOrder = 30 }
    )
    return Invoke-Json "Post" "$ApiBase/teams" @{ name = $Name; members = $members } @{} 30
}

function Wait-TeamRun($TeamId, $RunId, [int]$TimeoutSec) {
    $deadline = (Get-Date).AddSeconds($TimeoutSec)
    $last = $null
    while ((Get-Date) -lt $deadline) {
        $last = Invoke-RestMethod "$ApiBase/teams/$TeamId/runs/$RunId" -TimeoutSec 20
        if ($last.status -in @("COMPLETED", "FAILED")) {
            return $last
        }
        if ($last.status -eq "NEEDS_CLARIFICATION") {
            try {
                $last = Invoke-Json "Post" "$ApiBase/teams/$TeamId/runs/$RunId/resume" @{ clarificationAnswer = "请按当前信息继续，优先给出工程上可落地的保守方案。" } @{} 20
            } catch {}
        }
        Start-Sleep -Seconds 3
    }
    return $last
}

function Invoke-TeamScenario($TeamId, $Scenario) {
    $sw = [System.Diagnostics.Stopwatch]::StartNew()
    $run = Invoke-Json "Post" "$ApiBase/teams/$TeamId/runs" @{ task = $Scenario.task } @{} 30
    $complete = Wait-TeamRun $TeamId $run.runId $TimeoutSeconds
    $sw.Stop()
    $steps = @($complete.steps)
    $stepStats = @($steps | ForEach-Object {
        $started = if ($_.startedAt) { [datetime]$_.startedAt } else { $null }
        $completed = if ($_.completedAt) { [datetime]$_.completedAt } else { $null }
        $stepDurationMs = $null
        if ($started -and $completed) {
            $stepDurationMs = [math]::Round(($completed - $started).TotalMilliseconds, 1)
        }
        [pscustomobject]@{
            stepIndex = $_.stepIndex
            title = $_.title
            status = $_.status
            assignedAgentId = $_.assignedAgentId
            riskLevel = $_.riskLevel
            qualityStatus = $_.qualityStatus
            durationMs = $stepDurationMs
            retryCount = $_.retryCount
        }
    })
    return [pscustomobject]@{
        type = "team"
        name = $Scenario.name
        status = $complete.status
        runId = $complete.runId
        teamId = $TeamId
        durationMs = [math]::Round($sw.Elapsed.TotalMilliseconds, 1)
        taskLevel = $complete.taskLevel
        stepCount = $steps.Count
        eventCount = @($complete.events).Count
        steps = $stepStats
        eventTimeline = @($complete.events | ForEach-Object {
            [pscustomobject]@{
                type = $_.type
                actorRole = $_.actorRole
                actorAgentId = $_.actorAgentId
                stepId = $_.stepId
                createdAt = $_.createdAt
                message = $_.message
            }
        })
        preview = ShortPreview -Values @($complete.finalOutput, $complete.mergeOutput, $complete.clarificationQuestion)
    }
}

function Invoke-ChatConcurrent($Scenario, [int]$Count, $AdminHeaders) {
    $jobs = @()
    for ($i = 1; $i -le $Count; $i++) {
        $scenarioCopy = [pscustomobject]@{
            name = "$($Scenario.name)-$i"
            agentId = $Scenario.agentId
            modelId = $Scenario.modelId
            message = "$($Scenario.message)`n并发编号：$i"
        }
        $jobs += Start-Job -ScriptBlock {
            param($ScriptPath, $ScenarioJson, $BaseUrl, $TimeoutSeconds)
            [Console]::OutputEncoding = [System.Text.Encoding]::UTF8
            Add-Type -AssemblyName System.Net.Http
            $Scenario = $ScenarioJson | ConvertFrom-Json
            $ApiBase = "$BaseUrl/api"
            function ConvertTo-JsonUtf8($Object, [int]$Depth = 12) { return $Object | ConvertTo-Json -Depth $Depth -Compress }
            function Invoke-Json($Method, $Url, $Body = $null) {
                $params = @{ Method = $Method; Uri = $Url; TimeoutSec = 30 }
                if ($null -ne $Body) {
                    $params.ContentType = "application/json; charset=utf-8"
                    $params.Body = ConvertTo-JsonUtf8 $Body 16
                }
                return Invoke-RestMethod @params
            }
            function Read-Sse($RequestId, [int]$TimeoutSec) {
                $client = [System.Net.Http.HttpClient]::new()
                $client.Timeout = [TimeSpan]::FromSeconds($TimeoutSec)
                $sw = [System.Diagnostics.Stopwatch]::StartNew()
                $status = "UNKNOWN"
                $traceId = $null
                $events = 0
                try {
                    $stream = $client.GetStreamAsync("$ApiBase/chat/stream/$RequestId").GetAwaiter().GetResult()
                    $reader = [System.IO.StreamReader]::new($stream, [System.Text.Encoding]::UTF8)
                    $event = $null
                    while (-not $reader.EndOfStream) {
                        if ($sw.Elapsed.TotalSeconds -gt $TimeoutSec) { throw "timeout" }
                        $line = $reader.ReadLine()
                        if ($null -eq $line) { break }
                        if ($line.StartsWith("event:")) { $event = $line.Substring(6).Trim(); continue }
                        if ($line.StartsWith("data:")) {
                            $events++
                            $data = $line.Substring(5).Trim()
                            if ($event -eq "meta") {
                                try { $traceId = ($data | ConvertFrom-Json).traceId } catch {}
                            }
                            if ($event -eq "result") { $status = "COMPLETED"; break }
                            if ($event -eq "failure") { $status = "FAILED"; break }
                        }
                    }
                } finally {
                    $sw.Stop()
                    $client.Dispose()
                }
                return [pscustomobject]@{ status = $status; traceId = $traceId; durationMs = [math]::Round($sw.Elapsed.TotalMilliseconds, 1); eventCount = $events }
            }
            $sessionId = "codex-concurrent-$([guid]::NewGuid().ToString('N'))"
            $submitSw = [System.Diagnostics.Stopwatch]::StartNew()
            $submit = Invoke-Json "Post" "$ApiBase/chat" @{ agentId = $Scenario.agentId; message = $Scenario.message; sessionId = $sessionId; modelId = $Scenario.modelId }
            $submitSw.Stop()
            $sse = Read-Sse $submit.requestId $TimeoutSeconds
            $traceId = $submit.traceId
            if ($sse.traceId) {
                $traceId = $sse.traceId
            }
            [pscustomobject]@{
                name = $Scenario.name
                status = $sse.status
                requestId = $submit.requestId
                traceId = $traceId
                submitMs = [math]::Round($submitSw.Elapsed.TotalMilliseconds, 1)
                sseMs = $sse.durationMs
                eventCount = $sse.eventCount
            } | ConvertTo-Json -Compress
        } -ArgumentList $PSCommandPath, (ConvertTo-JsonUtf8 $scenarioCopy 8), $BaseUrl, $TimeoutSeconds
    }
    $sw = [System.Diagnostics.Stopwatch]::StartNew()
    Wait-Job -Job $jobs -Timeout ($TimeoutSeconds + 60) | Out-Null
    $sw.Stop()
    $results = @($jobs | ForEach-Object {
        $out = Receive-Job -Job $_
        Remove-Job -Job $_ -Force
        if ($out) { $out | ConvertFrom-Json }
    })
    return [pscustomobject]@{
        type = "chat-concurrency"
        count = $Count
        wallMs = [math]::Round($sw.Elapsed.TotalMilliseconds, 1)
        completed = @($results | Where-Object { $_.status -eq "COMPLETED" }).Count
        failed = @($results | Where-Object { $_.status -ne "COMPLETED" }).Count
        p50Ms = Percentile (@($results | ForEach-Object { [double]$_.sseMs })) 50
        p95Ms = Percentile (@($results | ForEach-Object { [double]$_.sseMs })) 95
        results = $results
    }
}

function Percentile($Values, [double]$P) {
    $arr = @($Values | Where-Object { $_ -ne $null } | Sort-Object)
    if ($arr.Count -eq 0) { return $null }
    $rank = [math]::Ceiling(($P / 100.0) * $arr.Count) - 1
    $rank = [math]::Max(0, [math]::Min($arr.Count - 1, $rank))
    return [math]::Round([double]$arr[$rank], 1)
}

Write-Status "Starting EchoMind dialog/team E2E test. Output: $OutputPath"
$adminHeaders = Login-Admin
$baseline = [pscustomobject]@{
    health = Invoke-RestMethod "$BaseUrl/actuator/health" -TimeoutSec 10
    models = Invoke-RestMethod "$ApiBase/models" -TimeoutSec 10
    agents = Invoke-RestMethod "$ApiBase/agents" -TimeoutSec 10
    queues = Get-RabbitQueues
    dockerStats = Get-DockerStats
}

$testAgentId = "codex-e2e-agent-$RunId"
$ragAgentId = "codex-rag-agent-$RunId"
$teamPlannerId = "codex-team-planner-$RunId"
$teamExecutorId = "codex-team-executor-$RunId"
$teamReviewerId = "codex-team-reviewer-$RunId"
$createdAgents = @($testAgentId, $ragAgentId, $teamPlannerId, $teamExecutorId, $teamReviewerId)
$createdTeamId = $null

try {
    Write-Status "Creating temporary agents"
    Ensure-Agent $testAgentId "Codex E2E DeepSeek" "你是 EchoMind 端到端测试 Agent。用中文简洁回答，除非用户要求详细。" "deepseek:deepseek-v4-flash" @("calculator", "date-query", "markdown-code") | Out-Null
    Ensure-Agent $ragAgentId "Codex E2E RAG" "你是知识库命中率测试 Agent。必须优先使用知识库材料；若没有资料，明确说未命中。" "deepseek:deepseek-v4-flash" @("markdown-code") | Out-Null
    Ensure-Agent $teamPlannerId "Codex Team Planner" "你负责把任务拆成清晰步骤，输出可执行计划。保持中文、结构化、简洁。" "deepseek:deepseek-v4-flash" @("markdown-code") | Out-Null
    Ensure-Agent $teamExecutorId "Codex Team Executor" "你负责完成具体工程分析任务，给出可落地实现细节和风险。" "deepseek:deepseek-v4-flash" @("markdown-code", "calculator", "date-query") | Out-Null
    Ensure-Agent $teamReviewerId "Codex Team Reviewer" "你负责审查计划和结果，指出漏洞、并给出是否通过。" "deepseek:deepseek-v4-flash" @("markdown-code") | Out-Null

    $kbPath = Join-Path $OutputPath "knowledge-fixture.txt"
    @"
EchoMind Codex E2E 知识库夹具。
项目代号：银杏计划。
正确发布窗口：每周二 09:30 到 11:00。
并发压测阈值：聊天 p95 低于 25 秒，Team 单 Run 低于 10 分钟。
灰度回滚口令：gingko-rollback-7429。
如果用户询问银杏计划，必须回答发布窗口和回滚口令。
"@ | Set-Content -Path $kbPath -Encoding UTF8
    Write-Status "Uploading knowledge fixture"
    $knowledgeUpload = Upload-Knowledge $ragAgentId $kbPath

    $chatScenarios = @(
        [pscustomobject]@{ name = "chat-basic-deepseek"; agentId = $testAgentId; modelId = "deepseek:deepseek-v4-flash"; message = "真实场景：我在排查 Spring Boot 接口偶发 502，请给一个 5 步排查清单，重点关注网关、应用线程池、数据库连接池。" },
        [pscustomobject]@{ name = "chat-tool-calculator"; agentId = $testAgentId; modelId = "deepseek:deepseek-v4-flash"; message = "真实场景：一次压测 8 分钟完成 18640 个请求，请计算平均 RPS，并说明是否需要继续看 p95。必须使用计算工具。" },
        [pscustomobject]@{ name = "chat-rag-knowledge"; agentId = $ragAgentId; modelId = "deepseek:deepseek-v4-flash"; message = "银杏计划的正确发布窗口和灰度回滚口令是什么？" },
        [pscustomobject]@{ name = "chat-memory-write"; agentId = $testAgentId; modelId = "deepseek:deepseek-v4-flash"; message = "请记住：我的测试偏好是先看阶段耗时，再看队列堆积，最后看最终答案质量。简单确认即可。" },
        [pscustomobject]@{ name = "chat-qwen-real"; agentId = $testAgentId; modelId = "aliyun-bailian:qwen3.7-max"; message = "真实模型连通性：用中文简要解释 Redis 分片队列为什么能降低同会话乱序风险。" }
    )

    $chatResults = @()
    foreach ($scenario in $chatScenarios) {
        Write-Status "Chat scenario: $($scenario.name)"
        $chatResults += Invoke-ChatScenario $scenario $adminHeaders
    }

    Write-Status "Creating temporary team"
    $team = Create-Team "Codex-E2E-Team-$RunId" @($teamPlannerId, $teamExecutorId, $teamReviewerId)
    $createdTeamId = $team.teamId
    $teamScenarios = @(
        [pscustomobject]@{ name = "team-simple"; task = "真实场景：为一个 Spring Boot + Vue 系统设计一次线上 502 故障排查流程，要求输出排查顺序、关键指标和回滚判断。" },
        [pscustomobject]@{ name = "team-complex"; task = "真实场景：设计 EchoMind 聊天模块高并发压测方案，覆盖 RabbitMQ 入队、SSE 推送、LLM Provider 限流、MySQL/Redis/Milvus 写入，给出指标、阈值和风险。" }
    )
    $teamResults = @()
    foreach ($scenario in $teamScenarios) {
        Write-Status "Team scenario: $($scenario.name)"
        $teamResults += Invoke-TeamScenario $createdTeamId $scenario
    }

    $chatConcurrencyCount = $ChatConcurrency
    $teamConcurrencyCount = $TeamConcurrency

    Write-Status "Running chat concurrency: $chatConcurrencyCount"
    $queueBeforeConcurrency = Get-RabbitQueues
    $statsBeforeConcurrency = Get-DockerStats
    $chatConcurrencyResult = Invoke-ChatConcurrent -Scenario ([pscustomobject]@{
        name = "chat-concurrent"
        agentId = $testAgentId
        modelId = "deepseek:deepseek-v4-flash"
        message = "并发真实场景：请用 3 句话给出接口延迟突然升高时的排查重点。"
    }) -Count $chatConcurrencyCount -AdminHeaders $adminHeaders
    $queueAfterConcurrency = Get-RabbitQueues
    $statsAfterConcurrency = Get-DockerStats

    Write-Status "Running team concurrency: $teamConcurrencyCount"
    $teamJobs = @()
    for ($i = 1; $i -le $teamConcurrencyCount; $i++) {
        $task = "并发 Team Run $i：为一个用户登录超时问题设计排查清单，覆盖网关、JWT、数据库连接池和日志关联。"
        $teamJobs += Start-Job -ScriptBlock {
            param($BaseUrl, $TeamId, $Task, $TimeoutSeconds)
            [Console]::OutputEncoding = [System.Text.Encoding]::UTF8
            $ApiBase = "$BaseUrl/api"
            function ToJson($o) { $o | ConvertTo-Json -Depth 12 -Compress }
            $sw = [System.Diagnostics.Stopwatch]::StartNew()
            $run = Invoke-RestMethod -Method Post "$ApiBase/teams/$TeamId/runs" -ContentType "application/json; charset=utf-8" -Body (ToJson @{ task = $Task }) -TimeoutSec 30
            $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
            $view = $null
            while ((Get-Date) -lt $deadline) {
                $view = Invoke-RestMethod "$ApiBase/teams/$TeamId/runs/$($run.runId)" -TimeoutSec 20
                if ($view.status -in @("COMPLETED", "FAILED")) { break }
                if ($view.status -eq "NEEDS_CLARIFICATION") {
                    try {
                        Invoke-RestMethod -Method Post "$ApiBase/teams/$TeamId/runs/$($run.runId)/resume" -ContentType "application/json; charset=utf-8" -Body (ToJson @{ clarificationAnswer = "继续执行，按保守工程判断给出结论。" }) -TimeoutSec 20 | Out-Null
                    } catch {}
                }
                Start-Sleep -Seconds 3
            }
            $sw.Stop()
            [pscustomobject]@{
                runId = $run.runId
                status = $view.status
                durationMs = [math]::Round($sw.Elapsed.TotalMilliseconds, 1)
                stepCount = @($view.steps).Count
                eventCount = @($view.events).Count
            } | ConvertTo-Json -Compress
        } -ArgumentList $BaseUrl, $createdTeamId, $task, $TimeoutSeconds
    }
    $teamWall = [System.Diagnostics.Stopwatch]::StartNew()
    if ($teamJobs.Count -gt 0) {
        Wait-Job -Job $teamJobs -Timeout ($TimeoutSeconds + 60) | Out-Null
    }
    $teamWall.Stop()
    $teamConcurrencyResults = @($teamJobs | ForEach-Object {
        $out = Receive-Job -Job $_
        Remove-Job -Job $_ -Force
        if ($out) { $out | ConvertFrom-Json }
    })
    $teamConcurrencyResult = [pscustomobject]@{
        type = "team-concurrency"
        count = $teamConcurrencyCount
        wallMs = [math]::Round($teamWall.Elapsed.TotalMilliseconds, 1)
        completed = @($teamConcurrencyResults | Where-Object { $_.status -eq "COMPLETED" }).Count
        failed = @($teamConcurrencyResults | Where-Object { $_.status -ne "COMPLETED" }).Count
        p50Ms = Percentile (@($teamConcurrencyResults | ForEach-Object { [double]$_.durationMs })) 50
        p95Ms = Percentile (@($teamConcurrencyResults | ForEach-Object { [double]$_.durationMs })) 95
        results = $teamConcurrencyResults
    }

    $report = [pscustomobject]@{
        runId = $RunId
        baseUrl = $BaseUrl
        startedAt = (Get-Date).ToString("o")
        baseline = $baseline
        createdAgents = $createdAgents
        createdTeamId = $createdTeamId
        knowledgeUpload = $knowledgeUpload
        chatResults = $chatResults
        teamResults = $teamResults
        concurrency = [pscustomobject]@{
            queueBeforeChat = $queueBeforeConcurrency
            statsBeforeChat = $statsBeforeConcurrency
            chat = $chatConcurrencyResult
            queueAfterChat = $queueAfterConcurrency
            statsAfterChat = $statsAfterConcurrency
            team = $teamConcurrencyResult
            queueFinal = Get-RabbitQueues
            statsFinal = Get-DockerStats
        }
        finishedAt = (Get-Date).ToString("o")
    }
    $jsonPath = Join-Path $OutputPath "report.json"
    $report | ConvertTo-Json -Depth 30 | Set-Content -Path $jsonPath -Encoding UTF8

    $summaryPath = Join-Path $OutputPath "summary.md"
    $chatSummary = $chatResults | ForEach-Object {
        $statusText = switch ($_.status) {
            "COMPLETED" { "完成" }
            "FAILED" { "失败" }
            default { $_.status }
        }
        "- $($_.name)：$statusText，提交耗时=$($_.submitMs)ms，SSE耗时=$($_.sseMs)ms，事件数=$($_.eventCount)，Trace=$($_.traceId)"
    }
    $teamSummary = $teamResults | ForEach-Object {
        $statusText = switch ($_.status) {
            "COMPLETED" { "完成" }
            "FAILED" { "失败" }
            "NEEDS_CLARIFICATION" { "需要澄清" }
            default { $_.status }
        }
        "- $($_.name)：$statusText，总耗时=$($_.durationMs)ms，Step数=$($_.stepCount)，事件数=$($_.eventCount)"
    }
    @"
# EchoMind 对话与 Team 端到端真实场景测试 $RunId

## 对话场景
$($chatSummary -join "`n")

## Team 场景
$($teamSummary -join "`n")

## 并发结果
- 对话：完成 $($chatConcurrencyResult.completed)/$chatConcurrencyCount，p50=$($chatConcurrencyResult.p50Ms)ms，p95=$($chatConcurrencyResult.p95Ms)ms，墙钟耗时=$($chatConcurrencyResult.wallMs)ms。
- Team：完成 $($teamConcurrencyResult.completed)/$teamConcurrencyCount，p50=$($teamConcurrencyResult.p50Ms)ms，p95=$($teamConcurrencyResult.p95Ms)ms，墙钟耗时=$($teamConcurrencyResult.wallMs)ms。

完整 JSON：$jsonPath
"@ | Set-Content -Path $summaryPath -Encoding UTF8

    Write-Status "报告已写入: $jsonPath"
    Write-Status "摘要已写入: $summaryPath"
} finally {
    Write-Status "清理临时 Team/Agent"
    if ($createdTeamId) {
        try { Invoke-RestMethod -Method Delete "$ApiBase/teams/$createdTeamId" -TimeoutSec 30 | Out-Null } catch { Write-Status "WARN: team cleanup failed: $($_.Exception.Message)" }
    }
    foreach ($agentId in $createdAgents) {
        try { Invoke-RestMethod -Method Delete "$ApiBase/agents/$agentId" -TimeoutSec 30 | Out-Null } catch { Write-Status "WARN: agent cleanup failed ${agentId}: $($_.Exception.Message)" }
    }
}
