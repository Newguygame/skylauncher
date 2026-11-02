# SkyLauncher 3.1.3 — public CurseForge relay (Pelican)

The VPS relay is shared by all SkyLauncher users. **No client-side token is required.** Keep the genuine CurseForge developer API key on the VPS only.

## Update your existing Pelican Python Generic server

1. Stop the Python server in Pelican.
2. Back up your *existing* `config.json` (do not share it). Keep the existing HTTPS certificate and private key (`bridge-cert.pem` and `bridge-key.pem`) in the same directory.
3. Replace only `server.py` with the `server.py` in this archive.
4. In your private `config.json`, preserve your real `CURSEFORGE_API_KEY`, set `SKYLAUNCHER_PUBLIC_ACCESS` to `true`, `SKYLAUNCHER_BIND` to `0.0.0.0`, and set `SKYLAUNCHER_PORT` to `25568` or your allocated port. The old `SKYLAUNCHER_ACCESS_TOKEN` is not needed in public mode and may be removed. **Never publish `config.json` or `bridge-key.pem`**.
5. Start `server.py`. The console should say `CurseForge relay listening securely (HTTPS)` and `(public=True)`.
6. Verify `https://185.83.154.171:25568/health` using the trusted certificate (a self-signed certificate will trigger a warning in a generic browser; do not disable certificate verification). Example from a machine with the public cert:
   ```bash
   curl --cacert bridge-cert.pem https://185.83.154.171:25568/health
   curl --cacert bridge-cert.pem 'https://185.83.154.171:25568/v1/search?q=create&version=1.21.1&loader=neoforge'
   ```
7. Test the public Java launcher separately. It pins `bridge-cert.pem` and validates the VPS IP.

## Security and limits

- The **API key remains server-side**; no bearer token or user API key is needed in SkyLauncher.
- Direct HTTPS with TLS server-certificate validation remains mandatory. The server won't fall back to HTTP.
- This bridge exposes fixed endpoints and checks CurseForge distribution permissions. It never accepts arbitrary download URLs.
- Rate limits are currently **300 requests per minute per source IP**, plus **3,000 downloads per hour per IP**, with at most **4 concurrent downloads** across all users. Rate limits are in-memory and reset on service restart. Shared NAT addresses share the per-IP quota.
- Approved download hosts and maximum file sizes are restricted. These controls reduce but do **not** eliminate abuse or bandwidth costs; a fully public service can still be accessed by people not using SkyLauncher.
- Public use must comply with your CurseForge API key's approval, quotas, and third-party distribution terms. Distribution-disabled mods cannot be downloaded via this service. Do not cache API-derived metadata without confirming that your contract permits it.
- If your old shared relay token was exposed, remove/rotate it; the new public relay never requires it.
- The existing TLS private key stays on Pelican; never upload it to GitHub. The public certificate expires in November 2028 and needs a coordinated replacement then.

## Package contents

- `server.py`: public relay code, Python 3.10+ standard library only
- `config.example.json`: placeholder settings, **not a live config**
- `bridge-cert.pem`: public certificate (safe to distribute, unlike `bridge-key.pem`)
- `test_public_bridge.py`: mocked unit tests, runnable with `python test_public_bridge.py`

**Keep SkyLauncher at version 3.1.3** unless you explicitly request an update or align an actual GitHub release.

## Large modpacks and higher quotas

The download quota counts successful and attempted **download requests per hour per source IP**; it does not limit the number of mods in a pack. The defaults allow a large 500–1,000-mod pack provided the project files are available and the upstream CurseForge quota permits it. Users behind the same NAT share the quota.

Optional `config.json` settings (environment variables take precedence):

```json
"SKYLAUNCHER_REQUESTS_PER_MINUTE": 300,
"SKYLAUNCHER_DOWNLOADS_PER_HOUR": 3000,
"SKYLAUNCHER_SIMULTANEOUS_DOWNLOADS": 4
```

Allowed ranges: 1–1,200 requests/min/IP; 1–10,000 downloads/hour/IP; 1–16 simultaneous downloads globally. These are public-bridge safeguards, **not** extra CurseForge API allowances. Larger values can cost significant traffic and cause CurseForge 429 rate limiting. Keep the concurrency small if VPS bandwidth is limited. Restart Pelican after editing the configuration.
