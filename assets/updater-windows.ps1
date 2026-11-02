# SkyLauncher native/portable Windows updater. Runs after the main application exits.
param(
  [Parameter(Mandatory=$true)][ValidateSet('portable-jar','win-installer')][string]$Mode,
  [Parameter(Mandatory=$true)][string]$Package,
  [Parameter(Mandatory=$true)][string]$Target,
  [string]$Launcher='',
  [Parameter(Mandatory=$true)][string]$Java,
  [Parameter(Mandatory=$true)][int]$ParentPid
)
$ErrorActionPreference='Stop'
try {
  for($i=0;$i -lt 240;$i++) {
    if(-not (Get-Process -Id $ParentPid -ErrorAction SilentlyContinue)) {break}
    Start-Sleep -Milliseconds 500
  }
  if(Get-Process -Id $ParentPid -ErrorAction SilentlyContinue) {throw 'SkyLauncher did not close within two minutes.'}
  if($Mode -eq 'portable-jar') {
    $backup=Join-Path (Split-Path -Parent $Package) 'previous.jar'
    Move-Item -LiteralPath $Target -Destination $backup -Force
    try {
      Move-Item -LiteralPath $Package -Destination $Target -Force
    } catch {
      Move-Item -LiteralPath $backup -Destination $Target -Force
      throw
    }
    Remove-Item -LiteralPath $backup -Force -ErrorAction SilentlyContinue
    Start-Process -FilePath $Java -ArgumentList @('-jar',('"'+$Target+'"'))
  } else {
    # Same Inno Setup AppId and installation directory: replaces the old app.
    $run=Start-Process -FilePath $Package -ArgumentList @('/SILENT','/SUPPRESSMSGBOXES','/NORESTART','/CLOSEAPPLICATIONS') -Wait -PassThru
    if($run.ExitCode -ne 0) {throw "Installer returned exit code $($run.ExitCode)."}
    if(-not (Test-Path -LiteralPath $Launcher -PathType Leaf)) {throw 'Updated launcher executable not found.'}
    Start-Process -FilePath $Launcher
  }
} catch {
  Write-Error "SkyLauncher update failed: $_"
  exit 1
}
