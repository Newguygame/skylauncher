# Run on Windows with JDK 21+ and WiX Toolset available to jpackage.
# Creates an EXE with SkyLauncher AND a private Java runtime: no downloads during installation.
$ErrorActionPreference = 'Stop'
Set-Location $PSScriptRoot
New-Item -ItemType Directory -Force -Path 'build\launcher','build\classes','input','dist' | Out-Null
javac --release 17 -encoding UTF-8 -d 'build\classes' 'src\SkyLauncher.java'
if ($LASTEXITCODE -ne 0) { throw 'Java compilation failed' }
Get-ChildItem 'build\classes' -Filter 'SkyLauncher*.class' | Copy-Item -Destination 'build\launcher' -Force
if (Test-Path 'build\launcher\SkyLauncherSetup.class') { Remove-Item 'build\launcher\SkyLauncherSetup.class' }
if (Test-Path 'icons\SkyLauncher.png') { New-Item -ItemType Directory -Force -Path 'build\launcher\icons' | Out-Null; Copy-Item 'icons\SkyLauncher.png' 'build\launcher\icons\' }
jar --create --file 'input\SkyLauncher.jar' --main-class SkyLauncher -C 'build\launcher' .
if ($LASTEXITCODE -ne 0) { throw 'JAR packaging failed' }
java -jar 'input\SkyLauncher.jar' --selftest
if ($LASTEXITCODE -ne 0) { throw 'Self test failed' }
$args = @('--type','exe','--name','SkyLauncher','--app-version','3.1.0','--input','input','--main-jar','SkyLauncher.jar','--main-class','SkyLauncher','--dest','dist','--vendor','SkyLauncher','--win-shortcut','--win-menu','--description','SkyLauncher offline installer with bundled Java')
if (Test-Path 'icons\SkyLauncher.ico') { $args += @('--icon','icons\SkyLauncher.ico') }
jpackage @args
if ($LASTEXITCODE -ne 0) { throw 'jpackage failed. Check the JDK and WiX setup.' }
Write-Host 'Installer created in dist\ with bundled Java and no install-time download.'
