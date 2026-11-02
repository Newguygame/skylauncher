#!/usr/bin/env python3
"""SkyLauncher 3.1.3 CurseForge relay. Python 3.10+, stdlib only.

Direct HTTPS endpoint for Pelican; the CurseForge API key is never returned to clients.
"""
import hmac
import json
import logging
import os
import re
import socket
import ssl
import threading
import time
import urllib.error
import urllib.parse
import urllib.request
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

API_ROOT = "https://api.curseforge.com/v1"
AGENT = "SkyLauncher-CurseForge-Bridge/3.1.3"
MAX_FILE_BYTES = 512 * 1024 * 1024
MAX_RESPONSE_BYTES = 4 * 1024 * 1024
ID_PATTERN = re.compile(r"^[1-9][0-9]{0,12}$")
SLUG_PATTERN = re.compile(r"^[a-zA-Z0-9][a-zA-Z0-9_-]{0,100}$")
CLASS_IDS = {"mod": 6, "modpack": 4471}
LOADER_IDS = {"forge": 1, "fabric": 4, "quilt": 5, "neoforge": 6}
LOADER_LABELS = {"forge": "Forge", "fabric": "Fabric", "quilt": "Quilt", "neoforge": "NeoForge"}
LOGGER = logging.getLogger("skycf")

class RelayError(Exception):
    def __init__(self, code: int, message: str):
        super().__init__(message)
        self.code = code
        self.message = message


def valid_id(value: str) -> int:
    if not ID_PATTERN.fullmatch(value):
        raise RelayError(400, "Invalid numeric CurseForge ID")
    return int(value)


def valid_game_version(value: str) -> str:
    if not re.fullmatch(r"[0-9]{1,3}(?:\.[0-9A-Za-z_-]{1,20}){0,4}", value):
        if value:
            raise RelayError(400, "Invalid Minecraft version")
    return value


def valid_loader(value: str) -> str:
    if value and value not in LOADER_IDS:
        raise RelayError(400, "Invalid mod loader")
    return value


def allowed_cdn(url: str) -> bool:
    try:
        parsed = urllib.parse.urlsplit(url)
        return (parsed.scheme == "https" and parsed.hostname is not None
            and (parsed.hostname == "forgecdn.net" or parsed.hostname.endswith(".forgecdn.net"))
            and parsed.port in (None, 443)
            and not parsed.username and not parsed.password
            and parsed.path.startswith("/files/"))
    except ValueError:
        return False


class SafeCdnRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        if not allowed_cdn(newurl):
            raise RelayError(502, "CurseForge CDN redirected outside approved hosts")
        return super().redirect_request(req, fp, code, msg, headers, newurl)


def cf_api(path: str, api_key: str):
    request = urllib.request.Request(API_ROOT + path, headers={
        "x-api-key": api_key, "Accept": "application/json", "User-Agent": AGENT
    })
    try:
        with urllib.request.urlopen(request, timeout=25) as r:
            body = r.read(MAX_RESPONSE_BYTES + 1)
        if len(body) > MAX_RESPONSE_BYTES:
            raise RelayError(502, "CurseForge API response too large")
        return json.loads(body).get("data")
    except urllib.error.HTTPError as ex:
        if ex.code in (401, 403):
            raise RelayError(502, "CurseForge rejected the VPS API key or access permission") from None
        if ex.code == 404:
            raise RelayError(404, "CurseForge project or file not found") from None
        if ex.code == 429:
            raise RelayError(429, "CurseForge rate limit reached") from None
        raise RelayError(502, f"CurseForge API returned {ex.code}") from None
    except (OSError, ValueError) as ex:
        raise RelayError(502, "Cannot reach CurseForge API") from ex


def cf_query(filters):
    return urllib.parse.urlencode(filters)


def gamefile_matches(file, version, loader):
    versions = [str(x).lower() for x in file.get("gameVersions", []) if isinstance(x, str)]
    if version and version.lower() not in versions:
        return False
    if loader:
        expected = LOADER_LABELS[loader].lower()
        # Some CurseForge files are marked with both Forge and NeoForge. Check exact tags.
        if expected not in versions:
            if loader == "quilt" and "fabric" in versions:
                return True
            return False
    return True


def simplify_file(file):
    hashes = file.get("hashes") or []
    sha1 = next((h.get("value") for h in hashes if h.get("algo") == 1), None)
    required = [d.get("modId") for d in (file.get("dependencies") or []) if d.get("relationType") == 3 and isinstance(d.get("modId"), int)]
    return {"id": file.get("id"), "modId": file.get("modId"), "name": file.get("fileName"),
            "date": file.get("fileDate"), "releaseType": file.get("releaseType", 1),
            "sha1": sha1, "size": file.get("fileLength"), "required": required,
            "downloadable": file.get("isAvailable") is not False}


class CFClient:
    def __init__(self, api_key):
        self.api_key = api_key

    def search(self, query, version="", loader="", slug="", kind="mod"):
        if kind not in CLASS_IDS:
            raise RelayError(400, "Invalid project type")
        params = {"gameId": 432, "classId": CLASS_IDS[kind], "pageSize": 25, "sortField": 12}
        if query:
            params["searchFilter"] = query[:100]
        if slug:
            params["slug"] = slug
        if version:
            params["gameVersion"] = version
        if loader:
            params["modLoaderType"] = LOADER_IDS[loader]
        data = cf_api("/mods/search?" + cf_query(params), self.api_key) or []
        return [{"id": o.get("id"), "name": o.get("name"), "slug": o.get("slug"),
                 "summary": o.get("summary") or "", "downloads": o.get("downloadCount") or 0}
                for o in data if isinstance(o, dict) and isinstance(o.get("id"), int)
                and o.get("allowModDistribution") is not False and o.get("isAvailable") is not False]

    def get_mod(self, mid, kind="mod"):
        if kind not in CLASS_IDS:
            raise RelayError(400, "Invalid project type")
        data = cf_api(f"/mods/{mid}", self.api_key) or {}
        if data.get("gameId") != 432 or (data.get("classId") is not None and data["classId"] != CLASS_IDS[kind]):
            raise RelayError(404, "Not a Minecraft " + kind)
        if data.get("allowModDistribution") is False:
            raise RelayError(403, "This author does not permit third-party distribution")
        if data.get("isAvailable") is False:
            raise RelayError(403, "This CurseForge project is unavailable")
        return {"id": data.get("id"), "name": data.get("name"), "slug": data.get("slug")}

    def files(self, mid, version, loader, kind="mod"):
        self.get_mod(mid, kind=kind)
        query = {"pageSize": 50}
        if version:
            query["gameVersion"] = version
        # CurseForge's files endpoint doesn't reliably filter NeoForge/Quilt,
        # so filter exact loader tags locally instead of risking incorrect jars.
        raw = cf_api(f"/mods/{mid}/files?" + cf_query(query), self.api_key) or []
        suffix = ".zip" if kind == "modpack" else ".jar"
        values = [simplify_file(f) for f in raw if isinstance(f, dict) and
                  isinstance(f.get("id"), int) and str(f.get("fileName", "")).lower().endswith(suffix) and
                  (kind == "modpack" or gamefile_matches(f, version, loader)) and
                  (not version or version in f.get("gameVersions", [])) and
                  f.get("isAvailable") is not False]
        values.sort(key=lambda f: f.get("date") or "", reverse=True)
        return values

    def file_info(self, mid, fid):
        file = cf_api(f"/mods/{mid}/files/{fid}", self.api_key) or {}
        if file.get("modId") != mid or file.get("gameId") != 432 or file.get("id") != fid:
            raise RelayError(404, "File does not belong to the Minecraft project")
        filename = str(file.get("fileName", "")).lower()
        kind = "modpack" if filename.endswith(".zip") else "mod"
        if not filename.endswith((".jar", ".zip")):
            raise RelayError(403, "Unsupported CurseForge file type")
        self.get_mod(mid, kind)
        if file.get("isAvailable") is False:
            raise RelayError(403, "Author has not made this file available")
        return file

    def download(self, mid, fid):
        file = self.file_info(mid, fid)
        length = file.get("fileLength")
        if isinstance(length, int) and length > MAX_FILE_BYTES:
            raise RelayError(413, "Mod exceeds bridge size limit")
        url = file.get("downloadUrl")
        if not url:
            url = cf_api(f"/mods/{mid}/files/{fid}/download-url", self.api_key)
        if not url or not allowed_cdn(url):
            raise RelayError(403, "CurseForge has not made this file downloadable through the API")
        req = urllib.request.Request(url, headers={"x-api-key": self.api_key, "User-Agent": AGENT})
        opener = urllib.request.build_opener(SafeCdnRedirect())
        try:
            upstream = opener.open(req, timeout=60)
            if upstream.status != 200:
                upstream.close()
                raise RelayError(502, "CurseForge CDN did not return a complete file")
            return upstream, file
        except urllib.error.HTTPError as ex:
            if ex.code in (401, 403):
                raise RelayError(403, "CurseForge CDN refused access to this file") from None
            raise RelayError(502, f"CurseForge CDN returned {ex.code}") from None
        except OSError as ex:
            raise RelayError(502, "Unable to download from CurseForge CDN") from ex


class RequestLimiter:
    def __init__(self, limit=90, period=60):
        self.calls = {}
        self.limit = limit
        self.period = period
        self.lock = threading.Lock()

    def allow(self, key):
        with self.lock:
            now = time.monotonic()
            times = [v for v in self.calls.get(key, []) if now - v < self.period]
            if len(times) >= self.limit:
                self.calls[key] = times
                return False
            times.append(now)
            self.calls[key] = times
            if len(self.calls) > 1000:
                self.calls = {k: v for k,v in self.calls.items() if v and now-v[-1] < self.period}
            return True


class Handler(BaseHTTPRequestHandler):
    server_version = "SkyForgeBridge/3.1.3"
    def log_message(self, format, *args):
        # Never log authorization header or CurseForge API key.
        LOGGER.info("%s %s", self.client_address[0], format % args)

    def send_json(self, code, payload):
        raw = json.dumps(payload, separators=(",", ":")).encode("utf8")
        self.send_response(code)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(raw)))
        self.send_header("Cache-Control", "no-store")
        self.send_header("X-Content-Type-Options", "nosniff")
        self.end_headers()
        self.wfile.write(raw)

    def do_GET(self):
        try:
            if not self.server.api_key:
                raise RelayError(503, "Bridge is not configured")
            url = urllib.parse.urlsplit(self.path)
            if url.path == "/health":
                return self.send_json(200, {"ok": True, "service": "SkyLauncher CurseForge relay", "version": "3.1.3"})
            if not self.server.public_access:
                supplied = self.headers.get("Authorization", "")
                if not self.server.access_token or not hmac.compare_digest(supplied, "Bearer " + self.server.access_token):
                    raise RelayError(401, "Invalid or missing bridge access token")
            if not self.server.limiter.allow(self.client_address[0]):
                raise RelayError(429, "Too many bridge requests")
            # Independently limit downloads to prevent anonymous bandwidth abuse.
            if re.fullmatch(r"/v1/download/[0-9]+/[0-9]+", url.path):
                if not self.server.download_limiter.allow(self.client_address[0]):
                    raise RelayError(429, "Download limit reached; retry later")
            args = urllib.parse.parse_qs(url.query, keep_blank_values=True)
            arg = lambda name: (args.get(name) or [""])[0]
            version = valid_game_version(arg("version"))
            loader = valid_loader(arg("loader"))
            if url.path == "/v1/search":
                q = arg("q").strip()
                if len(q) < 2 or len(q) > 100:
                    raise RelayError(400, "Search needs 2–100 characters")
                return self.send_json(200, {"data": self.server.cf.search(q, version, loader)})
            if url.path == "/v1/packs/search":
                q = arg("q").strip()
                if len(q) < 2 or len(q) > 100:
                    raise RelayError(400, "Search needs 2–100 characters")
                return self.send_json(200, {"data": self.server.cf.search(q, version, loader, kind="modpack")})
            if url.path == "/v1/packs/resolve":
                slug = arg("slug")
                if not SLUG_PATTERN.fullmatch(slug):
                    raise RelayError(400, "Invalid CurseForge slug")
                results = self.server.cf.search("", slug=slug, kind="modpack")
                exact = next((r for r in results if r["slug"] == slug), None)
                if exact is None:
                    raise RelayError(404, "No CurseForge modpack found for slug")
                return self.send_json(200, exact)
            if url.path == "/v1/resolve":
                slug = arg("slug")
                if not SLUG_PATTERN.fullmatch(slug):
                    raise RelayError(400, "Invalid CurseForge slug")
                results = self.server.cf.search("", slug=slug)
                exact = next((r for r in results if r["slug"] == slug), None)
                if exact is None:
                    raise RelayError(404, "No Minecraft mod found for slug")
                return self.send_json(200, exact)
            match = re.fullmatch(r"/v1/mods/([0-9]+)(?:/files)?", url.path)
            if match:
                mid = valid_id(match.group(1))
                if url.path.endswith("/files"):
                    return self.send_json(200, {"data": self.server.cf.files(mid, version, loader)})
                return self.send_json(200, self.server.cf.get_mod(mid))
            match = re.fullmatch(r"/v1/packs/([0-9]+)(?:/files)?", url.path)
            if match:
                mid = valid_id(match.group(1))
                if url.path.endswith("/files"):
                    return self.send_json(200, {"data": self.server.cf.files(mid, version, loader, kind="modpack")})
                return self.send_json(200, self.server.cf.get_mod(mid, kind="modpack"))
            match = re.fullmatch(r"/v1/mods/([0-9]+)/files/([0-9]+)", url.path)
            if match:
                mid, fid = valid_id(match.group(1)), valid_id(match.group(2))
                return self.send_json(200, simplify_file(self.server.cf.file_info(mid, fid)))
            match = re.fullmatch(r"/v1/download/([0-9]+)/([0-9]+)", url.path)
            if match:
                mid, fid = valid_id(match.group(1)), valid_id(match.group(2))
                if not self.server.download_slots.acquire(blocking=False):
                    raise RelayError(503, "Too many simultaneous downloads")
                try:
                    upstream, info = self.server.cf.download(mid, fid)
                    with upstream:
                        self.send_response(200)
                        zip_file = str(info.get("fileName", "")).lower().endswith(".zip")
                        self.send_header("Content-Type", "application/zip" if zip_file else "application/java-archive")
                        self.send_header("Content-Disposition", "attachment; filename=\"" + ("modpack.zip" if zip_file else "mod.jar") + "\"")
                        self.send_header("Cache-Control", "no-store")
                        self.send_header("X-Content-Type-Options", "nosniff")
                        self.end_headers()
                        total = 0
                        while True:
                            chunk = upstream.read(128 * 1024)
                            if not chunk:
                                break
                            total += len(chunk)
                            if total > MAX_FILE_BYTES:
                                LOGGER.warning("mod exceeded size limit while streaming")
                                self.close_connection = True
                                break
                            self.wfile.write(chunk)
                        return
                finally:
                    self.server.download_slots.release()
            raise RelayError(404, "Unknown endpoint")
        except RelayError as ex:
            try: self.send_json(ex.code, {"error": ex.message})
            except (OSError, ConnectionResetError): pass
        except (BrokenPipeError, ConnectionResetError):
            pass
        except Exception:
            LOGGER.exception("Unhandled relay error")
            try: self.send_json(500, {"error": "Internal relay error"})
            except (OSError, ConnectionResetError): pass


class Server(ThreadingHTTPServer):
    daemon_threads = True
    def __init__(self, addr, cf, api_key, token="", public_access=True,
                 requests_per_minute=300, downloads_per_hour=3000, simultaneous_downloads=4):
        super().__init__(addr, Handler)
        self.cf = cf
        self.api_key = api_key
        self.access_token = token
        self.public_access = public_access
        self.limiter = RequestLimiter(limit=requests_per_minute, period=60)
        self.download_limiter = RequestLimiter(limit=downloads_per_hour, period=3600)
        self.download_slots = threading.BoundedSemaphore(simultaneous_downloads)


def load_settings(config_path=None, environ=None):
    """Read config.json alongside server.py; environment overrides provided values.

    No external packages required. This file is intentionally never served by
    the HTTP handler (which uses an explicit route allowlist).
    """
    from pathlib import Path
    if config_path is None:
        config_path = Path(__file__).resolve().with_name("config.json")
    else:
        config_path = Path(config_path)
    if environ is None:
        environ = os.environ
    values = {}
    if config_path.is_file():
        try:
            values = json.loads(config_path.read_text(encoding="utf-8-sig"))
        except (OSError, ValueError) as exc:
            raise ValueError("Cannot read config.json. Check the JSON formatting and file permissions") from exc
        if not isinstance(values, dict):
            raise ValueError("config.json must contain a JSON object")
    else:
        LOGGER.warning("config.json not found alongside server.py; checking environment variables")

    def setting(name, default=""):
        envval = environ.get(name)
        if envval is not None and str(envval).strip():
            return str(envval).strip()
        value = values.get(name, "")
        return str(value).strip() if value is not None and str(value).strip() else default

    api_key = setting("CURSEFORGE_API_KEY")
    token = setting("SKYLAUNCHER_ACCESS_TOKEN")
    host = setting("SKYLAUNCHER_BIND", "127.0.0.1")
    # Pelican/Pterodactyl normally exposes the allocated port as SERVER_PORT.
    port_str = setting("SKYLAUNCHER_PORT", "") or str(environ.get("SERVER_PORT") or environ.get("PORT") or "8767")
    try:
        port = int(port_str)
        if not 1 <= port <= 65535:
            raise ValueError()
    except ValueError:
        raise ValueError("SKYLAUNCHER_PORT must be a valid TCP port (1–65535)") from None
    if len(api_key) < 12:
        raise ValueError("Set CURSEFORGE_API_KEY in config.json (or an environment variable)")
    public_access = setting("SKYLAUNCHER_PUBLIC_ACCESS", "true").lower() in ("1", "true", "yes", "on")
    if not public_access and len(token) < 24:
        raise ValueError("Private mode requires a SKYLAUNCHER_ACCESS_TOKEN of at least 24 characters")
    if token and any(ch.isspace() for ch in token):
        raise ValueError("SKYLAUNCHER_ACCESS_TOKEN must not contain whitespace")
    def bounded_limit(name, default, maximum):
        raw = setting(name, str(default))
        try:
            value = int(raw)
        except ValueError:
            raise ValueError(name + " must be a whole number") from None
        if not 1 <= value <= maximum:
            raise ValueError(name + f" must be between 1 and {maximum}")
        return value

    limits = {
        "requests_per_minute": bounded_limit("SKYLAUNCHER_REQUESTS_PER_MINUTE", 300, 1200),
        "downloads_per_hour": bounded_limit("SKYLAUNCHER_DOWNLOADS_PER_HOUR", 3000, 10000),
        "simultaneous_downloads": bounded_limit("SKYLAUNCHER_SIMULTANEOUS_DOWNLOADS", 4, 16),
    }
    return api_key, token, host, port, public_access, limits


def main():
    logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(message)s")
    try:
        api_key, token, host, port, public_access, limits = load_settings()
    except ValueError as exc:
        raise SystemExit(str(exc)) from None
    srv = Server((host, port), CFClient(api_key), api_key, token, public_access, **limits)
    # Important: TLS is mandatory on the public Pelican allocation. Never fall back to HTTP.
    from pathlib import Path
    service_dir = Path(__file__).resolve().parent
    certificate = service_dir / "bridge-cert.pem"
    private_key = service_dir / "bridge-key.pem"
    if not certificate.is_file() or not private_key.is_file():
        raise SystemExit("Missing bridge-cert.pem or bridge-key.pem alongside server.py. HTTPS is required.")
    context = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
    context.minimum_version = ssl.TLSVersion.TLSv1_2
    try:
        context.load_cert_chain(str(certificate), str(private_key))
        srv.socket = context.wrap_socket(srv.socket, server_side=True)
    except (ssl.SSLError, OSError) as exc:
        raise SystemExit("Unable to enable VPS HTTPS: check certificate and key files") from exc
    LOGGER.info("CurseForge relay listening securely (HTTPS) at %s:%s (public=%s)", host, port, public_access)
    LOGGER.info("Limits: %s requests/min/IP, %s downloads/hour/IP, %s simultaneous downloads", 
                limits["requests_per_minute"], limits["downloads_per_hour"], limits["simultaneous_downloads"])
    try:
        srv.serve_forever()
    except KeyboardInterrupt:
        pass
    finally:
        srv.server_close()

if __name__ == "__main__":
    main()
