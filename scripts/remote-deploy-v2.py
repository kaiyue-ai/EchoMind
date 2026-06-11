import paramiko, sys, os, time, glob as globmod

REMOTE_HOST = "106.55.54.63"
REMOTE_PORT = 22
REMOTE_USER = "root"
REMOTE_PASS = "214424"
REMOTE_PATH = "/opt/echomind"
LOCAL_BASE = r"D:\claudeWorkSpace\ai-agent"

RESULT_FILE = os.path.join(LOCAL_BASE, "deploy-log.txt")

def log(msg):
    with open(RESULT_FILE, "a", encoding="utf-8") as f:
        f.write(msg + "\n")
    print(msg)

def ssh_connect():
    c = paramiko.SSHClient()
    c.set_missing_host_key_policy(paramiko.AutoAddPolicy())
    c.connect(REMOTE_HOST, port=REMOTE_PORT, username=REMOTE_USER, password=REMOTE_PASS, timeout=30, banner_timeout=30, auth_timeout=30)
    return c

def remote_exec(c, cmd, timeout=120):
    stdin, stdout, stderr = c.exec_command(cmd, timeout=timeout)
    out = stdout.read().decode("utf-8", errors="replace")
    err = stderr.read().decode("utf-8", errors="replace")
    exit_code = stdout.channel.recv_exit_status()
    return out, err, exit_code

def upload_file(sftp, local, remote):
    size = os.path.getsize(local)
    log(f"  uploading {os.path.basename(local)} ({size/1024/1024:.1f} MB) -> {remote}")
    sftp.put(local, remote)
    log(f"  done: {os.path.basename(local)}")

def upload_dir(sftp, local_dir, remote_dir):
    for root, dirs, files in os.walk(local_dir):
        rel = os.path.relpath(root, local_dir).replace("\\", "/")
        if rel == ".":
            remote_root = remote_dir
        else:
            remote_root = f"{remote_dir}/{rel}"
        try:
            sftp.stat(remote_root)
        except FileNotFoundError:
            sftp.mkdir(remote_root)
        for f in files:
            local_path = os.path.join(root, f)
            remote_path = f"{remote_root}/{f}"
            upload_file(sftp, local_path, remote_path)

try:
    open(RESULT_FILE, "w").close()

    log("=" * 60)
    log("Step 1: SSH Connect + Check Old Deploy")
    log("=" * 60)
    c = ssh_connect()
    log("SSH connected!")

    out, err, rc = remote_exec(c, f"ls -la {REMOTE_PATH}/ 2>/dev/null || echo NO_OLD_DEPLOY")
    log(f"Old deploy:\n{out}")

    out, err, rc = remote_exec(c, "docker ps --format '{{.Names}} {{.Status}}' 2>/dev/null || echo NO_DOCKER")
    log(f"Docker containers:\n{out}")

    out, err, rc = remote_exec(c, f"cat {REMOTE_PATH}/.env 2>/dev/null || echo NO_ENV")
    log(f"Old .env:\n{out}")

    log("")
    log("=" * 60)
    log("Step 2: Backup Old Config + Stop Old Services")
    log("=" * 60)
    out, err, rc = remote_exec(c, f"cp {REMOTE_PATH}/.env {REMOTE_PATH}/.env.bak.`date +%Y%m%d%H%M%S` 2>/dev/null; echo BACKUP_DONE")
    log(out.strip())

    out, err, rc = remote_exec(c, f"cd {REMOTE_PATH} && docker compose down 2>/dev/null; echo STOP_DONE")
    log(out.strip())

    log("")
    log("=" * 60)
    log("Step 3: Create Remote Directories")
    log("=" * 60)
    dirs = [
        f"{REMOTE_PATH}/echomind-app/target",
        f"{REMOTE_PATH}/echomind-user-memory/target",
        f"{REMOTE_PATH}/external-mcp",
        f"{REMOTE_PATH}/docker/mysql",
        f"{REMOTE_PATH}/docker/open-websearch",
        f"{REMOTE_PATH}/docker/otel",
        f"{REMOTE_PATH}/echomind-web/dist",
        f"{REMOTE_PATH}/echomind-web/dist-admin",
        f"{REMOTE_PATH}/scripts",
    ]
    skill_dirs = ["skill-weather", "skill-calculator", "skill-markdown-code", "skill-date-query", "skill-github-intel", "skill-railway-12306", "skill-travel-planning"]
    for s in skill_dirs:
        dirs.append(f"{REMOTE_PATH}/skills/{s}/target")

    out, err, rc = remote_exec(c, f"rm -rf {REMOTE_PATH}/docker/mysql/migrations {REMOTE_PATH}/docker/open-websearch")
    if rc != 0:
        raise RuntimeError(f"Failed to clear remote generated deploy dirs: {err or out}")

    mkdir_cmd = "mkdir -p " + " ".join(dirs)
    out, err, rc = remote_exec(c, mkdir_cmd)
    log("Directories created")

    log("")
    log("=" * 60)
    log("Step 4: Upload Files via SFTP")
    log("=" * 60)
    sftp = c.open_sftp()

    upload_map = {
        "docker-compose.yml": f"{REMOTE_PATH}/docker-compose.yml",
        "Dockerfile.runtime": f"{REMOTE_PATH}/Dockerfile.runtime",
        "Dockerfile.user-memory": f"{REMOTE_PATH}/Dockerfile.user-memory",
        "Dockerfile": f"{REMOTE_PATH}/Dockerfile",
        "docker/mysql/init.sql": f"{REMOTE_PATH}/docker/mysql/init.sql",
        "docker/open-websearch/Dockerfile": f"{REMOTE_PATH}/docker/open-websearch/Dockerfile",
        "docker/otel/collector-config.yaml": f"{REMOTE_PATH}/docker/otel/collector-config.yaml",
        "scripts/apply-mysql-migrations.sh": f"{REMOTE_PATH}/scripts/apply-mysql-migrations.sh",
        "echomind-app/target/echomind-app-1.0.0-SNAPSHOT.jar": f"{REMOTE_PATH}/echomind-app/target/echomind-app-1.0.0-SNAPSHOT.jar",
        "echomind-user-memory/target/echomind-user-memory-1.0.0-SNAPSHOT.jar": f"{REMOTE_PATH}/echomind-user-memory/target/echomind-user-memory-1.0.0-SNAPSHOT.jar",
        "external-mcp/nowcoder-java-interview-mcp-server-1.0.0.jar": f"{REMOTE_PATH}/external-mcp/nowcoder-java-interview-mcp-server-1.0.0.jar",
        "echomind-web/Dockerfile.runtime": f"{REMOTE_PATH}/echomind-web/Dockerfile.runtime",
        "echomind-web/Dockerfile.admin-runtime": f"{REMOTE_PATH}/echomind-web/Dockerfile.admin-runtime",
    }

    for local_rel, remote_abs in upload_map.items():
        local_abs = os.path.join(LOCAL_BASE, local_rel.replace("/", os.sep))
        if os.path.exists(local_abs):
            upload_file(sftp, local_abs, remote_abs)
        else:
            log(f"  SKIP (not found): {local_rel}")

    for s in skill_dirs:
        pattern = os.path.join(LOCAL_BASE, "skills", s, "target", "*-jar-with-dependencies.jar")
        jars = globmod.glob(pattern)
        for jar in jars:
            remote_jar = f"{REMOTE_PATH}/skills/{s}/target/{os.path.basename(jar)}"
            upload_file(sftp, jar, remote_jar)

    log("Uploading MySQL migrations...")
    upload_dir(sftp, os.path.join(LOCAL_BASE, "docker", "mysql", "migrations"), f"{REMOTE_PATH}/docker/mysql/migrations")

    log("Uploading frontend dist...")
    upload_dir(sftp, os.path.join(LOCAL_BASE, "echomind-web", "dist"), f"{REMOTE_PATH}/echomind-web/dist")
    log("Uploading frontend dist-admin...")
    upload_dir(sftp, os.path.join(LOCAL_BASE, "echomind-web", "dist-admin"), f"{REMOTE_PATH}/echomind-web/dist-admin")

    sftp.close()
    log("All files uploaded!")

    log("")
    log("=" * 60)
    log("Step 5: Restore/Create .env")
    log("=" * 60)
    env_restore_cmd = f"""
if [ -f {REMOTE_PATH}/.env ]; then
    echo 'Using existing .env'
elif [ -f $(ls -t {REMOTE_PATH}/.env.bak.* 2>/dev/null | head -1) ]; then
    LATEST=$(ls -t {REMOTE_PATH}/.env.bak.* 2>/dev/null | head -1)
    cp "$LATEST" {REMOTE_PATH}/.env
    echo "Restored .env from $LATEST"
else
    cat > {REMOTE_PATH}/.env << 'ENVEOF'
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
    echo 'Created default .env - PLEASE EDIT API KEYS!'
fi
"""
    out, err, rc = remote_exec(c, env_restore_cmd)
    log(out.strip())

    log("")
    log("=" * 60)
    log("Step 6: Docker Build + Start")
    log("=" * 60)
    log("Applying MySQL migrations...")
    out, err, rc = remote_exec(c, f"cd {REMOTE_PATH} && chmod +x scripts/apply-mysql-migrations.sh && ./scripts/apply-mysql-migrations.sh --start-database", timeout=300)
    log(f"Migration output:\n{out}")
    if err.strip():
        log(f"Migration stderr:\n{err}")
    if rc != 0:
        raise RuntimeError("MySQL migrations failed")

    log("Building Docker images (this may take a few minutes)...")
    out, err, rc = remote_exec(c, f"cd {REMOTE_PATH} && docker compose build --no-cache 2>&1 | tail -30", timeout=600)
    log(f"Build output:\n{out}")
    if err.strip():
        log(f"Build stderr:\n{err}")

    log("Starting services...")
    out, err, rc = remote_exec(c, f"cd {REMOTE_PATH} && docker compose up -d 2>&1", timeout=120)
    log(f"Start output:\n{out}")
    if err.strip():
        log(f"Start stderr:\n{err}")

    log("")
    log("=" * 60)
    log("Step 7: Verify")
    log("=" * 60)
    log("Waiting 30s for services to start...")
    time.sleep(30)

    out, err, rc = remote_exec(c, "docker ps --format 'table {{.Names}}\t{{.Status}}\t{{.Ports}}'")
    log(f"Containers:\n{out}")

    out, err, rc = remote_exec(c, "curl -sf http://localhost:8080/actuator/health 2>/dev/null || echo 'Backend not ready yet'")
    log(f"Backend health: {out.strip()}")

    out, err, rc = remote_exec(c, "curl -sf http://localhost:80/ 2>/dev/null | head -3 || echo 'Frontend not ready yet'")
    log(f"Frontend: {out.strip()}")

    c.close()

    log("")
    log("=" * 60)
    log("DEPLOY COMPLETE!")
    log(f"Frontend:  http://{REMOTE_HOST}")
    log(f"Admin:     http://{REMOTE_HOST}:8081")
    log(f"Backend:   http://{REMOTE_HOST}:8080")
    log("=" * 60)

except Exception as e:
    log(f"FATAL ERROR: {e}")
    import traceback
    log(traceback.format_exc())
    sys.exit(1)
