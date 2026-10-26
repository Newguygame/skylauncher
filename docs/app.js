/* SkyLauncher downloads — no framework, dependencies, or build step needed. */
(() => {
  'use strict';
  const OWNER = 'Newguygame';
  const REPO = 'skylauncher';
  const RELEASES = `https://github.com/${OWNER}/${REPO}/releases`;
  const API = `https://api.github.com/repos/${OWNER}/${REPO}/releases/latest`;
  const $ = selector => document.querySelector(selector);
  const link = asset => asset.browser_download_url;

  // The main buttons always download installers, not manual application packages.
  const universal = {
    macos: 'SkyLauncher-Universal-macOS-Installer.zip',
    linux: 'SkyLauncher-Universal-Linux-Installer.zip',
  };

  function detectOS() {
    const platform = String(navigator.userAgentData?.platform || navigator.platform || '').toLowerCase();
    const agent = navigator.userAgent.toLowerCase();
    if (/windows|win32|win64/.test(platform) || agent.includes('windows')) return 'windows';
    if (/mac|darwin/.test(platform) || agent.includes('macintosh')) return 'macos';
    if (/linux|x11/.test(platform) || agent.includes('linux')) return 'linux';
    return null;
  }

  function isSafeAsset(asset) {
    try {
      const url = new URL(asset.browser_download_url);
      return url.protocol === 'https:' && url.hostname === 'github.com' &&
        url.pathname.startsWith(`/` + OWNER + `/` + REPO + `/releases/download/`) &&
        !asset.name.includes('/') && !asset.name.includes('\\');
    } catch { return false; }
  }

  function candidates(assets, os) {
    const safe = assets.filter(a => a && typeof a.name === 'string' && isSafeAsset(a));
    if (os === 'macos' || os === 'linux') {
      // Do not silently substitute a direct DMG, DEB or portable archive.
      return safe.filter(a => a.name === universal[os]);
    }
    if (os === 'windows') {
      // Versioned SkyLauncher Windows EXE files are complete installers.
      return safe.filter(a => {
        const name = a.name.toLowerCase();
        return /^skylauncher(?:[-_.].*)?\.exe$/.test(name) &&
          (/^skylauncher-\d+\.\d+(?:\.\d+)?\.exe$/.test(name) ||
           /(?:setup|installer|install)/.test(name)) &&
          !/(?:debug|source|portable|arm64|aarch64|linux|macos)/.test(name);
      }).sort((a, b) => {
        const preference = asset => /^skylauncher-\d+\.\d+(?:\.\d+)?\.exe$/i.test(asset.name) ? 1 : 0;
        return preference(b) - preference(a);
      });
    }
    return [];
  }

  function setButton(os, asset) {
    const button = $(`[data-download="${os}"]`);
    const alternatives = $(`[data-alternates="${os}"]`);
    button.classList.remove('disabled');
    button.removeAttribute('aria-disabled');
    button.removeAttribute('tabindex');
    button.href = link(asset);
    button.textContent = {
      windows: 'Download Windows installer ↓',
      macos: 'Download Mac installer (.zip) ↓',
      linux: 'Download Linux installer (.zip) ↓',
    }[os];
    button.title = asset.name;
    alternatives.replaceChildren();
  }

  function setUnavailable(os) {
    const button = $(`[data-download="${os}"]`);
    button.classList.add('disabled');
    button.setAttribute('aria-disabled','true');
    button.setAttribute('tabindex','-1');
    button.removeAttribute('download');
    button.href = RELEASES;
    button.textContent = 'Not released yet';
    $(`[data-alternates="${os}"]`).replaceChildren();
  }

  function render(release) {
    if (!release || !Array.isArray(release.assets)) throw Error('Invalid release response');
    const version = String(release.tag_name || release.name || 'latest');
    $('#release-pill').textContent = 'Latest release: ' + version;
    $('#release-dot').className = 'status-dot ready';
    $('#release-status').textContent = 'Latest published GitHub release: ' + version;
    $('#retry').hidden = true;
    const assets = release.assets;
    let available = 0;
    for (const os of ['windows', 'macos', 'linux']) {
      const choices = candidates(assets, os);
      if (choices.length) {
        setButton(os, choices[0]);
        available += 1;
        const alternatives = $(`[data-alternates="${os}"]`);
        alternatives.textContent = os === 'windows'
          ? 'Full installer · Java included'
          : 'Unzip and run the installer · Java included';
      } else setUnavailable(os);
    }
    const jars = assets.filter(a => typeof a.name === 'string' && /^skylauncher(?:[-_.].*)?\.jar$/i.test(a.name) && !/setup|source|debug/.test(a.name.toLowerCase()) && isSafeAsset(a));
    const portable = $('#portable-link');
    if (jars.length) {
      portable.href = link(jars[0]); portable.textContent = 'Portable Java version ↓';
    } else {
      portable.href = RELEASES; portable.textContent = 'Portable version / releases ↗';
    }
    $('#asset-note').textContent = available
      ? 'After downloading, run the installer. On Mac and Linux, unzip the download first.'
      : 'Installers are not available in this release yet. See All releases for other downloads.';
  }

  function setError(error) {
    for (const os of ['windows', 'macos', 'linux']) setUnavailable(os);
    $('#release-pill').textContent = 'No installer release available';
    $('#release-dot').className = 'status-dot offline';
    $('#release-status').textContent = 'Could not load a published release from GitHub.';
    $('#asset-note').textContent = 'Could not check downloads. Try again or open All releases.';
    $('#retry').hidden = false;
    console.info('SkyLauncher release lookup:', error.message);
  }

  async function load() {
    $('#retry').hidden = true;
    $('#release-status').textContent = 'Checking the latest GitHub release…';
    try {
      const controller = new AbortController();
      const timeout = setTimeout(() => controller.abort(), 10000);
      let response;
      try { response = await fetch(API, {headers: {'Accept': 'application/vnd.github+json'}, signal: controller.signal, cache: 'no-store'}); }
      finally { clearTimeout(timeout); }
      if (!response.ok) throw Error('GitHub HTTP ' + response.status);
      render(await response.json());
    } catch (error) { setError(error); }
  }

  const os = detectOS();
  if (os) {
    const card = $(`.platform-card[data-os="${os}"]`);
    if (card) { card.classList.add('recommended'); card.querySelector('.recommend').hidden = false; }
    const label = {windows: 'Download for Windows', macos: 'Download for macOS', linux: 'Download for Linux'};
    $('#hero-download-label').textContent = label[os];
  }
  $('#retry').addEventListener('click', load);
  load();
})();
