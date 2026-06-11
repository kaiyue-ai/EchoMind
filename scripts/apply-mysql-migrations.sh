#!/usr/bin/env bash
set -euo pipefail

MIGRATIONS_DIR="docker/mysql/migrations"
MYSQL_SERVICE="mysql"
MYSQL_CONTAINER="echomind-db"
DATABASE="echomind"
MYSQL_USER="root"
MYSQL_PASSWORD="echomind_root"
STARTUP_TIMEOUT_SECONDS=120
START_DATABASE=false

while [[ $# -gt 0 ]]; do
  case "$1" in
    --migrations-dir)
      MIGRATIONS_DIR="$2"
      shift 2
      ;;
    --mysql-service)
      MYSQL_SERVICE="$2"
      shift 2
      ;;
    --mysql-container)
      MYSQL_CONTAINER="$2"
      shift 2
      ;;
    --database)
      DATABASE="$2"
      shift 2
      ;;
    --mysql-user)
      MYSQL_USER="$2"
      shift 2
      ;;
    --mysql-password)
      MYSQL_PASSWORD="$2"
      shift 2
      ;;
    --startup-timeout-seconds)
      STARTUP_TIMEOUT_SECONDS="$2"
      shift 2
      ;;
    --start-database)
      START_DATABASE=true
      shift
      ;;
    *)
      echo "Unknown argument: $1" >&2
      exit 2
      ;;
  esac
done

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"

if [[ "$MIGRATIONS_DIR" = /* ]]; then
  MIGRATIONS_PATH="$MIGRATIONS_DIR"
else
  MIGRATIONS_PATH="$ROOT_DIR/$MIGRATIONS_DIR"
fi

if [[ ! -d "$MIGRATIONS_PATH" ]]; then
  echo "Migrations directory not found: $MIGRATIONS_PATH" >&2
  exit 1
fi

cd "$ROOT_DIR"

if [[ "$START_DATABASE" == true ]]; then
  docker compose up -d "$MYSQL_SERVICE"
fi

deadline=$((SECONDS + STARTUP_TIMEOUT_SECONDS))
while true; do
  status="$(docker inspect --format '{{if .State.Health}}{{.State.Health.Status}}{{else}}{{.State.Status}}{{end}}' "$MYSQL_CONTAINER" 2>/dev/null || true)"
  if [[ "$status" == "healthy" || "$status" == "running" ]]; then
    break
  fi

  if (( SECONDS >= deadline )); then
    echo "Timed out waiting for MySQL container '$MYSQL_CONTAINER' to become healthy." >&2
    exit 1
  fi

  sleep 2
done

mysql_exec() {
  docker exec -e "MYSQL_PWD=$MYSQL_PASSWORD" -i "$MYSQL_CONTAINER" \
    mysql --default-character-set=utf8mb4 -u "$MYSQL_USER" "$DATABASE"
}

mysql_scalar() {
  local sql="$1"
  docker exec -e "MYSQL_PWD=$MYSQL_PASSWORD" -i "$MYSQL_CONTAINER" \
    mysql --default-character-set=utf8mb4 --batch --skip-column-names \
    -u "$MYSQL_USER" "$DATABASE" -e "$sql" | head -n 1
}

sql_escape() {
  printf "%s" "$1" | sed "s/'/''/g"
}

file_sha256() {
  if command -v sha256sum >/dev/null 2>&1; then
    sha256sum "$1" | awk '{print tolower($1)}'
  else
    shasum -a 256 "$1" | awk '{print tolower($1)}'
  fi
}

mysql_exec <<'SQL'
CREATE TABLE IF NOT EXISTS echomind_schema_migrations (
    version VARCHAR(255) PRIMARY KEY,
    checksum_sha256 CHAR(64) NOT NULL,
    applied_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
SQL

mapfile -t migration_files < <(find "$MIGRATIONS_PATH" -maxdepth 1 -type f -name "*.sql" | sort)

if [[ ${#migration_files[@]} -eq 0 ]]; then
  echo "No MySQL migration files found under $MIGRATIONS_PATH"
  exit 0
fi

for file in "${migration_files[@]}"; do
  version="$(basename "$file")"
  version_sql="$(sql_escape "$version")"
  checksum="$(file_sha256 "$file")"

  applied_checksum="$(mysql_scalar "SELECT checksum_sha256 FROM echomind_schema_migrations WHERE version = '$version_sql';" | tr -d '[:space:]')"
  if [[ -n "$applied_checksum" ]]; then
    if [[ "${applied_checksum,,}" != "$checksum" ]]; then
      echo "Migration '$version' was already applied with checksum $applied_checksum, but file checksum is $checksum." >&2
      exit 1
    fi

    echo "Skipping applied migration $version"
    continue
  fi

  echo "Applying migration $version"
  mysql_exec < "$file"

  checksum_sql="$(sql_escape "$checksum")"
  mysql_exec <<<"INSERT INTO echomind_schema_migrations(version, checksum_sha256) VALUES ('$version_sql', '$checksum_sql');"
done

echo "MySQL migrations are up to date."
