param(
    [string[]]$Names = @(
        "DEEPSEEK_API_KEY",
        "DEEPSEEK_BASE_URL",
        "ANTHROPIC_API_KEY",
        "ANTHROPIC_BASE_URL",
        "OPENAI_API_KEY",
        "ALIYUN_BAILIAN_API_KEY",
        "ALIYUN_BAILIAN_BASE_URL",
        "ALIYUN_BAILIAN_EMBEDDING_BASE_URL",
        "AccessKeyID",
        "AccessKeySecret",
        "ALIBABA_CLOUD_ACCESS_KEY_ID",
        "ALIBABA_CLOUD_ACCESS_KEY_SECRET",
        "ALIYUN_OSS_ENDPOINT",
        "ALIYUN_OSS_BUCKET",
        "Webhook"
    )
)

$loaded = New-Object System.Collections.Generic.List[string]
$missing = New-Object System.Collections.Generic.List[string]

foreach ($name in $Names) {
    $value = [Environment]::GetEnvironmentVariable($name, "Process")
    if (-not $value) {
        $value = [Environment]::GetEnvironmentVariable($name, "User")
    }
    if (-not $value) {
        $value = [Environment]::GetEnvironmentVariable($name, "Machine")
    }

    if ($value) {
        Set-Item -Path "Env:$name" -Value $value
        $loaded.Add($name)
    } else {
        $missing.Add($name)
    }
}

if (-not $env:DEEPSEEK_API_KEY -and $env:ANTHROPIC_API_KEY) {
    $env:DEEPSEEK_API_KEY = $env:ANTHROPIC_API_KEY
    $loaded.Add("DEEPSEEK_API_KEY<-ANTHROPIC_API_KEY")
}

if (-not $env:DEEPSEEK_BASE_URL -and $env:ANTHROPIC_BASE_URL) {
    $env:DEEPSEEK_BASE_URL = $env:ANTHROPIC_BASE_URL
    $loaded.Add("DEEPSEEK_BASE_URL<-ANTHROPIC_BASE_URL")
}

Write-Host ("Loaded compose env names: " + (($loaded | Sort-Object -Unique) -join ", "))
if ($missing.Count -gt 0) {
    Write-Host ("Missing compose env names: " + (($missing | Sort-Object -Unique) -join ", "))
}
