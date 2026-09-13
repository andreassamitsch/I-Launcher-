"""Live PC test of the compiled production Kotlin CONNECT bridge (no Android runtime).

Provide --runtime-json from the BridgePcTest.kt compilation and --config as for the
main diagnostic. Credentials are passed only through the child process environment.
"""
import argparse
import json
import os
from pathlib import Path
import subprocess
import tempfile
import time

import test_mysterium_proxy as pc


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--config", type=Path, required=True)
    parser.add_argument("--runtime-json", type=Path, required=True, help="JSON containing classpath for compiled BridgePcTestKt")
    args = parser.parse_args()
    config = json.loads(args.config.read_text(encoding="utf-8-sig"))
    cp = json.loads(args.runtime_json.read_text())["classpath"]
    folder = Path(tempfile.mkdtemp(prefix="mysterium-bridge-live-"))
    redactor = pc.Redactor()
    redactor.learn(config)
    log = pc.Log(folder, redactor)
    print(f"Masked results: {folder}", flush=True)
    results = []
    with pc.session() as control:
        control.headers.update({"Authorization": "Bearer " + config["MYSTERIUM_ACCESS_TOKEN"],
                                "x-client-version": "joyntv-1", "x-client-platform": "android",
                                "User-Agent": "JoynTV/AndroidTV Mysterium-Residential-Integration"})
        for country in ("DE", "CH"):
            r = pc.request(control, "POST", pc.BASE + "/connection/connect-proxy", log, "lease-" + country,
                           json={"country": country, "ip_type": "residential", "reset_connection": True, "os_type": "android"})
            r.raise_for_status()
            lease = pc.parse_lease(r.json())
            env = dict(os.environ)
            for key in ("host", "port", "username", "password"):
                env["MYSTERIUM_PROXY_" + key.upper()] = str(lease[key])
            process = subprocess.Popen(["java", "-cp", cp, "com.andreassamitsch.joyntv.BridgePcTestKt", "live"],
                                       stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True, env=env)
            try:
                line = process.stdout.readline().strip()
                if not line.startswith("LOCAL_PROXY_PORT="):
                    raise RuntimeError("Bridge failed to start")
                port = int(line.split("=", 1)[1])
                with pc.session(f"http://127.0.0.1:{port}") as s:
                    s.headers["Connection"] = "close"
                    start = time.monotonic()
                    before = pc.trace(s, log, "exit-before")
                    first_ms = round((time.monotonic() - start) * 1000)
                    result = pc.joyn(s, country, log)
                    after = pc.trace(s, log, "exit-after")
                    result.update(country=country, api_host=lease["host"], api_port=lease["port"],
                                  before=before, after=after, first_trace_ms=first_ms,
                                  stable_ip=before["ip"] == after["ip"], reused_lease=True)
                    # Same target with HTTP keep-alive: record what the server actually does.
                    s.headers["Connection"] = "keep-alive"
                    result["keep_alive_traces"] = [pc.trace(s, log, "keepalive-trace") for _ in range(3)]
                    results.append(result)
                    log.save("bridge-result-" + country, result)
                    print(json.dumps(redactor.clean(result)), flush=True)
            finally:
                try:
                    _, errors = process.communicate("stop\n", timeout=10)
                except subprocess.TimeoutExpired:
                    process.kill()
                    _, errors = process.communicate()
                log.save("bridge-process", {"exit": process.returncode, "stderr": errors})
    log.save("summary", results)


if __name__ == "__main__":
    main()
