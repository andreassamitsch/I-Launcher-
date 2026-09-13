"""Local protocol fixtures, not evidence about real Mysterium/Joyn exits."""
import base64
import json
from pathlib import Path
import socket
import tempfile
import threading
import unittest
from unittest.mock import patch

import requests

import test_mysterium_proxy as probe


class FixtureAdapter(requests.adapters.BaseAdapter):
    def __init__(self, entitlement_status=200, entitlement_body=None):
        self.calls = []
        self.entitlement_status = entitlement_status
        self.entitlement_body = entitlement_body or {"entitlement_token": "fixture-entitlement-secret"}

    def send(self, request, **kwargs):
        self.calls.append((request, kwargs))
        r = requests.Response()
        r.status_code = 200
        r.request, r.url = request, request.url
        if "/auth/anonymous" in request.url:
            body = {"access_token": "fixture-access-secret", "token_type": "Bearer"}
        elif "/graphql" in request.url:
            body = {"data": {"liveStreams": [{"id": "paid", "markings": ["PLUS"]},
                                             {"id": "free", "markings": []}]}}
        elif "/entitlement-token" in request.url:
            body, r.status_code = self.entitlement_body, self.entitlement_status
        elif "bundle.js" in request.url:
            body = 'API_GW_API_KEY: {value:"fixture-api-key"}; const errors = "VPN_DETECTED Geoblock Forbidden"'
        else:
            body = '<script src="/bundle.js"></script>'
        r._content = (json.dumps(body) if isinstance(body, dict) else body).encode()
        return r

    def close(self):
        pass


class ProxyTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.log = probe.Log(Path(self.temp.name), probe.Redactor())

    def test_lease_aliases_and_no_guessed_metadata(self):
        p = probe.parse_lease({"proxyConfig": {"host": "proxy.example", "port": "8080", "expiresAt": ""}})
        self.assertEqual(p["port"], 8080)
        self.assertEqual(probe.lease_metadata({}, p)["api_fields"], {})
        for body in ({"limit_exceeded": True}, {}, {"proxy_config": {"host": "x", "port": 0}}):
            with self.assertRaises(ValueError):
                probe.parse_lease(body)

    def test_secret_redaction_in_nested_json_and_echoed_errors(self):
        data = {"proxy_config": {"username": "fixture-user", "password": "fixture-password"},
                "access_token": "fixture-token", "message": "fixture-password fixture-token"}
        self.log.save("lease", data)
        saved = next(Path(self.temp.name).glob("*.json")).read_text()
        for secret in ("fixture-user", "fixture-password", "fixture-token"):
            self.assertNotIn(secret, saved)
        self.assertIn('"proxy_config"', saved)

    def test_status_labels_are_not_learned_as_secrets(self):
        value = {"token": "OK", "entitlement": "HTTP_400", "graphql": "OK", "config": "OK"}
        self.log.save("result", value)
        self.assertEqual(self.log.redactor.clean(value), value)

    def test_observed_geo_error(self):
        result = self.workflow(FixtureAdapter(400, {"code": "ENT_AssetNotAvailableInCountry"}))
        self.assertEqual(result["classification"], "GEO_BLOCKED")
        self.assertEqual(result["vpn_detected"], "UNKNOWN")

    def test_connect_wire_bytes_and_auth_on_each_new_tcp_connection(self):
        server = socket.socket()
        server.bind(("127.0.0.1", 0))
        server.listen()
        server.settimeout(3)
        self.addCleanup(server.close)
        observed = []
        def serve():
            for status in (200, 200, 407):
                with server.accept()[0] as client:
                    client.settimeout(3)
                    data = b""
                    while not data.endswith(b"\r\n\r\n"):
                        data += client.recv(4096)
                    observed.append(data)
                    client.sendall(f"HTTP/1.1 {status} Fixture\r\n\r\n".encode())
        worker = threading.Thread(target=serve, daemon=True)
        worker.start()
        lease = {"host": "127.0.0.1", "port": server.getsockname()[1], "username": "u:ser", "password": "p@ss"}
        results = [probe.connect_probe(lease, False) for _ in range(3)]
        worker.join(4)
        self.assertFalse(worker.is_alive())
        self.assertEqual([r.get("status") for r in results], [200, 200, 407])
        self.assertFalse(results[-1]["ok"])
        for wire in observed:
            self.assertTrue(wire.startswith(b"CONNECT api.joyn.de:443 HTTP/1.1\r\n"))
            expected = b"Proxy-Authorization: Basic " + base64.b64encode(b"u:ser:p@ss")
            self.assertIn(expected + b"\r\n", wire)
            self.assertIn(b"Proxy-Connection: Keep-Alive\r\n", wire)

    def test_proxy_url_escapes_credentials(self):
        value = probe.proxy_url({"host": "::1", "port": 8080, "username": "a@b", "password": "a:/?#%"}, "http")
        self.assertEqual(value, "http://a%40b:a%3A%2F%3F%23%25@[::1]:8080")

    def workflow(self, adapter):
        proxy = "http://user:password@127.0.0.1:12345"
        with probe.session(proxy) as s:
            s.headers["Connection"] = "close"
            s.mount("https://", adapter)
            result = probe.joyn(s, "CH", self.log)
            self.assertFalse(s.trust_env)
        for req, kwargs in adapter.calls:
            self.assertEqual(kwargs["proxies"]["https"], proxy)
            self.assertTrue(kwargs["verify"])
            self.assertEqual(req.headers["Connection"], "close")
        return result

    def test_joyn_three_graphql_requests_same_proxy_and_correct_tenant(self):
        adapter = FixtureAdapter()
        result = self.workflow(adapter)
        self.assertEqual(result["classification"], "ACCEPTED")
        self.assertEqual(result["channel"], "free")
        self.assertEqual(result["graphql_requests_ok"], 3)
        self.assertEqual(result["vpn_detected"], "NO")
        for request, _ in adapter.calls:
            if "graphql" in request.url or "anonymous" in request.url:
                self.assertEqual(request.headers["Joyn-Distribution-Tenant"], "JOYN_CH")
        evidence = "".join(p.read_text() for p in Path(self.temp.name).glob("*.json"))
        self.assertNotIn("fixture-access-secret", evidence)
        self.assertNotIn("fixture-entitlement-secret", evidence)
        self.assertNotIn("fixture-api-key", evidence)

    def test_vpn_and_generic_forbidden_are_distinct(self):
        vpn = self.workflow(FixtureAdapter(403, {"code": "ENT_USER_VPN_DETECTED"}))
        self.assertEqual(vpn["vpn_detected"], "YES")
        forbidden = self.workflow(FixtureAdapter(403, {"message": "Forbidden"}))
        self.assertEqual(forbidden["vpn_detected"], "UNKNOWN")
        self.assertEqual(forbidden["classification"], "ACCESS_DENIED_UNSPECIFIED")

    def test_proxy_failure_never_attempts_direct_target(self):
        with probe.session("http://127.0.0.1:12345") as s:
            with patch("urllib3.util.connection.create_connection", side_effect=OSError("fixture refusal")) as connect:
                with self.assertRaises(requests.exceptions.ProxyError):
                    s.get("https://api.joyn.de/graphql", timeout=1)
                self.assertGreater(connect.call_count, 0)
                for call in connect.call_args_list:
                    self.assertEqual(call.args[0], ("127.0.0.1", 12345))


if __name__ == "__main__":
    unittest.main()
