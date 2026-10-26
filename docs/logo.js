// Optional custom artwork: upload docs/icon.png to display it in the reserved spots.
// With no image, the spots intentionally stay blank (no broken image icon).
(() => {
  for (const img of document.querySelectorAll('.custom-logo-slot img')) {
    img.addEventListener('load', () => { img.hidden = false; img.parentElement.classList.add('has-logo'); });
    img.addEventListener('error', () => { img.hidden = true; img.parentElement.classList.remove('has-logo'); });
    if (img.complete && img.naturalWidth) { img.hidden = false; img.parentElement.classList.add('has-logo'); }
  }
  // No favicon reference until the user supplies their own file.
  const probe = new Image();
  probe.onload = () => {
    const favicon = document.createElement('link');
    favicon.rel = 'icon'; favicon.type = 'image/png'; favicon.href = './icon.png';
    document.head.append(favicon);
  };
  probe.src = './icon.png';
})();
