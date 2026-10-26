param([string]$SetupPath = 'dist\SkyLauncher-3.1.1-TEST.exe')

$ErrorActionPreference = 'Stop'
Set-Location (Split-Path -Parent $PSScriptRoot)

$setup = (Resolve-Path $SetupPath).Path
$destination = Join-Path $env:TEMP ('SkyLauncher-CI-' + [guid]::NewGuid().ToString('N'))
$log = Join-Path $env:TEMP ('SkyLauncher-Install-' + [guid]::NewGuid().ToString('N') + '.log')
$desktop = [Environment]::GetFolderPath('DesktopDirectory')
$shortcut = Join-Path $desktop 'SkyLauncher TEST.lnk'
$hadShortcut = Test-Path $shortcut
if ($hadShortcut) { throw 'An existing SkyLauncher TEST desktop shortcut was found. Use a clean Windows account.' }

try {
  $installerArgs = @('/VERYSILENT', '/SUPPRESSMSGBOXES', '/NORESTART', '/TASKS=desktopicon', "/DIR="+$destination, "/LOG="+$log)
  $p = Start-Process -FilePath $setup -ArgumentList $installerArgs -Wait -PassThru
  if ($p.ExitCode -ne 0) { throw "Installer failed with code $($p.ExitCode). Read $log" }

  $installedApp = Join-Path $destination 'SkyLauncher.exe'
  if (!(Test-Path $installedApp)) { throw 'SkyLauncher.exe was not installed.' }
  if (!(Test-Path (Join-Path $destination 'runtime\release'))) { throw 'Bundled Java runtime was not installed.' }
  if ((Test-Path $desktop) -and !(Test-Path $shortcut)) { throw 'Desktop shortcut was not created.' }

  $launched = Start-Process -FilePath $installedApp -ArgumentList '--selftest' -Wait -PassThru
  if ($launched.ExitCode -ne 0) { throw 'Installed SkyLauncher failed its self-test.' }

  $uninstall = Join-Path $destination 'unins000.exe'
  if (!(Test-Path $uninstall)) { throw 'Uninstaller is missing.' }
  $removed = Start-Process -FilePath $uninstall -ArgumentList @('/VERYSILENT', '/SUPPRESSMSGBOXES', '/NORESTART') -Wait -PassThru
  if ($removed.ExitCode -ne 0) { throw 'Uninstall failed.' }
  if (Test-Path $installedApp) { throw 'SkyLauncher.exe remained after uninstall.' }
  if ((Test-Path $desktop) -and !$hadShortcut -and (Test-Path $shortcut)) { throw 'Desktop shortcut remained after uninstall.' }
  Write-Host 'Windows installer passed: install, Java, shortcut, app selftest and uninstall.'
}
finally {
  if (Test-Path $destination) { Remove-Item -Recurse -Force $destination -ErrorAction SilentlyContinue }
  if (Test-Path $log) { Remove-Item $log -Force -ErrorAction SilentlyContinue }
}
