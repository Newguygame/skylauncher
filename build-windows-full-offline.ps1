param([switch]$TestInstaller)

$ErrorActionPreference = 'Stop'
Set-Location $PSScriptRoot

foreach ($old in @('build\launcher', 'build\classes', 'build\windows-app\SkyLauncher')) {
  if (Test-Path $old) { Remove-Item -Recurse -Force $old }
}

New-Item -ItemType Directory -Force -Path 'build\launcher', 'build\classes', 'input', 'dist' | Out-Null
javac --release 17 -encoding UTF-8 -d 'build\classes' 'src\SkyLauncher.java'
if ($LASTEXITCODE -ne 0) { throw 'Could not build SkyLauncher.' }
Get-ChildItem 'build\classes' -Filter 'SkyLauncher*.class' | Copy-Item -Destination 'build\launcher' -Force
if (Test-Path 'icons\SkyLauncher.png') {
  New-Item -ItemType Directory -Force -Path 'build\launcher\icons' | Out-Null
  Copy-Item 'icons\SkyLauncher.png' 'build\launcher\icons\' -Force
}
jar --create --file 'input\SkyLauncher.jar' --main-class SkyLauncher -C 'build\launcher' .
if ($LASTEXITCODE -ne 0) { throw 'Could not package SkyLauncher.' }
java -jar 'input\SkyLauncher.jar' --selftest
if ($LASTEXITCODE -ne 0) { throw 'SkyLauncher checks failed.' }

$jpackageArgs = @('--type', 'app-image', '--name', 'SkyLauncher', '--app-version', '3.1.2', '--input', 'input', '--main-jar', 'SkyLauncher.jar', '--main-class', 'SkyLauncher', '--dest', 'build/windows-app', '--vendor', 'SkyLauncher')
if (Test-Path 'icons\SkyLauncher.ico') { $jpackageArgs += @('--icon', 'icons\SkyLauncher.ico') }
& jpackage @jpackageArgs
if ($LASTEXITCODE -ne 0) { throw 'Could not build the Windows application.' }
if (!(Test-Path 'build\windows-app\SkyLauncher\SkyLauncher.exe')) { throw 'The Windows application is missing.' }
if (!(Test-Path 'build\windows-app\SkyLauncher\runtime\release')) { throw 'Bundled Java runtime is missing.' }

$iscc = Join-Path ${env:ProgramFiles(x86)} 'Inno Setup 6\ISCC.exe'
if (!(Test-Path $iscc)) { throw 'Inno Setup 6 is required to build the Windows installer.' }
& $iscc 'windows-installer\SkyLauncher.iss'
if ($LASTEXITCODE -ne 0) { throw 'Could not create the Windows installer.' }
$output = 'dist\SkyLauncher-3.1.2.exe'
if (!(Test-Path $output)) { throw 'Windows installer was not created.' }

if ($TestInstaller) { & 'tests\test_windows_installer.ps1' -SetupPath $output }
Write-Host "Installer is ready: $output"
