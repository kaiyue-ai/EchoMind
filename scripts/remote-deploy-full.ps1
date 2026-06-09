<#
.SYNOPSIS
  EchoMind 远程一键部署脚本
.DESCRIPTION
  将本地已构建好的项目通过 SCP 上传到远程服务器并启动 Docker Compose。
  使用前确保本地已有 SSH 客户端和 scp 命令。
.USAGE
  .\scripts\remote-deploy-full.ps1
#>

$RemoteHost = "106.55.54.63"
$RemotePort = "2144"
$RemoteUser = "root"
$RemotePass = "214424"
$RemotePath = "/opt/echomind"
$LocalBase  = "D:\claudeWorkSpace\ai-agent"

$ErrorActionPreference = "Stop"
$SshTarget = "${RemoteUser}@${RemoteHost}"

function Remote-Exec($cmd) {
    Write-Host "`n>>> SSH: $cmd" -ForegroundColor Cyan
    ssh -o StrictHostKeyChecking=no -p $RemotePort $SshTarget $cmd
    if ($LASTEXITCODE -ne 0) {
        throw "Remote command failed (exit $LASTEXITCODE): $cmd"
    }
}

function Scp-Upload($localPath, $remotePath) {
    $remoteFull = "${SshTarget}:${remotePath}"
    Write-Host "  SCP: $localPath -> $remoteFull" -ForegroundColor Gray
    scp -o StrictHostKeyChecking=no -P $RemotePort $localPath $remoteFull
    if ($LASTEXITCODE -ne 0) {
        throw "SCP failed: $localPath -> $remoteFull"
    }
}

function Scp-UploadDir($localDir, $remotePath) {
    $remoteFull = "${SshTarget}:${remotePath}"
    Write-Host "  SCP -r: $localDir -> $remoteFull" -ForegroundColor Gray
    scp -o StrictHostKeyChecking=no -P $RemotePort -r $localDir $remoteFull
    if ($LASTEXITCODE -ne 0) {
        throw "SCP failed: $localDir -> $remoteFull"
    }
}

# ============================================================
# Step 0: 检查本地构建产物
# ============================================================
Write-Host "`n========================================" -ForegroundColor Yellow
Write-Host " Step 0: 检查本地构建产物" -ForegroundColor Yellow
Write-Host "========================================" -ForegroundColor Yellow

$requiredFiles = @(
    "$LocalBase\echomind-app\target\echomind-app-1.0.0-SNAPSHOT.jar",
    "$LocalBase\echomind-user-memory\target\echomind-user-memory-1.0.0-SNAPSHOT.jar",
    "$LocalBase\external-mcp\nowcoder-java-interview-mcp-server-1.0.0.jar",
    "$LocalBase\docker-compose.yml",
    "$LocalBase\Dockerfile.runtime",
    "$LocalBase\Dockerfile.user-memory",
    "$LocalBase\docker\mysql\init.sql",
    "$LocalBase\docker\open-websearch\Dockerfile",
    "$LocalBase\docker\otel\collector-config.yaml",
    "$LocalBase\scripts\apply-mysql-migrations.sh"
)

$missing = $false
foreach ($f in $requiredFiles) {
    if (!(Test-Path $f)) {
        Write-Host "  MISSING: $f" -ForegroundColor Red
        $missing = $true
    } else {
        $size = [math]::Round((Get-Item $f).Length / 1MB, 1)
        Write-Host "  OK: $(Split-Path $f -Leaf) ($size MB)" -ForegroundColor Green
    }
}

# Skill JARs
$skillDirs = @("skill-weather", "skill-calculator", "skill-markdown-code", "skill-date-query", "skill-github-intel", "skill-railway-12306", "skill-travel-planning")
foreach ($s in $skillDirs) {
    $jar = Get-Item "$LocalBase\skills\$s\target\*-jar-with-dependencies.jar" -ErrorAction SilentlyContinue
    if ($jar) {
        Write-Host "  OK: $s JAR" -ForegroundColor Green
    } else {
        Write-Host "  MISSING: $s JAR" -ForegroundColor Red
        $missing = $true
    }
}

# Frontend
if (Test-Path "$LocalBase\echomind-web\dist") {
    Write-Host "  OK: frontend dist" -ForegroundColor Green
} else {
    Write-Host "  MISSING: frontend dist" -ForegroundColor Red
    $missing = $true
}
if (Test-Path "$LocalBase\echomind-web\dist-admin") {
    Write-Host "  OK: admin dist-admin" -ForegroundColor Green
} else {
    Write-Host "  MISSING: admin dist-admin" -ForegroundColor Red
    $missing = $true
}

if ($missing) {
    Write-Host "`n!!! 有缺失文件，请先构建。退出。" -ForegroundColor Red
    return
}

$migrationFiles = @(Get-ChildItem "$LocalBase\docker\mysql\migrations\*.sql" -ErrorAction SilentlyContinue)
if (-not $migrationFiles) {
    Write-Host "`n!!! 缺少 MySQL migration 文件：$LocalBase\docker\mysql\migrations\*.sql" -ForegroundColor Red
    return
}
Write-Host "  OK: MySQL migrations ($($migrationFiles.Count) files)" -ForegroundColor Green

# ============================================================
# Step 1: SSH 连接测试 + 检查旧版部署
# ============================================================
Write-Host "`n========================================" -ForegroundColor Yellow
Write-Host " Step 1: SSH 连接测试 + 检查旧版部署" -ForegroundColor Yellow
Write-Host "========================================" -ForegroundColor Yellow

Remote-Exec "echo CONNECTION_OK"
Remote-Exec "ls -la $RemotePath/ 2>/dev/null || echo 'NO_OLD_DEPLOY'"
Remote-Exec "docker ps --format 'table {{.Names}}\t{{.Status}}' 2>/dev/null || echo 'Docker not running'"
Remote-Exec "cat ${RemotePath}/.env 2>/dev/null || echo 'NO_ENV_FILE'"

# ============================================================
# Step 2: 备份旧版 .env 和配置
# ============================================================
Write-Host "`n========================================" -ForegroundColor Yellow
Write-Host " Step 2: 备份旧版配置" -ForegroundColor Yellow
Write-Host "========================================" -ForegroundColor Yellow

Remote-Exec "cp ${RemotePath}/.env ${RemotePath}/.env.bak.`date +%Y%m%d%H%M%S` 2>/dev/null; echo BACKUP_DONE"

# ============================================================
# Step 3: 停止旧版服务
# ============================================================
Write-Host "`n========================================" -ForegroundColor Yellow
Write-Host " Step 3: 停止旧版服务" -ForegroundColor Yellow
Write-Host "========================================" -ForegroundColor Yellow

Remote-Exec "cd $RemotePath && docker compose down 2>/dev/null; echo STOP_DONE"

# ============================================================
# Step 4: 上传项目文件
# ============================================================
Write-Host "`n========================================" -ForegroundColor Yellow
Write-Host " Step 4: 上传项目文件" -ForegroundColor Yellow
Write-Host "========================================" -ForegroundColor Yellow

# 创建远程目录结构
Remote-Exec "rm -rf ${RemotePath}/docker/mysql/migrations ${RemotePath}/docker/open-websearch; mkdir -p ${RemotePath}/{echomind-app/target,echomind-user-memory/target,external-mcp,skills,docker/mysql,docker/open-websearch,docker/otel,echomind-web/dist,echomind-web/dist-admin,scripts}"

# 核心配置文件
Scp-Upload "$LocalBase\docker-compose.yml" "$RemotePath/docker-compose.yml"
Scp-Upload "$LocalBase\Dockerfile.runtime" "$RemotePath/Dockerfile.runtime"
Scp-Upload "$LocalBase\Dockerfile.user-memory" "$RemotePath/Dockerfile.user-memory"
Scp-Upload "$LocalBase\Dockerfile" "$RemotePath/Dockerfile"

# Docker 配置
Scp-Upload "$LocalBase\docker\mysql\init.sql" "$RemotePath/docker/mysql/init.sql"
Scp-UploadDir "$LocalBase\docker\mysql\migrations" "$RemotePath/docker/mysql/"
Scp-Upload "$LocalBase\docker\open-websearch\Dockerfile" "$RemotePath/docker/open-websearch/Dockerfile"
Scp-Upload "$LocalBase\docker\otel\collector-config.yaml" "$RemotePath/docker/otel/collector-config.yaml"
Scp-Upload "$LocalBase\scripts\apply-mysql-migrations.sh" "$RemotePath/scripts/apply-mysql-migrations.sh"

# 后端 JAR
Write-Host "`n  上传后端 JAR (约108MB，请耐心等待)..." -ForegroundColor Yellow
Scp-Upload "$LocalBase\echomind-app\target\echomind-app-1.0.0-SNAPSHOT.jar" "$RemotePath/echomind-app/target/echomind-app-1.0.0-SNAPSHOT.jar"

# User Memory JAR
Write-Host "`n  上传 User Memory JAR..." -ForegroundColor Yellow
Scp-Upload "$LocalBase\echomind-user-memory\target\echomind-user-memory-1.0.0-SNAPSHOT.jar" "$RemotePath/echomind-user-memory/target/echomind-user-memory-1.0.0-SNAPSHOT.jar"

# MCP JAR
Scp-Upload "$LocalBase\external-mcp\nowcoder-java-interview-mcp-server-1.0.0.jar" "$RemotePath/external-mcp/nowcoder-java-interview-mcp-server-1.0.0.jar"

# Skill JARs
Remote-Exec "mkdir -p ${RemotePath}/skills/{skill-weather/target,skill-calculator/target,skill-markdown-code/target,skill-date-query/target,skill-github-intel/target,skill-railway-12306/target,skill-travel-planning/target}"

foreach ($s in $skillDirs) {
    $jar = (Get-Item "$LocalBase\skills\$s\target\*-jar-with-dependencies.jar").FullName
    Scp-Upload $jar "$RemotePath/skills/$s/target/"
}

# 前端文件
Write-Host "`n  上传前端 dist..." -ForegroundColor Yellow
Scp-UploadDir "$LocalBase\echomind-web\dist" "$RemotePath/echomind-web/"
Write-Host "`n  上传前端 dist-admin..." -ForegroundColor Yellow
Scp-UploadDir "$LocalBase\echomind-web\dist-admin" "$RemotePath/echomind-web/"
Scp-Upload "$LocalBase\echomind-web\Dockerfile.runtime" "$RemotePath/echomind-web/Dockerfile.runtime"
Scp-Upload "$LocalBase\echomind-web\Dockerfile.admin-runtime" "$RemotePath/echomind-web/Dockerfile.admin-runtime"

# ============================================================
# Step 5: 恢复/创建 .env
# ============================================================
Write-Host "`n========================================" -ForegroundColor Yellow
Write-Host " Step 5: 恢复/创建 .env" -ForegroundColor Yellow
Write-Host "========================================" -ForegroundColor Yellow

Remote-Exec @"
if [ -f ${RemotePath}/.env ]; then
    echo 'Using existing .env'
    cat ${RemotePath}/.env
elif [ -f ${RemotePath}/.env.bak.* ]; then
    LATEST_ENV=`ls -t ${RemotePath}/.env.bak.* 2>/dev/null | head -1`
    if [ -n "`$LATEST_ENV" ]; then
        cp "`$LATEST_ENV" ${RemotePath}/.env
        echo "Restored .env from `$LATEST_ENV"
    fi
else
    echo 'Creating default .env'
    cat > ${RemotePath}/.env << 'ENVEOF'
DEEPSEEK_API_KEY=
DEEPSEEK_BASE_URL=https://api.deepseek.com
OPENAI_API_KEY=
ALIYUN_BAILIAN_API_KEY=
ALIYUN_BAILIAN_BASE_URL=https://dashscope.aliyuncs.com/compatible-mode/v1
ALIYUN_BAILIAN_EMBEDDING_BASE_URL=https://dashscope.aliyuncs.com/compatible-mode
ALIBABA_CLOUD_ACCESS_KEY_ID=
ALIBABA_CLOUD_ACCESS_KEY_SECRET=
ECHOMIND_STORAGE_MODE=oss
ALIYUN_OSS_ENDPOINT=https://oss-cn-beijing.aliyuncs.com
ALIYUN_OSS_BUCKET=echo-mind2144
HOST_MOUNT_PATH=/root
ENVEOF
    echo 'WARNING: .env created with empty API keys - please edit it!'
fi
"@

# ============================================================
# Step 6: 构建 Docker 镜像并启动
# ============================================================
Write-Host "`n========================================" -ForegroundColor Yellow
Write-Host " Step 6: 构建 Docker 镜像并启动" -ForegroundColor Yellow
Write-Host "========================================" -ForegroundColor Yellow

Remote-Exec "cd $RemotePath && chmod +x scripts/apply-mysql-migrations.sh && ./scripts/apply-mysql-migrations.sh --start-database"

Remote-Exec "cd $RemotePath && docker compose build --no-cache 2>&1 | tail -30"

Write-Host "`n  启动所有服务..." -ForegroundColor Yellow
Remote-Exec "cd $RemotePath && docker compose up -d 2>&1"

# ============================================================
# Step 7: 验证
# ============================================================
Write-Host "`n========================================" -ForegroundColor Yellow
Write-Host " Step 7: 验证部署" -ForegroundColor Yellow
Write-Host "========================================" -ForegroundColor Yellow

Write-Host "`n  等待服务启动 (30秒)..." -ForegroundColor Gray
Start-Sleep -Seconds 30

Remote-Exec "docker ps --format 'table {{.Names}}\t{{.Status}}\t{{.Ports}}'"
Remote-Exec "curl -sf http://localhost:8080/actuator/health 2>/dev/null || echo 'Backend not ready yet'"
Remote-Exec "curl -sf http://localhost:80/ 2>/dev/null | head -5 || echo 'Frontend not ready yet'"

Write-Host "`n========================================" -ForegroundColor Green
Write-Host " 部署完成！" -ForegroundColor Green
Write-Host " 前端: http://$RemoteHost" -ForegroundColor Green
Write-Host " 管理端: http://$RemoteHost:8081" -ForegroundColor Green
Write-Host " 后端: http://$RemoteHost:8080" -ForegroundColor Green
Write-Host "========================================" -ForegroundColor Green
