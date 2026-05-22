param(
    [string]$MigrationsDir = "docker/mysql/migrations",
    [string]$MysqlService = "mysql",
    [string]$MysqlContainer = "echomind-db",
    [string]$Database = "echomind",
    [string]$MysqlUser = "root",
    [string]$MysqlPassword = "echomind_root",
    [int]$StartupTimeoutSeconds = 120,
    [switch]$StartDatabase
)

$ErrorActionPreference = "Stop"

$utf8NoBom = New-Object System.Text.UTF8Encoding($false)
[Console]::OutputEncoding = $utf8NoBom
$OutputEncoding = $utf8NoBom

$scriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$rootDir = Split-Path -Parent $scriptDir

if ([System.IO.Path]::IsPathRooted($MigrationsDir)) {
    $migrationsPath = $MigrationsDir
} else {
    $migrationsPath = Join-Path $rootDir $MigrationsDir
}

if (-not (Test-Path -LiteralPath $migrationsPath)) {
    throw "Migrations directory not found: $migrationsPath"
}

function Wait-ForMysqlContainer {
    param(
        [string]$ContainerName,
        [int]$TimeoutSeconds
    )

    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    while ((Get-Date) -lt $deadline) {
        $status = & docker inspect --format "{{if .State.Health}}{{.State.Health.Status}}{{else}}{{.State.Status}}{{end}}" $ContainerName 2>$null
        if ($LASTEXITCODE -eq 0) {
            $status = ($status | Select-Object -First 1).Trim()
            if ($status -eq "healthy" -or $status -eq "running") {
                return
            }
        }
        Start-Sleep -Seconds 2
    }

    throw "Timed out waiting for MySQL container '$ContainerName' to become healthy."
}

function Invoke-Mysql {
    param(
        [Parameter(Mandatory = $true)]
        [string]$Sql
    )

    $args = @(
        "exec",
        "-e", "MYSQL_PWD=$MysqlPassword",
        "-i", $MysqlContainer,
        "mysql",
        "--default-character-set=utf8mb4",
        "-u", $MysqlUser,
        $Database
    )

    $Sql | & docker @args
    if ($LASTEXITCODE -ne 0) {
        throw "mysql command failed with exit code $LASTEXITCODE"
    }
}

function Invoke-MysqlScalar {
    param(
        [Parameter(Mandatory = $true)]
        [string]$Sql
    )

    $args = @(
        "exec",
        "-e", "MYSQL_PWD=$MysqlPassword",
        "-i", $MysqlContainer,
        "mysql",
        "--default-character-set=utf8mb4",
        "--batch",
        "--skip-column-names",
        "-u", $MysqlUser,
        $Database
    )

    $output = $Sql | & docker @args
    if ($LASTEXITCODE -ne 0) {
        throw "mysql query failed with exit code $LASTEXITCODE"
    }

    return ($output | Select-Object -First 1)
}

function Escape-SqlLiteral {
    param([string]$Value)
    return $Value.Replace("'", "''")
}

Push-Location $rootDir
try {
    if ($StartDatabase) {
        & docker compose up -d $MysqlService
        if ($LASTEXITCODE -ne 0) {
            throw "docker compose up -d $MysqlService failed with exit code $LASTEXITCODE"
        }
    }

    Wait-ForMysqlContainer -ContainerName $MysqlContainer -TimeoutSeconds $StartupTimeoutSeconds

    Invoke-Mysql @"
CREATE TABLE IF NOT EXISTS echomind_schema_migrations (
    version VARCHAR(255) PRIMARY KEY,
    checksum_sha256 CHAR(64) NOT NULL,
    applied_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
"@

    $migrationFiles = Get-ChildItem -LiteralPath $migrationsPath -Filter "*.sql" -File | Sort-Object Name
    if (-not $migrationFiles) {
        Write-Host "No MySQL migration files found under $migrationsPath"
        exit 0
    }

    foreach ($file in $migrationFiles) {
        $version = $file.Name
        $versionSql = Escape-SqlLiteral $version
        $checksum = (Get-FileHash -LiteralPath $file.FullName -Algorithm SHA256).Hash.ToLowerInvariant()

        $appliedChecksum = Invoke-MysqlScalar "SELECT checksum_sha256 FROM echomind_schema_migrations WHERE version = '$versionSql';"
        if ($appliedChecksum) {
            $appliedChecksum = $appliedChecksum.Trim().ToLowerInvariant()
            if ($appliedChecksum -ne $checksum) {
                throw "Migration '$version' was already applied with checksum $appliedChecksum, but file checksum is $checksum."
            }

            Write-Host "Skipping applied migration $version"
            continue
        }

        Write-Host "Applying migration $version"
        $sql = Get-Content -LiteralPath $file.FullName -Raw -Encoding UTF8
        Invoke-Mysql $sql

        $checksumSql = Escape-SqlLiteral $checksum
        Invoke-Mysql "INSERT INTO echomind_schema_migrations(version, checksum_sha256) VALUES ('$versionSql', '$checksumSql');"
    }

    Write-Host "MySQL migrations are up to date."
} finally {
    Pop-Location
}
