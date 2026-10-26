#define AppVersion "3.1.1"

[Setup]
AppId={{35B235D1-9B2D-4E12-BE7C-E567038F234F}
AppName=SkyLauncher TEST
AppVersion={#AppVersion}
AppPublisher=SkyLauncher
DefaultDirName={localappdata}\Programs\SkyLauncher-TEST
DefaultGroupName=SkyLauncher TEST
PrivilegesRequired=lowest
ArchitecturesAllowed=x64compatible
ArchitecturesInstallIn64BitMode=x64compatible
WizardStyle=modern
Compression=lzma2
SolidCompression=yes
OutputDir=..\dist
OutputBaseFilename=SkyLauncher-{#AppVersion}-TEST
UninstallDisplayIcon={app}\SkyLauncher.exe
UsePreviousTasks=no

[Tasks]
Name: "desktopicon"; Description: "Create a desktop shortcut"; GroupDescription: "Shortcuts:"

[Files]
Source: "..\build\windows-app\SkyLauncher\*"; DestDir: "{app}"; Flags: ignoreversion recursesubdirs createallsubdirs

[Icons]
Name: "{autoprograms}\SkyLauncher TEST"; Filename: "{app}\SkyLauncher.exe"; WorkingDir: "{app}"
Name: "{autodesktop}\SkyLauncher TEST"; Filename: "{app}\SkyLauncher.exe"; WorkingDir: "{app}"; Tasks: desktopicon

[Run]
Filename: "{app}\SkyLauncher.exe"; Description: "Launch SkyLauncher"; Flags: postinstall nowait skipifsilent
