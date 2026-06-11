import paramiko, sys, os

RESULT_FILE = r"D:\claudeWorkSpace\ai-agent\remote-result.txt"

def write_result(text):
    with open(RESULT_FILE, "w", encoding="utf-8") as f:
        f.write(text)
    print(text)

try:
    c = paramiko.SSHClient()
    c.set_missing_host_key_policy(paramiko.AutoAddPolicy())
    c.connect("106.55.54.63", port=22, username="root", password="214424", timeout=20, banner_timeout=20, auth_timeout=20)

    def run(cmd):
        stdin, stdout, stderr = c.exec_command(cmd, timeout=30)
        out = stdout.read().decode("utf-8", errors="replace")
        err = stderr.read().decode("utf-8", errors="replace")
        return out, err

    lines = []

    out, err = run("echo CONNECTION_OK")
    lines.append(f"CONNECT: {out.strip()}")

    out, err = run("ls -la /opt/echomind/ 2>/dev/null || echo NO_OLD_DEPLOY")
    lines.append(f"OLD_DEPLOY:\n{out}")

    out, err = run("docker ps --format '{{.Names}} {{.Status}}' 2>/dev/null || echo DOCKER_NOT_RUNNING")
    lines.append(f"DOCKER:\n{out}")

    out, err = run("cat /opt/echomind/.env 2>/dev/null || echo NO_ENV_FILE")
    lines.append(f"ENV:\n{out}")

    out, err = run("df -h / && free -h")
    lines.append(f"RESOURCES:\n{out}")

    c.close()
    write_result("\n".join(lines))

except Exception as e:
    write_result(f"ERROR: {e}")
    sys.exit(1)
