#!/usr/bin/env python3
"""PC-only connect-proxy/Joyn diagnostic. See docs/MYSTERIUM_PC_TEST.md.

Requires Python 3.10+ and requests. Never persists usable credentials.
"""
import argparse
import base64
import csv
import hashlib
import ipaddress
import json
import os
from pathlib import Path
import re
import socket
import ssl
import tempfile
import time
from datetime import datetime, timezone
from urllib.parse import quote, urljoin, urlsplit
import uuid

import requests

BASE = "https://api.mysteriumvpn.com/api/v1"
UA = "Mozilla/5.0 (Linux; Android 14; Android TV) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"
QUERY = "query MysteriumLiveProbe { liveStreams(filterLivestreamsTypes: [LINEAR], first: 30, offset: 0) { id markings } }"
AUTH = "https://auth.joyn.de/auth/anonymous"
GRAPHQL = "https://api.joyn.de/graphql"
ENTITLEMENT = "https://entitlements-service-alb.prd.platform.s.joyn.de/api/user/entitlement-token"
SECRET_KEY = re.compile(r"password|passwd|secret|token|authorization|cookie|api.?key|username|credential|verifier", re.I)
MARKERS = {
    "vpn": r"ENT_USER_VPN_DETECTED|VPN_DETECTED",
    "geo": r"GEO[_ -]?(?:BLOCK|RESTRICT)|COUNTRY[_ -]?(?:NOT|MISMATCH|BLOCK)|NOT_AVAILABLE_IN_YOUR_COUNTRY|ENT_USER_COUNTRY|ENT_CONTENT_GEO|ENT_AssetNotAvailableInCountry",
    "login_premium": r"LOGIN_REQUIRED|AUTHENTICATION_REQUIRED|SUBSCRIPTION_REQUIRED|PREMIUM_REQUIRED|ENT_USER_NOT_LOGGED_IN|ENT_USER_SUBSCRIPTION",
    "denied": r"ACCESS[_ -]DENIED|FORBIDDEN",
    "entitlement_codes": r"\bENT_[A-Z0-9_]+\b",
}


def is_status(key, value):
    return key in {"token", "config", "graphql", "entitlement"} and isinstance(value, str) and bool(
        re.fullmatch(r"OK|FAILED|NOT_RUN|HTTP_\d{3}|GRAPHQL_ERRORS", value))


def signals(body):
    return {key: sorted(set(re.findall(pattern, body, re.I))) for key, pattern in MARKERS.items()}


class Redactor:
    def __init__(self):
        self.secrets = set()

    def learn(self, value):
        if isinstance(value, dict):
            for key, item in value.items():
                if is_status(key, item):
                    continue
                if SECRET_KEY.search(key) and isinstance(item, str) and item:
                    self.secrets.add(item)
                self.learn(item)
        elif isinstance(value, list):
            for item in value:
                self.learn(item)

    def clean(self, value):
        if isinstance(value, dict):
            return {k: v if is_status(k, v) else "***" if SECRET_KEY.search(k) else self.clean(v) for k, v in value.items()}
        if isinstance(value, list):
            return [self.clean(v) for v in value]
        if isinstance(value, str):
            for secret in sorted(self.secrets, key=len, reverse=True):
                value = value.replace(secret, "***")
            value = re.sub(r"eyJ[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+", "***", value)
            value = re.sub(r"(?i)(?:https?|socks5)://[^\s/@]+:[^\s/@]+@", "proxy://***@", value)
            return value
        return value


class Log:
    def __init__(self, directory, redactor):
        self.directory, self.redactor = directory, redactor
        self.counter = 0
        self.joyn_signals = {}

    def save(self, label, value):
        if label.startswith("joyn-") and isinstance(value, dict):
            for key, values in value.get("signals", {}).items():
                self.joyn_signals.setdefault(key, []).extend(values)
            if value.get("status") in (401, 403):
                self.joyn_signals.setdefault("http_denied", []).append(value["status"])
        self.redactor.learn(value)
        self.counter += 1
        path = self.directory / f"{self.counter:03d}-{label}.json"
        path.write_text(json.dumps(self.redactor.clean(value), indent=2, ensure_ascii=True), encoding="utf-8")


def session(proxy=None):
    s = requests.Session()
    s.trust_env = False  # Ignore HTTP(S)_PROXY, ALL_PROXY, NO_PROXY and netrc.
    s.headers.update({"User-Agent": UA})
    if proxy:
        s.proxies = {"http": proxy, "https": proxy}
    return s


def request(s, method, url, log, label, **kwargs):
    """Redirects retain this session's mandatory proxy; refuse HTTPS downgrade."""
    start = time.monotonic()
    for hop in range(6):
        if urlsplit(url).scheme != "https":
            raise ValueError("HTTPS required, including redirects")
        response = s.request(method, url, timeout=(8, 20), allow_redirects=False, **kwargs)
        try:
            body = response.json()
            structured = True
        except ValueError:
            structured = False
            # HTML/JS may contain embedded secrets. Keep evidence, not opaque source text.
            body = {"non_json_bytes": len(response.content), "sha256": hashlib.sha256(response.content).hexdigest()}
        log.save(label, {"host": urlsplit(url).hostname, "status": response.status_code,
                         "elapsed_ms": round((time.monotonic() - start) * 1000),
                         # Successful JS/HTML contains error-code definitions, not actual errors.
                         "signals": signals(response.text) if structured or not response.ok else {}, "body": body})
        if response.status_code not in (301, 302, 303, 307, 308) or not response.headers.get("Location"):
            return response
        next_url = urljoin(url, response.headers["Location"])
        if urlsplit(next_url).netloc != urlsplit(url).netloc:
            # Never forward Joyn authorization/API keys to another origin.
            kwargs["headers"] = {k: v for k, v in kwargs.get("headers", {}).items()
                                 if k.lower() not in ("authorization", "x-api-key")}
            if method != "GET":
                raise ValueError("Cross-origin POST redirect refused")
        if response.status_code == 303 or (response.status_code in (301, 302) and method == "POST"):
            method = "GET"
            kwargs.pop("json", None)
        kwargs.pop("params", None)
        url = next_url
    raise ValueError("Redirect limit reached")


def parse_lease(root):
    if root.get("limit_exceeded") or root.get("limitExceeded"):
        raise ValueError("MYSTERIUM_LIMIT")
    p = root.get("proxy_config") or root.get("proxyConfig")
    if not isinstance(p, dict):
        raise ValueError("Missing proxy_config")
    host, port = str(p.get("host", "")).strip(), int(p.get("port", 0))
    if not host or any(c in host for c in "/@\r\n ") or not 1 <= port <= 65535:
        raise ValueError("Invalid proxy host/port")
    return {"host": host, "port": port, "username": p.get("username", ""),
            "password": p.get("password", ""), "expires": p.get("expires_at") or p.get("expiresAt", "")}


def lease_metadata(root, lease):
    """Preserve observed fields; do not invent country/provider/id or a server TTL."""
    result = {"expires": lease["expires"], "remaining_seconds_computed": None, "api_fields": {}}
    def visit(value, path=""):
        if isinstance(value, dict):
            for key, item in value.items():
                name = f"{path}.{key}" if path else key
                if re.search(r"country|provider|node|lease|expir|ttl|(^|_)id$", key, re.I) and not SECRET_KEY.search(key):
                    result["api_fields"][name] = item
                visit(item, name)
        elif isinstance(value, list):
            for i, item in enumerate(value):
                visit(item, f"{path}[{i}]")
    visit(root)
    try:
        expiry = datetime.fromisoformat(lease["expires"].replace("Z", "+00:00"))
        if expiry.tzinfo is not None:
            result["remaining_seconds_computed"] = round((expiry - datetime.now(timezone.utc)).total_seconds())
    except (ValueError, TypeError):
        pass
    return result


def connect_probe(lease, tls, target="api.joyn.de"):
    """No target DNS locally: send hostname in CONNECT. New TCP + auth per call."""
    start, stage, sock = time.monotonic(), "proxy_dns_tcp", None
    result = {"transport": "https" if tls else "http", "target": target}
    try:
        sock = socket.create_connection((lease["host"], lease["port"]), timeout=8)
        sock.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
        result["peer"] = list(sock.getpeername())
        result["tcp_ms"] = round((time.monotonic() - start) * 1000)
        if tls:
            stage = "proxy_tls"
            sock = ssl.create_default_context().wrap_socket(sock, server_hostname=lease["host"])
            result["tls_version"] = sock.version()
        stage = "connect"
        header = f"CONNECT {target}:443 HTTP/1.1\r\nHost: {target}:443\r\n"
        if lease["username"]:
            credentials = (lease["username"] + ":" + lease["password"]).encode("iso-8859-1")
            header += "Proxy-Authorization: Basic " + base64.b64encode(credentials).decode("ascii") + "\r\n"
        sock.sendall((header + "Proxy-Connection: Keep-Alive\r\n\r\n").encode("iso-8859-1"))
        response = bytearray()
        while not response.endswith(b"\r\n\r\n"):
            if len(response) >= 32768:
                raise ValueError("CONNECT header exceeds 32768 bytes")
            chunk = sock.recv(1)
            if not chunk:
                raise ConnectionError("EOF before CONNECT response")
            response.extend(chunk)
        match = re.match(rb"HTTP/1\.[01] (\d{3})(?: |\r)", response)
        if not match:
            raise ValueError("Invalid CONNECT status line")
        result["status"] = int(match[1])
        result["ok"] = result["status"] == 200
    except Exception as exc:
        # Exception strings may contain credentials or server-controlled text.
        result.update(ok=False, error=type(exc).__name__, stage=stage,
                      errno=getattr(exc, "errno", None))
    finally:
        if sock is not None:
            sock.close()
    result["elapsed_ms"] = round((time.monotonic() - start) * 1000)
    return result


def proxy_url(lease, transport):
    host = lease["host"]
    if ":" in host and not host.startswith("["):
        host = f"[{host}]"
    auth = ""
    if lease["username"]:
        auth = quote(lease["username"], safe="") + ":" + quote(lease["password"], safe="") + "@"
    return f"{transport}://{auth}{host}:{lease['port']}"


def trace(s, log, label):
    r = request(s, "GET", "https://www.cloudflare.com/cdn-cgi/trace", log, label)
    r.raise_for_status()
    values = dict(line.split("=", 1) for line in r.text.splitlines() if "=" in line)
    ip = str(ipaddress.ip_address(values["ip"]))
    return {"ip": ip, "country": values["loc"].upper()}


def joyn(s, country, log, api_key=None):
    log.joyn_signals = {}
    result = dict(token="NOT_RUN", graphql="NOT_RUN", entitlement="NOT_RUN", channel="",
                  vpn_detected="UNKNOWN", classification="JOYN_OTHER", signals={})

    def call(method, url, label, **kwargs):
        r = request(s, method, url, log, label, **kwargs)
        if r.status_code in (401, 403):
            result["signals"].setdefault("http_denied", []).append(r.status_code)
        return r

    try:
        web = f"https://www.joyn.{country.lower()}/"
        r = call("GET", web, "joyn-web")
        r.raise_for_status()
        if not api_key:
            for src in re.findall(r'''<script[^>]+src=["']([^"']+)["']''', r.text, re.I)[:50]:
                bundle = call("GET", urljoin(web, src), "joyn-bundle")
                if bundle.ok:
                    match = re.search(r'API_GW_API_KEY.{0,80}?value:"([^"]+)"', bundle.text, re.S)
                    if match:
                        api_key = match[1]
                        log.redactor.learn({"api_key": api_key})
                        break
        result["config"] = "OK" if api_key else "FAILED"
        headers = {"Joyn-Country": country, "Joyn-Distribution-Tenant": "JOYN_" + country}
        r = call("POST", AUTH, "joyn-token", headers=headers,
                 json={"anon_device_id": str(uuid.uuid4()), "client_id": str(uuid.uuid4()), "client_name": "web"})
        result["token"] = f"HTTP_{r.status_code}"
        r.raise_for_status()
        token = r.json()
        if not token.get("access_token"):
            raise ValueError("Anonymous token missing")
        result["token"] = "OK"
        if not api_key:
            raise ValueError("API_GW_API_KEY missing")
        authorization = token.get("token_type", "Bearer") + " " + token["access_token"]
        headers = {"Joyn-Country": country, "Joyn-Distribution-Tenant": "JOYN" if country == "DE" else "JOYN_CH",
                   "Joyn-Platform": "web", "Accept": "application/json", "x-api-key": api_key,
                   "Authorization": authorization}
        # Three independent TCP connections using the same lease and anonymous session.
        streams = []
        for i in range(3):
            r = call("GET", GRAPHQL, f"joyn-graphql-{i+1}", headers=headers, params={"query": QUERY})
            result["graphql"] = f"HTTP_{r.status_code}"
            r.raise_for_status()
            data = r.json()
            if data.get("errors"):
                result["graphql"] = "GRAPHQL_ERRORS"
                raise ValueError("GraphQL errors")
            streams = (data.get("data") or {}).get("liveStreams")
            if not isinstance(streams, list):
                raise ValueError("Missing liveStreams")
            result["graphql"] = "OK"
            result["graphql_requests_ok"] = i + 1
        channel = next((x for x in streams if x.get("id") and not
                        set(x.get("markings") or []).intersection({"PLUS", "PREMIUM"})), None)
        if not channel:
            raise ValueError("No unmarked free live channel")
        result["channel"] = channel["id"]
        r = call("POST", ENTITLEMENT, "joyn-entitlement", headers={"Authorization": authorization},
                 json={"content_id": channel["id"], "content_type": "LIVE"})
        result["entitlement"] = f"HTTP_{r.status_code}"
        if r.ok and r.json().get("entitlement_token"):
            result["entitlement"] = "OK"
            result["classification"] = "ACCEPTED"
        else:
            result["classification"] = "ENTITLEMENT_OTHER"
    except Exception as exc:
        result["error"] = type(exc).__name__
        if isinstance(exc, (requests.ConnectionError, requests.Timeout, requests.exceptions.SSLError)):
            result["classification"] = "PROXY_OR_TARGET_NETWORK_ERROR"
    flags = log.joyn_signals
    result["signals"] = flags
    if flags.get("vpn"):
        result.update(vpn_detected="YES", classification="VPN_DETECTED")
    elif flags.get("geo"):
        result["classification"] = "GEO_BLOCKED"
    elif flags.get("login_premium"):
        result["classification"] = "LOGIN_PREMIUM_REQUIRED"
    elif result["entitlement"] == "OK":
        result["vpn_detected"] = "NO"
    elif flags.get("denied") or flags.get("http_denied"):
        result["classification"] = "ACCESS_DENIED_UNSPECIFIED"
    return result


def run_lease(lease, country, log, api_key, fallback_tls_port=None):
    result = {"country": country, "proxy_host": lease["host"], "proxy_port": lease["port"],
              "expires": lease["expires"], "classification": "PROXY_BROKEN", "vpn_detected": "UNKNOWN"}
    probes = [connect_probe(lease, False), connect_probe(lease, True)]
    log.save("connect-variants", probes)
    result["connect_variants"] = probes
    order = ("https", "http") if lease["port"] in (443, 8443) else ("http", "https")
    working = next((p for transport in order for p in probes if p["transport"] == transport and p["ok"]), None)
    if working is None and fallback_tls_port and fallback_tls_port != lease["port"]:
        # Explicit diagnostic override only: preserve the API endpoint and its failed probes.
        result["api_proxy_port"] = lease["port"]
        alternative = dict(lease, port=fallback_tls_port)
        fallback = connect_probe(alternative, True)
        result["fallback_tls_probe"] = fallback
        log.save("explicit-tls-port-diagnostic", {"port": fallback_tls_port, "probe": fallback})
        if fallback["ok"]:
            lease, working = alternative, fallback
            result["proxy_port"] = fallback_tls_port
    if working is None:
        return result
    result.update(transport=working["transport"], latency_ms=working["elapsed_ms"])
    with session(proxy_url(lease, working["transport"])) as s:
        # Deliberately close target HTTP connections; every request requires a new authenticated CONNECT.
        s.headers["Connection"] = "close"
        try:
            first = trace(s, log, "exit-before")
            result.update(exit_ip=first["ip"], exit_country=first["country"])
            try:
                r = request(s, "GET", "https://api.ipify.org", log, "ipify")
                r.raise_for_status()
                result["ipify_ip"] = str(ipaddress.ip_address(r.text.strip()))
            except Exception as exc:
                result["ipify_error"] = type(exc).__name__
            result.update(joyn(s, country, log, api_key))
            result["joyn_classification"] = result["classification"]
            # Runs even when Joyn rejects the exit; does not request or renew any lease.
            last = trace(s, log, "exit-after")
            result["exit_after"] = last
            result["stable_ip"] = first["ip"] == last["ip"] and result.get("ipify_ip", first["ip"]) == first["ip"]
            result["reused_lease"] = True
            result["lease_usable_at_end"] = True
            if first["country"] != country or last["country"] != country:
                result["classification"] = "WRONG_EXIT_COUNTRY"
            elif not result["stable_ip"]:
                result["classification"] = "EXIT_CHANGED"
        except Exception as exc:
            result.update(error=type(exc).__name__, classification="PROXY_OR_TRACE_NETWORK_ERROR")
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--config", type=Path, help="Local untracked JSON with MYSTERIUM_ACCESS_TOKEN, optional JOYN_API_KEY_DE/CH")
    parser.add_argument("--countries", nargs="+", choices=["DE", "CH"], default=["DE", "CH"])
    parser.add_argument("--leases", type=int, choices=range(1, 11), default=5, metavar="1..10")
    parser.add_argument("--preflight", action="store_true", help="Public config and one UNAUTHENTICATED lease attempt per country; no paid lease")
    parser.add_argument("--fallback-tls-port", type=int, choices=[443], help="Explicit additional TLS:443 diagnosis AFTER failure on the exact API port; same lease credentials")
    args = parser.parse_args()
    config = json.loads(args.config.read_text(encoding="utf-8-sig")) if args.config else {}
    def setting(key):
        return os.environ.get(key) or config.get(key, "")
    token = setting("MYSTERIUM_ACCESS_TOKEN").removeprefix("Bearer ").strip()
    redactor = Redactor()
    redactor.learn(config)
    redactor.learn({"access_token": token})
    directory = Path(tempfile.mkdtemp(prefix="mysterium-pc-"))
    log = Log(directory, redactor)
    print(f"Masked debug/results: {directory}", flush=True)
    rows = []
    with session() as control:
        control.headers.update({"Accept": "application/json", "User-Agent": "JoynTV/AndroidTV Mysterium-Residential-Integration",
                                "x-client-version": "joyntv-1", "x-client-platform": "android"})
        try:
            request(control, "GET", BASE + "/auth/config", log, "mysterium-config")
            if not token and not args.preflight:
                log.save("blocked", {"reason": "MYSTERIUM_ACCESS_TOKEN missing"})
                print("BLOCKED: MYSTERIUM_ACCESS_TOKEN missing; no lease/exit/Joyn test performed.")
                return 2
            if not args.preflight:
                control.headers["Authorization"] = "Bearer " + token
            for country in args.countries:
                seen = set()
                for attempt in range(1, (1 if args.preflight else args.leases) + 1):
                    print(f"Requesting {country} lease {attempt} ...", flush=True)
                    r = request(control, "POST", BASE + "/connection/connect-proxy", log, f"lease-{country}-{attempt}",
                                json={"country": country, "ip_type": "residential", "reset_connection": True, "os_type": "android"})
                    row = {"country": country, "attempt": attempt, "lease_http_status": r.status_code,
                           "vpn_detected": "UNKNOWN", "classification": "LEASE_FAILED"}
                    if r.ok and not args.preflight:
                        try:
                            lease = parse_lease(r.json())
                        except (ValueError, TypeError, AttributeError) as exc:
                            row["classification"] = "LEASE_SCHEMA_OR_LIMIT_ERROR"
                            rows.append(row)
                            log.save(f"result-{country}-{attempt}", row)
                            raise ValueError("Invalid lease response or limit; see masked response") from exc
                        row["lease_metadata"] = lease_metadata(r.json(), lease)
                        print(json.dumps(redactor.clean({"MYSTERIUM_LEASE": lease, "requested_country": country,
                                                        "metadata": row["lease_metadata"]})), flush=True)
                        row.update(run_lease(lease, country, log, setting("JOYN_API_KEY_" + country), args.fallback_tls_port))
                        ip = row.get("exit_ip")
                        row["duplicate_exit"] = bool(ip and ip in seen)
                        if ip:
                            seen.add(ip)
                    rows.append(row)
                    log.save(f"result-{country}-{attempt}", row)
                    print(json.dumps(redactor.clean(row)), flush=True)
                    if r.status_code in (401, 403, 429) and not args.preflight:
                        raise ValueError("Mysterium authentication/subscription/rate limit; scan stopped")
                    time.sleep(0.35)
        except Exception as exc:
            log.save("stopped", {"error": type(exc).__name__})
            print(f"Stopped: {type(exc).__name__}; see masked response evidence.", flush=True)
        finally:
            log.save("results", {"utc": datetime.now(timezone.utc).isoformat(), "preflight": args.preflight, "rows": rows})
            fields = ["country", "attempt", "exit_ip", "exit_country", "proxy_host", "proxy_port", "latency_ms",
                      "token", "graphql", "entitlement", "vpn_detected", "classification", "stable_ip", "duplicate_exit"]
            with (directory / "results.csv").open("w", newline="", encoding="utf-8") as f:
                writer = csv.DictWriter(f, fieldnames=fields, extrasaction="ignore")
                writer.writeheader()
                writer.writerows(redactor.clean(rows))
            print("Country | Exit IP | Latency ms | Joyn API | Entitlement | VPN detected | Result")
            for row in rows:
                print(" | ".join(str(row.get(k, "NOT_RUN")) for k in
                                 ["country", "exit_ip", "latency_ms", "graphql", "entitlement", "vpn_detected", "classification"]))
    return 0 if rows and all(r.get("classification") == "ACCEPTED" for r in rows) else 2


if __name__ == "__main__":
    raise SystemExit(main())
