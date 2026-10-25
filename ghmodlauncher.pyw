#!/usr/bin/env python3
"""
GH Mod Launcher - crude CurseForge-style launcher: instances (mod profiles), GitHub mods/packs,
and a built-in game runner.
Python 3.9+ (3.11+ recommended for Forge/NeoForge dependency parsing).
Glass UI (rounded panels, smoke fades, glowing buttons) uses Pillow.
Pillow is installed automatically on first launch if missing — no manual pip needed.
Falls back to the flat theme if install is blocked offline / by policy.

Mods:     GitHub releases, Modrinth and CurseForge (paste a link). Matches loader + MC version,
          installs required dependencies automatically, verifies hashes (GitHub digest / sidecar /
          checksums file, Modrinth + CurseForge sha1/sha512, else trust-on-first-use).
          Version picker per mod, "Check updates" against each mod's own source, mod list with jar names.
Packs:    installs full modpacks from GitHub repos that contain mods/ (+ config/, etc.)
          via zipball download, or from Modrinth (.mrpack) / CurseForge modpack links.
          GitHub packs are checked for newer commits on startup and you are asked to update.
          Built-in one-click install for Fathom Studios pack.
Runtime:  installs vanilla + Fabric / Quilt / Forge / NeoForge, fetches Mojang's Java runtime,
          downloads libraries + assets, builds the command line and starts the game.
Auth:     Microsoft account (device-code flow) required to play; no offline mode.
          Tokens refresh automatically. Or set a profile's Launcher to "official" to
          hand it to the official Minecraft launcher (its own Microsoft login).
Profiles: Multiple independent instances (MC version, loader, mods, settings).
"""
import base64
import hashlib
import io
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
import zipfile
from concurrent.futures import ThreadPoolExecutor, as_completed
from datetime import datetime, timezone
import tkinter as tk
from tkinter import ttk, messagebox, simpledialog
from tkinter import font as tkfont
import urllib.parse
import urllib.request
import urllib.error

try:
    import tomllib
except ImportError:  # Python < 3.11
    tomllib = None

_PIL_IMPORT_ERROR = None  # last failure reason (for diagnostics)


def _ensure_user_site_on_path():
    """Make sure the per-user site-packages dir is importable (Windows --user installs)."""
    try:
        import site
        if hasattr(site, "getusersitepackages"):
            usp = site.getusersitepackages()
            if usp and usp not in sys.path and os.path.isdir(usp):
                sys.path.insert(0, usp)
        if hasattr(site, "addsitedir"):
            for key in ("USER_SITE",):
                pth = getattr(site, key, None)
                if isinstance(pth, str) and os.path.isdir(pth):
                    site.addsitedir(pth)
    except Exception:
        pass


def _try_import_pil():
    """Return True and bind PIL symbols into this module if Pillow is importable."""
    global HAS_PIL, Image, ImageDraw, ImageFilter, ImageFont, _PIL_IMPORT_ERROR
    _ensure_user_site_on_path()
    # Drop a half-loaded / broken PIL from sys.modules so a retry can succeed
    for name in list(sys.modules):
        if name == "PIL" or name.startswith("PIL."):
            del sys.modules[name]
    try:
        from PIL import Image as _Image, ImageDraw as _ImageDraw
        from PIL import ImageFilter as _ImageFilter, ImageFont as _ImageFont
        # Touch a real codec path so DLL load failures surface here, not later
        _Image.new("RGBA", (1, 1))
        Image, ImageDraw, ImageFilter, ImageFont = _Image, _ImageDraw, _ImageFilter, _ImageFont
        HAS_PIL = True
        _PIL_IMPORT_ERROR = None
        return True
    except Exception as e:
        HAS_PIL = False
        Image = ImageDraw = ImageFilter = ImageFont = None
        _PIL_IMPORT_ERROR = f"{type(e).__name__}: {e}"
        return False


HAS_PIL = False
Image = ImageDraw = ImageFilter = ImageFont = None
_try_import_pil()


def ensure_pillow(log=None):
    """
    Ensure Pillow is available for the glass UI.
    Installs or reinstalls with the same Python that runs this launcher so users
    never need to run pip themselves. Returns True if PIL is usable afterwards.
    """
    if HAS_PIL or _try_import_pil():
        return True
    _log = log or (lambda m: print(m, flush=True))
    if _PIL_IMPORT_ERROR:
        _log(f"Glass UI: Pillow import failed ({_PIL_IMPORT_ERROR}) — repairing...")
    else:
        _log("Glass UI: Pillow not found — installing automatically (one-time)...")
    try:
        import ensurepip
        ensurepip.bootstrap(user=True)
    except Exception:
        pass
    # --force-reinstall covers "pip says installed" but the binary failed to load
    pip_cmds = [
        [sys.executable, "-m", "pip", "install", "--user", "--upgrade", "--force-reinstall",
         "--disable-pip-version-check", "pillow"],
        [sys.executable, "-m", "pip", "install", "--upgrade", "--force-reinstall",
         "--disable-pip-version-check", "pillow"],
        [sys.executable, "-m", "pip", "install", "--user", "--disable-pip-version-check", "pillow"],
    ]
    last_err = _PIL_IMPORT_ERROR or "unknown error"
    for cmd in pip_cmds:
        try:
            r = subprocess.run(cmd, capture_output=True, text=True, timeout=300,
                               creationflags=0x08000000 if os.name == "nt" else 0)  # no console flash
            if _try_import_pil():
                _log("Glass UI: Pillow ready — rounded glass + glowing buttons enabled.")
                return True
            if r.returncode != 0:
                lines = (r.stderr or r.stdout or f"exit {r.returncode}").strip().splitlines()
                last_err = lines[-1] if lines else f"exit {r.returncode}"
            elif _PIL_IMPORT_ERROR:
                last_err = _PIL_IMPORT_ERROR
        except Exception as e:
            last_err = str(e)
    _log(f"Glass UI: Pillow still unavailable ({last_err}). Flat theme will be used.")
    return False

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
LAUNCHER_VERSION = "1.2.1"
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
    "sodium": "CaffeineMC/sodium-fabric",
    "iris": "IrisShaders/Iris",
    "lithium": "CaffeineMC/lithium-fabric",
    "phosphor": "CaffeineMC/phosphor-fabric",
    "entityculling": "tr7zw/EntityCulling",
    "ferritecore": "malte0811/FerriteCore",
    "indium": "comp500/Indium",
    "reeses-sodium-options": "FlashyReese/reeses-sodium-options",
    "sodium-extra": "FlashyReese/sodium-extra",
    "lambdynlights": "LambdAurora/LambDynamicLights",
    "midnightlib": "TeamMidnightDust/MidnightLib",
    "trinkets": "emilyploszaj/trinkets",
    "pehkui": "Virtuoel/Pehkui",
    "xaeros_minimap": "sargunv/Xaeros_Minimap",
    "xaerominimap": "sargunv/Xaeros_Minimap",
    "jei": "mezz/JustEnoughItems",
    "roughlyenoughitems": "shedaniel/RoughlyEnoughItems",
    "appleskin": "squeek502/AppleSkin",
    "jade": "Snownee/Jade",
    "wthit": "badasintended/wthit",
    "continuity": "PepperCode1/Continuity",
    "fabric-permission-api": "lucko/fabric-permissions-api",
    "placeholder-api": "Patbox/TextPlaceholderAPI",
    "servercore": "Wesley1808/ServerCore",
    "c2me": "RelativityMC/C2ME-fabric",
    "krypton": "astei/krypton",
    "starlight": "PaperMC/Starlight",
    "lazy-dfu": "tuxed/Lazydfu",
    "modernfix": "astei/modernfix",
    "memoryleakfix": "leonardo-d-carvalho/memoryleakfix",
    "smoothboot": "UltimateBoomer/Smooth-Boot",
    "dynamicfps": "juliand665/Dynamic-FPS",
    "capes": "caelthecoldeye/Capes",
    "authme": "axieum/authme",
    "essential-dependencies": "EssentialGG/EssentialDependencies",
}

# Optional direct-jar installs: paste a https://...jar link in Add mod

# CurseForge API key signup (shown as a clickable link in Settings)
CF_API_KEY_URL = "https://console.curseforge.com/"
GH_TOKEN_URL = "https://github.com/settings/tokens"
MR_TOKEN_URL = "https://modrinth.com/settings/account"  # optional PAT for higher limits

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
        # per-instance overrides (added in 1.1.5)
        "window_width": "",     # blank = game default
        "window_height": "",
        "fullscreen": False,
        "game_args": "",        # extra Minecraft (game) arguments
        "env_vars": "",         # KEY=VALUE;KEY2=VALUE2 passed to the game / pre-launch command
        "pre_launch_cmd": "",   # runs in the game dir before launch; non-zero exit aborts
        "launcher": "builtin",  # "builtin" (this app) or "official" (Mojang's Minecraft launcher)
        # pack tracking (pack_source: github | modrinth | curseforge; update checks are GitHub-only)
        "pack_source": "",
        "pack_sha": "",         # GitHub commit that is installed
        "pack_url": "",         # Modrinth / CurseForge pack link
        "pack_name": "",
        "pack_version": "",
        "pack_skip_sha": "",    # latest commit the user already declined
    }


def load_config():
    cfg = {"token": "", "active": "Default", "instances": {}, "dep_map": {},
           "runtime_dir": os.path.join(BASE, "runtime"),
           "msa_client_id": "", "account": None,
           "update_repo": DEFAULT_UPDATE_REPO, "skip_update_version": "",
           "official_launcher_path": "", "curseforge_api_key": "",
           "modrinth_token": "", "theme_bg": DEFAULT_BG}
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
    if script_name.lower().endswith(".pyw"):  # run as .pyw (no console), but the repo ships the .py name
        script_name = script_name[:-1]
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
    if current.lower().endswith((".py", ".pyw")):
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
    Returns None if no Microsoft account is stored or the refresh failed.
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
        save_config(cfg)
        log(f"Logged in as {new_acc['name']}")
        return new_acc
    except Exception as e:
        log(f"Token refresh failed: {e}")
        log("Please log in with Microsoft again.")
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


def clear_mods_dir(game_dir, log):
    mods_dir = os.path.join(game_dir, "mods")
    if not os.path.isdir(mods_dir):
        return
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


def install_pack_zip(zip_path, game_dir, log, clear_mods=True):
    """Extract pack folders from a GitHub zipball into game_dir."""
    os.makedirs(game_dir, exist_ok=True)
    if clear_mods:
        clear_mods_dir(game_dir, log)

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
        sha = ""
        try:  # pin the download to one commit so we know exactly what was installed
            sha = http(f"{API}/repos/{repo}/commits/{urllib.parse.quote(ref, safe='/')}", token)["sha"]
        except Exception as e:
            log(f"Couldn't read the pack's latest commit ({e}); update checks won't work for this install")
        zip_path = download_github_zipball(repo, sha or ref, token, log)
        game_dir = _game_dir_from_mods(inst["mods_dir"])
        log(f"Installing into {game_dir}")
        install_pack_zip(zip_path, game_dir, log, clear_mods=clear_mods)
        # Track that this instance came from a pack (for UI / future updates)
        inst.update(pack_source="github", pack_repo=repo, pack_ref=ref, pack_sha=sha,
                    pack_url="", pack_name=repo, pack_version=sha[:7], pack_skip_sha="")
        # Pack jars are not individually tracked as GitHub-release mods
        log("Pack install complete.")
    finally:
        if zip_path:
            try:
                os.unlink(zip_path)
            except OSError:
                pass


# ---- packs from Modrinth / CurseForge links
def parse_pack_link(text):
    """-> (kind, ref, extra): ('github', 'owner/name', ''), ('modrinth', slug, version), ('curseforge', slug, file id)."""
    t = (text or "").strip()
    m = re.match(r"(?:https?://)?(?:www\.)?modrinth\.com/modpack/([^/?#\s]+)(?:/version/([^/?#\s]+))?", t)
    if m:
        return "modrinth", m.group(1), m.group(2) or ""
    m = re.match(r"(?:https?://)?(?:www\.)?curseforge\.com/minecraft/modpacks/([^/?#\s]+)(?:/files/(\d+))?", t)
    if m:
        return "curseforge", m.group(1), m.group(2) or ""
    repo = re.sub(r"^https?://github\.com/", "", t).strip("/")
    repo = re.sub(r"\.git$", "", repo)
    repo = "/".join(repo.split("/")[:2])
    if re.fullmatch(r"[\w.-]+/[\w.-]+", repo):
        return "github", repo, ""
    return None, None, None


def _safe_join(base, rel):
    """Join a path from inside a pack archive onto base, refusing anything that escapes it."""
    rel = rel.replace("\\", "/")
    if rel.startswith("/") or re.match(r"^[A-Za-z]:", rel) or ".." in rel.split("/"):
        raise RuntimeError(f"Unsafe path in pack: {rel}")
    dest = os.path.normpath(os.path.join(base, *rel.split("/")))
    b = os.path.abspath(base)
    try:
        ok = os.path.commonpath([b, os.path.abspath(dest)]) == b
    except ValueError:
        ok = False
    if not ok:
        raise RuntimeError(f"Unsafe path in pack: {rel}")
    return dest


def _extract_overrides(z, prefixes, game_dir, log):
    n = 0
    for name in z.namelist():
        for pre in prefixes:
            if name.startswith(pre) and not name.endswith("/"):
                dest = _safe_join(game_dir, name[len(pre):])
                os.makedirs(os.path.dirname(dest), exist_ok=True)
                with z.open(name) as src, open(dest, "wb") as dst:
                    shutil.copyfileobj(src, dst)
                n += 1
    log(f"  overrides: {n} file(s)")


def _download_tmp(url, name, sha1=None):
    d = tempfile.mkdtemp(prefix="ghml-pack-")
    dest = os.path.join(d, name)
    fetch_file(url, dest, sha1)
    return d, dest


def install_mrpack(path, inst, log, clear_mods=True):
    """Install a Modrinth .mrpack into the instance and set its MC version / loader."""
    game_dir = _game_dir_from_mods(inst["mods_dir"])
    with zipfile.ZipFile(path) as z:
        idx = json.loads(z.read("modrinth.index.json"))
        deps = idx.get("dependencies", {})
        keymap = {"fabric-loader": "fabric", "quilt-loader": "quilt", "forge": "forge", "neoforge": "neoforge"}
        found = [(keymap[k], v) for k, v in deps.items() if k in keymap]
        mc = deps.get("minecraft")
        if not mc or not found:
            raise RuntimeError("This .mrpack has no Minecraft version / supported loader in its dependencies")
        loader, lver = found[0]
        os.makedirs(game_dir, exist_ok=True)
        if clear_mods:
            clear_mods_dir(game_dir, log)
        items = []
        for f in idx.get("files", []):
            if (f.get("env") or {}).get("client") == "unsupported":
                continue
            dest = _safe_join(game_dir, f["path"])
            url = next((u for u in f.get("downloads", []) if urllib.parse.urlparse(u).netloc in MR_PACK_HOSTS), None)
            if not url:
                raise RuntimeError(f"No download from an allowed host for {f['path']}")
            items.append((url, dest, (f.get("hashes") or {}).get("sha1"), None))
        log(f"Downloading {len(items)} pack file(s)...")
        download_all(items, log, "pack files")
        _extract_overrides(z, ("overrides/", "client-overrides/"), game_dir, log)
    inst.update(mc_version=mc, loader=loader, loader_version=lver)
    log(f"Pack needs {loader} {lver} on Minecraft {mc}")


def install_modrinth_pack(slug, version, inst, log, clear_mods=True):
    p = mr_json(f"/project/{urllib.parse.quote(slug)}")
    if p.get("project_type") != "modpack":
        raise RuntimeError(f"'{slug}' on Modrinth is a {p.get('project_type')}, not a modpack")
    if version:
        v = mr_json(f"/project/{p['id']}/version/{urllib.parse.quote(version)}")
    else:
        vs = mr_json(f"/project/{p['id']}/version")
        if not vs:
            raise RuntimeError("This modpack has no versions")
        v = next((x for x in vs if x.get("version_type") == "release"), vs[0])
    files = v.get("files") or []
    f = next((x for x in files if x.get("primary")), None) or next(
        (x for x in files if x["filename"].endswith(".mrpack")), None)
    if not f:
        raise RuntimeError("That version has no .mrpack file")
    log(f"Downloading {p['title']} {v['version_number']}...")
    tmpdir, dest = _download_tmp(f["url"], "pack.mrpack", (f.get("hashes") or {}).get("sha1"))
    try:
        install_mrpack(dest, inst, log, clear_mods)
    finally:
        shutil.rmtree(tmpdir, ignore_errors=True)
    inst.update(pack_source="modrinth", pack_repo="", pack_ref="", pack_sha="", pack_skip_sha="",
                pack_url=f"https://modrinth.com/modpack/{p.get('slug') or slug}",
                pack_name=p["title"], pack_version=v["version_number"])
    log("Pack install complete.")


def install_cf_zip(cfg, path, inst, log, clear_mods=True):
    """Install a CurseForge modpack zip (manifest.json + overrides/)."""
    game_dir = _game_dir_from_mods(inst["mods_dir"])
    with zipfile.ZipFile(path) as z:
        man = json.loads(z.read("manifest.json"))
        mcinfo = man.get("minecraft") or {}
        mc = mcinfo.get("version")
        mls = mcinfo.get("modLoaders") or []
        ml = next((m for m in mls if m.get("primary")), mls[0] if mls else None)
        if not mc or not ml:
            raise RuntimeError("manifest.json has no Minecraft version / mod loader")
        loader, _, lver = ml["id"].partition("-")
        if loader not in LOADERS:
            raise RuntimeError(f"Unsupported loader in pack: {ml['id']}")
        os.makedirs(game_dir, exist_ok=True)
        if clear_mods:
            clear_mods_dir(game_dir, log)
        entries = man.get("files", [])
        fids = [e["fileID"] for e in entries]
        pids = sorted({e["projectID"] for e in entries})
        log(f"Resolving {len(fids)} file(s) on CurseForge...")
        info = {}
        for i in range(0, len(fids), 400):
            for x in cf_json(cfg, "/mods/files", body={"fileIds": fids[i:i + 400]})["data"]:
                info[x["id"]] = x
        klass = {}
        try:
            for i in range(0, len(pids), 400):
                for m in cf_json(cfg, "/mods", body={"modIds": pids[i:i + 400]})["data"]:
                    klass[m["id"]] = m.get("classId")
        except Exception as e:
            log(f"  couldn't look up project types ({e}); sorting by file extension")
        folders = {6: "mods", 12: "resourcepacks", 6552: "shaderpacks"}
        items, missing = [], []
        for e in entries:
            x = info.get(e["fileID"])
            if not x:
                missing.append(f"project {e['projectID']} file {e['fileID']}")
                continue
            name = x["fileName"]
            folder = folders.get(klass.get(e["projectID"])) or ("mods" if name.lower().endswith(".jar") else None)
            if not folder:
                log(f"  skipping {name} (unsupported type)")
                continue
            url = x.get("downloadUrl") or CF_EDGE.format(x["id"] // 1000, x["id"] % 1000, urllib.parse.quote(name))
            sha1 = next((h["value"] for h in x.get("hashes", []) if h.get("algo") == 1), None)
            items.append((url, _safe_join(game_dir, f"{folder}/{name}"), sha1, None))
        log(f"Downloading {len(items)} file(s)...")
        fails = download_all(items, log, "pack files", strict=False)
        _extract_overrides(z, ((man.get("overrides") or "overrides").rstrip("/") + "/",), game_dir, log)
    for m in missing[:10]:
        log(f"  not found on CurseForge: {m}")
    if fails:
        log(f"WARNING: {len(fails)} file(s) couldn't be downloaded (the author may have blocked third-party "
            "downloads). Get them from CurseForge and drop them in mods/. First failures:")
        for f in fails[:10]:
            log("  " + f)
    inst.update(mc_version=mc, loader=loader, loader_version=lver)
    log(f"Pack needs {loader} {lver} on Minecraft {mc}")


def install_curseforge_pack(cfg, slug, file_id, inst, log, clear_mods=True):
    found = cf_json(cfg, "/mods/search", {"gameId": CF_GAME_ID, "classId": CF_CLASS_PACKS, "slug": slug})["data"]
    if not found:
        raise RuntimeError(f"CurseForge: no modpack with slug '{slug}'")
    mod = found[0]
    if file_id:
        f = cf_json(cfg, f"/mods/{mod['id']}/files/{file_id}")["data"]
    else:
        files = cf_json(cfg, f"/mods/{mod['id']}/files", {"pageSize": 50})["data"]
        files.sort(key=lambda x: x.get("fileDate") or "", reverse=True)
        f = next((x for x in files if x.get("releaseType") == 1), files[0] if files else None)
        if not f:
            raise RuntimeError("This modpack has no files")
    name = f["fileName"]
    url = f.get("downloadUrl") or CF_EDGE.format(f["id"] // 1000, f["id"] % 1000, urllib.parse.quote(name))
    sha1 = next((h["value"] for h in f.get("hashes", []) if h.get("algo") == 1), None)
    log(f"Downloading {mod['name']} ({f.get('displayName') or name})...")
    tmpdir, dest = _download_tmp(url, "pack.zip", sha1)
    try:
        install_cf_zip(cfg, dest, inst, log, clear_mods)
    finally:
        shutil.rmtree(tmpdir, ignore_errors=True)
    inst.update(pack_source="curseforge", pack_repo="", pack_ref="", pack_sha="", pack_skip_sha="",
                pack_url=f"https://www.curseforge.com/minecraft/modpacks/{mod.get('slug') or slug}",
                pack_name=mod["name"], pack_version=f.get("displayName") or name)
    log("Pack install complete.")


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


def find_release(repo, inst, token, tag=None):
    rels = [r for r in http(f"{API}/repos/{repo}/releases?per_page=100", token) if not r.get("draft")]
    if tag:  # a specific release picked from the version list
        for r in rels:
            if r["tag_name"] == tag:
                c = candidates(r, inst)
                return (r, c) if c else (None, [])
        return None, []
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


# ================================================================= mod sources (GitHub / Modrinth / CurseForge)
MR_API = "https://api.modrinth.com/v2"
CF_API = "https://api.curseforge.com/v1"
CF_GAME_ID = 432
CF_CLASS_MODS, CF_CLASS_PACKS = 6, 4471
CF_LOADER_TYPE = {"forge": 1, "fabric": 4, "quilt": 5, "neoforge": 6}
CF_CHANNEL = {1: "release", 2: "beta", 3: "alpha"}
CF_EDGE = "https://edge.forgecdn.net/files/{}/{}/{}"   # fallback when the API hides downloadUrl
MR_PACK_HOSTS = ("cdn.modrinth.com", "github.com", "raw.githubusercontent.com", "gitlab.com")


def mod_source(key):
    if key.startswith("modrinth:"):
        return "modrinth"
    if key.startswith("curseforge:"):
        return "curseforge"
    if key.startswith("url:"):
        return "url"
    return "github"


def installed_vid(key, info):
    """The id the source uses for the installed version (tag for GitHub, version/file id otherwise)."""
    return info.get("tag") if mod_source(key) == "github" else info.get("version_id")


def mr_json(path, params=None, token=""):
    """Modrinth API. Optional PAT raises rate limits; never required for public data."""
    url = MR_API + path + (("?" + urllib.parse.urlencode(params)) if params else "")
    h = {"User-Agent": "ghmodlauncher", "Accept": "application/json"}
    tok = (token or os.environ.get("MODRINTH_TOKEN") or "").strip()
    if tok:
        h["Authorization"] = tok
    req = urllib.request.Request(url, headers=h)
    with urllib.request.urlopen(req, timeout=60) as r:
        return json.loads(r.read().decode("utf-8"))


def search_modrinth(query, loader="", mc="", limit=20, token=""):
    """Search Modrinth mods. Returns list of {source, id, slug, name, description, downloads, loaders}."""
    facets = [["project_type:mod"]]
    if loader:
        facets.append([f"categories:{loader}"])
    if mc:
        facets.append([f"versions:{mc}"])
    params = {
        "query": query or "",
        "limit": max(1, min(int(limit), 50)),
        "index": "relevance",
        "facets": json.dumps(facets),
    }
    data = mr_json("/search", params, token=token)
    out = []
    for hit in data.get("hits") or []:
        out.append({
            "source": "modrinth",
            "id": hit.get("project_id") or hit.get("slug"),
            "slug": hit.get("slug") or "",
            "name": hit.get("title") or hit.get("slug") or "?",
            "description": (hit.get("description") or "")[:160],
            "downloads": hit.get("downloads") or 0,
            "loaders": hit.get("categories") or [],
            "url": f"https://modrinth.com/mod/{hit.get('slug') or hit.get('project_id')}",
        })
    return out


def search_curseforge(cfg, query, loader="", mc="", limit=20):
    """Search CurseForge mods. Requires API key. Same result shape as search_modrinth."""
    params = {
        "gameId": CF_GAME_ID,
        "classId": CF_CLASS_MODS,
        "searchFilter": query or "",
        "pageSize": max(1, min(int(limit), 50)),
        "sortField": 2,  # Popularity
        "sortOrder": "desc",
    }
    if mc:
        params["gameVersion"] = mc
    lt = CF_LOADER_TYPE.get((loader or "").lower())
    if lt:
        params["modLoaderType"] = lt
    data = cf_json(cfg, "/mods/search", params)["data"]
    out = []
    for m in data:
        loaders = []
        for lf in m.get("latestFilesIndexes") or []:
            # modLoader is int on some payloads; name may appear in gameVersions
            pass
        out.append({
            "source": "curseforge",
            "id": str(m["id"]),
            "slug": m.get("slug") or str(m["id"]),
            "name": m.get("name") or "?",
            "description": (m.get("summary") or "")[:160],
            "downloads": m.get("downloadCount") or 0,
            "loaders": loaders,
            "url": f"https://www.curseforge.com/minecraft/mc-mods/{m.get('slug') or m['id']}",
        })
    return out


def cf_key(cfg):
    return (cfg.get("curseforge_api_key") or os.environ.get("CURSEFORGE_API_KEY") or "").strip()


def cf_json(cfg, path, params=None, body=None):
    """CurseForge API call. The key is only ever sent to api.curseforge.com."""
    key = cf_key(cfg)
    if not key:
        raise RuntimeError("CurseForge needs an API key (free at console.curseforge.com). "
                           "Paste it into Settings -> CurseForge API key.")
    url = CF_API + path + (("?" + urllib.parse.urlencode(params)) if params else "")
    h = {"x-api-key": key, "Accept": "application/json", "User-Agent": "ghmodlauncher"}
    data = None
    if body is not None:
        data = json.dumps(body).encode("utf-8")
        h["Content-Type"] = "application/json"
    req = urllib.request.Request(url, data=data, headers=h, method="POST" if body is not None else "GET")
    with urllib.request.urlopen(req, timeout=60) as r:
        return json.loads(r.read().decode("utf-8"))


def parse_mod_link(text):
    """-> (source, ref) for GitHub / Modrinth / CurseForge / direct .jar URL; (None, None) if unknown."""
    t = (text or "").strip()
    # Direct jar URL (any host) — useful when a mod is only published as a file link
    if re.match(r"https?://\S+\.jar(?:\?\S*)?$", t, re.I):
        return "url", t
    m = re.match(r"(?:https?://)?(?:www\.)?modrinth\.com/(?:mod|plugin|datapack)/([^/?#\s]+)", t)
    if m:
        return "modrinth", m.group(1)
    m = re.match(r"(?:https?://)?(?:www\.)?curseforge\.com/minecraft/mc-mods/([^/?#\s]+)", t)
    if m:
        return "curseforge", m.group(1)
    m = re.match(r"(modrinth|curseforge|url):(\S+)$", t, re.I)
    if m:
        return m.group(1).lower(), m.group(2)
    repo = re.sub(r"^https?://github\.com/", "", t).strip("/")
    repo = re.sub(r"\.git$", "", repo)
    repo = "/".join(repo.split("/")[:2])
    if re.fullmatch(r"[\w.-]+/[\w.-]+", repo):
        return "github", repo
    return None, None


def resolve_mod(source, ref, cfg):
    """-> (key, display name). Modrinth/CurseForge keys use the stable project id."""
    if source == "github":
        return ref, ref
    if source == "url":
        name = os.path.basename(urllib.parse.urlparse(ref).path) or "direct.jar"
        # stable key from url hash so re-add updates the same entry
        key = "url:" + hashlib.sha1(ref.encode("utf-8")).hexdigest()[:12]
        return key, name
    if source == "modrinth":
        p = mr_json(f"/project/{urllib.parse.quote(ref)}", token=(cfg.get("modrinth_token") or ""))
        return f"modrinth:{p['id']}", p.get("title") or ref
    if ref.isdigit():
        mod = cf_json(cfg, f"/mods/{ref}")["data"]
    else:
        found = cf_json(cfg, "/mods/search", {"gameId": CF_GAME_ID, "classId": CF_CLASS_MODS, "slug": ref})["data"]
        if not found:
            raise RuntimeError(f"CurseForge: no mod with slug '{ref}'")
        mod = found[0]
    return f"curseforge:{mod['id']}", mod["name"]


def mr_norm(v):
    """Modrinth version object -> common version record (None if it has no jar)."""
    files = [{"name": f["filename"], "url": f["url"], "size": f.get("size"),
              "sha1": (f.get("hashes") or {}).get("sha1"), "sha512": (f.get("hashes") or {}).get("sha512"),
              "primary": bool(f.get("primary"))}
             for f in v.get("files", []) if f.get("filename", "").lower().endswith(".jar")]
    if not files:
        return None
    files.sort(key=lambda f: not f["primary"])
    return {"id": v["id"], "label": v.get("version_number") or v.get("name") or v["id"],
            "name": v.get("name") or "", "date": v.get("date_published") or "",
            "channel": v.get("version_type") or "release", "mc": v.get("game_versions") or [],
            "files": files,
            "deps": [{"project": d.get("project_id"), "version": d.get("version_id")}
                     for d in v.get("dependencies", []) if d.get("dependency_type") == "required"]}


def mr_versions(project, inst, token=""):
    loaders = [inst["loader"]] + (["fabric"] if inst["loader"] == "quilt" else [])
    params = {"loaders": json.dumps(loaders)}
    mc = inst["mc_version"].strip()
    if mc:
        params["game_versions"] = json.dumps([mc])
    raw = mr_json(f"/project/{urllib.parse.quote(str(project))}/version", params, token=token)
    return [n for n in map(mr_norm, raw) if n]


def cf_file_norm(f):
    name = f.get("fileName") or ""
    if not name.lower().endswith(".jar"):
        return None
    fid = f["id"]
    sha1 = next((h["value"] for h in f.get("hashes", []) if h.get("algo") == 1), None)
    url = f.get("downloadUrl") or CF_EDGE.format(fid // 1000, fid % 1000, urllib.parse.quote(name))
    return {"id": str(fid), "label": f.get("displayName") or name, "name": name,
            "date": f.get("fileDate") or "", "channel": CF_CHANNEL.get(f.get("releaseType"), "release"),
            "mc": [v for v in f.get("gameVersions", []) if v[:1].isdigit()],
            "files": [{"name": name, "url": url, "size": f.get("fileLength"), "sha1": sha1,
                       "sha512": None, "primary": True}],
            "deps": [{"project": str(d["modId"])} for d in f.get("dependencies", [])
                     if d.get("relationType") == 3]}  # 3 = required dependency


def cf_versions(modid, inst, cfg):
    params = {"pageSize": 50}
    mc = inst["mc_version"].strip()
    if mc:
        params["gameVersion"] = mc
    lt = CF_LOADER_TYPE.get(inst["loader"])
    if lt:
        params["modLoaderType"] = lt
    data = cf_json(cfg, f"/mods/{modid}/files", params)["data"]
    data.sort(key=lambda f: f.get("fileDate") or "", reverse=True)
    return [n for n in map(cf_file_norm, data) if n]


def gh_versions(repo, inst, token):
    """GitHub releases that contain a jar fitting this instance."""
    out = []
    for r in http(f"{API}/repos/{repo}/releases?per_page=100", token):
        if r.get("draft") or not candidates(r, inst):
            continue
        out.append({"id": r["tag_name"], "label": r["tag_name"], "name": r.get("name") or "",
                    "date": r.get("published_at") or "",
                    "channel": "beta" if r.get("prerelease") else "release", "mc": [], "files": [], "deps": [],
                    "file_name": os.path.basename(candidates(r, inst)[0][1]["name"])})
    return out


def list_versions(key, inst, cfg):
    """Versions of a tracked mod from its own source, newest first."""
    src = mod_source(key)
    if src == "github":
        return gh_versions(key, inst, cfg.get("token", ""))
    if src == "url":
        return []  # direct jar links have no version catalog
    pid = key.split(":", 1)[1]
    tok = (cfg.get("modrinth_token") or "")
    return mr_versions(pid, inst, token=tok) if src == "modrinth" else cf_versions(pid, inst, cfg)


def pick_latest(versions):
    return next((v for v in versions if v["channel"] == "release"), versions[0] if versions else None)


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


# ================================================================= pack update check (GitHub packs only)
def pack_is_github(inst):
    """Update checks only make sense for packs installed from a GitHub repo."""
    return bool(inst.get("pack_repo")) and inst.get("pack_source", "github") in ("github", "")


def check_github_pack_update(inst, token):
    """-> None if not a GitHub pack / can't compare, else dict(repo, ref, installed, latest, message, newer)."""
    if not pack_is_github(inst):
        return None
    repo, ref = inst["pack_repo"], inst.get("pack_ref") or "main"
    c = http(f"{API}/repos/{repo}/commits/{urllib.parse.quote(ref, safe='/')}", token)
    latest = c["sha"]
    msg = ((c.get("commit") or {}).get("message") or "").strip().splitlines()
    installed = inst.get("pack_sha") or ""
    return {"repo": repo, "ref": ref, "installed": installed, "latest": latest,
            "message": msg[0] if msg else "", "newer": bool(installed) and installed != latest}


# ---- jar metadata cache so the mod list can show every jar without re-opening zips on each refresh
_JAR_CACHE = {}


def jar_info(path):
    """-> (display id string, loaders) for a jar, cached by (path, mtime, size)."""
    try:
        st = os.stat(path)
    except OSError:
        return "", []
    k = (path, st.st_mtime_ns, st.st_size)
    hit = _JAR_CACHE.get(k)
    if hit is None:
        meta = read_meta(path)
        ids = sorted({i for v in meta.values() for i in v["ids"]})
        hit = (", ".join(ids[:2]) + ("..." if len(ids) > 2 else ""), sorted(meta))
        _JAR_CACHE[k] = hit
    return hit


def list_mod_rows(inst):
    """Rows for the mod list: tracked mods first, then every other jar in the mods folder."""
    rows, tracked_files = [], set()
    for key, i in inst["mods"].items():
        tracked_files.add(i.get("file"))
        rows.append({"iid": key, "key": key, "name": i.get("name") or key,
                     "version": i.get("tag") or "-", "file": i.get("file") or "-",
                     "source": mod_source(key),
                     "role": ("dependency of " + ", ".join(
                         (inst["mods"].get(x) or {}).get("name") or x for x in i.get("required_by", [])))
                     if i.get("auto") else "added by you"})
    try:
        files = sorted(os.listdir(inst["mods_dir"]), key=str.lower)
    except FileNotFoundError:
        files = []
    for f in files:
        if f.lower().endswith(".jar") and f not in tracked_files:
            ids, _ = jar_info(os.path.join(inst["mods_dir"], f))
            rows.append({"iid": "jar:" + f, "key": None, "name": ids or f, "version": "-", "file": f,
                         "source": "-", "role": "pack / manual"})
    return rows


def official_profiles_path():
    s = platform.system()
    if s == "Windows":
        root = os.path.join(os.environ.get("APPDATA", HOME), ".minecraft")
    elif s == "Darwin":
        root = os.path.expanduser("~/Library/Application Support/minecraft")
    else:
        root = os.path.expanduser("~/.minecraft")
    return os.path.join(root, "launcher_profiles.json")


def find_official_launcher(cfg):
    """Command (list) that starts the official Minecraft launcher, or None if not found.
    A path set in config ("official_launcher_path") always wins."""
    custom = (cfg.get("official_launcher_path") or "").strip().strip('"')
    if custom:
        if OS_NAME == "osx" and custom.endswith(".app"):
            return ["open", custom]
        return [custom] if os.path.exists(custom) else None
    if OS_NAME == "windows":
        for env in ("ProgramFiles(x86)", "ProgramFiles"):
            exe = os.path.join(os.environ.get(env, ""), "Minecraft Launcher", "MinecraftLauncher.exe")
            if os.path.exists(exe):
                return [exe]
        # Microsoft Store / Xbox app edition (best effort)
        store = os.path.join(os.environ.get("LOCALAPPDATA", ""), "Packages",
                             "Microsoft.4297127D64EC6_8wekyb3d8bbwe")
        if os.path.isdir(store):
            return ["explorer.exe", r"shell:AppsFolder\Microsoft.4297127D64EC6_8wekyb3d8bbwe!Minecraft"]
        return None
    if OS_NAME == "osx":
        for app in ("/Applications/Minecraft.app", os.path.expanduser("~/Applications/Minecraft.app")):
            if os.path.exists(app):
                return ["open", app]
        return None
    exe = shutil.which("minecraft-launcher")
    return [exe] if exe else None


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


def download_all(items, log, label, strict=True):
    """items: (url, dest, sha1, size). Parallel; raises if anything fails (strict) or returns the failures."""
    if not items:
        return []
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
        if strict:
            raise RuntimeError(f"{len(fails)} {label} failed; first: {fails[0]}")
        return fails
    return []


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


# ================================================================= theme
# Default matches the original dark look. Background is user-customizable.
# Charcoal matches the reference UI (soft dark panels, blue accents, green PLAY)
DEFAULT_BG = "#16181f"
BG_PRESETS = (
    ("Snow", "#ffffff"),
    ("Mist", "#e8e8ec"),
    ("Slate", "#6b6f7a"),
    ("Charcoal", "#16181f"),
    ("Midnight", "#0a0a0c"),
)


def _hex_to_rgb(h):
    h = (h or "").strip().lstrip("#")
    if len(h) != 6:
        return 27, 29, 37
    try:
        return int(h[0:2], 16), int(h[2:4], 16), int(h[4:6], 16)
    except ValueError:
        return 27, 29, 37


def _rgb_to_hex(r, g, b):
    return f"#{max(0, min(255, int(r))):02x}{max(0, min(255, int(g))):02x}{max(0, min(255, int(b))):02x}"


def _mix(c1, c2, t):
    """Linear blend of two #rrggbb colors; t=0 -> c1, t=1 -> c2."""
    a, b = _hex_to_rgb(c1), _hex_to_rgb(c2)
    return _rgb_to_hex(a[0] + (b[0] - a[0]) * t, a[1] + (b[1] - a[1]) * t, a[2] + (b[2] - a[2]) * t)


def _luma(h):
    r, g, b = _hex_to_rgb(h)
    return (0.2126 * r + 0.7152 * g + 0.0722 * b) / 255.0


def build_palette(bg_hex=None):
    """
    Derive a full UI palette from a background color.
    Light presets (Snow/Mist) -> dark text; dark presets -> light text;
    mid (Slate) -> dark text on slightly lifted surfaces so labels stay readable.
    """
    bg = (bg_hex or DEFAULT_BG).strip()
    if not re.fullmatch(r"#[0-9A-Fa-f]{6}", bg):
        bg = DEFAULT_BG
    L = _luma(bg)
    # thresholds tuned so Slate (#6b6f7a ~0.43) uses dark text, not washed-out white
    if L >= 0.58:
        mode = "light"
    elif L <= 0.38:
        mode = "dark"
    else:
        mode = "mid"

    if mode == "light":
        # Panels only a hair off the chosen bg (never much darker)
        surface = _mix(bg, "#000000", 0.03)
        surface2 = _mix(bg, "#000000", 0.06)
        border = _mix(bg, "#000000", 0.14)
        glass = surface
        fg = "#0e1016"
        muted = "#4a5160"
        outline = "#ffffff"
        accent, accent_hi = "#2f62d6", "#1e4db8"
        accent_glow = "#a8c4ff"
        green, green_hi = "#178a52", "#0f6b3e"
        green_glow = "#8eecc0"
        red, red_hi = "#c0392b", "#9b2e23"
        red_glow = "#ffb0a8"
        amber = "#9a7209"
        on_accent = "#ffffff"
        btn = _mix(bg, "#000000", 0.05)
        btn_border = _mix(bg, "#000000", 0.16)
    elif mode == "mid":
        surface = _mix(bg, "#ffffff", 0.08)
        surface2 = _mix(bg, "#ffffff", 0.14)
        border = _mix(bg, "#000000", 0.18)
        glass = surface
        fg = "#10131a"
        muted = "#2a303c"
        outline = "#e8ecf4"
        accent, accent_hi = "#3d6fd9", "#2a55c0"
        accent_glow = "#c5d7ff"
        green, green_hi = "#1a8f58", "#147246"
        green_glow = "#b6f0d0"
        red, red_hi = "#c94c48", "#a83c38"
        red_glow = "#ffc4c0"
        amber = "#8a6808"
        on_accent = "#ffffff"
        btn = _mix(bg, "#ffffff", 0.12)
        btn_border = _mix(bg, "#000000", 0.20)
    else:
        # Lift toward white only — panels stay the same family as bg, not a darker navy
        surface = _mix(bg, "#ffffff", 0.06)
        surface2 = _mix(bg, "#ffffff", 0.10)
        border = _mix(bg, "#ffffff", 0.14)
        glass = surface
        fg = "#eef1f8"
        muted = "#8b93a8"
        outline = "#0a0c12"
        accent, accent_hi = "#3d9eff", "#5cb0ff"
        accent_glow = "#1a4a7a"
        green, green_hi = "#2ee59a", "#4dffb3"
        green_glow = "#0d5c3a"
        red, red_hi = "#e85d5d", "#ff7a7a"
        red_glow = "#5a2020"
        amber = "#f0b429"
        on_accent = "#ffffff"
        btn = _mix(bg, "#ffffff", 0.09)
        btn_border = _mix(bg, "#ffffff", 0.16)

    smoke_top = _mix(bg, "#ffffff" if mode != "light" else "#000000", 0.04)
    smoke_bot = bg
    return {
        "bg": bg, "surface": surface, "surface2": surface2, "border": border, "glass": glass,
        "fg": fg, "muted": muted, "outline": outline,
        "accent": accent, "accent_hi": accent_hi, "accent_glow": accent_glow,
        "green": green, "green_hi": green_hi, "green_glow": green_glow,
        "red": red, "red_hi": red_hi, "red_glow": red_glow, "amber": amber,
        "on_accent": on_accent, "smoke_top": smoke_top, "smoke_bot": smoke_bot,
        "btn": btn, "btn_border": btn_border, "mode": mode,
    }


PAL = build_palette(DEFAULT_BG)


def apply_theme(root, bg_hex=None):
    """ttk theme (clam) with glass cards, glow-mapped buttons, high-contrast text."""
    global PAL
    if bg_hex is not None:
        PAL = build_palette(bg_hex)
    P = PAL
    fams = set(tkfont.families(root))
    ff = next((f for f in ("Segoe UI", "SF Pro Text", "Helvetica Neue", "Ubuntu", "DejaVu Sans") if f in fams),
              "TkDefaultFont")
    for nm in ("TkDefaultFont", "TkTextFont", "TkMenuFont", "TkHeadingFont"):
        try:
            tkfont.nametofont(nm).configure(family=ff, size=10)
        except tk.TclError:
            pass
    root.configure(bg=P["bg"])
    root.option_add("*TCombobox*Listbox.background", P["surface2"])
    root.option_add("*TCombobox*Listbox.foreground", P["fg"])
    root.option_add("*TCombobox*Listbox.selectBackground", P["accent"])
    root.option_add("*TCombobox*Listbox.selectForeground", P["on_accent"])
    st = ttk.Style(root)
    try:
        st.theme_use("clam")
    except tk.TclError:
        pass
    bold = (ff, 10, "bold")
    st.configure(".", background=P["bg"], foreground=P["fg"], fieldbackground=P["surface2"],
                 bordercolor=P["border"], lightcolor=P["glass"], darkcolor=P["border"],
                 troughcolor=P["bg"], focuscolor=P["accent_glow"], insertcolor=P["fg"],
                 selectbackground=P["accent"], selectforeground=P["on_accent"])
    st.configure("TFrame", background=P["bg"])
    st.configure("Card.TFrame", background=P["surface"])
    st.configure("Glass.TFrame", background=P["glass"])
    st.configure("TLabel", background=P["bg"], foreground=P["fg"])
    st.configure("Card.TLabel", background=P["surface"], foreground=P["fg"])
    st.configure("Muted.TLabel", background=P["bg"], foreground=P["muted"])
    st.configure("CardMuted.TLabel", background=P["surface"], foreground=P["muted"])
    st.configure("Title.TLabel", background=P["glass"], foreground=P["fg"], font=(ff, 15, "bold"))
    st.configure("Section.TLabel", background=P["surface"], foreground=P["accent_hi"], font=(ff, 8, "bold"))
    st.configure("BgSection.TLabel", background=P["bg"], foreground=P["accent_hi"], font=(ff, 8, "bold"))
    st.configure("Status.TLabel", background=P["surface"], foreground=P["muted"])
    st.configure("TLabelframe", background=P["bg"], bordercolor=P["border"], relief="solid", borderwidth=1)
    st.configure("TLabelframe.Label", background=P["bg"], foreground=P["accent_hi"], font=bold)

    # Soft “pill” buttons (clam approximates rounded via padding + border colors)
    st.configure("TButton", background=P["surface2"], foreground=P["fg"], borderwidth=1,
                 focusthickness=1, focuscolor=P["accent_glow"], padding=(14, 7), relief="flat",
                 bordercolor=P["border"], lightcolor=P["glass"], darkcolor=P["border"])
    st.map("TButton",
           background=[("disabled", P["surface"]), ("pressed", P["border"]), ("active", P["glass"])],
           foreground=[("disabled", P["muted"])],
           bordercolor=[("active", P["accent"]), ("focus", P["accent_hi"])],
           lightcolor=[("active", P["accent_glow"]), ("focus", P["accent_glow"])])

    for name, base, hi, glow in (
            ("Accent", "accent", "accent_hi", "accent_glow"),
            ("Play", "green", "green_hi", "green_glow"),
            ("Danger", "red", "red_hi", "red_glow")):
        st.configure(f"{name}.TButton", background=P[base], foreground=P["on_accent"], font=bold,
                     borderwidth=2, padding=(16, 8), bordercolor=P[glow],
                     lightcolor=P[glow], darkcolor=P[base])
        st.map(f"{name}.TButton",
               background=[("disabled", P["surface2"]), ("pressed", P[base]), ("active", P[hi])],
               foreground=[("disabled", P["muted"])],
               bordercolor=[("active", P[hi]), ("focus", P[glow])],
               lightcolor=[("active", P[glow]), ("focus", P[glow])])
    st.configure("Play.TButton", font=(ff, 12, "bold"), padding=(28, 10))

    st.configure("TEntry", fieldbackground=P["surface2"], foreground=P["fg"], padding=6, borderwidth=1,
                 bordercolor=P["border"], lightcolor=P["glass"], darkcolor=P["border"])
    st.map("TEntry", fieldbackground=[("readonly", P["surface"])],
           bordercolor=[("focus", P["accent"])], lightcolor=[("focus", P["accent_glow"])],
           darkcolor=[("focus", P["accent"])])
    st.configure("TCombobox", fieldbackground=P["surface2"], background=P["surface2"], foreground=P["fg"],
                 arrowcolor=P["muted"], padding=5, bordercolor=P["border"], lightcolor=P["glass"])
    st.map("TCombobox", fieldbackground=[("readonly", P["surface2"])], foreground=[("readonly", P["fg"])],
           selectbackground=[("readonly", P["surface2"])], selectforeground=[("readonly", P["fg"])],
           bordercolor=[("focus", P["accent"])], lightcolor=[("focus", P["accent_glow"])])
    st.configure("TCheckbutton", background=P["bg"], foreground=P["fg"])
    st.map("TCheckbutton", background=[("active", P["bg"])], foreground=[("active", P["fg"])])

    st.configure("TNotebook", background=P["bg"], borderwidth=0, tabmargins=(4, 4, 0, 0))
    st.configure("TNotebook.Tab", background=P["surface"], foreground=P["muted"], padding=(22, 9),
                 borderwidth=1, bordercolor=P["border"], lightcolor=P["glass"])
    st.map("TNotebook.Tab", background=[("selected", P["glass"]), ("active", P["surface2"])],
           foreground=[("selected", P["fg"]), ("active", P["fg"])],
           bordercolor=[("selected", P["accent"])], lightcolor=[("selected", P["accent_glow"])])

    st.configure("Treeview", background=P["surface"], fieldbackground=P["surface"], foreground=P["fg"],
                 rowheight=27, borderwidth=0, relief="flat")
    st.map("Treeview", background=[("selected", P["accent"])], foreground=[("selected", P["on_accent"])])
    st.configure("Treeview.Heading", background=P["surface2"], foreground=P["fg"], relief="flat",
                 borderwidth=0, padding=(8, 6), font=bold)
    st.map("Treeview.Heading", background=[("active", P["border"])])
    for o in ("Vertical", "Horizontal"):
        st.configure(f"{o}.TScrollbar", background=P["surface2"], troughcolor=P["bg"], bordercolor=P["bg"],
                     arrowcolor=P["muted"], relief="flat", gripcount=0)
        st.map(f"{o}.TScrollbar", background=[("active", P["border"])])
    st.configure("Horizontal.TProgressbar", troughcolor=P["surface2"], background=P["accent"],
                 bordercolor=P["surface"], lightcolor=P["accent_glow"], darkcolor=P["accent"], thickness=6)
    st.configure("TPanedwindow", background=P["bg"])
    st.configure("Sash", background=P["bg"], sashthickness=6)
    return ff


def draw_smoke_gradient(canvas, width, height):
    """Soft vertical smoke fade used behind the header / title bar."""
    P = PAL
    canvas.delete("smoke")
    if width < 2 or height < 2:
        return
    steps = max(8, min(height, 48))
    for i in range(steps):
        t = i / max(1, steps - 1)
        col = _mix(P["smoke_top"], P["smoke_bot"], t)
        y0 = int(i * height / steps)
        y1 = int((i + 1) * height / steps)
        canvas.create_rectangle(0, y0, width, y1, outline="", fill=col, tags="smoke")


def draw_outlined_text(canvas, x, y, text, font, fill, outline=None, tag="otxt"):
    """Title text (outline arg kept for call-site compatibility; no border drawn)."""
    canvas.delete(tag)
    canvas.create_text(x, y, text=text, font=font, fill=fill, anchor="w", tags=tag)




def _pil_round_rect(draw, box, radius, fill, outline=None, width=1):
    draw.rounded_rectangle(box, radius=radius, fill=fill, outline=outline, width=width)


def _pil_vertical_gradient(size, top_rgb, bot_rgb):
    w, h = size
    img = Image.new("RGB", (w, h))
    px = img.load()
    for y in range(h):
        t = y / max(1, h - 1)
        r = int(top_rgb[0] + (bot_rgb[0] - top_rgb[0]) * t)
        g = int(top_rgb[1] + (bot_rgb[1] - top_rgb[1]) * t)
        b = int(top_rgb[2] + (bot_rgb[2] - top_rgb[2]) * t)
        for x in range(w):
            px[x, y] = (r, g, b)
    return img


def _photo_from_pil(img, master):
    """RGBA/RGB PIL image -> tk.PhotoImage (PNG base64). Keeps a ref on master._photo_refs."""
    if img.mode not in ("RGB", "RGBA"):
        img = img.convert("RGBA")
    buf = io.BytesIO()
    img.save(buf, format="PNG")
    data = base64.b64encode(buf.getvalue())
    photo = tk.PhotoImage(data=data, master=master)
    refs = getattr(master, "_photo_refs", None)
    if refs is None:
        refs = []
        master._photo_refs = refs
    refs.append(photo)
    if len(refs) > 80:
        master._photo_refs = refs[-40:]
    return photo


def render_glass_bar(master, width, height, radius=16):
    """Smoky glass header strip with soft glow edge. Falls back to flat color without PIL."""
    P = PAL
    width, height = max(8, int(width)), max(8, int(height))
    if not HAS_PIL:
        img = None
        return None
    top, bot = _hex_to_rgb(P["smoke_top"]), _hex_to_rgb(P["smoke_bot"])
    base = _pil_vertical_gradient((width, height), top, bot).convert("RGBA")
    # frosted overlay
    frost = Image.new("RGBA", (width, height), _hex_to_rgb(P["glass"]) + (90,))
    base = Image.alpha_composite(base, frost)
    # rounded mask
    mask = Image.new("L", (width, height), 0)
    md = ImageDraw.Draw(mask)
    md.rounded_rectangle((0, 0, width - 1, height - 1), radius=radius, fill=255)
    out = Image.new("RGBA", (width, height), (0, 0, 0, 0))
    out.paste(base, (0, 0))
    out.putalpha(mask)
    # inner highlight line
    d = ImageDraw.Draw(out)
    d.rounded_rectangle((1, 1, width - 2, height - 2), radius=max(1, radius - 1),
                        outline=_hex_to_rgb(P["accent_glow"]) + (110,), width=1)
    return _photo_from_pil(out, master)


def render_glow_button(master, text, width, height, kind="accent", radius=None, font_size=12):
    """
    Antialiased rounded button with outer glow. kind: accent|play|danger|normal
    Returns (photo_idle, photo_hover) or (None, None) without PIL.
    """
    if not HAS_PIL:
        return None, None
    P = PAL
    width, height = max(40, int(width)), max(26, int(height))
    if radius is None:
        radius = max(8, height // 2)  # full pill
    if kind == "play":
        fill, glow, tcol = P["green"], P["green_glow"], P["on_accent"]
    elif kind == "danger":
        fill, glow, tcol = P["red"], P["red_glow"], P["on_accent"]
    elif kind == "accent":
        fill, glow, tcol = P["accent"], P["accent_glow"], P["on_accent"]
    else:
        # Same color family as the chosen background (slightly lifted, never darker)
        fill, glow, tcol = P.get("btn", P["surface2"]), P.get("btn_border", P["border"]), P["fg"]

    def make(hover=False):
        pad = 4
        W, H = width + pad * 2, height + pad * 2
        canvas = Image.new("RGBA", (W, H), (0, 0, 0, 0))
        # subtle edge light only (glow kept very low)
        glow_layer = Image.new("RGBA", (W, H), (0, 0, 0, 0))
        gd = ImageDraw.Draw(glow_layer)
        strength = (55 if hover else 28) if kind == "play" else (40 if hover else 18)
        blur = 2.2 if hover else 1.6
        gr = _hex_to_rgb(glow) + (strength,)
        gd.rounded_rectangle((pad - 2, pad - 2, pad + width + 1, pad + height + 1),
                             radius=radius + 2, fill=gr)
        glow_layer = glow_layer.filter(ImageFilter.GaussianBlur(radius=blur))
        canvas = Image.alpha_composite(canvas, glow_layer)
        # body
        body = Image.new("RGBA", (W, H), (0, 0, 0, 0))
        bd = ImageDraw.Draw(body)
        face = _mix(fill, "#ffffff", 0.08 if hover else 0.0)
        bd.rounded_rectangle((pad, pad, pad + width - 1, pad + height - 1),
                             radius=radius, fill=_hex_to_rgb(face) + (250,),
                             outline=_hex_to_rgb(glow) + (90,), width=1)
        # top glass sheen
        sheen = Image.new("RGBA", (W, H), (0, 0, 0, 0))
        sd = ImageDraw.Draw(sheen)
        sd.rounded_rectangle((pad + 2, pad + 2, pad + width - 3, pad + height // 2),
                             radius=max(1, radius - 2), fill=(255, 255, 255, 22 if hover else 12))
        body = Image.alpha_composite(body, sheen)
        # text
        try:
            font = ImageFont.truetype("DejaVuSans-Bold.ttf", font_size)
        except Exception:
            try:
                font = ImageFont.truetype("arial.ttf", font_size)
            except Exception:
                font = ImageFont.load_default()
        td = ImageDraw.Draw(body)
        bbox = td.textbbox((0, 0), text, font=font)
        tw, th = bbox[2] - bbox[0], bbox[3] - bbox[1]
        tx = pad + (width - tw) // 2 - bbox[0]
        ty = pad + (height - th) // 2 - bbox[1]
        td.text((tx, ty), text, font=font, fill=_hex_to_rgb(tcol) + (255,))
        canvas = Image.alpha_composite(canvas, body)
        return _photo_from_pil(canvas, master)

    return make(False), make(True)


def widget_bg(w):
    """The background colour actually showing behind widget `w` (its own bg for tk widgets,
    its ttk style's background for ttk widgets, else the nearest parent's)."""
    while w is not None:
        try:
            return w.cget("bg")
        except (tk.TclError, AttributeError):
            pass
        try:  # ttk widget: "Card.TFrame" etc. (empty = the class default, e.g. "TFrame")
            style = w.cget("style") or w.winfo_class()
            c = ttk.Style(w).lookup(style, "background")
            if c:
                return c
        except (tk.TclError, AttributeError):
            pass
        w = getattr(w, "master", None)
    return PAL.get("bg", "#16181f")


class GlowButton(tk.Label):
    """Rounded glowing pill button for every preset. Uses PIL when available; flat colored fallback otherwise."""

    def __init__(self, master, text="", command=None, kind="normal", width=None, height=32,
                 font_size=12, padx=16, **kw):
        self._command = command
        self._kind = kind or "normal"
        self._text = text
        self._font_size = font_size
        self._padx = padx
        self._bh = height
        # Auto width from text length if not given
        if width is None:
            width = max(52, int(len(text) * (font_size * 0.62) + padx * 2))
        self._bw = width
        top = master.winfo_toplevel()
        idle, hover = render_glow_button(top, text, width, height, self._kind, font_size=font_size)
        self._idle, self._hover = idle, hover
        parent_bg = widget_bg(master)  # the pill's transparent corners must match what is behind it
        if idle is not None:
            super().__init__(master, image=idle, bd=0, highlightthickness=0, cursor="hand2", bg=parent_bg)
            self.bind("<Enter>", lambda e: self.configure(image=self._hover or self._idle))
            self.bind("<Leave>", lambda e: self.configure(image=self._idle))
            self.bind("<Button-1>", self._fire)
        else:
            P = PAL
            colors = {
                "play": (P["green"], P["green_hi"], P["on_accent"]),
                "danger": (P["red"], P["red_hi"], P["on_accent"]),
                "accent": (P["accent"], P["accent_hi"], P["on_accent"]),
                "normal": (P["surface2"], P["border"], P["fg"]),
            }.get(self._kind, (P["surface2"], P["border"], P["fg"]))
            super().__init__(master, text=text, bd=0, padx=padx, pady=max(4, height // 5), cursor="hand2",
                             bg=colors[0], fg=colors[2], font=("", font_size, "bold"),
                             activebackground=colors[1], activeforeground=colors[2])
            self.bind("<Button-1>", self._fire)

    def _fire(self, _event=None):
        if self._command:
            self._command()

    def refresh_art(self):
        top = self.winfo_toplevel()
        idle, hover = render_glow_button(top, self._text, self._bw, self._bh,
                                         self._kind, font_size=self._font_size)
        parent_bg = widget_bg(self.master)
        if idle is not None:
            self._idle, self._hover = idle, hover
            self.configure(image=idle, bg=parent_bg)
        else:
            P = PAL
            colors = {
                "play": (P["green"], P["on_accent"]),
                "danger": (P["red"], P["on_accent"]),
                "accent": (P["accent"], P["on_accent"]),
                "normal": (P["surface2"], P["fg"]),
            }.get(self._kind, (P["surface2"], P["fg"]))
            self.configure(bg=colors[0], fg=colors[1])


class GlassPanel(tk.Frame):
    """Rounded glass card: background image + content frame on top."""

    def __init__(self, master, radius=18, pad=12, **kw):
        bg = PAL.get("bg", "#1b1d25")
        super().__init__(master, bg=bg, highlightthickness=0, bd=0, **kw)
        self._radius = radius
        self._pad = pad
        self._bg_lbl = tk.Label(self, bd=0, highlightthickness=0, bg=bg)
        self._bg_lbl.place(x=0, y=0, relwidth=1, relheight=1)
        self.body = tk.Frame(self, bg=PAL.get("surface", bg), highlightthickness=0, bd=0)
        self.body.pack(fill="both", expand=True, padx=pad, pady=pad)
        self.bind("<Configure>", self._redraw)
        self._photo = None

    def _redraw(self, event=None):
        w, h = max(self.winfo_width(), 4), max(self.winfo_height(), 4)
        P = PAL
        # Content + chrome use the same surface tint so buttons don't sit on a mismatched strip
        tint = P["surface"]
        if not HAS_PIL:
            self.configure(bg=P["bg"])
            self._bg_lbl.configure(bg=tint, image="")
            self.body.configure(bg=tint)
            return
        img = Image.new("RGBA", (w, h), (0, 0, 0, 0))
        # very soft shadow
        sh = Image.new("RGBA", (w, h), (0, 0, 0, 0))
        sd = ImageDraw.Draw(sh)
        sd.rounded_rectangle((2, 3, w - 1, h - 1), radius=self._radius, fill=(0, 0, 0, 50))
        sh = sh.filter(ImageFilter.GaussianBlur(radius=3))
        img = Image.alpha_composite(img, sh)
        # flat surface panel (same tint as body) with slight top→bottom smoke
        # Keep panel the same tint as surface (no darker bottom fade)
        base = _pil_vertical_gradient((w, h), _hex_to_rgb(tint),
                                     _hex_to_rgb(_mix(tint, "#ffffff", 0.02))).convert("RGBA")
        mask = Image.new("L", (w, h), 0)
        ImageDraw.Draw(mask).rounded_rectangle((0, 0, w - 1, h - 1), radius=self._radius, fill=255)
        rounded = Image.new("RGBA", (w, h), (0, 0, 0, 0))
        rounded.paste(base, (0, 0))
        rounded.putalpha(mask)
        img = Image.alpha_composite(img, rounded)
        d = ImageDraw.Draw(img)
        d.rounded_rectangle((1, 1, w - 2, h - 2), radius=self._radius,
                            outline=_hex_to_rgb(P["border"]) + (160,), width=1)
        self._photo = _photo_from_pil(img, self.winfo_toplevel())
        self.configure(bg=P["bg"])
        self._bg_lbl.configure(image=self._photo, bg=P["bg"])
        self.body.configure(bg=tint)
        for ch in self.body.winfo_children():
            try:
                if isinstance(ch, tk.Label):
                    ch.configure(bg=tint)
                elif isinstance(ch, tk.Frame):
                    ch.configure(bg=tint)
            except tk.TclError:
                pass



# ================================================================= app
LAUNCHERS = ("builtin", "official")
EDIT_KEYS = ("mc_version", "loader", "loader_version", "mods_dir", "java_path", "memory_mb", "jvm_args",
             "launch_cmd", "window_width", "window_height", "game_args", "env_vars",
             "pre_launch_cmd", "launcher")
RAW_KEYS = ("mods_dir", "launch_cmd", "game_args", "env_vars", "pre_launch_cmd")  # saved without strip()


def parse_env_vars(text):
    """'A=1;B=two words' (or newline separated) -> {'A': '1', 'B': 'two words'}."""
    out = {}
    for part in re.split(r"[;\n]", text or ""):
        if "=" in part:
            k, v = part.split("=", 1)
            if k.strip():
                out[k.strip()] = v.strip()
    return out


class App(tk.Tk):
    def __init__(self):
        super().__init__()
        self.title(f"GH Mod Launcher v{LAUNCHER_VERSION}")
        self.geometry("1060x800")
        self.minsize(780, 580)
        self.cfg = load_config()
        self.ff = apply_theme(self, self.cfg.get("theme_bg") or DEFAULT_BG)
        self.busy = False
        self.game_proc = None
        self._loading = False
        self.latest = {}          # instance -> {mod key: latest-version text}
        self._themed_tk = []      # plain tk widgets that need bg/fg refresh on theme change
        self._names = {}          # mod key -> display name cache
        self._pack_checked = set()  # instances whose pack was already checked this session
        self._wheel_canvases = []   # canvases that act as scrollable pages (see _scroll_area)
        self._install_wheel_router()
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


    # ---- scrolling
    def _install_wheel_router(self):
        """One mouse-wheel handler for the whole app. It scrolls whatever is under the pointer:
        a list/log that has more to show, otherwise the scrollable page it sits in."""
        for cls in ("Treeview", "Text", "Listbox", "TCombobox"):
            for seq in ("<MouseWheel>", "<Button-4>", "<Button-5>"):
                try:
                    self.unbind_class(cls, seq)  # default class handlers would double-scroll / change comboboxes
                except tk.TclError:
                    pass
        for seq in ("<MouseWheel>", "<Button-4>", "<Button-5>"):
            self.bind_all(seq, self._on_wheel)

    def _on_wheel(self, event):
        num = event.num if isinstance(getattr(event, "num", None), int) else 0
        delta = getattr(event, "delta", 0) or 0
        if num == 4:
            direction, notches = -1, 1
        elif num == 5:
            direction, notches = 1, 1
        elif delta:
            direction = -1 if delta > 0 else 1
            notches = max(1, abs(delta) // 120)
        else:
            return None
        try:
            w = self.winfo_containing(event.x_root, event.y_root)
            g = self.grab_current()
        except (KeyError, tk.TclError):
            return None
        if g is not None and w is not None and w.winfo_toplevel() is not g.winfo_toplevel():
            return None  # a dropdown / dialog is open; don't scroll the page behind it
        while w is not None:
            try:
                if isinstance(w, (ttk.Treeview, tk.Text, tk.Listbox)):
                    lo, hi = w.yview()
                    if lo > 0.0 or hi < 1.0:
                        w.yview_scroll(direction * notches * 3, "units")
                        return "break"
                elif any(w is c for c in self._wheel_canvases):
                    lo, hi = w.yview()
                    if lo > 0.0 or hi < 1.0:
                        w.yview_scroll(direction * notches * 3, "units")
                        return "break"
            except tk.TclError:
                pass
            w = getattr(w, "master", None)
        return None

    def _scroll_area(self, parent, padding=0):
        """A vertically scrollable page inside `parent`. Returns the frame to put content in.
        The content stretches to the page width (and height when the window is taller than it)."""
        P = PAL
        cv = tk.Canvas(parent, bg=P["bg"], highlightthickness=0, borderwidth=0, yscrollincrement=20)
        sb = ttk.Scrollbar(parent, orient="vertical", command=cv.yview)
        cv.configure(yscrollcommand=sb.set)
        sb.pack(side="right", fill="y")
        cv.pack(side="left", fill="both", expand=True)
        inner = ttk.Frame(cv, padding=padding)
        wid = cv.create_window((0, 0), window=inner, anchor="nw")

        def sync(_e=None):
            cv.configure(scrollregion=(0, 0, max(inner.winfo_reqwidth(), cv.winfo_width()),
                                       max(inner.winfo_reqheight(), cv.winfo_height())))
            cv.itemconfigure(wid, width=cv.winfo_width(), height=max(inner.winfo_reqheight(), cv.winfo_height()))

        inner.bind("<Configure>", sync)
        cv.bind("<Configure>", sync)
        self._wheel_canvases.append(cv)
        self._themed_tk.append(cv)
        return inner

    def _gbtn(self, parent, text, command=None, kind="normal", height=32, font_size=12, width=None, **pack):
        """Create a rounded GlowButton, track it for theme refresh, pack if pack kwargs given."""
        btn = GlowButton(parent, text=text, command=command, kind=kind, height=height,
                         font_size=font_size, width=width)
        self._glow_buttons.append(btn)
        if pack:
            btn.pack(**pack)
        return btn

    def _gpanel(self, parent, radius=18, pad=12, **pack):
        """Rounded glass card; track for theme refresh."""
        panel = GlassPanel(parent, radius=radius, pad=pad)
        self._glass_panels.append(panel)
        if pack:
            panel.pack(**pack)
        return panel

    # ---- UI
    def _build(self):
        P = PAL
        ff = self.ff
        self._glow_buttons = []
        self._glass_panels = []

        # ---- header: PIL glass strip + outlined title
        head = tk.Frame(self, bg=P["bg"], highlightthickness=0)
        head.pack(fill="x", padx=10, pady=(10, 0))
        self._header_canvas = tk.Canvas(head, height=64, highlightthickness=0, borderwidth=0, bg=P["bg"])
        self._header_canvas.pack(fill="x")
        self._themed_tk.append(self._header_canvas)

        def _paint_header(_event=None):
            c = self._header_canvas
            w = max(c.winfo_width(), 2)
            h = max(c.winfo_height(), 2)
            c.delete("all")
            photo = render_glass_bar(self, w, h, radius=22)
            if photo is not None:
                c.create_image(0, 0, image=photo, anchor="nw", tags="bg")
                c._glass_photo = photo
            else:
                draw_smoke_gradient(c, w, h)
            draw_outlined_text(c, 22, h // 2 - 2, "GH Mod Launcher",
                               (ff, 17, "bold"), PAL["fg"], PAL["outline"], tag="title")
            c.create_text(22 + 190, h // 2 + 1, text=f"v{LAUNCHER_VERSION}",
                          font=(ff, 9), fill=PAL["muted"], anchor="w", tags="title")

        self._header_canvas.bind("<Configure>", _paint_header)
        self._paint_header = _paint_header

        hin = tk.Frame(head, bg=P["bg"], highlightthickness=0)
        hin.place(relx=1.0, rely=0.5, anchor="e", x=-16, y=0)
        acct = tk.Frame(hin, bg=P["bg"], highlightthickness=0)
        acct.pack(side="right")
        self.auth_status = tk.StringVar(value="Not logged in")
        ttk.Label(acct, textvariable=self.auth_status, style="Muted.TLabel").pack(side="left", padx=(0, 10))
        gb = GlowButton(acct, text="Log in with Microsoft", command=self.ms_login, kind="accent",
                        width=180, height=34, font_size=12)
        gb.pack(side="left", padx=2)
        self._glow_buttons.append(gb)
        self._gbtn(acct, "Log out", self.ms_logout, kind="normal", height=32).pack(side="left", padx=2)
        update_row = tk.Frame(hin, bg=P["bg"], highlightthickness=0)
        update_row.pack(side="right", padx=12)
        self.update_btn = ttk.Button(update_row, text="Update launcher", style="Accent.TButton",
                                     command=self._do_self_update)
        self.update_lbl = ttk.Label(update_row, text="", style="Muted.TLabel")
        self._update_auth_status()

        # ---- bottom action bar
        act = tk.Frame(self, bg=P["bg"], highlightthickness=0)
        act.pack(side="bottom", fill="x", padx=10, pady=10)
        ain = GlassPanel(act, radius=20, pad=12)
        ain.pack(fill="x")
        self._glass_panels.append(ain)
        ain_body = ain.body
        play_btn = GlowButton(ain_body, text="PLAY", command=self.play, kind="play",
                              width=120, height=42, font_size=16)
        play_btn.pack(side="right")
        self._glow_buttons.append(play_btn)
        self._gbtn(ain_body, "Stop", self.stop_game, kind="normal", height=32).pack(side="right", padx=(0, 8))
        self._gbtn(ain_body, "Install / prepare", lambda: self.start_run(False),
                   kind="normal", height=32).pack(side="right", padx=(0, 8))
        self._gbtn(ain_body, "Sync to official launcher", self.sync_profile,
                   kind="normal", height=32).pack(side="right", padx=(0, 8))
        self.progress = ttk.Progressbar(ain_body, mode="indeterminate", length=120)
        self.progress.pack(side="left")
        self.status_var = tk.StringVar(value="Ready")
        ttk.Label(ain_body, textvariable=self.status_var, style="Status.TLabel").pack(side="left", padx=10)

        # ---- profile bar
        bar = ttk.Frame(self)
        bar.pack(fill="x", padx=16, pady=(12, 8))
        ttk.Label(bar, text="PROFILE", style="BgSection.TLabel").pack(side="left", padx=(0, 8))
        self.inst_var = tk.StringVar()
        self.inst_box = ttk.Combobox(bar, textvariable=self.inst_var, state="readonly", width=28)
        self.inst_box.pack(side="left")
        self.inst_box.bind("<<ComboboxSelected>>", lambda _: self._select_instance(self.inst_var.get()))
        self._gbtn(bar, "New profile", self.new_instance, kind="normal", height=32).pack(side="left", padx=(10, 4))
        self._gbtn(bar, "Open folder", self.open_folder, kind="normal", height=32).pack(side="left", padx=2)
        self._gbtn(bar, "Delete", self.delete_instance, kind="danger", height=32).pack(side="left", padx=2)

        # ---- main area: tabs on top, activity log underneath
        paned = ttk.PanedWindow(self, orient="vertical")
        paned.pack(fill="both", expand=True, padx=16, pady=(0, 8))
        nb = ttk.Notebook(paned)
        paned.add(nb, weight=4)
        log_fr = ttk.Frame(paned)
        paned.add(log_fr, weight=1)

        # ================= Mods tab
        mods_page = ttk.Frame(nb)
        nb.add(mods_page, text="Mods")
        mods = self._scroll_area(mods_page, padding=12)

        card_p = self._gpanel(mods, radius=18, pad=12, fill="x")
        card = card_p.body
        ttk.Label(card, text="MODPACK", style="Section.TLabel").pack(anchor="w")
        self.pack_lbl = ttk.Label(card, text="", style="Card.TLabel", font=(ff, 11, "bold"))
        self.pack_lbl.pack(anchor="w", pady=(2, 8))
        prow = ttk.Frame(card, style="Card.TFrame")
        prow.pack(fill="x")
        self._gbtn(prow, "Install Fathom Studios pack", self.install_fathom_pack,
                   kind="normal", height=32).pack(side="left")
        self._gbtn(prow, "Install pack from link...", self.install_pack_dialog,
                   kind="normal", height=32).pack(side="left", padx=6)
        self._gbtn(prow, "Check pack update", self.check_pack_update,
                   kind="normal", height=32).pack(side="left")
        self._gbtn(prow, "Reinstall pack", self.reinstall_current_pack,
                   kind="normal", height=32).pack(side="left", padx=6)

        add_p = self._gpanel(mods, radius=18, pad=12, fill="x", pady=(10, 0))
        addc = add_p.body
        ttk.Label(addc, text="ADD MOD", style="Section.TLabel").pack(anchor="w")
        arow = ttk.Frame(addc, style="Card.TFrame")
        arow.pack(fill="x", pady=(4, 0))
        self.repo_var = tk.StringVar()
        e = ttk.Entry(arow, textvariable=self.repo_var)
        e.pack(side="left", fill="x", expand=True)
        e.bind("<Return>", lambda _: self.add_repo())
        self._gbtn(arow, "Add + install", self.add_repo, kind="accent", height=34).pack(side="left", padx=(8, 0))
        ttk.Label(addc, text="GitHub owner/name or URL  ·  Modrinth / CurseForge link  ·  modrinth:slug / curseforge:slug",
                  style="CardMuted.TLabel").pack(anchor="w", pady=(4, 0))

        # ---- Search mods (Modrinth + CurseForge) with loader filter
        src_p = self._gpanel(mods, radius=18, pad=12, fill="x", pady=(10, 0))
        src = src_p.body
        ttk.Label(src, text="SEARCH MODS", style="Section.TLabel").pack(anchor="w")
        srow = ttk.Frame(src, style="Card.TFrame")
        srow.pack(fill="x", pady=(4, 0))
        self.search_var = tk.StringVar()
        se = ttk.Entry(srow, textvariable=self.search_var)
        se.pack(side="left", fill="x", expand=True)
        se.bind("<Return>", lambda _: self.search_mods())
        ttk.Label(srow, text="Loader", style="CardMuted.TLabel").pack(side="left", padx=(10, 4))
        self.search_loader = tk.StringVar(value="profile")
        ttk.Combobox(srow, textvariable=self.search_loader,
                     values=("profile", "any") + LOADERS, state="readonly", width=10).pack(side="left")
        ttk.Label(srow, text="Source", style="CardMuted.TLabel").pack(side="left", padx=(10, 4))
        self.search_source = tk.StringVar(value="modrinth")
        ttk.Combobox(srow, textvariable=self.search_source,
                     values=("modrinth", "curseforge", "both"), state="readonly", width=11).pack(side="left")
        self._gbtn(srow, "Search", self.search_mods, kind="accent", height=34).pack(side="left", padx=(8, 0))
        ttk.Label(src, text="Loader “profile” uses this instance’s loader + MC version. CurseForge search needs an API key in Settings.",
                  style="CardMuted.TLabel").pack(anchor="w", pady=(4, 0))
        srf = ttk.Frame(src, style="Card.TFrame")
        srf.pack(fill="x", pady=(6, 0))
        scols = ("name", "source", "downloads", "description")
        self.search_tree = ttk.Treeview(srf, columns=scols, show="headings", height=5, selectmode="browse")
        for c, w in zip(scols, (200, 90, 90, 420)):
            self.search_tree.heading(c, text=c.capitalize())
            self.search_tree.column(c, width=w, minwidth=50)
        srsb = ttk.Scrollbar(srf, orient="vertical", command=self.search_tree.yview)
        self.search_tree.configure(yscrollcommand=srsb.set)
        srsb.pack(side="right", fill="y")
        self.search_tree.pack(side="left", fill="x", expand=True)
        self.search_tree.bind("<Double-1>", lambda _e: self.install_search_selected())
        sbtn = ttk.Frame(src, style="Card.TFrame")
        sbtn.pack(fill="x", pady=(6, 0))
        self._gbtn(sbtn, "Install selected", self.install_search_selected, kind="accent", height=32).pack(side="left")
        self._gbtn(sbtn, "Open on website", self.open_search_selected, kind="normal", height=32).pack(side="left", padx=6)
        self._search_hits = {}  # tree iid -> result dict

        lh = ttk.Frame(mods)
        lh.pack(fill="x", pady=(12, 4))
        ttk.Label(lh, text="INSTALLED MODS", style="BgSection.TLabel").pack(side="left")
        self.count_lbl = ttk.Label(lh, text="", style="Muted.TLabel")
        self.count_lbl.pack(side="left", padx=10)
        self.filter_var = tk.StringVar()
        ttk.Entry(lh, textvariable=self.filter_var, width=24).pack(side="right")
        ttk.Label(lh, text="Filter", style="Muted.TLabel").pack(side="right", padx=6)
        self.filter_var.trace_add("write", lambda *_: self._refresh_list())

        tf = ttk.Frame(mods)
        tf.pack(fill="both", expand=True)
        cols = ("name", "version", "jar file", "latest", "source", "role")
        self.tree = ttk.Treeview(tf, columns=cols, show="headings", height=8)
        for c, w in zip(cols, (180, 110, 260, 110, 80, 170)):
            self.tree.heading(c, text=c.capitalize())
            self.tree.column(c, width=w, minwidth=60)
        self.tree.tag_configure("update", foreground=P["amber"])
        self.tree.tag_configure("dep", foreground=P["accent_hi"])
        self.tree.tag_configure("pack", foreground=P["muted"])
        tsb = ttk.Scrollbar(tf, orient="vertical", command=self.tree.yview)
        self.tree.configure(yscrollcommand=tsb.set)
        tsb.pack(side="right", fill="y")
        self.tree.pack(side="left", fill="both", expand=True)

        b0 = ttk.Frame(mods)
        b0.pack(fill="x", pady=(8, 0))
        self._gbtn(b0, "Check updates", self.check_updates, kind="accent", height=32).pack(side="left")
        for text, cmd, kind in (
                ("Versions...", self.show_versions, "normal"),
                ("Update selected", lambda: self.update_mods(True), "normal"),
                ("Update all", lambda: self.update_mods(False), "normal"),
                ("Verify hashes", self.verify, "normal"),
                ("Export modlist", self.export_modlist, "normal")):
            self._gbtn(b0, text, cmd, kind=kind, height=28).pack(side="left", padx=(6, 0))
        self._gbtn(b0, "Remove selected", self.remove_selected, kind="danger", height=32).pack(side="right")

        # ================= Settings tab (scrolls on small windows)
        set_tab = ttk.Frame(nb)
        nb.add(set_tab, text="Settings")
        sc = tk.Canvas(set_tab, bg=P["bg"], highlightthickness=0, borderwidth=0)
        self._settings_canvas = sc
        self._themed_tk.append(sc)
        ssb = ttk.Scrollbar(set_tab, orient="vertical", command=sc.yview)
        sc.configure(yscrollcommand=ssb.set)
        ssb.pack(side="right", fill="y")
        sc.pack(side="left", fill="both", expand=True)
        sform = ttk.Frame(sc, padding=(14, 12))
        sid = sc.create_window((0, 0), window=sform, anchor="nw")
        sform.bind("<Configure>", lambda _e: sc.configure(scrollregion=sc.bbox("all")))
        sc.bind("<Configure>", lambda e: sc.itemconfigure(sid, width=e.width))
        sc.configure(yscrollincrement=20)
        self._wheel_canvases.append(sc)  # wheel handled by the app-wide router

        self.vars = {k: tk.StringVar() for k in EDIT_KEYS}
        self.fullscreen_var = tk.BooleanVar()
        self.version_var = tk.StringVar()
        self.runtime_var = tk.StringVar(value=self.cfg.get("runtime_dir", ""))
        self.official_var = tk.StringVar(value=self.cfg.get("official_launcher_path", ""))
        sections = (
            ("Profile - game", (
                ("MC version (e.g. 1.20.1)", self.vars["mc_version"], None),
                ("Loader", self.vars["loader"], "loader"),
                ("Loader version (blank = latest)", self.vars["loader_version"], None),
                ("Memory (MB)", self.vars["memory_mb"], None),
                ("Extra JVM args", self.vars["jvm_args"], None),
                ("Game args (extra Minecraft args)", self.vars["game_args"], None),
                ("Window width (blank = default)", self.vars["window_width"], None),
                ("Window height (blank = default)", self.vars["window_height"], None),
                ("Fullscreen", self.fullscreen_var, "check"),
                ("Env vars (KEY=VAL;KEY2=VAL2)", self.vars["env_vars"], None),
                ("Pre-launch cmd (runs before game)", self.vars["pre_launch_cmd"], None))),
            ("Profile - paths and launcher", (
                ("Mods dir", self.vars["mods_dir"], None),
                ("Java path (blank = Mojang runtime)", self.vars["java_path"], None),
                ("External launch cmd (blank = built-in)", self.vars["launch_cmd"], None),
                ("Launcher (builtin / official Minecraft)", self.vars["launcher"], "launcher"),
                ("Installed version id", self.version_var, "ro"))),
            ("Global - all profiles", (
                ("Official launcher path (blank = auto)", self.official_var, None),
                ("Runtime dir", self.runtime_var, None))))
        self.token_var = tk.StringVar(value=self.cfg.get("token", ""))
        self.cf_var = tk.StringVar(value=self.cfg.get("curseforge_api_key", ""))
        self.mr_token_var = tk.StringVar(value=self.cfg.get("modrinth_token", ""))
        for title, rows in sections:
            lf = ttk.LabelFrame(sform, text=f" {title} ", padding=(12, 8))
            lf.pack(fill="x", pady=(0, 12))
            lf.columnconfigure(1, weight=1)
            for r, (lbl, var, kind) in enumerate(rows):
                ttk.Label(lf, text=lbl).grid(row=r, column=0, sticky="w", padx=(0, 14), pady=3)
                if kind == "loader":
                    w = ttk.Combobox(lf, textvariable=var, values=LOADERS, state="readonly", width=14)
                elif kind == "launcher":
                    w = ttk.Combobox(lf, textvariable=var, values=LAUNCHERS, state="readonly", width=14)
                elif kind == "check":
                    w = ttk.Checkbutton(lf, variable=var)
                elif kind == "ro":
                    w = ttk.Entry(lf, textvariable=var, state="readonly")
                elif kind == "secret":
                    w = ttk.Entry(lf, textvariable=var, show="*")
                else:
                    w = ttk.Entry(lf, textvariable=var)
                w.grid(row=r, column=1, sticky="w" if kind in ("loader", "launcher", "check") else "we", pady=3)
                if kind != "ro":
                    var.trace_add("write", lambda *_: self._save_settings())

        # API keys — with clickable links to where you create them
        keys_lf = ttk.LabelFrame(sform, text=" API keys ", padding=(12, 8))
        keys_lf.pack(fill="x", pady=(0, 12))
        keys_lf.columnconfigure(1, weight=1)

        def _key_row(row, label, var, url, link_text):
            ttk.Label(keys_lf, text=label).grid(row=row, column=0, sticky="w", padx=(0, 14), pady=3)
            ttk.Entry(keys_lf, textvariable=var, show="*").grid(row=row, column=1, sticky="we", pady=3)
            link = tk.Label(keys_lf, text=link_text, fg=PAL["accent_hi"], bg=PAL["bg"],
                            cursor="hand2", font=("", 9, "underline"))
            link.grid(row=row, column=2, sticky="w", padx=(10, 0))
            link.bind("<Button-1>", lambda _e, u=url: self._open_url(u))
            self._themed_tk.append(link)
            var.trace_add("write", lambda *_: self._save_settings())

        _key_row(0, "GitHub token (optional)", self.token_var, GH_TOKEN_URL, "Get a token →")
        _key_row(1, "CurseForge API key", self.cf_var, CF_API_KEY_URL, "Get a free key →")
        _key_row(2, "Modrinth token (optional)", self.mr_token_var, MR_TOKEN_URL, "Account settings →")
        ttk.Label(keys_lf,
                  text="CurseForge requires a free key from console.curseforge.com for search and downloads. "
                       "GitHub / Modrinth tokens are optional (higher limits / private repos).",
                  style="Muted.TLabel", wraplength=720).grid(row=3, column=0, columnspan=3, sticky="w", pady=(6, 0))

        # Appearance — background presets + system color picker
        app_lf = ttk.LabelFrame(sform, text=" Appearance ", padding=(12, 8))
        app_lf.pack(fill="x", pady=(0, 12))
        ttk.Label(app_lf, text="Background").grid(row=0, column=0, sticky="w", padx=(0, 14), pady=3)
        pret = ttk.Frame(app_lf)
        pret.grid(row=0, column=1, sticky="w", pady=3)
        for pname, phex in BG_PRESETS:
            ttk.Button(pret, text=pname, command=lambda h=phex: self.set_theme_bg(h)).pack(side="left", padx=(0, 4))
        ttk.Button(pret, text="Custom…", command=self._pick_bg_color).pack(side="left", padx=(8, 0))
        self.theme_swatch = tk.Label(pret, text="  ", width=4, relief="solid", borderwidth=1,
                                     bg=PAL["bg"], fg=PAL["fg"])
        self.theme_swatch.pack(side="left", padx=(10, 0))
        self._themed_tk.append(self.theme_swatch)
        pil_note = ("Glass look ON." if HAS_PIL
                    else "Glass look offline (auto-install did not run or failed).")
        ttk.Label(app_lf, text="Presets: Snow · Mist · Slate · Charcoal · Midnight. "
                               f"Custom opens the system color wheel.  {pil_note}",
                  style="Muted.TLabel").grid(row=1, column=1, sticky="w", pady=(0, 2))

        ttk.Label(sform, text="Command placeholders: {game_dir} {mods_dir} {mc_version} {loader} {name}",
                  style="Muted.TLabel").pack(anchor="w")

        # ---- activity log
        lgh = ttk.Frame(log_fr)
        lgh.pack(fill="x", pady=(8, 4))
        ttk.Label(lgh, text="ACTIVITY LOG", style="BgSection.TLabel").pack(side="left")
        ttk.Button(lgh, text="Clear", command=self._clear_log).pack(side="right")
        lbf = ttk.Frame(log_fr)
        lbf.pack(fill="both", expand=True)
        self.log_box = tk.Text(lbf, height=7, state="disabled", bg=P["surface"], fg=P["fg"],
                               insertbackground=P["fg"], relief="flat", borderwidth=0, padx=10, pady=8,
                               wrap="word", font="TkFixedFont", selectbackground=P["accent"])
        self._themed_tk.append(self.log_box)
        lsb = ttk.Scrollbar(lbf, orient="vertical", command=self.log_box.yview)
        self.log_box.configure(yscrollcommand=lsb.set)
        lsb.pack(side="right", fill="y")
        self.log_box.pack(side="left", fill="both", expand=True)
        self.log_box.tag_configure("err", foreground="#ff7b72")
        self.log_box.tag_configure("ok", foreground="#56d364")
        self.log_box.tag_configure("warn", foreground=P["amber"])
        self.log_box.tag_configure("mc", foreground=P["muted"])

    @property
    def busy(self):
        return self._busy

    @busy.setter
    def busy(self, value):
        self._busy = value
        try:
            self.after(0, self._show_busy)  # may be set from worker threads
        except Exception:
            pass

    def _show_busy(self):
        pb = getattr(self, "progress", None)
        if pb is None:
            return
        if self._busy:
            pb.start(12)
            self.status_var.set("Working...")
        else:
            pb.stop()
            self.status_var.set("Ready")

    def _clear_log(self):
        self.log_box.configure(state="normal")
        self.log_box.delete("1.0", "end")
        self.log_box.configure(state="disabled")

    def log(self, msg):
        low = msg.lower()
        if msg.startswith("[mc]"):
            tag = "mc"
        elif any(w in low for w in ("error", "failed", "rejected", "mismatch", "missing")):
            tag = "err"
        elif "warning" in low:
            tag = "warn"
        elif any(w in low for w in ("installed", "verified", "up to date", "is ready", "complete")):
            tag = "ok"
        else:
            tag = ""

        def _do():
            self.log_box.configure(state="normal")
            self.log_box.insert("end", msg + "\n", (tag,) if tag else ())
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
        else:
            self.auth_status.set("Not logged in")

    def _show_device_code_dialog(self, uri, code, expires_min):
        """Modal with code, copy button, and clickable link."""
        win = tk.Toplevel(self)
        win.title("Microsoft login")
        win.configure(bg=PAL["bg"])
        win.transient(self)
        win.grab_set()
        win.resizable(False, False)

        ttk.Label(win, text="Sign in with Microsoft", font=("", 12, "bold")).pack(
            padx=16, pady=(14, 6))
        ttk.Label(win, text="1. Open this page in your browser:").pack(anchor="w", padx=16)

        link_fr = ttk.Frame(win)
        link_fr.pack(fill="x", padx=16, pady=4)
        link = tk.Label(link_fr, text=uri, fg=PAL["accent_hi"], bg=PAL["bg"], cursor="hand2",
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
        tk.Label(code_fr, text=code, font=("", 22, "bold"), fg=PAL["fg"], bg=PAL["bg"]).pack(side="left")

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
        self.log("Logged out — log in again to play.")

    def set_theme_bg(self, bg_hex):
        """Apply a new background color, rebuild the palette, and refresh ttk + tracked widgets."""
        bg = (bg_hex or DEFAULT_BG).strip()
        if not re.fullmatch(r"#[0-9A-Fa-f]{6}", bg, re.I):
            bg = DEFAULT_BG
        self.cfg["theme_bg"] = bg
        save_config(self.cfg)
        apply_theme(self, bg)
        P = PAL
        for w in list(self._themed_tk):
            try:
                if not w.winfo_exists():
                    continue
                if isinstance(w, tk.Text):
                    w.configure(bg=P["surface"], fg=P["fg"], insertbackground=P["fg"],
                                selectbackground=P["accent"])
                    w.tag_configure("warn", foreground=P["amber"])
                    w.tag_configure("mc", foreground=P["muted"])
                elif isinstance(w, tk.Canvas):
                    # header uses glass smoke; settings canvas uses bg
                    if w is getattr(self, "_header_canvas", None):
                        w.configure(bg=P["glass"])
                    else:
                        w.configure(bg=P["bg"])
                else:
                    w.configure(bg=P["bg"], fg=P["fg"])
            except tk.TclError:
                pass
        try:
            if hasattr(self, "theme_swatch") and self.theme_swatch.winfo_exists():
                self.theme_swatch.configure(bg=P["bg"], fg=P["fg"])
        except tk.TclError:
            pass
        try:
            if hasattr(self, "_paint_header"):
                self._paint_header()
        except Exception:
            pass
        for gb in getattr(self, "_glow_buttons", []):
            try:
                gb.refresh_art()
            except Exception:
                pass
        for gp in getattr(self, "_glass_panels", []):
            try:
                gp._redraw()
            except Exception:
                pass

    def _pick_bg_color(self):
        """Open the system color wheel / picker for a custom background."""
        try:
            from tkinter import colorchooser
        except ImportError:
            messagebox.showerror("Color picker", "tkinter.colorchooser is not available on this system.")
            return
        initial = self.cfg.get("theme_bg") or DEFAULT_BG
        result = colorchooser.askcolor(color=initial, title="Background color", parent=self)
        if result and result[1]:
            self.set_theme_bg(result[1])

    def _save_settings(self):
        if self._loading:
            return
        i = self.inst
        for k, v in self.vars.items():
            i[k] = v.get().strip() if k not in RAW_KEYS else v.get()
        i["fullscreen"] = bool(self.fullscreen_var.get())
        self.cfg["runtime_dir"] = self.runtime_var.get().strip()
        self.cfg["official_launcher_path"] = self.official_var.get().strip()
        self.cfg["token"] = self.token_var.get().strip()
        self.cfg["curseforge_api_key"] = self.cf_var.get().strip()
        self.cfg["modrinth_token"] = self.mr_token_var.get().strip()
        save_config(self.cfg)

    def _select_instance(self, name):
        self.cfg["active"] = name
        self._loading = True
        self.inst_box["values"] = list(self.cfg["instances"])
        self.inst_var.set(name)
        for k, v in self.vars.items():
            v.set(self.inst.get(k, "") or ("builtin" if k == "launcher" else ""))
        self.fullscreen_var.set(bool(self.inst.get("fullscreen", False)))
        self.version_var.set(self.inst.get("version_id", ""))
        self._loading = False
        save_config(self.cfg)
        self._refresh_list()
        self._update_auth_status()
        self._schedule_pack_check()

    def _refresh_list(self):
        keep = set(self.tree.selection())
        self.tree.delete(*self.tree.get_children())
        latest = self.latest.get(self.cfg["active"], {})
        rows = list_mod_rows(self.inst)
        q = self.filter_var.get().strip().lower()
        shown = 0
        for r in rows:
            if q and q not in " ".join((r["name"], r["file"], r["source"], r["role"])).lower():
                continue
            lt = latest.get(r["key"], "") if r["key"] else ""
            tag = ("update" if lt.startswith("update") else "dep" if r["role"].startswith("dependency")
                   else "pack" if r["key"] is None else "")
            self.tree.insert("", "end", iid=r["iid"], values=(
                r["name"], r["version"], r["file"], lt, r["source"], r["role"]), tags=(tag,) if tag else ())
            shown += 1
        keep = [i for i in keep if self.tree.exists(i)]
        if keep:
            self.tree.selection_set(keep)
        inst = self.inst
        self.count_lbl.configure(text=f"{shown} of {len(rows)} shown" if q else f"{len(rows)} jar(s)")
        if inst.get("pack_source") or inst.get("pack_repo"):
            src = inst.get("pack_source") or "github"
            nm = inst.get("pack_name") or inst.get("pack_repo") or "?"
            ver = inst.get("pack_version") or inst.get("pack_ref") or ""
            self.pack_lbl.configure(text=f"{nm}   {ver}   -   {src}")
        else:
            self.pack_lbl.configure(text="No modpack installed in this profile")

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
        """Install a pack from a GitHub repo, a Modrinth modpack link or a CurseForge modpack link."""
        if self.busy or self.game_proc:
            self.log("Busy or game running — wait.")
            return
        text = simpledialog.askstring(
            "Install pack",
            "Pack link or GitHub repo:\n"
            "  GitHub:      owner/name  or  https://github.com/owner/name\n"
            "  Modrinth:    https://modrinth.com/modpack/<slug>[/version/<id>]\n"
            "  CurseForge:  https://www.curseforge.com/minecraft/modpacks/<slug>[/files/<id>]",
            parent=self)
        if not text:
            return
        kind, ref, extra = parse_pack_link(text)
        if not kind:
            messagebox.showerror("Bad link", "Couldn't recognise that as a GitHub repo, Modrinth modpack "
                                             "or CurseForge modpack link.")
            return
        if kind == "curseforge" and not cf_key(self.cfg):
            messagebox.showerror("CurseForge API key needed",
                                 "CurseForge requires a free API key (console.curseforge.com).\n"
                                 "Paste it into Settings -> CurseForge API key, then try again.")
            return
        if kind == "github":
            self._install_github_pack_flow(ref)
            return
        # Modrinth / CurseForge: the pack itself defines MC version + loader
        use_new = messagebox.askyesno(
            "New profile?",
            f"Install '{ref}' into a new profile?\n"
            f"(No = replace the mods folder of the current profile '{self.cfg['active']}')")
        if use_new:
            pname = simpledialog.askstring("Profile name", "Name for the new profile:",
                                           initialvalue=ref, parent=self)
            if not pname or re.search(r'[\\/:*?"<>|]', pname):
                return
            pname = pname.strip()
            base, n = pname, 2
            while pname in self.cfg["instances"]:
                pname, n = f"{base}-{n}", n + 1
            self.cfg["instances"][pname] = default_instance(pname, "", "fabric")
            os.makedirs(self.cfg["instances"][pname]["mods_dir"], exist_ok=True)
            self._select_instance(pname)
        self._start_link_pack_install(kind, ref, extra, clear_mods=True)

    def _install_github_pack_flow(self, repo):
        ref = simpledialog.askstring("Branch / tag", "Branch or tag (default: main):",
                                     initialvalue="main", parent=self) or "main"
        clear = messagebox.askyesno(
            "Clear mods?",
            "Replace the current mods folder with the pack's mods?\n"
            "(Recommended for a clean pack install.)")
        use_new = messagebox.askyesno(
            "New profile?",
            f"Install into a new profile named after the pack?\n"
            f"(No = install into current profile '{self.cfg['active']}')")
        if use_new:
            pname = repo.split("/")[-1]
            if pname in self.cfg["instances"]:
                pname = f"{pname}-{ref}"
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
        """Force a re-download of the current pack (any source)."""
        if self.busy or self.game_proc:
            self.log("Busy or game running — wait.")
            return
        inst = self.inst
        if inst.get("pack_source") in ("modrinth", "curseforge") and inst.get("pack_url"):
            kind, ref, _ = parse_pack_link(inst["pack_url"])
            if kind == "curseforge" and not cf_key(self.cfg):
                messagebox.showerror("CurseForge API key needed", "Set your CurseForge API key in Settings first.")
                return
            if kind and messagebox.askyesno(
                    "Reinstall pack",
                    f"Re-download the latest version of {inst.get('pack_name') or ref}?\n"
                    "Existing mods folder will be replaced; mods you added yourself are re-installed."):
                self._start_link_pack_install(kind, ref, "", clear_mods=True, restore=True)
            return
        repo = inst.get("pack_repo") or ""
        ref = inst.get("pack_ref") or "main"
        if not repo:
            if self.cfg["active"] == FATHOM_PACK["name"]:
                repo, ref = FATHOM_PACK["repo"], FATHOM_PACK["ref"]
            else:
                messagebox.showinfo(
                    "No pack",
                    "This profile has no pack recorded.\n"
                    "Use 'Install Fathom Studios pack' or 'Install pack from link...'.")
                return
        if not messagebox.askyesno("Reinstall pack",
                                   f"Re-download and reinstall {repo}@{ref}?\n"
                                   "Existing mods folder will be replaced; mods you added yourself are re-installed."):
            return
        self._start_pack_install(repo, ref, clear_mods=True, restore=True)

    def _run_pack_job(self, installer, restore=False):
        """Run installer(inst) in a thread on the active instance, then refresh the UI."""
        self.busy = True
        self._save_settings()
        inst = self.inst
        name = self.cfg["active"]

        def worker():
            try:
                installer(inst)
                if restore:
                    self._restore_tracked(inst)
                save_config(self.cfg)
                self.log(f"Profile '{name}' is ready. Press 'Install / prepare' then PLAY.")
            except urllib.error.HTTPError as e:
                hint = " (private repo? set a GitHub token)" if e.code in (401, 403, 404) else ""
                self.log(f"Pack install HTTP {e.code}{hint}")
            except Exception as e:
                self.log(f"Pack install ERROR: {e}")
            finally:
                self.busy = False
                self.after(0, lambda: self._after_pack_job(name))

        threading.Thread(target=worker, daemon=True).start()

    def _after_pack_job(self, name):
        if name in self.cfg["instances"] and self.cfg["active"] == name:
            self._select_instance(name)  # pack may have changed MC version / loader

    def _start_pack_install(self, repo, ref, clear_mods=True, restore=False):
        self._run_pack_job(lambda inst: install_github_pack(
            repo, inst, self.cfg.get("token", ""), self.log, ref=ref, clear_mods=clear_mods), restore)

    def _start_link_pack_install(self, kind, ref, extra, clear_mods=True, restore=False):
        if kind == "modrinth":
            fn = lambda inst: install_modrinth_pack(ref, extra, inst, self.log, clear_mods)
        else:
            fn = lambda inst: install_curseforge_pack(self.cfg, ref, extra, inst, self.log, clear_mods)
        self._run_pack_job(fn, restore)

    def _restore_tracked(self, inst):
        """After a pack (re)install wiped mods/, put back the mods the user added themselves."""
        mods = inst["mods"]
        if not mods:
            return
        self.log("Restoring mods you added...")
        have = installed_ids(inst)
        seen = set(mods)
        for key, info in list(mods.items()):
            ids = set(info.get("ids") or [])
            if ids and ids <= have:
                self.log(f"[{info.get('name') or key}] the pack already includes this mod; dropping your copy")
                mods.pop(key, None)
                continue
            try:
                self.install_mod(key, inst, bool(info.get("auto")), None, seen, vid=installed_vid(key, info))
            except Exception as e:
                self.log(f"[{info.get('name') or key}] restore failed: {e}")

    # ---- pack update check (GitHub packs only)
    def _schedule_pack_check(self):
        name = self.cfg["active"]
        if not pack_is_github(self.inst) or name in self._pack_checked:
            return
        self._pack_checked.add(name)
        threading.Thread(target=self._pack_check_worker, args=(name, False), daemon=True).start()

    def check_pack_update(self):
        inst = self.inst
        if not inst.get("pack_repo") and not inst.get("pack_source"):
            messagebox.showinfo("No pack", "This profile wasn't installed from a pack.")
            return
        if not pack_is_github(inst):
            messagebox.showinfo("GitHub packs only",
                                "Automatic update checks only work for packs installed from GitHub.\n"
                                "For Modrinth / CurseForge packs use 'Reinstall pack' to fetch the latest version.")
            return
        self.log("Checking the pack for updates...")
        threading.Thread(target=self._pack_check_worker, args=(self.cfg["active"], True), daemon=True).start()

    def _pack_check_worker(self, name, manual):
        inst = self.cfg["instances"].get(name)
        if not inst:
            return
        try:
            r = check_github_pack_update(inst, self.cfg.get("token", ""))
        except urllib.error.HTTPError as e:
            hint = " (private repo? set a GitHub token)" if e.code in (401, 403, 404) else ""
            self.after(0, lambda: self.log(f"Pack update check failed: HTTP {e.code}{hint}"))
            return
        except Exception as e:
            self.after(0, lambda err=str(e): self.log(f"Pack update check failed: {err}"))
            return
        if r:
            self.after(0, lambda: self._pack_check_result(name, r, manual))

    def _pack_check_result(self, name, r, manual):
        inst = self.cfg["instances"].get(name)
        if inst is None:
            return
        if not r["installed"]:
            note = ("The installed commit of this pack isn't recorded (it was installed before update tracking "
                    "existed). Use 'Reinstall pack' once and future updates will be detected.")
            if manual:
                messagebox.showinfo("Can't compare", note, parent=self)
            else:
                self.log("Pack update check: " + note)
            return
        if not r["newer"]:
            if manual:
                messagebox.showinfo("Pack up to date", f"{r['repo']} is up to date ({r['installed'][:7]}).", parent=self)
            else:
                self.log(f"Pack {r['repo']} is up to date ({r['installed'][:7]}).")
            return
        if self.cfg["active"] != name:  # user switched profile while we were checking
            self._pack_checked.discard(name)
            self.log(f"Pack update available for '{name}' - will ask when you switch back.")
            return
        if not manual and inst.get("pack_skip_sha") == r["latest"]:
            self.log(f"Pack update available ({r['latest'][:7]}) - you declined it earlier; use 'Check pack update'.")
            return
        if self.busy or self.game_proc:
            self.log("Pack update available, but the launcher is busy / the game is running. "
                     "Use 'Check pack update' later.")
            return
        msg = (f"A newer version of the modpack {r['repo']} ({r['ref']}) is available.\n\n"
               f"Installed:  {r['installed'][:7]}\nLatest:     {r['latest'][:7]}\n")
        if r["message"]:
            msg += f"Latest change: {r['message'][:200]}\n"
        msg += ("\nUpdate now?\nThe mods folder is replaced and the pack's config files are overwritten. "
                "Mods you added yourself are re-installed afterwards.")
        if messagebox.askyesno("Modpack update", msg, parent=self):
            self._start_pack_install(r["repo"], r["ref"], clear_mods=True, restore=True)
        else:
            inst["pack_skip_sha"] = r["latest"]
            save_config(self.cfg)
            self.log("Pack update skipped. It won't be offered again until there is a newer commit.")

    # ---- mod actions
    def _open_url(self, url):
        try:
            import webbrowser
            webbrowser.open(url)
        except Exception as e:
            self.log(f"Could not open browser: {e}")

    def _search_loader_mc(self):
        """Loader + MC version for search filters from the UI combobox."""
        choice = (self.search_loader.get() or "profile").strip().lower()
        if choice == "any":
            return "", ""
        if choice == "profile":
            return self.inst.get("loader") or "", (self.inst.get("mc_version") or "").strip()
        return choice, (self.inst.get("mc_version") or "").strip()

    def search_mods(self):
        q = self.search_var.get().strip()
        if not q:
            messagebox.showinfo("Search", "Type a mod name to search for.", parent=self)
            return
        if self.busy:
            self.log("Busy — wait for the current job.")
            return
        src = (self.search_source.get() or "modrinth").strip().lower()
        if src in ("curseforge", "both") and not cf_key(self.cfg):
            messagebox.showerror(
                "CurseForge API key needed",
                "CurseForge search needs a free API key.\n"
                "Open Settings → API keys and use “Get a free key →”, then paste it.",
                parent=self)
            if src == "curseforge":
                return
            src = "modrinth"
        loader, mc = self._search_loader_mc()
        self.busy = True
        self.log(f"Searching '{q}' ({src}, loader={loader or 'any'}, mc={mc or 'any'})...")

        def worker():
            hits, errors = [], []
            try:
                if src in ("modrinth", "both"):
                    try:
                        hits.extend(search_modrinth(
                            q, loader=loader, mc=mc, limit=25,
                            token=self.cfg.get("modrinth_token", "")))
                    except Exception as e:
                        errors.append(f"Modrinth: {e}")
                if src in ("curseforge", "both"):
                    try:
                        hits.extend(search_curseforge(self.cfg, q, loader=loader, mc=mc, limit=25))
                    except Exception as e:
                        errors.append(f"CurseForge: {e}")
            finally:
                self.busy = False
                self.after(0, lambda: self._search_done(hits, errors))

        threading.Thread(target=worker, daemon=True).start()

    def _search_done(self, hits, errors):
        self.search_tree.delete(*self.search_tree.get_children())
        self._search_hits = {}
        for i, h in enumerate(hits):
            iid = f"hit-{i}"
            self._search_hits[iid] = h
            dl = h.get("downloads") or 0
            dl_s = f"{dl/1e6:.1f}M" if dl >= 1e6 else (f"{dl/1e3:.0f}K" if dl >= 1000 else str(dl))
            self.search_tree.insert("", "end", iid=iid, values=(
                h.get("name") or "?", h.get("source") or "?", dl_s, h.get("description") or ""))
        for e in errors:
            self.log(f"Search error: {e}")
        self.log(f"Search: {len(hits)} result(s)" + (f" ({len(errors)} source error(s))" if errors else ""))
        if not hits and not errors:
            self.log("No mods matched. Try another query or loader filter.")

    def install_search_selected(self):
        sel = self.search_tree.selection()
        if not sel:
            messagebox.showinfo("Install", "Select a search result first.", parent=self)
            return
        hit = self._search_hits.get(sel[0])
        if not hit:
            return
        src, ref = hit["source"], hit.get("slug") or hit.get("id")
        if src == "curseforge" and not cf_key(self.cfg):
            messagebox.showerror("CurseForge API key needed",
                                 "Set your CurseForge API key in Settings first.", parent=self)
            return
        if self.busy or self.game_proc:
            self.log("Busy or game running — wait.")
            return
        self.busy = True
        threading.Thread(target=self._add_worker, args=(self.inst, src, ref), daemon=True).start()

    def open_search_selected(self):
        sel = self.search_tree.selection()
        if not sel:
            return
        hit = self._search_hits.get(sel[0])
        if hit and hit.get("url"):
            self._open_url(hit["url"])

    def add_repo(self):
        text = self.repo_var.get().strip()
        src, ref = parse_mod_link(text)
        if not src:
            messagebox.showerror("Bad link", "Use a GitHub owner/name or URL, a Modrinth / CurseForge mod link, "
                                             "a direct https://…jar link, or modrinth:<slug> / curseforge:<slug>.")
            return
        if src == "curseforge" and not cf_key(self.cfg):
            messagebox.showerror("CurseForge API key needed",
                                 "CurseForge requires a free API key (console.curseforge.com).\n"
                                 "Paste it into Settings → API keys (click “Get a free key →”).")
            return
        self.repo_var.set("")
        if src == "github":
            self.update_mods(only=[ref], explicit=True)
            return
        if self.busy:
            self.log("Busy, wait for the current job.")
            return
        if self.game_proc:
            self.log("Close the game before changing mods.")
            return
        self.busy = True
        threading.Thread(target=self._add_worker, args=(self.inst, src, ref), daemon=True).start()

    def _add_worker(self, inst, src, ref):
        try:
            key, name = resolve_mod(src, ref, self.cfg)
            self._names[key] = name
            if src == "url":
                inst["mods"].setdefault(key, {})["url"] = ref
                inst["mods"][key]["name"] = name
                save_config(self.cfg)
            self.log(f"Resolved {src} '{ref}' -> {name}")
        except urllib.error.HTTPError as e:
            self.log(f"[{ref}] {src} lookup failed: HTTP {e.code}")
            self.busy = False
            return
        except Exception as e:
            self.log(f"[{ref}] {src} lookup failed: {e}")
            self.busy = False
            return
        self._mod_worker(inst, [key], explicit=True)  # releases busy

    def update_mods(self, selected_only=False, only=None, explicit=False, pins=None):
        if self.busy:
            self.log("Busy, wait for the current job.")
            return
        if self.game_proc:
            self.log("Close the game before changing mods.")
            return
        if only is None:
            sel = [k for k in self.tree.selection() if k in self.inst["mods"]]
            only = sel if selected_only else list(self.inst["mods"])
        if not only:
            self.log("Nothing to update.")
            return
        self.busy = True
        threading.Thread(target=self._mod_worker, args=(self.inst, only, explicit, pins), daemon=True).start()

    def _mod_worker(self, inst, keys, explicit=False, pins=None):
        try:
            os.makedirs(inst["mods_dir"], exist_ok=True)
            seen = set(keys)
            for k in keys:
                try:
                    # updating keeps a dependency a dependency; only an explicit add makes it "yours"
                    auto = False if explicit else bool(inst["mods"].get(k, {}).get("auto"))
                    self.install_mod(k, inst, auto=auto, parent=None, seen=seen, vid=(pins or {}).get(k))
                except urllib.error.HTTPError as e:
                    hint = " (private repo? set a token)" if e.code in (401, 403, 404) else ""
                    self.log(f"[{k}] HTTP {e.code}{hint}")
                except Exception as e:
                    self.log(f"[{k}] ERROR: {e}")
            self.log("Done.")
        finally:
            self.busy = False
            self.after(0, self._refresh_list)

    def _mod_label(self, key):
        """Human name for a mod key (repo for GitHub, project title for Modrinth/CurseForge)."""
        if mod_source(key) == "github":
            return key
        n = self._names.get(key)
        if not n:
            for i in self.cfg["instances"].values():
                n = (i["mods"].get(key) or {}).get("name")
                if n:
                    break
        if not n:
            src, pid = key.split(":", 1)
            try:
                n = (mr_json(f"/project/{urllib.parse.quote(pid)}")["title"] if src == "modrinth"
                     else cf_json(self.cfg, f"/mods/{pid}")["data"]["name"])
            except Exception:
                n = key
        self._names[key] = n
        return n

    def install_mod(self, key, inst, auto, parent, seen, vid=None, expect_id=None):
        """Install/update one mod from its own source (GitHub, Modrinth, CurseForge or direct URL).
        vid pins a specific version (GitHub tag / Modrinth version id / CurseForge file id)."""
        src = mod_source(key)
        if src == "url":
            return self._install_url(key, inst, auto, parent)
        if src != "github":
            return self._install_remote(key, inst, auto, parent, seen, vid, expect_id)
        repo = key
        token = self.cfg["token"]
        loader, mods_dir = inst["loader"], inst["mods_dir"]
        self.log(f"[{repo}] checking ({loader} {inst['mc_version'] or 'any MC'})...")
        rel, cands = find_release(repo, inst, token, tag=vid)
        if not rel:
            self.log(f"[{repo}] no release with a matching jar for {loader} {inst['mc_version']}"
                     + (f" (version {vid})" if vid else ""))
            return
        os.makedirs(mods_dir, exist_ok=True)
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
        info.update(name=repo, tag=tag, file=fname, sha256=sha, ids=sorted(mf.get("ids", [])), loaders=sorted(meta))
        self._record_role(info, auto, parent)
        save_config(self.cfg)
        self._install_declared_deps(repo, mf, inst, seen)

    @staticmethod
    def _record_role(info, auto, parent):
        if not auto:
            info["auto"] = False
        else:
            info.setdefault("auto", True)
        if parent:
            rb = info.setdefault("required_by", [])
            if parent not in rb:
                rb.append(parent)

    def _install_url(self, key, inst, auto, parent):
        """Download a direct .jar URL into the mods folder (no version list; re-add to refresh)."""
        info0 = inst["mods"].get(key) or {}
        url = info0.get("url") or ""
        if not url:
            # first install: key was just resolved; url lives in worker via _names only — store on info
            raise RuntimeError("Direct URL mod is missing its download link; remove and add the URL again.")
        label = info0.get("name") or self._mod_label(key)
        loader, mods_dir = inst["loader"], inst["mods_dir"]
        os.makedirs(mods_dir, exist_ok=True)
        fname = os.path.basename(urllib.parse.urlparse(url).path) or "mod.jar"
        if not fname.lower().endswith(".jar"):
            fname += ".jar"
        dest = os.path.join(mods_dir, fname)
        self.log(f"[{label}] downloading {fname} from direct URL...")
        data = fetch_bytes(url, timeout=120)
        sha = hashlib.sha256(data).hexdigest()
        tmp = dest + ".part"
        with open(tmp, "wb") as fh:
            fh.write(data)
        meta = read_meta(tmp)
        if meta and not meta_for(meta, loader):
            self.log(f"[{label}] {fname} is a {'/'.join(meta)} jar, instance is {loader} - rejected")
            os.remove(tmp)
            return
        os.replace(tmp, dest)
        old = info0.get("file")
        if old and old != fname:
            try:
                os.remove(os.path.join(mods_dir, old))
            except FileNotFoundError:
                pass
        mf = meta_for(meta, loader)
        info = inst["mods"].setdefault(key, {})
        info.update(name=label, tag="direct", version_id="", file=fname, sha256=sha, url=url,
                    ids=sorted(mf.get("ids", [])), loaders=sorted(meta))
        self._record_role(info, auto, parent)
        save_config(self.cfg)
        self.log(f"[{label}] installed {fname}")

    def _install_remote(self, key, inst, auto, parent, seen, vid=None, expect_id=None):
        """Modrinth / CurseForge install of one mod + its required dependencies (from the source's own metadata)."""
        src = mod_source(key)
        loader, mods_dir = inst["loader"], inst["mods_dir"]
        label = self._mod_label(key)
        self.log(f"[{label}] checking {src} ({loader} {inst['mc_version'] or 'any MC'})...")
        versions = list_versions(key, inst, self.cfg)
        if vid:
            v = next((x for x in versions if x["id"] == vid), None)
            if not v:
                self.log(f"[{label}] version {vid} isn't available for {loader} {inst['mc_version']}")
                return
        else:
            v = pick_latest(versions)
        if not v:
            self.log(f"[{label}] no version with a jar for {loader} {inst['mc_version'] or 'any MC'} on {src}")
            return
        os.makedirs(mods_dir, exist_ok=True)
        old = inst["mods"].get(key, {})
        f = v["files"][0]
        fname = os.path.basename(f["name"])
        dest = os.path.join(mods_dir, fname)

        if old.get("version_id") == v["id"] and old.get("file") == fname and os.path.exists(dest) \
                and sha256_file(dest) == old.get("sha256"):
            self.log(f"[{label}] up to date ({v['label']})")
            sha, meta = old["sha256"], read_meta(dest)
        else:
            self.log(f"[{label}] downloading {fname} ({v['label']})")
            data = fetch_bytes(f["url"], timeout=120)
            if f.get("sha1") and hashlib.sha1(data).hexdigest() != f["sha1"].lower():
                self.log(f"[{label}] HASH MISMATCH for {fname} (sha1) - rejected")
                return
            if not f.get("sha1") and f.get("sha512") and hashlib.sha512(data).hexdigest() != f["sha512"].lower():
                self.log(f"[{label}] HASH MISMATCH for {fname} (sha512) - rejected")
                return
            verified = "sha1" if f.get("sha1") else ("sha512" if f.get("sha512") else None)
            sha = hashlib.sha256(data).hexdigest()
            self.log(f"[{label}] {verified} verified against {src}" if verified else
                     f"[{label}] no published hash; recorded sha256 {sha[:12]}.. (trust on first use)")
            tmp = os.path.join(mods_dir, fname + ".part")
            with open(tmp, "wb") as fh:
                fh.write(data)
            meta = read_meta(tmp)
            if meta and not meta_for(meta, loader):
                self.log(f"[{label}] {fname} is a {'/'.join(meta)} jar, instance is {loader} - rejected")
                os.remove(tmp)
                return
            ids = set(meta_for(meta, loader).get("ids", []))
            if expect_id and ids and expect_id not in ids:
                self.log(f"[{label}] doesn't provide mod id '{expect_id}' (wrong project) - rejected")
                os.remove(tmp)
                return
            if auto and key not in inst["mods"] and ids and ids <= installed_ids(inst):
                self.log(f"[{label}] already installed (provided by another jar) - skipped")
                os.remove(tmp)
                return
            if not meta:
                self.log(f"[{label}] warning: couldn't read mod metadata from {fname}")
            os.replace(tmp, dest)
            if old.get("file") and old["file"] != fname:
                try:
                    os.remove(os.path.join(mods_dir, old["file"]))
                except FileNotFoundError:
                    pass
            self.log(f"[{label}] installed {fname}")

        mf = meta_for(meta, loader)
        info = inst["mods"].setdefault(key, {})
        info.update(name=label, tag=v["label"], version_id=v["id"], file=fname, sha256=sha,
                    ids=sorted(mf.get("ids", [])), loaders=sorted(meta))
        self._record_role(info, auto, parent)
        save_config(self.cfg)

        # required dependencies as declared by the source itself
        for d in v.get("deps", []):
            pid2 = d.get("project")
            if not pid2:
                continue
            k2 = f"{src}:{pid2}"
            if k2 == key:
                continue
            have = inst["mods"].get(k2)
            if have and os.path.exists(os.path.join(mods_dir, have.get("file") or "\0")):
                rb = have.setdefault("required_by", [])
                if key not in rb:
                    rb.append(key)
                    save_config(self.cfg)
                continue
            if k2 in seen:
                continue
            seen.add(k2)
            self.log(f"[{label}] requires {self._mod_label(k2)}")
            try:
                self.install_mod(k2, inst, True, key, seen)
            except Exception as e:
                self.log(f"[{self._mod_label(k2)}] dependency install failed: {e}")
        # deps only visible inside the jar (not listed on the project page)
        self._install_declared_deps(key, mf, inst, seen)

    def _install_declared_deps(self, key, mf, inst, seen):
        """Resolve dependencies declared inside the jar: dep_map -> GitHub, otherwise Modrinth by mod id."""
        label = self._mod_label(key)
        dep_map = {**BUILTIN_DEP_MAP, **self.cfg.get("dep_map", {})}
        have = installed_ids(inst)
        for d in mf.get("deps", []):
            if not d or d in SKIP_DEPS or d in have:
                continue
            r2 = dep_map.get(d)
            try:
                if r2:
                    if r2 in seen:
                        continue
                    seen.add(r2)
                    self.log(f"[{label}] requires '{d}' -> {r2}")
                    self.install_mod(r2, inst, True, key, seen)
                else:
                    try:
                        p = mr_json(f"/project/{urllib.parse.quote(d)}")
                    except urllib.error.HTTPError:
                        p = None
                    if not p or p.get("project_type") != "mod":
                        self.log(f"[{label}] requires '{d}' - couldn't find it on Modrinth; "
                                 "add it yourself or map it in dep_map")
                        continue
                    k2 = f"modrinth:{p['id']}"
                    if k2 in seen:
                        continue
                    seen.add(k2)
                    self._names[k2] = p.get("title") or d
                    self.log(f"[{label}] requires '{d}' -> Modrinth {self._names[k2]}")
                    self.install_mod(k2, inst, True, key, seen, expect_id=d)
            except Exception as e:
                self.log(f"[{label}] dependency '{d}' failed: {e}")
            have = installed_ids(inst)

    def remove_selected(self):
        sel = list(self.tree.selection())
        if not sel or self.game_proc:
            return
        mods = self.inst["mods"]
        mods_dir = self.inst["mods_dir"]

        def nice(key):
            return mods.get(key, {}).get("name") or key

        def delete(key):
            info = mods.pop(key, {})
            p = os.path.join(mods_dir, info.get("file") or "\0")
            if info.get("file") and os.path.exists(p):
                os.remove(p)
            self.log(f"Removed {info.get('name') or key}")

        jars = [i[4:] for i in sel if i.startswith("jar:")]
        if jars and messagebox.askyesno(
                "Remove jars", f"Delete {len(jars)} jar(s) that the launcher doesn't track "
                               f"(from the pack or added by hand)?\n\n" + "\n".join(jars[:10])
                               + ("\n..." if len(jars) > 10 else "")):
            for fn in jars:
                try:
                    os.remove(os.path.join(mods_dir, fn))
                    self.log(f"Removed {fn}")
                except OSError as e:
                    self.log(f"Could not remove {fn}: {e}")
        for k in [i for i in sel if i in mods]:
            delete(k)
        changed = True
        while changed:  # clean up auto-installed deps nobody needs anymore
            changed = False
            for r, i in list(mods.items()):
                if i.get("auto"):
                    i["required_by"] = [x for x in i.get("required_by", []) if x in mods]
                    if not i["required_by"] and messagebox.askyesno(
                            "Orphaned dependency", f"{nice(r)} was only needed by removed mods. Remove it too?"):
                        delete(r)
                        changed = True
        save_config(self.cfg)
        self._refresh_list()

    # ---- update check + version picker (uses each mod's own source)
    def check_updates(self):
        if self.busy:
            self.log("Busy, wait for the current job.")
            return
        inst = self.inst
        keys = [k for k in self.tree.selection() if k in inst["mods"]] or list(inst["mods"])
        if not keys:
            self.log("No tracked mods to check.")
            return
        self.busy = True
        threading.Thread(target=self._check_updates_worker, args=(self.cfg["active"], inst, keys),
                         daemon=True).start()

    def _check_updates_worker(self, name, inst, keys):
        pending, latest = [], self.latest.setdefault(name, {})

        def one(k):
            vs = list_versions(k, inst, self.cfg)
            return vs, pick_latest(vs)

        try:
            self.log(f"Checking {len(keys)} mod(s) for updates on their sources...")
            with ThreadPoolExecutor(4) as ex:
                futs = {ex.submit(one, k): k for k in keys}
                for fut in as_completed(futs):
                    k = futs[fut]
                    try:
                        vs, best = fut.result()
                    except Exception as e:
                        self.log(f"[{self._mod_label(k)}] update check failed: {e}")
                        continue
                    if not best:
                        latest[k] = "?"
                        continue
                    cur = installed_vid(k, inst["mods"].get(k, {}))
                    ids = [v["id"] for v in vs]
                    # newer only if the installed one is not already at/above the latest release
                    newer = cur not in ids or ids.index(cur) > ids.index(best["id"])
                    latest[k] = ("update: " + best["label"]) if newer else "up to date"
                    if newer:
                        pending.append((k, best["label"]))
        finally:
            self.busy = False
            self.after(0, lambda: self._updates_done(name, pending))

    def _updates_done(self, name, pending):
        self._refresh_list()
        if not pending:
            self.log("Everything is up to date.")
            return
        lines = [f"  {self._names.get(k) or self.inst['mods'].get(k, {}).get('name') or k}  ->  {lab}"
                 for k, lab in pending[:15]]
        if messagebox.askyesno("Updates available",
                               f"{len(pending)} update(s) available:\n\n" + "\n".join(lines)
                               + ("\n  ..." if len(pending) > 15 else "") + "\n\nUpdate them now?",
                               parent=self):
            self.update_mods(only=[k for k, _ in pending])

    def show_versions(self):
        sel = [k for k in self.tree.selection() if k in self.inst["mods"]]
        if len(sel) != 1:
            messagebox.showinfo("Versions", "Select exactly one tracked mod in the list first.", parent=self)
            return
        if self.busy or self.game_proc:
            self.log("Busy or game running — wait.")
            return
        key, inst = sel[0], self.inst
        info = inst["mods"][key]
        cur = installed_vid(key, info)
        win = tk.Toplevel(self)
        win.title(f"Versions - {info.get('name') or key}")
        win.geometry("820x420")
        win.configure(bg=PAL["bg"])
        win.transient(self)
        status = ttk.Label(win, text=f"Loading versions from {mod_source(key)} "
                                     f"({inst['loader']} {inst['mc_version'] or 'any MC'})...")
        status.pack(anchor="w", padx=8, pady=6)
        cols = ("version", "channel", "date", "mc", "file")
        frame = ttk.Frame(win)
        frame.pack(fill="both", expand=True, padx=8)
        vt = ttk.Treeview(frame, columns=cols, show="headings", selectmode="browse")
        for c, w in zip(cols, (170, 70, 90, 150, 300)):
            vt.heading(c, text=c)
            vt.column(c, width=w)
        vsb = ttk.Scrollbar(frame, orient="vertical", command=vt.yview)
        vt.configure(yscrollcommand=vsb.set)
        vsb.pack(side="right", fill="y")
        vt.pack(side="left", fill="both", expand=True)

        def fill(versions, err=None):
            if not win.winfo_exists():
                return
            if err:
                status.configure(text=f"Couldn't load versions: {err}")
                return
            status.configure(text=f"{len(versions)} version(s) found. Installed: {info.get('tag') or '-'}")
            for v in versions:
                fn = v.get("file_name") or (v["files"][0]["name"] if v.get("files") else "")
                mark = "  (installed)" if v["id"] == cur else ""
                vt.insert("", "end", iid=v["id"], values=(
                    v["label"] + mark, v["channel"], (v["date"] or "")[:10],
                    ", ".join(v.get("mc") or [])[:40], fn))
            if cur and vt.exists(cur):
                vt.selection_set(cur)
                vt.see(cur)

        def load():
            try:
                vs = list_versions(key, inst, self.cfg)
                self.after(0, lambda: fill(vs))
            except Exception as e:
                self.after(0, lambda err=str(e): fill([], err))

        threading.Thread(target=load, daemon=True).start()

        def install():
            s = vt.selection()
            if not s:
                return
            if s[0] == cur:
                messagebox.showinfo("Versions", "That version is already installed.", parent=win)
                return
            win.destroy()
            self.update_mods(only=[key], pins={key: s[0]})

        btns = ttk.Frame(win)
        btns.pack(fill="x", padx=8, pady=8)
        ttk.Button(btns, text="Install selected version", command=install).pack(side="left")
        ttk.Button(btns, text="Close", command=win.destroy).pack(side="right")
        vt.bind("<Double-1>", lambda _e: install())

    def export_modlist(self):
        inst = self.inst
        rows = list_mod_rows(inst)
        lines = [f"# {self.cfg['active']} - {inst['loader']} {inst['mc_version'] or '?'} - {len(rows)} jar(s)", ""]
        for r in rows:
            lines.append(f"{r['file']}  |  {r['name']} {r['version'] if r['version'] != '-' else ''}".rstrip()
                         + f"  |  {r['source']}  |  {r['role']}")
        text = "\n".join(lines) + "\n"
        path = os.path.join(self._game_dir(), "modlist.txt")
        try:
            os.makedirs(os.path.dirname(path), exist_ok=True)
            with open(path, "w", encoding="utf-8") as f:
                f.write(text)
            self.clipboard_clear()
            self.clipboard_append(text)
            self.log(f"Mod list ({len(rows)} jars) saved to {path} and copied to the clipboard.")
        except Exception as e:
            self.log(f"Could not save the mod list: {e}")

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
    def prepare(self, inst, name, run=True):
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

        if not run:  # install/prepare only; no login needed
            return None, game_dir

        # Microsoft auth is required
        acc = ensure_fresh_account(self.cfg, log)
        if not acc:
            raise RuntimeError("Not logged in. Press 'Log in with Microsoft' first.")
        user = acc["name"]
        auth_uuid = acc["uuid"]
        access_token = acc["access_token"]
        user_type = "msa"
        xuid = acc.get("xuid") or ""
        log(f"Launching as {user}")

        cp = CP_SEP.join([os.path.join(rt, "libraries", l["path"]) for l in libs] + [jar])
        v = {"auth_player_name": user, "version_name": jar_id, "game_directory": game_dir,
             "assets_root": assets_root, "assets_index_name": ver["assetIndex"]["id"],
             "auth_uuid": auth_uuid, "auth_access_token": access_token, "auth_session": access_token,
             "clientid": "", "auth_xuid": xuid, "user_type": user_type, "user_properties": "{}",
             "version_type": ver.get("type", "release"), "natives_directory": ndir,
             "launcher_name": "ghmodlauncher", "launcher_version": LAUNCHER_VERSION, "classpath": cp,
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
        extra_game = []
        w, h = (inst.get("window_width") or "").strip(), (inst.get("window_height") or "").strip()
        if w.isdigit() and h.isdigit():
            extra_game += ["--width", w, "--height", h]
        if inst.get("fullscreen"):
            extra_game.append("--fullscreen")
        extra_game += shlex.split(inst.get("game_args") or "", posix=(OS_NAME != "windows"))
        cmd = [java, f"-Xmx{mem}M"] + shlex.split(inst["jvm_args"], posix=(OS_NAME != "windows"))
        if log_arg:
            cmd.append(subst(log_arg, v))
        cmd += [subst(a, v) for a in jvm] + [ver["mainClass"]] + [subst(a, v) for a in game] + extra_game
        return cmd, game_dir

    def play(self):
        i = self.inst
        if i["launch_cmd"].strip():
            self._external_launch()
        elif i.get("launcher") == "official":
            self.start_official()
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
            cmd, cwd = self.prepare(inst, name, run)
            if not run:
                self.log("Ready. Press PLAY.")
                return
            env = {**os.environ, **parse_env_vars(inst.get("env_vars"))}
            self._run_pre_launch(inst, name, cwd, env)
            self.log("Starting game...")
            self.game_proc = subprocess.Popen(cmd, cwd=cwd, env=env, stdout=subprocess.PIPE,
                                              stderr=subprocess.STDOUT, text=True, errors="replace",
                                              creationflags=NO_WINDOW)
            threading.Thread(target=self._pump, args=(self.game_proc,), daemon=True).start()
        except Exception as e:
            self.log(f"ERROR: {e}")
        finally:
            self.busy = False

    def _run_pre_launch(self, inst, name, cwd, env):
        pre = (inst.get("pre_launch_cmd") or "").strip()
        if not pre:
            return
        for k, val in (("{game_dir}", cwd), ("{mods_dir}", inst["mods_dir"]),
                       ("{mc_version}", inst["mc_version"]), ("{loader}", inst["loader"]),
                       ("{name}", name)):
            pre = pre.replace(k, val)
        self.log(f"Running pre-launch: {pre}")
        r = subprocess.run(shlex.split(pre, posix=(OS_NAME != "windows")), cwd=cwd, env=env,
                           creationflags=NO_WINDOW)
        if r.returncode != 0:
            raise RuntimeError(f"Pre-launch command exited with code {r.returncode}; not starting.")

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
                             env={**os.environ, **parse_env_vars(i.get("env_vars")),
                                  "GHML_GAME_DIR": self._game_dir()},
                             creationflags=NO_WINDOW)
            self.log(f"Launched external: {cmd}")
        except Exception as e:
            self.log(f"Launch failed: {e}")

    def _check_official_ready(self):
        """Main-thread checks shared by 'Sync' and official-launcher play. Returns official root or None."""
        path = official_profiles_path()
        if not os.path.exists(path):
            messagebox.showerror("Not found", f"{path} doesn't exist. Run the official launcher once first.")
            return None
        root = os.path.dirname(path)
        if os.path.abspath(self.cfg["runtime_dir"] or os.path.join(BASE, "runtime")) != os.path.abspath(root):
            if not messagebox.askyesno("Runtime dir",
                                       f"The official launcher only sees loaders installed in:\n{root}\n\n"
                                       "Switch the runtime dir to that folder now?"):
                return None
            self.runtime_var.set(root)  # saved via trace
            self.cfg["runtime_dir"] = root
        return root

    def _write_official_profile(self, inst, name, game_dir):
        """Add/update this instance as a profile in the official launcher's launcher_profiles.json."""
        path = official_profiles_path()
        shutil.copy2(path, path + ".ghml.bak")
        with open(path) as f:
            data = json.load(f)
        now = datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%S.000Z")
        profs = data.setdefault("profiles", {})
        key = "ghml-" + re.sub(r"\W+", "-", name.lower())
        p = profs.get(key, {"created": now})
        try:
            mem = int(inst["memory_mb"])
        except ValueError:
            mem = 4096
        p.update({"name": f"GHML: {name}", "type": "custom", "icon": "Furnace", "lastUsed": now,
                  "gameDir": game_dir, "lastVersionId": inst["version_id"],
                  "javaArgs": f"-Xmx{mem}M " + (inst.get("jvm_args") or "").strip()})
        if inst.get("java_path"):
            p["javaDir"] = inst["java_path"]
        else:
            p.pop("javaDir", None)
        w, h = (inst.get("window_width") or "").strip(), (inst.get("window_height") or "").strip()
        if w.isdigit() and h.isdigit():
            p["resolution"] = {"width": int(w), "height": int(h)}
        else:
            p.pop("resolution", None)
        profs[key] = p
        with open(path, "w") as f:
            json.dump(data, f, indent=2)
        self.log(f"Profile 'GHML: {name}' written (backup: {path}.ghml.bak).")

    def start_official(self):
        """PLAY via the official Minecraft launcher: install loader, write profile, start launcher."""
        if self.busy or self.game_proc:
            self.log("Busy or game already running.")
            return
        self._save_settings()
        if not self.inst["mc_version"].strip():
            messagebox.showinfo("MC version", "Set a Minecraft version first.")
            return
        if not find_official_launcher(self.cfg):
            messagebox.showerror("Launcher not found",
                                 "Couldn't find the official Minecraft launcher.\n"
                                 "Set 'Official launcher path' in Settings.")
            return
        if not self._check_official_ready():
            return
        if not messagebox.askyesno("Close the launcher",
                                   "The official launcher must be closed or it may overwrite the profile. "
                                   "Continue?"):
            return
        self.busy = True
        threading.Thread(target=self._official_worker, args=(self.inst, self.cfg["active"]), daemon=True).start()

    def _official_worker(self, inst, name):
        try:
            log = self.log
            rt = self.cfg["runtime_dir"] or os.path.join(BASE, "runtime")
            mc = inst["mc_version"].strip()
            os.makedirs(rt, exist_ok=True)
            log(f"Preparing '{name}' for the official launcher: {inst['loader']} on Minecraft {mc}")
            vjson = ensure_vanilla(rt, mc, log)
            java = resolve_java(inst, rt, vjson, log)
            vid = ensure_loader(inst, rt, java, log)
            save_config(self.cfg)
            self.after(0, lambda: self._set_version_var(vid) if self.cfg["active"] == name else None)
            game_dir = _game_dir_from_mods(inst["mods_dir"])
            os.makedirs(os.path.join(game_dir, "mods"), exist_ok=True)
            self._write_official_profile(inst, name, game_dir)
            env = {**os.environ, **parse_env_vars(inst.get("env_vars"))}
            self._run_pre_launch(inst, name, game_dir, env)
            cmd = find_official_launcher(self.cfg)
            if not cmd:
                raise RuntimeError("Official Minecraft launcher not found.")
            subprocess.Popen(cmd, env=env, **({"start_new_session": True} if OS_NAME != "windows"
                                              else {"creationflags": NO_WINDOW}))
            log(f"Started the official launcher. Pick the profile 'GHML: {name}' and press Play.")
        except Exception as e:
            self.log(f"ERROR: {e}")
        finally:
            self.busy = False

    def sync_profile(self):
        """Add/update a profile in the official launcher (gives you real Microsoft login)."""
        i, name = self.inst, self.cfg["active"]
        if not self._check_official_ready():
            return
        if not i["version_id"]:
            messagebox.showinfo("Not installed", "Press 'Install / prepare' first so the loader exists.")
            return
        if not messagebox.askyesno("Close the launcher", "The official launcher must be closed or it may overwrite this. Continue?"):
            return
        try:
            self._write_official_profile(i, name, self._game_dir())
            self.log("Open the official launcher and pick the profile.")
        except Exception as e:
            self.log(f"Profile sync failed: {e}")


def _no_console():
    """Windows: don't keep a console window attached to the launcher.
    If we were started with python.exe (what opens a console), restart under pythonw.exe and exit,
    so the window closes (or, if you ran us from your own terminal, the prompt comes back).
    With no console at all, send print()/tracebacks to ~/.ghmodlauncher/launcher.log.
    Set GHML_NO_RELAUNCH=1 to keep the console, e.g. for debugging."""
    if OS_NAME == "windows" and not os.environ.get("GHML_NO_RELAUNCH"):
        exe = sys.executable or ""
        if os.path.basename(exe).lower() in ("python.exe", "python3.exe"):
            cands = [os.path.join(os.path.dirname(exe), "pythonw.exe"), shutil.which("pythonw")]
            for pyw in dict.fromkeys(c for c in cands if c):
                try:
                    subprocess.Popen([pyw, os.path.abspath(sys.argv[0])] + sys.argv[1:],
                                     env={**os.environ, "GHML_NO_RELAUNCH": "1"},
                                     creationflags=0x00000008 | 0x00000200)  # DETACHED | NEW_PROCESS_GROUP
                except OSError:
                    continue
                sys.exit(0)
    if sys.stdout is None or sys.stderr is None:
        try:
            os.makedirs(BASE, exist_ok=True)
            logf = open(os.path.join(BASE, "launcher.log"), "a", encoding="utf-8", buffering=1)
            if sys.stdout is None:
                sys.stdout = logf
            if sys.stderr is None:
                sys.stderr = logf
        except OSError:
            pass


if __name__ == "__main__":
    _no_console()
    try:
        ensure_pillow()  # auto-install Pillow once if missing (glass UI); no manual pip
        App().mainloop()
    except tk.TclError as e:
        sys.exit(f"Tk failed to start (no display?): {e}")
