import paramiko
import sys
import traceback

try:
    client = paramiko.SSHClient()
    client.set_missing_host_key_policy(paramiko.AutoAddPolicy())
    client.connect("106.55.54.63", port=2144, username="root", password="214424", timeout=30)

    results = []

    def run(label, cmd):
        stdin, stdout, stderr = client.exec_command(cmd)
        out = stdout.read().decode("utf-8", errors="replace")
        err = stderr.read().decode("utf-8", errors="replace")
        results.append(f"=== {label} ===")
        results.append(out)
        if err.strip():
            results.append(f"STDERR: {err}")

    action = sys.argv[1] if len(sys.argv) > 1 else "check"

    if action == "check":
        run("/opt/", "ls -la /opt/")
        run("Docker", "docker ps --format '{{.Names}} {{.Status}}'")
        run("Disk", "df -h /")
        run("Memory", "free -h")
        run("Old compose", "cat /opt/echomind/docker-compose.yml 2>/dev/null | head -30 || echo 'NOT_FOUND'")
        run("Old env", "cat /opt/echomind/.env 2>/dev/null || echo 'NO .env'")

    elif action == "setup-key":
        with open(r"C:\Users\Jimmy\.ssh\id_rsa.pub", "r") as f:
            pub_key = f.read().strip()
        cmd = f'mkdir -p ~/.ssh && echo "{pub_key}" >> ~/.ssh/authorized_keys && chmod 700 ~/.ssh && chmod 600 ~/.ssh/authorized_keys && echo "KEY_ADDED_OK"'
        run("Setup Key", cmd)

    elif action == "upload":
        import os
        base_dir = r"D:\claudeWorkSpace\ai-agent"
        stdin, stdout, stderr = client.exec_command("mkdir -p /opt/echomind/echomind-app/target /opt/echomind/scripts")
        stdout.read()

        sftp = client.open_sftp()
        files = [
            ("docker-compose.yml", "/opt/echomind/docker-compose.yml"),
            ("Dockerfile.runtime", "/opt/echomind/Dockerfile.runtime"),
            ("Dockerfile", "/opt/echomind/Dockerfile"),
        ]
        env_file = os.path.join(base_dir, ".env")
        if os.path.exists(env_file):
            files.append((".env", "/opt/echomind/.env"))

        for local_name, remote_path in files:
            local_path = os.path.join(base_dir, local_name)
            if os.path.exists(local_path):
                sftp.put(local_path, remote_path)
                results.append(f"uploaded: {local_name}")

        jar_path = os.path.join(base_dir, "echomind-app", "target", "echomind-app-1.0.0-SNAPSHOT.jar")
        if os.path.exists(jar_path):
            jar_size_mb = os.path.getsize(jar_path) / (1024*1024)
            results.append(f"uploading JAR ({jar_size_mb:.1f} MB)...")
            sftp.put(jar_path, "/opt/echomind/echomind-app/target/echomind-app-1.0.0-SNAPSHOT.jar")
            results.append("uploaded: echomind-app-1.0.0-SNAPSHOT.jar")

        scripts_dir = os.path.join(base_dir, "scripts")
        if os.path.isdir(scripts_dir):
            for item in os.listdir(scripts_dir):
                local_path = os.path.join(scripts_dir, item)
                if os.path.isfile(local_path):
                    sftp.put(local_path, f"/opt/echomind/scripts/{item}")
                    results.append(f"uploaded: scripts/{item}")

        sftp.close()
        results.append("\nAll files uploaded!")

    elif action == "deploy":
        run("Stop old", "cd /opt/echomind && docker compose down 2>/dev/null || true")
        run("Build", "cd /opt/echomind && docker compose build --no-cache backend 2>&1 | tail -20")
        run("Start", "cd /opt/echomind && docker compose up -d 2>&1")
        run("Check", "docker ps --format '{{.Names}} {{.Status}}'")

    elif action == "exec":
        cmd = " ".join(sys.argv[2:])
        run("exec", cmd)

    client.close()
    output = "\n".join(results)
    with open(r"D:\claudeWorkSpace\ai-agent\remote-result.txt", "w", encoding="utf-8") as f:
        f.write(output)
    print(output)

except Exception as e:
    with open(r"D:\claudeWorkSpace\ai-agent\remote-result.txt", "w", encoding="utf-8") as f:
        f.write(f"ERROR: {e}\n{traceback.format_exc()}")
    print(f"ERROR: {e}")
    traceback.print_exc()
