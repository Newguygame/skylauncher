#!/usr/bin/env python3
"""
GH Mod Launcher - crude CurseForge-style launcher: instances (mod profiles), GitHub mods/packs,
and a built-in game runner.
Stdlib only (Python 3.9+, 3.11+ recommended for Forge/NeoForge dependency parsing).

Mods:     pulls jars from GitHub releases, matches loader + MC version, resolves dependencies,
          verifies sha256 (GitHub digest / sidecar / checksums file, else trust-on-first-use).
Packs:    installs full modpacks from GitHub repos that contain mods/ (+ config/, etc.)
          via zipball download. Built-in one-click install for Fathom Studios pack.
Runtime:  installs vanilla + Fabric / Quilt / Forge / NeoForge, fetches Mojang's Java runtime,
          downloads libraries + assets, builds the command line and starts the game.
Auth:     Microsoft account (device-code flow) for online play, or offline username.
          Tokens refresh automatically. Prefer "Sync to official launcher" if you want
          the official launcher's own Microsoft login instead.
Profiles: Multiple independent instances (MC version, loader, mods, settings).
"""
import hashlib
import json
import os
import platform
import re
import shlex
import shutil
import subprocess
import sys
import tempfile
import threading
import time
import uuid
import zipfile
from concurrent.futures import ThreadPoolExecutor, as_completed
from datetime import datetime, timezone
import tkinter as tk
from tkinter import ttk, messagebox, simpledialog
import urllib.parse
import urllib.request
import urllib.error

try:
    import tomllib
except ImportError:  # Python < 3.11
    tomllib = None

API = "https://api.github.com"
HOME = os.path.expanduser("~")
BASE = os.path.join(HOME, ".ghmodlauncher")
CONFIG_PATH = os.path.join(BASE, "config.json")
OLD_CONFIG = os.path.join(HOME, ".ghmodlauncher.json")

LOADERS = ("fabric", "quilt", "forge", "neoforge")
BAD_NAME_PARTS = ("sources", "javadoc", "-dev", "deobf")
SKIP_DEPS = {"minecraft", "java", "forge", "neoforge", "fabricloader", "fabric-loader",
             "quilt_loader", "quilt_base", "fml", "javafml", "lowcodefml", "mixinextras"}

# Launcher self-update (GitHub releases). Set "update_repo" in config.json or edit below.
LAUNCHER_VERSION = "1.1.1"
DEFAULT_UPDATE_REPO = "Newguygame/skylauncher"

# Built-in pack: Fathom Studios (NeoForge 1.21.1)
FATHOM_PACK = {
    "repo": "Newguygame/fathom-studios-mc-pack",
    "ref": "main",
    "name": "Fathom Studios",
    "mc_version": "1.21.1",
    "loader": "neoforge",
}

# Folders copied from a pack repo into the instance game dir
PACK_FOLDERS = ("mods", "config", "imported_world_presets", "resourcepacks",
                "shaderpacks", "datapacks", "kubejs", "scripts", "defaultconfigs")

# Best effort mod-id -> GitHub repo. Edit/extend via "dep_map" in the config file.
BUILTIN_DEP_MAP = {
    "fabric-api": "FabricMC/fabric",
    "fabric": "FabricMC/fabric",
    "fabric-language-kotlin": "FabricMC/fabric-language-kotlin",
    "cloth-config": "shedaniel/cloth-config",
    "cloth-config2": "shedaniel/cloth-config",
    "architectury": "architectury/architectury-api",
    "geckolib": "bernie-g/geckolib",
    "owo": "wisp-forest/owo-lib",
    "yet_another_config_lib_v3": "isXander/YetAnotherConfigLib",
    "modmenu": "TerraformersMC/ModMenu",
    "kotlinforforge": "thedarkcolour/KotlinForForge",
}

# MC versions 1.14+; used to skip builds that are clearly for a *different* MC version
OTHER_MC_RE = re.compile(r"(?<![\d.])1\.(?:1[4-9]|2\d)(?:\.\d+)?(?!\d)")

# ---- runtime constants
MANIFEST_URL = "https://piston-meta.mojang.com/mc/game/version_manifest_v2.json"
JAVA_ALL_URL = "https://launchermeta.mojang.com/v1/products/java-runtime/2ec0cc96c44e5a76b9c8b7c39df7210883d12871/all.json"
FORGE_PROMOS = "https://files.minecraftforge.net/net/minecraftforge/forge/promotions_slim.json"
OS_NAME = {"Windows": "windows", "Darwin": "osx"}.get(platform.system(), "linux")
_M = platform.machine().lower()
ARCH = "arm64" if _M in ("arm64", "aarch64") else ("x86" if _M in ("i386", "i686", "x86") else "x86_64")
CP_SEP = ";" if OS_NAME == "windows" else ":"
NO_WINDOW = 0x08000000 if OS_NAME == "windows" else 0

# Microsoft / Minecraft auth (public client; override via config "msa_client_id")
# Minecraft Console Client's registered public client — device-code + XboxLive.signin.
# First-party IDs (Azure CLI, etc.) are rejected: "users are not permitted to consent
# to first party applications".
MSA_CLIENT_ID = "54473e32-df8f-42e9-a649-9419b0dab9d3"
MSA_DEVICE_CODE_URL = "https://login.microsoftonline.com/consumers/oauth2/v2.0/devicecode"
MSA_TOKEN_URL = "https://login.microsoftonline.com/consumers/oauth2/v2.0/token"
MSA_SCOPE = "XboxLive.signin offline_access openid profile"
XBL_AUTH_URL = "https://user.auth.xboxlive.com/user/authenticate"
XSTS_AUTH_URL = "https://xsts.auth.xboxlive.com/xsts/authorize"
MC_LOGIN_URL = "https://api.minecraftservices.com/authentication/login_with_xbox"
MC_PROFILE_URL = "https://api.minecraftservices.com/minecraft/profile"
MC_ENTITLEMENTS_URL = "https://api.minecraftservices.com/entitlements/mcstore"


# ================================================================= config
def default_instance(name, mc="", loader="fabric"):
    return {
        "mc_version": mc,
        "loader": loader,
        "loader_version": "",   # blank = latest stable
        "mods_dir": os.path.join(BASE, "instances", name, ".minecraft", "mods"),
        "java_path": "",        # blank = Mojang's runtime for this MC version
        "memory_mb": "4096",
        "jvm_args": "",
        "launch_cmd": "",       # optional external launcher; blank = built-in runner
        "version_id": "",       # filled in when the loader is installed
        "installed_key": "",
        "mods": {},             # repo -> {tag, file, sha256, ids, loaders, auto, required_by}
        "pack_repo": "",        # if set, instance was installed from this GitHub pack
        "pack_ref": "",
    }


def load_config():
    cfg = {"token": "", "active": "Default", "instances": {}, "dep_map": {},
           "username": "Player", "runtime_dir": os.path.join(BASE, "runtime"),
           "msa_client_id": "", "account": None,
           "update_repo": DEFAULT_UPDATE_REPO, "skip_update_version": ""}
    try:
        with open(CONFIG_PATH) as f:
            cfg.update(json.load(f))
    except (FileNotFoundError, json.JSONDecodeError):
        try:  # migrate v1 config
            with open(OLD_CONFIG) as f:
                old = json.load(f)
            inst = default_instance("Default", old.get("mc_version", ""))
            inst["mods_dir"] = old.get("mods_dir", inst["mods_dir"])
            inst["launch_cmd"] = old.get("launch_cmd", "")
            inst["mods"] = old.get("repos", {})
            cfg["instances"]["Default"] = inst
        except (FileNotFoundError, json.JSONDecodeError):
            pass
    if not cfg["instances"]:
        cfg["instances"]["Default"] = default_instance("Default")
    for n, i in cfg["instances"].items():  # fill fields added in newer versions
        for k, v in default_instance(n).items():
            i.setdefault(k, v)
    if cfg["active"] not in cfg["instances"]:
        cfg["active"] = next(iter(cfg["instances"]))
    return cfg


def save_config(cfg):
    os.makedirs(BASE, exist_ok=True)
    with open(CONFIG_PATH, "w") as f:
        json.dump(cfg, f, indent=2)
    try:
        os.chmod(CONFIG_PATH, 0o600)  # token lives in here
    except OSError:
        pass


# ================================================================= http
class _Redirect(urllib.request.HTTPRedirectHandler):
    """Drop the Authorization header when redirected to another host (GitHub -> CDN)."""
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        new = super().redirect_request(req, fp, code, msg, headers, newurl)
        if new is not None and urllib.parse.urlparse(newurl).netloc != urllib.parse.urlparse(req.full_url).netloc:
            new.remove_header("Authorization")
        return new


OPENER = urllib.request.build_opener(_Redirect)


def http(url, token="", binary=False, accept=None):
    """GitHub API calls. The token is only ever sent to api.github.com."""
    h = {"User-Agent": "ghmodlauncher", "Accept": accept or "application/vnd.github+json"}
    tok = token or os.environ.get("GITHUB_TOKEN", "")
    if tok and urllib.parse.urlparse(url).netloc == "api.github.com":
        h["Authorization"] = f"Bearer {tok}"
    with OPENER.open(urllib.request.Request(url, headers=h), timeout=60) as r:
        data = r.read()
    return data if binary else json.loads(data)


def fetch_bytes(url, timeout=60):
    """Everything that is not GitHub (Mojang, Fabric, Forge, ...). Never sends credentials."""
    req = urllib.request.Request(url, headers={"User-Agent": "ghmodlauncher"})
    with urllib.request.urlopen(req, timeout=timeout) as r:
        return r.read()


def sha256_file(path):
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def sha1_file(path):
    h = hashlib.sha1()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


# ================================================================= self-update
def _parse_version(s):
    """Turn 'v1.2.3' / '1.2' into a comparable tuple of ints."""
    s = (s or "").strip().lstrip("vV")
    parts = re.findall(r"\d+", s)
    return tuple(int(p) for p in parts) if parts else (0,)


def _version_newer(remote, local):
    return _parse_version(remote) > _parse_version(local)


def fetch_latest_launcher_release(repo, token=""):
    """
    Return dict: tag, notes, download_url (direct .py or None), html_url
    or None if no releases / error.
    """
    if not repo or repo.count("/") != 1:
        return None
    try:
        rel = http(f"{API}/repos/{repo}/releases/latest", token)
    except Exception:
        return None
    if rel.get("draft") or not rel.get("tag_name"):
        return None
    tag = rel["tag_name"]
    download_url = None
    # Prefer a .py asset (exact name or any .py)
    assets = rel.get("assets") or []
    py_assets = [a for a in assets if a.get("name", "").endswith(".py")]
    script_name = os.path.basename(os.path.abspath(sys.argv[0])) if sys.argv else "ghmodlauncher.py"
    for a in py_assets:
        if a.get("name") == script_name:
            download_url = a.get("browser_download_url") or a.get("url")
            break
    if not download_url and py_assets:
        a = py_assets[0]
        download_url = a.get("browser_download_url") or a.get("url")
    # Fallback: raw file from the tag (common single-file layout)
    if not download_url:
        # Raw GitHub URLs do not provide a directory listing, so use the canonical
        # launcher filename first, followed by common alternatives. The download
        # is validated before it can replace the current script.
        names = list(dict.fromkeys((script_name, "ghmodlauncher.py", "launcher.py")))
        download_url = f"https://raw.githubusercontent.com/{repo}/{tag}/{names[0]}"
    return {
        "tag": tag,
        "notes": (rel.get("body") or "")[:500],
        "download_url": download_url,
        "html_url": rel.get("html_url") or f"https://github.com/{repo}/releases/tag/{tag}",
        "asset_api_url": next((a.get("url") for a in py_assets if a.get("name") == script_name),
                             py_assets[0].get("url") if py_assets else None),
    }


def download_launcher_update(info, token=""):
    """Download new script bytes. Uses GitHub asset API if needed (for private assets)."""
    url = info.get("download_url") or ""
    # GitHub release asset API needs Accept: application/octet-stream
    if info.get("asset_api_url") and "api.github.com" in (info.get("asset_api_url") or ""):
        return http(info["asset_api_url"], token, binary=True, accept="application/octet-stream")
    if url.startswith("https://api.github.com/"):
        return http(url, token, binary=True, accept="application/octet-stream")
    return fetch_bytes(url)


def apply_launcher_update(new_bytes):
    """
    Replace the running script with new_bytes.
    Writes alongside the current file, then replaces. Returns path written.
    """
    if not new_bytes or len(new_bytes) < 100:
        raise RuntimeError("Downloaded update looks empty or too small")
    # Sanity-check the downloaded source before replacing the current script.
    try:
        source = new_bytes.decode("utf-8-sig")
    except UnicodeDecodeError as exc:
        raise RuntimeError("Downloaded update is not valid UTF-8 Python source") from exc
    head = source[:2000]
    if "GH Mod Launcher" not in head and "import tkinter" not in source[:10000]:
        raise RuntimeError("Downloaded file does not look like this launcher")
    try:
        compile(source, "<launcher-update>", "exec")
    except SyntaxError as exc:
        raise RuntimeError(f"Downloaded update has a Python syntax error: {exc}") from exc
    current = os.path.abspath(sys.argv[0])
    # Never overwrite a packaged executable with Python source. For a normal
    # script install, update that script in place; for a frozen executable,
    # write a standalone .py launcher beside it instead.
    if current.lower().endswith(".py"):
        target = current
    else:
        target = os.path.join(os.path.dirname(current) or ".", "ghmodlauncher.py")
    tmp = target + ".new"
    bak = target + ".bak"
    with open(tmp, "wb") as f:
        f.write(new_bytes)
    try:
        if os.path.exists(target):
            shutil.copy2(target, bak)
        os.replace(tmp, target)
    except Exception:
        try:
            os.unlink(tmp)
        except OSError:
            pass
        raise
    return target


# ================================================================= Microsoft / Minecraft auth
def _json_post(url, body, headers=None, timeout=60):
    data = json.dumps(body).encode("utf-8") if not isinstance(body, (bytes, str)) else (
        body.encode("utf-8") if isinstance(body, str) else body)
    h = {"Content-Type": "application/json", "Accept": "application/json",
         "User-Agent": "ghmodlauncher"}
    if headers:
        h.update(headers)
    req = urllib.request.Request(url, data=data, headers=h, method="POST")
    with urllib.request.urlopen(req, timeout=timeout) as r:
        return json.loads(r.read().decode())


def _form_post(url, fields, timeout=60):
    data = urllib.parse.urlencode(fields).encode("utf-8")
    req = urllib.request.Request(url, data=data, headers={
        "Content-Type": "application/x-www-form-urlencoded",
        "Accept": "application/json",
        "User-Agent": "ghmodlauncher",
    }, method="POST")
    with urllib.request.urlopen(req, timeout=timeout) as r:
        return json.loads(r.read().decode())


def _json_get(url, bearer, timeout=30):
    req = urllib.request.Request(url, headers={
        "Authorization": f"Bearer {bearer}",
        "Accept": "application/json",
        "User-Agent": "ghmodlauncher",
    })
    with urllib.request.urlopen(req, timeout=timeout) as r:
        return json.loads(r.read().decode())


def msa_client_id(cfg):
    return (cfg.get("msa_client_id") or "").strip() or MSA_CLIENT_ID


def msa_device_code_start(client_id):
    """Start device-code flow. Returns dict with user_code, verification_uri, device_code, interval, expires_in."""
    return _form_post(MSA_DEVICE_CODE_URL, {
        "client_id": client_id,
        "scope": MSA_SCOPE,
    })


def msa_device_code_poll(client_id, device_code, interval, expires_in, log, cancel_flag=None):
    """Poll until user finishes login or timeout. Returns MSA token dict (access_token, refresh_token, ...)."""
    deadline = time.time() + max(30, int(expires_in) - 5)
    interval = max(3, int(interval or 5))
    while time.time() < deadline:
        if cancel_flag and cancel_flag():
            raise RuntimeError("Login cancelled")
        time.sleep(interval)
        try:
            tok = _form_post(MSA_TOKEN_URL, {
                "grant_type": "urn:ietf:params:oauth:grant-type:device_code",
                "client_id": client_id,
                "device_code": device_code,
            })
        except urllib.error.HTTPError as e:
            body = e.read().decode(errors="replace")
            try:
                err = json.loads(body)
            except Exception:
                err = {}
            code = err.get("error", "")
            if code in ("authorization_pending", "slow_down"):
                if code == "slow_down":
                    interval += 2
                continue
            if code == "expired_token":
                raise RuntimeError("Device code expired — try logging in again")
            raise RuntimeError(f"MSA token error: {code or e.code} {err.get('error_description', body)[:200]}")
        if "access_token" in tok:
            return tok
    raise RuntimeError("Timed out waiting for Microsoft login")


def msa_refresh(client_id, refresh_token):
    return _form_post(MSA_TOKEN_URL, {
        "grant_type": "refresh_token",
        "client_id": client_id,
        "refresh_token": refresh_token,
        "scope": MSA_SCOPE,
    })


def xbox_live_auth(msa_access_token):
    body = {
        "Properties": {
            "AuthMethod": "RPS",
            "SiteName": "user.auth.xboxlive.com",
            "RpsTicket": "d=" + msa_access_token,
        },
        "RelyingParty": "http://auth.xboxlive.com",
        "TokenType": "JWT",
    }
    r = _json_post(XBL_AUTH_URL, body)
    token = r["Token"]
    uhs = r["DisplayClaims"]["xui"][0]["uhs"]
    return token, uhs


def xsts_auth(xbl_token):
    body = {
        "Properties": {
            "SandboxId": "RETAIL",
            "UserTokens": [xbl_token],
        },
        "RelyingParty": "rp://api.minecraftservices.com/",
        "TokenType": "JWT",
    }
    try:
        r = _json_post(XSTS_AUTH_URL, body)
    except urllib.error.HTTPError as e:
        try:
            err = json.loads(e.read().decode())
        except Exception:
            err = {}
        xerr = err.get("XErr")
        hints = {
            2148916227: "Xbox account is banned",
            2148916233: "No Xbox account — open xbox.com once and create one",
            2148916235: "Xbox Live not available in this country",
            2148916238: "Child account — must be added to a Microsoft Family by an adult",
        }
        raise RuntimeError(hints.get(xerr, f"XSTS failed (XErr={xerr}): {err}")) from e
    return r["Token"], r["DisplayClaims"]["xui"][0]["uhs"]


def mc_login_with_xbox(uhs, xsts_token):
    body = {"identityToken": f"XBL3.0 x={uhs};{xsts_token}", "ensureLegacyEnabled": True}
    r = _json_post(MC_LOGIN_URL, body)
    return r["access_token"], int(r.get("expires_in", 86400))


def mc_profile(mc_token):
    return _json_get(MC_PROFILE_URL, mc_token)


def mc_owns_game(mc_token):
    try:
        r = _json_get(MC_ENTITLEMENTS_URL, mc_token)
        items = r.get("items") or []
        return any(i.get("name") in ("product_minecraft", "game_minecraft",
                                     "product_game_pass_pc", "product_game_pass_ultimate")
                   for i in items)
    except Exception:
        return True  # don't block launch on entitlements blip


def build_account_from_msa(msa_tok, client_id, log):
    """MSA tokens → full account dict with Minecraft access token + profile."""
    log("Authenticating with Xbox Live...")
    xbl, _ = xbox_live_auth(msa_tok["access_token"])
    log("Getting XSTS token...")
    xsts, uhs = xsts_auth(xbl)
    log("Logging into Minecraft services...")
    mc_token, expires_in = mc_login_with_xbox(uhs, xsts)
    if not mc_owns_game(mc_token):
        log("warning: account may not own Minecraft Java (empty entitlements)")
    prof = mc_profile(mc_token)
    uuid_raw = prof["id"]
    # Minecraft profile id is undashed; game expects dashed UUID
    if len(uuid_raw) == 32:
        uuid_fmt = f"{uuid_raw[:8]}-{uuid_raw[8:12]}-{uuid_raw[12:16]}-{uuid_raw[16:20]}-{uuid_raw[20:]}"
    else:
        uuid_fmt = uuid_raw
    now = int(time.time())
    return {
        "type": "msa",
        "name": prof["name"],
        "uuid": uuid_fmt,
        "uuid_nodash": uuid_raw.replace("-", ""),
        "access_token": mc_token,
        "mc_expires_at": now + expires_in - 60,
        "msa_access_token": msa_tok["access_token"],
        "msa_refresh_token": msa_tok["refresh_token"],
        "msa_expires_at": now + int(msa_tok.get("expires_in", 3600)) - 60,
        "client_id": client_id,
        "xuid": "",  # optional
    }


def ensure_fresh_account(cfg, log):
    """
    Return a usable account dict for launching, refreshing tokens if needed.
    Returns None if no Microsoft account is stored (caller uses offline).
    """
    acc = cfg.get("account")
    if not acc or acc.get("type") != "msa":
        return None
    client_id = acc.get("client_id") or msa_client_id(cfg)
    now = int(time.time())
    # Refresh Minecraft token via full chain if expired (or MSA about to expire)
    need_refresh = (acc.get("mc_expires_at") or 0) < now + 120
    if not need_refresh:
        return acc
    log("Refreshing Microsoft / Minecraft tokens...")
    try:
        if (acc.get("msa_expires_at") or 0) < now + 120:
            msa_tok = msa_refresh(client_id, acc["msa_refresh_token"])
        else:
            msa_tok = {
                "access_token": acc["msa_access_token"],
                "refresh_token": acc["msa_refresh_token"],
                "expires_in": max(60, (acc.get("msa_expires_at") or now) - now),
            }
        # Always re-run Xbox→MC when MC token is stale
        if "refresh_token" not in msa_tok:
            msa_tok["refresh_token"] = acc["msa_refresh_token"]
        new_acc = build_account_from_msa(msa_tok, client_id, log)
        cfg["account"] = new_acc
        cfg["username"] = new_acc["name"]
        save_config(cfg)
        log(f"Logged in as {new_acc['name']}")
        return new_acc
    except Exception as e:
        log(f"Token refresh failed: {e}")
        log("Falling back to offline mode for this launch.")
        return None


# ================================================================= packs (GitHub repo with mods/ folder)
def _game_dir_from_mods(mods_dir):
    d = mods_dir.rstrip("/\\")
    return os.path.dirname(d) if os.path.basename(d) == "mods" else d


def download_github_zipball(repo, ref, token, log):
    """Download repo zipball to a temp file. Returns path to the .zip."""
    url = f"{API}/repos/{repo}/zipball/{ref}"
    log(f"Downloading pack {repo}@{ref} (this may take a while)...")
    # Stream to disk so we don't hold a huge zip in memory
    h = {"User-Agent": "ghmodlauncher", "Accept": "application/vnd.github+json"}
    tok = token or os.environ.get("GITHUB_TOKEN", "")
    if tok:
        h["Authorization"] = f"Bearer {tok}"
    req = urllib.request.Request(url, headers=h)
    tmp = tempfile.NamedTemporaryFile(prefix="ghml-pack-", suffix=".zip", delete=False)
    try:
        with OPENER.open(req, timeout=300) as r:
            total = 0
            while True:
                chunk = r.read(1 << 20)
                if not chunk:
                    break
                tmp.write(chunk)
                total += len(chunk)
                if total % (20 << 20) < (1 << 20):
                    log(f"  ... {total // (1 << 20)} MB")
        tmp.close()
        log(f"Downloaded {os.path.getsize(tmp.name) // (1 << 20)} MB")
        return tmp.name
    except Exception:
        tmp.close()
        try:
            os.unlink(tmp.name)
        except OSError:
            pass
        raise


def install_pack_zip(zip_path, game_dir, log, clear_mods=True):
    """Extract pack folders from a GitHub zipball into game_dir."""
    os.makedirs(game_dir, exist_ok=True)
    mods_dir = os.path.join(game_dir, "mods")
    if clear_mods and os.path.isdir(mods_dir):
        log("Clearing existing mods folder...")
        for f in os.listdir(mods_dir):
            p = os.path.join(mods_dir, f)
            try:
                if os.path.isfile(p) or os.path.islink(p):
                    os.remove(p)
                elif os.path.isdir(p):
                    shutil.rmtree(p)
            except OSError as e:
                log(f"  warning: could not remove {f}: {e}")

    with zipfile.ZipFile(zip_path) as z:
        # GitHub zipball has a single top-level folder: owner-repo-sha/
        names = z.namelist()
        if not names:
            raise RuntimeError("Empty pack zip")
        root_prefix = names[0].split("/")[0] + "/"
        copied = {f: 0 for f in PACK_FOLDERS}
        for name in names:
            if not name.startswith(root_prefix) or name.endswith("/"):
                continue
            rel = name[len(root_prefix):]
            top = rel.split("/", 1)[0]
            if top not in PACK_FOLDERS:
                continue
            dest = os.path.join(game_dir, *rel.split("/"))
            os.makedirs(os.path.dirname(dest), exist_ok=True)
            with z.open(name) as src, open(dest, "wb") as dst:
                shutil.copyfileobj(src, dst)
            copied[top] = copied.get(top, 0) + 1
        for folder, n in sorted(copied.items()):
            if n:
                log(f"  {folder}/: {n} file(s)")
        if not any(copied.values()):
            raise RuntimeError(
                "No pack folders found (expected mods/, config/, ...). "
                "Is this a modpack repo?"
            )


def install_github_pack(repo, inst, token, log, ref="main", clear_mods=True):
    """Download a GitHub pack repo and install it into the instance."""
    zip_path = None
    try:
        zip_path = download_github_zipball(repo, ref, token, log)
        game_dir = _game_dir_from_mods(inst["mods_dir"])
        log(f"Installing into {game_dir}")
        install_pack_zip(zip_path, game_dir, log, clear_mods=clear_mods)
        # Track that this instance came from a pack (for UI / future updates)
        inst["pack_repo"] = repo
        inst["pack_ref"] = ref
        # Pack jars are not individually tracked as GitHub-release mods
        log("Pack install complete.")
    finally:
        if zip_path:
            try:
                os.unlink(zip_path)
            except OSError:
                pass


# ================================================================= jar metadata (mods)
def read_meta(path):
    """Return {loader: {"ids": set, "deps": [required mod ids]}} for a jar (empty if unknown)."""
    out = {}
    try:
        with zipfile.ZipFile(path) as z:
            names = set(z.namelist())

            if "fabric.mod.json" in names:
                j = json.loads(z.read("fabric.mod.json").decode("utf-8-sig"), strict=False)
                ids = {j.get("id")} | set(j.get("provides") or [])
                dep = j.get("depends")
                out["fabric"] = {"ids": {i for i in ids if i},
                                 "deps": list(dep.keys()) if isinstance(dep, dict) else []}

            if "quilt.mod.json" in names:
                q = json.loads(z.read("quilt.mod.json").decode("utf-8-sig"), strict=False).get("quilt_loader", {})
                deps = []
                for d in q.get("depends") or []:
                    if isinstance(d, str):
                        deps.append(d)
                    elif isinstance(d, dict) and not d.get("optional") and d.get("id"):
                        deps.append(d["id"])
                out["quilt"] = {"ids": {q.get("id")} - {None}, "deps": deps}

            for fn in ("META-INF/neoforge.mods.toml", "META-INF/mods.toml"):
                if fn not in names:
                    continue
                raw = z.read(fn).decode("utf-8-sig", errors="replace")
                if tomllib:
                    t = tomllib.loads(raw)
                    ids = {m.get("modId") for m in t.get("mods", []) if isinstance(m, dict)}
                    deps, dep_ids = [], set()
                    for lst in (t.get("dependencies") or {}).values():
                        for d in (lst if isinstance(lst, list) else []):
                            mid = d.get("modId")
                            dep_ids.add(mid)
                            if d.get("mandatory") is True or d.get("type") == "required":
                                deps.append(mid)
                    is_neo = fn.startswith("META-INF/neoforge") or "neoforge" in dep_ids
                else:
                    ids = set(re.findall(r'modId\s*=\s*"([^"]+)"', raw))
                    deps = []
                    is_neo = fn.startswith("META-INF/neoforge")
                out["neoforge" if is_neo else "forge"] = {"ids": {i for i in ids if i}, "deps": deps}
                break
    except Exception:
        pass
    return out


def meta_for(meta, loader):
    if loader in meta:
        return meta[loader]
    if loader == "quilt" and "fabric" in meta:
        return meta["fabric"]
    return {}


def name_loaders(name):
    n = name.lower()
    found = set()
    if "neoforge" in n:
        found.add("neoforge")
        n = n.replace("neoforge", "")
    if "forge" in n:
        found.add("forge")
    if "fabric" in n:
        found.add("fabric")
    if "quilt" in n:
        found.add("quilt")
    return found


# ================================================================= release selection (mods)
def candidates(rel, inst):
    """Scored jar assets of this release that fit the instance's loader / MC version."""
    mc, loader = inst["mc_version"], inst["loader"]
    relinfo = (rel.get("tag_name", "") + " " + (rel.get("name") or "")).lower()
    out = []
    for a in rel.get("assets", []):
        n = a["name"].lower()
        if not n.endswith(".jar") or any(b in n for b in BAD_NAME_PARTS):
            continue
        nl = name_loaders(n)
        if nl and loader not in nl and not (loader == "quilt" and "fabric" in nl):
            continue
        score = 3 if loader in nl else 0
        if mc:
            if mc in n:
                score += 4
            elif OTHER_MC_RE.search(n):
                continue  # filename says another MC version
            elif mc in relinfo:
                score += 2
            elif OTHER_MC_RE.search(relinfo):
                continue  # release says another MC version
        out.append((score, a))
    out.sort(key=lambda t: -t[0])
    return out


def find_release(repo, inst, token):
    rels = [r for r in http(f"{API}/repos/{repo}/releases?per_page=100", token) if not r.get("draft")]
    stable = [r for r in rels if not r.get("prerelease")]
    for pool in (stable, rels):  # prefer stable, fall back to prereleases
        for rel in pool:
            c = candidates(rel, inst)
            if c:
                return rel, c
    return None, []


def expected_hash(rel, asset, token):
    """(sha256, source) from GitHub's digest field, a sidecar file, or a checksums file."""
    d = asset.get("digest") or ""
    if d.startswith("sha256:"):
        return d[7:].lower(), "GitHub asset digest"
    hex64 = re.compile(r"\b[a-fA-F0-9]{64}\b")
    try:
        for a in rel.get("assets", []):
            n = a["name"].lower()
            if n in (asset["name"].lower() + ".sha256", asset["name"].lower() + ".sha256sum"):
                m = hex64.search(http(a["url"], token, True, "application/octet-stream").decode(errors="replace"))
                if m:
                    return m.group(0).lower(), a["name"]
        for a in rel.get("assets", []):
            n = a["name"].lower()
            if ("sha256" in n or "checksum" in n) and not n.endswith(".jar"):
                for line in http(a["url"], token, True, "application/octet-stream").decode(errors="replace").splitlines():
                    if asset["name"] in line:
                        m = hex64.search(line)
                        if m:
                            return m.group(0).lower(), a["name"]
    except Exception:
        pass
    return None, ""


def installed_ids(inst):
    ids = set()
    tracked = {i.get("file"): i for i in inst["mods"].values()}
    try:
        files = os.listdir(inst["mods_dir"])
    except FileNotFoundError:
        return ids
    for f in files:
        if not f.endswith(".jar"):
            continue
        t = tracked.get(f)
        if t and t.get("ids"):
            ids |= set(t["ids"])
        else:
            for v in read_meta(os.path.join(inst["mods_dir"], f)).values():
                ids |= v["ids"]
    return ids


def official_profiles_path():
    s = platform.system()
    if s == "Windows":
        root = os.path.join(os.environ.get("APPDATA", HOME), ".minecraft")
    elif s == "Darwin":
        root = os.path.expanduser("~/Library/Application Support/minecraft")
    else:
        root = os.path.expanduser("~/.minecraft")
    return os.path.join(root, "launcher_profiles.json")


# ================================================================= runtime: downloads
def fetch_file(url, dest, sha1=None, size=None):
    """Download url -> dest unless a valid copy exists. Returns False only if missing with no url."""
    if os.path.exists(dest):
        if size is not None:
            if os.path.getsize(dest) == size:
                return True
        elif sha1:
            if sha1_file(dest) == sha1:
                return True
        else:
            return True
    if not url:
        return False
    os.makedirs(os.path.dirname(dest), exist_ok=True)
    last = None
    for _ in range(3):
        try:
            data = fetch_bytes(url)
            if sha1 and hashlib.sha1(data).hexdigest() != sha1:
                raise IOError("sha1 mismatch")
            tmp = dest + ".part"
            with open(tmp, "wb") as f:
                f.write(data)
            os.replace(tmp, dest)
            return True
        except Exception as e:
            last = e
    raise RuntimeError(f"download failed: {url} ({last})")


def download_all(items, log, label):
    """items: (url, dest, sha1, size). Parallel; raises if anything fails."""
    if not items:
        return
    fails, done = [], 0
    with ThreadPoolExecutor(8) as ex:
        futs = {ex.submit(fetch_file, *it): it for it in items}
        for f in as_completed(futs):
            done += 1
            try:
                if not f.result():
                    fails.append(f"{futs[f][1]} (missing, no url)")
            except Exception as e:
                fails.append(str(e))
            if done % 500 == 0:
                log(f"  {label}: {done}/{len(items)}")
    if fails:
        raise RuntimeError(f"{len(fails)} {label} failed; first: {fails[0]}")


# ================================================================= runtime: version json handling
def rules_allow(rules):
    if not rules:
        return True
    allowed = False
    for r in rules:
        match = True
        o = r.get("os")
        if o:
            if "name" in o and o["name"] != OS_NAME:
                match = False
            if "arch" in o and o["arch"] != ARCH:
                match = False
        if r.get("features"):
            match = False  # demo mode, custom resolution, quick play... all off
        if match:
            allowed = r.get("action") == "allow"
    return allowed


def mvn_path(name):
    ext = "jar"
    if "@" in name:
        name, ext = name.split("@", 1)
    p = name.split(":")
    g, a, v = p[0], p[1], p[2]
    cl = p[3] if len(p) > 3 else ""
    return f"{g.replace('.', '/')}/{a}/{v}/{a}-{v}{'-' + cl if cl else ''}.{ext}"


def load_version(rt, vid):
    """Read versions/<id>/<id>.json and merge it with whatever it inherits from."""
    with open(os.path.join(rt, "versions", vid, vid + ".json"), encoding="utf-8") as f:
        j = json.load(f)
    parent_id = j.get("inheritsFrom")
    if not parent_id:
        j["_jar_id"] = j.get("jar", vid)
        return j
    p = load_version(rt, parent_id)
    m = dict(p)
    for k, v in j.items():
        if k == "libraries":
            m["libraries"] = v + p.get("libraries", [])  # child first so it wins dedupe
        elif k == "arguments":
            pa = p.get("arguments", {})
            m["arguments"] = {"game": pa.get("game", []) + v.get("game", []),
                              "jvm": pa.get("jvm", []) + v.get("jvm", [])}
        elif k != "inheritsFrom":
            m[k] = v
    m["_jar_id"] = p["_jar_id"]
    return m


def resolve_libs(version):
    """-> (classpath libs, native libs). Each {path,url,sha1,size[,exclude]}; deduped by group:artifact[:classifier]."""
    libs, natives, seen = [], [], set()
    for lib in version.get("libraries", []):
        if not rules_allow(lib.get("rules")):
            continue
        name = lib["name"]
        parts = name.split(":")
        key = ":".join(parts[:2]) + (":" + parts[3] if len(parts) > 3 else "")
        dl = lib.get("downloads") or {}
        nat = lib.get("natives")
        if nat and OS_NAME in nat:
            cl = nat[OS_NAME].replace("${arch}", "32" if ARCH == "x86" else "64")
            art = (dl.get("classifiers") or {}).get(cl)
            if art:
                natives.append({"path": art["path"], "url": art["url"], "sha1": art.get("sha1"),
                                "size": art.get("size"), "exclude": (lib.get("extract") or {}).get("exclude", [])})
        entry = None
        art = dl.get("artifact")
        if art:
            entry = {"path": art.get("path") or mvn_path(name), "url": art.get("url", ""),
                     "sha1": art.get("sha1"), "size": art.get("size")}
        elif not dl and not nat:  # Fabric/Quilt style: name + maven base url
            base = lib.get("url", "https://libraries.minecraft.net/")
            p = mvn_path(name)
            entry = {"path": p, "url": base.rstrip("/") + "/" + p, "sha1": None, "size": None}
        if entry and key not in seen:
            seen.add(key)
            libs.append(entry)
    return libs, natives


def expand_args(items):
    out = []
    for it in items:
        if isinstance(it, str):
            out.append(it)
        elif rules_allow(it.get("rules")):
            v = it.get("value", [])
            out.extend([v] if isinstance(v, str) else v)
    return out


def subst(s, variables):
    return re.sub(r"\$\{(\w+)\}", lambda m: str(variables.get(m.group(1), m.group(0))), s)


def extract_natives(rt, natives, ndir):
    os.makedirs(ndir, exist_ok=True)
    for n in natives:
        with zipfile.ZipFile(os.path.join(rt, "libraries", n["path"])) as z:
            for m in z.namelist():
                if m.endswith("/") or m.startswith("META-INF") or any(m.startswith(e) for e in n["exclude"]):
                    continue
                with z.open(m) as s, open(os.path.join(ndir, os.path.basename(m)), "wb") as d:
                    shutil.copyfileobj(s, d)


def ensure_vanilla(rt, mc, log):
    vj = os.path.join(rt, "versions", mc, mc + ".json")
    if not os.path.exists(vj):
        log(f"Fetching Minecraft {mc} metadata...")
        man = json.loads(fetch_bytes(MANIFEST_URL))
        v = next((x for x in man["versions"] if x["id"] == mc), None)
        if not v:
            raise RuntimeError(f"Unknown Minecraft version '{mc}'")
        fetch_file(v["url"], vj, v.get("sha1"))
    with open(vj, encoding="utf-8") as f:
        j = json.load(f)
    c = j["downloads"]["client"]
    fetch_file(c["url"], os.path.join(rt, "versions", mc, mc + ".jar"), c["sha1"], c["size"])
    return j


def ensure_assets(rt, version, log):
    ai = version["assetIndex"]
    idx_path = os.path.join(rt, "assets", "indexes", ai["id"] + ".json")
    fetch_file(ai["url"], idx_path, ai.get("sha1"))
    with open(idx_path, encoding="utf-8") as f:
        idx = json.load(f)
    items = []
    for o in idx["objects"].values():
        h = o["hash"]
        items.append((f"https://resources.download.minecraft.net/{h[:2]}/{h}",
                      os.path.join(rt, "assets", "objects", h[:2], h), h, o.get("size")))
    log(f"Checking {len(items)} asset files...")
    download_all(items, log, "assets")
    root = os.path.join(rt, "assets")
    game_assets = root
    if idx.get("virtual"):  # pre-1.7 style asset layout
        game_assets = os.path.join(root, "virtual", ai["id"])
        for name, o in idx["objects"].items():
            dst = os.path.join(game_assets, *name.split("/"))
            if not os.path.exists(dst):
                os.makedirs(os.path.dirname(dst), exist_ok=True)
                shutil.copy2(os.path.join(root, "objects", o["hash"][:2], o["hash"]), dst)
    return root, game_assets


# ================================================================= runtime: Java
def java_exe(root):
    for rel in ("bin/java.exe", "bin/java", "jre.bundle/Contents/Home/bin/java"):
        p = os.path.join(root, *rel.split("/"))
        if os.path.exists(p):
            return p
    return None


def java_major(java):
    try:
        r = subprocess.run([java, "-version"], capture_output=True, text=True, timeout=30, creationflags=NO_WINDOW)
        m = re.search(r'version "(\d+)(?:\.(\d+))?', r.stderr + r.stdout)
        if not m:
            return 0
        a = int(m.group(1))
        return int(m.group(2) or 0) if a == 1 else a
    except Exception:
        return 0


def mojang_platform():
    if OS_NAME == "windows":
        return {"arm64": "windows-arm64", "x86_64": "windows-x64"}.get(ARCH, "windows-x86")
    if OS_NAME == "osx":
        return "mac-os-arm64" if ARCH == "arm64" else "mac-os"
    return "linux" if ARCH == "x86_64" else "linux-i386"


def ensure_mojang_java(rt, vjson, log):
    jv = vjson.get("javaVersion") or {"component": "jre-legacy", "majorVersion": 8}
    comp, plat = jv["component"], mojang_platform()
    root = os.path.join(rt, "runtime", comp, plat, comp)
    exe = java_exe(root)
    if exe:
        return exe
    log(f"Downloading Mojang Java runtime {comp} (Java {jv.get('majorVersion')}) for {plat}...")
    allj = json.loads(fetch_bytes(JAVA_ALL_URL))
    ent = (allj.get(plat) or {}).get(comp)
    if not ent:
        raise RuntimeError(f"Mojang has no Java runtime '{comp}' for {plat}; set a Java path manually")
    man = json.loads(fetch_bytes(ent[0]["manifest"]["url"]))
    items, execs, links = [], [], []
    for path, f in man["files"].items():
        dest = os.path.join(root, *path.split("/"))
        if f["type"] == "directory":
            os.makedirs(dest, exist_ok=True)
        elif f["type"] == "file":
            raw = f["downloads"]["raw"]
            items.append((raw["url"], dest, raw["sha1"], raw["size"]))
            if f.get("executable"):
                execs.append(dest)
        elif f["type"] == "link":
            links.append((dest, f["target"]))
    download_all(items, log, "java files")
    for p in execs:
        os.chmod(p, 0o755)
    if OS_NAME != "windows":
        for dest, target in links:
            if not os.path.lexists(dest):
                os.makedirs(os.path.dirname(dest), exist_ok=True)
                os.symlink(target, dest)
    exe = java_exe(root)
    if not exe:
        raise RuntimeError("Java runtime downloaded but no java executable found")
    return exe


def resolve_java(inst, rt, vjson, log):
    need = (vjson.get("javaVersion") or {}).get("majorVersion", 8)
    if inst["java_path"].strip():
        j = inst["java_path"].strip()
        have = java_major(j)
        if have and have < need:
            log(f"warning: {j} is Java {have}, this Minecraft wants {need}+")
        return j
    try:
        return ensure_mojang_java(rt, vjson, log)
    except Exception as e:
        log(f"Mojang runtime failed ({e}); trying system Java")
        for cand in (os.path.join(os.environ.get("JAVA_HOME", ""), "bin", "java"), shutil.which("java")):
            if cand and os.path.exists(cand) and java_major(cand) >= need:
                return cand
        raise RuntimeError(f"No usable Java {need}+ found. Install it and set 'Java path'.")


# ================================================================= runtime: loader install
def vkey(v):
    return tuple(int(p) if p.isdigit() else 0 for p in re.split(r"[.\-]", v))


def install_fabric_like(kind, inst, rt, log):
    base = "https://meta.fabricmc.net/v2" if kind == "fabric" else "https://meta.quiltmc.org/v3"
    mc = inst["mc_version"]
    ver = inst["loader_version"].strip()
    if not ver:
        lst = json.loads(fetch_bytes(f"{base}/versions/loader/{mc}"))
        if not lst:
            raise RuntimeError(f"{kind} has no loader for Minecraft {mc}")
        good = [x for x in lst if not re.search(r"beta|pre|alpha|rc", x["loader"]["version"], re.I)]
        ver = (good or lst)[0]["loader"]["version"]
    log(f"Installing {kind} loader {ver} for {mc}...")
    raw = fetch_bytes(f"{base}/versions/loader/{mc}/{ver}/profile/json")
    vid = json.loads(raw)["id"]
    d = os.path.join(rt, "versions", vid)
    os.makedirs(d, exist_ok=True)
    with open(os.path.join(d, vid + ".json"), "wb") as f:
        f.write(raw)
    return vid


def forge_installer_url(mc, override):
    v = override
    if not v:
        promos = json.loads(fetch_bytes(FORGE_PROMOS))["promos"]
        v = promos.get(f"{mc}-recommended") or promos.get(f"{mc}-latest")
        if not v:
            raise RuntimeError(f"No Forge build listed for {mc}")
    full = f"{mc}-{v}"
    return (f"https://maven.minecraftforge.net/net/minecraftforge/forge/{full}/forge-{full}-installer.jar", v)


def neoforge_installer_url(mc, override):
    if mc == "1.20.1":  # NeoForge for 1.20.1 shipped as net.neoforged:forge
        art, prefix = "forge", "1.20.1-"
    else:
        m = re.match(r"1\.(\d+)(?:\.(\d+))?$", mc)
        if not m:
            raise RuntimeError("Can't map this MC version to a NeoForge version; fill in 'Loader version'")
        art, prefix = "neoforge", f"{m.group(1)}.{m.group(2) or 0}."
    v = override
    if not v:
        xml = fetch_bytes(f"https://maven.neoforged.net/releases/net/neoforged/{art}/maven-metadata.xml").decode()
        cand = [x for x in re.findall(r"<version>([^<]+)</version>", xml) if x.startswith(prefix)]
        pool = [x for x in cand if not re.search(r"alpha|beta|snapshot", x, re.I)] or cand
        if not pool:
            raise RuntimeError(f"No NeoForge build found for {mc}")
        v = max(pool, key=vkey)
    return (f"https://maven.neoforged.net/releases/net/neoforged/{art}/{v}/{art}-{v}-installer.jar", v)


def run_installer(java, url, ver, rt, loader, mc, log):
    jar = os.path.join(BASE, "installers", os.path.basename(url))
    log(f"Downloading installer {os.path.basename(url)}...")
    fetch_file(url, jar)
    lp = os.path.join(rt, "launcher_profiles.json")  # installers insist on this file
    if not os.path.exists(lp):
        with open(lp, "w") as f:
            json.dump({"profiles": {}, "version": 3}, f)
    vdir = os.path.join(rt, "versions")
    before = set(os.listdir(vdir)) if os.path.isdir(vdir) else set()
    log(f"Running {loader} installer (this can take a few minutes)...")
    r = subprocess.run([java, "-Djava.awt.headless=true", "-jar", jar, "--installClient", rt],
                       capture_output=True, text=True, timeout=1800, cwd=rt, creationflags=NO_WINDOW)
    tail = (r.stdout + r.stderr).strip().splitlines()[-8:]
    for line in tail:
        log("  | " + line)
    if r.returncode != 0:
        raise RuntimeError(f"{loader} installer exited with code {r.returncode}")
    after = set(os.listdir(vdir))
    has_json = lambda d: os.path.exists(os.path.join(vdir, d, d + ".json"))
    new = [d for d in after - before if has_json(d)]
    if not new:  # already installed earlier: find it by version string
        tail_v = ver.split("-")[-1]
        new = [d for d in after if tail_v in d and has_json(d)]
    if not new:
        raise RuntimeError("Installer finished but no new version folder was found")
    return max(new, key=lambda d: os.path.getmtime(os.path.join(vdir, d)))


def ensure_loader(inst, rt, java, log):
    """Install the loader if needed; returns the version id to launch."""
    mc, loader = inst["mc_version"], inst["loader"]
    key = f"{mc}|{loader}|{inst['loader_version'].strip()}"
    vid = inst.get("version_id", "")
    if vid and inst.get("installed_key") == key and os.path.exists(os.path.join(rt, "versions", vid, vid + ".json")):
        return vid
    if loader in ("fabric", "quilt"):
        vid = install_fabric_like(loader, inst, rt, log)
    elif loader == "forge":
        url, v = forge_installer_url(mc, inst["loader_version"].strip())
        vid = run_installer(java, url, v, rt, loader, mc, log)
    elif loader == "neoforge":
        url, v = neoforge_installer_url(mc, inst["loader_version"].strip())
        vid = run_installer(java, url, v, rt, loader, mc, log)
    else:
        raise RuntimeError(f"Unknown loader {loader}")
    inst["version_id"], inst["installed_key"] = vid, key
    log(f"Loader installed: {vid}")
    return vid


def offline_uuid(name):
    h = bytearray(hashlib.md5(("OfflinePlayer:" + name).encode()).digest())
    h[6] = (h[6] & 0x0F) | 0x30
    h[8] = (h[8] & 0x3F) | 0x80
    return str(uuid.UUID(bytes=bytes(h)))


# ================================================================= app
EDIT_KEYS = ("mc_version", "loader", "loader_version", "mods_dir", "java_path", "memory_mb", "jvm_args", "launch_cmd")


class App(tk.Tk):
    def __init__(self):
        super().__init__()
        self.title(f"GH Mod Launcher v{LAUNCHER_VERSION}")
        self.geometry("920x720")
        self.minsize(640, 480)
        self.cfg = load_config()
        self.busy = False
        self.game_proc = None
        self._loading = False
        self._build()
        self._select_instance(self.cfg["active"])
        self.after(200, self._first_run_hint)
        self.after(800, self._check_self_update)

    def _check_self_update(self):
        """Background: see if a newer launcher release exists; show button + optional prompt."""
        repo = (self.cfg.get("update_repo") or DEFAULT_UPDATE_REPO or "").strip()
        if not repo or repo.count("/") != 1:
            return
        skip = (self.cfg.get("skip_update_version") or "").strip()

        def worker():
            try:
                info = fetch_latest_launcher_release(repo, self.cfg.get("token", ""))
                if not info:
                    return
                tag = info["tag"]
                if not _version_newer(tag, LAUNCHER_VERSION):
                    return

                def on_found():
                    self._pending_update = info
                    self._show_update_button(tag)
                    self.log(f"Launcher update available: {tag} (you have {LAUNCHER_VERSION})")
                    # Auto-prompt only if user hasn't dismissed this tag before
                    if skip and tag.lstrip("vV") == skip.lstrip("vV"):
                        return
                    if skip and _parse_version(tag) == _parse_version(skip):
                        return
                    notes = (info.get("notes") or "").strip()
                    msg = (
                        f"A new launcher version is available.\n\n"
                        f"Current:  {LAUNCHER_VERSION}\n"
                        f"Latest:   {tag}\n\n"
                    )
                    if notes:
                        msg += f"Notes:\n{notes[:400]}\n\n"
                    msg += (
                        "Download and install the update now?\n"
                        "(You can also use the Update button later.)"
                    )
                    if messagebox.askyesno("Launcher update", msg, parent=self):
                        self._do_self_update()
                    else:
                        self.cfg["skip_update_version"] = tag
                        save_config(self.cfg)
                        self.log(f"Update to {tag} deferred — use the Update button when ready.")

                self.after(0, on_found)
            except Exception as e:
                self.after(0, lambda err=str(e): self.log(f"Update check failed: {err}"))

        threading.Thread(target=worker, daemon=True).start()

    def _show_update_button(self, tag):
        """Reveal the Update button with the latest version label."""
        btn = getattr(self, "update_btn", None)
        if not btn:
            return
        btn.configure(text=f"Update to {tag}")
        try:
            btn.pack(side="left", padx=4)
        except Exception:
            pass
        lbl = getattr(self, "update_lbl", None)
        if lbl:
            lbl.configure(text=f"v{LAUNCHER_VERSION} → {tag}")
            try:
                lbl.pack(side="left", padx=2)
            except Exception:
                pass

    def _do_self_update(self):
        """Download and install the pending launcher update."""
        info = getattr(self, "_pending_update", None)
        if not info:
            messagebox.showinfo("No update", "No pending launcher update.", parent=self)
            return
        if self.busy:
            self.log("Busy — wait for the current job before updating.")
            return
        tag = info.get("tag", "?")
        self.busy = True
        self.log(f"Downloading launcher {tag}...")

        def worker():
            try:
                data = download_launcher_update(info, self.cfg.get("token", ""))
                path = apply_launcher_update(data)
                self.cfg["skip_update_version"] = ""
                save_config(self.cfg)
                self.after(0, lambda: self.log(f"Updated to {tag} → {path}"))

                def done():
                    self.busy = False
                    if messagebox.askyesno(
                            "Update installed",
                            f"Launcher updated to {tag}.\n\nRestart now?",
                            parent=self):
                        self.destroy()
                        os.execv(sys.executable, [sys.executable, path] + sys.argv[1:])
                    else:
                        self.log("Restart the launcher to use the new version.")

                self.after(0, done)
            except Exception as e:
                error_text = str(e)
                self.after(0, lambda err=error_text: self.log(f"Update failed: {err}"))

                def err():
                    self.busy = False
                    messagebox.showerror(
                        "Update failed",
                        f"Could not install the update:\n{error_text}\n\n"
                        f"You can download it manually:\n{info.get('html_url', '')}",
                        parent=self)

                self.after(0, err)

        threading.Thread(target=worker, daemon=True).start()

    def _first_run_hint(self):
        """Offer one-click Fathom pack install when no pack profile exists yet."""
        if FATHOM_PACK["name"] in self.cfg["instances"]:
            return
        # Only prompt if profiles look unused (no tracked mods, or just Default)
        has_mods = any(i.get("mods") or i.get("pack_repo") for i in self.cfg["instances"].values())
        if has_mods:
            return
        if messagebox.askyesno(
                "Fathom Studios pack",
                "Install the Fathom Studios modpack now?\n\n"
                f"Repo: {FATHOM_PACK['repo']}\n"
                f"Loader: {FATHOM_PACK['loader']}  MC: {FATHOM_PACK['mc_version']}\n\n"
                "Creates a dedicated profile and downloads mods + config."):
            self.install_fathom_pack()

    @property
    def inst(self):
        return self.cfg["instances"][self.cfg["active"]]

    # ---- UI
    def _build(self):
        # Scrollable main area so the window fits smaller screens
        outer = ttk.Frame(self)
        outer.pack(fill="both", expand=True)
        canvas = tk.Canvas(outer, highlightthickness=0, borderwidth=0)
        sb = ttk.Scrollbar(outer, orient="vertical", command=canvas.yview)
        canvas.configure(yscrollcommand=sb.set)
        sb.pack(side="right", fill="y")
        canvas.pack(side="left", fill="both", expand=True)
        body = ttk.Frame(canvas)
        body_id = canvas.create_window((0, 0), window=body, anchor="nw")

        def _sync_scroll(_event=None):
            canvas.configure(scrollregion=canvas.bbox("all"))

        def _sync_width(event):
            canvas.itemconfigure(body_id, width=event.width)

        body.bind("<Configure>", _sync_scroll)
        canvas.bind("<Configure>", _sync_width)

        def _wheel(event):
            # Windows / macOS
            if getattr(event, "delta", 0):
                canvas.yview_scroll(int(-1 * (event.delta / 120)), "units")
            # X11
            elif getattr(event, "num", None) == 4:
                canvas.yview_scroll(-3, "units")
            elif getattr(event, "num", None) == 5:
                canvas.yview_scroll(3, "units")

        def _bind_wheel(_event=None):
            canvas.bind_all("<MouseWheel>", _wheel)
            canvas.bind_all("<Button-4>", _wheel)
            canvas.bind_all("<Button-5>", _wheel)

        def _unbind_wheel(_event=None):
            canvas.unbind_all("<MouseWheel>")
            canvas.unbind_all("<Button-4>")
            canvas.unbind_all("<Button-5>")

        canvas.bind("<Enter>", _bind_wheel)
        canvas.bind("<Leave>", _unbind_wheel)
        body.bind("<Enter>", _bind_wheel)
        body.bind("<Leave>", _unbind_wheel)

        # Self-update controls stay hidden until a newer GitHub release is found.
        update_row = ttk.Frame(body)
        update_row.pack(fill="x", padx=8, pady=(6, 0))
        self.update_btn = ttk.Button(
            update_row, text="Update launcher", command=self._do_self_update
        )
        self.update_lbl = ttk.Label(update_row, text="")

        row = ttk.Frame(body); row.pack(fill="x", padx=8, pady=6)
        ttk.Label(row, text="Profile / Instance:").pack(side="left")
        self.inst_var = tk.StringVar()
        self.inst_box = ttk.Combobox(row, textvariable=self.inst_var, state="readonly", width=24)
        self.inst_box.pack(side="left", padx=6)
        self.inst_box.bind("<<ComboboxSelected>>", lambda _: self._select_instance(self.inst_var.get()))
        for text, cmd in (("New profile", self.new_instance), ("Delete", self.delete_instance),
                          ("Open folder", self.open_folder)):
            ttk.Button(row, text=text, command=cmd).pack(side="left", padx=2)

        # Pack install row
        pack = ttk.Frame(body); pack.pack(fill="x", padx=8, pady=2)
        ttk.Label(pack, text="Packs:").pack(side="left")
        ttk.Button(pack, text="Install Fathom Studios pack",
                   command=self.install_fathom_pack).pack(side="left", padx=4)
        ttk.Button(pack, text="Install pack from GitHub...",
                   command=self.install_pack_dialog).pack(side="left", padx=2)
        ttk.Button(pack, text="Reinstall / update pack",
                   command=self.reinstall_current_pack).pack(side="left", padx=2)

        add = ttk.Frame(body); add.pack(fill="x", padx=8, pady=2)
        ttk.Label(add, text="Add mod (GitHub owner/name or URL):").pack(side="left")
        self.repo_var = tk.StringVar()
        e = ttk.Entry(add, textvariable=self.repo_var, width=40)
        e.pack(side="left", padx=6)
        e.bind("<Return>", lambda _: self.add_repo())
        ttk.Button(add, text="Add + install mod", command=self.add_repo).pack(side="left")

        cols = ("repo", "tag", "file", "sha256", "role")
        self.tree = ttk.Treeview(body, columns=cols, show="headings", height=9)
        for c, w in zip(cols, (200, 90, 280, 90, 160)):
            self.tree.heading(c, text=c)
            self.tree.column(c, width=w)
        self.tree.pack(fill="both", expand=True, padx=8, pady=6)

        b = ttk.Frame(body); b.pack(fill="x", padx=8)
        for text, cmd in (("Update selected", lambda: self.update_mods(True)),
                          ("Update all", lambda: self.update_mods(False)),
                          ("Remove selected", self.remove_selected),
                          ("Verify hashes", self.verify)):
            ttk.Button(b, text=text, command=cmd).pack(side="left", padx=2)
        ttk.Button(b, text="PLAY", command=self.play).pack(side="right")
        ttk.Button(b, text="Stop", command=self.stop_game).pack(side="right", padx=4)
        ttk.Button(b, text="Install / prepare", command=lambda: self.start_run(False)).pack(side="right", padx=4)
        ttk.Button(b, text="Sync to official launcher", command=self.sync_profile).pack(side="right", padx=4)

        # Account / auth bar
        auth = ttk.Frame(body); auth.pack(fill="x", padx=8, pady=4)
        ttk.Label(auth, text="Account:").pack(side="left")
        self.auth_status = tk.StringVar(value="Offline")
        ttk.Label(auth, textvariable=self.auth_status, width=28).pack(side="left", padx=6)
        ttk.Button(auth, text="Log in with Microsoft", command=self.ms_login).pack(side="left", padx=2)
        ttk.Button(auth, text="Log out", command=self.ms_logout).pack(side="left", padx=2)
        self._update_auth_status()

        s = ttk.LabelFrame(body, text="Settings"); s.pack(fill="x", padx=8, pady=6)
        self.vars = {k: tk.StringVar() for k in EDIT_KEYS}
        self.version_var = tk.StringVar()
        self.user_var = tk.StringVar(value=self.cfg.get("username", "Player"))
        self.runtime_var = tk.StringVar(value=self.cfg.get("runtime_dir", ""))
        self.token_var = tk.StringVar(value=self.cfg.get("token", ""))
        rows = (("MC version (e.g. 1.20.1)", self.vars["mc_version"], None),
                ("Loader", self.vars["loader"], "loader"),
                ("Loader version (blank = latest)", self.vars["loader_version"], None),
                ("Mods dir", self.vars["mods_dir"], None),
                ("Java path (blank = Mojang runtime)", self.vars["java_path"], None),
                ("Memory (MB)", self.vars["memory_mb"], None),
                ("Extra JVM args", self.vars["jvm_args"], None),
                ("External launch cmd (blank = built-in)", self.vars["launch_cmd"], None),
                ("Installed version id", self.version_var, "ro"),
                ("Offline username (used if not logged in)", self.user_var, None),
                ("Runtime dir (global)", self.runtime_var, None),
                ("GitHub token (global)", self.token_var, "secret"))
        for r, (lbl, var, kind) in enumerate(rows):
            ttk.Label(s, text=lbl).grid(row=r, column=0, sticky="w", padx=4, pady=1)
            if kind == "loader":
                w = ttk.Combobox(s, textvariable=var, values=LOADERS, state="readonly", width=12)
            elif kind == "ro":
                w = ttk.Entry(s, textvariable=var, state="readonly", width=80)
            elif kind == "secret":
                w = ttk.Entry(s, textvariable=var, show="*", width=80)
            else:
                w = ttk.Entry(s, textvariable=var, width=80)
            w.grid(row=r, column=1, sticky="we", padx=4, pady=1)
            if kind != "ro":
                var.trace_add("write", lambda *_: self._save_settings())
        s.columnconfigure(1, weight=1)
        ttk.Label(s, text="External cmd placeholders: {game_dir} {mods_dir} {mc_version} {loader} {name}",
                  foreground="gray").grid(row=len(rows), column=1, sticky="w", padx=4)

        self.log_box = tk.Text(body, height=10, state="disabled")
        self.log_box.pack(fill="both", padx=8, pady=6)

    def log(self, msg):
        def _do():
            self.log_box.configure(state="normal")
            self.log_box.insert("end", msg + "\n")
            if int(self.log_box.index("end-1c").split(".")[0]) > 4000:
                self.log_box.delete("1.0", "1000.0")
            self.log_box.see("end")
            self.log_box.configure(state="disabled")
        self.after(0, _do)

    def _set_version_var(self, v):
        self._loading = True
        self.version_var.set(v)
        self._loading = False

    def _update_auth_status(self):
        acc = self.cfg.get("account")
        if acc and acc.get("type") == "msa" and acc.get("name"):
            self.auth_status.set(f"Microsoft: {acc['name']}")
            if hasattr(self, "user_var"):
                self.user_var.set(acc["name"])
        else:
            self.auth_status.set("Offline")

    def _show_device_code_dialog(self, uri, code, expires_min):
        """Modal with code, copy button, and clickable link."""
        win = tk.Toplevel(self)
        win.title("Microsoft login")
        win.transient(self)
        win.grab_set()
        win.resizable(False, False)

        ttk.Label(win, text="Sign in with Microsoft", font=("", 12, "bold")).pack(
            padx=16, pady=(14, 6))
        ttk.Label(win, text="1. Open this page in your browser:").pack(anchor="w", padx=16)

        link_fr = ttk.Frame(win)
        link_fr.pack(fill="x", padx=16, pady=4)
        link = tk.Label(link_fr, text=uri, fg="#0645AD", cursor="hand2",
                        font=("", 10, "underline"))
        link.pack(side="left")

        def open_link(_=None):
            import webbrowser
            webbrowser.open(uri)

        link.bind("<Button-1>", open_link)
        ttk.Button(link_fr, text="Open", command=open_link).pack(side="left", padx=8)

        ttk.Label(win, text="2. Enter this code:").pack(anchor="w", padx=16, pady=(10, 2))
        code_fr = ttk.Frame(win)
        code_fr.pack(fill="x", padx=16, pady=4)
        tk.Label(code_fr, text=code, font=("", 22, "bold"), fg="#111").pack(side="left")

        def copy_code():
            try:
                self.clipboard_clear()
                self.clipboard_append(code)
                self.update()
                copy_btn.configure(text="Copied!")
                win.after(1500, lambda: copy_btn.configure(text="Copy code"))
            except Exception:
                pass

        copy_btn = ttk.Button(code_fr, text="Copy code", command=copy_code)
        copy_btn.pack(side="left", padx=12)
        try:
            self.clipboard_clear()
            self.clipboard_append(code)
            self.update()
        except Exception:
            pass

        ttk.Label(
            win,
            text=f"3. Sign in with the Microsoft account that owns Minecraft.\n"
                 f"   This window continues automatically when you finish.\n"
                 f"   Code expires in about {expires_min} minutes.",
            justify="left",
        ).pack(anchor="w", padx=16, pady=(8, 4))

        ttk.Button(win, text="OK — I'm signing in", command=win.destroy).pack(pady=(6, 14))
        win.update_idletasks()
        x = self.winfo_rootx() + max(0, (self.winfo_width() - win.winfo_width()) // 2)
        y = self.winfo_rooty() + 40
        win.geometry(f"+{x}+{y}")
        self.wait_window(win)

    def ms_login(self):
        if self.busy:
            self.log("Busy — wait for the current job.")
            return
        self.busy = True
        client_id = msa_client_id(self.cfg)
        cancel = {"flag": False}

        def worker():
            try:
                self.log("Starting Microsoft device-code login...")
                dc = msa_device_code_start(client_id)
                uri = dc.get("verification_uri") or "https://www.microsoft.com/link"
                code = dc["user_code"]
                expires_min = max(1, int(dc.get("expires_in", 900)) // 60)
                self.log(f"Open {uri} and enter code: {code}")
                self.log(f"(code expires in ~{expires_min} minutes)")
                done = threading.Event()

                def show():
                    try:
                        self._show_device_code_dialog(uri, code, expires_min)
                    finally:
                        done.set()

                self.after(0, show)
                done.wait(timeout=120)
                msa_tok = msa_device_code_poll(
                    client_id, dc["device_code"], dc.get("interval", 5),
                    dc.get("expires_in", 900), self.log,
                    cancel_flag=lambda: cancel["flag"])
                acc = build_account_from_msa(msa_tok, client_id, self.log)
                self.cfg["account"] = acc
                self.cfg["username"] = acc["name"]
                save_config(self.cfg)
                self.log(f"Logged in as {acc['name']} ({acc['uuid']})")
                self.after(0, self._update_auth_status)
            except Exception as e:
                self.log(f"Microsoft login failed: {e}")
                err = str(e).lower()
                if "first party" in err or "not permitted to consent" in err:
                    self.log("Hint: this client ID was rejected by Microsoft. "
                             "Set a different public Azure app ID in config as "
                             "\"msa_client_id\", or use Sync to official launcher.")
                elif "403" in err or "invalid app" in err:
                    self.log("Hint: this Azure app may lack Minecraft API permission "
                             "(aka.ms/mce-reviewappid). Try another client ID or "
                             "the official launcher sync.")
            finally:
                self.busy = False

        threading.Thread(target=worker, daemon=True).start()

    def ms_logout(self):
        self.cfg["account"] = None
        save_config(self.cfg)
        self._update_auth_status()
        self.log("Logged out — launches will use offline mode.")

    def _save_settings(self):
        if self._loading:
            return
        i = self.inst
        for k, v in self.vars.items():
            i[k] = v.get().strip() if k not in ("mods_dir", "launch_cmd") else v.get()
        self.cfg["username"] = self.user_var.get().strip() or "Player"
        self.cfg["runtime_dir"] = self.runtime_var.get().strip()
        self.cfg["token"] = self.token_var.get().strip()
        save_config(self.cfg)

    def _select_instance(self, name):
        self.cfg["active"] = name
        self._loading = True
        self.inst_box["values"] = list(self.cfg["instances"])
        self.inst_var.set(name)
        for k, v in self.vars.items():
            v.set(self.inst.get(k, ""))
        self.version_var.set(self.inst.get("version_id", ""))
        self._loading = False
        save_config(self.cfg)
        self._refresh_list()
        self._update_auth_status()

    def _refresh_list(self):
        self.tree.delete(*self.tree.get_children())
        for repo, i in self.inst["mods"].items():
            role = "dep of " + ", ".join(i.get("required_by", [])) if i.get("auto") else "added by you"
            self.tree.insert("", "end", iid=repo, values=(
                repo, i.get("tag") or "-", i.get("file") or "-", (i.get("sha256") or "-")[:10], role))
        # Show pack source as a non-selectable info row when present
        pack = self.inst.get("pack_repo") or ""
        if pack:
            ref = self.inst.get("pack_ref") or "main"
            n_jars = 0
            try:
                n_jars = sum(1 for f in os.listdir(self.inst["mods_dir"]) if f.endswith(".jar"))
            except FileNotFoundError:
                pass
            self.tree.insert("", "end", iid="__pack__", values=(
                f"[pack] {pack}", ref, f"{n_jars} jars in mods/", "-", "installed pack"))

    # ---- instances
    def new_instance(self):
        name = simpledialog.askstring("New profile", "Profile / instance name:", parent=self)
        if not name or name in self.cfg["instances"] or re.search(r'[\\/:*?"<>|]', name):
            return
        mc = simpledialog.askstring("New profile", "Minecraft version (e.g. 1.21.1):", parent=self) or ""
        loader = (simpledialog.askstring("New profile", f"Loader {LOADERS}:", initialvalue="neoforge", parent=self) or "").lower()
        if loader not in LOADERS:
            messagebox.showerror("Bad loader", f"Pick one of {LOADERS}")
            return
        self.cfg["instances"][name] = default_instance(name, mc.strip(), loader)
        os.makedirs(self.cfg["instances"][name]["mods_dir"], exist_ok=True)
        self._select_instance(name)

    def delete_instance(self):
        if len(self.cfg["instances"]) < 2:
            messagebox.showinfo("Nope", "Keep at least one instance.")
            return
        name = self.cfg["active"]
        if not messagebox.askyesno("Delete", f"Delete instance '{name}'?"):
            return
        inst = self.cfg["instances"].pop(name)
        game_dir = os.path.dirname(inst["mods_dir"])
        if game_dir.startswith(BASE) and os.path.isdir(game_dir) and \
                messagebox.askyesno("Delete files", f"Also delete {game_dir}?"):
            shutil.rmtree(game_dir, ignore_errors=True)
        self._select_instance(next(iter(self.cfg["instances"])))

    def open_folder(self):
        d = self._game_dir()
        os.makedirs(d, exist_ok=True)
        if OS_NAME == "windows":
            os.startfile(d)
        else:
            subprocess.Popen(["open" if OS_NAME == "osx" else "xdg-open", d])

    def _game_dir(self):
        d = self.inst["mods_dir"].rstrip("/\\")
        return os.path.dirname(d) if os.path.basename(d) == "mods" else d

    # ---- packs
    def install_fathom_pack(self):
        """One-click: create/select Fathom Studios profile and install the pack."""
        if self.busy or self.game_proc:
            self.log("Busy or game running — wait.")
            return
        name = FATHOM_PACK["name"]
        if name not in self.cfg["instances"]:
            self.cfg["instances"][name] = default_instance(
                name, FATHOM_PACK["mc_version"], FATHOM_PACK["loader"])
            os.makedirs(self.cfg["instances"][name]["mods_dir"], exist_ok=True)
            self.log(f"Created profile '{name}' ({FATHOM_PACK['loader']} {FATHOM_PACK['mc_version']})")
        self._select_instance(name)
        self._start_pack_install(FATHOM_PACK["repo"], FATHOM_PACK["ref"], clear_mods=True)

    def install_pack_dialog(self):
        if self.busy or self.game_proc:
            self.log("Busy or game running — wait.")
            return
        repo = simpledialog.askstring(
            "Install pack from GitHub",
            "GitHub repo (owner/name or URL):\n"
            "Repo must contain a mods/ folder (and optionally config/, etc.).",
            parent=self)
        if not repo:
            return
        repo = re.sub(r"^https?://github\.com/", "", repo.strip()).strip("/")
        repo = re.sub(r"\.git$", "", repo)
        repo = "/".join(repo.split("/")[:2])
        if repo.count("/") != 1:
            messagebox.showerror("Bad repo", "Use owner/name")
            return
        ref = simpledialog.askstring("Branch / tag", "Branch or tag (default: main):",
                                     initialvalue="main", parent=self) or "main"
        clear = messagebox.askyesno(
            "Clear mods?",
            "Replace the current mods folder with the pack's mods?\n"
            "(Recommended for a clean pack install.)")
        # Optionally create a new profile for this pack
        use_new = messagebox.askyesno(
            "New profile?",
            f"Install into a new profile named after the pack?\n"
            f"(No = install into current profile '{self.cfg['active']}')")
        if use_new:
            pname = repo.split("/")[-1]
            if pname in self.cfg["instances"]:
                pname = f"{pname}-{ref}"
            # Ask MC / loader if we can
            mc = simpledialog.askstring("Minecraft version", "MC version for this pack:",
                                        initialvalue=self.inst.get("mc_version") or "1.21.1",
                                        parent=self) or "1.21.1"
            loader = (simpledialog.askstring("Loader", f"Loader {LOADERS}:",
                                             initialvalue=self.inst.get("loader") or "neoforge",
                                             parent=self) or "neoforge").lower()
            if loader not in LOADERS:
                messagebox.showerror("Bad loader", f"Pick one of {LOADERS}")
                return
            self.cfg["instances"][pname] = default_instance(pname, mc.strip(), loader)
            os.makedirs(self.cfg["instances"][pname]["mods_dir"], exist_ok=True)
            self._select_instance(pname)
        self._start_pack_install(repo, ref.strip(), clear_mods=clear)

    def reinstall_current_pack(self):
        if self.busy or self.game_proc:
            self.log("Busy or game running — wait.")
            return
        repo = self.inst.get("pack_repo") or ""
        ref = self.inst.get("pack_ref") or "main"
        if not repo:
            # Fall back to Fathom if this is that profile
            if self.cfg["active"] == FATHOM_PACK["name"]:
                repo, ref = FATHOM_PACK["repo"], FATHOM_PACK["ref"]
            else:
                messagebox.showinfo(
                    "No pack",
                    "This profile has no pack recorded.\n"
                    "Use 'Install Fathom Studios pack' or 'Install pack from GitHub...'.")
                return
        if not messagebox.askyesno("Reinstall pack",
                                   f"Re-download and reinstall {repo}@{ref}?\n"
                                   "Existing mods folder will be replaced."):
            return
        self._start_pack_install(repo, ref, clear_mods=True)

    def _start_pack_install(self, repo, ref, clear_mods=True):
        self.busy = True
        self._save_settings()
        inst = self.inst
        name = self.cfg["active"]

        def worker():
            try:
                install_github_pack(repo, inst, self.cfg.get("token", ""), self.log,
                                    ref=ref, clear_mods=clear_mods)
                save_config(self.cfg)
                self.log(f"Profile '{name}' is ready. Press 'Install / prepare' then PLAY.")
            except urllib.error.HTTPError as e:
                hint = " (private repo? set a GitHub token)" if e.code in (401, 403, 404) else ""
                self.log(f"Pack install HTTP {e.code}{hint}")
            except Exception as e:
                self.log(f"Pack install ERROR: {e}")
            finally:
                self.busy = False
                self.after(0, self._refresh_list)

        threading.Thread(target=worker, daemon=True).start()

    # ---- mod actions
    def add_repo(self):
        repo = self.repo_var.get().strip()
        repo = re.sub(r"^https?://github\.com/", "", repo).strip("/")
        repo = re.sub(r"\.git$", "", repo)
        repo = "/".join(repo.split("/")[:2])
        if repo.count("/") != 1:
            messagebox.showerror("Bad repo", "Use owner/name")
            return
        self.repo_var.set("")
        self.update_mods(only=[repo])

    def update_mods(self, selected_only=False, only=None):
        if self.busy:
            self.log("Busy, wait for the current job.")
            return
        if self.game_proc:
            self.log("Close the game before changing mods.")
            return
        if only is None:
            only = list(self.tree.selection()) if selected_only else list(self.inst["mods"])
        if not only:
            self.log("Nothing to update.")
            return
        self.busy = True
        threading.Thread(target=self._mod_worker, args=(self.inst, only), daemon=True).start()

    def _mod_worker(self, inst, repos):
        try:
            os.makedirs(inst["mods_dir"], exist_ok=True)
            seen = set(repos)
            for r in repos:
                try:
                    self.install_mod(r, inst, auto=False, parent=None, seen=seen)
                except urllib.error.HTTPError as e:
                    hint = " (private repo? set a token)" if e.code in (401, 403, 404) else ""
                    self.log(f"[{r}] HTTP {e.code}{hint}")
                except Exception as e:
                    self.log(f"[{r}] ERROR: {e}")
            self.log("Done.")
        finally:
            self.busy = False
            self.after(0, self._refresh_list)

    def install_mod(self, repo, inst, auto, parent, seen):
        token = self.cfg["token"]
        loader, mods_dir = inst["loader"], inst["mods_dir"]
        self.log(f"[{repo}] checking ({loader} {inst['mc_version'] or 'any MC'})...")
        rel, cands = find_release(repo, inst, token)
        if not rel:
            self.log(f"[{repo}] no release with a matching jar for {loader} {inst['mc_version']}")
            return
        old = inst["mods"].get(repo, {})
        tag = rel["tag_name"]
        best = cands[0][1]
        dest0 = os.path.join(mods_dir, os.path.basename(best["name"]))

        if old.get("file") == best["name"] and old.get("tag") == tag and os.path.exists(dest0) \
                and sha256_file(dest0) == old.get("sha256"):
            self.log(f"[{repo}] up to date ({tag})")
            fname, sha, meta = best["name"], old["sha256"], read_meta(dest0)
        else:
            result = None
            for _, a in cands[:3]:
                fname = os.path.basename(a["name"])
                self.log(f"[{repo}] downloading {fname} ({tag})")
                data = http(a["url"], token, True, "application/octet-stream")
                if a.get("size") and len(data) != a["size"]:
                    self.log(f"[{repo}] size mismatch for {fname}, skipping")
                    continue
                sha = hashlib.sha256(data).hexdigest()
                exp, src = expected_hash(rel, a, token)
                if exp and exp != sha:
                    self.log(f"[{repo}] HASH MISMATCH for {fname} (expected {exp[:12]}.., got {sha[:12]}..) - rejected")
                    continue
                self.log(f"[{repo}] sha256 verified via {src}" if exp else
                         f"[{repo}] no published hash; recorded sha256 {sha[:12]}.. (trust on first use)")
                tmp = os.path.join(mods_dir, fname + ".part")
                with open(tmp, "wb") as f:
                    f.write(data)
                meta = read_meta(tmp)
                if meta and not meta_for(meta, loader):
                    self.log(f"[{repo}] {fname} is a {'/'.join(meta)} jar, instance is {loader} - rejected")
                    os.remove(tmp)
                    continue
                if not meta:
                    self.log(f"[{repo}] warning: couldn't read mod metadata from {fname}")
                os.replace(tmp, os.path.join(mods_dir, fname))
                result = (fname, sha, meta)
                break
            if not result:
                self.log(f"[{repo}] no acceptable jar installed")
                return
            fname, sha, meta = result
            if old.get("file") and old["file"] != fname:
                try:
                    os.remove(os.path.join(mods_dir, old["file"]))
                except FileNotFoundError:
                    pass
            self.log(f"[{repo}] installed {fname}")

        mf = meta_for(meta, loader)
        info = inst["mods"].setdefault(repo, {})
        info.update(tag=tag, file=fname, sha256=sha, ids=sorted(mf.get("ids", [])), loaders=sorted(meta))
        if not auto:
            info["auto"] = False
        else:
            info.setdefault("auto", True)
        if parent:
            rb = info.setdefault("required_by", [])
            if parent not in rb:
                rb.append(parent)
        save_config(self.cfg)

        dep_map = {**BUILTIN_DEP_MAP, **self.cfg.get("dep_map", {})}
        have = installed_ids(inst)
        for d in mf.get("deps", []):
            if not d or d in SKIP_DEPS or d in have:
                continue
            r2 = dep_map.get(d)
            if not r2:
                self.log(f"[{repo}] requires '{d}' - no known GitHub repo; add it yourself or map it in dep_map")
                continue
            if r2 in seen:
                continue
            seen.add(r2)
            self.log(f"[{repo}] requires '{d}' -> {r2}")
            try:
                self.install_mod(r2, inst, True, repo, seen)
            except Exception as e:
                self.log(f"[{r2}] dependency install failed: {e}")
            have = installed_ids(inst)

    def remove_selected(self):
        sel = [r for r in self.tree.selection() if r != "__pack__"]
        if not sel or self.game_proc:
            return
        mods = self.inst["mods"]
        mods_dir = self.inst["mods_dir"]

        def delete(repo):
            info = mods.pop(repo, {})
            p = os.path.join(mods_dir, info.get("file") or "\0")
            if info.get("file") and os.path.exists(p):
                os.remove(p)
            self.log(f"Removed {repo}")

        for r in sel:
            delete(r)
        changed = True
        while changed:  # clean up auto-installed deps nobody needs anymore
            changed = False
            for r, i in list(mods.items()):
                if i.get("auto"):
                    i["required_by"] = [x for x in i.get("required_by", []) if x in mods]
                    if not i["required_by"] and messagebox.askyesno("Orphaned dependency", f"{r} was only needed by removed mods. Remove it too?"):
                        delete(r)
                        changed = True
        save_config(self.cfg)
        self._refresh_list()

    def verify(self):
        inst = self.inst
        bad = 0
        for repo, i in inst["mods"].items():
            p = os.path.join(inst["mods_dir"], i.get("file") or "\0")
            if not os.path.exists(p):
                self.log(f"[{repo}] MISSING {i.get('file')}")
                bad += 1
            elif sha256_file(p) != i.get("sha256"):
                self.log(f"[{repo}] HASH MISMATCH {i.get('file')} (modified or corrupt)")
                bad += 1
            else:
                self.log(f"[{repo}] OK")
        tracked = {i.get("file") for i in inst["mods"].values()}
        try:
            for f in os.listdir(inst["mods_dir"]):
                if f.endswith(".jar") and f not in tracked:
                    self.log(f"untracked jar: {f}")
        except FileNotFoundError:
            pass
        self.log("Verify finished: " + ("all good." if not bad else f"{bad} problem(s)."))

    # ---- running the game
    def prepare(self, inst, name):
        """Install vanilla + loader + libs + assets + Java. Returns (command list, cwd)."""
        log = self.log
        rt = self.cfg["runtime_dir"] or os.path.join(BASE, "runtime")
        mc = inst["mc_version"].strip()
        if not mc:
            raise RuntimeError("Set a Minecraft version first")
        os.makedirs(rt, exist_ok=True)
        log(f"Preparing '{name}': {inst['loader']} on Minecraft {mc}")

        vjson = ensure_vanilla(rt, mc, log)
        java = resolve_java(inst, rt, vjson, log)
        vid = ensure_loader(inst, rt, java, log)
        save_config(self.cfg)
        self.after(0, lambda: self._set_version_var(vid) if self.cfg["active"] == name else None)

        ver = load_version(rt, vid)
        jar_id = ver["_jar_id"]
        if jar_id != mc:
            ensure_vanilla(rt, jar_id, log)
        jar = os.path.join(rt, "versions", jar_id, jar_id + ".jar")

        libs, natives = resolve_libs(ver)
        log(f"Checking {len(libs) + len(natives)} libraries...")
        download_all([(l["url"], os.path.join(rt, "libraries", l["path"]), l.get("sha1"), l.get("size"))
                      for l in libs + natives], log, "libraries")
        ndir = os.path.join(rt, "versions", vid, "natives")
        if natives:
            extract_natives(rt, natives, ndir)
        os.makedirs(ndir, exist_ok=True)
        assets_root, game_assets = ensure_assets(rt, ver, log)

        log_arg = ""
        lg = (ver.get("logging") or {}).get("client")
        if lg:  # also mitigates Log4Shell on old versions
            p = os.path.join(rt, "assets", "log_configs", lg["file"]["id"])
            fetch_file(lg["file"]["url"], p, lg["file"].get("sha1"))
            log_arg = lg["argument"].replace("${path}", p)

        game_dir = os.path.dirname(inst["mods_dir"].rstrip("/\\")) if os.path.basename(inst["mods_dir"].rstrip("/\\")) == "mods" \
            else inst["mods_dir"]
        os.makedirs(os.path.join(game_dir, "mods"), exist_ok=True)

        # Online (Microsoft) or offline auth
        acc = ensure_fresh_account(self.cfg, log)
        if acc:
            user = acc["name"]
            auth_uuid = acc["uuid"]
            access_token = acc["access_token"]
            user_type = "msa"
            xuid = acc.get("xuid") or ""
            log(f"Launching online as {user}")
        else:
            user = self.cfg.get("username") or "Player"
            auth_uuid = offline_uuid(user)
            access_token = "0"
            user_type = "legacy"
            xuid = ""
            log(f"Launching offline as {user}")

        cp = CP_SEP.join([os.path.join(rt, "libraries", l["path"]) for l in libs] + [jar])
        v = {"auth_player_name": user, "version_name": jar_id, "game_directory": game_dir,
             "assets_root": assets_root, "assets_index_name": ver["assetIndex"]["id"],
             "auth_uuid": auth_uuid, "auth_access_token": access_token, "auth_session": access_token,
             "clientid": "", "auth_xuid": xuid, "user_type": user_type, "user_properties": "{}",
             "version_type": ver.get("type", "release"), "natives_directory": ndir,
             "launcher_name": "ghmodlauncher", "launcher_version": "1.0", "classpath": cp,
             "classpath_separator": CP_SEP, "library_directory": os.path.join(rt, "libraries"),
             "primary_jar": jar, "primary_jar_name": os.path.basename(jar), "game_assets": game_assets}

        args = ver.get("arguments")
        if args:
            jvm, game = expand_args(args.get("jvm", [])), expand_args(args.get("game", []))
        else:  # pre-1.13 style
            jvm = ["-Djava.library.path=${natives_directory}", "-cp", "${classpath}"]
            game = ver.get("minecraftArguments", "").split()
        if "-cp" not in jvm and "-classpath" not in jvm:
            jvm += ["-cp", "${classpath}"]
        if not any(a.startswith("-Djava.library.path") for a in jvm):
            jvm.insert(0, "-Djava.library.path=${natives_directory}")
        try:
            mem = int(inst["memory_mb"])
        except ValueError:
            mem = 4096
        cmd = [java, f"-Xmx{mem}M"] + shlex.split(inst["jvm_args"], posix=(OS_NAME != "windows"))
        if log_arg:
            cmd.append(subst(log_arg, v))
        cmd += [subst(a, v) for a in jvm] + [ver["mainClass"]] + [subst(a, v) for a in game]
        return cmd, game_dir

    def play(self):
        i = self.inst
        if i["launch_cmd"].strip():
            self._external_launch()
        else:
            self.start_run(True)

    def start_run(self, run):
        if self.busy or self.game_proc:
            self.log("Busy or game already running.")
            return
        self._save_settings()
        self.busy = True
        threading.Thread(target=self._run_worker, args=(self.inst, self.cfg["active"], run), daemon=True).start()

    def _run_worker(self, inst, name, run):
        try:
            cmd, cwd = self.prepare(inst, name)
            if not run:
                self.log("Ready. Press PLAY.")
                return
            self.log("Starting game...")
            self.game_proc = subprocess.Popen(cmd, cwd=cwd, stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                                              text=True, errors="replace", creationflags=NO_WINDOW)
            threading.Thread(target=self._pump, args=(self.game_proc,), daemon=True).start()
        except Exception as e:
            self.log(f"ERROR: {e}")
        finally:
            self.busy = False

    def _pump(self, proc):
        for line in proc.stdout:
            self.log("[mc] " + line.rstrip())
        self.log(f"Game exited with code {proc.wait()}")
        self.game_proc = None

    def stop_game(self):
        if self.game_proc:
            self.game_proc.terminate()
            self.log("Asked the game to stop.")

    def _external_launch(self):
        i, name = self.inst, self.cfg["active"]
        cmd = i["launch_cmd"].strip()
        for k, v in (("{game_dir}", self._game_dir()), ("{mods_dir}", i["mods_dir"]),
                     ("{mc_version}", i["mc_version"]), ("{loader}", i["loader"]), ("{name}", name)):
            cmd = cmd.replace(k, v)
        try:
            subprocess.Popen(shlex.split(cmd, posix=(OS_NAME != "windows")),
                             env={**os.environ, "GHML_GAME_DIR": self._game_dir()})
            self.log(f"Launched external: {cmd}")
        except Exception as e:
            self.log(f"Launch failed: {e}")

    def sync_profile(self):
        """Add/update a profile in the official launcher (gives you real Microsoft login)."""
        i, name = self.inst, self.cfg["active"]
        path = official_profiles_path()
        if not os.path.exists(path):
            messagebox.showerror("Not found", f"{path} doesn't exist. Run the official launcher once first.")
            return
        root = os.path.dirname(path)
        if os.path.abspath(self.cfg["runtime_dir"]) != os.path.abspath(root):
            if messagebox.askyesno("Runtime dir",
                                   f"The official launcher only sees loaders installed in:\n{root}\n\n"
                                   "Switch the runtime dir to that folder now? Afterwards press "
                                   "'Install / prepare', then sync again."):
                self.runtime_var.set(root)
            return
        if not i["version_id"]:
            messagebox.showinfo("Not installed", "Press 'Install / prepare' first so the loader exists.")
            return
        if not messagebox.askyesno("Close the launcher", "The official launcher must be closed or it may overwrite this. Continue?"):
            return
        try:
            shutil.copy2(path, path + ".ghml.bak")
            with open(path) as f:
                data = json.load(f)
            now = datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%S.000Z")
            profs = data.setdefault("profiles", {})
            key = "ghml-" + re.sub(r"\W+", "-", name.lower())
            p = profs.get(key, {"created": now})
            p.update({"name": f"GHML: {name}", "type": "custom", "icon": "Furnace", "lastUsed": now,
                      "gameDir": self._game_dir(), "lastVersionId": i["version_id"]})
            profs[key] = p
            with open(path, "w") as f:
                json.dump(data, f, indent=2)
            self.log(f"Profile 'GHML: {name}' written (backup: {path}.ghml.bak). Open the official launcher and pick it.")
        except Exception as e:
            self.log(f"Profile sync failed: {e}")


if __name__ == "__main__":
    try:
        App().mainloop()
    except tk.TclError as e:
        sys.exit(f"Tk failed to start (no display?): {e}")
