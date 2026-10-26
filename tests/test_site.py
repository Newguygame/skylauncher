from pathlib import Path
from playwright.sync_api import sync_playwright
ROOT=Path(__file__).resolve().parents[1] / 'docs'
HTML='http://127.0.0.1:8765/index.html'

MOCK={
 'tag_name':'v3.2.1',
 'assets':[
  {'name':'SkyLauncherSetup-3.2.1.exe','browser_download_url':'https://github.com/Newguygame/skylauncher/releases/download/v3.2.1/SkyLauncherSetup-3.2.1.exe'},
  {'name':'SkyLauncherSetup-3.2.1.dmg','browser_download_url':'https://github.com/Newguygame/skylauncher/releases/download/v3.2.1/SkyLauncherSetup-3.2.1.dmg'},
  {'name':'skylauncher-setup_3.2.1_amd64.deb','browser_download_url':'https://github.com/Newguygame/skylauncher/releases/download/v3.2.1/skylauncher-setup_3.2.1_amd64.deb'},
  {'name':'SkyLauncher.jar','browser_download_url':'https://github.com/Newguygame/skylauncher/releases/download/v3.2.1/SkyLauncher.jar'},
 ]
}
with sync_playwright() as p:
 browser=p.chromium.launch(headless=True,executable_path='/usr/bin/chromium',args=['--no-sandbox'])
 for width in [1440,390]:
  page=browser.new_page(viewport={'width':width,'height':950},device_scale_factor=1)
  errors=[]
  page.on('pageerror', lambda err: errors.append(str(err)))
  page.route('https://api.github.com/repos/Newguygame/skylauncher/releases/latest',lambda route: route.fulfill(json=MOCK,status=200,headers={'access-control-allow-origin':'*'}))
  page.goto(HTML,wait_until='load')
  page.wait_for_function("document.querySelector('#release-pill').textContent.includes('v3.2.1')")
  for os,extension in [('windows','.exe'),('macos','.dmg'),('linux','.deb')]:
   href=page.locator(f'[data-download="{os}"]').get_attribute('href')
   assert href.endswith(extension),(os,href)
  assert page.locator('#portable-link').get_attribute('href').endswith('.jar')
  assert page.evaluate('document.documentElement.scrollWidth <= window.innerWidth'), 'Overflow'
  assert not errors, errors
  page.screenshot(path=str(Path(__file__).parent / f'preview-{width}.png'),full_page=True)
  print(width, 'mock release downloads OK, no JS errors, no horizontal overflow')
  page.close()
 # release with no matching assets
 page=browser.new_page()
 page.route('https://api.github.com/repos/Newguygame/skylauncher/releases/latest',lambda route: route.fulfill(json={'tag_name':'v1','assets':[]},status=200,headers={'access-control-allow-origin':'*'}))
 page.goto(HTML)
 page.wait_for_function("document.querySelector('#release-pill').textContent.includes('v1')")
 assert page.locator('.download-button.disabled').count()==3
 print('missing installers: safely disabled')
 page.close()
 # API not available
 page=browser.new_page()
 page.route('https://api.github.com/repos/Newguygame/skylauncher/releases/latest',lambda route: route.fulfill(body='Not found',status=404,headers={'access-control-allow-origin':'*'}))
 page.goto(HTML)
 page.wait_for_function("document.querySelector('#release-status').textContent.includes('Could not')")
 assert page.locator('#retry').is_visible()
 print('missing release API: fallback/retry shown')
 browser.close()
