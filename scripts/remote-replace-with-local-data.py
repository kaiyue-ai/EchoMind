import argparse
import getpass
import json
import os
import posixpath
import shlex
import subprocess
import sys
import time

import paramiko


LOCAL_ROOT = r"D:\claudeWorkSpace\ai-agent"
REMOTE_PATH = "/opt/echomind"

VOLUMES = [
    {
        "logical": "mysql_data",
        "container": "echomind-db",
        "destination": "/var/lib/mysql",
        "archive": "mysql_data.tar.gz",
    },
    {
        "logical": "redis_data",
        "container": "echomind-cache",
        "destination": "/data",
        "archive": "redis_data.tar.gz",
    },
    {
        "logical": "milvus_etcd_data",
        "container": "echomind-milvus-etcd",
        "destination": "/etcd",
        "archive": "milvus_etcd_data.tar.gz",
    },
    {
        "logical": "milvus_minio_data",
        "container": "echomind-milvus-minio",
        "destination": "/minio_data",
        "archive": "milvus_minio_data.tar.gz",
    },
    {
        "logical": "milvus_data",
        "container": "echomind-milvus",
        "destination": "/var/lib/milvus",
        "archive": "milvus_data.tar.gz",
    },
    {
        "logical": "echomind_data",
        "container": "echomind-backend",
        "destination": "/app/data",
        "archive": "echomind_data.tar.gz",
    },
]


def run_local(args, cwd=LOCAL_ROOT, capture=False, timeout=None):
    print("+ " + " ".join(args))
    if capture:
        result = subprocess.run(
            args,
            cwd=cwd,
            text=True,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            timeout=timeout,
            check=False,
        )
        if result.returncode != 0:
            raise RuntimeError(result.stderr or result.stdout)
        return result.stdout

    subprocess.run(args, cwd=cwd, timeout=timeout, check=True)
    return ""


def remote_exec(client, command, timeout=300):
    print(f">>> {command}")
    stdin, stdout, stderr = client.exec_command(command, timeout=timeout)
    out = stdout.read().decode("utf-8", errors="replace")
    err = stderr.read().decode("utf-8", errors="replace")
    code = stdout.channel.recv_exit_status()
    if out.strip():
        print(out.rstrip())
    if err.strip():
        print(err.rstrip(), file=sys.stderr)
    if code != 0:
        raise RuntimeError(f"Remote command failed with exit {code}: {command}")
    return out


def connect(args):
    client = paramiko.SSHClient()
    client.set_missing_host_key_policy(paramiko.AutoAddPolicy())
    password = args.password or os.environ.get("ECHOMIND_REMOTE_PASS")
    if password is None and not args.use_key:
        password = getpass.getpass("Remote SSH password: ")

    client.connect(
        args.host,
        port=args.port,
        username=args.user,
        password=password,
        timeout=30,
        banner_timeout=30,
        auth_timeout=30,
        look_for_keys=args.use_key,
    )
    return client


def inspect_local_volume(container, destination):
    raw = run_local(["docker", "inspect", container], capture=True)
    data = json.loads(raw)[0]
    for mount in data.get("Mounts", []):
        if mount.get("Type") == "volume" and mount.get("Destination") == destination:
            return mount["Name"]
    raise RuntimeError(f"Volume mount not found: {container}:{destination}")


def export_local_volumes(backup_dir):
    os.makedirs(backup_dir, exist_ok=True)
    resolved = []
    for item in VOLUMES:
        local_volume = inspect_local_volume(item["container"], item["destination"])
        archive_path = os.path.join(backup_dir, item["archive"])
        if os.path.exists(archive_path):
            os.remove(archive_path)
        print(f"Exporting {local_volume} -> {archive_path}")
        run_local(
            [
                "docker",
                "run",
                "--rm",
                "-v",
                f"{local_volume}:/volume:ro",
                "-v",
                f"{backup_dir}:/backup",
                "alpine:3.20",
                "sh",
                "-lc",
                f"cd /volume && tar czf /backup/{shlex.quote(item['archive'])} .",
            ]
        )
        resolved.append({**item, "local_volume": local_volume, "archive_path": archive_path})
    return resolved


def upload_archives(client, backup_dir, remote_import_dir, resolved):
    remote_exec(client, f"mkdir -p {shlex.quote(remote_import_dir)}")
    sftp = client.open_sftp()
    try:
        for item in resolved:
            remote_file = posixpath.join(remote_import_dir, item["archive"])
            size_mb = os.path.getsize(item["archive_path"]) / 1024 / 1024
            print(f"Uploading {item['archive']} ({size_mb:.1f} MB) -> {remote_file}")
            sftp.put(item["archive_path"], remote_file)
    finally:
        sftp.close()


def restore_remote_volumes(client, remote_import_dir, remote_project):
    remote_exec(client, f"cd {shlex.quote(REMOTE_PATH)} && docker compose down -v --remove-orphans", timeout=300)

    old_project_names = [remote_project]
    if remote_project != "ai-agent":
        old_project_names.append("ai-agent")
    volume_names = [f"{project}_{item['logical']}" for project in old_project_names for item in VOLUMES]
    remove_list = " ".join(shlex.quote(name) for name in volume_names)
    remote_exec(client, f"docker volume rm {remove_list} 2>/dev/null || true")

    for item in VOLUMES:
        remote_volume = f"{remote_project}_{item['logical']}"
        remote_archive = posixpath.join(remote_import_dir, item["archive"])
        remote_exec(client, f"docker volume create {shlex.quote(remote_volume)}")
        remote_exec(
            client,
            "docker run --rm "
            f"-v {shlex.quote(remote_volume)}:/volume "
            f"-v {shlex.quote(remote_import_dir)}:/backup "
            "alpine:3.20 sh -lc "
            + shlex.quote(f"cd /volume && tar xzf /backup/{item['archive']}"),
            timeout=600,
        )
        remote_exec(client, f"test -f {shlex.quote(remote_archive)}")

    remote_exec(client, f"cd {shlex.quote(REMOTE_PATH)} && docker compose up -d --remove-orphans", timeout=600)


def main():
    parser = argparse.ArgumentParser(
        description="Replace remote EchoMind Docker volumes with the current local Docker volumes."
    )
    parser.add_argument("--host", default=os.environ.get("ECHOMIND_REMOTE_HOST", "106.55.54.63"))
    parser.add_argument("--port", type=int, default=int(os.environ.get("ECHOMIND_REMOTE_PORT", "2144")))
    parser.add_argument("--user", default=os.environ.get("ECHOMIND_REMOTE_USER", "root"))
    parser.add_argument("--password", default=os.environ.get("ECHOMIND_REMOTE_PASS"))
    parser.add_argument("--use-key", action="store_true", help="Use local SSH keys instead of password auth.")
    parser.add_argument("--remote-project", default=os.environ.get("ECHOMIND_REMOTE_PROJECT", "echomind"))
    parser.add_argument("--skip-local-stop", action="store_true", help="Archive local volumes without stopping Compose.")
    parser.add_argument(
        "--confirm-delete-server",
        action="store_true",
        help="Required. Deletes remote EchoMind containers and named volumes before restoring local data.",
    )
    args = parser.parse_args()

    if not args.confirm_delete_server:
        raise SystemExit("Refusing to delete remote data without --confirm-delete-server")

    timestamp = time.strftime("%Y%m%d-%H%M%S")
    backup_dir = os.path.abspath(os.path.join(LOCAL_ROOT, "tmp", f"local-volume-export-{timestamp}"))
    remote_import_dir = posixpath.join(REMOTE_PATH, "data-import", timestamp)

    local_was_stopped = False
    try:
        if not args.skip_local_stop:
            run_local(["docker", "compose", "stop"])
            local_was_stopped = True

        resolved = export_local_volumes(backup_dir)
    finally:
        if local_was_stopped:
            run_local(["docker", "compose", "up", "-d"], timeout=600)

    client = connect(args)
    try:
        upload_archives(client, backup_dir, remote_import_dir, resolved)
        restore_remote_volumes(client, remote_import_dir, args.remote_project)
        remote_exec(client, "curl -sf http://localhost:8080/actuator/health || true", timeout=60)
        remote_exec(client, "docker ps --format 'table {{.Names}}\\t{{.Status}}\\t{{.Ports}}'", timeout=60)
    finally:
        client.close()

    print("Remote EchoMind data has been replaced with the local Docker volume data.")


if __name__ == "__main__":
    main()
