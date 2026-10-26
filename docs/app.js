/* SkyLauncher downloads — no framework, dependencies, or build step needed. */
(() => {
  'use strict';
  const OWNER = 'Newguygame';
  const REPO = 'skylauncher';
  const RELEASES = `https://github.com/${OWNER}/${REPO}/releases`;
  const API = `https://api.github.com/repos/${OWNER}/${REPO}/releases/latest`;
  const $ = selector => document.querySelector(selector);
  const link = asset => asset.browser_download_url;

  const formats = {
    windows: [ {ext: '.exe', label: 'Windows installer (.exe)'}, {ext: '.msi', label: 'Windows installer (.msi)'}, {ext: '.msix', label: 'Windows installer (.msix)'} ],
    macos:   [ {ext: '.dmg', label: 'macOS installer (.dmg)'}, {ext: '.pkg', label: 'macOS installer (.pkg)'} ],
    linux:   [ {ext: '.deb', label: 'Linux installer (.deb)'}, {ext: '.appimage', label: 'Linux AppImage'}, {ext: '.rpm', label: 'Linux package (.rpm)'} ],
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

  function matchesFormat(name, os) {
    const lower = name.toLowerCase();
    return formats[os].find(f => lower.endsWith(f.ext));
  }

  function scoreAsset(asset, os) {
    const name = asset.name.toLowerCase();
    // Reject other products, Java sources, dev tools, and obvious mismatches.
    if (!name.includes('skylauncher')) return -999;
    if (/source|debug|symbols|portable|src|javadoc/.test(name)) return -999;
    if (os !== 'windows' && /windows|win32|win64|win-x/.test(name)) return -999;
    if (os !== 'macos' && /macos|mac-os|darwin|osx/.test(name)) return -999;
    if (os !== 'linux' && /linux|ubuntu|debian|appimage/.test(name)) return -999;
    let score = 0;
    if (/setup|installer|install/.test(name)) score += 30;
    if (os === 'windows') {
      if (name.endsWith('.exe')) score += 30;
      if (name.endsWith('.msi')) score += 18;
      if (/windows|win64|win-x64/.test(name)) score += 10;
      if (/arm64|aarch64|x86(?![_-]?64)|i386/.test(name)) score -= 22;
    } else if (os === 'macos') {
      if (name.endsWith('.dmg')) score += 30;
      if (/universal/.test(name)) score += 15;
      if (name.endsWith('.pkg')) score += 15;
    } else {
      if (name.endsWith('.deb')) score += 30;
      if (name.endsWith('.appimage')) score += 22;
      if (name.endsWith('.rpm')) score += 12;
      if (/amd64|x86[_-]?64/.test(name)) score += 12;
      if (/arm64|aarch64|i386/.test(name)) score -= 22;
    }
    return score;
  }

  function candidates(assets, os) {
    return assets.filter(a => a && typeof a.name === 'string' && isSafeAsset(a) && matchesFormat(a.name, os))
      .map(a => ({asset: a, score: scoreAsset(a, os)})).filter(a => a.score > -999)
      .sort((a, b) => b.score - a.score).map(({asset}) => asset);
  }

  function setButton(os, asset) {
    const button = $(`[data-download="${os}"]`);
    const alternatives = $(`[data-alternates="${os}"]`);
    button.classList.remove('disabled');
    button.removeAttribute('aria-disabled');
    button.removeAttribute('tabindex');
    button.href = link(asset);
    button.textContent = 'Download ' + matchesFormat(asset.name, os).ext.toUpperCase().replace('.', '') + ' ↓';
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
    for (const os of Object.keys(formats)) {
      const choices = candidates(assets, os);
      if (choices.length) {
        setButton(os, choices[0]);
        available += 1;
        const alternatives = $(`[data-alternates="${os}"]`);
        if (choices.length > 1) {
          const details = document.createElement('details');
          const summary = document.createElement('summary');
          summary.textContent = 'Other formats (' + (choices.length - 1) + ')';
          details.appendChild(summary);
          for (const asset of choices.slice(1)) {
            const a = document.createElement('a');
            a.href = link(asset); a.textContent = asset.name;
            details.appendChild(a);
          }
          alternatives.appendChild(details);
        } else alternatives.textContent = 'Java included · GitHub hosted';
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
      ? 'Installer downloads go directly to the files published in GitHub Releases.'
      : 'This release has no Windows, macOS or Linux installer attached yet. Publish the native installer files to enable downloads.';
  }

  function setError(error) {
    for (const os of Object.keys(formats)) setUnavailable(os);
    $('#release-pill').textContent = 'No installer release available';
    $('#release-dot').className = 'status-dot offline';
    $('#release-status').textContent = 'Could not load a published release from GitHub.';
    $('#asset-note').textContent = 'No download was found. Publish a GitHub Release with installer assets, or check the releases page.';
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
